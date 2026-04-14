package edu.stanford.screenomics.collection

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.app.modules.audio.AudioModule
import com.app.modules.gps.GpsModule
import com.app.modules.motion.MotionModule
import com.app.modules.screenshot.ScreenshotModule
import edu.stanford.screenomics.BuildConfig
import edu.stanford.screenomics.MainActivity
import edu.stanford.screenomics.R
import edu.stanford.screenomics.ScreenomicsApp
import edu.stanford.screenomics.core.collection.DataNode
import edu.stanford.screenomics.core.management.PeriodicCacheEvictionTicker
import edu.stanford.screenomics.core.scheduling.TaskPriority
import edu.stanford.screenomics.core.unified.ModalityKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.jvm.JvmField

/**
 * Foreground service: activates all four modality [DataNode] graphs on shared caches, keeps
 * storage/edge/eviction running while the user switches apps or turns the screen off.
 *
 * Screenshots use [android.media.projection.MediaProjection] (full display). Start via
 * [Companion.start] with the consent [Intent] from [android.media.projection.MediaProjectionManager.createScreenCaptureIntent].
 * [com.app.modules.screenshot.ScreenshotDataNode] still skips work while the device is not interactive (screen off).
 */
class ModalityCollectionService : Service() {

    private val startStopLock = Any()

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Default)

    private val activeNodes = ArrayList<DataNode>()
    private val collectionJobs = ArrayList<Job>()
    private var edgeJob: Job? = null
    private var evictionTicker: PeriodicCacheEvictionTicker? = null
    private var locationReader: LocationSnapshotReader? = null

    private var didStartForeground: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCollectionInternal()
                stopSelf(startId)
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                if (!isRunning) {
                    startCollectionInternal(projectionFromIntent(intent))
                }
            }
        }
        // Do not use START_STICKY: after process death [ScreenomicsApp.phenotypingRuntime] is null until MainActivity
        // runs again; a sticky restart would skip startForeground and crash the app.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopCollectionInternal()
        serviceJob.cancel()
        super.onDestroy()
    }

    private fun startCollectionInternal(projection: Pair<Int, Intent>?) {
        synchronized(startStopLock) {
            if (isRunning) {
                return
            }
        }
        val runtime = (application as ScreenomicsApp).phenotypingRuntime
        if (runtime == null) {
            // startForegroundService() was already called — we MUST call startForeground() or the process dies.
            invokeStartForeground(buildRuntimeMissingNotification())
            runCatching { stopForeground(STOP_FOREGROUND_DETACH) }
            didStartForeground = false
            stopSelf()
            return
        }
        invokeStartForeground(buildNotification())
        val app = application as ScreenomicsApp
        if (projection != null) {
            if (!app.mediaProjectionCapture.start(projection.first, projection.second)) {
                Log.e(TAG, "MediaProjection start failed")
            }
        } else {
            Log.w(TAG, "Missing projection consent intent; screenshot frames will be empty until capture starts")
        }
        synchronized(startStopLock) {
            isRunning = true
        }

        val ts = runtime.taskScheduler
        runBlocking(Dispatchers.Default) {
            runCatching {
                ts.registerTask("collection-audio", TaskPriority.HIGH)
                ts.registerTask("collection-motion", TaskPriority.HIGH)
                ts.registerTask("collection-gps", TaskPriority.HIGH)
                ts.registerTask("collection-screenshot", TaskPriority.HIGH)
            }
        }
        val audio = AudioModule.create(
            appContext = this,
            nodeId = "audio-node-1",
            captureSessionId = runtime.captureSessionId,
            cache = runtime.audioCache,
            localFileSink = runtime.modalityLocalFileSink,
            pipelineDispatchers = ts.modulePipelineDispatchers(ModalityKind.AUDIO),
        )
        val motion = MotionModule.create(
            appContext = this,
            nodeId = "motion-node-1",
            captureSessionId = runtime.captureSessionId,
            cache = runtime.motionCache,
            pipelineDispatchers = ts.modulePipelineDispatchers(ModalityKind.MOTION),
        )
        val reader = LocationSnapshotReader(this)
        locationReader = reader
        reader.start()
        val gps = GpsModule.create(
            appContext = this,
            nodeId = "gps-node-1",
            captureSessionId = runtime.captureSessionId,
            locationSupplier = { reader.latest },
            cache = runtime.gpsCache,
            pipelineDispatchers = ts.modulePipelineDispatchers(ModalityKind.GPS),
            openWeatherMapApiKey = BuildConfig.OPENWEATHERMAP_API_KEY.takeIf { it.isNotBlank() },
        )
        val screenshot = ScreenshotModule.create(
            appContext = this,
            nodeId = "screenshot-node-1",
            captureSessionId = runtime.captureSessionId,
            frameSupplier = {
                withContext(Dispatchers.Default) {
                    app.mediaProjectionCapture.acquireLatestBitmap()
                }
            },
            cache = runtime.screenshotCache,
            localFileSink = runtime.modalityLocalFileSink,
            pipelineDispatchers = ts.modulePipelineDispatchers(ModalityKind.SCREENSHOT),
        )

        activeNodes.clear()
        activeNodes += listOf(audio, motion, gps, screenshot)

        collectionJobs.forEach { it.cancel() }
        collectionJobs.clear()

        for (node in activeNodes) {
            val j = serviceScope.launch {
                runCatching {
                    node.activate(this)
                    node.observeUnifiedOutputs().collect { }
                }
            }
            collectionJobs += j
        }

        evictionTicker?.stop()
        evictionTicker = PeriodicCacheEvictionTicker(
            scope = serviceScope,
            manager = runtime.cacheManager,
        ).also { it.start() }

        edgeJob?.cancel()
        edgeJob = serviceScope.launch {
            while (isActive) {
                runCatching {
                    runtime.edgeComputationEngine.runCycle(
                        cacheManager = runtime.cacheManager,
                        interventionController = runtime.interventionController,
                        activeJobIds = setOf("collection-audio", "collection-motion", "collection-gps", "collection-screenshot"),
                    )
                }
                delay(EDGE_INTERVAL_MS)
            }
        }
    }

    private fun stopCollectionInternal() {
        synchronized(startStopLock) {
            if (!isRunning) {
                return
            }
            isRunning = false
        }
        edgeJob?.cancel()
        edgeJob = null
        evictionTicker?.stop()
        evictionTicker = null
        locationReader?.stop()
        locationReader = null
        val collectionJobsSnapshot = collectionJobs.toList()
        collectionJobs.clear()
        collectionJobsSnapshot.forEach { it.cancel() }
        runBlocking(Dispatchers.Default) {
            collectionJobsSnapshot.joinAll()
            for (node in activeNodes) {
                runCatching { node.deactivate() }
            }
        }
        activeNodes.clear()
        (application as ScreenomicsApp).mediaProjectionCapture.stop()
        val runtime = (application as ScreenomicsApp).phenotypingRuntime
        val ts = runtime?.taskScheduler
        if (ts != null) {
            runBlocking(Dispatchers.Default) {
                runCatching {
                    ts.unregisterTask("collection-audio")
                    ts.unregisterTask("collection-motion")
                    ts.unregisterTask("collection-gps")
                    ts.unregisterTask("collection-screenshot")
                }
            }
        }
        if (didStartForeground) {
            stopForeground(STOP_FOREGROUND_DETACH)
            didStartForeground = false
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ModalityCollectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.collection_notification_title))
            .setContentText(getString(R.string.collection_notification_text))
            .setSmallIcon(applicationInfo.icon)
            .setContentIntent(open)
            .addAction(0, getString(R.string.collection_stop), stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun buildRuntimeMissingNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.collection_notification_title))
            .setContentText(getString(R.string.collection_error_runtime))
            .setSmallIcon(applicationInfo.icon)
            .build()

    private fun invokeStartForeground(notif: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("InlinedApi")
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (Build.VERSION.SDK_INT >= 34) {
                types = types or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            startForeground(NOTIFICATION_ID, notif, types)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notif)
        }
        didStartForeground = true
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.collection_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        const val ACTION_START: String = "edu.stanford.screenomics.collection.START"
        const val ACTION_STOP: String = "edu.stanford.screenomics.collection.STOP"

        private const val TAG: String = "ModalityCollectionSvc"
        private const val EXTRA_PROJECTION_RESULT_CODE: String = "extra_projection_result_code"
        private const val EXTRA_PROJECTION_DATA: String = "extra_projection_data"

        private const val NOTIFICATION_ID: Int = 71_001
        private const val CHANNEL_ID: String = "phenotyping_collection"
        private const val EDGE_INTERVAL_MS: Long = 60_000L

        @JvmField
        @Volatile
        var isRunning: Boolean = false

        fun start(context: Context, projectionResultCode: Int, projectionData: Intent) {
            val i = Intent(context, ModalityCollectionService::class.java).setAction(ACTION_START)
            i.putExtra(EXTRA_PROJECTION_RESULT_CODE, projectionResultCode)
            i.putExtra(EXTRA_PROJECTION_DATA, projectionData)
            ContextCompat.startForegroundService(context, i)
        }

        private fun projectionFromIntent(intent: Intent?): Pair<Int, Intent>? {
            if (intent == null) {
                return null
            }
            val code = intent.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, Int.MIN_VALUE)
            if (code == Int.MIN_VALUE) {
                return null
            }
            val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
            }
            return if (data != null) Pair(code, data) else null
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ModalityCollectionService::class.java).setAction(ACTION_STOP))
        }
    }
}
