package edu.stanford.screenomics.core.module.template

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Non-blocking dispatcher bundle for [ModulePipeline] stages (no [kotlinx.coroutines.runBlocking]).
 * Defaults use bounded parallelism suitable for adaptation + cache commits off the caller thread.
 */
data class ModulePipelineDispatchers(
    val rawIngress: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(4),
    val adaptation: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(4),
    val cacheCommit: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(4),
    val channelDelivery: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(4),
)
