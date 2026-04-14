package com.app.modules.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaRecorder
import edu.stanford.screenomics.core.module.template.ModulePipelineDispatchers
import edu.stanford.screenomics.core.storage.ModalityLocalFileSink
import edu.stanford.screenomics.core.unified.UnifiedDataPoint

/**
 * Factory wiring [AudioDataNode] + [AudioAdapter] + [AudioCache] against the shared module template graph.
 */
object AudioModule {

    fun create(
        appContext: Context,
        nodeId: String,
        captureSessionId: String,
        producerAdapterId: String = "default-audio-adapter",
        sampleRateHz: Int = 16_000,
        channelCount: Int = 1,
        audioEncoding: Int = AudioFormat.ENCODING_PCM_16BIT,
        audioSource: Int = MediaRecorder.AudioSource.DEFAULT,
        pipelineDispatchers: ModulePipelineDispatchers? = null,
        cache: AudioCache? = null,
        localFileSink: ModalityLocalFileSink? = null,
        onAfterUnifiedPointCommittedOutsideLock: suspend (UnifiedDataPoint) -> Unit = {},
    ): AudioDataNode {
        val adapter = AudioAdapter(
            adapterId = producerAdapterId,
            captureSessionId = captureSessionId,
            producerNodeId = nodeId,
            localFileSink = localFileSink,
        )
        val resolvedCache = cache ?: AudioCache(onAfterUnifiedPointCommittedOutsideLock = onAfterUnifiedPointCommittedOutsideLock)
        return AudioDataNode(
            appContext = appContext.applicationContext,
            nodeId = nodeId,
            adapter = adapter,
            cache = resolvedCache,
            pipelineDispatchers = pipelineDispatchers ?: ModulePipelineDispatchers(),
            sampleRateHz = sampleRateHz,
            channelCount = channelCount,
            audioEncoding = audioEncoding,
            audioSource = audioSource,
        )
    }
}
