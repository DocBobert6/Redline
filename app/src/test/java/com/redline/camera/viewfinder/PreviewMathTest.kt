package com.redline.camera.viewfinder

import com.redline.camera.capability.Baseline
import com.redline.camera.capability.Dim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** B12 preview choices: AE fps range, stream size, TextureView transform, rolling fps, report metrics. */
class PreviewMathTest {
    /** The baseline device profile's real AE target ranges. */
    private val baselineRanges = listOf(15 to 15, 15 to 24, 24 to 24, 15 to 30, 24 to 30, 30 to 30, 15 to 60, 60 to 60)

    @Test
    fun baselinePicksFixed30NotTheDimRoom15to30() {
        assertEquals(30 to 30, PreviewFps.choose(baselineRanges))
        assertEquals(30 to 30, PreviewFps.choose(Baseline.back.fpsRanges))
    }

    @Test
    fun without30to30Picks24to30() {
        assertEquals(24 to 30, PreviewFps.choose(baselineRanges - (30 to 30)))
    }

    @Test
    fun fallbacksStayAtOrAbove30WhenTheyCan() {
        assertEquals(20 to 30, PreviewFps.choose(listOf(15 to 30, 20 to 30, 60 to 60)))
        assertEquals(30 to 60, PreviewFps.choose(listOf(15 to 60, 30 to 60, 60 to 60)))
        assertEquals(15 to 30, PreviewFps.choose(listOf(15 to 30, 60 to 60)))
        assertNull(PreviewFps.choose(listOf(15 to 15, 24 to 24)))
        assertNull(PreviewFps.choose(emptyList()))
    }

    @Test
    fun previewSizePrefers1920x1440() {
        val baselinePrivate = listOf(
            Dim(4000, 3000),
            Dim(4000, 2256),
            Dim(3840, 2160),
            Dim(3264, 2448),
            Dim(2560, 1920),
            Dim(1920, 1440),
            Dim(1920, 1080),
            Dim(1440, 1080),
            Dim(1280, 960)
        )
        assertEquals(Dim(1920, 1440), PreviewSizes.choose(baselinePrivate))
        assertEquals(Dim(1440, 1080), PreviewSizes.choose(baselinePrivate - Dim(1920, 1440)))
        assertEquals(Dim(1920, 1080), PreviewSizes.choose(listOf(Dim(4000, 3000), Dim(1920, 1080), Dim(1280, 720))))
        assertEquals(Dim(4000, 3000), PreviewSizes.choose(listOf(Dim(4000, 3000))))
        assertNull(PreviewSizes.choose(emptyList()))
        assertTrue(PreviewSizes.isFourThree(Dim(4000, 3000)))
        assertFalse(PreviewSizes.isFourThree(Dim(1920, 1080)))
    }

    @Test
    fun portraitTransformCropsTheLongSideOf4x3To2x3() {
        // 2:3 portrait frame box, 1920x1440 stream, sensor at 90 degrees, display not rotated.
        val t = PreviewTransform.compute(1080f, 1620f, Dim(1920, 1440), sensorOrientation = 90, displayRotation = 0)
        assertEquals(0f, t.rotationDeg, 0f)
        assertEquals(1215f, t.contentWidth, 0.5f)
        assertEquals(1620f, t.contentHeight, 0.5f)
        assertEquals(1.125f, t.scaleX, 1e-3f)
        assertEquals(1f, t.scaleY, 1e-3f)
        // Content stays 3:4 (undistorted): 4:3 cropped to the 2:3 box keeps the full 4000 px side.
        assertEquals(0.75f, t.contentWidth / t.contentHeight, 1e-3f)
    }

    @Test
    fun landscapeTransformRotatesAndKeepsAspect() {
        val t = PreviewTransform.compute(1620f, 1080f, Dim(1920, 1440), sensorOrientation = 90, displayRotation = 90)
        assertEquals(270f, t.rotationDeg, 0f)
        assertEquals(1620f, t.contentWidth, 0.5f)
        assertEquals(1215f, t.contentHeight, 0.5f)
        // Pre-rotation extents: 1215 x 1620 drawn into a 1620 x 1080 view.
        assertEquals(0.75f, t.scaleX, 1e-3f)
        assertEquals(1.5f, t.scaleY, 1e-3f)
        val r270 = PreviewTransform.compute(1620f, 1080f, Dim(1920, 1440), sensorOrientation = 90, displayRotation = 270)
        assertEquals(90f, r270.rotationDeg, 0f)
        assertEquals(180f, PreviewTransform.compute(1080f, 1620f, Dim(1920, 1440), 90, 180).rotationDeg, 0f)
    }

    @Test
    fun sensorAt270AndNaturalLandscapeDevices() {
        assertEquals(Dim(1440, 1920), PreviewTransform.uprightSize(Dim(1920, 1440), 270, 0))
        assertEquals(Dim(1920, 1440), PreviewTransform.uprightSize(Dim(1920, 1440), 0, 0))
        assertEquals(Dim(1440, 1920), PreviewTransform.uprightSize(Dim(1920, 1440), 0, 90))
    }

    @Test
    fun rollingFpsOverOneSecond() {
        val fps = RollingFps()
        var last: Double? = null
        for (i in 0..90) last = fps.add(i * 33_333_333L)
        assertEquals(30.0, last!!, 0.05)
        // A dim-room 15 fps stretch shows up within the window.
        var t = 90 * 33_333_333L
        repeat(30) {
            t += 66_666_666L
            last = fps.add(t)
        }
        assertEquals(15.0, last!!, 0.1)
        fps.reset()
        assertNull(fps.add(1L))
    }

    @Test
    fun metricsGatesAndReportKeys() {
        val m = ViewfinderMetrics(
            firstFrameMs = 640,
            previewFps = 30.02,
            previewFpsMin = 29.8,
            previewSize = Dim(1920, 1440),
            aeFpsRange = 30 to 30,
            previewStabilization = true
        )
        assertEquals(true, m.startGatePassed)
        assertEquals(true, m.fpsGatePassed)
        assertEquals(false, m.copy(previewFpsMin = 15.0).fpsGatePassed)
        assertEquals(false, m.copy(firstFrameMs = 1200).startGatePassed)
        assertNull(ViewfinderMetrics().fpsGatePassed)
        val map = m.toMap(null)
        assertEquals(640L, map["first_frame_ms"])
        assertEquals(30.02, map["preview_fps"])
        assertEquals("1920x1440", map["preview_size"])
        assertEquals(listOf(30, 30), map["ae_fps_range"])
        assertTrue(m.overlayText().contains("640 ms"))
    }
}
