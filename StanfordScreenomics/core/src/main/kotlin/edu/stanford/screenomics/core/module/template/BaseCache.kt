package edu.stanford.screenomics.core.module.template

import edu.stanford.screenomics.core.debug.PipelineDiagnosticsRegistry
import edu.stanford.screenomics.core.debug.PipelineLogTags
import edu.stanford.screenomics.core.debug.toModuleLogTag
import edu.stanford.screenomics.core.management.InMemoryCache
import edu.stanford.screenomics.core.management.SlidingWindowEviction
import edu.stanford.screenomics.core.management.SlidingWindowTtlSpec
import edu.stanford.screenomics.core.management.SlidingWindowTtlSweepTarget
import edu.stanford.screenomics.core.management.VolatileCacheWindowRetention
import edu.stanford.screenomics.core.unified.CorrelationId
import edu.stanford.screenomics.core.unified.ModalityKind
import edu.stanford.screenomics.core.unified.UnifiedDataPoint
import edu.stanford.screenomics.core.unified.requireCorrelationId
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reusable volatile cache skeleton: generic in-memory retention with [Mutex] (suspend, non-blocking).
 *
 * Sliding-window TTL (see [VolatileCacheWindowRetention]) runs inside the same lock as [put] after
 * [onAfterPutExtension]. [DefaultCacheManager][edu.stanford.screenomics.core.management.DefaultCacheManager]
 * may additionally invoke [sweepSlidingWindowTtl] for coordinated sweeps.
 *
 * Optional **minimum wall-clock spacing** between volatile [store] inserts ([setVolatileInsertMinimumWallInterval]):
 * when set, high-frequency producers still invoke [onAfterUnifiedPointCommittedOutsideLock] every time, but the
 * in-memory sliding window accepts a new row at most once per interval (per cache instance).
 */
open class BaseCache(
    final override val cacheId: String,
    private val modality: ModalityKind,
    private val onAfterUnifiedPointCommittedOutsideLock: suspend (UnifiedDataPoint) -> Unit = {},
) : InMemoryCache, SlidingWindowTtlSweepTarget {

    private val mutex = Mutex()
    private val store = LinkedHashMap<CorrelationId, UnifiedDataPoint>()

    private val volatileThrottleLock = Any()
    private var volatileMinWallNanos: Long = 0L
    private var lastVolatileAcceptedWall: Instant? = null

    final override fun modalityKind(): ModalityKind = modality

    /**
     * When non-null and non-zero, at most one volatile [store] insert is allowed per this wall-clock [Duration],
     * using [Instant.now] between accepted inserts. [onAfterUnifiedPointCommittedOutsideLock] is still invoked
     * for every [put]. Passing null or a non-positive duration disables spacing (default).
     */
    fun setVolatileInsertMinimumWallInterval(minimumInterval: Duration?) {
        synchronized(volatileThrottleLock) {
            volatileMinWallNanos = when {
                minimumInterval == null || minimumInterval.isZero || minimumInterval.isNegative -> 0L
                else -> minimumInterval.toNanos()
            }
            lastVolatileAcceptedWall = null
        }
    }

    fun getVolatileInsertMinimumWallInterval(): Duration? {
        synchronized(volatileThrottleLock) {
            if (volatileMinWallNanos <= 0L) return null
            return Duration.ofNanos(volatileMinWallNanos)
        }
    }

    final override suspend fun put(point: UnifiedDataPoint) {
        val retainVolatile = shouldRetainPointInVolatileStore(point)
        var insertedVolatile = false
        val storeSizeAfterInsert = mutex.withLock {
            onBeforePut(point)
            if (retainVolatile) {
                val acceptVolatile = synchronized(volatileThrottleLock) {
                    val minNanos = volatileMinWallNanos
                    if (minNanos <= 0L) {
                        true
                    } else {
                        val now = Instant.now()
                        val last = lastVolatileAcceptedWall
                        val ok = last == null || Duration.between(last, now).toNanos() >= minNanos
                        if (ok) {
                            lastVolatileAcceptedWall = now
                        }
                        ok
                    }
                }
                if (acceptVolatile) {
                    store[point.requireCorrelationId()] = point
                    onAfterPutExtension(point, store)
                    applySlidingWindowEvictionUnderLock(
                        store = store,
                        windowDuration = VolatileCacheWindowRetention.duration(),
                        clockUpperBound = null,
                    )
                    insertedVolatile = true
                }
            }
            store.size
        }
        when {
            insertedVolatile -> {
                PipelineDiagnosticsRegistry.emit(
                    logTag = PipelineLogTags.CACHE,
                    module = modality,
                    stage = "cache_insert",
                    dataType = "${modality.name.lowercase()}_unified_point",
                    detail = "[CACHE][${modality.toModuleLogTag()}] inserted correlationId=${point.requireCorrelationId().value} " +
                        "cacheId=$cacheId timestamp=${point.metadata.timestamp} storeSize=$storeSizeAfterInsert",
                )
            }
            retainVolatile -> {
                PipelineDiagnosticsRegistry.emit(
                    logTag = PipelineLogTags.CACHE,
                    module = modality,
                    stage = "cache_volatile_throttled",
                    dataType = "${modality.name.lowercase()}_unified_point",
                    detail = "[CACHE][${modality.toModuleLogTag()}] volatile store throttled (min wall interval) " +
                        "correlationId=${point.requireCorrelationId().value} cacheId=$cacheId timestamp=${point.metadata.timestamp}",
                )
            }
            else -> {
                PipelineDiagnosticsRegistry.emit(
                    logTag = PipelineLogTags.CACHE,
                    module = modality,
                    stage = "cache_volatile_bypass",
                    dataType = "${modality.name.lowercase()}_unified_point",
                    detail = "[CACHE][${modality.toModuleLogTag()}] volatile store skipped (tier-2/local only) " +
                        "correlationId=${point.requireCorrelationId().value} cacheId=$cacheId timestamp=${point.metadata.timestamp}",
                )
            }
        }
        onAfterUnifiedPointCommittedOutsideLock(point)
    }

    /**
     * When false, the point is not kept in the in-memory [store] (no sliding-window row for it), but
     * [onAfterUnifiedPointCommittedOutsideLock] still runs so local/cloud pipelines can observe it.
     * Motion uses this for IMU streams while the 30‑minute volatile tier holds step aggregates only.
     */
    protected open fun shouldRetainPointInVolatileStore(point: UnifiedDataPoint): Boolean = true

    final override suspend fun get(correlationId: CorrelationId): UnifiedDataPoint? =
        mutex.withLock { store[correlationId] }

    final override suspend fun remove(correlationId: CorrelationId) {
        mutex.withLock {
            onBeforeRemove(correlationId)
            store.remove(correlationId)
            onAfterRemove(correlationId)
        }
    }

    final override suspend fun clear() {
        mutex.withLock {
            onBeforeClear()
            store.clear()
            onAfterClear()
        }
    }

    final override suspend fun snapshot(): List<UnifiedDataPoint> =
        mutex.withLock { store.values.toList() }

    final override suspend fun sweepSlidingWindowTtl(spec: SlidingWindowTtlSpec) {
        mutex.withLock {
            applySlidingWindowEvictionUnderLock(
                store = store,
                windowDuration = spec.windowDuration,
                clockUpperBound = spec.clock.instant(),
            )
        }
    }

    protected open suspend fun onBeforePut(point: UnifiedDataPoint) {}
    /**
     * Invoked while the cache [Mutex] is held, after the [point] is inserted into [store] and before
     * sliding-window TTL eviction ([VolatileCacheWindowRetention]).
     */
    protected open suspend fun onAfterPutExtension(
        point: UnifiedDataPoint,
        store: MutableMap<CorrelationId, UnifiedDataPoint>,
    ) {
    }

    private suspend fun applySlidingWindowEvictionUnderLock(
        store: MutableMap<CorrelationId, UnifiedDataPoint>,
        windowDuration: Duration,
        clockUpperBound: Instant?,
    ) {
        val keys = SlidingWindowEviction.evictionKeysForSlidingWindow(
            store = store,
            windowDuration = windowDuration,
            clockUpperBound = clockUpperBound,
        )
        for (k in keys) {
            onBeforeRemove(k)
            store.remove(k)
            onAfterRemove(k)
            PipelineDiagnosticsRegistry.emit(
                logTag = PipelineLogTags.CACHE,
                module = modality,
                stage = "cache_eviction_ttl",
                dataType = "${modality.name.lowercase()}_unified_point",
                detail = "[CACHE] Evicted correlationId=${k.value} olderThanWindow=${windowDuration.toMinutes()}min " +
                    "cacheId=$cacheId clockUpperBound=$clockUpperBound",
            )
        }
    }
    protected open suspend fun onBeforeRemove(correlationId: CorrelationId) {}
    protected open suspend fun onAfterRemove(correlationId: CorrelationId) {}
    protected open suspend fun onBeforeClear() {}
    protected open suspend fun onAfterClear() {}
}
