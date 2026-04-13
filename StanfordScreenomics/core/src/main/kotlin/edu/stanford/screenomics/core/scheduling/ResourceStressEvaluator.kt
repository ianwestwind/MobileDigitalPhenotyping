package edu.stanford.screenomics.core.scheduling

/**
 * Maps [HostResourceSnapshot] into discrete [ResourceStressSignals] and [ResourceStressLevel].
 */
object ResourceStressEvaluator {

    fun signals(snapshot: HostResourceSnapshot, thresholds: ResourceStressThresholds): ResourceStressSignals {
        val cpuHot = snapshot.processCpuLoad01 > thresholds.cpuLoadStrictlyAbove
        val memoryLow = snapshot.availableMemoryBytes < thresholds.availableMemoryStrictlyBelowBytes
        val batteryLow = !snapshot.batteryFraction.isNaN() &&
            snapshot.batteryFraction < thresholds.batteryFractionStrictlyBelow
        return ResourceStressSignals(cpuHot = cpuHot, memoryLow = memoryLow, batteryLow = batteryLow)
    }

    fun level(signals: ResourceStressSignals): ResourceStressLevel = when (signals.activeCount()) {
        0 -> ResourceStressLevel.NORMAL
        1 -> ResourceStressLevel.STRESSED
        else -> ResourceStressLevel.CRITICAL
    }
}
