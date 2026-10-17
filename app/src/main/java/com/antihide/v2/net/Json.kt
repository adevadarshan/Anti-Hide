package com.antihide.v2.net

/**
 * Minimal flat-object JSON support for the Slice 1 wire format.
 *
 * Hand-rolled instead of pulling in a serialization library: the PoC only exchanges flat objects
 * whose values are short strings or integers, on a private network, with a hard 64 KB body cap.
 */
object Json {

    fun obj(vararg fields: Pair<String, Any?>): String =
        fields.joinToString(",", "{", "}") { (k, v) ->
            val key = "\"${escape(k)}\""
            when (v) {
                null -> "$key:null"
                is Int, is Long, is Boolean -> "$key:$v"
                // Emitted as a real array rather than a quoted blob, so clients can iterate it.
                is List<*> -> "$key:" + v.joinToString(",", "[", "]") { item ->
                    when (item) {
                        null -> "null"
                        is Int, is Long, is Boolean -> "$item"
                        else -> "\"${escape(item.toString())}\""
                    }
                }
                else -> "$key:\"${escape(v.toString())}\""
            }
        }

    fun escape(raw: String): String {
        val sb = StringBuilder(raw.length + 8)
        for (c in raw) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u").append("%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /**
     * Reads a top-level field as text. Quoted strings are unescaped; bare scalars (`true`, `42`)
     * are returned verbatim. Returns null when the field is absent.
     */
    fun stringField(json: String, name: String): String? {
        val needle = "\"$name\""
        var i = json.indexOf(needle)
        while (i >= 0) {
            var j = i + needle.length
            while (j < json.length && json[j].isWhitespace()) j++
            if (j < json.length && json[j] == ':') {
                j++
                while (j < json.length && json[j].isWhitespace()) j++
                if (j < json.length) {
                    if (json[j] == '"') return readString(json, j)
                    val end = generateSequence(j) { it + 1 }
                        .first { it >= json.length || json[it] == ',' || json[it] == '}' }
                        .coerceAtMost(json.length)
                    return json.substring(j, end).trim().ifEmpty { null }
                }
            }
            i = json.indexOf(needle, i + needle.length)
        }
        return null
    }

    private fun readString(json: String, openQuote: Int): String? {
        val sb = StringBuilder()
        var j = openQuote + 1
        while (j < json.length) {
            when (val c = json[j]) {
                '"' -> return sb.toString()
                '\\' -> {
                    j++
                    if (j >= json.length) return null
                    when (val e = json[j]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'f' -> sb.append(0x0C.toChar())
                        'u' -> {
                            if (j + 4 >= json.length) return null
                            val hex = json.substring(j + 1, j + 5)
                            val code = hex.toIntOrNull(16) ?: return null
                            sb.append(code.toChar())
                            j += 4
                        }
                        else -> sb.append(e)
                    }
                }
                else -> sb.append(c)
            }
            j++
        }
        return null
    }
}