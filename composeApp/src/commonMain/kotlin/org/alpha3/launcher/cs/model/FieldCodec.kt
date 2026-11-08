package org.alpha3.launcher.cs.model

import org.alpha3.launcher.tools.openmw.Sub
import org.alpha3.launcher.tools.openmw.TextCodec

/**
 * A field's value: [String] for text, [Long] for whole numbers, [Float], or [ByteArray] for raw bytes.
 * Writing a value changes only that field's bytes; everything else in the subrecord stays as it was.
 */
internal class FieldSlot(val def: FieldDef, val offset: Int, val size: Int)

/** Where each field of [sub] sits, or null when [def] has no layout for its size (the subrecord is shown raw). */
internal fun slots(def: SubDef?, sub: Sub): List<FieldSlot>? {
    val layout = def?.layoutFor(sub.data.size) ?: return null
    val out = ArrayList<FieldSlot>(layout.size)
    var at = 0
    for (f in layout) {
        val n = if (f.type.size < 0) sub.data.size - at else f.type.size
        if (at + n > sub.data.size) return null
        out += FieldSlot(f, at, n)
        at += n
    }
    return out
}

private fun le(b: ByteArray, off: Int, n: Int): Long {
    var v = 0L
    for (i in 0 until n) v = v or ((b[off + i].toLong() and 0xff) shl (8 * i))
    return v
}

private fun putLe(b: ByteArray, off: Int, n: Int, v: Long) {
    for (i in 0 until n) b[off + i] = (v ushr (8 * i)).toByte()
}

internal fun readField(sub: Sub, slot: FieldSlot, codec: TextCodec): Any {
    val d = sub.data
    val o = slot.offset
    return when (val t = slot.def.type) {
        FieldType.ZString, is FieldType.Fixed -> {
            var end = o
            while (end < o + slot.size && d[end] != 0.toByte()) end++
            codec.decode(d.copyOfRange(o, end))
        }
        FieldType.Text -> codec.decode(d.copyOfRange(o, o + slot.size))
        FieldType.I8 -> d[o].toLong()
        FieldType.U8 -> d[o].toLong() and 0xff
        FieldType.I16 -> le(d, o, 2).toShort().toLong()
        FieldType.U16 -> le(d, o, 2)
        FieldType.I32 -> le(d, o, 4).toInt().toLong()
        FieldType.U32 -> le(d, o, 4)
        FieldType.I64 -> le(d, o, 8)
        FieldType.F32 -> Float.fromBits(le(d, o, 4).toInt())
        is FieldType.Bytes -> d.copyOfRange(o, o + slot.size).also { check(t.size == slot.size) }
    }
}

/** [sub] with one field set to [value]; text fields that span the subrecord resize it. */
internal fun writeField(sub: Sub, slot: FieldSlot, value: Any, codec: TextCodec): Sub {
    // the same value: the bytes stay as they are (fixed names often hold leftovers after their NUL)
    val current = readField(sub, slot, codec)
    if (current == value || (current is ByteArray && value is ByteArray && current.contentEquals(value)) ||
        (current is Float && value is Float && current.toBits() == value.toBits())) return sub
    val d = sub.data
    val o = slot.offset
    return when (val t = slot.def.type) {
        FieldType.ZString -> {
            // a NUL is written when the original had one, and always for a new or emptied string
            val text = codec.encode(value as String)
            val hadNul = slot.size == 0 || d.getOrNull(o + slot.size - 1) == 0.toByte()
            Sub(sub.tag, d.copyOfRange(0, o) + text + (if (hadNul) byteArrayOf(0) else ByteArray(0)))
        }
        FieldType.Text -> Sub(sub.tag, d.copyOfRange(0, o) + codec.encode(value as String))
        is FieldType.Fixed -> {
            val out = d.copyOf()
            val text = codec.encode(value as String)
            for (i in 0 until t.size) out[o + i] = if (i < text.size && i < t.size - 1) text[i] else 0
            Sub(sub.tag, out)
        }
        FieldType.F32 -> d.copyOf().also { putLe(it, o, 4, ((value as Number).toFloat()).toBits().toLong()) }.let { Sub(sub.tag, it) }
        is FieldType.Bytes -> d.copyOf().also { (value as ByteArray).copyInto(it, o, 0, minOf(value.size, t.size)) }.let { Sub(sub.tag, it) }
        else -> d.copyOf().also { putLe(it, o, t.size, (value as Number).toLong()) }.let { Sub(sub.tag, it) }
    }
}

/** A new subrecord for [def] with every field zero (or empty text). */
internal fun blankSub(def: SubDef): Sub {
    val layout = def.layouts.firstOrNull() ?: return Sub(def.tag, ByteArray(0))
    val size = layout.sumOf { if (it.type.size < 0) 0 else it.type.size }
    // a lone string field starts as an empty, terminated string
    val data = if (layout.size == 1 && layout[0].type == FieldType.ZString) byteArrayOf(0) else ByteArray(size)
    return Sub(def.tag, data)
}

/** The text an enum or flags value shows as. */
internal fun describe(def: FieldDef, value: Any): String = when {
    def.enum != null && value is Long -> def.enum.getOrNull(enumIndex(def, value)) ?: value.toString()
    def.flags != null && value is Long -> def.flags.filterKeys { value and it != 0L }.values.joinToString(", ").ifEmpty { "none" }
    value is ByteArray -> value.joinToString(" ") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    else -> value.toString()
}

/** The position in [FieldDef.enum] of a stored [value] (-1 or past the end: not a listed value). */
internal fun enumIndex(def: FieldDef, value: Long): Int = def.enumCodes?.indexOf(value) ?: (value - def.enumBase).toInt()

/** The stored value of enum entry [index]. */
internal fun enumCode(def: FieldDef, index: Int): Long = def.enumCodes?.get(index) ?: (index + def.enumBase).toLong()
