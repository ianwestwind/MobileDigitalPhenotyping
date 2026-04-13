package edu.stanford.screenomics.core.edge

/**
 * Float output buffer + [TfliteInferenceTrace] metadata from one edge inference pass.
 */
data class TfliteInferenceResult(
    val output: FloatArray,
    val trace: TfliteInferenceTrace,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TfliteInferenceResult) return false
        return output.contentEquals(other.output) && trace == other.trace
    }

    override fun hashCode(): Int = 31 * output.contentHashCode() + trace.hashCode()
}
