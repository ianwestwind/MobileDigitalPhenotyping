package com.app.modules.motion

import edu.stanford.screenomics.core.module.template.BaseCache
import edu.stanford.screenomics.core.unified.ModalityKind
import edu.stanford.screenomics.core.unified.UnifiedDataPoint

/**
 * Gradle module `:modules:motion` — modality-scoped volatile cache with default **30-minute sliding-window TTL**
 * (see [BaseCache]).
 */
class MotionCache(
    cacheId: String = "default-motion-cache",
    onAfterUnifiedPointCommittedOutsideLock: suspend (UnifiedDataPoint) -> Unit = {},
) : BaseCache(
    cacheId = cacheId,
    modality = ModalityKind.MOTION,
    onAfterUnifiedPointCommittedOutsideLock = onAfterUnifiedPointCommittedOutsideLock,
)
