// SPDX-License-Identifier: GPL-3.0-or-later
// The part of Bullet's collision shapes (zlib licence, Copyright (c) 2003-2013 Erwin Coumans) that OpenMW's
// navmesh generation touches: vectors, transforms, scaled triangle meshes and compound shapes. Bullet is
// built with double precision for OpenMW, so everything here is Double, in Bullet's operation order.
package org.alpha3.launcher.tools.navmesh

import kotlin.jvm.JvmField
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal class V3(@JvmField val x: Double, @JvmField val y: Double, @JvmField val z: Double) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(o: V3) = V3(x * o.x, y * o.y, z * o.z)
    operator fun div(o: V3) = V3(x / o.x, y / o.y, z / o.z)
    operator fun times(s: Double) = V3(x * s, y * s, z * s)
    fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    fun length() = sqrt(dot(this))

    companion object {
        val ONE = V3(1.0, 1.0, 1.0)
    }
}

/** A 3x3 matrix stored by rows, as btMatrix3x3. */
internal class Basis(@JvmField val m: DoubleArray) {
    fun row(i: Int) = V3(m[i * 3], m[i * 3 + 1], m[i * 3 + 2])

    fun absolute() = Basis(DoubleArray(9) { abs(m[it]) })

    operator fun times(o: Basis): Basis {
        val r = DoubleArray(9)
        for (i in 0 until 3) for (j in 0 until 3)
            r[i * 3 + j] = m[i * 3] * o.m[j] + m[i * 3 + 1] * o.m[3 + j] + m[i * 3 + 2] * o.m[6 + j]
        return Basis(r)
    }

    companion object {
        val IDENTITY get() = Basis(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))

        /** btMatrix3x3::setRotation for the quaternion (x, y, z, w). */
        fun fromQuaternion(x: Double, y: Double, z: Double, w: Double): Basis {
            val d = x * x + y * y + z * z + w * w
            val s = 2.0 / d
            val xs = x * s; val ys = y * s; val zs = z * s
            val wx = w * xs; val wy = w * ys; val wz = w * zs
            val xx = x * xs; val xy = x * ys; val xz = x * zs
            val yy = y * ys; val yz = y * zs; val zz = z * zs
            return Basis(doubleArrayOf(
                1.0 - (yy + zz), xy - wz, xz + wy,
                xy + wz, 1.0 - (xx + zz), yz - wx,
                xz - wy, yz + wx, 1.0 - (xx + yy),
            ))
        }
    }
}

internal class Transform(@JvmField val basis: Basis, @JvmField val origin: V3) {
    operator fun invoke(v: V3) = V3(
        v.dot(basis.row(0)) + origin.x, v.dot(basis.row(1)) + origin.y, v.dot(basis.row(2)) + origin.z,
    )

    operator fun times(o: Transform) = Transform(basis * o.basis, invoke(o.origin))

    companion object {
        val IDENTITY get() = Transform(Basis.IDENTITY, V3(0.0, 0.0, 0.0))
    }
}

/** osg::Quat with the double arithmetic OpenMW's rotation conversion uses. */
internal class Quat(val x: Double, val y: Double, val z: Double, val w: Double) {
    operator fun times(r: Quat) = Quat(
        r.w * x + r.x * w + r.y * z - r.z * y,
        r.w * y - r.x * z + r.y * w + r.z * x,
        r.w * z + r.x * y - r.y * x + r.z * w,
        r.w * w - r.x * x - r.y * y - r.z * z,
    )

    companion object {
        fun rotation(angle: Float, ax: Float, ay: Float, az: Float): Quat {
            val a = angle.toDouble()
            val x = ax.toDouble(); val y = ay.toDouble(); val z = az.toDouble()
            val length = sqrt(x * x + y * y + z * z)
            if (length < 0.0000001) return Quat(0.0, 0.0, 0.0, 1.0)
            val inverse = 1.0 / length
            val c = cos(0.5 * a)
            val s = sin(0.5 * a)
            return Quat(x * s * inverse, y * s * inverse, z * s * inverse, c)
        }

        /** Misc::Convert::makeOsgQuat: the ESM rotation (x, y, z) as a quaternion. */
        fun fromEsmRotation(rot: FloatArray): Quat =
            rotation(rot[2], 0f, 0f, -1f) * rotation(rot[1], 0f, -1f, 0f) * rotation(rot[0], -1f, 0f, 0f)
    }
}

internal fun Quat.toBasis() = Basis.fromQuaternion(x, y, z, w)

internal class Aabb(@JvmField val min: V3, @JvmField val max: V3)

internal abstract class BtShape {
    abstract var localScaling: V3
    abstract fun getAabb(t: Transform): Aabb
    abstract fun applyScaling(s: V3)
}

/** btBvhTriangleMeshShape: the triangles, which are all visited; the BVH only speeds up queries. */
internal class TriangleMesh(@JvmField val vertices: FloatArray, @JvmField val indices: IntArray) {
    val triangleCount get() = indices.size / 3
    val localMin: V3
    val localMax: V3

    init {
        var minX = 0.0; var minY = 0.0; var minZ = 0.0
        var maxX = 0.0; var maxY = 0.0; var maxZ = 0.0
        var first = true
        for (i in indices) {
            val x = vertices[i * 3].toDouble(); val y = vertices[i * 3 + 1].toDouble(); val z = vertices[i * 3 + 2].toDouble()
            if (first) { minX = x; maxX = x; minY = y; maxY = y; minZ = z; maxZ = z; first = false; continue }
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            if (z < minZ) minZ = z
            if (z > maxZ) maxZ = z
        }
        localMin = V3(minX, minY, minZ)
        localMax = V3(maxX, maxY, maxZ)
    }
}

/** btScaledBvhTriangleMeshShape. */
internal class ScaledTriangleMeshShape(@JvmField val mesh: TriangleMesh, scale: V3) : BtShape() {
    override var localScaling: V3 = scale

    override fun applyScaling(s: V3) { localScaling = s }

    override fun getAabb(t: Transform): Aabb {
        val s = localScaling
        val tmpMin = mesh.localMin * s
        val tmpMax = mesh.localMax * s
        val minX = if (s.x >= 0.0) tmpMin.x else tmpMax.x
        val minY = if (s.y >= 0.0) tmpMin.y else tmpMax.y
        val minZ = if (s.z >= 0.0) tmpMin.z else tmpMax.z
        val maxX = if (s.x <= 0.0) tmpMin.x else tmpMax.x
        val maxY = if (s.y <= 0.0) tmpMin.y else tmpMax.y
        val maxZ = if (s.z <= 0.0) tmpMin.z else tmpMax.z
        val halfExtents = V3(0.5 * (maxX - minX), 0.5 * (maxY - minY), 0.5 * (maxZ - minZ))
        val center = t(V3(0.5 * (maxX + minX), 0.5 * (maxY + minY), 0.5 * (maxZ + minZ)))
        val abs = t.basis.absolute()
        val extent = V3(halfExtents.dot(abs.row(0)), halfExtents.dot(abs.row(1)), halfExtents.dot(abs.row(2)))
        return Aabb(center - extent, center + extent)
    }

    /** Every triangle with the scaling applied, as nine doubles. */
    fun forEachTriangle(f: (DoubleArray) -> Unit) {
        val s = localScaling
        val tri = DoubleArray(9)
        val v = mesh.vertices
        var i = 0
        while (i < mesh.indices.size) {
            for (k in 0 until 3) {
                val p = mesh.indices[i + k] * 3
                tri[k * 3] = v[p].toDouble() * s.x
                tri[k * 3 + 1] = v[p + 1].toDouble() * s.y
                tri[k * 3 + 2] = v[p + 2].toDouble() * s.z
            }
            f(tri)
            i += 3
        }
    }
}

internal class CompoundChild(@JvmField var transform: Transform, @JvmField val shape: BtShape)

/** btCompoundShape. */
internal class CompoundShape : BtShape() {
    override var localScaling: V3 = V3.ONE
    val children = ArrayList<CompoundChild>()
    private var localMin = V3(LARGE, LARGE, LARGE)
    private var localMax = V3(-LARGE, -LARGE, -LARGE)

    fun addChild(t: Transform, shape: BtShape) {
        val a = shape.getAabb(t)
        localMin = V3(minOf(localMin.x, a.min.x), minOf(localMin.y, a.min.y), minOf(localMin.z, a.min.z))
        localMax = V3(maxOf(localMax.x, a.max.x), maxOf(localMax.y, a.max.y), maxOf(localMax.z, a.max.z))
        children.add(CompoundChild(t, shape))
    }

    private fun recalculateLocalAabb() {
        localMin = V3(LARGE, LARGE, LARGE)
        localMax = V3(-LARGE, -LARGE, -LARGE)
        for (c in children) {
            val a = c.shape.getAabb(c.transform)
            localMin = V3(minOf(localMin.x, a.min.x), minOf(localMin.y, a.min.y), minOf(localMin.z, a.min.z))
            localMax = V3(maxOf(localMax.x, a.max.x), maxOf(localMax.y, a.max.y), maxOf(localMax.z, a.max.z))
        }
    }

    override fun applyScaling(s: V3) {
        for (c in children) {
            c.shape.applyScaling(c.shape.localScaling * s / localScaling)
            c.transform = Transform(c.transform.basis, c.transform.origin * s / localScaling)
        }
        localScaling = s
        recalculateLocalAabb()
    }

    override fun getAabb(t: Transform): Aabb {
        var halfExtents = V3(0.5 * (localMax.x - localMin.x), 0.5 * (localMax.y - localMin.y), 0.5 * (localMax.z - localMin.z))
        var center = V3(0.5 * (localMax.x + localMin.x), 0.5 * (localMax.y + localMin.y), 0.5 * (localMax.z + localMin.z))
        if (children.isEmpty()) { halfExtents = V3(0.0, 0.0, 0.0); center = V3(0.0, 0.0, 0.0) }
        val abs = t.basis.absolute()
        val c = t(center)
        val extent = V3(halfExtents.dot(abs.row(0)), halfExtents.dot(abs.row(1)), halfExtents.dot(abs.row(2)))
        return Aabb(c - extent, c + extent)
    }

    /** Resource::duplicateCollisionShape: a copy whose scaling can change without touching the original. */
    fun duplicate(): CompoundShape {
        val copy = CompoundShape()
        for (c in children) {
            val s = c.shape
            copy.addChild(c.transform, if (s is ScaledTriangleMeshShape) ScaledTriangleMeshShape(s.mesh, s.localScaling) else (s as CompoundShape).duplicate())
        }
        return copy
    }

    private companion object {
        const val LARGE = 1e30
    }
}
