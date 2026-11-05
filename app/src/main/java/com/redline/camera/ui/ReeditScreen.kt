package com.redline.camera.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.redline.camera.dof.DofMath
import kotlin.math.roundToInt

const val REEDIT_TAG = "dof_reedit_screen"
const val REEDIT_RENDER_TAG = "dof_reedit_render"
const val REEDIT_IMAGE_TAG = "dof_reedit_image"

/** B31: what the re-edit screen shows. [focusU], [focusV]: the focus point on the upright picture (0..1). */
data class ReeditUi(
    val shots: List<String> = emptyList(),
    val selected: String? = null,
    val preview: ImageBitmap? = null,
    val aperture: Float = DofMath.DEFAULT_APERTURE,
    val focusU: Double = 0.5,
    val focusV: Double = 0.5,
    val busy: Boolean = false,
    val status: String? = null
)

/**
 * B31: re-edit after capture (minimal): pick a recent shot that has its sharp copy and depth, tap a new focus point,
 * step the aperture, render. The result is saved as a new photo; the original is kept.
 */
@Composable
fun ReeditScreen(
    ui: ReeditUi,
    onSelect: (String) -> Unit,
    onApertureStep: (Int) -> Unit,
    onFocus: (u: Double, v: Double) -> Unit,
    onRender: () -> Unit,
    onBack: () -> Unit
) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize().testTag(REEDIT_TAG)) {
            Column(modifier = Modifier.safeDrawingPadding().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onBack) { Text("Back") }
                    Text("Re-edit depth of field", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp))
                }
                if (ui.shots.isEmpty()) {
                    Text("No recent shot with a stored sharp copy and depth yet.", modifier = Modifier.padding(top = 16.dp))
                    return@Column
                }
                LazyRow(modifier = Modifier.padding(vertical = 8.dp)) {
                    items(ui.shots) { b ->
                        val sel = b == ui.selected
                        OutlinedButton(onClick = { onSelect(b) }, modifier = Modifier.padding(end = 6.dp)) {
                            Text(b.removePrefix("RL_"), color = if (sel) Color(0xFFD32F2F) else Color.Unspecified)
                        }
                    }
                }
                ui.preview?.let { img ->
                    var box by remember { mutableStateOf(IntSize.Zero) }
                    val half = with(LocalDensity.current) { 8.dp.roundToPx() }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(img.width.toFloat() / img.height.coerceAtLeast(1))
                            .onSizeChanged { box = it }
                            .pointerInput(ui.selected) {
                                detectTapGestures { o ->
                                    if (size.width > 0 && size.height > 0) {
                                        onFocus(
                                            (o.x / size.width).toDouble().coerceIn(0.0, 1.0),
                                            (o.y / size.height).toDouble().coerceIn(0.0, 1.0)
                                        )
                                    }
                                }
                            }
                            .testTag(REEDIT_IMAGE_TAG)
                    ) {
                        Image(bitmap = img, contentDescription = "sharp copy", modifier = Modifier.fillMaxSize())
                        if (box.width > 0) {
                            Box(
                                modifier = Modifier
                                    .offset {
                                        IntOffset(
                                            (ui.focusU * box.width).roundToInt() - half,
                                            (ui.focusV * box.height).roundToInt() - half
                                        )
                                    }
                                    .size(16.dp)
                                    .border(2.dp, Color.White)
                                    .background(Color(0x55D32F2F))
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedButton(onClick = { onApertureStep(-1) }) { Text("‹") }
                    Text(
                        DofMath.label(ui.aperture),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                    OutlinedButton(onClick = { onApertureStep(1) }) { Text("›") }
                    Button(
                        onClick = onRender,
                        enabled = !ui.busy && ui.selected != null,
                        modifier = Modifier.padding(start = 12.dp).testTag(REEDIT_RENDER_TAG)
                    ) {
                        Text(if (ui.busy) "Rendering..." else "Render new photo")
                    }
                }
                ui.status?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
                Text(
                    "Tap the picture to move the focus point. The new photo keeps the shot's focus distance and is saved next to the original.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}
