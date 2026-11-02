package com.redline.camera.capture

import java.nio.ByteBuffer

/**
 * B24.1: the pool buffers one shot holds from capture to save. The saver hands most of them back early
 * ([releaseExcept], right after the RAW render: only a DNG's own frame is still needed) and the rest at the end
 * ([releaseAll]). Each buffer goes back exactly once; [onEmpty] runs once, when the shot holds nothing any more.
 * Thread-safe.
 */
class ShotBuffers(buffers: List<ByteBuffer>, private val release: (List<ByteBuffer>) -> Unit, private val onEmpty: () -> Unit = {}) {
    private val held = ArrayList(buffers)
    private var emptied = false

    @Synchronized
    fun held(): Int = held.size

    /** Hands back every buffer but [keep] (identity; null = all of them). */
    fun releaseExcept(keep: ByteBuffer?) = give { held.filter { it !== keep } }

    fun releaseAll() = give { held.toList() }

    private fun give(pick: () -> List<ByteBuffer>) {
        val out: List<ByteBuffer>
        val nowEmpty: Boolean
        synchronized(this) {
            out = pick()
            held.removeAll { b -> out.any { it === b } }
            nowEmpty = held.isEmpty() && !emptied
            if (nowEmpty) emptied = true
        }
        if (out.isNotEmpty()) release(out)
        if (nowEmpty) onEmpty()
    }
}
