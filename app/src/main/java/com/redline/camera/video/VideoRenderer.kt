package com.redline.camera.video

import android.annotation.SuppressLint
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Half
import android.util.Log
import android.view.Surface
import com.redline.camera.capability.Dim
import com.redline.camera.look.CubeLut
import com.redline.camera.look.LookShaders
import com.redline.camera.look.LutMath
import com.redline.camera.look.LutTimingStats
import com.redline.camera.look.TimingSnapshot
import com.redline.camera.viewfinder.FrameStats
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CompletableFuture
import kotlin.math.cos
import kotlin.math.sin

/**
 * B40 GPU video chain on its own EGL14 / OpenGL ES 3 thread. The camera writes one PRIVATE stream into a SurfaceTexture
 * this class owns ([attach]); each frame runs pass A (OES -> RGBA16F intermediate, upright for the phone's natural
 * orientation, full stream resolution: inverse ISP curve + colour matrix) and pass B (intermediate -> encoder surface,
 * rotated by [VideoOrientation.contentRotation]: gain, Standard tone, the stills' LUT), then pass B again at screen size into
 * the viewfinder's TextureView. Passes A + B (encoder) are the chain timed for the 12 ms gate, with GPU timer queries
 * (GL_EXT_disjoint_timer_query) or, without them, a fence-timed bracket every 15th frame.
 *
 * [prepare] compiles everything and runs a GPU-vs-CPU self-check ([VideoChain]); a GPU that fails it records with the look
 * off (inverse curve + sRGB encode only), reported, so video never depends on a look the GPU got wrong.
 */
class VideoRenderer(private val listener: Listener) {
    interface Listener {
        /** About once a second while attached. */
        fun onStats(stats: Stats)

        fun onRuntimeError(message: String)
    }

    data class Caps(
        val ok: Boolean,
        val reason: String?,
        val renderer: String? = null,
        val intermediate: String? = null,
        val lutFormat: String? = null,
        val selfCheckMaxErrorSteps: Int? = null,
        val lookApplied: Boolean = false,
        val lookNote: String? = null,
        val timerMethod: String? = null
    )

    data class Stats(val chain: TimingSnapshot, val timerMethod: String?, val cameraFrames: Int, val dropped: Int, val encoderFrames: Int)

    private class Prog(val id: Int) {
        fun u(name: String) = GLES30.glGetUniformLocation(id, name)
        val uTexMatrix = u("uTexMatrix")
        val uInput = u("uInput")
    }

    private val thread = HandlerThread("redline-video-gl").apply { start() }
    private val handler = Handler(thread.looper)

    init {
        handler.post { runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) } }
    }

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encoderSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var decodeOes: Prog? = null
    private var decode2d: Prog? = null
    private var look2d: Prog? = null
    private var fusedOes: Prog? = null
    private var fused2d: Prog? = null
    private var chain: VideoChain? = null
    private var lut: CubeLut? = null
    private var lutTex = 0
    private var invTex = 0
    private var toneTex = 0
    private var oesTex = 0
    private var midTex = 0
    private var midFbo = 0
    private var useIntermediate = false
    private var lookOn = 1f
    private var cameraTexture: SurfaceTexture? = null
    private var streamSize = Dim(0, 0)
    private var uprightSize = Dim(0, 0)
    private var displaySize = Dim(0, 0)
    private var encoderSize = Dim(0, 0)
    private var ready = false
    private var failed = false
    private var released = false
    private val texMatrix = FloatArray(16)
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val quadPos = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val quadTex = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    private var encoderPos = quadPos

    // Timing.
    private var gpuTimer = false
    private var timerMethod: String? = null
    private val queries = IntArray(QUERY_RING)
    private val pending = BooleanArray(QUERY_RING)
    private var timerChecked = false
    private var gpuSamples = 0
    private var encodedSinceAttach = 0L
    private val timing = LutTimingStats()
    private var frameStats = FrameStats(30)
    private var cameraFrames = 0
    private var encoderFrames = 0
    private var lastStatsMs = 0L

    /** GL thread: context, programs, tables, self-check. */
    fun prepare(
        lut: CubeLut,
        plan: TonemapPlan,
        tone: DoubleArray,
        gainEv: Double,
        matrix: FloatArray = VideoChain.IDENTITY
    ): CompletableFuture<Caps> {
        val f = CompletableFuture<Caps>()
        handler.post {
            val caps = try {
                prepareOnThread(lut, plan, tone, gainEv, matrix)
            } catch (t: Throwable) {
                Log.w(TAG, "video renderer prepare failed", t)
                Caps(ok = false, reason = "GL init: ${t.javaClass.simpleName}: ${t.message}")
            }
            if (!caps.ok) teardownGl()
            ready = caps.ok
            f.complete(caps)
        }
        return f
    }

    /**
     * Starts drawing into [displayTexture] (the viewfinder's TextureView) at [displayOut] (same aspect as [stream]) and
     * returns the SurfaceTexture the camera should write to at [stream]. Call only while no other producer is connected.
     */
    fun attach(
        displayTexture: SurfaceTexture,
        stream: Dim,
        sensorOrientation: Int,
        displayOut: Dim,
        fps: Int
    ): CompletableFuture<SurfaceTexture> {
        val f = CompletableFuture<SurfaceTexture>()
        handler.post {
            if (!ready || failed || released) {
                f.completeExceptionally(IllegalStateException(if (failed) "renderer failed earlier" else "renderer not ready"))
                return@post
            }
            try {
                detachOnThread()
                displayTexture.setDefaultBufferSize(displayOut.width, displayOut.height)
                val s: EGLSurface? = EGL14.eglCreateWindowSurface(display, config, displayTexture, intArrayOf(EGL14.EGL_NONE), 0)
                check(s != null && s != EGL14.EGL_NO_SURFACE) { "display surface: ${eglError()}" }
                window = s
                makeCurrent(pbuffer)
                streamSize = stream
                uprightSize = VideoSizes.upright(stream, sensorOrientation)
                displaySize = displayOut
                if (useIntermediate) allocateIntermediate(uprightSize)
                oesTex = newTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_LINEAR)
                val cam = SurfaceTexture(oesTex)
                cam.setDefaultBufferSize(stream.width, stream.height)
                cam.setOnFrameAvailableListener({ drawFrame() }, handler)
                cameraTexture = cam
                timing.reset()
                frameStats = FrameStats(fps)
                cameraFrames = 0
                encoderFrames = 0
                encodedSinceAttach = 0
                gpuSamples = 0
                pending.fill(false)
                f.complete(cam)
            } catch (t: Throwable) {
                Log.w(TAG, "video attach failed", t)
                detachOnThread()
                f.completeExceptionally(t)
            }
        }
        return f
    }

    /** Starts (non-null [surface]) or stops sending frames to the encoder's input surface. */
    fun setEncoder(surface: Surface?, orientation: VideoOrientation, size: Dim): CompletableFuture<Unit> {
        val f = CompletableFuture<Unit>()
        handler.post {
            try {
                dropEncoderSurface()
                if (surface != null && !failed) {
                    val s: EGLSurface? = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
                    check(s != null && s != EGL14.EGL_NO_SURFACE) { "encoder surface: ${eglError()}" }
                    encoderSurface = s
                    encoderSize = size
                    encoderPos = rotated(orientation.contentRotation)
                    frameStats.breakRun()
                }
                f.complete(Unit)
            } catch (t: Throwable) {
                Log.w(TAG, "encoder surface failed", t)
                f.completeExceptionally(t)
            }
        }
        return f
    }

    fun detach(): CompletableFuture<Unit> {
        val f = CompletableFuture<Unit>()
        if (!handler.post {
                runCatching { detachOnThread() }
                f.complete(Unit)
            }
        ) {
            f.complete(Unit)
        }
        return f
    }

    fun release() {
        handler.post {
            released = true
            runCatching { detachOnThread() }
            teardownGl()
            thread.quitSafely()
        }
    }

    // ------------------------------------------------------------------------------------------------- GL thread

    private fun prepareOnThread(l: CubeLut, plan: TonemapPlan, tone: DoubleArray, gainEv: Double, matrix: FloatArray): Caps {
        initEgl()?.let { return Caps(ok = false, reason = it) }
        val rendererName = GLES30.glGetString(GLES30.GL_RENDERER)
        val ext = GLES30.glGetString(GLES30.GL_EXTENSIONS).orEmpty().split(' ').filter { it.isNotEmpty() }.toSet()
        val major = IntArray(1).also { GLES30.glGetIntegerv(GLES30.GL_MAJOR_VERSION, it, 0) }[0]
        if (major < 3) return Caps(ok = false, reason = "OpenGL ES 3.0+ required", renderer = rendererName)
        if ("GL_OES_EGL_image_external_essl3" !in ext) {
            return Caps(ok = false, reason = "GL_OES_EGL_image_external_essl3 missing", renderer = rendererName)
        }
        decodeOes = build(VideoShaders.DECODE_OES)
        decode2d = build(VideoShaders.DECODE_2D)
        look2d = build(VideoShaders.LOOK_2D)
        fusedOes = build(VideoShaders.FUSED_OES)
        fused2d = build(VideoShaders.FUSED_2D)
        if (fusedOes == null ||
            fused2d == null
        ) {
            return Caps(ok = false, reason = "video shaders did not compile/link", renderer = rendererName)
        }
        val c = VideoChain(plan.inverse, matrix, Math.pow(2.0, gainEv), tone, l)
        chain = c
        lut = l
        invTex = upload1d(plan.inverse) ?: return Caps(ok = false, reason = "inverse curve table upload failed", renderer = rendererName)
        toneTex = upload1d(c.toneTable) ?: return Caps(ok = false, reason = "tone table upload failed", renderer = rendererName)
        val lutFormat = uploadLut(l) ?: return Caps(ok = false, reason = "3D LUT upload failed", renderer = rendererName)
        // RGBA16F colour attachments: core in ES 3.2, else EXT_color_buffer_half_float / EXT_color_buffer_float; probed below.
        useIntermediate = decodeOes != null && decode2d != null && look2d != null && probeHalfFloatTarget()
        val intermediate = if (useIntermediate) "RGBA16F (two passes)" else "none: RGBA16F not renderable, fused single pass"
        var err = selfCheck(c)
        var note: String
        if (err == null && useIntermediate) {
            // The two-pass path could not render; try fused before giving up on the look.
            useIntermediate = false
            err = selfCheck(c)
        }
        lookOn = 1f
        if (err != null && err <= SELF_CHECK_TOLERANCE) {
            note = "self-check max $err/255"
        } else {
            lookOn = 0f
            note =
                if (err ==
                    null
                ) {
                    "self-check could not render; recording without the look"
                } else {
                    "self-check $err/255 > $SELF_CHECK_TOLERANCE; recording without the look"
                }
        }
        gpuTimer = "GL_EXT_disjoint_timer_query" in ext
        if (gpuTimer) GLES30.glGenQueries(QUERY_RING, queries, 0)
        timerMethod = if (gpuTimer) TIMER_GPU else TIMER_CPU
        if (!plan.exact) note += "; ${plan.label}: approximate inverse"
        return Caps(
            ok = true,
            reason = null,
            renderer = rendererName,
            intermediate = if (useIntermediate) intermediate else "none: fused single pass",
            lutFormat = lutFormat,
            selfCheckMaxErrorSteps = err,
            lookApplied = lookOn > 0f,
            lookNote = note,
            timerMethod = timerMethod
        )
    }

    private fun initEgl(): String? {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return "no EGL display"
        val v = IntArray(2)
        if (!EGL14.eglInitialize(display, v, 0, v, 1)) return "eglInitialize failed: ${eglError()}"
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) || count[0] == 0 || configs[0] == null) {
            return "no recordable EGL config for OpenGL ES 3"
        }
        config = configs[0]
        val ctx: EGLContext? = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
            0
        )
        if (ctx == null || ctx == EGL14.EGL_NO_CONTEXT) return "OpenGL ES 3 context not created: ${eglError()}"
        context = ctx
        val pb: EGLSurface? = EGL14.eglCreatePbufferSurface(
            display,
            config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
            0
        )
        if (pb == null || pb == EGL14.EGL_NO_SURFACE) return "pbuffer not created: ${eglError()}"
        pbuffer = pb
        if (!EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) return "eglMakeCurrent failed: ${eglError()}"
        return null
    }

    private fun build(fragment: String): Prog? {
        val vs = compile(GLES30.GL_VERTEX_SHADER, LookShaders.VERTEX) ?: return null
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragment) ?: return null
        val id = GLES30.glCreateProgram()
        GLES30.glAttachShader(id, vs)
        GLES30.glAttachShader(id, fs)
        GLES30.glLinkProgram(id)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        val st = IntArray(1)
        GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, st, 0)
        if (st[0] == 0) {
            Log.w(TAG, "link failed: ${GLES30.glGetProgramInfoLog(id)}")
            GLES30.glDeleteProgram(id)
            return null
        }
        return Prog(id)
    }

    private fun compile(type: Int, src: String): Int? {
        val id = GLES30.glCreateShader(type)
        GLES30.glShaderSource(id, src)
        GLES30.glCompileShader(id)
        val st = IntArray(1)
        GLES30.glGetShaderiv(id, GLES30.GL_COMPILE_STATUS, st, 0)
        if (st[0] == 0) {
            Log.w(TAG, "compile failed: ${GLES30.glGetShaderInfoLog(id)}")
            GLES30.glDeleteShader(id)
            return null
        }
        return id
    }

    /** A 1D table as an R16F width x 1 texture (linear filtering of 16-bit float is core in ES 3.0). */
    @SuppressLint("HalfFloat")
    private fun upload1d(values: FloatArray): Int? {
        drainGlErrors()
        val t = newTexture(GLES30.GL_TEXTURE_2D, GLES30.GL_LINEAR)
        val half = ByteBuffer.allocateDirect(values.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        for (v in values) half.put(Half.toHalf(v))
        half.position(0)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R16F, values.size, 1, 0, GLES30.GL_RED, GLES30.GL_HALF_FLOAT, half)
        if (GLES30.glGetError() != GLES30.GL_NO_ERROR) {
            GLES30.glDeleteTextures(1, intArrayOf(t), 0)
            return null
        }
        return t
    }

    @SuppressLint("HalfFloat")
    private fun uploadLut(l: CubeLut): String? {
        drainGlErrors()
        lutTex = newTexture(GLES30.GL_TEXTURE_3D, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        val n = l.size
        val count = n * n * n
        val half = ByteBuffer.allocateDirect(count * 8).order(ByteOrder.nativeOrder()).asShortBuffer()
        for (i in 0 until count) {
            for (c in 0..2) half.put(Half.toHalf(l.data[i * 3 + c]))
            half.put(HALF_ONE)
        }
        half.position(0)
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGBA16F, n, n, n, 0, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, half)
        if (GLES30.glGetError() == GLES30.GL_NO_ERROR) return "RGBA16F"
        val bytes = ByteBuffer.allocateDirect(count * 4).put(LutMath.toRgba8(l))
        bytes.position(0)
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGBA8, n, n, n, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bytes)
        return if (GLES30.glGetError() == GLES30.GL_NO_ERROR) "RGBA8" else null
    }

    private fun probeHalfFloatTarget(): Boolean {
        val ok = allocateIntermediate(Dim(4, 4))
        freeIntermediate()
        return ok
    }

    private fun allocateIntermediate(size: Dim): Boolean {
        freeIntermediate()
        drainGlErrors()
        midTex = newTexture(GLES30.GL_TEXTURE_2D, GLES30.GL_LINEAR)
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA16F, size.width, size.height)
        val fbo = IntArray(1).also { GLES30.glGenFramebuffers(1, it, 0) }
        midFbo = fbo[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, midFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, midTex, 0)
        val complete = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return complete && GLES30.glGetError() == GLES30.GL_NO_ERROR
    }

    private fun freeIntermediate() {
        if (midFbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(midFbo), 0)
        if (midTex != 0) GLES30.glDeleteTextures(1, intArrayOf(midTex), 0)
        midFbo = 0
        midTex = 0
    }

    /** Renders [LutMath.SELF_CHECK_COLOURS] through the path in use and returns the worst error in 8-bit steps, or null. */
    private fun selfCheck(c: VideoChain): Int? {
        val colours = LutMath.SELF_CHECK_COLOURS
        val w = colours.size / 3
        val input = ByteBuffer.allocateDirect(w * 4)
        for (i in 0 until w) {
            for (k in 0..2) input.put(colours[i * 3 + k].toByte())
            input.put(0xFF.toByte())
        }
        input.position(0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        val inTex = newTexture(GLES30.GL_TEXTURE_2D, GLES30.GL_NEAREST)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, 1, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, input)
        val outTex = newTexture(GLES30.GL_TEXTURE_2D, GLES30.GL_NEAREST)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, 1, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        val fbo = IntArray(1).also { GLES30.glGenFramebuffers(1, it, 0) }
        try {
            if (useIntermediate) {
                if (!allocateIntermediate(Dim(w, 1))) return null
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, midFbo)
                GLES30.glViewport(0, 0, w, 1)
                drawDecode(decode2d ?: return null, GLES30.GL_TEXTURE_2D, inTex, identity)
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, outTex, 0)
            if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) return null
            GLES30.glViewport(0, 0, w, 1)
            lookOn = 1f
            if (useIntermediate) {
                drawLook(look2d ?: return null, midTex, quadPos)
            } else {
                drawFused(fused2d ?: return null, GLES30.GL_TEXTURE_2D, inTex, identity, quadPos)
            }
            val px = ByteBuffer.allocateDirect(w * 4).order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(0, 0, w, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, px)
            if (GLES30.glGetError() != GLES30.GL_NO_ERROR) return null
            val bytes = ByteArray(w * 4)
            px.position(0)
            px.get(bytes)
            return c.maxErrorSteps(colours, bytes)
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glDeleteFramebuffers(1, fbo, 0)
            GLES30.glDeleteTextures(2, intArrayOf(inTex, outTex), 0)
            if (useIntermediate) freeIntermediate()
        }
    }

    private fun bindDecode(p: Prog) {
        val c = chain ?: return
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, invTex)
        GLES30.glUniform1i(p.u("uInv"), 2)
        val n = c.inverse.size
        GLES30.glUniform1f(p.u("uInvScale"), (n - 1f) / n)
        GLES30.glUniform1f(p.u("uInvOffset"), 0.5f / n)
        GLES30.glUniformMatrix3fv(p.u("uMatrix"), 1, true, c.matrix, 0)
    }

    private fun bindLook(p: Prog) {
        val c = chain ?: return
        val l = lut ?: return
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
        GLES30.glUniform1i(p.u("uLut"), 1)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, toneTex)
        GLES30.glUniform1i(p.u("uTone"), 3)
        val n = c.toneTable.size
        GLES30.glUniform1f(p.u("uToneScale"), (n - 1f) / n)
        GLES30.glUniform1f(p.u("uToneOffset"), 0.5f / n)
        GLES30.glUniform1f(p.u("uGain"), c.gain.toFloat())
        GLES30.glUniform1f(p.u("uFloor"), c.floorLinear.toFloat())
        GLES30.glUniform1f(p.u("uTone0"), c.toneTable[0])
        GLES30.glUniform1f(p.u("uEvMin"), com.redline.camera.colour.ColourMath.EV_MIN.toFloat())
        GLES30.glUniform1f(p.u("uEvSpanInv"), (1.0 / (c.clipEv - com.redline.camera.colour.ColourMath.EV_MIN)).toFloat())
        GLES30.glUniform3f(p.u("uDomainMin"), l.domainMin[0], l.domainMin[1], l.domainMin[2])
        GLES30.glUniform3f(
            p.u("uDomainScale"),
            1f / (l.domainMax[0] - l.domainMin[0]),
            1f / (l.domainMax[1] - l.domainMin[1]),
            1f / (l.domainMax[2] - l.domainMin[2])
        )
        GLES30.glUniform1f(p.u("uLutScale"), LutMath.coordScale(l.size))
        GLES30.glUniform1f(p.u("uLutOffset"), LutMath.coordOffset(l.size))
        GLES30.glUniform1f(p.u("uLook"), lookOn)
    }

    private fun bindInput(p: Prog, target: Int, tex: Int, matrix: FloatArray) {
        GLES30.glUseProgram(p.id)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(target, tex)
        GLES30.glUniform1i(p.uInput, 0)
        GLES30.glUniformMatrix4fv(p.uTexMatrix, 1, false, matrix, 0)
    }

    private fun quad(pos: FloatBuffer) {
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, pos)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 0, quadTex)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawDecode(p: Prog, target: Int, tex: Int, matrix: FloatArray) {
        bindInput(p, target, tex, matrix)
        bindDecode(p)
        quad(quadPos)
    }

    private fun drawLook(p: Prog, tex: Int, pos: FloatBuffer) {
        bindInput(p, GLES30.GL_TEXTURE_2D, tex, identity)
        bindLook(p)
        quad(pos)
    }

    private fun drawFused(p: Prog, target: Int, tex: Int, matrix: FloatArray, pos: FloatBuffer) {
        bindInput(p, target, tex, matrix)
        bindDecode(p)
        bindLook(p)
        quad(pos)
    }

    private fun drawFrame() {
        val cam = cameraTexture ?: return
        if (failed || window == EGL14.EGL_NO_SURFACE) return
        try {
            makeCurrent(pbuffer)
            cam.updateTexImage()
            cam.getTransformMatrix(texMatrix)
            val ts = cam.timestamp
            cameraFrames++
            val recording = encoderSurface != EGL14.EGL_NO_SURFACE
            if (recording) frameStats.add(ts)
            // The chain (timed): pass A into the float intermediate, pass B into the encoder.
            timedChain(recording, ts)
            // The viewfinder: pass B at screen size (not part of the chain gate).
            makeCurrent(window)
            GLES30.glViewport(0, 0, displaySize.width, displaySize.height)
            if (useIntermediate) {
                drawLook(look2d!!, midTex, quadPos)
            } else {
                drawFused(fusedOes!!, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex, texMatrix, quadPos)
            }
            if (!EGL14.eglSwapBuffers(display, window)) throw IllegalStateException("display swap: ${eglError()}")
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastStatsMs >= STATS_PERIOD_MS) {
                lastStatsMs = now
                listener.onStats(Stats(timing.snapshot(), timerMethod, cameraFrames, frameStats.dropped, encoderFrames))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "video frame failed", t)
            failed = true
            cam.setOnFrameAvailableListener(null)
            listener.onRuntimeError("${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun timedChain(recording: Boolean, ts: Long) {
        if (!recording) {
            // Pass A only (the viewfinder reads the intermediate); nothing timed while not recording.
            if (useIntermediate) passA()
            return
        }
        encodedSinceAttach++
        if (gpuTimer) {
            collectQueries()
            val slot = (encodedSinceAttach % QUERY_RING).toInt()
            val timed = !pending[slot]
            if (timed) {
                if (!timerChecked) drainGlErrors()
                GLES30.glBeginQuery(GL_TIME_ELAPSED_EXT, queries[slot])
            }
            passA()
            passBEncoder(ts, timedQuery = timed)
            if (timed) {
                if (!timerChecked) {
                    timerChecked = true
                    if (GLES30.glGetError() != GLES30.GL_NO_ERROR) {
                        gpuTimer = false
                        timerMethod = TIMER_CPU
                    }
                }
                if (gpuTimer) pending[slot] = true
            }
        } else if (encodedSinceAttach % CPU_SAMPLE_EVERY == 0L) {
            waitFence()
            val t0 = System.nanoTime()
            passA()
            passBEncoder(ts, timedQuery = false, fence = true)
            timing.add((lastFenceNs - t0) / 1e6)
        } else {
            passA()
            passBEncoder(ts, timedQuery = false)
        }
    }

    private var lastFenceNs = 0L

    private fun passA() {
        if (!useIntermediate) return
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, midFbo)
        GLES30.glViewport(0, 0, uprightSize.width, uprightSize.height)
        drawDecode(decodeOes!!, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex, texMatrix)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun passBEncoder(ts: Long, timedQuery: Boolean, fence: Boolean = false) {
        makeCurrent(encoderSurface)
        GLES30.glViewport(0, 0, encoderSize.width, encoderSize.height)
        if (useIntermediate) {
            drawLook(look2d!!, midTex, encoderPos)
        } else {
            drawFused(fusedOes!!, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex, texMatrix, encoderPos)
        }
        if (timedQuery) GLES30.glEndQuery(GL_TIME_ELAPSED_EXT)
        if (fence) {
            waitFence()
            lastFenceNs = System.nanoTime()
        }
        EGLExt.eglPresentationTimeANDROID(display, encoderSurface, ts)
        if (!EGL14.eglSwapBuffers(display, encoderSurface)) throw IllegalStateException("encoder swap: ${eglError()}")
        encoderFrames++
    }

    private fun waitFence(): Boolean {
        val sync = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        if (sync == 0L) return false
        val r = GLES30.glClientWaitSync(sync, GLES30.GL_SYNC_FLUSH_COMMANDS_BIT, FENCE_TIMEOUT_NS)
        GLES30.glDeleteSync(sync)
        return r == GLES30.GL_ALREADY_SIGNALED || r == GLES30.GL_CONDITION_SATISFIED
    }

    private fun collectQueries() {
        val disjoint = IntArray(1).also { GLES30.glGetIntegerv(GL_GPU_DISJOINT_EXT, it, 0) }[0] != 0
        val v = IntArray(1)
        for (i in 0 until QUERY_RING) {
            if (!pending[i]) continue
            GLES30.glGetQueryObjectuiv(queries[i], GLES30.GL_QUERY_RESULT_AVAILABLE, v, 0)
            if (v[0] == 0) continue
            GLES30.glGetQueryObjectuiv(queries[i], GLES30.GL_QUERY_RESULT, v, 0)
            pending[i] = false
            if (!disjoint) {
                timing.add((v[0].toLong() and 0xFFFFFFFFL) / 1e6)
                gpuSamples++
            }
        }
        // As in the look preview (B20a): a timer that never yields a usable sample falls back to fence timing.
        if (gpuSamples == 0 && encodedSinceAttach >= GPU_GRACE_FRAMES) {
            gpuTimer = false
            timerMethod = "$TIMER_CPU (GPU timer gave no usable sample in $GPU_GRACE_FRAMES frames)"
            pending.fill(false)
            timing.reset()
        }
    }

    private fun rotated(clockwiseDeg: Int): FloatBuffer {
        // Rotating the vertices counter-clockwise by -a turns the picture clockwise by a in the encoded frame (GL y up).
        val a = Math.toRadians(-clockwiseDeg.toDouble())
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        val p = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val out = FloatArray(8)
        for (i in 0 until 4) {
            val x = p[2 * i]
            val y = p[2 * i + 1]
            out[2 * i] = Math.round(x * c - y * s).toFloat()
            out[2 * i + 1] = Math.round(x * s + y * c).toFloat()
        }
        return floats(*out)
    }

    private fun dropEncoderSurface() {
        if (encoderSurface != EGL14.EGL_NO_SURFACE) {
            makeCurrent(pbuffer)
            EGL14.eglDestroySurface(display, encoderSurface)
            encoderSurface = EGL14.EGL_NO_SURFACE
        }
    }

    private fun detachOnThread() {
        cameraTexture?.let {
            it.setOnFrameAvailableListener(null)
            it.release()
        }
        cameraTexture = null
        if (display == EGL14.EGL_NO_DISPLAY) return
        dropEncoderSurface()
        if (pbuffer != EGL14.EGL_NO_SURFACE) makeCurrent(pbuffer)
        if (oesTex != 0) GLES30.glDeleteTextures(1, intArrayOf(oesTex), 0)
        oesTex = 0
        freeIntermediate()
        if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
        window = EGL14.EGL_NO_SURFACE
        pending.fill(false)
    }

    private fun teardownGl() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        runCatching {
            if (pbuffer != EGL14.EGL_NO_SURFACE) makeCurrent(pbuffer)
            listOfNotNull(decodeOes, decode2d, look2d, fusedOes, fused2d).forEach { GLES30.glDeleteProgram(it.id) }
            GLES30.glDeleteTextures(3, intArrayOf(lutTex, invTex, toneTex), 0)
            if (gpuTimer) GLES30.glDeleteQueries(QUERY_RING, queries, 0)
        }
        decodeOes = null
        decode2d = null
        look2d = null
        fusedOes = null
        fused2d = null
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
        pbuffer = EGL14.EGL_NO_SURFACE
        context = EGL14.EGL_NO_CONTEXT
        display = EGL14.EGL_NO_DISPLAY
        ready = false
    }

    private fun makeCurrent(s: EGLSurface) {
        if (!EGL14.eglMakeCurrent(display, s, s, context)) throw IllegalStateException("eglMakeCurrent failed: ${eglError()}")
    }

    private fun newTexture(target: Int, filter: Int): Int {
        val t = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
        GLES30.glBindTexture(target, t)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, filter)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, filter)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return t
    }

    private fun drainGlErrors() {
        var guard = 0
        while (GLES30.glGetError() != GLES30.GL_NO_ERROR && guard < 16) guard++
    }

    private fun eglError(): String = "0x" + Integer.toHexString(EGL14.eglGetError())

    companion object {
        private const val TAG = "RedlineVideoGl"
        const val TIMER_GPU = "GL_EXT_disjoint_timer_query"
        const val TIMER_CPU = "cpu_gl_fence_sync (chain bracketed by fences, every 15th frame)"
        const val SELF_CHECK_TOLERANCE = 4
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val FENCE_TIMEOUT_NS = 100_000_000L
        private const val CPU_SAMPLE_EVERY = 15L
        private const val QUERY_RING = 4
        private const val GPU_GRACE_FRAMES = 90L
        private const val STATS_PERIOD_MS = 1_000L
        private const val HALF_ONE: Short = 0x3C00
        private const val GL_TIME_ELAPSED_EXT = 0x88BF
        private const val GL_GPU_DISJOINT_EXT = 0x8FBB

        private fun floats(vararg v: Float): FloatBuffer =
            ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(v).also { it.position(0) }
    }
}
