package com.redline.camera.depth

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

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

    /** B31: a decoded sidecar: [rgb16] = width x height x 3 values (0..65535), row-major, and the metadata text. */
    class Decoded(val width: Int, val height: Int, val rgb16: IntArray, val metadataJson: String?) {
        /** Channel [c] (0 depth, 1 mask, 2 confidence) as 0..1 floats. */
        fun channel(c: Int): FloatArray = FloatArray(width * height) { i -> rgb16[3 * i + c] / 65535f }
    }

    /** B31: reads a sidecar back (16-bit RGB, non-interlaced, any filter); throws on anything else. */
    fun decode(png: ByteArray): Decoded {
        require(png.size > SIGNATURE.size && png.copyOfRange(0, SIGNATURE.size).contentEquals(SIGNATURE)) { "not a PNG" }
        var p = SIGNATURE.size
        var w = 0
        var h = 0
        val idat = ByteArrayOutputStream()
        while (p + 8 <= png.size) {
            val len = be32(png, p)
            val type = String(png, p + 4, 4, Charsets.ISO_8859_1)
            require(p + 12 + len <= png.size) { "truncated $type" }
            if (type == "IHDR") {
                w = be32(png, p + 8)
                h = be32(png, p + 12)
                require(png[p + 16].toInt() == 16 && png[p + 17].toInt() == 2 && png[p + 20].toInt() == 0) { "not 16-bit RGB" }
            } else if (type == "IDAT") {
                idat.write(png, p + 8, len)
            } else if (type == "IEND") {
                break
            }
            p += 12 + len
        }
        require(w > 0 && h > 0) { "no IHDR" }
        val stride = w * 6
        val raw = ByteArray((stride + 1) * h)
        val inf = Inflater()
        inf.setInput(idat.toByteArray())
        var off = 0
        while (off < raw.size && !inf.finished()) {
            val n = inf.inflate(raw, off, raw.size - off)
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
            off += n
        }
        inf.end()
        require(off == raw.size) { "short image data ($off of ${raw.size})" }
        val out = IntArray(w * h * 3)
        var prev = ByteArray(stride)
        val cur = ByteArray(stride)
        for (y in 0 until h) {
            val base = y * (stride + 1)
            val f = raw[base].toInt()
            for (x in 0 until stride) {
                val v = raw[base + 1 + x].toInt() and 0xFF
                val a = if (x >= 6) cur[x - 6].toInt() and 0xFF else 0
                val b = prev[x].toInt() and 0xFF
                val c = if (x >= 6) prev[x - 6].toInt() and 0xFF else 0
                cur[x] = when (f) {
                    0 -> v
                    1 -> v + a
                    2 -> v + b
                    3 -> v + ((a + b) ushr 1)
                    4 -> v + paeth(a, b, c)
                    else -> error("bad filter $f")
                }.toByte()
            }
            var i = y * w * 3
            for (x in 0 until stride step 2) out[i++] = ((cur[x].toInt() and 0xFF) shl 8) or (cur[x + 1].toInt() and 0xFF)
            prev = cur.copyOf()
        }
        return Decoded(w, h, out, readText(png))
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = kotlin.math.abs(p - a)
        val pb = kotlin.math.abs(p - b)
        val pc = kotlin.math.abs(p - c)
        return if (pa <= pb && pa <= pc) {
            a
        } else if (pb <= pc) {
            b
        } else {
            c
        }
    }

    private fun be32(b: ByteArray, p: Int): Int = ((b[p].toInt() and 0xFF) shl 24) or ((b[p + 1].toInt() and 0xFF) shl 16) or
        ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)

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
