package com.redline.camera.capture

import java.util.Collections
import java.util.IdentityHashMap
import java.util.TreeMap

/**
 * B22b: where one shot's time goes, from the press to the file. Every time is SystemClock.elapsedRealtime ms and is
 * reported as an offset from the press. Request index 0 is the camera-JPEG frame; 1..N are the burst's RAW frames.
 * Thread-safe: the camera thread marks capture events, the merge thread its merge steps, the save thread the save.
 */
class ShotTimeline(val pressMs: Long) {
    private val marks = LinkedHashMap<String, Long>()
    private val started = TreeMap<Int, Long>()
    private val sensorNs = TreeMap<Int, Long>()
    private val results = TreeMap<Int, Long>()
    private val rawArrivals = ArrayList<Long>()
    private val rawCopyMs = ArrayList<Long>()
    private val mergeAdds = ArrayList<Long>()

    /** The first time [name] happened (later marks of the same name are ignored). */
    @Synchronized
    fun mark(name: String, atMs: Long) {
        marks.putIfAbsent(name, atMs)
    }

    @Synchronized
    fun at(name: String): Long? = marks[name]

    @Synchronized
    fun started(index: Int, atMs: Long, sensorTimestampNs: Long) {
        started.putIfAbsent(index, atMs)
        sensorNs.putIfAbsent(index, sensorTimestampNs)
    }

    @Synchronized
    fun result(index: Int, atMs: Long) {
        results.putIfAbsent(index, atMs)
    }

    /** A RAW frame was copied out of the camera: arrived at [atMs], the copy took [copyMs]. */
    @Synchronized
    fun rawArrived(atMs: Long, copyMs: Long) {
        rawArrivals += atMs
        rawCopyMs += copyMs
    }

    @Synchronized
    fun mergeAdded(atMs: Long) {
        mergeAdds += atMs
    }

    /** Press to the shutter being free for the next shot, ms; null before then. */
    @Synchronized
    fun releaseMs(): Long? = marks[RELEASED]?.let { it - pressMs }

    @Synchronized
    fun toMap(): Map<String, Any?> {
        fun rel(v: Long?) = v?.let { it - pressMs }
        val rawFrames = sensorNs.filterKeys { it > 0 }.values.toList()
        val sensorGaps = rawFrames.zipWithNext { a, b -> r1((b - a) / 1e6) }
        val lastRaw = rawFrames.lastOrNull()
        val jpegNs = sensorNs[0]
        val firstRaw = rawArrivals.minOrNull()
        val lastRawIn = rawArrivals.maxOrNull()
        return linkedMapOf(
            "marks_ms" to marks.mapValues { it.value - pressMs },
            "capture_started_ms" to started.entries.associate { "${it.key}" to it.value - pressMs },
            "results_ms" to results.entries.associate { "${it.key}" to it.value - pressMs },
            "raw_arrival_ms" to rawArrivals.map { it - pressMs },
            "raw_copy_ms" to rawCopyMs.toList(),
            "merge_add_done_ms" to mergeAdds.map { it - pressMs },
            "sensor_frame_gaps_ms" to sensorGaps,
            "jpeg_frame_vs_last_raw_sensor_ms" to if (jpegNs != null && lastRaw != null) r1((jpegNs - lastRaw) / 1e6) else null,
            "phases_ms" to linkedMapOf(
                "press_to_submit" to rel(marks[SUBMIT]),
                "submit_to_first_started" to marks[SUBMIT]?.let { s -> started.values.minOrNull()?.let { it - s } },
                "submit_to_first_raw" to marks[SUBMIT]?.let { s -> firstRaw?.let { it - s } },
                "first_to_last_raw" to if (firstRaw != null && lastRawIn != null) lastRawIn - firstRaw else null,
                "last_raw_to_release" to if (lastRawIn != null) marks[RELEASED]?.let { it - lastRawIn } else null,
                "press_to_release" to rel(marks[RELEASED]),
                "release_to_jpeg" to marks[RELEASED]?.let { r -> marks[JPEG]?.let { it - r } },
                "merge_start_after_first_raw" to if (firstRaw != null) marks[MERGE_START]?.let { it - firstRaw } else null,
                "save_queue_wait" to marks[SAVE_QUEUED]?.let { q -> marks[SAVE_START]?.let { it - q } },
                "render" to marks[SAVE_START]?.let { r -> marks[RENDER_DONE]?.let { it - r } },
                "store_wait" to marks[RENDER_DONE]?.let { r -> marks[STORE_START]?.let { it - r } },
                "store" to marks[STORE_START]?.let { r -> marks[SAVED]?.let { it - r } },
                "press_to_saved" to rel(marks[SAVED])
            )
        )
    }

    companion object {
        const val START = "start"
        const val SUBMIT = "submit"
        const val RELEASED = "released"
        const val JPEG = "jpeg"
        const val JPEG_RESULT = "jpeg_result"
        const val MERGE_START = "merge_start"
        const val MERGE_FINISH = "merge_finish_start"
        const val MERGE_DONE = "merge_done"
        const val SAVE_QUEUED = "save_queued"
        const val SAVE_START = "save_start"

        /** B23b: the render lane finished the shot; the store lane started on it. */
        const val RENDER_DONE = "render_done"
        const val STORE_START = "store_start"
        const val SAVED = "saved"
    }
}

/**
 * B22b: what the shutter does with a press while the camera is busy. A press is never turned away for being early:
 * it waits for the shot ahead of it (and for a session rebuild) and then runs. Only a press beyond
 * [MAX_WAITING_PRESSES] waiting is not taken, with the reason shown and recorded.
 */
object PressPolicy {
    const val MAX_WAITING_PRESSES = 4

    /** [outstanding]: presses already sent that are not yet out of the camera (the one capturing plus those waiting). */
    fun accepts(outstanding: Int): Boolean = waiting(outstanding) < MAX_WAITING_PRESSES

    /** Presses waiting behind the one capturing. */
    fun waiting(outstanding: Int): Int = (outstanding - 1).coerceAtLeast(0)

    fun refusedReason(): String = "queue full ($MAX_WAITING_PRESSES presses waiting)"

    /** The viewfinder's capture line: what the shutter is doing right now, so a press never looks ignored. */
    fun indicator(outstanding: Int, processing: Int): String? {
        val parts = ArrayList<String>()
        if (outstanding > 0) {
            val w = waiting(outstanding)
            parts += if (w > 0) "Capturing · $w queued" else "Capturing"
        }
        if (processing > 0) parts += "Processing $processing"
        return parts.joinToString(" · ").ifEmpty { null }
    }
}

/** B22b: why a press or a shot was dropped without counting as a capture failure. */
object DropReason {
    const val LEFT_VIEWFINDER = "left the viewfinder"
    const val SESSION_REBUILT = "camera session rebuilt"
    const val CAMERA_ERROR = "camera closed after an error"
}

/** B22b: the burst noise model taken before any burst result exists (so the merge can start on the first frame). */
object EarlyNoise {
    /**
     * Rescales a noise profile (S: shot-noise slope, O: read-noise floor, normalised units) from [fromIso] to [toIso]:
     * S grows with the analog gain and O with its square (Camera2 SENSOR_NOISE_PROFILE model). Null when either ISO is
     * missing or not positive.
     */
    fun scale(s: Double, o: Double, fromIso: Int?, toIso: Int?): Pair<Double, Double>? {
        if (fromIso == null || toIso == null || fromIso <= 0 || toIso <= 0) return null
        val g = toIso.toDouble() / fromIso
        return s * g to o * g * g
    }
}

/**
 * B22b: a burst can leave the camera (the shutter is free) once every RAW frame has either been copied out or been
 * reported lost; its capture results and the camera JPEG may still be on their way.
 */
object BurstRelease {
    fun allFramesIn(frames: Int, copied: Int, lost: Int): Boolean = frames <= 0 || copied + lost >= frames
}

/**
 * A set compared by identity. Pool buffers are ByteBuffers, whose equals/hashCode read their whole contents
 * (24 MB each): a HashSet of them hashes every byte and treats two frames with equal contents (two fresh zeroed
 * buffers) as one. B22b: every collection of frame buffers is identity-based.
 */
fun <T> identitySet(): MutableSet<T> = Collections.newSetFromMap(IdentityHashMap())

private fun r1(v: Double): Double = Math.round(v * 10.0) / 10.0
