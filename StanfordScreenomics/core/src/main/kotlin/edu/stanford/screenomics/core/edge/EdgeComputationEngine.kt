package edu.stanford.screenomics.core.edge

import edu.stanford.screenomics.core.management.CacheManager
import edu.stanford.screenomics.core.management.InterventionController
import edu.stanford.screenomics.core.management.InterventionDirective

/**
 * On-device **edge** cycle: pull the last window of **cached** fused points, run phenotype + optional TFLite,
 * then forward enriched context into [InterventionController].
 *
 * Audit notes: [EdgeComputationAuditContractPlaceholder].
 */
interface EdgeComputationEngine {

    /**
     * One end-to-end computation → intervention evaluation pass.
     */
    suspend fun runCycle(
        cacheManager: CacheManager,
        interventionController: InterventionController,
        activeJobIds: Set<String>,
    ): InterventionDirective
}
