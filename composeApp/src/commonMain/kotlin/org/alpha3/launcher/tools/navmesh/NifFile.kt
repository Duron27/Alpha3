// SPDX-License-Identifier: GPL-3.0-or-later
// A strict reader for the NetImmerse 4.0.0.2 files Morrowind uses, following OpenMW's components/nif. It reads
// every record exactly as OpenMW does, so a file that OpenMW refuses to load is refused here as well, and keeps
// what the collision shapes need: the node tree, transforms, names, string extra data and the triangle data.
package org.alpha3.launcher.tools.navmesh

import kotlin.jvm.JvmField

internal class NavMeshException(message: String) : Exception(message)

internal fun navFail(message: String): Nothing = throw NavMeshException(message)

private class NifIn(val b: ByteArray, var p: Int) {
    fun need(n: Long) {
        if (n < 0 || n > b.size - p) navFail("unexpected end of NIF data at offset $p")
    }

    fun skip(n: Long) { need(n); p += n.toInt() }

    fun u8(): Int { need(1); return b[p++].toInt() and 0xff }

    fun u16(): Int { need(2); val v = (b[p].toInt() and 0xff) or ((b[p + 1].toInt() and 0xff) shl 8); p += 2; return v }

    fun i32(): Int {
        need(4)
        val v = (b[p].toInt() and 0xff) or ((b[p + 1].toInt() and 0xff) shl 8) or
            ((b[p + 2].toInt() and 0xff) shl 16) or ((b[p + 3].toInt() and 0xff) shl 24)
        p += 4
        return v
    }

    fun u32(): Long = i32().toLong() and 0xffffffffL

    fun f32(): Float = Float.fromBits(i32())

    /** A u32 element count, checked against the bytes left for [elementSize]-byte elements. */
    fun count(elementSize: Int): Int {
        val n = u32()
        if (n * elementSize > b.size - p) navFail("count $n exceeds the NIF data at offset $p")
        return n.toInt()
    }

    fun floats(n: Int): FloatArray = FloatArray(n) { f32() }

    fun string(): String {
        val n = u32()
        need(n)
        var end = p
        val stop = p + n.toInt()
        while (end < stop && b[end].toInt() != 0) end++
        val sb = StringBuilder(end - p)
        for (i in p until end) sb.append((b[i].toInt() and 0xff).toChar())
        p = stop
        return sb.toString()
    }

    /** A record reference, which must name a record in the file or be -1. */
    fun ref(records: Int): Int {
        val r = i32()
        if (r < -1 || r >= records) navFail("invalid record index $r")
        return r
    }

    fun refList(records: Int): IntArray {
        val n = count(4)
        return IntArray(n) { ref(records) }
    }
}

internal class NifObj(@JvmField val type: String, @JvmField val index: Int) {
    @JvmField var name = ""
    @JvmField var extra = -1
    @JvmField var controller = -1

    @JvmField var flags = 0
    @JvmField var trans = floatArrayOf(0f, 0f, 0f)
    @JvmField var rot = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    @JvmField var scale = 1f
    @JvmField var children = IntArray(0)

    /** Geometry: the data and skin instance references. */
    @JvmField var data = -1
    @JvmField var skin = -1

    /** Geometry data. */
    @JvmField var vertices: FloatArray? = null
    @JvmField var numTriangles = 0
    @JvmField var triangles = IntArray(0)
    @JvmField var strips: List<IntArray> = emptyList()

    /** Extra data. */
    @JvmField var next = -1
    @JvmField var text = ""

    /** Skin instance and data. */
    @JvmField var skinData = -1
    @JvmField var skinRoot = -1
    @JvmField var bones = IntArray(0)
    @JvmField var boneCount = 0

    /** Bounding box extents of a "Bounding Box" node are not needed for the navmesh. */
    val isNode get() = type in nodeTypes
    val isAVObject get() = isNode || isGeometry || type in otherAVTypes
    val isGeometry get() = type in geometryTypes
    val isGeometryData get() = type in geometryDataTypes
    val isExtra get() = type in extraTypes
}

private val nodeTypes = hashSetOf(
    "NiNode", "AvoidNode", "NiBillboardNode", "NiBSAnimationNode", "NiBSParticleNode", "NiCollisionSwitch",
    "NiSortAdjustNode", "RootCollisionNode", "NiSwitchNode", "NiFltAnimationNode", "NiLODNode",
)
private val geometryTypes = hashSetOf(
    "NiTriShape", "NiTriStrips", "NiLines", "NiAutoNormalParticles", "NiParticles", "NiRotatingParticles",
)
private val geometryDataTypes = hashSetOf(
    "NiTriShapeData", "NiTriStripsData", "NiLinesData", "NiAutoNormalParticlesData", "NiParticlesData",
    "NiRotatingParticlesData",
)
private val otherAVTypes = hashSetOf(
    "NiCamera", "NiAmbientLight", "NiDirectionalLight", "NiPointLight", "NiSpotLight", "NiTextureEffect",
)
private val extraTypes = hashSetOf("NiExtraData", "NiStringExtraData", "NiTextKeyExtraData", "NiVertWeightsExtraData")

internal class NifFile(@JvmField val records: Array<NifObj>, @JvmField val roots: IntArray)

private const val HEADER = "NetImmerse File Format, Version 4.0.0.2"
private const val VERSION_MW = 0x04000002L

/** Parses a Morrowind NIF; throws [NavMeshException] when OpenMW would refuse the file. */
internal fun parseNif(bytes: ByteArray): NifFile {
    var nl = 0
    while (nl < bytes.size && bytes[nl].toInt() != '\n'.code) nl++
    if (nl >= bytes.size) navFail("Invalid NIF header")
    val head = CharArray(nl) { (bytes[it].toInt() and 0xff).toChar() }.concatToString()
    if (!head.startsWith("NetImmerse File Format") && !head.startsWith("Gamebryo File Format")) navFail("Invalid NIF header: $head")
    val s = NifIn(bytes, nl + 1)
    val version = s.u32()
    if (version != VERSION_MW) navFail("unsupported NIF version 0x${version.toString(16)} ($head)")
    val count = s.u32()
    if (count > bytes.size) navFail("record count $count exceeds the file size")
    val n = count.toInt()
    val records = arrayOfNulls<NifObj>(n)
    for (i in 0 until n) {
        val type = s.string()
        if (type.isEmpty()) navFail("Record type is blank (index $i)")
        val o = NifObj(type, i)
        readRecord(s, o, n)
        records[i] = o
    }
    val rootCount = s.count(4)
    val roots = IntArray(rootCount) { s.i32() }
    if (s.p != bytes.size) navFail("NIF has ${bytes.size - s.p} bytes after the last root")
    val all = Array(n) { records[it]!! }
    postResolve(all)
    return NifFile(all, roots)
}

/** The checks RecordPtr::post and the nodes' post functions make once every record is read. */
private fun postResolve(r: Array<NifObj>) {
    fun target(i: Int): NifObj? = if (i < 0) null else r[i]
    for (o in r) {
        if (o.isNode) for (c in o.children) target(c)?.let { if (!it.isAVObject) navFail("Failed to cast record pointer to NiAVObject") }
        if (o.type == "NiSkinInstance") {
            val data = target(o.skinData)
            val root = target(o.skinRoot)
            if (data != null && data.type != "NiSkinData") navFail("Failed to cast record pointer to NiSkinData")
            if (root != null && !root.isNode) navFail("Failed to cast record pointer to NiNode")
            if (data == null || root == null) navFail("NiSkinInstance missing root or data")
            if (o.bones.size != data.boneCount) navFail("Mismatch in NiSkinData bone count")
            for (b in o.bones) {
                val bone = target(b) ?: navFail("Oops: Missing bone! Don't know how to handle this.")
                if (!bone.isAVObject) navFail("Failed to cast record pointer to NiAVObject")
            }
        }
        if (o.isGeometry) {
            val skin = target(o.skin)
            if (skin != null && skin.type != "NiSkinInstance") navFail("Failed to cast record pointer to NiSkinInstance")
            val data = target(o.data)
            if (data != null && !data.isGeometryData) navFail("Failed to cast record pointer to NiGeometryData")
            if (data != null) {
                val ok = when (o.type) {
                    "NiTriShape" -> data.type == "NiTriShapeData"
                    "NiTriStrips" -> data.type == "NiTriStripsData"
                    "NiLines" -> data.type == "NiLinesData"
                    else -> data.type == "NiParticlesData" || data.type == "NiAutoNormalParticlesData" || data.type == "NiRotatingParticlesData"
                }
                if (!ok) o.data = -1
            }
        }
        // A node's extra data chain must name extra data records.
        if (o.extra >= 0 && !r[o.extra].isExtra) navFail("Failed to cast record pointer to Extra")
        if (o.isExtra && o.next >= 0 && !r[o.next].isExtra) navFail("Failed to cast record pointer to Extra")
    }
}

private fun readRecord(s: NifIn, o: NifObj, n: Int) {
    when (o.type) {
        "NiNode", "AvoidNode", "NiBSAnimationNode", "NiBSParticleNode", "NiCollisionSwitch", "RootCollisionNode",
        "NiBillboardNode" -> readNode(s, o, n)
        "NiSortAdjustNode" -> { readNode(s, o, n); s.u32() }
        "NiSwitchNode" -> { readNode(s, o, n); s.u32() }
        "NiFltAnimationNode" -> { readNode(s, o, n); s.u32(); s.f32() }
        "NiLODNode" -> {
            readNode(s, o, n); s.u32()
            s.skip(12)
            val levels = s.count(8)
            s.skip(levels * 8L)
        }
        "NiSequenceStreamHelper" -> readObjectNet(s, o, n)
        "NiCamera" -> { readAVObject(s, o, n); s.skip(11 * 4L); s.ref(n); s.skip(4) }
        "NiAlphaAccumulator", "NiClusterAccumulator" -> {}
        "NiTriShape", "NiTriStrips", "NiLines", "NiAutoNormalParticles", "NiParticles", "NiRotatingParticles" -> {
            readAVObject(s, o, n)
            o.data = s.ref(n)
            o.skin = s.ref(n)
        }
        "NiTriShapeData" -> readTriShapeData(s, o)
        "NiTriStripsData" -> readTriStripsData(s, o)
        "NiLinesData" -> navFail("unsupported record NiLinesData")
        "NiParticlesData", "NiAutoNormalParticlesData" -> readParticlesData(s, o, false)
        "NiRotatingParticlesData" -> readParticlesData(s, o, true)
        "NiSkinInstance" -> {
            o.skinData = s.ref(n)
            o.skinRoot = s.ref(n)
            o.bones = s.refList(n)
        }
        "NiSkinData" -> {
            s.skip(13 * 4L)
            val bones = s.u32()
            s.ref(n)
            if (bones * 20 > s.b.size - s.p) navFail("bone count $bones exceeds the NIF data")
            o.boneCount = bones.toInt()
            for (i in 0 until o.boneCount) {
                s.skip(13 * 4L + 16)
                val weights = s.u16()
                s.skip(weights * 6L)
            }
        }
        "NiSkinPartition" -> navFail("unsupported record NiSkinPartition")
        // Properties
        "NiAlphaProperty" -> { readObjectNet(s, o, n); s.u16(); s.u8() }
        "NiDitherProperty", "NiShadeProperty", "NiSpecularProperty", "NiWireframeProperty", "NiZBufferProperty" -> {
            readObjectNet(s, o, n); s.u16()
        }
        "NiFogProperty" -> { readObjectNet(s, o, n); s.u16(); s.f32(); s.skip(12) }
        "NiMaterialProperty" -> { readObjectNet(s, o, n); s.u16(); s.skip(12 * 4L + 8) }
        "NiStencilProperty" -> { readObjectNet(s, o, n); s.u16(); s.u8(); s.skip(7 * 4L) }
        "NiVertexColorProperty" -> { readObjectNet(s, o, n); s.u16(); s.skip(8) }
        "NiTexturingProperty" -> readTexturingProperty(s, o, n)
        "NiSourceTexture" -> {
            readObjectNet(s, o, n)
            val external = s.u8() != 0
            val hasData = if (!external) s.u8() != 0 else false
            if (external) s.string()
            if (hasData) s.ref(n)
            s.skip(12)
            s.u8()
        }
        "NiPixelData" -> {
            s.skip(4 + 16 + 4 + 8)
            s.ref(n)
            val mips = s.count(12)
            s.u32()
            s.skip(mips * 12L)
            val pixels = s.u32()
            s.skip(pixels)
        }
        "NiPalette" -> {
            s.u8()
            val entries = s.count(4)
            s.skip(entries * 4L)
        }
        // Extra data
        "NiExtraData" -> { readExtra(s, o, n); s.skip(o.boneCount.toLong() and 0xffffffffL) }
        "NiStringExtraData" -> { readExtra(s, o, n); o.text = s.string() }
        "NiTextKeyExtraData" -> {
            readExtra(s, o, n)
            val keys = s.count(8)
            for (i in 0 until keys) { s.f32(); s.string() }
        }
        "NiVertWeightsExtraData" -> { readExtra(s, o, n); s.skip(s.u16() * 4L) }
        // Lights and effects
        "NiAmbientLight", "NiDirectionalLight" -> { readDynamicEffect(s, o, n); s.skip(10 * 4L) }
        "NiPointLight" -> { readDynamicEffect(s, o, n); s.skip(13 * 4L) }
        "NiSpotLight" -> { readDynamicEffect(s, o, n); s.skip(15 * 4L) }
        "NiTextureEffect" -> {
            readDynamicEffect(s, o, n)
            s.skip(9 * 4L + 3 * 4L + 4 * 4L)
            s.ref(n)
            s.u8()
            s.skip(16 + 4 + 2)
        }
        // Controllers
        "NiKeyframeController", "NiAlphaController", "NiRollController", "NiVisController",
        "NiMaterialColorController", "NiLightColorController" -> { readController(s, n); s.ref(n) }
        "NiUVController" -> { readController(s, n); s.u16(); s.ref(n) }
        "NiPathController" -> { readController(s, n); s.skip(4 + 4 + 4 + 2); s.ref(n); s.ref(n) }
        "NiLookAtController" -> { readController(s, n); s.ref(n) }
        "NiFlipController" -> { readController(s, n); s.skip(4 + 4 + 4); s.refList(n) }
        "NiGeomMorpherController" -> { readController(s, n); s.ref(n); s.u8() }
        "NiParticleSystemController", "NiBSPArrayController" -> readParticleController(s, n)
        // Particle modifiers and colliders
        "NiGravity" -> { readModifier(s, n); s.skip(4 + 4 + 4 + 12 + 12) }
        "NiParticleBomb" -> { readModifier(s, n); s.skip(4 * 4L + 8 + 24) }
        "NiParticleColorModifier" -> { readModifier(s, n); s.ref(n) }
        "NiParticleGrowFade" -> { readModifier(s, n); s.skip(8) }
        "NiParticleRotation" -> { readModifier(s, n); s.u8(); s.skip(16) }
        "NiPlanarCollider" -> { readModifier(s, n); s.skip(4 + 8 + 12 * 4L + 4) }
        "NiSphericalCollider" -> { readModifier(s, n); s.skip(4 + 4 + 12) }
        // Animation data
        "NiKeyframeData" -> {
            val type = readKeys(s, KEY_QUAT)
            if (type == INTERP_XYZ) {
                s.u32()
                readKeys(s, KEY_FLOAT); readKeys(s, KEY_FLOAT); readKeys(s, KEY_FLOAT)
            }
            readKeys(s, KEY_VEC3)
            readKeys(s, KEY_FLOAT)
        }
        "NiPosData" -> readKeys(s, KEY_VEC3)
        "NiFloatData" -> readKeys(s, KEY_FLOAT)
        "NiColorData" -> readKeys(s, KEY_VEC4)
        "NiUVData" -> { for (i in 0 until 4) readKeys(s, KEY_FLOAT) }
        "NiVisData" -> { val keys = s.count(5); s.skip(keys * 5L) }
        "NiMorphData" -> {
            val morphs = s.u32()
            val verts = s.u32()
            s.u8()
            if (morphs * 8 > s.b.size - s.p) navFail("morph count $morphs exceeds the NIF data")
            for (i in 0 until morphs.toInt()) {
                readKeys(s, KEY_FLOAT, morph = true)
                s.skip(verts * 12)
            }
        }
        else -> navFail("Unknown record type ${o.type}")
    }
}

private fun readObjectNet(s: NifIn, o: NifObj, n: Int) {
    o.name = s.string()
    o.extra = s.ref(n)
    o.controller = s.ref(n)
}

private fun readAVObject(s: NifIn, o: NifObj, n: Int) {
    readObjectNet(s, o, n)
    o.flags = s.u16()
    o.trans = s.floats(3)
    o.rot = s.floats(9)
    o.scale = s.f32()
    s.skip(12)
    s.refList(n)
    if (s.i32() != 0) readBound(s)
}

private fun readBound(s: NifIn) {
    when (val type = s.u32()) {
        0xFFFFFFFFL -> {}
        0L -> s.skip(16)
        1L -> s.skip(15 * 4L)
        2L -> s.skip(8 * 4L)
        3L -> s.skip(10 * 4L)
        4L -> {
            val kids = s.count(4)
            for (i in 0 until kids) readBound(s)
        }
        5L -> s.skip(16)
        else -> navFail("Unhandled BoundingVolume type: $type")
    }
}

private fun readNode(s: NifIn, o: NifObj, n: Int) {
    readAVObject(s, o, n)
    o.children = s.refList(n)
    s.refList(n)
    // The root of the file has no transform of its own, unless it is a skeleton root.
    if (o.index == 0 && !o.name.equals("bip01", ignoreCase = true)) {
        o.trans = floatArrayOf(0f, 0f, 0f)
        o.rot = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        o.scale = 1f
    }
}

private fun readDynamicEffect(s: NifIn, o: NifObj, n: Int) {
    readAVObject(s, o, n)
    val affected = s.count(4)
    s.skip(affected * 4L)
}

private fun readExtra(s: NifIn, o: NifObj, n: Int) {
    o.next = s.ref(n)
    // NiExtraData keeps its record size in boneCount, which no other record type uses
    o.boneCount = s.i32()
}

private fun readController(s: NifIn, n: Int) {
    s.ref(n)
    s.skip(2 + 16)
    s.ref(n)
}

private fun readModifier(s: NifIn, n: Int) {
    s.ref(n)
    s.ref(n)
}

private fun readParticleController(s: NifIn, n: Int) {
    readController(s, n)
    s.skip(6 * 4L + 12 + 16 + 12 + 1 + 4 * 3 + 2 + 12)
    s.ref(n)
    s.skip(2 + 4 + 2 + 4 + 4)
    val particles = s.u16()
    s.u16()
    s.skip(particles * 40L)
    s.skip(4)
    s.ref(n)
    s.ref(n)
    s.u8()
}

private fun readTexturingProperty(s: NifIn, o: NifObj, n: Int) {
    readObjectNet(s, o, n)
    s.u16()
    s.u32()
    val textures = s.count(4)
    for (i in 0 until textures) {
        if (s.i32() != 0) {
            s.ref(n)
            s.skip(12 + 4 + 2)
            if (i == 5) s.skip(24)
        }
    }
}

private fun readGeometryData(s: NifIn, o: NifObj): Int {
    val verts = s.u16()
    o.vertices = null
    if (s.i32() != 0) o.vertices = s.floats(verts * 3)
    if (s.i32() != 0) s.skip(verts * 12L)
    s.skip(16)
    if (s.i32() != 0) s.skip(verts * 16L)
    val uvSets = s.u16()
    val sets = if (s.i32() != 0) uvSets else 0
    s.skip(sets.toLong() * verts * 8L)
    return verts
}

private fun readTriBased(s: NifIn, o: NifObj) {
    readGeometryData(s, o)
    o.numTriangles = s.u16()
}

private fun readTriShapeData(s: NifIn, o: NifObj) {
    readTriBased(s, o)
    val indices = s.u32()
    s.need(indices * 2)
    o.triangles = IntArray(indices.toInt()) { s.u16() }
    val groups = s.u16()
    for (i in 0 until groups) s.skip(s.u16() * 2L)
}

private fun readTriStripsData(s: NifIn, o: NifObj) {
    readTriBased(s, o)
    val strips = s.u16()
    val lengths = IntArray(strips) { s.u16() }
    val list = ArrayList<IntArray>(strips)
    for (len in lengths) {
        s.need(len * 2L)
        list.add(IntArray(len) { s.u16() })
    }
    o.strips = list
}

private fun readParticlesData(s: NifIn, o: NifObj, rotating: Boolean) {
    val verts = readGeometryData(s, o)
    s.u16()
    s.f32()
    s.u16()
    if (s.i32() != 0) s.skip(verts * 4L)
    if (rotating && s.i32() != 0) s.skip(verts * 16L)
}

private const val KEY_FLOAT = 0
private const val KEY_VEC3 = 1
private const val KEY_VEC4 = 2
private const val KEY_QUAT = 3
private const val INTERP_XYZ = 4

/** Reads a key group, as NiKeyframeData and its relatives do, and returns its interpolation type (0 for none). */
private fun readKeys(s: NifIn, kind: Int, morph: Boolean = false): Int {
    val count = s.u32()
    if (count == 0L && !morph) return 0
    val type = s.u32().toInt()
    val value = when (kind) { KEY_FLOAT -> 4; KEY_VEC3 -> 12; else -> 16 }
    val bytes: Long = when (type) {
        1, 5 -> 4L + value
        2 -> if (kind == KEY_QUAT) 4L + value else 4L + 3 * value
        3 -> 4L + value + 12
        4 -> 0L
        else -> if (count != 0L) navFail("Unhandled interpolation type: $type") else 0L
    }
    if (count * bytes > s.b.size - s.p) navFail("key count $count exceeds the NIF data")
    s.skip(count * bytes)
    return type
}
