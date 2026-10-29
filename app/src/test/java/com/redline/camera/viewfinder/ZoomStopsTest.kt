package com.redline.camera.viewfinder

import com.redline.camera.capability.Baseline
import com.redline.camera.capability.LensProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** B12 zoom stops (PLAN 3.3, 19.6, 19.8): crop = target eq / eq of the lens used, honest labels. */
class ZoomStopsTest {
    /** Baseline device profile, physical 2: 4.53 mm on a 6.4 x 4.8 mm sensor, 4000x3000 -> 25.5 mm eq across a 3:2 frame. */
    private val main = lensProfile("2", 4.53f, 6.4f, 4.8f, 4000, 3000)

    /** Baseline device profile, physical 3: 1.854 mm ultrawide, 14.2 mm eq (3:2), 4208x3120. */
    private val ultrawide = lensProfile("3", 1.854f, 4.70f, 3.48f, 4208, 3120)

    /** Hypothetical phone (data only): a native 3x tele at 67 mm eq (3:2), 4000x3000. */
    private val tele = ZoomLens("4", 67f, 4000, 3000)

    private fun lensProfile(id: String, f: Float, w: Float, h: Float, px: Int, py: Int) = LensProfile(
        cameraId = id,
        focalLengthsMm = listOf(f),
        apertures = listOf(1.7f),
        sensorWidthMm = w,
        sensorHeightMm = h,
        pixelArrayWidth = px,
        pixelArrayHeight = py,
        activeWidth = px,
        activeHeight = py,
        minFocusDistanceDiopters = 10f,
        hasOis = false,
        hasDistortionModel = false
    )

    private val baselineLenses = ZoomStops.lensesOf(Baseline.back.copy(lenses = listOf(main, ultrawide)))

    @Test
    fun baselineLensEquivalents() {
        assertEquals(25.5f, baselineLenses.single { it.cameraId == "2" }.equivalent32Mm, 0.05f)
        assertEquals(14.2f, baselineLenses.single { it.cameraId == "3" }.equivalent32Mm, 0.05f)
        assertEquals("2", ZoomStops.reference(baselineLenses)?.cameraId)
    }

    @Test
    fun baselineWidestStopIsHonestAsterisk() {
        val s = ZoomStops.plan(baselineLenses).stop(24)!!
        assertEquals("24*", s.label)
        assertFalse(s.honest)
        assertEquals("24* ≈ 25.5 mm", s.focalNote)
        assertEquals(StopSource.MAIN_CROP, s.source)
        assertEquals("2", s.lensId)
        // Never below 1.0x: the logical camera must not switch to the ultrawide on its own.
        assertEquals(1f, s.zoomRatio, 1e-4f)
        assertEquals(4000, s.outputWidthPx)
        assertEquals(2666, s.outputHeightPx)
    }

    @Test
    fun baselineCropStopsAndEffectiveResolution() {
        val plan = ZoomStops.plan(baselineLenses)
        assertEquals(listOf("24*", "28", "35", "50", "70"), plan.stops.map { it.label })
        val expected = mapOf(28 to 1.099f, 35 to 1.374f, 50 to 1.963f, 70 to 2.748f)
        for ((mm, crop) in expected) {
            val s = plan.stop(mm)!!
            assertTrue("$mm honest", s.honest)
            assertEquals("$mm crop", crop, s.lensCrop, 0.01f)
            assertEquals("$mm ratio", crop, s.zoomRatio, 0.01f)
            assertEquals(StopSource.MAIN_CROP, s.source)
            assertEquals(4000, s.sourceWidthPx)
            assertEquals(s.outputWidthPx * 2 / 3, s.outputHeightPx)
        }
        // 50 mm ≈ 2.0x crop -> ~2000 px wide from 4000 (PLAN 3.3: ~2070x1380 at the 26 mm estimate).
        assertEquals(2040, plan.stop(50)!!.outputWidthPx)
        assertEquals(1460, plan.stop(70)!!.outputWidthPx)
        assertTrue(plan.stop(50)!!.resolutionNote.contains("~2040 px wide from 4000"))
        assertEquals(listOf("* 24* ≈ 25.5 mm"), plan.footnotes)
    }

    @Test
    fun defaultStopIs35() {
        assertEquals(35, ZoomStops.DEFAULT_STOP)
        assertTrue(ZoomStops.plan(baselineLenses).stop(ZoomStops.DEFAULT_STOP)!!.honest)
    }

    @Test
    fun ultrawideIsOptInOnly() {
        // PLAN 3.3: the ultrawide "true 24" is worse than the main lens, so it is opt-in.
        val opted = ZoomStops.plan(baselineLenses, minZoomRatio = 0.6f, allowUltrawide = true).stop(24)!!
        assertEquals("24", opted.label)
        assertEquals(StopSource.ULTRAWIDE_CROP, opted.source)
        assertEquals("3", opted.lensId)
        assertEquals(24f / 14.2f, opted.lensCrop, 0.01f)
        assertEquals(24f / 25.47f, opted.zoomRatio, 0.01f)
        assertTrue(opted.outputWidthPx in 2400..2550)
        // 28 still comes from the main lens.
        assertEquals("2", ZoomStops.plan(baselineLenses, minZoomRatio = 0.6f, allowUltrawide = true).stop(28)!!.lensId)
    }

    @Test
    fun hypotheticalNativeTelePicksTeleFor70Only() {
        val plan = ZoomStops.plan(baselineLenses + tele)
        val s70 = plan.stop(70)!!
        assertEquals(StopSource.NATIVE_LENS, s70.source)
        assertEquals("4", s70.lensId)
        assertTrue(s70.honest)
        assertEquals("70", s70.label)
        assertEquals(70f / 67f, s70.lensCrop, 0.001f)
        // Zoom ratio stays relative to the main lens at 1.0x on the logical camera.
        assertEquals(70f / 25.47f, s70.zoomRatio, 0.01f)
        assertEquals(3830, s70.outputWidthPx)
        // 50 is shorter than the tele: still a crop of the main lens.
        assertEquals("2", plan.stop(50)!!.lensId)
        assertEquals(StopSource.MAIN_CROP, plan.stop(50)!!.source)
        // And the reference lens is still the main camera.
        assertEquals("2", plan.reference?.cameraId)
    }

    @Test
    fun trueWideMainNeedsNoAsterisk() {
        val wideMain = ZoomLens("0", 23.6f, 4000, 3000)
        val s = ZoomStops.plan(listOf(wideMain)).stop(24)!!
        assertTrue(s.honest)
        assertEquals("24", s.label)
        assertEquals(24f / 23.6f, s.zoomRatio, 0.001f)
    }

    @Test
    fun zoomRangeClampIsLabelledHonestly() {
        val s = ZoomStops.plan(baselineLenses, maxZoomRatio = 2f).stop(70)!!
        assertEquals(2f, s.zoomRatio, 1e-4f)
        assertFalse(s.honest)
        assertEquals("70*", s.label)
        assertEquals("70* ≈ 51.0 mm", s.focalNote)
    }

    @Test
    fun noFocalDataGivesAsterisksNotGuesses() {
        val plan = ZoomStops.plan(emptyList())
        assertNull(plan.reference)
        assertEquals(listOf("24*", "28*", "35*", "50*", "70*"), plan.stops.map { it.label })
        assertTrue(plan.stops.all { it.trueEquivalentMm == null && it.zoomRatio >= 1f })
    }
}
