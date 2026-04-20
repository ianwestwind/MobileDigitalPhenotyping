package edu.stanford.screenomics.core.storage

/**
 * Async Firestore writes for **structured** UFS documents (embedded metadata + data).
 */
interface FirestoreStructuredWriteBridge {

    suspend fun enqueueStructuredDocument(collection: String, documentId: String, fields: Map<String, Any?>)

    /**
     * Deep-merge string keys under the document root field `data` (e.g. `file.location` after Cloud Storage upload).
     */
    suspend fun mergeStructuredDocumentEncodedData(
        collection: String,
        documentId: String,
        encodedDataEntries: Map<String, Any?>,
    )
}
