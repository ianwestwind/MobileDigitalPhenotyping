package edu.stanford.screenomics.core.collection

import edu.stanford.screenomics.core.unified.ModalityKind
import edu.stanford.screenomics.core.unified.UnifiedDataPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/**
 * Layer 1 — Data Collection: modality-local capture surface emitting fused outputs.
 *
 * **Phase 2 — UFS:** [observeUnifiedOutputs] MUST emit [edu.stanford.screenomics.core.unified.UnifiedDataPoint] only.
 */
interface DataNode {
    val nodeId: String

    fun modalityKind(): ModalityKind

    fun observeUnifiedOutputs(): Flow<UnifiedDataPoint>

    suspend fun activate(collectionScope: CoroutineScope)

    suspend fun deactivate()
}
