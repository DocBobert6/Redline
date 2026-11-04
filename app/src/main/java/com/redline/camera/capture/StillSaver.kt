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
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import com.redline.camera.capability.Dim
import com.redline.camera.colour.ColourPipeline
import com.redline.camera.colour.ColourShot
import com.redline.camera.depth.DepthReport
import com.redline.camera.depth.DepthService
import com.redline.camera.nativebridge.RedlineNative
import com.redline.camera.viewfinder.NormRect
import java.io.ByteArrayInputStream
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
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
    val release: () -> Unit = {},
    /**
     * B24.1: returns the shot's pool buffers early, all but [keep] (the DNG's source frame, when one is still to be
     * written). Called by the saver once the RAW render is done, before the upscale, the detail post-pass and the store,
     * so a busy queue gets its buffers back sooner. [release] still returns whatever is left, exactly once.
     */
    val releaseRaw: (keep: ByteBuffer?) -> Unit = {},
    /** B24b: the tapped region (frame-normalised, sensor orientation) the render meters with, or null (no tap). */
    val meterRegion: NormRect? = null,
    /** B25: the formats this shot is saved in (effective settings); null = B24 behaviour (JPEG, DNG per [saveDng]). */
    val formats: SaveSettings.Effective? = null,
    /** B25: the DNG's samples are the burst merge's, x this factor (1 = a camera frame). */
    val dngScale: Float = 1f,
    /** B25: what the DNG is ("single frame", "burst merge", "frame 0 (super-resolution)", ...). */
    val dngSourceLabel: String? = null
) {
    /** B25: a photo (JPEG or HEIF) is written; false for "DNG only". */
    val savePhoto: Boolean get() = formats?.photo ?: true
    val photoFormat: PhotoFormat get() = formats?.photoFormat ?: PhotoFormat.JPEG
    val ultraHdr: Boolean get() = formats?.ultraHdr ?: false
}

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
    val srError: String? = null,
    /** The capture result of [dngRaw]'s own frame (its metadata must match its pixels); null = [referenceResult]. */
    val dngResult: TotalCaptureResult? = null
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
    val look: String,
    /** B25: the render's Ultra HDR gain map (warped with the still when RAW feel distorted it), or null. */
    val hdr: ColourPipeline.HdrMap? = null
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

    /**
     * B30: depth sidecars. When [depthEnabled] and [depth] is set, a kept shot rendered from RAW gets depth prep from
     * its rendered still right after the photo is saved (before the bitmap is recycled) and the models run on the
     * depth lane, off the save path. Off until the caller sets both (the app setting "depth_enabled" defaults on).
     */
    @Volatile var depthEnabled: Boolean = false

    @Volatile var depth: DepthService? = null

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
                dngResult = shot.burst?.let { it.dngResult ?: it.referenceResult } ?: shot.result,
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
        // B25: "DNG only" renders no photo (the merge still runs for a merge DNG and its report).
        val renderRaw = shot.renderRaw && shot.savePhoto
        val uhdr = shot.ultraHdr
        var hdr: ColourPipeline.HdrMap? = null
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
            if (renderRaw && pipeline != null && sr != null && srImage != null && srStats != null) {
                val o = pipeline.renderSuperRes(
                    srImage,
                    b.referenceResult,
                    shot.characteristics,
                    shot.cameraId,
                    shot.jpeg,
                    sr.outWidth,
                    sr.outHeight,
                    shot.stopTargetMm,
                    shot.meterRegion,
                    uhdr
                )
                srImage.close()
                rendered = o.bitmap
                colour = o.shot
                hdr = o.hdr
                if (rendered == null) srFallback = "super-resolution render: ${o.shot.fallbackReason}" else srMerged = true
            }
            if (renderRaw && pipeline != null) {
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
                        b.mergedScale,
                        shot.stopTargetMm,
                        shot.meterRegion,
                        uhdr
                    )
                    rendered = o.bitmap
                    colour = o.shot
                    hdr = o.hdr
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
                        shot.stopLongPx,
                        stopMm = shot.stopTargetMm,
                        spot = shot.meterRegion,
                        ultraHdr = uhdr
                    )
                    rendered = o.bitmap
                    colour = o.shot
                    hdr = o.hdr
                }
                colour?.timingsMs?.get("total")?.let { timings["render"] = it }
            } else if (!shot.savePhoto) {
                // B25 "DNG only": nothing rendered; the merge (when it ran) is what the DNG may be written from.
                runCatching { srImage?.close() }
                mergedOk = stats != null || (srImage != null && srStats != null)
                fallback = null
            } else {
                fallback = "colour pipeline unavailable"
            }
            dngRaw = b.dngRaw
            dngResult = b.dngResult ?: b.referenceResult
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
        } else if (renderRaw && shot.raw != null && pipeline != null) {
            val o = pipeline.render(
                shot.raw,
                shot.result,
                shot.characteristics,
                shot.cameraId,
                shot.jpeg,
                shot.zoomRatio,
                shot.stopLongPx,
                stopMm = shot.stopTargetMm,
                spot = shot.meterRegion,
                ultraHdr = uhdr
            )
            rendered = o.bitmap
            colour = o.shot
            hdr = o.hdr
        } else if (renderRaw) {
            colour =
                ColourShot(rendered = false, fallbackReason = if (shot.raw == null) "no RAW frame in this shot" else "pipeline unavailable")
        }
        // B24.1: the RAW frames are no longer needed past this point (only the DNG's own frame, written by the store):
        // their pool buffers go back now, not after the upscale, detail post-pass, encode and MediaStore insert.
        val dngKeep = if (shot.saveDng) dngRaw?.buffer else null
        runCatching { shot.releaseRaw(dngKeep) }
        shot.timeline?.mark(ShotTimeline.RAW_RELEASED, nowMs())
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
        // B24: the detail post-pass on the finished still (after the upscale): distortion if toggled, sharpening, grain.
        val still = rendered
        if (still != null && pipeline != null) {
            val seed = (runCatching { shot.result.get(CaptureResult.SENSOR_TIMESTAMP) }.getOrNull() ?: shot.shutterAtMs).toInt()
            val d = pipeline.applyDetail(still, colour?.iso, shot.stopTargetMm, seed)
            if (d != null) {
                val ms = d.timingsMs["total"] ?: 0L
                colour = colour?.let { c -> c.copy(detail = d, timingsMs = c.timingsMs + ("detail" to ms)) }
                burstShot = burstShot?.let { it.copy(timingsMs = it.timingsMs + ("detail" to ms)) }
            }
            // B25: the gain map is distorted with the still (RAW feel), so it lifts the same highlights.
            hdr = hdr?.let { pipeline.warpHdr(it, still.width, still.height, shot.stopTargetMm, d?.distortion == true && d.error == null) }
        }
        if (rendered == null) hdr = null
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
            look = look,
            hdr = hdr
        )
    }

    /** B30: hands a saved, kept, RAW-rendered still to the depth lane (prep runs here, the models there). Never throws. */
    private fun startDepth(shot: StillShot, base: String, still: Bitmap?, saved: Boolean) {
        val svc = depth ?: return
        if (!depthEnabled) return
        runCatching {
            when {
                !saved -> svc.skip(DepthReport.SKIP_NO_PHOTO)
                still == null || still.isRecycled -> svc.skip(DepthReport.SKIP_NOT_RENDERED)
                !shot.keep -> svc.skip(DepthReport.SKIP_NOT_KEPT)
                else -> svc.submit(still, shot.jpegOrientation, base)
            }
        }.onFailure { Log.w(TAG, "depth hand-off failed", it) }
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
        val savedCameraJpeg = staged.rendered == null
        var photo: PhotoOut? = null
        val jpegOk = if (!shot.savePhoto) {
            staged.rendered?.recycle()
            staged.rendered = null
            false
        } else {
            try {
                val bmp = staged.rendered
                if (bmp != null) {
                    photo = saveRendered(shot, bmp, base, look, staged.hdr)
                    jpegSize = photo.size
                    colour = colour?.let { it.copy(timingsMs = it.timingsMs + ("encode_save" to (nowMs() - encodeStart))) }
                } else {
                    photo = saveJpeg(shot, base, look)
                    jpegSize = photo.size
                }
                true
            } catch (e: Throwable) {
                Log.w(TAG, "photo save failed", e)
                error = "${shot.photoFormat.label}: ${e.javaClass.simpleName}: ${e.message}"
                false
            } finally {
                startDepth(shot, staged.base, staged.rendered, photo != null)
                staged.rendered?.recycle()
                staged.rendered = null
            }
        }
        // B24.1: the camera JPEG is never a pressure fallback; when it was saved, say why (a hard reason) in the report.
        if (shot.savePhoto && savedCameraJpeg && jpegSize != null && burstShot != null) {
            val hard = hardJpegReason(shot, colour, burstShot)
            if (hard != null) burstShot = burstShot.copy(path = BurstShot.PATH_JPEG, hardJpegReason = hard)
        }
        val encodeMs = nowMs() - encodeStart
        var dngOk = false
        var dng: DngOut? = null
        val dngSource = staged.dngRaw
        val wantDng = dngSource != null && shot.saveDng
        val dngStart = nowMs()
        if (dngSource != null && wantDng) {
            try {
                dng = saveDng(shot, dngSource, staged.dngResult, base, look)
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
        val ph = photo
        val dg = dng
        val formats = shot.formats?.let { f ->
            val hdr = staged.hdr
            FormatsShot(
                settings = f,
                photoBytes = ph?.bytes,
                dngBytes = dg?.bytes,
                photoSize = jpegSize,
                ultraHdrWanted = f.ultraHdr && f.photo,
                ultraHdrWritten = ph?.ultraHdr == true,
                headroomStops = hdr?.headroomStops,
                p999Stops = hdr?.p999Stops,
                kneeSlope = hdr?.slope,
                aboveKneeFraction = hdr?.aboveKneeFraction,
                ultraHdrSkip = when {
                    !f.ultraHdr || !f.photo || ph?.ultraHdr == true -> null
                    else -> ph?.ultraHdrSkip ?: hdr?.skip ?: if (savedCameraJpeg) "camera JPEG (not rendered from RAW)" else "no gain map"
                },
                gainmapMs = if (f.ultraHdr && f.photo) (hdr?.ms ?: 0L) + (ph?.gainmapMs ?: 0L) else null,
                gainmapVerified = ph?.verified,
                photoEncodeMs = if (shot.savePhoto) encodeMs else null,
                dngSource = if (wantDng) shot.dngSourceLabel ?: "single frame" else null,
                dngLevels = dg?.levels,
                dngMs = if (wantDng) dngMs else null,
                heifError = ph?.heifError,
                heif = ph?.heif
            )
        }
        return ShotResult(
            superRes = srShot,
            ok = if (shot.savePhoto) jpegOk else dngOk,
            formats = formats,
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
     * B24.1: why a shot was saved as the camera JPEG, when that was a hard error: the RAW render failed (the pipeline's
     * reason), or the controller took the press without a RAW buffer ([ShotRouter.HARD_PREFIX] in the plan). Null when
     * the shot was never meant to be rendered from RAW (pipeline off, no RAW stream).
     */
    private fun hardJpegReason(shot: StillShot, colour: ColourShot?, b: BurstShot): String? {
        if (shot.renderRaw) return "render error: ${colour?.fallbackReason ?: b.fallbackReason ?: "no bitmap"}"
        val plan = b.plan.singleReason ?: return null
        return plan.split("; ").firstOrNull { it.startsWith(ShotRouter.HARD_PREFIX) }?.removePrefix(ShotRouter.HARD_PREFIX)
    }

    /** B25: what the photo write did. */
    private class PhotoOut(
        val size: Dim,
        val bytes: Long,
        val ultraHdr: Boolean = false,
        val ultraHdrSkip: String? = null,
        /** Gain map bitmap + metadata, ms (the encode itself is in the photo's encode time). */
        val gainmapMs: Long? = null,
        /** After the EXIF write, the MPF entry still points at the gain map image. */
        val verified: Boolean? = null,
        val heifError: String? = null,
        /** B30: codec, input mode, grid and time split of a HEIF write. */
        val heif: Map<String, Any?>? = null
    )

    /** B25: what the DNG write did. */
    private class DngOut(val bytes: Long, val levels: String?)

    /**
     * B21: the pipeline's bitmap (sensor orientation) as the photo; EXIF orientation comes from the shot. B25: HEIF when
     * chosen (a HEIF failure falls back to JPEG, so the photo is never lost), and an Ultra HDR JPEG when the render made a
     * gain map: the platform encoder (Bitmap.setGainmap, API 34+) writes the same SDR base JPEG plus the map (MPF + XMP).
     */
    private fun saveRendered(shot: StillShot, bmp: Bitmap, base: String, look: String, hdr: ColourPipeline.HdrMap?): PhotoOut {
        val original = ExifInterface(ByteArrayInputStream(shot.jpeg))
        val size = Dim(bmp.width, bmp.height)
        var heifError: String? = null
        if (shot.photoFormat == PhotoFormat.HEIF) {
            try {
                return saveHeif(shot, bmp, base, look, original, size)
            } catch (e: Throwable) {
                Log.w(TAG, "HEIF save failed, writing JPEG", e)
                heifError = "${e.javaClass.simpleName}: ${e.message}"
            }
        }
        val tmp = File.createTempFile(base, ".jpg", app.cacheDir)
        try {
            val r = PhotoJpeg.write(bmp, if (shot.ultraHdr) hdr?.bytes else null, tmp, JPEG_QUALITY) { f ->
                writeExif(f, original, shot, size, look, CaptureMath.exifOrientation(shot.jpegOrientation))
            }
            insert("$base.jpg", "image/jpeg", shot.keep) { os -> tmp.inputStream().use { it.copyTo(os) } }
            return PhotoOut(
                size,
                tmp.length(),
                ultraHdr = r.ultraHdr,
                ultraHdrSkip = r.skip ?: if (shot.ultraHdr && hdr?.bytes == null) hdr?.skip else null,
                gainmapMs = r.gainmapMs,
                verified = r.verified,
                heifError = heifError
            )
        } finally {
            tmp.delete()
        }
    }

    /**
     * B25: HEIF at [HEIF_QUALITY]; B30: written by [PhotoHeif] (hardware HEIC/HEVC encoder, YUV buffer input, 512 grid). The rotation goes in the HEIF itself
     * (irot); the EXIF block (built on a tiny JPEG with the same tags, orientation normal) is added as the item's Exif.
     */
    private fun saveHeif(shot: StillShot, bmp: Bitmap, base: String, look: String, original: ExifInterface, size: Dim): PhotoOut {
        val te = SystemClock.elapsedRealtime()
        val exif = exifBlock(base, original, shot, size, look)
        val exifMs = SystemClock.elapsedRealtime() - te
        val tmp = File.createTempFile(base, ".heic", app.cacheDir)
        try {
            val t0 = SystemClock.elapsedRealtime()
            val r = PhotoHeif.write(bmp, shot.jpegOrientation, HEIF_QUALITY, exif, tmp)
            val t1 = SystemClock.elapsedRealtime()
            insert("$base.${PhotoFormat.HEIF.ext}", PhotoFormat.HEIF.mime, shot.keep) { os -> tmp.inputStream().use { it.copyTo(os) } }
            val t2 = SystemClock.elapsedRealtime()
            val m = LinkedHashMap(r.toMap())
            m["heif_save_ms"] = linkedMapOf("exif_block" to exifMs, "write" to t1 - t0, "media_store" to t2 - t1)
            return PhotoOut(size, tmp.length(), heif = m)
        } finally {
            tmp.delete()
        }
    }

    /** B25: the Exif payload ("Exif\0\0" + TIFF) for a HEIF, written by ExifInterface on a tiny JPEG. */
    private fun exifBlock(base: String, original: ExifInterface, shot: StillShot, size: Dim, look: String): ByteArray? {
        val tiny = File.createTempFile("$base-exif", ".jpg", app.cacheDir)
        return try {
            val b = Bitmap.createBitmap(EXIF_STUB_PX, EXIF_STUB_PX, Bitmap.Config.ARGB_8888)
            tiny.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 50, it) }
            b.recycle()
            writeExif(tiny, original, shot, size, look, ExifInterface.ORIENTATION_NORMAL)
            JpegStructure.exifPayload(tiny.readBytes())
        } catch (e: Throwable) {
            Log.w(TAG, "HEIF EXIF block failed", e)
            null
        } finally {
            tiny.delete()
        }
    }

    private fun saveJpeg(shot: StillShot, base: String, look: String): PhotoOut {
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
            return PhotoOut(out, tmp.length())
        } finally {
            tmp.delete()
        }
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

    /**
     * The RAW frame as a DNG (full sensor, uncropped, no crop tag), from its pool copy and its own result. B25: a burst
     * merge DNG ([StillShot.dngScale] > 1) is written at the merge's precision with WhiteLevel/BlackLevel scaled to match
     * ([DngLevels]); if the levels cannot be patched, the samples are divided back to the sensor scale and written again.
     */
    private fun saveDng(shot: StillShot, raw: RawCopy, result: TotalCaptureResult, base: String, look: String): DngOut {
        val scale = Math.round(shot.dngScale)
        if (scale <= 1) {
            var count = 0L
            writeDng(shot, raw, result, look) { write ->
                insert("$base.dng", "image/x-adobe-dng", shot.keep) { os -> CountingStream(os).also { write(it) }.let { count = it.count } }
            }
            return DngOut(count, null)
        }
        val tmp = File.createTempFile(base, ".dng", app.cacheDir)
        try {
            writeDng(shot, raw, result, look) { write -> tmp.outputStream().buffered().use { write(it) } }
            var levels = "patched x$scale"
            if (!DngLevels.patch(tmp, scale)) {
                divideSamples(raw, scale)
                writeDng(shot, raw, result, look) { write -> tmp.outputStream().buffered().use { write(it) } }
                levels = "divided by $scale (levels not patchable)"
            }
            insert("$base.dng", "image/x-adobe-dng", shot.keep) { os -> tmp.inputStream().use { it.copyTo(os) } }
            return DngOut(tmp.length(), levels)
        } finally {
            tmp.delete()
        }
    }

    private fun writeDng(shot: StillShot, raw: RawCopy, result: TotalCaptureResult, look: String, sink: ((OutputStream) -> Unit) -> Unit) {
        DngCreator(shot.characteristics, result).use { dng ->
            dng.setOrientation(CaptureMath.exifOrientation(shot.jpegOrientation))
            dng.setDescription(look)
            sink { os -> dng.writeByteBuffer(os, Size(raw.width, raw.height), raw.buffer.duplicate().also { it.clear() }, 0L) }
        }
    }

    /** B25: merge samples back to the sensor scale, in place (the render is done with them by the time the store runs). */
    private fun divideSamples(raw: RawCopy, scale: Int) {
        val sb = raw.buffer.duplicate().also { it.clear() }.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
        val half = scale / 2
        for (i in 0 until minOf(sb.capacity(), raw.width * raw.height)) {
            val v = sb.get(i).toInt() and 0xFFFF
            sb.put(i, ((v + half) / scale).toShort())
        }
    }

    private class CountingStream(private val out: OutputStream) : OutputStream() {
        var count = 0L
        override fun write(b: Int) {
            out.write(b)
            count++
        }
        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }
        override fun flush() = out.flush()
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

        /** B25: HEIF quality (HeifWriter 0..100) and how long the encoder may take to finish. */
        const val HEIF_QUALITY = 92
        private const val EXIF_STUB_PX = 16

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
