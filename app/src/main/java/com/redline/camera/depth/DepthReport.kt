package com.redline.camera.depth

/**
 * B30: how the depth engine came up: which accelerator passed the self-check (GPU fp16 -> GPU fp32 -> CPU), what each
 * attempt measured or why it failed, and the models. Pure JVM (host-tested).
 */
data class DepthEngineInfo(
    val model: String,
    val segmenter: String,
    /** "gpu_fp16", "gpu_fp32" or "cpu"; null when no attempt passed. */
    val delegate: String?,
    val segmenterDelegate: String?,
    val inputWidth: Int,
    val inputHeight: Int,
    val segWidth: Int,
    val segHeight: Int,
    val initMs: Long,
    val attempts: List<DepthAttempt>,
    val error: String? = null
) {
    val ready: Boolean get() = delegate != null && segmenterDelegate != null

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "model" to model,
        "segmenter" to segmenter,
        "delegate" to delegate,
        "segmenter_delegate" to segmenterDelegate,
        "input_size" to "${inputWidth}x$inputHeight",
        "segmenter_input_size" to "${segWidth}x$segHeight",
        "init_ms" to initMs,
        "attempts" to attempts.map { it.toMap() },
        "error" to error
    )
}

/** One accelerator tried for one model: compile ms, self-check (relative max error, correlation, ms) or the error. */
data class DepthAttempt(
    val model: String,
    val delegate: String,
    val passed: Boolean,
    val compileMs: Long? = null,
    val checkMs: Long? = null,
    val relMaxError: Double? = null,
    val correlation: Double? = null,
    val error: String? = null
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "model" to model,
        "delegate" to delegate,
        "passed" to passed,
        "compile_ms" to compileMs,
        "selfcheck_ms" to checkMs,
        "selfcheck_rel_max_error" to relMaxError?.let { r5(it) },
        "selfcheck_correlation" to correlation?.let { r5(it) },
        "error" to error
    )
}

/** One shot's depth job: the stage times, the confidence analysis and the sidecar written (or the error). */
data class DepthShot(
    val base: String,
    val ok: Boolean,
    /** On the store lane, after the photo was saved: the still to the two model inputs. */
    val prepMs: Long,
    /** From the hand-off to the depth thread starting the job. */
    val queueMs: Long = 0,
    val depthMs: Long = 0,
    val segMs: Long = 0,
    val finishMs: Long = 0,
    val writeMs: Long = 0,
    /** Hand-off to sidecar written (excludes prep, which runs before the hand-off). */
    val totalMs: Long = 0,
    val delegate: String? = null,
    val confidence: Double? = null,
    val usable: Boolean? = null,
    val personCoverage: Double? = null,
    val width: Int = 0,
    val height: Int = 0,
    val storedBytes: Long? = null,
    val file: String? = null,
    val error: String? = null
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "base" to base,
        "ok" to ok,
        "ms" to linkedMapOf(
            "prep" to prepMs,
            "queue" to queueMs,
            "depth_infer" to depthMs,
            "segmentation" to segMs,
            "finish" to finishMs,
            "sidecar_write" to writeMs,
            "total" to totalMs
        ),
        "delegate" to delegate,
        "confidence" to confidence?.let { r3(it) },
        "usable" to usable,
        "person_coverage" to personCoverage?.let { r3(it) },
        "size" to if (width > 0) "${width}x$height" else null,
        "stored_bytes" to storedBytes,
        "file" to file,
        "error" to error
    )

    fun summary(): String = if (ok) {
        "depth ${depthMs} ms ($delegate), seg $segMs ms, total $totalMs ms, confidence %.2f%s, %d KB".format(
            confidence ?: 0.0,
            if (usable == true) "" else " (below 0.35: no bokeh)",
            (storedBytes ?: 0L) / 1024
        )
    } else {
        "failed: $error"
    }

    private fun r3(v: Double) = Math.round(v * 1000.0) / 1000.0
}

/** Hardware depth (DEPTH_OUTPUT or logical multi-camera disparity): detected and reported, not used in B30. */
data class HardwareDepth(val depthOutputCameras: List<String>, val logicalMultiCameras: List<String>) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "depth_output_cameras" to depthOutputCameras,
        "logical_multi_cameras" to logicalMultiCameras,
        "used" to false,
        "note" to if (depthOutputCameras.isEmpty()) {
            "no DEPTH_OUTPUT camera; monocular model only" +
                if (logicalMultiCameras.isNotEmpty()) " (dual-camera disparity not implemented)" else ""
        } else {
            "DEPTH_OUTPUT present but not used yet (monocular model is the depth source)"
        }
    )
}

/** B30: the report's "depth" section. */
data class DepthReport(
    val enabled: Boolean = DEFAULT_ON,
    val source: String = SOURCE_MONOCULAR,
    val hardware: HardwareDepth? = null,
    val engine: DepthEngineInfo? = null,
    val shots: Int = 0,
    val done: Int = 0,
    val failed: Int = 0,
    /** Skips by reason: "off", "busy", "not rendered from RAW", "not kept", "engine unavailable", ... */
    val skips: Map<String, Int> = emptyMap(),
    val totalDepthMs: Long = 0,
    val maxDepthMs: Long = 0,
    val maxTotalMs: Long = 0,
    val storedBytes: Long = 0,
    val lastError: String? = null,
    val recent: List<DepthShot> = emptyList()
) {
    fun record(s: DepthShot): DepthReport = copy(
        shots = shots + 1,
        done = done + if (s.ok) 1 else 0,
        failed = failed + if (s.ok) 0 else 1,
        totalDepthMs = totalDepthMs + if (s.ok) s.depthMs else 0,
        maxDepthMs = if (s.ok) maxOf(maxDepthMs, s.depthMs) else maxDepthMs,
        maxTotalMs = if (s.ok) maxOf(maxTotalMs, s.totalMs) else maxTotalMs,
        storedBytes = storedBytes + (s.storedBytes ?: 0L),
        lastError = if (s.ok) lastError else s.error,
        recent = (recent + s).takeLast(RECENT)
    )

    fun skip(reason: String): DepthReport = copy(skips = skips + (reason to (skips[reason] ?: 0) + 1))

    /** The B30 gate: depth (the model) under [GATE_MS] on every shot it ran. Null before any shot. */
    val gatePassed: Boolean? get() = if (done == 0) null else maxDepthMs < GATE_MS

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "enabled" to enabled,
        "source" to source,
        "hardware_depth" to hardware?.toMap(),
        "engine" to engine?.toMap(),
        "storage" to STORAGE,
        "shots" to shots,
        "done" to done,
        "failed" to failed,
        "skips" to skips,
        "avg_depth_ms" to if (done > 0) totalDepthMs / done else null,
        "max_depth_ms" to if (done > 0) maxDepthMs else null,
        "max_total_ms" to if (done > 0) maxTotalMs else null,
        "gate_depth_lt_400_ms" to gatePassed,
        "stored_bytes" to storedBytes,
        "last_error" to lastError,
        "last" to recent.lastOrNull()?.toMap(),
        "recent_shots" to recent.map { it.toMap() }
    )

    companion object {
        const val DEFAULT_ON = true
        const val SOURCE_MONOCULAR = "monocular (Depth Anything V2 Small) + person segmentation (MediaPipe selfie segmenter)"
        const val STORAGE =
            "app-private files/depth/<photo>.depth.png: 16-bit RGB (R depth, G person mask, B confidence) + JSON metadata (tEXt)"
        const val GATE_MS = 400L
        const val RECENT = 10

        const val SKIP_OFF = "off"
        const val SKIP_BUSY = "busy (previous depth job still running)"
        const val SKIP_NOT_RENDERED = "not rendered from RAW"
        const val SKIP_NOT_KEPT = "not kept (stress shot)"
        const val SKIP_ENGINE = "engine unavailable"
        const val SKIP_NO_PHOTO = "no photo saved"
    }
}

private fun r5(v: Double) = Math.round(v * 100000.0) / 100000.0
