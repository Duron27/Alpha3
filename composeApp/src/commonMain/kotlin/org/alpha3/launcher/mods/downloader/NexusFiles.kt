package org.alpha3.launcher.mods.downloader

import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.http.encodeURLParameter
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.IOException
import okio.buffer
import okio.use
import org.alpha3.launcher.io.File
import org.alpha3.launcher.utils.GameFilesPreferences
import kotlin.time.Duration.Companion.minutes

/**
 * One Nexus file fetched outside a mod list's run (a shader pack, a Wabbajack list's own file): the newest file of a
 * mod, its download link (straight away for a premium member, through the file's page and its nxm:// link for a free
 * one), and the download.
 */
object NexusFiles {
    private const val API = "https://api.nexusmods.com/v1/games/morrowind/mods"

    data class FileInfo(val fileId: String, val name: String, val fileName: String, val version: String, val sizeKb: Long)

    /** The mod downloader's API key, loaded from the settings when its screen has not been opened yet. */
    suspend fun requireApiKey() {
        if (ModListManager.apiKey.isBlank()) {
            val saved = GameFilesPreferences.getPreference(GameFilesPreferences.NEXUS_API_KEY, "").first()
            withContext(Dispatchers.Main) { ModListManager.apiKey = saved }
        }
        if (ModListManager.apiKey.isBlank()) throw IOException("Add your Nexus API key in the mod downloader first")
    }

    /** The newest file of [modId] in [category] whose name passes [accept]. */
    suspend fun newest(modId: String, category: String = "MAIN", accept: (FileInfo) -> Boolean = { true }): FileInfo {
        requireApiKey()
        val element = ModListManager.json.parseToJsonElement(ModListManager.fetchJsonResponse("$API/$modId/files.json"))
        val files = (element as? JsonObject)?.get("files")?.jsonArray ?: throw IOException("Nexus listed no files for mod $modId")
        fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull
        return files.map { it.jsonObject }
            .filter { it.str("category_name") == category }
            .sortedByDescending { it.str("uploaded_timestamp")?.toLongOrNull() ?: 0L }
            .map { o ->
                FileInfo(
                    fileId = o.str("file_id").orEmpty(),
                    name = o.str("name").orEmpty(),
                    fileName = o.str("file_name").orEmpty(),
                    version = o.str("version").orEmpty(),
                    sizeKb = o.str("size_kb")?.toLongOrNull() ?: 0L,
                )
            }
            .firstOrNull { it.fileId.isNotEmpty() && accept(it) }
            ?: throw IOException("Nexus lists no matching file for mod $modId")
    }

    /**
     * The download link of [fileId]. A free member gets the file's page opened and [onWaiting] says so; the link
     * waits for Slow download there, up to 15 minutes.
     */
    suspend fun downloadLink(modId: String, fileId: String, onWaiting: (String) -> Unit): String {
        requireApiKey()
        val premium = GameFilesPreferences.getNullablePreference(GameFilesPreferences.IS_NEXUS_PREMIUM).first() == true
        val query = if (premium) "" else {
            onWaiting("On the Nexus page: press Slow download")
            val link = NxmDownloads.awaitLink(modId, 15.minutes) {
                host?.openNexusPage("https://www.nexusmods.com/morrowind/mods/$modId?tab=files&file_id=$fileId&nmm=1")
                    ?: throw IOException("No browser on this platform")
            }
            val key = link.key ?: throw IOException("The Nexus download link had no key")
            val expires = link.expires ?: throw IOException("The Nexus download link had no key")
            "?key=${key.encodeURLParameter()}&expires=${expires.encodeURLParameter()}"
        }
        val response = ModListManager.json.parseToJsonElement(
            ModListManager.fetchJsonResponse("$API/$modId/files/$fileId/download_link.json$query"))
        return (response as? JsonArray)?.firstOrNull()?.jsonObject?.get("URI")?.jsonPrimitive?.contentOrNull
            ?.replace(" ", "%20") ?: throw IOException("Nexus gave no download link")
    }

    /** Downloads [url] to [file], reporting bytes done and the total (-1 when unknown). Throws on failure. */
    suspend fun download(url: String, file: File, onProgress: (Long, Long) -> Unit) {
        File(file.path.substringBeforeLast('/')).mkdirs()
        ModHttp.client.prepareGet(url) { header("User-Agent", "Alpha3/2.71") }.execute { response ->
            if (response.status.value !in 200..299) throw IOException("HTTP ${response.status.value} for the download")
            val total = response.contentLength() ?: -1L
            val channel = response.bodyAsChannel()
            val chunk = ByteArray(64 * 1024)
            var done = 0L
            file.sink().buffer().use { sink ->
                while (true) {
                    val read = channel.readAvailable(chunk, 0, chunk.size)
                    if (read == -1) break
                    if (read == 0) continue
                    sink.write(chunk, 0, read)
                    done += read
                    onProgress(done, total)
                }
            }
            if (total > 0 && done < total) throw IOException("The connection closed after $done of $total bytes")
        }
    }
}
