package com.redline.camera.nativebridge

import android.graphics.Bitmap
import com.redline.camera.look.CubeLut
import java.nio.ByteBuffer

/**
 * JNI wrapper for libredline_native.so (B20 LUT parser, B21 RAW colour pipeline, B22 burst merge, B23 super-resolution;
 * app/src/main/cpp). The library is loaded lazily and at most once;
 * a failed load is remembered with its reason (the look preview is then disabled and the report says why) and
 * never crashes the app.
 */
object RedlineNative {
    const val LIBRARY = "redline_native"

    /** Floats ahead of the LUT data in [nativeParseCube]'s result: size, domain min (3), domain max (3). */
    const val HEADER_FLOATS = 7

    data class LoadResult(val loaded: Boolean, val version: String?, val error: String?)

    @Volatile
    private var result: LoadResult? = null

    /** Loads the library on first use; later calls return the remembered outcome. Thread-safe. */
    fun load(): LoadResult = result ?: synchronized(this) {
        result ?: attempt { System.loadLibrary(it) }.also { result = it }
    }

    /** One load attempt through [loader]; never throws. Not cached (tests call it directly). */
    internal fun attempt(loader: (String) -> Unit): LoadResult = try {
        loader(LIBRARY)
        LoadResult(loaded = true, version = nativeVersion(), error = null)
    } catch (e: LinkageError) {
        LoadResult(loaded = false, version = null, error = "${e.javaClass.simpleName}: ${e.message}")
    } catch (e: RuntimeException) {
        LoadResult(loaded = false, version = null, error = "${e.javaClass.simpleName}: ${e.message}")
    }

    /** Parses .cube text natively. Throws IllegalStateException when the library is not loaded, IllegalArgumentException on bad data. */
    fun parseCube(bytes: ByteArray): CubeLut {
        val loaded = load()
        check(loaded.loaded) { "native library not loaded: ${loaded.error}" }
        return CubeLut.fromNativeLayout(nativeParseCube(bytes))
    }

    /** A RAW_SENSOR frame as the native pipeline reads it (16-bit samples in a direct buffer). */
    class RawFrame(
        val buffer: ByteBuffer,
        val width: Int,
        val height: Int,
        val rowStrideBytes: Int,
        /** Camera2 SENSOR_INFO_COLOR_FILTER_ARRANGEMENT 0..3. */
        val cfa: Int,
        /** Per CFA position (y & 1) * 2 + (x & 1). */
        val black: FloatArray,
        val white: Float,
        /** Lens shading map, Camera2 layout rows x cols x (R, G_even, G_odd, B), or null. */
        val shading: FloatArray?,
        val shadingRows: Int,
        val shadingCols: Int
    )

    class Stats(val medianY: Double, val clippedFraction: Double, val cells: Int)

    /** Exposure statistics over [crop] (left, top, width, height). Throws IllegalArgumentException on bad input. */
    fun rawStats(f: RawFrame, crop: IntArray, matrix: FloatArray, neutral: FloatArray, step: Int): Stats {
        check(load().loaded) { "native library not loaded: ${load().error}" }
        val r = nativeRawStats(
            f.buffer, f.width, f.height, f.rowStrideBytes, f.cfa, f.black, f.white, f.shading, f.shadingRows, f.shadingCols,
            crop, matrix, neutral, step
        )
        return Stats(r[0].toDouble(), r[1].toDouble(), r[2].toInt())
    }

    /**
     * Renders [crop] of the frame into [out] (ARGB_8888, the output size) through the B21 pipeline. Returns null on
     * success, else the native error (nothing useful is in the bitmap then).
     */
    fun renderRaw(
        f: RawFrame,
        crop: IntArray,
        matrix: FloatArray,
        neutral: FloatArray,
        gain: Float,
        curve: FloatArray,
        curveLo: Float,
        curveHi: Float,
        lut: CubeLut,
        threads: Int,
        out: Bitmap
    ): String? {
        val loaded = load()
        if (!loaded.loaded) return "native library not loaded: ${loaded.error}"
        return nativeRenderRaw(
            f.buffer, f.width, f.height, f.rowStrideBytes, f.cfa, f.black, f.white, f.shading, f.shadingRows, f.shadingCols,
            crop, matrix, neutral, gain, curve, curveLo, curveHi, lut.data, lut.size, threads, out
        )
    }

    // ------------------------------------------------------------------------------------------- B22 burst merge

    /** A frame-sized buffer in native memory (outside the Java heap), or null when it cannot be allocated. Free with [freeFrame]. */
    fun allocFrame(bytes: Long): ByteBuffer? {
        if (!load().loaded) return null
        return try {
            nativeAllocFrame(bytes)
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /** Frees a buffer from [allocFrame]; it must not be used afterwards. */
    fun freeFrame(buffer: ByteBuffer) {
        if (load().loaded) nativeFreeFrame(buffer)
    }

    /** Per alternate frame: alignment and weights. Displacements in RAW pixels. */
    class MergedFrame(
        val index: Int,
        val meanDispPx: Double,
        val maxDispPx: Double,
        val meanWeight: Double,
        val rejectedFraction: Double,
        val alignMs: Double,
        val mergeMs: Double
    )

    class MergeStats(
        val reference: Int,
        val frames: Int,
        val tilesX: Int,
        val tilesY: Int,
        val pyramidMs: Double,
        val referenceMs: Double,
        val alignMs: Double,
        val mergeMs: Double,
        val finishMs: Double,
        /** B22a: worker threads used (the caller included), the most frames held at once, the merger's own peak memory. */
        val threads: Int,
        val maxHeldFrames: Int,
        val peakNativeMb: Double,
        val perFrame: List<MergedFrame>
    )

    /**
     * Aligns and merges the RAW_SENSOR [frames] (same size, compact or strided, same exposure) into [out] as
     * uint16 scaled by [outScale] (black and white scale with it). [reference] -1 picks the sharpest of the first 3.
     * Throws IllegalArgumentException on bad input, IllegalStateException when the library is not loaded.
     */
    fun mergeBurst(
        frames: List<ByteBuffer>,
        width: Int,
        height: Int,
        rowStrideBytes: Int,
        black: FloatArray,
        white: Float,
        noiseS: Double,
        noiseO: Double,
        reference: Int,
        threads: Int,
        out: ByteBuffer,
        outScale: Float
    ): MergeStats {
        check(load().loaded) { "native library not loaded: ${load().error}" }
        val r = nativeMergeBurst(
            frames.toTypedArray(), width, height, rowStrideBytes, black, white, noiseS, noiseO, reference, threads, out, outScale
        )
        return mergeStats(r)
    }

    private fun mergeStats(r: FloatArray): MergeStats {
        val per = (MERGE_HEADER until r.size step MERGE_PER_FRAME).map { i ->
            MergedFrame(
                r[i].toInt(),
                r[i + 1].toDouble(),
                r[i + 2].toDouble(),
                r[i + 3].toDouble(),
                r[i + 4].toDouble(),
                r[i + 5].toDouble(),
                r[i + 6].toDouble()
            )
        }
        return MergeStats(
            r[0].toInt(), r[1].toInt(), r[2].toInt(), r[3].toInt(), r[4].toDouble(), r[5].toDouble(), r[6].toDouble(),
            r[7].toDouble(), r[8].toDouble(), r[9].toInt(), r[10].toInt(), r[11].toDouble(), per
        )
    }

    /**
     * B22a streaming merge of one burst: [add] each RAW frame as it arrives (in capture order), then [finish]. [add]
     * returns the indices (0-based, capture order) of frames whose buffers the merger no longer needs; [finish]
     * releases the rest. Use from one thread; always [close].
     */
    class BurstMerger internal constructor(private var handle: Long) : AutoCloseable {
        fun add(frame: ByteBuffer, rowStrideBytes: Int): IntArray {
            check(handle != 0L) { "merger closed" }
            return nativeMergerAdd(handle, frame, rowStrideBytes)
        }

        /** Writes the merge into [out] (uint16 x [outScale]); null writes it in place over the reference frame's buffer. */
        fun finish(out: ByteBuffer?, rowStrideBytes: Int, outScale: Float): MergeStats {
            check(handle != 0L) { "merger closed" }
            return mergeStats(nativeMergerFinish(handle, out, rowStrideBytes, outScale))
        }

        override fun close() {
            if (handle != 0L) {
                nativeMergerDestroy(handle)
                handle = 0L
            }
        }
    }

    /** A streaming merger ([reference] -1: the sharpest of the first 3). Throws like [mergeBurst]. */
    fun burstMerger(
        width: Int,
        height: Int,
        black: FloatArray,
        white: Float,
        noiseS: Double,
        noiseO: Double,
        reference: Int,
        threads: Int
    ): BurstMerger {
        check(load().loaded) { "native library not loaded: ${load().error}" }
        return BurstMerger(nativeMergerCreate(width, height, black, white, noiseS, noiseO, reference, threads))
    }

    /** Merge worker threads: the CPU's performance cores (not the little ones), 2..8; a guess when not loaded. */
    fun burstThreads(): Int = if (load().loaded) {
        nativeBurstThreads()
    } else {
        (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 8)
    }

    /** Nice value of the native merge workers (they inherit nothing from the calling thread once created). */
    fun setBurstWorkerNice(nice: Int) {
        if (load().loaded) nativeBurstWorkerNice(nice)
    }

    class BurstSelfCheck(val alignedFraction: Double, val noiseRatio: Double, val ms: Double)

    /** The native synthetic burst check (known shifts + noise). Throws like [mergeBurst]. */
    fun burstSelfCheck(threads: Int): BurstSelfCheck {
        check(load().loaded) { "native library not loaded: ${load().error}" }
        val r = nativeBurstSelfCheck(threads)
        return BurstSelfCheck(r[0].toDouble(), r[1].toDouble(), r[2].toDouble())
    }

    // ------------------------------------------------------------------------------------------- B23 super-resolution

    /** Per alternate frame of a super-resolution merge. Displacements and motion in RAW pixels. */
    class SrFrame(
        val index: Int,
        val meanDispPx: Double,
        val meanSubpixPx: Double,
        val meanRobustness: Double,
        val rejectedFraction: Double,
        val modelResolved: Double,
        val model: Boolean,
        val motionYPx: Double,
        val motionXPx: Double,
        val alignMs: Double,
        val robustMs: Double
    )

    class SrStats(
        val reference: Int,
        val frames: Int,
        /** left, top, width, height of the RAW region read (the crop plus a margin). */
        val region: IntArray,
        val coverage: Double,
        val sigmaG: Double,
        val sigmaRB: Double,
        val copyMs: Double,
        val referenceMs: Double,
        val alignMs: Double,
        val robustMs: Double,
        val gatherMs: Double,
        val threads: Int,
        val maxRegionsHeld: Int,
        val peakNativeMb: Double,
        val outputMb: Double,
        val perFrame: List<SrFrame>
    )

    internal fun srStats(r: FloatArray): SrStats {
        val per = (SR_HEADER until r.size step SR_PER_FRAME).map { i ->
            SrFrame(
                r[i].toInt(), r[i + 1].toDouble(), r[i + 2].toDouble(), r[i + 3].toDouble(), r[i + 4].toDouble(), r[i + 5].toDouble(),
                r[i + 6] > 0.5f, r[i + 7].toDouble(), r[i + 8].toDouble(), r[i + 9].toDouble(), r[i + 10].toDouble()
            )
        }
        return SrStats(
            r[0].toInt(), r[1].toInt(), intArrayOf(r[2].toInt(), r[3].toInt(), r[4].toInt(), r[5].toInt()), r[8].toDouble(),
            r[9].toDouble(), r[10].toDouble(), r[11].toDouble(), r[12].toDouble(), r[13].toDouble(), r[14].toDouble(),
            r[15].toDouble(), r[16].toInt(), r[17].toInt(), r[18].toDouble(), r[19].toDouble(), per
        )
    }

    /**
     * B23 streaming super-resolution merge of one burst at a 50/70 mm stop: [add] each RAW frame in capture order (the
     * merger copies the crop region and the frame's buffer is free again at once), [finish] (gathers every frame onto the
     * output grid, held natively: ~72 MB at 3000 x 2000), then [render] / [stats] on the result. Use from one thread at a
     * time; always [close].
     */
    class SuperResMerger internal constructor(private var handle: Long) : AutoCloseable {
        fun add(frame: ByteBuffer, rowStrideBytes: Int): IntArray {
            check(handle != 0L) { "merger closed" }
            return nativeSrAdd(handle, frame, rowStrideBytes)
        }

        /** [shading]: a burst frame's lens shading map (Camera2 layout), or null. */
        fun finish(shading: FloatArray?, shadingRows: Int, shadingCols: Int): SrStats {
            check(handle != 0L) { "merger closed" }
            return srStats(nativeSrFinish(handle, shading, shadingRows, shadingCols))
        }

        /** Exposure statistics of the finished merge (as [rawStats]). */
        fun stats(matrix: FloatArray, neutral: FloatArray, step: Int): Stats {
            check(handle != 0L) { "merger closed" }
            val r = nativeSrStats(handle, matrix, neutral, step)
            return Stats(r[0].toDouble(), r[1].toDouble(), r[2].toInt())
        }

        /** Renders the finished merge into [out] (ARGB_8888 at the output size) through B21 stages 4-8; null or the error. */
        fun render(
            matrix: FloatArray,
            neutral: FloatArray,
            gain: Float,
            curve: FloatArray,
            curveLo: Float,
            curveHi: Float,
            lut: CubeLut,
            threads: Int,
            out: Bitmap
        ): String? {
            if (handle == 0L) return "merger closed"
            return nativeSrRender(handle, matrix, neutral, gain, curve, curveLo, curveHi, lut.data, lut.size, threads, out)
        }

        override fun close() {
            if (handle != 0L) {
                nativeSrDestroy(handle)
                handle = 0L
            }
        }
    }

    /** A super-resolution merger for [crop] (left, top, width, height, even) to [outWidth] x [outHeight]. Throws like [mergeBurst]. */
    fun superResMerger(
        width: Int,
        height: Int,
        cfa: Int,
        black: FloatArray,
        white: Float,
        noiseS: Double,
        noiseO: Double,
        crop: IntArray,
        outWidth: Int,
        outHeight: Int,
        reference: Int,
        threads: Int
    ): SuperResMerger {
        check(load().loaded) { "native library not loaded: ${load().error}" }
        return SuperResMerger(
            nativeSrCreate(width, height, cfa, black, white, noiseS, noiseO, null, 0, 0, crop, outWidth, outHeight, reference, threads)
        )
    }

    class SrSelfCheck(
        val flowErrorPx: Double,
        val coverage: Double,
        val rmseMerge: Double,
        val rmseSingle: Double,
        val gain: Double,
        val ms: Double
    )

    /** The native synthetic super-resolution check (known sub-pixel shifts, grating on texture). Throws like [mergeBurst]. */
    fun superResSelfCheck(threads: Int): SrSelfCheck {
        check(load().loaded) { "native library not loaded: ${load().error}" }
        val r = nativeSrSelfCheck(threads)
        return SrSelfCheck(r[0].toDouble(), r[1].toDouble(), r[2].toDouble(), r[3].toDouble(), r[4].toDouble(), r[5].toDouble())
    }

    internal const val SR_HEADER = 20
    internal const val SR_PER_FRAME = 11

    @JvmStatic
    private external fun nativeSrCreate(
        width: Int,
        height: Int,
        cfa: Int,
        black: FloatArray,
        white: Float,
        noiseS: Double,
        noiseO: Double,
        shading: FloatArray?,
        shadingRows: Int,
        shadingCols: Int,
        crop: IntArray,
        outWidth: Int,
        outHeight: Int,
        reference: Int,
        threads: Int
    ): Long

    @JvmStatic
    private external fun nativeSrAdd(handle: Long, frame: ByteBuffer, rowStrideBytes: Int): IntArray

    @JvmStatic
    private external fun nativeSrFinish(handle: Long, shading: FloatArray?, shadingRows: Int, shadingCols: Int): FloatArray

    @JvmStatic
    private external fun nativeSrStats(handle: Long, matrix: FloatArray, neutral: FloatArray, step: Int): FloatArray

    @JvmStatic
    private external fun nativeSrRender(
        handle: Long,
        matrix: FloatArray,
        neutral: FloatArray,
        gain: Float,
        curve: FloatArray,
        curveLo: Float,
        curveHi: Float,
        lut: FloatArray,
        lutSize: Int,
        threads: Int,
        out: Bitmap
    ): String?

    @JvmStatic
    private external fun nativeSrDestroy(handle: Long)

    @JvmStatic
    private external fun nativeSrSelfCheck(threads: Int): FloatArray

    private const val MERGE_HEADER = 12
    private const val MERGE_PER_FRAME = 7

    @JvmStatic
    private external fun nativeAllocFrame(bytes: Long): ByteBuffer?

    @JvmStatic
    private external fun nativeFreeFrame(buffer: ByteBuffer)

    @JvmStatic
    private external fun nativeMergeBurst(
        buffers: Array<ByteBuffer>,
        width: Int,
        height: Int,
        rowStrideBytes: Int,
        black: FloatArray,
        white: Float,
        noiseS: Double,
        noiseO: Double,
        reference: Int,
        threads: Int,
        out: ByteBuffer,
        outScale: Float
    ): FloatArray

    @JvmStatic
    private external fun nativeBurstSelfCheck(threads: Int): FloatArray

    @JvmStatic
    private external fun nativeMergerCreate(
        width: Int,
        height: Int,
        black: FloatArray,
        white: Float,
        noiseS: Double,
        noiseO: Double,
        reference: Int,
        threads: Int
    ): Long

    @JvmStatic
    private external fun nativeMergerAdd(handle: Long, frame: ByteBuffer, rowStrideBytes: Int): IntArray

    @JvmStatic
    private external fun nativeMergerFinish(handle: Long, out: ByteBuffer?, rowStrideBytes: Int, outScale: Float): FloatArray

    @JvmStatic
    private external fun nativeMergerDestroy(handle: Long)

    @JvmStatic
    private external fun nativeBurstThreads(): Int

    @JvmStatic
    private external fun nativeBurstWorkerNice(nice: Int)

    @JvmStatic
    private external fun nativeVersion(): String

    @JvmStatic
    private external fun nativeRawStats(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStrideBytes: Int,
        cfa: Int,
        black: FloatArray,
        white: Float,
        shading: FloatArray?,
        shadingRows: Int,
        shadingCols: Int,
        crop: IntArray,
        matrix: FloatArray,
        neutral: FloatArray,
        step: Int
    ): FloatArray

    @JvmStatic
    private external fun nativeRenderRaw(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStrideBytes: Int,
        cfa: Int,
        black: FloatArray,
        white: Float,
        shading: FloatArray?,
        shadingRows: Int,
        shadingCols: Int,
        crop: IntArray,
        matrix: FloatArray,
        neutral: FloatArray,
        gain: Float,
        curve: FloatArray,
        curveLo: Float,
        curveHi: Float,
        lut: FloatArray,
        lutSize: Int,
        threads: Int,
        out: Bitmap
    ): String?

    @JvmStatic
    private external fun nativeParseCube(bytes: ByteArray): FloatArray
}
