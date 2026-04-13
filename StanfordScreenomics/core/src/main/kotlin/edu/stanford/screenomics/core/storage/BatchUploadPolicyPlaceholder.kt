package edu.stanford.screenomics.core.storage

import edu.stanford.screenomics.core.debug.PipelineDiagnosticsRegistry
import edu.stanford.screenomics.core.debug.PipelineLogTags

/**
 * Placeholder for future **batching / backoff / Wi-Fi-only / study-session** upload rules.
 * [DefaultDistributedStorageManager] enqueues work here before cloud I/O.
 */
object BatchUploadPolicyPlaceholder {

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
     * so **Realtime Database** carries the same embedded [edu.stanford.screenomics.core.unified.DataDescription] /
     * [edu.stanford.screenomics.core.unified.DataEntity] envelope as Firestore (batch/throttle rules TBD).
     */
    @Suppress("UNUSED_PARAMETER")
    fun shouldMirrorFullStructuredDocumentToRealtime(): Boolean = true
}
