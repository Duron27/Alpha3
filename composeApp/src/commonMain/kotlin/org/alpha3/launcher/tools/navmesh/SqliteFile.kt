// SPDX-License-Identifier: GPL-3.0-or-later
// A minimal SQLite 3 file reader and writer for navmesh.db: two tables with three indexes, written the way a
// freshly vacuumed database looks (4096 byte pages, UTF-8, rollback journal mode), so OpenMW can open the result.
package org.alpha3.launcher.tools.navmesh

import kotlin.jvm.JvmField

internal const val PAGE = 4096
internal const val MAX_LOCAL_TABLE = PAGE - 35
internal const val MAX_LOCAL_INDEX = ((PAGE - 12) * 64 / 255) - 23
private const val MIN_LOCAL = ((PAGE - 12) * 32 / 255) - 23

internal class TileRow(
    @JvmField var id: Long, @JvmField var revision: Long, @JvmField val worldspace: String,
    @JvmField val x: Int, @JvmField val y: Int, @JvmField var version: Int,
    @JvmField val input: ByteArray, @JvmField var data: ByteArray?,
)

internal class ShapeRow(@JvmField val id: Long, @JvmField val name: String, @JvmField val type: Int, @JvmField val hash: ByteArray)

internal val CREATE_TILES = """CREATE TABLE tiles (
                tile_id INTEGER PRIMARY KEY,
                revision INTEGER NOT NULL DEFAULT 1,
                worldspace TEXT NOT NULL,
                tile_position_x INTEGER NOT NULL,
                tile_position_y INTEGER NOT NULL,
                version INTEGER NOT NULL,
                input BLOB,
                data BLOB
            )"""
internal val CREATE_SHAPES = """CREATE TABLE shapes (
                shape_id INTEGER PRIMARY KEY,
                name TEXT NOT NULL,
                type INTEGER NOT NULL,
                hash BLOB NOT NULL
            )"""
internal val CREATE_TILES_UNIQUE = """CREATE UNIQUE INDEX index_unique_tiles_by_worldspace_and_tile_position_and_input
                ON tiles (worldspace, tile_position_x, tile_position_y, input)"""
internal val CREATE_TILES_INDEX = """CREATE INDEX index_tiles_by_worldspace_and_tile_position
                ON tiles (worldspace, tile_position_x, tile_position_y)"""
internal val CREATE_SHAPES_UNIQUE = """CREATE UNIQUE INDEX index_unique_shapes_by_name_and_type_and_hash
                ON shapes (name, type, hash)"""

// ---------------------------------------------------------------- records

internal class Out {
    var buf = ByteArray(256)
    var size = 0
    fun ensure(n: Int) { if (size + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + n)) }
    fun u8(v: Int) { ensure(1); buf[size++] = v.toByte() }
    fun be32(v: Int) { u8(v ushr 24); u8(v ushr 16); u8(v ushr 8); u8(v) }
    fun bytes(b: ByteArray, from: Int = 0, to: Int = b.size) { ensure(to - from); b.copyInto(buf, size, from, to); size += to - from }
    fun varint(v: Long) { bytes(varintBytes(v)) }
    fun toByteArray() = buf.copyOf(size)
}

internal fun varintBytes(v: Long): ByteArray {
    if (v < 0 || v.toULong() > 0x00ffffffffffffffUL) {
        val out = ByteArray(9)
        out[8] = v.toByte()
        var x = v ushr 8
        for (i in 7 downTo 0) { out[i] = ((x and 0x7f) or 0x80).toByte(); x = x ushr 7 }
        return out
    }
    val tmp = ByteArray(9)
    var n = 0
    var x = v
    do { tmp[n++] = (x and 0x7f).toByte(); x = x ushr 7 } while (x != 0L)
    return ByteArray(n) { i -> (tmp[n - 1 - i].toInt() or (if (i < n - 1) 0x80 else 0)).toByte() }
}

private fun intType(v: Long): Int = when {
    v == 0L -> 8
    v == 1L -> 9
    v >= -128 && v <= 127 -> 1
    v >= -32768 && v <= 32767 -> 2
    v >= -8388608 && v <= 8388607 -> 3
    v >= Int.MIN_VALUE && v <= Int.MAX_VALUE -> 4
    v >= -140737488355328L && v <= 140737488355327L -> 5
    else -> 6
}

private val intSizes = intArrayOf(0, 1, 2, 3, 4, 6, 8)

/** Encodes a row; values are null, Long, String or ByteArray. */
internal fun record(vararg values: Any?): ByteArray {
    val types = LongArray(values.size)
    val blobs = arrayOfNulls<ByteArray>(values.size)
    for ((i, v) in values.withIndex()) when (v) {
        null -> types[i] = 0
        is Long -> types[i] = intType(v).toLong()
        is Int -> types[i] = intType(v.toLong()).toLong()
        is String -> { val b = v.encodeToByteArray(); blobs[i] = b; types[i] = 13L + 2 * b.size }
        is ByteArray -> { blobs[i] = v; types[i] = 12L + 2 * v.size }
        else -> throw IllegalArgumentException("bad value")
    }
    val header = Out()
    for (t in types) header.varint(t)
    val out = Out()
    out.varint(header.size + 1L)
    out.bytes(header.buf, 0, header.size)
    for ((i, v) in values.withIndex()) {
        when (v) {
            is Long, is Int -> {
                val n = (v as Number).toLong()
                val t = types[i].toInt()
                if (t in 1..6) { val len = intSizes[t]; for (k in len - 1 downTo 0) out.u8((n shr (8 * k)).toInt()) }
            }
            is String, is ByteArray -> out.bytes(blobs[i]!!)
        }
    }
    return out.toByteArray()
}

internal fun compareBytes(a: ByteArray, b: ByteArray): Int {
    val n = minOf(a.size, b.size)
    for (i in 0 until n) {
        val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
        if (d != 0) return d
    }
    return a.size - b.size
}

// ---------------------------------------------------------------- b-tree writer

internal open class Pages {
    val list = ArrayList<ByteArray?>()
    open fun alloc(): Int { list.add(null); return list.size }
    open fun set(n: Int, b: ByteArray) { list[n - 1] = b }
}

private fun localSize(p: Int, maxLocal: Int): Int {
    if (p <= maxLocal) return p
    val k = MIN_LOCAL + ((p - MIN_LOCAL) % (PAGE - 4))
    return if (k <= maxLocal) k else MIN_LOCAL
}

/** The cell bytes after an optional left-child pointer: payload size, rowid (table leaves), local payload, overflow pointer. */
internal fun cellBody(pages: Pages, payload: ByteArray, rowid: Long?, maxLocal: Int): ByteArray {
    val local = localSize(payload.size, maxLocal)
    val out = Out()
    out.varint(payload.size.toLong())
    if (rowid != null) out.varint(rowid)
    out.bytes(payload, 0, local)
    if (local < payload.size) {
        var pos = local
        var prev: ByteArray? = null
        var first = 0
        while (pos < payload.size) {
            val n = pages.alloc()
            if (prev == null) first = n else { prev[0] = (n ushr 24).toByte(); prev[1] = (n ushr 16).toByte(); prev[2] = (n ushr 8).toByte(); prev[3] = n.toByte() }
            val page = ByteArray(PAGE)
            val len = minOf(PAGE - 4, payload.size - pos)
            payload.copyInto(page, 4, pos, pos + len)
            pages.set(n, page)
            prev = page
            pos += len
        }
        out.be32(first)
    }
    return out.toByteArray()
}

internal fun buildPage(flag: Int, cells: List<ByteArray>, rightmost: Int, headerOffset: Int = 0): ByteArray {
    val page = ByteArray(PAGE)
    val interior = flag == 0x02 || flag == 0x05
    val hdr = headerOffset
    page[hdr] = flag.toByte()
    page[hdr + 3] = (cells.size ushr 8).toByte(); page[hdr + 4] = cells.size.toByte()
    var end = PAGE
    val ptrBase = hdr + if (interior) 12 else 8
    for ((i, c) in cells.withIndex()) {
        end -= c.size
        if (end < ptrBase + cells.size * 2) throw IllegalStateException("page overflow")
        c.copyInto(page, end)
        page[ptrBase + i * 2] = (end ushr 8).toByte(); page[ptrBase + i * 2 + 1] = end.toByte()
    }
    page[hdr + 5] = (end ushr 8).toByte(); page[hdr + 6] = end.toByte()
    if (interior) { page[hdr + 8] = (rightmost ushr 24).toByte(); page[hdr + 9] = (rightmost ushr 16).toByte(); page[hdr + 10] = (rightmost ushr 8).toByte(); page[hdr + 11] = rightmost.toByte() }
    return page
}

internal fun withLeft(left: Int, body: ByteArray): ByteArray {
    val out = Out()
    out.be32(left)
    out.bytes(body)
    return out.toByteArray()
}

internal const val CAP_LEAF = PAGE - 8
internal const val CAP_INTERIOR = PAGE - 12

/** Writes a table b-tree of (rowid, payload) rows in rowid order into the reserved [root] page. */
internal fun buildTable(pages: Pages, root: Int, rows: List<Pair<Long, ByteArray>>) {
    val leaves = ArrayList<List<ByteArray>>()
    val leafMax = ArrayList<Long>()
    var cur = ArrayList<ByteArray>()
    var used = 0
    var lastRowid = 0L
    for ((rowid, payload) in rows) {
        val cell = cellBody(pages, payload, rowid, MAX_LOCAL_TABLE)
        if (used + cell.size + 2 > CAP_LEAF && cur.isNotEmpty()) {
            leaves.add(cur); leafMax.add(lastRowid); cur = ArrayList(); used = 0
        }
        cur.add(cell); used += cell.size + 2; lastRowid = rowid
    }
    if (cur.isNotEmpty() || leaves.isEmpty()) { leaves.add(cur); leafMax.add(lastRowid) }
    var nodes = ArrayList<Int>()
    var keys = ArrayList<Long>(leafMax)
    if (leaves.size == 1) { pages.set(root, buildPage(0x0d, leaves[0], 0)); return }
    for (l in leaves) { val n = pages.alloc(); pages.set(n, buildPage(0x0d, l, 0)); nodes.add(n) }
    while (true) {
        // group the children so that each interior page has at least two
        val groups = ArrayList<IntRange>()
        var i = 0
        while (i < nodes.size) {
            var j = i
            var u = 0
            while (j < nodes.size - 1 && u + 4 + varintBytes(keys[j]).size + 2 <= CAP_INTERIOR) { u += 4 + varintBytes(keys[j]).size + 2; j++ }
            groups.add(i..j)
            i = j + 1
        }
        if (groups.size > 1 && groups.last().first == groups.last().last) {
            val last = groups.removeAt(groups.size - 1)
            val prev = groups.removeAt(groups.size - 1)
            groups.add(prev.first until prev.last)
            groups.add(prev.last..last.last)
        }
        val single = groups.size == 1
        val nextNodes = ArrayList<Int>()
        val nextKeys = ArrayList<Long>()
        for (g in groups) {
            val cells = (g.first until g.last).map { k -> Out().also { it.be32(nodes[k]); it.varint(keys[k]) }.toByteArray() }
            val page = buildPage(0x05, cells, nodes[g.last])
            val n = if (single) root else pages.alloc()
            pages.set(n, page)
            nextNodes.add(n); nextKeys.add(keys[g.last])
        }
        if (single) return
        nodes = nextNodes; keys = nextKeys
    }
}

/** Writes an index b-tree of already ordered record payloads into the reserved [root] page. */
internal fun buildIndex(pages: Pages, root: Int, entries: List<ByteArray>) {
    val bodies = entries.map { cellBody(pages, it, null, MAX_LOCAL_INDEX) }
    // leaf level: greedy fill, the entry that does not fit becomes the separator
    var groups = ArrayList<List<ByteArray>>()
    var seps = ArrayList<ByteArray>()
    var cur = ArrayList<ByteArray>()
    var used = 0
    for ((i, b) in bodies.withIndex()) {
        if (used + b.size + 2 > CAP_LEAF) {
            if (i == bodies.size - 1) {
                val s = cur.removeAt(cur.size - 1)
                groups.add(cur); seps.add(s)
                cur = arrayListOf(b)
                used = b.size + 2
            } else {
                groups.add(cur); seps.add(b)
                cur = ArrayList(); used = 0
            }
        } else {
            cur.add(b); used += b.size + 2
        }
    }
    groups.add(cur)
    if (groups.size == 1) { pages.set(root, buildPage(0x0a, groups[0], 0)); return }
    var nodes = ArrayList<Int>()
    for (g in groups) { val n = pages.alloc(); pages.set(n, buildPage(0x0a, g, 0)); nodes.add(n) }
    while (true) {
        val outGroups = ArrayList<Pair<List<ByteArray>, Int>>() // cells (with left pointers), rightmost
        val outSeps = ArrayList<ByteArray>()
        var idx = 0
        val n = nodes.size
        while (idx < n) {
            val cells = ArrayList<ByteArray>()
            var u = 0
            while (idx < n - 1 && u + 4 + seps[idx].size + 2 <= CAP_INTERIOR) {
                cells.add(withLeft(nodes[idx], seps[idx])); u += 4 + seps[idx].size + 2; idx++
            }
            var rightmost = nodes[idx]
            idx++
            if (idx == n - 1 && cells.size > 1) {
                // a lone child would be left for the next page: give the last cell back
                val c = cells.removeAt(cells.size - 1)
                rightmost = (((c[0].toInt() and 0xff) shl 24) or ((c[1].toInt() and 0xff) shl 16) or ((c[2].toInt() and 0xff) shl 8) or (c[3].toInt() and 0xff))
                idx--
            }
            outGroups.add(cells to rightmost)
            if (idx < n) outSeps.add(seps[idx - 1])
        }
        val single = outGroups.size == 1
        val nextNodes = ArrayList<Int>()
        for ((cells, right) in outGroups) {
            val page = buildPage(0x02, cells, right)
            val p = if (single) root else pages.alloc()
            pages.set(p, page)
            nextNodes.add(p)
        }
        if (single) return
        nodes = nextNodes; seps = outSeps
    }
}

/** Page 1: the database header and sqlite_master for the two tables and three indexes, whose roots are pages 2 to 6. */
internal fun firstPage(pageCount: Int): ByteArray {
    val master = listOf(
        record("table", "tiles", "tiles", 2, CREATE_TILES),
        record("table", "shapes", "shapes", 3, CREATE_SHAPES),
        record("index", "index_unique_tiles_by_worldspace_and_tile_position_and_input", "tiles", 4, CREATE_TILES_UNIQUE),
        record("index", "index_tiles_by_worldspace_and_tile_position", "tiles", 5, CREATE_TILES_INDEX),
        record("index", "index_unique_shapes_by_name_and_type_and_hash", "shapes", 6, CREATE_SHAPES_UNIQUE),
    )
    val masterCells = master.mapIndexed { i, r -> Out().also { it.varint(r.size.toLong()); it.varint(i + 1L); it.bytes(r) }.toByteArray() }
    val first = buildPage(0x0d, masterCells, 0, 100)
    val h = "SQLite format 3\u0000".encodeToByteArray()
    h.copyInto(first)
    fun be32(o: Int, v: Int) { first[o] = (v ushr 24).toByte(); first[o + 1] = (v ushr 16).toByte(); first[o + 2] = (v ushr 8).toByte(); first[o + 3] = v.toByte() }
    first[16] = (PAGE ushr 8).toByte(); first[17] = PAGE.toByte() // 4096 does not fit in a byte pair the other way round
    first[16] = 0x10; first[17] = 0x00
    first[18] = 1; first[19] = 1; first[20] = 0; first[21] = 64; first[22] = 32; first[23] = 32
    be32(24, 1); be32(28, pageCount); be32(40, 6); be32(44, 4); be32(56, 1); be32(92, 1); be32(96, 3045000)
    return first
}

internal fun writeNavMeshDb(tiles: List<TileRow>, shapes: List<ShapeRow>): ByteArray {
    val pages = Pages()
    repeat(6) { pages.alloc() }
    val tiles8 = tiles.sortedBy { it.id }
    buildTable(pages, 2, tiles8.map { it.id to record(null, it.revision, it.worldspace, it.x, it.y, it.version, it.input, it.data) })
    val shapes8 = shapes.sortedBy { it.id }
    buildTable(pages, 3, shapes8.map { it.id to record(null, it.name, it.type, it.hash) })
    val byInput = tiles.sortedWith { a, b -> compareTileKey(a, b, true) }
    buildIndex(pages, 4, byInput.map { record(it.worldspace, it.x, it.y, it.input, it.id) })
    val byPosition = tiles.sortedWith { a, b -> compareTileKey(a, b, false) }
    buildIndex(pages, 5, byPosition.map { record(it.worldspace, it.x, it.y, it.id) })
    val byShape = shapes.sortedWith { a, b ->
        val c = compareBytes(a.name.encodeToByteArray(), b.name.encodeToByteArray())
        if (c != 0) c else if (a.type != b.type) a.type.compareTo(b.type) else {
            val h = compareBytes(a.hash, b.hash)
            if (h != 0) h else a.id.compareTo(b.id)
        }
    }
    buildIndex(pages, 6, byShape.map { record(it.name, it.type, it.hash, it.id) })

    pages.set(1, firstPage(pages.list.size))
    val out = ByteArray(pages.list.size * PAGE)
    for ((i, p) in pages.list.withIndex()) (p ?: throw IllegalStateException("page ${i + 1} not written")).copyInto(out, i * PAGE)
    return out
}

internal fun compareTileKey(a: TileRow, b: TileRow, withInput: Boolean): Int {
    val w = compareBytes(a.worldspace.encodeToByteArray(), b.worldspace.encodeToByteArray())
    if (w != 0) return w
    if (a.x != b.x) return a.x.compareTo(b.x)
    if (a.y != b.y) return a.y.compareTo(b.y)
    if (withInput) { val c = compareBytes(a.input, b.input); if (c != 0) return c }
    return a.id.compareTo(b.id)
}

// ---------------------------------------------------------------- reader

private fun be16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff)
private fun be32(b: ByteArray, o: Int) = (be16(b, o) shl 16) or be16(b, o + 2)

private class VarintResult(val value: Long, val length: Int)

private fun readVarint(b: ByteArray, o: Int): VarintResult {
    var v = 0L
    for (i in 0 until 8) {
        val x = b[o + i].toInt() and 0xff
        v = (v shl 7) or (x and 0x7f).toLong()
        if (x < 0x80) return VarintResult(v, i + 1)
    }
    v = (v shl 8) or (b[o + 8].toInt() and 0xff).toLong()
    return VarintResult(v, 9)
}

private class DbReader(val db: ByteArray) {
    val pageSize: Int
    init {
        if (db.size < 100 || CharArray(15) { db[it].toInt().toChar() }.concatToString() != "SQLite format 3") throw NavMeshException("not an SQLite database")
        val ps = be16(db, 16)
        pageSize = if (ps == 1) 65536 else ps
        if (db.size % pageSize != 0) throw NavMeshException("truncated SQLite database")
    }

    fun payload(page: Int, cell: Int, tableLeaf: Boolean, maxLocalFor: Int): ByteArray {
        var p = cell
        val size = readVarint(db, p).also { p += it.length }.value.toInt()
        if (tableLeaf) p += readVarint(db, p).length
        val local = localSizeFor(size, maxLocalFor)
        val out = ByteArray(size)
        db.copyInto(out, 0, p, p + local)
        if (local < size) {
            var next = be32(db, p + local)
            var pos = local
            while (pos < size) {
                if (next <= 0 || next * pageSize > db.size) throw NavMeshException("bad overflow chain")
                val base = (next - 1) * pageSize
                val len = minOf(pageSize - 4, size - pos)
                db.copyInto(out, pos, base + 4, base + 4 + len)
                pos += len
                next = be32(db, base)
            }
        }
        return out
    }

    private fun localSizeFor(p: Int, maxLocal: Int): Int {
        if (p <= maxLocal) return p
        val minLocal = ((pageSize - 12) * 32 / 255) - 23
        val k = minLocal + ((p - minLocal) % (pageSize - 4))
        return if (k <= maxLocal) k else minLocal
    }

    /** Visits (rowid, payload) of a table b-tree. */
    fun scanTable(root: Int, f: (Long, ByteArray) -> Unit) {
        val hdr = if (root == 1) 100 else 0
        val base = (root - 1) * pageSize
        val flag = db[base + hdr].toInt()
        val n = be16(db, base + hdr + 3)
        when (flag) {
            0x0d -> for (i in 0 until n) {
                val cell = base + be16(db, base + hdr + 8 + i * 2)
                var p = cell
                readVarint(db, p).also { p += it.length }
                val rowid = readVarint(db, p).value
                f(rowid, payload(root, cell, true, pageSize - 35))
            }
            0x05 -> {
                for (i in 0 until n) scanTable(be32(db, base + be16(db, base + hdr + 12 + i * 2)), f)
                scanTable(be32(db, base + hdr + 8), f)
            }
            else -> throw NavMeshException("unexpected page type $flag")
        }
    }
}

private fun decodeRecord(b: ByteArray): List<Any?> {
    var p = 0
    val hs = readVarint(b, 0).also { p += it.length }.value.toInt()
    val types = ArrayList<Long>()
    while (p < hs) { val v = readVarint(b, p); types.add(v.value); p += v.length }
    val out = ArrayList<Any?>()
    var d = hs
    for (t in types) {
        when {
            t == 0L -> out.add(null)
            t == 8L -> out.add(0L)
            t == 9L -> out.add(1L)
            t in 1..6 -> {
                val len = intSizes[t.toInt()]
                var v = if (b[d].toInt() < 0) -1L else 0L
                for (k in 0 until len) v = (v shl 8) or (b[d + k].toLong() and 0xff)
                out.add(v); d += len
            }
            t >= 12 && t % 2 == 0L -> { val len = ((t - 12) / 2).toInt(); out.add(b.copyOfRange(d, d + len)); d += len }
            t >= 13 -> { val len = ((t - 13) / 2).toInt(); out.add(b.copyOfRange(d, d + len).decodeToString()); d += len }
            else -> throw NavMeshException("unsupported column type $t")
        }
    }
    return out
}

/** Reads the tiles and shapes of a navmesh.db file written by this tool or by OpenMW. */
internal fun readNavMeshDb(bytes: ByteArray): Pair<List<TileRow>, List<ShapeRow>> {
    val r = DbReader(bytes)
    val roots = HashMap<String, Int>()
    r.scanTable(1) { _, payload ->
        val c = decodeRecord(payload)
        if (c[0] == "table") roots[c[1] as String] = (c[3] as Long).toInt()
    }
    val tiles = ArrayList<TileRow>()
    val shapes = ArrayList<ShapeRow>()
    roots["tiles"]?.let { root ->
        r.scanTable(root) { id, payload ->
            val c = decodeRecord(payload)
            tiles.add(TileRow(id, c[1] as Long, c[2] as String, (c[3] as Long).toInt(), (c[4] as Long).toInt(), (c[5] as Long).toInt(), (c[6] as? ByteArray) ?: ByteArray(0), c[7] as? ByteArray))
        }
    }
    roots["shapes"]?.let { root ->
        r.scanTable(root) { id, payload ->
            val c = decodeRecord(payload)
            shapes.add(ShapeRow(id, c[1] as String, (c[2] as Long).toInt(), c[3] as ByteArray))
        }
    }
    return tiles to shapes
}
