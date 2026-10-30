package org.alpha3.launcher.mods.downloader

import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.IOException
import okio.buffer
import okio.use
import org.alpha3.launcher.io.File
import org.alpha3.launcher.mods.downloader.ModListManager.allowExtract
import org.alpha3.launcher.mods.downloader.ModListManager.modList
import org.alpha3.launcher.utils.Log
import org.alpha3.launcher.utils.currentTimeMillis

/** Progress rows are per file: a mod with several archives in the list would otherwise share one row and hide each other. */
fun progressKey(mod: ModDesc, info: DownloadInfo): String =
    if (mod.downloadInfo.count { it.sourceLists.contains(modList) } > 1) "${mod.slug}|${info.fileName}" else mod.slug

fun appendToDownloadLog(message: String) {
    try {
        File("$modStorage/Download_$modList.log").appendText("[${logTimestamp()}] $message\n")
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
        val cacheDir = File(modCache)
        val outputFile = File(cacheDir, "${info.fileName}")
        val partFile = File(cacheDir, "${info.fileName}.part")
        var failure: String? = null
        ModListManager.pausedFiles.remove(key)

        try {
            var downloadUrl = info.directDownload
            // Premium links only live a few hours and a long run asks for them all up front, so ask again for any
            // that have run out (or are about to) just before using them.
            val expires = downloadUrl?.let { extractExpiresValue(it) }
            if (expires != null && expires < currentTimeMillis() / 1000 + 300) {
                downloadUrl = refreshedLink(mod, info) ?: downloadUrl
            }
            var refreshed = false
            if (downloadUrl.isNullOrEmpty()) throw IOException("Download URL is null or empty for ${mod.name}")
            cacheDir.mkdirs()

            var attempt = 0
            while (true) {
                attempt++
                try {
                    downloadOnce(mod, downloadUrl!!, partFile, key)
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
                    failure = e.message ?: e::class.simpleName
                    if (!retryable || attempt >= MAX_DOWNLOAD_ATTEMPTS) break
                    NexusInfo.downloadProgressMap[key] = "Retrying ${mod.name} (attempt ${attempt + 1} of $MAX_DOWNLOAD_ATTEMPTS)"
                    NexusInfo.downloadSpeedMap.remove(key)
                    delay(attempt * 3000L)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = e.message ?: e::class.simpleName
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
        getDirectDownload(mod, info).directDownload
            ?.also { appendToDownloadLog("Refreshed link: ${info.fileName}") }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}

private suspend fun downloadOnce(mod: ModDesc, url: String, partFile: File, key: String) {
    var offset = if (partFile.exists()) partFile.length() else 0L
    ModHttp.client.prepareGet(url) {
        header("User-Agent", "Alpha3/2.71")
        if (offset > 0) header("Range", "bytes=$offset-")
    }.execute { response ->
        val code = response.status.value
        var append = false
        when {
            code == 206 -> append = true
            // The part file already holds the whole file.
            code == 416 && offset > 0 -> return@execute
            code !in 200..299 -> throw HttpStatusException(code, "HTTP error: $code - ${response.status.description}")
            // The server ignored the Range header: start again from the beginning.
            else -> offset = 0
        }

        // A link that leads to a web page instead of a file would otherwise be cached and fail to unpack
        if (response.contentType()?.contentSubtype.equals("html", ignoreCase = true)) {
            throw HttpStatusException(415, "The link leads to a web page, not a file")
        }
        val remaining = response.contentLength() ?: -1L
        val total = if (remaining > 0) offset + remaining else -1L
        var done = offset
        val startTime = currentTimeMillis()
        val startOffset = offset
        var lastUpdate = 0L
        val channel = response.bodyAsChannel()
        val chunk = ByteArray(64 * 1024)

        partFile.sink(append = append).buffer().use { sink ->
            while (true) {
                while (ModListManager.isPaused(key)) {
                    NexusInfo.downloadProgressMap[key] = "Paused: ${mod.name}"
                    NexusInfo.downloadSpeedMap.remove(key)
                    if (ModListManager.cancelled) throw CancellationException("cancelled")
                    delay(300)
                }
                if (ModListManager.cancelled) throw CancellationException("cancelled")
                currentCoroutineContext().ensureActive()
                val read = channel.readAvailable(chunk, 0, chunk.size)
                if (read == -1) break
                if (read == 0) continue
                sink.write(chunk, 0, read)
                done += read
                val now = currentTimeMillis()
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
            val destination = File(modCache, "${info.fileName}")
            if (destination.exists()) {
                // Stored as raw bytes, not as a formatted string
                val totalExtractedSize = getTotalExtractedSize(destination)
                updateDownloadInfoInDatabase(mod.modId, info.copy(extractedSizeBytes = totalExtractedSize))
                Log.d("SizeCalculation", "Updated sizeOnDisk for ${mod.slug}: ${formatSize(totalExtractedSize / 1024)}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SizeCalculation", "Failed to calculate size for ${mod.slug}: ${e.message}")
        }
    }
}

/** Points the DB entry at the file that was really saved (MEGA picks its own file name). */
suspend fun renameDownloadInfoFile(modId: String, oldFileName: String?, newFileName: String) {
    withContext(Dispatchers.IO) {
        try {
            val modDao = ModDatabase.getDatabase().modDao()
            val currentMod = modDao.getModById(modId) ?: return@withContext
            val updatedInfo = currentMod.downloadInfo.map { info ->
                if (info.fileName == oldFileName) info.copy(fileName = newFileName) else info
            }
            modDao.updateMod(currentMod.copy(downloadInfo = updatedInfo))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("DatabaseUpdate", "Failed to rename download file for mod $modId: ${e.message}", e)
        }
    }
}

suspend fun updateDownloadInfoInDatabase(modId: String, updatedInfo: DownloadInfo) {
    withContext(Dispatchers.IO) {
        try {
            val modDao = ModDatabase.getDatabase().modDao()
            val currentMod = modDao.getModById(modId)
            if (currentMod == null) {
                Log.e("DatabaseUpdate", "Mod with ID $modId not found in database")
                return@withContext
            }
            val updatedDownloadInfo = currentMod.downloadInfo.map { info ->
                if (info.fileName == updatedInfo.fileName) updatedInfo else info
            }
            modDao.updateMod(currentMod.copy(downloadInfo = updatedDownloadInfo))
            Log.d("DatabaseUpdate", "Successfully updated sizeOnDisk for mod $modId")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("DatabaseUpdate", "Failed to update sizeOnDisk in database for mod $modId: ${e.message}", e)
        }
    }
}

fun formatSize(kb: Long): String = when {
    kb >= 1024 -> "${kb / 1024} MB"
    else -> "$kb KB"
}
