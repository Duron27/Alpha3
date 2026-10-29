// SPDX-License-Identifier: GPL-3.0-or-later
package org.alpha3.launcher.tools.pluginquery

import org.alpha3.launcher.tools.openmw.*

internal class FilterBytes(val plugin: ByteArray, val cells: Int, val references: Int)

private val fieldTags = mapOf(
    "model" to "MODL", "name" to "FNAM", "icon" to "ITEX", "script" to "SCRI", "enchantment" to "ENAM",
    "object_id" to "NAME", "id" to "NAME",
)

private fun fieldTag(field: String): String =
    fieldTags[field.lowercase()] ?: field.takeIf { it.length == 4 && it == it.uppercase() } ?: fail("unknown field \"$field\"")

private fun rewrite(subs: List<Sub>, m: Modify, codec: TextCodec): List<Sub> {
    val tag = fieldTag(m.field)
    return subs.map { s ->
        if (s.tag != tag) s else Sub(tag, codec.encode(replaceAll(m.regex, codec.decode(s.data), m.replacement)) + 0)
    }
}

private fun deletedRecord(tag: String, id: String, codec: TextCodec) =
    ByteBuilder().sub("NAME", codec.encode(id) + 0).uintSub("DELE", 1).toByteArray()

internal fun writeFilterPlugin(selected: List<Selected>, loaded: List<PluginFile>, o: FilterOptions, codec: TextCodec): FilterBytes {
    val cells = selected.filter { it.cell != null }
    val usedOrigins = cells.flatMap { it.cell!!.refs.values }.map { it.origin.lowercase() }.toSet()
    // Cell references address masters with one byte, so a huge load order keeps only the plugins in use.
    val masters = if (cells.isNotEmpty() && loaded.size > 255) loaded.filter { it.name.lowercase() in usedOrigins } else loaded
    if (masters.size > 255 && cells.isNotEmpty()) fail("references originate in ${masters.size} plugins; TES3 can encode at most 255 reference masters")
    val masterIndex = HashMap<String, Int>()
    masters.forEachIndexed { i, p -> masterIndex[p.name.lowercase()] = i + 1 }

    val body = ByteBuilder(1 shl 16)
    var count = 0
    var refCount = 0
    var needsPlaceholder = false
    val cellBodies = ArrayList<ByteArray>()

    for (sel in selected) {
        val m = sel.merged
        val spec = sel.spec
        val cell = sel.cell
        if (cell == null) {
            val data = if (spec.delete) deletedRecord(m.tag, m.id.display, codec) else {
                val subs = if (spec.modify != null) rewrite(m.subs, spec.modify, codec) else m.subs
                val b = ByteBuilder()
                for (s in subs) b.sub(s.tag, s.data)
                b.toByteArray()
            }
            body.record(m.tag, 0, data)
            count++
            continue
        }
        if (spec.delete && spec.cellrefObjectId == null) fail("deleting whole cells is not supported; match their references with --cellref-object-id")
        val b = ByteBuilder()
        if ("NAME" !in cell.meta) b.sub("NAME", byteArrayOf(0))
        for (meta in cell.meta.values) b.sub(meta.tag, meta.data)
        // References keep the order they were first defined in, as DeltaPlugin writes them.
        for (r in cell.refs.values) {
            if (r.number > 0xffffff) fail("reference ${r.origin}:0x${r.number.toString(16)} cannot fit in a TES3 override; refusing to truncate its ID")
            val n = ((masterIndex[r.origin.lowercase()] ?: fail("reference from ${r.origin} but it is not in the load order")).toLong() shl 24) or r.number
            cell.moved[r.key]?.let { b.uintSub("MVRF", n).sub("CNDT", it) }
            b.uintSub("FRMR", n)
            if (spec.delete) {
                b.strSub("NAME", "<deleted>").uintSub("DELE", 0)
                needsPlaceholder = true
            } else {
                val subs = if (spec.modify != null) rewrite(r.subs, spec.modify, codec) else r.subs
                for (s in subs) b.sub(s.tag, s.data)
            }
            refCount++
        }
        cellBodies.add(b.toByteArray())
    }
    if (needsPlaceholder) {
        body.record("ACTI", 0, ByteBuilder().strSub("NAME", "<deleted>").toByteArray())
        count++
    }
    for (c in cellBodies) { body.record("CELL", 0, c); count++ }

    val hedr = ByteArray(300)
    floatBytes(1.3f).copyInto(hedr)
    o.author.encodeToByteArray().copyInto(hedr, 8, 0, minOf(o.author.length, 32))
    o.description.encodeToByteArray().copyInto(hedr, 40, 0, minOf(o.description.length, 256))
    ByteBuilder(4).appendU32(count.toLong()).toByteArray().copyInto(hedr, 296)
    val h = ByteBuilder().sub("HEDR", hedr)
    for (p in masters) h.sub("MAST", codec.encode(p.name) + 0).sub("DATA", ByteBuilder(8).appendU64(p.size).toByteArray())
    val out = ByteBuilder(body.size + h.size + 64)
    out.record("TES3", 0, h.toByteArray())
    out.append(body.toByteArray())
    return FilterBytes(out.toByteArray(), cellBodies.size, refCount)
}
