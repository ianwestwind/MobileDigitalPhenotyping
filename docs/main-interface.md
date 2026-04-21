# Main Interface Design and Functionality

This document describes the **primary Android activity** for Stanford Screenomics: layout structure, user-visible controls, backing preferences, and how each action connects to collection, caches, edge computation, and the engine phenotype (random forest) path.

**Implementation anchors**

- **Activity:** `StanfordScreenomics/app/src/main/java/edu/stanford/screenomics/MainActivity.kt`
- **Layout:** `StanfordScreenomics/app/src/main/res/layout/activity_main.xml`
- **Copy / hints:** `StanfordScreenomics/app/src/main/res/values/strings.xml`
- **Unit labels:** `StanfordScreenomics/app/src/main/res/values/arrays.xml`
- **Value/unit bottom sheet:** `StanfordScreenomics/app/src/main/java/edu/stanford/screenomics/ui/ValueUnitPickerBottomSheet.kt`, layout `res/layout/bottom_sheet_value_unit_pickers.xml`

Paths below are relative to `MobileDigitalPhenotyping/StanfordScreenomics/` unless noted.

---

## 1. Purpose and composition

The main screen is a **single-activity** control surface: start/stop multimodal **foreground collection**, inspect **volatile in-memory caches** per modality, tune **sampling spacing** and **retention window**, configure how often the **RF phenotype analyzer** auto-runs, and read **phenotype summary** text plus **engine intervention receipts** after each train.

The UI uses **ViewBinding** (`ActivityMainBinding`), **Material 3** buttons and cards (`MaterialButton`, `MaterialCardView`), monospace bodies for cache dumps, and a **Material `BottomSheetDialog`** for numeric settings.

---

## 2. Layout hierarchy (`activity_main.xml`)

Root: **vertical `LinearLayout`**, `16dp` padding, full screen.

| Region (top → bottom) | Widgets | Role |
|------------------------|---------|------|
| **Header** | `title` (`TextView`), `subtitle` (`TextView`) | App title (`@string/welcome_title`). Subtitle is **not** set from XML; code binds **live host resource** line (CPU %, RAM MB) from `TaskScheduler.observeLastSnapshot()`. |
| **Main body** | Weighted vertical `LinearLayout` containing two **horizontal** rows | **2×2 grid** of four `MaterialCardView`s: Audio, Motion (row 1); GPS, Screenshot (row 2). Each card fills half width (`layout_weight="1"`). |
| **Collection actions** | `button_start_collection`, `button_stop_collection` | Full-width Material filled + outlined buttons. |
| **Monitor banner** | `cache_monitor_banner_container` (surface-variant background) | `cache_monitor_banner` + row with **Cache window** and **Phenotype window** outlined buttons. |
| **Engine phenotype (optional demo)** | `button_run_engine_phenotype_demo` | **Hidden** (`android:visibility="gone"`). Comment in XML: temporary; unhide to restore manual RF trigger from UI. |
| **Engine output** | `text_engine_phenotype_result`, `text_engine_intervention_receipt` | Monospace, surface-variant panels: RF **phenotype summary** report and **receipt** string after each auto/manual train. |

There is **no** `Toolbar` / `AppBar` in this layout; the activity uses the theme’s default window decor.

---

## 3. Header: title and resource subtitle

### 3.1 Title

- **Resource:** `@string/welcome_title` → “Stanford Screenomics”.
- **Style:** `?attr/textAppearanceHeadlineSmall`, centered.

### 3.2 Subtitle (dynamic)

- **Source:** `MainActivity.formatMainResourceLine(HostResourceSnapshot)` while the activity is at least **`STARTED`**.
- **Pipeline:** `PhenotypingRuntime.taskScheduler` → `startResourceMonitoring(..., AndroidHostResourceSignalProvider)` → `observeLastSnapshot()` collected in `lifecycleScope` + `repeatOnLifecycle(Lifecycle.State.STARTED)`.
- **Format:** `HostResourceSnapshot.processCpuLoad01` clamped to `[0,1]` then shown as **CPU usage** `0.0–100.0%`; RAM as **(total − available) / 1024²** MB, clamped non-negative. Joined with ` · ` via `@string/main_resource_line` (`%1$s · %2$s`).
- **Locale:** `Locale.US` in `String.format` for stable decimals.

---

## 4. Per-modality cache cards (2×2)

Each card follows the same internal pattern:

1. **Title** — `@string/cache_panel_*` (“In-memory audio”, motion, GPS, screenshot); `textAppearanceTitleSmall`.
2. **Row “Every [value] [Sec|Min]”** — label `@string/cache_volatile_interval_every` + outlined **`MaterialButton`** (`cache_*_volatile_interval_open`). Opens the **volatile interval** bottom sheet for that modality.
3. **`ScrollView`** → **`TextView`** body (`cache_*_body`) — `12sp`, monospace, **selectable** text; shows latest volatile-cache snapshot lines (newest first).

### 4.1 Live refresh of card bodies

While **`RESUMED`**, a coroutine loop runs every **`400 ms`**:

- Calls `refreshCachePanels()` (suspend): reads **`runtime.{audio,motion,gps,screenshot}Cache.snapshot()`** only (no `CacheManager` aggregation), each sorted **newest first**.
- **Banner:** `cache_monitor_banner` = `@string/cache_monitor_banner` with: idle vs collecting label, **total point count** across four caches, and a wall-clock **UI tick** (`HH:mm:ss.SSS`).
- **Bodies:** `formatCacheSnapshot` → header (`%d point(s) · volatile cache only · newest first`) then blank line then one block per point: timestamp, shortened correlation id, and a **modality-specific metric line** when present:
  - **Audio:** `audio.signal.meanDb` or else `audio.signal.rmsDb`
  - **Motion:** `motion.step.sessionTotal`
  - **GPS:** `gps.weather.sunScore0To10`
  - **Screenshot:** `screenshot.sentiment.score`
- **Empty:** `@string/cache_empty` (“No entries in the in-memory 30 min window yet.” — string is static; actual window length follows **Cache window** prefs).

### 4.2 “Every” interval buttons (per-modality volatile insert spacing)

**User action:** tap the outlined button on a card.

**UI:** `ValueUnitPickerBottomSheet.show` with:

- **Title:** modality panel title (same as card).
- **Units:** `@array/cache_volatile_interval_units_short` → **“Sec”**, **“Min”**.
- **Value:** `NumberPicker` **1–60** (inclusive), no wrap.

**Persistence:** `VolatileIntervalPrefs` (`settings/VolatileIntervalPrefs.kt`), SharedPreferences name **`cache_volatile_interval_v3`**, keys per modality: `{MODALITY}_value`, `{MODALITY}_minute`.

**Defaults when unset:**

| Modality | Default value | Default unit |
|----------|----------------|--------------|
| AUDIO | 55 | seconds |
| MOTION | 1 | minutes |
| GPS | 30 | seconds |
| SCREENSHOT | 5 | seconds |

**On commit:**

1. `VolatileIntervalPrefs.writePair`
2. `updateVolatileIntervalOpenText` — button shows e.g. `55 Sec`
3. `applyVolatileCacheIntervalToRuntime`:
   - `ModalityUserCadenceMillis.setForModality(modality, duration.toMillis())` so collection loops see cadence
   - `runtime.{modality}Cache.setVolatileInsertMinimumWallInterval(duration)` — **minimum wall time between volatile inserts** for that cache

**Resume sync:** `onResume` → `syncCacheVolatileIntervalUiAndCaches()` refreshes labels and reapplies all four intervals from prefs.

---

## 5. Start / stop collection

### 5.1 Start (`button_start_collection`)

- **Label:** `@string/start_all_modules` (“Start all modules”).
- **If already running:** click ignored (`ModalityCollectionService.isRunning`).
- **Otherwise:** if `missingRuntimePermissions()` non-empty, launches **`ActivityResultContracts.RequestMultiplePermissions()`** for:
  - `RECORD_AUDIO`
  - `ACCESS_FINE_LOCATION`
  - `POST_NOTIFICATIONS` (API 33+)
- **If permissions OK:** `launchScreenCaptureThenStartService()` → `MediaProjectionManager.createScreenCaptureIntent()` → **`StartActivityForResult`**. On **`RESULT_OK`**, `ModalityCollectionService.start(this, resultCode, data)`; short delayed `refreshCollectionUi()`.

**Rationale:** screenshot pipeline requires **MediaProjection** consent; user must approve screen capture.

### 5.2 Stop (`button_stop_collection`)

- **Label:** `@string/stop_all_modules` (“Stop collection”).
- Calls `ModalityCollectionService.stop(this)`; delayed `refreshCollectionUi()`.

### 5.3 Button enablement

`refreshCollectionUi()`: start **enabled** when not running; stop **enabled** when running.

### 5.4 Projection re-consent

`EXTRA_REQUEST_MEDIA_PROJECTION_AGAIN` on `Intent`: if set, `scheduleProjectionRelaunchFlow()` posts a delayed runnable that re-opens capture intent when collection is still on but projection capture is not running (e.g. after service-driven re-prompt).

---

## 6. Cache window and phenotype window (banner row)

Both use the same **`ValueUnitPickerBottomSheet`** pattern with **`@array/cache_window_units_min_hr`**: **“Min”**, **“Hr”**; value **1–60**.

### 6.1 Cache window (`cache_window_open`)

- **Label column:** `@string/cache_window_label` (“Cache window”).
- **Meaning:** shared **sliding TTL / edge slice duration** for volatile caches and `CachedWindowSelector` — persisted as **`VolatileCacheWindowPrefs`** (`volatile_cache_window_v1`), default **30 minutes** if unset.
- **On commit:** writes prefs → `VolatileCacheWindowRetention.setDuration(...)` → `scheduleGlobalCacheSweep()` (`cacheManager.sweepAllRegisteredSlidingWindow` with current `SlidingWindowTtlSpec`).
- **On resume:** `syncCacheWindowUiAndSweep()` — label + `VolatileCacheWindowPrefs.syncRetentionFromPrefs` + sweep.

### 6.2 Phenotype window (`phenotype_window_open`)

- **Label column:** `@string/phenotype_window_label` (“Phenotype window”).
- **Meaning:** interval between **automatic RF phenotype trains** (`PhenotypeWindowPrefs`, `phenotype_auto_window_v1`), default **5 minutes** if unset; **`periodMillis`** enforces **minimum 60 000 ms**.
- **On commit:** writes prefs → updates label → **`launchPhenotypeAutoTicker()`** (cancels prior job, restarts loop).

---

## 7. Engine phenotype: RF output on the main screen

### 7.1 Hint text (static until first train)

- **`text_engine_phenotype_result`:** `@string/engine_phenotype_result_hint` — explains regression RF on four caches, alignment to motion step totals, data requirements.
- **`text_engine_intervention_receipt`:** `@string/engine_intervention_receipt_hint` — describes `engineIntervention` receipts and **PHENOTYPE_UPDATED**.

### 7.2 Auto train loop

- **Started in `onCreate`:** `launchPhenotypeAutoTicker()`.
- **Scope:** `lifecycleScope` + `repeatOnLifecycle(Lifecycle.State.STARTED)`.
- **Body:** mutex-locked `runPhenotypeTrainLocked()` → `snapshotCachesAndTrainPhenotype()` on **Default** dispatcher → `EnginePhenotypeStepCountRandomForest.trainWithInterventionReceipt(audio, motion, gps, screenshot snapshots)`.
- **UI success:** `applyPhenotypeResultToUi` sets phenotype report string and intervention receipt string on the two `TextView`s.
- **Failure:** `applyPhenotypeFailureToUi` — error text + `EngineInterventionReceipts.acknowledgePipelineFailure(...)`.
- **Delay after each iteration:** `PhenotypeWindowPrefs.periodMillis(this)`.

### 7.3 Manual “Run engine phenotype” button

- **Layout:** `button_run_engine_phenotype_demo` is **`gone`**; XML comment says remove visibility to show again.
- **Code:** listener is **commented out** in `MainActivity` (same “TEMPORARY” note). Manual one-shot train is therefore **disabled** unless a developer restores the listener and visibility.

---

## 8. `PhenotypingRuntime` construction (when service not already running)

On first `onCreate` without an active `ModalityCollectionService`, `MainActivity` builds:

- `DefaultEdgeComputationEngine` with `AndroidTfliteInterpreterBridge` (`modelAssetPath = null` in snippet)
- `DefaultTaskScheduler`, `DefaultInterventionController`, `DefaultCacheManager`
- `DefaultDistributedStorageManager` with Android bridges (Firestore, Cloud Storage, Realtime DB)
- Four modality caches with **`onAfterUnifiedPointCommittedOutsideLock`** → `distributedStorageManager.onUnifiedPointCommitted`
- **`PhenotypingRuntime`** stored on **`ScreenomicsApp.phenotypingRuntime`**

If the service **is** already running and a runtime exists, the activity **reuses** `app.phenotypingRuntime`.

**Registry:** `PerModalityCacheRegistrySession.registerStandardModalities` runs when the service is not running (caches registered with `cacheManager`).

**Scheduler:** registers a bootstrap task and starts **resource monitoring** with `AndroidHostResourceSignalProvider`.

**Lifecycle bridge:** `AndroidCacheManagerLifecycleBridge` attaches the cache manager to the activity lifecycle.

---

## 9. Lifecycle and teardown

| Callback | Main UI–relevant behavior |
|----------|---------------------------|
| **`onStart`** | `refreshCollectionUi()` |
| **`onResume`** | Sync volatile intervals + caches; cache window prefs + sweep; phenotype window label |
| **`onDestroy`** | Remove projection relaunch callbacks; **cancel** `phenotypeAutoTickerJob`; if collection **not** running: `distributedStorageManager.shutdown()`, `taskScheduler.stopResourceMonitoring()` |

---

## 10. Related documentation

- **Architecture and data flow:** `doc/architecture.md` (especially §5.1 narrative, §2.5 edge table, §4 phenotype).
- **Phenotype vs edge summary terminology:** `doc/phenotype.md`.
- **Storage and cache spec:** repository root `Data Cache and Storage Location`.

---

*Update this file when `activity_main.xml`, `MainActivity.kt`, or picker/prefs contracts change.*
