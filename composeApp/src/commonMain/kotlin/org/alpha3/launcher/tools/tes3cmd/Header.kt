// SPDX-License-Identifier: GPL-3.0-or-later
package org.alpha3.launcher.tools.tes3cmd

import org.alpha3.launcher.tools.openmw.*
import kotlin.math.pow

class HeaderOptions(
    /** Replaces the author, or appends when it starts with `+`. */
    val author: String = "",
    /** Replaces the description, or appends when it starts with `+`. `\n` and `\r` are line breaks. */
    val description: String = "",
    val multiline: Boolean = false,
    /** Set the recorded size of each master to the size of the master on disk. */
    val updateMasters: Boolean = false,
    val updateRecordCount: Boolean = false,
) {
    companion object {
        fun synchronize() = HeaderOptions(updateMasters = true, updateRecordCount = true)
    }
}

private const val AUTHOR_LENGTH = 32
private const val DESC_LENGTH = 256

private fun stripNuls(s: String) = s.trimEnd('\u0000')

private fun latin1(b: ByteArray) = String(CharArray(b.size) { (b[it].toInt() and 0xff).toChar() })
private fun latin1Bytes(s: String) = ByteArray(s.length) { s[it].code.toByte() }

/** `pack("a<n>")`: padded with NULs or cut to [n] bytes. */
private fun fixed(s: String, n: Int): ByteArray = latin1Bytes(s).copyOf(n)

/** Reads or rewrites the TES3 header of [plugin], as `tes3cmd header`. */
internal fun Tes3Cmd.updateHeader(plugin: String, mod: ModOptions, h: HeaderOptions, quiet: Boolean = false, noBackup: Boolean = false) {
    if ((h.updateRecordCount || h.updateMasters) && Regex("~\\d+\\.es[mp]$", RegexOption.IGNORE_CASE).containsMatchIn(plugin)) {
        print("tes3cmd header skipping Backup: $plugin")
        return
    }
    val mtime = fs.lastModified(plugin)
    val reader = RecReader(fs.openRead(plugin), fs.size(plugin), baseName(plugin))
    val rec = try { reader.next("TES3", warn = { message("new_from_input(): $it") }) } finally { reader.close() }
        ?: run { error("Error reading TES3 header from $plugin"); return }
    val subs = rec.subs.map { Sub(it.tag, it.data.copyOf()) }.toMutableList()
    var modified = 0
    var updateMsg = ""
    var master: String? = null
    var hedr: Sub? = null
    var description = h.description
    for ((i, s) in subs.withIndex()) {
        if (s.tag == "HEDR" && s.data.size >= 4 + 4 + AUTHOR_LENGTH + DESC_LENGTH + 4) {
            hedr = s
            val d = s.data
            var author = latin1(d.copyOfRange(8, 8 + AUTHOR_LENGTH))
            var desc = latin1(d.copyOfRange(8 + AUTHOR_LENGTH, 8 + AUTHOR_LENGTH + DESC_LENGTH))
            if (h.author.isNotEmpty()) {
                modified++
                author = stripNuls(author)
                // tes3cmd matches /^\+(.*)/, where "." stops at a line break
                author = if (h.author.startsWith("+")) author + h.author.substring(1).substringBefore('\n') else h.author
                fixed(author, AUTHOR_LENGTH).copyInto(d, 8)
            }
            if (description.isNotEmpty()) {
                modified++
                desc = stripNuls(desc)
                description = description.replace("\\n", "\n").replace("\\r", "\r")
                desc = if (description.startsWith("+")) desc + description.substring(1).substringBefore('\n') else description
                fixed(desc, DESC_LENGTH).copyInto(d, 8 + AUTHOR_LENGTH)
            }
            if (h.updateRecordCount) {
                val count = fs.recordCount(plugin)
                val old = u32(d, 8 + AUTHOR_LENGTH + DESC_LENGTH).toInt()
                if (old != count) {
                    modified++
                    updateMsg += "  $plugin: Updated Record Count: $old --> $count\n"
                    for (k in 0 until 4) d[8 + AUTHOR_LENGTH + DESC_LENGTH + k] = (count ushr (8 * k)).toByte()
                }
            }
        }
        if (h.updateMasters) {
            if (s.tag == "MAST") master = zstr(s.data)
            else if (s.tag == "DATA") {
                val m = master
                if (m == null) error("Found DATA (size of master) but no corresponding MAST (name of master)")
                else if (s.data.size >= 8) {
                    val length = u32(s.data) + (u32(s.data, 4) shl 32)
                    val path = findMaster(m, dirName(plugin))
                    val newLen = path?.let { fs.size(it) } ?: 0L
                    if (newLen != length) {
                        modified++
                        updateMsg += "  $plugin: Synchronized master \"$m\" length: $length --> $newLen\n"
                        for (k in 0 until 4) s.data[k] = (newLen ushr (8 * k)).toByte()
                        for (k in 0 until 4) s.data[4 + k] = if (newLen > 0xffffffffL) ((newLen - 0xffffffffL) ushr (8 * k)).toByte() else 0
                    }
                }
            }
        }
        subs[i] = s
    }
    if (!quiet) {
        val hs = hedr
        if (verbose) {
            print("$plugin:\n" + headerText(hs, rec))
        } else if (hs != null && (!(h.author.isNotEmpty() || h.description.isNotEmpty() || h.updateRecordCount || h.updateMasters) || h.author.isNotEmpty() || h.description.isNotEmpty())) {
            if (h.multiline) print("$plugin:\n" + hedrText(hs.data))
            else {
                val d = hs.data
                val auth = latin1(d.copyOfRange(8, 8 + AUTHOR_LENGTH)).substringBefore('\u0000')
                val desc = latin1(d.copyOfRange(8 + AUTHOR_LENGTH, 8 + AUTHOR_LENGTH + DESC_LENGTH)).substringBefore('\u0000')
                print("$plugin: AUTH=\"$auth\"  DESC=\"$desc\"".replace(Regex("(\r)?\n"), "\\\\r\\\\n"))
            }
        }
        if (updateMsg.isNotEmpty()) out(updateMsg) else if (verbose) print("$plugin not modified")
    }
    if (modified > 0) {
        if (!noBackup) copyFile(plugin, uniqueBackupName(plugin, mod))
        val bytes = ByteBuilder().record("TES3", rec.flags, joinSubs(subs)).toByteArray()
        if (bytes.size - HEADER_SIZE != rec.body.size) fail("update_header(): output header size incorrect (expected:${rec.body.size}, got: ${bytes.size - HEADER_SIZE})")
        fs.patch(plugin, 0, bytes)
        if (mtime != 0L) fs.setLastModified(plugin, mtime)
    }
}

private fun hedrText(d: ByteArray): String {
    // "%0.2f" with trailing zeros dropped
    val ver = formatFixed(Float.fromBits(i32(d, 0)).toDouble(), 2).trimEnd('0')
    val author = stripNuls(latin1(d.copyOfRange(8, 8 + AUTHOR_LENGTH)))
    val desc = stripNuls(latin1(d.copyOfRange(8 + AUTHOR_LENGTH, 8 + AUTHOR_LENGTH + DESC_LENGTH)))
    val isMaster = u32(d, 4) != 0L
    val n = u32(d, 8 + AUTHOR_LENGTH + DESC_LENGTH)
    return "  Version:$ver  Is_Master:${if (isMaster) "True" else "False"}  Author:\"$author\"\n  Description:\"$desc\"\n  N_Records:$n"
}

internal fun formatFixed(v: Double, digits: Int): String {
    val neg = v < 0
    val scaled = kotlin.math.round(kotlin.math.abs(v) * 10.0.pow(digits)).toLong()
    val s = scaled.toString().padStart(digits + 1, '0')
    return (if (neg) "-" else "") + s.substring(0, s.length - digits) + "." + s.substring(s.length - digits)
}

private fun Tes3Cmd.headerText(hedr: Sub?, rec: Rec): String = buildString {
    if (hedr != null) append(hedrText(hedr.data))
    for (s in rec.subs) if (s.tag == "MAST") append("\n  MAST: ").append(zstr(s.data))
}
