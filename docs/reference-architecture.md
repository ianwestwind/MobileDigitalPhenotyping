# Stanford Screenomics — A Software Reference Architecture for Real-Time Mobile Digital Phenotyping

This document provides a **traceable mapping between the architecture described in the manuscript and its implementation** in the StanfordScreenomics Android codebase.

It maps the **two-layer mobile digital phenotyping reference architecture** (see also the repository file `Data Cache and Storage Location`) to the corresponding components in the StanfordScreenomics implementation.

---

## 1. Overview

### 1.1 System Description

The system implements an **on-device mobile digital phenotyping pipeline** that:

1. **Collects** multimodal sensor streams, including:
  - audio (processed into noise levels),
  - IMU motion (accelerometer and gyroscope, processed into step counts),
  - GPS (with derived weather score),
  - screenshots (processed into sentiment scores).
2. **Normalizes** each modality using a shared **Unified Fusion Standard (UFS)** carrier: `UnifiedDataPoint`, which consists of a payload map and associated `DataDescription` metadata.
3. **Retains** recent data in **per-modality in-memory caches** with a configurable sliding time window (default: **30 minutes**).
4. **Persists** data to **app-private `files/` directories** organized by modality, and performs **batched uploads** to Firebase services:
  - Firestore for structured data  
  - Cloud Storage for binary media  
  - Realtime Database when enabled in configuration
5. **Executes edge computation** on **cache snapshots only** (not raw incoming data): a per-cycle **edge window-summary hook** (Kotlin `PhenotypeAnalyzer`; default `HeuristicPhenotypeAnalyzer`), optional on-device TensorFlow Lite inference, and intervention **policy evaluation**. Separately, the **phenotype analyzer** in the manuscript sense—the **random forest** in `enginePhenotype`—builds the **phenotype summary** (importances + model statistics); see `doc/phenotype.md`.

The central orchestration component is `edu.stanford.screenomics.PhenotypingRuntime`  
(`app/src/main/java/edu/stanford/screenomics/PhenotypingRuntime.kt`). This component is instantiated in `MainActivity` and shared with background services (e.g., `ModalityCollectionService`) via `ScreenomicsApp.phenotypingRuntime`.

---

### 1.2 Architecture Summary: Two Layers and Intervention Engine

The architecture is organized into two primary layers:

- **Data Collection Layer**  
- **Data Management Layer**

The **intervention engine** is implemented as a separate component. It evaluates policies based on context produced after edge computation (phenotype analysis) and is not part of either layer.

Intervention evaluation is invoked at the end of each computation cycle within:  
`DefaultEdgeComputationEngine.runCycle()`.


| Component                                    | Role (Specification Intent)                                                                                                              | Implementation Anchor                                                                                                                                                                                                                                                                                                     |
| -------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Layer 1 — Data Collection**                | Raw data capture → adapter transformation → UFS (`UnifiedDataPoint`) → in-memory cache                                                   | `DataNode` implementations and `ModulePipeline` (`core/src/main/kotlin/edu/stanford/screenomics/core/module/template/ModulePipeline.kt`)                                                                                                                                                                                  |
| **Layer 2 — Data Management**                | Cache coordination, local and distributed storage, and phenotype analysis                                                                | `LifecycleAwareCacheManager` / `DefaultCacheManager`, `DefaultDistributedStorageManager`, `DefaultEdgeComputationEngine`                                                                                                                                                                                                  |
| **Intervention Engine** (outside Layers 1–2) | Policy evaluation over edge-derived context (phenotype summaries, optional TFLite outputs, recent cached data) → `InterventionDirective` | `InterventionController` (`core/src/main/kotlin/edu/stanford/screenomics/core/management/InterventionController.kt`), default implementation `DefaultInterventionController` (`.../management/DefaultInterventionController.kt`), invoked from `DefaultEdgeComputationEngine.runCycle()` after phenotype and TFLite steps |


### Edge Computation Data Flow Constraint

The edge computation engine operates **exclusively on cached data (`CacheManager` stapshots)** and does not access raw data streams directly.

```kotlin
/**
 * Default [EdgeComputationEngine]: sliding cache window from [VolatileCacheWindowRetention],
 * [PhenotypeAnalyzer], optional [TfliteInterpreterBridge], then [InterventionController.evaluate].
 *
 * Data source: only [CacheManager] snapshots of registered caches.
 * No direct access to DataNode raw streams or pipeline ingress.
 */
```

---

## 2. Architecture Mapping (Spec → Code)

### 2.1 Data Collection Layer

Each modality uses a **DataNode** to capture raw input. An **Adapter** transforms raw frames into **Unified Fusion Standard (UFS) outputs**. The **ModulePipeline** connects the processing stages:

`raw Flow → Adapter.adapt → UFS validation → InMemoryCache.put`

**Implementation**


| Concern               | Files                                                                                                                                    | Classes / Types                                                        | Responsibilities                                                                                           |
| --------------------- | ---------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------- |
| Raw frame contract    | `core/src/main/kotlin/edu/stanford/screenomics/core/collection/RawModalityFrame.kt` (and modality-specific subtypes in the same package) | `RawModalityFrame`, `AudioPcmBufferRawFrame`, `MotionAccelRawFrame`, … | Typed ingress payloads from sensors or capture subsystems                                                  |
| DataNode contract     | `core/src/main/kotlin/edu/stanford/screenomics/core/collection/DataNode.kt`                                                              | `DataNode`                                                             | `activate` / `deactivate`, `observeUnifiedOutputs(): Flow<UnifiedDataPoint>`, `modalityKind()`             |
| DataNode base         | `core/src/main/kotlin/edu/stanford/screenomics/core/module/template/BaseDataNode.kt`                                                     | `BaseDataNode`                                                         | Owns `ModulePipeline` and exposes unified output flow                                                      |
| Pipeline wiring       | `core/src/main/kotlin/edu/stanford/screenomics/core/module/template/ModulePipeline.kt`                                                   | `ModulePipeline`                                                       | `rawFrames → adapter.adapt → UnifiedFusionConsistency.validateOrThrow → cache.put`                         |
| Concurrency per stage | `core/src/main/kotlin/edu/stanford/screenomics/core/module/template/ModulePipelineDispatchers.kt`                                        | `ModulePipelineDispatchers`                                            | Defines coroutine contexts: `rawIngress`, `adaptation`, `cacheCommit`, `channelDelivery`                   |
| Adapter contract      | `core/src/main/kotlin/edu/stanford/screenomics/core/collection/Adapter.kt`                                                               | `Adapter`                                                              | `suspend fun adapt(RawModalityFrame): UnifiedDataPoint`                                                    |
| Adapter base          | `core/src/main/kotlin/edu/stanford/screenomics/core/module/template/BaseAdapter.kt`                                                      | `BaseAdapter`                                                          | Wraps `adaptRaw` and provides diagnostic logging                                                           |
| Foreground collection | `app/src/main/java/edu/stanford/screenomics/collection/ModalityCollectionService.kt`                                                     | `ModalityCollectionService`                                            | Starts modality pipelines in **parallel coroutines** (`node.activate` + `observeUnifiedOutputs().collect`) |


**Module factories (wiring DataNode + Adapter + Cache)**

- `modules/audio/src/main/kotlin/com/app/modules/audio/AudioModule.kt`  
→ `AudioModule.create()` → `AudioDataNode` + `AudioAdapter` + `AudioCache`
- `modules/motion/src/main/kotlin/com/app/modules/motion/MotionModule.kt`  
→ `MotionModule.create()` → `MotionDataNode` + `MotionAdapter` + `MotionCache`
- `modules/gps/src/main/kotlin/com/app/modules/gps/GpsModule.kt`  
→ `GpsModule.create()` → `GpsDataNode` + `GpsAdapter` + `GpsCache`
- `modules/screenshot/src/main/kotlin/com/app/modules/screenshot/ScreenshotModule.kt`  
→ `ScreenshotModule.create()` → `ScreenshotDataNode` + `ScreenshotAdapter` + `ScreenshotCache`

---

### 2.2 Data Management Layer

The data management layer consists of **in-memory volatile caches (sliding window)**, **local per-modality file storage**, **cloud upload pipelines**, and **real-time phenotype analysis over cached Unified Data Points**.

**Implementation**


| Concern                | Files                                                                                                                                      | Classes                                             | Responsibilities                                                                                                                                                                                         |
| ---------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ | --------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Cache trait            | `core/src/main/kotlin/edu/stanford/screenomics/core/management/InMemoryCache.kt`                                                           | `InMemoryCache`                                     | `put`, `get`, `snapshot`, `sweepSlidingWindowTtl`, etc.                                                                                                                                                  |
| Sliding window length  | `core/src/main/kotlin/edu/stanford/screenomics/core/management/VolatileCacheWindowRetention.kt`                                            | `VolatileCacheWindowRetention`                      | Defines global cache window duration (default: 30 minutes); synchronized via `VolatileCacheWindowPrefs` (`app/.../settings/VolatileCacheWindowPrefs.kt`)                                                 |
| Cache base structure   | `core/src/main/kotlin/edu/stanford/screenomics/core/module/template/BaseCache.kt`                                                          | `BaseCache`                                         | Maintains `LinkedHashMap` of `UnifiedDataPoint` indexed by `CorrelationId`; supports TTL eviction and sliding-window retention logic                                                                     |
| Cache orchestration    | `core/src/main/kotlin/edu/stanford/screenomics/core/management/DefaultCacheManager.kt`, `LifecycleAwareCacheManager.kt`, `CacheManager.kt` | `DefaultCacheManager`, `LifecycleAwareCacheManager` | Registers modality caches and provides `snapshotByCacheId()` for phenotype analysis layer                                                                                                                |
| Local file storage     | `app/src/main/java/edu/stanford/screenomics/storage/AndroidDistributedStorageBridges.kt`                                                   | `AndroidModalityLocalFileSink`                      | Implements `ModalityLocalFileSink`; writes to `files/<modality>/` directories                                                                                                                            |
| Modality directories   | `core/src/main/kotlin/edu/stanford/screenomics/core/storage/ModalityStorageDirectoryName.kt`                                               | `ModalityStorageDirectoryName`                      | Defines per-modality storage roots: `audio`, `motion`, `gps`, `screenshot` under `app/filesDir`                                                                                                          |
| Cloud upload + fan-out | `core/src/main/kotlin/edu/stanford/screenomics/core/storage/DefaultDistributedStorageManager.kt`                                           | `DefaultDistributedStorageManager`                  | Handles `onUnifiedPointCommitted(UnifiedDataPoint)`: writes structured data to Firestore, uploads media to Cloud Storage, optionally mirrors to Realtime Database, and manages post-upload cleanup logic |
| Serialization layer    | `core/src/main/kotlin/edu/stanford/screenomics/core/storage/UnifiedDataPointPersistenceCodec.kt`                                           | `UnifiedDataPointPersistenceCodec`                  | Converts `UnifiedDataPoint` into Firestore/Realtime Database-compatible structured maps                                                                                                                  |
| Upload policy layer    | `core/src/main/kotlin/edu/stanford/screenomics/core/storage/BatchUploadPolicyPlaceholder.kt`                                               | `BatchUploadPolicyPlaceholder`                      | Defines batching and conditional upload hooks (structured vs media uploads); currently permissive with diagnostic batching support                                                                       |


**Runtime Bundle (app)**

- `app/src/main/java/edu/stanford/screenomics/PhenotypingRuntime.kt`  
→ Central runtime container holding:
  - `LifecycleAwareCacheManager`
  - `DefaultDistributedStorageManager`
  - `TaskScheduler`
  - `EdgeComputationEngine`
  - `InterventionController`
  - modality cache registry and execution coordination

---

### 2.3 Unified Fusion Standard


| Spec concept                                               | Kotlin type                | Defined in                                                                               | Used in                                                                                                                                                                                                        |
| ---------------------------------------------------------- | -------------------------- | ---------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| UFS carrier interface                                      | `UnifiedFusionStandard`    | `core/src/main/kotlin/edu/stanford/screenomics/core/unified/UnifiedFusionStandard.kt`    | Implemented by `UnifiedDataPoint`                                                                                                                                                                              |
| Payload + metadata instance (runtime unit)                 | `UnifiedDataPoint`         | `core/src/main/kotlin/edu/stanford/screenomics/core/unified/UnifiedDataPoint.kt`         | Emitted by all adapters; stored in caches; serialized by `UnifiedDataPointPersistenceCodec`; processed by `DefaultDistributedStorageManager` and `DefaultEdgeComputationEngine`                                |
| Capture metadata                                           | `DataDescription`          | `core/src/main/kotlin/edu/stanford/screenomics/core/unified/DataDescription.kt`          | Required on every `UnifiedDataPoint` (`metadata` property)                                                                                                                                                     |
| Schema descriptor (structure definition, not runtime data) | `DataEntity`               | `core/src/main/kotlin/edu/stanford/screenomics/core/unified/DataEntity.kt`               | Used for modality schema definition and validation context; composed in `UfsSchemaComposition.kt` (not embedded as a field on `UnifiedDataPoint`); referenced during adapter design and UFS consistency checks |
| Reserved payload keys                                      | `UfsReservedDataKeys`      | `core/src/main/kotlin/edu/stanford/screenomics/core/unified/UfsReservedDataKeys.kt`      | Required keys validated in `UnifiedFusionConsistency`                                                                                                                                                          |
| UFS validation                                             | `UnifiedFusionConsistency` | `core/src/main/kotlin/edu/stanford/screenomics/core/unified/UnifiedFusionConsistency.kt` | Enforced at adapter output boundary and pipeline stage to ensure all `UnifiedDataPoint` instances conform to UFS rules                                                                                         |


**Enforcement point (pipeline)**

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

### 2.4 Task Scheduler

**Implementation Location**

- **Interface:** `core/src/main/kotlin/edu/stanford/screenomics/core/scheduling/TaskScheduler.kt` — `TaskScheduler`
- **Default implementation:** `core/src/main/kotlin/edu/stanford/screenomics/core/scheduling/DefaultTaskScheduler.kt` — `DefaultTaskScheduler`
- **Resource thresholds:** `core/src/main/kotlin/edu/stanford/screenomics/core/scheduling/ResourceStressThresholds.kt` — `ResourceStressThresholds`
- **Stress evaluation logic:**
  - `ResourceStressEvaluator.kt`
  - `ResourceStressSignals.kt`
  - `ResourceStressLevel.kt`
- **Android host signal provider:** `app/src/main/java/edu/stanford/screenomics/scheduling/AndroidHostResourceSignalProvider.kt` — `AndroidHostResourceSignalProvider`
- **Initialization entry point:** `app/src/main/java/edu/stanford/screenomics/MainActivity.kt`  
→ `taskScheduler.startResourceMonitoring(lifecycleScope, AndroidHostResourceSignalProvider(...))`

**Resource monitoring pipeline**

1. `DefaultTaskScheduler.startResourceMonitoring` periodically samples
  `HostResourceSignalProvider.currentSnapshot()` (default interval: **1000 ms**).
2. `ResourceStressEvaluator.signals(snapshot, thresholds)` evaluates system state:
  - CPU load exceeds configured threshold
  - Available memory falls below configured threshold
  - Battery level falls below configured threshold
3. Signals are aggregated into a `ResourceStressLevel`:
  - `NORMAL`
  - `STRESSED`
  - `CRITICAL`
4. `DefaultTaskScheduler` applies adaptive execution control:
  - updates `DynamicTaskPriorityRegistry`
  - adjusts parallelism via `PipelineParallelismPlan`
  - applies dispatcher constraints using:
    - `SwitchableCoroutineDispatcher`
    - `LimitedDispatcherCache`

**Default thresholds (product spec alignment):**

```6:12:StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/scheduling/ResourceStressThresholds.kt
data class ResourceStressThresholds(
    /** Stress when **process** CPU heuristic is **strictly greater** than this value (e.g. 0.85 = 85%). */
    val cpuLoadStrictlyAbove: Double = 0.85,
    /** Stress when available memory is **strictly below** this many bytes (e.g. 200 MiB). */
    val availableMemoryStrictlyBelowBytes: Long = 200L * 1024L * 1024L,
    /** Stress when battery fraction is **strictly below** this value (e.g. 0.20 = 20%). */
    val batteryFractionStrictlyBelow: Double = 0.20,
```

**Per-modality pipeline dispatchers**

- `TaskScheduler.modulePipelineDispatchers(ModalityKind)` returns `ModulePipelineDispatchers` used when constructing each modality module in `ModalityCollectionService`.

---

### 2.5 Edge Computation


| Topic                         | Where enforced / implemented                                                                          | Mechanism                                                                                                                                                                         |
| ----------------------------- | ----------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Sliding time window           | `DefaultEdgeComputationEngine.runCycle`                                                               | Applies `VolatileCacheWindowRetention.duration()` per-cache snapshot and filters via `CachedWindowSelector.filterWindow` after snapshot aggregation.                              |
| Window boundary definition    | `core/src/main/kotlin/edu/stanford/screenomics/core/edge/CachedWindowSelector.kt`                     | Computes `windowEndInclusive` using `DataDescription.timestamp` and current system clock.                                                                                         |
| Phenotype analyzer (RF)       | `enginePhenotype/src/main/kotlin/com/app/enginephenotype/EnginePhenotypeStepCountRandomForest.kt` (alignment in `MultimodalTemporalAlignment.kt`) | Multimodal temporal alignment → `RandomForest.fit` → **phenotype summary** (report + `PhenotypeRunEnvelope`). Triggered from `MainActivity` (`PhenotypeWindowPrefs` auto interval). **This** is the manuscript phenotype analyzer. |
| Edge window-summary hook      | `core/src/main/kotlin/edu/stanford/screenomics/core/edge/PhenotypeAnalyzer.kt`, `PhenotypeSummary.kt` | Per-cycle `analyze(recentPoints)` (default `HeuristicPhenotypeAnalyzer` in `DefaultEdgeComputationEngine`). Produces compact `PhenotypeSummary` for policy/TFLite encoding—**not** the RF phenotype analyzer. |
| Model inference (conditional) | `core/src/main/kotlin/edu/stanford/screenomics/core/edge/DefaultEdgeComputationEngine.kt`             | Executes `TfliteInterpreterBridge.runVectorInference` after `PhenotypeFeatureEncoder.encode` when inference is enabled. Android implementation: `AndroidTfliteInterpreterBridge`. |
| Edge cycle orchestration      | `app/src/main/java/edu/stanford/screenomics/collection/ModalityCollectionService.kt`                  | `edgeJob` loop executes `edgeComputationEngine.runCycle(...)` at fixed interval (`EDGE_INTERVAL_MS`) using coroutine delay-based scheduling.                                      |


**Window selection (code)**

```32:44:StanfordScreenomics/core/src/main/kotlin/edu/stanford/screenomics/core/edge/DefaultEdgeComputationEngine.kt
    override suspend fun runCycle(
        cacheManager: CacheManager,
        interventionController: InterventionController,
        activeJobIds: Set<String>,
    ): InterventionDirective {
        ...
        val snapshots = cacheManager.snapshotByCacheId()
        val flat = snapshots.values.flatten()
        val windowEnd = CachedWindowSelector.windowEndInclusive(flat, clock.instant())
        val windowDuration = VolatileCacheWindowRetention.duration()
        val windowedById = snapshots.mapValues { (_, pts) ->
            CachedWindowSelector.filterWindow(pts, windowEnd, windowDuration)
        }
```

---

### 2.6 Intervention Controller


| Topic                  | Implementation                                                                                                                     | Notes                                                                                                                  |
| ---------------------- | ---------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------- |
| Interface              | `core/src/main/kotlin/edu/stanford/screenomics/core/management/InterventionController.kt` — `InterventionController`               | `suspend fun evaluate(context: InterventionContext): InterventionDirective`                                            |
| Default policy         | `core/src/main/kotlin/edu/stanford/screenomics/core/management/DefaultInterventionController.kt` — `DefaultInterventionController` | Study placeholder implementing threshold-based policy over phenotype and inference signals.                            |
| Context assembly       | `DefaultEdgeComputationEngine.runCycle`                                                                                            | Constructs `InterventionContext` from cache snapshots, `PhenotypeSummary`, and optional `TfliteInferenceTraces`.       |
| State dependency model | `DefaultInterventionController`                                                                                                    | Decisions are evaluated per-cycle using the current `InterventionContext` only (no historical phenotype differencing). |


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

## 3. Data Collection Module Pipelines

All data collection modules in the system (Audio, Motion, GPS, Screenshot) follow a **uniform pipeline architecture** composed of DataNode ingestion, Adapter-based UFS normalization, in-memory caching, local persistence, cloud synchronization, and downstream phenotype analysis.

Memory retention across all modules is based on a **sliding window**, implemented in `BaseCache` using `VolatileCacheWindowRetention.duration()` (default: 30 minutes, configurable via UI settings). Each module may define modality-specific adjustments to volatile retention behavior depending on sampling frequency and computational constraints.

Data emitted by modules are represented using the **Unified Fusion Standard (UFS)** via `UnifiedDataPoint`, ensuring consistent structure across all modalities for downstream processing and analysis.

---

### 3.1 Audio

#### Pipeline

`AudioDataNode` (raw PCM windows) → `AudioAdapter` (builds UFS `UnifiedDataPoint`; writes zlib `audio_<stamp>.bin` to `files/audio/` when not duplicate-suppressed) → `AudioCache` (`BaseCache.put`, sliding TTL) → `DefaultDistributedStorageManager.onUnifiedPointCommitted` (Firestore structured document + optional RTDB mirror + Cloud Storage upload / `file.location` merge).

#### Implementation


| Stage          | File                                                                   | Class                                  |
| -------------- | ---------------------------------------------------------------------- | -------------------------------------- |
| DataNode       | `modules/audio/src/main/kotlin/com/app/modules/audio/AudioDataNode.kt` | `AudioDataNode` extends `BaseDataNode` |
| Adapter        | `modules/audio/src/main/kotlin/com/app/modules/audio/AudioAdapter.kt`  | `AudioAdapter`                         |
| Cache          | `modules/audio/src/main/kotlin/com/app/modules/audio/AudioCache.kt`    | `AudioCache` extends `BaseCache`       |
| Module factory | `modules/audio/src/main/kotlin/com/app/modules/audio/AudioModule.kt`   | `AudioModule`                          |


#### Data stored


| Tier       | What                                                                                                                                                                   | Mechanism / path                                                                                                                                             |
| ---------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **Local**  | Compressed audio payload `audio_<yyyyMMddHHmmssSSS>.bin` (if not fully deduplicated)                                                                                   | `AudioAdapter` → `ModalityLocalFileSink.writeBytes`; stored under `files/audio/` via `AndroidModalityLocalFileSink` and `ModalityStorageDirectoryName.AUDIO` |
| **Memory** | UFS-compliant `UnifiedDataPoint` including `DataDescription` metadata and derived feature `audio.signal.meanDb` within sliding cache window                            | `AudioCache` / `BaseCache.put` using `VolatileCacheWindowRetention`                                                                                          |
| **Cloud**  | Firestore structured document (`UnifiedDataPointPersistenceCodec.toStructuredMap`) and Cloud Storage `audio_*bin` upload referenced via metadata fields in UFS payload | `DefaultDistributedStorageManager.onUnifiedPointCommitted`                                                                                                   |


---

### 3.2 Motion

#### Pipeline

`MotionDataNode` (SensorManager flows + minute tick) → `MotionAdapter` → `MotionCache` → `DefaultDistributedStorageManager` writes `motion/imu/*.json` and/or `motion/steps/*.json` under `files/motion/` → Firestore / optional pause path (`pending_firestore/`).

#### Implementation


| Stage                       | File                                                                                                 | Class                                  |
| --------------------------- | ---------------------------------------------------------------------------------------------------- | -------------------------------------- |
| DataNode                    | `modules/motion/src/main/kotlin/com/app/modules/motion/MotionDataNode.kt`                            | `MotionDataNode`                       |
| Adapter                     | `modules/motion/src/main/kotlin/com/app/modules/motion/MotionAdapter.kt`                             | `MotionAdapter`                        |
| Cache                       | `modules/motion/src/main/kotlin/com/app/modules/motion/MotionCache.kt`                               | `MotionCache`                          |
| Module factory              | `modules/motion/src/main/kotlin/com/app/modules/motion/MotionModule.kt`                              | `MotionModule`                         |
| Structured upload selection | `core/src/main/kotlin/edu/stanford/screenomics/core/storage/MotionStructuredCloudUploadSelectors.kt` | `MotionStructuredCloudUploadSelectors` |


#### Data stored


| Tier       | What                                                                                                                                                                                    | Mechanism / path                                                                                                                            |
| ---------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------- |
| **Local**  | JSON records under `motion/imu/` (accelerometer + gyroscope streams) and `motion/steps/` (step aggregation outputs); optional `motion/pending_firestore/` when cloud upload is deferred | `DefaultDistributedStorageManager` → `ModalityLocalFileSink.writeBytes`; stored under `files/motion/` via `AndroidModalityLocalFileSink`    |
| **Memory** | UFS-compliant `UnifiedDataPoint` including `DataDescription` metadata and derived feature `motion.step.sessionTotal` within sliding cache window                                        | `MotionCache` / `BaseCache.put` using `VolatileCacheWindowRetention`                                                                        |
| **Cloud**  | Firestore structured documents per `UnifiedDataPoint`; optional Realtime Database mirror for structured motion logs                                                                     | `DefaultDistributedStorageManager.onUnifiedPointCommitted`; `AndroidFirestoreStructuredWriteBridge`, `AndroidRealtimeStructuredWriteBridge` |


---

### 3.3 GPS

#### Pipeline

`GpsDataNode` (location supplier from `LocationSnapshotReader`) → `GpsAdapter` (Open-Meteo / optional OpenWeather) → `GpsCache` → `**DefaultDistributedStorageManager`** writes `gps_<stamp>.json` under `files/gps/` → Firestore.

#### Implementation mapping


| Stage                 | File                                                                              | Class                    |
| --------------------- | --------------------------------------------------------------------------------- | ------------------------ |
| DataNode              | `modules/gps/src/main/kotlin/com/app/modules/gps/GpsDataNode.kt`                  | `GpsDataNode`            |
| Adapter               | `modules/gps/src/main/kotlin/com/app/modules/gps/GpsAdapter.kt`                   | `GpsAdapter`             |
| Cache                 | `modules/gps/src/main/kotlin/com/app/modules/gps/GpsCache.kt`                     | `GpsCache`               |
| Module factory        | `modules/gps/src/main/kotlin/com/app/modules/gps/GpsModule.kt`                    | `GpsModule`              |
| Location bridge (app) | `app/src/main/java/edu/stanford/screenomics/collection/LocationSnapshotReader.kt` | `LocationSnapshotReader` |


#### Data stored


| Tier       | What                                                                                                                                                  | Mechanism / path                                                                                                                                                                                          |
| ---------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Local**  | `gps_<yyyyMMddHHmmssSSS>.json` (latitude, longitude, sun/weather fields, timestamp, metadata)                                                         | `DefaultDistributedStorageManager` → `buildGpsLocalJson` → `ModalityLocalFileSink.writeBytes`; stored under `files/gps/` via `AndroidModalityLocalFileSink`                                               |
| **Memory** | UFS-compliant `UnifiedDataPoint` including `DataDescription` metadata and GPS payload keys within sliding cache window                                | `GpsCache` / `BaseCache.put` using `VolatileCacheWindowRetention`                                                                                                                                         |
| **Cloud**  | Firestore structured document (`UnifiedDataPointPersistenceCodec.toStructuredMap`); optional Realtime Database mirror for the same structured payload | `DefaultDistributedStorageManager.onUnifiedPointCommitted`; `AndroidFirestoreStructuredWriteBridge`, `AndroidRealtimeStructuredWriteBridge` (local `gps_*.json` removed after successful Firestore write) |


---

### 3.4 Screenshot

#### Pipeline

`ScreenshotDataNode` (raster supplier from `MediaProjectionScreenCapture`) → `ScreenshotAdapter` (ML Kit OCR + TFLite / lexicon sentiment) → `ScreenshotCache` → PNG `screenshot_<stamp>.png` under `files/screenshot/` → Cloud Storage + Firestore merge of `file.location`.

#### Implementation


| Stage               | File                                                                                    | Class                          |
| ------------------- | --------------------------------------------------------------------------------------- | ------------------------------ |
| DataNode            | `modules/screenshot/src/main/kotlin/com/app/modules/screenshot/ScreenshotDataNode.kt`   | `ScreenshotDataNode`           |
| Adapter             | `modules/screenshot/src/main/kotlin/com/app/modules/screenshot/ScreenshotAdapter.kt`    | `ScreenshotAdapter`            |
| Cache               | `modules/screenshot/src/main/kotlin/com/app/modules/screenshot/ScreenshotCache.kt`      | `ScreenshotCache`              |
| Module factory      | `modules/screenshot/src/main/kotlin/com/app/modules/screenshot/ScreenshotModule.kt`     | `ScreenshotModule`             |
| Media capture (app) | `app/src/main/java/edu/stanford/screenomics/screenshot/MediaProjectionScreenCapture.kt` | `MediaProjectionScreenCapture` |


#### Data stored


| Tier       | What                                                                                                                                                                                                      | Mechanism / path                                                                                                                                                              |
| ---------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Local**  | Downscaled PNG `screenshot_<yyyyMMddHHmmssSSS>.png` (when not duplicate-suppressed; `storageDownscaleFactor` default 0.5)                                                                                 | `ScreenshotAdapter` → `ModalityLocalFileSink.writeBytes`; stored under `files/screenshot/` via `AndroidModalityLocalFileSink`                                                 |
| **Memory** | UFS-compliant `UnifiedDataPoint` including `DataDescription` metadata and `screenshot.sentiment.score` (plus UFS reserved keys) within sliding cache window                                               | `ScreenshotCache` / `BaseCache.put` using `VolatileCacheWindowRetention`                                                                                                      |
| **Cloud**  | Firestore structured document (`UnifiedDataPointPersistenceCodec.toStructuredMap`); optional Realtime Database mirror for the same structured payload; Cloud Storage PNG upload and `file.location` merge | `DefaultDistributedStorageManager.onUnifiedPointCommitted`; `AndroidCloudMediaStorageBridge`; `AndroidFirestoreStructuredWriteBridge`, `AndroidRealtimeStructuredWriteBridge` |


---

## 4. Phenotype Analysis

This section describes how phenotype analysis is implemented as a **cache-driven computation pipeline** over Unified Fusion Standard (UFS) data. It focuses on the **execution flow, window selection, model invocation hooks, and integration with downstream policy logic**.


---

### 4.1 End-to-End Flow (two pathways)

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

### 4.2 Phenotype analyzer (RF): inputs → alignment → forest → **phenotype summary**

### 4.2.1 Inputs

Training reads **four** volatile-cache snapshots: **audio**, **motion** (step anchors), **GPS**, and **screenshot** `UnifiedDataPoint` lists, passed into `EnginePhenotypeStepCountRandomForest.trainFromVolatileCacheSnapshots` (or `trainWithInterventionReceipt`).

### 4.2.2 Alignment (irregular sampling → fixed feature rows)

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

### 4.2.3 Phenotype Summary

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

### 4.3 Edge Cycle: windowing → **edge window summary**

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


### 4.3.1 Windowing

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

**Note:** richer analyzers could read payload keys inside each `UnifiedDataPoint`; the **stock** `HeuristicPhenotypeAnalyzer` uses only **modality** and **cardinality**, not sentiment, sun score, or audio dB.


### 4.3.2 `PhenotypeAnalyzer` (`HeuristicPhenotypeAnalyzer`)

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


### 4.3.3 Fields of `PhenotypeSummary`

| Field | Meaning |
|-------|--------|
| `pointsByModality` | Count of `UnifiedDataPoint` rows per `ModalityKind` in the window. |
| `windowPointCount` | Total points analyzed (`n`). |
| `stressScore` | Heuristic in `[0, 1]` from `n` only in the default edge hook; validated in `init`. |

**Empty state:** `PhenotypeSummary.EMPTY` — empty map, count 0, `stressScore` 0.0.


---

### 4.3.4 Pathway Cadence

| Pathway | Trigger | Typical interval |
|--------|---------|------------------|
| **Phenotype analyzer** → **phenotype summary** (RF report + envelope) | `MainActivity.snapshotCachesAndTrainPhenotype` → `EnginePhenotypeStepCountRandomForest.trainWithInterventionReceipt`; repeats under `launchPhenotypeAutoTicker` | **`PhenotypeWindowPrefs`** (“Phenotype window” in the main UI; minimum **60s** between trains) |
| **Edge window summary** + policy | `ModalityCollectionService` → `edgeComputationEngine.runCycle` | **`EDGE_INTERVAL_MS`** (60s, compile-time) between cycles; **history span** for points = `VolatileCacheWindowRetention` (“Cache window” in the UI; default 30 minutes) |


---

### 4.3.5 Downstream Consumers

| Consumer | Input type | Role |
|----------|------------|------|
| UI / logs | **Phenotype summary** string (`PhenotypeRunUiBundle.phenotypeReport`) | Human-readable RF report (metrics + importance ranks). |
| Intervention receipts | `PhenotypeRunEnvelope` | Condensed outcome of an RF run (e.g. **PHENOTYPE_UPDATED** when top-importance predictor changes). |
| `PhenotypeFeatureEncoder` | **`PhenotypeSummary`** (edge window summary) | Fixed-length `FloatArray` from modality counts + `stressScore` for optional TFLite (`DefaultPhenotypeFeatureEncoder`). |
| `InterventionContext.phenotypeSummary` | **`PhenotypeSummary`** (edge window summary) | Field name overload: passed to `InterventionController.evaluate`; default policy thresholds on `stressScore` |


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


### 4.3.6 File Index

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

## 5. Data Flow (End-to-End)

6. **Edge computation:** `ModalityCollectionService` periodically triggers `DefaultEdgeComputationEngine.runCycle`, operating exclusively on **cache snapshots provided by `LifecycleAwareCacheManager`** (`ModalityCollectionService.kt`, `DefaultEdgeComputationEngine.kt`).
7. **Intervention:** `DefaultInterventionController.evaluate` produces an `InterventionDirective` based on the current `InterventionContext`, which is consumed at the end of each edge computation cycle (`DefaultInterventionController.kt`).


---

### 5.1 Narrative

1. **Raw ingestion:** `*DataNode` produces `RawModalityFrame` instances consumed by `ModulePipeline.rawFrames` (`ModulePipeline.kt`).
2. **Adaptation:** `Adapter.adapt` (`BaseAdapter.adapt` → module-specific `adaptRaw`) produces a `UnifiedDataPoint` containing modality payload fields and UFS reserved keys (`UfsReservedDataKeys`).
3. **Validation:** `UnifiedFusionConsistency.validateOrThrow` ensures required metadata fields and reserved UFS constraints are satisfied (`UnifiedFusionConsistency.kt`, also enforced in `UnifiedDataPoint` initialization).
4. **Caching:** `InMemoryCache.put` (`BaseCache.put`) stores the `UnifiedDataPoint` in a sliding-window `LinkedHashMap`, evicts entries based on `VolatileCacheWindowRetention.duration()`, and invokes a post-commit hook (`onAfterUnifiedPointCommittedOutsideLock`) used for downstream fan-out.
5. **Storage fan-out:** `DefaultDistributedStorageManager.onUnifiedPointCommitted` handles asynchronous persistence to:
  - Firestore (structured documents)
  - Realtime Database (optional structured mirror)
  - Cloud Storage (media artifacts when applicable)
  - Local filesystem writes and cleanup of intermediate artifacts where required
6. **Edge computation (per-cycle):** `ModalityCollectionService` periodically triggers `DefaultEdgeComputationEngine.runCycle`, operating **only** on **cache snapshots** from `CacheManager` / `LifecycleAwareCacheManager` (not raw `DataNode` streams). Each cycle: apply the configured sliding window (`VolatileCacheWindowRetention`, `CachedWindowSelector`) → run the edge **window-summary hook** (`PhenotypeAnalyzer.analyze`, default `HeuristicPhenotypeAnalyzer` → `PhenotypeSummary` for modality counts and density heuristic) → optionally encode that bundle and run `TfliteInterpreterBridge` vector inference → build `InterventionContext` for policy (`ModalityCollectionService.kt`, `DefaultEdgeComputationEngine.kt`).
7. **Phenotype analysis:** In parallel with the edge ticker’s rhythm, **`MainActivity`** (and `PhenotypeWindowPrefs` when auto-tick is active) trains **`EnginePhenotypeStepCountRandomForest`** on snapshots from the audio, motion, GPS, and screenshot caches: **multimodal temporal alignment** → **`RandomForest.fit`** (step-count regression) → a **phenotype summary** (ranked predictor importances and out-of-bag model statistics, plus optional `PhenotypeRunEnvelope` / intervention receipts). This pathway is the manuscript **phenotype analyzer**; it does not replace the per-cycle edge hook in step 6.
8. **Intervention:** `DefaultInterventionController.evaluate` consumes the current `InterventionContext` (edge `PhenotypeSummary`, optional TFLite traces, cache snapshots) and returns an `InterventionDirective` at the end of each **edge** computation cycle (`DefaultInterventionController.kt`). RF run receipts are orthogonal to that per-cycle evaluate path unless wired through shared UI or logging.


---

### 5.2 Wiring reference (runtime construction)

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


---

### 5.3 Service-side activation

```215:223:StanfordScreenomics/app/src/main/java/edu/stanford/screenomics/collection/ModalityCollectionService.kt
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

---

## 6. File / Folder Structure Map

Repository root: `MobileDigitalPhenotyping/`


| Path                                      | Purpose                                                                                                                                                                                                                                                                  |
| ----------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `doc/`                                    | Project documentation (this file).                                                                                                                                                                                                                                       |
| `Data Cache and Storage Location`         | Human-readable **specification** for minimal fields, caches, and storage (source of truth for compliance discussions).                                                                                                                                                   |
| `StanfordScreenomics/`                    | Gradle **multi-module** Android project.                                                                                                                                                                                                                                 |
| `StanfordScreenomics/app/`                | Android application module: `MainActivity`, `ModalityCollectionService`, `ScreenomicsApp`, Firebase bridges under `storage/`, scheduling under `scheduling/`.                                                                                                            |
| `StanfordScreenomics/core/`               | Shared Kotlin **library**: UFS types (`core/unified/`), management (`core/management/`), storage managers (`core/storage/`), scheduling (`core/scheduling/`), edge (`core/edge/`), module template (`core/module/template/`), collection contracts (`core/collection/`). |
| `StanfordScreenomics/modules/audio/`      | Audio modality: `AudioDataNode`, `AudioAdapter`, `AudioCache`, `AudioModule`.                                                                                                                                                                                            |
| `StanfordScreenomics/modules/motion/`     | Motion modality: `MotionDataNode`, `MotionAdapter`, `MotionCache`, `MotionModule`.                                                                                                                                                                                       |
| `StanfordScreenomics/modules/gps/`        | GPS modality: `GpsDataNode`, `GpsAdapter`, `GpsCache`, `GpsModule`.                                                                                                                                                                                                      |
| `StanfordScreenomics/modules/screenshot/` | Screenshot modality: `ScreenshotDataNode`, `ScreenshotAdapter`, `ScreenshotCache`, `ScreenshotModule`, sentiment helpers.                                                                                                                                                |
| `StanfordScreenomics/enginePhenotype/`    | Separate **engine phenotype** / RF demo module (not the same as `DefaultEdgeComputationEngine` cache-window phenotype).                                                                                                                                                  |
| `StanfordScreenomics/engineIntervention/` | **`engineIntervention` Gradle module:** `PhenotypeRunEnvelope`, `PhenotypeRunOutcome`, and `EngineInterventionReceipts` (receipt strings after RF runs, including **PHENOTYPE_UPDATED** when top-importance predictor changes).                                            |


**Key cross-module dependency rule:** `app` depends on `core` and on each `modules:`* artifact; modality modules depend on `core` only.

---

*Update this document when UFS fields, storage layout, or edge/intervention policies change.*
