// SPDX-License-Identifier: GPL-3.0-or-later
// Writes navmesh.db while the tiles are being made, the way SQLite itself would: rows are appended to the table and index
// b-trees and the file grows, instead of the whole database being kept in memory and written at the end. Tiles have to
// arrive in the order of the indexes (worldspace, then tile position), which is the order the generator makes them in;
// the tile ids are then ascending as well, so every tree only grows on its right edge.
package org.alpha3.launcher.tools.navmesh

import org.alpha3.launcher.tools.openmw.ToolFs

/** Hands out page numbers and keeps the finished pages until the next flush. */
private class AppendPages(var next: Int) : Pages() {
    val pending = LinkedHashMap<Int, ByteArray>()
    override fun alloc(): Int = next++
    override fun set(n: Int, b: ByteArray) { pending[n] = b }
}

/** Reuses the pages a rebuilt tree used last time, so that rebuilding the (small) shapes trees does not leave unused pages behind. */
private class PoolPages(private val owner: AppendPages, private val pool: ArrayList<Int>) : Pages() {
    private var used = 0
    override fun alloc(): Int {
        if (used < pool.size) return pool[used++]
        val n = owner.alloc()
        pool.add(n)
        used++
        return n
    }
    override fun set(n: Int, b: ByteArray) = owner.set(n, b)
}

private class Level(var pageNo: Int) {
    val cells = ArrayList<ByteArray>()
    /** Interior levels: the key (table: largest rowid below) of each cell. */
    val keys = ArrayList<Long>()
    var used = 0
    var lastRowid = 0L
}

/**
 * One b-tree that only grows on the right. A full page is written for good and the level starts a new one; the pages that are
 * still open are written as they are at every [snapshot], so the file is always a valid database. A page is never left
 * without cells: the entry that does not fit moves up as the separator (index) or the page gives up its last cell.
 */
private class AppendTree(private val pages: AppendPages, private val root: Int, private val index: Boolean) {
    private val levels = arrayListOf(Level(root))

    private fun leafFlag() = if (index) 0x0a else 0x0d
    private fun interiorFlag() = if (index) 0x02 else 0x05

    fun addRow(rowid: Long, payload: ByteArray) = addToLeaf(cellBody(pages, payload, rowid, MAX_LOCAL_TABLE), rowid)

    fun addEntry(entry: ByteArray) = addToLeaf(cellBody(pages, entry, null, MAX_LOCAL_INDEX), 0)

    private fun addToLeaf(cell: ByteArray, rowid: Long) {
        val leaf = levels[0]
        if (leaf.used + cell.size + 2 > CAP_LEAF && leaf.cells.isNotEmpty()) {
            if (index) {
                val separator = leaf.cells.removeAt(leaf.cells.size - 1)
                leaf.used -= separator.size + 2
                val no = closeLevel(0, 0)
                pushUp(1, no, 0, separator)
            } else {
                val key = leaf.lastRowid
                val no = closeLevel(0, 0)
                pushUp(1, no, key, null)
            }
        }
        leaf.cells.add(cell)
        leaf.used += cell.size + 2
        leaf.lastRowid = rowid
    }

    /** Writes the open page of level [l] as a finished one and starts a new open page; returns the finished page's number. */
    private fun closeLevel(l: Int, rightmost: Int): Int {
        val lv = levels[l]
        // The top page lives in the fixed root page; a finished one goes to a new page and the root is taken by the level above
        val no = if (l == levels.size - 1) pages.alloc() else lv.pageNo
        pages.set(no, buildPage(if (l == 0) leafFlag() else interiorFlag(), lv.cells, rightmost))
        lv.pageNo = pages.alloc()
        lv.cells.clear(); lv.keys.clear(); lv.used = 0
        return no
    }

    private fun pushUp(l: Int, leftNo: Int, key: Long, separator: ByteArray?) {
        if (l == levels.size) levels.add(Level(root))
        val lv = levels[l]
        val cell = if (index) withLeft(leftNo, separator!!) else Out().also { it.be32(leftNo); it.varint(key) }.toByteArray()
        if (lv.used + cell.size + 2 > CAP_INTERIOR && lv.cells.size >= 2) {
            val last = lv.cells.removeAt(lv.cells.size - 1)
            val lastKey = lv.keys.removeAt(lv.keys.size - 1)
            lv.used -= last.size + 2
            val lastLeft = ((last[0].toInt() and 0xff) shl 24) or ((last[1].toInt() and 0xff) shl 16) or ((last[2].toInt() and 0xff) shl 8) or (last[3].toInt() and 0xff)
            val no = closeLevel(l, lastLeft)
            pushUp(l + 1, no, lastKey, if (index) last.copyOfRange(4, last.size) else null)
        }
        lv.cells.add(cell); lv.keys.add(key); lv.used += cell.size + 2
    }

    /** Puts the open pages, with the rows added so far, into [pages]. */
    fun snapshot() {
        for ((l, lv) in levels.withIndex()) {
            pages.set(lv.pageNo, if (l == 0) buildPage(leafFlag(), lv.cells, 0) else buildPage(interiorFlag(), lv.cells, levels[l - 1].pageNo))
        }
    }
}

/**
 * navmesh.db that grows as tiles are added. [shapes] is read at every [flush] (the shapes trees are small, so they are
 * rebuilt each time). Nothing reaches the file until [flush], which writes whole pages and the header last.
 */
internal class NavMeshDbWriter(private val fs: ToolFs, private val path: String, private val shapes: () -> List<ShapeRow>) {
    private val pages = AppendPages(7)
    private val tiles = AppendTree(pages, 2, false)
    private val byInput = AppendTree(pages, 4, true)
    private val byPosition = AppendTree(pages, 5, true)
    private val shapePool = ArrayList<Int>()
    private var lastWorldspace: ByteArray? = null
    private var lastX = 0
    private var lastY = 0
    var tileCount = 0
        private set

    init {
        // Pages 1 to 6 are the header and the roots; the empty trees are valid as they are
        val first = ByteArray(PAGE * 6)
        firstPage(6).copyInto(first, 0)
        for (root in 2..6) buildPage(if (root == 2 || root == 3) 0x0d else 0x0a, emptyList(), 0).copyInto(first, (root - 1) * PAGE)
        val w = fs.openWrite(path)
        try { w.write(first, 0, first.size) } finally { w.close() }
    }

    /** Adds a tile; ids must ascend and positions must ascend within a worldspace, worldspaces in byte order. */
    fun addTile(row: TileRow) {
        val ws = row.worldspace.encodeToByteArray()
        val last = lastWorldspace
        if (last != null) {
            val c = compareBytes(last, ws)
            if (c > 0 || (c == 0 && (row.x < lastX || (row.x == lastX && row.y <= lastY)))) {
                throw NavMeshException("tile ${row.worldspace} ${row.x},${row.y} arrives out of order (after ${last.decodeToString()} $lastX,$lastY)")
            }
        }
        lastWorldspace = ws; lastX = row.x; lastY = row.y
        tiles.addRow(row.id, record(null, row.revision, row.worldspace, row.x, row.y, row.version, row.input, row.data))
        byInput.addEntry(record(row.worldspace, row.x, row.y, row.input, row.id))
        byPosition.addEntry(record(row.worldspace, row.x, row.y, row.id))
        tileCount++
    }

    /** Writes everything added so far; the file is a valid database afterwards. */
    fun flush() {
        tiles.snapshot(); byInput.snapshot(); byPosition.snapshot()
        val rows = shapes()
        val shapePages = PoolPages(pages, shapePool)
        buildTable(shapePages, 3, rows.sortedBy { it.id }.map { it.id to record(null, it.name, it.type, it.hash) })
        val byShape = rows.sortedWith { a, b ->
            val c = compareBytes(a.name.encodeToByteArray(), b.name.encodeToByteArray())
            if (c != 0) c else if (a.type != b.type) a.type.compareTo(b.type) else {
                val h = compareBytes(a.hash, b.hash)
                if (h != 0) h else a.id.compareTo(b.id)
            }
        }
        buildIndex(shapePages, 6, byShape.map { record(it.name, it.type, it.hash, it.id) })

        val numbers = pages.pending.keys.sorted()
        // Neighbouring pages go out in one write, in chunks of at most 4 MB
        var i = 0
        while (i < numbers.size) {
            var j = i
            while (j + 1 < numbers.size && numbers[j + 1] == numbers[j] + 1 && j + 1 - i < 1024) j++
            val out = ByteArray((j - i + 1) * PAGE)
            for (k in i..j) pages.pending.getValue(numbers[k]).copyInto(out, (k - i) * PAGE)
            fs.patch(path, (numbers[i] - 1).toLong() * PAGE, out)
            i = j + 1
        }
        pages.pending.clear()
        // The header comes last: it makes the new page count count
        fs.patch(path, 0, firstPage(pages.next - 1))
    }
}
