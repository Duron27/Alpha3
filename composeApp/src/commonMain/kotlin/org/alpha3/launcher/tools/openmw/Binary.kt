// SPDX-License-Identifier: GPL-3.0-or-later
// Kotlin port of GOCoverify (https://gitlab.com/modding-openmw/gocoverify), common code only.
package org.alpha3.launcher.tools.openmw

import kotlin.math.min

class ToolException(message: String) : Exception(message)

/** A missing file, as opposed to an unreadable one; archive lookup falls back only on this. */
class ToolNotFound(message: String) : Exception(message)

internal fun fail(message: String): Nothing = throw ToolException(message)

internal fun u32(b: ByteArray, off: Int = 0): Long =
    (b[off].toLong() and 0xff) or
        ((b[off + 1].toLong() and 0xff) shl 8) or
        ((b[off + 2].toLong() and 0xff) shl 16) or
        ((b[off + 3].toLong() and 0xff) shl 24)

internal fun i32(b: ByteArray, off: Int = 0): Int = u32(b, off).toInt()

internal class Sub(val tag: String, val data: ByteArray)

internal class ByteBuilder(capacity: Int = 64) {
    private var buf = ByteArray(capacity)
    var size = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + extra))
    }

    fun append(b: ByteArray, off: Int = 0, len: Int = b.size - off): ByteBuilder {
        ensure(len)
        b.copyInto(buf, size, off, off + len)
        size += len
        return this
    }

    fun appendAscii(s: String): ByteBuilder {
        ensure(s.length)
        for (c in s) buf[size++] = c.code.toByte()
        return this
    }

    fun appendU32(v: Long): ByteBuilder {
        ensure(4)
        for (i in 0 until 4) buf[size++] = (v ushr (8 * i)).toByte()
        return this
    }

    fun appendU64(v: Long): ByteBuilder {
        ensure(8)
        for (i in 0 until 8) buf[size++] = (v ushr (8 * i)).toByte()
        return this
    }

    fun clear() {
        size = 0
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}

/** Calls [fn] with each subrecord's tag and a copy of its data. */
internal inline fun subs(b: ByteArray, fn: (String, ByteArray) -> Unit) {
    var p = 0
    while (p < b.size) {
        if (b.size - p < 8) fail("truncated subrecord header (${b.size - p} bytes remain)")
        val n = u32(b, p + 4)
        val tag = tagString(b, p)
        if (n > b.size - p - 8) fail("$tag subrecord size $n exceeds remaining ${b.size - p - 8} bytes")
        fn(tag, b.copyOfRange(p + 8, p + 8 + n.toInt()))
        p += 8 + n.toInt()
    }
}

internal fun tagString(b: ByteArray, off: Int): String =
    String(CharArray(4) { (b[off + it].toInt() and 0xff).toChar() })

internal fun ByteBuilder.sub(tag: String, data: ByteArray): ByteBuilder =
    appendAscii(tag).appendU32(data.size.toLong()).append(data)

internal fun ByteBuilder.strSub(tag: String, value: String): ByteBuilder =
    sub(tag, ByteArray(value.length + 1) { if (it < value.length) value[it].code.toByte() else 0 })

internal fun ByteBuilder.uintSub(tag: String, v: Long): ByteBuilder = sub(tag, ByteBuilder(4).appendU32(v).toByteArray())

internal fun floatBytes(f: Float): ByteArray = ByteBuilder(4).appendU32(f.toBits().toLong() and 0xffffffffL).toByteArray()

internal fun ByteBuilder.record(tag: String, flags: Long, data: ByteArray): ByteBuilder {
    appendAscii(tag).appendU32(data.size.toLong()).appendU32(0).appendU32(flags)
    return append(data)
}

/** Sequential reader over random-access input; keeps one read-ahead buffer and skips cheaply. */
internal class BufferedInput(private val src: ToolReader, private val length: Long, bufferSize: Int = 256 * 1024) {
    private val buf = ByteArray(bufferSize)
    private var bufStart = 0L
    private var bufLen = 0
    var pos = 0L
        private set

    fun readFully(dst: ByteArray, len: Int = dst.size) {
        var done = 0
        while (done < len) {
            if (pos < bufStart || pos >= bufStart + bufLen) {
                bufStart = pos
                bufLen = src.read(pos, buf, 0, buf.size)
                if (bufLen <= 0) {
                    bufLen = 0
                    fail("unexpected end of file at $pos")
                }
            }
            val inBuf = (pos - bufStart).toInt()
            val n = min(len - done, bufLen - inBuf)
            buf.copyInto(dst, done, inBuf, inBuf + n)
            pos += n
            done += n
        }
    }

    fun skip(n: Long) {
        if (n > length - pos) fail("skip past end of file")
        pos += n
    }
}
