package com.appeeky.analytics

/**
 * Minimal JSON encoder and parser for the SDK's own payloads and persisted
 * state. It avoids org.json (an Android stub on the JVM, which makes unit
 * tests need Robolectric) and a serialization library dependency.
 *
 * Values map to: Map<String, Any?>, List<Any?>, String, Long, Double,
 * Boolean and null.
 */
internal object Json {
    fun encode(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    fun parse(text: String): Any? {
        val parser = Parser(text)
        val value = parser.value()
        parser.skipWhitespace()
        if (!parser.atEnd()) throw IllegalArgumentException("trailing data at ${parser.pos}")
        return value
    }

    /** Parse, returning null for empty or malformed input instead of throwing. */
    fun parseOrNull(text: String?): Any? {
        if (text.isNullOrEmpty()) return null
        return try {
            parse(text)
        } catch (_: Exception) {
            null
        }
    }

    private fun write(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> writeString(out, value)
            is Boolean -> out.append(if (value) "true" else "false")
            is Double -> if (value.isFinite()) out.append(formatDouble(value)) else out.append("null")
            is Float -> write(out, value.toDouble())
            is Int, is Long, is Short, is Byte -> out.append(value.toString())
            is Number -> write(out, value.toDouble())
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) out.append(',')
                    first = false
                    writeString(out, k.toString())
                    out.append(':')
                    write(out, v)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                var first = true
                for (v in value) {
                    if (!first) out.append(',')
                    first = false
                    write(out, v)
                }
                out.append(']')
            }
            else -> writeString(out, value.toString())
        }
    }

    private fun formatDouble(value: Double): String {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) return value.toLong().toString()
        return value.toString()
    }

    private fun writeString(out: StringBuilder, s: String) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (c < ' ' || c == ' ' || c == ' ') {
                    out.append("\\u").append(Integer.toHexString(c.code).padStart(4, '0'))
                } else {
                    out.append(c)
                }
            }
        }
        out.append('"')
    }

    private class Parser(val s: String) {
        var pos = 0

        fun atEnd() = pos >= s.length

        fun skipWhitespace() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun value(): Any? {
            skipWhitespace()
            if (atEnd()) throw IllegalArgumentException("unexpected end")
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("unexpected '$c' at $pos")
            }
        }

        private fun literal(word: String, result: Any?): Any? {
            if (!s.startsWith(word, pos)) throw IllegalArgumentException("bad literal at $pos")
            pos += word.length
            return result
        }

        private fun obj(): Map<String, Any?> {
            pos++
            val out = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (s.getOrNull(pos) == '}') {
                pos++
                return out
            }
            while (true) {
                skipWhitespace()
                if (s.getOrNull(pos) != '"') throw IllegalArgumentException("expected key at $pos")
                val key = str()
                skipWhitespace()
                if (s.getOrNull(pos) != ':') throw IllegalArgumentException("expected ':' at $pos")
                pos++
                out[key] = value()
                skipWhitespace()
                when (s.getOrNull(pos)) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return out
                    }
                    else -> throw IllegalArgumentException("expected ',' or '}' at $pos")
                }
            }
        }

        private fun arr(): List<Any?> {
            pos++
            val out = ArrayList<Any?>()
            skipWhitespace()
            if (s.getOrNull(pos) == ']') {
                pos++
                return out
            }
            while (true) {
                out.add(value())
                skipWhitespace()
                when (s.getOrNull(pos)) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return out
                    }
                    else -> throw IllegalArgumentException("expected ',' or ']' at $pos")
                }
            }
        }

        private fun str(): String {
            pos++
            val out = StringBuilder()
            while (true) {
                if (atEnd()) throw IllegalArgumentException("unterminated string")
                val c = s[pos++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (atEnd()) throw IllegalArgumentException("bad escape")
                        when (val e = s[pos++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw IllegalArgumentException("bad unicode escape")
                                out.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("bad escape '$e'")
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun num(): Any {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            val text = s.substring(start, pos)
            val isInteger = text.none { it == '.' || it == 'e' || it == 'E' }
            if (isInteger) text.toLongOrNull()?.let { return it }
            return text.toDouble()
        }
    }
}
