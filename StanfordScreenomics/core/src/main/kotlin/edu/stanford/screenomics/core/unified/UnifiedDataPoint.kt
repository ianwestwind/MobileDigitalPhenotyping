package edu.stanford.screenomics.core.unified

/**
 * Unified Fusion Standard — fused carrier: payload map + metadata.
 *
 * - **[data]**: runtime map of attribute key → value (includes reserved UFS keys and modality payload keys).
 * - **[metadata]**: traceable capture context ([DataDescription]).
 *
 * [data] is shallow-copied to an unmodifiable map to reduce accidental cross-module mutation.
 */
class UnifiedDataPoint(
    data: Map<String, Any>,
    override val metadata: DataDescription,
) : UnifiedFusionStandard {

    override val data: Map<String, Any> = data.toMap()

    init {
        UnifiedFusionConsistency.validateOrThrow(this)
    }

    fun copy(
        data: Map<String, Any> = this.data,
        metadata: DataDescription = this.metadata,
    ): UnifiedDataPoint = UnifiedDataPoint(data = data, metadata = metadata)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UnifiedDataPoint) return false
        return data == other.data && metadata == other.metadata
    }

    override fun hashCode(): Int {
        var result = data.hashCode()
        result = 31 * result + metadata.hashCode()
        return result
    }

    override fun toString(): String =
        "UnifiedDataPoint(dataKeys=${data.keys}, metadata=$metadata)"
}
