package com.redline.camera.colour

import com.redline.camera.capability.FactoryCalibration
import com.redline.camera.capability.JsonParse
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs

/** DNG-style factory colour calibration (PLAN 9.6): the inputs of the camera -> XYZ solve. Matrices row-major. */
class ColourCalibration(
    val illuminant1: Int,
    val illuminant2: Int?,
    val colorMatrix1: DoubleArray,
    val colorMatrix2: DoubleArray? = null,
    val forwardMatrix1: DoubleArray? = null,
    val forwardMatrix2: DoubleArray? = null,
    val calibration1: DoubleArray? = null,
    val calibration2: DoubleArray? = null,
    val analogBalance: DoubleArray = doubleArrayOf(1.0, 1.0, 1.0)
) {
    /** Throws IllegalArgumentException when a matrix is missing, malformed or singular, or an illuminant is unknown. */
    fun validate(): ColourCalibration {
        listOf(colorMatrix1, colorMatrix2, forwardMatrix1, forwardMatrix2, calibration1, calibration2).forEach { m ->
            if (m != null) {
                require(m.size == 9 && m.all { it.isFinite() }) { "matrix must be a finite 3x3" }
                require(abs(Mat3.det(m)) >= 1e-9) { "singular matrix" }
            }
        }
        require(illuminant1 in ColourMath.ILLUMINANT_CCT) { "unsupported reference illuminant $illuminant1" }
        if (colorMatrix2 != null) require(illuminant2 in ColourMath.ILLUMINANT_CCT) { "unsupported reference illuminant $illuminant2" }
        require(analogBalance.size == 3 && analogBalance.all { it > 0 && it.isFinite() }) { "bad analog balance" }
        return this
    }

    companion object {
        /** From the reference's JSON layout (calibration/redline_cal/dng_profile.py ColorCalibration). */
        fun fromJson(m: Map<*, *>): ColourCalibration = ColourCalibration(
            illuminant1 = (m["illuminant1"] as Number).toInt(),
            illuminant2 = (m["illuminant2"] as? Number)?.toInt(),
            colorMatrix1 = matrix(m["color_matrix1"]) ?: throw IllegalArgumentException("no color_matrix1"),
            colorMatrix2 = matrix(m["color_matrix2"]),
            forwardMatrix1 = matrix(m["forward_matrix1"]),
            forwardMatrix2 = matrix(m["forward_matrix2"]),
            calibration1 = matrix(m["calibration1"]),
            calibration2 = matrix(m["calibration2"]),
            analogBalance = (m["analog_balance"] as? List<*>)?.map { (it as Number).toDouble() }?.toDoubleArray()
                ?: doubleArrayOf(1.0, 1.0, 1.0)
        ).validate()

        /** Camera2 factory calibration (SENSOR_COLOR_TRANSFORM = DNG ColorMatrix), or null when it lacks the colour keys. */
        fun fromCamera2(f: FactoryCalibration): ColourCalibration? {
            val cm1 = f.colorTransform1 ?: return null
            val ill1 = f.referenceIlluminant1 ?: return null
            val cm2 = f.colorTransform2
            return ColourCalibration(
                illuminant1 = ill1,
                illuminant2 = if (cm2 != null) f.referenceIlluminant2 else null,
                colorMatrix1 = cm1.toDoubleArray(),
                colorMatrix2 = cm2?.toDoubleArray(),
                forwardMatrix1 = f.forwardMatrix1?.toDoubleArray(),
                forwardMatrix2 = if (cm2 != null) f.forwardMatrix2?.toDoubleArray() else null,
                calibration1 = f.calibrationTransform1?.toDoubleArray(),
                calibration2 = if (cm2 != null) f.calibrationTransform2?.toDoubleArray() else null
            ).validate()
        }

        /** Nested [[a,b,c],[d,e,f],[g,h,i]] or flat 9 numbers -> row-major DoubleArray; null stays null. */
        fun matrix(v: Any?): DoubleArray? {
            val list = v as? List<*> ?: return null
            val flat = list.flatMap { if (it is List<*>) it else listOf(it) }.map { (it as Number).toDouble() }
            require(flat.size == 9) { "expected 9 values, got ${flat.size}" }
            return flat.toDoubleArray()
        }
    }
}

/**
 * A colour profile bundled with the app (assets/colour/<id>_profile.json): colour numbers taken from a device's own
 * DNG tags, matched by the SHA-256 of "manufacturer|model" (lowercase) so the repo never names the device.
 */
class BundledProfile(val id: String, val modelSha256: Set<String>, val facing: String?, val calibration: ColourCalibration) {
    companion object {
        fun parse(text: String): BundledProfile {
            val m = JsonParse.parse(text) as Map<*, *>
            return BundledProfile(
                id = m["profile_id"] as String,
                modelSha256 = (m["model_sha256"] as List<*>).map { (it as String).lowercase(Locale.US) }.toSet(),
                facing = m["facing"] as? String,
                calibration = ColourCalibration.fromJson(m["color"] as Map<*, *>)
            )
        }
    }
}

/** Which calibration a camera renders with, or why the pipeline falls back to the camera JPEG. */
data class ProfileChoice(val source: String?, val profileId: String?, val calibration: ColourCalibration?, val reason: String?) {
    val ok: Boolean get() = calibration != null
}

object ColourProfiles {
    fun modelHash(manufacturer: String, model: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("${manufacturer.lowercase(Locale.US)}|${model.lowercase(Locale.US)}".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Profile selection (B21): a bundled profile matched by model hash (and camera facing), then the camera's own
     * Camera2 matrices, else none (the still falls back to the camera JPEG, with the reason).
     */
    fun select(
        bundled: List<BundledProfile>,
        manufacturer: String,
        model: String,
        facing: String?,
        camera2: FactoryCalibration?
    ): ProfileChoice {
        val hash = modelHash(manufacturer, model)
        bundled.firstOrNull { hash in it.modelSha256 && (it.facing == null || it.facing == facing) }?.let {
            return ProfileChoice("bundled", it.id, it.calibration, null)
        }
        val c2 = camera2?.let { runCatching { ColourCalibration.fromCamera2(it) } }
        val cal = c2?.getOrNull()
        if (cal != null) return ProfileChoice("camera2", "camera2:${camera2.sourceCameraId}", cal, null)
        val why = when {
            camera2 == null -> "camera characteristics unavailable"
            c2?.exceptionOrNull() != null -> "Camera2 colour calibration unusable: ${c2.exceptionOrNull()?.message}"
            else -> "Camera2 reports no colour transforms"
        }
        return ProfileChoice(null, null, null, "no bundled profile for this model and $why; camera JPEG is used")
    }
}
