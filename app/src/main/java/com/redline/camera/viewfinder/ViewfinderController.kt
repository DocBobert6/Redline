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
import com.redline.camera.capture.BurstReport
import com.redline.camera.capture.CaptureMath
import com.redline.camera.capture.FramePool
import com.redline.camera.capture.MemoryBudget
import com.redline.camera.capture.RawCopy
import com.redline.camera.capture.ShotResult
import com.redline.camera.capture.StillSaver
import com.redline.camera.capture.StillShot
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
class ViewfinderController(context: Context, private val listener: Listener, version: String = "", colourPipeline: ColourPipeline? = null) {
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
                manual = r.request.tag as? ManualApplied
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

    /** B22: the request tag of burst frame [index] (1..N; frame 0 is the camera-JPEG frame, tagged with the manual setting). */
    data class BurstTag(val index: Int, val manual: ManualApplied?)

    private val manager = context.applicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("redline-viewfinder").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val saver = StillSaver(context, version, colourPipeline)

    /** B22: the background processing queue (single worker): merge, render, encode, save. */
    private val saveExecutor = Executors.newSingleThreadExecutor()
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
    private var pending: PendingShot? = null
    private val retired = mutableListOf<ImageReader>()

    // B22 burst capture and processing queue (camera thread).
    private var burstWanted = false
    private var pool: FramePool? = null
    private var rawMinFrameNs = 0L
    private var meteredIso: Int? = null
    private var meteredExposureNs: Long? = null
    private var jobs = 0
    private var burstsQueued = 0
    private var queuedPress: QueuedPress? = null
    private val trimPool = Runnable { if (jobs == 0 && pending == null) pool?.trimIdle() }

    @Volatile private var motionRadPerSec: Double? = null

    private class QueuedPress(val request: StillRequest, val onCaptured: () -> Unit, val done: (ShotResult) -> Unit, val atMs: Long)

    private class PendingShot(
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
        /** Pool buffers held by this shot: RAW copies first, then (burst) the merge output as the last one. */
        val buffers: List<ByteBuffer>
    ) {
        var timestamp: Long? = null
        var result: TotalCaptureResult? = null
        var jpeg: ByteArray? = null
        var raw: RawCopy? = null
        var finished = false
        val burstResults = HashMap<Int, TotalCaptureResult>()
        val burstLost = HashSet<Int>()
        val burstRaws = HashMap<Long, RawCopy>()
        var nextBuffer = 0
        val isBurst: Boolean get() = plan?.isBurst == true
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
        val cam = device ?: return
        val target = surface ?: return
        if (pending != null) return // rebuilt after the shot in flight
        if (sessionWantsRaw() == sessionHasRaw) return
        rebuildSession(cam, target)
    }

    /**
     * Takes one still (JPEG, rendered from RAW while the B21 pipeline is on; plus DNG while the DNG toggle is on).
     * B22: with burst merge on it is a RAW burst merged on the processing queue. [onCaptured] runs (camera thread)
     * as soon as the frames are out of the camera, so the next shot can start; [done] runs on the processing
     * thread once the files are written, or with a failure. A press while a capture is in flight waits for it (one
     * press deep) instead of being dropped; a second waiting press, or a full queue, is refused (counted as blocked).
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
        camera.close()
        finishClose()
        if (current) listener.onError(message)
    }

    private fun createSession(camera: CameraDevice, target: Surface, gen: Int) {
        val su = setup
        closeReaders()
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
            r.setOnImageAvailableListener({ onRaw(it) }, handler)
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

    private fun captureOnThread(request: StillRequest, onCaptured: () -> Unit, done: (ShotResult) -> Unit, pressedAtMs: Long) {
        val notReady = when {
            session == null || device == null || setup == null || surface == null -> "camera not ready"
            jpegReader == null -> "no JPEG stream"
            else -> null
        }
        if (notReady != null) {
            saveExecutor.execute { done(ShotResult(ok = false, error = notReady, kept = request.keep)) }
            return
        }
        if (pending != null) {
            if (queuedPress == null) {
                queuedPress = QueuedPress(request, onCaptured, done, pressedAtMs)
            } else {
                refuseBlocked(request, done, "busy: a press is already waiting for the camera")
            }
            return
        }
        if (jobs >= MAX_JOBS) {
            refuseBlocked(request, done, "busy: $jobs shots waiting to be processed")
            return
        }
        startShot(request, onCaptured, done, SystemClock.elapsedRealtime() - pressedAtMs)
    }

    private fun refuseBlocked(request: StillRequest, done: (ShotResult) -> Unit, why: String) {
        saveExecutor.execute { done(ShotResult(ok = false, error = why, kept = request.keep, shutterBlocked = true)) }
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
                        poolFreeFrames = p.available(),
                        burstsQueued = burstsQueued,
                        motionRadPerSec = motionRadPerSec
                    )
                )
            }
        }
    }

    private fun startShot(request: StillRequest, onCaptured: () -> Unit, done: (ShotResult) -> Unit, waitMs: Long) {
        val s = session!!
        val cam = device!!
        val su = setup!!
        val target = surface!!
        val jpeg = jpegReader!!
        handler.removeCallbacks(trimPool)
        val blocked = waitMs > BurstReport.SHUTTER_BLOCK_MS
        val doneWithWait: (ShotResult) -> Unit = { r -> done(r.copy(shutterWaitMs = waitMs, shutterBlocked = r.shutterBlocked || blocked)) }
        var plan = planShot(su)
        var buffers: List<ByteBuffer> = emptyList()
        if (plan?.isBurst == true) {
            buffers = pool!!.acquire(plan.frames + 1)
            if (buffers.size < BurstPlanner.MIN_FRAMES + 1) {
                pool!!.release(buffers)
                buffers = emptyList()
                plan = BurstPlan(frames = 0, singleReason = "not enough memory for ${BurstPlanner.MIN_FRAMES} frames")
            } else if (buffers.size < plan.frames + 1) {
                plan = plan.copy(frames = buffers.size - 1, limits = plan.limits + "memory")
            }
        }
        val burst = plan?.isBurst == true
        var wantRaw = !burst && sessionHasRaw && rawReader != null
        if (wantRaw) {
            buffers = pool?.acquire(1).orEmpty()
            if (buffers.isEmpty()) wantRaw = false // no memory for a RAW copy: this shot is the camera JPEG
        }
        val orientation = CaptureMath.jpegOrientation(su.sensorOrientation, request.deviceDegrees, su.frontFacing)
        val shot = PendingShot(
            request = request,
            jpegOrientation = orientation,
            wantRaw = wantRaw,
            saveDng = (wantRaw || burst) && rawWanted,
            renderRaw = pipelineWanted && (wantRaw || burst),
            zoomRatio = zoomRatio,
            shutterAtMs = SystemClock.elapsedRealtime() - waitMs,
            onCaptured = onCaptured,
            done = doneWithWait,
            plan = plan,
            buffers = buffers
        )
        try {
            val first = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
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
            }.build()
            pending = shot
            if (burst) {
                val p = plan!!
                val requests = mutableListOf(first)
                for (i in 1..p.frames) requests += burstRequest(cam, su, p, i)
                s.captureBurst(requests, stillCallback, handler)
            } else {
                s.capture(first, stillCallback, handler)
            }
            // A long manual exposure and the burst's sensor time get their own time on top of the usual budget.
            val timeout = SHOT_TIMEOUT_MS + (manual?.stillFrameDurationNs ?: 0L) / 1_000_000L + (plan?.captureMs ?: 0L)
            handler.postDelayed({ if (pending === shot) failShot(shot, "timed out after $timeout ms") }, timeout)
        } catch (e: Exception) {
            Log.w(TAG, "still capture failed", e)
            pending = null
            pool?.release(buffers)
            saveExecutor.execute { doneWithWait(ShotResult(ok = false, error = "capture: ${e.javaClass.simpleName}", kept = request.keep)) }
            afterShot()
        }
    }

    /**
     * B22 burst frame [index]: RAW only (the preview pauses for the burst rather than flashing darker), AE off at the
     * plan's ISO / shutter / frame duration, focus and white balance as the JPEG frame (continuous AF or the M focus).
     */
    private fun burstRequest(cam: CameraDevice, su: ViewfinderSetup, p: BurstPlan, index: Int): CaptureRequest =
        cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(rawReader!!.surface)
            set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            set(CaptureRequest.SENSOR_SENSITIVITY, p.iso)
            set(CaptureRequest.SENSOR_EXPOSURE_TIME, p.exposureNs)
            set(CaptureRequest.SENSOR_FRAME_DURATION, p.frameDurationNs)
            if (su.continuousAf) set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, stabilizationMode())
            applyZoom(this, su)
            applyFocusAndWhiteBalance(this)
            setTag(BurstTag(index, manual))
        }.build()

    private val stillCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureStarted(s: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
            if (request.tag is BurstTag) return
            pending?.let { if (it.timestamp == null) it.timestamp = timestamp }
        }

        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val p = pending ?: return
            val tag = request.tag
            if (tag is BurstTag) {
                p.burstResults[tag.index] = result
                if (tag.index == p.plan?.frames) frameStats.breakRun() // the preview paused for the burst
            } else {
                listener.onStillValues(ResultValues.of(result))
                p.result = result
                if (p.timestamp == null) p.timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
            }
            maybeFinish(p)
        }

        override fun onCaptureFailed(s: CameraCaptureSession, request: CaptureRequest, failure: android.hardware.camera2.CaptureFailure) {
            val p = pending ?: return
            val tag = request.tag
            if (tag is BurstTag) {
                // A lost burst frame only shortens the burst.
                p.burstLost += tag.index
                if (tag.index == p.plan?.frames) frameStats.breakRun()
                maybeFinish(p)
            } else {
                failShot(p, "capture failed (reason ${failure.reason})")
            }
        }

        override fun onCaptureBufferLost(s: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) {
            val p = pending ?: return
            val tag = request.tag
            if (tag is BurstTag && target == rawReader?.surface) {
                p.burstLost += tag.index
                maybeFinish(p)
            }
        }
    }

    private fun onJpeg(reader: ImageReader) {
        val img = runCatching { reader.acquireNextImage() }.getOrNull() ?: return
        val p = pending
        if (p == null || p.jpeg != null) {
            img.close()
            return
        }
        try {
            val buf = img.planes[0].buffer
            p.jpeg = ByteArray(buf.remaining()).also { buf.get(it) }
        } finally {
            img.close()
        }
        maybeFinish(p)
    }

    /** B22: every RAW frame is copied into the shot's next pool buffer and its Image closed straight away. */
    private fun onRaw(reader: ImageReader) {
        val img = runCatching { reader.acquireNextImage() }.getOrNull() ?: return
        try {
            val p = pending ?: return
            val wanted = p.isBurst || (p.wantRaw && p.raw == null)
            val rawSlots = if (p.isBurst) p.buffers.size - 1 else p.buffers.size
            if (!wanted || p.nextBuffer >= rawSlots) return
            val plane = img.planes.firstOrNull() ?: return
            if (plane.pixelStride != 2) return
            val copy = RawCopy.copy(plane.buffer, plane.rowStride, img.width, img.height, p.buffers[p.nextBuffer], img.timestamp)
            p.nextBuffer++
            if (p.isBurst) p.burstRaws[img.timestamp] = copy else p.raw = copy
            maybeFinish(p)
        } catch (e: Exception) {
            Log.w(TAG, "RAW copy failed", e)
        } finally {
            img.close()
        }
    }

    private fun maybeFinish(p: PendingShot) {
        if (p.finished || p !== pending) return
        val result = p.result ?: return
        val jpeg = p.jpeg ?: return
        if (p.wantRaw && p.raw == null) return
        val plan = p.plan
        var burst: BurstCapture? = null
        if (p.isBurst && plan != null) {
            val indices = (1..plan.frames).filter { it !in p.burstLost }
            for (i in indices) {
                val r = p.burstResults[i] ?: return
                val ts = r.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
                if (p.burstRaws[ts] == null) return
            }
            val frames = indices.map { p.burstRaws[p.burstResults[it]!!.get(CaptureResult.SENSOR_TIMESTAMP)!!]!! }
            val results = indices.map { p.burstResults[it]!! }
            if (frames.isNotEmpty()) {
                burst = BurstCapture(frames, results, p.buffers.last(), plan, SystemClock.elapsedRealtime() - p.shutterAtMs)
            }
        }
        val ch = characteristics
        if (ch == null) {
            failShot(p, "no camera characteristics")
            return
        }
        p.finished = true
        pending = null
        val r = p.request
        val behind = jobs
        jobs++
        if (burst != null) burstsQueued++
        listener.onQueueDepth(jobs)
        val buffers = p.buffers
        val currentPool = pool
        val singlePlan = when {
            plan == null -> null
            plan.isBurst && burst == null -> BurstPlan(frames = 0, singleReason = "every burst frame was lost")
            plan.isBurst -> null
            else -> plan
        }
        val shot = StillShot(
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
            saveDng = p.saveDng && (p.raw != null || burst != null),
            renderRaw = p.renderRaw && (p.raw != null || burst != null),
            cameraId = setup?.cameraId ?: "",
            zoomRatio = p.zoomRatio,
            burst = burst,
            singlePlan = singlePlan,
            stress = r.stress,
            queuedBehind = behind,
            queuedAtMs = SystemClock.elapsedRealtime(),
            release = { currentPool?.release(buffers) }
        )
        val wasBurst = burst != null
        p.onCaptured()
        saveExecutor.execute {
            val out = saver.save(shot) { SystemClock.elapsedRealtime() }
            handler.post {
                jobs--
                if (wasBurst) burstsQueued--
                listener.onQueueDepth(jobs)
                if (jobs == 0) handler.postDelayed(trimPool, POOL_TRIM_IDLE_MS)
            }
            p.done(out)
        }
        afterShot()
    }

    private fun failShot(p: PendingShot, message: String) {
        if (p.finished) return
        p.finished = true
        if (pending === p) pending = null
        pool?.release(p.buffers)
        saveExecutor.execute { p.done(ShotResult(ok = false, error = message, dngWanted = p.saveDng, kept = p.request.keep)) }
        p.onCaptured()
        afterShot()
    }

    /** A RAW / pipeline toggle that arrived during a shot is applied once the shot is out of the camera. */
    private fun afterShot() {
        closeRetiredIfIdle()
        if (sessionWantsRaw() != sessionHasRaw) {
            val cam = device
            val target = surface
            if (cam != null && target != null) rebuildSession(cam, target)
        }
        // B22: a press that arrived during the capture starts now (after any rebuild, once the session is back).
        val q = queuedPress ?: return
        queuedPress = null
        handler.post { captureOnThread(q.request, q.onCaptured, q.done, q.atMs) }
    }

    private fun stabilizationMode(): Int = if (stabilization && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
    } else {
        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
    }

    /**
     * Detaches the readers from the next session. B22: queued shots hold pool copies, not camera Images, so a
     * retired reader is closed as soon as no capture is in flight.
     */
    private fun closeReaders() {
        pending?.let { failShot(it, "session closed") }
        jpegReader?.let { retired += it }
        rawReader?.let { retired += it }
        jpegReader = null
        rawReader = null
        sessionHasRaw = false
        closeRetiredIfIdle()
    }

    private fun closeRetiredIfIdle() {
        if (pending != null) return
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
            if ((request.tag as? ManualApplied)?.aeOff != true) {
                // B22: the auto-exposure reading the burst planner under-exposes from (Av's EV is already in it).
                result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { meteredIso = it }
                result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { meteredExposureNs = it }
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
        closeReaders()
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

        /** B22: shots on the processing queue before presses are refused (bursts beyond 2 already go single-frame). */
        private const val MAX_JOBS = 12
        private const val RAW_MAX_IMAGES = 4

        /** Idle pool buffers are freed once the queue has been empty this long. */
        private const val POOL_TRIM_IDLE_MS = 15_000L
    }
}
