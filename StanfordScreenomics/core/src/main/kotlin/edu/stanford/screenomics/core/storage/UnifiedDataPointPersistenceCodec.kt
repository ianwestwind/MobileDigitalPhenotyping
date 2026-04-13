package edu.stanford.screenomics.core.storage

import edu.stanford.screenomics.core.unified.CategoricalEntityValueRange
import edu.stanford.screenomics.core.unified.DataDescription
import edu.stanford.screenomics.core.unified.DataEntity
import edu.stanford.screenomics.core.unified.EntityAttributeSpec
import edu.stanford.screenomics.core.unified.EntityRelationship
import edu.stanford.screenomics.core.unified.EntityTypeSpec
import edu.stanford.screenomics.core.unified.EntityValueRange
import edu.stanford.screenomics.core.unified.NumericEntityValueRange
import edu.stanford.screenomics.core.unified.OrdinalEntityValueRange
import edu.stanford.screenomics.core.unified.ProvenanceRecord
import edu.stanford.screenomics.core.unified.UnboundedEntityValueRange
import edu.stanford.screenomics.core.unified.UnifiedDataPoint
import java.time.ZoneOffset
import java.util.Base64

/**
 * Serializes a [UnifiedDataPoint] into Firestore/RTDB-safe maps with **embedded**
 * [DataDescription] and [DataEntity] (no separate metadata collection).
 */
object UnifiedDataPointPersistenceCodec {

    fun toStructuredMap(point: UnifiedDataPoint): Map<String, Any?> = linkedMapOf(
        "ufsEnvelopeVersion" to point.metadata.ufsEnvelopeVersion,
        "metadata" to encodeDescription(point.metadata),
        "schema" to encodeEntity(point.schema),
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

    private fun encodeEntity(e: DataEntity): Map<String, Any?> = linkedMapOf(
        "schemaId" to e.schemaId,
        "schemaRevision" to e.schemaRevision,
        "attributes" to e.attributes.mapValues { (_, v) -> encodeAttrSpec(v) },
        "types" to e.types.mapValues { (_, v) -> encodeTypeSpec(v) },
        "valueRanges" to e.valueRanges.mapValues { (_, v) -> encodeValueRange(v) },
        "relationships" to e.relationships.map { encodeRelationship(it) },
    )

    private fun encodeAttrSpec(s: EntityAttributeSpec): Map<String, Any?> = mapOf(
        "required" to s.required,
        "semanticDescription" to s.semanticDescription,
        "structureTag" to s.structureTag,
    )

    private fun encodeTypeSpec(t: EntityTypeSpec): Map<String, Any?> = mapOf(
        "typeId" to t.typeId,
        "encoding" to t.encoding,
        "version" to t.version,
    )

    private fun encodeRelationship(r: EntityRelationship): Map<String, Any?> = mapOf(
        "subjectAttributeKey" to r.subjectAttributeKey,
        "predicateToken" to r.predicateToken,
        "objectAttributeKey" to r.objectAttributeKey,
        "bidirectional" to r.bidirectional,
        "cardinalityHint" to r.cardinalityHint,
    )

    private fun encodeValueRange(v: EntityValueRange): Map<String, Any?> = when (v) {
        is NumericEntityValueRange -> mapOf(
            "type" to "numeric",
            "minInclusive" to v.minInclusive,
            "maxInclusive" to v.maxInclusive,
            "stepHint" to v.stepHint,
        )
        is CategoricalEntityValueRange -> mapOf(
            "type" to "categorical",
            "allowedValues" to v.allowedValues.toList(),
        )
        is OrdinalEntityValueRange -> mapOf(
            "type" to "ordinal",
            "orderedLabels" to v.orderedLabels.toList(),
        )
        is UnboundedEntityValueRange -> mapOf(
            "type" to "unbounded",
            "rationale" to v.rationale,
        )
    }

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
