package edu.stanford.screenomics.core.module.template

import edu.stanford.screenomics.core.collection.DataNode
import edu.stanford.screenomics.core.collection.RawModalityFrame
import edu.stanford.screenomics.core.unified.ModalityKind
import edu.stanford.screenomics.core.unified.UnifiedDataPoint
import edu.stanford.screenomics.core.debug.PipelineDiagnosticsFormat
import edu.stanford.screenomics.core.debug.PipelineDiagnosticsRegistry
import edu.stanford.screenomics.core.debug.toModuleLogTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach

/**
 * Reusable DataNode skeleton: exposes raw ingress for the pipeline and UFS egress after Adapter→Cache stages.
 * Subclasses supply modality identity, [observeRawFrames], and lifecycle hooks (no sensor acquisition here).
 */
abstract class BaseDataNode(
    private val adapter: BaseAdapter,
    private val cache: BaseCache,
    private val dispatchers: ModulePipelineDispatchers = ModulePipelineDispatchers(),
    private val hooks: ModulePipelineHooks = ModulePipelineHooks(),
) : DataNode {

    abstract override val nodeId: String

    abstract override fun modalityKind(): ModalityKind

    /**
     * Modality-local raw capture stream feeding [ModulePipeline] (async, non-blocking [Flow]).
     */
    protected abstract fun observeRawFrames(): Flow<RawModalityFrame>

    private val modulePipeline: ModulePipeline by lazy {
        ModulePipeline(
            dispatchers = dispatchers,
            rawFrames = observeRawFrames().onEach { raw ->
                val m = modalityKind()
                PipelineDiagnosticsRegistry.emit(
                    logTag = m.toModuleLogTag(),
                    module = m,
                    stage = "DataNode",
                    dataType = "raw_${raw::class.simpleName}",
                    detail = "[DataNode] captured raw ${PipelineDiagnosticsFormat.rawFrame(m, raw)}",
                )
            },
            adapter = adapter,
            cache = cache,
            hooks = hooks,
        )
    }

    final override fun observeUnifiedOutputs(): Flow<UnifiedDataPoint> =
        modulePipeline.unifiedOutputFlow()

    /**
     * Optional hot channel view of the same pipeline graph (parallel to [observeUnifiedOutputs] collectors).
     */
    fun openUnifiedOutputChannel(scope: CoroutineScope, capacity: Int = Channel.BUFFERED): ReceiveChannel<UnifiedDataPoint> =
        modulePipeline.openUnifiedOutputChannel(scope, capacity)

    final override suspend fun activate(collectionScope: CoroutineScope) {
        onActivate(collectionScope)
    }

    final override suspend fun deactivate() {
        onDeactivate()
    }

    protected open suspend fun onActivate(collectionScope: CoroutineScope) {}
    protected open suspend fun onDeactivate() {}
}
