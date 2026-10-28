package com.redline.camera.capability

/** Test fixtures shaped like the baseline device profile (FULL, RAW, 12 MP, 8-bit video, no telephoto). */
object Baseline {
    private fun lens(id: String, focal: Float, width: Float, height: Float, ois: Boolean) = LensProfile(
        cameraId = id,
        focalLengthsMm = listOf(focal),
        apertures = listOf(1.8f),
        sensorWidthMm = width,
        sensorHeightMm = height,
        pixelArrayWidth = 4000,
        pixelArrayHeight = 3000,
        activeWidth = 4000,
        activeHeight = 3000,
        minFocusDistanceDiopters = 10f,
        hasOis = ois,
        hasDistortionModel = false
    )

    val back = CameraProfile(
        id = "0",
        facing = "BACK",
        hardwareLevel = HardwareLevel.FULL,
        capabilities = listOf(
            "BACKWARD_COMPATIBLE",
            "MANUAL_SENSOR",
            "MANUAL_POST_PROCESSING",
            "RAW",
            "LOGICAL_MULTI_CAMERA",
            "STREAM_USE_CASE"
        ),
        lenses = listOf(lens("2", 4.5f, 6.4f, 4.8f, true), lens("3", 2.0f, 4.6f, 3.4f, false)),
        isoRange = 50 to 6400,
        exposureRangeNs = 10_000L to 1_000_000_000L,
        maxFrameDurationNs = 1_000_000_000L,
        outputSizes = mapOf(
            "JPEG" to listOf("4000x3000", "1920x1080"),
            "YUV_420_888" to listOf("4000x3000", "1920x1080"),
            "PRIVATE" to listOf("4000x3000", "3840x2160", "1920x1080"),
            "RAW_SENSOR" to listOf("4000x3000", "4000x2256", "2000x1500")
        ),
        maxResolutionSizes = emptyMap(),
        formats = listOf("JPEG", "PRIVATE", "RAW_SENSOR", "YUV_420_888"),
        fpsRanges = listOf(15 to 30, 24 to 24, 30 to 30, 60 to 60),
        highSpeedFps = listOf(120 to 120, 240 to 240),
        aeModes = listOf("OFF", "ON"),
        afModes = listOf("OFF", "AUTO", "CONTINUOUS_PICTURE"),
        awbModes = listOf("OFF", "AUTO"),
        videoStabilization = listOf("OFF", "ON", "PREVIEW_STABILIZATION"),
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

    val front = back.copy(
        id = "1",
        facing = "FRONT",
        capabilities = listOf("BACKWARD_COMPATIBLE", "MANUAL_SENSOR", "RAW"),
        lenses = listOf(lens("1", 2.6f, 4.7f, 3.5f, false).copy(minFocusDistanceDiopters = 0f))
    )

    fun profile(back: CameraProfile = this.back) = DeviceProfile(
        manufacturer = "Test",
        model = "Baseline",
        sdkInt = 37,
        buildFingerprint = "test/baseline/1:37/X/1:user/release-keys",
        cameras = listOf(back, front),
        encoders = Encoders(hevcMain10 = false, av1 = false, apv = false)
    )

    fun combos(status: ComboStatus = ComboStatus.SUPPORTED): List<ComboResult> = ComboKeys.LABELS.map { (k, label) ->
        ComboResult(k, label, status, ComboSource.VERIFIED, "session query: supported")
    }

    fun selfTest(status: CheckStatus = CheckStatus.PASS): SelfTestReport = SelfTestReport(
        buildFingerprint = "test/baseline/1:37/X/1:user/release-keys",
        cameraId = "0",
        ranAtMs = 1L,
        complete = true,
        checks = SelfTestKeys.LABELS.map { (k, label) -> CheckResult(k, label, status, "ok") }
    )
}
