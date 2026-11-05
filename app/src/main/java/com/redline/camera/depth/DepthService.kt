package com.redline.camera.depth

import android.content.Context
import android.graphics.Bitmap
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.redline.camera.capability.Json
import com.redline.camera.nativebridge.RedlineNative
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * B30: the depth lane. One worker thread owns the [DepthEngine] (compiled lazily on first use, GPU models stay on their
 * thread) and runs one job at a time: a shot that arrives while a job is running is skipped ("busy", recorded in the
 * [report]), never queued, so depth can never back up the save path.
 *
 * [submit] runs on the caller's (store) lane: it takes the job slot, runs the native prep from the rendered still into
 * this service's own direct buffers (the bitmap may be recycled as soon as it returns), then hands the rest to the
 * worker: the two models, the native finish (no distortion warp), and the sidecar files/depth/<base>.depth.png, pruned
 * to the newest [KEEP_SIDECARS].
 */
class DepthService(context: Context, private val cpuOnly: Boolean = false) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, DIR)
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "redline-depth").apply { isDaemon = true } }
    private val busy = AtomicBoolean(false)

    // Owned by whoever holds [busy]: the store lane during prep, then the worker.
    private val rgbCanvas = direct(DepthEngine.W * DepthEngine.H * 3)
    private val segIn = direct(DepthEngine.SEG_W * DepthEngine.SEG_H * 3)
    private val depthOut = direct(DepthEngine.W * DepthEngine.H)
    private val segOut = direct(DepthEngine.SEG_W * DepthEngine.SEG_H)
    private val rect = IntArray(4)
    private val packed = ShortArray(DepthEngine.W * DepthEngine.H * 3)

    // Created and used on the worker thread; read (ready or not) from the store lane.
    @Volatile private var engine: DepthEngine? = null
    private val warming = AtomicBoolean(false)

    /** B31: writes the sharp copies (a JPEG encode) off both the store lane and the depth worker. */
    private val io: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "redline-dof-io").apply { isDaemon = true } }
    private val dofDir = File(app.filesDir, DOF_DIR)

    private val lock = Any()
    private var state = DepthReport()

    /** The report's "depth" section as of now. */
    val report: DepthReport get() = synchronized(lock) { state }

    fun skip(reason: String) = update { it.skip(reason) }

    /** Called (on whichever thread changed it) after every report change: a shot recorded, a skip, the engine up. */
    @Volatile var onChange: ((DepthReport) -> Unit)? = null

    /** Mirrors the user setting into the report (the saver's own flag gates the work). */
    fun setEnabled(on: Boolean) = update { it.copy(enabled = on) }

    /**
     * Starts depth for a saved shot from its rendered [still] ([rotation] = clockwise degrees to upright, the JPEG
     * orientation). Returns quickly; never throws. Skips (recorded) when a job is still running.
     */
    fun submit(still: Bitmap, rotation: Int, base: String) {
        if (!busy.compareAndSet(false, true)) {
            skip(DepthReport.SKIP_BUSY)
            return
        }
        var handed = false
        try {
            val p0 = SystemClock.elapsedRealtime()
            val imageW = still.width
            val imageH = still.height
            rgbCanvas.clear()
            segIn.clear()
            val err = RedlineNative.depthPrep(
                still,
                rotation,
                rgbCanvas,
                DepthEngine.W,
                DepthEngine.H,
                segIn,
                DepthEngine.SEG_W,
                DepthEngine.SEG_H,
                rect
            )
            val prepMs = SystemClock.elapsedRealtime() - p0
            if (err != null) {
                update { it.record(DepthShot(base = base, ok = false, prepMs = prepMs, error = "prep: $err")) }
                return
            }
            val handedAt = SystemClock.elapsedRealtime()
            worker.execute {
                try {
                    run(base, rotation, imageW, imageH, prepMs, handedAt)
                } finally {
                    busy.set(false)
                }
            }
            handed = true
        } catch (e: Throwable) {
            Log.w(TAG, "depth submit failed", e)
            update { it.record(DepthShot(base = base, ok = false, prepMs = 0, error = "submit: ${describe(e)}")) }
        } finally {
            if (!handed) busy.set(false)
        }
    }

    /**
     * B31: compiles the engine on the worker now (at nice [WARM_NICE]) instead of on the first shot, so the first DOF shot
     * finds it ready. Never blocks; does nothing when it is up or already warming.
     */
    fun warmUp() {
        if (engine != null || !warming.compareAndSet(false, true)) return
        runCatching {
            worker.execute {
                try {
                    runCatching { Process.setThreadPriority(WARM_NICE) }
                    if (engine == null) {
                        engine = DepthEngine.open(app.assets, cpuOnly).also { update { s -> s.copy(engine = it.info) } }
                    }
                } finally {
                    warming.set(false)
                }
            }
        }.onFailure { warming.set(false) }
    }

    /** B31: the engine compiled and passed its self-check (a sync job will not wait for compilation). */
    val engineReady: Boolean get() = engine?.ready == true

    /** B31: the maps of one shot (still orientation), handed back to the save path by [runSync]. */
    class SyncDepth(
        val depth: FloatArray,
        val mask: FloatArray,
        val confidenceMap: FloatArray,
        val width: Int,
        val height: Int,
        val confidence: Float,
        val usable: Boolean,
        val personCoverage: Float,
        val prepMs: Long,
        val depthMs: Long,
        val segMs: Long,
        val finishMs: Long,
        /** Prep to maps back (what the save path waited). */
        val waitMs: Long,
        val delegate: String?
    )

    /** B31: [maps], or why there are none ([skip], a reason for the DOF report). */
    class SyncResult(val maps: SyncDepth?, val skip: String?)

    /**
     * B31: depth for a shot on the save path. Prep runs here, the models and the finish on the worker, and this call waits
     * for the maps (at most [timeoutMs]); the sidecar is then written on the worker, after the maps are handed back. Never
     * queues and never compiles: an engine not ready (warm-up started) or busy returns at once with the reason, and the
     * caller saves the still sharp. [nice]: the worker's priority for this job (the save path's throttle).
     */
    fun runSync(still: Bitmap, rotation: Int, base: String, nice: Int, timeoutMs: Long = SYNC_TIMEOUT_MS): SyncResult {
        val e = engine
        if (e == null) {
            warmUp()
            return SyncResult(null, "$SKIP_SYNC_NOT_READY (warming up)")
        }
        if (!e.ready) return SyncResult(null, "$SKIP_SYNC_NOT_READY (${e.info.error ?: "self-check failed"})")
        if (!busy.compareAndSet(false, true)) return SyncResult(null, SKIP_SYNC_BUSY)
        val t0 = SystemClock.elapsedRealtime()
        val future = CompletableFuture<SyncDepth?>()
        var handed = false
        try {
            val imageW = still.width
            val imageH = still.height
            rgbCanvas.clear()
            segIn.clear()
            val err = RedlineNative.depthPrep(
                still,
                rotation,
                rgbCanvas,
                DepthEngine.W,
                DepthEngine.H,
                segIn,
                DepthEngine.SEG_W,
                DepthEngine.SEG_H,
                rect
            )
            val prepMs = SystemClock.elapsedRealtime() - t0
            if (err != null) {
                update { it.record(DepthShot(base = base, ok = false, prepMs = prepMs, error = "prep: $err")) }
                return SyncResult(null, "depth failed (prep: $err)")
            }
            val handedAt = SystemClock.elapsedRealtime()
            worker.execute {
                try {
                    runCatching { Process.setThreadPriority(nice) }
                    run(base, rotation, imageW, imageH, prepMs, handedAt) { maps -> future.complete(maps) }
                } catch (t: Throwable) {
                    future.complete(null)
                    throw t
                } finally {
                    future.complete(null)
                    busy.set(false)
                }
            }
            handed = true
        } catch (t: Throwable) {
            Log.w(TAG, "depth sync failed", t)
            return SyncResult(null, "depth failed (${describe(t)})")
        } finally {
            if (!handed) busy.set(false)
        }
        val maps = try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (t: TimeoutException) {
            return SyncResult(null, "depth failed (timed out after $timeoutMs ms)")
        } catch (t: Throwable) {
            return SyncResult(null, "depth failed (${describe(t)})")
        }
        return if (maps == null) SyncResult(null, "depth failed (${report.lastError ?: "no maps"})") else SyncResult(maps, null)
    }

    /**
     * B31: keeps the sharp render for re-edit: [copy] (recycled here) written as files/dof/<base>.sharp.jpg (q95) on the
     * io thread, with [metaJson] (the lens and tone the shot was rendered with) as <base>.dof.json, then pruned with the
     * depth sidecars. Never throws.
     */
    fun writeSharp(copy: Bitmap, base: String, metaJson: String, onDone: (Boolean) -> Unit = {}) {
        runCatching {
            io.execute {
                var ok = false
                try {
                    runCatching { Process.setThreadPriority(IO_NICE) }
                    dofDir.mkdirs()
                    val tmp = File(dofDir, "$base$SHARP_SUFFIX.tmp")
                    tmp.outputStream().buffered().use { check(copy.compress(Bitmap.CompressFormat.JPEG, SHARP_QUALITY, it)) { "encode" } }
                    File(dofDir, "$base$META_SUFFIX").writeText(metaJson)
                    ok = tmp.renameTo(File(dofDir, "$base$SHARP_SUFFIX"))
                    pruneSharp()
                } catch (t: Throwable) {
                    Log.w(TAG, "sharp copy failed", t)
                } finally {
                    copy.recycle()
                    runCatching { onDone(ok) }
                }
            }
        }.onFailure {
            copy.recycle()
            onDone(false)
        }
    }

    /** B31: shots that have both a sidecar and a sharp copy (re-edit candidates), newest first. */
    fun reeditable(): List<String> {
        val sharp = dofDir.listFiles { f -> f.name.endsWith(SHARP_SUFFIX) } ?: return emptyList()
        return sharp.sortedByDescending { it.lastModified() }
            .map { it.name.removeSuffix(SHARP_SUFFIX) }
            .filter { File(dir, "$it$SUFFIX").isFile }
    }

    fun sharpFile(base: String) = File(dofDir, "$base$SHARP_SUFFIX")

    fun sidecarFile(base: String) = File(dir, "$base$SUFFIX")

    /** Closes the engine on its own thread and stops the worker. */
    fun close() {
        runCatching {
            worker.execute {
                engine?.close()
                engine = null
            }
        }
        worker.shutdown()
        io.shutdown()
    }

    private fun run(
        base: String,
        rot: Int,
        imageW: Int,
        imageH: Int,
        prepMs: Long,
        handedAt: Long,
        deliver: ((SyncDepth?) -> Unit)? = null
    ) {
        val start = SystemClock.elapsedRealtime()
        val queueMs = start - handedAt
        val e = engine ?: DepthEngine.open(app.assets, cpuOnly).also {
            engine = it
            update { s -> s.copy(engine = it.info) }
        }
        if (!e.ready) {
            skip(DepthReport.SKIP_ENGINE)
            deliver?.invoke(null)
            return
        }
        try {
            val d0 = SystemClock.elapsedRealtime()
            val depth = e.runDepth(floats(rgbCanvas, DepthEngine.W * DepthEngine.H * 3))
            val depthMs = SystemClock.elapsedRealtime() - d0
            val s0 = SystemClock.elapsedRealtime()
            val seg = e.runSegmenter(floats(segIn, DepthEngine.SEG_W * DepthEngine.SEG_H * 3))
            val segMs = SystemClock.elapsedRealtime() - s0
            put(depthOut, depth, DepthEngine.W * DepthEngine.H)
            put(segOut, seg, DepthEngine.SEG_W * DepthEngine.SEG_H)
            val f0 = SystemClock.elapsedRealtime()
            val stats = RedlineNative.depthFinish(
                depthOut,
                segOut,
                DepthEngine.SEG_W,
                DepthEngine.SEG_H,
                rgbCanvas,
                DepthEngine.W,
                DepthEngine.H,
                rect,
                rot,
                imageW,
                imageH,
                null,
                packed
            )
            val finishMs = SystemClock.elapsedRealtime() - f0
            deliver?.invoke(
                syncMaps(stats, prepMs, depthMs, segMs, finishMs, SystemClock.elapsedRealtime() - handedAt + prepMs, e.info.delegate)
            )
            val w0 = SystemClock.elapsedRealtime()
            val meta = Json.value(metadata(e, stats, rot, imageW, imageH))
            dir.mkdirs()
            val name = "$base$SUFFIX"
            val tmp = File(dir, "$name.tmp")
            tmp.outputStream().buffered().use { DepthPng.write(it, stats.outWidth, stats.outHeight, packed, meta) }
            val file = File(dir, name)
            check(tmp.renameTo(file)) { "could not rename the sidecar" }
            prune()
            val writeMs = SystemClock.elapsedRealtime() - w0
            update {
                it.record(
                    DepthShot(
                        base = base,
                        ok = true,
                        prepMs = prepMs,
                        queueMs = queueMs,
                        depthMs = depthMs,
                        segMs = segMs,
                        finishMs = finishMs,
                        writeMs = writeMs,
                        totalMs = SystemClock.elapsedRealtime() - handedAt,
                        delegate = e.info.delegate,
                        confidence = stats.confidence.toDouble(),
                        usable = stats.usable,
                        personCoverage = stats.personCoverage.toDouble(),
                        width = stats.outWidth,
                        height = stats.outHeight,
                        storedBytes = file.length(),
                        file = "$DIR/$name"
                    )
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "depth job failed", t)
            deliver?.invoke(null)
            update {
                it.record(
                    DepthShot(
                        base = base,
                        ok = false,
                        prepMs = prepMs,
                        queueMs = queueMs,
                        totalMs = SystemClock.elapsedRealtime() - handedAt,
                        delegate = e.info.delegate,
                        error = describe(t)
                    )
                )
            }
        }
    }

    private fun metadata(e: DepthEngine, s: RedlineNative.DepthStats, rot: Int, imageW: Int, imageH: Int): Map<String, Any?> = linkedMapOf(
        "version" to 1,
        "model" to DepthEngine.MODEL_NAME,
        "segmenter" to DepthEngine.SEG_NAME,
        "delegate" to e.info.delegate,
        "channels" to "R depth (relative inverse, larger = nearer, normalised to dmin..dmax), G person mask, B confidence",
        "size" to "${s.outWidth}x${s.outHeight}",
        "image_size" to "${imageW}x$imageH",
        "rotation" to rot,
        "distortion_warped" to false,
        "dmin" to s.dmin.toDouble(),
        "dmax" to s.dmax.toDouble(),
        "confidence" to s.confidence.toDouble(),
        "usable" to s.usable,
        "p2" to s.p2.toDouble(),
        "p98" to s.p98.toDouble(),
        "spread" to s.spread.toDouble(),
        "range_score" to s.rangeScore.toDouble(),
        "edge_agree" to s.edgeAgree?.toDouble(),
        "edge_score" to s.edgeScore.toDouble(),
        "person_coverage" to s.personCoverage.toDouble(),
        "person_nearer" to s.personNearer?.toDouble(),
        "person_score" to s.personScore.toDouble()
    )

    private fun syncMaps(
        s: RedlineNative.DepthStats,
        prepMs: Long,
        depthMs: Long,
        segMs: Long,
        finishMs: Long,
        waitMs: Long,
        delegate: String?
    ): SyncDepth {
        val n = s.outWidth * s.outHeight
        val d = FloatArray(n)
        val m = FloatArray(n)
        val c = FloatArray(n)
        for (i in 0 until n) {
            d[i] = (packed[3 * i].toInt() and 0xFFFF) / 65535f
            m[i] = (packed[3 * i + 1].toInt() and 0xFFFF) / 65535f
            c[i] = (packed[3 * i + 2].toInt() and 0xFFFF) / 65535f
        }
        return SyncDepth(
            d, m, c, s.outWidth, s.outHeight, s.confidence, s.usable, s.personCoverage, prepMs, depthMs, segMs, finishMs, waitMs,
            delegate
        )
    }

    private fun prune() {
        val files = dir.listFiles { f -> f.name.endsWith(SUFFIX) } ?: return
        if (files.size > KEEP_SIDECARS) {
            files.sortedByDescending { it.lastModified() }.drop(KEEP_SIDECARS).forEach { runCatching { it.delete() } }
        }
        pruneSharp()
    }

    /** B31: sharp copies go with their sidecars (a copy whose sidecar was pruned is deleted), newest [KEEP_SIDECARS]. */
    @Synchronized private fun pruneSharp() {
        val sharp = dofDir.listFiles { f -> f.name.endsWith(SHARP_SUFFIX) } ?: return
        val now = System.currentTimeMillis()
        sharp.sortedByDescending { it.lastModified() }.forEachIndexed { i, f ->
            val base = f.name.removeSuffix(SHARP_SUFFIX)
            // A copy written moments before its sidecar is kept (the sidecar is still being written on the worker).
            val orphan = !File(dir, "$base$SUFFIX").isFile && now - f.lastModified() > ORPHAN_GRACE_MS
            if (i >= KEEP_SIDECARS || orphan) runCatching { f.delete() }
        }
        dofDir.listFiles { f -> f.name.endsWith(META_SUFFIX) }?.forEach { f ->
            val base = f.name.removeSuffix(META_SUFFIX)
            if (!File(dofDir, "$base$SHARP_SUFFIX").isFile && now - f.lastModified() > ORPHAN_GRACE_MS) runCatching { f.delete() }
        }
    }

    fun metaFile(base: String) = File(dofDir, "$base$META_SUFFIX")

    private fun update(f: (DepthReport) -> DepthReport) {
        val now = synchronized(lock) { f(state).also { state = it } }
        onChange?.let { cb -> runCatching { cb(now) } }
    }

    companion object {
        private const val TAG = "RedlineDepth"

        @Volatile private var instance: DepthService? = null

        /** The app-lifetime depth lane (one worker, one engine) shared by every activity instance. */
        fun shared(context: Context): DepthService = instance ?: synchronized(this) {
            instance ?: DepthService(context.applicationContext).also { instance = it }
        }
        const val DIR = "depth"
        const val SUFFIX = ".depth.png"
        const val KEEP_SIDECARS = 20

        /** B31: the sharp renders kept for re-edit (app-private), JPEG quality. */
        const val DOF_DIR = "dof"
        const val SHARP_SUFFIX = ".sharp.jpg"
        const val META_SUFFIX = ".dof.json"
        const val SHARP_QUALITY = 95
        const val SYNC_TIMEOUT_MS = 3_000L
        const val SKIP_SYNC_NOT_READY = "depth engine not ready"
        const val SKIP_SYNC_BUSY = "depth engine busy"
        private const val WARM_NICE = 10
        private const val IO_NICE = 10
        private const val ORPHAN_GRACE_MS = 60_000L

        private fun direct(floats: Int): ByteBuffer = ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder())

        private fun floats(b: ByteBuffer, n: Int): FloatArray {
            val fb = b.duplicate().order(ByteOrder.nativeOrder()).apply { clear() }.asFloatBuffer()
            return FloatArray(n).also { fb.get(it) }
        }

        private fun put(b: ByteBuffer, v: FloatArray, n: Int) {
            require(v.size >= n) { "model output has ${v.size} values, expected $n" }
            val fb = b.duplicate().order(ByteOrder.nativeOrder()).apply { clear() }.asFloatBuffer()
            fb.put(v, 0, n)
        }

        private fun describe(t: Throwable) = "${t.javaClass.simpleName}: ${t.message}"
    }
}
