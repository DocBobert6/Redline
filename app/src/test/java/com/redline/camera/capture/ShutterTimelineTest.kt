package com.redline.camera.capture

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ShutterTimelineTest {
    @Test
    fun timelineReportsOffsetsFromThePress() {
        val t = ShotTimeline(pressMs = 1_000)
        t.mark(ShotTimeline.START, 1_002)
        t.mark(ShotTimeline.SUBMIT, 1_010)
        t.mark(ShotTimeline.SUBMIT, 1_900) // only the first mark counts
        // RAW frames 1..3 at 33.3 ms, then the JPEG frame (index 0) 40 ms after the last.
        t.started(1, 1_060, 5_000_000_000L)
        t.started(2, 1_093, 5_033_300_000L)
        t.started(3, 1_126, 5_066_600_000L)
        t.started(0, 1_160, 5_106_600_000L)
        t.rawArrived(1_110, 6)
        t.rawArrived(1_143, 7)
        t.rawArrived(1_176, 6)
        t.result(1, 1_120)
        t.mergeAdded(1_170)
        t.mark(ShotTimeline.MERGE_START, 1_112)
        t.mark(ShotTimeline.RELEASED, 1_180)
        t.mark(ShotTimeline.JPEG, 1_400)
        t.mark(ShotTimeline.SAVED, 2_200)
        val m = t.toMap()

        @Suppress("UNCHECKED_CAST")
        val marks = m["marks_ms"] as Map<String, Long>
        assertEquals(10L, marks[ShotTimeline.SUBMIT])
        assertEquals(listOf(110L, 143L, 176L), m["raw_arrival_ms"])
        assertEquals(listOf(33.3, 33.3), m["sensor_frame_gaps_ms"])
        assertEquals(40.0, m["jpeg_frame_vs_last_raw_sensor_ms"])
        @Suppress("UNCHECKED_CAST")
        val phases = m["phases_ms"] as Map<String, Long?>
        assertEquals(10L, phases["press_to_submit"])
        assertEquals(50L, phases["submit_to_first_started"])
        assertEquals(100L, phases["submit_to_first_raw"])
        assertEquals(66L, phases["first_to_last_raw"])
        assertEquals(4L, phases["last_raw_to_release"])
        assertEquals(180L, phases["press_to_release"])
        assertEquals(220L, phases["release_to_jpeg"])
        assertEquals(2L, phases["merge_start_after_first_raw"])
        assertEquals(1_200L, phases["press_to_saved"])
        assertEquals(180L, t.releaseMs())
    }

    @Test
    fun emptyTimelineHasNullPhases() {
        val t = ShotTimeline(pressMs = 5)
        assertNull(t.releaseMs())
        @Suppress("UNCHECKED_CAST")
        val phases = t.toMap()["phases_ms"] as Map<String, Long?>
        assertTrue(phases.values.all { it == null })
    }

    @Test
    fun pressPolicyCapsWaitingPresses() {
        assertTrue(PressPolicy.accepts(0))
        assertTrue(PressPolicy.accepts(1)) // one capturing, none waiting
        assertTrue(PressPolicy.accepts(PressPolicy.MAX_WAITING_PRESSES))
        assertFalse(PressPolicy.accepts(PressPolicy.MAX_WAITING_PRESSES + 1))
        assertEquals(0, PressPolicy.waiting(0))
        assertEquals(0, PressPolicy.waiting(1))
        assertEquals(2, PressPolicy.waiting(3))
    }

    @Test
    fun indicatorNeverLeavesAPressSilent() {
        assertNull(PressPolicy.indicator(0, 0))
        assertEquals("Capturing", PressPolicy.indicator(1, 0))
        assertEquals("Capturing · 2 queued", PressPolicy.indicator(3, 0))
        assertEquals("Processing 2", PressPolicy.indicator(0, 2))
        assertEquals("Capturing · 1 queued · Processing 1", PressPolicy.indicator(2, 1))
    }

    @Test
    fun earlyNoiseScalesWithGain() {
        val (s, o) = EarlyNoise.scale(1e-5, 1e-8, 100, 400)!!
        assertEquals(4e-5, s, 1e-12)
        assertEquals(16e-8, o, 1e-15)
        assertNull(EarlyNoise.scale(1e-5, 1e-8, null, 400))
        assertNull(EarlyNoise.scale(1e-5, 1e-8, 100, 0))
    }

    @Test
    fun burstReleasesOnceEveryFrameIsInOrLost() {
        assertFalse(BurstRelease.allFramesIn(frames = 6, copied = 5, lost = 0))
        assertTrue(BurstRelease.allFramesIn(frames = 6, copied = 6, lost = 0))
        assertTrue(BurstRelease.allFramesIn(frames = 6, copied = 4, lost = 2))
        assertTrue(BurstRelease.allFramesIn(frames = 6, copied = 0, lost = 6))
    }

    @Test
    fun identitySetKeepsEqualContentBuffersApart() {
        // Two fresh direct buffers are equal() and hash alike (all zero): a HashSet keeps one, which leaked pool buffers.
        val a = ByteBuffer.allocateDirect(64)
        val b = ByteBuffer.allocateDirect(64)
        assertEquals(a, b)
        assertEquals(1, hashSetOf(a, b).size)
        val set = identitySet<ByteBuffer>()
        set += a
        set += b
        assertEquals(2, set.size)
        assertTrue(set.remove(b))
        assertSame(a, set.single())
        assertFalse(set.remove(ByteBuffer.allocateDirect(64)))
    }

    @Test
    fun droppedShotIsNotACaptureFailure() {
        val dropped = ShotResult(ok = false, error = "dropped: x", dropped = DropReason.LEFT_VIEWFINDER)
        val stats = CaptureStats().add(dropped, stress = false)
        assertEquals(0, stats.failures)
        assertEquals(0, stats.shots)
        assertEquals(1, stats.dropped)
        assertNull(stats.lastError)
        assertEquals("Shot dropped: left the viewfinder", stats.last)
        val failed = stats.add(ShotResult(ok = false, error = "capture failed (reason 1)"), stress = false)
        assertEquals(1, failed.failures)
        assertEquals("capture failed (reason 1)", failed.lastError)
        val refused = ShotResult(ok = false, dropped = PressPolicy.refusedReason(), shutterBlocked = true)
        assertEquals("Not taken: ${PressPolicy.refusedReason()}", refused.summary())
    }

    @Test
    fun burstReportCountsDropsAndRefusals() {
        val r = BurstReport()
            .press(0, blocked = true, stress = false).drop(PressPolicy.refusedReason(), refused = true)
            .press(100, blocked = false, stress = false).drop(DropReason.LEFT_VIEWFINDER, refused = false)
            .drop(DropReason.LEFT_VIEWFINDER, refused = false)
        assertEquals(1, r.refusedPresses)
        assertEquals(1, r.shutterBlocked)
        assertEquals(2, r.droppedShots)
        assertEquals(mapOf(DropReason.LEFT_VIEWFINDER to 2), r.dropReasons)
        val m = r.toMap()
        assertEquals(1, m["refused_presses_queue_full"])
        assertEquals(2, m["dropped_shots"])
        assertEquals(PressPolicy.MAX_WAITING_PRESSES, m["max_waiting_presses"])
    }

    @Test
    fun burstReportKeepsRecentTimelines() {
        val plan =
            BurstPlan(frames = 6, iso = 144, exposureNs = 20_000_000L, frameDurationNs = 33_333_333L, light = "good", residentFrames = 6)
        var r = BurstReport()
        repeat(BurstReport.RECENT_TIMELINES + 2) { i ->
            val shot = BurstShot(
                plan = plan,
                stopMm = 24,
                framesCaptured = 6,
                framesMerged = 6,
                merged = true,
                timingsMs = mapOf("capture" to 300L, "shutter_release" to 310L + i, "merge_tail" to 150L),
                timeline = mapOf("marks_ms" to mapOf("released" to 310L + i))
            )
            r = r.record(shot, 1_200, ok = true)
        }
        assertEquals(BurstReport.RECENT_TIMELINES, r.recentTimelines.size)
        assertEquals("burst", r.recentTimelines.last()["path"])
        assertEquals(mapOf("released" to 315L), r.recentTimelines.last()["marks_ms"])
        assertNotNull(r.last?.toMap()?.get("timeline"))
        assertEquals(315L, r.recent.last()["shutter_release_ms"])
        assertEquals(150L, r.recent.last()["merge_tail_ms"])
    }

    @Test
    fun theDngTakesItsOwnFramesResultNotTheSuperResReference() {
        // Burst results by sensor timestamp: frame 0 at 1000, frame 1 (the SR reference) at 2000.
        data class R(val ts: Long?, val name: String)
        val results = listOf(R(2000, "frame1"), R(1000, "frame0"), R(3000, "frame2"))
        val jpegFrame = R(null, "jpeg")
        assertEquals("frame0", FrameResults.forFrame(1000L, results, { it.ts }, jpegFrame).name)
        assertEquals("frame1", FrameResults.forFrame(2000L, results, { it.ts }, jpegFrame).name)
        // No match: the first burst result; no burst result at all: the JPEG frame's.
        assertEquals("frame1", FrameResults.forFrame(9999L, results, { it.ts }, jpegFrame).name)
        assertEquals("jpeg", FrameResults.forFrame(1000L, emptyList(), { it.ts }, jpegFrame).name)
    }
}
