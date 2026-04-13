package edu.stanford.screenomics.core.unified

/**
 * Well-known keys inside [UnifiedDataPoint.data] for cross-modality linkage.
 * All modules MUST use these tokens for correlation and optional provenance to keep caches and schedulers coherent.
 */
object UfsReservedDataKeys {
    const val RESERVED_KEY_PREFIX: String = "ufs."

    const val CORRELATION_ID: String = "ufs.correlationId"
    const val MONOTONIC_TIMESTAMP_NANOS: String = "ufs.monotonicTimestampNanos"
    const val PROVENANCE_RECORDS: String = "ufs.provenanceRecords"
}
