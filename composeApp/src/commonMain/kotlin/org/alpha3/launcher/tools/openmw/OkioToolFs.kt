// SPDX-License-Identifier: GPL-3.0-or-later
package org.alpha3.launcher.tools.openmw

import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer

/** okio backed [ToolFs] for platforms without java.io (iOS). Modification times are only read; override [setLastModified] to set them. */
open class OkioToolFs(private val fs: FileSystem = FileSystem.SYSTEM) : ToolFs {
    override fun isFile(path: String) = fs.metadataOrNull(path.toPath())?.isRegularFile == true
    override fun isDirectory(path: String) = fs.metadataOrNull(path.toPath())?.isDirectory == true
    override fun list(path: String): List<String>? = fs.listOrNull(path.toPath())?.map { it.name }
    override fun size(path: String) = fs.metadataOrNull(path.toPath())?.size ?: 0L
    override fun readText(path: String): String = fs.read(path.toPath()) { readUtf8() }

    override fun openRead(path: String): ToolReader {
        val h = fs.openReadOnly(path.toPath())
        return object : ToolReader {
            override fun read(offset: Long, dst: ByteArray, dstOffset: Int, len: Int) = h.read(offset, dst, dstOffset, len)
            override fun close() = h.close()
        }
    }

    override fun mkdirs(path: String) = fs.createDirectories(path.toPath())

    override fun openWrite(path: String): ToolWriter {
        val out = fs.sink(path.toPath(), mustCreate = true).buffer()
        return object : ToolWriter {
            override fun write(src: ByteArray, offset: Int, len: Int) { out.write(src, offset, len) }
            override fun close() = out.close()
        }
    }

    override fun rename(from: String, to: String) {
        fs.delete(to.toPath(), mustExist = false)
        fs.atomicMove(from.toPath(), to.toPath())
    }

    override fun delete(path: String) = fs.deleteRecursively(path.toPath(), mustExist = false)

    override fun patch(path: String, offset: Long, data: ByteArray) {
        val handle = fs.openReadWrite(path.toPath())
        try { handle.write(offset, data, 0, data.size) } finally { handle.close() }
    }

    override fun lastModified(path: String) = fs.metadataOrNull(path.toPath())?.lastModifiedAtMillis ?: 0L
}
