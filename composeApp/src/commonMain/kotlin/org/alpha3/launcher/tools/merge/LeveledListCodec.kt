// SPDX-License-Identifier: GPL-3.0-or-later
package org.alpha3.launcher.tools.merge

import org.alpha3.launcher.tools.openmw.*

private class Parsed(val list: LeveledList, val deleted: Boolean)

/** A LEVI/LEVC record; null marks a deleted list. */
internal fun parseLeveledList(kind: ListKind, body: ByteArray, codec: TextCodec): LeveledList? {
    val p = parseList(kind, body, codec)
    return if (p.deleted) null else p.list
}

private fun parseList(kind: ListKind, body: ByteArray, codec: TextCodec): Parsed {
    var id: String? = null
    var deleted = false
    var noneChance = 0
    var each = false
    var allLevels = false
    val items = LinkedHashMap<ItemKey, Int>()
    var last: String? = null
    subs(body) { tag, d ->
        when (tag) {
            "NAME" -> id = codec.decode(d)
            "NNAM" -> {
                if (d.isEmpty()) fail("NNAM is empty")
                noneChance = d[0].toInt() and 0xff
            }
            "DATA" -> {
                if (d.size < 4) fail("DATA must be 4 bytes")
                val flags = i32(d)
                if (kind == ListKind.Items) {
                    // DeltaPlugin's bit assignment; it round-trips consistently.
                    if (flags and 0x02 != 0) allLevels = true
                    if (flags and 0x01 != 0) each = true
                } else if (flags and 0x03 != 0) {
                    allLevels = true
                }
            }
            "INTV" -> {
                val entry = last ?: fail("missing ${kind.entryTag} subrecord")
                if (d.size < 2) fail("INTV must be 2 bytes")
                val level = ((d[1].toInt() shl 8) or (d[0].toInt() and 0xff)).toShort().toInt()
                val key = ItemKey(entry, level)
                items[key] = (items[key] ?: 0) + 1
                last = null
            }
            "DELE" -> deleted = true
            "INDX" -> {}
            kind.entryTag -> last = codec.decode(d)
            else -> fail("unexpected subrecord $tag")
        }
    }
    val name = id ?: fail("missing NAME")
    return Parsed(LeveledList(kind, name, items, each, allLevels, noneChance), deleted)
}

internal fun leveledListBody(l: LeveledList, codec: TextCodec): ByteArray {
    val entries = ByteBuilder()
    var count = 0
    for ((k, n) in l.items) repeat(n) {
        entries.sub(l.kind.entryTag, codec.encode(k.id) + 0)
        entries.sub("INTV", byteArrayOf(k.level.toByte(), (k.level shr 8).toByte()))
        count++
    }
    val flags = when (l.kind) {
        ListKind.Items -> (if (l.each) 1 else 0) or (if (l.allLevels) 2 else 0)
        ListKind.Creatures -> if (l.allLevels) 1 else 0
    }
    val body = ByteBuilder()
    body.sub("NAME", codec.encode(l.id) + 0)
    body.sub("DATA", ByteBuilder(4).appendU32(flags.toLong()).toByteArray())
    body.sub("NNAM", byteArrayOf(l.noneChance.toByte()))
    body.sub("INDX", ByteBuilder(4).appendU32(count.toLong()).toByteArray())
    body.append(entries.toByteArray())
    return body.toByteArray()
}
