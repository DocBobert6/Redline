package com.redline.camera.capture

import com.redline.camera.nativebridge.RedlineNative
import java.nio.ByteBuffer

/** B23: what a super-resolution merge needs beyond the B22 merge: the CFA layout, the stop's RAW crop and the output size. */
class SuperResSetup(val cfa: Int, val crop: IntArray, val outWidth: Int, val outHeight: Int)

/**
 * The merge-thread side of one burst (B22a). Every method runs on the single merge thread, in order: [start] once
 * the black/white levels and noise model are known (from the first burst frame's capture result), [add] for each
 * RAW frame in capture order as it arrives, then [finish], then [close]. A failure at any step is kept in [error];
 * later calls do nothing, and the caller renders one of the frames it still holds instead (the B21 single-frame path).
 *
 * B23: with [superRes] the frames go to the super-resolution merger instead. It copies each frame's crop region and
 * hands the buffer back at once, except frame 0, which the caller keeps (it is the DNG and the fallback render).
 * [finishSuperRes] leaves the gathered image in [superResImage]; whoever takes it ([takeSuperRes]) closes it.
 */
class StreamingMerge(private val threads: Int, val superRes: SuperResSetup? = null) {
    private var merger: RedlineNative.BurstMerger? = null
    private var srMerger: RedlineNative.SuperResMerger? = null

    /** B23: the finished super-resolution merge (render it, then close), until taken. */
    var superResImage: RedlineNative.SuperResMerger? = null
        private set

    @Volatile var error: String? = null
        private set
    var noiseModel: String? = null
        private set
    var scale: Float = StillSaver.MERGE_SCALE
        private set

    /** Frames handed to the native merger so far. */
    @Volatile var added: Int = 0
        private set

    fun start(levels: Pair<FloatArray, Float>?, noise: Triple<Double, Double, String>, width: Int, height: Int) {
        if (error != null || merger != null) return
        if (levels == null) {
            error = "merge: no black/white levels"
            return
        }
        noiseModel = noise.third
        scale = StillSaver.mergeScale(levels.second)
        try {
            val sr = superRes
            if (sr != null) {
                srMerger = RedlineNative.superResMerger(
                    width, height, sr.cfa, levels.first, levels.second, noise.first, noise.second,
                    sr.crop, sr.outWidth, sr.outHeight, -1, threads
                )
            } else {
                merger = RedlineNative.burstMerger(width, height, levels.first, levels.second, noise.first, noise.second, -1, threads)
            }
        } catch (e: Exception) {
            error = "merge: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /** Merges the next frame; returns the indices (capture order) whose buffers are free again, or null after a failure. */
    fun add(frame: RawCopy): IntArray? {
        if (error != null) return null
        val sr = srMerger
        if (sr != null) {
            return try {
                // Frame 0 stays with the caller (DNG and fallback); every other buffer is free once its region is copied.
                sr.add(frame.buffer, frame.rowStrideBytes).also { added++ }.filter { it != 0 }.toIntArray()
            } catch (e: Exception) {
                error = "superres: ${e.javaClass.simpleName}: ${e.message}"
                null
            } catch (e: OutOfMemoryError) {
                error = "superres: out of memory"
                null
            }
        }
        val m = merger ?: return null
        return try {
            m.add(frame.buffer, frame.rowStrideBytes).also { added++ }
        } catch (e: Exception) {
            error = "merge: ${e.javaClass.simpleName}: ${e.message}"
            null
        } catch (e: OutOfMemoryError) {
            error = "merge: out of memory"
            null
        }
    }

    /** Writes the merged mosaic into [out], or (null) in place over the reference frame when no DNG needs it untouched. */
    fun finish(out: ByteBuffer?, rowStrideBytes: Int): RedlineNative.MergeStats? {
        val m = merger ?: return null
        if (error != null) return null
        return try {
            m.finish(out, rowStrideBytes, scale)
        } catch (e: Exception) {
            error = "merge: ${e.javaClass.simpleName}: ${e.message}"
            null
        }
    }

    /** B23: gathers the super-resolution merge ([shading]: a burst frame's lens shading map, Camera2 layout, or null). */
    fun finishSuperRes(shading: FloatArray?, rows: Int, cols: Int): RedlineNative.SrStats? {
        val m = srMerger ?: return null
        if (error != null) return null
        return try {
            m.finish(shading, rows, cols).also {
                superResImage = m
                srMerger = null
            }
        } catch (e: Exception) {
            error = "superres: ${e.javaClass.simpleName}: ${e.message}"
            null
        } catch (e: OutOfMemoryError) {
            error = "superres: out of memory"
            null
        }
    }

    /** B23: hands the finished image over (the taker closes it); null when there is none. */
    fun takeSuperRes(): RedlineNative.SuperResMerger? = superResImage.also { superResImage = null }

    fun close() {
        merger?.close()
        merger = null
        srMerger?.close()
        srMerger = null
        superResImage?.close()
        superResImage = null
    }

    /** Marks the merge failed (frames lost, timeout, session closed) so nothing more is merged. */
    fun fail(why: String) {
        if (error == null) error = why
    }
}
