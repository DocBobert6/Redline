package com.redline.camera.ui

import android.content.res.Configuration
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.redline.camera.capability.Dim
import com.redline.camera.viewfinder.PreviewTextureView
import com.redline.camera.viewfinder.ViewfinderMetrics
import com.redline.camera.viewfinder.ZoomPlan
import com.redline.camera.viewfinder.ZoomStop
import kotlin.math.abs

const val VIEWFINDER_TAG = "viewfinder"
const val FRAME_TAG = "viewfinder_frame"
const val ZOOM_RING_TAG = "zoom_ring"
const val ZOOM_NOTE_TAG = "zoom_note"
const val OPEN_CAPABILITY_TAG = "open_capability"
const val SHUTTER_TAG = "shutter"
const val RAW_TOGGLE_TAG = "raw_toggle"
const val LOOK_BADGE_TAG = "look_badge"

fun zoomStopTag(mm: Int): String = "zoom_stop_$mm"

private val Accent = Color(0xFFD32F2F)
private val Scrim = Color(0x99000000)

/**
 * The main screen (B12): a letterboxed 3:2 Camera2 preview (2:3 when held upright), a zoom ring with
 * click stops and honest labels, a small debug overlay with first-frame time and preview fps, and a
 * button to the capability screen. B14 adds the grid, level and histogram overlays and the Auto / Av / M
 * controls.
 */
@Composable
fun ViewfinderScreen(
    plan: ZoomPlan?,
    selectedStop: Int,
    onSelectStop: (Int) -> Unit,
    previewSize: Dim?,
    sensorOrientation: Int,
    textureListener: TextureView.SurfaceTextureListener,
    metrics: ViewfinderMetrics?,
    status: String?,
    needsPermission: Boolean,
    onRequestPermission: () -> Unit,
    onOpenCapability: () -> Unit,
    onShutter: () -> Unit = {},
    shutterEnabled: Boolean = false,
    rawAvailable: Boolean = false,
    rawOn: Boolean = false,
    onToggleRaw: () -> Unit = {},
    captureText: String? = null,
    manualUi: ManualUi = ManualUi(),
    manualActions: ManualActions = ManualActions(),
    onPreviewView: (TextureView) -> Unit = {},
    /** B20: shown while the camera renders through the GPU look-preview LUT. */
    lookBadge: String? = null
) {
    val portrait = LocalConfiguration.current.orientation != Configuration.ORIENTATION_LANDSCAPE
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent)) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black).testTag(VIEWFINDER_TAG)) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .aspectRatio(if (portrait) 2f / 3f else 3f / 2f)
                        .clipToBounds()
                        .testTag(FRAME_TAG)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            PreviewTextureView(ctx).also {
                                it.surfaceTextureListener = textureListener
                                onPreviewView(it)
                            }
                        },
                        update = { it.configure(previewSize, sensorOrientation) },
                        modifier = Modifier.fillMaxSize()
                    )
                    FrameOverlays(manualUi)
                    if (lookBadge != null) {
                        Text(
                            lookBadge,
                            fontSize = 10.sp,
                            modifier = Modifier.align(
                                Alignment.TopStart
                            ).padding(6.dp).background(Scrim).padding(2.dp).testTag(LOOK_BADGE_TAG)
                        )
                    }
                    if (status != null || needsPermission) {
                        Column(
                            modifier = Modifier.align(Alignment.Center).padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            status?.let { Text(it) }
                            if (needsPermission) {
                                Button(onClick = onRequestPermission, modifier = Modifier.padding(top = 8.dp)) { Text("Allow camera") }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().safeDrawingPadding().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        metrics?.overlayText() ?: "starting...",
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f).background(Scrim).padding(4.dp)
                    )
                    OutlinedButton(onClick = onOpenCapability, modifier = Modifier.padding(start = 8.dp).testTag(OPEN_CAPABILITY_TAG)) {
                        Text("Info")
                    }
                }
                Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding()) {
                    captureText?.let {
                        Text(it, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().background(Scrim).padding(4.dp))
                    }
                    ManualBar(manualUi, manualActions, modifier = Modifier.fillMaxWidth())
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (rawAvailable) {
                            OutlinedButton(onClick = onToggleRaw, modifier = Modifier.testTag(RAW_TOGGLE_TAG)) {
                                Text(if (rawOn) "RAW on" else "RAW off", color = if (rawOn) Accent else Color.White)
                            }
                        } else {
                            Spacer(modifier = Modifier.size(1.dp))
                        }
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(if (shutterEnabled) Color.White else Color.Gray)
                                .border(4.dp, Accent, CircleShape)
                                .clickable(enabled = shutterEnabled, onClick = onShutter)
                                .testTag(SHUTTER_TAG)
                        )
                        Spacer(modifier = Modifier.size(width = 88.dp, height = 1.dp))
                    }
                    if (plan != null) {
                        ZoomRing(plan = plan, selected = selectedStop, onSelect = onSelectStop, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

/** Click stops 24* / 28 / 35 / 50 / 70: tap a stop, or drag along the ring; each stop passed gives a haptic tick. */
@Composable
fun ZoomRing(plan: ZoomPlan, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    val stepPx = with(LocalDensity.current) { STEP_DP.dp.toPx() }
    val current by rememberUpdatedState(selected)
    val select by rememberUpdatedState(onSelect)
    val drag = remember { mutableFloatStateOf(0f) }
    val stops = plan.stops
    Column(
        modifier = modifier
            .background(Scrim)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .pointerInput(plan) {
                detectHorizontalDragGestures(onDragEnd = { drag.floatValue = 0f }, onDragCancel = { drag.floatValue = 0f }) { _, dx ->
                    drag.floatValue += dx
                    if (abs(drag.floatValue) >= stepPx) {
                        val i = stops.indexOfFirst { it.targetMm == current }
                        // Dragging left moves to longer focal lengths, like turning a zoom ring.
                        val next = (i + if (drag.floatValue < 0) 1 else -1).coerceIn(0, stops.lastIndex)
                        drag.floatValue = 0f
                        if (next != i && next >= 0) {
                            select(stops[next].targetMm)
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    }
                }
            }
            .testTag(ZOOM_RING_TAG)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            stops.forEach { s ->
                StopMark(s, s.targetMm == selected) {
                    if (s.targetMm != selected) {
                        select(s.targetMm)
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                }
            }
        }
        val cur = plan.stop(selected)
        if (cur != null) {
            Text(
                "${cur.focalNote} · ${cur.resolutionNote}",
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag(ZOOM_NOTE_TAG)
            )
        }
        plan.footnotes.forEach { Text(it, fontSize = 11.sp, color = Color(0xFFBDBDBD)) }
        Spacer(modifier = Modifier.padding(bottom = 2.dp))
    }
}

@Composable
private fun StopMark(stop: ZoomStop, selected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp).testTag(zoomStopTag(stop.targetMm))
    ) {
        Text(
            stop.label,
            fontSize = 18.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) Accent else Color.White
        )
        Text(stop.badge, fontSize = 10.sp, color = Color(0xFFBDBDBD))
    }
}

private const val STEP_DP = 40
