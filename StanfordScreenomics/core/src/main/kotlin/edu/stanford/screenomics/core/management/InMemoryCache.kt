package edu.stanford.screenomics.core.management

import edu.stanford.screenomics.core.unified.CorrelationId
import edu.stanford.screenomics.core.unified.ModalityKind
import edu.stanford.screenomics.core.unified.UnifiedDataPoint

/**
 * Layer 2 — Data Management: modality-scoped volatile retention of fused points.
 */
interface InMemoryCache {
    val cacheId: String

    fun modalityKind(): ModalityKind

    suspend fun put(point: UnifiedDataPoint)

    suspend fun get(correlationId: CorrelationId): UnifiedDataPoint?

    suspend fun remove(correlationId: CorrelationId)

    suspend fun clear()

    suspend fun snapshot(): List<UnifiedDataPoint>
}
