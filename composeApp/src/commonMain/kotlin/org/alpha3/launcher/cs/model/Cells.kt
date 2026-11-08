package org.alpha3.launcher.cs.model

import org.alpha3.launcher.tools.openmw.Sub
import org.alpha3.launcher.tools.openmw.fail

/*
 * Cells and the objects placed in them (references).
 *
 * A CELL record is a header (name, grid, lighting, region...) followed by references, each starting with FRMR (its
 * number) and sometimes MVRF/CNDT before it (moved to another cell). References before NAM0 are persistent, those
 * after it temporary. FRMR's top byte says whose reference it is: 0 = the file's own, n = the file's n-th master.
 * A plugin's CELL holds only the header and the references it adds, changes or deletes; the game merges every
 * file's version of a cell by reference number, as [cellView] does here.
 */

/** A reference's identity: the content file that placed it (index into the document's files) and its number there. */
internal data class RefKey(val file: Int, val index: Int)

/** One reference as stored in one file: its subrecords, from FRMR (or MVRF) on. */
internal class RawRef(val subs: List<Sub>, val persistent: Boolean) {
    val frmr: Long get() = subs.firstOrNull { it.tag == "FRMR" }?.data?.let { le32(it) } ?: 0L
    val deleted: Boolean get() = subs.any { it.tag == "DELE" }
}

internal class CellParts(val header: List<Sub>, val refs: List<RawRef>)

private fun le32(b: ByteArray, o: Int = 0): Long =
    (b[o].toLong() and 0xff) or ((b[o + 1].toLong() and 0xff) shl 8) or ((b[o + 2].toLong() and 0xff) shl 16) or ((b[o + 3].toLong() and 0xff) shl 24)

private fun u32Bytes(v: Long) = ByteArray(4) { (v ushr (8 * it)).toByte() }

private val REF_START = setOf("FRMR", "MVRF")

/** A cell's header and references, in file order. NAM0 marks where temporary references start. */
internal fun splitCell(subs: List<Sub>): CellParts {
    val header = ArrayList<Sub>()
    val refs = ArrayList<RawRef>()
    var cur: ArrayList<Sub>? = null
    var temporary = false
    var curTemporary = false
    fun flush() { cur?.let { if (it.isNotEmpty()) refs += RawRef(it, !curTemporary) }; cur = null }
    for (s in subs) {
        when {
            s.tag == "NAM0" -> { flush(); temporary = true }
            s.tag in REF_START -> {
                val c = cur
                // MVRF/CNDT come before the FRMR of the same reference
                if (c == null || c.any { it.tag == "FRMR" } || s.tag == "MVRF") { flush(); cur = arrayListOf(s); curTemporary = temporary }
                else c += s
            }
            cur != null -> cur!! += s
            else -> header += s
        }
    }
    flush()
    return CellParts(header, refs)
}

/** A cell record's subrecords: header, persistent references, NAM0 with the count of temporary ones, temporary ones. */
internal fun joinCell(header: List<Sub>, refs: List<RawRef>): List<Sub> {
    val out = ArrayList<Sub>(header.filter { it.tag != "NAM0" })
    refs.filter { it.persistent }.forEach { out += it.subs }
    val temporary = refs.filter { !it.persistent }
    if (temporary.isNotEmpty()) {
        out += Sub("NAM0", u32Bytes(temporary.size.toLong()))
        temporary.forEach { out += it.subs }
    }
    return out
}

/** One reference of the merged cell. [state] is relative to the plugin being edited. */
internal class CellRef(val key: RefKey, val subs: List<Sub>, val persistent: Boolean, val state: RecordState, val source: Int) {
    val objectId: String get() = zstringOf(subs.firstOrNull { it.tag == "NAME" }?.data)
    /** x, y, z, rotation x, y, z (radians); zero when the reference has no position. */
    val position: FloatArray
        get() {
            val d = subs.firstOrNull { it.tag == "DATA" && it.data.size == 24 }?.data ?: return FloatArray(6)
            return FloatArray(6) { Float.fromBits(le32(d, it * 4).toInt()) }
        }
    val scale: Float get() = subs.firstOrNull { it.tag == "XSCL" && it.data.size == 4 }?.data?.let { Float.fromBits(le32(it).toInt()) } ?: 1f
}

internal class CellView(val row: RecordRow, val header: List<Sub>, val refs: List<CellRef>) {
    val headerData: ByteArray? get() = header.firstOrNull { it.tag == "DATA" && it.data.size == 12 }?.data
    val interior: Boolean get() = headerData?.let { le32(it) and 1L != 0L } ?: true
    val grid: Pair<Int, Int> get() = headerData?.let { le32(it, 4).toInt() to le32(it, 8).toInt() } ?: (0 to 0)
}

/** Subrecords of a reference with its position (and scale, 1 = none) replaced. */
internal fun withPlacement(subs: List<Sub>, pos: FloatArray, scale: Float): List<Sub> {
    val data = Sub("DATA", ByteArray(24).also { b -> for (i in 0 until 6) u32Bytes(pos[i].toBits().toLong()).copyInto(b, i * 4) })
    val out = ArrayList<Sub>()
    var placed = false
    for (s in subs) {
        when {
            s.tag == "XSCL" -> {}
            s.tag == "DATA" && s.data.size == 24 -> { out += data; placed = true }
            else -> out += s
        }
    }
    if (!placed) out += data
    // XSCL goes right after NAME (and UNAM), as the game writes it
    if (scale != 1f) {
        val at = out.indexOfFirst { it.tag == "NAME" }.let { n -> if (n >= 0 && out.getOrNull(n + 1)?.tag == "UNAM") n + 2 else n + 1 }
        out.add(at.coerceIn(0, out.size), Sub("XSCL", u32Bytes(scale.coerceIn(0.5f, 2f).toBits().toLong())))
    }
    return out
}

// ---- the merged view and edits, over the document's files ------------------------------------------------------------

private val gridSuffix = Regex("""\((-?\d+), (-?\d+)\)$""")

/** Whether two cell ids are the same cell: exteriors by grid (their ids carry the region's name too), interiors by name. */
private fun sameCell(a: String, b: String): Boolean {
    val ga = gridSuffix.find(a)?.value
    val gb = gridSuffix.find(b)?.value
    return if (ga != null && gb != null) ga == gb else a == b
}

/** The index of this document's plugin among its files (masters are 0 until it). */
internal val CsDocument.pluginFile: Int get() = masters.size

private fun CsDocument.fileOfMaster(name: String): Int = masters.indexOfFirst { it.name.equals(name, ignoreCase = true) }

/** Whose reference [frmr] is, read in a file with [fileMasters] whose own index is [file]. */
private fun CsDocument.keyOf(frmr: Long, file: Int, fileMasters: List<String>): RefKey {
    val local = (frmr ushr 24).toInt()
    val index = (frmr and 0xffffff).toInt()
    if (local == 0 || local > fileMasters.size) return RefKey(file, index)
    val owner = fileOfMaster(fileMasters[local - 1])
    return RefKey(if (owner >= 0) owner else file, index)
}

/** FRMR as the plugin writes it for [key]. */
private fun CsDocument.frmrFor(key: RefKey): Long =
    if (key.file == pluginFile) key.index.toLong() else ((key.file + 1).toLong() shl 24) or key.index.toLong()

private fun CsDocument.pluginCellIndex(row: RecordRow): Int =
    plugin.records.indexOfLast { it.tag == "CELL" && sameCell(it.id, row.id) }

/** Every file's version of the cell, in load order: (file, record, that file's masters). */
private fun CsDocument.cellVersions(row: RecordRow): List<Triple<Int, CsRecord, List<String>>> {
    val out = ArrayList<Triple<Int, CsRecord, List<String>>>()
    for ((k, m) in masters.withIndex()) {
        for (e in m.entries) if (e.tag == "CELL" && sameCell(e.id, row.id)) out += Triple(k, m.load(e), m.masterNames)
    }
    val pi = pluginCellIndex(row)
    if (pi >= 0) out += Triple(pluginFile, plugin.records[pi], plugin.masters.map { it.first })
    return out
}

/** The cell as the game would load it with the plugin: every file's references merged by number. */
internal fun CsDocument.cellView(row: RecordRow): CellView {
    val versions = cellVersions(row)
    if (versions.isEmpty()) fail("cell ${row.displayId} is in no file")
    val refs = LinkedHashMap<RefKey, CellRef>()
    var header: List<Sub> = emptyList()
    for ((file, rec, fileMasters) in versions) {
        val parts = splitCell(rec.subs)
        header = parts.header
        for (raw in parts.refs) {
            val key = keyOf(raw.frmr, file, fileMasters)
            if (file == pluginFile) {
                val state = when {
                    raw.deleted -> RecordState.Deleted
                    key.file == pluginFile -> RecordState.New
                    else -> RecordState.Modified
                }
                refs[key] = CellRef(key, raw.subs, raw.persistent, state, file)
            } else if (raw.deleted) {
                refs.remove(key)
            } else {
                refs[key] = CellRef(key, raw.subs, raw.persistent, RecordState.Base, file)
            }
        }
    }
    return CellView(row, header, refs.values.toList())
}

/**
 * Rewrites the plugin's version of the cell: [header] (null: keep it) and its own references changed by [change].
 * A cell the plugin did not touch yet starts from the last master's header and no references.
 */
private fun CsDocument.editCell(row: RecordRow, header: List<Sub>? = null, mergeKey: String? = null, change: (MutableList<RawRef>, List<String>) -> Unit) {
    val pi = pluginCellIndex(row)
    val parts = if (pi >= 0) splitCell(plugin.records[pi].subs) else CellParts(cellVersions(row).last().second.subs.let { splitCell(it).header }, emptyList())
    val refs = parts.refs.toMutableList()
    change(refs, plugin.masters.map { it.first })
    val subs = joinCell(header ?: parts.header, refs)
    // the row must point at the plugin's record when there is one, or edit() copies the master's whole cell first
    val target = if (pi >= 0) RecordRow(row.tag, row.id, row.displayId, RecordState.Modified, row.master, plugin.records[pi]) else row
    edit(target, subs, mergeKey = mergeKey)
}

private fun CsDocument.indexIn(refs: List<RawRef>, key: RefKey, fileMasters: List<String>) =
    refs.indexOfFirst { keyOf(it.frmr, pluginFile, fileMasters) == key }

/** Sets [ref]'s subrecords in the plugin (FRMR is written for the plugin). */
internal fun CsDocument.setRef(row: RecordRow, ref: CellRef, subs: List<Sub>, mergeKey: String? = null) {
    val frmr = Sub("FRMR", u32Bytes(frmrFor(ref.key)))
    val body = subs.filter { it.tag != "DELE" }.map { if (it.tag == "FRMR") frmr else it }
    editCell(row, mergeKey = mergeKey?.let { "ref:${ref.key}:$it" }) { refs, fm ->
        val raw = RawRef(body, ref.persistent)
        val i = indexIn(refs, ref.key, fm)
        if (i >= 0) refs[i] = raw else refs += raw
    }
}

/** Deletes [ref]: a reference the plugin added is removed; a master's is marked deleted. */
internal fun CsDocument.deleteRef(row: RecordRow, ref: CellRef) {
    editCell(row) { refs, fm ->
        val i = indexIn(refs, ref.key, fm)
        if (ref.key.file == pluginFile) { if (i >= 0) refs.removeAt(i); return@editCell }
        val frmr = Sub("FRMR", u32Bytes(frmrFor(ref.key)))
        val body = ref.subs.filter { it.tag != "DELE" }.map { if (it.tag == "FRMR") frmr else it } + Sub("DELE", ByteArray(4))
        val raw = RawRef(body, ref.persistent)
        if (i >= 0) refs[i] = raw else refs += raw
    }
}

/** Takes the plugin's change to a master's [ref] back out. */
internal fun CsDocument.revertRef(row: RecordRow, ref: CellRef) {
    editCell(row) { refs, fm -> indexIn(refs, ref.key, fm).takeIf { it >= 0 }?.let { refs.removeAt(it) } }
}

/** The next free reference number of the plugin's own (unique across all its cells). */
private fun CsDocument.nextRefIndex(): Int {
    var max = 0
    for (r in plugin.records) {
        if (r.tag != "CELL") continue
        for (raw in splitCell(r.subs).refs) if (raw.frmr ushr 24 == 0L) max = maxOf(max, (raw.frmr and 0xffffff).toInt())
    }
    return max + 1
}

/** Places a new reference to [objectId] at [pos] (x, y, z, rotations). Returns its key. */
internal fun CsDocument.addRef(row: RecordRow, objectId: String, pos: FloatArray, persistent: Boolean): RefKey {
    val key = RefKey(pluginFile, nextRefIndex())
    val subs = withPlacement(listOf(Sub("FRMR", u32Bytes(key.index.toLong())), Sub("NAME", codec.encode(objectId) + byteArrayOf(0))), pos, 1f)
    editCell(row) { refs, _ -> refs += RawRef(subs, persistent) }
    return key
}

/** Replaces the cell's header (name, grid, lighting, region...) in the plugin. */
internal fun CsDocument.setCellHeader(row: RecordRow, header: List<Sub>, mergeKey: String? = null) {
    editCell(row, header = header, mergeKey = mergeKey?.let { "header:$it" }) { _, _ -> }
}

/** The record [id] of any type the game can place (for its model and whether it persists). */
internal fun CsDocument.placeable(id: String): CsRecord? {
    for (tag in PLACEABLE) find(tag, id)?.let { return it }
    return null
}

internal val PLACEABLE = listOf(
    "STAT", "ACTI", "DOOR", "CONT", "LIGH", "MISC", "WEAP", "ARMO", "CLOT", "BOOK", "ALCH", "INGR", "APPA", "LOCK",
    "PROB", "REPA", "NPC_", "CREA", "LEVC", "LEVI", "BODY",
)
