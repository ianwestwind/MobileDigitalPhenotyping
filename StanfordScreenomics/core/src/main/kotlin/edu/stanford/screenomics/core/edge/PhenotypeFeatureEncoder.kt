package edu.stanford.screenomics.core.edge

import edu.stanford.screenomics.core.unified.ModalityKind

/**
 * Maps [PhenotypeSummary] into a fixed-length feature vector for TFLite input tensors.
 */
fun interface PhenotypeFeatureEncoder {
    fun encode(summary: PhenotypeSummary, targetLength: Int): FloatArray
}

/**
 * Deterministic padding/truncation with per-modality count slots in enum order + stress scalar.
 */
class DefaultPhenotypeFeatureEncoder : PhenotypeFeatureEncoder {

    override fun encode(summary: PhenotypeSummary, targetLength: Int): FloatArray {
        require(targetLength > 0) { "targetLength must be positive" }
        val out = FloatArray(targetLength)
        val order = ModalityKind.entries
        var i = 0
        for (k in order) {
            if (i >= targetLength) return out
            out[i++] = (summary.pointsByModality[k] ?: 0).toFloat()
        }
        if (i < targetLength) {
            out[i] = summary.stressScore.toFloat()
        }
        return out
    }
}
