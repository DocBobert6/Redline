package com.redline.camera.capability

import com.redline.camera.capture.CaptureStats
import com.redline.camera.colour.ColourPipelineReport
import com.redline.camera.look.LookPreviewReport
import com.redline.camera.manual.ManualReport
import com.redline.camera.viewfinder.ViewfinderMetrics
import com.redline.camera.viewfinder.ZoomPlan

/**
 * Everything B11 adds on top of the static [DeviceProfile]: combination results, the self-test,
 * the matched quirks, and debug-forced failures. Pure, so the screen and the shared report derive
 * the same effective defaults from it.
 */
data class CapabilityState(
    /** Null while the checks are still running. */
    val combos: List<ComboResult>? = null,
    val selfTest: SelfTestReport? = null,
    val selfTestRunning: Boolean = false,
    val quirks: QuirksTable = QuirksTable.EMPTY,
    /** Feature key -> note, for quirk entries matching this phone. */
    val quirkDisables: Map<String, String> = emptyMap(),
    /** Debug-only forced failures ([DebugOverrides]). Always empty in release builds. */
    val forced: Set<String> = emptySet(),
    /** Repeat-run progress, e.g. "37/40 runs passed". */
    val repeatStatus: String? = null,
    /** B12 viewfinder measurements (first frame, preview fps, stream size, AE range); null before it opens. */
    val viewfinder: ViewfinderMetrics? = null,
    /** B12 zoom stops for the default camera. */
    val zoomPlan: ZoomPlan? = null,
    /** B13 still-capture counters and the 500-shot stress run. */
    val capture: CaptureStats = CaptureStats(),
    /** B14 manual controls: ranges read, mode, current values, clamp events and the range gate. */
    val manual: ManualReport = ManualReport(),
    /** B20 GPU look preview: availability (GLES 3, native module, LUT), self-check, LUT-pass timing and its gate. */
    val lookPreview: LookPreviewReport = LookPreviewReport(),
    /** B21 RAW colour pipeline: availability, profile, on-device self-check (parity, neutrals), per-shot timings. */
    val colourPipeline: ColourPipelineReport = ColourPipelineReport()
) {
    fun effectiveCombos(): List<ComboResult> = DebugOverrides.applyCombos(combos.orEmpty(), forced)

    fun effectiveSelfTest(): SelfTestReport? = DebugOverrides.applySelfTest(selfTest, forced)

    fun policy(profile: DeviceProfile?): PolicyResult =
        FeaturePolicy.evaluate(profile, effectiveCombos(), effectiveSelfTest(), quirkDisables)
}

object CapabilityReport {
    /** The B10 profile plus the B11 sections, B12 viewfinder, B13 capture, B14 manual controls, B20 look preview and B21 colour pipeline. */
    fun toMap(profile: DeviceProfile?, state: CapabilityState): Map<String, Any?> {
        val policy = state.policy(profile)
        val out = LinkedHashMap<String, Any?>(profile?.toMap().orEmpty())
        out["self_test"] = state.effectiveSelfTest()?.toMap()
        out["combinations"] = state.effectiveCombos().map { it.toMap() }
        out["effective_defaults"] = policy.defaults.toMap()
        out["demotions"] = policy.demotions.map { it.toMap() }
        out["policy_notes"] = policy.notes
        out["self_test_repeat"] = state.repeatStatus
        out["quirks"] = linkedMapOf(
            "source" to state.quirks.source,
            "version" to state.quirks.version,
            "disabled" to state.quirkDisables
        )
        out["viewfinder"] = (state.viewfinder ?: ViewfinderMetrics(status = "not opened")).toMap(state.zoomPlan)
        out["capture"] = state.capture.toMap()
        out["manual"] = state.manual.toMap()
        out["look_preview"] = state.lookPreview.toMap()
        out["colour_pipeline"] = state.colourPipeline.toMap()
        if (state.forced.isNotEmpty()) out["forced_failures"] = state.forced.sorted()
        return out
    }

    fun toJson(profile: DeviceProfile?, state: CapabilityState): String = Json.value(toMap(profile, state))
}
