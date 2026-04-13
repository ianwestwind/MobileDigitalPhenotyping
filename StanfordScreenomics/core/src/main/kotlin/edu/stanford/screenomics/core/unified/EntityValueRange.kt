package edu.stanford.screenomics.core.unified

/**
 * Declares admissible values for a single attribute key in [DataEntity.valueRanges].
 */
sealed interface EntityValueRange

data class NumericEntityValueRange(
    val minInclusive: Double,
    val maxInclusive: Double,
    val stepHint: Double?,
) : EntityValueRange

data class CategoricalEntityValueRange(
    val allowedValues: Set<String>,
) : EntityValueRange

data class OrdinalEntityValueRange(
    val orderedLabels: List<String>,
) : EntityValueRange

data class UnboundedEntityValueRange(
    val rationale: String,
) : EntityValueRange
