package com.app.modules.audio

import edu.stanford.screenomics.core.collection.AudioPcmBufferRawFrame
import edu.stanford.screenomics.core.collection.AudioRawCaptureFrame
import edu.stanford.screenomics.core.collection.RawModalityFrame
import edu.stanford.screenomics.core.module.template.BaseAdapter
import edu.stanford.screenomics.core.storage.ModalityLocalFileSink
import edu.stanford.screenomics.core.storage.StorageArtifactFilename
import edu.stanford.screenomics.core.storage.UfsBinaryPayloadCodec
import edu.stanford.screenomics.core.unified.DataDescription
import edu.stanford.screenomics.core.unified.ModalityKind
import edu.stanford.screenomics.core.unified.UfsEnvelopeVersions
import edu.stanford.screenomics.core.unified.UfsReservedDataKeys
import edu.stanford.screenomics.core.unified.UnifiedDataPoint
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import kotlin.math.ln
import kotlin.math.sqrt

private const val AUDIO_KEY_MEAN_DB: String = "audio.signal.meanDb"

/**
 * dBFS for this buffer lies in [-120, 0]; adding this yields a 0–120 dB scale where 0 ≈ digital silence
 * and 120 ≈ 16-bit full scale. Device/mic gain is not compensated—do not interpret as dB SPL.
 */
private const val MEAN_DB_OFFSET_FROM_DBFS: Double = 120.0

/**
 * Minimal audio [BaseAdapter]: mean RMS level as `audio.signal.meanDb` (0–120 re. digital silence); zlib PCM
 * artifact written separately via [localFileSink] when not duplicate-suppressed.
 */
class AudioAdapter(
    adapterId: String,
    private val captureSessionId: String,
    private val producerNodeId: String,
    private val localFileSink: ModalityLocalFileSink? = null,
    private val sourceLabel: String = "android:AudioRecord",
    private val acquisitionMethodLabel: String = "AudioRecord.nonBlocking",
    private val ufsEnvelopeVersion: String = UfsEnvelopeVersions.V1,
) : BaseAdapter(adapterId = adapterId, modality = ModalityKind.AUDIO) {

    private val dedupeMutex = Mutex()
    private var lastContentFingerprint: Int? = null

    override suspend fun adaptRaw(raw: RawModalityFrame): UnifiedDataPoint =
        when (raw) {
            is AudioPcmBufferRawFrame -> adaptPcm(raw)
            is AudioRawCaptureFrame -> adaptLegacyPlaceholder(raw)
            else -> error("unsupported RawModalityFrame for audio: ${raw::class.simpleName}")
        }

    private fun alignInterleavedPcm(interleaved: ShortArray, channelCount: Int): ShortArray {
        val remainder = interleaved.size % channelCount
        return if (remainder == 0) interleaved else interleaved.copyOf(interleaved.size - remainder)
    }

    private suspend fun adaptPcm(raw: AudioPcmBufferRawFrame): UnifiedDataPoint {
        require(raw.samples.isNotEmpty()) { "AudioPcmBufferRawFrame.samples must be non-empty" }
        require(raw.channelCount >= 1) { "channelCount must be >= 1" }

        val alignedSamples = alignInterleavedPcm(raw.samples, raw.channelCount)
        if (alignedSamples.isEmpty()) {
            return adaptPcmAlignmentDroppedPlaceholder(raw)
        }
        val cleanedMetrics = cleanAndMeasure(alignedSamples, raw.channelCount)
        val suppressed = dedupeMutex.withLock {
            val dup = if (cleanedMetrics.cleanedFingerprint == lastContentFingerprint) 1L else 0L
            lastContentFingerprint = cleanedMetrics.cleanedFingerprint
            dup
        }
        if (suppressed == 0L && localFileSink != null) {
            val stamp = StorageArtifactFilename.stampUtc(raw.capturedAtEpochMillis)
            val rel = "audio_$stamp.bin"
            val pcmLe = UfsBinaryPayloadCodec.cleanedPcm16LeBytes(cleanedMetrics.cleanedSamples)
            val zlib = UfsBinaryPayloadCodec.deflateToBytes(pcmLe)
            localFileSink.writeBytes(
                modality = ModalityKind.AUDIO,
                relativePath = rel,
                bytes = zlib,
                dedupeContentKey = "${cleanedMetrics.cleanedFingerprint}",
            )
        }

        val monoNanos = System.nanoTime()
        val correlationString = raw.correlationId.value
        val data = linkedMapOf<String, Any>(
            UfsReservedDataKeys.CORRELATION_ID to correlationString,
            UfsReservedDataKeys.MONOTONIC_TIMESTAMP_NANOS to monoNanos,
            AUDIO_KEY_MEAN_DB to cleanedMetrics.meanDb,
        )
        val metadata = DataDescription(
            source = sourceLabel,
            timestamp = Instant.ofEpochMilli(raw.capturedAtEpochMillis),
            acquisitionMethod = acquisitionMethodLabel,
            modality = ModalityKind.AUDIO,
            captureSessionId = captureSessionId,
            producerNodeId = producerNodeId,
            producerAdapterId = adapterId,
            ufsEnvelopeVersion = ufsEnvelopeVersion,
        )
        return UnifiedDataPoint(data = data, metadata = metadata)
    }

    private suspend fun adaptPcmAlignmentDroppedPlaceholder(raw: AudioPcmBufferRawFrame): UnifiedDataPoint {
        val monoNanos = System.nanoTime()
        val correlationString = raw.correlationId.value
        val data = linkedMapOf<String, Any>(
            UfsReservedDataKeys.CORRELATION_ID to correlationString,
            UfsReservedDataKeys.MONOTONIC_TIMESTAMP_NANOS to monoNanos,
            AUDIO_KEY_MEAN_DB to 0.0,
        )
        val metadata = DataDescription(
            source = sourceLabel,
            timestamp = Instant.ofEpochMilli(raw.capturedAtEpochMillis),
            acquisitionMethod = acquisitionMethodLabel,
            modality = ModalityKind.AUDIO,
            captureSessionId = captureSessionId,
            producerNodeId = producerNodeId,
            producerAdapterId = adapterId,
            ufsEnvelopeVersion = ufsEnvelopeVersion,
        )
        return UnifiedDataPoint(data = data, metadata = metadata)
    }

    private suspend fun adaptLegacyPlaceholder(raw: AudioRawCaptureFrame): UnifiedDataPoint {
        val monoNanos = System.nanoTime()
        val correlationString = raw.correlationId.value
        val data = linkedMapOf<String, Any>(
            UfsReservedDataKeys.CORRELATION_ID to correlationString,
            UfsReservedDataKeys.MONOTONIC_TIMESTAMP_NANOS to monoNanos,
            AUDIO_KEY_MEAN_DB to 0.0,
        )
        val metadata = DataDescription(
            source = sourceLabel,
            timestamp = Instant.ofEpochMilli(raw.capturedAtEpochMillis),
            acquisitionMethod = acquisitionMethodLabel,
            modality = ModalityKind.AUDIO,
            captureSessionId = captureSessionId,
            producerNodeId = producerNodeId,
            producerAdapterId = adapterId,
            ufsEnvelopeVersion = ufsEnvelopeVersion,
        )
        return UnifiedDataPoint(data = data, metadata = metadata)
    }

    private data class CleanedMetrics(
        val meanDb: Double,
        val cleanedFingerprint: Int,
        val cleanedSamples: ShortArray,
    )

    private fun cleanAndMeasure(interleaved: ShortArray, channelCount: Int): CleanedMetrics {
        val frameCount = interleaved.size / channelCount
        require(frameCount > 0) { "sample buffer too small for channelCount=$channelCount" }
        require(interleaved.size == frameCount * channelCount) {
            "interleaved buffer size ${interleaved.size} not divisible by channelCount=$channelCount"
        }

        val dcOffsets = DoubleArray(channelCount)
        for (frame in 0 until frameCount) {
            val base = frame * channelCount
            for (ch in 0 until channelCount) {
                dcOffsets[ch] += interleaved[base + ch].toDouble()
            }
        }
        for (ch in 0 until channelCount) {
            dcOffsets[ch] /= frameCount.toDouble()
        }

        val cleaned = ShortArray(interleaved.size)
        var write = 0
        while (write < interleaved.size) {
            for (ch in 0 until channelCount) {
                val rawSample = interleaved[write + ch].toDouble()
                val mean = dcOffsets[ch]
                val centered = (rawSample - mean).toInt().coerceIn(
                    Short.MIN_VALUE.toInt(),
                    Short.MAX_VALUE.toInt(),
                )
                cleaned[write + ch] = centered.toShort()
            }
            write += channelCount
        }

        var sumSq = 0.0
        for (s in cleaned) {
            val d = s.toDouble()
            sumSq += d * d
        }
        val n = cleaned.size
        val rms = sqrt(sumSq / n.toDouble())
        val rmsDbfs = if (rms <= 1e-12) {
            -120.0
        } else {
            (20.0 * (ln(rms / 32768.0) / ln(10.0))).coerceIn(-120.0, 0.0)
        }
        val meanDb = (rmsDbfs + MEAN_DB_OFFSET_FROM_DBFS).coerceIn(0.0, 120.0)

        return CleanedMetrics(
            meanDb = meanDb,
            cleanedFingerprint = cleaned.contentHashCode(),
            cleanedSamples = cleaned,
        )
    }
}
