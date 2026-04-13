package edu.stanford.screenomics.core.edge

/**
 * Post–[PhenotypeAnalyzer.analyze] witness hook (metrics, drift monitors, federated round staging).
 */
object PhenotypeOutputWitnessPlaceholder {

    @Suppress("UNUSED_PARAMETER")
    suspend fun afterPhenotypeComputed(summary: PhenotypeSummary, windowPointCount: Int) {
    }
}
