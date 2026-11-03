package com.redline.camera.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** B30: which encoder the HEIF writer takes (the B25 slowness was androidx.heifwriter taking the software CQ encoder). */
class PhotoHeifTest {
    private fun hevc(name: String, hw: Boolean, cq: Boolean, yuv: Boolean = true) =
        PhotoHeif.Candidate(name, PhotoHeif.HEVC_MIME, hw, cq, yuv, fullSize = true, tileSize = true)

    private fun heic(name: String, hw: Boolean, fullSize: Boolean = true) =
        PhotoHeif.Candidate(name, PhotoHeif.HEIC_MIME, hw, cq = true, yuvFlexible = true, fullSize = fullSize, tileSize = true)

    @Test
    fun hardwareHevcBeatsTheSoftwareCqEncoder() {
        // Platform order: hardware first, software CQ-capable later (what heifwriter 1.1.0 picked).
        val list = listOf(hevc("c2.vendor.hevc.encoder", hw = true, cq = false), hevc("c2.android.hevc.encoder", hw = false, cq = true))
        assertEquals("c2.vendor.hevc.encoder", PhotoHeif.choose(list, 2910, 1939)?.name)
    }

    @Test
    fun hardwareCqPreferredAmongHardware() {
        val list = listOf(
            hevc("hw.a", hw = true, cq = false),
            hevc("hw.b", hw = true, cq = true),
            hevc("sw", hw = false, cq = true)
        )
        assertEquals("hw.b", PhotoHeif.choose(list, 4000, 3000)?.name)
    }

    @Test
    fun hardwareHeicImageEncoderFirstForEvenSizes() {
        val list = listOf(hevc("hw.hevc", hw = true, cq = false), heic("hw.heic", hw = true))
        assertEquals("hw.heic", PhotoHeif.choose(list, 4000, 3000)?.name)
        // Odd height (4:2:0 needs even sizes) or a size it cannot take: the HEVC grid.
        assertEquals("hw.hevc", PhotoHeif.choose(list, 2910, 1939)?.name)
        assertEquals(
            "hw.hevc",
            PhotoHeif.choose(listOf(hevc("hw.hevc", true, false), heic("hw.heic", true, fullSize = false)), 4000, 3000)?.name
        )
    }

    @Test
    fun softwareOnlyWhenNothingElseAndNeedsYuvInput() {
        assertEquals("sw", PhotoHeif.choose(listOf(heic("sw.heic", hw = false), hevc("sw", hw = false, cq = false)), 4000, 3000)?.name)
        assertNull(PhotoHeif.choose(listOf(hevc("surface.only", hw = true, cq = true, yuv = false)), 4000, 3000))
        assertNull(PhotoHeif.choose(emptyList(), 4000, 3000))
    }

    @Test
    fun gridAndBudget() {
        assertEquals(6 to 4, PhotoHeif.grid(2910, 1939))
        assertEquals(8 to 6, PhotoHeif.grid(4000, 3000))
        assertEquals(1 to 1, PhotoHeif.grid(512, 512))
        // 2910 x 1939 x 1.5 x 8 x 0.25 x 0.92
        assertEquals(15_573_272, PhotoHeif.bitrate(2910, 1939, 92))
    }
}
