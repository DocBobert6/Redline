package com.redline.camera.look

import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

/** A parsed 3D LUT: [size]^3 RGB triples in .cube order (red fastest, then green, then blue), on [domainMin]..[domainMax]. */
class CubeLut(val size: Int, val domainMin: FloatArray, val domainMax: FloatArray, val data: FloatArray) {
    init {
        require(size >= 2) { "LUT size $size < 2" }
        require(data.size == size * size * size * 3) { "LUT data ${data.size} != ${size * size * size * 3}" }
        require(domainMin.size == 3 && domainMax.size == 3) { "domain needs 3 channels" }
        require((0..2).all { domainMax[it] > domainMin[it] }) { "empty domain" }
    }

    companion object {
        /** Decodes RedlineNative's layout: [size, domainMin r g b, domainMax r g b, data...]. */
        fun fromNativeLayout(a: FloatArray): CubeLut {
            require(a.size >= 7) { "short native LUT array" }
            val n = a[0].roundToInt()
            return CubeLut(n, a.copyOfRange(1, 4), a.copyOfRange(4, 7), a.copyOfRange(7, a.size))
        }

        fun identity(n: Int): CubeLut {
            val d = FloatArray(n * n * n * 3)
            var i = 0
            for (b in 0 until n) {
                for (g in 0 until n) {
                    for (r in 0 until n) {
                        d[i++] = r / (n - 1f)
                        d[i++] = g / (n - 1f)
                        d[i++] = b / (n - 1f)
                    }
                }
            }
            return CubeLut(n, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f), d)
        }
    }
}

/**
 * CPU reference for the GPU look-preview shader (B20). The shader samples the LUT as a GL_TEXTURE_3D with
 * hardware trilinear filtering; [sample] is the same lookup done by hand, and [applyLook] is the whole fragment
 * program (index the LUT with the encoded preview value, mix with the input in linear light by strength, encode).
 * The GL renderer's start-up self-check compares the GPU against these.
 */
object LutMath {
    /** Texture-coordinate scale/offset that put LUT node 0 and node n-1 at texel centres (half-texel correction). */
    fun coordScale(n: Int): Float = (n - 1f) / n

    fun coordOffset(n: Int): Float = 0.5f / n

    fun textureCoordinate(x: Float, n: Int): Float = x.coerceIn(0f, 1f) * coordScale(n) + coordOffset(n)

    /** Trilinear lookup of encoded [r], [g], [b] (mapped through the LUT's domain and clamped). */
    fun sample(lut: CubeLut, r: Float, g: Float, b: Float): FloatArray {
        val n = lut.size
        val x = FloatArray(3)
        val inRgb = floatArrayOf(r, g, b)
        for (c in 0..2) {
            val t = (inRgb[c] - lut.domainMin[c]) / (lut.domainMax[c] - lut.domainMin[c])
            x[c] = t.coerceIn(0f, 1f) * (n - 1)
        }
        val i0 = IntArray(3) { floor(x[it]).toInt().coerceIn(0, n - 2) }
        val f = FloatArray(3) { x[it] - i0[it] }
        val out = FloatArray(3)
        for (dr in 0..1) {
            for (dg in 0..1) {
                for (db in 0..1) {
                    val w = (if (dr == 1) f[0] else 1 - f[0]) * (if (dg == 1) f[1] else 1 - f[1]) * (if (db == 1) f[2] else 1 - f[2])
                    if (w == 0f) continue
                    val idx = (((i0[2] + db) * n + (i0[1] + dg)) * n + (i0[0] + dr)) * 3
                    for (c in 0..2) out[c] += w * lut.data[idx + c]
                }
            }
        }
        return out
    }

    fun srgbDecode(e: Float): Float {
        val c = e.coerceIn(0f, 1f)
        return if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    }

    fun srgbEncode(l: Float): Float {
        val c = l.coerceAtLeast(0f)
        return if (c <= 0.0031308f) c * 12.92f else (1.055 * c.toDouble().pow(1.0 / 2.4) - 0.055).toFloat()
    }

    /** The fragment program: encoded input -> LUT -> mixed with the input in linear light by [strength] -> encoded output. */
    fun applyLook(lut: CubeLut, r: Float, g: Float, b: Float, strength: Float): FloatArray {
        val inRgb = floatArrayOf(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
        val looked = sample(lut, inRgb[0], inRgb[1], inRgb[2])
        val s = strength.coerceIn(0f, 1f)
        return FloatArray(3) { c ->
            val lin = srgbDecode(inRgb[c]) * (1 - s) + srgbDecode(looked[c]) * s
            srgbEncode(lin).coerceIn(0f, 1f)
        }
    }

    /** RGBA8 copy of the LUT data (fallback 3D-texture format when half-float upload is refused). */
    fun toRgba8(lut: CubeLut): ByteArray {
        val count = lut.size * lut.size * lut.size
        val out = ByteArray(count * 4)
        for (i in 0 until count) {
            for (c in 0..2) out[i * 4 + c] = (lut.data[i * 3 + c].coerceIn(0f, 1f) * 255f).roundToInt().toByte()
            out[i * 4 + 3] = 0xFF.toByte()
        }
        return out
    }

    /** Max per-channel difference, in 8-bit steps, between GPU RGBA8 [pixels] and the reference for [inputs] (RGB bytes). */
    fun maxErrorSteps(lut: CubeLut, inputs: IntArray, pixels: ByteArray, strength: Float): Int {
        var worst = 0
        val count = inputs.size / 3
        for (i in 0 until count) {
            val ref = applyLook(lut, inputs[i * 3] / 255f, inputs[i * 3 + 1] / 255f, inputs[i * 3 + 2] / 255f, strength)
            for (c in 0..2) {
                val gpu = pixels[i * 4 + c].toInt() and 0xFF
                val want = (ref[c] * 255f).roundToInt()
                worst = maxOf(worst, kotlin.math.abs(gpu - want))
            }
        }
        return worst
    }

    /** sRGB test colours for the GPU self-check: black, white, greys, primaries, skin, sky, foliage. */
    val SELF_CHECK_COLOURS = intArrayOf(
        0, 0, 0, 255, 255, 255, 128, 128, 128, 64, 64, 64,
        200, 200, 200, 255, 0, 0, 0, 255, 0, 0, 0, 255,
        230, 170, 140, 30, 90, 160, 250, 220, 40, 120, 40, 160,
        10, 200, 180, 180, 120, 60, 90, 140, 70, 240, 240, 250
    )

    /** GPU-vs-reference tolerance in 8-bit steps: half-float LUT storage plus filtering-weight precision plus rounding. */
    const val SELF_CHECK_TOLERANCE = 3
}
