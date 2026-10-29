package com.redline.camera

import android.Manifest
import android.content.Intent
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.OrientationEventListener
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import com.redline.camera.capture.CaptureStats
import com.redline.camera.capture.ShotResult
import com.redline.camera.ui.CapabilityScreen
import com.redline.camera.ui.ViewfinderScreen
import com.redline.camera.viewfinder.ViewfinderController
import com.redline.camera.viewfinder.ViewfinderMetrics
import com.redline.camera.viewfinder.ViewfinderSetup
import com.redline.camera.viewfinder.ZoomStops
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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

    /** B13: save a DNG next to the JPEG. Off by default (PLAN 5: RAW file is a toggle). */
    private val rawOn = mutableStateOf(false)
    private val shotInFlight = mutableStateOf(false)
    private val streaming = mutableStateOf(false)
    private var deviceDegrees = 0
    private var stressPending = false
    private var stressAbort = false

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
            // Release the texture only once the camera has stopped writing to it.
            lastClose.whenComplete { _, _ -> surface.release() }
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

        override fun onRawUnavailable(reason: String) {
            runOnUiThread {
                rawOn.value = false
                status.value = "RAW unavailable: $reason"
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
        controller = ViewfinderController(this, cameraListener, BuildConfig.VERSION_NAME)
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation != ORIENTATION_UNKNOWN) deviceDegrees = orientation
            }
        }
        savedInstanceState?.let {
            selectedStop.intValue = it.getInt(STATE_STOP, ZoomStops.DEFAULT_STOP)
            rawOn.value = it.getBoolean(STATE_RAW, false)
            if (it.getBoolean(STATE_CAPABILITY, false)) screen.value = Screen.CAPABILITY
        }

        val dngCrop = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_DNG_CROP, true)
        state.value = state.value.copy(capture = state.value.capture.copy(dngCropToFrame = dngCrop))

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
            )
        )

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
        syncCamera()
        if (!awaitingPermission) scheduleChecks(immediate = !permissionGranted.value || setup == null)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        permissionGranted.value = SelfTest(this).hasPermission()
        syncCamera()
    }

    override fun onPause() {
        resumed = false
        orientationListener.disable()
        syncCamera()
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(checksFallback)
        controller.release()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_STOP, selectedStop.intValue)
        outState.putBoolean(STATE_CAPABILITY, screen.value == Screen.CAPABILITY)
        outState.putBoolean(STATE_RAW, rawOn.value)
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
                shutterEnabled = streaming.value && !shotInFlight.value && !state.value.capture.stressRunning,
                rawAvailable = rawAvailable(),
                rawOn = rawOn.value,
                onToggleRaw = ::toggleRaw,
                captureText = state.value.capture.overlayText()
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
                onToggleDngCrop = ::toggleDngCrop,
                onRunStress = {
                    stressPending = true
                    showViewfinder()
                },
                onBack = ::showViewfinder
            )
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

    /** Settings: DNG framing tag on/off, persisted (default on). */
    private fun toggleDngCrop() {
        val next = !state.value.capture.dngCropToFrame
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_DNG_CROP, next).apply()
        state.value = state.value.copy(capture = state.value.capture.copy(dngCropToFrame = next))
    }

    /** One still with the selected stop's framing. [done] runs on the main thread. */
    private fun shoot(keep: Boolean, done: (ShotResult) -> Unit) {
        val stop = setupState.value?.zoomPlan?.stop(selectedStop.intValue)
        val request = ViewfinderController.StillRequest(
            stopLabel = stop?.label ?: "${selectedStop.intValue}",
            stopTargetMm = stop?.targetMm ?: selectedStop.intValue,
            trueEquivalentMm = stop?.trueEquivalentMm,
            stopLongPx = stop?.outputWidthPx ?: 0,
            lensCrop = stop?.zoomRatio ?: 1f,
            dngCropToFrame = state.value.capture.dngCropToFrame,
            deviceDegrees = deviceDegrees,
            keep = keep
        )
        shotInFlight.value = true
        controller.capture(request) { result ->
            runOnUiThread {
                shotInFlight.value = false
                val stress = !keep
                state.value = state.value.copy(capture = state.value.capture.add(result, stress))
                done(result)
            }
        }
    }

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
     * permission granted, a surface and a setup, and no self-test holding the camera. The returned future
     * completes when any close it started has finished.
     */
    private fun syncCamera(): CompletableFuture<Unit> {
        val setup = setupState.value
        val surface = texture
        val want = resumed &&
            screen.value == Screen.VIEWFINDER &&
            permissionGranted.value &&
            surface != null &&
            setup != null &&
            !selfTestOwnsCamera
        if (want && !cameraOpen && surface != null && setup != null) {
            cameraOpen = true
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
            val stop = setup.zoomPlan.stop(selectedStop.intValue)
            val ratio = stop?.zoomRatio ?: 1f
            if (status.value != null && !selfTestOwnsCamera) status.value = null
            controller.open(setup, surface, ratio, appliedStabilization, rawOn.value && rawAvailable())
            updateViewfinder { it.copy(zoomStopMm = stop?.targetMm, zoomRatio = ratio, status = "opening") }
        } else if (!want && cameraOpen) {
            cameraOpen = false
            streaming.value = false
            stressAbort = true
            lastClose = controller.close()
            return lastClose
        }
        return CompletableFuture.completedFuture(Unit)
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

    /** Combination results can demote preview stabilization after the preview opened; follow the policy. */
    private fun applyPolicyToCamera() {
        if (!cameraOpen) return
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
        private const val PREFS = "redline_settings"
        private const val PREF_DNG_CROP = "dng_crop_to_frame"
        private const val DEFAULT_SENSOR_ORIENTATION = 90

        /** Deferred checks start at the first preview frame, or after this long if no frame arrives. */
        private const val CHECKS_FALLBACK_MS = 3_000L
        private const val RELEASE_TIMEOUT_MS = 4_000L

        /** One worker for every check so two runs never hold the camera at once. */
        private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    }
}
