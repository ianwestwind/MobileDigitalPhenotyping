package edu.stanford.screenomics.core.edge

/**
 * Reserved extension point for learned phenotype encoders (e.g. contrastive embeddings) before TFLite.
 */
object EdgePhenotypeEncoderPlaceholder {

    @Suppress("UNUSED_PARAMETER")
    suspend fun beforeTfliteEncode(summary: PhenotypeSummary, rawFeatures: FloatArray) {
    }
}
