package edu.stanford.screenomics.core.unified

/**
 * Composes modality-specific payload schema declarations with canonical UFS reserved declarations.
 * Payload attribute keys MUST NOT use the reserved `ufs.` prefix (canonical keys always win on collision).
 */
object UfsSchemaComposition {

    fun compose(
        schemaId: String,
        schemaRevision: String,
        payloadAttributes: Map<String, EntityAttributeSpec>,
        payloadTypes: Map<String, EntityTypeSpec>,
        payloadValueRanges: Map<String, EntityValueRange>,
        relationships: List<EntityRelationship>,
    ): DataEntity {
        for (k in payloadAttributes.keys) {
            require(!k.startsWith(UfsReservedDataKeys.RESERVED_KEY_PREFIX)) {
                "payload attribute key must not start with '${UfsReservedDataKeys.RESERVED_KEY_PREFIX}': $k"
            }
        }
        for (k in payloadTypes.keys) {
            require(!k.startsWith(UfsReservedDataKeys.RESERVED_KEY_PREFIX)) {
                "payload types key must not start with '${UfsReservedDataKeys.RESERVED_KEY_PREFIX}': $k"
            }
        }
        for (k in payloadValueRanges.keys) {
            require(!k.startsWith(UfsReservedDataKeys.RESERVED_KEY_PREFIX)) {
                "payload valueRanges key must not start with '${UfsReservedDataKeys.RESERVED_KEY_PREFIX}': $k"
            }
        }

        val attributes = LinkedHashMap<String, EntityAttributeSpec>().apply {
            putAll(payloadAttributes)
            putAll(UfsCanonicalReservedDeclarations.attributeSpecs)
        }
        val types = LinkedHashMap<String, EntityTypeSpec>().apply {
            putAll(payloadTypes)
            putAll(UfsCanonicalReservedDeclarations.typeSpecs)
        }
        val valueRanges = LinkedHashMap<String, EntityValueRange>().apply {
            putAll(payloadValueRanges)
            putAll(UfsCanonicalReservedDeclarations.valueRangeSpecs)
        }

        return DataEntity(
            schemaId = schemaId,
            schemaRevision = schemaRevision,
            attributes = attributes,
            types = types,
            valueRanges = valueRanges,
            relationships = relationships,
        )
    }
}
