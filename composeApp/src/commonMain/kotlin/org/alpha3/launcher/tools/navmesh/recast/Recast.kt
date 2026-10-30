// SPDX-License-Identifier: GPL-3.0-or-later
// A Kotlin port of the parts of Recast (https://github.com/recastnavigation/recastnavigation, zlib licence,
// Copyright (c) 2009-2010 Mikko Mononen) that OpenMW's navmesh generation uses. Arithmetic is kept in
// Float, in the same order as the C++, so the results match the native library bit for bit.
package org.alpha3.launcher.tools.navmesh.recast

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt

internal const val RC_PI = 3.14159265f
internal const val RC_SPAN_HEIGHT_BITS = 13
internal const val RC_SPAN_MAX_HEIGHT = (1 shl RC_SPAN_HEIGHT_BITS) - 1
internal const val RC_BORDER_REG = 0x8000
internal const val RC_MULTIPLE_REGS = 0
internal const val RC_BORDER_VERTEX = 0x10000
internal const val RC_AREA_BORDER = 0x20000
internal const val RC_CONTOUR_TESS_WALL_EDGES = 0x01
internal const val RC_CONTOUR_TESS_AREA_EDGES = 0x02
internal const val RC_CONTOUR_REG_MASK = 0xffff
internal const val RC_MESH_NULL_IDX = 0xffff
internal const val RC_NULL_AREA = 0
internal const val RC_WALKABLE_AREA = 63
internal const val RC_NOT_CONNECTED = 0x3f

internal fun rcGetDirOffsetX(direction: Int): Int = when (direction and 3) { 0 -> -1; 2 -> 1; else -> 0 }
internal fun rcGetDirOffsetY(direction: Int): Int = when (direction and 3) { 1 -> 1; 3 -> -1; else -> 0 }
private val DIR_FOR_OFFSET = intArrayOf(3, 0, -1, 2, 1)
internal fun rcGetDirForOffset(offsetX: Int, offsetZ: Int): Int = DIR_FOR_OFFSET[((offsetZ + 1) shl 1) + offsetX]

/** Spans of a solid heightfield, stored column by column as linked lists in a pool of parallel arrays. */
internal class Heightfield(
    val width: Int, val height: Int,
    val bmin: FloatArray, val bmax: FloatArray,
    val cs: Float, val ch: Float,
) {
    /** First span of each column, or -1. */
    val columns = IntArray(width * height) { -1 }
    var smin = IntArray(1024)
    var smax = IntArray(1024)
    var area = IntArray(1024)
    var next = IntArray(1024)
    private var used = 0
    private var freeStack = IntArray(256)
    private var freeCount = 0

    fun alloc(): Int {
        if (freeCount > 0) return freeStack[--freeCount]
        if (used == smin.size) {
            val n = used * 2
            smin = smin.copyOf(n); smax = smax.copyOf(n); area = area.copyOf(n); next = next.copyOf(n)
        }
        return used++
    }

    fun free(span: Int) {
        if (freeCount == freeStack.size) freeStack = freeStack.copyOf(freeCount * 2)
        freeStack[freeCount++] = span
    }
}

internal class CompactHeightfield(
    val width: Int, val height: Int, val spanCount: Int,
    val walkableHeight: Int, val walkableClimb: Int,
    val bmin: FloatArray, val bmax: FloatArray, val cs: Float, val ch: Float,
) {
    var borderSize = 0
    var maxDistance = 0
    var maxRegions = 0
    /** Per cell: index of its first span, and how many spans it has. */
    val cellIndex = IntArray(width * height)
    val cellCount = IntArray(width * height)
    val y = IntArray(spanCount)
    val reg = IntArray(spanCount)
    val con = IntArray(spanCount)
    val h = IntArray(spanCount)
    var dist = IntArray(0)
    val areas = IntArray(spanCount)

    /** Neighbouring span in each of the four directions (span * 4 + dir), or -1 where there is no connection. */
    val nbr = IntArray(spanCount * 4).also { it.fill(-1) }

    fun getCon(span: Int, direction: Int): Int = (con[span] ushr (direction * 6)) and 0x3f

    fun setCon(span: Int, direction: Int, neighbor: Int) {
        val shift = direction * 6
        con[span] = (con[span] and (0x3f shl shift).inv()) or ((neighbor and 0x3f) shl shift)
    }
}

internal fun rcCreateHeightfield(sizeX: Int, sizeZ: Int, minBounds: FloatArray, maxBounds: FloatArray, cellSize: Float, cellHeight: Float) =
    Heightfield(sizeX, sizeZ, minBounds.copyOf(), maxBounds.copyOf(), cellSize, cellHeight)

private fun calcTriNormal(v: FloatArray, a: Int, b: Int, c: Int, out: FloatArray) {
    val e0x = v[b * 3] - v[a * 3]; val e0y = v[b * 3 + 1] - v[a * 3 + 1]; val e0z = v[b * 3 + 2] - v[a * 3 + 2]
    val e1x = v[c * 3] - v[a * 3]; val e1y = v[c * 3 + 1] - v[a * 3 + 1]; val e1z = v[c * 3 + 2] - v[a * 3 + 2]
    out[0] = e0y * e1z - e0z * e1y
    out[1] = e0z * e1x - e0x * e1z
    out[2] = e0x * e1y - e0y * e1x
    val d = 1.0f / sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2])
    out[0] *= d; out[1] *= d; out[2] *= d
}

internal fun rcClearUnwalkableTriangles(walkableSlopeAngle: Float, verts: FloatArray, tris: IntArray, numTris: Int, triAreaIDs: IntArray) {
    val walkableLimitY = cos(walkableSlopeAngle / 180.0f * RC_PI)
    val faceNormal = FloatArray(3)
    for (i in 0 until numTris) {
        calcTriNormal(verts, tris[i * 3], tris[i * 3 + 1], tris[i * 3 + 2], faceNormal)
        if (faceNormal[1] <= walkableLimitY) triAreaIDs[i] = RC_NULL_AREA
    }
}

private fun addSpan(hf: Heightfield, x: Int, z: Int, min: Int, max: Int, areaID: Int, flagMergeThreshold: Int) {
    val newSpan = hf.alloc()
    hf.smin[newSpan] = min and 0x1fff
    hf.smax[newSpan] = max and 0x1fff
    hf.area[newSpan] = areaID and 0x3f
    hf.next[newSpan] = -1

    val columnIndex = x + z * hf.width
    var previous = -1
    var current = hf.columns[columnIndex]
    while (current != -1) {
        if (hf.smin[current] > hf.smax[newSpan]) break
        if (hf.smax[current] < hf.smin[newSpan]) {
            previous = current
            current = hf.next[current]
        } else {
            if (hf.smin[current] < hf.smin[newSpan]) hf.smin[newSpan] = hf.smin[current]
            if (hf.smax[current] > hf.smax[newSpan]) hf.smax[newSpan] = hf.smax[current]
            if (kotlin.math.abs(hf.smax[newSpan] - hf.smax[current]) <= flagMergeThreshold) {
                hf.area[newSpan] = maxOf(hf.area[newSpan], hf.area[current])
            }
            val next = hf.next[current]
            hf.free(current)
            if (previous != -1) hf.next[previous] = next else hf.columns[columnIndex] = next
            current = next
        }
    }
    if (previous != -1) {
        hf.next[newSpan] = hf.next[previous]
        hf.next[previous] = newSpan
    } else {
        hf.next[newSpan] = hf.columns[columnIndex]
        hf.columns[columnIndex] = newSpan
    }
}

/** Splits a convex polygon (at most 12 vertices) at [axisOffset] along [axis]; returns the vertex counts of both halves. */
private fun dividePoly(
    inVerts: FloatArray, inOff: Int, inVertsCount: Int,
    outVerts1: FloatArray, out1Off: Int, outVerts2: FloatArray, out2Off: Int,
    axisOffset: Float, axis: Int, counts: IntArray, delta: FloatArray,
) {
    if (inVertsCount == 0) { counts[0] = 0; counts[1] = 0; return }
    for (v in 0 until inVertsCount) delta[v] = axisOffset - inVerts[inOff + v * 3 + axis]
    var o1 = out1Off
    var o2 = out2Off
    var a = 0
    var b = inVertsCount - 1
    var da: Float
    var db = delta[b]
    while (a < inVertsCount) {
        da = delta[a]
        val ia = inOff + a * 3
        if ((da >= 0) != (db >= 0)) {
            val ib = inOff + b * 3
            val s = db / (db - da)
            val x = inVerts[ib] + (inVerts[ia] - inVerts[ib]) * s
            val y = inVerts[ib + 1] + (inVerts[ia + 1] - inVerts[ib + 1]) * s
            val z = inVerts[ib + 2] + (inVerts[ia + 2] - inVerts[ib + 2]) * s
            outVerts1[o1] = x; outVerts1[o1 + 1] = y; outVerts1[o1 + 2] = z; o1 += 3
            outVerts2[o2] = x; outVerts2[o2 + 1] = y; outVerts2[o2 + 2] = z; o2 += 3
            if (da > 0) {
                outVerts1[o1] = inVerts[ia]; outVerts1[o1 + 1] = inVerts[ia + 1]; outVerts1[o1 + 2] = inVerts[ia + 2]; o1 += 3
            } else if (da < 0) {
                outVerts2[o2] = inVerts[ia]; outVerts2[o2 + 1] = inVerts[ia + 1]; outVerts2[o2 + 2] = inVerts[ia + 2]; o2 += 3
            }
        } else {
            var addTo2 = true
            if (da >= 0) {
                outVerts1[o1] = inVerts[ia]; outVerts1[o1 + 1] = inVerts[ia + 1]; outVerts1[o1 + 2] = inVerts[ia + 2]; o1 += 3
                if (da != 0f) addTo2 = false
            }
            if (addTo2) {
                outVerts2[o2] = inVerts[ia]; outVerts2[o2 + 1] = inVerts[ia + 1]; outVerts2[o2 + 2] = inVerts[ia + 2]; o2 += 3
            }
        }
        db = da
        b = a
        a++
    }
    counts[0] = (o1 - out1Off) / 3
    counts[1] = (o2 - out2Off) / 3
}

private fun rasterizeTri(
    verts: FloatArray, i0: Int, i1: Int, i2: Int, areaID: Int, hf: Heightfield,
    cellSize: Float, inverseCellSize: Float, inverseCellHeight: Float, flagMergeThreshold: Int,
    buf: FloatArray,
) {
    val bbMin = hf.bmin
    val bbMax = hf.bmax
    var triMinX = verts[i0 * 3]; var triMinY = verts[i0 * 3 + 1]; var triMinZ = verts[i0 * 3 + 2]
    var triMaxX = triMinX; var triMaxY = triMinY; var triMaxZ = triMinZ
    for (i in intArrayOf(i1, i2)) {
        val x = verts[i * 3]; val y = verts[i * 3 + 1]; val z = verts[i * 3 + 2]
        if (x < triMinX) triMinX = x; if (y < triMinY) triMinY = y; if (z < triMinZ) triMinZ = z
        if (x > triMaxX) triMaxX = x; if (y > triMaxY) triMaxY = y; if (z > triMaxZ) triMaxZ = z
    }
    if (!(triMinX <= bbMax[0] && triMaxX >= bbMin[0] && triMinY <= bbMax[1] && triMaxY >= bbMin[1] && triMinZ <= bbMax[2] && triMaxZ >= bbMin[2])) return

    val w = hf.width
    val h = hf.height
    val by = bbMax[1] - bbMin[1]

    var z0 = ((triMinZ - bbMin[2]) * inverseCellSize).toInt()
    var z1 = ((triMaxZ - bbMin[2]) * inverseCellSize).toInt()
    z0 = z0.coerceIn(-1, h - 1)
    z1 = z1.coerceIn(0, h - 1)

    // buf holds four 7-vertex polygons: in, inRow, p1, p2
    var inOff = 0
    var inRowOff = 21
    var p1Off = 42
    var p2Off = 63
    for (k in 0 until 3) {
        buf[inOff + k] = verts[i0 * 3 + k]
        buf[inOff + 3 + k] = verts[i1 * 3 + k]
        buf[inOff + 6 + k] = verts[i2 * 3 + k]
    }
    var nvIn = 3
    val counts = IntArray(2)
    val delta = FloatArray(12)

    for (z in z0..z1) {
        val cellZ = bbMin[2] + z.toFloat() * cellSize
        dividePoly(buf, inOff, nvIn, buf, inRowOff, buf, p1Off, cellZ + cellSize, 2, counts, delta)
        val nvRow = counts[0]
        nvIn = counts[1]
        run { val t = inOff; inOff = p1Off; p1Off = t }

        if (nvRow < 3) continue
        if (z < 0) continue

        var minX = buf[inRowOff]
        var maxX = buf[inRowOff]
        for (vert in 1 until nvRow) {
            if (minX > buf[inRowOff + vert * 3]) minX = buf[inRowOff + vert * 3]
            if (maxX < buf[inRowOff + vert * 3]) maxX = buf[inRowOff + vert * 3]
        }
        var x0 = ((minX - bbMin[0]) * inverseCellSize).toInt()
        var x1 = ((maxX - bbMin[0]) * inverseCellSize).toInt()
        if (x1 < 0 || x0 >= w) continue
        x0 = x0.coerceIn(-1, w - 1)
        x1 = x1.coerceIn(0, w - 1)

        var nv2 = nvRow
        for (x in x0..x1) {
            val cx = bbMin[0] + x.toFloat() * cellSize
            dividePoly(buf, inRowOff, nv2, buf, p1Off, buf, p2Off, cx + cellSize, 0, counts, delta)
            val nv = counts[0]
            nv2 = counts[1]
            run { val t = inRowOff; inRowOff = p2Off; p2Off = t }

            if (nv < 3) continue
            if (x < 0) continue

            var spanMin = buf[p1Off + 1]
            var spanMax = buf[p1Off + 1]
            for (vert in 1 until nv) {
                spanMin = minOf(spanMin, buf[p1Off + vert * 3 + 1])
                spanMax = maxOf(spanMax, buf[p1Off + vert * 3 + 1])
            }
            spanMin -= bbMin[1]
            spanMax -= bbMin[1]

            if (spanMax < 0.0f) continue
            if (spanMin > by) continue
            if (spanMin < 0.0f) spanMin = 0f
            if (spanMax > by) spanMax = by

            val minCell = floor(spanMin * inverseCellHeight).toInt().coerceIn(0, RC_SPAN_MAX_HEIGHT) and 0xffff
            val maxCell = ceil(spanMax * inverseCellHeight).toInt().coerceIn(minCell + 1, RC_SPAN_MAX_HEIGHT) and 0xffff
            addSpan(hf, x, z, minCell, maxCell, areaID, flagMergeThreshold)
        }
    }
}

internal fun rcRasterizeTriangles(verts: FloatArray, tris: IntArray, triAreaIDs: IntArray, numTris: Int, hf: Heightfield, flagMergeThreshold: Int) {
    val inverseCellSize = 1.0f / hf.cs
    val inverseCellHeight = 1.0f / hf.ch
    val buf = FloatArray(7 * 3 * 4)
    for (t in 0 until numTris) {
        rasterizeTri(verts, tris[t * 3], tris[t * 3 + 1], tris[t * 3 + 2], triAreaIDs[t], hf, hf.cs, inverseCellSize, inverseCellHeight, flagMergeThreshold, buf)
    }
}

private const val MAX_HEIGHTFIELD_HEIGHT = 0xffff

private val RECT_TRIS = intArrayOf(0, 1, 2, 0, 2, 3)

private fun rasterizeRectNaive(minX: Float, minZ: Float, maxX: Float, maxZ: Float, y: Float, area: Int, hf: Heightfield, flagMergeThreshold: Int) {
    val verts = floatArrayOf(minX, y, minZ, minX, y, maxZ, maxX, y, maxZ, maxX, y, minZ)
    rcRasterizeTriangles(verts, RECT_TRIS, intArrayOf(area, area), 2, hf, flagMergeThreshold)
}

/**
 * Rasterizes the horizontal rectangle (two triangles) like [rcRasterizeTriangles] would. Cells well inside it all get
 * the same span, so those are added directly and only a ring along the edges goes through the polygon clipping.
 */
internal fun rcRasterizeFlatRectangle(minX: Float, minZ: Float, maxX: Float, maxZ: Float, y: Float, area: Int, hf: Heightfield, flagMergeThreshold: Int) {
    val inv = 1.0f / hf.cs
    val bmin = hf.bmin
    val ix0 = ((minX - bmin[0]) * inv).toInt() + 2
    val ix1 = ((maxX - bmin[0]) * inv).toInt() - 3
    val iz0 = ((minZ - bmin[2]) * inv).toInt() + 2
    val iz1 = ((maxZ - bmin[2]) * inv).toInt() - 3
    val cx0 = maxOf(ix0, 0)
    val cx1 = minOf(ix1, hf.width - 1)
    val cz0 = maxOf(iz0, 0)
    val cz1 = minOf(iz1, hf.height - 1)
    val by = hf.bmax[1] - bmin[1]
    var spanMin = y - bmin[1]
    var spanMax = spanMin
    if (cx0 > cx1 || cz0 > cz1 || ix0 > ix1 || iz0 > iz1 || spanMax < 0.0f || spanMin > by) {
        rasterizeRectNaive(minX, minZ, maxX, maxZ, y, area, hf, flagMergeThreshold)
        return
    }
    if (spanMin < 0.0f) spanMin = 0f
    if (spanMax > by) spanMax = by
    val inverseCellHeight = 1.0f / hf.ch
    val minCell = floor(spanMin * inverseCellHeight).toInt().coerceIn(0, RC_SPAN_MAX_HEIGHT) and 0xffff
    val maxCell = ceil(spanMax * inverseCellHeight).toInt().coerceIn(minCell + 1, RC_SPAN_MAX_HEIGHT) and 0xffff
    for (z in cz0..cz1) for (x in cx0..cx1) addSpan(hf, x, z, minCell, maxCell, area, flagMergeThreshold)

    // the ring around the filled cells, split at grid lines
    val sx0 = bmin[0] + ix0.toFloat() * hf.cs
    val sx1 = bmin[0] + (ix1 + 1).toFloat() * hf.cs
    val sz0 = bmin[2] + iz0.toFloat() * hf.cs
    val sz1 = bmin[2] + (iz1 + 1).toFloat() * hf.cs
    rasterizeRectNaive(minX, minZ, sx0, maxZ, y, area, hf, flagMergeThreshold)
    rasterizeRectNaive(sx1, minZ, maxX, maxZ, y, area, hf, flagMergeThreshold)
    rasterizeRectNaive(sx0, minZ, sx1, sz0, y, area, hf, flagMergeThreshold)
    rasterizeRectNaive(sx0, sz1, sx1, maxZ, y, area, hf, flagMergeThreshold)
}

internal fun rcFilterLowHangingWalkableObstacles(walkableClimb: Int, hf: Heightfield) {
    val xSize = hf.width
    val zSize = hf.height
    for (z in 0 until zSize) for (x in 0 until xSize) {
        var previous = -1
        var previousWasWalkable = false
        var previousAreaID = RC_NULL_AREA
        var span = hf.columns[x + z * xSize]
        while (span != -1) {
            val walkable = hf.area[span] != RC_NULL_AREA
            if (!walkable && previousWasWalkable && hf.smax[span] - hf.smax[previous] <= walkableClimb) hf.area[span] = previousAreaID
            previousWasWalkable = walkable
            previousAreaID = hf.area[span]
            previous = span
            span = hf.next[span]
        }
    }
}

private const val ALL_NOT_CONNECTED = RC_NOT_CONNECTED or (RC_NOT_CONNECTED shl 6) or (RC_NOT_CONNECTED shl 12) or (RC_NOT_CONNECTED shl 18)
private val LEDGE_DX = intArrayOf(-1, 0, 1, 0)
private val LEDGE_DZ = intArrayOf(0, 1, 0, -1)

internal fun rcFilterLedgeSpans(walkableHeight: Int, walkableClimb: Int, hf: Heightfield) {
    val xSize = hf.width
    val zSize = hf.height
    val columns = hf.columns
    val area = hf.area
    val next = hf.next
    val smin = hf.smin
    val smax = hf.smax
    for (z in 0 until zSize) for (x in 0 until xSize) {
        var span = columns[x + z * xSize]
        while (span != -1) {
            if (area[span] == RC_NULL_AREA) { span = next[span]; continue }
            val floor = smax[span]
            val ceiling = if (next[span] != -1) smin[next[span]] else MAX_HEIGHTFIELD_HEIGHT
            var lowestNeighborFloorDifference = MAX_HEIGHTFIELD_HEIGHT
            var lowestTraversableNeighborFloor = smax[span]
            var highestTraversableNeighborFloor = smax[span]

            for (direction in 0 until 4) {
                val neighborX = x + LEDGE_DX[direction]
                val neighborZ = z + LEDGE_DZ[direction]
                if (neighborX < 0 || neighborZ < 0 || neighborX >= xSize || neighborZ >= zSize) {
                    lowestNeighborFloorDifference = -walkableClimb - 1
                    break
                }
                var neighbor = columns[neighborX + neighborZ * xSize]
                var neighborCeiling = if (neighbor != -1) smin[neighbor] else MAX_HEIGHTFIELD_HEIGHT
                if (minOf(ceiling, neighborCeiling) - floor > walkableHeight) {
                    lowestNeighborFloorDifference = -walkableClimb - 1
                    break
                }
                while (neighbor != -1) {
                    val neighborFloor = smax[neighbor]
                    neighborCeiling = if (next[neighbor] != -1) smin[next[neighbor]] else MAX_HEIGHTFIELD_HEIGHT
                    if (minOf(ceiling, neighborCeiling) - maxOf(floor, neighborFloor) <= walkableHeight) {
                        neighbor = next[neighbor]
                        continue
                    }
                    val neighborFloorDifference = neighborFloor - floor
                    lowestNeighborFloorDifference = minOf(lowestNeighborFloorDifference, neighborFloorDifference)
                    if (kotlin.math.abs(neighborFloorDifference) <= walkableClimb) {
                        lowestTraversableNeighborFloor = minOf(lowestTraversableNeighborFloor, neighborFloor)
                        highestTraversableNeighborFloor = maxOf(highestTraversableNeighborFloor, neighborFloor)
                    } else if (neighborFloorDifference < -walkableClimb) {
                        break
                    }
                    neighbor = next[neighbor]
                }
            }
            if (lowestNeighborFloorDifference < -walkableClimb) area[span] = RC_NULL_AREA
            else if (highestTraversableNeighborFloor - lowestTraversableNeighborFloor > walkableClimb) area[span] = RC_NULL_AREA
            span = next[span]
        }
    }
}

internal fun rcFilterWalkableLowHeightSpans(walkableHeight: Int, hf: Heightfield) {
    val xSize = hf.width
    val zSize = hf.height
    for (z in 0 until zSize) for (x in 0 until xSize) {
        var span = hf.columns[x + z * xSize]
        while (span != -1) {
            val floor = hf.smax[span]
            val ceiling = if (hf.next[span] != -1) hf.smin[hf.next[span]] else MAX_HEIGHTFIELD_HEIGHT
            if (ceiling - floor < walkableHeight) hf.area[span] = RC_NULL_AREA
            span = hf.next[span]
        }
    }
}

private fun rcGetHeightFieldSpanCount(hf: Heightfield): Int {
    var count = 0
    for (c in 0 until hf.width * hf.height) {
        var span = hf.columns[c]
        while (span != -1) {
            if (hf.area[span] != RC_NULL_AREA) count++
            span = hf.next[span]
        }
    }
    return count
}

internal fun rcBuildCompactHeightfield(walkableHeight: Int, walkableClimb: Int, hf: Heightfield): CompactHeightfield {
    val xSize = hf.width
    val zSize = hf.height
    val spanCount = rcGetHeightFieldSpanCount(hf)
    val bmax = hf.bmax.copyOf()
    bmax[1] += walkableHeight * hf.ch
    val chf = CompactHeightfield(xSize, zSize, spanCount, walkableHeight, walkableClimb, hf.bmin.copyOf(), bmax, hf.cs, hf.ch)
    chf.areas.fill(RC_NULL_AREA)

    val maxHeight = 0xffff
    var currentCell = 0
    for (column in 0 until xSize * zSize) {
        var span = hf.columns[column]
        if (span == -1) continue
        chf.cellIndex[column] = currentCell
        chf.cellCount[column] = 0
        while (span != -1) {
            if (hf.area[span] != RC_NULL_AREA) {
                val bot = hf.smax[span]
                val top = if (hf.next[span] != -1) hf.smin[hf.next[span]] else maxHeight
                chf.y[currentCell] = bot.coerceIn(0, 0xffff)
                chf.h[currentCell] = (top - bot).coerceIn(0, 0xff)
                chf.areas[currentCell] = hf.area[span]
                currentCell++
                chf.cellCount[column]++
            }
            span = hf.next[span]
        }
    }

    val maxLayers = RC_NOT_CONNECTED - 1
    val cellIndex = chf.cellIndex
    val cellCount = chf.cellCount
    val ys = chf.y
    val hs = chf.h
    val nbr = chf.nbr
    val conArr = chf.con
    for (z in 0 until zSize) for (x in 0 until xSize) {
        val cell = x + z * xSize
        var i = cellIndex[cell]
        val ni = i + cellCount[cell]
        while (i < ni) {
            val yi = ys[i]
            val topI = yi + hs[i]
            conArr[i] = ALL_NOT_CONNECTED
            for (dir in 0 until 4) {
                val neighborX = x + LEDGE_DX[dir]
                val neighborZ = z + LEDGE_DZ[dir]
                if (neighborX < 0 || neighborZ < 0 || neighborX >= xSize || neighborZ >= zSize) continue
                val neighborCell = neighborX + neighborZ * xSize
                val k0 = cellIndex[neighborCell]
                var k = k0
                val nk = k0 + cellCount[neighborCell]
                while (k < nk) {
                    val yk = ys[k]
                    val bot = if (yi > yk) yi else yk
                    val topK = yk + hs[k]
                    val top = if (topI < topK) topI else topK
                    if (top - bot >= walkableHeight && kotlin.math.abs(yk - yi) <= walkableClimb) {
                        val layerIndex = k - k0
                        if (layerIndex > maxLayers) { k++; continue }
                        chf.setCon(i, dir, layerIndex)
                        nbr[i * 4 + dir] = k
                        break
                    }
                    k++
                }
            }
            i++
        }
    }
    return chf
}
