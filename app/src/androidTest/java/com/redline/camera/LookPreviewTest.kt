package com.redline.camera

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.redline.camera.look.LookPreviewRenderer
import com.redline.camera.nativebridge.RedlineNative
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B20 on a real Android runtime: the native module loads for this ABI and parses the bundled LUT, and wherever the
 * GPU offers OpenGL ES 3 with external-image ESSL3 the look renderer's start-up checks (shader compile, 3D LUT
 * upload, GPU-vs-CPU self-check) pass. Without those, or on an emulator / software GLES renderer, the render check
 * is skipped (Assume): real GPU verification happens on the device, where the app's own self-check gates the feature.
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
            // GPU rendering correctness is verified on real devices only; emulator / software GLES is not chased.
            assumeFalse(
                "emulator or software GLES (${caps.renderer}): render self-check not asserted",
                isEmulatorOrSoftwareGl(caps.renderer)
            )
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

    private fun isEmulatorOrSoftwareGl(renderer: String?): Boolean {
        val r = renderer.orEmpty().lowercase()
        val software = listOf("swiftshader", "android emulator", "llvmpipe", "softpipe", "lavapipe").any { it in r }
        val device = listOf(Build.FINGERPRINT, Build.HARDWARE, Build.PRODUCT).joinToString(" ").lowercase()
        return software || "goldfish" in device || "ranchu" in device || "generic" in Build.FINGERPRINT.lowercase()
    }

    companion object {
        private const val LUT = "luts/redline_standard_preview_33.cube"
    }
}
