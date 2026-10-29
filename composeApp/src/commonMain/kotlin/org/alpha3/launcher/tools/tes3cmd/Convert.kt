// SPDX-License-Identifier: GPL-3.0-or-later
package org.alpha3.launcher.tools.tes3cmd

import org.alpha3.launcher.tools.openmw.*

/** `tes3cmd esm`: copies a plugin (.esp) to a master (.esm); `tes3cmd esp` does the reverse. */
fun Tes3Cmd.convertPlugin(input: String, toMaster: Boolean, overwrite: Boolean) {
    if (toMaster && !input.endsWith(".esp", ignoreCase = true)) fail("Error, input must be a plugin (.esp)")
    if (!toMaster && !input.endsWith(".esm", ignoreCase = true)) fail("Error, input must be a master (.esm)")
    val expected = if (toMaster) 0 else 1
    val newByte = if (toMaster) 1 else 0
    val output = input.dropLast(4) + if (toMaster) ".esm" else ".esp"
    if (fs.isFile(output) && !overwrite) fail("Error, $output already exists! (Use --overwrite to overwrite)")
    copyFile(input, output)
    try {
        val r = fs.openRead(output)
        try {
            val head = ByteArray(29)
            var got = 0
            while (got < head.size) { val n = r.read(got.toLong(), head, got, head.size - got); if (n <= 0) break; got += n }
            if (got < 4) fail("$output: Error reading magic")
            val magic = tagString(head, 0)
            when (magic) {
                "TES3" -> {
                    if (got < 29) fail("$output: Error reading master byte")
                    val b = head[28].toInt() and 0xff
                    if (b != expected) fail("Error, expected master byte value of $expected, got $b instead")
                }
                "TES4" -> fail("This function is not yet implemented for TES4 files")
                else -> fail("$output: Error, this does not appear to be Morrowind plugin")
            }
        } finally { r.close() }
        fs.patch(output, 28, byteArrayOf(newByte.toByte()))
    } catch (e: ToolException) {
        error(e.message ?: "error")
        fs.delete(output)
        return
    }
    val m = fs.lastModified(input)
    if (m != 0L) fs.setLastModified(output, m)
    print("\"$input\" copied to \"$output\"")
}

/** `tes3cmd common`: the ids two plugins both define. */
fun Tes3Cmd.commonRecords(plugin1: String, plugin2: String) {
    var (a, b) = plugin1 to plugin2
    if (fs.size(a) > fs.size(b)) { val t = a; a = b; b = t }
    val first = HashSet<String>()
    fs.eachRecord(a) { r ->
        if (r.flags and HDR_IGNORED == 0L) first.add("${r.tag}\u0000${r.id()}")
        true
    }
    fs.eachRecord(b) { r ->
        if (r.flags and HDR_IGNORED == 0L && r.tag != "TES3" && "${r.tag}\u0000${r.id()}" in first) out("  ${r.tag}: ${r.id()}\n")
        true
    }
}
