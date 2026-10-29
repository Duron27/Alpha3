// SPDX-License-Identifier: GPL-3.0-or-later
package org.alpha3.launcher.tools.coverify

import org.alpha3.launcher.tools.openmw.*

internal class Plugin(val name: String, val path: String, val size: Long, val id: Int)
internal data class ObjectKey(val tag: String, val id: String)
internal class GameObject(val key: ObjectKey, val data: ByteArray, val flags: Long, val model: String)
internal data class CellKey(val x: Int, val y: Int)
internal data class RefKey(val plugin: Int, val number: Long)
internal class Reference(val id: String, val data: ByteArray)
internal class Cell(val key: CellKey) {
    val meta = ArrayList<Sub>()
    val refs = LinkedHashMap<RefKey, Reference>()
    val moved = HashMap<RefKey, ByteArray>()
}

/** A reference as one plugin writes it: [master] is an index into that plugin's masters, or -1 for the plugin itself. */
internal class LocalRef(val master: Int, val number: Long)

/** What one plugin changes, in file order; [World.apply] replays it onto the load order. */
internal class PluginChanges(val masters: List<String>) {
    internal val ops = ArrayList<Op>()
    internal sealed class Op {
        class SetObject(val key: ObjectKey, val obj: GameObject?) : Op()
        class RemoveCell(val key: CellKey) : Op()
        class CellMeta(val key: CellKey, val meta: List<Sub>) : Op()
        class SetRef(val cell: CellKey, val ref: LocalRef, val value: Reference?) : Op()
        class SetMoved(val cell: CellKey, val ref: LocalRef, val data: ByteArray) : Op()
    }
}

/** The merged load order: matching objects and exterior cell references, last plugin wins. */
internal class World(private val matcher: Matcher, private val codec: TextCodec, private val fs: ToolFs) {
    val objects = HashMap<ObjectKey, GameObject>()
    val cells = HashMap<CellKey, Cell>()
    val names = ArrayList<String>()
    private val nameIds = HashMap<String, Int>()

    fun pluginId(name: String): Int = nameIds.getOrPut(name.lowercase()) { names.add(name); names.size - 1 }

    fun load(p: Plugin) = apply(p, parse(p))

    /** Replays [changes] of [p]; plugins have to be applied in load order. */
    fun apply(p: Plugin, changes: PluginChanges) {
        val ids = changes.masters.map { pluginId(it) }
        fun key(r: LocalRef) = RefKey(if (r.master < 0) p.id else ids[r.master], r.number)
        for (op in changes.ops) when (op) {
            is PluginChanges.Op.SetObject -> if (op.obj == null) objects.remove(op.key) else objects[op.key] = op.obj
            is PluginChanges.Op.RemoveCell -> cells.remove(op.key)
            is PluginChanges.Op.CellMeta -> {
                val cell = cells.getOrPut(op.key) { Cell(op.key) }
                // NAME and DATA always override. Optional fields carry forward when absent.
                for (s in op.meta) {
                    val i = cell.meta.indexOfFirst { it.tag == s.tag }
                    if (i >= 0) cell.meta[i] = s else cell.meta.add(s)
                }
            }
            is PluginChanges.Op.SetRef -> {
                val cell = cells[op.cell] ?: continue
                if (op.value == null) cell.refs.remove(key(op.ref)) else cell.refs[key(op.ref)] = op.value
            }
            is PluginChanges.Op.SetMoved -> cells[op.cell]?.moved?.set(key(op.ref), op.data)
        }
    }

    /**
     * Reads one plugin without touching the world, so plugins can be read on several threads. Only CELLs and
     * configured object types are decoded; other records are skipped unread.
     */
    fun parse(p: Plugin): PluginChanges {
        val reader = fs.openRead(p.path)
        try {
            val input = BufferedInput(reader, p.size)
            val header = ByteArray(16)
            val masters = ArrayList<String>()
            val changes = PluginChanges(masters)
            val matchCache = HashMap<String, Boolean>()
            fun matches(id: String) = matchCache.getOrPut(id) { matcher.matches(id) }
            while (input.pos < p.size) {
                val start = input.pos
                try {
                    if (p.size - start < 16) fail("record header: unexpected end of file")
                    input.readFully(header)
                    val tag = tagString(header, 0)
                    val n = u32(header, 4)
                    val flags = u32(header, 12)
                    if (n > p.size - input.pos) fail("$tag size $n exceeds file")
                    if (start == 0L && tag != "TES3") fail("missing TES3 header")
                    if (tag != "TES3" && tag != "CELL" && tag !in matcher.types) {
                        input.skip(n)
                        continue
                    }
                    val body = ByteArray(n.toInt())
                    input.readFully(body)
                    when (tag) {
                        "TES3" -> {
                            if (start != 0L) fail("multiple TES3 headers")
                            var found = false
                            subs(body) { t, b ->
                                if (t == "HEDR") {
                                    if (b.size != 300) fail("invalid HEDR size ${b.size}")
                                    found = true
                                }
                                if (t == "MAST") masters.add(codec.decode(b))
                            }
                            if (!found) fail("missing HEDR")
                        }
                        "CELL" -> parseCell(body, masters.size, changes, ::matches)
                        else -> parseObject(tag, flags, body, changes, ::matches)
                    }
                } catch (e: ToolException) {
                    fail("${p.name} at 0x${start.toString(16)}: ${e.message}")
                }
            }
            if (input.pos == 0L) fail("${p.name}: empty plugin")
            return changes
        } finally {
            reader.close()
        }
    }

    private fun parseObject(tag: String, flags: Long, b: ByteArray, changes: PluginChanges, matches: (String) -> Boolean) {
        var name = ""
        var model = ""
        var deleted = false
        subs(b) { t, d ->
            when (t) {
                "NAME" -> name = codec.decode(d).lowercase()
                "MODL" -> model = codec.decode(d)
                "DELE" -> deleted = true
            }
        }
        if (name.isEmpty()) fail("missing object NAME")
        if (!matches(name)) return
        val key = ObjectKey(tag, name)
        changes.ops.add(PluginChanges.Op.SetObject(key, if (deleted) null else GameObject(key, b, flags, model)))
    }

    private fun decodeRef(b: ByteArray, masterCount: Int): LocalRef {
        if (b.size != 4) fail("reference number must be 4 bytes, got ${b.size}")
        val n = u32(b)
        val master = (n ushr 24).toInt()
        if (master > 0 && master <= masterCount) return LocalRef(master - 1, n and 0xffffff)
        // OpenMW treats an out-of-range master index as a local 32-bit ID.
        return LocalRef(-1, n)
    }

    private fun parseCell(b: ByteArray, masterCount: Int, changes: PluginChanges, matches: (String) -> Boolean) {
        val metadata = ArrayList<Sub>()
        var key = CellKey(0, 0)
        var c: CellKey? = null
        var haveData = false
        var interior = false
        var deleted = false
        var refsStarted = false
        var rk = LocalRef(-1, 0)
        val refData = ByteBuilder()
        var refId = ""
        var refDeleted = false
        var refActive = false
        var movedKey = LocalRef(-1, 0)
        var moveActive = false
        var moveReady = false

        fun initCell() {
            if (refsStarted) return
            refsStarted = true
            if (!haveData) fail("CELL missing DATA")
            if (interior) return
            if (deleted) { changes.ops.add(PluginChanges.Op.RemoveCell(key)); return }
            if (metadata.none { it.tag == "NAME" }) metadata.add(0, Sub("NAME", byteArrayOf(0)))
            c = key
            changes.ops.add(PluginChanges.Op.CellMeta(key, ArrayList(metadata)))
        }

        fun flush() {
            val cell = c
            if (!refActive || cell == null) return
            if (!refDeleted && refId.isEmpty()) fail("reference ${rk.number} missing NAME")
            changes.ops.add(PluginChanges.Op.SetRef(cell, rk, if (refDeleted || !matches(refId)) null else Reference(refId, refData.toByteArray())))
        }

        subs(b) { tag0, d0 ->
            var tag = tag0
            var d = d0
            if (tag == "FRMR" || tag == "MVRF") {
                initCell()
                flush()
                refActive = false
                val cell = c ?: return@subs
                val id = decodeRef(d, masterCount)
                if (moveActive) fail("MVRF missing CNDT")
                if (moveReady && (tag != "FRMR" || id.master != movedKey.master || id.number != movedKey.number)) fail("moved reference must be followed by its matching FRMR")
                moveReady = false
                if (tag == "MVRF") {
                    movedKey = id
                    moveActive = true
                    return@subs
                }
                rk = id
                refId = ""
                refDeleted = false
                refData.clear()
                refActive = true
                moveActive = false
                return@subs
            }
            if (refsStarted) {
                val cell = c ?: return@subs
                if (moveActive) {
                    if (tag != "CNDT" || d.size != 8) fail("MVRF must be followed by an 8-byte CNDT")
                    changes.ops.add(PluginChanges.Op.SetMoved(cell, movedKey, d))
                    moveActive = false
                    moveReady = true
                    return@subs
                }
                if (!refActive) fail("unexpected $tag after cell metadata")
                when (tag) {
                    "NAME" -> refId = codec.decode(d).lowercase()
                    "DELE" -> refDeleted = true
                    "DATA" -> if (d.size != 24) fail("reference DATA must be 24 bytes")
                    "XSCL" -> if (d.size != 4) fail("XSCL must be 4 bytes")
                }
                if (tag != "NAM0") refData.sub(tag, d)
                return@subs
            }
            when (tag) {
                "DATA" -> {
                    if (d.size != 12) fail("CELL DATA must be 12 bytes")
                    haveData = true
                    interior = u32(d) and 1L != 0L
                    key = CellKey(i32(d, 4), i32(d, 8))
                }
                "DELE" -> { deleted = true; return@subs }
                "NAM0" -> return@subs // Temporary-reference count is regenerated by omission.
                "INTV" -> {
                    if (d.size != 4) fail("CELL INTV must be 4 bytes")
                    tag = "WHGT"
                    d = floatBytes(i32(d).toFloat())
                }
            }
            metadata.add(Sub(tag, d))
        }
        initCell()
        if (moveActive || moveReady) fail("incomplete moved reference")
        flush()
    }
}
