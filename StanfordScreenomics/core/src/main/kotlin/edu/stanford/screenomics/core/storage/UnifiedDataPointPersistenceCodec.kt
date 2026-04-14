package edu.stanford.screenomics.core.storage

import edu.stanford.screenomics.core.unified.DataDescription
import edu.stanford.screenomics.core.unified.ProvenanceRecord
import edu.stanford.screenomics.core.unified.UnifiedDataPoint
import java.time.ZoneOffset
import java.util.Base64

/**
 * Serializes a [UnifiedDataPoint] into Firestore/RTDB-safe maps with embedded [DataDescription] (no schema blob).
 */
object UnifiedDataPointPersistenceCodec {

    fun toStructuredMap(point: UnifiedDataPoint): Map<String, Any?> = linkedMapOf(
        "ufsEnvelopeVersion" to point.metadata.ufsEnvelopeVersion,
        "metadata" to encodeDescription(point.metadata),
        "data" to encodeData(point.data),
    )

    private fun encodeDescription(d: DataDescription): Map<String, Any?> = linkedMapOf(
        "source" to d.source,
        "timestampEpochMillis" to d.timestamp.toEpochMilli(),
        "timestampIsoOffsetUtc" to d.timestamp.atOffset(ZoneOffset.UTC).toString(),
        "acquisitionMethod" to d.acquisitionMethod,
        "modality" to d.modality.name,
        "captureSessionId" to d.captureSessionId,
        "producerNodeId" to d.producerNodeId,
        "producerAdapterId" to d.producerAdapterId,
        "ufsEnvelopeVersion" to d.ufsEnvelopeVersion,
    )

    private fun encodeData(data: Map<String, Any>): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        for ((k, v) in data) {
            out[k] = encodeDataValue(v)
        }
        return out
    }

    private fun encodeDataValue(v: Any?): Any? = when (v) {
        null -> null
        is String, is Number, is Boolean -> v
        is ByteArray -> mapOf("_type" to "bytes", "base64" to Base64.getEncoder().encodeToString(v))
        is List<*> -> v.map { encodeDataValue(it) }
        is ProvenanceRecord -> mapOf(
            "_type" to "ProvenanceRecord",
            "hopName" to v.hopName,
            "componentId" to v.componentId,
            "recordedAtEpochMillis" to v.recordedAtEpochMillis,
            "note" to v.note,
        )
        else -> mapOf("_type" to "toString", "value" to v.toString())
    }
}
