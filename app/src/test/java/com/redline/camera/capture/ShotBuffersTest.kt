package com.redline.camera.capture

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** B24.1: a shot's pool buffers go back early (all but the DNG's frame), the rest at the end, each exactly once. */
class ShotBuffersTest {
    private fun bufs(n: Int) = List(n) { ByteBuffer.allocateDirect(8) }

    @Test
    fun earlyReleaseKeepsOnlyTheDngFrame() {
        val b = bufs(3)
        val back = mutableListOf<ByteBuffer>()
        var emptied = 0
        val s = ShotBuffers(b, { back += it }) { emptied++ }
        s.releaseExcept(b[1])
        assertEquals(2, back.size)
        assertTrue(back.none { it === b[1] })
        assertEquals(0, emptied)
        assertEquals(1, s.held())
        s.releaseAll()
        assertEquals(3, back.size)
        assertEquals(1, emptied)
        s.releaseAll()
        s.releaseExcept(null)
        assertEquals(3, back.size) // never twice
        assertEquals(1, emptied)
    }

    @Test
    fun noDngMeansEverythingGoesBackAfterTheRender() {
        val b = bufs(1)
        var back = 0
        var emptied = 0
        val s = ShotBuffers(b, { back += it.size }) { emptied++ }
        s.releaseExcept(null)
        assertEquals(1, back)
        assertEquals(1, emptied) // a single leaves the holding queue as soon as its frame is rendered
        s.releaseAll()
        assertEquals(1, back)
        assertEquals(1, emptied)
    }

    @Test
    fun aShotWithoutBuffersEmptiesOnce() {
        var emptied = 0
        val s = ShotBuffers(emptyList(), { error("nothing to release") }) { emptied++ }
        s.releaseAll()
        s.releaseAll()
        assertEquals(1, emptied)
    }

    @Test
    fun poolReportsReleases() {
        var released = 0
        val p = FramePool(8, 2, { ByteBuffer.allocateDirect(it.toInt()) }, {}) { released++ }
        val a = p.acquire(2)
        assertEquals(0, p.available())
        p.release(a)
        p.release(emptyList())
        assertEquals(1, released)
        assertEquals(2, p.available())
    }
}
