package com.redline.camera.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TierRulesTest {
    private fun facts(
        level: HardwareLevel = HardwareLevel.FULL,
        raw: Boolean = true,
        manual: Boolean = true,
        tenBit: Boolean = false,
        uhr: Boolean = false,
        focals: List<Float> = listOf(25f),
        fps: List<Pair<Int, Int>> = listOf(24 to 24, 30 to 30),
    ) = CameraFacts(
        id = "0", facing = "BACK", hardwareLevel = level, hasRaw = raw, hasManualSensor = manual,
        hasManualPostProcessing = true, hasTenBit = tenBit, hasUltraHighRes = uhr, hasDepthOutput = false,
        physicalIds = emptyList(), equivalentFocalLengthsMm = focals, maxJpegWidth = 4000,
        maxJpegHeight = 3000, fpsRanges = fps,
    )

    @Test fun baselineIsPro() = assertEquals(CapabilityTier.PRO, TierRules.tierOf(facts()))
    @Test fun limitedIsBasic() = assertEquals(CapabilityTier.BASIC, TierRules.tierOf(facts(level = HardwareLevel.LIMITED)))
    @Test fun noRawIsBasic() = assertEquals(CapabilityTier.BASIC, TierRules.tierOf(facts(raw = false)))
    @Test fun tenBitUnlocks() = assertEquals(CapabilityTier.PRO_PLUS, TierRules.tierOf(facts(tenBit = true)))
    @Test fun nativeTeleUnlocks() = assertEquals(CapabilityTier.PRO_PLUS, TierRules.tierOf(facts(focals = listOf(24f, 67f))))
    @Test fun true24p() = assertTrue(TierRules.supportsTrue24p(facts()))
    @Test fun equivalentFocal() {
        // 1/2" type sensor ~6.4 x 4.8 mm (8.0 mm diagonal), 4.6 mm lens -> ~24.9 mm equivalent
        assertEquals(24.9f, TierRules.equivalentFocalMm(4.6f, 8.0f), 0.1f)
    }
}
