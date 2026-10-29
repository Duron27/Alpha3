// SPDX-License-Identifier: GPL-3.0-or-later
// Port of prepareNavMeshTileData from OpenMW's components/detournavigator/makenavmesh.cpp.
package org.alpha3.launcher.tools.navmesh

import org.alpha3.launcher.tools.navmesh.recast.*
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

private const val WALKABLE_RADIUS_UPPER_LIMIT = 255
private const val MAX_VERTEX_COORDINATE = 4194304f // 1 shl 22

private fun agentHeight(a: AgentBounds) = 2.0f * a.halfExtents[2]

private fun agentRadius(a: AgentBounds): Float = when (a.shapeType) {
    0 -> max(a.halfExtents[0], a.halfExtents[1]) * sqrt(2.0f)
    1 -> a.halfExtents[0]
    else -> max(a.halfExtents[0], a.halfExtents[1])
}

private fun height(s: RecastSettings, a: AgentBounds) = agentHeight(a) * s.recastScaleFactor
private fun maxClimb(s: RecastSettings) = s.maxClimb * s.recastScaleFactor
private fun radius(s: RecastSettings, a: AgentBounds) = agentRadius(a) * s.recastScaleFactor

private fun swimLevel(s: RecastSettings, waterLevel: Float, halfZ: Float) = waterLevel - s.swimHeightScale * halfZ - halfZ

internal fun walkableHeight(s: RecastSettings, a: AgentBounds) = ceil(height(s, a) / s.cellHeight).toInt()
internal fun walkableRadius(s: RecastSettings, a: AgentBounds) = ceil(radius(s, a) / s.cellSize).toInt()

/** std::round on a float: halves go away from zero. */
private fun roundAway(x: Float): Float {
    val a = if (x < 0f) -x else x
    val f = floor(a)
    val r = if (a - f >= 0.5f) f + 1f else f
    return if (x < 0f) -r else r
}

internal fun isSupportedAgentBounds(s: RecastSettings, a: AgentBounds) =
    walkableHeight(s, a) >= 3 && walkableRadius(s, a).let { 0 < it && it < WALKABLE_RADIUS_UPPER_LIMIT }

private class Params(s: RecastSettings, a: AgentBounds) {
    val walkableHeight = walkableHeight(s, a)
    val walkableClimb = floor(maxClimb(s) / s.cellHeight).toInt()
    val walkableRadius = walkableRadius(s, a)
    val maxEdgeLen = roundAway(s.maxEdgeLen.toFloat() / s.cellSize).toInt()
    val sampleDist = if (s.detailSampleDist < 0.9f) 0f else s.cellSize * s.detailSampleDist
    val sampleMaxError = s.cellHeight * s.detailSampleMaxError
}

private fun supported(v: Float) = -MAX_VERTEX_COORDINATE < v && v < MAX_VERTEX_COORDINATE

private fun rasterizeMesh(mesh: Mesh, s: RecastSettings, p: Params, solid: Heightfield): Boolean {
    val areas = mesh.areaTypes.copyOf()
    val vertices = mesh.vertices.copyOf()
    var i = 0
    while (i < vertices.size) {
        for (j in 0 until 3) {
            val c = vertices[i + j] * s.recastScaleFactor
            if (!supported(c)) return false
            vertices[i + j] = c
        }
        val t = vertices[i + 1]; vertices[i + 1] = vertices[i + 2]; vertices[i + 2] = t
        i += 3
    }
    rcClearUnwalkableTriangles(s.maxSlope, vertices, mesh.indices, areas.size, areas)
    rcRasterizeTriangles(vertices, mesh.indices, areas, areas.size, solid, p.walkableClimb)
    return true
}

private val RECT_INDICES = intArrayOf(0, 1, 2, 0, 2, 3)

private fun rasterizeRectangle(b: TileBounds, height: Float, areaType: Int, p: Params, solid: Heightfield): Boolean {
    val vertices = floatArrayOf(
        b.minX, height, b.minY,
        b.minX, height, b.maxY,
        b.maxX, height, b.maxY,
        b.maxX, height, b.minY,
    )
    if (!vertices.all { supported(it) }) return false
    rcRasterizeFlatRectangle(b.minX, b.minY, b.maxX, b.maxY, height, areaType, solid, p.walkableClimb)
    return true
}

private fun scaled(b: TileBounds, f: Float) = TileBounds(b.minX * f, b.minY * f, b.maxX * f, b.maxY * f)

private fun boundsByZ(mesh: RecastMesh, halfZ: Float, s: RecastSettings): Pair<Float, Float> {
    var minZ = 0f
    var maxZ = 0f
    val v = mesh.mesh.vertices
    var i = 0
    while (i < v.size) {
        minZ = minOf(minZ, v[i + 2])
        maxZ = maxOf(maxZ, v[i + 2])
        i += 3
    }
    for (w in mesh.water) {
        val level = swimLevel(s, w.water.level, halfZ)
        minZ = minOf(minZ, level)
        maxZ = maxOf(maxZ, level)
    }
    for (h in mesh.heightfields) {
        if (h.heights.isEmpty()) continue
        var lo = h.heights[0]
        var hi = h.heights[0]
        for (x in h.heights) { if (x < lo) lo = x; if (x > hi) hi = x }
        minZ = minOf(minZ, lo)
        maxZ = maxOf(maxZ, hi)
    }
    for (h in mesh.flatHeightfields) {
        minZ = minOf(minZ, h.height)
        maxZ = maxOf(maxZ, h.height)
    }
    return minZ to maxZ
}

/** Builds the navmesh data for one tile; null if the tile has nothing to walk on or cannot be built. */
/** Nanoseconds spent in each step of one tile; [names] says which. */
internal class StageTimes {
    val ns = LongArray(names.size)
    companion object { val names = listOf("rasterize", "filter", "compact+erode", "distance", "regions", "contours", "polymesh", "detail") }
}

internal fun prepareNavMeshTileData(mesh: RecastMesh, tile: TilePos, agent: AgentBounds, s: RecastSettings, times: StageTimes? = null): PreparedNavMeshData? {
    var mark = kotlin.time.TimeSource.Monotonic.markNow()
    fun stage(i: Int) { if (times != null) { times.ns[i] += mark.elapsedNow().inWholeNanoseconds; mark = kotlin.time.TimeSource.Monotonic.markNow() } }
    val halfZ = agent.halfExtents[2]
    val (minZ, maxZ) = boundsByZ(mesh, halfZ, s)

    val size = s.tileSize + s.borderSize * 2
    if (size < 0 || s.cellHeight <= 0f || s.cellSize <= 0f) return null
    val halfBoundsSize = size * s.cellSize * 0.5f
    val shiftX = (tile.x + 0.5f) * s.tileSizeNav()
    val shiftY = (tile.y + 0.5f) * s.tileSizeNav()
    val bmin = floatArrayOf(shiftX - halfBoundsSize, minZ * s.recastScaleFactor, shiftY - halfBoundsSize)
    val bmax = floatArrayOf(shiftX + halfBoundsSize, maxZ * s.recastScaleFactor, shiftY + halfBoundsSize)
    val solid = rcCreateHeightfield(size, size, bmin, bmax, s.cellSize, s.cellHeight)

    val p = Params(s, agent)
    val realBounds = s.realTileBoundsWithBorder(tile)
    if (!rasterizeMesh(mesh.mesh, s, p, solid)) return null
    for (w in mesh.water) {
        val cell = maxCellTileBounds(w.cellX, w.cellY, w.water.cellSize)
        val inter = getIntersection(realBounds, cell) ?: continue
        val level = swimLevel(s, w.water.level, halfZ) * s.recastScaleFactor
        if (!rasterizeRectangle(scaled(inter, s.recastScaleFactor), level, AREA_WATER, p, solid)) return null
    }
    for (h in mesh.heightfields) if (!rasterizeMesh(makeMesh(h), s, p, solid)) return null
    for (h in mesh.flatHeightfields) {
        val cell = maxCellTileBounds(h.cellX, h.cellY, h.cellSize)
        val inter = getIntersection(realBounds, cell) ?: continue
        if (!rasterizeRectangle(scaled(inter, s.recastScaleFactor), h.height * s.recastScaleFactor, AREA_GROUND, p, solid)) return null
    }

    stage(0)
    rcFilterLowHangingWalkableObstacles(p.walkableClimb, solid)
    rcFilterLedgeSpans(p.walkableHeight, p.walkableClimb, solid)
    rcFilterWalkableLowHeightSpans(p.walkableHeight, solid)

    stage(1)
    if (p.walkableHeight < 3 || p.walkableClimb < 0) return null
    val compact = rcBuildCompactHeightfield(p.walkableHeight, p.walkableClimb, solid)
    if (!(0 < p.walkableRadius && p.walkableRadius < WALKABLE_RADIUS_UPPER_LIMIT)) return null
    if (!rcErodeWalkableArea(p.walkableRadius, compact)) return null
    stage(2)
    rcBuildDistanceField(compact)
    stage(3)
    if (s.borderSize < 0 || s.regionMinArea < 0 || s.regionMergeArea < 0) return null
    if (!rcBuildRegions(compact, s.borderSize, s.regionMinArea, s.regionMergeArea)) return null
    if (s.maxSimplificationError < 0f || p.maxEdgeLen < 0) return null
    stage(4)
    val contours = rcBuildContours(compact, s.maxSimplificationError, p.maxEdgeLen, RC_CONTOUR_TESS_WALL_EDGES)
    if (contours.conts.isEmpty()) return null
    if (s.maxVertsPerPoly < 3) return null
    stage(5)
    val polyMesh = rcBuildPolyMesh(contours, s.maxVertsPerPoly) ?: return null
    stage(6)
    if (p.sampleDist < 0f || p.sampleMaxError < 0f) return null
    val detail = rcBuildPolyMeshDetail(polyMesh, compact, p.sampleDist, p.sampleMaxError) ?: return null

    stage(7)
    polyMesh.flags = IntArray(polyMesh.npolys) { areaFlag(polyMesh.areas[it]) }
    return PreparedNavMeshData(0, s.cellSize, s.cellHeight, polyMesh, detail)
}

private fun areaFlag(area: Int) = when (area) {
    AREA_NULL -> FLAG_NONE
    AREA_GROUND -> FLAG_WALK
    AREA_WATER -> FLAG_SWIM
    AREA_DOOR -> FLAG_OPEN_DOOR
    AREA_PATHGRID -> FLAG_USE_PATHGRID
    else -> FLAG_NONE
}
