package edu.stanford.screenomics.core.edge

import edu.stanford.screenomics.core.unified.UnifiedDataPoint
import java.time.Duration
import java.time.Instant

/**
 * Selects **cached** [UnifiedDataPoint] rows whose [edu.stanford.screenomics.core.unified.DataDescription.timestamp]
 * falls in a **sliding** window ending at the latest observed event time (or a clock fallback when empty).
 */
object CachedWindowSelector {

    fun windowEndInclusive(points: Iterable<UnifiedDataPoint>, clockFallback: Instant): Instant =
        points.maxOfOrNull { it.metadata.timestamp } ?: clockFallback

    fun cutoffInstant(windowEnd: Instant, windowDuration: Duration): Instant =
        windowEnd.minusMillis(windowDuration.toMillis())

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
}
