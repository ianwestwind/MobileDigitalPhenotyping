package com.app.modules.screenshot

import android.content.Context
import android.graphics.Bitmap
import edu.stanford.screenomics.core.module.template.ModulePipelineDispatchers
import edu.stanford.screenomics.core.unified.UnifiedDataPoint

/**
 * Factory wiring [ScreenshotDataNode] + [ScreenshotAdapter] + [ScreenshotCache] for the module template graph.
 */
object ScreenshotModule {

    fun create(
        appContext: Context,
        nodeId: String,
        captureSessionId: String,
        frameSupplier: suspend () -> Bitmap?,
        producerAdapterId: String = "default-screenshot-adapter",
        pollIntervalMs: Long = 5_000L,
        sentimentAssetPath: String = "screenshot_sentiment.tflite",
        pipelineDispatchers: ModulePipelineDispatchers? = null,
        cache: ScreenshotCache? = null,
        onAfterUnifiedPointCommittedOutsideLock: suspend (UnifiedDataPoint) -> Unit = {},
    ): ScreenshotDataNode {
        val adapter = ScreenshotAdapter(
            adapterId = producerAdapterId,
            captureSessionId = captureSessionId,
            producerNodeId = nodeId,
            appContext = appContext.applicationContext,
            sentimentAssetPath = sentimentAssetPath,
        )
        val resolvedCache = cache ?: ScreenshotCache(onAfterUnifiedPointCommittedOutsideLock = onAfterUnifiedPointCommittedOutsideLock)
        return ScreenshotDataNode(
            appContext = appContext.applicationContext,
            nodeId = nodeId,
            adapter = adapter,
            cache = resolvedCache,
            pipelineDispatchers = pipelineDispatchers ?: ModulePipelineDispatchers(),
            frameSupplier = frameSupplier,
            pollIntervalMs = pollIntervalMs,
        )
    }
}
