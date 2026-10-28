package com.redline.camera.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.redline.camera.BuildConfig
import com.redline.camera.capability.CameraFacts
import com.redline.camera.capability.TierRules

@Composable
fun CapabilityScreen(cameras: List<CameraFacts>) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.padding(16.dp)) {
                item {
                    Text("Redline ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.headlineSmall)
                    Text("Capability probe", style = MaterialTheme.typography.bodyMedium)
                }
                items(cameras) { c -> CameraCard(c) }
            }
        }
    }
}

@Composable
private fun CameraCard(c: CameraFacts) {
    Card(modifier = Modifier.padding(top = 12.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Camera ${c.id} (${c.facing}) - tier ${TierRules.tierOf(c)}", style = MaterialTheme.typography.titleMedium)
            Text("Level ${c.hardwareLevel}, RAW ${c.hasRaw}, manual ${c.hasManualSensor}, 10-bit ${c.hasTenBit}")
            Text("Ultra-high-res ${c.hasUltraHighRes}, depth ${c.hasDepthOutput}, true 24p ${TierRules.supportsTrue24p(c)}")
            Text("Max JPEG ${c.maxJpegWidth}x${c.maxJpegHeight}")
            Text("Equivalent focal ${c.equivalentFocalLengthsMm.joinToString { "%.1fmm".format(it) }}")
            if (c.physicalIds.isNotEmpty()) Text("Physical cameras ${c.physicalIds.joinToString()}")
        }
    }
}
