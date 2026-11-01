package com.redline.camera.colour

import com.redline.camera.capability.Dim
import com.redline.camera.capture.CaptureMath
import com.redline.camera.capture.CropBox

/**
 * The per-shot decisions of the B21 pipeline, in the order the reference makes them (raw_pipeline.plan_shot):
 * colour solve and crop first (the native statistics need both), then exposure and the tone curve.
 */
object ShotPlanner {
    class Geometry(val solve: ColourMath.ColourSolve, val crop: IntArray, val out: Dim)

    class Tone(val exposure: ColourMath.Exposure, val curve: ColourMath.ToneCurve) {
        val table: FloatArray by lazy { curve.table() }
    }

    fun geometry(
        cal: ColourCalibration,
        asShotNeutral: DoubleArray,
        width: Int,
        height: Int,
        zoom: Double,
        longPx: Int,
        warmth: Boolean = true
    ): Geometry {
        val solve = ColourMath.solveColour(cal, asShotNeutral, warmth)
        val crop = ColourMath.rawCrop(width, height, zoom)
        val out = CaptureMath.outputSize(CropBox(crop[0], crop[1], crop[2], crop[3]), longPx)
        return Geometry(solve, crop, out)
    }

    fun tone(tone: DoubleArray, medianY: Double, targetEncoded: Double?): Tone {
        val e = ColourMath.solveExposure(tone, medianY, targetEncoded)
        return Tone(e, ColourMath.ToneCurve(tone, Math.pow(2.0, e.ev)))
    }

    /** B23c: the shot's tone from the R5 II metering fit (the frame's own median and p98). */
    fun meteredTone(tone: DoubleArray, medianY: Double, p98Y: Double, metering: ColourMath.Metering): Tone {
        val e = ColourMath.solveExposureMetered(tone, medianY, p98Y, metering)
        return Tone(e, ColourMath.ToneCurve(tone, Math.pow(2.0, e.ev)))
    }

    fun floats(d: DoubleArray): FloatArray = FloatArray(d.size) { d[it].toFloat() }
}

/** What one still's colour step did (rendered by the pipeline, or fell back to the camera JPEG and why). */
data class ColourShot(
    val rendered: Boolean,
    val fallbackReason: String? = null,
    val profile: String? = null,
    val cct: Double? = null,
    val warmth: Double? = null,
    val exposureEv: Double? = null,
    val exposureMethod: String? = null,
    val jpegMedian: Double? = null,
    /** B23c: the gain the pre-B23c method (match the camera JPEG's median) would have used, for comparison only. */
    val exposureEvCameraJpeg: Double? = null,
    val clippedFraction: Double? = null,
    val outSize: Dim? = null,
    /** Stage timings in ms: jpeg_median, stats, plan, render, encode (filled by the saver), total. */
    val timingsMs: Map<String, Long> = emptyMap()
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "rendered" to rendered,
        "fallback_reason" to fallbackReason,
        "profile" to profile,
        "cct" to cct?.let { Math.round(it) },
        "warmth_retained" to warmth?.let { r3(it) },
        "exposure_ev" to exposureEv?.let { r3(it) },
        "exposure_method" to exposureMethod,
        "camera_jpeg_median" to jpegMedian?.let { r3(it) },
        "exposure_ev_camera_jpeg_method" to exposureEvCameraJpeg?.let { r3(it) },
        "clipped_fraction" to clippedFraction?.let { r3(it) },
        "out_size" to outSize?.toString(),
        "timings_ms" to timingsMs
    )
}

/** Result of the on-device self-check: the bundled synthetic frame rendered by Kotlin + native vs its reference render. */
data class ColourSelfCheck(
    val meanDe2000: Double? = null,
    val maxDe2000: Double? = null,
    val maxStep8bit: Int? = null,
    val greyChromaMax: Double? = null,
    val kotlinMathsMatch: Boolean? = null,
    val ms: Long? = null,
    val error: String? = null
) {
    /** Parity: mean dE2000 <= 1.0 and every channel within one 8-bit step of the reference's 8-bit render. */
    val parityPassed: Boolean? get() = if (error != null) false else meanDe2000?.let { it <= GATE_DE && (maxStep8bit ?: 99) <= MAX_STEP }

    val neutralPassed: Boolean? get() = if (error != null) false else greyChromaMax?.let { it <= NEUTRAL_MAX_CHROMA }

    val passed: Boolean get() = parityPassed == true && neutralPassed == true && kotlinMathsMatch == true

    companion object {
        const val GATE_DE = 1.0
        const val MAX_STEP = 1
        const val NEUTRAL_MAX_CHROMA = 2.0
    }
}

/**
 * The "colour_pipeline" section of the capability report and the Info screen (B21). [available] is null while the
 * start-up preparation (native module, assets, self-check, profile) is running, false with a [reason] when any
 * part failed; the still then comes from the camera JPEG.
 */
data class ColourPipelineReport(
    val userSetting: Boolean = true,
    val available: Boolean? = null,
    val reason: String? = null,
    val lookName: String? = null,
    val nativeVersion: String? = null,
    val profileSource: String? = null,
    val profileId: String? = null,
    val selfCheck: ColourSelfCheck? = null,
    val rendered: Int = 0,
    val fallbacks: Int = 0,
    val totalRenderMs: Long = 0,
    val maxRenderMs: Long = 0,
    val totalShotMs: Long = 0,
    val last: ColourShot? = null,
    val sessionRawUnavailable: String? = null,
    /** B23c: how stills are metered (the method of the last rendered shot, else the configured one). */
    val meteringHoldoutMaeEv: Double? = null
) {
    val exposureMethod: String get() = last?.exposureMethod?.takeIf { last.rendered } ?: ColourMath.METERING_METHOD

    /** What the shutter does: render through the pipeline (RAW is then always in the session). */
    val enabled: Boolean get() = userSetting && available == true && sessionRawUnavailable == null

    fun whyOff(): String? = when {
        sessionRawUnavailable != null -> "RAW stream unavailable this session: $sessionRawUnavailable"
        available == null -> "checking native module, profile and self-check..."
        available == false -> reason ?: "unavailable"
        !userSetting -> "turned off (stills come from the camera JPEG)"
        else -> null
    }

    fun record(shot: ColourShot, shotMs: Long?): ColourPipelineReport {
        val render = shot.timingsMs["total"] ?: 0L
        return if (shot.rendered) {
            copy(
                rendered = rendered + 1,
                totalRenderMs = totalRenderMs + render,
                maxRenderMs = maxOf(maxRenderMs, render),
                totalShotMs = totalShotMs + (shotMs ?: 0L),
                last = shot
            )
        } else {
            copy(fallbacks = fallbacks + 1, last = shot)
        }
    }

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "label" to LABEL,
        "enabled" to enabled,
        "user_setting" to userSetting,
        "available" to available,
        "reason" to whyOff(),
        "look" to lookName,
        "fallback" to "camera JPEG",
        "native_version" to nativeVersion,
        "profile_source" to profileSource,
        "profile_id" to profileId,
        "exposure_method" to exposureMethod,
        "metering" to METERING_LABEL,
        "metering_holdout_mae_ev" to meteringHoldoutMaeEv,
        "self_check" to selfCheck?.let {
            linkedMapOf(
                "mean_de2000" to it.meanDe2000?.let { v -> r4(v) },
                "max_de2000" to it.maxDe2000?.let { v -> r4(v) },
                "max_step_8bit" to it.maxStep8bit,
                "grey_chroma_max" to it.greyChromaMax?.let { v -> r3(v) },
                "kotlin_maths_match" to it.kotlinMathsMatch,
                "ms" to it.ms,
                "error" to it.error
            )
        },
        "rendered" to rendered,
        "fallbacks" to fallbacks,
        "avg_render_ms" to if (rendered == 0) null else totalRenderMs / rendered,
        "max_render_ms" to if (rendered == 0) null else maxRenderMs,
        "avg_shutter_to_saved_ms" to if (rendered == 0) null else totalShotMs / rendered,
        "last" to last?.toMap(),
        "gate" to linkedMapOf(
            "parity_de2000_le_1" to selfCheck?.parityPassed,
            "neutral_grey_chroma_le_2" to selfCheck?.neutralPassed
        )
    )

    companion object {
        const val LABEL = "RAW colour pipeline (B21): phone matrices -> XYZ -> Standard"
        const val METERING_LABEL = "B23c: mid-tone target fitted from R5 II RAW + camera JPEG pairs " +
            "(log2(p98/median) of the frame), not the phone JPEG's median"
    }
}

private fun r3(v: Double): Double = Math.round(v * 1000.0) / 1000.0

private fun r4(v: Double): Double = Math.round(v * 10000.0) / 10000.0
