package com.redline.camera.capture

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FramePoolTest {
    private var allocs = 0
    private var frees = 0
    private var failAfter = Int.MAX_VALUE

    private fun pool(capacity: Int) = FramePool(16, capacity, {
        if (allocs >= failAfter) {
            null
        } else {
            allocs++
            ByteBuffer.allocateDirect(it.toInt())
        }
    }, { frees++ })

    @Test
    fun acquiresUpToCapacityAndReuses() {
        val p = pool(4)
        val a = p.acquire(3)
        assertEquals(3, a.size)
        assertEquals(1, p.available())
        val c = p.acquire(5)
        assertEquals(1, c.size) // only one left; it stays in use below
        p.release(a)
        assertEquals(3, p.available())
        val b = p.acquire(2)
        assertEquals(2, b.size)
        assertEquals(4, allocs) // reused, not reallocated
    }

    @Test
    fun allocationFailureGivesFewer() {
        failAfter = 2
        val p = pool(8)
        assertEquals(2, p.acquire(5).size)
    }

    @Test
    fun trimFreesOnlyIdle() {
        val p = pool(4)
        val a = p.acquire(3)
        p.release(a.take(2))
        p.trimIdle()
        assertEquals(2, frees)
        assertEquals(1, p.inUse())
        assertEquals(1, p.allocated())
    }

    @Test
    fun shrinkingCapacityFreesReturnedBuffers() {
        val p = pool(4)
        val a = p.acquire(4)
        p.capacity = 2
        p.release(a)
        assertEquals(2, frees)
        assertEquals(2, p.allocated())
    }

    @Test
    fun rawCopyCompactsStridedRows() {
        val w = 3
        val h = 2
        val stride = 10 // bytes: 6 used + 4 padding
        val src = ByteBuffer.allocateDirect(stride * (h - 1) + w * 2)
        for (y in 0 until h) for (i in 0 until w * 2) src.put(y * stride + i, (y * 16 + i).toByte())
        val dst = ByteBuffer.allocateDirect(w * h * 2)
        val c = RawCopy.copy(src, stride, w, h, dst, 42L)
        assertEquals(6, c.rowStrideBytes)
        assertEquals(42L, c.timestampNs)
        for (y in 0 until h) for (i in 0 until w * 2) assertEquals((y * 16 + i).toByte(), dst.get(y * 6 + i))
        // Compact source: one bulk copy.
        val compact = ByteBuffer.allocateDirect(w * h * 2).also { for (i in 0 until 12) it.put(i, i.toByte()) }
        RawCopy.copy(compact, 6, w, h, dst, 0)
        assertTrue((0 until 12).all { dst.get(it) == it.toByte() })
    }
}
