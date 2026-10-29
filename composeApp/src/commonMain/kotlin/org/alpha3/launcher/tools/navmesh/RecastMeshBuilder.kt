// SPDX-License-Identifier: GPL-3.0-or-later
// Port of OpenMW's components/detournavigator/recastmeshbuilder.cpp.
package org.alpha3.launcher.tools.navmesh

import kotlin.math.floor

internal class RecastMeshTriangle(@JvmField val areaType: Int, @JvmField val v: FloatArray)

private fun compareVertex(a: FloatArray, ao: Int, b: FloatArray, bo: Int): Int {
    for (k in 0 until 3) {
        val x = a[ao + k]
        val y = b[bo + k]
        if (x < y) return -1
        if (x > y) return 1
    }
    return 0
}

private val triangleOrder = Comparator<RecastMeshTriangle> { l, r ->
    if (l.areaType != r.areaType) return@Comparator l.areaType.compareTo(r.areaType)
    for (k in 0 until 9) {
        val x = l.v[k]
        val y = r.v[k]
        if (x < y) return@Comparator -1
        if (x > y) return@Comparator 1
    }
    0
}

/** makeMesh(triangles, shift): shared vertices, sorted, with [shift] added in single precision. */
internal fun makeMesh(triangles: List<RecastMeshTriangle>, shiftX: Float = 0f, shiftY: Float = 0f, shiftZ: Float = 0f): Mesh {
    val all = FloatArray(triangles.size * 9)
    for ((i, t) in triangles.withIndex()) t.v.copyInto(all, i * 9)
    val order = (0 until triangles.size * 3).sortedWith { a, b -> compareVertex(all, a * 3, all, b * 3) }
    // unique vertices, in order
    val unique = ArrayList<Int>(order.size)
    for (idx in order) if (unique.isEmpty() || compareVertex(all, unique.last() * 3, all, idx * 3) != 0) unique.add(idx)
    val indices = IntArray(triangles.size * 3)
    for (i in indices.indices) {
        var lo = 0
        var hi = unique.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compareVertex(all, unique[mid] * 3, all, i * 3) < 0) lo = mid + 1 else hi = mid
        }
        indices[i] = lo
    }
    val vertices = FloatArray(unique.size * 3)
    for ((i, u) in unique.withIndex()) {
        vertices[i * 3] = all[u * 3] + shiftX
        vertices[i * 3 + 1] = all[u * 3 + 1] + shiftY
        vertices[i * 3 + 2] = all[u * 3 + 2] + shiftZ
    }
    return Mesh(indices, vertices, IntArray(triangles.size) { triangles[it].areaType })
}

private fun triangleOf(areaType: Int, p: DoubleArray, reversed: Boolean): RecastMeshTriangle {
    val v = FloatArray(9)
    for (k in 0 until 3) {
        val src = if (reversed) 2 - k else k
        v[k * 3] = p[src * 3].toFloat()
        v[k * 3 + 1] = p[src * 3 + 1].toFloat()
        v[k * 3 + 2] = p[src * 3 + 2].toFloat()
    }
    return RecastMeshTriangle(areaType, v)
}

/** makeMesh(const Heightfield&): the triangles of a terrain block, via btHeightfieldTerrainShape. */
internal fun makeMesh(h: HeightfieldData): Mesh {
    val width = h.heights.size / h.length
    val length = h.length
    val scale = h.cellSize.toFloat() / (h.originalSize - 1).toFloat()
    val sx = scale.toDouble()
    val sy = scale.toDouble()
    val sz = 1.0
    val minH = h.minHeight.toDouble()
    val maxH = h.maxHeight.toDouble()
    val bWidth = (width - 1).toDouble()
    val bLength = (length - 1).toDouble()
    val originZ = 0.5 * (minH + maxH)
    // getAabb(identity): +-extent
    val hx = (bWidth - 0.0) * sx * 0.5
    val hy = (bLength - 0.0) * sy * 0.5
    val hz = (maxH - minH) * sz * 0.5
    val aabbMinZ = -hz
    val aabbMaxZ = hz

    fun vertex(x: Int, y: Int, out: DoubleArray, at: Int) {
        val height = h.heights[y * width + x].toDouble()
        out[at] = (-bWidth / 2.0 + x) * sx
        out[at + 1] = (-bLength / 2.0 + y) * sy
        out[at + 2] = (height - originZ) * sz
    }

    val triangles = ArrayList<RecastMeshTriangle>()
    val p = DoubleArray(9)
    val startX = 0
    val endX = width - 1
    val startJ = 0
    val endJ = length - 1
    fun overlaps(): Boolean {
        val lo = minOf(p[2], p[5], p[8])
        val hi = maxOf(p[2], p[5], p[8])
        return !(lo > aabbMaxZ || hi < aabbMinZ)
    }
    for (j in startJ until endJ) {
        for (x in startX until endX) {
            vertex(x, j, p, 0)
            vertex(x, j + 1, p, 3)
            vertex(x + 1, j, p, 6)
            if (overlaps()) triangles.add(triangleOf(AREA_GROUND, p, false))
            // second triangle: (x + 1, j), (x, j + 1), (x + 1, j + 1)
            p[0] = p[6]; p[1] = p[7]; p[2] = p[8]
            vertex(x + 1, j + 1, p, 6)
            if (overlaps()) triangles.add(triangleOf(AREA_GROUND, p, false))
        }
    }
    val aabbShiftX = hx
    val aabbShiftY = hy
    val tileShiftX = h.minX.toFloat() * scale
    val tileShiftY = h.minY.toFloat() * scale
    val localShiftX = aabbShiftX.toFloat() + tileShiftX
    val localShiftY = aabbShiftY.toFloat() + tileShiftY
    val cellSize = h.cellSize.toFloat()
    val cellShiftX = h.cellX.toFloat() * cellSize
    val cellShiftY = h.cellY.toFloat() * cellSize
    val cellShiftZ = (h.minHeight + h.maxHeight) * 0.5f
    return makeMesh(triangles, cellShiftX + localShiftX, cellShiftY + localShiftY, cellShiftZ + 0f)
}

private fun testTriangleAgainstAabb(p: DoubleArray, minX: Double, minY: Double, maxX: Double, maxY: Double): Boolean {
    if (minOf(minOf(p[0], p[3]), p[6]) > maxX) return false
    if (maxOf(maxOf(p[0], p[3]), p[6]) < minX) return false
    if (minOf(minOf(p[1], p[4]), p[7]) > maxY) return false
    if (maxOf(maxOf(p[1], p[4]), p[7]) < minY) return false
    return true
}

/** std::round: halves go away from zero. */
private fun roundHalfAway(x: Float): Float {
    val a = if (x < 0f) -x else x
    val f = floor(a)
    val r = if (a - f >= 0.5f) f + 1f else f
    return if (x < 0f) -r else r
}

private fun heightfieldScale(cellSize: Int, dataSize: Int) = cellSize.toFloat() / (dataSize - 1).toFloat()

internal class RecastMeshBuilder(private val bounds: TileBounds) {
    private val triangles = ArrayList<RecastMeshTriangle>()
    private val water = ArrayList<CellWater>()
    private val heightfields = ArrayList<HeightfieldData>()
    private val flatHeightfields = ArrayList<FlatHeightfield>()
    private val sources = ArrayList<MeshSource>()

    fun addObject(shape: BtShape, transform: Transform, areaType: Int, source: NavShape, objectTransform: ObjectTransform) {
        addShape(shape, transform, areaType)
        sources.add(MeshSource(source, objectTransform, areaType))
    }

    private fun addShape(shape: BtShape, transform: Transform, areaType: Int) {
        when (shape) {
            is CompoundShape -> for (c in shape.children) addShape(c.shape, transform * c.transform, areaType)
            is ScaledTriangleMeshShape -> {
                val minX = bounds.minX.toDouble()
                val minY = bounds.minY.toDouble()
                val maxX = bounds.maxX.toDouble()
                val maxY = bounds.maxY.toDouble()
                val out = DoubleArray(9)
                shape.forEachTriangle { tri ->
                    for (k in 0 until 3) {
                        val r = transform(V3(tri[k * 3], tri[k * 3 + 1], tri[k * 3 + 2]))
                        out[k * 3] = r.x; out[k * 3 + 1] = r.y; out[k * 3 + 2] = r.z
                    }
                    if (testTriangleAgainstAabb(out, minX, minY, maxX, maxY)) triangles.add(triangleOf(areaType, out, true))
                }
            }
            else -> throw IllegalArgumentException("Unsupported shape type")
        }
    }

    fun addWater(cellX: Int, cellY: Int, w: Water) {
        water.add(CellWater(cellX, cellY, w))
    }

    fun addHeightfieldPlane(cellX: Int, cellY: Int, cellSize: Int, height: Float) {
        if (getIntersection(bounds, maxCellTileBounds(cellX, cellY, cellSize)) != null)
            flatHeightfields.add(FlatHeightfield(cellX, cellY, cellSize, height))
    }

    fun addHeightfieldSurface(cellX: Int, cellY: Int, cellSize: Int, heights: FloatArray, size: Int, minHeight: Float, maxHeight: Float) {
        val intersection = getIntersection(bounds, maxCellTileBounds(cellX, cellY, cellSize)) ?: return
        val shiftX = ((cellX + 0.5f) * cellSize).toDouble().toFloat()
        val shiftY = ((cellY + 0.5f) * cellSize).toDouble().toFloat()
        val stepSize = heightfieldScale(cellSize, size)
        val halfCellSize = cellSize / 2
        fun local(v: Float, offset: Float) = (v - offset + halfCellSize) / stepSize
        fun index(v: Float, add: Int) = (v.toInt() + add).coerceIn(0, size)
        val minX = index(roundHalfAway(local(intersection.minX, shiftX)), -1)
        val minY = index(roundHalfAway(local(intersection.minY, shiftY)), -1)
        val maxX = index(roundHalfAway(local(intersection.maxX, shiftX)), 1)
        val maxY = index(roundHalfAway(local(intersection.maxY, shiftY)), 1)
        val endX = minOf(maxX + 1, size)
        val endY = minOf(maxY + 1, size)
        val sliceSize = (endX - minX) * (endY - minY)
        if (sliceSize <= 0) return
        val tile = FloatArray(sliceSize)
        var k = 0
        for (y in minY until endY) for (x in minX until endX) tile[k++] = heights[x + y * size]
        heightfields.add(HeightfieldData(cellX, cellY, cellSize, (endY - minY) and 0xff, minHeight, maxHeight, tile, size.toLong(), minX and 0xff, minY and 0xff))
    }

    fun create(): RecastMesh {
        val valid = triangles.filter { t -> t.v.none { it.isNaN() } }
        val sorted = valid.sortedWith(triangleOrder)
        water.sortWith { a, b ->
            when {
                a.cellX != b.cellX -> a.cellX.compareTo(b.cellX)
                a.cellY != b.cellY -> a.cellY.compareTo(b.cellY)
                a.water.cellSize != b.water.cellSize -> a.water.cellSize.compareTo(b.water.cellSize)
                else -> a.water.level.compareTo(b.water.level)
            }
        }
        heightfields.sortWith { a, b ->
            when {
                a.cellX != b.cellX -> a.cellX.compareTo(b.cellX)
                a.cellY != b.cellY -> a.cellY.compareTo(b.cellY)
                a.cellSize != b.cellSize -> a.cellSize.compareTo(b.cellSize)
                a.length != b.length -> a.length.compareTo(b.length)
                a.minHeight != b.minHeight -> a.minHeight.compareTo(b.minHeight)
                a.maxHeight != b.maxHeight -> a.maxHeight.compareTo(b.maxHeight)
                else -> {
                    var r = 0
                    val n = minOf(a.heights.size, b.heights.size)
                    for (i in 0 until n) if (a.heights[i] != b.heights[i]) { r = a.heights[i].compareTo(b.heights[i]); break }
                    if (r == 0) r = a.heights.size.compareTo(b.heights.size)
                    if (r == 0) r = a.originalSize.compareTo(b.originalSize)
                    if (r == 0) r = a.minX.compareTo(b.minX)
                    if (r == 0) r = a.minY.compareTo(b.minY)
                    r
                }
            }
        }
        flatHeightfields.sortWith { a, b ->
            when {
                a.cellX != b.cellX -> a.cellX.compareTo(b.cellX)
                a.cellY != b.cellY -> a.cellY.compareTo(b.cellY)
                a.cellSize != b.cellSize -> a.cellSize.compareTo(b.cellSize)
                else -> a.height.compareTo(b.height)
            }
        }
        return RecastMesh(makeMesh(sorted), water, heightfields, flatHeightfields, sources)
    }
}
