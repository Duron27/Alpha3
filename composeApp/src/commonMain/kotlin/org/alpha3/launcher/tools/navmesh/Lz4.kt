// SPDX-License-Identifier: GPL-3.0-or-later
// A port of the LZ4 block format (lz4.c, BSD 2-Clause, Copyright (c) Yann Collet). The compressor follows
// LZ4_compress_default so that its output is byte-identical, which keeps blobs comparable with
// databases written by OpenMW's own tool.
package org.alpha3.launcher.tools.navmesh

internal object Lz4 {
    private const val MIN_MATCH = 4
    private const val LAST_LITERALS = 5
    private const val MF_LIMIT = 12
    private const val MIN_LENGTH = MF_LIMIT + 1
    private const val SKIP_TRIGGER = 6
    private const val HASH_LOG = 12
    private const val RUN_MASK = 15
    private const val ML_MASK = 15
    private const val ML_BITS = 4
    private const val DISTANCE_MAX = 65535
    private const val INPUT_64K_LIMIT = 65547

    fun compressBound(size: Int) = size + size / 255 + 16

    private fun read32(b: ByteArray, p: Int): Int =
        (b[p].toInt() and 0xff) or ((b[p + 1].toInt() and 0xff) shl 8) or
            ((b[p + 2].toInt() and 0xff) shl 16) or ((b[p + 3].toInt() and 0xff) shl 24)

    private fun read64(b: ByteArray, p: Int): Long =
        (read32(b, p).toLong() and 0xffffffffL) or (read32(b, p + 4).toLong() shl 32)

    private fun hash(b: ByteArray, p: Int, small: Boolean): Int =
        if (small) ((read32(b, p) * -1640531535) ushr (32 - (HASH_LOG + 1)))
        else (((read64(b, p) shl 24) * 889523592379L) ushr (64 - HASH_LOG)).toInt()

    /** LZ4_compress_default: a single block, no header. */
    fun compress(src: ByteArray): ByteArray {
        val n = src.size
        val dst = ByteArray(compressBound(n))
        var op = 0
        val small = n < INPUT_64K_LIMIT
        val table = IntArray(if (small) 1 shl (HASH_LOG + 1) else 1 shl HASH_LOG)
        var ip = 0
        var anchor = 0
        val iend = n
        val mflimitPlusOne = iend - MF_LIMIT + 1
        val matchLimit = iend - LAST_LITERALS

        if (n >= MIN_LENGTH) {
            table[hash(src, ip, small)] = ip
            ip++
            var forwardH = hash(src, ip, small)
            outer@ while (true) {
                var match = 0
                var forwardIp = ip
                var step = 1
                var searchMatchNb = 1 shl SKIP_TRIGGER
                while (true) {
                    val h = forwardH
                    val current = forwardIp
                    val matchIndex = table[h]
                    ip = forwardIp
                    forwardIp += step
                    step = searchMatchNb++ ushr SKIP_TRIGGER
                    if (forwardIp > mflimitPlusOne) break@outer
                    match = matchIndex
                    forwardH = hash(src, forwardIp, small)
                    table[h] = current
                    if (!small && matchIndex + DISTANCE_MAX < current) continue
                    if (read32(src, match) == read32(src, ip)) break
                }
                // catch up
                while (ip > anchor && match > 0 && src[ip - 1] == src[match - 1]) { ip--; match-- }
                // literals
                val litLength = ip - anchor
                var token = op++
                if (litLength >= RUN_MASK) {
                    var len = litLength - RUN_MASK
                    dst[token] = (RUN_MASK shl ML_BITS).toByte()
                    while (len >= 255) { dst[op++] = 255.toByte(); len -= 255 }
                    dst[op++] = len.toByte()
                } else dst[token] = (litLength shl ML_BITS).toByte()
                src.copyInto(dst, op, anchor, anchor + litLength)
                op += litLength
                while (true) {
                    // offset
                    val offset = ip - match
                    dst[op++] = offset.toByte(); dst[op++] = (offset ushr 8).toByte()
                    // match length
                    var matchCode = count(src, ip + MIN_MATCH, match + MIN_MATCH, matchLimit)
                    ip += matchCode + MIN_MATCH
                    if (matchCode >= ML_MASK) {
                        dst[token] = (dst[token] + ML_MASK).toByte()
                        matchCode -= ML_MASK
                        fill255(dst, op)
                        while (matchCode >= 4 * 255) {
                            op += 4; fill255(dst, op); matchCode -= 4 * 255
                        }
                        op += matchCode / 255
                        dst[op++] = (matchCode % 255).toByte()
                    } else dst[token] = (dst[token] + matchCode).toByte()
                    anchor = ip
                    if (ip >= mflimitPlusOne) break@outer
                    table[hash(src, ip - 2, small)] = ip - 2
                    val h = hash(src, ip, small)
                    val current = ip
                    val matchIndex = table[h]
                    table[h] = current
                    if ((small || matchIndex + DISTANCE_MAX >= current) && read32(src, matchIndex) == read32(src, ip)) {
                        token = op++
                        dst[token] = 0
                        match = matchIndex
                        continue
                    }
                    break
                }
                forwardH = hash(src, ++ip, small)
            }
        }
        // last literals
        val lastRun = iend - anchor
        if (lastRun >= RUN_MASK) {
            var acc = lastRun - RUN_MASK
            dst[op++] = (RUN_MASK shl ML_BITS).toByte()
            while (acc >= 255) { dst[op++] = 255.toByte(); acc -= 255 }
            dst[op++] = acc.toByte()
        } else dst[op++] = (lastRun shl ML_BITS).toByte()
        src.copyInto(dst, op, anchor, anchor + lastRun)
        op += lastRun
        return dst.copyOf(op)
    }

    private fun fill255(d: ByteArray, p: Int) { for (i in 0 until 4) if (p + i < d.size) d[p + i] = 255.toByte() }

    private fun count(b: ByteArray, inIdx: Int, matchIdx: Int, limit: Int): Int {
        var p = inIdx
        var m = matchIdx
        while (p < limit && b[p] == b[m]) { p++; m++ }
        return p - inIdx
    }

    /** LZ4_decompress_safe; [size] is the exact decoded length. */
    fun decompress(src: ByteArray, from: Int, size: Int): ByteArray {
        val out = ByteArray(size)
        var ip = from
        var op = 0
        val end = src.size
        while (true) {
            val token = src[ip++].toInt() and 0xff
            var len = token ushr ML_BITS
            if (len == RUN_MASK) {
                var s: Int
                do { s = src[ip++].toInt() and 0xff; len += s } while (s == 255)
            }
            if (ip + len > end || op + len > size) throw IllegalArgumentException("corrupt LZ4 literals")
            src.copyInto(out, op, ip, ip + len)
            ip += len; op += len
            if (ip >= end) break
            val offset = (src[ip].toInt() and 0xff) or ((src[ip + 1].toInt() and 0xff) shl 8)
            ip += 2
            if (offset == 0 || offset > op) throw IllegalArgumentException("corrupt LZ4 offset")
            var ml = token and ML_MASK
            if (ml == ML_MASK) {
                var s: Int
                do { s = src[ip++].toInt() and 0xff; ml += s } while (s == 255)
            }
            ml += MIN_MATCH
            if (op + ml > size) throw IllegalArgumentException("corrupt LZ4 match")
            var m = op - offset
            repeat(ml) { out[op++] = out[m++] }
        }
        if (op != size) throw IllegalArgumentException("LZ4 size mismatch")
        return out
    }
}
