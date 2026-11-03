package com.redline.camera.capability

import com.redline.camera.manual.ManualCaps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B25 manual-focus regression. Camera2 leaves out permission-gated keys (LENS_INFO_MINIMUM_FOCUS_DISTANCE among them)
 * until the CAMERA permission is held. Every first launch probed the profile and read the viewfinder's manual caps
 * before the prompt was answered, cached the incomplete profile, and hid manual focus until a later launch.
 */
class ProfilePermissionTest {
    private val fp = Baseline.profile().buildFingerprint

    /** What the probe sees before the grant: no minimum focus distance on any lens, and none on the logical camera. */
    private val probedBeforeGrant = Baseline.profile(
        Baseline.back.copy(lenses = Baseline.back.lenses.map { it.copy(minFocusDistanceDiopters = null) })
    ).copy(probedWithCameraPermission = false)

    private fun caps(profile: DeviceProfile, logicalMinFocus: Float?) = ManualCaps.from(
        profile.cameras.first(),
        aeLockAvailable = true,
        awbLockAvailable = true,
        logicalMinFocus = logicalMinFocus,
        logicalFocals = profile.cameras.first().lenses.first().focalLengthsMm,
        logicalApertures = listOf(1.7f),
        focusCalibration = "APPROXIMATE"
    )

    @Test
    fun profileProbedWithoutPermissionIsNotReusedOnceItIsGranted() {
        assertFalse(DeviceProfile.cacheReusable(probedBeforeGrant, fp, permissionNow = true))
        // Still reused while the permission is missing (nothing better can be read yet).
        assertTrue(DeviceProfile.cacheReusable(probedBeforeGrant, fp, permissionNow = false))
        assertTrue(DeviceProfile.cacheReusable(Baseline.profile(), fp, permissionNow = true))
        assertFalse(DeviceProfile.cacheReusable(Baseline.profile(), "other/build", permissionNow = true))
        assertFalse(DeviceProfile.cacheReusable(null, fp, permissionNow = true))
    }

    @Test
    fun permissionFlagRoundTripsAndOldCachesCountAsProbedWithout() {
        assertEquals(probedBeforeGrant, DeviceProfileParser.fromJson(probedBeforeGrant.toJson()))
        assertEquals(true, DeviceProfileParser.fromJson(Baseline.profile().toJson())?.probedWithCameraPermission)
        val noFlag = Baseline.profile().toJson().replace("\"probed_with_camera_permission\": true,", "")
        val parsed = DeviceProfileParser.fromJson(noFlag)
        assertEquals(false, parsed?.probedWithCameraPermission)
        assertFalse(DeviceProfile.cacheReusable(parsed, fp, permissionNow = true))
    }

    @Test
    fun capsReadBeforeTheGrantHideManualFocusAndTheRereadRestoresIt() {
        // The 2026-11-03 09:29 report: min_focus_diopters null, source "not reported", manual focus hidden.
        val before = caps(probedBeforeGrant, logicalMinFocus = null)
        assertEquals(null, before.minFocusDiopters)
        assertEquals("not reported", before.minFocusSource)
        assertFalse(before.manualFocus)
        assertEquals("LENS_INFO_MINIMUM_FOCUS_DISTANCE not reported", before.hidden()["manual_focus"])
        // After the grant the logical camera reports it (20 D on the baseline device, the 2026-11-02 11:36 report).
        val after = caps(Baseline.profile(), logicalMinFocus = 20f)
        assertEquals(20f, after.minFocusDiopters)
        assertEquals("logical", after.minFocusSource)
        assertTrue(after.manualFocus)
        assertEquals(20f, after.focusLadder.last())
    }
}
