package com.redline.camera.video

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import com.redline.camera.capability.Dim
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * B40 recording: MediaCodec HEVC (hardware first, [EncoderPick]) fed by the GPU chain through its input Surface, AAC from
 * AudioRecord when RECORD_AUDIO is granted (else video only, recorded as such), MediaMuxer MP4 written straight into a
 * MediaStore item in Movies/Redline (pending until it is finished, deleted if nothing was written).
 *
 * Timestamps: video carries the camera's sensor timestamps (eglPresentationTimeANDROID); audio is stamped on the same clock
 * ([realtimeClock] = SENSOR_INFO_TIMESTAMP_SOURCE REALTIME -> elapsedRealtimeNanos, else System.nanoTime) and both are
 * written relative to the first video frame.
 */
class VideoRecorder(
    private val context: Context,
    val size: Dim,
    val fps: Int,
    private val orientationHint: Int,
    audioWanted: Boolean,
    private val realtimeClock: Boolean
) {
    data class EncoderInfo(val name: String, val mime: String, val hardware: Boolean, val bitrate: Int, val bitrateRange: Pair<Int, Int>?)

    data class Result(
        val uri: Uri?,
        val durationMs: Long,
        val framesEncoded: Int,
        val audio: Boolean,
        val bytes: Long?,
        val tracks: Int,
        val error: String?
    )

    private val audioAllowed = audioWanted &&
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    lateinit var encoder: EncoderInfo
        private set
    lateinit var inputSurface: Surface
        private set
    var audioNote: String = if (!audioWanted) {
        "off"
    } else if (audioAllowed) {
        "pending"
    } else {
        "off: RECORD_AUDIO not granted"
    }
        private set

    private var video: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var audioChannels = 0
    private var muxer: MediaMuxer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private val lock = Object()
    private var videoTrack = -1
    private var audioTrack = -1
    private var videoFormat: MediaFormat? = null
    private var audioFormat: MediaFormat? = null
    private var started = false
    private var firstVideoPtsUs = -1L
    private var lastVideoPtsUs = -1L
    private var lastAudioPtsUs = -1L
    private var framesEncoded = 0
    private var audioSamplesWritten = 0
    private val heldVideo = ArrayList<Pair<ByteBuffer, MediaCodec.BufferInfo>>()
    private var error: String? = null
    private val videoDone = CountDownLatch(1)
    private val audioDone = CountDownLatch(1)

    @Volatile private var audioStop = false
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    /** Creates the encoder(s), the MediaStore item and the muxer. Throws with a reason when the encoder cannot be set up. */
    fun prepare() {
        val pick = pickEncoder(size, fps) ?: throw IllegalStateException("no HEVC or AVC encoder takes $size at $fps fps")
        val bitrate = VideoBitrate.choose(size, fps, pick.bitrateRange)
        encoder = EncoderInfo(pick.name, pick.mime, pick.hardware, bitrate, pick.bitrateRange)
        val fmt = MediaFormat.createVideoFormat(pick.mime, size.width, size.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }
        val codec = MediaCodec.createByCodecName(pick.name)
        try {
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            codec.release()
            throw IllegalStateException("${pick.name} refused $size at $bitrate bps: ${e.javaClass.simpleName}")
        }
        inputSurface = codec.createInputSurface()
        video = codec
        if (audioAllowed) prepareAudio()
        openOutput()
    }

    @SuppressLint("MissingPermission") // checked in audioAllowed
    private fun prepareAudio() {
        try {
            var rec: AudioRecord? = null
            for (channels in intArrayOf(2, 1)) {
                val mask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
                val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, mask, AudioFormat.ENCODING_PCM_16BIT)
                if (min <= 0) continue
                val r =
                    AudioRecord(
                        MediaRecorder.AudioSource.CAMCORDER,
                        SAMPLE_RATE,
                        mask,
                        AudioFormat.ENCODING_PCM_16BIT,
                        maxOf(min * 4, 16384)
                    )
                if (r.state == AudioRecord.STATE_INITIALIZED) {
                    rec = r
                    audioChannels = channels
                    break
                }
                r.release()
            }
            if (rec == null) {
                audioNote = "off: AudioRecord did not initialise"
                return
            }
            val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, audioChannels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, if (audioChannels == 2) 192_000 else 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            val ac = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            ac.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            audioRecord = rec
            audioCodec = ac
            audioNote = "AAC-LC ${SAMPLE_RATE / 1000} kHz ${if (audioChannels == 2) "stereo" else "mono"} (${ac.name})"
        } catch (e: Exception) {
            Log.w(TAG, "audio setup failed", e)
            audioRecord?.release()
            audioRecord = null
            audioCodec = null
            audioNote = "off: audio setup failed (${e.javaClass.simpleName})"
        }
    }

    private fun openOutput() {
        val name = "RL_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val item =
            resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("MediaStore insert failed")
        uri = item
        val fd = resolver.openFileDescriptor(item, "rw") ?: throw IllegalStateException("MediaStore item not writable")
        pfd = fd
        muxer = MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { it.setOrientationHint(orientationHint) }
    }

    /** Starts the encoder(s). The GPU chain then draws into [inputSurface]. */
    fun start() {
        val v = video ?: throw IllegalStateException("not prepared")
        v.start()
        videoThread = Thread({ drainVideo(v) }, "redline-video-enc").apply { start() }
        val ac = audioCodec
        val rec = audioRecord
        if (ac != null && rec != null) {
            ac.start()
            try {
                rec.startRecording()
                audioThread = Thread({ pumpAudio(rec, ac) }, "redline-audio-enc").apply { start() }
            } catch (e: Exception) {
                audioNote = "off: startRecording failed (${e.javaClass.simpleName})"
                runCatching { ac.stop() }
                ac.release()
                rec.release()
                audioCodec = null
                audioRecord = null
                audioDone.countDown()
            }
        } else {
            audioDone.countDown()
        }
    }

    /** Ends the streams, finishes the MP4 and publishes it (or deletes it when nothing was written). */
    fun stop(startedAtMs: Long): Result {
        runCatching { video?.signalEndOfInputStream() }.onFailure { setError("signalEndOfInputStream: ${it.javaClass.simpleName}") }
        audioStop = true
        if (!videoDone.await(STOP_TIMEOUT_S, TimeUnit.SECONDS)) setError("video encoder did not finish in $STOP_TIMEOUT_S s")
        if (!audioDone.await(STOP_TIMEOUT_S, TimeUnit.SECONDS)) setError("audio encoder did not finish in $STOP_TIMEOUT_S s")
        release()
        val wall = SystemClock.elapsedRealtime() - startedAtMs
        val ok: Boolean
        val tracks: Int
        synchronized(lock) {
            ok = started && framesEncoded > 0
            tracks = if (!started) 0 else 1 + if (audioTrack >= 0) 1 else 0
        }
        val resolver = context.contentResolver
        val item = uri
        var bytes: Long? = null
        if (item != null) {
            if (ok) {
                resolver.update(item, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                bytes = runCatching { resolver.openFileDescriptor(item, "r")?.use { it.statSize } }.getOrNull()
            } else {
                runCatching { resolver.delete(item, null, null) }
            }
        }
        val dur = if (firstVideoPtsUs >= 0 &&
            lastVideoPtsUs >= firstVideoPtsUs
        ) {
            (lastVideoPtsUs - firstVideoPtsUs) / 1000 + 1000 / fps
        } else {
            wall
        }
        return Result(
            uri = if (ok) item else null,
            durationMs = dur,
            framesEncoded = framesEncoded,
            audio = audioTrack >= 0 && audioSamplesWritten > 0,
            bytes = bytes,
            tracks = tracks,
            error = error ?: if (!ok) "no frames were encoded" else null
        )
    }

    /** Releases everything without publishing (a failed start). */
    fun abort() {
        audioStop = true
        release()
        uri?.let { item -> runCatching { context.contentResolver.delete(item, null, null) } }
        uri = null
    }

    private fun release() {
        runCatching { videoThread?.join(1000) }
        runCatching { audioThread?.join(1000) }
        runCatching { video?.stop() }
        runCatching { video?.release() }
        video = null
        runCatching { if (::inputSurface.isInitialized) inputSurface.release() }
        runCatching { audioCodec?.stop() }
        runCatching { audioCodec?.release() }
        audioCodec = null
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        synchronized(lock) {
            if (started) runCatching { muxer?.stop() }.onFailure { setError("muxer stop: ${it.javaClass.simpleName}: ${it.message}") }
            runCatching { muxer?.release() }
            muxer = null
        }
        runCatching { pfd?.close() }
        pfd = null
    }

    private fun setError(e: String) {
        synchronized(lock) { if (error == null) error = e }
    }

    private fun drainVideo(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                val i = codec.dequeueOutputBuffer(info, DEQUEUE_US)
                when {
                    i == MediaCodec.INFO_TRY_AGAIN_LATER -> continue
                    i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(lock) {
                        videoFormat = codec.outputFormat
                        maybeStartMuxer()
                    }
                    i >= 0 -> {
                        val buf = codec.getOutputBuffer(i)
                        if (buf != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) writeVideo(buf, info)
                        codec.releaseOutputBuffer(i, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "video drain failed", e)
            setError("video encoder: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            videoDone.countDown()
        }
    }

    private fun writeVideo(buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(lock) {
            if (firstVideoPtsUs < 0) firstVideoPtsUs = info.presentationTimeUs
            val pts = info.presentationTimeUs - firstVideoPtsUs
            if (pts <= lastVideoPtsUs - firstVideoPtsUs && framesEncoded > 0) return
            lastVideoPtsUs = info.presentationTimeUs
            framesEncoded++
            val copyInfo = MediaCodec.BufferInfo().apply { set(0, info.size, pts, info.flags) }
            if (!started) {
                val copy = ByteBuffer.allocateDirect(info.size)
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                copy.put(buf)
                copy.flip()
                heldVideo += copy to copyInfo
                return
            }
            buf.position(info.offset)
            buf.limit(info.offset + info.size)
            muxer?.writeSampleData(videoTrack, buf, copyInfo)
        }
    }

    /** Lock held. Starts the muxer once the video format (and the audio format, when audio runs) are known. */
    private fun maybeStartMuxer() {
        if (started) return
        val m = muxer ?: return
        val vf = videoFormat ?: return
        val audioRunning = audioCodec != null
        if (audioRunning && audioFormat == null) return
        videoTrack = m.addTrack(vf)
        audioFormat?.let { audioTrack = m.addTrack(it) }
        m.start()
        started = true
        for ((b, i) in heldVideo) m.writeSampleData(videoTrack, b, i)
        heldVideo.clear()
    }

    private fun pumpAudio(rec: AudioRecord, codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var frames = 0L
        var baseNs = -1L
        var eosQueued = false
        val bytesPerFrame = 2 * audioChannels
        try {
            while (true) {
                if (!eosQueued) {
                    val ii = codec.dequeueInputBuffer(DEQUEUE_US)
                    if (ii >= 0) {
                        val inBuf = codec.getInputBuffer(ii)!!
                        inBuf.clear()
                        if (audioStop) {
                            codec.queueInputBuffer(ii, 0, 0, ptsUs(baseNs, frames), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosQueued = true
                        } else {
                            val n = rec.read(inBuf, minOf(inBuf.capacity(), AUDIO_CHUNK_BYTES))
                            if (n > 0) {
                                if (baseNs < 0) baseNs = clockNs() - n.toLong() / bytesPerFrame * 1_000_000_000L / SAMPLE_RATE
                                codec.queueInputBuffer(ii, 0, n, ptsUs(baseNs, frames), 0)
                                frames += n / bytesPerFrame
                            } else {
                                codec.queueInputBuffer(ii, 0, 0, ptsUs(baseNs, frames), 0)
                            }
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, if (eosQueued) DEQUEUE_US else 0L)
                when {
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(lock) {
                        audioFormat = codec.outputFormat
                        maybeStartMuxer()
                    }
                    oi >= 0 -> {
                        val buf = codec.getOutputBuffer(oi)
                        if (buf != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) writeAudio(buf, info)
                        codec.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "audio pump failed", e)
            setError("audio: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            audioDone.countDown()
        }
    }

    private fun ptsUs(baseNs: Long, frames: Long): Long = (maxOf(baseNs, 0L) + frames * 1_000_000_000L / SAMPLE_RATE) / 1000

    private fun clockNs(): Long = if (realtimeClock) SystemClock.elapsedRealtimeNanos() else System.nanoTime()

    private fun writeAudio(buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(lock) {
            if (!started || audioTrack < 0 || firstVideoPtsUs < 0) return
            val pts = info.presentationTimeUs - firstVideoPtsUs
            if (pts < 0 || pts <= lastAudioPtsUs) return
            lastAudioPtsUs = pts
            buf.position(info.offset)
            buf.limit(info.offset + info.size)
            muxer?.writeSampleData(audioTrack, buf, MediaCodec.BufferInfo().apply { set(0, info.size, pts, info.flags) })
            audioSamplesWritten++
        }
    }

    companion object {
        private const val TAG = "RedlineVideoRec"
        const val RELATIVE_PATH = "Movies/Redline"
        const val SAMPLE_RATE = 48_000
        private const val DEQUEUE_US = 10_000L
        private const val AUDIO_CHUNK_BYTES = 8192
        private const val STOP_TIMEOUT_S = 5L

        /** Every encoder for HEVC / AVC with whether it takes [size] at [fps], then [EncoderPick.choose]. */
        fun pickEncoder(size: Dim, fps: Int): EncoderCandidate? = EncoderPick.choose(candidates(size, fps))

        fun candidates(size: Dim, fps: Int): List<EncoderCandidate> {
            val out = mutableListOf<EncoderCandidate>()
            for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                if (!info.isEncoder) continue
                for (mime in listOf(EncoderPick.HEVC, EncoderPick.AVC)) {
                    if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
                    val vc = runCatching { info.getCapabilitiesForType(mime).videoCapabilities }.getOrNull() ?: continue
                    val ok = runCatching { vc.areSizeAndRateSupported(size.width, size.height, fps.toDouble()) }.getOrDefault(false)
                    out += EncoderCandidate(info.name, mime, info.isHardwareAccelerated, ok, vc.bitrateRange?.let { it.lower to it.upper })
                }
            }
            return out
        }
    }
}
