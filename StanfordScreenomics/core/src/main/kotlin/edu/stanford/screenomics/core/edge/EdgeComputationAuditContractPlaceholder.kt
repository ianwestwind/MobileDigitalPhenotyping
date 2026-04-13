package edu.stanford.screenomics.core.edge

/**
 * Formal audit notes for [EdgeComputationEngine] (no mutable state).
 */
object EdgeComputationAuditContractPlaceholder {

    const val INVARIANT_CACHE_MANAGER_ONLY_NO_RAW_STREAM: String =
        "EdgeComputationEngine must consume fused points only via CacheManager snapshots (registered InMemoryCache); " +
            "it must not attach to DataNode raw Flows, ModulePipeline raw ingress, or hot capture channels."

    const val INVARIANT_CACHE_WINDOW: String =
        "Edge cycles slice cached UnifiedDataPoints to a sliding window (default 30 minutes) via CachedWindowSelector."

    const val INVARIANT_SLIDING_WINDOW_ANCHOR: String =
        "Window end = max(point.timestamp) in snapshot union, else clock fallback; cutoff = windowEnd - windowDuration; " +
            "retention uses metadata.timestamp (event time), aligned with cache TTL semantics."

    const val INVARIANT_PHENOTYPE_OUTPUT: String =
        "Every runCycle invokes PhenotypeAnalyzer.analyze on windowed points and passes PhenotypeSummary into InterventionContext."

    const val INVARIANT_INTERVENTION_OUTPUT: String =
        "Every successful runCycle terminates in InterventionController.evaluate (directive is the engine output contract)."

    const val INVARIANT_TFLITE_BRIDGE: String =
        "TFLite is accessed only through TfliteInterpreterBridge; JVM uses NoOp; Android uses asset-backed Interpreter."
}
