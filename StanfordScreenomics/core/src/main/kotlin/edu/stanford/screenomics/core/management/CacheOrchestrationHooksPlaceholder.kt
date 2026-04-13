package edu.stanford.screenomics.core.management

/**
 * Extension seam for tracing, metrics export, or adaptive eviction tuning after coordinated operations.
 * Intentionally empty so orchestration stays observable in one place without hiding missing telemetry.
 */
object CacheOrchestrationHooksPlaceholder {

    @Suppress("UNUSED_PARAMETER")
    suspend fun afterSlidingWindowSweep(sweepTargetCount: Int, spec: SlidingWindowTtlSpec) {
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun afterClearAllRegisteredCaches(cacheCount: Int) {
    }
}
