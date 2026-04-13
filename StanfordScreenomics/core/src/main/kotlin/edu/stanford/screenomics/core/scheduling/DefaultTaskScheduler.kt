package edu.stanford.screenomics.core.scheduling

import edu.stanford.screenomics.core.debug.PipelineDiagnosticsRegistry
import edu.stanford.screenomics.core.debug.PipelineLogTags
import edu.stanford.screenomics.core.module.template.ModulePipelineDispatchers
import edu.stanford.screenomics.core.unified.ModalityKind
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Default [TaskScheduler]: monitors CPU/memory/battery thresholds via [HostResourceSignalProvider],
 * retunes **switchable** [CoroutineDispatcher] delegates, demotes [TaskPriority] under stress, and prefers
 * **light** modalities via [PipelineParallelismPlan].
 */
class DefaultTaskScheduler(
    private val thresholds: ResourceStressThresholds = ResourceStressThresholds.DEFAULT,
    private val pollIntervalMs: Long = 1_000L,
) : TaskScheduler {

    private val schedulerRoot = SupervisorJob()
    private val parallelWorkRoot = SupervisorJob(schedulerRoot)

    private val stressFlow = MutableStateFlow(ResourceStressLevel.NORMAL)
    private val snapshotFlow = MutableStateFlow(HostResourceSnapshot.placeholderHealthy())

    private val priorityRegistry = DynamicTaskPriorityRegistry()
    private val monitoringMutex = Mutex()
    private var monitoringJob: Job? = null

    private val modalityBundles: ConcurrentHashMap<ModalityKind, ModalityDispatcherBundle> = ConcurrentHashMap()

    private val ioSwitchable = SwitchableCoroutineDispatcher(
        label = "task-scheduler-io",
        initial = LimitedDispatcherCache.ioLimited(4),
    )
    private val cpuSwitchable = SwitchableCoroutineDispatcher(
        label = "task-scheduler-cpu",
        initial = LimitedDispatcherCache.defaultLimited(4),
    )

    override fun modulePipelineDispatchers(modality: ModalityKind): ModulePipelineDispatchers =
        modalityBundles.computeIfAbsent(modality) { createBundleForModality(it) }.asPipeline()

    override fun currentStressLevel(): ResourceStressLevel = stressFlow.value

    override fun observeStressLevel(): StateFlow<ResourceStressLevel> = stressFlow.asStateFlow()

    override fun observeLastSnapshot(): StateFlow<HostResourceSnapshot> = snapshotFlow.asStateFlow()

    override suspend fun registerTask(taskId: String, basePriority: TaskPriority) {
        priorityRegistry.register(taskId, basePriority)
    }

    override suspend fun unregisterTask(taskId: String) {
        priorityRegistry.unregister(taskId)
    }

    override suspend fun effectiveTaskPriority(taskId: String): TaskPriority =
        priorityRegistry.effectivePriority(taskId)

    override suspend fun snapshotRegisteredTaskPriorities(): Map<String, TaskPriority> =
        priorityRegistry.snapshotEffectivePriorities()

    override suspend fun startResourceMonitoring(scope: CoroutineScope, provider: HostResourceSignalProvider) {
        monitoringMutex.withLock {
            monitoringJob?.cancel()
            monitoringJob = scope.launch(Dispatchers.Default) {
                while (isActive) {
                    val snap = provider.currentSnapshot()
                    snapshotFlow.value = snap
                    val signals = ResourceStressEvaluator.signals(snap, thresholds)
                    val level = ResourceStressEvaluator.level(signals)
                    PipelineDiagnosticsRegistry.emit(
                        logTag = PipelineLogTags.SCHEDULER,
                        module = null,
                        stage = "resource_poll",
                        dataType = "host_resource_snapshot",
                        detail = "[SCHEDULER] cpuLoad01=${snap.processCpuLoad01} availMemBytes=${snap.availableMemoryBytes} " +
                            "batteryFraction=${snap.batteryFraction} stressLevel=$level signals=$signals",
                    )
                    stressFlow.value = level
                    priorityRegistry.setStressLevel(level)
                    reapplyParallelism(level)
                    val (ioP, cpuP) = reapplyUtilityDispatchers(level)
                    val planSummary = modalityBundles.entries.joinToString(separator = "; ") { (modality, _) ->
                        val weight = ModalityComputeProfile.classify(modality)
                        val plan = PipelineParallelismPlan.forModality(level, weight)
                        "${modality.name}:raw=${plan.rawIngress} adapt=${plan.adaptation} cache=${plan.cacheCommit} chan=${plan.channelDelivery}"
                    }.ifBlank { "(no modality pipelines registered yet)" }
                    val stressHint = when (level) {
                        ResourceStressLevel.NORMAL -> "parallelism relaxed"
                        ResourceStressLevel.STRESSED -> "prioritizing lighter modalities + lower IO/CPU caps"
                        ResourceStressLevel.CRITICAL -> "aggressive throttling; heavy modalities serialized"
                    }
                    PipelineDiagnosticsRegistry.emit(
                        logTag = PipelineLogTags.SCHEDULER,
                        module = null,
                        stage = "task_prioritization",
                        dataType = "dispatcher_retune",
                        detail = "[SCHEDULER] stressLevel=$level ioParallel=$ioP cpuParallel=$cpuP cpuLoad01=${snap.processCpuLoad01} " +
                            "availMemBytes=${snap.availableMemoryBytes} batteryFraction=${snap.batteryFraction} → $stressHint | $planSummary",
                    )
                    HostResourcePollHooksPlaceholder.afterPoll(snap, level)
                    kotlinx.coroutines.delay(pollIntervalMs)
                }
            }
        }
    }

    override fun stopResourceMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
    }

    override suspend fun launchParallelIo(blocks: List<suspend () -> Unit>) {
        if (blocks.isEmpty()) return
        coroutineScope {
            blocks.map { block ->
                async(coroutineContext + parallelWorkRoot + ioSwitchable) {
                    block()
                }
            }.awaitAll()
        }
    }

    override suspend fun launchParallelCpu(blocks: List<suspend () -> Unit>) {
        if (blocks.isEmpty()) return
        coroutineScope {
            blocks.map { block ->
                async(coroutineContext + parallelWorkRoot + cpuSwitchable) {
                    block()
                }
            }.awaitAll()
        }
    }

    override suspend fun cancelAllRegistered() {
        parallelWorkRoot.cancelChildren()
    }

    private fun createBundleForModality(modality: ModalityKind): ModalityDispatcherBundle {
        val level = stressFlow.value
        val plan = PipelineParallelismPlan.forModality(level, ModalityComputeProfile.classify(modality))
        return ModalityDispatcherBundle(
            modality = modality,
            rawIngress = newSwitchable("raw-$modality", plan.rawIngress),
            adaptation = newSwitchable("adapt-$modality", plan.adaptation),
            cacheCommit = newSwitchable("cache-$modality", plan.cacheCommit),
            channelDelivery = newSwitchable("chan-$modality", plan.channelDelivery),
        )
    }

    private fun newSwitchable(label: String, parallelism: Int): SwitchableCoroutineDispatcher =
        SwitchableCoroutineDispatcher(label, LimitedDispatcherCache.defaultLimited(parallelism))

    private suspend fun reapplyParallelism(level: ResourceStressLevel) {
        for ((modality, bundle) in modalityBundles) {
            val weight = ModalityComputeProfile.classify(modality)
            val plan = PipelineParallelismPlan.forModality(level, weight)
            bundle.rawIngress.replace(LimitedDispatcherCache.defaultLimited(plan.rawIngress))
            bundle.adaptation.replace(LimitedDispatcherCache.defaultLimited(plan.adaptation))
            bundle.cacheCommit.replace(LimitedDispatcherCache.defaultLimited(plan.cacheCommit))
            bundle.channelDelivery.replace(LimitedDispatcherCache.defaultLimited(plan.channelDelivery))
            TaskSchedulerTelemetryPlaceholder.onParallelismRebound(modality, plan, level)
        }
    }

    private fun reapplyUtilityDispatchers(level: ResourceStressLevel): Pair<Int, Int> {
        val (ioP, cpuP) = when (level) {
            ResourceStressLevel.NORMAL -> 4 to 4
            ResourceStressLevel.STRESSED -> 3 to 2
            ResourceStressLevel.CRITICAL -> 2 to 1
        }
        ioSwitchable.replace(LimitedDispatcherCache.ioLimited(ioP))
        cpuSwitchable.replace(LimitedDispatcherCache.defaultLimited(cpuP))
        return ioP to cpuP
    }

    private data class ModalityDispatcherBundle(
        val modality: ModalityKind,
        val rawIngress: SwitchableCoroutineDispatcher,
        val adaptation: SwitchableCoroutineDispatcher,
        val cacheCommit: SwitchableCoroutineDispatcher,
        val channelDelivery: SwitchableCoroutineDispatcher,
    ) {
        fun asPipeline(): ModulePipelineDispatchers = ModulePipelineDispatchers(
            rawIngress = rawIngress,
            adaptation = adaptation,
            cacheCommit = cacheCommit,
            channelDelivery = channelDelivery,
        )
    }
}
