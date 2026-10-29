// SPDX-License-Identifier: GPL-3.0-or-later
package org.alpha3.launcher.tools.openmw

/** Single-byte plugin string codec; bytes below 0x80 are ASCII, the rest come from a code page table. */
internal class TextCodec private constructor(private val table: IntArray) {
    private val reverse = HashMap<Char, Byte>().also { m -> table.forEachIndexed { i, c -> m[c.toChar()] = (i + 128).toByte() } }

    /** Decodes up to the first NUL. */
    fun decode(b: ByteArray): String {
        var end = b.indexOf(0)
        if (end < 0) end = b.size
        val out = CharArray(end)
        for (i in 0 until end) {
            val v = b[i].toInt() and 0xff
            out[i] = if (v < 128) v.toChar() else table[v - 128].toChar()
        }
        return out.concatToString()
    }

    fun encode(s: String): ByteArray = ByteArray(s.length) {
        val c = s[it]
        if (c.code < 128) c.code.toByte() else reverse[c] ?: fail("cannot encode \"$s\" in plugin code page")
    }

    companion object {
        fun forName(name: String): TextCodec = when (name.lowercase()) {
            "win1250", "windows-1250" -> TextCodec(cp1250)
            "win1251", "windows-1251" -> TextCodec(cp1251)
            "", "win1252", "windows-1252" -> TextCodec(cp1252)
            else -> fail("unsupported encoding \"$name\" (expected win1250, win1251 or win1252)")
        }
    }
}
