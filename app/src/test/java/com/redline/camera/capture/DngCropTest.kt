package com.redline.camera.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DngCropTest {
    /** Minimal little-endian TIFF: IFD0 with DefaultCropOrigin/Size as LONG[2] (the DngCreator layout). */
    private fun tiff(le: Boolean = true, type: Int = 4): ByteArray {
        val b = ByteArray(64)
        fun w16(at: Int, v: Int) {
            if (le) {
                b[at] = v.toByte()
                b[at + 1] = (v shr 8).toByte()
            } else {
                b[at] = (v shr 8).toByte()
                b[at + 1] = v.toByte()
            }
        }
        fun w32(at: Int, v: Int) {
            if (le) {
                w16(at, v and 0xFFFF)
                w16(at + 2, v ushr 16)
            } else {
                w16(at, v ushr 16)
                w16(at + 2, v and 0xFFFF)
            }
        }
        b[0] = (if (le) 'I' else 'M').code.toByte()
        b[1] = b[0]
        w16(2, 42)
        w32(4, 8)
        w16(8, 2)
        val size = if (type == 3) 2 else 4
        listOf(DngCrop.TAG_DEFAULT_CROP_ORIGIN to (8 to 8), DngCrop.TAG_DEFAULT_CROP_SIZE to (3984 to 2984)).forEachIndexed { i, (tag, v) ->
            val e = 10 + i * 12
            w16(e, tag)
            w16(e + 2, type)
            w32(e + 4, 2)
            if (type == 3) {
                w16(e + 8, v.first)
                w16(e + 10, v.second)
            } else {
                val at = 40 + i * 8
                w32(e + 8, at)
                w32(at, v.first)
                w32(at + 4, v.second)
            }
        }
        check(size > 0)
        return b
    }

    @Test fun readsAndRewritesLongTags() {
        val b = tiff()
        assertEquals(CropBox(8, 8, 3984, 2984), DngCrop.read(b))
        assertTrue(DngCrop.write(b, CropBox(100, 200, 2000, 1334)))
        assertEquals(CropBox(100, 200, 2000, 1334), DngCrop.read(b))
    }

    @Test fun shortTagsAndBigEndian() {
        val s = tiff(type = 3)
        assertTrue(DngCrop.write(s, CropBox(2, 4, 600, 400)))
        assertEquals(CropBox(2, 4, 600, 400), DngCrop.read(s))
        val m = tiff(le = false)
        assertEquals(CropBox(8, 8, 3984, 2984), DngCrop.read(m))
    }

    @Test fun notATiffIsLeftAlone() {
        val b = ByteArray(32)
        assertNull(DngCrop.read(b))
        assertFalse(DngCrop.write(b, CropBox(0, 0, 2, 2)))
    }

    @Test fun frameAtOneIsFull32() {
        val f = DngCrop.frame(CropBox(8, 8, 3984, 2984), 1f)
        assertEquals(3984, f.width)
        assertEquals(2656, f.height)
        assertEquals(8, f.left)
        assertEquals(8 + 164, f.top)
    }

    @Test fun frameIsCentredEvenAnd32() {
        val base = CropBox(8, 8, 3984, 2984)
        val f = DngCrop.frame(base, 35f / 26f)
        listOf(f.left, f.top, f.width, f.height).forEach { assertEquals(0, it % 2) }
        assertEquals(1.5, f.width.toDouble() / f.height, 0.01)
        assertEquals(base.left + base.width / 2.0, f.left + f.width / 2.0, 2.0)
        assertEquals(base.top + base.height / 2.0, f.top + f.height / 2.0, 2.0)
        assertTrue(f.left >= base.left && f.left + f.width <= base.left + base.width)
    }
}
