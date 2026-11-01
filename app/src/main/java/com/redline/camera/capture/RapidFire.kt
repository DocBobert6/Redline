package com.redline.camera.capture

import kotlin.math.max
import kotlin.math.min

/** B23b: how many worker threads background work (merge, render) may use, and at what nice value. */
data class WorkerBudget(val threads: Int, val nice: Int, val loaded: Boolean)

/**
 * B23b: background work yields to the viewfinder under load. While the viewfinder is showing and work is backed up
 * behind the shot being processed (more than one shot saving, or presses waiting / a shot capturing), merge and render
 * use at most [LOADED_THREADS] threads at nice [LOADED_NICE], so the camera, the preview's GL thread and the UI keep the
 * CPU and the preview holds 30 fps. A lone shot (nothing behind it) or work with the viewfinder hidden gets every
 * thread at [IDLE_NICE], so the unhurried save time (B22 gate) does not change.
 */
object WorkerGovernor {
    const val IDLE_NICE = 5
    const val LOADED_NICE = 10
    const val LOADED_THREADS = 2

    /** B23b.1: the merge tail (finish / gather) runs this much nicer than the frame adds, so a queued burst's adds win. */
    const val TAIL_NICE_STEP = 3

    /** B23b.1: under load the store lane (JPEG encode, MediaStore) runs this much nicer than merge and render. */
    const val STORE_NICE_STEP = 2
    const val MAX_NICE = 19

    /**
     * [processing]: shots out of the camera and not yet saved; [capturing]: presses waiting plus the shot capturing.
     * B23b.1: [latched] (see [GovernorLatch]) keeps the budget loaded down to a backlog of one, so the last shots of a
     * rapid-fire run drain at the loaded budget too; a lone shot never latches.
     */
    fun budget(viewfinderActive: Boolean, processing: Int, capturing: Int, maxThreads: Int, latched: Boolean = false): WorkerBudget {
        val all = max(1, maxThreads)
        val backlog = processing + capturing
        return if (viewfinderActive && (backlog >= 2 || (latched && backlog >= 1))) {
            WorkerBudget(min(all, LOADED_THREADS), LOADED_NICE, loaded = true)
        } else {
            WorkerBudget(all, IDLE_NICE, loaded = false)
        }
    }

    /** B23b.1: the merge tail's nice for a budget. */
    fun tailNice(b: WorkerBudget): Int = min(MAX_NICE, b.nice + TAIL_NICE_STEP)

    /** B23b.1: the store lane's nice for a budget (lowered only under load). */
    fun storeNice(b: WorkerBudget): Int = if (b.loaded) min(MAX_NICE, b.nice + STORE_NICE_STEP) else b.nice

    /** Threads a pool of [cores] may use: at most [cap], and at most [limit] when the governor set one; never below 1. */
    fun cappedThreads(cores: Int, cap: Int, limit: Int?): Int {
        val n = cores.coerceIn(1, max(1, cap))
        return if (limit == null) n else n.coerceAtMost(max(1, limit))
    }
}

/**
 * B23b.1: the governor stays loaded from the moment the backlog reaches two (a rapid-fire run) until the queue is empty
 * or the viewfinder is gone, so the whole drain (backlog > 0) runs at the loaded budget. A lone shot (backlog one, never
 * two) does not latch and keeps every thread (the B22 unhurried save time).
 */
class GovernorLatch {
    @Volatile var latched = false
        private set

    fun update(viewfinderActive: Boolean, backlog: Int): Boolean {
        latched = when {
            !viewfinderActive || backlog <= 0 -> false
            backlog >= 2 -> true
            else -> latched
        }
        return latched
    }
}

/**
 * B23b: one rapid-fire run: from the processing queue going busy (empty to non-empty) until it next goes busy again.
 * Shots saved after the queue drained still belong to the run that took them. Preview readings count only while the
 * queue is non-empty (the drain).
 */
data class RapidFireRun(
    val shots: Int = 0,
    val bursts: Int = 0,
    val rawSingles: Int = 0,
    val cameraJpeg: Int = 0,
    val jpegReasons: Map<String, Int> = emptyMap(),
    val maxSaveWaitMs: Long = 0,
    val maxQueueDepth: Int = 0,
    val fpsMin: Double? = null,
    val fpsSamples: Int = 0,
    val droppedFrames: Int = 0,
    /** B23b.1: preview pauses for burst captures during this run, and their total length. */
    val pauses: Int = 0,
    val pauseMs: Double = 0.0
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "shots" to shots,
        "bursts" to bursts,
        "raw_single_shots" to rawSingles,
        "camera_jpeg_fallbacks" to cameraJpeg,
        "camera_jpeg_reasons" to jpegReasons,
        "max_save_wait_ms" to maxSaveWaitMs,
        "max_queue_depth" to maxQueueDepth,
        "preview_fps_min_during_drain" to fpsMin?.let { Math.round(it * 10.0) / 10.0 },
        "preview_fps_samples_during_drain" to fpsSamples,
        "preview_dropped_during_drain" to droppedFrames,
        "preview_pauses_during_drain" to pauses,
        "preview_pause_ms_during_drain" to Math.round(pauseMs)
    )
}

/**
 * B23b: the "rapid_fire" section of the burst report. Totals over the session, the run in progress (or the last one),
 * and the largest run. Gates (null until a run of [GATE_TAPS] shots exists): no camera JPEG in any such run, and the
 * preview fps never below [GATE_FPS_MIN] while such a run's queue drained.
 */
data class RapidFire(
    val busy: Boolean = false,
    val runs: Int = 0,
    val current: RapidFireRun = RapidFireRun(),
    /** Completed runs of at least [GATE_TAPS] shots: count, camera JPEGs, lowest fps during their drain (null = no sample). */
    val gateRuns: Int = 0,
    val gateCameraJpeg: Int = 0,
    val gateFpsMin: Double? = null,
    val gateFpsSamples: Int = 0,
    val largest: RapidFireRun? = null,
    val cameraJpegFallbacks: Int = 0,
    val rawSingleShots: Int = 0,
    val jpegReasons: Map<String, Int> = emptyMap(),
    val maxSaveWaitMs: Long = 0,
    val fpsMinUnderLoad: Double? = null,
    val droppedUnderLoad: Int = 0,
    /** The cumulative dropped-frame count last seen from the viewfinder (deltas count while busy). */
    val lastDroppedSeen: Int? = null,
    val workerThreadsLoaded: Int = WorkerGovernor.LOADED_THREADS,
    val workerNiceLoaded: Int = WorkerGovernor.LOADED_NICE,
    /** B23b.1: burst pauses while busy (count, ms), from the cumulative meter values last seen. */
    val pausesUnderLoad: Int = 0,
    val pauseMsUnderLoad: Double = 0.0,
    val warmupFramesUnderLoad: Int = 0,
    val lastPausesSeen: Int? = null,
    val lastPauseMsSeen: Double? = null,
    val lastWarmupSeen: Int? = null,
    /** B23b.1: the latest meter reading's first frames after recent pauses (diagnostic). */
    val recentResumes: List<Map<String, Any?>> = emptyList(),
    /** B23b.1: native worker read-back (pool nice, tail yields, render nice); null until read. */
    val nativeWorkers: Map<String, Int>? = null
) {
    /** The processing queue depth changed. Empty to non-empty starts a new run (closing the previous one). */
    fun queue(depth: Int): RapidFire {
        if (depth > 0 && !busy) {
            val closed = close()
            return closed.copy(busy = true, runs = closed.runs + 1, current = RapidFireRun(maxQueueDepth = depth))
        }
        return copy(busy = depth > 0, current = current.copy(maxQueueDepth = max(current.maxQueueDepth, depth)))
    }

    /** A saved (or failed) shot: its path, why it was the camera JPEG, and how long it waited for the save lane. */
    fun shot(path: String, singleReason: String?, saveWaitMs: Long?): RapidFire {
        val jpeg = path == BurstShot.PATH_JPEG
        val raw = path == BurstShot.PATH_SINGLE
        val why = if (jpeg) singleReason ?: "?" else null
        val wait = saveWaitMs ?: 0
        val c = current.copy(
            shots = current.shots + 1,
            bursts = current.bursts + if (path == BurstShot.PATH_BURST) 1 else 0,
            rawSingles = current.rawSingles + if (raw) 1 else 0,
            cameraJpeg = current.cameraJpeg + if (jpeg) 1 else 0,
            jpegReasons = why?.let { current.jpegReasons + (it to (current.jpegReasons[it] ?: 0) + 1) } ?: current.jpegReasons,
            maxSaveWaitMs = max(current.maxSaveWaitMs, wait)
        )
        return copy(
            current = c,
            cameraJpegFallbacks = cameraJpegFallbacks + if (jpeg) 1 else 0,
            rawSingleShots = rawSingleShots + if (raw) 1 else 0,
            jpegReasons = why?.let { jpegReasons + (it to (jpegReasons[it] ?: 0) + 1) } ?: jpegReasons,
            maxSaveWaitMs = max(maxSaveWaitMs, wait)
        )
    }

    /** A preview reading (warm only): [fps] the rolling rate, [droppedTotal] the viewfinder's cumulative dropped count. */
    fun preview(fps: Double?, droppedTotal: Int?): RapidFire {
        var next = this
        if (droppedTotal != null) {
            val prev = lastDroppedSeen
            val delta = if (prev == null || droppedTotal < prev) 0 else droppedTotal - prev
            next = next.copy(lastDroppedSeen = droppedTotal)
            if (busy && delta > 0) {
                next =
                    next.copy(
                        droppedUnderLoad = next.droppedUnderLoad + delta,
                        current = next.current.copy(
                            droppedFrames =
                            next.current.droppedFrames + delta
                        )
                    )
            }
        }
        if (busy && fps != null) {
            val c = next.current
            next = next.copy(
                fpsMinUnderLoad = minOf(next.fpsMinUnderLoad ?: fps, fps),
                current = c.copy(fpsMin = minOf(c.fpsMin ?: fps, fps), fpsSamples = c.fpsSamples + 1)
            )
        }
        return next
    }

    /**
     * B23b.1: a reading of the honest under-load meter (fps over counted frames only; drops; burst pauses; warm-up).
     * The fps and drops go through [preview]; pauses and warm-up frames are recorded separately and never lower the fps.
     */
    fun load(
        fps: Double?,
        droppedTotal: Int,
        pausesTotal: Int,
        pauseMsTotal: Double,
        warmupTotal: Int,
        resumes: List<Map<String, Any?>> = recentResumes
    ): RapidFire {
        var next = preview(fps, droppedTotal)
        val dp = delta(lastPausesSeen, pausesTotal)
        val dms = lastPauseMsSeen.let { if (it == null || pauseMsTotal < it) 0.0 else pauseMsTotal - it }
        val dw = delta(lastWarmupSeen, warmupTotal)
        next = next.copy(
            lastPausesSeen = pausesTotal,
            lastPauseMsSeen = pauseMsTotal,
            lastWarmupSeen = warmupTotal,
            recentResumes = resumes
        )
        if (busy) {
            next = next.copy(
                pausesUnderLoad = next.pausesUnderLoad + dp,
                pauseMsUnderLoad = next.pauseMsUnderLoad + dms,
                warmupFramesUnderLoad = next.warmupFramesUnderLoad + dw,
                current = next.current.copy(pauses = next.current.pauses + dp, pauseMs = next.current.pauseMs + dms)
            )
        }
        return next
    }

    private fun delta(prev: Int?, total: Int): Int = if (prev == null || total < prev) 0 else total - prev

    /** Folds the current run into the gate tallies and the largest run (a new run is about to start). */
    private fun close(): RapidFire {
        val c = current
        if (c.shots == 0) return this
        var next = if (largest == null || c.shots > largest.shots) copy(largest = c) else this
        if (c.shots >= GATE_TAPS) {
            next = next.copy(
                gateRuns = gateRuns + 1,
                gateCameraJpeg = gateCameraJpeg + c.cameraJpeg,
                gateFpsMin = c.fpsMin?.let { f -> minOf(gateFpsMin ?: f, f) } ?: gateFpsMin,
                gateFpsSamples = gateFpsSamples + c.fpsSamples
            )
        }
        return next
    }

    /** The gate tallies including the run in progress when it already has [GATE_TAPS] shots. */
    private fun withCurrent(): RapidFire = if (current.shots >= GATE_TAPS) close() else this

    /** Gate: no camera-JPEG fallback in any run of [GATE_TAPS]+ taps; null until such a run exists. */
    val noCameraJpegGate: Boolean? get() = withCurrent().let { if (it.gateRuns == 0) null else it.gateCameraJpeg == 0 }

    /** Gate: preview fps min >= [GATE_FPS_MIN] while a [GATE_TAPS]+ run's queue drained; null until one has a reading. */
    val previewFpsGate: Boolean? get() = withCurrent().let { r -> r.gateFpsMin?.let { it >= GATE_FPS_MIN } }

    fun toMap(): Map<String, Any?> {
        val g = withCurrent()
        return linkedMapOf(
            "camera_jpeg_fallbacks" to cameraJpegFallbacks,
            "camera_jpeg_reasons" to jpegReasons,
            "raw_single_shots" to rawSingleShots,
            "max_save_wait_ms" to maxSaveWaitMs,
            "preview_fps_min_under_load" to fpsMinUnderLoad?.let { Math.round(it * 10.0) / 10.0 },
            "preview_dropped_under_load" to droppedUnderLoad,
            "preview_fps_method" to FPS_METHOD,
            "preview_pauses_under_load" to pausesUnderLoad,
            "preview_pause_ms_under_load" to Math.round(pauseMsUnderLoad),
            "preview_warmup_frames_excluded" to warmupFramesUnderLoad,
            "preview_recent_resumes" to recentResumes,
            "runs" to runs,
            "busy" to busy,
            "current_run" to current.toMap(),
            "largest_run" to largest?.toMap(),
            "gate_runs_15_plus" to g.gateRuns,
            "worker_loaded" to linkedMapOf(
                "threads" to workerThreadsLoaded,
                "nice" to workerNiceLoaded,
                "tail_nice_step" to WorkerGovernor.TAIL_NICE_STEP,
                "store_nice_step" to WorkerGovernor.STORE_NICE_STEP
            ),
            "native_workers" to nativeWorkers,
            "holding_queue_max" to RawHoldingQueue.MAX_SINGLES,
            "gate" to linkedMapOf(
                "no_camera_jpeg_in_15_taps" to noCameraJpegGate,
                "preview_fps_min_ge_29_5_under_load" to previewFpsGate
            )
        )
    }

    companion object {
        const val GATE_TAPS = 15
        const val GATE_FPS_MIN = 29.5
        const val FPS_METHOD = "counted frames only: excludes frames while a burst capture is in flight and warm-up after each pause"

        /** B23b.1: the native worker read-back ([com.redline.camera.nativebridge.RedlineNative.workerStats]) for the report. */
        fun nativeWorkerMap(v: IntArray?): Map<String, Int>? {
            if (v == null || v.size < 6) return null
            return linkedMapOf(
                "pool_worker_nice_seen" to v[0],
                "pool_last_job_nice" to v[1],
                "tail_yields" to v[2],
                "tail_yield_ms" to v[3],
                "tail_slices" to v[4],
                "render_thread_nice_seen" to v[5]
            )
        }
    }
}
