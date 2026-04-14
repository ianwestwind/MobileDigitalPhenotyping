package edu.stanford.screenomics.core.storage

import edu.stanford.screenomics.core.debug.PipelineDiagnosticsRegistry
import edu.stanford.screenomics.core.debug.PipelineLogTags
import edu.stanford.screenomics.core.debug.toModuleLogTag
import edu.stanford.screenomics.core.unified.ModalityKind
import edu.stanford.screenomics.core.unified.UfsReservedDataKeys
import edu.stanford.screenomics.core.unified.UnifiedDataPoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Firestore/RTDB structured fan-out, Cloud Storage for audio/screenshot bytes, minimal per-modality local artifacts.
 * Local media (audio/screenshot binaries) and motion/gps JSON are removed after successful cloud/Firestore transfer.
 */
class DefaultDistributedStorageManager(
    private val localFileSink: ModalityLocalFileSink,
    private val firestoreBridge: FirestoreStructuredWriteBridge,
    private val cloudMediaBridge: CloudMediaStorageBridge,
    private val realtimeBridge: RealtimeStructuredWriteBridge,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val firestoreCollection: String = "unified_points",
    private val rtdbStructuredMirrorRoot: String = "unified_structured_rt",
) : DistributedStorageManager {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + ioDispatcher)

    override suspend fun onUnifiedPointCommitted(point: UnifiedDataPoint) {
        val modality = point.metadata.modality
        val doc = UnifiedDataPointPersistenceCodec.toStructuredMap(point)
        val correlation = point.data[UfsReservedDataKeys.CORRELATION_ID] as? String ?: "unknown"
        PipelineDiagnosticsRegistry.emit(
            logTag = PipelineLogTags.UPLOAD_QUEUE,
            module = modality,
            stage = "upload_trigger",
            dataType = "${modality.name.lowercase()}_committed_point",
            detail = "[UPLOAD_QUEUE] Upload pipeline triggered for correlationId=$correlation modality=$modality",
        )
        val structuredOk = BatchUploadPolicyPlaceholder.shouldEnqueueStructuredWrite(correlation)
        val epochMs = point.metadata.timestamp.toEpochMilli()
        val stamp = StorageArtifactFilename.stampUtc(epochMs)

        if (modality == ModalityKind.MOTION || modality == ModalityKind.GPS) {
            val motionRel = if (modality == ModalityKind.MOTION) "motion_$stamp.json" else null
            val gpsRel = if (modality == ModalityKind.GPS) "gps_$stamp.json" else null
            scope.launch {
                withContext(ioDispatcher) {
                    if (motionRel != null) {
                        val json = buildMotionLocalJson(point)
                        localFileSink.writeBytes(
                            modality = ModalityKind.MOTION,
                            relativePath = motionRel,
                            bytes = json.toByteArray(Charsets.UTF_8),
                            dedupeContentKey = null,
                        )
                    }
                    if (gpsRel != null) {
                        val json = buildGpsLocalJson(point)
                        localFileSink.writeBytes(
                            modality = ModalityKind.GPS,
                            relativePath = gpsRel,
                            bytes = json.toByteArray(Charsets.UTF_8),
                            dedupeContentKey = null,
                        )
                    }
                }
                if (structuredOk) {
                    val skipMotionCloud =
                        modality == ModalityKind.MOTION && BatchUploadPolicyPlaceholder.pauseMotionFirestoreUpload
                    if (skipMotionCloud) {
                        PipelineDiagnosticsRegistry.emit(
                            logTag = PipelineLogTags.UPLOAD_QUEUE,
                            module = ModalityKind.MOTION,
                            stage = "firestore_skipped_motion_pause",
                            dataType = "structured_write",
                            detail = "[UPLOAD_QUEUE] Firestore/RTDB structured upload skipped for motion " +
                                "(BatchUploadPolicyPlaceholder.pauseMotionFirestoreUpload=true) correlationId=$correlation",
                        )
                    }
                    val firestoreOk = if (skipMotionCloud) {
                        false
                    } else {
                        runCatching {
                            firestoreBridge.enqueueStructuredDocument(
                                collection = firestoreCollection,
                                documentId = correlation,
                                fields = doc,
                            )
                        }.isSuccess
                    }
                    if (firestoreOk) {
                        withContext(ioDispatcher) {
                            if (motionRel != null) {
                                localFileSink.deleteFile(ModalityKind.MOTION, motionRel)
                            }
                            if (gpsRel != null) {
                                localFileSink.deleteFile(ModalityKind.GPS, gpsRel)
                            }
                        }
                    }
                    if (firestoreOk && BatchUploadPolicyPlaceholder.shouldMirrorFullStructuredDocumentToRealtime()) {
                        runCatching {
                            realtimeBridge.enqueueStructured(
                                path = "$rtdbStructuredMirrorRoot/$correlation",
                                fields = doc,
                            )
                        }
                    }
                }
            }
        } else if (structuredOk) {
            scope.launch {
                val firestoreOk = runCatching {
                    firestoreBridge.enqueueStructuredDocument(
                        collection = firestoreCollection,
                        documentId = correlation,
                        fields = doc,
                    )
                }.isSuccess
                if (firestoreOk && BatchUploadPolicyPlaceholder.shouldMirrorFullStructuredDocumentToRealtime()) {
                    runCatching {
                        realtimeBridge.enqueueStructured(
                            path = "$rtdbStructuredMirrorRoot/$correlation",
                            fields = doc,
                        )
                    }
                }
            }
        }

        routeAudioScreenshotMedia(point, stamp)
    }

    private fun routeAudioScreenshotMedia(point: UnifiedDataPoint, stamp: String) {
        val modality = point.metadata.modality
        val corr = point.data[UfsReservedDataKeys.CORRELATION_ID] as? String ?: return
        when (modality) {
            ModalityKind.AUDIO -> {
                val rel = "audio_$stamp.bin"
                scope.launch {
                    val bytes = localFileSink.readBytes(ModalityKind.AUDIO, rel) ?: return@launch
                    if (!BatchUploadPolicyPlaceholder.shouldEnqueueMediaUpload("audio/$corr", bytes.size)) return@launch
                    val cloudPath = "${ModalityStorageDirectoryName.forModality(ModalityKind.AUDIO)}/$rel"
                    val url = cloudMediaBridge.enqueueMediaObject(cloudPath, bytes, "application/zlib")
                    if (url != null) {
                        localFileSink.deleteFile(ModalityKind.AUDIO, rel)
                    }
                }
            }
            ModalityKind.SCREENSHOT -> {
                val rel = "screenshot_$stamp.bin"
                scope.launch {
                    val bytes = localFileSink.readBytes(ModalityKind.SCREENSHOT, rel) ?: return@launch
                    if (!BatchUploadPolicyPlaceholder.shouldEnqueueMediaUpload("screenshot/$corr", bytes.size)) return@launch
                    val cloudPath = "${ModalityStorageDirectoryName.forModality(ModalityKind.SCREENSHOT)}/$rel"
                    val url = cloudMediaBridge.enqueueMediaObject(cloudPath, bytes, "application/octet-stream")
                    if (url != null) {
                        localFileSink.deleteFile(ModalityKind.SCREENSHOT, rel)
                    }
                }
            }
            else -> Unit
        }
    }

    private fun buildMotionLocalJson(p: UnifiedDataPoint): String {
        val d = p.data
        fun num(key: String): Double = (d[key] as? Number)?.toDouble() ?: Double.NaN
        fun long(key: String): Long = (d[key] as? Number)?.toLong() ?: -1L
        val ts = p.metadata.timestamp.toEpochMilli()
        val m = p.metadata
        return (
            "{\"motion.imu.accel.xMs2\":${num("motion.imu.accel.xMs2")}," +
                "\"motion.imu.accel.yMs2\":${num("motion.imu.accel.yMs2")}," +
                "\"motion.imu.accel.zMs2\":${num("motion.imu.accel.zMs2")}," +
                "\"motion.imu.gyro.xRadS\":${num("motion.imu.gyro.xRadS")}," +
                "\"motion.imu.gyro.yRadS\":${num("motion.imu.gyro.yRadS")}," +
                "\"motion.imu.gyro.zRadS\":${num("motion.imu.gyro.zRadS")}," +
                "\"motion.step.sessionTotal\":${long("motion.step.sessionTotal")}," +
                "\"timestampEpochMillis\":$ts," +
                "\"metadata\":{" +
                "\"source\":\"${jsonEscape(m.source)}\"," +
                "\"acquisitionMethod\":\"${jsonEscape(m.acquisitionMethod)}\"," +
                "\"producerAdapterId\":\"${jsonEscape(m.producerAdapterId)}\"," +
                "\"producerNodeId\":\"${jsonEscape(m.producerNodeId)}\"" +
                "}," +
                "\"captureSessionId\":\"${jsonEscape(m.captureSessionId)}\"," +
                "\"correlationId\":\"${jsonEscape(d[UfsReservedDataKeys.CORRELATION_ID] as? String ?: "")}\"}"
            )
    }

    private fun buildGpsLocalJson(p: UnifiedDataPoint): String {
        val d = p.data
        fun num(key: String): Double = (d[key] as? Number)?.toDouble() ?: Double.NaN
        val ts = p.metadata.timestamp.toEpochMilli()
        val m = p.metadata
        return (
            "{\"gps.fix.latitudeDegrees\":${num("gps.fix.latitudeDegrees")}," +
                "\"gps.fix.longitudeDegrees\":${num("gps.fix.longitudeDegrees")}," +
                "\"gps.weather.sunScore0To10\":${num("gps.weather.sunScore0To10")}," +
                "\"timestampEpochMillis\":$ts," +
                "\"metadata\":{" +
                "\"source\":\"${jsonEscape(m.source)}\"," +
                "\"acquisitionMethod\":\"${jsonEscape(m.acquisitionMethod)}\"," +
                "\"producerAdapterId\":\"${jsonEscape(m.producerAdapterId)}\"," +
                "\"producerNodeId\":\"${jsonEscape(m.producerNodeId)}\"" +
                "}," +
                "\"captureSessionId\":\"${jsonEscape(m.captureSessionId)}\"," +
                "\"correlationId\":\"${jsonEscape(d[UfsReservedDataKeys.CORRELATION_ID] as? String ?: "")}\"}"
            )
    }

    private fun jsonEscape(s: String): String = buildString {
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(if (c.code < 32) "\\u%04x".format(c.code) else c)
            }
        }
    }

    override fun shutdown() {
        job.cancel()
    }
}
