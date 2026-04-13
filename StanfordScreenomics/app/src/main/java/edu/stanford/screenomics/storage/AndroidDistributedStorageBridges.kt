package edu.stanford.screenomics.storage

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import edu.stanford.screenomics.core.debug.PipelineDiagnosticsRegistry
import edu.stanford.screenomics.core.debug.PipelineLogTags
import edu.stanford.screenomics.core.debug.toModuleLogTag
import edu.stanford.screenomics.core.storage.CloudMediaStorageBridge
import edu.stanford.screenomics.core.storage.FirestoreStructuredWriteBridge
import edu.stanford.screenomics.core.storage.ModalityLocalFileSink
import edu.stanford.screenomics.core.storage.ModalityStorageDirectoryName
import edu.stanford.screenomics.core.storage.RealtimeStructuredWriteBridge
import edu.stanford.screenomics.core.unified.ModalityKind
import java.io.File
import kotlinx.coroutines.tasks.await

private fun modalityFromStorageObjectPath(path: String): ModalityKind? = when {
    path.startsWith("${ModalityStorageDirectoryName.forModality(ModalityKind.AUDIO)}/") -> ModalityKind.AUDIO
    path.startsWith("${ModalityStorageDirectoryName.forModality(ModalityKind.SCREENSHOT)}/") -> ModalityKind.SCREENSHOT
    path.startsWith("${ModalityStorageDirectoryName.forModality(ModalityKind.GPS)}/") -> ModalityKind.GPS
    path.startsWith("${ModalityStorageDirectoryName.forModality(ModalityKind.MOTION)}/") -> ModalityKind.MOTION
    else -> null
}

class AndroidModalityLocalFileSink(
    private val appContext: Context,
) : ModalityLocalFileSink {

    override suspend fun writeBytes(
        modality: ModalityKind,
        relativePath: String,
        bytes: ByteArray,
        dedupeContentKey: String?,
    ): Boolean {
        val dirName = ModalityStorageDirectoryName.forModality(modality)
        val root = File(appContext.filesDir, dirName).apply { mkdirs() }
        val target = File(root, relativePath)
        target.parentFile?.mkdirs()
        if (dedupeContentKey != null && target.exists()) {
            PipelineDiagnosticsRegistry.emit(
                logTag = PipelineLogTags.LOCAL_STORAGE,
                module = modality,
                stage = "local_file_dedupe_hit",
                dataType = "filesystem_skip",
                detail = "[LOCAL_STORAGE][${modality.toModuleLogTag()}] Skipped write (dedupe): path=${target.absolutePath} key=$dedupeContentKey",
            )
            return true
        }
        PipelineDiagnosticsRegistry.emit(
            logTag = PipelineLogTags.LOCAL_STORAGE,
            module = modality,
            stage = "local_file_write_begin",
            dataType = "raw_or_compressed_bytes",
            detail = "[LOCAL_STORAGE][${modality.toModuleLogTag()}] Saved file: path=${target.absolutePath} bytes=${bytes.size}",
        )
        target.writeBytes(bytes)
        PipelineDiagnosticsRegistry.emit(
            logTag = PipelineLogTags.LOCAL_STORAGE,
            module = modality,
            stage = "local_file_write_complete",
            dataType = "raw_or_compressed_bytes",
            detail = "[LOCAL_STORAGE][${modality.toModuleLogTag()}] Write complete path=${target.absolutePath}",
        )
        return true
    }
}

class AndroidFirestoreStructuredWriteBridge(
    private val appContext: Context,
) : FirestoreStructuredWriteBridge {

    override suspend fun enqueueStructuredDocument(
        collection: String,
        documentId: String,
        fields: Map<String, Any?>,
    ) {
        if (FirebaseApp.getApps(appContext).isEmpty()) return
        FirebaseAnonymousAuth.ensureSignedIn()
        runCatching {
            FirebaseFirestore.getInstance()
                .collection(collection)
                .document(documentId)
                .set(fields, SetOptions.merge())
                .await()
            PipelineDiagnosticsRegistry.emit(
                logTag = PipelineLogTags.FIREBASE,
                module = null,
                stage = "firestore_write_complete",
                dataType = "firestore_document",
                detail = "[FIREBASE][DB] Uploaded structured document collection=$collection documentId=$documentId fieldCount=${fields.size}",
            )
        }.onFailure { e ->
            Log.e("FIREBASE", "Firestore write failed collection=$collection id=$documentId", e)
            PipelineDiagnosticsRegistry.emit(
                logTag = PipelineLogTags.FIREBASE,
                module = null,
                stage = "firestore_write_failed",
                dataType = "firestore_document",
                detail = "[FIREBASE][DB] Firestore write failed collection=$collection documentId=$documentId error=${e.message}",
            )
        }
    }
}

class AndroidCloudMediaStorageBridge(
    private val appContext: Context,
    /**
     * Temporary override while `google-services.json` default bucket is not used.
     * Pass [USE_DEFAULT_FIREBASE_STORAGE_BUCKET] to revert to [FirebaseStorage.getInstance].
     */
    private val storageBucketGsUrl: String = TEMPORARY_SCREENOMICS_STORAGE_BUCKET,
) : CloudMediaStorageBridge {

    override suspend fun enqueueMediaObject(storagePath: String, bytes: ByteArray, contentType: String) {
        if (FirebaseApp.getApps(appContext).isEmpty()) return
        FirebaseAnonymousAuth.ensureSignedIn()
        val modality = modalityFromStorageObjectPath(storagePath)
        val moduleTag = modality?.toModuleLogTag() ?: "SYSTEM"
        runCatching {
            val storage = firebaseStorageForUpload()
            val ref = storage.reference.child(storagePath)
            val meta = StorageMetadata.Builder().setContentType(contentType).build()
            ref.putBytes(bytes, meta).await()
            PipelineDiagnosticsRegistry.emit(
                logTag = PipelineLogTags.FIREBASE,
                module = modality,
                stage = "cloud_storage_upload_complete",
                dataType = "storage_media_object",
                detail = "[FIREBASE][STORAGE] Uploaded media file: path=$storagePath bytes=${bytes.size} contentType=$contentType module=$moduleTag",
            )
        }.onFailure { e ->
            Log.e(
                "FIREBASE",
                "Storage upload failed path=$storagePath. 403=rules/auth (enable Anonymous auth + rules for authenticated users); " +
                    "404=bucket/project mismatch.",
                e,
            )
            PipelineDiagnosticsRegistry.emit(
                logTag = PipelineLogTags.FIREBASE,
                module = modality,
                stage = "cloud_storage_upload_failed",
                dataType = "storage_media_object",
                detail = "[FIREBASE][STORAGE] Upload failed path=$storagePath module=$moduleTag error=${e.message}",
            )
        }
    }

    private fun firebaseStorageForUpload(): FirebaseStorage {
        if (storageBucketGsUrl.isBlank() || storageBucketGsUrl == USE_DEFAULT_FIREBASE_STORAGE_BUCKET) {
            return FirebaseStorage.getInstance()
        }
        return FirebaseStorage.getInstance(FirebaseApp.getInstance(), storageBucketGsUrl)
    }

    companion object {
        /** Use with [AndroidCloudMediaStorageBridge] constructor to use the default bucket from `google-services.json`. */
        const val USE_DEFAULT_FIREBASE_STORAGE_BUCKET: String = ""

        /** Empty for public repo; set to your `gs://` bucket locally when needed. */
        const val TEMPORARY_SCREENOMICS_STORAGE_BUCKET: String = ""
    }
}

class AndroidRealtimeStructuredWriteBridge(
    private val appContext: Context,
) : RealtimeStructuredWriteBridge {

    override suspend fun enqueueStructured(path: String, fields: Map<String, Any?>) {
        if (FirebaseApp.getApps(appContext).isEmpty()) return
        FirebaseAnonymousAuth.ensureSignedIn()
        val modalityHint = when {
            path.contains("motion_step_minute_buckets") -> ModalityKind.MOTION
            path.contains("unified_structured_rt") -> null
            else -> null
        }
        runCatching {
            var ref = FirebaseDatabase.getInstance().reference
            for (segment in path.split('/').filter { it.isNotBlank() }) {
                ref = ref.child(segment)
            }
            ref.setValue(fields).await()
            PipelineDiagnosticsRegistry.emit(
                logTag = PipelineLogTags.FIREBASE,
                module = modalityHint,
                stage = "realtime_write_complete",
                dataType = "realtime_structured",
                detail = "[FIREBASE][DB] Realtime write complete path=$path fieldCount=${fields.size}",
            )
        }.onFailure { e ->
            Log.e("FIREBASE", "Realtime DB write failed path=$path", e)
            PipelineDiagnosticsRegistry.emit(
                logTag = PipelineLogTags.FIREBASE,
                module = modalityHint,
                stage = "realtime_write_failed",
                dataType = "realtime_structured",
                detail = "[FIREBASE][DB] Realtime write failed path=$path error=${e.message}",
            )
        }
    }
}
