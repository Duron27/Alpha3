// SPDX-License-Identifier: GPL-3.0-or-later
package org.alpha3.launcher.tools.merge

internal class MergedRecord(val value: Mergeable, val sources: List<String>)

private val baseGamePlugins = setOf("morrowind.esm", "tribunal.esm", "bloodmoon.esm")

/**
 * DeltaPlugin's merge. Each plugin is reduced to what it changed relative to its masters (new
 * records in full, changed records as deltas), the changes are replayed in load order, and a record
 * is kept only if the result differs from the last plugin's own version.
 *
 * [loaded] holds every readable plugin by lowercase name; [order] is the load order to merge.
 */
internal fun mergeRecords(loaded: Map<String, MergePlugin>, order: List<String>, log: (String) -> Unit): List<MergedRecord> {
    val plugins = order.map { loaded.getValue(it) }

    // Reduce each plugin to its changes.
    val changes = plugins.map { p ->
        val out = LinkedHashMap<String, Entry>()
        val masters = if (p.key in baseGamePlugins) emptyList() else p.masters.mapNotNull { m ->
            loaded[m.lowercase()].also { if (it == null) log("Master $m of ${p.name} is not in the load order; ignoring it") }
        }
        for ((key, value) in p.records) {
            if (value == null) {
                out[key] = Entry.Delete
                continue
            }
            // The newest master version wins; a master's deletion hides earlier versions.
            var master: Mergeable? = null
            for (m in masters) if (key in m.records) master = m.records[key]
            out[key] = when {
                master == null -> Entry.Full(value)
                master.sameAs(value) -> continue
                else -> Entry.Delta(master.diffTo(value))
            }
        }
        out
    }

    // Group every plugin's entry for a record, in load order.
    val groups = LinkedHashMap<String, MutableList<Pair<Int, Entry>>>()
    for ((i, c) in changes.withIndex()) for ((key, e) in c) groups.getOrPut(key) { ArrayList() }.add(i to e)

    // The newest raw version of each record across the whole load order.
    val latest = HashMap<String, Mergeable>()
    for (p in plugins) for ((key, value) in p.records) if (value == null) latest.remove(key) else latest[key] = value

    val result = ArrayList<MergedRecord>()
    log("Replaying the changes to ${groups.size} records in load order")
    for ((n, group) in groups.entries.withIndex()) {
        val (key, entries) = group
        if (n > 0 && n % 500 == 0) log("  replayed $n of ${groups.size} records, ${result.size} kept so far")
        var rest = entries.toList()
        val first = rest[0].second
        val base: Mergeable
        if (first is Entry.Full) {
            base = first.value
            rest = rest.drop(1)
        } else {
            // A record that starts with a deletion or delta has nothing to build on.
            if (rest.size == 1) continue
            rest = rest.drop(1)
            var cur = rest[0]
            rest = rest.drop(1)
            while (rest.isNotEmpty() && cur.second is Entry.Delete) {
                cur = rest[0]
                rest = rest.drop(1)
            }
            base = (cur.second as? Entry.Full)?.value ?: continue
        }
        val merged = combine(base, rest, plugins.map { it.name }, log) ?: continue
        val previous = latest[key]
        if (previous != null && previous.sameAs(merged)) continue
        val names = ArrayList<String>()
        names.add(plugins[entries[0].first].name)
        for ((i, _) in rest) names.add(plugins[i].name)
        if (rest.isNotEmpty()) log("Merged ${merged.recordTag} ${merged.recordId} from ${names.joinToString(", ")}")
        result.add(MergedRecord(merged, names))
    }
    return result
}

/**
 * Applies [deltas] to [base] in order. When a delta conflicts (it removes something that is not
 * there), look back for an earlier state it does apply to and skip the plugin that broke it.
 * A null result means the record ends up deleted.
 */
private fun combine(base: Mergeable, deltas: List<Pair<Int, Entry>>, names: List<String>, log: (String) -> Unit): Mergeable? {
    val id = "${base.recordTag} ${base.recordId}"
    val states = ArrayList<Mergeable?>()
    states.add(base)
    val skip = HashSet<Int>()
    var pos = 0
    while (pos < deltas.size) {
        if (pos in skip) {
            states.add(states.last())
            pos++
            continue
        }
        val (pluginIndex, entry) = deltas[pos]
        when (entry) {
            is Entry.Full -> states.add(entry.value.copyValue())
            Entry.Delete -> states.add(null)
            is Entry.Delta -> {
                val last = states.last()
                if (last == null) {
                    states.add(null) // nothing to modify once deleted
                } else {
                    val next = last.copyValue()
                    try {
                        next.applyDelta(entry.delta)
                        states.add(next)
                    } catch (c: Conflict) {
                        log("Conflict in $id from ${names[pluginIndex]}: ${c.message}")
                        val back = (states.size - 1 downTo 0).firstOrNull { i ->
                            states[i]?.copyValue()?.let { s -> runCatching { s.applyDelta(entry.delta) }.isSuccess } == true
                        }
                        var skipIndex = back
                        while (skipIndex != null && skipIndex in skip) skipIndex++
                        if (back != null && skipIndex != null && skipIndex <= pos) {
                            log("Backing up ${pos - back} so that ${names[deltas[skipIndex].first]} can be skipped")
                            skip.add(skipIndex)
                            while (states.size > back + 1) states.removeAt(states.size - 1)
                            pos = back
                            continue
                        }
                        log("Could not resolve the conflict for $id; skipping ${names[pluginIndex]}'s changes to it")
                        skip.add(pos)
                        states.add(last)
                    }
                }
            }
        }
        pos++
    }
    return states.last()
}
