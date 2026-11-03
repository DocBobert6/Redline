package com.redline.camera.capture

import android.graphics.Bitmap
import android.graphics.Gainmap
import android.os.Build
import androidx.annotation.RequiresApi
import com.redline.camera.nativebridge.RedlineNative
import java.nio.ByteBuffer

/**
 * B25: attaches Redline's gain map to the rendered still so Bitmap.compress(JPEG) writes an Ultra HDR JPEG through the
 * platform encoder (API 34+): the SDR base is encoded exactly as without the map, then the map (1 channel, quarter
 * resolution, ALPHA_8) with its metadata (ratioMin 1, ratioMax 2^headroom, gamma 1, epsilon 0) is appended (MPF + XMP).
 */
object UltraHdrEncoder {
    /** Sets the gain map on [bitmap]; [detach] removes it after the encode. */
    fun attach(bitmap: Bitmap, map: RedlineNative.GainMapBytes) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Api34.attach(bitmap, map)
        } else {
            error("Ultra HDR needs API 34")
        }
    }

    fun detach(bitmap: Bitmap) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) Api34.detach(bitmap)
    }

    /** The map's rows copied into a buffer with the ALPHA_8 bitmap's own row stride. */
    fun rows(map: RedlineNative.GainMapBytes, rowBytes: Int): ByteArray {
        if (rowBytes == map.stride) return map.bytes
        val out = ByteArray(rowBytes * map.height)
        for (y in 0 until map.height) System.arraycopy(map.bytes, y * map.stride, out, y * rowBytes, minOf(map.width, rowBytes))
        return out
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private object Api34 {
        fun attach(bitmap: Bitmap, map: RedlineNative.GainMapBytes) {
            val contents = Bitmap.createBitmap(map.width, map.height, Bitmap.Config.ALPHA_8)
            contents.copyPixelsFromBuffer(ByteBuffer.wrap(rows(map, contents.rowBytes)))
            val p = GainmapParams(map.headroomStops)
            val g = Gainmap(contents)
            g.setRatioMin(p.ratioMin, p.ratioMin, p.ratioMin)
            g.setRatioMax(p.ratioMax, p.ratioMax, p.ratioMax)
            g.setGamma(p.gamma, p.gamma, p.gamma)
            g.setEpsilonSdr(p.epsilonSdr, p.epsilonSdr, p.epsilonSdr)
            g.setEpsilonHdr(p.epsilonHdr, p.epsilonHdr, p.epsilonHdr)
            g.displayRatioForFullHdr = p.displayRatioForFullHdr
            g.minDisplayRatioForHdrTransition = p.minDisplayRatioForHdrTransition
            bitmap.gainmap = g
        }

        fun detach(bitmap: Bitmap) {
            bitmap.gainmap?.gainmapContents?.recycle()
            bitmap.gainmap = null
        }
    }
}
