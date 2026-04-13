package edu.stanford.screenomics.core.management

import edu.stanford.screenomics.core.unified.ModalityKind
import edu.stanford.screenomics.core.unified.UnifiedDataPoint

/**
 * Layer 2 — Data Management: coordinates multiple [InMemoryCache] instances without merging modality caches.
 *
 * Registered caches remain **distinct objects** per modality / pipeline instance; this manager never
 * substitutes a shared global store.
 */
interface CacheManager {
    suspend fun register(cache: InMemoryCache): CacheRegistrationHandle

    suspend fun unregister(cacheId: String)

    suspend fun snapshotByCacheId(): Map<String, List<UnifiedDataPoint>>

    suspend fun snapshotByModality(): Map<ModalityKind, List<UnifiedDataPoint>>
}
