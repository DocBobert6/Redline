package com.redline.camera.capability

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import java.io.File

/**
 * Builds the full [DeviceProfile] from CameraCharacteristics and MediaCodecList (PLAN 4.1).
 * Reads only static metadata: no camera is opened and no permission is needed.
 * Every key is read on its own so one vendor quirk can't hide the rest.
 */
class DeviceProfileProbe(private val context: Context) {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    fun build(): DeviceProfile = DeviceProfile(
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        sdkInt = Build.VERSION.SDK_INT,
        buildFingerprint = Build.FINGERPRINT,
        cameras = safe { manager.cameraIdList.toList() }.orEmpty().mapNotNull { id -> safe { camera(id) } },
        encoders = encoders()
    )

    /** Builds the profile and caches it; reuses the cache until the OS build changes. */
    fun cachedJson(): String {
        val file = File(context.filesDir, CACHE_FILE)
        val cached = safe { file.readText() }
        if (cached != null && cached.contains(Json.string(Build.FINGERPRINT))) return cached
        val json = build().toJson()
        safe { file.writeText(json) }
        return json
    }

    private fun camera(id: String): CameraProfile {
        val ch = manager.getCameraCharacteristics(id)
        val capInts = safe { ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList() }.orEmpty()
        val map = safe { ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) }
        val maxResMap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            safe { ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION) }
        } else {
            null
        }
        val aeInts = safe { ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)?.toList() }.orEmpty()
        val physicalIds = safe { ch.physicalCameraIds.toList() }.orEmpty()
        val lensSources = if (physicalIds.isEmpty()) listOf(id to ch) else physicalIds.mapNotNull { pid -> safe { pid to manager.getCameraCharacteristics(pid) } }
        val iso = safe { ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) }
        val exposure = safe { ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) }
        val aeComp = safe { ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) }

        return CameraProfile(
            id = id,
            facing = when (safe { ch.get(CameraCharacteristics.LENS_FACING) }) {
                CameraMetadata.LENS_FACING_BACK -> "BACK"
                CameraMetadata.LENS_FACING_FRONT -> "FRONT"
                CameraMetadata.LENS_FACING_EXTERNAL -> "EXTERNAL"
                else -> "UNKNOWN"
            },
            hardwareLevel = when (safe { ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) }) {
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> HardwareLevel.LEGACY
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> HardwareLevel.EXTERNAL
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> HardwareLevel.LIMITED
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> HardwareLevel.FULL
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> HardwareLevel.LEVEL_3
                else -> HardwareLevel.UNKNOWN
            },
            capabilities = CameraNames.names(capInts, CameraNames.CAPABILITIES),
            lenses = lensSources.map { (lid, lch) -> lens(lid, lch) },
            isoRange = iso?.let { it.lower to it.upper },
            exposureRangeNs = exposure?.let { it.lower to it.upper },
            maxFrameDurationNs = safe { ch.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION) },
            outputSizes = sizes(map),
            maxResolutionSizes = sizes(maxResMap),
            formats = CameraNames.names(safe { map?.outputFormats?.toList() }.orEmpty(), CameraNames.FORMATS),
            fpsRanges = safe { ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.map { it.lower to it.upper } }.orEmpty(),
            highSpeedFps = safe { map?.highSpeedVideoFpsRanges?.map { it.lower to it.upper }?.distinct() }.orEmpty(),
            aeModes = CameraNames.names(aeInts, CameraNames.AE_MODES),
            afModes = CameraNames.names(safe { ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList() }.orEmpty(), CameraNames.AF_MODES),
            awbModes = CameraNames.names(safe { ch.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toList() }.orEmpty(), CameraNames.AWB_MODES),
            videoStabilization = CameraNames.names(
                safe { ch.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.toList() }.orEmpty(),
                CameraNames.VIDEO_STABILIZATION
            ),
            aeCompensation = aeComp?.let { it.lower to it.upper },
            aeCompensationStep = safe { ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() },
            tonemapMaxCurvePoints = safe { ch.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS) },
            dynamicRangeProfiles = dynamicRange(ch),
            colorSpaceProfiles = 20 in capInts,
            cctWhiteBalance = cct(ch),
            lowLightBoost = CameraNames.LOW_LIGHT_BOOST_AE_MODE in aeInts,
            extensions = extensions(id),
            colourCalibrationExposed = (listOf(ch) + lensSources.map { it.second }).any {
                safe { it.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1) } != null
            }
        )
    }

    private fun lens(id: String, ch: CameraCharacteristics): LensProfile {
        val size = safe { ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) }
        val pixels = safe { ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) }
        val active = safe { ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) }
        return LensProfile(
            cameraId = id,
            focalLengthsMm = safe { ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList() }.orEmpty(),
            apertures = safe { ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.toList() }.orEmpty(),
            sensorWidthMm = size?.width ?: 0f,
            sensorHeightMm = size?.height ?: 0f,
            pixelArrayWidth = pixels?.width ?: 0,
            pixelArrayHeight = pixels?.height ?: 0,
            activeWidth = active?.width() ?: (pixels?.width ?: 0),
            activeHeight = active?.height() ?: (pixels?.height ?: 0),
            minFocusDistanceDiopters = safe { ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) },
            hasOis = safe { ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.contains(1) } == true,
            hasDistortionModel = safe { ch.get(CameraCharacteristics.LENS_DISTORTION) } != null
        )
    }

    private fun sizes(map: StreamConfigurationMap?): Map<String, List<String>> {
        if (map == null) return emptyMap()
        val out = linkedMapOf<String, List<String>>()
        for ((format, name) in REPORTED_FORMATS) {
            val list = safe { map.getOutputSizes(format) }?.sortedByDescending { it.width.toLong() * it.height }
            if (!list.isNullOrEmpty()) out[name] = list.take(MAX_SIZES).map { "${it.width}x${it.height}" }
        }
        return out
    }

    private fun dynamicRange(ch: CameraCharacteristics): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return emptyList()
        val set = safe { ch.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)?.supportedProfiles }
        return CameraNames.names(set.orEmpty(), CameraNames.DYNAMIC_RANGE)
    }

    private fun cct(ch: CameraCharacteristics): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return false
        return safe { ch.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_MODES)?.contains(CCT_MODE) } == true
    }

    private fun extensions(id: String): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptyList()
        val list = safe { manager.getCameraExtensionCharacteristics(id).supportedExtensions }
        return CameraNames.names(list.orEmpty(), CameraNames.EXTENSIONS)
    }

    private fun encoders(): Encoders {
        val infos = safe { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder } }.orEmpty()
        fun supports(mime: String) = infos.any { info -> info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
        val main10 = infos.any { info ->
            info.supportedTypes.any { it.equals(HEVC, ignoreCase = true) } &&
                safe {
                    info.getCapabilitiesForType(HEVC).profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 }
                } == true
        }
        return Encoders(hevcMain10 = main10, av1 = supports("video/av01"), apv = supports("video/apv"))
    }

    companion object {
        const val CACHE_FILE = "device_profile.json"
        private const val MAX_SIZES = 12
        private const val HEVC = "video/hevc"

        /** CameraMetadata.COLOR_CORRECTION_MODE_CCT (Android 16). */
        private const val CCT_MODE = 3

        private val REPORTED_FORMATS = listOf(
            ImageFormat.JPEG to "JPEG",
            ImageFormat.YUV_420_888 to "YUV_420_888",
            ImageFormat.PRIVATE to "PRIVATE",
            ImageFormat.RAW_SENSOR to "RAW_SENSOR",
            0x1005 to "JPEG_R",
            ImageFormat.HEIC to "HEIC"
        )

        private inline fun <T> safe(block: () -> T?): T? = try {
            block()
        } catch (e: Exception) {
            null
        }
    }
}
