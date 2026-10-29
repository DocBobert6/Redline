package com.redline.camera.capability

enum class StillPath { RAW_PLUS_JPEG, JPEG_ONLY }

enum class DemotionSource { PROFILE, QUIRK, COMBINATION, SELF_TEST }

/** One default that was stepped down, with the reason shown to the user and written to the report. */
data class Demotion(val feature: String, val from: String, val to: String, val reason: String, val source: DemotionSource) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "feature" to feature,
        "from" to from,
        "to" to to,
        "reason" to reason,
        "source" to source.name
    )

    override fun toString(): String = "$feature: $from -> $to ($reason)"
}

/** The defaults Redline actually uses on this phone after detection, checks, self-test and quirks. */
data class EffectiveDefaults(
    val cameraId: String?,
    val detectedTier: CapabilityTier,
    val tier: CapabilityTier,
    val stillPath: StillPath,
    val videoFps: Int,
    val previewStabilization: Boolean,
    val stabilized60fps: Boolean
) {
    /**
     * Whether the viewfinder starts with preview stabilization on. [previewStabilization] says it is
     * available (detected, verified, not demoted); it is still off by default, because on the baseline
     * device profile it stretched single preview frames to ~50 ms and failed the B12 30 fps gate,
     * while the plain preview held 30 fps with no drops. It is offered as an opt-in setting instead.
     */
    val previewStabilizationOnByDefault: Boolean
        get() = previewStabilization && FeaturePolicy.PREVIEW_STABILIZATION_ON_BY_DEFAULT

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "camera_id" to cameraId,
        "detected_tier" to detectedTier.name,
        "tier" to tier.name,
        "still_path" to stillPath.name,
        "video_fps" to videoFps,
        "preview_stabilization" to previewStabilization,
        "preview_stabilization_on_by_default" to previewStabilizationOnByDefault,
        "stabilized_60fps" to stabilized60fps
    )
}

data class PolicyResult(val defaults: EffectiveDefaults, val demotions: List<Demotion>, val notes: List<String>)

/**
 * Demotion rules (PLAN 4.2/4.3). Pure: maps what was detected, verified and measured to the
 * effective defaults. Every step down records a human-readable reason.
 *
 * - RAW off (no capability, quirk, unsupported combination, failed RAW self-test) -> JPEG-only path.
 * - 24p fails (no fixed range, quirk, unsupported combination, failed 24 fps self-test) -> 30p.
 * - Preview stabilization unsupported -> plain preview.
 * - Tier: Basic / Pro / Pro+ from [TierRules]; a Pro camera whose RAW path is demoted runs as Basic.
 *
 * A skipped self-test check (no permission, camera busy) does not demote: it is retried next launch.
 * A combination that is UNKNOWN does not demote a detected single feature, but an unverified
 * flagship combination (stabilized 60 fps) is never offered.
 */
object FeaturePolicy {
    const val VIDEO_FPS_DEFAULT = 24
    const val VIDEO_FPS_FALLBACK = 30

    /** Preview stabilization is opt-in: available features are offered, but only gate-passing ones default on. */
    const val PREVIEW_STABILIZATION_ON_BY_DEFAULT = false

    /** The camera Redline defaults to: the first back-facing camera, else the first camera. */
    fun defaultCamera(profile: DeviceProfile?): CameraProfile? =
        profile?.cameras?.firstOrNull { it.facing == "BACK" } ?: profile?.cameras?.firstOrNull()

    fun evaluate(
        profile: DeviceProfile?,
        combos: List<ComboResult>,
        selfTest: SelfTestReport?,
        quirks: Map<String, String> = emptyMap()
    ): PolicyResult {
        val cam = defaultCamera(profile)
        val notes = mutableListOf<String>()
        if (cam == null) {
            notes += "No camera found: JPEG-only Basic defaults"
            val basic = CapabilityTier.BASIC
            val d = EffectiveDefaults(null, basic, basic, StillPath.JPEG_ONLY, VIDEO_FPS_FALLBACK, false, false)
            return PolicyResult(d, emptyList(), notes)
        }
        val demotions = mutableListOf<Demotion>()
        val comboByKey = combos.associateBy { it.key }

        fun comboBlock(vararg keys: String): Pair<String, DemotionSource>? = keys.firstNotNullOfOrNull { k ->
            comboByKey[k]?.takeIf { it.status == ComboStatus.UNSUPPORTED }?.let {
                "${it.label} unsupported, ${it.source.label}: ${it.reason}" to DemotionSource.COMBINATION
            }
        }

        fun selfTestBlock(key: String): Pair<String, DemotionSource>? {
            val c = selfTest?.check(key)?.takeIf { it.status == CheckStatus.FAIL } ?: return null
            return "self-test ${c.label} failed: ${c.reason}" to DemotionSource.SELF_TEST
        }

        fun quirkBlock(key: String): Pair<String, DemotionSource>? {
            val note = quirks[key] ?: return null
            return "quirks table: ${note.ifEmpty { "disabled" }}" to DemotionSource.QUIRK
        }

        // RAW still path.
        val rawDetected = cam.has("RAW") && "RAW_SENSOR" in cam.formats
        var stillPath = if (rawDetected) StillPath.RAW_PLUS_JPEG else StillPath.JPEG_ONLY
        if (rawDetected) {
            val block = quirkBlock(FeatureKeys.RAW)
                ?: comboBlock(ComboKeys.PREVIEW_RAW, ComboKeys.PREVIEW_YUV_RAW)
                ?: selfTestBlock(SelfTestKeys.RAW)
            if (block != null) {
                stillPath = StillPath.JPEG_ONLY
                demotions += Demotion(FeatureKeys.RAW, "RAW + JPEG", "JPEG only", block.first, block.second)
            }
        } else {
            notes += "No RAW capability: JPEG-only path"
        }
        comboByKey[ComboKeys.PREVIEW_JPEG]?.takeIf { it.status == ComboStatus.UNSUPPORTED }?.let {
            notes += "Preview + JPEG reported unsupported (${it.reason}); JPEG remains the floor path"
        }

        // Video frame rate.
        var videoFps = VIDEO_FPS_DEFAULT
        val fixed24 = cam.fpsRanges.any { it.first == 24 && it.second == 24 }
        val fpsBlock = if (!fixed24) {
            "no fixed [24,24] AE target range" to DemotionSource.PROFILE
        } else {
            quirkBlock(FeatureKeys.VIDEO_24P) ?: comboBlock(ComboKeys.VIDEO_24) ?: selfTestBlock(SelfTestKeys.FPS_24)
        }
        if (fpsBlock != null) {
            videoFps = VIDEO_FPS_FALLBACK
            demotions += Demotion(FeatureKeys.VIDEO_24P, "24p", "30p", fpsBlock.first, fpsBlock.second)
        }

        // Preview stabilization.
        var stab = true
        val stabBlock = if ("PREVIEW_STABILIZATION" !in cam.videoStabilization) {
            "PREVIEW_STABILIZATION mode not listed" to DemotionSource.PROFILE
        } else {
            quirkBlock(FeatureKeys.PREVIEW_STABILIZATION) ?: comboBlock(ComboKeys.PREVIEW_STABILIZATION)
        }
        if (stabBlock != null) {
            stab = false
            val feature = FeatureKeys.PREVIEW_STABILIZATION
            demotions += Demotion(feature, "preview stabilization", "plain preview", stabBlock.first, stabBlock.second)
        }

        // Stabilized 60 fps: an unlock, offered only when the combination was verified.
        var stab60 = false
        if (stab && cam.fpsRanges.any { it.second >= 60 }) {
            val combo = comboByKey[ComboKeys.PREVIEW_STAB_60]
            val block = quirkBlock(FeatureKeys.STABILIZED_60FPS) ?: comboBlock(ComboKeys.PREVIEW_STAB_60)
            when {
                block != null -> demotions += Demotion(FeatureKeys.STABILIZED_60FPS, "stabilized 60 fps", "off", block.first, block.second)
                combo?.verified == true -> stab60 = true
                else -> notes += "Stabilized 60 fps not offered: combination not verified"
            }
        }

        // Tier.
        val detected = TierRules.tierOf(facts(cam))
        var tier = detected
        if (detected != CapabilityTier.BASIC && stillPath == StillPath.JPEG_ONLY) {
            tier = CapabilityTier.BASIC
            val why = demotions.firstOrNull { it.feature == FeatureKeys.RAW }?.reason ?: "no RAW_SENSOR output"
            demotions += Demotion("tier", tierName(detected), tierName(tier), "RAW path off (PLAN 4.3): $why", DemotionSource.PROFILE)
        }

        val defaults = EffectiveDefaults(cam.id, detected, tier, stillPath, videoFps, stab, stab60)
        return PolicyResult(defaults, demotions, notes)
    }

    fun tierName(t: CapabilityTier): String = when (t) {
        CapabilityTier.BASIC -> "Basic"
        CapabilityTier.PRO -> "Pro"
        CapabilityTier.PRO_PLUS -> "Pro+"
    }

    /** CameraProfile -> the facts [TierRules] reads; physical lenses supply the equivalent focal lengths. */
    fun facts(c: CameraProfile): CameraFacts {
        val jpeg = c.outputSizes["JPEG"].orEmpty().mapNotNull { Dim.parse(it) }.maxByOrNull { it.area }
        return CameraFacts(
            id = c.id,
            facing = c.facing,
            hardwareLevel = c.hardwareLevel,
            hasRaw = c.has("RAW"),
            hasManualSensor = c.has("MANUAL_SENSOR"),
            hasManualPostProcessing = c.has("MANUAL_POST_PROCESSING"),
            hasTenBit = c.has("DYNAMIC_RANGE_TEN_BIT"),
            hasUltraHighRes = c.has("ULTRA_HIGH_RESOLUTION_SENSOR"),
            hasDepthOutput = c.has("DEPTH_OUTPUT"),
            physicalIds = if (c.has("LOGICAL_MULTI_CAMERA")) c.lenses.map { it.cameraId } else emptyList(),
            equivalentFocalLengthsMm = c.lenses.map { it.equivalentDiagonalMm },
            maxJpegWidth = jpeg?.width ?: 0,
            maxJpegHeight = jpeg?.height ?: 0,
            fpsRanges = c.fpsRanges
        )
    }
}
