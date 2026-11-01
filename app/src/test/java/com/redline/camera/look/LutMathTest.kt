package com.redline.camera.look

import com.redline.camera.nativebridge.RedlineNative
import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** B20 look preview: the CPU reference of the GPU LUT pass, timing stats and gate, report, native loading. */
class LutMathTest {
    /** A LUT whose nodes hold an affine function of the node position; trilinear interpolation reproduces it exactly. */
    private fun affineLut(n: Int, f: (Float, Float, Float) -> FloatArray): CubeLut {
        val d = FloatArray(n * n * n * 3)
        var i = 0
        for (b in 0 until n) {
            for (g in 0 until n) {
                for (r in 0 until n) {
                    val v = f(r / (n - 1f), g / (n - 1f), b / (n - 1f))
                    d[i++] = v[0]
                    d[i++] = v[1]
                    d[i++] = v[2]
                }
            }
        }
        return CubeLut(n, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f), d)
    }

    private fun assertRgb(expected: FloatArray, actual: FloatArray, tol: Float = 1e-5f) {
        for (c in 0..2) assertEquals("channel $c", expected[c], actual[c], tol)
    }

    @Test
    fun identityLutReturnsItsInput() {
        val lut = CubeLut.identity(33)
        listOf(floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f), floatArrayOf(0.123f, 0.5f, 0.987f)).forEach {
            assertRgb(it, LutMath.sample(lut, it[0], it[1], it[2]))
        }
    }

    @Test
    fun cubeOrderIsRedFastest() {
        val lut = CubeLut.identity(2)
        // Entry 1 in file order is (r=1, g=0, b=0); entry 2 is (r=0, g=1, b=0).
        assertArrayEquals(floatArrayOf(1f, 0f, 0f), lut.data.copyOfRange(3, 6), 0f)
        assertArrayEquals(floatArrayOf(0f, 1f, 0f), lut.data.copyOfRange(6, 9), 0f)
        assertRgb(floatArrayOf(1f, 0f, 0f), LutMath.sample(lut, 1f, 0f, 0f))
    }

    @Test
    fun trilinearReproducesAffineFunctionsBetweenNodes() {
        val f = { r: Float, g: Float, b: Float -> floatArrayOf(0.2f + 0.5f * r - 0.1f * b, g * 0.8f + 0.1f, 1f - b) }
        val lut = affineLut(5, f)
        listOf(floatArrayOf(0.1f, 0.33f, 0.71f), floatArrayOf(0.999f, 0.001f, 0.5f), floatArrayOf(0.62f, 0.62f, 0.62f)).forEach {
            assertRgb(f(it[0], it[1], it[2]), LutMath.sample(lut, it[0], it[1], it[2]))
        }
    }

    @Test
    fun nonLinearLutHitsNodesExactlyAndInterpolatesBetween() {
        val lut = affineLut(3) { r, g, b -> floatArrayOf(r * r, g, b) }
        assertEquals(0.25f, LutMath.sample(lut, 0.5f, 0f, 0f)[0], 1e-6f) // node
        assertEquals((0.25f + 1f) / 2, LutMath.sample(lut, 0.75f, 0f, 0f)[0], 1e-6f) // halfway between nodes 0.25 and 1
    }

    @Test
    fun inputsOutsideTheDomainClamp() {
        val lut = CubeLut.identity(9)
        assertRgb(floatArrayOf(0f, 1f, 0.5f), LutMath.sample(lut, -0.4f, 1.7f, 0.5f))
        val wide = CubeLut(2, floatArrayOf(0f, 0f, 0f), floatArrayOf(2f, 2f, 2f), CubeLut.identity(2).data)
        assertRgb(floatArrayOf(0.5f, 0.25f, 1f), LutMath.sample(wide, 1f, 0.5f, 2f))
    }

    @Test
    fun halfTexelCoordinatesPutEndNodesAtTexelCentres() {
        val n = 33
        assertEquals(0.5f / n, LutMath.textureCoordinate(0f, n), 1e-7f)
        assertEquals(1f - 0.5f / n, LutMath.textureCoordinate(1f, n), 1e-7f)
        // Node k (input k / (n - 1)) lands on the centre of texel k: (k + 0.5) / n.
        for (k in listOf(1, 16, 31)) assertEquals((k + 0.5f) / n, LutMath.textureCoordinate(k / (n - 1f), n), 1e-6f)
        // What hardware linear filtering then computes (u * n - 0.5) is the node-space position the CPU reference uses.
        val x = 0.4321f
        assertEquals(x * (n - 1), LutMath.textureCoordinate(x, n) * n - 0.5f, 1e-4f)
    }

    @Test
    fun srgbTransferRoundTrips() {
        for (i in 0..100) {
            val e = i / 100f
            assertEquals(e, LutMath.srgbEncode(LutMath.srgbDecode(e)), 1e-5f)
        }
        assertEquals(0.2140f, LutMath.srgbDecode(0.5f), 1e-4f)
    }

    @Test
    fun strengthMixesInLinearLight() {
        val invert = affineLut(2) { r, g, b -> floatArrayOf(1 - r, 1 - g, 1 - b) }
        val input = floatArrayOf(0.2f, 0.5f, 0.9f)
        assertRgb(input, LutMath.applyLook(invert, input[0], input[1], input[2], 0f), 1e-5f)
        assertRgb(floatArrayOf(0.8f, 0.5f, 0.1f), LutMath.applyLook(invert, input[0], input[1], input[2], 1f), 1e-5f)
        val half = LutMath.applyLook(invert, 0f, 0f, 0f, 0.5f)[0]
        // Black mixed half-way with white in linear light is linear 0.5, i.e. encoded ~0.735, not 0.5.
        assertEquals(LutMath.srgbEncode(0.5f), half, 1e-5f)
    }

    @Test
    fun rgba8FallbackRoundsAndClamps() {
        val lut = CubeLut(2, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f), FloatArray(24) { if (it == 0) 1.2f else 0.5f })
        val bytes = LutMath.toRgba8(lut)
        assertEquals(32, bytes.size)
        assertEquals(255, bytes[0].toInt() and 0xFF)
        assertEquals(128, bytes[1].toInt() and 0xFF)
        assertEquals(255, bytes[3].toInt() and 0xFF)
    }

    @Test
    fun rgb10a2PacksRedLowThenGreenBlueAndAlpha() {
        val lut = CubeLut(2, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f), FloatArray(24) { if (it == 0) 1.2f else 0.5f })
        val words = LutMath.toRgb10a2(lut)
        assertEquals(8, words.size)
        val w = words[0]
        assertEquals(1023, w and 0x3FF) // red clamped to 1
        assertEquals(512, (w ushr 10) and 0x3FF) // 0.5 * 1023 = 511.5 -> 512
        assertEquals(512, (w ushr 20) and 0x3FF)
        assertEquals(3, w ushr 30)
        // Four times the precision of RGBA8: the quantisation error is at most half a 10-bit step.
        val id = CubeLut.identity(33)
        val packed = LutMath.toRgb10a2(id)
        for (i in packed.indices) {
            for (c in 0..2) {
                val v = ((packed[i] ushr (10 * c)) and 0x3FF) / 1023f
                assertEquals(id.data[i * 3 + c], v, 0.5f / 1023f + 1e-6f)
            }
        }
        assertTrue(LutMath.COMPACT_TOLERANCE <= 1)
    }

    @Test
    fun selfCheckErrorMeasuresAgainstTheReference() {
        val lut = CubeLut.identity(17)
        val inputs = LutMath.SELF_CHECK_COLOURS
        val count = inputs.size / 3
        val exact = ByteArray(count * 4) { i -> if (i % 4 == 3) -1 else inputs[(i / 4) * 3 + i % 4].toByte() }
        assertEquals(0, LutMath.maxErrorSteps(lut, inputs, exact, 1f))
        exact[4] = (exact[4] - 5).toByte() // white's red off by 5
        assertEquals(5, LutMath.maxErrorSteps(lut, inputs, exact, 1f))
    }

    @Test
    fun nativeLayoutDecodes() {
        val id = CubeLut.identity(2)
        val a = floatArrayOf(2f, 0f, 0f, 0f, 1f, 1f, 1f) + id.data
        val lut = CubeLut.fromNativeLayout(a)
        assertEquals(2, lut.size)
        assertArrayEquals(id.data, lut.data, 0f)
        assertEquals(RedlineNative.HEADER_FLOATS, a.size - id.data.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun wrongDataLengthIsRefused() {
        CubeLut(3, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f), FloatArray(10))
    }

    @Test
    fun timingPercentilesAndGate() {
        val s = LutTimingStats(window = 1000)
        assertNull(s.snapshot().p95Ms)
        for (i in 1..99) s.add(i / 10.0) // 0.1 .. 9.9 ms
        val early = s.snapshot()
        assertEquals(99, early.framesMeasured)
        assertNull("gate waits for 100 frames", early.gatePassed)
        s.add(10.0)
        val snap = s.snapshot()
        assertEquals(5.0, snap.p50Ms!!, 1e-9)
        assertEquals(9.5, snap.p95Ms!!, 1e-9)
        assertEquals(10.0, snap.maxMs!!, 1e-9)
        assertFalse(snap.gatePassed!!)
        val fast = LutTimingStats()
        repeat(200) { fast.add(1.5) }
        assertTrue(fast.snapshot().gatePassed!!)
        val edge = LutTimingStats()
        repeat(100) { edge.add(8.0) }
        assertTrue("p95 exactly 8 ms passes", edge.snapshot().gatePassed!!)
    }

    @Test
    fun timingWindowKeepsRecentSamplesButSessionMax() {
        val s = LutTimingStats(window = 10)
        s.add(50.0)
        repeat(10) { s.add(1.0) }
        val snap = s.snapshot()
        assertEquals(11, snap.framesMeasured)
        assertEquals(1.0, snap.p95Ms!!, 1e-9)
        assertEquals(50.0, snap.maxMs!!, 1e-9)
        s.reset()
        assertEquals(0, s.snapshot().framesMeasured)
        s.add(Double.NaN)
        assertEquals(0, s.count)
    }

    @Test
    fun reportSectionCarriesEveryField() {
        val r = LookPreviewReport(
            available = true,
            active = true,
            glVersion = "OpenGL ES 3.2",
            lutName = "luts/x.cube",
            lutSize = 33,
            timerMethod = "GL_EXT_disjoint_timer_query",
            timing = TimingSnapshot(150, 1.2, 2.5, 4.0)
        )
        val m = r.toMap()
        listOf(
            "enabled", "reason", "gl_version", "renderer", "float_linear", "lut_name", "lut_size", "native_version",
            "timer_method", "frames_measured", "lut_ms_p50", "lut_ms_p95", "lut_ms_max", "gate"
        ).forEach { assertTrue("missing $it", m.containsKey(it)) }
        assertEquals(true, m["enabled"])
        assertNull(m["reason"])
        assertEquals("gl_lut", m["path"])
        assertEquals(mapOf("lut_le_8_ms" to true), m["gate"])
        assertEquals(LookPreviewReport.LABEL, m["label"])
    }

    @Test
    fun reportExplainsWhyItIsOff() {
        assertNotNull(LookPreviewReport().whyOff()) // still checking
        val failed = LookPreviewReport(available = false, reason = "OpenGL ES 3.0 required")
        assertFalse(failed.enabled)
        assertEquals("OpenGL ES 3.0 required", failed.whyOff())
        assertEquals("turned off", LookPreviewReport(available = true, userSetting = false).whyOff())
        val broke = LookPreviewReport(available = true, runtimeError = "eglSwapBuffers 0x300d")
        assertFalse(broke.enabled)
        assertTrue(broke.whyOff()!!.contains("plain preview"))
        assertEquals(null, LookPreviewReport(available = true, timing = TimingSnapshot(10, 1.0, 1.0, 1.0)).gateLutLe8Ms)
    }

    @Test
    fun nativeLoadFailureIsReportedNotThrown() {
        val r = RedlineNative.attempt { throw UnsatisfiedLinkError("no redline_native in java.library.path") }
        assertFalse(r.loaded)
        assertNull(r.version)
        assertTrue(r.error!!.contains("UnsatisfiedLinkError"))
        val sec = RedlineNative.attempt { throw SecurityException("denied") }
        assertFalse(sec.loaded)
    }

    @Test
    fun bundledLutMatchesTheReferenceShape() {
        // The asset ships with the APK; on the JVM it is read straight from the source tree when present.
        val f = listOf("src/main/assets/luts/redline_standard_preview_33.cube", "app/src/main/assets/luts/redline_standard_preview_33.cube")
            .map { java.io.File(it) }
            .firstOrNull { it.exists() } ?: return
        val lines = f.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue(lines.any { it.trim() == "LUT_3D_SIZE 33" })
        val data = lines.filter { it.first().isDigit() || it.first() == '-' }
        assertEquals(33 * 33 * 33, data.size)
        // Neutrals stay close to neutral in the residual-mode preview LUT.
        val mid = data[(16 * 33 + 16) * 33 + 16].trim().split(Regex("\\s+")).map { it.toFloat() }
        assertTrue("mid grey stays near grey: $mid", mid.all { abs(it - 0.5f) < 0.03f })
    }

    @Test
    fun cpuEstimateGateNeedsOnly20Samples() {
        val t = TimingSnapshot(21, 4.7, 5.85, 6.2)
        val gpu = LookPreviewReport(available = true, timing = t, timerMethod = "GL_EXT_disjoint_timer_query")
        assertEquals(null, gpu.gateLutLe8Ms)
        val cpu = LookPreviewReport(available = true, timing = t, timerMethod = "cpu_glfinish_estimate (every 15th frame)")
        assertEquals(true, cpu.gateLutLe8Ms)
        val few = TimingSnapshot(19, 4.7, 5.85, 6.2)
        assertEquals(null, LookPreviewReport(available = true, timing = few, timerMethod = "cpu_glfinish_estimate").gateLutLe8Ms)
    }
}
