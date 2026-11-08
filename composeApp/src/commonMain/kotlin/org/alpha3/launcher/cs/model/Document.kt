package org.alpha3.launcher.cs.model

import org.alpha3.launcher.tools.openmw.BufferedInput
import org.alpha3.launcher.tools.openmw.Sub
import org.alpha3.launcher.tools.openmw.TextCodec
import org.alpha3.launcher.tools.openmw.ToolFs
import org.alpha3.launcher.tools.openmw.ToolReader
import org.alpha3.launcher.tools.openmw.baseName
import org.alpha3.launcher.tools.openmw.fail
import org.alpha3.launcher.tools.openmw.tagString
import org.alpha3.launcher.tools.openmw.u32
import org.alpha3.launcher.tools.tes3cmd.parseSubs
import org.alpha3.launcher.tools.tes3cmd.recordId

/** How a record in the merged view relates to the masters, as OpenCS colours them. */
internal enum class RecordState { Base, Modified, New, Deleted }

/** A master's record, found by an index made once; its bytes are read from the file when it is opened. */
internal class MasterEntry(val file: Int, val tag: String, val id: String, val displayId: String, val offset: Long, val size: Int, val flags: Long, val unknown: Long)

/**
 * A master file indexed without keeping its records in memory (Morrowind.esm alone is 80 MB): one pass reads each
 * record's id, and its body is read again when the editor opens it.
 */
internal class MasterIndex(val fs: ToolFs, val path: String, val fileIndex: Int) {
    val name = baseName(path)
    val entries = ArrayList<MasterEntry>()
    /** This file's own masters, in its header's order (its references name them by position). */
    val masterNames = ArrayList<String>()

    init {
        val size = fs.size(path)
        val reader = fs.openRead(path)
        try {
            val input = BufferedInput(reader, size)
            val head = ByteArray(16)
            var first = true
            while (input.pos < size) {
                val at = input.pos
                input.readFully(head)
                val tag = tagString(head, 0)
                val len = u32(head, 4).toInt()
                val body = ByteArray(len)
                input.readFully(body)
                if (first) {
                    first = false
                    if (tag != "TES3") fail("$path: not a Morrowind plugin")
                    for (sub in parseSubs(body, tag)) if (sub.tag == "MAST") masterNames += zstringOf(sub.data)
                    continue
                }
                if (tag !in RECORD_DEFS) continue
                val subs = parseSubs(body, tag)
                entries += MasterEntry(fileIndex, tag, recordId(tag, subs), displayId(tag, subs), at + 16, len, u32(head, 12), u32(head, 8))
            }
        } finally {
            reader.close()
        }
    }

    private var reader: ToolReader? = null

    fun load(e: MasterEntry): CsRecord {
        val r = reader ?: fs.openRead(path).also { reader = it }
        val body = ByteArray(e.size)
        var done = 0
        while (done < body.size) {
            val n = r.read(e.offset + done, body, done, body.size - done)
            if (n <= 0) fail("$path: short read")
            done += n
        }
        return CsRecord(e.tag, e.flags, e.unknown, body, null)
    }

    fun close() { reader?.close(); reader = null }
}

/** A row of the merged view: the record the game would use, where it comes from and its state. */
internal class RecordRow(val tag: String, val id: String, val displayId: String, val state: RecordState, val master: MasterEntry?, val active: CsRecord?)

/**
 * The plugin being edited over its masters, as the game would see them: the active file's records win over the
 * masters', the last master over earlier ones. Edits go only into the active file, with undo and redo.
 */
internal class CsDocument(val fs: ToolFs, val codec: TextCodec, val plugin: PluginFile, val masters: List<MasterIndex>) {
    /** Bumped on every change; the screens read it to know when to refresh. */
    var version = 0
        private set

    private class Step(val index: Int, val before: CsRecord?, val after: CsRecord?)
    private val undoStack = ArrayList<Step>()
    private val redoStack = ArrayList<Step>()

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()
    var dirty = false
        private set

    private val masterByKey: Map<String, MasterEntry> by lazy {
        val map = HashMap<String, MasterEntry>()
        for (m in masters) for (e in m.entries) map["${e.tag}:${e.id}"] = e
        map
    }

    /** Every record of [tag] in the merged view, sorted by id. */
    fun rows(tag: String): List<RecordRow> {
        val out = LinkedHashMap<String, RecordRow>()
        for (m in masters) for (e in m.entries) if (e.tag == tag) out[e.id] = RecordRow(tag, e.id, e.displayId, RecordState.Base, e, null)
        for (r in plugin.records) {
            if (r.tag != tag) continue
            val id = r.id
            val base = masterByKey["$tag:$id"]
            val state = when {
                r.deleted -> RecordState.Deleted
                base != null -> RecordState.Modified
                else -> RecordState.New
            }
            out[id] = RecordRow(tag, id, r.displayId, state, base, r)
        }
        return out.values.sortedBy { it.id }
    }

    /** How many records of each type there are, for the type list. */
    fun counts(): Map<String, Int> {
        val ids = HashMap<String, MutableSet<String>>()
        for (m in masters) for (e in m.entries) ids.getOrPut(e.tag) { HashSet() } += e.id
        for (r in plugin.records) ids.getOrPut(r.tag) { HashSet() } += r.id
        return ids.mapValues { it.value.size }
    }

    /** Every id of [tags] (for reference pickers). */
    fun ids(tags: Set<String>): List<String> {
        val out = HashSet<String>()
        for (m in masters) for (e in m.entries) if (e.tag in tags) out += e.displayId
        for (r in plugin.records) if (r.tag in tags && !r.deleted) out += r.displayId
        return out.sortedBy { it.lowercase() }
    }

    /** The record [tag] [id] as the game would see it (this plugin's, else the last master's), or null. */
    fun find(tag: String, id: String): CsRecord? {
        val key = id.lowercase()
        plugin.records.lastOrNull { it.tag == tag && it.id == key }?.let { return if (it.deleted) null else it }
        val e = masterByKey["$tag:$key"] ?: return null
        return masters[e.file].load(e)
    }

    /** The record [row] stands for (the active file's, or a master's read now). */
    fun recordOf(row: RecordRow): CsRecord = row.active ?: masters[row.master!!.file].load(row.master)

    private fun indexOf(tag: String, id: String) = plugin.records.indexOfFirst { it.tag == tag && it.id == id }

    private fun apply(step: Step, forward: Boolean) {
        val target = if (forward) step.after else step.before
        val other = if (forward) step.before else step.after
        if (target == null) {
            // the record leaves the active file
            val i = if (other != null) indexOf(other.tag, other.id) else -1
            if (i >= 0) plugin.records.removeAt(i)
        } else {
            val i = if (other != null) indexOf(other.tag, other.id) else -1
            if (i >= 0) plugin.records[i] = target else plugin.records.add(step.index.coerceIn(0, plugin.records.size), target)
        }
        dirty = true
        version++
    }

    private var lastMergeKey: String? = null

    /** One undo step; edits with the same [mergeKey] in a row (typing in one field) make a single step. */
    private fun record(before: CsRecord?, after: CsRecord?, mergeKey: String? = null) {
        val last = undoStack.lastOrNull()
        if (mergeKey != null && mergeKey == lastMergeKey && last != null && redoStack.isEmpty()) {
            apply(Step(last.index, before, after), true)
            undoStack[undoStack.lastIndex] = Step(last.index, last.before, after?.copy())
            return
        }
        lastMergeKey = mergeKey
        val index = before?.let { indexOf(it.tag, it.id) }?.takeIf { it >= 0 } ?: plugin.records.size
        val step = Step(index, before?.copy(), after?.copy())
        apply(Step(index, before, after), true)
        undoStack += step
        if (undoStack.size > 200) undoStack.removeAt(0)
        redoStack.clear()
    }

    fun undo() { lastMergeKey = null; val s = undoStack.removeLastOrNull() ?: return; apply(s, false); redoStack += s }
    fun redo() { lastMergeKey = null; val s = redoStack.removeLastOrNull() ?: return; apply(s, true); undoStack += s }

    /** Replaces [row]'s subrecords: a master record is copied into the active file first. */
    fun edit(row: RecordRow, subs: List<Sub>, flags: Long? = null, mergeKey: String? = null) {
        val current = row.active
        val next = (current ?: recordOf(row)).copy()
        next.subs = subs
        if (flags != null) next.flags = flags
        record(current, next, mergeKey?.let { "${row.tag}:${row.id}:$it" })
    }

    /** A new record of [tag] named [id], with the subrecords a new record of the type starts with. */
    fun create(tag: String, id: String): CsRecord {
        val def = RECORD_DEFS[tag] ?: fail("no definition for $tag")
        fun z(t: String, v: String) = Sub(t, codec.encode(v) + byteArrayOf(0))
        val subs = ArrayList<Sub>()
        for (s in def.subs) {
            // scale is left out (the game takes 1); a zero would make the object invisible
            if (s.tag == "DELE" || s.tag == "XSCL" || s.repeats || s.layouts.isEmpty()) continue
            val layout = s.layouts.first()
            val textOnly = layout.any { it.type == FieldType.ZString || it.type == FieldType.Text }
            when {
                s.tag == "NAME" -> subs += z("NAME", id)
                // data subrecords (numbers), the model, the name; references are added when the user sets them
                s.required || !textOnly || s.tag == "MODL" || s.tag == "FNAM" && tag != "BODY" ->
                    subs += Sub(s.tag, ByteArray(layout.sumOf { maxOf(it.type.size, 0) }).let { if (textOnly) byteArrayOf(0) else it })
            }
        }
        if (tag == "NPC_") {
            // a working NPC: a race, class, head and hair, stats worked out by the game from level 1
            val i = subs.indexOfFirst { it.tag == "FNAM" } + 1
            subs.addAll(i, listOf(z("RNAM", "Dark Elf"), z("CNAM", "Commoner"), z("BNAM", "b_n_dark elf_m_head_01"), z("KNAM", "b_n_dark elf_m_hair_01")))
            val npdt = subs.indexOfFirst { it.tag == "NPDT" }
            if (npdt >= 0) subs[npdt] = Sub("NPDT", ByteArray(12).also { it[0] = 1; it[2] = 50 })
            val flag = subs.indexOfFirst { it.tag == "FLAG" }
            if (flag >= 0) subs[flag] = Sub("FLAG", byteArrayOf(0x18, 0, 0, 0))
        }
        // a new cell is an interior: no map colour or reference count, which belong to exteriors and saved cells
        if (tag == "CELL") subs.removeAll { it.tag == "NAM5" || it.tag == "NAM0" }
        // usable starting values (a weapon of speed 0 cannot be swung)
        NEW_RECORD_VALUES[tag]?.let { values ->
            for (i in subs.indices) {
                val sl = slots(def.sub(subs[i].tag), subs[i]) ?: continue
                for (slot in sl) values[slot.def.name]?.let { subs[i] = writeField(subs[i], slot, it, codec) }
            }
        }
        val rec = CsRecord.create(tag, subs)
        record(null, rec)
        return rec
    }

    /** A copy of [row] under a new id. */
    fun cloneAs(row: RecordRow, id: String): CsRecord {
        val src = recordOf(row)
        val subs = src.subs.map { s -> if (s.tag == "NAME") Sub("NAME", codec.encode(id) + byteArrayOf(0)) else Sub(s.tag, s.data.copyOf()) }
        val rec = CsRecord.create(row.tag, subs, src.flags and FLAG_DELETED.inv())
        record(null, rec)
        return rec
    }

    /** New records leave the file; a master's record is marked deleted. */
    fun delete(row: RecordRow) {
        when (row.state) {
            RecordState.New -> record(row.active, null)
            RecordState.Deleted -> {}
            else -> {
                val base = (row.active ?: recordOf(row)).copy()
                // a cell keeps its name and grid (the game needs both to know which cell), and none of its references
                val keep = if (row.tag == "CELL") splitCell(base.subs).header.filter { it.tag == "NAME" || it.tag == "DATA" }
                else base.subs.filter { it.tag == "NAME" || it.tag == "INDX" || it.tag == "SCHD" }
                base.subs = keep + Sub("DELE", ByteArray(4))
                record(row.active, base)
            }
        }
    }

    /** Takes the active file's version of [row] out (a master record goes back to the master's). */
    fun revert(row: RecordRow) {
        if (row.active != null) record(row.active, null)
    }

    /** The header's author and description. */
    fun headerText(): Pair<String, String> {
        val d = plugin.header.subs.firstOrNull { it.tag == "HEDR" }?.data ?: return "" to ""
        fun s(o: Int, n: Int) = codec.decode(d.copyOfRange(o, minOf(o + n, d.size)).takeWhile { it != 0.toByte() }.toByteArray())
        return s(8, 32) to s(40, 256)
    }

    fun setHeaderText(author: String, description: String) {
        val subs = plugin.header.subs.map { s ->
            if (s.tag != "HEDR" || s.data.size < 296) s else {
                val d = s.data.copyOf()
                for (i in 8 until 296) d[i] = 0
                codec.encode(author).copyInto(d, 8, 0, minOf(author.length, 31))
                codec.encode(description).copyInto(d, 40, 0, minOf(description.length, 255))
                Sub("HEDR", d)
            }
        }
        plugin.header.subs = subs
        dirty = true
        version++
    }

    /** Saves the active file, the header's record count and master sizes brought up to date. */
    fun save(masterSize: (String) -> Long?) {
        refreshHeader(plugin, masterSize)
        savePlugin(fs, plugin)
        dirty = false
        version++
    }

    fun close() = masters.forEach { it.close() }
}

/** Field values a new record of a type starts with, by field name. */
private val NEW_RECORD_VALUES: Map<String, Map<String, Any>> = mapOf(
    "WEAP" to mapOf("Weight" to 10f, "Value" to 10L, "Health" to 400L, "Speed" to 1f, "Reach" to 1f, "Chop min" to 1L, "Chop max" to 8L, "Slash min" to 1L, "Slash max" to 8L, "Thrust min" to 1L, "Thrust max" to 8L),
    "ARMO" to mapOf("Weight" to 10f, "Value" to 10L, "Health" to 300L, "Armor rating" to 10L),
    "CLOT" to mapOf("Weight" to 1f, "Value" to 5L),
    "MISC" to mapOf("Weight" to 1f, "Value" to 1L),
    "BOOK" to mapOf("Weight" to 1f, "Value" to 5L),
    "ALCH" to mapOf("Weight" to 0.5f, "Value" to 10L),
    "INGR" to mapOf("Weight" to 0.1f, "Value" to 1L, *(1..4).flatMap { n -> listOf("Effect $n", "Effect $n skill", "Effect $n attribute").map { it to (-1L as Any) } }.toTypedArray()),
    "APPA" to mapOf("Weight" to 1f, "Value" to 10L, "Quality" to 1f),
    "LOCK" to mapOf("Weight" to 0.25f, "Value" to 10L, "Quality" to 1f, "Uses" to 25L),
    "PROB" to mapOf("Weight" to 0.25f, "Value" to 10L, "Quality" to 1f, "Uses" to 25L),
    "REPA" to mapOf("Weight" to 1f, "Value" to 10L, "Quality" to 1f, "Uses" to 10L),
    "LIGH" to mapOf("Weight" to 1f, "Time" to -1L, "Radius" to 256L, "Red" to 255L, "Green" to 200L, "Blue" to 150L, "Flags" to 0x3L),
    "CREA" to mapOf("Level" to 1L, "Health" to 50L, "Fatigue" to 100L, "Strength" to 50L, "Agility" to 50L, "Speed" to 50L, "Endurance" to 50L, "Attack 1 min" to 1L, "Attack 1 max" to 5L),
    "SOUN" to mapOf("Volume" to 255L, "Min range" to 0L, "Max range" to 255L),
    "CELL" to mapOf(
        "Flags" to 0x1L, "Ambient R" to 90L, "Ambient G" to 85L, "Ambient B" to 80L, "Ambient A" to 255L,
        "Sunlight R" to 160L, "Sunlight G" to 150L, "Sunlight B" to 130L, "Sunlight A" to 255L,
        "Fog R" to 40L, "Fog G" to 40L, "Fog B" to 45L, "Fog A" to 255L, "Fog density" to 0.5f,
    ),
    "GLOB" to mapOf("Type" to 's'.code.toLong()),
)
