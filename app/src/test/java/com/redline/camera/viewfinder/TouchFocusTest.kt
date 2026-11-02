package com.redline.camera.viewfinder

import com.redline.camera.colour.ColourMath
import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B24b touch mapping: a scene point is pushed forward through an independent pixel-domain model of the preview (zoom
 * crop on the active array, the HAL's crop to the buffer aspect, SurfaceTexture's sensor rotation, the front mirror,
 * the view's display rotation and centre-crop fill), tapped, and mapped back with [TouchMapping]. Gate: the region
 * lands within 2 % of the frame of the scene point at every stop (24-70), orientation and zoom API.
 */
class TouchFocusTest {
    private val activeW = 4000
    private val activeH = 3000
    private val stream = 1920 to 1440

    /** Baseline device profile stops (main lens 25.5 mm equivalent in 3:2; 24* is clamped to 1.0x). */
    private val ratios = mapOf(24 to 1.0f, 28 to 28f / 25.5f, 35 to 35f / 25.5f, 50 to 50f / 25.5f, 70 to 70f / 25.5f)

    /** Rotates pixel (x, y) of a w x h image clockwise by deg; returns the point and the new size. */
    private fun rotPx(x: Double, y: Double, w: Double, h: Double, deg: Int): DoubleArray = when ((deg % 360 + 360) % 360) {
        90 -> doubleArrayOf(h - y, x, h, w)
        180 -> doubleArrayOf(w - x, h - y, w, h)
        270 -> doubleArrayOf(y, w - x, h, w)
        else -> doubleArrayOf(x, y, w, h)
    }

    /** Forward model: active-array point -> view pixel. [fov] is the zoomed field of view in active pixels. */
    private fun forward(ax: Double, ay: Double, fov: DoubleArray, g: TouchGeometry): DoubleArray {
        val (fl, ft, fw, fh) = fov.toList()
        // the HAL crops the FOV to the buffer's aspect, centred, then scales it to the buffer
        val aS = g.streamW.toDouble() / g.streamH
        val cw = if (aS > fw / fh) fw else fh * aS
        val ch = if (aS > fw / fh) fw / aS else fh
        val bx = (ax - (fl + (fw - cw) / 2)) / cw * g.streamW
        val by = (ay - (ft + (fh - ch) / 2)) / ch * g.streamH
        var p = rotPx(bx, by, g.streamW.toDouble(), g.streamH.toDouble(), g.sensorOrientation)
        if (g.frontFacing) p = doubleArrayOf(p[2] - p[0], p[1], p[2], p[3])
        p = rotPx(p[0], p[1], p[2], p[3], 360 - g.displayRotation)
        val s = maxOf(g.viewW / p[2], g.viewH / p[3])
        return doubleArrayOf(g.viewW / 2 + (p[0] - p[2] / 2) * s, g.viewH / 2 + (p[1] - p[3] / 2) * s)
    }

    private fun geometries(): List<TouchGeometry> {
        val out = mutableListOf<TouchGeometry>()
        for (so in listOf(90, 270, 0)) {
            for (dr in listOf(0, 90, 180, 270)) {
                for (front in listOf(false, true)) {
                    val portrait = dr % 180 == 0 && so % 180 != 0 || dr % 180 != 0 && so % 180 == 0
                    // the 3:2 box the app lays out (2:3 when the picture is upright in portrait)
                    val (vw, vh) = if (portrait) 1080.0 to 1620.0 else 1620.0 to 1080.0
                    out += TouchGeometry(vw, vh, stream.first, stream.second, so, dr, front, activeW, activeH)
                }
            }
        }
        // a box a pixel off 3:2, and a 16:9 buffer on the 4:3 FOV
        out += TouchGeometry(1081.0, 1619.0, 1920, 1440, 90, 0, false, activeW, activeH)
        out += TouchGeometry(1620.0, 1080.0, 1920, 1080, 90, 90, false, activeW, activeH)
        return out
    }

    @Test
    fun tapRegionLandsOnTheScenePointAtEveryStopAndOrientation() {
        var worst = 0.0
        for ((mm, z) in ratios) {
            for (zoomApi in listOf(true, false)) {
                val crop = TouchMapping.cropRegion(activeW, activeH, z)
                val fov = if (zoomApi) {
                    val w = activeW / z.toDouble()
                    val h = activeH / z.toDouble()
                    doubleArrayOf((activeW - w) / 2, (activeH - h) / 2, w, h)
                } else {
                    doubleArrayOf(crop.left.toDouble(), crop.top.toDouble(), crop.width.toDouble(), crop.height.toDouble())
                }
                val frameW = fov[2]
                for (g in geometries()) {
                    // scene points over the stop's 3:2 frame, away from its edges by more than half a region
                    for (i in 1..7) {
                        for (j in 1..7) {
                            val fx = 0.1 + 0.8 * (i - 1) / 6
                            val fy = 0.1 + 0.8 * (j - 1) / 6
                            val f = TouchMapping.frameInFov(activeW, activeH)
                            val ax = fov[0] + (f.left + fx * f.width) * fov[2]
                            val ay = fov[1] + (f.top + fy * f.height) * fov[3]
                            val v = forward(ax, ay, fov, g)
                            if (v[0] < 0 || v[1] < 0 || v[0] > g.viewW || v[1] > g.viewH) continue
                            val (px, py) = TouchMapping.viewToFov(v[0], v[1], g)
                            val (tx, ty) = TouchMapping.fovToFrame(px, py, activeW, activeH)
                            val region = TouchMapping.regionAround(tx, ty, activeW, activeH)
                            val rect = TouchMapping.meteringRect(region, activeW, activeH, zoomApi, crop)
                            // where the request's rectangle centre is in the scene (active pixels)
                            val cx = rect.left + rect.width / 2.0
                            val cy = rect.top + rect.height / 2.0
                            val sx = if (zoomApi) fov[0] + cx / activeW * fov[2] else cx
                            val sy = if (zoomApi) fov[1] + cy / activeH * fov[3] else cy
                            val err = hypot(sx - ax, sy - ay) / frameW
                            worst = maxOf(worst, err)
                            assertTrue("stop $mm zoomApi $zoomApi $g at ($fx, $fy): error ${err * 100} %", err < 0.02)
                            // and back to the view (the AF point is drawn there)
                            val back = TouchMapping.fovToView(px, py, g)
                            assertEquals(v[0], back.first, 1e-6)
                            assertEquals(v[1], back.second, 1e-6)
                        }
                    }
                }
            }
        }
        // the mapping itself is exact; what is left is integer rounding of the rectangles
        assertTrue("worst ${worst * 100} %", worst < 0.002)
    }

    @Test
    fun stillRegionMatchesTheRawCropAtEveryStop() {
        for ((mm, z) in ratios) {
            val crop = ColourMath.rawCrop(activeW, activeH, z.toDouble())
            val c = com.redline.camera.viewfinder.PxRect(crop[0], crop[1], crop[2], crop[3])
            for (fx in listOf(0.2, 0.5, 0.8)) {
                for (fy in listOf(0.2, 0.5, 0.8)) {
                    val region = TouchMapping.regionAround(fx, fy, activeW, activeH)
                    val px = TouchMapping.regionInCrop(region, c, even = true)
                    assertEquals(0, px.left % 2)
                    assertEquals(0, px.top % 2)
                    val cx = px.left + px.width / 2.0
                    val cy = px.top + px.height / 2.0
                    val ex = crop[0] + fx * crop[2]
                    val ey = crop[1] + fy * crop[3]
                    assertTrue("stop $mm ($fx, $fy)", hypot(cx - ex, cy - ey) / crop[2] < 0.02)
                    // square in pixels, side 0.11 of the frame's short side
                    assertEquals(0.11 * crop[3], px.height.toDouble(), 2.5)
                    assertEquals(px.height.toDouble(), px.width.toDouble(), 2.5)
                }
            }
            // the super-resolution output grid of the same stop
            val grid = TouchMapping.regionInCrop(TouchMapping.regionAround(0.5, 0.5, activeW, activeH), PxRect(0, 0, 3000, 2000), false)
            assertEquals(1500.0, grid.left + grid.width / 2.0, 1.0)
            assertEquals(1000.0, grid.top + grid.height / 2.0, 1.0)
        }
    }

    @Test
    fun portraitTopLeftIsTheSensorsBottomLeft() {
        // back camera, sensor 90, phone upright: the view's top-left corner is the frame's (0, 1) in sensor orientation
        val g = TouchGeometry(1000.0, 1500.0, 1920, 1440, 90, 0, false, activeW, activeH)
        val (px, py) = TouchMapping.viewToFov(0.0, 0.0, g)
        val (fx, fy) = TouchMapping.fovToFrame(px, py, activeW, activeH)
        assertEquals(0.0, fx, 1e-9)
        assertEquals(1.0, fy, 1e-9)
        // the centre is the centre
        val c = TouchMapping.viewToFov(500.0, 750.0, g)
        assertEquals(0.5, c.first, 1e-12)
        assertEquals(0.5, c.second, 1e-12)
    }

    @Test
    fun regionStaysInsideTheFrameNearEdges() {
        val r = TouchMapping.regionAround(0.0, 1.0, activeW, activeH)
        assertEquals(0.0, r.left, 1e-12)
        assertEquals(1.0, r.bottom, 1e-12)
        assertTrue(r.width > 0 && r.height > 0)
        val rect = TouchMapping.meteringRect(r, activeW, activeH, zoomRatioApi = true)
        assertTrue(rect.left >= 0 && rect.top >= 0 && rect.left + rect.width <= activeW && rect.top + rect.height <= activeH)
    }

    @Test
    fun capsNeverEnableWithoutRegions() {
        val none = TouchFocusCaps.from(0, 0, listOf("AUTO", "CONTINUOUS_PICTURE"), true)
        assertFalse(none.enabled)
        assertFalse(TouchFocusCaps.from(null, null, listOf("AUTO"), true).enabled)
        val aeOnly = TouchFocusCaps.from(1, 1, listOf("OFF"), false)
        assertFalse(aeOnly.af)
        assertTrue(aeOnly.ae && aeOnly.enabled)
        val both = TouchFocusCaps.from(1, 1, listOf("OFF", "AUTO", "CONTINUOUS_PICTURE"), true)
        assertTrue(both.af && both.ae)
        assertFalse(TouchFocusSession(none).tap(TouchMapping.regionAround(0.5, 0.5, activeW, activeH), 0, false))
    }

    @Test
    fun sessionTimesFocusAndResets() {
        val s = TouchFocusSession(TouchFocusCaps.from(1, 1, listOf("AUTO"), true))
        val r = TouchMapping.regionAround(0.3, 0.6, activeW, activeH)
        assertTrue(s.tap(r, 1000, lock = false))
        assertEquals(TouchMode.FOCUSING, s.mode)
        s.onAfState(AfState.FOCUSED, 1050) // before the trigger: ignored (a stale result)
        assertEquals(TouchMode.FOCUSING, s.mode)
        s.triggerSubmitted()
        s.onAfState(AfState.SCANNING, 1200)
        s.onAfState(AfState.FOCUSED, 1420)
        assertEquals(TouchMode.FOCUSED, s.mode)
        assertEquals(420L, s.lastFocusMs)
        assertFalse(s.aeLock)
        s.reset(TouchReset.SCENE_EV)
        assertNull(s.region)
        assertEquals(TouchMode.AUTO, s.mode)
        assertEquals(1, s.resets[TouchReset.SCENE_EV])
        // long-press locks AF, and AE (lock available) once focus finished and the region had time to meter
        assertTrue(s.tap(r, 5000, lock = true))
        assertTrue(s.locked)
        assertFalse(s.aeLock)
        s.triggerSubmitted()
        s.onAfState(AfState.FOCUSED, 5300)
        assertFalse(s.armLockIfDue(5300))
        assertTrue(s.armLockIfDue(5600))
        assertTrue(s.aeLock)
        assertFalse(s.armLockIfDue(5700))
        // a tap that never focuses times out
        assertTrue(s.tap(r, 8000, lock = false))
        s.triggerSubmitted()
        s.onAfState(AfState.SCANNING, 11_500)
        assertEquals(TouchMode.TIMED_OUT, s.mode)
        // manual focus distance: the tap only meters
        assertTrue(s.tap(r, 12_000, lock = false, af = false))
        assertEquals(TouchMode.METERED, s.mode)
        assertFalse(s.awaitingTrigger)
    }

    @Test
    fun sceneChangeNeedsASustainedExposureJumpOrADeliberatePan() {
        val sc = SceneChange()
        sc.start(0)
        assertFalse(sc.onExposure(0.0, 500)) // settling
        assertFalse(sc.onExposure(0.0, 1300)) // baseline
        for (t in 0 until 5) assertFalse(sc.onExposure(1.5, 1400L + t * 33))
        assertFalse(sc.onExposure(0.2, 1600)) // back: run broken
        var fired = false
        for (t in 0 until 6) fired = sc.onExposure(-1.2, 1700L + t * 33)
        assertTrue(fired)
        val pan = SceneChange()
        pan.start(0)
        val hfov = Math.toRadians(73.7)
        repeat(100) { assertFalse(pan.onMotion(0.1, 0.02, hfov)) } // hand shake: ignored
        var panned = false
        repeat(40) { if (pan.onMotion(1.0, 0.02, hfov)) panned = true } // 0.8 rad at 1 rad/s > 0.4 x 1.29
        assertTrue(panned)
    }

    @Test
    fun guardNeverExposesBrighterThanTheFrameMeter() {
        val cap = TouchExposureGuard.ev(100, 10_000_000L, 100)
        // the tap lifted AE by 2 EV (ISO 400): the capture comes back to cap + 1/3 EV, ISO first
        val g = TouchExposureGuard.cap(400, 10_000_000L, 100, cap, 50, 100_000L)
        assertTrue(g.capped)
        assertEquals(10_000_000L, g.exposureNs)
        assertTrue(abs(TouchExposureGuard.ev(g.iso, g.exposureNs, 100) - (cap + TouchExposureGuard.HEADROOM_EV)) < 0.02)
        // ISO at its floor: the shutter shortens
        val s = TouchExposureGuard.cap(60, 40_000_000L, 100, cap, 50, 100_000L)
        assertEquals(50, s.iso)
        assertTrue(TouchExposureGuard.ev(s.iso, s.exposureNs, 100) <= cap + TouchExposureGuard.HEADROOM_EV + 1e-6)
        // darker than the meter, or no tap: unchanged
        assertFalse(TouchExposureGuard.cap(50, 5_000_000L, 100, cap, 50, 100_000L).capped)
        assertFalse(TouchExposureGuard.cap(400, 10_000_000L, 100, null, 50, 100_000L).capped)
    }
}
