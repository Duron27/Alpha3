// SPDX-License-Identifier: GPL-3.0-or-later
package org.alpha3.launcher.tools.openmw

/** A file in the VFS: loose, or [archive] bytes at [offset]; [entry] is the name stored in the archive. */
internal class Source(val path: String, val offset: Long, val size: Long, val archive: Boolean, val entry: String = "")

/** OpenMW's virtual file system: case-insensitive data directories, then fallback BSA archives. */
internal class Vfs(
    private val dirs: List<String>,
    private val archives: List<String>,
    private val codec: TextCodec,
    private val fs: ToolFs,
) {
    private val cache = HashMap<String, Map<String, String>?>()
    private var archiveFiles: HashMap<String, Source>? = null

    private fun directory(name: String): Map<String, String>? = cache.getOrPut(name) {
        fs.list(name)?.associateBy { it.lowercase() }
    }

    fun loose(name: String): Source {
        val rel = safeRelative(name)
        val parts = rel.split('/')
        for (i in dirs.indices.reversed()) {
            var p = dirs[i]
            var found = true
            for (part in parts) {
                val actual = directory(p)?.get(part.lowercase())
                if (actual == null) { found = false; break }
                p = joinPath(p, actual)
            }
            if (found) {
                if (!fs.isFile(p)) fail("VFS path is not a regular file: $p")
                return Source(p, 0, fs.size(p), false)
            }
        }
        throw ToolNotFound("$rel: no such file")
    }

    fun resolve(name: String): Source {
        try {
            return loose(name)
        } catch (e: ToolNotFound) {
            val files = archiveFiles ?: loadArchives().also { archiveFiles = it }
            return files[name.replace('\\', '/').lowercase()] ?: throw e
        }
    }

    private fun loadArchives(): HashMap<String, Source> {
        val files = HashMap<String, Source>()
        for (name in archives) {
            val s = try { loose(name) } catch (e: Exception) { fail("fallback archive: ${e.message}") }
            try { readBsa(s, files) } catch (e: ToolException) { fail("${s.path}: ${e.message}") }
        }
        return files
    }

    /** TES3 BSAs have uncompressed data and a separate filename/offset table. */
    private fun readBsa(s: Source, files: HashMap<String, Source>) {
        val f = fs.openRead(s.path)
        try {
            val h = ByteArray(12)
            readExactly(f, 0, h)
            if (u32(h) != 0x100L) fail("unsupported archive format (expected TES3 BSA)")
            val dirSize = u32(h, 4)
            val count = u32(h, 8)
            val dataStart = 12 + dirSize + 8 * count
            if (dirSize < 12 * count || dataStart > s.size || count * 21 > s.size - 12) fail("invalid BSA directory bounds")
            val data = ByteArray(dirSize.toInt())
            readExactly(f, 12, data)
            val names = 12 * count.toInt()
            for (i in 0 until count.toInt()) {
                val size = u32(data, i * 8)
                val off = dataStart + u32(data, i * 8 + 4)
                val nameOff = u32(data, 8 * count.toInt() + i * 4)
                if (off + size > s.size || names + nameOff >= data.size) fail("BSA file $i is out of bounds")
                val start = names + nameOff.toInt()
                var end = start
                while (end < data.size && data[end] != 0.toByte()) end++
                if (end == data.size) fail("unterminated BSA filename")
                val stored = codec.decode(data.copyOfRange(start, end))
                files[safeRelative(stored).lowercase()] = Source(s.path, off, size, true, stored)
            }
        } finally {
            f.close()
        }
    }

    fun copy(s: Source, dest: String, fs: ToolFs) {
        val input = fs.openRead(s.path)
        try {
            fs.mkdirs(dirName(dest))
            val out = fs.openWrite(dest)
            try {
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (done < s.size) {
                    val n = input.read(s.offset + done, buf, 0, minOf(buf.size.toLong(), s.size - done).toInt())
                    if (n <= 0) fail("${s.path}: unexpected end of file")
                    out.write(buf, 0, n)
                    done += n
                }
            } finally {
                out.close()
            }
        } finally {
            input.close()
        }
    }
}

private fun readExactly(f: ToolReader, offset: Long, dst: ByteArray) {
    var done = 0
    while (done < dst.size) {
        val n = f.read(offset + done, dst, done, dst.size - done)
        if (n <= 0) fail("unexpected end of file")
        done += n
    }
}

internal fun safeRelative(raw: String): String {
    val name = raw.replace('\\', '/')
    if (name.isEmpty() || name.startsWith("/") || name.contains(':')) fail("invalid VFS path \"$name\"")
    val segs = name.split('/')
    if (segs.any { it == ".." }) fail("parent traversal in VFS path \"$name\"")
    val cleaned = segs.filter { it.isNotEmpty() && it != "." }.joinToString("/")
    if (cleaned.isEmpty()) fail("invalid VFS file path")
    return cleaned
}
