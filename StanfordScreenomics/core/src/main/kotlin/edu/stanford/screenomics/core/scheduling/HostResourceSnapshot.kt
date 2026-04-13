package edu.stanford.screenomics.core.scheduling

/**
 * Point-in-time host signals used for adaptive scheduling.
 *
 * @property processCpuLoad01 Heuristic load in \[0, ~1+\] (may exceed 1 on multi-core bursts depending on provider).
 * @property availableMemoryBytes **Free** or **available** memory per platform provider semantics (Android: [android.app.ActivityManager.MemoryInfo.availMem]).
 * @property batteryFraction State of charge in \[0, 1\], or [Double.NaN] if unknown.
 */
data class HostResourceSnapshot(
    val processCpuLoad01: Double,
    val availableMemoryBytes: Long,
    val batteryFraction: Double,
) {
    companion object {
        fun placeholderHealthy(): HostResourceSnapshot = HostResourceSnapshot(
            processCpuLoad01 = 0.0,
            availableMemoryBytes = Long.MAX_VALUE / 4,
            batteryFraction = 1.0,
        )
    }
}
