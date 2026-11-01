package com.redline.camera.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RapidFireTest {
    // ---------------------------------------------------------------- RawHoldingQueue

    @Test
    fun holdingQueueIsThePoolLessWhatPendingBurstsMayHold() {
        val perBurst = BurstPlanner.MAX_RESIDENT_FRAMES + 1
        assertEquals(7, perBurst)
        assertEquals(12, RawHoldingQueue.capacity(poolFrames = 12, burstsPending = 0))
        assertEquals(5, RawHoldingQueue.capacity(poolFrames = 12, burstsPending = 1))
        // Floor 2 while the pool has buffers, even when bursts could claim them all.
        assertEquals(2, RawHoldingQueue.capacity(poolFrames = 12, burstsPending = 2))
        assertEquals(2, RawHoldingQueue.capacity(poolFrames = 4, burstsPending = 3))
        // A one-buffer pool can hold one; an empty pool none.
        assertEquals(1, RawHoldingQueue.capacity(poolFrames = 1, burstsPending = 0))
        assertEquals(0, RawHoldingQueue.capacity(poolFrames = 0, burstsPending = 0))
        assertEquals(0, RawHoldingQueue.capacity(poolFrames = -3, burstsPending = 0))
        // Cap 16 (each held shot also costs a render later).
        assertEquals(RawHoldingQueue.MAX_SINGLES, RawHoldingQueue.capacity(poolFrames = 40, burstsPending = 0))
        assertEquals(16, RawHoldingQueue.capacity(poolFrames = 40, burstsPending = 1))
        // A negative burst count is treated as none.
        assertEquals(10, RawHoldingQueue.capacity(poolFrames = 10, burstsPending = -1))
    }

    @Test
    fun fifteenRapidTapsFitTheBaselinePool() {
        // The baseline device profile's pool (26 frames) with nothing pending holds 16 singles: 15 taps never fall back.
        val cap = RawHoldingQueue.capacity(poolFrames = 26, burstsPending = 0)
        assertTrue(cap >= RapidFire.GATE_TAPS)
        for (held in 0 until RapidFire.GATE_TAPS) {
            assertEquals(ShotRouter.Path.SINGLE_RAW, ShotRouter.route(true, held, 26 - held, cap).first)
        }
    }

    // ---------------------------------------------------------------- WorkerGovernor

    @Test
    fun governorThrottlesOnlyWhenTheViewfinderIsBusy() {
        val loaded = WorkerGovernor.budget(viewfinderActive = true, processing = 1, capturing = 1, maxThreads = 8)
        assertEquals(WorkerBudget(WorkerGovernor.LOADED_THREADS, WorkerGovernor.LOADED_NICE, loaded = true), loaded)
        assertEquals(WorkerBudget(2, 10, true), WorkerGovernor.budget(true, processing = 2, capturing = 0, maxThreads = 8))
        assertEquals(WorkerBudget(2, 10, true), WorkerGovernor.budget(true, processing = 0, capturing = 2, maxThreads = 8))
        // A lone shot: every thread at the idle nice (the unhurried save time does not change).
        assertEquals(WorkerBudget(8, WorkerGovernor.IDLE_NICE, loaded = false), WorkerGovernor.budget(true, 1, 0, 8))
        assertEquals(WorkerBudget(8, 5, false), WorkerGovernor.budget(true, 0, 0, 8))
        // Viewfinder hidden: no preview to protect, full speed however deep the backlog.
        assertEquals(WorkerBudget(8, 5, false), WorkerGovernor.budget(false, 10, 4, 8))
    }

    @Test
    fun governorNeverAsksForMoreThreadsThanExistOrFewerThanOne() {
        assertEquals(1, WorkerGovernor.budget(true, 3, 3, maxThreads = 1).threads)
        assertEquals(1, WorkerGovernor.budget(false, 0, 0, maxThreads = 0).threads)
        assertEquals(1, WorkerGovernor.budget(true, 5, 0, maxThreads = -2).threads)
        assertTrue(WorkerGovernor.LOADED_NICE > WorkerGovernor.IDLE_NICE)
        assertTrue(WorkerGovernor.IDLE_NICE > 0) // workers always yield to the default-priority camera thread
    }

    @Test
    fun cappedThreadsHonoursTheLimit() {
        assertEquals(8, WorkerGovernor.cappedThreads(cores = 8, cap = 8, limit = null))
        assertEquals(8, WorkerGovernor.cappedThreads(cores = 12, cap = 8, limit = null))
        assertEquals(2, WorkerGovernor.cappedThreads(cores = 8, cap = 8, limit = 2))
        assertEquals(4, WorkerGovernor.cappedThreads(cores = 4, cap = 8, limit = 6))
        assertEquals(1, WorkerGovernor.cappedThreads(cores = 0, cap = 8, limit = null))
        assertEquals(1, WorkerGovernor.cappedThreads(cores = 8, cap = 8, limit = 0))
    }

    // ---------------------------------------------------------------- RapidFire

    private fun RapidFire.taps(n: Int, path: String = BurstShot.PATH_SINGLE, why: String? = null, waitMs: Long = 40): RapidFire {
        var r = this
        repeat(n) { r = r.shot(path, why, waitMs) }
        return r
    }

    @Test
    fun gatesAreNullUntilARunOfFifteen() {
        var r = RapidFire().queue(1).taps(14)
        assertNull(r.noCameraJpegGate)
        assertNull(r.previewFpsGate)
        assertEquals(14, r.current.shots)
        r = r.taps(1)
        // The run in progress counts as soon as it has 15 shots.
        assertEquals(true, r.noCameraJpegGate)
        assertNull(r.previewFpsGate) // no preview reading during its drain yet
        r = r.preview(30.0, null)
        assertEquals(true, r.previewFpsGate)
    }

    @Test
    fun aCameraJpegInAFifteenTapRunFailsTheGateWithItsReason() {
        val why = ShotRouter.whyQueueFull(16)
        val r = RapidFire().queue(3).taps(14).taps(1, BurstShot.PATH_JPEG, why)
        assertEquals(false, r.noCameraJpegGate)
        assertEquals(1, r.cameraJpegFallbacks)
        assertEquals(mapOf(why to 1), r.jpegReasons)
        assertEquals(14, r.rawSingleShots)
        @Suppress("UNCHECKED_CAST")
        val gate = r.toMap()["gate"] as Map<String, Any?>
        assertEquals(false, gate["no_camera_jpeg_in_15_taps"])
    }

    @Test
    fun aJpegInAShortRunDoesNotCountAgainstTheGate() {
        var r = RapidFire().queue(1).taps(3, BurstShot.PATH_JPEG, ShotRouter.WHY_NO_BUFFER).queue(0)
        r = r.queue(1).taps(15).queue(0)
        assertEquals(2, r.runs)
        assertEquals(true, r.noCameraJpegGate)
        assertEquals(3, r.cameraJpegFallbacks) // still in the session totals
    }

    @Test
    fun aNewRunStartsOnlyWhenTheQueueGoesBusyAgain() {
        var r = RapidFire().queue(1).queue(2).queue(3).taps(5)
        assertEquals(1, r.runs)
        assertEquals(3, r.current.maxQueueDepth)
        r = r.queue(0)
        assertFalse(r.busy)
        r = r.taps(2) // saved after the drain: still the run that took them
        assertEquals(7, r.current.shots)
        r = r.queue(1)
        assertEquals(2, r.runs)
        assertEquals(0, r.current.shots)
        assertEquals(7, r.largest!!.shots)
    }

    @Test
    fun previewCountsOnlyWhileTheQueueDrains() {
        var r = RapidFire().preview(20.0, 0)
        assertNull(r.fpsMinUnderLoad)
        r = r.preview(null, 5) // idle drops are not load drops
        assertEquals(0, r.droppedUnderLoad)
        r = r.queue(1).preview(29.8, 7).preview(30.0, null)
        assertEquals(29.8, r.fpsMinUnderLoad!!, 1e-9)
        assertEquals(2, r.droppedUnderLoad)
        assertEquals(2, r.current.fpsSamples)
        // A counter reset (new stream) is not a negative drop.
        r = r.preview(null, 1)
        assertEquals(2, r.droppedUnderLoad)
        r = r.preview(null, 4)
        assertEquals(5, r.droppedUnderLoad)
        r = r.queue(0).preview(10.0, 50)
        assertEquals(29.8, r.fpsMinUnderLoad!!, 1e-9)
        assertEquals(5, r.droppedUnderLoad)
    }

    @Test
    fun previewGateFailsBelowTwentyNinePointFive() {
        var r = RapidFire().queue(2).taps(15).preview(29.4, null)
        assertEquals(false, r.previewFpsGate)
        r = RapidFire().queue(2).taps(15).preview(RapidFire.GATE_FPS_MIN, null)
        assertEquals(true, r.previewFpsGate)
        // The worst of several 15+ runs decides.
        r = RapidFire().queue(1).taps(15).preview(30.0, null).queue(0).queue(1).taps(16).preview(29.0, null).queue(0)
        assertEquals(false, r.previewFpsGate)
        assertEquals(2, r.withGateRuns())
    }

    private fun RapidFire.withGateRuns(): Int = toMap()["gate_runs_15_plus"] as Int

    @Test
    fun saveWaitAndPathsAreTallied() {
        val r = RapidFire().queue(1)
            .shot(BurstShot.PATH_BURST, null, 120)
            .shot(BurstShot.PATH_SINGLE, null, 900)
            .shot(BurstShot.PATH_SINGLE, null, null)
        assertEquals(900L, r.maxSaveWaitMs)
        assertEquals(900L, r.current.maxSaveWaitMs)
        assertEquals(1, r.current.bursts)
        assertEquals(2, r.current.rawSingles)
        assertEquals(0, r.cameraJpegFallbacks)
    }

    @Test
    fun reportCarriesTheSectionsTheDeviceChecklistReads() {
        val m = RapidFire().queue(1).taps(15).preview(30.0, 2).toMap()
        listOf(
            "camera_jpeg_fallbacks", "camera_jpeg_reasons", "raw_single_shots", "max_save_wait_ms",
            "preview_fps_min_under_load", "preview_dropped_under_load", "current_run", "largest_run", "gate"
        ).forEach { assertTrue(it, m.containsKey(it)) }
        @Suppress("UNCHECKED_CAST")
        val run = m["current_run"] as Map<String, Any?>
        assertEquals(15, run["shots"])
        assertEquals(30.0, run["preview_fps_min_during_drain"])
    }

    @Test
    fun burstReportFeedsRapidFireAndMirrorsItsGates() {
        val single = BurstShot(plan = BurstPlan(frames = 0, singleReason = "presses waiting"), stopMm = 24, saveWaitMs = 300)
        val jpeg = BurstShot(
            plan = BurstPlan(frames = 0, singleReason = "presses waiting; ${ShotRouter.WHY_LOW_MEMORY}"),
            stopMm = 24,
            path = BurstShot.PATH_JPEG
        )
        var b = BurstReport().queue(1)
        repeat(14) { b = b.record(single, 1000, true) }
        b = b.record(jpeg, 1000, true).preview(30.0, null)
        assertEquals(1, b.rapidFire.cameraJpegFallbacks)
        assertEquals(mapOf(ShotRouter.WHY_LOW_MEMORY to 1), b.rapidFire.jpegReasons)
        assertEquals(300L, b.rapidFire.maxSaveWaitMs)
        @Suppress("UNCHECKED_CAST")
        val gate = b.toMap()["gate"] as Map<String, Any?>
        assertEquals(false, gate["no_camera_jpeg_in_15_taps"])
        assertEquals(true, gate["preview_fps_min_ge_29_5_under_load"])
        assertTrue(b.toMap().containsKey("rapid_fire"))
    }

    // ---------------------------------------------------------------- B23b.1

    @Test
    fun latchKeepsTheWholeDrainLoadedButNotALoneShot() {
        val latch = GovernorLatch()
        // A lone shot: backlog one, never two: not latched, every thread.
        assertFalse(latch.update(viewfinderActive = true, backlog = 1))
        assertFalse(WorkerGovernor.budget(true, 1, 0, 8, latch.latched).loaded)
        // A rapid-fire run: latched at two, stays loaded down to one, released at zero.
        assertTrue(latch.update(true, 3))
        assertTrue(latch.update(true, 1))
        assertEquals(WorkerBudget(2, WorkerGovernor.LOADED_NICE, true), WorkerGovernor.budget(true, 1, 0, 8, latch.latched))
        assertFalse(latch.update(true, 0))
        assertFalse(latch.update(true, 1))
        // Leaving the viewfinder releases it, and the budget is idle without the viewfinder anyway.
        assertTrue(latch.update(true, 2))
        assertFalse(latch.update(false, 2))
        assertFalse(WorkerGovernor.budget(false, 1, 0, 8, latched = true).loaded)
    }

    @Test
    fun tailRunsNicerThanAddsAndStoreOnlyUnderLoad() {
        val idle = WorkerGovernor.budget(true, 0, 0, 8)
        val loaded = WorkerGovernor.budget(true, 2, 1, 8)
        assertEquals(WorkerGovernor.IDLE_NICE + WorkerGovernor.TAIL_NICE_STEP, WorkerGovernor.tailNice(idle))
        assertEquals(WorkerGovernor.LOADED_NICE + WorkerGovernor.TAIL_NICE_STEP, WorkerGovernor.tailNice(loaded))
        assertTrue(WorkerGovernor.tailNice(loaded) > loaded.nice)
        assertEquals(idle.nice, WorkerGovernor.storeNice(idle))
        assertEquals(WorkerGovernor.LOADED_NICE + WorkerGovernor.STORE_NICE_STEP, WorkerGovernor.storeNice(loaded))
        assertEquals(WorkerGovernor.MAX_NICE, WorkerGovernor.tailNice(WorkerBudget(2, 18, true)))
    }

    @Test
    fun loadReadingsCountPausesApartFromFps() {
        var r = RapidFire().load(fps = null, droppedTotal = 0, pausesTotal = 2, pauseMsTotal = 500.0, warmupTotal = 3)
        assertEquals(0, r.pausesUnderLoad) // idle pauses are not load pauses
        r = r.queue(2).load(29.9, 0, 4, 1100.0, 7)
        assertEquals(2, r.pausesUnderLoad)
        assertEquals(600.0, r.pauseMsUnderLoad, 1e-9)
        assertEquals(4, r.warmupFramesUnderLoad)
        assertEquals(29.9, r.fpsMinUnderLoad!!, 1e-9)
        assertEquals(2, r.current.pauses)
        // A null fps (window too short after a pause) is not a reading; a real drop still counts.
        r = r.load(null, 1, 5, 1400.0, 8)
        assertEquals(29.9, r.fpsMinUnderLoad!!, 1e-9)
        assertEquals(1, r.droppedUnderLoad)
        assertEquals(3, r.pausesUnderLoad)
        // A meter restart (lower totals) adds nothing.
        r = r.load(30.0, 0, 0, 0.0, 0)
        assertEquals(3, r.pausesUnderLoad)
        val m = r.toMap()
        assertEquals(3, m["preview_pauses_under_load"])
        assertEquals(900L, m["preview_pause_ms_under_load"])
        assertEquals(RapidFire.FPS_METHOD, m["preview_fps_method"])
    }

    @Test
    fun nativeWorkerReadBackMapsByPosition() {
        assertNull(RapidFire.nativeWorkerMap(null))
        assertNull(RapidFire.nativeWorkerMap(intArrayOf(1, 2)))
        val m = RapidFire.nativeWorkerMap(intArrayOf(10, 13, 4, 120, 32, 10))!!
        assertEquals(10, m["pool_worker_nice_seen"])
        assertEquals(13, m["pool_last_job_nice"])
        assertEquals(4, m["tail_yields"])
        assertEquals(120, m["tail_yield_ms"])
        assertEquals(32, m["tail_slices"])
        assertEquals(10, m["render_thread_nice_seen"])
        assertEquals(m, BurstReport().nativeWorkers(intArrayOf(10, 13, 4, 120, 32, 10)).rapidFire.nativeWorkers)
    }
}
