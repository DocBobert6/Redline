package com.redline.camera.viewfinder

import android.annotation.SuppressLint
import android.content.Context
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
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.view.Surface
import com.redline.camera.capability.CameraProfile
import com.redline.camera.capability.Dim
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

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
    val zoomPlan: ZoomPlan
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
                zoomPlan = plan
            )
        }

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
class ViewfinderController(context: Context, private val listener: Listener) {
    interface Listener {
        /** First onCaptureCompleted of a session, as SystemClock.elapsedRealtime(). */
        fun onFirstCapture(elapsedRealtimeMs: Long)

        /** Rolling sensor fps, about twice a second. [warm] is false during the first seconds while AE settles. */
        fun onPreviewFps(fps: Double, warm: Boolean)

        /** The repeating request is running with these settings. */
        fun onStreaming(setup: ViewfinderSetup, stabilization: Boolean)

        /** The camera could not be opened or was lost; the controller has already cleaned up. */
        fun onError(message: String)
    }

    private val manager = context.applicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("redline-viewfinder").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    // Camera-thread state.
    private var generation = 0
    private var opening = false
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var surface: Surface? = null
    private var setup: ViewfinderSetup? = null
    private var zoomRatio = 1f
    private var stabilization = false
    private val closeWaiters = mutableListOf<CompletableFuture<Unit>>()
    private val fps = RollingFps()
    private var firstCaptureSeen = false
    private var streamStartMs = 0L
    private var lastFpsReportMs = 0L

    fun open(setup: ViewfinderSetup, texture: SurfaceTexture, zoomRatio: Float, stabilization: Boolean) {
        handler.post { openOnThread(setup, texture, zoomRatio, stabilization) }
    }

    fun setZoom(ratio: Float) {
        handler.post {
            zoomRatio = ratio
            repeat()
        }
    }

    fun setStabilization(on: Boolean) {
        handler.post {
            if (stabilization != on) {
                stabilization = on
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
    }

    @SuppressLint("MissingPermission")
    private fun openOnThread(setup: ViewfinderSetup, texture: SurfaceTexture, ratio: Float, stab: Boolean) {
        if (device != null || opening) return
        this.setup = setup
        zoomRatio = ratio
        stabilization = stab
        firstCaptureSeen = false
        fps.reset()
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
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (gen != generation) {
                    s.close()
                    return
                }
                session = s
                streamStartMs = SystemClock.elapsedRealtime()
                repeat()
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                if (gen == generation) listener.onError("preview session configuration failed")
            }
        }
        try {
            val config = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(OutputConfiguration(target)), executor, callback)
            camera.createCaptureSession(config)
        } catch (e: Exception) {
            Log.w(TAG, "createCaptureSession failed", e)
            listener.onError("preview session failed: ${e.javaClass.simpleName}")
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val now = SystemClock.elapsedRealtime()
            if (!firstCaptureSeen) {
                firstCaptureSeen = true
                listener.onFirstCapture(now)
            }
            val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
            val rate = fps.add(ts) ?: return
            if (now - lastFpsReportMs >= FPS_REPORT_MS) {
                lastFpsReportMs = now
                listener.onPreviewFps(rate, now - streamStartMs >= WARMUP_MS)
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
                val stabMode = if (stabilization && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
                } else {
                    CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                }
                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, stabMode)
                applyZoom(this, su)
            }
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

    private fun closeOnThread(f: CompletableFuture<Unit>) {
        generation++
        val cam = device
        runCatching { session?.close() }
        session = null
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
    }
}
