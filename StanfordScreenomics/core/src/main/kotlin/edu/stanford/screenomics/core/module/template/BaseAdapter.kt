package edu.stanford.screenomics.core.module.template

import edu.stanford.screenomics.core.collection.Adapter
import edu.stanford.screenomics.core.collection.RawModalityFrame
import edu.stanford.screenomics.core.debug.PipelineDiagnosticsFormat
import edu.stanford.screenomics.core.debug.PipelineDiagnosticsRegistry
import edu.stanford.screenomics.core.debug.PipelineLogTags
import edu.stanford.screenomics.core.debug.toModuleLogTag
import edu.stanford.screenomics.core.unified.ModalityKind
import edu.stanford.screenomics.core.unified.UnifiedDataPoint

/**
 * Reusable adapter skeleton: binds identity + modality; sensor-specific [adaptRaw] remains abstract (no sensor logic here).
 */
abstract class BaseAdapter(
    final override val adapterId: String,
    private val modality: ModalityKind,
) : Adapter {

    final override fun modalityKind(): ModalityKind = modality

    final override suspend fun adapt(raw: RawModalityFrame): UnifiedDataPoint {
        val point = adaptRaw(raw)
        val m = modalityKind()
        PipelineDiagnosticsRegistry.emit(
            logTag = PipelineLogTags.ADAPTER,
            module = m,
            stage = "UnifiedDataPoint_created",
            dataType = "${m.name.lowercase()}_unified",
            detail = "[ADAPTER][${m.toModuleLogTag()}] UnifiedDataPoint created: " +
                PipelineDiagnosticsFormat.unifiedDataPointFull(point),
        )
        return point
    }

    /**
     * Sensor modules implement fusion from raw frame to UFS output (placeholder contract until real codecs exist).
     */
    protected abstract suspend fun adaptRaw(raw: RawModalityFrame): UnifiedDataPoint
}
