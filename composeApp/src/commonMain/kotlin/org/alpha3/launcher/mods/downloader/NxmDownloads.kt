package org.alpha3.launcher.mods.downloader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.alpha3.launcher.mods.downloader.ModListManager.json
import org.alpha3.launcher.utils.Log
import org.alpha3.launcher.utils.MToast
import kotlin.time.Duration.Companion.minutes

private const val TAG = "NxmDownload"

/** A parsed `nxm://<game>/mods/<modId>/files/<fileId>?key=...&expires=...` link, as sent by Nexus' "Mod Manager Download". */
data class NxmLink(val game: String, val modId: String, val fileId: String, val key: String?, val expires: String?, val userId: String?) {
    companion object {
        fun parse(uri: String?): NxmLink? {
            if (uri == null || !uri.startsWith("nxm://", ignoreCase = true)) return null
            val rest = uri.substring("nxm://".length)
            val path = rest.substringBefore('?').substringBefore('#')
            val query = rest.substringAfter('?', "").substringBefore('#')
            // host is the game; path is mods/<modId>/files/<fileId>
            val host = path.substringBefore('/')
            val segments = path.substringAfter('/', "").split('/').filter { it.isNotEmpty() }
            if (segments.size < 4 || segments[0] != "mods" || segments[2] != "files") return null
            val params = query.split('&').filter { it.isNotEmpty() }.associate { param ->
                param.substringBefore('=') to param.substringAfter('=', "").decodeURLQueryComponent(plusIsSpace = true)
            }
            return NxmLink(
                game = host,
                modId = segments[1],
                fileId = segments[3],
                key = params["key"],
                expires = params["expires"],
                userId = params["user_id"],
            )
        }
    }
}

/**
 * Downloads for free (non-premium) Nexus members. Nexus only hands them a download link after they press
 * "Slow download" / "Mod manager download" on the website, so the file page is opened in the browser (with the
 * member's existing sign-in, see [ModDownloaderHost.openNexusPage]) and the resulting nxm:// link is delivered back to
 * the app, which then asks the API for the link with the one-time key and downloads the file itself.
 */
object NxmDownloads {
    private class Pending(val modId: String, val fileId: String?, val url: String, val title: String)

    /** Files waiting for their turn in the browser, and the one whose page is open now (shown in the downloader). */
    var queuedCount by mutableIntStateOf(0)
        private set
    var currentTitle by mutableStateOf<String?>(null)
        private set

    private const val PAGE_TIMEOUT_MINUTES = 15
    private val timers = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val queue = ArrayDeque<Pending>()
    private var current: Pending? = null
    private val lock = SpinLock()

    // Downloads started from nxm links that are still running. A page only opens while there are fewer than the
    // download thread setting, as for premium downloads: without it every page opens as soon as the last one handed
    // over its link, and hundreds of downloads share the connection and none of them finishes.
    private var activeDownloads = 0
    private fun downloadLimit() = ModListManager.selectedThreadCount.coerceAtLeast(1)

    /**
     * Queues the file's page for the browser; the nxm:// link it produces is picked up by [handle]. Only one page is
     * open at a time: once a download starts the next file's page opens by itself.
     */
    fun open(mod: ModDesc, info: DownloadInfo) {
        val url = "https://www.nexusmods.com/morrowind/mods/${mod.modId}?tab=files&file_id=${info.nexusFileId}&nmm=1"
        lock.withLock {
            if (current?.url == url || queue.any { it.url == url }) return
            queue.addLast(Pending(mod.modId, info.nexusFileId, url, "${mod.name}: ${info.fileName}"))
            queuedCount = queue.size
        }
        openNextIfIdle()
    }

    private fun openNextIfIdle() {
        val next = lock.withLock {
            if (current != null || activeDownloads >= downloadLimit()) return
            queue.removeFirstOrNull()?.also { current = it }.also { queuedCount = queue.size; currentTitle = it?.title }
        } ?: return
        try {
            host?.openNexusPage(next.url) ?: throw IllegalStateException("No browser on this platform")
        } catch (e: Exception) {
            Log.e(TAG, "Could not open ${next.url}", e)
            MToast("No browser available to open Nexus", true)
        }
        // A page nobody pressed Slow download on (it was closed, or the person walked away) must not hold the queue
        timers.launch {
            delay(PAGE_TIMEOUT_MINUTES.minutes)
            if (lock.withLock { current === next }) {
                MToast("Skipped ${next.title}: no download started within $PAGE_TIMEOUT_MINUTES minutes. Press Download on its card to try again.", true)
                skipCurrent()
            }
        }
    }

    /** True while the file's page is open or queued in the browser. */
    fun isPending(modId: String, fileId: String?): Boolean {
        queuedCount; currentTitle // read so a composable that calls this updates when the queue changes
        return lock.withLock {
            (current?.modId == modId && current?.fileId == fileId) || queue.any { it.modId == modId && it.fileId == fileId }
        }
    }

    /** Gives up on the file whose page is open (for example it was closed) and moves on to the next one. */
    fun skipCurrent() {
        val skipped = lock.withLock { current.also { current = null; currentTitle = null } }
        if (skipped != null) ModListManager.finishMod()
        openNextIfIdle()
    }

    /** The download for [modId]/[fileId] has started (or failed): free the page slot and open the next page. */
    private fun finishCurrent(modId: String, fileId: String) {
        lock.withLock {
            if (current?.modId == modId && current?.fileId == fileId) current = null
            else queue.removeAll { it.modId == modId && it.fileId == fileId }
            queuedCount = queue.size
            if (current == null) currentTitle = null
        }
        openNextIfIdle()
    }

    /** Forgets every queued page and the one that is open (the host closes its own page; a browser tab is left to the person). */
    fun cancelAll() {
        lock.withLock {
            queue.clear()
            current = null
            queuedCount = 0
            currentTitle = null
            activeDownloads = 0
        }
        timers.coroutineContext.cancelChildren()
        timers.launch { host?.closeNexusPages() }
    }

    fun openSignIn() {
        try {
            host?.openSignIn("https://users.nexusmods.com/auth/sign_in?redirect_url=https%3A%2F%2Fwww.nexusmods.com%2F")
        } catch (e: Exception) {
            Log.e(TAG, "Could not open the Nexus sign-in", e)
            MToast("No browser available to open Nexus", true)
        }
    }

    /** Handles an nxm:// link: resolves the mod and file from the database, fetches the link and downloads. */
    fun handle(uri: String?) {
        val link = NxmLink.parse(uri) ?: return
        // A link sent by a page that was open when the run was cancelled
        if (ModListManager.cancelled) return
        ModListManager.transferScope.launch {
            // The browser has handed over; bring up the next queued file's page while this one downloads, if a
            // download slot is free (otherwise the page opens when one finishes).
            lock.withLock { activeDownloads++ }
            withContext(Dispatchers.Main) { finishCurrent(link.modId, link.fileId) }
            try {
                download(link)
            } finally {
                lock.withLock { activeDownloads = (activeDownloads - 1).coerceAtLeast(0) }
                withContext(NonCancellable + Dispatchers.Main) { openNextIfIdle() }
            }
        }
    }

    private suspend fun download(link: NxmLink) {
        val mod = ModDatabase.getDatabase().modDao().getModById(link.modId)
        val info = mod?.downloadInfo?.firstOrNull { it.nexusFileId == link.fileId }
            ?: mod?.downloadInfo?.firstOrNull { d -> d.versions.any { it.fileId == link.fileId } }
        if (mod == null || info == null) {
            Log.w(TAG, "No mod ${link.modId} / file ${link.fileId} in the database")
            MToast("Nexus sent a download for a mod this list does not contain", true)
            return
        }
        if (link.key == null || link.expires == null) {
            MToast("The Nexus download link had no key", true)
            ModListManager.finishMod()
            return
        }
        val api = "https://api.nexusmods.com/v1/games/${link.game}/mods/${link.modId}/files/${link.fileId}/download_link.json" +
            "?key=${link.key.encodeURLParameter()}&expires=${link.expires.encodeURLParameter()}"
        var failure: String? = null
        val uriString = try {
            val parsed = json.parseToJsonElement(ModListManager.fetchJsonResponse(api))
            (parsed as? JsonArray)?.firstOrNull()?.jsonObject?.get("URI")?.jsonPrimitive?.contentOrNull
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "download_link failed", e)
            failure = e.cause?.message ?: e.message
            // A key is only valid for the account that pressed the button, so say so when the API key is another account's.
            val apiUser = try {
                json.parseToJsonElement(ModListManager.fetchJsonResponse("https://api.nexusmods.com/v1/users/validate.json"))
                    .jsonObject["user_id"]?.jsonPrimitive?.contentOrNull
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) { null }
            if (apiUser != null && link.userId != null && apiUser != link.userId) {
                failure = "the browser is signed in as Nexus user ${link.userId} but the app's API key belongs to $apiUser; use that account's API key or sign in with it"
            }
            null
        }
        if (uriString == null) {
            MToast("Nexus refused the download: ${failure ?: "no link returned"}", true)
            ModListManager.finishMod()
            return
        }
        downloadModFile(mod, info.copy(directDownload = uriString.replace(" ", "%20")))
    }
}
