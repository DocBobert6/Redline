package com.redline.camera.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BurstReportTest {
    private val plan8 = BurstPlan(frames = 8, iso = 100, exposureNs = 6_000_000L, frameDurationNs = 33_333_333L, underEv = 0.7)

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
