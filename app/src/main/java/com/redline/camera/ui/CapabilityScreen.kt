package com.redline.camera.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.redline.camera.BuildConfig
import com.redline.camera.capability.CameraFacts
import com.redline.camera.capability.CameraProfile
import com.redline.camera.capability.CapabilityState
import com.redline.camera.capability.CheckStatus
import com.redline.camera.capability.ComboStatus
import com.redline.camera.capability.DeviceProfile
import com.redline.camera.capability.FactoryCalibration
import com.redline.camera.capability.FeaturePolicy
import com.redline.camera.capability.TierRules
import com.redline.camera.capture.CaptureStats
import com.redline.camera.manual.ManualCaps
import com.redline.camera.manual.ManualMath

@Composable
fun CapabilityScreen(
    cameras: List<CameraFacts>,
    calibrations: Map<String, FactoryCalibration> = emptyMap(),
    onShareCalibration: (FactoryCalibration) -> Unit = {},
    profile: DeviceProfile? = null,
    onShareReport: () -> Unit = {},
    state: CapabilityState = CapabilityState(),
    onRunSelfTest: () -> Unit = {},
    onRepeatSelfTest: (() -> Unit)? = null,
    onToggleStabilization: (() -> Unit)? = null,
    onRunStress: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null
) {
    val policy = state.policy(profile)
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.safeDrawingPadding().padding(16.dp).testTag(LIST_TAG)) {
                item {
                    Text("Redline ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.headlineSmall)
                    Text("Capability probe", style = MaterialTheme.typography.bodyMedium)
                    if (onBack != null) {
                        OutlinedButton(onClick = onBack, modifier = Modifier.padding(top = 8.dp).testTag(BACK_TAG)) {
                            Text("Back to viewfinder")
                        }
                    }
                    if (profile != null) {
                        val e = profile.encoders
                        Text("${profile.manufacturer} ${profile.model}, Android API ${profile.sdkInt}")
                        Text("Encoders: HEVC Main10 ${e.hevcMain10}, AV1 ${e.av1}, APV ${e.apv}")
                        OutlinedButton(onClick = onShareReport, modifier = Modifier.padding(top = 8.dp)) {
                            Text("Share capability report")
                        }
                    }
                    val d = policy.defaults
                    val detected = if (d.tier != d.detectedTier) " (detected ${FeaturePolicy.tierName(d.detectedTier)})" else ""
                    Text(
                        "Capability tier ${FeaturePolicy.tierName(d.tier)}$detected",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                item {
                    SectionCard("Capture") {
                        val c = state.capture
                        Text("Shots ${c.shots}, failures ${c.failures}, DNG ${c.dngSaved}/${c.dngWanted}")
                        c.avgSaveMs?.let { Text("Shutter to saved: avg $it ms, max ${c.maxSaveMs} ms") }
                        c.last?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        if (c.stressRequested > 0) {
                            val raw = if (c.stressRaw) ", RAW on" else ""
                            val gate = gateWord(c.stressGatePassed)
                            Text("Stress ${c.stressDone}/${c.stressRequested}, ${c.stressFailures} failed$raw (gate: $gate)")
                        }
                        if (onRunStress != null) {
                            OutlinedButton(onClick = onRunStress, enabled = !c.stressRunning, modifier = Modifier.padding(top = 8.dp)) {
                                Text("Run ${CaptureStats.STRESS_RUNS}-capture stress test")
                            }
                            Text(
                                "Runs on the viewfinder; files are fully written, then deleted. Uses the current RAW setting.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                item {
                    SectionCard("Manual controls") {
                        val m = state.manual
                        val caps = m.caps
                        if (caps == null) {
                            Text("Ranges not read yet")
                        } else {
                            val mode = m.applied?.mode ?: m.settings.mode
                            Text("Mode ${mode.label}; offered ${caps.modes().joinToString { it.label }}")
                            Text("ISO ${caps.isoRange?.toList() ?: "not reported"}, shutter ${shutterRange(caps)}")
                            Text("Focus ${focusRange(caps)}, aperture ${caps.apertureLabel}")
                            Text("EV ${evRange(caps)}, AE lock ${caps.aeLockAvailable}, AWB lock ${caps.awbLockAvailable}")
                            val presets = caps.awbPresets.joinToString { ManualCaps.awbLabel(it) }
                            Text("WB presets ${presets.ifEmpty { "none" }}")
                            val outOfRange = m.resultViolationCount + m.appliedOutOfRange
                            Text("Clamps ${m.clampCount}, results checked ${m.resultsChecked}, out of range $outOfRange")
                            Text("Gate (every control within reported ranges): ${gateWord(m.gatePassed)}")
                            m.clampEvents.takeLast(3).forEach { Text("Clamped $it", style = MaterialTheme.typography.bodySmall) }
                            caps.hidden().forEach { (k, why) -> Text("$k: $why", style = MaterialTheme.typography.bodySmall) }
                        }
                        Text(
                            "Histogram: ${m.histogramSource ?: "-"}; level: ${m.levelSensor ?: "-"}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                item {
                    SectionCard("Self-test") {
                        val report = state.effectiveSelfTest()
                        if (report == null) {
                            Text(if (state.selfTestRunning) "Running..." else "Not run yet")
                        } else {
                            val summary = when {
                                report.passed -> "passed"
                                report.complete -> "failed"
                                else -> "incomplete"
                            }
                            val again = if (state.selfTestRunning) " (running again...)" else ""
                            Text("Camera ${report.cameraId ?: "-"}: $summary$again")
                            report.checks.forEach { c -> Text("${statusWord(c.status)} ${c.label}: ${c.reason}") }
                        }
                        state.repeatStatus?.let { Text("Repeat: $it") }
                        OutlinedButton(onClick = onRunSelfTest, enabled = !state.selfTestRunning, modifier = Modifier.padding(top = 8.dp)) {
                            Text("Run self-test again")
                        }
                        if (onRepeatSelfTest != null) {
                            OutlinedButton(onClick = onRepeatSelfTest, enabled = !state.selfTestRunning) {
                                Text("Run self-test 100x")
                            }
                        }
                    }
                }
                item {
                    SectionCard("Viewfinder") {
                        val v = state.viewfinder
                        if (v == null) {
                            Text("Not opened yet")
                        } else {
                            Text("First frame ${v.firstFrameMs?.let { "$it ms" } ?: "-"} (gate < 1000 ms: ${gateWord(v.startGatePassed)})")
                            Text(
                                "Preview ${v.previewFps?.let { "%.1f".format(it) } ?: "-"} fps, " +
                                    "min ${v.previewFpsMin?.let { "%.1f".format(it) } ?: "-"} (gate >= 30: ${gateWord(v.fpsGatePassed)})"
                            )
                            Text("Stream ${v.previewSize ?: "-"} cropped to 3:2, AE ${v.aeFpsRange?.toList() ?: "auto"}")
                            Text(
                                "Stabilization ${v.previewStabilization}, zoom via ${v.zoomApi ?: "-"}, profile cache ${v.profileCache ?: "-"}"
                            )
                            v.droppedFrames?.let {
                                Text("Dropped frames $it of ${v.framesCounted ?: 0}, worst interval ${v.worstFrameIntervalMs ?: "-"} ms")
                            }
                            v.status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                        if (onToggleStabilization != null) {
                            val next = v?.stabilizationOverride?.let { !it } ?: !policy.defaults.previewStabilizationOnByDefault
                            OutlinedButton(onClick = onToggleStabilization, modifier = Modifier.padding(top = 8.dp)) {
                                Text("Turn preview stabilization ${if (next) "on" else "off"} (default off)")
                            }
                        }
                        state.zoomPlan?.stops?.forEach { s ->
                            Text("${s.focalNote}: ${s.resolutionNote}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item {
                    SectionCard("Feature combinations") {
                        val combos = state.effectiveCombos()
                        if (state.combos == null) Text("Checking...")
                        combos.forEach { c ->
                            Text("${comboWord(c.status)} ${c.label} [${c.source.label}]")
                            Text(c.reason, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item {
                    SectionCard("Effective defaults") {
                        val d = policy.defaults
                        Text("Still path ${d.stillPath}, video ${d.videoFps} fps")
                        Text(
                            "Preview stabilization available ${d.previewStabilization}, " +
                                "on by default ${d.previewStabilizationOnByDefault}, stabilized 60 fps ${d.stabilized60fps}"
                        )
                        val q = state.quirks
                        Text("Quirks table ${q.source} v${q.version}, ${state.quirkDisables.size} feature(s) disabled")
                        if (state.forced.isNotEmpty()) Text("Forced failures (debug): ${state.forced.sorted().joinToString()}")
                        Text("Demotions", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                        if (policy.demotions.isEmpty()) Text("None")
                        policy.demotions.forEach { Text("${it.feature}: ${it.from} -> ${it.to}. ${it.reason}") }
                        policy.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                items(cameras) { c ->
                    CameraCard(c, calibrations[c.id], onShareCalibration, profile?.cameras?.firstOrNull { it.id == c.id })
                }
            }
        }
    }
}

const val LIST_TAG = "capability_list"
const val BACK_TAG = "back_to_viewfinder"

private fun gateWord(ok: Boolean?): String = when (ok) {
    null -> "not measured"
    true -> "pass"
    false -> "FAIL"
}

private fun shutterRange(c: ManualCaps): String {
    val l = c.shutterLadder
    if (l.isEmpty()) return "not reported"
    return "${ManualMath.shutterLabel(l.first())} to ${ManualMath.shutterLabel(l.last())}"
}

private fun evRange(c: ManualCaps): String {
    val l = c.evLadder
    if (l.isEmpty()) return "not offered"
    return "${ManualMath.evLabel(l.first().ev)} to ${ManualMath.evLabel(l.last().ev)}"
}

private fun focusRange(c: ManualCaps): String {
    val m = c.minFocusDiopters ?: return "not reported"
    if (!c.manualFocus) return "fixed or auto only ($m D)"
    return "∞ to ${ManualMath.focusLabel(m, m, c.metricFocus)} ($m D, ${c.minFocusSource}, ${c.focusCalibration ?: "?"})"
}

private fun statusWord(s: CheckStatus): String = when (s) {
    CheckStatus.PASS -> "PASS"
    CheckStatus.FAIL -> "FAIL"
    CheckStatus.SKIPPED -> "SKIPPED"
}

private fun comboWord(s: ComboStatus): String = when (s) {
    ComboStatus.SUPPORTED -> "Supported"
    ComboStatus.UNSUPPORTED -> "Unsupported"
    ComboStatus.UNKNOWN -> "Unknown"
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.padding(top = 12.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun CameraCard(c: CameraFacts, cal: FactoryCalibration?, onShare: (FactoryCalibration) -> Unit, full: CameraProfile? = null) {
    Card(modifier = Modifier.padding(top = 12.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Camera ${c.id} (${c.facing}) - tier ${TierRules.tierOf(c)}", style = MaterialTheme.typography.titleMedium)
            Text("Level ${c.hardwareLevel}, RAW ${c.hasRaw}, manual ${c.hasManualSensor}, 10-bit ${c.hasTenBit}")
            Text("Ultra-high-res ${c.hasUltraHighRes}, depth ${c.hasDepthOutput}, true 24p ${TierRules.supportsTrue24p(c)}")
            Text("Max JPEG ${c.maxJpegWidth}x${c.maxJpegHeight}")
            Text("Equivalent focal ${c.equivalentFocalLengthsMm.joinToString { "%.1fmm".format(it) }}")
            if (c.physicalIds.isNotEmpty()) Text("Physical cameras ${c.physicalIds.joinToString()}")
            full?.lenses?.forEach { l ->
                val f = l.apertures.minOrNull()?.let { " f/$it" }.orEmpty()
                val ois = if (l.hasOis) ", OIS" else ""
                val focus = if (l.fixedFocus == true) ", fixed focus" else ""
                Text(
                    "Lens ${l.cameraId}: ${"%.1f".format(l.equivalentDiagonalMm)}mm eq " +
                        "(3:2 ${"%.1f".format(l.equivalent32Mm)}mm)$f$ois$focus"
                )
            }
            full?.let { p ->
                p.isoRange?.let { Text("ISO ${it.first}-${it.second}") }
                if (p.dynamicRangeProfiles.isNotEmpty()) Text("Dynamic range ${p.dynamicRangeProfiles.joinToString()}")
                if (p.extensions.isNotEmpty()) Text("Extensions ${p.extensions.joinToString()}")
            }
            val hasCal = cal?.hasColorCalibration == true
            val via = if (cal != null && cal.sourceCameraId != c.id) " (from physical camera ${cal.sourceCameraId})" else ""
            Text("Factory colour calibration ${if (hasCal) "yes" else "no"}$via")
            if (cal != null) {
                OutlinedButton(onClick = { onShare(cal) }, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Share calibration")
                }
            }
        }
    }
}
