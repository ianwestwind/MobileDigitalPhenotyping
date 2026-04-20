package edu.stanford.screenomics

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.app.modules.audio.AudioCache
import com.app.modules.gps.GpsCache
import com.app.modules.motion.MotionCache
import com.app.modules.screenshot.ScreenshotCache
import edu.stanford.screenomics.cachemanagement.AndroidCacheManagerLifecycleBridge
import edu.stanford.screenomics.collection.ModalityCollectionService
import edu.stanford.screenomics.core.management.DefaultCacheManager
import edu.stanford.screenomics.core.management.DefaultInterventionController
import edu.stanford.screenomics.core.management.InterventionController
import edu.stanford.screenomics.core.management.LifecycleAwareCacheManager
import edu.stanford.screenomics.core.management.PerModalityCacheRegistrySession
import edu.stanford.screenomics.core.edge.DefaultEdgeComputationEngine
import edu.stanford.screenomics.core.edge.EdgeComputationEngine
import edu.stanford.screenomics.core.scheduling.DefaultTaskScheduler
import edu.stanford.screenomics.core.scheduling.TaskPriority
import edu.stanford.screenomics.core.scheduling.TaskScheduler
import edu.stanford.screenomics.core.storage.DefaultDistributedStorageManager
import edu.stanford.screenomics.core.unified.ModalityKind
import edu.stanford.screenomics.core.unified.UnifiedDataPoint
import edu.stanford.screenomics.core.unified.requireCorrelationId
import edu.stanford.screenomics.databinding.ActivityMainBinding
import edu.stanford.screenomics.edge.AndroidTfliteInterpreterBridge
import edu.stanford.screenomics.scheduling.AndroidHostResourceSignalProvider
import edu.stanford.screenomics.storage.AndroidCloudMediaStorageBridge
import edu.stanford.screenomics.storage.AndroidFirestoreStructuredWriteBridge
import edu.stanford.screenomics.storage.AndroidModalityLocalFileSink
import edu.stanford.screenomics.storage.AndroidRealtimeStructuredWriteBridge
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val cacheUiTickFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.US).withZone(ZoneId.systemDefault())

    private val cachePointTimeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.US).withZone(ZoneId.systemDefault())

    private lateinit var binding: ActivityMainBinding

    private lateinit var runtime: PhenotypingRuntime

    private lateinit var cacheLifecycleBridge: AndroidCacheManagerLifecycleBridge

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.all { it }) {
            launchScreenCaptureThenStartService()
        }
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            Toast.makeText(this, R.string.collection_projection_required, Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        ModalityCollectionService.start(this, result.resultCode, result.data!!)
        binding.root.postDelayed({ refreshCollectionUi() }, 400L)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as ScreenomicsApp
        runtime = if (ModalityCollectionService.isRunning && app.phenotypingRuntime != null) {
            app.phenotypingRuntime!!
        } else {
            val edgeComputationEngine: EdgeComputationEngine = DefaultEdgeComputationEngine(
                tfliteInterpreterBridge = AndroidTfliteInterpreterBridge(applicationContext),
                modelAssetPath = null,
            )
            val taskScheduler: TaskScheduler = DefaultTaskScheduler()
            val interventionController: InterventionController = DefaultInterventionController()
            val cacheManager: LifecycleAwareCacheManager = DefaultCacheManager()
            val appCtx = applicationContext
            val localSink = AndroidModalityLocalFileSink(appCtx)
            val distributedStorageManager = DefaultDistributedStorageManager(
                localFileSink = localSink,
                firestoreBridge = AndroidFirestoreStructuredWriteBridge(appCtx),
                cloudMediaBridge = AndroidCloudMediaStorageBridge(appCtx),
                realtimeBridge = AndroidRealtimeStructuredWriteBridge(appCtx),
            )
            val storageFanOut: suspend (UnifiedDataPoint) -> Unit = { point ->
                distributedStorageManager.onUnifiedPointCommitted(point)
            }
            val audioCache = AudioCache(onAfterUnifiedPointCommittedOutsideLock = storageFanOut)
            val motionCache = MotionCache(onAfterUnifiedPointCommittedOutsideLock = storageFanOut)
            val gpsCache = GpsCache(onAfterUnifiedPointCommittedOutsideLock = storageFanOut)
            val screenshotCache = ScreenshotCache(onAfterUnifiedPointCommittedOutsideLock = storageFanOut)
            PhenotypingRuntime(
                captureSessionId = UUID.randomUUID().toString(),
                audioCache = audioCache,
                motionCache = motionCache,
                gpsCache = gpsCache,
                screenshotCache = screenshotCache,
                modalityLocalFileSink = localSink,
                distributedStorageManager = distributedStorageManager,
                cacheManager = cacheManager,
                taskScheduler = taskScheduler,
                edgeComputationEngine = edgeComputationEngine,
                interventionController = interventionController,
            ).also { app.phenotypingRuntime = it }
        }

        cacheLifecycleBridge = AndroidCacheManagerLifecycleBridge(
            owner = this,
            orchestrator = runtime.cacheManager,
        )
        cacheLifecycleBridge.attach()

        lifecycleScope.launch {
            if (!ModalityCollectionService.isRunning) {
                PerModalityCacheRegistrySession(runtime.cacheManager).registerStandardModalities(
                    audioCache = runtime.audioCache,
                    motionCache = runtime.motionCache,
                    gpsCache = runtime.gpsCache,
                    screenshotCache = runtime.screenshotCache,
                )
            }
            runCatching {
                runtime.taskScheduler.registerTask("capture-graph-bootstrap", TaskPriority.HIGH)
            }
            runtime.taskScheduler.startResourceMonitoring(
                scope = lifecycleScope,
                provider = AndroidHostResourceSignalProvider(this@MainActivity),
            )
        }

        binding.buttonStartCollection.setOnClickListener {
            if (ModalityCollectionService.isRunning) {
                return@setOnClickListener
            }
            if (missingRuntimePermissions().isNotEmpty()) {
                permissionLauncher.launch(missingRuntimePermissions().toTypedArray())
            } else {
                launchScreenCaptureThenStartService()
            }
        }
        binding.buttonStopCollection.setOnClickListener {
            ModalityCollectionService.stop(this)
            binding.root.postDelayed({ refreshCollectionUi() }, 400L)
        }
        refreshCollectionUi()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    refreshCachePanels()
                    delay(400L)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        refreshCollectionUi()
    }

    override fun onDestroy() {
        if (!ModalityCollectionService.isRunning) {
            runtime.distributedStorageManager.shutdown()
            runtime.taskScheduler.stopResourceMonitoring()
        }
        super.onDestroy()
    }

    private fun launchScreenCaptureThenStartService() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        if (mpm == null) {
            Toast.makeText(this, R.string.collection_projection_required, Toast.LENGTH_LONG).show()
            return
        }
        screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun missingRuntimePermissions(): List<String> {
        val out = ArrayList<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            out += Manifest.permission.RECORD_AUDIO
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            out += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                out += Manifest.permission.POST_NOTIFICATIONS
            }
        }
        return out
    }

    private fun refreshCollectionUi() {
        val running = ModalityCollectionService.isRunning
        binding.buttonStartCollection.isEnabled = !running
        binding.buttonStopCollection.isEnabled = running
    }

    /**
     * Reads each modality [com.app.modules.*.*Cache] [InMemoryCache.snapshot] only (30‑min sliding [BaseCache] store).
     * Does not use [edu.stanford.screenomics.core.management.CacheManager.snapshotByModality] or any disk/remote buffer.
     */
    private suspend fun refreshCachePanels() {
        val audio = runtime.audioCache.snapshot().sortedByDescending { it.metadata.timestamp }
        val motion = runtime.motionCache.snapshot().sortedByDescending { it.metadata.timestamp }
        val gps = runtime.gpsCache.snapshot().sortedByDescending { it.metadata.timestamp }
        val screenshot = runtime.screenshotCache.snapshot().sortedByDescending { it.metadata.timestamp }
        val total = audio.size + motion.size + gps.size + screenshot.size
        val stateLabel = if (ModalityCollectionService.isRunning) {
            getString(R.string.cache_monitor_collecting)
        } else {
            getString(R.string.cache_monitor_idle)
        }
        val tick = cacheUiTickFormatter.format(Instant.now())
        binding.cacheMonitorBanner.text = getString(R.string.cache_monitor_banner, stateLabel, total, tick)
        binding.cacheAudioBody.text = formatCacheSnapshot(ModalityKind.AUDIO, audio)
        binding.cacheMotionBody.text = formatCacheSnapshot(ModalityKind.MOTION, motion)
        binding.cacheGpsBody.text = formatCacheSnapshot(ModalityKind.GPS, gps)
        binding.cacheScreenshotBody.text = formatCacheSnapshot(ModalityKind.SCREENSHOT, screenshot)
    }

    private fun formatCacheSnapshot(modality: ModalityKind, points: List<UnifiedDataPoint>): String {
        if (points.isEmpty()) return getString(R.string.cache_empty)
        val header = getString(R.string.cache_snapshot_header, points.size)
        val body = points.joinToString(separator = "\n\n") { formatCachedPointLine(modality, it) }
        return "$header\n\n$body"
    }

    private fun formatCachedPointLine(modality: ModalityKind, point: UnifiedDataPoint): String {
        val ts = cachePointTimeFormatter.format(point.metadata.timestamp)
        val shortId = shortCorrelationSnippet(point.requireCorrelationId().value)
        val metric = volatileInMemoryCacheMetricLine(modality, point)
        return if (metric.isNullOrBlank()) {
            "$ts  id=$shortId"
        } else {
            "$ts  id=$shortId\n$metric"
        }
    }

    private fun shortCorrelationSnippet(full: String): String =
        if (full.length <= 10) full else "${full.take(8)}…"

    /**
     * Only the modality-specific volatile fields intended for the 30‑min in-memory window — not full UFS payloads
     * (which can also carry local-file / cloud metadata on the same [UnifiedDataPoint]).
     */
    private fun volatileInMemoryCacheMetricLine(modality: ModalityKind, point: UnifiedDataPoint): String? {
        val d = point.data
        return when (modality) {
            ModalityKind.AUDIO -> {
                val meanDb = d["audio.signal.meanDb"]
                val rmsDb = d["audio.signal.rmsDb"]
                when {
                    meanDb != null -> String.format(Locale.US, "audio.signal.meanDb=%s", meanDb)
                    rmsDb != null -> String.format(Locale.US, "audio.signal.rmsDb=%s", rmsDb)
                    else -> null
                }
            }
            ModalityKind.MOTION ->
                d["motion.step.sessionTotal"]?.let { "motion.step.sessionTotal=$it" }
            ModalityKind.GPS ->
                d["gps.weather.sunScore0To10"]?.let { "gps.weather.sunScore0To10=$it" }
            ModalityKind.SCREENSHOT ->
                d["screenshot.sentiment.score"]?.let { "screenshot.sentiment.score=$it" }
        }
    }
}
