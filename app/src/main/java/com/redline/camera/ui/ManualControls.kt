package com.redline.camera.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.redline.camera.manual.ManualApplied
import com.redline.camera.manual.ManualCaps
import com.redline.camera.manual.ManualMath
import com.redline.camera.manual.ManualSettings
import com.redline.camera.manual.ShootMode

const val MODE_ROW_TAG = "mode_row"
const val MANUAL_CONTROLS_TAG = "manual_controls"
const val GRID_TOGGLE_TAG = "grid_toggle"
const val LEVEL_TOGGLE_TAG = "level_toggle"
const val HISTOGRAM_TOGGLE_TAG = "histogram_toggle"
const val HISTOGRAM_TAG = "histogram"

fun modeTag(mode: ShootMode): String = "mode_${mode.name.lowercase()}"

/** Everything the B14 controls and overlays draw from. */
data class ManualUi(
    val caps: ManualCaps? = null,
    val settings: ManualSettings = ManualSettings(),
    val applied: ManualApplied? = null,
    /** What the camera is doing right now, e.g. "ISO 310 · 1/60 · AF". */
    val live: String? = null,
    val histogram: IntArray? = null,
    val tiltDeg: Float? = null,
    val gridOn: Boolean = true,
    val levelOn: Boolean = true,
    val histogramOn: Boolean = true
) {
    override fun equals(other: Any?): Boolean = other is ManualUi &&
        caps == other.caps &&
        settings == other.settings &&
        applied == other.applied &&
        live == other.live &&
        histogram.contentEquals(other.histogram) &&
        tiltDeg == other.tiltDeg &&
        gridOn == other.gridOn &&
        levelOn == other.levelOn &&
        histogramOn == other.histogramOn

    override fun hashCode(): Int = settings.hashCode() * 31 + histogram.contentHashCode()
}

/** Callbacks for the B14 controls; `dir` is -1 or +1 along each control's ladder. */
data class ManualActions(
    val onMode: (ShootMode) -> Unit = {},
    val onIso: (Int) -> Unit = {},
    val onShutter: (Int) -> Unit = {},
    val onFocus: (Int) -> Unit = {},
    val onEv: (Int) -> Unit = {},
    val onToggleAeLock: () -> Unit = {},
    val onToggleAwbLock: () -> Unit = {},
    val onCycleAwb: () -> Unit = {},
    val onToggleGrid: () -> Unit = {},
    val onToggleLevel: () -> Unit = {},
    val onToggleHistogram: () -> Unit = {}
)

private val LevelGreen = Color(0xFF66BB6A)
private val OverlayScrim = Color(0x99000000)
private val Line = Color(0x59FFFFFF)

/** Grid (rule of thirds), level (horizon line) and histogram, drawn over the 3:2 frame. They touch no camera request. */
@Composable
fun FrameOverlays(ui: ManualUi) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (ui.gridOn) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                for (i in 1..2) {
                    val x = size.width * i / 3f
                    val y = size.height * i / 3f
                    drawLine(Line, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
                    drawLine(Line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                }
            }
        }
        if (ui.levelOn) {
            val tilt = ui.tiltDeg
            Canvas(modifier = Modifier.fillMaxSize()) {
                val c = Offset(size.width / 2f, size.height / 2f)
                val tick = size.minDimension * 0.06f
                val gap = size.minDimension * 0.22f
                drawLine(Color.White, Offset(c.x - gap - tick, c.y), Offset(c.x - gap, c.y), strokeWidth = 2.dp.toPx())
                drawLine(Color.White, Offset(c.x + gap, c.y), Offset(c.x + gap + tick, c.y), strokeWidth = 2.dp.toPx())
                if (tilt != null) {
                    val color = if (ManualMath.isLevel(tilt)) LevelGreen else Color.White
                    rotate(tilt, c) {
                        drawLine(color, Offset(c.x - gap, c.y), Offset(c.x + gap, c.y), strokeWidth = 2.dp.toPx())
                    }
                }
            }
        }
        val hist = ui.histogram
        if (ui.histogramOn && hist != null && hist.isNotEmpty()) {
            Canvas(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .size(width = 120.dp, height = 48.dp)
                    .background(OverlayScrim)
                    .testTag(HISTOGRAM_TAG)
            ) {
                val peak = hist.maxOrNull()?.takeIf { it > 0 } ?: return@Canvas
                val w = size.width / hist.size
                hist.forEachIndexed { i, n ->
                    val h = size.height * n / peak
                    drawRect(Color.White, topLeft = Offset(i * w, size.height - h), size = androidx.compose.ui.geometry.Size(w, h))
                }
            }
        }
    }
}

/** Mode row (Auto / Av / M plus the overlay toggles) and the controls for the selected mode. */
@Composable
fun ManualBar(ui: ManualUi, actions: ManualActions, modifier: Modifier = Modifier) {
    val caps = ui.caps
    val s = ui.settings
    val mode = ui.applied?.mode ?: ShootMode.AUTO
    Column(modifier = modifier.background(OverlayScrim).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag(MODE_ROW_TAG),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            (caps?.modes() ?: listOf(ShootMode.AUTO)).forEach { m ->
                Pill(m.label, selected = m == mode, tag = modeTag(m)) { actions.onMode(m) }
            }
            Text("|", color = Color.Gray, fontSize = 12.sp)
            Pill("Grid", selected = ui.gridOn, tag = GRID_TOGGLE_TAG, onClick = actions.onToggleGrid)
            Pill("Level", selected = ui.levelOn, tag = LEVEL_TOGGLE_TAG, onClick = actions.onToggleLevel)
            Pill("Histo", selected = ui.histogramOn, tag = HISTOGRAM_TOGGLE_TAG, onClick = actions.onToggleHistogram)
        }
        if (caps != null && mode != ShootMode.AUTO) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).horizontalScroll(rememberScrollState()).testTag(MANUAL_CONTROLS_TAG),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (mode == ShootMode.AV) {
                    Text(caps.apertureLabel, fontSize = 13.sp, color = Color(0xFFBDBDBD))
                    if (caps.ev) {
                        Stepper("EV", ManualMath.evLabel(ui.applied?.ev ?: 0.0), actions.onEv)
                    }
                    if (caps.aeLockAvailable) Pill("AE-L", selected = s.aeLock, onClick = actions.onToggleAeLock)
                } else {
                    Stepper("ISO", "${ui.applied?.stillIso ?: s.iso}", actions.onIso)
                    Stepper("", ManualMath.shutterLabel(ui.applied?.stillExposureNs ?: s.exposureNs), actions.onShutter)
                    if (caps.manualFocus) {
                        val f = ui.applied?.focusDiopters
                        val label = if (f == null) "AF" else ManualMath.focusLabel(f, caps.minFocusDiopters, caps.metricFocus)
                        Stepper("MF", label, actions.onFocus)
                    }
                }
                if (caps.awbPresets.size > 1) Pill(ManualCaps.awbLabel(ui.applied?.awbMode ?: "AUTO"), onClick = actions.onCycleAwb)
                if (caps.awbLockAvailable) Pill("AWB-L", selected = s.awbLock, onClick = actions.onToggleAwbLock)
            }
            if (ui.applied?.previewLimited == true) {
                val short = ui.applied.previewShortfallEv
                val note = if (short > 0.05) ", preview %.1f EV darker".format(short) else ""
                Text("Preview shutter held at 1/30 s (still uses the full time)$note", fontSize = 11.sp, color = Color(0xFFBDBDBD))
            }
        }
        ui.live?.let { Text(it, fontSize = 11.sp, color = Color(0xFFBDBDBD)) }
    }
}

@Composable
private fun Stepper(label: String, value: String, onStep: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Pill("‹") { onStep(-1) }
        Text(
            if (label.isEmpty()) value else "$label $value",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 6.dp)
        )
        Pill("›") { onStep(1) }
    }
}

@Composable
private fun Pill(text: String, selected: Boolean = false, tag: String? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val base = Modifier
        .border(1.dp, if (selected) Color(0xFFD32F2F) else Color(0x80FFFFFF), shape)
        .clickable(onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 4.dp)
    Text(
        text,
        fontSize = 13.sp,
        color = if (selected) Color(0xFFD32F2F) else Color.White,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = if (tag != null) base.testTag(tag) else base
    )
}
