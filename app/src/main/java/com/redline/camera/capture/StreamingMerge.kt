package com.redline.camera.capture

import com.redline.camera.nativebridge.RedlineNative
import java.nio.ByteBuffer

/**
 * The merge-thread side of one burst (B22a). Every method runs on the single merge thread, in order: [start] once
 * the black/white levels and noise model are known (from the first burst frame's capture result), [add] for each
 * RAW frame in capture order as it arrives, then [finish], then [close]. A failure at any step is kept in [error];
 * later calls do nothing, and the caller renders one of the frames it still holds instead (the B21 single-frame path).
 */
class StreamingMerge(private val threads: Int) {
    private var merger: RedlineNative.BurstMerger? = null

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
            merger = RedlineNative.burstMerger(width, height, levels.first, levels.second, noise.first, noise.second, -1, threads)
        } catch (e: Exception) {
            error = "merge: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /** Merges the next frame; returns the indices (capture order) whose buffers are free again, or null after a failure. */
    fun add(frame: RawCopy): IntArray? {
        val m = merger ?: return null
        if (error != null) return null
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

    fun close() {
        merger?.close()
        merger = null
    }

    /** Marks the merge failed (frames lost, timeout, session closed) so nothing more is merged. */
    fun fail(why: String) {
        if (error == null) error = why
    }
}
