package com.app.modules.audio

import edu.stanford.screenomics.core.module.template.BaseCache
import edu.stanford.screenomics.core.unified.ModalityKind
import edu.stanford.screenomics.core.unified.UnifiedDataPoint

/**
 * Gradle module `:modules:audio` — modality-scoped volatile cache with default **30-minute sliding-window TTL**
 * on [edu.stanford.screenomics.core.unified.DataDescription.timestamp] (see [BaseCache]).
 */
class AudioCache(
    cacheId: String = "default-audio-cache",
    onAfterUnifiedPointCommittedOutsideLock: suspend (UnifiedDataPoint) -> Unit = {},
) : BaseCache(
    cacheId = cacheId,
    modality = ModalityKind.AUDIO,
    onAfterUnifiedPointCommittedOutsideLock = onAfterUnifiedPointCommittedOutsideLock,
)
