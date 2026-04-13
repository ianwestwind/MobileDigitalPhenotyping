package edu.stanford.screenomics.core.storage

/**
 * Async Firebase **Cloud Storage** uploads for deduplicated compressed media blobs.
 */
fun interface CloudMediaStorageBridge {
    suspend fun enqueueMediaObject(storagePath: String, bytes: ByteArray, contentType: String)
}
