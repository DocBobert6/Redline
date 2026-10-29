package com.redline.camera

import android.content.Intent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.redline.camera.capability.CapabilityProbe
import com.redline.camera.capability.DeviceProfileProbe
import com.redline.camera.look.LookPreviewReport
import com.redline.camera.manual.ShootMode
import com.redline.camera.ui.BACK_TAG
import com.redline.camera.ui.GRID_TOGGLE_TAG
import com.redline.camera.ui.HISTOGRAM_TOGGLE_TAG
import com.redline.camera.ui.LEVEL_TOGGLE_TAG
import com.redline.camera.ui.LIST_TAG
import com.redline.camera.ui.LOOK_SECTION_TITLE
import com.redline.camera.ui.MODE_ROW_TAG
import com.redline.camera.ui.OPEN_CAPABILITY_TAG
import com.redline.camera.ui.VIEWFINDER_TAG
import com.redline.camera.ui.ZOOM_RING_TAG
import com.redline.camera.ui.modeTag
import com.redline.camera.ui.zoomStopTag
import com.redline.camera.viewfinder.ZoomStops
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Launches without the CAMERA permission prompt (debug extra), so the viewfinder shows its permission
 * message and the self-test reports skipped checks on the emulator. The app opens on the viewfinder (B12);
 * the capability screen is reached through its Info button. A launch with forced failures exercises the
 * demotion rules (B11).
 */
@RunWith(AndroidJUnit4::class)
class LaunchSmokeTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null

    private fun launch(forceFail: String? = null): ActivityScenario<MainActivity> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_NO_PERMISSION_PROMPT, true)
            if (forceFail != null) putExtra(MainActivity.EXTRA_FORCE_FAIL, forceFail)
        }
        return ActivityScenario.launch<MainActivity>(intent).also { scenario = it }
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun exists(tag: String): Boolean = try {
        compose.onNodeWithTag(tag).assertExists()
        true
    } catch (e: Throwable) {
        false
    }

    private fun scrolledTo(text: String, substring: Boolean = false): Boolean = try {
        compose.onNodeWithTag(LIST_TAG).performScrollToNode(hasText(text, substring = substring))
        true
    } catch (e: Throwable) {
        false
    }

    private fun openCapabilityScreen() {
        compose.waitUntil(TIMEOUT_MS) { exists(OPEN_CAPABILITY_TAG) }
        compose.onNodeWithTag(OPEN_CAPABILITY_TAG).performClick()
        compose.waitUntil(TIMEOUT_MS) { exists(LIST_TAG) }
    }

    @Test
    fun appLaunchesIntoViewfinderWithZoomStops() {
        launch()
        compose.waitUntil(TIMEOUT_MS) { exists(VIEWFINDER_TAG) }
        // The emulator camera may be slow or absent; the ring only needs the cached profile.
        compose.waitUntil(TIMEOUT_MS) { exists(ZOOM_RING_TAG) }
        ZoomStops.STOPS.forEach { mm -> compose.onNodeWithTag(zoomStopTag(mm)).assertExists() }
        compose.onNodeWithTag(OPEN_CAPABILITY_TAG).assertExists()
    }

    @Test
    fun zoomStopTapDoesNotCrash() {
        launch()
        compose.waitUntil(TIMEOUT_MS) { exists(zoomStopTag(70)) }
        compose.onNodeWithTag(zoomStopTag(70)).performClick()
        compose.onNodeWithTag(zoomStopTag(24)).performClick()
        compose.onNodeWithTag(VIEWFINDER_TAG).assertExists()
    }

    @Test
    fun modeRowAndProToolTogglesDoNotCrash() {
        launch()
        compose.waitUntil(TIMEOUT_MS) { exists(MODE_ROW_TAG) }
        compose.onNodeWithTag(modeTag(ShootMode.AUTO)).assertExists()
        listOf(GRID_TOGGLE_TAG, LEVEL_TOGGLE_TAG, HISTOGRAM_TOGGLE_TAG).forEach { tag ->
            compose.onNodeWithTag(tag).performClick()
            compose.onNodeWithTag(tag).performClick()
        }
        // Av / M appear only when the emulated camera reports what they need; tapping them must not crash.
        listOf(ShootMode.AV, ShootMode.M, ShootMode.AUTO).forEach { m ->
            if (exists(modeTag(m))) compose.onNodeWithTag(modeTag(m)).performClick()
        }
        compose.onNodeWithTag(VIEWFINDER_TAG).assertExists()
    }

    @Test
    fun navigatesToCapabilityScreenAndBack() {
        launch()
        openCapabilityScreen()
        compose.onNodeWithText("Capability probe").assertExists()
        compose.onNodeWithTag(BACK_TAG).performClick()
        compose.waitUntil(TIMEOUT_MS) { exists(VIEWFINDER_TAG) }
    }

    @Test
    fun capabilityScreenShowsSections() {
        launch()
        openCapabilityScreen()
        compose.waitUntil(TIMEOUT_MS) { scrolledTo("Self-test") }
        compose.onNodeWithText("Self-test").assertExists()
        compose.waitUntil(TIMEOUT_MS) { scrolledTo("Run self-test again") }
        compose.waitUntil(TIMEOUT_MS) { scrolledTo("Manual controls") }
        compose.waitUntil(TIMEOUT_MS) { scrolledTo("Viewfinder") }
        compose.waitUntil(TIMEOUT_MS) { scrolledTo("Feature combinations") }
        compose.waitUntil(TIMEOUT_MS) { scrolledTo("Demotions") }
    }

    /**
     * B20: the Look preview section is always there. The emulator may lack OpenGL ES 3 or the native ABI; then the
     * section says why and the app keeps running on the plain preview. Either way the start-up checks finish.
     */
    @Test
    fun capabilityScreenShowsLookPreviewSection() {
        launch()
        openCapabilityScreen()
        compose.waitUntil(TIMEOUT_MS) { scrolledTo(LOOK_SECTION_TITLE) }
        compose.waitUntil(TIMEOUT_MS) { scrolledTo(LookPreviewReport.LABEL) }
        compose.waitUntil(TIMEOUT_MS) { scrolledTo("LUT pass", substring = true) }
        compose.waitUntil(TIMEOUT_MS) { !scrolledTo("checking OpenGL ES", substring = true) }
    }

    @Test
    fun forcedFailuresDemoteCleanly() {
        launch(forceFail = "raw_capture,fps_24")
        openCapabilityScreen()
        // The emulator may have no RAW or no fixed 24 fps range; either way the 24p default must step down.
        compose.waitUntil(TIMEOUT_MS) { scrolledTo("video_24p: 24p -> 30p", substring = true) }
        compose.waitUntil(TIMEOUT_MS) { scrolledTo("Forced failures (debug)", substring = true) }
    }

    @Test
    fun probeReadsEveryCameraWithoutThrowing() {
        launch()
        val cameras = CapabilityProbe(InstrumentationRegistry.getInstrumentation().targetContext).probeAll()
        assertTrue(cameras.all { it.id.isNotEmpty() })
    }

    @Test
    fun deviceProfileBuildsAndSerialises() {
        val profile = DeviceProfileProbe(InstrumentationRegistry.getInstrumentation().targetContext).build()
        val json = profile.toJson()
        assertTrue(json.startsWith("{") && json.contains("\"cameras\""))
        assertTrue(profile.cameras.all { it.id.isNotEmpty() })
    }

    @Test
    fun cachedProfileIsReusedOnTheSecondRead() {
        val probe = DeviceProfileProbe(InstrumentationRegistry.getInstrumentation().targetContext)
        val (first, _) = probe.cachedOrBuild()
        val (second, hit) = probe.cachedOrBuild()
        assertTrue(hit)
        assertTrue(first == second)
    }

    companion object {
        private const val TIMEOUT_MS = 20_000L
    }
}
