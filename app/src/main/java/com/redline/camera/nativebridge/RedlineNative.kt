package com.redline.camera.nativebridge

import android.graphics.Bitmap
import com.redline.camera.look.CubeLut
import java.nio.ByteBuffer

/**
 * JNI wrapper for libredline_native.so (B20 LUT parser, B21 RAW colour pipeline, B22 burst merge; app/src/main/cpp). The library is loaded lazily and at most once;
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
            r[7].toDouble(), r[8].toDouble(), per
        )
    }

    class BurstSelfCheck(val alignedFraction: Double, val noiseRatio: Double, val ms: Double)

    /** The native synthetic burst check (known shifts + noise). Throws like [mergeBurst]. */
    fun burstSelfCheck(threads: Int): BurstSelfCheck {
        check(load().loaded) { "native library not loaded: ${load().error}" }
        val r = nativeBurstSelfCheck(threads)
        return BurstSelfCheck(r[0].toDouble(), r[1].toDouble(), r[2].toDouble())
    }

    private const val MERGE_HEADER = 9
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
