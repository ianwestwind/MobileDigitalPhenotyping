package edu.stanford.screenomics.core.storage

import edu.stanford.screenomics.core.debug.PipelineDiagnosticsRegistry
import edu.stanford.screenomics.core.debug.PipelineLogTags
import edu.stanford.screenomics.core.debug.toModuleLogTag
import edu.stanford.screenomics.core.unified.ModalityKind
import edu.stanford.screenomics.core.unified.UfsReservedDataKeys
import edu.stanford.screenomics.core.unified.UnifiedDataPoint
import java.util.Base64
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Default [DistributedStorageManager]: async fan-out on **[Dispatchers.IO]** (parallel to edge/Default workloads),
 * honoring [BatchUploadPolicyPlaceholder] gates.
 */
class DefaultDistributedStorageManager(
    private val localFileSink: ModalityLocalFileSink,
    private val firestoreBridge: FirestoreStructuredWriteBridge,
    private val cloudMediaBridge: CloudMediaStorageBridge,
    private val realtimeBridge: RealtimeStructuredWriteBridge,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val firestoreCollection: String = "unified_points",
    private val rtdbMotionStepsRoot: String = "motion_step_minute_buckets",
    private val rtdbStructuredMirrorRoot: String = "unified_structured_rt",
) : DistributedStorageManager {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + ioDispatcher)

    private val motionRtdbMutex = Mutex()
    private var motionBucketOpen: Long = Long.MIN_VALUE
    private var motionStepAtOpenBucket: Long = 0L
    private var motionPreviousFrameStepTotal: Long = 0L

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
        PipelineDiagnosticsRegistry.emit(
            logTag = PipelineLogTags.UPLOAD_QUEUE,
            module = modality,
            stage = "distributed_storage_gate",
            dataType = "${modality.name.lowercase()}_structured_enqueue",
            detail = "[UPLOAD_QUEUE] structuredWriteAllowed=$structuredOk correlationId=$correlation " +
                "docFieldCount=${doc.size}",
        )
        if (!structuredOk) {
            PipelineDiagnosticsRegistry.emit(
                logTag = PipelineLogTags.UPLOAD_QUEUE,
                module = modality,
                stage = "structured_write_skipped",
                dataType = "policy_gate",
                detail = "[UPLOAD_QUEUE] Structured Firestore/RTDB mirror skipped by batch policy correlationId=$correlation",
            )
        }
        if (structuredOk) {
            scope.launch {
                PipelineDiagnosticsRegistry.emit(
                    logTag = PipelineLogTags.FIREBASE,
                    module = modality,
                    stage = "firestore_enqueue",
                    dataType = "firestore_document",
                    detail = "[FIREBASE][Firestore] merge set collection=$firestoreCollection documentId=$correlation",
                )
                firestoreBridge.enqueueStructuredDocument(
                    collection = firestoreCollection,
                    documentId = correlation,
                    fields = doc,
                )
                if (BatchUploadPolicyPlaceholder.shouldMirrorFullStructuredDocumentToRealtime()) {
                    PipelineDiagnosticsRegistry.emit(
                        logTag = PipelineLogTags.FIREBASE,
                        module = modality,
                        stage = "realtime_mirror_enqueue",
                        dataType = "realtime_structured",
                        detail = "[FIREBASE][DB] mirror path=$rtdbStructuredMirrorRoot/$correlation",
                    )
                    realtimeBridge.enqueueStructured(
                        path = "$rtdbStructuredMirrorRoot/$correlation",
                        fields = doc,
                    )
                }
            }
        }

        routeLocalAndCloudMedia(point)

        if (point.metadata.modality == ModalityKind.MOTION) {
            maybeEmitMotionMinuteRtdb(point, doc)
        }
    }

    private fun routeLocalAndCloudMedia(point: UnifiedDataPoint) {
        val modality = point.metadata.modality
        val corr = point.data[UfsReservedDataKeys.CORRELATION_ID] as? String ?: return
        when (modality) {
            ModalityKind.AUDIO -> {
                val b64 = point.data[DistributedStoragePayloadHints.AUDIO_DEFLATED_PCM_BASE64] as? String
                val sha = point.data[DistributedStoragePayloadHints.AUDIO_DEFLATED_SHA256_HEX] as? String
                if (b64.isNullOrBlank() || sha.isNullOrBlank()) return
                if (!BatchUploadPolicyPlaceholder.shouldEnqueueMediaUpload("audio/$corr", b64.length)) {
                    PipelineDiagnosticsRegistry.emit(
                        logTag = PipelineLogTags.UPLOAD_QUEUE,
                        module = modality,
                        stage = "media_upload_skipped",
                        dataType = "policy_gate",
                        detail = "[UPLOAD_QUEUE] Audio media upload skipped by batch policy correlationId=$corr b64Len=${b64.length}",
                    )
                    return
                }
                val bytes = runCatching { Base64.getDecoder().decode(b64) }.getOrNull() ?: return
                scope.launch {
                    PipelineDiagnosticsRegistry.emit(
                        logTag = PipelineLogTags.LOCAL_STORAGE,
                        module = modality,
                        stage = "local_media_write",
                        dataType = "compressed_audio_zlib",
                        detail = "[LOCAL_STORAGE][${modality.toModuleLogTag()}] writing path=windows/$sha.bin.z bytes=${bytes.size}",
                    )
                    localFileSink.writeBytes(
                        modality = modality,
                        relativePath = "windows/$sha.bin.z",
                        bytes = bytes,
                        dedupeContentKey = sha,
                    )
                    PipelineDiagnosticsRegistry.emit(
                        logTag = PipelineLogTags.FIREBASE,
                        module = modality,
                        stage = "cloud_storage_enqueue",
                        dataType = "storage_media_audio",
                        detail = "[FIREBASE][STORAGE] upload path=${ModalityStorageDirectoryName.forModality(modality)}/$sha.bin.z " +
                            "bytes=${bytes.size} contentType=application/zlib",
                    )
                    cloudMediaBridge.enqueueMediaObject(
                        storagePath = "${ModalityStorageDirectoryName.forModality(modality)}/$sha.bin.z",
                        bytes = bytes,
                        contentType = "application/zlib",
                    )
                }
            }
            ModalityKind.SCREENSHOT -> {
                val b64 = point.data[DistributedStoragePayloadHints.SCREENSHOT_DEFLATED_RASTER_BASE64] as? String
                val sha = point.data[DistributedStoragePayloadHints.SCREENSHOT_DEFLATED_SHA256_HEX] as? String
                if (b64.isNullOrBlank() || sha.isNullOrBlank()) return
                if (!BatchUploadPolicyPlaceholder.shouldEnqueueMediaUpload("screenshot/$corr", b64.length)) {
                    PipelineDiagnosticsRegistry.emit(
                        logTag = PipelineLogTags.UPLOAD_QUEUE,
                        module = modality,
                        stage = "media_upload_skipped",
                        dataType = "policy_gate",
                        detail = "[UPLOAD_QUEUE] Screenshot media upload skipped by batch policy correlationId=$corr b64Len=${b64.length}",
                    )
                    return
                }
                val bytes = runCatching { Base64.getDecoder().decode(b64) }.getOrNull() ?: return
                scope.launch {
                    PipelineDiagnosticsRegistry.emit(
                        logTag = PipelineLogTags.LOCAL_STORAGE,
                        module = modality,
                        stage = "local_media_write",
                        dataType = "compressed_screenshot_zlib",
                        detail = "[LOCAL_STORAGE][${modality.toModuleLogTag()}] writing path=frames/$sha.png.z bytes=${bytes.size}",
                    )
                    localFileSink.writeBytes(
                        modality = modality,
                        relativePath = "frames/$sha.png.z",
                        bytes = bytes,
                        dedupeContentKey = sha,
                    )
                    PipelineDiagnosticsRegistry.emit(
                        logTag = PipelineLogTags.FIREBASE,
                        module = modality,
                        stage = "cloud_storage_enqueue",
                        dataType = "storage_media_screenshot",
                        detail = "[FIREBASE][STORAGE] upload path=${ModalityStorageDirectoryName.forModality(modality)}/$sha.png.z " +
                            "bytes=${bytes.size} contentType=application/zlib",
                    )
                    cloudMediaBridge.enqueueMediaObject(
                        storagePath = "${ModalityStorageDirectoryName.forModality(modality)}/$sha.png.z",
                        bytes = bytes,
                        contentType = "application/zlib",
                    )
                }
            }
            ModalityKind.GPS,
            ModalityKind.MOTION,
            -> Unit
        }
    }

    private data class MotionMinuteRtdbWrite(
        val path: String,
        val fields: Map<String, Any?>,
    )

    private suspend fun maybeEmitMotionMinuteRtdb(point: UnifiedDataPoint, doc: Map<String, Any?>) {
        val stepTotal = (point.data[DistributedStoragePayloadHints.MOTION_STEP_SESSION_TOTAL] as? Number)?.toLong() ?: return
        val bucket = point.metadata.timestamp.epochSecond / 60L
        val pending = motionRtdbMutex.withLock {
            if (motionBucketOpen == Long.MIN_VALUE) {
                motionBucketOpen = bucket
                motionStepAtOpenBucket = stepTotal
                motionPreviousFrameStepTotal = stepTotal
                return@withLock null
            }
            var out: MotionMinuteRtdbWrite? = null
            if (bucket != motionBucketOpen) {
                val completedBucket = motionBucketOpen
                val sumPastMinute =
                    (motionPreviousFrameStepTotal - motionStepAtOpenBucket).coerceAtLeast(0L)
                if (BatchUploadPolicyPlaceholder.shouldEnqueueRealtimeStructured("$rtdbMotionStepsRoot/$completedBucket")) {
                    val m = LinkedHashMap<String, Any?>(doc.size + 4)
                    m.putAll(doc)
                    m[DistributedStoragePayloadHints.MOTION_STEP_SUM_PAST_MINUTE] = sumPastMinute
                    m["motion.step.sessionTotalAtBucketClose"] = motionPreviousFrameStepTotal
                    val path = "$rtdbMotionStepsRoot/${point.metadata.captureSessionId}/$completedBucket"
                    out = MotionMinuteRtdbWrite(path = path, fields = m)
                }
                motionBucketOpen = bucket
                motionStepAtOpenBucket = motionPreviousFrameStepTotal
            }
            motionPreviousFrameStepTotal = stepTotal
            out
        }
        if (pending != null) {
            withContext(ioDispatcher) {
                PipelineDiagnosticsRegistry.emit(
                    logTag = PipelineLogTags.FIREBASE,
                    module = ModalityKind.MOTION,
                    stage = "realtime_motion_minute_bucket",
                    dataType = "realtime_step_aggregate",
                    detail = "[FIREBASE][DB] minute bucket path=${pending.path} fieldKeys=${pending.fields.keys.take(12)}",
                )
                realtimeBridge.enqueueStructured(pending.path, pending.fields)
            }
        }
    }

    override fun shutdown() {
        job.cancel()
    }
}
