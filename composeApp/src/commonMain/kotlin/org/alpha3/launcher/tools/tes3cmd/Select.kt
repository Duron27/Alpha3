// SPDX-License-Identifier: GPL-3.0-or-later
package org.alpha3.launcher.tools.tes3cmd

import org.alpha3.launcher.tools.openmw.*

private val flagNames = linkedMapOf("deleted" to HDR_DELETED, "persistent" to HDR_PERSISTENT, "ignored" to HDR_IGNORED, "blocked" to HDR_BLOCKED)

internal fun flagsText(flags: Long): String {
    val names = flagNames.filterValues { flags and it != 0L }.keys.map { it.replaceFirstChar { c -> c.uppercase() } }.sorted()
    return "0x" + flags.toString(16).padStart(4, '0') + " (" + names.joinToString(", ") + ")"
}

private fun quote(s: String): String = s

/** A readable form of one subrecord's data: strings shown as text, other data as hex bytes. */
internal fun subText(s: Sub): String {
    val d = s.data
    if (s.tag == "FRMR" || s.tag == "MVRF") {
        if (d.size == 4) { val v = u32(d); return "ObjIdx:${v and 0xFFFFFF}  MastIdx:${v ushr 24}" }
    }
    val body = if (d.isNotEmpty() && d.last() == 0.toByte()) d.copyOf(d.size - 1) else d
    val printable = body.isNotEmpty() && body.all { (it.toInt() and 0xff) >= 0x20 && it.toInt() != 0x7f }
    if (printable && (d.last() == 0.toByte() || body.size > 3)) return quote(CharArray(body.size) { (body[it].toInt() and 0xff).toChar() }.concatToString())
    return "[hex:" + d.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') } + "]"
}

/** Text form of a record, used for the `--match` family of options and for dumps. */
internal fun recordText(rec: Rec): String = buildString {
    append("Record: ${rec.tag} \"${rec.id()}\" Flags:${flagsText(rec.flags)}\n")
    append(rec.subs.joinToString("\n") { (if (it.tag == "FRMR") " *" else "  ") + it.tag + ": " + subText(it) })
}

/** The record selection switches shared by `delete`, `dump` and friends. */
class RecordSelection(
    /** Record types, as `TYPE` or `TYPE.SUBT`; empty selects every type. */
    val types: List<String> = emptyList(),
    /** Regexes matched (case-insensitively, anywhere) against record ids. */
    val ids: List<String> = emptyList(),
    val exactIds: List<String> = emptyList(),
    /** Header flags, as numbers or names (deleted, persistent, ignored, blocked, or d/p/i/b). */
    val flags: List<String> = emptyList(),
    val interior: Boolean = false,
    val exterior: Boolean = false,
    /** A record's text must match all of these... */
    val match: List<String> = emptyList(),
    /** ...and none of these. */
    val noMatch: List<String> = emptyList(),
    val instanceMatch: String? = null,
    val instanceNoMatch: String? = null,
    val subMatch: String? = null,
    val subNoMatch: String? = null,
) {
    private val wantedTypes: Set<String>
    private val wantedFlags: Long
    private val idRegex: Regex?

    init {
        var types2 = types
        if (instanceMatch != null || instanceNoMatch != null || exterior || interior) types2 = listOf("cell")
        val t = HashSet<String>()
        for (o in types2) {
            var (rt, st) = o.uppercase().split('.', limit = 2).let { it[0] to it.getOrNull(1) }
            if (rt == "NPC") rt = "NPC_"
            if (rt.length != 4) fail("get_wanted_types: Invalid record type: \"$rt\" (must be 4 characters long)")
            t.add(rt)
            if (st != null) {
                if (st.length != 4) fail("get_wanted_types: Invalid record subtype: \"$st\" (must be 4 characters long)")
                t.add("$rt.$st")
            }
        }
        wantedTypes = t
        var f = 0L
        for (flag in flags) {
            f = f or when {
                Regex("^(?:0[xob])?\\d+$").matches(flag) -> when {
                    flag.startsWith("0x") -> flag.substring(2).toLong(16)
                    flag.startsWith("0o") -> flag.substring(2).toLong(8)
                    flag.startsWith("0b") -> flag.substring(2).toLong(2)
                    else -> flag.toLong()
                }
                else -> flagNames[flag.lowercase()] ?: flagNames.entries.firstOrNull { it.key.startsWith(flag.lowercase()) && flag.length == 1 }?.value ?: 0L
            }
        }
        wantedFlags = f
        val all = ids + exactIds.map { "^" + Regex.escape(it) + "$" }
        idRegex = if (all.isEmpty()) null else Regex("(" + all.joinToString("|") + ")", RegexOption.IGNORE_CASE)
    }

    private val matchRegexes = match.map { Regex(it, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)) }
    private val noMatchRegexes = noMatch.map { Regex(it, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)) }

    internal fun matches(rec: Rec): Boolean {
        if (wantedFlags != 0L && rec.flags and wantedFlags == 0L) return false
        if (wantedTypes.isNotEmpty() && rec.tag !in wantedTypes) return false
        if (idRegex != null && !idRegex.containsMatchIn(rec.id())) return false
        if (rec.tag == "CELL") {
            if (rec.cellIsInterior()) { if (exterior) return false } else if (interior) return false
        }
        if (matchRegexes.isNotEmpty() || noMatchRegexes.isNotEmpty()) {
            val text = recordText(rec)
            if (noMatchRegexes.any { it.containsMatchIn(text) }) return false
            if (matchRegexes.any { !it.containsMatchIn(text) }) return false
        }
        return true
    }

    internal fun subMatches(s: Sub): Boolean {
        val text = subText(s)
        val m = subMatch?.let { Regex(it, RegexOption.IGNORE_CASE).containsMatchIn(text) } ?: true
        val n = subNoMatch?.let { !Regex(it, RegexOption.IGNORE_CASE).containsMatchIn(text) } ?: true
        return m && n
    }

    internal fun groupMatches(text: String): Boolean {
        val m = instanceMatch?.let { Regex(it, RegexOption.IGNORE_CASE).containsMatchIn(text) } ?: true
        val n = instanceNoMatch?.let { !Regex(it, RegexOption.IGNORE_CASE).containsMatchIn(text) } ?: true
        return m && n
    }

    internal val deletesSubrecords get() = subMatch != null || subNoMatch != null
    internal val deletesInstances get() = instanceMatch != null || instanceNoMatch != null
}

/** Groups of a cell's subrecords: the header, then one list per object instance (FRMR). */
internal fun cellGroups(subs: List<Sub>): List<List<Sub>> {
    val groups = ArrayList<MutableList<Sub>>()
    var cur = ArrayList<Sub>()
    for (s in subs) {
        if (s.tag == "FRMR") { groups.add(cur); cur = ArrayList() }
        cur.add(s)
    }
    groups.add(cur)
    return groups
}
