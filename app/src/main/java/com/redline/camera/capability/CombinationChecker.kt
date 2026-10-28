package com.redline.camera.capability

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.os.Build
import android.util.Range
import android.util.Size
import androidx.annotation.RequiresApi

/**
 * Feature-combination checks (PLAN 4.1, B11).
 *
 * On API 35+ with CameraManager.isCameraDeviceSetupSupported, each default combination is asked of
 * the HAL through CameraDevice.CameraDeviceSetup.isSessionConfigurationSupported, using deferred
 * OutputConfigurations (sizes and surface classes, no real surfaces) and the session parameters the
 * combination needs. Those answers are labelled VERIFIED. Otherwise, or if a query throws, the
 * result comes from the guaranteed stream combination tables ([MandatoryCombos]) and is labelled
 * ASSUMED. Nothing is ever reported as verified that the HAL did not confirm.
 *
 * TODO(B43): add CameraX feature groups (GroupableFeature HDR_HLG10, FPS_60, PREVIEW_STABILIZATION,
 *  IMAGE_ULTRA_HDR) as a second opinion once a CameraX session exists (B12+). They need a bound
 *  CameraX use-case graph, which this capability screen does not have; Camera2 stays the source of truth.
 */
class CombinationChecker(context: Context) {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    fun check(camera: CameraProfile, quirks: Map<String, String> = emptyMap()): List<ComboResult> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return MandatoryCombos.assumeAll(camera, "API ${Build.VERSION.SDK_INT} < 35, no session query")
        }
        if (FeatureKeys.SESSION_QUERY in quirks) return MandatoryCombos.assumeAll(camera, "session query disabled by quirks table")
        if (!setupSupported(camera.id)) return MandatoryCombos.assumeAll(camera, "CameraDeviceSetup not supported for camera ${camera.id}")
        return try {
            Api35(manager, camera).checkAll()
        } catch (e: Exception) {
            MandatoryCombos.assumeAll(camera, "session query unavailable: ${e.javaClass.simpleName}")
        }
    }

    private fun setupSupported(id: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return false
        return try {
            manager.isCameraDeviceSetupSupported(id)
        } catch (e: Exception) {
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private class Api35(manager: CameraManager, private val camera: CameraProfile) {
        private val setup: CameraDevice.CameraDeviceSetup = manager.getCameraDeviceSetup(camera.id)
        private val map = manager.getCameraCharacteristics(camera.id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        private fun sizes(format: Int): List<Dim> = map?.getOutputSizes(format)?.map { Dim(it.width, it.height) }.orEmpty()

        private fun privSizes(): List<Dim> = map?.getOutputSizes(SurfaceTexture::class.java)?.map { Dim(it.width, it.height) }.orEmpty()

        private val preview = StreamSizes.preview(privSizes())
        private val jpegMax = StreamSizes.largest(sizes(ImageFormat.JPEG))
        private val rawMax = StreamSizes.largest(sizes(ImageFormat.RAW_SENSOR))
        private val yuv = StreamSizes.preview(sizes(ImageFormat.YUV_420_888))

        fun checkAll(): List<ComboResult> = ComboKeys.LABELS.keys.map { key ->
            try {
                checkOne(key)
            } catch (e: Exception) {
                MandatoryCombos.assume(key, camera, "session query threw ${e.javaClass.simpleName}: ${e.message.orEmpty().take(80)}")
            }
        }

        private fun checkOne(key: String): ComboResult {
            val pv = preview ?: return MandatoryCombos.assume(key, camera, "no preview size in stream map")
            return when (key) {
                ComboKeys.PREVIEW_JPEG -> {
                    val j = jpegMax ?: return unsupported(key, "no JPEG output size")
                    query(key, listOf(previewOut(pv), formatOut(ImageFormat.JPEG, j)), listOf("PRIVATE $pv", "JPEG $j"))
                }
                ComboKeys.PREVIEW_RAW -> {
                    val r = rawMax ?: return unsupported(key, "no RAW_SENSOR output size")
                    query(key, listOf(previewOut(pv), formatOut(ImageFormat.RAW_SENSOR, r)), listOf("PRIVATE $pv", "RAW_SENSOR $r"))
                }
                ComboKeys.PREVIEW_YUV_RAW -> {
                    val r = rawMax ?: return unsupported(key, "no RAW_SENSOR output size")
                    val y = yuv ?: return unsupported(key, "no YUV_420_888 output size")
                    val outs = listOf(previewOut(pv), formatOut(ImageFormat.YUV_420_888, y), formatOut(ImageFormat.RAW_SENSOR, r))
                    query(key, outs, listOf("PRIVATE $pv", "YUV_420_888 $y", "RAW_SENSOR $r"))
                }
                ComboKeys.VIDEO_24 -> video(key, pv, 24)
                ComboKeys.VIDEO_30 -> video(key, pv, 30)
                ComboKeys.PREVIEW_STABILIZATION -> stabilized(key, pv, 30)
                ComboKeys.PREVIEW_STAB_60 -> stabilized(key, pv, 60)
                else -> MandatoryCombos.assume(key, camera, "no session query for this key")
            }
        }

        private fun video(key: String, pv: Dim, fps: Int): ComboResult {
            if (camera.fpsRanges.none { it.first == fps && it.second == fps }) return unsupported(key, "no fixed [$fps,$fps] AE range")
            val candidates = StreamSizes.videoCandidates(privSizes())
            if (candidates.isEmpty()) return unsupported(key, "neither 3840x2160 nor 1920x1080 offered")
            val rejected = mutableListOf<String>()
            for (v in candidates) {
                val outs = listOf(previewOut(pv), OutputConfiguration(Size(v.width, v.height), MediaCodec::class.java))
                val ok = supported(outs, CameraDevice.TEMPLATE_RECORD) { b ->
                    b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
                }
                if (ok) {
                    val note = if (rejected.isEmpty()) "" else " (${rejected.joinToString()} rejected)"
                    val outs2 = listOf("PRIVATE $pv", "video $v @$fps")
                    return result(key, ComboStatus.SUPPORTED, "video $v at $fps fps supported$note", outs2)
                }
                rejected += v.toString()
            }
            return result(key, ComboStatus.UNSUPPORTED, "${rejected.joinToString()} at $fps fps rejected", listOf("PRIVATE $pv"))
        }

        private fun stabilized(key: String, pv: Dim, fps: Int): ComboResult {
            if ("PREVIEW_STABILIZATION" !in camera.videoStabilization) return unsupported(key, "PREVIEW_STABILIZATION mode not listed")
            if (camera.fpsRanges.none { it.second >= fps }) return unsupported(key, "no $fps fps AE range")
            val range = camera.fpsRanges.firstOrNull { it.first == fps && it.second == fps } ?: camera.fpsRanges.first { it.second >= fps }
            val ok = supported(listOf(previewOut(pv)), CameraDevice.TEMPLATE_PREVIEW) { b ->
                b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, PREVIEW_STAB_MODE)
                b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(range.first, range.second))
            }
            val label = "preview stabilization at [${range.first},${range.second}]"
            val status = if (ok) ComboStatus.SUPPORTED else ComboStatus.UNSUPPORTED
            return result(key, status, "$label ${if (ok) "supported" else "rejected"}", listOf("PRIVATE $pv"))
        }

        private fun query(key: String, outs: List<OutputConfiguration>, described: List<String>): ComboResult {
            val ok = supported(outs, CameraDevice.TEMPLATE_PREVIEW) {}
            val status = if (ok) ComboStatus.SUPPORTED else ComboStatus.UNSUPPORTED
            return result(key, status, if (ok) "session query: supported" else "session query: rejected", described)
        }

        private fun supported(outs: List<OutputConfiguration>, template: Int, params: (CaptureRequest.Builder) -> Unit): Boolean {
            val config = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs)
            val builder = setup.createCaptureRequest(template)
            params(builder)
            config.sessionParameters = builder.build()
            return setup.isSessionConfigurationSupported(config)
        }

        private fun previewOut(d: Dim) = OutputConfiguration(Size(d.width, d.height), SurfaceTexture::class.java)

        private fun formatOut(format: Int, d: Dim) = OutputConfiguration(format, Size(d.width, d.height))

        private fun result(key: String, status: ComboStatus, reason: String, outputs: List<String>) =
            ComboResult(key, ComboKeys.label(key), status, ComboSource.VERIFIED, reason, outputs)

        private fun unsupported(key: String, reason: String) =
            ComboResult(key, ComboKeys.label(key), ComboStatus.UNSUPPORTED, ComboSource.STATIC, reason)
    }

    private companion object {
        /** CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION (API 33). */
        const val PREVIEW_STAB_MODE = 2
    }
}
