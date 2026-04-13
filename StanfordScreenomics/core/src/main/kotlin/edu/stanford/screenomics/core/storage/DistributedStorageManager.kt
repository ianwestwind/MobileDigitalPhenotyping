package edu.stanford.screenomics.core.storage

import edu.stanford.screenomics.core.unified.UnifiedDataPoint

/**
 * Layer-2 **distributed** persistence: Firestore (structured), Cloud Storage (media), RTDB (structured),
 * plus per-modality local files — **parallel** to edge computation (separate coroutine scope / dispatchers).
 */
interface DistributedStorageManager {

    /**
     * Invoked after cache commit (typically from [edu.stanford.screenomics.core.module.template.ModulePipelineHooks]).
     */
    suspend fun onUnifiedPointCommitted(point: UnifiedDataPoint)

    fun shutdown()
}
