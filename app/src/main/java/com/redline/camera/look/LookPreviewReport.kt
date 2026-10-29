package com.redline.camera.look

import kotlin.math.ceil

/** Per-frame LUT-pass timings: percentiles over the last [window] samples, max over the session. */
class LutTimingStats(private val window: Int = 1000) {
    private val ring = DoubleArray(window)
    private var next = 0
    private var maxMs = 0.0

    var count = 0
        private set

    fun add(ms: Double) {
        if (ms.isNaN() || ms < 0) return
        ring[next] = ms
        next = (next + 1) % window
        count++
        if (ms > maxMs) maxMs = ms
    }

    fun reset() {
        next = 0
        count = 0
        maxMs = 0.0
    }

    fun snapshot(): TimingSnapshot {
        val n = minOf(count, window)
        if (n == 0) return TimingSnapshot(0, null, null, null)
        val sorted = ring.copyOf(n).also { it.sort() }
        return TimingSnapshot(count, percentile(sorted, 50.0), percentile(sorted, 95.0), maxMs)
    }

    companion object {
        /** Nearest-rank percentile of an ascending array. */
        fun percentile(sorted: DoubleArray, p: Double): Double {
            val rank = ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }
    }
}

data class TimingSnapshot(val framesMeasured: Int, val p50Ms: Double?, val p95Ms: Double?, val maxMs: Double?) {
    /** B20 gate: p95 of the LUT pass <= 8 ms, judged once at least 100 frames were measured; null before. */
    val gatePassed: Boolean?
        get() = if (framesMeasured >= LookPreviewReport.GATE_MIN_FRAMES && p95Ms != null) p95Ms <= LookPreviewReport.GATE_MS else null
}

/**
 * The "look_preview" section of the capability report and the Info screen (B20). [available] is null while the
 * GL/native/LUT preparation is still running, false with a [reason] when any part of it failed.
 */
data class LookPreviewReport(
    val userSetting: Boolean = true,
    val available: Boolean? = null,
    val reason: String? = null,
    val active: Boolean = false,
    val runtimeError: String? = null,
    val glVersion: String? = null,
    val glRenderer: String? = null,
    val es3: Boolean? = null,
    val externalEssl3: Boolean? = null,
    val floatLinear: Boolean? = null,
    val halfFloatColorBuffer: Boolean? = null,
    val lutName: String? = null,
    val lutSize: Int? = null,
    val lutFormat: String? = null,
    val nativeVersion: String? = null,
    val nativeError: String? = null,
    val selfCheckMaxErrorSteps: Int? = null,
    val timerMethod: String? = null,
    val timing: TimingSnapshot = TimingSnapshot(0, null, null, null),
    val displayFrames: Int? = null,
    val displayDropped: Int? = null,
    val strength: Float = 1f
) {
    /** On, available and not failed at runtime: what the viewfinder renders through when the camera is open. */
    val enabled: Boolean get() = userSetting && available == true && runtimeError == null

    val gateLutLe8Ms: Boolean? get() = timing.gatePassed

    /** Why the look preview is not on, or null when it is. */
    fun whyOff(): String? = when {
        runtimeError != null -> "failed at runtime, plain preview in use: $runtimeError"
        available == null -> "checking OpenGL ES, native module and LUT..."
        available == false -> reason ?: "unavailable"
        !userSetting -> "turned off"
        else -> null
    }

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "label" to LABEL,
        "enabled" to enabled,
        "user_setting" to userSetting,
        "available" to available,
        "reason" to whyOff(),
        "path" to if (active) "gl_lut" else "plain_textureview",
        "runtime_error" to runtimeError,
        "gl_version" to glVersion,
        "renderer" to glRenderer,
        "gles3" to es3,
        "external_image_essl3" to externalEssl3,
        "float_linear" to floatLinear,
        "half_float_color_buffer" to halfFloatColorBuffer,
        "lut_name" to lutName,
        "lut_size" to lutSize,
        "lut_format" to lutFormat,
        "native_version" to nativeVersion,
        "native_error" to nativeError,
        "self_check_max_error_8bit" to selfCheckMaxErrorSteps,
        "strength" to round2(strength.toDouble()),
        "timer_method" to timerMethod,
        "frames_measured" to timing.framesMeasured,
        "lut_ms_p50" to timing.p50Ms?.let { round3(it) },
        "lut_ms_p95" to timing.p95Ms?.let { round3(it) },
        "lut_ms_max" to timing.maxMs?.let { round3(it) },
        "display_frames" to displayFrames,
        "display_dropped" to displayDropped,
        "gate" to linkedMapOf("lut_le_8_ms" to gateLutLe8Ms)
    )

    companion object {
        const val LABEL = "Look preview (approximate until B21)"
        const val GATE_MS = 8.0
        const val GATE_MIN_FRAMES = 100

        private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0

        private fun round3(v: Double): Double = Math.round(v * 1000.0) / 1000.0
    }
}
