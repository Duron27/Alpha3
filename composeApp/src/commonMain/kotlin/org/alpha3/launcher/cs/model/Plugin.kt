package org.alpha3.launcher.cs.model

import org.alpha3.launcher.tools.openmw.refuseOfficial
import org.alpha3.launcher.tools.openmw.BufferedInput
import org.alpha3.launcher.tools.openmw.ByteBuilder
import org.alpha3.launcher.tools.openmw.Sub
import org.alpha3.launcher.tools.openmw.TextCodec
import org.alpha3.launcher.tools.openmw.ToolFs
import org.alpha3.launcher.tools.openmw.fail
import org.alpha3.launcher.tools.openmw.i32
import org.alpha3.launcher.tools.openmw.sub
import org.alpha3.launcher.tools.openmw.tagString
import org.alpha3.launcher.tools.openmw.u32
import org.alpha3.launcher.tools.tes3cmd.parseSubs
import org.alpha3.launcher.tools.tes3cmd.recordId

const val FLAG_DELETED = 0x20L
const val FLAG_PERSISTENT = 0x400L
const val FLAG_BLOCKED = 0x2000L

/**
 * A record of a plugin. Read records keep their bytes: until [subs] is changed the record is written back exactly as
 * it was read (header word, flags and body), so opening and saving a plugin changes nothing it did not edit.
 */
internal class CsRecord internal constructor(
    val tag: String,
    flags: Long,
    /** The header word between the size and the flags (unused by the game, kept as read). */
    val unknown: Long,
    private var body: ByteArray?,
    subs: List<Sub>?,
) {
    var flags: Long = flags
        set(value) { if (value != field) { field = value; changed = true } }

    private var parsed: List<Sub>? = subs

    /** The subrecords; setting them marks the record changed. */
    var subs: List<Sub>
        get() = parsed ?: parseSubs(body!!, tag).also { parsed = it }
        set(value) { parsed = value; body = null; changed = true }

    /** Edited (or new) since it was read. */
    var changed: Boolean = body == null
        private set

    /** The lower-case id the game looks records up by. */
    val id: String get() = recordId(tag, subs)

    /** The id as written (case kept), for showing. */
    val displayId: String get() = displayId(tag, subs)

    val deleted: Boolean get() = flags and FLAG_DELETED != 0L || subs.asSequence().takeWhile { it.tag != "FRMR" && it.tag != "MVRF" }.any { it.tag == "DELE" }

    fun bodyBytes(): ByteArray = body ?: joinSubsFast(subs).also { if (!changed) body = it }

    fun copy(): CsRecord = CsRecord(tag, flags, unknown, null, subs.map { Sub(it.tag, it.data.copyOf()) })

    companion object {
        /** A new record (not read from a file). */
        fun create(tag: String, subs: List<Sub>, flags: Long = 0): CsRecord = CsRecord(tag, flags, 0, null, subs)
    }
}

private fun joinSubsFast(subs: List<Sub>): ByteArray {
    val b = ByteBuilder(subs.sumOf { it.data.size + 8 })
    for (s in subs) b.sub(s.tag, s.data)
    return b.toByteArray()
}

internal fun zstringOf(b: ByteArray?): String {
    if (b == null) return ""
    var end = b.indexOf(0)
    if (end < 0) end = b.size
    return CharArray(end) { (b[it].toInt() and 0xff).toChar() }.concatToString()
}

/** The id as stored, for lists and titles. */
internal fun displayId(tag: String, subs: List<Sub>): String {
    fun first(t: String) = subs.firstOrNull { it.tag == t }?.data
    return when (tag) {
        "SKIL" -> first("INDX")?.takeIf { it.size >= 4 }?.let { SKILLS.getOrNull(i32(it)) ?: "Skill ${i32(it)}" } ?: "?"
        "MGEF" -> first("INDX")?.takeIf { it.size >= 4 }?.let { MAGIC_EFFECTS.getOrNull(i32(it)) ?: "Effect ${i32(it)}" } ?: "?"
        "SCPT" -> zstringOf(first("SCHD")?.copyOf(32))
        "INFO" -> zstringOf(first("INAM"))
        "TES3" -> "Header"
        else -> first("NAME")?.let { zstringOf(it) } ?: recordId(tag, subs)
    }
}

/** A plugin as read: its header record and the records after it, in file order. */
internal class PluginFile(val path: String, var header: CsRecord, val records: MutableList<CsRecord>) {
    val masters: List<Pair<String, Long>>
        get() {
            val out = ArrayList<Pair<String, Long>>()
            var name: String? = null
            for (s in header.subs) {
                when (s.tag) {
                    "MAST" -> { name?.let { out += it to 0L }; name = zstringOf(s.data) }
                    "DATA" -> { name?.let { out += it to (if (s.data.size >= 8) u32(s.data) or (u32(s.data, 4) shl 32) else 0L) }; name = null }
                }
            }
            name?.let { out += it to 0L }
            return out
        }

    val isMaster: Boolean
        get() = header.subs.firstOrNull { it.tag == "HEDR" }?.data?.let { it.size >= 8 && u32(it, 4) == 1L } ?: false
}

/** Reads every record of [path]. Bodies are kept as bytes and parsed when first looked at. */
internal fun readPlugin(fs: ToolFs, path: String): PluginFile {
    val size = fs.size(path)
    val reader = fs.openRead(path)
    try {
        val input = BufferedInput(reader, size)
        val records = ArrayList<CsRecord>()
        val head = ByteArray(16)
        while (input.pos < size) {
            val at = input.pos
            if (size - at < 16) fail("$path: a record header is cut off at byte $at")
            input.readFully(head)
            val tag = tagString(head, 0)
            val len = u32(head, 4)
            if (len > size - input.pos) fail("$path: $tag at byte $at is cut off ($len bytes, ${size - input.pos} left)")
            val body = ByteArray(len.toInt())
            input.readFully(body)
            records += CsRecord(tag, u32(head, 12), u32(head, 8), body, null)
        }
        val header = records.firstOrNull()?.takeIf { it.tag == "TES3" } ?: fail("$path: not a Morrowind plugin (no TES3 header)")
        records.removeAt(0)
        return PluginFile(path, header, records)
    } finally {
        reader.close()
    }
}

/** The plugin as bytes: header then records, each as read unless edited. */
internal fun pluginBytes(plugin: PluginFile): ByteArray {
    val out = ByteBuilder(1 shl 16)
    fun put(r: CsRecord) {
        val body = r.bodyBytes()
        out.appendAscii(r.tag)
        out.appendU32(body.size.toLong())
        out.appendU32(r.unknown)
        out.appendU32(r.flags)
        out.append(body)
    }
    put(plugin.header)
    plugin.records.forEach(::put)
    return out.toByteArray()
}

/**
 * Brings the header up to date before saving: the record count, and each master's size from the file on disk
 * ([masterSize]; null keeps the stored size). Changes the header only when something differs.
 */
internal fun refreshHeader(plugin: PluginFile, masterSize: (String) -> Long?) {
    val subs = plugin.header.subs
    var changed = false
    val out = ArrayList<Sub>(subs.size)
    var lastMaster: String? = null
    for (s in subs) {
        when (s.tag) {
            "HEDR" -> {
                val d = s.data.copyOf()
                if (d.size >= 300 && i32(d, 296) != plugin.records.size) {
                    putI32(d, 296, plugin.records.size)
                    changed = true
                }
                out += Sub(s.tag, d)
            }
            "MAST" -> { lastMaster = zstringOf(s.data); out += s }
            "DATA" -> {
                val size = lastMaster?.let(masterSize)
                if (size != null && s.data.size == 8 && (u32(s.data) or (u32(s.data, 4) shl 32)) != size) {
                    val d = ByteArray(8)
                    putI32(d, 0, (size and 0xffffffffL).toInt())
                    putI32(d, 4, (size ushr 32).toInt())
                    out += Sub("DATA", d)
                    changed = true
                } else out += s
                lastMaster = null
            }
            else -> out += s
        }
    }
    if (changed) plugin.header.subs = out
}

internal fun putI32(b: ByteArray, off: Int, v: Int) {
    b[off] = v.toByte(); b[off + 1] = (v shr 8).toByte(); b[off + 2] = (v shr 16).toByte(); b[off + 3] = (v shr 24).toByte()
}

/** A new, empty plugin's header (version 1.3, as the game's own plugins) with [masters] and their sizes. */
internal fun newHeader(author: String, description: String, masters: List<Pair<String, Long>>, codec: TextCodec): CsRecord {
    val hedr = ByteArray(300)
    putI32(hedr, 0, 1.3f.toBits())
    codec.encode(author).copyInto(hedr, 8, 0, minOf(author.length, 31))
    codec.encode(description).copyInto(hedr, 40, 0, minOf(description.length, 255))
    val subs = arrayListOf(Sub("HEDR", hedr))
    for ((name, size) in masters) {
        subs += Sub("MAST", codec.encode(name) + byteArrayOf(0))
        val d = ByteArray(8)
        putI32(d, 0, (size and 0xffffffffL).toInt())
        putI32(d, 4, (size ushr 32).toInt())
        subs += Sub("DATA", d)
    }
    return CsRecord.create("TES3", subs)
}

/** Writes [plugin] to its path: to a temporary file first, the previous file kept as `<name>.bak`. */
internal fun savePlugin(fs: ToolFs, plugin: PluginFile, path: String = plugin.path) {
    refuseOfficial(path)
    val bytes = pluginBytes(plugin)
    val tmp = "$path.tmp"
    fs.delete(tmp)
    val w = fs.openWrite(tmp)
    try { w.write(bytes, 0, bytes.size) } finally { w.close() }
    if (fs.isFile(path)) {
        val bak = "$path.bak"
        fs.delete(bak)
        fs.rename(path, bak)
    }
    fs.rename(tmp, path)
}
