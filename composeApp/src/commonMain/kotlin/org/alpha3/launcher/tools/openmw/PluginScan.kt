// SPDX-License-Identifier: GPL-3.0-or-later
package org.alpha3.launcher.tools.openmw

internal class PluginFile(val name: String, val path: String, val size: Long)

internal class PluginHeader(val masters: List<String>, val description: String)

/**
 * Streams a plugin: [onHeader] gets the TES3 header, then [onRecord] gets each record whose tag is
 * in [tags] (every record when null). Other record bodies are skipped unread.
 */
internal fun scanPlugin(
    p: PluginFile,
    tags: Set<String>?,
    codec: TextCodec,
    fs: ToolFs,
    onHeader: (PluginHeader) -> Unit = {},
    onRecord: (tag: String, flags: Long, body: ByteArray) -> Unit,
): Int {
    val reader = fs.openRead(p.path)
    var count = 0
    try {
        val input = BufferedInput(reader, p.size)
        val header = ByteArray(16)
        while (input.pos < p.size) {
            val start = input.pos
            try {
                if (p.size - start < 16) fail("record header: unexpected end of file")
                input.readFully(header)
                val tag = tagString(header, 0)
                val n = u32(header, 4)
                val flags = u32(header, 12)
                if (n > p.size - input.pos) fail("$tag size $n exceeds file")
                if (start == 0L && tag != "TES3") fail("missing TES3 header")
                if (tag != "TES3") count++
                if (tag != "TES3" && tags != null && tag !in tags) {
                    input.skip(n)
                    continue
                }
                val body = ByteArray(n.toInt())
                input.readFully(body)
                if (tag == "TES3") {
                    val masters = ArrayList<String>()
                    var desc = ""
                    subs(body) { t, b ->
                        if (t == "MAST") masters.add(codec.decode(b))
                        if (t == "HEDR" && b.size >= 296) desc = codec.decode(b.copyOfRange(40, 296))
                    }
                    onHeader(PluginHeader(masters, desc))
                } else {
                    onRecord(tag, flags, body)
                }
            } catch (e: ToolException) {
                fail("${p.name} at 0x${start.toString(16)}: ${e.message}")
            }
        }
        if (input.pos == 0L) fail("${p.name}: empty plugin")
    } finally {
        reader.close()
    }
    return count
}

internal fun readSubs(body: ByteArray): List<Sub> {
    val out = ArrayList<Sub>()
    subs(body) { t, d -> out.add(Sub(t, d)) }
    return out
}

/** One reference inside a CELL. [origin] and [number] say which plugin's reference it is. */
internal class CellRef(val origin: String, val number: Long, val objectId: String, val subs: List<Sub>, val moved: ByteArray?, val deleted: Boolean) {
    val key get() = "${origin.lowercase()}#$number"
}

internal class ParsedCell(val deleted: Boolean, val meta: List<Sub>, val refs: List<CellRef>)

internal fun parseCell(body: ByteArray, plugin: String, masters: List<String>, codec: TextCodec): ParsedCell {
    val meta = ArrayList<Sub>()
    val refs = ArrayList<CellRef>()
    var deleted = false
    var inRefs = false
    // State of the reference being read.
    var origin = ""
    var number = 0L
    var objectId = ""
    var refSubs = ArrayList<Sub>()
    var refDeleted = false
    var moved: ByteArray? = null
    var pendingMove: ByteArray? = null
    var expectMove = false
    var active = false

    fun flush() {
        if (active) refs.add(CellRef(origin, number, objectId, refSubs, moved, refDeleted))
        active = false
    }
    subs(body) { tag, d ->
        when {
            tag == "FRMR" || tag == "MVRF" -> {
                inRefs = true
                if (d.size != 4) fail("reference number must be 4 bytes, got ${d.size}")
                flush()
                if (tag == "MVRF") {
                    expectMove = true
                } else {
                    val n = u32(d)
                    val master = (n ushr 24).toInt()
                    if (master > 0 && master <= masters.size) { origin = masters[master - 1]; number = n and 0xffffff }
                    else { origin = plugin; number = n }
                    objectId = ""
                    refSubs = ArrayList()
                    refDeleted = false
                    moved = pendingMove
                    pendingMove = null
                    active = true
                }
            }
            !inRefs -> when (tag) {
                "DELE" -> deleted = true
                "NAM0" -> {}
                else -> meta.add(Sub(tag, d))
            }
            tag == "CNDT" && expectMove -> { pendingMove = d; expectMove = false }
            tag == "NAM0" -> {}
            else -> {
                if (tag == "NAME") objectId = codec.decode(d)
                if (tag == "DELE") refDeleted = true
                refSubs.add(Sub(tag, d))
            }
        }
    }
    flush()
    return ParsedCell(deleted, meta, refs)
}
