// SPDX-License-Identifier: GPL-3.0-or-later
// Kotlin port of Recast's RecastMesh.cpp (zlib licence, Copyright (c) 2009-2010 Mikko Mononen).
// Unsigned 16-bit data is stored in IntArrays (values 0..65535).
package org.alpha3.launcher.tools.navmesh.recast

internal class PolyMesh(val nvp: Int) {
    var verts = IntArray(0)
    var polys = IntArray(0)
    var regs = IntArray(0)
    var areas = IntArray(0)
    var flags = IntArray(0)
    var nverts = 0
    var npolys = 0
    var maxpolys = 0
    var bmin = FloatArray(3)
    var bmax = FloatArray(3)
    var cs = 0f
    var ch = 0f
    var borderSize = 0
    var maxEdgeError = 0f
}

private fun buildMeshAdjacency(polys: IntArray, npolys: Int, nverts: Int, vertsPerPoly: Int) {
    val maxEdgeCount = npolys * vertsPerPoly
    val firstEdge = IntArray(nverts) { RC_MESH_NULL_IDX }
    val nextEdge = IntArray(maxEdgeCount)
    var edgeCount = 0
    // rcEdge: vert[2], polyEdge[2], poly[2]
    val eVert0 = IntArray(maxEdgeCount); val eVert1 = IntArray(maxEdgeCount)
    val ePolyEdge0 = IntArray(maxEdgeCount); val ePolyEdge1 = IntArray(maxEdgeCount)
    val ePoly0 = IntArray(maxEdgeCount); val ePoly1 = IntArray(maxEdgeCount)

    for (i in 0 until npolys) {
        val t = i * vertsPerPoly * 2
        for (j in 0 until vertsPerPoly) {
            if (polys[t + j] == RC_MESH_NULL_IDX) break
            val v0 = polys[t + j]
            val v1 = if (j + 1 >= vertsPerPoly || polys[t + j + 1] == RC_MESH_NULL_IDX) polys[t] else polys[t + j + 1]
            if (v0 < v1) {
                eVert0[edgeCount] = v0; eVert1[edgeCount] = v1
                ePoly0[edgeCount] = i; ePolyEdge0[edgeCount] = j
                ePoly1[edgeCount] = i; ePolyEdge1[edgeCount] = 0
                nextEdge[edgeCount] = firstEdge[v0]
                firstEdge[v0] = edgeCount
                edgeCount++
            }
        }
    }
    for (i in 0 until npolys) {
        val t = i * vertsPerPoly * 2
        for (j in 0 until vertsPerPoly) {
            if (polys[t + j] == RC_MESH_NULL_IDX) break
            val v0 = polys[t + j]
            val v1 = if (j + 1 >= vertsPerPoly || polys[t + j + 1] == RC_MESH_NULL_IDX) polys[t] else polys[t + j + 1]
            if (v0 > v1) {
                var e = firstEdge[v1]
                while (e != RC_MESH_NULL_IDX) {
                    if (eVert1[e] == v0 && ePoly0[e] == ePoly1[e]) {
                        ePoly1[e] = i
                        ePolyEdge1[e] = j
                        break
                    }
                    e = nextEdge[e]
                }
            }
        }
    }
    for (i in 0 until edgeCount) {
        if (ePoly0[i] != ePoly1[i]) {
            val p0 = ePoly0[i] * vertsPerPoly * 2
            val p1 = ePoly1[i] * vertsPerPoly * 2
            polys[p0 + vertsPerPoly + ePolyEdge0[i]] = ePoly1[i]
            polys[p1 + vertsPerPoly + ePolyEdge1[i]] = ePoly0[i]
        }
    }
}

private const val VERTEX_BUCKET_COUNT = 1 shl 12

private fun computeVertexHash(x: Int, y: Int, z: Int): Int {
    val h1 = 0x8da6b343.toInt()
    val h2 = 0xd8163841.toInt()
    val h3 = 0xcb1ab31f.toInt()
    val n = h1 * x + h2 * y + h3 * z
    return n and (VERTEX_BUCKET_COUNT - 1)
}

private fun addVertex(x: Int, y: Int, z: Int, verts: IntArray, firstVert: IntArray, nextVert: IntArray, nv: IntArray): Int {
    val bucket = computeVertexHash(x, 0, z)
    var i = firstVert[bucket]
    while (i != -1) {
        if (verts[i * 3] == x && kotlin.math.abs(verts[i * 3 + 1] - y) <= 2 && verts[i * 3 + 2] == z) return i
        i = nextVert[i]
    }
    i = nv[0]; nv[0]++
    verts[i * 3] = x; verts[i * 3 + 1] = y; verts[i * 3 + 2] = z
    nextVert[i] = firstVert[bucket]
    firstVert[bucket] = i
    return i
}

private const val IDX_MASK = 0x0fffffff
private const val CAN_REMOVE = Int.MIN_VALUE

private fun diagonalie(i: Int, j: Int, n: Int, verts: IntArray, indices: IntArray): Boolean {
    val d0 = (indices[i] and IDX_MASK) * 4
    val d1 = (indices[j] and IDX_MASK) * 4
    for (k in 0 until n) {
        val k1 = next(k, n)
        if (!((k == i) || (k1 == i) || (k == j) || (k1 == j))) {
            val p0 = (indices[k] and IDX_MASK) * 4
            val p1 = (indices[k1] and IDX_MASK) * 4
            if (vequal(verts, d0, verts, p0) || vequal(verts, d1, verts, p0) || vequal(verts, d0, verts, p1) || vequal(verts, d1, verts, p1)) continue
            if (intersect(verts, d0, verts, d1, verts, p0, verts, p1)) return false
        }
    }
    return true
}

private fun inCone(i: Int, j: Int, n: Int, verts: IntArray, indices: IntArray): Boolean {
    val pi = (indices[i] and IDX_MASK) * 4
    val pj = (indices[j] and IDX_MASK) * 4
    val pi1 = (indices[next(i, n)] and IDX_MASK) * 4
    val pin1 = (indices[prev(i, n)] and IDX_MASK) * 4
    if (leftOn(verts, pin1, verts, pi, verts, pi1)) return left(verts, pi, verts, pj, verts, pin1) && left(verts, pj, verts, pi, verts, pi1)
    return !(leftOn(verts, pi, verts, pj, verts, pi1) && leftOn(verts, pj, verts, pi, verts, pin1))
}

private fun diagonal(i: Int, j: Int, n: Int, verts: IntArray, indices: IntArray) = inCone(i, j, n, verts, indices) && diagonalie(i, j, n, verts, indices)

private fun diagonalieLoose(i: Int, j: Int, n: Int, verts: IntArray, indices: IntArray): Boolean {
    val d0 = (indices[i] and IDX_MASK) * 4
    val d1 = (indices[j] and IDX_MASK) * 4
    for (k in 0 until n) {
        val k1 = next(k, n)
        if (!((k == i) || (k1 == i) || (k == j) || (k1 == j))) {
            val p0 = (indices[k] and IDX_MASK) * 4
            val p1 = (indices[k1] and IDX_MASK) * 4
            if (vequal(verts, d0, verts, p0) || vequal(verts, d1, verts, p0) || vequal(verts, d0, verts, p1) || vequal(verts, d1, verts, p1)) continue
            if (intersectProp(verts, d0, verts, d1, verts, p0, verts, p1)) return false
        }
    }
    return true
}

private fun inConeLoose(i: Int, j: Int, n: Int, verts: IntArray, indices: IntArray): Boolean {
    val pi = (indices[i] and IDX_MASK) * 4
    val pj = (indices[j] and IDX_MASK) * 4
    val pi1 = (indices[next(i, n)] and IDX_MASK) * 4
    val pin1 = (indices[prev(i, n)] and IDX_MASK) * 4
    if (leftOn(verts, pin1, verts, pi, verts, pi1)) return leftOn(verts, pi, verts, pj, verts, pin1) && leftOn(verts, pj, verts, pi, verts, pi1)
    return !(leftOn(verts, pi, verts, pj, verts, pi1) && leftOn(verts, pj, verts, pi, verts, pin1))
}

private fun diagonalLoose(i: Int, j: Int, n: Int, verts: IntArray, indices: IntArray) = inConeLoose(i, j, n, verts, indices) && diagonalieLoose(i, j, n, verts, indices)

/** Returns the triangle count, negated if the contour was too broken to finish. */
private fun triangulate(n0: Int, verts: IntArray, indices: IntArray, tris: IntArray): Int {
    var n = n0
    var ntris = 0
    var dst = 0
    for (i in 0 until n) {
        val i1 = next(i, n)
        val i2 = next(i1, n)
        if (diagonal(i, i2, n, verts, indices)) indices[i1] = indices[i1] or CAN_REMOVE
    }
    while (n > 3) {
        var minLen = -1
        var mini = -1
        for (i in 0 until n) {
            val i1 = next(i, n)
            if (indices[i1] < 0) {
                val p0 = (indices[i] and IDX_MASK) * 4
                val p2 = (indices[next(i1, n)] and IDX_MASK) * 4
                val dx = verts[p2] - verts[p0]
                val dy = verts[p2 + 2] - verts[p0 + 2]
                val len = dx * dx + dy * dy
                if (minLen < 0 || len < minLen) { minLen = len; mini = i }
            }
        }
        if (mini == -1) {
            minLen = -1
            mini = -1
            for (i in 0 until n) {
                val i1 = next(i, n)
                val i2 = next(i1, n)
                if (diagonalLoose(i, i2, n, verts, indices)) {
                    val p0 = (indices[i] and IDX_MASK) * 4
                    val p2 = (indices[next(i2, n)] and IDX_MASK) * 4
                    val dx = verts[p2] - verts[p0]
                    val dy = verts[p2 + 2] - verts[p0 + 2]
                    val len = dx * dx + dy * dy
                    if (minLen < 0 || len < minLen) { minLen = len; mini = i }
                }
            }
            if (mini == -1) return -ntris
        }
        var i = mini
        var i1 = next(i, n)
        val i2 = next(i1, n)
        tris[dst++] = indices[i] and IDX_MASK
        tris[dst++] = indices[i1] and IDX_MASK
        tris[dst++] = indices[i2] and IDX_MASK
        ntris++

        n--
        for (k in i1 until n) indices[k] = indices[k + 1]
        if (i1 >= n) i1 = 0
        i = prev(i1, n)
        if (diagonal(prev(i, n), i1, n, verts, indices)) indices[i] = indices[i] or CAN_REMOVE
        else indices[i] = indices[i] and IDX_MASK
        if (diagonal(i, next(i1, n), n, verts, indices)) indices[i1] = indices[i1] or CAN_REMOVE
        else indices[i1] = indices[i1] and IDX_MASK
    }
    tris[dst++] = indices[0] and IDX_MASK
    tris[dst++] = indices[1] and IDX_MASK
    tris[dst++] = indices[2] and IDX_MASK
    ntris++
    return ntris
}

private fun countPolyVerts(p: IntArray, po: Int, nvp: Int): Int {
    for (i in 0 until nvp) if (p[po + i] == RC_MESH_NULL_IDX) return i
    return nvp
}

private fun uleft(a: IntArray, ao: Int, b: IntArray, bo: Int, c: IntArray, co: Int) =
    (b[bo] - a[ao]) * (c[co + 2] - a[ao + 2]) - (c[co] - a[ao]) * (b[bo + 2] - a[ao + 2]) < 0

/** Returns the merge value or -1; the shared edges are written to [e] (ea, eb). */
private fun getPolyMergeValue(pa: IntArray, pao: Int, pb: IntArray, pbo: Int, verts: IntArray, e: IntArray, nvp: Int): Int {
    val na = countPolyVerts(pa, pao, nvp)
    val nb = countPolyVerts(pb, pbo, nvp)
    if (na + nb - 2 > nvp) return -1
    var ea = -1
    var eb = -1
    for (i in 0 until na) {
        var va0 = pa[pao + i]
        var va1 = pa[pao + (i + 1) % na]
        if (va0 > va1) { val t = va0; va0 = va1; va1 = t }
        for (j in 0 until nb) {
            var vb0 = pb[pbo + j]
            var vb1 = pb[pbo + (j + 1) % nb]
            if (vb0 > vb1) { val t = vb0; vb0 = vb1; vb1 = t }
            if (va0 == vb0 && va1 == vb1) { ea = i; eb = j; break }
        }
    }
    e[0] = ea; e[1] = eb
    if (ea == -1 || eb == -1) return -1

    var va = pa[pao + (ea + na - 1) % na]
    var vb = pa[pao + ea]
    var vc = pb[pbo + (eb + 2) % nb]
    if (!uleft(verts, va * 3, verts, vb * 3, verts, vc * 3)) return -1
    va = pb[pbo + (eb + nb - 1) % nb]
    vb = pb[pbo + eb]
    vc = pa[pao + (ea + 2) % na]
    if (!uleft(verts, va * 3, verts, vb * 3, verts, vc * 3)) return -1
    va = pa[pao + ea]
    vb = pa[pao + (ea + 1) % na]
    val dx = verts[va * 3] - verts[vb * 3]
    val dy = verts[va * 3 + 2] - verts[vb * 3 + 2]
    return dx * dx + dy * dy
}

private fun mergePolyVerts(pa: IntArray, pao: Int, pb: IntArray, pbo: Int, ea: Int, eb: Int, tmp: IntArray, tmpo: Int, nvp: Int) {
    val na = countPolyVerts(pa, pao, nvp)
    val nb = countPolyVerts(pb, pbo, nvp)
    for (i in 0 until nvp) tmp[tmpo + i] = 0xffff
    var n = 0
    for (i in 0 until na - 1) tmp[tmpo + n++] = pa[pao + (ea + 1 + i) % na]
    for (i in 0 until nb - 1) tmp[tmpo + n++] = pb[pbo + (eb + 1 + i) % nb]
    for (i in 0 until nvp) pa[pao + i] = tmp[tmpo + i]
}

private fun pushFront(v: Int, arr: IntArray, an: Int): Int {
    val n = an + 1
    for (i in n - 1 downTo 1) arr[i] = arr[i - 1]
    arr[0] = v
    return n
}

private fun canRemoveVertex(mesh: PolyMesh, rem: Int): Boolean {
    val nvp = mesh.nvp
    var numTouchedVerts = 0
    var numRemainingEdges = 0
    for (i in 0 until mesh.npolys) {
        val p = i * nvp * 2
        val nv = countPolyVerts(mesh.polys, p, nvp)
        var numRemoved = 0
        var numVerts = 0
        for (j in 0 until nv) {
            if (mesh.polys[p + j] == rem) { numTouchedVerts++; numRemoved++ }
            numVerts++
        }
        if (numRemoved != 0) numRemainingEdges += numVerts - (numRemoved + 1)
    }
    if (numRemainingEdges <= 2) return false

    val maxEdges = numTouchedVerts * 2
    var nedges = 0
    val edges = IntArray(maxEdges * 3)
    for (i in 0 until mesh.npolys) {
        val p = i * nvp * 2
        val nv = countPolyVerts(mesh.polys, p, nvp)
        var k = nv - 1
        for (j in 0 until nv) {
            if (mesh.polys[p + j] == rem || mesh.polys[p + k] == rem) {
                var a = mesh.polys[p + j]
                var b = mesh.polys[p + k]
                if (b == rem) { val t = a; a = b; b = t }
                var exists = false
                for (m in 0 until nedges) {
                    if (edges[m * 3 + 1] == b) { edges[m * 3 + 2]++; exists = true }
                }
                if (!exists) {
                    edges[nedges * 3] = a
                    edges[nedges * 3 + 1] = b
                    edges[nedges * 3 + 2] = 1
                    nedges++
                }
            }
            k = j
        }
    }
    var numOpenEdges = 0
    for (i in 0 until nedges) if (edges[i * 3 + 2] < 2) numOpenEdges++
    return numOpenEdges <= 2
}

private fun removeVertex(mesh: PolyMesh, rem: Int, maxTris: Int): Boolean {
    val nvp = mesh.nvp
    var numRemovedVerts = 0
    for (i in 0 until mesh.npolys) {
        val p = i * nvp * 2
        val nv = countPolyVerts(mesh.polys, p, nvp)
        for (j in 0 until nv) if (mesh.polys[p + j] == rem) numRemovedVerts++
    }

    var nedges = 0
    val edges = IntArray(numRemovedVerts * nvp * 4)
    var nhole = 0
    val hole = IntArray(numRemovedVerts * nvp + 1)
    var nhreg = 0
    val hreg = IntArray(numRemovedVerts * nvp + 1)
    var nharea = 0
    val harea = IntArray(numRemovedVerts * nvp + 1)

    var i = 0
    while (i < mesh.npolys) {
        val p = i * nvp * 2
        val nv = countPolyVerts(mesh.polys, p, nvp)
        var hasRem = false
        for (j in 0 until nv) if (mesh.polys[p + j] == rem) hasRem = true
        if (hasRem) {
            var k = nv - 1
            for (j in 0 until nv) {
                if (mesh.polys[p + j] != rem && mesh.polys[p + k] != rem) {
                    edges[nedges * 4] = mesh.polys[p + k]
                    edges[nedges * 4 + 1] = mesh.polys[p + j]
                    edges[nedges * 4 + 2] = mesh.regs[i]
                    edges[nedges * 4 + 3] = mesh.areas[i]
                    nedges++
                }
                k = j
            }
            val p2 = (mesh.npolys - 1) * nvp * 2
            if (p != p2) for (q in 0 until nvp) mesh.polys[p + q] = mesh.polys[p2 + q]
            for (q in 0 until nvp) mesh.polys[p + nvp + q] = 0xffff
            mesh.regs[i] = mesh.regs[mesh.npolys - 1]
            mesh.areas[i] = mesh.areas[mesh.npolys - 1]
            mesh.npolys--
            --i
        }
        i++
    }

    for (v in rem until mesh.nverts - 1) {
        mesh.verts[v * 3] = mesh.verts[(v + 1) * 3]
        mesh.verts[v * 3 + 1] = mesh.verts[(v + 1) * 3 + 1]
        mesh.verts[v * 3 + 2] = mesh.verts[(v + 1) * 3 + 2]
    }
    mesh.nverts--

    for (pi in 0 until mesh.npolys) {
        val p = pi * nvp * 2
        val nv = countPolyVerts(mesh.polys, p, nvp)
        for (j in 0 until nv) if (mesh.polys[p + j] > rem) mesh.polys[p + j]--
    }
    for (e in 0 until nedges) {
        if (edges[e * 4] > rem) edges[e * 4]--
        if (edges[e * 4 + 1] > rem) edges[e * 4 + 1]--
    }
    if (nedges == 0) return true

    hole[nhole++] = edges[0]
    hreg[nhreg++] = edges[2]
    harea[nharea++] = edges[3]

    while (nedges != 0) {
        var match = false
        var e = 0
        while (e < nedges) {
            val ea = edges[e * 4]
            val eb = edges[e * 4 + 1]
            val r = edges[e * 4 + 2]
            val a = edges[e * 4 + 3]
            var add = false
            if (hole[0] == eb) {
                nhole = pushFront(ea, hole, nhole)
                nhreg = pushFront(r, hreg, nhreg)
                nharea = pushFront(a, harea, nharea)
                add = true
            } else if (hole[nhole - 1] == ea) {
                hole[nhole++] = eb
                hreg[nhreg++] = r
                harea[nharea++] = a
                add = true
            }
            if (add) {
                edges[e * 4] = edges[(nedges - 1) * 4]
                edges[e * 4 + 1] = edges[(nedges - 1) * 4 + 1]
                edges[e * 4 + 2] = edges[(nedges - 1) * 4 + 2]
                edges[e * 4 + 3] = edges[(nedges - 1) * 4 + 3]
                --nedges
                match = true
                --e
            }
            e++
        }
        if (!match) break
    }

    val tris = IntArray(nhole * 3)
    val tverts = IntArray(nhole * 4)
    val thole = IntArray(nhole)
    for (k in 0 until nhole) {
        val pi = hole[k]
        tverts[k * 4] = mesh.verts[pi * 3]
        tverts[k * 4 + 1] = mesh.verts[pi * 3 + 1]
        tverts[k * 4 + 2] = mesh.verts[pi * 3 + 2]
        tverts[k * 4 + 3] = 0
        thole[k] = k
    }
    var ntris = triangulate(nhole, tverts, thole, tris)
    if (ntris < 0) ntris = -ntris

    val polys = IntArray((ntris + 1) * nvp)
    val pregs = IntArray(ntris)
    val pareas = IntArray(ntris)
    val tmpPoly = ntris * nvp

    var npolys = 0
    for (k in 0 until ntris * nvp) polys[k] = 0xffff
    for (j in 0 until ntris) {
        val t0 = tris[j * 3]; val t1 = tris[j * 3 + 1]; val t2 = tris[j * 3 + 2]
        if (t0 != t1 && t0 != t2 && t1 != t2) {
            polys[npolys * nvp] = hole[t0] and 0xffff
            polys[npolys * nvp + 1] = hole[t1] and 0xffff
            polys[npolys * nvp + 2] = hole[t2] and 0xffff
            pregs[npolys] = if (hreg[t0] != hreg[t1] || hreg[t1] != hreg[t2]) RC_MULTIPLE_REGS else hreg[t0] and 0xffff
            pareas[npolys] = harea[t0] and 0xff
            npolys++
        }
    }
    if (npolys == 0) return true

    if (nvp > 3) {
        val e = IntArray(2)
        while (true) {
            var bestMergeVal = 0
            var bestPa = 0; var bestPb = 0; var bestEa = 0; var bestEb = 0
            for (j in 0 until npolys - 1) {
                for (k in j + 1 until npolys) {
                    val v = getPolyMergeValue(polys, j * nvp, polys, k * nvp, mesh.verts, e, nvp)
                    if (v > bestMergeVal) { bestMergeVal = v; bestPa = j; bestPb = k; bestEa = e[0]; bestEb = e[1] }
                }
            }
            if (bestMergeVal > 0) {
                mergePolyVerts(polys, bestPa * nvp, polys, bestPb * nvp, bestEa, bestEb, polys, tmpPoly, nvp)
                if (pregs[bestPa] != pregs[bestPb]) pregs[bestPa] = RC_MULTIPLE_REGS
                val last = (npolys - 1) * nvp
                if (bestPb * nvp != last) for (q in 0 until nvp) polys[bestPb * nvp + q] = polys[last + q]
                pregs[bestPb] = pregs[npolys - 1]
                pareas[bestPb] = pareas[npolys - 1]
                npolys--
            } else break
        }
    }

    for (k in 0 until npolys) {
        if (mesh.npolys >= maxTris) break
        val p = mesh.npolys * nvp * 2
        for (q in 0 until nvp * 2) mesh.polys[p + q] = 0xffff
        for (j in 0 until nvp) mesh.polys[p + j] = polys[k * nvp + j]
        mesh.regs[mesh.npolys] = pregs[k]
        mesh.areas[mesh.npolys] = pareas[k]
        mesh.npolys++
        if (mesh.npolys > maxTris) return false
    }
    return true
}

internal fun rcBuildPolyMesh(cset: ContourSet, nvp: Int): PolyMesh? {
    val mesh = PolyMesh(nvp)
    mesh.bmin = cset.bmin.copyOf()
    mesh.bmax = cset.bmax.copyOf()
    mesh.cs = cset.cs
    mesh.ch = cset.ch
    mesh.borderSize = cset.borderSize
    mesh.maxEdgeError = cset.maxError

    var maxVertices = 0
    var maxTris = 0
    var maxVertsPerCont = 0
    for (c in cset.conts) {
        if (c.nverts < 3) continue
        maxVertices += c.nverts
        maxTris += c.nverts - 2
        maxVertsPerCont = maxOf(maxVertsPerCont, c.nverts)
    }
    if (maxVertices >= 0xfffe) return null

    val vflags = IntArray(maxVertices + 1)
    mesh.verts = IntArray(maxVertices * 3)
    mesh.polys = IntArray(maxTris * nvp * 2) { 0xffff }
    mesh.regs = IntArray(maxTris)
    mesh.areas = IntArray(maxTris)
    mesh.nverts = 0
    mesh.npolys = 0
    mesh.maxpolys = maxTris

    val nextVert = IntArray(maxVertices)
    val firstVert = IntArray(VERTEX_BUCKET_COUNT) { -1 }
    val indices = IntArray(maxVertsPerCont)
    val tris = IntArray(maxVertsPerCont * 3)
    val polys = IntArray((maxVertsPerCont + 1) * nvp)
    val tmpPoly = maxVertsPerCont * nvp
    val nv = IntArray(1)
    val e = IntArray(2)

    for (cont in cset.conts) {
        if (cont.nverts < 3) continue
        for (j in 0 until cont.nverts) indices[j] = j
        var ntris = triangulate(cont.nverts, cont.verts, indices, tris)
        if (ntris <= 0) ntris = -ntris

        nv[0] = mesh.nverts
        for (j in 0 until cont.nverts) {
            val v = j * 4
            indices[j] = addVertex(cont.verts[v] and 0xffff, cont.verts[v + 1] and 0xffff, cont.verts[v + 2] and 0xffff, mesh.verts, firstVert, nextVert, nv)
            if ((cont.verts[v + 3] and RC_BORDER_VERTEX) != 0) vflags[indices[j]] = 1
        }
        mesh.nverts = nv[0]

        var npolys = 0
        for (k in 0 until maxVertsPerCont * nvp) polys[k] = 0xffff
        for (j in 0 until ntris) {
            val t0 = tris[j * 3]; val t1 = tris[j * 3 + 1]; val t2 = tris[j * 3 + 2]
            if (t0 != t1 && t0 != t2 && t1 != t2) {
                polys[npolys * nvp] = indices[t0]
                polys[npolys * nvp + 1] = indices[t1]
                polys[npolys * nvp + 2] = indices[t2]
                npolys++
            }
        }
        if (npolys == 0) continue

        if (nvp > 3) {
            while (true) {
                var bestMergeVal = 0
                var bestPa = 0; var bestPb = 0; var bestEa = 0; var bestEb = 0
                for (j in 0 until npolys - 1) {
                    for (k in j + 1 until npolys) {
                        val v = getPolyMergeValue(polys, j * nvp, polys, k * nvp, mesh.verts, e, nvp)
                        if (v > bestMergeVal) { bestMergeVal = v; bestPa = j; bestPb = k; bestEa = e[0]; bestEb = e[1] }
                    }
                }
                if (bestMergeVal > 0) {
                    mergePolyVerts(polys, bestPa * nvp, polys, bestPb * nvp, bestEa, bestEb, polys, tmpPoly, nvp)
                    val last = (npolys - 1) * nvp
                    if (bestPb * nvp != last) for (q in 0 until nvp) polys[bestPb * nvp + q] = polys[last + q]
                    npolys--
                } else break
            }
        }

        for (j in 0 until npolys) {
            val p = mesh.npolys * nvp * 2
            for (k in 0 until nvp) mesh.polys[p + k] = polys[j * nvp + k]
            mesh.regs[mesh.npolys] = cont.reg
            mesh.areas[mesh.npolys] = cont.area
            mesh.npolys++
            if (mesh.npolys > maxTris) return null
        }
    }

    var i = 0
    while (i < mesh.nverts) {
        if (vflags[i] != 0) {
            if (!canRemoveVertex(mesh, i)) { i++; continue }
            if (!removeVertex(mesh, i, maxTris)) return null
            for (j in i until mesh.nverts) vflags[j] = vflags[j + 1]
            --i
        }
        i++
    }

    buildMeshAdjacency(mesh.polys, mesh.npolys, mesh.nverts, nvp)

    if (mesh.borderSize > 0) {
        val w = cset.width
        val h = cset.height
        for (pi in 0 until mesh.npolys) {
            val p = pi * 2 * nvp
            for (j in 0 until nvp) {
                if (mesh.polys[p + j] == RC_MESH_NULL_IDX) break
                if (mesh.polys[p + nvp + j] != RC_MESH_NULL_IDX) continue
                var nj = j + 1
                if (nj >= nvp || mesh.polys[p + nj] == RC_MESH_NULL_IDX) nj = 0
                val va = mesh.polys[p + j] * 3
                val vb = mesh.polys[p + nj] * 3
                if (mesh.verts[va] == 0 && mesh.verts[vb] == 0) mesh.polys[p + nvp + j] = 0x8000 or 0
                else if (mesh.verts[va + 2] == h && mesh.verts[vb + 2] == h) mesh.polys[p + nvp + j] = 0x8000 or 1
                else if (mesh.verts[va] == w && mesh.verts[vb] == w) mesh.polys[p + nvp + j] = 0x8000 or 2
                else if (mesh.verts[va + 2] == 0 && mesh.verts[vb + 2] == 0) mesh.polys[p + nvp + j] = 0x8000 or 3
            }
        }
    }
    mesh.flags = IntArray(mesh.npolys)
    return mesh
}
