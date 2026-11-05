package com.redline.camera

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.redline.camera.nativebridge.RedlineNative
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B31: the JNI binding of the bokeh render on a device ABI. A sharp red subject on a striped background (far) keeps its
 * pixels; the background is blurred; low confidence leaves the still untouched with a reason.
 */
@RunWith(AndroidJUnit4::class)
class BokehNativeTest {
    private val w = 480
    private val h = 320

    private fun still(): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val subject = x in 180 until 300 && y in 80 until 300
                val c = when {
                    subject -> Color.rgb(200, 50, 40)
                    (x / 4) % 2 == 0 -> Color.rgb(30, 90, 120)
                    else -> Color.rgb(60, 140, 90)
                }
                bmp.setPixel(x, y, c)
            }
        }
        return bmp
    }

    private fun tone(): RedlineNative.BokehTone {
        val lo = -14f
        val hi = 1f
        val n = 256
        val curve = FloatArray(n) { i ->
            val x = 2.0.pow(lo + (hi - lo) * i / (n - 1) - hi)
            val y = if (x <= 0.0031308) 12.92 * x else 1.055 * x.pow(1 / 2.4) - 0.055
            y.coerceIn(0.0, 1.0).toFloat()
        }
        curve[n - 1] = 1f
        return RedlineNative.BokehTone(curve, lo, hi)
    }

    private fun depth(confidence: Float): RedlineNative.BokehDepth {
        val dw = 120
        val dh = 80
        val d = FloatArray(dw * dh)
        val m = FloatArray(dw * dh)
        for (y in 0 until dh) {
            for (x in 0 until dw) {
                val fx = (x + 0.5f) * w / dw
                val fy = (y + 0.5f) * h / dh
                val subject = fx >= 180 && fx < 300 && fy >= 80 && fy < 300
                d[y * dw + x] = if (subject) 0.8f else 0.05f
                m[y * dw + x] = if (subject) 1f else 0f
            }
        }
        return RedlineNative.BokehDepth(d, m, null, dw, dh, confidence)
    }

    @Test
    fun rendersAndGates() {
        assertTrue(RedlineNative.load().loaded)
        val lens = RedlineNative.BokehLens(
            focalMm = 70f,
            aperture = 2.8f,
            focusDistanceM = 1.5f,
            focusX = 0.5f,
            focusY = 0.6f,
            phoneEquivalentAperture = 6f
        )
        val bmp = still()
        val before = bmp.copy(Bitmap.Config.ARGB_8888, false)
        val r = RedlineNative.bokehRender(bmp, lens, tone(), depth(0.8f), threads = 2)
        assertTrue(r.applied)
        assertTrue(r.maxRadiusPx > 2f)
        assertEquals(before.getPixel(240, 190), bmp.getPixel(240, 190))
        var changed = 0
        for (x in 10 until 150) if (before.getPixel(x, 40) != bmp.getPixel(x, 40)) changed++
        assertTrue("background blurred ($changed of 140 changed)", changed > 100)

        val low = still()
        val r2 = RedlineNative.bokehRender(low, lens, tone(), depth(0.2f))
        assertFalse(r2.applied)
        assertNotNull(r2.reason)
        assertTrue(low.sameAs(still()))
    }
}
