package com.redline.camera.video

import android.annotation.SuppressLint
import android.content.Context
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
import android.hardware.camera2.params.TonemapCurve
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.view.Surface
import com.redline.camera.capability.Dim
import com.redline.camera.look.CubeLut
import com.redline.camera.viewfinder.ViewfinderSetup
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * B40 video mode: its own camera session with one PRIVATE stream (3840x2160 first, else the best 16:9 size the camera and
 * an encoder both take at 30 fps), the flat ISP tonemap ([TonemapPlan]), the GPU chain ([VideoRenderer]) drawing the
 * viewfinder and, while recording, the encoder ([VideoRecorder]). A size is used only after the session query
 * (CameraDevice.isSessionConfigurationSupported with the real surface) accepts it, or, where the HAL cannot answer, because a
 * single PRIVATE stream at a listed size and a listed fixed fps range is a guaranteed configuration; a size the session then
 * still refuses falls through to the next one. All camera work runs on one thread; listener calls arrive there too.
 */
class VideoController(private val context: Context, private val listener: Listener) {
    interface Listener {
        fun onReport(change: (VideoReport) -> VideoReport)

        /** The session streams at [size] (null: closed). */
        fun onStreaming(size: Dim?)

        /** Recording started at [startedAtMs] (elapsedRealtime), or stopped (null). */
        fun onRecording(startedAtMs: Long?)

        fun onSaved(uri: Uri?, record: RecordingRecord)

        fun onError(message: String)
    }

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("redline-video-cam").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    private val renderer = VideoRenderer(object : VideoRenderer.Listener {
        override fun onStats(stats: VideoRenderer.Stats) {
            lastStats = stats
            listener.onReport {
                it.copy(
                    chain = stats.chain,
                    timerMethod = stats.timerMethod,
                    cameraFrames = stats.cameraFrames,
                    framesDropped = stats.dropped
                )
            }
        }

        override fun onRuntimeError(message: String) {
            handler.post {
                listener.onReport { it.withError("gpu: $message") }
                listener.onError("video GPU chain failed: $message")
                stopRecordingOnThread()
            }
        }
    })

    // Camera-thread state.
    private var generation = 0
    private var device: CameraDevice? = null
    private var opening = false
    private var session: CameraCaptureSession? = null
    private var cameraSurface: Surface? = null
    private var preparedPlan: TonemapPlan? = null
    private var plan: TonemapPlan? = null
    private var streamSize: Dim? = null
    private var sensorOrientation = 90
    private var afModes: List<Int> = emptyList()
    private var realtimeClock = false
    private var recorder: VideoRecorder? = null
    private var recordStartMs = 0L
    private var droppedAtStart = 0
    private var resultModeSeen = false
    private val closeWaiters = mutableListOf<CompletableFuture<Unit>>()

    @Volatile private var lastStats: VideoRenderer.Stats? = null

    val recording: Boolean get() = recorder != null

    /**
     * Opens the camera in video mode. [loadLook] (run on the camera thread) gives the stills' Standard look: the preview's
     * 33^3 LUT and the fitted tone. [displayOut] gives the viewfinder render size for a stream size.
     */
    fun open(
        setup: ViewfinderSetup,
        display: SurfaceTexture,
        loadLook: () -> Pair<CubeLut, DoubleArray>,
        zoomRatio: Float,
        displayOut: (Dim) -> Dim
    ) {
        handler.post { openOnThread(setup, display, loadLook, zoomRatio, displayOut) }
    }

    fun startRecording(deviceDegrees: Int, audio: Boolean) {
        handler.post { startRecordingOnThread(deviceDegrees, audio) }
    }

    fun stopRecording() {
        handler.post { stopRecordingOnThread() }
    }

    /** Stops any recording (it is saved), closes the camera and detaches the renderer from the display texture. */
    fun close(): CompletableFuture<Unit> {
        val f = CompletableFuture<Unit>()
        if (!handler.post { closeOnThread(f) }) f.complete(Unit)
        return f
    }

    fun release() {
        close().whenComplete { _, _ ->
            renderer.release()
            thread.quitSafely()
        }
    }

    // ------------------------------------------------------------------------------------------------ camera thread

    @SuppressLint("MissingPermission")
    private fun openOnThread(
        setup: ViewfinderSetup,
        display: SurfaceTexture,
        loadLook: () -> Pair<CubeLut, DoubleArray>,
        zoomRatio: Float,
        displayOut: (Dim) -> Dim
    ) {
        if (device != null || opening) return
        val gen = ++generation
        val ch = runCatching { manager.getCameraCharacteristics(setup.cameraId) }.getOrElse {
            fail("characteristics: ${it.javaClass.simpleName}")
            return
        }
        sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        afModes = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList().orEmpty()
        realtimeClock = ch.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
        val modes = ch.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)?.toList().orEmpty()
        val tp = TonemapPlan.choose(modes, ch.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS))
        plan = tp
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(SurfaceTexture::class.java)?.map { Dim(it.width, it.height) }.orEmpty()
        val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.map { it.lower to it.upper }.orEmpty()
        val bySize = VideoSizes.candidates(sizes, ranges, FPS) { d ->
            runCatching { map?.getOutputMinFrameDuration(SurfaceTexture::class.java, android.util.Size(d.width, d.height)) }.getOrNull()
        }
        val candidates = bySize.filter { VideoRecorder.pickEncoder(it, FPS) != null }
        listener.onReport {
            it.copy(
                tonemapMode = tp.modeName,
                tonemapLabel = tp.label,
                tonemapExact = tp.exact,
                tonemapNote = tp.note,
                curvePoints = tp.curve?.let { c -> c.size / 2 },
                sizesTried = emptyList(),
                fps = FPS,
                colourMatrix = "identity (the ISP's colour correction already gives linear sRGB)"
            )
        }
        if (candidates.isEmpty()) {
            val why = if (bySize.isEmpty()) {
                "no 16:9 PRIVATE size at a fixed [$FPS,$FPS] range"
            } else {
                "no encoder takes ${bySize.joinToString()} at $FPS fps"
            }
            listener.onReport { it.copy(active = false).withError(why) }
            listener.onError("video unavailable: $why")
            return
        }
        if (preparedPlan != tp) {
            val (lut, tone) = try {
                loadLook()
            } catch (e: Exception) {
                fail("look not loaded: ${e.message ?: e.javaClass.simpleName}")
                return
            }
            val gainEv = VideoExposure.gainEv(tone)
            val caps = try {
                renderer.prepare(lut, tp, tone, gainEv).get(PREPARE_TIMEOUT_S, TimeUnit.SECONDS)
            } catch (e: Exception) {
                VideoRenderer.Caps(ok = false, reason = "prepare: ${e.javaClass.simpleName}")
            }
            listener.onReport {
                it.copy(
                    glRenderer = caps.renderer,
                    intermediate = caps.intermediate,
                    lutFormat = caps.lutFormat,
                    selfCheckMaxErrorSteps = caps.selfCheckMaxErrorSteps,
                    lookApplied = caps.lookApplied,
                    lookNote = caps.lookNote ?: caps.reason,
                    timerMethod = caps.timerMethod,
                    exposureGainEv = gainEv
                )
            }
            if (!caps.ok) {
                listener.onReport { it.withError("gpu: ${caps.reason}") }
                listener.onError("video GPU chain unavailable: ${caps.reason}")
                return
            }
            preparedPlan = tp
        }
        val callback = object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                opening = false
                if (gen != generation) {
                    camera.close()
                    return
                }
                device = camera
                trySize(camera, setup, display, candidates, 0, zoomRatio, displayOut, gen, mutableListOf())
            }

            override fun onClosed(camera: CameraDevice) {
                finishClose()
            }

            override fun onDisconnected(camera: CameraDevice) {
                opening = false
                lost(camera, "camera disconnected")
            }

            override fun onError(camera: CameraDevice, error: Int) {
                opening = false
                lost(camera, "camera error $error")
            }
        }
        try {
            opening = true
            manager.openCamera(setup.cameraId, executor, callback)
        } catch (e: Exception) {
            opening = false
            fail("openCamera: ${e.javaClass.simpleName}")
        }
    }

    private fun trySize(
        camera: CameraDevice,
        setup: ViewfinderSetup,
        display: SurfaceTexture,
        sizes: List<Dim>,
        index: Int,
        zoomRatio: Float,
        displayOut: (Dim) -> Dim,
        gen: Int,
        tried: MutableList<String>
    ) {
        if (gen != generation) return
        if (index >= sizes.size) {
            listener.onReport { it.copy(sizesTried = tried.toList()).withError("no video session configured (${tried.joinToString()})") }
            listener.onError("video session could not be configured")
            return
        }
        val size = sizes[index]
        val camTexture = try {
            renderer.attach(display, size, sensorOrientation, displayOut(size), FPS).get(PREPARE_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: Exception) {
            listener.onReport { it.withError("attach $size: ${e.cause?.message ?: e.message}") }
            listener.onError("video renderer attach failed")
            return
        }
        cameraSurface?.release()
        val surface = Surface(camTexture)
        cameraSurface = surface
        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        builder.addTarget(surface)
        applyRequest(builder, setup, zoomRatio)
        val outputs = listOf(OutputConfiguration(surface))
        val cb = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (gen != generation) {
                    s.close()
                    return
                }
                session = s
                streamSize = size
                resultModeSeen = false
                try {
                    s.setRepeatingRequest(builder.build(), resultCallback, handler)
                } catch (e: Exception) {
                    listener.onReport { it.withError("repeating request: ${e.javaClass.simpleName}") }
                    listener.onError("video preview failed")
                    return
                }
                tried += "$size ok"
                val src = sizeSource ?: "session"
                listener.onReport { it.copy(active = true, size = size.toString(), sizesTried = tried.toList(), sizeSource = src) }
                listener.onStreaming(size)
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                if (gen != generation) return
                tried += "$size configure failed"
                trySize(camera, setup, display, sizes, index + 1, zoomRatio, displayOut, gen, tried)
            }
        }
        val config = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor, cb)
        config.sessionParameters = builder.build()
        sizeSource = null
        val supported = try {
            camera.isSessionConfigurationSupported(config).also {
                sizeSource =
                    "session query (CameraDevice.isSessionConfigurationSupported)"
            }
        } catch (e: Exception) {
            // UnsupportedOperationException where the HAL cannot answer: a lone PRIVATE stream at a listed size and a listed
            // fixed fps range is a guaranteed configuration.
            sizeSource = "guaranteed single PRIVATE stream (session query: ${e.javaClass.simpleName})"
            true
        }
        if (!supported) {
            tried += "$size rejected by session query"
            trySize(camera, setup, display, sizes, index + 1, zoomRatio, displayOut, gen, tried)
            return
        }
        try {
            camera.createCaptureSession(config)
        } catch (e: Exception) {
            tried += "$size ${e.javaClass.simpleName}"
            trySize(camera, setup, display, sizes, index + 1, zoomRatio, displayOut, gen, tried)
        }
    }

    private var sizeSource: String? = null

    private fun applyRequest(b: CaptureRequest.Builder, setup: ViewfinderSetup, zoomRatio: Float) {
        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(FPS, FPS))
        // Only modes the camera lists (a fixed-focus camera keeps the template's AF mode).
        if (CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO in afModes) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        }
        val tp = plan
        when (tp?.mode) {
            TonemapModes.CONTRAST_CURVE -> tp.curve?.let { c ->
                b.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
                b.set(CaptureRequest.TONEMAP_CURVE, TonemapCurve(c, c, c))
            }
            TonemapModes.GAMMA_VALUE -> {
                b.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_GAMMA_VALUE)
                b.set(CaptureRequest.TONEMAP_GAMMA, tp.gamma ?: TonemapPlan.GAMMA)
            }
            TonemapModes.PRESET_CURVE -> {
                b.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_PRESET_CURVE)
                b.set(CaptureRequest.TONEMAP_PRESET_CURVE, CaptureRequest.TONEMAP_PRESET_CURVE_SRGB)
            }
            else -> Unit
        }
        val range = setup.zoomRatioRange
        if (range != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio.coerceIn(range.first, range.second))
        } else {
            setup.activeArray?.let { b.set(CaptureRequest.SCALER_CROP_REGION, ViewfinderSetup.cropRegion(it, zoomRatio)) }
        }
        listener.onReport { it.copy(stabilization = "off (OIS + EIS for video is B41)") }
    }

    private val resultCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            if (resultModeSeen) return
            resultModeSeen = true
            val mode = result.get(CaptureResult.TONEMAP_MODE)
            listener.onReport { it.copy(tonemapResultMode = mode?.let { m -> TonemapModes.name(m) } ?: "not reported") }
        }
    }

    private fun startRecordingOnThread(deviceDegrees: Int, audio: Boolean) {
        if (recorder != null || session == null) return
        val size = streamSize ?: return
        val upright = VideoSizes.upright(size, sensorOrientation)
        val orientation = VideoOrientation.plan(upright.height > upright.width, deviceDegrees)
        val rec = VideoRecorder(context, size, FPS, orientation.hint, audio, realtimeClock)
        try {
            rec.prepare()
            rec.start()
            renderer.setEncoder(rec.inputSurface, orientation, size).get(PREPARE_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "recording start failed", e)
            runCatching { renderer.setEncoder(null, orientation, size).get(PREPARE_TIMEOUT_S, TimeUnit.SECONDS) }
            rec.abort()
            val msg = e.cause?.message ?: e.message ?: e.javaClass.simpleName
            listener.onReport { it.withError("record start: $msg") }
            listener.onError("recording could not start: $msg")
            return
        }
        recorder = rec
        recordStartMs = SystemClock.elapsedRealtime()
        droppedAtStart = lastStats?.dropped ?: 0
        val e = rec.encoder
        listener.onReport {
            it.copy(
                recording = true,
                codec = if (e.mime == EncoderPick.HEVC) "HEVC" else "AVC (no HEVC encoder takes $size)",
                encoder = e.name,
                encoderHardware = e.hardware,
                bitrateBps = e.bitrate,
                bitrateRange = e.bitrateRange?.let { r -> "${r.first}-${r.second}" },
                orientation = "content ${orientation.contentRotation}°, hint ${orientation.hint}° (device ${VideoOrientation.snap(
                    deviceDegrees
                )}°)",
                audio = rec.audioNote
            )
        }
        listener.onRecording(recordStartMs)
    }

    private fun stopRecordingOnThread() {
        val rec = recorder ?: return
        recorder = null
        val size = streamSize ?: rec.size
        runCatching { renderer.setEncoder(null, VideoOrientation(0, 0), size).get(PREPARE_TIMEOUT_S, TimeUnit.SECONDS) }
        val r = rec.stop(recordStartMs)
        val dropped = ((lastStats?.dropped ?: 0) - droppedAtStart).coerceAtLeast(0)
        val record = RecordingRecord(r.durationMs, r.framesEncoded, dropped, r.audio, r.bytes, r.tracks, r.uri != null, r.error)
        listener.onReport { s ->
            val withRec = s.copy(recording = false, recordings = (s.recordings + record).takeLast(MAX_RECORDINGS), audio = rec.audioNote)
            if (r.error != null) withRec.withError("recording: ${r.error}") else withRec
        }
        listener.onRecording(null)
        listener.onSaved(r.uri, record)
    }

    private fun closeOnThread(f: CompletableFuture<Unit>) {
        stopRecordingOnThread()
        generation++
        runCatching { session?.close() }
        session = null
        streamSize = null
        val cam = device
        device = null
        closeWaiters += f
        listener.onReport { it.copy(active = false, recording = false) }
        listener.onStreaming(null)
        if (cam == null) {
            finishClose()
        } else {
            cam.close()
        }
    }

    private fun finishClose() {
        if (device != null || opening) return
        val waiters = closeWaiters.toList()
        closeWaiters.clear()
        renderer.detach().whenComplete { _, _ ->
            handler.post {
                cameraSurface?.release()
                cameraSurface = null
                waiters.forEach { it.complete(Unit) }
            }
        }
    }

    private fun lost(camera: CameraDevice, why: String) {
        stopRecordingOnThread()
        if (device == camera) {
            device = null
            session = null
        }
        camera.close()
        listener.onReport { it.copy(active = false).withError(why) }
        listener.onError(why)
        finishClose()
    }

    private fun fail(why: String) {
        listener.onReport { it.copy(active = false).withError(why) }
        listener.onError(why)
    }

    companion object {
        private const val TAG = "RedlineVideo"
        const val FPS = 30
        private const val PREPARE_TIMEOUT_S = 10L
        private const val MAX_RECORDINGS = 20
    }
}
