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
import com.redline.camera.capture.WorkerGovernor
import com.redline.camera.colour.ColourPipeline
import com.redline.camera.depth.DepthPng
import com.redline.camera.depth.DepthService
import com.redline.camera.nativebridge.RedlineNative
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * B31: simulated depth of field, and re-edit after capture.
 *
 * B31.1: DOF is off the shutter-to-saved path. The store lane saves the sharp photo exactly as without DOF, then hands
 * the rendered still to [enqueue]: a bounded background queue ([DofReport.QUEUE_CAPACITY] waiting; a full queue leaves
 * the shot sharp, recorded) on its own thread, which waits until the capture pipeline is idle (rapid fire: the queue
 * drains after the run), then runs [apply] (depth on the [DepthService] lane, the sharp copy for re-edit, the bokeh in
 * place, the Ultra HDR gain map recomputed from the blurred linear result) and rewrites the same MediaStore item in place
 * ([Job.replace]). The bokeh runs at the throttle nice ([VF_NICE], [VF_THREADS] threads) while the viewfinder is showing,
 * else at [WorkerGovernor.IDLE_NICE] with [MAX_THREADS]; its workers are pinned to the cores above the little cluster.
 * Queued shots beyond the first keep their pixels in an app-private spill file instead of memory.
 *
 * Every failure leaves the sharp photo (our own render, never the camera JPEG) in place.
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

    /** B31.1: the capture pipeline has shots capturing or saving (the queue waits for it to go idle). */
    @Volatile var pipelineBusy: () -> Boolean = { false }

    /** B31.1: the viewfinder is showing (the bokeh runs at the throttle budget). */
    @Volatile var viewfinderActive: () -> Boolean = { false }

    /** B31.1: the system reports low memory (a job then leaves its shot sharp). */
    @Volatile var lowMemory: () -> Boolean = { false }

    private val queueLock = Any()
    private val waiting = ArrayDeque<Job>()
    private var running = false
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "redline-dof").apply { isDaemon = true } }
    private val spiller = Executors.newSingleThreadExecutor { r -> Thread(r, "redline-dof-spill").apply { isDaemon = true } }
    private val spillDir = File(app.cacheDir, SPILL_DIR)

    @Volatile private var jobFpsMin: Double? = null

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
        if (busy) {
            jobFpsMin = jobFpsMin?.let { minOf(it, v) } ?: v
            update { it.fps(v) }
        }
    }

    /** What the save path knows about the shot. */
    data class Request(
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
        val lowMemory: Boolean,
        val aperture: Float? = null,
        /** B31.1: a stress run shot (B22 burst stress when true; with [keep] false and this false, the B13 run). */
        val stress: Boolean = false,
        /** B31.1: pin the bokeh workers to the cores above the little cluster. */
        val bigCores: Boolean = true
    )

    /** B31.1: what rewriting the saved photo in place did. */
    class ReplaceResult(val ok: Boolean, val ms: Long, val error: String? = null)

    /**
     * B31.1: one shot for the background queue. [request]'s still is owned by the job from [enqueue] on (recycled
     * when it is done). [replace] re-encodes the blurred still (with the recomputed gain map and the look tag
     * suffix, e.g. "simulated DOF f/2.8") into the photo already saved. [sharpSaveMs]: its shutter-to-saved time, set
     * by the store lane once known.
     */
    class Job(val request: Request, val shutterAtMs: Long, val replace: (Bitmap, ColourPipeline.HdrMap?, String) -> ReplaceResult) {
        @Volatile var sharpSaveMs: Long? = null
        internal val enqueuedAtMs = SystemClock.elapsedRealtime()

        @Volatile internal var still: Bitmap? = request.still

        @Volatile internal var spill: File? = null

        @Volatile internal var spillDone: CountDownLatch? = null
        internal val w = request.still.width
        internal val h = request.still.height
    }

    /**
     * B31.1: hands a saved shot to the background queue. True: the job owns [Job.request]'s still from now on. False:
     * the queue is full ([DofReport.QUEUE_CAPACITY] waiting) or DOF is off; the shot stays sharp (recorded) and the
     * caller keeps the still. Never blocks on DOF work.
     */
    fun enqueue(job: Job): Boolean {
        if (!enabled) return false
        val depthNow: Int
        val spill: Boolean
        synchronized(queueLock) {
            if (waiting.size >= DofReport.QUEUE_CAPACITY) {
                depthNow = -1
                spill = false
            } else {
                spill = waiting.isNotEmpty() || running
                waiting.addLast(job)
                depthNow = waiting.size + if (running) 1 else 0
            }
        }
        if (depthNow < 0) {
            skip(job.request, "${DofReport.SKIP_QUEUE_FULL} (${DofReport.QUEUE_CAPACITY} waiting)", queued = false)
            update { it.overflowed() }
            return false
        }
        if (spill) spillAsync(job)
        update { it.enqueued(depthNow) }
        worker.execute { drainOne() }
        return true
    }

    /** B31.1: records a shot that gets no DOF (sharp) with [reason]. */
    fun skip(r: Request, reason: String, queued: Boolean = false, sharpSaveMs: Long? = null) {
        update {
            it.record(
                DofShot(
                    base = r.base,
                    applied = false,
                    reason = reason,
                    aperture = r.aperture ?: aperture,
                    focalMm = r.focalMm,
                    focusDistanceM = r.focus.metres,
                    focusSource = r.focus.source,
                    phoneEquivalentAperture = r.phoneEquivalentAperture,
                    queued = queued,
                    shutterToSavedMs = sharpSaveMs
                )
            )
        }
    }

    /** Jobs waiting or running. */
    val queueDepth: Int get() = synchronized(queueLock) { waiting.size + if (running) 1 else 0 }

    // The waiting job's pixels to an app-private file (the next jobs wait anyway); restored when it runs.
    private fun spillAsync(job: Job) {
        val latch = CountDownLatch(1)
        job.spillDone = latch
        runCatching {
            spiller.execute {
                try {
                    runCatching { Process.setThreadPriority(SPILL_NICE) }
                    val b = job.still ?: return@execute
                    spillDir.mkdirs()
                    val f = File(spillDir, "${job.request.base}.rgba")
                    RandomAccessFile(f, "rw").use { raf ->
                        val bytes = b.byteCount.toLong()
                        raf.setLength(bytes)
                        val map = raf.channel.map(FileChannel.MapMode.READ_WRITE, 0, bytes)
                        b.copyPixelsToBuffer(map)
                        map.force()
                    }
                    job.spill = f
                    job.still = null
                    b.recycle()
                } catch (t: Throwable) {
                    Log.w(TAG, "DOF spill failed (kept in memory)", t)
                } finally {
                    latch.countDown()
                }
            }
        }.onFailure { latch.countDown() }
    }

    private fun restore(job: Job): Bitmap? {
        job.spillDone?.await(SPILL_WAIT_MS, TimeUnit.MILLISECONDS)
        job.still?.let { return it }
        val f = job.spill ?: return null
        return try {
            val b = Bitmap.createBitmap(job.w, job.h, Bitmap.Config.ARGB_8888)
            RandomAccessFile(f, "r").use { raf ->
                val map = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
                b.copyPixelsFromBuffer(map)
            }
            b
        } catch (t: Throwable) {
            Log.w(TAG, "DOF restore failed", t)
            null
        } finally {
            f.delete()
        }
    }

    private fun drainOne() {
        val job = synchronized(queueLock) {
            waiting.removeFirstOrNull()?.also { running = true }
        } ?: return
        try {
            runJob(job)
        } catch (t: Throwable) {
            Log.w(TAG, "DOF job failed; the sharp photo stays", t)
        } finally {
            val d = synchronized(queueLock) {
                running = false
                waiting.size
            }
            update { it.depth(d) }
        }
    }

    private fun runJob(job: Job) {
        // rapid fire: run when the capture pipeline is idle (bounded wait, then at the throttle budget anyway)
        val w0 = SystemClock.elapsedRealtime()
        while (runCatching { pipelineBusy() }.getOrDefault(false) && SystemClock.elapsedRealtime() - w0 < MAX_IDLE_WAIT_MS) {
            Thread.sleep(IDLE_POLL_MS)
        }
        val startedAt = SystemClock.elapsedRealtime()
        val still = restore(job)
        if (still == null) {
            skip(job.request, "${DofReport.SKIP_ERROR}: queued pixels lost", queued = true, sharpSaveMs = job.sharpSaveMs)
            return
        }
        try {
            if (!enabled) {
                skip(job.request, "DOF turned off before it ran", queued = true, sharpSaveMs = job.sharpSaveMs)
                return
            }
            val vf = runCatching { viewfinderActive() }.getOrDefault(true)
            val r = job.request.copy(
                still = still,
                threads = if (vf) VF_THREADS else MAX_THREADS,
                nice = if (vf) VF_NICE else WorkerGovernor.IDLE_NICE,
                lowMemory = runCatching { lowMemory() }.getOrDefault(false)
            )
            jobFpsMin = null
            val out = apply(r)
            var shot = out.shot ?: return
            if (shot.applied) {
                val label = DofMath.label(shot.aperture)
                val rr = try {
                    job.replace(still, out.hdr, "simulated DOF $label")
                } catch (t: Throwable) {
                    ReplaceResult(false, 0, "${t.javaClass.simpleName}: ${t.message}")
                }
                shot = shot.copy(
                    replaced = rr.ok,
                    replaceMs = rr.ms,
                    replaceError = rr.error,
                    timeToFinalMs = if (rr.ok) SystemClock.elapsedRealtime() - job.shutterAtMs else null
                )
            } else if (!out.depthHandled && r.keep) {
                // the sidecar for re-edit, as a shot without DOF gets it
                runCatching { depth.submit(still, r.rotation, r.base) }
            }
            if (out.windowOpen) working.decrementAndGet()
            update {
                it.record(
                    shot.copy(
                        queued = true,
                        queueWaitMs = startedAt - job.enqueuedAtMs,
                        shutterToSavedMs = job.sharpSaveMs,
                        viewfinderActive = vf,
                        previewFpsMin = jobFpsMin
                    )
                )
            }
        } finally {
            still.recycle()
        }
    }

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
            !r.keep -> return skip(DofReport.notKeptReason(r.stress))
            r.lowMemory -> return skip(DofReport.SKIP_MEMORY)
            r.tone == null -> return skip(DofReport.SKIP_NO_TONE)
            r.phoneEquivalentAperture == null -> return skip(DofReport.SKIP_NO_LENS)
        }
        val tone: ColourPipeline.RenderTone = r.tone
        val phoneN: Double = r.phoneEquivalentAperture
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
            val res = withNice(r.nice) {
                RedlineNative.bokehRender(
                    r.still,
                    lens,
                    bt,
                    bd,
                    threads = r.threads,
                    workMax = WORK_MAX,
                    nice = r.nice,
                    bigCores = r.bigCores
                )
            }
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
                delegate = maps.delegate,
                stagesMs = if (res.applied) res.stagesMs.mapValues { Math.round(it.value) } else null,
                cpus = res.cpus.takeIf { res.applied },
                workerNice = res.workerNice.takeIf { res.applied },
                bigCores = res.bigCoresAvailable && r.bigCores
            )
            return Outcome(shot, hdr, depthHandled = true, windowOpen = true)
        } catch (t: Throwable) {
            Log.w(TAG, "DOF failed; saving sharp", t)
            return skip("${DofReport.SKIP_ERROR}: ${t.javaClass.simpleName}: ${t.message}", handled = true, open = true)
        }
    }

    /**
     * Records the shot with its shutter-to-saved time and closes its preview-fps window, for a caller that ran [apply]
     * itself (tests); the background queue records its own. Never throws.
     */
    fun finish(o: Outcome, shutterToSavedMs: Long?) {
        if (o.windowOpen) working.decrementAndGet()
        val s = o.shot ?: return
        update { it.record(s.copy(shutterToSavedMs = shutterToSavedMs)) }
    }

    private fun focusPoint(tap: Pair<Double, Double>?, maps: DepthService.SyncDepth): Triple<String, Double, Double> {
        if (tap != null) return Triple(DofMath.POINT_TAP, tap.first, tap.second)
        val c = DofMath.personFocusPoint(maps.mask, maps.width, maps.height)
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
                val res = RedlineNative.bokehRender(still, lens, tone, bd, threads = threads, workMax = WORK_MAX, bigCores = true)
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

        /** The DOF stage's own thread cap (viewfinder hidden). */
        const val MAX_THREADS = 4

        /** B31.1: the throttle budget while the viewfinder is showing (the preview's threads win every contended core). */
        const val VF_THREADS = 3
        const val VF_NICE = 10

        /** B31.1: how long a queued job waits for the capture pipeline to go idle before it runs anyway. */
        const val MAX_IDLE_WAIT_MS = 20_000L
        const val IDLE_POLL_MS = 50L
        private const val SPILL_DIR = "dof-queue"
        private const val SPILL_NICE = 10
        private const val SPILL_WAIT_MS = 30_000L

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
