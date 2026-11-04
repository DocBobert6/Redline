package com.redline.camera.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** B30: the depth report's counters, gate, status line and JSON shape, and the self-check maths. */
class DepthReportTest {
    private fun shot(ok: Boolean, depthMs: Long, total: Long, conf: Double? = 0.6, bytes: Long = 1000) = DepthShot(
        base = "IMG_$depthMs",
        ok = ok,
        prepMs = 5,
        depthMs = depthMs,
        segMs = 7,
        totalMs = total,
        delegate = "cpu",
        confidence = if (ok) conf else null,
        usable = conf?.let { it >= 0.35 },
        storedBytes = if (ok) bytes else null,
        error = if (ok) null else "boom"
    )

    private val engine = DepthEngineInfo(
        model = "m",
        segmenter = "s",
        delegate = "gpu_fp16",
        segmenterDelegate = "gpu_fp16",
        inputWidth = 420,
        inputHeight = 280,
        segWidth = 256,
        segHeight = 144,
        initMs = 900,
        attempts = listOf(
            DepthAttempt("depth", "gpu_fp16", passed = true, compileMs = 800, checkMs = 90, relMaxError = 0.012345678, correlation = 0.9999)
        )
    )

    @Test
    fun defaultIsOnAndEmpty() {
        val r = DepthReport()
        assertTrue(r.enabled)
        assertEquals(DepthReport.DEFAULT_ON, r.enabled)
        assertNull(r.gatePassed)
        assertEquals("On · engine loads on first shot · no shots yet", r.statusLine())
        assertEquals("Off", r.copy(enabled = false).statusLine())
    }

    @Test
    fun recordsShotsFailuresAndGate() {
        var r = DepthReport(engine = engine)
        r = r.record(shot(true, 300, 420))
        r = r.record(shot(true, 350, 500, bytes = 2000))
        r = r.record(shot(false, 0, 10))
        assertEquals(3, r.shots)
        assertEquals(2, r.done)
        assertEquals(1, r.failed)
        assertEquals(350, r.maxDepthMs)
        assertEquals(500, r.maxTotalMs)
        assertEquals(3000, r.storedBytes)
        assertEquals("boom", r.lastError)
        assertEquals(true, r.gatePassed)
        assertEquals(false, r.record(shot(true, 450, 600)).gatePassed)
        val m = r.toMap()
        assertEquals(325L, m["avg_depth_ms"])
        assertEquals(true, m["gate_depth_lt_400_ms"])
        assertEquals(3, (m["recent_shots"] as List<*>).size)
        assertEquals("On · gpu_fp16 · last failed: boom", r.statusLine())
    }

    @Test
    fun recentIsCapped() {
        var r = DepthReport()
        repeat(DepthReport.RECENT + 5) { r = r.record(shot(true, 100L + it, 200)) }
        assertEquals(DepthReport.RECENT, r.recent.size)
        assertEquals(100L + DepthReport.RECENT + 4, r.recent.last().depthMs)
    }

    @Test
    fun skipsCountAndShowInStatusUntilTheNextShot() {
        var r = DepthReport(engine = engine).record(shot(true, 250, 400, conf = 0.72))
        assertEquals("On · gpu_fp16 · last 250 ms depth (400 ms total), confidence 0.72", r.statusLine())
        r = r.skip(DepthReport.SKIP_BUSY).skip(DepthReport.SKIP_BUSY).skip(DepthReport.SKIP_NOT_RENDERED)
        assertEquals(mapOf(DepthReport.SKIP_BUSY to 2, DepthReport.SKIP_NOT_RENDERED to 1), r.skips)
        assertEquals("On · gpu_fp16 · last skipped: ${DepthReport.SKIP_NOT_RENDERED}", r.statusLine())
        r = r.record(shot(true, 260, 410))
        assertFalse(r.lastWasSkip)
        assertTrue(r.statusLine().contains("last 260 ms depth"))
    }

    @Test
    fun failedEngineShowsWhy() {
        val bad = engine.copy(delegate = null, error = "no accelerator passed the depth self-check")
        assertFalse(bad.ready)
        assertEquals(
            "On · engine failed: no accelerator passed the depth self-check · no shots yet",
            DepthReport(engine = bad).statusLine()
        )
    }

    @Test
    fun mapShape() {
        val r = DepthReport(engine = engine, hardware = HardwareDepth(emptyList(), listOf("4"))).record(shot(true, 300, 420))
        val m = r.toMap()
        assertEquals(true, m["enabled"])
        val hw = m["hardware_depth"] as Map<*, *>
        assertEquals(false, hw["used"])
        assertTrue((hw["note"] as String).contains("dual-camera disparity not implemented"))
        val e = m["engine"] as Map<*, *>
        assertEquals("420x280", e["input_size"])
        val a = (e["attempts"] as List<*>).single() as Map<*, *>
        assertEquals(0.01235, a["selfcheck_rel_max_error"])
        val last = m["last"] as Map<*, *>
        assertEquals(300L, (last["ms"] as Map<*, *>)["depth_infer"])
        assertEquals(0.6, last["confidence"])
        assertTrue(m["status"] is String)
        val withOutput = HardwareDepth(listOf("0"), emptyList()).toMap()
        assertTrue((withOutput["note"] as String).startsWith("DEPTH_OUTPUT present but not used"))
    }

    @Test
    fun selfCheckIdenticalPassesAndGarbageFails() {
        val w = 16
        val h = 8
        val depth = FloatArray(w * h) { i -> (i % w) * 0.5f + (i / w) * 0.25f }
        val expected = DepthSelfCheck.blockMeans(depth, w, h)
        assertEquals((w / 4) * (h / 4), expected.size)
        val same = DepthSelfCheck.compare(depth, w, h, expected)
        assertTrue(same.passed)
        assertEquals(0.0, same.relMaxError, 1e-9)
        assertEquals(1.0, same.correlation, 1e-9)

        val noisy = FloatArray(depth.size) { depth[it] * 1.01f }
        assertTrue(DepthSelfCheck.compare(noisy, w, h, expected).passed)

        val flipped = FloatArray(depth.size) { 10f - depth[it] }
        val f = DepthSelfCheck.compare(flipped, w, h, expected)
        assertFalse(f.passed)
        assertTrue(f.correlation < 0)

        val nan = depth.copyOf().also { it[0] = Float.NaN }
        assertFalse(DepthSelfCheck.compare(nan, w, h, expected).passed)
    }

    @Test
    fun blockMeansAndRgb() {
        val d = FloatArray(8 * 4) { if (it % 8 < 4) 1f else 3f }
        val b = DepthSelfCheck.blockMeans(d, 8, 4)
        assertEquals(listOf(1f, 3f), b.toList())
        val rgb = DepthSelfCheck.argbToRgbFloats(intArrayOf(0xFFFF8000.toInt()))
        assertEquals(1f, rgb[0], 1e-6f)
        assertEquals(128 / 255f, rgb[1], 1e-6f)
        assertEquals(0f, rgb[2], 1e-6f)
    }
}
