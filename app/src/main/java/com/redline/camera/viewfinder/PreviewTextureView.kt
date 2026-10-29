package com.redline.camera.viewfinder

import android.content.Context
import android.graphics.Matrix
import android.hardware.display.DisplayManager
import android.view.Surface
import android.view.TextureView
import com.redline.camera.capability.Dim

/**
 * TextureView that keeps the camera picture upright and centre-cropped to its own bounds. Placed in a 3:2
 * (2:3 in portrait) box, the 4:3 preview stream is cropped to the 3:2 frame on screen. Re-applies the
 * transform on size changes and on display rotation changes (including 180 degree turns, which do not
 * recreate the activity).
 */
class PreviewTextureView(context: Context) : TextureView(context) {
    private var buffer: Dim? = null
    private var sensorOrientation: Int = 90

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (display?.displayId == displayId) applyTransform()
        }
    }

    fun configure(buffer: Dim?, sensorOrientation: Int) {
        if (buffer == this.buffer && sensorOrientation == this.sensorOrientation) return
        this.buffer = buffer
        this.sensorOrientation = sensorOrientation
        applyTransform()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyTransform()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.registerDisplayListener(displayListener, handler)
        applyTransform()
    }

    override fun onDetachedFromWindow() {
        (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.unregisterDisplayListener(displayListener)
        super.onDetachedFromWindow()
    }

    private fun displayRotationDegrees(): Int = when (display?.rotation ?: Surface.ROTATION_0) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    private fun applyTransform() {
        val b = buffer ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val t = PreviewTransform.compute(w, h, b, sensorOrientation, displayRotationDegrees(), fill = true)
        val m = Matrix()
        m.setScale(t.scaleX, t.scaleY, w / 2f, h / 2f)
        m.postRotate(t.rotationDeg, w / 2f, h / 2f)
        setTransform(m)
    }
}
