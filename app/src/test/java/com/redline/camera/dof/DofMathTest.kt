package com.redline.camera.dof

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** B31: phone-equivalent aperture, focus distance, the dial, focus-point mapping and the DOF report. */
class DofMathTest {
    @Test
    fun phoneEquivalentApertureFromSensorAndCrop() {
        // A full-frame 36 mm wide sensor at f/2.8, uncropped: the equivalent is the f-number itself.
        assertEquals(2.8, DofMath.phoneEquivalentAperture(2.8, 36.0, 1.0)!!, 1e-9)
        // A 6.4 mm wide phone sensor at f/1.7: 1.7 x 36 / 6.4 = 9.5625; a 1.4x crop (35 mm stop from a 25 mm main)
        // narrows the sensor area used to 6.4 / 1.4 mm, so the equivalent grows by 1.4.
        assertEquals(9.5625, DofMath.phoneEquivalentAperture(1.7, 6.4, 1.0)!!, 1e-9)
        assertEquals(9.5625 * 1.4, DofMath.phoneEquivalentAperture(1.7, 6.4, 1.0 / 1.4)!!, 1e-9)
        assertNull(DofMath.phoneEquivalentAperture(null, 6.4, 1.0))
        assertNull(DofMath.phoneEquivalentAperture(1.7, 0.0, 1.0))
        // Physical active width: sensor width x active / pixel array.
        assertEquals(6.0, DofMath.activeWidthMm(6.4, 3750, 4000)!!, 1e-9)
        assertEquals(6.4, DofMath.activeWidthMm(6.4, 0, 0)!!, 1e-9)
        assertNull(DofMath.activeWidthMm(null, 4000, 4000))
    }

    @Test
    fun cropFractionFollowsTheRawCrop() {
        // 4:3 active array: the 3:2 crop keeps the full width at 1x, 1/z of it at z.
        assertEquals(1.0, DofMath.cropWidthFraction(4000, 3000, 1.0), 1e-3)
        assertEquals(1 / 1.4, DofMath.cropWidthFraction(4000, 3000, 1.4), 2e-3)
        // 16:9: the 3:2 crop is height-limited (1.5 x 2250 / 4000).
        assertEquals(1.5 * 2250 / 4000, DofMath.cropWidthFraction(4000, 2250, 1.0), 2e-3)
        // Combined: 24 mm-class phone at the 35 stop.
        val n = DofMath.phoneEquivalentAperture(1.7, 6.4, DofMath.cropWidthFraction(4000, 3000, 35.0 / 25.0))!!
        assertEquals(1.7 * 36 / 6.4 * 1.4, n, 0.05)
    }

    @Test
    fun focusDistanceFromDiopters() {
        val af = DofMath.focusDistance(0.5f, 2, 35.0, 2.8)
        assertEquals(2.0, af.metres, 1e-9)
        assertEquals(DofMath.SOURCE_AF, af.source)
        assertEquals(0.5, af.diopters!!, 1e-9)
        assertEquals(0.25, DofMath.focusDistance(4f, 1, 35.0, 2.8).metres, 1e-9)
        assertEquals(DofMath.SOURCE_AF_UNCALIBRATED, DofMath.focusDistance(4f, DofMath.CALIBRATION_UNCALIBRATED, 35.0, 2.8).source)
        // Macro values clamp at the closest accepted distance.
        assertEquals(DofMath.MIN_FOCUS_M, DofMath.focusDistance(50f, 2, 35.0, 2.8).metres, 1e-9)
        // 0 (infinity) or missing: the hyperfocal distance of the virtual lens, f^2 / (N c) + f.
        val h = DofMath.focusDistance(0f, 2, 35.0, 2.8)
        assertEquals(DofMath.SOURCE_HYPERFOCAL, h.source)
        assertEquals((35.0 * 35.0 / (2.8 * 0.03) + 35.0) / 1000.0, h.metres, 1e-9)
        assertEquals(14.618, h.metres, 1e-3)
        assertEquals(DofMath.SOURCE_HYPERFOCAL, DofMath.focusDistance(null, null, 50.0, 4.0).source)
        assertEquals(DofMath.SOURCE_HYPERFOCAL, DofMath.focusDistance(Float.NaN, null, 50.0, 4.0).source)
    }

    @Test
    fun dialIsThirdStopsFrom28To22() {
        val a = DofMath.APERTURES
        assertEquals(2.8f, a.first())
        assertEquals(22f, a.last())
        assertEquals(19, a.size) // 6 stops x 3 + 1
        // f-number x 2^(1/6) per third of a stop (the marked values are rounded, so each step is 0.5-1.5 thirds)
        fun thirds(x: Float, y: Float) = 6 * Math.log(y.toDouble() / x) / Math.log(2.0)
        for (i in 1 until a.size) assertEquals("step ${a[i - 1]} -> ${a[i]}", 1.0, thirds(a[i - 1], a[i]), 0.5)
        assertEquals(18.0, thirds(a.first(), a.last()), 0.3)
        assertEquals(4f, DofMath.snap(4.1f))
        assertEquals(2.8f, DofMath.snap(1.4f))
        assertEquals(22f, DofMath.snap(32f))
        assertEquals(2.8f, DofMath.snap(Float.NaN))
        assertEquals(3.2f, DofMath.step(2.8f, 1))
        assertEquals(2.8f, DofMath.step(2.8f, -1))
        assertEquals(22f, DofMath.step(20f, 5))
        assertEquals("f/2.8", DofMath.label(2.8f))
        assertEquals("f/4", DofMath.label(4f))
        assertEquals("f/11", DofMath.label(11f))
    }

    @Test
    fun virtualFocalHonestStopsKeepTheirLabel() {
        assertEquals(70.0, DofMath.virtualFocalMm(70, 69.5f), 1e-9)
        assertEquals(25.5, DofMath.virtualFocalMm(24, 25.5f), 1e-6)
        assertEquals(35.0, DofMath.virtualFocalMm(35, null), 1e-9)
    }

    @Test
    fun focusPointMapping() {
        // B31.1: the tap is a frame region (normalised in the stop's 3:2 frame = the still), so it maps as is at every
        // zoom (alpha.1 pushed it through the zoomed RAW crop again: a tap at 0.25 landed at 0 at 50 mm).
        assertEquals(0.25 to 0.4, DofMath.tapInStill(0.25, 0.4))
        assertEquals(0.5 to 0.5, DofMath.tapInStill(0.5, 0.5))
        assertEquals(0.0 to 1.0, DofMath.tapInStill(-0.1, 1.3))
        // Upright <-> still for every rotation.
        for (rot in listOf(0, 90, 180, 270, -90, 450)) {
            val (x, y) = DofMath.uprightToStill(0.2, 0.7, rot)
            val (u, v) = DofMath.stillToUpright(x, y, rot)
            assertEquals(0.2, u, 1e-12)
            assertEquals(0.7, v, 1e-12)
        }
        // 90 degrees clockwise to upright: the still's top-left is the upright top-right.
        assertEquals(1.0 to 0.0, DofMath.stillToUpright(0.0, 0.0, 90))
    }

    /**
     * B31.1: a re-edit tap on the upright picture lands on the same scene point in the still (and so in the depth map,
     * which depth_post rotates back to the still's orientation: x = v, y = H - 1 - u at 90, etc.) for 90/180/270. A
     * marker at still (0.1, 0.2) (a face near the still's top-left) is found where the upright picture shows it.
     */
    @Test
    fun reeditTapFollowsTheRotation() {
        val still = 0.1 to 0.2
        val upright = mapOf(
            90 to (0.8 to 0.1), // the still's left edge is the upright top (turned clockwise)
            180 to (0.9 to 0.8),
            270 to (0.2 to 0.9),
            0 to (0.1 to 0.2)
        )
        for ((rot, uv) in upright) {
            val (u, v) = DofMath.stillToUpright(still.first, still.second, rot)
            assertEquals("rot $rot u", uv.first, u, 1e-12)
            assertEquals("rot $rot v", uv.second, v, 1e-12)
            val (x, y) = DofMath.uprightToStill(uv.first, uv.second, rot)
            assertEquals("rot $rot x", still.first, x, 1e-12)
            assertEquals("rot $rot y", still.second, y, 1e-12)
        }
        // The same convention as depth_post's uprightToSensor on a W x H still (pixel centres): upright (u, v) of the
        // rotated picture -> still pixel.
        val w = 30
        val h = 20
        for (rot in listOf(90, 180, 270)) {
            val uw = if (rot == 180) w else h
            val uh = if (rot == 180) h else w
            for ((pu, pv) in listOf(3 to 4, 0 to 0, uw - 1 to uh - 1)) {
                val (sx, sy) = when (rot) {
                    90 -> pv to h - 1 - pu
                    180 -> w - 1 - pu to h - 1 - pv
                    else -> w - 1 - pv to pu
                }
                val (x, y) = DofMath.uprightToStill((pu + 0.5) / uw, (pv + 0.5) / uh, rot)
                assertEquals("rot $rot x", (sx + 0.5) / w, x, 1e-9)
                assertEquals("rot $rot y", (sy + 0.5) / h, y, 1e-9)
            }
        }
    }

    /** B31.1: no tap: the focus lands on the main person, never between two masked regions. */
    @Test
    fun personFocusPointStaysOnThePerson() {
        val w = 60
        val h = 40
        val m = FloatArray(w * h)
        // a child in the middle-left and a near adult leg along the right edge, both masked
        for (y in 12 until 30) for (x in 14 until 30) m[y * w + x] = 1f
        for (y in 0 until 40) for (x in 50 until 60) m[y * w + x] = 1f
        val c = DofMath.maskCentroid(m, w, h)!!
        val cx = (c.first * w).toInt()
        val cy = (c.second * h).toInt()
        assertTrue("the plain centroid falls between them", m[cy * w + cx] < 0.5f)
        val p = DofMath.personFocusPoint(m, w, h)!!
        val px = (p.first * w).toInt()
        val py = (p.second * h).toInt()
        assertTrue("on the mask", m[py * w + px] >= 0.5f)
        assertTrue("on the central person", px in 14 until 30 && py in 12 until 30)
        assertNull(DofMath.personFocusPoint(FloatArray(w * h), w, h))
    }

    @Test
    fun personCentroid() {
        val w = 40
        val h = 20
        val m = FloatArray(w * h)
        for (y in 5 until 15) for (x in 10 until 20) m[y * w + x] = 1f
        val c = DofMath.maskCentroid(m, w, h)
        assertNotNull(c)
        assertEquals(15.0 / w, c!!.first, 1e-9)
        assertEquals(10.0 / h, c.second, 1e-9)
        assertNull(DofMath.maskCentroid(FloatArray(w * h), w, h))
        val tiny = FloatArray(w * h).also { it[0] = 1f }
        assertNull(DofMath.maskCentroid(tiny, w, h))
    }

    @Test
    fun reportRecordsAppliedAndSkips() {
        var r = DofReport()
        assertTrue(r.statusLine().contains("no shots yet"))
        r = r.record(
            DofShot(
                base = "a",
                applied = true,
                aperture = 2.8f,
                depthMs = 160,
                bokehMs = 900,
                confidence = 0.8,
                shutterToSavedMs = 2600
            )
        )
        r = r.record(DofShot(base = "b", applied = false, reason = "rapid fire (2 queued)", aperture = 2.8f))
        r = r.record(DofShot(base = "c", applied = false, reason = "low confidence (0.20 < 0.35)", aperture = 2.8f))
        r = r.fps(29.5).fps(27.0).fps(30.0)
        assertEquals(3, r.shots)
        assertEquals(1, r.applied)
        assertEquals(mapOf("rapid fire" to 1, "low confidence" to 1), r.skips)
        assertEquals(27.0, r.previewFpsMin!!, 1e-9)
        val m = r.toMap()
        assertEquals(900L, m["max_bokeh_ms"])
        assertEquals(2600L, m["max_shutter_to_saved_ms_applied"])
        assertTrue(r.statusLine().contains("last saved sharp: low confidence"))
        assertTrue(DofReport(enabled = false).statusLine().startsWith("Off"))
        assertEquals("depth engine not ready", DofReport.skipKey("depth engine not ready (warming up)"))
        assertEquals("bokeh error", DofReport.skipKey("bokeh error: IllegalStateException: x"))
    }
}
