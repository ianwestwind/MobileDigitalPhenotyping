package edu.stanford.screenomics.core.storage

/**
 * Async Firestore writes for **structured** UFS documents (embedded metadata + schema + data).
 */
fun interface FirestoreStructuredWriteBridge {
    suspend fun enqueueStructuredDocument(collection: String, documentId: String, fields: Map<String, Any?>)
}
