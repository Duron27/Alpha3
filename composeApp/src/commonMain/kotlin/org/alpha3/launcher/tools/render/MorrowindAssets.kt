package org.alpha3.launcher.tools.render

import org.alpha3.launcher.tools.openmw.ToolFs
import kotlin.math.max

/**
 * Minimal readers for Morrowind game assets (BSA archives, NIF meshes, DDS/TGA/BMP textures),
 * just enough to drop real game objects into the light preview scene.
 */

// ---------------------------------------------------------------------------------------------
// BSA archive (Morrowind format, magic 0x00000100)
// ---------------------------------------------------------------------------------------------

internal class MwArchive(private val fs: ToolFs, private val path: String) {
    private val entries = HashMap<String, LongArray>() // normalized path -> [offset, size]

    private fun int(b: ByteArray, o: Int) =
        (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8) or ((b[o + 2].toInt() and 255) shl 16) or ((b[o + 3].toInt() and 255) shl 24)

    private fun ByteArray.fill(r: org.alpha3.launcher.tools.openmw.ToolReader, offset: Long) {
        var done = 0
        while (done < size) {
            val n = r.read(offset + done, this, done, size - done)
            require(n > 0) { "Truncated BSA: $path" }
            done += n
        }
    }

    init {
        val r = fs.openRead(path)
        try {
            val header = ByteArray(12).also { it.fill(r, 0) }
            val hashOffset = int(header, 4).toLong() and 0xFFFFFFFFL
            val count = int(header, 8)
            require(int(header, 0) == 0x100 && count in 0..2_000_000) { "Not a Morrowind BSA: $path" }

            val table = ByteArray(count * 12).also { it.fill(r, 12) }
            val nameBytes = (hashOffset - count * 4L - count * 8L).toInt()
            val names = ByteArray(nameBytes).also { it.fill(r, 12L + count * 12L) }
            val dataStart = 12 + hashOffset + count * 8L

            for (i in 0 until count) {
                val size = int(table, i * 8).toLong() and 0xFFFFFFFFL
                val offset = int(table, i * 8 + 4).toLong() and 0xFFFFFFFFL
                val nameOffset = int(table, count * 8 + i * 4)
                var end = nameOffset
                while (end < names.size && names[end] != 0.toByte()) end++
                val name = names.decodeToString(nameOffset, end)
                entries[normalize(name)] = longArrayOf(dataStart + offset, size)
            }
        } finally {
            r.close()
        }
    }

    fun read(path: String): ByteArray? {
        val e = entries[normalize(path)] ?: return null
        val r = fs.openRead(this.path)
        try {
            return ByteArray(e[1].toInt()).also { it.fill(r, e[0]) }
        } finally {
            r.close()
        }
    }

    private fun normalize(p: String) = p.replace('\\', '/').lowercase()
}

/** Looks assets up in loose files first (so mods override), then in every BSA. */
internal class MwAssets(private val fs: ToolFs, dataDirs: List<String>) {
    private val dirs = dataDirs.filter { fs.isDirectory(it) }.distinct()
    private val archives: List<MwArchive> = dirs.flatMap { dir ->
        fs.list(dir).orEmpty().filter { it.endsWith(".bsa", true) }.sortedBy { it.lowercase() }.map { "$dir/$it" }
    }.mapNotNull { runCatching { MwArchive(fs, it) }.getOrNull() }

    val isEmpty get() = archives.isEmpty() && dirs.isEmpty()

    fun read(path: String): ByteArray? {
        val rel = path.replace('\\', '/').trim('/')
        for (dir in dirs.reversed()) findLoose(dir, rel)?.let { return readAll(it) }
        for (a in archives.reversed()) a.read(rel)?.let { return it }
        return null
    }

    private fun readAll(path: String): ByteArray {
        val out = ByteArray(fs.size(path).toInt())
        val r = fs.openRead(path)
        try {
            var done = 0
            while (done < out.size) {
                val n = r.read(done.toLong(), out, done, out.size - done)
                if (n <= 0) break
                done += n
            }
        } finally {
            r.close()
        }
        return out
    }

    private fun findLoose(root: String, rel: String): String? {
        var cur = root
        for (part in rel.split('/')) {
            val direct = "$cur/$part"
            cur = if (fs.isFile(direct) || fs.isDirectory(direct)) direct
            else "$cur/" + (fs.list(cur)?.firstOrNull { it.equals(part, true) } ?: return null)
        }
        return cur.takeIf { fs.isFile(it) }
    }

    /** Textures are referenced as .tga/.bmp but are often shipped as .dds (and vice versa). */
    fun readTexture(name: String): Bitmap? {
        val base = name.replace('\\', '/').substringBeforeLast('.')
        val candidates = listOf(name, "$base.dds", "$base.tga", "$base.bmp")
            .map { if (it.startsWith("textures/", true)) it else "textures/$it" }
        for (c in candidates) {
            val bytes = read(c) ?: continue
            val bmp = runCatching {
                when (c.substringAfterLast('.').lowercase()) {
                    "dds" -> decodeDds(bytes)
                    "tga" -> decodeTga(bytes)
                    else -> decodeBmp(bytes)
                }
            }.getOrNull()
            if (bmp != null) return bmp
        }
        return null
    }
}

// ---------------------------------------------------------------------------------------------
// Textures
// ---------------------------------------------------------------------------------------------

/** ARGB bitmap with a box-filtered mip chain. */
internal class Bitmap(val width: Int, val height: Int, pixels: IntArray) {
    val levels: List<Triple<Int, Int, IntArray>>
    val hasAlpha: Boolean = pixels.any { (it ushr 24) < 255 }

    init {
        val chain = ArrayList<Triple<Int, Int, IntArray>>()
        var w = width
        var h = height
        var px = pixels
        chain += Triple(w, h, px)
        while (w > 4 && h > 4) {
            val nw = w / 2
            val nh = h / 2
            val next = IntArray(nw * nh)
            for (y in 0 until nh) for (x in 0 until nw) {
                val a = px[(y * 2) * w + x * 2]
                val b = px[(y * 2) * w + x * 2 + 1]
                val c = px[(y * 2 + 1) * w + x * 2]
                val d = px[(y * 2 + 1) * w + x * 2 + 1]
                var out = 0
                for (shift in intArrayOf(24, 16, 8, 0)) {
                    val v = (((a ushr shift) and 255) + ((b ushr shift) and 255) +
                        ((c ushr shift) and 255) + ((d ushr shift) and 255)) shr 2
                    out = out or (v shl shift)
                }
                next[y * nw + x] = out
            }
            w = nw; h = nh; px = next
            chain += Triple(w, h, px)
        }
        levels = chain
    }
}

private fun le32(b: ByteArray, o: Int) =
    (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8) or
        ((b[o + 2].toInt() and 255) shl 16) or ((b[o + 3].toInt() and 255) shl 24)

private fun rgb565(v: Int, a: Int = 255): Int {
    val r = ((v shr 11) and 31) * 255 / 31
    val g = ((v shr 5) and 63) * 255 / 63
    val bl = (v and 31) * 255 / 31
    return (a shl 24) or (r shl 16) or (g shl 8) or bl
}

private fun lerpColor(c0: Int, c1: Int, n0: Int, n1: Int): Int {
    val d = n0 + n1
    fun ch(s: Int) = ((((c0 ushr s) and 255) * n0 + ((c1 ushr s) and 255) * n1) / d) shl s
    return (255 shl 24) or ch(16) or ch(8) or ch(0)
}

internal fun decodeDds(b: ByteArray): Bitmap? {
    if (b.size < 128 || le32(b, 0) != 0x20534444) return null
    val height = le32(b, 12)
    val width = le32(b, 16)
    val pfFlags = le32(b, 80)
    val fourCC = String(b, 84, 4, Charsets.ISO_8859_1)
    val bpp = le32(b, 88)
    val px = IntArray(width * height)
    var p = 128

    if (pfFlags and 0x4 != 0) { // FOURCC -> DXT
        val dxt1 = fourCC == "DXT1"
        if (!dxt1 && fourCC != "DXT3" && fourCC != "DXT5") return null
        val blockBytes = if (dxt1) 8 else 16
        for (by in 0 until (height + 3) / 4) for (bx in 0 until (width + 3) / 4) {
            val alpha = IntArray(16) { 255 }
            var c = p
            if (!dxt1) {
                if (fourCC == "DXT3") {
                    for (i in 0 until 16) {
                        val nib = (b[p + i / 2].toInt() shr ((i and 1) * 4)) and 15
                        alpha[i] = nib * 17
                    }
                } else {
                    val a0 = b[p].toInt() and 255
                    val a1 = b[p + 1].toInt() and 255
                    val table = IntArray(8)
                    table[0] = a0; table[1] = a1
                    if (a0 > a1) for (i in 1..6) table[i + 1] = ((7 - i) * a0 + i * a1) / 7
                    else {
                        for (i in 1..4) table[i + 1] = ((5 - i) * a0 + i * a1) / 5
                        table[6] = 0; table[7] = 255
                    }
                    var bits = 0L
                    for (i in 0 until 6) bits = bits or ((b[p + 2 + i].toLong() and 255) shl (8 * i))
                    for (i in 0 until 16) alpha[i] = table[((bits shr (3 * i)) and 7).toInt()]
                }
                c += 8
            }
            val c0 = (b[c].toInt() and 255) or ((b[c + 1].toInt() and 255) shl 8)
            val c1 = (b[c + 2].toInt() and 255) or ((b[c + 3].toInt() and 255) shl 8)
            val col = IntArray(4)
            col[0] = rgb565(c0); col[1] = rgb565(c1)
            if (c0 > c1 || !dxt1) {
                col[2] = lerpColor(col[0], col[1], 2, 1)
                col[3] = lerpColor(col[0], col[1], 1, 2)
            } else {
                col[2] = lerpColor(col[0], col[1], 1, 1)
                col[3] = 0
            }
            val idx = le32(b, c + 4)
            for (i in 0 until 16) {
                val x = bx * 4 + (i and 3)
                val y = by * 4 + (i shr 2)
                if (x >= width || y >= height) continue
                val color = col[(idx ushr (2 * i)) and 3]
                px[y * width + x] = (color and 0xFFFFFF) or (if (dxt1) (color ushr 24) shl 24 else alpha[i] shl 24)
            }
            p += blockBytes
        }
        return Bitmap(width, height, px)
    }

    if (bpp == 32 || bpp == 24) {
        val bytes = bpp / 8
        val hasA = pfFlags and 0x1 != 0
        for (i in 0 until width * height) {
            val o = p + i * bytes
            val a = if (bytes == 4 && hasA) b[o + 3].toInt() and 255 else 255
            px[i] = (a shl 24) or ((b[o + 2].toInt() and 255) shl 16) or
                ((b[o + 1].toInt() and 255) shl 8) or (b[o].toInt() and 255)
        }
        return Bitmap(width, height, px)
    }
    return null
}

internal fun decodeTga(b: ByteArray): Bitmap? {
    val idLen = b[0].toInt() and 255
    val cmType = b[1].toInt()
    val type = b[2].toInt()
    val cmLen = (b[5].toInt() and 255) or ((b[6].toInt() and 255) shl 8)
    val cmBits = b[7].toInt() and 255
    val width = (b[12].toInt() and 255) or ((b[13].toInt() and 255) shl 8)
    val height = (b[14].toInt() and 255) or ((b[15].toInt() and 255) shl 8)
    val bits = b[16].toInt() and 255
    val topDown = (b[17].toInt() and 0x20) != 0
    if (width <= 0 || height <= 0 || type !in intArrayOf(1, 2, 3, 9, 10, 11)) return null
    val bytes = bits / 8
    var p = 18 + idLen
    var palette: IntArray? = null
    if (cmType == 1) {
        val cb = cmBits / 8
        palette = IntArray(cmLen) { i ->
            val o = p + i * cb
            (255 shl 24) or ((b[o + 2].toInt() and 255) shl 16) or ((b[o + 1].toInt() and 255) shl 8) or (b[o].toInt() and 255)
        }
        p += cmLen * cb
    }
    fun pixelAt(o: Int): Int = when {
        palette != null -> palette[b[o].toInt() and 255]
        type == 3 || type == 11 -> { val g = b[o].toInt() and 255; (255 shl 24) or (g shl 16) or (g shl 8) or g }
        bytes == 2 -> {
            val v = (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8)
            val r = ((v shr 10) and 31) * 255 / 31
            val g = ((v shr 5) and 31) * 255 / 31
            val bl = (v and 31) * 255 / 31
            (255 shl 24) or (r shl 16) or (g shl 8) or bl
        }
        else -> {
            val a = if (bytes == 4) b[o + 3].toInt() and 255 else 255
            (a shl 24) or ((b[o + 2].toInt() and 255) shl 16) or ((b[o + 1].toInt() and 255) shl 8) or (b[o].toInt() and 255)
        }
    }
    val step = if (palette != null || type == 3 || type == 11) 1 else bytes
    val raw = IntArray(width * height)
    var i = 0
    if (type < 9) {
        while (i < raw.size) { raw[i++] = pixelAt(p); p += step }
    } else {
        while (i < raw.size) {
            val h = b[p++].toInt() and 255
            val n = (h and 127) + 1
            if (h and 128 != 0) {
                val c = pixelAt(p); p += step
                repeat(n) { if (i < raw.size) raw[i++] = c }
            } else repeat(n) { if (i < raw.size) { raw[i++] = pixelAt(p); p += step } }
        }
    }
    val out = if (topDown) raw else IntArray(raw.size).also { dst ->
        for (y in 0 until height) raw.copyInto(dst, y * width, (height - 1 - y) * width, (height - y) * width)
    }
    return Bitmap(width, height, out)
}

internal fun decodeBmp(b: ByteArray): Bitmap? {
    if (b.size < 54 || b[0] != 'B'.code.toByte() || b[1] != 'M'.code.toByte()) return null
    val dataOffset = le32(b, 10)
    val headerSize = le32(b, 14)
    if (headerSize < 40) return null
    val width = le32(b, 18)
    val rawHeight = le32(b, 22)
    val height = kotlin.math.abs(rawHeight)
    val topDown = rawHeight < 0
    val bits = (b[28].toInt() and 255) or ((b[29].toInt() and 255) shl 8)
    val compression = le32(b, 30)
    if (width <= 0 || height <= 0 || width > 16384 || height > 16384 || compression != 0 && !(compression == 3 && bits == 32)) return null
    val colors = if (bits <= 8) (le32(b, 46).takeIf { it > 0 } ?: (1 shl bits)) else 0
    val paletteAt = 14 + headerSize
    val rowBytes = ((width * bits + 31) / 32) * 4
    if (dataOffset + rowBytes.toLong() * height > b.size) return null
    val out = IntArray(width * height)
    for (y in 0 until height) {
        val row = dataOffset + (if (topDown) y else height - 1 - y) * rowBytes
        for (x in 0 until width) {
            out[y * width + x] = when (bits) {
                24 -> { val o = row + x * 3; (255 shl 24) or ((b[o + 2].toInt() and 255) shl 16) or ((b[o + 1].toInt() and 255) shl 8) or (b[o].toInt() and 255) }
                32 -> { val o = row + x * 4; (255 shl 24) or ((b[o + 2].toInt() and 255) shl 16) or ((b[o + 1].toInt() and 255) shl 8) or (b[o].toInt() and 255) }
                8 -> palette(b, paletteAt, colors, b[row + x].toInt() and 255)
                4 -> palette(b, paletteAt, colors, ((b[row + x / 2].toInt() and 255) shr (if (x % 2 == 0) 4 else 0)) and 15)
                1 -> palette(b, paletteAt, colors, ((b[row + x / 8].toInt() and 255) shr (7 - x % 8)) and 1)
                else -> return null
            }
        }
    }
    return Bitmap(width, height, out)
}

private fun palette(b: ByteArray, at: Int, count: Int, i: Int): Int {
    if (i >= count) return 0xFF000000.toInt()
    val o = at + i * 4
    return (255 shl 24) or ((b[o + 2].toInt() and 255) shl 16) or ((b[o + 1].toInt() and 255) shl 8) or (b[o].toInt() and 255)
}

// ---------------------------------------------------------------------------------------------
// NIF (NetImmerse 4.0.0.2, the version Morrowind uses)
// ---------------------------------------------------------------------------------------------

/** One renderable triangle list, already transformed to model space. */
internal class MwMesh(
    val positions: FloatArray,
    val normals: FloatArray,
    val uvs: FloatArray?,
    val colors: FloatArray?,
    val indices: IntArray,
    val texture: String?,
    val diffuse: FloatArray,
    val emissive: FloatArray,
    val alphaBlend: Boolean,
    val alphaTest: Boolean,
    val name: String
)

internal class MwModel(val meshes: List<MwMesh>, val error: String? = null) {
    val min = FloatArray(3) { Float.MAX_VALUE }
    val max = FloatArray(3) { -Float.MAX_VALUE }

    init {
        for (m in meshes) {
            var i = 0
            while (i < m.positions.size) {
                for (a in 0..2) {
                    min[a] = minOf(min[a], m.positions[i + a])
                    max[a] = max(max[a], m.positions[i + a])
                }
                i += 3
            }
        }
        if (meshes.isEmpty()) for (a in 0..2) { min[a] = 0f; max[a] = 0f }
    }
}

internal class NifBlock(val type: String) {
    var name = ""
    var flags = 0
    var matrix = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    var translation = floatArrayOf(0f, 0f, 0f)
    var scale = 1f
    var properties = IntArray(0)
    var children = IntArray(0)
    /** NiSwitchNode / NiLODNode: the child shown (0 is the most detailed level). */
    var switchIndex = 0
    var data = -1
    var skin = -1
    // data blocks
    var positions: FloatArray? = null
    var normals: FloatArray? = null
    var uvs: FloatArray? = null
    var colors: FloatArray? = null
    var indices: IntArray? = null
    // properties
    var source = -1
    var fileName: String? = null
    var diffuse = floatArrayOf(1f, 1f, 1f)
    var emissive = floatArrayOf(0f, 0f, 0f)
    var alpha = 1f
    var alphaFlags = 0
    var vertexMode = 1
    // animation / skinning
    var extra = -1
    var controller = -1
    var next = -1
    var text: String? = null
    var textKeys: List<Pair<Float, String>> = emptyList()
    var skinRoot = -1
    var bones = IntArray(0)
    var skinData: NifSkinData? = null
    var keys: NifKeys? = null
}

/** Per-bone vertex weights for a skinned shape. [rot]/[trans]/[scale] is the bone's skin-to-bone transform. */
internal class NifBone(val rot: FloatArray, val trans: FloatArray, val scale: Float, val vertices: IntArray, val weights: FloatArray)
internal class NifSkinData(val rot: FloatArray, val trans: FloatArray, val scale: Float, val bones: List<NifBone>)

/** Keyframes of one bone. Rotation is either quaternions (w,x,y,z) or three euler tracks. */
internal class NifKeys(
    val rotTimes: FloatArray, val rotValues: FloatArray,
    val eulerTimes: Array<FloatArray>, val eulerValues: Array<FloatArray>,
    val transTimes: FloatArray, val transValues: FloatArray
)

/** A little-endian cursor over a byte array. */
internal class LeBuffer(private val bytes: ByteArray) {
    private var pos = 0
    fun array() = bytes
    fun position() = pos
    fun position(p: Int) { pos = p }
    fun get(): Byte = bytes[pos++]
    fun getShort(): Short = ((bytes[pos].toInt() and 255) or ((bytes[pos + 1].toInt() and 255) shl 8)).also { pos += 2 }.toShort()
    fun getInt(): Int = ((bytes[pos].toInt() and 255) or ((bytes[pos + 1].toInt() and 255) shl 8) or
        ((bytes[pos + 2].toInt() and 255) shl 16) or ((bytes[pos + 3].toInt() and 255) shl 24)).also { pos += 4 }
    fun getFloat(): Float = Float.fromBits(getInt())
}

private class NifReader(bytes: ByteArray) {
    val buf = LeBuffer(bytes)
    fun int() = buf.getInt()
    fun ushort() = buf.getShort().toInt() and 0xFFFF
    fun byte() = buf.get().toInt() and 255
    fun float() = buf.getFloat()
    fun skip(n: Int) { buf.position(buf.position() + n) }
    fun string(): String {
        val len = int()
        require(len in 0..4096) { "bad string length $len" }
        val s = buf.array().decodeToString(buf.position(), buf.position() + len)
        skip(len)
        return s
    }
    fun floats(n: Int) = FloatArray(n) { float() }
    fun refs0(): IntArray = IntArray(int().also { require(it in 0..100000) { "bad ref count" } }) { int() }
    fun refs(): IntArray = IntArray(int().also { require(it in 0..100000) { "bad ref count" } }) { int() }
}

/** Moves to the next length-prefixed "Ni..." type name. Returns false if there is none. */
private fun NifReader.resyncToNextBlock(): Boolean {
    val arr = buf.array()
    var p = buf.position()
    while (p + 12 < arr.size) {
        val len = (arr[p].toInt() and 255) or ((arr[p + 1].toInt() and 255) shl 8) or
                ((arr[p + 2].toInt() and 255) shl 16) or ((arr[p + 3].toInt() and 255) shl 24)
        if (len in 6..48 && p + 4 + len <= arr.size && arr[p + 4] == 'N'.code.toByte() && arr[p + 5] == 'i'.code.toByte()) {
            var ok = true
            for (k in 0 until len) {
                val c = arr[p + 4 + k].toInt().toChar()
                if (!c.isLetter()) { ok = false; break }
            }
            if (ok) { buf.position(p); return true }
        }
        p++
    }
    buf.position(arr.size)
    return false
}

private fun NifBlock.readAvObject(r: NifReader) {
    name = r.string(); extra = r.int(); controller = r.int()
    flags = r.ushort()
    translation = r.floats(3)
    matrix = r.floats(9)
    scale = r.float()
    r.skip(12) // velocity
    properties = r.refs()
    if (r.int() != 0) r.skip(4 + 12 + 36 + 12) // bounding box
}

private fun NifReader.skipNet() { string(); skip(8) }
private fun NifBlock.readNet(r: NifReader) { name = r.string(); extra = r.int(); controller = r.int() }
private fun NifReader.skipController() { skip(4 + 2 + 16 + 4) } // next, flags, freq/phase/start/stop, target

/** Parses all blocks. On an unsupported block, returns what has been read so far plus an error. */
internal fun readBlocks(bytes: ByteArray): Pair<List<NifBlock>, String?> {
    val r = NifReader(bytes)
    var nl = 0
    while (bytes[nl].toInt() != '\n'.code) nl++
    r.buf.position(nl + 1)
    val version = r.int()
    if (version != 0x04000002) return emptyList<NifBlock>() to "unsupported NIF version 0x${version.toString(16)}"
    val count = r.int()
    val blocks = ArrayList<NifBlock>(count)
    try {
        for (i in 0 until count) {
            val type = r.string()
            val b = NifBlock(type)
            when (type) {
                "NiNode", "RootCollisionNode", "NiBSAnimationNode", "NiBSParticleNode", "NiBillboardNode", "NiCamera", "NiLODNode", "NiSwitchNode" -> {
                    require(type != "NiCamera") { "unsupported $type" }
                    b.readAvObject(r)
                    b.children = r.refs()
                    r.refs() // effects
                    if (type == "NiSwitchNode" || type == "NiLODNode") b.switchIndex = r.int()
                    if (type == "NiLODNode") {
                        r.skip(12) // lod center
                        val levels = r.int()
                        require(levels in 0..64) { "bad NiLODNode level count $levels" }
                        r.skip(levels * 8) // near/far extent per level
                    }
                }
                "NiTriShape", "NiTriStrips", "NiAutoNormalParticles", "NiRotatingParticles" -> {
                    require(type != "NiTriStrips") { "unsupported $type" }
                    b.readAvObject(r)
                    b.data = r.int(); b.skin = r.int()
                }
                "NiTriShapeData" -> {
                    val n = r.ushort()
                    if (r.int() != 0) b.positions = r.floats(n * 3)
                    if (r.int() != 0) b.normals = r.floats(n * 3)
                    r.skip(16) // center + radius
                    if (r.int() != 0) b.colors = r.floats(n * 4)
                    val uvSets = r.ushort() and 63
                    if (r.int() != 0 && uvSets > 0) {
                        b.uvs = r.floats(n * 2)
                        r.skip((uvSets - 1) * n * 8)
                    }
                    val tris = r.ushort()
                    r.int()
                    b.indices = IntArray(tris * 3) { r.ushort() }
                    repeat(r.ushort()) { r.skip(r.ushort() * 2) } // match groups
                }
                "NiAutoNormalParticlesData", "NiRotatingParticlesData" -> {
                    val n = r.ushort()
                    if (r.int() != 0) r.skip(n * 12)
                    if (r.int() != 0) r.skip(n * 12)
                    r.skip(16)
                    if (r.int() != 0) r.skip(n * 16)
                    val pUvSets = r.ushort() and 63
                    if (r.int() != 0) r.skip(pUvSets * n * 8)
                    r.skip(2 + 4 + 2) // numParticles, radius, numActive
                    if (r.int() != 0) r.skip(n * 4)
                    if (type == "NiRotatingParticlesData" && r.int() != 0) r.skip(n * 16)
                }
                "NiTexturingProperty" -> {
                    r.skipNet(); r.skip(2 + 4)
                    val slots = r.int()
                    for (s in 0 until slots) {
                        if (r.int() == 0) continue
                        val src = r.int()
                        if (s == 0) b.source = src
                        r.skip(4 + 4 + 4 + 2 + 2 + 2)
                        if (s == 5) r.skip(24)
                    }
                }
                "NiSourceTexture" -> {
                    r.skipNet()
                    if (r.byte() != 0) b.fileName = r.string() else { r.skip(1); r.skip(4) }
                    r.skip(4 + 4 + 4 + 1)
                }
                "NiMaterialProperty" -> {
                    r.skipNet(); r.skip(2)
                    r.skip(12) // ambient
                    b.diffuse = r.floats(3)
                    r.skip(12) // specular
                    b.emissive = r.floats(3)
                    r.skip(4)
                    b.alpha = r.float()
                }
                "NiAlphaProperty" -> { r.skipNet(); b.alphaFlags = r.ushort(); r.skip(1) }
                "NiVertexColorProperty" -> { r.skipNet(); r.skip(2); b.vertexMode = r.int(); r.skip(4) }
                "NiZBufferProperty", "NiSpecularProperty", "NiWireframeProperty", "NiDitherProperty", "NiShadeProperty" -> {
                    r.skipNet(); r.skip(2)
                }
                "NiStencilProperty" -> { r.skipNet(); r.skip(2 + 1 + 4 + 4 + 4 + 4 + 4 + 4) }
                "NiStringExtraData" -> { b.next = r.int(); r.skip(4); b.text = r.string() }
                "NiTextKeyExtraData" -> {
                    b.next = r.int(); r.skip(4)
                    b.textKeys = List(r.int()) { r.float() to r.string() }
                }
                "NiKeyframeController" -> { b.next = r.int(); r.skip(2 + 16 + 4); b.data = r.int() }
                "NiSequenceStreamHelper" -> b.readNet(r)
                "NiKeyframeData" -> b.keys = readKeys(r)
                "NiSkinInstance" -> {
                    b.data = r.int(); b.skinRoot = r.int()
                    b.bones = r.refs0()
                }
                "NiSkinData" -> b.skinData = readSkinData(r)
                else -> {
                    // Blocks have no stored size in this NIF version, so an unknown one (particle systems,
                    // alpha/visibility controllers, ...) can't be skipped exactly. Keep a placeholder so
                    // block indices stay valid and resume at the next block's type name.
                    require(r.resyncToNextBlock() || i == count - 1) { "unsupported block $type" }
                }
            }
            blocks += b
        }
    } catch (e: Exception) {
        return blocks to "block ${blocks.size} @${r.buf.position()}: ${e.message ?: e::class.simpleName}"
    }
    return blocks to null
}

private val SKIPPED_NODE_PREFIXES = listOf("collision", "rootcollision", "bounding", "tri shadow", "shadow")

/** [includeSkinned] also draws skinned shapes (armor, clothing, body parts) where their node transforms put them. */
internal fun parseNif(bytes: ByteArray, includeSkinned: Boolean = false): MwModel {
    val (blocks, error) = runCatching { readBlocks(bytes) }.getOrElse { emptyList<NifBlock>() to it.message }
    if (blocks.isEmpty()) return MwModel(emptyList(), error)
    val meshes = ArrayList<MwMesh>()

    fun visit(index: Int, parentM: FloatArray, parentT: FloatArray, parentS: Float, inherited: IntArray) {
        val b = blocks.getOrNull(index) ?: return
        if (b.flags and 1 != 0) return // hidden
        val lname = b.name.lowercase()
        if (SKIPPED_NODE_PREFIXES.any { lname.startsWith(it) }) return

        // compose child transform: world = parent * local
        val s = parentS * b.scale
        val m = FloatArray(9)
        for (i in 0..2) for (j in 0..2) {
            m[i * 3 + j] = parentM[i * 3] * b.matrix[j] + parentM[i * 3 + 1] * b.matrix[3 + j] + parentM[i * 3 + 2] * b.matrix[6 + j]
        }
        val t = FloatArray(3) { i ->
            parentT[i] + parentS * (parentM[i * 3] * b.translation[0] + parentM[i * 3 + 1] * b.translation[1] + parentM[i * 3 + 2] * b.translation[2])
        }
        val props = inherited + b.properties

        when (b.type) {
            "NiNode", "NiBSAnimationNode", "NiBillboardNode" -> b.children.forEach { visit(it, m, t, s, props) }
            "NiSwitchNode", "NiLODNode" -> {
                // Only one child is shown at a time; the active one is the full detail model
                val child = b.children.getOrNull(b.switchIndex) ?: b.children.firstOrNull { it >= 0 } ?: return
                visit(child, m, t, s, props)
            }
            "NiTriShape" -> {
                if (b.skin >= 0 && !includeSkinned) return
                val d = blocks.getOrNull(b.data) ?: return
                val pos = d.positions ?: return
                val idx = d.indices ?: return
                if (idx.isEmpty()) return
                var texture: String? = null
                var diffuse = floatArrayOf(1f, 1f, 1f)
                var emissive = floatArrayOf(0f, 0f, 0f)
                var blend = false
                var test = false
                for (pi in props) {
                    val p = blocks.getOrNull(pi) ?: continue
                    when (p.type) {
                        "NiTexturingProperty" -> texture = blocks.getOrNull(p.source)?.fileName ?: texture
                        "NiMaterialProperty" -> { diffuse = p.diffuse; emissive = p.emissive }
                        "NiAlphaProperty" -> { blend = p.alphaFlags and 1 != 0; test = p.alphaFlags and 0x200 != 0 }
                    }
                }
                val n = pos.size / 3
                val wp = FloatArray(pos.size)
                val wn = FloatArray(pos.size)
                val src = d.normals
                for (v in 0 until n) {
                    val x = pos[v * 3]; val y = pos[v * 3 + 1]; val z = pos[v * 3 + 2]
                    for (i in 0..2) {
                        wp[v * 3 + i] = t[i] + s * (m[i * 3] * x + m[i * 3 + 1] * y + m[i * 3 + 2] * z)
                    }
                    if (src != null) {
                        val nx = src[v * 3]; val ny = src[v * 3 + 1]; val nz = src[v * 3 + 2]
                        for (i in 0..2) wn[v * 3 + i] = m[i * 3] * nx + m[i * 3 + 1] * ny + m[i * 3 + 2] * nz
                    }
                }
                if (src == null) computeNormals(wp, idx, wn)
                meshes += MwMesh(wp, wn, d.uvs, d.colors, idx, texture, diffuse, emissive, blend, test, b.name)
            }
        }
    }

    // Root is the first block.
    visit(0, floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), floatArrayOf(0f, 0f, 0f), 1f, IntArray(0))
    return MwModel(meshes, error)
}

private fun computeNormals(p: FloatArray, idx: IntArray, out: FloatArray) {
    var i = 0
    while (i + 2 < idx.size) {
        val a = idx[i] * 3; val b = idx[i + 1] * 3; val c = idx[i + 2] * 3
        val ux = p[b] - p[a]; val uy = p[b + 1] - p[a + 1]; val uz = p[b + 2] - p[a + 2]
        val vx = p[c] - p[a]; val vy = p[c + 1] - p[a + 1]; val vz = p[c + 2] - p[a + 2]
        val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
        for (k in intArrayOf(a, b, c)) { out[k] += nx; out[k + 1] += ny; out[k + 2] += nz }
        i += 3
    }
    var v = 0
    while (v < out.size) {
        val l = kotlin.math.sqrt(out[v] * out[v] + out[v + 1] * out[v + 1] + out[v + 2] * out[v + 2])
        if (l > 1e-6f) { out[v] /= l; out[v + 1] /= l; out[v + 2] /= l }
        v += 3
    }
}

private fun readSkinData(r: NifReader): NifSkinData {
    val rot = r.floats(9); val trans = r.floats(3); val scale = r.float()
    val boneCount = r.int()
    r.skip(4) // skin partition ref (unused in 4.0.0.2)
    val bones = List(boneCount) {
        val brot = r.floats(9); val btrans = r.floats(3); val bscale = r.float()
        r.skip(16) // bounding sphere
        val n = r.ushort()
        val idx = IntArray(n); val w = FloatArray(n)
        for (i in 0 until n) { idx[i] = r.ushort(); w[i] = r.float() }
        NifBone(brot, btrans, bscale, idx, w)
    }
    return NifSkinData(rot, trans, scale, bones)
}

private fun readKeys(r: NifReader): NifKeys {
    var rotTimes = FloatArray(0); var rotValues = FloatArray(0)
    val eulerTimes = Array(3) { FloatArray(0) }; val eulerValues = Array(3) { FloatArray(0) }
    val nRot = r.int()
    if (nRot > 0) {
        val type = r.int()
        if (type == 4) {
            for (axis in 0..2) {
                val n = r.int()
                if (n == 0) continue
                val kt = r.int()
                val t = FloatArray(n); val v = FloatArray(n)
                for (i in 0 until n) {
                    t[i] = r.float(); v[i] = r.float()
                    when (kt) { 2 -> r.skip(8); 3 -> r.skip(12) }
                }
                eulerTimes[axis] = t; eulerValues[axis] = v
            }
        } else {
            rotTimes = FloatArray(nRot); rotValues = FloatArray(nRot * 4)
            for (i in 0 until nRot) {
                rotTimes[i] = r.float()
                for (k in 0..3) rotValues[i * 4 + k] = r.float()
                if (type == 3) r.skip(12)
            }
        }
    }
    var transTimes = FloatArray(0); var transValues = FloatArray(0)
    val nTrans = r.int()
    if (nTrans > 0) {
        val kt = r.int()
        transTimes = FloatArray(nTrans); transValues = FloatArray(nTrans * 3)
        for (i in 0 until nTrans) {
            transTimes[i] = r.float()
            for (k in 0..2) transValues[i * 3 + k] = r.float()
            when (kt) { 2 -> r.skip(24); 3 -> r.skip(12) }
        }
    }
    val nScale = r.int()
    if (nScale > 0) {
        val kt = r.int()
        for (i in 0 until nScale) { r.skip(8); when (kt) { 2 -> r.skip(8); 3 -> r.skip(12) } }
    }
    return NifKeys(rotTimes, rotValues, eulerTimes, eulerValues, transTimes, transValues)
}
