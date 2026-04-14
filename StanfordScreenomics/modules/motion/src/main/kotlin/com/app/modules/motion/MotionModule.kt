package com.app.modules.motion

import android.content.Context
import android.hardware.SensorManager
import edu.stanford.screenomics.core.module.template.ModulePipelineDispatchers
import edu.stanford.screenomics.core.unified.UnifiedDataPoint

/**
 * Factory wiring [MotionDataNode] + [MotionAdapter] + [MotionCache] for the module template graph.
 */
object MotionModule {

    fun create(
        appContext: Context,
        nodeId: String,
        captureSessionId: String,
        producerAdapterId: String = "default-motion-adapter",
        samplingDelayUs: Int = SensorManager.SENSOR_DELAY_GAME,
        idleAccelDelayUs: Int = SensorManager.SENSOR_DELAY_NORMAL,
        stationaryHoldNs: Long = 5_000_000_000L,
        idleHeartbeatIntervalMs: Long = 55_000L,
        pipelineDispatchers: ModulePipelineDispatchers? = null,
        cache: MotionCache? = null,
        onAfterUnifiedPointCommittedOutsideLock: suspend (UnifiedDataPoint) -> Unit = {},
    ): MotionDataNode {
        val adapter = MotionAdapter(
            adapterId = producerAdapterId,
            captureSessionId = captureSessionId,
            producerNodeId = nodeId,
        )
        val resolvedCache = cache ?: MotionCache(onAfterUnifiedPointCommittedOutsideLock = onAfterUnifiedPointCommittedOutsideLock)
        return MotionDataNode(
            appContext = appContext.applicationContext,
            nodeId = nodeId,
            adapter = adapter,
            cache = resolvedCache,
            pipelineDispatchers = pipelineDispatchers ?: ModulePipelineDispatchers(),
            samplingDelayUs = samplingDelayUs,
            idleAccelDelayUs = idleAccelDelayUs,
            stationaryHoldNs = stationaryHoldNs,
            idleHeartbeatIntervalMs = idleHeartbeatIntervalMs,
        )
    }
}
