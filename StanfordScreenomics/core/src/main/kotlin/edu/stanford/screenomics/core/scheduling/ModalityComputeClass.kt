package edu.stanford.screenomics.core.scheduling

import edu.stanford.screenomics.core.unified.ModalityKind

/**
 * Static **compute weight** used to prefer lightweight modalities under stress.
 */
enum class ModalityComputeClass {
    LIGHT,
    STANDARD,
    HEAVY,
}

object ModalityComputeProfile {

    fun classify(modality: ModalityKind): ModalityComputeClass = when (modality) {
        ModalityKind.MOTION,
        ModalityKind.GPS,
        -> ModalityComputeClass.LIGHT
        ModalityKind.AUDIO,
        -> ModalityComputeClass.STANDARD
        ModalityKind.SCREENSHOT,
        -> ModalityComputeClass.HEAVY
    }
}
