package com.redline.camera.viewfinder

import com.redline.camera.capability.Dim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** B23b.1: the honest under-load preview meter, the render size of the LUT pass, and the fixed-range frame duration. */
class PreviewLoadTest {
    private val frameNs = 33_333_333L
    private val slowNs = 41_666_667L // a 24 fps frame

    /** Feeds [n] frames [dt] apart after [start]; returns the last timestamp. */
    private fun PreviewLoadMeter.feed(start: Long, n: Int, dt: Long = frameNs): Long {
        var t = start
        repeat(n) {
            t += dt
            frame(t)
        }
        return t
    }

    @Test
    fun steady30FpsReadsThirtyWithNoDrops() {
        val m = PreviewLoadMeter(30)
        m.frame(0)
        m.feed(0, 40)
        assertEquals(30.0, m.fps()!!, 0.01)
        assertEquals(0, m.dropped)
        assertEquals(40, m.intervals)
        assertEquals(0, m.pauses)
    }

    @Test
    fun noReadingUntilTheWindowHoldsEnoughFrames() {
        val m = PreviewLoadMeter(30)
        m.frame(0)
        m.feed(0, PreviewLoadMeter.MIN_WINDOW_FRAMES - 2)
        assertNull(m.fps())
        m.feed((PreviewLoadMeter.MIN_WINDOW_FRAMES - 2) * frameNs, 1)
        assertEquals(30.0, m.fps()!!, 0.01)
    }

    @Test
    fun burstPauseAndSlowResumeAreExcludedNotCountedAsDrops() {
        val m = PreviewLoadMeter(30)
        m.frame(0)
        var t = m.feed(0, 20)
        m.captureStarted(7)
        // Frames whose results arrive while the burst is in flight (queued before it) are not counted.
        t = m.feed(t, 2)
        assertEquals(2, m.inFlightFrames)
        m.captureEnded(7)
        // The pause: 300 ms without a preview frame, then two 24 fps frames, then 30 fps again.
        t += 300_000_000L
        m.frame(t)
        t = m.feed(t, 2, slowNs)
        t = m.feed(t, 30)
        assertEquals(1, m.pauses)
        assertEquals(300.0, m.pauseNs / 1e6, 0.01)
        assertEquals(3, m.warmupFrames) // the first frame after the pause and the two slow ones
        assertEquals(0, m.dropped)
        assertEquals(30.0, m.fps()!!, 0.01)
        assertEquals(33.33, m.worstNs / 1e6, 0.01)
        val r = m.reading()
        assertEquals(1, r.recentResumes.size)
        assertEquals(listOf(41.7, 41.7, 33.3, 33.3), r.recentResumes[0].toMap()["intervals_ms"])
    }

    @Test
    fun warmUpEndsAtTheFirstNormalInterval() {
        val m = PreviewLoadMeter(30)
        m.frame(0)
        var t = m.feed(0, 10)
        m.captureStarted(1)
        m.captureEnded(1)
        t += 250_000_000L
        m.frame(t)
        t = m.feed(t, 1) // a normal interval at once: counted
        assertEquals(1, m.warmupFrames)
        assertEquals(11, m.intervals)
        m.feed(t, 10)
        assertEquals(30.0, m.fps()!!, 0.01)
    }

    @Test
    fun aStream24FpsAfterTheWarmUpIsNotHidden() {
        val m = PreviewLoadMeter(30)
        m.frame(0)
        var t = m.feed(0, 10)
        m.captureStarted(1)
        m.captureEnded(1)
        t += 250_000_000L
        m.frame(t)
        m.feed(t, 20, slowNs)
        // Three frames of warm-up at most; the rest count, and the reading shows 24 fps.
        assertEquals(PreviewLoadMeter.WARMUP_FRAMES, m.warmupFrames)
        assertEquals(24.0, m.fps()!!, 0.01)
        assertTrue(m.fps()!! < 29.5)
    }

    @Test
    fun aDropWithNoCaptureInFlightCounts() {
        val m = PreviewLoadMeter(30)
        m.frame(0)
        var t = m.feed(0, 20)
        t += 2 * frameNs // one frame missing: a 66.7 ms interval
        m.frame(t)
        m.feed(t, 5)
        assertEquals(1, m.dropped)
        assertEquals(0, m.pauses)
        assertTrue(m.fps()!! < 29.5)
        assertEquals(66.67, m.worstNs / 1e6, 0.01)
    }

    @Test
    fun captureEndIsIdempotentAndANewSessionClearsCaptures() {
        val m = PreviewLoadMeter(30)
        m.captureStarted(1)
        m.captureStarted(2)
        m.captureEnded(1)
        m.captureEnded(1)
        assertTrue(m.capturingNow)
        m.restart()
        assertTrue(m.capturingNow) // a new repeating request does not end a burst
        m.restart(clearCaptures = true)
        assertFalse(m.capturingNow)
    }

    @Test
    fun resumeSamplesKeepFrameDurationsAndAreBounded() {
        val m = PreviewLoadMeter(30)
        var t = 0L
        m.frame(t, frameNs)
        for (i in 0 until 10) {
            m.captureStarted(i)
            m.captureEnded(i)
            t += 200_000_000L
            m.frame(t, slowNs)
            repeat(5) {
                t += frameNs
                m.frame(t, frameNs)
            }
        }
        val r = m.reading()
        assertEquals(10, r.pauses)
        assertEquals(6, r.recentResumes.size)
        val last = r.recentResumes.last().toMap()
        assertEquals(41.7, (last["frame_durations_ms"] as List<*>).first())
        assertEquals(200.0, last["pause_ms"])
    }

    @Test
    fun outOfOrderTimestampsAreIgnored() {
        val m = PreviewLoadMeter(30)
        m.frame(100)
        m.frame(100)
        m.frame(50)
        assertEquals(0, m.intervals)
    }

    // ---------------------------------------------------------------- LUT pass size, frame duration

    @Test
    fun lookRendersAtTheViewSizeOnScreen() {
        val buffer = Dim(1920, 1440)
        // Portrait phone, 3:2 box 1080 x 1620: the fill scale is 1620 / 1920.
        assertEquals(Dim(1620, 1216), LookRenderSize.choose(buffer, 1080, 1620, 90, 0))
        // A view larger than the buffer on screen: the buffer itself.
        assertEquals(buffer, LookRenderSize.choose(buffer, 1440, 2160, 90, 0))
        // Unknown view size: the buffer.
        assertEquals(buffer, LookRenderSize.choose(buffer, 0, 0, 90, 0))
        // Tiny views keep at least MIN_SIDE on the short side.
        val small = LookRenderSize.choose(buffer, 100, 150, 90, 0)
        assertEquals(LookRenderSize.MIN_SIDE, minOf(small.width, small.height))
        // Same aspect as the buffer (within rounding to even sizes).
        val d = LookRenderSize.choose(buffer, 1080, 1620, 90, 0)
        assertEquals(4.0 / 3.0, d.width.toDouble() / d.height, 0.01)
    }

    @Test
    fun fixedRangeGetsAOneOverNFrameDuration() {
        assertEquals(33_333_333L, PreviewFps.fixedFrameDurationNs(30 to 30))
        assertNull(PreviewFps.fixedFrameDurationNs(24 to 30))
        assertNull(PreviewFps.fixedFrameDurationNs(null))
    }
}
