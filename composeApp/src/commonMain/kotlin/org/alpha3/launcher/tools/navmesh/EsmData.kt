// SPDX-License-Identifier: GPL-3.0-or-later
// The part of OpenMW's esmloader and esm3 readers that the navmesh tool uses: activators, containers, doors,
// statics, game settings, cells with their references, and land heights, merged across the load order the same way.
package org.alpha3.launcher.tools.navmesh

import org.alpha3.launcher.tools.openmw.TextCodec
import org.alpha3.launcher.tools.merge.parallelMap
import org.alpha3.launcher.tools.openmw.ToolFs
import org.alpha3.launcher.utils.availableCpuCores
import org.alpha3.launcher.tools.openmw.ToolReader
import org.alpha3.launcher.tools.openmw.ToolException
import org.alpha3.launcher.tools.openmw.Vfs

internal const val REC_ACTI = 1
internal const val REC_CONT = 2
internal const val REC_DOOR = 3
internal const val REC_STAT = 4

internal const val LAND_SIZE = 65
internal const val LAND_REAL_SIZE = 8192
internal const val LAND_DEFAULT_HEIGHT = -2048
private const val HEIGHT_SCALE = 8

private const val CELL_INTERIOR = 0x01
internal const val CELL_HAS_WATER = 0x02

/** Where a cell's references are: a record body of a plugin, from [refsStart] on. */
internal class CellContext(val plugin: Int, val offset: Long, val size: Int, val refsStart: Int)

internal class EsmCell(var name: String, var flags: Int, var x: Int, var y: Int, var water: Float, val deleted: Boolean) {
    val contexts = ArrayList<CellContext>()
    val exterior get() = flags and CELL_INTERIOR == 0

    /** Cell::getDescription */
    fun description(region: String? = null): String =
        if (!exterior) name else "$name ($x, $y)".let { if (name.isNotEmpty()) it else "${region ?: "Wilderness"} ($x, $y)" }
}

internal class LandRecord(val x: Int, val y: Int, val flags: Int, val hasHeights: Boolean, val plugin: Int, val offset: Long, val size: Int, val deleted: Boolean)

/** A cell's height map, kept as the file stores it (65x65 signed byte steps, 4 KB) and expanded to floats when a tile needs it. */
internal class LandHeights(private val start: Float, private val steps: ByteArray, val minHeight: Float, val maxHeight: Float) {
    val heights: FloatArray get() {
        val out = FloatArray(LAND_SIZE * LAND_SIZE)
        var rowOffset = start
        for (y in 0 until LAND_SIZE) {
            rowOffset += steps[y * LAND_SIZE]
            out[y * LAND_SIZE] = rowOffset * HEIGHT_SCALE
            var col = rowOffset
            for (x in 1 until LAND_SIZE) {
                col += steps[y * LAND_SIZE + x]
                out[y * LAND_SIZE + x] = col * HEIGHT_SCALE
            }
        }
        return out
    }
}

/** One placed reference: [pos] holds position then rotation (radians), as in the DATA subrecord. */
internal class CellRefData(val id: String, val scale: Float, val pos: FloatArray)

internal class ModelRecord(val type: Int, val model: String)

internal class EsmData(
    val cells: List<EsmCell>,
    val lands: List<LandRecord>,
    val models: Map<String, ModelRecord>,
    val gameSettings: Map<String, Float>,
    val plugins: List<EsmPlugin>,
)

internal class EsmPlugin(val index: Int, val name: String, val path: String, val parents: IntArray)

/** Normalises a resource path the way VFS::Path::Normalized does: lower case, '/' separators, no duplicate or leading ones. */
internal fun normalizeResourcePath(name: String): String {
    val sb = StringBuilder(name.length)
    for (ch in name) {
        val c = if (ch == '\\') '/' else if (ch in 'A'..'Z') ch + 32 else ch
        if (c == '/' && (sb.isEmpty() || sb.last() == '/')) continue
        sb.append(c)
    }
    return sb.toString()
}

private fun lowerAscii(s: String): String {
    var changed = false
    for (c in s) if (c in 'A'..'Z') { changed = true; break }
    if (!changed) return s
    return CharArray(s.length) { val c = s[it]; if (c in 'A'..'Z') c + 32 else c }.concatToString()
}

private fun le32(b: ByteArray, o: Int) =
    (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)

private fun leFloat(b: ByteArray, o: Int) = Float.fromBits(le32(b, o))

private fun tagAt(b: ByteArray, o: Int): String = CharArray(4) { (b[o + it].toInt() and 0xff).toChar() }.concatToString()

/** Calls [f] with each subrecord's tag, data offset and size, from [start]; stops when [f] returns false. Returns the offset it stopped at. */
private inline fun walkSubs(b: ByteArray, start: Int, f: (String, Int, Int) -> Boolean): Int {
    var p = start
    while (p < b.size) {
        if (b.size - p < 8) throw ToolException("truncated subrecord header")
        val n = le32(b, p + 4)
        if (n < 0 || n > b.size - p - 8) throw ToolException("subrecord ${tagAt(b, p)} size $n exceeds the record")
        if (!f(tagAt(b, p), p + 8, n)) return p
        p += 8 + n
    }
    return p
}

private class BufferedFile(val reader: ToolReader, val size: Long) {
    private val buf = ByteArray(1 shl 16)
    private var start = 0L
    private var len = 0

    fun read(offset: Long, count: Int): ByteArray {
        if (offset < 0 || offset + count > size) throw ToolException("unexpected end of file")
        val out = ByteArray(count)
        if (count > buf.size) { readDirect(offset, out); return out }
        if (offset < start || offset + count > start + len) {
            start = offset
            len = minOf(buf.size.toLong(), size - offset).toInt()
            val tmp = ByteArray(len)
            readDirect(offset, tmp)
            tmp.copyInto(buf)
        }
        buf.copyInto(out, 0, (offset - start).toInt(), (offset - start).toInt() + count)
        return out
    }

    private fun readDirect(offset: Long, dst: ByteArray) {
        var done = 0
        while (done < dst.size) {
            val n = reader.read(offset + done, dst, done, dst.size - done)
            if (n <= 0) throw ToolException("unexpected end of file")
            done += n
        }
    }
}

private class Keyed<T>(val value: T, val deleted: Boolean)

/** What one plugin says about the records the tool cares about, in file order. */
private sealed class EsmOp {
    class Model(val kind: Int, val key: String, val model: String, val deleted: Boolean) : EsmOp()
    class Gmst(val id: String, val value: Float) : EsmOp()
    /** [water] holds the INTV (false) and WHGT (true) values of the cell in order. */
    class CellOp(
        val name: String, val flags: Int, val x: Int, val y: Int, val deleted: Boolean, val water: List<Pair<Boolean, Float>>,
        val bodyPos: Long, val bodySize: Int, val refsStart: Int,
    ) : EsmOp()
    class LandOp(val x: Int, val y: Int, val flags: Int, val heights: Boolean, val bodyPos: Long, val bodySize: Int, val deleted: Boolean) : EsmOp()
}

private class ParsedEsm(val name: String, val path: String, val masters: List<String>, val ops: List<EsmOp>)

/** Reads one plugin without touching anything shared, so many can be read at once. */
private fun parseEsmFile(name: String, path: String, size: Long, fs: ToolFs, codec: TextCodec, hidden: Set<String>): ParsedEsm {
    val reader = fs.openRead(path)
    try {
        val file = BufferedFile(reader, size)
        var pos = 0L
        fun header(): Triple<String, Int, Int> {
            val h = file.read(pos, 16)
            return Triple(tagAt(h, 0), le32(h, 4), le32(h, 12))
        }
        val (first, firstSize, _) = header()
        if (first != "TES3") throw ToolException("$name: Not a valid Morrowind file")
        val masters = ArrayList<String>()
        walkSubs(file.read(pos + 16, firstSize), 0) { tag, off, n ->
            if (tag == "MAST") masters.add(codec.decode(file.read(pos + 16 + off, n)))
            true
        }
        pos += 16L + firstSize
        val ops = ArrayList<EsmOp>()
        while (pos < size) {
            val (tag, bodySize, flags) = header()
            val bodyPos = pos + 16
            if (bodySize < 0 || bodyPos + bodySize > size) throw ToolException("$name: record $tag exceeds the file")
            pos = bodyPos + bodySize
            if (flags and 0x1000 != 0) continue
            when (tag) {
                "ACTI", "CONT", "DOOR", "STAT" -> {
                    val body = file.read(bodyPos, bodySize)
                    var id: String? = null
                    var model = ""
                    var deleted = false
                    walkSubs(body, 0) { t, off, n ->
                        when (t) {
                            "NAME" -> id = codec.decode(body.copyOfRange(off, off + n))
                            "MODL" -> model = codec.decode(body.copyOfRange(off, off + n))
                            "DELE" -> deleted = true
                        }
                        true
                    }
                    val key = lowerAscii(id ?: throw ToolException("$name: Missing NAME subrecord in $tag"))
                    if (key in hidden) continue
                    val kind = when (tag) { "ACTI" -> REC_ACTI; "CONT" -> REC_CONT; "DOOR" -> REC_DOOR; else -> REC_STAT }
                    ops.add(EsmOp.Model(kind, key, normalizeResourcePath(model), deleted))
                }
                "GMST" -> {
                    val body = file.read(bodyPos, bodySize)
                    var id = ""
                    var value: Float? = null
                    walkSubs(body, 0) { t, off, n ->
                        when (t) {
                            "NAME" -> id = codec.decode(body.copyOfRange(off, off + n))
                            "FLTV" -> if (n >= 4) value = leFloat(body, off)
                            "INTV" -> if (n >= 4) value = le32(body, off).toFloat()
                        }
                        true
                    }
                    value?.let { ops.add(EsmOp.Gmst(lowerAscii(id), it)) }
                }
                "CELL" -> {
                    val body = file.read(bodyPos, bodySize)
                    var name2 = ""
                    var flags2 = 0; var x = 0; var y = 0
                    var deleted = false
                    var hasData = false
                    var p = walkSubs(body, 0) { t, off, n ->
                        when (t) {
                            "NAME" -> name2 = codec.decode(body.copyOfRange(off, off + n))
                            "DATA" -> {
                                if (n < 12) throw ToolException("$name: short CELL DATA")
                                flags2 = le32(body, off); x = le32(body, off + 4); y = le32(body, off + 8); hasData = true
                            }
                            "DELE" -> deleted = true
                            else -> return@walkSubs false
                        }
                        true
                    }
                    if (!hasData) throw ToolException("$name: Missing DATA subrecord")
                    val water = ArrayList<Pair<Boolean, Float>>()
                    p = walkSubs(body, p) { t, off, n ->
                        when (t) {
                            "INTV" -> water.add(false to le32(body, off).toFloat())
                            "WHGT" -> water.add(true to leFloat(body, off))
                            "AMBI", "RGNN", "NAM5", "NAM0" -> {}
                            else -> return@walkSubs false
                        }
                        true
                    }
                    ops.add(EsmOp.CellOp(name2, flags2, x, y, deleted, water, bodyPos, bodySize, p))
                }
                "LAND" -> {
                    val body = file.read(bodyPos, bodySize)
                    var x = 0; var y = 0; var lflags = 0; var hasLoc = false; var deleted = false; var heights = false
                    walkSubs(body, 0) { t, off, n ->
                        when (t) {
                            "INTV" -> { x = le32(body, off); y = le32(body, off + 4); hasLoc = true }
                            "DATA" -> lflags = le32(body, off)
                            "DELE" -> deleted = true
                            "VHGT" -> if (lflags and 1 != 0) heights = true
                        }
                        true
                    }
                    if (!hasLoc) throw ToolException("$name: Missing INTV subrecord")
                    ops.add(EsmOp.LandOp(x, y, lflags, heights, bodyPos, bodySize, deleted))
                }
            }
        }
        return ParsedEsm(name, path, masters, ops)
    } finally {
        reader.close()
    }
}

/** Loads the [content] plugins in order, as EsmLoader::loadEsmData does for activators, cells, containers, doors, game settings, lands and statics. */
internal fun loadEsmData(content: List<String>, vfs: Vfs, fs: ToolFs, codec: TextCodec, log: (String) -> Unit = {}): EsmData {
    val supported = setOf("esm", "esp", "omwgame", "omwaddon", "project")
    val plugins = ArrayList<EsmPlugin>()
    val acti = HashMap<String, Keyed<ModelRecord>>()
    val cont = HashMap<String, Keyed<ModelRecord>>()
    val door = HashMap<String, Keyed<ModelRecord>>()
    val stat = HashMap<String, Keyed<ModelRecord>>()
    val gmst = HashMap<String, Float>()
    val cellList = ArrayList<EsmCell>()
    val interior = HashMap<String, EsmCell>()
    val exterior = HashMap<Long, EsmCell>()
    val lands = HashMap<Long, LandRecord>()
    val hidden = setOf("prisonmarker", "divinemarker", "templemarker", "northmarker")

    // The files are read on every core and what each one changes is applied in load order, so the last plugin still wins
    class ToLoad(val name: String, val path: String, val size: Long)
    val files = ArrayList<ToLoad>()
    for (name in content) {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in supported) { log("Skipping unsupported content file: $name"); continue }
        val path = vfs.loose(name).path
        files.add(ToLoad(name, path, fs.size(path)))
    }
    val parsed = parallelMap(files, availableCpuCores(), { n -> if (n % 50 == 0) log("  read $n of ${files.size} plugins") }) { parseEsmFile(it.name, it.path, it.size, fs, codec, hidden) }
    for (p in parsed) {
        log("Loading ESM file ${p.name}")
        val i = plugins.size // plugins are indexed among the supported files only; contexts look them up by this
        val parents = IntArray(p.masters.size) { m ->
            var idx = i
            for (q in plugins) if (q.name.equals(p.masters[m], ignoreCase = true)) { idx = q.index; break }
            idx
        }
        plugins.add(EsmPlugin(i, baseName(p.path), p.path, parents))
        for (op in p.ops) when (op) {
            is EsmOp.Model -> {
                val map = when (op.kind) { REC_ACTI -> acti; REC_CONT -> cont; REC_DOOR -> door; else -> stat }
                map[op.key] = Keyed(ModelRecord(op.kind, op.model), op.deleted)
            }
            is EsmOp.Gmst -> gmst[op.id] = op.value
            is EsmOp.CellOp -> {
                val isInterior = op.flags and CELL_INTERIOR != 0
                val ck = (op.x.toLong() shl 32) or (op.y.toLong() and 0xffffffffL)
                val known = if (isInterior) interior[op.name] else exterior[ck]
                val target: EsmCell
                if (known == null) {
                    target = EsmCell(op.name, op.flags, op.x, op.y, 0f, op.deleted)
                    cellList.add(target)
                    if (isInterior) interior[op.name] = target else exterior[ck] = target
                } else {
                    target = known
                    target.flags = op.flags; target.x = op.x; target.y = op.y
                }
                val freshName = known == null
                for ((isHeight, v) in op.water) {
                    if (!isHeight) target.water = v
                    else if (v.isNaN() || v.isInfinite()) { if (freshName) target.water = Float.MAX_VALUE }
                    else target.water = v
                }
                target.contexts.add(CellContext(i, op.bodyPos, op.bodySize, op.refsStart))
            }
            is EsmOp.LandOp -> lands[(op.x.toLong() shl 32) or (op.y.toLong() and 0xffffffffL)] =
                LandRecord(op.x, op.y, op.flags, op.heights, i, op.bodyPos, op.bodySize, op.deleted)
        }
    }

    val models = HashMap<String, ModelRecord>()
    for (m in listOf(acti, cont, door, stat)) for ((k, v) in m) if (!v.deleted) models.getOrPut(k) { v.value }
    val landList = lands.values.filter { !it.deleted }.sortedWith(compareBy({ it.x }, { it.y }))
    return EsmData(cellList.filter { !it.deleted }, landList, models, gmst, plugins)
}

private fun baseName(p: String): String = p.substring(maxOf(p.lastIndexOf('/'), p.lastIndexOf('\\')) + 1)

/** Land::loadData for the height map: VHGT is a float offset, then 65x65 signed byte deltas. */
internal fun loadLandHeights(land: LandRecord, data: EsmData, fs: ToolFs): LandHeights? {
    if (!land.hasHeights) return null
    val reader = fs.openRead(data.plugins[land.plugin].path)
    val body = ByteArray(land.size)
    try {
        var done = 0
        while (done < body.size) {
            val n = reader.read(land.offset + done, body, done, body.size - done)
            if (n <= 0) throw ToolException("unexpected end of file: land record of ${data.plugins[land.plugin].path} at ${land.offset}, ${land.size} bytes, ${done} read")
            done += n
        }
    } finally {
        reader.close()
    }
    var off = -1
    walkSubs(body, 0) { t, o, n -> if (t == "VHGT" && n >= 4 + LAND_SIZE * LAND_SIZE) off = o; off < 0 }
    if (off < 0) return null
    val heights = FloatArray(LAND_SIZE * LAND_SIZE)
    var rowOffset = leFloat(body, off)
    var min = Float.MAX_VALUE
    var max = -Float.MAX_VALUE
    val d = off + 4
    for (y in 0 until LAND_SIZE) {
        rowOffset += body[d + y * LAND_SIZE]
        var v = rowOffset * HEIGHT_SCALE
        heights[y * LAND_SIZE] = v
        if (v < min) min = v
        if (v > max) max = v
        var col = rowOffset
        for (x in 1 until LAND_SIZE) {
            col += body[d + y * LAND_SIZE + x]
            v = col * HEIGHT_SCALE
            heights[y * LAND_SIZE + x] = v
            if (v < min) min = v
            if (v > max) max = v
        }
    }
    return LandHeights(leFloat(body, off), body.copyOfRange(d, d + LAND_SIZE * LAND_SIZE), min, max)
}

private class RawRef(val key: Long, val id: String, val scale: Float, val pos: FloatArray, val deleted: Boolean)

/** Reads the references of every context of [cell]; later plugins override earlier ones by reference number, deleted ones are dropped. */
internal fun loadCellRefs(cell: EsmCell, data: EsmData, fs: ToolFs, codec: TextCodec): List<CellRefData> {
    val byKey = HashMap<Long, RawRef>()
    for (ctx in cell.contexts) {
        val plugin = data.plugins[ctx.plugin]
        val body = ByteArray(ctx.size)
        val reader = fs.openRead(plugin.path)
        try {
            var done = 0
            while (done < body.size) {
                val n = reader.read(ctx.offset + done, body, done, body.size - done)
                if (n <= 0) throw ToolException("unexpected end of file: cell ${cell.name} of ${plugin.path} at ${ctx.offset}, ${ctx.size} bytes, ${done} read")
                done += n
            }
        } finally {
            reader.close()
        }
        var p = ctx.refsStart
        fun tag() = tagAt(body, p)
        fun size() = le32(body, p + 4)
        fun hasNext() = body.size - p >= 8
        fun readRef(): RawRef {
            val formId = le32(body, p + 8)
            p += 8 + size()
            var id = ""
            var scale = 1f
            val pos = FloatArray(6)
            var deleted = false
            while (hasNext()) {
                val t = tag(); val n = size()
                if (n < 0 || n > body.size - p - 8) throw ToolException("bad reference subrecord $t")
                val o = p + 8
                when (t) {
                    "NAME" -> id = codec.decode(body.copyOfRange(o, o + n))
                    "XSCL" -> scale = leFloat(body, o).coerceIn(0.5f, 2f)
                    "DATA" -> if (n >= 24) for (k in 0 until 6) pos[k] = leFloat(body, o + 4 * k)
                    "DELE" -> deleted = true
                    "UNAM", "ANAM", "BNAM", "XSOL", "CNAM", "INDX", "XCHG", "INTV", "NAM9", "DODT", "DNAM", "FLTV", "KNAM", "TNAM", "NAM0" -> {}
                    else -> break
                }
                p += 8 + n
            }
            val local = (formId and 0xff000000.toInt()) ushr 24
            val index: Int
            val file: Int
            if (local != 0 && local <= plugin.parents.size) { index = formId and 0xffffff; file = plugin.parents[local - 1] }
            else { index = formId; file = plugin.index }
            return RawRef((index.toLong() shl 32) or file.toLong(), lowerAscii(id), scale, pos, deleted)
        }
        while (hasNext()) {
            if (tag() == "MVRF") {
                p += 8 + size()
                if (hasNext() && tag() == "CNDT") p += 8 + size()
                if (hasNext() && tag() == "FRMR") { readRef(); continue }
                break
            }
            if (tag() != "FRMR") break
            val r = readRef()
            byKey[r.key] = r
        }
    }
    return byKey.values.sortedBy { it.key }.filter { !it.deleted }.map { CellRefData(it.id, it.scale, it.pos) }
}
