package edu.stanford.screenomics.core.storage

import edu.stanford.screenomics.core.unified.ModalityKind

/**
 * Per-modality **relative** directory names under app-private files root (no shared global folder).
 */
object ModalityStorageDirectoryName {

    fun forModality(modality: ModalityKind): String = when (modality) {
        ModalityKind.AUDIO -> "audio"
        ModalityKind.SCREENSHOT -> "screenshot"
        ModalityKind.GPS -> "gps"
        ModalityKind.MOTION -> "motion"
    }
}
