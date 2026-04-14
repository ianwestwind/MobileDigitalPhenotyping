package com.app.modules.motion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import edu.stanford.screenomics.core.collection.MotionImuRawFrame
import edu.stanford.screenomics.core.collection.MotionRawCaptureFrame
import edu.stanford.screenomics.core.collection.RawModalityFrame
import edu.stanford.screenomics.core.module.template.BaseDataNode
import edu.stanford.screenomics.core.module.template.ModulePipelineDispatchers
import edu.stanford.screenomics.core.unified.CorrelationId
import edu.stanford.screenomics.core.unified.ModalityKind
import java.util.UUID
import kotlin.jvm.Volatile
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private enum class ImuCollectionMode {
    /** High-rate accelerometer + gyroscope for step detection and IMU payload. */
    ACTIVE,

    /** No gyro; low-rate accelerometer for wake-only. Heartbeats carry implicit zero-IMU / current step total. */
    IDLE,
}

/**
 * Gradle module `:modules:motion` — concrete [BaseDataNode] streaming [MotionImuRawFrame] from **accelerometer** and **gyroscope**
 * while movement is present. After sustained stillness, sensors drop to a **low-rate accelerometer** wake probe only and the node
 * emits periodic [MotionRawCaptureFrame] heartbeats so the pipeline still records **no new steps** (session step total unchanged,
 * IMU axes zeroed in the adapter).
 */
class MotionDataNode(
    private val appContext: Context,
    override val nodeId: String,
    adapter: MotionAdapter,
    cache: MotionCache,
    pipelineDispatchers: ModulePipelineDispatchers = ModulePipelineDispatchers(),
    private val samplingDelayUs: Int = SensorManager.SENSOR_DELAY_GAME,
    private val idleAccelDelayUs: Int = SensorManager.SENSOR_DELAY_NORMAL,
    /** Duration of near-still readings before switching to [ImuCollectionMode.IDLE]. */
    private val stationaryHoldNs: Long = 5_000_000_000L,
    /** Interval between idle heartbeats (implicit zero-step / zero-IMU points). */
    private val idleHeartbeatIntervalMs: Long = 55_000L,
) : BaseDataNode(adapter = adapter, cache = cache, dispatchers = pipelineDispatchers) {

    private val sensorManager: SensorManager =
        appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val accelSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val rawIngress = MutableSharedFlow<RawModalityFrame>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val sharedRaw: Flow<RawModalityFrame> = rawIngress.asSharedFlow()

    private var handlerThread: HandlerThread? = null
    private var sensorHandler: Handler? = null
    private var emissionScope: CoroutineScope? = null
    private var idleHeartbeatJob: Job? = null

    @Volatile
    private var imuCollectionMode: ImuCollectionMode = ImuCollectionMode.ACTIVE

    private var stationarySinceNs: Long? = null
    private var lastIdleAccelMag: Float? = null

    @Volatile
    private var lastGyroX: Float = 0f

    @Volatile
    private var lastGyroY: Float = 0f

    @Volatile
    private var lastGyroZ: Float = 0f

    @Volatile
    private var gyroSampleSeen: Boolean = false

    private val sensorListener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

        override fun onSensorChanged(event: SensorEvent) {
            when (imuCollectionMode) {
                ImuCollectionMode.ACTIVE -> onActiveSensorEvent(event)
                ImuCollectionMode.IDLE -> onIdleSensorEvent(event)
            }
        }
    }

    private fun onActiveSensorEvent(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                lastGyroX = event.values[0]
                lastGyroY = event.values[1]
                lastGyroZ = event.values[2]
                gyroSampleSeen = true
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val ax = event.values[0]
                val ay = event.values[1]
                val az = event.values[2]
                val gyroAvail = gyroSensor != null && gyroSampleSeen
                if (isMovementHeuristic(ax, ay, az, gyroAvail)) {
                    stationarySinceNs = null
                } else {
                    val t = event.timestamp
                    if (stationarySinceNs == null) {
                        stationarySinceNs = t
                    } else if (t - stationarySinceNs!! >= stationaryHoldNs) {
                        enterIdleMode()
                        return
                    }
                }
                emitImuFrame(event.timestamp, ax, ay, az)
            }
        }
    }

    private fun onIdleSensorEvent(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) {
            return
        }
        val ax = event.values[0]
        val ay = event.values[1]
        val az = event.values[2]
        if (isIdleWakeEvent(ax, ay, az)) {
            enterActiveMode()
        }
    }

    private fun isMovementHeuristic(ax: Float, ay: Float, az: Float, gyroAvail: Boolean): Boolean {
        val mag = sqrt(ax * ax + ay * ay + az * az)
        if (abs(mag - GRAVITY_MS2) > MOVEMENT_ACCEL_DEVIATION_MS2) {
            return true
        }
        if (gyroAvail) {
            val gMag = sqrt(lastGyroX * lastGyroX + lastGyroY * lastGyroY + lastGyroZ * lastGyroZ)
            if (gMag > MOVEMENT_GYRO_MAG_RAD_S) {
                return true
            }
        }
        return false
    }

    private fun isIdleWakeEvent(ax: Float, ay: Float, az: Float): Boolean {
        val mag = sqrt(ax * ax + ay * ay + az * az)
        lastIdleAccelMag?.let { prev ->
            if (abs(mag - prev) > IDLE_JERK_MS2) {
                lastIdleAccelMag = mag
                return true
            }
        }
        lastIdleAccelMag = mag
        return abs(mag - GRAVITY_MS2) > IDLE_WAKE_STATIC_DEVIATION_MS2
    }

    private fun emitImuFrame(eventTimestampNanos: Long, ax: Float, ay: Float, az: Float) {
        val scope = emissionScope ?: return
        val gx = lastGyroX
        val gy = lastGyroY
        val gz = lastGyroZ
        val gyroAvail = gyroSensor != null && gyroSampleSeen
        val frame = MotionImuRawFrame(
            correlationId = CorrelationId("motion-${UUID.randomUUID()}"),
            capturedAtEpochMillis = System.currentTimeMillis(),
            eventTimestampNanos = eventTimestampNanos,
            accelXMs2 = ax,
            accelYMs2 = ay,
            accelZMs2 = az,
            gyroXRadS = gx,
            gyroYRadS = gy,
            gyroZRadS = gz,
            gyroAvailable = gyroAvail,
        )
        scope.launch(Dispatchers.Default) {
            rawIngress.emit(frame)
        }
    }

    private fun enterIdleMode() {
        if (imuCollectionMode != ImuCollectionMode.ACTIVE) {
            return
        }
        sensorManager.unregisterListener(sensorListener)
        imuCollectionMode = ImuCollectionMode.IDLE
        stationarySinceNs = null
        lastIdleAccelMag = null
        gyroSampleSeen = false
        idleHeartbeatJob?.cancel()
        val scope = emissionScope
        val handler = sensorHandler
        if (scope == null || handler == null) {
            return
        }
        idleHeartbeatJob = scope.launch {
            while (isActive && imuCollectionMode == ImuCollectionMode.IDLE) {
                delay(idleHeartbeatIntervalMs)
                if (imuCollectionMode != ImuCollectionMode.IDLE) {
                    break
                }
                rawIngress.emit(
                    MotionRawCaptureFrame(
                        correlationId = CorrelationId("motion-idle-${UUID.randomUUID()}"),
                        capturedAtEpochMillis = System.currentTimeMillis(),
                        sensorTypePlaceholder = MOTION_IDLE_HEARTBEAT_PLACEHOLDER,
                    ),
                )
            }
        }
        sensorManager.registerListener(sensorListener, accelSensor!!, idleAccelDelayUs, handler)
    }

    private fun enterActiveMode() {
        if (imuCollectionMode != ImuCollectionMode.IDLE) {
            return
        }
        sensorManager.unregisterListener(sensorListener)
        imuCollectionMode = ImuCollectionMode.ACTIVE
        idleHeartbeatJob?.cancel()
        idleHeartbeatJob = null
        stationarySinceNs = null
        lastIdleAccelMag = null
        val handler = sensorHandler ?: return
        sensorManager.registerListener(sensorListener, accelSensor!!, samplingDelayUs, handler)
        gyroSensor?.let { g ->
            sensorManager.registerListener(sensorListener, g, samplingDelayUs, handler)
        }
    }

    override fun modalityKind(): ModalityKind = ModalityKind.MOTION

    override fun observeRawFrames(): Flow<RawModalityFrame> = sharedRaw

    override suspend fun onActivate(collectionScope: CoroutineScope) {
        require(accelSensor != null) { "No default accelerometer on this device" }
        onDeactivate()
        imuCollectionMode = ImuCollectionMode.ACTIVE
        emissionScope = CoroutineScope(collectionScope.coroutineContext)
        val thread = HandlerThread("motion-imu").also { it.start() }
        handlerThread = thread
        sensorHandler = Handler(thread.looper)
        sensorManager.registerListener(sensorListener, accelSensor, samplingDelayUs, sensorHandler)
        gyroSensor?.let { g ->
            sensorManager.registerListener(sensorListener, g, samplingDelayUs, sensorHandler)
        }
    }

    override suspend fun onDeactivate() {
        idleHeartbeatJob?.cancel()
        idleHeartbeatJob = null
        sensorManager.unregisterListener(sensorListener)
        handlerThread?.quitSafely()
        handlerThread = null
        sensorHandler = null
        emissionScope = null
        gyroSampleSeen = false
        stationarySinceNs = null
        lastIdleAccelMag = null
        imuCollectionMode = ImuCollectionMode.ACTIVE
    }

    private companion object {
        private const val GRAVITY_MS2: Float = 9.81f
        private const val MOVEMENT_ACCEL_DEVIATION_MS2: Float = 0.28f
        private const val MOVEMENT_GYRO_MAG_RAD_S: Float = 0.07f
        private const val IDLE_WAKE_STATIC_DEVIATION_MS2: Float = 0.35f
        private const val IDLE_JERK_MS2: Float = 0.22f
    }
}
