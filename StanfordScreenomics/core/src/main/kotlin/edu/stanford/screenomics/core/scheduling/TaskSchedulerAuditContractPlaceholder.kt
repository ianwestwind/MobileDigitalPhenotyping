package edu.stanford.screenomics.core.scheduling

/**
 * Documented audit contract for [TaskScheduler] / [DefaultTaskScheduler] (no mutable global state).
 */
object TaskSchedulerAuditContractPlaceholder {

    const val INVARIANT_THRESHOLD_CPU: String =
        "CPU stress when snapshot.processCpuLoad01 > ResourceStressThresholds.cpuLoadStrictlyAbove (default 0.85)."

    const val INVARIANT_THRESHOLD_MEMORY: String =
        "Memory stress when snapshot.availableMemoryBytes < ResourceStressThresholds.availableMemoryStrictlyBelowBytes (default 200 MiB)."

    const val INVARIANT_THRESHOLD_BATTERY: String =
        "Battery stress when snapshot.batteryFraction is finite and < ResourceStressThresholds.batteryFractionStrictlyBelow (default 0.20)."

    const val INVARIANT_DYNAMIC_PRIORITY: String =
        "DynamicTaskPriorityRegistry demotes effective TaskPriority from base by ResourceStressLevel; optional prioritized parallel launch uses effective order."

    const val INVARIANT_PARALLEL_LAUNCH: String =
        "launchParallelIo / launchParallelCpu use coroutineScope + async + awaitAll (concurrent child coroutines), not sequential forEach."
}
