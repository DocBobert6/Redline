package com.redline.camera.capability

/**
 * Minimal JSON reader, the counterpart of [Json]. Pure Kotlin so cached files and the quirks
 * table parse the same way in unit tests as on the device (android.jar's org.json is a stub off-device).
 *
 * Objects become LinkedHashMap<String, Any?>, arrays List<Any?>, integers Long, other numbers Double.
 */
object JsonParse {
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        if (!p.atEnd()) throw IllegalArgumentException("Trailing data at ${p.pos}")
        return v
    }

    /** Parses and returns null instead of throwing on malformed input. */
    fun parseOrNull(text: String?): Any? = if (text == null) null else runCatching { parse(text) }.getOrNull()

    private class Parser(private val s: String) {
        var pos = 0

        fun atEnd(): Boolean = pos >= s.length

        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun value(): Any? {
            if (atEnd()) throw IllegalArgumentException("Unexpected end")
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("Unexpected '$c' at $pos")
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) throw IllegalArgumentException("Bad literal at $pos")
            pos += word.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            pos++
            skipWs()
            if (peek() == '}') {
                pos++
                return out
            }
            while (true) {
                skipWs()
                if (peek() != '"') throw IllegalArgumentException("Expected key at $pos")
                val k = str()
                skipWs()
                expect(':')
                skipWs()
                out[k] = value()
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return out
                    }
                    else -> throw IllegalArgumentException("Expected , or } at $pos")
                }
            }
        }

        private fun arr(): List<Any?> {
            val out = ArrayList<Any?>()
            pos++
            skipWs()
            if (peek() == ']') {
                pos++
                return out
            }
            while (true) {
                skipWs()
                out.add(value())
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return out
                    }
                    else -> throw IllegalArgumentException("Expected , or ] at $pos")
                }
            }
        }

        private fun str(): String {
            expect('"')
            val b = StringBuilder()
            while (true) {
                if (atEnd()) throw IllegalArgumentException("Unterminated string")
                val c = s[pos++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> {
                        if (atEnd()) throw IllegalArgumentException("Bad escape")
                        when (val e = s[pos++]) {
                            '"', '\\', '/' -> b.append(e)
                            'b' -> b.append('\b')
                            'f' -> b.append('\u000C')
                            'n' -> b.append('\n')
                            'r' -> b.append('\r')
                            't' -> b.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw IllegalArgumentException("Bad unicode escape")
                                b.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("Bad escape \\$e")
                        }
                    }
                    else -> b.append(c)
                }
            }
        }

        private fun num(): Number {
            val start = pos
            if (peek() == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            val t = s.substring(start, pos)
            return if (t.any { it == '.' || it == 'e' || it == 'E' }) t.toDouble() else t.toLongOrNull() ?: t.toDouble()
        }

        private fun peek(): Char? = if (atEnd()) null else s[pos]

        private fun expect(c: Char) {
            if (peek() != c) throw IllegalArgumentException("Expected '$c' at $pos")
            pos++
        }
    }
}

/** Typed accessors for parsed JSON maps. */
internal fun Map<*, *>.str(key: String): String? = this[key] as? String

internal fun Map<*, *>.int(key: String): Int? = (this[key] as? Number)?.toInt()

internal fun Map<*, *>.double(key: String): Double? = (this[key] as? Number)?.toDouble()

internal fun Map<*, *>.bool(key: String): Boolean? = this[key] as? Boolean

internal fun Map<*, *>.list(key: String): List<*> = this[key] as? List<*> ?: emptyList<Any?>()

internal fun Map<*, *>.obj(key: String): Map<*, *>? = this[key] as? Map<*, *>
