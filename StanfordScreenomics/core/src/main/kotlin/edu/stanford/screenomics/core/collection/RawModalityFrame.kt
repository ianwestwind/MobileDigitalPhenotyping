package edu.stanford.screenomics.core.collection

import edu.stanford.screenomics.core.unified.CorrelationId

/**
 * Modality-typed raw capture container feeding [Adapter]. Parallel modalities use independent frame types.
 */
sealed interface RawModalityFrame {
    val correlationId: CorrelationId
    val capturedAtEpochMillis: Long
}

data class AudioRawCaptureFrame(
    override val correlationId: CorrelationId,
    override val capturedAtEpochMillis: Long,
    val pcmByteLengthPlaceholder: Long,
) : RawModalityFrame

/**
 * Live PCM ingress from [android.media.AudioRecord] (or equivalent); samples are a **copy** of the read buffer window.
 */
data class AudioPcmBufferRawFrame(
    override val correlationId: CorrelationId,
    override val capturedAtEpochMillis: Long,
    val sampleRateHz: Int,
    val channelCount: Int,
    val audioEncoding: Int,
    val samples: ShortArray,
) : RawModalityFrame {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioPcmBufferRawFrame) return false
        if (correlationId != other.correlationId) return false
        if (capturedAtEpochMillis != other.capturedAtEpochMillis) return false
        if (sampleRateHz != other.sampleRateHz) return false
        if (channelCount != other.channelCount) return false
        if (audioEncoding != other.audioEncoding) return false
        if (!samples.contentEquals(other.samples)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = correlationId.hashCode()
        result = 31 * result + capturedAtEpochMillis.hashCode()
        result = 31 * result + sampleRateHz
        result = 31 * result + channelCount
        result = 31 * result + audioEncoding
        result = 31 * result + samples.contentHashCode()
        return result
    }
}

data class ScreenshotRawCaptureFrame(
    override val correlationId: CorrelationId,
    override val capturedAtEpochMillis: Long,
    val rasterByteLengthPlaceholder: Long,
) : RawModalityFrame

/**
 * Encoded raster window (typically PNG) from screen capture for OCR / downstream vision.
 */
data class ScreenshotRasterRawFrame(
    override val correlationId: CorrelationId,
    override val capturedAtEpochMillis: Long,
    val widthPx: Int,
    val heightPx: Int,
    val compressFormatAndroid: Int,
    val encodedBytes: ByteArray,
) : RawModalityFrame {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScreenshotRasterRawFrame) return false
        if (correlationId != other.correlationId) return false
        if (capturedAtEpochMillis != other.capturedAtEpochMillis) return false
        if (widthPx != other.widthPx) return false
        if (heightPx != other.heightPx) return false
        if (compressFormatAndroid != other.compressFormatAndroid) return false
        if (!encodedBytes.contentEquals(other.encodedBytes)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = correlationId.hashCode()
        result = 31 * result + capturedAtEpochMillis.hashCode()
        result = 31 * result + widthPx
        result = 31 * result + heightPx
        result = 31 * result + compressFormatAndroid
        result = 31 * result + encodedBytes.contentHashCode()
        return result
    }
}

data class GpsRawCaptureFrame(
    override val correlationId: CorrelationId,
    override val capturedAtEpochMillis: Long,
    val providerNamePlaceholder: String,
) : RawModalityFrame

/**
 * Single GNSS fix feeding [Adapter] before weather enrichment.
 */
data class GpsLocationRawFrame(
    override val correlationId: CorrelationId,
    override val capturedAtEpochMillis: Long,
    val latitudeDegrees: Double,
    val longitudeDegrees: Double,
    val horizontalAccuracyMeters: Float?,
    val providerName: String,
) : RawModalityFrame

data class MotionRawCaptureFrame(
    override val correlationId: CorrelationId,
    override val capturedAtEpochMillis: Long,
    val sensorTypePlaceholder: String,
) : RawModalityFrame

/**
 * Fused accelerometer + gyroscope sample (gyro may be absent on device; [gyroAvailable] indicates validity).
 */
data class MotionImuRawFrame(
    override val correlationId: CorrelationId,
    override val capturedAtEpochMillis: Long,
    val eventTimestampNanos: Long,
    val accelXMs2: Float,
    val accelYMs2: Float,
    val accelZMs2: Float,
    val gyroXRadS: Float,
    val gyroYRadS: Float,
    val gyroZRadS: Float,
    val gyroAvailable: Boolean,
) : RawModalityFrame

/** Accelerometer-only sample while movement is active (high-rate). */
data class MotionAccelRawFrame(
    override val correlationId: CorrelationId,
    override val capturedAtEpochMillis: Long,
    val eventTimestampNanos: Long,
    val accelXMs2: Float,
    val accelYMs2: Float,
    val accelZMs2: Float,
) : RawModalityFrame

/** Gyroscope-only sample while movement is active (high-rate). */
data class MotionGyroRawFrame(
    override val correlationId: CorrelationId,
    override val capturedAtEpochMillis: Long,
    val eventTimestampNanos: Long,
    val gyroXRadS: Float,
    val gyroYRadS: Float,
    val gyroZRadS: Float,
    val gyroAvailable: Boolean,
) : RawModalityFrame

/** Periodic tick (e.g. every 1 min) to emit step counts for the elapsed window. */
data class MotionStepMinuteTickFrame(
    override val correlationId: CorrelationId,
    override val capturedAtEpochMillis: Long,
) : RawModalityFrame
