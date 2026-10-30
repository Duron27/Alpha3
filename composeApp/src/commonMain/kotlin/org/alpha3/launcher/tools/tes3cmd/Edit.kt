// SPDX-License-Identifier: GPL-3.0-or-later
package org.alpha3.launcher.tools.tes3cmd

import org.alpha3.launcher.tools.openmw.*

/** `tes3cmd delete`: removes matching records, matching subrecords, or matching object instances. */
fun Tes3Cmd.deleteFromPlugin(plugin: String, sel: RecordSelection, mod: ModOptions = ModOptions()) {
    currentPlugin = plugin
    processForUpdate(plugin, mod, null) fn@{ rec ->
        if (rec.tag == "TES3" || !sel.matches(rec)) return@fn Action.Keep
        val id = rec.id()
        if (sel.deletesSubrecords) {
            val kept = ArrayList<Sub>()
            for (s in rec.subs) {
                if (sel.subMatches(s)) print("[${rec.tag} $id] DELETED SUBRECORD: ${s.tag}: ${subText(s)}") else kept.add(s)
            }
            if (kept.isEmpty()) { print("DELETED: ${rec.tag} $id (all subrecords were deleted)"); return@fn Action.Drop }
            return@fn if (kept.size == rec.subs.size) Action.Keep else Action.Replace(Rec(rec.tag, 0, joinSubs(kept)))
        }
        if (sel.deletesInstances) {
            if (rec.tag != "CELL") return@fn Action.Keep
            val groups = cellGroups(rec.subs)
            val kept = ArrayList<Sub>(groups[0])
            var deletions = 0
            for (g in groups.drop(1)) {
                val text = g.joinToString("") { subText(it) }
                val name = g.firstOrNull { it.tag == "NAME" }?.let { zstr(it.data) } ?: ""
                val objIdx = g[0].data.takeIf { it.size >= 4 }?.let { u32(it) and 0xFFFFFF } ?: 0
                if (sel.groupMatches(text)) { print("[CELL: $id] DELETED OBJECT INSTANCE: $name ObjIdx:$objIdx"); deletions++ } else kept.addAll(g)
            }
            return@fn if (deletions > 0) Action.Replace(Rec("CELL", 0, joinSubs(kept))) else Action.Keep
        }
        if (verbose) print("\nDELETED RECORD:\n${recordText(rec)}") else print("DELETED: ${rec.tag} $id")
        Action.Drop
    }
}

/** `tes3cmd undelete`: turns deleted object instances in cells back into (disabled) ones. */
fun Tes3Cmd.undeleteInPlugin(plugin: String, mod: ModOptions = ModOptions()) {
    currentPlugin = plugin
    processForUpdate(plugin, mod, null) fn@{ rec ->
        if (rec.tag != "CELL") return@fn Action.Keep
        val out = ArrayList<Sub>()
        val objects = ArrayList<String>()
        var name: String? = null
        var changed = false
        for (s in rec.subs) {
            if (s.tag == "DELE") {
                changed = true
                if (verbose && name != null) objects.add(name)
                out.add(Sub("ZNAM", byteArrayOf(0)))
                out.add(Sub("DATA", ByteArray(24)))
            } else {
                if (s.tag == "NAME") name = zstr(s.data)
                out.add(s)
            }
        }
        if (!changed) return@fn Action.Keep
        print("Undeleted objects in CELL: ${rec.id()}")
        for (o in objects) print("\t$o")
        Action.Replace(Rec("CELL", 0, joinSubs(out)))
    }
}

/** What `tes3cmd dump` prints for each matching record, with [Tes3Cmd.out]. */
class DumpOptions(
    val selection: RecordSelection = RecordSelection(),
    /** One line per record instead of the full text. */
    val list: Boolean = false,
    val noBanner: Boolean = false,
    /** Receives matching records as binary data (header included) instead of printing text. */
    val binary: ((ByteArray) -> Unit)? = null,
)

// Record types whose FNAM subrecord is the object's display name.
private val fnamIsName = ("ACTI ALCH APPA ARMO BOOK BSGN CLAS CLOT CONT CREA DOOR FACT INGR LIGH LOCK MISC NPC_ PROB RACE REGN REPA SPEL WEAP").split(' ').toSet()

/** Dumps several plugins; like tes3cmd, the "Plugin:" banner only appears when there is more than one. */
fun Tes3Cmd.dumpPlugins(plugins: List<String>, o: DumpOptions) {
    val o2 = if (plugins.size == 1) DumpOptions(o.selection, o.list, true, o.binary) else o
    for (p in plugins) dumpPlugin(p, o2)
}

fun Tes3Cmd.dumpPlugin(plugin: String, o: DumpOptions) {
    currentPlugin = plugin
    var banner = if (o.noBanner) "" else "\nPlugin: $plugin\n"
    val sel = o.selection
    try {
        fs.eachRecord(plugin, warn = { message("new_from_input(): $it") }) { rec ->
            if (!sel.matches(rec)) return@eachRecord true
            val id = rec.id()
            if (banner.isNotEmpty()) { out(banner); banner = "" }
            fun listLine(prefix: String) {
                if (rec.tag == "CELL") {
                    val n = rec.subs.count { it.tag == "FRMR" }
                    out("$prefix ${rec.tag}: ${id.padEnd(45)}\t${n.toString().padStart(5)} objects\n")
                } else {
                    val fnam = rec.subs.firstOrNull { it.tag == "FNAM" && rec.tag in fnamIsName }?.let { zstr(it.data) }
                    out("$prefix ${rec.tag}: $id${if (fnam != null) " ($fnam)" else ""}\n")
                }
            }
            if (o.list) listLine("")
            else if (sel.deletesInstances && rec.tag == "CELL") {
                val groups = cellGroups(rec.subs)
                val kept = ArrayList<Sub>(groups[0])
                var selected = 0
                for (g in groups.drop(1)) {
                    if (sel.groupMatches(g.joinToString("\n") { subText(it) })) { kept.addAll(g); selected++ }
                }
                if (selected > 0) {
                    val nr = Rec("CELL", 0, joinSubs(kept))
                    if (o.binary != null) { listLine("Raw Output:"); o.binary.invoke(ByteBuilder().writeRec(nr).toByteArray()) } else out(recordText(nr) + "\n")
                }
            } else if (o.binary != null) {
                listLine("Raw Output:")
                o.binary.invoke(ByteBuilder().writeRec(rec).toByteArray())
            } else {
                out("\n${recordText(rec)}\n")
            }
            true
        }
    } catch (e: ToolException) {
        message(e.message ?: "error")
    }
}

class DiffOptions(
    /** Record types (or `TYPE.SUBT`) to leave out of the comparison. */
    val ignoreTypes: List<String> = emptyList(),
    val oneNotTwo: Boolean = false,
    val twoNotOne: Boolean = false,
    val equal: Boolean = false,
    val notEqual: Boolean = false,
    val sortSubrecords: Boolean = false,
) {
    /** With no report selected, tes3cmd reports everything. */
    fun resolved() = if (oneNotTwo || twoNotOne || equal || notEqual) this else DiffOptions(ignoreTypes, true, true, true, true, sortSubrecords)
}

/** `tes3cmd diff`: reports the records two plugins do not share, or share but define differently. */
fun Tes3Cmd.diffPlugins(plugin1: String, plugin2: String, options: DiffOptions) {
    val o = options.resolved()
    val ignoreRec = o.ignoreTypes.map { it.uppercase().substringBefore('.') }.toSet()
    val ignoreSub = HashMap<String, MutableSet<String>>()
    for (t in o.ignoreTypes) {
        val (rt, st) = t.uppercase().split('.', limit = 2).let { it[0] to it.getOrNull(1) }
        if (st != null && st.isNotBlank()) ignoreSub.getOrPut(rt) { LinkedHashSet() }.add(st)
    }
    val ignoreSubRe = ignoreSub.mapValues { (_, v) -> Regex("^(\\s*\\*?(?:" + v.joinToString("|") + "):)") }
    fun read(p: String): HashMap<String, HashMap<String, Rec>> {
        val m = HashMap<String, HashMap<String, Rec>>()
        fs.eachRecord(p, warn = { message("new_from_input(): $it") }) { r ->
            if (r.flags and HDR_IGNORED == 0L) m.getOrPut(r.id()) { HashMap() }[r.tag] = r
            true
        }
        return m
    }
    val obj1 = read(plugin1)
    val obj2 = read(plugin2)
    if (verbose) { print("Plugin1: $plugin1"); print("Plugin2: $plugin2") }
    val p1Not2 = ArrayList<String>(); val p2Not1 = ArrayList<String>(); val equal = ArrayList<String>(); val differ = ArrayList<String>()
    val diff1 = ArrayList<String>(); val diff2 = ArrayList<String>()
    fun norm(r: Rec): String {
        var t = recordText(r)
        ignoreSubRe[r.tag]?.let { re -> t = t.lines().joinToString("\n") { l -> re.find(l)?.let { m -> "${m.groupValues[1]} [IGNORED]" } ?: l } }
        if (r.tag == "CELL") t = Regex("^(\\s*?\\*?FRMR:.*?MastIdx:)\\d+", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)).replace(t, "$1 [IGNORED]")
        return t
    }
    for ((id, types) in obj1) for ((rt, r1) in types) {
        if (rt in ignoreRec) continue
        val r2 = obj2[id]?.get(rt)
        if (r2 != null) {
            val a = norm(r1); val b = norm(r2)
            val ca = if (o.sortSubrecords) a.split('\n').sorted().joinToString("\n") else a.lowercase()
            val cb = if (o.sortSubrecords) b.split('\n').sorted().joinToString("\n") else b.lowercase()
            if (ca == cb) { if (o.equal) equal.add("$rt: $id") }
            else if (o.notEqual) { differ.add("$rt: $id"); diff1.add("\n$rt: $id\n$a\n"); diff2.add("\n$rt: $id\n$b\n") }
        } else if (o.oneNotTwo) p1Not2.add("$rt: $id")
    }
    for ((id, types) in obj2) for (rt in types.keys) if (rt !in ignoreRec && obj1[id]?.get(rt) == null && o.twoNotOne) p2Not1.add("$rt: $id")
    if (o.oneNotTwo && p1Not2.isNotEmpty()) { out("\nRecords in \"$plugin1\" not in \"$plugin2\" (${p1Not2.size}):\n"); p1Not2.sorted().forEach { out("$it\n") } }
    if (o.twoNotOne && p2Not1.isNotEmpty()) { out("\nRecords in \"$plugin2\" not in \"$plugin1\" (${p2Not1.size}):\n"); p2Not1.sorted().forEach { out("$it\n") } }
    if (o.equal && equal.isNotEmpty()) { out("\nRecords that are equal in \"$plugin1\" and \"$plugin2\" (${equal.size}):\n"); equal.sorted().forEach { out("$it\n") } }
    if (o.notEqual) {
        val f1 = "$plugin1-diff.txt"; val f2 = "$plugin2-diff.txt"
        if (differ.isNotEmpty()) {
            out("\nRecords that are different between \"$plugin1\" and \"$plugin2\" (${differ.size}):\n(Compare $f1 to $f2)\n")
            differ.sorted().forEach { out("$it\n") }
        }
        out("\n")
        for ((f, d) in listOf(f1 to diff1, f2 to diff2)) {
            fs.delete(f)
            val w = fs.openWrite(f)
            try { val b = d.sorted().joinToString("").encodeToByteArray(); w.write(b, 0, b.size) } finally { w.close() }
            out("Diff written to: $f\n")
        }
    }
}

/** `tes3cmd overdial`: dialogue topics whose names contain other topics' names (a source of missing-topic bugs). */
fun Tes3Cmd.overdial(plugins: List<String>, singlePlugin: Boolean = false) {
    val dialog = LinkedHashMap<String, MutableSet<String>>()
    for (p in plugins) {
        currentPlugin = p
        try {
            fs.eachRecord(p, warn = { message("new_from_input(): $it") }) { r ->
                if (r.flags and HDR_IGNORED == 0L && r.tag == "DIAL") {
                    val type = r.subs.firstOrNull { it.tag == "DATA" }?.data?.firstOrNull()?.toInt() ?: -1
                    val id = r.id()
                    if (type == 0 && id.isNotEmpty()) dialog.getOrPut(p) { sortedSetLike() }.add(id)
                }
                true
            }
        } catch (e: ToolException) { error(e.message ?: "error") }
    }
    val test = if (singlePlugin) plugins.take(1) else dialog.keys.sorted()
    for (p1 in dialog.keys.sorted()) for (p2 in test) {
        if (p1 == p2) continue
        for (id1 in dialog[p1].orEmpty().sorted()) for (id2 in dialog[p2].orEmpty().sorted()) {
            if (id1.length > id2.length && runCatching { Regex("\\b$id2\\b", RegexOption.IGNORE_CASE).containsMatchIn(id1) }.getOrDefault(false)) {
                out("${"$p1:".padEnd(40)} \"$id1\"\n${"$p2:".padEnd(40)} \"$id2\"\n\n")
            }
        }
    }
}

private fun sortedSetLike(): MutableSet<String> = LinkedHashSet()

/** `tes3cmd recover`: keeps the records of a damaged plugin that can still be read. */
fun Tes3Cmd.recoverPlugin(plugin: String, mod: ModOptions = ModOptions()) {
    currentPlugin = plugin
    if (!Regex("\\.es[mps]$", RegexOption.IGNORE_CASE).containsMatchIn(plugin)) fail("\"$plugin\" does not end in .esm/.esp/.ess")
    if (!fs.isFile(plugin)) fail("Invalid input file")
    val size = fs.size(plugin)
    val data = ByteArray(size.toInt())
    fs.openRead(plugin).let { r -> try { var g = 0; while (g < data.size) { val n = r.read(g.toLong(), data, g, data.size - g); if (n <= 0) break; g += n } } finally { r.close() } }
    out("$plugin: length = $size\n")
    val tmp = "$plugin.tmp"
    fs.delete(tmp)
    val w = OutFile(fs.openWrite(tmp))
    val removed = ArrayList<Pair<Int, Int>>()
    var recovered = 0
    var pos = 0
    var expected: String? = "TES3"
    val typeRe = Regex(RECTYPES.joinToString("|"))
    val scanChunk = 4096 + 3
    read@ while (pos < data.size) {
        var rec: Rec? = null
        try {
            if (data.size - pos < HEADER_SIZE) fail("Read Error on header")
            val tag = tagString(data, pos)
            val len = u32(data, pos + 4)
            val flags = u32(data, pos + 12)
            if (len > data.size - pos - HEADER_SIZE) fail("Read Error: short record")
            if (tag != "TES3" && expected != null) message("new_from_input(): Type Error (at byte: $pos): Expected: \"$expected\", got: \"$tag\"")
            expected = null
            if (tag !in RECTYPES) fail("Error, Invalid record type: $tag")
            // A record with no data cannot be decoded.
            if (len == 0L) fail("decode(): recbuf not set")
            val r = Rec(tag, flags, data.copyOfRange(pos + HEADER_SIZE, pos + HEADER_SIZE + len.toInt()))
            val valid = SUBTYPES[tag].orEmpty()
            for (sub in r.subs) if (sub.tag !in valid) error("Encountered unknown subtype: $tag::${sub.tag}")
            rec = r
        } catch (e: ToolException) { /* falls through to the scan */ }
        if (rec != null) {
            w.write(rec)
            recovered++
            pos += HEADER_SIZE + rec.body.size
        } else {
            val start = pos
            out("READ ERROR on record starting at: $start\n")
            pos += 1
            out("Scanning for records from: $pos\n")
            // tes3cmd reads one chunk; with no record type in it, the scan "finds" the end of the chunk.
            val n = minOf(scanChunk, data.size - pos)
            if (n <= 0) break@read
            val chunk = CharArray(n) { (data[pos + it].toInt() and 0xff).toChar() }.concatToString()
            val m = typeRe.find(chunk)
            val newOffset = pos + (m?.range?.first ?: n)
            out("Scan found ${m?.value ?: ""} at: $newOffset\n")
            pos = newOffset
            removed.add(start to pos)
        }
    }
    if (recovered > 0 && removed.isNotEmpty()) {
        w.close()
        val n = removed.size
        out("Removed $n section${if (n > 1) "s" else ""} of bad data:\n")
        var total = 0
        for ((a, b) in removed) { out("  From: ${a.toString().padStart(8)} To: ${b.toString().padStart(8)}  (lost ${b - a} bytes)\n"); total += b - a }
        if (removed.size > 1) out("Total bytes lost: $total\n")
        fixOutput(plugin, true, null, mod)
    } else {
        runCatching { w.close() }
        fs.delete(tmp)
        out("Unable to recover data from $plugin\n")
    }
}
