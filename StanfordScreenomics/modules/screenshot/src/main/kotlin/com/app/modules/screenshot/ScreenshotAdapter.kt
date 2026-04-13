package com.app.modules.screenshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import edu.stanford.screenomics.core.debug.PipelineDiagnosticsFormat
import edu.stanford.screenomics.core.debug.PipelineDiagnosticsRegistry
import edu.stanford.screenomics.core.debug.PipelineLogTags
import edu.stanford.screenomics.core.collection.RawModalityFrame
import edu.stanford.screenomics.core.collection.ScreenshotRasterRawFrame
import edu.stanford.screenomics.core.collection.ScreenshotRawCaptureFrame
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.abs
import kotlin.math.max

private const val SHOT_SCHEMA_ID: String = "com.app.modules.screenshot/ufs-schema"
private const val SHOT_SCHEMA_REVISION: String = "3"

private const val KEY_OCR_FULL_TEXT: String = "screenshot.ocr.fullText"
private const val KEY_OCR_LINE_COUNT: String = "screenshot.ocr.lineCount"
private const val KEY_RASTER_WIDTH: String = "screenshot.raster.widthPx"
private const val KEY_RASTER_HEIGHT: String = "screenshot.raster.heightPx"
private const val KEY_RASTER_BYTE_LENGTH: String = "screenshot.raster.byteLength"
private const val KEY_SENTIMENT_SCORE: String = "screenshot.sentiment.score"
private const val KEY_SENTIMENT_PLACEHOLDER: String = "screenshot.sentiment.analysisPlaceholder"

private fun buildScreenshotPayloadDataEntity(): DataEntity = UfsSchemaComposition.compose(
    schemaId = SHOT_SCHEMA_ID,
    schemaRevision = SHOT_SCHEMA_REVISION,
    payloadAttributes = mapOf(
        KEY_OCR_FULL_TEXT to EntityAttributeSpec(
            required = true,
            semanticDescription = "Concatenated on-device OCR text from ML Kit Text Recognition",
            structureTag = "scalar.string",
        ),
        KEY_OCR_LINE_COUNT to EntityAttributeSpec(
            required = true,
            semanticDescription = "Approximate line count derived from OCR layout blocks",
            structureTag = "scalar.long",
        ),
        KEY_RASTER_WIDTH to EntityAttributeSpec(
            required = true,
            semanticDescription = "Raster width in pixels at encode time",
            structureTag = "scalar.long",
        ),
        KEY_RASTER_HEIGHT to EntityAttributeSpec(
            required = true,
            semanticDescription = "Raster height in pixels at encode time",
            structureTag = "scalar.long",
        ),
        KEY_RASTER_BYTE_LENGTH to EntityAttributeSpec(
            required = true,
            semanticDescription = "Encoded raster payload length in bytes (PNG/JPEG/WebP per device)",
            structureTag = "scalar.long",
        ),
        KEY_SENTIMENT_SCORE to EntityAttributeSpec(
            required = true,
            semanticDescription = "Sentiment polarity from TensorFlow Lite (-1 negative .. +1 positive); 0 when placeholder",
            structureTag = "scalar.double",
        ),
        KEY_SENTIMENT_PLACEHOLDER to EntityAttributeSpec(
            required = true,
            semanticDescription = "1 if TensorFlow Lite sentiment used a placeholder path; 0 if a model run was attempted",
            structureTag = "scalar.long",
        ),
        DistributedStoragePayloadHints.SCREENSHOT_DEFLATED_RASTER_BASE64 to EntityAttributeSpec(
            required = false,
            semanticDescription = "Zlib-deflated encoded raster bytes (Base64); omitted when identical to prior frame hash",
            structureTag = "media.base64.zlib",
        ),
        DistributedStoragePayloadHints.SCREENSHOT_DEFLATED_SHA256_HEX to EntityAttributeSpec(
            required = false,
            semanticDescription = "SHA-256 (hex) of deflated raster bytes; dedupe key for Storage + local sink",
            structureTag = "integrity.sha256.hex",
        ),
    ),
    payloadTypes = mapOf(
        KEY_OCR_FULL_TEXT to EntityTypeSpec("screenshot.OcrFullText", "kotlin.String", "1"),
        KEY_OCR_LINE_COUNT to EntityTypeSpec("screenshot.OcrLineCount", "kotlin.Long", "1"),
        KEY_RASTER_WIDTH to EntityTypeSpec("screenshot.RasterWidthPx", "kotlin.Long", "1"),
        KEY_RASTER_HEIGHT to EntityTypeSpec("screenshot.RasterHeightPx", "kotlin.Long", "1"),
        KEY_RASTER_BYTE_LENGTH to EntityTypeSpec("screenshot.RasterByteLength", "kotlin.Long", "1"),
        KEY_SENTIMENT_SCORE to EntityTypeSpec("screenshot.SentimentScore", "kotlin.Double", "1"),
        KEY_SENTIMENT_PLACEHOLDER to EntityTypeSpec("screenshot.SentimentPlaceholder", "kotlin.Long", "1"),
        DistributedStoragePayloadHints.SCREENSHOT_DEFLATED_RASTER_BASE64 to EntityTypeSpec(
            "screenshot.DeflatedRasterB64",
            "kotlin.String",
            "1",
        ),
        DistributedStoragePayloadHints.SCREENSHOT_DEFLATED_SHA256_HEX to EntityTypeSpec(
            "screenshot.DeflatedRasterSha256",
            "kotlin.String",
            "1",
        ),
    ),
    payloadValueRanges = mapOf(
        KEY_OCR_FULL_TEXT to UnboundedEntityValueRange("screenshot-ocr-text-unbounded"),
        KEY_OCR_LINE_COUNT to NumericEntityValueRange(0.0, 100_000.0, null),
        KEY_RASTER_WIDTH to NumericEntityValueRange(1.0, 32_768.0, null),
        KEY_RASTER_HEIGHT to NumericEntityValueRange(1.0, 32_768.0, null),
        KEY_RASTER_BYTE_LENGTH to NumericEntityValueRange(1.0, 256_000_000.0, null),
        KEY_SENTIMENT_SCORE to NumericEntityValueRange(-1.0, 1.0, null),
        KEY_SENTIMENT_PLACEHOLDER to NumericEntityValueRange(0.0, 1.0, 1.0),
        DistributedStoragePayloadHints.SCREENSHOT_DEFLATED_RASTER_BASE64 to UnboundedEntityValueRange(
            "screenshot-deflated-raster-b64",
        ),
        DistributedStoragePayloadHints.SCREENSHOT_DEFLATED_SHA256_HEX to UnboundedEntityValueRange(
            "screenshot-deflated-raster-sha256-hex",
        ),
    ),
    relationships = listOf(
        EntityRelationship(
            subjectAttributeKey = KEY_OCR_FULL_TEXT,
            predicateToken = "informs",
            objectAttributeKey = KEY_SENTIMENT_SCORE,
            bidirectional = false,
            cardinalityHint = "1:1",
        ),
    ),
)

/**
 * Gradle module `:modules:screenshot` — [BaseAdapter] with ML Kit OCR and TensorFlow Lite sentiment (optional asset model).
 */
class ScreenshotAdapter(
    adapterId: String,
    private val captureSessionId: String,
    private val producerNodeId: String,
    private val appContext: Context,
    private val sourceLabel: String = "android:Bitmap.PixelCopy",
    private val acquisitionMethodLabel: String = "MLKit.textRecognition+TFLite.sentiment",
    private val ufsEnvelopeVersion: String = UfsEnvelopeVersions.V1,
    private val sentimentAssetPath: String = "screenshot_sentiment.tflite",
    private val dataEntity: DataEntity = buildScreenshotPayloadDataEntity(),
) : BaseAdapter(adapterId = adapterId, modality = ModalityKind.SCREENSHOT) {

    private val textRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private val sentimentInterpreter: Interpreter? by lazy {
        runCatching {
            appContext.assets.open(sentimentAssetPath).use { stream ->
                val bytes = stream.readBytes()
                val bb = ByteBuffer.allocateDirect(bytes.size)
                bb.order(ByteOrder.nativeOrder())
                bb.put(bytes)
                bb.rewind()
                Interpreter(bb)
            }
        }.getOrNull()
    }

    private val ocrMutex = Mutex()
    private val tfliteMutex = Mutex()
    private val rasterDedupeMutex = Mutex()
    private var lastRasterPlainSha256Hex: String? = null

    override suspend fun adaptRaw(raw: RawModalityFrame): UnifiedDataPoint =
        when (raw) {
            is ScreenshotRasterRawFrame -> adaptRaster(raw)
            is ScreenshotRawCaptureFrame -> adaptLegacyPlaceholder(raw)
            else -> error("unsupported RawModalityFrame for screenshot: ${raw::class.simpleName}")
        }

    private suspend fun adaptRaster(raw: ScreenshotRasterRawFrame): UnifiedDataPoint {
        val bitmap = withContext(Dispatchers.Default) {
            BitmapFactory.decodeByteArray(raw.encodedBytes, 0, raw.encodedBytes.size)
        }
        if (bitmap == null) {
            return adaptDecodeFailure(raw)
        }
        try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val visionText = ocrMutex.withLock {
                textRecognizer.process(inputImage).await()
            }
            val fullText = visionText.text
            val blockLines = visionText.textBlocks.sumOf { it.lines.size }
            val newlineLines = if (fullText.isEmpty()) 0 else fullText.lines().size
            val lineCount = max(0, max(blockLines, newlineLines)).toLong()
            PipelineDiagnosticsRegistry.emit(
                logTag = PipelineLogTags.ADAPTER,
                module = ModalityKind.SCREENSHOT,
                stage = "adapter_ocr",
                dataType = "ocr_text",
                detail = "[ADAPTER][SCREENSHOT_MODULE] OCR extracted text length=${fullText.length} lineCount=$lineCount " +
                    "timestamp=${raw.capturedAtEpochMillis}",
            )
            val (sentimentScore, sentimentPlaceholder) = tfliteMutex.withLock {
                runSentimentWithTflite(fullText)
            }
            PipelineDiagnosticsRegistry.emit(
                logTag = PipelineLogTags.ADAPTER,
                module = ModalityKind.SCREENSHOT,
                stage = "adapter_sentiment",
                dataType = "sentiment_score",
                detail = "[ADAPTER][SCREENSHOT_MODULE] Sentiment score=$sentimentScore placeholder=$sentimentPlaceholder " +
                    "timestamp=${raw.capturedAtEpochMillis}",
            )
            return buildPoint(
                raw = raw,
                ocrFullText = fullText,
                ocrLineCount = lineCount,
                rasterWidth = raw.widthPx.toLong(),
                rasterHeight = raw.heightPx.toLong(),
                rasterByteLength = raw.encodedBytes.size.toLong(),
                sentimentScore = sentimentScore,
                sentimentPlaceholder = sentimentPlaceholder,
                provenanceHop = "screenshot.adapt.raster",
                provenanceNote = "mlkit=text-recognition;tflitePlaceholder=$sentimentPlaceholder",
                rasterEncodedBytes = raw.encodedBytes,
            )
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    private suspend fun adaptDecodeFailure(raw: ScreenshotRasterRawFrame): UnifiedDataPoint =
        buildPoint(
            raw = raw,
            ocrFullText = "",
            ocrLineCount = 0L,
            rasterWidth = raw.widthPx.toLong(),
            rasterHeight = raw.heightPx.toLong(),
            rasterByteLength = raw.encodedBytes.size.toLong(),
            sentimentScore = 0.0,
            sentimentPlaceholder = 1L,
            provenanceHop = "screenshot.adapt.decodeFailure",
            provenanceNote = "BitmapFactory.decodeByteArray returned null",
        )

    private suspend fun adaptLegacyPlaceholder(raw: ScreenshotRawCaptureFrame): UnifiedDataPoint =
        buildPoint(
            raw = raw,
            ocrFullText = "",
            ocrLineCount = 0L,
            rasterWidth = 1L,
            rasterHeight = 1L,
            rasterByteLength = raw.rasterByteLengthPlaceholder.coerceAtLeast(1L),
            sentimentScore = 0.0,
            sentimentPlaceholder = 1L,
            provenanceHop = "screenshot.adapt.legacyPlaceholder",
            provenanceNote = "rasterByteLengthPlaceholder=${raw.rasterByteLengthPlaceholder}",
        )

    private suspend fun buildPoint(
        raw: RawModalityFrame,
        ocrFullText: String,
        ocrLineCount: Long,
        rasterWidth: Long,
        rasterHeight: Long,
        rasterByteLength: Long,
        sentimentScore: Double,
        sentimentPlaceholder: Long,
        provenanceHop: String,
        provenanceNote: String,
        rasterEncodedBytes: ByteArray? = null,
    ): UnifiedDataPoint {
        val monoNanos = System.nanoTime()
        val correlationString = raw.correlationId.value
        val w = rasterWidth.coerceIn(1L, 32_768L)
        val h = rasterHeight.coerceIn(1L, 32_768L)
        val bl = rasterByteLength.coerceIn(1L, 256_000_000L)
        val data = linkedMapOf<String, Any>(
            UfsReservedDataKeys.CORRELATION_ID to correlationString,
            UfsReservedDataKeys.MONOTONIC_TIMESTAMP_NANOS to monoNanos,
            UfsReservedDataKeys.PROVENANCE_RECORDS to listOf(
                ProvenanceRecord(
                    hopName = provenanceHop,
                    componentId = adapterId,
                    recordedAtEpochMillis = System.currentTimeMillis(),
                    note = provenanceNote,
                ),
            ),
            KEY_OCR_FULL_TEXT to ocrFullText,
            KEY_OCR_LINE_COUNT to ocrLineCount.coerceIn(0L, 100_000L),
            KEY_RASTER_WIDTH to w,
            KEY_RASTER_HEIGHT to h,
            KEY_RASTER_BYTE_LENGTH to bl,
            KEY_SENTIMENT_SCORE to sentimentScore.coerceIn(-1.0, 1.0),
            KEY_SENTIMENT_PLACEHOLDER to sentimentPlaceholder.coerceIn(0L, 1L),
        )
        if (rasterEncodedBytes != null && rasterEncodedBytes.isNotEmpty()) {
            val plainSha = MessageDigest.getInstance("SHA-256").digest(rasterEncodedBytes).joinToString("") { b ->
                "%02x".format(b)
            }
            val shouldAttach = rasterDedupeMutex.withLock {
                if (plainSha == lastRasterPlainSha256Hex) {
                    false
                } else {
                    lastRasterPlainSha256Hex = plainSha
                    true
                }
            }
            if (shouldAttach) {
                val (b64, shaZ) = UfsBinaryPayloadCodec.deflatedBase64AndSha256Hex(rasterEncodedBytes)
                data[DistributedStoragePayloadHints.SCREENSHOT_DEFLATED_RASTER_BASE64] = b64
                data[DistributedStoragePayloadHints.SCREENSHOT_DEFLATED_SHA256_HEX] = shaZ
                val ratioPct = if (rasterEncodedBytes.isEmpty()) 0.0 else (b64.length * 100.0) / rasterEncodedBytes.size
                PipelineDiagnosticsRegistry.emit(
                    logTag = PipelineLogTags.ADAPTER,
                    module = ModalityKind.SCREENSHOT,
                    stage = "adapter_compression",
                    dataType = "deflated_raster",
                    detail = "[ADAPTER][SCREENSHOT_MODULE] Compressed raster base64Len=${b64.length} " +
                        "plainBytes=${rasterEncodedBytes.size} approxRatioPct=${"%.1f".format(ratioPct)} sha=$shaZ",
                )
            } else {
                PipelineDiagnosticsRegistry.emit(
                    logTag = PipelineLogTags.ADAPTER,
                    module = ModalityKind.SCREENSHOT,
                    stage = "adapter_deduplication",
                    dataType = "raster_sha256",
                    detail = "[ADAPTER][SCREENSHOT_MODULE] Raster dedupe suppressed duplicate plainSha256",
                )
            }
        }
        val metadata = DataDescription(
            source = sourceLabel,
            timestamp = Instant.ofEpochMilli(raw.capturedAtEpochMillis),
            acquisitionMethod = acquisitionMethodLabel,
            modality = ModalityKind.SCREENSHOT,
            captureSessionId = captureSessionId,
            producerNodeId = producerNodeId,
            producerAdapterId = adapterId,
            ufsEnvelopeVersion = ufsEnvelopeVersion,
        )
        PipelineDiagnosticsRegistry.emit(
            logTag = PipelineLogTags.ADAPTER,
            module = ModalityKind.SCREENSHOT,
            stage = "adapter_metadata_attachment",
            dataType = "screenshot_ufs",
            detail = "[ADAPTER][SCREENSHOT_MODULE] metadata={ ${PipelineDiagnosticsFormat.dataDescription(metadata)} }",
        )
        return UnifiedDataPoint(
            data = data,
            metadata = metadata,
            schema = dataEntity,
        )
    }

    /**
     * Best-effort TensorFlow Lite run when [sentimentAssetPath] loads; otherwise neutral score + placeholder flag.
     */
    private fun runSentimentWithTflite(ocrText: String): Pair<Double, Long> {
        val interpreter = sentimentInterpreter ?: return 0.0 to 1L
        return runCatching {
            val inTensor = interpreter.getInputTensor(0)
            val shape = inTensor.shape()
            if (shape.size != 2 || shape[0] != 1) {
                return@runCatching 0.0 to 1L
            }
            val featureDim = shape[1]
            val floats = FloatArray(featureDim) { i ->
                val idx = i % max(1, ocrText.length)
                val c = if (ocrText.isEmpty()) 0 else ocrText[idx].code
                ((c + i * 31) % 1025) / 1024f
            }
            val input = Array(1) { floats }
            val outShape = interpreter.getOutputTensor(0).shape()
            val outLen = outShape.fold(1, Int::times)
            val output = Array(1) { FloatArray(outLen) }
            interpreter.run(input, output)
            val rawScore = output[0].firstOrNull()?.toDouble() ?: 0.0
            val normalized = when {
                rawScore.isNaN() -> 0.0
                rawScore in -1.0..1.0 -> rawScore
                rawScore in 0.0..1.0 -> rawScore * 2.0 - 1.0
                else -> (rawScore / (1.0 + abs(rawScore))).coerceIn(-1.0, 1.0)
            }
            normalized to 0L
        }.getOrElse { 0.0 to 1L }
    }
}
