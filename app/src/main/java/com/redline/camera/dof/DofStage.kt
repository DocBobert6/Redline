package com.redline.camera.dof

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.os.Environment
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import com.redline.camera.capability.Json
import com.redline.camera.capability.JsonParse
import com.redline.camera.capture.CaptureMath
import com.redline.camera.capture.SaveSettings
import com.redline.camera.colour.ColourPipeline
import com.redline.camera.depth.DepthPng
import com.redline.camera.depth.DepthService
import com.redline.camera.nativebridge.RedlineNative
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * B31: simulated depth of field on the save path, and re-edit after capture.
 *
 * [apply] runs on the store lane on a RAW-rendered still before it is encoded: depth for this shot synchronously on the
 * [DepthService] engine (never compiled or queued for: not ready or busy = the still is saved sharp, the reason
 * recorded), a copy of the sharp render handed to the io thread (files/dof/<base>.sharp.jpg, q95, with the lens and tone
 * in <base>.dof.json, pruned with the depth sidecars), then [RedlineNative.bokehRender] in place. The Ultra HDR gain map
 * is recomputed by the bokeh from the blurred linear result (the same HDR continuation that made the highlights bloom),
 * so the HDR rendition matches the blurred base; it is re-encoded with the B25 headroom rules.
 *
 * Every failure degrades to a sharp still from our own render (never the camera JPEG). Rapid fire (shots queued behind
 * this one) and memory pressure skip DOF for the shot rather than delay it.
 */
class DofStage(context: Context, val depth: DepthService) {
    private val app = context.applicationContext

    @Volatile var enabled: Boolean = DofReport.DEFAULT_ON
        private set

    @Volatile var aperture: Float = DofMath.DEFAULT_APERTURE
        private set

    private val lock = Any()
    private var state = DofReport()
    private val working = AtomicInteger(0)

    val report: DofReport get() = synchronized(lock) { state }

    @Volatile var onChange: ((DofReport) -> Unit)? = null

    /** How long the save path waits for this shot's depth (the engine's GPU run is ~160 ms on the baseline phone). */
    @Volatile var syncTimeoutMs: Long = DepthService.SYNC_TIMEOUT_MS

    fun setEnabled(on: Boolean) {
        enabled = on
        update { it.copy(enabled = on) }
        if (on) depth.warmUp()
    }

    fun setAperture(n: Float) {
        aperture = DofMath.snap(n)
        update { it.copy(aperture = aperture) }
    }

    /** True while depth or bokeh work for a shot is in progress (the preview-fps window of the report). */
    val busy: Boolean get() = working.get() > 0

    /** A preview fps reading: kept (as the window minimum) only while DOF work runs. */
    fun previewFps(v: Double) {
        if (busy) update { it.fps(v) }
    }

    /** What the save path knows about the shot. */
    class Request(
        val still: Bitmap,
        /** Clockwise degrees to upright (the JPEG orientation). */
        val rotation: Int,
        val base: String,
        val keep: Boolean,
        val focalMm: Double,
        val phoneEquivalentAperture: Double?,
        val focus: DofMath.FocusDistance,
        /** The touch-focus point in the still (0..1), or null. */
        val tap: Pair<Double, Double>?,
        val tone: ColourPipeline.RenderTone?,
        val hdr: ColourPipeline.HdrMap?,
        val threads: Int,
        val nice: Int,
        /** Shots queued behind this one (presses waiting, capturing or saving). */
        val queuedBehind: Int,
        val lowMemory: Boolean,
        val aperture: Float? = null
    )

    /** [shot] null when DOF is off; [hdr] the (possibly recomputed) gain map; [depthHandled] the sidecar is on its way. */
    class Outcome(val shot: DofShot?, val hdr: ColourPipeline.HdrMap?, val depthHandled: Boolean, internal val windowOpen: Boolean)

    /** In place on [Request.still]. Never throws. Call [finish] once the photo is saved. */
    fun apply(r: Request): Outcome {
        if (!enabled) return Outcome(null, r.hdr, depthHandled = false, windowOpen = false)
        val n = r.aperture ?: aperture
        val t0 = SystemClock.elapsedRealtime()
        fun skip(reason: String, handled: Boolean = false, open: Boolean = false, extra: (DofShot) -> DofShot = { it }) = Outcome(
            extra(
                DofShot(
                    base = r.base,
                    applied = false,
                    reason = reason,
                    aperture = n,
                    focalMm = r.focalMm,
                    focusDistanceM = r.focus.metres,
                    focusSource = r.focus.source,
                    phoneEquivalentAperture = r.phoneEquivalentAperture,
                    stageMs = SystemClock.elapsedRealtime() - t0
                )
            ),
            r.hdr,
            handled,
            open
        )
        when {
            !r.keep -> return skip(DofReport.SKIP_NOT_KEPT)
            r.queuedBehind > 0 -> return skip("${DofReport.SKIP_RAPID_FIRE} (${r.queuedBehind} queued)")
            r.lowMemory -> return skip(DofReport.SKIP_MEMORY)
            r.tone == null -> return skip(DofReport.SKIP_NO_TONE)
            r.phoneEquivalentAperture == null -> return skip(DofReport.SKIP_NO_LENS)
        }
        val tone = r.tone!!
        val phoneN = r.phoneEquivalentAperture!!
        working.incrementAndGet()
        try {
            val sync = depth.runSync(r.still, r.rotation, r.base, r.nice, syncTimeoutMs)
            val maps = sync.maps ?: return skip(sync.skip ?: DofReport.SKIP_DEPTH_FAILED, open = true)
            val withDepth: (DofShot) -> DofShot = {
                it.copy(confidence = maps.confidence.toDouble(), depthMs = maps.depthMs, delegate = maps.delegate)
            }
            if (!maps.usable) {
                return skip("low confidence (%.2f < 0.35)".format(maps.confidence), handled = true, open = true, extra = withDepth)
            }
            val (point, fx, fy) = focusPoint(r.tap, maps)
            // The sharp render for re-edit (a copy; the bokeh works in place on the still).
            val copy = try {
                r.still.copy(Bitmap.Config.ARGB_8888, false)
            } catch (e: OutOfMemoryError) {
                null
            }
            copy?.let { depth.writeSharp(it, r.base, metaJson(r, n, phoneN, tone, fx, fy)) }
            val lens = RedlineNative.BokehLens(
                focalMm = r.focalMm.toFloat(),
                aperture = n,
                focusDistanceM = r.focus.metres.toFloat(),
                focusX = fx.toFloat(),
                focusY = fy.toFloat(),
                phoneEquivalentAperture = phoneN.toFloat()
            )
            val gain = gainIn(r.hdr)
            val gainOut = gain?.let { FloatArray(it.values.size) }
            val bt = RedlineNative.BokehTone(
                tone.table,
                tone.lo,
                tone.hi,
                tone.kneeEv,
                slope = if (gain != null) gain.slope else 0f,
                gainLog2 = gain?.values,
                gainW = gain?.w ?: 0,
                gainH = gain?.h ?: 0,
                gainOut = gainOut
            )
            val bd = RedlineNative.BokehDepth(maps.depth, maps.mask, maps.confidenceMap, maps.width, maps.height, maps.confidence)
            val b0 = SystemClock.elapsedRealtime()
            val res = withNice(r.nice) { RedlineNative.bokehRender(r.still, lens, bt, bd, threads = r.threads, workMax = WORK_MAX) }
            val bokehMs = SystemClock.elapsedRealtime() - b0
            var hdr = r.hdr
            var gainNote: String? = if (r.hdr?.bytes == null) "no gain map" else null
            if (res.applied && gain != null && gainOut != null) {
                val g0 = SystemClock.elapsedRealtime()
                hdr = reencode(r.hdr!!, gain, gainOut, tone.kneeEv)
                gainNote = if (hdr.bytes != null) {
                    "recomputed from the blurred linear result (%d ms)".format(SystemClock.elapsedRealtime() - g0)
                } else {
                    "dropped after the blur: ${hdr.skip}"
                }
            }
            val shot = DofShot(
                base = r.base,
                applied = res.applied,
                reason = res.reason,
                aperture = n,
                focalMm = r.focalMm,
                focusDistanceM = r.focus.metres,
                focusSource = r.focus.source,
                focusPoint = point,
                focusX = fx,
                focusY = fy,
                phoneEquivalentAperture = phoneN,
                confidence = maps.confidence.toDouble(),
                depthMs = maps.depthMs,
                bokehMs = bokehMs,
                stageMs = SystemClock.elapsedRealtime() - t0,
                maxRadiusPx = if (res.applied) res.maxRadiusPx.toDouble() else null,
                threads = r.threads,
                nice = r.nice,
                sharpCopy = copy != null,
                gainMap = gainNote,
                delegate = maps.delegate
            )
            return Outcome(shot, hdr, depthHandled = true, windowOpen = true)
        } catch (t: Throwable) {
            Log.w(TAG, "DOF failed; saving sharp", t)
            return skip("${DofReport.SKIP_ERROR}: ${t.javaClass.simpleName}: ${t.message}", handled = true, open = true)
        }
    }

    /** Records the shot with its shutter-to-saved time and closes its preview-fps window. Never throws. */
    fun finish(o: Outcome, shutterToSavedMs: Long?) {
        if (o.windowOpen) working.decrementAndGet()
        val s = o.shot ?: return
        update { it.record(s.copy(shutterToSavedMs = shutterToSavedMs)) }
    }

    private fun focusPoint(tap: Pair<Double, Double>?, maps: DepthService.SyncDepth): Triple<String, Double, Double> {
        if (tap != null) return Triple(DofMath.POINT_TAP, tap.first, tap.second)
        val c = DofMath.maskCentroid(maps.mask, maps.width, maps.height)
        if (c != null) return Triple(DofMath.POINT_PERSON, c.first, c.second)
        return Triple(DofMath.POINT_CENTRE, 0.5, 0.5)
    }

    private class GainIn(val values: FloatArray, val w: Int, val h: Int, val slope: Float)

    /** The written gain map as mean log2 gain per cell (byte / 255 x headroom), with the render's knee slope. */
    private fun gainIn(hdr: ColourPipeline.HdrMap?): GainIn? {
        val b = hdr?.bytes ?: return null
        val slope = hdr.slope?.toFloat() ?: return null
        if (slope <= 0f || !b.written) return null
        val head = b.headroomStops.toFloat()
        val v = FloatArray(b.width * b.height)
        for (y in 0 until b.height) {
            for (x in 0 until b.width) v[y * b.width + x] = (b.bytes[y * b.stride + x].toInt() and 0xFF) / 255f * head
        }
        return GainIn(v, b.width, b.height, slope)
    }

    private fun reencode(hdr: ColourPipeline.HdrMap, gain: GainIn, out: FloatArray, kneeEv: Float): ColourPipeline.HdrMap {
        val t0 = SystemClock.elapsedRealtime()
        return try {
            val cells = RedlineNative.GainMapCells(
                gain.w * RedlineNative.GainMapCells.CELL,
                gain.h * RedlineNative.GainMapCells.CELL,
                kneeEv
            )
            val per = (RedlineNative.GainMapCells.CELL * RedlineNative.GainMapCells.CELL).toFloat()
            for (i in out.indices) cells.data[i] = out[i] * per
            cells.data[cells.data.size - 1] = gain.slope
            val b = RedlineNative.finishGainMap(cells, SaveSettings.HEADROOM_CAP_STOPS, SaveSettings.HEADROOM_MIN_STOPS)
            val skip = when (b.skipCode) {
                0 -> null
                1 -> "no highlights above the knee"
                else -> "headroom %.2f stops < %.2f".format(b.headroomStops, SaveSettings.HEADROOM_MIN_STOPS)
            }
            ColourPipeline.HdrMap(
                if (b.written) b else null,
                hdr.slope,
                skip,
                hdr.ms + SystemClock.elapsedRealtime() - t0,
                b.headroomStops,
                b.p999Stops,
                b.aboveKneeFraction
            )
        } catch (e: Exception) {
            ColourPipeline.HdrMap(null, hdr.slope, "gain map after bokeh: ${e.javaClass.simpleName}: ${e.message}", hdr.ms)
        }
    }

    private fun metaJson(r: Request, n: Float, phoneN: Double, tone: ColourPipeline.RenderTone, fx: Double, fy: Double): String =
        Json.value(
            linkedMapOf(
                "version" to 1,
                "rotation" to r.rotation,
                "focal_mm" to r.focalMm,
                "aperture" to n.toDouble(),
                "focus_distance_m" to r.focus.metres,
                "focus_distance_source" to r.focus.source,
                "focus_xy" to listOf(fx, fy),
                "phone_equivalent_aperture" to phoneN,
                "tone" to linkedMapOf(
                    "lo" to tone.lo.toDouble(),
                    "hi" to tone.hi.toDouble(),
                    "knee_ev" to tone.kneeEv.toDouble(),
                    "table" to tone.table.map { it.toDouble() }
                )
            )
        )

    // ------------------------------------------------------------------------------------------------- re-edit

    /** A re-edit candidate: the stored lens and the sharp copy's size. */
    class Stored(val base: String, val aperture: Float, val focusX: Double, val focusY: Double, val focalMm: Double)

    /** Recent shots with a sharp copy, a depth sidecar and the lens metadata, newest first. */
    fun stored(): List<Stored> = depth.reeditable().mapNotNull { b ->
        runCatching {
            val m = JsonParse.parse(depth.metaFile(b).readText()) as Map<*, *>
            val xy = m["focus_xy"] as List<*>
            Stored(b, (m["aperture"] as Number).toFloat(), (xy[0] as Number).toDouble(), (xy[1] as Number).toDouble(), num(m["focal_mm"]))
        }.getOrNull()
    }

    /**
     * Re-renders a stored shot from its sharp copy and depth at a new [aperture] and focus point ([fx], [fy], 0..1 of the
     * still), and saves it as a new photo in DCIM/Redline (the original is kept). The focus distance stays the shot's AF
     * distance: the tapped point becomes the plane at that distance (the depth is relative). No gain map (plain JPEG).
     */
    fun reedit(base: String, aperture: Float, fx: Double, fy: Double, threads: Int = 4): DofReedit {
        val t0 = SystemClock.elapsedRealtime()
        val n = DofMath.snap(aperture)
        fun fail(why: String) =
            DofReedit(base, ok = false, aperture = n, focusX = fx, focusY = fy, reason = why).also { r -> update { it.reedit(r) } }
        return try {
            val m = JsonParse.parse(depth.metaFile(base).readText()) as Map<*, *>
            val tm = m["tone"] as Map<*, *>
            val tone = RedlineNative.BokehTone(
                (tm["table"] as List<*>).map { (it as Number).toFloat() }.toFloatArray(),
                num(tm["lo"]).toFloat(),
                num(tm["hi"]).toFloat(),
                num(tm["knee_ev"]).toFloat()
            )
            val png = DepthPng.decode(depth.sidecarFile(base).readBytes())
            val meta = png.metadataJson?.let { JsonParse.parse(it) as? Map<*, *> }
            val conf = (meta?.get("confidence") as? Number)?.toFloat() ?: 0f
            val opts = BitmapFactory.Options().apply {
                inMutable = true
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val still = BitmapFactory.decodeFile(depth.sharpFile(base).absolutePath, opts) ?: return fail("sharp copy unreadable")
            try {
                val lens = RedlineNative.BokehLens(
                    focalMm = num(m["focal_mm"]).toFloat(),
                    aperture = n,
                    focusDistanceM = num(m["focus_distance_m"]).toFloat(),
                    focusX = fx.toFloat(),
                    focusY = fy.toFloat(),
                    phoneEquivalentAperture = num(m["phone_equivalent_aperture"]).toFloat()
                )
                val bd = RedlineNative.BokehDepth(png.channel(0), png.channel(1), png.channel(2), png.width, png.height, conf)
                val b0 = SystemClock.elapsedRealtime()
                val res = RedlineNative.bokehRender(still, lens, tone, bd, threads = threads, workMax = WORK_MAX)
                val bokehMs = SystemClock.elapsedRealtime() - b0
                val rot = (m["rotation"] as? Number)?.toInt() ?: 0
                val name = "${base}_${DofMath.label(n).replace("/", "").replace(".", "")}_${System.currentTimeMillis() % 100000}.jpg"
                saveJpeg(app, still, name, rot, "Redline re-edit ${DofMath.label(n)} (from $base)")
                DofReedit(
                    base,
                    ok = true,
                    aperture = n,
                    focusX = fx,
                    focusY = fy,
                    applied = res.applied,
                    reason = res.reason,
                    bokehMs = bokehMs,
                    totalMs = SystemClock.elapsedRealtime() - t0,
                    savedAs = name
                ).also { r -> update { it.reedit(r) } }
            } finally {
                still.recycle()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "re-edit failed", t)
            fail("${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun update(f: (DofReport) -> DofReport) {
        val now = synchronized(lock) { f(state).also { state = it } }
        onChange?.let { cb -> runCatching { cb(now) } }
    }

    companion object {
        private const val TAG = "RedlineDof"

        /** The guided-filter / matte grid's long side (the bokeh core's default). */
        const val WORK_MAX = 1024

        /** The DOF stage's own thread cap (the governor lowers it to 3 under load). */
        const val MAX_THREADS = 4

        @Volatile private var instance: DofStage? = null

        /** The app-lifetime stage (shares the depth lane). */
        fun shared(context: Context): DofStage = instance ?: synchronized(this) {
            instance ?: DofStage(context.applicationContext, DepthService.shared(context)).also { instance = it }
        }

        private fun num(v: Any?): Double = (v as Number).toDouble()

        /** Runs [block] at [nice] on this thread (the native workers it starts inherit it), then restores the priority. */
        fun <T> withNice(nice: Int, block: () -> T): T {
            val prev = runCatching { Process.getThreadPriority(Process.myTid()) }.getOrNull()
            runCatching { Process.setThreadPriority(nice) }
            try {
                return block()
            } finally {
                if (prev != null) runCatching { Process.setThreadPriority(prev) }
            }
        }

        /** A rendered still (sensor orientation) as a JPEG (q95) in DCIM/Redline with EXIF orientation [rotation]. */
        fun saveJpeg(context: Context, bmp: Bitmap, name: String, rotation: Int, description: String, keep: Boolean = true): Long {
            val tmp = File.createTempFile("dof", ".jpg", context.cacheDir)
            try {
                tmp.outputStream().buffered().use {
                    check(bmp.compress(Bitmap.CompressFormat.JPEG, DepthService.SHARP_QUALITY, it)) { "JPEG encode failed" }
                }
                val exif = ExifInterface(tmp.absolutePath)
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, CaptureMath.exifOrientation(rotation).toString())
                exif.setAttribute(ExifInterface.TAG_SOFTWARE, "Redline")
                exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, description)
                exif.saveAttributes()
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Redline")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val r = context.contentResolver
                val uri = r.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                    ?: error("MediaStore insert returned null")
                try {
                    r.openOutputStream(uri)!!.use { os -> tmp.inputStream().use { it.copyTo(os) } }
                    if (keep) {
                        r.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                    } else {
                        r.delete(uri, null, null)
                    }
                } catch (t: Throwable) {
                    runCatching { r.delete(uri, null, null) }
                    throw t
                }
                return tmp.length()
            } finally {
                tmp.delete()
            }
        }
    }
}
