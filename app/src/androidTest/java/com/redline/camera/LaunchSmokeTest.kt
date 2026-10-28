package com.redline.camera

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.redline.camera.capability.CapabilityProbe
import com.redline.camera.capability.DeviceProfileProbe
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LaunchSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun appLaunchesAndShowsProbe() {
        compose.onNodeWithText("Capability probe").assertExists()
    }

    @Test
    fun probeReadsEveryCameraWithoutThrowing() {
        val cameras = CapabilityProbe(compose.activity).probeAll()
        assertTrue(cameras.all { it.id.isNotEmpty() })
    }

    @Test
    fun deviceProfileBuildsAndSerialises() {
        val profile = DeviceProfileProbe(compose.activity).build()
        val json = profile.toJson()
        assertTrue(json.startsWith("{") && json.contains("\"cameras\""))
        assertTrue(profile.cameras.all { it.id.isNotEmpty() })
    }
}
