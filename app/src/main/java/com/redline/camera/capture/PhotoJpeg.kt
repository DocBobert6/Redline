package com.redline.camera.capture

import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import com.redline.camera.nativebridge.RedlineNative
import java.io.File
import java.io.RandomAccessFile

/**
 * B25: the still's JPEG write, shared by the saver and the CI emulator test so both run the same code: encode (an
 * Ultra HDR JPEG when a gain map is given and the platform has the encoder, API 34+), then the EXIF write
 * ([exif]: ExifInterface.saveAttributes in production), then a check that the MPF entry still points at the gain map.
 */
object PhotoJpeg {
    class Result(
        /** A gain map was written and is still in the file after the EXIF write. */
        val ultraHdr: Boolean,
        /** Why a gain map that was given is not in the file (encoder, API, lost in the EXIF write); null otherwise. */
        val skip: String?,
        /** Gain map bitmap + metadata, ms. */
        val gainmapMs: Long?,
        /** After the EXIF write (null: no gain map was attempted). */
        val verified: Boolean?
    )

    fun write(bitmap: Bitmap, map: RedlineNative.GainMapBytes?, file: File, quality: Int, exif: (File) -> Unit): Result {
        var skip: String? = null
        var gainmapMs: Long? = null
        val attempt = map != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        if (map != null && !attempt) skip = "Ultra HDR needs Android 14 (API 34)"
        var attached = false
        if (attempt) {
            val t0 = SystemClock.elapsedRealtime()
            try {
                UltraHdrEncoder.attach(bitmap, map!!)
                attached = true
            } catch (e: Throwable) {
                skip = "gain map: ${e.javaClass.simpleName}: ${e.message}"
            }
            gainmapMs = SystemClock.elapsedRealtime() - t0
        }
        try {
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it)) { "JPEG encode failed" } }
        } finally {
            if (attached) UltraHdrEncoder.detach(bitmap)
        }
        exif(file)
        val verified = if (attached) gainmapSurvived(file) else null
        if (attached && verified != true) skip = "gain map lost in the EXIF write"
        return Result(attached && verified == true, skip, gainmapMs, verified)
    }

    /** The MPF entry of [file] still points at a JPEG (the gain map); reads the first 64 KB and two bytes. */
    fun gainmapSurvived(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { f ->
            val head = ByteArray(minOf(f.length(), HEAD_BYTES.toLong()).toInt())
            f.readFully(head)
            JpegStructure.mpfSecondImageOk(head, f.length()) { at ->
                f.seek(at)
                f.read()
            }
        }
    }.getOrDefault(false)

    private const val HEAD_BYTES = 65536
}
