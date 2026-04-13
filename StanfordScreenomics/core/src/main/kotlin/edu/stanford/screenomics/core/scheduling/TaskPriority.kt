package edu.stanford.screenomics.core.scheduling

/**
 * Logical task priority; [DefaultTaskScheduler] may **demote** registered tasks under resource stress.
 */
enum class TaskPriority {
    LOW,
    NORMAL,
    HIGH,
}
