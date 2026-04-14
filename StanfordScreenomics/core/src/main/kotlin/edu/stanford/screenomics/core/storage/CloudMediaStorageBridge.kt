package edu.stanford.screenomics.core.storage

/**
 * Firebase **Cloud Storage** uploads. Returns **download URL** on success, else null.
 */
interface CloudMediaStorageBridge {
    suspend fun enqueueMediaObject(storagePath: String, bytes: ByteArray, contentType: String): String?
}
