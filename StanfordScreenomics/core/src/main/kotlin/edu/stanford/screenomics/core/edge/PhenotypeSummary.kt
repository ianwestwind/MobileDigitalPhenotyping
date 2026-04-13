package edu.stanford.screenomics.core.edge

import edu.stanford.screenomics.core.unified.ModalityKind

/**
 * Compact **phenotype** view derived from fused points in the edge window (counts + heuristic stress).
 */
data class PhenotypeSummary(
    val pointsByModality: Map<ModalityKind, Int>,
    val windowPointCount: Int,
    /** Heuristic in \[0, 1\] for downstream policy (not clinical ground truth). */
    val stressScore: Double,
) {
    init {
        require(stressScore in 0.0..1.0) { "stressScore must be 0..1" }
    }

    companion object {
        val EMPTY: PhenotypeSummary = PhenotypeSummary(emptyMap(), 0, 0.0)
    }
}
