package edu.stanford.screenomics.core.edge

/**
 * Platform bridge for TensorFlow Lite **vector** models used by [EdgeComputationEngine].
 * JVM/tests should inject [NoOpTfliteInterpreterBridge]; Android supplies an asset-backed implementation.
 */
interface TfliteInterpreterBridge {
    suspend fun runVectorInference(
        modelAssetPath: String?,
        inputFeatures: FloatArray,
        outputLength: Int,
    ): TfliteInferenceResult
}

/**
 * Safe default: no model load; returns zeroed outputs with a skipped trace.
 */
class NoOpTfliteInterpreterBridge : TfliteInterpreterBridge {

    override suspend fun runVectorInference(
        modelAssetPath: String?,
        inputFeatures: FloatArray,
        outputLength: Int,
    ): TfliteInferenceResult {
        val out = FloatArray(outputLength.coerceAtLeast(1)) { 0f }
        val trace = TfliteInferenceTrace(
            modelAssetPath = modelAssetPath ?: "",
            inputLength = inputFeatures.size,
            outputLength = out.size,
            aggregateScore = 0.0,
            fingerprint = "noop",
            skipped = true,
            skipReason = "NoOpTfliteInterpreterBridge",
        )
        return TfliteInferenceResult(output = out, trace = trace)
    }
}
