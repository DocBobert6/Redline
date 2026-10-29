package com.redline.camera

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.redline.camera.look.LookPreviewRenderer
import com.redline.camera.nativebridge.RedlineNative
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * B20 on a real Android runtime: the native module loads for this ABI and parses the bundled LUT, and wherever the
 * GPU offers OpenGL ES 3 with external-image ESSL3 the look renderer's start-up checks (shader compile, 3D LUT
 * upload, GPU-vs-CPU self-check) pass. Without those the test is skipped: the app then runs on the plain preview.
 */
@RunWith(AndroidJUnit4::class)
class LookPreviewTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun lutBytes(): ByteArray = context.assets.open(LUT).use { it.readBytes() }

    @Test
    fun nativeModuleLoadsAndParsesTheBundledLut() {
        val loaded = RedlineNative.load()
        assertTrue("native load failed: ${loaded.error}", loaded.loaded)
        assertTrue(loaded.version!!.startsWith("redline_native"))
        val lut = RedlineNative.parseCube(lutBytes())
        assertEquals(33, lut.size)
        assertEquals(33 * 33 * 33 * 3, lut.data.size)
        assertTrue(lut.data.all { it in 0f..1f })
    }

    @Test
    fun rendererPreparesWhereGles3IsAvailable() {
        val result = CompletableFuture<LookPreviewRenderer.Caps>()
        val renderer = LookPreviewRenderer(
            object : LookPreviewRenderer.Listener {
                override fun onPrepared(caps: LookPreviewRenderer.Caps) {
                    result.complete(caps)
                }

                override fun onStats(stats: LookPreviewRenderer.Stats) = Unit

                override fun onRuntimeError(message: String) = Unit
            }
        )
        try {
            renderer.prepare { RedlineNative.parseCube(lutBytes()) }
            val caps = result.get(30, TimeUnit.SECONDS)
            assumeTrue("no OpenGL ES 3 + external ESSL3 here (${caps.glVersion}): ${caps.reason}", caps.es3 && caps.externalEssl3)
            assertTrue(
                "look renderer start-up failed on ${caps.glVersion} / ${caps.renderer}: ${caps.reason} " +
                    "(format ${caps.lutFormat}, self-check ${caps.selfCheckMaxErrorSteps}/255)",
                caps.ok
            )
            assertEquals(33, caps.lutSize)
        } finally {
            renderer.release()
        }
    }

    companion object {
        private const val LUT = "luts/redline_standard_preview_33.cube"
    }
}
