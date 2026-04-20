package edu.stanford.screenomics.core.management

/**
 * [CacheManager] plus explicit **host lifecycle** commands and coordinated TTL sweeps.
 *
 * Implementations must remain **instance-scoped** (no process-wide cache singleton).
 */
interface LifecycleAwareCacheManager : CacheManager {

    /**
     * Host moved to background: run sliding-window TTL sweep across every registered cache that supports it.
     */
    suspend fun onHostBackgrounded()

    /**
     * Host returned to foreground: extension hook (see [HostForegroundPolicyPlaceholder]).
     */
    suspend fun onHostForegrounded()

    /**
     * Host teardown: clear every registered cache, then drop registry entries.
     */
    suspend fun onHostDestroyed()

    /**
     * Manager-directed sweep (parallel to per-[put] eviction inside [SlidingWindowTtlSweepTarget] caches).
     */
    suspend fun sweepAllRegisteredSlidingWindow(
        spec: SlidingWindowTtlSpec = SlidingWindowTtlSpec(windowDuration = VolatileCacheWindowRetention.duration()),
    )

    suspend fun clearAllRegisteredCaches()

    suspend fun unregisterAll()
}
