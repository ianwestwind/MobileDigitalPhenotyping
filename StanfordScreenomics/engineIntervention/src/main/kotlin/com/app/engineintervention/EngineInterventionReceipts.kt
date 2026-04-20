package com.app.engineintervention

import java.time.Instant

enum class PhenotypeRunOutcome {
    SUCCESS,
    INSUFFICIENT_DATA,
    PIPELINE_ERROR,
}

/**
 * Payload forwarded from [com.app.enginephenotype] after each RF run (or failure before a forest exists).
 */
data class PhenotypeRunEnvelope(
    val wallClock: Instant = Instant.now(),
    val outcome: PhenotypeRunOutcome,
    /** Predictor name with highest absolute importance (e.g. `audio_db`); null if no forest / unknown. */
    val topPredictorFeature: String?,
    /** One-line summary for the receipt (metrics or error hint). */
    val summaryOneLine: String,
)

/**
 * In-process receipt line for the UI. [acknowledgePhenotypeRun] updates **last successful** top predictor
 * only when [PhenotypeRunEnvelope.outcome] is [PhenotypeRunOutcome.SUCCESS] and [topPredictorFeature] is non-blank.
 */
object EngineInterventionReceipts {

    private val lock = Any()
    private var receiptSequence: Int = 0
    private var lastTopPredictor: String? = null

    fun acknowledgePhenotypeRun(envelope: PhenotypeRunEnvelope): String {
        val currentTop = envelope.topPredictorFeature?.takeIf { it.isNotBlank() }
        val (seq, phenotypeUpdated, previousTop) = synchronized(lock) {
            receiptSequence += 1
            when (envelope.outcome) {
                PhenotypeRunOutcome.SUCCESS -> {
                    val prev = lastTopPredictor
                    val updated = currentTop != null && prev != null && prev != currentTop
                    if (currentTop != null) {
                        lastTopPredictor = currentTop
                    }
                    Triple(receiptSequence, updated, prev)
                }
                PhenotypeRunOutcome.INSUFFICIENT_DATA,
                PhenotypeRunOutcome.PIPELINE_ERROR,
                -> Triple(receiptSequence, false, null)
            }
        }
        return buildReceiptText(
            seq = seq,
            envelope = envelope,
            phenotypeUpdated = phenotypeUpdated,
            previousTop = previousTop,
            currentTop = currentTop,
        )
    }

    fun acknowledgePipelineFailure(message: String): String =
        acknowledgePhenotypeRun(
            PhenotypeRunEnvelope(
                outcome = PhenotypeRunOutcome.PIPELINE_ERROR,
                topPredictorFeature = null,
                summaryOneLine = message.take(240),
            ),
        )

    private fun buildReceiptText(
        seq: Int,
        envelope: PhenotypeRunEnvelope,
        phenotypeUpdated: Boolean,
        previousTop: String?,
        currentTop: String?,
    ): String = buildString {
        appendLine("engineIntervention RECEIPT #$seq")
        appendLine("wallClock=${envelope.wallClock}")
        appendLine("outcome=${envelope.outcome}")
        if (currentTop != null) {
            appendLine("topPredictor=$currentTop")
        }
        appendLine("---")
        appendLine(envelope.summaryOneLine)
        when (envelope.outcome) {
            PhenotypeRunOutcome.SUCCESS -> {
                when {
                    phenotypeUpdated ->
                        appendLine("PHENOTYPE_UPDATED: top predictor changed (was $previousTop).")
                    currentTop == null ->
                        appendLine("(no top predictor in this run.)")
                    previousTop == null ->
                        appendLine("(first successful run — no prior top predictor to compare.)")
                    else ->
                        appendLine("Top predictor unchanged since last success ($currentTop).")
                }
            }
            PhenotypeRunOutcome.INSUFFICIENT_DATA ->
                appendLine("No forest trained — last successful top predictor left unchanged.")
            PhenotypeRunOutcome.PIPELINE_ERROR ->
                appendLine("Pipeline error — phenotype payload not applied to intervention state.")
        }
    }.trimEnd()
}
