package edu.stanford.screenomics.core.management

/**
 * **Audit contract** for [DefaultCacheManager] / [CacheManager]: documents invariants and reserves a
 * single extension seam (no mutable global cache state).
 *
 * Future work: static verification hooks, conformance tests, or build-time assertions may call into
 * companion functions added here without changing pipeline modules.
 */
object CacheManagerAuditedInvariantsPlaceholder {

    /** Each [InMemoryCache] owns its backing store; the manager registry is `cacheId → instance` only. */
    const val INVARIANT_NO_MERGED_BACKING_STORE: String =
        "DefaultCacheManager never merges UnifiedDataPoint maps across modalities or cache instances."

    /**
     * [CacheManager.snapshotByModality] may concatenate lists from **multiple** caches that share a
     * [edu.stanford.screenomics.core.unified.ModalityKind]; that is a read-only aggregate view, not shared storage.
     */
    const val INVARIANT_AGGREGATE_SNAPSHOTS_ARE_VIEWS: String =
        "snapshotByModality groups snapshots; it does not route puts through a shared buffer."

    /** Caches that do not implement [SlidingWindowTtlSweepTarget] are still isolated but skipped by sweeps. */
    const val INVARIANT_SWEEP_TARGETS_OPTIONAL: String =
        "Registration retains InMemoryCache; sweep only invokes SlidingWindowTtlSweepTarget when present."

    const val INVARIANT_TTL_SLIDING_WINDOW: String =
        "Eviction cutoff = windowEnd - windowDuration; post-put uses event-time end; sweeps may anchor with spec.clock."
}
