package com.redline.camera.capability

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.Surface
import java.io.File
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * First-launch self-test (PLAN 4.2, B11). Opens the default back camera and runs four short sessions:
 * preview frames + fps, a still JPEG, a RAW_SENSOR capture with exposure metadata, and a fixed
 * 24 fps repeating request. Blocking: call it off the main thread. Every wait has a timeout.
 *
 * The result is cached in self_test.json and reused until the OS build fingerprint changes.
 * Failures are logged locally (logcat + self_test_failures.log); nothing is sent anywhere.
 */
class SelfTest(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    fun hasPermission(): Boolean = appContext.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    fun cached(): SelfTestReport? = SelfTestReport.fromJson(runCatching { File(appContext.filesDir, CACHE_FILE).readText() }.getOrNull())

    /** True when [cachedOrRun] would open the camera: forced, never run, incomplete, or run on another OS build. */
    fun isDue(force: Boolean = false): Boolean {
        val cached = cached()
        return force || cached == null || !cached.complete || cached.buildFingerprint != Build.FINGERPRINT
    }

    /** The cached report when it is for this OS build and complete; otherwise a fresh run (cached if complete). */
    fun cachedOrRun(camera: CameraProfile?, force: Boolean = false): SelfTestReport {
        val cached = cached()
        if (!force && cached != null && cached.complete && cached.buildFingerprint == Build.FINGERPRINT) return cached
        val report = run(camera)
        if (report.complete) runCatching { File(appContext.filesDir, CACHE_FILE).writeText(report.toJson()) }
        logFailures(report)
        return report
    }

    fun run(camera: CameraProfile?): SelfTestReport {
        val now = System.currentTimeMillis()
        if (camera == null) return SelfTestReport.allSkipped(Build.FINGERPRINT, null, "no camera", now)
        if (!hasPermission()) return SelfTestReport.allSkipped(Build.FINGERPRINT, camera.id, "CAMERA permission not granted", now)
        val thread = HandlerThread("redline-self-test").apply { start() }
        val handler = Handler(thread.looper)
        var device: CameraDevice? = null
        try {
            val opened = try {
                open(camera.id, handler)
            } catch (e: Exception) {
                return SelfTestReport.allSkipped(Build.FINGERPRINT, camera.id, "camera unavailable: ${describe(e)}", now)
            }
            device = opened
            val run = Run(opened, camera, handler)
            val checks = listOf(
                guard(SelfTestKeys.PREVIEW) { run.preview() },
                guard(SelfTestKeys.JPEG) { run.jpeg() },
                guard(SelfTestKeys.RAW) { run.raw() },
                guard(SelfTestKeys.FPS_24) { run.fps24() }
            )
            return SelfTestReport(
                buildFingerprint = Build.FINGERPRINT,
                cameraId = camera.id,
                ranAtMs = now,
                complete = true,
                checks = checks
            )
        } finally {
            runCatching { device?.close() }
            thread.quitSafely()
        }
    }

    private fun guard(key: String, block: () -> CheckResult): CheckResult = try {
        block()
    } catch (e: Exception) {
        CheckResult(key, SelfTestKeys.label(key), CheckStatus.FAIL, describe(e))
    }

    private fun logFailures(report: SelfTestReport) {
        if (report.failures.isEmpty()) return
        val lines = report.failures.joinToString("\n") { "${report.ranAtMs} ${Build.FINGERPRINT} ${it.key} FAIL ${it.reason}" }
        Log.w(TAG, "Self-test failures:\n$lines")
        runCatching {
            val f = File(appContext.filesDir, FAILURE_LOG)
            if (f.length() > MAX_LOG_BYTES) f.delete()
            f.appendText(lines + "\n")
        }
    }

    @SuppressLint("MissingPermission")
    private fun open(id: String, handler: Handler): CameraDevice {
        val f = CompletableFuture<CameraDevice>()
        val callback = object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (!f.complete(camera)) camera.close()
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                f.completeExceptionally(IllegalStateException("camera disconnected"))
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                f.completeExceptionally(IllegalStateException("camera error $error"))
            }
        }
        manager.openCamera(id, callback, handler)
        return await(f, OPEN_TIMEOUT_MS, "camera open")
    }

    /** One opened camera; each check builds and closes its own session. */
    private inner class Run(private val device: CameraDevice, private val camera: CameraProfile, private val handler: Handler) {
        private val executor = Executor { handler.post(it) }
        private val map = manager.getCameraCharacteristics(camera.id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        private fun sizes(format: Int): List<Dim> = map?.getOutputSizes(format)?.map { Dim(it.width, it.height) }.orEmpty()

        private val previewSize = StreamSizes.preview(sizes(ImageFormat.YUV_420_888)) ?: Dim(640, 480)
        private val jpegBytes = AtomicInteger(0)

        fun preview(): CheckResult {
            val key = SelfTestKeys.PREVIEW
            val counter = FrameCounter()
            withReaders(listOf(reader(previewSize, ImageFormat.YUV_420_888, counter))) { readers ->
                withSession(readers.map { it.surface }) { session ->
                    val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(readers[0].surface) }
                    session.setRepeatingRequest(req.build(), counter, handler)
                    Thread.sleep(PREVIEW_MS)
                    session.stopRepeating()
                }
            }
            val fps = FpsMath.measure(counter.timestamps.toList(), PREVIEW_WARMUP_NS)
            val metrics = linkedMapOf<String, Any?>(
                "frames" to counter.results.get(),
                "images" to counter.images.get(),
                "capture_failures" to counter.failures.get(),
                "fps" to fps?.let { round2(it) },
                "size" to previewSize.toString()
            )
            val ok = counter.results.get() >= MIN_FRAMES && counter.images.get() >= MIN_FRAMES
            val reason = if (ok) {
                "${counter.images.get()} frames, ${fps?.let { "%.1f".format(it) } ?: "?"} fps"
            } else {
                "only ${counter.images.get()} images / ${counter.results.get()} results in ${PREVIEW_MS} ms"
            }
            return CheckResult(key, SelfTestKeys.label(key), if (ok) CheckStatus.PASS else CheckStatus.FAIL, reason, metrics)
        }

        fun jpeg(): CheckResult {
            val key = SelfTestKeys.JPEG
            val size = StreamSizes.largest(sizes(ImageFormat.JPEG)) ?: return skipped(key, "no JPEG output size")
            val bytes = CompletableFuture<ByteArray>()
            val jpegReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
            val onJpeg = ImageReader.OnImageAvailableListener { r ->
                r.acquireLatestImage()?.use { img ->
                    val buf = img.planes[0].buffer
                    jpegBytes.set(buf.remaining())
                    val head = ByteArray(minOf(2, buf.remaining()))
                    buf.get(head)
                    bytes.complete(head)
                }
            }
            jpegReader.setOnImageAvailableListener(onJpeg, handler)
            val previewCounter = FrameCounter()
            withReaders(listOf(reader(previewSize, ImageFormat.YUV_420_888, previewCounter), jpegReader)) { readers ->
                withSession(readers.map { it.surface }) { session ->
                    val pv = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(readers[0].surface) }
                    session.setRepeatingRequest(pv.build(), previewCounter, handler)
                    Thread.sleep(SETTLE_MS)
                    val still = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply { addTarget(jpegReader.surface) }
                    session.capture(still.build(), null, handler)
                    await(bytes, CAPTURE_TIMEOUT_MS, "JPEG image")
                    session.stopRepeating()
                }
            }
            val head = bytes.getNow(ByteArray(0))
            val soi = head.size >= 2 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte()
            val metrics = linkedMapOf<String, Any?>("size" to size.toString(), "bytes" to jpegBytes.get())
            return if (soi) {
                CheckResult(key, SelfTestKeys.label(key), CheckStatus.PASS, "JPEG $size, ${jpegBytes.get() / 1024} KiB", metrics)
            } else {
                CheckResult(key, SelfTestKeys.label(key), CheckStatus.FAIL, "image arrived without a JPEG SOI marker", metrics)
            }
        }

        fun raw(): CheckResult {
            val key = SelfTestKeys.RAW
            if (!camera.has("RAW")) return skipped(key, "no RAW capability")
            val size = StreamSizes.largest(sizes(ImageFormat.RAW_SENSOR)) ?: return skipped(key, "no RAW_SENSOR output size")
            val image = CompletableFuture<Boolean>()
            val captured = CompletableFuture<TotalCaptureResult>()
            val rawReader = ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, 2)
            val onRaw = ImageReader.OnImageAvailableListener { r ->
                r.acquireLatestImage()?.use { img -> image.complete(img.planes.isNotEmpty() && img.planes[0].buffer.remaining() > 0) }
            }
            rawReader.setOnImageAvailableListener(onRaw, handler)
            val stillCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    captured.complete(result)
                }

                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    captured.completeExceptionally(IllegalStateException("RAW capture failed, reason ${failure.reason}"))
                }
            }
            val previewCounter = FrameCounter()
            withReaders(listOf(reader(previewSize, ImageFormat.YUV_420_888, previewCounter), rawReader)) { readers ->
                withSession(readers.map { it.surface }) { session ->
                    val pv = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(readers[0].surface) }
                    session.setRepeatingRequest(pv.build(), previewCounter, handler)
                    Thread.sleep(SETTLE_MS)
                    val still = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply { addTarget(rawReader.surface) }
                    session.capture(still.build(), stillCallback, handler)
                    await(captured, CAPTURE_TIMEOUT_MS, "RAW capture result")
                    await(image, CAPTURE_TIMEOUT_MS, "RAW image")
                    session.stopRepeating()
                }
            }
            val r = captured.getNow(null)
            val iso = r?.get(CaptureResult.SENSOR_SENSITIVITY)
            val exposure = r?.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            val metrics = linkedMapOf<String, Any?>("size" to size.toString(), "iso" to iso, "exposure_ns" to exposure)
            val missing = listOfNotNull(
                if (image.getNow(false) == true) null else "empty RAW image",
                if (iso == null) "no SENSOR_SENSITIVITY" else null,
                if (exposure == null) "no SENSOR_EXPOSURE_TIME" else null
            )
            return if (missing.isEmpty()) {
                CheckResult(key, SelfTestKeys.label(key), CheckStatus.PASS, "RAW $size, ISO $iso, ${(exposure ?: 0L) / 1000} us", metrics)
            } else {
                CheckResult(key, SelfTestKeys.label(key), CheckStatus.FAIL, missing.joinToString(), metrics)
            }
        }

        fun fps24(): CheckResult {
            val key = SelfTestKeys.FPS_24
            if (camera.fpsRanges.none { it.first == 24 && it.second == 24 }) return skipped(key, "no fixed [24,24] AE range")
            val counter = FrameCounter()
            withReaders(listOf(reader(previewSize, ImageFormat.YUV_420_888, counter))) { readers ->
                withSession(readers.map { it.surface }) { session ->
                    val req = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(readers[0].surface)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(24, 24))
                    }
                    session.setRepeatingRequest(req.build(), counter, handler)
                    Thread.sleep(FPS_RUN_MS)
                    session.stopRepeating()
                }
            }
            val fps = FpsMath.measure(counter.timestamps.toList(), FPS_WARMUP_NS)
            val metrics = linkedMapOf<String, Any?>(
                "fps" to fps?.let { round2(it) },
                "frames" to counter.results.get(),
                "tolerance" to FPS_TOLERANCE
            )
            val ok = FpsMath.within(fps, 24.0, FPS_TOLERANCE)
            val shown = fps?.let { "%.2f".format(it) } ?: "no"
            val reason = if (ok) "$shown fps" else "$shown fps measured, want 24 +/- $FPS_TOLERANCE"
            return CheckResult(key, SelfTestKeys.label(key), if (ok) CheckStatus.PASS else CheckStatus.FAIL, reason, metrics)
        }

        private fun skipped(key: String, reason: String) = CheckResult(key, SelfTestKeys.label(key), CheckStatus.SKIPPED, reason)

        private fun reader(size: Dim, format: Int, counter: FrameCounter): ImageReader =
            ImageReader.newInstance(size.width, size.height, format, 4).apply {
                val onImage = ImageReader.OnImageAvailableListener { r ->
                    r.acquireLatestImage()?.let {
                        counter.images.incrementAndGet()
                        it.close()
                    }
                }
                setOnImageAvailableListener(onImage, handler)
            }

        private fun <T> withReaders(readers: List<ImageReader>, block: (List<ImageReader>) -> T): T = try {
            block(readers)
        } finally {
            readers.forEach { runCatching { it.close() } }
        }

        private fun withSession(surfaces: List<Surface>, block: (CameraCaptureSession) -> Unit) {
            val f = CompletableFuture<CameraCaptureSession>()
            val callback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (!f.complete(session)) session.close()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    f.completeExceptionally(IllegalStateException("session configuration failed"))
                }
            }
            val outputs = surfaces.map { OutputConfiguration(it) }
            val config = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor, callback)
            device.createCaptureSession(config)
            val session = await(f, SESSION_TIMEOUT_MS, "session configure")
            try {
                block(session)
            } finally {
                runCatching { session.close() }
            }
        }
    }

    /** Counts results and images; timestamps come from SENSOR_TIMESTAMP so fps is the sensor's, not the app's. */
    private class FrameCounter : CameraCaptureSession.CaptureCallback() {
        val timestamps: MutableList<Long> = Collections.synchronizedList(mutableListOf())
        val results = AtomicInteger(0)
        val images = AtomicInteger(0)
        val failures = AtomicInteger(0)

        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            results.incrementAndGet()
            result.get(CaptureResult.SENSOR_TIMESTAMP)?.let { timestamps.add(it) }
        }

        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            failures.incrementAndGet()
        }
    }

    companion object {
        const val CACHE_FILE = "self_test.json"
        const val FAILURE_LOG = "self_test_failures.log"
        private const val TAG = "RedlineSelfTest"
        private const val MAX_LOG_BYTES = 64 * 1024L
        private const val OPEN_TIMEOUT_MS = 3_000L
        private const val SESSION_TIMEOUT_MS = 3_000L
        private const val CAPTURE_TIMEOUT_MS = 4_000L
        private const val PREVIEW_MS = 1_000L
        private const val SETTLE_MS = 400L
        private const val FPS_RUN_MS = 1_500L
        private const val PREVIEW_WARMUP_NS = 200_000_000L
        private const val FPS_WARMUP_NS = 500_000_000L
        private const val MIN_FRAMES = 3
        const val FPS_TOLERANCE = 1.5

        private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0

        private fun describe(e: Throwable): String {
            val cause = if (e is ExecutionException) e.cause ?: e else e
            return "${cause.javaClass.simpleName}: ${cause.message.orEmpty()}".take(160)
        }

        private fun <T> await(f: CompletableFuture<T>, timeoutMs: Long, what: String): T = try {
            f.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            f.completeExceptionally(e)
            throw IllegalStateException("$what timed out after $timeoutMs ms")
        }
    }
}
