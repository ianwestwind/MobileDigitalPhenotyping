package edu.stanford.screenomics.core.storage

/**
 * Async **Firebase Realtime Database** writes for structured payloads (e.g. motion step aggregates).
 * Full UFS embedding uses the same [UnifiedDataPointPersistenceCodec] map shape as Firestore.
 */
fun interface RealtimeStructuredWriteBridge {
    suspend fun enqueueStructured(path: String, fields: Map<String, Any?>)
}
