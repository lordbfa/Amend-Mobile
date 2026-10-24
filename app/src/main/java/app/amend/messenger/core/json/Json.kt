package app.amend.messenger.core.json

/**
 * A small JSON reader/writer. Kept dependency-free so the protocol core
 * runs (and is tested) on a plain JVM.
 *
 * Values: null, Boolean, Long, Double, String, List<Any?>, Map<String, Any?>.
 */
object Json {

    class JsonException(message: String) : Exception(message)

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        if (p.pos != text.length) throw JsonException("trailing characters at ${p.pos}")
        return v
    }

    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?> =
        parse(text) as? Map<String, Any?> ?: throw JsonException("expected object")

    @Suppress("UNCHECKED_CAST")
    fun parseArray(text: String): List<Any?> =
        parse(text) as? List<Any?> ?: throw JsonException("expected array")

    /**
     * @param nip01 when true, strings use the NIP-01 event-id escaping rules
     *   (only \n \" \\ \r \t \b \f are escaped; everything else verbatim).
     */
    fun write(value: Any?, nip01: Boolean = false): String =
        StringBuilder().also { writeTo(it, value, nip01) }.toString()

    private fun writeTo(sb: StringBuilder, v: Any?, nip01: Boolean) {
        when (v) {
            null -> sb.append("null")
            is String -> writeString(sb, v, nip01)
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Double -> {
                if (v.isNaN() || v.isInfinite()) throw JsonException("non-finite number")
                if (v == Math.floor(v) && Math.abs(v) < 1e15) sb.append(v.toLong()) else sb.append(v.toString())
            }
            is Float -> writeTo(sb, v.toDouble(), nip01)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k.toString(), nip01)
                    sb.append(':')
                    writeTo(sb, value, nip01)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in v) {
                    if (!first) sb.append(',')
                    first = false
                    writeTo(sb, item, nip01)
                }
                sb.append(']')
            }
            is Array<*> -> writeTo(sb, v.toList(), nip01)
            else -> writeString(sb, v.toString(), nip01)
        }
    }

    private fun writeString(sb: StringBuilder, s: String, nip01: Boolean) {
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
                else -> if (!nip01 && c < ' ') {
                    sb.append("\\u").append(String.format("%04x", c.code))
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var pos = 0

        fun skipWs() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\n' || s[pos] == '\r' || s[pos] == '\t')) pos++
        }

        private fun fail(msg: String): Nothing = throw JsonException("$msg at $pos")

        fun value(): Any? {
            if (pos >= s.length) fail("unexpected end")
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else fail("unexpected '$c'")
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) fail("invalid literal")
            pos += word.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            pos++
            skipWs()
            if (pos < s.length && s[pos] == '}') { pos++; return m }
            while (true) {
                skipWs()
                if (pos >= s.length || s[pos] != '"') fail("expected key")
                val k = str()
                skipWs()
                if (pos >= s.length || s[pos] != ':') fail("expected ':'")
                pos++
                skipWs()
                m[k] = value()
                skipWs()
                if (pos >= s.length) fail("unterminated object")
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return m }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            pos++
            skipWs()
            if (pos < s.length && s[pos] == ']') { pos++; return l }
            while (true) {
                skipWs()
                l.add(value())
                skipWs()
                if (pos >= s.length) fail("unterminated array")
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return l }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun str(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) fail("unterminated string")
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= s.length) fail("bad escape")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) fail("bad unicode escape")
                                sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> fail("bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Any {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && s[pos] in '0'..'9') pos++
            var isDouble = false
            if (pos < s.length && s[pos] == '.') {
                isDouble = true
                pos++
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                isDouble = true
                pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            val text = s.substring(start, pos)
            if (text == "-" || text.isEmpty()) fail("invalid number")
            return if (isDouble) text.toDouble() else (text.toLongOrNull() ?: text.toDouble())
        }
    }
}

// Convenience accessors
fun Map<String, Any?>.str(key: String): String? = this[key] as? String
fun Map<String, Any?>.long(key: String): Long? = when (val v = this[key]) {
    is Long -> v
    is Double -> v.toLong()
    is Int -> v.toLong()
    else -> null
}
