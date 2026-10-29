// SPDX-License-Identifier: GPL-3.0-or-later
package org.alpha3.launcher.tools.merge

import org.alpha3.launcher.tools.openmw.*

/** One element of a keyed collection or set: the subrecords that make it up, e.g. DODT then DNAM. */
internal class Elem(val subs: List<Sub>) {
    val bytes: ByteArray by lazy(LazyThreadSafetyMode.NONE) {
        val b = ByteBuilder()
        for (s in subs) b.sub(s.tag, s.data)
        b.toByteArray()
    }
    fun sameAs(o: Elem) = bytes.contentEquals(o.bytes)
    /** The int32 count an inventory entry starts with. */
    val count: Int get() = i32(subs[0].data)
    fun withCount(c: Int): Elem {
        val d = subs[0].data.copyOf()
        ByteBuilder(4).appendU32(c.toLong() and 0xffffffffL).toByteArray().copyInto(d)
        return Elem(listOf(Sub(subs[0].tag, d)) + subs.drop(1))
    }
}

/** One edit to a set: the entries [removed] go, [added] come in at the same place, and an edit at the end stays at the end. */
internal class SeqHunk(
    val oldStart: Int, val removed: List<String>, val added: List<Pair<String, Elem>>,
    val before: List<String>, val atEnd: Boolean,
)

internal class FieldDelta(
    /** Chunk key to new data; null removes the chunk. */
    val scalars: LinkedHashMap<String, ByteArray?>,
    /** Per keyed collection: key to an [Elem] (put) or null (remove). */
    val keyed: LinkedHashMap<String, LinkedHashMap<String, Any?>>,
    /** Per set (spells, AI packages), the edits one plugin made to its entries, in order. */
    val seqHunks: LinkedHashMap<String, List<SeqHunk>>,
    /** Per text subrecord, the line edits one plugin made to it. */
    val texts: LinkedHashMap<String, List<TextHunk>> = LinkedHashMap(),
    /** An NPC's 52 byte stat chunk the plugin left as it was in the master's 12 byte form: chunk to (12 byte chunk, from, to). */
    val carry: LinkedHashMap<String, Triple<String, Int, Int>> = LinkedHashMap(),
    /** Per flag field, the bits a plugin switched on and off. */
    val bits: LinkedHashMap<String, Pair<Long, Long>> = LinkedHashMap(),
    /** Per text, whether a plugin ended it with a NUL, when that differs from the version it was made against. */
    val textNul: LinkedHashMap<String, Boolean> = LinkedHashMap(),
    /** The newer plugin's whole text for each entry of [texts], used where a record has no text to edit. */
    val textWhole: LinkedHashMap<String, ByteArray> = LinkedHashMap(),
)

/**
 * A record split into independently mergeable fields: data subrecords cut into their fields,
 * inventories and similar collections keyed by item, and sets of spells, AI packages and the like.
 * A record that does not fit its layout is kept whole under the key "*", and then the last plugin wins.
 */
internal class FieldRecord(
    override val recordTag: String,
    override val recordId: String,
    val schema: Schema,
    val scalars: LinkedHashMap<String, ByteArray> = LinkedHashMap(),
    val keyed: LinkedHashMap<String, LinkedHashMap<String, Elem>> = LinkedHashMap(),
    val seqs: LinkedHashMap<String, ArrayList<Elem>> = LinkedHashMap(),
    /** The dialogue topic an INFO belongs to. */
    val parent: String? = null,
) : Mergeable {
    val flags: Long get() = scalars["@flags"]?.let { u32(it) } ?: 0L

    override fun copyValue(): Mergeable = FieldRecord(
        recordTag, recordId, schema, LinkedHashMap(scalars),
        LinkedHashMap<String, LinkedHashMap<String, Elem>>().also { m -> for ((k, v) in keyed) m[k] = LinkedHashMap(v) },
        LinkedHashMap<String, ArrayList<Elem>>().also { m -> for ((k, v) in seqs) m[k] = ArrayList(v) },
        parent,
    )

    override fun sameAs(o: Mergeable): Boolean {
        if (o !is FieldRecord || o.recordTag != recordTag) return false
        if (scalars.keys != o.scalars.keys) return false
        for ((k, v) in scalars) if (!v.contentEquals(o.scalars.getValue(k))) return false
        for (spec in schema.keyed) {
            val a = keyed[spec.name].orEmpty()
            val b = o.keyed[spec.name].orEmpty()
            if (a.keys != b.keys) return false
            for ((k, e) in a) if (!e.sameAs(b.getValue(k))) return false
        }
        for (spec in schema.seqs) {
            if (occurrences(spec, seqs[spec.name].orEmpty()).sorted() != occurrences(spec, o.seqs[spec.name].orEmpty()).sorted()) return false
        }
        return true
    }

    override fun diffTo(newer: Mergeable): Any {
        newer as FieldRecord
        val sc = LinkedHashMap<String, ByteArray?>()
        val texts = LinkedHashMap<String, List<TextHunk>>()
        val textWhole = LinkedHashMap<String, ByteArray>()
        val textNul = LinkedHashMap<String, Boolean>()
        val bits = LinkedHashMap<String, Pair<Long, Long>>()
        val carry = LinkedHashMap<String, Triple<String, Int, Int>>()
        // Going from the 12 byte form to the 52 byte one, the fields both have only count as changed if they differ.
        val formChange = recordTag == "NPC_" && scalars.keys.any { it.startsWith("NPDT/12:") } && scalars.keys.none { it.startsWith("NPDT/52:") }
        for ((k, v) in newer.scalars) {
            val old = scalars[k]
            if (old == null && formChange) {
                val from = sharedWith12(k)
                val mine = from?.let { scalars[it.first]?.copyOfRange(it.second, it.third) }
                if (from != null && mine != null && mine.contentEquals(v)) { carry[k] = from; continue }
            }
            if (old == null || !old.contentEquals(v)) {
                if (old != null && k in schema.bitFields && old.size == 4 && v.size == 4) {
                    bits[k] = (u32(v) and u32(old).inv() and 0xffffffffL) to (u32(old) and u32(v).inv() and 0xffffffffL)
                    continue
                }
                val hunks = if (old != null && k in schema.textFields) diffText(withoutNul(old), withoutNul(v)) else null
                if (hunks != null) {
                    texts[k] = hunks
                    textWhole[k] = v
                    if (endsWithNul(old!!) != endsWithNul(v)) textNul[k] = endsWithNul(v)
                } else sc[k] = v
            }
        }
        for (k in scalars.keys) if (k !in newer.scalars) sc[k] = null

        val kd = LinkedHashMap<String, LinkedHashMap<String, Any?>>()
        for (spec in schema.keyed) {
            val a = keyed[spec.name].orEmpty()
            val b = newer.keyed[spec.name].orEmpty()
            val d = LinkedHashMap<String, Any?>()
            for ((k, e) in b) {
                val old = a[k]
                when {
                    old == null -> d[k] = e
                    old.sameAs(e) -> {}
                    else -> d[k] = e
                }
            }
            for (k in a.keys) if (k !in b) d[k] = null
            if (d.isNotEmpty()) kd[spec.name] = d
        }

        val hunks = LinkedHashMap<String, List<SeqHunk>>()
        for (spec in schema.seqs) {
            val a = seqs[spec.name].orEmpty()
            val b = newer.seqs[spec.name].orEmpty()
            val oa = occurrences(spec, a)
            val ob = occurrences(spec, b)
            val ranges = diffRanges(oa, ob) ?: listOf(EditRange(0, oa.size, 0, ob.size))
            if (ranges.isNotEmpty()) hunks[spec.name] = ranges.map { r ->
                SeqHunk(
                    r.aFrom, oa.subList(r.aFrom, r.aTo).toList(), (r.bFrom until r.bTo).map { ob[it] to b[it] },
                    oa.subList(maxOf(0, r.aFrom - 2), r.aFrom).toList(), r.aTo == oa.size,
                )
            }
        }
        return FieldDelta(sc, kd, hunks, texts, carry, bits, textNul, textWhole)
    }

    override fun applyDelta(d: Any) {
        d as FieldDelta
        // A data subrecord that comes in two sizes (an NPC's 52 byte stats or its 12 byte autocalculated ones) is split
        // into chunks per size. All the chunks of a size replace the other size; a few of them only change a record
        // that already holds that size, otherwise what is left would be a fragment.
        val provides = LinkedHashMap(d.scalars)
        for ((k, from) in d.carry) {
            val value = scalars[k] ?: scalars[from.first]?.copyOfRange(from.second, from.third) ?: continue
            provides[k] = value
        }
        val changes = LinkedHashMap(provides)
        val variants = provides.keys.filter { '/' in it }.groupBy { it.substringBefore(':') }
        for ((variant, keys) in variants) {
            val tag = variant.substringBefore('/')
            val expected = chunkLayout(recordTag, tag, variant.substringAfter('/').toInt())?.size ?: continue
            val provided = keys.count { provides[it] != null }
            if (provided == expected) scalars.keys.removeAll { it.startsWith("$tag/") && !it.startsWith("$variant:") }
            else if (provided > 0 && scalars.keys.none { it.startsWith("$variant:") }) {
                // A few fields of an NPC's 12 byte form land on the matching fields of its 52 byte stats
                if (variant != "NPDT/12" || recordTag != "NPC_" || scalars.keys.none { it.startsWith("NPDT/52:") })
                    throw Conflict("cannot change $tag of $recordId: the record holds it in another form")
                for (key in keys) {
                    val data = changes.remove(key) ?: continue
                    when (key.substringAfter(':').toInt()) {
                        0 -> changes["NPDT/52:0"] = data
                        1 -> changes["NPDT/52:7"] = data
                        2 -> changes["NPDT/52:8"] = data
                        3 -> changes["NPDT/52:9"] = data
                        4 -> { changes["NPDT/52:3"] = data.copyOfRange(0, 1); changes["NPDT/52:10"] = data.copyOfRange(1, 2) }
                        5 -> changes["NPDT/52:11"] = data
                    }
                }
            }
        }
        for ((k, v) in changes) if (v == null) scalars.remove(k) else scalars[k] = v
        for ((k, change) in d.bits) {
            val current = scalars[k]?.takeIf { it.size == 4 }?.let { u32(it) } ?: throw Conflict("cannot change the flags $k of $recordId: they are not there")
            val (on, off) = change
            if (current and off != off) throw Conflict("cannot switch off a flag of $k in $recordId: it is not set")
            scalars[k] = ByteBuilder(4).appendU32((current and off.inv() or on) and 0xffffffffL).toByteArray()
        }
        for ((k, hunks) in d.texts) {
            val current = scalars[k]
            scalars[k] = when {
                current == null -> d.textWhole.getValue(k)
                // Magic effects and skills are redefined by plugins that never list the original as a master, so their
                // descriptions rarely still hold the lines another plugin edited; the newer text then stands.
                else -> {
                    val body = try { applyText(withoutNul(current), hunks, "$k of $recordId") } catch (c: Conflict) {
                        if (!schema.idIsIndex) throw c
                        withoutNul(d.textWhole.getValue(k))
                    }
                    if (d.textNul[k] ?: endsWithNul(current)) body + 0 else body
                }
            }
        }
        for (spec in schema.keyed) {
            val changes = d.keyed[spec.name] ?: continue
            val map = keyed.getOrPut(spec.name) { LinkedHashMap() }
            for ((k, change) in changes) {
                when (change) {
                    null -> if (map.remove(k) == null) throw Conflict("cannot remove $k from ${spec.name} of $recordId: it is not there")
                    is Elem -> map[k] = change
                }
            }
        }
        for (spec in schema.seqs) {
            val list = seqs.getOrPut(spec.name) { ArrayList() }
            for (h in d.seqHunks[spec.name].orEmpty()) {
                for (id in h.removed) {
                    val at = occurrences(spec, list).indexOf(id)
                    if (at < 0) throw Conflict("cannot remove an entry from ${spec.name} of $recordId: it is not there")
                    list.removeAt(at)
                }
                var at = list.size
                // Spells are a set: what a plugin adds goes after what is there. Packages and travel keep their place.
                if (!spec.byName && !h.atEnd) {
                    val occ = occurrences(spec, list)
                    at = h.before.lastOrNull { it in occ }?.let { occ.indexOf(it) + 1 } ?: minOf(h.oldStart, list.size)
                }
                for ((id, e) in h.added) {
                    if (!spec.duplicates && id in occurrences(spec, list)) continue
                    list.add(at++, e)
                }
            }
        }
    }

    /** Each element's identity plus how many equal elements precede it, so repeated entries stay distinct. */
    private fun occurrences(spec: SeqSpec, list: List<Elem>): List<String> {
        val seen = HashMap<String, Int>()
        return list.map { e ->
            val id = identity(spec, e)
            val n = seen.getOrElse(id) { 0 }
            seen[id] = n + 1
            "$id#$n"
        }
    }

    private fun identity(spec: SeqSpec, e: Elem): String =
        if (spec.byName) trimmedNameExact(e.subs[0].data) else String(CharArray(e.bytes.size) { (e.bytes[it].toInt() and 0xff).toChar() })

    /** The subrecords of this record, without NAME. */
    fun toSubs(): List<Sub> {
        if ("*" in scalars) return readSubs(scalars.getValue("*"))
        val out = ArrayList<Sub>()
        // Group chunks by subrecord, in the order the subrecords first appear.
        val groups = LinkedHashMap<String, LinkedHashMap<String, TreeMap2>>()
        val wholes = ArrayList<Pair<String, ByteArray>>()
        for ((key, data) in scalars) {
            if (key == "@flags") continue
            if (key.endsWith("*")) { wholes.add(key to data); continue }
            val tag = key.substringBefore('/').substringBefore(':')
            val size = if ('/' in key) key.substringAfter('/').substringBefore(':') else ""
            val idx = if (':' in key) key.substringAfter(':').toInt() else 0
            groups.getOrPut(tag) { LinkedHashMap() }.getOrPut(size) { TreeMap2() }.put(idx, data)
        }
        val parts = ArrayList<Pair<String, List<Sub>>>()
        for ((tag, variants) in groups) {
            val b = ByteBuilder()
            for (chunk in variants.values.last().values()) b.append(chunk)
            // DeltaPlugin always ends a text with a NUL, whatever the plugins did
            val bytes = b.toByteArray().let { if (tag in schema.textFields && !endsWithNul(it)) it + 0 else it }
            parts.add(tag to listOf(Sub(tag, if (recordTag == "INGR" && tag == "IRDT") unusedIngredientSlots(bytes) else bytes)))
        }
        for ((key, data) in wholes) parts.add(key to readSubs(data))
        val rank = schema.tagOrder
        val ordered = if (rank.isEmpty()) parts else parts.sortedBy { p -> rank.indexOf(p.first).let { if (it < 0) rank.size else it } }
        for ((_, subs) in ordered) out.addAll(subs)
        for (spec in schema.keyed) keyed[spec.name]?.values?.forEach { out.addAll(it.subs) }
        for (spec in schema.seqs) seqs[spec.name]?.forEach { out.addAll(it.subs) }
        return out
    }
}

/** A tiny sorted int-to-bytes map, so chunk order survives however they were inserted. */
internal class TreeMap2 {
    private val items = ArrayList<Pair<Int, ByteArray>>()
    fun put(i: Int, b: ByteArray) {
        items.removeAll { it.first == i }
        items.add(i to b)
        items.sortBy { it.first }
    }
    fun values(): List<ByteArray> = items.map { it.second }
}

internal fun readSubs(body: ByteArray): List<Sub> {
    val out = ArrayList<Sub>()
    subs(body) { t, d -> out.add(Sub(t, d)) }
    return out
}

/** The name up to its first NUL with its case kept: DeltaPlugin tells "Light" and "light" apart in a set of spells. */
internal fun trimmedNameExact(b: ByteArray): String {
    var end = b.size
    for (i in b.indices) if (b[i].toInt() == 0) { end = i; break }
    return String(CharArray(end) { (b[it].toInt() and 0xff).toChar() })
}

internal fun trimmedName(b: ByteArray): String {
    var end = b.size
    for (i in b.indices) if (b[i].toInt() == 0) { end = i; break }
    val sb = StringBuilder(end)
    for (i in 0 until end) sb.append((b[i].toInt() and 0xff).toChar().lowercaseChar())
    return sb.toString()
}

/** Splits a record body into a [FieldRecord]; a record that does not fit its layout is kept whole. */
internal fun parseFieldRecord(tag: String, id: String, flags: Long, body: ByteArray, schema: Schema, parent: String? = null): FieldRecord? {
    val all = readSubs(body)
    if (!schema.deleteIsField && all.any { it.tag == "DELE" }) return null
    val rec = FieldRecord(tag, id, schema, parent = parent)
    rec.scalars["@flags"] = ByteBuilder(4).appendU32(flags).toByteArray()
    val withoutName = all.filterIndexed { i, s -> !(i == 0 && s.tag == schema.idTag) }
    try {
        var i = 0
        while (i < withoutName.size) {
            val s = withoutName[i]
            val keyedSpec = schema.keyed.firstOrNull { it.start == s.tag }
            val seqSpec = if (keyedSpec == null) schema.seqs.firstOrNull { s.tag in it.starts } else null
            if (keyedSpec != null || seqSpec != null) {
                val attached = keyedSpec?.attached ?: seqSpec!!.attached
                val parts = arrayListOf(if (seqSpec != null && seqSpec.name == "ai") Sub(s.tag, zeroNamePadding(s.tag, s.data)) else s)
                while (i + 1 < withoutName.size && withoutName[i + 1].tag in attached) parts.add(withoutName[++i])
                if (keyedSpec != null) parts[0] = Sub(parts[0].tag, withoutNamePadding(keyedSpec.name, parts[0].data))
                val e = Elem(parts)
                if (keyedSpec != null) {
                    val map = rec.keyed.getOrPut(keyedSpec.name) { LinkedHashMap() }
                    val key = keyedSpec.keyOf(parts)
                    val old = map[key]
                    map[key] = when {
                        old == null -> e
                        keyedSpec.counted -> old.withCount(old.count + e.count)
                        keyedSpec.lastWins -> e
                        else -> throw ToolException("duplicate ${keyedSpec.name} entry $key")
                    }
                } else {
                    val spec = seqSpec!!
                    val list = rec.seqs.getOrPut(spec.name) { ArrayList() }
                    val ident = if (spec.byName) trimmedNameExact(parts[0].data) else null
                    if (ident == null || list.none { trimmedNameExact(it.subs[0].data) == ident }) list.add(e)
                }
            } else if (s.tag in schema.wholeLists) {
                val key = "${schema.wholeGroup ?: s.tag}*"
                val b = ByteBuilder().append(rec.scalars[key] ?: ByteArray(0)).sub(s.tag, s.data)
                rec.scalars[key] = b.toByteArray()
            } else {
                val layout = chunkLayout(tag, s.tag, s.data.size)
                if (layout == null) {
                    if (s.tag in rec.scalars) throw ToolException("duplicate ${s.tag}")
                    rec.scalars[s.tag] = s.data
                } else {
                    var off = 0
                    for ((n, len) in layout.withIndex()) {
                        val key = "${s.tag}/${s.data.size}:$n"
                        if (key in rec.scalars) throw ToolException("duplicate ${s.tag}")
                        rec.scalars[key] = s.data.copyOfRange(off, off + len)
                        off += len
                    }
                }
            }
            i++
        }
    } catch (e: ToolException) {
        // Anything unexpected: keep the record whole, so the last plugin's version wins as it would in the game.
        val whole = FieldRecord(tag, id, schema, parent = parent)
        whole.scalars["@flags"] = rec.scalars["@flags"]!!
        val b = ByteBuilder()
        for (s in withoutName) b.sub(s.tag, s.data)
        whole.scalars["*"] = b.toByteArray()
        return whole
    }
    return rec
}

private val attributeEffects = setOf(17, 22, 74, 79, 85)
private val skillEffects = setOf(21, 26, 78, 83, 89)

/**
 * An ingredient's skill and attribute slots only mean something for effects that take one; DeltaPlugin writes -1 in the
 * others, where the Construction Set leaves 0.
 */
private fun unusedIngredientSlots(data: ByteArray): ByteArray {
    if (data.size != 56) return data
    val out = data.copyOf()
    for (i in 0 until 4) {
        val effect = i32(data.copyOfRange(8 + i * 4, 12 + i * 4))
        for ((base, used) in listOf(24 to skillEffects, 40 to attributeEffects)) {
            val at = base - 8 + i * 4 + 8
            if (effect !in used) for (k in 0 until 4) out[at + k] = 0xff.toByte()
        }
    }
    return out
}

/** Where an NPC's 52 byte stat chunk sits in the 12 byte form (chunk, from, to), when that form has the same field. */
private fun sharedWith12(chunk: String): Triple<String, Int, Int>? = when (chunk) {
    "NPDT/52:0" -> Triple("NPDT/12:0", 0, 2)
    "NPDT/52:7" -> Triple("NPDT/12:1", 0, 1)
    "NPDT/52:8" -> Triple("NPDT/12:2", 0, 1)
    "NPDT/52:9" -> Triple("NPDT/12:3", 0, 1)
    "NPDT/52:3" -> Triple("NPDT/12:4", 0, 1)
    "NPDT/52:10" -> Triple("NPDT/12:4", 1, 2)
    "NPDT/52:11" -> Triple("NPDT/12:5", 0, 4)
    else -> null
}

/**
 * The name in an escort, follow or activate package sits in a 32 byte field; the Construction Set leaves whatever was in
 * memory after its end, which DeltaPlugin does not see, so two plugins' otherwise equal packages stay equal.
 */
private fun zeroNamePadding(tag: String, data: ByteArray): ByteArray {
    val from = when (tag) { "AI_E", "AI_F" -> 14; "AI_A" -> 0; else -> return data }
    val to = minOf(data.size, from + 32)
    var nul = from
    while (nul < to && data[nul].toInt() != 0) nul++
    if (nul >= to) return data
    val out = data.copyOf()
    for (i in nul until to) out[i] = 0
    return out
}

/**
 * Inventory item names sit in a 32 byte field and faction reaction names end at their NUL; the Construction Set leaves
 * stray bytes after that end, which DeltaPlugin does not see, so equal entries from different plugins stay equal.
 */
private fun withoutNamePadding(collection: String, data: ByteArray): ByteArray {
    val from = when (collection) { "inventory" -> 4; "reactions" -> 0; else -> return data }
    var nul = from
    while (nul < data.size && data[nul].toInt() != 0) nul++
    if (collection == "reactions") return if (nul == data.size) data else data.copyOf(nul)
    val end = minOf(data.size, from + 32)
    if (nul >= end) return data
    val out = data.copyOf()
    for (i in nul until end) out[i] = 0
    return out
}

private fun endsWithNul(b: ByteArray) = b.isNotEmpty() && b[b.size - 1].toInt() == 0
private fun withoutNul(b: ByteArray) = if (endsWithNul(b)) b.copyOf(b.size - 1) else b
