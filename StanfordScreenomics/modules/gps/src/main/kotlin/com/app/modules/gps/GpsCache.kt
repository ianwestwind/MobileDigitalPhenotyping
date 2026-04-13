package com.app.modules.gps

import edu.stanford.screenomics.core.module.template.BaseCache
import edu.stanford.screenomics.core.unified.ModalityKind
import edu.stanford.screenomics.core.unified.UnifiedDataPoint

/**
 * Gradle module `:modules:gps` — modality-scoped volatile cache with default **30-minute sliding-window TTL**
 * (see [BaseCache]).
 */
class GpsCache(
    cacheId: String = "default-gps-cache",
    onAfterUnifiedPointCommittedOutsideLock: suspend (UnifiedDataPoint) -> Unit = {},
) : BaseCache(
    cacheId = cacheId,
    modality = ModalityKind.GPS,
    onAfterUnifiedPointCommittedOutsideLock = onAfterUnifiedPointCommittedOutsideLock,
)
