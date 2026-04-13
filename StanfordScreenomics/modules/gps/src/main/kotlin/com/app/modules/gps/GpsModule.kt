package com.app.modules.gps

import android.content.Context
import edu.stanford.screenomics.core.module.template.ModulePipelineDispatchers
import edu.stanford.screenomics.core.unified.UnifiedDataPoint

/**
 * Factory wiring [GpsDataNode] + [GpsAdapter] + [GpsCache] for the module template graph.
 */
object GpsModule {

    fun create(
        appContext: Context,
        nodeId: String,
        captureSessionId: String,
        locationSupplier: suspend () -> GpsDataNode.LocationSnapshot?,
        producerAdapterId: String = "default-gps-adapter",
        pollIntervalMs: Long = 60_000L,
        openMeteoBaseUrl: String = "https://api.open-meteo.com/",
        /** When non-blank, [gps.weather.sunScore0To10] is derived from OpenWeatherMap condition `id`; otherwise from Open-Meteo WMO `weather_code`. */
        openWeatherMapApiKey: String? = null,
        openWeatherMapBaseUrl: String = "https://api.openweathermap.org/data/2.5/",
        pipelineDispatchers: ModulePipelineDispatchers? = null,
        cache: GpsCache? = null,
        onAfterUnifiedPointCommittedOutsideLock: suspend (UnifiedDataPoint) -> Unit = {},
    ): GpsDataNode {
        val adapter = GpsAdapter(
            adapterId = producerAdapterId,
            captureSessionId = captureSessionId,
            producerNodeId = nodeId,
            openMeteoBaseUrl = openMeteoBaseUrl,
            openWeatherMapApiKey = openWeatherMapApiKey,
            openWeatherMapBaseUrl = openWeatherMapBaseUrl,
        )
        val resolvedCache = cache ?: GpsCache(onAfterUnifiedPointCommittedOutsideLock = onAfterUnifiedPointCommittedOutsideLock)
        return GpsDataNode(
            appContext = appContext.applicationContext,
            nodeId = nodeId,
            adapter = adapter,
            cache = resolvedCache,
            pipelineDispatchers = pipelineDispatchers ?: ModulePipelineDispatchers(),
            locationSupplier = locationSupplier,
            pollIntervalMs = pollIntervalMs,
        )
    }
}
