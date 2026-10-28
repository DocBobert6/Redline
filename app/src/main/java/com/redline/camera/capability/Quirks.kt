package com.redline.camera.capability

/**
 * Feature keys shared by the quirks table, the self-test, the combination checks and [FeaturePolicy].
 * A quirk entry's `disable` list names these keys.
 */
object FeatureKeys {
    /** RAW_SENSOR capture (RAW + JPEG still path). */
    const val RAW = "raw"

    /** True 24p video (fixed [24,24] AE target range). */
    const val VIDEO_24P = "video_24p"

    /** CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION. */
    const val PREVIEW_STABILIZATION = "preview_stabilization"

    /** Preview stabilization together with a 60 fps range. */
    const val STABILIZED_60FPS = "stabilized_60fps"

    /** Ignore CameraDeviceSetup session queries for a HAL known to answer them wrongly; use the mandatory tables. */
    const val SESSION_QUERY = "session_query"

    val ALL = listOf(RAW, VIDEO_24P, PREVIEW_STABILIZATION, STABILIZED_60FPS, SESSION_QUERY)
}

/** What a device looks like to the quirks matcher. */
data class DeviceKey(val manufacturer: String, val model: String, val fingerprint: String, val sdkInt: Int)

data class QuirkMatch(
    val manufacturer: String? = null,
    val model: String? = null,
    val fingerprintPrefix: String? = null,
    val sdkMin: Int? = null,
    val sdkMax: Int? = null
) {
    /** A match with no fields would hit every phone; it is ignored rather than trusted. */
    val isEmpty: Boolean
        get() = manufacturer == null && model == null && fingerprintPrefix == null && sdkMin == null && sdkMax == null

    fun matches(d: DeviceKey): Boolean {
        if (isEmpty) return false
        if (manufacturer != null && !manufacturer.equals(d.manufacturer.trim(), ignoreCase = true)) return false
        if (model != null && !model.equals(d.model.trim(), ignoreCase = true)) return false
        if (fingerprintPrefix != null && !d.fingerprint.startsWith(fingerprintPrefix)) return false
        if (sdkMin != null && d.sdkInt < sdkMin) return false
        if (sdkMax != null && d.sdkInt > sdkMax) return false
        return true
    }
}

data class QuirkEntry(val match: QuirkMatch, val disable: List<String>, val note: String)

/**
 * Known-bad HAL behaviour (PLAN 4.2). Bundled as assets/quirks.json, optionally refreshed from the
 * repository; a matching entry disables features regardless of what detection says.
 */
data class QuirksTable(val version: Int, val entries: List<QuirkEntry>, val source: String = "bundled") {
    fun matching(d: DeviceKey): List<QuirkEntry> = entries.filter { it.match.matches(d) }

    /** Feature key -> note of the first matching entry that disables it. */
    fun disabledFor(d: DeviceKey): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for (e in matching(d)) {
            for (k in e.disable) {
                if (k !in out) out[k] = e.note
            }
        }
        return out
    }

    companion object {
        val EMPTY = QuirksTable(0, emptyList(), "none")

        /** Throws on malformed JSON or a missing version, so a bad remote copy is never used. */
        fun parse(json: String, source: String = "bundled"): QuirksTable {
            val root = JsonParse.parse(json) as? Map<*, *> ?: throw IllegalArgumentException("quirks root is not an object")
            val version = root.int("version") ?: throw IllegalArgumentException("quirks version missing")
            val entries = root.list("entries").mapNotNull { e ->
                val m = e as? Map<*, *> ?: return@mapNotNull null
                val match = m.obj("match") ?: return@mapNotNull null
                QuirkEntry(
                    match = QuirkMatch(
                        manufacturer = match.str("manufacturer"),
                        model = match.str("model"),
                        fingerprintPrefix = match.str("fingerprint_prefix"),
                        sdkMin = match.int("sdk_min"),
                        sdkMax = match.int("sdk_max")
                    ),
                    disable = m.list("disable").mapNotNull { it as? String },
                    note = m.str("note").orEmpty()
                )
            }
            return QuirksTable(version, entries, source)
        }

        fun parseOrNull(json: String?, source: String): QuirksTable? = json?.let { runCatching { parse(it, source) }.getOrNull() }

        /** Prefer the remote copy only when it parses and is at least as new as the bundled one. */
        fun pick(bundled: QuirksTable, remote: QuirksTable?): QuirksTable =
            if (remote != null && remote.version >= bundled.version) remote else bundled
    }
}
