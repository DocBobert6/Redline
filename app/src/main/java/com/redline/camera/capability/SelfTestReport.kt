package com.redline.camera.capability

import kotlin.math.abs

enum class CheckStatus { PASS, FAIL, SKIPPED }

data class CheckResult(
    val key: String,
    val label: String,
    val status: CheckStatus,
    val reason: String,
    val metrics: Map<String, Any?> = emptyMap()
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "key" to key,
        "label" to label,
        "status" to status.name,
        "reason" to reason,
        "metrics" to metrics
    )

    companion object {
        fun fromMap(m: Map<*, *>): CheckResult? {
            val key = m.str("key") ?: return null
            val status = runCatching { CheckStatus.valueOf(m.str("status").orEmpty()) }.getOrNull() ?: return null
            val metrics = m.obj("metrics")?.entries?.associate { (k, v) -> k.toString() to v }.orEmpty()
            return CheckResult(key, m.str("label") ?: key, status, m.str("reason").orEmpty(), metrics)
        }
    }
}

object SelfTestKeys {
    const val PREVIEW = "preview_frames"
    const val JPEG = "still_jpeg"
    const val RAW = "raw_capture"
    const val FPS_24 = "fps_24"

    val LABELS = linkedMapOf(
        PREVIEW to "Preview frames",
        JPEG to "Still JPEG capture",
        RAW to "RAW_SENSOR capture + metadata",
        FPS_24 to "24 fps fixed range"
    )

    fun label(key: String): String = LABELS[key] ?: key
}

/**
 * Result of the first-launch self-test (PLAN 4.2), cached per OS build like device_profile.json.
 * [complete] is false when the run could not reach the camera (no permission, camera busy);
 * such a run is shown but not cached, so the test runs again next launch.
 */
data class SelfTestReport(
    val schema: Int = SCHEMA,
    val buildFingerprint: String,
    val cameraId: String?,
    val ranAtMs: Long,
    val complete: Boolean,
    val checks: List<CheckResult>
) {
    fun check(key: String): CheckResult? = checks.firstOrNull { it.key == key }

    val failures: List<CheckResult> get() = checks.filter { it.status == CheckStatus.FAIL }

    /** The B11 gate counts a run as passed when it completed and nothing failed. */
    val passed: Boolean get() = complete && failures.isEmpty()

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "schema" to schema,
        "build_fingerprint" to buildFingerprint,
        "camera_id" to cameraId,
        "ran_at_ms" to ranAtMs,
        "complete" to complete,
        "passed" to passed,
        "checks" to checks.map { it.toMap() }
    )

    fun toJson(): String = Json.value(toMap())

    companion object {
        const val SCHEMA = 1

        fun fromJson(json: String?): SelfTestReport? {
            val m = JsonParse.parseOrNull(json) as? Map<*, *> ?: return null
            if (m.int("schema") != SCHEMA) return null
            return SelfTestReport(
                buildFingerprint = m.str("build_fingerprint") ?: return null,
                cameraId = m.str("camera_id"),
                ranAtMs = (m["ran_at_ms"] as? Number)?.toLong() ?: 0L,
                complete = m.bool("complete") == true,
                checks = m.list("checks").mapNotNull { c -> (c as? Map<*, *>)?.let { CheckResult.fromMap(it) } }
            )
        }

        /** Every check skipped with the same reason, e.g. when the CAMERA permission is missing. */
        fun allSkipped(fingerprint: String, cameraId: String?, reason: String, nowMs: Long): SelfTestReport = SelfTestReport(
            buildFingerprint = fingerprint,
            cameraId = cameraId,
            ranAtMs = nowMs,
            complete = false,
            checks = SelfTestKeys.LABELS.map { (k, label) -> CheckResult(k, label, CheckStatus.SKIPPED, reason) }
        )
    }
}

object FpsMath {
    /**
     * Frame rate from sensor timestamps (ns), ignoring frames in the first [warmupNs] while AE settles.
     * Returns null with fewer than 3 usable frames.
     */
    fun measure(timestampsNs: List<Long>, warmupNs: Long = 0L): Double? {
        if (timestampsNs.isEmpty()) return null
        val sorted = timestampsNs.sorted()
        val usable = sorted.filter { it >= sorted.first() + warmupNs }
        if (usable.size < 3) return null
        val span = usable.last() - usable.first()
        return if (span <= 0) null else (usable.size - 1) * 1e9 / span
    }

    fun within(measured: Double?, target: Double, tolerance: Double): Boolean = measured != null && abs(measured - target) <= tolerance
}

/**
 * Debug-only forced failures so demotion can be exercised without a broken HAL. Keys are self-test
 * keys ([SelfTestKeys]) or `combo:<ComboKeys>`; anything else is ignored.
 */
object DebugOverrides {
    const val REASON = "forced failure (debug)"
    const val COMBO_PREFIX = "combo:"

    fun parse(extra: String?): Set<String> = extra.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun applySelfTest(report: SelfTestReport?, forced: Set<String>): SelfTestReport? {
        if (report == null || forced.isEmpty()) return report
        val checks = report.checks.map { c -> if (c.key in forced) c.copy(status = CheckStatus.FAIL, reason = REASON) else c }
        return report.copy(checks = checks)
    }

    fun applyCombos(combos: List<ComboResult>, forced: Set<String>): List<ComboResult> = combos.map { c ->
        if ((COMBO_PREFIX + c.key) in forced) c.copy(status = ComboStatus.UNSUPPORTED, reason = REASON) else c
    }
}
