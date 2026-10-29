package org.alpha3.launcher.tools

import okio.Buffer
import okio.FileHandle
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import org.alpha3.launcher.io.systemFileSystem

/**
 * Kotlin port of OpenMW's `bsatool` (apps/bsatool/bsatool.cpp) plus the uncompressed
 * Morrowind BSA reader/writer from components/bsa/bsafile.cpp.
 *
 * Only the uncompressed (0x100) format is implemented. Compressed BSA (TES4/FO3/SSE) and
 * BA2 (GNRL/DX10) archives are detected but reported as unsupported; they need zlib/LZ4 and
 * texture rebuilding, which have no common-Kotlin implementation here.
 */
object BsaTool {
    const val VERSION = "1.1"

    enum class BsaVersion { Unknown, Uncompressed, Compressed, BA2GNRL, BA2DX10 }

    class BsaException(message: String) : RuntimeException(message)

    class Hash(val low: Int, val high: Int)

    class FileEntry(
        val name: String,
        val fileSize: Long,
        /** Absolute offset in the archive, or -1 for a file added in memory. */
        val offset: Long,
        val hash: Hash,
        internal val added: ByteArray? = null,
    )

    // region Hash

    // From bsapack by ghostwheel; identical to the OpenMW implementation.
    fun getHash(name: String): Hash {
        val l = name.length ushr 1
        var sum = 0
        var off = 0
        var i = 0
        while (i < l) {
            sum = sum xor ((name[i].code and 0xFF) shl (off and 0x1F))
            off += 8
            i++
        }
        val low = sum

        sum = 0
        off = 0
        while (i < name.length) {
            val temp = (name[i].code and 0xFF) shl (off and 0x1F)
            sum = sum xor temp
            sum = sum.rotateRight(temp and 0x1F)
            off += 8
            i++
        }
        return Hash(low, sum)
    }

    // endregion

    fun detectVersion(path: String, fs: FileSystem = systemFileSystem): BsaVersion {
        val p = path.toPath()
        if (!fs.exists(p)) return BsaVersion.Unknown
        val head = fs.source(p).buffer().use { src ->
            if (!src.request(12)) return BsaVersion.Unknown
            Triple(src.readIntLe(), src.readIntLe(), src.readIntLe())
        }
        return when (head.first) {
            0x100 -> BsaVersion.Uncompressed
            0x415342 -> BsaVersion.Compressed // "BSA\0"
            BTDX -> when (head.third) {
                GNRL -> BsaVersion.BA2GNRL
                DX10 -> BsaVersion.BA2DX10
                else -> BsaVersion.Unknown
            }
            else -> BsaVersion.Unknown
        }
    }

    private val BTDX = fourCC("BTDX")
    private val GNRL = fourCC("GNRL")
    private val DX10 = fourCC("DX10")
    private fun fourCC(s: String) =
        (s[0].code) or (s[1].code shl 8) or (s[2].code shl 16) or (s[3].code shl 24)

    /** An uncompressed Morrowind BSA. */
    class BsaFile(private val path: String, private val fs: FileSystem = systemFileSystem) {
        private val okioPath: Path = path.toPath()
        private val files = mutableListOf<FileEntry>()
        private var hasChanged = false
        private var isLoaded = false

        val list: List<FileEntry> get() = files

        private fun fail(msg: String): Nothing = throw BsaException("BSA Error: $msg\nArchive: $path")

        fun open() {
            if (fs.exists(okioPath)) {
                readHeader()
            } else {
                fs.write(okioPath) { }
                writeArchive()
            }
            isLoaded = true
        }

        private fun readHeader() {
            val fsize = fs.metadata(okioPath).size ?: 0L
            if (fsize < 12) fail("File too small to be a valid BSA archive")

            fs.source(okioPath).buffer().use { src ->
                val id = src.readIntLe()
                val dirsize = src.readIntLe().toLong() and 0xFFFFFFFFL
                val filenum = src.readIntLe().toLong() and 0xFFFFFFFFL
                if (id != 0x100) fail("Unrecognized BSA header")

                if (filenum * 21 > fsize - 12 || dirsize + 8 * filenum > fsize - 12) {
                    fail("Directory information larger than entire archive")
                }
                if (dirsize < 12 * filenum) fail("Directory information is corrupt")
                val n = filenum.toInt()

                val offsets = LongArray(3 * n) { src.readIntLe().toLong() and 0xFFFFFFFFL }
                val strings = src.readByteArray(dirsize - 12 * filenum)
                val hashes = List(n) { Hash(src.readIntLe(), src.readIntLe()) }

                val fileDataOffset = 12 + dirsize + 8 * filenum
                for (i in 0 until n) {
                    val size = offsets[i * 2]
                    val offset = offsets[i * 2 + 1] + fileDataOffset
                    if (size + offset > fsize) {
                        fail("Archive contains offsets outside itself: $size + $offset > $fsize")
                    }
                    val nameOffset = offsets[2 * n + i].toInt()
                    if (nameOffset < 0 || nameOffset >= strings.size) fail("Archive contains names offset outside itself")
                    var end = nameOffset
                    while (end < strings.size && strings[end] != 0.toByte()) end++
                    if (end == strings.size) fail("Archive contains non-zero terminated string")
                    val name = buildString { for (k in nameOffset until end) append((strings[k].toInt() and 0xFF).toChar()) }
                    files.add(FileEntry(name, size, offset, hashes[i]))
                }
            }
            files.sortBy { it.offset }
        }

        /**
         * A read handle on the archive. Several threads may read through the same one at once, which also saves
         * opening the archive again for every file. The caller closes it.
         */
        fun openReader(): FileHandle = fs.openReadOnly(okioPath)

        /** The bytes of [entry]. Pass a [reader] from [openReader] when reading many files. */
        fun getFile(entry: FileEntry, reader: FileHandle? = null): ByteArray {
            entry.added?.let { return it }
            if (reader != null) return reader.source(entry.offset).buffer().use { it.readByteArray(entry.fileSize) }
            return fs.openReadOnly(okioPath).use { h ->
                h.source(entry.offset).buffer().use { it.readByteArray(entry.fileSize) }
            }
        }

        /** Adds [data] under [filename]; the archive is rewritten on [close]. */
        fun addFile(filename: String, data: ByteArray) {
            if (!isLoaded) fail("Unable to add file $filename the archive is not opened")
            files.add(FileEntry(filename, data.size.toLong(), -1, getHash(filename), data))
            hasChanged = true
        }

        fun close() {
            if (hasChanged) writeArchive()
            files.clear()
            isLoaded = false
        }

        /** Rewrites the whole archive to a temp file, then swaps it in. */
        private fun writeArchive() {
            val sorted = files.sortedWith(compareBy<FileEntry>({ it.hash.low.toUInt() }, { it.hash.high.toUInt() }))
            val names = Buffer()
            val nameOffsets = IntArray(sorted.size)
            sorted.forEachIndexed { i, f ->
                nameOffsets[i] = names.size.toInt()
                for (c in f.name) names.writeByte(c.code and 0xFF)
                names.writeByte(0)
            }
            val dirsize = 12L * sorted.size + names.size
            val dataOffsets = LongArray(sorted.size)
            var cursor = 0L
            // keep data in the original on-disk order, new files last
            val dataOrder = sorted.indices.sortedBy { i -> sorted[i].offset.let { if (it < 0) Long.MAX_VALUE else it } }
            for (i in dataOrder) { dataOffsets[i] = cursor; cursor += sorted[i].fileSize }

            val tmp = (path + ".tmp").toPath()
            val oldExists = fs.exists(okioPath) && (fs.metadata(okioPath).size ?: 0L) > 0
            val reader = if (oldExists) fs.openReadOnly(okioPath) else null
            try {
                fs.write(tmp) {
                    writeIntLe(0x100)
                    writeIntLe(dirsize.toInt())
                    writeIntLe(sorted.size)
                    for (i in sorted.indices) {
                        writeIntLe(sorted[i].fileSize.toInt())
                        writeIntLe(dataOffsets[i].toInt())
                    }
                    for (o in nameOffsets) writeIntLe(o)
                    writeAll(names)
                    for (f in sorted) { writeIntLe(f.hash.low); writeIntLe(f.hash.high) }
                    for (i in dataOrder) {
                        val f = sorted[i]
                        val added = f.added
                        if (added != null) write(added)
                        else reader!!.source(f.offset).buffer().use { src ->
                            val chunk = Buffer()
                            var left = f.fileSize
                            while (left > 0) {
                                val r = src.read(chunk, minOf(left, 8192L))
                                if (r < 0) fail("Unexpected end of archive")
                                write(chunk, r); left -= r
                            }
                        }
                    }
                }
            } finally {
                reader?.close()
            }
            fs.atomicMove(tmp, okioPath)

            // refresh entries so the object stays usable after a write
            files.clear()
            hasChanged = false
            readHeader()
        }
    }

    // region Extraction helpers (used by the texture converter)

    /** Opens an uncompressed Morrowind BSA for reading, or throws [BsaException] with a readable message. */
    fun openForReading(archive: String, fs: FileSystem = systemFileSystem): BsaFile {
        when (detectVersion(archive, fs)) {
            BsaVersion.Uncompressed -> {}
            BsaVersion.Unknown -> throw BsaException("This is not a Morrowind BSA archive")
            else -> throw BsaException("Only uncompressed Morrowind BSA archives are supported, not Oblivion or newer ones")
        }
        return BsaFile(archive, fs).also { it.open() }
    }

    /**
     * A path from an archive entry that is safe to join to an output folder: forward slashes, no empty, `.` or `..`
     * parts, no drive letter. Archives come from anywhere, so their names are not trusted.
     */
    fun safeRelativePath(entryName: String): String {
        val parts = entryName.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.any { it == "." || it == ".." || ':' in it }) {
            throw BsaException("Unsafe file name in the archive: $entryName")
        }
        return parts.joinToString("/")
    }

    /**
     * Writes [entry] below [outDir], keeping the archive's folder layout. Returns the file written.
     * Pass [reader] (from [BsaFile.openReader]) when extracting many files; set [makeFolder] to false when the folders
     * are already there.
     */
    fun extractEntry(
        bsa: BsaFile,
        entry: FileEntry,
        outDir: String,
        fs: FileSystem = systemFileSystem,
        reader: FileHandle? = null,
        makeFolder: Boolean = true,
    ): String {
        val target = outDir.toPath() / safeRelativePath(entry.name)
        if (makeFolder) target.parent?.let { fs.createDirectories(it) }
        fs.write(target) { write(bsa.getFile(entry, reader)) }
        return target.toString()
    }

    // endregion

    // region Commands (bsatool modes)

    data class Arguments(
        val mode: String,
        val filename: String,
        val extractFile: String = "",
        val addFile: String = "",
        val outDir: String = ".",
        val longFormat: Boolean = false,
        val fullPath: Boolean = false,
    )

    /** Parses `bsatool` arguments. Returns null (after calling [out] with a message) on failure/help. */
    fun parseArguments(args: List<String>, out: (String) -> Unit, workingDir: String = "."): Arguments? {
        var long = false
        var full = false
        val positional = mutableListOf<String>()
        for (a in args) when (a) {
            "-h", "--help" -> { out(USAGE); return null }
            "-v", "--version" -> { out("BSATool version $VERSION"); return null }
            "-l", "--long" -> long = true
            "-f", "--full-path" -> full = true
            else -> positional.add(a)
        }
        if (positional.isEmpty()) { out("ERROR: no mode specified!\n\n$USAGE"); return null }
        val mode = positional[0]
        if (mode !in setOf("list", "extract", "extractall", "add", "create")) {
            out("\nERROR: invalid mode \"$mode\"\n\n$USAGE"); return null
        }
        val input = positional.drop(1)
        if (input.isEmpty()) { out("\nERROR: missing BSA archive\n\n$USAGE"); return null }
        if (input.size > 3) { out("ERROR parsing arguments: too many positional arguments\n\n$USAGE"); return null }

        var outDir = workingDir
        var extractFile = ""
        var addFile = ""
        when (mode) {
            "extract" -> {
                if (input.size < 2) { out("\nERROR: file to extract unspecified\n\n$USAGE"); return null }
                extractFile = input[1]
                if (input.size > 2) outDir = input[2]
            }
            "add" -> if (input.size > 1) addFile = input[1]
            else -> if (input.size > 1) outDir = input[1]
        }
        return Arguments(mode, input[0], extractFile, addFile, outDir, long, full)
    }

    /** Runs a parsed command; returns the process exit code like the C++ tool (0 ok, 1/2/3 errors). */
    fun run(info: Arguments, out: (String) -> Unit, fs: FileSystem = systemFileSystem): Int {
        return try {
            if (info.mode == "create") {
                BsaFile(info.filename, fs).apply { open() }.close()
                return 0
            }
            when (detectVersion(info.filename, fs)) {
                BsaVersion.Uncompressed -> call(info, out, fs)
                BsaVersion.Compressed, BsaVersion.BA2GNRL, BsaVersion.BA2DX10 ->
                    throw BsaException("Archive format not supported by the Kotlin port (only uncompressed Morrowind BSA)")
                BsaVersion.Unknown -> throw BsaException("Unrecognised BSA archive")
            }
        } catch (e: Exception) {
            out("ERROR reading BSA archive\nDetails:\n${e.message}")
            2
        }
    }

    private fun call(info: Arguments, out: (String) -> Unit, fs: FileSystem): Int {
        val bsa = BsaFile(info.filename, fs)
        bsa.open()
        return when (info.mode) {
            "list" -> list(bsa, info, out)
            "extract" -> extract(bsa, info, out, fs)
            "extractall" -> extractAll(bsa, info, out, fs)
            "add" -> add(bsa, info, fs)
            else -> { out("Unsupported mode. That is not supposed to happen."); 1 }
        }
    }

    private fun list(bsa: BsaFile, info: Arguments, out: (String) -> Unit): Int {
        for (file in bsa.list) {
            if (info.longFormat) {
                out(file.name.padEnd(50) + file.fileSize.toString().padEnd(8) + "@ 0x" + file.offset.toString(16))
            } else out(file.name)
        }
        return 0
    }

    private fun extract(bsa: BsaFile, info: Arguments, out: (String) -> Unit, fs: FileSystem): Int {
        val archivePath = info.extractFile.replace('/', '\\')
        val extractPath = info.extractFile.replace('\\', '/')

        // later entries win, so search from the back
        val entry = bsa.list.asReversed().firstOrNull {
            it.name.equals(archivePath, true) || it.name.equals(extractPath, true)
        }
        if (entry == null) {
            out("ERROR: file '$archivePath' not found")
            out("In archive: ${info.filename}")
            return 3
        }

        val rel = extractPath.toPath()
        val target = if (info.fullPath) info.outDir.toPath() / rel else info.outDir.toPath() / (rel.name)
        target.parent?.let { fs.createDirectories(it) }
        if (target.parent?.let { fs.metadataOrNull(it)?.isDirectory } == false) {
            out("ERROR: ${target.parent} is not a directory.")
            return 3
        }
        out("Extracting ${info.extractFile} to $target")
        fs.write(target) { write(bsa.getFile(entry)) }
        return 0
    }

    // Like the original, only .dds entries are extracted.
    private fun extractAll(bsa: BsaFile, info: Arguments, out: (String) -> Unit, fs: FileSystem): Int {
        for (file in bsa.list) {
            if (!file.name.endsWith(".dds", true)) continue
            val target = info.outDir.toPath() / file.name.replace('\\', '/')
            target.parent?.let { fs.createDirectories(it) }
            if (target.parent?.let { fs.metadataOrNull(it)?.isDirectory } == false) {
                out("ERROR: ${target.parent} is not a directory.")
                return 3
            }
            out("Extracting $target")
            fs.write(target) { write(bsa.getFile(file)) }
        }
        return 0
    }

    private fun add(bsa: BsaFile, info: Arguments, fs: FileSystem): Int {
        val data = fs.read(info.addFile.toPath()) { readByteArray() }
        bsa.addFile(info.addFile, data)
        bsa.close()
        return 0
    }

    /** Entry point equivalent of `main(argc, argv)`. */
    fun main(args: List<String>, out: (String) -> Unit = ::println): Int {
        val info = parseArguments(args, out) ?: return 1
        return run(info, out)
    }

    private val USAGE = """Inspect and extract files from Bethesda BSA archives

Usages:
  bsatool list [-l] archivefile
      List the files presents in the input archive.

  bsatool extract [-f] archivefile [file_to_extract] [output_directory]
      Extract a file from the input archive.

  bsatool extractall archivefile [output_directory]
      Extract all files from the input archive.

  bsatool add [-a] archivefile file_to_add
      Add a file to the input archive.

  bsatool create [-c] archivefile
      Create an archive.
Allowed options:
  -h [ --help ]         print help message.
  -v [ --version ]      print version information and quit.
  -l [ --long ]         Include extra information in archive listing.
  -f [ --full-path ]    Create directory hierarchy on file extraction (always true for extractall)."""

    // endregion
}
