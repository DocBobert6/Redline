package com.redline.camera.capture

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import com.redline.camera.capability.Dim
import com.redline.camera.colour.ColourPipeline
import com.redline.camera.colour.ColourShot
import com.redline.camera.nativebridge.RedlineNative
import java.io.ByteArrayInputStream
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer

/** Everything one still capture produced, handed from the camera thread to the saver. */
class StillShot(
    val jpeg: ByteArray,
    /** B22: pool copy of the RAW_SENSOR frame of the single-frame path (same request as [jpeg]); null otherwise. */
    val raw: RawCopy?,
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
    /** B21: render the still from RAW through the colour pipeline instead of re-encoding the camera JPEG. */
    val renderRaw: Boolean = false,
    val cameraId: String = "",
    val zoomRatio: Float = 1f,
    /** B22: the RAW burst (its frames are merged, rendered, and the reference frame is the DNG). */
    val burst: BurstCapture? = null,
    /** B22: the single-frame path's record for the burst report (why no burst), or null when burst merge is off. */
    val singlePlan: BurstPlan? = null,
    val stress: Boolean = false,
    /** Jobs ahead of this one when it was queued. */
    val queuedBehind: Int = 0,
    /** When the capture was complete and the job queued (SystemClock.elapsedRealtime). */
    val queuedAtMs: Long = shutterAtMs,
    /** Returns the shot's pool buffers; called exactly once by the saver when it is done. */
    val release: () -> Unit = {}
)

/** B22: the burst frames (frame order) with their results, the pool buffer the merge writes into, and the plan. */
class BurstCapture(
    val frames: List<RawCopy>,
    val results: List<TotalCaptureResult>,
    val out: ByteBuffer,
    val plan: BurstPlan,
    /** Shutter to the last frame in, ms. */
    val captureMs: Long
)

/**
 * Still saving (PLAN 6 step 9). B21: with the colour pipeline on, the RAW_SENSOR frame is rendered through it
 * (3:2 crop at the zoom stop, the stop's honest output size, Standard look) and that is the JPEG; if it cannot
 * render, the B13 path is used: the HAL JPEG is cropped to the viewfinder's 3:2, scaled to the stop's output size
 * and re-encoded. Either way the JPEG gets EXIF (camera tags copied, Make/Model untouched, look tag added) and is
 * saved to DCIM/Redline through MediaStore. With the DNG toggle on, the RAW frame is also written as a DNG with
 * DngCreator (full sensor, uncropped, no crop tag).
 *
 * B22: a burst shot is aligned and merged natively first and the merged mosaic rendered the same way; if the merge
 * or its render fails, the burst's reference frame is rendered alone (the B21 single-frame path). The DNG is always
 * the single reference frame (full sensor, uncropped); a DNG of the merge is B25.
 */
class StillSaver(context: Context, private val version: String, private val pipeline: ColourPipeline? = null) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver

    /** Runs on the saver (processing queue) thread. Never throws; always calls [StillShot.release]. */
    fun save(shot: StillShot, nowMs: () -> Long): ShotResult {
        try {
            return saveInner(shot, nowMs)
        } finally {
            runCatching { shot.release() }
        }
    }

    private fun saveInner(shot: StillShot, nowMs: () -> Long): ShotResult {
        val startMs = nowMs()
        val base = CaptureMath.baseName(System.currentTimeMillis())
        var colour: ColourShot? = null
        var rendered: Bitmap? = null
        var dngRaw: RawCopy? = shot.raw
        var dngResult: TotalCaptureResult = shot.result
        var burstShot: BurstShot? = shot.singlePlan?.let { BurstShot(plan = it, stopMm = shot.stopTargetMm, stress = shot.stress) }
        val b = shot.burst
        if (b != null) {
            val timings = linkedMapOf("capture" to b.captureMs, "queue_wait" to (startMs - shot.queuedAtMs).coerceAtLeast(0))
            var ref = 0
            var stats: RedlineNative.MergeStats? = null
            var fallback: String? = null
            var noiseModel: String? = null
            var scale = MERGE_SCALE
            if (shot.renderRaw && pipeline != null) {
                val t = nowMs()
                try {
                    val levels = pipeline.levels(b.results[0], shot.characteristics) ?: error("no black/white levels")
                    val noise = noiseModel(b.results[0])
                    noiseModel = noise.third
                    scale = mergeScale(levels.second)
                    val f0 = b.frames[0]
                    stats = RedlineNative.mergeBurst(
                        b.frames.map { it.buffer }, f0.width, f0.height, f0.rowStrideBytes, levels.first, levels.second,
                        noise.first, noise.second, -1, threads(), b.out, scale
                    )
                    ref = stats.reference
                } catch (e: Exception) {
                    fallback = "merge: ${e.javaClass.simpleName}: ${e.message}"
                } catch (e: OutOfMemoryError) {
                    fallback = "merge: out of memory"
                }
                timings["merge_total"] = nowMs() - t
                stats?.let {
                    timings["pyramid"] = Math.round(it.pyramidMs)
                    timings["reference"] = Math.round(it.referenceMs)
                    timings["align"] = Math.round(it.alignMs)
                    timings["merge"] = Math.round(it.mergeMs)
                    timings["finish"] = Math.round(it.finishMs)
                    val f0 = b.frames[0]
                    val merged = RawCopy(b.out, f0.width, f0.height, b.frames[ref].timestampNs)
                    val o = pipeline.render(
                        merged,
                        b.results[ref],
                        shot.characteristics,
                        shot.cameraId,
                        shot.jpeg,
                        shot.zoomRatio,
                        shot.stopLongPx,
                        scale
                    )
                    rendered = o.bitmap
                    colour = o.shot
                    if (rendered == null) fallback = "merged render: ${o.shot.fallbackReason}"
                }
                if (rendered == null) {
                    val o = pipeline.render(
                        b.frames[ref],
                        b.results[ref],
                        shot.characteristics,
                        shot.cameraId,
                        shot.jpeg,
                        shot.zoomRatio,
                        shot.stopLongPx
                    )
                    rendered = o.bitmap
                    colour = o.shot
                }
                colour?.timingsMs?.get("total")?.let { timings["render"] = it }
            } else {
                fallback = "colour pipeline unavailable"
            }
            dngRaw = b.frames[ref]
            dngResult = b.results[ref]
            val per = stats?.perFrame.orEmpty()
            burstShot = BurstShot(
                plan = b.plan,
                stopMm = shot.stopTargetMm,
                framesCaptured = b.frames.size,
                framesMerged = if (stats != null && fallback == null) stats.frames else 0,
                reference = if (stats != null) ref else null,
                merged = stats != null && fallback == null,
                fallbackReason = fallback,
                noiseModel = noiseModel,
                meanDispPx = per.takeIf { it.isNotEmpty() }?.map { it.meanDispPx }?.average(),
                maxDispPx = per.maxOfOrNull { it.maxDispPx },
                meanWeight = per.takeIf { it.isNotEmpty() }?.map { it.meanWeight }?.average(),
                rejectedFraction = per.takeIf { it.isNotEmpty() }?.map { it.rejectedFraction }?.average(),
                timingsMs = timings,
                queuedBehind = shot.queuedBehind,
                stress = shot.stress
            )
        } else if (shot.renderRaw && shot.raw != null && pipeline != null) {
            val o = pipeline.render(shot.raw, shot.result, shot.characteristics, shot.cameraId, shot.jpeg, shot.zoomRatio, shot.stopLongPx)
            rendered = o.bitmap
            colour = o.shot
        } else if (shot.renderRaw) {
            colour =
                ColourShot(rendered = false, fallbackReason = if (shot.raw == null) "no RAW frame in this shot" else "pipeline unavailable")
        }
        val lookName = if (rendered != null) ColourPipeline.LOOK_NAME else LOOK_NONE
        val look = CaptureMath.lookTag(version, lookName, shot.stopLabel, shot.trueEquivalentMm)
        var jpegSize: Dim? = null
        var error: String? = null
        val encodeStart = nowMs()
        val jpegOk = try {
            val bmp = rendered
            if (bmp != null) {
                jpegSize = saveRendered(shot, bmp, base, look)
                colour = colour?.let { it.copy(timingsMs = it.timingsMs + ("encode_save" to (nowMs() - encodeStart))) }
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
        val encodeMs = nowMs() - encodeStart
        var dngOk = false
        val dngSource = dngRaw
        val wantDng = dngSource != null && shot.saveDng
        val dngStart = nowMs()
        if (dngSource != null && wantDng) {
            try {
                saveDng(shot, dngSource, dngResult, base, look)
                dngOk = true
            } catch (e: Throwable) {
                Log.w(TAG, "DNG save failed", e)
                if (error == null) error = "DNG: ${e.javaClass.simpleName}: ${e.message}"
            }
        }
        val dngMs = nowMs() - dngStart
        val total = nowMs() - shot.shutterAtMs
        burstShot = burstShot?.let {
            if (it.plan.isBurst) {
                it.copy(timingsMs = it.timingsMs + linkedMapOf("encode_save" to encodeMs, "dng" to dngMs, "total" to total))
            } else {
                it
            }
        }
        return ShotResult(
            ok = jpegOk,
            jpegSize = jpegSize,
            dngSaved = dngOk,
            dngWanted = wantDng,
            shutterToSavedMs = total,
            error = error,
            kept = shot.keep,
            look = lookName,
            colour = colour,
            burst = burstShot
        )
    }

    /**
     * Camera2 SENSOR_NOISE_PROFILE (S, O per CFA channel, normalised signal) averaged over channels; without it a
     * rough ISO-scaled default (marked in the report).
     */
    private fun noiseModel(r: TotalCaptureResult): Triple<Double, Double, String> {
        val p = runCatching { r.get(CaptureResult.SENSOR_NOISE_PROFILE) }.getOrNull()
        if (p != null && p.isNotEmpty()) {
            val s = p.map { it.first }.average()
            val o = p.map { it.second }.average()
            if (s.isFinite() && o.isFinite() && s >= 0 && o >= 0) return Triple(s, o, "camera")
        }
        val iso = (r.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100).toDouble()
        return Triple(DEFAULT_NOISE_S_PER_ISO * iso, DEFAULT_NOISE_O_PER_ISO2 * iso * iso, "iso_default")
    }

    private fun threads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, MAX_THREADS)

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

    /** The single RAW frame as a DNG (full sensor, uncropped, no crop tag), from its pool copy and its own result. */
    private fun saveDng(shot: StillShot, raw: RawCopy, result: TotalCaptureResult, base: String, look: String) {
        DngCreator(shot.characteristics, result).use { dng ->
            dng.setOrientation(CaptureMath.exifOrientation(shot.jpegOrientation))
            dng.setDescription(look)
            insert("$base.dng", "image/x-adobe-dng", shot.keep) { os ->
                dng.writeByteBuffer(os, Size(raw.width, raw.height), raw.buffer.duplicate().also { it.clear() }, 0L)
            }
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

        /** B22: the merged mosaic is written x16 (4 extra bits of the averaged precision; 1023 * 16 fits 16 bits). */
        const val MERGE_SCALE = 16f

        /** [MERGE_SCALE], or less for a sensor whose white level would overflow 16 bits. */
        fun mergeScale(white: Float): Float = minOf(MERGE_SCALE, kotlin.math.floor(65535f / white)).coerceAtLeast(1f)
        private const val MAX_THREADS = 8

        /** Rough noise model when the result has no SENSOR_NOISE_PROFILE (normalised units). */
        private const val DEFAULT_NOISE_S_PER_ISO = 2e-6
        private const val DEFAULT_NOISE_O_PER_ISO2 = 2e-10

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
