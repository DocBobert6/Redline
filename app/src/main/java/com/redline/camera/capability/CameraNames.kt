package com.redline.camera.capability

/**
 * Camera2 integer constants -> readable names for the capability report.
 * Literal values (not SDK constants) so newer keys still name correctly on older
 * compile targets and the tables are unit-testable off-device.
 */
object CameraNames {
    val CAPABILITIES = mapOf(
        0 to "BACKWARD_COMPATIBLE",
        1 to "MANUAL_SENSOR",
        2 to "MANUAL_POST_PROCESSING",
        3 to "RAW",
        4 to "PRIVATE_REPROCESSING",
        5 to "READ_SENSOR_SETTINGS",
        6 to "BURST_CAPTURE",
        7 to "YUV_REPROCESSING",
        8 to "DEPTH_OUTPUT",
        9 to "CONSTRAINED_HIGH_SPEED_VIDEO",
        10 to "MOTION_TRACKING",
        11 to "LOGICAL_MULTI_CAMERA",
        12 to "MONOCHROME",
        13 to "SECURE_IMAGE_DATA",
        14 to "SYSTEM_CAMERA",
        15 to "OFFLINE_PROCESSING",
        16 to "ULTRA_HIGH_RESOLUTION_SENSOR",
        17 to "REMOSAIC_REPROCESSING",
        18 to "DYNAMIC_RANGE_TEN_BIT",
        19 to "STREAM_USE_CASE",
        20 to "COLOR_SPACE_PROFILES"
    )

    val AE_MODES = mapOf(
        0 to "OFF",
        1 to "ON",
        2 to "ON_AUTO_FLASH",
        3 to "ON_ALWAYS_FLASH",
        4 to "ON_AUTO_FLASH_REDEYE",
        5 to "ON_EXTERNAL_FLASH",
        6 to "ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY"
    )

    val AF_MODES = mapOf(
        0 to "OFF",
        1 to "AUTO",
        2 to "MACRO",
        3 to "CONTINUOUS_VIDEO",
        4 to "CONTINUOUS_PICTURE",
        5 to "EDOF"
    )

    val AWB_MODES = mapOf(
        0 to "OFF",
        1 to "AUTO",
        2 to "INCANDESCENT",
        3 to "FLUORESCENT",
        4 to "WARM_FLUORESCENT",
        5 to "DAYLIGHT",
        6 to "CLOUDY_DAYLIGHT",
        7 to "TWILIGHT",
        8 to "SHADE"
    )

    val VIDEO_STABILIZATION = mapOf(0 to "OFF", 1 to "ON", 2 to "PREVIEW_STABILIZATION")

    val FORMATS = mapOf(
        0x20 to "RAW_SENSOR",
        0x25 to "RAW10",
        0x26 to "RAW12",
        0x100 to "JPEG",
        0x23 to "YUV_420_888",
        0x22 to "PRIVATE",
        0x48454946 to "HEIC",
        0x1005 to "JPEG_R",
        0x1006 to "HEIC_ULTRAHDR",
        0x44363159 to "DEPTH16",
        0x69656963 to "DEPTH_JPEG"
    )

    val EXTENSIONS = mapOf(0 to "AUTOMATIC", 1 to "FACE_RETOUCH", 2 to "BOKEH", 3 to "HDR", 4 to "NIGHT", 5 to "EYES_FREE_VIDEOGRAPHY")

    /** DynamicRangeProfiles bit flags. */
    val DYNAMIC_RANGE = mapOf(
        1L to "STANDARD",
        2L to "HLG10",
        4L to "HDR10",
        8L to "HDR10_PLUS",
        16L to "DOLBY_VISION_10B_HDR_REF",
        32L to "DOLBY_VISION_10B_HDR_REF_PO",
        64L to "DOLBY_VISION_10B_HDR_OEM",
        128L to "DOLBY_VISION_10B_HDR_OEM_PO",
        256L to "DOLBY_VISION_8B_HDR_REF",
        512L to "DOLBY_VISION_8B_HDR_REF_PO",
        1024L to "DOLBY_VISION_8B_HDR_OEM",
        2048L to "DOLBY_VISION_8B_HDR_OEM_PO"
    )

    const val LOW_LIGHT_BOOST_AE_MODE = 6

    /** Unknown values stay visible as UNKNOWN_<n> instead of disappearing. */
    fun <K : Number> names(values: Iterable<K>, table: Map<K, String>): List<String> =
        values.distinct().sortedBy { it.toLong() }.map { table[it] ?: "UNKNOWN_$it" }
}
