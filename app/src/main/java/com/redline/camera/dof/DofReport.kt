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
    val delegate: String? = null
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
        "delegate" to delegate
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
 * preview fps seen while depth or bokeh work was running (the window opens when the DOF stage starts and closes when the
 * photo is saved).
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
    val vignetteNote: String = VIGNETTE_NOTE
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
            recent = (recent + s).takeLast(RECENT)
        )
    }

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
            "last applied: depth ${last.depthMs ?: 0} ms, bokeh ${last.bokehMs ?: 0} ms, confidence %.2f, saved in %d ms".format(
                last.confidence ?: 0.0,
                last.shutterToSavedMs ?: 0
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
