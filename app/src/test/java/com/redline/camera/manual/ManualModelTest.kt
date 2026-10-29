package com.redline.camera.manual

import com.redline.camera.capability.Baseline
import com.redline.camera.capability.CameraProfile
import com.redline.camera.capability.CapabilityReport
import com.redline.camera.capability.CapabilityState
import com.redline.camera.capability.FeaturePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** B14: modes, defaults and the clamp-before-apply rule on the baseline device profile fixture. */
class ManualModelTest {
    private fun caps(
        camera: CameraProfile = Baseline.back,
        aeLock: Boolean = true,
        awbLock: Boolean = true,
        logicalMinFocus: Float? = null,
        focals: List<Float> = listOf(4.5f),
        calibration: String? = "APPROXIMATE"
    ) = ManualCaps.from(camera, aeLock, awbLock, logicalMinFocus, focals, listOf(1.7f), calibration)

    @Test
    fun baselineOffersEveryModeAndControl() {
        val c = caps()
        assertEquals(listOf(ShootMode.AUTO, ShootMode.AV, ShootMode.M), c.modes())
        assertTrue(c.manualExposure)
        assertTrue(c.manualFocus)
        assertTrue(c.ev)
        assertTrue(c.fixedAperture)
        assertEquals("f/1.7 fixed", c.apertureLabel)
        val hidden = c.hidden()
        assertFalse(hidden.containsKey("manual_exposure"))
        assertFalse(hidden.containsKey("manual_focus"))
        assertFalse(hidden.containsKey("ae_lock"))
        assertTrue(hidden.containsKey("kelvin_wb"))
    }

    @Test
    fun minFocusComesFromThePhysicalMainLensWhenTheLogicalCameraOmitsIt() {
        val c = caps(logicalMinFocus = null, focals = listOf(4.5f))
        assertEquals(10f, c.minFocusDiopters!!, 0f)
        assertEquals("physical 2", c.minFocusSource)
        assertEquals("logical", caps(logicalMinFocus = 8f).minFocusSource)
        // Logical focal not matching any lens and several lenses: unknown, so manual focus is hidden.
        val unknown = caps(logicalMinFocus = null, focals = listOf(9.9f))
        assertNull(unknown.minFocusDiopters)
        assertFalse(unknown.manualFocus)
        assertEquals("LENS_INFO_MINIMUM_FOCUS_DISTANCE not reported", unknown.hidden()["manual_focus"])
    }

    @Test
    fun fixedFocusAndMissingManualSensorHideTheirControls() {
        val front = caps(camera = Baseline.front, logicalMinFocus = 0f, focals = listOf(2.6f))
        assertFalse(front.manualFocus)
        val noManual = caps(camera = Baseline.back.copy(capabilities = listOf("BACKWARD_COMPATIBLE")))
        assertEquals(listOf(ShootMode.AUTO, ShootMode.AV), noManual.modes())
        assertEquals("no MANUAL_SENSOR capability", noManual.hidden()["manual_exposure"])
        val noLocks = caps(aeLock = false, awbLock = false)
        assertTrue(noLocks.hidden().containsKey("ae_lock"))
        assertTrue(noLocks.hidden().containsKey("awb_lock"))
    }

    @Test
    fun autoDefaultsApplyNothingManual() {
        val a = ManualModel.resolve(caps(), ManualSettings())
        assertEquals(ShootMode.AUTO, a.mode)
        assertFalse(a.aeOff)
        assertEquals(0, a.evIndex)
        assertNull(a.focusDiopters)
        assertFalse(a.aeLock)
        assertFalse(a.awbLock)
        assertEquals("AUTO", a.awbMode)
        assertTrue(a.withinRanges)
        assertTrue(a.clamps.isEmpty())
    }

    @Test
    fun avAppliesEvAndLocksOnlyWhenReported() {
        val s = ManualSettings(mode = ShootMode.AV, evIndex = 4, aeLock = true, awbLock = true)
        val a = ManualModel.resolve(caps(), s)
        assertEquals(4, a.evIndex)
        assertEquals(4 / 6.0, a.ev, 1e-6)
        assertTrue(a.aeLock)
        assertTrue(a.awbLock)
        val noLocks = ManualModel.resolve(caps(aeLock = false, awbLock = false), s)
        assertFalse(noLocks.aeLock)
        assertFalse(noLocks.awbLock)
        assertTrue(noLocks.withinRanges)
    }

    @Test
    fun outOfRangeValuesAreClampedAndRecorded() {
        val c = caps()
        val s = ManualSettings(mode = ShootMode.M, iso = 100_000, exposureNs = 30_000_000_000L, focusDiopters = 25f)
        val a = ManualModel.resolve(c, s)
        assertEquals(6400, a.stillIso) // fixture ISO 50-6400
        assertEquals(1_000_000_000L, a.stillExposureNs) // fixture max frame duration 1 s
        assertEquals(10f, a.focusDiopters!!, 0f)
        assertEquals(setOf("iso", "exposure_ns", "focus_diopters"), a.clamps.map { it.control }.toSet())
        assertTrue(a.withinRanges)
        val ev = ManualModel.resolve(c, ManualSettings(mode = ShootMode.AV, evIndex = 99))
        assertEquals(24, ev.evIndex)
        assertEquals("ev_index", ev.clamps.single().control)
    }

    @Test
    fun manualModeSplitsPreviewAndStill() {
        val s = ManualSettings(mode = ShootMode.M, iso = 100, exposureNs = 500_000_000L)
        val a = ManualModel.resolve(caps(), s)
        assertTrue(a.aeOff)
        assertEquals(500_000_000L, a.stillExposureNs)
        assertEquals(500_000_000L, a.stillFrameDurationNs)
        assertEquals(ManualMath.PREVIEW_MAX_EXPOSURE_NS, a.previewExposureNs)
        assertEquals(ManualMath.PREVIEW_FRAME_DURATION_NS, a.previewFrameDurationNs)
        assertEquals(1500, a.previewIso)
        assertTrue(a.previewLimited)
        assertNull("M starts in AF", a.focusDiopters)
        assertEquals(0, a.evIndex)
    }

    @Test
    fun unavailableModeFallsBackToAuto() {
        val noManual = caps(camera = Baseline.back.copy(capabilities = listOf("BACKWARD_COMPATIBLE")))
        val a = ManualModel.resolve(noManual, ManualSettings(mode = ShootMode.M))
        assertEquals(ShootMode.AUTO, a.mode)
        assertFalse(a.aeOff)
        assertEquals("mode", a.clamps.single().control)
    }

    @Test
    fun awbPresetsOnlyFromTheListedModes() {
        val rich = caps(camera = Baseline.back.copy(awbModes = listOf("OFF", "AUTO", "DAYLIGHT", "INCANDESCENT")))
        assertEquals(listOf("AUTO", "DAYLIGHT", "INCANDESCENT"), rich.awbPresets)
        val a = ManualModel.resolve(rich, ManualSettings(mode = ShootMode.AV, awb = "INCANDESCENT"))
        assertEquals("INCANDESCENT", a.awbMode)
        val b = ManualModel.resolve(caps(), ManualSettings(mode = ShootMode.AV, awb = "SHADE"))
        assertEquals("AUTO", b.awbMode)
        assertEquals("awb", b.clamps.single().control)
    }

    @Test
    fun resultViolationsCatchValuesOutsideTheRanges() {
        val c = caps()
        assertTrue(ManualModel.violations(c, 400, 10_000_000L, 2f).isEmpty())
        assertEquals(1, ManualModel.violations(c, 12_800, null, null).size)
        assertEquals(1, ManualModel.violations(c, null, 2_000_000_000L, null).size)
        assertEquals(1, ManualModel.violations(c, null, null, 11f).size)
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun reportCarriesRangesModeValuesAndGate() {
        val c = caps()
        val s = ManualSettings(mode = ShootMode.M, iso = 99_999)
        val a = ManualModel.resolve(c, s)
        val r = ManualReport(caps = c).record(a, s).recordResult(emptyList())
        assertEquals(true, r.gatePassed)
        assertEquals(1, r.clampCount)
        val map = CapabilityReport.toMap(Baseline.profile(), CapabilityState(manual = r))
        val manual = map["manual"] as Map<String, Any?>
        assertEquals("M", manual["mode"])
        val gate = manual["gate"] as Map<String, Any?>
        assertEquals(true, gate["controls_within_reported_ranges"])
        val ranges = manual["ranges"] as Map<String, Any?>
        assertEquals(listOf(50, 6400), ranges["iso_range"])
        assertEquals(1, (manual["clamp_events"] as List<*>).size)
        assertEquals(false, r.recordResult(listOf("iso 9 outside")).gatePassed)
        assertNull(ManualReport().gatePassed)
    }

    @Test
    fun proToolsAreOnByDefault() {
        val d = FeaturePolicy.evaluate(Baseline.profile(), Baseline.combos(), Baseline.selfTest()).defaults
        assertTrue(d.gridOn)
        assertTrue(d.levelOn)
        assertTrue(d.histogramOn)
        assertEquals("AUTO", d.startMode)
        assertEquals(true, d.toMap()["histogram_on"])
    }
}
