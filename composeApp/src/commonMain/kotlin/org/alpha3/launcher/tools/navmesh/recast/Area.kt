// SPDX-License-Identifier: GPL-3.0-or-later
// Kotlin port of Recast's RecastArea.cpp (zlib licence, Copyright (c) 2009-2010 Mikko Mononen).
package org.alpha3.launcher.tools.navmesh.recast

internal fun rcErodeWalkableArea(erosionRadius: Int, chf: CompactHeightfield): Boolean {
    val xSize = chf.width
    val zSize = chf.height
    val n = chf.spanCount
    val nbr = chf.nbr
    val areas = chf.areas
    val cellIndex = chf.cellIndex
    val cellCount = chf.cellCount
    val dist = IntArray(n)
    java.util.Arrays.fill(dist, 255)

    for (s in 0 until n) {
        if (areas[s] == RC_NULL_AREA) { dist[s] = 0; continue }
        val b = s * 4
        if (nbr[b] < 0 || areas[nbr[b]] == RC_NULL_AREA || nbr[b + 1] < 0 || areas[nbr[b + 1]] == RC_NULL_AREA ||
            nbr[b + 2] < 0 || areas[nbr[b + 2]] == RC_NULL_AREA || nbr[b + 3] < 0 || areas[nbr[b + 3]] == RC_NULL_AREA) dist[s] = 0
    }

    // One sweep step: relax from the straight neighbour in direction [d1] and the diagonal reached via [d2] from it.
    for (cell in 0 until xSize * zSize) {
        val end = cellIndex[cell] + cellCount[cell]
        for (s in cellIndex[cell] until end) {
            relaxErode(dist, nbr, s, 0, 3)
            relaxErode(dist, nbr, s, 3, 2)
        }
    }
    for (cell in xSize * zSize - 1 downTo 0) {
        val end = cellIndex[cell] + cellCount[cell]
        for (s in cellIndex[cell] until end) {
            relaxErode(dist, nbr, s, 2, 1)
            relaxErode(dist, nbr, s, 1, 0)
        }
    }

    val minBoundaryDistance = (erosionRadius * 2) and 0xff
    for (s in 0 until n) if (dist[s] < minBoundaryDistance) areas[s] = RC_NULL_AREA
    return true
}

private fun relaxErode(dist: IntArray, nbr: IntArray, s: Int, d1: Int, d2: Int) {
    val a = nbr[s * 4 + d1]
    if (a < 0) return
    var nd = dist[a] + 2
    if (nd > 255) nd = 255
    if (nd < dist[s]) dist[s] = nd
    val b = nbr[a * 4 + d2]
    if (b >= 0) {
        nd = dist[b] + 3
        if (nd > 255) nd = 255
        if (nd < dist[s]) dist[s] = nd
    }
}
