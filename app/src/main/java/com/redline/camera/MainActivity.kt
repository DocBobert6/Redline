package com.redline.camera

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.OrientationEventListener
import android.view.Surface
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.edit
import com.redline.camera.capability.CameraFacts
import com.redline.camera.capability.CapabilityProbe
import com.redline.camera.capability.CapabilityReport
import com.redline.camera.capability.CapabilityState
import com.redline.camera.capability.CombinationChecker
import com.redline.camera.capability.DebugOverrides
import com.redline.camera.capability.DeviceProfile
import com.redline.camera.capability.DeviceProfileProbe
import com.redline.camera.capability.FactoryCalibration
import com.redline.camera.capability.FeaturePolicy
import com.redline.camera.capability.QuirksRepository
import com.redline.camera.capability.SelfTest
import com.redline.camera.capability.StillPath
import com.redline.camera.capture.BurstReport
import com.redline.camera.capture.CaptureStats
import com.redline.camera.capture.ShotResult
import com.redline.camera.colour.ColourPipeline
import com.redline.camera.colour.ColourPipelineReport
import com.redline.camera.colour.ProfileChoice
import com.redline.camera.look.CubeLut
import com.redline.camera.look.LookPreviewRenderer
import com.redline.camera.look.LookPreviewReport
import com.redline.camera.manual.ManualApplied
import com.redline.camera.manual.ManualCaps
import com.redline.camera.manual.ManualMath
import com.redline.camera.manual.ManualModel
import com.redline.camera.manual.ManualSettings
import com.redline.camera.manual.ShootMode
import com.redline.camera.nativebridge.RedlineNative
import com.redline.camera.ui.CapabilityScreen
import com.redline.camera.ui.ManualActions
import com.redline.camera.ui.ManualUi
import com.redline.camera.ui.ViewfinderScreen
import com.redline.camera.viewfinder.ViewfinderController
import com.redline.camera.viewfinder.ViewfinderMetrics
import com.redline.camera.viewfinder.ViewfinderSetup
import com.redline.camera.viewfinder.ZoomStops
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Opens on the 3:2 viewfinder (B12); the capability screen (B10/B11) is one tap away.
 *
 * Cold start is kept lean: the cached device profile is loaded off the main thread, the camera opens as soon
 * as the profile and the preview surface are ready, and the combination checks, the self-test and the
 * capability-screen probes wait for the first preview frame. The self-test and the viewfinder never hold the
 * camera at the same time: the preview is closed (and its close awaited) before a self-test run, then reopened.
 */
class MainActivity : ComponentActivity() {
    private enum class Screen { VIEWFINDER, CAPABILITY }

    private val state = mutableStateOf(CapabilityState())
    private val profileState = mutableStateOf<DeviceProfile?>(null)
    private val cameraFacts = mutableStateOf<List<CameraFacts>>(emptyList())
    private val calibrations = mutableStateOf<Map<String, FactoryCalibration>>(emptyMap())
    private val screen = mutableStateOf(Screen.VIEWFINDER)
    private val setupState = mutableStateOf<ViewfinderSetup?>(null)
    private val selectedStop = mutableIntStateOf(ZoomStops.DEFAULT_STOP)
    private val status = mutableStateOf<String?>(null)
    private val permissionGranted = mutableStateOf(false)

    /** B13: save a DNG next to the JPEG. Off by default (PLAN 5: RAW file is a toggle; B21 captures RAW for the look regardless). */
    private val rawOn = mutableStateOf(false)
    private val shotInFlight = mutableStateOf(false)
    private val streaming = mutableStateOf(false)
    private var deviceDegrees = 0
    private var stressPending = false
    private var burstStressPending = false
    private var stressAbort = false

    // B14 manual controls and pro tools (main thread).
    private val manualSettings = mutableStateOf(ManualSettings())
    private val manualApplied = mutableStateOf<ManualApplied?>(null)
    private val gridOn = mutableStateOf(FeaturePolicy.GRID_ON_BY_DEFAULT)
    private val levelOn = mutableStateOf(FeaturePolicy.LEVEL_ON_BY_DEFAULT)
    private val histogramOn = mutableStateOf(FeaturePolicy.HISTOGRAM_ON_BY_DEFAULT)
    private val histogram = mutableStateOf<IntArray?>(null)
    private val tilt = mutableStateOf<Float?>(null)
    private val liveText = mutableStateOf<String?>(null)
    private var lastAuto: ViewfinderController.ResultValues? = null
    private var previewView: TextureView? = null
    private var histBitmap: Bitmap? = null
    private val histPixels = IntArray(HIST_W * HIST_H)
    private var sensorManager: SensorManager? = null
    private var levelSensor: Sensor? = null
    private var levelRegistered = false
    private var lastTiltMs = 0L
    private val gravity = FloatArray(3)
    private val rotation = FloatArray(9)

    private val profile: DeviceProfile? get() = profileState.value

    // Main-thread state.
    private var createdAtMs = 0L
    private var texture: SurfaceTexture? = null
    private var resumed = false
    private var cameraOpen = false
    private var lastClose: CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)
    private var appliedStabilization = false

    /** Test-only override of preview stabilization from the Info screen (null = follow the policy). Not persisted. */
    private var stabilizationOverride: Boolean? = null

    private fun wantedStabilization(): Boolean {
        val d = state.value.policy(profile).defaults
        if (!d.previewStabilization) return false
        return stabilizationOverride ?: d.previewStabilizationOnByDefault
    }
    private var selfTestOwnsCamera = false
    private var awaitingPermission = false
    private var checksPending = false
    private var oneTimeChecksDone = false
    private var firstFrameSeen = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val checksFallback = Runnable { flushChecks() }
    private lateinit var controller: ViewfinderController
    private lateinit var orientationListener: OrientationEventListener

    // B20 GPU look preview (main thread). The camera writes either straight into the TextureView (plain path) or
    // into the renderer's SurfaceTexture, which draws through the LUT into the TextureView (look path).
    private enum class Attach { NONE, ATTACHING, ATTACHED }

    private lateinit var lookRenderer: LookPreviewRenderer
    private lateinit var prefs: SharedPreferences
    private var lookSetting = true
    private var lookStrength = 1f
    private var lookPrepareDone = false
    private var lookReady = false
    private var lookRuntimeError: String? = null
    private var lookAttach = Attach.NONE
    private var attachToken = 0
    private var attachedTo: SurfaceTexture? = null
    private var cameraTexture: SurfaceTexture? = null
    private var openedWithLook = false
    private var lastDetach: CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)

    // B21 RAW colour pipeline (main thread). Prepared (native, assets, self-check) and the profile chosen on its own
    // worker; stills are rendered from RAW while it is enabled, else they come from the camera JPEG.
    private lateinit var colourPipeline: ColourPipeline
    private var colourSetting = true
    private var colourPrepared: ColourPipeline.Prepared? = null
    private var colourChoice: ProfileChoice? = null

    // B22 burst merge (main thread): the setting, its native self-check, the gyroscope for the planner, the stress run.
    private var burstSetting = true
    private var burstChecked = false
    private var gyroSensor: Sensor? = null
    private var gyroRegistered = false
    private var motion = 0.0
    private var burstStressAbort = false

    /** The look path is used when the user wants it, start-up checks passed and nothing failed at runtime. */
    private fun useLook(): Boolean = lookSetting && lookReady && lookRuntimeError == null

    private val lookPrepareTimeout = Runnable {
        if (!lookPrepareDone) {
            lookPrepareDone = true
            lookReady = false
            updateLook {
                it.copy(
                    available = false,
                    reason = "OpenGL ES preparation took over $LOOK_PREPARE_TIMEOUT_MS ms; plain preview this session"
                )
            }
            syncCamera()
        }
    }

    private val lookListener = object : LookPreviewRenderer.Listener {
        override fun onPrepared(caps: LookPreviewRenderer.Caps) {
            runOnUiThread {
                if (lookPrepareDone) return@runOnUiThread // timed out earlier: stays off for this session
                lookPrepareDone = true
                mainHandler.removeCallbacks(lookPrepareTimeout)
                lookReady = caps.ok
                updateLook {
                    it.copy(
                        available = caps.ok,
                        reason = caps.reason,
                        glVersion = caps.glVersion,
                        glRenderer = caps.renderer,
                        es3 = caps.es3,
                        externalEssl3 = caps.externalEssl3,
                        floatLinear = caps.floatLinear,
                        halfFloatColorBuffer = caps.halfFloatColorBuffer,
                        lutSize = caps.lutSize,
                        lutFormat = caps.lutFormat,
                        timerMethod = caps.timerMethod,
                        selfCheckMaxErrorSteps = caps.selfCheckMaxErrorSteps
                    )
                }
                syncCamera()
            }
        }

        override fun onStats(stats: LookPreviewRenderer.Stats) {
            updateLook {
                it.copy(
                    timing = stats.timing,
                    timerMethod = stats.timerMethod,
                    displayFrames = stats.displayFrames,
                    displayDropped = stats.displayDropped
                )
            }
        }

        override fun onRuntimeError(message: String) {
            runOnUiThread {
                lookFailed(message)
                syncCamera()
            }
        }
    }

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        awaitingPermission = false
        permissionGranted.value = granted
        if (granted) {
            syncCamera()
            scheduleChecks(immediate = false)
        } else {
            scheduleChecks(immediate = true)
        }
    }

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            texture = surface
            syncCamera()
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            texture = null
            syncCamera()
            // Release the texture only once the camera has stopped writing to it and the look renderer has let go.
            val wasAttached = attachedTo === surface
            val detached = CompletableFuture<Unit>()
            if (wasAttached) {
                forgetAttachment()
                lastDetach = detached
            }
            lastClose.whenComplete { _, _ ->
                val gl = if (wasAttached) lookRenderer.detach() else CompletableFuture.completedFuture(Unit)
                gl.whenComplete { _, _ ->
                    surface.release()
                    detached.complete(Unit)
                    if (wasAttached) runOnUiThread { syncCamera() }
                }
            }
            return false
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
            if (!firstFrameSeen) onFirstFrameDisplayed()
        }
    }

    private val cameraListener = object : ViewfinderController.Listener {
        override fun onFirstCapture(elapsedRealtimeMs: Long) {
            updateViewfinder { m -> if (m.firstCaptureMs == null) m.copy(firstCaptureMs = elapsedRealtimeMs - createdAtMs) else m }
        }

        override fun onPreviewFps(fps: Double, warm: Boolean) {
            updateViewfinder { m ->
                val min = if (warm) minOf(m.previewFpsMin ?: fps, fps) else m.previewFpsMin
                m.copy(previewFps = fps, previewFpsMin = min)
            }
        }

        override fun onFrameStats(intervals: Int, dropped: Int, worstIntervalMs: Double) {
            updateViewfinder { m -> m.copy(framesCounted = intervals, droppedFrames = dropped, worstFrameIntervalMs = worstIntervalMs) }
        }

        override fun onStreaming(setup: ViewfinderSetup, stabilization: Boolean) {
            runOnUiThread {
                streaming.value = true
                if (stressPending) {
                    stressPending = false
                    startStress()
                }
                if (burstStressPending) {
                    burstStressPending = false
                    startBurstStress()
                }
            }
            updateViewfinder { m ->
                m.copy(
                    previewSize = setup.previewSize,
                    aeFpsRange = setup.aeFpsRange,
                    previewStabilization = stabilization,
                    zoomApi = setup.zoomApi,
                    status = "streaming"
                )
            }
        }

        override fun onPreviewValues(values: ViewfinderController.ResultValues) {
            runOnUiThread {
                if (values.manual?.aeOff != true) lastAuto = values
                liveText.value = liveLine(values)
                checkResult(values)
            }
        }

        override fun onStillValues(values: ViewfinderController.ResultValues) {
            runOnUiThread { checkResult(values) }
        }

        override fun onQueueDepth(depth: Int) {
            updateBurst { it.queue(depth) }
        }

        override fun onPool(frames: Int, frameMb: Double) {
            updateBurst { it.copy(poolFrames = frames, frameMb = frameMb) }
        }

        override fun onRawUnavailable(reason: String) {
            runOnUiThread {
                rawOn.value = false
                status.value = "RAW unavailable: $reason"
                updateColour { it.copy(sessionRawUnavailable = reason) }
            }
        }

        override fun onError(message: String) {
            runOnUiThread {
                cameraOpen = false
                streaming.value = false
                status.value = message
                updateViewfinder { it.copy(status = message) }
                flushChecks()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        createdAtMs = SystemClock.elapsedRealtime()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        colourPipeline = ColourPipeline(this)
        controller = ViewfinderController(this, cameraListener, BuildConfig.VERSION_NAME, colourPipeline)
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        colourSetting = prefs.getBoolean(PREF_COLOUR_PIPELINE, true)
        burstSetting = prefs.getBoolean(PREF_BURST_MERGE, true)
        lookSetting = prefs.getBoolean(PREF_LOOK_PREVIEW, true)
        lookStrength = prefs.getFloat(PREF_LOOK_STRENGTH, 1f).coerceIn(0f, 1f)
        lookRenderer = LookPreviewRenderer(lookListener)
        lookRenderer.setStrength(lookStrength)
        // Start-up checks run in parallel with the profile load, so the camera can open on the right path at once.
        lookRenderer.prepare { loadLookLut() }
        mainHandler.postDelayed(lookPrepareTimeout, LOOK_PREPARE_TIMEOUT_MS)
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation != ORIENTATION_UNKNOWN) deviceDegrees = orientation
            }
        }
        savedInstanceState?.let {
            selectedStop.intValue = it.getInt(STATE_STOP, ZoomStops.DEFAULT_STOP)
            rawOn.value = it.getBoolean(STATE_RAW, false)
            restoreManual(it)
            if (it.getBoolean(STATE_CAPABILITY, false)) screen.value = Screen.CAPABILITY
        }

        val quirksRepo = QuirksRepository(this)
        val quirks = quirksRepo.load()
        val forced = if (BuildConfig.DEBUG) DebugOverrides.parse(intent.getStringExtra(EXTRA_FORCE_FAIL)) else emptySet()
        val noPrompt = BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_NO_PERMISSION_PROMPT, false)
        permissionGranted.value = SelfTest(this).hasPermission()
        awaitingPermission = savedInstanceState == null && !permissionGranted.value && !noPrompt
        state.value = state.value.copy(
            quirks = quirks,
            quirkDisables = quirks.disabledFor(QuirksRepository.deviceKey()),
            forced = forced,
            viewfinder = ViewfinderMetrics(
                launch = if (savedInstanceState == null) "cold" else "recreated",
                permissionPrompted = awaitingPermission,
                status = "starting"
            ),
            lookPreview = LookPreviewReport(userSetting = lookSetting, strength = lookStrength, lutName = LOOK_LUT_ASSET),
            colourPipeline = ColourPipelineReport(userSetting = colourSetting, lookName = ColourPipeline.LOOK_NAME),
            burst = BurstReport(userSetting = burstSetting)
        )
        colourWorker.execute {
            val prepared = colourPipeline.prepare()
            runOnUiThread {
                colourPrepared = prepared
                updateColour { it.copy(nativeVersion = prepared.nativeVersion, selfCheck = prepared.selfCheck) }
                refreshColourAvailability()
            }
            // B22: the native burst merge on a synthetic burst with known shifts (CPU only, ~0.1-0.3 s).
            val check = runCatching { RedlineNative.burstSelfCheck(Runtime.getRuntime().availableProcessors().coerceIn(1, 8)) }
            runOnUiThread {
                burstChecked = true
                check.onSuccess { c ->
                    updateBurst { it.copy(selfCheckAligned = c.alignedFraction, selfCheckNoiseRatio = c.noiseRatio, selfCheckMs = c.ms) }
                }.onFailure { e -> updateBurst { it.copy(selfCheckError = "${e.javaClass.simpleName}: ${e.message}") } }
                refreshBurstAvailability()
            }
        }

        setContent { Content() }

        // The cached profile first: it is all the viewfinder needs to open the camera.
        val app = applicationContext
        worker.execute {
            val (loaded, hit) = runCatching { DeviceProfileProbe(app).cachedOrBuild() }.getOrNull() ?: (null to false)
            val camera = FeaturePolicy.defaultCamera(loaded)
            val setup = camera?.let { runCatching { ViewfinderSetup.read(app, it) }.getOrNull() }
            runOnUiThread { onProfileReady(loaded, hit, setup) }
        }

        if (awaitingPermission) permissionRequest.launch(Manifest.permission.CAMERA)

        sensorManager = getSystemService(SENSOR_SERVICE) as? SensorManager
        levelSensor = sensorManager?.let { sm ->
            sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
                ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
                ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        }
        gyroSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    }

    private fun onProfileReady(loaded: DeviceProfile?, cacheHit: Boolean, setup: ViewfinderSetup?) {
        profileState.value = loaded
        setupState.value = setup
        state.value = state.value.copy(zoomPlan = setup?.zoomPlan)
        updateViewfinder {
            it.copy(
                profileCache = when {
                    loaded == null -> "unavailable"
                    cacheHit -> "hit"
                    else -> "miss"
                }
            )
        }
        if (setup == null) status.value = if (loaded == null) "Camera profile unavailable" else "No usable camera found"
        if (setup?.zoomPlan?.stop(selectedStop.intValue) == null) {
            selectedStop.intValue = setup?.zoomPlan?.stops?.firstOrNull()?.targetMm ?: ZoomStops.DEFAULT_STOP
        }
        update {
            it.copy(
                manual = it.manual.copy(
                    caps = setup?.manual,
                    gridOn = gridOn.value,
                    levelOn = levelOn.value,
                    histogramOn = histogramOn.value,
                    histogramSource = HISTOGRAM_SOURCE,
                    levelSensor = levelSensorName()
                )
            )
        }
        applyManual()
        if (setup != null) {
            colourWorker.execute {
                val choice = colourPipeline.profileFor(setup.cameraId)
                runOnUiThread {
                    colourChoice = choice
                    refreshColourAvailability()
                }
            }
        } else {
            colourChoice = ProfileChoice(null, null, null, "no usable camera")
            refreshColourAvailability()
        }
        syncCamera()
        if (!awaitingPermission) scheduleChecks(immediate = !permissionGranted.value || setup == null)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        permissionGranted.value = SelfTest(this).hasPermission()
        syncCamera()
        syncLevelSensor()
        syncGyro()
        mainHandler.removeCallbacks(histogramTick)
        mainHandler.postDelayed(histogramTick, HISTOGRAM_PERIOD_MS)
    }

    override fun onPause() {
        resumed = false
        orientationListener.disable()
        mainHandler.removeCallbacks(histogramTick)
        syncLevelSensor()
        syncGyro()
        syncCamera()
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(checksFallback)
        mainHandler.removeCallbacks(lookPrepareTimeout)
        // The renderer owns the camera's SurfaceTexture on the look path: release it once the camera has closed.
        controller.close().whenComplete { _, _ -> lookRenderer.release() }
        controller.release()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_STOP, selectedStop.intValue)
        outState.putBoolean(STATE_CAPABILITY, screen.value == Screen.CAPABILITY)
        outState.putBoolean(STATE_RAW, rawOn.value)
        val m = manualSettings.value
        outState.putString(STATE_MODE, m.mode.name)
        outState.putInt(STATE_ISO, m.iso)
        outState.putLong(STATE_EXPOSURE, m.exposureNs)
        outState.putFloat(STATE_FOCUS, m.focusDiopters ?: Float.NaN)
        outState.putInt(STATE_EV, m.evIndex)
        outState.putBoolean(STATE_AE_LOCK, m.aeLock)
        outState.putBoolean(STATE_AWB_LOCK, m.awbLock)
        outState.putString(STATE_AWB, m.awb)
        outState.putBoolean(STATE_GRID, gridOn.value)
        outState.putBoolean(STATE_LEVEL, levelOn.value)
        outState.putBoolean(STATE_HISTOGRAM, histogramOn.value)
    }

    private fun restoreManual(b: Bundle) {
        val d = ManualSettings()
        val focus = b.getFloat(STATE_FOCUS, Float.NaN)
        manualSettings.value = ManualSettings(
            mode = runCatching { ShootMode.valueOf(b.getString(STATE_MODE) ?: "") }.getOrDefault(d.mode),
            iso = b.getInt(STATE_ISO, d.iso),
            exposureNs = b.getLong(STATE_EXPOSURE, d.exposureNs),
            focusDiopters = if (focus.isNaN()) null else focus,
            evIndex = b.getInt(STATE_EV, d.evIndex),
            aeLock = b.getBoolean(STATE_AE_LOCK, d.aeLock),
            awbLock = b.getBoolean(STATE_AWB_LOCK, d.awbLock),
            awb = b.getString(STATE_AWB) ?: d.awb
        )
        gridOn.value = b.getBoolean(STATE_GRID, gridOn.value)
        levelOn.value = b.getBoolean(STATE_LEVEL, levelOn.value)
        histogramOn.value = b.getBoolean(STATE_HISTOGRAM, histogramOn.value)
    }

    @Composable
    private fun Content() {
        BackHandler(enabled = screen.value == Screen.CAPABILITY) { showViewfinder() }
        if (screen.value == Screen.VIEWFINDER) {
            val setup = setupState.value
            ViewfinderScreen(
                plan = setup?.zoomPlan,
                selectedStop = selectedStop.intValue,
                onSelectStop = ::selectStop,
                previewSize = setup?.previewSize,
                sensorOrientation = setup?.sensorOrientation ?: DEFAULT_SENSOR_ORIENTATION,
                textureListener = textureListener,
                metrics = state.value.viewfinder,
                status = if (!permissionGranted.value) "Camera permission needed" else status.value,
                needsPermission = !permissionGranted.value,
                onRequestPermission = { permissionRequest.launch(Manifest.permission.CAMERA) },
                onOpenCapability = ::showCapability,
                onShutter = { shoot(keep = true) {} },
                // B22: the shutter stays live during a capture (the press waits for the camera, one deep).
                shutterEnabled = streaming.value && !state.value.capture.stressRunning && !state.value.burst.stressRunning,
                rawAvailable = rawAvailable(),
                rawOn = rawOn.value,
                onToggleRaw = ::toggleRaw,
                captureText = listOfNotNull(burstOverlay(), state.value.capture.overlayText()).joinToString(" · ").ifEmpty { null },
                manualUi = ManualUi(
                    caps = setup?.manual,
                    settings = manualSettings.value,
                    applied = manualApplied.value,
                    live = liveText.value,
                    histogram = histogram.value,
                    tiltDeg = tilt.value,
                    gridOn = gridOn.value,
                    levelOn = levelOn.value,
                    histogramOn = histogramOn.value
                ),
                manualActions = manualActions,
                onPreviewView = { previewView = it },
                lookBadge = if (state.value.lookPreview.active) LOOK_BADGE else null
            )
        } else {
            CapabilityScreen(
                cameras = cameraFacts.value,
                calibrations = calibrations.value,
                onShareCalibration = { cal -> share("redline_camera2_${cal.cameraId}.json", cal.toJson(), "Share calibration") },
                profile = profile,
                onShareReport = {
                    val json = profile?.let { CapabilityReport.toJson(it, state.value) }
                        ?: runCatching { DeviceProfileProbe(this).cachedJson() }.getOrDefault("{}")
                    share("redline_capability_report.json", json, "Share capability report")
                },
                state = state.value,
                onRunSelfTest = { runChecks(force = true) },
                onRepeatSelfTest = { repeatSelfTest(REPEAT_RUNS) },
                onToggleStabilization = if (state.value.policy(profile).defaults.previewStabilization) {
                    {
                        stabilizationOverride = !wantedStabilization()
                        updateViewfinder { it.copy(stabilizationOverride = stabilizationOverride) }
                    }
                } else {
                    null
                },
                onRunStress = {
                    stressPending = true
                    showViewfinder()
                },
                onToggleLook = if (state.value.lookPreview.available == true && lookRuntimeError == null) ::toggleLook else null,
                onToggleColourPipeline = if (state.value.colourPipeline.available == true) ::toggleColourPipeline else null,
                onToggleBurst = if (state.value.burst.available == true) ::toggleBurst else null,
                onRunBurstStress = if (state.value.burst.enabled && colourEnabled()) {
                    {
                        burstStressPending = true
                        showViewfinder()
                    }
                } else {
                    null
                },
                onCycleLookStrength = ::cycleLookStrength,
                onBack = ::showViewfinder
            )
        }
    }

    private val manualActions = ManualActions(
        onMode = ::selectMode,
        onIso = { dir -> stepManual { caps, s -> s.copy(iso = ManualMath.stepIso(caps.isoLadder, s.iso, dir)) } },
        onShutter = { dir -> stepManual { caps, s -> s.copy(exposureNs = ManualMath.stepShutter(caps.shutterLadder, s.exposureNs, dir)) } },
        onFocus = { dir -> stepManual { caps, s -> s.copy(focusDiopters = ManualMath.stepFocus(caps.focusLadder, s.focusDiopters, dir)) } },
        onEv = { dir -> stepManual { caps, s -> s.copy(evIndex = ManualMath.stepEv(caps.evLadder, s.evIndex, dir)) } },
        onToggleAeLock = { stepManual { _, s -> s.copy(aeLock = !s.aeLock) } },
        onToggleAwbLock = { stepManual { _, s -> s.copy(awbLock = !s.awbLock) } },
        onCycleAwb = {
            stepManual { caps, s ->
                val presets = caps.awbPresets
                val i = presets.indexOf(s.awb)
                s.copy(awb = if (presets.isEmpty()) "AUTO" else presets[(i + 1).mod(presets.size)])
            }
        },
        onToggleGrid = {
            gridOn.value = !gridOn.value
            update { it.copy(manual = it.manual.copy(gridOn = gridOn.value)) }
        },
        onToggleLevel = {
            levelOn.value = !levelOn.value
            syncLevelSensor()
            update { it.copy(manual = it.manual.copy(levelOn = levelOn.value)) }
        },
        onToggleHistogram = {
            histogramOn.value = !histogramOn.value
            if (!histogramOn.value) histogram.value = null
            update { it.copy(manual = it.manual.copy(histogramOn = histogramOn.value)) }
        }
    )

    private fun stepManual(change: (ManualCaps, ManualSettings) -> ManualSettings) {
        val caps = setupState.value?.manual ?: return
        applyManual(change(caps, manualSettings.value))
    }

    /** Entering M starts from what auto exposure was doing, snapped to the ladders. */
    private fun selectMode(mode: ShootMode) {
        val caps = setupState.value?.manual ?: return
        val s = manualSettings.value
        if (mode == ShootMode.M && s.mode != ShootMode.M) {
            val auto = lastAuto
            applyManual(
                s.copy(
                    mode = mode,
                    iso = ManualMath.snapIso(caps.isoLadder, auto?.iso ?: s.iso),
                    exposureNs = ManualMath.snapShutter(caps.shutterLadder, auto?.exposureNs ?: s.exposureNs),
                    focusDiopters = null
                )
            )
        } else {
            applyManual(s.copy(mode = mode))
        }
    }

    /**
     * Resolves [next] against the reported ranges (ManualModel clamps and records any clamp), keeps the stored
     * settings on the clamped values, and hands the result to the camera for both the preview and the stills.
     */
    private fun applyManual(next: ManualSettings = manualSettings.value) {
        val caps = setupState.value?.manual
        if (caps == null) {
            manualSettings.value = next
            manualApplied.value = null
            controller.setManual(null)
            return
        }
        val a = ManualModel.resolve(caps, next)
        val normalized = next.copy(
            mode = a.mode,
            iso = a.stillIso ?: next.iso,
            exposureNs = a.stillExposureNs ?: next.exposureNs,
            focusDiopters = if (a.mode == ShootMode.M) a.focusDiopters else next.focusDiopters,
            evIndex = if (a.mode == ShootMode.AV) a.evIndex else next.evIndex,
            awb = if (a.mode != ShootMode.AUTO) a.awbMode else next.awb
        )
        manualSettings.value = normalized
        manualApplied.value = a
        controller.setManual(a)
        update { it.copy(manual = it.manual.copy(caps = caps).record(a, normalized)) }
    }

    /** B14 gate evidence: values Redline set manually must come back inside the reported ranges. */
    private fun checkResult(v: ViewfinderController.ResultValues) {
        val m = v.manual ?: return
        val caps = setupState.value?.manual ?: return
        if (!m.aeOff && m.focusDiopters == null) return
        val violations = ManualModel.violations(
            caps,
            if (m.aeOff) v.iso else null,
            if (m.aeOff) v.exposureNs else null,
            if (m.focusDiopters != null) v.focusDiopters else null
        )
        update { it.copy(manual = it.manual.recordResult(violations)) }
    }

    private fun liveLine(v: ViewfinderController.ResultValues): String {
        val caps = setupState.value?.manual
        val parts = mutableListOf<String>()
        v.iso?.let { parts += "ISO $it" }
        v.exposureNs?.let { parts += ManualMath.shutterLabel(it) }
        val f = v.focusDiopters
        if (f != null && caps?.manualFocus == true) parts += ManualMath.focusLabel(f, caps.minFocusDiopters, caps.metricFocus)
        val mf = if (v.manual?.focusDiopters != null) "MF" else "AF"
        return "Live: ${parts.joinToString(" · ")} · $mf"
    }

    /** Histogram from a downscaled TextureView sample, about 4 times a second, only while it is visible. */
    private val histogramTick = object : Runnable {
        override fun run() {
            sampleHistogram()
            if (resumed) mainHandler.postDelayed(this, HISTOGRAM_PERIOD_MS)
        }
    }

    private fun sampleHistogram() {
        if (!histogramOn.value || !streaming.value || screen.value != Screen.VIEWFINDER) return
        val v = previewView ?: return
        if (!v.isAttachedToWindow || !v.isAvailable) return
        val bmp = histBitmap ?: Bitmap.createBitmap(HIST_W, HIST_H, Bitmap.Config.ARGB_8888).also { histBitmap = it }
        runCatching { v.getBitmap(bmp) }.getOrNull() ?: return
        bmp.getPixels(histPixels, 0, HIST_W, 0, 0, HIST_W, HIST_H)
        histogram.value = ManualMath.histogramFromArgb(histPixels, ManualMath.DEFAULT_BINS)
    }

    private val levelListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val up = if (e.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                for (i in 0..2) gravity[i] += LEVEL_SMOOTHING * (e.values[i] - gravity[i])
                gravity
            } else {
                if (runCatching { SensorManager.getRotationMatrixFromVector(rotation, e.values) }.isFailure) return
                floatArrayOf(rotation[6], rotation[7], rotation[8])
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastTiltMs < LEVEL_PERIOD_MS) return
            lastTiltMs = now
            val t = ManualMath.horizonTiltDegrees(up[0], up[1], up[2], displayRotationDegrees())
            val old = tilt.value
            if (t == null || old == null || abs(t - old) >= LEVEL_MIN_CHANGE_DEG) tilt.value = t
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** B22: hand motion for the burst planner, a smoothed gyroscope magnitude (rad/s). */
    private val gyroListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val m = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble()
            motion += GYRO_SMOOTHING * (m - motion)
            controller.setMotion(motion)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun syncGyro() {
        val sm = sensorManager ?: return
        val sensor = gyroSensor
        if (sensor == null) {
            controller.setMotion(null)
            return
        }
        if (resumed && !gyroRegistered) {
            gyroRegistered = sm.registerListener(gyroListener, sensor, SensorManager.SENSOR_DELAY_GAME)
        } else if (!resumed && gyroRegistered) {
            sm.unregisterListener(gyroListener)
            gyroRegistered = false
        }
    }

    private fun syncLevelSensor() {
        val sm = sensorManager ?: return
        val sensor = levelSensor ?: return
        val want = resumed && levelOn.value
        if (want && !levelRegistered) {
            levelRegistered = sm.registerListener(levelListener, sensor, SensorManager.SENSOR_DELAY_UI)
        } else if (!want && levelRegistered) {
            sm.unregisterListener(levelListener)
            levelRegistered = false
            tilt.value = null
        }
    }

    private fun levelSensorName(): String = when (levelSensor?.type) {
        Sensor.TYPE_GAME_ROTATION_VECTOR -> "game_rotation_vector"
        Sensor.TYPE_ROTATION_VECTOR -> "rotation_vector"
        Sensor.TYPE_ACCELEROMETER -> "accelerometer"
        else -> "none"
    }

    private fun displayRotationDegrees(): Int {
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
        return when (r) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    private fun showCapability() {
        screen.value = Screen.CAPABILITY
        syncCamera()
        flushChecks()
    }

    private fun showViewfinder() {
        screen.value = Screen.VIEWFINDER
        syncCamera()
    }

    /** RAW is offered when the camera has a RAW_SENSOR stream and the policy kept the RAW path. */
    private fun rawAvailable(): Boolean =
        setupState.value?.rawSize != null && state.value.policy(profile).defaults.stillPath == StillPath.RAW_PLUS_JPEG

    private fun toggleRaw() {
        rawOn.value = !rawOn.value && rawAvailable()
        controller.setRaw(rawOn.value)
    }

    /** One still with the selected stop's framing. [onCaptured] and [done] run on the main thread. */
    private fun shoot(keep: Boolean, stress: Boolean = false, onCaptured: () -> Unit = {}, done: (ShotResult) -> Unit) {
        val stop = setupState.value?.zoomPlan?.stop(selectedStop.intValue)
        val request = ViewfinderController.StillRequest(
            stopLabel = stop?.label ?: "${selectedStop.intValue}",
            stopTargetMm = stop?.targetMm ?: selectedStop.intValue,
            trueEquivalentMm = stop?.trueEquivalentMm,
            stopLongPx = stop?.outputWidthPx ?: 0,
            deviceDegrees = deviceDegrees,
            keep = keep,
            stress = stress
        )
        shotInFlight.value = true
        controller.capture(request, onCaptured = {
            runOnUiThread {
                shotInFlight.value = false
                onCaptured()
            }
        }) { result ->
            runOnUiThread {
                val b13Stress = !keep && !stress
                val colour =
                    result.colour?.let { state.value.colourPipeline.record(it, result.shutterToSavedMs) } ?: state.value.colourPipeline
                var burst = state.value.burst.press(result.shutterWaitMs, result.shutterBlocked, stress)
                result.burst?.let { burst = burst.record(it, result.shutterToSavedMs, result.ok) }
                if (stress && result.burst == null) {
                    burst = burst.copy(stressDone = burst.stressDone + 1, stressFailures = burst.stressFailures + if (result.ok) 0 else 1)
                }
                state.value = state.value.copy(capture = state.value.capture.add(result, b13Stress), colourPipeline = colour, burst = burst)
                done(result)
            }
        }
    }

    /**
     * B22 burst stress: [BurstReport.STRESS_SHOTS] shots fired as fast as the camera frees up (each press as soon as
     * the previous capture is out of the camera, not when it is saved), files deleted. Measures shutter blocking and
     * the queue under load; the save gate ignores these shots.
     */
    private fun startBurstStress() {
        if (state.value.burst.stressRunning) return
        burstStressAbort = false
        val runs = BurstReport.STRESS_SHOTS
        updateBurstNow {
            it.copy(stressRequested = runs, stressDone = 0, stressFailures = 0, stressBlocked = 0, stressMaxQueue = 0, stressRunning = true)
        }
        var fired = 0
        fun finishIfDone() {
            val b = state.value.burst
            if (b.stressRunning && (b.stressDone >= runs || (burstStressAbort && b.stressDone >= fired))) {
                updateBurstNow { it.copy(stressRunning = false) }
            }
        }
        fun next() {
            if (burstStressAbort || !cameraOpen || fired >= runs) {
                finishIfDone()
                return
            }
            fired++
            shoot(keep = false, stress = true, onCaptured = { next() }) { finishIfDone() }
        }
        next()
    }

    private fun burstOverlay(): String? = state.value.burst.overlay()

    /**
     * B13 gate: 500 consecutive captures through the full save path (files written via MediaStore, then
     * deleted), one at a time. Leaving the viewfinder aborts the run, which then does not count.
     */
    private fun startStress() {
        if (state.value.capture.stressRunning) return
        stressAbort = false
        val runs = CaptureStats.STRESS_RUNS
        state.value = state.value.copy(
            capture = state.value.capture.copy(
                stressRequested = runs,
                stressDone = 0,
                stressFailures = 0,
                stressRaw = rawOn.value,
                stressRunning = true
            )
        )
        fun next() {
            val c = state.value.capture
            if (stressAbort || !cameraOpen) {
                val aborted = c.copy(stressRunning = false, lastError = "stress run aborted at ${c.stressDone}/$runs")
                state.value = state.value.copy(capture = aborted)
                return
            }
            if (c.stressDone >= runs) {
                state.value = state.value.copy(capture = c.copy(stressRunning = false))
                return
            }
            shoot(keep = false) { next() }
        }
        next()
    }

    private fun selectStop(mm: Int) {
        selectedStop.intValue = mm
        val stop = setupState.value?.zoomPlan?.stop(mm) ?: return
        if (cameraOpen) controller.setZoom(stop.zoomRatio)
        updateViewfinder { it.copy(zoomStopMm = mm, zoomRatio = stop.zoomRatio) }
    }

    /**
     * Opens or closes the preview so it matches what should be happening: resumed, on the viewfinder screen,
     * permission granted, a surface and a setup, and no self-test holding the camera. B20: the camera writes into
     * the look renderer's SurfaceTexture when the look preview is in use, else straight into the TextureView; a
     * change of path closes the camera first, then (re)attaches or detaches the renderer, then reopens. The
     * returned future completes when any close it started has finished.
     */
    private fun syncCamera(): CompletableFuture<Unit> {
        val setup = setupState.value
        val display = texture
        val look = useLook()
        val base = resumed &&
            screen.value == Screen.VIEWFINDER &&
            permissionGranted.value &&
            display != null &&
            setup != null &&
            !selfTestOwnsCamera
        if (cameraOpen && (!base || openedWithLook != look)) {
            cameraOpen = false
            streaming.value = false
            stressAbort = true
            burstStressAbort = true
            updateLook { it.copy(active = false) }
            lastClose = controller.close()
            // Once closed, settle the renderer and reopen on the right path if the camera is still wanted.
            lastClose.whenComplete { _, _ -> runOnUiThread { syncCamera() } }
            return lastClose
        }
        if (!cameraOpen) syncRenderer(display, setup, look)
        val target = when {
            look -> if (lookAttach == Attach.ATTACHED && attachedTo === display) cameraTexture else null
            lookAttach == Attach.NONE && lastDetach.isDone -> display
            else -> null
        }
        val lookPending = lookSetting && !lookPrepareDone
        if (base && !lookPending && !cameraOpen && target != null) {
            cameraOpen = true
            openedWithLook = look
            appliedStabilization = wantedStabilization()
            // Each viewfinder session measures fresh, so the report reflects the current settings only.
            updateViewfinder {
                it.copy(
                    previewFpsMin = null,
                    framesCounted = null,
                    droppedFrames = null,
                    worstFrameIntervalMs = null,
                    stabilizationOverride = stabilizationOverride
                )
            }
            updateLook { it.copy(active = look) }
            val stop = setup.zoomPlan.stop(selectedStop.intValue)
            val ratio = stop?.zoomRatio ?: 1f
            if (status.value != null && !selfTestOwnsCamera) status.value = null
            controller.open(setup, target, ratio, appliedStabilization, rawOn.value && rawAvailable(), colourEnabled())
            controller.setBurst(burstEnabled())
            updateViewfinder { it.copy(zoomStopMm = stop?.targetMm, zoomRatio = ratio, status = "opening") }
        }
        return CompletableFuture.completedFuture(Unit)
    }

    /** Camera closed: attach the renderer to the TextureView for the look path, or detach it for the plain path. */
    private fun syncRenderer(display: SurfaceTexture?, setup: ViewfinderSetup?, look: Boolean) {
        if (!look) {
            if (lookAttach != Attach.NONE) {
                forgetAttachment()
                val f = lookRenderer.detach()
                lastDetach = f
                f.whenComplete { _, _ -> runOnUiThread { syncCamera() } }
            }
            return
        }
        if (lookAttach != Attach.NONE || display == null || setup == null || !permissionGranted.value || !lastDetach.isDone) return
        lookAttach = Attach.ATTACHING
        attachedTo = display
        val token = ++attachToken
        lookRenderer.attach(display, setup.previewSize, setup.aeFpsRange?.second ?: LOOK_DEFAULT_FPS) { cam, error ->
            runOnUiThread {
                // Superseded (surface destroyed or look turned off meanwhile): its detach is already queued behind it.
                if (token != attachToken) return@runOnUiThread
                if (cam == null) {
                    forgetAttachment()
                    lookFailed("attach: $error")
                } else {
                    lookAttach = Attach.ATTACHED
                    cameraTexture = cam
                }
                syncCamera()
            }
        }
    }

    private fun forgetAttachment() {
        lookAttach = Attach.NONE
        attachedTo = null
        cameraTexture = null
        attachToken++
    }

    /** The GL path failed after start-up: the plain TextureView preview takes over for the rest of the session. */
    private fun lookFailed(message: String) {
        if (lookRuntimeError != null) return
        lookRuntimeError = message
        updateLook { it.copy(runtimeError = message, active = false) }
    }

    /** GL thread: loads the native module and parses the bundled LUT with it. */
    private fun loadLookLut(): CubeLut {
        val native = RedlineNative.load()
        updateLook { it.copy(nativeVersion = native.version, nativeError = native.error) }
        check(native.loaded) { "native module not loaded (${native.error})" }
        val bytes = applicationContext.assets.open(LOOK_LUT_ASSET).use { it.readBytes() }
        return RedlineNative.parseCube(bytes)
    }

    /** B21: available once the self-check passed and a profile was found for the camera, which must offer RAW. */
    private fun refreshColourAvailability() {
        val prepared = colourPrepared
        val choice = colourChoice
        val (available, reason) = when {
            prepared != null && !prepared.ok -> false to prepared.reason
            choice != null && !choice.ok -> false to choice.reason
            prepared == null || choice == null || setupState.value == null -> null to null
            !rawAvailable() -> false to "this camera offers no RAW_SENSOR stream (or the RAW path was demoted); camera JPEG is used"
            else -> true to null
        }
        updateColour { it.copy(available = available, reason = reason, profileSource = choice?.source, profileId = choice?.profileId) }
        syncColourPipeline()
        runOnUiThread { refreshBurstAvailability() }
    }

    /**
     * B22: burst merge is available when the native self-check passed, the B21 pipeline is available, and the camera
     * has RAW, BURST_CAPTURE and manual exposure (the burst frames are AE-off). On by default then; off with the reason
     * otherwise, and the still takes the B21 single-frame path.
     */
    private fun refreshBurstAvailability() {
        val b = state.value.burst
        val cam = FeaturePolicy.defaultCamera(profile)
        val caps = setupState.value?.manual
        val selfOk = b.selfCheckError == null &&
            (b.selfCheckAligned ?: 0.0) >= BurstReport.SELF_CHECK_MIN_ALIGNED &&
            (b.selfCheckNoiseRatio ?: 0.0) >= BurstReport.SELF_CHECK_MIN_NOISE_RATIO
        val colour = state.value.colourPipeline
        val (available, reason) = when {
            burstChecked && !selfOk -> false to "native self-check failed: ${b.selfCheckError ?: selfCheckSummary(b)}"
            colour.available == false -> false to "needs the RAW colour pipeline (${colour.reason ?: "unavailable"})"
            !burstChecked || colour.available == null || setupState.value == null -> null to null
            cam == null || !cam.has("BURST_CAPTURE") -> false to "camera does not report BURST_CAPTURE"
            caps == null || !caps.manualExposure -> false to "camera has no manual exposure (burst frames are AE-off)"
            !rawAvailable() -> false to "no RAW_SENSOR stream"
            else -> true to null
        }
        updateBurst { it.copy(available = available, reason = reason) }
        syncBurst()
    }

    private fun selfCheckSummary(b: BurstReport): String = "aligned ${b.selfCheckAligned}, noise ratio ${b.selfCheckNoiseRatio}"

    private fun burstEnabled(): Boolean = state.value.burst.enabled && colourEnabled()

    private fun syncBurst() {
        if (cameraOpen) controller.setBurst(burstEnabled())
    }

    private fun toggleBurst() {
        burstSetting = !burstSetting
        prefs.edit { putBoolean(PREF_BURST_MERGE, burstSetting) }
        state.value = state.value.copy(burst = state.value.burst.copy(userSetting = burstSetting))
        syncBurst()
    }

    private fun updateBurst(change: (BurstReport) -> BurstReport) {
        update { s -> s.copy(burst = change(s.burst)) }
    }

    /** Main thread only: applies at once (the stress run reads its own counters back immediately). */
    private fun updateBurstNow(change: (BurstReport) -> BurstReport) {
        state.value = state.value.copy(burst = change(state.value.burst))
    }

    /** Stills are rendered from RAW: setting on, prepared, profile found, RAW offered and no session failure. */
    private fun colourEnabled(): Boolean = state.value.colourPipeline.enabled && rawAvailable()

    private fun syncColourPipeline() {
        if (cameraOpen) controller.setPipeline(colourEnabled())
    }

    private fun toggleColourPipeline() {
        colourSetting = !colourSetting
        prefs.edit { putBoolean(PREF_COLOUR_PIPELINE, colourSetting) }
        state.value = state.value.copy(colourPipeline = state.value.colourPipeline.copy(userSetting = colourSetting))
        syncColourPipeline()
    }

    private fun updateColour(change: (ColourPipelineReport) -> ColourPipelineReport) {
        update { s -> s.copy(colourPipeline = change(s.colourPipeline)) }
    }

    private fun toggleLook() {
        lookSetting = !lookSetting
        prefs.edit { putBoolean(PREF_LOOK_PREVIEW, lookSetting) }
        updateLook { it.copy(userSetting = lookSetting) }
        syncCamera()
    }

    private fun cycleLookStrength() {
        val i = LOOK_STRENGTHS.indexOfFirst { abs(it - lookStrength) < 0.01f }
        lookStrength = LOOK_STRENGTHS[(i + 1).mod(LOOK_STRENGTHS.size)]
        prefs.edit { putFloat(PREF_LOOK_STRENGTH, lookStrength) }
        lookRenderer.setStrength(lookStrength)
        updateLook { it.copy(strength = lookStrength) }
    }

    private fun onFirstFrameDisplayed() {
        firstFrameSeen = true
        val now = SystemClock.elapsedRealtime()
        val sinceProcess = runCatching { now - Process.getStartElapsedRealtime() }.getOrNull()
        updateViewfinder { it.copy(firstFrameMs = now - createdAtMs, processToFirstFrameMs = sinceProcess) }
        flushChecks()
    }

    /** Marks the deferred checks as due: run at the first preview frame, or after a fallback delay, or now. */
    private fun scheduleChecks(immediate: Boolean) {
        checksPending = true
        mainHandler.removeCallbacks(checksFallback)
        if (immediate || firstFrameSeen) flushChecks() else mainHandler.postDelayed(checksFallback, CHECKS_FALLBACK_MS)
    }

    private fun flushChecks() {
        if (!checksPending || profile == null) return
        checksPending = false
        mainHandler.removeCallbacks(checksFallback)
        if (!oneTimeChecksDone) {
            oneTimeChecksDone = true
            loadCapabilityDetails()
            val app = applicationContext
            worker.execute { QuirksRepository(app).refresh() }
        }
        runChecks(force = false)
    }

    /** Camera facts and factory calibration for the capability screen; static metadata only, off the main thread. */
    private fun loadCapabilityDetails() {
        val app = applicationContext
        worker.execute {
            val cameras = runCatching { CapabilityProbe(app).probeAll() }.getOrDefault(emptyList())
            val manager = app.getSystemService(CAMERA_SERVICE) as CameraManager
            val cals = cameras.mapNotNull { c ->
                val candidates = (listOf(c.id) + c.physicalIds).mapNotNull { id ->
                    runCatching { FactoryCalibration.read(c.id, manager.getCameraCharacteristics(id), id) }.getOrNull()
                }
                FactoryCalibration.pick(candidates)?.let { c.id to it }
            }.toMap()
            runOnUiThread {
                cameraFacts.value = cameras
                calibrations.value = cals
            }
        }
    }

    /** Combination checks, then the cached-or-fresh self-test, all on the worker thread. */
    private fun runChecks(force: Boolean) {
        val app = applicationContext
        val camera = FeaturePolicy.defaultCamera(profile)
        update { it.copy(selfTestRunning = true) }
        worker.execute {
            val repo = QuirksRepository(app)
            val quirks = repo.load()
            val disables = quirks.disabledFor(QuirksRepository.deviceKey())
            val combos = camera?.let { runCatching { CombinationChecker(app).check(it, disables) }.getOrNull() }.orEmpty()
            update { it.copy(quirks = quirks, quirkDisables = disables, combos = combos) }
            val selfTest = SelfTest(app)
            val report = withCameraReleased(camera != null && selfTest.isDue(force) && selfTest.hasPermission()) {
                selfTest.cachedOrRun(camera, force)
            }
            update { it.copy(selfTest = report, selfTestRunning = false) }
        }
    }

    /** Run the self-test [runs] times in a row and count clean passes (the B11 100/100 gate). */
    private fun repeatSelfTest(runs: Int) {
        val app = applicationContext
        val camera = FeaturePolicy.defaultCamera(profile)
        update { it.copy(selfTestRunning = true, repeatStatus = "0/$runs") }
        worker.execute {
            withCameraReleased(true) {
                var passed = 0
                for (i in 1..runs) {
                    val report = SelfTest(app).cachedOrRun(camera, force = true)
                    if (report.passed) passed++
                    update { it.copy(selfTest = report, repeatStatus = "$passed/$i runs passed (of $runs)") }
                }
            }
            update { it.copy(selfTestRunning = false) }
        }
    }

    /**
     * Worker thread only. When [needed], closes the viewfinder's camera (and waits for the close) before
     * [block] and reopens it afterwards, so the self-test never fights the preview for the camera device.
     */
    private fun <T> withCameraReleased(needed: Boolean, block: () -> T): T {
        if (!needed) return block()
        val released = CompletableFuture<Unit>()
        runOnUiThread {
            selfTestOwnsCamera = true
            status.value = "Checking camera (self-test)..."
            syncCamera().whenComplete { _, _ -> released.complete(Unit) }
        }
        runCatching { released.get(RELEASE_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        try {
            return block()
        } finally {
            runOnUiThread {
                selfTestOwnsCamera = false
                if (status.value == "Checking camera (self-test)...") status.value = null
                syncCamera()
            }
        }
    }

    private fun update(change: (CapabilityState) -> CapabilityState) {
        runOnUiThread {
            state.value = change(state.value)
            applyPolicyToCamera()
        }
    }

    private fun updateViewfinder(change: (ViewfinderMetrics) -> ViewfinderMetrics) {
        update { s -> s.copy(viewfinder = change(s.viewfinder ?: ViewfinderMetrics())) }
    }

    private fun updateLook(change: (LookPreviewReport) -> LookPreviewReport) {
        update { s -> s.copy(lookPreview = change(s.lookPreview)) }
    }

    /** Combination results can demote preview stabilization after the preview opened; follow the policy. */
    private fun applyPolicyToCamera() {
        if (!cameraOpen) return
        controller.setPipeline(colourEnabled())
        controller.setBurst(burstEnabled())
        val stab = wantedStabilization()
        if (stab != appliedStabilization) {
            appliedStabilization = stab
            controller.setStabilization(stab)
        }
    }

    private fun share(name: String, json: String, title: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, name)
            putExtra(Intent.EXTRA_TEXT, json)
        }
        startActivity(Intent.createChooser(send, title))
    }

    companion object {
        /** Debug builds: comma-separated self-test keys or combo:<key> to force as failed. */
        const val EXTRA_FORCE_FAIL = "redline.force_fail"

        /** Debug builds: skip the CAMERA permission prompt (instrumented tests). */
        const val EXTRA_NO_PERMISSION_PROMPT = "redline.no_permission_prompt"

        private const val REPEAT_RUNS = 100
        private const val STATE_STOP = "redline.zoom_stop"
        private const val STATE_CAPABILITY = "redline.capability_screen"
        private const val STATE_RAW = "redline.raw"
        private const val STATE_MODE = "redline.mode"
        private const val STATE_ISO = "redline.iso"
        private const val STATE_EXPOSURE = "redline.exposure_ns"
        private const val STATE_FOCUS = "redline.focus_diopters"
        private const val STATE_EV = "redline.ev_index"
        private const val STATE_AE_LOCK = "redline.ae_lock"
        private const val STATE_AWB_LOCK = "redline.awb_lock"
        private const val STATE_AWB = "redline.awb"
        private const val STATE_GRID = "redline.grid"
        private const val STATE_LEVEL = "redline.level"
        private const val STATE_HISTOGRAM = "redline.histogram"

        /** Histogram sample: 4 Hz from a 128x96 TextureView copy; the camera session and its 30 fps are untouched. */
        private const val HISTOGRAM_PERIOD_MS = 250L
        private const val HIST_W = 128
        private const val HIST_H = 96
        private const val HISTOGRAM_SOURCE =
            "TextureView sample, 4 Hz, 128x96 (no YUV stream: preview + YUV + JPEG (+RAW) is not a verified combination)"
        private const val LEVEL_PERIOD_MS = 66L
        private const val LEVEL_SMOOTHING = 0.2f
        private const val LEVEL_MIN_CHANGE_DEG = 0.2f
        private const val DEFAULT_SENSOR_ORIENTATION = 90

        /** Deferred checks start at the first preview frame, or after this long if no frame arrives. */
        private const val CHECKS_FALLBACK_MS = 3_000L
        private const val RELEASE_TIMEOUT_MS = 4_000L

        // B20 look preview.
        private const val PREFS_NAME = "redline_settings"
        private const val PREF_LOOK_PREVIEW = "look_preview"
        private const val PREF_LOOK_STRENGTH = "look_strength"
        private const val PREF_COLOUR_PIPELINE = "colour_pipeline"
        private const val PREF_BURST_MERGE = "burst_merge"
        private const val GYRO_SMOOTHING = 0.1
        private const val LOOK_LUT_ASSET = "luts/redline_standard_preview_33.cube"
        private const val LOOK_BADGE = "Look preview (approx.)"
        private const val LOOK_PREPARE_TIMEOUT_MS = 1_500L
        private const val LOOK_DEFAULT_FPS = 30
        private val LOOK_STRENGTHS = floatArrayOf(1f, 0.75f, 0.5f, 0.25f)

        /** One worker for every check so two runs never hold the camera at once. */
        private val worker: ExecutorService = Executors.newSingleThreadExecutor()

        /** B21 preparation (native, assets, self-check) and profile selection, off the camera checks' worker. */
        private val colourWorker: ExecutorService = Executors.newSingleThreadExecutor()
    }
}
