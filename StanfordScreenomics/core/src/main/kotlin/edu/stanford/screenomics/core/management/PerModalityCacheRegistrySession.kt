package edu.stanford.screenomics.core.management

import edu.stanford.screenomics.core.unified.ModalityKind

/**
 * **Session-scoped** helper for registering the four modality caches with one [CacheManager] handle list.
 * This is not a global cache: it only batches explicit [InMemoryCache] instances supplied by the caller.
 */
class PerModalityCacheRegistrySession(
    private val manager: CacheManager,
) {
    private val handles = ArrayList<CacheRegistrationHandle>()

    suspend fun registerStandardModalities(
        audioCache: InMemoryCache,
        motionCache: InMemoryCache,
        gpsCache: InMemoryCache,
        screenshotCache: InMemoryCache,
    ) {
        requireKind(audioCache, ModalityKind.AUDIO)
        requireKind(motionCache, ModalityKind.MOTION)
        requireKind(gpsCache, ModalityKind.GPS)
        requireKind(screenshotCache, ModalityKind.SCREENSHOT)
        handles += manager.register(audioCache)
        handles += manager.register(motionCache)
        handles += manager.register(gpsCache)
        handles += manager.register(screenshotCache)
    }

    suspend fun releaseAllFromManager() {
        for (h in handles.asReversed()) {
            h.releaseFromManager()
        }
        handles.clear()
    }

    private fun requireKind(cache: InMemoryCache, expected: ModalityKind) {
        require(cache.modalityKind() == expected) {
            "Expected $expected for cacheId=${cache.cacheId}, got ${cache.modalityKind()}"
        }
    }
}
