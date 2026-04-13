package edu.stanford.screenomics.core.unified

/**
 * Unified Fusion Standard — canonical fused carrier: payload map + metadata + schema.
 *
 * - **[schema]**: structural declaration ([DataEntity]); no runtime measurement values.
 * - **[data]**: runtime map of attribute key → value (includes reserved UFS keys and modality payload keys).
 * - **[metadata]**: traceable capture context ([DataDescription]).
 *
 * [data] is shallow-copied to an unmodifiable map to reduce accidental cross-module mutation.
 */
class UnifiedDataPoint(
    data: Map<String, Any>,
    override val metadata: DataDescription,
    override val schema: DataEntity,
) : UnifiedFusionStandard {

    override val data: Map<String, Any> = data.toMap()

    init {
        UnifiedFusionConsistency.validateOrThrow(this)
    }

    fun copy(
        data: Map<String, Any> = this.data,
        metadata: DataDescription = this.metadata,
        schema: DataEntity = this.schema,
    ): UnifiedDataPoint = UnifiedDataPoint(data = data, metadata = metadata, schema = schema)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UnifiedDataPoint) return false
        return data == other.data && metadata == other.metadata && schema == other.schema
    }

    override fun hashCode(): Int {
        var result = data.hashCode()
        result = 31 * result + metadata.hashCode()
        result = 31 * result + schema.hashCode()
        return result
    }

    override fun toString(): String =
        "UnifiedDataPoint(dataKeys=${data.keys}, metadata=$metadata, schemaKeys=${schema.attributes.keys})"
}
