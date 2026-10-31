package com.redline.camera.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BurstReportTest {
    private val plan8 =
        BurstPlan(
            frames = 8,
            iso = 320,
            exposureNs = 6_000_000L,
            frameDurationNs = 33_333_333L,
            underEv = 0.7,
            light = "indoor",
            residentFrames = 6
        )

    private fun merged(stop: Int, behind: Int = 0, stress: Boolean = false) = BurstShot(
        plan = plan8,
        stopMm = stop,
        framesCaptured = 8,
        framesMerged = 8,
        reference = 1,
        merged = true,
        timingsMs = mapOf("capture" to 400L, "merge_total" to 600L, "render" to 400L),
        queuedBehind = behind,
        stress = stress
    )

    @Test
    fun saveGateNeedsBothStopsAndUsesIsolatedShotsOnly() {
        var r = BurstReport(available = true)
        assertNull(r.saveGate)
        r = r.record(merged(24), 2100, true)
        assertNull(r.saveGate)
        r = r.record(merged(35), 2400, true)
        assertEquals(true, r.saveGate)
        // A shot that waited behind another job, or a stress shot, does not count against the gate.
        r = r.record(merged(35, behind = 1), 4200, true).record(merged(24, stress = true), 5000, true)
        assertEquals(true, r.saveGate)
        r = r.record(merged(24), 3100, true)
        assertEquals(false, r.saveGate)
        assertEquals(5, r.mergedShots)
        assertEquals(40, r.framesMerged)
        val m = r.toMap()
        assertEquals(false, (m["gate"] as Map<*, *>)["save_lt_3000_ms_24_35"])
        assertEquals(400L, (m["stage_avg_ms"] as Map<*, *>)["render"])
    }

    @Test
    fun singleFrameAndFallbackShotsAreCountedSeparately() {
        var r = BurstReport(available = true)
        r = r.record(BurstShot(BurstPlan(0, singleReason = "queue full (2 bursts waiting)"), 24), 900, true)
        r = r.record(BurstShot(plan8, 24, framesCaptured = 8, merged = false, fallbackReason = "merge: boom"), 1500, true)
        assertEquals(1, r.singleShots)
        assertEquals(1, r.fallbacks)
        assertEquals(0, r.mergedShots)
        assertEquals(mapOf("queue full (2 bursts waiting)" to 1), r.singleReasons)
        assertNull(r.saveGate)
    }

    @Test
    fun shutterGateAndQueue() {
        var r = BurstReport(available = true)
        repeat(BurstReport.MIN_PRESSES - 1) { r = r.press(0, false, false) }
        assertNull(r.shutterGate)
        r = r.press(120, false, false)
        assertEquals(true, r.shutterGate)
        assertEquals(1, r.shutterWaits)
        assertEquals(120L, r.maxShutterWaitMs)
        r = r.press(900, true, true)
        assertEquals(false, r.shutterGate)
        assertEquals(1, r.stressBlocked)
        r = r.queue(3).queue(1)
        assertEquals(1, r.queueDepth)
        assertEquals(3, r.maxQueueDepth)
        assertEquals("Processing 1", r.overlay())
        assertNull(r.queue(0).overlay())
    }

    @Test
    fun queuedShotsAreRecordedApartFromTheGate() {
        var r = BurstReport(available = true)
        r = r.record(merged(24), 1900, true).record(merged(35), 2000, true)
        r = r.record(merged(24, behind = 2), 4100, true).record(merged(35, behind = 1), 3500, true)
        assertEquals(true, r.saveGate)
        assertEquals(2, r.queuedShots)
        assertEquals(4100L, r.queuedMaxMs)
        val q = r.toMap()["queued_shots"] as Map<*, *>
        assertEquals(3800L, q["avg_shutter_to_saved_ms"])
    }

    @Test
    fun framesByLightStreamingAndCheapestPath() {
        val st = BurstStreaming(
            framesMergedDuringCapture = 3,
            maxBacklog = 4,
            maxResidentFrames = 5,
            threads = 4,
            mergeTailMs = 500,
            peakNativeMb = 65.0,
            peakResidentMb = 180.0
        )
        val bright =
            BurstPlan(frames = 4, iso = 64, exposureNs = 1_000_000L, frameDurationNs = 33_333_333L, light = "bright", residentFrames = 4)
        var r = BurstReport(available = true)
        r = r.record(merged(24).copy(streaming = st), 1800, true)
        r =
            r.record(
                merged(35).copy(plan = bright, framesMerged = 4, streaming = st.copy(maxResidentFrames = 4, mergeTailMs = 300)),
                1300,
                true
            )
        r =
            r.record(
                BurstShot(BurstPlan(0, singleReason = "queue full; single-frame queue full"), 24, path = BurstShot.PATH_JPEG),
                700,
                true
            )
        assertEquals(listOf(1, 8, 8, 8), r.framesByLight["indoor"])
        assertEquals(listOf(1, 4, 4, 4), r.framesByLight["bright"])
        assertEquals(1, r.cameraJpegShots)
        assertEquals(5, r.streamMaxResidentFrames)
        assertEquals(180.0, r.peakResidentMb, 1e-9)
        val m = r.toMap()
        assertEquals(400L, (m["streaming"] as Map<*, *>)["avg_merge_tail_ms"])
        assertEquals(listOf("bright", "indoor"), (m["frames_by_light"] as Map<*, *>).keys.toList())
        val recent = m["recent_shots"] as List<*>
        assertEquals(3, recent.size)
        assertEquals("camera_jpeg", (recent.last() as Map<*, *>)["path"])
        assertEquals(4, (recent[1] as Map<*, *>)["frames_planned"])
        assertEquals("bright", (recent[1] as Map<*, *>)["light"])
    }

    @Test
    fun availabilityAndReasons() {
        assertFalse(BurstReport().enabled)
        assertTrue(BurstReport().whyOff()!!.contains("checking"))
        assertTrue(BurstReport(available = true).enabled)
        assertFalse(BurstReport(available = true, userSetting = false).enabled)
        assertEquals("no RAW", BurstReport(available = false, reason = "no RAW").whyOff())
        val m = BurstReport(available = true).toMap()
        assertTrue(m.containsKey("gate"))
        assertTrue((m["gate"] as Map<*, *>).containsKey("no_shutter_blocking"))
    }
}
