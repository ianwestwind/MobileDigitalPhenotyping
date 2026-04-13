package edu.stanford.screenomics.core.management

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * **Optional** coarse periodic eviction in addition to per-[put] sliding-window eviction on each cache.
 *
 * This is a deliberate **placeholder scheduler**: production may replace the fixed delay with
 * WorkManager, alarm alignment, or battery-aware policies without changing [LifecycleAwareCacheManager].
 */
class PeriodicCacheEvictionTicker(
    private val scope: CoroutineScope,
    private val manager: LifecycleAwareCacheManager,
    private val spec: SlidingWindowTtlSpec = SlidingWindowTtlSpec.DEFAULT_THIRTY_MINUTES,
    private val tickIntervalMs: Long = DEFAULT_TICK_MS,
) {
    @Volatile
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                manager.sweepAllRegisteredSlidingWindow(spec)
                delay(tickIntervalMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        private const val DEFAULT_TICK_MS = 60_000L
    }
}
