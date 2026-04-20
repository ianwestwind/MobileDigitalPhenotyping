package edu.stanford.screenomics.core.scheduling

/**
 * Point-in-time host signals used for adaptive scheduling.
 *
 * @property processCpuLoad01 Heuristic process utilization in \[0, 1\] (Android provider clamps to this range).
 * @property availableMemoryBytes **Free** or **available** memory per platform provider semantics (Android: [android.app.ActivityManager.MemoryInfo.availMem]).
 * @property totalMemoryBytes Total device RAM on Android ([android.app.ActivityManager.MemoryInfo.totalMem]); JVM placeholder uses [Runtime.maxMemory].
 * @property batteryFraction State of charge in \[0, 1\], or [Double.NaN] if unknown.
 */
data class HostResourceSnapshot(
    val processCpuLoad01: Double,
    val availableMemoryBytes: Long,
    val totalMemoryBytes: Long,
    val batteryFraction: Double,
) {
    companion object {
        fun placeholderHealthy(): HostResourceSnapshot {
            val fourGiB = 4L * 1024L * 1024L * 1024L
            val eightGiB = 8L * 1024L * 1024L * 1024L
            return HostResourceSnapshot(
                processCpuLoad01 = 0.0,
                availableMemoryBytes = fourGiB,
                totalMemoryBytes = eightGiB,
                batteryFraction = 1.0,
            )
        }
    }
}
