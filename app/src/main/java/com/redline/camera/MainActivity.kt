package com.redline.camera

import android.Manifest
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
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
import com.redline.camera.ui.CapabilityScreen
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val state = mutableStateOf(CapabilityState())
    private var profile: DeviceProfile? = null

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { runChecks(force = false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val cameras = runCatching { CapabilityProbe(this).probeAll() }.getOrDefault(emptyList())
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        val calibrations = cameras.mapNotNull { c ->
            val candidates = (listOf(c.id) + c.physicalIds).mapNotNull { id ->
                runCatching { FactoryCalibration.read(c.id, manager.getCameraCharacteristics(id), id) }.getOrNull()
            }
            FactoryCalibration.pick(candidates)?.let { c.id to it }
        }.toMap()
        val profileProbe = DeviceProfileProbe(this)
        profile = runCatching { profileProbe.build() }.getOrNull()

        val quirksRepo = QuirksRepository(this)
        val quirks = quirksRepo.load()
        val forced = if (BuildConfig.DEBUG) DebugOverrides.parse(intent.getStringExtra(EXTRA_FORCE_FAIL)) else emptySet()
        state.value = state.value.copy(quirks = quirks, quirkDisables = quirks.disabledFor(QuirksRepository.deviceKey()), forced = forced)
        // Remote quirks refresh: background, short timeout, used from the next check run on.
        worker.execute { quirksRepo.refresh() }

        setContent {
            CapabilityScreen(
                cameras = cameras,
                calibrations = calibrations,
                onShareCalibration = { cal -> share("redline_camera2_${cal.cameraId}.json", cal.toJson(), "Share calibration") },
                profile = profile,
                onShareReport = {
                    val json = profile?.let { CapabilityReport.toJson(it, state.value) }
                        ?: runCatching { profileProbe.cachedJson() }.getOrDefault("{}")
                    share("redline_capability_report.json", json, "Share capability report")
                },
                state = state.value,
                onRunSelfTest = { runChecks(force = true) },
                onRepeatSelfTest = if (BuildConfig.DEBUG) ({ repeatSelfTest(REPEAT_RUNS) }) else null
            )
        }

        val noPrompt = BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_NO_PERMISSION_PROMPT, false)
        if (savedInstanceState == null && !SelfTest(this).hasPermission() && !noPrompt) {
            permissionRequest.launch(Manifest.permission.CAMERA)
        } else {
            runChecks(force = false)
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
            val report = SelfTest(app).cachedOrRun(camera, force)
            update { it.copy(selfTest = report, selfTestRunning = false) }
        }
    }

    /** Debug-only: run the self-test [runs] times in a row and count clean passes (the B11 100/100 gate). */
    private fun repeatSelfTest(runs: Int) {
        val app = applicationContext
        val camera = FeaturePolicy.defaultCamera(profile)
        update { it.copy(selfTestRunning = true, repeatStatus = "0/$runs") }
        worker.execute {
            var passed = 0
            for (i in 1..runs) {
                val report = SelfTest(app).cachedOrRun(camera, force = true)
                if (report.passed) passed++
                update { it.copy(selfTest = report, repeatStatus = "$passed/$i runs passed (of $runs)") }
            }
            update { it.copy(selfTestRunning = false) }
        }
    }

    private fun update(change: (CapabilityState) -> CapabilityState) {
        runOnUiThread { state.value = change(state.value) }
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

        /** One worker for every check so two runs never hold the camera at once. */
        private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    }
}
