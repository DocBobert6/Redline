package com.redline.camera.capture

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

    /** B30: [PhotoHeif] encodes with a hardware HEIC encoder, else an HEVC encoder (hardware first): is one usable? */
    fun heifSupport(): Pair<Boolean, String?> = PhotoHeif.support().also { if (it.first) PhotoHeif.prewarm() }

    const val HEIC_MIME = "image/vnd.android.heic"
    const val HEVC_MIME = "video/hevc"
}
