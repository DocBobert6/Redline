package com.redline.camera.viewfinder

/** B23b.1: the first preview frames after one burst pause: their intervals and the sensor frame durations (ms). */
data class ResumeSample(val pauseMs: Double, val intervalsMs: List<Double>, val frameDurationsMs: List<Double>) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "pause_ms" to round1(pauseMs),
        "intervals_ms" to intervalsMs.map { round1(it) },
        "frame_durations_ms" to frameDurationsMs.map { round1(it) }
    )

    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0
}

/** B23b.1: one reading of [PreviewLoadMeter]; counts are cumulative over the meter's life. */
data class PreviewLoadReading(
    /** Rate over the counted frames in the last window; null until the window holds [PreviewLoadMeter.MIN_WINDOW_FRAMES]. */
    val fps: Double?,
    val intervals: Int,
    val dropped: Int,
    val worstIntervalMs: Double,
    val pauses: Int,
    val pauseMs: Double,
    val warmupFrames: Int,
    val inFlightFrames: Int,
    val recentResumes: List<ResumeSample>
)

/**
 * B23b.1: preview fps under load, measured honestly. A burst's frames target the RAW stream only, so the preview pauses
 * for the burst by design: frames that arrive while a burst capture is in flight are not counted, the gap is recorded as
 * a pause (count and total ms), and after each pause the first frames are warm-up (excluded until one interval of at most
 * [FAST_FACTOR] x the frame time is seen, or [WARMUP_FRAMES] frames passed) because the pipeline restarts. Every other
 * frame counts, so a drop while no burst is in flight is a real drop: it lowers [PreviewLoadReading.fps] and is counted in
 * [PreviewLoadReading.dropped] (an interval over 1.5x the frame time, as [FrameStats]).
 */
class PreviewLoadMeter(val targetFps: Int, private val windowNs: Long = 1_000_000_000L) {
    private val expectedNs = 1_000_000_000L / targetFps.coerceAtLeast(1)
    private val capturing = mutableSetOf<Int>()
    private val window = ArrayDeque<Long>()
    private var lastSeenNs: Long? = null
    private var lastCountedNs: Long? = null
    private var pausePending = false
    private var warmLeft = 0
    private var resume: MutableList<Double>? = null
    private var resumeDurations: MutableList<Double>? = null
    private var resumePauseMs = 0.0
    private val resumes = ArrayDeque<ResumeSample>()

    var intervals = 0
        private set
    var dropped = 0
        private set
    var worstNs = 0L
        private set
    var pauses = 0
        private set
    var pauseNs = 0L
        private set
    var warmupFrames = 0
        private set
    var inFlightFrames = 0
        private set

    val capturingNow: Boolean get() = capturing.isNotEmpty()

    /** A burst capture [id] was submitted: the preview pauses until it ends. */
    fun captureStarted(id: Int) {
        capturing += id
        pausePending = true
    }

    /** Burst capture [id] ended (its last request completed or failed); idempotent. */
    fun captureEnded(id: Int) {
        capturing -= id
    }

    /**
     * A new repeating request: the next frame starts a new run (no interval to the old one). Counts are kept, and so are
     * the bursts in flight unless [clearCaptures] (a new session: nothing of the old one will complete).
     */
    fun restart(clearCaptures: Boolean = false) {
        if (clearCaptures) capturing.clear()
        window.clear()
        lastSeenNs = null
        lastCountedNs = null
        pausePending = false
        warmLeft = 0
        closeResume()
    }

    /** A preview frame (sensor timestamp, and the sensor frame duration from its result when known). */
    fun frame(timestampNs: Long, frameDurationNs: Long? = null) {
        val prevSeen = lastSeenNs
        if (prevSeen != null && timestampNs <= prevSeen) return
        lastSeenNs = timestampNs
        if (capturing.isNotEmpty()) {
            inFlightFrames++
            return
        }
        if (pausePending) {
            pausePending = false
            pauses++
            val gap = if (prevSeen != null) timestampNs - prevSeen else 0L
            pauseNs += gap
            closeResume()
            resume = mutableListOf()
            resumeDurations = mutableListOf()
            resumePauseMs = gap / 1e6
            frameDurationNs?.let { resumeDurations?.add(it / 1e6) }
            warmLeft = WARMUP_FRAMES - 1
            window.clear()
            lastCountedNs = timestampNs
            warmupFrames++
            return
        }
        val prev = lastCountedNs
        lastCountedNs = timestampNs
        if (prev == null) {
            window.clear()
            window.addLast(timestampNs)
            return
        }
        val dt = timestampNs - prev
        resume?.let { r ->
            if (r.size < RESUME_LOGGED) {
                r += dt / 1e6
                frameDurationNs?.let { resumeDurations?.add(it / 1e6) }
            } else {
                closeResume()
            }
        }
        if (warmLeft > 0) {
            if (dt * FAST_DEN <= expectedNs * FAST_NUM) {
                // The first normal interval: the pipeline is back; this frame counts.
                warmLeft = 0
                window.clear()
                window.addLast(prev)
            } else {
                warmLeft--
                warmupFrames++
                if (warmLeft == 0) {
                    window.clear()
                    window.addLast(timestampNs)
                }
                return
            }
        }
        count(timestampNs, dt)
    }

    private fun count(timestampNs: Long, dt: Long) {
        intervals++
        if (dt * 2 > expectedNs * 3) dropped++
        if (dt > worstNs) worstNs = dt
        window.addLast(timestampNs)
        while (window.size > 2 && timestampNs - window.first() > windowNs) window.removeFirst()
    }

    private fun closeResume() {
        val r = resume ?: return
        resumes.addLast(ResumeSample(resumePauseMs, r.toList(), resumeDurations?.toList().orEmpty()))
        while (resumes.size > RESUMES_KEPT) resumes.removeFirst()
        resume = null
        resumeDurations = null
    }

    /** Rate over the counted frames in the window, once it holds [MIN_WINDOW_FRAMES]; null before. */
    fun fps(): Double? {
        if (window.size < MIN_WINDOW_FRAMES) return null
        val span = window.last() - window.first()
        return if (span <= 0) null else (window.size - 1) * 1e9 / span
    }

    fun reading(): PreviewLoadReading = PreviewLoadReading(
        fps = fps(),
        intervals = intervals,
        dropped = dropped,
        worstIntervalMs = worstNs / 1e6,
        pauses = pauses,
        pauseMs = pauseNs / 1e6,
        warmupFrames = warmupFrames,
        inFlightFrames = inFlightFrames,
        recentResumes = resumes.toList() + listOfNotNull(
            resume?.let { ResumeSample(resumePauseMs, it.toList(), resumeDurations?.toList().orEmpty()) }
        )
    )

    companion object {
        /** Warm-up frames after a pause at most (the first frame after the pause is one of them). */
        const val WARMUP_FRAMES = 3

        /** An interval of at most 1.15x the frame time (38.3 ms at 30 fps; a 24 fps frame, 41.7 ms, is not one) ends warm-up. */
        private const val FAST_NUM = 23L
        private const val FAST_DEN = 20L
        const val FAST_FACTOR = 1.15

        /** Frames the fps window needs (8 intervals, about 0.27 s at 30 fps) before it gives a reading. */
        const val MIN_WINDOW_FRAMES = 9
        private const val RESUME_LOGGED = 4
        private const val RESUMES_KEPT = 6
    }
}
