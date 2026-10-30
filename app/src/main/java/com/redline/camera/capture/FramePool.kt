package com.redline.camera.capture

import java.nio.ByteBuffer

/**
 * A copy of one RAW_SENSOR frame in a pool buffer (B22): compact rows (row stride = width * 2), so the camera's
 * Image goes back to its ImageReader at once and a queued shot never holds a camera buffer.
 */
class RawCopy(val buffer: ByteBuffer, val width: Int, val height: Int, val timestampNs: Long) {
    val rowStrideBytes: Int get() = width * 2

    companion object {
        /** Copies a RAW_SENSOR plane ([src] from position 0, [srcRowStride] bytes per row) into [dst]. */
        fun copy(src: ByteBuffer, srcRowStride: Int, width: Int, height: Int, dst: ByteBuffer, timestampNs: Long): RawCopy {
            val row = width * 2
            require(dst.capacity() >= row * height) { "pool buffer too small" }
            val s = src.duplicate()
            val d = dst.duplicate()
            d.clear()
            if (srcRowStride == row) {
                s.position(0)
                s.limit(row * height)
                d.put(s)
            } else {
                for (y in 0 until height) {
                    val start = y * srcRowStride
                    s.limit(start + row)
                    s.position(start)
                    d.put(s)
                }
            }
            return RawCopy(dst, width, height, timestampNs)
        }
    }
}

/**
 * Frame-sized buffers in native memory, reused across shots (B22). At most [capacity] exist at once; [acquire]
 * returns null when all are in use or the allocation fails, and the shutter then plans fewer frames (or none)
 * instead of waiting. Idle buffers are freed by [trimIdle] when the queue has been empty for a while. Thread-safe.
 */
class FramePool(
    val frameBytes: Long,
    @Volatile var capacity: Int,
    private val alloc: (Long) -> ByteBuffer?,
    private val free: (ByteBuffer) -> Unit
) {
    private val idle = ArrayDeque<ByteBuffer>()
    private var inUse = 0

    /** Buffers that could still be handed out (idle + not yet allocated). */
    @Synchronized
    fun available(): Int = (capacity - inUse).coerceAtLeast(0)

    @Synchronized
    fun inUse(): Int = inUse

    @Synchronized
    fun allocated(): Int = inUse + idle.size

    /** Up to [n] buffers (fewer when the pool is short); hand every one back with [release]. */
    @Synchronized
    fun acquire(n: Int): List<ByteBuffer> {
        val out = ArrayList<ByteBuffer>(n)
        while (out.size < n && inUse < capacity) {
            val b = idle.removeFirstOrNull() ?: alloc(frameBytes) ?: break
            inUse++
            out += b
        }
        return out
    }

    @Synchronized
    fun release(buffers: Collection<ByteBuffer>) {
        for (b in buffers) {
            inUse--
            if (idle.size + inUse < capacity) idle.addLast(b) else free(b)
        }
    }

    /** Frees the idle buffers (memory goes back to the system); buffers in use are untouched. */
    @Synchronized
    fun trimIdle() {
        while (idle.isNotEmpty()) free(idle.removeFirst())
    }
}
