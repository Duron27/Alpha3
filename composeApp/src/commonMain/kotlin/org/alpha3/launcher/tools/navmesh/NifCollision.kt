// SPDX-License-Identifier: GPL-3.0-or-later
// Builds the collision shapes OpenMW's BulletNifLoader makes from a Morrowind NIF, in the same float and
// double arithmetic (osg::Matrixf is single precision, Bullet is double).
package org.alpha3.launcher.tools.navmesh

import kotlin.math.sqrt

/** The row-major 4x4 float matrix of osg::Matrixf, indexed [row * 4 + column]. */
private class Mat4(@JvmField val m: FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)) {
    /** this = this * other, as Matrixf::postMult. */
    fun postMult(o: Mat4) {
        val a = m
        val b = o.m
        for (row in 0 until 4) {
            val t0 = a[row * 4] * b[0] + a[row * 4 + 1] * b[4] + a[row * 4 + 2] * b[8] + a[row * 4 + 3] * b[12]
            val t1 = a[row * 4] * b[1] + a[row * 4 + 1] * b[5] + a[row * 4 + 2] * b[9] + a[row * 4 + 3] * b[13]
            val t2 = a[row * 4] * b[2] + a[row * 4 + 1] * b[6] + a[row * 4 + 2] * b[10] + a[row * 4 + 3] * b[14]
            val t3 = a[row * 4] * b[3] + a[row * 4 + 1] * b[7] + a[row * 4 + 2] * b[11] + a[row * 4 + 3] * b[15]
            a[row * 4] = t0; a[row * 4 + 1] = t1; a[row * 4 + 2] = t2; a[row * 4 + 3] = t3
        }
    }

    fun scale(): V3 {
        fun len(c: Int): Double {
            val x = m[c].toDouble(); val y = m[4 + c].toDouble(); val z = m[8 + c].toDouble()
            return sqrt(x * x + y * y + z * z)
        }
        return V3(len(0), len(1), len(2))
    }

    /** Matrixf::orthoNormalize(*this). */
    fun orthoNormalize() {
        for (c in 0 until 3) {
            val x = m[c]; val y = m[4 + c]; val z = m[8 + c]
            var mag = x * x + y * y + z * z
            val d1 = 1.0 - mag.toDouble()
            val d0 = 0.0 - mag.toDouble()
            val nearOne = if (d1 < 0.0) d1 >= -1e-6 else d1 <= 1e-6
            val nearZero = if (d0 < 0.0) d0 >= -1e-6 else d0 <= 1e-6
            if (!nearOne && !nearZero) {
                mag = sqrt(mag.toDouble()).toFloat()
                m[c] = x / mag; m[4 + c] = y / mag; m[8 + c] = z / mag
            }
        }
    }
}

private fun NifObj.toMatrix(): Mat4 {
    val r = Mat4()
    r.m[12] = trans[0]; r.m[13] = trans[1]; r.m[14] = trans[2]
    for (i in 0 until 3) for (j in 0 until 3) r.m[j * 4 + i] = rot[i * 3 + j] * scale
    return r
}

private class Args(
    var generateCollision: Boolean = false,
    var avoid: Boolean = false,
    var hasTriMarkers: Boolean = false,
    var collisionNode: NifObj? = null,
) {
    fun copy() = Args(generateCollision, avoid, hasTriMarkers, collisionNode)
}

private class CollisionLoader(val nif: NifFile) {
    var compound: CompoundShape? = null
    var avoidCompound: CompoundShape? = null
    var visual = 0

    fun obj(i: Int): NifObj? = if (i < 0) null else nif.records[i]

    fun extras(node: NifObj): List<NifObj> {
        val list = ArrayList<NifObj>()
        var e = obj(node.extra)
        while (e != null) { list.add(e); e = obj(e.next) }
        return list
    }

    fun findRootCollisionNode(node: NifObj, recursive: Boolean): NifObj? {
        for (i in node.children.indices.reversed()) {
            val child = obj(node.children[i]) ?: continue
            if (child.type == "RootCollisionNode") return child
            if (recursive && child.isNode) findRootCollisionNode(child, true)?.let { return it }
        }
        return null
    }

    fun handleRoot(node: NifObj, args: Args) {
        var recursiveRcn = false
        for (e in extras(node)) {
            if (e.type != "NiStringExtraData") continue
            val d = e.text
            if (d == "MRK") args.hasTriMarkers = true
            else if (d.startsWith("NC", ignoreCase = true)) {
                visual = if (d.length > 2 && d[2] == 'C') 2 else 1
            } else if (d == "RCN") recursiveRcn = true
        }
        if (node.isNode) args.collisionNode = findRootCollisionNode(node, recursiveRcn)
        val cn = args.collisionNode
        if (cn == null) args.generateCollision = true
        else if (cn.children.isEmpty()) {
            args.generateCollision = true
            visual = 2
        }
        handleNode(node, emptyList(), args)
    }

    /** [parents] runs from the closest parent outwards. */
    fun handleNode(node: NifObj, parents: List<NifObj>, argsIn: Args) {
        val args = argsIn.copy()
        if (node.type == "NiCollisionSwitch" && node.flags and 0x20 == 0) return
        if (node.type == "RootCollisionNode") {
            if (args.collisionNode === node && args.generateCollision && visual == 2) return
            if (args.collisionNode === node) args.generateCollision = true
        }
        if (node.type == "AvoidNode") args.avoid = true
        if (args.generateCollision && node.isGeometry) handleGeometry(node, parents, args)
        if (node.isNode) {
            val next = ArrayList<NifObj>(parents.size + 1)
            next.add(node)
            next.addAll(parents)
            for (c in node.children) {
                obj(c)?.let { handleNode(it, next, args) }
                if (node.type == "NiSwitchNode" || node.type == "NiFltAnimationNode") break
            }
        }
    }

    fun collisionMesh(g: NifObj): TriangleMesh? {
        if (g.type != "NiTriShape" && g.type != "NiTriStrips") return null
        val data = obj(g.data) ?: return null
        val v = data.vertices ?: return null
        if (v.isEmpty()) return null
        val vertexCount = v.size / 3
        val indices = ArrayList<Int>()
        fun add(a: Int, b: Int, c: Int) {
            if (a >= vertexCount || b >= vertexCount || c >= vertexCount) navFail("triangle index out of range")
            indices.add(a); indices.add(b); indices.add(c)
        }
        if (g.type == "NiTriShape") {
            if (data.numTriangles != 0) {
                var i = 0
                while (i + 2 < data.triangles.size) { add(data.triangles[i], data.triangles[i + 1], data.triangles[i + 2]); i += 3 }
            }
        } else if (data.numTriangles != 0) {
            for (strip in data.strips) {
                if (strip.size < 3) continue
                var b = strip[0]
                var c = strip[1]
                for (i in 2 until strip.size) {
                    val a = b
                    b = c
                    c = strip[i]
                    if (a == b || b == c || a == c) continue
                    if (i % 2 == 0) add(a, b, c) else add(a, c, b)
                }
            }
        }
        if (indices.isEmpty()) return null
        return TriangleMesh(v, indices.toIntArray())
    }

    fun handleGeometry(g: NifObj, parents: List<NifObj>, args: Args) {
        if (args.hasTriMarkers && g.name.startsWith("Tri EditorMarker", ignoreCase = true)) return
        val mesh = collisionMesh(g) ?: return
        val t = g.toMatrix()
        for (p in parents) t.postMult(p.toMatrix())
        val shape = ScaledTriangleMeshShape(mesh, t.scale())
        t.orthoNormalize()
        val basis = DoubleArray(9)
        for (i in 0 until 3) for (j in 0 until 3) basis[i * 3 + j] = t.m[j * 4 + i].toDouble()
        val trans = Transform(Basis(basis), V3(t.m[12].toDouble(), t.m[13].toDouble(), t.m[14].toDouble()))
        if (!args.avoid) {
            val c = compound ?: CompoundShape().also { compound = it }
            c.addChild(trans, shape)
        } else {
            val c = avoidCompound ?: CompoundShape().also { avoidCompound = it }
            c.addChild(trans, shape)
        }
    }
}

/** BulletNifLoader::load for the NIF [bytes]; throws [NavMeshException] when the file cannot be loaded. */
internal fun loadNavShape(fileName: String, bytes: ByteArray): NavShape {
    val nif = parseNif(bytes)
    val loader = CollisionLoader(nif)
    val roots = nif.roots.asList().mapNotNull { r -> nif.records.getOrNull(r)?.takeIf { it.isAVObject } }
    for (root in roots) loader.handleRoot(root, Args())
    return NavShape(fileName, Murmur3.fileHash(bytes), loader.compound, loader.avoidCompound, loader.visual)
}
