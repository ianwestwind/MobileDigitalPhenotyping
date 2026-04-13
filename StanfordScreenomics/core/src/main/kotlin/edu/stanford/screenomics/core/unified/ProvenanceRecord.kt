package edu.stanford.screenomics.core.unified

/**
 * Auditable hop in the provenance chain; when carried on a point, list under [UfsReservedDataKeys.PROVENANCE_RECORDS]
 * inside [UnifiedDataPoint.data], or modelled via [DataEntity.relationships] for structural links.
 */
data class ProvenanceRecord(
    val hopName: String,
    val componentId: String,
    val recordedAtEpochMillis: Long,
    val note: String,
)
