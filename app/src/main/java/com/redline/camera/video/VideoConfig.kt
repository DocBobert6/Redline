package com.redline.camera.video

import com.redline.camera.capability.Dim
import com.redline.camera.colour.ColourMath
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * B40 video configuration, pure so it is unit-testable: which stream sizes to try, the encoder bitrate, the ISP tonemap
 * (a flat curve the GPU chain inverts), the exposure gain onto the Standard tone, and how the recorded frame is rotated.
 */
object VideoSizes {
    val UHD = Dim(3840, 2160)
    val FHD = Dim(1920, 1080)
    private val MIN = Dim(1280, 720)

    fun isSixteenNine(d: Dim): Boolean = d.width * 9 == d.height * 16

    /**
     * 16:9 PRIVATE sizes to try for a [fps] recording, best first: 3840x2160 when offered, then the other 16:9 sizes
     * down to 1280x720 by area. A size whose minimum frame duration (from the stream map; null = not reported) is
     * longer than one frame at [fps] is left out. Empty when no fixed [fps, fps] AE range is listed.
     */
    fun candidates(sizes: List<Dim>, fpsRanges: List<Pair<Int, Int>>, fps: Int, minFrameNs: (Dim) -> Long?): List<Dim> {
        if (fpsRanges.none { it.first == fps && it.second == fps }) return emptyList()
        val frameNs = 1_000_000_000L / fps
        return sizes.asSequence()
            .filter { isSixteenNine(it) && it.width <= UHD.width && it.area >= MIN.area }
            .filter { (minFrameNs(it) ?: 0L) <= frameNs + FRAME_SLACK_NS }
            .distinct()
            .sortedByDescending { it.area }
            .toList()
    }

    /** The upright frame's size for the GPU intermediate: width and height swap when the sensor is turned 90/270. */
    fun upright(size: Dim, sensorOrientation: Int): Dim = if (sensorOrientation % 180 != 0) Dim(size.height, size.width) else size

    // Stream maps round 1/30 s; allow 0.1 ms.
    private const val FRAME_SLACK_NS = 100_000L
}

object VideoBitrate {
    /** 4K30 HEVC target (PLAN 8.1 says ~50-80 Mbps; B40 brief ~60-100): 80 Mbps at 3840x2160x30, scaled by pixel rate. */
    const val UHD30_BPS = 80_000_000
    const val MIN_BPS = 8_000_000

    /** Target bitrate for [size] at [fps], clamped to the encoder's [range] (bits/s) when it reports one. */
    fun choose(size: Dim, fps: Int, range: Pair<Int, Int>?): Int {
        val rate = size.area.toDouble() * fps / (VideoSizes.UHD.area.toDouble() * 30)
        var bps = max(MIN_BPS.toDouble(), UHD30_BPS * rate).roundToLong().toInt()
        if (range != null) bps = bps.coerceIn(range.first, max(range.first, range.second))
        return bps
    }
}

/** Camera2 TONEMAP_MODE values (CameraMetadata), here so the selection is testable without android.jar. */
object TonemapModes {
    const val CONTRAST_CURVE = 0
    const val FAST = 1
    const val HIGH_QUALITY = 2
    const val GAMMA_VALUE = 3
    const val PRESET_CURVE = 4
    const val PRESET_SRGB = 0

    fun name(mode: Int): String = when (mode) {
        CONTRAST_CURVE -> "CONTRAST_CURVE"
        FAST -> "FAST"
        HIGH_QUALITY -> "HIGH_QUALITY"
        GAMMA_VALUE -> "GAMMA_VALUE"
        PRESET_CURVE -> "PRESET_CURVE"
        else -> "UNKNOWN($mode)"
    }
}

/**
 * The flat ISP curve (PLAN 8.1: a log-like CONTRAST_CURVE keeps highlights and shadows inside the 8-bit stream):
 * y = log2(1 + K x) / log2(1 + K). The ISP interpolates linearly between the points it is given, so the GPU inverts that
 * piecewise-linear curve exactly ([inverseTable]), not the analytic one.
 */
object FlatCurve {
    const val K = 40.0
    const val NAME = "redline_flat_log40"
    const val POINTS_WANTED = 64
    const val INVERSE_SIZE = 1024

    fun forward(x: Double): Double = ln(1 + K * x.coerceIn(0.0, 1.0)) / ln(1 + K)

    /** [n] (Pin, Pout) pairs, Pin from 0 to 1, denser in the shadows (square spacing), as TonemapCurve wants them. */
    fun points(n: Int): FloatArray {
        require(n >= 2) { "a curve needs at least 2 points" }
        val out = FloatArray(n * 2)
        for (i in 0 until n) {
            val x = (i.toDouble() / (n - 1)).pow(2.0)
            out[2 * i] = x.toFloat()
            out[2 * i + 1] = forward(x).toFloat()
        }
        return out
    }

    /** The ISP's view of a (Pin, Pout) list: linear interpolation, clamped at the ends. */
    fun evalPoints(points: FloatArray, x: Double): Double {
        val n = points.size / 2
        if (x <= points[0]) return points[1].toDouble()
        for (i in 1 until n) {
            val x1 = points[2 * i].toDouble()
            if (x <= x1) {
                val x0 = points[2 * i - 2].toDouble()
                val y0 = points[2 * i - 1].toDouble()
                val y1 = points[2 * i + 1].toDouble()
                return if (x1 > x0) y0 + (y1 - y0) * (x - x0) / (x1 - x0) else y1
            }
        }
        return points[2 * n - 1].toDouble()
    }

    /** Linear value for each of [size] encoded values 0..1 through the inverse of the piecewise-linear [points]. */
    fun inverseTable(points: FloatArray, size: Int = INVERSE_SIZE): FloatArray {
        val n = points.size / 2
        return FloatArray(size) { j ->
            val y = j.toDouble() / (size - 1)
            var x = 1.0
            if (y <= points[1]) {
                x = points[0].toDouble()
            } else {
                for (i in 1 until n) {
                    val y1 = points[2 * i + 1].toDouble()
                    if (y <= y1) {
                        val y0 = points[2 * i - 1].toDouble()
                        val x0 = points[2 * i - 2].toDouble()
                        val x1 = points[2 * i].toDouble()
                        x = if (y1 > y0) x0 + (x1 - x0) * (y - y0) / (y1 - y0) else x1
                        break
                    }
                }
            }
            x.toFloat()
        }
    }
}

/** What the ISP tonemap is set to for video and how the GPU undoes it. */
data class TonemapPlan(
    /** TonemapModes value set on the request, or null to leave the ISP default (FAST / HIGH_QUALITY) alone. */
    val mode: Int?,
    val label: String,
    /** CONTRAST_CURVE (Pin, Pout) pairs, same curve on R, G, B. */
    val curve: FloatArray? = null,
    val gamma: Float? = null,
    val preset: Int? = null,
    /** Encoded 0..1 -> linear, [FlatCurve.INVERSE_SIZE] entries, for the GPU's first pass. */
    val inverse: FloatArray,
    /** True only for the curve Redline set and inverts exactly; the fallbacks are recorded as such. */
    val exact: Boolean,
    val note: String
) {
    val modeName: String get() = mode?.let { TonemapModes.name(it) } ?: "isp_default"

    override fun equals(other: Any?): Boolean = other is TonemapPlan && other.label == label && other.mode == mode

    override fun hashCode(): Int = label.hashCode() * 31 + (mode ?: -1)

    companion object {
        const val GAMMA = 2.2f

        /**
         * B40 order: CONTRAST_CURVE with the flat curve (at most [FlatCurve.POINTS_WANTED] points, never more than the
         * camera's TONEMAP_MAX_CURVE_POINTS) -> GAMMA_VALUE 2.2 -> PRESET_CURVE sRGB -> the ISP default curve, whose
         * inverse is only approximated by the sRGB decode.
         */
        fun choose(availableModes: List<Int>, maxCurvePoints: Int?): TonemapPlan {
            val pts = min(FlatCurve.POINTS_WANTED, maxCurvePoints ?: 0)
            if (TonemapModes.CONTRAST_CURVE in availableModes && pts >= 2) {
                val curve = FlatCurve.points(pts)
                return TonemapPlan(
                    TonemapModes.CONTRAST_CURVE,
                    FlatCurve.NAME,
                    curve = curve,
                    inverse = FlatCurve.inverseTable(curve),
                    exact = true,
                    note = "flat log curve, $pts points (max ${maxCurvePoints ?: "unreported"}), inverted on the GPU"
                )
            }
            val why = when {
                TonemapModes.CONTRAST_CURVE !in availableModes -> "CONTRAST_CURVE not listed"
                else -> "TONEMAP_MAX_CURVE_POINTS ${maxCurvePoints ?: "unreported"} < 2"
            }
            if (TonemapModes.GAMMA_VALUE in availableModes) {
                val inv =
                    FloatArray(FlatCurve.INVERSE_SIZE) { (it.toDouble() / (FlatCurve.INVERSE_SIZE - 1)).pow(GAMMA.toDouble()).toFloat() }
                return TonemapPlan(
                    TonemapModes.GAMMA_VALUE,
                    "gamma_$GAMMA",
                    gamma = GAMMA,
                    inverse = inv,
                    exact = true,
                    note = "$why; gamma $GAMMA"
                )
            }
            val srgb = FloatArray(FlatCurve.INVERSE_SIZE) { ColourMath.srgbDecode(it.toDouble() / (FlatCurve.INVERSE_SIZE - 1)).toFloat() }
            if (TonemapModes.PRESET_CURVE in availableModes) {
                return TonemapPlan(
                    TonemapModes.PRESET_CURVE,
                    "preset_srgb",
                    preset = TonemapModes.PRESET_SRGB,
                    inverse = srgb,
                    exact = true,
                    note = "$why; GAMMA_VALUE not listed; sRGB preset"
                )
            }
            return TonemapPlan(
                null,
                "isp_default",
                inverse = srgb,
                exact = false,
                note = "$why; no GAMMA_VALUE / PRESET_CURVE: ISP default curve, approximate sRGB inverse " +
                    "(look will not match stills exactly)"
            )
        }
    }
}

/**
 * Exposure onto the Standard tone: the ISP's auto exposure still meters the scene (AE runs before the tonemap), so a
 * mid-grey reaches the GPU at about [ISP_MID_GREY] linear; it is gained so it lands where the stills put a mid-tone
 * ([ColourMath.DEFAULT_TARGET] encoded on the fitted tone). One gain per session; B41 may meter per frame.
 */
object VideoExposure {
    const val ISP_MID_GREY = 0.18
    const val METHOD = "isp_ae_mid_grey_0.18_to_standard_0.46"

    fun gainEv(tone: DoubleArray): Double {
        val t = ColourMath.ToneCurve(tone, 1.0)
        val target = min(ColourMath.DEFAULT_TARGET, t.yKnee - 1e-3)
        val ev = ColourMath.invertFitted(tone, target) - ln(ISP_MID_GREY) / ln(2.0)
        return ev.coerceIn(ColourMath.EXPOSURE_MIN_EV, ColourMath.EXPOSURE_MAX_EV)
    }
}

/**
 * How the recorded frame is turned. The GPU's intermediate holds the picture upright for the phone's natural
 * orientation; the encoded frame is always landscape-shaped for the stream size (so the encoder size is the camera size),
 * rotated clockwise by [contentRotation], and the MP4 orientation hint [hint] (clockwise, applied by players) makes
 * up the rest of the device's clockwise rotation from natural (contentRotation + hint = device rotation).
 */
data class VideoOrientation(val contentRotation: Int, val hint: Int) {
    companion object {
        fun snap(deviceDegrees: Int): Int = ((((deviceDegrees % 360) + 360) % 360 + 45) / 90 * 90) % 360

        /** [uprightPortrait]: the natural-orientation picture is taller than wide (a phone's back camera). */
        fun plan(uprightPortrait: Boolean, deviceDegrees: Int): VideoOrientation {
            val d = snap(deviceDegrees)
            val a = if (uprightPortrait) {
                if (d == 90 || d == 270) d else 90
            } else {
                if (d == 0 || d == 180) d else 0
            }
            return VideoOrientation(a, ((d - a) % 360 + 360) % 360)
        }
    }
}

/**
 * CPU reference of the GPU video chain (same steps and the same table interpolation), for the GPU start-up self-check
 * and the unit tests: encoded ISP value -> inverse of the ISP curve -> colour matrix -> exposure gain -> Standard tone
 * (log2 grid, [ColourMath.ToneCurve]) -> the stills' 33^3 residual LUT ([com.redline.camera.look.LutMath.sample]).
 */
class VideoChain(
    val inverse: FloatArray,
    val matrix: FloatArray,
    val gain: Double,
    tone: DoubleArray,
    val lut: com.redline.camera.look.CubeLut
) {
    val curve = ColourMath.ToneCurve(tone, gain)
    val toneTable: FloatArray = curve.table(TONE_SIZE)
    val clipEv: Double = curve.clipEv
    val floorLinear: Double = 2.0.pow(ColourMath.EV_MIN)

    /** Linear lookup in a table spanning 0..1, as the GPU's half-texel-corrected linear filter does it. */
    fun table(t: FloatArray, u: Double): Double {
        val x = u.coerceIn(0.0, 1.0) * (t.size - 1)
        val i = x.toInt().coerceIn(0, t.size - 2)
        val f = x - i
        return t[i] + (t[i + 1] - t[i]) * f
    }

    fun linear(e: FloatArray): DoubleArray {
        val l = DoubleArray(3) { table(inverse, e[it].toDouble()) }
        return DoubleArray(3) { r -> matrix[r * 3] * l[0] + matrix[r * 3 + 1] * l[1] + matrix[r * 3 + 2] * l[2] }
    }

    fun tone(lin: Double): Double {
        val x = max(lin * gain, 0.0)
        if (x < floorLinear) return x / floorLinear * toneTable[0]
        val ev = ln(x) / ln(2.0)
        return table(toneTable, (ev - ColourMath.EV_MIN) / (clipEv - ColourMath.EV_MIN))
    }

    fun apply(r: Float, g: Float, b: Float): FloatArray {
        val lin = linear(floatArrayOf(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f)))
        val toned = FloatArray(3) { tone(lin[it]).toFloat() }
        val out = com.redline.camera.look.LutMath.sample(lut, toned[0], toned[1], toned[2])
        return FloatArray(3) { out[it].coerceIn(0f, 1f) }
    }

    /** Worst per-channel error in 8-bit steps between GPU RGBA8 [pixels] and the reference for [inputs] (RGB bytes). */
    fun maxErrorSteps(inputs: IntArray, pixels: ByteArray): Int {
        var worst = 0
        for (i in 0 until inputs.size / 3) {
            val ref = apply(inputs[i * 3] / 255f, inputs[i * 3 + 1] / 255f, inputs[i * 3 + 2] / 255f)
            for (c in 0..2) worst = max(worst, kotlin.math.abs((pixels[i * 4 + c].toInt() and 0xFF) - (ref[c] * 255f).roundToInt()))
        }
        return worst
    }

    companion object {
        const val TONE_SIZE = 1024
        val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    }
}

/** One encoder as MediaCodecList describes it, for [EncoderPick]. */
data class EncoderCandidate(
    val name: String,
    val mime: String,
    val hardware: Boolean,
    /** VideoCapabilities.areSizeAndRateSupported(width, height, fps) for the session size. */
    val sizeAndRateSupported: Boolean,
    val bitrateRange: Pair<Int, Int>?
)

object EncoderPick {
    const val HEVC = "video/hevc"
    const val AVC = "video/avc"

    /**
     * B40: HEVC first (hardware before software), then AVC as a recorded fallback, each only where the encoder says it
     * takes the session size at the session frame rate. Null when nothing does (video is then unavailable at that size).
     */
    fun choose(candidates: List<EncoderCandidate>): EncoderCandidate? {
        val usable = candidates.filter { it.sizeAndRateSupported }
        for (mime in listOf(HEVC, AVC)) {
            usable.filter { it.mime == mime }.sortedByDescending { it.hardware }.firstOrNull()?.let { return it }
        }
        return null
    }
}

/** The record timer: m:ss under an hour, h:mm:ss after. */
object VideoTimer {
    fun label(elapsedMs: Long): String {
        val s = (elapsedMs.coerceAtLeast(0L) / 1000)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(java.util.Locale.US, h, m, sec) else "%02d:%02d".format(java.util.Locale.US, m, sec)
    }
}
