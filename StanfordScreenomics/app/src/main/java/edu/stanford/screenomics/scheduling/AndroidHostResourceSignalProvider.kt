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
import kotlin.jvm.Synchronized
import kotlin.math.max
import kotlin.math.min

/**
 * Android [HostResourceSignalProvider]: [ActivityManager.MemoryInfo.availMem], battery capacity (API 21+),
 * and coarse process CPU from [Process.getElapsedCpuTime] deltas (API 26+), else [ProcStatCpuSamplingPlaceholder].
 * [HostResourceSnapshot.processCpuLoad01] is always in **[0, 1]** (heuristic utilization for this process).
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

    private var lastWallMs: Long = SystemClock.elapsedRealtime()
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
            totalMemoryBytes = mem.totalMem,
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

    @Synchronized
    private fun resolveCpuLoad01(): Double {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nowWall = SystemClock.elapsedRealtime()
            val nowCpu = Process.getElapsedCpuTime()
            val wallDelta = (nowWall - lastWallMs).coerceAtLeast(1L)
            val cpuDelta = nowCpu - lastCpuMs
            lastWallMs = nowWall
            lastCpuMs = nowCpu
            if (cpuDelta < 0L) {
                return 0.0
            }
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val r = cpuDelta.toDouble() / wallDelta.toDouble() / cores.toDouble()
            if (!r.isFinite()) {
                return 0.0
            }
            return max(0.0, min(r, 1.0))
        }
        val p = ProcStatCpuSamplingPlaceholder.sampleProcessCpu01Placeholder()
        val out = when {
            p.isNaN() || p.isInfinite() -> 0.0
            else -> max(0.0, min(p, 1.0))
        }
        return if (out.isFinite()) out else 0.0
    }
}
