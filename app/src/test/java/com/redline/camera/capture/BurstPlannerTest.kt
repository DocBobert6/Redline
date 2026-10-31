package com.redline.camera.capture

import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BurstPlannerTest {
    /** The baseline device profile's main camera: ISO 29-7213, 57 us - 16 s, RAW at 30 fps. */
    private fun inputs(
        iso: Int? = 100,
        exposureNs: Long? = 10_000_000L,
        pool: Int = 26,
        queued: Int = 0,
        motion: Double? = 0.05,
        manualIso: Int? = null,
        manualExposureNs: Long? = null,
        enabled: Boolean = true
    ) = BurstInputs(
        enabled = enabled,
        meteredIso = iso,
        meteredExposureNs = exposureNs,
        manualIso = manualIso,
        manualExposureNs = manualExposureNs,
        isoRange = 29 to 7213,
        exposureRangeNs = 57_000L to 16_000_000_000L,
        rawMinFrameDurationNs = 33_333_333L,
        poolFreeFrames = pool,
        burstsQueued = queued,
        motionRadPerSec = motion
    )

    @Test
    fun brightSceneTakesFourFramesUnderExposedByShutter() {
        val p = BurstPlanner.plan(inputs())
        assertEquals(4, p.frames)
        assertEquals("bright", p.light)
        assertEquals(100, p.iso)
        assertEquals(Math.round(10_000_000L * 2.0.pow(-0.7)), p.exposureNs)
        assertEquals(0.7, p.underEv, 1e-3)
        assertEquals(33_333_333L, p.frameDurationNs)
        assertEquals(listOf("light"), p.limits)
        assertEquals(4, p.residentFrames)
        assertNull(p.singleReason)
    }

    @Test
    fun frameCountFollowsTheLight() {
        assertEquals(6, BurstPlanner.plan(inputs(iso = 200)).frames)
        assertEquals(8, BurstPlanner.plan(inputs(iso = 320)).frames)
        assertEquals(10, BurstPlanner.plan(inputs(iso = 800)).frames)
        val dark = BurstPlanner.plan(inputs(iso = 3200, exposureNs = 33_000_000L))
        assertEquals(12, dark.frames)
        assertEquals("dark", dark.light)
        assertTrue(dark.frames <= BurstPlanner.MAX_FRAMES)
        // At most 6 frames are held in pool buffers; the rest stream through the camera's queue.
        assertEquals(BurstPlanner.MAX_RESIDENT_FRAMES, dark.residentFrames)
        assertEquals(
            listOf("bright", "good", "indoor", "dim", "dark"),
            listOf(50, 150, 399, 1599, 1600).map {
                BurstPlanner.lightLevel(it)
            }
        )
    }

    @Test
    fun shortestShutterFallsBackToLowerIsoAndClamps() {
        // 100 us at ISO 400: 0.7 EV shorter would be under the 57 us floor, so ISO drops instead.
        val p = BurstPlanner.plan(inputs(iso = 400, exposureNs = 80_000L))
        assertEquals(57_000L, p.exposureNs)
        assertTrue(p.iso in 29..400)
        assertEquals(0.7, p.underEv, 0.02)
        // At the ISO floor too: less under-exposure is possible, and it says so.
        val q = BurstPlanner.plan(inputs(iso = 29, exposureNs = 60_000L))
        assertEquals(57_000L, q.exposureNs)
        assertEquals(29, q.iso)
        assertTrue(q.underEv < 0.1)
    }

    @Test
    fun manualModeKeepsTheUsersExposure() {
        val p = BurstPlanner.plan(inputs(manualIso = 200, manualExposureNs = 4_000_000L))
        assertEquals(200, p.iso)
        assertEquals(4_000_000L, p.exposureNs)
        assertEquals(0.0, p.underEv, 1e-9)
        // M is planned even before any auto-exposure reading exists.
        assertTrue(BurstPlanner.plan(inputs(iso = null, exposureNs = null, manualIso = 200, manualExposureNs = 4_000_000L)).isBurst)
    }

    @Test
    fun longExposuresShortenTheBurst() {
        val p = BurstPlanner.plan(inputs(manualIso = 800, manualExposureNs = 250_000_000L))
        assertEquals(4, p.frames) // 1.2 s / 0.25 s
        assertTrue("duration" in p.limits)
        val single = BurstPlanner.plan(inputs(manualIso = 100, manualExposureNs = 1_000_000_000L))
        assertFalse(single.isBurst)
        assertNotNull(single.singleReason)
        assertTrue(single.singleReason!!.contains("exposure too long"))
    }

    @Test
    fun motionAndMemoryLimitTheCount() {
        val moving = BurstPlanner.plan(inputs(iso = 3200, motion = 1.2))
        assertEquals(BurstPlanner.FRAMES_HIGH_MOTION, moving.frames)
        assertTrue("motion" in moving.limits)
        // Four free buffers: 4 resident + 3 waiting in the camera queue + 2 the merge takes in meanwhile (10 x 33 ms / 120 ms).
        val tight = BurstPlanner.plan(inputs(iso = 800, pool = 4))
        assertEquals(9, tight.frames)
        assertEquals(4, tight.residentFrames)
        assertTrue("memory" in tight.limits)
        val none = BurstPlanner.plan(inputs(pool = 2))
        assertFalse(none.isBurst)
        assertTrue(none.singleReason!!.contains("memory"))
    }

    @Test
    fun singleFramePathReasons() {
        assertEquals("burst merge off", BurstPlanner.plan(inputs(enabled = false)).singleReason)
        assertTrue(BurstPlanner.plan(inputs(queued = 2)).singleReason!!.startsWith("queue full"))
        assertEquals("no metered exposure yet", BurstPlanner.plan(inputs(iso = null)).singleReason)
        // B22a: a waiting press would sit behind the burst's capture, so the shot is a fast single frame.
        assertTrue(BurstPlanner.plan(inputs().copy(pressesWaiting = 1)).singleReason!!.startsWith("presses waiting"))
    }

    @Test
    fun busyShotsTakeTheCheapestPathNeverARefusal() {
        assertEquals(ShotRouter.Path.SINGLE_RAW to null, ShotRouter.route(wantsRaw = true, pendingSingles = 0, poolFree = 3))
        val full = ShotRouter.route(wantsRaw = true, pendingSingles = ShotRouter.MAX_PENDING_SINGLES, poolFree = 3)
        assertEquals(ShotRouter.Path.CAMERA_JPEG, full.first)
        assertTrue(full.second!!.contains("queue full"))
        val noMemory = ShotRouter.route(wantsRaw = true, pendingSingles = 0, poolFree = 0)
        assertEquals(ShotRouter.Path.CAMERA_JPEG, noMemory.first)
        assertTrue(noMemory.second!!.contains("memory"))
        assertEquals(ShotRouter.Path.CAMERA_JPEG to null, ShotRouter.route(wantsRaw = false, pendingSingles = 0, poolFree = 3))
    }

    @Test
    fun captureTimeCoversTheFrames() {
        val p = BurstPlanner.plan(inputs())
        assertEquals(4 * 33_333_333L / 1_000_000L, p.captureMs)
    }

    @Test
    fun memoryBudget() {
        val frame = 4000L * 3000 * 2
        // Plenty available: two full bursts plus outputs.
        assertEquals(MemoryBudget.MAX_POOL_FRAMES, MemoryBudget.poolFrames(4_000L * 1024 * 1024, false, frame))
        // 1.2 GB available: (400 MB - 192 MB) / 24 MB = 9 frames.
        assertEquals(9, MemoryBudget.poolFrames(1_200L * 1024 * 1024, false, frame))
        assertEquals(4, MemoryBudget.poolFrames(1_200L * 1024 * 1024, true, frame))
        assertEquals(0, MemoryBudget.poolFrames(300L * 1024 * 1024, false, frame))
        assertEquals(0, MemoryBudget.poolFrames(4_000L * 1024 * 1024, false, 0))
    }
}
