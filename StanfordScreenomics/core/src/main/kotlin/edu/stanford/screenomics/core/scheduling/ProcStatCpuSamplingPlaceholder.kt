package edu.stanford.screenomics.core.scheduling

/**
 * Placeholder for `/proc`-based CPU sampling on Linux/Android when `Process.getElapsedCpuTime` is
 * unavailable or insufficient; keeps an explicit extension point without silently faking metrics.
 */
object ProcStatCpuSamplingPlaceholder {

    fun sampleProcessCpu01Placeholder(): Double = Double.NaN
}
