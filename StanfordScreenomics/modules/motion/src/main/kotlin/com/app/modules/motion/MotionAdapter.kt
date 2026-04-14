package com.app.modules.motion

import edu.stanford.screenomics.core.debug.PipelineDiagnosticsFormat
import edu.stanford.screenomics.core.debug.PipelineDiagnosticsRegistry
import edu.stanford.screenomics.core.debug.PipelineLogTags
import edu.stanford.screenomics.core.collection.MotionAccelRawFrame
import edu.stanford.screenomics.core.collection.MotionGyroRawFrame
import edu.stanford.screenomics.core.collection.MotionStepMinuteTickFrame
import edu.stanford.screenomics.core.collection.RawModalityFrame
import edu.stanford.screenomics.core.module.template.BaseAdapter
import edu.stanford.screenomics.core.unified.DataDescription
import edu.stanford.screenomics.core.unified.ModalityKind
import edu.stanford.screenomics.core.unified.UfsEnvelopeVersions
import edu.stanford.screenomics.core.unified.UfsReservedDataKeys
import edu.stanford.screenomics.core.unified.UnifiedDataPoint
import java.time.Instant
import kotlin.math.sqrt
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val KEY_ACX: String = "motion.imu.accel.xMs2"
private const val KEY_ACY: String = "motion.imu.accel.yMs2"
private const val KEY_ACZ: String = "motion.imu.accel.zMs2"
private const val KEY_GX: String = "motion.imu.gyro.xRadS"
private const val KEY_GY: String = "motion.imu.gyro.yRadS"
private const val KEY_GZ: String = "motion.imu.gyro.zRadS"
private const val KEY_EVENT_TS_NS: String = "motion.sensor.eventTimestampNanos"
private const val KEY_STEP_MINUTE: String = "motion.step.minuteLast"
private const val KEY_STEP_SESSION: String = "motion.step.sessionTotal"

private const val ACQUISITION_ACCEL: String = "motion.accelerometer.continuous"
private const val ACQUISITION_GYRO: String = "motion.gyroscope.continuous"
private const val ACQUISITION_STEP_MINUTE: String = "motion.step.minute_window"

/**
 * Gradle module `:modules:motion` — [BaseAdapter] with three UFS shapes:
 * 1) accelerometer + metadata (continuous while movement is active),
 * 2) gyroscope + metadata (continuous while movement is active),
 * 3) steps in the last minute + session total + metadata (on a fixed cadence).
 *
 * Fused step detection runs on accelerometer samples using the latest gyroscope sample held in memory.
 */
class MotionAdapter(
    adapterId: String,
    private val captureSessionId: String,
    private val producerNodeId: String,
    private val sourceLabel: String = "android.hardware.SensorManager",
    private val ufsEnvelopeVersion: String = UfsEnvelopeVersions.V1,
) : BaseAdapter(adapterId = adapterId, modality = ModalityKind.MOTION) {

    private val fusionMutex = Mutex()

    private var lastGyroX: Float = 0f
    private var lastGyroY: Float = 0f
    private var lastGyroZ: Float = 0f
    private var gyroSampleSeen: Boolean = false

    private var gravityEma: Double = 9.81
    private var highPassPrev: Double = 0.0
    private var highPassPrev2: Double = 0.0
    private var lastStepTimestampNs: Long = Long.MIN_VALUE
    private var sessionAcceptedStepCount: Long = 0L
    private var sessionCountAtMinuteWindowStart: Long = 0L

    override suspend fun adaptRaw(raw: RawModalityFrame): UnifiedDataPoint =
        when (raw) {
            is MotionAccelRawFrame -> adaptAccel(raw)
            is MotionGyroRawFrame -> adaptGyro(raw)
            is MotionStepMinuteTickFrame -> adaptMinute(raw)
            else -> error("unsupported RawModalityFrame for motion: ${raw::class.simpleName}")
        }

    private suspend fun adaptGyro(raw: MotionGyroRawFrame): UnifiedDataPoint {
        fusionMutex.withLock {
            lastGyroX = raw.gyroXRadS
            lastGyroY = raw.gyroYRadS
            lastGyroZ = raw.gyroZRadS
            gyroSampleSeen = raw.gyroAvailable
        }
        val gx = raw.gyroXRadS.toDouble()
        val gy = raw.gyroYRadS.toDouble()
        val gz = raw.gyroZRadS.toDouble()
        val gMag = if (raw.gyroAvailable) sqrt(gx * gx + gy * gy + gz * gz) else -1.0
        PipelineDiagnosticsRegistry.emit(
            logTag = PipelineLogTags.ADAPTER,
            module = ModalityKind.MOTION,
            stage = "adapter_gyro",
            dataType = "gyroscope",
            detail = "[ADAPTER][MOTION_MODULE] gyro xyz=(${raw.gyroXRadS},${raw.gyroYRadS},${raw.gyroZRadS}) " +
                "gyroMagRadS=${"%.4f".format(gMag)} eventNs=${raw.eventTimestampNanos}",
        )
        return buildGyroPoint(raw, gx, gy, gz)
    }

    private suspend fun adaptAccel(raw: MotionAccelRawFrame): UnifiedDataPoint {
        val sessionTotal = fusionMutex.withLock {
            val gx = lastGyroX
            val gy = lastGyroY
            val gz = lastGyroZ
            val gyroAvail = gyroSampleSeen
            computeStepOnAccel(
                timestampNanos = raw.eventTimestampNanos,
                ax = raw.accelXMs2,
                ay = raw.accelYMs2,
                az = raw.accelZMs2,
                gx = gx,
                gy = gy,
                gz = gz,
                gyroAvailable = gyroAvail,
            )
            sessionAcceptedStepCount
        }
        PipelineDiagnosticsRegistry.emit(
            logTag = PipelineLogTags.ADAPTER,
            module = ModalityKind.MOTION,
            stage = "adapter_accel",
            dataType = "accelerometer",
            detail = "[ADAPTER][MOTION_MODULE] accel xyz=(${raw.accelXMs2},${raw.accelYMs2},${raw.accelZMs2}) " +
                "stepSession=$sessionTotal eventNs=${raw.eventTimestampNanos}",
        )
        return buildAccelPoint(
            raw = raw,
            acx = raw.accelXMs2.toDouble(),
            acy = raw.accelYMs2.toDouble(),
            acz = raw.accelZMs2.toDouble(),
            eventTimestampNanos = raw.eventTimestampNanos,
            acquisitionMethod = ACQUISITION_ACCEL,
        )
    }

    private suspend fun adaptMinute(raw: MotionStepMinuteTickFrame): UnifiedDataPoint {
        val (minuteLast, sessionTotal) = fusionMutex.withLock {
            val minute = (sessionAcceptedStepCount - sessionCountAtMinuteWindowStart).coerceAtLeast(0L)
            sessionCountAtMinuteWindowStart = sessionAcceptedStepCount
            val total = sessionAcceptedStepCount
            minute to total
        }
        PipelineDiagnosticsRegistry.emit(
            logTag = PipelineLogTags.ADAPTER,
            module = ModalityKind.MOTION,
            stage = "adapter_step_minute",
            dataType = "step_minute_window",
            detail = "[ADAPTER][MOTION_MODULE] minuteLast=$minuteLast sessionTotal=$sessionTotal",
        )
        return buildMinutePoint(raw, minuteLast, sessionTotal)
    }

    /**
     * Fused step detector: accel high-pass peak and gyro magnitude gate; increments [sessionAcceptedStepCount]
     * when a step is accepted (refractory [MIN_STEP_INTERVAL_NS]). Call only under [fusionMutex].
     */
    private fun computeStepOnAccel(
        timestampNanos: Long,
        ax: Float,
        ay: Float,
        az: Float,
        gx: Float,
        gy: Float,
        gz: Float,
        gyroAvailable: Boolean,
    ) {
        val mag = sqrt((ax * ax + ay * ay + az * az).toDouble())
        gravityEma = 0.96 * gravityEma + 0.04 * mag
        val hp = mag - gravityEma
        val accelPeak = hp < highPassPrev &&
            highPassPrev > highPassPrev2 &&
            highPassPrev > STEP_HP_THRESHOLD

        val gMag = sqrt((gx * gx + gy * gy + gz * gz).toDouble())
        val rotationGated = gyroAvailable &&
            gMag > ROTATION_THRESHOLD_LOW &&
            gMag < ROTATION_THRESHOLD_HIGH

        if (accelPeak && rotationGated) {
            val elapsed = timestampNanos - lastStepTimestampNs
            val allow = lastStepTimestampNs == Long.MIN_VALUE || elapsed >= MIN_STEP_INTERVAL_NS
            if (allow) {
                lastStepTimestampNs = timestampNanos
                sessionAcceptedStepCount++
            }
        }
        highPassPrev2 = highPassPrev
        highPassPrev = hp
    }

    private fun buildAccelPoint(
        raw: RawModalityFrame,
        acx: Double,
        acy: Double,
        acz: Double,
        eventTimestampNanos: Long,
        acquisitionMethod: String,
    ): UnifiedDataPoint {
        val monoNanos = System.nanoTime()
        val correlationString = raw.correlationId.value
        val data = linkedMapOf<String, Any>(
            UfsReservedDataKeys.CORRELATION_ID to correlationString,
            UfsReservedDataKeys.MONOTONIC_TIMESTAMP_NANOS to monoNanos,
            KEY_EVENT_TS_NS to eventTimestampNanos,
            KEY_ACX to acx.coerceIn(-80.0, 80.0),
            KEY_ACY to acy.coerceIn(-80.0, 80.0),
            KEY_ACZ to acz.coerceIn(-80.0, 80.0),
        )
        val metadata = DataDescription(
            source = sourceLabel,
            timestamp = Instant.ofEpochMilli(raw.capturedAtEpochMillis),
            acquisitionMethod = acquisitionMethod,
            modality = ModalityKind.MOTION,
            captureSessionId = captureSessionId,
            producerNodeId = producerNodeId,
            producerAdapterId = adapterId,
            ufsEnvelopeVersion = ufsEnvelopeVersion,
        )
        PipelineDiagnosticsRegistry.emit(
            logTag = PipelineLogTags.ADAPTER,
            module = ModalityKind.MOTION,
            stage = "adapter_transform_metadata",
            dataType = "motion_accel",
            detail = "[ADAPTER][MOTION_MODULE] metadata={ ${PipelineDiagnosticsFormat.dataDescription(metadata)} }",
        )
        return UnifiedDataPoint(data = data, metadata = metadata)
    }

    private fun buildGyroPoint(
        raw: MotionGyroRawFrame,
        gx: Double,
        gy: Double,
        gz: Double,
    ): UnifiedDataPoint {
        val monoNanos = System.nanoTime()
        val correlationString = raw.correlationId.value
        val data = linkedMapOf<String, Any>(
            UfsReservedDataKeys.CORRELATION_ID to correlationString,
            UfsReservedDataKeys.MONOTONIC_TIMESTAMP_NANOS to monoNanos,
            KEY_EVENT_TS_NS to raw.eventTimestampNanos,
            KEY_GX to gx.coerceIn(-40.0, 40.0),
            KEY_GY to gy.coerceIn(-40.0, 40.0),
            KEY_GZ to gz.coerceIn(-40.0, 40.0),
        )
        val metadata = DataDescription(
            source = sourceLabel,
            timestamp = Instant.ofEpochMilli(raw.capturedAtEpochMillis),
            acquisitionMethod = ACQUISITION_GYRO,
            modality = ModalityKind.MOTION,
            captureSessionId = captureSessionId,
            producerNodeId = producerNodeId,
            producerAdapterId = adapterId,
            ufsEnvelopeVersion = ufsEnvelopeVersion,
        )
        PipelineDiagnosticsRegistry.emit(
            logTag = PipelineLogTags.ADAPTER,
            module = ModalityKind.MOTION,
            stage = "adapter_transform_metadata",
            dataType = "motion_gyro",
            detail = "[ADAPTER][MOTION_MODULE] metadata={ ${PipelineDiagnosticsFormat.dataDescription(metadata)} }",
        )
        return UnifiedDataPoint(data = data, metadata = metadata)
    }

    private fun buildMinutePoint(
        raw: MotionStepMinuteTickFrame,
        minuteLast: Long,
        sessionTotal: Long,
    ): UnifiedDataPoint {
        val monoNanos = System.nanoTime()
        val correlationString = raw.correlationId.value
        val data = linkedMapOf<String, Any>(
            UfsReservedDataKeys.CORRELATION_ID to correlationString,
            UfsReservedDataKeys.MONOTONIC_TIMESTAMP_NANOS to monoNanos,
            KEY_STEP_MINUTE to minuteLast.coerceIn(0L, 50_000L),
            KEY_STEP_SESSION to sessionTotal.coerceIn(0L, 50_000_000L),
        )
        val metadata = DataDescription(
            source = sourceLabel,
            timestamp = Instant.ofEpochMilli(raw.capturedAtEpochMillis),
            acquisitionMethod = ACQUISITION_STEP_MINUTE,
            modality = ModalityKind.MOTION,
            captureSessionId = captureSessionId,
            producerNodeId = producerNodeId,
            producerAdapterId = adapterId,
            ufsEnvelopeVersion = ufsEnvelopeVersion,
        )
        PipelineDiagnosticsRegistry.emit(
            logTag = PipelineLogTags.ADAPTER,
            module = ModalityKind.MOTION,
            stage = "adapter_transform_metadata",
            dataType = "motion_step_minute",
            detail = "[ADAPTER][MOTION_MODULE] minuteLast=$minuteLast sessionTotal=$sessionTotal " +
                "metadata={ ${PipelineDiagnosticsFormat.dataDescription(metadata)} }",
        )
        return UnifiedDataPoint(data = data, metadata = metadata)
    }

    private companion object {
        private const val STEP_HP_THRESHOLD: Double = 0.38
        private const val MIN_STEP_INTERVAL_NS: Long = 280_000_000L
        private const val ROTATION_THRESHOLD_LOW: Double = 0.05
        private const val ROTATION_THRESHOLD_HIGH: Double = 2.2
    }
}
