// SPDX-License-Identifier: GPL-3.0-or-later
package org.alpha3.launcher.tools.merge

import org.alpha3.launcher.tools.openmw.*

/** What the merge needs from one plugin: its header, and the records of the types it merges. */
internal class MergePlugin(
    val name: String,
    val key: String,
    val path: String,
    val size: Long,
    val masters: List<String>,
    val description: String,
    /** Keyed by "TAG::id" with the id lowercased; null marks a deleted record. */
    val records: LinkedHashMap<String, Mergeable?>,
) {
    /** Plugins tagged DoNotMerge (including DeltaPlugin's own output) are left out of merging. */
    val doNotMerge get() = description.split(Regex("\\s+")).any { it == "DoNotMerge" }
}

/** The header of a plugin and the keys of its mergeable records, from a quick first pass. */
internal class PluginScan(
    val name: String,
    val path: String,
    val size: Long,
    val masters: List<String>,
    val description: String,
    val keys: List<String>,
) {
    val doNotMerge get() = description.split(Regex("\\s+")).any { it == "DoNotMerge" }
}

private fun mergeable(tag: String) = tag == "LEVI" || tag == "LEVC" || isFieldRecord(tag)

/**
 * Walks a plugin's records. [onRecord] gets each record after the header and must consume exactly [n]
 * bytes of [input]; the TES3 header is handled here.
 */
private fun walk(
    name: String, path: String, size: Long, codec: TextCodec, fs: ToolFs,
    onHeader: (masters: List<String>, description: String) -> Unit,
    onRecord: (tag: String, flags: Long, n: Long, input: BufferedInput) -> Unit,
) {
    val reader = fs.openRead(path)
    try {
        val input = BufferedInput(reader, size)
        val header = ByteArray(16)
        while (input.pos < size) {
            val start = input.pos
            try {
                if (size - start < 16) fail("record header: unexpected end of file")
                input.readFully(header)
                val tag = tagString(header, 0)
                val n = u32(header, 4)
                val flags = u32(header, 12)
                if (n > size - input.pos) fail("$tag size $n exceeds file")
                if (start == 0L && tag != "TES3") fail("missing TES3 header")
                if (tag == "TES3") {
                    val body = ByteArray(n.toInt())
                    input.readFully(body)
                    val masters = ArrayList<String>()
                    var description = ""
                    subs(body) { t, b ->
                        if (t == "HEDR") {
                            if (b.size != 300) fail("invalid HEDR size ${b.size}")
                            description = codec.decode(b.copyOfRange(40, 296))
                        }
                        if (t == "MAST") masters.add(codec.decode(b))
                    }
                    onHeader(masters, description)
                } else {
                    onRecord(tag, flags, n, input)
                }
            } catch (e: ToolException) {
                fail("$name at 0x${start.toString(16)}: ${e.message}")
            }
        }
        if (input.pos == 0L) fail("$name: empty plugin")
    } finally {
        reader.close()
    }
}

/** The first subrecord of a record, which holds its id, and how many bytes reading it used. */
private class First(val sub: Sub?, val consumed: Long)

private fun readFirstSub(input: BufferedInput, n: Long): First {
    if (n < 8) return First(null, 0)
    val h = ByteArray(8)
    input.readFully(h)
    val len = u32(h, 4)
    if (len > n - 8) return First(null, 8)
    val data = ByteArray(len.toInt())
    input.readFully(data)
    return First(Sub(tagString(h, 0), data), 8 + len)
}

/** A record's id from its id subrecord: the name, or for magic effects and skills the decimal index. */
private fun idOf(tag: String, data: ByteArray, codec: TextCodec): String =
    if (tag != "LEVI" && tag != "LEVC" && schemaFor(tag)?.idIsIndex == true && data.size == 4) u32(data).toString()
    else codec.decode(data)

private fun idTagOf(tag: String) = if (tag == "LEVI" || tag == "LEVC") "NAME" else schemaFor(tag)!!.idTag

/** The merge key and id of an INFO, which belongs to the topic before it; null before any topic. */
private fun infoKey(topic: String?, id: String): String? = topic?.let { mergeKey("INFO", "${it.lowercase()}/$id") }

internal fun scanPlugin(name: String, path: String, size: Long, codec: TextCodec, fs: ToolFs): PluginScan {
    var masters = emptyList<String>()
    var description = ""
    val keys = ArrayList<String>()
    var topic: String? = null
    walk(name, path, size, codec, fs, { m, d -> masters = m; description = d }) { tag, _, n, input ->
        if (!mergeable(tag)) { input.skip(n); return@walk }
        val first = readFirstSub(input, n)
        val sub = first.sub
        if (sub == null || sub.tag != idTagOf(tag)) { input.skip(n - first.consumed); return@walk }
        val id = idOf(tag, sub.data, codec)
        if (tag == "DIAL") topic = id
        val key = if (tag == "INFO") infoKey(topic, id) else mergeKey(tag, id)
        if (key != null) keys.add(key)
        input.skip(n - first.consumed)
    }
    return PluginScan(name, path, size, masters, description, keys)
}

/** Loads the records of [scan] whose keys are in [wanted]; everything else is skipped. */
internal fun loadMergePlugin(
    scan: PluginScan, wanted: Set<String>, codec: TextCodec, fs: ToolFs, warn: (String) -> Unit,
): MergePlugin {
    val records = LinkedHashMap<String, Mergeable?>()
    var topic: String? = null
    walk(scan.name, scan.path, scan.size, codec, fs, { _, _ -> }) { tag, flags, n, input ->
        if (!mergeable(tag)) { input.skip(n); return@walk }
        val first = readFirstSub(input, n)
        val sub = first.sub
        if (sub == null || sub.tag != idTagOf(tag)) { input.skip(n - first.consumed); return@walk }
        val id = idOf(tag, sub.data, codec)
        if (tag == "DIAL") topic = id
        val key = (if (tag == "INFO") infoKey(topic, id) else mergeKey(tag, id))
        val prefix = first.consumed.toInt()
        if (key == null || key !in wanted) { input.skip(n - prefix); return@walk }
        val body = ByteArray(n.toInt())
        ByteBuilder(prefix).sub(sub.tag, sub.data).toByteArray().copyInto(body)
        val rest = ByteArray((n - prefix).toInt())
        input.readFully(rest)
        rest.copyInto(body, prefix)
        try {
            records[key] = when (tag) {
                "LEVI" -> parseLeveledList(ListKind.Items, body, codec)
                "LEVC" -> parseLeveledList(ListKind.Creatures, body, codec)
                else -> parseFieldRecord(tag, id, flags, body, schemaFor(tag)!!, if (tag == "INFO") topic else null)
            }
        } catch (e: ToolException) {
            warn("${scan.name}: skipping $tag $id: ${e.message}")
        }
    }
    return MergePlugin(scan.name, scan.name.lowercase(), scan.path, scan.size, scan.masters, scan.description, records)
}
