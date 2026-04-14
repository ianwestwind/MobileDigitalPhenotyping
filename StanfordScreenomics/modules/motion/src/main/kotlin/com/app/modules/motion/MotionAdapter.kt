package com.app.modules.motion

import edu.stanford.screenomics.core.debug.PipelineDiagnosticsFormat
import edu.stanford.screenomics.core.debug.PipelineDiagnosticsRegistry
import edu.stanford.screenomics.core.debug.PipelineLogTags
import edu.stanford.screenomics.core.collection.MotionImuRawFrame
import edu.stanford.screenomics.core.collection.MotionRawCaptureFrame
import edu.stanford.screenomics.core.collection.RawModalityFrame
import edu.stanford.screenomics.core.module.template.BaseAdapter
import edu.stanford.screenomics.core.unified.DataDescription
import edu.stanford.screenomics.core.unified.ModalityKind
import edu.stanford.screenomics.core.unified.UfsEnvelopeVersions
import edu.stanford.screenomics.core.unified.UfsReservedDataKeys
import edu.stanford.screenomics.core.unified.UnifiedDataPoint
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import kotlin.math.sqrt

private const val KEY_ACX: String = "motion.imu.accel.xMs2"
private const val KEY_ACY: String = "motion.imu.accel.yMs2"
private const val KEY_ACZ: String = "motion.imu.accel.zMs2"
private const val KEY_GX: String = "motion.imu.gyro.xRadS"
private const val KEY_GY: String = "motion.imu.gyro.yRadS"
private const val KEY_GZ: String = "motion.imu.gyro.zRadS"
private const val KEY_STEP_SESSION: String = "motion.step.sessionTotal"

/**
 * Gradle module `:modules:motion` — [BaseAdapter] with fused step detection; exposes only IMU axes and cumulative
 * [KEY_STEP_SESSION] on each point.
 */
class MotionAdapter(
    adapterId: String,
    private val captureSessionId: String,
    private val producerNodeId: String,
    private val sourceLabel: String = "android.hardware.SensorManager",
    private val acquisitionMethodLabel: String = "local.accelPeak+gyroRotationGate+UFS",
    private val ufsEnvelopeVersion: String = UfsEnvelopeVersions.V1,
) : BaseAdapter(adapterId = adapterId, modality = ModalityKind.MOTION) {

    private val stepMutex = Mutex()
    private var gravityEma: Double = 9.81
    private var highPassPrev: Double = 0.0
    private var highPassPrev2: Double = 0.0
    private var lastStepTimestampNs: Long = Long.MIN_VALUE
    private var sessionAcceptedStepCount: Long = 0L

    override suspend fun adaptRaw(raw: RawModalityFrame): UnifiedDataPoint =
        when (raw) {
            is MotionImuRawFrame -> adaptImu(raw)
            is MotionRawCaptureFrame -> adaptLegacyPlaceholder(raw)
            else -> error("unsupported RawModalityFrame for motion: ${raw::class.simpleName}")
        }

    private suspend fun adaptImu(raw: MotionImuRawFrame): UnifiedDataPoint {
        val gyroAvailable = raw.gyroAvailable
        val gxF = if (gyroAvailable) raw.gyroXRadS else 0f
        val gyF = if (gyroAvailable) raw.gyroYRadS else 0f
        val gzF = if (gyroAvailable) raw.gyroZRadS else 0f
        val sessionTotal = computeStepAndSessionTotal(
            timestampNanos = raw.eventTimestampNanos,
            ax = raw.accelXMs2,
            ay = raw.accelYMs2,
            az = raw.accelZMs2,
            gx = gxF,
            gy = gyF,
            gz = gzF,
            gyroAvailable = gyroAvailable,
        )
        val gx = gxF.toDouble()
        val gy = gyF.toDouble()
        val gz = gzF.toDouble()
        val gMag = if (gyroAvailable) sqrt((gx * gx + gy * gy + gz * gz)) else -1.0
        PipelineDiagnosticsRegistry.emit(
            logTag = PipelineLogTags.ADAPTER,
            module = ModalityKind.MOTION,
            stage = "adapter_imu_features",
            dataType = "accelerometer_gyro_step",
            detail = "[ADAPTER][MOTION_MODULE] Accelerometer xyz=(${raw.accelXMs2},${raw.accelYMs2},${raw.accelZMs2}) " +
                "gyroMagRadS=${"%.4f".format(gMag)} stepSession=$sessionTotal " +
                "eventNs=${raw.eventTimestampNanos} capturedAt=${raw.capturedAtEpochMillis}",
        )
        return buildPoint(
            raw = raw,
            acx = raw.accelXMs2.toDouble(),
            acy = raw.accelYMs2.toDouble(),
            acz = raw.accelZMs2.toDouble(),
            gx = gx,
            gy = gy,
            gz = gz,
            stepSession = sessionTotal,
        )
    }

    private suspend fun adaptLegacyPlaceholder(raw: MotionRawCaptureFrame): UnifiedDataPoint {
        val total = stepMutex.withLock { sessionAcceptedStepCount }
        if (raw.sensorTypePlaceholder == MOTION_IDLE_HEARTBEAT_PLACEHOLDER) {
            PipelineDiagnosticsRegistry.emit(
                logTag = PipelineLogTags.ADAPTER,
                module = ModalityKind.MOTION,
                stage = "adapter_idle_heartbeat",
                dataType = "implicit_zero_steps",
                detail = "[ADAPTER][MOTION_MODULE] Idle heartbeat stepSession=$total (no IMU sample)",
            )
        }
        return buildPoint(
            raw = raw,
            acx = 0.0,
            acy = 0.0,
            acz = 0.0,
            gx = 0.0,
            gy = 0.0,
            gz = 0.0,
            stepSession = total,
        )
    }

    /**
     * Fused step detector: accel high-pass peak and gyro magnitude gate; increments [sessionAcceptedStepCount]
     * when a step is accepted (refractory [MIN_STEP_INTERVAL_NS]).
     */
    private suspend fun computeStepAndSessionTotal(
        timestampNanos: Long,
        ax: Float,
        ay: Float,
        az: Float,
        gx: Float,
        gy: Float,
        gz: Float,
        gyroAvailable: Boolean,
    ): Long =
        stepMutex.withLock {
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
            sessionAcceptedStepCount
        }

    private fun buildPoint(
        raw: RawModalityFrame,
        acx: Double,
        acy: Double,
        acz: Double,
        gx: Double,
        gy: Double,
        gz: Double,
        stepSession: Long,
    ): UnifiedDataPoint {
        val monoNanos = System.nanoTime()
        val correlationString = raw.correlationId.value
        val data = linkedMapOf<String, Any>(
            UfsReservedDataKeys.CORRELATION_ID to correlationString,
            UfsReservedDataKeys.MONOTONIC_TIMESTAMP_NANOS to monoNanos,
            KEY_ACX to acx.coerceIn(-80.0, 80.0),
            KEY_ACY to acy.coerceIn(-80.0, 80.0),
            KEY_ACZ to acz.coerceIn(-80.0, 80.0),
            KEY_GX to gx.coerceIn(-40.0, 40.0),
            KEY_GY to gy.coerceIn(-40.0, 40.0),
            KEY_GZ to gz.coerceIn(-40.0, 40.0),
            KEY_STEP_SESSION to stepSession.coerceIn(0L, 50_000_000L),
        )
        val metadata = DataDescription(
            source = sourceLabel,
            timestamp = Instant.ofEpochMilli(raw.capturedAtEpochMillis),
            acquisitionMethod = acquisitionMethodLabel,
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
            dataType = "step_count",
            detail = "[ADAPTER][MOTION_MODULE] step_count=$stepSession metadata={ " +
                "${PipelineDiagnosticsFormat.dataDescription(metadata)} }",
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
