package com.redline.camera.video

import com.redline.camera.capability.Dim
import com.redline.camera.colour.ColourMath
import com.redline.camera.colour.ColourPipeline
import com.redline.camera.look.CubeLut
import com.redline.camera.look.TimingSnapshot
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** B40: video size / bitrate / tonemap / encoder selection, the flat curve and its inverse, orientation, the chain reference. */
class VideoConfigTest {
    private val fps30 = listOf(15 to 30, 30 to 30, 7 to 60)

    private fun tone(): DoubleArray {
        val f = listOf("src/main/assets/colour/standard_tone.json", "app/src/main/assets/colour/standard_tone.json").map(::File).first {
            it.exists()
        }
        return ColourPipeline.loadTone(f.readText())
    }

    @Test
    fun uhdFirstThenOtherSixteenNineSizes() {
        val sizes = listOf(Dim(1920, 1080), Dim(4000, 3000), Dim(3840, 2160), Dim(1280, 720), Dim(640, 360), Dim(2560, 1440))
        val c = VideoSizes.candidates(sizes, fps30, 30) { null }
        assertEquals(listOf(Dim(3840, 2160), Dim(2560, 1440), Dim(1920, 1080), Dim(1280, 720)), c)
    }

    @Test
    fun sizeTooSlowForThirtyIsLeftOut() {
        val sizes = listOf(Dim(3840, 2160), Dim(1920, 1080))
        val c = VideoSizes.candidates(sizes, fps30, 30) { if (it == Dim(3840, 2160)) 50_000_000L else 33_333_333L }
        assertEquals(listOf(Dim(1920, 1080)), c)
    }

    @Test
    fun noFixedThirtyRangeMeansNoCandidates() {
        assertTrue(VideoSizes.candidates(listOf(Dim(3840, 2160)), listOf(15 to 30, 30 to 60), 30) { null }.isEmpty())
    }

    @Test
    fun uprightSwapsForQuarterTurnSensors() {
        assertEquals(Dim(2160, 3840), VideoSizes.upright(Dim(3840, 2160), 90))
        assertEquals(Dim(2160, 3840), VideoSizes.upright(Dim(3840, 2160), 270))
        assertEquals(Dim(3840, 2160), VideoSizes.upright(Dim(3840, 2160), 0))
    }

    @Test
    fun bitrateScalesWithPixelRateAndRespectsTheEncoder() {
        assertEquals(80_000_000, VideoBitrate.choose(Dim(3840, 2160), 30, null))
        assertEquals(20_000_000, VideoBitrate.choose(Dim(1920, 1080), 30, null))
        assertEquals(8_888_889, VideoBitrate.choose(Dim(1280, 720), 30, null))
        assertEquals(VideoBitrate.MIN_BPS, VideoBitrate.choose(Dim(640, 360), 30, null))
        assertEquals(40_000_000, VideoBitrate.choose(Dim(3840, 2160), 30, 1 to 40_000_000))
        assertEquals(100_000_000, VideoBitrate.choose(Dim(1280, 720), 30, 100_000_000 to 200_000_000))
    }

    @Test
    fun contrastCurveChosenWhenListedWithEnoughPoints() {
        val p = TonemapPlan.choose(listOf(TonemapModes.CONTRAST_CURVE, TonemapModes.FAST, TonemapModes.HIGH_QUALITY), 128)
        assertEquals(TonemapModes.CONTRAST_CURVE, p.mode)
        assertEquals(FlatCurve.POINTS_WANTED, p.curve!!.size / 2)
        assertTrue(p.exact)
        val small = TonemapPlan.choose(listOf(TonemapModes.CONTRAST_CURVE), 32)
        assertEquals(32, small.curve!!.size / 2)
    }

    @Test
    fun tonemapFallbacksAreRecorded() {
        val noPoints = TonemapPlan.choose(listOf(TonemapModes.CONTRAST_CURVE, TonemapModes.GAMMA_VALUE), null)
        assertEquals(TonemapModes.GAMMA_VALUE, noPoints.mode)
        assertTrue(noPoints.note.contains("TONEMAP_MAX_CURVE_POINTS"))
        val preset = TonemapPlan.choose(listOf(TonemapModes.FAST, TonemapModes.PRESET_CURVE), 64)
        assertEquals(TonemapModes.PRESET_CURVE, preset.mode)
        assertEquals(TonemapModes.PRESET_SRGB, preset.preset)
        val def = TonemapPlan.choose(listOf(TonemapModes.FAST, TonemapModes.HIGH_QUALITY), 64)
        assertNull(def.mode)
        assertEquals("isp_default", def.modeName)
        assertFalse(def.exact)
        // Gamma inverse undoes x^(1/2.2).
        val g = noPoints.inverse
        val y = Math.pow(0.18, 1 / 2.2)
        assertEquals(0.18, g[(y * (g.size - 1)).toInt()].toDouble(), 0.003)
    }

    @Test
    fun flatCurveRoundTripsThroughItsPiecewiseInverse() {
        val pts = FlatCurve.points(64)
        assertEquals(0f, pts[0])
        assertEquals(0f, pts[1])
        assertEquals(1f, pts[pts.size - 2])
        assertEquals(1f, pts[pts.size - 1], 1e-6f)
        for (i in 1 until 64) assertTrue(pts[2 * i + 1] > pts[2 * i - 1])
        val inv = FlatCurve.inverseTable(pts)
        val chain = VideoChain(inv, VideoChain.IDENTITY, 1.0, tone(), CubeLut.identity(2))
        for (x in listOf(0.001, 0.01, 0.05, 0.18, 0.5, 0.9, 1.0)) {
            val y = FlatCurve.evalPoints(pts, x)
            val back = chain.table(inv, y)
            assertEquals("x=$x", x, back, maxOf(2e-4, x * 2e-3))
        }
        // Flat: mid grey sits higher than sRGB (0.46) and shadows keep more codes than sRGB at 1 % (0.0998).
        assertTrue(FlatCurve.forward(0.18) > 0.5)
        assertTrue(FlatCurve.forward(0.01) * 255 > 20)
    }

    @Test
    fun orientationContentPlusHintIsTheDeviceRotation() {
        assertEquals(VideoOrientation(90, 270), VideoOrientation.plan(true, 0))
        assertEquals(VideoOrientation(90, 0), VideoOrientation.plan(true, 90))
        assertEquals(VideoOrientation(270, 0), VideoOrientation.plan(true, 270))
        assertEquals(VideoOrientation(90, 90), VideoOrientation.plan(true, 180))
        assertEquals(VideoOrientation(0, 90), VideoOrientation.plan(false, 90))
        assertEquals(0, VideoOrientation.snap(350))
        assertEquals(90, VideoOrientation.snap(80))
        for (d in 0 until 360 step 15) {
            for (portrait in listOf(true, false)) {
                val o = VideoOrientation.plan(portrait, d)
                assertEquals((o.contentRotation + o.hint) % 360, VideoOrientation.snap(d))
                // The encoded frame stays landscape-shaped: a portrait upright picture is turned by 90 or 270.
                assertEquals(if (portrait) 90 else 0, o.contentRotation % 180)
            }
        }
    }

    @Test
    fun exposureGainPutsIspMidGreyOnTheStillsMidTone() {
        val t = tone()
        val ev = VideoExposure.gainEv(t)
        // The fitted tone puts its mid-tone near EV -3.7, so ISP mid grey (log2 0.18 = -2.47) is gained down about 1.25 EV.
        assertTrue("gain $ev", ev > -2.0 && ev < 4.0)
        val curve = ColourMath.ToneCurve(t, Math.pow(2.0, ev))
        assertEquals(ColourMath.DEFAULT_TARGET, curve.eval(VideoExposure.ISP_MID_GREY * Math.pow(2.0, ev)), 0.01)
    }

    @Test
    fun chainReferenceMatchesTheStillsToneOnGrey() {
        val t = tone()
        val pts = FlatCurve.points(64)
        val gain = Math.pow(2.0, VideoExposure.gainEv(t))
        val chain = VideoChain(FlatCurve.inverseTable(pts), VideoChain.IDENTITY, gain, t, CubeLut.identity(33))
        val stills = ColourMath.ToneCurve(t, gain)
        for (x in listOf(0.02, 0.09, 0.18, 0.36, 0.7)) {
            val e = FlatCurve.evalPoints(pts, x).toFloat()
            val out = chain.apply(e, e, e)
            val want = stills.eval(gain * x)
            for (c in 0..2) assertEquals("x=$x", want, out[c].toDouble(), 0.004)
        }
        // Mid grey through the whole chain lands on the stills' mid-tone.
        val mid = FlatCurve.evalPoints(pts, VideoExposure.ISP_MID_GREY).toFloat()
        assertEquals(ColourMath.DEFAULT_TARGET, chain.apply(mid, mid, mid)[1].toDouble(), 0.012)
        // The colour matrix is applied before the tone: swapping R and B swaps the outputs.
        val swap = VideoChain(FlatCurve.inverseTable(pts), floatArrayOf(0f, 0f, 1f, 0f, 1f, 0f, 1f, 0f, 0f), gain, t, CubeLut.identity(33))
        val a = chain.apply(0.2f, 0.5f, 0.8f)
        val b = swap.apply(0.8f, 0.5f, 0.2f)
        for (c in 0..2) assertEquals(a[c], b[c], 1e-6f)
    }

    @Test
    fun chainSelfCheckErrorCountsSteps() {
        val chain = VideoChain(FlatCurve.inverseTable(FlatCurve.points(64)), VideoChain.IDENTITY, 2.0, tone(), CubeLut.identity(33))
        val inputs = intArrayOf(128, 128, 128)
        val ref = chain.apply(128 / 255f, 128 / 255f, 128 / 255f).map { Math.round(it * 255f) }
        val px = byteArrayOf((ref[0] + 3).toByte(), ref[1].toByte(), ref[2].toByte(), -1)
        assertEquals(3, chain.maxErrorSteps(inputs, px))
    }

    @Test
    fun encoderPickPrefersHardwareHevcThenSoftwareThenAvc() {
        val hwHevcNo = EncoderCandidate("hw.hevc", EncoderPick.HEVC, true, false, null)
        val swHevc = EncoderCandidate("sw.hevc", EncoderPick.HEVC, false, true, null)
        val hwHevc = EncoderCandidate("hw2.hevc", EncoderPick.HEVC, true, true, 1 to 120_000_000)
        val avc = EncoderCandidate("hw.avc", EncoderPick.AVC, true, true, null)
        assertEquals("hw2.hevc", EncoderPick.choose(listOf(swHevc, hwHevcNo, avc, hwHevc))?.name)
        assertEquals("sw.hevc", EncoderPick.choose(listOf(hwHevcNo, avc, swHevc))?.name)
        assertEquals("hw.avc", EncoderPick.choose(listOf(hwHevcNo, avc))?.name)
        assertNull(EncoderPick.choose(listOf(hwHevcNo)))
    }

    @Test
    fun reportGateAndSection() {
        assertNull(VideoReport().gatePassed)
        assertNull(VideoReport(chain = TimingSnapshot(50, 5.0, 6.0, 7.0)).gatePassed)
        assertEquals(true, VideoReport(chain = TimingSnapshot(300, 5.0, 11.9, 14.0)).gatePassed)
        assertEquals(false, VideoReport(chain = TimingSnapshot(300, 9.0, 12.5, 14.0)).gatePassed)
        assertTrue(VideoReport.DEFAULT_ON)
        val m = VideoReport(
            size = "3840x2160",
            fps = 30,
            codec = "HEVC",
            recordings = listOf(RecordingRecord(3000, 90, 0, true, 1000, 2, true))
        ).withError("e1").toMap()
        val keys = listOf("size", "fps", "codec", "bitrate_bps", "tonemap_mode", "chain_ms_p50", "chain_ms_p95", "chain_ms_max")
        for (k in keys + listOf("frames_dropped", "recordings", "audio", "errors")) {
            assertTrue(k, m.containsKey(k))
        }
        assertEquals(listOf(3000L), m["recording_durations_ms"])
        assertEquals(listOf("e1"), m["errors"])
        assertNotNull(VideoReport(size = "1920x1080", fps = 30).summary())
        assertTrue(abs(12.0 - VideoReport.GATE_MS) < 1e-9)
    }
}
