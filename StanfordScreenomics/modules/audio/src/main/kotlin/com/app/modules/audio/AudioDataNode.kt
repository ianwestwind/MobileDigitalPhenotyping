package com.app.modules.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import edu.stanford.screenomics.core.collection.AudioPcmBufferRawFrame
import edu.stanford.screenomics.core.collection.RawModalityFrame
import edu.stanford.screenomics.core.module.template.BaseDataNode
import edu.stanford.screenomics.core.module.template.ModulePipelineDispatchers
import edu.stanford.screenomics.core.unified.CorrelationId
import edu.stanford.screenomics.core.unified.ModalityKind
import kotlin.math.min
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
import java.util.UUID

private const val CYCLE_SPACING_MS: Long = 55_000L
private const val CAPTURE_WINDOW_MS: Long = 5_000L

/**
 * Gradle module `:modules:audio` — concrete [BaseDataNode]: [AudioRecord] ingress on [Dispatchers.IO],
 * emitting **[5 s]** cleaned PCM windows on a **~55 s** spacing cadence into the module pipeline (per Prompt 5D).
 */
class AudioDataNode(
    private val appContext: Context,
    override val nodeId: String,
    adapter: AudioAdapter,
    cache: AudioCache,
    pipelineDispatchers: ModulePipelineDispatchers = ModulePipelineDispatchers(),
    private val sampleRateHz: Int,
    private val channelCount: Int,
    private val audioEncoding: Int,
    private val audioSource: Int = MediaRecorder.AudioSource.DEFAULT,
) : BaseDataNode(adapter = adapter, cache = cache, dispatchers = pipelineDispatchers) {

    init {
        require(channelCount == 1 || channelCount == 2) {
            "channelCount must be 1 or 2 for this implementation (was $channelCount)"
        }
        require(sampleRateHz > 0) { "sampleRateHz must be positive" }
        require(audioEncoding == AudioFormat.ENCODING_PCM_16BIT) {
            "AudioDataNode cadence collector currently requires ENCODING_PCM_16BIT (was $audioEncoding)"
        }
    }

    private val channelConfig: Int = when (channelCount) {
        1 -> AudioFormat.CHANNEL_IN_MONO
        2 -> AudioFormat.CHANNEL_IN_STEREO
        else -> error("unreachable")
    }

    private val samplesPerCaptureWindow: Int = sampleRateHz * channelCount * (CAPTURE_WINDOW_MS / 1000L).toInt()

    private val rawIngress = MutableSharedFlow<RawModalityFrame>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val sharedRaw: Flow<RawModalityFrame> = rawIngress.asSharedFlow()

    private var ingressJob: Job? = null

    private enum class AudioIngressPhase {
        WAIT_SPACING,
        COLLECT_FIVE_SECOND_WINDOW,
    }

    override fun modalityKind(): ModalityKind = ModalityKind.AUDIO

    /**
     * Live capture parameters for this node (not UFS payload; callers use [observeUnifiedOutputs] for [edu.stanford.screenomics.core.unified.UnifiedDataPoint]).
     */
    fun currentCaptureFormat(): CaptureFormat =
        CaptureFormat(
            sampleRateHz = sampleRateHz,
            channelCount = channelCount,
            audioEncodingConstant = audioEncoding,
        )

    override fun observeRawFrames(): Flow<RawModalityFrame> = sharedRaw

    override suspend fun onActivate(collectionScope: CoroutineScope) {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("RECORD_AUDIO permission not granted")
        }
        ingressJob?.cancel()
        ingressJob = collectionScope.launch(Dispatchers.IO) {
            val minBuf = AudioRecord.getMinBufferSize(sampleRateHz, channelConfig, audioEncoding)
            require(minBuf > 0) { "AudioRecord.getMinBufferSize invalid: $minBuf" }
            val bufferBytes = minBuf * 4
            val record = AudioRecord.Builder()
                .setAudioSource(audioSource)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioEncoding)
                        .setSampleRate(sampleRateHz)
                        .setChannelMask(channelConfig)
                        .build(),
                )
                .setBufferSizeInBytes(bufferBytes)
                .build()

            try {
                record.startRecording()
                val shortsPerRead = bufferBytes / 2
                val readBuf = ShortArray(shortsPerRead)
                var phase = AudioIngressPhase.WAIT_SPACING
                var spacingAnchorElapsed = SystemClock.elapsedRealtime()
                var windowBuffer = ShortArray(samplesPerCaptureWindow)
                var windowFilled = 0
                var windowStartElapsed = 0L

                while (isActive && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val read = record.read(readBuf, 0, readBuf.size, AudioRecord.READ_NON_BLOCKING)
                    when {
                        read > 0 -> {
                            val copy = readBuf.copyOf(read)
                            when (phase) {
                                AudioIngressPhase.WAIT_SPACING -> {
                                    if (SystemClock.elapsedRealtime() - spacingAnchorElapsed >= CYCLE_SPACING_MS) {
                                        phase = AudioIngressPhase.COLLECT_FIVE_SECOND_WINDOW
                                        windowBuffer = ShortArray(samplesPerCaptureWindow)
                                        windowFilled = 0
                                        windowStartElapsed = SystemClock.elapsedRealtime()
                                    }
                                }
                                AudioIngressPhase.COLLECT_FIVE_SECOND_WINDOW -> {
                                    var src = 0
                                    while (src < copy.size && windowFilled < samplesPerCaptureWindow) {
                                        val take = min(copy.size - src, samplesPerCaptureWindow - windowFilled)
                                        System.arraycopy(copy, src, windowBuffer, windowFilled, take)
                                        windowFilled += take
                                        src += take
                                    }
                                    val elapsedWindow = SystemClock.elapsedRealtime() - windowStartElapsed
                                    val full = windowFilled >= samplesPerCaptureWindow
                                    val timedOut = elapsedWindow >= CAPTURE_WINDOW_MS && windowFilled > 0
                                    if (full || timedOut) {
                                        val emitLen = if (full) samplesPerCaptureWindow else windowFilled
                                        val emitted = windowBuffer.copyOf(emitLen)
                                        rawIngress.emit(
                                            AudioPcmBufferRawFrame(
                                                correlationId = CorrelationId("audio-${UUID.randomUUID()}"),
                                                capturedAtEpochMillis = System.currentTimeMillis(),
                                                sampleRateHz = sampleRateHz,
                                                channelCount = channelCount,
                                                audioEncoding = audioEncoding,
                                                samples = emitted,
                                            ),
                                        )
                                        phase = AudioIngressPhase.WAIT_SPACING
                                        spacingAnchorElapsed = SystemClock.elapsedRealtime()
                                    }
                                }
                            }
                        }
                        read == 0 -> delay(2L)
                        else -> delay(5L)
                    }
                }
            } finally {
                try {
                    record.stop()
                } catch (_: Throwable) {
                }
                record.release()
            }
        }
    }

    override suspend fun onDeactivate() {
        ingressJob?.cancel()
        ingressJob = null
    }

    data class CaptureFormat(
        val sampleRateHz: Int,
        val channelCount: Int,
        val audioEncodingConstant: Int,
    )
}
