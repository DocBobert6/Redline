package com.redline.camera.capability

/**
 * Redline capability tiers (see docs/PLAN.md, section 4.3).
 *
 * BASIC  - LEGACY/LIMITED hardware or no RAW: processed YUV path, look applied on top.
 * PRO    - FULL (or better) + RAW + MANUAL_SENSOR. The baseline every shipped default is built for.
 * PRO_PLUS - PRO plus at least one flagship unlock (10-bit video, ultra-high-res sensor,
 *            LEVEL_3, depth output, or a native lens in the 60-80mm equivalent range).
 */
enum class CapabilityTier { BASIC, PRO, PRO_PLUS }

data class CameraFacts(
    val id: String,
    val facing: String,
    val hardwareLevel: HardwareLevel,
    val hasRaw: Boolean,
    val hasManualSensor: Boolean,
    val hasManualPostProcessing: Boolean,
    val hasTenBit: Boolean,
    val hasUltraHighRes: Boolean,
    val hasDepthOutput: Boolean,
    val physicalIds: List<String>,
    val equivalentFocalLengthsMm: List<Float>,
    val maxJpegWidth: Int,
    val maxJpegHeight: Int,
    val fpsRanges: List<Pair<Int, Int>>,
)

enum class HardwareLevel(val rank: Int) {
    LEGACY(0), EXTERNAL(1), LIMITED(2), FULL(3), LEVEL_3(4), UNKNOWN(-1)
}

object TierRules {
    /** Native telephoto counts as a 70mm-stop unlock when it lands in this range. */
    val NATIVE_TELE_RANGE_MM = 60f..80f

    fun tierOf(c: CameraFacts): CapabilityTier {
        val pro = c.hardwareLevel.rank >= HardwareLevel.FULL.rank && c.hasRaw && c.hasManualSensor
        if (!pro) return CapabilityTier.BASIC
        val unlock = c.hasTenBit ||
            c.hasUltraHighRes ||
            c.hasDepthOutput ||
            c.hardwareLevel == HardwareLevel.LEVEL_3 ||
            c.equivalentFocalLengthsMm.any { it in NATIVE_TELE_RANGE_MM }
        return if (unlock) CapabilityTier.PRO_PLUS else CapabilityTier.PRO
    }

    /** True 24p needs an exact [24,24] AE target range. */
    fun supportsTrue24p(c: CameraFacts): Boolean = c.fpsRanges.any { it.first == 24 && it.second == 24 }

    /** 35mm-equivalent focal length from a physical focal length and sensor diagonal (mm). */
    fun equivalentFocalMm(focalMm: Float, sensorDiagonalMm: Float): Float =
        if (sensorDiagonalMm <= 0f) 0f else focalMm * 43.27f / sensorDiagonalMm
}
