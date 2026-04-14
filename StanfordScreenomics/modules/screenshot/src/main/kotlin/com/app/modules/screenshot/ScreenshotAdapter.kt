package com.app.modules.screenshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Bitmap.CompressFormat
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
import edu.stanford.screenomics.core.storage.ModalityLocalFileSink
import edu.stanford.screenomics.core.storage.StorageArtifactFilename
import edu.stanford.screenomics.core.unified.DataDescription
import edu.stanford.screenomics.core.unified.ModalityKind
import edu.stanford.screenomics.core.unified.UfsEnvelopeVersions
import edu.stanford.screenomics.core.unified.UfsReservedDataKeys
import edu.stanford.screenomics.core.unified.UnifiedDataPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant

private const val KEY_SENTIMENT_SCORE: String = "screenshot.sentiment.score"
private const val PNG_MIME: String = "image/png"

/**
 * Screenshots: **Google ML Kit Text Recognition** on the raster, then **on-device TFLite** regression
 * (`screenshot_sentiment.tflite`) trained for a **1–10** sentiment score (higher = more positive).
 * If the model is missing or fails, falls back to the same lexicon heuristic used during training (1–10).
 */
class ScreenshotAdapter(
    adapterId: String,
    private val captureSessionId: String,
    private val producerNodeId: String,
    private val appContext: Context,
    private val localFileSink: ModalityLocalFileSink? = null,
    private val sourceLabel: String = "android:Bitmap.PixelCopy",
    private val acquisitionMethodLabel: String = "MLKit.textRecognition+TFLite.sentiment",
    private val ufsEnvelopeVersion: String = UfsEnvelopeVersions.V1,
    private val sentimentAssetPath: String = "screenshot_sentiment.tflite",
    /** Downscale factor applied to the stored/uploaded PNG. 1.0 preserves original pixels. */
    private val storageDownscaleFactor: Double = 0.5,
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
            val (sentimentScore, sentimentSource) = tfliteMutex.withLock {
                resolveSentiment(fullText)
            }
            PipelineDiagnosticsRegistry.emit(
                logTag = PipelineLogTags.ADAPTER,
                module = ModalityKind.SCREENSHOT,
                stage = "adapter_sentiment",
                dataType = "sentiment_score",
                detail = "[ADAPTER][SCREENSHOT_MODULE] Sentiment score=$sentimentScore source=$sentimentSource " +
                    "ocrLen=${fullText.length} timestamp=${raw.capturedAtEpochMillis}",
            )
            if (localFileSink != null && raw.encodedBytes.isNotEmpty()) {
                // Store/upload a downscaled PNG, but keep OCR on the original bitmap for quality.
                val storedPng = encodeStoredPng(bitmap, storageDownscaleFactor)
                if (storedPng.isNotEmpty()) {
                    val plainSha = sha256Hex(storedPng)
                val shouldWrite = rasterDedupeMutex.withLock {
                    if (plainSha == lastRasterPlainSha256Hex) {
                        false
                    } else {
                        lastRasterPlainSha256Hex = plainSha
                        true
                    }
                }
                if (shouldWrite) {
                    val stamp = StorageArtifactFilename.stampUtc(raw.capturedAtEpochMillis)
                    val rel = "screenshot_$stamp.png"
                    localFileSink.writeBytes(
                        modality = ModalityKind.SCREENSHOT,
                        relativePath = rel,
                        bytes = storedPng,
                        dedupeContentKey = null,
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
            }
            return buildPoint(
                raw = raw,
                sentimentScore = sentimentScore,
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
            sentimentScore = NEUTRAL_SENTIMENT_1_TO_10,
        )

    private suspend fun adaptLegacyPlaceholder(raw: ScreenshotRawCaptureFrame): UnifiedDataPoint =
        buildPoint(
            raw = raw,
            sentimentScore = NEUTRAL_SENTIMENT_1_TO_10,
        )

    private fun buildPoint(
        raw: RawModalityFrame,
        sentimentScore: Double,
    ): UnifiedDataPoint {
        val monoNanos = System.nanoTime()
        val correlationString = raw.correlationId.value
        val data = linkedMapOf<String, Any>(
            UfsReservedDataKeys.CORRELATION_ID to correlationString,
            UfsReservedDataKeys.MONOTONIC_TIMESTAMP_NANOS to monoNanos,
            KEY_SENTIMENT_SCORE to sentimentScore.coerceIn(1.0, 10.0),
        )
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
        return UnifiedDataPoint(data = data, metadata = metadata)
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { b -> "%02x".format(b) }
    }

    private fun encodeStoredPng(bitmap: Bitmap, downscaleFactor: Double): ByteArray {
        val f = downscaleFactor.coerceIn(0.05, 1.0)
        val b = if (f == 1.0) {
            bitmap
        } else {
            val w = (bitmap.width * f).toInt().coerceAtLeast(1)
            val h = (bitmap.height * f).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, w, h, true)
        }
        return try {
            ByteArrayOutputStream().use { bos ->
                b.compress(CompressFormat.PNG, /*ignored*/ 100, bos)
                bos.toByteArray()
            }
        } finally {
            if (b !== bitmap && !b.isRecycled) {
                b.recycle()
            }
        }
    }

    /**
     * Prefer bundled TFLite (1–10); if missing or inference fails, lexicon fallback (1–10).
     */
    private fun resolveSentiment(ocrText: String): Pair<Double, String> {
        val lex = ScreenshotLexiconSentiment.scoreOneToTen(ocrText)
        val interpreter = sentimentInterpreter
            ?: return lex to "lexicon_no_model"
        val fromModel = runTfliteModel(interpreter, ocrText)
        return if (fromModel != null) {
            fromModel to "tflite"
        } else {
            lex to "lexicon_fallback"
        }
    }

    /** Model outputs one sigmoid in [0, 1] → map to [1, 10]. */
    private fun runTfliteModel(interpreter: Interpreter, ocrText: String): Double? =
        runCatching {
            val inTensor = interpreter.getInputTensor(0)
            val shape = inTensor.shape()
            if (shape.size != 2 || shape[0] != 1 || shape[1] != ScreenshotSentimentTextEncoding.INPUT_DIM) {
                return@runCatching null
            }
            val floats = ScreenshotSentimentTextEncoding.encode(ocrText)
            val input = Array(1) { floats }
            val outShape = interpreter.getOutputTensor(0).shape()
            val outLen = outShape.fold(1, Int::times)
            val output = Array(1) { FloatArray(outLen) }
            interpreter.run(input, output)
            val raw = output[0].firstOrNull()?.toDouble() ?: return@runCatching null
            if (raw.isNaN()) return@runCatching null
            val sigmoid01 = raw.coerceIn(0.0, 1.0)
            (1.0 + sigmoid01 * 9.0).coerceIn(1.0, 10.0)
        }.getOrNull()

    private companion object {
        private const val NEUTRAL_SENTIMENT_1_TO_10: Double = 5.5
    }
}
