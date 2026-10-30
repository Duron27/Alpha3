package org.alpha3.launcher.mods.downloader

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.alpha3.launcher.io.File
import org.alpha3.launcher.mods.downloader.ModListManager.modList
import org.alpha3.launcher.mods.downloader.NexusInfo.extractionProgressMap
import org.alpha3.launcher.mods.downloader.NexusInfo.extractionProgressPercentMap
import org.alpha3.launcher.paths.OpenMWPaths
import org.alpha3.launcher.tools.PRUNE_MANIFEST
import org.alpha3.launcher.tools.modActionMasterDirs
import org.alpha3.launcher.tools.openmw.PrunedFiles
import org.alpha3.launcher.tools.runModActions
import org.alpha3.launcher.ui.view.addCustomLog
import org.alpha3.launcher.utils.GameFilesPreferences
import org.alpha3.launcher.utils.Log
import org.alpha3.launcher.utils.Platform

fun appendToExtractLog(message: String) {
    try {
        File("$modStorage/Extraction_$modList.log").appendText("[${logTimestamp()}] $message\n")
    } catch (e: Exception) {
        Log.e("ExtractLog", "Failed to write to log file: ${e.message}")
    }
}

/** Where the archives of [mod] unpack to. */
private fun modFolder(mod: ModDesc, info: DownloadInfo) = File("$modStorage/Mods", "${mod.category}/${info.extractTo}")

/**
 * Remembers that an archive was fully unpacked (and its list actions run), so a repeated or resumed run does not unpack
 * gigabytes again and the card can show what is really done from the first second. The marker holds the archive's
 * size; a different archive under the same name, or a missing mod folder, makes it stale.
 */
object ExtractMarker {
    private fun marker(info: DownloadInfo) = File("$modStorage/.extracted", "${info.fileName}.done".replace('/', '_'))

    private fun archive(info: DownloadInfo) = File(modCache, "${info.fileName}")

    fun isDone(mod: ModDesc, info: DownloadInfo): Boolean = try {
        val file = archive(info)
        val m = marker(info)
        // The archive may have been deleted on purpose once it was installed; then the marker and the folder are the proof
        m.isFile && modFolder(mod, info).isDirectory && (!file.exists() || m.readText().trim() == file.length().toString())
    } catch (e: Exception) { false }

    /** Removes the downloaded archive of an installed file when the user asked for that; the marker keeps it counted as done. */
    suspend fun deleteArchiveIfWanted(info: DownloadInfo) {
        try {
            if (GameFilesPreferences.getPreference(GameFilesPreferences.DELETE_ARCHIVES_KEY, false).first()) {
                if (archive(info).delete()) appendToExtractLog("Deleted archive: ${info.fileName}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("ExtractMarker", "Could not delete ${info.fileName}: ${e.message}")
        }
    }

    fun write(info: DownloadInfo) {
        try {
            marker(info).writeText(archive(info).length().toString())
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
        val key = progressKey(mod, info)
        if (ExtractMarker.isDone(mod, info)) {
            appendToExtractLog("Already extracted: ${info.fileName}")
            extractionProgressMap[key] = "${mod.name} Extracted"
            extractionProgressPercentMap[key] = 100f
            ModListManager.finishMod()
            return@withContext
        }
        appendToExtractLog("Extracting: ${info.fileName}")
        var counted = false
        try {
            val archive = File(modCache, "${info.fileName}")
            Log.i("ArchiveDetect", "Detected format: ${Archives.formatName(archive.path)}")

            var totalFiles = 0
            var processedFiles = 0
            var extracted = false
            ZipUtils.nativeExtract(
                pathFrom = archive.path,
                pathTo = modFolder(mod, info).path,
                listener = object : ZipUtils.NameProgressListener {
                    override fun onStart() {
                        extractionProgressMap[key] = "Starting extraction for ${mod.name}"
                        extractionProgressPercentMap[key] = 0f
                    }

                    override fun onGetFileNum(num: Int) {
                        totalFiles = num
                        extractionProgressMap[key] = "Extracting ${mod.name} ($num files)"
                    }

                    override fun onProgressUpdate(fileName: String, fileSize: Long) {
                        processedFiles++
                        extractionProgressMap[key] = "Extracting ${mod.name}: $fileName"
                        extractionProgressPercentMap[key] = if (totalFiles > 0) (processedFiles * 100f) / totalFiles else 0f
                    }

                    override fun onError(error: String) {
                        extractionProgressMap[key] = "Extraction error for ${mod.name}: $error"
                        extractionProgressPercentMap[key] = 0f
                        Log.e("ProcessModList", "Extraction error for ${mod.slug}: $error")
                    }

                    override fun onCompleted() {
                        extractionProgressMap[key] = "${mod.name} Extracted"
                        extractionProgressPercentMap[key] = 100f
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
                if (extractionProgressMap[key]?.contains("error", ignoreCase = true) != true) {
                    extractionProgressMap[key] = "Failed to extract ${mod.name}"
                }
                ModListManager.fileFailed()
            }
            ModListManager.finishMod()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("ProcessModList", "Failed to extract file for ${mod.slug}: ${e.message}", e)
            extractionProgressMap[key] = "Failed to extract ${mod.name}"
            extractionProgressPercentMap[key] = 0f
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
    val key = progressKey(mod, info)
    extractionProgressMap[key] = "Running ${actions.size} actions for ${mod.name}"
    appendToExtractLog("Running ${actions.size} actions for ${mod.name} (${info.fileName})")
    try {
        val fs = Platform.features.toolFs ?: throw IllegalStateException("This platform has no tool storage")
        val platform = Platform.features.toolPlatform ?: throw IllegalStateException("This platform has no tool storage")
        runModActions(
            fs,
            "$modStorage/Mods/${mod.category}/",
            actions,
            modActionMasterDirs(fs, platform, OpenMWPaths.USER_OPENMW_CFG),
        ) { appendToExtractLog("  $it") }
        extractionProgressMap[key] = "${mod.name} Extracted"
        return true
    } catch (e: Exception) {
        Log.e("ProcessModList", "Actions failed for ${mod.slug}: ${e.message}", e)
        appendToExtractLog("Actions failed for ${mod.name}: ${e.message}")
        addCustomLog("Actions failed for ${mod.name}: ${e.message}", textSize = 10, textColor = Color.Red)
        extractionProgressMap[key] = "Actions failed for ${mod.name}: ${e.message}"
        return false
    }
}

object ZipUtils {
    private const val TAG = "ZipUtils"

    /** Where an item of [archivePath] goes below the mod folder: GitLab's "-master" archives lose their top folder. */
    private fun outputPath(archivePath: String, itemPath: String): String =
        if (archivePath.contains("-master", ignoreCase = true)) itemPath.split("/").drop(1).joinToString("/") else itemPath

    /** An item whose path climbs out of the mod folder (or is absolute) is not unpacked. */
    private fun isUnsafe(path: String) = path.startsWith("/") || path.split('/', '\\').any { it == ".." }

    fun isArchiveFullyExtracted(items: List<ArchiveFile>, outputDir: File, pathFrom: String, pruned: PrunedFiles): Boolean {
        for (item in items) {
            val outputFile = File(outputDir, outputPath(pathFrom, item.path))
            if (item.isFolder) {
                if (!outputFile.isDirectory) return false
            } else {
                // A file that pruning deleted on purpose is not a reason to extract the archive again.
                if (!outputFile.exists()) {
                    if (outputFile.path in pruned) continue
                    return false
                }
                if (outputFile.length() != item.size) return false
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

    /** Every item of [file], or nothing when it cannot be read. */
    fun nativeGetArchiveFileList(file: File): List<ArchiveFile> = try {
        Archives.list(file.path).also { Log.i("ArchiveRead", "Items in archive: ${it.size}") }
    } catch (e: Exception) {
        Log.e("ArchiveRead", "Error reading archive: ${e.message}")
        emptyList()
    }

    fun nativeExtract(pathFrom: String, pathTo: String, listener: NameProgressListener) {
        val outputDir = File(pathTo)
        Log.i(TAG, "Starting extraction: $pathFrom -> $pathTo")
        listener.onStart()
        try {
            val items = Archives.list(pathFrom)
            val fs = Platform.features.toolFs
            val pruned = if (fs != null) PrunedFiles.load(fs, PRUNE_MANIFEST) else null
            if (pruned != null && isArchiveFullyExtracted(items, outputDir, pathFrom, pruned)) {
                Log.i(TAG, "Archive already fully extracted. Skipping.")
                listener.onCompleted()
                return
            }
            listener.onGetFileNum(items.size)
            Archives.extract(
                pathFrom,
                target = { item ->
                    val path = outputPath(pathFrom, item.path)
                    if (item.isFolder || isUnsafe(path)) null
                    else File(outputDir, path).also { it.parentFile?.mkdirs() }.path
                },
                onFile = { item -> listener.onProgressUpdate(item.path, item.size) },
                onError = { error ->
                    addCustomLog("Extraction result: $error", textSize = 10, textColor = Color.Red)
                    listener.onError("Extraction failed: $error")
                },
            )
            listener.onCompleted()
            Log.i(TAG, "Extraction finished successfully.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            listener.onError(e.message ?: "Unknown error")
            Log.e(TAG, "Extraction error: ${e.message}")
        }
    }
}

/** The total size of the files in [archiveFile] once unpacked; 0 when it cannot be read. */
fun getTotalExtractedSize(archiveFile: File): Long = try {
    Archives.list(archiveFile.path).filter { !it.isFolder }.sumOf { it.size }
} catch (e: Exception) {
    Log.e("SizeCalculation", "Error calculating total size: ${e.message}")
    0L
}

/** True when every file of the archive is already in [targetFolder] with its size. */
fun shouldSkipExtraction(archiveFiles: List<ArchiveFile>, targetFolder: File): Boolean {
    for (archiveFile in archiveFiles) {
        if (archiveFile.isFolder) continue
        val targetFile = File(targetFolder, archiveFile.path)
        if (!targetFile.exists()) {
            Log.i("ExtractionCheck", "Missing file: ${archiveFile.path}")
            return false
        }
        if (targetFile.length() != archiveFile.size) {
            Log.i("ExtractionCheck", "Size mismatch: ${archiveFile.path}")
            return false
        }
    }
    Log.i("ExtractionCheck", "All files match — skipping extraction")
    return true
}
