package edu.stanford.screenomics.core.unified

/**
 * Unified Fusion Standard (UFS): nominal contract for fused carriers used across every modality module.
 *
 * **Schema vs semantic payload:** [schema] is a [DataEntity] / [UnifiedFusionSchemaDescriptor] (structure only).
 * **Instance values** (“entity state” / measurements) live only in [data]. Do not duplicate payload values on [schema].
 */
interface UnifiedFusionStandard {
    val data: Map<String, Any>
    val metadata: DataDescription
    val schema: DataEntity
}
