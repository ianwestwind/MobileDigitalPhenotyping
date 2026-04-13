package edu.stanford.screenomics.core.management

import edu.stanford.screenomics.core.unified.UnifiedDataPoint

/**
 * Layer 2 — Data Management: discriminated outcome for edge computation.
 */
sealed interface EdgeComputationResult

data class EdgeComputationSuccess(
    val outputs: List<UnifiedDataPoint>,
) : EdgeComputationResult

data class EdgeComputationFailure(
    val reason: String,
) : EdgeComputationResult
