package com.redline.camera.capture

import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** What the shutter knows when it plans a burst (B22). Pure data; no Android types. */
data class BurstInputs(
    /** The burst-merge setting is on and its checks passed (B21 pipeline on, RAW in the session, self-check). */
    val enabled: Boolean,
    /** Metered exposure of the latest auto-exposure preview frame (Auto/Av); null before one arrived. */
    val meteredIso: Int?,
    val meteredExposureNs: Long?,
    /** M: the user's still exposure (AE off). Null in Auto/Av. */
    val manualIso: Int? = null,
    val manualExposureNs: Long? = null,
    val isoRange: Pair<Int, Int>,
    val exposureRangeNs: Pair<Long, Long>,
    /** Shortest frame duration of a full-size RAW_SENSOR stream (0 = not reported). */
    val rawMinFrameDurationNs: Long,
    /** Frame buffers the memory pool can still hand out to this burst's RAW frames (a DNG's output slot already taken off). */
    val poolFreeFrames: Int,
    /** Bursts pending: capturing, merging or waiting to be saved. */
    val burstsQueued: Int,
    /** Hand motion (gyroscope magnitude, rad/s, smoothed); null when there is no gyroscope. */
    val motionRadPerSec: Double? = null,
    /** B22a: shutter presses waiting for the camera; a burst would hold them up, so they take the fast single path. */
    val pressesWaiting: Int = 0
)

/**
 * The shutter's decision. [frames] > 0: a RAW burst of that many frames at [iso] / [exposureNs] (all frames the
 * same), plus one normally exposed camera-JPEG frame first (the B21 exposure target). [frames] == 0: the B21
 * single-frame path, [singleReason] says why.
 */
data class BurstPlan(
    val frames: Int,
    val iso: Int = 0,
    val exposureNs: Long = 0,
    val frameDurationNs: Long = 0,
    /** Applied under-exposure in EV versus the metered (or manual) exposure, after clamping. */
    val underEv: Double = 0.0,
    /** What the frame count was decided by: light, motion, duration, memory (in the order applied). */
    val limits: List<String> = emptyList(),
    val singleReason: String? = null,
    /** B22a: the light level the frame count came from ([BurstPlanner.lightLevel] of the burst ISO). */
    val light: String? = null,
    /**
     * B22a: pool buffers the burst holds at most (the 3 reference candidates, then the reference and frames waiting to
     * be merged); frames beyond wait in the camera's own queue until the streaming merge hands a buffer back.
     */
    val residentFrames: Int = 0
) {
    val isBurst: Boolean get() = frames > 0

    /** Time the sensor needs for the burst frames, in ms (for the capture timeout). */
    val captureMs: Long get() = frames * max(frameDurationNs, exposureNs) / 1_000_000L

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "frames" to frames,
        "iso" to if (isBurst) iso else null,
        "exposure_ns" to if (isBurst) exposureNs else null,
        "frame_duration_ns" to if (isBurst) frameDurationNs else null,
        "under_ev" to if (isBurst) Math.round(underEv * 100.0) / 100.0 else null,
        "limits" to limits,
        "light" to light,
        "resident_frames" to if (isBurst) residentFrames else null,
        "single_reason" to singleReason
    )
}

/**
 * B22 burst planning (PLAN 6 step 1), adaptive since B22a: the frame count follows the light (the burst ISO): 4 in
 * bright light, 6 in good light, 8 indoors, 10 when dim, 12 when dark; fewer when the hand is moving, when the burst
 * would last too long, or when memory is short. Under-exposed by [UNDER_EV] in Auto/Av (shorter shutter first, then
 * lower ISO, both clamped to the reported ranges) for highlight headroom. In M the user's exposure is kept exactly (no
 * under-exposure): M means the user chose it. The shutter never waits on a burst: with presses waiting or
 * [MAX_QUEUED_BURSTS] bursts pending, the shot takes the single-frame path.
 */
object BurstPlanner {
    const val MAX_FRAMES = 12
    const val MIN_FRAMES = 3
    const val UNDER_EV = 0.7
    const val MOTION_HIGH_RAD_S = 0.6
    const val FRAMES_HIGH_MOTION = 6

    /** Light levels by burst ISO (upper bound inclusive) and their frame counts; above the last: [MAX_FRAMES]. */
    val LIGHT_LEVELS: List<Triple<String, Int, Int>> = listOf(
        Triple("bright", 100, 4),
        Triple("good", 200, 6),
        Triple("indoor", 399, 8),
        Triple("dim", 1599, 10)
    )
    const val LIGHT_DARK = "dark"

    /** The burst frames (not counting the JPEG frame) may take at most this long on the sensor. */
    const val MAX_BURST_NS = 1_200_000_000L

    /** At most this many bursts pending (capturing, merging or saving); later shots take the single-frame path. */
    const val MAX_QUEUED_BURSTS = 2

    /** Pool buffers one burst holds at most: the 3 reference candidates plus 3 frames waiting to be merged. */
    const val MAX_RESIDENT_FRAMES = 6

    /** Frames that may wait in the camera's RAW queue (beyond the burst's buffers) for a buffer to come back. */
    const val CAMERA_QUEUE_SLACK = 3

    /** A conservative phone estimate of the streaming merge's time per frame (planning only: how far it falls behind). */
    const val ASSUMED_MERGE_NS_PER_FRAME = 120_000_000L

    /** Most frames a burst of [n] frames, [frameNs] apart, can take with [resident] pool buffers without stalling the camera. */
    fun streamCap(resident: Int, n: Int, frameNs: Long): Int {
        val drained = (n * frameNs / ASSUMED_MERGE_NS_PER_FRAME).toInt()
        return resident + CAMERA_QUEUE_SLACK + drained
    }

    fun lightLevel(iso: Int): String = LIGHT_LEVELS.firstOrNull { iso <= it.second }?.first ?: LIGHT_DARK

    fun framesFor(iso: Int): Int = LIGHT_LEVELS.firstOrNull { iso <= it.second }?.third ?: MAX_FRAMES

    fun plan(i: BurstInputs): BurstPlan {
        if (!i.enabled) return single("burst merge off")
        if (i.pressesWaiting > 0) return single("presses waiting (single frame keeps the shutter free)")
        if (i.burstsQueued >= MAX_QUEUED_BURSTS) return single("queue full ($MAX_QUEUED_BURSTS bursts pending)")
        val manual = i.manualIso != null && i.manualExposureNs != null
        val baseIso = if (manual) i.manualIso!! else i.meteredIso ?: return single("no metered exposure yet")
        val baseExp = if (manual) i.manualExposureNs!! else i.meteredExposureNs ?: return single("no metered exposure yet")
        val (isoLo, isoHi) = i.isoRange
        val (expLo, expHi) = i.exposureRangeNs
        var iso = baseIso.coerceIn(isoLo, isoHi)
        var exp = baseExp.coerceIn(expLo, expHi)
        if (!manual) {
            val factor = 2.0.pow(-UNDER_EV)
            val wantExp = baseExp * factor
            if (wantExp >= expLo) {
                exp = wantExp.roundToLong().coerceIn(expLo, expHi)
            } else {
                exp = expLo
                iso = (baseIso * (wantExp / expLo)).roundToInt().coerceIn(isoLo, isoHi)
            }
        }
        val under = log2((baseExp.toDouble() * baseIso) / (exp.toDouble() * iso))
        val light = lightLevel(iso)
        val limits = mutableListOf("light")
        var n = framesFor(iso)
        val motion = i.motionRadPerSec
        if (motion != null && motion >= MOTION_HIGH_RAD_S && n > FRAMES_HIGH_MOTION) {
            n = FRAMES_HIGH_MOTION
            limits += "motion"
        }
        val frameNs = max(exp, i.rawMinFrameDurationNs)
        val byDuration = floor(MAX_BURST_NS.toDouble() / frameNs).toInt()
        if (byDuration < n) {
            n = byDuration
            limits += "duration"
        }
        if (n < MIN_FRAMES) {
            val why = if ("duration" in limits) "exposure too long for a burst (${exp / 1_000_000} ms)" else "fewer than $MIN_FRAMES frames"
            return single(why).copy(limits = limits, light = light)
        }
        val resident = minOf(n, MAX_RESIDENT_FRAMES, max(i.poolFreeFrames, 0))
        if (resident < MIN_FRAMES) {
            limits += "memory"
            return single("not enough memory for $MIN_FRAMES frames").copy(limits = limits, light = light)
        }
        // Frames beyond the burst's buffers wait in the camera's queue until the streaming merge frees one; cap them by
        // that queue plus what the merge can take in while the burst lasts, so the camera never stalls.
        val byMemory = streamCap(resident, n, frameNs)
        if (byMemory < n) {
            n = byMemory
            limits += "memory"
        }
        n = min(n, MAX_FRAMES)
        return BurstPlan(n, iso, exp, frameNs, under, limits, light = light, residentFrames = min(resident, n))
    }

    private fun single(why: String) = BurstPlan(frames = 0, singleReason = why)

    private fun log2(v: Double): Double = ln(v) / ln(2.0)
}

/**
 * B22a: where a shot goes when it is not a burst. The B21 single-frame RAW path holds one pool buffer until its
 * render, so at most [MAX_PENDING_SINGLES] wait; beyond that (or with no buffer free) the shot is the camera JPEG,
 * which needs no pool memory. The shutter is never refused.
 */
object ShotRouter {
    const val MAX_PENDING_SINGLES = 2

    enum class Path { SINGLE_RAW, CAMERA_JPEG }

    /** [wantsRaw]: the session has RAW and the pipeline or the DNG toggle wants it. Returns the path and why it is the JPEG. */
    fun route(wantsRaw: Boolean, pendingSingles: Int, poolFree: Int): Pair<Path, String?> = when {
        !wantsRaw -> Path.CAMERA_JPEG to null
        pendingSingles >= MAX_PENDING_SINGLES -> Path.CAMERA_JPEG to "single-frame queue full ($MAX_PENDING_SINGLES waiting): camera JPEG"
        poolFree < 1 -> Path.CAMERA_JPEG to "no memory for a RAW copy: camera JPEG"
        else -> Path.SINGLE_RAW to null
    }
}

/**
 * How many frame buffers the memory pool may hold (PLAN 12: merge memory cap ~1.2 GB on an 8 GB phone): a third
 * of what the system reports available, capped at [MAX_BUDGET_BYTES], minus the merge's own working set (B22a: one
 * streaming accumulator, ~5.5 bytes per RAW pixel, since bursts merge one at a time), halved when the system says
 * memory is low.
 */
object MemoryBudget {
    const val MAX_BUDGET_BYTES = 1_200L * 1024 * 1024
    const val WORKING_SET_BYTES = 192L * 1024 * 1024
    const val MAX_POOL_FRAMES = 2 * (BurstPlanner.MAX_FRAMES + 1)

    fun poolFrames(availBytes: Long, lowMemory: Boolean, frameBytes: Long): Int {
        if (frameBytes <= 0) return 0
        var budget = min(MAX_BUDGET_BYTES, availBytes / 3) - WORKING_SET_BYTES
        if (lowMemory) budget /= 2
        if (budget <= 0) return 0
        return min((budget / frameBytes).toInt(), MAX_POOL_FRAMES)
    }
}
