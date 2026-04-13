package edu.stanford.screenomics.core.management

import edu.stanford.screenomics.core.edge.PhenotypeSummary
import edu.stanford.screenomics.core.edge.TfliteInferenceTrace
import edu.stanford.screenomics.core.unified.UnifiedDataPoint

/**
 * Layer 2 — Data Management: snapshot inputs for policy evaluation inside [InterventionController],
 * optionally enriched by [edu.stanford.screenomics.core.edge.EdgeComputationEngine] (phenotype + TFLite traces).
 */
data class InterventionContext(
    val recentPoints: List<UnifiedDataPoint>,
    val cacheSnapshotsById: Map<String, List<UnifiedDataPoint>>,
    val activeJobIds: Set<String>,
    val phenotypeSummary: PhenotypeSummary = PhenotypeSummary.EMPTY,
    val tfliteInferenceTraces: List<TfliteInferenceTrace> = emptyList(),
    val edgeComputationSessionId: String? = null,
    /** Last edge TFLite output as plain floats (avoids [FloatArray] identity semantics in equals). */
    val lastTfliteOutput: List<Float> = emptyList(),
)
