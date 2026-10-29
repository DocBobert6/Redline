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

@Composable
fun CapabilityScreen(
    cameras: List<CameraFacts>,
    calibrations: Map<String, FactoryCalibration> = emptyMap(),
    onShareCalibration: (FactoryCalibration) -> Unit = {},
    profile: DeviceProfile? = null,
    onShareReport: () -> Unit = {},
    state: CapabilityState = CapabilityState(),
    onRunSelfTest: () -> Unit = {},
    onRepeatSelfTest: (() -> Unit)? = null
) {
    val policy = state.policy(profile)
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.safeDrawingPadding().padding(16.dp).testTag(LIST_TAG)) {
                item {
                    Text("Redline ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.headlineSmall)
                    Text("Capability probe", style = MaterialTheme.typography.bodyMedium)
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
                        Text("Preview stabilization ${d.previewStabilization}, stabilized 60 fps ${d.stabilized60fps}")
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
