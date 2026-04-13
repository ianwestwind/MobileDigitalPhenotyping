package edu.stanford.screenomics.core.management

/**
 * Opaque handle for a single [InMemoryCache] registration with a [CacheManager].
 * Callers retain this for explicit release — there is **no** global resolver for registered caches.
 */
interface CacheRegistrationHandle {
    val cacheId: String

    /**
     * Removes this cache from the manager registry (does **not** by itself clear the cache instance).
     */
    suspend fun releaseFromManager()
}
