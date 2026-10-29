// SPDX-License-Identifier: GPL-3.0-or-later
// Kotlin port of Recast's RecastContour.cpp (zlib licence, Copyright (c) 2009-2010 Mikko Mononen).
package org.alpha3.launcher.tools.navmesh.recast

internal class IntVec(capacity: Int = 16) {
    var data = IntArray(capacity)
    var size = 0
    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(maxOf(16, size * 2))
        data[size++] = v
    }
    fun clear() { size = 0 }
    operator fun get(i: Int) = data[i]
    operator fun set(i: Int, v: Int) { data[i] = v }
    /** Grows the vector by [n] zeroed entries. */
    fun grow(n: Int) { while (size + n > data.size) data = data.copyOf(maxOf(16, data.size * 2)); size += n }
    fun shrink(n: Int) { size -= n }
    fun resize(n: Int) {
        if (n > data.size) data = data.copyOf(maxOf(16, n, data.size * 2))
        if (n > size) data.fill(0, size, n)
        size = n
    }
}

internal class Contour {
    var verts = IntArray(0)
    var nverts = 0
    var rverts = IntArray(0)
    var nrverts = 0
    var reg = 0
    var area = 0
}

internal class ContourSet(
    val bmin: FloatArray, val bmax: FloatArray, val cs: Float, val ch: Float,
    val width: Int, val height: Int, val borderSize: Int, val maxError: Float,
) {
    val conts = ArrayList<Contour>()
}

private fun getCornerHeight(x: Int, y: Int, i: Int, dir: Int, chf: CompactHeightfield, border: BooleanArray): Int {
    var ch = chf.y[i]
    val dirp = (dir + 1) and 3
    val regs = IntArray(4)
    regs[0] = chf.reg[i] or (chf.areas[i] shl 16)

    if (chf.getCon(i, dir) != RC_NOT_CONNECTED) {
        val ax = x + rcGetDirOffsetX(dir)
        val ay = y + rcGetDirOffsetY(dir)
        val ai = chf.cellIndex[ax + ay * chf.width] + chf.getCon(i, dir)
        ch = maxOf(ch, chf.y[ai])
        regs[1] = chf.reg[ai] or (chf.areas[ai] shl 16)
        if (chf.getCon(ai, dirp) != RC_NOT_CONNECTED) {
            val ax2 = ax + rcGetDirOffsetX(dirp)
            val ay2 = ay + rcGetDirOffsetY(dirp)
            val ai2 = chf.cellIndex[ax2 + ay2 * chf.width] + chf.getCon(ai, dirp)
            ch = maxOf(ch, chf.y[ai2])
            regs[2] = chf.reg[ai2] or (chf.areas[ai2] shl 16)
        }
    }
    if (chf.getCon(i, dirp) != RC_NOT_CONNECTED) {
        val ax = x + rcGetDirOffsetX(dirp)
        val ay = y + rcGetDirOffsetY(dirp)
        val ai = chf.cellIndex[ax + ay * chf.width] + chf.getCon(i, dirp)
        ch = maxOf(ch, chf.y[ai])
        regs[3] = chf.reg[ai] or (chf.areas[ai] shl 16)
        if (chf.getCon(ai, dir) != RC_NOT_CONNECTED) {
            val ax2 = ax + rcGetDirOffsetX(dir)
            val ay2 = ay + rcGetDirOffsetY(dir)
            val ai2 = chf.cellIndex[ax2 + ay2 * chf.width] + chf.getCon(ai, dir)
            ch = maxOf(ch, chf.y[ai2])
            regs[2] = chf.reg[ai2] or (chf.areas[ai2] shl 16)
        }
    }

    for (j in 0 until 4) {
        val a = j
        val b = (j + 1) and 3
        val c = (j + 2) and 3
        val d = (j + 3) and 3
        val twoSameExts = (regs[a] and regs[b] and RC_BORDER_REG) != 0 && regs[a] == regs[b]
        val twoInts = ((regs[c] or regs[d]) and RC_BORDER_REG) == 0
        val intsSameArea = (regs[c] ushr 16) == (regs[d] ushr 16)
        val noZeros = regs[a] != 0 && regs[b] != 0 && regs[c] != 0 && regs[d] != 0
        if (twoSameExts && twoInts && intsSameArea && noZeros) {
            border[0] = true
            break
        }
    }
    return ch
}

private fun walkContour(x0: Int, y0: Int, i0: Int, chf: CompactHeightfield, flags: IntArray, points: IntVec) {
    var x = x0; var y = y0; var i = i0
    var dir = 0
    while ((flags[i] and (1 shl dir)) == 0) dir++
    val startDir = dir
    val starti = i
    val area = chf.areas[i]
    val border = BooleanArray(1)

    var iter = 0
    while (++iter < 40000) {
        if ((flags[i] and (1 shl dir)) != 0) {
            border[0] = false
            var isAreaBorder = false
            var px = x
            val py = getCornerHeight(x, y, i, dir, chf, border)
            var pz = y
            when (dir) {
                0 -> pz++
                1 -> { px++; pz++ }
                2 -> px++
            }
            var r = 0
            if (chf.getCon(i, dir) != RC_NOT_CONNECTED) {
                val ax = x + rcGetDirOffsetX(dir)
                val ay = y + rcGetDirOffsetY(dir)
                val ai = chf.cellIndex[ax + ay * chf.width] + chf.getCon(i, dir)
                r = chf.reg[ai]
                if (area != chf.areas[ai]) isAreaBorder = true
            }
            if (border[0]) r = r or RC_BORDER_VERTEX
            if (isAreaBorder) r = r or RC_AREA_BORDER
            points.add(px); points.add(py); points.add(pz); points.add(r)
            flags[i] = flags[i] and (1 shl dir).inv()
            dir = (dir + 1) and 3
        } else {
            var ni = -1
            val nx = x + rcGetDirOffsetX(dir)
            val ny = y + rcGetDirOffsetY(dir)
            if (chf.getCon(i, dir) != RC_NOT_CONNECTED) ni = chf.cellIndex[nx + ny * chf.width] + chf.getCon(i, dir)
            if (ni == -1) return
            x = nx; y = ny; i = ni
            dir = (dir + 3) and 3
        }
        if (starti == i && startDir == dir) break
    }
}

private fun distancePtSeg(x: Int, z: Int, px: Int, pz: Int, qx: Int, qz: Int): Float {
    val pqx = (qx - px).toFloat()
    val pqz = (qz - pz).toFloat()
    var dx = (x - px).toFloat()
    var dz = (z - pz).toFloat()
    val d = pqx * pqx + pqz * pqz
    var t = pqx * dx + pqz * dz
    if (d > 0) t /= d
    if (t < 0) t = 0f else if (t > 1) t = 1f
    dx = px.toFloat() + t * pqx - x.toFloat()
    dz = pz.toFloat() + t * pqz - z.toFloat()
    return dx * dx + dz * dz
}

private fun insertPoint(simplified: IntVec, at: Int, points: IntVec, pointIndex: Int) {
    simplified.grow(4)
    val n = simplified.size / 4
    for (j in n - 1 downTo at + 1) {
        for (k in 0 until 4) simplified[j * 4 + k] = simplified[(j - 1) * 4 + k]
    }
    simplified[(at) * 4 + 0 + 4] = points[pointIndex * 4 + 0]
    simplified[(at) * 4 + 1 + 4] = points[pointIndex * 4 + 1]
    simplified[(at) * 4 + 2 + 4] = points[pointIndex * 4 + 2]
    simplified[(at) * 4 + 3 + 4] = pointIndex
}

private fun simplifyContour(points: IntVec, simplified: IntVec, maxError: Float, maxEdgeLen: Int, buildFlags: Int) {
    var hasConnections = false
    run {
        var i = 0
        while (i < points.size) {
            if ((points[i + 3] and RC_CONTOUR_REG_MASK) != 0) { hasConnections = true; break }
            i += 4
        }
    }
    if (hasConnections) {
        val ni = points.size / 4
        for (i in 0 until ni) {
            val ii = (i + 1) % ni
            val differentRegs = (points[i * 4 + 3] and RC_CONTOUR_REG_MASK) != (points[ii * 4 + 3] and RC_CONTOUR_REG_MASK)
            val areaBorders = (points[i * 4 + 3] and RC_AREA_BORDER) != (points[ii * 4 + 3] and RC_AREA_BORDER)
            if (differentRegs || areaBorders) {
                simplified.add(points[i * 4 + 0]); simplified.add(points[i * 4 + 1]); simplified.add(points[i * 4 + 2]); simplified.add(i)
            }
        }
    }
    if (simplified.size == 0) {
        var llx = points[0]; var lly = points[1]; var llz = points[2]; var lli = 0
        var urx = points[0]; var ury = points[1]; var urz = points[2]; var uri = 0
        var i = 0
        while (i < points.size) {
            val x = points[i]; val y = points[i + 1]; val z = points[i + 2]
            if (x < llx || (x == llx && z < llz)) { llx = x; lly = y; llz = z; lli = i / 4 }
            if (x > urx || (x == urx && z > urz)) { urx = x; ury = y; urz = z; uri = i / 4 }
            i += 4
        }
        simplified.add(llx); simplified.add(lly); simplified.add(llz); simplified.add(lli)
        simplified.add(urx); simplified.add(ury); simplified.add(urz); simplified.add(uri)
    }

    val pn = points.size / 4
    var i = 0
    while (i < simplified.size / 4) {
        val ii = (i + 1) % (simplified.size / 4)
        var ax = simplified[i * 4 + 0]
        var az = simplified[i * 4 + 2]
        val ai = simplified[i * 4 + 3]
        var bx = simplified[ii * 4 + 0]
        var bz = simplified[ii * 4 + 2]
        val bi = simplified[ii * 4 + 3]

        var maxd = 0f
        var maxi = -1
        var ci: Int
        val cinc: Int
        val endi: Int
        if (bx > ax || (bx == ax && bz > az)) {
            cinc = 1
            ci = (ai + cinc) % pn
            endi = bi
        } else {
            cinc = pn - 1
            ci = (bi + cinc) % pn
            endi = ai
            val tx = ax; ax = bx; bx = tx
            val tz = az; az = bz; bz = tz
        }
        if ((points[ci * 4 + 3] and RC_CONTOUR_REG_MASK) == 0 || (points[ci * 4 + 3] and RC_AREA_BORDER) != 0) {
            while (ci != endi) {
                val d = distancePtSeg(points[ci * 4 + 0], points[ci * 4 + 2], ax, az, bx, bz)
                if (d > maxd) { maxd = d; maxi = ci }
                ci = (ci + cinc) % pn
            }
        }
        if (maxi != -1 && maxd > (maxError * maxError)) {
            insertPoint(simplified, i, points, maxi)
        } else {
            ++i
        }
    }

    if (maxEdgeLen > 0 && (buildFlags and (RC_CONTOUR_TESS_WALL_EDGES or RC_CONTOUR_TESS_AREA_EDGES)) != 0) {
        var k = 0
        while (k < simplified.size / 4) {
            val ii = (k + 1) % (simplified.size / 4)
            val ax = simplified[k * 4 + 0]
            val az = simplified[k * 4 + 2]
            val ai = simplified[k * 4 + 3]
            val bx = simplified[ii * 4 + 0]
            val bz = simplified[ii * 4 + 2]
            val bi = simplified[ii * 4 + 3]

            var maxi = -1
            val ci = (ai + 1) % pn
            var tess = false
            if ((buildFlags and RC_CONTOUR_TESS_WALL_EDGES) != 0 && (points[ci * 4 + 3] and RC_CONTOUR_REG_MASK) == 0) tess = true
            if ((buildFlags and RC_CONTOUR_TESS_AREA_EDGES) != 0 && (points[ci * 4 + 3] and RC_AREA_BORDER) != 0) tess = true
            if (tess) {
                val dx = bx - ax
                val dz = bz - az
                if (dx * dx + dz * dz > maxEdgeLen * maxEdgeLen) {
                    val n = if (bi < ai) (bi + pn - ai) else (bi - ai)
                    if (n > 1) {
                        maxi = if (bx > ax || (bx == ax && bz > az)) (ai + n / 2) % pn else (ai + (n + 1) / 2) % pn
                    }
                }
            }
            if (maxi != -1) insertPoint(simplified, k, points, maxi) else ++k
        }
    }

    for (j in 0 until simplified.size / 4) {
        val ai = (simplified[j * 4 + 3] + 1) % pn
        val bi = simplified[j * 4 + 3]
        simplified[j * 4 + 3] = (points[ai * 4 + 3] and (RC_CONTOUR_REG_MASK or RC_AREA_BORDER)) or (points[bi * 4 + 3] and RC_BORDER_VERTEX)
    }
}

private fun calcAreaOfPolygon2D(verts: IntArray, nverts: Int): Int {
    var area = 0
    var j = nverts - 1
    for (i in 0 until nverts) {
        area += verts[i * 4] * verts[j * 4 + 2] - verts[j * 4] * verts[i * 4 + 2]
        j = i
    }
    return (area + 1) / 2
}

internal fun prev(i: Int, n: Int) = if (i - 1 >= 0) i - 1 else n - 1
internal fun next(i: Int, n: Int) = if (i + 1 < n) i + 1 else 0

// Points are (array, offset) pairs; coordinates at +0 (x) and +2 (z).
internal fun area2(a: IntArray, ao: Int, b: IntArray, bo: Int, c: IntArray, co: Int): Int =
    (b[bo] - a[ao]) * (c[co + 2] - a[ao + 2]) - (c[co] - a[ao]) * (b[bo + 2] - a[ao + 2])

internal fun left(a: IntArray, ao: Int, b: IntArray, bo: Int, c: IntArray, co: Int) = area2(a, ao, b, bo, c, co) < 0
internal fun leftOn(a: IntArray, ao: Int, b: IntArray, bo: Int, c: IntArray, co: Int) = area2(a, ao, b, bo, c, co) <= 0
internal fun collinear(a: IntArray, ao: Int, b: IntArray, bo: Int, c: IntArray, co: Int) = area2(a, ao, b, bo, c, co) == 0

internal fun intersectProp(a: IntArray, ao: Int, b: IntArray, bo: Int, c: IntArray, co: Int, d: IntArray, dof: Int): Boolean {
    if (collinear(a, ao, b, bo, c, co) || collinear(a, ao, b, bo, d, dof) || collinear(c, co, d, dof, a, ao) || collinear(c, co, d, dof, b, bo)) return false
    return (left(a, ao, b, bo, c, co) xor left(a, ao, b, bo, d, dof)) && (left(c, co, d, dof, a, ao) xor left(c, co, d, dof, b, bo))
}

internal fun between(a: IntArray, ao: Int, b: IntArray, bo: Int, c: IntArray, co: Int): Boolean {
    if (!collinear(a, ao, b, bo, c, co)) return false
    return if (a[ao] != b[bo]) ((a[ao] <= c[co]) && (c[co] <= b[bo])) || ((a[ao] >= c[co]) && (c[co] >= b[bo]))
    else ((a[ao + 2] <= c[co + 2]) && (c[co + 2] <= b[bo + 2])) || ((a[ao + 2] >= c[co + 2]) && (c[co + 2] >= b[bo + 2]))
}

internal fun intersect(a: IntArray, ao: Int, b: IntArray, bo: Int, c: IntArray, co: Int, d: IntArray, dof: Int): Boolean {
    if (intersectProp(a, ao, b, bo, c, co, d, dof)) return true
    return between(a, ao, b, bo, c, co) || between(a, ao, b, bo, d, dof) || between(c, co, d, dof, a, ao) || between(c, co, d, dof, b, bo)
}

internal fun vequal(a: IntArray, ao: Int, b: IntArray, bo: Int) = a[ao] == b[bo] && a[ao + 2] == b[bo + 2]

private fun intersectSegContour(d0: IntArray, d0o: Int, d1: IntArray, d1o: Int, i: Int, n: Int, verts: IntArray): Boolean {
    for (k in 0 until n) {
        val k1 = next(k, n)
        if (i == k || i == k1) continue
        val p0 = k * 4
        val p1 = k1 * 4
        if (vequal(d0, d0o, verts, p0) || vequal(d1, d1o, verts, p0) || vequal(d0, d0o, verts, p1) || vequal(d1, d1o, verts, p1)) continue
        if (intersect(d0, d0o, d1, d1o, verts, p0, verts, p1)) return true
    }
    return false
}

private fun inConeHole(i: Int, n: Int, verts: IntArray, pj: IntArray, pjo: Int): Boolean {
    val pi = i * 4
    val pi1 = next(i, n) * 4
    val pin1 = prev(i, n) * 4
    if (leftOn(verts, pin1, verts, pi, verts, pi1)) return left(verts, pi, pj, pjo, verts, pin1) && left(pj, pjo, verts, pi, verts, pi1)
    return !(leftOn(verts, pi, pj, pjo, verts, pi1) && leftOn(pj, pjo, verts, pi, verts, pin1))
}

private fun removeDegenerateSegments(simplified: IntVec) {
    var npts = simplified.size / 4
    var i = 0
    while (i < npts) {
        val ni = next(i, npts)
        if (vequal(simplified.data, i * 4, simplified.data, ni * 4)) {
            for (j in i until simplified.size / 4 - 1) for (k in 0 until 4) simplified[j * 4 + k] = simplified[(j + 1) * 4 + k]
            simplified.shrink(4)
            npts--
        }
        i++
    }
}

private fun mergeContours(ca: Contour, cb: Contour, ia: Int, ib: Int) {
    val maxVerts = ca.nverts + cb.nverts + 2
    val verts = IntArray(maxVerts * 4)
    var nv = 0
    for (i in 0..ca.nverts) {
        val src = ((ia + i) % ca.nverts) * 4
        for (k in 0 until 4) verts[nv * 4 + k] = ca.verts[src + k]
        nv++
    }
    for (i in 0..cb.nverts) {
        val src = ((ib + i) % cb.nverts) * 4
        for (k in 0 until 4) verts[nv * 4 + k] = cb.verts[src + k]
        nv++
    }
    ca.verts = verts
    ca.nverts = nv
    cb.verts = IntArray(0)
    cb.nverts = 0
}

private class ContourHole(val contour: Contour) {
    var minx = 0
    var minz = 0
    var leftmost = 0
}

private class ContourRegion {
    var outline: Contour? = null
    val holes = ArrayList<ContourHole>()
}

private fun mergeRegionHoles(region: ContourRegion) {
    for (h in region.holes) {
        val c = h.contour
        h.minx = c.verts[0]; h.minz = c.verts[2]; h.leftmost = 0
        for (i in 1 until c.nverts) {
            val x = c.verts[i * 4]; val z = c.verts[i * 4 + 2]
            if (x < h.minx || (x == h.minx && z < h.minz)) { h.minx = x; h.minz = z; h.leftmost = i }
        }
    }
    region.holes.sortWith { a, b ->
        if (a.minx == b.minx) a.minz.compareTo(b.minz) else a.minx.compareTo(b.minx)
    }

    val outline = region.outline!!
    var maxVerts = outline.nverts
    for (h in region.holes) maxVerts += h.contour.nverts
    // The C++ reads diags[i] (not diags[j]) in the intersection test, so the buffer's stale contents matter.
    val diagVert = IntArray(maxVerts)
    val diagDist = IntArray(maxVerts)

    for (i in region.holes.indices) {
        val hole = region.holes[i].contour
        var index = -1
        var bestVertex = region.holes[i].leftmost
        for (iter in 0 until hole.nverts) {
            var ndiags = 0
            val corner = bestVertex * 4
            for (j in 0 until outline.nverts) {
                if (inConeHole(j, outline.nverts, outline.verts, hole.verts, corner)) {
                    val dx = outline.verts[j * 4] - hole.verts[corner]
                    val dz = outline.verts[j * 4 + 2] - hole.verts[corner + 2]
                    diagVert[ndiags] = j
                    diagDist[ndiags] = dx * dx + dz * dz
                    ndiags++
                }
            }
            // Stable sort of the first ndiags entries by distance.
            val order = (0 until ndiags).sortedBy { diagDist[it] }
            val sv = IntArray(ndiags) { diagVert[order[it]] }
            val sd = IntArray(ndiags) { diagDist[order[it]] }
            for (k in 0 until ndiags) { diagVert[k] = sv[k]; diagDist[k] = sd[k] }

            index = -1
            for (j in 0 until ndiags) {
                val pt = diagVert[j] * 4
                var isect = intersectSegContour(outline.verts, pt, hole.verts, corner, diagVert[i], outline.nverts, outline.verts)
                var k = i
                while (k < region.holes.size && !isect) {
                    val hc = region.holes[k].contour
                    isect = isect or intersectSegContour(outline.verts, pt, hole.verts, corner, -1, hc.nverts, hc.verts)
                    k++
                }
                if (!isect) { index = diagVert[j]; break }
            }
            if (index != -1) break
            bestVertex = (bestVertex + 1) % hole.nverts
        }
        if (index == -1) continue
        mergeContours(outline, hole, index, bestVertex)
    }
}

internal fun rcBuildContours(chf: CompactHeightfield, maxError: Float, maxEdgeLen: Int, buildFlags: Int): ContourSet {
    val w = chf.width
    val h = chf.height
    val borderSize = chf.borderSize
    val bmin = chf.bmin.copyOf()
    val bmax = chf.bmax.copyOf()
    if (borderSize > 0) {
        val pad = borderSize * chf.cs
        bmin[0] += pad; bmin[2] += pad
        bmax[0] -= pad; bmax[2] -= pad
    }
    val cset = ContourSet(bmin, bmax, chf.cs, chf.ch, chf.width - chf.borderSize * 2, chf.height - chf.borderSize * 2, chf.borderSize, maxError)

    val flags = IntArray(chf.spanCount)
    for (y in 0 until h) for (x in 0 until w) {
        val c = x + y * w
        for (i in chf.cellIndex[c] until chf.cellIndex[c] + chf.cellCount[c]) {
            var res = 0
            if (chf.reg[i] == 0 || (chf.reg[i] and RC_BORDER_REG) != 0) { flags[i] = 0; continue }
            for (dir in 0 until 4) {
                var r = 0
                if (chf.getCon(i, dir) != RC_NOT_CONNECTED) {
                    val ax = x + rcGetDirOffsetX(dir)
                    val ay = y + rcGetDirOffsetY(dir)
                    r = chf.reg[chf.cellIndex[ax + ay * w] + chf.getCon(i, dir)]
                }
                if (r == chf.reg[i]) res = res or (1 shl dir)
            }
            flags[i] = res xor 0xf
        }
    }

    val verts = IntVec(256)
    val simplified = IntVec(64)
    for (y in 0 until h) for (x in 0 until w) {
        val c = x + y * w
        for (i in chf.cellIndex[c] until chf.cellIndex[c] + chf.cellCount[c]) {
            if (flags[i] == 0 || flags[i] == 0xf) { flags[i] = 0; continue }
            val reg = chf.reg[i]
            if (reg == 0 || (reg and RC_BORDER_REG) != 0) continue
            val area = chf.areas[i]
            verts.clear()
            simplified.clear()
            walkContour(x, y, i, chf, flags, verts)
            simplifyContour(verts, simplified, maxError, maxEdgeLen, buildFlags)
            removeDegenerateSegments(simplified)

            if (simplified.size / 4 >= 3) {
                val cont = Contour()
                cont.nverts = simplified.size / 4
                cont.verts = simplified.data.copyOf(cont.nverts * 4)
                if (borderSize > 0) for (j in 0 until cont.nverts) { cont.verts[j * 4] -= borderSize; cont.verts[j * 4 + 2] -= borderSize }
                cont.nrverts = verts.size / 4
                cont.rverts = verts.data.copyOf(cont.nrverts * 4)
                if (borderSize > 0) for (j in 0 until cont.nrverts) { cont.rverts[j * 4] -= borderSize; cont.rverts[j * 4 + 2] -= borderSize }
                cont.reg = reg
                cont.area = area
                cset.conts.add(cont)
            }
        }
    }

    if (cset.conts.isNotEmpty()) {
        val winding = IntArray(cset.conts.size)
        var nholes = 0
        for ((i, cont) in cset.conts.withIndex()) {
            winding[i] = if (calcAreaOfPolygon2D(cont.verts, cont.nverts) < 0) -1 else 1
            if (winding[i] < 0) nholes++
        }
        if (nholes > 0) {
            val nregions = chf.maxRegions + 1
            val regions = Array(nregions) { ContourRegion() }
            for ((i, cont) in cset.conts.withIndex()) {
                if (winding[i] > 0) regions[cont.reg].outline = cont
                else regions[cont.reg].holes.add(ContourHole(cont))
            }
            for (i in 0 until nregions) {
                val reg = regions[i]
                if (reg.holes.isEmpty()) continue
                if (reg.outline != null) mergeRegionHoles(reg)
            }
        }
    }
    return cset
}
