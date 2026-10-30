package com.redline.camera

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.redline.camera.colour.ColourPipeline
import com.redline.camera.colour.ColourSelfCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B21 on a real Android runtime: the native RAW pipeline loads for this ABI, renders the bundled self-check frame
 * through JNI into a Bitmap, and the result matches the Python reference's render (parity and neutral gates), with
 * the Kotlin per-shot maths reproducing the reference's plan. CPU only, so it is asserted on the emulator too.
 */
@RunWith(AndroidJUnit4::class)
class ColourPipelineTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun selfCheckFrameMatchesTheReference() {
        val pipeline = ColourPipeline(context)
        val prepared = pipeline.prepare()
        val c = prepared.selfCheck
        assertTrue("prepare failed: ${prepared.reason}", c != null)
        c!!
        assertEquals("self-check error", null, c.error)
        assertEquals("Kotlin maths match", true, c.kotlinMathsMatch)
        assertTrue("mean dE2000 ${c.meanDe2000}", c.meanDe2000!! <= ColourSelfCheck.GATE_DE)
        assertTrue("max step ${c.maxStep8bit}", c.maxStep8bit!! <= ColourSelfCheck.MAX_STEP)
        assertTrue("grey chroma ${c.greyChromaMax}", c.greyChromaMax!! <= ColourSelfCheck.NEUTRAL_MAX_CHROMA)
        assertTrue("prepared not ok: ${prepared.reason}", prepared.ok)
    }
}
