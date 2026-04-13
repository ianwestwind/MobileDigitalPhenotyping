package edu.stanford.screenomics.core.scheduling

/**
 * Product thresholds for **resource stress** (any breach contributes to elevated scheduling tier).
 */
data class ResourceStressThresholds(
    /** Stress when **process** CPU heuristic is **strictly greater** than this value (e.g. 0.85 = 85%). */
    val cpuLoadStrictlyAbove: Double = 0.85,
    /** Stress when available memory is **strictly below** this many bytes (e.g. 200 MiB). */
    val availableMemoryStrictlyBelowBytes: Long = 200L * 1024L * 1024L,
    /** Stress when battery fraction is **strictly below** this value (e.g. 0.20 = 20%). */
    val batteryFractionStrictlyBelow: Double = 0.20,
) {
    init {
        require(cpuLoadStrictlyAbove in 0.0..1.5) { "cpuLoadStrictlyAbove out of sensible range" }
        require(availableMemoryStrictlyBelowBytes > 0L) { "memory threshold must be positive" }
        require(batteryFractionStrictlyBelow in 0.0..1.0) { "battery threshold must be 0..1" }
    }

    companion object {
        val DEFAULT: ResourceStressThresholds = ResourceStressThresholds()
    }
}
