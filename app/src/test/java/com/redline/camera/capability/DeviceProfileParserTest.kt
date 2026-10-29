package com.redline.camera.capability

import com.redline.camera.viewfinder.ViewfinderMetrics
import com.redline.camera.viewfinder.ZoomStops
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** B12 cold start reads the cached profile back; the shared report gains a "viewfinder" section. */
class DeviceProfileParserTest {
    @Test
    fun cachedProfileRoundTrips() {
        val profile = Baseline.profile()
        assertEquals(profile, DeviceProfileParser.fromJson(profile.toJson()))
    }

    @Test
    fun malformedOrOtherSchemaIsRejected() {
        assertNull(DeviceProfileParser.fromJson(null))
        assertNull(DeviceProfileParser.fromJson("{"))
        assertNull(DeviceProfileParser.fromJson("[]"))
        assertNull(DeviceProfileParser.fromJson(Baseline.profile().copy(schema = 99).toJson()))
    }

    @Test
    fun reportCarriesViewfinderMetrics() {
        val plan = ZoomStops.plan(ZoomStops.lensesOf(Baseline.back))
        val metrics = ViewfinderMetrics(firstFrameMs = 700, previewFps = 30.0, previewFpsMin = 29.9, aeFpsRange = 30 to 30)
        val state = CapabilityState(viewfinder = metrics, zoomPlan = plan)
        val json = CapabilityReport.toJson(Baseline.profile(), state)
        val vf = (JsonParse.parse(json) as Map<*, *>)["viewfinder"] as Map<*, *>
        assertEquals(700L, vf["first_frame_ms"])
        assertEquals(30.0, vf["preview_fps"])
        assertEquals(listOf(30L, 30L), vf["ae_fps_range"])
        assertEquals(5, (vf["zoom_stops"] as List<*>).size)
        assertTrue(json.contains("\"preview_size\""))
        val empty = CapabilityReport.toMap(Baseline.profile(), CapabilityState())["viewfinder"] as Map<*, *>
        assertEquals("not opened", empty["status"])
    }
}
