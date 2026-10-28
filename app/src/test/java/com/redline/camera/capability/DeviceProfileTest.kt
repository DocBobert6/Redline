package com.redline.camera.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfileTest {
    /** Baseline-class main camera: 1/2" sensor, 4.5 mm lens. */
    private val main = LensProfile(
        cameraId = "2",
        focalLengthsMm = listOf(4.5f),
        apertures = listOf(1.7f),
        sensorWidthMm = 6.4f,
        sensorHeightMm = 4.8f,
        pixelArrayWidth = 4000,
        pixelArrayHeight = 3000,
        activeWidth = 4000,
        activeHeight = 3000,
        minFocusDistanceDiopters = 10f,
        hasOis = true,
        hasDistortionModel = false
    )

    private fun camera(lenses: List<LensProfile> = listOf(main)) = CameraProfile(
        id = "0",
        facing = "BACK",
        hardwareLevel = HardwareLevel.FULL,
        capabilities = listOf("BACKWARD_COMPATIBLE", "RAW"),
        lenses = lenses,
        isoRange = 50 to 6400,
        exposureRangeNs = 10_000L to 1_000_000_000L,
        maxFrameDurationNs = 1_000_000_000L,
        outputSizes = mapOf("JPEG" to listOf("4000x3000")),
        maxResolutionSizes = emptyMap(),
        formats = listOf("JPEG", "RAW_SENSOR"),
        fpsRanges = listOf(24 to 24, 30 to 30),
        highSpeedFps = emptyList(),
        aeModes = listOf("OFF", "ON"),
        afModes = listOf("OFF", "AUTO"),
        awbModes = listOf("OFF", "AUTO"),
        videoStabilization = listOf("OFF", "ON"),
        aeCompensation = -24 to 24,
        aeCompensationStep = 1f / 6f,
        tonemapMaxCurvePoints = 64,
        dynamicRangeProfiles = listOf("STANDARD"),
        colorSpaceProfiles = false,
        cctWhiteBalance = false,
        lowLightBoost = false,
        extensions = emptyList(),
        colourCalibrationExposed = false
    )

    @Test
    fun equivalentFocalMatchesPlanMath() {
        // diag 8.0 mm -> 4.5 * 43.27 / 8 = 24.3 mm
        assertEquals(24.3f, main.equivalentDiagonalMm, 0.05f)
        // 3:2 by width: 4.5 * 36 / 6.4 = 25.3 mm (PLAN 3.3 estimates ~26)
        assertEquals(25.3f, main.equivalent32Mm, 0.05f)
        assertEquals(70.8f, main.horizontalFovDeg, 0.2f)
        assertEquals(false, main.fixedFocus)
    }

    @Test
    fun unreportedFocusRangeIsUnknownNotFixed() {
        assertNull(main.copy(minFocusDistanceDiopters = null).fixedFocus)
        assertEquals(true, main.copy(minFocusDistanceDiopters = 0f).fixedFocus)
    }

    @Test
    fun activeAreaNarrowerThanPixelArrayLengthensFocal() {
        val cropped = main.copy(activeWidth = 3600)
        assertTrue(cropped.equivalent32Mm > main.equivalent32Mm)
    }

    @Test
    fun zeroOrMissingSensorSizeGivesZeroNotInfinity() {
        val broken = main.copy(sensorWidthMm = 0f, sensorHeightMm = 0f)
        assertEquals(0f, broken.equivalentDiagonalMm, 0f)
        assertEquals(0f, broken.equivalent32Mm, 0f)
    }

    @Test
    fun namesKeepUnknownValuesVisible() {
        assertEquals(listOf("BACKWARD_COMPATIBLE", "RAW", "UNKNOWN_99"), CameraNames.names(listOf(3, 0, 99, 3), CameraNames.CAPABILITIES))
        assertEquals(listOf("STANDARD", "HLG10"), CameraNames.names(setOf(2L, 1L), CameraNames.DYNAMIC_RANGE))
    }

    @Test
    fun jsonIsWellFormedAndCarriesEveryCamera() {
        val profile = DeviceProfile(
            manufacturer = "Acme",
            model = "Phone \"X\"",
            sdkInt = 36,
            buildFingerprint = "acme/x:16",
            cameras = listOf(camera()),
            encoders = Encoders(hevcMain10 = true, av1 = false, apv = false)
        )
        val json = profile.toJson()
        assertTrue(json.contains("\"model\": \"Phone \\\"X\\\"\""))
        assertTrue(json.contains("\"equivalent_diagonal_mm\": 24.3"))
        assertTrue(json.contains("\"fps_ranges\": [[24, 24], [30, 30]]"))
        assertTrue(json.contains("\"max_resolution_sizes\": {}"))
        assertEquals(json.count { it == '{' }, json.count { it == '}' })
        assertEquals(json.count { it == '[' }, json.count { it == ']' })
    }

    @Test
    fun jsonEscapesControlCharacters() {
        assertEquals("\"a\\nb\\u0001\"", Json.string("a\nb\u0001"))
        assertEquals("null", Json.value(Float.NaN))
    }
}
