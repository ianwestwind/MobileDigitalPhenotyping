package edu.stanford.screenomics.core.unified

/**
 * Declares physical/logical typing for a schema attribute path (parallel to [DataEntity.attributes] keys).
 */
data class EntityTypeSpec(
    val typeId: String,
    val encoding: String,
    val version: String,
)
