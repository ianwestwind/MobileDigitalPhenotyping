package com.app.enginephenotype

import com.app.engineintervention.EngineInterventionReceipts
import com.app.engineintervention.PhenotypeRunEnvelope
import com.app.engineintervention.PhenotypeRunOutcome
import edu.stanford.screenomics.core.unified.UnifiedDataPoint
import java.time.Instant
import java.util.Locale
import kotlin.math.abs
import smile.data.DataFrame
import smile.data.formula.Formula
import smile.regression.RandomForest

/**
 * Trains a **regression** random forest: **session step total** (from motion cache) from
 * **audio mean dB** (or RMS), **screenshot sentiment**, and **GPS sun score**, after
 * [MultimodalTemporalAlignment] handles irregular sampling.
 */
object EnginePhenotypeStepCountRandomForest {

    private const val MIN_TRAINING_ROWS = 12

    data class PhenotypeRunUiBundle(
        val phenotypeReport: String,
        val interventionReceipt: String,
    )

    data class StepTrainingSummary(
        val rowsUsed: Int,
        val treeCount: Int,
        val outOfBagRmse: Double,
        val outOfBagR2: Double,
    )

    class TrainedStepModel internal constructor(
        internal val forest: RandomForest,
        /** Medians of aligned training features (for prediction-time gap fill). */
        val featureMedians: Triple<Double, Double, Double>,
        val summary: StepTrainingSummary,
    ) {
        fun predictSessionTotalSteps(audioMeanDb: Double, sentimentScore: Double, sunScore0To10: Double): Double {
            val row = DataFrame.of(
                arrayOf(doubleArrayOf(audioMeanDb, sentimentScore, sunScore0To10)),
                "audio_db",
                "sentiment",
                "sun",
            )
            return forest.predict(row.get(0))
        }

        /**
         * Uses the same LOCF / forward / median rules as training, evaluated at [referenceTime]
         * against the current volatile-cache snapshots.
         */
        fun predictSessionTotalAtReferenceTime(
            referenceTime: Instant,
            audioPoints: List<UnifiedDataPoint>,
            screenshotPoints: List<UnifiedDataPoint>,
            gpsPoints: List<UnifiedDataPoint>,
        ): Double {
            val (a, s, u) = alignedFeaturesAtInstant(
                referenceTime,
                audioPoints,
                screenshotPoints,
                gpsPoints,
                featureMedians,
            )
            return predictSessionTotalSteps(a, s, u)
        }
    }

    sealed class TrainResult {
        data class Success(val model: TrainedStepModel, val summary: StepTrainingSummary) : TrainResult()
        data class InsufficientData(val message: String) : TrainResult()
    }

    /**
     * @param audioPoints [InMemoryCache.snapshot] for audio modality
     * @param motionPoints snapshot for motion (anchors = points with `motion.step.sessionTotal`)
     * @param gpsPoints snapshot for GPS
     * @param screenshotPoints snapshot for screenshot
     */
    fun trainFromVolatileCacheSnapshots(
        audioPoints: List<UnifiedDataPoint>,
        motionPoints: List<UnifiedDataPoint>,
        gpsPoints: List<UnifiedDataPoint>,
        screenshotPoints: List<UnifiedDataPoint>,
        treeCount: Int = 64,
        maxTrainingRows: Int = 1500,
    ): TrainResult {
        val aligned = buildAlignedStepTrainingRows(
            audioPoints = audioPoints,
            screenshotPoints = screenshotPoints,
            gpsPoints = gpsPoints,
            motionPoints = motionPoints,
            maxTrainingRows = maxTrainingRows,
        )
        if (aligned.size < MIN_TRAINING_ROWS) {
            return TrainResult.InsufficientData(
                "Need at least $MIN_TRAINING_ROWS motion step rows with alignable audio/sentiment/sun " +
                    "(have ${aligned.size}). Collect more data or wait for minute-window step summaries.",
            )
        }
        val matrix = Array(aligned.size) { i ->
            val r = aligned[i]
            doubleArrayOf(r.audioMeanDb, r.sentiment, r.sunScore, r.sessionTotalSteps)
        }
        val frame = DataFrame.of(matrix, "audio_db", "sentiment", "sun", "steps")
        val options = RandomForest.Options(treeCount, 0, 12, 512, 3, 1.0, null, null)
        val forest = RandomForest.fit(Formula.lhs("steps"), frame, options)
        val metrics = forest.metrics()
        val medians = featureMedianTriple(aligned)
        val summary = StepTrainingSummary(
            rowsUsed = aligned.size,
            treeCount = treeCount,
            outOfBagRmse = metrics.rmse(),
            outOfBagR2 = metrics.r2(),
        )
        return TrainResult.Success(
            TrainedStepModel(forest = forest, featureMedians = medians, summary = summary),
            summary,
        )
    }

    /**
     * Trains the RF, builds the on-screen phenotype report, and forwards a compact run summary to
     * [EngineInterventionReceipts] (including **PHENOTYPE_UPDATED** when the top-importance predictor changes).
     */
    fun trainWithInterventionReceipt(
        audioPoints: List<UnifiedDataPoint>,
        motionPoints: List<UnifiedDataPoint>,
        gpsPoints: List<UnifiedDataPoint>,
        screenshotPoints: List<UnifiedDataPoint>,
        treeCount: Int = 64,
        maxTrainingRows: Int = 1500,
    ): PhenotypeRunUiBundle {
        val result = trainFromVolatileCacheSnapshots(
            audioPoints = audioPoints,
            motionPoints = motionPoints,
            gpsPoints = gpsPoints,
            screenshotPoints = screenshotPoints,
            treeCount = treeCount,
            maxTrainingRows = maxTrainingRows,
        )
        val phenotypeReport = formatTrainResultReport(
            result,
            audioPoints = audioPoints,
            motionPoints = motionPoints,
            gpsPoints = gpsPoints,
            screenshotPoints = screenshotPoints,
        )
        val envelope = when (result) {
            is TrainResult.Success -> {
                val top = topPredictorKey(result.model.forest)
                val summary = String.format(
                    Locale.US,
                    "rows=%d RMSE=%.3f R²=%.4f topPredictor=%s",
                    result.summary.rowsUsed,
                    result.summary.outOfBagRmse,
                    result.summary.outOfBagR2,
                    top ?: "?",
                )
                PhenotypeRunEnvelope(
                    outcome = PhenotypeRunOutcome.SUCCESS,
                    topPredictorFeature = top,
                    summaryOneLine = summary,
                )
            }
            is TrainResult.InsufficientData ->
                PhenotypeRunEnvelope(
                    outcome = PhenotypeRunOutcome.INSUFFICIENT_DATA,
                    topPredictorFeature = null,
                    summaryOneLine = result.message.take(200),
                )
        }
        val interventionReceipt = EngineInterventionReceipts.acknowledgePhenotypeRun(envelope)
        return PhenotypeRunUiBundle(
            phenotypeReport = phenotypeReport,
            interventionReceipt = interventionReceipt,
        )
    }

    fun formatTrainResultReport(
        result: TrainResult,
        audioPoints: List<UnifiedDataPoint>,
        motionPoints: List<UnifiedDataPoint>,
        gpsPoints: List<UnifiedDataPoint>,
        screenshotPoints: List<UnifiedDataPoint>,
    ): String {
        return when (result) {
            is TrainResult.InsufficientData -> result.message
            is TrainResult.Success -> {
                val tRef = defaultReferenceInstant(
                    audioPoints,
                    screenshotPoints,
                    gpsPoints,
                    motionPoints,
                )
                // TEMPORARY: hide predicted sessionTotalSteps line from phenotype report; restore block below to re-enable.
                /* val pred = result.model.predictSessionTotalAtReferenceTime(
                    tRef,
                    audioPoints,
                    screenshotPoints,
                    gpsPoints,
                ) */
                buildString {
                    appendLine("Step-count RF (volatile caches, temporally aligned)")
                    appendLine("reportWallClock=${Instant.now()}")
                    append("trainRows=").append(result.summary.rowsUsed)
                    append(", trees=").append(result.summary.treeCount).appendLine()
                    appendLine(
                        String.format(
                            Locale.US,
                            "OOB RMSE=%.3f  R²=%.4f",
                            result.summary.outOfBagRmse,
                            result.summary.outOfBagR2,
                        ),
                    )
                    appendLine("referenceInstant=$tRef")
                    /* appendLine(
                        String.format(
                            Locale.US,
                            "predicted sessionTotalSteps≈%.0f (cumulative counter; not steps/min)",
                            pred,
                        ),
                    ) */
                    appendLine()
                    appendLine("Feature importance:")
                    appendFeatureImportanceSection(result.model.forest, this)
                }
            }
        }
    }

    /** Fallback labels if [RandomForest.schema] does not match [RandomForest.importance] length. */
    private val predictorImportanceLabels = listOf(
        "audio_db (mean/RMS dB)",
        "screenshot sentiment",
        "gps sunScore0To10",
    )

    /**
     * Predictor column name (e.g. `audio_db`) with largest absolute [RandomForest.importance] weight.
     */
    private fun topPredictorKey(forest: RandomForest): String? {
        val raw = forest.importance()
        if (raw.isEmpty()) return null
        val names = predictorNamesForImportance(forest, raw.size)
        val pairs = names.zip(raw.toList())
        return pairs.maxByOrNull { abs(it.second) }?.first
    }

    private fun appendFeatureImportanceSection(forest: RandomForest, out: StringBuilder) {
        val raw = forest.importance()
        if (raw.isEmpty()) {
            out.appendLine("  (not available)")
            return
        }
        val names = predictorNamesForImportance(forest, raw.size)
        val pairs = names.zip(raw.toList())
        val denom = pairs.sumOf { abs(it.second) }.takeIf { it > 1e-12 } ?: 1.0
        pairs.forEach { (label, value) ->
            out.appendLine(
                String.format(
                    Locale.US,
                    "  %-28s %10.5f  (%5.1f%%)",
                    label,
                    value,
                    100.0 * abs(value) / denom,
                ),
            )
        }
    }

    private fun predictorNamesForImportance(forest: RandomForest, importanceLen: Int): List<String> {
        val schema = runCatching { forest.schema() }.getOrNull()
        if (schema != null) {
            val fromSchema = buildList {
                val n = runCatching { schema.length() }.getOrNull() ?: return@buildList
                for (i in 0 until n) {
                    val name = runCatching { schema.field(i).name }.getOrNull() ?: continue
                    if (name == "steps") continue
                    add(name)
                }
            }
            if (fromSchema.size == importanceLen) return fromSchema
        }
        return (0 until importanceLen).map { i ->
            predictorImportanceLabels.getOrElse(i) { "feature_$i" }
        }
    }

    private fun featureMedianTriple(rows: List<AlignedPhenotypeRow>): Triple<Double, Double, Double> {
        fun med(values: List<Double>): Double {
            val v = values.sorted()
            if (v.isEmpty()) return 0.0
            val m = v.size / 2
            return if (v.size % 2 == 1) v[m] else (v[m - 1] + v[m]) / 2.0
        }
        return Triple(
            med(rows.map { it.audioMeanDb }),
            med(rows.map { it.sentiment }),
            med(rows.map { it.sunScore }),
        )
    }
}
