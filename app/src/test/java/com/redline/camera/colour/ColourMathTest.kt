package com.redline.camera.colour

import com.redline.camera.capability.JsonParse
import com.redline.camera.capture.CaptureMath
import com.redline.camera.capture.CropBox
import kotlin.math.abs
import kotlin.math.max
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B21: the Kotlin per-shot maths reproduces the Python reference (calibration/redline_cal/raw_pipeline.py) on every
 * case in colour_math_cases.json, which calibration/tools/make_colour_fixtures.py generates from the reference.
 */
class ColourMathTest {
    private val cases: Map<*, *> by lazy {
        val text = javaClass.getResource("/colour/colour_math_cases.json")!!.readText()
        JsonParse.parse(text) as Map<*, *>
    }

    private fun list(key: String) = cases[key] as List<*>

    private fun d(v: Any?) = (v as Number).toDouble()

    private fun doubles(v: Any?) = (v as List<*>).map { d(it) }.toDoubleArray()

    private fun close(what: String, expected: Double, actual: Double, rel: Double = 1e-7, abs: Double = 1e-9) {
        val tol = max(abs, rel * kotlin.math.abs(expected))
        assertTrue("$what: expected $expected, got $actual", kotlin.math.abs(expected - actual) <= tol)
    }

    private fun profiles(): Map<String, ColourCalibration> =
        (cases["profiles"] as Map<*, *>).entries.associate { (k, v) -> k as String to ColourCalibration.fromJson(v as Map<*, *>) }

    private fun tone() = doubles(cases["tone"])

    @Test
    fun planckLocusWarmthAndCct() {
        list("planck").forEach {
            val r = doubles(it)
            val xy = ColourMath.planckXy(r[0])
            close("planck x ${r[0]}", r[1], xy[0])
            close("planck y ${r[0]}", r[2], xy[1])
        }
        list("warmth").forEach {
            val r = doubles(it)
            close("warmth ${r[0]}", r[1], ColourMath.warmthRetention(r[0]))
        }
        list("cct").forEach {
            val r = doubles(it)
            close("cct", r[2], ColourMath.xyToCct(r[0], r[1]))
        }
    }

    @Test
    fun colourSolveMatchesTheReference() {
        val p = profiles()
        list("solve").forEach { c ->
            c as Map<*, *>
            val s = ColourMath.solveColour(
                p.getValue(c["profile"] as String),
                doubles(c["as_shot_neutral"]),
                c["warmth_enabled"] as Boolean
            )
            val label = "${c["profile"]} ${c["as_shot_neutral"]} warmth=${c["warmth_enabled"]}"
            doubles(c["matrix"]).forEachIndexed { i, v -> close("$label matrix[$i]", v, s.matrix[i], rel = 1e-6, abs = 1e-7) }
            doubles(c["neutral"]).forEachIndexed { i, v -> close("$label neutral[$i]", v, s.neutral[i], rel = 1e-6, abs = 1e-8) }
            doubles(c["white_xy"]).forEachIndexed { i, v -> close("$label white_xy[$i]", v, s.whiteXy[i], rel = 1e-6) }
            close("$label cct", d(c["cct"]), s.cct, rel = 1e-6)
            close("$label retention", d(c["retention"]), s.warmth, rel = 1e-6, abs = 1e-8)
            assertEquals(label, c["used_forward"], s.usedForward)
        }
    }

    @Test
    fun daylightGreyMapsToWhite() {
        val p = profiles().getValue("baseline")
        val n = list("solve").map { it as Map<*, *> }.first { it["profile"] == "baseline" }
        val s = ColourMath.solveColour(p, doubles(n["as_shot_neutral"]))
        val white = Mat3.apply(s.matrix, doubleArrayOf(1.0, 1.0, 1.0))
        white.forEach { assertEquals(1.0, it, 3e-3) }
    }

    @Test
    fun toneCurveAndShoulderMatchTheReference() {
        val tone = tone()
        list("curves").forEach { c ->
            c as Map<*, *>
            val gain = d(c["gain"])
            val curve = ColourMath.ToneCurve(tone, gain)
            close("y_knee $gain", d(c["y_knee"]), curve.yKnee)
            close("slope $gain", d(c["slope"]), curve.slope)
            close("clip_ev $gain", d(c["clip_ev"]), curve.clipEv)
            close("k $gain", d(c["k"]), curve.k, rel = 1e-6, abs = 1e-8)
            val xs = doubles(c["x"])
            val ys = doubles(c["y"])
            xs.indices.forEach { i -> close("curve($gain) at ${xs[i]}", ys[i], curve.eval(xs[i] * gain), rel = 1e-6, abs = 1e-8) }
            val t16 = doubles(c["table16"])
            val ours = curve.table(16)
            t16.indices.forEach { i -> assertEquals("table16[$i] gain $gain", t16[i], ours[i].toDouble(), 1e-6) }
            assertEquals(1.0, curve.eval(Math.pow(2.0, curve.clipEv)), 1e-9)
        }
        list("shoulder_k").forEach {
            val r = doubles(it)
            close("shoulder k for slope ${r[0]}", r[1], ColourMath.solveShoulderK(r[0]), rel = 1e-6, abs = 1e-8)
        }
    }

    @Test
    fun exposureMatchesTheReference() {
        val tone = tone()
        list("exposure").forEach { c ->
            c as Map<*, *>
            val e = ColourMath.solveExposure(tone, d(c["median_y"]), (c["target"] as? Number)?.toDouble())
            close("exposure ${c["median_y"]} ${c["target"]}", d(c["ev"]), e.ev, rel = 1e-7, abs = 1e-9)
            assertEquals(c["method"], e.method)
        }
    }

    @Test
    fun meteredExposureMatchesTheReference() {
        val tone = tone()
        val met = ColourMath.Metering.parse(cases["metering_model"] as Map<*, *>)
        assertTrue(met.coef[1] < 0)
        list("metering").forEach { c ->
            c as Map<*, *>
            val p98 = (c["p98_y"] as? Number)?.toDouble() ?: Double.NaN
            val p25 = (c["p25_y"] as? Number)?.toDouble() ?: Double.NaN
            val clipped = (c["clipped_fraction"] as? Number)?.toDouble() ?: Double.NaN
            val e = ColourMath.solveExposureMetered(tone, d(c["median_y"]), p98, met, p25, clipped)
            close("metered ${c["median_y"]} ${c["p98_y"]} ${c["p25_y"]}", d(c["ev"]), e.ev, rel = 1e-7, abs = 1e-9)
            assertEquals(c["method"], e.method)
            val lift = (c["headroom_lift_ev"] as? Number)?.toDouble() ?: 0.0
            close("headroom lift ${c["median_y"]} ${c["p25_y"]}", lift, e.headroomLiftEv, rel = 1e-7, abs = 1e-9)
        }
    }

    @Test
    fun overcastSkyOverAsphaltIsLiftedAndAFlatWallIsNot() {
        // B23c.1 device case: overcast sky is the median (p98 only 0.37 EV above it), asphalt fills the darkest quarter
        // (~2.9 EV down). The fit alone places the sky like a flat mid-tone; the headroom lift raises it ~1 EV.
        val tone = tone()
        val met = ColourMath.Metering.parse(cases["metering_model"] as Map<*, *>)
        val head = met.headroom!!
        val med = 0.2
        val p98 = med * Math.pow(2.0, 0.37)
        val overcast = ColourMath.solveExposureMetered(tone, med, p98, met, med * Math.pow(2.0, -2.86), 0.0)
        val noLift = ColourMath.solveExposureMetered(tone, med, p98, met)
        assertTrue("lift ${overcast.headroomLiftEv}", overcast.headroomLiftEv > 0.8 && overcast.headroomLiftEv <= head.liftMaxEv)
        close("lifted gain", noLift.ev + overcast.headroomLiftEv, overcast.ev, rel = 0.0, abs = 1e-12)
        // A uniform wall with the same p98 / median but no dark quarter, and a clipping frame: unchanged.
        val wall = ColourMath.solveExposureMetered(tone, med, p98, met, med * Math.pow(2.0, -0.16), 0.0)
        assertEquals(0.0, wall.headroomLiftEv, 0.0)
        val clipping = ColourMath.solveExposureMetered(tone, med, p98, met, med * Math.pow(2.0, -2.86), 0.05)
        assertEquals(0.0, clipping.headroomLiftEv, 0.0)
        // A contrasty control (p98 3.4 EV over the median, already above the floor): unchanged.
        val control = ColourMath.solveExposureMetered(tone, 0.05, 0.05 * Math.pow(2.0, 3.39), met, 0.05 * Math.pow(2.0, -0.76), 0.0)
        assertEquals(0.0, control.headroomLiftEv, 0.0)
    }

    @Test
    fun jpegMedianMatchesTheReference() {
        list("jpeg_median").forEach { c ->
            c as Map<*, *>
            val px = (c["pixels"] as List<*>).map { (it as Number).toInt() }.toIntArray()
            close("jpeg median of ${px.size / 3} px", d(c["median"]), ColourMath.jpegMedianEncoded(px))
        }
    }

    @Test
    fun cropAndOutputSizeMatchTheReference() {
        list("crop").forEach { c ->
            c as Map<*, *>
            val crop = ColourMath.rawCrop(d(c["width"]).toInt(), d(c["height"]).toInt(), d(c["zoom"]))
            val want = (c["crop"] as List<*>).map { (it as Number).toInt() }.toIntArray()
            assertArrayEquals("crop $c", want, crop)
        }
        list("output_size").forEach {
            val r = (it as List<*>).map { v -> (v as Number).toInt() }
            val out = CaptureMath.outputSize(CropBox(0, 0, r[0], r[1]), r[2])
            assertEquals("output size $r", r[3], out.width)
            assertEquals("output size $r", r[4], out.height)
        }
    }

    @Test
    fun labAndDeltaE2000MatchTheReference() {
        list("lab").forEach { c ->
            c as Map<*, *>
            val rgb = doubles(c["rgb"])
            val lab = ColourMath.srgbEncodedToLab(rgb[0], rgb[1], rgb[2])
            doubles(c["lab"]).forEachIndexed { i, v -> assertEquals("lab[$i] of ${rgb.toList()}", v, lab[i], 1e-6) }
        }
        list("de2000").forEach { c ->
            c as Map<*, *>
            val de = ColourMath.deltaE2000(doubles(c["lab1"]), doubles(c["lab2"]))
            assertEquals("dE2000 ${c["lab1"]} ${c["lab2"]}", d(c["de"]), de, 1e-4)
        }
    }

    @Test
    fun matrixHelpers() {
        val m = doubleArrayOf(2.0, 1.0, 0.0, 0.0, 3.0, 1.0, 1.0, 0.0, 4.0)
        val i = Mat3.mul(m, Mat3.inv(m))
        Mat3.IDENTITY.indices.forEach { assertEquals(Mat3.IDENTITY[it], i[it], 1e-12) }
        val v = Mat3.solve(m, doubleArrayOf(1.0, 2.0, 3.0))
        val back = Mat3.apply(m, v)
        assertTrue(abs(back[0] - 1.0) + abs(back[1] - 2.0) + abs(back[2] - 3.0) < 1e-12)
    }
}
