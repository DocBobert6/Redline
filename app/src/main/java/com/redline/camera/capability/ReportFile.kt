package com.redline.camera.capability

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Report export as a file: chat apps truncate pasted text (about 20,000 characters), so the full JSON report
 * is also written to `cache/reports/redline-report-<timestamp>.txt` and shared as text/plain through FileProvider.
 */
object ReportFile {
    const val DIR = "reports"
    const val PREFIX = "redline-report-"
    const val SUFFIX = ".txt"
    const val MIME = "text/plain"

    /** Reports kept in the cache folder; older ones are deleted when a new one is written. */
    const val KEEP = 5

    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /** `redline-report-20261101-121400.txt` for the given time in the given zone. */
    fun fileName(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        PREFIX + STAMP.format(Instant.ofEpochMilli(nowMs).atZone(zone)) + SUFFIX

    /** Writes [json] in full (UTF-8) to `<cacheDir>/reports/<fileName>` and prunes older reports beyond [KEEP]. */
    fun write(cacheDir: File, json: String, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): File {
        val dir = File(cacheDir, DIR)
        dir.mkdirs()
        val file = File(dir, fileName(nowMs, zone))
        file.writeText(json, Charsets.UTF_8)
        prune(dir, keep = file)
        return file
    }

    private fun prune(dir: File, keep: File) {
        val old = dir.listFiles { f -> f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX) && f != keep }
            ?.sortedByDescending { it.name }
            ?: return
        old.drop(KEEP - 1).forEach { it.delete() }
    }
}
