package com.redline.camera.capture

import kotlin.math.max
import kotlin.math.min

/**
 * Non-destructive DNG framing: rewrites the DNG DefaultCropOrigin / DefaultCropSize tags (IFD0) so editors open
 * the file framed like the zoom stop's 3:2 JPEG. The pixels stay full-sensor; removing the crop in an editor
 * brings the whole frame back. Pure Kotlin over the file bytes (no Android types) so it is unit-tested.
 */
object DngCrop {
    const val TAG_DEFAULT_CROP_ORIGIN = 0xC61F
    const val TAG_DEFAULT_CROP_SIZE = 0xC620

    private const val SHORT = 3
    private const val LONG = 4
    private const val RATIONAL = 5

    /**
     * The 3:2 frame of a stop that crops the sensor by [lensCrop], centred inside the writer's own default crop
     * [base] (already relative to the DNG ActiveArea). Even-aligned so the Bayer phase is kept.
     */
    fun frame(base: CropBox, lensCrop: Float): CropBox {
        val c = max(1f, lensCrop)
        val landscape = base.width >= base.height
        val long0 = if (landscape) base.width else base.height
        val short0 = if (landscape) base.height else base.width
        var long = long0 / c
        var short = long * 2f / 3f
        val shortMax = short0 / c
        if (short > shortMax) {
            short = shortMax
            long = min(long0.toFloat(), short * 1.5f)
        }
        val l = even(long.toInt()).coerceIn(2, long0)
        val s = even(short.toInt()).coerceIn(2, short0)
        val w = if (landscape) l else s
        val h = if (landscape) s else l
        return CropBox(base.left + even((base.width - w) / 2), base.top + even((base.height - h) / 2), w, h)
    }

    /** The current DefaultCrop in IFD0, or null when the file has none (or isn't a readable TIFF). */
    fun read(bytes: ByteArray, length: Int = bytes.size): CropBox? {
        val t = Tiff.of(bytes, length) ?: return null
        val o = t.entry(TAG_DEFAULT_CROP_ORIGIN) ?: return null
        val s = t.entry(TAG_DEFAULT_CROP_SIZE) ?: return null
        val ov = t.values(o) ?: return null
        val sv = t.values(s) ?: return null
        return CropBox(ov[0], ov[1], sv[0], sv[1])
    }

    /** Rewrites both tags in place. False when the tags are missing or of an unexpected shape. */
    fun write(bytes: ByteArray, box: CropBox, length: Int = bytes.size): Boolean {
        val t = Tiff.of(bytes, length) ?: return false
        val o = t.entry(TAG_DEFAULT_CROP_ORIGIN) ?: return false
        val s = t.entry(TAG_DEFAULT_CROP_SIZE) ?: return false
        if (t.values(o) == null || t.values(s) == null) return false
        t.put(o, box.left, box.top)
        t.put(s, box.width, box.height)
        return true
    }

    private fun even(v: Int): Int = v and 1.inv()

    private class Entry(val type: Int, val count: Int, val valueAt: Int)

    private class Tiff(val b: ByteArray, val n: Int, val le: Boolean) {
        fun u16(at: Int): Int = if (le) (b[at].u or (b[at + 1].u shl 8)) else ((b[at].u shl 8) or b[at + 1].u)

        fun u32(at: Int): Long = if (le) {
            (u16(at).toLong()) or (u16(at + 2).toLong() shl 16)
        } else {
            (u16(at).toLong() shl 16) or u16(at + 2).toLong()
        }

        fun w16(at: Int, v: Int) {
            if (le) {
                b[at] = v.toByte()
                b[at + 1] = (v shr 8).toByte()
            } else {
                b[at] = (v shr 8).toByte()
                b[at + 1] = v.toByte()
            }
        }

        fun w32(at: Int, v: Long) {
            if (le) {
                w16(at, (v and 0xFFFF).toInt())
                w16(at + 2, ((v shr 16) and 0xFFFF).toInt())
            } else {
                w16(at, ((v shr 16) and 0xFFFF).toInt())
                w16(at + 2, (v and 0xFFFF).toInt())
            }
        }

        fun entry(tag: Int): Entry? {
            val ifd = u32(4)
            if (ifd < 8 || ifd + 2 > n) return null
            val count = u16(ifd.toInt())
            for (i in 0 until count) {
                val e = ifd.toInt() + 2 + i * 12
                if (e + 12 > n) return null
                if (u16(e) != tag) continue
                val type = u16(e + 2)
                val cnt = u32(e + 4).toInt()
                val size = when (type) {
                    SHORT -> 2
                    LONG -> 4
                    RATIONAL -> 8
                    else -> return null
                } * cnt
                val at = if (size <= 4) e + 8 else u32(e + 8).toInt()
                if (cnt != 2 || at < 0 || at + size > n) return null
                return Entry(type, cnt, at)
            }
            return null
        }

        fun values(e: Entry): IntArray? = when (e.type) {
            SHORT -> intArrayOf(u16(e.valueAt), u16(e.valueAt + 2))
            LONG -> intArrayOf(u32(e.valueAt).toInt(), u32(e.valueAt + 4).toInt())
            RATIONAL -> {
                val d0 = u32(e.valueAt + 4)
                val d1 = u32(e.valueAt + 12)
                if (d0 == 0L || d1 == 0L) null else intArrayOf((u32(e.valueAt) / d0).toInt(), (u32(e.valueAt + 8) / d1).toInt())
            }
            else -> null
        }

        fun put(e: Entry, a: Int, c: Int) {
            when (e.type) {
                SHORT -> {
                    w16(e.valueAt, a)
                    w16(e.valueAt + 2, c)
                }
                LONG -> {
                    w32(e.valueAt, a.toLong())
                    w32(e.valueAt + 4, c.toLong())
                }
                RATIONAL -> {
                    w32(e.valueAt, a.toLong())
                    w32(e.valueAt + 4, 1)
                    w32(e.valueAt + 8, c.toLong())
                    w32(e.valueAt + 12, 1)
                }
            }
        }

        companion object {
            fun of(b: ByteArray, n: Int): Tiff? {
                if (n < 8) return null
                val le = when {
                    b[0] == 'I'.code.toByte() && b[1] == 'I'.code.toByte() -> true
                    b[0] == 'M'.code.toByte() && b[1] == 'M'.code.toByte() -> false
                    else -> return null
                }
                val t = Tiff(b, n, le)
                return if (t.u16(2) == 42) t else null
            }
        }
    }

    private val Byte.u: Int get() = toInt() and 0xFF
}
