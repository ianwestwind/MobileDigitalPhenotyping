package edu.stanford.screenomics.core.scheduling

/**
 * Aggregated scheduling tier derived from [ResourceStressSignals.activeCount].
 */
enum class ResourceStressLevel {
    NORMAL,
    STRESSED,
    CRITICAL,
}
