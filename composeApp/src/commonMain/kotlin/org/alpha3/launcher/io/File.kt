package org.alpha3.launcher.io

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

internal expect val systemFileSystem: FileSystem

/**
 * Small java.io.File look-alike built on okio so shared code behaves the same on Android and iOS.
 * Only the members the app uses are provided.
 */
class File(path: String) {
    constructor(parent: String?, child: String) : this(join(parent, child))
    constructor(parent: File?, child: String) : this(join(parent?.path, child))

    private val fs get() = systemFileSystem

    val path: String = path.let { if (it.length > 1) it.trimEnd('/') else it }
    private val okioPath: Path get() = path.toPath()

    val name: String get() = path.substringAfterLast('/')
    val absolutePath: String get() = path
    val parent: String? get() = path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }
    val parentFile: File? get() = parent?.let { File(it) }
    val extension: String get() = name.substringAfterLast('.', "")
    val nameWithoutExtension: String get() = name.substringBeforeLast('.')

    fun exists(): Boolean = fs.exists(okioPath)
    val isFile: Boolean get() = fs.metadataOrNull(okioPath)?.isRegularFile == true
    val isDirectory: Boolean get() = fs.metadataOrNull(okioPath)?.isDirectory == true
    fun canRead(): Boolean = exists()
    fun length(): Long = fs.metadataOrNull(okioPath)?.size ?: 0L
    fun lastModified(): Long = fs.metadataOrNull(okioPath)?.lastModifiedAtMillis ?: 0L

    fun delete(): Boolean = try {
        if (!exists()) false else { fs.delete(okioPath); true }
    } catch (e: Exception) { false }

    fun deleteRecursively(): Boolean = try {
        if (exists()) fs.deleteRecursively(okioPath)
        true
    } catch (e: Exception) { false }

    fun mkdir(): Boolean = try {
        if (exists()) false else { fs.createDirectory(okioPath); true }
    } catch (e: Exception) { false }

    fun mkdirs(): Boolean = try {
        if (exists()) false else { fs.createDirectories(okioPath); true }
    } catch (e: Exception) { false }

    fun createNewFile(): Boolean = try {
        if (exists()) false else { fs.write(okioPath) { }; true }
    } catch (e: Exception) { false }

    fun renameTo(dest: File): Boolean = try {
        fs.atomicMove(okioPath, dest.okioPath); true
    } catch (e: Exception) { false }

    fun list(): Array<String>? = try {
        fs.list(okioPath).map { it.name }.toTypedArray()
    } catch (e: Exception) { null }

    fun listFiles(): Array<File>? = try {
        fs.list(okioPath).map { File(it.toString()) }.toTypedArray()
    } catch (e: Exception) { null }

    fun listFiles(filter: (File) -> Boolean): Array<File>? = listFiles()?.filter(filter)?.toTypedArray()

    fun readText(): String = fs.read(okioPath) { readUtf8() }
    fun readBytes(): ByteArray = fs.read(okioPath) { readByteArray() }

    /** The last [maxBytes] of the file, for logs that can be far bigger than anyone wants to load. */
    fun readTailBytes(maxBytes: Long): ByteArray = fs.source(okioPath).buffer().use { source ->
        val size = length()
        if (size > maxBytes) source.skip(size - maxBytes)
        source.readByteArray()
    }
    fun readLines(): List<String> = readText().lines()
    fun forEachLine(action: (String) -> Unit) = readLines().forEach(action)

    fun writeText(text: String) {
        parentFile?.takeIf { !it.exists() }?.mkdirs()
        fs.write(okioPath) { writeUtf8(text) }
    }

    fun writeBytes(bytes: ByteArray) {
        parentFile?.takeIf { !it.exists() }?.mkdirs()
        fs.write(okioPath) { write(bytes) }
    }

    fun appendText(text: String) {
        parentFile?.takeIf { !it.exists() }?.mkdirs()
        fs.appendingSink(okioPath).buffer().use { it.writeUtf8(text) }
    }

    /** A stream into the file, replacing it or added to its end. */
    fun sink(append: Boolean = false): okio.Sink {
        parentFile?.takeIf { !it.exists() }?.mkdirs()
        return if (append) fs.appendingSink(okioPath) else fs.sink(okioPath)
    }

    fun source(): okio.Source = fs.source(okioPath)

    fun copyTo(target: File, overwrite: Boolean = false): File {
        if (target.exists()) {
            if (!overwrite) throw IllegalStateException("Target ${target.path} already exists")
            target.deleteRecursively()
        }
        target.parentFile?.takeIf { !it.exists() }?.mkdirs()
        fs.copy(okioPath, target.okioPath)
        return target
    }

    /** Files and directories below this one, depth first, this directory itself included. */
    fun walk(): FileTreeWalk = FileTreeWalk(this, Int.MAX_VALUE)

    fun walkTopDown(): FileTreeWalk = walk()

    override fun toString(): String = path
    override fun equals(other: Any?): Boolean = other is File && other.path == path
    override fun hashCode(): Int = path.hashCode()

    companion object {
        private fun join(parent: String?, child: String): String = when {
            parent.isNullOrEmpty() -> child
            child.startsWith("/") -> parent.trimEnd('/') + child
            else -> parent.trimEnd('/') + "/" + child
        }
    }
}

fun File.copyRecursively(target: File, overwrite: Boolean = false): Boolean {
    if (!exists()) return false
    if (isDirectory) {
        target.mkdirs()
        listFiles()?.forEach { it.copyRecursively(File(target, it.name), overwrite) }
    } else {
        copyTo(target, overwrite)
    }
    return true
}

class FileTreeWalk(private val root: File, private val maxDepth: Int) : Sequence<File> {
    fun maxDepth(depth: Int): FileTreeWalk = FileTreeWalk(root, depth)

    override fun iterator(): Iterator<File> = walkFrom(root, 0).iterator()

    private fun walkFrom(file: File, depth: Int): Sequence<File> = sequence {
        yield(file)
        if (depth < maxDepth && file.isDirectory) {
            file.listFiles()?.sortedBy { it.name }?.forEach { yieldAll(walkFrom(it, depth + 1)) }
        }
    }
}
