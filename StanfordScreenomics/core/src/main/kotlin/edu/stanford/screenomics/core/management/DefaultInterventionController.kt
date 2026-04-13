package edu.stanford.screenomics.core.management

import edu.stanford.screenomics.core.debug.PipelineDiagnosticsRegistry
import edu.stanford.screenomics.core.debug.PipelineLogTags
import edu.stanford.screenomics.core.unified.ModalityKind

/**
 * Baseline [InterventionController]: reacts to [InterventionContext.phenotypeSummary] and TFLite aggregate scores.
 * Replace with study-specific policy as requirements harden.
 */
class DefaultInterventionController : InterventionController {

    override suspend fun evaluate(context: InterventionContext): InterventionDirective {
        PipelineDiagnosticsRegistry.emit(
            logTag = PipelineLogTags.INTERVENTION,
            module = null,
            stage = "intervention_evaluate_begin",
            dataType = "phenotype_context",
            detail = "[INTERVENTION] phenotype stressScore=${context.phenotypeSummary.stressScore} " +
                "windowPoints=${context.recentPoints.size} tfliteTraces=${context.tfliteInferenceTraces.size}",
        )
        if (context.recentPoints.isEmpty()) {
            PipelineDiagnosticsRegistry.emit(
                logTag = PipelineLogTags.INTERVENTION,
                module = null,
                stage = "intervention_decision",
                dataType = "no_intervention",
                detail = "[INTERVENTION] decision=NoInterventionDirective reason=empty_recent_points",
            )
            return NoInterventionDirective
        }
        val tfliteHot = context.tfliteInferenceTraces.any { !it.skipped && it.aggregateScore > 0.85 }
        if (tfliteHot) {
            val d = SamplingPolicyDirective(
                targetModalityKindName = ModalityKind.SCREENSHOT.name,
                policyToken = "edge_tflite_high_activation_throttle",
            )
            PipelineDiagnosticsRegistry.emit(
                logTag = PipelineLogTags.INTERVENTION,
                module = null,
                stage = "intervention_decision",
                dataType = "sampling_policy",
                detail = "[INTERVENTION] Triggering intervention: $d",
            )
            return d
        }
        if (context.phenotypeSummary.stressScore >= 0.85) {
            val d = SamplingPolicyDirective(
                targetModalityKindName = ModalityKind.AUDIO.name,
                policyToken = "edge_phenotype_high_density_throttle",
            )
            PipelineDiagnosticsRegistry.emit(
                logTag = PipelineLogTags.INTERVENTION,
                module = null,
                stage = "intervention_decision",
                dataType = "sampling_policy",
                detail = "[INTERVENTION] Triggering intervention: $d",
            )
            return d
        }
        PipelineDiagnosticsRegistry.emit(
            logTag = PipelineLogTags.INTERVENTION,
            module = null,
            stage = "intervention_decision",
            dataType = "no_intervention",
            detail = "[INTERVENTION] decision=NoInterventionDirective reason=thresholds_not_met",
        )
        return NoInterventionDirective
    }
}
