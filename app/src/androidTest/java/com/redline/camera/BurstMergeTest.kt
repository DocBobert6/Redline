package com.redline.camera

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.redline.camera.capture.BurstReport
import com.redline.camera.capture.FramePool
import com.redline.camera.capture.RawCopy
import com.redline.camera.nativebridge.RedlineNative
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B22 on a real Android runtime (CPU only, so it runs on the emulator, which has no RAW stream): the native burst
 * merge loads for this ABI, passes its synthetic self-check (the start-up gate for the Info toggle), and merges
 * frames held in native pool buffers through JNI into a x16-scaled output.
 */
@RunWith(AndroidJUnit4::class)
class BurstMergeTest {
    @Test
    fun selfCheckPasses() {
        val c = RedlineNative.burstSelfCheck(4)
        assertTrue("aligned ${c.alignedFraction}", c.alignedFraction >= BurstReport.SELF_CHECK_MIN_ALIGNED)
        assertTrue("noise ratio ${c.noiseRatio}", c.noiseRatio >= BurstReport.SELF_CHECK_MIN_NOISE_RATIO)
    }

    @Test
    fun mergesPoolFramesThroughJni() {
        val w = 128
        val h = 96
        val pool = FramePool(w * h * 2L, 4, { RedlineNative.allocFrame(it) }, { RedlineNative.freeFrame(it) })
        val bufs = pool.acquire(4)
        assertEquals(4, bufs.size)
        try {
            // Three identical textured frames: the merge must return the frame itself (x16), with no motion.
            val src = java.nio.ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.nativeOrder())
            for (i in 0 until w * h) src.putShort(i * 2, (64 + (i * 37) % 900).toShort())
            val frames = (0 until 3).map { RawCopy.copy(src, w * 2, w, h, bufs[it].order(ByteOrder.nativeOrder()), it.toLong()) }
            val out = bufs[3].order(ByteOrder.nativeOrder())
            val stats = RedlineNative.mergeBurst(
                frames.map { it.buffer },
                w,
                h,
                w * 2,
                floatArrayOf(64f, 64f, 64f, 64f),
                1023f,
                2e-3,
                2e-5,
                -1,
                2,
                out,
                16f
            )
            assertNotNull(stats)
            assertEquals(3, stats.frames)
            assertEquals(2, stats.perFrame.size)
            assertTrue(stats.perFrame.all { it.maxDispPx == 0.0 && it.meanWeight > 0.999 })
            for (i in listOf(0, 77, w * h - 1)) {
                assertEquals((src.getShort(i * 2).toInt() and 0xFFFF) * 16, out.getShort(i * 2).toInt() and 0xFFFF)
            }
        } finally {
            pool.release(bufs)
            pool.trimIdle()
        }
    }
}
