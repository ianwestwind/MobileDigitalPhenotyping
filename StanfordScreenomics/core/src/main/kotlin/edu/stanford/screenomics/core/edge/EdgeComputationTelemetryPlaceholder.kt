package edu.stanford.screenomics.core.edge

import edu.stanford.screenomics.core.management.InterventionDirective

/**
 * Reserved hook for exporting edge-cycle latency, model ids, and issued directives to telemetry sinks.
 */
object EdgeComputationTelemetryPlaceholder {

    @Suppress("UNUSED_PARAMETER")
    suspend fun afterCycle(
        recentPointCount: Int,
        directive: InterventionDirective,
    ) {
    }
}
