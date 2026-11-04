package com.redline.camera.depth

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.redline.camera.capability.Json
import com.redline.camera.nativebridge.RedlineNative
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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

    // Worker thread only.
    private var engine: DepthEngine? = null

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

    /** Closes the engine on its own thread and stops the worker. */
    fun close() {
        runCatching {
            worker.execute {
                engine?.close()
                engine = null
            }
        }
        worker.shutdown()
    }

    private fun run(base: String, rot: Int, imageW: Int, imageH: Int, prepMs: Long, handedAt: Long) {
        val start = SystemClock.elapsedRealtime()
        val queueMs = start - handedAt
        val e = engine ?: DepthEngine.open(app.assets, cpuOnly).also {
            engine = it
            update { s -> s.copy(engine = it.info) }
        }
        if (!e.ready) {
            skip(DepthReport.SKIP_ENGINE)
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

    private fun prune() {
        val files = dir.listFiles { f -> f.name.endsWith(SUFFIX) } ?: return
        if (files.size <= KEEP_SIDECARS) return
        files.sortedByDescending { it.lastModified() }.drop(KEEP_SIDECARS).forEach { runCatching { it.delete() } }
    }

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
