// SPDX-License-Identifier: GPL-3.0-or-later
// Port of the binary formats in OpenMW's components/detournavigator/serialization.cpp (little endian).
package org.alpha3.launcher.tools.navmesh

import org.alpha3.launcher.tools.navmesh.recast.PolyMesh
import org.alpha3.launcher.tools.navmesh.recast.PolyMeshDetail

internal class BinaryWriter(initial: Int = 1024) {
    private var buf = ByteArray(initial)
    var size = 0
        private set

    private fun ensure(n: Int) {
        if (size + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + n))
    }

    fun u8(v: Int) { ensure(1); buf[size++] = v.toByte() }
    fun u16(v: Int) { ensure(2); buf[size++] = v.toByte(); buf[size++] = (v ushr 8).toByte() }
    fun i32(v: Int) {
        ensure(4)
        for (i in 0 until 4) buf[size++] = (v ushr (8 * i)).toByte()
    }
    fun i64(v: Long) {
        ensure(8)
        for (i in 0 until 8) buf[size++] = (v ushr (8 * i)).toByte()
    }
    fun f32(v: Float) = i32(v.toRawBits())
    fun bytes(b: ByteArray) { ensure(b.size); b.copyInto(buf, size); size += b.size }
    fun toByteArray(): ByteArray = buf.copyOf(size)
}

internal class DbRefGeometryObject(@JvmField val shapeId: Long, @JvmField val transform: ObjectTransform)

internal fun compareObjects(l: DbRefGeometryObject, r: DbRefGeometryObject): Int {
    if (l.shapeId != r.shapeId) return l.shapeId.compareTo(r.shapeId)
    for (k in 0 until 3) {
        val x = l.transform.pos[k]; val y = r.transform.pos[k]
        if (x < y) return -1
        if (x > y) return 1
    }
    for (k in 0 until 3) {
        val x = l.transform.rot[k]; val y = r.transform.rot[k]
        if (x < y) return -1
        if (x > y) return 1
    }
    val x = l.transform.scale; val y = r.transform.scale
    return if (x < y) -1 else if (x > y) 1 else 0
}

private fun BinaryWriter.vec2i(x: Int, y: Int) { i32(x); i32(y) }

/** The tile's input, which identifies it in the database. */
internal fun serializeInput(settings: RecastSettings, agent: AgentBounds, mesh: RecastMesh, objects: List<DbRefGeometryObject>): ByteArray {
    val w = BinaryWriter(4096)
    w.bytes(byteArrayOf('r'.code.toByte(), 'c'.code.toByte(), 's'.code.toByte(), 't'.code.toByte()))
    w.i32(2)
    w.f32(settings.cellHeight); w.f32(settings.cellSize); w.f32(settings.detailSampleDist); w.f32(settings.detailSampleMaxError)
    w.f32(settings.maxClimb); w.f32(settings.maxSimplificationError); w.f32(settings.maxSlope); w.f32(settings.recastScaleFactor)
    w.f32(settings.swimHeightScale)
    w.i32(settings.borderSize); w.i32(settings.maxEdgeLen); w.i32(settings.maxVertsPerPoly)
    w.i32(settings.regionMergeArea); w.i32(settings.regionMinArea); w.i32(settings.tileSize)
    w.u8(agent.shapeType)
    for (k in 0 until 3) w.f32(agent.halfExtents[k])
    w.i64(mesh.water.size.toLong())
    for (cw in mesh.water) { w.vec2i(cw.cellX, cw.cellY); w.i32(cw.water.cellSize); w.f32(cw.water.level) }
    w.i64(mesh.heightfields.size.toLong())
    for (h in mesh.heightfields) {
        w.vec2i(h.cellX, h.cellY); w.i32(h.cellSize); w.u8(h.length); w.f32(h.minHeight); w.f32(h.maxHeight)
        w.i64(h.heights.size.toLong())
        for (v in h.heights) w.f32(v)
        w.i64(h.originalSize); w.u8(h.minX); w.u8(h.minY)
    }
    w.i64(mesh.flatHeightfields.size.toLong())
    for (h in mesh.flatHeightfields) { w.vec2i(h.cellX, h.cellY); w.i32(h.cellSize); w.f32(h.height) }
    w.i64(objects.size.toLong())
    for (o in objects) {
        w.i64(o.shapeId)
        for (k in 0 until 3) w.f32(o.transform.pos[k])
        for (k in 0 until 3) w.f32(o.transform.rot[k])
        w.f32(o.transform.scale)
    }
    return w.toByteArray()
}

/** DetourNavigator::PreparedNavMeshData */
internal class PreparedNavMeshData(
    @JvmField var userId: Int,
    @JvmField val cellSize: Float,
    @JvmField val cellHeight: Float,
    @JvmField val polyMesh: PolyMesh,
    @JvmField val detail: PolyMeshDetail,
)

internal fun serializePrepared(d: PreparedNavMeshData): ByteArray {
    val w = BinaryWriter(4096)
    w.bytes(byteArrayOf('p'.code.toByte(), 'n'.code.toByte(), 'a'.code.toByte(), 'v'.code.toByte()))
    w.i32(1)
    w.i32(d.userId)
    w.f32(d.cellSize)
    w.f32(d.cellHeight)
    val pm = d.polyMesh
    w.i32(pm.nverts); w.i32(pm.npolys); w.i32(pm.maxpolys); w.i32(pm.nvp)
    for (k in 0 until 3) w.f32(pm.bmin[k])
    for (k in 0 until 3) w.f32(pm.bmax[k])
    w.f32(pm.cs); w.f32(pm.ch); w.i32(pm.borderSize); w.f32(pm.maxEdgeError)
    for (k in 0 until pm.nverts * 3) w.u16(pm.verts[k])
    for (k in 0 until pm.maxpolys * pm.nvp * 2) w.u16(pm.polys[k])
    for (k in 0 until pm.maxpolys) w.u16(pm.regs[k])
    for (k in 0 until pm.npolys) w.u16(pm.flags[k])
    for (k in 0 until pm.maxpolys) w.u8(pm.areas[k])
    val dm = d.detail
    w.i32(dm.nmeshes)
    for (k in 0 until dm.nmeshes * 4) w.i32(dm.meshes[k])
    w.i32(dm.nverts)
    for (k in 0 until dm.nverts * 3) w.f32(dm.verts[k])
    w.i32(dm.ntris)
    for (k in 0 until dm.ntris * 4) w.u8(dm.tris[k])
    return w.toByteArray()
}
