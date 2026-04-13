package edu.stanford.screenomics.core.storage

/**
 * Canonical UFS **data** keys referenced by [DefaultDistributedStorageManager] for media/local routing.
 * Module adapters MUST use these exact strings when attaching compressible payloads for Layer-2 persistence.
 */
object DistributedStoragePayloadHints {
    const val AUDIO_DEFLATED_PCM_BASE64: String = "audio.media.deflatedPcmBase64"
    const val AUDIO_DEFLATED_SHA256_HEX: String = "audio.media.deflatedSha256Hex"

    const val SCREENSHOT_DEFLATED_RASTER_BASE64: String = "screenshot.media.deflatedRasterBase64"
    const val SCREENSHOT_DEFLATED_SHA256_HEX: String = "screenshot.media.deflatedRasterSha256Hex"

    const val MOTION_STEP_SESSION_TOTAL: String = "motion.step.sessionTotal"

    /** Minute-close rollup written alongside motion RTDB minute buckets (not necessarily on every IMU frame schema). */
    const val MOTION_STEP_SUM_PAST_MINUTE: String = "motion.step.sumPastMinute"
}
