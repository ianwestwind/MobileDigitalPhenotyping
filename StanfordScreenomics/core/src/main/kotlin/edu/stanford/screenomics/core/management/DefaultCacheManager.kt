package edu.stanford.screenomics.core.management

import edu.stanford.screenomics.core.unified.ModalityKind
import edu.stanford.screenomics.core.unified.UnifiedDataPoint
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * **Instance-scoped** cache coordinator: each modality keeps its own [InMemoryCache] instance;
 * this manager only **indexes** them for snapshots, TTL sweeps, and lifecycle-driven cleanup.
 *
 * There is **no** global cache shortcut: callers must construct and retain this object explicitly.
 *
 * Audit invariants (documentation only): [CacheManagerAuditedInvariantsPlaceholder].
 */
class DefaultCacheManager(
    private val defaultSweepSpec: SlidingWindowTtlSpec = SlidingWindowTtlSpec.DEFAULT_THIRTY_MINUTES,
) : LifecycleAwareCacheManager {

    private val mutex = Mutex()
    private val registry = linkedMapOf<String, RegisteredCache>()

    private data class RegisteredCache(
        val cache: InMemoryCache,
        val sweepTarget: SlidingWindowTtlSweepTarget?,
    )

    override suspend fun register(cache: InMemoryCache): CacheRegistrationHandle {
        mutex.withLock {
            require(!registry.containsKey(cache.cacheId)) {
                "Cache id '${cache.cacheId}' is already registered; use a distinct per-instance id."
            }
            val sweep = cache as? SlidingWindowTtlSweepTarget
            registry[cache.cacheId] = RegisteredCache(cache = cache, sweepTarget = sweep)
        }
        return Handle(cacheId = cache.cacheId)
    }

    override suspend fun unregister(cacheId: String) {
        mutex.withLock {
            registry.remove(cacheId)
        }
    }

    override suspend fun snapshotByCacheId(): Map<String, List<UnifiedDataPoint>> {
        val entries = mutex.withLock { registry.values.map { it.cache.cacheId to it.cache }.toList() }
        return entries.associate { (id, cache) -> id to cache.snapshot() }
    }

    override suspend fun snapshotByModality(): Map<ModalityKind, List<UnifiedDataPoint>> {
        val entries = mutex.withLock { registry.values.map { it.cache }.toList() }
        val grouped = linkedMapOf<ModalityKind, MutableList<UnifiedDataPoint>>()
        for (cache in entries) {
            val kind = cache.modalityKind()
            grouped.getOrPut(kind) { mutableListOf() }.addAll(cache.snapshot())
        }
        return grouped
    }

    override suspend fun onHostBackgrounded() {
        sweepAllRegisteredSlidingWindow(defaultSweepSpec)
    }

    override suspend fun onHostForegrounded() {
        HostForegroundPolicyPlaceholder.onHostForegrounded(this)
    }

    override suspend fun onHostDestroyed() {
        clearAllRegisteredCaches()
        unregisterAll()
    }

    override suspend fun sweepAllRegisteredSlidingWindow(spec: SlidingWindowTtlSpec) {
        val targets = mutex.withLock { registry.values.mapNotNull { it.sweepTarget }.toList() }
        for (t in targets) {
            t.sweepSlidingWindowTtl(spec)
        }
        CacheOrchestrationHooksPlaceholder.afterSlidingWindowSweep(
            sweepTargetCount = targets.size,
            spec = spec,
        )
    }

    override suspend fun clearAllRegisteredCaches() {
        val caches = mutex.withLock { registry.values.map { it.cache }.toList() }
        for (c in caches) {
            c.clear()
        }
        CacheOrchestrationHooksPlaceholder.afterClearAllRegisteredCaches(cacheCount = caches.size)
    }

    override suspend fun unregisterAll() {
        mutex.withLock {
            registry.clear()
        }
    }

    private inner class Handle(
        override val cacheId: String,
    ) : CacheRegistrationHandle {

        override suspend fun releaseFromManager() {
            unregister(cacheId)
        }
    }
}
