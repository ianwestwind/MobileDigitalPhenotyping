package com.app.modules.motion

import edu.stanford.screenomics.core.module.template.BaseCache
import edu.stanford.screenomics.core.storage.MotionStructuredCloudUploadSelectors
import edu.stanford.screenomics.core.unified.ModalityKind
import edu.stanford.screenomics.core.unified.UnifiedDataPoint

/**
 * Gradle module `:modules:motion` — **30‑minute volatile tier**: only **step/minute rollup** points
 * ([MotionStructuredCloudUploadSelectors.DATA_KEY_STEP_SESSION]) are retained in memory. Continuous IMU
 * (accel/gyro) never enters the [store]; they still invoke [onAfterUnifiedPointCommittedOutsideLock] for tier‑2
 * local JSON / structured cloud policy ([MotionStructuredCloudUploadSelectors]).
 */
class MotionCache(
    cacheId: String = "default-motion-cache",
    onAfterUnifiedPointCommittedOutsideLock: suspend (UnifiedDataPoint) -> Unit = {},
) : BaseCache(
    cacheId = cacheId,
    modality = ModalityKind.MOTION,
    onAfterUnifiedPointCommittedOutsideLock = onAfterUnifiedPointCommittedOutsideLock,
) {

    override fun shouldRetainPointInVolatileStore(point: UnifiedDataPoint): Boolean {
        if (MotionStructuredCloudUploadSelectors.motionPayloadHasAccelOrGyroData(point.data)) {
            return false
        }
        return point.data.containsKey(MotionStructuredCloudUploadSelectors.DATA_KEY_STEP_SESSION)
    }
}
