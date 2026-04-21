# Digital phenotype, phenotype summary, and edge bundles (on-device)

This document expands **`doc/architecture.md` §2.5 (Edge computation)** by separating (a) **phenotype analysis** (the **phenotype analyzer**: random forest on aligned multimodal features → **phenotype summary**) from (b) the **per-cycle edge hook** that builds a compact **`PhenotypeSummary`** for policy and optional TFLite. **Edge computation** schedules windowing, calls that edge hook (`PhenotypeAnalyzer.analyze` in code—**not** the manuscript sense of “phenotype analyzer”), optional vector inference, and intervention evaluation. The RF pathway is driven from `MainActivity` (see §5).

Paths are relative to `MobileDigitalPhenotyping/StanfordScreenomics/` unless stated otherwise.

---

## 1. Terminology (use consistently)

| Term | Meaning in this repository / manuscript alignment |
|------|---------------------------------------------------|
| **Digital phenotype** | The multimodal behavioral pattern inferred from aligned UFS-backed signals (audio level, screenshot sentiment, GPS sun score, motion step totals, and their temporal relationships). It is **not** a clinical diagnosis. |
| **Phenotype analyzer** | The **random forest** pathway: temporally aligned cache-backed features → `RandomForest.fit` → interpretation (feature importances, OOB error, R², training metadata). In code this lives in **`EnginePhenotypeStepCountRandomForest`** (module `enginePhenotype`). It is **not** the Kotlin interface also named `PhenotypeAnalyzer` in `core/.../edge/` (that interface is an edge-cycle **window-summary hook**; see below). |
| **Phenotype summary** | The **constructed final phenotype representation** after the **phenotype analyzer** (RF) runs: **ranked predictor importances** together with **model statistics** (for example out-of-bag RMSE and R², training row count, tree count, reference instant). In code this is primarily the textual report from `EnginePhenotypeStepCountRandomForest.formatTrainResultReport`, plus the compact `PhenotypeRunEnvelope.summaryOneLine` and `StepTrainingSummary` fields produced alongside training. |
| **Edge window summary (`PhenotypeSummary`)** | A small Kotlin DTO produced **every edge cycle** by the core hook `PhenotypeAnalyzer.analyze` (default `HeuristicPhenotypeAnalyzer`): per-modality **counts** in the sliding window, `windowPointCount`, and a **density-only** `stressScore` heuristic. This type is **named** `PhenotypeSummary` in code but it is **not** the manuscript’s **phenotype summary** above—it is an intervention-facing **window bundle**. |

Naming collision: `InterventionContext.phenotypeSummary` holds the **edge window summary** (`PhenotypeSummary`), not the RF **phenotype summary** report.

---

## 2. End-to-end flow (two pathways)

Both pathways respect the same data-management constraint: they consume **`CacheManager.snapshotByCacheId()`** snapshots (or lists derived from them), not raw `DataNode` streams.

```text
[Volatile caches: audio, motion, GPS, screenshot]
        │
        ├──────────────────────────────────────────────────────────┐
        │                                                          │
        ▼                                                          ▼
  Phenotype analyzer (RF)                               Edge cycle (default)
  ───────────────────────────                          ─────────────────────
  Per-modality snapshots                                Same snapshots
        → multimodal temporal alignment                 → sliding window filter
        → aligned training rows                       → recentPoints (sorted)
        → RandomForest.fit (regression on steps)      → HeuristicPhenotypeAnalyzer.analyze
        → phenotype summary                           → PhenotypeSummary (edge bundle)
           (importance ranks + OOB stats)                 │
        → optional PhenotypeRunEnvelope                 → PhenotypeFeatureEncoder → TFLite (optional)
        → EngineInterventionReceipts …                  → InterventionContext → policy
```

---

## 3. Phenotype analyzer (RF): inputs → alignment → forest → **phenotype summary**

### 3.1 Inputs

Training reads **four** volatile-cache snapshots: **audio**, **motion** (step anchors), **GPS**, and **screenshot** `UnifiedDataPoint` lists, passed into `EnginePhenotypeStepCountRandomForest.trainFromVolatileCacheSnapshots` (or `trainWithInterventionReceipt`).

### 3.2 Alignment (irregular sampling → fixed feature rows)

`MultimodalTemporalAlignment` aligns asynchronous series to **motion step anchors** using backward LOCF, forward fill, then median imputation so the forest always receives finite predictors:

```8:16:StanfordScreenomics/enginePhenotype/src/main/kotlin/com/app/enginephenotype/MultimodalTemporalAlignment.kt
/**
 * Irregular multimodal streams (different sensor cadences) are aligned to **motion step anchors**
 * using **asynchronous time series** rules:
 *
 * 1. **Backward LOCF** — for each anchor time `t`, use the latest sample of each signal with `timestamp <= t`.
 * 2. **Forward fill** — if nothing exists before `t`, use the earliest sample with `timestamp >= t` (sensor started late).
 * 3. **Median imputation** — remaining gaps (no signal yet) are filled with the column median over training rows
 *    so the random forest always receives finite inputs.
 */
```

The regression uses predictors **audio mean dB** (or RMS), **screenshot sentiment**, and **GPS sun score**, with **session step total** as the target (see class-level comment in `EnginePhenotypeStepCountRandomForest.kt`).

### 3.3 Random forest fit and **phenotype summary** contents

After `RandomForest.fit`, the **phenotype summary** aggregates:

- **Model statistics:** training rows used, tree count, out-of-bag RMSE and R² (`StepTrainingSummary` / report header).
- **Ranked importances:** `appendFeatureImportanceSection` lists each predictor with raw importance and normalized share; `topPredictorKey` picks the largest absolute weight for compact receipts.

```206:229:StanfordScreenomics/enginePhenotype/src/main/kotlin/com/app/enginephenotype/EnginePhenotypeStepCountRandomForest.kt
                buildString {
                    appendLine("Step-count RF (volatile caches, temporally aligned)")
                    appendLine("reportWallClock=${Instant.now()}")
                    append("trainRows=").append(result.summary.rowsUsed)
                    append(", trees=").append(result.summary.treeCount).appendLine()
                    appendLine(
                        String.format(
                            Locale.US,
                            "OOB RMSE=%.3f  R²=%.4f",
                            result.summary.outOfBagRmse,
                            result.summary.outOfBagR2,
                        ),
                    )
                    appendLine("referenceInstant=$tRef")
                    appendLine()
                    appendLine("Feature importance:")
                    appendFeatureImportanceSection(result.model.forest, this)
                }
```

On success, `trainWithInterventionReceipt` also builds a one-line machine-facing summary (rows, RMSE, R², top predictor) inside `PhenotypeRunEnvelope` for `EngineInterventionReceipts.acknowledgePhenotypeRun`.

---

## 4. Edge cycle: windowing → **edge window summary** (`PhenotypeSummary`)

This path runs inside `DefaultEdgeComputationEngine.runCycle` on the **same** cache-backed principle: snapshots → time filter → sorted **`recentPoints`**.

```38:45:StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/edge/DefaultEdgeComputationEngine.kt
        val snapshots = cacheManager.snapshotByCacheId()
        val flat = snapshots.values.flatten()
        val windowEnd = CachedWindowSelector.windowEndInclusive(flat, clock.instant())
        val windowDuration = VolatileCacheWindowRetention.duration()
        val windowedById = snapshots.mapValues { (_, pts) ->
            CachedWindowSelector.filterWindow(pts, windowEnd, windowDuration)
        }
        val recentPoints = windowedById.values.flatten().sortedBy { it.metadata.timestamp }
```

### 4.1 Windowing

| Concern | Type / object | Role |
|--------|----------------|------|
| Window length | `VolatileCacheWindowRetention` (`core/.../management/VolatileCacheWindowRetention.kt`) | Global sliding duration (default 30 minutes; configurable from UI via `VolatileCacheWindowPrefs`). |
| Window end | `CachedWindowSelector.windowEndInclusive` | Latest `DataDescription.timestamp` among points, or clock fallback if empty. |
| Inclusion rule | `CachedWindowSelector.filterWindow` | Keeps points with `timestamp >= windowEnd - windowDuration`. |

```13:27:StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/edge/CachedWindowSelector.kt
    fun windowEndInclusive(points: Iterable<UnifiedDataPoint>, clockFallback: Instant): Instant =
        points.maxOfOrNull { it.metadata.timestamp } ?: clockFallback

    fun filterWindow(
        points: Iterable<UnifiedDataPoint>,
        windowEnd: Instant,
        windowDuration: Duration,
    ): List<UnifiedDataPoint> {
        val cutoff = cutoffInstant(windowEnd, windowDuration)
        return points
            .filter { !it.metadata.timestamp.isBefore(cutoff) }
            .sortedBy { it.metadata.timestamp }
    }
```

**Implication:** richer analyzers could read payload keys inside each `UnifiedDataPoint`; the **stock** `HeuristicPhenotypeAnalyzer` uses only **modality** and **cardinality**, not sentiment, sun score, or audio dB.

### 4.2 Core hook named `PhenotypeAnalyzer` (`HeuristicPhenotypeAnalyzer`)

**`PhenotypeAnalyzer`** is a **Kotlin interface** on the edge engine: a pluggable **window-summary** step. Default implementation: **`HeuristicPhenotypeAnalyzer`**.

- **Interface:** `core/.../edge/PhenotypeAnalyzer.kt` — `suspend fun analyze(pointsInWindow: List<UnifiedDataPoint>): PhenotypeSummary`

**Default behavior** (edge window summary only):

1. Empty window → `PhenotypeSummary.EMPTY`.
2. Else count points per `ModalityKind` → `pointsByModality`.
3. `windowPointCount = n`.
4. `stressScore = (n / 2000.0).coerceIn(0.0, 1.0)` — density only.

```16:33:StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/edge/PhenotypeAnalyzer.kt
class HeuristicPhenotypeAnalyzer : PhenotypeAnalyzer {

    override suspend fun analyze(pointsInWindow: List<UnifiedDataPoint>): PhenotypeSummary {
        if (pointsInWindow.isEmpty()) return PhenotypeSummary.EMPTY
        val counts = linkedMapOf<ModalityKind, Int>()
        for (p in pointsInWindow) {
            val k = p.metadata.modality
            counts[k] = (counts[k] ?: 0) + 1
        }
        val n = pointsInWindow.size
        val stress = (n / 2000.0).coerceIn(0.0, 1.0)
        return PhenotypeSummary(
            pointsByModality = counts.toMap(),
            windowPointCount = n,
            stressScore = stress,
        )
    }
}
```

**Wiring:** `DefaultEdgeComputationEngine` defaults to `HeuristicPhenotypeAnalyzer()` and calls `phenotypeAnalyzer.analyze(recentPoints)` once per cycle. A custom implementation can be injected for study-specific **edge window summaries**; that does **not** replace the **phenotype analyzer** (RF) in `enginePhenotype`.

The Kotlin DTO is documented as a compact **phenotype** view in source, but for manuscript alignment treat it as the **edge window summary** unless you are discussing the type name itself:

```5:12:StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/edge/PhenotypeSummary.kt
/**
 * Compact **phenotype** view derived from fused points in the edge window (counts + heuristic stress).
 */
data class PhenotypeSummary(
    val pointsByModality: Map<ModalityKind, Int>,
    val windowPointCount: Int,
    /** Heuristic in \[0, 1\] for downstream policy (not clinical ground truth). */
    val stressScore: Double,
```

### 4.3 Fields of `PhenotypeSummary` (edge window summary)

| Field | Meaning |
|-------|--------|
| `pointsByModality` | Count of `UnifiedDataPoint` rows per `ModalityKind` in the window. |
| `windowPointCount` | Total points analyzed (`n`). |
| `stressScore` | Heuristic in `[0, 1]` from `n` only in the default edge hook; validated in `init`. |

**Empty state:** `PhenotypeSummary.EMPTY` — empty map, count 0, `stressScore` 0.0.

---

## 5. When each pathway runs (cadence)

| Pathway | Trigger | Typical interval |
|--------|---------|------------------|
| **Phenotype analyzer** → **phenotype summary** (RF report + envelope) | `MainActivity.snapshotCachesAndTrainPhenotype` → `EnginePhenotypeStepCountRandomForest.trainWithInterventionReceipt`; repeats under `launchPhenotypeAutoTicker` | **`PhenotypeWindowPrefs`** (“Phenotype window” in the main UI; minimum **60s** between trains) |
| **Edge window summary** + policy | `ModalityCollectionService` → `edgeComputationEngine.runCycle` | **`EDGE_INTERVAL_MS`** (60s, compile-time) between cycles; **history span** for points = `VolatileCacheWindowRetention` (“Cache window” in the UI; default 30 minutes) |

---

## 6. Downstream consumers (do not confuse the two summaries)

| Consumer | Input type | Role |
|----------|------------|------|
| UI / logs | **Phenotype summary** string (`PhenotypeRunUiBundle.phenotypeReport`) | Human-readable RF report (metrics + importance ranks). |
| Intervention receipts | `PhenotypeRunEnvelope` | Condensed outcome of an RF run (e.g. **PHENOTYPE_UPDATED** when top-importance predictor changes). |
| `PhenotypeFeatureEncoder` | **`PhenotypeSummary`** (edge window summary) | Fixed-length `FloatArray` from modality counts + `stressScore` for optional TFLite (`DefaultPhenotypeFeatureEncoder`). |
| `InterventionContext.phenotypeSummary` | **`PhenotypeSummary`** (edge window summary) | Field name overload: passed to `InterventionController.evaluate`; default policy thresholds on `stressScore` (see `doc/architecture.md` §2.6). |

**Encoder (deterministic layout):**

```15:29:StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/edge/PhenotypeFeatureEncoder.kt
class DefaultPhenotypeFeatureEncoder : PhenotypeFeatureEncoder {

    override fun encode(summary: PhenotypeSummary, targetLength: Int): FloatArray {
        require(targetLength > 0) { "targetLength must be positive" }
        val out = FloatArray(targetLength)
        val order = ModalityKind.entries
        var i = 0
        for (k in order) {
            if (i >= targetLength) return out
            out[i++] = (summary.pointsByModality[k] ?: 0).toFloat()
        }
        if (i < targetLength) {
            out[i] = summary.stressScore.toFloat()
        }
        return out
    }
}
```

**Policy note:** `DefaultInterventionController` compares `context.phenotypeSummary.stressScore` to a threshold—that is **policy on the edge window summary**, not interpretation of RF importances.

---

## 7. File index

| Topic | File |
|-------|------|
| **Phenotype analyzer** (RF) + **phenotype summary** report | `enginePhenotype/.../EnginePhenotypeStepCountRandomForest.kt` |
| Multimodal alignment | `enginePhenotype/.../MultimodalTemporalAlignment.kt` |
| RF train entry + auto interval prefs | `app/.../MainActivity.kt` (`snapshotCachesAndTrainPhenotype`, `launchPhenotypeAutoTicker`); `app/.../settings/PhenotypeWindowPrefs.kt` |
| Edge window-summary hook (interface name `PhenotypeAnalyzer`) | `core/.../edge/PhenotypeAnalyzer.kt` |
| Default edge window summary | `core/.../edge/PhenotypeAnalyzer.kt` (`HeuristicPhenotypeAnalyzer`) |
| Edge window DTO | `core/.../edge/PhenotypeSummary.kt` |
| Window selection | `core/.../edge/CachedWindowSelector.kt` |
| Window duration | `core/.../management/VolatileCacheWindowRetention.kt` |
| Edge orchestration | `core/.../edge/DefaultEdgeComputationEngine.kt` |
| Vector from edge bundle | `core/.../edge/PhenotypeFeatureEncoder.kt` |
| Policy context field | `core/.../management/InterventionContext.kt` |

---

*Companion to `doc/architecture.md` §2.5. Update when RF reporting, edge `PhenotypeAnalyzer` hook implementations, or `PhenotypeSummary` fields change.*
