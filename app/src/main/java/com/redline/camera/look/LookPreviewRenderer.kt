package com.redline.camera.look

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
import android.os.SystemClock
import android.util.Half
import android.util.Log
import com.redline.camera.capability.Dim
import com.redline.camera.viewfinder.FrameStats
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CompletableFuture

/**
 * GPU look preview (B20). Owns an EGL14 / OpenGL ES 3 context on its own thread. The camera writes into a
 * SurfaceTexture this class creates ([attach]); every camera frame is drawn through the LUT program into the
 * viewfinder TextureView's own SurfaceTexture, at the camera buffer size and with the camera's texture
 * transform, so the TextureView's existing 3:2 crop / rotation transform applies unchanged.
 *
 * [prepare] runs once at start-up (before the camera opens): EGL + context, capability checks, LUT upload and a
 * GPU-vs-CPU self-check. Nothing is ever applied that did not pass those checks. Listener calls arrive on the GL
 * thread.
 */
class LookPreviewRenderer(private val listener: Listener) {
    interface Listener {
        fun onPrepared(caps: Caps)

        /** About once a second while frames are drawn. */
        fun onStats(stats: Stats)

        /** Drawing failed after a successful start; the renderer stops drawing. Fall back to the plain preview. */
        fun onRuntimeError(message: String)
    }

    data class Caps(
        val ok: Boolean,
        val reason: String?,
        val glVersion: String? = null,
        val renderer: String? = null,
        val es3: Boolean = false,
        val externalEssl3: Boolean = false,
        val floatLinear: Boolean = false,
        val halfFloatColorBuffer: Boolean = false,
        val lutSize: Int? = null,
        val lutFormat: String? = null,
        val timerMethod: String? = null,
        val selfCheckMaxErrorSteps: Int? = null
    )

    data class Stats(val timing: TimingSnapshot, val timerMethod: String?, val displayFrames: Int, val displayDropped: Int)

    private class Program(val id: Int) {
        val uTexMatrix = GLES30.glGetUniformLocation(id, "uTexMatrix")
        val uInput = GLES30.glGetUniformLocation(id, "uInput")
        val uLut = GLES30.glGetUniformLocation(id, "uLut")
        val uStrength = GLES30.glGetUniformLocation(id, "uStrength")
        val uDomainMin = GLES30.glGetUniformLocation(id, "uDomainMin")
        val uDomainScale = GLES30.glGetUniformLocation(id, "uDomainScale")
        val uLutScale = GLES30.glGetUniformLocation(id, "uLutScale")
        val uLutOffset = GLES30.glGetUniformLocation(id, "uLutOffset")
    }

    private val thread = HandlerThread("redline-gl").apply { start() }
    private val handler = Handler(thread.looper)

    // GL-thread state.
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var programOes: Program? = null
    private var program2d: Program? = null
    private var lut: CubeLut? = null
    private var lutTex = 0
    private var oesTex = 0
    private var cameraTexture: SurfaceTexture? = null
    private var bufferSize = Dim(0, 0)
    private var ready = false
    private var failed = false
    private var released = false

    @Volatile
    private var strength = 1f
    private val texMatrix = FloatArray(16)
    private val quadPos = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val quadTex = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

    // Timing.
    private var gpuTimer = false
    private var timerMethod: String? = null
    private val queries = IntArray(QUERY_RING)
    private val queryPending = BooleanArray(QUERY_RING)
    private var timerChecked = false
    private var frameIndex = 0L
    private val timing = LutTimingStats()
    private var displayStats = FrameStats(DEFAULT_FPS)
    private var attachedAtMs = 0L
    private var lastStatsMs = 0L

    /** Start-up checks and LUT upload; [loadLut] runs on the GL thread (native parse of the bundled asset). */
    fun prepare(loadLut: () -> CubeLut) {
        handler.post {
            val caps = try {
                prepareOnThread(loadLut)
            } catch (t: Throwable) {
                Log.w(TAG, "look preview prepare failed", t)
                Caps(ok = false, reason = "GL init: ${t.javaClass.simpleName}: ${t.message}")
            }
            if (!caps.ok) teardownGl()
            ready = caps.ok
            listener.onPrepared(caps)
        }
    }

    /**
     * Draws camera frames into [displayTexture] (the TextureView's SurfaceTexture) at [size]. [done] gets the
     * SurfaceTexture the camera should write to, or an error. Call only while no other producer (the camera) is
     * connected to [displayTexture].
     */
    fun attach(displayTexture: SurfaceTexture, size: Dim, expectedFps: Int, done: (SurfaceTexture?, String?) -> Unit) {
        handler.post {
            if (!ready || failed || released) {
                done(null, if (failed) "renderer failed earlier" else "renderer not ready")
                return@post
            }
            try {
                detachOnThread()
                displayTexture.setDefaultBufferSize(size.width, size.height)
                val surface: EGLSurface? = EGL14.eglCreateWindowSurface(display, config, displayTexture, intArrayOf(EGL14.EGL_NONE), 0)
                check(surface != null && surface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed: ${eglError()}" }
                window = surface
                makeCurrent(window)
                oesTex = newTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_LINEAR)
                val cam = SurfaceTexture(oesTex)
                cam.setDefaultBufferSize(size.width, size.height)
                cam.setOnFrameAvailableListener({ drawFrame() }, handler)
                cameraTexture = cam
                bufferSize = size
                timing.reset()
                displayStats = FrameStats(expectedFps)
                attachedAtMs = SystemClock.elapsedRealtime()
                lastStatsMs = attachedAtMs
                done(cam, null)
            } catch (t: Throwable) {
                Log.w(TAG, "look preview attach failed", t)
                detachOnThread()
                done(null, "${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    /** Stops drawing and disconnects from the display texture. Call only after the camera stopped writing. */
    fun detach(): CompletableFuture<Unit> {
        val f = CompletableFuture<Unit>()
        val posted = handler.post {
            runCatching { detachOnThread() }
            f.complete(Unit)
        }
        if (!posted) f.complete(Unit)
        return f
    }

    fun setStrength(value: Float) {
        strength = value.coerceIn(0f, 1f)
    }

    fun release() {
        handler.post {
            released = true
            runCatching { detachOnThread() }
            teardownGl()
            thread.quitSafely()
        }
    }

    private fun prepareOnThread(loadLut: () -> CubeLut): Caps {
        initEgl()?.let { return Caps(ok = false, reason = it) }
        val version = GLES30.glGetString(GLES30.GL_VERSION)
        val renderer = GLES30.glGetString(GLES30.GL_RENDERER)
        val ext = GLES30.glGetString(GLES30.GL_EXTENSIONS).orEmpty().split(' ').filter { it.isNotEmpty() }.toSet()
        val major = IntArray(1).also { GLES30.glGetIntegerv(GLES30.GL_MAJOR_VERSION, it, 0) }[0]
        val es3 = major >= 3 && GLES30.glGetError() == GLES30.GL_NO_ERROR
        val base = Caps(
            ok = false,
            reason = null,
            glVersion = version,
            renderer = renderer,
            es3 = es3,
            externalEssl3 = "GL_OES_EGL_image_external_essl3" in ext,
            floatLinear = "GL_OES_texture_float_linear" in ext,
            halfFloatColorBuffer = "GL_EXT_color_buffer_half_float" in ext
        )
        if (!es3) return base.copy(reason = "OpenGL ES 3.0+ required (context reports $version)")
        if (!base.externalEssl3) return base.copy(reason = "GL_OES_EGL_image_external_essl3 missing (camera texture in GLSL ES 3.00)")
        val loaded = try {
            loadLut()
        } catch (t: Throwable) {
            return base.copy(reason = "LUT not loaded: ${t.message ?: t.javaClass.simpleName}")
        }
        lut = loaded
        programOes = buildProgram(LookShaders.FRAGMENT_OES) ?: return base.copy(reason = "look shader (OES) did not compile/link")
        program2d = buildProgram(LookShaders.FRAGMENT_2D) ?: return base.copy(reason = "look shader (2D) did not compile/link")
        val (format, check, tried) = uploadAndCheckLut(loaded)
        val withLut = base.copy(lutSize = loaded.size, lutFormat = format, selfCheckMaxErrorSteps = check?.maxErr)
        if (format == null || check == null) {
            return withLut.copy(reason = "GPU self-check failed for every 3D LUT format, LUT not applied: $tried")
        }
        gpuTimer = "GL_EXT_disjoint_timer_query" in ext
        if (gpuTimer) GLES30.glGenQueries(QUERY_RING, queries, 0)
        timerMethod = if (gpuTimer) TIMER_GPU else TIMER_CPU
        return withLut.copy(ok = true, timerMethod = timerMethod)
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
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) || count[0] == 0 || configs[0] == null) {
            return "no EGL config for OpenGL ES 3 (window + pbuffer, RGBA8888)"
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
        val pb: EGLSurface? =
            EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        if (pb == null || pb == EGL14.EGL_NO_SURFACE) return "pbuffer not created: ${eglError()}"
        pbuffer = pb
        if (!EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) return "eglMakeCurrent failed: ${eglError()}"
        return null
    }

    private fun buildProgram(fragment: String): Program? {
        val vs = compile(GLES30.GL_VERTEX_SHADER, LookShaders.VERTEX) ?: return null
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragment) ?: return null
        val id = GLES30.glCreateProgram()
        GLES30.glAttachShader(id, vs)
        GLES30.glAttachShader(id, fs)
        GLES30.glLinkProgram(id)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        val status = IntArray(1)
        GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.w(TAG, "link failed: ${GLES30.glGetProgramInfoLog(id)}")
            GLES30.glDeleteProgram(id)
            return null
        }
        return Program(id)
    }

    private fun compile(type: Int, source: String): Int? {
        val id = GLES30.glCreateShader(type)
        GLES30.glShaderSource(id, source)
        GLES30.glCompileShader(id)
        val status = IntArray(1)
        GLES30.glGetShaderiv(id, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.w(TAG, "compile failed: ${GLES30.glGetShaderInfoLog(id)}")
            GLES30.glDeleteShader(id)
            return null
        }
        return id
    }

    private data class CheckResult(val maxErr: Int, val detail: String)

    private data class LutChoice(val format: String?, val check: CheckResult?, val tried: String)

    /**
     * Uploads the LUT as a 3D texture in the first format that both uploads cleanly and passes the GPU self-check:
     * RGBA16F (half floats; linear-filterable in core ES 3.0), RGB16F (from floats), then RGBA8.
     */
    @SuppressLint("HalfFloat") // the ShortBuffer carries raw half-float bits for GL_HALF_FLOAT, by design
    private fun uploadAndCheckLut(l: CubeLut): LutChoice {
        drainGlErrors()
        lutTex = newTexture(GLES30.GL_TEXTURE_3D, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        val n = l.size
        val count = n * n * n
        val tried = mutableListOf<String>()
        for (format in LUT_FORMATS) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
            when (format) {
                "RGBA16F" -> {
                    val half = ByteBuffer.allocateDirect(count * 8).order(ByteOrder.nativeOrder()).asShortBuffer()
                    for (i in 0 until count) {
                        for (c in 0..2) half.put(Half.toHalf(l.data[i * 3 + c]))
                        half.put(HALF_ONE)
                    }
                    half.position(0)
                    GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGBA16F, n, n, n, 0, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, half)
                }
                "RGB16F" -> {
                    val data = ByteBuffer.allocateDirect(l.data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(l.data)
                    data.position(0)
                    GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F, n, n, n, 0, GLES30.GL_RGB, GLES30.GL_FLOAT, data)
                }
                else -> {
                    val bytes = ByteBuffer.allocateDirect(count * 4).put(LutMath.toRgba8(l))
                    bytes.position(0)
                    val t = GLES30.GL_TEXTURE_3D
                    GLES30.glTexImage3D(t, 0, GLES30.GL_RGBA8, n, n, n, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bytes)
                }
            }
            val err = GLES30.glGetError()
            if (err != GLES30.GL_NO_ERROR) {
                tried += "$format upload error 0x${Integer.toHexString(err)}"
                continue
            }
            val check = selfCheck(l)
            if (check == null) {
                tried += "$format: self-check could not render"
                continue
            }
            tried += "$format: ${check.detail}"
            if (check.maxErr <= LutMath.SELF_CHECK_TOLERANCE) return LutChoice(format, check, tried.joinToString("; "))
        }
        Log.w(TAG, "LUT self-check failed: $tried")
        return LutChoice(null, null, tried.joinToString("; "))
    }

    /**
     * Renders [LutMath.SELF_CHECK_COLOURS] through the real program (2D input; strength 0, 1 and 0.5) into an RGBA8
     * framebuffer and compares with the CPU reference [LutMath.applyLook]. Strength 0 checks the pipeline without the
     * LUT. Returns the worst error in 8-bit steps with a short description, or null when it could not render.
     */
    private fun selfCheck(l: CubeLut): CheckResult? {
        val p = program2d ?: return null
        val colours = LutMath.SELF_CHECK_COLOURS
        val w = colours.size / 3
        val input = ByteBuffer.allocateDirect(w * 4)
        for (i in 0 until w) {
            for (c in 0..2) input.put(colours[i * 3 + c].toByte())
            input.put(0xFF.toByte())
        }
        input.position(0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        val inTex = newTexture(GLES30.GL_TEXTURE_2D, GLES30.GL_NEAREST)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, 1, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, input)
        val outTex = newTexture(GLES30.GL_TEXTURE_2D, GLES30.GL_NEAREST)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, 1, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        val fbo = IntArray(1).also { GLES30.glGenFramebuffers(1, it, 0) }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, outTex, 0)
        try {
            if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) return null
            GLES30.glViewport(0, 0, w, 1)
            Matrix.setIdentityM(texMatrix, 0)
            var max = 0
            val parts = mutableListOf<String>()
            var worstNote = ""
            for (s in floatArrayOf(0f, 1f, 0.5f)) {
                GLES30.glClearColor(0f, 0f, 0f, 0f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                draw(p, GLES30.GL_TEXTURE_2D, inTex, l, s)
                val pixels = ByteBuffer.allocateDirect(w * 4).order(ByteOrder.nativeOrder())
                GLES30.glReadPixels(0, 0, w, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
                if (GLES30.glGetError() != GLES30.GL_NO_ERROR) return null
                val bytes = ByteArray(w * 4)
                pixels.position(0)
                pixels.get(bytes)
                val err = LutMath.maxErrorSteps(l, colours, bytes, s)
                parts += "s$s=$err"
                if (err > max) {
                    max = err
                    worstNote = worstColour(l, colours, bytes, s)
                }
            }
            return CheckResult(max, "max $max/255 (${parts.joinToString(" ")})$worstNote")
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glDeleteFramebuffers(1, fbo, 0)
            GLES30.glDeleteTextures(2, intArrayOf(inTex, outTex), 0)
        }
    }

    private fun worstColour(l: CubeLut, colours: IntArray, bytes: ByteArray, s: Float): String {
        var worst = -1
        var at = 0
        for (i in 0 until colours.size / 3) {
            val one = LutMath.maxErrorSteps(l, colours.copyOfRange(i * 3, i * 3 + 3), bytes.copyOfRange(i * 4, i * 4 + 4), s)
            if (one > worst) {
                worst = one
                at = i
            }
        }
        val want = LutMath.applyLook(l, colours[at * 3] / 255f, colours[at * 3 + 1] / 255f, colours[at * 3 + 2] / 255f, s)
            .map { (it * 255f).toInt() }
        val gpu = (0..2).map { bytes[at * 4 + it].toInt() and 0xFF }
        return ", worst in ${colours.copyOfRange(at * 3, at * 3 + 3).toList()} at s$s: gpu $gpu want $want"
    }

    private fun draw(p: Program, inputTarget: Int, inputTex: Int, l: CubeLut, s: Float) {
        GLES30.glUseProgram(p.id)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(inputTarget, inputTex)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
        GLES30.glUniform1i(p.uInput, 0)
        GLES30.glUniform1i(p.uLut, 1)
        GLES30.glUniformMatrix4fv(p.uTexMatrix, 1, false, texMatrix, 0)
        GLES30.glUniform1f(p.uStrength, s)
        GLES30.glUniform3f(p.uDomainMin, l.domainMin[0], l.domainMin[1], l.domainMin[2])
        GLES30.glUniform3f(
            p.uDomainScale,
            1f / (l.domainMax[0] - l.domainMin[0]),
            1f / (l.domainMax[1] - l.domainMin[1]),
            1f / (l.domainMax[2] - l.domainMin[2])
        )
        GLES30.glUniform1f(p.uLutScale, LutMath.coordScale(l.size))
        GLES30.glUniform1f(p.uLutOffset, LutMath.coordOffset(l.size))
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, quadPos)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 0, quadTex)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawFrame() {
        val cam = cameraTexture ?: return
        val p = programOes ?: return
        val l = lut ?: return
        if (failed || window == EGL14.EGL_NO_SURFACE) return
        try {
            makeCurrent(window)
            cam.updateTexImage()
            cam.getTransformMatrix(texMatrix)
            val now = SystemClock.elapsedRealtime()
            if (now - attachedAtMs >= WARMUP_MS) displayStats.add(cam.timestamp) else displayStats.breakRun()
            GLES30.glViewport(0, 0, bufferSize.width, bufferSize.height)
            timedDraw(p, l)
            if (!EGL14.eglSwapBuffers(display, window)) throw IllegalStateException("eglSwapBuffers failed: ${eglError()}")
            if (now - lastStatsMs >= STATS_PERIOD_MS) {
                lastStatsMs = now
                listener.onStats(Stats(timing.snapshot(), timerMethod, displayStats.intervals, displayStats.dropped))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "look preview frame failed", t)
            failed = true
            cam.setOnFrameAvailableListener(null)
            listener.onRuntimeError("${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** Draws one frame, timing the LUT pass with GPU timer queries (non-blocking, read a few frames later) or a sparse glFinish bracket. */
    private fun timedDraw(p: Program, l: CubeLut) {
        frameIndex++
        if (gpuTimer) {
            collectQueries()
            val slot = (frameIndex % QUERY_RING).toInt()
            if (queryPending[slot]) {
                draw(p, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex, l, strength)
                return
            }
            if (!timerChecked) drainGlErrors()
            GLES30.glBeginQuery(GL_TIME_ELAPSED_EXT, queries[slot])
            draw(p, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex, l, strength)
            GLES30.glEndQuery(GL_TIME_ELAPSED_EXT)
            if (!timerChecked) {
                timerChecked = true
                if (GLES30.glGetError() != GLES30.GL_NO_ERROR) {
                    // The driver lists the extension but refuses TIME_ELAPSED on the core entry points.
                    gpuTimer = false
                    timerMethod = TIMER_CPU
                    timing.reset()
                    return
                }
            }
            queryPending[slot] = true
        } else if (frameIndex % CPU_SAMPLE_EVERY == 0L) {
            GLES30.glFinish()
            val t0 = System.nanoTime()
            draw(p, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex, l, strength)
            GLES30.glFinish()
            timing.add((System.nanoTime() - t0) / 1e6)
        } else {
            draw(p, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex, l, strength)
        }
    }

    private fun collectQueries() {
        val disjoint = IntArray(1).also { GLES30.glGetIntegerv(GL_GPU_DISJOINT_EXT, it, 0) }[0] != 0
        val v = IntArray(1)
        for (i in 0 until QUERY_RING) {
            if (!queryPending[i]) continue
            GLES30.glGetQueryObjectuiv(queries[i], GLES30.GL_QUERY_RESULT_AVAILABLE, v, 0)
            if (v[0] == 0) continue
            GLES30.glGetQueryObjectuiv(queries[i], GLES30.GL_QUERY_RESULT, v, 0)
            queryPending[i] = false
            if (!disjoint) timing.add((v[0].toLong() and 0xFFFFFFFFL) / 1e6)
        }
    }

    private fun detachOnThread() {
        cameraTexture?.let {
            it.setOnFrameAvailableListener(null)
            it.release()
        }
        cameraTexture = null
        if (display != EGL14.EGL_NO_DISPLAY && pbuffer != EGL14.EGL_NO_SURFACE) makeCurrent(pbuffer)
        if (oesTex != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(oesTex), 0)
            oesTex = 0
        }
        if (window != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(display, window)
            window = EGL14.EGL_NO_SURFACE
        }
        queryPending.fill(false)
    }

    private fun teardownGl() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        runCatching {
            if (pbuffer != EGL14.EGL_NO_SURFACE && context != EGL14.EGL_NO_CONTEXT) makeCurrent(pbuffer)
            programOes?.let { GLES30.glDeleteProgram(it.id) }
            program2d?.let { GLES30.glDeleteProgram(it.id) }
            if (lutTex != 0) GLES30.glDeleteTextures(1, intArrayOf(lutTex), 0)
            if (gpuTimer) GLES30.glDeleteQueries(QUERY_RING, queries, 0)
        }
        programOes = null
        program2d = null
        lutTex = 0
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        pbuffer = EGL14.EGL_NO_SURFACE
        context = EGL14.EGL_NO_CONTEXT
        display = EGL14.EGL_NO_DISPLAY
        ready = false
    }

    private fun makeCurrent(surface: EGLSurface) {
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) throw IllegalStateException("eglMakeCurrent failed: ${eglError()}")
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
        private const val TAG = "RedlineLook"
        const val TIMER_GPU = "GL_EXT_disjoint_timer_query"
        const val TIMER_CPU = "cpu_glfinish_estimate (every 15th frame)"
        private const val CPU_SAMPLE_EVERY = 15L
        private const val QUERY_RING = 4
        private val LUT_FORMATS = listOf("RGBA16F", "RGB16F", "RGBA8")
        private const val HALF_ONE: Short = 0x3C00
        private const val DEFAULT_FPS = 30
        private const val WARMUP_MS = 2_000L
        private const val STATS_PERIOD_MS = 1_000L

        // GL_EXT_disjoint_timer_query enums (not in android.opengl.GLES30).
        private const val GL_TIME_ELAPSED_EXT = 0x88BF
        private const val GL_GPU_DISJOINT_EXT = 0x8FBB

        private fun floats(vararg v: Float): FloatBuffer =
            ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(v).also { it.position(0) }
    }
}
