package edu.stanford.screenomics.core.storage

import edu.stanford.screenomics.core.unified.ModalityKind

/**
 * Layer-2 per-modality **local** byte persistence (isolated roots; no cross-modality paths).
 */
fun interface ModalityLocalFileSink {
    /**
     * @param relativePath path segments under the modality root (e.g. `windows/2026/clip.bin`).
     * @return true if bytes were durably written (or deduped skip treated as success).
     */
    suspend fun writeBytes(
        modality: ModalityKind,
        relativePath: String,
        bytes: ByteArray,
        dedupeContentKey: String?,
    ): Boolean
}
