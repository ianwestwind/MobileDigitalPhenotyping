package edu.stanford.screenomics.core.management

import java.time.Clock
import java.time.Duration

/**
 * TTL policy for **sliding-window** retention: entries older than
 * `windowEnd - windowDuration` are evicted, where `windowEnd` is derived from in-store event times and,
 * for coordinated sweeps, may incorporate [clock] so idle buffers can still age out.
 *
 * Default matches product requirement: **30 minutes**.
 */
data class SlidingWindowTtlSpec(
    val windowDuration: Duration = DEFAULT_WINDOW,
    val clock: Clock = Clock.systemUTC(),
) {
    init {
        require(!windowDuration.isNegative && !windowDuration.isZero) {
            "windowDuration must be positive (got $windowDuration)"
        }
    }

    companion object {
        private val DEFAULT_WINDOW: Duration = Duration.ofMinutes(30)

        val DEFAULT_THIRTY_MINUTES: SlidingWindowTtlSpec = SlidingWindowTtlSpec(DEFAULT_WINDOW)
    }
}
