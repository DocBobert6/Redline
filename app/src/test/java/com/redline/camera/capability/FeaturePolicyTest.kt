package com.redline.camera.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** B11 gate, unit half: baseline passes with no demotions; each forced failure demotes cleanly with a reason. */
class FeaturePolicyTest {
    private fun evaluate(
        combos: List<ComboResult> = Baseline.combos(),
        selfTest: SelfTestReport? = Baseline.selfTest(),
        quirks: Map<String, String> = emptyMap(),
        profile: DeviceProfile? = Baseline.profile()
    ) = FeaturePolicy.evaluate(profile, combos, selfTest, quirks)

    private fun failCheck(key: String) = Baseline.selfTest().let { r ->
        r.copy(checks = r.checks.map { if (it.key == key) it.copy(status = CheckStatus.FAIL, reason = "boom") else it })
    }

    private fun unsupported(key: String) =
        Baseline.combos().map { if (it.key == key) it.copy(status = ComboStatus.UNSUPPORTED, reason = "rejected") else it }

    @Test
    fun baselineAllPassingIsProWithNoDemotions() {
        val r = evaluate()
        assertEquals(CapabilityTier.PRO, r.defaults.detectedTier)
        assertEquals(CapabilityTier.PRO, r.defaults.tier)
        assertEquals(StillPath.RAW_PLUS_JPEG, r.defaults.stillPath)
        assertEquals(24, r.defaults.videoFps)
        assertTrue(r.defaults.previewStabilization)
        assertTrue(r.defaults.stabilized60fps)
        assertEquals("0", r.defaults.cameraId)
        assertTrue(r.demotions.toString(), r.demotions.isEmpty())
    }

    @Test
    fun rawCaptureFailureDemotesToJpegOnlyAndBasic() {
        val r = evaluate(selfTest = failCheck(SelfTestKeys.RAW))
        assertEquals(StillPath.JPEG_ONLY, r.defaults.stillPath)
        assertEquals(CapabilityTier.BASIC, r.defaults.tier)
        assertEquals(CapabilityTier.PRO, r.defaults.detectedTier)
        val raw = r.demotions.single { it.feature == FeatureKeys.RAW }
        assertEquals(DemotionSource.SELF_TEST, raw.source)
        assertTrue(raw.reason.contains("boom"))
        assertTrue(r.demotions.any { it.feature == "tier" && it.to == "Basic" })
        // Nothing else moved.
        assertEquals(24, r.defaults.videoFps)
        assertTrue(r.defaults.previewStabilization)
        assertEquals(2, r.demotions.size)
    }

    @Test
    fun fps24FailureDemotesTo30p() {
        val r = evaluate(selfTest = failCheck(SelfTestKeys.FPS_24))
        assertEquals(30, r.defaults.videoFps)
        assertEquals(CapabilityTier.PRO, r.defaults.tier)
        assertEquals(StillPath.RAW_PLUS_JPEG, r.defaults.stillPath)
        val d = r.demotions.single()
        assertEquals(FeatureKeys.VIDEO_24P, d.feature)
        assertEquals("24p", d.from)
        assertEquals("30p", d.to)
        assertTrue(d.reason.isNotBlank())
    }

    @Test
    fun unsupportedRawCombinationDemotesRaw() {
        val r = evaluate(combos = unsupported(ComboKeys.PREVIEW_RAW))
        assertEquals(StillPath.JPEG_ONLY, r.defaults.stillPath)
        assertEquals(DemotionSource.COMBINATION, r.demotions.first { it.feature == FeatureKeys.RAW }.source)
        assertTrue(r.demotions.first().reason.contains("rejected"))
    }

    @Test
    fun unsupported24pCombinationDemotesTo30p() {
        val r = evaluate(combos = unsupported(ComboKeys.VIDEO_24))
        assertEquals(30, r.defaults.videoFps)
        assertEquals(DemotionSource.COMBINATION, r.demotions.single().source)
    }

    @Test
    fun unsupportedPreviewStabilizationDemotesToPlain() {
        val r = evaluate(combos = unsupported(ComboKeys.PREVIEW_STABILIZATION))
        assertFalse(r.defaults.previewStabilization)
        assertFalse(r.defaults.stabilized60fps)
        assertEquals(FeatureKeys.PREVIEW_STABILIZATION, r.demotions.single().feature)
        assertEquals("plain preview", r.demotions.single().to)
    }

    @Test
    fun unsupportedStabilized60OnlyTurnsOffThatUnlock() {
        val r = evaluate(combos = unsupported(ComboKeys.PREVIEW_STAB_60))
        assertTrue(r.defaults.previewStabilization)
        assertFalse(r.defaults.stabilized60fps)
        assertEquals(FeatureKeys.STABILIZED_60FPS, r.demotions.single().feature)
    }

    @Test
    fun profileWithoutPreviewStabilizationRunsPlain() {
        val cam = Baseline.back.copy(videoStabilization = listOf("OFF", "ON"))
        val r = evaluate(profile = Baseline.profile(cam))
        assertFalse(r.defaults.previewStabilization)
        assertEquals(DemotionSource.PROFILE, r.demotions.single().source)
    }

    @Test
    fun quirkOverridesPassingDetection() {
        val r = evaluate(quirks = mapOf(FeatureKeys.RAW to "RAW frames corrupt on this HAL"))
        assertEquals(StillPath.JPEG_ONLY, r.defaults.stillPath)
        val raw = r.demotions.first { it.feature == FeatureKeys.RAW }
        assertEquals(DemotionSource.QUIRK, raw.source)
        assertTrue(raw.reason.contains("RAW frames corrupt"))
    }

    @Test
    fun skippedSelfTestDoesNotDemote() {
        val r = evaluate(selfTest = Baseline.selfTest(CheckStatus.SKIPPED))
        assertTrue(r.demotions.isEmpty())
        assertEquals(CapabilityTier.PRO, r.defaults.tier)
    }

    @Test
    fun assumedCombinationsKeepDefaultsButNeverOfferUnverifiedUnlock() {
        val r = evaluate(combos = MandatoryCombos.assumeAll(Baseline.back, "API 34"))
        assertTrue(r.demotions.toString(), r.demotions.isEmpty())
        assertEquals(StillPath.RAW_PLUS_JPEG, r.defaults.stillPath)
        assertEquals(24, r.defaults.videoFps)
        assertTrue(r.defaults.previewStabilization)
        assertFalse(r.defaults.stabilized60fps)
    }

    @Test
    fun debugForcedFailuresDemoteThroughTheSameRules() {
        val forced = DebugOverrides.parse("raw_capture, fps_24,combo:preview_stabilization")
        val selfTest = DebugOverrides.applySelfTest(Baseline.selfTest(), forced)
        val combos = DebugOverrides.applyCombos(Baseline.combos(), forced)
        val r = evaluate(combos = combos, selfTest = selfTest)
        assertEquals(StillPath.JPEG_ONLY, r.defaults.stillPath)
        assertEquals(30, r.defaults.videoFps)
        assertFalse(r.defaults.previewStabilization)
        assertEquals(CapabilityTier.BASIC, r.defaults.tier)
        assertTrue(r.demotions.all { it.reason.isNotBlank() })
        assertTrue(r.demotions.any { it.reason.contains(DebugOverrides.REASON) })
    }

    @Test
    fun limitedCameraWithoutRawIsBasicWithoutDemotion() {
        val cam = Baseline.back.copy(hardwareLevel = HardwareLevel.LIMITED, capabilities = listOf("BACKWARD_COMPATIBLE"))
        val r = evaluate(profile = Baseline.profile(cam))
        assertEquals(CapabilityTier.BASIC, r.defaults.tier)
        assertEquals(StillPath.JPEG_ONLY, r.defaults.stillPath)
        assertTrue(r.demotions.none { it.feature == FeatureKeys.RAW || it.feature == "tier" })
    }

    @Test
    fun noProfileFallsBackToBasicJpeg() {
        val r = evaluate(profile = null)
        assertEquals(CapabilityTier.BASIC, r.defaults.tier)
        assertEquals(StillPath.JPEG_ONLY, r.defaults.stillPath)
        assertEquals(30, r.defaults.videoFps)
    }

    @Test
    fun reportCarriesTheB11Sections() {
        val state = CapabilityState(combos = Baseline.combos(), selfTest = Baseline.selfTest())
        val json = CapabilityReport.toJson(Baseline.profile(), state)
        for (k in listOf("\"self_test\"", "\"combinations\"", "\"effective_defaults\"", "\"demotions\"", "\"cameras\"")) {
            assertTrue(k, json.contains(k))
        }
        val parsed = JsonParse.parse(json) as Map<*, *>
        assertEquals("PRO", (parsed["effective_defaults"] as Map<*, *>)["tier"])
    }
}
