package com.redline.camera.video

import com.redline.camera.look.TimingSnapshot

/** One finished (or failed) recording. */
data class RecordingRecord(
    val durationMs: Long,
    val framesEncoded: Int,
    val framesDropped: Int,
    val audio: Boolean,
    val bytes: Long?,
    val tracks: Int,
    val saved: Boolean,
    val error: String? = null
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "duration_ms" to durationMs,
        "frames_encoded" to framesEncoded,
        "frames_dropped" to framesDropped,
        "audio" to audio,
        "bytes" to bytes,
        "tracks" to tracks,
        "saved" to saved,
        "error" to error
    )
}

/**
 * The "video" section of the shared device report (B40): the setting, what the session ran at (size, fps, codec, encoder,
 * bitrate, tonemap and how it is undone), the GPU chain timing with its gate, dropped frames, the recordings and errors.
 */
data class VideoReport(
    val userSetting: Boolean = DEFAULT_ON,
    val active: Boolean = false,
    val recording: Boolean = false,
    val size: String? = null,
    val fps: Int? = null,
    val sizesTried: List<String> = emptyList(),
    val sizeSource: String? = null,
    val codec: String? = null,
    val encoder: String? = null,
    val encoderHardware: Boolean? = null,
    val bitrateBps: Int? = null,
    val bitrateRange: String? = null,
    val tonemapMode: String? = null,
    val tonemapLabel: String? = null,
    val tonemapExact: Boolean? = null,
    val tonemapNote: String? = null,
    val tonemapResultMode: String? = null,
    val curvePoints: Int? = null,
    val exposureGainEv: Double? = null,
    val colourMatrix: String? = null,
    val intermediate: String? = null,
    val lutFormat: String? = null,
    val selfCheckMaxErrorSteps: Int? = null,
    val lookApplied: Boolean? = null,
    val lookNote: String? = null,
    val glRenderer: String? = null,
    val timerMethod: String? = null,
    val chain: TimingSnapshot? = null,
    val displayPass: TimingSnapshot? = null,
    val cameraFrames: Int = 0,
    val framesDropped: Int = 0,
    val orientation: String? = null,
    val stabilization: String? = null,
    val audioPermission: Boolean? = null,
    val audio: String? = null,
    val recordings: List<RecordingRecord> = emptyList(),
    val errors: List<String> = emptyList()
) {
    /** B40 gate: chain p95 <= 12 ms per frame at the session size (4K on the baseline), once [GATE_MIN_FRAMES] are measured. */
    val gatePassed: Boolean? get() = chain?.let { c ->
        if (c.framesMeasured >= GATE_MIN_FRAMES &&
            c.p95Ms != null
        ) {
            c.p95Ms <= GATE_MS
        } else {
            null
        }
    }

    fun withError(e: String): VideoReport = copy(errors = (errors + e).takeLast(MAX_ERRORS))

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "user_setting" to userSetting,
        "active" to active,
        "recording" to recording,
        "size" to size,
        "fps" to fps,
        "sizes_tried" to sizesTried,
        "size_source" to sizeSource,
        "codec" to codec,
        "encoder" to encoder,
        "encoder_hardware" to encoderHardware,
        "bitrate_bps" to bitrateBps,
        "bitrate_range" to bitrateRange,
        "tonemap_mode" to tonemapMode,
        "tonemap_label" to tonemapLabel,
        "tonemap_exact_inverse" to tonemapExact,
        "tonemap_note" to tonemapNote,
        "tonemap_mode_in_result" to tonemapResultMode,
        "curve_points" to curvePoints,
        "exposure_gain_ev" to exposureGainEv,
        "exposure_method" to VideoExposure.METHOD,
        "colour_matrix" to colourMatrix,
        "intermediate" to intermediate,
        "lut_format" to lutFormat,
        "self_check_max_error_steps" to selfCheckMaxErrorSteps,
        "look_applied" to lookApplied,
        "look_note" to lookNote,
        "gl_renderer" to glRenderer,
        "timer_method" to timerMethod,
        "chain_frames_measured" to chain?.framesMeasured,
        "chain_ms_p50" to chain?.p50Ms,
        "chain_ms_p95" to chain?.p95Ms,
        "chain_ms_max" to chain?.maxMs,
        "chain_gate_ms" to GATE_MS,
        "chain_gate_passed" to gatePassed,
        "display_pass_ms_p95" to displayPass?.p95Ms,
        "camera_frames" to cameraFrames,
        "frames_dropped" to framesDropped,
        "orientation" to orientation,
        "stabilization" to stabilization,
        "audio_permission" to audioPermission,
        "audio" to audio,
        "recordings" to recordings.map { it.toMap() },
        "recording_durations_ms" to recordings.map { it.durationMs },
        "errors" to errors
    )

    fun summary(): String {
        val parts = mutableListOf<String>()
        parts += if (userSetting) "on" else "off"
        size?.let { parts += "$it@${fps ?: "?"}" }
        codec?.let { parts += "$it ${bitrateBps?.let { b -> "${b / 1_000_000} Mbps" } ?: ""}".trim() }
        tonemapLabel?.let { parts += "tonemap $it" }
        chain?.p95Ms?.let { parts += "chain p95 %.2f ms".format(it) }
        gatePassed?.let { parts += if (it) "gate pass" else "gate FAIL" }
        if (recordings.isNotEmpty()) parts += "${recordings.size} recording(s)"
        return parts.joinToString(" · ")
    }

    companion object {
        /** B40: video is available by default wherever the capability profile allows a session. */
        const val DEFAULT_ON = true
        const val GATE_MS = 12.0
        const val GATE_MIN_FRAMES = 100
        private const val MAX_ERRORS = 10
    }
}
