package com.redline.camera.capability

/** A stream size, independent of android.util.Size so the pickers are unit-testable. */
data class Dim(val width: Int, val height: Int) {
    val area: Long get() = width.toLong() * height

    override fun toString(): String = "${width}x$height"

    companion object {
        fun parse(s: String): Dim? {
            val parts = s.split('x')
            if (parts.size != 2) return null
            val w = parts[0].trim().toIntOrNull() ?: return null
            val h = parts[1].trim().toIntOrNull() ?: return null
            return Dim(w, h)
        }
    }
}

object StreamSizes {
    val FHD = Dim(1920, 1080)
    val UHD = Dim(3840, 2160)

    /** 1920x1080 when offered, else the largest 16:9 size no wider than 1920, else the largest size up to 1080p area. */
    fun preview(sizes: List<Dim>): Dim? {
        if (sizes.isEmpty()) return null
        if (FHD in sizes) return FHD
        val wide = sizes.filter { it.width <= FHD.width && it.width * 9 == it.height * 16 }.maxByOrNull { it.area }
        return wide ?: sizes.filter { it.area <= FHD.area }.maxByOrNull { it.area } ?: sizes.minByOrNull { it.area }
    }

    fun largest(sizes: List<Dim>): Dim? = sizes.maxByOrNull { it.area }

    /** Video sizes to try, best first: UHD then FHD, whichever the stream map offers. */
    fun videoCandidates(sizes: List<Dim>): List<Dim> = listOf(UHD, FHD).filter { it in sizes }
}

enum class ComboStatus { SUPPORTED, UNSUPPORTED, UNKNOWN }

enum class ComboSource(val label: String) {
    VERIFIED("verified (session query)"),
    ASSUMED("assumed (mandatory table)"),
    STATIC("static metadata"),
    NOT_CHECKED("not checked")
}

/** One default stream combination and how its support was established. */
data class ComboResult(
    val key: String,
    val label: String,
    val status: ComboStatus,
    val source: ComboSource,
    val reason: String,
    val outputs: List<String> = emptyList()
) {
    /** True only for a positive answer from the session query, never for a table assumption. */
    val verified: Boolean get() = status == ComboStatus.SUPPORTED && source == ComboSource.VERIFIED

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "key" to key,
        "label" to label,
        "status" to status.name,
        "source" to source.name,
        "source_label" to source.label,
        "reason" to reason,
        "outputs" to outputs
    )
}

object ComboKeys {
    const val PREVIEW_JPEG = "preview_jpeg"
    const val PREVIEW_RAW = "preview_raw"
    const val PREVIEW_YUV_RAW = "preview_yuv_raw"
    const val VIDEO_24 = "video_24"
    const val VIDEO_30 = "video_30"
    const val PREVIEW_STABILIZATION = "preview_stabilization"
    const val PREVIEW_STAB_60 = "preview_stab_60"

    val LABELS = linkedMapOf(
        PREVIEW_JPEG to "Preview + JPEG (max)",
        PREVIEW_RAW to "Preview + RAW_SENSOR (max)",
        PREVIEW_YUV_RAW to "Preview + YUV + RAW_SENSOR",
        VIDEO_24 to "Video 24 fps + preview",
        VIDEO_30 to "Video 30 fps + preview",
        PREVIEW_STABILIZATION to "Preview stabilization (30 fps)",
        PREVIEW_STAB_60 to "Preview stabilization + 60 fps"
    )

    fun label(key: String): String = LABELS[key] ?: key
}

/**
 * Fallback when CameraDeviceSetup is unavailable (API < 35, unsupported, or disabled by a quirk):
 * reads the guaranteed stream combination tables in the CameraDevice docs for the hardware level
 * and capabilities. Results are labelled ASSUMED, never VERIFIED. Combinations the tables do not
 * cover (stabilization, 60 fps) are UNKNOWN.
 */
object MandatoryCombos {
    fun assume(key: String, camera: CameraProfile, why: String): ComboResult {
        val level = camera.hardwareLevel
        val raw = camera.has("RAW")
        val limitedPlus = level.rank >= HardwareLevel.LIMITED.rank

        fun r(status: ComboStatus, reason: String) = ComboResult(key, ComboKeys.label(key), status, ComboSource.ASSUMED, "$reason ($why)")

        return when (key) {
            ComboKeys.PREVIEW_JPEG -> if (level == HardwareLevel.UNKNOWN) {
                ComboResult(key, ComboKeys.label(key), ComboStatus.UNKNOWN, ComboSource.NOT_CHECKED, "hardware level unknown ($why)")
            } else {
                r(ComboStatus.SUPPORTED, "PRIV PREVIEW + JPEG MAXIMUM is guaranteed at every hardware level")
            }
            ComboKeys.PREVIEW_RAW -> if (raw) {
                r(ComboStatus.SUPPORTED, "PRIV PREVIEW + RAW MAXIMUM is guaranteed with the RAW capability")
            } else {
                r(ComboStatus.UNSUPPORTED, "no RAW capability")
            }
            ComboKeys.PREVIEW_YUV_RAW -> if (raw) {
                r(ComboStatus.SUPPORTED, "PRIV PREVIEW + YUV PREVIEW + RAW MAXIMUM is guaranteed with the RAW capability")
            } else {
                r(ComboStatus.UNSUPPORTED, "no RAW capability")
            }
            ComboKeys.VIDEO_24, ComboKeys.VIDEO_30 -> {
                val fps = if (key == ComboKeys.VIDEO_24) 24 else 30
                val fixed = camera.fpsRanges.any { it.first == fps && it.second == fps }
                when {
                    !fixed -> r(ComboStatus.UNSUPPORTED, "no fixed [$fps,$fps] AE range")
                    limitedPlus -> r(ComboStatus.SUPPORTED, "PRIV PREVIEW + PRIV RECORD guaranteed at LIMITED+ (RECORD size only)")
                    else -> r(ComboStatus.UNKNOWN, "not guaranteed at $level")
                }
            }
            ComboKeys.PREVIEW_STABILIZATION -> if ("PREVIEW_STABILIZATION" !in camera.videoStabilization) {
                r(ComboStatus.UNSUPPORTED, "PREVIEW_STABILIZATION mode not listed")
            } else {
                r(ComboStatus.UNKNOWN, "mode listed; stabilization is not covered by the mandatory tables")
            }
            ComboKeys.PREVIEW_STAB_60 -> when {
                "PREVIEW_STABILIZATION" !in camera.videoStabilization -> r(ComboStatus.UNSUPPORTED, "PREVIEW_STABILIZATION mode not listed")
                camera.fpsRanges.none { it.second >= 60 } -> r(ComboStatus.UNSUPPORTED, "no 60 fps AE range")
                else -> r(ComboStatus.UNKNOWN, "not covered by the mandatory tables")
            }
            else -> ComboResult(key, ComboKeys.label(key), ComboStatus.UNKNOWN, ComboSource.NOT_CHECKED, "unknown combination")
        }
    }

    fun assumeAll(camera: CameraProfile, why: String): List<ComboResult> = ComboKeys.LABELS.keys.map { assume(it, camera, why) }
}
