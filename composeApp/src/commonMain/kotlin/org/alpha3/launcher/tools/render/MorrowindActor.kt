package org.alpha3.launcher.tools.render

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * An animated humanoid built from stock Morrowind assets: the shared skeleton (xbase_anim.nif),
 * the walk cycle from xbase_anim.kf, rigid body parts / armor attached to skeleton nodes, and
 * skinned parts (chest, hands, cuirass) deformed by the bones every frame.
 */

private class SkelNode(
    val name: String, val parent: Int,
    val rot: FloatArray, val trans: FloatArray, val scale: Float
)

private class Track(val keys: NifKeys)

internal class SkinnedSource(
    val positions: FloatArray, val normals: FloatArray,
    val boneIndex: IntArray, val boneWeight: FloatArray, // 4 influences per vertex
    val boneNames: List<String>, val boneXforms: List<NifBone>
)

/** Extracts skinned shapes (which [parseNif] skips) with per-vertex bone influences. */
private fun parseSkinned(bytes: ByteArray, meshOut: MutableList<MwMesh>, sources: MutableList<SkinnedSource>) {
    val (blocks, _) = runCatching { readBlocks(bytes) }.getOrElse { return }
    if (blocks.isEmpty()) return

    fun visit(i: Int, inherited: IntArray) {
        val b = blocks.getOrNull(i) ?: return
        val props = inherited + b.properties
        when (b.type) {
            "NiNode", "NiBSAnimationNode" -> b.children.forEach { visit(it, props) }
            "NiTriShape" -> {
                if (b.skin < 0) return
                val inst = blocks.getOrNull(b.skin) ?: return
                val sd = blocks.getOrNull(inst.data)?.skinData ?: return
                val d = blocks.getOrNull(b.data) ?: return
                val pos = d.positions ?: return
                val idx = d.indices ?: return
                val n = pos.size / 3
                val bi = IntArray(n * 4); val bw = FloatArray(n * 4)
                for ((bone, info) in sd.bones.withIndex()) {
                    for (k in info.vertices.indices) {
                        val v = info.vertices[k]; val w = info.weights[k]
                        if (v >= n) continue
                        // keep the 4 strongest influences
                        var slot = -1; var weakest = w
                        for (s in 0..3) if (bw[v * 4 + s] < weakest) { weakest = bw[v * 4 + s]; slot = s }
                        if (slot >= 0) { bi[v * 4 + slot] = bone; bw[v * 4 + slot] = w }
                    }
                }
                for (v in 0 until n) {
                    val sum = bw[v * 4] + bw[v * 4 + 1] + bw[v * 4 + 2] + bw[v * 4 + 3]
                    if (sum > 1e-6f) for (s in 0..3) bw[v * 4 + s] /= sum
                }
                var texture: String? = null
                var diffuse = floatArrayOf(1f, 1f, 1f)
                var emissive = floatArrayOf(0f, 0f, 0f)
                var blend = false
                for (pi in props) {
                    val p = blocks.getOrNull(pi) ?: continue
                    when (p.type) {
                        "NiTexturingProperty" -> texture = blocks.getOrNull(p.source)?.fileName ?: texture
                        "NiMaterialProperty" -> { diffuse = p.diffuse; emissive = p.emissive }
                        "NiAlphaProperty" -> blend = p.alphaFlags and 1 != 0
                    }
                }
                val nrm = d.normals ?: FloatArray(pos.size).also { it[2] = 1f; for (v in 0 until n) it[v * 3 + 2] = 1f }
                meshOut += MwMesh(pos, nrm, d.uvs, d.colors, idx, texture, diffuse, emissive, blend, false, b.name)
                sources += SkinnedSource(pos, nrm, bi, bw, inst.bones.map { blocks.getOrNull(it)?.name ?: "" }, sd.bones)
            }
        }
    }
    visit(0, IntArray(0))
}

/** What to put on the guard. Names are attachment nodes in the skeleton. */
private class RigidSpec(val file: String, val node: String, val scale: Float = 1f)

internal class GuardActor private constructor(
    private val nodes: List<SkelNode>,
    private val tracks: Map<String, Track>,
    private val loopStart: Float,
    private val loopEnd: Float,
    private val rigid: List<Rigid>,
    private val skinned: List<Skinned>,
    private val torchNode: Int,
    private val torchTip: FloatArray,
    private val path: WalkPath,
    val speed: Float
) {
    private class Rigid(val node: Int, val local: MwMesh, val out: SceneMesh, val localNormals: FloatArray = local.normals)
    private class Skinned(val src: SkinnedSource, val out: SceneMesh, val boneNodes: IntArray)

    class WalkPath(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val clockwise: Boolean = true)

    val meshes: List<SceneMesh> = rigid.map { it.out } + skinned.map { it.out }

    private val rot = FloatArray(nodes.size * 9)
    private val trans = FloatArray(nodes.size * 3)
    private val scl = FloatArray(nodes.size)
    private val loopLen = loopEnd - loopStart

    /** Fixed warm fire color, independent of the light settings. */
    var torchX = 0f; var torchY = 0f; var torchZ = 0f
        private set

    private val q = FloatArray(4)

    fun update(time: Float) {
        // position along the path
        val perimeter = ellipsePerimeter()
        val dist = time * speed
        val ang = (dist / perimeter) * 2 * PI.toFloat() * (if (path.clockwise) -1f else 1f)
        val px = path.cx + path.rx * cos(ang); val py = path.cy + path.ry * sin(ang)
        val dir = if (path.clockwise) -1f else 1f
        val tx = -path.rx * sin(ang) * dir; val ty = path.ry * cos(ang) * dir
        val yaw = atan2(-tx, ty)
        applyPose(time, px, py, yaw)
    }

    /**
     * Walks on the spot at the origin, facing [yaw] (0 faces +Y, -PI/2 faces +X), so the caller can
     * move the rendered figure however it likes.
     */
    fun updateInPlace(time: Float, yaw: Float) = applyPose(time, 0f, 0f, yaw)

    private fun applyPose(time: Float, px: Float, py: Float, yaw: Float) =
        applyPoseAt(loopStart + (time % loopLen + loopLen) % loopLen, px, py, yaw)

    /** Poses the actor at an absolute time in the animation file (not wrapped to the loop). */
    fun updateAtAnimTime(animT: Float, yaw: Float) = applyPoseAt(animT, 0f, 0f, yaw)

    /** Where the actor stands on the ground (the walk path position), for casting a shadow. */
    var footX = 0f; var footY = 0f
        private set

    private fun applyPoseAt(animT: Float, px: Float, py: Float, yaw: Float) {
        footX = px; footY = py
        val cy = cos(yaw); val sy = sin(yaw)

        poseSkeleton(animT)

        for (r in rigid) {
            val n = r.node
            val m = FloatArray(9) { rot[n * 9 + it] }
            val s = scl[n]
            val src = r.local.positions
            val outP = r.out.positions; val outN = r.out.normals
            for (v in 0 until src.size / 3) {
                val x = src[v * 3]; val y = src[v * 3 + 1]; val z = src[v * 3 + 2]
                val wx = trans[n * 3] + s * (m[0] * x + m[1] * y + m[2] * z)
                val wy = trans[n * 3 + 1] + s * (m[3] * x + m[4] * y + m[5] * z)
                val wz = trans[n * 3 + 2] + s * (m[6] * x + m[7] * y + m[8] * z)
                outP[v * 3] = px + cy * wx - sy * wy
                outP[v * 3 + 1] = py + sy * wx + cy * wy
                outP[v * 3 + 2] = wz
                val nx = r.localNormals[v * 3]; val ny = r.localNormals[v * 3 + 1]; val nz = r.localNormals[v * 3 + 2]
                val ax = m[0] * nx + m[1] * ny + m[2] * nz
                val ay = m[3] * nx + m[4] * ny + m[5] * nz
                outN[v * 3] = cy * ax - sy * ay
                outN[v * 3 + 1] = sy * ax + cy * ay
                outN[v * 3 + 2] = m[6] * nx + m[7] * ny + m[8] * nz
            }
        }

        for (sk in skinned) {
            val src = sk.src
            val bones = src.boneXforms
            // per-bone final matrix: world(bone) * skinToBone
            val bm = Array(bones.size) { b ->
                val node = sk.boneNodes[b]
                val out = FloatArray(12)
                if (node < 0) { out[0] = 1f; out[4] = 1f; out[8] = 1f; return@Array out }
                val info = bones[b]
                val wr = FloatArray(9) { rot[node * 9 + it] }
                val ws = scl[node]
                // R = wr * info.rot * (ws*info.scale)
                for (i in 0..2) for (j in 0..2) {
                    out[i * 3 + j] = (wr[i * 3] * info.rot[j] + wr[i * 3 + 1] * info.rot[3 + j] + wr[i * 3 + 2] * info.rot[6 + j]) * ws * info.scale
                }
                for (i in 0..2) {
                    out[9 + i] = trans[node * 3 + i] + ws * (wr[i * 3] * info.trans[0] + wr[i * 3 + 1] * info.trans[1] + wr[i * 3 + 2] * info.trans[2])
                }
                out
            }
            val outP = sk.out.positions; val outN = sk.out.normals
            for (v in 0 until src.positions.size / 3) {
                val x = src.positions[v * 3]; val y = src.positions[v * 3 + 1]; val z = src.positions[v * 3 + 2]
                val nx = src.normals[v * 3]; val ny = src.normals[v * 3 + 1]; val nz = src.normals[v * 3 + 2]
                var ax = 0f; var ay = 0f; var az = 0f
                var bx = 0f; var by = 0f; var bz = 0f
                for (s in 0..3) {
                    val w = src.boneWeight[v * 4 + s]
                    if (w <= 0f) continue
                    val m = bm[src.boneIndex[v * 4 + s]]
                    ax += w * (m[0] * x + m[1] * y + m[2] * z + m[9])
                    ay += w * (m[3] * x + m[4] * y + m[5] * z + m[10])
                    az += w * (m[6] * x + m[7] * y + m[8] * z + m[11])
                    bx += w * (m[0] * nx + m[1] * ny + m[2] * nz)
                    by += w * (m[3] * nx + m[4] * ny + m[5] * nz)
                    bz += w * (m[6] * nx + m[7] * ny + m[8] * nz)
                }
                outP[v * 3] = px + cy * ax - sy * ay
                outP[v * 3 + 1] = py + sy * ax + cy * ay
                outP[v * 3 + 2] = az
                val bl = sqrt(bx * bx + by * by + bz * bz).coerceAtLeast(1e-6f)
                bx /= bl; by /= bl; bz /= bl
                outN[v * 3] = cy * bx - sy * by
                outN[v * 3 + 1] = sy * bx + cy * by
                outN[v * 3 + 2] = bz
            }
        }

        if (torchNode < 0) return
        // flame position: tip of the torch in the hand node
        val n = torchNode
        val s = scl[n]
        val lx = trans[n * 3] + s * (rot[n * 9] * torchTip[0] + rot[n * 9 + 1] * torchTip[1] + rot[n * 9 + 2] * torchTip[2])
        val ly = trans[n * 3 + 1] + s * (rot[n * 9 + 3] * torchTip[0] + rot[n * 9 + 4] * torchTip[1] + rot[n * 9 + 5] * torchTip[2])
        torchX = px + cy * lx - sy * ly
        torchY = py + sy * lx + cy * ly
        torchZ = trans[n * 3 + 2] + s * (rot[n * 9 + 6] * torchTip[0] + rot[n * 9 + 7] * torchTip[1] + rot[n * 9 + 8] * torchTip[2])
    }

    private fun ellipsePerimeter(): Float {
        val a = path.rx; val b = path.ry
        return (PI * (3 * (a + b) - sqrt((3 * a + b) * (a + 3 * b)))).toFloat()
    }

    private fun poseSkeleton(t: Float) {
        for ((i, n) in nodes.withIndex()) {
            val tr = tracks[n.name]
            var lr = n.rot
            var lt = n.trans
            if (tr != null) {
                val k = tr.keys
                if (k.rotTimes.isNotEmpty()) { sampleQuat(k, t, q); lr = quatToMatrix(q) }
                if (k.transTimes.isNotEmpty()) {
                    lt = sampleVec3(k, t)
                    // keep the walk in place; the actor is moved along the path instead
                    if (n.name == "Bip01") { val base = sampleVec3(k, loopStart); lt = floatArrayOf(base[0], base[1], lt[2]) }
                }
            }
            if (n.parent < 0) {
                lr.copyInto(rot, i * 9, 0, 9)
                for (c in 0..2) trans[i * 3 + c] = lt[c] * n.scale
                scl[i] = n.scale
            } else {
                val p = n.parent
                val ps = scl[p]
                for (r in 0..2) for (c in 0..2) {
                    rot[i * 9 + r * 3 + c] = rot[p * 9 + r * 3] * lr[c] + rot[p * 9 + r * 3 + 1] * lr[3 + c] + rot[p * 9 + r * 3 + 2] * lr[6 + c]
                }
                for (r in 0..2) {
                    trans[i * 3 + r] = trans[p * 3 + r] + ps * (rot[p * 9 + r * 3] * lt[0] + rot[p * 9 + r * 3 + 1] * lt[1] + rot[p * 9 + r * 3 + 2] * lt[2])
                }
                scl[i] = ps * n.scale
            }
        }
    }

    private fun sampleQuat(k: NifKeys, t: Float, out: FloatArray) {
        val times = k.rotTimes
        var hi = times.binarySearch(t).let { if (it >= 0) it else -it - 1 }
        if (hi <= 0) { for (c in 0..3) out[c] = k.rotValues[c]; return }
        if (hi >= times.size) { for (c in 0..3) out[c] = k.rotValues[(times.size - 1) * 4 + c]; return }
        val lo = hi - 1
        val f = (t - times[lo]) / (times[hi] - times[lo]).coerceAtLeast(1e-6f)
        var dot = 0f
        for (c in 0..3) dot += k.rotValues[lo * 4 + c] * k.rotValues[hi * 4 + c]
        val sign = if (dot < 0f) -1f else 1f
        var len = 0f
        for (c in 0..3) {
            out[c] = k.rotValues[lo * 4 + c] * (1 - f) + sign * k.rotValues[hi * 4 + c] * f
            len += out[c] * out[c]
        }
        len = sqrt(len).coerceAtLeast(1e-6f)
        for (c in 0..3) out[c] /= len
    }

    private fun sampleVec3(k: NifKeys, t: Float): FloatArray {
        val times = k.transTimes
        val hi = times.binarySearch(t).let { if (it >= 0) it else -it - 1 }
        if (hi <= 0) return floatArrayOf(k.transValues[0], k.transValues[1], k.transValues[2])
        if (hi >= times.size) { val o = (times.size - 1) * 3; return floatArrayOf(k.transValues[o], k.transValues[o + 1], k.transValues[o + 2]) }
        val lo = hi - 1
        val f = (t - times[lo]) / (times[hi] - times[lo]).coerceAtLeast(1e-6f)
        return FloatArray(3) { k.transValues[lo * 3 + it] * (1 - f) + k.transValues[hi * 3 + it] * f }
    }

    private fun quatToMatrix(q: FloatArray): FloatArray {
        val w = q[0]; val x = q[1]; val y = q[2]; val z = q[3]
        return floatArrayOf(
            1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w),
            2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w),
            2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)
        )
    }

    companion object {
        private val BODY = listOf(
            "b/b_n_imperial_m_head_01.nif" to "Head",
            "b/b_n_imperial_m_neck.nif" to "Neck",
            "b/b_n_imperial_m_groin.nif" to "Groin",
            "b/b_n_imperial_m_foot.nif" to "Foot",
            "b/b_n_imperial_m_ankle.nif" to "Ankle",
            "b/b_n_imperial_m_knee.nif" to "Knee",
            "b/b_n_imperial_m_upper leg.nif" to "Upper Leg",
            "b/b_n_imperial_m_wrist.nif" to "Wrist",
            "b/b_n_imperial_m_forearm.nif" to "Forearm",
            "b/b_n_imperial_m_upper arm.nif" to "Upper Arm"
        )

        /** Flattens the bone hierarchy (NiNodes only), parents first. */
        private fun flattenSkeleton(skelBlocks: List<NifBlock>, nodes: MutableList<SkelNode>, byName: MutableMap<String, Int>) {
            fun walk(i: Int, parent: Int) {
                val b = skelBlocks.getOrNull(i) ?: return
                if (b.type != "NiNode" || b.name == "Bounding Box") return
                val idx = nodes.size
                nodes += SkelNode(b.name, parent, b.matrix, b.translation, b.scale)
                byName.putIfAbsent(b.name, idx)
                b.children.forEach { walk(it, idx) }
            }
            walk(0, -1)
        }

        /** Per-bone keyframe tracks of a .kf file, keyed by bone name. */
        private fun readTracks(kf: List<NifBlock>): HashMap<String, Track> {
            val names = ArrayList<String>()
            var ex = kf[0].extra
            while (ex >= 0) { val e = kf.getOrNull(ex) ?: break; e.text?.let { names += it }; ex = e.next }
            val tracks = HashMap<String, Track>()
            var c = kf[0].controller; var ci = 0
            while (c >= 0) {
                val cc = kf.getOrNull(c) ?: break
                val keys = kf.getOrNull(cc.data)?.keys
                val name = names.getOrNull(ci)
                if (keys != null && name != null) tracks[name] = Track(keys)
                c = cc.next; ci++
            }
            return tracks
        }

        /** Speed (units/s) at which the root moves over one walk loop, so feet don't slide. */
        private fun walkSpeed(tracks: Map<String, Track>, loopStart: Float, loopEnd: Float): Float {
            val rootKeys = tracks["Bip01"]?.keys
            var speed = 90f
            if (rootKeys != null && rootKeys.transTimes.isNotEmpty()) {
                fun at(t: Float): FloatArray {
                    val i = rootKeys.transTimes.indexOfFirst { it >= t }.let { if (it < 0) rootKeys.transTimes.size - 1 else it }
                    return floatArrayOf(rootKeys.transValues[i * 3], rootKeys.transValues[i * 3 + 1])
                }
                val a = at(loopStart); val b = at(loopEnd)
                val d = sqrt((b[0] - a[0]) * (b[0] - a[0]) + (b[1] - a[1]) * (b[1] - a[1]))
                if (d > 5f) speed = d / (loopEnd - loopStart)
            }
            return speed
        }

        private fun sceneMeshOf(assets: MwAssets, m: MwMesh): SceneMesh {
            val tex = m.texture?.let { assets.readTexture(it) }
            return SceneMesh(
                FloatArray(m.positions.size), FloatArray(m.normals.size), m.uvs, m.colors, m.indices, tex,
                if (m.diffuse.all { it == 0f }) floatArrayOf(1f, 1f, 1f) else m.diffuse, m.emissive, m.alphaBlend, m.alphaTest
            )
        }

        /**
         * Dagoth Ur's second form: the creature skeleton and skinned body from Morrowind.bsa, driven by
         * the creature's own walk cycle. Particle effects are not drawn.
         */
        fun loadDagoth(assets: MwAssets, path: WalkPath, missing: MutableList<String>): GuardActor? =
            loadCreature(assets, "meshes/r/xdagothr.nif", "meshes/r/xdagothr.kf", "meshes/r/dagothr.nif", "WalkForward", path, missing)

        /**
         * A skinned creature: skeleton from [skeletonNif], skinned shapes from [bodyNif] and the loop
         * [group] ("<group>: Loop Start" / "<group>: Loop Stop") from [kfPath].
         */
        fun loadCreature(
            assets: MwAssets, skeletonNif: String, kfPath: String, bodyNif: String, group: String,
            path: WalkPath, missing: MutableList<String>
        ): GuardActor? {
            val skelBlocks = readBlocks(assets.read(skeletonNif) ?: run { missing += skeletonNif; return null }).first
            if (skelBlocks.isEmpty()) return null
            val nodes = ArrayList<SkelNode>(); val byName = HashMap<String, Int>()
            flattenSkeleton(skelBlocks, nodes, byName)

            val kf = readBlocks(assets.read(kfPath) ?: run { missing += kfPath; return null }).first
            if (kf.isEmpty()) return null
            val keysText = kf.firstOrNull { it.type == "NiTextKeyExtraData" }?.textKeys ?: return null
            // several text keys can share one timestamp, separated by newlines
            fun keyTime(name: String) = keysText.firstOrNull { k -> k.second.lines().any { it.trim().equals(name, true) } }?.first
            val loopStart = keyTime("$group: Loop Start") ?: return null
            val loopEnd = keyTime("$group: Loop Stop") ?: return null
            val tracks = readTracks(kf)

            val skMeshes = ArrayList<MwMesh>(); val skSources = ArrayList<SkinnedSource>()
            val body = assets.read(bodyNif) ?: run { missing += bodyNif; return null }
            parseSkinned(body, skMeshes, skSources)
            val skinned = skMeshes.indices.map { i ->
                val src = skSources[i]
                Skinned(src, sceneMeshOf(assets, skMeshes[i]), IntArray(src.boneNames.size) { byName[src.boneNames[it]] ?: -1 })
            }
            if (skinned.isEmpty()) return null
            return GuardActor(nodes, tracks, loopStart, loopEnd, emptyList(), skinned, -1, floatArrayOf(0f, 0f, 0f), path, walkSpeed(tracks, loopStart, loopEnd))
        }

        fun load(assets: MwAssets, path: WalkPath, missing: MutableList<String>): GuardActor? {
            val skelBlocks = readBlocks(assets.read("meshes/xbase_anim.nif") ?: run { missing += "xbase_anim.nif"; return null }).first
            if (skelBlocks.isEmpty()) return null

            val nodes = ArrayList<SkelNode>()
            val byName = HashMap<String, Int>()
            flattenSkeleton(skelBlocks, nodes, byName)

            // walk cycle (one-handed, so the right arm holds the torch out)
            val kf = readBlocks(assets.read("meshes/xbase_anim.kf") ?: run { missing += "xbase_anim.kf"; return null }).first
            if (kf.isEmpty()) return null
            val keysText = kf.firstOrNull { it.type == "NiTextKeyExtraData" }?.textKeys ?: return null
            val loopStart = keysText.firstOrNull { it.second.equals("WalkForward1h: Loop Start", true) }?.first ?: return null
            val loopEnd = keysText.firstOrNull { it.second.equals("WalkForward1h: Loop Stop", true) }?.first ?: return null
            val tracks = readTracks(kf)

            val rigid = ArrayList<Rigid>()
            fun attach(file: String, nodeName: String) {
                val node = byName[nodeName] ?: return
                val bytes = assets.read("meshes/$file") ?: run { missing += file.substringAfterLast('/'); return }
                val model = parseNif(bytes)
                for (m in model.meshes) {
                    val tex = m.texture?.let { assets.readTexture(it) }
                    val out = SceneMesh(
                        FloatArray(m.positions.size), FloatArray(m.normals.size), m.uvs, m.colors, m.indices, tex,
                        if (m.diffuse.all { it == 0f }) floatArrayOf(1f, 1f, 1f) else m.diffuse, m.emissive, m.alphaBlend, m.alphaTest
                    )
                    rigid += Rigid(node, m, out)
                }
            }
            for ((file, part) in BODY) {
                if (part == "Head" || part == "Neck" || part == "Groin") attach(file, part)
                else for (side in listOf("Left", "Right")) attach(file, "$side $part")
            }
            attach("b/b_n_imperial_m_hair_01.nif", "Head")
            // armor
            attach("a/a_m_imperialchain_helmet.nif", "Head")
            for (side in listOf("Left", "Right")) {
                attach("a/a_m_imperialchain_pauldron_ua.nif", "$side Clavicle")
                attach("a/a_m_imperialchain_greaves_ul.nif", "$side Upper Leg")
            }

            // torch in the right hand
            val torchNode = byName["Weapon Bone"] ?: return null
            val torchBytes = assets.read("meshes/l/light_com_torch_01.nif") ?: run { missing += "light_com_torch_01.nif"; return null }
            val torch = parseNif(torchBytes)
            var tipZ = 0f
            for (m in torch.meshes) {
                val tex = m.texture?.let { assets.readTexture(it) }
                val out = SceneMesh(
                    FloatArray(m.positions.size), FloatArray(m.normals.size), m.uvs, m.colors, m.indices, tex,
                    if (m.diffuse.all { it == 0f }) floatArrayOf(1f, 1f, 1f) else m.diffuse, m.emissive, m.alphaBlend, m.alphaTest
                )
                rigid += Rigid(torchNode, m, out)
            }
            tipZ = torch.max[2]

            // skinned parts: body skin and cuirass
            val skMeshes = ArrayList<MwMesh>(); val skSources = ArrayList<SkinnedSource>()
            for (f in listOf("meshes/b/b_n_imperial_m_skins.nif", "meshes/a/a_m_imperialchain_cuirass.nif")) {
                val bytes = assets.read(f) ?: run { missing += f.substringAfterLast('/'); null } ?: continue
                parseSkinned(bytes, skMeshes, skSources)
            }
            val skinned = skMeshes.indices.map { i ->
                val m = skMeshes[i]; val src = skSources[i]
                val tex = m.texture?.let { assets.readTexture(it) }
                val out = SceneMesh(
                    FloatArray(m.positions.size), FloatArray(m.normals.size), m.uvs, m.colors, m.indices, tex,
                    if (m.diffuse.all { it == 0f }) floatArrayOf(1f, 1f, 1f) else m.diffuse, m.emissive, m.alphaBlend, m.alphaTest
                )
                Skinned(src, out, IntArray(src.boneNames.size) { byName[src.boneNames[it]] ?: -1 })
            }

            // walking speed from how far the animation moves the root over one loop
            val speed = walkSpeed(tracks, loopStart, loopEnd)
            return GuardActor(nodes, tracks, loopStart, loopEnd, rigid, skinned, torchNode, floatArrayOf(0f, 0f, tipZ + 4f), path, speed)
        }
    }
}
