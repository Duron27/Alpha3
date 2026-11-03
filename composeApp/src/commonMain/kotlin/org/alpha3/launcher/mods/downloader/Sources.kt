package org.alpha3.launcher.mods.downloader

import io.ktor.client.request.head
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.alpha3.launcher.io.File
import org.alpha3.launcher.mods.downloader.ModListManager.fetchJsonResponse
import org.alpha3.launcher.mods.downloader.StatusInfo.processing
import org.alpha3.launcher.utils.Log

// ---- GitLab packages ----

private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

/** The newest package of a mod hosted on GitLab: its download link, file name and size are saved to the database. */
suspend fun fetchGitProjectId(mod: ModDesc, modDao: ModDao): String? = withContext(Dispatchers.IO) {
    try {
        // The project from the GitLab Pages URL
        val pathSegments = mod.url.substringAfter("://").substringAfter('/', "")
            .substringBefore('?').substringBefore('#')
            .split("/").filter { it.isNotBlank() }

        val owner = when {
            mod.url.contains("modding-openmw") -> "modding-openmw"
            pathSegments.size >= 2 -> pathSegments[pathSegments.size - 2]
            else -> pathSegments.firstOrNull() ?: ""
        }
        val slug = if (pathSegments.size >= 2) pathSegments.last() else pathSegments.firstOrNull() ?: ""
        Log.d("GITLAB Base", "Extracted Segments: $pathSegments, Slug: $slug")

        val url = "https://gitlab.com/api/v4/projects/$owner%2F$slug/packages?per_page=100"
        NexusInfo.downloadProgressMap[mod.slug] = "Fetching URL: $url"

        val jsonElement = Json.parseToJsonElement(fetchJsonResponse(url))
        if (jsonElement !is JsonArray || jsonElement.isEmpty()) {
            Log.d("GITLAB_JSON", "No projects found or empty array")
            return@withContext null
        }
        // The latest package by created_at; GitLab's ISO 8601 UTC times ("2026-02-12T18:28:18.334Z") sort as text
        val newestPackage = jsonElement.maxByOrNull { it.jsonObject.string("created_at") ?: "" }?.jsonObject
        val pipeline = newestPackage?.get("pipeline") as? JsonObject
        val projectId = pipeline?.string("project_id")
        val packageId = newestPackage?.string("id")
        val ref = pipeline?.string("ref")

        if (projectId != null && packageId != null) {
            NexusInfo.downloadProgressMap[mod.slug] = "Found project ID: $projectId and package ID: $packageId"

            var fileId: String? = null
            try {
                val packagesUrl =
                    "https://gitlab.com/api/v4/projects/$projectId/packages/$packageId/package_files?id=$projectId&order_by=id&package_id=$packageId&page=1&per_page=20&sort=asc"
                val packages = Json.parseToJsonElement(fetchJsonResponse(packagesUrl)).jsonArray
                val matchingFile = packages.firstOrNull { file ->
                    val fileName = file.jsonObject.string("file_name") ?: ""
                    fileName.startsWith(slug) &&
                            !fileName.contains(".sha") &&
                            !fileName.contains(".md5") &&
                            (fileName.endsWith(".zip") || fileName.endsWith(".7z") || fileName.endsWith(".rar"))
                }
                fileId = matchingFile?.jsonObject?.string("id")
                Log.d("FILE_ID", "Matching file ID: $fileId")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("FETCH_ERROR", "Failed to get package file ID", e)
            }
            NexusInfo.downloadProgressMap[mod.slug] = "Found file ID: $fileId"

            val finalUrl = "https://gitlab.com/$owner/$slug/-/package_files/$fileId/download"
            NexusInfo.downloadProgressMap[mod.slug] = "Latest URL: $finalUrl"

            val downloadInfo = getRemoteFileInfo(finalUrl)
            val originalFileName = downloadInfo.fileName
            val baseFileName = originalFileName?.substringBeforeLast(".")
            val fileExtension = originalFileName?.substringAfterLast(".", missingDelimiterValue = "")
            val newFileName = if (ref != null && !fileExtension.isNullOrEmpty()) "${baseFileName}_${ref}.${fileExtension}" else originalFileName

            val updatedDownloadInfo = mod.downloadInfo.map { info ->
                info.copy(directDownload = finalUrl, fileName = newFileName, fileSize = downloadInfo.fileSize)
            }
            modDao.insertOrUpdateMod(
                mod.copy(gitlabProjectId = projectId, gitlabPackageId = packageId, dlUrl = finalUrl, downloadInfo = updatedDownloadInfo)
            )
        } else {
            Log.d("GITLAB_JSON", "No package found for ${mod.url}")
        }

        NexusInfo.downloadProgressMap[mod.slug] = ""
        projectId
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        NexusInfo.downloadProgressMap[mod.slug] = "Failed to fetch project ID: ${e.message}"
        Log.e("FETCH_ERROR", "Failed to fetch project ID: ${e.message}", e)
        null
    }
}

/** The file name and size (KB) a server gives for [url], from a HEAD request. */
suspend fun getRemoteFileInfo(url: String): DownloadInfo = try {
    val response = ModHttp.client.head(url)
    val filename = extractFilenameFromContentDisposition(response.headers["Content-Disposition"])
        ?: url.substringAfterLast("/").substringBefore("?")
    val sizeKb = response.headers["Content-Length"]?.toLongOrNull()?.let { (it / 1024).toString() }
    DownloadInfo(fileName = filename, fileSize = sizeKb)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.e("GITLAB_FILENAME", "Error getting file info: ${e.message}")
    DownloadInfo(directDownload = url)
}

private fun extractFilenameFromContentDisposition(contentDisposition: String?): String? {
    if (contentDisposition.isNullOrEmpty()) return null
    // filename="example.zip", or filename=example.zip without quotes
    return Regex("filename=\"([^\"]+)\"").find(contentDisposition)?.groupValues?.get(1)
        ?: Regex("filename=([^;]+)").find(contentDisposition)?.groupValues?.get(1)?.trim()
}

// ---- mw.modhistory.com, through the Wayback Machine ----

fun extractLastDigits(url: String): String? {
    val lastSegment = url.substringAfterLast('-')
    return lastSegment.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
}

/** The newest Wayback Machine capture of the modhistory file the archived page links to. */
suspend fun getLatestWaybackUrl(originalArchivedUrl: String): String? = withContext(Dispatchers.IO) {
    try {
        // Step 1: the archived page, and the file URL it mentions
        val html = ModHttp.getText(originalArchivedUrl, userAgent = null)
        val match = Regex("""https?://mw\.modhistory\.com/file\.php\?id=\d+""").find(html)?.value
            ?: return@withContext null

        // Step 2: its newest capture
        val encodedFileUrl = match.replaceFirst("https?://".toRegex(), "").encodeURLParameter()
        val cdxApiUrl = "https://web.archive.org/cdx/search/cdx?url=$encodedFileUrl&output=json&sort=descending&limit=1"
        val captures = Json.parseToJsonElement(ModHttp.getText(cdxApiUrl, userAgent = null)).jsonArray
        if (captures.size < 2) return@withContext null

        val latestCapture = captures[1].jsonArray
        val timestamp = latestCapture[1].jsonPrimitive.content
        val original = latestCapture[2].jsonPrimitive.content
        "https://web.archive.org/web/${timestamp}if_/$original"
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e("WebArchive", "No Wayback capture for $originalArchivedUrl: ${e.message}")
        null
    }
}

