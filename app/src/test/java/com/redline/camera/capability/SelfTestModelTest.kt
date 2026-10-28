package com.redline.camera.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfTestModelTest {
    @Test
    fun reportRoundTripsThroughJson() {
        val r = Baseline.selfTest().copy(
            checks = listOf(CheckResult("fps_24", "24 fps fixed range", CheckStatus.FAIL, "21.3 fps \"low\"", mapOf("fps" to 21.3)))
        )
        val back = SelfTestReport.fromJson(r.toJson())
        assertEquals(r, back)
        assertFalse(back!!.passed)
    }

    @Test
    fun corruptOrOldCacheIsIgnored() {
        assertNull(SelfTestReport.fromJson("{"))
        assertNull(SelfTestReport.fromJson("""{"schema": 99, "build_fingerprint": "x"}"""))
        assertNull(SelfTestReport.fromJson(null))
    }

    @Test
    fun permissionDeniedIsSkippedNotFailedAndIncomplete() {
        val r = SelfTestReport.allSkipped("fp", "0", "CAMERA permission not granted", 0L)
        assertTrue(r.checks.all { it.status == CheckStatus.SKIPPED })
        assertTrue(r.failures.isEmpty())
        assertFalse(r.complete)
        assertFalse(r.passed)
        assertEquals(SelfTestKeys.LABELS.keys.toList(), r.checks.map { it.key })
    }

    @Test
    fun fpsFromSensorTimestamps() {
        val period = 1_000_000_000L / 24
        val ts = (0 until 36).map { it * period }
        assertEquals(24.0, FpsMath.measure(ts)!!, 0.01)
        assertEquals(24.0, FpsMath.measure(ts, warmupNs = 500_000_000L)!!, 0.01)
        assertTrue(FpsMath.within(23.0, 24.0, 1.5))
        assertFalse(FpsMath.within(29.9, 24.0, 1.5))
        assertFalse(FpsMath.within(null, 24.0, 1.5))
        assertNull(FpsMath.measure(listOf(1L, 2L)))
    }

    @Test
    fun jsonParserReadsWhatJsonWrites() {
        val src = linkedMapOf<String, Any?>("a" to listOf(1, 2.5, "x\n\"y\""), "b" to null, "c" to true, "d" to emptyMap<String, Any?>())
        val parsed = JsonParse.parse(Json.value(src)) as Map<*, *>
        assertEquals(listOf(1L, 2.5, "x\n\"y\""), parsed["a"])
        assertNull(parsed["b"])
        assertEquals(true, parsed["c"])
        assertEquals(emptyMap<String, Any?>(), parsed["d"])
    }

    @Test
    fun streamSizePickers() {
        val sizes = listOf("4000x3000", "3840x2160", "2560x1440", "1920x1080", "640x480").mapNotNull { Dim.parse(it) }
        assertEquals(Dim(1920, 1080), StreamSizes.preview(sizes))
        assertEquals(Dim(1280, 720), StreamSizes.preview(listOf(Dim(4000, 3000), Dim(1280, 720), Dim(640, 480))))
        assertEquals(Dim(4000, 3000), StreamSizes.largest(sizes))
        assertEquals(listOf(Dim(3840, 2160), Dim(1920, 1080)), StreamSizes.videoCandidates(sizes))
    }

    @Test
    fun mandatoryTableAssumesButNeverVerifies() {
        val combos = MandatoryCombos.assumeAll(Baseline.back, "API 34")
        assertTrue(combos.none { it.source == ComboSource.VERIFIED })
        assertTrue(combos.none { it.verified })
        val byKey = combos.associateBy { it.key }
        assertEquals(ComboStatus.SUPPORTED, byKey.getValue(ComboKeys.PREVIEW_RAW).status)
        assertEquals(ComboStatus.UNKNOWN, byKey.getValue(ComboKeys.PREVIEW_STAB_60).status)
        val noRaw = MandatoryCombos.assume(ComboKeys.PREVIEW_RAW, Baseline.back.copy(capabilities = listOf("BACKWARD_COMPATIBLE")), "x")
        assertEquals(ComboStatus.UNSUPPORTED, noRaw.status)
    }
}
