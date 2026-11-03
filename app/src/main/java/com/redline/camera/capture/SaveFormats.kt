package com.redline.camera.capture

import com.redline.camera.capability.Dim
import java.io.RandomAccessFile

/**
 * B25: what a still is saved as. Persisted on the Info screen (replaces the B13 viewfinder RAW toggle, which was not
 * persisted). Pure data and rules; the saver ([StillSaver]) does the writing.
 *
 * - Save formats: JPEG (the photo only), JPEG + DNG, or DNG only. The photo is JPEG or HEIF.
 * - Ultra HDR: a gain map Redline computes in the RAW render (continuing the tone curve past the highlight knee),
 *   encoded with the platform's Ultra HDR JPEG encoder (Bitmap.setGainmap, API 34+). JPEG only; the SDR base image
 *   is the same JPEG as without it.
 * - DNG source: the single reference frame, or the burst merge (written at the merge's x16 precision with
 *   WhiteLevel/BlackLevel patched to match, else divided back to the sensor scale). A super-resolution shot's DNG stays
 *   frame 0. Every DNG is the full sensor, uncropped, with no DefaultCrop tag (B13/B14).
 */
enum class SaveFormat(val key: String, val label: String) {
    JPEG("jpeg", "JPEG"),
    JPEG_DNG("jpeg_dng", "JPEG + DNG"),
    DNG("dng", "DNG");

    val photo: Boolean get() = this != DNG
    val dng: Boolean get() = this != JPEG

    companion object {
        fun of(key: String?): SaveFormat = entries.firstOrNull { it.key == key } ?: JPEG
    }
}

enum class PhotoFormat(val key: String, val label: String, val mime: String, val ext: String) {
    JPEG("jpeg", "JPEG", "image/jpeg", "jpg"),
    HEIF("heif", "HEIF", "image/heic", "heic");

    companion object {
        fun of(key: String?): PhotoFormat = entries.firstOrNull { it.key == key } ?: JPEG
    }
}

enum class DngSource(val key: String, val label: String) {
    SINGLE("single", "single frame"),
    MERGE("merge", "burst merge");

    companion object {
        fun of(key: String?): DngSource = entries.firstOrNull { it.key == key } ?: SINGLE
    }
}

/** What this phone can do for [SaveSettings]. */
data class SaveCaps(
    /** RAW_SENSOR in the session (the camera has the RAW capability and it was not demoted). */
    val raw: Boolean,
    /** An HEIC image encoder or an HEVC video encoder with YUV buffer input exists (B30: [PhotoHeif]). */
    val heif: Boolean,
    /** API 34+: android.graphics.Gainmap and the platform's Ultra HDR JPEG encoder. */
    val ultraHdr: Boolean,
    /** Why HEIF is not offered (null when it is). */
    val heifReason: String? = null
)

/** The user's choices, as persisted. [effective] applies what the phone can do. */
data class SaveSettings(
    val format: SaveFormat = SaveFormat.JPEG,
    val photoFormat: PhotoFormat = PhotoFormat.JPEG,
    val ultraHdr: Boolean = ULTRA_HDR_DEFAULT,
    val dngSource: DngSource = DngSource.SINGLE
) {
    /** What a shot really does on this phone: no DNG without RAW (DNG only falls back to JPEG), HEIF and Ultra HDR only where available. */
    fun effective(caps: SaveCaps): Effective {
        val format = if (caps.raw) format else SaveFormat.JPEG
        val photo = if (photoFormat == PhotoFormat.HEIF && !caps.heif) PhotoFormat.JPEG else photoFormat
        val uhdr = ultraHdr && caps.ultraHdr && format.photo && photo == PhotoFormat.JPEG
        val notes = buildList {
            if (format != this@SaveSettings.format) add("no RAW stream: ${this@SaveSettings.format.label} saved as JPEG")
            if (photo != photoFormat) add("HEIF unavailable (${caps.heifReason ?: "no HEVC/HEIC encoder"}): JPEG")
            if (ultraHdr && !caps.ultraHdr) add("Ultra HDR needs Android 14 (API 34)")
            if (ultraHdr && caps.ultraHdr && photo == PhotoFormat.HEIF) add("Ultra HDR is JPEG only: HEIF saved without a gain map")
        }
        return Effective(format, photo, uhdr, dngSource, notes)
    }

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "save_formats" to format.key,
        "photo_format" to photoFormat.key,
        "ultra_hdr" to ultraHdr,
        "dng_source" to dngSource.key
    )

    data class Effective(
        val format: SaveFormat,
        val photoFormat: PhotoFormat,
        val ultraHdr: Boolean,
        val dngSource: DngSource,
        val notes: List<String> = emptyList()
    ) {
        val photo: Boolean get() = format.photo
        val dng: Boolean get() = format.dng

        fun label(): String = buildString {
            append(if (photo) photoFormat.label else "")
            if (photo && ultraHdr) append(" (Ultra HDR)")
            if (photo && dng) append(" + ")
            if (dng) append("DNG (${dngSource.label})")
        }

        fun toMap(): Map<String, Any?> = linkedMapOf(
            "save_formats" to format.key,
            "photo_format" to if (photo) photoFormat.key else null,
            "ultra_hdr" to ultraHdr,
            "dng_source" to if (dng) dngSource.key else null,
            "notes" to notes
        )
    }

    companion object {
        /**
         * Ultra HDR default ON. Decided from the host benchmark (work/b25_report.md): the gain map adds 41 ms (typical scene,
         * 42% of cells above the knee) to 70 ms (every pixel above it) of single-thread render work at the 24 mm still
         * size, 3-6 ms to encode the map and ~3 ms for its JPEG, inside the 40-70 ms budget that keeps a lone 24/35 mm
         * save under 3 s with the defaults (B24 gate: 2.9 s max).
         */
        const val ULTRA_HDR_DEFAULT = true

        /** Headroom cap and the minimum worth a gain map (stops). */
        const val HEADROOM_CAP_STOPS = 2.0f
        const val HEADROOM_MIN_STOPS = 0.2f

        /** The tone curve's highlight knee (scene EV at unit gain); the HDR rendition continues the curve past it. */
        const val KNEE_EV = -1.5f
    }
}

/**
 * B25: Ultra HDR metadata for a map encoded by [com.redline.camera.nativebridge.RedlineNative.finishGainMap]
 * (1 channel, log-encoded, ratioMin 1, gamma 1, epsilon 0): ratioMax = displayRatioForFullHdr = 2^headroom,
 * minDisplayRatioForHdrTransition = 1 (the map starts to apply on any HDR headroom).
 */
data class GainmapParams(val headroomStops: Double) {
    val ratioMin: Float get() = 1f
    val ratioMax: Float get() = Math.pow(2.0, headroomStops).toFloat()
    val gamma: Float get() = 1f
    val epsilonSdr: Float get() = 0f
    val epsilonHdr: Float get() = 0f
    val displayRatioForFullHdr: Float get() = ratioMax
    val minDisplayRatioForHdrTransition: Float get() = 1f
}

/** B25: one shot's formats for the report. */
data class FormatsShot(
    val settings: SaveSettings.Effective,
    val photoBytes: Long? = null,
    val dngBytes: Long? = null,
    val photoSize: Dim? = null,
    /** Ultra HDR requested for this shot (effective setting and a RAW render). */
    val ultraHdrWanted: Boolean = false,
    val ultraHdrWritten: Boolean = false,
    val headroomStops: Double? = null,
    val p999Stops: Double? = null,
    val kneeSlope: Double? = null,
    val aboveKneeFraction: Double? = null,
    /** Why no gain map was written (headroom below the minimum, no highlights, camera JPEG, encoder, ...). */
    val ultraHdrSkip: String? = null,
    /** Gain map work: encode the cells, warp (RAW feel), bitmap; plus the extra encode time, ms. */
    val gainmapMs: Long? = null,
    /** After the EXIF write: MPF and the gain map image still in the file. */
    val gainmapVerified: Boolean? = null,
    val photoEncodeMs: Long? = null,
    val dngSource: String? = null,
    /** "patched" (x16 data, WhiteLevel/BlackLevel scaled) or "divided" (data back at the sensor scale); null = single frame. */
    val dngLevels: String? = null,
    val dngMs: Long? = null,
    val heifError: String? = null,
    /** B30: the HEIF writer's codec, input mode, grid and time split (null for JPEG). */
    val heif: Map<String, Any?>? = null
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "formats" to settings.toMap(),
        "photo_bytes" to photoBytes,
        "photo_size" to photoSize?.toString(),
        "dng_bytes" to dngBytes,
        "ultra_hdr" to linkedMapOf(
            "wanted" to ultraHdrWanted,
            "written" to ultraHdrWritten,
            "headroom_stops" to headroomStops?.let { round3(it) },
            "p999_stops" to p999Stops?.let { round3(it) },
            "knee_slope" to kneeSlope?.let { round3(it) },
            "above_knee_fraction" to aboveKneeFraction?.let { round3(it) },
            "skip_reason" to ultraHdrSkip,
            "gainmap_ms" to gainmapMs,
            "verified_after_exif" to gainmapVerified
        ),
        "photo_encode_ms" to photoEncodeMs,
        "dng_source" to dngSource,
        "dng_levels" to dngLevels,
        "dng_ms" to dngMs,
        "heif_error" to heifError,
        "heif" to heif
    )

    fun summary(): String = buildString {
        append(settings.label())
        if (ultraHdrWritten) append(" · Ultra HDR +%.1f stops".format(headroomStops ?: 0.0))
        if (ultraHdrWanted && !ultraHdrWritten && ultraHdrSkip != null) append(" · no gain map ($ultraHdrSkip)")
    }

    private fun round3(v: Double) = Math.round(v * 1000.0) / 1000.0
}

/** B25: the report's "save_formats" section: settings, availability, gain map statistics, the last shots. */
data class SaveFormatsReport(
    val settings: SaveSettings = SaveSettings(),
    val caps: SaveCaps? = null,
    val shots: Int = 0,
    val ultraHdrWritten: Int = 0,
    val ultraHdrSkipped: Int = 0,
    val totalGainmapMs: Long = 0,
    val maxGainmapMs: Long = 0,
    val lastSkip: String? = null,
    val recent: List<FormatsShot> = emptyList()
) {
    fun record(f: FormatsShot): SaveFormatsReport {
        val ms = f.gainmapMs ?: 0L
        return copy(
            shots = shots + 1,
            ultraHdrWritten = ultraHdrWritten + if (f.ultraHdrWritten) 1 else 0,
            ultraHdrSkipped = ultraHdrSkipped + if (f.ultraHdrWanted && !f.ultraHdrWritten) 1 else 0,
            totalGainmapMs = totalGainmapMs + ms,
            maxGainmapMs = maxOf(maxGainmapMs, ms),
            lastSkip = if (f.ultraHdrWanted && !f.ultraHdrWritten) f.ultraHdrSkip else lastSkip,
            recent = (recent + f).takeLast(RECENT)
        )
    }

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "settings" to settings.toMap(),
        "effective" to caps?.let { settings.effective(it).toMap() },
        "available" to
            caps?.let { linkedMapOf("raw" to it.raw, "heif" to it.heif, "heif_reason" to it.heifReason, "ultra_hdr" to it.ultraHdr) },
        "shots" to shots,
        "ultra_hdr_written" to ultraHdrWritten,
        "ultra_hdr_skipped" to ultraHdrSkipped,
        "avg_gainmap_ms" to if (ultraHdrWritten + ultraHdrSkipped > 0) totalGainmapMs / (ultraHdrWritten + ultraHdrSkipped) else null,
        "max_gainmap_ms" to maxGainmapMs,
        "last_skip_reason" to lastSkip,
        "last" to recent.lastOrNull()?.toMap(),
        "recent_shots" to recent.map { it.toMap() }
    )

    companion object {
        const val RECENT = 10
    }
}

/**
 * B25: a DNG of the burst merge is written by DngCreator with the merge's samples (x [scale]) and the camera's levels;
 * [patch] then scales WhiteLevel (0xC61D) and BlackLevel (0xC61A) in every IFD of the file in place, so the levels match
 * the data. Returns false (file untouched) when a value cannot be scaled in its stored type or the TIFF is not as
 * expected; the caller then writes the DNG again with the data divided back to the sensor scale.
 */
object DngLevels {
    const val TAG_BLACK_LEVEL = 0xC61A
    const val TAG_WHITE_LEVEL = 0xC61D
    private const val TAG_SUB_IFDS = 0x014A
    private const val SHORT = 3
    private const val LONG = 4
    private const val RATIONAL = 5

    fun patch(file: java.io.File, scale: Int): Boolean = RandomAccessFile(file, "rw").use { f ->
        val bytes = ByteArray(minOf(f.length(), Int.MAX_VALUE.toLong()).toInt())
        f.readFully(bytes)
        val edits = plan(bytes, scale) ?: return@use false
        for ((offset, value) in edits) {
            f.seek(offset.toLong())
            f.write(value)
        }
        true
    }

    /** The in-place writes ((offset, bytes)) that scale both levels, or null when the file cannot be patched. */
    fun plan(b: ByteArray, scale: Int): List<Pair<Int, ByteArray>>? {
        if (b.size < 8 || scale < 1) return null
        val le = when {
            b[0] == 'I'.code.toByte() && b[1] == 'I'.code.toByte() -> true
            b[0] == 'M'.code.toByte() && b[1] == 'M'.code.toByte() -> false
            else -> return null
        }
        val r = Reader(b, le)
        if (r.u16(2) != 42) return null
        val edits = ArrayList<Pair<Int, ByteArray>>()
        var sawWhite = false
        var sawBlack = false
        val queue = ArrayDeque<Long>().apply { add(r.u32(4)) }
        val seen = HashSet<Long>()
        while (queue.isNotEmpty()) {
            val ifd = queue.removeFirst()
            if (ifd <= 0 || ifd + 2 > b.size || !seen.add(ifd)) continue
            val n = r.u16(ifd.toInt())
            for (i in 0 until n) {
                val e = ifd.toInt() + 2 + i * 12
                if (e + 12 > b.size) return null
                val tag = r.u16(e)
                val type = r.u16(e + 2)
                val count = r.u32(e + 4).toInt()
                val size = typeSize(type) * count
                val dataAt = if (size <= 4) e + 8 else r.u32(e + 8).toInt()
                if (dataAt < 0 || dataAt + size > b.size) return null
                when (tag) {
                    TAG_SUB_IFDS -> for (k in 0 until count) {
                        queue.add(
                            if (type ==
                                SHORT
                            ) {
                                r.u16(dataAt + 2 * k).toLong()
                            } else {
                                r.u32(dataAt + 4 * k)
                            }
                        )
                    }
                    TAG_WHITE_LEVEL, TAG_BLACK_LEVEL -> {
                        if (tag == TAG_WHITE_LEVEL) sawWhite = true else sawBlack = true
                        for (k in 0 until count) {
                            when (type) {
                                SHORT -> {
                                    val v = r.u16(dataAt + 2 * k).toLong() * scale
                                    if (v > 0xFFFF) return null
                                    edits += (dataAt + 2 * k) to r.enc16(v.toInt())
                                }
                                LONG -> {
                                    val v = r.u32(dataAt + 4 * k) * scale
                                    if (v > 0xFFFFFFFFL) return null
                                    edits += (dataAt + 4 * k) to r.enc32(v)
                                }
                                RATIONAL -> {
                                    val v = r.u32(dataAt + 8 * k) * scale
                                    if (v > 0xFFFFFFFFL) return null
                                    edits += (dataAt + 8 * k) to r.enc32(v)
                                }
                                else -> return null
                            }
                        }
                    }
                }
            }
            val next = ifd.toInt() + 2 + n * 12
            if (next + 4 <= b.size) queue.add(r.u32(next))
        }
        // BlackLevel may be absent (0); WhiteLevel must be there, or the levels would not describe the x16 data.
        if (!sawBlack && edits.isEmpty()) return null
        return if (sawWhite) edits else null
    }

    private fun typeSize(type: Int): Int = when (type) {
        1, 2, 6, 7 -> 1
        3, 8 -> 2
        4, 9, 11, 13 -> 4
        5, 10, 12 -> 8
        else -> 1
    }

    private class Reader(val b: ByteArray, val le: Boolean) {
        fun u8(i: Int) = b[i].toInt() and 0xFF
        fun u16(i: Int) = if (le) u8(i) or (u8(i + 1) shl 8) else (u8(i) shl 8) or u8(i + 1)
        fun u32(i: Int): Long = if (le) {
            u16(i).toLong() or (u16(i + 2).toLong() shl 16)
        } else {
            (u16(i).toLong() shl 16) or u16(i + 2).toLong()
        }

        fun enc16(v: Int) = if (le) byteArrayOf(v.toByte(), (v shr 8).toByte()) else byteArrayOf((v shr 8).toByte(), v.toByte())
        fun enc32(v: Long): ByteArray {
            val x = v.toInt()
            return if (le) {
                byteArrayOf(x.toByte(), (x shr 8).toByte(), (x shr 16).toByte(), (x shr 24).toByte())
            } else {
                byteArrayOf((x shr 24).toByte(), (x shr 16).toByte(), (x shr 8).toByte(), x.toByte())
            }
        }
    }
}

/**
 * B25: JPEG structure checks. [inspect] says whether a file is an Ultra HDR JPEG (MPF in the primary image, the hdrgm
 * XMP namespace, a second image after the primary's EOI); [exifPayload] returns the APP1 Exif payload ("Exif\0\0" + TIFF),
 * which is what HeifWriter.addExifData takes.
 */
object JpegStructure {
    data class Info(val mpf: Boolean, val hdrgm: Boolean, val images: Int, val primaryEnd: Int) {
        val ultraHdr: Boolean get() = mpf && hdrgm && images >= 2
    }

    fun inspect(b: ByteArray): Info {
        val primaryEnd = primaryEnd(b)
        var mpf = false
        var hdrgm = false
        var i = 2
        while (i + 4 <= primaryEnd && (b[i].toInt() and 0xFF) == 0xFF) {
            val marker = b[i + 1].toInt() and 0xFF
            if (marker == 0xDA) break
            val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            if (marker == 0xE2 && startsWith(b, i + 4, "MPF\u0000")) mpf = true
            if (marker == 0xE1 && contains(b, i + 4, minOf(i + 2 + len, b.size), "hdrgm")) hdrgm = true
            i += 2 + len
        }
        var images = if (primaryEnd > 0) 1 else 0
        var j = primaryEnd
        while (j in 1 until b.size - 1) {
            if ((b[j].toInt() and 0xFF) == 0xFF && (b[j + 1].toInt() and 0xFF) == 0xD8) {
                images++
                val end = primaryEndFrom(b, j)
                if (end <= j) break
                j = end
            } else {
                j++
            }
        }
        return Info(mpf, hdrgm, images, primaryEnd)
    }

    fun exifPayload(b: ByteArray): ByteArray? {
        var i = 2
        if (b.size < 4 || (b[0].toInt() and 0xFF) != 0xFF || (b[1].toInt() and 0xFF) != 0xD8) return null
        while (i + 4 <= b.size && (b[i].toInt() and 0xFF) == 0xFF) {
            val marker = b[i + 1].toInt() and 0xFF
            if (marker == 0xDA) return null
            val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            if (marker == 0xE1 && startsWith(b, i + 4, "Exif\u0000\u0000")) return b.copyOfRange(i + 4, minOf(i + 2 + len, b.size))
            i += 2 + len
        }
        return null
    }

    /**
     * Whether the primary image's MPF index points at a second JPEG (the gain map) that is really there: its MP entry's
     * offset (from the MPF header's byte-order mark) lands on an SOI inside the file and its size fits. Reads only the
     * primary's segments, so it is cheap on a full-size still.
     */
    fun mpfSecondImageOk(b: ByteArray): Boolean = mpfSecondImageOk(b, b.size.toLong()) { b[it.toInt()].toInt() and 0xFF }

    /** The same on the file's first bytes [head]; [byteAt] reads one byte anywhere in the file of [fileLength] bytes. */
    fun mpfSecondImageOk(head: ByteArray, fileLength: Long, byteAt: (Long) -> Int): Boolean {
        val b = head
        var i = 2
        if (b.size < 4 || (b[0].toInt() and 0xFF) != 0xFF || (b[1].toInt() and 0xFF) != 0xD8) return false
        while (i + 4 <= b.size && (b[i].toInt() and 0xFF) == 0xFF) {
            val marker = b[i + 1].toInt() and 0xFF
            if (marker == 0xDA) return false
            val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            if (marker == 0xE2 && startsWith(b, i + 4, "MPF\u0000")) {
                return mpfEntryOk(b, i + 8, minOf(i + 2 + len, b.size), fileLength, byteAt)
            }
            i += 2 + len
        }
        return false
    }

    private fun mpfEntryOk(b: ByteArray, tiff: Int, end: Int, fileLength: Long, byteAt: (Long) -> Int): Boolean {
        if (tiff + 8 > end) return false
        val le = b[tiff] == 'I'.code.toByte()
        fun u16(at: Int): Int {
            val x = b[at].toInt() and 0xFF
            val y = b[at + 1].toInt() and 0xFF
            return if (le) x or (y shl 8) else (x shl 8) or y
        }
        fun u32(at: Int): Long = if (le) {
            u16(at).toLong() or (u16(at + 2).toLong() shl 16)
        } else {
            (u16(at).toLong() shl 16) or
                u16(at + 2).toLong()
        }
        val ifd = tiff + u32(tiff + 4).toInt()
        if (ifd + 2 > end) return false
        val n = u16(ifd)
        for (k in 0 until n) {
            val e = ifd + 2 + 12 * k
            if (e + 12 > end) return false
            if (u16(e) != 0xB002) continue
            val count = u32(e + 4).toInt()
            val entries = tiff + u32(e + 8).toInt()
            if (count < 32 || entries + 32 > end) return false
            val size = u32(entries + 16 + 4)
            val offset = u32(entries + 16 + 8)
            val at = tiff + offset
            return offset > 0 && at + 2 <= fileLength && at + size <= fileLength && byteAt(at) == 0xFF && byteAt(at + 1) == 0xD8
        }
        return false
    }

    /** Offset just past the EOI of the image starting at 0. */
    private fun primaryEnd(b: ByteArray): Int = primaryEndFrom(b, 0)

    /** Offset just past the EOI of the JPEG starting at [start] (segments, then entropy data up to an unstuffed EOI). */
    private fun primaryEndFrom(b: ByteArray, start: Int): Int {
        if (start + 4 > b.size || (b[start].toInt() and 0xFF) != 0xFF || (b[start + 1].toInt() and 0xFF) != 0xD8) return -1
        var i = start + 2
        while (i + 4 <= b.size) {
            if ((b[i].toInt() and 0xFF) != 0xFF) return -1
            val marker = b[i + 1].toInt() and 0xFF
            val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            i += 2 + len
            if (marker == 0xDA) break
        }
        while (i + 1 < b.size) {
            if ((b[i].toInt() and 0xFF) == 0xFF) {
                val m = b[i + 1].toInt() and 0xFF
                if (m == 0xD9) return i + 2
                // Stuffed 0xFF00 and restart markers stay in the scan; another segment (progressive scans) is skipped.
                if (m != 0x00 && m !in 0xD0..0xD7 && m != 0xFF && i + 4 <= b.size) {
                    val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
                    i += 2 + len
                    continue
                }
            }
            i++
        }
        return -1
    }

    private fun startsWith(b: ByteArray, at: Int, s: String): Boolean =
        at + s.length <= b.size && s.indices.all { b[at + it] == s[it].code.toByte() }

    private fun contains(b: ByteArray, from: Int, to: Int, s: String): Boolean {
        for (i in from..(to - s.length)) if (startsWith(b, i, s)) return true
        return false
    }
}
