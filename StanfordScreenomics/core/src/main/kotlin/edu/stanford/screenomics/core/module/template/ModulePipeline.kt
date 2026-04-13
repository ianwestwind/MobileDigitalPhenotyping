package edu.stanford.screenomics.core.module.template

import edu.stanford.screenomics.core.collection.Adapter
import edu.stanford.screenomics.core.collection.RawModalityFrame
import edu.stanford.screenomics.core.management.InMemoryCache
import edu.stanford.screenomics.core.unified.UnifiedDataPoint
import edu.stanford.screenomics.core.unified.UnifiedFusionConsistency
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.transform

/**
 * Coroutine-based module pipeline wiring the canonical flow:
 *
 * **DataNode (raw)** → **Adapter** → **Cache** → **UnifiedDataPoint** (emitted downstream).
 *
 * Streaming is asynchronous ([kotlinx.coroutines.flow.Flow] and optional [ReceiveChannel]); no blocking APIs are used here.
 *
 * Every emitted [UnifiedDataPoint] is validated with [UnifiedFusionConsistency.validateOrThrow] after [Adapter.adapt]
 * and before cache commit so downstream observers only see UFS-conformant points.
 */
class ModulePipeline(
    private val dispatchers: ModulePipelineDispatchers,
    private val rawFrames: Flow<RawModalityFrame>,
    private val adapter: Adapter,
    private val cache: InMemoryCache,
    private val hooks: ModulePipelineHooks = ModulePipelineHooks(),
    /**
     * Bounded queues between ingress → adaptation → cache so slow stages do not fully serialize faster producers
     * (coroutine suspension only; no blocking queues).
     */
    private val stageChannelCapacity: Int = 64,
) {

    /**
     * Cold flow: each collector runs ingress → adaptation → cache commit without blocking calls.
     */
    fun unifiedOutputFlow(): Flow<UnifiedDataPoint> =
        rawFrames
            .flowOn(dispatchers.rawIngress)
            .buffer(capacity = stageChannelCapacity)
            .transform { raw ->
                hooks.onRawFrameObserved(raw)
                val point = adapter.adapt(raw)
                UnifiedFusionConsistency.validateOrThrow(point)
                hooks.onAdapted(point)
                emit(point)
            }
            .flowOn(dispatchers.adaptation)
            .buffer(capacity = stageChannelCapacity)
            .onEach { point ->
                cache.put(point)
                hooks.onCacheCommitted(point)
            }
            .flowOn(dispatchers.cacheCommit)

    /**
     * Hot async stream backed by a [ReceiveChannel] (buffered); cancel the returned channel or the scope to stop.
     */
    fun openUnifiedOutputChannel(
        scope: CoroutineScope,
        capacity: Int = Channel.BUFFERED,
    ): ReceiveChannel<UnifiedDataPoint> =
        scope.produce(capacity = capacity, context = dispatchers.channelDelivery) {
            unifiedOutputFlow().collect { send(it) }
        }
}
