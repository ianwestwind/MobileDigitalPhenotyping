package edu.stanford.screenomics.core.management

import edu.stanford.screenomics.core.unified.UnifiedDataPoint

/**
 * Layer 2 — Data Management: immutable job descriptor for edge-side computation over fused inputs.
 */
data class EdgeComputationJob(
    val jobId: String,
    val inputs: List<UnifiedDataPoint>,
    val policyHint: String,
)
