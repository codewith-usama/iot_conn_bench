package com.thesis.iotconnbench.core.util

/**
 * Minimal, dependency-free JSON reader/writer used by the core (payload codec, exports).
 * Kept in-house so the core compiles and tests on a plain JVM (android's org.json is a stub there).
 * Numbers are parsed as Long when integral, otherwise Double.
 */
object Json {
    class ParseException(msg: String) : Exception(msg)

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.readValue()
        p.skipWs()
        if (!p.atEnd()) throw ParseException("Trailing data at ${p.pos}")
        return v
    }

    fun parseObject(text: String): Map<String, Any?> =
        parse(text) as? Map<String, Any?> ?: throw ParseException("Not a JSON object")

    fun write(value: Any?): String = StringBuilder().also { writeTo(it, value) }.toString()

    fun quote(s: String): String = StringBuilder().also { writeString(it, s) }.toString()

    private fun writeTo(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> writeString(sb, v)
            is Boolean -> sb.append(v)
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Double -> if (v.isFinite()) sb.append(v.toString()) else sb.append("null")
            is Float -> if (v.isFinite()) sb.append(v.toString()) else sb.append("null")
            is Number -> sb.append(v.toString())
            is Enum<*> -> writeString(sb, v.name)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k.toString())
                    sb.append(':')
                    writeTo(sb, value)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in v) {
                    if (!first) sb.append(',')
                    first = false
                    writeTo(sb, item)
                }
                sb.append(']')
            }
            is Array<*> -> writeTo(sb, v.asList())
            else -> writeString(sb, v.toString())
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var pos = 0
        fun atEnd() = pos >= s.length
        fun skipWs() { while (pos < s.length && s[pos].isWhitespace()) pos++ }

        fun readValue(): Any? {
            skipWs()
            if (atEnd()) throw ParseException("Unexpected end")
            return when (val c = s[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) readNumber() else throw ParseException("Unexpected '$c' at $pos")
            }
        }

        fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) throw ParseException("Bad literal at $pos")
            pos += word.length
            return v
        }

        fun readObject(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            pos++
            skipWs()
            if (peek() == '}') { pos++; return m }
            while (true) {
                skipWs()
                if (peek() != '"') throw ParseException("Expected key at $pos")
                val k = readString()
                skipWs()
                expect(':')
                m[k] = readValue()
                skipWs()
                when (next()) {
                    ',' -> continue
                    '}' -> return m
                    else -> throw ParseException("Expected , or } at ${pos - 1}")
                }
            }
        }

        fun readArray(): List<Any?> {
            val l = ArrayList<Any?>()
            pos++
            skipWs()
            if (peek() == ']') { pos++; return l }
            while (true) {
                l.add(readValue())
                skipWs()
                when (next()) {
                    ',' -> continue
                    ']' -> return l
                    else -> throw ParseException("Expected , or ] at ${pos - 1}")
                }
            }
        }

        fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw ParseException("Unterminated string")
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) throw ParseException("Bad escape")
                        when (val e = s[pos++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                            'u' -> {
                                if (pos + 4 > s.length) throw ParseException("Bad unicode escape")
                                sb.append(s.substring(pos, pos + 4).toInt(16).toChar()); pos += 4
                            }
                            else -> throw ParseException("Bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun readNumber(): Any {
            val start = pos
            if (peek() == '-') pos++
            while (!atEnd() && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            val t = s.substring(start, pos)
            return if (t.any { it == '.' || it == 'e' || it == 'E' }) {
                t.toDoubleOrNull() ?: throw ParseException("Bad number $t")
            } else {
                t.toLongOrNull() ?: t.toDoubleOrNull() ?: throw ParseException("Bad number $t")
            }
        }

        fun peek(): Char = if (atEnd()) '\u0000' else s[pos]
        fun next(): Char = if (atEnd()) throw ParseException("Unexpected end") else s[pos++]
        fun expect(c: Char) { if (next() != c) throw ParseException("Expected '$c' at ${pos - 1}") }
    }
}
