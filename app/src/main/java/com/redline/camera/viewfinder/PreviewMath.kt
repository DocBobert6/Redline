package com.redline.camera.viewfinder

import com.redline.camera.capability.Dim
import kotlin.math.abs

/**
 * The AE target fps range for the viewfinder. Left alone, AE may pick a range like [15,30] and drop
 * to 15 fps in a dim room (seen on the baseline device profile: 66 ms exposures). Preview must stay at
 * 30 fps (PLAN 12), so the range is chosen from the profile, never hard-coded:
 * [30,30], else [24,30], else the range ending at 30 with the highest floor, else the range containing
 * 30 with the highest floor, else null (leave AE's default; nothing in the profile can hold 30).
 */
object PreviewFps {
    const val TARGET = 30

    /**
     * B23b.1: the frame duration to request with a fixed AE range ([n, n]): 1/n s. Null for a variable range, which
     * leaves the frame duration to AE. (With AE on the HAL may ignore it; it is a hint that matches the range.)
     */
    fun fixedFrameDurationNs(range: Pair<Int, Int>?): Long? =
        range?.takeIf { it.first == it.second && it.first > 0 }?.let { 1_000_000_000L / it.first }

    fun choose(ranges: List<Pair<Int, Int>>): Pair<Int, Int>? {
        if ((TARGET to TARGET) in ranges) return TARGET to TARGET
        if ((24 to TARGET) in ranges) return 24 to TARGET
        ranges.filter { it.second == TARGET }.maxByOrNull { it.first }?.let { return it }
        return ranges.filter {
            it.first <= TARGET && it.second >= TARGET
        }.maxWithOrNull(compareBy<Pair<Int, Int>>({ it.first }, { -it.second }))
    }
}

/** Preview stream size: a 4:3 PRIVATE size around 1440p/1080p (cropped to 3:2 on screen). */
object PreviewSizes {
    val PREFERRED = Dim(1920, 1440)
    private const val MAX_WIDTH = 1920
    private const val MAX_HEIGHT = 1440

    fun isFourThree(d: Dim): Boolean = d.height > 0 && abs(d.width * 3 - d.height * 4) <= d.width / 100

    /**
     * 1920x1440 when offered; else the largest 4:3 size within 1920x1440; else the largest size within
     * 1920x1440; else the smallest size. Null only for an empty list.
     */
    fun choose(sizes: List<Dim>): Dim? {
        if (sizes.isEmpty()) return null
        if (PREFERRED in sizes) return PREFERRED
        val fits = sizes.filter { it.width <= MAX_WIDTH && it.height <= MAX_HEIGHT }
        return fits.filter { isFourThree(it) }.maxByOrNull { it.area }
            ?: fits.maxByOrNull { it.area }
            ?: sizes.minByOrNull { it.area }
    }
}

/**
 * TextureView transform (applied about the view centre as scale then rotate). The camera writes buffers
 * that SurfaceTexture rotates by the sensor orientation, so with an identity transform the picture is
 * upright in the device's natural orientation and stretched to the view. This undoes the stretch, rotates
 * by minus the display rotation, and scales to fill (centre crop) or fit.
 */
data class PreviewTransform(
    val scaleX: Float,
    val scaleY: Float,
    val rotationDeg: Float,
    val contentWidth: Float,
    val contentHeight: Float
) {
    companion object {
        /** Size of the picture once upright on screen, before scaling: buffer rotated by sensor and display rotation. */
        fun uprightSize(buffer: Dim, sensorOrientation: Int, displayRotation: Int): Dim {
            val natural = if (sensorOrientation % 180 != 0) Dim(buffer.height, buffer.width) else buffer
            return if (displayRotation % 180 != 0) Dim(natural.height, natural.width) else natural
        }

        fun compute(
            viewW: Float,
            viewH: Float,
            buffer: Dim,
            sensorOrientation: Int,
            displayRotation: Int,
            fill: Boolean = true
        ): PreviewTransform {
            if (viewW <= 0f || viewH <= 0f || buffer.width <= 0 || buffer.height <= 0) return PreviewTransform(1f, 1f, 0f, viewW, viewH)
            val upright = uprightSize(buffer, sensorOrientation, displayRotation)
            val sx = viewW / upright.width
            val sy = viewH / upright.height
            val s = if (fill) maxOf(sx, sy) else minOf(sx, sy)
            val w = upright.width * s
            val h = upright.height * s
            val sideways = displayRotation % 180 != 0
            // Pre-rotation extents: rotating by 90/270 swaps the axes.
            val preW = if (sideways) h else w
            val preH = if (sideways) w else h
            val rotation = ((360 - displayRotation % 360) % 360).toFloat()
            return PreviewTransform(preW / viewW, preH / viewH, rotation, w, h)
        }
    }
}

/** Frames per second over a sliding window of sensor timestamps (ns). */
class RollingFps(private val windowNs: Long = 1_000_000_000L) {
    private val stamps = ArrayDeque<Long>()

    /** Adds a frame; returns the current rate, or null until the window holds at least 3 frames. */
    fun add(timestampNs: Long): Double? {
        stamps.addLast(timestampNs)
        while (stamps.size > 2 && timestampNs - stamps.first() > windowNs) stamps.removeFirst()
        return current()
    }

    fun current(): Double? {
        if (stamps.size < 3) return null
        val span = stamps.last() - stamps.first()
        return if (span <= 0) null else (stamps.size - 1) * 1e9 / span
    }

    fun reset() = stamps.clear()
}

/**
 * Frame-interval statistics from sensor timestamps (ns). A frame counts as dropped when its interval is more than
 * 1.5x the expected interval (1 / target fps), which separates a real stutter from ordinary timestamp jitter.
 */
class FrameStats(targetFps: Int) {
    private val expectedNs = 1_000_000_000L / targetFps.coerceAtLeast(1)
    private var last: Long? = null

    var intervals = 0
        private set
    var dropped = 0
        private set
    var worstNs = 0L
        private set

    fun add(timestampNs: Long) {
        val prev = last
        last = timestampNs
        if (prev == null || timestampNs <= prev) return
        val dt = timestampNs - prev
        intervals++
        if (dt * 2 > expectedNs * 3) dropped++
        if (dt > worstNs) worstNs = dt
    }

    /** Starts a new run of intervals (new session or repeating request) without clearing the counts. */
    fun breakRun() {
        last = null
    }
}

/** What the viewfinder measured, for the overlay and the shared capability report ("viewfinder" section). */
data class ViewfinderMetrics(
    val firstFrameMs: Long? = null,
    val firstCaptureMs: Long? = null,
    val processToFirstFrameMs: Long? = null,
    val previewFps: Double? = null,
    val previewFpsMin: Double? = null,
    val previewSize: Dim? = null,
    val aeFpsRange: Pair<Int, Int>? = null,
    val previewStabilization: Boolean = false,
    val zoomApi: String? = null,
    val zoomStopMm: Int? = null,
    val zoomRatio: Float? = null,
    val profileCache: String? = null,
    val launch: String? = null,
    val permissionPrompted: Boolean = false,
    val status: String? = null,
    val framesCounted: Int? = null,
    val droppedFrames: Int? = null,
    val worstFrameIntervalMs: Double? = null,
    /** Set when the Info-screen test toggle overrides the policy's preview stabilization. */
    val stabilizationOverride: Boolean? = null
) {
    val fpsGatePassed: Boolean? get() = previewFpsMin?.let { it >= FPS_GATE - FPS_SLACK }
    val startGatePassed: Boolean? get() = firstFrameMs?.let { it < START_GATE_MS }

    fun overlayText(): String {
        val first = firstFrameMs?.let { "$it ms" } ?: "-"
        val fps = previewFps?.let { "%.1f".format(it) } ?: "-"
        val ae = aeFpsRange?.let { "[${it.first},${it.second}]" } ?: "auto"
        return "first frame $first · $fps fps · ${previewSize ?: "-"} · AE $ae"
    }

    fun toMap(stops: ZoomPlan?): Map<String, Any?> = linkedMapOf(
        "first_frame_ms" to firstFrameMs,
        "first_capture_ms" to firstCaptureMs,
        "process_to_first_frame_ms" to processToFirstFrameMs,
        "preview_fps" to previewFps?.let { round2(it) },
        "preview_fps_min" to previewFpsMin?.let { round2(it) },
        "preview_size" to previewSize?.toString(),
        "display_crop" to "3:2",
        "ae_fps_range" to aeFpsRange?.toList(),
        "preview_stabilization" to previewStabilization,
        "zoom_api" to zoomApi,
        "zoom_stop_mm" to zoomStopMm,
        "zoom_ratio" to zoomRatio?.let { round2(it.toDouble()) },
        "profile_cache" to profileCache,
        "launch" to launch,
        "permission_prompted" to permissionPrompted,
        "status" to status,
        "frames_counted" to framesCounted,
        "dropped_frames" to droppedFrames,
        "worst_frame_interval_ms" to worstFrameIntervalMs?.let { round2(it) },
        "stabilization_override" to stabilizationOverride,
        "gate" to linkedMapOf(
            "preview_fps_min_ge_30" to fpsGatePassed,
            "first_frame_lt_1000_ms" to startGatePassed
        ),
        "zoom_stops" to stops?.toMap()
    )

    companion object {
        const val FPS_GATE = 30.0

        /** Sensor timestamps jitter; 29.5 over a 1 s window is a 30 fps stream. */
        const val FPS_SLACK = 0.5
        const val START_GATE_MS = 1_000L

        private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
    }
}
