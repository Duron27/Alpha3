package org.alpha3.launcher.cs.save

import org.alpha3.launcher.tools.openmw.ToolFs
import org.alpha3.launcher.tools.openmw.ToolException

/*
 * An OpenMW saved game (.omwsave), opened for editing. A save is records like a plugin's (TES3 header, then SAVE,
 * then the world: globals, quests, journal, cell states, the player...), and most of it (cell states, Lua data: 60 MB
 * and more on a long game) is never touched here: only the record index is read, and on writing every record that was
 * not edited is copied byte for byte from the original. The records edited are decoded into their subrecords and
 * changed in place, following what OpenMW's components/esm3 (player, npcstats, creaturestats, inventorystate,
 * statstate, cellref) write, so the result loads like a save the game made. It is always written to a new file.
 */

internal class SaveSub(val tag: String, var data: ByteArray)

/** One record of the save: where it is in the original file, and its subrecords once decoded (then written from them). */
internal class SaveRecord(val tag: String, val header: ByteArray, val offset: Long, val size: Int) {
    var subs: MutableList<SaveSub>? = null
    var edited = false
}

// --- little-endian values ---------------------------------------------------------------------------------------------

internal fun i32(d: ByteArray, o: Int = 0) =
    (d[o].toInt() and 255) or ((d[o + 1].toInt() and 255) shl 8) or ((d[o + 2].toInt() and 255) shl 16) or (d[o + 3].toInt() shl 24)
internal fun f32(d: ByteArray, o: Int = 0) = Float.fromBits(i32(d, o))
internal fun i32Bytes(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
internal fun f32Bytes(v: Float) = i32Bytes(v.toBits())

/**
 * An id as a save stores it (ESM::RefId): a type byte, then the id. 2 is a plain string id, 4 an id the game made up
 * for an object it created (a potion, an enchanted item: shown as "generated #n"), 0 empty.
 */
internal fun refIdText(d: ByteArray): String {
    if (d.isEmpty()) return ""
    return when (d[0].toInt()) {
        0 -> ""
        1, 2 -> d.copyOfRange(1, d.size).decodeToString().trimEnd('\u0000')
        4 -> if (d.size >= 9) "generated #" + (0 until 8).fold(0L) { a, i -> a or ((d[1 + i].toLong() and 255) shl (8 * i)) } else "generated"
        else -> d.decodeToString().trimEnd('\u0000')
    }
}
internal fun refIdBytes(id: String) = byteArrayOf(2) + id.encodeToByteArray()

/** A zero-terminated or plain string subrecord, written back the way it was. */
internal fun stringOf(d: ByteArray) = d.decodeToString().trimEnd('\u0000')
internal fun stringLike(old: ByteArray, text: String): ByteArray {
    val b = text.encodeToByteArray()
    return if (old.isNotEmpty() && old.last() == 0.toByte()) b + 0 else b
}

/** A stat as OpenMW saves it (StatState): STBA base, and when not 0 STMO modifier, STCU current, STDF damage, STPR progress. */
internal class StatRef(private val subs: MutableList<SaveSub>, private var at: Int) {
    private fun groupEnd(): Int {
        var j = at + 1
        while (j < subs.size && subs[j].tag in STAT_PARTS) j++
        return j
    }
    private fun part(tag: String) = (at + 1 until groupEnd()).firstOrNull { subs[it].tag == tag }

    var base: Float
        get() = f32(subs[at].data)
        set(v) { subs[at].data = f32Bytes(v) }
    val modifier: Float get() = part("STMO")?.let { f32(subs[it].data) } ?: 0f
    var current: Float
        get() = part("STCU")?.let { f32(subs[it].data) } ?: 0f
        set(v) {
            val p = part("STCU")
            if (p != null) subs[p].data = f32Bytes(v)
            else subs.add((part("STMO") ?: at) + 1, SaveSub("STCU", f32Bytes(v)))  // the order OpenMW reads them in
        }
    val hasCurrent get() = part("STCU") != null

    companion object {
        val STAT_PARTS = setOf("STMO", "STCU", "STDF", "STDA", "STPR")
    }
}

val ATTRIBUTES = listOf("Strength", "Intelligence", "Willpower", "Agility", "Speed", "Endurance", "Personality", "Luck")
val SKILLS = listOf(
    "Block", "Armorer", "Medium Armor", "Heavy Armor", "Blunt Weapon", "Long Blade", "Axe", "Spear", "Athletics",
    "Enchant", "Destruction", "Alteration", "Illusion", "Conjuration", "Mysticism", "Restoration", "Alchemy",
    "Unarmored", "Security", "Sneak", "Acrobatics", "Light Armor", "Short Blade", "Marksman", "Mercantile",
    "Speechcraft", "Hand-to-hand",
)
val DYNAMICS = listOf("Health", "Magicka", "Fatigue")

/** One item the player carries: its subrecords from its FRMR to the next item's. */
internal class InvItem(val index: Int, val id: String, val count: Int, val condition: Int?, val charge: Float?, val equipped: Boolean)

internal class GlobalVar(val record: SaveRecord, val id: String, val type: Char) {
    val value get() = f32(record.subs!!.first { it.tag == "FLTV" }.data)
}

internal class QuestState(val record: SaveRecord, val id: String) {
    val stage get() = record.subs!!.firstOrNull { it.tag == "QSTA" }?.let { i32(it.data) } ?: 0
    val finished get() = record.subs!!.firstOrNull { it.tag == "QFIN" }?.data?.firstOrNull()?.toInt()?.let { it != 0 } ?: false
}

internal class SaveGame private constructor(private val fs: ToolFs, val path: String, val records: MutableList<SaveRecord>) {
    /** Bumped on every edit, for the screens. */
    var version = 0
        private set

    private fun rec(tag: String) = records.firstOrNull { it.tag == tag }
    private val save = rec("SAVE")
    private val play = rec("PLAY")
    private val luam = rec("LUAM")
    private val playerNpc = records.firstOrNull { it.tag == "NPC_" && it.subs?.firstOrNull { s -> s.tag == "NAME" }?.let { n -> refIdText(n.data) }.equals("player", true) }

    val globals: List<GlobalVar> = records.filter { it.tag == "GLOB" && it.subs != null }.mapNotNull { r ->
        val s = r.subs!!
        val id = s.firstOrNull { it.tag == "NAME" }?.let { refIdText(it.data) } ?: return@mapNotNull null
        if (s.none { it.tag == "FLTV" }) return@mapNotNull null
        GlobalVar(r, id, s.firstOrNull { it.tag == "FNAM" }?.data?.firstOrNull()?.toInt()?.toChar() ?: 'f')
    }.sortedBy { it.id.lowercase() }

    /** The quests the player has started (a new game has few or none: [startQuest] adds one). */
    val quests: List<QuestState>
        get() = records.filter { it.tag == "QUES" && it.subs != null }.mapNotNull { r ->
            val id = r.subs!!.firstOrNull { it.tag == "YETO" }?.let { refIdText(it.data) } ?: return@mapNotNull null
            QuestState(r, id)
        }.sortedBy { it.id.lowercase() }

    /**
     * Starts quest [id] (a journal topic of the content) at [stage], or sets its stage when it is already started.
     * The game writes its quests straight after the SAVE header, before the journal entries: it goes after the last.
     */
    fun startQuest(id: String, stage: Int) {
        val name = id.trim()
        if (name.isEmpty()) return
        quests.firstOrNull { it.id.equals(name, true) }?.let { setQuest(it, stage, null); return }
        val after = records.indexOfLast { it.tag == "QUES" }.takeIf { it >= 0 } ?: records.indexOfFirst { it.tag == "SAVE" }
        val rec = SaveRecord("QUES", ByteArray(8), -1, 0).also {
            it.subs = mutableListOf(SaveSub("YETO", refIdBytes(name)), SaveSub("QSTA", i32Bytes(stage)), SaveSub("QFIN", byteArrayOf(0)))
        }
        records.add(after + 1, rec)
        edit(rec)
    }

    val journalEntries = records.count { it.tag == "JOUR" }

    private fun edit(r: SaveRecord?) { if (r != null) { r.edited = true; version++ } }
    private fun sub(r: SaveRecord?, tag: String) = r?.subs?.firstOrNull { it.tag == tag }

    // --- the SAVE header: what the game's load menu shows --------------------------------------------------------------

    var description: String
        get() = sub(save, "DESC")?.let { stringOf(it.data) } ?: ""
        set(v) { sub(save, "DESC")?.let { it.data = stringLike(it.data, v); edit(save) } }
    val cellName get() = sub(save, "PLCE")?.let { stringOf(it.data) } ?: ""
    val className get() = sub(save, "PLCL")?.let { stringOf(it.data) } ?: ""
    val gameDays get() = sub(save, "CDAY")?.let { i32(it.data) } ?: 0
    /** The screenshot of the save (a JPEG), for the list. */
    val screenshot get() = sub(save, "SCRN")?.data
    val masters: List<String> get() = rec("TES3")?.subs?.filter { it.tag == "MAST" }?.map { stringOf(it.data) }.orEmpty()

    var playerName: String
        get() = sub(save, "PLNA")?.let { stringOf(it.data) } ?: ""
        set(v) {
            sub(save, "PLNA")?.let { it.data = stringLike(it.data, v); edit(save) }
            sub(playerNpc, "FNAM")?.let { it.data = stringLike(it.data, v); edit(playerNpc) }
        }

    // --- the player (PLAY): its inventory, then its NPC stats, then its creature stats --------------------------------

    private val ps get() = play?.subs

    /** Where the parts of the player record are, found again after every change (subrecords come and go). */
    private class Layout(
        val icnt: Int, val items: List<IntRange>, val eqip: Int, val sele: Int,
        val skills: List<Int>, val attributes: List<Int>, val dynamics: List<Int>,
        val afterSkills: Int, val levl: Int,
    )

    private fun layout(): Layout? {
        val s = ps ?: return null
        val icnt = s.indexOfFirst { it.tag == "ICNT" }
        val items = ArrayList<IntRange>()
        if (icnt >= 0) {
            val n = i32(s[icnt].data)
            var i = icnt + 1
            repeat(n) { k ->
                if (i >= s.size || s[i].tag != "FRMR") return null  // not laid out as expected: no inventory editing
                var j = i + 1
                if (k < n - 1) while (j < s.size && s[j].tag != "FRMR") j++
                else while (j < s.size && s[j].tag !in AFTER_ITEMS) j++
                items += i until j
                i = j
            }
        }
        fun groupEnd(at: Int): Int {
            var j = at + 1
            while (j < s.size && s[j].tag in StatRef.STAT_PARTS) j++
            return j
        }
        fun norm(t: String) = t.filter { it.isLetterOrDigit() }.lowercase()
        // Newer saves (format 38 and on) name each stat: SKIL <id> or ATTR <id>, then its StatState, sorted by id.
        // Older ones write all 27 skills, then all 8 attributes and the 3 dynamic stats, in the game's fixed order.
        val named = { tag: String, names: List<String> ->
            val byName = HashMap<String, Int>()
            for (i in s.indices) if (s[i].tag == tag && i + 1 < s.size && s[i + 1].tag == "STBA") byName[norm(refIdText(s[i].data))] = i + 1
            if (byName.isEmpty()) null else names.map { byName[norm(it)] ?: -1 }
        }
        var skills: List<Int> = named("SKIL", SKILLS) ?: emptyList()
        var attributes: List<Int> = named("ATTR", ATTRIBUTES) ?: emptyList()
        var dynamics: List<Int> = emptyList()
        // runs of consecutive stats: STBA [STMO STCU STDF STPR] STBA ...
        val runs = ArrayList<List<Int>>()
        var i = 0
        while (i < s.size) {
            if (s[i].tag == "STBA") {
                val run = ArrayList<Int>()
                while (i < s.size && s[i].tag == "STBA") { run += i; i = groupEnd(i) }
                runs += run
            } else i++
        }
        if (skills.isEmpty()) skills = runs.firstOrNull { it.size == SKILLS.size }.orEmpty()
        if (attributes.isEmpty()) {
            val run = runs.firstOrNull { it.size == ATTRIBUTES.size + DYNAMICS.size && (skills.isEmpty() || it[0] > skills.max()) }
            attributes = run?.take(ATTRIBUTES.size).orEmpty()
            dynamics = run?.drop(ATTRIBUTES.size).orEmpty()
        } else {
            // the three dynamic stats come straight after the last attribute
            val lastAttr = attributes.filter { it >= 0 }.maxOrNull()
            if (lastAttr != null) {
                var j = groupEnd(lastAttr)
                val dyn = ArrayList<Int>()
                while (dyn.size < DYNAMICS.size && j < s.size && s[j].tag == "STBA") { dyn += j; j = groupEnd(j) }
                if (dyn.size == DYNAMICS.size) dynamics = dyn
            }
        }
        var after = -1
        val lastSkill = skills.filter { it >= 0 }.maxOrNull()
        if (lastSkill != null) {
            after = groupEnd(lastSkill)
            if (after < s.size && s[after].tag == "WOLF") after++
        }
        return Layout(
            icnt, items, s.indexOfFirst { it.tag == "EQIP" }, s.indexOfFirst { it.tag == "SELE" },
            skills, attributes, dynamics, after, s.indexOfFirst { it.tag == "LEVL" },
        )
    }

    /** Whatever of the stats the save has can be edited: each one shows when it is there. */
    val canEditPlayer get() = layout()?.let { l -> (l.skills + l.attributes + l.dynamics).any { it >= 0 } } ?: false
    /** An empty inventory has no item count at all: the first item added makes it, where the game writes it. */
    val canEditInventory get() = layout()?.let { it.icnt >= 0 || inventoryAnchor() >= 0 } ?: false
    private fun inventoryAnchor() = ps?.indexOfFirst { it.tag in AFTER_ITEMS } ?: -1

    fun attribute(i: Int) = layout()?.attributes?.getOrNull(i)?.takeIf { it >= 0 }?.let { StatRef(ps!!, it) }
    fun skill(i: Int) = layout()?.skills?.getOrNull(i)?.takeIf { it >= 0 }?.let { StatRef(ps!!, it) }
    fun dynamic(i: Int) = layout()?.dynamics?.getOrNull(i)?.let { StatRef(ps!!, it) }

    fun setAttribute(i: Int, v: Float) { attribute(i)?.base = v; edit(play) }
    fun setSkill(i: Int, v: Float) { skill(i)?.base = v; edit(play) }
    fun setDynamic(i: Int, base: Float?, current: Float?) {
        val d = dynamic(i) ?: return
        base?.let { d.base = it }
        current?.let { d.current = it }
        edit(play)
    }

    var level: Int
        get() = layout()?.levl?.takeIf { it >= 0 }?.let { i32(ps!![it].data) } ?: sub(save, "PLLE")?.let { i32(it.data) } ?: 1
        set(v) {
            layout()?.levl?.takeIf { it >= 0 }?.let { ps!![it].data = i32Bytes(v); edit(play) }
            sub(save, "PLLE")?.let { it.data = i32Bytes(v); edit(save) }
            sub(playerNpc, "NPDT")?.let { if (it.data.size >= 2) { it.data[0] = v.toByte(); it.data[1] = (v shr 8).toByte(); edit(playerNpc) } }
        }

    /** An optional int of the NPC stats that goes right after the skills (bounty, reputation), in OpenMW's order. */
    private fun npcInt(tag: String) = ps?.let { s ->
        val l = layout() ?: return null
        if (l.afterSkills < 0) return null
        (l.afterSkills until minOf(s.size, l.afterSkills + 6)).firstOrNull { s[it].tag == tag }?.let { i32(s[it].data) }
    }
    private fun setNpcInt(tag: String, v: Int, order: List<String>) {
        val s = ps ?: return
        val l = layout() ?: return
        if (l.afterSkills < 0) return
        val window = l.afterSkills until minOf(s.size, l.afterSkills + 6)
        val at = window.firstOrNull { s[it].tag == tag }
        if (at != null) s[at].data = i32Bytes(v)
        else {
            // after the ones that come before it
            var pos = l.afterSkills
            while (pos < s.size && s[pos].tag in order.subList(0, order.indexOf(tag))) pos++
            s.add(pos, SaveSub(tag, i32Bytes(v)))
        }
        edit(play)
    }
    private val npcOrder = listOf("BOUN", "REPU", "WKIL", "LPRO")
    var bounty: Int
        get() = npcInt("BOUN") ?: 0
        set(v) = setNpcInt("BOUN", v, npcOrder)
    var reputation: Int
        get() = npcInt("REPU") ?: 0
        set(v) = setNpcInt("REPU", v, npcOrder)

    /** The player's factions: id, rank (-1 none), reputation, expelled. */
    class Faction(val id: String, val rank: Int, val reputation: Int, val expelled: Boolean)

    val factions: List<Faction>
        get() {
            val s = ps ?: return emptyList()
            val out = ArrayList<Faction>()
            var i = 0
            while (i < s.size) {
                if (s[i].tag == "FACT") {
                    val id = refIdText(s[i].data)
                    var rank = -1; var rep = 0; var exp = false
                    var j = i + 1
                    while (j < s.size && s[j].tag in setOf("FAEX", "FARA", "FARE")) {
                        when (s[j].tag) { "FARA" -> rank = i32(s[j].data); "FARE" -> rep = i32(s[j].data); "FAEX" -> exp = i32(s[j].data) != 0 }
                        j++
                    }
                    out += Faction(id, rank, rep, exp)
                    i = j
                } else i++
            }
            return out
        }

    fun setFaction(id: String, rank: Int?, reputation: Int?) {
        val s = ps ?: return
        val at = s.indexOfFirst { it.tag == "FACT" && refIdText(it.data) == id }
        if (at < 0) return
        fun set(tag: String, v: Int, after: List<String>) {
            var j = at + 1
            var found = -1
            while (j < s.size && s[j].tag in setOf("FAEX", "FARA", "FARE")) { if (s[j].tag == tag) found = j; j++ }
            if (found >= 0) s[found].data = i32Bytes(v)
            else {
                var pos = at + 1
                while (pos < s.size && s[pos].tag in after) pos++
                s.add(pos, SaveSub(tag, i32Bytes(v)))
            }
        }
        rank?.let { set("FARA", it, listOf("FAEX")) }
        reputation?.let { set("FARE", it, listOf("FAEX", "FARA")) }
        edit(play)
    }

    // --- inventory -------------------------------------------------------------------------------------------------

    private fun equippedIndices(l: Layout): Set<Int> {
        if (l.eqip < 0) return emptySet()
        val d = ps!![l.eqip].data
        val n = i32(d)
        return (0 until n).mapNotNull { k -> if (8 + k * 8 <= d.size) i32(d, 4 + k * 8) else null }.toSet()
    }

    val items: List<InvItem>
        get() {
            val l = layout() ?: return emptyList()
            val s = ps!!
            val eq = equippedIndices(l)
            return l.items.mapIndexed { k, r ->
                fun find(tag: String) = r.firstOrNull { s[it].tag == tag }?.let { s[it].data }
                InvItem(
                    k, find("NAME")?.let(::refIdText) ?: "?", find("NAM9")?.let { i32(it) } ?: 1,
                    find("INTV")?.let { i32(it) }, find("XCHG")?.let { f32(it) }, k in eq,
                )
            }
        }

    fun setItemCount(index: Int, count: Int) {
        val l = layout() ?: return
        val r = l.items.getOrNull(index) ?: return
        val s = ps!!
        val at = r.firstOrNull { s[it].tag == "NAM9" }
        if (at != null) {
            if (count == 1) s.removeAt(at) else s[at].data = i32Bytes(count)  // 1 is not written
        } else if (count != 1) {
            // NAM9 comes after the other reference fields and before the object state's (HLOC, LUAS, FLAG...)
            var pos = r.first + 2
            while (pos <= r.last && s[pos].tag in REF_FIELDS) pos++
            s.add(pos, SaveSub("NAM9", i32Bytes(count)))
        }
        edit(play)
    }

    fun setItemCondition(index: Int, value: Int) {
        val l = layout() ?: return
        val r = l.items.getOrNull(index) ?: return
        val s = ps!!
        val at = r.firstOrNull { s[it].tag == "INTV" && it < (r.firstOrNull { k -> s[k].tag == "HLOC" } ?: Int.MAX_VALUE) } ?: return
        s[at].data = i32Bytes(value)
        edit(play)
    }

    fun removeItem(index: Int) {
        val l = layout() ?: return
        val r = l.items.getOrNull(index) ?: return
        val s = ps!!
        // what is equipped or selected points at items by their place: those after it move up one
        if (l.eqip >= 0) {
            val d = s[l.eqip].data
            val pairs = (0 until i32(d)).map { k -> i32(d, 4 + k * 8) to i32(d, 8 + k * 8) }
                .filter { it.first != index }.map { (item, slot) -> (if (item > index) item - 1 else item) to slot }
            s[l.eqip].data = i32Bytes(pairs.size) + pairs.flatMap { (a, b) -> (i32Bytes(a) + i32Bytes(b)).toList() }.toByteArray()
        }
        if (l.sele >= 0) {
            val sel = i32(s[l.sele].data)
            if (sel == index) s.removeAt(l.sele) else if (sel > index) s[l.sele].data = i32Bytes(sel - 1)
        }
        for (k in r.reversed()) s.removeAt(k)
        val left = i32(s[l.icnt].data) - 1
        // the game writes no count for an empty inventory
        if (left <= 0) s.removeAt(l.icnt) else s[l.icnt].data = i32Bytes(left)
        edit(play)
    }

    /** Adds [count] of [id] (an item's id from the game's or a mod's content) at the end of the inventory. */
    fun addItem(id: String, count: Int) {
        var l = layout() ?: return
        if (id.isBlank()) return
        val s = ps!!
        if (l.icnt < 0) {
            // no inventory yet: its count goes before what InventoryState and the stats write after the items
            val at = inventoryAnchor()
            if (at < 0) return
            s.add(at, SaveSub("ICNT", i32Bytes(0)))
            l = layout() ?: return
        }
        val insertAt = l.items.lastOrNull()?.let { it.last + 1 } ?: (l.icnt + 1)
        val add = mutableListOf(SaveSub("FRMR", newRefNum()), SaveSub("NAME", refIdBytes(id.trim())))
        if (count != 1) add += SaveSub("NAM9", i32Bytes(count))
        s.addAll(insertAt, add)
        s[l.icnt].data = i32Bytes(i32(s[l.icnt].data) + 1)
        edit(play)
    }

    /** A reference number for a new object: the game's last generated one (kept by the Lua manager) plus one. */
    private fun newRefNum(): ByteArray {
        val f = sub(luam, "FRMR")
        if (f != null && f.data.size >= 8) {
            val next = i32(f.data) + 1
            f.data = i32Bytes(next) + f.data.copyOfRange(4, 8)
            edit(luam)
            return i32Bytes(next) + i32Bytes(-1)
        }
        // no counter: past every generated number the inventory has
        val used = ps!!.filter { it.tag == "FRMR" && it.data.size >= 8 && i32(it.data, 4) == -1 }.maxOfOrNull { i32(it.data) } ?: 0
        return i32Bytes(used + 1000) + i32Bytes(-1)
    }

    val gold: Int get() = items.firstOrNull { it.id.equals("gold_001", true) }?.count ?: 0
    fun setGold(v: Int) {
        val g = items.firstOrNull { it.id.equals("gold_001", true) }
        when {
            g != null && v <= 0 -> removeItem(g.index)
            g != null -> setItemCount(g.index, v)
            v > 0 -> addItem("gold_001", v)
        }
    }

    // --- globals and quests -------------------------------------------------------------------------------------------

    fun setGlobal(g: GlobalVar, v: Float) {
        g.record.subs!!.first { it.tag == "FLTV" }.data = f32Bytes(if (g.type == 'f') v else v.toInt().toFloat())
        edit(g.record)
    }

    fun setQuest(q: QuestState, stage: Int?, finished: Boolean?) {
        val s = q.record.subs!!
        stage?.let { v -> s.firstOrNull { it.tag == "QSTA" }?.data = i32Bytes(v) }
        finished?.let { v -> s.firstOrNull { it.tag == "QFIN" }?.data = byteArrayOf(if (v) 1 else 0) }
        edit(q.record)
    }

    val edited get() = records.any { it.edited }

    // --- writing ------------------------------------------------------------------------------------------------------

    /** Writes the save, edited, to [target] (never over the file it was opened from). */
    fun writeTo(target: String, progress: (Float) -> Unit = {}) {
        if (target == path) throw ToolException("Save to a new file: the original is kept as it is")
        val tmp = "$target.part"
        val reader = fs.openRead(path)
        val out = fs.openWrite(tmp)
        try {
            val buf = ByteArray(1 shl 20)
            val total = records.sumOf { 16L + it.size }
            var done = 0L
            for (r in records) {
                if (r.subs != null && (r.edited || r.offset < 0)) {
                    val body = encodeSubs(r.subs!!)
                    out.write(r.tag.encodeToByteArray(), 0, 4)
                    out.write(i32Bytes(body.size), 0, 4)
                    out.write(r.header, 0, 8)
                    out.write(body, 0, body.size)
                } else {
                    var left = 16L + r.size
                    var at = r.offset
                    while (left > 0) {
                        val n = minOf(left, buf.size.toLong()).toInt()
                        readFully(reader, at, buf, n)
                        out.write(buf, 0, n)
                        at += n; left -= n
                    }
                }
                done += 16L + r.size
                progress(done.toFloat() / total)
            }
        } catch (e: Throwable) {
            runCatching { out.close() }
            runCatching { fs.delete(tmp) }
            throw e
        } finally {
            reader.close()
        }
        out.close()
        if (fs.isFile(target)) fs.delete(target)
        fs.rename(tmp, target)
    }

    companion object {
        /** Subrecords that end the inventory's last item: what InventoryState, NpcStats and CreatureStats write next. */
        private val AFTER_ITEMS = setOf("MAGI", "EQIP", "SELE", "FACT", "DISP", "DISM", "STBA", "SKIL")
        /** A reference's own fields in an inventory item (CellRef::save), before the object's state. */
        private val REF_FIELDS = setOf("XSCL", "ANAM", "BNAM", "XSOL", "CNAM", "INDX", "XCHG", "INTV")
        /** The records that are decoded: everything the editor shows or changes. */
        private val DECODED = setOf("TES3", "SAVE", "PLAY", "GLOB", "QUES", "LUAM", "NPC_")

        private fun readFully(r: org.alpha3.launcher.tools.openmw.ToolReader, offset: Long, dst: ByteArray, len: Int) {
            var done = 0
            while (done < len) {
                val n = r.read(offset + done, dst, done, len - done)
                if (n <= 0) throw ToolException("The save file ended early")
                done += n
            }
        }

        private fun decodeSubs(body: ByteArray): MutableList<SaveSub> {
            val out = ArrayList<SaveSub>()
            var p = 0
            while (p + 8 <= body.size) {
                val tag = body.decodeToString(p, p + 4)
                val n = i32(body, p + 4)
                if (n < 0 || p + 8 + n > body.size) throw ToolException("A damaged subrecord ($tag) in the save")
                out += SaveSub(tag, body.copyOfRange(p + 8, p + 8 + n))
                p += 8 + n
            }
            return out
        }

        private fun encodeSubs(subs: List<SaveSub>): ByteArray {
            var size = 0
            for (s in subs) size += 8 + s.data.size
            val out = ByteArray(size)
            var p = 0
            for (s in subs) {
                s.tag.encodeToByteArray().copyInto(out, p)
                i32Bytes(s.data.size).copyInto(out, p + 4)
                s.data.copyInto(out, p + 8)
                p += 8 + s.data.size
            }
            return out
        }

        /** Reads the record index of the save at [path], and decodes the records the editor works on. */
        fun open(fs: ToolFs, path: String): SaveGame {
            val size = fs.size(path)
            val r = fs.openRead(path)
            val records = ArrayList<SaveRecord>()
            try {
                val head = ByteArray(16)
                var at = 0L
                while (at + 16 <= size) {
                    readFully(r, at, head, 16)
                    val tag = head.decodeToString(0, 4)
                    val n = i32(head, 4)
                    if (n < 0 || at + 16 + n > size) throw ToolException("Not an OpenMW save, or a damaged one (record $tag)")
                    val rec = SaveRecord(tag, head.copyOfRange(8, 16), at, n)
                    if (tag in DECODED) {
                        val body = ByteArray(n)
                        readFully(r, at + 16, body, n)
                        rec.subs = decodeSubs(body)
                    }
                    records += rec
                    at += 16 + n
                }
            } finally {
                r.close()
            }
            if (records.firstOrNull()?.tag != "TES3" || records.none { it.tag == "SAVE" }) throw ToolException("Not an OpenMW saved game")
            // only the player's NPC record is needed of the NPCs
            for (rec in records) if (rec.tag == "NPC_" &&
                !(rec.subs?.firstOrNull { it.tag == "NAME" }?.let { refIdText(it.data) }.equals("player", true))) rec.subs = null
            return SaveGame(fs, path, records.toMutableList())
        }
    }
}
