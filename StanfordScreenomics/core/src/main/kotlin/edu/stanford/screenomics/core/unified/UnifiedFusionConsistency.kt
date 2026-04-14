package edu.stanford.screenomics.core.unified

/**
 * UFS consistency rules shared by all modules. Invoked from [UnifiedDataPoint] construction.
 */
object UnifiedFusionConsistency {

    fun validateOrThrow(point: UnifiedDataPoint) {
        val data = point.data
        val metadata = point.metadata

        require(metadata.source.isNotBlank())
        require(metadata.acquisitionMethod.isNotBlank())
        require(metadata.captureSessionId.isNotBlank())
        require(metadata.producerNodeId.isNotBlank())
        require(metadata.producerAdapterId.isNotBlank())
        require(metadata.ufsEnvelopeVersion.isNotBlank())

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
    }
}
