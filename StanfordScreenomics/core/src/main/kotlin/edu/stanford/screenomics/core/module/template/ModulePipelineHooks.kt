package edu.stanford.screenomics.core.module.template

import edu.stanford.screenomics.core.collection.RawModalityFrame
import edu.stanford.screenomics.core.unified.UnifiedDataPoint

/**
 * Placeholder extension points for telemetry, policy, or auditing around pipeline stages.
 * Default no-ops keep the template reusable without sensor-specific logic.
 */
open class ModulePipelineHooks {
    open suspend fun onRawFrameObserved(raw: RawModalityFrame) {}
    open suspend fun onAdapted(point: UnifiedDataPoint) {}
    open suspend fun onCacheCommitted(point: UnifiedDataPoint) {}
}
