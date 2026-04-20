package edu.stanford.screenomics.core.storage

import edu.stanford.screenomics.core.unified.ModalityKind
import edu.stanford.screenomics.core.unified.UnifiedDataPoint

/**
 * Selects which MOTION [UnifiedDataPoint]s skip structured Firestore/RTDB when
 * [BatchUploadPolicyPlaceholder.pauseMotionFirestoreUpload] is enabled. IMU tier‑2 files live under
 * `motion/imu/`; step rollups under `motion/steps/`. Step/minute aggregates are never paused here for cloud.
 *
 * Values must stay aligned with [com.app.modules.motion.MotionAdapter] payload keys and acquisition labels.
 */
object MotionStructuredCloudUploadSelectors {

    const val ACQUISITION_METHOD_ACCEL: String = "motion.accelerometer.continuous"
    const val ACQUISITION_METHOD_GYRO: String = "motion.gyroscope.continuous"
    const val ACQUISITION_METHOD_STEP_MINUTE: String = "motion.step.minute_window"

    /** Same key as [com.app.modules.motion.MotionAdapter] — only minute rollup points carry this in the volatile tier. */
    const val DATA_KEY_STEP_SESSION: String = "motion.step.sessionTotal"

    /** Same keys as [com.app.modules.motion.MotionAdapter] (detect IMU rows even if metadata drifts). */
    private const val DATA_KEY_ACCEL_X: String = "motion.imu.accel.xMs2"
    private const val DATA_KEY_GYRO_X: String = "motion.imu.gyro.xRadS"

    fun isMotionAccelOrGyroAcquisition(acquisitionMethod: String): Boolean =
        acquisitionMethod == ACQUISITION_METHOD_ACCEL || acquisitionMethod == ACQUISITION_METHOD_GYRO

    fun motionPayloadHasAccelOrGyroData(data: Map<String, *>): Boolean =
        data.containsKey(DATA_KEY_ACCEL_X) || data.containsKey(DATA_KEY_GYRO_X)

    /**
     * When true, structured cloud upload for this **accel/gyro** point is skipped (payload has IMU keys or
     * acquisition is continuous accel/gyro). Step/minute points (no IMU keys, minute_window) still upload.
     * While paused, [DefaultDistributedStorageManager] queues the structured map under `motion/pending_firestore/`
     * and still writes a tier‑2 local `motion_*.json` artifact (IMU never enters the 30‑minute volatile motion cache).
     */
    fun shouldPauseStructuredCloudForMotionPoint(point: UnifiedDataPoint): Boolean {
        if (point.metadata.modality != ModalityKind.MOTION) return false
        if (!BatchUploadPolicyPlaceholder.pauseMotionFirestoreUpload) return false
        if (motionPayloadHasAccelOrGyroData(point.data)) return true
        return isMotionAccelOrGyroAcquisition(point.metadata.acquisitionMethod)
    }

    fun structuredDocumentAcquisitionMethod(fields: Map<String, Any?>): String? {
        @Suppress("UNCHECKED_CAST")
        val meta = fields["metadata"] as? Map<String, Any?> ?: return null
        return meta["acquisitionMethod"] as? String
    }

    fun isMotionAccelOrGyroStructuredDocument(fields: Map<String, Any?>): Boolean {
        @Suppress("UNCHECKED_CAST")
        val meta = fields["metadata"] as? Map<String, Any?> ?: return false
        if (meta["modality"] as? String != ModalityKind.MOTION.name) return false
        @Suppress("UNCHECKED_CAST")
        val data = fields["data"] as? Map<String, *> ?: return false
        if (motionPayloadHasAccelOrGyroData(data)) return true
        val acq = meta["acquisitionMethod"] as? String ?: return false
        return isMotionAccelOrGyroAcquisition(acq)
    }

    /** Android Firestore bridge: skip `set` when pause is on and payload is motion accel/gyro only. */
    fun shouldSkipFirestoreForStructuredFields(fields: Map<String, Any?>): Boolean =
        BatchUploadPolicyPlaceholder.pauseMotionFirestoreUpload &&
            isMotionAccelOrGyroStructuredDocument(fields)
}
