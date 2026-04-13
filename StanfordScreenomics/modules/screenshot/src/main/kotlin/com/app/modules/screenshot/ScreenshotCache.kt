package com.app.modules.screenshot

import edu.stanford.screenomics.core.module.template.BaseCache
import edu.stanford.screenomics.core.unified.ModalityKind
import edu.stanford.screenomics.core.unified.UnifiedDataPoint

/**
 * Gradle module `:modules:screenshot` — modality-scoped volatile cache with default **30-minute sliding-window TTL**
 * (see [BaseCache]).
 */
class ScreenshotCache(
    cacheId: String = "default-screenshot-cache",
    onAfterUnifiedPointCommittedOutsideLock: suspend (UnifiedDataPoint) -> Unit = {},
) : BaseCache(
    cacheId = cacheId,
    modality = ModalityKind.SCREENSHOT,
    onAfterUnifiedPointCommittedOutsideLock = onAfterUnifiedPointCommittedOutsideLock,
)
