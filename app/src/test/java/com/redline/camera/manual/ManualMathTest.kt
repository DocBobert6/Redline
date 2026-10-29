package com.redline.camera.manual

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** B14 gate, unit half: every ladder and clamp stays inside the ranges the camera reports. */
class ManualMathTest {
    // Baseline device profile ranges (B10 report): ISO 29-7213, exposure 57 us - 16 s, EV [-24, 24] x 1/6.
    private val iso = 29 to 7213
    private val exposure = 57_000L to 16_000_000_000L
    private val second = ManualMath.NS_PER_S

    @Test
    fun isoLadderIsThirdStopsInsideTheRange() {
        val l = ManualMath.isoLadder(iso)
        assertEquals(32, l.first())
        assertEquals(6400, l.last())
        assertTrue(l.all { it in iso.first..iso.second })
        assertEquals(listOf(100, 125, 160, 200), l.subList(l.indexOf(100), l.indexOf(100) + 4))
    }

    @Test
    fun isoLadderFallsBackToTheRangeEnds() {
        assertEquals(listOf(105, 110), ManualMath.isoLadder(105 to 110))
        assertEquals(listOf(100), ManualMath.isoLadder(100 to 100))
    }

    @Test
    fun isoClampRecordsWhenMoved() {
        assertFalse(ManualMath.clampIso(iso, 400).clamped)
        val low = ManualMath.clampIso(iso, 12)
        assertTrue(low.clamped)
        assertEquals(29, low.value)
        assertEquals(7213, ManualMath.clampIso(iso, 102400).value)
    }

    @Test
    fun shutterLadderIsCappedAtTheMaxFrameDuration() {
        val l = ManualMath.shutterLadder(exposure, 1 * second)
        assertEquals("1/16000", ManualMath.shutterLabel(l.first()))
        assertEquals(1 * second, l.last())
        assertTrue(l.all { it in exposure.first..second })
        // No max frame duration reported: the exposure range's own top applies (16 s -> the 15 s stop).
        assertEquals(15 * second, ManualMath.shutterLadder(exposure, null).last())
        assertEquals(30 * second, ManualMath.shutterLadder(exposure.first to 30 * second, null).last())
        assertEquals(15 * second, ManualMath.shutterLadder(exposure.first to 15_500_000_000L, null).last())
    }

    @Test
    fun shutterClampUsesTheCeiling() {
        val c = ManualMath.clampExposure(exposure, 2 * second, 10 * second)
        assertTrue(c.clamped)
        assertEquals(2 * second, c.value)
        assertEquals(57_000L, ManualMath.clampExposure(exposure, null, 1_000L).value)
        assertEquals(2 * second, ManualMath.shutterCeilingNs(exposure, 2 * second))
        assertEquals(16 * second, ManualMath.shutterCeilingNs(exposure, 0L))
    }

    @Test
    fun shutterStopsAreThirdsAndLabelled() {
        assertEquals("1/60", ManualMath.shutterLabel(second / 60))
        assertEquals("0.3s", ManualMath.shutterLabel(second * 3 / 10))
        assertEquals("2s", ManualMath.shutterLabel(2 * second))
        assertEquals("1/17544", ManualMath.shutterLabel(57_000L))
        val stops = ManualMath.SHUTTER_STOPS_NS
        assertTrue(stops.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun previewShutterIsHeldAt30fpsWithIsoMakingUp() {
        val p = ManualMath.previewExposure(second / 4, 100, iso, exposure, 16 * second)
        assertEquals(ManualMath.PREVIEW_MAX_EXPOSURE_NS, p.exposureNs)
        assertEquals(ManualMath.PREVIEW_FRAME_DURATION_NS, p.frameDurationNs)
        assertTrue(p.compensated)
        assertEquals(750, p.iso) // 100 x (250 ms / 33.3 ms)
        assertEquals(0.0, p.shortfallEv, 1e-9)
    }

    @Test
    fun previewIsoStopsAtTheRangeAndReportsTheShortfall() {
        val p = ManualMath.previewExposure(4 * second, 3200, iso, exposure, 16 * second)
        assertEquals(7213, p.iso)
        assertTrue(p.shortfallEv > 5.0)
        assertTrue(p.iso in iso.first..iso.second)
    }

    @Test
    fun previewKeepsShortShuttersAsTheyAre() {
        val p = ManualMath.previewExposure(second / 500, 200, iso, exposure, 16 * second)
        assertEquals(second / 500, p.exposureNs)
        assertEquals(200, p.iso)
        assertFalse(p.compensated)
        assertEquals(ManualMath.PREVIEW_FRAME_DURATION_NS, p.frameDurationNs)
    }

    @Test
    fun stillFrameDurationCoversTheExposure() {
        assertEquals(ManualMath.PREVIEW_FRAME_DURATION_NS, ManualMath.stillFrameDurationNs(second / 1000, second))
        assertEquals(2 * second, ManualMath.stillFrameDurationNs(2 * second, 16 * second))
    }

    @Test
    fun evLadderIsThirdsClampedToTheCompensationRange() {
        val l = ManualMath.evLadder(-24 to 24, 1f / 6f)
        assertEquals(25, l.size)
        assertEquals(-24, l.first().index)
        assertEquals(24, l.last().index)
        assertEquals(2, l[13].index) // +1/3 EV = 2 steps of 1/6
        assertEquals(-4.0, l.first().ev, 1e-6)
        // A narrower range (+/-2 EV at 1/3) cuts the ladder; nothing outside the range is offered.
        val narrow = ManualMath.evLadder(-6 to 6, 1f / 3f)
        assertEquals((-6..6).toList(), narrow.map { it.index })
        // 1/2 EV steps: thirds round onto the half steps without duplicates.
        val half = ManualMath.evLadder(-8 to 8, 0.5f)
        assertEquals(half.map { it.index }.distinct(), half.map { it.index })
        assertTrue(half.all { it.index in -8..8 })
        assertTrue(ManualMath.evLadder(null, 1f / 6f).isEmpty())
        assertTrue(ManualMath.evLadder(0 to 0, 1f / 6f).isEmpty())
    }

    @Test
    fun evClampHonoursRangeAndFourStops() {
        assertEquals(24, ManualMath.clampEvIndex(-24 to 24, 1f / 6f, 40).value)
        assertEquals(-12, ManualMath.clampEvIndex(-12 to 12, 1f / 6f, -20).value)
        // A camera that allows +/-6 EV is still held to +/-4 (PLAN 11).
        assertEquals(12, ManualMath.clampEvIndex(-18 to 18, 1f / 3f, 18).value)
        assertEquals(0, ManualMath.clampEvIndex(null, null, 5).value)
        assertEquals(2, ManualMath.evIndexForThirds(1, 1f / 6f))
        assertEquals("+1.3", ManualMath.evLabel(8 / 6.0))
        assertEquals("-0.7", ManualMath.evLabel(-4 / 6.0))
        assertEquals("0.0", ManualMath.evLabel(0.0))
    }

    @Test
    fun focusLadderRunsFromInfinityToTheMinimumDistance() {
        val l = ManualMath.focusLadder(10f)
        assertEquals(0f, l.first(), 0f)
        assertEquals(10f, l.last(), 0f)
        assertTrue(l.all { it in 0f..10f })
        assertEquals(listOf(0f, 0.2f, 0.5f, 1f, 1.5f, 2f, 3f, 3.3f), ManualMath.focusLadder(3.3f))
        assertTrue(ManualMath.focusLadder(0f).isEmpty())
        assertTrue(ManualMath.focusLadder(null).isEmpty())
        assertFalse(ManualMath.manualFocusAvailable(0f))
    }

    @Test
    fun focusClampAndLabels() {
        assertEquals(10f, ManualMath.clampDiopters(10f, 14f).value, 0f)
        assertEquals(0f, ManualMath.clampDiopters(10f, -1f).value, 0f)
        assertTrue(ManualMath.clampDiopters(10f, 14f).clamped)
        assertEquals("∞", ManualMath.focusLabel(0f, 10f, true))
        assertEquals("2.0 m", ManualMath.focusLabel(0.5f, 10f, true))
        assertEquals("10 cm", ManualMath.focusLabel(10f, 10f, true))
        assertEquals("50% near", ManualMath.focusLabel(5f, 10f, false))
    }

    @Test
    fun steppingStaysOnTheLadderAndAtTheEnds() {
        val isoL = ManualMath.isoLadder(iso)
        assertEquals(125, ManualMath.stepIso(isoL, 100, 1))
        assertEquals(6400, ManualMath.stepIso(isoL, 6400, 1))
        assertEquals(32, ManualMath.stepIso(isoL, 32, -1))
        assertEquals(320, ManualMath.snapIso(isoL, 310))
        val sh = ManualMath.shutterLadder(exposure, second)
        assertEquals(second / 50, ManualMath.stepShutter(sh, second / 60, 1))
        assertEquals(second / 60, ManualMath.snapShutter(sh, 16_000_000L))
        val ev = ManualMath.evLadder(-24 to 24, 1f / 6f)
        assertEquals(2, ManualMath.stepEv(ev, 0, 1))
        assertEquals(24, ManualMath.stepEv(ev, 24, 1))
        val f = ManualMath.focusLadder(10f)
        assertEquals(0f, ManualMath.stepFocus(f, null, 1))
        assertNull(ManualMath.stepFocus(f, 0f, -1))
        assertEquals(10f, ManualMath.stepFocus(f, 10f, 1))
        assertEquals(0.2f, ManualMath.stepFocus(f, 0f, 1))
    }

    @Test
    fun histogramBinsLumaFromASample() {
        val luma = intArrayOf(0, 3, 4, 128, 255, 255, 300, -5)
        val h = ManualMath.histogram(luma, 64)
        assertEquals(64, h.size)
        assertEquals(luma.size, h.sum())
        assertEquals(3, h[0]) // 0, 3, -5 (clamped)
        assertEquals(1, h[1]) // 4
        assertEquals(1, h[32]) // 128
        assertEquals(3, h[63]) // 255, 255, 300 (clamped)
        assertArrayEquals(intArrayOf(2, 2), ManualMath.histogram(intArrayOf(0, 127, 128, 255), 2))
    }

    @Test
    fun histogramFromArgbUsesRec601Luma() {
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        val green = 0xFF00FF00.toInt()
        assertEquals(255, ManualMath.luma(white))
        assertEquals(0, ManualMath.luma(black))
        assertEquals(149, ManualMath.luma(green))
        val h = ManualMath.histogramFromArgb(intArrayOf(white, black, green, white), 4)
        assertArrayEquals(intArrayOf(1, 0, 1, 2), h)
        val (shadows, highlights) = ManualMath.clipFractions(h)
        assertEquals(0.25, shadows, 1e-9)
        assertEquals(0.5, highlights, 1e-9)
        assertArrayEquals(intArrayOf(1, 0, 0, 0), ManualMath.histogramFromArgb(intArrayOf(black, white), 4, count = 1))
    }

    @Test
    fun levelReadsTheHorizonForEachDisplayRotation() {
        assertEquals(0f, ManualMath.horizonTiltDegrees(0f, 9.8f, 0f, 0)!!, 0.01f)
        // Upright portrait, phone rolled clockwise by 10 degrees: the horizon line turns 10 degrees counter-clockwise.
        val s = Math.sin(Math.toRadians(10.0)).toFloat() * 9.8f
        val c = Math.cos(Math.toRadians(10.0)).toFloat() * 9.8f
        assertEquals(-10f, ManualMath.horizonTiltDegrees(-s, c, 0f, 0)!!, 0.01f)
        // Landscape (rotation 90): gravity along device +X is level.
        assertEquals(0f, ManualMath.horizonTiltDegrees(9.8f, 0f, 0f, 90)!!, 0.01f)
        assertEquals(0f, ManualMath.horizonTiltDegrees(-9.8f, 0f, 0f, 270)!!, 0.01f)
        assertEquals(0f, ManualMath.horizonTiltDegrees(0f, -9.8f, 0f, 180)!!, 0.01f)
        // Lying flat: no horizon.
        assertNull(ManualMath.horizonTiltDegrees(0.5f, 0.5f, 9.8f, 0))
        assertTrue(ManualMath.isLevel(0.5f))
        assertFalse(ManualMath.isLevel(2f))
        assertFalse(ManualMath.isLevel(null))
    }
}
