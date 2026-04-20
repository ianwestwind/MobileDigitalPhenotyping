package edu.stanford.screenomics.core.storage

import edu.stanford.screenomics.core.unified.ModalityKind

/**
 * Layer-2 per-modality **local** byte persistence (isolated roots; no cross-modality paths).
 */
interface ModalityLocalFileSink {
    /**
     * @param relativePath path segments under the modality root (e.g. `audio_20260101120000123.bin`).
     * @return true if bytes were durably written (or deduped skip treated as success).
     */
    suspend fun writeBytes(
        modality: ModalityKind,
        relativePath: String,
        bytes: ByteArray,
        dedupeContentKey: String?,
    ): Boolean

    suspend fun readBytes(modality: ModalityKind, relativePath: String): ByteArray?

    suspend fun deleteFile(modality: ModalityKind, relativePath: String): Boolean

    /**
     * Non-recursive list of file paths under `[modalityRoot]/[subdirectory]/` (paths include [subdirectory]/ prefix).
     */
    suspend fun listFilesUnderSubdirectory(modality: ModalityKind, subdirectory: String): List<String>
}
