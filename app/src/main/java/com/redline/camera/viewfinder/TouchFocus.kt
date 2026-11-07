package com.redline.camera.viewfinder

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A rectangle in normalised coordinates (0..1), x to the right, y down. */
data class NormRect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top
    val centerX: Double get() = (left + right) / 2
    val centerY: Double get() = (top + bottom) / 2

    fun contains(x: Double, y: Double): Boolean = x in left..right && y in top..bottom

    fun toList(): List<Double> = listOf(left, top, right, bottom).map { Math.round(it * 10000.0) / 10000.0 }
}

/** An integer rectangle (left, top, width, height) in pixels. */
data class PxRect(val left: Int, val top: Int, val width: Int, val height: Int) {
    fun toIntArray(): IntArray = intArrayOf(left, top, width, height)

    fun toList(): List<Int> = listOf(left, top, width, height)
}

/**
 * B24b: what touch focus / metering may do on one camera. Regions are only ever written when the camera reports
 * CONTROL_MAX_REGIONS_AF / CONTROL_MAX_REGIONS_AE > 0 (a camera with 0, or a key it does not report, gets none); a tap
 * focus also needs AF mode AUTO (the trigger's mode), and an AE lock needs CONTROL_AE_LOCK_AVAILABLE.
 */
data class TouchFocusCaps(val maxRegionsAf: Int, val maxRegionsAe: Int, val afAutoMode: Boolean, val aeLockAvailable: Boolean) {
    val af: Boolean get() = maxRegionsAf > 0 && afAutoMode
    val ae: Boolean get() = maxRegionsAe > 0
    val enabled: Boolean get() = af || ae

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "af_regions_supported" to af,
        "ae_regions_supported" to ae,
        "max_regions_af" to maxRegionsAf,
        "max_regions_ae" to maxRegionsAe,
        "af_auto_mode" to afAutoMode,
        "ae_lock_available" to aeLockAvailable,
        "enabled" to enabled
    )

    companion object {
        val NONE = TouchFocusCaps(0, 0, afAutoMode = false, aeLockAvailable = false)

        fun from(maxRegionsAf: Int?, maxRegionsAe: Int?, afModes: List<String>, aeLockAvailable: Boolean): TouchFocusCaps =
            TouchFocusCaps(maxOf(0, maxRegionsAf ?: 0), maxOf(0, maxRegionsAe ?: 0), "AUTO" in afModes, aeLockAvailable)
    }
}

/**
 * The geometry a tap is mapped through: the view (the 3:2 / 2:3 box the preview fills), the preview buffer in sensor
 * orientation, the sensor and display rotation, the facing, and the active array.
 */
data class TouchGeometry(
    val viewW: Double,
    val viewH: Double,
    val streamW: Int,
    val streamH: Int,
    val sensorOrientation: Int,
    val displayRotation: Int,
    val frontFacing: Boolean,
    val activeW: Int,
    val activeH: Int
)

/**
 * B24b tap mapping. Coordinates:
 * - view: pixels of the preview box (the TextureView fills it, centre-cropped; see [PreviewTransform]);
 * - FOV: the zoomed field of view (what CONTROL_ZOOM_RATIO or SCALER_CROP_REGION leaves), normalised, sensor
 *   orientation, the active array's aspect;
 * - frame: the stop's centred 3:2 frame inside the FOV, normalised, sensor orientation (the still's crop:
 *   ColourMath.rawCrop, and the super-resolution output grid). A tap is stored as a frame region, which keeps its place in
 *   the frame at every zoom stop (as an AF point does on a camera) and maps to the request and the still at the stop's
 *   ratio.
 *
 * The picture on screen is the buffer rotated clockwise by the sensor orientation (SurfaceTexture), mirrored for a front
 * camera (Camera2's default preview mirror), then rotated by minus the display rotation and centre-cropped to the view.
 */
object TouchMapping {
    /** Tap region side as a fraction of the frame's short side (the R5 II pairs' median AF area; metering.json spot). */
    const val DEFAULT_REGION_SHORT_SIDE = 0.11

    /** Camera2 MeteringRectangle weight for the tapped region (the maximum, 1000). */
    const val REGION_WEIGHT = 1000

    private fun rotCw(x: Double, y: Double, deg: Int): Pair<Double, Double> = when (((deg % 360) + 360) % 360) {
        90 -> (1 - y) to x
        180 -> (1 - x) to (1 - y)
        270 -> y to (1 - x)
        else -> x to y
    }

    private fun swaps(deg: Int): Boolean = ((deg % 180) + 180) % 180 == 90

    /** The picture's size on screen before the fill scale: the buffer rotated by sensor and display rotation. */
    private fun screenSize(g: TouchGeometry): Pair<Double, Double> {
        var w = g.streamW.toDouble()
        var h = g.streamH.toDouble()
        if (swaps(g.sensorOrientation)) w = h.also { h = w }
        if (swaps(g.displayRotation)) w = h.also { h = w }
        return w to h
    }

    /** Visible fraction of the on-screen picture in x and y (centre crop to fill the view). */
    private fun visible(g: TouchGeometry): Pair<Double, Double> {
        val (uw, uh) = screenSize(g)
        val s = max(g.viewW / uw, g.viewH / uh)
        return (g.viewW / (uw * s)) to (g.viewH / (uh * s))
    }

    /** Fraction of the FOV the buffer covers (the HAL centre-crops the FOV to the buffer's aspect). */
    private fun streamInFov(g: TouchGeometry): Pair<Double, Double> {
        val aStream = g.streamW.toDouble() / g.streamH
        val aFov = g.activeW.toDouble() / g.activeH
        return if (aStream > aFov) 1.0 to (aFov / aStream) else (aStream / aFov) to 1.0
    }

    /** Tap at view pixel (x, y) -> point in the FOV (normalised, sensor orientation). */
    fun viewToFov(x: Double, y: Double, g: TouchGeometry): Pair<Double, Double> {
        val (fx, fy) = visible(g)
        val ux = 0.5 + (x / g.viewW - 0.5) * fx
        val uy = 0.5 + (y / g.viewH - 0.5) * fy
        // screen -> natural (undo the view's rotation by minus the display rotation)
        var (nx, ny) = rotCw(ux, uy, g.displayRotation)
        if (g.frontFacing) nx = 1 - nx
        // natural -> sensor (undo SurfaceTexture's clockwise sensor rotation)
        val (sx, sy) = rotCw(nx, ny, 360 - g.sensorOrientation)
        val (cx, cy) = streamInFov(g)
        return (0.5 + (sx - 0.5) * cx) to (0.5 + (sy - 0.5) * cy)
    }

    /** Point in the FOV -> view pixel: the inverse of [viewToFov] (draws the AF point where the region really is). */
    fun fovToView(px: Double, py: Double, g: TouchGeometry): Pair<Double, Double> {
        val (cx, cy) = streamInFov(g)
        val sx = 0.5 + (px - 0.5) / cx
        val sy = 0.5 + (py - 0.5) / cy
        var (nx, ny) = rotCw(sx, sy, g.sensorOrientation)
        if (g.frontFacing) nx = 1 - nx
        val (ux, uy) = rotCw(nx, ny, 360 - g.displayRotation)
        val (fx, fy) = visible(g)
        return ((0.5 + (ux - 0.5) / fx) * g.viewW) to ((0.5 + (uy - 0.5) / fy) * g.viewH)
    }

    /** The stop's centred 3:2 frame inside the FOV, normalised (the proportions of ColourMath.rawCrop, at any zoom). */
    fun frameInFov(activeW: Int, activeH: Int): NormRect {
        val w = activeW.toDouble()
        val h = activeH.toDouble()
        var cw: Double
        var ch: Double
        if (w >= h) {
            cw = w
            ch = w * 2.0 / 3.0
            if (ch > h) {
                ch = h
                cw = h * 1.5
            }
        } else {
            ch = h
            cw = h * 2.0 / 3.0
            if (cw > w) {
                cw = w
                ch = w * 1.5
            }
        }
        val fw = cw / w
        val fh = ch / h
        return NormRect((1 - fw) / 2, (1 - fh) / 2, (1 + fw) / 2, (1 + fh) / 2)
    }

    fun fovToFrame(px: Double, py: Double, activeW: Int, activeH: Int): Pair<Double, Double> {
        val f = frameInFov(activeW, activeH)
        return ((px - f.left) / f.width).coerceIn(0.0, 1.0) to ((py - f.top) / f.height).coerceIn(0.0, 1.0)
    }

    fun frameToFov(fx: Double, fy: Double, activeW: Int, activeH: Int): Pair<Double, Double> {
        val f = frameInFov(activeW, activeH)
        return (f.left + fx * f.width) to (f.top + fy * f.height)
    }

    /**
     * A square region (in sensor pixels) centred on frame point ([fx], [fy]), its side [shortSide] of the frame's short side,
     * shifted (never shrunk) to stay inside the frame. Frame-normalised.
     */
    fun regionAround(fx: Double, fy: Double, activeW: Int, activeH: Int, shortSide: Double = DEFAULT_REGION_SHORT_SIDE): NormRect {
        val f = frameInFov(activeW, activeH)
        val wPx = f.width * activeW
        val hPx = f.height * activeH
        val side = shortSide.coerceIn(0.01, 1.0) * min(wPx, hPx)
        val hw = min(0.5, side / wPx / 2)
        val hh = min(0.5, side / hPx / 2)
        val cx = fx.coerceIn(hw, 1 - hw)
        val cy = fy.coerceIn(hh, 1 - hh)
        return NormRect(cx - hw, cy - hh, cx + hw, cy + hh)
    }

    /** A frame region in FOV coordinates. */
    fun regionInFov(region: NormRect, activeW: Int, activeH: Int): NormRect {
        val (l, t) = frameToFov(region.left, region.top, activeW, activeH)
        val (r, b) = frameToFov(region.right, region.bottom, activeW, activeH)
        return NormRect(l, t, r, b)
    }

    /**
     * Frame region -> Camera2 metering rectangle (x, y, width, height), origin at the active array's top-left.
     * [zoomRatioApi] (API 30+, CONTROL_ZOOM_RATIO): region coordinates span the zoomed FOV mapped onto the whole active
     * array (Camera2: "relative to the active array representing the zoomed field of view"). Otherwise the FOV is
     * [cropRegion] (SCALER_CROP_REGION, relative to the active array's origin) and the coordinates are absolute.
     */
    fun meteringRect(region: NormRect, activeW: Int, activeH: Int, zoomRatioApi: Boolean, cropRegion: PxRect? = null): PxRect {
        val r = regionInFov(region, activeW, activeH)
        val ox: Double
        val oy: Double
        val sw: Double
        val sh: Double
        if (zoomRatioApi || cropRegion == null) {
            ox = 0.0
            oy = 0.0
            sw = activeW.toDouble()
            sh = activeH.toDouble()
        } else {
            ox = cropRegion.left.toDouble()
            oy = cropRegion.top.toDouble()
            sw = cropRegion.width.toDouble()
            sh = cropRegion.height.toDouble()
        }
        val l = (ox + r.left * sw).roundToInt().coerceIn(0, activeW - 1)
        val t = (oy + r.top * sh).roundToInt().coerceIn(0, activeH - 1)
        val rr = (ox + r.right * sw).roundToInt().coerceIn(l + 1, activeW)
        val bb = (oy + r.bottom * sh).roundToInt().coerceIn(t + 1, activeH)
        return PxRect(l, t, rr - l, bb - t)
    }

    /** SCALER_CROP_REGION for [ratio] relative to the active array's origin (ViewfinderSetup.cropRegion's arithmetic). */
    fun cropRegion(activeW: Int, activeH: Int, ratio: Float): PxRect {
        val r = ratio.coerceAtLeast(1f)
        val w = (activeW / r).toInt()
        val h = (activeH / r).toInt()
        return PxRect((activeW - w) / 2, (activeH - h) / 2, w, h)
    }

    /**
     * Frame region -> pixels of a frame crop ([crop]: the still's RAW crop, or (0, 0, w, h) for the super-resolution output
     * grid). [even]: left/top/size even (CFA cells). Always inside the crop, at least 2 x 2.
     */
    fun regionInCrop(region: NormRect, crop: PxRect, even: Boolean): PxRect {
        fun ev(v: Int) = if (even) v and 1.inv() else v
        var l = ev(crop.left + floor(region.left * crop.width).toInt())
        var t = ev(crop.top + floor(region.top * crop.height).toInt())
        var w = ev(max(2, (region.width * crop.width).roundToInt()))
        var h = ev(max(2, (region.height * crop.height).roundToInt()))
        w = min(w, ev(crop.width))
        h = min(h, ev(crop.height))
        l = ev(l.coerceIn(crop.left, crop.left + crop.width - w))
        t = ev(t.coerceIn(crop.top, crop.top + crop.height - h))
        return PxRect(l, t, w, h)
    }
}

/** Camera2 CONTROL_AF_STATE, as touch focus reads it. */
enum class AfState { INACTIVE, SCANNING, FOCUSED, NOT_FOCUSED, UNKNOWN }

/** Where touch focus stands. */
enum class TouchMode { AUTO, FOCUSING, FOCUSED, NOT_FOCUSED, METERED, TIMED_OUT }

/** Why a tap went back to continuous auto. */
enum class TouchReset { TAP_OFF, SCENE_EV, SCENE_PAN, CAMERA_CLOSED }

/**
 * B24b touch focus state (pure: the controller feeds it time, AF states and preview exposure). One tap or long-press at a
 * time; a long-press locks AF (when the camera has AF regions) and AE (when it has AE lock) and is never reset by a scene
 * change; a plain tap is reset by a large scene change ([SceneChange]) or by tapping its AF point again.
 */
class TouchFocusSession(private val caps: TouchFocusCaps) {
    var region: NormRect? = null
        private set
    var mode: TouchMode = TouchMode.AUTO
        private set
    var locked = false
        private set
    var tapAtMs = 0L
        private set
    var lastFocusMs: Long? = null
        private set
    var lastAfState: AfState = AfState.INACTIVE
        private set
    var taps = 0
        private set
    var locks = 0
        private set
    var lastReset: TouchReset? = null
        private set
    val resets = LinkedHashMap<TouchReset, Int>()
    val focusTimesMs = ArrayDeque<Long>()

    /** Whether a trigger is waiting for its AF result (results before the trigger's own frame are ignored). */
    var awaitingTrigger = false
        private set

    val active: Boolean get() = region != null

    /** A long-press locks AE only once the region was metered and focused ([armLockIfDue]), not at the press. */
    private var aeLockArmed = false

    /**
     * A tap (or [lock]: a long-press) at frame region [r]. [af]: an AF cycle may run (AF regions, and no manual focus
     * distance). Returns false when the camera has no regions at all.
     */
    fun tap(r: NormRect, nowMs: Long, lock: Boolean, af: Boolean = caps.af): Boolean {
        if (!caps.enabled) return false
        val focus = af && caps.af
        region = r
        tapAtMs = nowMs
        locked = lock
        aeLockArmed = false
        taps++
        if (lock) locks++
        lastFocusMs = null
        awaitingTrigger = focus
        mode = if (focus) TouchMode.FOCUSING else TouchMode.METERED
        return true
    }

    /** A new AF cycle at the same region (the zoom stop changed under a plain tap); not counted as a tap. */
    fun refocus(nowMs: Long) {
        if (region == null || locked || !caps.af) return
        tapAtMs = nowMs
        lastFocusMs = null
        awaitingTrigger = true
        mode = TouchMode.FOCUSING
    }

    /** The AF trigger request completed: AF results from here on belong to this tap. */
    fun triggerSubmitted() {
        awaitingTrigger = false
    }

    /** One preview result's AF state. */
    fun onAfState(s: AfState, nowMs: Long) {
        lastAfState = s
        if (region == null || mode != TouchMode.FOCUSING || awaitingTrigger) return
        when (s) {
            AfState.FOCUSED, AfState.NOT_FOCUSED -> {
                val ms = nowMs - tapAtMs
                lastFocusMs = ms
                focusTimesMs.addLast(ms)
                while (focusTimesMs.size > MAX_TIMES) focusTimesMs.removeFirst()
                mode = if (s == AfState.FOCUSED) TouchMode.FOCUSED else TouchMode.NOT_FOCUSED
            }
            else -> if (nowMs - tapAtMs > FOCUS_TIMEOUT_MS) mode = TouchMode.TIMED_OUT
        }
    }

    fun reset(why: TouchReset) {
        if (region == null) return
        region = null
        locked = false
        aeLockArmed = false
        awaitingTrigger = false
        mode = TouchMode.AUTO
        lastReset = why
        resets[why] = (resets[why] ?: 0) + 1
    }

    /**
     * Arms a long-press's AE lock once AF has finished (or, without AF, the exposure had [AE_SETTLE_MS] on the region).
     * Returns true when it just armed (the repeating request needs the lock).
     */
    fun armLockIfDue(nowMs: Long): Boolean {
        if (!locked || aeLockArmed || !(caps.aeLockAvailable && caps.ae) || region == null) return false
        if (mode == TouchMode.FOCUSING || nowMs - tapAtMs < AE_SETTLE_MS) return false
        aeLockArmed = true
        return true
    }

    /** The AE lock to write: a long-press, armed, on a camera that reports CONTROL_AE_LOCK_AVAILABLE. */
    val aeLock: Boolean get() = locked && aeLockArmed && caps.aeLockAvailable && caps.ae

    companion object {
        const val FOCUS_TIMEOUT_MS = 3000L
        const val AE_SETTLE_MS = 600L
        const val MAX_TIMES = 20
    }
}

/** B24b: touch focus for the shared report ("touch_focus") and the viewfinder's AF point. */
data class TouchFocusReport(
    val caps: TouchFocusCaps = TouchFocusCaps.NONE,
    val region: NormRect? = null,
    val meteringRect: PxRect? = null,
    val zoomRatio: Float? = null,
    val mode: TouchMode = TouchMode.AUTO,
    val afState: AfState = AfState.INACTIVE,
    val locked: Boolean = false,
    val aeLocked: Boolean = false,
    val taps: Int = 0,
    val locks: Int = 0,
    val lastFocusMs: Long? = null,
    val focusTimesMs: List<Long> = emptyList(),
    val resets: Map<String, Int> = emptyMap(),
    val lastReset: String? = null,
    val guardedCaptures: Int = 0,
    val lastGuardCutEv: Double? = null
) {
    /** Gate: every timed tap focus in the run landed within 1 s (null before any). */
    val focusGatePassed: Boolean? get() = focusTimesMs.takeIf { it.isNotEmpty() }?.let { t -> t.max() < FOCUS_GATE_MS }

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "af_regions_supported" to caps.af,
        "ae_regions_supported" to caps.ae,
        "caps" to caps.toMap(),
        "last_tap_region" to region?.toList(),
        "last_metering_rect" to meteringRect?.toList(),
        "zoom_ratio" to zoomRatio?.let { Math.round(it * 1000.0) / 1000.0 },
        "mode" to mode.name,
        "af_state" to afState.name,
        "lock_state" to when {
            !locked -> "none"
            aeLocked -> "ae_af_locked"
            else -> "af_locked"
        },
        "taps" to taps,
        "locks" to locks,
        "time_to_focus_ms" to lastFocusMs,
        "time_to_focus_recent_ms" to focusTimesMs,
        "time_to_focus_max_ms" to focusTimesMs.maxOrNull(),
        "resets" to resets,
        "last_reset" to lastReset,
        "highlight_guard_captures" to guardedCaptures,
        "highlight_guard_last_cut_ev" to lastGuardCutEv?.let { Math.round(it * 1000.0) / 1000.0 },
        "gate" to linkedMapOf("tap_focus_lt_1000_ms" to focusGatePassed)
    )

    companion object {
        const val FOCUS_GATE_MS = 1000L
    }
}

/**
 * B24b: a large scene change returns a plain tap to continuous auto. Two signals, each conservative so a tap holds while
 * the photographer recomposes slightly:
 * - exposure: the preview's auto-exposure (metering the tapped region) moves more than [evThreshold] from where it
 *   settled, for [frames] frames in a row (the subject left the region or the light changed);
 * - pan: deliberate rotation (above [panRateRadPerSec], so hand shake is ignored) adds up to more than [panFraction] of the
 *   stop's horizontal field of view.
 * The baseline is taken [settleMs] after the tap (AE converging on the region is not a change).
 */
class SceneChange(
    private val evThreshold: Double = 1.0,
    private val frames: Int = 6,
    private val panRateRadPerSec: Double = 0.25,
    private val panFraction: Double = 0.4,
    private val settleMs: Long = 1200L
) {
    private var startMs = 0L
    private var baseline: Double? = null
    private var over = 0
    private var panRad = 0.0

    fun start(nowMs: Long) {
        startMs = nowMs
        baseline = null
        over = 0
        panRad = 0.0
    }

    /** A preview frame's exposure, log2(ISO x exposure time x boost); returns true on a large change. */
    fun onExposure(ev: Double, nowMs: Long): Boolean {
        if (!ev.isFinite() || nowMs - startMs < settleMs) return false
        val b = baseline ?: run {
            baseline = ev
            return false
        }
        over = if (abs(ev - b) > evThreshold) over + 1 else 0
        return over >= frames
    }

    /** Rotation rate over [dtSec]; [hfovRad]: the stop's horizontal field of view. Returns true on a large pan. */
    fun onMotion(radPerSec: Double, dtSec: Double, hfovRad: Double): Boolean {
        if (!radPerSec.isFinite() || dtSec <= 0 || hfovRad <= 0) return false
        if (radPerSec > panRateRadPerSec) panRad += radPerSec * min(dtSec, 0.5)
        return panRad > panFraction * hfovRad
    }

    /** B40: how far deliberate panning since the tap has gone toward the reset (1 = the reset), 0 without a field of view. */
    fun panShare(hfovRad: Double): Double = if (hfovRad > 0) panRad / (panFraction * hfovRad) else 0.0
}

/**
 * B24b highlight guard. The tapped region steers the phone's auto exposure (the preview follows the tap), but the R5 II
 * metering does not lift a dark AF subject (spot fit: w_darker = 0), so a RAW exposed brighter than the frame meter would
 * only clip highlights our render then has to pull back down. A capture after a tap is therefore never brighter than the
 * frame meter at the moment of the tap plus [HEADROOM_EV] (drift and AE jitter): ISO comes down first, then the shutter.
 * Darker is allowed (it protects a bright subject, as the R5 II does).
 */
object TouchExposureGuard {
    const val HEADROOM_EV = 1.0 / 3.0

    data class Exposure(val iso: Int, val exposureNs: Long, val capped: Boolean, val cutEv: Double)

    /** Exposure product (ISO x seconds x boost) as log2. */
    fun ev(iso: Int, exposureNs: Long, boost: Int?): Double = Math.log(iso * (exposureNs / 1e9) * ((boost ?: 100) / 100.0)) / Math.log(2.0)

    fun cap(iso: Int, exposureNs: Long, boost: Int?, capEv: Double?, isoMin: Int, exposureMinNs: Long): Exposure {
        if (capEv == null || !capEv.isFinite()) return Exposure(iso, exposureNs, false, 0.0)
        val now = ev(iso, exposureNs, boost)
        val limit = capEv + HEADROOM_EV
        if (now <= limit) return Exposure(iso, exposureNs, false, 0.0)
        val factor = Math.pow(2.0, limit - now)
        val newIso = max(isoMin, min(iso, Math.round(iso * factor).toInt()))
        val rest = factor * iso / newIso
        // ISO rounding leaves at most ~1 % (0.015 EV): the shutter only moves when ISO alone could not get there
        val newExp = if (rest >= 0.99) exposureNs else max(exposureMinNs, min(exposureNs, Math.round(exposureNs * rest)))
        return Exposure(newIso, newExp, true, now - ev(newIso, newExp, boost))
    }
}
