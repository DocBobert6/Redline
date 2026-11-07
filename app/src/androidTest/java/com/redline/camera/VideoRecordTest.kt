package com.redline.camera

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.redline.camera.capability.Json
import com.redline.camera.ui.MODE_VIDEO_TAG
import com.redline.camera.ui.RECORD_TAG
import com.redline.camera.ui.RECORD_TIMER_TAG
import com.redline.camera.video.VideoReport
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B40 on the emulator: switch the viewfinder to video, record about 3 s through the real chain (camera -> GPU -> encoder;
 * the emulator's software encoder and SwiftShader GL are fine), stop, and check the MP4 in Movies/Redline: it opens, has
 * a video track (HEVC, or AVC where no HEVC encoder takes the size), the track count the recorder reported (audio only
 * when the emulator's microphone delivered), about 3 s long, and a frame decodes. GPU chain timing is not asserted here
 * (CI policy: GPU timers / gates are judged on the device).
 */
@RunWith(AndroidJUnit4::class)
class VideoRecordTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private var saved: Uri? = null

    @After
    fun tearDown() {
        scenario?.close()
        saved?.let { runCatching { context.contentResolver.delete(it, null, null) } }
    }

    private fun exists(tag: String): Boolean = try {
        compose.onNodeWithTag(tag).assertExists()
        true
    } catch (e: Throwable) {
        false
    }

    private fun enabled(tag: String): Boolean = try {
        compose.onNodeWithTag(tag).assertIsEnabled()
        true
    } catch (e: Throwable) {
        false
    }

    private fun report(): VideoReport {
        var r = VideoReport()
        scenario?.onActivity { r = it.videoReportForTest() }
        return r
    }

    @Test
    fun recordsThreeSecondsToAPlayableMp4() {
        val pkg = context.packageName
        instrumentation.uiAutomation.grantRuntimePermission(pkg, Manifest.permission.CAMERA)
        instrumentation.uiAutomation.grantRuntimePermission(pkg, Manifest.permission.RECORD_AUDIO)
        val intent = Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_NO_PERMISSION_PROMPT, true)
        scenario = ActivityScenario.launch(intent)

        compose.waitUntil(TIMEOUT_MS) { exists(MODE_VIDEO_TAG) }
        compose.onNodeWithTag(MODE_VIDEO_TAG).performClick()
        try {
            compose.waitUntil(TIMEOUT_MS) { exists(RECORD_TAG) && enabled(RECORD_TAG) }
        } catch (e: Throwable) {
            throw AssertionError("video session did not stream: ${report().toMap()}", e)
        }
        compose.onNodeWithTag(RECORD_TAG).performClick()
        try {
            compose.waitUntil(TIMEOUT_MS) { exists(RECORD_TIMER_TAG) }
        } catch (e: Throwable) {
            throw AssertionError("recording did not start: ${report().toMap()}", e)
        }
        SystemClock.sleep(RECORD_MS)
        compose.onNodeWithTag(RECORD_TAG).performClick()
        compose.waitUntil(TIMEOUT_MS) { report().recordings.isNotEmpty() }
        val r = report()
        val rec = r.recordings.last()
        var uri: Uri? = null
        scenario?.onActivity { uri = it.lastVideoUri }
        saved = uri
        assertNotNull("recording not saved: ${r.toMap()}", uri)
        assertTrue("no frames: ${r.toMap()}", rec.framesEncoded > 0)

        val extractor = MediaExtractor()
        context.contentResolver.openFileDescriptor(uri!!, "r")!!.use { extractor.setDataSource(it.fileDescriptor) }
        val mimes = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() }
        extractor.release()
        assertEquals("tracks $mimes vs report ${r.toMap()}", rec.tracks, mimes.size)
        assertTrue("no video track: $mimes", mimes.any { it == MediaFormat.MIMETYPE_VIDEO_HEVC || it == MediaFormat.MIMETYPE_VIDEO_AVC })
        assertEquals("audio track vs report: $mimes", rec.audio, mimes.any { it.startsWith("audio/") })

        val mmr = MediaMetadataRetriever()
        try {
            context.contentResolver.openFileDescriptor(uri!!, "r")!!.use { mmr.setDataSource(it.fileDescriptor) }
            val durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            assertTrue("duration $durationMs ms (${r.toMap()})", durationMs in 1_500L..8_000L)
            val frame = mmr.getFrameAtTime(500_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            assertNotNull("no frame decoded", frame)
        } finally {
            mmr.release()
        }
        // What the emulator measured, for the CI log ("Emulator measurements" prints Downloads/RedlineCI/*.json).
        val json = Json.value(report().toMap() + mapOf("tracks_in_file" to mimes))
        Log.i(TAG, "REDLINE_CI_VIDEO $json")
        val f = File(context.cacheDir, "video.json").apply { writeText(json) }
        runCatching { toDownloads(f, "redline_ci_video.json") }.onFailure { Log.w(TAG, "could not copy to Downloads", it) }
    }

    private fun toDownloads(f: File, name: String) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/RedlineCI")
        }
        val r = context.contentResolver
        r.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, "${MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf(name))
        val uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore insert failed")
        r.openOutputStream(uri)!!.use { os -> f.inputStream().use { it.copyTo(os) } }
    }

    companion object {
        private const val TIMEOUT_MS = 30_000L
        private const val RECORD_MS = 3_200L
        private const val TAG = "RedlineVideoCI"
    }
}
