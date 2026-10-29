package com.redline.camera.capture

import com.redline.camera.capability.Dim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureMathTest {
    @Test
    fun crop32KeepsLongSideOfLandscapeSensor() {
        // Baseline main camera: 4000x3000 -> 4000x2667 centred (same framing as the 3:2 viewfinder).
        val c = CaptureMath.crop32(4000, 3000)
        assertEquals(4000, c.width)
        assertEquals(2667, c.height)
        assertEquals(0, c.left)
        assertEquals(166, c.top)
    }

    @Test
    fun crop32HandlesPreRotatedPortraitBuffers() {
        val c = CaptureMath.crop32(3000, 4000)
        assertEquals(2667, c.width)
        assertEquals(4000, c.height)
        assertEquals(166, c.left)
        assertEquals(0, c.top)
    }

    @Test
    fun crop32OfWiderThanThreeTwoCropsTheLongSide() {
        val c = CaptureMath.crop32(4000, 2000)
        assertEquals(3000, c.width)
        assertEquals(2000, c.height)
        assertEquals(500, c.left)
    }

    @Test
    fun outputSizeScalesToStopWidthAndNeverUpscales() {
        val crop = CaptureMath.crop32(4000, 3000)
        assertEquals(Dim(2910, 1940), CaptureMath.outputSize(crop, 2910))
        assertEquals(Dim(4000, 2667), CaptureMath.outputSize(crop, 4000))
        assertEquals(Dim(4000, 2667), CaptureMath.outputSize(crop, 9000))
        assertEquals(Dim(4000, 2667), CaptureMath.outputSize(crop, 0))
        val portrait = CaptureMath.crop32(3000, 4000)
        assertEquals(Dim(1460, 2190), CaptureMath.outputSize(portrait, 2190))
    }

    @Test
    fun jpegOrientationFollowsCamera2Formula() {
        assertEquals(90, CaptureMath.jpegOrientation(90, 0, frontFacing = false))
        assertEquals(180, CaptureMath.jpegOrientation(90, 90, frontFacing = false))
        assertEquals(0, CaptureMath.jpegOrientation(90, 270, frontFacing = false))
        assertEquals(270, CaptureMath.jpegOrientation(270, 0, frontFacing = true))
        assertEquals(180, CaptureMath.jpegOrientation(270, 90, frontFacing = true))
        // Rounds to the nearest quarter turn.
        assertEquals(180, CaptureMath.jpegOrientation(90, 100, frontFacing = false))
        assertEquals(90, CaptureMath.jpegOrientation(90, 350, frontFacing = false))
    }

    @Test
    fun exifOrientationRoundTrips() {
        listOf(0, 90, 180, 270).forEach { assertEquals(it, CaptureMath.degreesOf(CaptureMath.exifOrientation(it))) }
        assertEquals(6, CaptureMath.exifOrientation(-270))
    }

    @Test
    fun lookTagIsHonestAndNeverNamesCanonAsTheCamera() {
        val t = CaptureMath.lookTag("0.1.3-alpha.1", StillSaver.LOOK_NONE, "24*", 25.5f)
        assertEquals("Redline 0.1.3-alpha.1 | Look: none (camera JPEG) @ 24*mm (≈25.5mm)", t)
        assertEquals("Redline v | Look: none (camera JPEG) @ 35mm", CaptureMath.lookTag("v", StillSaver.LOOK_NONE, "35", 35f))
        assertEquals(26, CaptureMath.focal35(24, 25.5f))
        assertEquals(50, CaptureMath.focal35(50, null))
    }

    @Test
    fun baseNameIsSortable() {
        assertTrue(CaptureMath.baseName(0L).matches(Regex("RL_\\d{8}_\\d{6}_\\d{3}")))
    }

    @Test
    fun statsCountDngFailuresAsFailuresAndGateNeedsFullRun() {
        var s = CaptureStats(stressRequested = 500, stressRunning = true, stressRaw = true)
        assertNull(s.stressGatePassed)
        val good = ShotResult(ok = true, jpegSize = Dim(10, 10), dngSaved = true, dngWanted = true, shutterToSavedMs = 100)
        val badDng = ShotResult(ok = true, jpegSize = Dim(10, 10), dngWanted = true, shutterToSavedMs = 300, error = "DNG: x")
        repeat(499) { s = s.add(good, stress = true) }
        s = s.add(badDng, stress = true)
        s = s.copy(stressRunning = false)
        assertEquals(500, s.stressDone)
        assertEquals(1, s.stressFailures)
        assertEquals(1, s.failures)
        assertEquals(499, s.dngSaved)
        assertEquals(300L, s.maxSaveMs)
        assertFalse(s.stressGatePassed!!)

        var clean = CaptureStats(stressRequested = 500, stressRunning = true)
        repeat(500) { clean = clean.add(ShotResult(ok = true, shutterToSavedMs = 50), stress = true) }
        assertTrue(clean.copy(stressRunning = false).stressGatePassed!!)
        assertEquals(50L, clean.avgSaveMs)
    }

    @Test
    fun abortedRunFailsTheGate() {
        val s = CaptureStats(stressRequested = 500, stressDone = 120, stressRunning = false)
        assertFalse(s.stressGatePassed!!)
    }
}
