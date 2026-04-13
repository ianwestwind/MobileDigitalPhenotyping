package edu.stanford.screenomics.core.debug

import edu.stanford.screenomics.core.collection.RawModalityFrame
import edu.stanford.screenomics.core.unified.DataDescription
import edu.stanford.screenomics.core.unified.ModalityKind
import edu.stanford.screenomics.core.unified.UnifiedDataPoint

/**
 * Global hook for **Android Log** (installed from `:app` via [PipelineDiagnosticsRegistry.install]).
 * Core modules call [PipelineDiagnosticsRegistry.emit] with stable [PipelineLogTags] values.
 */
object PipelineLogTags {
    const val AUDIO_MODULE = "AUDIO_MODULE"
    const val SCREENSHOT_MODULE = "SCREENSHOT_MODULE"
    const val GPS_MODULE = "GPS_MODULE"
    const val MOTION_MODULE = "MOTION_MODULE"

    const val ADAPTER = "ADAPTER"
    const val CACHE = "CACHE"
    const val LOCAL_STORAGE = "LOCAL_STORAGE"
    const val UPLOAD_QUEUE = "UPLOAD_QUEUE"
    const val FIREBASE = "FIREBASE"
    const val EDGE_ENGINE = "EDGE_ENGINE"
    const val SCHEDULER = "SCHEDULER"
    const val INTERVENTION = "INTERVENTION"
}

fun ModalityKind.toModuleLogTag(): String = when (this) {
    ModalityKind.AUDIO -> PipelineLogTags.AUDIO_MODULE
    ModalityKind.SCREENSHOT -> PipelineLogTags.SCREENSHOT_MODULE
    ModalityKind.GPS -> PipelineLogTags.GPS_MODULE
    ModalityKind.MOTION -> PipelineLogTags.MOTION_MODULE
}

fun interface PipelineDiagnostics {
    /**
     * @param logTag Android Log tag (e.g. [PipelineLogTags.ADAPTER]).
     * @param module null for system-wide stages.
     * @param stage pipeline stage name (e.g. `DataNode`, `adapt_complete`, `cache_insert`).
     * @param dataType logical data label (e.g. `raw_pcm`, `mean_decibel`, `step_count`).
     */
    fun emit(logTag: String, module: ModalityKind?, stage: String, dataType: String, detail: String)
}

private object NoopPipelineDiagnostics : PipelineDiagnostics {
    override fun emit(logTag: String, module: ModalityKind?, stage: String, dataType: String, detail: String) = Unit
}

object PipelineDiagnosticsRegistry {

    @Volatile
    private var impl: PipelineDiagnostics = NoopPipelineDiagnostics

    fun install(diagnostics: PipelineDiagnostics) {
        impl = diagnostics
    }

    fun resetToNoop() {
        impl = NoopPipelineDiagnostics
    }

    fun emit(logTag: String, module: ModalityKind?, stage: String, dataType: String, detail: String) {
        impl.emit(logTag, module, stage, dataType, truncate(detail, 3500))
    }
}

private fun truncate(s: String, max: Int): String =
    if (s.length <= max) s else s.take(max) + "…(truncated len=${s.length})"

object PipelineDiagnosticsFormat {

    fun rawFrame(modality: ModalityKind, raw: RawModalityFrame): String =
        "frameClass=${raw::class.simpleName} correlationId=${raw.correlationId.value} " +
            "capturedAtEpochMillis=${raw.capturedAtEpochMillis}"

    fun dataDescription(d: DataDescription): String =
        "source=${d.source} acquisitionMethod=${d.acquisitionMethod} timestamp=${d.timestamp} " +
            "modality=${d.modality.name} captureSessionId=${d.captureSessionId} producerNodeId=${d.producerNodeId} " +
            "producerAdapterId=${d.producerAdapterId} ufsEnvelopeVersion=${d.ufsEnvelopeVersion}"

    fun unifiedDataPointFull(p: UnifiedDataPoint): String {
        val keys = p.data.keys.sorted()
        val sample = keys.take(16).joinToString(separator = "; ") { k -> "$k=${p.data[k]}" }
        return "metadata={ ${dataDescription(p.metadata)} } " +
            "schema={ schemaId=${p.schema.schemaId} schemaRevision=${p.schema.schemaRevision} " +
            "attributeCount=${p.schema.attributes.size} } " +
            "dataKeys=[$keys] dataSample=[$sample]"
    }
}
