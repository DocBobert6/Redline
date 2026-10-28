package com.redline.camera

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.redline.camera.capability.CapabilityProbe
import com.redline.camera.ui.CapabilityScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cameras = runCatching { CapabilityProbe(this).probeAll() }.getOrDefault(emptyList())
        setContent { CapabilityScreen(cameras) }
    }
}
