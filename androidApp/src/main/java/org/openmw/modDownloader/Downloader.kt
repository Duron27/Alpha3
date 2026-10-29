package org.openmw.modDownloader

import android.annotation.SuppressLint
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import okio.Buffer
import okio.buffer
import okio.sink
import org.alpha3.launcher.paths.OpenMWPaths
import org.openmw.modDownloader.ModListManager.allowExtract
import org.openmw.modDownloader.ModListManager.applicationContext
import org.openmw.modDownloader.ModListManager.modList
import org.openmw.modDownloader.NexusInfo.extractionProgressMap
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.coroutines.coroutineContext

enum class ModStatus {
    DOWNLOADING,
    EXTRACTING,
    COMPLETED,
    IDLE
}

/** Progress rows are per file: a mod with several archives in the list would otherwise share one row and hide each other. */
fun progressKey(mod: ModDesc, info: DownloadInfo): String =
    if (mod.downloadInfo.count { it.sourceLists.contains(ModListManager.modList) } > 1) "${mod.slug}|${info.fileName}" else mod.slug

fun getModStatus(modId: String): ModStatus {
    val progress = NexusInfo.downloadProgressMap[modId] ?: ""
    val extractionStatus = extractionProgressMap[modId] ?: "Waiting..."

    return when {
        !progress.contains("Downloaded") -> ModStatus.DOWNLOADING
        !extractionStatus.contains("Extracted") && !extractionStatus.contains("Waiting") -> ModStatus.EXTRACTING
        progress.contains("Downloaded") && extractionStatus.contains("Extracted") -> ModStatus.COMPLETED
        else -> ModStatus.IDLE
    }
}

@SuppressLint("SimpleDateFormat")
fun appendToDownloadLog(message: String) {
    try {
        val logFile = File("${OpenMWPaths.USER_FILE_STORAGE}/OpenMW/Download_$modList.log")
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())
        val logEntry = "[$timestamp] $message\n"

        logFile.parentFile?.mkdirs()
        logFile.appendText(logEntry)
    } catch (e: Exception) {
        Log.e("DownloadLog", "Failed to write to log file: ${e.message}")
    }
}

private const val MAX_DOWNLOAD_ATTEMPTS = 4
private const val PROGRESS_INTERVAL_MS = 200L

private class HttpStatusException(val code: Int, message: String) : IOException(message)

/**
 * Downloads [info] into the CACHE folder as `<file>.part` and renames it when complete, so a half-finished file is never
 * mistaken for a finished one. A partial file is continued with an HTTP Range request (after a dropped connection, a
 * pause that outlived the connection, or an app restart), and a failed attempt is retried a few times.
 */
suspend fun downloadModFile(mod: ModDesc, info: DownloadInfo) {
    withContext(Dispatchers.IO) {
        val key = progressKey(mod, info)
        val cacheDir = File("${OpenMWPaths.USER_FILE_STORAGE}/OpenMW/CACHE")
        val outputFile = File(cacheDir, "${info.fileName}")
        val partFile = File(cacheDir, "${info.fileName}.part")
        var failure: String? = null
        ModListManager.pausedFiles.remove(key)

        try {
            var downloadUrl = info.directDownload
            // Premium links only live a few hours and a long run asks for them all up front, so ask again for any
            // that have run out (or are about to) just before using them.
            val expires = downloadUrl?.let { extractExpiresValue(it) }
            if (expires != null && expires < System.currentTimeMillis() / 1000 + 300) {
                downloadUrl = refreshedLink(mod, info) ?: downloadUrl
            }
            var refreshed = false
            if (downloadUrl.isNullOrEmpty()) throw IOException("Download URL is null or empty for ${mod.name}")
            cacheDir.mkdirs()

            var attempt = 0
            while (true) {
                attempt++
                try {
                    downloadOnce(mod, info, downloadUrl!!, partFile, key)
                    if (outputFile.exists()) outputFile.delete()
                    if (!partFile.renameTo(outputFile)) throw IOException("Could not move ${partFile.name} into place")
                    failure = null
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Cancelling drops the connection, which surfaces as an IOException: that is not a failure to retry
                    if (ModListManager.cancelled) throw CancellationException("cancelled")
                    Log.e("ProcessModList", "Download attempt $attempt failed for ${mod.slug}: ${e.message}", e)
                    if (e is HttpStatusException && (e.code == 401 || e.code == 403 || e.code == 410) && !refreshed) {
                        refreshed = true
                        val fresh = refreshedLink(mod, info)
                        if (fresh != null) {
                            downloadUrl = fresh
                            continue
                        }
                    }
                    val retryable = e !is HttpStatusException || e.code >= 500 || e.code == 408 || e.code == 429
                    failure = e.message ?: e.javaClass.simpleName
                    if (!retryable || attempt >= MAX_DOWNLOAD_ATTEMPTS) break
                    NexusInfo.downloadProgressMap[key] = "Retrying ${mod.name} (attempt ${attempt + 1} of $MAX_DOWNLOAD_ATTEMPTS)"
                    NexusInfo.downloadSpeedMap.remove(key)
                    delay(attempt * 3000L)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = e.message ?: e.javaClass.simpleName
        }

        if (failure != null) {
            Log.e("ProcessModList", "Failed to download file for ${mod.slug}: $failure")
            appendToDownloadLog("Failed: ${info.fileName}: $failure")
            withContext(Dispatchers.Main) {
                NexusInfo.downloadProgressMap[key] = "Failed to download ${mod.name}: $failure"
                NexusInfo.downloadSpeedMap.remove(key)
            }
            ModListManager.fileFailed()
            ModListManager.finishMod()
            return@withContext
        }

        withContext(Dispatchers.Main) {
            NexusInfo.downloadProgressMap[key] = "${mod.name} Downloaded"
            NexusInfo.downloadProgressPercentMap[key] = 100
            NexusInfo.downloadSpeedMap.remove(key)
        }

        calculateAndUpdateSizeOnDisk(mod, info)

        // extractModFile counts the file off remainingMods when it finishes
        if (allowExtract) extractModFile(mod, info) else ModListManager.finishMod()
    }
}

/** A new link for a Nexus file whose stored one was refused or has expired; null for other sources or on failure. */
private suspend fun refreshedLink(mod: ModDesc, info: DownloadInfo): String? {
    if (info.nexusFileId.isNullOrEmpty() || info.directDownload?.contains("nexus", ignoreCase = true) != true) return null
    return try {
        getDirectDownload(applicationContext, mod, info).directDownload
            ?.also { appendToDownloadLog("Refreshed link: ${info.fileName}") }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}

private suspend fun downloadOnce(mod: ModDesc, info: DownloadInfo, url: String, partFile: File, key: String) {
    var offset = if (partFile.exists()) partFile.length() else 0L
    val request = Request.Builder()
        .url(url)
        .header("User-Agent", "Alpha3/2.71")
        .apply { if (offset > 0) header("Range", "bytes=$offset-") }
        .build()

    ModListManager.client.newCall(request).execute().use { response ->
        var append = false
        when {
            response.code == 206 -> append = true
            // The part file already holds the whole file.
            response.code == 416 && offset > 0 -> return
            !response.isSuccessful -> throw HttpStatusException(response.code, "HTTP error: ${response.code} - ${response.message}")
            // The server ignored the Range header: start again from the beginning.
            else -> offset = 0
        }

        val body = response.body
        // A link that leads to a web page instead of a file would otherwise be cached and fail to unpack
        if (body.contentType()?.subtype.equals("html", ignoreCase = true)) {
            throw HttpStatusException(415, "The link leads to a web page, not a file")
        }
        val remaining = body.contentLength()
        val total = if (remaining > 0) offset + remaining else -1L
        var done = offset
        val startTime = System.currentTimeMillis()
        val startOffset = offset
        var lastUpdate = 0L
        val source = body.source()
        val chunk = Buffer()

        partFile.sink(append = append).buffer().use { sink ->
            while (true) {
                while (ModListManager.isPaused(key)) {
                    NexusInfo.downloadProgressMap[key] = "Paused: ${mod.name}"
                    NexusInfo.downloadSpeedMap.remove(key)
                    if (ModListManager.cancelled) throw CancellationException("cancelled")
                    delay(300)
                }
                if (ModListManager.cancelled) throw CancellationException("cancelled")
                coroutineContext.ensureActive()
                val read = source.read(chunk, 64 * 1024L)
                if (read == -1L) break
                sink.write(chunk, read)
                done += read
                val now = System.currentTimeMillis()
                if (now - lastUpdate >= PROGRESS_INTERVAL_MS) {
                    lastUpdate = now
                    val seconds = (now - startTime) / 1000.0
                    NexusInfo.downloadProgressMap[key] = "Downloading ${mod.name}"
                    if (total > 0) NexusInfo.downloadProgressPercentMap[key] = ((done * 100) / total).toInt()
                    NexusInfo.downloadBytesMap[key] = done
                    if (total > 0) NexusInfo.downloadTotalMap[key] = total
                    NexusInfo.downloadSpeedMap[key] = if (seconds > 0) ((done - startOffset) / 1024.0 / seconds).toInt() else 0
                }
            }
        }
        if (total > 0 && done < total) throw IOException("Connection closed after $done of $total bytes")
    }
}


suspend fun calculateAndUpdateSizeOnDisk(mod: ModDesc, info: DownloadInfo) {
    withContext(Dispatchers.IO) {
        try {
            val destination = File(
                "${OpenMWPaths.USER_FILE_STORAGE}/OpenMW/CACHE",
                "${info.fileName}"
            )

            if (destination.exists()) {
                val totalExtractedSize = getTotalExtractedSize(destination)

                // Store as raw bytes instead of formatted string
                val updatedInfo = info.copy(extractedSizeBytes = totalExtractedSize)

                // Update the database
                updateDownloadInfoInDatabase(mod.modId, updatedInfo)

                Log.d("SizeCalculation", "Updated sizeOnDisk for ${mod.slug}: ${formatSize(totalExtractedSize / 1024)}")
            }
        } catch (e: Exception) {
            Log.e("SizeCalculation", "Failed to calculate size for ${mod.slug}: ${e.message}")
        }
    }
}

/** Points the DB entry at the file that was really saved (MEGA picks its own file name). */
suspend fun renameDownloadInfoFile(modId: String, oldFileName: String?, newFileName: String) {
    withContext(Dispatchers.IO) {
        try {
            val modDao = ModDatabase.getDatabase(applicationContext).modDao()
            val currentMod = modDao.getModById(modId) ?: return@withContext
            val updatedInfo = currentMod.downloadInfo.map { info ->
                if (info.fileName == oldFileName) info.copy(fileName = newFileName) else info
            }
            modDao.updateMod(currentMod.copy(downloadInfo = updatedInfo))
        } catch (e: Exception) {
            Log.e("DatabaseUpdate", "Failed to rename download file for mod $modId: ${e.message}", e)
        }
    }
}

suspend fun updateDownloadInfoInDatabase(modId: String, updatedInfo: DownloadInfo) {
    withContext(Dispatchers.IO) {
        try {
            val modDao = ModDatabase.getDatabase(applicationContext).modDao()

            // Get the current mod from database
            val currentMod = modDao.getModById(modId)
            if (currentMod == null) {
                Log.e("DatabaseUpdate", "Mod with ID $modId not found in database")
                return@withContext
            }

            // Update the download info
            val updatedDownloadInfo = currentMod.downloadInfo.map { info ->
                if (info.fileName == updatedInfo.fileName) updatedInfo else info
            }

            // Create updated mod object
            val updatedMod = currentMod.copy(downloadInfo = updatedDownloadInfo)

            // Update in database
            modDao.updateMod(updatedMod)

            Log.d("DatabaseUpdate", "Successfully updated sizeOnDisk for mod $modId")

        } catch (e: Exception) {
            Log.e("DatabaseUpdate", "Failed to update sizeOnDisk in database for mod $modId: ${e.message}", e)
        }
    }
}
