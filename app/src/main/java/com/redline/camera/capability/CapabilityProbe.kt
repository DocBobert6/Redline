package com.redline.camera.capability

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import kotlin.math.sqrt

/**
 * Reads CameraCharacteristics for every camera. Reading characteristics does not
 * open the camera and does not need the CAMERA permission.
 */
class CapabilityProbe(context: Context) {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    fun probeAll(): List<CameraFacts> = manager.cameraIdList.map { probe(it) }

    fun probe(id: String): CameraFacts {
        val ch = manager.getCameraCharacteristics(id)
        val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet() ?: emptySet()
        val size = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val diag = if (size != null) sqrt(size.width * size.width + size.height * size.height) else 0f
        val focals = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList() ?: emptyList()
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val jpeg = map?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width.toLong() * it.height }
        val fps = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.map { it.lower to it.upper } ?: emptyList()

        return CameraFacts(
            id = id,
            facing = when (ch.get(CameraCharacteristics.LENS_FACING)) {
                CameraMetadata.LENS_FACING_BACK -> "BACK"
                CameraMetadata.LENS_FACING_FRONT -> "FRONT"
                CameraMetadata.LENS_FACING_EXTERNAL -> "EXTERNAL"
                else -> "UNKNOWN"
            },
            hardwareLevel = when (ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> HardwareLevel.LEGACY
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> HardwareLevel.EXTERNAL
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> HardwareLevel.LIMITED
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> HardwareLevel.FULL
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> HardwareLevel.LEVEL_3
                else -> HardwareLevel.UNKNOWN
            },
            hasRaw = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in caps,
            hasManualSensor = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps,
            hasManualPostProcessing = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in caps,
            hasTenBit = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT in caps,
            hasUltraHighRes = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR in caps,
            hasDepthOutput = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT in caps,
            physicalIds = ch.physicalCameraIds.toList(),
            equivalentFocalLengthsMm = focals.map { TierRules.equivalentFocalMm(it, diag) },
            maxJpegWidth = jpeg?.width ?: 0,
            maxJpegHeight = jpeg?.height ?: 0,
            fpsRanges = fps,
        )
    }
}
