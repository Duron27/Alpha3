// SPDX-License-Identifier: GPL-3.0-or-later
// Record layouts follow the Morrowind plugin format as read by OpenMW and DeltaPlugin.
package org.alpha3.launcher.tools.merge

import org.alpha3.launcher.tools.openmw.*

/** A keyed collection, such as an inventory: one element per key, merged key by key. */
internal class KeyedSpec(
    val name: String,
    val start: String,
    val attached: Set<String> = emptySet(),
    /** The element starts with an int32 count; entries for the same key in one record are summed. */
    val counted: Boolean = false,
    /** A repeated key within one record is not an error: the later entry replaces the earlier one in its place. */
    val lastWins: Boolean = false,
    val keyOf: (List<Sub>) -> String,
)

/** A set of elements (spells, AI packages, effects) in order; plugins add and remove elements. */
internal class SeqSpec(
    val name: String,
    val starts: Set<String>,
    val attached: Set<String> = emptySet(),
    /** Identity is the lowercased text in the first subrecord, rather than all of the bytes. */
    val byName: Boolean = false,
    /** An element a second plugin also adds is kept twice, as DeltaPlugin does for AI packages and travel services. */
    val duplicates: Boolean = false,
)

internal class Schema(
    val tag: String,
    val keyed: List<KeyedSpec> = emptyList(),
    val seqs: List<SeqSpec> = emptyList(),
    /** Repeated subrecords that are replaced as a whole, e.g. a faction's rank names. */
    val wholeLists: Set<String> = emptySet(),
    /** Whole lists that share one key because their subrecords interleave (an INFO's SCVR/INTV/FLTV rules). */
    val wholeGroup: String? = null,
    /** The subrecord that holds the record's id, which is not a field. */
    val idTag: String = "NAME",
    /** A DELE subrecord is a field to merge rather than the end of the record (dialogue keeps deleted infos). */
    val deleteIsField: Boolean = false,
    /** Output order of the subrecords, when it matters to readers of the plugin. */
    val tagOrder: List<String> = emptyList(),
    /** Subrecords that are text: plugins' edits to them are merged line by line instead of the last one winning. */
    val textFields: Set<String> = emptySet(),
    /** The id subrecord is a 4 byte index (magic effects, skills) rather than a name. */
    val idIsIndex: Boolean = false,
    /** Data chunks that are sets of flag bits: plugins' changes combine bit by bit and conflict over a bit that is gone. */
    val bitFields: Set<String> = emptySet(),
)

private fun trimmedLower(b: ByteArray, from: Int = 0): String {
    var end = b.size
    for (i in from until b.size) if (b[i].toInt() == 0) { end = i; break }
    val sb = StringBuilder(maxOf(0, end - from))
    for (i in from until end) sb.append((b[i].toInt() and 0xff).toChar().lowercaseChar())
    return sb.toString()
}

private val inventory = KeyedSpec("inventory", "NPCO", counted = true) { trimmedLower(it[0].data, 4) }
private val spells = SeqSpec("spells", setOf("NPCS"), byName = true)
private val aiPackages = SeqSpec("ai", setOf("AI_W", "AI_T", "AI_F", "AI_E", "AI_A"), attached = setOf("CNDT"), duplicates = true)
private val travel = SeqSpec("travel", setOf("DODT"), attached = setOf("DNAM"), duplicates = true)
private val effects = SeqSpec("effects", setOf("ENAM"))
private val bodyParts = KeyedSpec("bodyparts", "INDX", attached = setOf("BNAM", "CNAM")) { (it[0].data.getOrNull(0)?.toInt() ?: 0).and(0xff).toString() }

private val schemas: Map<String, Schema> = listOf(
    Schema("CONT", keyed = listOf(inventory)),
    Schema("NPC_", keyed = listOf(inventory), seqs = listOf(spells, aiPackages, travel), bitFields = setOf("AIDT/12:5", "FLAG")),
    Schema("CREA", keyed = listOf(inventory), seqs = listOf(spells, aiPackages, travel), bitFields = setOf("AIDT/12:5")),
    Schema("ARMO", keyed = listOf(bodyParts)),
    Schema("CLOT", keyed = listOf(bodyParts)),
    Schema("ALCH", seqs = listOf(effects)),
    Schema("ENCH", seqs = listOf(effects)),
    Schema("SPEL", seqs = listOf(effects)),
    Schema("BSGN", seqs = listOf(spells)),
    Schema("RACE", seqs = listOf(spells)),
    Schema("REGN", seqs = listOf(SeqSpec("sounds", setOf("SNAM")))),
    Schema(
        "FACT",
        keyed = listOf(KeyedSpec("reactions", "ANAM", attached = setOf("INTV"), lastWins = true) { trimmedLower(it[0].data) }),
        wholeLists = setOf("RNAM"),
    ),
    Schema("BOOK", textFields = setOf("TEXT")),
    Schema(
        "MGEF", idTag = "INDX", idIsIndex = true, textFields = setOf("DESC"),
        tagOrder = listOf("MEDT", "ITEX", "PTEX", "CVFX", "BVFX", "HVFX", "AVFX", "DESC", "CSND", "BSND", "HSND", "ASND"),
    ),
    Schema("SKIL", idTag = "INDX", idIsIndex = true, textFields = setOf("DESC"), tagOrder = listOf("SKDT", "DESC")),
    Schema("DIAL", deleteIsField = true),
    Schema(
        "INFO", idTag = "INAM", deleteIsField = true,
        wholeLists = setOf("SCVR", "INTV", "FLTV"), wholeGroup = "SCVR",
        tagOrder = listOf("PNAM", "NNAM", "DELE", "DATA", "ONAM", "RNAM", "CNAM", "FNAM", "ANAM", "DNAM", "SNAM", "NAME", "SCVR*", "BNAM", "QSTN", "QSTF", "QSTR"),
    ),
    // Records with a plain set of subrecords: each is a field of its own.
    *listOf("ACTI", "APPA", "CLAS", "DOOR", "INGR", "LIGH", "LOCK", "MISC",
        "PROB", "REPA", "SNDG", "SOUN", "STAT", "WEAP", "BODY").map { Schema(it) }.toTypedArray(),
).associateBy { it.tag }

internal fun schemaFor(tag: String): Schema? = schemas[tag]

/** Record types that are merged field by field (levelled lists and dialogue have their own handling). */
internal fun isFieldRecord(tag: String) = tag in schemas

private fun u(vararg n: Int) = n
private fun ones(n: Int) = IntArray(n) { 1 }
private fun fours(n: Int) = IntArray(n) { 4 }

/**
 * How fixed-size data subrecords split into independent fields, keyed "RECORD:SUBRECORD:size", so
 * that two plugins changing different fields of the same record both survive. Sizes without an
 * entry stay whole.
 */
private val layouts: Map<String, IntArray> = mapOf(
    // level, 8 attributes, 27 skills, unknown, health, magicka, fatigue, disposition, reputation, rank, unknown, gold
    "INFO:DATA:12" to u(4, 4, 1, 1, 1, 1),
    // level, attributes, skills, unknown, health, magicka, fatigue, disposition, reputation, rank, unknown, gold
    "NPC_:NPDT:52" to u(2, 8, 27, 1, 2, 2, 2, 1, 1, 1, 1, 4),
    "NPC_:NPDT:12" to u(2, 1, 1, 1, 3, 4),
    "NPC_:AIDT:12" to u(2, 1, 1, 1, 3, 4),
    "CREA:AIDT:12" to u(2, 1, 1, 1, 3, 4),
    "CREA:NPDT:96" to fours(24),
    "CONT:CNDT:4" to u(4),
    // two attributes, ten ranks of five ints each, seven skills, hidden flag
    "FACT:FADT:240" to (u(8) + IntArray(10) { 20 } + u(28, 4)),
    "WEAP:WPDT:32" to u(4, 4, 2, 2, 4, 4, 2, 1, 1, 1, 1, 1, 1, 4),
    "ARMO:AODT:24" to fours(6),
    "CLOT:CTDT:12" to u(4, 4, 2, 2),
    "BOOK:BKDT:20" to fours(5),
    // school, base cost, flags, colour, speed, size, size cap
    "MGEF:MEDT:36" to fours(9),
    // attribute, specialization, four use values
    "SKIL:SKDT:24" to fours(6),
    "MISC:MCDT:12" to fours(3),
    "LIGH:LHDT:24" to fours(6),
    "APPA:AADT:16" to fours(4),
    "LOCK:LKDT:16" to fours(4),
    "PROB:PBDT:16" to fours(4),
    "REPA:RIDT:16" to fours(4),
    "INGR:IRDT:56" to fours(14),
    "ALCH:ALDT:12" to fours(3),
    "ENCH:ENDT:16" to fours(4),
    "SPEL:SPDT:12" to fours(3),
)

internal fun chunkLayout(record: String, sub: String, size: Int): IntArray? =
    layouts["$record:$sub:$size"]?.takeIf { it.sum() == size }
