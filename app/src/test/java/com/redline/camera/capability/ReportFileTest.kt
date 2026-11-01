package com.redline.camera.capability

import java.nio.file.Files
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportFileTest {
    private val utc = ZoneId.of("UTC")

    @Test
    fun fileNameCarriesTimestamp() {
        // 2026-11-01 17:14:05 UTC
        assertEquals("redline-report-20261101-171405.txt", ReportFile.fileName(1_793_553_245_000L, utc))
    }

    @Test
    fun writesFullReportUntruncated() {
        val dir = Files.createTempDirectory("rl").toFile()
        val json = "{\"k\":\"" + "x".repeat(60_000) + "\"}"
        val f = ReportFile.write(dir, json, 1_793_553_245_000L, utc)
        assertEquals(java.io.File(dir, "reports"), f.parentFile)
        assertEquals(json, f.readText())
        assertTrue(f.name.endsWith(".txt"))
    }

    @Test
    fun keepsOnlyRecentReports() {
        val dir = Files.createTempDirectory("rl").toFile()
        repeat(8) { i -> ReportFile.write(dir, "{}", 1_793_553_245_000L + i * 1_000L, utc) }
        val left = java.io.File(dir, "reports").listFiles()!!.map { it.name }.sorted()
        assertEquals(ReportFile.KEEP, left.size)
        assertEquals("redline-report-20261101-171412.txt", left.last())
    }
}
