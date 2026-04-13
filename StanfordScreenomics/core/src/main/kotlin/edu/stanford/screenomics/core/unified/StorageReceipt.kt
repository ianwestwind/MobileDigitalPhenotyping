package edu.stanford.screenomics.core.unified

/**
 * Placeholder receipt token returned by staging APIs in the data management layer.
 */
data class StorageReceipt(
    val receiptId: String,
    val modalityKind: ModalityKind,
)
