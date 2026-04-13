package edu.stanford.screenomics.core.unified

/**
 * UFS consistency rules shared by all modules. Invoked from [UnifiedDataPoint] construction.
 */
object UnifiedFusionConsistency {

    fun validateOrThrow(point: UnifiedDataPoint) {
        val schema = point.schema
        val data = point.data
        val metadata = point.metadata

        require(schema.attributes.isNotEmpty()) { "DataEntity.attributes must not be empty" }

        assertCanonicalReservedMatches(schema)

        for ((key, spec) in schema.attributes) {
            require(key.isNotBlank()) { "attribute key must not be blank" }
            require(spec.semanticDescription.isNotBlank()) { "semanticDescription must not be blank for $key" }
            require(spec.structureTag.isNotBlank()) { "structureTag must not be blank for $key" }
            if (spec.required) {
                require(key in data) { "required attribute '$key' missing from data map" }
            }
        }

        for (key in schema.types.keys) {
            require(key in schema.attributes) { "types key '$key' must exist in attributes" }
        }

        for (key in schema.valueRanges.keys) {
            require(key in schema.attributes) { "valueRanges key '$key' must exist in attributes" }
            if (key in data) {
                validateValueForRange(key, data[key]!!, schema.valueRanges.getValue(key))
            }
        }

        for (rel in schema.relationships) {
            require(rel.subjectAttributeKey in schema.attributes) {
                "relationship subject '${rel.subjectAttributeKey}' must exist in attributes"
            }
            require(rel.objectAttributeKey in schema.attributes) {
                "relationship object '${rel.objectAttributeKey}' must exist in attributes"
            }
            require(rel.predicateToken.isNotBlank()) { "predicateToken must not be blank" }
        }

        val correlationEntry = data[UfsReservedDataKeys.CORRELATION_ID]
        require(correlationEntry is String && correlationEntry.isNotBlank()) {
            "data['${UfsReservedDataKeys.CORRELATION_ID}'] must be a non-blank String"
        }

        val mono = data[UfsReservedDataKeys.MONOTONIC_TIMESTAMP_NANOS]
        require(mono is Long) { "data['${UfsReservedDataKeys.MONOTONIC_TIMESTAMP_NANOS}'] must be a non-null Long" }

        val prov = data[UfsReservedDataKeys.PROVENANCE_RECORDS]
        if (prov != null) {
            require(prov is List<*>) { "reserved '${UfsReservedDataKeys.PROVENANCE_RECORDS}' must be List<ProvenanceRecord> when present" }
            @Suppress("UNCHECKED_CAST")
            val list = prov as List<ProvenanceRecord>
            for (p in list) {
                require(p.hopName.isNotBlank() && p.componentId.isNotBlank()) { "invalid ProvenanceRecord entry" }
            }
        }

        require(schema.schemaId.isNotBlank() && schema.schemaRevision.isNotBlank())
    }

    private fun assertCanonicalReservedMatches(schema: DataEntity) {
        for ((key, expected) in UfsCanonicalReservedDeclarations.attributeSpecs) {
            val actual = schema.attributes[key]
            require(actual == expected) {
                "schema.attributes['$key'] must exactly match UFS canonical reserved declaration"
            }
        }
        for ((key, expected) in UfsCanonicalReservedDeclarations.typeSpecs) {
            val actual = schema.types[key]
            require(actual == expected) {
                "schema.types['$key'] must exactly match UFS canonical reserved declaration"
            }
        }
        for ((key, expected) in UfsCanonicalReservedDeclarations.valueRangeSpecs) {
            val actual = schema.valueRanges[key]
            require(actual == expected) {
                "schema.valueRanges['$key'] must exactly match UFS canonical reserved declaration"
            }
        }
    }

    private fun validateValueForRange(attributeKey: String, value: Any, range: EntityValueRange) {
        when (range) {
            is NumericEntityValueRange -> {
                val n = value as? Number ?: throw IllegalArgumentException("attribute '$attributeKey' expected numeric for NumericEntityValueRange")
                val d = n.toDouble()
                require(d in range.minInclusive..range.maxInclusive) {
                    "attribute '$attributeKey' value $d outside [${range.minInclusive}, ${range.maxInclusive}]"
                }
            }
            is CategoricalEntityValueRange -> {
                val s = value as? String ?: throw IllegalArgumentException("attribute '$attributeKey' expected String for CategoricalEntityValueRange")
                require(s in range.allowedValues) { "attribute '$attributeKey' value not in allowed set" }
            }
            is OrdinalEntityValueRange -> {
                val s = value as? String ?: throw IllegalArgumentException("attribute '$attributeKey' expected String for OrdinalEntityValueRange")
                require(s in range.orderedLabels) { "attribute '$attributeKey' value not in ordinal labels" }
            }
            is UnboundedEntityValueRange -> Unit
        }
    }
}
