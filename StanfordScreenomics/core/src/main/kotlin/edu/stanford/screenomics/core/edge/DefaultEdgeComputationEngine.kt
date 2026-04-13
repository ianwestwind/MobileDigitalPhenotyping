package edu.stanford.screenomics.core.edge

import edu.stanford.screenomics.core.debug.PipelineDiagnosticsRegistry
import edu.stanford.screenomics.core.debug.PipelineLogTags
import edu.stanford.screenomics.core.management.CacheManager
import edu.stanford.screenomics.core.management.InterventionContext
import edu.stanford.screenomics.core.management.InterventionController
import edu.stanford.screenomics.core.management.InterventionDirective
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * Default [EdgeComputationEngine]: **30-minute** sliding cache window, [PhenotypeAnalyzer], optional
 * [TfliteInterpreterBridge], then [InterventionController.evaluate].
 *
 * **Data source:** only [CacheManager] snapshots of registered caches — no `DataNode` raw streams or pipeline ingress.
 *
 * Audit: [EdgeComputationAuditContractPlaceholder].
 */
class DefaultEdgeComputationEngine(
    private val phenotypeAnalyzer: PhenotypeAnalyzer = HeuristicPhenotypeAnalyzer(),
    private val featureEncoder: PhenotypeFeatureEncoder = DefaultPhenotypeFeatureEncoder(),
    private val tfliteInterpreterBridge: TfliteInterpreterBridge = NoOpTfliteInterpreterBridge(),
    private val windowDuration: Duration = Duration.ofMinutes(30),
    private val clock: Clock = Clock.systemUTC(),
    private val modelAssetPath: String? = null,
    private val tfliteInputLength: Int = 16,
    private val tfliteOutputLength: Int = 16,
) : EdgeComputationEngine {

    override suspend fun runCycle(
        cacheManager: CacheManager,
        interventionController: InterventionController,
        activeJobIds: Set<String>,
    ): InterventionDirective {
        EdgeComputationCachePathVerifierPlaceholder.assertCacheBackedSourceOnly(cacheManager)
        val snapshots = cacheManager.snapshotByCacheId()
        val flat = snapshots.values.flatten()
        val windowEnd = CachedWindowSelector.windowEndInclusive(flat, clock.instant())
        val windowedById = snapshots.mapValues { (_, pts) ->
            CachedWindowSelector.filterWindow(pts, windowEnd, windowDuration)
        }
        val recentPoints = windowedById.values.flatten().sortedBy { it.metadata.timestamp }

        PipelineDiagnosticsRegistry.emit(
            logTag = PipelineLogTags.EDGE_ENGINE,
            module = null,
            stage = "edge_cache_snapshot",
            dataType = "multi_modality_window",
            detail = "[EDGE_ENGINE] cacheRetrieval windowPointCount=${recentPoints.size} " +
                "windowDurationMin=${windowDuration.toMinutes()} cacheIds=${windowedById.keys.joinToString()}",
        )

        val phenotype = phenotypeAnalyzer.analyze(recentPoints)
        PhenotypeOutputWitnessPlaceholder.afterPhenotypeComputed(
            summary = phenotype,
            windowPointCount = recentPoints.size,
        )
        val feats = featureEncoder.encode(phenotype, tfliteInputLength)
        EdgePhenotypeEncoderPlaceholder.beforeTfliteEncode(
            summary = phenotype,
            rawFeatures = feats,
        )

        PipelineDiagnosticsRegistry.emit(
            logTag = PipelineLogTags.EDGE_ENGINE,
            module = null,
            stage = "edge_feature_vector",
            dataType = "tflite_input_vector",
            detail = "[EDGE_ENGINE] phenotype stressScore=${phenotype.stressScore} pointsByModality=${phenotype.pointsByModality} " +
                "featureDim=${feats.size} featurePreview=${feats.take(8).joinToString { "%.4f".format(it) }}",
        )

        val inference = tfliteInterpreterBridge.runVectorInference(
            modelAssetPath = modelAssetPath,
            inputFeatures = feats,
            outputLength = tfliteOutputLength,
        )

        PipelineDiagnosticsRegistry.emit(
            logTag = PipelineLogTags.EDGE_ENGINE,
            module = null,
            stage = "edge_model_inference",
            dataType = "tflite_output",
            detail = "[EDGE_ENGINE] tflite skipped=${inference.trace.skipped} aggregateScore=${inference.trace.aggregateScore} " +
                "fingerprint=${inference.trace.fingerprint} outputPreview=${inference.output.take(8).joinToString { "%.4f".format(it) }}",
        )

        val context = InterventionContext(
            recentPoints = recentPoints,
            cacheSnapshotsById = windowedById,
            activeJobIds = activeJobIds,
            phenotypeSummary = phenotype,
            tfliteInferenceTraces = listOf(inference.trace),
            edgeComputationSessionId = UUID.randomUUID().toString(),
            lastTfliteOutput = inference.output.toList(),
        )
        val directive = interventionController.evaluate(context)
        PipelineDiagnosticsRegistry.emit(
            logTag = PipelineLogTags.EDGE_ENGINE,
            module = null,
            stage = "edge_intervention_directive",
            dataType = "intervention_directive",
            detail = "[EDGE_ENGINE] directive=${directive::class.simpleName} recentPoints=${recentPoints.size}",
        )
        EdgeComputationTelemetryPlaceholder.afterCycle(
            recentPointCount = recentPoints.size,
            directive = directive,
        )
        return directive
    }
}
