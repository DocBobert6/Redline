package com.redline.camera.viewfinder

import com.redline.camera.capability.CameraProfile
import com.redline.camera.capability.FocalMath
import kotlin.math.abs
import kotlin.math.roundToInt

/** One physical lens as the zoom ring sees it: its 3:2 horizontal equivalent focal length and active pixel size. */
data class ZoomLens(val cameraId: String, val equivalent32Mm: Float, val activeWidthPx: Int, val activeHeightPx: Int)

/** Where a stop's pixels come from. */
enum class StopSource {
    /** Digital crop of the reference (main) lens. */
    MAIN_CROP,

    /** A dedicated lens whose own field of view matches the stop (e.g. a 67 mm tele for 70). */
    NATIVE_LENS,

    /** Crop of the ultrawide (only when opted in; PLAN 3.3 keeps it off on the baseline device profile). */
    ULTRAWIDE_CROP,

    /** No usable focal-length data: the stop is framed by an assumed main-lens equivalent. */
    UNKNOWN
}

/**
 * One click stop on the zoom ring (PLAN 3.3, 5, 7, 11).
 *
 * [zoomRatio] is relative to the reference lens at 1.0x (what CONTROL_ZOOM_RATIO / SCALER_CROP_REGION
 * expect on the logical camera). [lensCrop] is the crop on the lens the pixels come from. The output size is
 * the 3:2 frame that crop leaves on that lens (full sensor width / crop, height = 2/3 of width).
 */
data class ZoomStop(
    val targetMm: Int,
    val trueEquivalentMm: Float?,
    val honest: Boolean,
    val source: StopSource,
    val lensId: String?,
    val lensCrop: Float,
    val zoomRatio: Float,
    val sourceWidthPx: Int,
    val outputWidthPx: Int,
    val outputHeightPx: Int
) {
    /** "35" when the stop really frames 35 mm; "24*" when it can't (PLAN 11 honest labels). */
    val label: String get() = if (honest) "$targetMm" else "$targetMm*"

    /** e.g. "24* ≈ 25.5 mm" or "50 mm". */
    val focalNote: String
        get() = when {
            honest -> "$targetMm mm"
            trueEquivalentMm == null -> "$label ≈ ? mm (focal length not reported)"
            else -> "$label ≈ ${fmt1(trueEquivalentMm)} mm"
        }

    /** A dedicated lens used with (almost) no crop. */
    val nativeFit: Boolean get() = source == StopSource.NATIVE_LENS && lensCrop < 1.0f + ZoomStops.HONEST_TOLERANCE

    /** "1.96x" crop badge text, or "native" for a dedicated lens used without meaningful crop. */
    val badge: String
        get() = when {
            nativeFit -> "native"
            source == StopSource.NATIVE_LENS -> "tele ${fmt2(lensCrop)}x"
            source == StopSource.ULTRAWIDE_CROP -> "uw ${fmt2(lensCrop)}x"
            else -> "${fmt2(lensCrop)}x"
        }

    /** The quality trade, e.g. "1.96x crop: ~2040 px wide from 4000 (2.8 MP)". */
    val resolutionNote: String
        get() {
            if (sourceWidthPx <= 0) return "resolution unknown"
            val mp = outputWidthPx.toLong() * outputHeightPx / 1_000_000.0
            val what = when (source) {
                StopSource.NATIVE_LENS -> if (nativeFit) "native lens" else "${fmt2(lensCrop)}x crop of tele"
                StopSource.ULTRAWIDE_CROP -> "${fmt2(lensCrop)}x crop of ultrawide"
                else -> if (lensCrop <= 1.0f + 1e-3f) "full width" else "${fmt2(lensCrop)}x crop"
            }
            return "$what: ~$outputWidthPx px wide from $sourceWidthPx (${fmt1(mp)} MP)"
        }

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "stop_mm" to targetMm,
        "label" to label,
        "true_equivalent_mm" to trueEquivalentMm?.let { FocalMath.round1(it) },
        "honest" to honest,
        "source" to source.name,
        "lens_id" to lensId,
        "lens_crop" to round2(lensCrop),
        "zoom_ratio" to round2(zoomRatio),
        "output_px" to "${outputWidthPx}x$outputHeightPx"
    )

    private companion object {
        fun fmt1(v: Float): String = "%.1f".format(v)
        fun fmt1(v: Double): String = "%.1f".format(v)
        fun fmt2(v: Float): String = "%.2f".format(v)
        fun round2(v: Float): Double = (v * 100.0).roundToInt() / 100.0
    }
}

data class ZoomPlan(val reference: ZoomLens?, val stops: List<ZoomStop>) {
    fun stop(mm: Int): ZoomStop? = stops.firstOrNull { it.targetMm == mm }

    /** Footnote for every asterisked stop, e.g. "* 24* ≈ 25.5 mm: the widest the main lens frames in 3:2". */
    val footnotes: List<String> get() = stops.filter { !it.honest }.map { "* ${it.focalNote}" }

    fun toMap(): List<Map<String, Any?>> = stops.map { it.toMap() }
}

/**
 * Zoom-stop math (PLAN 3.3 and 19.6): crop = target_eq_mm / phone_eq_mm of the lens used, with the
 * equivalent measured across the width of a 3:2 frame cut from the full sensor width.
 *
 * - The reference lens (zoom ratio 1.0 on the logical camera) is the lens closest to a 26 mm main camera.
 * - Each stop uses the longest eligible lens that is not longer than the stop, so a native telephoto
 *   (PLAN 4.4, adaptive default) takes the 70 stop on a phone that has one. Eligible: the reference lens,
 *   lenses longer than it, and the ultrawide only when [allowUltrawide] (PLAN 3.3: opt-in on the baseline).
 * - A stop no eligible lens can reach is framed as wide as possible (crop 1.0) and labelled with an
 *   asterisk and its true equivalent ("24* ≈ 25.5 mm"). Zoom ratios are kept >= 1.0 (or the HAL's minimum
 *   when the ultrawide is allowed) so the logical camera never switches to the ultrawide on its own,
 *   and <= [maxZoomRatio]; a clamped stop is labelled honestly too.
 *
 * Pure Kotlin: unit-tested with the baseline device profile and a hypothetical native-tele phone.
 */
object ZoomStops {
    val STOPS = listOf(24, 28, 35, 50, 70)

    /** PLAN 5: the ring opens at 35 mm. */
    const val DEFAULT_STOP = 35

    /** A typical phone main camera; used to pick the reference lens and when no focal data exists. */
    const val REFERENCE_EQ_MM = 26f

    /** Relative error under which a stop counts as truly reached (24 vs 24.4 is honest, 24 vs 25.5 is not). */
    const val HONEST_TOLERANCE = 0.02f

    fun lensesOf(camera: CameraProfile?): List<ZoomLens> = camera?.lenses.orEmpty()
        .filter { it.equivalent32Mm > 0f }
        .map { ZoomLens(it.cameraId, it.equivalent32Mm, it.activeWidth, it.activeHeight) }

    fun reference(lenses: List<ZoomLens>): ZoomLens? = lenses.filter { it.equivalent32Mm > 0f }
        .minByOrNull { abs(it.equivalent32Mm - REFERENCE_EQ_MM) }

    fun plan(
        lenses: List<ZoomLens>,
        maxZoomRatio: Float = Float.MAX_VALUE,
        minZoomRatio: Float = 1f,
        allowUltrawide: Boolean = false,
        stops: List<Int> = STOPS
    ): ZoomPlan {
        val ref = reference(lenses) ?: return ZoomPlan(null, stops.map { unknownStop(it, maxZoomRatio) })
        val eligible = lenses.filter { it.equivalent32Mm > 0f }.filter { l ->
            l == ref || l.equivalent32Mm > ref.equivalent32Mm || allowUltrawide
        }
        val floor = if (allowUltrawide) minZoomRatio.coerceAtMost(1f) else 1f
        return ZoomPlan(ref, stops.map { stop(it, ref, eligible, floor, maxZoomRatio) })
    }

    private fun stop(target: Int, ref: ZoomLens, eligible: List<ZoomLens>, floor: Float, maxRatio: Float): ZoomStop {
        val t = target.toFloat()
        val lens = eligible.filter { it.equivalent32Mm <= t * (1f + HONEST_TOLERANCE) }.maxByOrNull { it.equivalent32Mm }
            ?: eligible.minByOrNull { it.equivalent32Mm }
            ?: ref
        val wanted = t / ref.equivalent32Mm
        val ratio = wanted.coerceIn(floor, maxOf(floor, maxRatio))
        // Field of view actually framed: the requested ratio unless a clamp moved it, and never wider than the lens itself.
        val framed = maxOf(ref.equivalent32Mm * ratio, lens.equivalent32Mm)
        val lensCrop = framed / lens.equivalent32Mm
        val honest = abs(framed - t) <= t * HONEST_TOLERANCE
        val source = when {
            lens == ref -> StopSource.MAIN_CROP
            lens.equivalent32Mm > ref.equivalent32Mm -> StopSource.NATIVE_LENS
            else -> StopSource.ULTRAWIDE_CROP
        }
        val outW = (lens.activeWidthPx / lensCrop / 10f).roundToInt() * 10
        return ZoomStop(
            targetMm = target,
            trueEquivalentMm = framed,
            honest = honest,
            source = source,
            lensId = lens.cameraId,
            lensCrop = lensCrop,
            zoomRatio = ratio,
            sourceWidthPx = lens.activeWidthPx,
            outputWidthPx = outW,
            outputHeightPx = outW * 2 / 3
        )
    }

    /** No focal data: frame relative to an assumed 26 mm main, and say so with an asterisk. */
    private fun unknownStop(target: Int, maxRatio: Float): ZoomStop = ZoomStop(
        targetMm = target,
        trueEquivalentMm = null,
        honest = false,
        source = StopSource.UNKNOWN,
        lensId = null,
        lensCrop = (target / REFERENCE_EQ_MM).coerceIn(1f, maxOf(1f, maxRatio)),
        zoomRatio = (target / REFERENCE_EQ_MM).coerceIn(1f, maxOf(1f, maxRatio)),
        sourceWidthPx = 0,
        outputWidthPx = 0,
        outputHeightPx = 0
    )
}
