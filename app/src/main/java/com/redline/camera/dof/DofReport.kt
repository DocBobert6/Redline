package com.redline.camera.dof

/** B31: one still's simulated depth of field: applied (and how) or why the still was saved sharp. */
data class DofShot(
    val base: String,
    val applied: Boolean,
    /** Why it was saved sharp (null when applied). */
    val reason: String? = null,
    val aperture: Float,
    val focalMm: Double? = null,
    val focusDistanceM: Double? = null,
    val focusSource: String? = null,
    val focusPoint: String? = null,
    val focusX: Double? = null,
    val focusY: Double? = null,
    /** B40: the tap (0..1 of the still) and what the tap snap compared (relative depth, nearer larger). */
    val tapXy: Pair<Double, Double>? = null,
    val tapDepth: Double? = null,
    val personDepth: Double? = null,
    val personGapDiag: Double? = null,
    val phoneEquivalentAperture: Double? = null,
    val confidence: Double? = null,
    val depthMs: Long? = null,
    val bokehMs: Long? = null,
    /** The whole DOF stage on the save path (depth wait + sharp copy + bokeh + gain map). */
    val stageMs: Long? = null,
    val shutterToSavedMs: Long? = null,
    val maxRadiusPx: Double? = null,
    val threads: Int? = null,
    val nice: Int? = null,
    val sharpCopy: Boolean = false,
    /** Ultra HDR handling: "recomputed from the blurred result", "no gain map", or why it was dropped. */
    val gainMap: String? = null,
    val delegate: String? = null,
    /** B31.1: the shot went to the background DOF queue (its sharp photo saved first). */
    val queued: Boolean = false,
    /** B31.1: sharp photo saved to DOF work started (waiting for an idle pipeline and the shots ahead). */
    val queueWaitMs: Long? = null,
    /** B31.1: shutter to the photo in MediaStore holding its final (blurred) pixels; null when it stayed sharp. */
    val timeToFinalMs: Long? = null,
    /** B31.1: the saved photo was replaced in place with the blurred render. */
    val replaced: Boolean = false,
    val replaceMs: Long? = null,
    val replaceError: String? = null,
    /** B31.1: the bokeh's own stage timings (ms) and where it ran. */
    val stagesMs: Map<String, Long>? = null,
    val cpus: List<Int>? = null,
    val workerNice: Int? = null,
    val bigCores: Boolean? = null,
    /** B31.1: the viewfinder was showing when the DOF work ran (throttled budget). */
    val viewfinderActive: Boolean? = null,
    /** B31.1: lowest preview fps seen while this shot's DOF work ran. */
    val previewFpsMin: Double? = null
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "base" to base,
        "applied" to applied,
        "skip_reason" to reason,
        "aperture" to r1(aperture.toDouble()),
        "focal_mm" to focalMm?.let { r1(it) },
        "focus_distance_m" to focusDistanceM?.let { r3(it) },
        "focus_distance_source" to focusSource,
        "focus_point" to focusPoint,
        "focus_xy" to if (focusX != null && focusY != null) listOf(r3(focusX), r3(focusY)) else null,
        "tap_xy" to tapXy?.let { listOf(r3(it.first), r3(it.second)) },
        "tap_depth" to tapDepth?.let { r3(it) },
        "person_depth_near_tap" to personDepth?.let { r3(it) },
        "person_gap_diag" to personGapDiag?.let { r3(it) },
        "phone_equivalent_aperture" to phoneEquivalentAperture?.let { r1(it) },
        "confidence" to confidence?.let { r3(it) },
        "depth_ms" to depthMs,
        "bokeh_ms" to bokehMs,
        "dof_stage_ms" to stageMs,
        "shutter_to_saved_ms" to shutterToSavedMs,
        "max_radius_px" to maxRadiusPx?.let { r1(it) },
        "threads" to threads,
        "nice" to nice,
        "sharp_copy" to sharpCopy,
        "gain_map" to gainMap,
        "delegate" to delegate,
        "queued" to queued,
        "queue_wait_ms" to queueWaitMs,
        "time_to_final_ms" to timeToFinalMs,
        "replaced" to replaced,
        "replace_ms" to replaceMs,
        "replace_error" to replaceError,
        "bokeh_stages_ms" to stagesMs,
        "cpus" to cpus,
        "worker_nice" to workerNice,
        "big_cores" to bigCores,
        "viewfinder_active" to viewfinderActive,
        "preview_fps_min" to previewFpsMin?.let { Math.round(it * 10.0) / 10.0 }
    )
}

/** B31: one re-edit (a new photo rendered from a stored sharp copy + depth). */
data class DofReedit(
    val base: String,
    val ok: Boolean,
    val aperture: Float,
    val focusX: Double,
    val focusY: Double,
    val applied: Boolean = false,
    val reason: String? = null,
    val bokehMs: Long? = null,
    val totalMs: Long? = null,
    val savedAs: String? = null
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "base" to base,
        "ok" to ok,
        "aperture" to r1(aperture.toDouble()),
        "focus_xy" to listOf(r3(focusX), r3(focusY)),
        "applied" to applied,
        "reason" to reason,
        "bokeh_ms" to bokehMs,
        "total_ms" to totalMs,
        "saved_as" to savedAs
    )
}

/**
 * B31: the report's "dof" section: the setting and aperture, per-shot applied/skip records, totals, and the lowest
 * preview fps seen while depth or bokeh work was running. B31.1: DOF runs on a background queue after the sharp photo is
 * saved (time to final, replaced in place, queue depth and overflow); the fps window is the background job.
 */
data class DofReport(
    val enabled: Boolean = DEFAULT_ON,
    val aperture: Float = DofMath.DEFAULT_APERTURE,
    val shots: Int = 0,
    val applied: Int = 0,
    val skips: Map<String, Int> = emptyMap(),
    val maxBokehMs: Long = 0,
    val totalBokehMs: Long = 0,
    val maxShutterToSavedMs: Long = 0,
    val recent: List<DofShot> = emptyList(),
    val previewFpsMin: Double? = null,
    val previewFpsSamples: Int = 0,
    val reedits: List<DofReedit> = emptyList(),
    val vignetteNote: String = VIGNETTE_NOTE,
    /** B31.1: shots handed to the background queue, replaced in place, refused because the queue was full. */
    val queued: Int = 0,
    val replaced: Int = 0,
    val overflow: Int = 0,
    val maxQueueDepth: Int = 0,
    val queueDepth: Int = 0,
    val maxTimeToFinalMs: Long = 0,
    val totalTimeToFinalMs: Long = 0,
    /** B31.1: the sharp save (shutter to saved) of every shot DOF was on for: never waits for DOF. */
    val maxSharpSaveMs: Long = 0
) {
    fun record(s: DofShot): DofReport {
        val key = s.reason?.let { skipKey(it) }
        return copy(
            shots = shots + 1,
            applied = applied + if (s.applied) 1 else 0,
            skips = if (key == null) skips else skips + (key to (skips[key] ?: 0) + 1),
            maxBokehMs = if (s.applied) maxOf(maxBokehMs, s.bokehMs ?: 0) else maxBokehMs,
            totalBokehMs = totalBokehMs + if (s.applied) s.bokehMs ?: 0 else 0,
            maxShutterToSavedMs = if (s.applied) maxOf(maxShutterToSavedMs, s.shutterToSavedMs ?: 0) else maxShutterToSavedMs,
            recent = (recent + s).takeLast(RECENT),
            replaced = replaced + if (s.replaced) 1 else 0,
            maxTimeToFinalMs = if (s.replaced) maxOf(maxTimeToFinalMs, s.timeToFinalMs ?: 0) else maxTimeToFinalMs,
            totalTimeToFinalMs = totalTimeToFinalMs + if (s.replaced) s.timeToFinalMs ?: 0 else 0,
            maxSharpSaveMs = maxOf(maxSharpSaveMs, s.shutterToSavedMs ?: 0)
        )
    }

    /** B31.1: a shot went to the background queue ([depth] = jobs waiting or running after it). */
    fun enqueued(depth: Int): DofReport = copy(queued = queued + 1, queueDepth = depth, maxQueueDepth = maxOf(maxQueueDepth, depth))

    /** B31.1: the queue's depth changed (a job started or finished). */
    fun depth(d: Int): DofReport = copy(queueDepth = d)

    /** B31.1: a shot the full queue could not take (saved sharp, recorded with the reason). */
    fun overflowed(): DofReport = copy(overflow = overflow + 1)

    /** A preview fps reading taken while DOF work was running. */
    fun fps(v: Double): DofReport =
        copy(previewFpsMin = previewFpsMin?.let { minOf(it, v) } ?: v, previewFpsSamples = previewFpsSamples + 1)

    fun reedit(r: DofReedit): DofReport = copy(reedits = (reedits + r).takeLast(RECENT))

    /** The Info card's one line. */
    fun statusLine(): String {
        if (!enabled) return "Off (stills saved sharp)"
        val head = "On · ${DofMath.label(aperture)}"
        val last = recent.lastOrNull() ?: return "$head · no shots yet"
        val tail = if (last.applied) {
            (
                "last applied: depth ${last.depthMs ?: 0} ms, bokeh ${last.bokehMs ?: 0} ms, " +
                    "confidence %.2f, sharp saved in %d ms, final in %d ms"
                ).format(
                last.confidence ?: 0.0,
                last.shutterToSavedMs ?: 0,
                last.timeToFinalMs ?: 0
            )
        } else {
            "last saved sharp: ${last.reason}"
        }
        return "$head · $applied/$shots applied · $tail"
    }

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "enabled" to enabled,
        "aperture" to r1(aperture.toDouble()),
        "default" to "on at ${DofMath.label(DofMath.DEFAULT_APERTURE)}; auto-off below confidence 0.35 (saved sharp)",
        "shots" to shots,
        "applied" to applied,
        "skips" to skips,
        "avg_bokeh_ms" to if (applied > 0) totalBokehMs / applied else null,
        "max_bokeh_ms" to if (applied > 0) maxBokehMs else null,
        "max_shutter_to_saved_ms_applied" to if (applied > 0) maxShutterToSavedMs else null,
        "on_save_path" to false,
        "save_path_note" to SAVE_PATH_NOTE,
        "max_sharp_shutter_to_saved_ms" to if (shots > 0) maxSharpSaveMs else null,
        "queued" to queued,
        "replaced" to replaced,
        "queue_overflow" to overflow,
        "queue_depth" to queueDepth,
        "max_queue_depth" to maxQueueDepth,
        "queue_capacity" to QUEUE_CAPACITY,
        "avg_time_to_final_ms" to if (replaced > 0) totalTimeToFinalMs / replaced else null,
        "max_time_to_final_ms" to if (replaced > 0) maxTimeToFinalMs else null,
        "preview_fps_min_during_dof" to previewFpsMin?.let { Math.round(it * 10.0) / 10.0 },
        "preview_fps_samples_during_dof" to previewFpsSamples,
        "vignette" to vignetteNote,
        "status" to statusLine(),
        "last" to recent.lastOrNull()?.toMap(),
        "recent_shots" to recent.map { it.toMap() },
        "reedits" to reedits.map { it.toMap() }
    )

    companion object {
        const val DEFAULT_ON = true
        const val RECENT = 10
        const val VIGNETTE_NOTE = "B24 vignette has no aperture input (one f/2.8 table per stop); the dial does not change it"

        const val SKIP_RAPID_FIRE = "rapid fire"
        const val SKIP_MEMORY = "memory pressure"
        const val SKIP_NOT_KEPT = "not kept (stress shot)"

        /** B31.1: the B22 burst stress run (30 shots, files deleted) and the B13 500-shot run, told apart. */
        const val SKIP_BURST_STRESS = "burst stress run shot (not kept)"
        const val SKIP_B13_STRESS = "B13 stress run shot (not kept)"
        const val SKIP_QUEUE_FULL = "DOF queue full"
        const val SKIP_NO_PHOTO = "photo not saved"
        const val QUEUE_CAPACITY = 4
        const val SAVE_PATH_NOTE =
            "the sharp photo is saved first (shutter to saved never waits for DOF); depth + bokeh run on the background DOF " +
                "queue when the capture pipeline is idle and the same MediaStore item is rewritten in place"

        /** B31.1: why a not-kept shot gets no DOF ([stress]: the B22 burst stress run, else the B13 run). */
        fun notKeptReason(stress: Boolean): String = if (stress) SKIP_BURST_STRESS else SKIP_B13_STRESS
        const val SKIP_NO_TONE = "no tone curve from the render"
        const val SKIP_NO_LENS = "no lens data (aperture / sensor size)"
        const val SKIP_ENGINE = "depth engine not ready"
        const val SKIP_BUSY = "depth engine busy"
        const val SKIP_DEPTH_FAILED = "depth failed"
        const val SKIP_ERROR = "bokeh error"

        /** The skip table's key: the reason up to its first " (" or ":" (details stay in the shot record). */
        fun skipKey(reason: String): String {
            var k = reason
            for (sep in listOf(" (", ": ")) {
                val i = k.indexOf(sep)
                if (i > 0) k = k.substring(0, i)
            }
            return k
        }
    }
}

private fun r1(v: Double) = Math.round(v * 10.0) / 10.0

private fun r3(v: Double) = Math.round(v * 1000.0) / 1000.0
