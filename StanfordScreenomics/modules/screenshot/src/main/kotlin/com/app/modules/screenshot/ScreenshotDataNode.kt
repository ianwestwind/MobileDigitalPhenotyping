package com.app.modules.screenshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.os.PowerManager
import edu.stanford.screenomics.core.collection.RawModalityFrame
import edu.stanford.screenomics.core.collection.ScreenshotRasterRawFrame
import edu.stanford.screenomics.core.module.template.BaseDataNode
import edu.stanford.screenomics.core.module.template.ModulePipelineDispatchers
import edu.stanford.screenomics.core.unified.CorrelationId
import edu.stanford.screenomics.core.unified.ModalityKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Gradle module `:modules:screenshot` — concrete [BaseDataNode] polling [frameSupplier] for [Bitmap] frames,
 * encoding them to bytes and emitting [ScreenshotRasterRawFrame] into the module pipeline.
 */
class ScreenshotDataNode(
    private val appContext: Context,
    override val nodeId: String,
    adapter: ScreenshotAdapter,
    cache: ScreenshotCache,
    pipelineDispatchers: ModulePipelineDispatchers = ModulePipelineDispatchers(),
    private val frameSupplier: suspend () -> Bitmap?,
    private val pollIntervalMs: Long = 5_000L,
    private val compressFormat: CompressFormat = CompressFormat.PNG,
    private val compressQuality: Int = 92,
) : BaseDataNode(adapter = adapter, cache = cache, dispatchers = pipelineDispatchers) {

    init {
        require(pollIntervalMs > 0L) { "pollIntervalMs must be positive" }
        require(compressQuality in 0..100) { "compressQuality must be 0..100" }
    }

    private val rawIngress = MutableSharedFlow<RawModalityFrame>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val sharedRaw: Flow<RawModalityFrame> = rawIngress.asSharedFlow()

    private var ingressJob: Job? = null

    private val powerManager: PowerManager =
        appContext.getSystemService(Context.POWER_SERVICE) as PowerManager

    override fun modalityKind(): ModalityKind = ModalityKind.SCREENSHOT

    /**
     * Display density for the app window hosting capture (informational; UFS payload lives on [observeUnifiedOutputs]).
     */
    fun displayDensityDpi(): Int = appContext.resources.displayMetrics.densityDpi

    override fun observeRawFrames(): Flow<RawModalityFrame> = sharedRaw

    override suspend fun onActivate(collectionScope: CoroutineScope) {
        ingressJob?.cancel()
        ingressJob = collectionScope.launch(Dispatchers.Default) {
            while (isActive) {
                if (!powerManager.isInteractive) {
                    delay(pollIntervalMs)
                    continue
                }
                val bitmap = frameSupplier()
                if (bitmap == null) {
                    delay(pollIntervalMs)
                    continue
                }
                if (bitmap.isRecycled) {
                    delay(pollIntervalMs)
                    continue
                }
                val encoded = encodeBitmap(bitmap)
                val width = bitmap.width
                val height = bitmap.height
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
                if (encoded.isEmpty()) {
                    delay(pollIntervalMs)
                    continue
                }
                rawIngress.emit(
                    ScreenshotRasterRawFrame(
                        correlationId = CorrelationId("screenshot-${UUID.randomUUID()}"),
                        capturedAtEpochMillis = System.currentTimeMillis(),
                        widthPx = width,
                        heightPx = height,
                        compressFormatAndroid = compressFormat.ordinal,
                        encodedBytes = encoded,
                    ),
                )
                delay(pollIntervalMs)
            }
        }
    }

    private suspend fun encodeBitmap(bitmap: Bitmap): ByteArray =
        withContext(Dispatchers.Default) {
            ByteArrayOutputStream().use { bos ->
                bitmap.compress(compressFormat, compressQuality, bos)
                bos.toByteArray()
            }
        }

    override suspend fun onDeactivate() {
        ingressJob?.cancel()
        ingressJob = null
    }
}
