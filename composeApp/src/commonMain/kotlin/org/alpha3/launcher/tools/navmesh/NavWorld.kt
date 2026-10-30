// SPDX-License-Identifier: GPL-3.0-or-later
// The worldspace gathering of OpenMW's navmeshtool (worldspacedata.cpp) with the tile bookkeeping of
// TileCachedRecastMeshManager: which objects, water and terrain touch which tiles, and the mesh of a tile.
package org.alpha3.launcher.tools.navmesh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import org.alpha3.launcher.tools.sortedIndexOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.alpha3.launcher.utils.freeMemoryBytes
import org.alpha3.launcher.utils.maxMemoryBytes
import kotlin.coroutines.coroutineContext
import kotlin.time.TimeSource
import org.alpha3.launcher.tools.openmw.TextCodec
import org.alpha3.launcher.tools.openmw.ToolFs
import org.alpha3.launcher.tools.openmw.Vfs

private fun objectTransformOf(ot: ObjectTransform) =
    Transform(Quat.fromEsmRotation(ot.rot).toBasis(), V3(ot.pos[0].toDouble(), ot.pos[1].toDouble(), ot.pos[2].toDouble()))

private class PlacedWater(val cellX: Int, val cellY: Int, val water: Water)

private class PlacedHeightfield(val cellX: Int, val cellY: Int, val cellSize: Int, val plane: Float, val surface: LandHeights?)

private fun cellKey(x: Int, y: Int) = (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)

/** The navigation input of one worldspace, queryable by tile. */
internal class WorldspaceData(private val settings: RecastSettings) {
    // Objects are kept as numbers in flat arrays (a big world has hundreds of thousands); the shapes are shared between objects
    private val sourceTable = ArrayList<NavShape>()
    private val sourceIds = HashMap<NavShape, Int>()
    private var objectCount = 0
    val objectTotal get() = objectCount
    val heightfieldTotal get() = fields.size
    private var objShape = IntArray(1024)
    private var objArea = ByteArray(1024)
    private var objPlacement = FloatArray(7 * 1024) // position, rotation, scale
    private var objRange = IntArray(4 * 1024) // the tiles it touches: beginX, beginY, endX, endY
    private val waters = ArrayList<PlacedWater>()
    private val waterRanges = ArrayList<TilesRange?>()
    private val waterCells = HashSet<Long>()
    private val fields = ArrayList<PlacedHeightfield>()
    private val fieldRanges = ArrayList<TilesRange?>()
    private val fieldCells = HashSet<Long>()
    // Which items touch which tile: the tiles with some input, sorted, and for each kind a packed list per tile
    private var tileKeys = LongArray(0)
    private var objIndex = TileIndex(IntArray(1), IntArray(0))
    private var waterIndex = TileIndex(IntArray(1), IntArray(0))
    private var fieldIndex = TileIndex(IntArray(1), IntArray(0))
    private var infiniteWater = ArrayList<Int>()
    private var infiniteFields = ArrayList<Int>()

    /** minX, minY, minZ, maxX, maxY, maxZ; valid when [aabbValid]. */
    val aabb = DoubleArray(6)
    var aabbValid = false
        private set

    fun mergeAabb(minX: Double, minY: Double, minZ: Double, maxX: Double, maxY: Double, maxZ: Double) {
        if (!aabbValid) {
            aabb[0] = minX; aabb[1] = minY; aabb[2] = minZ; aabb[3] = maxX; aabb[4] = maxY; aabb[5] = maxZ
            aabbValid = true
        } else {
            aabb[0] = minOf(aabb[0], minX); aabb[1] = minOf(aabb[1], minY); aabb[2] = minOf(aabb[2], minZ)
            aabb[3] = maxOf(aabb[3], maxX); aabb[4] = maxOf(aabb[4], maxY); aabb[5] = maxOf(aabb[5], maxZ)
        }
    }

    fun mergeAabb(a: Aabb) = mergeAabb(a.min.x, a.min.y, a.min.z, a.max.x, a.max.y, a.max.z)

    fun addObject(shape: BtShape, transform: Transform, areaType: Int, source: NavShape, ot: ObjectTransform) {
        if (objectCount == objShape.size) {
            val n = objectCount * 2
            objShape = objShape.copyOf(n); objArea = objArea.copyOf(n); objPlacement = objPlacement.copyOf(7 * n); objRange = objRange.copyOf(4 * n)
        }
        val id = sourceIds.getOrPut(source) { sourceTable.add(source); sourceTable.size - 1 }
        val range = settings.tilesRangeOfShape(shape, transform)
        val i = objectCount++
        objShape[i] = id
        objArea[i] = areaType.toByte()
        for (k in 0 until 3) { objPlacement[7 * i + k] = ot.pos[k]; objPlacement[7 * i + 3 + k] = ot.rot[k] }
        objPlacement[7 * i + 6] = ot.scale
        objRange[4 * i] = range.beginX; objRange[4 * i + 1] = range.beginY; objRange[4 * i + 2] = range.endX; objRange[4 * i + 3] = range.endY
    }

    fun addWater(cellX: Int, cellY: Int, size: Int, level: Float) {
        if (!waterCells.add(cellKey(cellX, cellY))) return
        waters.add(PlacedWater(cellX, cellY, Water(size, level)))
        waterRanges.add(
            if (size == Int.MAX_VALUE) null
            else settings.tilesRangeForCell(size, ((cellX + 0.5f) * size).toDouble(), ((cellY + 0.5f) * size).toDouble()),
        )
    }

    fun addHeightfield(cellX: Int, cellY: Int, size: Int, plane: Float, surface: LandHeights?) {
        if (!fieldCells.add(cellKey(cellX, cellY))) return
        fields.add(PlacedHeightfield(cellX, cellY, size, plane, surface))
        fieldRanges.add(
            if (size == Int.MAX_VALUE) null
            else settings.tilesRangeForCell(size, ((cellX + 0.5f) * size).toDouble(), ((cellY + 0.5f) * size).toDouble()),
        )
    }

    private var sortedTiles: List<TilePos>? = null

    /** Every tile that has some input, in ascending order. */
    fun tiles(): List<TilePos> {
        sortedTiles?.let { return it }
        val objRangeOf = { i: Int, r: IntArray -> objRange.copyInto(r, 0, 4 * i, 4 * i + 4); true }
        val waterRangeOf = { i: Int, r: IntArray -> rangeInto(waterRanges[i], r) }
        val fieldRangeOf = { i: Int, r: IntArray -> rangeInto(fieldRanges[i], r) }
        infiniteWater = ArrayList<Int>().also { l -> for (i in waterRanges.indices) if (waterRanges[i] == null) l.add(i) }
        infiniteFields = ArrayList<Int>().also { l -> for (i in fieldRanges.indices) if (fieldRanges[i] == null) l.add(i) }
        var keys = LongArray(1024)
        var n = 0
        val r = IntArray(4)
        fun collect(count: Int, rangeOf: (Int, IntArray) -> Boolean) {
            for (i in 0 until count) {
                if (!rangeOf(i, r)) continue
                for (x in r[0] until r[2]) for (y in r[1] until r[3]) {
                    if (n == keys.size) keys = keys.copyOf(n * 2)
                    keys[n++] = tileKey(x, y)
                }
            }
        }
        collect(objectCount, objRangeOf); collect(waters.size, waterRangeOf); collect(fields.size, fieldRangeOf)
        keys = keys.copyOf(n)
        keys.sort()
        var unique = 0
        for (k in 0 until n) if (k == 0 || keys[k] != keys[k - 1]) keys[unique++] = keys[k]
        tileKeys = keys.copyOf(unique)
        objIndex = TileIndex.build(tileKeys, objectCount, objRangeOf)
        waterIndex = TileIndex.build(tileKeys, waters.size, waterRangeOf)
        fieldIndex = TileIndex.build(tileKeys, fields.size, fieldRangeOf)
        return ArrayList<TilePos>(unique).also { l -> for (k in tileKeys) l.add(tileOf(k)) }.also { sortedTiles = it }
    }

    private fun rangeInto(range: TilesRange?, r: IntArray): Boolean {
        if (range == null) return false
        r[0] = range.beginX; r[1] = range.beginY; r[2] = range.endX; r[3] = range.endY
        return true
    }

    /** TileCachedRecastMeshManager::getMesh; null when nothing touches the tile. */
    fun makeMesh(tile: TilePos): RecastMesh? {
        if (sortedTiles == null) tiles()
        val pos = tileKeys.sortedIndexOf(tileKey(tile.x, tile.y))
        if (pos < 0) return null
        val builder = RecastMeshBuilder(settings.realTileBoundsWithBorder(tile))
        for (k in waterIndex.offsets[pos] until waterIndex.offsets[pos + 1]) waters[waterIndex.data[k]].let { builder.addWater(it.cellX, it.cellY, it.water) }
        for (i in infiniteWater) waters[i].let { builder.addWater(it.cellX, it.cellY, it.water) }
        for (k in fieldIndex.offsets[pos] until fieldIndex.offsets[pos + 1]) addField(builder, fields[fieldIndex.data[k]])
        for (i in infiniteFields) addField(builder, fields[i])
        for (k in objIndex.offsets[pos] until objIndex.offsets[pos + 1]) {
            val i = objIndex.data[k]
            val ot = ObjectTransform(floatArrayOf(objPlacement[7 * i], objPlacement[7 * i + 1], objPlacement[7 * i + 2]),
                floatArrayOf(objPlacement[7 * i + 3], objPlacement[7 * i + 4], objPlacement[7 * i + 5]), objPlacement[7 * i + 6])
            val source = sourceTable[objShape[i]]
            // The scaled copy of the model is made here, for this tile only, instead of being kept for every object
            val base = if (objArea[i].toInt() == AREA_NULL) source.avoid!! else source.collision!!
            builder.addObject(scaled(base, ot.scale), objectTransformOf(ot), objArea[i].toInt(), source, ot)
        }
        return builder.create()
    }

    private fun addField(builder: RecastMeshBuilder, f: PlacedHeightfield) {
        val s = f.surface
        if (s == null) builder.addHeightfieldPlane(f.cellX, f.cellY, f.cellSize, f.plane)
        else builder.addHeightfieldSurface(f.cellX, f.cellY, f.cellSize, s.heights, LAND_SIZE, s.minHeight, s.maxHeight)
    }
}

private fun tileKey(x: Int, y: Int) = (x.toLong() shl 32) + y.toLong()

private fun tileOf(key: Long): TilePos {
    val x = ((key + (1L shl 31)) shr 32).toInt()
    return TilePos(x, (key - (x.toLong() shl 32)).toInt())
}

/** The items touching each tile, packed: those of the tile at position p in the sorted tile keys are data[offsets[p] until offsets[p + 1]], in item order. */
private class TileIndex(val offsets: IntArray, val data: IntArray) {
    companion object {
        fun build(keys: LongArray, count: Int, rangeOf: (Int, IntArray) -> Boolean): TileIndex {
            val offsets = IntArray(keys.size + 1)
            val r = IntArray(4)
            for (pass in 0..1) {
                val fill = if (pass == 1) offsets.copyOf() else null
                var data = IntArray(0)
                if (pass == 1) data = IntArray(offsets[keys.size])
                for (i in 0 until count) {
                    if (!rangeOf(i, r)) continue
                    for (x in r[0] until r[2]) for (y in r[1] until r[3]) {
                        val p = keys.sortedIndexOf(tileKey(x, y))
                        if (pass == 0) offsets[p + 1]++ else data[fill!![p]++] = i
                    }
                }
                if (pass == 0) for (k in 0 until keys.size) offsets[k + 1] += offsets[k]
                else return TileIndex(offsets, data)
            }
            throw IllegalStateException()
        }
    }
}

/** Loads and caches the collision shapes of models, as Resource::BulletShapeManager does for the tool. */
internal class ShapeCache(private val vfs: Vfs, private val fs: ToolFs, private val log: (String) -> Unit) {
    private val cache = HashMap<String, NavShape?>()

    val size get() = cache.size
    private val existing = HashMap<String, Boolean>()
    private val actorPaths = HashMap<String, String>()

    fun exists(name: String): Boolean = existing.getOrPut(name) { try { vfs.resolve(name); true } catch (e: Exception) { false } }

    /** [correctActorModelPath] for [model], looked up once per model: most references share a few hundred models. */
    fun actorPath(model: String): String = actorPaths.getOrPut(model) { correctActorModelPath(model, ::exists) }

    private class Loaded(val shape: NavShape?, val error: String?)

    /** The bytes of one model; safe to call from several threads (it does not touch the virtual file system or the cache). */
    private fun read(model: String, src: org.alpha3.launcher.tools.openmw.Source): ByteArray {
        val bytes = ByteArray(src.size.toInt())
        val r = fs.openRead(src.path)
        try {
            var done = 0
            while (done < bytes.size) {
                val n = r.read(src.offset + done, bytes, done, bytes.size - done)
                if (n <= 0) throw NavMeshException("unexpected end of $model (${src.path} at ${src.offset}, ${src.size} bytes, $done read)")
                done += n
            }
        } finally {
            r.close()
        }
        return bytes
    }

    private fun parse(model: String, bytes: ByteArray): NavShape? = loadNavShape("meshes/$model", bytes).takeIf { it.collision != null }

    private fun load(model: String, src: org.alpha3.launcher.tools.openmw.Source): Loaded = try {
        Loaded(parse(model, read(model, src)), null)
    } catch (e: Exception) {
        Loaded(null, "${e.message} (${src.path}${if (src.archive) " in an archive" else ""})")
    }

    /** Loads the models in [models] that are not cached yet on every core; the results are cached in the calling thread. */
    suspend fun prefetch(models: Collection<String>) {
        val todo = models.filter { it !in cache }.distinct()
        if (todo.isEmpty()) return
        // The virtual file system is not thread safe: lookups stay here, only the reading and parsing runs in parallel
        val sources = todo.map { m -> m to try { vfs.resolve("meshes/$m") to null } catch (e: Exception) { null to (e.message ?: e.toString()) } }
        val loaded = coroutineScope {
            sources.map { (m, found) ->
                // Reading waits on storage, so many reads are in flight at once; parsing needs a core
                async(Dispatchers.IO) {
                    val src = found.first
                    if (src == null) Loaded(null, found.second)
                    else try {
                        val bytes = read(m, src)
                        withContext(Dispatchers.Default) { Loaded(parse(m, bytes), null) }
                    } catch (e: Exception) {
                        Loaded(null, "${e.message} (${src.path}${if (src.archive) " in an archive" else ""})")
                    }
                }
            }.awaitAll()
        }
        for ((k, m) in todo.withIndex()) {
            val l = loaded[k]
            if (l.error != null) log("Failed to load model \"$m\": ${l.error}")
            cache[m] = l.shape
        }
    }

    fun get(model: String): NavShape? = cache.getOrPut(model) {
        try {
            val src = vfs.resolve("meshes/$model")
            val l = load(model, src)
            if (l.error != null) log("Failed to load model \"$model\": ${l.error}")
            l.shape
        } catch (e: Exception) {
            log("Failed to load model \"$model\": ${e.message}")
            null
        }
    }
}

/** Misc::ResourceHelpers::correctActorModelPath */
private fun correctActorModelPath(model: String, exists: (String) -> Boolean): String {
    val slash = model.lastIndexOf('/')
    val x = if (slash >= 0) model.substring(0, slash + 1) + "x" + model.substring(slash + 1) else "x$model"
    val kf = if (x.endsWith(".nif")) x.substring(0, x.length - 4) + ".kf" else x
    return if (exists(kf)) x else model
}

private fun scaled(shape: CompoundShape, s: Float): CompoundShape {
    if (s == 1f) return shape // nothing to scale, and nothing mutates a shape after it is made
    val c = shape.duplicate()
    c.applyScaling(V3(s.toDouble(), s.toDouble(), s.toDouble()))
    return c
}

private fun cellAabb(x: Int, y: Int, minH: Float, maxH: Float): Aabb {
    val s = LAND_REAL_SIZE.toDouble()
    return Aabb(V3(x * s, y * s, minH.toDouble()), V3((x + 1) * s, (y + 1) * s, maxH.toDouble()))
}

private class CellInput(val heights: LandHeights?, val refs: List<CellRefData>)

/** A model at one scale; the scaled collision and avoid shapes are made once and shared by every object that uses them. */
private data class ScaleKey(val source: NavShape, val scale: Float)

private class ScaledShapes(val collision: CompoundShape, val avoid: CompoundShape?)

private class BuiltObject(
    val collision: CompoundShape, val avoid: CompoundShape?, val transform: Transform, val objectTransform: ObjectTransform,
    val source: NavShape, val collisionAabb: Aabb, val avoidAabb: Aabb?,
)

/** The model of a reference, or null when it has none; the same lookups as the native tool. */
private fun modelOf(ref: CellRefData, esm: EsmData, shapes: ShapeCache): String? {
    val rec = esm.models[ref.id] ?: return null
    val model = rec.model
    if (model.isEmpty()) return null
    return if (rec.type != REC_STAT) shapes.actorPath(model) else model
}

/**
 * gatherWorldspaceData for one worldspace's cells; [onCell] receives the number of cells processed. Cells are read, their
 * models loaded and their objects built on every core, a batch at a time; the results are added in cell order, so the
 * outcome is the same as one thread would give.
 */
internal suspend fun gatherWorldspace(
    cells: List<EsmCell>,
    esm: EsmData,
    fs: ToolFs,
    codec: TextCodec,
    shapes: ShapeCache,
    settings: RecastSettings,
    threads: Int,
    log: (String) -> Unit,
    onCell: (Int) -> Unit,
): WorldspaceData {
    val data = WorldspaceData(settings)
    val landsByCell = esm.lands.associateBy { cellKey(it.x, it.y) }
    var done = 0
    var from = 0
    while (from < cells.size) {
        // A batch holds the references and shapes of its cells; with little memory left it is a lot smaller
        val wanted = maxOf(1, threads) * 32
        val batchSize = if (freeMemoryBytes() < lowMemoryBytes()) maxOf(4, wanted / 8) else wanted
        val batch = cells.subList(from, minOf(cells.size, from + batchSize))
        from += batch.size
        coroutineContext.ensureActive()
        val t0 = TimeSource.Monotonic.markNow()
        // The plugins are read by many threads at once, each with its own file handle
        val inputs = coroutineScope {
            batch.map { cell ->
                async(Dispatchers.Default) {
                    try {
                        val land = if (cell.exterior) landsByCell[cellKey(cell.x, cell.y)] else null
                        CellInput(land?.let { loadLandHeights(it, esm, fs) }, loadCellRefs(cell, esm, fs, codec))
                    } catch (e: Exception) {
                        throw NavMeshException("cell ${cell.description()}: ${e.message}")
                    }
                }
            }.awaitAll()
        }
        val tRead = t0.elapsedNow()
        val models = inputs.map { input -> input.refs.map { modelOf(it, esm, shapes) } }
        val tResolve = t0.elapsedNow()
        shapes.prefetch(models.flatMap { it.filterNotNull() })
        // Scaling copies a shape, so each model and scale is done once, not once per object; thousands of objects share it
        val scaledShapes = HashMap<ScaleKey, ScaledShapes>() // only for this batch; the world keeps the models, not the copies
        val missing = LinkedHashSet<ScaleKey>()
        for ((c, input) in inputs.withIndex()) for ((k, ref) in input.refs.withIndex()) {
            val nav = models[c][k]?.let { shapes.get(it) } ?: continue
            if (nav.visualCollision != 0) continue
            val key = ScaleKey(nav, ref.scale)
            if (key !in scaledShapes) missing.add(key)
        }
        if (missing.isNotEmpty()) {
            val list = missing.toList()
            val made = coroutineScope {
                list.map { key ->
                    async(Dispatchers.Default) { ScaledShapes(scaled(key.source.collision!!, key.scale), key.source.avoid?.let { scaled(it, key.scale) }) }
                }.awaitAll()
            }
            for ((k, key) in list.withIndex()) scaledShapes[key] = made[k]
        }
        val tShapes = t0.elapsedNow()
        val built = coroutineScope {
            inputs.indices.map { c ->
                async(Dispatchers.Default) {
                    val out = ArrayList<BuiltObject>()
                    for ((r, ref) in inputs[c].refs.withIndex()) {
                        val model = models[c][r] ?: continue
                        val nav = shapes.get(model) ?: continue
                        if (nav.visualCollision != 0) continue
                        val ot = ObjectTransform(ref.pos.copyOfRange(0, 3), ref.pos.copyOfRange(3, 6), ref.scale)
                        val tf = objectTransformOf(ot)
                        val shared = scaledShapes.getValue(ScaleKey(nav, ref.scale))
                        val collision = shared.collision
                        val avoid = shared.avoid
                        out.add(BuiltObject(collision, avoid, tf, ot, nav, collision.getAabb(tf), avoid?.getAabb(tf)))
                    }
                    out
                }
            }.awaitAll()
        }
        val tBuilt = t0.elapsedNow()
        for ((c, cell) in batch.withIndex()) {
            val heights = inputs[c].heights
            if (cell.exterior) {
                val min = heights?.minHeight ?: LAND_DEFAULT_HEIGHT.toFloat()
                val max = heights?.maxHeight ?: LAND_DEFAULT_HEIGHT.toFloat()
                data.mergeAabb(cellAabb(cell.x, cell.y, min, max))
                data.addHeightfield(cell.x, cell.y, LAND_REAL_SIZE, LAND_DEFAULT_HEIGHT.toFloat(), heights)
                data.addWater(cell.x, cell.y, LAND_REAL_SIZE, -1f)
            } else if (cell.flags and CELL_HAS_WATER != 0) {
                data.addWater(cell.x, cell.y, Int.MAX_VALUE, cell.water)
            }
            var count = 0
            for (o in built[c]) {
                data.mergeAabb(o.collisionAabb)
                o.avoidAabb?.let { data.mergeAabb(it) }
                data.addObject(o.collision, o.transform, AREA_GROUND, o.source, o.objectTransform)
                if (o.avoid != null) data.addObject(o.avoid, o.transform, AREA_NULL, o.source, o.objectTransform)
                count++
            }
            done++
            log("Processed ${if (cell.exterior) "exterior" else "interior"} cell ($done/${cells.size}) ${cell.description()} with $count objects")
            onCell(done)
        }
        log("Batch of ${batch.size} cells: read ${tRead.inWholeMilliseconds} ms, model lookup ${(tResolve - tRead).inWholeMilliseconds} ms, " +
            "shapes ${(tShapes - tResolve).inWholeMilliseconds} ms, objects ${(tBuilt - tShapes).inWholeMilliseconds} ms, " +
            "added ${(t0.elapsedNow() - tBuilt).inWholeMilliseconds} ms; heap ${(maxMemoryBytes() - freeMemoryBytes()) shr 20}/${maxMemoryBytes() shr 20} MB, " +
            "${shapes.size} models, ${data.objectTotal} objects, ${data.heightfieldTotal} heightfields")
    }
    return data
}
