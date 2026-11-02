package com.redline.camera.colour

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log2
import kotlin.math.round

/**
 * B24 detail stage (calibration/redline_cal/detail.py is the contract): the RF 24-70's in-camera residual vignette
 * (applied in linear light inside the RAW render), and the post-pass on the final bitmap: distortion ("RAW feel",
 * off by default), Canon Standard sharpening, grain topped up to the R5 II camera JPEG's noise. All values come from
 * the asset `colour/detail.json` (fitted on R5 II RAW + camera JPEG pairs; lens tables adapted from lensfun).
 */
class DetailAsset(
    val pictureStyle: String,
    val stylesSupported: List<String>,
    val stylesNote: String,
    val vignetteAlpha: Double,
    val vignetteFNumber: Double,
    val vignette: Map<Int, FloatArray>,
    val distortion: Map<Int, FloatArray>,
    val sharpen: Map<Int, Usm>,
    val grainIsoMin: Double,
    val grainIsoMax: Double,
    val grain: Map<Int, Grain>
) {
    class Usm(val sigma: Double, val amount: Double, val threshold: Double)

    class Grain(val c: DoubleArray, val shape: DoubleArray, val kernelW: Double, val kappa: Double)

    fun stopKey(mm: Int, keys: Set<Int>): Int = keys.minByOrNull { abs(it - mm) } ?: error("empty table")

    fun vignetteFor(mm: Int): FloatArray = vignette.getValue(stopKey(mm, vignette.keys))

    fun distortionFor(mm: Int): FloatArray = distortion.getValue(stopKey(mm, distortion.keys))

    fun usmFor(width: Int): Usm = sharpen.getValue(stopKey(width, sharpen.keys))

    fun grainFor(width: Int): Grain = grain.getValue(stopKey(width, grain.keys))

    /** The camera JPEG's noise (Immerkaer sigma, encoded 0..1) per luma bin at [iso] and output [width]. */
    fun grainTarget(iso: Double, width: Int): FloatArray {
        val g = grainFor(width)
        val s = log2(iso.coerceIn(grainIsoMin, grainIsoMax) / 100.0)
        val level = exp(g.c[0] + g.c[1] * s + g.c[2] * s * s)
        return FloatArray(g.shape.size) { (level * g.shape[it]).toFloat() }
    }

    /** Corner (r = 1) residual vignette of the stop in EV. */
    fun cornerEv(mm: Int): Double = log2(vignetteFor(mm).last().toDouble())

    companion object {
        const val MODEL = "redline_detail v1"

        fun parse(m: Map<*, *>): DetailAsset {
            require(m["model"] == MODEL) { "unexpected detail asset" }
            val vig = m["vignette"] as Map<*, *>
            val dist = m["distortion"] as Map<*, *>
            val sh = (m["sharpen"] as Map<*, *>)["widths"] as Map<*, *>
            val gr = m["grain"] as Map<*, *>
            fun floats(v: Any?): FloatArray = (v as List<*>).map { (it as Number).toFloat() }.toFloatArray()
            fun doubles(v: Any?): DoubleArray = (v as List<*>).map { (it as Number).toDouble() }.toDoubleArray()
            fun num(v: Any?): Double = (v as Number).toDouble()
            return DetailAsset(
                pictureStyle = m["picture_style"] as String,
                stylesSupported = (m["picture_styles_supported"] as List<*>).map { it as String },
                stylesNote = m["picture_styles_note"] as String,
                vignetteAlpha = num(vig["alpha"]),
                vignetteFNumber = num(vig["f_number"]),
                vignette = (vig["stops"] as Map<*, *>).entries.associate { (k, v) -> (k as String).toInt() to floats(v) },
                distortion = (dist["stops"] as Map<*, *>).entries.associate { (k, v) -> (k as String).toInt() to floats(v) },
                sharpen = sh.entries.associate { (k, v) ->
                    val u = v as Map<*, *>
                    (k as String).toInt() to Usm(num(u["sigma"]), num(u["amount"]), num(u["threshold"]))
                },
                grainIsoMin = num(gr["iso_min"]),
                grainIsoMax = num(gr["iso_max"]),
                grain = (gr["widths"] as Map<*, *>).entries.associate { (k, v) ->
                    val g = v as Map<*, *>
                    (k as String).toInt() to Grain(doubles(g["c"]), doubles(g["shape"]), num(g["kernel_w"]), num(g["kappa"]))
                }
            )
        }
    }
}

/** The Info screen's detail toggles. Default: vignette, sharpening and grain on; distortion off (R5 II JPEGs are corrected). */
data class DetailSettings(
    val vignette: Boolean = true,
    val sharpen: Boolean = true,
    val grain: Boolean = true,
    val distortion: Boolean = false
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "vignette" to vignette,
        "sharpen" to sharpen,
        "grain" to grain,
        "distortion_raw_feel" to distortion
    )
}

/** What the detail stage did to one still (report `colour_pipeline.last.detail`). */
data class DetailShot(
    val pictureStyle: String,
    val stopMm: Int,
    val vignetteCornerEv: Double?,
    val vignetteAlpha: Double?,
    val distortion: Boolean,
    val distortScale: Double?,
    val sharpen: DetailAsset.Usm?,
    val grainIso: Double?,
    val grainTarget: List<Double>?,
    val grainMeasured: List<Double?>?,
    val grainAdded: List<Double>?,
    val timingsMs: Map<String, Long>,
    val error: String? = null,
    /** B24.1: the pass's peak working memory (strip buffers, MB) and the size it ran at. */
    val peakMb: Double? = null,
    val width: Int? = null,
    val height: Int? = null
) {
    private fun sizeLabel(): String? = if (width != null && height != null) "${width}x$height" else null

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "picture_style" to pictureStyle,
        "stop_mm" to stopMm,
        "vignette" to vignetteCornerEv?.let { linkedMapOf("corner_ev" to r3(it), "alpha" to vignetteAlpha) },
        "distortion" to if (distortion) linkedMapOf("on" to true, "fill_scale" to distortScale?.let { r4(it) }) else false,
        "sharpen" to sharpen?.let {
            linkedMapOf("sigma_px" to it.sigma, "amount" to it.amount, "threshold_255" to r3(it.threshold * 255.0))
        },
        "grain" to grainIso?.let {
            linkedMapOf(
                "iso" to Math.round(it),
                "target_255" to grainTarget?.map { v -> r3(v * 255.0) },
                "measured_255" to grainMeasured?.map { v -> v?.let { r3(it * 255.0) } },
                "added_255" to grainAdded?.map { v -> r3(v * 255.0) }
            )
        },
        "detail_ms" to timingsMs,
        "memory" to peakMb?.let { linkedMapOf("peak_mb" to r3(it), "size" to sizeLabel()) },
        "error" to error
    )
}

private fun r3(v: Double): Double = round(v * 1000.0) / 1000.0

private fun r4(v: Double): Double = round(v * 10000.0) / 10000.0
