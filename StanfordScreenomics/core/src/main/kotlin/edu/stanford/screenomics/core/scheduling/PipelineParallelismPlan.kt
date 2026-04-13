package edu.stanford.screenomics.core.scheduling

/**
 * Per-stage bounded parallelism for [edu.stanford.screenomics.core.module.template.ModulePipelineDispatchers].
 */
data class PipelineParallelismPlan(
    val rawIngress: Int,
    val adaptation: Int,
    val cacheCommit: Int,
    val channelDelivery: Int,
) {
    init {
        require(rawIngress >= 1 && adaptation >= 1 && cacheCommit >= 1 && channelDelivery >= 1) {
            "All stage parallelisms must be >= 1"
        }
    }

    companion object {

        fun forModality(level: ResourceStressLevel, weight: ModalityComputeClass): PipelineParallelismPlan = when (level) {
            ResourceStressLevel.NORMAL -> PipelineParallelismPlan(4, 4, 4, 4)
            ResourceStressLevel.STRESSED -> when (weight) {
                ModalityComputeClass.LIGHT -> PipelineParallelismPlan(3, 3, 2, 2)
                ModalityComputeClass.STANDARD -> PipelineParallelismPlan(2, 2, 2, 2)
                ModalityComputeClass.HEAVY -> PipelineParallelismPlan(1, 1, 1, 1)
            }
            ResourceStressLevel.CRITICAL -> when (weight) {
                ModalityComputeClass.LIGHT -> PipelineParallelismPlan(2, 2, 1, 1)
                ModalityComputeClass.STANDARD -> PipelineParallelismPlan(1, 1, 1, 1)
                ModalityComputeClass.HEAVY -> PipelineParallelismPlan(1, 1, 1, 1)
            }
        }
    }
}
