package com.redline.camera.capability

import kotlin.math.atan
import kotlin.math.sqrt

/**
 * Everything Redline knows about a phone's cameras (PLAN 4.1, B10).
 * Pure data: built by [DeviceProfileProbe] on the device, serialised by [toJson],
 * and cached per OS build so it is rebuilt after an update.
 */
data class DeviceProfile(
    val schema: Int = SCHEMA,
    val manufacturer: String,
    val model: String,
    val sdkInt: Int,
    val buildFingerprint: String,
    val cameras: List<CameraProfile>,
    val encoders: Encoders
) {
    fun toJson(): String = Json.obj(
        "schema" to schema,
        "manufacturer" to manufacturer,
        "model" to model,
        "sdk_int" to sdkInt,
        "build_fingerprint" to buildFingerprint,
        "encoders" to encoders.toMap(),
        "cameras" to cameras.map { it.toMap() }
    )

    companion object {
        const val SCHEMA = 1
    }
}

data class Encoders(val hevcMain10: Boolean, val av1: Boolean, val apv: Boolean) {
    fun toMap(): Map<String, Any?> = linkedMapOf("hevc_main10" to hevcMain10, "av1" to av1, "apv" to apv)
}

/** One physical sensor + lens. A plain (non-logical) camera is its own single lens. */
data class LensProfile(
    val cameraId: String,
    val focalLengthsMm: List<Float>,
    val apertures: List<Float>,
    val sensorWidthMm: Float,
    val sensorHeightMm: Float,
    val pixelArrayWidth: Int,
    val pixelArrayHeight: Int,
    val activeWidth: Int,
    val activeHeight: Int,
    /** Diopters; 0 means fixed focus. */
    val minFocusDistanceDiopters: Float?,
    val hasOis: Boolean,
    val hasDistortionModel: Boolean
) {
    /** Width and height of the active area in mm. */
    val activeWidthMm: Float get() = if (pixelArrayWidth > 0) sensorWidthMm * activeWidth / pixelArrayWidth else sensorWidthMm
    val activeHeightMm: Float get() = if (pixelArrayHeight > 0) sensorHeightMm * activeHeight / pixelArrayHeight else sensorHeightMm

    /** Diagonal-based 35mm equivalent of the widest focal length. */
    val equivalentDiagonalMm: Float
        get() = FocalMath.equivalentDiagonal(focalLengthsMm.minOrNull() ?: 0f, activeWidthMm, activeHeightMm)

    /** Equivalent focal across the width of a 3:2 frame cut from the full sensor width (PLAN 3.3). */
    val equivalent32Mm: Float
        get() = FocalMath.equivalentHorizontal32(focalLengthsMm.minOrNull() ?: 0f, activeWidthMm)

    val horizontalFovDeg: Float
        get() = FocalMath.horizontalFovDeg(focalLengthsMm.minOrNull() ?: 0f, activeWidthMm)

    val fixedFocus: Boolean get() = (minFocusDistanceDiopters ?: 0f) == 0f

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "camera_id" to cameraId,
        "focal_lengths_mm" to focalLengthsMm,
        "apertures" to apertures,
        "sensor_mm" to listOf(sensorWidthMm, sensorHeightMm),
        "pixel_array" to listOf(pixelArrayWidth, pixelArrayHeight),
        "active_array" to listOf(activeWidth, activeHeight),
        "equivalent_diagonal_mm" to FocalMath.round1(equivalentDiagonalMm),
        "equivalent_3x2_mm" to FocalMath.round1(equivalent32Mm),
        "horizontal_fov_deg" to FocalMath.round1(horizontalFovDeg),
        "min_focus_diopters" to minFocusDistanceDiopters,
        "fixed_focus" to fixedFocus,
        "ois" to hasOis,
        "distortion_model" to hasDistortionModel
    )
}

data class CameraProfile(
    val id: String,
    val facing: String,
    val hardwareLevel: HardwareLevel,
    val capabilities: List<String>,
    val lenses: List<LensProfile>,
    val isoRange: Pair<Int, Int>?,
    val exposureRangeNs: Pair<Long, Long>?,
    val maxFrameDurationNs: Long?,
    val outputSizes: Map<String, List<String>>,
    val maxResolutionSizes: Map<String, List<String>>,
    val formats: List<String>,
    val fpsRanges: List<Pair<Int, Int>>,
    val highSpeedFps: List<Pair<Int, Int>>,
    val aeModes: List<String>,
    val afModes: List<String>,
    val awbModes: List<String>,
    val videoStabilization: List<String>,
    val aeCompensation: Pair<Int, Int>?,
    val aeCompensationStep: Float?,
    val tonemapMaxCurvePoints: Int?,
    val dynamicRangeProfiles: List<String>,
    val colorSpaceProfiles: Boolean,
    val cctWhiteBalance: Boolean,
    val lowLightBoost: Boolean,
    val extensions: List<String>,
    val colourCalibrationExposed: Boolean
) {
    fun has(cap: String): Boolean = cap in capabilities

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "facing" to facing,
        "hardware_level" to hardwareLevel.name,
        "capabilities" to capabilities,
        "lenses" to lenses.map { it.toMap() },
        "iso_range" to isoRange?.toList(),
        "exposure_range_ns" to exposureRangeNs?.toList(),
        "max_frame_duration_ns" to maxFrameDurationNs,
        "output_sizes" to outputSizes,
        "max_resolution_sizes" to maxResolutionSizes,
        "formats" to formats,
        "fps_ranges" to fpsRanges.map { it.toList() },
        "high_speed_fps" to highSpeedFps.map { it.toList() },
        "ae_modes" to aeModes,
        "af_modes" to afModes,
        "awb_modes" to awbModes,
        "video_stabilization" to videoStabilization,
        "ae_compensation" to aeCompensation?.toList(),
        "ae_compensation_step" to aeCompensationStep,
        "tonemap_max_curve_points" to tonemapMaxCurvePoints,
        "dynamic_range_profiles" to dynamicRangeProfiles,
        "color_space_profiles" to colorSpaceProfiles,
        "cct_white_balance" to cctWhiteBalance,
        "low_light_boost" to lowLightBoost,
        "extensions" to extensions,
        "colour_calibration_exposed" to colourCalibrationExposed
    )
}

object FocalMath {
    private const val FULL_FRAME_DIAG = 43.27f
    private const val FULL_FRAME_WIDTH = 36f

    fun equivalentDiagonal(focalMm: Float, widthMm: Float, heightMm: Float): Float {
        val diag = sqrt(widthMm * widthMm + heightMm * heightMm)
        return if (diag <= 0f || focalMm <= 0f) 0f else focalMm * FULL_FRAME_DIAG / diag
    }

    /** A 3:2 frame that uses the sensor's full width: matches a full-frame 3:2 frame by width. */
    fun equivalentHorizontal32(focalMm: Float, widthMm: Float): Float =
        if (widthMm <= 0f || focalMm <= 0f) 0f else focalMm * FULL_FRAME_WIDTH / widthMm

    fun horizontalFovDeg(focalMm: Float, widthMm: Float): Float =
        if (widthMm <= 0f || focalMm <= 0f) 0f else Math.toDegrees(2.0 * atan(widthMm / (2.0 * focalMm))).toFloat()

    fun round1(v: Float): Double = Math.round(v * 10.0) / 10.0
}

/** Minimal JSON writer: maps keep insertion order, lists stay lists, non-finite numbers become null. */
object Json {
    fun obj(vararg fields: Pair<String, Any?>): String = value(linkedMapOf(*fields), 0)

    fun value(v: Any?, indent: Int = 0): String = when (v) {
        null -> "null"
        is String -> string(v)
        is Boolean -> v.toString()
        is Double -> if (v.isFinite()) v.toString() else "null"
        is Float -> if (v.isFinite()) v.toString() else "null"
        is Number -> v.toString()
        is Map<*, *> -> if (v.isEmpty()) {
            "{}"
        } else {
            val pad = "  ".repeat(indent + 1)
            v.entries.joinToString(",\n", "{\n", "\n" + "  ".repeat(indent) + "}") { (k, x) ->
                pad + string(k.toString()) + ": " + value(x, indent + 1)
            }
        }
        is List<*> -> if (v.any { it is Map<*, *> }) {
            val pad = "  ".repeat(indent + 1)
            v.joinToString(",\n", "[\n", "\n" + "  ".repeat(indent) + "]") { pad + value(it, indent + 1) }
        } else {
            v.joinToString(", ", "[", "]") { value(it, indent + 1) }
        }
        else -> string(v.toString())
    }

    fun string(s: String): String {
        val b = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> b.append("\\\"")
                '\\' -> b.append("\\\\")
                '\n' -> b.append("\\n")
                '\r' -> b.append("\\r")
                '\t' -> b.append("\\t")
                else -> if (c < ' ') b.append("\\u%04x".format(c.code)) else b.append(c)
            }
        }
        return b.append('"').toString()
    }
}
