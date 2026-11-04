package com.redline.camera.depth

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.CompiledModel.GpuOptions.Precision
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * B30: the two on-device models on LiteRT (CompiledModel API): Depth Anything V2 Small (280x420 in, relative inverse
 * depth out) and the MediaPipe selfie segmenter (144x256 in, person probability out).
 *
 * The depth model is compiled on the GPU at fp16, then the GPU at fp32, then the CPU, and each is accepted only when it
 * reproduces the torch reference on the self-check scene ([DepthSelfCheck]): a GPU that compiles but computes garbage
 * (fp16 overflow in the attention, a driver bug) falls through to the next. The segmenter is tried on the GPU, then CPU.
 * Not thread-safe, and a GPU model belongs to the thread that compiled it: create, run and close on one thread
 * ([DepthService] does).
 */
class DepthEngine private constructor(
    private val env: Environment?,
    private val depth: Loaded?,
    private val seg: Loaded?,
    val info: DepthEngineInfo
) : Closeable {
    private class Loaded(val model: CompiledModel, val delegate: String) {
        val inputs: List<TensorBuffer> = model.createInputBuffers()
        val outputs: List<TensorBuffer> = model.createOutputBuffers()

        fun run(input: FloatArray): FloatArray {
            inputs[0].writeFloat(input)
            model.run(inputs, outputs)
            return outputs[0].readFloat()
        }

        fun close() {
            inputs.forEach { runCatching { it.close() } }
            outputs.forEach { runCatching { it.close() } }
            runCatching { model.close() }
        }
    }

    val ready: Boolean get() = depth != null && seg != null

    /** [input] = [W] x [H] x 3 floats (RGB 0..1); returns [W] x [H] relative inverse depth. */
    fun runDepth(input: FloatArray): FloatArray = checkNotNull(depth) { "depth model not loaded" }.run(input)

    /** [input] = [SEG_W] x [SEG_H] x 3 floats (RGB 0..1); returns [SEG_W] x [SEG_H] person probability. */
    fun runSegmenter(input: FloatArray): FloatArray = checkNotNull(seg) { "segmenter not loaded" }.run(input)

    override fun close() {
        depth?.close()
        seg?.close()
        runCatching { env?.close() }
    }

    companion object {
        const val W = 420
        const val H = 280
        const val SEG_W = 256
        const val SEG_H = 144
        const val DEPTH_ASSET = "depth/depth_anything_v2_small_280x420_fp16.tflite"
        const val SEG_ASSET = "depth/selfie_segmenter_landscape.tflite"
        const val SELFCHECK_IMAGE = "depth/selfcheck_420x280.png"
        const val SELFCHECK_EXPECTED = "depth/selfcheck_expected_105x70.f32"
        const val MODEL_NAME = "Depth Anything V2 Small (Apache-2.0), 280x420, fp16 weights"
        const val SEG_NAME = "MediaPipe selfie segmenter landscape (Apache-2.0), 144x256"

        const val GPU_FP16 = "gpu_fp16"
        const val GPU_FP32 = "gpu_fp32"
        const val CPU = "cpu"

        /** Compiles and self-checks the models. Never throws: [DepthEngine.ready] false means depth is off. */
        fun open(assets: AssetManager, cpuOnly: Boolean = false, nowMs: () -> Long = System::currentTimeMillis): DepthEngine {
            val t0 = nowMs()
            val attempts = ArrayList<DepthAttempt>()
            val env = try {
                Environment.create()
            } catch (e: Throwable) {
                return failed(attempts, t0, nowMs, "LiteRT environment: ${describe(e)}")
            }
            val (input, expected) = try {
                selfCheckInput(assets) to readFloats(assets, SELFCHECK_EXPECTED)
            } catch (e: Throwable) {
                runCatching { env.close() }
                return failed(attempts, t0, nowMs, "self-check assets: ${describe(e)}")
            }
            val order = if (cpuOnly) listOf(CPU) else listOf(GPU_FP16, GPU_FP32, CPU)
            var depth: Loaded? = null
            for (d in order) {
                val c0 = nowMs()
                val loaded = try {
                    Loaded(CompiledModel.create(assets, DEPTH_ASSET, options(d), env), d)
                } catch (e: Throwable) {
                    attempts += DepthAttempt("depth", d, passed = false, compileMs = nowMs() - c0, error = describe(e))
                    continue
                }
                val compileMs = nowMs() - c0
                val r0 = nowMs()
                val check = try {
                    DepthSelfCheck.compare(loaded.run(input), W, H, expected)
                } catch (e: Throwable) {
                    loaded.close()
                    attempts += DepthAttempt("depth", d, passed = false, compileMs = compileMs, checkMs = nowMs() - r0, error = describe(e))
                    continue
                }
                val ok = check.passed
                attempts += DepthAttempt(
                    "depth",
                    d,
                    passed = ok,
                    compileMs = compileMs,
                    checkMs = nowMs() - r0,
                    relMaxError = check.relMaxError,
                    correlation = check.correlation,
                    error = if (ok) null else "self-check off the reference"
                )
                if (ok) {
                    depth = loaded
                    break
                }
                loaded.close()
            }
            var seg: Loaded? = null
            for (d in if (cpuOnly) listOf(CPU) else listOf(GPU_FP16, CPU)) {
                val c0 = nowMs()
                try {
                    val l = Loaded(CompiledModel.create(assets, SEG_ASSET, options(d), env), d)
                    val out = l.run(FloatArray(SEG_W * SEG_H * 3) { 0.5f })
                    if (out.size != SEG_W * SEG_H || out.any { !it.isFinite() }) {
                        l.close()
                        attempts += DepthAttempt("segmenter", d, passed = false, compileMs = nowMs() - c0, error = "output not finite")
                        continue
                    }
                    attempts += DepthAttempt("segmenter", d, passed = true, compileMs = nowMs() - c0)
                    seg = l
                    break
                } catch (e: Throwable) {
                    attempts += DepthAttempt("segmenter", d, passed = false, compileMs = nowMs() - c0, error = describe(e))
                }
            }
            val err = when {
                depth == null -> "no accelerator passed the depth self-check"
                seg == null -> "segmenter did not load"
                else -> null
            }
            val info = DepthEngineInfo(
                model = MODEL_NAME,
                segmenter = SEG_NAME,
                delegate = depth?.delegate,
                segmenterDelegate = seg?.delegate,
                inputWidth = W,
                inputHeight = H,
                segWidth = SEG_W,
                segHeight = SEG_H,
                initMs = nowMs() - t0,
                attempts = attempts,
                error = err
            )
            return DepthEngine(env, depth, seg, info)
        }

        private fun failed(attempts: List<DepthAttempt>, t0: Long, nowMs: () -> Long, error: String) = DepthEngine(
            null,
            null,
            null,
            DepthEngineInfo(MODEL_NAME, SEG_NAME, null, null, W, H, SEG_W, SEG_H, nowMs() - t0, attempts, error)
        )

        private fun options(delegate: String): CompiledModel.Options = when (delegate) {
            CPU -> CompiledModel.Options(Accelerator.CPU)
            else -> CompiledModel.Options(Accelerator.GPU).apply {
                gpuOptions = CompiledModel.GpuOptions(
                    precision = if (delegate == GPU_FP16) Precision.FP16 else Precision.FP32
                )
            }
        }

        private fun describe(e: Throwable) = "${e.javaClass.simpleName}: ${e.message}"

        /** The self-check scene as the depth model's input (RGB 0..1, row-major). */
        fun selfCheckInput(assets: AssetManager): FloatArray {
            val opts = BitmapFactory.Options().apply {
                inScaled = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bmp = assets.open(SELFCHECK_IMAGE).use { BitmapFactory.decodeStream(it, null, opts) }
                ?: error("could not decode $SELFCHECK_IMAGE")
            try {
                require(bmp.width == W && bmp.height == H) { "self-check scene is ${bmp.width}x${bmp.height}" }
                val px = IntArray(W * H)
                bmp.getPixels(px, 0, W, 0, 0, W, H)
                return DepthSelfCheck.argbToRgbFloats(px)
            } finally {
                bmp.recycle()
            }
        }

        fun readFloats(assets: AssetManager, name: String): FloatArray {
            val bytes = assets.open(name).use { it.readBytes() }
            val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return FloatArray(fb.remaining()).also { fb.get(it) }
        }
    }
}

/** B30: the depth self-check maths (pure JVM): 4x4 block means of the model output against the torch reference. */
object DepthSelfCheck {
    const val BLOCK = 4
    const val MAX_REL_ERROR = 0.05
    const val MIN_CORRELATION = 0.998

    class Result(val relMaxError: Double, val correlation: Double) {
        val passed: Boolean get() = relMaxError <= MAX_REL_ERROR && correlation >= MIN_CORRELATION
    }

    fun argbToRgbFloats(px: IntArray): FloatArray {
        val out = FloatArray(px.size * 3)
        for (i in px.indices) {
            val c = px[i]
            out[3 * i] = ((c ushr 16) and 0xFF) / 255f
            out[3 * i + 1] = ((c ushr 8) and 0xFF) / 255f
            out[3 * i + 2] = (c and 0xFF) / 255f
        }
        return out
    }

    fun blockMeans(d: FloatArray, w: Int, h: Int): FloatArray {
        val bw = w / BLOCK
        val bh = h / BLOCK
        val out = FloatArray(bw * bh)
        for (by in 0 until bh) {
            for (bx in 0 until bw) {
                var s = 0.0
                for (y in by * BLOCK until (by + 1) * BLOCK) for (x in bx * BLOCK until (bx + 1) * BLOCK) s += d[y * w + x]
                out[by * bw + bx] = (s / (BLOCK * BLOCK)).toFloat()
            }
        }
        return out
    }

    fun compare(depth: FloatArray, w: Int, h: Int, expected: FloatArray): Result {
        require(depth.size >= w * h) { "depth output has ${depth.size} values, expected ${w * h}" }
        val b = blockMeans(depth, w, h)
        require(b.size == expected.size) { "reference has ${expected.size} values, expected ${b.size}" }
        var maxDiff = 0.0
        var maxRef = 1e-6
        var sa = 0.0
        var se = 0.0
        for (i in b.indices) {
            if (!b[i].isFinite()) return Result(Double.POSITIVE_INFINITY, 0.0)
            maxDiff = maxOf(maxDiff, kotlin.math.abs((b[i] - expected[i]).toDouble()))
            maxRef = maxOf(maxRef, kotlin.math.abs(expected[i].toDouble()))
            sa += b[i]
            se += expected[i]
        }
        val ma = sa / b.size
        val me = se / b.size
        var cov = 0.0
        var va = 0.0
        var ve = 0.0
        for (i in b.indices) {
            val da = b[i] - ma
            val de = expected[i] - me
            cov += da * de
            va += da * da
            ve += de * de
        }
        val corr = if (va > 0 && ve > 0) cov / kotlin.math.sqrt(va * ve) else 0.0
        return Result(maxDiff / maxRef, corr)
    }
}
