package com.redline.camera.capture

import android.media.MediaCodecList

/** B25: Info-screen keys for the save-format buttons, the viewfinder's cycle order, and the HEIF encoder check. */
object SaveFormatsUi {
    const val FORMAT = "format"
    const val PHOTO = "photo"
    const val ULTRA_HDR = "ultra_hdr"
    const val DNG_SOURCE = "dng_source"
    const val TAG_PREFIX = "save_format_"

    fun nextFormat(f: SaveFormat): SaveFormat = when (f) {
        SaveFormat.JPEG -> SaveFormat.JPEG_DNG
        SaveFormat.JPEG_DNG -> SaveFormat.DNG
        SaveFormat.DNG -> SaveFormat.JPEG
    }

    /** androidx.heifwriter encodes with an HEIC image encoder, else an HEVC video encoder: is either present? */
    fun heifSupport(): Pair<Boolean, String?> = try {
        val encoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder }
        fun has(mime: String) = encoders.any { info -> info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
        when {
            has(HEIC_MIME) -> true to null
            has(HEVC_MIME) -> true to null
            else -> false to "no HEIC or HEVC encoder"
        }
    } catch (e: Exception) {
        false to "${e.javaClass.simpleName}: ${e.message}"
    }

    const val HEIC_MIME = "image/vnd.android.heic"
    const val HEVC_MIME = "video/hevc"
}
