package com.redline.camera.colour

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The per-shot maths of the B21 RAW colour pipeline, a line-for-line port of the Python reference
 * (calibration/redline_cal/raw_pipeline.py and dng_profile.py; its docstring is the contract). Pure Kotlin with
 * no Android types, unit-tested against app/src/test/resources/colour/colour_math_cases.json, which the reference
 * generates. The per-pixel work is native (app/src/main/cpp/raw_pipeline.cpp).
 *
 * Matrices are row-major DoubleArray(9).
 */
object ColourMath {
    // ------------------------------------------------------------------------------------------- constants

    val XYZ_TO_SRGB = doubleArrayOf(
        3.2404542, -1.5371385, -0.4985314,
        -0.9692660, 1.8760108, 0.0415560,
        0.0556434, -0.2040259, 1.0572252
    )
    val SRGB_TO_XYZ: DoubleArray = Mat3.inv(XYZ_TO_SRGB)
    val D65 = doubleArrayOf(0.95047, 1.0, 1.08883)
    val D50 = doubleArrayOf(0.9642, 1.0, 0.8249)
    private val BRADFORD = doubleArrayOf(0.8951, 0.2664, -0.1614, -0.7502, 1.7135, 0.0367, 0.0389, -0.0685, 1.0296)
    private val BRADFORD_INV = Mat3.inv(BRADFORD)
    val LUMA = doubleArrayOf(0.2126729, 0.7151522, 0.0721750)

    /** EXIF LightSource / Camera2 SENSOR_REFERENCE_ILLUMINANT codes -> CCT (K). */
    val ILLUMINANT_CCT: Map<Int, Double> = mapOf(
        1 to 5500.0, 2 to 4150.0, 3 to 2856.0, 4 to 5500.0, 9 to 5500.0, 10 to 6500.0, 11 to 7500.0,
        12 to 6430.0, 13 to 5000.0, 14 to 4150.0, 15 to 3450.0, 16 to 2940.0, 17 to 2856.0, 18 to 4874.0,
        19 to 6774.0, 20 to 5503.0, 21 to 6504.0, 22 to 7504.0, 23 to 5003.0, 24 to 3200.0
    )

    // Canon "ambience priority" warmth (PLAN 19.3): fraction of the scene's warm cast retained.
    const val WARMTH_MAX = 0.30
    const val WARMTH_FULL_K = 3000.0
    const val WARMTH_OFF_K = 5000.0

    // Tone curve (log2 grid of the fitted Standard tone) and the highlight shoulder.
    const val EV_MIN = -14.0
    const val EV_MAX = 3.0
    const val KNEE_EV = -1.5
    const val KNEE_SLOPE_DELTA = 0.25
    const val MIN_SHOULDER_EV = 0.5
    const val EXPOSURE_MIN_EV = -2.0
    const val EXPOSURE_MAX_EV = 5.0
    const val DEFAULT_TARGET = 0.46
    const val CURVE_POINTS = 1024
    const val STATS_STEP = 8

    // ------------------------------------------------------------------------------------------- colorimetry

    fun srgbDecode(e: Double): Double {
        val c = e.coerceIn(0.0, 1.0)
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    fun srgbEncode(l: Double): Double {
        val c = max(l, 0.0)
        return if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1.0 / 2.4) - 0.055
    }

    fun xyzToLab(xyz: DoubleArray, white: DoubleArray = D65): DoubleArray {
        val d = 6.0 / 29.0
        val f = DoubleArray(3) { i ->
            val t = xyz[i] / white[i]
            if (t > d * d * d) cbrt(t) else t / (3 * d * d) + 4.0 / 29.0
        }
        return doubleArrayOf(116 * f[1] - 16, 500 * (f[0] - f[1]), 200 * (f[1] - f[2]))
    }

    /** sRGB-encoded (0..1) -> CIELAB (D65). */
    fun srgbEncodedToLab(r: Double, g: Double, b: Double): DoubleArray =
        xyzToLab(Mat3.apply(SRGB_TO_XYZ, doubleArrayOf(srgbDecode(r), srgbDecode(g), srgbDecode(b))))

    /** CIELAB chroma of an sRGB-encoded colour (0 = perfectly neutral). */
    fun chroma(r: Double, g: Double, b: Double): Double {
        val lab = srgbEncodedToLab(r, g, b)
        return hypot(lab[1], lab[2])
    }

    /** CIEDE2000 (Sharma, Wu, Dalal 2005), kL = kC = kH = 1. */
    fun deltaE2000(lab1: DoubleArray, lab2: DoubleArray): Double {
        val (l1, a1, b1) = Triple(lab1[0], lab1[1], lab1[2])
        val (l2, a2, b2) = Triple(lab2[0], lab2[1], lab2[2])
        val c1 = hypot(a1, b1)
        val c2 = hypot(a2, b2)
        val cm = (c1 + c2) / 2
        val g = 0.5 * (1 - sqrt(cm.pow(7) / (cm.pow(7) + 25.0.pow(7))))
        val a1p = (1 + g) * a1
        val a2p = (1 + g) * a2
        val c1p = hypot(a1p, b1)
        val c2p = hypot(a2p, b2)
        val h1p = (Math.toDegrees(atan2(b1, a1p)) % 360 + 360) % 360
        val h2p = (Math.toDegrees(atan2(b2, a2p)) % 360 + 360) % 360
        val dLp = l2 - l1
        val dCp = c2p - c1p
        var dh = h2p - h1p
        dh = if (dh > 180) {
            dh - 360
        } else if (dh < -180) {
            dh + 360
        } else {
            dh
        }
        if (c1p * c2p == 0.0) dh = 0.0
        val dHp = 2 * sqrt(c1p * c2p) * sin(Math.toRadians(dh) / 2)
        val lpm = (l1 + l2) / 2
        val cpm = (c1p + c2p) / 2
        val hs = h1p + h2p
        var hpm = if (abs(h1p - h2p) > 180) (if (hs < 360) (hs + 360) / 2 else (hs - 360) / 2) else hs / 2
        if (c1p * c2p == 0.0) hpm = hs
        val t = 1 - 0.17 * cos(Math.toRadians(hpm - 30)) + 0.24 * cos(Math.toRadians(2 * hpm)) +
            0.32 * cos(Math.toRadians(3 * hpm + 6)) - 0.20 * cos(Math.toRadians(4 * hpm - 63))
        val dTheta = 30 * exp(-((hpm - 275) / 25).pow(2))
        val rc = 2 * sqrt(cpm.pow(7) / (cpm.pow(7) + 25.0.pow(7)))
        val sl = 1 + 0.015 * (lpm - 50).pow(2) / sqrt(20 + (lpm - 50).pow(2))
        val sc = 1 + 0.045 * cpm
        val sh = 1 + 0.015 * cpm * t
        val rt = -sin(Math.toRadians(2 * dTheta)) * rc
        val x = dLp / sl
        val y = dCp / sc
        val z = dHp / sh
        return sqrt(x * x + y * y + z * z + rt * y * z)
    }

    // ------------------------------------------------------------------------------------------- whites

    /** Planckian locus chromaticity (Kim et al. 2002 cubic spline), 1667-25000 K (clamped). */
    fun planckXy(cct: Double): DoubleArray {
        val t = cct.coerceIn(1667.0, 25000.0)
        val x = if (t <= 4000.0) {
            -0.2661239e9 / t.pow(3) - 0.2343589e6 / t.pow(2) + 0.8776956e3 / t + 0.179910
        } else {
            -3.0258469e9 / t.pow(3) + 2.1070379e6 / t.pow(2) + 0.2226347e3 / t + 0.240390
        }
        val y = when {
            t <= 2222.0 -> -1.1063814 * x.pow(3) - 1.34811020 * x.pow(2) + 2.18555832 * x - 0.20219683
            t <= 4000.0 -> -0.9549476 * x.pow(3) - 1.37418593 * x.pow(2) + 2.09137015 * x - 0.16748867
            else -> 3.0817580 * x.pow(3) - 5.87338670 * x.pow(2) + 3.75112997 * x - 0.37001483
        }
        return doubleArrayOf(x, y)
    }

    /** McCamy's approximation. */
    fun xyToCct(x: Double, y: Double): Double {
        val n = (x - 0.3320) / (0.1858 - y)
        return 449.0 * n.pow(3) + 3525.0 * n.pow(2) + 6823.3 * n + 5520.33
    }

    fun xyToXyz(x: Double, y: Double): DoubleArray = doubleArrayOf(x / y, 1.0, (1.0 - x - y) / y)

    fun warmthRetention(cct: Double, enabled: Boolean = true): Double {
        if (!enabled) return 0.0
        val f = (WARMTH_OFF_K - cct) / (WARMTH_OFF_K - WARMTH_FULL_K)
        return WARMTH_MAX * f.coerceIn(0.0, 1.0)
    }

    /** XYZ (Y = 1) the rendered white is adapted to: D65 moved towards the scene's Planckian white by the retention. */
    fun outputWhite(cct: Double, warmth: Boolean = true): Pair<DoubleArray, Double> {
        val r = warmthRetention(cct, warmth)
        if (r <= 0.0) return D65.copyOf() to r
        val xy = planckXy(cct)
        val p = xyToXyz(xy[0], xy[1])
        val w = DoubleArray(3) { (1 - r) * D65[it] + r * p[it] }
        val y = w[1]
        return DoubleArray(3) { w[it] / y } to r
    }

    fun bradford(src: DoubleArray, dst: DoubleArray): DoubleArray {
        val s = Mat3.apply(BRADFORD, src)
        val d = Mat3.apply(BRADFORD, dst)
        return Mat3.mul(BRADFORD_INV, Mat3.mul(Mat3.diag(d[0] / s[0], d[1] / s[1], d[2] / s[2]), BRADFORD))
    }

    // ------------------------------------------------------------------------------------------- DNG camera -> XYZ

    /** DNG spec: linear in inverse CCT, clamped; the weight of matrix 1. */
    fun interpolationWeight(cct: Double, cct1: Double, cct2: Double): Double {
        if (cct1 == cct2) return 1.0
        val lo = min(cct1, cct2)
        val hi = max(cct1, cct2)
        val c = cct.coerceIn(lo, hi)
        val g = (1.0 / c - 1.0 / cct2) / (1.0 / cct1 - 1.0 / cct2)
        return g.coerceIn(0.0, 1.0)
    }

    private fun blend(a: DoubleArray?, b: DoubleArray?, g: Double): DoubleArray? {
        if (a == null) return null
        if (b == null) return a
        return DoubleArray(9) { g * a[it] + (1 - g) * b[it] }
    }

    private fun xyzToCamera(cal: ColourCalibration, g: Double): DoubleArray {
        val cm = blend(cal.colorMatrix1, cal.colorMatrix2, g)!!
        val cc = blend(cal.calibration1, cal.calibration2, g) ?: Mat3.IDENTITY
        val ab = Mat3.diag(cal.analogBalance[0], cal.analogBalance[1], cal.analogBalance[2])
        return Mat3.mul(ab, Mat3.mul(cc, cm))
    }

    class WhiteSolve(val xy: DoubleArray, val cct: Double, val weight1: Double)

    /** White xy, CCT and matrix-1 weight from AsShotNeutral (iterative, DNG spec). */
    fun solveWhite(cal: ColourCalibration, neutral: DoubleArray): WhiteSolve {
        val cct1 = ILLUMINANT_CCT.getValue(cal.illuminant1)
        val cct2 = cal.illuminant2?.let { ILLUMINANT_CCT.getValue(it) } ?: cct1
        var xy = doubleArrayOf(0.3457, 0.3585)
        for (i in 0 until 30) {
            val cct = xyToCct(xy[0], xy[1])
            val g = interpolationWeight(cct, cct1, cct2)
            val xyz = Mat3.solve(xyzToCamera(cal, g), neutral)
            val s = xyz[0] + xyz[1] + xyz[2]
            val next = doubleArrayOf(xyz[0] / s, xyz[1] / s)
            val done = abs(next[0] - xy[0]) + abs(next[1] - xy[1]) < 1e-7
            xy = next
            if (done) break
        }
        val cct = xyToCct(xy[0], xy[1])
        return WhiteSolve(xy, cct, interpolationWeight(cct, cct1, cct2))
    }

    class CameraToXyz(
        val whiteXy: DoubleArray,
        val cct: Double,
        val weight1: Double,
        val cameraNeutral: DoubleArray,
        /** Camera RGB -> XYZ D50 through the forward matrices, null without them. */
        val forwardPath: DoubleArray?,
        /** Camera RGB -> XYZ D50 through the colour matrices + Bradford. */
        val colorPath: DoubleArray
    )

    fun cameraToXyz(cal: ColourCalibration, neutral: DoubleArray): CameraToXyz {
        val w = solveWhite(cal, neutral)
        val x2c = xyzToCamera(cal, w.weight1)
        val white = xyToXyz(w.xy[0], w.xy[1])
        val camN = Mat3.apply(x2c, white).let { n ->
            val m = n.max()
            DoubleArray(3) { n[it] / m }
        }
        var colorPath = Mat3.mul(bradford(white, D50), Mat3.inv(x2c))
        val scale = D50[1] / Mat3.apply(colorPath, camN)[1]
        colorPath = DoubleArray(9) { colorPath[it] * scale }
        val fm = blend(cal.forwardMatrix1, cal.forwardMatrix2, w.weight1)
        val forward = fm?.let {
            val cc = blend(cal.calibration1, cal.calibration2, w.weight1) ?: Mat3.IDENTITY
            val abcc = Mat3.mul(Mat3.diag(cal.analogBalance[0], cal.analogBalance[1], cal.analogBalance[2]), cc)
            val ref = Mat3.solve(abcc, camN)
            Mat3.mul(it, Mat3.mul(Mat3.diag(1 / ref[0], 1 / ref[1], 1 / ref[2]), Mat3.inv(abcc)))
        }
        return CameraToXyz(w.xy, w.cct, w.weight1, camN, forward, colorPath)
    }

    /** White-balanced camera RGB -> linear sRGB for one shot, and what it was solved from. */
    class ColourSolve(
        val matrix: DoubleArray,
        val neutral: DoubleArray,
        val whiteXy: DoubleArray,
        val cct: Double,
        val warmth: Double,
        val usedForward: Boolean
    )

    fun solveColour(cal: ColourCalibration, asShotNeutral: DoubleArray, warmth: Boolean = true): ColourSolve {
        require(asShotNeutral.size == 3 && asShotNeutral.all { it.isFinite() && it > 0 }) { "AsShotNeutral must be three positive values" }
        val mx = asShotNeutral.max()
        val m = cameraToXyz(cal, DoubleArray(3) { asShotNeutral[it] / mx })
        val path = m.forwardPath ?: m.colorPath
        val (wOut, r) = outputWhite(m.cct, warmth)
        val n = m.cameraNeutral
        val mat = Mat3.mul(XYZ_TO_SRGB, Mat3.mul(bradford(D50, wOut), Mat3.mul(path, Mat3.diag(n[0], n[1], n[2]))))
        return ColourSolve(mat, n, m.whiteXy, m.cct, r, m.forwardPath != null)
    }

    // ------------------------------------------------------------------------------------------- tone + exposure

    private fun log2(x: Double): Double = ln(x) / ln(2.0)

    /** numpy.interp on a uniform grid lo..hi (clamped at the ends). */
    private fun interpUniform(x: Double, lo: Double, hi: Double, values: DoubleArray): Double {
        val n = values.size
        if (x <= lo) return values[0]
        if (x >= hi) return values[n - 1]
        val f = (x - lo) / (hi - lo) * (n - 1)
        val i = floor(f).toInt().coerceIn(0, n - 2)
        val u = f - i
        return values[i] + (values[i + 1] - values[i]) * u
    }

    fun shoulder(u: Double, k: Double): Double = if (abs(k) < 1e-9) u else (1.0 - exp(-k * u)) / (1.0 - exp(-k))

    private fun slope0(k: Double): Double = if (abs(k) < 1e-9) 1.0 else k / (1.0 - exp(-k))

    /** k with shoulder'(0) = m (bisection; slope0 is increasing in k). */
    fun solveShoulderK(m: Double): Double {
        var lo = -50.0
        var hi = 50.0
        val target = m.coerceIn(slope0(lo), slope0(hi))
        repeat(200) {
            val mid = 0.5 * (lo + hi)
            if (slope0(mid) < target) lo = mid else hi = mid
        }
        return 0.5 * (lo + hi)
    }

    /** The fitted Standard tone (log2 grid EV_MIN..EV_MAX) plus the highlight shoulder for one exposure gain. */
    class ToneCurve(val tone: DoubleArray, val gain: Double, val kneeEv: Double = KNEE_EV) {
        val yKnee: Double = fitted(kneeEv)
        val slope: Double = (fitted(kneeEv + KNEE_SLOPE_DELTA) - fitted(kneeEv - KNEE_SLOPE_DELTA)) / (2 * KNEE_SLOPE_DELTA)
        val clipEv: Double = max(log2(gain), kneeEv + MIN_SHOULDER_EV)
        val k: Double = solveShoulderK(slope * (clipEv - kneeEv) / max(1.0 - yKnee, 1e-6))

        fun fitted(ev: Double): Double = interpUniform(ev, EV_MIN, EV_MAX, tone)

        /** The curve on a gained linear value. */
        fun eval(x0: Double): Double {
            val x = max(x0, 0.0)
            val floorV = 2.0.pow(EV_MIN)
            val y = if (x < floorV) {
                x / floorV * tone[0]
            } else {
                val e = log2(x)
                if (e > kneeEv) {
                    val u = ((e - kneeEv) / (clipEv - kneeEv)).coerceIn(0.0, 1.0)
                    yKnee + (1.0 - yKnee) * shoulder(u, k)
                } else {
                    fitted(e)
                }
            }
            return y.coerceIn(0.0, 1.0)
        }

        /** Samples on the log2 grid EV_MIN..clipEv handed to the native code. */
        fun table(points: Int = CURVE_POINTS): FloatArray = FloatArray(points) { i ->
            val ev = EV_MIN + (clipEv - EV_MIN) * i / (points - 1)
            eval(2.0.pow(ev)).toFloat()
        }
    }

    /** EV where the fitted tone reaches [y] (monotone envelope, as numpy.interp on a strictly increasing copy). */
    fun invertFitted(tone: DoubleArray, y: Double): Double {
        val n = tone.size
        val t = DoubleArray(n)
        var run = Double.NEGATIVE_INFINITY
        for (i in 0 until n) {
            run = max(run, tone[i])
            t[i] = run + i * 1e-9
        }
        val grid = DoubleArray(n) { EV_MIN + (EV_MAX - EV_MIN) * it / (n - 1) }
        if (y <= t[0]) return grid[0]
        if (y >= t[n - 1]) return grid[n - 1]
        var i = 0
        while (i < n - 2 && t[i + 1] < y) i++
        return grid[i] + (grid[i + 1] - grid[i]) * (y - t[i]) / (t[i + 1] - t[i])
    }

    class Exposure(val ev: Double, val method: String)

    /** Exposure gain (log2) that puts the RAW frame's median luminance where the camera JPEG's median sits. */
    fun solveExposure(tone: DoubleArray, medianY: Double, targetEncoded: Double?): Exposure {
        var method = "camera_jpeg_median"
        var target = targetEncoded
        if (target == null || !target.isFinite() || target <= 0) {
            target = DEFAULT_TARGET
            method = "default_target"
        }
        val yKnee = interpUniform(KNEE_EV, EV_MIN, EV_MAX, tone)
        val t = min(target, yKnee - 1e-3)
        if (!(medianY > 1e-6 && medianY.isFinite())) return Exposure(0.0, "no_signal")
        val ev = invertFitted(tone, t) - log2(medianY)
        return Exposure(ev.coerceIn(EXPOSURE_MIN_EV, EXPOSURE_MAX_EV), method)
    }

    /** Median luma of sRGB-encoded pixels (0..255 per channel, RGB triples), encoded. */
    fun jpegMedianEncoded(rgb: IntArray): Double {
        val n = rgb.size / 3
        require(n > 0) { "no pixels" }
        val y = DoubleArray(n) { i ->
            LUMA[0] * srgbDecode(rgb[3 * i] / 255.0) + LUMA[1] * srgbDecode(rgb[3 * i + 1] / 255.0) +
                LUMA[2] * srgbDecode(rgb[3 * i + 2] / 255.0)
        }
        y.sort()
        val med = if (n % 2 == 1) y[n / 2] else 0.5 * (y[n / 2 - 1] + y[n / 2])
        return srgbEncode(med)
    }

    // ------------------------------------------------------------------------------------------- geometry

    /** Centred 3:2 window of the zoomed field of view on the RAW frame, left/top even (= reference crop_32). */
    fun rawCrop(width: Int, height: Int, zoom: Double): IntArray {
        val z = max(zoom, 1.0)
        val zw = width / z
        val zh = height / z
        var cw: Double
        var ch: Double
        if (zw >= zh) {
            cw = zw
            ch = zw * 2.0 / 3.0
            if (ch > zh) {
                ch = zh
                cw = zh * 1.5
            }
        } else {
            ch = zh
            cw = zh * 2.0 / 3.0
            if (cw > zw) {
                cw = zw
                ch = zw * 1.5
            }
        }
        // Python round(): half to even.
        val w = Math.rint(cw).toInt() / 2 * 2
        val h = Math.rint(ch).toInt() / 2 * 2
        val left = (width - w) / 2 / 2 * 2
        val top = (height - h) / 2 / 2 * 2
        return intArrayOf(left, top, w, h)
    }
}

/** Row-major 3x3 helpers. */
object Mat3 {
    val IDENTITY = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    fun diag(a: Double, b: Double, c: Double) = doubleArrayOf(a, 0.0, 0.0, 0.0, b, 0.0, 0.0, 0.0, c)

    fun mul(a: DoubleArray, b: DoubleArray): DoubleArray = DoubleArray(9) { i ->
        val r = i / 3
        val c = i % 3
        a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
    }

    fun apply(m: DoubleArray, v: DoubleArray): DoubleArray = DoubleArray(3) { r ->
        m[r * 3] * v[0] + m[r * 3 + 1] * v[1] + m[r * 3 + 2] * v[2]
    }

    fun det(m: DoubleArray): Double =
        m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])

    fun inv(m: DoubleArray): DoubleArray {
        val d = det(m)
        require(abs(d) > 1e-12) { "singular matrix" }
        return doubleArrayOf(
            (m[4] * m[8] - m[5] * m[7]) / d, (m[2] * m[7] - m[1] * m[8]) / d, (m[1] * m[5] - m[2] * m[4]) / d,
            (m[5] * m[6] - m[3] * m[8]) / d, (m[0] * m[8] - m[2] * m[6]) / d, (m[2] * m[3] - m[0] * m[5]) / d,
            (m[3] * m[7] - m[4] * m[6]) / d, (m[1] * m[6] - m[0] * m[7]) / d, (m[0] * m[4] - m[1] * m[3]) / d
        )
    }

    fun solve(m: DoubleArray, v: DoubleArray): DoubleArray = apply(inv(m), v)
}
