// SPDX-License-Identifier: GPL-3.0-or-later
// Data types and settings helpers of OpenMW's navmesh generation (components/detournavigator).
package org.alpha3.launcher.tools.navmesh

import kotlin.jvm.JvmField
import kotlin.math.floor

/** Recast area ids as OpenMW's AreaType. */
internal const val AREA_NULL = 0
internal const val AREA_WATER = 1
internal const val AREA_DOOR = 2
internal const val AREA_PATHGRID = 3
internal const val AREA_GROUND = 63

internal const val FLAG_NONE = 0
internal const val FLAG_WALK = 1
internal const val FLAG_SWIM = 2
internal const val FLAG_OPEN_DOOR = 4
internal const val FLAG_USE_PATHGRID = 8

internal const val NAV_MESH_FORMAT_VERSION = 2

/** DetourNavigator::RecastSettings with OpenMW's default values. */
class RecastSettings(
    val cellHeight: Float = 0.2f,
    val cellSize: Float = 0.2f,
    val detailSampleDist: Float = 6.0f,
    val detailSampleMaxError: Float = 1.0f,
    val maxClimb: Float = 34.0f,
    val maxSimplificationError: Float = 1.3f,
    val maxSlope: Float = 46.0f,
    val recastScaleFactor: Float = 0.029411764705882353f,
    val swimHeightScale: Float = 0f,
    val borderSize: Int = 16,
    val maxEdgeLen: Int = 12,
    val maxVertsPerPoly: Int = 6,
    val regionMergeArea: Int = 400,
    val regionMinArea: Int = 64,
    val tileSize: Int = 128,
) {
    fun withSwimHeightScale(v: Float) = RecastSettings(
        cellHeight, cellSize, detailSampleDist, detailSampleMaxError, maxClimb, maxSimplificationError, maxSlope,
        recastScaleFactor, v, borderSize, maxEdgeLen, maxVertsPerPoly, regionMergeArea, regionMinArea, tileSize,
    )
}

/** An agent shape: 0 = Aabb, 1 = RotatingBox, 2 = Cylinder. */
class AgentBounds(
    val shapeType: Int = 0,
    val halfExtents: FloatArray = floatArrayOf(29.27999496459961f, 28.479997634887695f, 66.5f),
)

internal class TileBounds(@JvmField val minX: Float, @JvmField val minY: Float, @JvmField val maxX: Float, @JvmField val maxY: Float)

internal data class TilePos(val x: Int, val y: Int) : Comparable<TilePos> {
    override fun compareTo(other: TilePos): Int = if (x != other.x) x.compareTo(other.x) else y.compareTo(other.y)
}

/** Half open range of tiles: begin inclusive, end exclusive. */
internal class TilesRange(@JvmField val beginX: Int, @JvmField val beginY: Int, @JvmField val endX: Int, @JvmField val endY: Int) {
    fun contains(x: Int, y: Int) = x >= beginX && x < endX && y >= beginY && y < endY
}

internal fun getIntersection(a: TileBounds, b: TileBounds): TileBounds? {
    val minX = maxOf(a.minX, b.minX)
    val maxX = minOf(a.maxX, b.maxX)
    if (minX > maxX) return null
    val minY = maxOf(a.minY, b.minY)
    val maxY = minOf(a.maxY, b.maxY)
    if (minY > maxY) return null
    return TileBounds(minX, minY, maxX, maxY)
}

internal fun maxCellTileBounds(cellX: Int, cellY: Int, size: Int): TileBounds {
    val s = size.toFloat()
    return TileBounds(cellX.toFloat() * s, cellY.toFloat() * s, (cellX + 1).toFloat() * s, (cellY + 1).toFloat() * s)
}

internal fun RecastSettings.tileSizeNav(): Float = tileSize.toFloat() * cellSize

internal fun RecastSettings.borderSizeNav(): Float = borderSize.toFloat() * cellSize

internal fun RecastSettings.tilePosition(position: Float): Int {
    val v = floor(position / tileSizeNav())
    if (v < Int.MIN_VALUE.toFloat()) return Int.MIN_VALUE
    if (v > (Int.MAX_VALUE - 1).toFloat()) return Int.MAX_VALUE - 1
    return v.toInt()
}

/** Tile bounds in real coordinates, including the border. */
internal fun RecastSettings.realTileBoundsWithBorder(tile: TilePos): TileBounds {
    val size = tileSizeNav()
    val border = borderSizeNav()
    val minX = tile.x.toFloat() * size - border
    val minY = tile.y.toFloat() * size - border
    val maxX = (tile.x + 1).toFloat() * size + border
    val maxY = (tile.y + 1).toFloat() * size + border
    return TileBounds(minX / recastScaleFactor, minY / recastScaleFactor, maxX / recastScaleFactor, maxY / recastScaleFactor)
}

internal fun RecastSettings.tilesRange(minX: Float, minY: Float, maxX: Float, maxY: Float): TilesRange {
    val border = borderSizeNav()
    val nMinX = minX * recastScaleFactor - border
    val nMinY = minY * recastScaleFactor - border
    val nMaxX = maxX * recastScaleFactor + border
    val nMaxY = maxY * recastScaleFactor + border
    var minTx = tilePosition(nMinX)
    var minTy = tilePosition(nMinY)
    var maxTx = tilePosition(nMaxX)
    var maxTy = tilePosition(nMaxY)
    if (minTx > maxTx) { val t = minTx; minTx = maxTx; maxTx = t }
    if (minTy > maxTy) { val t = minTy; minTy = maxTy; maxTy = t }
    return TilesRange(minTx, minTy, maxTx + 1, maxTy + 1)
}

/** The tiles a square cell (water or terrain) centred on [shift] covers. */
internal fun RecastSettings.tilesRangeForCell(cellSize: Int, shiftX: Double, shiftY: Double): TilesRange {
    val half = cellSize / 2
    val aMinX = -half.toDouble() + shiftX
    val aMinY = -half.toDouble() + shiftY
    val aMaxX = half.toDouble() + shiftX
    val aMaxY = half.toDouble() + shiftY
    val minX = minOf(aMinX, aMaxX)
    val minY = minOf(aMinY, aMaxY)
    val maxX = maxOf(minX, aMaxX)
    val maxY = maxOf(minY, aMaxY)
    return tilesRange(minX.toFloat(), minY.toFloat(), maxX.toFloat(), maxY.toFloat())
}

internal fun RecastSettings.tilesRangeOfShape(shape: BtShape, t: Transform): TilesRange {
    val a = shape.getAabb(t)
    return tilesRange(a.min.x.toFloat(), a.min.y.toFloat(), a.max.x.toFloat(), a.max.y.toFloat())
}

internal class Water(@JvmField val cellSize: Int, @JvmField val level: Float)

internal class CellWater(@JvmField val cellX: Int, @JvmField val cellY: Int, @JvmField val water: Water)

internal class HeightfieldData(
    @JvmField val cellX: Int, @JvmField val cellY: Int, @JvmField val cellSize: Int, @JvmField val length: Int,
    @JvmField val minHeight: Float, @JvmField val maxHeight: Float, @JvmField val heights: FloatArray,
    @JvmField val originalSize: Long, @JvmField val minX: Int, @JvmField val minY: Int,
)

internal class FlatHeightfield(@JvmField val cellX: Int, @JvmField val cellY: Int, @JvmField val cellSize: Int, @JvmField val height: Float)

/** ESM::Position plus the reference's scale. */
internal class ObjectTransform(@JvmField val pos: FloatArray, @JvmField val rot: FloatArray, @JvmField val scale: Float)

/** A loaded collision shape: what the database remembers about it is its model path, area and file hash. */
internal class NavShape(
    @JvmField val fileName: String,
    @JvmField val fileHash: ByteArray,
    @JvmField val collision: CompoundShape?,
    @JvmField val avoid: CompoundShape?,
    /** Resource::VisualCollisionType: 0 none, 1 default, 2 camera. */
    @JvmField val visualCollision: Int,
)

internal class MeshSource(@JvmField val shape: NavShape, @JvmField val transform: ObjectTransform, @JvmField val areaType: Int)

internal class Mesh(@JvmField val indices: IntArray, @JvmField val vertices: FloatArray, @JvmField val areaTypes: IntArray) {
    val verticesCount get() = vertices.size / 3
}

internal class RecastMesh(
    @JvmField val mesh: Mesh,
    @JvmField val water: List<CellWater>,
    @JvmField val heightfields: List<HeightfieldData>,
    @JvmField val flatHeightfields: List<FlatHeightfield>,
    @JvmField val sources: List<MeshSource>,
) {
    val isEmpty get() = mesh.indices.isEmpty() && water.isEmpty() && heightfields.isEmpty() && flatHeightfields.isEmpty()
}
