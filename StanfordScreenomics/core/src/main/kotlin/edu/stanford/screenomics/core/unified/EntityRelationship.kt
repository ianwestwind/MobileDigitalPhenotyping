package edu.stanford.screenomics.core.unified

/**
 * Declares a directed semantic relationship between two attribute keys in [DataEntity.relationships].
 */
data class EntityRelationship(
    val subjectAttributeKey: String,
    val predicateToken: String,
    val objectAttributeKey: String,
    val bidirectional: Boolean,
    val cardinalityHint: String?,
)
