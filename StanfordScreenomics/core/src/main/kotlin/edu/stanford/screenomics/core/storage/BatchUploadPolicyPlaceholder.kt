package edu.stanford.screenomics.core.storage

import edu.stanford.screenomics.core.debug.PipelineDiagnosticsRegistry
import edu.stanford.screenomics.core.debug.PipelineLogTags
import edu.stanford.screenomics.core.unified.ModalityKind

/**
 * Placeholder for future **batching / backoff / Wi-Fi-only / study-session** upload rules.
 * [DefaultDistributedStorageManager] enqueues work here before cloud I/O.
 */
object BatchUploadPolicyPlaceholder {

    /**
     * When true, **accelerometer** and **gyroscope** [ModalityKind.MOTION] points only: skip structured Firestore
     * and Realtime full-document mirror for those rows; each skipped structured document is stored under
     * `files/motion/pending_firestore/<correlation>.json` until this flag becomes false, then flushed to Firestore/RTDB.
     * **Step/minute** motion points (`motion.step.minute_window`) still enqueue Firestore; tier‑2
     * `motion/steps/motion_*.json` is removed after a successful upload. IMU rows are written only under
     * `motion/imu/motion_*.json` (and `pending_firestore/` when paused). The 30‑minute volatile motion cache
     * retains **step aggregates only**; accel/gyro never enter that store.
     *
     * **Note:** The Firebase client may still deliver **older** writes that were already queued before a pause flip
     * (offline persistence / pending writes). Force-stop the app or clear app data to discard that queue when testing.
     */
    @Volatile
    var pauseMotionFirestoreUpload: Boolean = false

    private const val STRUCTURED_BATCH_LOG_SIZE: Int = 25
    private const val MEDIA_BATCH_LOG_SIZE: Int = 25

    private val structuredBatchBuffer = ArrayList<String>(STRUCTURED_BATCH_LOG_SIZE)
    private val mediaBatchBuffer = ArrayList<String>(MEDIA_BATCH_LOG_SIZE)

    @Synchronized
    @Suppress("UNUSED_PARAMETER")
    fun shouldEnqueueStructuredWrite(documentId: String): Boolean {
        structuredBatchBuffer.add(documentId)
        if (structuredBatchBuffer.size >= STRUCTURED_BATCH_LOG_SIZE) {
            PipelineDiagnosticsRegistry.emit(
                logTag = PipelineLogTags.UPLOAD_QUEUE,
                module = null,
                stage = "batch_created",
                dataType = "structured_writes",
                detail = "[UPLOAD_QUEUE] Batch created: size=${structuredBatchBuffer.size} items " +
                    "first=${structuredBatchBuffer.first()} last=${structuredBatchBuffer.last()}",
            )
            structuredBatchBuffer.clear()
        }
        return true
    }

    @Synchronized
    @Suppress("UNUSED_PARAMETER")
    fun shouldEnqueueMediaUpload(objectPath: String, byteSize: Int): Boolean {
        mediaBatchBuffer.add(objectPath)
        if (mediaBatchBuffer.size >= MEDIA_BATCH_LOG_SIZE) {
            PipelineDiagnosticsRegistry.emit(
                logTag = PipelineLogTags.UPLOAD_QUEUE,
                module = null,
                stage = "batch_created",
                dataType = "media_uploads",
                detail = "[UPLOAD_QUEUE] Batch created: size=${mediaBatchBuffer.size} media paths " +
                    "lastByteSize=$byteSize tail=${mediaBatchBuffer.last()}",
            )
            mediaBatchBuffer.clear()
        }
        return true
    }

    @Suppress("UNUSED_PARAMETER")
    fun shouldEnqueueRealtimeStructured(path: String): Boolean = true

    /**
     * When true, each structured UFS document is also mirrored under `unified_structured_rt/{correlationId}`
     * so **Realtime Database** carries the same embedded [edu.stanford.screenomics.core.unified.DataDescription]
     * and data map as Firestore (batch/throttle rules TBD).
     */
    @Suppress("UNUSED_PARAMETER")
    fun shouldMirrorFullStructuredDocumentToRealtime(): Boolean = true
}
