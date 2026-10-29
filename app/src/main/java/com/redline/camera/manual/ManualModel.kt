package com.redline.camera.manual

import com.redline.camera.capability.CameraProfile
import kotlin.math.abs

enum class ShootMode(val label: String) {
    AUTO("Auto"),

    /** Aperture priority. On a fixed-aperture phone this is auto exposure with EV, AE lock and WB controls. */
    AV("Av"),

    /** Manual ISO, shutter and focus. */
    M("M")
}

/**
 * The manual-control ranges of the default camera, read once from CameraCharacteristics (B14). Pure, so the
 * gate (every control limited to the reported ranges) is unit-testable. A control whose key is missing is
 * hidden, never guessed.
 */
data class ManualCaps(
    val hardwareLevel: String,
    val manualSensor: Boolean,
    val manualPostProcessing: Boolean,
    val isoRange: Pair<Int, Int>?,
    val exposureRangeNs: Pair<Long, Long>?,
    val maxFrameDurationNs: Long?,
    val minFocusDiopters: Float?,
    /** Where [minFocusDiopters] came from: "logical", "physical <id>", or "not reported". */
    val minFocusSource: String,
    /** LENS_INFO_FOCUS_DISTANCE_CALIBRATION name; distances are shown in metres only when not UNCALIBRATED. */
    val focusCalibration: String?,
    val aeCompRange: Pair<Int, Int>?,
    val aeCompStep: Float?,
    val aeLockAvailable: Boolean,
    val awbLockAvailable: Boolean,
    val aeModes: List<String>,
    val afModes: List<String>,
    val awbModes: List<String>,
    val apertures: List<Float>
) {
    val manualExposure: Boolean
        get() = manualSensor && "OFF" in aeModes && isoRange != null && exposureRangeNs != null

    val manualFocus: Boolean
        get() = manualSensor && "OFF" in afModes && ManualMath.manualFocusAvailable(minFocusDiopters)

    val ev: Boolean get() = "ON" in aeModes && ManualMath.evAvailable(aeCompRange, aeCompStep)

    /** AUTO plus every preset the camera lists; empty when AWB AUTO itself is not listed. */
    val awbPresets: List<String>
        get() = if ("AUTO" !in awbModes) emptyList() else AWB_PRESETS.filter { it in awbModes }

    val metricFocus: Boolean get() = focusCalibration != null && focusCalibration != "UNCALIBRATED"

    val fixedAperture: Boolean get() = apertures.size <= 1

    val apertureLabel: String
        get() = when {
            apertures.isEmpty() -> "f/? (not reported)"
            apertures.size == 1 -> "f/${apertures[0]} fixed"
            else -> apertures.joinToString("/", prefix = "f/")
        }

    val isoLadder: List<Int> get() = isoRange?.let { ManualMath.isoLadder(it) }.orEmpty()
    val shutterLadder: List<Long> get() = exposureRangeNs?.let { ManualMath.shutterLadder(it, maxFrameDurationNs) }.orEmpty()
    val evLadder: List<EvStop> get() = if (ev) ManualMath.evLadder(aeCompRange, aeCompStep) else emptyList()
    val focusLadder: List<Float> get() = if (manualFocus) ManualMath.focusLadder(minFocusDiopters) else emptyList()

    /** Av needs auto exposure; M needs MANUAL_SENSOR with AE OFF and both sensor ranges. */
    fun modes(): List<ShootMode> = buildList {
        add(ShootMode.AUTO)
        if ("ON" in aeModes) add(ShootMode.AV)
        if (manualExposure) add(ShootMode.M)
    }

    /** Controls that are hidden or not applied on this camera, with the reason shown in the report. */
    fun hidden(): Map<String, String> {
        val out = linkedMapOf<String, String>()
        if (!manualExposure) {
            out["manual_exposure"] = when {
                !manualSensor -> "no MANUAL_SENSOR capability"
                "OFF" !in aeModes -> "AE mode OFF not listed"
                isoRange == null -> "SENSOR_INFO_SENSITIVITY_RANGE not reported"
                else -> "SENSOR_INFO_EXPOSURE_TIME_RANGE not reported"
            }
        }
        if (!manualFocus) {
            out["manual_focus"] = when {
                !manualSensor -> "no MANUAL_SENSOR capability"
                "OFF" !in afModes -> "AF mode OFF not listed"
                minFocusDiopters == null -> "LENS_INFO_MINIMUM_FOCUS_DISTANCE not reported"
                else -> "fixed-focus lens (minimum focus distance 0)"
            }
        }
        if (!ev) out["ev"] = "CONTROL_AE_COMPENSATION_RANGE/STEP not usable"
        if (!aeLockAvailable) out["ae_lock"] = "CONTROL_AE_LOCK_AVAILABLE false"
        if (!awbLockAvailable) out["awb_lock"] = "CONTROL_AWB_LOCK_AVAILABLE false"
        if (awbPresets.size <= 1) out["awb_presets"] = "no AWB presets listed besides AUTO"
        out["kelvin_wb"] = "not in B14: white balance uses the CONTROL_AWB_MODE presets the camera lists"
        out["aperture"] = if (fixedAperture) "fixed aperture: shown read-only ($apertureLabel)" else "aperture selection not in B14"
        out["iso_cap"] = "Av has no ISO ceiling: Camera2 auto exposure has no ISO-limit control, so none is applied"
        return out
    }

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "hardware_level" to hardwareLevel,
        "manual_sensor" to manualSensor,
        "manual_post_processing" to manualPostProcessing,
        "iso_range" to isoRange?.toList(),
        "exposure_range_ns" to exposureRangeNs?.toList(),
        "max_frame_duration_ns" to maxFrameDurationNs,
        "shutter_ceiling_ns" to exposureRangeNs?.let { ManualMath.shutterCeilingNs(it, maxFrameDurationNs) },
        "preview_max_exposure_ns" to ManualMath.PREVIEW_MAX_EXPOSURE_NS,
        "min_focus_diopters" to minFocusDiopters,
        "min_focus_source" to minFocusSource,
        "focus_calibration" to focusCalibration,
        "ae_compensation_range" to aeCompRange?.toList(),
        "ae_compensation_step" to aeCompStep,
        "ae_lock_available" to aeLockAvailable,
        "awb_lock_available" to awbLockAvailable,
        "awb_presets" to awbPresets,
        "apertures" to apertures,
        "iso_ladder" to isoLadder,
        "shutter_ladder" to shutterLadder.map { ManualMath.shutterLabel(it) },
        "ev_ladder" to evLadder.map { ManualMath.evLabel(it.ev) },
        "focus_ladder_diopters" to focusLadder
    )

    companion object {
        /** Presets in the order the WB button cycles through them. */
        val AWB_PRESETS = listOf(
            "AUTO",
            "DAYLIGHT",
            "CLOUDY_DAYLIGHT",
            "SHADE",
            "TWILIGHT",
            "FLUORESCENT",
            "WARM_FLUORESCENT",
            "INCANDESCENT"
        )

        fun awbLabel(mode: String): String = when (mode) {
            "AUTO" -> "AWB"
            "DAYLIGHT" -> "Daylight"
            "CLOUDY_DAYLIGHT" -> "Cloudy"
            "SHADE" -> "Shade"
            "TWILIGHT" -> "Twilight"
            "FLUORESCENT" -> "Fluorescent"
            "WARM_FLUORESCENT" -> "Warm fluor."
            "INCANDESCENT" -> "Tungsten"
            else -> mode
        }

        /**
         * Builds the caps from the cached profile plus the keys the profile does not keep. The logical camera may
         * omit LENS_INFO_MINIMUM_FOCUS_DISTANCE (B10a); then the physical lens whose focal length the logical
         * camera reports (the main lens) supplies it, as in DeviceProfile.
         */
        fun from(
            camera: CameraProfile,
            aeLockAvailable: Boolean,
            awbLockAvailable: Boolean,
            logicalMinFocus: Float?,
            logicalFocals: List<Float>,
            logicalApertures: List<Float>,
            focusCalibration: String?
        ): ManualCaps {
            val main = camera.lenses.firstOrNull { l -> l.focalLengthsMm.any { f -> logicalFocals.any { abs(it - f) < 0.01f } } }
                ?: camera.lenses.singleOrNull()
            val (minFocus, source) = when {
                logicalMinFocus != null -> logicalMinFocus to "logical"
                main?.minFocusDistanceDiopters != null -> main.minFocusDistanceDiopters to "physical ${main.cameraId}"
                else -> null to "not reported"
            }
            return ManualCaps(
                hardwareLevel = camera.hardwareLevel.name,
                manualSensor = camera.has("MANUAL_SENSOR"),
                manualPostProcessing = camera.has("MANUAL_POST_PROCESSING"),
                isoRange = camera.isoRange,
                exposureRangeNs = camera.exposureRangeNs,
                maxFrameDurationNs = camera.maxFrameDurationNs,
                minFocusDiopters = minFocus,
                minFocusSource = source,
                focusCalibration = focusCalibration,
                aeCompRange = camera.aeCompensation,
                aeCompStep = camera.aeCompensationStep,
                aeLockAvailable = aeLockAvailable,
                awbLockAvailable = awbLockAvailable,
                aeModes = camera.aeModes,
                afModes = camera.afModes,
                awbModes = camera.awbModes,
                apertures = logicalApertures.ifEmpty { main?.apertures.orEmpty() }
            )
        }
    }
}

/** What the user asked for. Values are kept on the ladders; [ManualModel.resolve] clamps whatever arrives. */
data class ManualSettings(
    val mode: ShootMode = ShootMode.AUTO,
    val iso: Int = 100,
    val exposureNs: Long = 16_666_666L,
    /** Null = autofocus. */
    val focusDiopters: Float? = null,
    val evIndex: Int = 0,
    val aeLock: Boolean = false,
    val awbLock: Boolean = false,
    val awb: String = "AUTO"
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "mode" to mode.name,
        "iso" to iso,
        "exposure_ns" to exposureNs,
        "focus_diopters" to focusDiopters,
        "ev_index" to evIndex,
        "ae_lock" to aeLock,
        "awb_lock" to awbLock,
        "awb" to awb
    )
}

/** A value that had to be moved into its reported range before it was applied. */
data class ClampEvent(val control: String, val requested: String, val applied: String, val range: String) {
    fun toMap(): Map<String, Any?> = linkedMapOf("control" to control, "requested" to requested, "applied" to applied, "range" to range)

    override fun toString(): String = "$control $requested -> $applied ($range)"
}

/** Exactly what goes into the preview and still requests. Null fields are left to the template / auto modes. */
data class ManualApplied(
    val mode: ShootMode,
    /** CONTROL_AE_MODE OFF with the sensor values below (M). */
    val aeOff: Boolean,
    val stillIso: Int?,
    val stillExposureNs: Long?,
    val stillFrameDurationNs: Long?,
    val previewIso: Int?,
    val previewExposureNs: Long?,
    val previewFrameDurationNs: Long?,
    val previewShortfallEv: Double,
    /** Null = autofocus (CONTINUOUS_PICTURE); else AF OFF with LENS_FOCUS_DISTANCE. */
    val focusDiopters: Float?,
    val evIndex: Int,
    val ev: Double,
    val aeLock: Boolean,
    val awbLock: Boolean,
    val awbMode: String,
    val clamps: List<ClampEvent>,
    /** Independent re-check of every applied value against its reported range. */
    val withinRanges: Boolean
) {
    val previewLimited: Boolean get() = aeOff && previewExposureNs != null && stillExposureNs != null && previewExposureNs < stillExposureNs

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "mode" to mode.name,
        "ae_off" to aeOff,
        "still_iso" to stillIso,
        "still_exposure_ns" to stillExposureNs,
        "still_shutter" to stillExposureNs?.let { ManualMath.shutterLabel(it) },
        "still_frame_duration_ns" to stillFrameDurationNs,
        "preview_iso" to previewIso,
        "preview_exposure_ns" to previewExposureNs,
        "preview_frame_duration_ns" to previewFrameDurationNs,
        "preview_shortfall_ev" to Math.round(previewShortfallEv * 100) / 100.0,
        "focus_diopters" to focusDiopters,
        "ev_index" to evIndex,
        "ev" to Math.round(ev * 1000) / 1000.0,
        "ae_lock" to aeLock,
        "awb_lock" to awbLock,
        "awb_mode" to awbMode,
        "within_ranges" to withinRanges
    )
}

object ManualModel {
    /**
     * Resolves the user's settings against the reported ranges. Every value is clamped (and the clamp recorded)
     * before it can reach a request; a control the camera does not report is left at its auto default, never
     * applied. The result is re-checked against the ranges independently ([ManualApplied.withinRanges]).
     */
    fun resolve(caps: ManualCaps, s: ManualSettings): ManualApplied {
        val clamps = mutableListOf<ClampEvent>()
        val mode = if (s.mode in caps.modes()) {
            s.mode
        } else {
            clamps += ClampEvent("mode", s.mode.name, ShootMode.AUTO.name, "modes ${caps.modes().joinToString { it.name }}")
            ShootMode.AUTO
        }
        val pro = mode != ShootMode.AUTO
        val awb = when {
            !pro || caps.awbPresets.isEmpty() -> "AUTO"
            s.awb in caps.awbPresets -> s.awb
            else -> {
                clamps += ClampEvent("awb", s.awb, "AUTO", "presets ${caps.awbPresets.joinToString()}")
                "AUTO"
            }
        }
        val awbLock = pro && s.awbLock && caps.awbLockAvailable

        if (mode != ShootMode.M) {
            var evIndex = 0
            if (mode == ShootMode.AV && caps.ev) {
                val c = ManualMath.clampEvIndex(caps.aeCompRange, caps.aeCompStep, s.evIndex)
                if (c.clamped) clamps += ClampEvent("ev_index", "${c.requested}", "${c.value}", "${caps.aeCompRange?.toList()} and +/-4 EV")
                evIndex = c.value
            }
            val ev = evIndex * (caps.aeCompStep ?: 0f).toDouble()
            val applied = ManualApplied(
                mode = mode,
                aeOff = false,
                stillIso = null,
                stillExposureNs = null,
                stillFrameDurationNs = null,
                previewIso = null,
                previewExposureNs = null,
                previewFrameDurationNs = null,
                previewShortfallEv = 0.0,
                focusDiopters = null,
                evIndex = evIndex,
                ev = ev,
                aeLock = mode == ShootMode.AV && s.aeLock && caps.aeLockAvailable,
                awbLock = awbLock,
                awbMode = awb,
                clamps = clamps,
                withinRanges = true
            )
            return applied.copy(withinRanges = check(caps, applied))
        }

        val isoRange = caps.isoRange!!
        val expRange = caps.exposureRangeNs!!
        val iso = ManualMath.clampIso(isoRange, s.iso)
        if (iso.clamped) clamps += ClampEvent("iso", "${iso.requested}", "${iso.value}", "${isoRange.toList()}")
        val exp = ManualMath.clampExposure(expRange, caps.maxFrameDurationNs, s.exposureNs)
        if (exp.clamped) {
            val ceiling = ManualMath.shutterCeilingNs(expRange, caps.maxFrameDurationNs)
            clamps += ClampEvent("exposure_ns", "${exp.requested}", "${exp.value}", "[${expRange.first}, $ceiling]")
        }
        val preview = ManualMath.previewExposure(exp.value, iso.value, isoRange, expRange, caps.maxFrameDurationNs)
        val focus = s.focusDiopters?.let { d ->
            if (!caps.manualFocus) {
                clamps += ClampEvent("focus_diopters", "$d", "AF", "manual focus not available")
                null
            } else {
                val c = ManualMath.clampDiopters(caps.minFocusDiopters, d)
                if (c.clamped) clamps += ClampEvent("focus_diopters", "${c.requested}", "${c.value}", "[0, ${caps.minFocusDiopters}]")
                c.value
            }
        }
        val applied = ManualApplied(
            mode = mode,
            aeOff = true,
            stillIso = iso.value,
            stillExposureNs = exp.value,
            stillFrameDurationNs = ManualMath.stillFrameDurationNs(exp.value, caps.maxFrameDurationNs),
            previewIso = preview.iso,
            previewExposureNs = preview.exposureNs,
            previewFrameDurationNs = preview.frameDurationNs,
            previewShortfallEv = preview.shortfallEv,
            focusDiopters = focus,
            evIndex = 0,
            ev = 0.0,
            aeLock = false,
            awbLock = awbLock,
            awbMode = awb,
            clamps = clamps,
            withinRanges = true
        )
        return applied.copy(withinRanges = check(caps, applied))
    }

    /** True when every value [a] would apply is inside the range the camera reports for it. */
    fun check(caps: ManualCaps, a: ManualApplied): Boolean = violations(caps, a.stillIso, a.stillExposureNs, a.focusDiopters).isEmpty() &&
        violations(caps, a.previewIso, a.previewExposureNs, null).isEmpty() &&
        (a.evIndex == 0 || (caps.ev && ManualMath.clampEvIndex(caps.aeCompRange, caps.aeCompStep, a.evIndex).value == a.evIndex)) &&
        (!a.aeOff || caps.manualExposure) &&
        (!a.aeLock || caps.aeLockAvailable) &&
        (!a.awbLock || caps.awbLockAvailable) &&
        (a.awbMode == "AUTO" || a.awbMode in caps.awbPresets) &&
        (a.stillFrameDurationNs == null || caps.maxFrameDurationNs == null || a.stillFrameDurationNs <= caps.maxFrameDurationNs)

    /** Values outside their reported ranges (used for applied values and for what capture results report back). */
    fun violations(caps: ManualCaps, iso: Int?, exposureNs: Long?, focusDiopters: Float?): List<String> {
        val out = mutableListOf<String>()
        if (iso != null) {
            val r = caps.isoRange
            if (r == null || iso < r.first || iso > r.second) out += "iso $iso outside ${r?.toList()}"
        }
        if (exposureNs != null) {
            val r = caps.exposureRangeNs
            val ceiling = r?.let { ManualMath.shutterCeilingNs(it, caps.maxFrameDurationNs) }
            if (r == null || exposureNs < r.first || exposureNs > ceiling!!) {
                out += "exposure ${exposureNs}ns outside [${r?.first}, $ceiling]"
            }
        }
        if (focusDiopters != null) {
            val m = caps.minFocusDiopters
            if (m == null || focusDiopters < -FOCUS_SLACK || focusDiopters > m + FOCUS_SLACK) {
                out += "focus $focusDiopters D outside [0, $m]"
            }
        }
        return out
    }

    /** Capture results may report focus a hair off the request. */
    private const val FOCUS_SLACK = 0.01f
}

/** The capability report's "manual" section and the Info screen's summary (B14). */
data class ManualReport(
    val caps: ManualCaps? = null,
    val settings: ManualSettings = ManualSettings(),
    val applied: ManualApplied? = null,
    val clampEvents: List<ClampEvent> = emptyList(),
    val clampCount: Int = 0,
    /** Applied sets whose independent range re-check failed (should stay 0). */
    val appliedOutOfRange: Int = 0,
    val resultsChecked: Int = 0,
    val resultViolations: List<String> = emptyList(),
    val resultViolationCount: Int = 0,
    val gridOn: Boolean = true,
    val levelOn: Boolean = true,
    val histogramOn: Boolean = true,
    val histogramSource: String? = null,
    val levelSensor: String? = null
) {
    /** B14 gate (software half): every applied value inside its range, and results echo values inside them too. */
    val gatePassed: Boolean? get() = if (caps == null) null else appliedOutOfRange == 0 && resultViolationCount == 0

    fun record(a: ManualApplied, s: ManualSettings): ManualReport = copy(
        settings = s,
        applied = a,
        clampEvents = (clampEvents + a.clamps).takeLast(MAX_EVENTS),
        clampCount = clampCount + a.clamps.size,
        appliedOutOfRange = appliedOutOfRange + if (a.withinRanges) 0 else 1
    )

    fun recordResult(violations: List<String>): ManualReport = copy(
        resultsChecked = resultsChecked + 1,
        resultViolations = (resultViolations + violations).takeLast(MAX_EVENTS),
        resultViolationCount = resultViolationCount + violations.size
    )

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "mode" to (applied?.mode ?: settings.mode).name,
        "modes_available" to caps?.modes()?.map { it.name },
        "ranges" to caps?.toMap(),
        "settings" to settings.toMap(),
        "applied" to applied?.toMap(),
        "hidden" to caps?.hidden(),
        "pro_tools" to linkedMapOf(
            "grid" to gridOn,
            "level" to levelOn,
            "histogram" to histogramOn,
            "histogram_source" to histogramSource,
            "level_sensor" to levelSensor
        ),
        "clamp_count" to clampCount,
        "clamp_events" to clampEvents.map { it.toMap() },
        "applied_out_of_range" to appliedOutOfRange,
        "results_checked" to resultsChecked,
        "result_violation_count" to resultViolationCount,
        "result_violations" to resultViolations,
        "gate" to linkedMapOf("controls_within_reported_ranges" to gatePassed)
    )

    companion object {
        const val MAX_EVENTS = 20
    }
}
