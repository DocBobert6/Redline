package com.redline.camera.manual

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** A requested value and the value it became after clamping to a reported range. */
data class Clamped<T>(val requested: T, val value: T) {
    val clamped: Boolean get() = requested != value
}

/** One EV choice: the CONTROL_AE_EXPOSURE_COMPENSATION index and the exposure change it really gives. */
data class EvStop(val index: Int, val ev: Double)

/** What the repeating preview request uses in manual exposure (the still may be longer). */
data class PreviewExposure(
    val exposureNs: Long,
    val iso: Int,
    val frameDurationNs: Long,
    /** True when the preview exposure is shorter than the still's and ISO was raised to make up for it. */
    val compensated: Boolean,
    /** Stops by which the preview is darker than the still because ISO hit its ceiling (0 when fully made up). */
    val shortfallEv: Double
)

/**
 * Pure range, clamp and step math for B14's manual controls. Every ladder is cut to the ranges the camera
 * reports (SENSOR_INFO_SENSITIVITY_RANGE, SENSOR_INFO_EXPOSURE_TIME_RANGE capped at
 * SENSOR_INFO_MAX_FRAME_DURATION, LENS_INFO_MINIMUM_FOCUS_DISTANCE, CONTROL_AE_COMPENSATION_RANGE x STEP),
 * so a control can never offer a value outside them.
 */
object ManualMath {
    const val NS_PER_S = 1_000_000_000L

    /** The preview shutter is held at 1/30 s or shorter so the 30 fps preview (B12) is not slowed down. */
    const val PREVIEW_MAX_EXPOSURE_NS = 33_333_333L
    const val PREVIEW_FRAME_DURATION_NS = 33_333_333L

    /** EV +/-4 in 1/3 steps (PLAN 11). */
    const val EV_MAX_THIRDS = 12
    const val EV_MAX = 4.0

    /** Below this share of g in the screen plane the phone is lying flat and the level is hidden. */
    const val LEVEL_FLAT_FRACTION = 0.35f
    const val LEVEL_TOLERANCE_DEG = 1f

    /** Nominal third-stop ISO values. */
    val ISO_STOPS = listOf(
        12, 16, 20, 25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600,
        2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800, 16000, 20000, 25600, 32000, 40000, 51200,
        64000, 80000, 102400
    )

    /** Nominal third-stop shutter speeds as seconds (numerator to denominator), fastest first. */
    private val SHUTTER_STOPS: List<Pair<Int, Int>> = listOf(
        1 to 16000, 1 to 12800, 1 to 10000, 1 to 8000, 1 to 6400, 1 to 5000, 1 to 4000, 1 to 3200, 1 to 2500,
        1 to 2000, 1 to 1600, 1 to 1250, 1 to 1000, 1 to 800, 1 to 640, 1 to 500, 1 to 400, 1 to 320, 1 to 250,
        1 to 200, 1 to 160, 1 to 125, 1 to 100, 1 to 80, 1 to 60, 1 to 50, 1 to 40, 1 to 30, 1 to 25, 1 to 20,
        1 to 15, 1 to 13, 1 to 10, 1 to 8, 1 to 6, 1 to 5, 1 to 4, 3 to 10, 4 to 10, 5 to 10, 6 to 10, 8 to 10,
        1 to 1, 13 to 10, 16 to 10, 2 to 1, 25 to 10, 32 to 10, 4 to 1, 5 to 1, 6 to 1, 8 to 1, 10 to 1,
        13 to 1, 15 to 1, 20 to 1, 25 to 1, 30 to 1
    )

    val SHUTTER_STOPS_NS: List<Long> = SHUTTER_STOPS.map { (n, d) -> n * NS_PER_S / d }

    /** Focus stops in diopters (0 = infinity); cut to the lens's minimum focus distance. */
    val FOCUS_STOPS_DIOPTERS = listOf(0f, 0.2f, 0.5f, 1f, 1.5f, 2f, 3f, 4f, 5f, 6f, 8f, 10f, 12.5f, 15f, 20f)

    private fun <T : Comparable<T>> ordered(r: Pair<T, T>): Pair<T, T> = if (r.first <= r.second) r else r.second to r.first

    // ISO

    /** Third-stop ISO values inside [range]; the range ends themselves when no nominal value fits. */
    fun isoLadder(range: Pair<Int, Int>): List<Int> {
        val (lo, hi) = ordered(range)
        return ISO_STOPS.filter { it in lo..hi }.ifEmpty { listOf(lo, hi).distinct() }
    }

    fun clampIso(range: Pair<Int, Int>, iso: Int): Clamped<Int> {
        val (lo, hi) = ordered(range)
        return Clamped(iso, iso.coerceIn(lo, hi))
    }

    // Shutter

    /** Longest usable exposure: the reported maximum, but never longer than SENSOR_INFO_MAX_FRAME_DURATION. */
    fun shutterCeilingNs(range: Pair<Long, Long>, maxFrameDurationNs: Long?): Long {
        val (lo, hi) = ordered(range)
        val cap = if (maxFrameDurationNs != null && maxFrameDurationNs > 0) min(hi, maxFrameDurationNs) else hi
        return max(lo, cap)
    }

    /** Third-stop shutter speeds inside the exposure range (capped at the max frame duration), fastest first. */
    fun shutterLadder(range: Pair<Long, Long>, maxFrameDurationNs: Long?): List<Long> {
        val lo = ordered(range).first
        val hi = shutterCeilingNs(range, maxFrameDurationNs)
        return SHUTTER_STOPS_NS.filter { it in lo..hi }.ifEmpty { listOf(lo, hi).distinct() }
    }

    fun clampExposure(range: Pair<Long, Long>, maxFrameDurationNs: Long?, ns: Long): Clamped<Long> {
        val lo = ordered(range).first
        return Clamped(ns, ns.coerceIn(lo, shutterCeilingNs(range, maxFrameDurationNs)))
    }

    /**
     * Preview settings for a manual still exposure: the preview shutter is limited to 1/30 s (and the frame
     * duration kept at 1/30 s) so the preview stays at 30 fps; ISO is raised by the same ratio, within its range,
     * so the preview brightness still matches the still.
     */
    fun previewExposure(
        stillExposureNs: Long,
        stillIso: Int,
        isoRange: Pair<Int, Int>,
        exposureRange: Pair<Long, Long>,
        maxFrameDurationNs: Long?
    ): PreviewExposure {
        val lo = ordered(exposureRange).first
        val ceiling = shutterCeilingNs(exposureRange, maxFrameDurationNs)
        val cap = min(PREVIEW_MAX_EXPOSURE_NS, ceiling).coerceAtLeast(lo)
        val exposure = stillExposureNs.coerceIn(lo, cap)
        val wantedIso = stillIso.toDouble() * stillExposureNs / exposure
        val (isoLo, isoHi) = ordered(isoRange)
        val iso = wantedIso.roundToLong().coerceIn(isoLo.toLong(), isoHi.toLong()).toInt()
        val frame = max(exposure, min(PREVIEW_FRAME_DURATION_NS, ceiling))
        val shortfall = if (iso > 0 && wantedIso > isoHi) log2(wantedIso / iso) else 0.0
        return PreviewExposure(exposure, iso, frame, exposure < stillExposureNs, shortfall)
    }

    /** Frame duration for a manual still: long enough for the exposure, never past the max frame duration. */
    fun stillFrameDurationNs(exposureNs: Long, maxFrameDurationNs: Long?): Long {
        val hasMax = maxFrameDurationNs != null && maxFrameDurationNs > 0
        val base = if (hasMax) min(PREVIEW_FRAME_DURATION_NS, maxFrameDurationNs!!) else PREVIEW_FRAME_DURATION_NS
        return max(exposureNs, base)
    }

    // EV

    fun evAvailable(range: Pair<Int, Int>?, step: Float?): Boolean =
        range != null && step != null && step > 0f && step.isFinite() && range.first < range.second

    /** Largest |index| that stays within +/-4 EV for this step. */
    private fun evIndexLimit(step: Float): Int = ((EV_MAX + 1e-6) / step).toInt()

    /**
     * EV choices in 1/3 steps within +/-4 EV that the camera can express, low to high. Each third is rounded to
     * the nearest compensation index; thirds that land outside CONTROL_AE_COMPENSATION_RANGE are dropped, and two
     * thirds that round to the same index give one choice.
     */
    fun evLadder(range: Pair<Int, Int>?, step: Float?): List<EvStop> {
        if (!evAvailable(range, step)) return emptyList()
        val (lo, hi) = ordered(range!!)
        val limit = evIndexLimit(step!!)
        return (-EV_MAX_THIRDS..EV_MAX_THIRDS)
            .map { t -> (t / 3.0 / step).roundToInt() }
            .filter { it in lo..hi && abs(it) <= limit }
            .distinct()
            .sorted()
            .map { EvStop(it, it * step.toDouble()) }
    }

    /** Clamps a compensation index to the reported range and to +/-4 EV. */
    fun clampEvIndex(range: Pair<Int, Int>?, step: Float?, index: Int): Clamped<Int> {
        if (!evAvailable(range, step)) return Clamped(index, 0)
        val (lo, hi) = ordered(range!!)
        val limit = evIndexLimit(step!!)
        return Clamped(index, index.coerceIn(max(lo, -limit), min(hi, limit)))
    }

    fun evIndexForThirds(thirds: Int, step: Float): Int = (thirds / 3.0 / step).roundToInt()

    fun evLabel(ev: Double): String {
        val r = (ev * 10).roundToInt() / 10.0
        return when {
            r == 0.0 -> "0.0"
            r > 0 -> "+%.1f".format(r)
            else -> "%.1f".format(r)
        }
    }

    // Focus

    /** Manual focus is offered only when the lens reports a minimum focus distance above 0 diopters. */
    fun manualFocusAvailable(minFocusDiopters: Float?): Boolean =
        minFocusDiopters != null && minFocusDiopters.isFinite() && minFocusDiopters > 0f

    /** Diopter stops from infinity (0) to the minimum focus distance, which is always the last stop. */
    fun focusLadder(minFocusDiopters: Float?): List<Float> {
        if (!manualFocusAvailable(minFocusDiopters)) return emptyList()
        val m = minFocusDiopters!!
        return FOCUS_STOPS_DIOPTERS.filter { it < m - 1e-3f } + m
    }

    fun clampDiopters(minFocusDiopters: Float?, diopters: Float): Clamped<Float> {
        if (!manualFocusAvailable(minFocusDiopters)) return Clamped(diopters, 0f)
        return Clamped(diopters, diopters.coerceIn(0f, minFocusDiopters!!))
    }

    /**
     * Focus label. With a calibrated or approximate lens, diopters are 1/metres; an uncalibrated lens only
     * promises 0 = infinity and the minimum distance = closest, so it gets a share of the range instead.
     */
    fun focusLabel(diopters: Float, minFocusDiopters: Float?, metric: Boolean): String {
        if (diopters <= 0f) return "∞"
        if (!metric) {
            val m = minFocusDiopters ?: return "%.1f D".format(diopters)
            return "${(100 * diopters / m).roundToInt()}% near"
        }
        val metres = 1f / diopters
        return if (metres >= 1f) "%.1f m".format(metres) else "${(metres * 100).roundToInt()} cm"
    }

    // Stepping

    /** Index of the ladder entry nearest [value] on a log scale (stops, not linear distance). */
    fun nearestIndex(ladder: List<Double>, value: Double): Int {
        if (ladder.isEmpty()) return -1
        if (value <= 0.0) return ladder.indices.minByOrNull { ladder[it] } ?: 0
        return ladder.indices.minByOrNull { i -> if (ladder[i] <= 0.0) Double.MAX_VALUE else abs(ln(ladder[i] / value)) } ?: 0
    }

    /** The ladder value [dir] steps from the entry nearest [current]; stays at the ends. */
    fun stepIso(ladder: List<Int>, current: Int, dir: Int): Int {
        if (ladder.isEmpty()) return current
        val i = nearestIndex(ladder.map { it.toDouble() }, current.toDouble())
        return ladder[(i + dir).coerceIn(0, ladder.lastIndex)]
    }

    fun stepShutter(ladder: List<Long>, current: Long, dir: Int): Long {
        if (ladder.isEmpty()) return current
        val i = nearestIndex(ladder.map { it.toDouble() }, current.toDouble())
        return ladder[(i + dir).coerceIn(0, ladder.lastIndex)]
    }

    fun snapIso(ladder: List<Int>, value: Int): Int = if (ladder.isEmpty()) value else stepIso(ladder, value, 0)

    fun snapShutter(ladder: List<Long>, value: Long): Long = if (ladder.isEmpty()) value else stepShutter(ladder, value, 0)

    fun stepEv(ladder: List<EvStop>, currentIndex: Int, dir: Int): Int {
        if (ladder.isEmpty()) return 0
        val i = ladder.indices.minByOrNull { abs(ladder[it].index - currentIndex) } ?: 0
        return ladder[(i + dir).coerceIn(0, ladder.lastIndex)].index
    }

    /** Focus steps: AF (null), then infinity, ..., the closest distance. */
    fun stepFocus(ladder: List<Float>, current: Float?, dir: Int): Float? {
        if (ladder.isEmpty()) return null
        val i = if (current == null) 0 else 1 + (ladder.indices.minByOrNull { abs(ladder[it] - current) } ?: 0)
        val next = (i + dir).coerceIn(0, ladder.size)
        return if (next == 0) null else ladder[next - 1]
    }

    fun shutterLabel(ns: Long): String {
        val stop = SHUTTER_STOPS.indices.firstOrNull { SHUTTER_STOPS_NS[it] == ns }
        if (stop != null) {
            val (n, d) = SHUTTER_STOPS[stop]
            return when {
                n == 1 && d > 1 -> "1/$d"
                d == 1 -> "${n}s"
                else -> "%.1fs".format(n.toDouble() / d)
            }
        }
        val s = ns.toDouble() / NS_PER_S
        return if (s < 0.3) "1/${(1 / s).roundToInt()}" else "%.1fs".format(s)
    }

    // Histogram

    /** Rec. 601 luma (0..255) of an ARGB pixel. */
    fun luma(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (77 * r + 150 * g + 29 * b) shr 8
    }

    /** Bins 0..255 luma values into [bins] equal buckets. */
    fun histogram(luma: IntArray, bins: Int = DEFAULT_BINS): IntArray {
        require(bins in 1..256)
        val out = IntArray(bins)
        for (v in luma) out[v.coerceIn(0, 255) * bins / 256]++
        return out
    }

    /** Luma histogram of the first [count] ARGB pixels of a downscaled preview sample. */
    fun histogramFromArgb(pixels: IntArray, bins: Int = DEFAULT_BINS, count: Int = pixels.size): IntArray {
        require(bins in 1..256)
        val out = IntArray(bins)
        val n = count.coerceIn(0, pixels.size)
        for (i in 0 until n) out[luma(pixels[i]) * bins / 256]++
        return out
    }

    /** Share of samples in the lowest and highest bins (crushed shadows, clipped highlights). */
    fun clipFractions(hist: IntArray): Pair<Double, Double> {
        val total = hist.sum()
        if (total == 0 || hist.isEmpty()) return 0.0 to 0.0
        return hist.first().toDouble() / total to hist.last().toDouble() / total
    }

    const val DEFAULT_BINS = 64

    // Level

    /**
     * Horizon tilt for the overlay, in degrees, clockwise-positive as Compose rotate() expects: the line drawn at
     * this angle stays parallel to the real horizon. Input is the up vector in device axes (accelerometer at rest,
     * or the third row of the rotation-vector matrix) and the display rotation in degrees. Null when the phone is
     * close to flat, where the horizon is undefined.
     */
    fun horizonTiltDegrees(gx: Float, gy: Float, gz: Float, displayRotationDeg: Int): Float? {
        val (sx, sy) = when (((displayRotationDeg % 360) + 360) % 360) {
            90 -> -gy to gx
            180 -> -gx to -gy
            270 -> gy to -gx
            else -> gx to gy
        }
        val norm = sqrt(gx * gx + gy * gy + gz * gz)
        if (norm <= 0f || hypot(sx, sy) < LEVEL_FLAT_FRACTION * norm) return null
        return Math.toDegrees(atan2(sx.toDouble(), sy.toDouble())).toFloat()
    }

    fun isLevel(tiltDeg: Float?, tolerance: Float = LEVEL_TOLERANCE_DEG): Boolean = tiltDeg != null && abs(tiltDeg) <= tolerance
}
