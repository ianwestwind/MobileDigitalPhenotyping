package edu.stanford.screenomics

import com.app.modules.audio.AudioCache
import com.app.modules.gps.GpsCache
import com.app.modules.motion.MotionCache
import com.app.modules.screenshot.ScreenshotCache
import edu.stanford.screenomics.core.edge.EdgeComputationEngine
import edu.stanford.screenomics.core.management.InterventionController
import edu.stanford.screenomics.core.management.LifecycleAwareCacheManager
import edu.stanford.screenomics.core.scheduling.TaskScheduler
import edu.stanford.screenomics.core.storage.DefaultDistributedStorageManager
import edu.stanford.screenomics.core.storage.ModalityLocalFileSink

/**
 * Process-wide handles for phenotyping so [ModalityCollectionService] shares the same caches and
 * storage graph as [MainActivity].
 */
data class PhenotypingRuntime(
    val captureSessionId: String,
    val audioCache: AudioCache,
    val motionCache: MotionCache,
    val gpsCache: GpsCache,
    val screenshotCache: ScreenshotCache,
    val modalityLocalFileSink: ModalityLocalFileSink,
    val distributedStorageManager: DefaultDistributedStorageManager,
    val cacheManager: LifecycleAwareCacheManager,
    val taskScheduler: TaskScheduler,
    val edgeComputationEngine: EdgeComputationEngine,
    val interventionController: InterventionController,
)
