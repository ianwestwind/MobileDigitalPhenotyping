package edu.stanford.screenomics.core.scheduling

/**
 * JVM/desktop placeholder provider: **not** representative of Android battery or system RAM pressure.
 * On Android, use the app-layer `AndroidHostResourceSignalProvider` (`:app` module).
 */
class JvmHostResourceSignalProvider : HostResourceSignalProvider {

    override suspend fun currentSnapshot(): HostResourceSnapshot {
        val rt = Runtime.getRuntime()
        val approxFree = rt.freeMemory() + (rt.maxMemory() - rt.totalMemory()).coerceAtLeast(0L)
        val maxMem = rt.maxMemory().coerceAtLeast(1L)
        return HostResourceSnapshot(
            processCpuLoad01 = 0.0,
            availableMemoryBytes = approxFree,
            totalMemoryBytes = maxMem,
            batteryFraction = 1.0,
        )
    }
}
