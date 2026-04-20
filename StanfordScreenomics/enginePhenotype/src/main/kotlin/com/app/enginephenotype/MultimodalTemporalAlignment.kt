package com.app.enginephenotype

import edu.stanford.screenomics.core.unified.ModalityKind
import edu.stanford.screenomics.core.unified.UnifiedDataPoint
import java.time.Instant
import kotlin.math.min

/**
 * Irregular multimodal streams (different sensor cadences) are aligned to **motion step anchors**
 * using **asynchronous time series** rules:
 *
 * 1. **Backward LOCF** — for each anchor time `t`, use the latest sample of each signal with `timestamp <= t`.
 * 2. **Forward fill** — if nothing exists before `t`, use the earliest sample with `timestamp >= t` (sensor started late).
 * 3. **Median imputation** — remaining gaps (no signal yet) are filled with the column median over training rows
 *    so the random forest always receives finite inputs.
 */
internal data class TimedDouble(val time: Instant, val value: Double)

internal data class AlignedPhenotypeRow(
    val anchorTime: Instant,
    val audioMeanDb: Double,
    val sentiment: Double,
    val sunScore: Double,
    val sessionTotalSteps: Double,
)

internal const val KEY_AUDIO_MEAN_DB = "audio.signal.meanDb"
internal const val KEY_AUDIO_RMS_DB = "audio.signal.rmsDb"
internal const val KEY_SCREENSHOT_SENTIMENT = "screenshot.sentiment.score"
internal const val KEY_GPS_SUN = "gps.weather.sunScore0To10"
internal const val KEY_MOTION_STEP_SESSION = "motion.step.sessionTotal"

internal fun anyToDouble(v: Any?): Double? = when (v) {
    is Number -> v.toDouble()
    else -> null
}

internal fun anyToLong(v: Any?): Long? = when (v) {
    is Number -> v.toLong()
    else -> null
}

internal fun extractAudioMeanDbSeries(points: List<UnifiedDataPoint>): List<TimedDouble> =
    points.asSequence()
        .filter { it.metadata.modality == ModalityKind.AUDIO }
        .mapNotNull { p ->
            val db = (p.data[KEY_AUDIO_MEAN_DB] ?: p.data[KEY_AUDIO_RMS_DB])?.let { anyToDouble(it) }
                ?: return@mapNotNull null
            TimedDouble(p.metadata.timestamp, db)
        }
        .sortedBy { it.time }
        .toList()

internal fun extractScreenshotSentimentSeries(points: List<UnifiedDataPoint>): List<TimedDouble> =
    extractNumericSeries(points, ModalityKind.SCREENSHOT, KEY_SCREENSHOT_SENTIMENT)

internal fun extractGpsSunSeries(points: List<UnifiedDataPoint>): List<TimedDouble> =
    extractNumericSeries(points, ModalityKind.GPS, KEY_GPS_SUN)

private fun extractNumericSeries(
    points: List<UnifiedDataPoint>,
    modality: ModalityKind,
    key: String,
): List<TimedDouble> =
    points.asSequence()
        .filter { it.metadata.modality == modality }
        .mapNotNull { p ->
            val v = p.data[key]?.let { anyToDouble(it) } ?: return@mapNotNull null
            TimedDouble(p.metadata.timestamp, v)
        }
        .sortedBy { it.time }
        .toList()

/**
 * Motion points that carry [KEY_MOTION_STEP_SESSION] (minute-window step summaries), sorted by time.
 */
internal fun extractMotionStepAnchors(points: List<UnifiedDataPoint>): List<Pair<Instant, Double>> =
    points.asSequence()
        .filter { it.metadata.modality == ModalityKind.MOTION }
        .mapNotNull { p ->
            val steps = p.data[KEY_MOTION_STEP_SESSION]?.let { anyToLong(it) } ?: return@mapNotNull null
            p.metadata.timestamp to steps.toDouble()
        }
        .sortedBy { it.first }
        .toList()

internal fun lastValueAtOrBefore(series: List<TimedDouble>, t: Instant): Double? {
    if (series.isEmpty()) return null
    var lo = 0
    var hi = series.size - 1
    var best = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (!series[mid].time.isAfter(t)) {
            best = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return if (best >= 0) series[best].value else null
}

internal fun firstValueAtOrAfter(series: List<TimedDouble>, t: Instant): Double? {
    if (series.isEmpty()) return null
    var lo = 0
    var hi = series.size - 1
    var first = series.size
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (!series[mid].time.isBefore(t)) {
            first = mid
            hi = mid - 1
        } else {
            lo = mid + 1
        }
    }
    return if (first < series.size) series[first].value else null
}

internal fun locfThenForwardElseNaN(t: Instant, series: List<TimedDouble>): Double {
    val back = lastValueAtOrBefore(series, t)
    if (back != null) return back
    val fwd = firstValueAtOrAfter(series, t)
    return fwd ?: Double.NaN
}

internal fun subsampleIndices(n: Int, maxRows: Int): List<Int> {
    if (n <= 0) return emptyList()
    if (maxRows <= 1) return listOf(n - 1)
    if (n <= maxRows) return (0 until n).toList()
    val cap = maxRows.coerceAtLeast(2)
    return (0 until cap).map { k ->
        min(n - 1, (k * (n - 1).toLong() / (cap - 1).toLong()).toInt())
    }.distinct().sorted()
}

/**
 * Builds one training row per (subsampled) motion step anchor: **y** = session step total at that time;
 * **X** = audio dB, screenshot sentiment, GPS sun score each aligned to that instant.
 */
internal fun buildAlignedStepTrainingRows(
    audioPoints: List<UnifiedDataPoint>,
    screenshotPoints: List<UnifiedDataPoint>,
    gpsPoints: List<UnifiedDataPoint>,
    motionPoints: List<UnifiedDataPoint>,
    maxTrainingRows: Int = 1500,
): List<AlignedPhenotypeRow> {
    val audioS = extractAudioMeanDbSeries(audioPoints)
    val sentS = extractScreenshotSentimentSeries(screenshotPoints)
    val sunS = extractGpsSunSeries(gpsPoints)
    val anchors = extractMotionStepAnchors(motionPoints)
    if (anchors.isEmpty()) return emptyList()
    val idx = subsampleIndices(anchors.size, maxTrainingRows)
    val rawRows = ArrayList<AlignedPhenotypeRow>()
    for (i in idx) {
        val (t, y) = anchors[i]
        val a = locfThenForwardElseNaN(t, audioS)
        val s = locfThenForwardElseNaN(t, sentS)
        val u = locfThenForwardElseNaN(t, sunS)
        if (a.isNaN() && s.isNaN() && u.isNaN()) continue
        rawRows.add(
            AlignedPhenotypeRow(
                anchorTime = t,
                audioMeanDb = a,
                sentiment = s,
                sunScore = u,
                sessionTotalSteps = y,
            ),
        )
    }
    return imputeNaNsWithColumnMedian(rawRows)
}

internal fun imputeNaNsWithColumnMedian(rows: List<AlignedPhenotypeRow>): List<AlignedPhenotypeRow> {
    if (rows.isEmpty()) return rows
    fun medianOf(getter: (AlignedPhenotypeRow) -> Double): Double {
        val vals = rows.map(getter).filter { !it.isNaN() }.sorted()
        if (vals.isEmpty()) return 0.0
        val m = vals.size / 2
        return if (vals.size % 2 == 1) vals[m] else (vals[m - 1] + vals[m]) / 2.0
    }
    val ma = medianOf { it.audioMeanDb }
    val ms = medianOf { it.sentiment }
    val mu = medianOf { it.sunScore }
    return rows.map { r ->
        r.copy(
            audioMeanDb = if (r.audioMeanDb.isNaN()) ma else r.audioMeanDb,
            sentiment = if (r.sentiment.isNaN()) ms else r.sentiment,
            sunScore = if (r.sunScore.isNaN()) mu else r.sunScore,
        )
    }
}

/**
 * Features at [referenceTime] for one-off prediction (same LOCF + forward + median rules as training).
 */
internal fun alignedFeaturesAtInstant(
    referenceTime: Instant,
    audioPoints: List<UnifiedDataPoint>,
    screenshotPoints: List<UnifiedDataPoint>,
    gpsPoints: List<UnifiedDataPoint>,
    trainingRowMedians: Triple<Double, Double, Double>,
): Triple<Double, Double, Double> {
    val audioS = extractAudioMeanDbSeries(audioPoints)
    val sentS = extractScreenshotSentimentSeries(screenshotPoints)
    val sunS = extractGpsSunSeries(gpsPoints)
    val a = locfThenForwardElseNaN(referenceTime, audioS).let { if (it.isNaN()) trainingRowMedians.first else it }
    val s = locfThenForwardElseNaN(referenceTime, sentS).let { if (it.isNaN()) trainingRowMedians.second else it }
    val u = locfThenForwardElseNaN(referenceTime, sunS).let { if (it.isNaN()) trainingRowMedians.third else it }
    return Triple(a, s, u)
}

internal fun defaultReferenceInstant(
    audioPoints: List<UnifiedDataPoint>,
    screenshotPoints: List<UnifiedDataPoint>,
    gpsPoints: List<UnifiedDataPoint>,
    motionPoints: List<UnifiedDataPoint>,
): Instant {
    val times = sequenceOf(
        audioPoints.asSequence().map { it.metadata.timestamp },
        screenshotPoints.asSequence().map { it.metadata.timestamp },
        gpsPoints.asSequence().map { it.metadata.timestamp },
        motionPoints.asSequence().map { it.metadata.timestamp },
    ).flatten().maxOrNull()
    return times ?: Instant.now()
}
