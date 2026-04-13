package edu.stanford.screenomics.core.scheduling

import edu.stanford.screenomics.core.module.template.ModulePipelineDispatchers
import edu.stanford.screenomics.core.unified.ModalityKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Layer 2 — adaptive orchestration: **resource-aware** scheduling, **dynamic** task priorities, and
 * **strict coroutine dispatcher control** for module pipelines and parallel utility work.
 *
 * Audit invariants (documentation): [TaskSchedulerAuditContractPlaceholder].
 */
interface TaskScheduler {

    /**
     * [ModulePipelineDispatchers] for the given modality; stages are [SwitchableCoroutineDispatcher] instances
     * retuned by [startResourceMonitoring] feedback loops.
     */
    fun modulePipelineDispatchers(modality: ModalityKind): ModulePipelineDispatchers

    fun currentStressLevel(): ResourceStressLevel

    fun observeStressLevel(): StateFlow<ResourceStressLevel>

    fun observeLastSnapshot(): StateFlow<HostResourceSnapshot>

    suspend fun registerTask(taskId: String, basePriority: TaskPriority)

    suspend fun unregisterTask(taskId: String)

    suspend fun effectiveTaskPriority(taskId: String): TaskPriority

    /**
     * Snapshot of **effective** priorities for all registered task ids (empty if none).
     */
    suspend fun snapshotRegisteredTaskPriorities(): Map<String, TaskPriority>

    /**
     * Like [launchParallelIo], but schedules `async` children in **descending effective priority** order
     * (still **concurrent** — order only affects scheduling hints, not sequentialization).
     */
    suspend fun launchParallelIoPrioritized(tasks: List<Pair<String, suspend () -> Unit>>) {
        if (tasks.isEmpty()) return
        val scored = ArrayList<Triple<TaskPriority, String, suspend () -> Unit>>(tasks.size)
        for ((id, block) in tasks) {
            scored += Triple(effectiveTaskPriority(id), id, block)
        }
        val ordered = scored.sortedWith(compareByDescending { it.first.ordinal }).map { it.third }
        launchParallelIo(ordered)
    }

    /**
     * CPU-shaped parallel launch with the same **priority-ordered scheduling** semantics as [launchParallelIoPrioritized].
     */
    suspend fun launchParallelCpuPrioritized(tasks: List<Pair<String, suspend () -> Unit>>) {
        if (tasks.isEmpty()) return
        val scored = ArrayList<Triple<TaskPriority, String, suspend () -> Unit>>(tasks.size)
        for ((id, block) in tasks) {
            scored += Triple(effectiveTaskPriority(id), id, block)
        }
        val ordered = scored.sortedWith(compareByDescending { it.first.ordinal }).map { it.third }
        launchParallelCpu(ordered)
    }

    /**
     * Polls [HostResourceSignalProvider] on [kotlinx.coroutines.Dispatchers.Default] (never Main).
     */
    suspend fun startResourceMonitoring(scope: CoroutineScope, provider: HostResourceSignalProvider)

    fun stopResourceMonitoring()

    /**
     * Parallel IO-shaped work; **must** use scheduler-controlled IO dispatcher (not raw [Dispatchers.IO] at call sites).
     */
    suspend fun launchParallelIo(blocks: List<suspend () -> Unit>)

    /**
     * Parallel CPU-shaped work; **must** use scheduler-controlled Default dispatcher slice.
     */
    suspend fun launchParallelCpu(blocks: List<suspend () -> Unit>)

    /**
     * Cancels outstanding parallel batches launched via [launchParallelIo] / [launchParallelCpu] (child scope),
     * but does **not** stop resource monitoring unless combined with [stopResourceMonitoring] by policy.
     */
    suspend fun cancelAllRegistered()
}
