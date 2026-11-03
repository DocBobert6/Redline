package com.redline.camera.capture

import android.graphics.Bitmap
import androidx.heifwriter.HeifWriter
import java.io.File

/** B25: the still as HEIF (androidx.heifwriter: HEIC image encoder, else HEVC), shared by the saver and the CI test. */
object PhotoHeif {
    /** [rotationDegrees] goes in the HEIF (irot); [exif] is an Exif payload ("Exif\\0\\0" + TIFF) or null. */
    fun write(bitmap: Bitmap, rotationDegrees: Int, quality: Int, exif: ByteArray?, file: File) {
        val writer = HeifWriter.Builder(file.absolutePath, bitmap.width, bitmap.height, HeifWriter.INPUT_MODE_BITMAP)
            .setQuality(quality)
            .setRotation(((rotationDegrees % 360) + 360) % 360)
            .build()
        writer.use { w ->
            w.start()
            w.addBitmap(bitmap)
            if (exif != null) w.addExifData(0, exif, 0, exif.size)
            w.stop(TIMEOUT_MS)
        }
        check(file.length() > 0) { "HEIF writer produced no data" }
    }

    private const val TIMEOUT_MS = 5000L
}
