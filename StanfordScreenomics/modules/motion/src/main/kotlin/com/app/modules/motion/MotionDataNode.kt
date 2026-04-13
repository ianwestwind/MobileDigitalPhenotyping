package com.app.modules.motion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import edu.stanford.screenomics.core.collection.MotionImuRawFrame
import edu.stanford.screenomics.core.collection.RawModalityFrame
import edu.stanford.screenomics.core.module.template.BaseDataNode
import edu.stanford.screenomics.core.module.template.ModulePipelineDispatchers
import edu.stanford.screenomics.core.unified.CorrelationId
import edu.stanford.screenomics.core.unified.ModalityKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.jvm.Volatile

/**
 * Gradle module `:modules:motion` — concrete [BaseDataNode] streaming [MotionImuRawFrame] from **accelerometer** and **gyroscope**
 * on a dedicated [HandlerThread] (local hardware sampling only).
 */
class MotionDataNode(
    private val appContext: Context,
    override val nodeId: String,
    adapter: MotionAdapter,
    cache: MotionCache,
    pipelineDispatchers: ModulePipelineDispatchers = ModulePipelineDispatchers(),
    private val samplingDelayUs: Int = SensorManager.SENSOR_DELAY_GAME,
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
            when (event.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> {
                    lastGyroX = event.values[0]
                    lastGyroY = event.values[1]
                    lastGyroZ = event.values[2]
                    gyroSampleSeen = true
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    val scope = emissionScope ?: return
                    val gx = lastGyroX
                    val gy = lastGyroY
                    val gz = lastGyroZ
                    val gyroAvail = gyroSensor != null && gyroSampleSeen
                    val frame = MotionImuRawFrame(
                        correlationId = CorrelationId("motion-${UUID.randomUUID()}"),
                        capturedAtEpochMillis = System.currentTimeMillis(),
                        eventTimestampNanos = event.timestamp,
                        accelXMs2 = event.values[0],
                        accelYMs2 = event.values[1],
                        accelZMs2 = event.values[2],
                        gyroXRadS = gx,
                        gyroYRadS = gy,
                        gyroZRadS = gz,
                        gyroAvailable = gyroAvail,
                    )
                    scope.launch(Dispatchers.Default) {
                        rawIngress.emit(frame)
                    }
                }
            }
        }
    }

    override fun modalityKind(): ModalityKind = ModalityKind.MOTION

    override fun observeRawFrames(): Flow<RawModalityFrame> = sharedRaw

    override suspend fun onActivate(collectionScope: CoroutineScope) {
        require(accelSensor != null) { "No default accelerometer on this device" }
        onDeactivate()
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
        sensorManager.unregisterListener(sensorListener)
        handlerThread?.quitSafely()
        handlerThread = null
        sensorHandler = null
        emissionScope = null
        gyroSampleSeen = false
    }
}
