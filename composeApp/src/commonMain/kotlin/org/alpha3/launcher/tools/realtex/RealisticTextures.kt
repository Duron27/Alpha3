package org.alpha3.launcher.tools.realtex

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.IOException
import org.alpha3.launcher.mods.downloader.ModHttp
import org.alpha3.launcher.tools.openmw.TextCodec
import org.alpha3.launcher.tools.openmw.ToolFs
import org.alpha3.launcher.tools.openmw.ToolNotFound
import org.alpha3.launcher.tools.openmw.ToolPlatform
import org.alpha3.launcher.tools.openmw.Vfs
import org.alpha3.launcher.tools.openmw.cleanPath
import org.alpha3.launcher.tools.openmw.enableInOpenMWConfig
import org.alpha3.launcher.tools.openmw.joinPath
import org.alpha3.launcher.tools.openmw.readOpenMW
import org.alpha3.launcher.tools.render.Bitmap
import org.alpha3.launcher.tools.render.decodeDds
import org.alpha3.launcher.tools.render.decodeEncodedImage
import org.alpha3.launcher.tools.render.decodeTga
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A game texture made over from a CC0 photo texture of Poly Haven (polyhaven.com): the original's colour and grime
 * (blurred to a [grid] x [grid] picture) times the photo's detail, at the original's detail contrast.
 */
class RealMapping(
    /** The game's texture, as in textures/ without the extension. */
    val texture: String,
    /** The Poly Haven asset id. */
    val asset: String,
    /** How many times the photo repeats across the texture. */
    val repeat: Int = 1,
    /** The original kept at this many cells across; higher keeps more of its painted detail, 0 all of it. */
    val grid: Int = 8,
    /** How strongly the photo's detail shows; null: matched to the original's own detail contrast. */
    val contrast: Float? = null,
    /** The photo turned a quarter: its grain runs the other way. */
    val rotate: Boolean = false,
)

/**
 * Balmora and the land around it: the Hlaalu plaster and its trims, the roads and cobbles, wood (posts, boardwalks,
 * crates, doors), bark, rusty iron, rock and the ground (scrub, grass, dirt). Textures with see-through parts (grass
 * blades, leaves, rope) and patterns a photo cannot follow (woven straw) are left alone.
 */
val BALMORA_SET = listOf(
    // Hlaalu plaster: the original whole (its stains and stone trims), the photo's weathering over it
    *listOf(
        "tx_hlaalu_wall2_01", "tx_hlaalu_wall2_02", "tx_hlaalu_wall2_03", "tx_hlaalu_wall1_01", "tx_hlaalu_wall1_02",
        "tx_hlaalu_wall1_03", "tx_hlaalu_topedge_02", "tx_hlaalu_sideedge_01",
    ).map { RealMapping(it, "grey_plaster_02", grid = 0, contrast = 1f) }.toTypedArray(),
    // roads
    RealMapping("tx_wg_road_01", "cobblestone_floor_08", grid = 4),
    RealMapping("tx_wg_mainroad_01", "rock_embedded_floor", grid = 4),
    RealMapping("tx_wg_cobblestones_01", "cobblestone_square", grid = 4),
    RealMapping("tx_wg_dirtroad_01", "dirt", grid = 4),
    // wood
    RealMapping("tx_wood_brown_posts_01", "rough_wood", grid = 4),
    RealMapping("tx_wood_brown_posts_02", "rough_wood", grid = 4),
    RealMapping("tx_wood_wethered", "rough_wood", grid = 4, rotate = true),
    // painted frames and fittings: the original whole, the photo's grain over it
    RealMapping("tx_wood_brown_box_01", "rough_wood", grid = 0, contrast = 0.8f),
    RealMapping("tx_wood_brown_box_02", "rough_wood", grid = 0, contrast = 0.8f),
    RealMapping("tx_hlaalu_door_02", "rough_wood", grid = 0, contrast = 0.8f),
    // bark
    RealMapping("tx_bark_03", "bark_platanus", grid = 4),
    RealMapping("tx_bark_04", "bark_brown_02", grid = 4),
    RealMapping("tx_bark_05", "bark_willow_02", grid = 4),
    RealMapping("tx_ashtree_bark_01", "bark_willow", grid = 4),
    // iron
    RealMapping("tx_metal_iron_rusty_01", "rusty_metal_04", grid = 4),
    // rock (objects and land)
    RealMapping("tx_rock_grey_01", "mossy_rock", grid = 4),
    RealMapping("tx_bc_rock_03", "rocky_terrain_02", grid = 4),
    RealMapping("tx_rm_rock_01", "dark_rock", grid = 4),
    RealMapping("tx_rock_wg_01", "rock_ground", grid = 4),
    RealMapping("tx_rm_grayrock_01", "lichen_rock", grid = 4),
    RealMapping("tx_ma_rock04", "dark_rock_02", grid = 4),
    RealMapping("tx_coastal_rock_01", "rock_face_04", grid = 4),
    // ground
    RealMapping("tx_wg_rockydirt_01", "rocky_trail", grid = 4),
    RealMapping("tx_rockyscrub_01", "rocks_ground_02", grid = 4),
    RealMapping("tx_bc_rockyscrub", "rocks_ground_02", grid = 4),
    RealMapping("tx_scrubplain_01", "sparse_grass", grid = 4),
    RealMapping("tx_grass_01", "leafy_grass", grid = 4),
    RealMapping("tx_ai_grass_01", "grass_ground", grid = 4),
    RealMapping("tx_ai_grass_dirt_01", "grass_path_2", grid = 4),
    RealMapping("tx_ai_dirt_01", "forrest_ground_01", grid = 4),
)

private val json = Json { ignoreUnknownKeys = true }

/** The folder the textures go to: a data= folder of its own, loaded after the mods; removing it undoes everything. */
fun realTexturesDir(generatedDir: String): String = joinPath(generatedDir.trimEnd('/').substringBeforeLast('/'), "alpha3_realtex")

class RealTexturesResult(val made: Int, val skipped: Int)

/**
 * Makes [mappings] into [outDir]/textures (`<name>.dds`, and `<name>_n.dds` for [Shaders] auto use object/terrain
 * normal maps) and adds [outDir] to openmw.cfg. The originals are read through the load order as it is, so a
 * retexture mod's colours are the ones kept. Downloads are kept in [outDir]/.downloads.
 */
suspend fun buildRealTextures(
    fs: ToolFs,
    platform: ToolPlatform,
    cfg: String,
    outDir: String,
    mappings: List<RealMapping>,
    size: Int = 1024,
    log: (String) -> Unit,
): RealTexturesResult {
    val omw = readOpenMW(cfg, platform, fs)
    val own = cleanPath(outDir).trimEnd('/')
    val vfs = Vfs(omw.data.filter { cleanPath(it).trimEnd('/') != own }, omw.archives, TextCodec.forName(omw.encoding), fs)
    val textures = joinPath(outDir, "textures")
    val downloads = joinPath(outDir, ".downloads")
    // made again from scratch: nothing left over from an earlier set
    deleteTree(fs, textures)
    fs.mkdirs(textures)
    fs.mkdirs(downloads)

    val photos = HashMap<String, Pair<Bitmap, Bitmap?>>()
    val credits = LinkedHashSet<String>()
    var made = 0
    var skipped = 0
    for (m in mappings) {
        currentCoroutineContext().ensureActive()
        val original = readOriginal(vfs, fs, m.texture)
        if (original == null) {
            log("${m.texture}: not in the load order, skipped")
            skipped++
            continue
        }
        val (diffuse, normal) = photos.getOrPut(m.asset) { downloadAsset(fs, downloads, m.asset, log) }
        credits += m.asset
        val w = min(size, original.width * 4)
        val h = min(size, original.height * 4)
        val colour = blend(original, diffuse, w, h, m)
        writeDds(fs, joinPath(textures, "${m.texture}.dds"), w, h, colour)
        if (normal != null) writeDds(fs, joinPath(textures, "${m.texture}_n.dds"), w, h, normalMap(normal, w, h, m))
        log("${m.texture}: ${m.asset} (${w}x$h)")
        made++
    }
    writeText(
        fs, joinPath(outDir, "CREDITS.txt"),
        "Photo textures from Poly Haven (https://polyhaven.com), CC0 1.0 (public domain):\n" +
            credits.joinToString("") { "  https://polyhaven.com/a/$it\n" } +
            "Each is blended with the colours of the game texture it replaces, by the Alpha3 launcher.\n",
    )
    val edit = enableInOpenMWConfig(fs, cfg, omw, "realtex", outDir)
    if (edit.changed) log("openmw.cfg: ${edit.added.joinToString()}")
    return RealTexturesResult(made, skipped)
}

/** Takes [outDir] out of openmw.cfg and deletes it. */
fun removeRealTextures(fs: ToolFs, cfg: String, outDir: String, log: (String) -> Unit) {
    val own = cleanPath(outDir).trimEnd('/')
    val file = if (fs.isDirectory(cfg)) joinPath(cfg, "openmw.cfg") else cfg
    if (fs.isFile(file)) {
        val text = fs.readText(file)
        val kept = text.lines().filterNot { line ->
            val t = line.trim()
            t.startsWith("data=") && cleanPath(t.removePrefix("data=").trim().removeSurrounding("\"").replace("&\"", "\"").replace("&&", "&")).trimEnd('/') == own
        }
        if (kept.size != text.lines().size) {
            writeText(fs, file, kept.joinToString("\n"))
            log("openmw.cfg: removed data=\"$own\"")
        }
    }
    deleteTree(fs, outDir)
    log("Deleted $outDir")
}

private fun deleteTree(fs: ToolFs, path: String) {
    if (fs.isDirectory(path)) fs.list(path)?.forEach { deleteTree(fs, joinPath(path, it)) }
    fs.delete(path)
}

private fun writeText(fs: ToolFs, path: String, text: String) {
    val tmp = "$path.tmp"
    fs.delete(tmp)
    val w = fs.openWrite(tmp)
    try { val b = text.encodeToByteArray(); w.write(b, 0, b.size) } finally { w.close() }
    fs.delete(path)
    fs.rename(tmp, path)
}

private fun readBytes(fs: ToolFs, path: String, offset: Long, size: Long): ByteArray {
    val out = ByteArray(size.toInt())
    val r = fs.openRead(path)
    try {
        var done = 0
        while (done < out.size) {
            val n = r.read(offset + done, out, done, out.size - done)
            if (n <= 0) throw IOException("$path: short read")
            done += n
        }
    } finally { r.close() }
    return out
}

private fun readOriginal(vfs: Vfs, fs: ToolFs, texture: String): Bitmap? {
    for (ext in listOf("dds", "tga")) {
        val s = try { vfs.resolve("textures/$texture.$ext") } catch (_: ToolNotFound) { continue }
        val bytes = readBytes(fs, s.path, s.offset, s.size)
        return (if (ext == "dds") decodeDds(bytes) else decodeTga(bytes)) ?: continue
    }
    return null
}

private suspend fun fetch(url: String): ByteArray {
    val response = ModHttp.client.get(url) { header("User-Agent", "Alpha3/2.71") }
    if (!response.status.isSuccess()) throw IOException("HTTP ${response.status.value} for $url")
    return response.readRawBytes()
}

/** The asset's 1k diffuse and DirectX-style normal map (the one Morrowind-era normal maps use), downloaded once. */
private suspend fun downloadAsset(fs: ToolFs, dir: String, asset: String, log: (String) -> Unit): Pair<Bitmap, Bitmap?> {
    suspend fun file(map: String, key: String): Bitmap? {
        val local = joinPath(dir, "${asset}_${map}_1k.jpg")
        val bytes = if (fs.isFile(local) && fs.size(local) > 0) readBytes(fs, local, 0, fs.size(local)) else {
            val files = json.parseToJsonElement(ModHttp.getText("https://api.polyhaven.com/files/$asset")).jsonObject
            val url = (((files[key] as? JsonObject)?.get("1k") as? JsonObject)?.get("jpg") as? JsonObject)
                ?.get("url")?.jsonPrimitive?.content ?: return null
            log("Downloading $asset ($map)")
            fetch(url).also { b ->
                val w = fs.openWrite(local)
                try { w.write(b, 0, b.size) } finally { w.close() }
            }
        }
        return decodeEncodedImage(bytes)
    }
    val diffuse = file("diff", "Diffuse") ?: throw IOException("$asset: no diffuse map on Poly Haven")
    return diffuse to file("nor_dx", "nor_dx")
}

// ---- image work: ARGB ints -------------------------------------------------------------------------------------------

private class Rgb(val w: Int, val h: Int) {
    val r = FloatArray(w * h)
    val g = FloatArray(w * h)
    val b = FloatArray(w * h)
}

private fun wrap(v: Int, n: Int) = ((v % n) + n) % n

private fun Bitmap.pixels(): IntArray = levels[0].third

/** The photo at [w] x [h]: turned if asked, repeated, sampled bilinearly with wrap. */
private fun resample(src: Bitmap, w: Int, h: Int, m: RealMapping): Rgb {
    val out = Rgb(w, h)
    val px = src.pixels()
    val sw = src.width
    val sh = src.height
    for (y in 0 until h) for (x in 0 until w) {
        // the position in the photo, 0..1, repeated; both by the width, so a long or tall texture is not squashed
        var u = (x + 0.5f) / w * m.repeat
        var v = (y + 0.5f) / w * m.repeat
        if (m.rotate) { val t = u; u = v; v = -t }
        val fx = u * sw - 0.5f
        val fy = v * sh - 0.5f
        val x0 = kotlin.math.floor(fx).toInt()
        val y0 = kotlin.math.floor(fy).toInt()
        val ax = fx - x0
        val ay = fy - y0
        var r = 0f; var g = 0f; var b = 0f
        for (j in 0..1) for (i in 0..1) {
            val wt = (if (i == 0) 1 - ax else ax) * (if (j == 0) 1 - ay else ay)
            val p = px[wrap(y0 + j, sh) * sw + wrap(x0 + i, sw)]
            r += wt * ((p shr 16) and 255); g += wt * ((p shr 8) and 255); b += wt * (p and 255)
        }
        val o = y * w + x
        out.r[o] = r; out.g[o] = g; out.b[o] = b
    }
    return out
}

/** [src] averaged to [gw] x [gh] cells, then spread back over [w] x [h] bilinearly (wrapping: textures tile). */
private fun lowFrequency(src: Bitmap, gw: Int, gh: Int, w: Int, h: Int): Rgb {
    val px = src.pixels()
    val cr = FloatArray(gw * gh); val cg = FloatArray(gw * gh); val cb = FloatArray(gw * gh); val cn = FloatArray(gw * gh)
    for (y in 0 until src.height) for (x in 0 until src.width) {
        val c = (y * gh / src.height) * gw + (x * gw / src.width)
        val p = px[y * src.width + x]
        cr[c] += ((p shr 16) and 255).toFloat(); cg[c] += ((p shr 8) and 255).toFloat(); cb[c] += (p and 255).toFloat(); cn[c] += 1f
    }
    for (c in cn.indices) if (cn[c] > 0) { cr[c] /= cn[c]; cg[c] /= cn[c]; cb[c] /= cn[c] }
    val out = Rgb(w, h)
    for (y in 0 until h) for (x in 0 until w) {
        val fx = (x + 0.5f) / w * gw - 0.5f
        val fy = (y + 0.5f) / h * gh - 0.5f
        val x0 = kotlin.math.floor(fx).toInt()
        val y0 = kotlin.math.floor(fy).toInt()
        val ax = fx - x0
        val ay = fy - y0
        var r = 0f; var g = 0f; var b = 0f
        for (j in 0..1) for (i in 0..1) {
            val wt = (if (i == 0) 1 - ax else ax) * (if (j == 0) 1 - ay else ay)
            val c = wrap(y0 + j, gh) * gw + wrap(x0 + i, gw)
            r += wt * cr[c]; g += wt * cg[c]; b += wt * cb[c]
        }
        val o = y * w + x
        out.r[o] = r; out.g[o] = g; out.b[o] = b
    }
    return out
}

private fun lum(r: Float, g: Float, b: Float) = 0.3f * r + 0.59f * g + 0.11f * b

private fun stdDev(values: FloatArray): Float {
    var s = 0.0; var s2 = 0.0
    for (v in values) { s += v; s2 += v.toDouble() * v }
    val mean = s / values.size
    return sqrt(max(0.0, s2 / values.size - mean * mean)).toFloat()
}

private fun gridOf(m: RealMapping, w: Int, h: Int): Pair<Int, Int> =
    if (m.grid <= 0) w to h
    else if (w >= h) m.grid to max(1, m.grid * h / w) else max(1, m.grid * w / h) to m.grid

private fun blend(original: Bitmap, photo: Bitmap, w: Int, h: Int, m: RealMapping): IntArray {
    val (gw, gh) = gridOf(m, original.width, original.height)
    val low = lowFrequency(original, gw, gh, w, h)
    val real = resample(photo, w, h, m)
    val n = w * h
    val mr = real.r.average().toFloat().coerceAtLeast(1f)
    val mg = real.g.average().toFloat().coerceAtLeast(1f)
    val mb = real.b.average().toFloat().coerceAtLeast(1f)
    val k = m.contrast ?: detailContrast(original, gw, gh, real, mr, mg, mb)

    return IntArray(n) { i ->
        fun ch(lowV: Float, realV: Float, mean: Float) = (lowV * (1 + (realV / mean - 1) * k)).roundToInt().coerceIn(0, 255)
        (255 shl 24) or (ch(low.r[i], real.r[i], mr) shl 16) or (ch(low.g[i], real.g[i], mg) shl 8) or ch(low.b[i], real.b[i], mb)
    }
}

/** The original's detail contrast against its own blur over the photo's against its mean: the photo's detail gain. */
private fun detailContrast(original: Bitmap, gw: Int, gh: Int, real: Rgb, mr: Float, mg: Float, mb: Float): Float {
    val origLow = lowFrequency(original, gw, gh, original.width, original.height)
    val op = original.pixels()
    val origDetail = FloatArray(op.size) { i ->
        val p = op[i]
        lum(((p shr 16) and 255).toFloat(), ((p shr 8) and 255).toFloat(), (p and 255).toFloat()) /
            max(1f, lum(origLow.r[i], origLow.g[i], origLow.b[i]))
    }
    val realDetail = FloatArray(real.w * real.h) { lum(real.r[it] / mr, real.g[it] / mg, real.b[it] / mb) }
    return (stdDev(origDetail) / max(stdDev(realDetail), 1e-3f)).coerceIn(0.8f, 3f)
}

/** The photo's normal map laid out as [blend] lays out its colour, the vectors turned with it. */
private fun normalMap(normal: Bitmap, w: Int, h: Int, m: RealMapping): IntArray {
    val s = resample(normal, w, h, m)
    return IntArray(w * h) { i ->
        var nx = s.r[i] / 127.5f - 1
        var ny = s.g[i] / 127.5f - 1
        val nz = s.b[i] / 127.5f - 1
        // rotate maps destination u to source v and destination v to source -u
        if (m.rotate) { val t = nx; nx = -ny; ny = t }
        val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-4f)
        fun enc(v: Float) = ((v / len * 0.5f + 0.5f) * 255).roundToInt().coerceIn(0, 255)
        (255 shl 24) or (enc(nx) shl 16) or (enc(ny) shl 8) or enc(nz)
    }
}

// ---- DDS: BC1 (DXT1) with a full mip chain ---------------------------------------------------------------------------

private fun mips(w0: Int, h0: Int, px0: IntArray): List<Triple<Int, Int, IntArray>> {
    val out = ArrayList<Triple<Int, Int, IntArray>>()
    var w = w0; var h = h0; var px = px0
    out += Triple(w, h, px)
    while (w > 1 || h > 1) {
        val nw = max(1, w / 2); val nh = max(1, h / 2)
        val next = IntArray(nw * nh)
        for (y in 0 until nh) for (x in 0 until nw) {
            var r = 0; var g = 0; var b = 0; var c = 0
            for (j in 0..1) for (i in 0..1) {
                val sx = min(w - 1, x * 2 + i); val sy = min(h - 1, y * 2 + j)
                val p = px[sy * w + sx]
                r += (p shr 16) and 255; g += (p shr 8) and 255; b += p and 255; c++
            }
            next[y * nw + x] = (255 shl 24) or ((r / c) shl 16) or ((g / c) shl 8) or (b / c)
        }
        w = nw; h = nh; px = next
        out += Triple(w, h, px)
    }
    return out
}

private fun to565(r: Int, g: Int, b: Int) = ((r * 31 + 127) / 255 shl 11) or ((g * 63 + 127) / 255 shl 5) or ((b * 31 + 127) / 255)

private fun from565(c: Int) = intArrayOf(((c shr 11) and 31) * 255 / 31, ((c shr 5) and 63) * 255 / 63, (c and 31) * 255 / 31)

/** One 4x4 block: end points on the colours' principal axis, inset a little, four-colour mode. */
private fun encodeBlock(block: IntArray, out: ByteArray, at: Int) {
    var mr = 0f; var mg = 0f; var mb = 0f
    for (p in block) { mr += (p shr 16) and 255; mg += (p shr 8) and 255; mb += p and 255 }
    mr /= 16; mg /= 16; mb /= 16
    // covariance, then the principal axis by power iteration
    var xx = 0f; var xy = 0f; var xz = 0f; var yy = 0f; var yz = 0f; var zz = 0f
    for (p in block) {
        val r = ((p shr 16) and 255) - mr; val g = ((p shr 8) and 255) - mg; val b = (p and 255) - mb
        xx += r * r; xy += r * g; xz += r * b; yy += g * g; yz += g * b; zz += b * b
    }
    var ax = 1f; var ay = 1f; var az = 1f
    repeat(8) {
        val nx = xx * ax + xy * ay + xz * az
        val ny = xy * ax + yy * ay + yz * az
        val nz = xz * ax + yz * ay + zz * az
        val l = max(abs(nx), max(abs(ny), abs(nz)))
        if (l < 1e-6f) { ax = 0f; ay = 0f; az = 0f; return@repeat }
        ax = nx / l; ay = ny / l; az = nz / l
    }
    var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
    for (p in block) {
        val t = (((p shr 16) and 255) - mr) * ax + (((p shr 8) and 255) - mg) * ay + ((p and 255) - mb) * az
        lo = min(lo, t); hi = max(hi, t)
    }
    if (lo == Float.MAX_VALUE) { lo = 0f; hi = 0f }
    val inset = (hi - lo) / 16
    lo += inset; hi -= inset
    fun at(t: Float, a: Float, m: Float) = (m + t * a).roundToInt().coerceIn(0, 255)
    var c0 = to565(at(hi, ax, mr), at(hi, ay, mg), at(hi, az, mb))
    var c1 = to565(at(lo, ax, mr), at(lo, ay, mg), at(lo, az, mb))
    if (c0 < c1) { val t = c0; c0 = c1; c1 = t }
    val e0 = from565(c0); val e1 = from565(c1)
    val palette = arrayOf(
        e0, e1,
        IntArray(3) { (2 * e0[it] + e1[it]) / 3 },
        IntArray(3) { (e0[it] + 2 * e1[it]) / 3 },
    )
    var indices = 0
    for (i in 0 until 16) {
        val p = block[i]
        val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
        var best = 0; var bestD = Int.MAX_VALUE
        // c0 == c1: only index 0 is meaningful in four-colour mode
        val count = if (c0 == c1) 1 else 4
        for (k in 0 until count) {
            val q = palette[k]
            val d = (r - q[0]) * (r - q[0]) + (g - q[1]) * (g - q[1]) + (b - q[2]) * (b - q[2])
            if (d < bestD) { bestD = d; best = k }
        }
        indices = indices or (best shl (2 * i))
    }
    out[at] = c0.toByte(); out[at + 1] = (c0 shr 8).toByte()
    out[at + 2] = c1.toByte(); out[at + 3] = (c1 shr 8).toByte()
    for (i in 0 until 4) out[at + 4 + i] = (indices shr (8 * i)).toByte()
}

private fun encodeBc1(w: Int, h: Int, px: IntArray): ByteArray {
    val bw = (w + 3) / 4; val bh = (h + 3) / 4
    val out = ByteArray(bw * bh * 8)
    val block = IntArray(16)
    for (by in 0 until bh) for (bx in 0 until bw) {
        for (j in 0 until 4) for (i in 0 until 4) block[j * 4 + i] = px[min(h - 1, by * 4 + j) * w + min(w - 1, bx * 4 + i)]
        encodeBlock(block, out, (by * bw + bx) * 8)
    }
    return out
}

private fun writeDds(fs: ToolFs, path: String, w: Int, h: Int, px: IntArray) {
    val chain = mips(w, h, px)
    val header = ByteArray(128)
    fun put(o: Int, v: Int) { for (i in 0 until 4) header[o + i] = (v shr (8 * i)).toByte() }
    put(0, 0x20534444) // "DDS "
    put(4, 124)
    put(8, 0x1 or 0x2 or 0x4 or 0x1000 or 0x20000 or 0x80000) // caps height width pixelformat mipmapcount linearsize
    put(12, h)
    put(16, w)
    put(20, max(1, (w + 3) / 4) * max(1, (h + 3) / 4) * 8)
    put(28, chain.size)
    put(76, 32)
    put(80, 0x4) // fourcc
    put(84, 0x31545844) // "DXT1"
    put(108, 0x1000 or 0x8 or 0x400000) // texture complex mipmap
    val tmp = "$path.tmp"
    fs.delete(tmp)
    val wr = fs.openWrite(tmp)
    try {
        wr.write(header, 0, header.size)
        for ((lw, lh, lp) in chain) { val b = encodeBc1(lw, lh, lp); wr.write(b, 0, b.size) }
    } finally { wr.close() }
    fs.delete(path)
    fs.rename(tmp, path)
}

