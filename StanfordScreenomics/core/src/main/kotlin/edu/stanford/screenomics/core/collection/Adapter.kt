package edu.stanford.screenomics.core.collection

import edu.stanford.screenomics.core.unified.ModalityKind
import edu.stanford.screenomics.core.unified.UnifiedDataPoint

/**
 * Layer 1 — Data Collection: transforms modality-specific raw frames into the Unified Fusion Standard.
 *
 * **Phase 2 — UFS:** [adapt] MUST return [edu.stanford.screenomics.core.unified.UnifiedDataPoint] only (no alternate fused carriers).
 */
interface Adapter {
    val adapterId: String

    fun modalityKind(): ModalityKind

    suspend fun adapt(raw: RawModalityFrame): UnifiedDataPoint
}
