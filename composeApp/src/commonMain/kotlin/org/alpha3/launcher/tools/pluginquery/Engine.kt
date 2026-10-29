// SPDX-License-Identifier: GPL-3.0-or-later
package org.alpha3.launcher.tools.pluginquery

import org.alpha3.launcher.tools.openmw.*

/** One `match` statement. The first statement matching a record's type decides its fate. */
class MatchSpec(
    /** Four-letter tag; see [recordTag]. */
    val tag: String,
    /** Case-insensitive; matched anywhere in the lowercased record id. */
    val id: Regex? = null,
    /** Cells only: keep just the references whose object id matches. */
    val cellrefObjectId: Regex? = null,
    /** Filter only: turn matches into deletions. */
    val delete: Boolean = false,
    /** Filter only: rewrite a string field. */
    val modify: Modify? = null,
)

class Modify(val field: String, val regex: Regex, val replacement: String)

internal class CellState {
    val meta = LinkedHashMap<String, Sub>()
    val refs = LinkedHashMap<String, CellRef>()

    /** Where moved references go, kept even when a later plugin redefines the reference itself. */
    val moved = HashMap<String, ByteArray>()
}

/** A record after merging every plugin that defines it. */
internal class Merged(val tag: String, val id: Identity, val flags: Long, val subs: List<Sub>, val cell: CellState?)

/** A record that survived a [MatchSpec]; cells may have been cut down to the matching references. */
internal class Selected(val merged: Merged, val spec: MatchSpec, val cell: CellState?)

/** Applies plugins in load order, last definition winning and deletions removing records. */
internal class Merger(private val tags: Set<String>, private val codec: TextCodec) {
    val records = LinkedHashMap<String, Merged>()

    fun apply(plugin: String, masters: List<String>, tag: String, flags: Long, body: ByteArray) {
        val subs = readSubs(body)
        if (tag == "CELL") return applyCell(plugin, masters, body, subs, flags)
        val id = identify(tag, subs, codec) ?: return
        val key = "$tag::${id.canonical}"
        if (subs.any { it.tag == "DELE" }) records.remove(key) else records[key] = Merged(tag, id, flags, subs, null)
    }

    private fun applyCell(plugin: String, masters: List<String>, body: ByteArray, subs: List<Sub>, flags: Long) {
        val parsed = parseCell(body, plugin, masters, codec)
        val id = identify("CELL", parsed.meta, codec) ?: return
        val key = "CELL::${id.canonical}"
        if (parsed.deleted) { records.remove(key); return }
        val state = records[key]?.cell ?: CellState()
        // NAME and DATA always override; optional fields carry forward when absent.
        for (m in parsed.meta) {
            // Old water heights are an int INTV; newer plugins use a float WHGT, which replaces it.
            val sub = if (m.tag == "INTV" && m.data.size == 4) Sub("WHGT", floatBytes(i32(m.data).toFloat())) else m
            state.meta[sub.tag] = sub
        }
        for (r in parsed.refs) {
            state.refs[r.key] = r // a deletion stays in place, so a later plugin re-adding the reference keeps its position
            r.moved?.let { state.moved[r.key] = it }
        }
        records[key] = Merged("CELL", id, flags, emptyList(), state)
    }

    fun select(specs: List<MatchSpec>): List<Selected> {
        val out = ArrayList<Selected>()
        for (m in records.values) {
            val spec = specs.firstOrNull { it.tag == m.tag } ?: continue
            if (spec.id != null && !spec.id.containsMatchIn(m.id.canonical)) continue
            if (m.cell != null) {
                val cut = CellState()
                cut.meta.putAll(m.cell.meta)
                for ((k, r) in m.cell.refs) {
                    if (r.deleted) continue
                    if (spec.cellrefObjectId != null && !spec.cellrefObjectId.containsMatchIn(r.objectId.lowercase())) continue
                    cut.refs[k] = r
                    m.cell.moved[k]?.let { cut.moved[k] = it }
                }
                if (spec.cellrefObjectId != null && cut.refs.isEmpty()) continue
                out.add(Selected(m, spec, cut))
            } else {
                out.add(Selected(m, spec, null))
            }
        }
        out.sortWith(compareBy({ it.merged.tag }, { it.merged.id.canonical }))
        return out
    }
}

/** Expands `$1`, `${name}` and `$$` the way Rust's regex crate does; backslashes are literal. */
internal fun replaceAll(regex: Regex, input: String, template: String): String = regex.replace(input) { m ->
    val sb = StringBuilder()
    var i = 0
    while (i < template.length) {
        val c = template[i]
        if (c != '$' || i + 1 >= template.length) { sb.append(c); i++; continue }
        when {
            template[i + 1] == '$' -> { sb.append('$'); i += 2 }
            template[i + 1] == '{' -> {
                val end = template.indexOf('}', i + 2)
                if (end < 0) { sb.append(c); i++ } else { sb.append(group(m, template.substring(i + 2, end))); i = end + 1 }
            }
            else -> {
                var j = i + 1
                while (j < template.length && (template[j].isLetterOrDigit() || template[j] == '_')) j++
                if (j == i + 1) { sb.append(c); i++ } else { sb.append(group(m, template.substring(i + 1, j))); i = j }
            }
        }
    }
    sb.toString()
}

private fun group(m: MatchResult, name: String): String {
    val idx = name.toIntOrNull()
    return if (idx != null) (if (idx < m.groups.size) m.groups[idx]?.value else null).orEmpty()
    else (m.groups as? MatchNamedGroupCollection)?.let { runCatching { it[name]?.value }.getOrNull() }.orEmpty()
}
