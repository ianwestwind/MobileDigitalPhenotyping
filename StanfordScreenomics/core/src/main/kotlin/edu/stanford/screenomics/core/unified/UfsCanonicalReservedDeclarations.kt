package edu.stanford.screenomics.core.unified

/**
 * Canonical UFS declarations for reserved [UfsReservedDataKeys] entries. Every modality schema MUST include
 * these entries exactly (equality) so management, caches, and auditors see a uniform contract surface.
 */
object UfsCanonicalReservedDeclarations {

    private const val UFS_UNBOUNDED_RATIONALE: String = "ufs-canonical-reserved-attribute"

    val canonicalUnboundedRange: UnboundedEntityValueRange =
        UnboundedEntityValueRange(UFS_UNBOUNDED_RATIONALE)

    val attributeSpecs: Map<String, EntityAttributeSpec> = mapOf(
        UfsReservedDataKeys.CORRELATION_ID to EntityAttributeSpec(
            required = true,
            semanticDescription = "Opaque correlation identifier for cache indexing and parallel fusion joins",
            structureTag = "ufs.correlation",
        ),
        UfsReservedDataKeys.MONOTONIC_TIMESTAMP_NANOS to EntityAttributeSpec(
            required = true,
            semanticDescription = "Monotonic device clock in nanoseconds for cross-stream ordering",
            structureTag = "ufs.monotonic-nanos",
        ),
        UfsReservedDataKeys.PROVENANCE_RECORDS to EntityAttributeSpec(
            required = false,
            semanticDescription = "Optional ordered provenance hop list (typed entries)",
            structureTag = "ufs.provenance-list",
        ),
    )

    val typeSpecs: Map<String, EntityTypeSpec> = mapOf(
        UfsReservedDataKeys.CORRELATION_ID to EntityTypeSpec(
            typeId = "ufs.CorrelationIdString",
            encoding = "kotlin.String",
            version = "1",
        ),
        UfsReservedDataKeys.MONOTONIC_TIMESTAMP_NANOS to EntityTypeSpec(
            typeId = "ufs.MonotonicNanos",
            encoding = "kotlin.Long",
            version = "1",
        ),
        UfsReservedDataKeys.PROVENANCE_RECORDS to EntityTypeSpec(
            typeId = "ufs.ProvenanceRecordList",
            encoding = "kotlin.collections.List<ProvenanceRecord>",
            version = "1",
        ),
    )

    val valueRangeSpecs: Map<String, EntityValueRange> = mapOf(
        UfsReservedDataKeys.CORRELATION_ID to canonicalUnboundedRange,
        UfsReservedDataKeys.MONOTONIC_TIMESTAMP_NANOS to canonicalUnboundedRange,
        UfsReservedDataKeys.PROVENANCE_RECORDS to canonicalUnboundedRange,
    )
}
