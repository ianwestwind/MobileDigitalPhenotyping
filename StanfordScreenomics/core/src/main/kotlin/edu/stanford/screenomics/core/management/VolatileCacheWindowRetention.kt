package edu.stanford.screenomics.core.management

import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * Global sliding-window length for all modality [edu.stanford.screenomics.core.module.template.BaseCache] stores.
 * Updated from the app UI in real time (see app [VolatileCacheWindowPrefs]).
 */
object VolatileCacheWindowRetention {

    private val nanos = AtomicLong(Duration.ofMinutes(30).toNanos())

    /** Minimum 1 minute, maximum 48 hours (UI uses 1–60 minutes or hours). */
    private const val MIN_NANOS: Long = 60_000_000_000L
    private const val MAX_NANOS: Long = 48L * 60L * 60L * 1_000_000_000L

    fun duration(): Duration = Duration.ofNanos(nanos.get())

    fun setDuration(d: Duration) {
        val n = d.toNanos().coerceIn(MIN_NANOS, MAX_NANOS)
        nanos.set(n)
    }
}
