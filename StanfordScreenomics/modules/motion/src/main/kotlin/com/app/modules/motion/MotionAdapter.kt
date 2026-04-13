package com.app.modules.motion

import edu.stanford.screenomics.core.debug.PipelineDiagnosticsFormat
import edu.stanford.screenomics.core.debug.PipelineDiagnosticsRegistry
import edu.stanford.screenomics.core.debug.PipelineLogTags
import edu.stanford.screenomics.core.collection.MotionImuRawFrame
import edu.stanford.screenomics.core.collection.MotionRawCaptureFrame
import edu.stanford.screenomics.core.collection.RawModalityFrame
import edu.stanford.screenomics.core.module.template.BaseAdapter
import edu.stanford.screenomics.core.unified.DataDescription
import edu.stanford.screenomics.core.unified.DataEntity
import edu.stanford.screenomics.core.unified.EntityAttributeSpec
import edu.stanford.screenomics.core.unified.EntityRelationship
import edu.stanford.screenomics.core.unified.EntityTypeSpec
import edu.stanford.screenomics.core.unified.ModalityKind
import edu.stanford.screenomics.core.unified.NumericEntityValueRange
import edu.stanford.screenomics.core.unified.ProvenanceRecord
import edu.stanford.screenomics.core.unified.UfsEnvelopeVersions
import edu.stanford.screenomics.core.unified.UfsReservedDataKeys
import edu.stanford.screenomics.core.unified.UfsSchemaComposition
import edu.stanford.screenomics.core.unified.UnifiedDataPoint
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import kotlin.math.sqrt

private const val MOTION_SCHEMA_ID: String = "com.app.modules.motion/ufs-schema"
private const val MOTION_SCHEMA_REVISION: String = "2"

private const val KEY_ACX: String = "motion.imu.accel.xMs2"
private const val KEY_ACY: String = "motion.imu.accel.yMs2"
private const val KEY_ACZ: String = "motion.imu.accel.zMs2"
private const val KEY_GX: String = "motion.imu.gyro.xRadS"
private const val KEY_GY: String = "motion.imu.gyro.yRadS"
private const val KEY_GZ: String = "motion.imu.gyro.zRadS"
private const val KEY_GYRO_PRESENT: String = "motion.imu.gyroPresent"
private const val KEY_STEP_DELTA: String = "motion.step.deltaSinceLastFrame"
private const val KEY_STEP_SESSION: String = "motion.step.sessionTotal"

private fun buildMotionPayloadDataEntity(): DataEntity = UfsSchemaComposition.compose(
    schemaId = MOTION_SCHEMA_ID,
    schemaRevision = MOTION_SCHEMA_REVISION,
    payloadAttributes = mapOf(
        KEY_ACX to EntityAttributeSpec(true, "Accelerometer X (m/s²), device frame", "scalar.double"),
        KEY_ACY to EntityAttributeSpec(true, "Accelerometer Y (m/s²), device frame", "scalar.double"),
        KEY_ACZ to EntityAttributeSpec(true, "Accelerometer Z (m/s²), device frame", "scalar.double"),
        KEY_GX to EntityAttributeSpec(true, "Gyroscope X (rad/s); 0 when absent", "scalar.double"),
        KEY_GY to EntityAttributeSpec(true, "Gyroscope Y (rad/s); 0 when absent", "scalar.double"),
        KEY_GZ to EntityAttributeSpec(true, "Gyroscope Z (rad/s); 0 when absent", "scalar.double"),
        KEY_GYRO_PRESENT to EntityAttributeSpec(true, "1 if gyro samples are paired with this accel event", "scalar.long"),
        KEY_STEP_DELTA to EntityAttributeSpec(true, "Local peak-detection step increment for this frame (0 or 1)", "scalar.long"),
        KEY_STEP_SESSION to EntityAttributeSpec(true, "Monotonic session step count after local detector", "scalar.long"),
    ),
    payloadTypes = mapOf(
        KEY_ACX to EntityTypeSpec("motion.AccelX", "kotlin.Double", "1"),
        KEY_ACY to EntityTypeSpec("motion.AccelY", "kotlin.Double", "1"),
        KEY_ACZ to EntityTypeSpec("motion.AccelZ", "kotlin.Double", "1"),
        KEY_GX to EntityTypeSpec("motion.GyroX", "kotlin.Double", "1"),
        KEY_GY to EntityTypeSpec("motion.GyroY", "kotlin.Double", "1"),
        KEY_GZ to EntityTypeSpec("motion.GyroZ", "kotlin.Double", "1"),
        KEY_GYRO_PRESENT to EntityTypeSpec("motion.GyroPresent", "kotlin.Long", "1"),
        KEY_STEP_DELTA to EntityTypeSpec("motion.StepDelta", "kotlin.Long", "1"),
        KEY_STEP_SESSION to EntityTypeSpec("motion.StepSessionTotal", "kotlin.Long", "1"),
    ),
    payloadValueRanges = mapOf(
        KEY_ACX to NumericEntityValueRange(-80.0, 80.0, null),
        KEY_ACY to NumericEntityValueRange(-80.0, 80.0, null),
        KEY_ACZ to NumericEntityValueRange(-80.0, 80.0, null),
        KEY_GX to NumericEntityValueRange(-40.0, 40.0, null),
        KEY_GY to NumericEntityValueRange(-40.0, 40.0, null),
        KEY_GZ to NumericEntityValueRange(-40.0, 40.0, null),
        KEY_GYRO_PRESENT to NumericEntityValueRange(0.0, 1.0, 1.0),
        KEY_STEP_DELTA to NumericEntityValueRange(0.0, 1.0, 1.0),
        KEY_STEP_SESSION to NumericEntityValueRange(0.0, 50_000_000.0, null),
    ),
    relationships = listOf(
        EntityRelationship(
            subjectAttributeKey = KEY_ACZ,
            predicateToken = "informs",
            objectAttributeKey = KEY_STEP_DELTA,
            bidirectional = false,
            cardinalityHint = "N:1",
        ),
    ),
)

/**
 * Gradle module `:modules:motion` — [BaseAdapter] with **local** magnitude peak step detection (EMA gravity removal).
 */
class MotionAdapter(
    adapterId: String,
    private val captureSessionId: String,
    private val producerNodeId: String,
    private val sourceLabel: String = "android.hardware.SensorManager",
    private val acquisitionMethodLabel: String = "local.peakStepDetector+UFS",
    private val ufsEnvelopeVersion: String = UfsEnvelopeVersions.V1,
    private val dataEntity: DataEntity = buildMotionPayloadDataEntity(),
) : BaseAdapter(adapterId = adapterId, modality = ModalityKind.MOTION) {

    private val stepMutex = Mutex()
    private var gravityEma: Double = 9.81
    private var highPassPrev: Double = 0.0
    private var highPassPrev2: Double = 0.0
    private var lastStepTimestampNs: Long = Long.MIN_VALUE
    private var sessionStepTotal: Long = 0L

    override suspend fun adaptRaw(raw: RawModalityFrame): UnifiedDataPoint =
        when (raw) {
            is MotionImuRawFrame -> adaptImu(raw)
            is MotionRawCaptureFrame -> adaptLegacyPlaceholder(raw)
            else -> error("unsupported RawModalityFrame for motion: ${raw::class.simpleName}")
        }

    private suspend fun adaptImu(raw: MotionImuRawFrame): UnifiedDataPoint {
        val (delta, total) = computeStepDelta(
            timestampNanos = raw.eventTimestampNanos,
            ax = raw.accelXMs2,
            ay = raw.accelYMs2,
            az = raw.accelZMs2,
        )
        val gyroPresent = if (raw.gyroAvailable) 1L else 0L
        val gx = if (raw.gyroAvailable) raw.gyroXRadS.toDouble() else 0.0
        val gy = if (raw.gyroAvailable) raw.gyroYRadS.toDouble() else 0.0
        val gz = if (raw.gyroAvailable) raw.gyroZRadS.toDouble() else 0.0
        PipelineDiagnosticsRegistry.emit(
            logTag = PipelineLogTags.ADAPTER,
            module = ModalityKind.MOTION,
            stage = "adapter_imu_features",
            dataType = "accelerometer_gyro_step",
            detail = "[ADAPTER][MOTION_MODULE] Accelerometer xyz=(${raw.accelXMs2},${raw.accelYMs2},${raw.accelZMs2}) " +
                "gyroPresent=$gyroPresent stepDelta=$delta stepSession=$total eventNs=${raw.eventTimestampNanos} " +
                "capturedAt=${raw.capturedAtEpochMillis}",
        )
        return buildPoint(
            raw = raw,
            acx = raw.accelXMs2.toDouble(),
            acy = raw.accelYMs2.toDouble(),
            acz = raw.accelZMs2.toDouble(),
            gx = gx,
            gy = gy,
            gz = gz,
            gyroPresent = gyroPresent,
            stepDelta = delta,
            stepSession = total,
            provenanceHop = "motion.adapt.imu",
            provenanceNote = "stepDelta=$delta;sessionTotal=$total;gyroPresent=$gyroPresent",
        )
    }

    private suspend fun adaptLegacyPlaceholder(raw: MotionRawCaptureFrame): UnifiedDataPoint {
        val total = stepMutex.withLock { sessionStepTotal }
        return buildPoint(
            raw = raw,
            acx = 0.0,
            acy = 0.0,
            acz = 0.0,
            gx = 0.0,
            gy = 0.0,
            gz = 0.0,
            gyroPresent = 0L,
            stepDelta = 0L,
            stepSession = total,
            provenanceHop = "motion.adapt.legacyPlaceholder",
            provenanceNote = "sensorTypePlaceholder=${raw.sensorTypePlaceholder}",
        )
    }

    /**
     * Local step detector: EMA gravity estimate, high-pass magnitude, adaptive peak with refractory interval.
     */
    private suspend fun computeStepDelta(timestampNanos: Long, ax: Float, ay: Float, az: Float): Pair<Long, Long> =
        stepMutex.withLock {
            val mag = sqrt(
                (ax * ax + ay * ay + az * az).toDouble(),
            )
            gravityEma = 0.96 * gravityEma + 0.04 * mag
            val hp = mag - gravityEma
            val peak = hp < highPassPrev &&
                highPassPrev > highPassPrev2 &&
                highPassPrev > STEP_HP_THRESHOLD
            var delta = 0L
            if (peak) {
                val elapsed = timestampNanos - lastStepTimestampNs
                val allow = lastStepTimestampNs == Long.MIN_VALUE || elapsed >= MIN_STEP_INTERVAL_NS
                if (allow) {
                    lastStepTimestampNs = timestampNanos
                    sessionStepTotal += 1
                    delta = 1L
                }
            }
            highPassPrev2 = highPassPrev
            highPassPrev = hp
            delta to sessionStepTotal
        }

    private fun buildPoint(
        raw: RawModalityFrame,
        acx: Double,
        acy: Double,
        acz: Double,
        gx: Double,
        gy: Double,
        gz: Double,
        gyroPresent: Long,
        stepDelta: Long,
        stepSession: Long,
        provenanceHop: String,
        provenanceNote: String,
    ): UnifiedDataPoint {
        val monoNanos = System.nanoTime()
        val correlationString = raw.correlationId.value
        val data = linkedMapOf<String, Any>(
            UfsReservedDataKeys.CORRELATION_ID to correlationString,
            UfsReservedDataKeys.MONOTONIC_TIMESTAMP_NANOS to monoNanos,
            UfsReservedDataKeys.PROVENANCE_RECORDS to listOf(
                ProvenanceRecord(
                    hopName = provenanceHop,
                    componentId = adapterId,
                    recordedAtEpochMillis = System.currentTimeMillis(),
                    note = provenanceNote,
                ),
            ),
            KEY_ACX to acx.coerceIn(-80.0, 80.0),
            KEY_ACY to acy.coerceIn(-80.0, 80.0),
            KEY_ACZ to acz.coerceIn(-80.0, 80.0),
            KEY_GX to gx.coerceIn(-40.0, 40.0),
            KEY_GY to gy.coerceIn(-40.0, 40.0),
            KEY_GZ to gz.coerceIn(-40.0, 40.0),
            KEY_GYRO_PRESENT to gyroPresent.coerceIn(0L, 1L),
            KEY_STEP_DELTA to stepDelta.coerceIn(0L, 1L),
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
            detail = "[ADAPTER][MOTION_MODULE] step_count=$stepSession step_delta=$stepDelta metadata={ " +
                "${PipelineDiagnosticsFormat.dataDescription(metadata)} }",
        )
        return UnifiedDataPoint(
            data = data,
            metadata = metadata,
            schema = dataEntity,
        )
    }

    private companion object {
        private const val STEP_HP_THRESHOLD: Double = 0.38
        private const val MIN_STEP_INTERVAL_NS: Long = 280_000_000L
    }
}
