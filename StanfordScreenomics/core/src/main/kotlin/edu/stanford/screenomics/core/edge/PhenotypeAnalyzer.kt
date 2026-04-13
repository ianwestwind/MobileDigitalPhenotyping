package edu.stanford.screenomics.core.edge

import edu.stanford.screenomics.core.unified.ModalityKind
import edu.stanford.screenomics.core.unified.UnifiedDataPoint

/**
 * Derives a [PhenotypeSummary] from fused points in the **edge window** (last N minutes of cache).
 */
fun interface PhenotypeAnalyzer {
    suspend fun analyze(pointsInWindow: List<UnifiedDataPoint>): PhenotypeSummary
}

/**
 * Lightweight, modality-count–based phenotype for edge cycles (replaceable with richer models).
 */
class HeuristicPhenotypeAnalyzer : PhenotypeAnalyzer {

    override suspend fun analyze(pointsInWindow: List<UnifiedDataPoint>): PhenotypeSummary {
        if (pointsInWindow.isEmpty()) return PhenotypeSummary.EMPTY
        val counts = linkedMapOf<ModalityKind, Int>()
        for (p in pointsInWindow) {
            val k = p.metadata.modality
            counts[k] = (counts[k] ?: 0) + 1
        }
        val n = pointsInWindow.size
        val stress = (n / 2000.0).coerceIn(0.0, 1.0)
        return PhenotypeSummary(
            pointsByModality = counts.toMap(),
            windowPointCount = n,
            stressScore = stress,
        )
    }
}
