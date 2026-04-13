package edu.stanford.screenomics.core.unified

/**
 * Structural schema for UFS (attribute/type/range/relationship declarations).
 *
 * **Separation of concerns:** this type describes *shape and constraints* only. Measured or derived **instance values**
 * (the semantic payload / “entity state”) live exclusively in [UnifiedDataPoint.data]. Do not store payload values here.
 */
data class DataEntity(
    val schemaId: String,
    val schemaRevision: String,
    val attributes: Map<String, EntityAttributeSpec>,
    val types: Map<String, EntityTypeSpec>,
    val valueRanges: Map<String, EntityValueRange>,
    val relationships: List<EntityRelationship>,
) {
    init {
        require(schemaId.isNotBlank()) { "schemaId must not be blank" }
        require(schemaRevision.isNotBlank()) { "schemaRevision must not be blank" }
    }
}

/**
 * Explicit alias clarifying that [DataEntity] is the **schema descriptor**, not the runtime payload map.
 */
typealias UnifiedFusionSchemaDescriptor = DataEntity
