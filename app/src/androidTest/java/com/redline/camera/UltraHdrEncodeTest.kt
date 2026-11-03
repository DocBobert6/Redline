package com.redline.camera

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.redline.camera.capture.JpegStructure
import com.redline.camera.capture.PhotoHeif
import com.redline.camera.capture.PhotoJpeg
import com.redline.camera.capture.SaveFormatsUi
import com.redline.camera.capture.SaveSettings
import com.redline.camera.capture.StillSaver
import com.redline.camera.nativebridge.RedlineNative
import java.io.File
import kotlin.math.abs
import kotlin.math.ln
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B25 CI gate: the production JPEG path ([PhotoJpeg.write]: platform Ultra HDR encoder, then ExifInterface.saveAttributes)
 * keeps the gain map, and the SDR base decodes to exactly the pixels of the plain JPEG. The files are copied to
 * Downloads/RedlineCI so the workflow can adb-pull them and validate them on the host with libultrahdr.
 */
@RunWith(AndroidJUnit4::class)
class UltraHdrEncodeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun still(w: Int, h: Int): Bitmap {
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                // A ramp to white on the right (the highlights the map lifts) with some texture.
                val t = x.toDouble() / (w - 1)
                val v = (40 + 215 * t + 10 * Math.sin(x * 0.3) * Math.cos(y * 0.2)).toInt().coerceIn(0, 255)
                val g = (v * (0.9 + 0.1 * y / h)).toInt().coerceIn(0, 255)
                px[y * w + x] = (0xFF shl 24) or (v shl 16) or (g shl 8) or ((v * 0.8).toInt())
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)
    }

    /** Native-encoded map from cell sums that rise to ~1.5 stops at the bright edge (the production finish step). */
    private fun gainMap(w: Int, h: Int): RedlineNative.GainMapBytes {
        val cells = RedlineNative.GainMapCells(w, h, -1.5f)
        for (cy in 0 until cells.cellsH) {
            for (cx in 0 until cells.cellsW) {
                val t = cx.toDouble() / (cells.cellsW - 1)
                cells.data[cy * cells.cellsW + cx] = if (t < 0.5) 0f else (16 * 1.5 * (t - 0.5) * 2).toFloat()
            }
        }
        val b = RedlineNative.finishGainMap(cells, SaveSettings.HEADROOM_CAP_STOPS, SaveSettings.HEADROOM_MIN_STOPS)
        assertTrue("map written", b.written)
        return b
    }

    private fun exifWriter(): (File) -> Unit = { f ->
        // The same ExifInterface calls as StillSaver.writeExif (copied tags, orientation, sizes, software, look tag).
        val exif = ExifInterface(f.absolutePath)
        exif.setAttribute(ExifInterface.TAG_MAKE, "Google")
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, "Redline CI")
        exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, "redline look=standard | CI Ultra HDR test")
        exif.setAttribute(ExifInterface.TAG_USER_COMMENT, "redline look=standard | CI Ultra HDR test")
        exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, "24")
        exif.saveAttributes()
    }

    private fun pixels(f: File): IntArray {
        val o = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val b = BitmapFactory.decodeFile(f.absolutePath, o)
        assertNotNull("decodes", b)
        return IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
    }

    private fun toDownloads(f: File, name: String, mime: String) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/RedlineCI")
        }
        val r = context.contentResolver
        // A rerun on the same emulator replaces the old file.
        r.delete(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(name)
        )
        val uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore insert failed")
        r.openOutputStream(uri)!!.use { os -> f.inputStream().use { it.copyTo(os) } }
    }

    @Test
    fun ultraHdrJpegKeepsTheGainMapThroughExifAndTheBaseEqualsThePlainJpeg() {
        assumeTrue("Ultra HDR needs API 34", Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        assumeTrue("native library", RedlineNative.load().loaded)
        val w = 1200
        val h = 800
        val map = gainMap(w, h)
        assertEquals((w + 3) / 4, map.width)
        assertEquals((h + 3) / 4, map.height)
        assertEquals(1.5, map.headroomStops, 0.01)
        val dir = context.cacheDir
        val uhdr = File(dir, "uhdr.jpg")
        val plain = File(dir, "plain.jpg")
        val bmp = still(w, h)
        val r = PhotoJpeg.write(bmp, map, uhdr, StillSaver.JPEG_QUALITY, exifWriter())
        PhotoJpeg.write(bmp, null, plain, StillSaver.JPEG_QUALITY, exifWriter())
        Log.i(TAG, "uhdr ${uhdr.length()} B, plain ${plain.length()} B, gain map ${r.gainmapMs} ms, skip ${r.skip}")
        assertTrue("gain map written and verified after EXIF: ${r.skip}", r.ultraHdr)
        assertEquals(true, r.verified)
        assertTrue("bitmap carries no gain map after the encode", !bmp.hasGainmap())
        val info = JpegStructure.inspect(uhdr.readBytes())
        assertTrue("MPF ${info.mpf}, hdrgm ${info.hdrgm}, images ${info.images}", info.ultraHdr)
        assertTrue("plain JPEG is not Ultra HDR", !JpegStructure.inspect(plain.readBytes()).ultraHdr)
        // The EXIF write landed.
        assertEquals("Redline CI", ExifInterface(uhdr.absolutePath).getAttribute(ExifInterface.TAG_SOFTWARE))
        // The platform decoder sees the map and its metadata.
        val decoded = BitmapFactory.decodeFile(uhdr.absolutePath)
        assertTrue("decoded bitmap has the gain map", decoded.hasGainmap())
        val gm = decoded.gainmap!!
        assertEquals(Math.pow(2.0, map.headroomStops).toFloat(), gm.ratioMax[0], 0.02f)
        assertEquals(1f, gm.ratioMin[0], 1e-3f)
        assertEquals(1f, gm.gamma[0], 1e-3f)
        assertEquals(gm.ratioMax[0], gm.displayRatioForFullHdr, 0.02f)
        assertEquals(map.width, gm.gainmapContents.width)
        // SDR base: exactly the plain JPEG's pixels.
        assertArrayEquals("SDR base equals the plain JPEG", pixels(plain), pixels(uhdr))
        val mean = map.bytes.map { it.toInt() and 0xFF }.average()
        Log.i(
            TAG,
            "map mean ${"%.1f".format(mean)}, ratioMax ${gm.ratioMax[0]} (${"%.2f".format(ln(gm.ratioMax[0].toDouble()) / ln(2.0))} stops)"
        )
        toDownloads(uhdr, "redline_ci_uhdr.jpg", "image/jpeg")
        toDownloads(plain, "redline_ci_plain.jpg", "image/jpeg")
        assertTrue(abs(mean) > 0)
    }

    /**
     * B30: the HEIF writer (MediaCodec + MediaMuxer, YUV buffer input, 512 grid). Times the write, decodes the file back
     * (round-trip means within tolerance, size or rotated size), and reads the Exif item back. Codec and time split go
     * to the log for the CI summary.
     */
    @Test
    fun heifStillWritesTimedAndDecodes() {
        val (heif, why) = SaveFormatsUi.heifSupport()
        assumeTrue("HEIF encoder: $why", heif)
        assumeTrue("native library", RedlineNative.load().loaded)
        val f = File(context.cacheDir, "still.heic")
        // Odd width and height: the last grid column and row repeat the edge pixels.
        val w = 1455
        val h = 969
        val bmp = still(w, h)
        val tiny = File(context.cacheDir, "exif_stub.jpg")
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 50, tiny.outputStream())
        ExifInterface(tiny.absolutePath).apply {
            setAttribute(ExifInterface.TAG_SOFTWARE, "RedlineCI")
            saveAttributes()
        }
        val exif = JpegStructure.exifPayload(tiny.readBytes())
        assertNotNull("exif payload", exif)
        val r = PhotoHeif.write(bmp, 90, StillSaver.HEIF_QUALITY, exif, f)
        Log.i(TAG, "HEIF ${w}x$h: ${r.toMap()} bytes ${f.length()}")
        assertTrue(f.length() > 1000)
        val b = BitmapFactory.decodeFile(f.absolutePath)
        assertNotNull("HEIF decodes", b)
        // stored with irot 90; decoders may or may not apply the rotation
        assertTrue("${b.width}x${b.height}", (b.width == w && b.height == h) || (b.width == h && b.height == w))
        val check = r.check
        assertNotNull("first HEIF of the process carries the round-trip check", check)
        assertEquals("round trip $check", true, check!!["ok"])
        val back = ExifInterface(f.absolutePath)
        Log.i(
            TAG,
            "HEIF exif software=${back.getAttribute(
                ExifInterface.TAG_SOFTWARE
            )} orientation=${back.getAttribute(ExifInterface.TAG_ORIENTATION)}"
        )
        assertEquals("RedlineCI", back.getAttribute(ExifInterface.TAG_SOFTWARE))
        // A second write (codec list cached, not the first in the process) for the steady-state time.
        val f2 = File(context.cacheDir, "still2.heic")
        val r2 = PhotoHeif.write(bmp, 0, StillSaver.HEIF_QUALITY, null, f2)
        Log.i(TAG, "HEIF second write: ${r2.ms}")
        assertTrue(r2.ms.getValue("total") < 20_000)
        toDownloads(f, "redline_ci_still.heic", "image/heic")
        val json = File(context.cacheDir, "heif.json")
        json.writeText(
            org.json.JSONObject(
                mapOf(
                    "size" to "${w}x$h",
                    "first" to r.toMap(),
                    "second_ms" to r2.ms,
                    "bytes" to f.length(),
                    "api" to Build.VERSION.SDK_INT
                )
            ).toString(2)
        )
        toDownloads(json, "redline_ci_heif.json", "application/json")
    }

    companion object {
        private const val TAG = "UltraHdrEncodeTest"
    }
}
