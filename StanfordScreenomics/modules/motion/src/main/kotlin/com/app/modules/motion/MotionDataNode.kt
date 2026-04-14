package com.app.modules.motion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import edu.stanford.screenomics.core.collection.MotionAccelRawFrame
import edu.stanford.screenomics.core.collection.MotionGyroRawFrame
import edu.stanford.screenomics.core.collection.MotionStepMinuteTickFrame
import edu.stanford.screenomics.core.collection.RawModalityFrame
import edu.stanford.screenomics.core.module.template.BaseDataNode
import edu.stanford.screenomics.core.module.template.ModulePipelineDispatchers
import edu.stanford.screenomics.core.unified.CorrelationId
import edu.stanford.screenomics.core.unified.ModalityKind
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.jvm.Volatile
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ImuCollectionMode {
    /** High-rate accelerometer + gyroscope while movement is present. */
    ACTIVE,

    /** Low-rate accelerometer for wake-only; no IMU stream payloads. */
    IDLE,
}

/**
 * Gradle module `:modules:motion` — streams **separate** raw frames for accelerometer and gyroscope while [ImuCollectionMode.ACTIVE],
 * and emits [MotionStepMinuteTickFrame] on a wall-clock cadence (default **1 min**) for step counts in that window.
 * After sustained stillness, high-rate IMU stops; minute step ticks continue (typically **0** steps when idle).
 */
class MotionDataNode(
    private val appContext: Context,
    override val nodeId: String,
    adapter: MotionAdapter,
    cache: MotionCache,
    pipelineDispatchers: ModulePipelineDispatchers = ModulePipelineDispatchers(),
    private val samplingDelayUs: Int = SensorManager.SENSOR_DELAY_GAME,
    private val idleAccelDelayUs: Int = SensorManager.SENSOR_DELAY_NORMAL,
    private val stationaryHoldNs: Long = 5_000_000_000L,
    /** Elapsed window length for [MotionStepMinuteTickFrame] (step rollup). */
    private val minuteStepIntervalMs: Long = 60_000L,
) : BaseDataNode(adapter = adapter, cache = cache, dispatchers = pipelineDispatchers) {

    private val sensorManager: SensorManager =
        appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val accelSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val rawIngress = MutableSharedFlow<RawModalityFrame>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val sharedRaw: Flow<RawModalityFrame> = rawIngress.asSharedFlow()

    private var handlerThread: HandlerThread? = null
    private var sensorHandler: Handler? = null
    private var emissionScope: CoroutineScope? = null
    private var emitDispatcher: ExecutorCoroutineDispatcher? = null
    private var minuteStepJob: Job? = null

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
                emitGyroFrame(event.timestamp)
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
                emitAccelFrame(event.timestamp, ax, ay, az)
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

    private fun emitAccelFrame(eventTimestampNanos: Long, ax: Float, ay: Float, az: Float) {
        val scope = emissionScope ?: return
        val exec = emitDispatcher ?: return
        val frame = MotionAccelRawFrame(
            correlationId = CorrelationId("motion-accel-${UUID.randomUUID()}"),
            capturedAtEpochMillis = System.currentTimeMillis(),
            eventTimestampNanos = eventTimestampNanos,
            accelXMs2 = ax,
            accelYMs2 = ay,
            accelZMs2 = az,
        )
        scope.launch(exec) {
            rawIngress.emit(frame)
        }
    }

    private fun emitGyroFrame(eventTimestampNanos: Long) {
        val scope = emissionScope ?: return
        val exec = emitDispatcher ?: return
        val gx = lastGyroX
        val gy = lastGyroY
        val gz = lastGyroZ
        val frame = MotionGyroRawFrame(
            correlationId = CorrelationId("motion-gyro-${UUID.randomUUID()}"),
            capturedAtEpochMillis = System.currentTimeMillis(),
            eventTimestampNanos = eventTimestampNanos,
            gyroXRadS = gx,
            gyroYRadS = gy,
            gyroZRadS = gz,
            gyroAvailable = gyroSampleSeen,
        )
        scope.launch(exec) {
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
        val handler = sensorHandler ?: return
        sensorManager.registerListener(sensorListener, accelSensor!!, idleAccelDelayUs, handler)
    }

    private fun enterActiveMode() {
        if (imuCollectionMode != ImuCollectionMode.IDLE) {
            return
        }
        sensorManager.unregisterListener(sensorListener)
        imuCollectionMode = ImuCollectionMode.ACTIVE
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
        emitDispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "motion-emit") }.asCoroutineDispatcher()
        val thread = HandlerThread("motion-imu").also { it.start() }
        handlerThread = thread
        sensorHandler = Handler(thread.looper)
        sensorManager.registerListener(sensorListener, accelSensor, samplingDelayUs, sensorHandler)
        gyroSensor?.let { g ->
            sensorManager.registerListener(sensorListener, g, samplingDelayUs, sensorHandler)
        }
        val scope = emissionScope!!
        val exec = emitDispatcher!!
        minuteStepJob = scope.launch {
            while (isActive) {
                delay(minuteStepIntervalMs)
                withContext(exec) {
                    rawIngress.emit(
                        MotionStepMinuteTickFrame(
                            correlationId = CorrelationId("motion-step-${UUID.randomUUID()}"),
                            capturedAtEpochMillis = System.currentTimeMillis(),
                        ),
                    )
                }
            }
        }
    }

    override suspend fun onDeactivate() {
        minuteStepJob?.cancel()
        minuteStepJob = null
        emitDispatcher?.close()
        emitDispatcher = null
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
