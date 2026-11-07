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
import com.redline.camera.colour.ColourPipeline
import com.redline.camera.depth.DepthEngine
import com.redline.camera.depth.DepthService
import com.redline.camera.dof.DofMath
import com.redline.camera.dof.DofStage
import com.redline.camera.nativebridge.RedlineNative
import java.io.File
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B31 CI gate: the save path's DOF stage end to end on a synthetic RAW-rendered still (the depth self-check scene at
 * 1500 x 1000, a tone curve and an Ultra HDR gain map as the RAW render hands them over), CPU delegate: depth runs
 * synchronously, the bokeh is applied in place, the gain map is recomputed, the photo is encoded and saved through
 * MediaStore, the sharp copy, lens metadata and depth sidecar are written; then a re-edit from them is saved as a new
 * photo. Timings go to the log and Downloads/RedlineCI/redline_ci_dof.json.
 */
@RunWith(AndroidJUnit4::class)
class DofSaveTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun still(): Bitmap {
        val opts = BitmapFactory.Options().apply {
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val src = context.assets.open(DepthEngine.SELFCHECK_IMAGE).use { BitmapFactory.decodeStream(it, null, opts) }!!
        val big = Bitmap.createScaledBitmap(src, W, H, true)
        src.recycle()
        return if (big.isMutable) big else big.copy(Bitmap.Config.ARGB_8888, true).also { big.recycle() }
    }

    /** sRGB encode of the linear value on the native grid lo..hi (log2), like the render's table. */
    private fun tone(): ColourPipeline.RenderTone {
        val lo = -14f
        val hi = 1f
        val n = 256
        val t = FloatArray(n) { i ->
            val x = 2.0.pow((lo + (hi - lo) * i / (n - 1) - hi).toDouble())
            val y = if (x <= 0.0031308) 12.92 * x else 1.055 * x.pow(1 / 2.4) - 0.055
            y.coerceIn(0.0, 1.0).toFloat()
        }
        t[n - 1] = 1f
        return ColourPipeline.RenderTone(t, lo, hi, -1.5f)
    }

    /** A gain map over the sky (top third, 1.5 stops), encoded with the B25 rules. */
    private fun hdr(): ColourPipeline.HdrMap {
        val cells = RedlineNative.GainMapCells(W, H, -1.5f)
        for (cy in 0 until cells.cellsH) {
            for (cx in 0 until cells.cellsW) cells.data[cy * cells.cellsW + cx] = if (cy < cells.cellsH / 3) 1.5f * 16 else 0f
        }
        cells.data[cells.data.size - 1] = 0.5f
        val b = RedlineNative.finishGainMap(cells, 2f, 0.2f)
        assertTrue("synthetic gain map written", b.written)
        return ColourPipeline.HdrMap(b, 0.5, null, 0, b.headroomStops, b.p999Stops, b.aboveKneeFraction)
    }

    @Test
    fun saveRenderedStillWithDofThenReedit() {
        assertTrue(RedlineNative.load().loaded)
        File(context.filesDir, DepthService.DIR).deleteRecursively()
        File(context.filesDir, DepthService.DOF_DIR).deleteRecursively()
        val svc = DepthService(context, cpuOnly = true)
        val stage = DofStage(context, svc)
        stage.syncTimeoutMs = 120_000
        val w0 = SystemClock.elapsedRealtime()
        stage.setEnabled(true) // warms the engine up
        while (!svc.engineReady && SystemClock.elapsedRealtime() - w0 < 180_000) Thread.sleep(100)
        val warmMs = SystemClock.elapsedRealtime() - w0
        assertTrue("engine ready: ${svc.report.engine?.error}", svc.engineReady)

        val bmp = still()
        val before = bmp.copy(Bitmap.Config.ARGB_8888, false)
        val base = "CI_DOF_${System.currentTimeMillis()}"
        val focus = DofMath.focusDistance(0.4f, 2, 50.0, 2.8) // AF at 2.5 m
        val shutterAt = SystemClock.elapsedRealtime()
        val out = stage.apply(
            DofStage.Request(
                still = bmp,
                rotation = 0,
                base = base,
                keep = true,
                focalMm = 50.0,
                phoneEquivalentAperture = DofMath.phoneEquivalentAperture(1.7, 6.4, 1.0 / 1.9),
                focus = focus,
                tap = null,
                tone = tone(),
                hdr = hdr(),
                threads = 2,
                nice = 5,
                lowMemory = false
            )
        )
        val shot = out.shot
        assertNotNull(shot)
        Log.i(TAG, "shot: ${Json.value(shot!!.toMap())}")
        assertTrue("applied (reason: ${shot.reason})", shot.applied)
        assertTrue(out.depthHandled)
        assertTrue("sharp copy started", shot.sharpCopy)
        assertNotNull("gain map note", shot.gainMap)
        // the far floor left of the subject (checkerboard just below the horizon) is blurred
        var changed = 0
        var n = 0
        val y = (H * 0.47).toInt()
        for (x in 0 until W / 4 step 5) {
            n++
            if (before.getPixel(x, y) != bmp.getPixel(x, y)) changed++
        }
        assertTrue("background changed ($changed of $n)", changed > n / 2)
        assertTrue((shot.bokehMs ?: 0) > 0)

        // encode and save the blurred photo through MediaStore (as the store lane does after the stage)
        val e0 = SystemClock.elapsedRealtime()
        val bytes = DofStage.saveJpeg(context, bmp, "$base.jpg", 0, "Redline CI DOF", keep = true)
        val encodeSaveMs = SystemClock.elapsedRealtime() - e0
        val total = SystemClock.elapsedRealtime() - shutterAt
        stage.finish(out, total)
        assertTrue(bytes > 10_000)
        assertTrue("photo in MediaStore", deleteFromMediaStore("$base.jpg") == 1)
        assertEquals(1, stage.report.applied)
        assertEquals(false, stage.busy)

        // sharp copy, lens metadata and sidecar on disk (io thread / depth worker)
        val d0 = SystemClock.elapsedRealtime()
        while ((!svc.sharpFile(base).isFile || !svc.sidecarFile(base).isFile) &&
            SystemClock.elapsedRealtime() - d0 < 60_000
        ) {
            Thread.sleep(50)
        }
        assertTrue("sharp copy written", svc.sharpFile(base).isFile)
        assertTrue("depth sidecar written", svc.sidecarFile(base).isFile)
        assertTrue("lens metadata written", svc.metaFile(base).isFile)
        val sharp = BitmapFactory.decodeFile(svc.sharpFile(base).absolutePath)
        assertEquals(W, sharp.width)
        assertEquals(H, sharp.height)
        sharp.recycle()
        assertEquals(listOf(base), stage.stored().map { it.base })

        // re-edit at f/8 focused on the centre, saved as a new photo
        val r = stage.reedit(base, 8f, 0.5, 0.5, threads = 2)
        Log.i(TAG, "reedit: ${Json.value(r.toMap())}")
        assertTrue("re-edit ok: ${r.reason}", r.ok)
        assertNotNull(r.savedAs)
        assertTrue("re-edit in MediaStore", deleteFromMediaStore(r.savedAs!!) == 1)
        svc.close()

        val timings = linkedMapOf(
            "device" to "${Build.MANUFACTURER} ${Build.MODEL} API ${Build.VERSION.SDK_INT}",
            "delegate" to "cpu",
            "size" to "${W}x$H",
            "engine_warm_ms" to warmMs,
            "dof" to shot.toMap(),
            "encode_save_ms" to encodeSaveMs,
            "stage_plus_save_ms" to total,
            "reedit" to r.toMap(),
            "report" to stage.report.toMap()
        )
        val json = Json.value(timings)
        Log.i(TAG, "REDLINE_CI_DOF $json")
        val f = File(context.cacheDir, "dof.json").apply { writeText(json) }
        runCatching { toDownloads(f, "redline_ci_dof.json") }.onFailure { Log.w(TAG, "could not copy to Downloads", it) }
    }

    private fun deleteFromMediaStore(name: String): Int = context.contentResolver.delete(
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
        arrayOf(name)
    )

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
        private const val TAG = "RedlineDofCI"
        private const val W = 1500
        private const val H = 1000
    }
}
