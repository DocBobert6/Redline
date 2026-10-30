package com.redline.camera.colour

import com.redline.camera.capability.JsonParse
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.abs
import kotlin.math.max

/**
 * The bundled self-check frame (assets/colour/self_check.json, written by calibration/tools/make_colour_fixtures.py):
 * a small synthetic RAW mosaic under daylight, its capture parameters, and the reference pipeline's 8-bit render.
 * The app renders it through its own Kotlin planning + native pipeline at start-up and compares ([compare]).
 */
class SelfCheckFrame(
    val width: Int,
    val height: Int,
    val cfa: Int,
    val black: FloatArray,
    val white: Float,
    val rawLittleEndian: ByteArray,
    val shading: FloatArray?,
    val shadingRows: Int,
    val shadingCols: Int,
    val calibration: ColourCalibration,
    val asShotNeutral: DoubleArray,
    val zoom: Double,
    val longPx: Int,
    val targetEncoded: Double,
    val expectedMedianY: Double,
    val expectedExposureEv: Double,
    val expectedMatrix: DoubleArray,
    val expectedCrop: IntArray,
    val expectedOutWidth: Int,
    val expectedOutHeight: Int,
    val expectedRgb8: ByteArray,
    /** Grey patch boxes in output pixels: x0, y0, x1, y1 (exclusive). */
    val greyBoxes: List<IntArray>
) {
    /** The mosaic in a direct buffer, as the native code reads a RAW_SENSOR plane. */
    fun rawBuffer(): ByteBuffer = ByteBuffer.allocateDirect(rawLittleEndian.size).order(ByteOrder.LITTLE_ENDIAN).apply {
        put(rawLittleEndian)
        rewind()
    }

    /** True when Kotlin's plan reproduces the reference's matrix, crop, output size and exposure. */
    fun mathsMatch(g: ShotPlanner.Geometry, ev: Double): Boolean =
        g.solve.matrix.indices.all { abs(g.solve.matrix[it] - expectedMatrix[it]) < 1e-5 } &&
            g.crop.contentEquals(expectedCrop) &&
            g.out.width == expectedOutWidth &&
            g.out.height == expectedOutHeight &&
            abs(ev - expectedExposureEv) < 1e-3

    /** Compares a native render ([argb], Bitmap.getPixels order) with the reference's 8-bit render. */
    fun compare(argb: IntArray, mathsMatch: Boolean, ms: Long): ColourSelfCheck {
        val n = expectedOutWidth * expectedOutHeight
        if (argb.size != n) return ColourSelfCheck(error = "render size ${argb.size} != $n pixels", ms = ms)
        var sum = 0.0
        var worst = 0.0
        var step = 0
        for (i in 0 until n) {
            val p = argb[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val er = expectedRgb8[i * 3].toInt() and 0xFF
            val eg = expectedRgb8[i * 3 + 1].toInt() and 0xFF
            val eb = expectedRgb8[i * 3 + 2].toInt() and 0xFF
            step = max(step, max(abs(r - er), max(abs(g - eg), abs(b - eb))))
            val de = ColourMath.deltaE2000(
                ColourMath.srgbEncodedToLab(er / 255.0, eg / 255.0, eb / 255.0),
                ColourMath.srgbEncodedToLab(r / 255.0, g / 255.0, b / 255.0)
            )
            sum += de
            worst = max(worst, de)
        }
        var grey = 0.0
        for (box in greyBoxes) {
            val mean = DoubleArray(3)
            var count = 0
            for (y in box[1] until box[3]) {
                for (x in box[0] until box[2]) {
                    val p = argb[y * expectedOutWidth + x]
                    mean[0] += ((p shr 16) and 0xFF) / 255.0
                    mean[1] += ((p shr 8) and 0xFF) / 255.0
                    mean[2] += (p and 0xFF) / 255.0
                    count++
                }
            }
            if (count > 0) grey = max(grey, ColourMath.chroma(mean[0] / count, mean[1] / count, mean[2] / count))
        }
        return ColourSelfCheck(sum / n, worst, step, grey, mathsMatch, ms)
    }

    companion object {
        fun parse(text: String): SelfCheckFrame {
            val m = JsonParse.parse(text) as Map<*, *>
            fun num(k: String, src: Map<*, *> = m) = (src[k] as Number).toDouble()
            fun doubles(v: Any?) = (v as List<*>).map { (it as Number).toDouble() }.toDoubleArray()
            fun ints(v: Any?) = (v as List<*>).map { (it as Number).toInt() }.toIntArray()
            val e = m["expected"] as Map<*, *>
            val sh = m["shading"] as? Map<*, *>
            val out = ints(e["out"])
            return SelfCheckFrame(
                width = num("width").toInt(),
                height = num("height").toInt(),
                cfa = num("cfa").toInt(),
                black = doubles(m["black"]).map { it.toFloat() }.toFloatArray(),
                white = num("white").toFloat(),
                rawLittleEndian = Base64.getDecoder().decode(m["raw_u16le_b64"] as String),
                shading = sh?.let { doubles(it["values"]).map { v -> v.toFloat() }.toFloatArray() },
                shadingRows = sh?.let { num("rows", it).toInt() } ?: 0,
                shadingCols = sh?.let { num("cols", it).toInt() } ?: 0,
                calibration = ColourCalibration.fromJson(m["profile"] as Map<*, *>),
                asShotNeutral = doubles(m["as_shot_neutral"]),
                zoom = num("zoom"),
                longPx = num("long_px").toInt(),
                targetEncoded = num("target_encoded"),
                expectedMedianY = num("median_y", e),
                expectedExposureEv = num("exposure_ev", e),
                expectedMatrix = doubles(e["matrix"]),
                expectedCrop = ints(e["crop"]),
                expectedOutWidth = out[0],
                expectedOutHeight = out[1],
                expectedRgb8 = Base64.getDecoder().decode(e["rgb8_b64"] as String),
                greyBoxes = (m["grey_boxes_out"] as List<*>).map { ints(it) }
            )
        }
    }
}
