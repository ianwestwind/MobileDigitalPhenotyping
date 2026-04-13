package edu.stanford.screenomics.core.edge

import edu.stanford.screenomics.core.management.CacheManager

/**
 * Pre–[EdgeComputationEngine.runCycle] verification seam (static analysis, tests, or future runtime guards).
 * Asserts the engine is about to read **cache-backed** state only — never raw capture streams.
 */
object EdgeComputationCachePathVerifierPlaceholder {

    @Suppress("UNUSED_PARAMETER")
    suspend fun assertCacheBackedSourceOnly(cacheManager: CacheManager) {
        // Placeholder: e.g. require(cacheManager is DefaultCacheManager) for policy tests, or attach debug probes.
    }
}
