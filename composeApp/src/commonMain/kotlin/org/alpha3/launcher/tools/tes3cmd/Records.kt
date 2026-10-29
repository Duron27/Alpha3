// SPDX-License-Identifier: GPL-3.0-or-later
// Kotlin port of tes3cmd (https://github.com/john-moonsugar/tes3cmd), MIT licensed.
package org.alpha3.launcher.tools.tes3cmd

import org.alpha3.launcher.tools.openmw.*

internal val RECTYPES = ("ACTI ALCH APPA ARMO BODY BOOK BSGN CELL CLAS CLOT CNTC CONT CREA CREC DIAL DOOR ENCH FACT FMAP GAME GLOB GMST INFO " +
    "INGR JOUR KLST LAND LEVC LEVI LIGH LOCK LTEX MGEF MISC NPCC NPC_ PCDT PGRD PROB PROJ QUES RACE REFR REGN REPA SCPT SKIL SNDG SOUN " +
    "SPEL SPLM SSCR STAT STLN TES3 VFXM WEAP").split(' ').toSet()

internal const val HDR_DELETED = 0x0020L
internal const val HDR_PERSISTENT = 0x0400L
internal const val HDR_IGNORED = 0x1000L
internal const val HDR_BLOCKED = 0x2000L

internal const val HEADER_SIZE = 16

/** A record as stored: type, header flags and the raw subrecord bytes. */
internal class Rec(val tag: String, val flags: Long, val body: ByteArray) {
    val subs: List<Sub> by lazy { parseSubs(body, tag) }
}

/** Splits a record body into subrecords, keeping what can be read of a malformed one. */
internal fun parseSubs(body: ByteArray, tag: String = "", warn: (String) -> Unit = {}): List<Sub> {
    val out = ArrayList<Sub>()
    var p = 0
    while (p < body.size) {
        if (body.size - p < 8) { warn("$tag has malformed recbuf"); break }
        val n = u32(body, p + 4)
        val avail = (body.size - p - 8).toLong()
        val len = if (n > avail) { warn("$tag.${tagString(body, p)} has malformed subbuf"); avail } else n
        out.add(Sub(tagString(body, p), body.copyOfRange(p + 8, p + 8 + len.toInt())))
        p += 8 + len.toInt()
    }
    return out
}

internal fun joinSubs(subs: List<Sub>): ByteArray {
    val b = ByteBuilder(subs.sumOf { it.data.size + 8 })
    for (s in subs) b.sub(s.tag, s.data)
    return b.toByteArray()
}

/** Writes a record the way tes3cmd does: the unused second length word is zero. */
internal fun ByteBuilder.writeRec(r: Rec): ByteBuilder = record(r.tag, r.flags, r.body)

/** Perl's `lc` on a byte string: ASCII only. */
internal fun lcAscii(s: String): String {
    val sb = StringBuilder(s.length)
    for (c in s) sb.append(if (c in 'A'..'Z') c + 32 else c)
    return sb.toString()
}

/** `unpack("Z*")` on bytes viewed as Latin-1. */
internal fun zstr(b: ByteArray?): String {
    if (b == null) return ""
    var end = b.indexOf(0)
    if (end < 0) end = b.size
    return String(CharArray(end) { (b[it].toInt() and 0xff).toChar() })
}

internal fun zstrFixed(b: ByteArray, n: Int): String = zstr(b.copyOf(minOf(n, b.size)))

private val noIdTypes = setOf("TES3", "FMAP", "GAME", "PCDT", "SPLM", "VFXM")

private fun first(subs: List<Sub>, tag: String) = subs.firstOrNull { it.tag == tag }?.data

/** The record id tes3cmd compares records by (lowercased). */
internal fun recordId(tag: String, subs: List<Sub>): String {
    fun name() = zstr(first(subs, "NAME"))
    val id = when {
        tag == "INFO" -> zstr(first(subs, "INAM"))
        tag == "CELL" -> {
            val n = name()
            val d = first(subs, "DATA") ?: ByteArray(12)
            if (d.size >= 4 && u32(d) and 1L != 0L) n else {
                val nn = n.ifEmpty { first(subs, "RGNN")?.let { zstr(it) } ?: "wilderness" }
                "$nn (${if (d.size >= 12) i32(d, 4) else 0}, ${if (d.size >= 12) i32(d, 8) else 0})"
            }
        }
        tag == "PGRD" -> {
            val d = first(subs, "DATA") ?: ByteArray(8)
            val x = i32(d, 0); val y = i32(d, 4)
            if (x == 0 && y == 0) name() else "${name()} ($x, $y)"
        }
        tag == "SCPT" -> zstrFixed(first(subs, "SCHD") ?: ByteArray(0), 32)
        tag in noIdTypes -> "()"
        tag == "LAND" -> first(subs, "INTV")?.let { "(${i32(it, 0)}, ${i32(it, 4)})" } ?: "()"
        tag == "SSCR" -> zstrFixed(first(subs, "DATA") ?: ByteArray(0), 32)
        first(subs, "NAME") != null -> name()
        first(subs, "INDX") != null -> u32(first(subs, "INDX")!!).toString()
        else -> "()"
    }
    return lcAscii(id)
}

internal fun Rec.id(): String = recordId(tag, subs)

/** CELL helpers. */
internal fun Rec.cellIsInterior(): Boolean = subs.firstOrNull { it.tag == "DATA" }?.data?.let { it.size >= 4 && u32(it) and 1L != 0L } ?: false

/** Streams the records of a plugin. */
internal class RecReader(private val reader: ToolReader, private val size: Long, private val name: String) {
    private val input = BufferedInput(reader, size)
    val pos get() = input.pos

    /** Reads the next record, or null at the end; throws on a malformed one. */
    fun next(expected: String? = null, strict: Boolean = false, warn: (String) -> Unit = {}): Rec? {
        if (input.pos >= size) return null
        val start = input.pos
        if (size - start < HEADER_SIZE) fail("read error ($name header at byte: $start): asked for $HEADER_SIZE bytes, got ${size - start}")
        val h = ByteArray(HEADER_SIZE)
        input.readFully(h)
        val tag = tagString(h, 0)
        val len = u32(h, 4)
        val flags = u32(h, 12)
        if (tag !in RECTYPES) {
            if (strict) fail("Error ($name at byte: $start): Invalid Record Type: \"$tag\"")
            warn("Type Error (at byte: $start): Unknown Record Type: \"$tag\"")
        }
        if (expected != null && expected != tag) {
            if (strict) fail("Error ($name at byte: $start): Expected: \"$expected\", got: \"$tag\"")
            warn("Type Error (at byte: $start): Expected: \"$expected\", got: \"$tag\"")
        }
        if (len > size - input.pos) fail("Read Error (at byte: $start, rec_type=\"$tag\"): asked for $len bytes, got ${size - input.pos}")
        val body = ByteArray(len.toInt())
        input.readFully(body)
        return Rec(tag, flags, body)
    }

    fun close() = reader.close()
}

/** Reads records until the end, stopping early if [fn] returns false. */
internal inline fun ToolFs.eachRecord(path: String, strict: Boolean = false, noinline warn: (String) -> Unit = {}, fn: (Rec) -> Boolean) {
    val r = RecReader(openRead(path), size(path), baseName(path))
    try {
        while (true) {
            val rec = r.next(strict = strict, warn = warn) ?: break
            if (!fn(rec)) break
        }
    } finally {
        r.close()
    }
}

/** Number of records in a plugin, not counting the TES3 header. */
internal fun ToolFs.recordCount(path: String): Int {
    var n = -1
    eachRecord(path, strict = true) { n++; true }
    return maxOf(n, 0)
}

/** Appends to a file, in chunks. */
internal class OutFile(private val w: ToolWriter) {
    private val buf = ByteBuilder(1 shl 16)
    fun write(r: Rec) {
        buf.writeRec(r)
        if (buf.size > 1 shl 20) flush()
    }
    fun flush() {
        if (buf.size == 0) return
        val b = buf.toByteArray()
        w.write(b, 0, b.size)
        buf.clear()
    }
    fun close() { flush(); w.close() }
}
