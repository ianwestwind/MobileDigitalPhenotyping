package edu.stanford.screenomics.core.management

import edu.stanford.screenomics.core.unified.CorrelationId
import edu.stanford.screenomics.core.unified.UnifiedDataPoint
import java.time.Duration
import java.time.Instant

/**
 * Pure sliding-window eviction logic shared by [edu.stanford.screenomics.core.module.template.BaseCache]
 * and [DefaultCacheManager]-directed sweeps (no storage ownership here).
 */
object SlidingWindowEviction {

    /**
     * @param clockUpperBound When null (typical **post-[put]** path), the window end is the latest
     * **event timestamp** in [store] only, and a **single** entry is never evicted (no relative spread).
     * When non-null (typical **manager sweep**), `max(latestEvent, clockUpperBound)` anchors idle caches
     * so TTL still advances while ingestion is paused.
     */
    fun evictionKeysForSlidingWindow(
        store: Map<CorrelationId, UnifiedDataPoint>,
        windowDuration: Duration,
        clockUpperBound: Instant? = null,
    ): List<CorrelationId> {
        if (store.isEmpty()) return emptyList()
        if (store.size == 1 && clockUpperBound == null) return emptyList()

        val maxEvent = store.values.maxOf { it.metadata.timestamp }
        val windowEnd = if (clockUpperBound != null) {
            maxOf(maxEvent, clockUpperBound)
        } else {
            maxEvent
        }
        val cutoff = windowEnd.minusMillis(windowDuration.toMillis())
        return store.filter { (_, v) -> v.metadata.timestamp.isBefore(cutoff) }.keys.toList()
    }
}
