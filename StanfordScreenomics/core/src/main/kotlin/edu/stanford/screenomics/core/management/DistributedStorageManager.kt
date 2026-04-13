package edu.stanford.screenomics.core.management

import edu.stanford.screenomics.core.unified.ModalityKind
import edu.stanford.screenomics.core.unified.StorageReceipt
import edu.stanford.screenomics.core.unified.UnifiedDataPoint

/**
 * Layer 2 — Data Management: stages fused outputs for off-device or partitioned persistence paths.
 */
interface DistributedStorageManager {
    suspend fun stage(points: List<UnifiedDataPoint>): List<StorageReceipt>

    suspend fun flush(modalityKind: ModalityKind)

    suspend fun acknowledge(receiptId: String)
}
