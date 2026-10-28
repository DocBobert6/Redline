package com.redline.camera.capability

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Loads the quirks table: the bundled assets/quirks.json, replaced by a cached remote copy when that
 * copy parses and is at least as new. [refresh] fetches the remote copy with a short timeout and must
 * run off the main thread; it never throws and a failed or malformed download changes nothing.
 * No Firebase or remote config (deferred to B51); the remote copy is the file on the main branch.
 */
class QuirksRepository(context: Context) {
    private val appContext = context.applicationContext
    private val cacheFile = File(appContext.filesDir, CACHE_FILE)

    fun bundled(): QuirksTable = runCatching {
        QuirksTable.parse(appContext.assets.open(ASSET).bufferedReader().use { it.readText() }, "bundled")
    }.getOrElse { QuirksTable.EMPTY }

    fun cached(): QuirksTable? = QuirksTable.parseOrNull(runCatching { cacheFile.readText() }.getOrNull(), "remote (cached)")

    fun load(): QuirksTable = QuirksTable.pick(bundled(), cached())

    /** Downloads the remote table; returns it when it was valid and cached, else null. */
    fun refresh(): QuirksTable? = try {
        val conn = (URL(REMOTE_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                null
            } else {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val table = QuirksTable.parse(text, "remote")
                cacheFile.writeText(text)
                table
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        Log.i(TAG, "Quirks refresh skipped: ${e.javaClass.simpleName}")
        null
    }

    companion object {
        const val ASSET = "quirks.json"
        const val CACHE_FILE = "quirks_remote.json"
        const val REMOTE_URL = "https://raw.githubusercontent.com/DocBobert6/Redline/main/app/src/main/assets/quirks.json"
        private const val TAG = "RedlineQuirks"
        private const val TIMEOUT_MS = 3_000

        fun deviceKey(): DeviceKey = DeviceKey(Build.MANUFACTURER, Build.MODEL, Build.FINGERPRINT, Build.VERSION.SDK_INT)
    }
}
