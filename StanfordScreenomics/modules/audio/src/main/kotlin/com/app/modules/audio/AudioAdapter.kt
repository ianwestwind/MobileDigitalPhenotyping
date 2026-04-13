package com.app.modules.audio

import android.media.AudioFormat
import edu.stanford.screenomics.core.debug.PipelineDiagnosticsFormat
import edu.stanford.screenomics.core.debug.PipelineDiagnosticsRegistry
import edu.stanford.screenomics.core.debug.PipelineLogTags
import edu.stanford.screenomics.core.collection.AudioPcmBufferRawFrame
import edu.stanford.screenomics.core.collection.AudioRawCaptureFrame
import edu.stanford.screenomics.core.collection.RawModalityFrame
import edu.stanford.screenomics.core.module.template.BaseAdapter
import edu.stanford.screenomics.core.storage.DistributedStoragePayloadHints
import edu.stanford.screenomics.core.storage.UfsBinaryPayloadCodec
import edu.stanford.screenomics.core.unified.DataDescription
import edu.stanford.screenomics.core.unified.DataEntity
import edu.stanford.screenomics.core.unified.EntityAttributeSpec
import edu.stanford.screenomics.core.unified.EntityRelationship
import edu.stanford.screenomics.core.unified.EntityTypeSpec
import edu.stanford.screenomics.core.unified.ModalityKind
import edu.stanford.screenomics.core.unified.NumericEntityValueRange
import edu.stanford.screenomics.core.unified.ProvenanceRecord
import edu.stanford.screenomics.core.unified.UnboundedEntityValueRange
import edu.stanford.screenomics.core.unified.UfsEnvelopeVersions
import edu.stanford.screenomics.core.unified.UfsReservedDataKeys
import edu.stanford.screenomics.core.unified.UfsSchemaComposition
import edu.stanford.screenomics.core.unified.UnifiedDataPoint
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

private const val AUDIO_SCHEMA_ID: String = "com.app.modules.audio/ufs-schema"
private const val AUDIO_SCHEMA_REVISION: String = "3"

private const val AUDIO_KEY_RMS_DB: String = "audio.signal.rmsDb"
private const val AUDIO_KEY_PEAK_ABS: String = "audio.signal.peakAbs"
private const val AUDIO_KEY_SAMPLE_COUNT: String = "audio.window.sampleCount"
private const val AUDIO_KEY_ENCODING: String = "audio.device.encodingConstant"
private const val AUDIO_KEY_DEDUPE_SUPPRESSED: String = "audio.dedupe.suppressed"
private const val AUDIO_KEY_DC_OFFSET_REMOVED: String = "audio.processing.dcOffsetRemoved"

private fun buildAudioPayloadDataEntity(): DataEntity = UfsSchemaComposition.compose(
    schemaId = AUDIO_SCHEMA_ID,
    schemaRevision = AUDIO_SCHEMA_REVISION,
    payloadAttributes = mapOf(
        AUDIO_KEY_RMS_DB to EntityAttributeSpec(
            required = true,
            semanticDescription = "Root-mean-square level of the cleaned window in dBFS (16-bit full-scale reference)",
            structureTag = "scalar.double",
        ),
        AUDIO_KEY_PEAK_ABS to EntityAttributeSpec(
            required = true,
            semanticDescription = "Absolute peak sample magnitude after cleaning (linear, post-DC-removal)",
            structureTag = "scalar.double",
        ),
        AUDIO_KEY_SAMPLE_COUNT to EntityAttributeSpec(
            required = true,
            semanticDescription = "Number of PCM samples in the adapted window",
            structureTag = "scalar.long",
        ),
        AUDIO_KEY_ENCODING to EntityAttributeSpec(
            required = true,
            semanticDescription = "Android AudioFormat encoding constant for the source buffer",
            structureTag = "scalar.long",
        ),
        AUDIO_KEY_DEDUPE_SUPPRESSED to EntityAttributeSpec(
            required = true,
            semanticDescription = "1 if this window was flagged as a duplicate of the prior fingerprint; 0 otherwise",
            structureTag = "scalar.long",
        ),
        AUDIO_KEY_DC_OFFSET_REMOVED to EntityAttributeSpec(
            required = true,
            semanticDescription = "Estimated mean sample value removed during DC-offset cleaning (pre-subtraction mean)",
            structureTag = "scalar.double",
        ),
        DistributedStoragePayloadHints.AUDIO_DEFLATED_PCM_BASE64 to EntityAttributeSpec(
            required = false,
            semanticDescription = "Zlib-deflated cleaned PCM16 LE window (Base64); omitted when duplicate-window suppressed",
            structureTag = "media.base64.zlib",
        ),
        DistributedStoragePayloadHints.AUDIO_DEFLATED_SHA256_HEX to EntityAttributeSpec(
            required = false,
            semanticDescription = "SHA-256 (hex) of deflated audio bytes; aligns with local/cloud object dedupe key",
            structureTag = "integrity.sha256.hex",
        ),
    ),
    payloadTypes = mapOf(
        AUDIO_KEY_RMS_DB to EntityTypeSpec("audio.RmsDbfs", "kotlin.Double", "1"),
        AUDIO_KEY_PEAK_ABS to EntityTypeSpec("audio.PeakAbs", "kotlin.Double", "1"),
        AUDIO_KEY_SAMPLE_COUNT to EntityTypeSpec("audio.SampleCount", "kotlin.Long", "1"),
        AUDIO_KEY_ENCODING to EntityTypeSpec("audio.PcmEncoding", "kotlin.Long", "1"),
        AUDIO_KEY_DEDUPE_SUPPRESSED to EntityTypeSpec("audio.DedupeFlag", "kotlin.Long", "1"),
        AUDIO_KEY_DC_OFFSET_REMOVED to EntityTypeSpec("audio.DcOffset", "kotlin.Double", "1"),
        DistributedStoragePayloadHints.AUDIO_DEFLATED_PCM_BASE64 to EntityTypeSpec(
            "audio.DeflatedPcmB64",
            "kotlin.String",
            "1",
        ),
        DistributedStoragePayloadHints.AUDIO_DEFLATED_SHA256_HEX to EntityTypeSpec(
            "audio.DeflatedSha256Hex",
            "kotlin.String",
            "1",
        ),
    ),
    payloadValueRanges = mapOf(
        AUDIO_KEY_RMS_DB to NumericEntityValueRange(minInclusive = -120.0, maxInclusive = 0.0, stepHint = null),
        AUDIO_KEY_PEAK_ABS to NumericEntityValueRange(minInclusive = 0.0, maxInclusive = 32768.0, stepHint = null),
        AUDIO_KEY_SAMPLE_COUNT to NumericEntityValueRange(minInclusive = 1.0, maxInclusive = 2_000_000.0, stepHint = null),
        AUDIO_KEY_ENCODING to UnboundedEntityValueRange("android-audio-encoding-constant"),
        AUDIO_KEY_DEDUPE_SUPPRESSED to NumericEntityValueRange(minInclusive = 0.0, maxInclusive = 1.0, stepHint = 1.0),
        AUDIO_KEY_DC_OFFSET_REMOVED to NumericEntityValueRange(
            minInclusive = -32768.0,
            maxInclusive = 32768.0,
            stepHint = null,
        ),
        DistributedStoragePayloadHints.AUDIO_DEFLATED_PCM_BASE64 to UnboundedEntityValueRange("audio-deflated-pcm-b64"),
        DistributedStoragePayloadHints.AUDIO_DEFLATED_SHA256_HEX to UnboundedEntityValueRange("audio-deflated-sha256-hex"),
    ),
    relationships = listOf(
        EntityRelationship(
            subjectAttributeKey = AUDIO_KEY_SAMPLE_COUNT,
            predicateToken = "informs",
            objectAttributeKey = AUDIO_KEY_RMS_DB,
            bidirectional = false,
            cardinalityHint = "N:1",
        ),
        EntityRelationship(
            subjectAttributeKey = AUDIO_KEY_PEAK_ABS,
            predicateToken = "bounds",
            objectAttributeKey = AUDIO_KEY_RMS_DB,
            bidirectional = false,
            cardinalityHint = "1:1",
        ),
    ),
)

/**
 * Gradle module `:modules:audio` — concrete [BaseAdapter]: DC-offset cleaning, duplicate-window suppression,
 * dBFS / peak extraction, and UFS [DataDescription] / [DataEntity] on every [UnifiedDataPoint].
 */
class AudioAdapter(
    adapterId: String,
    private val captureSessionId: String,
    private val producerNodeId: String,
    private val sourceLabel: String = "android:AudioRecord",
    private val acquisitionMethodLabel: String = "AudioRecord.nonBlocking",
    private val ufsEnvelopeVersion: String = UfsEnvelopeVersions.V1,
    private val dataEntity: DataEntity = buildAudioPayloadDataEntity(),
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
        PipelineDiagnosticsRegistry.emit(
            logTag = PipelineLogTags.ADAPTER,
            module = ModalityKind.AUDIO,
            stage = "adapter_cleaning",
            dataType = "mean_decibel",
            detail = "[ADAPTER][AUDIO_MODULE] DC-offset removed=${cleanedMetrics.dcOffsetScalar} " +
                "rmsDbfs=${cleanedMetrics.rmsDbfs} peakAbs=${cleanedMetrics.peakAbs} " +
                "cleanedSamples=${cleanedMetrics.cleanedSampleCount} timestamp=${raw.capturedAtEpochMillis}",
        )
        val fingerprint = cleanedMetrics.cleanedFingerprint
        val suppressed = dedupeMutex.withLock {
            val dup = if (fingerprint == lastContentFingerprint) 1L else 0L
            lastContentFingerprint = fingerprint
            dup
        }
        PipelineDiagnosticsRegistry.emit(
            logTag = PipelineLogTags.ADAPTER,
            module = ModalityKind.AUDIO,
            stage = "adapter_deduplication",
            dataType = "audio_fingerprint",
            detail = "[ADAPTER][AUDIO_MODULE] dedupeSuppressed=$suppressed fingerprint=$fingerprint " +
                "timestamp=${raw.capturedAtEpochMillis}",
        )

        val monoNanos = System.nanoTime()
        val correlationString = raw.correlationId.value

        val data = linkedMapOf<String, Any>(
            UfsReservedDataKeys.CORRELATION_ID to correlationString,
            UfsReservedDataKeys.MONOTONIC_TIMESTAMP_NANOS to monoNanos,
            UfsReservedDataKeys.PROVENANCE_RECORDS to listOf(
                ProvenanceRecord(
                    hopName = "audio.adapt.pcm",
                    componentId = adapterId,
                    recordedAtEpochMillis = System.currentTimeMillis(),
                    note = "cleaned=${cleanedMetrics.cleanedSampleCount};dedupeSuppressed=$suppressed",
                ),
            ),
            AUDIO_KEY_RMS_DB to cleanedMetrics.rmsDbfs,
            AUDIO_KEY_PEAK_ABS to cleanedMetrics.peakAbs,
            AUDIO_KEY_SAMPLE_COUNT to cleanedMetrics.cleanedSampleCount.toLong(),
            AUDIO_KEY_ENCODING to raw.audioEncoding.toLong(),
            AUDIO_KEY_DEDUPE_SUPPRESSED to suppressed,
            AUDIO_KEY_DC_OFFSET_REMOVED to cleanedMetrics.dcOffsetScalar,
        )
        if (suppressed == 0L) {
            val pcmLe = UfsBinaryPayloadCodec.cleanedPcm16LeBytes(cleanedMetrics.cleanedSamples)
            val (b64, shaHex) = UfsBinaryPayloadCodec.deflatedBase64AndSha256Hex(pcmLe)
            data[DistributedStoragePayloadHints.AUDIO_DEFLATED_PCM_BASE64] = b64
            data[DistributedStoragePayloadHints.AUDIO_DEFLATED_SHA256_HEX] = shaHex
            val ratioPct = if (pcmLe.isEmpty()) 0.0 else (b64.length * 100.0) / pcmLe.size
            PipelineDiagnosticsRegistry.emit(
                logTag = PipelineLogTags.ADAPTER,
                module = ModalityKind.AUDIO,
                stage = "adapter_compression",
                dataType = "deflated_pcm",
                detail = "[ADAPTER][AUDIO_MODULE] Compressed audio base64Len=${b64.length} pcmBytes=${pcmLe.size} " +
                    "approxRatioPct=${"%.1f".format(ratioPct)} sha=$shaHex",
            )
        } else {
            PipelineDiagnosticsRegistry.emit(
                logTag = PipelineLogTags.ADAPTER,
                module = ModalityKind.AUDIO,
                stage = "adapter_deduplication",
                dataType = "audio_payload",
                detail = "[ADAPTER][AUDIO_MODULE] Deflated PCM omitted (dedupeSuppressed=1)",
            )
        }

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
        PipelineDiagnosticsRegistry.emit(
            logTag = PipelineLogTags.ADAPTER,
            module = ModalityKind.AUDIO,
            stage = "adapter_transform_metadata",
            dataType = "audio_features",
            detail = "[ADAPTER][AUDIO_MODULE] Processed audio → mean_decibel=${cleanedMetrics.rmsDbfs} " +
                "timestamp=${raw.capturedAtEpochMillis} fullMetadata={ ${PipelineDiagnosticsFormat.dataDescription(metadata)} }",
        )

        return UnifiedDataPoint(
            data = data,
            metadata = metadata,
            schema = dataEntity,
        )
    }

    private suspend fun adaptPcmAlignmentDroppedPlaceholder(raw: AudioPcmBufferRawFrame): UnifiedDataPoint {
        val fingerprint = raw.samples.contentHashCode() xor raw.channelCount
        val suppressed = dedupeMutex.withLock {
            val dup = if (fingerprint == lastContentFingerprint) 1L else 0L
            lastContentFingerprint = fingerprint
            dup
        }
        val monoNanos = System.nanoTime()
        val correlationString = raw.correlationId.value
        val data = linkedMapOf<String, Any>(
            UfsReservedDataKeys.CORRELATION_ID to correlationString,
            UfsReservedDataKeys.MONOTONIC_TIMESTAMP_NANOS to monoNanos,
            UfsReservedDataKeys.PROVENANCE_RECORDS to listOf(
                ProvenanceRecord(
                    hopName = "audio.adapt.pcm.alignmentPlaceholder",
                    componentId = adapterId,
                    recordedAtEpochMillis = System.currentTimeMillis(),
                    note = "droppedPartialTail;samples=${raw.samples.size};ch=${raw.channelCount};dedupeSuppressed=$suppressed",
                ),
            ),
            AUDIO_KEY_RMS_DB to -120.0,
            AUDIO_KEY_PEAK_ABS to 0.0,
            AUDIO_KEY_SAMPLE_COUNT to 1L,
            AUDIO_KEY_ENCODING to raw.audioEncoding.toLong(),
            AUDIO_KEY_DEDUPE_SUPPRESSED to suppressed,
            AUDIO_KEY_DC_OFFSET_REMOVED to 0.0,
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
        return UnifiedDataPoint(
            data = data,
            metadata = metadata,
            schema = dataEntity,
        )
    }

    private suspend fun adaptLegacyPlaceholder(raw: AudioRawCaptureFrame): UnifiedDataPoint {
        val sampleCount = raw.pcmByteLengthPlaceholder.coerceAtLeast(2L) / 2L
        val bounded = sampleCount.coerceIn(1L, 2_000_000L)
        val fingerprint = raw.pcmByteLengthPlaceholder.hashCode()
        val suppressed = dedupeMutex.withLock {
            val dup = if (fingerprint == lastContentFingerprint) 1L else 0L
            lastContentFingerprint = fingerprint
            dup
        }

        val monoNanos = System.nanoTime()
        val correlationString = raw.correlationId.value

        val data = linkedMapOf<String, Any>(
            UfsReservedDataKeys.CORRELATION_ID to correlationString,
            UfsReservedDataKeys.MONOTONIC_TIMESTAMP_NANOS to monoNanos,
            UfsReservedDataKeys.PROVENANCE_RECORDS to listOf(
                ProvenanceRecord(
                    hopName = "audio.adapt.legacyPlaceholder",
                    componentId = adapterId,
                    recordedAtEpochMillis = System.currentTimeMillis(),
                    note = "pcmByteLengthPlaceholder=${raw.pcmByteLengthPlaceholder}",
                ),
            ),
            AUDIO_KEY_RMS_DB to -120.0,
            AUDIO_KEY_PEAK_ABS to 0.0,
            AUDIO_KEY_SAMPLE_COUNT to bounded,
            AUDIO_KEY_ENCODING to AudioFormat.ENCODING_PCM_16BIT.toLong(),
            AUDIO_KEY_DEDUPE_SUPPRESSED to suppressed,
            AUDIO_KEY_DC_OFFSET_REMOVED to 0.0,
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

        return UnifiedDataPoint(
            data = data,
            metadata = metadata,
            schema = dataEntity,
        )
    }

    private data class CleanedMetrics(
        val rmsDbfs: Double,
        val peakAbs: Double,
        val cleanedSampleCount: Int,
        val dcOffsetScalar: Double,
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

        val dcOffsetScalar = when (channelCount) {
            1 -> dcOffsets[0]
            else -> {
                var acc = 0.0
                for (v in dcOffsets) acc += v * v
                sqrt(acc / channelCount)
            }
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
        var peak = 0.0
        for (s in cleaned) {
            val d = s.toDouble()
            sumSq += d * d
            peak = max(peak, abs(d))
        }
        val n = cleaned.size
        val rms = sqrt(sumSq / n.toDouble())
        val rmsDbfs = if (rms <= 1e-12) {
            -120.0
        } else {
            (20.0 * (ln(rms / 32768.0) / ln(10.0))).coerceIn(-120.0, 0.0)
        }

        return CleanedMetrics(
            rmsDbfs = rmsDbfs,
            peakAbs = peak,
            cleanedSampleCount = n,
            dcOffsetScalar = dcOffsetScalar,
            cleanedFingerprint = cleaned.contentHashCode(),
            cleanedSamples = cleaned,
        )
    }
}
