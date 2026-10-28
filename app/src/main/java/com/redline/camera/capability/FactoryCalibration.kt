package com.redline.camera.capability

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.ColorSpaceTransform

/**
 * Factory calibration a camera reports through Camera2 (PLAN 9.6, B04).
 * Exported as JSON for calibration/redline_cal/profile_device.py, which checks
 * it against the phone's own DNG tags. Key names match CameraCharacteristics.
 */
data class FactoryCalibration(
    val cameraId: String,
    val referenceIlluminant1: Int?,
    val referenceIlluminant2: Int?,
    val colorTransform1: List<Double>?,
    val colorTransform2: List<Double>?,
    val forwardMatrix1: List<Double>?,
    val forwardMatrix2: List<Double>?,
    val calibrationTransform1: List<Double>?,
    val calibrationTransform2: List<Double>?,
    val lensDistortion: List<Float>?,
    val lensIntrinsicCalibration: List<Float>?,
    val whiteLevel: Int?,
    val blackLevelPattern: List<Int>?
) {
    /** True when the camera exposes the colour calibration needed to profile it without a chart. */
    val hasColorCalibration: Boolean
        get() = referenceIlluminant1 != null && colorTransform1 != null

    fun toJson(): String {
        val fields = linkedMapOf<String, Any?>(
            "camera_id" to cameraId,
            "SENSOR_REFERENCE_ILLUMINANT1" to referenceIlluminant1,
            "SENSOR_REFERENCE_ILLUMINANT2" to referenceIlluminant2,
            "SENSOR_COLOR_TRANSFORM1" to colorTransform1,
            "SENSOR_COLOR_TRANSFORM2" to colorTransform2,
            "SENSOR_FORWARD_MATRIX1" to forwardMatrix1,
            "SENSOR_FORWARD_MATRIX2" to forwardMatrix2,
            "SENSOR_CALIBRATION_TRANSFORM1" to calibrationTransform1,
            "SENSOR_CALIBRATION_TRANSFORM2" to calibrationTransform2,
            "LENS_DISTORTION" to lensDistortion,
            "LENS_INTRINSIC_CALIBRATION" to lensIntrinsicCalibration,
            "SENSOR_INFO_WHITE_LEVEL" to whiteLevel,
            "SENSOR_BLACK_LEVEL_PATTERN" to blackLevelPattern
        )
        return fields.entries.joinToString(",\n  ", prefix = "{\n  ", postfix = "\n}") { (k, v) ->
            "\"$k\": ${jsonValue(v)}"
        }
    }

    companion object {
        internal fun jsonValue(v: Any?): String = when (v) {
            null -> "null"
            is String -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            is List<*> -> v.joinToString(", ", "[", "]") { jsonValue(it) }
            is Double -> if (v.isFinite()) v.toString() else "null"
            is Float -> if (v.isFinite()) v.toString() else "null"
            else -> v.toString()
        }

        /** Row-major 3x3 from a Camera2 transform. */
        fun rowMajor(t: ColorSpaceTransform?): List<Double>? = t?.let { m ->
            (0 until 3).flatMap { row -> (0 until 3).map { col -> m.getElement(col, row).toDouble() } }
        }

        fun read(cameraId: String, ch: CameraCharacteristics): FactoryCalibration {
            val pattern = ch.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
            return FactoryCalibration(
                cameraId = cameraId,
                referenceIlluminant1 = ch.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1),
                referenceIlluminant2 = ch.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt(),
                colorTransform1 = rowMajor(ch.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)),
                colorTransform2 = rowMajor(ch.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)),
                forwardMatrix1 = rowMajor(ch.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)),
                forwardMatrix2 = rowMajor(ch.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)),
                calibrationTransform1 = rowMajor(ch.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)),
                calibrationTransform2 = rowMajor(ch.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)),
                lensDistortion = ch.get(CameraCharacteristics.LENS_DISTORTION)?.toList(),
                lensIntrinsicCalibration = ch.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)?.toList(),
                whiteLevel = ch.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL),
                blackLevelPattern = pattern?.let { p -> (0 until 4).map { i -> p.getOffsetForIndex(i % 2, i / 2) } }
            )
        }
    }
}
