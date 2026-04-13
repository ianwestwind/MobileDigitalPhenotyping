package edu.stanford.screenomics.core.scheduling

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Stable [Dispatchers.Default]/[Dispatchers.IO] [limitedParallelism][kotlinx.coroutines.CoroutineDispatcher.limitedParallelism]
 * instances keyed by **n** to avoid leaking unbounded dispatcher pools during adaptive retuning.
 */
internal object LimitedDispatcherCache {

    private val defaultLimited = ConcurrentHashMap<Int, CoroutineDispatcher>()
    private val ioLimited = ConcurrentHashMap<Int, CoroutineDispatcher>()

    fun defaultLimited(parallelism: Int): CoroutineDispatcher {
        val n = parallelism.coerceIn(1, 8)
        return defaultLimited.computeIfAbsent(n) { Dispatchers.Default.limitedParallelism(n) }
    }

    fun ioLimited(parallelism: Int): CoroutineDispatcher {
        val n = parallelism.coerceIn(1, 8)
        return ioLimited.computeIfAbsent(n) { Dispatchers.IO.limitedParallelism(n) }
    }
}
