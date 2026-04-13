package edu.stanford.screenomics.core.unified

import java.time.Instant

/**
 * Unified Fusion Standard — capture metadata bound to every [UnifiedDataPoint] as [UnifiedDataPoint.metadata].
 * Traceability: session + producer identities are mandatory so every modality can be attributed in fused timelines.
 */
data class DataDescription(
    val source: String,
    val timestamp: Instant,
    val acquisitionMethod: String,
    val modality: ModalityKind,
    val captureSessionId: String,
    val producerNodeId: String,
    val producerAdapterId: String,
    val ufsEnvelopeVersion: String,
) {
    init {
        require(source.isNotBlank()) { "source must not be blank" }
        require(acquisitionMethod.isNotBlank()) { "acquisitionMethod must not be blank" }
        require(captureSessionId.isNotBlank()) { "captureSessionId must not be blank" }
        require(producerNodeId.isNotBlank()) { "producerNodeId must not be blank" }
        require(producerAdapterId.isNotBlank()) { "producerAdapterId must not be blank" }
        require(ufsEnvelopeVersion.isNotBlank()) { "ufsEnvelopeVersion must not be blank" }
    }
}
