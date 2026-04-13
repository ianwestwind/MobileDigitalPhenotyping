package edu.stanford.screenomics.core.management

/**
 * Reserved extension point for post-resume behaviour (metrics, policy refresh, remote config).
 * Intentionally a no-op so lifecycle wiring stays complete without hiding a missing subsystem behind comments.
 */
object HostForegroundPolicyPlaceholder {

    @Suppress("UNUSED_PARAMETER")
    suspend fun onHostForegrounded(manager: CacheManager) {
        // Placeholder: correlate with capture session resume, tracing, etc.
    }
}
