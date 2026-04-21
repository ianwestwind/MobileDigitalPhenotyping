# Stanford Screenomics — Specification → Implementation Architecture

This document maps the **minimal data / two-layer phenotyping** specification (repository file `Data Cache and Storage Location`) to the **StanfordScreenomics** Android (Kotlin) codebase. Paths are relative to the repository root `MobileDigitalPhenotyping/`.

**Companion document:** `doc/architecture.md` is the overview-style architecture note (`## 1.`–`## 6.`) with the same specification → implementation mapping; **this file** follows the numbered `# 1.`–`# 6.` cross-reference layout.

---

# 1. Overview

## Brief description of system

The system is an **on-device mobile digital phenotyping** stack that:

1. **Collects** multimodal data (audio PCM windows, IMU motion, GPS with optional weather enrichment, screenshots).
2. **Normalizes** each sample through the **Unified Fusion Standard (UFS)**: every committed sample is a `UnifiedDataPoint` (payload map + mandatory `DataDescription` metadata).
3. **Retains** recent history in **per-modality in-memory caches** governed by a global sliding TTL (`VolatileCacheWindowRetention`, default **30 minutes**, user-adjustable).
4. **Persists** modality artifacts under **app-private `files/`** subtrees, then **uploads** via `DefaultDistributedStorageManager` (Firestore structured documents, optional Realtime Database mirror, Cloud Storage for binary media).
5. **Runs edge analytics** on **`CacheManager` snapshots only** (not raw `DataNode` streams): windowed points → `PhenotypeAnalyzer` → optional TFLite → `InterventionController.evaluate`.

Orchestration anchor: `edu.stanford.screenomics.PhenotypingRuntime` in `StanfordScreenomics/app/src/main/java/edu/stanford/screenomics/PhenotypingRuntime.kt`, built in `MainActivity` and shared with `ModalityCollectionService` through `ScreenomicsApp.phenotypingRuntime`.

## Two-layer architecture summary

| Layer | Specification intent | Primary implementation |
|-------|------------------------|-------------------------|
| **Layer 1 — Data collection** | Raw capture → adapter → UFS point → volatile cache | `DataNode` + `ModulePipeline` (`StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/module/template/ModulePipeline.kt`); modality modules under `StanfordScreenomics/modules/*/` |
| **Layer 2 — Data management** | Cache registry, local/cloud storage, edge cycles, intervention | `DefaultCacheManager` / `LifecycleAwareCacheManager`, `DefaultDistributedStorageManager`, `DefaultEdgeComputationEngine`, `DefaultInterventionController`, `TaskScheduler` |

**Invariant:** the edge engine consumes **cache snapshots** only:

```14:19:StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/edge/DefaultEdgeComputationEngine.kt
/**
 * Default [EdgeComputationEngine]: sliding cache window from [VolatileCacheWindowRetention] (main UI),
 * [PhenotypeAnalyzer], optional [TfliteInterpreterBridge], then [InterventionController.evaluate].
 *
 * **Data source:** only [CacheManager] snapshots of registered caches — no `DataNode` raw streams or pipeline ingress.
```

---

# 2. Architecture Mapping (Spec → Code)

## Data Collection Layer

**Description (from spec):** Each modality uses a **DataNode** for raw ingress. An **Adapter** maps `RawModalityFrame` → **`UnifiedDataPoint`**. **ModulePipeline** wires: raw `Flow` → `adapter.adapt` → UFS validation → `InMemoryCache.put`. Collection runs in the foreground service with **parallel** modality jobs.

**Implementation**

| Concern | Files | Classes / types | Responsibilities |
|---------|-------|-------------------|------------------|
| Raw frame contract | `StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/collection/RawModalityFrame.kt` | `RawModalityFrame`, modality-specific subtypes | Typed ingress from sensors or capture subsystems |
| Node contract | `StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/collection/DataNode.kt` | `DataNode` | `activate` / `deactivate`, `observeUnifiedOutputs(): Flow<UnifiedDataPoint>`, `modalityKind()` |
| Node template | `StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/module/template/BaseDataNode.kt` | `BaseDataNode` | Owns `ModulePipeline`, exposes unified output flow |
| Pipeline | `StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/module/template/ModulePipeline.kt` | `ModulePipeline` | `rawFrames` → `adapter.adapt` → `UnifiedFusionConsistency.validateOrThrow` → `cache.put` |
| Stage dispatchers | `StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/module/template/ModulePipelineDispatchers.kt` | `ModulePipelineDispatchers` | Coroutine contexts: `rawIngress`, `adaptation`, `cacheCommit`, `channelDelivery` |
| Adapter contract | `StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/collection/Adapter.kt` | `Adapter` | `suspend fun adapt(RawModalityFrame): UnifiedDataPoint` |
| Adapter base | `StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/module/template/BaseAdapter.kt` | `BaseAdapter` | Wraps `adaptRaw`, emits pipeline diagnostics |
| Foreground collection | `StanfordScreenomics/app/src/main/java/edu/stanford/screenomics/collection/ModalityCollectionService.kt` | `ModalityCollectionService` | Starts four modality graphs in parallel `Job`s (`node.activate` + `observeUnifiedOutputs().collect`) |

**Module factories (DataNode + Adapter + Cache)**

| Modality | Factory file | Factory / graph types |
|----------|--------------|------------------------|
| Audio | `StanfordScreenomics/modules/audio/src/main/kotlin/com/app/modules/audio/AudioModule.kt` | `AudioModule.create` → `AudioDataNode`, `AudioAdapter`, `AudioCache` |
| Motion | `StanfordScreenomics/modules/motion/src/main/kotlin/com/app/modules/motion/MotionModule.kt` | `MotionModule.create` → `MotionDataNode`, `MotionAdapter`, `MotionCache` |
| GPS | `StanfordScreenomics/modules/gps/src/main/kotlin/com/app/modules/gps/GpsModule.kt` | `GpsModule.create` → `GpsDataNode`, `GpsAdapter`, `GpsCache` |
| Screenshot | `StanfordScreenomics/modules/screenshot/src/main/kotlin/com/app/modules/screenshot/ScreenshotModule.kt` | `ScreenshotModule.create` → `ScreenshotDataNode`, `ScreenshotAdapter`, `ScreenshotCache` |

---

## Data Management Layer

**Description (from spec):** Volatile caches (sliding window), local file sinks per modality, cloud upload, periodic cache eviction, edge computation over unified points.

**Implementation**

| Concern | Files | Classes | Responsibilities |
|---------|-------|---------|------------------|
| Cache API | `StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/management/InMemoryCache.kt` | `InMemoryCache` | `put`, `get`, `snapshot`, `sweepSlidingWindowTtl`, etc. |
| Sliding window duration | `StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/management/VolatileCacheWindowRetention.kt` | `VolatileCacheWindowRetention` | Global `Duration` (default 30 min); `setDuration` clamped 1 min–48 h |
| UI prefs for window | `StanfordScreenomics/app/src/main/java/edu/stanford/screenomics/settings/VolatileCacheWindowPrefs.kt` | `VolatileCacheWindowPrefs` | Syncs UI → `VolatileCacheWindowRetention` |
| Cache implementation | `StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/module/template/BaseCache.kt` | `BaseCache` | `LinkedHashMap` by `CorrelationId`, TTL eviction, `onAfterUnifiedPointCommittedOutsideLock` hook |
| Cache coordinator | `StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/management/DefaultCacheManager.kt`, `LifecycleAwareCacheManager.kt`, `CacheManager.kt` | `DefaultCacheManager`, `LifecycleAwareCacheManager`, `CacheManager` | Register caches; `snapshotByCacheId()` for edge |
| Periodic eviction | `StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/management/PeriodicCacheEvictionTicker.kt` | `PeriodicCacheEvictionTicker` | Started from `ModalityCollectionService` with `runtime.cacheManager` |
| Local files (Android) | `StanfordScreenomics/app/src/main/java/edu/stanford/screenomics/storage/AndroidDistributedStorageBridges.kt` | `AndroidModalityLocalFileSink` | `ModalityLocalFileSink`: writes under `files/<modalityDir>/` |
| Directory names | `StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/storage/ModalityStorageDirectoryName.kt` | `ModalityStorageDirectoryName` | `audio`, `motion`, `gps`, `screenshot` |
| Upload orchestration | `StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/storage/DefaultDistributedStorageManager.kt` | `DefaultDistributedStorageManager` | `onUnifiedPointCommitted(UnifiedDataPoint)`: Firestore, RTDB, Cloud Storage, local JSON/media lifecycle |
| Structured encoding | `StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/storage/UnifiedDataPointPersistenceCodec.kt` | `UnifiedDataPointPersistenceCodec` | `toStructuredMap(point)` for remote-safe maps |
| Batch policy hooks | `StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/storage/BatchUploadPolicyPlaceholder.kt` | `BatchUploadPolicyPlaceholder` | Motion Firestore pause flag; enqueue predicates (mostly pass-through today) |
| Runtime bundle | `StanfordScreenomics/app/src/main/java/edu/stanford/screenomics/PhenotypingRuntime.kt` | `PhenotypingRuntime` | Holds caches, sink, `DefaultDistributedStorageManager`, `LifecycleAwareCacheManager`, `TaskScheduler`, `EdgeComputationEngine`, `InterventionController` |

---

## Unified Fusion Standard

### DataDescription

| Item | Detail |
|------|--------|
| **Role** | Mandatory capture metadata on every `UnifiedDataPoint` (`UnifiedDataPoint.metadata`). |
| **Defined in** | `StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/unified/DataDescription.kt` — `data class DataDescription(...)` |
| **Used in** | Constructed inside every module `*Adapter`; validated in `UnifiedFusionConsistency`; encoded in `UnifiedDataPointPersistenceCodec.encodeDescription`; edge windowing uses `DataDescription.timestamp` via `CachedWindowSelector` |

### DataEntity

| Item | Detail |
|------|--------|
| **Role** | **Schema descriptor only** (attribute/type/range/relationship declarations), not runtime payload. |
| **Defined in** | `StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/unified/DataEntity.kt` — `data class DataEntity(...)` (alias `UnifiedFusionSchemaDescriptor`) |
| **Composed with** | `StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/unified/UfsSchemaComposition.kt` — `object UfsSchemaComposition` merges modality payload specs with canonical reserved UFS declarations |
| **Not** | Embedded as a field on each `UnifiedDataPoint`; instance values live only in `UnifiedDataPoint.data` |

### UnifiedDataPoint

| Item | Detail |
|------|--------|
| **Role** | UFS carrier: unmodifiable `data` map + `metadata: DataDescription`; implements `UnifiedFusionStandard`. |
| **Defined in** | `StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/unified/UnifiedDataPoint.kt` |
| **Interface** | `StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/unified/UnifiedFusionStandard.kt` — `UnifiedFusionStandard` |
| **Used in** | `ModulePipeline` (post-adapter), all `*Cache` / `BaseCache`, `DefaultDistributedStorageManager`, `UnifiedDataPointPersistenceCodec`, `DefaultEdgeComputationEngine`, `InterventionContext` |

### Validation and reserved keys

| Item | File | Type |
|------|------|------|
| Reserved payload keys | `StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/unified/UfsReservedDataKeys.kt` | `UfsReservedDataKeys` |
| UFS validation | `StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/unified/UnifiedFusionConsistency.kt` | `UnifiedFusionConsistency.validateOrThrow` (called from `UnifiedDataPoint` `init` and `ModulePipeline`) |

**Pipeline enforcement**

```44:60:StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/module/template/ModulePipeline.kt
    fun unifiedOutputFlow(): Flow<UnifiedDataPoint> =
        rawFrames
            .flowOn(dispatchers.rawIngress)
            .buffer(capacity = stageChannelCapacity)
            .transform { raw ->
                hooks.onRawFrameObserved(raw)
                val point = adapter.adapt(raw)
                UnifiedFusionConsistency.validateOrThrow(point)
                hooks.onAdapted(point)
                emit(point)
            }
            ...
            .onEach { point ->
                cache.put(point)
                hooks.onCacheCommitted(point)
            }
```

---

## Task Scheduler

| Item | Location |
|------|----------|
| Interface | `StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/scheduling/TaskScheduler.kt` — `TaskScheduler` |
| Default implementation | `StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/scheduling/DefaultTaskScheduler.kt` — `DefaultTaskScheduler` |
| Thresholds | `StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/scheduling/ResourceStressThresholds.kt` — `ResourceStressThresholds` |
| Evaluation | `StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/scheduling/ResourceStressEvaluator.kt`, `ResourceStressSignals.kt`, `ResourceStressLevel.kt` |
| Android host signals | `StanfordScreenomics/app/src/main/java/edu/stanford/screenomics/scheduling/AndroidHostResourceSignalProvider.kt` — `AndroidHostResourceSignalProvider` |
| Monitoring start | `StanfordScreenomics/app/src/main/java/edu/stanford/screenomics/MainActivity.kt` — `taskScheduler.startResourceMonitoring(lifecycleScope, AndroidHostResourceSignalProvider(...))` |
| Per-modality dispatchers | `DefaultTaskScheduler.modulePipelineDispatchers(ModalityKind)` consumed when constructing nodes in `ModalityCollectionService` |

**How resource thresholds are handled**

1. `DefaultTaskScheduler.startResourceMonitoring` loops on `pollIntervalMs` (default **1000 ms**), reading `HostResourceSignalProvider.currentSnapshot()`.
2. `ResourceStressEvaluator.signals(snapshot, thresholds)` sets booleans (CPU hot, low memory, low battery).
3. `ResourceStressEvaluator.level` maps to `ResourceStressLevel` (`NORMAL`, `STRESSED`, `CRITICAL`).
4. Under stress, `DefaultTaskScheduler` updates `DynamicTaskPriorityRegistry` and reapplies **parallelism** and **dispatcher limits** via `PipelineParallelismPlan`, `SwitchableCoroutineDispatcher`, and `LimitedDispatcherCache`.

Default threshold values:

```6:14:StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/scheduling/ResourceStressThresholds.kt
data class ResourceStressThresholds(
    /** Stress when **process** CPU heuristic is **strictly greater** than this value (e.g. 0.85 = 85%). */
    val cpuLoadStrictlyAbove: Double = 0.85,
    /** Stress when available memory is **strictly below** this many bytes (e.g. 200 MiB). */
    val availableMemoryStrictlyBelowBytes: Long = 200L * 1024L * 1024L,
    /** Stress when battery fraction is **strictly below** this value (e.g. 0.20 = 20%). */
    val batteryFractionStrictlyBelow: Double = 0.20,
```

---

## Edge Computation

| Question | Answer |
|----------|--------|
| **Where is the 30-minute window enforced?** | **Volatile retention:** `VolatileCacheWindowRetention` default `Duration.ofMinutes(30)` (`VolatileCacheWindowRetention.kt`). **Per-point eviction:** `BaseCache` uses `VolatileCacheWindowRetention.duration()` when sweeping TTL. **Edge input window:** `DefaultEdgeComputationEngine.runCycle` passes `VolatileCacheWindowRetention.duration()` into `CachedWindowSelector.filterWindow` (`DefaultEdgeComputationEngine.kt`, `CachedWindowSelector.kt`). |
| **Where does model inference occur?** | **Core:** `DefaultEdgeComputationEngine.runCycle` calls `TfliteInterpreterBridge.runVectorInference` after `PhenotypeFeatureEncoder.encode` (`StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/edge/DefaultEdgeComputationEngine.kt`). **Android bridge:** `StanfordScreenomics/app/src/main/java/edu/stanford/screenomics/edge/AndroidTfliteInterpreterBridge.kt` — `AndroidTfliteInterpreterBridge`. |
| **Cycle driver** | `StanfordScreenomics/app/src/main/java/edu/stanford/screenomics/collection/ModalityCollectionService.kt` — `edgeJob` loop: `runCycle` then `delay(EDGE_INTERVAL_MS)` where `EDGE_INTERVAL_MS = 60_000L` (60 s between cycles; distinct from the 30 min **data** window). |

**Window selection (edge)**

```32:44:StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/edge/DefaultEdgeComputationEngine.kt
    override suspend fun runCycle(
        cacheManager: CacheManager,
        interventionController: InterventionController,
        activeJobIds: Set<String>,
    ): InterventionDirective {
        EdgeComputationCachePathVerifierPlaceholder.assertCacheBackedSourceOnly(cacheManager)
        val snapshots = cacheManager.snapshotByCacheId()
        val flat = snapshots.values.flatten()
        val windowEnd = CachedWindowSelector.windowEndInclusive(flat, clock.instant())
        val windowDuration = VolatileCacheWindowRetention.duration()
        val windowedById = snapshots.mapValues { (_, pts) ->
            CachedWindowSelector.filterWindow(pts, windowEnd, windowDuration)
        }
```

**Supporting types**

| File | Class |
|------|-------|
| `StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/edge/CachedWindowSelector.kt` | `CachedWindowSelector` |
| `StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/edge/PhenotypeAnalyzer.kt` | `PhenotypeAnalyzer`, `HeuristicPhenotypeAnalyzer` |
| `StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/edge/PhenotypeSummary.kt` | `PhenotypeSummary` |
| `StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/management/EdgeComputationEngine.kt` | `EdgeComputationEngine` |

---

## Intervention Controller

| Question | Answer |
|----------|--------|
| **How are decisions triggered?** | At the end of each `DefaultEdgeComputationEngine.runCycle`, after phenotype analysis and optional TFLite, the engine builds `InterventionContext` and calls `interventionController.evaluate(context)` (`DefaultEdgeComputationEngine.kt`). |
| **Where does phenotype comparison happen?** | **No explicit diff vs a prior phenotype snapshot** in `DefaultInterventionController`. Policy is **threshold-based on the current cycle’s** `InterventionContext` (e.g. `phenotypeSummary.stressScore`, TFLite `aggregateScore`). |

| Item | File | Class |
|------|------|-------|
| Contract | `StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/management/InterventionController.kt` | `InterventionController` |
| Default policy | `StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/management/DefaultInterventionController.kt` | `DefaultInterventionController` |
| Context DTO | `StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/management/InterventionContext.kt` | `InterventionContext` |
| Directives | Same package / `InterventionDirective` types (e.g. `SamplingPolicyDirective`, `NoInterventionDirective`) | consumed by edge cycle logging / future actuators |

**Decision logic (excerpt)**

```32:68:StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/management/DefaultInterventionController.kt
        val tfliteHot = context.tfliteInferenceTraces.any { !it.skipped && it.aggregateScore > 0.85 }
        if (tfliteHot) {
            val d = SamplingPolicyDirective(
                targetModalityKindName = ModalityKind.SCREENSHOT.name,
                policyToken = "edge_tflite_high_activation_throttle",
            )
            ...
            return d
        }
        if (context.phenotypeSummary.stressScore >= 0.85) {
            val d = SamplingPolicyDirective(
                targetModalityKindName = ModalityKind.AUDIO.name,
                policyToken = "edge_phenotype_high_density_throttle",
            )
            ...
            return d
        }
        ...
        return NoInterventionDirective
```

---

# 3. Module Pipelines

Legend: **Memory (30 min)** = sliding retention via `VolatileCacheWindowRetention` + `BaseCache` eviction, unless a module overrides volatile retention (Motion excludes continuous IMU from the in-memory map).

---

## 3.1 Audio

### Pipeline

`AudioDataNode` → `AudioAdapter` → `AudioCache` → **`AndroidModalityLocalFileSink`** (`files/audio/`) → **`DefaultDistributedStorageManager`** (Firestore + optional Cloud Storage + `file.location` merge).

### Implementation Mapping

| Stage | File | Class |
|-------|------|-------|
| DataNode | `StanfordScreenomics/modules/audio/src/main/kotlin/com/app/modules/audio/AudioDataNode.kt` | `AudioDataNode` |
| Adapter | `StanfordScreenomics/modules/audio/src/main/kotlin/com/app/modules/audio/AudioAdapter.kt` | `AudioAdapter` |
| Cache | `StanfordScreenomics/modules/audio/src/main/kotlin/com/app/modules/audio/AudioCache.kt` | `AudioCache` |
| Module wiring | `StanfordScreenomics/modules/audio/src/main/kotlin/com/app/modules/audio/AudioModule.kt` | `AudioModule` |

### Data Stored

| Tier | Content | Mechanism / path |
|------|---------|------------------|
| **Local** | Zlib-compressed PCM `audio_<yyyyMMddHHmmssSSS>.bin` (when not deduplicated) | `AudioAdapter` + `ModalityLocalFileSink`; root `files/audio/` via `AndroidModalityLocalFileSink` / `ModalityStorageDirectoryName.AUDIO` |
| **Memory (30 min)** | Full `UnifiedDataPoint` rows in volatile store | `AudioCache` / `BaseCache.put` |
| **Cloud** | Firestore structured map; Cloud Storage blob; `mergeStructuredDocumentEncodedData` / `file.location` | `DefaultDistributedStorageManager.onUnifiedPointCommitted`, `uploadAudioOrScreenshotMediaAndMergeFileLocation` |

---

## 3.2 Motion

### Pipeline

`MotionDataNode` → `MotionAdapter` → `MotionCache` → **`DefaultDistributedStorageManager`** writes `motion/imu/*.json`, `motion/steps/*.json` under `files/motion/` (and optional `pending_firestore/`) → Firestore / controlled paths.

### Implementation Mapping

| Stage | File | Class |
|-------|------|-------|
| DataNode | `StanfordScreenomics/modules/motion/src/main/kotlin/com/app/modules/motion/MotionDataNode.kt` | `MotionDataNode` |
| Adapter | `StanfordScreenomics/modules/motion/src/main/kotlin/com/app/modules/motion/MotionAdapter.kt` | `MotionAdapter` |
| Cache | `StanfordScreenomics/modules/motion/src/main/kotlin/com/app/modules/motion/MotionCache.kt` | `MotionCache` |
| Module wiring | `StanfordScreenomics/modules/motion/src/main/kotlin/com/app/modules/motion/MotionModule.kt` | `MotionModule` |
| Firestore selection helpers | `StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/storage/MotionStructuredCloudUploadSelectors.kt` | `MotionStructuredCloudUploadSelectors` |

### Data Stored

| Tier | Content | Notes |
|------|---------|--------|
| **Local** | JSON under `motion/imu/` and `motion/steps/`; optional `motion/pending_firestore/` when accel/gyro Firestore upload paused | `DefaultDistributedStorageManager.buildMotionLocalJson` |
| **Memory (30 min)** | **Non-IMU** points retained in volatile map (e.g. step rollup with `motion.step.sessionTotal`); continuous accel/gyro may still trigger `onAfterUnifiedPointCommittedOutsideLock` without volatile retention | `MotionCache.shouldRetainPointInVolatileStore` |
| **Cloud** | Firestore structured documents; optional RTDB mirror | `AndroidFirestoreStructuredWriteBridge`, `AndroidRealtimeStructuredWriteBridge` |

---

## 3.3 GPS

### Pipeline

`GpsDataNode` (location from `LocationSnapshotReader`) → `GpsAdapter` (Open-Meteo / optional OpenWeather) → `GpsCache` → **`DefaultDistributedStorageManager`** writes `gps_<stamp>.json` under `files/gps/` → Firestore.

### Implementation Mapping

| Stage | File | Class |
|-------|------|-------|
| DataNode | `StanfordScreenomics/modules/gps/src/main/kotlin/com/app/modules/gps/GpsDataNode.kt` | `GpsDataNode` |
| Adapter | `StanfordScreenomics/modules/gps/src/main/kotlin/com/app/modules/gps/GpsAdapter.kt` | `GpsAdapter` |
| Cache | `StanfordScreenomics/modules/gps/src/main/kotlin/com/app/modules/gps/GpsCache.kt` | `GpsCache` |
| Module wiring | `StanfordScreenomics/modules/gps/src/main/kotlin/com/app/modules/gps/GpsModule.kt` | `GpsModule` |
| Location bridge | `StanfordScreenomics/app/src/main/java/edu/stanford/screenomics/collection/LocationSnapshotReader.kt` | `LocationSnapshotReader` |

### Data Stored

| Tier | Content |
|------|---------|
| **Local** | `gps_<stamp>.json` (lat, lon, sun/score fields, metadata, epoch timestamp) via `buildGpsLocalJson` |
| **Memory (30 min)** | Full `UnifiedDataPoint` in `GpsCache` within sliding TTL |
| **Cloud** | Firestore via `UnifiedDataPointPersistenceCodec`; local JSON removed on successful structured write |

---

## 3.4 Screenshot

### Pipeline

`ScreenshotDataNode` (bitmap supplier from `MediaProjectionScreenCapture`) → `ScreenshotAdapter` (ML Kit OCR + optional TFLite / lexicon sentiment) → `ScreenshotCache` → PNG under `files/screenshot/` → Cloud Storage + Firestore `file.location` merge.

### Implementation Mapping

| Stage | File | Class |
|-------|------|-------|
| DataNode | `StanfordScreenomics/modules/screenshot/src/main/kotlin/com/app/modules/screenshot/ScreenshotDataNode.kt` | `ScreenshotDataNode` |
| Adapter | `StanfordScreenomics/modules/screenshot/src/main/kotlin/com/app/modules/screenshot/ScreenshotAdapter.kt` | `ScreenshotAdapter` |
| Cache | `StanfordScreenomics/modules/screenshot/src/main/kotlin/com/app/modules/screenshot/ScreenshotCache.kt` | `ScreenshotCache` |
| Module wiring | `StanfordScreenomics/modules/screenshot/src/main/kotlin/com/app/modules/screenshot/ScreenshotModule.kt` | `ScreenshotModule` |
| Media capture | `StanfordScreenomics/app/src/main/java/edu/stanford/screenomics/screenshot/MediaProjectionScreenCapture.kt` | `MediaProjectionScreenCapture` |

### Data Stored

| Tier | Content |
|------|---------|
| **Local** | Downscaled PNG `screenshot_<stamp>.png` (`storageDownscaleFactor` default 0.5) |
| **Memory (30 min)** | Full `UnifiedDataPoint` in `ScreenshotCache` |
| **Cloud** | Firestore structured fields + PNG in Cloud Storage via `AndroidCloudMediaStorageBridge` |

---

# 4. Data Flow (End-to-End)

**Chain:** Raw → Adapter → `UnifiedDataPoint` → Cache → Storage → Edge → Intervention

1. **Raw:** Each `*DataNode` feeds `RawModalityFrame` into `ModulePipeline.rawFrames` (`ModulePipeline.kt`).
2. **Adapter:** `Adapter.adapt` (`BaseAdapter` → module `adaptRaw`) returns `UnifiedDataPoint` with modality keys and UFS reserved keys (`UfsReservedDataKeys`).
3. **UnifiedDataPoint:** `UnifiedFusionConsistency.validateOrThrow` in pipeline (and `UnifiedDataPoint` `init`) enforces metadata and reserved entries.
4. **Cache:** `BaseCache.put` updates volatile store (subject to modality hooks such as `MotionCache`), evicts by sliding TTL from `VolatileCacheWindowRetention.duration()`, then invokes `onAfterUnifiedPointCommittedOutsideLock`.
5. **Storage:** `MainActivity` wires `storageFanOut` to `DefaultDistributedStorageManager.onUnifiedPointCommitted` for all four caches (`MainActivity.kt`).
6. **Edge:** `ModalityCollectionService` `edgeJob` repeatedly calls `edgeComputationEngine.runCycle` with `runtime.cacheManager` and `runtime.interventionController` (`ModalityCollectionService.kt`).
7. **Intervention:** `DefaultInterventionController.evaluate` returns a directive from the current `InterventionContext` (`DefaultInterventionController.kt`).

**Runtime construction (storage fan-out + caches)**

```134:168:StanfordScreenomics/app/src/main/java/edu/stanford/screenomics/MainActivity.kt
            val edgeComputationEngine: EdgeComputationEngine = DefaultEdgeComputationEngine(
                tfliteInterpreterBridge = AndroidTfliteInterpreterBridge(applicationContext),
                modelAssetPath = null,
            )
            val taskScheduler: TaskScheduler = DefaultTaskScheduler()
            val interventionController: InterventionController = DefaultInterventionController()
            val cacheManager: LifecycleAwareCacheManager = DefaultCacheManager()
            val appCtx = applicationContext
            val localSink = AndroidModalityLocalFileSink(appCtx)
            val distributedStorageManager = DefaultDistributedStorageManager(
                localFileSink = localSink,
                firestoreBridge = AndroidFirestoreStructuredWriteBridge(appCtx),
                cloudMediaBridge = AndroidCloudMediaStorageBridge(appCtx),
                realtimeBridge = AndroidRealtimeStructuredWriteBridge(appCtx),
            )
            val storageFanOut: suspend (UnifiedDataPoint) -> Unit = { point ->
                distributedStorageManager.onUnifiedPointCommitted(point)
            }
            val audioCache = AudioCache(onAfterUnifiedPointCommittedOutsideLock = storageFanOut)
            val motionCache = MotionCache(onAfterUnifiedPointCommittedOutsideLock = storageFanOut)
            val gpsCache = GpsCache(onAfterUnifiedPointCommittedOutsideLock = storageFanOut)
            val screenshotCache = ScreenshotCache(onAfterUnifiedPointCommittedOutsideLock = storageFanOut)
            PhenotypingRuntime(
                captureSessionId = UUID.randomUUID().toString(),
                audioCache = audioCache,
                motionCache = motionCache,
                gpsCache = gpsCache,
                screenshotCache = screenshotCache,
                modalityLocalFileSink = localSink,
                distributedStorageManager = distributedStorageManager,
                cacheManager = cacheManager,
                taskScheduler = taskScheduler,
                edgeComputationEngine = edgeComputationEngine,
                interventionController = interventionController,
            ).also { app.phenotypingRuntime = it }
```

**Parallel modality collection**

```227:235:StanfordScreenomics/app/src/main/java/edu/stanford/screenomics/collection/ModalityCollectionService.kt
        for (node in activeNodes) {
            val j = serviceScope.launch {
                runCatching {
                    node.activate(this)
                    node.observeUnifiedOutputs().collect { }
                }
            }
            collectionJobs += j
        }
```

**Edge loop (cadence)**

```243:254:StanfordScreenomics/app/src/main/java/edu/stanford/screenomics/collection/ModalityCollectionService.kt
        edgeJob?.cancel()
        edgeJob = serviceScope.launch {
            while (isActive) {
                runCatching {
                    runtime.edgeComputationEngine.runCycle(
                        cacheManager = runtime.cacheManager,
                        interventionController = runtime.interventionController,
                        activeJobIds = setOf("collection-audio", "collection-motion", "collection-gps", "collection-screenshot"),
                    )
                }
                delay(EDGE_INTERVAL_MS)
            }
        }
```

---

# 5. Compliance with Specification

Cross-reference: `Data Cache and Storage Location` (repository root).

| Specification theme | Implementation |
|----------------------|----------------|
| **Real-time phenotyping** | **Satisfied:** `DefaultEdgeComputationEngine.runCycle` runs on a timer (60 s) over **live** `CacheManager` snapshots, windowed by `VolatileCacheWindowRetention.duration()` (default 30 min of retained points). |
| **Batch upload** | **Not implemented as queued batch/midnight flush:** `DefaultDistributedStorageManager.onUnifiedPointCommitted` schedules work **per committed point**. `BatchUploadPolicyPlaceholder` documents future batching; enqueue helpers largely always allow work. |
| **Parallel processing** | **Satisfied:** four parallel `Job`s in `ModalityCollectionService`; `DefaultTaskScheduler` additionally retunes modality parallelism under `ResourceStressLevel`. |
| **Metadata standardization** | **Satisfied:** every `UnifiedDataPoint` carries `DataDescription`; `UnifiedFusionConsistency` + `UnifiedDataPointPersistenceCodec` enforce/encode a consistent envelope. **`DataEntity` is schema-only** (`UfsSchemaComposition`), not duplicated per point at runtime. |

**Additional traceability (two-layer, storage, intervention)**

| Theme | Status |
|-------|--------|
| Two-layer separation (collection vs edge) | **Satisfied:** edge reads `CacheManager` only (`DefaultEdgeComputationEngine` KDoc). |
| Per-modality local paths | **Satisfied:** `AndroidModalityLocalFileSink` + `ModalityStorageDirectoryName`. |
| Intervention tied to phenotype *change* | **Gap vs strict wording:** `DefaultInterventionController` uses **fixed thresholds** on the current context, not a differential vs prior phenotype (see §2.6). |

---

# 6. File/Folder Structure Map

Repository root: `MobileDigitalPhenotyping/`

| Path | Purpose |
|------|---------|
| `Data Cache and Storage Location` | Narrative **specification** for minimal fields, caches, storage layout. |
| `doc/architecture.md` | Overview-style architecture documentation (alternate heading hierarchy). |
| `doc/spec-implementation-cross-reference.md` | **This file** — structured spec → implementation cross-reference. |
| `StanfordScreenomics/` | Gradle **multi-module** Android application. |
| `StanfordScreenomics/app/` | Application module: `MainActivity`, `ModalityCollectionService`, `PhenotypingRuntime`, `ScreenomicsApp`, Android bridges (`storage/`, `scheduling/`, `edge/`, `screenshot/`, `settings/`). |
| `StanfordScreenomics/core/` | Shared library: `unified/` (UFS), `collection/` (contracts), `module/template/` (pipeline), `management/` (caches, intervention), `storage/`, `scheduling/`, `edge/`. |
| `StanfordScreenomics/modules/audio/` | Audio modality implementation. |
| `StanfordScreenomics/modules/motion/` | Motion modality implementation. |
| `StanfordScreenomics/modules/gps/` | GPS modality implementation. |
| `StanfordScreenomics/modules/screenshot/` | Screenshot modality implementation. |
| `StanfordScreenomics/enginePhenotype/` | Separate RF/engine demo module (not the same as `DefaultEdgeComputationEngine` heuristic path). |

**Dependency rule:** `app` depends on `core` and all `modules:*`; each modality module depends on `core` only.

---

*Update `doc/spec-implementation-cross-reference.md` when UFS fields, storage paths, edge cadence, or intervention policies change.*
