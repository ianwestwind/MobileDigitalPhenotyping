package edu.stanford.screenomics

import android.Manifest
import android.app.Activity
import android.content.Intent
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
import com.app.engineintervention.EngineInterventionReceipts
import com.app.enginephenotype.EnginePhenotypeStepCountRandomForest
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
import edu.stanford.screenomics.core.management.SlidingWindowTtlSpec
import edu.stanford.screenomics.core.management.VolatileCacheWindowRetention
import edu.stanford.screenomics.core.edge.DefaultEdgeComputationEngine
import edu.stanford.screenomics.core.edge.EdgeComputationEngine
import edu.stanford.screenomics.core.scheduling.DefaultTaskScheduler
import edu.stanford.screenomics.core.scheduling.HostResourceSnapshot
import edu.stanford.screenomics.core.scheduling.TaskPriority
import edu.stanford.screenomics.core.scheduling.TaskScheduler
import edu.stanford.screenomics.core.collection.ModalityUserCadenceMillis
import edu.stanford.screenomics.core.storage.DefaultDistributedStorageManager
import edu.stanford.screenomics.core.unified.ModalityKind
import edu.stanford.screenomics.core.unified.UnifiedDataPoint
import edu.stanford.screenomics.core.unified.requireCorrelationId
import edu.stanford.screenomics.databinding.ActivityMainBinding
import edu.stanford.screenomics.settings.PhenotypeWindowPrefs
import edu.stanford.screenomics.settings.VolatileCacheWindowPrefs
import edu.stanford.screenomics.settings.VolatileIntervalPrefs
import edu.stanford.screenomics.ui.ValueUnitPickerBottomSheet
import edu.stanford.screenomics.edge.AndroidTfliteInterpreterBridge
import edu.stanford.screenomics.scheduling.AndroidHostResourceSignalProvider
import edu.stanford.screenomics.storage.AndroidCloudMediaStorageBridge
import edu.stanford.screenomics.storage.AndroidFirestoreStructuredWriteBridge
import edu.stanford.screenomics.storage.AndroidModalityLocalFileSink
import edu.stanford.screenomics.storage.AndroidRealtimeStructuredWriteBridge
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_REQUEST_MEDIA_PROJECTION_AGAIN: String =
            "edu.stanford.screenomics.extra.REQUEST_MEDIA_PROJECTION_AGAIN"
    }

    private val cacheUiTickFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.US).withZone(ZoneId.systemDefault())

    private val cachePointTimeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.US).withZone(ZoneId.systemDefault())

    private lateinit var binding: ActivityMainBinding

    private lateinit var runtime: PhenotypingRuntime

    private lateinit var cacheLifecycleBridge: AndroidCacheManagerLifecycleBridge

    /** Serializes manual RF runs and periodic auto runs so they never overlap. */
    private val phenotypeTrainMutex = Mutex()

    private var phenotypeAutoTickerJob: Job? = null

    private val projectionRelaunchRunnable = Runnable {
        if (isFinishing) {
            return@Runnable
        }
        if (!ModalityCollectionService.isRunning) {
            return@Runnable
        }
        if ((application as ScreenomicsApp).mediaProjectionCapture.isRunning()) {
            return@Runnable
        }
        launchScreenCaptureThenStartService()
    }

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
        binding.buttonRunEnginePhenotypeDemo.setOnClickListener {
            lifecycleScope.launch {
                binding.buttonRunEnginePhenotypeDemo.isEnabled = false
                binding.textEnginePhenotypeResult.text = getString(R.string.engine_phenotype_running)
                binding.textEngineInterventionReceipt.text = getString(R.string.engine_intervention_receipt_waiting)
                try {
                    runCatching {
                        runPhenotypeTrainLocked()
                    }.fold(
                        onSuccess = { bundle -> applyPhenotypeResultToUi(bundle) },
                        onFailure = { e -> applyPhenotypeFailureToUi(e) },
                    )
                } finally {
                    binding.buttonRunEnginePhenotypeDemo.isEnabled = true
                }
            }
        }
        refreshCollectionUi()
        if (consumeProjectionRelaunchExtra(intent)) {
            scheduleProjectionRelaunchFlow()
        }
        wireVolatileIntervalOpenButtons()
        VolatileCacheWindowPrefs.syncRetentionFromPrefs(this)
        wireCacheWindowOpenButton()
        wirePhenotypeWindowOpenButton()
        refreshVolatileIntervalOpenLabels()
        updateCacheWindowOpenLabel()
        updatePhenotypeWindowOpenLabel()
        applyAllVolatileCacheIntervalsFromPrefs()
        launchPhenotypeAutoTicker()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    refreshCachePanels()
                    delay(400L)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                runtime.taskScheduler.observeLastSnapshot().collect { snap ->
                    binding.subtitle.text = formatMainResourceLine(snap)
                }
            }
        }
    }

    /** CPU % from `processCpuLoad01` (0–1 → 0–100%). RAM MB = \((total - avail) / 1024^2\). All clamps + Locale.US formatting. */
    private fun formatMainResourceLine(snap: HostResourceSnapshot): String {
        val load01 = snap.processCpuLoad01
        val frac = when {
            !load01.isFinite() -> 0.0
            load01 < 0.0 -> 0.0
            load01 > 1.0 -> 1.0
            else -> load01
        }
        val pct = (frac * 100.0).coerceIn(0.0, 100.0)
        val usedMb = usedRamMegabytes(snap).coerceAtLeast(0.0)
        val cpuSeg = String.format(Locale.US, "CPU usage %.1f%%", pct)
        val ramSeg = String.format(Locale.US, "RAM usage %.0f MB", usedMb)
        return getString(R.string.main_resource_line, cpuSeg, ramSeg)
    }

    private fun usedRamMegabytes(snap: HostResourceSnapshot): Double {
        val total = snap.totalMemoryBytes
        val avail = snap.availableMemoryBytes
        if (total <= 0L) return 0.0
        val usedBytes = (total - avail).coerceAtLeast(0L)
        return usedBytes.toDouble() / (1024.0 * 1024.0)
    }

    override fun onStart() {
        super.onStart()
        refreshCollectionUi()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (consumeProjectionRelaunchExtra(intent)) {
            scheduleProjectionRelaunchFlow()
        }
    }

    override fun onResume() {
        super.onResume()
        syncCacheVolatileIntervalUiAndCaches()
        syncCacheWindowUiAndSweep()
        syncPhenotypeWindowUi()
    }

    override fun onDestroy() {
        if (::binding.isInitialized) {
            binding.root.removeCallbacks(projectionRelaunchRunnable)
        }
        phenotypeAutoTickerJob?.cancel()
        if (!ModalityCollectionService.isRunning) {
            runtime.distributedStorageManager.shutdown()
            runtime.taskScheduler.stopResourceMonitoring()
        }
        super.onDestroy()
    }

    private fun consumeProjectionRelaunchExtra(incoming: Intent): Boolean {
        if (!incoming.getBooleanExtra(EXTRA_REQUEST_MEDIA_PROJECTION_AGAIN, false)) {
            return false
        }
        incoming.removeExtra(EXTRA_REQUEST_MEDIA_PROJECTION_AGAIN)
        return true
    }

    /**
     * Called after [ModalityCollectionService] wakes the UI for a new MediaProjection consent while collection stays on.
     */
    private fun scheduleProjectionRelaunchFlow() {
        if (!ModalityCollectionService.isRunning) {
            return
        }
        val app = application as ScreenomicsApp
        if (app.mediaProjectionCapture.isRunning()) {
            return
        }
        binding.root.removeCallbacks(projectionRelaunchRunnable)
        binding.root.postDelayed(projectionRelaunchRunnable, 500L)
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

    private fun volatileIntervalUnitLabels(): Array<String> =
        resources.getStringArray(R.array.cache_volatile_interval_units_short)

    private fun cacheWindowUnitLabels(): Array<String> =
        resources.getStringArray(R.array.cache_window_units_min_hr)

    private fun formatValueUnitSummary(value: Int, unitWord: String): String = "$value $unitWord"

    private fun volatileIntervalSheetTitle(modality: ModalityKind): String =
        when (modality) {
            ModalityKind.AUDIO -> getString(R.string.cache_panel_audio)
            ModalityKind.MOTION -> getString(R.string.cache_panel_motion)
            ModalityKind.GPS -> getString(R.string.cache_panel_gps)
            ModalityKind.SCREENSHOT -> getString(R.string.cache_panel_screenshot)
        }

    private fun updateVolatileIntervalOpenText(modality: ModalityKind) {
        val (value, useMinutes) = VolatileIntervalPrefs.readPair(this, modality)
        val labels = volatileIntervalUnitLabels()
        val text = formatValueUnitSummary(value, labels[if (useMinutes) 1 else 0])
        when (modality) {
            ModalityKind.AUDIO -> binding.cacheAudioVolatileIntervalOpen.text = text
            ModalityKind.MOTION -> binding.cacheMotionVolatileIntervalOpen.text = text
            ModalityKind.GPS -> binding.cacheGpsVolatileIntervalOpen.text = text
            ModalityKind.SCREENSHOT -> binding.cacheScreenshotVolatileIntervalOpen.text = text
        }
    }

    private fun refreshVolatileIntervalOpenLabels() {
        for (m in arrayOf(ModalityKind.AUDIO, ModalityKind.MOTION, ModalityKind.GPS, ModalityKind.SCREENSHOT)) {
            updateVolatileIntervalOpenText(m)
        }
    }

    private fun wireVolatileIntervalOpenButtons() {
        binding.cacheAudioVolatileIntervalOpen.setOnClickListener { openVolatileIntervalSheet(ModalityKind.AUDIO) }
        binding.cacheMotionVolatileIntervalOpen.setOnClickListener { openVolatileIntervalSheet(ModalityKind.MOTION) }
        binding.cacheGpsVolatileIntervalOpen.setOnClickListener { openVolatileIntervalSheet(ModalityKind.GPS) }
        binding.cacheScreenshotVolatileIntervalOpen.setOnClickListener {
            openVolatileIntervalSheet(ModalityKind.SCREENSHOT)
        }
    }

    private fun openVolatileIntervalSheet(modality: ModalityKind) {
        val (value, useMinutes) = VolatileIntervalPrefs.readPair(this, modality)
        ValueUnitPickerBottomSheet.show(
            context = this,
            title = volatileIntervalSheetTitle(modality),
            unitLabels = volatileIntervalUnitLabels(),
            initialValue = value,
            initialUnitIndex = if (useMinutes) 1 else 0,
        ) { newValue, unitIndex ->
            val minutes = unitIndex == 1
            VolatileIntervalPrefs.writePair(this, modality, newValue, minutes)
            updateVolatileIntervalOpenText(modality)
            applyVolatileCacheIntervalToRuntime(modality, newValue to minutes)
        }
    }

    private fun syncCacheVolatileIntervalUiAndCaches() {
        refreshVolatileIntervalOpenLabels()
        applyAllVolatileCacheIntervalsFromPrefs()
    }

    private fun applyVolatileCacheIntervalToRuntime(modality: ModalityKind, valueAndMinutes: Pair<Int, Boolean>) {
        val (value, useMinutes) = valueAndMinutes
        val duration = VolatileIntervalPrefs.duration(value, useMinutes)
        ModalityUserCadenceMillis.setForModality(modality, duration.toMillis())
        when (modality) {
            ModalityKind.AUDIO -> runtime.audioCache.setVolatileInsertMinimumWallInterval(duration)
            ModalityKind.MOTION -> runtime.motionCache.setVolatileInsertMinimumWallInterval(duration)
            ModalityKind.GPS -> runtime.gpsCache.setVolatileInsertMinimumWallInterval(duration)
            ModalityKind.SCREENSHOT -> runtime.screenshotCache.setVolatileInsertMinimumWallInterval(duration)
        }
    }

    private fun applyAllVolatileCacheIntervalsFromPrefs() {
        for (m in arrayOf(ModalityKind.AUDIO, ModalityKind.MOTION, ModalityKind.GPS, ModalityKind.SCREENSHOT)) {
            applyVolatileCacheIntervalToRuntime(m, VolatileIntervalPrefs.readPair(this, m))
        }
    }

    private fun updateCacheWindowOpenLabel() {
        val (value, useHours) = VolatileCacheWindowPrefs.readPair(this)
        val labels = cacheWindowUnitLabels()
        binding.cacheWindowOpen.text = formatValueUnitSummary(value, labels[if (useHours) 1 else 0])
    }

    private fun wireCacheWindowOpenButton() {
        binding.cacheWindowOpen.setOnClickListener {
            val (value, useHours) = VolatileCacheWindowPrefs.readPair(this)
            ValueUnitPickerBottomSheet.show(
                context = this,
                title = getString(R.string.cache_window_label),
                unitLabels = cacheWindowUnitLabels(),
                initialValue = value,
                initialUnitIndex = if (useHours) 1 else 0,
            ) { newValue, unitIndex ->
                val hours = unitIndex == 1
                VolatileCacheWindowPrefs.writePair(this@MainActivity, newValue, hours)
                updateCacheWindowOpenLabel()
                VolatileCacheWindowRetention.setDuration(VolatileCacheWindowPrefs.duration(newValue, hours))
                scheduleGlobalCacheSweep()
            }
        }
    }

    private fun scheduleGlobalCacheSweep() {
        lifecycleScope.launch(Dispatchers.Default) {
            runCatching {
                runtime.cacheManager.sweepAllRegisteredSlidingWindow(
                    SlidingWindowTtlSpec(windowDuration = VolatileCacheWindowRetention.duration()),
                )
            }
        }
    }

    private fun syncCacheWindowUiAndSweep() {
        updateCacheWindowOpenLabel()
        VolatileCacheWindowPrefs.syncRetentionFromPrefs(this)
        scheduleGlobalCacheSweep()
    }

    private fun syncPhenotypeWindowUi() {
        updatePhenotypeWindowOpenLabel()
    }

    private fun updatePhenotypeWindowOpenLabel() {
        val (value, useHours) = PhenotypeWindowPrefs.readPair(this)
        val labels = cacheWindowUnitLabels()
        binding.phenotypeWindowOpen.text = formatValueUnitSummary(value, labels[if (useHours) 1 else 0])
    }

    private fun launchPhenotypeAutoTicker() {
        phenotypeAutoTickerJob?.cancel()
        phenotypeAutoTickerJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    runCatching {
                        runPhenotypeTrainLocked()
                    }.fold(
                        onSuccess = { bundle -> applyPhenotypeResultToUi(bundle) },
                        onFailure = { e -> applyPhenotypeFailureToUi(e) },
                    )
                    val periodMs = PhenotypeWindowPrefs.periodMillis(this@MainActivity)
                    delay(periodMs)
                }
            }
        }
    }

    private suspend fun runPhenotypeTrainLocked(): EnginePhenotypeStepCountRandomForest.PhenotypeRunUiBundle =
        phenotypeTrainMutex.withLock {
            snapshotCachesAndTrainPhenotype()
        }

    private suspend fun snapshotCachesAndTrainPhenotype(): EnginePhenotypeStepCountRandomForest.PhenotypeRunUiBundle =
        withContext(Dispatchers.Default) {
            val audio = runtime.audioCache.snapshot()
            val motion = runtime.motionCache.snapshot()
            val gps = runtime.gpsCache.snapshot()
            val screenshot = runtime.screenshotCache.snapshot()
            EnginePhenotypeStepCountRandomForest.trainWithInterventionReceipt(
                audioPoints = audio,
                motionPoints = motion,
                gpsPoints = gps,
                screenshotPoints = screenshot,
            )
        }

    private fun applyPhenotypeResultToUi(bundle: EnginePhenotypeStepCountRandomForest.PhenotypeRunUiBundle) {
        binding.textEnginePhenotypeResult.text = bundle.phenotypeReport
        binding.textEngineInterventionReceipt.text = bundle.interventionReceipt
    }

    private fun applyPhenotypeFailureToUi(e: Throwable) {
        binding.textEnginePhenotypeResult.text = getString(
            R.string.engine_phenotype_error,
            e.message ?: e.javaClass.simpleName,
        )
        binding.textEngineInterventionReceipt.text =
            EngineInterventionReceipts.acknowledgePipelineFailure(
                e.message ?: e.javaClass.simpleName,
            )
    }

    private fun wirePhenotypeWindowOpenButton() {
        binding.phenotypeWindowOpen.setOnClickListener {
            val (value, useHours) = PhenotypeWindowPrefs.readPair(this)
            ValueUnitPickerBottomSheet.show(
                context = this,
                title = getString(R.string.phenotype_window_label),
                unitLabels = cacheWindowUnitLabels(),
                initialValue = value,
                initialUnitIndex = if (useHours) 1 else 0,
            ) { newValue, unitIndex ->
                val hours = unitIndex == 1
                PhenotypeWindowPrefs.writePair(this@MainActivity, newValue, hours)
                updatePhenotypeWindowOpenLabel()
                launchPhenotypeAutoTicker()
            }
        }
    }
}
