package com.redline.camera.viewfinder

import android.annotation.SuppressLint
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
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.view.Surface
import com.redline.camera.capability.CameraNames
import com.redline.camera.capability.CameraProfile
import com.redline.camera.capability.Dim
import com.redline.camera.capture.CaptureMath
import com.redline.camera.capture.ShotResult
import com.redline.camera.capture.StillSaver
import com.redline.camera.capture.StillShot
import com.redline.camera.manual.ManualApplied
import com.redline.camera.manual.ManualCaps
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
class ViewfinderController(context: Context, private val listener: Listener, version: String = "") {
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

        /** The session could not be configured with the RAW stream; RAW was turned off and the session rebuilt. */
        fun onRawUnavailable(reason: String) {}

        /** What the preview frames report (about twice a second): ISO, exposure and focus distance. */
        fun onPreviewValues(values: ResultValues) {}

        /** What a still's capture result reported, for the B14 range check. */
        fun onStillValues(values: ResultValues) {}
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
        val keep: Boolean
    )

    private val manager = context.applicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("redline-viewfinder").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val saver = StillSaver(context, version)
    private val saveExecutor = Executors.newSingleThreadExecutor()

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

    // B13 still capture (camera thread).
    private var rawWanted = false
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var sessionHasRaw = false
    private var characteristics: CameraCharacteristics? = null
    private var pending: PendingShot? = null
    private var savesQueued = 0
    private val retired = mutableListOf<ImageReader>()

    private class PendingShot(
        val request: StillRequest,
        val jpegOrientation: Int,
        val wantRaw: Boolean,
        val shutterAtMs: Long,
        val done: (ShotResult) -> Unit
    ) {
        var timestamp: Long? = null
        var result: TotalCaptureResult? = null
        var jpeg: ByteArray? = null
        var raw: Image? = null
        var finished = false
    }

    fun open(setup: ViewfinderSetup, texture: SurfaceTexture, zoomRatio: Float, stabilization: Boolean, raw: Boolean = false) {
        handler.post {
            rawWanted = raw
            openOnThread(setup, texture, zoomRatio, stabilization)
        }
    }

    /** RAW (DNG) on/off. The RAW stream is only in the session while RAW is on, so the default session stays preview + JPEG. */
    fun setRaw(on: Boolean) {
        handler.post {
            if (rawWanted == on) return@post
            rawWanted = on
            val cam = device ?: return@post
            val target = surface ?: return@post
            if (pending != null) return@post // rebuilt after the shot in flight
            rebuildSession(cam, target)
        }
    }

    /**
     * Takes one still (JPEG, plus DNG while RAW is on). [done] runs on the saver thread once the files are
     * written, or with a failure. Refused (failure, nothing captured) while a shot is in flight or two saves
     * are queued.
     */
    fun capture(request: StillRequest, done: (ShotResult) -> Unit) {
        handler.post { captureOnThread(request, done) }
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
        val withRaw = rawWanted && su?.rawSize != null
        if (withRaw) {
            val size = su!!.rawSize!!
            val r = ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, 2)
            r.setOnImageAvailableListener({ onRaw(it) }, handler)
            rawReader = r
            outputs += OutputConfiguration(r.surface)
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

    private fun captureOnThread(request: StillRequest, done: (ShotResult) -> Unit) {
        val s = session
        val cam = device
        val su = setup
        val target = surface
        val jpeg = jpegReader
        val refuse = when {
            s == null || cam == null || su == null || target == null -> "camera not ready"
            jpeg == null -> "no JPEG stream"
            pending != null -> "busy: previous shot in flight"
            savesQueued >= MAX_SAVES_QUEUED -> "busy: saving"
            else -> null
        }
        if (refuse != null) {
            saveExecutor.execute { done(ShotResult(ok = false, error = refuse, kept = request.keep)) }
            return
        }
        val wantRaw = sessionHasRaw && rawReader != null
        val orientation = CaptureMath.jpegOrientation(su!!.sensorOrientation, request.deviceDegrees, su.frontFacing)
        val shot = PendingShot(request, orientation, wantRaw, SystemClock.elapsedRealtime(), done)
        try {
            val req = cam!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(target!!)
                addTarget(jpeg!!.surface)
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
            }
            pending = shot
            s!!.capture(req.build(), stillCallback, handler)
            // A long manual exposure gets its own time on top of the usual budget.
            val timeout = SHOT_TIMEOUT_MS + (manual?.stillFrameDurationNs ?: 0L) / 1_000_000L
            handler.postDelayed({ if (pending === shot) failShot(shot, "timed out after $timeout ms") }, timeout)
        } catch (e: Exception) {
            Log.w(TAG, "still capture failed", e)
            pending = null
            saveExecutor.execute { done(ShotResult(ok = false, error = "capture: ${e.javaClass.simpleName}", kept = request.keep)) }
        }
    }

    private val stillCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureStarted(s: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
            pending?.let { if (it.timestamp == null) it.timestamp = timestamp }
        }

        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val p = pending ?: return
            listener.onStillValues(ResultValues.of(result))
            p.result = result
            if (p.timestamp == null) p.timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
            maybeFinish(p)
        }

        override fun onCaptureFailed(s: CameraCaptureSession, request: CaptureRequest, failure: android.hardware.camera2.CaptureFailure) {
            pending?.let { failShot(it, "capture failed (reason ${failure.reason})") }
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

    private fun onRaw(reader: ImageReader) {
        val img = runCatching { reader.acquireNextImage() }.getOrNull() ?: return
        val p = pending
        if (p == null || !p.wantRaw || p.raw != null) {
            img.close()
            return
        }
        p.raw = img
        maybeFinish(p)
    }

    private fun maybeFinish(p: PendingShot) {
        if (p.finished || p !== pending) return
        val result = p.result ?: return
        val jpeg = p.jpeg ?: return
        if (p.wantRaw && p.raw == null) return
        val ch = characteristics
        if (ch == null) {
            failShot(p, "no camera characteristics")
            return
        }
        p.finished = true
        pending = null
        savesQueued++
        val r = p.request
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
            keep = r.keep
        )
        saveExecutor.execute {
            val out = saver.save(shot) { SystemClock.elapsedRealtime() }
            handler.post {
                savesQueued--
                closeRetiredIfIdle()
            }
            p.done(out)
        }
        afterShot()
    }

    private fun failShot(p: PendingShot, message: String) {
        if (p.finished) return
        p.finished = true
        if (pending === p) pending = null
        runCatching { p.raw?.close() }
        saveExecutor.execute { p.done(ShotResult(ok = false, error = message, dngWanted = p.wantRaw, kept = p.request.keep)) }
        afterShot()
    }

    /** A RAW toggle that arrived during a shot is applied once the shot is out of the camera. */
    private fun afterShot() {
        if (rawWanted != sessionHasRaw) {
            val cam = device ?: return
            val target = surface ?: return
            rebuildSession(cam, target)
        }
    }

    private fun stabilizationMode(): Int = if (stabilization && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
    } else {
        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
    }

    /**
     * Detaches the readers from the next session. A RAW Image the saver is still writing belongs to its
     * reader, so readers are only closed once no save is queued.
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
        if (savesQueued > 0) return
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
        private const val MAX_SAVES_QUEUED = 2
        private const val SHOT_TIMEOUT_MS = 5_000L
    }
}
