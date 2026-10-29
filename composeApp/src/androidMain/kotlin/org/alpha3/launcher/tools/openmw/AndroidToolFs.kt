// SPDX-License-Identifier: GPL-3.0-or-later
package org.alpha3.launcher.tools.openmw

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** java.io backed ToolFs for Android. */
object AndroidToolFs : ToolFs {
    override fun isFile(path: String) = File(path).isFile
    override fun isDirectory(path: String) = File(path).isDirectory
    override fun list(path: String): List<String>? = File(path).list()?.toList()
    override fun size(path: String) = File(path).length()
    override fun readText(path: String) = File(path).readText(Charsets.UTF_8)

    override fun openRead(path: String): ToolReader {
        val f = RandomAccessFile(path, "r")
        return object : ToolReader {
            override fun read(offset: Long, dst: ByteArray, dstOffset: Int, len: Int): Int {
                f.seek(offset)
                return f.read(dst, dstOffset, len)
            }
            override fun close() = f.close()
        }
    }

    override fun mkdirs(path: String) {
        if (!File(path).mkdirs() && !File(path).isDirectory) throw java.io.IOException("cannot create directory $path")
    }

    override fun openWrite(path: String): ToolWriter {
        val out = Files.newOutputStream(File(path).toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).buffered(256 * 1024)
        return object : ToolWriter {
            override fun write(src: ByteArray, offset: Int, len: Int) = out.write(src, offset, len)
            override fun close() = out.close()
        }
    }

    override fun rename(from: String, to: String) {
        Files.move(File(from).toPath(), File(to).toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    override fun delete(path: String) {
        File(path).deleteRecursively()
    }

    override fun patch(path: String, offset: Long, data: ByteArray) {
        RandomAccessFile(path, "rw").use { f ->
            f.seek(offset)
            f.write(data)
        }
    }

    override fun lastModified(path: String) = File(path).lastModified()

    override fun setLastModified(path: String, millis: Long) {
        File(path).setLastModified(millis)
    }
}

fun androidToolPlatform(): ToolPlatform {
    val home = System.getProperty("user.home") ?: "/"
    return ToolPlatform(OsKind.Android, home, System.getenv())
}
