package com.redline.camera.capture

import kotlin.math.max

/** What one shot's burst step did (B22): planned, captured and merged frames, alignment and stage timings. */
data class BurstShot(
    val plan: BurstPlan,
    val stopMm: Int,
    val framesCaptured: Int = 0,
    val framesMerged: Int = 0,
    val reference: Int? = null,
    /** True when the merged frame was rendered; false = single frame (plan) or a fallback ([fallbackReason]). */
    val merged: Boolean = false,
    val fallbackReason: String? = null,
    val noiseModel: String? = null,
    val meanDispPx: Double? = null,
    val maxDispPx: Double? = null,
    val meanWeight: Double? = null,
    val rejectedFraction: Double? = null,
    /** Stage timings in ms: capture (shutter to last frame in), queue_wait, pyramid, reference, align, merge, finish, render, encode_save, dng, total. */
    val timingsMs: Map<String, Long> = emptyMap(),
    /** Jobs ahead of this one when it was queued (0 = processed straight away). */
    val queuedBehind: Int = 0,
    val stress: Boolean = false
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "plan" to plan.toMap(),
        "stop_mm" to stopMm,
        "frames_requested" to plan.frames,
        "frames_captured" to framesCaptured,
        "frames_merged" to framesMerged,
        "reference" to reference,
        "merged" to merged,
        "fallback_reason" to fallbackReason,
        "noise_model" to noiseModel,
        "alignment" to linkedMapOf(
            "mean_disp_px" to meanDispPx?.let { r2(it) },
            "max_disp_px" to maxDispPx?.let { r2(it) },
            "mean_tile_weight" to meanWeight?.let { r3(it) },
            "rejected_tile_fraction" to rejectedFraction?.let { r3(it) }
        ),
        "timings_ms" to timingsMs,
        "queued_behind" to queuedBehind
    )
}

/**
 * The "burst" section of the capability report and the Info screen (B22). [available] is null while the start-up
 * self-check runs, false with a [reason] when burst merge cannot run here (the still then takes the B21
 * single-frame path).
 */
data class BurstReport(
    val userSetting: Boolean = true,
    val available: Boolean? = null,
    val reason: String? = null,
    val selfCheckAligned: Double? = null,
    val selfCheckNoiseRatio: Double? = null,
    val selfCheckMs: Double? = null,
    val selfCheckError: String? = null,
    val poolFrames: Int? = null,
    val frameMb: Double? = null,
    val mergedShots: Int = 0,
    val singleShots: Int = 0,
    val singleReasons: Map<String, Int> = emptyMap(),
    val fallbacks: Int = 0,
    val framesRequested: Int = 0,
    val framesCaptured: Int = 0,
    val framesMerged: Int = 0,
    val totalSaveMs: Long = 0,
    val maxSaveMs: Long = 0,
    val stageTotals: Map<String, Long> = emptyMap(),
    val stageMax: Map<String, Long> = emptyMap(),
    /** stop mm -> (shots, max shutter-to-saved ms) for gate-eligible merged shots (not stress, nothing queued ahead). */
    val gateStops: Map<Int, Pair<Int, Long>> = emptyMap(),
    val queueDepth: Int = 0,
    val maxQueueDepth: Int = 0,
    val presses: Int = 0,
    val shutterBlocked: Int = 0,
    val shutterWaits: Int = 0,
    val maxShutterWaitMs: Long = 0,
    val last: BurstShot? = null,
    val stressRequested: Int = 0,
    val stressDone: Int = 0,
    val stressFailures: Int = 0,
    val stressBlocked: Int = 0,
    val stressMaxQueue: Int = 0,
    val stressRunning: Boolean = false
) {
    val enabled: Boolean get() = userSetting && available == true

    fun whyOff(): String? = when {
        available == null -> "checking the native burst merge..."
        available == false -> reason ?: "unavailable"
        !userSetting -> "turned off (stills use the single-frame RAW path)"
        else -> null
    }

    /** A shutter press: [waitMs] how long it waited for the camera (a press queued behind a capture), [blocked] refused or late. */
    fun press(waitMs: Long?, blocked: Boolean, stress: Boolean): BurstReport = copy(
        presses = presses + 1,
        shutterBlocked = shutterBlocked + if (blocked) 1 else 0,
        shutterWaits = shutterWaits + if ((waitMs ?: 0) > 0) 1 else 0,
        maxShutterWaitMs = max(maxShutterWaitMs, waitMs ?: 0),
        stressBlocked = stressBlocked + if (stress && blocked) 1 else 0
    )

    fun queue(depth: Int): BurstReport = copy(
        queueDepth = depth,
        maxQueueDepth = max(maxQueueDepth, depth),
        stressMaxQueue = if (stressRunning) max(stressMaxQueue, depth) else stressMaxQueue
    )

    fun record(shot: BurstShot, shutterToSavedMs: Long?, ok: Boolean): BurstReport {
        val ms = shutterToSavedMs ?: 0
        val stress = shot.stress
        var next = copy(
            framesRequested = framesRequested + shot.plan.frames,
            framesCaptured = framesCaptured + shot.framesCaptured,
            framesMerged = framesMerged + shot.framesMerged,
            last = shot,
            stressDone = stressDone + if (stress) 1 else 0,
            stressFailures = stressFailures + if (stress && !ok) 1 else 0
        )
        if (!shot.plan.isBurst) {
            val why = shot.plan.singleReason ?: "?"
            return next.copy(singleShots = singleShots + 1, singleReasons = singleReasons + (why to (singleReasons[why] ?: 0) + 1))
        }
        if (!shot.merged) return next.copy(fallbacks = fallbacks + 1)
        val totals = stageTotals.toMutableMap()
        val maxes = stageMax.toMutableMap()
        shot.timingsMs.forEach { (k, v) ->
            totals[k] = (totals[k] ?: 0) + v
            maxes[k] = max(maxes[k] ?: 0, v)
        }
        next = next.copy(
            mergedShots = mergedShots + 1,
            totalSaveMs = totalSaveMs + ms,
            maxSaveMs = max(maxSaveMs, ms),
            stageTotals = totals,
            stageMax = maxes
        )
        if (!stress && shot.queuedBehind == 0 && ok && shutterToSavedMs != null) {
            val prev = gateStops[shot.stopMm]
            val entry = (prev?.first ?: 0) + 1 to max(prev?.second ?: 0, ms)
            next = next.copy(gateStops = gateStops + (shot.stopMm to entry))
        }
        return next
    }

    /** B22 gate: every gate-eligible merged shot at 24 and at 35 mm saved in under 3 s; null until both stops have one. */
    val saveGate: Boolean?
        get() {
            val stops = GATE_STOPS.map { gateStops[it] }
            if (stops.any { it == null }) return null
            return stops.all { it!!.second < SAVE_GATE_MS }
        }

    /** B22 gate: no shutter press refused or held > [SHUTTER_BLOCK_MS]; null until [MIN_PRESSES] presses. */
    val shutterGate: Boolean? get() = if (presses < MIN_PRESSES) null else shutterBlocked == 0

    fun overlay(): String? = if (queueDepth > 0) "Processing $queueDepth" else null

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "label" to LABEL,
        "enabled" to enabled,
        "user_setting" to userSetting,
        "available" to available,
        "reason" to whyOff(),
        "self_check" to linkedMapOf(
            "aligned_fraction" to selfCheckAligned?.let { r3(it) },
            "noise_ratio" to selfCheckNoiseRatio?.let { r3(it) },
            "ms" to selfCheckMs?.let { Math.round(it) },
            "error" to selfCheckError
        ),
        "memory" to linkedMapOf("pool_frames" to poolFrames, "frame_mb" to frameMb?.let { r2(it) }),
        "merged_shots" to mergedShots,
        "single_shots" to singleShots,
        "single_reasons" to singleReasons,
        "fallbacks" to fallbacks,
        "frames_requested" to framesRequested,
        "frames_captured" to framesCaptured,
        "frames_merged" to framesMerged,
        "avg_shutter_to_saved_ms" to if (mergedShots == 0) null else totalSaveMs / mergedShots,
        "max_shutter_to_saved_ms" to if (mergedShots == 0) null else maxSaveMs,
        "stage_avg_ms" to if (mergedShots == 0) null else stageTotals.mapValues { it.value / mergedShots },
        "stage_max_ms" to if (mergedShots == 0) null else stageMax,
        "gate_shots_by_stop" to gateStops.entries.sortedBy { it.key }.associate {
            "${it.key}" to linkedMapOf("shots" to it.value.first, "max_shutter_to_saved_ms" to it.value.second)
        },
        "queue_depth" to queueDepth,
        "max_queue_depth" to maxQueueDepth,
        "shutter_presses" to presses,
        "shutter_blocked" to shutterBlocked,
        "shutter_waits" to shutterWaits,
        "max_shutter_wait_ms" to maxShutterWaitMs,
        "last" to last?.toMap(),
        "stress" to linkedMapOf(
            "requested" to stressRequested,
            "done" to stressDone,
            "failures" to stressFailures,
            "blocked" to stressBlocked,
            "max_queue_depth" to stressMaxQueue,
            "running" to stressRunning
        ),
        "gate" to linkedMapOf(
            "save_lt_3000_ms_24_35" to saveGate,
            "no_shutter_blocking" to shutterGate
        )
    )

    companion object {
        const val LABEL = "RAW burst merge (B22): 8-12 under-exposed RAW frames, tile align + merge, background queue"
        const val SAVE_GATE_MS = 3_000L
        val GATE_STOPS = listOf(24, 35)

        /** A press held longer than this behind the previous capture counts as blocked. */
        const val SHUTTER_BLOCK_MS = 500L
        const val MIN_PRESSES = 10
        const val STRESS_SHOTS = 30

        /** Self-check pass marks (synthetic 6-frame burst with known shifts): see burst_merge.cpp burstSelfCheck. */
        const val SELF_CHECK_MIN_ALIGNED = 0.95
        const val SELF_CHECK_MIN_NOISE_RATIO = 2.0
    }
}

private fun r2(v: Double): Double = Math.round(v * 100.0) / 100.0

private fun r3(v: Double): Double = Math.round(v * 1000.0) / 1000.0
