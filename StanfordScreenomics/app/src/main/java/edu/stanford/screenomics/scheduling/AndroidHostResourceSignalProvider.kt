package edu.stanford.screenomics.scheduling

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import edu.stanford.screenomics.core.scheduling.HostResourceSignalProvider
import edu.stanford.screenomics.core.scheduling.HostResourceSnapshot
import edu.stanford.screenomics.core.scheduling.ProcStatCpuSamplingPlaceholder
import kotlin.math.min

/**
 * Android [HostResourceSignalProvider]: [ActivityManager.MemoryInfo.availMem], battery capacity (API 21+),
 * and coarse process CPU from [Process.getElapsedCpuTime] deltas (API 26+), else [ProcStatCpuSamplingPlaceholder].
 */
class AndroidHostResourceSignalProvider(
    appContext: Context,
) : HostResourceSignalProvider {

    private val applicationContext = appContext.applicationContext
    private val activityManager =
        applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val batteryManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            applicationContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        } else {
            null
        }

    @Volatile
    private var lastWallMs: Long = SystemClock.elapsedRealtime()

    @Volatile
    private var lastCpuMs: Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Process.getElapsedCpuTime()
    } else {
        0L
    }

    override suspend fun currentSnapshot(): HostResourceSnapshot {
        val mem = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(mem)

        return HostResourceSnapshot(
            processCpuLoad01 = resolveCpuLoad01(),
            availableMemoryBytes = mem.availMem,
            batteryFraction = resolveBatteryFraction(),
        )
    }

    private fun resolveBatteryFraction(): Double =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && batteryManager != null) {
            val raw = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (raw in 0..100) raw / 100.0 else Double.NaN
        } else {
            Double.NaN
        }

    private fun resolveCpuLoad01(): Double {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nowWall = SystemClock.elapsedRealtime()
            val nowCpu = Process.getElapsedCpuTime()
            val wallDelta = (nowWall - lastWallMs).coerceAtLeast(1L)
            val cpuDelta = nowCpu - lastCpuMs
            lastWallMs = nowWall
            lastCpuMs = nowCpu
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val r = cpuDelta.toDouble() / wallDelta.toDouble() / cores.toDouble()
            return min(r, 1.5)
        }
        val p = ProcStatCpuSamplingPlaceholder.sampleProcessCpu01Placeholder()
        return if (p.isNaN()) 0.0 else p
    }
}
