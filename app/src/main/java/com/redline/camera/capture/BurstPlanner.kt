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
    /** Frame buffers the memory pool can still hand out (the merge output takes one). */
    val poolFreeFrames: Int,
    /** Bursts already waiting or processing on the queue. */
    val burstsQueued: Int,
    /** Hand motion (gyroscope magnitude, rad/s, smoothed); null when there is no gyroscope. */
    val motionRadPerSec: Double? = null
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
    /** What the frame count was decided by: target, light, motion, duration, memory (in the order applied). */
    val limits: List<String> = emptyList(),
    val singleReason: String? = null
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
        "single_reason" to singleReason
    )
}

/**
 * B22 burst planning (PLAN 6 step 1): 8 frames by default, up to 12 in low light, fewer when the hand is moving,
 * when the burst would last too long, or when memory or the queue is short; under-exposed by [UNDER_EV] in Auto/Av
 * (shorter shutter first, then lower ISO, both clamped to the reported ranges) for highlight headroom. In M the
 * user's exposure is kept exactly (no under-exposure): M means the user chose it.
 */
object BurstPlanner {
    const val TARGET_FRAMES = 8
    const val MAX_FRAMES = 12
    const val MIN_FRAMES = 3
    const val UNDER_EV = 0.7
    const val ISO_MORE_FRAMES = 400
    const val ISO_MAX_FRAMES = 1600
    const val FRAMES_MORE_LIGHT = 10
    const val MOTION_HIGH_RAD_S = 0.6
    const val FRAMES_HIGH_MOTION = 6

    /** The burst frames (not counting the JPEG frame) may take at most this long on the sensor. */
    const val MAX_BURST_NS = 1_200_000_000L

    /** The plan's "max 2 bursts queued" (PLAN 12); later shots take the single-frame path until one finishes. */
    const val MAX_QUEUED_BURSTS = 2

    fun plan(i: BurstInputs): BurstPlan {
        if (!i.enabled) return single("burst merge off")
        if (i.burstsQueued >= MAX_QUEUED_BURSTS) return single("queue full ($MAX_QUEUED_BURSTS bursts waiting)")
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
        val limits = mutableListOf("target")
        var n = TARGET_FRAMES
        if (iso >= ISO_MAX_FRAMES) {
            n = MAX_FRAMES
            limits += "light"
        } else if (iso >= ISO_MORE_FRAMES) {
            n = FRAMES_MORE_LIGHT
            limits += "light"
        }
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
        val byMemory = i.poolFreeFrames - 1
        if (byMemory < n) {
            n = max(byMemory, 0)
            limits += "memory"
        }
        n = min(n, MAX_FRAMES)
        if (n < MIN_FRAMES) {
            val why = when (limits.last()) {
                "memory" -> "not enough memory for $MIN_FRAMES frames"
                "duration" -> "exposure too long for a burst (${exp / 1_000_000} ms)"
                else -> "fewer than $MIN_FRAMES frames"
            }
            return single(why).copy(limits = limits)
        }
        return BurstPlan(n, iso, exp, frameNs, under, limits)
    }

    private fun single(why: String) = BurstPlan(frames = 0, singleReason = why)

    private fun log2(v: Double): Double = ln(v) / ln(2.0)
}

/**
 * How many frame buffers the memory pool may hold (PLAN 12: merge memory cap ~1.2 GB on an 8 GB phone): a third
 * of what the system reports available, capped at [MAX_BUDGET_BYTES], minus the merge's own working set, halved
 * when the system says memory is low; never more than two full bursts plus their outputs.
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
