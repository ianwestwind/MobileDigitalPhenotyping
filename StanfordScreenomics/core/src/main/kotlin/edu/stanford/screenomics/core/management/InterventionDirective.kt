package edu.stanford.screenomics.core.management

/**
 * Layer 2 — Data Management: controller output describing permitted runtime adjustments.
 */
sealed interface InterventionDirective

data object NoInterventionDirective : InterventionDirective

data class SamplingPolicyDirective(
    val targetModalityKindName: String,
    val policyToken: String,
) : InterventionDirective

data class CacheEvictionDirective(
    val cacheId: String,
    val reason: String,
) : InterventionDirective
