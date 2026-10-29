package org.openmw.modDownloader

import android.util.Log
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import net.sf.sevenzipjbinding.ArchiveFormat
import net.sf.sevenzipjbinding.ExtractAskMode
import net.sf.sevenzipjbinding.ExtractOperationResult
import net.sf.sevenzipjbinding.IArchiveExtractCallback
import net.sf.sevenzipjbinding.IInArchive
import net.sf.sevenzipjbinding.ISequentialOutStream
import net.sf.sevenzipjbinding.PropID
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.SevenZip.getSevenZipVersion
import net.sf.sevenzipjbinding.SevenZipException
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import okhttp3.internal.closeQuietly
import kotlinx.coroutines.flow.first
import org.alpha3.launcher.paths.OpenMWPaths
import org.alpha3.launcher.utils.GameFilesPreferences
import org.alpha3.launcher.tools.modActionMasterDirs
import org.alpha3.launcher.tools.PRUNE_MANIFEST
import org.alpha3.launcher.tools.openmw.AndroidToolFs
import org.alpha3.launcher.tools.openmw.PrunedFiles
import org.alpha3.launcher.tools.openmw.androidToolPlatform
import org.alpha3.launcher.tools.runModActions
import org.openmw.modDownloader.ModListManager.modList
import org.openmw.modDownloader.NexusInfo.extractionProgressMap
import org.openmw.modDownloader.NexusInfo.extractionProgressPercentMap
import org.openmw.modDownloader.ZipUtils.nativeExtract
import org.alpha3.launcher.ui.view.addCustomLog
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

fun appendToExtractLog(message: String) {
    try {
        val logFile = File("${OpenMWPaths.USER_FILE_STORAGE}/OpenMW/Extraction_$modList.log")
        val timestamp = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.getDefault()))
        val logEntry = "[$timestamp] $message\n"

        logFile.parentFile?.mkdirs()
        logFile.appendText(logEntry)
    } catch (e: Exception) {
        Log.e("ExtractLog", "Failed to write to log file: ${e.message}")
    }
}

object SevenZip {
    private const val TAG = "7-Zip-JBinding-4Android"

    fun isInitialized() {
        val version = getSevenZipVersion()
        Log.i(TAG, "7-zip version: ${version.major}.${version.minor}.${version.build} (${version.version}), ${version.date}${version.copyright}")
        Log.i(TAG, "7-Zip-JBinding version: ${SevenZip.getSevenZipJBindingVersion()}")
        Log.i(TAG, "Native library initialized: ${SevenZip.isInitializedSuccessfully()}")
    }
}

/**
 * Remembers that an archive was fully unpacked (and its list actions run), so a repeated or resumed run does not unpack
 * gigabytes again and the card can show what is really done from the first second. The marker holds the archive's
 * size; a different archive under the same name, or a missing mod folder, makes it stale.
 */
object ExtractMarker {
    private fun marker(info: DownloadInfo) =
        File("${OpenMWPaths.USER_FILE_STORAGE}/OpenMW/.extracted", "${info.fileName}.done".replace('/', '_'))

    private fun archive(info: DownloadInfo) = File("${OpenMWPaths.USER_FILE_STORAGE}/OpenMW/CACHE", "${info.fileName}")

    private fun target(mod: ModDesc, info: DownloadInfo) =
        File("${OpenMWPaths.USER_FILE_STORAGE}/OpenMW/Mods", "${mod.category}/${info.extractTo}")

    fun isDone(mod: ModDesc, info: DownloadInfo): Boolean = try {
        val file = archive(info)
        val m = marker(info)
        // The archive may have been deleted on purpose once it was installed; then the marker and the folder are the proof
        m.isFile && target(mod, info).isDirectory && (!file.exists() || m.readText().trim() == file.length().toString())
    } catch (e: Exception) { false }

    /** Removes the downloaded archive of an installed file when the user asked for that; the marker keeps it counted as done. */
    suspend fun deleteArchiveIfWanted(info: DownloadInfo) {
        try {
            if (GameFilesPreferences.getPreference(GameFilesPreferences.DELETE_ARCHIVES_KEY, false).first()) {
                val file = archive(info)
                if (file.delete()) appendToExtractLog("Deleted archive: ${info.fileName}")
            }
        } catch (e: Exception) {
            Log.w("ExtractMarker", "Could not delete ${info.fileName}: ${e.message}")
        }
    }

    fun write(info: DownloadInfo) {
        try {
            val m = marker(info)
            m.parentFile?.mkdirs()
            m.writeText(archive(info).length().toString())
        } catch (e: Exception) {
            Log.w("ExtractMarker", "Could not write the marker for ${info.fileName}: ${e.message}")
        }
    }
}

/**
 * Queues [info] for unpacking and returns at once, so the caller's download slot is free for the next download while
 * the archive unpacks. Unpacking has its own limit ([ModListManager.extractPermits]).
 */
fun extractModFile(mod: ModDesc, info: DownloadInfo) {
    ModListManager.extractScope.launch {
        ModListManager.extractPermits.withPermit { unpackModFile(mod, info) }
    }
}

private suspend fun unpackModFile(mod: ModDesc, info: DownloadInfo) {
    withContext(Dispatchers.IO) {
        if (ExtractMarker.isDone(mod, info)) {
            appendToExtractLog("Already extracted: ${info.fileName}")
            extractionProgressMap[progressKey(mod, info)] = "${mod.name} Extracted"
            extractionProgressPercentMap[progressKey(mod, info)] = 100f
            ModListManager.finishMod()
            return@withContext
        }
        appendToExtractLog("Extracting: ${info.fileName}")
        var counted = false
        try {
            getSevenZipVersion()
            val extractTo = File(
                "${OpenMWPaths.USER_FILE_STORAGE}/OpenMW/Mods",
                "${mod.category}/${info.extractTo}"
            )

            val destination = File(
                "${OpenMWPaths.USER_FILE_STORAGE}/OpenMW/CACHE",
                "${info.fileName}"
            )

            val randomAccessFile = RandomAccessFile(destination, "r")
            val inStream = RandomAccessFileInStream(randomAccessFile)
            val inArchive = SevenZip.openInArchive(null, inStream) // Auto-detect format
            val detectedFormat = inArchive.archiveFormat
            Log.i("ArchiveDetect", "Detected format: ${detectedFormat.methodName}")
            inArchive.close()
            inStream.close()

            var totalFiles = 0
            var processedFiles = 0
            var extracted = false
            nativeExtract(
                pathFrom = destination.absolutePath,
                pathTo = extractTo.absolutePath,
                archiveFormat = detectedFormat,
                listener = object : ZipUtils.NameProgressListener {
                    override fun onStart() {
                        extractionProgressMap[progressKey(mod, info)] = "Starting extraction for ${mod.name}"
                        extractionProgressPercentMap[progressKey(mod, info)] = 0f
                    }

                    override fun onGetFileNum(num: Int) {
                        totalFiles = num
                        extractionProgressMap[progressKey(mod, info)] = "Extracting ${mod.name} ($num files)"
                    }

                    override fun onProgressUpdate(fileName: String, fileSize: Long) {
                        processedFiles++
                        val progressPercent = if (totalFiles > 0) (processedFiles * 100f) / totalFiles else 0f
                        extractionProgressMap[progressKey(mod, info)] = "Extracting ${mod.name}: $fileName"
                        extractionProgressPercentMap[progressKey(mod, info)] = progressPercent
                    }

                    override fun onError(error: String) {
                        extractionProgressMap[progressKey(mod, info)] = "Extraction error for ${mod.name}: $error"
                        extractionProgressPercentMap[progressKey(mod, info)] = 0f
                        Log.e(
                            "ProcessModList",
                            "Extraction error for ${mod.slug}: $error"
                        )
                    }

                    override fun onCompleted() {
                        extractionProgressMap[progressKey(mod, info)] = "${mod.name} Extracted"
                        extractionProgressPercentMap[progressKey(mod, info)] = 100f
                        extracted = true
                    }
                }
            )
            // The mod list's own fix-ups (remove, rename, copy, tes3cmd clean) come after extraction and before
            // the file counts as finished, because the generated openmw.cfg refers to their results.
            counted = true
            if (extracted) {
                if (runActionsFor(mod, info)) {
                    ExtractMarker.write(info)
                    ExtractMarker.deleteArchiveIfWanted(info)
                } else ModListManager.fileFailed()
            } else {
                // The extractor reported an error and never completed
                extractionProgressMap[progressKey(mod, info)].let {
                    if (it == null || !it.contains("error", ignoreCase = true)) {
                        extractionProgressMap[progressKey(mod, info)] = "Failed to extract ${mod.name}"
                    }
                }
                ModListManager.fileFailed()
            }
            ModListManager.finishMod()
        } catch (e: Exception) {
            Log.e(
                "ProcessModList",
                "Failed to extract file for ${mod.slug}: ${e.message}",
                e
            )
            extractionProgressMap[progressKey(mod, info)] = "Failed to extract ${mod.name}"
            extractionProgressPercentMap[progressKey(mod, info)] = 0f
            ModListManager.fileFailed()
            // A failed file is still finished work, or the remaining count never reaches 0
            if (!counted) ModListManager.finishMod()
        }
    }
}

/** Runs what the mod list says to do with [info] after it was extracted; a failure is reported and stops the rest. */
private fun runActionsFor(mod: ModDesc, info: DownloadInfo): Boolean {
    val actions = info.actionsFor(modList)
    if (actions.isEmpty()) return true
    extractionProgressMap[progressKey(mod, info)] = "Running ${actions.size} actions for ${mod.name}"
    appendToExtractLog("Running ${actions.size} actions for ${mod.name} (${info.fileName})")
    try {
        runModActions(
            AndroidToolFs,
            "${OpenMWPaths.USER_FILE_STORAGE}/OpenMW/Mods/${mod.category}/",
            actions,
            modActionMasterDirs(AndroidToolFs, androidToolPlatform(), OpenMWPaths.USER_OPENMW_CFG),
        ) { appendToExtractLog("  $it") }
        extractionProgressMap[progressKey(mod, info)] = "${mod.name} Extracted"
        return true
    } catch (e: Exception) {
        Log.e("ProcessModList", "Actions failed for ${mod.slug}: ${e.message}", e)
        appendToExtractLog("Actions failed for ${mod.name}: ${e.message}")
        addCustomLog("Actions failed for ${mod.name}: ${e.message}", textSize = 10, textColor = Color.Red)
        extractionProgressMap[progressKey(mod, info)] = "Actions failed for ${mod.name}: ${e.message}"
        return false
    }
}

object ZipUtils {

    private const val TAG = "ZipUtils"

    fun isArchiveFullyExtracted(
        inArchive: IInArchive,
        outputDir: File,
        pathFrom: String,
        pruned: PrunedFiles
    ): Boolean {
        for (i in 0 until inArchive.numberOfItems) {
            val itemPath = inArchive.getProperty(i, PropID.PATH) as String
            val fileSize = inArchive.getProperty(i, PropID.SIZE) as? Long ?: continue
            val isDirectory = inArchive.getProperty(i, PropID.IS_FOLDER) as Boolean

            val outputPath = if (pathFrom.contains("-master", ignoreCase = true)) {
                itemPath.split("/").drop(1).joinToString("/")
            } else {
                itemPath
            }

            val outputFile = File(outputDir, outputPath)

            if (isDirectory) {
                if (!outputFile.exists() || !outputFile.isDirectory) return false
            } else {
                // A file that pruning deleted on purpose is not a reason to extract the archive again.
                if (!outputFile.exists()) {
                    if (outputFile.path in pruned) continue
                    return false
                }
                if (outputFile.length() != fileSize) return false
            }
        }
        return true
    }

    interface NameProgressListener {
        fun onStart()
        fun onGetFileNum(num: Int)
        fun onProgressUpdate(fileName: String, fileSize: Long)
        fun onCompleted()
        fun onError(error: String)
    }

    data class ArchiveFile(
        val path: String,
        val name: String,
        val extension: String,
        val isFolder: Boolean,
        val size: Long,
        val encrypted: Boolean
    )

    fun nativeGetArchiveFileList(file: File): List<ArchiveFile> {
        val fileList = mutableListOf<ArchiveFile>()
        var inStream: RandomAccessFileInStream? = null
        var inArchive: IInArchive? = null

        try {
            val randomAccessFile = RandomAccessFile(file, "r")
            inStream = RandomAccessFileInStream(randomAccessFile)

            // Auto-detect format
            inArchive = SevenZip.openInArchive(null, inStream)
            val format = inArchive.archiveFormat
            Log.i("ArchiveRead", "Detected format: ${format.methodName}")

            val itemCount = inArchive.numberOfItems
            Log.i("ArchiveRead", "Items in archive: $itemCount")

            for (i in 0 until itemCount) {
                val path = inArchive.getStringProperty(i, PropID.PATH) ?: continue
                val name = inArchive.getStringProperty(i, PropID.NAME) ?: ""
                val extension = inArchive.getStringProperty(i, PropID.EXTENSION) ?: ""
                val isFolder = inArchive.getProperty(i, PropID.IS_FOLDER) as? Boolean ?: false
                val size = (inArchive.getProperty(i, PropID.SIZE) as? Long) ?: 0L
                val encrypted = inArchive.getProperty(i, PropID.ENCRYPTED) as? Boolean ?: false

                fileList.add(
                    ArchiveFile(
                        path = path,
                        name = name,
                        extension = extension,
                        isFolder = isFolder,
                        size = size,
                        encrypted = encrypted
                    )
                )
            }
        } catch (e: Exception) {
            Log.e("ArchiveRead", "Error reading archive: ${e.message}")
            e.printStackTrace()
        } finally {
            try {
                inArchive?.close()
            } catch (e: Exception) {
                Log.e("ArchiveRead", "Failed to close archive: ${e.message}")
            }
            try {
                inStream?.close()
            } catch (e: Exception) {
                Log.e("ArchiveRead", "Failed to close stream: ${e.message}")
            }
        }

        return fileList
    }

    fun nativeExtract(
        pathFrom: String,
        pathTo: String,
        archiveFormat: ArchiveFormat = ArchiveFormat.SEVEN_ZIP,
        listener: NameProgressListener
    ) {
        val fileFrom = File(pathFrom)
        val outputDir = File(pathTo)
        Log.i(TAG, "Starting extraction: $pathFrom -> $pathTo")
        listener.onStart()
        try {
            val inStream = RandomAccessFileInStream(RandomAccessFile(fileFrom, "r"))
            val inArchive = SevenZip.openInArchive(archiveFormat, inStream)
            if (isArchiveFullyExtracted(inArchive, outputDir, pathFrom, PrunedFiles.load(AndroidToolFs, PRUNE_MANIFEST))) {
                Log.i(TAG, "Archive already fully extracted. Skipping.")
                listener.onCompleted()
                inArchive.close()
                inStream.close()
                return
            }

            listener.onGetFileNum(inArchive.numberOfItems)

            val extractCallback = object : IArchiveExtractCallback {
                private var currentFile: File? = null
                private var fileOutputStream: OutputStream? = null

                val outputCallback = ISequentialOutStream { data ->
                    // Log.i(TAG, "write size ${data.size} to ${currentFile}")
                    fileOutputStream?.write(data)
                    return@ISequentialOutStream data.size
                }

                override fun getStream(
                    index: Int,
                    extractAskMode: ExtractAskMode
                ): ISequentialOutStream? {
                    val item = inArchive.getProperty(index, PropID.PATH) as String
                    val fileSize = inArchive.getProperty(index, PropID.SIZE) as Long
                    val isDirectory = inArchive.getProperty(index, PropID.IS_FOLDER) as Boolean
                    // Strip top-level folder if pathFrom contains "-master"
                    val outputPath = if (pathFrom.contains("-master", ignoreCase = true)) {
                        item.split("/").drop(1).joinToString("/")
                    } else {
                        item
                    }

                    val outputFile = File(outputDir, outputPath)
                    if (isDirectory) {
                        return null
                    }
                    listener.onProgressUpdate(item, fileSize)
                    outputFile.parentFile?.mkdirs()
                    currentFile = outputFile
                    fileOutputStream = outputFile.outputStream().buffered()
                    return outputCallback
                }

                override fun prepareOperation(extractAskMode: ExtractAskMode) {
                    //Log.i(TAG, "Preparing extraction: $extractAskMode")
                }

                override fun setOperationResult(extractOperationResult: ExtractOperationResult) {
                    /*
                    Log.i(
                        TAG,
                        "Extraction result: $extractOperationResult for ${currentFile?.absolutePath}"
                    )
                     */
                    addCustomLog("Extraction result: $extractOperationResult for ${currentFile?.absolutePath}", textSize = 10, textColor = Color.Green)
                    fileOutputStream?.closeQuietly()
                    if (extractOperationResult != ExtractOperationResult.OK) {
                        listener.onError("Extraction failed: $extractOperationResult")
                    }
                }

                override fun setTotal(total: Long) {
                    Log.i(TAG, "Total extraction size: $total bytes")
                }

                override fun setCompleted(complete: Long) {
                    // Log.i(TAG, "Completed extraction: $complete bytes")
                }
            }

            inArchive.extract(null, false, extractCallback)

            inArchive.close()
            inStream.close()

            listener.onCompleted()

            Log.i(TAG, "Extraction finished successfully.")
        } catch (e: FileNotFoundException) {
            listener.onError(e.message ?: "Unknown error")
            Log.e(TAG, "File not found: ${e.message}")
            e.printStackTrace()
        } catch (e: SevenZipException) {
            listener.onError(e.message ?: "Unknown error")
            Log.e(TAG, "7-Zip error: ${e.message}")
            e.printStackTrace()
        } catch (e: IOException) {
            listener.onError(e.message ?: "Unknown error")
            Log.e(TAG, "IO error: ${e.message}")
            e.printStackTrace()
        }
    }
}

fun getTotalExtractedSize(archiveFile: File): Long {
    var totalSize: Long = 0
    var inStream: RandomAccessFileInStream? = null
    var inArchive: IInArchive? = null

    try {
        val randomAccessFile = RandomAccessFile(archiveFile, "r")
        inStream = RandomAccessFileInStream(randomAccessFile)
        inArchive = SevenZip.openInArchive(null, inStream)

        val itemCount = inArchive.numberOfItems
        for (i in 0 until itemCount) {
            val isDirectory = inArchive.getProperty(i, PropID.IS_FOLDER) as? Boolean ?: false
            if (!isDirectory) {
                val size = (inArchive.getProperty(i, PropID.SIZE) as? Long) ?: 0L
                totalSize += size
            }
        }
    } catch (e: Exception) {
        Log.e("SizeCalculation", "Error calculating total size: ${e.message}")
    } finally {
        try {
            inArchive?.close()
        } catch (e: Exception) {
            Log.e("SizeCalculation", "Failed to close archive: ${e.message}")
        }
        try {
            inStream?.close()
        } catch (e: Exception) {
            Log.e("SizeCalculation", "Failed to close stream: ${e.message}")
        }
    }

    return totalSize
}
