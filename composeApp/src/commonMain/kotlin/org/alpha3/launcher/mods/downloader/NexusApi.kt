package org.alpha3.launcher.mods.downloader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.alpha3.launcher.mods.downloader.ModListManager.fetchJsonResponse
import org.alpha3.launcher.mods.downloader.ModListManager.json
import org.alpha3.launcher.utils.Log
import org.alpha3.launcher.utils.currentTimeMillis

object NexusInfo {
    var isNexusExpanded by mutableStateOf(false)
    val hourlylimit by mutableIntStateOf(2000)
    var hourlyremaining by mutableIntStateOf(0)
    val dailylimit by mutableIntStateOf(20000)
    var dailyremaining by mutableIntStateOf(0)
    /** Epoch milliseconds. */
    var hourlyResetTime by mutableStateOf<Long?>(null)
    var dailyResetTime by mutableStateOf<Long?>(null)
    var hourlyResetInMinutes by mutableLongStateOf(0L)
    var dailyResetInMinutes by mutableLongStateOf(0L)
    val downloadProgressMap = mutableStateMapOf<String, String>()
    val downloadProgressPercentMap = mutableStateMapOf<String, Int>()
    val downloadSpeedMap = mutableStateMapOf<String, Int>()
    /** Bytes received and the file's total size, by progress key. */
    val downloadBytesMap = mutableStateMapOf<String, Long>()
    val downloadTotalMap = mutableStateMapOf<String, Long>()
    val extractionProgressMap = mutableStateMapOf<String, String>()
    val extractionProgressPercentMap = mutableStateMapOf<String, Float>()

    val hourlyPercentage: Float
        get() = if (hourlylimit > 0) hourlyremaining.toFloat() / hourlylimit else 1f

    val dailyPercentage: Float
        get() = if (dailylimit > 0) dailyremaining.toFloat() / dailylimit else 1f

    /** Fewer daily requests left than this turns the card red. The hourly count refills too soon to warn about. */
    const val DAILY_WARNING_LEFT = 500

    // dailyResetTime is only set once a response has told us the real count
    val isNearLimit: Boolean
        get() = dailyResetTime != null && dailyremaining < DAILY_WARNING_LEFT

    val hourlyResetFormatted: String
        get() = formatTimeUntilReset(hourlyResetInMinutes)

    val dailyResetFormatted: String
        get() = formatTimeUntilReset(dailyResetInMinutes)
}

private val resetTimePattern = Regex("""(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2}) ([+-])(\d{2}):?(\d{2})""")

/** The reset time of a Nexus rate-limit header ("2025-07-24 04:00:00 +0000") in epoch milliseconds. */
fun parseResetTime(resetHeader: String): Long? {
    val m = resetTimePattern.matchEntire(resetHeader.trim()) ?: run {
        Log.e("NexusInfo", "Failed to parse reset time: $resetHeader")
        return null
    }
    val v = m.groupValues
    val days = daysFromCivil(v[1].toLong(), v[2].toInt(), v[3].toInt())
    val offsetSeconds = (v[8].toLong() * 3600 + v[9].toLong() * 60) * if (v[7] == "-") -1 else 1
    val seconds = days * 86_400 + v[4].toLong() * 3600 + v[5].toLong() * 60 + v[6].toLong() - offsetSeconds
    return seconds * 1000
}

/** Days from 1970-01-01 to the given proleptic Gregorian date (H. Hinnant's algorithm). */
private fun daysFromCivil(year: Long, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val mp = (month + 9) % 12
    val doy = (153 * mp + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097 + doe - 719_468
}

/** Whole minutes until [resetTime] (epoch milliseconds), 0 once it has passed. */
fun calculateMinutesUntilReset(resetTime: Long): Long = ((resetTime - currentTimeMillis()) / 60_000).coerceAtLeast(0)

/** Minutes as "Xh Ym". */
fun formatTimeUntilReset(minutes: Long): String {
    val hours = minutes / 60
    val remainingMinutes = minutes % 60
    return if (hours > 0) "${hours}h ${remainingMinutes}m" else "${remainingMinutes}m"
}

fun extractExpiresValue(url: String): Long? = queryParameter(url, "expires")?.toLongOrNull()

suspend fun fetchResponseBody(url: String): String? = withContext(Dispatchers.IO) {
    val response = ModHttp.client.get(url)
    if (!response.status.isSuccess()) null else response.bodyAsText()
}

private val imgSrcPattern = Regex("""<img\b[^>]*?\bsrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

suspend fun getUpdatedModGallery(mod: ModDesc): ModDesc {
    val modId = mod.modId
    val pageHtml = fetchResponseBody("https://www.nexusmods.com/morrowind/mods/$modId?tab=images") ?: return mod

    if (mod.galleryUrls.isNotEmpty()) {
        return mod // Already has cached gallery URLs, no need to re-fetch
    }

    return try {
        val galleryImages = imgSrcPattern.findAll(pageHtml)
            .map { it.groupValues[1].replace("&amp;", "&") }
            .filter {
                it.contains("staticdelivery.nexusmods.com") &&
                        !it.contains("staticdelivery.nexusmods.com/images/News/")
            }
            .toList()
        Log.i("getUpdatedModGallery", galleryImages.toString())
        mod.copy(galleryUrls = galleryImages)
    } catch (e: Exception) {
        Log.e("ModProcessing", "Failed to parse gallery images for mod $modId", e)
        mod
    }
}

private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

suspend fun getFileIds(mod: ModDesc): List<DownloadInfo> = withContext(Dispatchers.IO) {
    // Only Nexus mods have file IDs
    if (!mod.url.contains("nexusmods.com/morrowind/mods/")) {
        return@withContext mod.downloadInfo
    }

    val modId = mod.url.substringAfter("mods/").substringBefore("?")
    Log.i("GetFileID", "Started for ${mod.modId}")
    val jsonResponse = try {
        fetchJsonResponse("https://api.nexusmods.com/v1/games/morrowind/mods/${mod.modId}/files.json")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e("GetFileID", "Failed to fetch JSON for mod $modId", e)
        return@withContext mod.downloadInfo
    }

    val jsonElement = try {
        json.parseToJsonElement(jsonResponse)
    } catch (e: Exception) {
        Log.e("GetFileID", "Failed to parse JSON for mod $modId", e)
        return@withContext mod.downloadInfo
    }

    val filesArray = (jsonElement as? JsonObject)?.get("files")?.jsonArray
        ?: (jsonElement as? JsonArray)?.takeIf { it.isNotEmpty() }
        ?: run {
            Log.w("GetFileID", "No files array in JSON for mod $modId")
            return@withContext mod.downloadInfo
        }

    // Every file version
    val allFileVersions = filesArray.mapNotNull { fileElement ->
        val fileObject = fileElement.jsonObject
        DownloadInfo.Version(
            fileName = fileObject.string("name") ?: return@mapNotNull null,
            fileId = fileObject.string("file_id") ?: return@mapNotNull null,
            version = fileObject.string("version"),
            sizeKb = fileObject.string("size_kb"),
            category = fileObject.string("category_name") ?: "N/A"
        ).takeIf { fileObject.string("file_name") != null }
    }

    mod.downloadInfo.map { downloadInfo ->
        // Skip if nexusFileId already exists
        if (!downloadInfo.nexusFileId.isNullOrEmpty()) return@map downloadInfo
        val targetFileName = downloadInfo.fileName ?: return@map downloadInfo

        var selectedFileId: String? = null
        var selectedFileName: String? = null
        var latestTimestamp: Long? = null
        var selectedSizeInKb: String? = null
        val matchingVersions = allFileVersions.filter { it.fileName.equals(targetFileName, ignoreCase = true) }

        // The newest upload whose display name is the list's file name
        for (fileElement in filesArray) {
            val fileObject = fileElement.jsonObject
            val nexusFileName = fileObject.string("name") ?: continue
            val fileName = fileObject.string("file_name") ?: continue
            val timestamp = fileObject.string("uploaded_timestamp")?.toLongOrNull()
            if (nexusFileName.equals(targetFileName, ignoreCase = true) &&
                timestamp != null && (latestTimestamp == null || timestamp > latestTimestamp)
            ) {
                selectedFileId = fileObject.string("file_id")
                selectedFileName = fileName
                latestTimestamp = timestamp
                selectedSizeInKb = fileObject.string("size_kb")
            }
        }

        downloadInfo.copy(
            nexusFileId = selectedFileId,
            fileName = selectedFileName,
            fileSize = selectedSizeInKb,
            versions = matchingVersions
        )
    }
}

fun formatRemainingTime(expiresTimestamp: Long, currentTimestamp: Long): String {
    val secondsRemaining = expiresTimestamp - currentTimestamp
    if (secondsRemaining <= 0) return "Expired"

    val days = secondsRemaining / (24 * 60 * 60)
    val hours = (secondsRemaining % (24 * 60 * 60)) / (60 * 60)
    val minutes = (secondsRemaining % (60 * 60)) / 60

    return buildString {
        if (days > 0) append("$days day${if (days > 1) "s" else ""}")
        if (hours > 0) {
            if (days > 0) append(", ")
            append("$hours hour${if (hours > 1) "s" else ""}")
        }
        if (minutes > 0 && days == 0L) { // Only show minutes if no days
            if (hours > 0) append(", ")
            append("$minutes minute${if (minutes > 1) "s" else ""}")
        }
    }.takeIf { it.isNotEmpty() } ?: "Less than a minute"
}

/** A premium member's download link for [downloadInfo], trying its MAIN files first; saved to the database when new. */
suspend fun getDirectDownload(mod: ModDesc, downloadInfo: DownloadInfo): DownloadInfo = withContext(Dispatchers.IO) {
    var directDownload: String? = null
    var successfulFileId: String? = null

    // Prioritize file IDs: MAIN > current file ID > others
    val prioritizedFileIds = mutableListOf<String>().apply {
        downloadInfo.versions.filter { it.category == "MAIN" && it.fileId.isNotEmpty() }.forEach { add(it.fileId) }
        if (!downloadInfo.nexusFileId.isNullOrEmpty() && !contains(downloadInfo.nexusFileId)) add(downloadInfo.nexusFileId)
        downloadInfo.versions.filter { it.category != "MAIN" && it.fileId.isNotEmpty() && !contains(it.fileId) }.forEach { add(it.fileId) }
    }.distinct().filter { it.isNotEmpty() }

    for (fileId in prioritizedFileIds) {
        try {
            val response = fetchJsonResponse("https://api.nexusmods.com/v1/games/morrowind/mods/${mod.modId}/files/$fileId/download_link.json")
            // A 404 or another error in the response: try the next one
            if (response.contains("\"code\":404") || response.contains("No File found") || response.contains("\"error\"")) continue

            val element = json.parseToJsonElement(response)
            val uri = (element as? JsonArray)?.firstOrNull()?.jsonObject?.string("URI")
            if (uri != null) {
                directDownload = uri.replace(" ", "%20")
                successfulFileId = fileId
                break
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("NexusAPI", "Failed to fetch download link for file ID $fileId: ${e.message}")
        }
    }

    val updatedInfo = if (directDownload != null) {
        downloadInfo.copy(directDownload = directDownload, nexusFileId = successfulFileId ?: downloadInfo.nexusFileId)
    } else {
        downloadInfo.copy(directDownload = null)
    }

    // Persist if changed
    if (directDownload != null && directDownload != downloadInfo.directDownload) {
        val updatedDownloadList = mod.downloadInfo.map { if (it == downloadInfo) updatedInfo else it }
        ModDatabase.getDatabase().modDao().insertOrUpdateMod(mod.copy(downloadInfo = updatedDownloadList))
    }
    updatedInfo
}
