// SPDX-License-Identifier: GPL-3.0-or-later
// Kotlin port of Recast's RecastMeshDetail.cpp (zlib licence, Copyright (c) 2009-2010 Mikko Mononen).
package org.alpha3.launcher.tools.navmesh.recast

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

internal class PolyMeshDetail {
    /** Per polygon: first vertex, vertex count, first triangle, triangle count. */
    var meshes = IntArray(0)
    var nmeshes = 0
    var verts = FloatArray(0)
    var nverts = 0
    /** Four bytes per triangle: three vertex indices and the edge flags. */
    var tris = IntArray(0)
    var ntris = 0
}

private const val RC_UNSET_HEIGHT = 0xffff
private const val FLT_MAX = Float.MAX_VALUE

private class HeightPatch {
    var data = IntArray(0)
    var xmin = 0
    var ymin = 0
    var width = 0
    var height = 0
}

private fun vdot2(a: FloatArray, ao: Int, b: FloatArray, bo: Int) = a[ao] * b[bo] + a[ao + 2] * b[bo + 2]

private fun vdistSq2(p: FloatArray, po: Int, q: FloatArray, qo: Int): Float {
    val dx = q[qo] - p[po]
    val dy = q[qo + 2] - p[po + 2]
    return dx * dx + dy * dy
}

private fun vdist2(p: FloatArray, po: Int, q: FloatArray, qo: Int) = sqrt(vdistSq2(p, po, q, qo))

private fun vcross2(p1: FloatArray, o1: Int, p2: FloatArray, o2: Int, p3: FloatArray, o3: Int): Float {
    val u1 = p2[o2] - p1[o1]
    val v1 = p2[o2 + 2] - p1[o1 + 2]
    val u2 = p3[o3] - p1[o1]
    val v2 = p3[o3 + 2] - p1[o1 + 2]
    return u1 * v2 - v1 * u2
}

/** Circumcircle in the xz plane; writes the centre to [c] and its radius to [r] (0 when degenerate). */
private fun circumCircle(p: FloatArray, p1: Int, p2: Int, p3: Int, c: FloatArray, r: FloatArray): Boolean {
    val eps = 1e-6f
    val v1 = floatArrayOf(0f, 0f, 0f)
    val v2 = floatArrayOf(p[p2] - p[p1], p[p2 + 1] - p[p1 + 1], p[p2 + 2] - p[p1 + 2])
    val v3 = floatArrayOf(p[p3] - p[p1], p[p3 + 1] - p[p1 + 1], p[p3 + 2] - p[p1 + 2])
    val cp = vcross2(v1, 0, v2, 0, v3, 0)
    if (abs(cp) > eps) {
        val v1Sq = vdot2(v1, 0, v1, 0)
        val v2Sq = vdot2(v2, 0, v2, 0)
        val v3Sq = vdot2(v3, 0, v3, 0)
        c[0] = (v1Sq * (v2[2] - v3[2]) + v2Sq * (v3[2] - v1[2]) + v3Sq * (v1[2] - v2[2])) / (2 * cp)
        c[1] = 0f
        c[2] = (v1Sq * (v3[0] - v2[0]) + v2Sq * (v1[0] - v3[0]) + v3Sq * (v2[0] - v1[0])) / (2 * cp)
        r[0] = vdist2(c, 0, v1, 0)
        c[0] += p[p1]; c[1] += p[p1 + 1]; c[2] += p[p1 + 2]
        return true
    }
    c[0] = p[p1]; c[1] = p[p1 + 1]; c[2] = p[p1 + 2]
    r[0] = 0f
    return false
}

private fun distPtTri(p: FloatArray, po: Int, a: FloatArray, ao: Int, b: FloatArray, bo: Int, c: FloatArray, co: Int): Float {
    val v0 = floatArrayOf(c[co] - a[ao], c[co + 1] - a[ao + 1], c[co + 2] - a[ao + 2])
    val v1 = floatArrayOf(b[bo] - a[ao], b[bo + 1] - a[ao + 1], b[bo + 2] - a[ao + 2])
    val v2 = floatArrayOf(p[po] - a[ao], p[po + 1] - a[ao + 1], p[po + 2] - a[ao + 2])
    val dot00 = vdot2(v0, 0, v0, 0)
    val dot01 = vdot2(v0, 0, v1, 0)
    val dot02 = vdot2(v0, 0, v2, 0)
    val dot11 = vdot2(v1, 0, v1, 0)
    val dot12 = vdot2(v1, 0, v2, 0)
    val invDenom = 1.0f / (dot00 * dot11 - dot01 * dot01)
    val u = (dot11 * dot02 - dot01 * dot12) * invDenom
    val v = (dot00 * dot12 - dot01 * dot02) * invDenom
    val eps = 1e-4f
    if (u >= -eps && v >= -eps && (u + v) <= 1 + eps) {
        val y = a[ao + 1] + v0[1] * u + v1[1] * v
        return abs(y - p[po + 1])
    }
    return FLT_MAX
}

private fun distancePtSeg(pt: FloatArray, pto: Int, p: FloatArray, po: Int, q: FloatArray, qo: Int): Float {
    val pqx = q[qo] - p[po]
    val pqy = q[qo + 1] - p[po + 1]
    val pqz = q[qo + 2] - p[po + 2]
    var dx = pt[pto] - p[po]
    var dy = pt[pto + 1] - p[po + 1]
    var dz = pt[pto + 2] - p[po + 2]
    val d = pqx * pqx + pqy * pqy + pqz * pqz
    var t = pqx * dx + pqy * dy + pqz * dz
    if (d > 0) t /= d
    if (t < 0) t = 0f else if (t > 1) t = 1f
    dx = p[po] + t * pqx - pt[pto]
    dy = p[po + 1] + t * pqy - pt[pto + 1]
    dz = p[po + 2] + t * pqz - pt[pto + 2]
    return dx * dx + dy * dy + dz * dz
}

private fun distancePtSeg2d(pt: FloatArray, pto: Int, p: FloatArray, po: Int, q: FloatArray, qo: Int): Float {
    val pqx = q[qo] - p[po]
    val pqz = q[qo + 2] - p[po + 2]
    var dx = pt[pto] - p[po]
    var dz = pt[pto + 2] - p[po + 2]
    val d = pqx * pqx + pqz * pqz
    var t = pqx * dx + pqz * dz
    if (d > 0) t /= d
    if (t < 0) t = 0f else if (t > 1) t = 1f
    dx = p[po] + t * pqx - pt[pto]
    dz = p[po + 2] + t * pqz - pt[pto + 2]
    return dx * dx + dz * dz
}

private fun distToTriMesh(p: FloatArray, po: Int, verts: FloatArray, tris: IntVec, ntris: Int): Float {
    var dmin = FLT_MAX
    for (i in 0 until ntris) {
        val d = distPtTri(p, po, verts, tris[i * 4] * 3, verts, tris[i * 4 + 1] * 3, verts, tris[i * 4 + 2] * 3)
        if (d < dmin) dmin = d
    }
    if (dmin == FLT_MAX) return -1f
    return dmin
}

private fun distToPoly(nvert: Int, verts: FloatArray, p: FloatArray): Float {
    var dmin = FLT_MAX
    var c = false
    var j = nvert - 1
    for (i in 0 until nvert) {
        val vi = i * 3
        val vj = j * 3
        if (((verts[vi + 2] > p[2]) != (verts[vj + 2] > p[2])) &&
            (p[0] < (verts[vj] - verts[vi]) * (p[2] - verts[vi + 2]) / (verts[vj + 2] - verts[vi + 2]) + verts[vi])
        ) c = !c
        dmin = minOf(dmin, distancePtSeg2d(p, 0, verts, vj, verts, vi))
        j = i
    }
    return if (c) -dmin else dmin
}

private fun getHeight(fx: Float, fy: Float, fz: Float, ics: Float, ch: Float, radius: Int, hp: HeightPatch): Int {
    var ix = floor(fx * ics + 0.01f).toInt()
    var iz = floor(fz * ics + 0.01f).toInt()
    ix = (ix - hp.xmin).coerceIn(0, hp.width - 1)
    iz = (iz - hp.ymin).coerceIn(0, hp.height - 1)
    var h = hp.data[ix + iz * hp.width]
    if (h == RC_UNSET_HEIGHT) {
        var x = 1; var z = 0; var dx = 1; var dz = 0
        val maxSize = radius * 2 + 1
        val maxIter = maxSize * maxSize - 1
        var nextRingIterStart = 8
        var nextRingIters = 16
        var dmin = FLT_MAX
        for (i in 0 until maxIter) {
            val nx = ix + x
            val nz = iz + z
            if (nx >= 0 && nz >= 0 && nx < hp.width && nz < hp.height) {
                val nh = hp.data[nx + nz * hp.width]
                if (nh != RC_UNSET_HEIGHT) {
                    val d = abs(nh.toFloat() * ch - fy)
                    if (d < dmin) { h = nh; dmin = d }
                }
            }
            if (i + 1 == nextRingIterStart) {
                if (h != RC_UNSET_HEIGHT) break
                nextRingIterStart += nextRingIters
                nextRingIters += 8
            }
            if ((x == z) || ((x < 0) && (x == -z)) || ((x > 0) && (x == 1 - z))) {
                val tmp = dx
                dx = -dz
                dz = tmp
            }
            x += dx
            z += dz
        }
    }
    return h
}

private const val EV_UNDEF = -1
private const val EV_HULL = -2

private fun findEdge(edges: IntVec, nedges: Int, s: Int, t: Int): Int {
    for (i in 0 until nedges) {
        val e = i * 4
        if ((edges[e] == s && edges[e + 1] == t) || (edges[e] == t && edges[e + 1] == s)) return i
    }
    return EV_UNDEF
}

private fun addEdge(edges: IntVec, nedges: IntArray, maxEdges: Int, s: Int, t: Int, l: Int, r: Int): Int {
    if (nedges[0] >= maxEdges) return EV_UNDEF
    val e = findEdge(edges, nedges[0], s, t)
    if (e == EV_UNDEF) {
        val o = nedges[0] * 4
        edges[o] = s; edges[o + 1] = t; edges[o + 2] = l; edges[o + 3] = r
        return nedges[0]++
    }
    return EV_UNDEF
}

private fun updateLeftFace(edges: IntVec, e: Int, s: Int, t: Int, f: Int) {
    val o = e * 4
    if (edges[o] == s && edges[o + 1] == t && edges[o + 2] == EV_UNDEF) edges[o + 2] = f
    else if (edges[o + 1] == s && edges[o] == t && edges[o + 3] == EV_UNDEF) edges[o + 3] = f
}

private fun overlapSegSeg2d(p: FloatArray, a: Int, b: Int, c: Int, d: Int): Boolean {
    val a1 = vcross2(p, a, p, b, p, d)
    val a2 = vcross2(p, a, p, b, p, c)
    if (a1 * a2 < 0.0f) {
        val a3 = vcross2(p, c, p, d, p, a)
        val a4 = a3 + a2 - a1
        if (a3 * a4 < 0.0f) return true
    }
    return false
}

private fun overlapEdges(pts: FloatArray, edges: IntVec, nedges: Int, s1: Int, t1: Int): Boolean {
    for (i in 0 until nedges) {
        val s0 = edges[i * 4]
        val t0 = edges[i * 4 + 1]
        if (s0 == s1 || s0 == t1 || t0 == s1 || t0 == t1) continue
        if (overlapSegSeg2d(pts, s0 * 3, t0 * 3, s1 * 3, t1 * 3)) return true
    }
    return false
}

private fun completeFacet(pts: FloatArray, npts: Int, edges: IntVec, nedges: IntArray, maxEdges: Int, nfaces: IntArray, e0: Int) {
    val eps = 1e-5f
    var e = e0
    val s: Int
    val t: Int
    if (edges[e * 4 + 2] == EV_UNDEF) {
        s = edges[e * 4]; t = edges[e * 4 + 1]
    } else if (edges[e * 4 + 3] == EV_UNDEF) {
        s = edges[e * 4 + 1]; t = edges[e * 4]
    } else return

    var pt = npts
    val c = floatArrayOf(0f, 0f, 0f)
    val cr = FloatArray(1)
    var r = -1f
    for (u in 0 until npts) {
        if (u == s || u == t) continue
        if (vcross2(pts, s * 3, pts, t * 3, pts, u * 3) > eps) {
            if (r < 0) {
                pt = u
                circumCircle(pts, s * 3, t * 3, u * 3, c, cr); r = cr[0]
                continue
            }
            val d = vdist2(c, 0, pts, u * 3)
            val tol = 0.001f
            if (d > r * (1 + tol)) {
                continue
            } else if (d < r * (1 - tol)) {
                pt = u
                circumCircle(pts, s * 3, t * 3, u * 3, c, cr); r = cr[0]
            } else {
                if (overlapEdges(pts, edges, nedges[0], s, u)) continue
                if (overlapEdges(pts, edges, nedges[0], t, u)) continue
                pt = u
                circumCircle(pts, s * 3, t * 3, u * 3, c, cr); r = cr[0]
            }
        }
    }

    if (pt < npts) {
        updateLeftFace(edges, e, s, t, nfaces[0])
        e = findEdge(edges, nedges[0], pt, s)
        if (e == EV_UNDEF) addEdge(edges, nedges, maxEdges, pt, s, nfaces[0], EV_UNDEF)
        else updateLeftFace(edges, e, pt, s, nfaces[0])
        e = findEdge(edges, nedges[0], t, pt)
        if (e == EV_UNDEF) addEdge(edges, nedges, maxEdges, t, pt, nfaces[0], EV_UNDEF)
        else updateLeftFace(edges, e, t, pt, nfaces[0])
        nfaces[0]++
    } else {
        updateLeftFace(edges, e, s, t, EV_HULL)
    }
}

private fun delaunayHull(npts: Int, pts: FloatArray, nhull: Int, hull: IntArray, tris: IntVec, edges: IntVec) {
    val nfaces = intArrayOf(0)
    val nedges = intArrayOf(0)
    val maxEdges = npts * 10
    edges.resize(maxEdges * 4)

    var j = nhull - 1
    for (i in 0 until nhull) {
        addEdge(edges, nedges, maxEdges, hull[j], hull[i], EV_HULL, EV_UNDEF)
        j = i
    }
    var currentEdge = 0
    while (currentEdge < nedges[0]) {
        if (edges[currentEdge * 4 + 2] == EV_UNDEF) completeFacet(pts, npts, edges, nedges, maxEdges, nfaces, currentEdge)
        if (edges[currentEdge * 4 + 3] == EV_UNDEF) completeFacet(pts, npts, edges, nedges, maxEdges, nfaces, currentEdge)
        currentEdge++
    }

    tris.resize(nfaces[0] * 4)
    for (i in 0 until nfaces[0] * 4) tris[i] = -1
    for (i in 0 until nedges[0]) {
        val e = i * 4
        if (edges[e + 3] >= 0) {
            val t = edges[e + 3] * 4
            if (tris[t] == -1) {
                tris[t] = edges[e]; tris[t + 1] = edges[e + 1]
            } else if (tris[t] == edges[e + 1]) tris[t + 2] = edges[e]
            else if (tris[t + 1] == edges[e]) tris[t + 2] = edges[e + 1]
        }
        if (edges[e + 2] >= 0) {
            val t = edges[e + 2] * 4
            if (tris[t] == -1) {
                tris[t] = edges[e + 1]; tris[t + 1] = edges[e]
            } else if (tris[t] == edges[e]) tris[t + 2] = edges[e + 1]
            else if (tris[t + 1] == edges[e + 1]) tris[t + 2] = edges[e]
        }
    }
    var i = 0
    while (i < tris.size / 4) {
        val t = i * 4
        if (tris[t] == -1 || tris[t + 1] == -1 || tris[t + 2] == -1) {
            tris[t] = tris[tris.size - 4]
            tris[t + 1] = tris[tris.size - 3]
            tris[t + 2] = tris[tris.size - 2]
            tris[t + 3] = tris[tris.size - 1]
            tris.resize(tris.size - 4)
            --i
        }
        i++
    }
}

private fun polyMinExtent(verts: FloatArray, nverts: Int): Float {
    var minDist = FLT_MAX
    for (i in 0 until nverts) {
        val ni = (i + 1) % nverts
        var maxEdgeDist = 0f
        for (j in 0 until nverts) {
            if (j == i || j == ni) continue
            val d = distancePtSeg2d(verts, j * 3, verts, i * 3, verts, ni * 3)
            maxEdgeDist = maxOf(maxEdgeDist, d)
        }
        minDist = minOf(minDist, maxEdgeDist)
    }
    return sqrt(minDist)
}

private fun triangulateHull(verts: FloatArray, nhull: Int, hull: IntArray, nin: Int, tris: IntVec) {
    var start = 0
    var left = 1
    var right = nhull - 1
    var dmin = FLT_MAX
    for (i in 0 until nhull) {
        if (hull[i] >= nin) continue
        val pi = prev(i, nhull)
        val ni = next(i, nhull)
        val pv = hull[pi] * 3
        val cv = hull[i] * 3
        val nv = hull[ni] * 3
        val d = vdist2(verts, pv, verts, cv) + vdist2(verts, cv, verts, nv) + vdist2(verts, nv, verts, pv)
        if (d < dmin) { start = i; left = ni; right = pi; dmin = d }
    }
    tris.add(hull[start]); tris.add(hull[left]); tris.add(hull[right]); tris.add(0)
    while (next(left, nhull) != right) {
        val nleft = next(left, nhull)
        val nright = prev(right, nhull)
        val cvleft = hull[left] * 3
        val nvleft = hull[nleft] * 3
        val cvright = hull[right] * 3
        val nvright = hull[nright] * 3
        val dleft = vdist2(verts, cvleft, verts, nvleft) + vdist2(verts, nvleft, verts, cvright)
        val dright = vdist2(verts, cvright, verts, nvright) + vdist2(verts, cvleft, verts, nvright)
        if (dleft < dright) {
            tris.add(hull[left]); tris.add(hull[nleft]); tris.add(hull[right]); tris.add(0)
            left = nleft
        } else {
            tris.add(hull[left]); tris.add(hull[nright]); tris.add(hull[right]); tris.add(0)
            right = nright
        }
    }
}

private fun getJitterX(i: Int): Float = (((i * 0x8da6b343.toInt()) and 0xffff) / 65535.0f * 2.0f) - 1.0f
private fun getJitterY(i: Int): Float = (((i * 0xd8163841.toInt()) and 0xffff) / 65535.0f * 2.0f) - 1.0f

private fun onHull(a: Int, b: Int, nhull: Int, hull: IntArray): Boolean {
    if (a >= nhull || b >= nhull) return false
    var j = nhull - 1
    for (i in 0 until nhull) {
        if (a == hull[j] && b == hull[i]) return true
        j = i
    }
    return false
}

private fun setTriFlags(tris: IntVec, nhull: Int, hull: IntArray) {
    val boundary = 0x1
    var i = 0
    while (i < tris.size) {
        val a = tris[i]; val b = tris[i + 1]; val c = tris[i + 2]
        var flags = 0
        flags = flags or ((if (onHull(a, b, nhull, hull)) boundary else 0) shl 0)
        flags = flags or ((if (onHull(b, c, nhull, hull)) boundary else 0) shl 2)
        flags = flags or ((if (onHull(c, a, nhull, hull)) boundary else 0) shl 4)
        tris[i + 3] = flags
        i += 4
    }
}

private const val MAX_VERTS = 127
private const val MAX_TRIS = 255
private const val MAX_VERTS_PER_EDGE = 32

/** Returns the number of vertices written to [verts]; triangles go to [tris]. */
private fun buildPolyDetail(
    inp: FloatArray, nin: Int, sampleDist: Float, sampleMaxError: Float, heightSearchRadius: Int,
    chf: CompactHeightfield, hp: HeightPatch, verts: FloatArray, tris: IntVec, edges: IntVec, samples: IntVec,
): Int {
    val edge = FloatArray((MAX_VERTS_PER_EDGE + 1) * 3)
    val hull = IntArray(MAX_VERTS)
    var nhull = 0
    var nverts = nin
    for (i in 0 until nin * 3) verts[i] = inp[i]
    edges.clear()
    tris.clear()

    val cs = chf.cs
    val ics = 1.0f / cs
    val minExtent = polyMinExtent(verts, nverts)

    if (sampleDist > 0) {
        var j = nin - 1
        for (i in 0 until nin) {
            var vj = j * 3
            var vi = i * 3
            var swapped = false
            if (abs(inp[vj] - inp[vi]) < 1e-6f) {
                if (inp[vj + 2] > inp[vi + 2]) { val t = vj; vj = vi; vi = t; swapped = true }
            } else {
                if (inp[vj] > inp[vi]) { val t = vj; vj = vi; vi = t; swapped = true }
            }
            val dx = inp[vi] - inp[vj]
            val dy = inp[vi + 1] - inp[vj + 1]
            val dz = inp[vi + 2] - inp[vj + 2]
            val d = sqrt(dx * dx + dz * dz)
            var nn = 1 + floor(d / sampleDist).toInt()
            if (nn >= MAX_VERTS_PER_EDGE) nn = MAX_VERTS_PER_EDGE - 1
            if (nverts + nn >= MAX_VERTS) nn = MAX_VERTS - 1 - nverts

            for (k in 0..nn) {
                val u = k.toFloat() / nn.toFloat()
                val pos = k * 3
                edge[pos] = inp[vj] + dx * u
                edge[pos + 1] = inp[vj + 1] + dy * u
                edge[pos + 2] = inp[vj + 2] + dz * u
                edge[pos + 1] = getHeight(edge[pos], edge[pos + 1], edge[pos + 2], ics, chf.ch, heightSearchRadius, hp).toFloat() * chf.ch
            }
            val idx = IntArray(MAX_VERTS_PER_EDGE)
            idx[0] = 0; idx[1] = nn
            var nidx = 2
            var k = 0
            while (k < nidx - 1) {
                val a = idx[k]
                val b = idx[k + 1]
                var maxd = 0f
                var maxi = -1
                for (m in a + 1 until b) {
                    val dev = distancePtSeg(edge, m * 3, edge, a * 3, edge, b * 3)
                    if (dev > maxd) { maxd = dev; maxi = m }
                }
                if (maxi != -1 && maxd > sampleMaxError * sampleMaxError) {
                    for (m in nidx downTo k + 1) idx[m] = idx[m - 1]
                    idx[k + 1] = maxi
                    nidx++
                } else {
                    ++k
                }
            }

            hull[nhull++] = j
            if (swapped) {
                for (kk in nidx - 2 downTo 1) {
                    for (q in 0 until 3) verts[nverts * 3 + q] = edge[idx[kk] * 3 + q]
                    hull[nhull++] = nverts
                    nverts++
                }
            } else {
                for (kk in 1 until nidx - 1) {
                    for (q in 0 until 3) verts[nverts * 3 + q] = edge[idx[kk] * 3 + q]
                    hull[nhull++] = nverts
                    nverts++
                }
            }
            j = i
        }
    }

    if (minExtent < sampleDist * 2) {
        triangulateHull(verts, nhull, hull, nin, tris)
        setTriFlags(tris, nhull, hull)
        return nverts
    }

    triangulateHull(verts, nhull, hull, nin, tris)
    if (tris.size == 0) return nverts

    if (sampleDist > 0) {
        val bmin = floatArrayOf(inp[0], inp[1], inp[2])
        val bmax = floatArrayOf(inp[0], inp[1], inp[2])
        for (i in 1 until nin) {
            for (q in 0 until 3) {
                bmin[q] = minOf(bmin[q], inp[i * 3 + q])
                bmax[q] = maxOf(bmax[q], inp[i * 3 + q])
            }
        }
        val x0 = floor(bmin[0] / sampleDist).toInt()
        val x1 = ceil(bmax[0] / sampleDist).toInt()
        val z0 = floor(bmin[2] / sampleDist).toInt()
        val z1 = ceil(bmax[2] / sampleDist).toInt()
        samples.clear()
        val pt = FloatArray(3)
        for (z in z0 until z1) for (x in x0 until x1) {
            pt[0] = x * sampleDist
            pt[1] = (bmax[1] + bmin[1]) * 0.5f
            pt[2] = z * sampleDist
            if (distToPoly(nin, inp, pt) > -sampleDist / 2) continue
            samples.add(x)
            samples.add(getHeight(pt[0], pt[1], pt[2], ics, chf.ch, heightSearchRadius, hp))
            samples.add(z)
            samples.add(0)
        }

        val nsamples = samples.size / 4
        val bestpt = FloatArray(3)
        val spt = FloatArray(3)
        for (iter in 0 until nsamples) {
            if (nverts >= MAX_VERTS) break
            bestpt[0] = 0f; bestpt[1] = 0f; bestpt[2] = 0f
            var bestd = 0f
            var besti = -1
            for (i in 0 until nsamples) {
                val s = i * 4
                if (samples[s + 3] != 0) continue
                spt[0] = samples[s] * sampleDist + getJitterX(i) * cs * 0.1f
                spt[1] = samples[s + 1] * chf.ch
                spt[2] = samples[s + 2] * sampleDist + getJitterY(i) * cs * 0.1f
                val d = distToTriMesh(spt, 0, verts, tris, tris.size / 4)
                if (d < 0) continue
                if (d > bestd) {
                    bestd = d
                    besti = i
                    bestpt[0] = spt[0]; bestpt[1] = spt[1]; bestpt[2] = spt[2]
                }
            }
            if (bestd <= sampleMaxError || besti == -1) break
            samples[besti * 4 + 3] = 1
            for (q in 0 until 3) verts[nverts * 3 + q] = bestpt[q]
            nverts++
            edges.clear()
            tris.clear()
            delaunayHull(nverts, verts, nhull, hull, tris, edges)
        }
    }

    val ntris = tris.size / 4
    if (ntris > MAX_TRIS) tris.resize(MAX_TRIS * 4)
    setTriFlags(tris, nhull, hull)
    return nverts
}

private fun seedArrayWithPolyCenter(chf: CompactHeightfield, poly: IntArray, polyOff: Int, npoly: Int, verts: IntArray, bs: Int, hp: HeightPatch, array: IntVec) {
    val offset = intArrayOf(0, 0, -1, -1, 0, -1, 1, -1, 1, 0, 1, 1, 0, 1, -1, 1, -1, 0)
    var startCellX = 0
    var startCellY = 0
    var startSpanIndex = -1
    var dmin = RC_UNSET_HEIGHT
    var j = 0
    while (j < npoly && dmin > 0) {
        var k = 0
        while (k < 9 && dmin > 0) {
            val ax = verts[poly[polyOff + j] * 3] + offset[k * 2]
            val ay = verts[poly[polyOff + j] * 3 + 1]
            val az = verts[poly[polyOff + j] * 3 + 2] + offset[k * 2 + 1]
            if (ax < hp.xmin || ax >= hp.xmin + hp.width || az < hp.ymin || az >= hp.ymin + hp.height) { k++; continue }
            val c = (ax + bs) + (az + bs) * chf.width
            var i = chf.cellIndex[c]
            val ni = chf.cellIndex[c] + chf.cellCount[c]
            while (i < ni && dmin > 0) {
                val d = abs(ay - chf.y[i])
                if (d < dmin) { startCellX = ax; startCellY = az; startSpanIndex = i; dmin = d }
                i++
            }
            k++
        }
        j++
    }

    var pcx = 0
    var pcy = 0
    for (q in 0 until npoly) {
        pcx += verts[poly[polyOff + q] * 3]
        pcy += verts[poly[polyOff + q] * 3 + 2]
    }
    pcx /= npoly
    pcy /= npoly

    array.clear()
    array.add(startCellX); array.add(startCellY); array.add(startSpanIndex)

    val dirs = intArrayOf(0, 1, 2, 3)
    hp.data.fill(0, 0, hp.width * hp.height)
    var cx = -1
    var cy = -1
    var ci = -1
    while (true) {
        if (array.size < 3) break
        ci = array.data[--array.size]
        cy = array.data[--array.size]
        cx = array.data[--array.size]
        if (cx == pcx && cy == pcy) break

        val directDir = if (cx == pcx) rcGetDirForOffset(0, if (pcy > cy) 1 else -1) else rcGetDirForOffset(if (pcx > cx) 1 else -1, 0)
        run { val t = dirs[directDir]; dirs[directDir] = dirs[3]; dirs[3] = t }
        for (i in 0 until 4) {
            val dir = dirs[i]
            if (chf.getCon(ci, dir) == RC_NOT_CONNECTED) continue
            val newX = cx + rcGetDirOffsetX(dir)
            val newY = cy + rcGetDirOffsetY(dir)
            val hpx = newX - hp.xmin
            val hpy = newY - hp.ymin
            if (hpx < 0 || hpx >= hp.width || hpy < 0 || hpy >= hp.height) continue
            if (hp.data[hpx + hpy * hp.width] != 0) continue
            hp.data[hpx + hpy * hp.width] = 1
            array.add(newX); array.add(newY)
            array.add(chf.cellIndex[(newX + bs) + (newY + bs) * chf.width] + chf.getCon(ci, dir))
        }
        run { val t = dirs[directDir]; dirs[directDir] = dirs[3]; dirs[3] = t }
    }

    array.clear()
    array.add(cx + bs); array.add(cy + bs); array.add(ci)
    hp.data.fill(0xffff, 0, hp.width * hp.height)
    hp.data[cx - hp.xmin + (cy - hp.ymin) * hp.width] = chf.y[ci]
}

private fun getHeightData(chf: CompactHeightfield, poly: IntArray, polyOff: Int, npoly: Int, verts: IntArray, bs: Int, hp: HeightPatch, queue: IntVec, region: Int) {
    queue.clear()
    hp.data.fill(0xffff, 0, hp.width * hp.height)
    var empty = true

    if (region != RC_MULTIPLE_REGS) {
        for (hy in 0 until hp.height) {
            val y = hp.ymin + hy + bs
            for (hx in 0 until hp.width) {
                val x = hp.xmin + hx + bs
                val c = x + y * chf.width
                for (i in chf.cellIndex[c] until chf.cellIndex[c] + chf.cellCount[c]) {
                    if (chf.reg[i] == region) {
                        hp.data[hx + hy * hp.width] = chf.y[i]
                        empty = false
                        var border = false
                        for (dir in 0 until 4) {
                            if (chf.getCon(i, dir) != RC_NOT_CONNECTED) {
                                val ax = x + rcGetDirOffsetX(dir)
                                val ay = y + rcGetDirOffsetY(dir)
                                val ai = chf.cellIndex[ax + ay * chf.width] + chf.getCon(i, dir)
                                if (chf.reg[ai] != region) { border = true; break }
                            }
                        }
                        if (border) { queue.add(x); queue.add(y); queue.add(i) }
                        break
                    }
                }
            }
        }
    }

    if (empty) seedArrayWithPolyCenter(chf, poly, polyOff, npoly, verts, bs, hp, queue)

    val retractSize = 256
    var head = 0
    while (head * 3 < queue.size) {
        val cx = queue[head * 3]
        val cy = queue[head * 3 + 1]
        val ci = queue[head * 3 + 2]
        head++
        if (head >= retractSize) {
            head = 0
            if (queue.size > retractSize * 3) queue.data.copyInto(queue.data, 0, retractSize * 3, queue.size)
            queue.resize(queue.size - retractSize * 3)
        }
        for (dir in 0 until 4) {
            if (chf.getCon(ci, dir) == RC_NOT_CONNECTED) continue
            val ax = cx + rcGetDirOffsetX(dir)
            val ay = cy + rcGetDirOffsetY(dir)
            val hx = ax - hp.xmin - bs
            val hy = ay - hp.ymin - bs
            if (hx < 0 || hx >= hp.width || hy < 0 || hy >= hp.height) continue
            if (hp.data[hx + hy * hp.width] != RC_UNSET_HEIGHT) continue
            val ai = chf.cellIndex[ax + ay * chf.width] + chf.getCon(ci, dir)
            hp.data[hx + hy * hp.width] = chf.y[ai]
            queue.add(ax); queue.add(ay); queue.add(ai)
        }
    }
}

internal fun rcBuildPolyMeshDetail(mesh: PolyMesh, chf: CompactHeightfield, sampleDist: Float, sampleMaxError: Float): PolyMeshDetail? {
    val dmesh = PolyMeshDetail()
    if (mesh.nverts == 0 || mesh.npolys == 0) return dmesh

    val nvp = mesh.nvp
    val cs = mesh.cs
    val ch = mesh.ch
    val orig = mesh.bmin
    val borderSize = mesh.borderSize
    val heightSearchRadius = maxOf(1, ceil(mesh.maxEdgeError).toInt())

    val edges = IntVec(64)
    val tris = IntVec(512)
    val arr = IntVec(512)
    val samples = IntVec(512)
    val verts = FloatArray(256 * 3)
    val hp = HeightPatch()
    var nPolyVerts = 0
    var maxhw = 0
    var maxhh = 0
    val bounds = IntArray(mesh.npolys * 4)
    val poly = FloatArray(nvp * 3)

    for (i in 0 until mesh.npolys) {
        val p = i * nvp * 2
        var xmin = chf.width
        var xmax = 0
        var ymin = chf.height
        var ymax = 0
        for (j in 0 until nvp) {
            if (mesh.polys[p + j] == RC_MESH_NULL_IDX) break
            val v = mesh.polys[p + j] * 3
            xmin = minOf(xmin, mesh.verts[v])
            xmax = maxOf(xmax, mesh.verts[v])
            ymin = minOf(ymin, mesh.verts[v + 2])
            ymax = maxOf(ymax, mesh.verts[v + 2])
            nPolyVerts++
        }
        xmin = maxOf(0, xmin - 1)
        xmax = minOf(chf.width, xmax + 1)
        ymin = maxOf(0, ymin - 1)
        ymax = minOf(chf.height, ymax + 1)
        bounds[i * 4] = xmin; bounds[i * 4 + 1] = xmax; bounds[i * 4 + 2] = ymin; bounds[i * 4 + 3] = ymax
        if (xmin >= xmax || ymin >= ymax) continue
        maxhw = maxOf(maxhw, xmax - xmin)
        maxhh = maxOf(maxhh, ymax - ymin)
    }

    hp.data = IntArray(maxOf(1, maxhw * maxhh))
    dmesh.nmeshes = mesh.npolys
    dmesh.meshes = IntArray(dmesh.nmeshes * 4)
    var vcap = nPolyVerts + nPolyVerts / 2
    var tcap = vcap * 2
    dmesh.verts = FloatArray(vcap * 3)
    dmesh.tris = IntArray(tcap * 4)

    for (i in 0 until mesh.npolys) {
        val p = i * nvp * 2
        var npoly = 0
        for (j in 0 until nvp) {
            if (mesh.polys[p + j] == RC_MESH_NULL_IDX) break
            val v = mesh.polys[p + j] * 3
            poly[j * 3] = mesh.verts[v].toFloat() * cs
            poly[j * 3 + 1] = mesh.verts[v + 1].toFloat() * ch
            poly[j * 3 + 2] = mesh.verts[v + 2].toFloat() * cs
            npoly++
        }
        hp.xmin = bounds[i * 4]
        hp.ymin = bounds[i * 4 + 2]
        hp.width = bounds[i * 4 + 1] - bounds[i * 4]
        hp.height = bounds[i * 4 + 3] - bounds[i * 4 + 2]
        getHeightData(chf, mesh.polys, p, npoly, mesh.verts, borderSize, hp, arr, mesh.regs[i])

        val nverts = buildPolyDetail(poly, npoly, sampleDist, sampleMaxError, heightSearchRadius, chf, hp, verts, tris, edges, samples)

        for (j in 0 until nverts) {
            verts[j * 3] += orig[0]
            verts[j * 3 + 1] += orig[1] + chf.ch
            verts[j * 3 + 2] += orig[2]
        }
        val ntris = tris.size / 4

        dmesh.meshes[i * 4] = dmesh.nverts
        dmesh.meshes[i * 4 + 1] = nverts
        dmesh.meshes[i * 4 + 2] = dmesh.ntris
        dmesh.meshes[i * 4 + 3] = ntris

        if (dmesh.nverts + nverts > vcap) {
            while (dmesh.nverts + nverts > vcap) vcap += 256
            dmesh.verts = dmesh.verts.copyOf(vcap * 3)
        }
        for (j in 0 until nverts) {
            dmesh.verts[dmesh.nverts * 3] = verts[j * 3]
            dmesh.verts[dmesh.nverts * 3 + 1] = verts[j * 3 + 1]
            dmesh.verts[dmesh.nverts * 3 + 2] = verts[j * 3 + 2]
            dmesh.nverts++
        }
        if (dmesh.ntris + ntris > tcap) {
            while (dmesh.ntris + ntris > tcap) tcap += 256
            dmesh.tris = dmesh.tris.copyOf(tcap * 4)
        }
        for (j in 0 until ntris) {
            for (q in 0 until 4) dmesh.tris[dmesh.ntris * 4 + q] = tris[j * 4 + q] and 0xff
            dmesh.ntris++
        }
    }
    return dmesh
}
