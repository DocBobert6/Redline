package com.redline.camera.depth

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** B30: the depth sidecar PNG decodes (16-bit RGB, exact samples) and carries its metadata. */
class DepthPngTest {
    @Test
    fun sixteenBitSamplesAndMetadataRoundTrip() {
        val w = 37
        val h = 23
        val px = ShortArray(w * h * 3) { i ->
            val p = i / 3
            when (i % 3) {
                0 -> ((p * 977) % 65536).toShort()
                1 -> (if ((p % w) < w / 2) 65535 else 0).toShort()
                else -> ((p * 31) and 0xFFFF).toShort()
            }
        }
        val json = """{"model":"depth_anything_v2_small","w":37}"""
        val png = DepthPng.encode(w, h, px, json)
        assertEquals(json, DepthPng.readText(png))
        val img = ImageIO.read(ByteArrayInputStream(png))
        assertEquals(w, img.width)
        assertEquals(h, img.height)
        val r = img.raster
        assertEquals(16, r.sampleModel.getSampleSize(0))
        for (y in 0 until h) {
            for (x in 0 until w) {
                for (b in 0 until 3) {
                    assertEquals("($x, $y) band $b", px[(y * w + x) * 3 + b].toInt() and 0xFFFF, r.getSample(x, y, b))
                }
            }
        }
    }

    @Test
    fun noMetadataWithoutTheChunk() {
        assertNull(DepthPng.readText(ByteArray(8)))
    }
}
