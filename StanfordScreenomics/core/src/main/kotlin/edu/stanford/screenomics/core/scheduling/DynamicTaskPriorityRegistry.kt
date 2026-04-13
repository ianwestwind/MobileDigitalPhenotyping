package edu.stanford.screenomics.core.scheduling

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Thread-safe registry of task ids → declared base priority, with **effective** priority derived from
 * the latest [ResourceStressLevel] (heavier demotion under worse stress).
 */
class DynamicTaskPriorityRegistry {

    private val mutex = Mutex()
    private val baseById = linkedMapOf<String, TaskPriority>()
    private var lastLevel: ResourceStressLevel = ResourceStressLevel.NORMAL

    suspend fun setStressLevel(level: ResourceStressLevel) {
        mutex.withLock { lastLevel = level }
    }

    suspend fun register(taskId: String, basePriority: TaskPriority) {
        mutex.withLock {
            baseById[taskId] = basePriority
        }
    }

    suspend fun unregister(taskId: String) {
        mutex.withLock {
            baseById.remove(taskId)
        }
    }

    suspend fun effectivePriority(taskId: String): TaskPriority {
        mutex.withLock {
            val base = baseById[taskId] ?: return TaskPriority.NORMAL
            return demote(base, lastLevel)
        }
    }

    suspend fun snapshotEffectivePriorities(): Map<String, TaskPriority> {
        mutex.withLock {
            return baseById.mapValues { (_, base) -> demote(base, lastLevel) }
        }
    }

    private fun demote(base: TaskPriority, level: ResourceStressLevel): TaskPriority = when (level) {
        ResourceStressLevel.NORMAL -> base
        ResourceStressLevel.STRESSED -> when (base) {
            TaskPriority.HIGH -> TaskPriority.NORMAL
            TaskPriority.NORMAL -> TaskPriority.NORMAL
            TaskPriority.LOW -> TaskPriority.LOW
        }
        ResourceStressLevel.CRITICAL -> when (base) {
            TaskPriority.HIGH -> TaskPriority.LOW
            TaskPriority.NORMAL -> TaskPriority.LOW
            TaskPriority.LOW -> TaskPriority.LOW
        }
    }
}
