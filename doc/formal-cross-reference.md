# Implementation Crosswalk: Manuscript Claims to Codebase Mapping

**Methods supplement — formal cross-reference**

Repository root: `MobileDigitalPhenotyping/` (paths below are relative to this root unless noted).

---

## Section 1 — How to read this document

This document maps **verifiable textual claims** to **concrete implementation** in the StanfordScreenomics Android/Kotlin codebase. Each mapped item includes:

- **Quoted text** — exact wording from the source named below.
- **Interpretation** — what must be true in software for the claim to hold.
- **Implementation** — files, types, and entry points that exist in this repository.
- **Code evidence** — minimal excerpt (not full files).
- **Verification status** — `FULLY IMPLEMENTED` | `PARTIALLY IMPLEMENTED` | `NOT IMPLEMENTED` | `IMPLEMENTED DIFFERENTLY`.
- **Notes** — gaps, naming differences, or scope limits.

**Source of claims (critical limitation):** This repository **does not contain** a separate JMIR manuscript file (no `.docx`, `.tex`, or narrative `.md` draft was found under `MobileDigitalPhenotyping/`). **All quoted claims in Section 2 are taken from the in-repository specification** `Data Cache and Storage Location` (plain text at repository root). If the submitted manuscript differs, reviewers should diff that text against this file and extend the crosswalk accordingly.

**Rules followed:** No code was modified. No behavior was inferred without reading sources. Where the specification demands something the code does not provide, that is marked **NOT IMPLEMENTED** or **PARTIALLY IMPLEMENTED**.

---

## Section 2 — Claim-by-claim mapping

Claims are numbered for reference. Quotes preserve source spelling (e.g. “trasnfered”).

---

#### Claim 1

> "DataNode may capture raw input"

**Technical requirement:** Raw multimodal ingress is allowed upstream of fusion; fusion is not required at the raw capture stage.

**Implementation**

- `StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/collection/DataNode.kt` — interface `DataNode`
- `StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/module/template/BaseDataNode.kt` — `BaseDataNode`
- Modality nodes: e.g. `StanfordScreenomics/modules/audio/.../AudioDataNode.kt`, `MotionDataNode.kt`, `GpsDataNode.kt`, `ScreenshotDataNode.kt`

**Code evidence**

```11:17:StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/collection/DataNode.kt
interface DataNode {
    suspend fun activate(scope: CoroutineScope)
    suspend fun deactivate()
    fun observeUnifiedOutputs(): Flow<UnifiedDataPoint>
    fun modalityKind(): ModalityKind
}
```

**Verification status:** **FULLY IMPLEMENTED**

**Notes:** Unified outputs still flow through `ModulePipeline`; raw capture is modality-specific behind `DataNode`.

---

#### Claim 2

> "Adapter MUST: - extract ONLY required outputs - discard everything else immediately"

**Technical requirement:** Adapters reduce raw frames to a minimal `UnifiedDataPoint` payload; no retention of full raw sensor streams in the fused object.

**Implementation**

- `StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/collection/Adapter.kt` — `Adapter`
- `StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/module/template/BaseAdapter.kt` — `BaseAdapter`
- `StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/module/template/ModulePipeline.kt` — `adapter.adapt(raw)` before cache commit

**Code evidence**

```22:24:StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/module/template/BaseAdapter.kt
    final override suspend fun adapt(raw: RawModalityFrame): UnifiedDataPoint {
        val point = adaptRaw(raw)
```

**Verification status:** **PARTIALLY IMPLEMENTED**

**Notes:** Adapters emit small key sets plus **UFS reserved keys** (`ufs.correlationId`, etc.) and modality-specific keys; the codebase does **not** include an automated proof that *only* spec-listed keys exist globally. Some modalities add keys beyond the prose “KEEP ONLY” lists (see architecture docs / adapter sources).

---

#### Claim 3

> "Cache MUST store ONLY required derived data"

**Technical requirement:** Volatile tier stores only the minimal fields described per module.

**Implementation**

- `StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/module/template/BaseCache.kt` — `BaseCache` stores full `UnifiedDataPoint` in `store`
- `StanfordScreenomics/modules/motion/.../MotionCache.kt` — overrides `shouldRetainPointInVolatileStore`

**Code evidence**

```23:28:StanfordScreenomics/modules/motion/src/main/kotlin/com/app/modules/motion/MotionCache.kt
    override fun shouldRetainPointInVolatileStore(point: UnifiedDataPoint): Boolean {
        if (MotionStructuredCloudUploadSelectors.motionPayloadHasAccelOrGyroData(point.data)) {
            return false
        }
        return point.data.containsKey(MotionStructuredCloudUploadSelectors.DATA_KEY_STEP_SESSION)
    }
```

**Verification status:** **PARTIALLY IMPLEMENTED**

**Notes:** **Motion** matches “steps only in volatile cache” intent. **Audio, GPS, Screenshot** caches use default `BaseCache` retention: entire `UnifiedDataPoint` rows (not field-stripped copies) while keys may still be minimal. GPS spec asks cache to hold only sun score + metadata + timestamp; **code retains full GPS points** in volatile store (see Claim 11).

---

#### Claim 4

> "UnifiedDataPoint MUST NOT contain unused fields"

**Technical requirement:** Payload map contains no extraneous keys beyond policy.

**Implementation**

- `StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/unified/UnifiedDataPoint.kt`
- `StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/unified/UnifiedFusionConsistency.kt` — validation at construction / pipeline

**Code evidence**

```11:20:StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/unified/UnifiedDataPoint.kt
class UnifiedDataPoint(
    data: Map<String, Any>,
    override val metadata: DataDescription,
) : UnifiedFusionStandard {

    override val data: Map<String, Any> = data.toMap()

    init {
        UnifiedFusionConsistency.validateOrThrow(this)
    }
```

**Verification status:** **PARTIALLY IMPLEMENTED**

**Notes:** Unused fields are **not** mechanically forbidden at compile time; enforcement is by adapter construction + consistency rules. Screenshot OCR text is **not** placed in `UnifiedDataPoint.data` (computed for sentiment only); see `ScreenshotAdapter.buildPoint` (Claim 12 area).

---

#### Claim 5

> "It contains ONLY: required data fields per module metadata (DataDescription) schema (DataEntity)"

**Technical requirement:** Runtime carrier embeds `DataEntity` / schema alongside `data` and `DataDescription`.

**Implementation**

- `StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/unified/DataEntity.kt` — `DataEntity`
- `StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/unified/UfsSchemaComposition.kt` — composes schema descriptors
- `UnifiedDataPoint` — **two** properties: `data`, `metadata` only (no `DataEntity` field)

**Code evidence**

```11:14:StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/unified/UnifiedDataPoint.kt
class UnifiedDataPoint(
    data: Map<String, Any>,
    override val metadata: DataDescription,
) : UnifiedFusionStandard {
```

**Verification status:** **NOT IMPLEMENTED** (for embedded `DataEntity` on each point)

**Notes:** **IMPLEMENTED DIFFERENTLY** for “schema”: `DataEntity` exists as a **separate** schema type; it is **not** a field on `UnifiedDataPoint`. This contradicts Part 6 as written if “schema (DataEntity)” is read as per-instance payload.

---

#### Claim 6

> "Delete local storage data once it's trasnfered to Cloud storage or Firestore."

**Technical requirement:** Successful cloud/Firestore completion deletes or stops retaining corresponding local artifacts where applicable.

**Implementation**

- `StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/storage/DefaultDistributedStorageManager.kt` — `onUnifiedPointCommitted` and upload helpers (documented behavior in file KDoc)

**Code evidence**

```19:22:StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/storage/DefaultDistributedStorageManager.kt
 * Firestore/RTDB structured fan-out, Cloud Storage for audio/screenshot bytes, minimal per-modality local artifacts.
 * …
 * `motion/steps/`, and GPS JSON are removed after successful cloud/Firestore transfer when applicable.
```

**Verification status:** **FULLY IMPLEMENTED** (on success paths described in that manager)

**Notes:** Exact branches are modality-specific; failures may leave local files. No midnight batch is required for deletion (see Claim 7).

---

#### Claim 7

> "later trasnfered in batch to CloudStorage and/or Firestore; eg., at midnight"

**Technical requirement:** Deferred batch upload (e.g. wall-clock scheduled flush).

**Implementation**

- `StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/storage/BatchUploadPolicyPlaceholder.kt` — placeholder; `shouldEnqueueStructuredWrite` / `shouldEnqueueMediaUpload` return `true` with diagnostic batch **logging** only
- `StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/storage/DefaultDistributedStorageManager.kt` — per-point `onUnifiedPointCommitted`

**Code evidence**

```7:10:StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/storage/BatchUploadPolicyPlaceholder.kt
/**
 * Placeholder for future **batching / backoff / Wi-Fi-only / study-session** upload rules.
 * [DefaultDistributedStorageManager] enqueues work here before cloud I/O.
 */
```

**Verification status:** **NOT IMPLEMENTED** (midnight / time-batched upload as specified)

**Notes:** Upload work is driven by **per committed** unified point, not a scheduled batch queue in this codebase.

---

#### Claim 8

> "audio.signal.rmsDb"

**Technical requirement:** Audio level key exactly `audio.signal.rmsDb` in fused payload.

**Implementation**

- `StanfordScreenomics/modules/audio/.../AudioAdapter.kt` — constant `AUDIO_KEY_MEAN_DB = "audio.signal.meanDb"`

**Code evidence**

```21:21:StanfordScreenomics/modules/audio/src/main/kotlin/com/app/modules/audio/AudioAdapter.kt
private const val AUDIO_KEY_MEAN_DB: String = "audio.signal.meanDb"
```

**Verification status:** **IMPLEMENTED DIFFERENTLY**

**Notes:** Level is stored under **`audio.signal.meanDb`**, not `audio.signal.rmsDb`. Semantics: derived from RMS internally then mapped to a 0–120 “mean dB” scale (see adapter KDoc and `cleanedMetrics.meanDb`).

---

#### Claim 9

> "CACHE (30 min): - audio.signal.rmsDb - metadata - timestamp"

**Technical requirement:** 30-minute sliding volatile window; audio cache holds only those conceptual fields.

**Implementation**

- `StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/management/VolatileCacheWindowRetention.kt` — default 30 minutes
- `StanfordScreenomics/core/.../BaseCache.kt` — `VolatileCacheWindowRetention.duration()` in eviction
- `StanfordScreenomics/modules/audio/.../AudioCache.kt` — no field filtering override

**Code evidence**

```12:12:StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/management/VolatileCacheWindowRetention.kt
    private val nanos = AtomicLong(Duration.ofMinutes(30).toNanos())
```

**Verification status:** **PARTIALLY IMPLEMENTED**

**Notes:** **30-minute window:** yes (global default, UI can change). **Field minimalism:** volatile store holds full `UnifiedDataPoint` for audio (includes UFS reserved keys and `audio.signal.meanDb`, not `rmsDb` per Claim 8).

---

#### Claim 10

> "CACHE (30 min): - motion.step.sessionTotal - metadata - timestamp"

**Technical requirement:** Volatile motion cache retains step aggregate only.

**Implementation**

- `MotionCache.kt` (Claim 3 evidence)

**Verification status:** **FULLY IMPLEMENTED** (for volatile store semantics; IMU excluded)

**Notes:** IMU still flows to local JSON / storage policy via `onAfterUnifiedPointCommittedOutsideLock` without entering volatile `store`.

---

#### Claim 11

> "CACHE (30 min): - gps.weather.sunScore0To10 - metadata - timestamp"

**Technical requirement:** GPS volatile cache stores only sun score + trace metadata + timestamp.

**Implementation**

- `StanfordScreenomics/modules/gps/.../GpsCache.kt` — extends `BaseCache` with **no** `shouldRetainPointInVolatileStore` override

**Code evidence**

```11:18:StanfordScreenomics/modules/gps/src/main/kotlin/com/app/modules/gps/GpsCache.kt
class GpsCache(
    cacheId: String = "default-gps-cache",
    onAfterUnifiedPointCommittedOutsideLock: suspend (UnifiedDataPoint) -> Unit = {},
) : BaseCache(
    cacheId = cacheId,
    modality = ModalityKind.GPS,
    onAfterUnifiedPointCommittedOutsideLock = onAfterUnifiedPointCommittedOutsideLock,
)
```

**Verification status:** **IMPLEMENTED DIFFERENTLY**

**Notes:** Full GPS `UnifiedDataPoint` rows (including lat/lon keys present in adapter output) are eligible for the same volatile retention policy as other default caches unless pruned elsewhere.

---

#### Claim 12

> "REMOVE: - screenshot.ocr.fullText"

**Technical requirement:** OCR plaintext not persisted in UFS payload.

**Implementation**

- `StanfordScreenomics/modules/screenshot/.../ScreenshotAdapter.kt` — `buildPoint` constructs `data` without OCR keys

**Code evidence**

```171:175:StanfordScreenomics/modules/screenshot/src/main/kotlin/com/app/modules/screenshot/ScreenshotAdapter.kt
        val data = linkedMapOf<String, Any>(
            UfsReservedDataKeys.CORRELATION_ID to correlationString,
            UfsReservedDataKeys.MONOTONIC_TIMESTAMP_NANOS to monoNanos,
            KEY_SENTIMENT_SCORE to sentimentScore.coerceIn(1.0, 10.0),
        )
```

**Verification status:** **FULLY IMPLEMENTED** (for `UnifiedDataPoint.data`; OCR used transiently for sentiment)

---

#### Claim 13

> "Local storage is per-module: /files/audio/ /files/motion/ /files/gps/ /files/screenshot/"

**Technical requirement:** App-private `files/` subtree per modality without cross-module paths.

**Implementation**

- `StanfordScreenomics/core/.../ModalityStorageDirectoryName.kt`
- `StanfordScreenomics/app/.../storage/AndroidDistributedStorageBridges.kt` — `AndroidModalityLocalFileSink`

**Verification status:** **FULLY IMPLEMENTED**

**Notes:** Motion adds subfolders (`imu/`, `steps/`, `pending_firestore/`) under `files/motion/` per storage manager logic.

---

#### Claim 14 (edge / phenotyping — if manuscript aligns with “real-time phenotyping over recent window”)

Interpretation from codebase behavior: edge cycles read **cache snapshots**, windowed by global retention duration.

**Implementation**

- `StanfordScreenomics/core/.../edge/DefaultEdgeComputationEngine.kt` — `runCycle`
- `StanfordScreenomics/app/.../collection/ModalityCollectionService.kt` — `edgeJob` + `EDGE_INTERVAL_MS`

**Code evidence**

```38:44:StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/edge/DefaultEdgeComputationEngine.kt
        val snapshots = cacheManager.snapshotByCacheId()
        val flat = snapshots.values.flatten()
        val windowEnd = CachedWindowSelector.windowEndInclusive(flat, clock.instant())
        val windowDuration = VolatileCacheWindowRetention.duration()
        val windowedById = snapshots.mapValues { (_, pts) ->
            CachedWindowSelector.filterWindow(pts, windowEnd, windowDuration)
        }
```

**Verification status:** **FULLY IMPLEMENTED** (for “cache-backed, windowed edge cycles”; not manuscript-specific without manuscript text)

**Notes:** Edge tick interval is **60 seconds** (`EDGE_INTERVAL_MS = 60_000L` in `ModalityCollectionService.kt`), separate from 30-minute **data** retention.

---

## Section 3 — Architecture traceability

### Data collection layer

| Manuscript / spec concept | Implementation |
|---------------------------|----------------|
| DataNode | `DataNode`, `BaseDataNode`, `*DataNode` per module |
| Adapter | `Adapter`, `BaseAdapter`, `*Adapter` per module |
| Unified Fusion Standard | `UnifiedDataPoint`, `DataDescription`, `UnifiedFusionConsistency`, `UfsReservedDataKeys`; pipeline in `ModulePipeline.kt` |

### Data management layer

| Concept | Implementation |
|---------|----------------|
| Cache (30-min window default) | `VolatileCacheWindowRetention`, `BaseCache.applySlidingWindowEvictionUnderLock`, `VolatileCacheWindowPrefs` (app) |
| Distributed storage | `DefaultDistributedStorageManager`, bridges in `StanfordScreenomics/app/.../storage/AndroidDistributedStorageBridges.kt` (Firestore, RTDB, Cloud Storage) |
| Edge computation | `EdgeComputationEngine`, `DefaultEdgeComputationEngine`, `CachedWindowSelector`, `PhenotypeAnalyzer` / `HeuristicPhenotypeAnalyzer` |
| Intervention controller | `InterventionController`, `DefaultInterventionController`, `InterventionContext` |

### Parallel processing and scheduler

| Requirement | Implementation | Evidence |
|-------------|------------------|----------|
| Parallel modality collection | Four concurrent `Job`s | `ModalityCollectionService.kt` — loop `serviceScope.launch { node.activate; observeUnifiedOutputs().collect }` |
| Resource-aware scheduling | `DefaultTaskScheduler`, `ResourceStressEvaluator`, `AndroidHostResourceSignalProvider` | `DefaultTaskScheduler.startResourceMonitoring` poll loop |
| CPU / memory / battery thresholds | `ResourceStressThresholds` defaults | `cpuLoadStrictlyAbove`, `availableMemoryStrictlyBelowBytes`, `batteryFractionStrictlyBelow` in `ResourceStressThresholds.kt` |

---

## Section 4 — Module pipeline traceability

Pipeline as stated in the specification: **DataNode → Adapter → Cache → Local Storage → Cloud**.

### Audio

| Stage | File / class |
|-------|----------------|
| DataNode | `modules/audio/.../AudioDataNode.kt` — `AudioDataNode` |
| Adapter | `modules/audio/.../AudioAdapter.kt` — `AudioAdapter` |
| Cache | `modules/audio/.../AudioCache.kt` — `AudioCache` |
| Local storage | `AndroidModalityLocalFileSink` + `AudioAdapter` (zlib PCM filename `audio_<stamp>.bin` pattern via `StorageArtifactFilename` / adapter) |
| Cloud upload | `DefaultDistributedStorageManager.onUnifiedPointCommitted` — Firestore + `CloudMediaStorageBridge` for media |

**Data verification**

| Tier | What exists |
|------|-------------|
| Memory (~30 min sliding) | Full `UnifiedDataPoint` in `AudioCache` / `BaseCache` (not field-stripped) |
| Local | Compressed audio bytes under `files/audio/` |
| Cloud | Structured Firestore document + optional Cloud Storage object + merged file URL logic in `DefaultDistributedStorageManager` |

### Motion

| Stage | File / class |
|-------|----------------|
| DataNode | `modules/motion/.../MotionDataNode.kt` |
| Adapter | `modules/motion/.../MotionAdapter.kt` |
| Cache | `modules/motion/.../MotionCache.kt` |
| Local storage | `DefaultDistributedStorageManager` — `motion/imu/`, `motion/steps/`, `pending_firestore/` JSON |
| Cloud | Firestore / RTDB bridges + selectors `MotionStructuredCloudUploadSelectors.kt` |

**Data verification**

| Tier | What exists |
|------|-------------|
| Memory | Step/session rollup points only in volatile `store`; IMU excluded (`MotionCache`) |
| Local | IMU and step JSON artifacts per manager |
| Cloud | Structured writes; accel/gyro may be paused to `pending_firestore/` when `BatchUploadPolicyPlaceholder.pauseMotionFirestoreUpload` is true |

### GPS

| Stage | File / class |
|-------|----------------|
| DataNode | `modules/gps/.../GpsDataNode.kt` |
| Adapter | `modules/gps/.../GpsAdapter.kt` |
| Cache | `modules/gps/.../GpsCache.kt` |
| Local storage | `DefaultDistributedStorageManager` — `gps_*.json` under `files/gps/` |
| Cloud | Firestore via `UnifiedDataPointPersistenceCodec` |

**Data verification**

| Tier | What exists |
|------|-------------|
| Memory | Full GPS unified points in volatile cache (no sun-only override) |
| Local | JSON snapshot files |
| Cloud | Firestore; local JSON removed on successful structured write (per manager behavior) |

### Screenshot

| Stage | File / class |
|-------|----------------|
| DataNode | `modules/screenshot/.../ScreenshotDataNode.kt` |
| Adapter | `modules/screenshot/.../ScreenshotAdapter.kt` |
| Cache | `modules/screenshot/.../ScreenshotCache.kt` |
| Local storage | PNG under `files/screenshot/` via `ModalityLocalFileSink` |
| Cloud | `CloudMediaStorageBridge` + Firestore merge patterns in `DefaultDistributedStorageManager` |

**Data verification**

| Tier | What exists |
|------|-------------|
| Memory | Full screenshot `UnifiedDataPoint` (sentiment score + UFS keys) |
| Local | PNG file |
| Cloud | Firestore + Cloud Storage upload path |

---

## Section 5 — Unified Fusion Standard

### DataDescription

- **Definition:** `StanfordScreenomics/core/.../unified/DataDescription.kt`
- **Attachment:** Required constructor argument to `UnifiedDataPoint` as `metadata` property.
- **Propagation:** Set in each `*Adapter` when building the point; encoded by `UnifiedDataPointPersistenceCodec.toStructuredMap`; timestamps used by `CachedWindowSelector` for edge windows.

### DataEntity

- **Definition:** `StanfordScreenomics/core/.../unified/DataEntity.kt`
- **Composition:** `UfsSchemaComposition.kt` merges modality payload schema with canonical reserved declarations.
- **Propagation:** **Not** carried on `UnifiedDataPoint`; schema is separate from runtime instances (Section 2, Claim 5).

### UnifiedDataPoint

- **Definition:** `StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/unified/UnifiedDataPoint.kt`
- **Pipeline path:** `ModulePipeline` → `UnifiedFusionConsistency.validateOrThrow` → `BaseCache.put` → optional `onAfterUnifiedPointCommittedOutsideLock` → `DefaultDistributedStorageManager`.

**Metadata attachment (code evidence)**

```171:185:StanfordScreenomics/modules/screenshot/src/main/kotlin/com/app/modules/screenshot/ScreenshotAdapter.kt
        val data = linkedMapOf<String, Any>(
            UfsReservedDataKeys.CORRELATION_ID to correlationString,
            UfsReservedDataKeys.MONOTONIC_TIMESTAMP_NANOS to monoNanos,
            KEY_SENTIMENT_SCORE to sentimentScore.coerceIn(1.0, 10.0),
        )
        val metadata = DataDescription(
            source = sourceLabel,
            timestamp = Instant.ofEpochMilli(raw.capturedAtEpochMillis),
            …
        )
```

---

## Section 6 — Real-time phenotyping

| Topic | Where implemented | Evidence |
|-------|---------------------|----------|
| 30-minute window (data retention default) | `VolatileCacheWindowRetention`, `BaseCache` eviction | `Duration.ofMinutes(30)` default |
| Edge window filter | `DefaultEdgeComputationEngine` + `CachedWindowSelector` | Uses `VolatileCacheWindowRetention.duration()` |
| Feature inputs (sentiment, audio level, weather/sun, steps) in **edge phenotype** | `HeuristicPhenotypeAnalyzer` | Uses **per-modality counts** and total window size only — **not** per-key sentiment/sun/audio DB values |

**Code evidence (phenotype features)**

```18:31:StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/edge/PhenotypeAnalyzer.kt
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
```

| Manuscript-style claim | Verification status | Notes |
|------------------------|----------------------|-------|
| Multimodal RF phenotype on device in the **main edge path** | **NOT IMPLEMENTED** | `DefaultEdgeComputationEngine` uses `HeuristicPhenotypeAnalyzer` + optional TFLite vector path. |
| Random Forest elsewhere in repo | **IMPLEMENTED** (separate module) | `StanfordScreenomics/enginePhenotype/.../EnginePhenotypeStepCountRandomForest.kt` uses Smile `smile.regression.RandomForest` — **not** referenced from `DefaultEdgeComputationEngine` in the inspected wiring (`MainActivity` passes `modelAssetPath = null`). |

**TFLite on edge path**

- `DefaultEdgeComputationEngine.runCycle` calls `TfliteInterpreterBridge.runVectorInference`.
- `AndroidTfliteInterpreterBridge`: if `modelAssetPath` is null/blank, inference returns a **skipped** trace.

**Code evidence**

```39:45:StanfordScreenomics/app/src/main/java/edu/stanford/screenomics/edge/AndroidTfliteInterpreterBridge.kt
            if (modelAssetPath.isNullOrBlank()) {
                return@withLock skippedResult(
                    inputLength = inputFeatures.size,
                    outputLength = outputLength,
                    modelAssetPath = "",
                    reason = "no_model_asset_path",
                )
            }
```

**Phenotype “update” logic:** Each edge cycle recomputes summary from the current windowed points; there is **no** persisted longitudinal phenotype state in `HeuristicPhenotypeAnalyzer` itself.

---

## Section 7 — Storage behavior

| Topic | Status | Implementation notes |
|-------|--------|----------------------|
| Midnight / batch upload queue | **NOT IMPLEMENTED** as specified | `BatchUploadPolicyPlaceholder` is explicit placeholder; `DefaultDistributedStorageManager` commits per point. |
| Firestore vs Realtime DB vs Cloud Storage | **IMPLEMENTED** | `DefaultDistributedStorageManager` constructor takes `FirestoreStructuredWriteBridge`, `RealtimeStructuredWriteBridge`, `CloudMediaStorageBridge`; behavior split by modality/path in `onUnifiedPointCommitted` and helpers. |
| Local per-module roots | **IMPLEMENTED** | `ModalityStorageDirectoryName` + `AndroidModalityLocalFileSink`. |

---

## Section 8 — Intervention controller

| Topic | Implementation |
|-------|----------------|
| Phenotype → decision | `DefaultInterventionController.evaluate` reads `InterventionContext.phenotypeSummary` and `tfliteInferenceTraces` |
| When interventions trigger | After each `DefaultEdgeComputationEngine.runCycle`, if thresholds met |

**Code evidence**

```32:47:StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/management/DefaultInterventionController.kt
        val tfliteHot = context.tfliteInferenceTraces.any { !it.skipped && it.aggregateScore > 0.85 }
        if (tfliteHot) {
            val d = SamplingPolicyDirective(
                targetModalityKindName = ModalityKind.SCREENSHOT.name,
                policyToken = "edge_tflite_high_activation_throttle",
            )
            …
            return d
        }
        if (context.phenotypeSummary.stressScore >= 0.85) {
            val d = SamplingPolicyDirective(
                targetModalityKindName = ModalityKind.AUDIO.name,
                policyToken = "edge_phenotype_high_density_throttle",
            )
```

**How updates occur:** Directives are **returned** to the caller of `runCycle`; there is **no** separate actuator in core that applies sampling policy to `DataNode` rates in the snippets above—verify consumers of `InterventionDirective` if manuscript claims automatic throttling is enforced end-to-end (search `SamplingPolicyDirective` usage beyond logging).

**Verification status (phenotype comparison):** **IMPLEMENTED DIFFERENTLY** if manuscript requires differential phenotype comparison — policy is **threshold-on-current-context**, not diff vs prior state.

---

## Section 9 — Summary of implementation fidelity

| Category | Items |
|----------|--------|
| **Fully implemented (relative to `Data Cache and Storage Location`)** | `DataNode` pattern; `Adapter` + `ModulePipeline`; UFS `UnifiedDataPoint` + `DataDescription`; per-modality `files/` roots; motion volatile cache IMU exclusion; screenshot OCR not stored in UFS payload; post-success local cleanup paths in `DefaultDistributedStorageManager` (as documented there); resource threshold types; parallel modality jobs; cache-sourced edge engine. |
| **Partially implemented** | “ONLY required outputs/fields” globally; audio/GPS/screenshot volatile caches holding full points; upload batching only as logging placeholder. |
| **Not implemented** | Midnight/time-batch upload; embedded `DataEntity` on each `UnifiedDataPoint`; Random Forest in the **default** edge engine path; TFLite with asset path in default `MainActivity` wiring (`modelAssetPath = null`). |
| **Implemented differently** | `audio.signal.meanDb` vs `audio.signal.rmsDb`; GPS volatile cache content vs “sun only”; schema attachment pattern for `DataEntity`. |

---

## Section 10 — Repository navigation guide

If you want to verify **X**, open **Y**:

| X | Y |
|---|----|
| Adapter logic (per modality) | `StanfordScreenomics/modules/<modality>/src/main/kotlin/com/app/modules/<modality>/*Adapter.kt` |
| Raw ingress / node lifecycle | `StanfordScreenomics/core/.../collection/DataNode.kt`, `.../template/BaseDataNode.kt`, `modules/*/*DataNode.kt` |
| Pipeline fusion order | `StanfordScreenomics/core/.../template/ModulePipeline.kt` |
| Cache TTL / sliding window | `StanfordScreenomics/core/.../management/VolatileCacheWindowRetention.kt`, `.../template/BaseCache.kt` |
| Motion volatile filtering | `StanfordScreenomics/modules/motion/.../MotionCache.kt`, `core/.../storage/MotionStructuredCloudUploadSelectors.kt` |
| Cloud + local orchestration | `StanfordScreenomics/core/.../storage/DefaultDistributedStorageManager.kt` |
| Firestore document shape | `StanfordScreenomics/core/.../storage/UnifiedDataPointPersistenceCodec.kt` |
| Edge window + phenotype + TFLite hook | `StanfordScreenomics/core/.../edge/DefaultEdgeComputationEngine.kt`, `.../edge/PhenotypeAnalyzer.kt` |
| TFLite Android bridge | `StanfordScreenomics/app/.../edge/AndroidTfliteInterpreterBridge.kt` |
| Intervention policy | `StanfordScreenomics/core/.../management/DefaultInterventionController.kt` |
| Parallel collection + edge tick | `StanfordScreenomics/app/.../collection/ModalityCollectionService.kt` |
| Scheduler thresholds | `StanfordScreenomics/core/.../scheduling/ResourceStressThresholds.kt`, `DefaultTaskScheduler.kt` |
| Random Forest (separate demo / engine module) | `StanfordScreenomics/enginePhenotype/.../EnginePhenotypeStepCountRandomForest.kt` |
| UFS types | `StanfordScreenomics/core/.../unified/*.kt` |

---

*End of document. To extend Section 2 with true manuscript sentences, add the manuscript file to the repository (or paste claims) and map each quote using the same template.*
