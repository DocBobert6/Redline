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

    /** [processing]: shots out of the camera and not yet saved; [capturing]: presses waiting plus the shot capturing. */
    fun budget(viewfinderActive: Boolean, processing: Int, capturing: Int, maxThreads: Int): WorkerBudget {
        val all = max(1, maxThreads)
        val backlog = processing + capturing
        return if (viewfinderActive && backlog >= 2) {
            WorkerBudget(min(all, LOADED_THREADS), LOADED_NICE, loaded = true)
        } else {
            WorkerBudget(all, IDLE_NICE, loaded = false)
        }
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
    val droppedFrames: Int = 0
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
        "preview_dropped_during_drain" to droppedFrames
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
    val workerNiceLoaded: Int = WorkerGovernor.LOADED_NICE
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
                next = next.copy(droppedUnderLoad = next.droppedUnderLoad + delta, current = next.current.copy(droppedFrames = next.current.droppedFrames + delta))
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
            "runs" to runs,
            "busy" to busy,
            "current_run" to current.toMap(),
            "largest_run" to largest?.toMap(),
            "gate_runs_15_plus" to g.gateRuns,
            "worker_loaded" to linkedMapOf("threads" to workerThreadsLoaded, "nice" to workerNiceLoaded),
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
    }
}
