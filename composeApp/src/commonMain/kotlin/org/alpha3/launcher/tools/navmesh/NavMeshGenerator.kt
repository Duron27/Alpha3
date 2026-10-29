// SPDX-License-Identifier: GPL-3.0-or-later
// The main flow of OpenMW's navmeshtool: gather every worldspace, generate the tiles that are missing from
// navmesh.db and write the database back.
package org.alpha3.launcher.tools.navmesh

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.alpha3.launcher.tools.NavMeshTool
import org.alpha3.launcher.tools.openmw.TextCodec
import org.alpha3.launcher.tools.openmw.ToolFs
import org.alpha3.launcher.tools.openmw.Vfs
import org.alpha3.launcher.utils.freeMemoryBytes
import org.alpha3.launcher.utils.maxMemoryBytes
import kotlin.coroutines.coroutineContext
import kotlin.time.TimeSource

internal const val DEFAULT_WORLDSPACE = "sys::default"
private const val SHAPE_AVOID = 2
private const val SHAPE_COLLISION = 1
/** Below this much free memory the generator slows down: fewer tiles in flight, smaller batches of cells. */
internal fun lowMemoryBytes(): Long = maxOf(48L shl 20, maxMemoryBytes() / 8)

/** How often a database that is rebuilt in memory is written out. */
private const val SAVE_INTERVAL_SECONDS = 90
/** How often a database that grows as tiles are made is flushed to the file. */
private const val FLUSH_INTERVAL_SECONDS = 5

internal class NavMeshJob(
    val content: List<String>,
    val vfs: Vfs,
    val fs: ToolFs,
    val codec: TextCodec,
    val dbPath: String,
    val settings: RecastSettings = RecastSettings(),
    val agent: AgentBounds = AgentBounds(),
    val processInteriorCells: Boolean = false,
    val removeUnusedTiles: Boolean = false,
    val worldspaceFilter: Regex = Regex(".*"),
    val threads: Int = 1,
)

internal class NavMeshResult(val inserted: Int, val updated: Int, val deleted: Int, val unchanged: Int)

/** Misc::compress: the original size as a host-endian u64, then the LZ4 block. */
internal fun miscCompress(data: ByteArray): ByteArray {
    val block = Lz4.compress(data)
    val out = ByteArray(8 + block.size)
    for (i in 0 until 8) out[i] = (data.size.toLong() shr (8 * i)).toByte()
    block.copyInto(out, 8)
    return out
}

private fun hex(b: ByteArray): String {
    val sb = StringBuilder(b.size * 2)
    for (x in b) { val v = x.toInt() and 0xff; sb.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 15]) }
    return sb.toString()
}

private class TileResult(
    val inputBlob: ByteArray, val existing: TileRow?, val prepared: PreparedNavMeshData?,
    /** Where the time went, in nanoseconds: the mesh of the tile, its input blob, the navmesh. */
    val meshNs: Long = 0, val inputNs: Long = 0, val prepareNs: Long = 0, val meshTriangles: Int = 0, val stages: StageTimes? = null,
)

/** Builds one tile's mesh and input and, unless the database already has it, the navmesh; null when the tile has no input. */
private suspend fun buildTile(
    tile: TilePos, data: WorldspaceData, db: Database, shapeLock: Mutex, worldspace: String,
    settings: RecastSettings, job: NavMeshJob,
): TileResult? {
    val t0 = TimeSource.Monotonic.markNow()
    val mesh = data.makeMesh(tile)
    if (mesh == null || mesh.isEmpty) return null
    val meshNs = t0.elapsedNow().inWholeNanoseconds
    val objects = ArrayList<DbRefGeometryObject>(mesh.sources.size)
    for (s in mesh.sources) {
        val type = if (s.areaType == AREA_NULL) SHAPE_AVOID else SHAPE_COLLISION
        val id = shapeLock.withLock { db.shapeId(s.shape.fileName, type, s.shape.fileHash) }
        objects.add(DbRefGeometryObject(id, s.transform))
    }
    objects.sortWith(::compareObjects)
    val t1 = TimeSource.Monotonic.markNow()
    val inputBlob = miscCompress(serializeInput(settings, job.agent, mesh, objects))
    val existing = db.find(worldspace, tile.x, tile.y, inputBlob)
    val inputNs = t1.elapsedNow().inWholeNanoseconds
    val t2 = TimeSource.Monotonic.markNow()
    val stages = StageTimes()
    val prepared = if (existing != null && existing.version == NAV_MESH_FORMAT_VERSION) null
    else prepareNavMeshTileData(mesh, tile, job.agent, settings, stages)
    return TileResult(inputBlob, existing, prepared, meshNs, inputNs, t2.elapsedNow().inWholeNanoseconds, mesh.mesh.indices.size / 3, stages)
}

private class Database(tiles: List<TileRow>, shapes: List<ShapeRow>, val writer: NavMeshDbWriter? = null) {
    val tileRows = ArrayList(tiles)
    val shapeRows = ArrayList(shapes)
    private val byPosition = HashMap<String, MutableList<TileRow>>()
    private val shapeIds = HashMap<String, Long>()
    var nextTileId = (tiles.maxOfOrNull { it.id } ?: 0L) + 1
    var nextShapeId = (shapes.maxOfOrNull { it.id } ?: 0L) + 1
    var dirty = false

    init {
        for (t in tiles) byPosition.getOrPut(posKey(t.worldspace, t.x, t.y)) { ArrayList() }.add(t)
        for (s in shapes) shapeIds[shapeKey(s.name, s.type, s.hash)] = s.id
    }

    private fun posKey(ws: String, x: Int, y: Int) = "$ws|$x|$y"
    private fun shapeKey(name: String, type: Int, hash: ByteArray) = "$name|$type|${hex(hash)}"

    fun find(ws: String, x: Int, y: Int, input: ByteArray): TileRow? =
        byPosition[posKey(ws, x, y)]?.firstOrNull { it.input.contentEquals(input) }

    fun shapeId(name: String, type: Int, hash: ByteArray): Long = shapeIds.getOrPut(shapeKey(name, type, hash)) {
        val id = nextShapeId++
        shapeRows.add(ShapeRow(id, name, type, hash))
        dirty = true
        id
    }

    fun insert(ws: String, x: Int, y: Int, version: Int, input: ByteArray, data: ByteArray): Long {
        val row = TileRow(nextTileId++, 1, ws, x, y, version, input, data)
        dirty = true
        // A growing database takes the tile straight into the file; nothing of it is kept here
        if (writer != null) { writer.addTile(row); return row.id }
        tileRows.add(row)
        byPosition.getOrPut(posKey(ws, x, y)) { ArrayList() }.add(row)
        return row.id
    }

    fun update(row: TileRow, version: Int, data: ByteArray) {
        row.version = version; row.data = data; row.revision += 1
        dirty = true
    }

    /** Deletes the tiles at a position other than [keep]; returns how many. */
    fun deleteAt(ws: String, x: Int, y: Int, keep: TileRow?): Int {
        val list = byPosition[posKey(ws, x, y)] ?: return 0
        val gone = list.filter { it !== keep }
        if (gone.isEmpty()) return 0
        list.removeAll(gone.toSet())
        tileRows.removeAll(gone.toSet())
        dirty = true
        return gone.size
    }

    fun deleteOutside(ws: String, r: TilesRange): Int {
        val gone = tileRows.filter { it.worldspace == ws && !r.contains(it.x, it.y) }
        for (t in gone) byPosition[posKey(t.worldspace, t.x, t.y)]?.remove(t)
        tileRows.removeAll(gone.toSet())
        if (gone.isNotEmpty()) dirty = true
        return gone.size
    }

    fun tileId(row: TileRow) = row.id
}

private fun save(job: NavMeshJob, db: Database) {
    if (db.writer != null) { db.writer.flush(); db.dirty = false; return }
    val bytes = writeNavMeshDb(db.tileRows, db.shapeRows)
    val tmp = job.dbPath + ".tmp"
    job.fs.delete(tmp)
    val w = job.fs.openWrite(tmp)
    try { w.write(bytes, 0, bytes.size) } finally { w.close() }
    job.fs.delete(job.dbPath)
    job.fs.rename(tmp, job.dbPath)
    db.dirty = false
}

/** Generates the navmesh tiles of all selected worldspaces into [NavMeshJob.dbPath]. */
internal suspend fun runNavMeshJob(job: NavMeshJob, log: (String) -> Unit, progress: (NavMeshTool.Message) -> Unit): NavMeshResult {
    val esm = loadEsmData(job.content, job.vfs, job.fs, job.codec, log)
    val swim = esm.gameSettings["fswimheightscale"] ?: throw NavMeshException("Game setting fSwimHeightScale is not found")
    val settings = job.settings.withSwimHeightScale(swim)

    val byWorldspace = LinkedHashMap<String, MutableList<EsmCell>>()
    for (cell in esm.cells) {
        if (cell.exterior) byWorldspace.getOrPut(DEFAULT_WORLDSPACE) { ArrayList() }.add(cell)
        else if (job.processInteriorCells) byWorldspace.getOrPut(cell.name.lowercase()) { ArrayList() }.add(cell)
    }
    val selected = byWorldspace.filterKeys { job.worldspaceFilter.matches(it) }
    val cellsTotal = selected.values.sumOf { it.size }
    log("Found ${selected.size} worldspaces with $cellsTotal cells")
    progress(NavMeshTool.Message.ExpectedCells(cellsTotal.toLong()))

    val existing = if (job.fs.isFile(job.dbPath)) readFile(job).let { readNavMeshDb(it) } else emptyList<TileRow>() to emptyList()
    // With nothing in the database yet it grows as the tiles are made; otherwise it is rewritten from memory
    val growing = existing.first.isEmpty() && existing.second.isEmpty()
    var db: Database
    if (growing) {
        job.fs.delete(job.dbPath)
        lateinit var created: Database
        db = Database(emptyList(), emptyList(), NavMeshDbWriter(job.fs, job.dbPath) { created.shapeRows })
        created = db
        log("Writing ${job.dbPath} as the tiles are made")
    } else {
        db = Database(existing.first, existing.second)
        log("Updating ${existing.first.size} tiles of ${job.dbPath}; it is written when the tiles are done")
    }
    val shapes = ShapeCache(job.vfs, job.fs, log)

    var inserted = 0; var updated = 0; var deleted = 0; var unchanged = 0
    var cellsDone = 0L
    var expected = 0L
    var generated = 0L
    val clock = TimeSource.Monotonic
    var lastSave = clock.markNow()
    val chunkSize = maxOf(1, job.threads) * 16
    val shapeLock = Mutex()

    // A growing database needs the tiles in index order: worldspaces by the bytes of their names
    val order = if (growing) selected.entries.sortedWith { a, b -> compareBytes(a.key.encodeToByteArray(), b.key.encodeToByteArray()) } else selected.entries.toList()
    try {
        for ((worldspace, cells) in order) {
            coroutineContext.ensureActive()
            log("Processing worldspace $worldspace")
            val base = cellsDone
            val data = gatherWorldspace(cells, esm, job.fs, job.codec, shapes, settings, job.threads, log) { progress(NavMeshTool.Message.ProcessedCells(base + it)) }
            cellsDone += cells.size
            val tiles = data.tiles()
            expected += tiles.size
            progress(NavMeshTool.Message.ExpectedTiles(expected))
            log("Generating navmesh for ${tiles.size} tiles of worldspace $worldspace")

            // Tiles are built continuously on every thread, a few per thread ahead of the writer (each one in flight holds its mesh, and a heap full of them is all the garbage collector does), so no thread waits at a
            // chunk boundary; the writer consumes the results in tile order, which keeps ids and the output deterministic.
            coroutineScope {
                val window = Channel<Pair<TilePos, Deferred<TileResult?>>>(maxOf(4, job.threads * 3))
                var consumed = 0L
                var produced = 0L
                val producer = launch {
                    for (t in tiles) {
                        // Under memory pressure fewer tiles are made ahead of the writer; each one in flight holds its mesh
                        while (produced - consumed > 2 && freeMemoryBytes() < lowMemoryBytes()) delay(25)
                        produced++
                        window.send(t to async(Dispatchers.Default) { buildTile(t, data, db, shapeLock, worldspace, settings, job) })
                    }
                    window.close()
                }
                var sinceLog = 0
                var emptyTiles = 0L; var noNavMesh = 0L; var triangles = 0L
                var meshMs = 0.0; var inputMs = 0.0; var prepareMs = 0.0
                val stageMs = DoubleArray(StageTimes.names.size)
                for ((tile, deferred) in window) {
                    coroutineContext.ensureActive()
                    val w = deferred.await()
                    consumed++
                    if (w == null) emptyTiles++ else {
                        meshMs += w.meshNs / 1_000_000.0; inputMs += w.inputNs / 1_000_000.0; prepareMs += w.prepareNs / 1_000_000.0
                        triangles += w.meshTriangles
                        w.stages?.let { st -> for (k in st.ns.indices) stageMs[k] += st.ns[k] / 1_000_000.0 }
                        if (w.prepared == null && w.existing == null) noNavMesh++
                    }
                    if (w == null) {
                        if (job.removeUnusedTiles) deleted += db.deleteAt(worldspace, tile.x, tile.y, null)
                    } else {
                        val ex = w.existing
                        if (ex != null && ex.version == NAV_MESH_FORMAT_VERSION) {
                            unchanged++
                            if (job.removeUnusedTiles) deleted += db.deleteAt(worldspace, tile.x, tile.y, ex)
                        } else {
                            val p = w.prepared
                            if (p == null) {
                                if (job.removeUnusedTiles) deleted += db.deleteAt(worldspace, tile.x, tile.y, null)
                            } else if (ex != null) {
                                p.userId = ex.id.toInt()
                                db.update(ex, NAV_MESH_FORMAT_VERSION, miscCompress(serializePrepared(p)))
                                updated++
                            } else {
                                p.userId = db.nextTileId.toInt()
                                db.insert(worldspace, tile.x, tile.y, NAV_MESH_FORMAT_VERSION, w.inputBlob, miscCompress(serializePrepared(p)))
                                inserted++
                            }
                        }
                    }
                    generated++
                    if (++sinceLog >= chunkSize) {
                        sinceLog = 0
                        progress(NavMeshTool.Message.GeneratedTiles(generated))
                        log("$generated/$expected (${generated.toDouble() / expected.toDouble() * 100}%) navmesh tiles are generated; $inserted new, $updated updated, $unchanged unchanged; $emptyTiles without input, $noNavMesh without a navmesh; thread time per tile: mesh ${(meshMs / maxOf(1L, generated - emptyTiles)).format1()} ms, input ${(inputMs / maxOf(1L, generated - emptyTiles)).format1()} ms, navmesh ${(prepareMs / maxOf(1L, generated - emptyTiles)).format1()} ms, ${triangles / maxOf(1L, generated - emptyTiles)} triangles; navmesh steps (ms per tile): ${StageTimes.names.indices.joinToString { StageTimes.names[it] + " " + (stageMs[it] / maxOf(1L, generated - emptyTiles)).format1() }}")
                        if (db.dirty && lastSave.elapsedNow().inWholeSeconds >= (if (growing) FLUSH_INTERVAL_SECONDS else SAVE_INTERVAL_SECONDS)) { save(job, db); lastSave = clock.markNow() }
                    }
                }
                producer.join()
            }
            progress(NavMeshTool.Message.GeneratedTiles(generated))
            log("$generated/$expected (${generated.toDouble() / expected.toDouble() * 100}%) navmesh tiles are generated")
            if (job.removeUnusedTiles && data.aabbValid) {
                val a = data.aabb
                deleted += db.deleteOutside(worldspace, settings.tilesRange(a[0].toFloat(), a[1].toFloat(), a[3].toFloat(), a[4].toFloat()))
            }
        }
    } catch (e: Throwable) {
        // The heap is a fixed size on a phone; say so, and keep what was made (the finally below writes it)
        if (e::class.simpleName == "OutOfMemoryError") {
            throw NavMeshException("ran out of memory (${(maxMemoryBytes() shr 20)} MB available to the app) after $generated of $expected tiles; " +
                "tiles made so far are kept, run it again to continue, and close other apps first")
        }
        throw e
    } finally {
        // Cancelled or failed: what is done so far stays in the file, which is a valid database after every flush
        if (growing && db.dirty) try { save(job, db) } catch (e: Exception) { log("Could not write the tiles made so far: ${e.message}") }
    }
    if (db.dirty || !job.fs.isFile(job.dbPath)) save(job, db)
    log("Done: $inserted inserted, $updated updated, $deleted deleted, $unchanged unchanged")
    return NavMeshResult(inserted, updated, deleted, unchanged)
}

private fun readFile(job: NavMeshJob): ByteArray {
    val size = job.fs.size(job.dbPath).toInt()
    val out = ByteArray(size)
    val r = job.fs.openRead(job.dbPath)
    try {
        var done = 0
        while (done < size) {
            val n = r.read(done.toLong(), out, done, size - done)
            if (n <= 0) throw NavMeshException("unexpected end of ${job.dbPath}")
            done += n
        }
    } finally {
        r.close()
    }
    return out
}

private fun Double.format1(): String = ((this * 10).toLong() / 10.0).toString()
