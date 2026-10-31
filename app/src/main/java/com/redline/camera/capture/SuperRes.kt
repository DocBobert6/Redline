package com.redline.camera.capture

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** What the shutter knows when it decides on super-resolution for a shot (B23). Pure data; no Android types. */
data class SuperResInputs(
    /** The zoom stop's target focal length (24, 28, 35, 50, 70). */
    val stopMm: Int,
    /** The super-resolution setting is on and its native self-check passed. */
    val enabled: Boolean,
    /** Long side of the stop's RAW crop in RAW pixels (0 = unknown). */
    val cropLongPx: Int,
    /** The B22 burst plan for this shot (null: burst merge off, the shot is a single frame). */
    val plan: BurstPlan?,
    /** Hand motion (gyroscope magnitude, rad/s, smoothed); null when there is no gyroscope. */
    val motionRadPerSec: Double?
)

/**
 * The shutter's super-resolution decision. [applies]: the shot is at a 50/70 mm stop with the setting on, so it is
 * saved at [outWidth] x [outHeight] (PLAN 5). [active]: a super-resolution merge of [frames] RAW frames; otherwise the
 * still is the B22 merge (or one frame) upscaled to that size, and [reason] says why.
 */
data class SuperResDecision(
    val applies: Boolean,
    val active: Boolean = false,
    val frames: Int = 0,
    val outWidth: Int = 0,
    val outHeight: Int = 0,
    val reason: String? = null
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "applies" to applies,
        "active" to active,
        "frames" to if (active) frames else null,
        "out_size" to if (applies) "${outWidth}x$outHeight" else null,
        "reason" to reason
    )
}

/**
 * B23: handheld multi-frame super-resolution at the 50 and 70 mm stops (PLAN 6 step 2). The burst planner's frame
 * count is raised to at least [MIN_FRAMES] (sub-pixel spread needs frames; at most [MAX_FRAMES], and no longer on the
 * sensor than the B22 limit); a hand held too still for sub-pixel shifts (a tripod) gets the B22 merge upscaled
 * instead, since the super-resolution gather then has nothing to add. 24/35 mm shots are untouched.
 */
object SuperResPlanner {
    val STOPS = setOf(50, 70)
    const val OUTPUT_WIDTH = 3000
    const val OUTPUT_HEIGHT = 2000
    const val MIN_FRAMES = 8
    const val MAX_FRAMES = 12

    /** Below this smoothed gyroscope magnitude the phone is treated as on a tripod (no sub-pixel spread to use). */
    const val STILL_RAD_S = 0.003

    fun decide(i: SuperResInputs): SuperResDecision {
        if (i.stopMm !in STOPS || !i.enabled) return SuperResDecision(applies = false)
        if (i.cropLongPx >= OUTPUT_WIDTH) return SuperResDecision(applies = false, reason = "crop already ${i.cropLongPx} px wide")
        fun upscale(why: String) = SuperResDecision(true, false, 0, OUTPUT_WIDTH, OUTPUT_HEIGHT, why)
        val p = i.plan ?: return upscale("burst merge off: one frame upscaled")
        if (!p.isBurst) return upscale("no burst (${p.singleReason ?: "single frame"}): one frame upscaled")
        val motion = i.motionRadPerSec
        if (motion != null && motion < STILL_RAD_S) return upscale("too still for sub-pixel shifts (tripod?): burst merge upscaled")
        val byDuration = floor(BurstPlanner.MAX_BURST_NS.toDouble() / max(p.frameDurationNs, 1L)).toInt()
        val n = min(min(max(p.frames, MIN_FRAMES), MAX_FRAMES), max(byDuration, p.frames))
        return SuperResDecision(true, true, n, OUTPUT_WIDTH, OUTPUT_HEIGHT, null)
    }

    /** The burst plan for an active decision: its frame count, and "superres" among the limits. */
    fun plan(p: BurstPlan, d: SuperResDecision): BurstPlan = if (!d.active || d.frames == p.frames) {
        if (d.active) p.copy(limits = p.limits + "superres") else p
    } else {
        p.copy(frames = d.frames, limits = p.limits + "superres")
    }
}

/** What one 50/70 mm shot's super-resolution step did (B23), for the report's "superres" section. */
data class SuperResShot(
    val stopMm: Int,
    val decision: SuperResDecision,
    /** True when the super-resolution merge was rendered; false: upscaled ([fallbackReason]). */
    val merged: Boolean,
    val fallbackReason: String? = null,
    val framesMerged: Int = 0,
    val reference: Int? = null,
    val outWidth: Int = 0,
    val outHeight: Int = 0,
    /** Sub-pixel spread of the burst: 0 (tripod) .. 1; the kernel widens below ~0.45. */
    val coverage: Double? = null,
    val sigmaG: Double? = null,
    val meanSubpixPx: Double? = null,
    val meanRobustness: Double? = null,
    val rejectedFraction: Double? = null,
    /** Mean fraction of tiles that took the frame's motion model (periodic detail, or a subject that moved). */
    val modelResolved: Double? = null,
    /** Largest hand-shake motion seen across the burst (RAW px). */
    val maxMotionPx: Double? = null,
    /** copy, reference, align (incl. LK + motion model), robust, gather, render, encode_save, total. */
    val timingsMs: Map<String, Long> = emptyMap(),
    val peakNativeMb: Double? = null,
    val outputMb: Double? = null,
    val queuedBehind: Int = 0,
    val stress: Boolean = false
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "stop_mm" to stopMm,
        "decision" to decision.toMap(),
        "merged" to merged,
        "fallback_reason" to fallbackReason,
        "frames_merged" to framesMerged,
        "reference" to reference,
        "out_size" to if (outWidth > 0) "${outWidth}x$outHeight" else null,
        "coverage" to coverage?.let { r3(it) },
        "sigma_g" to sigmaG?.let { r3(it) },
        "mean_subpix_px" to meanSubpixPx?.let { r3(it) },
        "mean_robustness" to meanRobustness?.let { r3(it) },
        "rejected_fraction" to rejectedFraction?.let { r3(it) },
        "model_resolved" to modelResolved?.let { r3(it) },
        "max_motion_px" to maxMotionPx?.let { r2(it) },
        "timings_ms" to timingsMs,
        "memory_mb" to linkedMapOf("peak_native" to peakNativeMb?.let { r1(it) }, "output" to outputMb?.let { r1(it) }),
        "queued_behind" to queuedBehind
    )
}

/**
 * The "superres" section of the capability report and the Info screen (B23). [available] is null while the start-up
 * self-check runs; false with a [reason] when super-resolution cannot run here (50/70 mm stills then keep the B22
 * path and their honest crop size). On by default only when the self-check passes.
 */
data class SuperResReport(
    val userSetting: Boolean = true,
    val available: Boolean? = null,
    val reason: String? = null,
    val selfCheckGain: Double? = null,
    val selfCheckFlowErrorPx: Double? = null,
    val selfCheckCoverage: Double? = null,
    val selfCheckMs: Double? = null,
    val selfCheckError: String? = null,
    val shots: Int = 0,
    val mergedShots: Int = 0,
    val upscaledShots: Int = 0,
    val upscaleReasons: Map<String, Int> = emptyMap(),
    /** stop mm -> (shots, max shutter-to-saved ms) for gate-eligible super-resolution shots (not stress, nothing queued ahead). */
    val gateStops: Map<Int, Pair<Int, Long>> = emptyMap(),
    val coverageTotal: Double = 0.0,
    val maxSaveMs: Long = 0,
    val peakNativeMb: Double = 0.0,
    val last: SuperResShot? = null,
    val recent: List<Map<String, Any?>> = emptyList()
) {
    val selfCheckPassed: Boolean
        get() = (selfCheckGain ?: 0.0) >= SELF_CHECK_MIN_GAIN &&
            (selfCheckFlowErrorPx ?: 1.0) <= SELF_CHECK_MAX_FLOW_ERROR_PX &&
            (selfCheckCoverage ?: 0.0) >= SELF_CHECK_MIN_COVERAGE

    val enabled: Boolean get() = userSetting && available == true

    fun whyOff(): String? = when {
        available == null -> "checking the native super-resolution merge..."
        available == false -> reason ?: "unavailable"
        !userSetting -> "turned off (50/70 mm stills keep the crop's own size)"
        else -> null
    }

    fun record(shot: SuperResShot, shutterToSavedMs: Long?, ok: Boolean): SuperResReport {
        var next = copy(
            shots = shots + 1,
            last = shot,
            recent = (
                recent + linkedMapOf(
                    "stop_mm" to shot.stopMm,
                    "merged" to shot.merged,
                    "frames" to shot.framesMerged,
                    "coverage" to shot.coverage?.let { r3(it) },
                    "fallback_reason" to (shot.fallbackReason ?: shot.decision.reason),
                    "total_ms" to shutterToSavedMs
                )
                ).takeLast(RECENT_SHOTS)
        )
        if (!shot.merged) {
            val why = shot.fallbackReason ?: shot.decision.reason ?: "?"
            return next.copy(upscaledShots = upscaledShots + 1, upscaleReasons = upscaleReasons + (why to (upscaleReasons[why] ?: 0) + 1))
        }
        val ms = shutterToSavedMs ?: 0
        next = next.copy(
            mergedShots = mergedShots + 1,
            coverageTotal = coverageTotal + (shot.coverage ?: 0.0),
            maxSaveMs = max(maxSaveMs, ms),
            peakNativeMb = maxOf(peakNativeMb, (shot.peakNativeMb ?: 0.0) + (shot.outputMb ?: 0.0))
        )
        if (!shot.stress && shot.queuedBehind == 0 && ok && shutterToSavedMs != null) {
            val prev = gateStops[shot.stopMm]
            next = next.copy(gateStops = gateStops + (shot.stopMm to ((prev?.first ?: 0) + 1 to max(prev?.second ?: 0, ms))))
        }
        return next
    }

    /** B23 gate: every gate-eligible super-resolution shot at 50 and at 70 mm saved in under 5 s; null until both have one. */
    val saveGate: Boolean?
        get() {
            val stops = GATE_STOPS.map { gateStops[it] }
            if (stops.any { it == null }) return null
            return stops.all { it!!.second < SAVE_GATE_MS }
        }

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "label" to LABEL,
        "enabled" to enabled,
        "user_setting" to userSetting,
        "available" to available,
        "reason" to whyOff(),
        "output_px" to "${SuperResPlanner.OUTPUT_WIDTH}x${SuperResPlanner.OUTPUT_HEIGHT}",
        "self_check" to linkedMapOf(
            "gain" to selfCheckGain?.let { r3(it) },
            "flow_error_px" to selfCheckFlowErrorPx?.let { r3(it) },
            "coverage" to selfCheckCoverage?.let { r3(it) },
            "ms" to selfCheckMs?.let { Math.round(it) },
            "passed" to if (selfCheckGain == null) null else selfCheckPassed,
            "error" to selfCheckError
        ),
        "shots" to shots,
        "merged_shots" to mergedShots,
        "upscaled_shots" to upscaledShots,
        "upscale_reasons" to upscaleReasons,
        "avg_coverage" to if (mergedShots == 0) null else r3(coverageTotal / mergedShots),
        "max_shutter_to_saved_ms" to if (mergedShots == 0) null else maxSaveMs,
        "peak_native_mb" to r1(peakNativeMb),
        "gate_shots_by_stop" to gateStops.entries.sortedBy { it.key }.associate {
            "${it.key}" to linkedMapOf("shots" to it.value.first, "max_shutter_to_saved_ms" to it.value.second)
        },
        "last" to last?.toMap(),
        "recent_shots" to recent,
        "gate" to linkedMapOf(
            "save_lt_5000_ms_50_70" to saveGate,
            "resolution_gain" to linkedMapOf(
                "self_check_gain" to selfCheckGain?.let { r3(it) },
                "passed" to if (selfCheckGain == null) null else selfCheckGain >= SELF_CHECK_MIN_GAIN,
                "note" to "synthetic (known sub-pixel shifts, grating on texture): error vs one frame; " +
                    "the chart gain on the device is measured from shared DNG bursts with calibration/tools/superres_quality.py"
            )
        )
    )

    companion object {
        const val LABEL = "Handheld super-resolution (B23): 8-12 RAW frames at 50/70 mm gathered onto a 3000x2000 grid"
        const val RECENT_SHOTS = 8
        const val SAVE_GATE_MS = 5_000L
        val GATE_STOPS = listOf(50, 70)

        /** Self-check pass marks: see superres.cpp superResSelfCheck (measured 1.65 / 0.05 px / 0.86 on the host). */
        const val SELF_CHECK_MIN_GAIN = 1.15
        const val SELF_CHECK_MAX_FLOW_ERROR_PX = 0.15
        const val SELF_CHECK_MIN_COVERAGE = 0.4
    }
}

private fun r1(v: Double): Double = Math.round(v * 10.0) / 10.0

private fun r2(v: Double): Double = Math.round(v * 100.0) / 100.0

private fun r3(v: Double): Double = Math.round(v * 1000.0) / 1000.0
