package com.redline.camera.colour

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.os.SystemClock
import com.redline.camera.capability.Dim
import com.redline.camera.capability.FactoryCalibration
import com.redline.camera.capability.JsonParse
import com.redline.camera.capture.CaptureMath
import com.redline.camera.capture.RawCopy
import com.redline.camera.capture.SaveSettings
import com.redline.camera.capture.WorkerGovernor
import com.redline.camera.look.CubeLut
import com.redline.camera.nativebridge.RedlineNative
import com.redline.camera.viewfinder.NormRect
import com.redline.camera.viewfinder.PxRect
import com.redline.camera.viewfinder.TouchMapping

/**
 * B21 RAW colour pipeline on the phone (PLAN 6 steps 3-6, single frame): RAW_SENSOR -> normalise (levels, lens
 * shading map) -> demosaic -> 3:2 crop at the zoom stop -> white balance -> factory matrices -> warmth-adjusted
 * D65 -> linear sRGB -> area downscale -> Standard tone curve with highlight shoulder -> residual LUT -> sRGB.
 * Kotlin plans each shot ([ShotPlanner], [ColourMath]); the native module does the pixels. Thread-safe after
 * [prepare]; [render] runs on the saver thread.
 */
class ColourPipeline(context: Context) {
    private val app = context.applicationContext

    class Prepared(
        val ok: Boolean,
        val reason: String?,
        val nativeVersion: String?,
        val selfCheck: ColourSelfCheck?,
        val metering: ColourMath.Metering? = null,
        /** B24: the detail asset (vignette, distortion, sharpening, grain), or why it did not load (the still is then
         * rendered without the detail stage). */
        val detail: DetailAsset? = null,
        val detailError: String? = null
    )

    class Outcome(val bitmap: Bitmap?, val shot: ColourShot, val hdr: HdrMap? = null)

    /**
     * B25: the Ultra HDR gain map of a render: the encoded map ([bytes] null when not worth writing, [skip] says why), the
     * knee slope the render measured, and the time spent on it outside the render (encoding the cells, warping).
     */
    class HdrMap(
        val bytes: RedlineNative.GainMapBytes?,
        val slope: Double?,
        val skip: String?,
        val ms: Long,
        val headroomStops: Double? = null,
        val p999Stops: Double? = null,
        val aboveKneeFraction: Double? = null
    ) {
        fun plus(extraMs: Long) = HdrMap(bytes, slope, skip, ms + extraMs, headroomStops, p999Stops, aboveKneeFraction)
    }

    @Volatile private var tone: DoubleArray? = null

    @Volatile private var lut: CubeLut? = null

    @Volatile private var metering: ColourMath.Metering? = null

    @Volatile private var detail: DetailAsset? = null

    /** B24: the Info screen's detail toggles (read at the start of each render and post-pass). */
    @Volatile var detailSettings: DetailSettings = DetailSettings()

    @Volatile private var bundled: List<BundledProfile> = emptyList()

    @Volatile private var ready = false
    private val choices = HashMap<String, ProfileChoice>()

    /** Worker thread: native module, assets, then the self-check frame. Never throws. */
    fun prepare(): Prepared {
        val native = RedlineNative.load()
        if (!native.loaded) return Prepared(false, "native module not loaded (${native.error})", null, null)
        try {
            tone = loadTone(asset(TONE_ASSET))
            metering = ColourMath.Metering.parse(JsonParse.parse(asset(METERING_ASSET)) as Map<*, *>)
            lut = RedlineNative.parseCube(app.assets.open(LUT_ASSET).use { it.readBytes() })
            bundled = PROFILE_ASSETS.map { BundledProfile.parse(asset(it)) }
        } catch (e: Exception) {
            return Prepared(false, "assets: ${e.javaClass.simpleName}: ${e.message}", native.version, null)
        }
        // B24: the detail asset is optional; without it stills render as before (no vignette, sharpening or grain).
        val detailError = try {
            detail = DetailAsset.parse(JsonParse.parse(asset(DETAIL_ASSET)) as Map<*, *>)
            null
        } catch (e: Exception) {
            "${e.javaClass.simpleName}: ${e.message}"
        }
        val check = runSelfCheck()
        ready = check.passed
        val reason = if (check.passed) null else "self-check failed: " + (check.error ?: describe(check))
        return Prepared(check.passed, reason, native.version, check, metering, detail, detailError)
    }

    private fun describe(c: ColourSelfCheck): String =
        "mean dE2000 ${c.meanDe2000}, max step ${c.maxStep8bit}/255, grey chroma ${c.greyChromaMax}, Kotlin maths match ${c.kotlinMathsMatch}"

    /** Renders the bundled self-check frame through Kotlin + native and compares it with the reference render. */
    fun runSelfCheck(): ColourSelfCheck {
        val t0 = SystemClock.elapsedRealtime()
        return try {
            val f = SelfCheckFrame.parse(asset(SELF_CHECK_ASSET))
            val t = tone ?: error("tone not loaded")
            val l = lut ?: error("LUT not loaded")
            val frame = RedlineNative.RawFrame(
                f.rawBuffer(), f.width, f.height, f.width * 2, f.cfa, f.black, f.white, f.shading, f.shadingRows, f.shadingCols
            )
            val g = ShotPlanner.geometry(f.calibration, f.asShotNeutral, f.width, f.height, f.zoom, f.longPx)
            val m = ShotPlanner.floats(g.solve.matrix)
            val n = ShotPlanner.floats(g.solve.neutral)
            val st = RedlineNative.rawStats(frame, g.crop, m, n, ColourMath.STATS_STEP)
            val tn = ShotPlanner.tone(t, st.medianY, f.targetEncoded)
            val out = Bitmap.createBitmap(g.out.width, g.out.height, Bitmap.Config.ARGB_8888)
            val err = RedlineNative.renderRaw(
                frame, g.crop, m, n, tn.curve.gain.toFloat(), tn.table, ColourMath.EV_MIN.toFloat(), tn.curve.clipEv.toFloat(),
                l, threads(), out
            )
            if (err != null) return ColourSelfCheck(error = err, ms = SystemClock.elapsedRealtime() - t0)
            val px = IntArray(g.out.width * g.out.height)
            out.getPixels(px, 0, g.out.width, 0, 0, g.out.width, g.out.height)
            out.recycle()
            f.compare(px, f.mathsMatch(g, tn.exposure.ev), SystemClock.elapsedRealtime() - t0)
        } catch (e: Exception) {
            ColourSelfCheck(error = "${e.javaClass.simpleName}: ${e.message}", ms = SystemClock.elapsedRealtime() - t0)
        }
    }

    /** Profile for [cameraId]: bundled (model hash + facing), else Camera2 matrices, else none with the reason. Cached. */
    /**
     * Drops the cached choices. B25: a choice made before the CAMERA grant could not see the factory calibration
     * (SENSOR_COLOR_TRANSFORM / FORWARD_MATRIX are permission-gated), so it is made again after the grant.
     */
    fun forgetChoices() = synchronized(choices) { choices.clear() }

    fun profileFor(cameraId: String): ProfileChoice = synchronized(choices) {
        choices.getOrPut(cameraId) {
            val manager = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val ch = runCatching { manager.getCameraCharacteristics(cameraId) }.getOrNull()
            val facing = when (ch?.let { runCatching { it.get(CameraCharacteristics.LENS_FACING) }.getOrNull() }) {
                CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                null -> null
                else -> "EXTERNAL"
            }
            // Logical multi-cameras often leave the calibration to a physical camera (as in B04).
            val camera2 = ch?.let {
                val ids = listOf(cameraId) + runCatching { it.physicalCameraIds.toList() }.getOrDefault(emptyList())
                FactoryCalibration.pick(
                    ids.mapNotNull { id ->
                        runCatching { FactoryCalibration.read(cameraId, manager.getCameraCharacteristics(id), id) }.getOrNull()
                    }
                )
            }
            ColourProfiles.select(bundled, Build.MANUFACTURER ?: "", Build.MODEL ?: "", facing, camera2)
        }
    }

    /**
     * One still: renders the RAW frame (sensor orientation, the zoom stop's 3:2 crop and output size). The bitmap
     * is null when the pipeline could not render; [Outcome.shot] then says why and the caller saves the camera JPEG.
     * B22: [raw] is a pool copy of the frame, or the merged burst; [levelScale] is the factor its samples were scaled
     * by (16 for the merge's extra precision), applied to the result's black and white levels. The exposure is solved
     * from the frame's own median and p98 with the R5 II metering fit (B23c; it was the camera JPEG's median before) plus
     * the B23c.1 headroom lift (p25 and the clipped fraction), so
     * an under-exposed burst is brought back up here. The camera JPEG's median is still read, for the report only.
     */
    fun render(
        raw: RawCopy,
        result: TotalCaptureResult,
        characteristics: CameraCharacteristics,
        cameraId: String,
        jpeg: ByteArray,
        zoomRatio: Float,
        stopLongPx: Int,
        levelScale: Float = 1f,
        stopMm: Int = 0,
        spot: NormRect? = null,
        ultraHdr: Boolean = false
    ): Outcome {
        val t0 = SystemClock.elapsedRealtime()
        val timings = LinkedHashMap<String, Long>()
        fun fail(why: String) = Outcome(null, ColourShot(rendered = false, fallbackReason = why, timingsMs = timings))
        if (!ready) return fail("pipeline not ready")
        val t = tone ?: return fail("tone not loaded")
        val l = lut ?: return fail("LUT not loaded")
        val choice = profileFor(cameraId)
        val cal = choice.calibration ?: return fail(choice.reason ?: "no colour profile")
        return try {
            val frame = rawFrame(raw, result, characteristics, levelScale) ?: return fail("unsupported RAW layout or missing levels")
            val neutral = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)?.map { it.toDouble() }?.toDoubleArray()
                ?: return fail("no SENSOR_NEUTRAL_COLOR_POINT in the capture result")
            var t1 = SystemClock.elapsedRealtime()
            val median = runCatching { jpegMedian(jpeg) }.getOrNull()
            timings["jpeg_median"] = SystemClock.elapsedRealtime() - t1
            t1 = SystemClock.elapsedRealtime()
            val g = ShotPlanner.geometry(cal, neutral, frame.width, frame.height, zoomRatio.toDouble(), stopLongPx)
            val m = ShotPlanner.floats(g.solve.matrix)
            val n = ShotPlanner.floats(g.solve.neutral)
            val st = RedlineNative.rawStats(frame, g.crop, m, n, ColourMath.STATS_STEP)
            // B24b: the tapped region's stats over the same CFA cells (a few hundred samples), for the touch-metering shift;
            // a region the native side refuses is no reason to lose the shot (no shift then)
            val spotPx = spot?.let { TouchMapping.regionInCrop(it, PxRect(g.crop[0], g.crop[1], g.crop[2], g.crop[3]), even = true) }
            val spotSt = spotPx?.let { runCatching { RedlineNative.rawStats(frame, it.toIntArray(), m, n, spotStep(it, 2)) }.getOrNull() }
            timings["stats"] = SystemClock.elapsedRealtime() - t1
            t1 = SystemClock.elapsedRealtime()
            val met = metering ?: return fail("metering not loaded")
            val tn = ShotPlanner.meteredTone(t, st.medianY, st.p98Y, met, st.p25Y, st.clippedFraction, spotSt?.medianY ?: Double.NaN)
            val jpegEv = median?.let { ColourMath.solveExposure(t, st.medianY, it).ev }
            val table = tn.table
            timings["plan"] = SystemClock.elapsedRealtime() - t1
            t1 = SystemClock.elapsedRealtime()
            val out = Bitmap.createBitmap(g.out.width, g.out.height, Bitmap.Config.ARGB_8888)
            val cells = if (ultraHdr) RedlineNative.GainMapCells(g.out.width, g.out.height, ColourMath.KNEE_EV.toFloat()) else null
            val err = RedlineNative.renderRaw(
                frame, g.crop, m, n, tn.curve.gain.toFloat(), table, ColourMath.EV_MIN.toFloat(), tn.curve.clipEv.toFloat(),
                l, threads(), out, vignetteTable(stopMm), cells
            )
            timings["render"] = SystemClock.elapsedRealtime() - t1
            timings["total"] = SystemClock.elapsedRealtime() - t0
            if (err != null) {
                out.recycle()
                return fail("native render: $err")
            }
            Outcome(
                out,
                ColourShot(
                    rendered = true,
                    profile = choice.profileId,
                    cct = g.solve.cct,
                    warmth = g.solve.warmth,
                    exposureEv = tn.exposure.ev,
                    exposureMethod = tn.exposure.method,
                    jpegMedian = median,
                    exposureEvCameraJpeg = jpegEv,
                    clippedFraction = st.clippedFraction,
                    headroomLiftEv = tn.exposure.headroomLiftEv,
                    p98Y = st.p98Y.takeIf { it.isFinite() },
                    sceneHiEv = sceneHi(st),
                    sceneSplitEv = sceneSplit(st),
                    spot = spotShot(spotPx, spotSt, st, tn.exposure),
                    outSize = g.out,
                    timingsMs = timings,
                    iso = sensitivity(result),
                    stopMm = stopMm
                ),
                cells?.let { finishHdr(it) }
            )
        } catch (e: Exception) {
            fail("${e.javaClass.simpleName}: ${e.message}")
        } catch (e: OutOfMemoryError) {
            fail("out of memory")
        }
    }

    /** B25: encodes the gain map cells of a render (capped p99.9 headroom); never throws. */
    private fun finishHdr(cells: RedlineNative.GainMapCells): HdrMap {
        val t0 = SystemClock.elapsedRealtime()
        return try {
            val b = RedlineNative.finishGainMap(cells, SaveSettings.HEADROOM_CAP_STOPS, SaveSettings.HEADROOM_MIN_STOPS)
            val skip = when (b.skipCode) {
                0 -> null
                1 -> "no highlights above the knee"
                else -> "headroom %.2f stops < %.2f".format(b.headroomStops, SaveSettings.HEADROOM_MIN_STOPS)
            }
            HdrMap(
                if (b.written) b else null,
                cells.slope.toDouble(),
                skip,
                SystemClock.elapsedRealtime() - t0,
                b.headroomStops,
                b.p999Stops,
                b.aboveKneeFraction
            )
        } catch (e: Exception) {
            HdrMap(null, null, "gain map: ${e.javaClass.simpleName}: ${e.message}", SystemClock.elapsedRealtime() - t0)
        }
    }

    /**
     * B25: the gain map follows the still through the RAW-feel distortion ([applied]: the detail pass distorted it), so
     * the highlights it lifts stay on the same pixels. Returns the map with the warp time added; never throws.
     */
    fun warpHdr(hdr: HdrMap, imageWidth: Int, imageHeight: Int, stopMm: Int, applied: Boolean): HdrMap {
        val b = hdr.bytes ?: return hdr
        val d = detail ?: return hdr
        if (!applied || stopMm <= 0) return hdr
        val t0 = SystemClock.elapsedRealtime()
        val err = runCatching { RedlineNative.warpGainMap(b, imageWidth, imageHeight, d.distortionFor(stopMm)) }
            .getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
        val ms = SystemClock.elapsedRealtime() - t0
        return if (err == null) {
            hdr.plus(ms)
        } else {
            HdrMap(null, hdr.slope, "gain map warp: $err", hdr.ms + ms, hdr.headroomStops, hdr.p999Stops, hdr.aboveKneeFraction)
        }
    }

    /**
     * B23: renders a finished super-resolution merge (normalised camera RGB already on the [outWidth] x [outHeight] grid
     * of the stop's crop) through stages 4-8, planned exactly as [render] plans a frame: the colour solve from the
     * reference frame's [result], the exposure from the merge's own median and p98 (B23c metering fit).
     */
    fun renderSuperRes(
        sr: RedlineNative.SuperResMerger,
        result: TotalCaptureResult,
        characteristics: CameraCharacteristics,
        cameraId: String,
        jpeg: ByteArray,
        outWidth: Int,
        outHeight: Int,
        stopMm: Int = 0,
        spot: NormRect? = null,
        ultraHdr: Boolean = false
    ): Outcome {
        val t0 = SystemClock.elapsedRealtime()
        val timings = LinkedHashMap<String, Long>()
        fun fail(why: String) = Outcome(null, ColourShot(rendered = false, fallbackReason = why, timingsMs = timings))
        if (!ready) return fail("pipeline not ready")
        val t = tone ?: return fail("tone not loaded")
        val l = lut ?: return fail("LUT not loaded")
        val choice = profileFor(cameraId)
        val cal = choice.calibration ?: return fail(choice.reason ?: "no colour profile")
        return try {
            val neutral = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)?.map { it.toDouble() }?.toDoubleArray()
                ?: return fail("no SENSOR_NEUTRAL_COLOR_POINT in the capture result")
            var t1 = SystemClock.elapsedRealtime()
            val median = runCatching { jpegMedian(jpeg) }.getOrNull()
            timings["jpeg_median"] = SystemClock.elapsedRealtime() - t1
            t1 = SystemClock.elapsedRealtime()
            val solve = ColourMath.solveColour(cal, neutral, true)
            val m = ShotPlanner.floats(solve.matrix)
            val n = ShotPlanner.floats(solve.neutral)
            val st = sr.stats(m, n, 2 * ColourMath.STATS_STEP)
            val spotPx = spot?.let { TouchMapping.regionInCrop(it, PxRect(0, 0, outWidth, outHeight), even = false) }
            val spotSt = spotPx?.let { runCatching { sr.stats(m, n, spotStep(it, 1), it.toIntArray()) }.getOrNull() }
            timings["stats"] = SystemClock.elapsedRealtime() - t1
            t1 = SystemClock.elapsedRealtime()
            val met = metering ?: return fail("metering not loaded")
            val tn = ShotPlanner.meteredTone(t, st.medianY, st.p98Y, met, st.p25Y, st.clippedFraction, spotSt?.medianY ?: Double.NaN)
            val jpegEv = median?.let { ColourMath.solveExposure(t, st.medianY, it).ev }
            val table = tn.table
            timings["plan"] = SystemClock.elapsedRealtime() - t1
            t1 = SystemClock.elapsedRealtime()
            val out = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
            val cells = if (ultraHdr) RedlineNative.GainMapCells(outWidth, outHeight, ColourMath.KNEE_EV.toFloat()) else null
            val err = sr.render(
                m, n, tn.curve.gain.toFloat(), table, ColourMath.EV_MIN.toFloat(), tn.curve.clipEv.toFloat(), l, threads(), out,
                vignetteTable(stopMm), cells
            )
            timings["render"] = SystemClock.elapsedRealtime() - t1
            timings["total"] = SystemClock.elapsedRealtime() - t0
            if (err != null) {
                out.recycle()
                return fail("native super-resolution render: $err")
            }
            Outcome(
                out,
                ColourShot(
                    rendered = true,
                    profile = choice.profileId,
                    cct = solve.cct,
                    warmth = solve.warmth,
                    exposureEv = tn.exposure.ev,
                    exposureMethod = tn.exposure.method,
                    jpegMedian = median,
                    exposureEvCameraJpeg = jpegEv,
                    clippedFraction = st.clippedFraction,
                    headroomLiftEv = tn.exposure.headroomLiftEv,
                    p98Y = st.p98Y.takeIf { it.isFinite() },
                    sceneHiEv = sceneHi(st),
                    sceneSplitEv = sceneSplit(st),
                    spot = spotShot(spotPx, spotSt, st, tn.exposure),
                    outSize = Dim(outWidth, outHeight),
                    timingsMs = timings,
                    iso = sensitivity(result),
                    stopMm = stopMm
                ),
                cells?.let { finishHdr(it) }
            )
        } catch (e: Exception) {
            fail("${e.javaClass.simpleName}: ${e.message}")
        } catch (e: OutOfMemoryError) {
            fail("out of memory")
        }
    }

    /** B24: the stop's residual vignette table when the toggle is on and the asset loaded (0 mm = no stop: none). */
    private fun vignetteTable(stopMm: Int): FloatArray? {
        val d = detail ?: return null
        return if (detailSettings.vignette && stopMm > 0) d.vignetteFor(stopMm) else null
    }

    private fun sensitivity(result: TotalCaptureResult): Int? = runCatching { result.get(CaptureResult.SENSOR_SENSITIVITY) }.getOrNull()

    /**
     * B24: the detail post-pass on the finished still (after the 50/70 upscale), in place: distortion (RAW feel
     * toggle), Canon Standard sharpening, grain topped up to the R5 II camera JPEG's noise at the shot's [iso].
     * Returns what it did (null when the asset is missing or every post-pass toggle is off). Never throws.
     */
    fun applyDetail(bitmap: Bitmap, iso: Int?, stopMm: Int, seed: Int): DetailShot? {
        val d = detail ?: return null
        val set = detailSettings
        val vig = if (set.vignette && stopMm > 0) d.cornerEv(stopMm) else null
        if (!set.sharpen && !set.grain && !set.distortion) {
            return vig?.let {
                DetailShot(d.pictureStyle, stopMm, it, d.vignetteAlpha, false, null, null, null, null, null, null, emptyMap())
            }
        }
        val t0 = SystemClock.elapsedRealtime()
        val usm = if (set.sharpen) d.usmFor(bitmap.width) else null
        val grainIso = if (set.grain && iso != null && iso > 0) iso.toDouble() else null
        val target = grainIso?.let { d.grainTarget(it, bitmap.width) }
        val g = d.grainFor(bitmap.width)
        return try {
            val st = RedlineNative.applyDetail(
                bitmap,
                set.distortion && stopMm > 0,
                if (stopMm > 0) d.distortionFor(stopMm) else FloatArray(3),
                (usm?.sigma ?: 1.0).toFloat(),
                (usm?.amount ?: 0.0).toFloat(),
                (usm?.threshold ?: 0.0).toFloat(),
                target,
                g.kernelW.toFloat(),
                g.kappa.toFloat(),
                seed,
                threads()
            )
            val timings = linkedMapOf(
                "total" to SystemClock.elapsedRealtime() - t0,
                "distortion" to Math.round(st.distortMs),
                "sharpen" to Math.round(st.sharpenMs),
                "noise_estimate" to Math.round(st.noiseMs),
                "grain" to Math.round(st.grainMs)
            )
            DetailShot(
                pictureStyle = d.pictureStyle,
                stopMm = stopMm,
                vignetteCornerEv = vig,
                vignetteAlpha = vig?.let { d.vignetteAlpha },
                distortion = set.distortion && stopMm > 0,
                distortScale = if (set.distortion && stopMm > 0) st.distortScale else null,
                sharpen = usm,
                grainIso = grainIso,
                grainTarget = target?.map { it.toDouble() },
                grainMeasured = if (target != null) st.measured else null,
                grainAdded = if (target != null) st.added else null,
                timingsMs = timings,
                peakMb = st.peakMb,
                width = bitmap.width,
                height = bitmap.height
            )
        } catch (e: Exception) {
            DetailShot(
                d.pictureStyle, stopMm, vig, d.vignetteAlpha, false, null, null, null, null, null, null,
                mapOf("total" to SystemClock.elapsedRealtime() - t0), error = "${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    /** B23: the CFA layout of the camera (Camera2 0..3), or null when it is not a supported Bayer layout. */
    fun cfaOf(ch: CameraCharacteristics): Int? =
        runCatching { ch.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) }.getOrNull()?.takeIf { it in 0..3 }

    /** The RAW samples plus levels (times [scale]), CFA layout and lens shading map from the result and characteristics. */
    private fun rawFrame(raw: RawCopy, result: TotalCaptureResult, ch: CameraCharacteristics, scale: Float): RedlineNative.RawFrame? {
        val cfa = runCatching { ch.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) }.getOrNull() ?: return null
        if (cfa !in 0..3) return null
        val (black, white) = levels(result, ch) ?: return null
        val map = runCatching { result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP) }.getOrNull()
        val shading = map?.let { FloatArray(it.gainFactorCount).also { a -> it.copyGainFactors(a, 0) } }
        return RedlineNative.RawFrame(
            raw.buffer, raw.width, raw.height, raw.rowStrideBytes, cfa, FloatArray(4) { black[it] * scale }, white * scale, shading,
            map?.rowCount ?: 0, map?.columnCount ?: 0
        )
    }

    /** Black and white levels of a frame (dynamic from the result, else static), for the burst merge. */
    fun levels(result: TotalCaptureResult, ch: CameraCharacteristics): Pair<FloatArray, Float>? {
        val black = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.takeIf { it.size == 4 }
            ?: ch.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.let { p ->
                FloatArray(4) { i -> p.getOffsetForIndex(i % 2, i / 2).toFloat() }
            } ?: return null
        val white = result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)?.toFloat()
            ?: ch.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)?.toFloat() ?: return null
        return black to white
    }

    /**
     * B24b: sampling step for a tapped region so it gives ~24 samples per side ([pxPerSample] pixels per sample unit: 2 for
     * CFA cells, 1 for the super-resolution grid), never coarser than the frame's own stats.
     */
    private fun spotStep(r: PxRect, pxPerSample: Int): Int =
        (minOf(r.width, r.height) / pxPerSample / SPOT_SAMPLES_PER_SIDE).coerceIn(1, ColourMath.STATS_STEP)

    private fun spotShot(px: PxRect?, st: RedlineNative.Stats?, frame: RedlineNative.Stats, e: ColourMath.Exposure): SpotShot? {
        if (px == null || st == null) return null
        val d = if (st.medianY > 0 && frame.medianY > 0) Math.log(frame.medianY / st.medianY) / Math.log(2.0) else null
        return SpotShot(px.toList(), st.medianY, d, e.spotShiftEv, st.cells)
    }

    /** Touch-metering model (metering.json spot), or null when the asset has none. */
    val spotModel: ColourMath.Spot? get() = metering?.spot

    /** True once the self-check passed (the burst merge renders through the same pipeline). */
    val isReady: Boolean get() = ready

    /** The camera JPEG's median luma (encoded) over the same 3:2 framing, from an 1/8-scale decode. */
    private fun sceneHi(st: RedlineNative.Stats): Double? =
        if (st.p98Y.isFinite() && st.medianY > 0) ColourMath.sceneHi(st.medianY, st.p98Y) else null

    private fun sceneSplit(st: RedlineNative.Stats): Double? =
        if (st.p25Y.isFinite() && st.medianY > 0) ColourMath.sceneSplit(st.medianY, st.p25Y) else null

    private fun jpegMedian(jpeg: ByteArray): Double? {
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = 8 }) ?: return null
        try {
            val c = CaptureMath.crop32(bmp.width, bmp.height)
            val px = IntArray(c.width * c.height)
            bmp.getPixels(px, 0, c.width, c.left, c.top, c.width, c.height)
            val rgb = IntArray(px.size * 3)
            for (i in px.indices) {
                rgb[3 * i] = (px[i] shr 16) and 0xFF
                rgb[3 * i + 1] = (px[i] shr 8) and 0xFF
                rgb[3 * i + 2] = px[i] and 0xFF
            }
            return ColourMath.jpegMedianEncoded(rgb)
        } finally {
            bmp.recycle()
        }
    }

    private fun asset(name: String): String = app.assets.open(name).use { it.readBytes().toString(Charsets.UTF_8) }

    /**
     * B23b: the most render threads the pipeline may use (the worker governor lowers it while the viewfinder is busy);
     * null = every core up to [MAX_THREADS]. Read at the start of each render.
     */
    @Volatile var threadLimit: Int? = null

    private fun threads(): Int = WorkerGovernor.cappedThreads(Runtime.getRuntime().availableProcessors(), MAX_THREADS, threadLimit)

    companion object {
        const val LOOK_NAME = "Redline Standard"
        const val TONE_ASSET = "colour/standard_tone.json"
        const val METERING_ASSET = "colour/metering.json"
        private const val SPOT_SAMPLES_PER_SIDE = 24
        const val LUT_ASSET = "luts/redline_standard_preview_33.cube"
        const val SELF_CHECK_ASSET = "colour/self_check.json"
        const val DETAIL_ASSET = "colour/detail.json"
        val PROFILE_ASSETS = listOf("colour/baseline_profile.json")
        private const val MAX_THREADS = 8

        /** The fitted Standard tone values from assets/colour/standard_tone.json (same check as the reference's load_tone). */
        fun loadTone(text: String): DoubleArray {
            val m = JsonParse.parse(text) as Map<*, *>
            require((m["model"] as? String)?.startsWith("xyz_d65 -> srgb_linear -> tone(log2) -> lut3d(srgb_encoded)") == true) {
                "unexpected look model"
            }
            val tn = m["tone"] as Map<*, *>
            require((tn["ev_min"] as Number).toDouble() == ColourMath.EV_MIN && (tn["ev_max"] as Number).toDouble() == ColourMath.EV_MAX) {
                "tone grid differs"
            }
            return (tn["values"] as List<*>).map { (it as Number).toDouble() }.toDoubleArray()
        }
    }
}
