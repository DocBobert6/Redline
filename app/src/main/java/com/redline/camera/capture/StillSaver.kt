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
import kotlin.math.abs

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
    /** B22a: [BurstShot.PATH_SINGLE] (a RAW frame rendered alone) or [BurstShot.PATH_JPEG] (the camera JPEG). */
    val singlePath: String = BurstShot.PATH_SINGLE,
    val stress: Boolean = false,
    /** Shots still in flight (capturing, merging or saving) when this one was pressed. */
    val queuedBehind: Int = 0,
    /** When the job was queued for saving (SystemClock.elapsedRealtime): capture complete, or a burst's merge done. */
    val queuedAtMs: Long = shutterAtMs,
    /** B22b: the shot's press-to-file timings (the saver adds its own steps). */
    val timeline: ShotTimeline? = null,
    /** B23: the 50/70 mm super-resolution decision (applies: saved at its output size, merged or upscaled). */
    val superRes: SuperResDecision? = null,
    /** Returns the shot's pool buffers; called exactly once by the saver when it is done. */
    val release: () -> Unit = {}
)

/**
 * B22a: a burst after its streaming merge: the merged mosaic (x [mergedScale]) in a pool buffer, the reference
 * frame's capture result, the untouched reference for the DNG (DNG on), or, when the merge failed, one RAW frame to
 * render alone (the B21 single-frame path).
 */
class BurstCapture(
    val plan: BurstPlan,
    val framesCaptured: Int,
    val merged: RawCopy?,
    val mergedScale: Float,
    val stats: RedlineNative.MergeStats?,
    val referenceResult: TotalCaptureResult,
    val dngRaw: RawCopy?,
    val fallbackRaw: RawCopy?,
    val fallbackResult: TotalCaptureResult?,
    val fallbackReason: String?,
    val noiseModel: String?,
    /** Shutter to the last frame in, ms. */
    val captureMs: Long,
    /** Last frame in to merge finished, ms. */
    val mergeTailMs: Long,
    val streaming: BurstStreaming,
    /** B22b: press to the shutter being free (all RAW frames in), ms. */
    val releaseMs: Long? = null,
    /** B23: the finished super-resolution merge (the saver renders and closes it), its stats, or why it failed. */
    val srImage: RedlineNative.SuperResMerger? = null,
    val srStats: RedlineNative.SrStats? = null,
    val srError: String? = null
)

/**
 * B23b: a shot between the render lane and the store lane ([StillSaver.render] -> [StillSaver.store]): the rendered
 * bitmap (null = save the camera JPEG), the report pieces the render filled in, and the DNG source. Holds the shot's
 * pool buffers until the store releases them.
 */
class StagedShot(
    val shot: StillShot,
    val base: String,
    val renderStartMs: Long,
    val renderDoneMs: Long,
    @Volatile var rendered: Bitmap?,
    val colour: ColourShot?,
    val burstShot: BurstShot?,
    val dngRaw: RawCopy?,
    val dngResult: TotalCaptureResult,
    val srMerged: Boolean,
    val srFallback: String?,
    val lookName: String,
    val look: String
)

/**
 * Still saving (PLAN 6 step 9). B21: with the colour pipeline on, the RAW_SENSOR frame is rendered through it
 * (3:2 crop at the zoom stop, the stop's honest output size, Standard look) and that is the JPEG; if it cannot
 * render, the B13 path is used: the HAL JPEG is cropped to the viewfinder's 3:2, scaled to the stop's output size
 * and re-encoded. Either way the JPEG gets EXIF (camera tags copied, Make/Model untouched, look tag added) and is
 * saved to DCIM/Redline through MediaStore. With the DNG toggle on, the RAW frame is also written as a DNG with
 * DngCreator (full sensor, uncropped, no crop tag).
 *
 * B22: a burst shot is aligned and merged natively and the merged mosaic rendered the same way. B22a: the merge
 * streams on its own thread while the frames arrive (ViewfinderController), so the saver receives the merged mosaic;
 * if the merge failed, one burst frame is rendered alone (the B21 single-frame path), and if nothing renders, the
 * camera JPEG is saved. The DNG is always the single reference frame (full sensor, uncropped); a DNG of the merge is B25.
 */
class StillSaver(context: Context, private val version: String, private val pipeline: ColourPipeline? = null) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver

    /** Render then store on the calling thread (the two lanes in one). Never throws; always calls [StillShot.release]. */
    fun save(shot: StillShot, nowMs: () -> Long): ShotResult = store(render(shot, nowMs), nowMs)

    /**
     * B23b, render lane: the colour pipeline (merged, super-resolution or single frame) into a bitmap, ready for
     * [store]. Never throws: a render that fails leaves no bitmap and the store saves the camera JPEG. The shot's pool
     * buffers stay held (the DNG still reads its RAW frame); [store] returns them.
     */
    fun render(shot: StillShot, nowMs: () -> Long): StagedShot {
        val startMs = nowMs()
        shot.timeline?.mark(ShotTimeline.SAVE_START, startMs)
        val base = CaptureMath.baseName(System.currentTimeMillis())
        return try {
            renderInner(shot, nowMs, startMs, base)
        } catch (e: Throwable) {
            Log.w(TAG, "render failed", e)
            runCatching { shot.burst?.srImage?.close() }
            val doneMs = nowMs()
            shot.timeline?.mark(ShotTimeline.RENDER_DONE, doneMs)
            StagedShot(
                shot = shot,
                base = base,
                renderStartMs = startMs,
                renderDoneMs = doneMs,
                rendered = null,
                colour = if (shot.renderRaw) ColourShot(rendered = false, fallbackReason = "render: ${e.javaClass.simpleName}") else null,
                burstShot = shot.singlePlan?.let {
                    BurstShot(
                        plan = it,
                        stopMm = shot.stopTargetMm,
                        stress = shot.stress,
                        queuedBehind = shot.queuedBehind,
                        path = shot.singlePath
                    )
                },
                dngRaw = shot.raw ?: shot.burst?.dngRaw,
                dngResult = shot.burst?.referenceResult ?: shot.result,
                srMerged = false,
                srFallback = null,
                lookName = LOOK_NONE,
                look = CaptureMath.lookTag(version, LOOK_NONE, shot.stopLabel, shot.trueEquivalentMm)
            )
        }
    }

    /**
     * B23b, store lane: encode, EXIF, MediaStore insert, DNG. Never throws; always closes the super-resolution image,
     * recycles the bitmap and calls [StillShot.release] exactly once.
     */
    fun store(staged: StagedShot, nowMs: () -> Long): ShotResult {
        try {
            return storeInner(staged, nowMs)
        } finally {
            runCatching { staged.rendered?.recycle() }
            runCatching { staged.shot.burst?.srImage?.close() }
            runCatching { staged.shot.release() }
        }
    }

    private fun renderInner(shot: StillShot, nowMs: () -> Long, startMs: Long, base: String): StagedShot {
        var colour: ColourShot? = null
        var rendered: Bitmap? = null
        var dngRaw: RawCopy? = shot.raw
        var dngResult: TotalCaptureResult = shot.result
        var burstShot: BurstShot? = shot.singlePlan?.let {
            BurstShot(plan = it, stopMm = shot.stopTargetMm, stress = shot.stress, queuedBehind = shot.queuedBehind, path = shot.singlePath)
        }
        var srMerged = false
        var srFallback: String? = null
        val b = shot.burst
        if (b != null) {
            val timings = linkedMapOf(
                "capture" to b.captureMs,
                "merge_tail" to b.mergeTailMs,
                "queue_wait" to (startMs - shot.queuedAtMs).coerceAtLeast(0)
            )
            b.releaseMs?.let { timings["shutter_release"] = it }
            val stats = b.stats
            var fallback: String? = b.fallbackReason
            stats?.let {
                timings["merge_total"] = Math.round(it.pyramidMs + it.referenceMs + it.alignMs + it.mergeMs + it.finishMs)
                timings["pyramid"] = Math.round(it.pyramidMs)
                timings["reference"] = Math.round(it.referenceMs)
                timings["align"] = Math.round(it.alignMs)
                timings["merge"] = Math.round(it.mergeMs)
                timings["finish"] = Math.round(it.finishMs)
            }
            var mergedOk = false
            val srImage = b.srImage
            val srStats = b.srStats
            val sr = shot.superRes
            if (sr != null && sr.active) {
                srFallback = b.srError
                srStats?.let {
                    timings["sr_copy"] = Math.round(it.copyMs)
                    timings["sr_align"] = Math.round(it.alignMs + it.referenceMs)
                    timings["sr_robust"] = Math.round(it.robustMs)
                    timings["sr_gather"] = Math.round(it.gatherMs)
                }
            }
            if (shot.renderRaw && pipeline != null && sr != null && srImage != null && srStats != null) {
                val o = pipeline.renderSuperRes(
                    srImage,
                    b.referenceResult,
                    shot.characteristics,
                    shot.cameraId,
                    shot.jpeg,
                    sr.outWidth,
                    sr.outHeight
                )
                srImage.close()
                rendered = o.bitmap
                colour = o.shot
                if (rendered == null) srFallback = "super-resolution render: ${o.shot.fallbackReason}" else srMerged = true
            }
            if (shot.renderRaw && pipeline != null) {
                val m = b.merged
                if (rendered == null && m != null && stats != null) {
                    val o = pipeline.render(
                        m,
                        b.referenceResult,
                        shot.characteristics,
                        shot.cameraId,
                        shot.jpeg,
                        shot.zoomRatio,
                        shot.stopLongPx,
                        b.mergedScale
                    )
                    rendered = o.bitmap
                    colour = o.shot
                    if (rendered == null) fallback = "merged render: ${o.shot.fallbackReason}" else mergedOk = true
                }
                val f = b.fallbackRaw
                if (rendered == null && f != null) {
                    val o = pipeline.render(
                        f,
                        b.fallbackResult ?: b.referenceResult,
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
            dngRaw = b.dngRaw
            dngResult = b.referenceResult
            if (srMerged) mergedOk = true
            val per = stats?.perFrame.orEmpty()
            burstShot = BurstShot(
                plan = b.plan,
                stopMm = shot.stopTargetMm,
                framesCaptured = b.framesCaptured,
                framesMerged = if (srMerged) {
                    srStats?.frames ?: 0
                } else if (mergedOk) {
                    stats?.frames ?: 0
                } else {
                    0
                },
                reference = if (srMerged) srStats?.reference else stats?.reference,
                merged = mergedOk,
                fallbackReason = if (mergedOk) null else fallback ?: "merge unavailable",
                noiseModel = b.noiseModel,
                meanDispPx = per.takeIf { it.isNotEmpty() }?.map { it.meanDispPx }?.average(),
                maxDispPx = per.maxOfOrNull { it.maxDispPx },
                meanWeight = per.takeIf { it.isNotEmpty() }?.map { it.meanWeight }?.average(),
                rejectedFraction = per.takeIf { it.isNotEmpty() }?.map { it.rejectedFraction }?.average(),
                timingsMs = timings,
                queuedBehind = shot.queuedBehind,
                stress = shot.stress,
                streaming = b.streaming
            )
        } else if (shot.renderRaw && shot.raw != null && pipeline != null) {
            val o = pipeline.render(shot.raw, shot.result, shot.characteristics, shot.cameraId, shot.jpeg, shot.zoomRatio, shot.stopLongPx)
            rendered = o.bitmap
            colour = o.shot
        } else if (shot.renderRaw) {
            colour =
                ColourShot(rendered = false, fallbackReason = if (shot.raw == null) "no RAW frame in this shot" else "pipeline unavailable")
        }
        // B23: a 50/70 mm still with super-resolution on is saved at its output size: the merge itself, or the B22 merge
        // (or one frame) upscaled, with the reason recorded and named in the EXIF note.
        val sr = shot.superRes
        var upscaledFrom: Dim? = null
        if (sr != null && sr.applies && !srMerged) {
            val bmp = rendered
            if (bmp != null && (bmp.width != sr.outWidth || bmp.height != sr.outHeight)) {
                upscaledFrom = Dim(bmp.width, bmp.height)
                val up = runCatching { Bitmap.createScaledBitmap(bmp, sr.outWidth, sr.outHeight, true) }.getOrNull()
                if (up != null && up !== bmp) {
                    bmp.recycle()
                    rendered = up
                }
            }
            if (srFallback == null) {
                srFallback = sr.reason ?: if (bmp == null) "camera JPEG (not rendered from RAW)" else "super-resolution unavailable"
            }
        }
        val lookName = if (rendered != null) ColourPipeline.LOOK_NAME else LOOK_NONE
        val detail = when {
            sr == null || !sr.applies -> null
            srMerged -> "super-resolution: ${b?.srStats?.frames ?: 0} frames"
            upscaledFrom != null -> "upscaled from $upscaledFrom ($srFallback)"
            else -> null
        }
        val look = CaptureMath.lookTag(version, lookName, shot.stopLabel, shot.trueEquivalentMm) + (detail?.let { " | $it" } ?: "")
        val doneMs = nowMs()
        shot.timeline?.mark(ShotTimeline.RENDER_DONE, doneMs)
        return StagedShot(
            shot = shot,
            base = base,
            renderStartMs = startMs,
            renderDoneMs = doneMs,
            rendered = rendered,
            colour = colour,
            burstShot = burstShot,
            dngRaw = dngRaw,
            dngResult = dngResult,
            srMerged = srMerged,
            srFallback = srFallback,
            lookName = lookName,
            look = look
        )
    }

    private fun storeInner(staged: StagedShot, nowMs: () -> Long): ShotResult {
        val shot = staged.shot
        val storeStart = nowMs()
        shot.timeline?.mark(ShotTimeline.STORE_START, storeStart)
        val saveWaitMs = (staged.renderStartMs - shot.queuedAtMs).coerceAtLeast(0) + (storeStart - staged.renderDoneMs).coerceAtLeast(0)
        val base = staged.base
        val look = staged.look
        val lookName = staged.lookName
        var colour = staged.colour
        var burstShot = staged.burstShot
        val srMerged = staged.srMerged
        val srFallback = staged.srFallback
        val sr = shot.superRes
        val b = shot.burst
        var jpegSize: Dim? = null
        var error: String? = null
        val encodeStart = nowMs()
        val jpegOk = try {
            val bmp = staged.rendered
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
            staged.rendered?.recycle()
            staged.rendered = null
        }
        val encodeMs = nowMs() - encodeStart
        var dngOk = false
        val dngSource = staged.dngRaw
        val wantDng = dngSource != null && shot.saveDng
        val dngStart = nowMs()
        if (dngSource != null && wantDng) {
            try {
                saveDng(shot, dngSource, staged.dngResult, base, look)
                dngOk = true
            } catch (e: Throwable) {
                Log.w(TAG, "DNG save failed", e)
                if (error == null) error = "DNG: ${e.javaClass.simpleName}: ${e.message}"
            }
        }
        val dngMs = nowMs() - dngStart
        val savedAt = nowMs()
        val total = savedAt - shot.shutterAtMs
        shot.timeline?.mark(ShotTimeline.SAVED, savedAt)
        burstShot = burstShot?.let {
            if (it.plan.isBurst) {
                it.copy(
                    timingsMs = it.timingsMs + linkedMapOf("encode_save" to encodeMs, "dng" to dngMs, "total" to total),
                    timeline = shot.timeline?.toMap(),
                    saveWaitMs = saveWaitMs
                )
            } else {
                it.copy(timeline = shot.timeline?.toMap(), saveWaitMs = saveWaitMs)
            }
        }
        val srShot = if (sr != null && sr.applies) {
            val st = b?.srStats
            val per = st?.perFrame.orEmpty()
            SuperResShot(
                stopMm = shot.stopTargetMm,
                decision = sr,
                merged = srMerged && jpegOk,
                fallbackReason = if (srMerged) null else srFallback,
                framesMerged = if (srMerged) st?.frames ?: 0 else 0,
                reference = st?.reference,
                outWidth = jpegSize?.width ?: 0,
                outHeight = jpegSize?.height ?: 0,
                coverage = st?.coverage,
                sigmaG = st?.sigmaG,
                meanSubpixPx = per.takeIf { it.isNotEmpty() }?.map { it.meanSubpixPx }?.average(),
                meanRobustness = per.takeIf { it.isNotEmpty() }?.map { it.meanRobustness }?.average(),
                rejectedFraction = per.takeIf { it.isNotEmpty() }?.map { it.rejectedFraction }?.average(),
                modelResolved = per.takeIf { it.isNotEmpty() }?.map { it.modelResolved }?.average(),
                maxMotionPx = per.filter { it.model }.maxOfOrNull { maxOf(abs(it.motionYPx), abs(it.motionXPx)) },
                timingsMs = burstShot?.timingsMs.orEmpty().filterKeys { it.startsWith("sr_") || it in SR_TIMINGS } +
                    linkedMapOf("encode_save" to encodeMs, "total" to total),
                peakNativeMb = st?.peakNativeMb,
                outputMb = st?.outputMb,
                queuedBehind = shot.queuedBehind,
                stress = shot.stress
            )
        } else {
            null
        }
        return ShotResult(
            superRes = srShot,
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

        /** B23: the burst timings copied into the super-resolution record. */
        private val SR_TIMINGS = setOf("capture", "merge_tail", "shutter_release", "render", "queue_wait")

        /** The still came from the camera JPEG (pipeline off or fell back); the tag says so. */
        const val LOOK_NONE = "none (camera JPEG)"

        /** B22: the merged mosaic is written x16 (4 extra bits of the averaged precision; 1023 * 16 fits 16 bits). */
        const val MERGE_SCALE = 16f

        /** [MERGE_SCALE], or less for a sensor whose white level would overflow 16 bits. */
        fun mergeScale(white: Float): Float = minOf(MERGE_SCALE, kotlin.math.floor(65535f / white)).coerceAtLeast(1f)

        /**
         * Camera2 SENSOR_NOISE_PROFILE (S, O per CFA channel, normalised signal) averaged over channels; without it a
         * rough ISO-scaled default (marked in the report).
         */
        fun noiseModel(r: TotalCaptureResult): Triple<Double, Double, String> {
            val p = runCatching { r.get(CaptureResult.SENSOR_NOISE_PROFILE) }.getOrNull()
            if (p != null && p.isNotEmpty()) {
                val s = p.map { it.first }.average()
                val o = p.map { it.second }.average()
                if (s.isFinite() && o.isFinite() && s >= 0 && o >= 0) return Triple(s, o, "camera")
            }
            val iso = (r.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100).toDouble()
            return Triple(DEFAULT_NOISE_S_PER_ISO * iso, DEFAULT_NOISE_O_PER_ISO2 * iso * iso, "iso_default")
        }

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
