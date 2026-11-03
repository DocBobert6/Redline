package com.redline.camera.depth

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * B30: the depth sidecar as a 16-bit RGB PNG (any viewer opens it; every channel is a full-precision map):
 *  R = relative inverse depth, normalised to the shot's min..max (larger = nearer; the raw range is in the metadata),
 *  G = person mask (segmenter probability), B = per-pixel depth confidence;
 * in the still's pixel orientation (the photo's EXIF orientation applies to both), plus a tEXt chunk [KEYWORD] holding
 * the JSON metadata (model, size, orientation, ranges, confidence cues). Pure JVM, so it is unit-tested on the host.
 */
object DepthPng {
    const val KEYWORD = "redline:depth"
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)

    /** [rgb16] is width x height x 3 values (0..65535 stored in Shorts), row-major. */
    fun encode(width: Int, height: Int, rgb16: ShortArray, metadataJson: String): ByteArray {
        require(width > 0 && height > 0 && rgb16.size >= width * height * 3) { "pixels mis-sized" }
        val bos = ByteArrayOutputStream(width * height * 3)
        write(bos, width, height, rgb16, metadataJson)
        return bos.toByteArray()
    }

    fun write(out: OutputStream, width: Int, height: Int, rgb16: ShortArray, metadataJson: String) {
        val o = DataOutputStream(out)
        o.write(SIGNATURE)
        val ihdr = ByteArrayOutputStream().also { b ->
            DataOutputStream(b).apply {
                writeInt(width)
                writeInt(height)
                writeByte(16) // bit depth
                writeByte(2) // colour type: RGB
                writeByte(0)
                writeByte(0)
                writeByte(0)
            }
        }.toByteArray()
        chunk(o, "IHDR", ihdr)
        chunk(o, "tEXt", KEYWORD.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) + metadataJson.toByteArray(Charsets.ISO_8859_1))
        // Scanlines with the Up filter (2): each byte minus the byte above (mod 256); big-endian 16-bit samples.
        val stride = width * 6
        val raw = ByteArray((stride + 1) * height)
        var prev = ByteArray(stride)
        val row = ByteArray(stride)
        for (y in 0 until height) {
            var i = y * width * 3
            for (x in 0 until stride step 2) {
                val v = rgb16[i++].toInt() and 0xFFFF
                row[x] = (v ushr 8).toByte()
                row[x + 1] = v.toByte()
            }
            val base = y * (stride + 1)
            raw[base] = 2
            for (x in 0 until stride) raw[base + 1 + x] = (row[x] - prev[x]).toByte()
            prev = row.copyOf()
        }
        val deflater = Deflater(6)
        deflater.setInput(raw)
        deflater.finish()
        val z = ByteArrayOutputStream(raw.size / 2)
        val buf = ByteArray(1 shl 16)
        while (!deflater.finished()) z.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        chunk(o, "IDAT", z.toByteArray())
        chunk(o, "IEND", ByteArray(0))
        o.flush()
    }

    /** The tEXt value stored under [KEYWORD], or null (for tests and the report check). */
    fun readText(png: ByteArray): String? {
        var p = SIGNATURE.size
        while (p + 8 <= png.size) {
            val len = ((png[p].toInt() and 0xFF) shl 24) or ((png[p + 1].toInt() and 0xFF) shl 16) or
                ((png[p + 2].toInt() and 0xFF) shl 8) or (png[p + 3].toInt() and 0xFF)
            val type = String(png, p + 4, 4, Charsets.ISO_8859_1)
            if (type == "tEXt" && p + 8 + len <= png.size) {
                val body = png.copyOfRange(p + 8, p + 8 + len)
                val z = body.indexOf(0)
                if (z > 0 && String(body, 0, z, Charsets.ISO_8859_1) == KEYWORD) {
                    return String(body, z + 1, body.size - z - 1, Charsets.ISO_8859_1)
                }
            }
            if (type == "IEND") break
            p += 12 + len
        }
        return null
    }

    private fun chunk(o: DataOutputStream, type: String, data: ByteArray) {
        val t = type.toByteArray(Charsets.ISO_8859_1)
        o.writeInt(data.size)
        o.write(t)
        o.write(data)
        val crc = CRC32()
        crc.update(t)
        crc.update(data)
        o.writeInt(crc.value.toInt())
    }
}
