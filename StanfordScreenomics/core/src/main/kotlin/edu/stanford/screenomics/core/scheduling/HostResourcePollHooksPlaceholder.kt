package edu.stanford.screenomics.core.scheduling

/**
 * Post-poll extension seam (tracing, adaptive thresholds, remote feature flags).
 */
object HostResourcePollHooksPlaceholder {

    @Suppress("UNUSED_PARAMETER")
    suspend fun afterPoll(snapshot: HostResourceSnapshot, level: ResourceStressLevel) {
    }
}
