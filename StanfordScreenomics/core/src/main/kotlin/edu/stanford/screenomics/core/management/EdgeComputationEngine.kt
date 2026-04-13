package edu.stanford.screenomics.core.management

/**
 * Layer 2 — Data Management: executes edge-side analytic transforms over fused [UnifiedDataPoint] batches.
 */
interface EdgeComputationEngine {
    suspend fun submit(job: EdgeComputationJob): EdgeComputationResult
}
