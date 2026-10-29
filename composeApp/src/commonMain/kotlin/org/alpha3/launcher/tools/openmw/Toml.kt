// SPDX-License-Identifier: GPL-3.0-or-later
package org.alpha3.launcher.tools.openmw

/**
 * A small TOML reader for tool settings: tables, dotted and quoted keys, strings, numbers,
 * booleans, arrays and inline tables. Values come back as String, Long, Double, Boolean,
 * List<Any> and Map<String, Any>. Arrays of tables and date-times are not supported.
 */
internal object Toml {
    fun parse(text: String): Map<String, Any> = Reader(text).document()

    private class Reader(val s: String) {
        var i = 0

        fun document(): Map<String, Any> {
            val root: MutableMap<String, Any> = LinkedHashMap()
            var current = root
            while (true) {
                skip(newlines = true)
                if (i >= s.length) return root
                if (s[i] == '[') {
                    if (s.startsWith("[[", i)) err("arrays of tables are not supported")
                    i++
                    val path = keyPath()
                    skip(newlines = false)
                    expect(']')
                    current = table(root, path)
                } else {
                    val path = keyPath()
                    skip(newlines = false)
                    expect('=')
                    val value = value()
                    val parent = table(current, path.dropLast(1))
                    if (path.last() in parent) err("duplicate key ${path.last()}")
                    parent[path.last()] = value
                }
                skip(newlines = false)
                if (i < s.length && s[i] != '\n' && s[i] != '\r') err("expected end of line")
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun table(from: MutableMap<String, Any>, path: List<String>): MutableMap<String, Any> {
            var t = from
            for (k in path) t = t.getOrPut(k) { LinkedHashMap<String, Any>() } as? MutableMap<String, Any> ?: err("$k is not a table")
            return t
        }

        private fun err(msg: String): Nothing {
            val line = s.substring(0, minOf(i, s.length)).count { it == '\n' } + 1
            fail("TOML line $line: $msg")
        }

        private fun expect(c: Char) {
            if (i >= s.length || s[i] != c) err("expected '$c'")
            i++
        }

        /** Skips spaces and comments, and line breaks too when [newlines]. */
        private fun skip(newlines: Boolean) {
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '#' -> while (i < s.length && s[i] != '\n') i++
                    c == '\n' || c == '\r' -> if (newlines) i++ else return
                    c == ' ' || c == '\t' -> i++
                    else -> return
                }
            }
        }

        private fun keyPath(): List<String> {
            val out = ArrayList<String>()
            while (true) {
                skip(newlines = false)
                if (i >= s.length) err("missing key")
                out.add(
                    when (s[i]) {
                        '"' -> basicString()
                        '\'' -> literalString()
                        else -> {
                            val st = i
                            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '-')) i++
                            if (st == i) err("unexpected '${s[i]}'")
                            s.substring(st, i)
                        }
                    },
                )
                skip(newlines = false)
                if (i < s.length && s[i] == '.') i++ else return out
            }
        }

        private fun basicString(): String {
            if (s.startsWith("\"\"\"", i)) return multiline("\"\"\"", escapes = true)
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length || s[i] == '\n') err("unterminated string")
                val c = s[i++]
                if (c == '"') return sb.toString()
                if (c == '\\') escape(sb) else sb.append(c)
            }
        }

        private fun escape(sb: StringBuilder) {
            when (val e = s[i++]) {
                'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                '"', '\\' -> sb.append(e)
                'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                'U' -> { sb.appendCodePoint(s.substring(i, i + 8).toInt(16)); i += 8 }
                else -> err("unsupported escape \\$e")
            }
        }

        private fun StringBuilder.appendCodePoint(cp: Int) {
            if (cp < 0x10000) append(cp.toChar()) else {
                val v = cp - 0x10000
                append((0xD800 + (v shr 10)).toChar()).append((0xDC00 + (v and 0x3FF)).toChar())
            }
        }

        private fun literalString(): String {
            if (s.startsWith("'''", i)) return multiline("'''", escapes = false)
            i++
            val st = i
            while (i < s.length && s[i] != '\'' && s[i] != '\n') i++
            if (i >= s.length || s[i] != '\'') err("unterminated string")
            return s.substring(st, i++)
        }

        private fun multiline(q: String, escapes: Boolean): String {
            i += 3
            if (s.startsWith("\r\n", i)) i += 2 else if (i < s.length && s[i] == '\n') i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) err("unterminated string")
                if (s.startsWith(q, i)) { i += 3; return sb.toString() }
                val c = s[i++]
                if (escapes && c == '\\') {
                    if (s[i] == '\n' || s[i] == ' ' || s[i] == '\r') { while (i < s.length && s[i].isWhitespace()) i++ } else escape(sb)
                } else sb.append(c)
            }
        }

        private fun value(): Any {
            skip(newlines = false)
            if (i >= s.length) err("missing value")
            return when (val c = s[i]) {
                '"' -> basicString()
                '\'' -> literalString()
                '[' -> array()
                '{' -> inlineTable()
                else -> {
                    val st = i
                    while (i < s.length && s[i] !in ",]}\n\r#" && !s[i].isWhitespace()) i++
                    scalar(s.substring(st, i).also { if (it.isEmpty()) err("unexpected '$c'") })
                }
            }
        }

        private fun scalar(t: String): Any = when (t) {
            "true" -> true
            "false" -> false
            "inf", "+inf" -> Double.POSITIVE_INFINITY
            "-inf" -> Double.NEGATIVE_INFINITY
            "nan", "+nan", "-nan" -> Double.NaN
            else -> {
                val n = t.replace("_", "")
                n.toLongOrNull() ?: n.toDoubleOrNull() ?: err("invalid value '$t'")
            }
        }

        private fun array(): List<Any> {
            i++
            val out = ArrayList<Any>()
            while (true) {
                skip(newlines = true)
                if (i >= s.length) err("unterminated array")
                if (s[i] == ']') { i++; return out }
                out.add(value())
                skip(newlines = true)
                if (i < s.length && s[i] == ',') i++
            }
        }

        private fun inlineTable(): Map<String, Any> {
            i++
            val out = LinkedHashMap<String, Any>()
            while (true) {
                skip(newlines = false)
                if (i >= s.length) err("unterminated inline table")
                if (s[i] == '}') { i++; return out }
                val path = keyPath()
                skip(newlines = false)
                expect('=')
                table(out, path.dropLast(1))[path.last()] = value()
                skip(newlines = false)
                if (i < s.length && s[i] == ',') i++
            }
        }
    }
}
