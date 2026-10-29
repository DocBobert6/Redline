package com.redline.camera.capability

/**
 * Reads a cached [DeviceProfile] back from its own JSON ([DeviceProfile.toJson]), so the viewfinder
 * starts from the cached profile instead of re-probing every camera (PLAN 12: first frame < 1 s with
 * the profile cached). Returns null for anything malformed or from another schema; the caller rebuilds.
 */
object DeviceProfileParser {
    fun fromJson(json: String?): DeviceProfile? =
        (JsonParse.parseOrNull(json) as? Map<*, *>)?.let { runCatching { fromMap(it) }.getOrNull() }

    fun fromMap(m: Map<*, *>): DeviceProfile? {
        if (m.int("schema") != DeviceProfile.SCHEMA) return null
        val enc = m.obj("encoders")
        return DeviceProfile(
            schema = DeviceProfile.SCHEMA,
            manufacturer = m.str("manufacturer") ?: return null,
            model = m.str("model") ?: return null,
            sdkInt = m.int("sdk_int") ?: return null,
            buildFingerprint = m.str("build_fingerprint") ?: return null,
            cameras = m.list("cameras").mapNotNull { (it as? Map<*, *>)?.let(::camera) },
            encoders = Encoders(
                hevcMain10 = enc?.bool("hevc_main10") == true,
                av1 = enc?.bool("av1") == true,
                apv = enc?.bool("apv") == true
            )
        )
    }

    private fun camera(m: Map<*, *>): CameraProfile? {
        val id = m.str("id") ?: return null
        return CameraProfile(
            id = id,
            facing = m.str("facing") ?: "UNKNOWN",
            hardwareLevel = HardwareLevel.entries.firstOrNull { it.name == m.str("hardware_level") } ?: HardwareLevel.UNKNOWN,
            capabilities = strings(m.list("capabilities")),
            lenses = m.list("lenses").mapNotNull { (it as? Map<*, *>)?.let(::lens) },
            isoRange = intPair(m["iso_range"]),
            exposureRangeNs = longPair(m["exposure_range_ns"]),
            maxFrameDurationNs = (m["max_frame_duration_ns"] as? Number)?.toLong(),
            outputSizes = sizeMap(m.obj("output_sizes")),
            maxResolutionSizes = sizeMap(m.obj("max_resolution_sizes")),
            formats = strings(m.list("formats")),
            fpsRanges = m.list("fps_ranges").mapNotNull { intPair(it) },
            highSpeedFps = m.list("high_speed_fps").mapNotNull { intPair(it) },
            aeModes = strings(m.list("ae_modes")),
            afModes = strings(m.list("af_modes")),
            awbModes = strings(m.list("awb_modes")),
            videoStabilization = strings(m.list("video_stabilization")),
            aeCompensation = intPair(m["ae_compensation"]),
            aeCompensationStep = (m["ae_compensation_step"] as? Number)?.toFloat(),
            tonemapMaxCurvePoints = m.int("tonemap_max_curve_points"),
            dynamicRangeProfiles = strings(m.list("dynamic_range_profiles")),
            colorSpaceProfiles = m.bool("color_space_profiles") == true,
            cctWhiteBalance = m.bool("cct_white_balance") == true,
            lowLightBoost = m.bool("low_light_boost") == true,
            extensions = strings(m.list("extensions")),
            colourCalibrationExposed = m.bool("colour_calibration_exposed") == true
        )
    }

    private fun lens(m: Map<*, *>): LensProfile? {
        val sensor = floats(m.list("sensor_mm"))
        val pixels = intPair(m["pixel_array"])
        val active = intPair(m["active_array"])
        return LensProfile(
            cameraId = m.str("camera_id") ?: return null,
            focalLengthsMm = floats(m.list("focal_lengths_mm")),
            apertures = floats(m.list("apertures")),
            sensorWidthMm = sensor.getOrNull(0) ?: 0f,
            sensorHeightMm = sensor.getOrNull(1) ?: 0f,
            pixelArrayWidth = pixels?.first ?: 0,
            pixelArrayHeight = pixels?.second ?: 0,
            activeWidth = active?.first ?: 0,
            activeHeight = active?.second ?: 0,
            minFocusDistanceDiopters = (m["min_focus_diopters"] as? Number)?.toFloat(),
            hasOis = m.bool("ois") == true,
            hasDistortionModel = m.bool("distortion_model") == true
        )
    }

    private fun strings(l: List<*>): List<String> = l.mapNotNull { it as? String }

    private fun floats(l: List<*>): List<Float> = l.mapNotNull { (it as? Number)?.toFloat() }

    private fun intPair(v: Any?): Pair<Int, Int>? {
        val l = (v as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: return null
        return if (l.size == 2) l[0] to l[1] else null
    }

    private fun longPair(v: Any?): Pair<Long, Long>? {
        val l = (v as? List<*>)?.mapNotNull { (it as? Number)?.toLong() } ?: return null
        return if (l.size == 2) l[0] to l[1] else null
    }

    private fun sizeMap(m: Map<*, *>?): Map<String, List<String>> {
        if (m == null) return emptyMap()
        val out = linkedMapOf<String, List<String>>()
        for ((k, v) in m) out[k.toString()] = strings(v as? List<*> ?: emptyList<Any?>())
        return out
    }
}
