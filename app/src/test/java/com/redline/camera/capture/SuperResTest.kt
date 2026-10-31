package com.redline.camera.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuperResTest {
    private fun burst(frames: Int, frameNs: Long = 33_000_000L) = BurstPlan(
        frames = frames,
        iso = 200,
        exposureNs = 10_000_000L,
        frameDurationNs = frameNs,
        limits = listOf("light"),
        residentFrames = 6
    )

    private fun inputs(stop: Int = 70, plan: BurstPlan? = burst(6), motion: Double? = 0.02, enabled: Boolean = true, crop: Int = 1486) =
        SuperResInputs(stopMm = stop, enabled = enabled, cropLongPx = crop, plan = plan, motionRadPerSec = motion)

    @Test
    fun onlyFiftyAndSeventyWithTheSettingOn() {
        assertFalse(SuperResPlanner.decide(inputs(stop = 35)).applies)
        assertFalse(SuperResPlanner.decide(inputs(stop = 24)).applies)
        assertFalse(SuperResPlanner.decide(inputs(enabled = false)).applies)
        assertTrue(SuperResPlanner.decide(inputs(stop = 50, crop = 2080)).active)
        // A crop already as wide as the output (a native tele lens) needs no super-resolution.
        assertFalse(SuperResPlanner.decide(inputs(crop = 3200)).applies)
    }

    @Test
    fun framesRaisedToEightAndCappedAtTwelve() {
        val d6 = SuperResPlanner.decide(inputs(plan = burst(6)))
        assertTrue(d6.active)
        assertEquals(8, d6.frames)
        assertEquals(3000, d6.outWidth)
        assertEquals(2000, d6.outHeight)
        assertEquals(10, SuperResPlanner.decide(inputs(plan = burst(10))).frames)
        assertEquals(12, SuperResPlanner.decide(inputs(plan = burst(12))).frames)
        // The B22 sensor-time limit still holds: 200 ms frames allow 6 within 1.2 s.
        assertEquals(6, SuperResPlanner.decide(inputs(plan = burst(4, frameNs = 200_000_000L))).frames)
        val p = SuperResPlanner.plan(burst(6), d6)
        assertEquals(8, p.frames)
        assertTrue("superres" in p.limits)
    }

    @Test
    fun tripodAndSingleFramesAreUpscaledWithAReason() {
        val still = SuperResPlanner.decide(inputs(motion = 0.001))
        assertTrue(still.applies)
        assertFalse(still.active)
        assertTrue(still.reason!!.contains("too still"))
        assertEquals(burst(6), SuperResPlanner.plan(burst(6), still))
        val single = SuperResPlanner.decide(inputs(plan = BurstPlan(frames = 0, singleReason = "presses waiting")))
        assertTrue(single.applies && !single.active)
        assertTrue(single.reason!!.contains("presses waiting"))
        val off = SuperResPlanner.decide(inputs(plan = null))
        assertTrue(off.applies && !off.active)
        // No gyroscope: treated as handheld.
        assertTrue(SuperResPlanner.decide(inputs(motion = null)).active)
    }

    private fun shot(stop: Int, merged: Boolean, reason: String? = null, queued: Int = 0, stress: Boolean = false) = SuperResShot(
        stopMm = stop,
        decision = SuperResDecision(true, merged, if (merged) 8 else 0, 3000, 2000, reason),
        merged = merged,
        fallbackReason = if (merged) null else reason,
        framesMerged = if (merged) 8 else 0,
        outWidth = 3000,
        outHeight = 2000,
        coverage = if (merged) 0.6 else null,
        peakNativeMb = 100.0,
        outputMb = 68.7,
        queuedBehind = queued,
        stress = stress
    )

    @Test
    fun reportGatesAndCounters() {
        var r = SuperResReport(available = true, selfCheckGain = 1.65, selfCheckFlowErrorPx = 0.05, selfCheckCoverage = 0.86)
        assertTrue(r.selfCheckPassed)
        assertTrue(r.enabled)
        assertNull(r.saveGate)
        r = r.record(shot(50, true), 3200, ok = true)
        assertNull(r.saveGate) // 70 mm not yet
        r = r.record(shot(70, true, queued = 1), 6500, ok = true) // queued behind: not gate-eligible
        assertNull(r.saveGate)
        r = r.record(shot(70, true), 2900, ok = true)
        assertEquals(true, r.saveGate)
        r = r.record(shot(70, false, "too still"), 2000, ok = true)
        assertEquals(1, r.upscaledShots)
        assertEquals(1, r.upscaleReasons["too still"])
        assertEquals(3, r.mergedShots)
        r = r.record(shot(50, true), 5200, ok = true)
        assertEquals(false, r.saveGate)
        val m = r.toMap()
        val gate = m["gate"] as Map<*, *>
        assertEquals(false, gate["save_lt_5000_ms_50_70"])
        val gain = gate["resolution_gain"] as Map<*, *>
        assertEquals(1.65, gain["self_check_gain"])
        assertEquals(true, gain["passed"])
        assertEquals("3000x2000", m["output_px"])
        assertNotNull(m["last"])
        assertEquals(168.7, m["peak_native_mb"])
    }

    @Test
    fun selfCheckFailureTurnsItOff() {
        val r = SuperResReport(
            available = false,
            reason = "native self-check failed",
            selfCheckGain = 1.02,
            selfCheckFlowErrorPx = 0.4
        )
        assertFalse(r.selfCheckPassed)
        assertFalse(r.enabled)
        assertEquals("native self-check failed", r.whyOff())
        assertEquals(
            "turned off (50/70 mm stills keep the crop's own size)",
            SuperResReport(available = true, userSetting = false).whyOff()
        )
    }
}
