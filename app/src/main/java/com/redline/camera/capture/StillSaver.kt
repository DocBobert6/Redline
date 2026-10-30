package com.redline.camera.capture

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.ExifInterface
import android.media.Image
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.redline.camera.capability.Dim
import com.redline.camera.colour.ColourPipeline
import com.redline.camera.colour.ColourShot
import java.io.ByteArrayInputStream
import java.io.File
import java.io.OutputStream

/** Everything one still capture produced, handed from the camera thread to the saver. */
class StillShot(
    val jpeg: ByteArray,
    /** RAW_SENSOR image, open until the saver has rendered it and/or written the DNG (the saver closes it). */
    val raw: Image?,
    val result: TotalCaptureResult,
    val characteristics: CameraCharacteristics,
    val jpegOrientation: Int,
    val stopLabel: String,
    val stopTargetMm: Int,
    val trueEquivalentMm: Float?,
    val stopLongPx: Int,
    val shutterAtMs: Long,
    /** False for stress-test shots: the files are fully written through MediaStore, then deleted. */
    val keep: Boolean,
    /** B21: write the RAW frame as a DNG (the DNG toggle). RAW can be in the shot for the pipeline alone. */
    val saveDng: Boolean = raw != null,
    /** B21: render the still from [raw] through the colour pipeline instead of re-encoding the camera JPEG. */
    val renderRaw: Boolean = false,
    val cameraId: String = "",
    val zoomRatio: Float = 1f
)

/**
 * Still saving (PLAN 6 step 9). B21: with the colour pipeline on, the RAW_SENSOR frame is rendered through it
 * (3:2 crop at the zoom stop, the stop's honest output size, Standard look) and that is the JPEG; if it cannot
 * render, the B13 path is used: the HAL JPEG is cropped to the viewfinder's 3:2, scaled to the stop's output size
 * and re-encoded. Either way the JPEG gets EXIF (camera tags copied, Make/Model untouched, look tag added) and is
 * saved to DCIM/Redline through MediaStore. With the DNG toggle on, the RAW frame is also written as a DNG with
 * DngCreator (full sensor, uncropped, no crop tag).
 */
class StillSaver(context: Context, private val version: String, private val pipeline: ColourPipeline? = null) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver

    /** Runs on the saver thread. Never throws; always closes [StillShot.raw]. */
    fun save(shot: StillShot, nowMs: () -> Long): ShotResult {
        val base = CaptureMath.baseName(System.currentTimeMillis())
        val raw = shot.raw
        var colour: ColourShot? = null
        var rendered: Bitmap? = null
        if (shot.renderRaw && raw != null && pipeline != null) {
            val o = pipeline.render(raw, shot.result, shot.characteristics, shot.cameraId, shot.jpeg, shot.zoomRatio, shot.stopLongPx)
            rendered = o.bitmap
            colour = o.shot
        } else if (shot.renderRaw) {
            colour = ColourShot(rendered = false, fallbackReason = if (raw == null) "no RAW frame in this shot" else "pipeline unavailable")
        }
        val lookName = if (rendered != null) ColourPipeline.LOOK_NAME else LOOK_NONE
        val look = CaptureMath.lookTag(version, lookName, shot.stopLabel, shot.trueEquivalentMm)
        var jpegSize: Dim? = null
        var error: String? = null
        val jpegOk = try {
            if (rendered != null) {
                val t = nowMs()
                jpegSize = saveRendered(shot, rendered, base, look)
                colour = colour?.let { it.copy(timingsMs = it.timingsMs + ("encode_save" to (nowMs() - t))) }
            } else {
                jpegSize = saveJpeg(shot, base, look)
            }
            true
        } catch (e: Throwable) {
            Log.w(TAG, "JPEG save failed", e)
            error = "JPEG: ${e.javaClass.simpleName}: ${e.message}"
            false
        } finally {
            rendered?.recycle()
        }
        var dngOk = false
        val wantDng = raw != null && shot.saveDng
        if (raw != null) {
            try {
                if (wantDng) {
                    saveDng(shot, raw, base, look)
                    dngOk = true
                }
            } catch (e: Throwable) {
                Log.w(TAG, "DNG save failed", e)
                if (error == null) error = "DNG: ${e.javaClass.simpleName}: ${e.message}"
            } finally {
                runCatching { raw.close() }
            }
        }
        return ShotResult(
            ok = jpegOk,
            jpegSize = jpegSize,
            dngSaved = dngOk,
            dngWanted = wantDng,
            shutterToSavedMs = nowMs() - shot.shutterAtMs,
            error = error,
            kept = shot.keep,
            look = lookName,
            colour = colour
        )
    }

    /** B21: the pipeline's bitmap (sensor orientation) as the JPEG; EXIF orientation comes from the shot. */
    private fun saveRendered(shot: StillShot, bmp: Bitmap, base: String, look: String): Dim {
        val original = ExifInterface(ByteArrayInputStream(shot.jpeg))
        val size = Dim(bmp.width, bmp.height)
        val tmp = File.createTempFile(base, ".jpg", app.cacheDir)
        try {
            tmp.outputStream().use { check(bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)) { "JPEG encode failed" } }
            writeExif(tmp, original, shot, size, look, CaptureMath.exifOrientation(shot.jpegOrientation))
            insert("$base.jpg", "image/jpeg", shot.keep) { os -> tmp.inputStream().use { it.copyTo(os) } }
        } finally {
            tmp.delete()
        }
        return size
    }

    private fun saveJpeg(shot: StillShot, base: String, look: String): Dim {
        val bytes = shot.jpeg
        val original = ExifInterface(ByteArrayInputStream(bytes))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "undecodable JPEG (${bytes.size} bytes)" }
        val crop = CaptureMath.crop32(bounds.outWidth, bounds.outHeight)
        val out = CaptureMath.outputSize(crop, shot.stopLongPx)
        val decoder = newRegionDecoder(bytes)
        val region = try {
            decoder.decodeRegion(Rect(crop.left, crop.top, crop.left + crop.width, crop.top + crop.height), null)
                ?: error("region decode returned null")
        } finally {
            decoder.recycle()
        }
        val scaled = if (region.width != out.width || region.height != out.height) {
            Bitmap.createScaledBitmap(region, out.width, out.height, true).also { if (it !== region) region.recycle() }
        } else {
            region
        }
        val tmp = File.createTempFile(base, ".jpg", app.cacheDir)
        try {
            tmp.outputStream().use { check(scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)) { "JPEG encode failed" } }
            scaled.recycle()
            writeExif(tmp, original, shot, out, look)
            insert("$base.jpg", "image/jpeg", shot.keep) { os -> tmp.inputStream().use { it.copyTo(os) } }
        } finally {
            tmp.delete()
        }
        return out
    }

    private fun writeExif(file: File, original: ExifInterface, shot: StillShot, size: Dim, look: String, orientation: Int? = null) {
        val exif = ExifInterface(file.absolutePath)
        COPIED_TAGS.forEach { tag -> original.getAttribute(tag)?.let { exif.setAttribute(tag, it) } }
        if (orientation != null) {
            // The RAW render keeps the sensor orientation whatever the HAL did to its own JPEG.
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
        } else if (original.getAttribute(ExifInterface.TAG_ORIENTATION) == null) {
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, CaptureMath.exifOrientation(shot.jpegOrientation).toString())
        }
        exif.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, size.width.toString())
        exif.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, size.height.toString())
        exif.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, size.width.toString())
        exif.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, size.height.toString())
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, "Redline $version")
        exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, look)
        exif.setAttribute(ExifInterface.TAG_USER_COMMENT, look)
        exif.setAttribute(
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            CaptureMath.focal35(shot.stopTargetMm, shot.trueEquivalentMm).toString()
        )
        exif.saveAttributes()
    }

    private fun saveDng(shot: StillShot, raw: Image, base: String, look: String) {
        DngCreator(shot.characteristics, shot.result).use { dng ->
            dng.setOrientation(CaptureMath.exifOrientation(shot.jpegOrientation))
            dng.setDescription(look)
            insert("$base.dng", "image/x-adobe-dng", shot.keep) { os -> dng.writeImage(os, raw) }
        }
    }

    /** Pending insert, write, publish; a stress shot is deleted after the full write instead of published. */
    private fun insert(name: String, mime: String, keep: Boolean, write: (OutputStream) -> Unit) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Redline")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri: Uri = resolver.insert(collection, values) ?: error("MediaStore insert returned null for $mime")
        try {
            val os = resolver.openOutputStream(uri) ?: error("no output stream for $uri")
            os.use(write)
            if (keep) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } else {
                resolver.delete(uri, null, null)
            }
        } catch (e: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private fun newRegionDecoder(bytes: ByteArray): BitmapRegionDecoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
    } else {
        BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
    } ?: error("no region decoder")

    companion object {
        private const val TAG = "RedlineSaver"
        const val JPEG_QUALITY = 95

        /** The still came from the camera JPEG (pipeline off or fell back); the tag says so. */
        const val LOOK_NONE = "none (camera JPEG)"

        private val COPIED_TAGS = listOf(
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_SUBSEC_TIME,
            ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_ISO_SPEED_RATINGS,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_APERTURE_VALUE,
            ExifInterface.TAG_SHUTTER_SPEED_VALUE,
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
            ExifInterface.TAG_EXPOSURE_PROGRAM,
            ExifInterface.TAG_METERING_MODE,
            ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_COLOR_SPACE
        )
    }
}
