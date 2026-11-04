package com.redline.camera

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.redline.camera.capability.Json
import com.redline.camera.capability.JsonParse
import com.redline.camera.depth.DepthEngine
import com.redline.camera.depth.DepthPng
import com.redline.camera.depth.DepthService
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B30 CI gate: the depth engine CPU-only on the self-check scene (the model must reproduce the torch reference), then the
 * whole depth lane ([DepthService]: native prep, both models, native finish, sidecar write) on the same scene, and the
 * sidecar read back (size, 16-bit PNG, metadata). Stage timings go to the log and to Downloads/RedlineCI/redline_ci_depth.json.
 */
@RunWith(AndroidJUnit4::class)
class DepthEngineTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun scene(): Bitmap {
        val opts = BitmapFactory.Options().apply {
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.assets.open(DepthEngine.SELFCHECK_IMAGE).use { BitmapFactory.decodeStream(it, null, opts) }!!
    }

    @Test
    fun cpuSelfCheckThenLaneWritesSidecar() {
        // 1. The engine alone, CPU only: the self-check is the correctness gate.
        val t0 = SystemClock.elapsedRealtime()
        val engine = DepthEngine.open(context.assets, cpuOnly = true)
        val openMs = SystemClock.elapsedRealtime() - t0
        val info = engine.info
        Log.i(TAG, "engine: ${Json.value(info.toMap())}")
        assertTrue("engine ready: ${info.error} ${info.attempts.map { it.toMap() }}", engine.ready)
        assertEquals(DepthEngine.CPU, info.delegate)
        val check = info.attempts.first { it.model == "depth" && it.passed }
        assertTrue("self-check correlation ${check.correlation}", (check.correlation ?: 0.0) >= 0.998)
        val input = DepthEngine.selfCheckInput(context.assets)
        val r0 = SystemClock.elapsedRealtime()
        val depth = engine.runDepth(input)
        val depthMs = SystemClock.elapsedRealtime() - r0
        assertEquals(DepthEngine.W * DepthEngine.H, depth.size)
        assertTrue(depth.all { it.isFinite() })
        engine.close()

        // 2. The lane end to end on the same scene (as a rendered still, upright).
        File(context.filesDir, DepthService.DIR).deleteRecursively()
        val svc = DepthService(context, cpuOnly = true)
        val base = "CI_DEPTH_${System.currentTimeMillis()}"
        val still = scene()
        svc.submit(still, 0, base)
        still.recycle()
        val deadline = SystemClock.elapsedRealtime() + 120_000
        while (svc.report.shots == 0 && svc.report.skips.isEmpty() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        val rep = svc.report
        svc.close()
        Log.i(TAG, "report: ${Json.value(rep.toMap())}")
        assertTrue("skips ${rep.skips} engine ${rep.engine?.error}", rep.skips.isEmpty())
        val shot = rep.recent.single()
        assertTrue("shot failed: ${shot.error}", shot.ok)
        assertEquals(DepthEngine.CPU, shot.delegate)

        // 3. The sidecar read back.
        val file = File(context.filesDir, "${DepthService.DIR}/$base${DepthService.SUFFIX}")
        assertTrue("sidecar exists", file.isFile)
        val bytes = file.readBytes()
        assertEquals(file.length(), shot.storedBytes)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        assertEquals(shot.width, bounds.outWidth)
        assertEquals(shot.height, bounds.outHeight)
        assertTrue("sidecar ${bounds.outWidth}x${bounds.outHeight}", bounds.outWidth > 0 && bounds.outHeight > 0)
        // IHDR: 16-bit, colour type 2 (RGB).
        assertEquals(16, bytes[24].toInt())
        assertEquals(2, bytes[25].toInt())
        val text = DepthPng.readText(bytes)
        assertNotNull("metadata chunk", text)
        val meta = JsonParse.parse(text!!) as Map<*, *>
        assertEquals(1L, (meta["version"] as Number).toLong())
        assertEquals(DepthEngine.MODEL_NAME, meta["model"])
        assertEquals(DepthEngine.SEG_NAME, meta["segmenter"])
        assertEquals(DepthEngine.CPU, meta["delegate"])
        assertEquals("${shot.width}x${shot.height}", meta["size"])
        assertEquals("${DepthEngine.W}x${DepthEngine.H}", meta["image_size"])
        assertEquals(0L, (meta["rotation"] as Number).toLong())
        assertEquals(false, meta["distortion_warped"])
        val conf = (meta["confidence"] as Number).toDouble()
        assertTrue("confidence $conf", conf in 0.0..1.0)
        assertEquals(shot.confidence!!, conf, 1e-3)
        val dmin = (meta["dmin"] as Number).toDouble()
        val dmax = (meta["dmax"] as Number).toDouble()
        assertTrue("dmin $dmin < dmax $dmax", dmin < dmax)

        val timings = linkedMapOf(
            "device" to "${Build.MANUFACTURER} ${Build.MODEL} API ${Build.VERSION.SDK_INT}",
            "delegate" to "cpu",
            "engine_open_ms" to openMs,
            "engine" to info.toMap(),
            "selfcheck_scene_depth_ms" to depthMs,
            "lane_ms" to linkedMapOf(
                "prep" to shot.prepMs,
                "queue" to shot.queueMs,
                "depth_infer" to shot.depthMs,
                "segmentation" to shot.segMs,
                "finish" to shot.finishMs,
                "sidecar_write" to shot.writeMs,
                "total" to shot.totalMs
            ),
            "lane_engine_init_ms" to rep.engine?.initMs,
            "confidence" to conf,
            "usable" to shot.usable,
            "sidecar" to "${shot.width}x${shot.height}, ${bytes.size} bytes"
        )
        val json = Json.value(timings)
        Log.i(TAG, "REDLINE_CI_DEPTH $json")
        val f = File(context.cacheDir, "depth.json").apply { writeText(json) }
        runCatching { toDownloads(f, "redline_ci_depth.json") }.onFailure { Log.w(TAG, "could not copy to Downloads", it) }
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
        private const val TAG = "RedlineDepthCI"
    }
}
