package com.redline.camera.capability

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuirksTest {
    private val device = DeviceKey("Acme", "Phone 9", "acme/phone9/phone9:15/AP1A/123:user/release-keys", 35)

    private val json = """
        {
          "version": 3,
          "entries": [
            {"match": {"manufacturer": "acme", "model": "Phone 9"}, "disable": ["raw"], "note": "RAW frames corrupt"},
            {
              "match": {"fingerprint_prefix": "acme/phone9/", "sdk_min": 34, "sdk_max": 35},
              "disable": ["video_24p", "raw"],
              "note": "24p drifts"
            },
            {"match": {"manufacturer": "other"}, "disable": ["preview_stabilization"], "note": "not us"},
            {"match": {}, "disable": ["raw"], "note": "empty match is ignored"}
          ]
        }
    """.trimIndent()

    @Test
    fun parsesSchema() {
        val t = QuirksTable.parse(json)
        assertEquals(3, t.version)
        assertEquals(4, t.entries.size)
        assertEquals(listOf("video_24p", "raw"), t.entries[1].disable)
        assertEquals(35, t.entries[1].match.sdkMax)
    }

    @Test
    fun matchesByManufacturerModelCaseInsensitively() {
        assertTrue(QuirkMatch(manufacturer = "ACME", model = "phone 9").matches(device))
        assertFalse(QuirkMatch(manufacturer = "acme", model = "Phone 10").matches(device))
    }

    @Test
    fun matchesFingerprintPrefixAndSdkRange() {
        assertTrue(QuirkMatch(fingerprintPrefix = "acme/phone9/").matches(device))
        assertFalse(QuirkMatch(fingerprintPrefix = "acme/phone8/").matches(device))
        assertTrue(QuirkMatch(manufacturer = "acme", sdkMin = 35).matches(device))
        assertFalse(QuirkMatch(manufacturer = "acme", sdkMin = 36).matches(device))
        assertFalse(QuirkMatch(manufacturer = "acme", sdkMax = 34).matches(device))
    }

    @Test
    fun emptyMatchNeverMatches() {
        assertFalse(QuirkMatch().matches(device))
    }

    @Test
    fun disabledForCollectsKeysWithFirstNote() {
        val disabled = QuirksTable.parse(json).disabledFor(device)
        assertEquals(setOf("raw", "video_24p"), disabled.keys)
        assertEquals("RAW frames corrupt", disabled["raw"])
        assertEquals("24p drifts", disabled["video_24p"])
    }

    @Test
    fun malformedRemoteIsRejectedAndBundledWins() {
        assertNull(QuirksTable.parseOrNull("{not json", "remote"))
        assertNull(QuirksTable.parseOrNull("""{"entries": []}""", "remote"))
        val bundled = QuirksTable(2, emptyList())
        assertEquals(bundled, QuirksTable.pick(bundled, null))
        assertEquals(bundled, QuirksTable.pick(bundled, QuirksTable(1, emptyList(), "remote")))
        assertEquals("remote", QuirksTable.pick(bundled, QuirksTable(2, emptyList(), "remote")).source)
    }

    @Test
    fun bundledAssetParsesWithNoEntries() {
        val asset = listOf("src/main/assets/quirks.json", "app/src/main/assets/quirks.json").map(::File).first { it.exists() }
        val t = QuirksTable.parse(asset.readText())
        assertNotNull(t)
        assertTrue(t.version >= 1)
        assertTrue(t.entries.isEmpty())
        assertTrue(t.disabledFor(device).isEmpty())
    }

    @Test
    fun featureKeysAreKnown() {
        val t = QuirksTable.parse(json)
        assertTrue(t.entries.flatMap { it.disable }.all { it in FeatureKeys.ALL })
    }
}
