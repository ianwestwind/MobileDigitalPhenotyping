package edu.stanford.screenomics

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
import edu.stanford.screenomics.core.scheduling.TaskPriority
import edu.stanford.screenomics.core.scheduling.TaskScheduler
import edu.stanford.screenomics.core.collection.ModalityUserCadenceMillis
import edu.stanford.screenomics.core.storage.DefaultDistributedStorageManager
import edu.stanford.screenomics.core.unified.ModalityKind
import edu.stanford.screenomics.core.unified.UnifiedDataPoint
import edu.stanford.screenomics.core.unified.requireCorrelationId
import edu.stanford.screenomics.databinding.ActivityMainBinding
import edu.stanford.screenomics.settings.VolatileCacheWindowPrefs
import edu.stanford.screenomics.settings.VolatileIntervalPrefs
import edu.stanford.screenomics.ui.UnitSpinnerSelectionCallbacks
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private val cacheUiTickFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.US).withZone(ZoneId.systemDefault())

    private val cachePointTimeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.US).withZone(ZoneId.systemDefault())

    private lateinit var binding: ActivityMainBinding

    private lateinit var runtime: PhenotypingRuntime

    private lateinit var cacheLifecycleBridge: AndroidCacheManagerLifecycleBridge

    private var suppressCacheVolatileIntervalEvents: Boolean = false

    private var suppressCacheWindowUiEvents: Boolean = false

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
                try {
                    val message = runCatching {
                        withContext(Dispatchers.Default) {
                            val audio = runtime.audioCache.snapshot()
                            val motion = runtime.motionCache.snapshot()
                            val gps = runtime.gpsCache.snapshot()
                            val screenshot = runtime.screenshotCache.snapshot()
                            val result = EnginePhenotypeStepCountRandomForest.trainFromVolatileCacheSnapshots(
                                audioPoints = audio,
                                motionPoints = motion,
                                gpsPoints = gps,
                                screenshotPoints = screenshot,
                            )
                            EnginePhenotypeStepCountRandomForest.formatTrainResultReport(
                                result,
                                audioPoints = audio,
                                motionPoints = motion,
                                gpsPoints = gps,
                                screenshotPoints = screenshot,
                            )
                        }
                    }.getOrElse { e ->
                        getString(R.string.engine_phenotype_error, e.message ?: e.javaClass.simpleName)
                    }
                    binding.textEnginePhenotypeResult.text = message
                } finally {
                    binding.buttonRunEnginePhenotypeDemo.isEnabled = true
                }
            }
        }
        refreshCollectionUi()
        wireCacheVolatileIntervalControls()
        VolatileCacheWindowPrefs.syncRetentionFromPrefs(this)
        wireCacheWindowControls()

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

    override fun onResume() {
        super.onResume()
        syncCacheVolatileIntervalUiAndCaches()
        syncCacheWindowUiAndSweep()
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

    private fun wireCacheVolatileIntervalControls() {
        setupCacheVolatileIntervalRow(ModalityKind.AUDIO, binding.cacheAudioVolatileIntervalValue, binding.cacheAudioVolatileIntervalUnit)
        setupCacheVolatileIntervalRow(ModalityKind.MOTION, binding.cacheMotionVolatileIntervalValue, binding.cacheMotionVolatileIntervalUnit)
        setupCacheVolatileIntervalRow(ModalityKind.GPS, binding.cacheGpsVolatileIntervalValue, binding.cacheGpsVolatileIntervalUnit)
        setupCacheVolatileIntervalRow(
            ModalityKind.SCREENSHOT,
            binding.cacheScreenshotVolatileIntervalValue,
            binding.cacheScreenshotVolatileIntervalUnit,
        )
        applyAllVolatileCacheIntervalsFromPrefs()
    }

    private fun syncCacheVolatileIntervalUiAndCaches() {
        suppressCacheVolatileIntervalEvents = true
        try {
            bindVolatileIntervalRowFromPrefs(ModalityKind.AUDIO, binding.cacheAudioVolatileIntervalValue, binding.cacheAudioVolatileIntervalUnit)
            bindVolatileIntervalRowFromPrefs(ModalityKind.MOTION, binding.cacheMotionVolatileIntervalValue, binding.cacheMotionVolatileIntervalUnit)
            bindVolatileIntervalRowFromPrefs(ModalityKind.GPS, binding.cacheGpsVolatileIntervalValue, binding.cacheGpsVolatileIntervalUnit)
            bindVolatileIntervalRowFromPrefs(
                ModalityKind.SCREENSHOT,
                binding.cacheScreenshotVolatileIntervalValue,
                binding.cacheScreenshotVolatileIntervalUnit,
            )
        } finally {
            suppressCacheVolatileIntervalEvents = false
        }
        applyAllVolatileCacheIntervalsFromPrefs()
    }

    private fun bindVolatileIntervalRowFromPrefs(modality: ModalityKind, valueField: EditText, unitSpinner: Spinner) {
        val (value, useMinutes) = VolatileIntervalPrefs.readPair(this, modality)
        valueField.setText(value.toString())
        unitSpinner.setSelection(if (useMinutes) 1 else 0, false)
    }

    private fun setupCacheVolatileIntervalRow(modality: ModalityKind, valueField: EditText, unitSpinner: Spinner) {
        val adapter = ArrayAdapter.createFromResource(
            this,
            R.array.cache_volatile_interval_units_short,
            android.R.layout.simple_spinner_item,
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        suppressCacheVolatileIntervalEvents = true
        try {
            unitSpinner.adapter = adapter
            bindVolatileIntervalRowFromPrefs(modality, valueField, unitSpinner)
        } finally {
            suppressCacheVolatileIntervalEvents = false
        }

        fun applyFromFields(persist: Boolean) {
            if (suppressCacheVolatileIntervalEvents) return
            val raw = valueField.text?.toString()?.trim().orEmpty()
            val num = raw.toIntOrNull()
            if (num == null || num !in 1..60) {
                Toast.makeText(this, R.string.cache_volatile_interval_invalid, Toast.LENGTH_SHORT).show()
                suppressCacheVolatileIntervalEvents = true
                try {
                    bindVolatileIntervalRowFromPrefs(modality, valueField, unitSpinner)
                } finally {
                    suppressCacheVolatileIntervalEvents = false
                }
                applyVolatileCacheIntervalToRuntime(modality, VolatileIntervalPrefs.readPair(this, modality))
                return
            }
            val useMinutes = unitSpinner.selectedItemPosition == 1
            if (persist) {
                VolatileIntervalPrefs.writePair(this, modality, num, useMinutes)
            }
            applyVolatileCacheIntervalToRuntime(modality, num to useMinutes)
        }

        unitSpinner.onItemSelectedListener = UnitSpinnerSelectionCallbacks.onItemSelected {
            applyFromFields(persist = true)
        }
        valueField.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) applyFromFields(persist = true)
        }
        valueField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                applyFromFields(persist = true)
                true
            } else {
                false
            }
        }

        // Real-time apply while typing: debounce so transient empty/partial input does not toast or clobber prefs.
        val debouncedPersistAndApply = Runnable {
            if (suppressCacheVolatileIntervalEvents) return@Runnable
            val raw = valueField.text?.toString()?.trim().orEmpty()
            val num = raw.toIntOrNull() ?: return@Runnable
            if (num !in 1..60) return@Runnable
            val useMinutes = unitSpinner.selectedItemPosition == 1
            VolatileIntervalPrefs.writePair(this@MainActivity, modality, num, useMinutes)
            applyVolatileCacheIntervalToRuntime(modality, num to useMinutes)
        }
        valueField.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    valueField.removeCallbacks(debouncedPersistAndApply)
                    valueField.postDelayed(debouncedPersistAndApply, 280L)
                }
            },
        )
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

    private fun bindCacheWindowRowFromPrefs() {
        val (value, useHours) = VolatileCacheWindowPrefs.readPair(this)
        binding.cacheWindowValue.setText(value.toString())
        binding.cacheWindowUnit.setSelection(if (useHours) 1 else 0, false)
    }

    private fun wireCacheWindowControls() {
        val adapter = ArrayAdapter.createFromResource(
            this,
            R.array.cache_window_units_min_hr,
            android.R.layout.simple_spinner_item,
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        suppressCacheWindowUiEvents = true
        try {
            binding.cacheWindowUnit.adapter = adapter
            bindCacheWindowRowFromPrefs()
        } finally {
            suppressCacheWindowUiEvents = false
        }

        fun applyWindowFromFields(persist: Boolean) {
            if (suppressCacheWindowUiEvents) return
            val raw = binding.cacheWindowValue.text?.toString()?.trim().orEmpty()
            val num = raw.toIntOrNull()
            if (num == null || num !in 1..60) {
                Toast.makeText(this, R.string.cache_window_invalid, Toast.LENGTH_SHORT).show()
                suppressCacheWindowUiEvents = true
                try {
                    bindCacheWindowRowFromPrefs()
                } finally {
                    suppressCacheWindowUiEvents = false
                }
                VolatileCacheWindowPrefs.syncRetentionFromPrefs(this@MainActivity)
                scheduleGlobalCacheSweep()
                return
            }
            val useHours = binding.cacheWindowUnit.selectedItemPosition == 1
            if (persist) {
                VolatileCacheWindowPrefs.writePair(this@MainActivity, num, useHours)
            }
            VolatileCacheWindowRetention.setDuration(VolatileCacheWindowPrefs.duration(num, useHours))
            scheduleGlobalCacheSweep()
        }

        binding.cacheWindowUnit.onItemSelectedListener = UnitSpinnerSelectionCallbacks.onRunnable {
            applyWindowFromFields(persist = true)
        }
        binding.cacheWindowValue.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) applyWindowFromFields(persist = true)
        }
        binding.cacheWindowValue.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                applyWindowFromFields(persist = true)
                true
            } else {
                false
            }
        }
        val debouncedWindowApply = Runnable {
            if (suppressCacheWindowUiEvents) return@Runnable
            val raw = binding.cacheWindowValue.text?.toString()?.trim().orEmpty()
            val num = raw.toIntOrNull() ?: return@Runnable
            if (num !in 1..60) return@Runnable
            val useHours = binding.cacheWindowUnit.selectedItemPosition == 1
            VolatileCacheWindowPrefs.writePair(this@MainActivity, num, useHours)
            VolatileCacheWindowRetention.setDuration(VolatileCacheWindowPrefs.duration(num, useHours))
            scheduleGlobalCacheSweep()
        }
        binding.cacheWindowValue.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    binding.cacheWindowValue.removeCallbacks(debouncedWindowApply)
                    binding.cacheWindowValue.postDelayed(debouncedWindowApply, 280L)
                }
            },
        )
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
        suppressCacheWindowUiEvents = true
        try {
            bindCacheWindowRowFromPrefs()
        } finally {
            suppressCacheWindowUiEvents = false
        }
        VolatileCacheWindowPrefs.syncRetentionFromPrefs(this)
        scheduleGlobalCacheSweep()
    }
}
