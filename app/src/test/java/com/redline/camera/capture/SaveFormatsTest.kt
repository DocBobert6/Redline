package com.redline.camera.capture

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveFormatsTest {
    private val all = SaveCaps(raw = true, heif = true, ultraHdr = true)

    @Test
    fun defaultsAreJpegWithUltraHdrAndSingleFrameDng() {
        val s = SaveSettings()
        assertEquals(SaveFormat.JPEG, s.format)
        assertEquals(PhotoFormat.JPEG, s.photoFormat)
        assertTrue(s.ultraHdr)
        assertEquals(DngSource.SINGLE, s.dngSource)
        val e = s.effective(all)
        assertTrue(e.photo)
        assertFalse(e.dng)
        assertTrue(e.ultraHdr)
        assertEquals("JPEG (Ultra HDR)", e.label())
    }

    @Test
    fun effectiveSettingsFollowWhatThePhoneCanDo() {
        // DNG only without RAW: JPEG, never nothing
        val dngOnly = SaveSettings(format = SaveFormat.DNG).effective(all.copy(raw = false))
        assertEquals(SaveFormat.JPEG, dngOnly.format)
        assertTrue(dngOnly.notes.any { it.contains("no RAW") })
        // DNG only with RAW: no photo, no gain map
        val raw = SaveSettings(format = SaveFormat.DNG, dngSource = DngSource.MERGE).effective(all)
        assertFalse(raw.photo)
        assertTrue(raw.dng)
        assertFalse(raw.ultraHdr)
        assertEquals("DNG (burst merge)", raw.label())
        // HEIF without an encoder: JPEG; HEIF never carries Ultra HDR
        val heifNo = SaveSettings(photoFormat = PhotoFormat.HEIF).effective(all.copy(heif = false, heifReason = "none"))
        assertEquals(PhotoFormat.JPEG, heifNo.photoFormat)
        assertTrue(heifNo.ultraHdr)
        val heif = SaveSettings(format = SaveFormat.JPEG_DNG, photoFormat = PhotoFormat.HEIF).effective(all)
        assertEquals(PhotoFormat.HEIF, heif.photoFormat)
        assertFalse(heif.ultraHdr)
        assertEquals("HEIF + DNG (single frame)", heif.label())
        // below API 34
        val old = SaveSettings().effective(all.copy(ultraHdr = false))
        assertFalse(old.ultraHdr)
        assertTrue(old.notes.any { it.contains("API 34") })
    }

    @Test
    fun keysRoundTrip() {
        SaveFormat.entries.forEach { assertEquals(it, SaveFormat.of(it.key)) }
        PhotoFormat.entries.forEach { assertEquals(it, PhotoFormat.of(it.key)) }
        DngSource.entries.forEach { assertEquals(it, DngSource.of(it.key)) }
        assertEquals(SaveFormat.JPEG, SaveFormat.of(null))
        assertEquals(PhotoFormat.JPEG, PhotoFormat.of("bogus"))
    }

    @Test
    fun gainmapParamsAreLogEncodedFromHeadroom() {
        val p = GainmapParams(1.5)
        assertEquals(1f, p.ratioMin, 0f)
        assertEquals(2.828427f, p.ratioMax, 1e-5f)
        assertEquals(p.ratioMax, p.displayRatioForFullHdr, 0f)
        assertEquals(1f, p.gamma, 0f)
        assertEquals(0f, p.epsilonSdr, 0f)
        assertEquals(0f, p.epsilonHdr, 0f)
        assertEquals(1f, p.minDisplayRatioForHdrTransition, 0f)
    }

    @Test
    fun reportCountsWrittenAndSkippedGainMaps() {
        val e = SaveSettings().effective(all)
        var r = SaveFormatsReport(caps = all)
        r =
            r.record(
                FormatsShot(e, photoBytes = 4_000_000, ultraHdrWanted = true, ultraHdrWritten = true, headroomStops = 1.2, gainmapMs = 30)
            )
        r = r.record(FormatsShot(e, ultraHdrWanted = true, ultraHdrSkip = "headroom 0.10 stops < 0.20", gainmapMs = 10))
        assertEquals(2, r.shots)
        assertEquals(1, r.ultraHdrWritten)
        assertEquals(1, r.ultraHdrSkipped)
        assertEquals(30L, r.maxGainmapMs)
        val m = r.toMap()
        assertEquals(20L, m["avg_gainmap_ms"])
        assertEquals("headroom 0.10 stops < 0.20", m["last_skip_reason"])
        val last = m["last"] as Map<*, *>
        assertEquals("headroom 0.10 stops < 0.20", (last["ultra_hdr"] as Map<*, *>)["skip_reason"])
    }

    // ---------------------------------------------------------------------------------------------- DNG levels

    /** A minimal TIFF: IFD0 with WhiteLevel and BlackLevel (and a SubIFD holding them again, as DngCreator may). */
    private fun tiff(order: ByteOrder, whiteType: Int, blackType: Int, white: Long, black: Long): ByteArray {
        val buf = ByteBuffer.allocate(512).order(order)
        buf.put(if (order == ByteOrder.LITTLE_ENDIAN) "II".toByteArray() else "MM".toByteArray())
        buf.putShort(42)
        buf.putInt(8)
        // IFD0 at 8: 3 entries (white, black x4, SubIFDs) + next 0
        val dataArea = 8 + 2 + 3 * 12 + 4 // 50
        buf.position(8)
        buf.putShort(3)
        fun entry(tag: Int, type: Int, count: Int, value: (ByteBuffer) -> Unit) {
            buf.putShort(tag.toShort())
            buf.putShort(type.toShort())
            buf.putInt(count)
            val p = buf.position()
            value(buf)
            buf.position(p + 4)
        }
        entry(DngLevels.TAG_WHITE_LEVEL, whiteType, 1) { if (whiteType == 3) it.putShort(white.toShort()) else it.putInt(white.toInt()) }
        // BlackLevel x4 out of line at dataArea
        entry(DngLevels.TAG_BLACK_LEVEL, blackType, 4) { it.putInt(dataArea) }
        entry(0x014A, 4, 1) { it.putInt(200) }
        buf.putInt(0)
        buf.position(dataArea)
        repeat(4) {
            when (blackType) {
                3 -> buf.putShort(black.toShort())
                4 -> buf.putInt(black.toInt())
                5 -> {
                    buf.putInt(black.toInt())
                    buf.putInt(1)
                }
            }
        }
        // SubIFD at 200: WhiteLevel only
        buf.position(200)
        buf.putShort(1)
        entry(DngLevels.TAG_WHITE_LEVEL, 4, 1) { it.putInt(white.toInt()) }
        buf.putInt(0)
        return buf.array()
    }

    private fun read(b: ByteArray, order: ByteOrder, at: Int, type: Int): Long {
        val bb = ByteBuffer.wrap(b).order(order)
        return if (type == 3) (bb.getShort(at).toLong() and 0xFFFF) else (bb.getInt(at).toLong() and 0xFFFFFFFFL)
    }

    @Test
    fun dngLevelsAreScaledInEveryIfdForEveryType() {
        for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            for ((wt, bt) in listOf(3 to 3, 4 to 4, 3 to 5, 4 to 5)) {
                val b = tiff(order, wt, bt, 1023, 64)
                val f = File.createTempFile("dng", ".dng").apply { writeBytes(b) }
                assertTrue("$order $wt/$bt", DngLevels.patch(f, 16))
                val p = f.readBytes()
                f.delete()
                assertEquals(16368L, read(p, order, 8 + 2 + 8, wt))
                val step = when (bt) {
                    3 -> 2
                    4 -> 4
                    else -> 8
                }
                for (k in 0 until 4) assertEquals(1024L, read(p, order, 50 + k * step, if (bt == 3) 3 else 4))
                if (bt == 5) assertEquals(1L, read(p, order, 50 + 4, 4)) // denominator untouched
                assertEquals(16368L, read(p, order, 200 + 2 + 8, 4))
            }
        }
    }

    @Test
    fun dngLevelsRefuseWhatTheyCannotScale() {
        // SHORT white 8191 x 16 overflows 16 bits: nothing is written, the caller divides the data back instead
        val b = tiff(ByteOrder.LITTLE_ENDIAN, 3, 3, 8191, 64)
        assertNull(DngLevels.plan(b, 16))
        val f = File.createTempFile("dng", ".dng").apply { writeBytes(b) }
        assertFalse(DngLevels.patch(f, 16))
        assertTrue(f.readBytes().contentEquals(b))
        f.delete()
        assertNull(DngLevels.plan(ByteArray(16), 16))
        assertNull(DngLevels.plan("II*\u0000".toByteArray() + ByteArray(4), 16))
    }

    // ---------------------------------------------------------------------------------------------- JPEG structure

    private fun segment(marker: Int, payload: ByteArray): ByteArray =
        byteArrayOf(0xFF.toByte(), marker.toByte(), ((payload.size + 2) shr 8).toByte(), (payload.size + 2).toByte()) + payload

    private fun jpeg(vararg segments: ByteArray, scan: ByteArray = byteArrayOf(1, 2, 0xFF.toByte(), 0, 3)): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        segments.forEach { out.write(it) }
        out.write(segment(0xDA, byteArrayOf(1, 0, 0)))
        out.write(scan)
        out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        return out.toByteArray()
    }

    @Test
    fun ultraHdrStructureIsRecognised() {
        val exif = "Exif\u0000\u0000II*\u0000".toByteArray()
        val gainmap = jpeg(segment(0xE1, "http://ns.adobe.com/xap/1.0/\u0000<hdrgm:Version>".toByteArray()))
        val primary = jpeg(
            segment(0xE1, exif),
            segment(0xE1, "http://ns.adobe.com/xap/1.0/\u0000 xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\"".toByteArray()),
            segment(0xE2, "MPF\u0000MM".toByteArray())
        )
        val uhdr = JpegStructure.inspect(primary + gainmap)
        assertTrue(uhdr.mpf)
        assertTrue(uhdr.hdrgm)
        assertEquals(2, uhdr.images)
        assertTrue(uhdr.ultraHdr)
        assertEquals(primary.size, uhdr.primaryEnd)
        val plain = JpegStructure.inspect(jpeg(segment(0xE1, exif)))
        assertFalse(plain.ultraHdr)
        assertEquals(1, plain.images)
        // gain map stripped (trailing image lost)
        assertFalse(JpegStructure.inspect(primary).ultraHdr)
        assertTrue(JpegStructure.exifPayload(primary)!!.contentEquals(exif))
        assertNull(JpegStructure.exifPayload(jpeg()))
        assertNotNull(JpegStructure.exifPayload(primary + gainmap))
    }

    /** MPF APP2 payload (big-endian) whose second entry points [offset] bytes past the MPF header at an image of [size]. */
    private fun mpf(offset: Int, size: Int): ByteArray {
        val bb = ByteBuffer.allocate(4 + 8 + 2 + 3 * 12 + 4 + 32).order(ByteOrder.BIG_ENDIAN)
        bb.put("MPF\u0000".toByteArray())
        bb.put("MM".toByteArray())
        bb.putShort(42)
        bb.putInt(8)
        bb.putShort(3)
        bb.putShort(0xB000.toShort()).putShort(7).putInt(4).put("0100".toByteArray())
        bb.putShort(0xB001.toShort()).putShort(4).putInt(1).putInt(2)
        bb.putShort(0xB002.toShort()).putShort(7).putInt(32).putInt(8 + 2 + 36 + 4)
        bb.putInt(0)
        bb.putInt(0x030000).putInt(1000).putInt(0).putShort(0).putShort(0)
        bb.putInt(0).putInt(size).putInt(offset).putShort(0).putShort(0)
        return bb.array()
    }

    @Test
    fun mpfEntryMustPointAtTheGainMap() {
        val gainmap = jpeg()

        // The MPF header (byte-order mark) sits 2 (SOI) + 4 (APP2 marker, length) + 4 (MPF tag) = 10 bytes into the file.
        fun build(offsetFix: Int): ByteArray {
            var primary = jpeg(segment(0xE2, mpf(0, gainmap.size)))
            val off = primary.size - 10 + offsetFix
            primary = jpeg(segment(0xE2, mpf(off, gainmap.size)))
            return primary + gainmap
        }
        assertTrue(JpegStructure.mpfSecondImageOk(build(0)))
        assertFalse(JpegStructure.mpfSecondImageOk(build(3)))
        val good = build(0)
        assertFalse(JpegStructure.mpfSecondImageOk(good.copyOf(good.size - gainmap.size))) // map dropped
        assertFalse(JpegStructure.mpfSecondImageOk(jpeg()))
    }
}
