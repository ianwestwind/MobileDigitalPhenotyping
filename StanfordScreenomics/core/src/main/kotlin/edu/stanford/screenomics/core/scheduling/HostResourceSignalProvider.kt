package edu.stanford.screenomics.core.scheduling

/**
 * Platform hook supplying [HostResourceSnapshot] samples for [TaskScheduler] monitoring loops.
 */
fun interface HostResourceSignalProvider {
    suspend fun currentSnapshot(): HostResourceSnapshot
}
