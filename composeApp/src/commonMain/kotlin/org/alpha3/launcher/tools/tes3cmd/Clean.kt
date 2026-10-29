// SPDX-License-Identifier: GPL-3.0-or-later
package org.alpha3.launcher.tools.tes3cmd

import org.alpha3.launcher.tools.openmw.*

class CleanOptions(
    /** Remove object instances in cells that are identical to ones in a master. */
    val instances: Boolean = false,
    /** Remove interior cell AMBI/WHGT identical to a master's. */
    val cellParams: Boolean = false,
    /** Remove whole records identical to a master's. */
    val dups: Boolean = false,
    /** Remove the "evil" GMSTs the Construction Set adds for Tribunal and Bloodmoon. */
    val gmsts: Boolean = false,
    /** Remove cells that add nothing to the master's definition. */
    val junkCells: Boolean = false,
    val mod: ModOptions = ModOptions(),
) {
    /** With nothing selected, tes3cmd cleans everything that is safe to clean. */
    fun resolved(all: Boolean = false): CleanOptions =
        if (all || !(instances || cellParams || dups || gmsts || junkCells)) CleanOptions(true, true, true, true, true, mod) else this
}

private val cleanSkip = mapOf(
    "bloodmoon.esm" to "Bethesda Master",
    "fogpatch.esp" to "does not need cleaning",
    "gmst fix.esp" to "intentionally contain Evil GMSTs",
    "gmst vaccine.esp" to "intentionally contain Evil GMSTs",
    "mashed lists.esp" to "does not need cleaning",
    "merged_dialogs.esp" to "does not need cleaning, but you should delete it anyway",
    "merged_leveled_lists.esp" to "does not need cleaning, but you should use \"tes3cmd multipatch\" instead",
    "merged_objects.esp" to "does not need cleaning",
    "morrowind.esm" to "Bethesda Master",
    "multipatch.esp" to "does not need cleaning",
    "tribunal.esm" to "Bethesda Master",
)

private val dupTypes = ("ACTI ALCH APPA ARMO BODY BOOK BSGN CELL CLAS CLOT CONT CREA DOOR ENCH FACT GLOB GMST INFO INGR LAND LEVC LEVI LIGH LOCK MGEF " +
    "MISC NPC_ PGRD PROB RACE REGN REPA SCPT SKIL SNDG SOUN SPEL SSCR STAT WEAP").split(' ').toSet()

private val junkSubtypes = setOf("NAME", "DATA", "RGNN")

private val evilTribunal = mapOf(
    "scompanionshare" to "5354525620436f6d70616e696f6e205368617265",
    "scompanionwarningbuttonone" to "53545256204c657420746865206d657263656e61727920717569742e",
    "scompanionwarningbuttontwo" to "535452562052657475726e20746f20436f6d70616e696f6e20536861726520646973706c61792e",
    "scompanionwarningmessage" to "5354525620596f7572206d657263656e61727920697320706f6f726572206e6f77207468616e207768656e20686520636f6e74726163746564207769746820796f752e2020596f7572206d657263656e6172792077696c6c207175697420696620796f7520646f206e6f7420676976652068696d20676f6c64206f7220676f6f647320746f206272696e67206869732050726f6669742056616c756520746f206120706f7369746976652076616c75652e",
    "sdeletenote" to "535452562044656c657465204e6f74653f",
    "seffectsummonfabricant" to "53545256207345666665637453756d6d6f6e466162726963616e74",
    "slevitatedisabled" to "53545256204c657669746174696f6e206d6167696320646f6573206e6f7420776f726b20686572652e",
    "smagicfabricantid" to "5354525620466162726963616e74",
    "smaxsale" to "53545256204d61782053616c65",
    "sprofitvalue" to "535452562050726f6669742056616c7565",
    "steleportdisabled" to "535452562054656c65706f72746174696f6e206d6167696320646f6573206e6f7420776f726b20686572652e",
)

private val evilBloodmoon = mapOf(
    "fcombatdistancewerewolfmod" to "464c5456209a99993e", "ffleedistance" to "464c54562000803b45",
    "fwerewolfacrobatics" to "464c54562000001643", "fwerewolfagility" to "464c54562000001643",
    "fwerewolfalchemy" to "464c5456200000803f", "fwerewolfalteration" to "464c5456200000803f",
    "fwerewolfarmorer" to "464c5456200000803f", "fwerewolfathletics" to "464c54562000001643",
    "fwerewolfaxe" to "464c5456200000803f", "fwerewolfblock" to "464c5456200000803f",
    "fwerewolfbluntweapon" to "464c5456200000803f", "fwerewolfconjuration" to "464c5456200000803f",
    "fwerewolfdestruction" to "464c5456200000803f", "fwerewolfenchant" to "464c5456200000803f",
    "fwerewolfendurance" to "464c54562000001643", "fwerewolffatigue" to "464c5456200000c843",
    "fwerewolfhandtohand" to "464c5456200000c842", "fwerewolfhealth" to "464c54562000000040",
    "fwerewolfheavyarmor" to "464c5456200000803f", "fwerewolfillusion" to "464c5456200000803f",
    "fwerewolfintellegence" to "464c5456200000803f", "fwerewolflightarmor" to "464c5456200000803f",
    "fwerewolflongblade" to "464c5456200000803f", "fwerewolfluck" to "464c5456200000803f",
    "fwerewolfmagicka" to "464c5456200000c842", "fwerewolfmarksman" to "464c5456200000803f",
    "fwerewolfmediumarmor" to "464c5456200000803f", "fwerewolfmerchantile" to "464c5456200000803f",
    "fwerewolfmysticism" to "464c5456200000803f", "fwerewolfpersonality" to "464c5456200000803f",
    "fwerewolfrestoration" to "464c5456200000803f", "fwerewolfrunmult" to "464c5456200000c03f",
    "fwerewolfsecurity" to "464c5456200000803f", "fwerewolfshortblade" to "464c5456200000803f",
    "fwerewolfsilverweapondamagemult" to "464c5456200000c03f", "fwerewolfsneak" to "464c5456200000803f",
    "fwerewolfspear" to "464c5456200000803f", "fwerewolfspeechcraft" to "464c5456200000803f",
    "fwerewolfspeed" to "464c54562000001643", "fwerewolfstrength" to "464c54562000001643",
    "fwerewolfunarmored" to "464c5456200000c842", "fwerewolfwillpower" to "464c5456200000803f",
    "iwerewolfbounty" to "494e54562010270000", "iwerewolffightmod" to "494e54562064000000",
    "iwerewolffleemod" to "494e54562064000000", "iwerewolfleveltoattack" to "494e54562014000000",
    "seditnote" to "535452562045646974204e6f7465",
    "seffectsummoncreature01" to "53545256207345666665637453756d6d6f6e43726561747572653031",
    "seffectsummoncreature02" to "53545256207345666665637453756d6d6f6e43726561747572653032",
    "seffectsummoncreature03" to "53545256207345666665637453756d6d6f6e43726561747572653033",
    "seffectsummoncreature04" to "53545256207345666665637453756d6d6f6e43726561747572653034",
    "seffectsummoncreature05" to "53545256207345666665637453756d6d6f6e43726561747572653035",
    "smagiccreature01id" to "5354525620734d61676963437265617475726530314944",
    "smagiccreature02id" to "5354525620734d61676963437265617475726530324944",
    "smagiccreature03id" to "5354525620734d61676963437265617475726530334944",
    "smagiccreature04id" to "5354525620734d61676963437265617475726530344944",
    "smagiccreature05id" to "5354525620734d61676963437265617475726530354944",
    "swerewolfalarmmessage" to "5354525620596f752068617665206265656e206465746563746564206368616e67696e672066726f6d20612077657265776f6c662073746174652e",
    "swerewolfpopup" to "535452562057657265776f6c66",
    "swerewolfrefusal" to "5354525620596f752063616e6e6f7420646f207468697320617320612077657265776f6c662e",
    "swerewolfrestmessage" to "5354525620596f752063616e6e6f74207265737420696e2077657265776f6c6620666f726d2e",
)

private fun hex(b: ByteArray) = b.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

/** Offsets of the records of a master, found without keeping their bytes in memory. */
internal class MasterIndex(private val cmd: Tes3Cmd) {
    class Entry(val offset: Long, val length: Int, val flags: Long)
    private class Master(val reader: ToolReader, val ids: HashMap<String, HashMap<String, Entry>>)

    private val masters = HashMap<String, Master>()

    fun has(name: String) = lcAscii(name) in masters

    /** Reads the records of [name] (found as [pluginDir] or the master directories), once. */
    fun load(name: String, pluginDir: String) {
        val key = lcAscii(name)
        if (key in masters) return
        cmd.out("Loading Master: $key\n")
        val path = cmd.findMaster(name, pluginDir)
        if (path == null) {
            cmd.error("Master: $key not found in <DATADIR>")
            return
        }
        val size = cmd.fs.size(path)
        val rr = RecReader(cmd.fs.openRead(path), size, key)
        val ids = HashMap<String, HashMap<String, Entry>>()
        try {
            while (true) {
                val start = rr.pos
                val rec = rr.next(strict = true) ?: break
                if (rec.flags and HDR_IGNORED != 0L) continue
                ids.getOrPut(rec.id()) { HashMap() }[rec.tag] = Entry(start + HEADER_SIZE, rec.body.size, rec.flags)
            }
        } catch (e: ToolException) {
            cmd.error(e.message ?: "error")
        } finally {
            rr.close()
        }
        masters[key] = Master(cmd.fs.openRead(path), ids)
    }

    fun entry(master: String, id: String, tag: String): Entry? = masters[lcAscii(master)]?.ids?.get(id)?.get(tag)

    fun body(master: String, e: Entry): ByteArray {
        val r = masters.getValue(lcAscii(master)).reader
        val b = ByteArray(e.length)
        var got = 0
        while (got < b.size) {
            val n = r.read(e.offset + got, b, got, b.size - got)
            if (n <= 0) fail("unexpected end of master")
            got += n
        }
        return b
    }

    fun close() = masters.values.forEach { it.reader.close() }
}

/** A cell's subrecords split like tes3cmd does: everything up to the first FRMR, then one group per FRMR. */
private fun splitGroups(subs: List<Sub>): List<List<Sub>> {
    val groups = ArrayList<MutableList<Sub>>()
    var cur = ArrayList<Sub>()
    for (s in subs) {
        if (s.tag == "FRMR") { groups.add(cur); cur = ArrayList() }
        cur.add(s)
    }
    groups.add(cur)
    return groups
}

private class Buffalized(val objIdx: Long, val buf: ByteArray, val name: String?)

private fun buffalize(group: List<Sub>): Buffalized {
    val frmr = group[0]
    if (frmr.tag != "FRMR" && frmr.tag != "MVRF") fail("Oops! Expected first subrec of group to be FRMR, instead got: ${frmr.tag}")
    val objIdx = if (frmr.data.size >= 4) u32(frmr.data) and 0xFFFFFF else 0L
    var name: String? = null
    val b = ByteBuilder()
    for (s in group.drop(1)) {
        if (name == null && s.tag == "NAME") name = zstr(s.data)
        b.append(s.data)
    }
    return Buffalized(objIdx, b.toByteArray(), name)
}

private fun List<Sub>.get(tag: String): Sub? = firstOrNull { it.tag == tag }

private fun Sub.floatValue(): Double? = if (data.size >= 4) Float.fromBits(i32(data)).toDouble() else null
private fun Sub.intValue(): Double? = if (data.size >= 4) i32(data).toDouble() else null

/** `tes3cmd clean` on one plugin. */
fun Tes3Cmd.cleanPlugin(plugin: String, options: CleanOptions) {
    val o = options
    currentPlugin = plugin
    if (plugin.endsWith(".ess", ignoreCase = true)) { print("tes3cmd clean skipping $plugin: don't know how to clean savegames ... yet."); return }
    if (Regex("~\\d+\\.es[mps]$", RegexOption.IGNORE_CASE).containsMatchIn(plugin)) { print("tes3cmd clean skipping $plugin: because it's a backup."); return }
    cleanSkip[plugin.lowercase()]?.let { print("tes3cmd clean skipping $plugin: $it."); return }
    out("\nCLEANING: \"$plugin\" ...\n")

    val pluginDir = dirName(plugin)
    val index = masterIndex
    val myMasters = ArrayList<String>()
    val stats = HashMap<String, Int>()
    val persistent = HashSet<String>()
    fun stat(what: String) { stats[what] = (stats[what] ?: 0) + 1 }
    val cellCache = HashMap<String, Pair<List<Sub>, HashMap<Long, ByteArray>>?>()

    // The master's version of cell [id], with its object instances keyed by object index.
    fun masterCell(master: String, id: String): Pair<List<Sub>, HashMap<Long, ByteArray>>? = cellCache.getOrPut("$master\u0000$id") {
        val e = index.entry(master, id, "CELL") ?: return@getOrPut null
        val subs = parseSubs(index.body(master, e), "CELL")
        val byIdx = HashMap<Long, ByteArray>()
        for (g in splitGroups(subs).drop(1)) {
            val bf = buffalize(g)
            if (bf.objIdx !in byIdx) byIdx[bf.objIdx] = bf.buf
        }
        subs to byIdx
    }

    val fn: (Rec) -> Action = fn@{ rec ->
        val tag = rec.tag
        var subs: List<Sub> = rec.subs
        var flags = rec.flags
        var modified = false
        var rebuilt = false

        if (o.instances && flags and HDR_PERSISTENT != 0L) persistent.add(rec.id())

        if (tag == "GMST") {
            if (o.gmsts) {
                val id = rec.id()
                val valueTag = when (id.firstOrNull()) { 'i' -> "INTV"; 'f' -> "FLTV"; 's' -> "STRV"; else -> null }
                val valSub = valueTag?.let { subs.get(it) }
                if (valSub != null) {
                    val hexval = hex("$valueTag ".encodeToByteArray() + valSub.data)
                    if (evilBloodmoon[id] == hexval) { stat("Evil-GMST Bloodmoon"); out(" Cleaned Evil-GMST Bloodmoon: $id\n"); return@fn Action.Drop }
                    if (evilTribunal[id] == hexval) { stat("Evil-GMST Tribunal"); out(" Cleaned Evil-GMST Tribunal: $id\n"); return@fn Action.Drop }
                }
            }
        } else if (tag == "CELL") {
            if (o.instances) {
                val id = rec.id()
                val groups = splitGroups(subs)
                val kept = ArrayList<Sub>(groups[0])
                var cellModified = 0
                for (g in groups.drop(1)) {
                    val name = g.firstOrNull { it.tag == "NAME" }?.let { zstr(it.data) }
                    // Note: tes3cmd compares the object's name as written against lowercase ids.
                    if (name != null && name in persistent) { kept.addAll(g); continue }
                    val bf = buffalize(g)
                    var cleaned = false
                    for (m in myMasters) {
                        val mc = masterCell(m, id) ?: continue
                        val mb = mc.second[bf.objIdx] ?: continue
                        if (mb.contentEquals(bf.buf)) { cleaned = true; break }
                    }
                    if (cleaned) {
                        cellModified++
                        stat("duplicate object instance")
                        out(" Cleaned duplicate object instance (${bf.name ?: ""} FRMR: ${bf.objIdx}) from CELL: $id\n")
                    } else kept.addAll(g)
                }
                if (cellModified > 0) {
                    subs = kept
                    flags = 0 // a rebuilt record starts with no header flags
                    rebuilt = true
                    modified = true
                }
            }
            if (o.cellParams || o.junkCells) {
                val id = recordId("CELL", subs)
                var cleanedAmbi = false
                var cleanedWhgt = false
                var cur = subs.toMutableList()
                for (m in myMasters) {
                    val mc = masterCell(m, id) ?: continue
                    val mastSubs = mc.first
                    val interior = cur.get("DATA")?.data?.let { it.size >= 4 && u32(it) and 1L != 0L } ?: false
                    if (interior && o.cellParams) {
                        if (!cleanedAmbi) {
                            val pa = cur.get("AMBI"); val ma = mastSubs.get("AMBI")
                            if (pa != null && ma != null && pa.data.contentEquals(ma.data)) { cur.removeAll { it.tag == "AMBI" }; cleanedAmbi = true }
                        }
                        if (!cleanedWhgt) {
                            val pw = cur.get("WHGT")?.floatValue()
                            val mw = mastSubs.get("WHGT")?.floatValue() ?: mastSubs.get("INTV")?.intValue()
                            if (pw != null && mw != null && pw == mw) { cur.removeAll { it.tag == "WHGT" }; cleanedWhgt = true }
                        }
                    }
                    if (o.junkCells && cur.size < 4) {
                        var newData = false
                        for (st in cur.map { it.tag }) {
                            val ps = cur.get(st)!!
                            val ms = mastSubs.get(st) ?: fail("no mast subtype: $st (id=$id)")
                            if (st !in junkSubtypes || !ps.data.contentEquals(ms.data)) { newData = true; break }
                        }
                        if (!newData) { stat("junk-CELL"); out(" Cleaned junk-CELL: $id\n"); return@fn Action.Drop }
                    }
                }
                val what = ArrayList<String>()
                if (cleanedAmbi) { what.add("AMBI"); stat("redundant CELL.AMBI") }
                if (cleanedWhgt) { what.add("WHGT"); stat("redundant CELL.WHGT") }
                if (what.isNotEmpty()) {
                    out(" Cleaned redundant ${what.joinToString(",")} from CELL: $id\n")
                    subs = cur
                    modified = true
                }
            }
        } else if (tag == "TES3") {
            for (s in subs) if (s.tag == "MAST") {
                val name = zstr(s.data)
                if (myMasters.none { lcAscii(it) == lcAscii(name) }) myMasters.add(name)
                index.load(name, pluginDir)
            }
        }

        if (o.dups) {
            val id = if (rebuilt || modified) recordId(tag, subs) else rec.id()
            for (m in myMasters) {
                val e = index.entry(m, id, tag) ?: continue
                // A cell rebuilt from groups has no original bytes to compare.
                if (tag in dupTypes && !rebuilt && e.flags == rec.flags && index.body(m, e).contentEquals(rec.body)) {
                    stat("duplicate record")
                    out(" Cleaned duplicate record ($tag): $id\n")
                    return@fn Action.Drop
                }
            }
        }
        if (modified) Action.Replace(Rec(tag, flags, joinSubs(subs))) else Action.Keep
    }

    if (!processForUpdate(plugin, o.mod, if (o.mod.overwrite) null else "Clean_", fn)) return
    if (stats.isNotEmpty()) {
        out("\nCleaning Stats for \"$plugin\":\n")
        for (k in stats.keys.sorted()) { val v = stats.getValue(k); out("  ${k.padStart(30)}:  ${v.toString().padStart(4)}\n") }
    }
}
