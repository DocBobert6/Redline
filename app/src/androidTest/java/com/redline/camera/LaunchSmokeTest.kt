package com.redline.camera

import android.content.Intent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.redline.camera.capability.CapabilityProbe
import com.redline.camera.capability.DeviceProfileProbe
import com.redline.camera.ui.LIST_TAG
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Launches without the CAMERA permission prompt (debug extra), so the self-test reports skipped
 * checks on the emulator. A second launch forces failures to exercise the demotion rules.
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

    private fun scrolledTo(text: String, substring: Boolean = false): Boolean = try {
        compose.onNodeWithTag(LIST_TAG).performScrollToNode(hasText(text, substring = substring))
        true
    } catch (e: Throwable) {
        false
    }

    @Test
    fun appLaunchesAndShowsProbe() {
        launch()
        compose.onNodeWithText("Capability probe").assertExists()
    }

    @Test
    fun screenShowsSelfTestSection() {
        launch()
        compose.waitUntil(TIMEOUT_MS) { scrolledTo("Self-test") }
        compose.onNodeWithText("Self-test").assertExists()
        compose.waitUntil(TIMEOUT_MS) { scrolledTo("Run self-test again") }
        compose.waitUntil(TIMEOUT_MS) { scrolledTo("Feature combinations") }
        compose.waitUntil(TIMEOUT_MS) { scrolledTo("Demotions") }
    }

    @Test
    fun forcedFailuresDemoteCleanly() {
        launch(forceFail = "raw_capture,fps_24")
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

    companion object {
        private const val TIMEOUT_MS = 20_000L
    }
}
