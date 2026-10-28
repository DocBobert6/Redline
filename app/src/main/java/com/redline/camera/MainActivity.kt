package com.redline.camera

import android.content.Intent
import android.hardware.camera2.CameraManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.redline.camera.capability.CapabilityProbe
import com.redline.camera.capability.FactoryCalibration
import com.redline.camera.ui.CapabilityScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val cameras = runCatching { CapabilityProbe(this).probeAll() }.getOrDefault(emptyList())
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        val calibrations = cameras.mapNotNull { c ->
            val candidates = (listOf(c.id) + c.physicalIds).mapNotNull { id ->
                runCatching { FactoryCalibration.read(c.id, manager.getCameraCharacteristics(id), id) }.getOrNull()
            }
            FactoryCalibration.pick(candidates)?.let { c.id to it }
        }.toMap()
        setContent {
            CapabilityScreen(cameras, calibrations) { cal -> share(cal) }
        }
    }

    private fun share(cal: FactoryCalibration) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, "redline_camera2_${cal.cameraId}.json")
            putExtra(Intent.EXTRA_TEXT, cal.toJson())
        }
        startActivity(Intent.createChooser(send, "Share calibration"))
    }
}
