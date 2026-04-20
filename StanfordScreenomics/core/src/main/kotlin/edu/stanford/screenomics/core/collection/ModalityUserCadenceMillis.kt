package edu.stanford.screenomics.core.collection

import edu.stanford.screenomics.core.unified.ModalityKind
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-modality collection cadence (milliseconds), updated from the app main UI in real time.
 * [DataNode][edu.stanford.screenomics.core.collection] loops read these atomically so intervals change
 * without restarting the foreground collection service.
 *
 * Defaults match the historical fixed constants in each modality node.
 */
object ModalityUserCadenceMillis {

    private val audioCycleSpacingMs = AtomicLong(55_000L)
    private val motionStepWindowMs = AtomicLong(60_000L)
    private val gpsPollMs = AtomicLong(30_000L)
    private val screenshotPollMs = AtomicLong(5_000L)

    private const val MIN_MS: Long = 1_000L
    private const val MAX_MS: Long = 3_600_000L

    fun setForModality(modality: ModalityKind, millis: Long) {
        val m = millis.coerceIn(MIN_MS, MAX_MS)
        when (modality) {
            ModalityKind.AUDIO -> audioCycleSpacingMs.set(m)
            ModalityKind.MOTION -> motionStepWindowMs.set(m)
            ModalityKind.GPS -> gpsPollMs.set(m)
            ModalityKind.SCREENSHOT -> screenshotPollMs.set(m)
        }
    }

    fun audioCycleSpacingMs(): Long = audioCycleSpacingMs.get()
    fun motionStepWindowMs(): Long = motionStepWindowMs.get()
    fun gpsPollMs(): Long = gpsPollMs.get()
    fun screenshotPollMs(): Long = screenshotPollMs.get()
}
