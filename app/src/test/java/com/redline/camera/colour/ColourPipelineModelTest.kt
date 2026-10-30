package com.redline.camera.colour

import com.redline.camera.capability.FactoryCalibration
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** B21: profile selection, the bundled assets, the self-check frame's Kotlin planning, and the report section. */
class ColourPipelineModelTest {
    private fun asset(name: String) = File("src/main/assets/$name").readText()

    private val identity = listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    private fun camera2(withColour: Boolean) = FactoryCalibration(
        cameraId = "0",
        sourceCameraId = "2",
        referenceIlluminant1 = if (withColour) 21 else null,
        referenceIlluminant2 = null,
        colorTransform1 = if (withColour) listOf(0.71, -0.13, -0.07, -0.42, 1.21, 0.24, -0.06, 0.15, 0.62) else null,
        colorTransform2 = null,
        forwardMatrix1 = null,
        forwardMatrix2 = null,
        calibrationTransform1 = if (withColour) identity else null,
        calibrationTransform2 = null,
        lensDistortion = null,
        lensIntrinsicCalibration = null,
        whiteLevel = 1023,
        blackLevelPattern = listOf(64, 64, 64, 64)
    )

    private fun fakeProfile(hash: String) = BundledProfile(
        "test",
        setOf(hash),
        "BACK",
        ColourCalibration(21, null, doubleArrayOf(0.71, -0.13, -0.07, -0.42, 1.21, 0.24, -0.06, 0.15, 0.62)).validate()
    )

    @Test
    fun bundledProfileParsesAndNamesNoDevice() {
        val text = asset("colour/baseline_profile.json")
        val p = BundledProfile.parse(text)
        assertEquals("baseline", p.id)
        assertEquals("BACK", p.facing)
        assertTrue(p.modelSha256.all { it.matches(Regex("[0-9a-f]{64}")) })
        assertNotNull(p.calibration.forwardMatrix1)
        // The repo never names the device: only a hash identifies it.
        assertFalse(text.contains("pixel", ignoreCase = true))
    }

    @Test
    fun selectionPrefersBundledThenCamera2ThenFallsBack() {
        val hash = ColourProfiles.modelHash("Acme", "Phone 1")
        assertEquals(ColourProfiles.modelHash("ACME", "phone 1"), hash)
        val bundled = listOf(fakeProfile(hash))
        val a = ColourProfiles.select(bundled, "Acme", "Phone 1", "BACK", camera2(true))
        assertEquals("bundled", a.source)
        assertEquals("test", a.profileId)
        // The bundled profile is for the back camera only.
        val front = ColourProfiles.select(bundled, "Acme", "Phone 1", "FRONT", camera2(true))
        assertEquals("camera2", front.source)
        assertEquals("camera2:2", front.profileId)
        val other = ColourProfiles.select(bundled, "Other", "Phone", "BACK", camera2(true))
        assertEquals("camera2", other.source)
        val none = ColourProfiles.select(bundled, "Other", "Phone", "BACK", camera2(false))
        assertFalse(none.ok)
        assertNull(none.calibration)
        assertTrue(none.reason!!.contains("camera JPEG"))
        val noChars = ColourProfiles.select(emptyList(), "Other", "Phone", "BACK", null)
        assertTrue(noChars.reason!!.contains("characteristics"))
    }

    @Test
    fun bundledToneIsTheFittedStandardCurve() {
        val tone = ColourPipeline.loadTone(asset(ColourPipeline.TONE_ASSET))
        assertEquals(257, tone.size)
        assertTrue((1 until tone.size).all { tone[it] >= tone[it - 1] })
        // The highlight shoulder fixes the fit artefact: the shipped curve reaches 1.0 at the sensor clip.
        val curve = ColourMath.ToneCurve(tone, 0.5)
        assertEquals(1.0, curve.eval(0.5), 1e-9)
        assertTrue(curve.eval(0.5 * 0.7) < 1.0)
    }

    @Test
    fun selfCheckFramePlansLikeTheReference() {
        val f = SelfCheckFrame.parse(asset(ColourPipeline.SELF_CHECK_ASSET))
        assertEquals(f.width * f.height * 2, f.rawLittleEndian.size)
        val tone = ColourPipeline.loadTone(asset(ColourPipeline.TONE_ASSET))
        val g = ShotPlanner.geometry(f.calibration, f.asShotNeutral, f.width, f.height, f.zoom, f.longPx)
        val t = ShotPlanner.tone(tone, f.expectedMedianY, f.targetEncoded)
        assertTrue(f.mathsMatch(g, t.exposure.ev))
        assertEquals(0.0, g.solve.warmth, 0.0) // daylight: no warmth retained
        // The reference render compared with itself passes every gate.
        val argb = IntArray(f.expectedOutWidth * f.expectedOutHeight) { i ->
            val r = f.expectedRgb8[i * 3].toInt() and 0xFF
            val gg = f.expectedRgb8[i * 3 + 1].toInt() and 0xFF
            val b = f.expectedRgb8[i * 3 + 2].toInt() and 0xFF
            (0xFF shl 24) or (r shl 16) or (gg shl 8) or b
        }
        val ok = f.compare(argb, true, 5)
        assertEquals(0.0, ok.maxDe2000!!, 1e-12)
        assertEquals(0, ok.maxStep8bit)
        assertTrue("grey chroma ${ok.greyChromaMax}", ok.neutralPassed == true)
        assertTrue(ok.passed)
        // A colour shift fails parity.
        val shifted = argb.map { p -> (p and 0xFFFF00FF.toInt()) or ((((p shr 8) and 0xFF) / 2) shl 8) }.toIntArray()
        val bad = f.compare(shifted, true, 5)
        assertFalse(bad.passed)
        assertEquals(false, bad.parityPassed)
        assertFalse(f.compare(IntArray(3), true, 1).passed)
    }

    @Test
    fun reportSectionCarriesGatesAndTimings() {
        val r = ColourPipelineReport(
            userSetting = true,
            available = true,
            lookName = ColourPipeline.LOOK_NAME,
            profileSource = "bundled",
            profileId = "baseline",
            selfCheck = ColourSelfCheck(0.001, 0.4, 1, 0.5, true, 40)
        )
        assertTrue(r.enabled)
        assertNull(r.whyOff())
        val shot = ColourShot(rendered = true, cct = 3100.0, warmth = 0.28, timingsMs = mapOf("render" to 200L, "total" to 260L))
        val after = r.record(shot, 900).record(ColourShot(rendered = false, fallbackReason = "x"), 500)
        assertEquals(1, after.rendered)
        assertEquals(1, after.fallbacks)
        val m = after.toMap()
        assertEquals(260L, m["avg_render_ms"])
        assertEquals(900L, m["avg_shutter_to_saved_ms"])
        val gate = m["gate"] as Map<*, *>
        assertEquals(true, gate["parity_de2000_le_1"])
        assertEquals(true, gate["neutral_grey_chroma_le_2"])
        val off = r.copy(userSetting = false)
        assertFalse(off.enabled)
        assertTrue(off.whyOff()!!.contains("camera JPEG"))
        val raw = r.copy(sessionRawUnavailable = "no")
        assertFalse(raw.enabled)
        assertEquals(false, ColourSelfCheck(error = "boom").parityPassed)
    }
}
