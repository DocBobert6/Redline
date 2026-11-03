package com.redline.camera.capture

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import com.redline.camera.nativebridge.RedlineNative
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException
import kotlin.math.abs

/**
 * B30: the still as HEIF, written by Redline (MediaCodec + MediaMuxer HEIF) instead of androidx.heifwriter.
 *
 * Why: heifwriter 1.1.0's bitmap mode draws every 512x512 tile through EGL, and when the phone has no HEIC image encoder
 * it takes the FIRST HEVC encoder that supports constant-quality mode. Hardware HEVC encoders usually do not offer CQ,
 * so on the baseline device it picks the software encoder (c2.android.hevc.encoder): 3.4 s for a 2910x1939 still.
 *
 * Here: a hardware HEIC image encoder when there is one (even sizes only), else a hardware HEVC encoder fed 512x512 grid
 * tiles, software only when nothing else exists. Input is buffer mode: native code converts the bitmap's RGBA straight
 * into each input image as full-range BT.601 YUV 4:2:0 (no GL, no EGL). The rotation goes in the file (irot), the EXIF
 * block is the image's Exif item. [Result] says which codec, mode and grid were used and where the time went.
 */
object PhotoHeif {
    const val TILE = 512
    const val HEIC_MIME = "image/vnd.android.heic"
    const val HEVC_MIME = "video/hevc"
    const val INPUT_MODE = "buffer_yuv420"

    /** An encoder as the chooser sees it (pure data, so the choice is unit-tested on the JVM). */
    data class Candidate(
        val name: String,
        val mime: String,
        val hardware: Boolean,
        /** BITRATE_MODE_CQ supported. */
        val cq: Boolean,
        /** COLOR_FormatYUV420Flexible input (buffer mode). */
        val yuvFlexible: Boolean,
        /** The whole still fits one frame (HEIC image encoders take the full image). */
        val fullSize: Boolean,
        /** A 512x512 tile fits (HEVC grid). */
        val tileSize: Boolean
    ) {
        val heic get() = mime == HEIC_MIME
    }

    /**
     * The encoder for a [width] x [height] still: a hardware HEIC image encoder (even sizes only: 4:2:0 needs them);
     * else a hardware HEVC encoder (CQ-capable first) for the 512 grid; else a software HEVC one (CQ first); else null.
     * Order within each class keeps the platform's list order (its preference).
     */
    fun choose(candidates: List<Candidate>, width: Int, height: Int): Candidate? {
        val usable = candidates.filter { it.yuvFlexible }
        val even = width % 2 == 0 && height % 2 == 0
        usable.firstOrNull { it.heic && it.hardware && it.fullSize && even }?.let { return it }
        val hevc = usable.filter { !it.heic && it.tileSize }
        val hw = hevc.filter { it.hardware }
        hw.firstOrNull { it.cq }?.let { return it }
        hw.firstOrNull()?.let { return it }
        val sw = hevc.filter { !it.hardware }
        return sw.firstOrNull { it.cq } ?: sw.firstOrNull()
    }

    /** Grid of 512 tiles covering the still (cols, rows). */
    fun grid(width: Int, height: Int): Pair<Int, Int> = ((width + TILE - 1) / TILE) to ((height + TILE - 1) / TILE)

    /** Bits for one image when the encoder has no CQ mode (androidx.heifwriter's budget: 1/4 of 4:2:0 size x quality). */
    fun bitrate(width: Int, height: Int, quality: Int): Int = (width.toLong() * height * 1.5 * 8 * 0.25 * quality / 100.0).toInt()

    data class Result(
        val codec: String,
        val hardware: Boolean,
        val heicEncoder: Boolean,
        val grid: String,
        val tiles: Int,
        val bitrateMode: String,
        val bytes: Long,
        val firstInProcess: Boolean,
        /** select, configure (create + configure + start), convert (RGBA -> YUV, all tiles), encode (queue + drain minus convert), finish (muxer), total. */
        val ms: Map<String, Long>,
        /** First HEIF of the process only: decoded back at 1/8 and compared with the source (mean |diff| per channel, /255). */
        val check: Map<String, Any?>? = null
    ) {
        fun toMap(): Map<String, Any?> = linkedMapOf(
            "heif_codec" to codec,
            "heif_hardware" to hardware,
            "heif_encoder_kind" to if (heicEncoder) "heic image encoder" else "hevc video encoder",
            "heif_input_mode" to INPUT_MODE,
            "heif_grid" to grid,
            "heif_tiles" to tiles,
            "heif_bitrate_mode" to bitrateMode,
            "heif_first_in_process" to firstInProcess,
            "heif_encode_ms" to ms,
            "heif_check" to check
        )
    }

    @Volatile private var listCache: List<MediaCodecInfo>? = null

    @Volatile private var encodedOnce = false

    private fun encoders(): List<MediaCodecInfo> = listCache ?: synchronized(this) {
        listCache ?: MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder }.also { listCache = it }
    }

    /** Reads the codec list once (off the shutter path, when HEIF is chosen or the caps are read). */
    fun prewarm() {
        runCatching { encoders() }
    }

    fun candidates(width: Int, height: Int): List<Candidate> = encoders().flatMap { info ->
        info.supportedTypes.filter { it.equals(HEIC_MIME, true) || it.equals(HEVC_MIME, true) }.mapNotNull { type ->
            runCatching {
                val caps = info.getCapabilitiesForType(type)
                val video = caps.videoCapabilities
                Candidate(
                    name = info.name,
                    mime = if (type.equals(HEIC_MIME, true)) HEIC_MIME else HEVC_MIME,
                    hardware = info.isHardwareAccelerated,
                    cq = caps.encoderCapabilities?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ) == true,
                    yuvFlexible = caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible),
                    fullSize = video?.isSizeSupported(width, height) == true,
                    tileSize = video?.isSizeSupported(TILE, TILE) == true
                )
            }.getOrNull()
        }
    }

    /** HEIF is offered when some encoder qualifies for a typical still; the reason when not. */
    fun support(): Pair<Boolean, String?> = try {
        val c = choose(candidates(4000, 3000), 4000, 3000)
        if (c != null) true to null else false to "no HEIC or HEVC encoder with YUV buffer input"
    } catch (e: Exception) {
        false to "${e.javaClass.simpleName}: ${e.message}"
    }

    /** [rotationDegrees] goes in the HEIF (irot); [exif] is an Exif payload ("Exif\\0\\0" + TIFF) or null. */
    @SuppressLint("WrongConstant")
    fun write(bitmap: Bitmap, rotationDegrees: Int, quality: Int, exif: ByteArray?, file: File): Result {
        require(bitmap.config == Bitmap.Config.ARGB_8888) { "HEIF input must be ARGB_8888" }
        val t0 = SystemClock.elapsedRealtime()
        val w = bitmap.width
        val h = bitmap.height
        val pick = choose(candidates(w, h), w, h) ?: error("no HEIC or HEVC encoder with YUV buffer input")
        val tSelect = SystemClock.elapsedRealtime()
        val useGrid = !pick.heic
        val (cols, rows) = if (useGrid) grid(w, h) else 1 to 1
        val frames = cols * rows
        val frameW = if (useGrid) TILE else w
        val frameH = if (useGrid) TILE else h
        val fmt = MediaFormat.createVideoFormat(pick.mime, frameW, frameH)
        val (gc, gr) = grid(w, h)
        // The tile hint also goes to an HEIC image encoder (it tiles internally), as androidx.heifwriter does.
        fmt.setInteger(MediaFormat.KEY_TILE_WIDTH, TILE)
        fmt.setInteger(MediaFormat.KEY_TILE_HEIGHT, TILE)
        fmt.setInteger(MediaFormat.KEY_GRID_COLUMNS, gc)
        fmt.setInteger(MediaFormat.KEY_GRID_ROWS, gr)
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0)
        fmt.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT601_NTSC)
        fmt.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL)
        fmt.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, frames)
        fmt.setInteger(MediaFormat.KEY_OPERATING_RATE, if (frames > 1) 120 else 30)
        val codec = MediaCodec.createByCodecName(pick.name)
        val bitrateMode = configureRate(fmt, codec, pick, w, h, quality)
        var muxer: MediaMuxer? = null
        var convertMs = 0L
        var tConfigured = tSelect
        var tLoop = tSelect
        val firstInProcess = !encodedOnce
        try {
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            tConfigured = SystemClock.elapsedRealtime()
            val info = MediaCodec.BufferInfo()
            var queued = 0
            var eosQueued = false
            var track = -1
            var done = false
            val deadline = tConfigured + DEADLINE_MS
            while (!done) {
                if (SystemClock.elapsedRealtime() > deadline) throw TimeoutException("HEIF encode over $DEADLINE_MS ms")
                if (!eosQueued) {
                    val ii = codec.dequeueInputBuffer(DEQUEUE_US)
                    if (ii >= 0) {
                        if (queued < frames) {
                            val c0 = SystemClock.elapsedRealtime()
                            fill(
                                codec,
                                ii,
                                bitmap,
                                if (useGrid) (queued % cols) * TILE else 0,
                                if (useGrid) (queued / cols) * TILE else 0,
                                frameW,
                                frameH
                            )
                            convertMs += SystemClock.elapsedRealtime() - c0
                            val cap = codec.getInputBuffer(ii)?.capacity() ?: 0
                            codec.queueInputBuffer(ii, 0, cap, pts(queued, frames), 0)
                            queued++
                        } else {
                            codec.queueInputBuffer(ii, 0, 0, pts(frames, frames), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosQueued = true
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, if (eosQueued) DEQUEUE_US else 0L)
                when {
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(muxer == null) { "output format changed twice" }
                        val out = trackFormat(codec.outputFormat, w, h, useGrid, cols, rows)
                        val m = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_HEIF)
                        val rot = ((rotationDegrees % 360) + 360) % 360
                        if (rot != 0) m.setOrientationHint(rot)
                        track = m.addTrack(out)
                        m.start()
                        if (exif != null) {
                            val eb = ByteBuffer.allocateDirect(exif.size).put(exif)
                            eb.flip()
                            val ei = MediaCodec.BufferInfo().apply { set(0, exif.size, 0, MUXER_DATA_FLAG) }
                            m.writeSampleData(track, eb, ei)
                        }
                        muxer = m
                    }
                    oi >= 0 -> {
                        val buf = codec.getOutputBuffer(oi)
                        if (buf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            val m = checkNotNull(muxer) { "encoder output before its format" }
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val si = MediaCodec.BufferInfo().apply { set(info.offset, info.size, 0, 0) }
                            m.writeSampleData(track, buf, si)
                        }
                        codec.releaseOutputBuffer(oi, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) done = true
                    }
                }
            }
            tLoop = SystemClock.elapsedRealtime()
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
        val m = checkNotNull(muxer) { "encoder produced no output" }
        m.stop()
        m.release()
        val tEnd = SystemClock.elapsedRealtime()
        encodedOnce = true
        check(file.length() > 0) { "HEIF muxer produced no data" }
        val check = if (firstInProcess) roundTrip(bitmap, file) else null
        return Result(
            codec = pick.name,
            hardware = pick.hardware,
            heicEncoder = pick.heic,
            grid = if (useGrid) "${cols}x$rows tiles of ${TILE}x$TILE" else "none (HEIC encoder, ${w}x$h; tiles ${gc}x$gr hinted)",
            tiles = frames,
            bitrateMode = bitrateMode,
            bytes = file.length(),
            firstInProcess = firstInProcess,
            ms = linkedMapOf(
                "select" to tSelect - t0,
                "configure" to tConfigured - tSelect,
                "convert" to convertMs,
                "encode" to (tLoop - tConfigured - convertMs).coerceAtLeast(0),
                "finish" to tEnd - tLoop,
                "total" to tEnd - t0
            ),
            check = check
        )
    }

    private fun configureRate(fmt: MediaFormat, codec: MediaCodec, pick: Candidate, w: Int, h: Int, quality: Int): String {
        val caps = codec.codecInfo.getCapabilitiesForType(pick.mime)
        val enc = caps.encoderCapabilities ?: error("${pick.name} has no encoder capabilities")
        return if (enc.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ)) {
            val r = enc.qualityRange
            val q = (r.lower + (r.upper - r.lower) * quality / 100.0).toInt()
            fmt.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ)
            fmt.setInteger(MediaFormat.KEY_QUALITY, q)
            "cq $q (${r.lower}..${r.upper})"
        } else {
            val mode = if (enc.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)) {
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            } else {
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            }
            val bps = caps.videoCapabilities?.bitrateRange?.clamp(bitrate(w, h, quality)) ?: bitrate(w, h, quality)
            fmt.setInteger(MediaFormat.KEY_BITRATE_MODE, mode)
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, bps)
            "${if (mode == MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) "cbr" else "vbr"} $bps bits per image"
        }
    }

    private fun fill(codec: MediaCodec, index: Int, bitmap: Bitmap, x0: Int, y0: Int, tileW: Int, tileH: Int) {
        val image = codec.getInputImage(index) ?: error("encoder gave no input image (flexible YUV)")
        val p = image.planes
        check(p.size == 3) { "input image has ${p.size} planes" }
        val err = RedlineNative.rgbaToYuvTile(
            bitmap,
            x0,
            y0,
            tileW,
            tileH,
            p[0].buffer,
            p[0].rowStride,
            p[0].pixelStride,
            p[1].buffer,
            p[2].buffer,
            p[1].rowStride,
            p[1].pixelStride
        )
        check(err == null) { "YUV tile: $err" }
    }

    private fun trackFormat(enc: MediaFormat, w: Int, h: Int, grid: Boolean, cols: Int, rows: Int): MediaFormat {
        if (enc.getString(MediaFormat.KEY_MIME) != HEIC_MIME) {
            enc.setString(MediaFormat.KEY_MIME, HEIC_MIME)
            enc.setInteger(MediaFormat.KEY_WIDTH, w)
            enc.setInteger(MediaFormat.KEY_HEIGHT, h)
            if (grid) {
                enc.setInteger(MediaFormat.KEY_TILE_WIDTH, TILE)
                enc.setInteger(MediaFormat.KEY_TILE_HEIGHT, TILE)
                enc.setInteger(MediaFormat.KEY_GRID_ROWS, rows)
                enc.setInteger(MediaFormat.KEY_GRID_COLUMNS, cols)
            }
        }
        enc.setInteger(MediaFormat.KEY_IS_DEFAULT, 1)
        return enc
    }

    private fun pts(i: Int, frames: Int): Long = 132L + i * 1_000_000L / frames.coerceAtLeast(1)

    /** Decodes the file at 1/8 and compares channel means with the source scaled the same way (catches a range or matrix mix-up). */
    private fun roundTrip(src: Bitmap, file: File): Map<String, Any?> {
        val t = SystemClock.elapsedRealtime()
        return try {
            val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
            val dec = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return linkedMapOf("decoded" to false)
            // The decoder may or may not apply irot; means do not depend on rotation.
            val a = means(dec)
            dec.recycle()
            val small = Bitmap.createScaledBitmap(src, (src.width / 8).coerceAtLeast(1), (src.height / 8).coerceAtLeast(1), true)
            val b = means(small)
            if (small !== src) small.recycle()
            val diff = DoubleArray(3) { abs(a[it] - b[it]) }
            linkedMapOf(
                "decoded" to true,
                "mean_abs_diff_rgb_255" to diff.map { Math.round(it * 100) / 100.0 },
                "ok" to (diff.max() <= CHECK_TOLERANCE_255),
                "ms" to SystemClock.elapsedRealtime() - t
            )
        } catch (e: Throwable) {
            linkedMapOf("decoded" to false, "error" to "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun means(b: Bitmap): DoubleArray {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        val s = DoubleArray(3)
        for (c in px) {
            s[0] += (c shr 16) and 0xFF
            s[1] += (c shr 8) and 0xFF
            s[2] += c and 0xFF
        }
        return DoubleArray(3) { s[it] / px.size.coerceAtLeast(1) }
    }

    /** MediaCodec.BUFFER_FLAG_MUXER_DATA (hidden): the sample is the image's Exif item. */
    private const val MUXER_DATA_FLAG = 16
    private const val DEQUEUE_US = 10_000L
    private const val DEADLINE_MS = 8_000L

    /** Mean channel difference that still counts as the same picture (a limited/full range mix-up moves means by ~10+). */
    const val CHECK_TOLERANCE_255 = 4.0
}
