package com.redline.camera.capture

import com.redline.camera.capability.Dim
import com.redline.camera.colour.ColourShot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** A crop rectangle in stored-pixel coordinates. */
data class CropBox(val left: Int, val top: Int, val width: Int, val height: Int)

/** Pure helpers for the B13 still path (unit-tested; no Android types). */
object CaptureMath {
    /**
     * Centred 3:2 crop of a stored image, keeping its long side, the same framing as the 3:2 viewfinder.
     * Works for landscape and pre-rotated portrait buffers. An image already wider than 3:2 is cropped on
     * its long side instead.
     */
    fun crop32(width: Int, height: Int): CropBox {
        require(width > 0 && height > 0)
        val landscape = width >= height
        val long = if (landscape) width else height
        val short = if (landscape) height else width
        var cropLong = long
        var cropShort = (long * 2.0 / 3.0).roundToInt()
        if (cropShort > short) {
            cropShort = short
            cropLong = (short * 3.0 / 2.0).roundToInt().coerceAtMost(long)
        }
        val w = if (landscape) cropLong else cropShort
        val h = if (landscape) cropShort else cropLong
        return CropBox((width - w) / 2, (height - h) / 2, w, h)
    }

    /**
     * Final pixel size: the crop scaled so its long side is [stopLongPx] (the zoom stop's honest output
     * width), never upscaled past the crop itself. [stopLongPx] <= 0 keeps the crop size.
     */
    fun outputSize(crop: CropBox, stopLongPx: Int): Dim {
        val long = max(crop.width, crop.height)
        if (stopLongPx <= 0 || stopLongPx >= long) return Dim(crop.width, crop.height)
        val s = stopLongPx.toDouble() / long
        return Dim((crop.width * s).roundToInt().coerceAtLeast(1), (crop.height * s).roundToInt().coerceAtLeast(1))
    }

    /** Camera2 JPEG_ORIENTATION for a device rotated [deviceDegrees] clockwise from natural (Camera2 docs). */
    fun jpegOrientation(sensorOrientation: Int, deviceDegrees: Int, frontFacing: Boolean): Int {
        val d = ((deviceDegrees + 45) / 90 * 90) % 360
        return if (frontFacing) (sensorOrientation - d + 360) % 360 else (sensorOrientation + d) % 360
    }

    /** EXIF orientation value for a clockwise rotation in degrees. */
    fun exifOrientation(degrees: Int): Int = when ((degrees % 360 + 360) % 360) {
        90 -> 6
        180 -> 3
        270 -> 8
        else -> 1
    }

    /** Clockwise rotation in degrees for an EXIF orientation value (mirrored values count as their rotation). */
    fun degreesOf(exifOrientation: Int): Int = when (exifOrientation) {
        6, 7 -> 90
        3, 4 -> 180
        8, 5 -> 270
        else -> 0
    }

    /**
     * The EXIF look tag (PLAN 6 step 9): the look's name when the RAW pipeline rendered the still, else "none
     * (camera JPEG)". Make/Model are never changed (no Canon spoof).
     */
    fun lookTag(version: String, look: String, stopLabel: String, trueEquivalentMm: Float?): String {
        val focal = if (trueEquivalentMm != null && stopLabel.endsWith("*")) {
            "${stopLabel}mm (≈${fmt1(trueEquivalentMm)}mm)"
        } else {
            "${stopLabel}mm"
        }
        return "Redline $version | Look: $look @ $focal"
    }

    /** EXIF FocalLengthIn35mmFilm: the true equivalent, rounded (24* on the baseline is 26, not 24). */
    fun focal35(targetMm: Int, trueEquivalentMm: Float?): Int = (trueEquivalentMm ?: targetMm.toFloat()).roundToInt()

    fun baseName(timeMs: Long): String = "RL_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(timeMs))

    private fun fmt1(v: Float): String = String.format(Locale.US, "%.1f", v)
}

/** One finished (or failed) shot. */
data class ShotResult(
    val ok: Boolean,
    val jpegSize: Dim? = null,
    val dngSaved: Boolean = false,
    val dngWanted: Boolean = false,
    val shutterToSavedMs: Long? = null,
    val error: String? = null,
    val kept: Boolean = true,
    /** The look named in the EXIF tag. */
    val look: String? = null,
    /** B21 colour step: rendered by the pipeline, or the fallback reason; null when the pipeline was off. */
    val colour: ColourShot? = null,
    /** B22 burst step (merged, single-frame and why, or fallback); null when burst merge was off. */
    val burst: BurstShot? = null,
    /** B22: how long the press waited for the previous capture to leave the camera (0 = started at once). */
    val shutterWaitMs: Long? = null,
    /** B22: the press was refused, or held longer than BurstReport.SHUTTER_BLOCK_MS. */
    val shutterBlocked: Boolean = false,
    /**
     * B22b: the press or shot ended without a photo for a reason that is not a capture failure: the user left the
     * viewfinder, the session was rebuilt under it, or (with [shutterBlocked]) the waiting queue was full.
     */
    val dropped: String? = null
) {
    fun summary(): String = when {
        dropped != null && shutterBlocked -> "Not taken: $dropped"
        dropped != null -> "Shot dropped: $dropped"
        !ok -> "Capture failed: ${error ?: "unknown"}"
        else -> buildString {
            append(if (kept) "Saved " else "Captured ")
            append("JPEG ${jpegSize ?: "?"}")
            if (dngWanted) append(if (dngSaved) " + DNG" else " (DNG failed: ${error ?: "?"})")
            shutterToSavedMs?.let { append(" in $it ms") }
            colour?.let { append(if (it.rendered) " · RAW look" else " · camera JPEG (${it.fallbackReason ?: "?"})") }
            burst?.let { b ->
                when {
                    b.merged -> append(" · ${b.framesMerged}-frame merge")
                    b.plan.isBurst -> append(" · single frame (${b.fallbackReason ?: "?"})")
                }
            }
        }
    }
}

/** Running capture counters for the overlay and the report's "capture" section. */
data class CaptureStats(
    val shots: Int = 0,
    val failures: Int = 0,
    val dngWanted: Int = 0,
    val dngSaved: Int = 0,
    val totalSaveMs: Long = 0,
    val maxSaveMs: Long = 0,
    val lastError: String? = null,
    val last: String? = null,
    val stressRequested: Int = 0,
    val stressDone: Int = 0,
    val stressFailures: Int = 0,
    val stressRaw: Boolean = false,
    val stressRunning: Boolean = false,
    /** B22b: presses / shots that ended without a photo but not by a capture failure ([ShotResult.dropped]). */
    val dropped: Int = 0,
    val lastDropped: String? = null
) {
    fun add(r: ShotResult, stress: Boolean): CaptureStats {
        if (r.dropped != null) {
            return copy(
                dropped = dropped + 1,
                lastDropped = r.dropped,
                last = r.summary(),
                stressDone = stressDone + if (stress) 1 else 0
            )
        }
        val dngFailed = r.dngWanted && !r.dngSaved
        val failed = !r.ok || dngFailed
        val ms = r.shutterToSavedMs ?: 0
        return copy(
            shots = shots + 1,
            failures = failures + if (failed) 1 else 0,
            dngWanted = dngWanted + if (r.dngWanted) 1 else 0,
            dngSaved = dngSaved + if (r.dngSaved) 1 else 0,
            totalSaveMs = totalSaveMs + ms,
            maxSaveMs = max(maxSaveMs, ms),
            lastError = if (failed) r.error ?: "unknown" else lastError,
            last = r.summary(),
            stressDone = stressDone + if (stress) 1 else 0,
            stressFailures = stressFailures + if (stress && failed) 1 else 0
        )
    }

    val avgSaveMs: Long? get() = if (shots == 0) null else totalSaveMs / shots

    /** B13 gate: a finished 500-shot run with zero failures (JPEG, and DNG when it was on). */
    val stressGatePassed: Boolean?
        get() = if (stressRequested < STRESS_RUNS || stressRunning) null else stressDone >= STRESS_RUNS && stressFailures == 0

    fun overlayText(): String? = when {
        stressRunning -> "Stress $stressDone/$stressRequested · $stressFailures failed${if (stressRaw) " · RAW on" else ""}"
        else -> last
    }

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "shots" to shots,
        "failures" to failures,
        "dng_wanted" to dngWanted,
        "dng_saved" to dngSaved,
        "avg_shutter_to_saved_ms" to avgSaveMs,
        "max_shutter_to_saved_ms" to if (shots == 0) null else maxSaveMs,
        "last" to last,
        "last_error" to lastError,
        "dropped" to dropped,
        "last_dropped" to lastDropped,
        "stress" to linkedMapOf(
            "requested" to stressRequested,
            "done" to stressDone,
            "failures" to stressFailures,
            "raw" to stressRaw,
            "running" to stressRunning
        ),
        "gate" to linkedMapOf("stress_500_zero_failures" to stressGatePassed)
    )

    companion object {
        const val STRESS_RUNS = 500
    }
}
