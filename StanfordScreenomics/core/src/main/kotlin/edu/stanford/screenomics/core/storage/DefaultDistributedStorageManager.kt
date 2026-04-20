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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Firestore/RTDB structured fan-out, Cloud Storage for audio/screenshot bytes, minimal per-modality local artifacts.
 * Local media (audio/screenshot binaries), motion **IMU** JSON under `motion/imu/`, motion **step** JSON under
 * `motion/steps/`, and GPS JSON are removed after successful cloud/Firestore transfer when applicable.
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

    private val pendingMotionFirestoreDrainMutex = Mutex()

    companion object {
        /** Cloud Storage download URL merged under Firestore root `data` after media upload (audio/screenshot). */
        private const val FILE_LOCATION_DATA_KEY: String = "file.location"

        /** Queued structured Firestore payloads for motion IMU while [BatchUploadPolicyPlaceholder.pauseMotionFirestoreUpload]. */
        private const val PENDING_FIRESTORE_SUBDIR: String = "pending_firestore"

        /** Continuous accel/gyro samples — same tier as audio/screenshot bulk under the modality root. */
        private const val MOTION_IMU_LOCAL_SUBDIR: String = "imu"

        /** Minute step rollups — local mirror only; volatile cache also holds these without IMU keys. */
        private const val MOTION_STEPS_LOCAL_SUBDIR: String = "steps"

        private fun motionCorrelationFileSuffix(correlation: String): String {
            val safe = correlation.replace(Regex("[^a-zA-Z0-9_.-]"), "_").trim('_').take(200)
            return if (safe.isNotEmpty()) safe else "unknown"
        }

        private fun motionImuLocalRelativePath(stamp: String, correlation: String): String =
            "$MOTION_IMU_LOCAL_SUBDIR/motion_${stamp}_${motionCorrelationFileSuffix(correlation)}.json"

        private fun motionStepLocalRelativePath(stamp: String, correlation: String): String =
            "$MOTION_STEPS_LOCAL_SUBDIR/motion_${stamp}_${motionCorrelationFileSuffix(correlation)}.json"
    }

    override suspend fun onUnifiedPointCommitted(point: UnifiedDataPoint) {
        if (!BatchUploadPolicyPlaceholder.pauseMotionFirestoreUpload) {
            pendingMotionFirestoreDrainMutex.withLock {
                drainPendingMotionAccelGyroFirestoreDocuments()
            }
        }
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
            val motionRel = if (modality == ModalityKind.MOTION) {
                when {
                    MotionStructuredCloudUploadSelectors.motionPayloadHasAccelOrGyroData(point.data) ->
                        motionImuLocalRelativePath(stamp = stamp, correlation = correlation)
                    point.data.containsKey(MotionStructuredCloudUploadSelectors.DATA_KEY_STEP_SESSION) ->
                        motionStepLocalRelativePath(stamp = stamp, correlation = correlation)
                    else ->
                        "${MOTION_STEPS_LOCAL_SUBDIR}/motion_${stamp}_${motionCorrelationFileSuffix(correlation)}.json"
                }
            } else {
                null
            }
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
                    val skipMotionAccelGyroCloud = MotionStructuredCloudUploadSelectors.shouldPauseStructuredCloudForMotionPoint(point)
                    if (skipMotionAccelGyroCloud) {
                        PipelineDiagnosticsRegistry.emit(
                            logTag = PipelineLogTags.UPLOAD_QUEUE,
                            module = ModalityKind.MOTION,
                            stage = "firestore_skipped_motion_accel_gyro_pause",
                            dataType = "structured_write",
                            detail = "[UPLOAD_QUEUE] Firestore/RTDB structured upload skipped for motion accel/gyro " +
                                "(pauseMotionFirestoreUpload=true) acquisition=${point.metadata.acquisitionMethod} " +
                                "correlationId=$correlation",
                        )
                        withContext(ioDispatcher) {
                            val pendingRel = "$PENDING_FIRESTORE_SUBDIR/$correlation.json"
                            localFileSink.writeBytes(
                                modality = ModalityKind.MOTION,
                                relativePath = pendingRel,
                                bytes = StructuredFirestorePendingCodec.encodeToUtf8Bytes(doc),
                                dedupeContentKey = null,
                            )
                        }
                    }
                    val firestoreOk = if (skipMotionAccelGyroCloud) {
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
            if (modality == ModalityKind.AUDIO || modality == ModalityKind.SCREENSHOT) {
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
                    if (firestoreOk) {
                        uploadAudioOrScreenshotMediaAndMergeFileLocation(
                            stamp = stamp,
                            modality = modality,
                            correlation = correlation,
                        )
                    }
                }
            } else {
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
        }

        val mediaRouteDeferredToStructuredBranch =
            structuredOk && (modality == ModalityKind.AUDIO || modality == ModalityKind.SCREENSHOT)
        if (!mediaRouteDeferredToStructuredBranch) {
            routeAudioScreenshotMedia(point, stamp)
        }
    }

    private suspend fun uploadAudioOrScreenshotMediaAndMergeFileLocation(
        stamp: String,
        modality: ModalityKind,
        correlation: String,
    ) {
        when (modality) {
            ModalityKind.AUDIO -> {
                val rel = "audio_$stamp.bin"
                val bytes = withContext(ioDispatcher) {
                    localFileSink.readBytes(ModalityKind.AUDIO, rel)
                } ?: return
                if (!BatchUploadPolicyPlaceholder.shouldEnqueueMediaUpload("audio/$correlation", bytes.size)) return
                val cloudPath = "${ModalityStorageDirectoryName.forModality(ModalityKind.AUDIO)}/$rel"
                val url = cloudMediaBridge.enqueueMediaObject(cloudPath, bytes, "application/zlib")
                if (url != null) {
                    withContext(ioDispatcher) {
                        localFileSink.deleteFile(ModalityKind.AUDIO, rel)
                    }
                    runCatching {
                        firestoreBridge.mergeStructuredDocumentEncodedData(
                            collection = firestoreCollection,
                            documentId = correlation,
                            encodedDataEntries = mapOf(FILE_LOCATION_DATA_KEY to url),
                        )
                    }
                }
            }
            ModalityKind.SCREENSHOT -> {
                val rel = "screenshot_$stamp.png"
                val bytes = withContext(ioDispatcher) {
                    localFileSink.readBytes(ModalityKind.SCREENSHOT, rel)
                } ?: return
                if (!BatchUploadPolicyPlaceholder.shouldEnqueueMediaUpload("screenshot/$correlation", bytes.size)) return
                val cloudPath = "${ModalityStorageDirectoryName.forModality(ModalityKind.SCREENSHOT)}/$rel"
                val url = cloudMediaBridge.enqueueMediaObject(cloudPath, bytes, "image/png")
                if (url != null) {
                    withContext(ioDispatcher) {
                        localFileSink.deleteFile(ModalityKind.SCREENSHOT, rel)
                    }
                    runCatching {
                        firestoreBridge.mergeStructuredDocumentEncodedData(
                            collection = firestoreCollection,
                            documentId = correlation,
                            encodedDataEntries = mapOf(FILE_LOCATION_DATA_KEY to url),
                        )
                    }
                }
            }
            else -> Unit
        }
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
                val rel = "screenshot_$stamp.png"
                scope.launch {
                    val bytes = localFileSink.readBytes(ModalityKind.SCREENSHOT, rel) ?: return@launch
                    if (!BatchUploadPolicyPlaceholder.shouldEnqueueMediaUpload("screenshot/$corr", bytes.size)) return@launch
                    val cloudPath = "${ModalityStorageDirectoryName.forModality(ModalityKind.SCREENSHOT)}/$rel"
                    val url = cloudMediaBridge.enqueueMediaObject(cloudPath, bytes, "image/png")
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
        val ts = p.metadata.timestamp.toEpochMilli()
        val m = p.metadata
        val dataFields = buildString {
            var first = true
            for ((k, v) in d) {
                if (!first) append(',')
                first = false
                append('"').append(jsonEscape(k)).append("\":")
                append(jsonEncodeDataValue(v))
            }
        }
        return (
            "{$dataFields," +
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

    private fun jsonEncodeDataValue(v: Any): String = when (v) {
        is Number -> v.toString()
        is Boolean -> v.toString()
        is String -> "\"${jsonEscape(v)}\""
        else -> "\"${jsonEscape(v.toString())}\""
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

    /**
     * Uploads structured motion IMU documents queued under [PENDING_FIRESTORE_SUBDIR] while pause was enabled.
     * Invoked whenever [BatchUploadPolicyPlaceholder.pauseMotionFirestoreUpload] is false (typically on each commit).
     */
    private suspend fun drainPendingMotionAccelGyroFirestoreDocuments() {
        val paths = withContext(ioDispatcher) {
            localFileSink.listFilesUnderSubdirectory(ModalityKind.MOTION, PENDING_FIRESTORE_SUBDIR)
        }
        for (rel in paths) {
            if (!rel.endsWith(".json")) continue
            val bytes = withContext(ioDispatcher) {
                localFileSink.readBytes(ModalityKind.MOTION, rel)
            } ?: continue
            val fields = runCatching { StructuredFirestorePendingCodec.decodeFromUtf8Bytes(bytes) }.getOrNull() ?: continue
            @Suppress("UNCHECKED_CAST")
            val data = fields["data"] as? Map<String, Any?> ?: continue
            val docId = data[UfsReservedDataKeys.CORRELATION_ID] as? String ?: continue
            val fsOk = runCatching {
                firestoreBridge.enqueueStructuredDocument(
                    collection = firestoreCollection,
                    documentId = docId,
                    fields = fields,
                )
            }.isSuccess
            if (!fsOk) continue
            if (BatchUploadPolicyPlaceholder.shouldMirrorFullStructuredDocumentToRealtime()) {
                runCatching {
                    realtimeBridge.enqueueStructured(
                        path = "$rtdbStructuredMirrorRoot/$docId",
                        fields = fields,
                    )
                }
            }
            withContext(ioDispatcher) {
                localFileSink.deleteFile(ModalityKind.MOTION, rel)
            }
        }
    }

    override fun shutdown() {
        job.cancel()
    }
}
