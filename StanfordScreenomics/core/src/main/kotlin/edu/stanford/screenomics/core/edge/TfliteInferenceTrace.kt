package edu.stanford.screenomics.core.edge

/**
 * Audit-friendly record of a TensorFlow Lite run on-device (no raw tensor dump by default).
 */
data class TfliteInferenceTrace(
    val modelAssetPath: String,
    val inputLength: Int,
    val outputLength: Int,
    /** Scalar summary derived from outputs (e.g. max absolute activation), for intervention policy hooks. */
    val aggregateScore: Double,
    /** Short stable digest for logs / deduplication. */
    val fingerprint: String,
    val skipped: Boolean,
    val skipReason: String?,
)
