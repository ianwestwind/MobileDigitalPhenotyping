package edu.stanford.screenomics.collection

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.app.modules.audio.AudioModule
import com.app.modules.gps.GpsModule
import com.app.modules.motion.MotionModule
import com.app.modules.screenshot.ScreenshotModule
import edu.stanford.screenomics.BuildConfig
import edu.stanford.screenomics.MediaProjectionRelaunchTrampolineActivity
import edu.stanford.screenomics.MainActivity
import edu.stanford.screenomics.R
import edu.stanford.screenomics.settings.VolatileCacheWindowPrefs
import edu.stanford.screenomics.settings.VolatileIntervalPrefs
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
 *
 * When the screen turns off, the system may stop [android.media.projection.MediaProjection] without immediately
 * invoking [android.media.projection.MediaProjection.Callback.onStop], leaving our
 * [edu.stanford.screenomics.screenshot.MediaProjectionScreenCapture] out
 * of sync. We therefore tear down local capture on [Intent.ACTION_SCREEN_OFF] while collection is active so that
 * [Intent.ACTION_SCREEN_ON] / [Intent.ACTION_USER_PRESENT] can reliably reopen the consent UI.
 * While collection is still active we listen for those actions and for default-display state via
 * [DisplayManager.DisplayListener], then start
 * [edu.stanford.screenomics.MediaProjectionRelaunchTrampolineActivity] so the system capture dialog runs immediately
 * (no [MainActivity] delay). API 34+ uses BAL opt-in on the [PendingIntent] (sender + creator modes). If the activity
 * still cannot start, a high-importance notification with [android.app.Notification#setFullScreenIntent] asks the user
 * to continue.
 */
class ModalityCollectionService : Service() {

    private val startStopLock = Any()

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Default)

    private val mainHandler = Handler(Looper.getMainLooper())

    private val activeNodes = ArrayList<DataNode>()
    private val collectionJobs = ArrayList<Job>()
    private var edgeJob: Job? = null
    private var evictionTicker: PeriodicCacheEvictionTicker? = null
    private var locationReader: LocationSnapshotReader? = null

    /** Cloned consent for [edu.stanford.screenomics.screenshot.MediaProjectionScreenCapture.start] after SCREEN_ON. */
    private var lastMediaProjectionConsent: Pair<Int, Intent>? = null

    private var screenOnReceiver: BroadcastReceiver? = null

    /** Catches display power transitions when [Intent.ACTION_SCREEN_ON] is delayed or missing (OEM-dependent). */
    private var displayWakeListener: DisplayManager.DisplayListener? = null

    private var lastKnownDefaultDisplayState: Int = Display.STATE_OFF

    /** Limits repeated UI launches when [Intent.ACTION_SCREEN_ON] fires in quick succession. */
    private var lastProjectionRelaunchUiElapsedMs: Long = 0L

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
                val projection = projectionFromIntent(intent)
                if (!isRunning) {
                    startCollectionInternal(projection)
                } else if (projection != null) {
                    applyMediaProjectionUpdate(projection)
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
            lastMediaProjectionConsent = projection.first to Intent(projection.second)
            if (!app.mediaProjectionCapture.start(projection.first, projection.second)) {
                Log.e(TAG, "MediaProjection start failed")
            } else {
                cancelProjectionRelaunchPromptNotification()
            }
        } else {
            lastMediaProjectionConsent = null
            Log.w(TAG, "Missing projection consent intent; screenshot frames will be empty until capture starts")
        }
        synchronized(startStopLock) {
            isRunning = true
        }
        registerScreenOnReceiver()

        VolatileIntervalPrefs.syncCollectionCadenceRegistryFromPrefs(applicationContext)
        VolatileCacheWindowPrefs.syncRetentionFromPrefs(applicationContext)

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

    /**
     * Applies a fresh consent [Intent] while collection is already running (e.g. after the user re-approves capture
     * when the screen turns back on).
     */
    private fun applyMediaProjectionUpdate(projection: Pair<Int, Intent>) {
        lastMediaProjectionConsent = projection.first to Intent(projection.second)
        val app = application as ScreenomicsApp
        if (!app.mediaProjectionCapture.start(projection.first, projection.second)) {
            Log.e(TAG, "MediaProjection update after new consent failed")
        } else {
            cancelProjectionRelaunchPromptNotification()
        }
    }

    private fun stopCollectionInternal() {
        synchronized(startStopLock) {
            if (!isRunning) {
                return
            }
            isRunning = false
        }
        unregisterScreenOnReceiver()
        lastMediaProjectionConsent = null
        cancelProjectionRelaunchPromptNotification()
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

    private fun registerScreenOnReceiver() {
        if (screenOnReceiver != null) {
            return
        }
        screenOnReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> syncMediaProjectionStoppedForNextWake()
                    Intent.ACTION_SCREEN_ON,
                    Intent.ACTION_USER_PRESENT,
                    -> requestMediaProjectionConsentUiAfterScreenOn()
                    else -> Unit
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenOnReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(screenOnReceiver, filter)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register screen power broadcast receiver", e)
            screenOnReceiver = null
            return
        }
        try {
            registerDefaultDisplayWakeListener()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register display wake listener", e)
        }
    }

    private fun unregisterScreenOnReceiver() {
        unregisterDefaultDisplayWakeListener()
        val r = screenOnReceiver ?: return
        screenOnReceiver = null
        runCatching { unregisterReceiver(r) }
    }

    private fun registerDefaultDisplayWakeListener() {
        if (displayWakeListener != null) {
            return
        }
        val dm = getSystemService(DisplayManager::class.java) ?: return
        lastKnownDefaultDisplayState = dm.getDisplay(Display.DEFAULT_DISPLAY)?.state ?: Display.STATE_OFF
        displayWakeListener = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) {
                    return
                }
                val state = dm.getDisplay(displayId)?.state ?: return
                val prev = lastKnownDefaultDisplayState
                lastKnownDefaultDisplayState = state
                if (state == Display.STATE_ON && prev != Display.STATE_ON) {
                    requestMediaProjectionConsentUiAfterScreenOn()
                } else if (state == Display.STATE_OFF && prev != Display.STATE_OFF) {
                    syncMediaProjectionStoppedForNextWake()
                }
            }

            override fun onDisplayAdded(displayId: Int) = Unit

            override fun onDisplayRemoved(displayId: Int) = Unit
        }
        dm.registerDisplayListener(displayWakeListener, mainHandler)
    }

    private fun unregisterDefaultDisplayWakeListener() {
        val l = displayWakeListener ?: return
        displayWakeListener = null
        runCatching { getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(l) }
    }

    /**
     * Aligns local capture state with display power: avoids treating a stale [MediaProjection] handle as "still
     * running" after [Intent.ACTION_SCREEN_ON], which would skip the consent / trampoline flow.
     */
    private fun syncMediaProjectionStoppedForNextWake() {
        val running = synchronized(startStopLock) { isRunning }
        if (!running) {
            return
        }
        (application as ScreenomicsApp).mediaProjectionCapture.stop()
    }

    /**
     * After wake / unlock, [MediaProjection] is often already stopped. Reusing the old consent [Intent] from
     * [getMediaProjection] frequently throws [SecurityException], so we start [MediaProjectionRelaunchTrampolineActivity]
     * (see [launchProjectionRelaunchTrampolineWithBalOptIn]).
     */
    private fun requestMediaProjectionConsentUiAfterScreenOn() {
        mainHandler.post {
            val running = synchronized(startStopLock) { isRunning }
            if (!running) {
                return@post
            }
            val app = application as ScreenomicsApp
            // Always drop local capture on wake so we never skip the consent flow due to a stale handle
            // (the system can invalidate projection without a timely [MediaProjection.Callback.onStop]).
            app.mediaProjectionCapture.stop()
            val now = SystemClock.uptimeMillis()
            if (now - lastProjectionRelaunchUiElapsedMs < 2_500L) {
                return@post
            }
            lastProjectionRelaunchUiElapsedMs = now
            mainHandler.postDelayed({
                val stillRunning = synchronized(startStopLock) { isRunning }
                if (!stillRunning) {
                    return@postDelayed
                }
                if ((application as ScreenomicsApp).mediaProjectionCapture.isRunning()) {
                    return@postDelayed
                }
                launchProjectionRelaunchTrampolineWithBalOptIn()
            }, SCREEN_ON_PROJECTION_UI_DELAY_MS)
        }
    }

    private fun trampolineRelaunchIntent(): Intent =
        Intent(this, MediaProjectionRelaunchTrampolineActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

    private fun launchProjectionRelaunchTrampolineWithBalOptIn() {
        val launch = trampolineRelaunchIntent()
        val balBundle = projectionRelaunchActivityOptions().toBundle()
        // API 34+: try direct start with BAL bundle first; PendingIntent.send() can complete without an activity
        // actually appearing, which previously short-circuited the fallbacks.
        if (Build.VERSION.SDK_INT >= 34) {
            val started = runCatching {
                startActivity(launch, balBundle)
                true
            }.getOrElse { ex ->
                Log.w(TAG, "startActivity with BAL bundle for projection relaunch failed", ex)
                false
            }
            if (started) {
                return
            }
            runCatching {
                sendProjectionRelaunchPendingIntent(launch)
            }.onFailure { ex ->
                Log.w(TAG, "PendingIntent.send for projection relaunch failed", ex)
            }
        }
        val plainStarted = runCatching {
            startActivity(launch)
            true
        }.getOrElse { ex ->
            Log.w(TAG, "Plain startActivity for projection relaunch failed", ex)
            false
        }
        if (plainStarted) {
            return
        }
        Log.w(TAG, "Activity launch paths failed; posting full-screen / tap notification")
        showProjectionRelaunchPromptNotification(launch)
    }

    private fun projectionRelaunchActivityOptions(): ActivityOptions {
        val piOpts = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= 34) {
            piOpts.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            piOpts.setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        }
        return piOpts
    }

    private fun sendProjectionRelaunchPendingIntent(launch: Intent) {
        val pi = PendingIntent.getActivity(
            this,
            PI_REQUEST_PROJECTION_RELAUNCH,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            projectionRelaunchActivityOptions().toBundle(),
        )
        pi.send()
    }

    private fun showProjectionRelaunchPromptNotification(launch: Intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val nm = getSystemService(NotificationManager::class.java) ?: return
        ensureProjectionRelaunchChannel()
        val tap = PendingIntent.getActivity(
            this,
            PI_REQUEST_PROJECTION_RELAUNCH_NOTIFICATION,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            if (Build.VERSION.SDK_INT >= 34) projectionRelaunchActivityOptions().toBundle() else null,
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID_PROJECTION_REL_PROMPT)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle(getString(R.string.collection_projection_relaunch_title))
            .setContentText(getString(R.string.collection_projection_relaunch_text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setFullScreenIntent(tap, true)
                }
            }
            .build()
        nm.notify(NOTIFICATION_ID_PROJECTION_REL_PROMPT, n)
    }

    private fun cancelProjectionRelaunchPromptNotification() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.cancel(NOTIFICATION_ID_PROJECTION_REL_PROMPT)
    }

    private fun ensureProjectionRelaunchChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_PROJECTION_REL_PROMPT,
                getString(R.string.collection_projection_relaunch_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
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
        private const val NOTIFICATION_ID_PROJECTION_REL_PROMPT: Int = 71_004
        private const val CHANNEL_ID: String = "phenotyping_collection"
        private const val CHANNEL_ID_PROJECTION_REL_PROMPT: String = "phenotyping_projection_relaunch"
        private const val PI_REQUEST_PROJECTION_RELAUNCH: Int = 71_003
        private const val PI_REQUEST_PROJECTION_RELAUNCH_NOTIFICATION: Int = 71_006
        /** Brief delay so the display and BAL policy are ready after [Intent.ACTION_SCREEN_ON]. */
        private const val SCREEN_ON_PROJECTION_UI_DELAY_MS: Long = 600L
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
