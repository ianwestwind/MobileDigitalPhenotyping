package edu.stanford.screenomics.core.scheduling

import edu.stanford.screenomics.core.unified.ModalityKind

/**
 * Reserved hook for exporting scheduler decisions (stress tier, parallelism plan) to metrics backends.
 */
object TaskSchedulerTelemetryPlaceholder {

    @Suppress("UNUSED_PARAMETER")
    suspend fun onParallelismRebound(
        modality: ModalityKind,
        plan: PipelineParallelismPlan,
        level: ResourceStressLevel,
    ) {
    }
}
