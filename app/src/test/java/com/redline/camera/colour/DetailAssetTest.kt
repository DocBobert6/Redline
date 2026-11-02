package com.redline.camera.colour

import com.redline.camera.capability.JsonParse
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** B24: the detail asset parses, and the Kotlin selection and grain target match the reference (redline_cal.detail). */
class DetailAssetTest {
    private val asset: DetailAsset by lazy {
        val f = listOf("src/main/assets/colour/detail.json", "app/src/main/assets/colour/detail.json").map(::File).first { it.exists() }
        DetailAsset.parse(JsonParse.parse(f.readText()) as Map<*, *>)
    }

    @Test
    fun standardOnly() {
        assertEquals("Standard", asset.pictureStyle)
        assertEquals(listOf("Standard"), asset.stylesSupported)
        assertTrue(asset.stylesNote.contains("shot in that style"))
    }

    @Test
    fun stopsAndWidthsSelectTheNearestTable() {
        assertEquals(setOf(24, 28, 35, 50, 70), asset.vignette.keys)
        assertEquals(65, asset.vignetteFor(24).size)
        assertEquals(-1.18998, asset.cornerEv(24), 1e-4)
        assertEquals(-0.87874, asset.cornerEv(50), 1e-4)
        assertTrue(asset.vignetteFor(26) === asset.vignetteFor(24))
        assertEquals(1.217, asset.usmFor(4000).sigma, 1e-9)
        assertEquals(0.933, asset.usmFor(3000).sigma, 1e-9)
        assertEquals(8.0 / 255.0, asset.usmFor(4100).threshold, 1e-5)
        assertEquals(3, asset.distortionFor(70).size)
    }

    @Test
    fun grainTargetMatchesTheReference() {
        val iso100 = floatArrayOf(
            0.000646431f,
            0.000951994f,
            0.001351889f,
            0.001361509f,
            0.001212333f,
            0.001078697f,
            0.0008486f
        )
        val iso3200 = floatArrayOf(
            0.001268977f,
            0.001868813f,
            0.002653828f,
            0.002672711f,
            0.002379872f,
            0.002117538f,
            0.001665845f
        )
        val iso51200 = floatArrayOf(
            0.004612293f,
            0.00675198f,
            0.009588136f,
            0.009673966f,
            0.008469597f,
            0.007447942f,
            0.005638716f
        )
        val cases = listOf(
            Triple(100.0, 4000, iso100),
            Triple(3200.0, 4000, iso3200),
            Triple(51200.0, 3000, iso51200),
            Triple(20.0, 4000, iso100)
        )
        for ((iso, w, want) in cases) assertArrayEquals(want, asset.grainTarget(iso, w), 2e-8f)
    }

    @Test
    fun defaultsAndReport() {
        val d = DetailSettings()
        assertTrue(d.vignette && d.sharpen && d.grain)
        assertFalse(d.distortion)
        val shot = DetailShot(
            "Standard", 35, -0.889, 0.55, false, null, asset.usmFor(4000), 3200.0,
            listOf(0.001), listOf(null), listOf(0.0), mapOf("total" to 120L)
        )
        val m = shot.toMap()
        assertEquals("Standard", m["picture_style"])
        assertEquals(false, m["distortion"])
        assertEquals(mapOf("total" to 120L), m["detail_ms"])
        assertEquals(8.0, (m["sharpen"] as Map<*, *>)["threshold_255"])
    }
}
