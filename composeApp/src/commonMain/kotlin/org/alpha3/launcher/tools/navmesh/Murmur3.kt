// SPDX-License-Identifier: GPL-3.0-or-later
// MurmurHash3_x64_128 (public domain, Austin Appleby), chained over 4096-byte blocks the way OpenMW's
// Files::getHash does: each block is hashed with the previous result as the seed.
package org.alpha3.launcher.tools.navmesh

internal object Murmur3 {
    private const val C1 = -0x783c846eeebdac2bL // 0x87c37b91114253d5
    private const val C2 = 0x4cf5ad432745937fL

    private fun fmix(k0: Long): Long {
        var k = k0
        k = k xor (k ushr 33)
        k *= -0xae502812aa7333L // 0xff51afd7ed558ccd
        k = k xor (k ushr 33)
        k *= -0x3b314601e57a13adL // 0xc4ceb9fe1a85ec53
        k = k xor (k ushr 33)
        return k
    }

    private fun le64(b: ByteArray, p: Int): Long {
        var r = 0L
        for (i in 7 downTo 0) r = (r shl 8) or (b[p + i].toLong() and 0xff)
        return r
    }

    private fun hash128(data: ByteArray, from: Int, len: Int, seed1: Long, seed2: Long): LongArray {
        var h1 = seed1
        var h2 = seed2
        val nblocks = len / 16
        for (i in 0 until nblocks) {
            var k1 = le64(data, from + i * 16)
            var k2 = le64(data, from + i * 16 + 8)
            k1 *= C1; k1 = k1.rotateLeft(31); k1 *= C2; h1 = h1 xor k1
            h1 = h1.rotateLeft(27); h1 += h2; h1 = h1 * 5 + 0x52dce729
            k2 *= C2; k2 = k2.rotateLeft(33); k2 *= C1; h2 = h2 xor k2
            h2 = h2.rotateLeft(31); h2 += h1; h2 = h2 * 5 + 0x38495ab5
        }
        val tail = from + nblocks * 16
        var k1 = 0L
        var k2 = 0L
        val rem = len and 15
        if (rem > 8) {
            for (i in rem - 1 downTo 8) k2 = k2 xor ((data[tail + i].toLong() and 0xff) shl ((i - 8) * 8))
            k2 *= C2; k2 = k2.rotateLeft(33); k2 *= C1; h2 = h2 xor k2
        }
        if (rem > 0) {
            for (i in minOf(rem, 8) - 1 downTo 0) k1 = k1 xor ((data[tail + i].toLong() and 0xff) shl (i * 8))
            k1 *= C1; k1 = k1.rotateLeft(31); k1 *= C2; h1 = h1 xor k1
        }
        h1 = h1 xor len.toLong(); h2 = h2 xor len.toLong()
        h1 += h2; h2 += h1
        h1 = fmix(h1); h2 = fmix(h2)
        h1 += h2; h2 += h1
        return longArrayOf(h1, h2)
    }

    /** The 16-byte hash of a file's contents (the two 64-bit halves, little endian). */
    fun fileHash(bytes: ByteArray): ByteArray {
        var h = longArrayOf(0, 0)
        var pos = 0
        while (pos < bytes.size) {
            val n = minOf(4096, bytes.size - pos)
            h = hash128(bytes, pos, n, h[0], h[1])
            pos += n
        }
        val out = ByteArray(16)
        for (i in 0 until 8) {
            out[i] = (h[0] ushr (8 * i)).toByte()
            out[8 + i] = (h[1] ushr (8 * i)).toByte()
        }
        return out
    }
}
