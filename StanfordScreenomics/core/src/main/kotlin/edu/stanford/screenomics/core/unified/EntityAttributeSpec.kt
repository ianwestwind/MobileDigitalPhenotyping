package edu.stanford.screenomics.core.unified

/**
 * Declares one logical attribute in a [DataEntity] schema: requirement, semantics, and structural tag for validation.
 */
data class EntityAttributeSpec(
    val required: Boolean,
    val semanticDescription: String,
    val structureTag: String,
)
