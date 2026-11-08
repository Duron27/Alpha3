package org.alpha3.launcher.cs.ui

import org.alpha3.launcher.cs.model.CellRef
import org.alpha3.launcher.cs.model.CsDocument
import org.alpha3.launcher.cs.model.RefKey
import org.alpha3.launcher.cs.model.placeable
import org.alpha3.launcher.cs.model.zstringOf
import org.alpha3.launcher.tools.render.Bitmap
import org.alpha3.launcher.tools.render.MwAssets
import org.alpha3.launcher.tools.render.MwModel
import org.alpha3.launcher.tools.render.RenderLight
import org.alpha3.launcher.tools.render.SceneMesh
import org.alpha3.launcher.tools.render.parseNif
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** What an object id looks like in a cell: its model (data path), or a marker shape for things without one. */
internal class ObjectLook(val tag: String?, val mesh: String?, val light: FloatArray?)

/** Models, textures and object looks read once per session; cells and edits reuse them. */
internal class SceneCache(private val assets: MwAssets) {
    private val models = HashMap<String, MwModel?>()
    private val textures = HashMap<String, Bitmap?>()
    private val looks = HashMap<String, ObjectLook>()

    fun model(path: String): MwModel? = models.getOrPut(path.lowercase()) {
        assets.read(path)?.let { runCatching { parseNif(it, includeSkinned = true) }.getOrNull() }?.takeIf { it.meshes.isNotEmpty() }
    }

    fun texture(name: String): Bitmap? = textures.getOrPut(name.lowercase()) { assets.readTexture(name) }

    fun look(doc: CsDocument, id: String): ObjectLook = looks.getOrPut(id.lowercase()) {
        val rec = runCatching { doc.placeable(id) }.getOrNull() ?: return@getOrPut ObjectLook(null, null, null)
        val modl = rec.subs.firstOrNull { it.tag == "MODL" }?.let { zstringOf(it.data).trim() }?.takeIf { it.isNotEmpty() }
        val mesh = modl?.replace('\\', '/')?.let { if (it.startsWith("meshes/", true)) it else "meshes/$it" }
        // a light's radius and colour (LHDT: weight, value, time, radius, r, g, b, a, flags)
        val light = if (rec.tag == "LIGH") rec.subs.firstOrNull { it.tag == "LHDT" && it.data.size >= 24 }?.data?.let { d ->
            fun i32(o: Int) = (d[o].toInt() and 255) or ((d[o + 1].toInt() and 255) shl 8) or ((d[o + 2].toInt() and 255) shl 16) or (d[o + 3].toInt() shl 24)
            floatArrayOf(i32(12).toFloat(), (d[16].toInt() and 255) / 255f, (d[17].toInt() and 255) / 255f, (d[18].toInt() and 255) / 255f, i32(20).toFloat())
        } else null
        ObjectLook(rec.tag, mesh, light)
    }

    fun forget(id: String) { looks.remove(id.lowercase()) }
}

/** One reference drawn: its meshes in world space, a highlighted copy, and its bounds for picking. */
internal class PlacedRef(
    val key: RefKey,
    val signature: String,
    val meshes: List<SceneMesh>,
    val highlighted: List<SceneMesh>,
    val min: FloatArray,
    val max: FloatArray,
    val light: RenderLight?,
    /** The light's flags (flicker, pulse...; LHDT), 0 for objects that give no light. */
    val lightFlags: Int = 0,
)

/** A reference's model-to-world rotation as OpenMW applies it: X, then Y, then Z, each by minus the stored angle. */
internal fun rotationMatrix(rx: Float, ry: Float, rz: Float): FloatArray {
    val cx = cos(-rx); val sx = sin(-rx)
    val cy = cos(-ry); val sy = sin(-ry)
    val cz = cos(-rz); val sz = sin(-rz)
    // Rz * Ry * Rx, row-major
    return floatArrayOf(
        cz * cy, cz * sy * sx - sz * cx, cz * sy * cx + sz * sx,
        sz * cy, sz * sy * sx + cz * cx, sz * sy * cx - cz * sx,
        -sy, cy * sx, cy * cx,
    )
}

private val HIGHLIGHT = floatArrayOf(0.30f, 0.22f, 0.02f)

/** A box marker for objects without a model: NPCs, levelled lists, lights without a mesh. */
private fun markerMesh(tag: String?): Pair<FloatArray, IntArray> {
    val (w, h) = when (tag) {
        "NPC_", "LEVC" -> 22f to 128f
        "LIGH" -> 8f to 16f
        else -> 16f to 16f
    }
    val z0 = if (tag == "NPC_" || tag == "LEVC") 0f else -h / 2
    val xs = floatArrayOf(-w, w); val zs = floatArrayOf(z0, z0 + h)
    val p = FloatArray(24)
    var k = 0
    for (z in zs) for (y in xs) for (x in xs) { p[k++] = x; p[k++] = y; p[k++] = z }
    val idx = intArrayOf(0, 1, 3, 0, 3, 2, 4, 6, 7, 4, 7, 5, 0, 4, 5, 0, 5, 1, 2, 3, 7, 2, 7, 6, 0, 2, 6, 0, 6, 4, 1, 5, 7, 1, 7, 3)
    return p to idx
}

private fun markerColour(tag: String?) = when (tag) {
    "NPC_" -> floatArrayOf(0.35f, 0.75f, 0.35f)
    "LEVC", "LEVI" -> floatArrayOf(0.75f, 0.45f, 0.85f)
    "LIGH" -> floatArrayOf(1f, 0.85f, 0.4f)
    null -> floatArrayOf(0.85f, 0.3f, 0.3f)
    else -> floatArrayOf(0.6f, 0.6f, 0.6f)
}

/** [ref] placed at [pos] (x, y, z, rotations) and [scale], reusing [previous] when nothing about it changed. */
internal fun placeRef(cache: SceneCache, doc: CsDocument, ref: CellRef, pos: FloatArray, scale: Float, previous: PlacedRef?): PlacedRef {
    val id = ref.objectId
    val signature = "$id|${pos.joinToString(",")}|$scale"
    if (previous != null && previous.signature == signature) return previous
    val look = cache.look(doc, id)
    val model = look.mesh?.let { cache.model(it) }
    val r = rotationMatrix(pos[3], pos[4], pos[5])
    val min = FloatArray(3) { Float.MAX_VALUE }
    val max = FloatArray(3) { -Float.MAX_VALUE }
    fun transform(src: FloatArray, normals: Boolean): FloatArray {
        val out = FloatArray(src.size)
        var i = 0
        while (i < src.size) {
            val x = src[i]; val y = src[i + 1]; val z = src[i + 2]
            var wx = r[0] * x + r[1] * y + r[2] * z
            var wy = r[3] * x + r[4] * y + r[5] * z
            var wz = r[6] * x + r[7] * y + r[8] * z
            if (!normals) {
                wx = wx * scale + pos[0]; wy = wy * scale + pos[1]; wz = wz * scale + pos[2]
                if (wx < min[0]) min[0] = wx; if (wy < min[1]) min[1] = wy; if (wz < min[2]) min[2] = wz
                if (wx > max[0]) max[0] = wx; if (wy > max[1]) max[1] = wy; if (wz > max[2]) max[2] = wz
            }
            out[i] = wx; out[i + 1] = wy; out[i + 2] = wz
            i += 3
        }
        return out
    }
    val meshes = ArrayList<SceneMesh>()
    val lit = ArrayList<SceneMesh>()
    if (model != null) {
        for (m in model.meshes) {
            val p = transform(m.positions, false)
            val n = transform(m.normals, true)
            val tex = m.texture?.let { cache.texture(it) }
            val diffuse = if (m.diffuse.all { it == 0f }) floatArrayOf(1f, 1f, 1f) else m.diffuse
            meshes += SceneMesh(p, n, m.uvs, m.colors, m.indices, tex, diffuse, m.emissive, m.alphaBlend, m.alphaTest)
            lit += SceneMesh(p, n, m.uvs, m.colors, m.indices, tex, diffuse, FloatArray(3) { m.emissive[it] + HIGHLIGHT[it] }, m.alphaBlend, m.alphaTest)
        }
    } else {
        val (mp, mi) = markerMesh(look.tag)
        val p = transform(mp, false)
        val n = FloatArray(p.size).also { a -> for (i in a.indices step 3) a[i + 2] = 1f }
        val c = markerColour(look.tag)
        meshes += SceneMesh(p, n, null, null, mi, null, c, FloatArray(3) { c[it] * 0.3f }, false, false)
        lit += SceneMesh(p, n, null, null, mi, null, c, FloatArray(3) { c[it] * 0.3f + HIGHLIGHT[it] }, false, false)
    }
    // lights that start switched off (flag 0x20) give no light
    val light = look.light?.takeIf { it[4].toInt() and 0x20 == 0 }?.let { l ->
        val radius = l[0].coerceIn(32f, 2048f)
        RenderLight(pos[0], pos[1], pos[2], l[1] * 1.4f, l[2] * 1.4f, l[3] * 1.4f, radius * 1.6f)
    }
    return PlacedRef(ref.key, signature, meshes, lit, min, max, light, look.light?.get(4)?.toInt() ?: 0)
}

/** The camera: orbiting [target] at [dist], as the software renderer places it. */
internal class OrbitCamera(val yaw: Float, val pitch: Float, val dist: Float, val target: FloatArray) {
    val eye: FloatArray
    val fwd: FloatArray
    val right: FloatArray
    val up: FloatArray

    init {
        val cp = cos(pitch); val sp = sin(pitch); val cy = cos(yaw); val sy = sin(yaw)
        eye = floatArrayOf(target[0] + dist * cp * sy, target[1] - dist * cp * cy, target[2] + dist * sp)
        var fx = target[0] - eye[0]; var fy = target[1] - eye[1]; var fz = target[2] - eye[2]
        val fl = sqrt(fx * fx + fy * fy + fz * fz); fx /= fl; fy /= fl; fz /= fl
        var rx = fy; var ry = -fx
        val rl = sqrt(rx * rx + ry * ry); rx /= rl; ry /= rl
        fwd = floatArrayOf(fx, fy, fz)
        right = floatArrayOf(rx, ry, 0f)
        up = floatArrayOf(ry * fz, -rx * fz, rx * fy - ry * fx)
    }

    /** The view ray through pixel ([sx], [sy]) of a [w] x [h] frame (the renderer's 60 degree vertical view). */
    fun ray(sx: Float, sy: Float, w: Int, h: Int): FloatArray {
        val focal = (h / 2f) / tan(PI / 6).toFloat()
        val a = (sx - w / 2f) / focal
        val b = -(sy - h / 2f) / focal
        val d = FloatArray(3) { fwd[it] + right[it] * a + up[it] * b }
        val l = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
        return floatArrayOf(d[0] / l, d[1] / l, d[2] / l)
    }
}

private fun rayBox(o: FloatArray, d: FloatArray, min: FloatArray, max: FloatArray): Boolean {
    var t0 = 0f; var t1 = Float.MAX_VALUE
    for (a in 0..2) {
        if (kotlin.math.abs(d[a]) < 1e-8f) { if (o[a] < min[a] || o[a] > max[a]) return false; continue }
        var ta = (min[a] - o[a]) / d[a]; var tb = (max[a] - o[a]) / d[a]
        if (ta > tb) { val t = ta; ta = tb; tb = t }
        t0 = maxOf(t0, ta); t1 = minOf(t1, tb)
        if (t0 > t1) return false
    }
    return true
}

/** The nearest reference whose triangles the ray from [o] along [d] hits, with the distance. */
internal fun pick(placed: Collection<PlacedRef>, o: FloatArray, d: FloatArray): Pair<RefKey, Float>? {
    var best: RefKey? = null
    var bestT = Float.MAX_VALUE
    for (p in placed) {
        if (!rayBox(o, d, p.min, p.max)) continue
        for (m in p.meshes) {
            val v = m.positions
            val idx = m.indices
            var i = 0
            while (i + 2 < idx.size) {
                val a = idx[i] * 3; val b = idx[i + 1] * 3; val c = idx[i + 2] * 3
                i += 3
                val e1x = v[b] - v[a]; val e1y = v[b + 1] - v[a + 1]; val e1z = v[b + 2] - v[a + 2]
                val e2x = v[c] - v[a]; val e2y = v[c + 1] - v[a + 1]; val e2z = v[c + 2] - v[a + 2]
                val px = d[1] * e2z - d[2] * e2y; val py = d[2] * e2x - d[0] * e2z; val pz = d[0] * e2y - d[1] * e2x
                val det = e1x * px + e1y * py + e1z * pz
                if (kotlin.math.abs(det) < 1e-9f) continue
                val inv = 1f / det
                val tx = o[0] - v[a]; val ty = o[1] - v[a + 1]; val tz = o[2] - v[a + 2]
                val u = (tx * px + ty * py + tz * pz) * inv
                if (u < 0f || u > 1f) continue
                val qx = ty * e1z - tz * e1y; val qy = tz * e1x - tx * e1z; val qz = tx * e1y - ty * e1x
                val vv = (d[0] * qx + d[1] * qy + d[2] * qz) * inv
                if (vv < 0f || u + vv > 1f) continue
                val t = (e2x * qx + e2y * qy + e2z * qz) * inv
                if (t > 1f && t < bestT) { bestT = t; best = p.key }
            }
        }
    }
    return best?.let { it to bestT }
}

/** Where the ray meets the horizontal plane at height [z], or null when it runs parallel or away. */
internal fun rayPlaneZ(o: FloatArray, d: FloatArray, z: Float): FloatArray? {
    if (kotlin.math.abs(d[2]) < 1e-5f) return null
    val t = (z - o[2]) / d[2]
    if (t <= 0f) return null
    return floatArrayOf(o[0] + d[0] * t, o[1] + d[1] * t, z)
}

/** The box around every drawn reference, for framing the camera (large outliers are ignored by taking quantiles). */
internal fun sceneBounds(placed: Collection<PlacedRef>): Pair<FloatArray, Float> {
    if (placed.isEmpty()) return floatArrayOf(0f, 0f, 0f) to 512f
    val cx = placed.map { (it.min[0] + it.max[0]) / 2 }.sorted()
    val cy = placed.map { (it.min[1] + it.max[1]) / 2 }.sorted()
    val cz = placed.map { (it.min[2] + it.max[2]) / 2 }.sorted()
    fun q(l: List<Float>, f: Float) = l[((l.size - 1) * f).toInt()]
    val center = floatArrayOf(q(cx, 0.5f), q(cy, 0.5f), q(cz, 0.5f))
    val ex = q(cx, 0.95f) - q(cx, 0.05f); val ey = q(cy, 0.95f) - q(cy, 0.05f)
    val radius = (sqrt(ex * ex + ey * ey) / 2f).coerceIn(256f, 8192f)
    return center to radius
}


private fun flicker(x: Float) = 0.78f + 0.22f * (sin(x) * 0.5f + sin(x * 2.3f + 1.7f) * 0.3f + sin(x * 5.1f + 0.3f) * 0.2f)

/** [p]'s light at time [t]: flickering and pulsing as its flags say (as the game animates them). */
internal fun animatedLight(p: PlacedRef, t: Float): RenderLight? {
    val l = p.light ?: return null
    val f = p.lightFlags
    val seed = (p.key.index % 997) * 0.37f
    val k = when {
        f and 0x08 != 0 -> flicker(t * 7f + seed)
        f and 0x40 != 0 -> flicker(t * 2.5f + seed)
        f and 0x80 != 0 -> 0.72f + 0.28f * sin(t * 3.2f + seed)
        f and 0x100 != 0 -> 0.72f + 0.28f * sin(t * 1.1f + seed)
        else -> 1f
    }
    if (k == 1f) return l
    return RenderLight(l.x, l.y, l.z, l.r * k, l.g * k, l.b * k, l.radius * (0.9f + 0.1f * k))
}
