package com.redline.camera.viewfinder

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import com.redline.camera.capability.CameraNames
import com.redline.camera.capability.CameraProfile
import com.redline.camera.capability.Dim
import com.redline.camera.capture.BurstCapture
import com.redline.camera.capture.BurstInputs
import com.redline.camera.capture.BurstPlan
import com.redline.camera.capture.BurstPlanner
import com.redline.camera.capture.BurstRelease
import com.redline.camera.capture.BurstReport
import com.redline.camera.capture.BurstShot
import com.redline.camera.capture.BurstStreaming
import com.redline.camera.capture.CaptureMath
import com.redline.camera.capture.DropReason
import com.redline.camera.capture.EarlyNoise
import com.redline.camera.capture.FramePool
import com.redline.camera.capture.MemoryBudget
import com.redline.camera.capture.PressPolicy
import com.redline.camera.capture.RawCopy
import com.redline.camera.capture.ShotResult
import com.redline.camera.capture.ShotRouter
import com.redline.camera.capture.ShotTimeline
import com.redline.camera.capture.StillSaver
import com.redline.camera.capture.StillShot
import com.redline.camera.capture.StreamingMerge
import com.redline.camera.capture.identitySet
import com.redline.camera.colour.ColourPipeline
import com.redline.camera.manual.ManualApplied
import com.redline.camera.manual.ManualCaps
import com.redline.camera.nativebridge.RedlineNative
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Everything the viewfinder needs to open the default camera, read once from the cached profile plus a
 * few static characteristics (no camera is opened, no permission needed).
 */
data class ViewfinderSetup(
    val cameraId: String,
    val previewSize: Dim,
    val aeFpsRange: Pair<Int, Int>?,
    val sensorOrientation: Int,
    /** CONTROL_ZOOM_RATIO_RANGE on API 30+, else null (SCALER_CROP_REGION is used). */
    val zoomRatioRange: Pair<Float, Float>?,
    val maxDigitalZoom: Float,
    val activeArray: Rect?,
    val continuousAf: Boolean,
    val zoomPlan: ZoomPlan,
    /** Largest JPEG size (B13 stills); null when the profile lists none. */
    val jpegSize: Dim? = null,
    /** Largest RAW_SENSOR size when the camera has the RAW capability, else null. */
    val rawSize: Dim? = null,
    val frontFacing: Boolean = false,
    /** B14 manual-control ranges; null when the characteristics could not be read. */
    val manual: ManualCaps? = null
) {
    val zoomApi: String get() = if (zoomRatioRange != null) "CONTROL_ZOOM_RATIO" else "SCALER_CROP_REGION"

    companion object {
        /** Builds the setup for [camera] (the FeaturePolicy default camera); null when it has no usable preview size. */
        fun read(context: Context, camera: CameraProfile): ViewfinderSetup? {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val ch = runCatching { manager.getCameraCharacteristics(camera.id) }.getOrNull() ?: return null
            val profileSizes = camera.outputSizes["PRIVATE"].orEmpty().mapNotNull { Dim.parse(it) }
            val sizes = profileSizes.ifEmpty {
                runCatching {
                    ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        ?.getOutputSizes(SurfaceTexture::class.java)
                        ?.map { Dim(it.width, it.height) }
                }.getOrNull().orEmpty()
            }
            val size = PreviewSizes.choose(sizes) ?: return null
            val zoomRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching { ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) }.getOrNull()?.let { it.lower to it.upper }
            } else {
                null
            }
            val maxDigital = runCatching { ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) }.getOrNull() ?: 1f
            val plan = ZoomStops.plan(
                lenses = ZoomStops.lensesOf(camera),
                maxZoomRatio = zoomRange?.second ?: maxDigital,
                minZoomRatio = zoomRange?.first ?: 1f
            )
            return ViewfinderSetup(
                cameraId = camera.id,
                previewSize = size,
                aeFpsRange = PreviewFps.choose(camera.fpsRanges),
                sensorOrientation = runCatching { ch.get(CameraCharacteristics.SENSOR_ORIENTATION) }.getOrNull() ?: 90,
                zoomRatioRange = zoomRange,
                maxDigitalZoom = maxDigital,
                activeArray = runCatching { ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) }.getOrNull(),
                continuousAf = "CONTINUOUS_PICTURE" in camera.afModes,
                zoomPlan = plan,
                jpegSize = largest(camera.outputSizes["JPEG"]),
                rawSize = if (camera.has("RAW")) largest(camera.outputSizes["RAW_SENSOR"]) else null,
                frontFacing = camera.facing == "FRONT",
                manual = runCatching { manualCaps(ch, camera) }.getOrNull()
            )
        }

        private fun manualCaps(ch: CameraCharacteristics, camera: CameraProfile): ManualCaps {
            fun <T> get(key: CameraCharacteristics.Key<T>): T? = runCatching { ch.get(key) }.getOrNull()
            val calibration = get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)
            return ManualCaps.from(
                camera = camera,
                aeLockAvailable = get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true,
                awbLockAvailable = get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true,
                logicalMinFocus = get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE),
                logicalFocals = get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList().orEmpty(),
                logicalApertures = get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.toList().orEmpty(),
                focusCalibration = when (calibration) {
                    CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED -> "UNCALIBRATED"
                    CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE -> "APPROXIMATE"
                    CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED -> "CALIBRATED"
                    else -> null
                }
            )
        }

        private fun largest(sizes: List<String>?): Dim? = sizes.orEmpty().mapNotNull { Dim.parse(it) }.maxByOrNull { it.area }

        /** Centred crop of the active array for [ratio] (API 29 path). */
        fun cropRegion(active: Rect, ratio: Float): Rect {
            val r = ratio.coerceAtLeast(1f)
            val w = (active.width() / r).toInt()
            val h = (active.height() / r).toInt()
            val left = active.left + (active.width() - w) / 2
            val top = active.top + (active.height() - h) / 2
            return Rect(left, top, left + w, top + h)
        }
    }
}

/**
 * Camera2 preview for the viewfinder (B12). All camera work runs on its own background thread; every
 * public call just posts there. Open on resume, close on pause; [close] completes once the device is
 * really closed, so the self-test can take the camera without fighting the viewfinder for it.
 */
class ViewfinderController(
    context: Context,
    private val listener: Listener,
    version: String = "",
    private val colourPipeline: ColourPipeline? = null
) {
    interface Listener {
        /** First onCaptureCompleted of a session, as SystemClock.elapsedRealtime(). */
        fun onFirstCapture(elapsedRealtimeMs: Long)

        /** Rolling sensor fps, about twice a second. [warm] is false during the first seconds while AE settles. */
        fun onPreviewFps(fps: Double, warm: Boolean)

        /** Warm-up-excluded frame-interval statistics, reported alongside [onPreviewFps]. */
        fun onFrameStats(intervals: Int, dropped: Int, worstIntervalMs: Double)

        /** The repeating request is running with these settings. */
        fun onStreaming(setup: ViewfinderSetup, stabilization: Boolean)

        /** The camera could not be opened or was lost; the controller has already cleaned up. */
        fun onError(message: String)

        /**
         * The session could not be configured with the RAW stream; RAW (the DNG toggle and the B21 colour pipeline)
         * was turned off and the session rebuilt.
         */
        fun onRawUnavailable(reason: String) {}

        /** What the preview frames report (about twice a second): ISO, exposure and focus distance. */
        fun onPreviewValues(values: ResultValues) {}

        /** What a still's capture result reported, for the B14 range check. */
        fun onStillValues(values: ResultValues) {}

        /** B22: shots waiting on or running in the processing queue (the viewfinder's pending count). */
        fun onQueueDepth(depth: Int) {}

        /** B22: the frame-buffer pool was sized (frames it may hold, MB per frame). */
        fun onPool(frames: Int, frameMb: Double) {}

        /** B22a: the merge workers (native threads, their nice value), once at start-up. */
        fun onMergeWorker(threads: Int, nice: Int) {}
    }

    /**
     * Sensor values echoed by a capture result; null when the result does not carry the key. [manual] is the
     * B14 setting the request was built with (carried as the request tag), not whatever is current when the
     * result arrives, so results already in flight during a change are judged by their own request.
     */
    data class ResultValues(val iso: Int?, val exposureNs: Long?, val focusDiopters: Float?, val manual: ManualApplied?) {
        companion object {
            fun of(r: CaptureResult) = ResultValues(
                iso = r.get(CaptureResult.SENSOR_SENSITIVITY),
                exposureNs = r.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                focusDiopters = r.get(CaptureResult.LENS_FOCUS_DISTANCE),
                manual = (r.request.tag as? ManualApplied) ?: (r.request.tag as? BurstTag)?.manual
            )
        }
    }

    /** What the shutter needs to know about the selected zoom stop (B13). */
    data class StillRequest(
        val stopLabel: String,
        val stopTargetMm: Int,
        val trueEquivalentMm: Float?,
        val stopLongPx: Int,
        val deviceDegrees: Int,
        val keep: Boolean,
        /** B22 burst stress shot (files deleted like [keep] = false; counted in the burst report's stress run). */
        val stress: Boolean = false
    )

    /**
     * B22: the request tag of every still request: shot [shot], request [index] (0 = the camera-JPEG frame, 1..N = the
     * burst's RAW frames), and the B14 setting it was built with. B22b: results and images find their shot by it, since a
     * shot's JPEG may arrive while the next shot captures.
     */
    data class BurstTag(val shot: Int, val index: Int, val manual: ManualApplied?)

    private val manager = context.applicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("redline-viewfinder").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val saver = StillSaver(context, version, colourPipeline)

    /** B22: the background processing queue (single worker): render, encode, save. Mildly lowered priority. */
    private val saveExecutor = Executors.newSingleThreadExecutor(niceThreads("redline-save"))

    /**
     * B22a: the streaming merge (single thread; the native pool works under it): frames are merged as they arrive,
     * so the merge overlaps the capture. Same lowered priority, so preview rendering keeps the CPU it needs.
     */
    private val mergeExecutor = Executors.newSingleThreadExecutor(niceThreads("redline-merge"))
    private val mergeThreads = RedlineNative.burstThreads()

    init {
        mergeExecutor.execute {
            RedlineNative.setBurstWorkerNice(WORKER_NICE)
            listener.onMergeWorker(mergeThreads, WORKER_NICE)
        }
    }
    private val activityManager = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    // Camera-thread state.
    private var generation = 0
    private var opening = false
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var surface: Surface? = null
    private var setup: ViewfinderSetup? = null
    private var zoomRatio = 1f
    private var stabilization = false

    /** B14 manual settings, already clamped to the reported ranges; null = the B12/B13 auto behaviour. */
    private var manual: ManualApplied? = null
    private val closeWaiters = mutableListOf<CompletableFuture<Unit>>()
    private val fps = RollingFps()
    private var frameStats = FrameStats(PreviewFps.TARGET)
    private var firstCaptureSeen = false
    private var streamStartMs = 0L
    private var lastFpsReportMs = 0L

    // B13 still capture (camera thread). rawWanted = the DNG toggle; pipelineWanted = B21 renders stills from RAW.
    private var rawWanted = false
    private var pipelineWanted = false
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var sessionHasRaw = false
    private var characteristics: CameraCharacteristics? = null

    /** The shot capturing (it owns the RAW stream's images); null once its frames are out of the camera. */
    private var pending: PendingShot? = null

    /** B22b: shots started and not yet complete or failed (the one capturing, then those waiting on JPEG / merge). */
    private val active = ArrayList<PendingShot>()
    private var nextShotId = 0
    private val retired = mutableListOf<ImageReader>()

    // B22 burst capture and processing queue (camera thread).
    private var burstWanted = false
    private var pool: FramePool? = null
    private var rawMinFrameNs = 0L
    private var meteredIso: Int? = null
    private var meteredExposureNs: Long? = null
    private var meteredBoost: Int? = null

    /** B22b: the latest preview result: its levels and noise profile start a burst's merge before any burst result. */
    private var lastPreviewResult: TotalCaptureResult? = null

    /** B22b: a burst just ended: the next preview frame restarts the fps window (the burst's pause is not a drop). */
    private var previewResumePending = false

    /** B22b: why a press is dropped while the camera is closed. */
    private var closedReason = DropReason.LEFT_VIEWFINDER

    /** Shots out of the camera and not yet saved (merging or on the save queue): the "Processing N" count. */
    private var jobs = 0

    /** Shots started and not yet saved or failed: a press with any ahead of it is "queued", not unhurried. */
    private var inFlight = 0
    private var burstsPending = 0
    private var singlesPending = 0

    /** B22a: presses that arrived while the camera was busy; they start in order, never refused. */
    private val waitingPresses = ArrayDeque<QueuedPress>()
    private val trimPool = Runnable { if (inFlight == 0 && pending == null) pool?.trimIdle() }

    @Volatile private var motionRadPerSec: Double? = null

    private class QueuedPress(val request: StillRequest, val onCaptured: () -> Unit, val done: (ShotResult) -> Unit, val atMs: Long)

    private class PendingShot(
        val id: Int,
        val request: StillRequest,
        val jpegOrientation: Int,
        val wantRaw: Boolean,
        val saveDng: Boolean,
        val renderRaw: Boolean,
        val zoomRatio: Float,
        val shutterAtMs: Long,
        val onCaptured: () -> Unit,
        val done: (ShotResult) -> Unit,
        /** B22: the burst plan (frames > 0: RAW burst after the JPEG frame), or the single-frame record; null = burst off. */
        val plan: BurstPlan?,
        /** Single-frame path: the pool buffer for its RAW copy (empty for the camera-JPEG path). */
        val buffers: List<ByteBuffer>,
        /** B22a: the burst's streaming state, null for a single frame. */
        val run: BurstRun?,
        /** B22a: [BurstShot.PATH_SINGLE] or [BurstShot.PATH_JPEG] for a single frame. */
        val singlePath: String,
        /** Shots in flight when this one was pressed. */
        val queuedBehind: Int,
        /** B22b: press-to-file timings for the report. */
        val timeline: ShotTimeline
    ) {
        /** Sensor timestamp of the camera-JPEG frame (matches its JPEG image). */
        var timestamp: Long? = null
        var result: TotalCaptureResult? = null
        var jpeg: ByteArray? = null
        var raw: RawCopy? = null

        /** B22b: out of the camera (the shutter is free for the next shot); its JPEG and merge may still be on the way. */
        var released = false

        /** Complete (queued for saving) or failed. */
        var finished = false
        val burstResults = HashMap<Int, TotalCaptureResult>()
        val burstLost = HashSet<Int>()
    }

    /**
     * B22a: one burst from its first RAW frame to its merge. Camera-thread state; [merge] runs on the merge thread.
     * The burst owns at most [BurstPlan.residentFrames] pool buffers for its frames (plus the DNG's merge output);
     * a buffer the merger hands back is reused for a later frame, or returned to the pool once all frames are in.
     */
    private inner class BurstRun(val plan: BurstPlan, slots: List<ByteBuffer>, val dngOut: ByteBuffer?) {
        val merge = StreamingMerge(mergeThreads)
        val free = ArrayDeque(slots)

        // B22b: identity sets only. ByteBuffer equality compares the 24 MB contents (and hashes every byte).
        val owned: MutableSet<ByteBuffer> = identitySet<ByteBuffer>().also { set ->
            set.addAll(slots)
            dngOut?.let { set += it }
        }
        val copies = ArrayList<RawCopy>()
        val released = HashSet<Int>()
        val waiting = ArrayList<Int>()
        var started = false
        var captureDone = false
        var closed = false
        var finishWanted = false
        var finishQueued = false
        var mergeDone = false
        var mergeStats: RedlineNative.MergeStats? = null
        var mergeTailMs = 0L
        var lastFrameAtMs = 0L
        var captureMs = 0L
        var mergedAtCapture = 0
        var maxBacklog = 0

        /** Most frames copied and not yet handed back by the merger at once (pool buffers holding frames). */
        var maxResident = 0
    }

    fun open(
        setup: ViewfinderSetup,
        texture: SurfaceTexture,
        zoomRatio: Float,
        stabilization: Boolean,
        raw: Boolean = false,
        pipeline: Boolean = false
    ) {
        handler.post {
            rawWanted = raw
            pipelineWanted = pipeline
            openOnThread(setup, texture, zoomRatio, stabilization)
        }
    }

    /**
     * DNG on/off. The RAW stream is in the session while the DNG toggle or the B21 colour pipeline wants it (with
     * the pipeline on, the default session is preview + JPEG + RAW, a verified combination).
     */
    fun setRaw(on: Boolean) {
        handler.post {
            if (rawWanted == on) return@post
            rawWanted = on
            syncRawStream()
        }
    }

    /** B21 colour pipeline on/off: stills are rendered from RAW (RAW is then always captured). */
    fun setPipeline(on: Boolean) {
        handler.post {
            if (pipelineWanted == on) return@post
            pipelineWanted = on
            syncRawStream()
        }
    }

    /** B22 burst merge on/off (the setting, its self-check and the B21 pipeline all on); takes effect at the next shot. */
    fun setBurst(on: Boolean) {
        handler.post { burstWanted = on }
    }

    /** B22: smoothed hand motion from the gyroscope (rad/s), read by the burst planner; null = no gyroscope. */
    fun setMotion(radPerSec: Double?) {
        motionRadPerSec = radPerSec
    }

    private fun sessionWantsRaw(): Boolean = (rawWanted || pipelineWanted) && setup?.rawSize != null

    /** Camera thread: rebuilds the session when the RAW stream's presence no longer matches what is wanted. */
    private fun syncRawStream() {
        // Rebuilt once no shot expects images from the current outputs; waiting presses run on the new session.
        maybeRebuild()
    }

    /**
     * Takes one still (JPEG, rendered from RAW while the B21 pipeline is on; plus DNG while the DNG toggle is on).
     * B22: with burst merge on it is a RAW burst, merged on its own thread while the frames arrive (B22a). [onCaptured]
     * runs (camera thread) as soon as the frames are out of the camera, so the next shot can start; [done] runs on the
     * processing thread once the files are written, or with a failure. B22a: a press is never refused: one that
     * arrives while a capture is in flight waits for it, and a busy queue sends the shot down a cheaper path (single
     * RAW frame, then the camera JPEG) with the reason recorded. B22b: [onCaptured] runs exactly once per press, as
     * soon as the shot's RAW frames are in (its JPEG may follow); a press also waits out a session rebuild, and one that
     * cannot run (camera closed, more than [PressPolicy.MAX_WAITING_PRESSES] waiting) ends with ShotResult.dropped.
     */
    fun capture(request: StillRequest, onCaptured: () -> Unit = {}, done: (ShotResult) -> Unit) {
        val at = SystemClock.elapsedRealtime()
        handler.post { captureOnThread(request, onCaptured, done, at) }
    }

    fun setZoom(ratio: Float) {
        handler.post {
            zoomRatio = ratio
            repeat()
        }
    }

    /** Applies [applied] (already clamped by ManualModel) to the repeating request and to every later still. */
    fun setManual(applied: ManualApplied?) {
        handler.post {
            if (manual != applied) {
                manual = applied
                repeat()
            }
        }
    }

    fun setStabilization(on: Boolean) {
        handler.post {
            if (stabilization != on) {
                stabilization = on
                frameStats = FrameStats(setup?.aeFpsRange?.second ?: PreviewFps.TARGET)
                streamStartMs = SystemClock.elapsedRealtime()
                repeat()
            }
        }
    }

    /** Closes the session and device; the future completes when the device reports closed (or nothing was open). */
    fun close(): CompletableFuture<Unit> {
        val f = CompletableFuture<Unit>()
        handler.post { closeOnThread(f) }
        return f
    }

    fun release() {
        close()
        thread.quitSafely()
        mergeExecutor.shutdown()
        saveExecutor.execute { pool?.trimIdle() }
        saveExecutor.shutdown()
    }

    @SuppressLint("MissingPermission")
    private fun openOnThread(setup: ViewfinderSetup, texture: SurfaceTexture, ratio: Float, stab: Boolean) {
        if (device != null || opening) return
        this.setup = setup
        zoomRatio = ratio
        stabilization = stab
        firstCaptureSeen = false
        fps.reset()
        frameStats = FrameStats(setup.aeFpsRange?.second ?: PreviewFps.TARGET)
        characteristics = runCatching { manager.getCameraCharacteristics(setup.cameraId) }.getOrNull()
        rawMinFrameNs = setup.rawSize?.let { size ->
            runCatching {
                characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputMinFrameDuration(ImageFormat.RAW_SENSOR, Size(size.width, size.height))
            }.getOrNull()
        } ?: 0L
        texture.setDefaultBufferSize(setup.previewSize.width, setup.previewSize.height)
        surface?.release()
        val target = Surface(texture)
        surface = target
        opening = true
        val gen = generation
        val callback = object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                opening = false
                if (gen != generation) {
                    camera.close()
                    return
                }
                device = camera
                createSession(camera, target, gen)
            }

            override fun onClosed(camera: CameraDevice) {
                finishClose()
            }

            override fun onDisconnected(camera: CameraDevice) {
                fail(camera, gen, "camera disconnected (in use by another app?)")
            }

            override fun onError(camera: CameraDevice, error: Int) {
                fail(camera, gen, "camera error $error")
            }
        }
        try {
            manager.openCamera(setup.cameraId, executor, callback)
        } catch (e: Exception) {
            opening = false
            Log.w(TAG, "openCamera failed", e)
            listener.onError("camera unavailable: ${e.javaClass.simpleName}")
            finishClose()
        }
    }

    private fun fail(camera: CameraDevice, gen: Int, message: String) {
        opening = false
        val current = gen == generation
        if (device == camera) {
            device = null
            session = null
        }
        closedReason = DropReason.CAMERA_ERROR
        dropWaitingPresses(DropReason.CAMERA_ERROR)
        camera.close()
        finishClose()
        if (current) listener.onError(message)
    }

    private fun createSession(camera: CameraDevice, target: Surface, gen: Int) {
        val su = setup
        closeReaders(DropReason.SESSION_REBUILT)
        val outputs = mutableListOf(OutputConfiguration(target))
        su?.jpegSize?.let { size ->
            val r = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
            r.setOnImageAvailableListener({ onJpeg(it) }, handler)
            jpegReader = r
            outputs += OutputConfiguration(r.surface)
        }
        val withRaw = sessionWantsRaw() && su?.rawSize != null
        if (withRaw) {
            val size = su!!.rawSize!!
            // B22: frames are copied into pool buffers on arrival and closed at once; a few spare images absorb a burst.
            val r = ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, RAW_MAX_IMAGES)
            r.setOnImageAvailableListener({ drainRaw() }, handler)
            rawReader = r
            outputs += OutputConfiguration(r.surface)
            ensurePool(size)
        }
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (gen != generation) {
                    s.close()
                    return
                }
                session = s
                sessionHasRaw = withRaw
                streamStartMs = SystemClock.elapsedRealtime()
                frameStats.breakRun()
                repeat()
                // B22b: presses that waited for this session (a rebuild for a toggle) run now.
                handler.post { pumpPresses() }
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                if (gen != generation) return
                if (withRaw) {
                    rawWanted = false
                    pipelineWanted = false
                    listener.onRawUnavailable("session with RAW_SENSOR ${su?.rawSize} could not be configured")
                    createSession(camera, target, gen)
                } else {
                    listener.onError("preview session configuration failed")
                }
            }
        }
        try {
            val config = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor, callback)
            camera.createCaptureSession(config)
        } catch (e: Exception) {
            Log.w(TAG, "createCaptureSession failed", e)
            listener.onError("preview session failed: ${e.javaClass.simpleName}")
        }
    }

    private fun rebuildSession(camera: CameraDevice, target: Surface) {
        runCatching { session?.close() }
        session = null
        createSession(camera, target, generation)
    }

    /** B22: the native frame pool for [size], its capacity from the memory the system reports right now. */
    private fun ensurePool(size: Dim) {
        val bytes = size.width.toLong() * size.height * 2
        val info = ActivityManager.MemoryInfo().also { runCatching { activityManager.getMemoryInfo(it) } }
        val frames = MemoryBudget.poolFrames(info.availMem, info.lowMemory, bytes)
        val p = pool
        if (p == null || p.frameBytes != bytes) {
            p?.trimIdle()
            pool = FramePool(bytes, frames, { RedlineNative.allocFrame(it) }, { RedlineNative.freeFrame(it) })
        } else {
            p.capacity = maxOf(frames, p.inUse())
        }
        listener.onPool(frames, bytes / 1_048_576.0)
    }

    /**
     * B22b: a press runs at once when the camera is free; otherwise it waits (for the shot ahead of it, or for a session
     * rebuild to finish) and runs as soon as it can. A press is dropped (not a capture failure) only when the camera is
     * closed, and refused only beyond [PressPolicy.MAX_WAITING_PRESSES] waiting.
     */
    private fun captureOnThread(request: StillRequest, onCaptured: () -> Unit, done: (ShotResult) -> Unit, pressedAtMs: Long) {
        val q = QueuedPress(request, onCaptured, done, pressedAtMs)
        if (device == null && !opening) {
            dropPress(q, closedReason)
            return
        }
        if (waitingPresses.isEmpty() && canStartNow()) {
            startPress(q)
            return
        }
        if (waitingPresses.size >= PressPolicy.MAX_WAITING_PRESSES) {
            dropPress(q, PressPolicy.refusedReason(), refused = true)
            return
        }
        waitingPresses.addLast(q)
    }

    /** The camera can take a new shot now: a configured session, nothing capturing, no session rebuild wanted. */
    private fun canStartNow(): Boolean =
        session != null && device != null && setup != null && surface != null && pending == null && !rebuildWanted()

    private fun rebuildWanted(): Boolean = session != null && sessionWantsRaw() != sessionHasRaw

    /** A shot still expects images or results from the current session's outputs (so its readers must stay open). */
    private fun outputsPending(): Boolean = pending != null || active.any { !it.finished && (it.jpeg == null || it.result == null) }

    private fun startPress(q: QueuedPress) {
        if (jpegReader == null) {
            q.onCaptured()
            saveExecutor.execute { q.done(ShotResult(ok = false, error = "no JPEG stream", kept = q.request.keep)) }
            return
        }
        startShot(q)
    }

    /** B22b: a press that will not run: the camera closed ([why] a [DropReason]) or the waiting queue is full ([refused]). */
    private fun dropPress(q: QueuedPress, why: String, refused: Boolean = false) {
        val waited = SystemClock.elapsedRealtime() - q.atMs
        q.onCaptured()
        saveExecutor.execute {
            q.done(
                ShotResult(
                    ok = false,
                    error = if (refused) "not taken: $why" else "dropped: $why",
                    dropped = why,
                    kept = q.request.keep,
                    shutterWaitMs = waited,
                    shutterBlocked = refused
                )
            )
        }
    }

    /** Starts the next waiting press when the camera can take it (after a shot leaves the camera or a rebuild). */
    private fun pumpPresses() {
        if (!canStartNow()) return
        val q = waitingPresses.removeFirstOrNull() ?: return
        startPress(q)
    }

    /** The burst plan for the next shot, or null when burst merge is off (the shot is then exactly B21). */
    private fun planShot(su: ViewfinderSetup): BurstPlan? {
        if (!burstWanted) return null
        val caps = su.manual
        val p = pool
        val single = { why: String -> BurstPlan(frames = 0, singleReason = why) }
        return when {
            !pipelineWanted -> single("colour pipeline off")
            !sessionHasRaw || rawReader == null || p == null -> single("no RAW stream in this session")
            caps == null || !caps.manualExposure -> single("camera has no manual exposure control")
            else -> {
                val m = manual
                BurstPlanner.plan(
                    BurstInputs(
                        enabled = true,
                        meteredIso = meteredIso,
                        meteredExposureNs = meteredExposureNs,
                        manualIso = if (m?.aeOff == true) m.stillIso else null,
                        manualExposureNs = if (m?.aeOff == true) m.stillExposureNs else null,
                        isoRange = caps.isoRange!!,
                        exposureRangeNs = caps.exposureRangeNs!!,
                        rawMinFrameDurationNs = rawMinFrameNs,
                        poolFreeFrames = p.available() - if (rawWanted) 1 else 0,
                        burstsQueued = burstsPending,
                        motionRadPerSec = motionRadPerSec,
                        pressesWaiting = waitingPresses.size
                    )
                )
            }
        }
    }

    private fun startShot(q: QueuedPress) {
        val s = session!!
        val cam = device!!
        val su = setup!!
        val target = surface!!
        val jpeg = jpegReader!!
        val request = q.request
        val nowMs = SystemClock.elapsedRealtime()
        val waitMs = nowMs - q.atMs
        val timeline = ShotTimeline(q.atMs).also { it.mark(ShotTimeline.START, nowMs) }
        handler.removeCallbacks(trimPool)
        val blocked = waitMs > BurstReport.SHUTTER_BLOCK_MS
        val doneWithWait: (
            ShotResult
        ) -> Unit = { r -> q.done(r.copy(shutterWaitMs = waitMs, shutterBlocked = r.shutterBlocked || blocked)) }
        var plan = planShot(su)
        var run: BurstRun? = null
        if (plan?.isBurst == true) {
            val dngSlot = if (rawWanted) 1 else 0
            val slots = pool!!.acquire(plan.residentFrames + dngSlot)
            if (slots.size < BurstPlanner.MIN_FRAMES + dngSlot) {
                pool!!.release(slots)
                plan = BurstPlan(frames = 0, singleReason = "not enough memory for ${BurstPlanner.MIN_FRAMES} frames", light = plan.light)
            } else {
                val resident = slots.size - dngSlot
                if (resident < plan.residentFrames) {
                    val n = minOf(plan.frames, BurstPlanner.streamCap(resident, plan.frames, plan.frameDurationNs))
                    plan = plan.copy(frames = n, residentFrames = resident, limits = plan.limits + "memory")
                }
                run = BurstRun(plan, slots.take(resident), if (dngSlot == 1) slots.last() else null)
            }
        }
        val burst = run != null
        // Not a burst: the B21 single RAW frame, or the camera JPEG when the single-frame queue is full or memory is short.
        var buffers: List<ByteBuffer> = emptyList()
        var singlePath = BurstShot.PATH_JPEG
        if (!burst) {
            val wantsRaw = sessionHasRaw && rawReader != null
            var (path, why) = ShotRouter.route(wantsRaw, singlesPending, pool?.available() ?: 0)
            if (path == ShotRouter.Path.SINGLE_RAW) {
                buffers = pool?.acquire(1).orEmpty()
                if (buffers.isEmpty()) {
                    path = ShotRouter.Path.CAMERA_JPEG
                    why = "no memory for a RAW copy: camera JPEG"
                }
            }
            if (path == ShotRouter.Path.SINGLE_RAW) singlePath = BurstShot.PATH_SINGLE
            if (why != null && plan != null) plan = plan.copy(singleReason = listOfNotNull(plan.singleReason, why).joinToString("; "))
        }
        val wantRaw = buffers.isNotEmpty()
        val orientation = CaptureMath.jpegOrientation(su.sensorOrientation, request.deviceDegrees, su.frontFacing)
        val shot = PendingShot(
            id = ++nextShotId,
            request = request,
            jpegOrientation = orientation,
            wantRaw = wantRaw,
            saveDng = (wantRaw || burst) && rawWanted,
            renderRaw = pipelineWanted && (wantRaw || burst),
            zoomRatio = zoomRatio,
            shutterAtMs = q.atMs,
            onCaptured = q.onCaptured,
            done = doneWithWait,
            plan = plan,
            buffers = buffers,
            run = run,
            singlePath = singlePath,
            queuedBehind = inFlight,
            timeline = timeline
        )
        inFlight++
        if (burst) burstsPending++
        if (wantRaw) singlesPending++
        try {
            val jpegFrame = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(target)
                addTarget(jpeg.surface)
                if (wantRaw) {
                    addTarget(rawReader!!.surface)
                    set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
                }
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                su.aeFpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(it.first, it.second)) }
                if (su.continuousAf) set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, stabilizationMode())
                set(CaptureRequest.JPEG_ORIENTATION, orientation)
                set(CaptureRequest.JPEG_QUALITY, StillSaver.JPEG_QUALITY.toByte())
                applyZoom(this, su)
                applyManual(this, preview = false)
                if (run != null) {
                    // B22b: the burst's JPEG frame comes after its RAW frames and holds the metered exposure with AE off, so
                    // no frame of the burst waits for the JPEG stream's stall or for an AE on/off switch.
                    set(CaptureRequest.CONTROL_ENABLE_ZSL, false)
                    if (manual?.aeOff != true) holdMeteredExposure(this)
                }
                setTag(BurstTag(shot.id, 0, manual))
            }.build()
            pending = shot
            active += shot
            if (run != null) {
                val requests = mutableListOf<CaptureRequest>()
                for (i in 1..run.plan.frames) requests += burstRequest(cam, su, run.plan, shot.id, i)
                requests += jpegFrame
                s.captureBurst(requests, stillCallback, handler)
            } else {
                s.capture(jpegFrame, stillCallback, handler)
            }
            timeline.mark(ShotTimeline.SUBMIT, SystemClock.elapsedRealtime())
            // A long manual exposure and the burst's sensor time get their own time on top of the usual budget.
            val timeout = SHOT_TIMEOUT_MS + (manual?.stillFrameDurationNs ?: 0L) / 1_000_000L + (plan?.captureMs ?: 0L)
            handler.postDelayed({ if (!shot.finished) failShot(shot, "timed out after $timeout ms") }, timeout)
        } catch (e: Exception) {
            Log.w(TAG, "still capture failed", e)
            failShot(shot, "capture: ${e.javaClass.simpleName}")
        }
    }

    /**
     * B22b: the burst's JPEG frame at the exposure auto-exposure metered on the preview (AE off), so it is exposed
     * exactly as an AE-on still would be (it is the B21 exposure target) without an AE switch inside the burst.
     */
    private fun holdMeteredExposure(b: CaptureRequest.Builder) {
        val iso = meteredIso ?: return
        val exposure = meteredExposureNs ?: return
        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
        b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
        b.set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(exposure, rawMinFrameNs))
        val key = CameraCharacteristics.CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE
        val range = runCatching { characteristics?.get(key) }.getOrNull() ?: return
        val boost = meteredBoost ?: return
        b.set(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, boost.coerceIn(range.lower, range.upper))
    }

    /**
     * B22 burst frame [index]: RAW only (the preview pauses for the burst rather than flashing darker), AE off at the
     * plan's ISO / shutter / frame duration, focus and white balance as the JPEG frame (continuous AF or the M focus).
     * B22b: zero-shutter-lag off, so every frame is a new exposure with these settings.
     */
    private fun burstRequest(cam: CameraDevice, su: ViewfinderSetup, p: BurstPlan, shotId: Int, index: Int): CaptureRequest =
        cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(rawReader!!.surface)
            set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            set(CaptureRequest.CONTROL_ENABLE_ZSL, false)
            set(CaptureRequest.SENSOR_SENSITIVITY, p.iso)
            set(CaptureRequest.SENSOR_EXPOSURE_TIME, p.exposureNs)
            set(CaptureRequest.SENSOR_FRAME_DURATION, p.frameDurationNs)
            if (su.continuousAf) set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, stabilizationMode())
            applyZoom(this, su)
            applyFocusAndWhiteBalance(this)
            setTag(BurstTag(shotId, index, manual))
        }.build()

    /** The shot (still in flight) and request index a still request belongs to. */
    private fun shotFor(request: CaptureRequest): Pair<PendingShot, Int>? {
        val tag = request.tag as? BurstTag ?: return null
        val p = active.firstOrNull { it.id == tag.shot } ?: return null
        return p to tag.index
    }

    private val stillCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureStarted(s: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
            val (p, index) = shotFor(request) ?: return
            p.timeline.started(index, SystemClock.elapsedRealtime(), timestamp)
            if (index == 0 && p.timestamp == null) p.timestamp = timestamp
        }

        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val (p, index) = shotFor(request) ?: return
            p.timeline.result(index, SystemClock.elapsedRealtime())
            if (index > 0) {
                p.burstResults[index] = result
                p.run?.let { startMerge(p, it) }
            } else {
                listener.onStillValues(ResultValues.of(result))
                p.result = result
                if (p.timestamp == null) p.timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
                endOfBurst(p)
            }
            progress(p)
        }

        override fun onCaptureFailed(s: CameraCaptureSession, request: CaptureRequest, failure: android.hardware.camera2.CaptureFailure) {
            val (p, index) = shotFor(request) ?: return
            if (index > 0) {
                // A lost burst frame only shortens the burst.
                p.burstLost += index
                progress(p)
            } else {
                endOfBurst(p)
                failShot(p, "capture failed (reason ${failure.reason})")
            }
        }

        override fun onCaptureBufferLost(s: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) {
            val (p, index) = shotFor(request) ?: return
            when {
                index > 0 && target == rawReader?.surface -> {
                    p.burstLost += index
                    progress(p)
                }
                index == 0 && target == jpegReader?.surface -> failShot(p, "camera JPEG buffer lost")
                index == 0 && p.wantRaw && target == rawReader?.surface -> failShot(p, "RAW buffer lost")
            }
        }
    }

    /** B22b: the burst's last request (its JPEG frame) is done: the preview resumes, and that gap is not a dropped frame. */
    private fun endOfBurst(p: PendingShot) {
        if (p.run == null) return
        previewResumePending = true
        frameStats.breakRun()
    }

    private fun onJpeg(reader: ImageReader) {
        val img = runCatching { reader.acquireNextImage() }.getOrNull() ?: return
        // B22b: the JPEG may arrive after its shot left the camera (the next one may be capturing): match by timestamp.
        val ts = img.timestamp
        val p = active.firstOrNull { !it.finished && it.jpeg == null && it.timestamp == ts }
            ?: active.firstOrNull { !it.finished && it.jpeg == null && it.timestamp == null }
            ?: active.firstOrNull { !it.finished && it.jpeg == null }
        if (p == null) {
            img.close()
            return
        }
        try {
            val buf = img.planes[0].buffer
            p.jpeg = ByteArray(buf.remaining()).also { buf.get(it) }
        } finally {
            img.close()
        }
        p.timeline.mark(ShotTimeline.JPEG, SystemClock.elapsedRealtime())
        progress(p)
    }

    /**
     * B22: every RAW frame is copied into a pool buffer and its Image closed straight away. B22a: a burst frame that
     * finds none of the burst's buffers free stays in the camera's queue until the merge hands one back (the plan
     * keeps that wait within [BurstPlanner.CAMERA_QUEUE_SLACK] frames). RAW images belong to the shot capturing.
     */
    private fun drainRaw() {
        val reader = rawReader ?: return
        while (true) {
            val p = pending
            val run = p?.run
            if (run != null && !run.captureDone && run.free.isEmpty()) return
            val img = runCatching { reader.acquireNextImage() }.getOrNull() ?: return
            try {
                if (p == null || p.finished) continue
                val plane = img.planes.firstOrNull() ?: continue
                if (plane.pixelStride != 2) continue
                if (run != null) {
                    if (run.captureDone || run.closed || run.copies.size >= run.plan.frames) continue
                    val t0 = SystemClock.elapsedRealtime()
                    val buf = run.free.removeFirst()
                    val copy = RawCopy.copy(plane.buffer, plane.rowStride, img.width, img.height, buf, img.timestamp)
                    val index = run.copies.size
                    run.copies += copy
                    p.timeline.rawArrived(t0, SystemClock.elapsedRealtime() - t0)
                    run.maxResident = maxOf(run.maxResident, run.copies.size - run.released.size)
                    if (run.started) submitFrame(p, run, index) else run.waiting += index
                    startMerge(p, run)
                    run.maxBacklog = maxOf(run.maxBacklog, run.copies.size - run.merge.added)
                    progress(p)
                } else if (p.wantRaw && p.raw == null) {
                    p.raw = RawCopy.copy(plane.buffer, plane.rowStride, img.width, img.height, p.buffers[0], img.timestamp)
                    progress(p)
                }
            } catch (e: Exception) {
                Log.w(TAG, "RAW copy failed", e)
            } finally {
                img.close()
            }
        }
    }

    /**
     * B22b: the black/white levels and noise model the merge starts with, before any burst result exists: the latest
     * preview frame's levels, and its noise profile rescaled from the preview ISO to the burst ISO. Null without one.
     */
    private fun earlyLevelsAndNoise(
        plan: BurstPlan,
        ch: CameraCharacteristics
    ): Pair<Pair<FloatArray, Float>?, Triple<Double, Double, String>>? {
        val r = lastPreviewResult ?: return null
        val levels = colourPipeline?.levels(r, ch) ?: return null
        val (s, o, source) = StillSaver.noiseModel(r)
        val scaled = EarlyNoise.scale(s, o, r.get(CaptureResult.SENSOR_SENSITIVITY), plan.iso) ?: return null
        return levels to Triple(scaled.first, scaled.second, "${source}_preview_scaled")
    }

    /**
     * B22b: starts the burst's streaming merge on its first RAW frame (levels and noise from the preview, see
     * [earlyLevelsAndNoise]), or failing that once a burst frame's result is in, so the merge runs during the capture.
     */
    private fun startMerge(p: PendingShot, run: BurstRun) {
        if (run.started || run.closed) return
        val first = run.copies.firstOrNull() ?: return
        val ch = characteristics ?: return
        val start = earlyLevelsAndNoise(run.plan, ch)
            ?: p.burstResults.values.firstOrNull()?.let { r -> colourPipeline?.levels(r, ch) to StillSaver.noiseModel(r) }
            ?: return
        run.started = true
        val (levels, noise) = start
        mergeExecutor.execute {
            p.timeline.mark(ShotTimeline.MERGE_START, SystemClock.elapsedRealtime())
            run.merge.start(levels, noise, first.width, first.height)
        }
        run.waiting.forEach { submitFrame(p, run, it) }
        run.waiting.clear()
        if (run.finishWanted) queueMergeFinish(p, run)
    }

    private fun submitFrame(p: PendingShot, run: BurstRun, index: Int) {
        val copy = run.copies[index]
        mergeExecutor.execute {
            val released = run.merge.add(copy)
            if (released != null) p.timeline.mergeAdded(SystemClock.elapsedRealtime())
            when {
                released == null -> handler.post { onMergeFailed(run) }
                released.isNotEmpty() -> handler.post { onReleased(run, released) }
            }
        }
    }

    /** The merge failed mid-burst: keep the earliest frame (rendered alone instead) and free the rest, so capture goes on. */
    private fun onMergeFailed(run: BurstRun) {
        if (run.closed) return
        val unreleased = run.copies.indices.filter { it !in run.released }
        if (unreleased.size > 1) onReleased(run, unreleased.drop(1).toIntArray())
    }

    /** Camera thread: frames the merger no longer needs; their buffers serve the burst's later frames, else the pool. */
    private fun onReleased(run: BurstRun, indices: IntArray) {
        if (run.closed) return
        for (i in indices) {
            if (!run.released.add(i)) continue
            val buf = run.copies[i].buffer
            if (!run.captureDone) {
                run.free.addLast(buf)
            } else if (run.owned.remove(buf)) {
                pool?.release(listOf(buf))
            }
        }
        drainRaw()
    }

    /**
     * B22b: moves a shot along. It leaves the camera (the shutter is free, [releaseCamera]) as soon as all its RAW
     * frames are in, and is complete (merged result queued for saving) once the merge, the camera JPEG and the JPEG
     * frame's result are all in; those may arrive while the next shot captures.
     */
    private fun progress(p: PendingShot) {
        if (p.finished) return
        if (!p.released && pending === p && releasable(p)) releaseCamera(p)
        if (p.finished || !p.released) return
        val result = p.result ?: return
        val jpeg = p.jpeg ?: return
        val ch = characteristics
        if (ch == null) {
            failShot(p, "no camera characteristics")
            return
        }
        val run = p.run
        if (run != null) {
            if (!run.mergeDone) return
            p.finished = true
            active.remove(p)
            completeBurst(p, run, result, jpeg, ch)
        } else {
            if (p.wantRaw && p.raw == null) return
            p.finished = true
            active.remove(p)
            queueSave(p, buildShot(p, result, jpeg, ch, burst = null, queuedAtMs = SystemClock.elapsedRealtime(), keep = p.buffers))
        }
        // Its outputs are done: a rebuild held for them can run, and retired readers can close.
        closeRetiredIfIdle()
        maybeRebuild()
    }

    /** Burst: every RAW frame copied or lost. Single frame: its result is in, and its RAW copy when it has one. */
    private fun releasable(p: PendingShot): Boolean {
        val run = p.run
        return if (run != null) {
            BurstRelease.allFramesIn(run.plan.frames, run.copies.size, p.burstLost.size)
        } else {
            p.result != null && (!p.wantRaw || p.raw != null)
        }
    }

    /** The shot is out of the camera: the shutter re-arms, the merge's tail starts, and the next waiting press runs. */
    private fun releaseCamera(p: PendingShot) {
        p.released = true
        if (pending === p) pending = null
        jobs++
        listener.onQueueDepth(jobs)
        val now = SystemClock.elapsedRealtime()
        p.timeline.mark(ShotTimeline.RELEASED, now)
        p.onCaptured()
        p.run?.let { run ->
            run.captureDone = true
            run.lastFrameAtMs = now
            run.captureMs = now - p.shutterAtMs
            run.mergedAtCapture = run.merge.added
            pool?.release(run.free.toList())
            run.owned.removeAll(run.free)
            run.free.clear()
            queueMergeFinish(p, run)
        }
        afterShot()
    }

    /**
     * Finishes the streaming merge on the merge thread (it has been merging since the first frame arrived): in place
     * over the reference frame, or into the DNG's output buffer when the DNG needs the reference untouched. Waits for the
     * merge to have started when it could not yet (no levels); a burst with no frame at all finishes as a failed merge.
     */
    private fun queueMergeFinish(p: PendingShot, run: BurstRun) {
        if (run.finishQueued || run.closed) return
        if (!run.started) {
            if (run.copies.isNotEmpty()) {
                run.finishWanted = true
                return
            }
            run.merge.fail("merge: no burst frame arrived")
        }
        run.finishQueued = true
        val out = run.dngOut
        val lastFrameAtMs = run.lastFrameAtMs
        val first = run.copies.firstOrNull()
        mergeExecutor.execute {
            p.timeline.mark(ShotTimeline.MERGE_FINISH, SystemClock.elapsedRealtime())
            val stats = if (first != null) run.merge.finish(out, first.rowStrideBytes) else null
            run.merge.close()
            val doneAtMs = SystemClock.elapsedRealtime()
            p.timeline.mark(ShotTimeline.MERGE_DONE, doneAtMs)
            handler.post {
                if (!run.closed) {
                    run.mergeStats = stats
                    run.mergeTailMs = doneAtMs - lastFrameAtMs
                    run.mergeDone = true
                    progress(p)
                }
            }
        }
    }

    /** Camera thread: the merge is done (or failed) and the JPEG is in. Keeps the buffers the save needs and queues it. */
    private fun completeBurst(p: PendingShot, run: BurstRun, result: TotalCaptureResult, jpeg: ByteArray, ch: CameraCharacteristics) {
        if (run.closed) return
        run.closed = true
        val stats = run.mergeStats
        fun resultFor(copy: RawCopy): TotalCaptureResult = p.burstResults.values.firstOrNull {
            it.get(CaptureResult.SENSOR_TIMESTAMP) == copy.timestampNs
        } ?: p.burstResults.values.firstOrNull() ?: result
        val keep = ArrayList<ByteBuffer>()
        var merged: RawCopy? = null
        var dngRaw: RawCopy? = null
        var fallbackRaw: RawCopy? = null
        var refResult = result
        val ref = stats?.reference?.let { run.copies.getOrNull(it) }
        if (stats != null && ref != null) {
            refResult = resultFor(ref)
            val out = run.dngOut
            if (out != null) {
                merged = RawCopy(out, ref.width, ref.height, ref.timestampNs)
                dngRaw = ref
                keep += out
            } else {
                merged = ref // merged in place
            }
            keep += ref.buffer
        } else {
            // The merge failed: the earliest frame still held is rendered alone (and is the DNG).
            val f = run.copies.indices.firstOrNull { it !in run.released }?.let { run.copies[it] }
            if (f != null) {
                fallbackRaw = f
                refResult = resultFor(f)
                if (p.saveDng) dngRaw = f
                keep += f.buffer
            }
        }
        val spare = run.owned.filter { b -> keep.none { it === b } }
        pool?.release(spare)
        run.owned.clear()
        val frameMb = (run.copies.firstOrNull()?.let { it.width.toLong() * it.height * 2 } ?: 0L) / 1_048_576.0
        val streaming = BurstStreaming(
            framesMergedDuringCapture = run.mergedAtCapture,
            maxBacklog = run.maxBacklog,
            maxResidentFrames = run.maxResident,
            maxHeldByMerger = stats?.maxHeldFrames ?: 0,
            threads = stats?.threads ?: mergeThreads,
            mergeTailMs = run.mergeTailMs,
            peakNativeMb = stats?.peakNativeMb ?: 0.0,
            peakResidentMb = (run.maxResident + if (run.dngOut != null) 1 else 0) * frameMb + (stats?.peakNativeMb ?: 0.0)
        )
        val capture = BurstCapture(
            plan = run.plan,
            framesCaptured = run.copies.size,
            merged = merged,
            mergedScale = run.merge.scale,
            stats = stats,
            referenceResult = refResult,
            dngRaw = dngRaw,
            fallbackRaw = fallbackRaw,
            fallbackResult = fallbackRaw?.let { refResult },
            fallbackReason = if (stats == null) run.merge.error ?: "merge: no frames merged" else null,
            noiseModel = run.merge.noiseModel,
            captureMs = run.captureMs,
            mergeTailMs = run.mergeTailMs,
            streaming = streaming,
            releaseMs = p.timeline.releaseMs()
        )
        queueSave(p, buildShot(p, result, jpeg, ch, capture, SystemClock.elapsedRealtime(), keep))
    }

    private fun buildShot(
        p: PendingShot,
        result: TotalCaptureResult,
        jpeg: ByteArray,
        ch: CameraCharacteristics,
        burst: BurstCapture?,
        queuedAtMs: Long,
        keep: List<ByteBuffer>
    ): StillShot {
        val r = p.request
        val plan = p.plan
        val currentPool = pool
        val hasRaw = p.raw != null || burst?.merged != null || burst?.fallbackRaw != null
        p.timeline.mark(ShotTimeline.SAVE_QUEUED, queuedAtMs)
        return StillShot(
            jpeg = jpeg,
            raw = p.raw,
            result = result,
            characteristics = ch,
            jpegOrientation = p.jpegOrientation,
            stopLabel = r.stopLabel,
            stopTargetMm = r.stopTargetMm,
            trueEquivalentMm = r.trueEquivalentMm,
            stopLongPx = r.stopLongPx,
            shutterAtMs = p.shutterAtMs,
            keep = r.keep,
            saveDng = p.saveDng && (p.raw != null || burst?.dngRaw != null),
            renderRaw = p.renderRaw && hasRaw,
            cameraId = setup?.cameraId ?: "",
            zoomRatio = p.zoomRatio,
            burst = burst,
            singlePlan = if (plan == null || plan.isBurst) null else plan,
            singlePath = p.singlePath,
            stress = r.stress,
            queuedBehind = p.queuedBehind,
            queuedAtMs = queuedAtMs,
            timeline = p.timeline,
            release = { currentPool?.release(keep) }
        )
    }

    private fun queueSave(p: PendingShot, shot: StillShot) {
        val wasBurst = p.run != null
        val wasSingle = p.wantRaw
        saveExecutor.execute {
            val out = saver.save(shot) { SystemClock.elapsedRealtime() }
            handler.post {
                jobs--
                inFlight--
                if (wasBurst) burstsPending--
                if (wasSingle) singlesPending--
                listener.onQueueDepth(jobs)
                if (inFlight == 0) handler.postDelayed(trimPool, POOL_TRIM_IDLE_MS)
            }
            p.done(out)
        }
    }

    /**
     * Ends a shot that cannot complete. [dropped] (a [DropReason]): the user left the viewfinder or the session went
     * away under it, so it is recorded as dropped, not as a capture failure.
     */
    private fun failShot(p: PendingShot, message: String, dropped: String? = null) {
        if (p.finished) return
        p.finished = true
        active.remove(p)
        if (pending === p) pending = null
        pool?.release(p.buffers)
        p.run?.let { run ->
            // Queued merge work finishes first (it still reads the frames); then every buffer goes back.
            run.closed = true
            run.captureDone = true
            val owned = run.owned.toList()
            run.owned.clear()
            mergeExecutor.execute {
                run.merge.fail(message)
                run.merge.close()
                handler.post { pool?.release(owned) }
            }
        }
        inFlight--
        if (p.run != null) burstsPending--
        if (p.wantRaw) singlesPending--
        if (p.released) {
            jobs--
            listener.onQueueDepth(jobs)
        }
        if (inFlight == 0) handler.postDelayed(trimPool, POOL_TRIM_IDLE_MS)
        val error = if (dropped != null) "dropped: $message" else message
        saveExecutor.execute {
            p.done(ShotResult(ok = false, error = error, dropped = dropped, dngWanted = p.saveDng, kept = p.request.keep))
        }
        if (!p.released) {
            p.released = true
            p.onCaptured()
        }
        afterShot()
    }

    /** After a shot leaves the camera or ends: a RAW / pipeline toggle held for it is applied, then the next press runs. */
    private fun afterShot() {
        closeRetiredIfIdle()
        maybeRebuild()
        handler.post { pumpPresses() }
    }

    /** Rebuilds the session for a RAW / pipeline toggle once no shot still expects images from its outputs. */
    private fun maybeRebuild() {
        if (!rebuildWanted() || outputsPending()) return
        val cam = device ?: return
        val target = surface ?: return
        rebuildSession(cam, target)
    }

    private fun stabilizationMode(): Int = if (stabilization && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
    } else {
        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
    }

    /**
     * Detaches the readers from the next session. B22: queued shots hold pool copies, not camera Images, so a
     * retired reader is closed as soon as no capture expects its images. B22b: a shot still expecting images from the
     * old session is dropped with [reason] (not a capture failure); waiting presses stay queued for the new session.
     */
    private fun closeReaders(reason: String) {
        // A shot only waiting for its merge needs nothing from the session and carries on.
        active.filter { it === pending || it.jpeg == null || it.result == null }
            .forEach { failShot(it, "session closed ($reason)", dropped = reason) }
        jpegReader?.let { retired += it }
        rawReader?.let { retired += it }
        jpegReader = null
        rawReader = null
        sessionHasRaw = false
        closeRetiredIfIdle()
    }

    /** B22b: presses still waiting when the camera closes are dropped with [reason] (not capture failures). */
    private fun dropWaitingPresses(reason: String) {
        while (true) dropPress(waitingPresses.removeFirstOrNull() ?: return, reason)
    }

    private fun closeRetiredIfIdle() {
        if (outputsPending()) return
        retired.forEach { runCatching { it.close() } }
        retired.clear()
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val now = SystemClock.elapsedRealtime()
            if (!firstCaptureSeen) {
                firstCaptureSeen = true
                listener.onFirstCapture(now)
            }
            lastPreviewResult = result
            if ((request.tag as? ManualApplied)?.aeOff != true) {
                // B22: the auto-exposure reading the burst planner under-exposes from (Av's EV is already in it).
                result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { meteredIso = it }
                result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { meteredExposureNs = it }
                meteredBoost = result.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST)
            }
            if (previewResumePending) {
                // B22b: the preview paused for a burst; that pause is neither a dropped frame nor a low fps reading.
                previewResumePending = false
                fps.reset()
                frameStats.breakRun()
            }
            val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
            val warm = now - streamStartMs >= WARMUP_MS
            if (warm) frameStats.add(ts) else frameStats.breakRun()
            val rate = fps.add(ts) ?: return
            if (now - lastFpsReportMs >= FPS_REPORT_MS) {
                lastFpsReportMs = now
                listener.onPreviewFps(rate, warm)
                listener.onFrameStats(frameStats.intervals, frameStats.dropped, frameStats.worstNs / 1e6)
                listener.onPreviewValues(ResultValues.of(result))
            }
        }
    }

    /** (Re)issues the repeating preview request with the current zoom and stabilization. */
    private fun repeat() {
        val s = session ?: return
        val cam = device ?: return
        val su = setup ?: return
        val target = surface ?: return
        try {
            val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(target)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                su.aeFpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(it.first, it.second)) }
                if (su.continuousAf) set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, stabilizationMode())
                applyZoom(this, su)
                applyManual(this, preview = true)
            }
            val targetFps = su.aeFpsRange?.second ?: PreviewFps.TARGET
            if (frameStats.intervals == 0) frameStats = FrameStats(targetFps)
            frameStats.breakRun()
            s.setRepeatingRequest(req.build(), captureCallback, handler)
            listener.onStreaming(su, stabilization && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        } catch (e: Exception) {
            Log.w(TAG, "setRepeatingRequest failed", e)
            listener.onError("preview request failed: ${e.javaClass.simpleName}")
        }
    }

    private fun applyZoom(b: CaptureRequest.Builder, su: ViewfinderSetup) {
        val range = su.zoomRatioRange
        if (range != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio.coerceIn(range.first, range.second))
        } else {
            val active = su.activeArray ?: return
            b.set(
                CaptureRequest.SCALER_CROP_REGION,
                ViewfinderSetup.cropRegion(active, zoomRatio.coerceIn(1f, maxOf(1f, su.maxDigitalZoom)))
            )
        }
    }

    /**
     * Writes the B14 settings. M: AE OFF with ISO, exposure and frame duration (the preview keeps its shutter at
     * 1/30 s or shorter, with ISO raised to match, so it stays at 30 fps; the still gets the full exposure). Av:
     * EV and AE lock on top of auto exposure. Manual focus, AWB preset and AWB lock in both. Nothing is written
     * that ManualModel did not clamp to a reported range.
     */
    private fun applyManual(b: CaptureRequest.Builder, preview: Boolean) {
        b.setTag(manual)
        val m = manual ?: return
        if (m.aeOff) {
            val iso = if (preview) m.previewIso else m.stillIso
            val exposure = if (preview) m.previewExposureNs else m.stillExposureNs
            val frame = if (preview) m.previewFrameDurationNs else m.stillFrameDurationNs
            if (iso != null && exposure != null && frame != null) {
                b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
                b.set(CaptureRequest.SENSOR_FRAME_DURATION, frame)
            }
        } else {
            if (m.evIndex != 0) b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, m.evIndex)
            if (m.aeLock) b.set(CaptureRequest.CONTROL_AE_LOCK, true)
        }
        applyFocusAndWhiteBalance(b)
    }

    /** The B14 manual focus, AWB preset and AWB lock (no exposure, no tag): shared by stills and B22 burst frames. */
    private fun applyFocusAndWhiteBalance(b: CaptureRequest.Builder) {
        val m = manual ?: return
        m.focusDiopters?.let {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, it)
        }
        awbModeInt(m.awbMode)?.let { if (it != CameraMetadata.CONTROL_AWB_MODE_AUTO) b.set(CaptureRequest.CONTROL_AWB_MODE, it) }
        if (m.awbLock) b.set(CaptureRequest.CONTROL_AWB_LOCK, true)
    }

    private fun awbModeInt(name: String): Int? = CameraNames.AWB_MODES.entries.firstOrNull { it.value == name }?.key

    private fun closeOnThread(f: CompletableFuture<Unit>) {
        generation++
        val cam = device
        runCatching { session?.close() }
        session = null
        closedReason = DropReason.LEFT_VIEWFINDER
        closeReaders(DropReason.LEFT_VIEWFINDER)
        dropWaitingPresses(DropReason.LEFT_VIEWFINDER)
        device = null
        if (cam == null && !opening) {
            surface?.release()
            surface = null
            f.complete(Unit)
            return
        }
        closeWaiters += f
        // A pending open is closed in onOpened (generation changed); onClosed then finishes the wait.
        cam?.close()
    }

    private fun finishClose() {
        if (device != null || opening) return
        surface?.release()
        surface = null
        closeWaiters.forEach { it.complete(Unit) }
        closeWaiters.clear()
    }

    companion object {
        private const val TAG = "RedlineViewfinder"
        private const val FPS_REPORT_MS = 500L
        private const val WARMUP_MS = 2_000L
        private const val SHOT_TIMEOUT_MS = 5_000L

        /**
         * B22a: nice value of the merge and save threads and the native merge workers: below the default so the
         * camera, the preview's GL thread and the UI win the CPU, but well short of THREAD_PRIORITY_BACKGROUND (10),
         * which Android may also confine to the little cores.
         */
        const val WORKER_NICE = 5

        private fun niceThreads(name: String) = java.util.concurrent.ThreadFactory { r ->
            Thread({
                runCatching { Process.setThreadPriority(WORKER_NICE) }
                r.run()
            }, name)
        }
        private const val RAW_MAX_IMAGES = 4

        /** Idle pool buffers are freed once the queue has been empty this long. */
        private const val POOL_TRIM_IDLE_MS = 15_000L
    }
}
