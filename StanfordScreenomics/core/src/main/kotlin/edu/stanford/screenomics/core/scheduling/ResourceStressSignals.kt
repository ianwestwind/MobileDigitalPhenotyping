package edu.stanford.screenomics.core.scheduling

/**
 * Boolean stress indicators derived from [HostResourceSnapshot] + [ResourceStressThresholds].
 */
data class ResourceStressSignals(
    val cpuHot: Boolean,
    val memoryLow: Boolean,
    val batteryLow: Boolean,
) {
    fun activeCount(): Int = listOf(cpuHot, memoryLow, batteryLow).count { it }
}
