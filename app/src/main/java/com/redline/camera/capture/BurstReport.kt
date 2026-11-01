package com.redline.camera.capture

import com.redline.camera.viewfinder.PreviewLoadReading
import kotlin.math.max

/**
 * B22a: how one burst streamed through the merge. [framesMergedDuringCapture]: frames merged before the last frame
 * arrived; [maxBacklog]: most frames copied but not yet merged; [maxResidentFrames]: most pool buffers the burst
 * held at once; [mergeTailMs]: last frame in to merge finished; [peakResidentMb]: the burst's pool buffers at their
 * peak plus the merger's own working set.
 */
data class BurstStreaming(
    val framesMergedDuringCapture: Int = 0,
    val maxBacklog: Int = 0,
    val maxResidentFrames: Int = 0,
    val maxHeldByMerger: Int = 0,
    val threads: Int = 0,
    val mergeTailMs: Long = 0,
    val peakNativeMb: Double = 0.0,
    val peakResidentMb: Double = 0.0
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "frames_merged_during_capture" to framesMergedDuringCapture,
        "max_backlog_frames" to maxBacklog,
        "max_resident_frames" to maxResidentFrames,
        "max_held_by_merger" to maxHeldByMerger,
        "worker_threads" to threads,
        "merge_tail_ms" to mergeTailMs,
        "peak_native_mb" to r1(peakNativeMb),
        "peak_resident_mb" to r1(peakResidentMb)
    )
}

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
    /** Shots still capturing, merging or saving when this one was pressed (0 = unhurried: nothing ahead of it). */
    val queuedBehind: Int = 0,
    val stress: Boolean = false,
    /** B22a: "burst", "single_raw" (B21 single frame) or "camera_jpeg" (the cheapest path; [plan] says why). */
    val path: String = if (plan.isBurst) PATH_BURST else PATH_SINGLE,
    val streaming: BurstStreaming? = null,
    /** B22b: press-to-file timeline ([ShotTimeline.toMap]): per-frame capture, RAW arrival, result, merge and save times. */
    val timeline: Map<String, Any?>? = null,
    /** B23b: time the shot waited for the render lane plus for the store lane (queued, not working), ms. */
    val saveWaitMs: Long? = null
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
        "queued_behind" to queuedBehind,
        "path" to path,
        "streaming" to streaming?.toMap(),
        "save_wait_ms" to saveWaitMs,
        "timeline" to timeline
    )

    /** One line of the report's recent-shots list: frames planned against the light level, and how it went. */
    fun brief(totalMs: Long?): Map<String, Any?> = linkedMapOf(
        "stop_mm" to stopMm,
        "path" to path,
        "light" to plan.light,
        "iso" to if (plan.isBurst) plan.iso else null,
        "frames_planned" to plan.frames,
        "frames_merged" to framesMerged,
        "single_reason" to plan.singleReason,
        "queued_behind" to queuedBehind,
        "shutter_release_ms" to timingsMs["shutter_release"],
        "capture_ms" to timingsMs["capture"],
        "merge_tail_ms" to timingsMs["merge_tail"],
        "save_wait_ms" to saveWaitMs,
        "total_ms" to totalMs
    )

    companion object {
        const val PATH_BURST = "burst"
        const val PATH_SINGLE = "single_raw"
        const val PATH_JPEG = "camera_jpeg"
    }
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
    val stressRunning: Boolean = false,
    /** B22a: merge worker threads and their nice value. */
    val workerThreads: Int? = null,
    val workerNice: Int? = null,
    /** B22a: light level -> (shots, frames planned in total, fewest, most) for burst shots. */
    val framesByLight: Map<String, List<Int>> = emptyMap(),
    /** B22a: merged shots that queued behind another shot (excluded from the save gate): count, total and max ms. */
    val queuedShots: Int = 0,
    val queuedTotalMs: Long = 0,
    val queuedMaxMs: Long = 0,
    val cameraJpegShots: Int = 0,
    val streamFramesMergedDuringCapture: Int = 0,
    val streamMaxBacklog: Int = 0,
    val streamMaxResidentFrames: Int = 0,
    val streamTailTotalMs: Long = 0,
    val peakResidentMb: Double = 0.0,
    val peakNativeMb: Double = 0.0,
    val recent: List<Map<String, Any?>> = emptyList(),
    /** B22b: presses not taken because [PressPolicy.MAX_WAITING_PRESSES] were already waiting (counted as blocked too). */
    val refusedPresses: Int = 0,
    /** B22b: presses / shots dropped without a capture failure (left the viewfinder, session rebuilt), by reason. */
    val droppedShots: Int = 0,
    val dropReasons: Map<String, Int> = emptyMap(),
    /** B22b: timelines of the latest shots ([ShotTimeline.toMap]), newest last. */
    val recentTimelines: List<Map<String, Any?>> = emptyList(),
    /** B23b: rapid-fire runs (camera-JPEG fallbacks, RAW singles, save waits, preview fps while the queue drains). */
    val rapidFire: RapidFire = RapidFire()
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

    /** B22b: a press that ended without a shot: refused (queue full) or dropped ([why]); never a capture failure. */
    fun drop(why: String, refused: Boolean): BurstReport = if (refused) {
        copy(refusedPresses = refusedPresses + 1)
    } else {
        copy(droppedShots = droppedShots + 1, dropReasons = dropReasons + (why to (dropReasons[why] ?: 0) + 1))
    }

    fun queue(depth: Int): BurstReport = copy(
        rapidFire = rapidFire.queue(depth),
        queueDepth = depth,
        maxQueueDepth = max(maxQueueDepth, depth),
        stressMaxQueue = if (stressRunning) max(stressMaxQueue, depth) else stressMaxQueue
    )

    fun record(shot: BurstShot, shutterToSavedMs: Long?, ok: Boolean): BurstReport {
        val ms = shutterToSavedMs ?: 0
        val stress = shot.stress
        var next = copy(
            rapidFire = rapidFire.shot(shot.path, jpegReason(shot), shot.saveWaitMs),
            framesRequested = framesRequested + shot.plan.frames,
            framesCaptured = framesCaptured + shot.framesCaptured,
            framesMerged = framesMerged + shot.framesMerged,
            last = shot,
            stressDone = stressDone + if (stress) 1 else 0,
            stressFailures = stressFailures + if (stress && !ok) 1 else 0,
            recent = (recent + shot.brief(shutterToSavedMs)).takeLast(RECENT_SHOTS),
            recentTimelines = shot.timeline?.let { t ->
                (
                    recentTimelines + (
                        linkedMapOf<String, Any?>(
                            "path" to shot.path,
                            "queued_behind" to shot.queuedBehind
                        ) + t
                        )
                    ).takeLast(RECENT_TIMELINES)
            } ?: recentTimelines
        )
        if (!shot.plan.isBurst) {
            val why = shot.plan.singleReason ?: "?"
            return next.copy(
                singleShots = singleShots + 1,
                singleReasons = singleReasons + (why to (singleReasons[why] ?: 0) + 1),
                cameraJpegShots = cameraJpegShots + if (shot.path == BurstShot.PATH_JPEG) 1 else 0
            )
        }
        shot.plan.light?.let { light ->
            val prev = framesByLight[light]
            val f = shot.plan.frames
            next = next.copy(
                framesByLight = framesByLight + (
                    light to if (prev == null) {
                        listOf(1, f, f, f)
                    } else {
                        listOf(prev[0] + 1, prev[1] + f, minOf(prev[2], f), maxOf(prev[3], f))
                    }
                    )
            )
        }
        shot.streaming?.let { st ->
            next = next.copy(
                streamFramesMergedDuringCapture = next.streamFramesMergedDuringCapture + st.framesMergedDuringCapture,
                streamMaxBacklog = max(next.streamMaxBacklog, st.maxBacklog),
                streamMaxResidentFrames = max(next.streamMaxResidentFrames, st.maxResidentFrames),
                streamTailTotalMs = next.streamTailTotalMs + st.mergeTailMs,
                peakResidentMb = maxOf(next.peakResidentMb, st.peakResidentMb),
                peakNativeMb = maxOf(next.peakNativeMb, st.peakNativeMb)
            )
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
        } else if (shot.queuedBehind > 0 && ok && shutterToSavedMs != null) {
            next = next.copy(queuedShots = queuedShots + 1, queuedTotalMs = queuedTotalMs + ms, queuedMaxMs = max(queuedMaxMs, ms))
        }
        return next
    }

    /** B23b: a warm preview reading ([fps] rolling rate, [droppedTotal] the viewfinder's cumulative dropped frames). */
    fun preview(fps: Double?, droppedTotal: Int?): BurstReport = copy(rapidFire = rapidFire.preview(fps, droppedTotal))

    /** B23b.1: a reading of the viewfinder's under-load meter (see [RapidFire.load]). */
    fun load(r: PreviewLoadReading): BurstReport = copy(
        rapidFire = rapidFire.load(r.fps, r.dropped, r.pauses, r.pauseMs, r.warmupFrames, r.recentResumes.map { it.toMap() })
    )

    /** B23b.1: the native worker read-back for the report. */
    fun nativeWorkers(v: IntArray?): BurstReport = RapidFire.nativeWorkerMap(v)?.let { copy(rapidFire = rapidFire.copy(nativeWorkers = it)) } ?: this

    /** B23b: why a shot was the camera JPEG: the router's reason (the last "...: camera JPEG" part of the plan's reason). */
    private fun jpegReason(shot: BurstShot): String? {
        if (shot.path != BurstShot.PATH_JPEG) return null
        val why = shot.plan.singleReason ?: return null
        return why.split("; ").lastOrNull { it.endsWith("camera JPEG") } ?: why
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
        "memory" to linkedMapOf(
            "pool_frames" to poolFrames,
            "frame_mb" to frameMb?.let { r2(it) },
            "peak_resident_mb" to r1(peakResidentMb),
            "peak_native_mb" to r1(peakNativeMb),
            "max_resident_frames_per_burst" to streamMaxResidentFrames
        ),
        "worker" to linkedMapOf("threads" to workerThreads, "nice" to workerNice),
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
        "queued_shots" to linkedMapOf(
            "shots" to queuedShots,
            "avg_shutter_to_saved_ms" to if (queuedShots == 0) null else queuedTotalMs / queuedShots,
            "max_shutter_to_saved_ms" to if (queuedShots == 0) null else queuedMaxMs
        ),
        "camera_jpeg_shots" to cameraJpegShots,
        "frames_by_light" to framesByLight.entries.sortedBy { lightOrder(it.key) }.associate { (k, v) ->
            k to linkedMapOf("shots" to v[0], "avg_frames" to r2(v[1].toDouble() / v[0]), "min_frames" to v[2], "max_frames" to v[3])
        },
        "streaming" to linkedMapOf(
            "frames_merged_during_capture" to streamFramesMergedDuringCapture,
            "max_backlog_frames" to streamMaxBacklog,
            "max_resident_frames" to streamMaxResidentFrames,
            "avg_merge_tail_ms" to if (mergedShots == 0) null else streamTailTotalMs / mergedShots
        ),
        "recent_shots" to recent,
        "queue_depth" to queueDepth,
        "max_queue_depth" to maxQueueDepth,
        "shutter_presses" to presses,
        "shutter_blocked" to shutterBlocked,
        "shutter_waits" to shutterWaits,
        "max_shutter_wait_ms" to maxShutterWaitMs,
        "refused_presses_queue_full" to refusedPresses,
        "dropped_shots" to droppedShots,
        "drop_reasons" to dropReasons,
        "max_waiting_presses" to PressPolicy.MAX_WAITING_PRESSES,
        "last" to last?.toMap(),
        "recent_timelines" to recentTimelines,
        "rapid_fire" to rapidFire.toMap(),
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
            "no_shutter_blocking" to shutterGate,
            "no_camera_jpeg_in_15_taps" to rapidFire.noCameraJpegGate,
            "preview_fps_min_ge_29_5_under_load" to rapidFire.previewFpsGate
        )
    )

    companion object {
        const val LABEL =
            "RAW burst merge (B22b): 4-12 under-exposed RAW frames by light, align + merge streamed from the first frame, background queue"
        const val RECENT_SHOTS = 12
        const val RECENT_TIMELINES = 4

        private fun lightOrder(light: String): Int =
            BurstPlanner.LIGHT_LEVELS.indexOfFirst { it.first == light }.let { if (it < 0) BurstPlanner.LIGHT_LEVELS.size else it }
        const val SAVE_GATE_MS = 3_000L
        val GATE_STOPS = listOf(24, 35)

        /** A press held longer than this behind the previous capture counts as blocked (no press is ever refused). */
        const val SHUTTER_BLOCK_MS = 500L
        const val MIN_PRESSES = 10
        const val STRESS_SHOTS = 30

        /** Self-check pass marks (synthetic 6-frame burst with known shifts): see burst_merge.cpp burstSelfCheck. */
        const val SELF_CHECK_MIN_ALIGNED = 0.95
        const val SELF_CHECK_MIN_NOISE_RATIO = 2.0
    }
}

private fun r1(v: Double): Double = Math.round(v * 10.0) / 10.0

private fun r2(v: Double): Double = Math.round(v * 100.0) / 100.0

private fun r3(v: Double): Double = Math.round(v * 1000.0) / 1000.0
