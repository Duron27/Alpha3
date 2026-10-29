// SPDX-License-Identifier: GPL-3.0-or-later
package org.alpha3.launcher.tools.openmw

/** Random-access read handle. */
interface ToolReader {
    /** Reads up to [len] bytes at [offset]; returns the count, or -1 at end of file. */
    fun read(offset: Long, dst: ByteArray, dstOffset: Int, len: Int): Int
    fun close()
}

interface ToolWriter {
    fun write(src: ByteArray, offset: Int, len: Int)
    fun close()
}

/**
 * The only way Coverify touches storage. Paths use '/' (or '\' on Windows) and are absolute.
 * Desktop and Android share a java.io implementation; iOS can back this with NSFileManager.
 */
interface ToolFs {
    fun isFile(path: String): Boolean
    fun isDirectory(path: String): Boolean

    /** File names in [path], or null if it is missing or unreadable. */
    fun list(path: String): List<String>?
    fun size(path: String): Long
    fun openRead(path: String): ToolReader
    fun readText(path: String): String
    fun mkdirs(path: String)

    /** Creates a new file, failing if it already exists. */
    fun openWrite(path: String): ToolWriter
    fun rename(from: String, to: String)

    /** Deletes a file or a directory tree; a missing path is not an error. */
    fun delete(path: String)

    /** Overwrites bytes of an existing file in place. */
    fun patch(path: String, offset: Long, data: ByteArray)

    /** Modification time in milliseconds, or 0 if unknown. */
    fun lastModified(path: String): Long = 0L

    fun setLastModified(path: String, millis: Long) {}
}

enum class OsKind { Linux, MacOs, Windows, Android, Ios }

class ToolPlatform(
    val os: OsKind,
    val home: String,
    val env: Map<String, String> = emptyMap(),
    /** Windows Documents folder (may be redirected, e.g. OneDrive). */
    val documents: String? = null,
)

internal fun isAbsolutePath(p: String): Boolean =
    p.startsWith("/") || p.startsWith("\\") || (p.length >= 2 && p[1] == ':' && p[0].isLetter())

/** Normalises separators to '/' and resolves "." and ".." segments. */
internal fun cleanPath(p: String): String {
    val s = p.replace('\\', '/')
    val prefix = when {
        s.startsWith("/") -> "/"
        s.length >= 2 && s[1] == ':' && s[0].isLetter() -> s.substring(0, 2) + if (s.startsWith("/", 2)) "/" else ""
        else -> ""
    }
    val parts = ArrayList<String>()
    for (seg in s.substring(prefix.length).split('/')) {
        when (seg) {
            "", "." -> {}
            ".." -> if (parts.isNotEmpty() && parts.last() != "..") parts.removeAt(parts.size - 1) else if (prefix.isEmpty()) parts.add(seg)
            else -> parts.add(seg)
        }
    }
    val joined = parts.joinToString("/")
    return if (prefix.isEmpty() && joined.isEmpty()) "." else prefix + joined
}

internal fun joinPath(a: String, b: String): String =
    if (a.isEmpty()) b else if (a.endsWith("/") || a.endsWith("\\")) a + b else "$a/$b"

internal fun dirName(p: String): String {
    val i = maxOf(p.lastIndexOf('/'), p.lastIndexOf('\\'))
    return when {
        i < 0 -> "."
        i == 0 -> "/"
        else -> p.substring(0, i)
    }
}

internal fun baseName(p: String): String = p.substring(maxOf(p.lastIndexOf('/'), p.lastIndexOf('\\')) + 1)

internal fun expandHome(p: String, home: String): String =
    if (p.startsWith("~/") || p.startsWith("~\\")) joinPath(home, p.substring(2)) else p
