package edu.stanford.screenomics.core.management

/**
 * Optional capability of an [InMemoryCache] implementation: manager-directed **sliding-window TTL** sweep
 * without bypassing modality-local locking.
 */
interface SlidingWindowTtlSweepTarget {
    suspend fun sweepSlidingWindowTtl(spec: SlidingWindowTtlSpec = SlidingWindowTtlSpec.DEFAULT_THIRTY_MINUTES)
}
