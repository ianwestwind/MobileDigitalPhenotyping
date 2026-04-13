package com.app.modules.gps

import android.content.Context
import edu.stanford.screenomics.core.collection.GpsLocationRawFrame
import edu.stanford.screenomics.core.collection.RawModalityFrame
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
import java.util.UUID

/**
 * Gradle module `:modules:gps` — concrete [BaseDataNode] polling [locationSupplier] and emitting [GpsLocationRawFrame]
 * for adapter-side Open-Meteo enrichment.
 */
class GpsDataNode(
    private val appContext: Context,
    override val nodeId: String,
    adapter: GpsAdapter,
    cache: GpsCache,
    pipelineDispatchers: ModulePipelineDispatchers = ModulePipelineDispatchers(),
    private val locationSupplier: suspend () -> LocationSnapshot?,
    private val pollIntervalMs: Long = 30_000L,
) : BaseDataNode(adapter = adapter, cache = cache, dispatchers = pipelineDispatchers) {

    init {
        require(pollIntervalMs > 0L) { "pollIntervalMs must be positive" }
        require(appContext.packageName.isNotBlank()) { "appContext must be a real Application/Activity Context" }
    }

    /**
     * Minimal fix snapshot from app-level [android.location.Location] or fused pipeline (no extra module types).
     */
    data class LocationSnapshot(
        val latitudeDegrees: Double,
        val longitudeDegrees: Double,
        val horizontalAccuracyMeters: Float?,
        val providerName: String,
    )

    private val rawIngress = MutableSharedFlow<RawModalityFrame>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val sharedRaw: Flow<RawModalityFrame> = rawIngress.asSharedFlow()

    private var ingressJob: Job? = null

    override fun modalityKind(): ModalityKind = ModalityKind.GPS

    override fun observeRawFrames(): Flow<RawModalityFrame> = sharedRaw

    override suspend fun onActivate(collectionScope: CoroutineScope) {
        ingressJob?.cancel()
        ingressJob = collectionScope.launch(Dispatchers.Default) {
            while (isActive) {
                val snap = locationSupplier()
                if (snap != null) {
                    rawIngress.emit(
                        GpsLocationRawFrame(
                            correlationId = CorrelationId("gps-${UUID.randomUUID()}"),
                            capturedAtEpochMillis = System.currentTimeMillis(),
                            latitudeDegrees = snap.latitudeDegrees,
                            longitudeDegrees = snap.longitudeDegrees,
                            horizontalAccuracyMeters = snap.horizontalAccuracyMeters,
                            providerName = snap.providerName,
                        ),
                    )
                }
                delay(pollIntervalMs)
            }
        }
    }

    override suspend fun onDeactivate() {
        ingressJob?.cancel()
        ingressJob = null
    }
}
