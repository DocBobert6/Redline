package com.redline.camera.dof

import com.redline.camera.colour.ColourMath
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max

/**
 * B31: the simulated depth-of-field maths around the bokeh core (pure JVM, host-tested): the aperture dial, the focus
 * distance from the capture result, the phone's full-frame-equivalent f-number for the crop being rendered, and the focus
 * point in the still.
 */
object DofMath {
    /** The dial: f/2.8 to f/22 in 1/3 stops (the marked values of a full-frame zoom). */
    val APERTURES = listOf(2.8f, 3.2f, 3.5f, 4f, 4.5f, 5f, 5.6f, 6.3f, 7.1f, 8f, 9f, 10f, 11f, 13f, 14f, 16f, 18f, 20f, 22f)
    const val DEFAULT_APERTURE = 2.8f

    /** Full-frame width (mm) and the circle of confusion the hyperfocal default uses (0.03 mm, the full-frame convention). */
    const val FF_WIDTH_MM = 36.0
    const val COC_FF_MM = 0.03

    /** Closest focus distance accepted from the AF (a phone reports up to ~10-20 diopters at macro). */
    const val MIN_FOCUS_M = 0.08

    /** CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED (diopters not in physical units). */
    const val CALIBRATION_UNCALIBRATED = 0

    const val SOURCE_AF = "af"
    const val SOURCE_AF_UNCALIBRATED = "af_uncalibrated"
    const val SOURCE_HYPERFOCAL = "hyperfocal_default"

    const val POINT_TAP = "tap"
    const val POINT_PERSON = "person_centroid"
    const val POINT_CENTRE = "centre"

    /** B40: a tap just beside a person while the AF distance matches the person: the person region is the focus plane. */
    const val POINT_TAP_SNAPPED = "tap_snapped_person"

    /** B40: a tap older than the current AF scene, not reused: the person region instead. */
    const val POINT_TAP_STALE_PERSON = "tap_stale_person"

    /** B40: a person region whose nearest pixel is within this share of the frame diagonal of the tap can take it. */
    const val SNAP_RADIUS_DIAG = 0.06

    /** B40: the person's anchored depth is the median depth of its region's pixels within this share of the diagonal. */
    const val SNAP_ANCHOR_DIAG = 0.12

    /** B40: a region smaller than this share of the frame is mask noise, never a snap target. */
    const val SNAP_MIN_REGION = 0.002

    /**
     * B40: the nominal depth scale for the AF comparison: diopters at the frame's nearest depth (the far end, the 2 %
     * percentile, is infinity, as in the bokeh's anchoring). The relative depth has no metric scale of its own, so the
     * comparison is in log diopters, where the scale only shifts both candidates alike except near the far end.
     */
    const val SNAP_NEAR_DIOPTERS = 1.0

    /** B40: the floor (diopters, 50 m) of the comparison, so the far end and an infinity AF reading stay finite. */
    const val SNAP_FLOOR_DIOPTERS = 0.02

    /** The bokeh's far anchor percentile and focus window (bokeh.cpp kBgPct, kFocusWin). */
    const val BG_PCT = 0.02
    const val FOCUS_WIN = 0.02

    /** B40: a plain tap older than this at the press is stale for DOF (the AF scene has moved on). */
    const val TAP_STALE_MS = 8000L

    /** B40: a plain tap is stale once the camera has panned this share of the scene-change reset threshold. */
    const val TAP_STALE_PAN_SHARE = 0.5

    /** B40: at most this many candidate pixels (nearest first) get the focus-window check. */
    const val SNAP_MAX_CHECKS = 4000

    /** A person mask must cover at least this share of the frame for its centroid to be the focus point. */
    const val MIN_PERSON_COVERAGE = 0.02

    /** The dial value nearest [n] (log distance), so a stored value from another build still lands on a mark. */
    fun snap(n: Float): Float = if (!n.isFinite() || n <= 0f) DEFAULT_APERTURE else APERTURES.minBy { abs(ln(it / n)) }

    /** [delta] 1/3 stops from [n], clamped to the dial. */
    fun step(n: Float, delta: Int): Float {
        val i = APERTURES.indexOf(snap(n))
        return APERTURES[(i + delta).coerceIn(0, APERTURES.size - 1)]
    }

    /** "f/2.8", "f/11". */
    fun label(n: Float): String = if (n >= 10f || n == n.toInt().toFloat()) "f/${n.toInt()}" else "f/$n"

    /** Hyperfocal distance (m) of the full-frame lens: f^2 / (N c) + f. */
    fun hyperfocalM(focalMm: Double, n: Double): Double = (focalMm * focalMm / (n * COC_FF_MM) + focalMm) / 1000.0

    data class FocusDistance(val metres: Double, val source: String, val diopters: Double?)

    /**
     * The focus distance from LENS_FOCUS_DISTANCE ([diopters], 1/m). 0 (infinity), missing or not finite: the virtual
     * lens's hyperfocal distance (everything from half of it to infinity stays sharp, so a wrong guess never blurs a far
     * subject), recorded as [SOURCE_HYPERFOCAL]. An uncalibrated lens's value is still used, and marked.
     */
    fun focusDistance(diopters: Float?, calibration: Int?, focalMm: Double, aperture: Double): FocusDistance {
        if (diopters == null || !diopters.isFinite() || diopters <= 0f) {
            return FocusDistance(hyperfocalM(focalMm, aperture), SOURCE_HYPERFOCAL, diopters?.toDouble())
        }
        val m = max(1.0 / diopters, MIN_FOCUS_M)
        val src = if (calibration == CALIBRATION_UNCALIBRATED) SOURCE_AF_UNCALIBRATED else SOURCE_AF
        return FocusDistance(m, src, diopters.toDouble())
    }

    /** Share of the active array's width the stop's 3:2 crop at [zoom] keeps (the RAW render's crop, [ColourMath.rawCrop]). */
    fun cropWidthFraction(activeW: Int, activeH: Int, zoom: Double): Double {
        if (activeW <= 0 || activeH <= 0) return 1.0 / max(zoom, 1.0)
        return ColourMath.rawCrop(activeW, activeH, zoom)[2].toDouble() / activeW
    }

    /**
     * The phone's full-frame-equivalent f-number for the crop rendered: N x 36 mm / (the crop's width on the sensor), the
     * crop's width = the active area's physical width x [cropFraction]. Null without the physical data.
     */
    fun phoneEquivalentAperture(fNumber: Double?, activeWidthMm: Double?, cropFraction: Double): Double? {
        if (fNumber == null || activeWidthMm == null || fNumber <= 0 || activeWidthMm <= 0 || cropFraction <= 0) return null
        return fNumber * FF_WIDTH_MM / (activeWidthMm * cropFraction)
    }

    /** The physical width of the active array: sensor width x active / pixel array width. */
    fun activeWidthMm(sensorWidthMm: Double?, activeW: Int, pixelArrayW: Int): Double? {
        if (sensorWidthMm == null || sensorWidthMm <= 0) return null
        return if (pixelArrayW > 0 && activeW > 0) sensorWidthMm * activeW / pixelArrayW else sensorWidthMm
    }

    /** The virtual lens: the stop's focal when it is honest (within 2 %), else the field of view it really frames. */
    fun virtualFocalMm(stopTargetMm: Int, trueEquivalentMm: Float?): Double {
        val t = stopTargetMm.toDouble()
        val e = trueEquivalentMm?.toDouble() ?: return t
        return if (abs(e - t) <= t * 0.02) t else e
    }

    /**
     * B31.1: the touch-focus point in the still (0..1). The tap is stored as a frame region (TouchMapping: normalised in
     * the stop's 3:2 frame, sensor orientation), and that frame is the still (the RAW crop at the stop's zoom, or the
     * super-resolution output grid), so its centre is the still point as is, clamped. (alpha.1 mapped it through the
     * zoomed RAW crop a second time, which pushed every tap toward the edges at 35-70 mm: the wrong focus plane.)
     */
    fun tapInStill(frameX: Double, frameY: Double): Pair<Double, Double> = frameX.coerceIn(0.0, 1.0) to frameY.coerceIn(0.0, 1.0)

    /**
     * A point on the upright picture ([u], [v], 0..1) to the still's own pixel orientation, the still being turned
     * [rotation] degrees clockwise to upright (the JPEG orientation).
     */
    fun uprightToStill(u: Double, v: Double, rotation: Int): Pair<Double, Double> = when (((rotation % 360) + 360) % 360) {
        90 -> v to (1 - u)
        180 -> (1 - u) to (1 - v)
        270 -> (1 - v) to u
        else -> u to v
    }

    /** The inverse of [uprightToStill]. */
    fun stillToUpright(x: Double, y: Double, rotation: Int): Pair<Double, Double> = when (((rotation % 360) + 360) % 360) {
        90 -> (1 - y) to x
        180 -> (1 - x) to (1 - y)
        270 -> y to (1 - x)
        else -> x to y
    }

    /**
     * B31.1: the default focus point when there is no tap: on the main person. The person mask's pixels >= 0.5 are split
     * into connected regions (4-neighbour); the region with the largest area x centrality (1 - distance of its centroid
     * from the frame centre / half-diagonal, at least 0.2) is the subject, and the point is its pixel nearest its own
     * centroid, so it is always on the person (a plain centroid of two people, or of a person with a near arm or leg in
     * the frame, can land on the background between them). Null below [MIN_PERSON_COVERAGE] of the frame.
     */
    fun personFocusPoint(mask: FloatArray?, w: Int, h: Int): Pair<Double, Double>? {
        if (mask == null || w <= 0 || h <= 0 || mask.size < w * h) return null
        val label = IntArray(w * h) { -1 }
        var total = 0
        var best: Pair<Double, Double>? = null
        var bestScore = -1.0
        val stack = IntArray(w * h)
        var next = 0
        for (start in 0 until w * h) {
            if (mask[start] < 0.5f || label[start] >= 0) continue
            val id = next++
            var top = 0
            stack[top++] = start
            label[start] = id
            val members = ArrayList<Int>()
            var sx = 0.0
            var sy = 0.0
            while (top > 0) {
                val i = stack[--top]
                members.add(i)
                val x = i % w
                val y = i / w
                sx += x + 0.5
                sy += y + 0.5
                for (k in 0 until 4) {
                    val nx = x + if (k == 0) {
                        -1
                    } else if (k == 1) {
                        1
                    } else {
                        0
                    }
                    val ny = y + if (k == 2) {
                        -1
                    } else if (k == 3) {
                        1
                    } else {
                        0
                    }
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val j = ny * w + nx
                    if (label[j] < 0 && mask[j] >= 0.5f) {
                        label[j] = id
                        stack[top++] = j
                    }
                }
            }
            total += members.size
            val cx = sx / members.size
            val cy = sy / members.size
            val d = Math.hypot(cx / w - 0.5, cy / h - 0.5) / Math.hypot(0.5, 0.5)
            val score = members.size * maxOf(0.2, 1.0 - d)
            if (score > bestScore) {
                bestScore = score
                var bi = members[0]
                var bd = Double.MAX_VALUE
                for (i in members) {
                    val dd = Math.hypot(i % w + 0.5 - cx, i / w + 0.5 - cy)
                    if (dd < bd) {
                        bd = dd
                        bi = i
                    }
                }
                best = (bi % w + 0.5) / w to (bi / w + 0.5) / h
            }
        }
        if (total < MIN_PERSON_COVERAGE * w * h || total == 0) return null
        return best
    }

    /** Centroid (0..1) of the person mask's pixels >= 0.5, or null below [MIN_PERSON_COVERAGE]. */
    fun maskCentroid(mask: FloatArray?, w: Int, h: Int): Pair<Double, Double>? {
        if (mask == null || w <= 0 || h <= 0 || mask.size < w * h) return null
        var n = 0L
        var sx = 0.0
        var sy = 0.0
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (mask[y * w + x] >= 0.5f) {
                    n++
                    sx += x + 0.5
                    sy += y + 0.5
                }
            }
        }
        if (n < MIN_PERSON_COVERAGE * w * h || n == 0L) return null
        return (sx / n / w) to (sy / n / h)
    }

    /**
     * B40: whether a tap is older than the current AF scene and must not be reused for DOF. A long-press ([locked], the
     * photographer's AF lock) is never stale; a plain tap is stale when [ageMs] (press minus its AF cycle start) is over
     * [TAP_STALE_MS], when the AF went back to scanning after the tap's cycle finished ([afRescanned]), or when the camera
     * panned [TAP_STALE_PAN_SHARE] of the way to the scene_pan reset ([panShare]). A tap the scene_pan / scene_ev reset
     * already cleared never reaches the still.
     */
    fun tapStale(ageMs: Long?, locked: Boolean, afRescanned: Boolean = false, panShare: Double = 0.0): Boolean {
        if (locked) return false
        if (afRescanned) return true
        if (ageMs != null && ageMs > TAP_STALE_MS) return true
        return panShare.isFinite() && panShare >= TAP_STALE_PAN_SHARE
    }

    /** B40: the focus point chosen for a still, with what the tap snap compared (null where it did not get that far). */
    data class FocusChoice(
        val point: String,
        val x: Double,
        val y: Double,
        /** Relative depth (0..1, nearer is larger) at the tap and the person's anchored depth near it. */
        val tapDepth: Double? = null,
        val personDepth: Double? = null,
        /** Nearest person pixel to the tap, as a share of the frame diagonal. */
        val personGapDiag: Double? = null
    )

    /** The person mask's 4-connected regions of pixels >= 0.5 (pixel indices), in scan order. */
    fun personRegions(mask: FloatArray, w: Int, h: Int): List<IntArray> {
        val out = ArrayList<IntArray>()
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        for (start in 0 until w * h) {
            if (mask[start] < 0.5f || seen[start]) continue
            var top = 0
            stack[top++] = start
            seen[start] = true
            val members = ArrayList<Int>()
            while (top > 0) {
                val i = stack[--top]
                members.add(i)
                val x = i % w
                val y = i / w
                if (x > 0 && !seen[i - 1] && mask[i - 1] >= 0.5f) {
                    seen[i - 1] = true
                    stack[top++] = i - 1
                }
                if (x < w - 1 && !seen[i + 1] && mask[i + 1] >= 0.5f) {
                    seen[i + 1] = true
                    stack[top++] = i + 1
                }
                if (y > 0 && !seen[i - w] && mask[i - w] >= 0.5f) {
                    seen[i - w] = true
                    stack[top++] = i - w
                }
                if (y < h - 1 && !seen[i + w] && mask[i + w] >= 0.5f) {
                    seen[i + w] = true
                    stack[top++] = i + w
                }
            }
            out.add(members.toIntArray())
        }
        return out
    }

    /** Median of a window of [FOCUS_WIN] x the long side around ([fx], [fy]), as the bokeh samples its focus depth. */
    fun windowMedian(m: FloatArray, w: Int, h: Int, fx: Double, fy: Double): Double {
        var r = max(1, Math.round(FOCUS_WIN * max(w, h)).toInt())
        r = minOf(r, (w - 1) / 2, (h - 1) / 2)
        val cx = Math.floor(fx * w).toInt().coerceIn(r, w - 1 - r)
        val cy = Math.floor(fy * h).toInt().coerceIn(r, h - 1 - r)
        val win = ArrayList<Float>()
        for (y in cy - r..cy + r) for (x in cx - r..cx + r) win.add(m[y * w + x])
        win.sort()
        return win[(win.size - 1) / 2].toDouble()
    }

    private fun percentile(v: FloatArray, n: Int, q: Double): Double {
        val c = v.copyOf(n)
        c.sort()
        return c[(q * (n - 1)).toInt().coerceIn(0, n - 1)].toDouble()
    }

    /** Relative depth (nearer larger) to the nominal diopters of the AF comparison ([SNAP_NEAR_DIOPTERS] at [near]). */
    private fun nominalDiopters(r: Double, far: Double, near: Double): Double =
        max((r - far) / max(near - far, 1e-3), 0.0) * SNAP_NEAR_DIOPTERS

    /**
     * B40: the focus point for a still. No tap: the person ([personFocusPoint]), else the centre. A [stale] tap is not
     * reused: the person, else the tap after all (it still anchors the AF distance better than the centre).
     *
     * A fresh tap off the person snaps to the person when a person region's nearest pixel is within [SNAP_RADIUS_DIAG]
     * of the frame diagonal of it AND the AF distance ([focus], LENS_FOCUS_DISTANCE) is closer to the person's anchored
     * depth (the median depth of the region's pixels within [SNAP_ANCHOR_DIAG] of the tap) than to the depth at the tap
     * (the bokeh's own window median): the AF followed the person and the tap landed beside them (a moving subject, a
     * near miss), and anchoring the AF distance at the tap would put the plane on the background. Compared in log
     * diopters on the bokeh's own anchoring (the 2 % far percentile at infinity), with the nominal scale
     * [SNAP_NEAR_DIOPTERS]. Without a usable AF distance (the hyperfocal default) the tap is kept. The snapped point is
     * the region pixel nearest the tap whose focus window is on the person and whose depth is the anchored depth, so the
     * bokeh's own sample (and its person protection) lands on the person.
     */
    fun chooseFocus(
        tap: Pair<Double, Double>?,
        stale: Boolean,
        depth: FloatArray?,
        mask: FloatArray?,
        w: Int,
        h: Int,
        focus: FocusDistance?
    ): FocusChoice {
        val person = personFocusPoint(mask, w, h)
        if (tap == null || stale) {
            if (person != null) return FocusChoice(if (tap == null) POINT_PERSON else POINT_TAP_STALE_PERSON, person.first, person.second)
            if (tap != null) return FocusChoice(POINT_TAP, tap.first, tap.second)
            return FocusChoice(POINT_CENTRE, 0.5, 0.5)
        }
        val keep = FocusChoice(POINT_TAP, tap.first, tap.second)
        if (depth == null || mask == null || w < 3 || h < 3 || depth.size < w * h || mask.size < w * h) return keep
        val tx = Math.floor(tap.first * w).toInt().coerceIn(0, w - 1)
        val ty = Math.floor(tap.second * h).toInt().coerceIn(0, h - 1)
        val diag = Math.hypot(w.toDouble(), h.toDouble())
        val rTap = windowMedian(depth, w, h, tap.first, tap.second)
        if (mask[ty * w + tx] >= 0.5f) return keep.copy(tapDepth = rTap, personGapDiag = 0.0)
        var region: IntArray? = null
        var gap = Double.MAX_VALUE
        for (reg in personRegions(mask, w, h)) {
            if (reg.size < SNAP_MIN_REGION * w * h) continue
            for (i in reg) {
                val d = Math.hypot(i % w - tx.toDouble(), i / w - ty.toDouble())
                if (d < gap) {
                    gap = d
                    region = reg
                }
            }
        }
        val reg = region ?: return keep.copy(tapDepth = rTap)
        val gapDiag = gap / diag
        if (gapDiag > SNAP_RADIUS_DIAG) return keep.copy(tapDepth = rTap, personGapDiag = gapDiag)
        val near = reg.filter { Math.hypot(it % w - tx.toDouble(), it / w - ty.toDouble()) <= SNAP_ANCHOR_DIAG * diag }
        val vals = near.map { depth[it] }.sorted()
        val rPerson = vals[(vals.size - 1) / 2].toDouble()
        val compared = keep.copy(tapDepth = rTap, personDepth = rPerson, personGapDiag = gapDiag)
        val af = focus?.diopters
        if (focus == null || focus.source == SOURCE_HYPERFOCAL || af == null || !af.isFinite() || af <= 0.0) return compared
        val n = w * h
        val far = percentile(depth, n, BG_PCT)
        val nearest = percentile(depth, n, 1.0 - BG_PCT)
        val lnAf = ln(max(af, SNAP_FLOOR_DIOPTERS))
        val dPerson = abs(lnAf - ln(max(nominalDiopters(rPerson, far, nearest), SNAP_FLOOR_DIOPTERS)))
        val dTap = abs(lnAf - ln(max(nominalDiopters(rTap, far, nearest), SNAP_FLOOR_DIOPTERS)))
        if (dPerson >= dTap) return compared
        // the snapped point: on the person (its focus window's mask median), at the anchored depth, nearest the tap
        val tol = 0.05
        val dist = { i: Int -> Math.hypot(i % w - tx.toDouble(), i / w - ty.toDouble()) }
        val cands = reg.filter { abs(depth[it] - rPerson) <= tol }.sortedBy(dist)
        val pick = cands.asSequence().take(SNAP_MAX_CHECKS).firstOrNull { i ->
            val px = (i % w + 0.5) / w
            val py = (i / w + 0.5) / h
            windowMedian(mask, w, h, px, py) >= 0.5 && abs(windowMedian(depth, w, h, px, py) - rPerson) <= tol
        } ?: cands.firstOrNull() ?: near.minBy(dist)
        return compared.copy(point = POINT_TAP_SNAPPED, x = (pick % w + 0.5) / w, y = (pick / w + 0.5) / h)
    }
}
