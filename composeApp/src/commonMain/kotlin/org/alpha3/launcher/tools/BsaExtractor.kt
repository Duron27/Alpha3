package org.alpha3.launcher.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import org.alpha3.launcher.io.systemFileSystem

/**
 * Unpacks a Morrowind BSA with many files in flight at once.
 *
 * Uncompressed archives need no CPU work, the time goes into opening, reading and writing thousands of small files,
 * and on phone storage each of those waits. Running several at a time keeps the storage busy and uses all the cores.
 */
object BsaExtractor {

    /** Where the extraction stands. */
    data class Report(val doneFiles: Int, val totalFiles: Int, val doneBytes: Long, val totalBytes: Long, val lastName: String)

    /**
     * @param workers how many files are extracted at the same time.
     * @param accept which entries to unpack (by archive name).
     * @param onProgress called for about every [reportEvery] files and for the last one, one call at a time.
     * @return the number of files written.
     */
    suspend fun extract(
        archive: String,
        outDir: String,
        workers: Int,
        accept: (String) -> Boolean = { true },
        fs: FileSystem = systemFileSystem,
        reportEvery: Int = 25,
        onProgress: suspend (Report) -> Unit = {},
    ): Int = coroutineScope {
        val bsa = BsaTool.openForReading(archive, fs)
        val reader = bsa.openReader()
        try {
            val entries = bsa.list.filter { accept(it.name) }
            val totalBytes = entries.sumOf { it.fileSize }

            // Names are checked and every folder is made once, before the workers start, so they neither race over
            // the folders nor ask the file system about them thousands of times.
            val folders = LinkedHashSet<String>()
            for (entry in entries) {
                val relative = BsaTool.safeRelativePath(entry.name)
                relative.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let { folders.add("$outDir/$it") }
            }
            withContext(Dispatchers.IO) {
                fs.createDirectories(outDir.toPath())
                for (folder in folders) fs.createDirectories(folder.toPath())
            }

            val permits = Semaphore(workers.coerceAtLeast(1))
            val progressLock = Mutex()
            var done = 0
            var doneBytes = 0L
            entries.map { entry ->
                async(Dispatchers.IO) {
                    permits.withPermit {
                        BsaTool.extractEntry(bsa, entry, outDir, fs, reader, makeFolder = false)
                    }
                    progressLock.withLock {
                        done++
                        doneBytes += entry.fileSize
                        if (done == entries.size || done % reportEvery == 0) {
                            onProgress(Report(done, entries.size, doneBytes, totalBytes, entry.name.substringAfterLast('\\')))
                        }
                    }
                }
            }.awaitAll()
            entries.size
        } finally {
            reader.close()
            bsa.close()
        }
    }
}
