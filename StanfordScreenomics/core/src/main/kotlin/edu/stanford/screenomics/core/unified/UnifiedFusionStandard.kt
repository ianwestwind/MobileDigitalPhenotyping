package edu.stanford.screenomics.core.unified

/**
 * Unified Fusion Standard (UFS): nominal contract for fused carriers used across every modality module.
 *
 * **Instance values** live in [data]; traceability in [metadata].
 */
interface UnifiedFusionStandard {
    val data: Map<String, Any>
    val metadata: DataDescription
}
