// SPDX-License-Identifier: GPL-3.0-or-later
package org.alpha3.launcher.tools.pluginquery

import org.alpha3.launcher.tools.openmw.*

private fun quote(s: String): String {
    val sb = StringBuilder("\"")
    for (c in s) when {
        c == '"' -> sb.append("\\\"")
        c == '\\' -> sb.append("\\\\")
        c == '\n' -> sb.append("\\n")
        c == '\t' -> sb.append("\\t")
        c.code < 0x20 -> sb.append("\\x").append(c.code.toString(16).padStart(2, '0'))
        else -> sb.append(c)
    }
    return sb.append('"').toString()
}

/** Strings (printable, optionally NUL-terminated) are shown decoded; anything else as hex bytes. */
internal fun formatValue(data: ByteArray, codec: TextCodec): String {
    val body = if (data.isNotEmpty() && data.last() == 0.toByte()) data.copyOf(data.size - 1) else data
    val text = body.isNotEmpty() && body.all { (it.toInt() and 0xff) >= 0x20 && it.toInt() != 0x7f || it.toInt() == 9 }
    if (text && (data.isEmpty() || data.last() == 0.toByte() || body.size > 3)) return quote(codec.decode(body))
    if (data.isEmpty()) return "\"\""
    return "<" + data.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') } + ">"
}

private fun subLines(out: StringBuilder, subs: Collection<Sub>, indent: String, codec: TextCodec) {
    for (s in subs) out.append(indent).append(s.tag).append(": ").append(formatValue(s.data, codec)).append('\n')
}

internal fun dumpRecord(out: StringBuilder, sel: Selected, codec: TextCodec) {
    val m = sel.merged
    out.append(quote(if (m.tag == "CELL" || m.id.display.contains("::")) m.id.display else "${typeName(m.tag)}::${m.id.display}")).append(":\n")
    out.append("  type: ").append(typeName(m.tag)).append('\n')
    val cell = sel.cell
    if (cell == null) {
        subLines(out, m.subs, "  ", codec)
        return
    }
    subLines(out, cell.meta.values, "  ", codec)
    out.append("  references:\n")
    for ((key, r) in cell.refs) {
        out.append("    ").append(quote(key)).append(":\n")
        cell.moved[key]?.let { out.append("      MOVED: ").append(formatValue(it, codec)).append('\n') }
        subLines(out, r.subs, "      ", codec)
    }
}
