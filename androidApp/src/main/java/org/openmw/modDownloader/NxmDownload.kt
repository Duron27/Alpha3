package org.openmw.modDownloader

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.openmw.modDownloader.ModListManager.json

private const val TAG = "NxmDownload"

/** A parsed `nxm://<game>/mods/<modId>/files/<fileId>?key=...&expires=...` link, as sent by Nexus' "Mod Manager Download". */
data class NxmLink(val game: String, val modId: String, val fileId: String, val key: String?, val expires: String?, val userId: String?) {
    companion object {
        fun parse(uri: Uri?): NxmLink? {
            if (uri == null || uri.scheme != "nxm") return null
            val segments = uri.pathSegments
            // host is the game; path is mods/<modId>/files/<fileId>
            if (segments.size < 4 || segments[0] != "mods" || segments[2] != "files") return null
            return NxmLink(
                game = uri.host.orEmpty(),
                modId = segments[1],
                fileId = segments[3],
                key = uri.getQueryParameter("key"),
                expires = uri.getQueryParameter("expires"),
                userId = uri.getQueryParameter("user_id"),
            )
        }
    }
}

/**
 * Downloads for free (non-premium) Nexus members. Nexus only hands them a download link after they press
 * "Slow download" / "Mod manager download" on the website, so the file page is opened in a Custom Tab (the
 * real browser, with the member's existing sign-in) and the resulting nxm:// link is delivered back to the app,
 * which then asks the API for the link with the one-time key and downloads the file itself.
 */
object NxmDownloads {
    private class Pending(val modId: String, val fileId: String?, val url: String, val title: String)

    /** Files waiting for their turn in the browser, and the one whose page is open now (shown in the downloader). */
    var queuedCount by mutableIntStateOf(0)
        private set
    var currentTitle by mutableStateOf<String?>(null)
        private set

    private const val PAGE_TIMEOUT_MINUTES = 15
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val queue = ArrayDeque<Pending>()
    private var current: Pending? = null
    private val lock = Any()

    // Downloads started from nxm links that are still running. A page only opens while there are fewer than the
    // download thread setting, as for premium downloads: without it every page opens as soon as the last one handed
    // over its link, and hundreds of downloads share the connection and none of them finishes.
    private var activeDownloads = 0
    private fun downloadLimit() = ModListManager.selectedThreadCount.coerceAtLeast(1)

    /**
     * Queues the file's page for a Custom Tab; the nxm:// link it produces is picked up by [handle]. Only one tab is
     * open at a time: once a download starts the next file's page opens by itself.
     */
    fun open(context: Context, mod: ModDesc, info: DownloadInfo) {
        val url = "https://www.nexusmods.com/morrowind/mods/${mod.modId}?tab=files&file_id=${info.nexusFileId}&nmm=1"
        synchronized(lock) {
            if (current?.url == url || queue.any { it.url == url }) return
            queue.addLast(Pending(mod.modId, info.nexusFileId, url, "${mod.name}: ${info.fileName}"))
            queuedCount = queue.size
        }
        openNextIfIdle(context.applicationContext)
    }

    private fun openNextIfIdle(context: Context) {
        val next = synchronized(lock) {
            if (current != null || activeDownloads >= downloadLimit()) return
            queue.removeFirstOrNull()?.also { current = it }.also { queuedCount = queue.size; currentTitle = it?.title }
        } ?: return
        val handled = try { org.openmw.plugins.Plugins.nexusPageOpener?.open(next.url) == true } catch (e: Throwable) {
            Log.e(TAG, "Plugin could not open ${next.url}", e)
            false
        }
        if (!handled) launch(context, next.url)
        // A page nobody pressed Slow download on (its tab was closed, or the person walked away) must not hold the queue
        mainHandler.postDelayed({
            if (synchronized(lock) { current === next }) {
                Toast.makeText(context, "Skipped ${next.title}: no download started within ${PAGE_TIMEOUT_MINUTES} minutes. Press Download on its card to try again.", Toast.LENGTH_LONG).show()
                skipCurrent(context)
            }
        }, PAGE_TIMEOUT_MINUTES * 60_000L)
    }

    /** True while the file's page is open or queued in the browser. */
    fun isPending(modId: String, fileId: String?): Boolean {
        queuedCount; currentTitle // read so a composable that calls this updates when the queue changes
        return synchronized(lock) {
            (current?.modId == modId && current?.fileId == fileId) || queue.any { it.modId == modId && it.fileId == fileId }
        }
    }

    /** Gives up on the file whose page is open (for example its tab was closed) and moves on to the next one. */
    fun skipCurrent(context: Context) {
        val skipped = synchronized(lock) { current.also { current = null; currentTitle = null } }
        if (skipped != null) ModListManager.finishMod()
        openNextIfIdle(context.applicationContext)
    }

    /** The download for [modId]/[fileId] has started (or failed): free the tab slot and open the next page. */
    private fun finishCurrent(context: Context, modId: String, fileId: String) {
        synchronized(lock) {
            if (current?.modId == modId && current?.fileId == fileId) current = null
            else queue.removeAll { it.modId == modId && it.fileId == fileId }
            queuedCount = queue.size
            if (current == null) currentTitle = null
        }
        openNextIfIdle(context.applicationContext)
    }

    /** Forgets every queued page and the one that is open (the plugin's page is closed; a browser tab is left to the person). */
    fun cancelAll() {
        synchronized(lock) {
            queue.clear()
            current = null
            queuedCount = 0
            currentTitle = null
            activeDownloads = 0
        }
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.post { org.openmw.plugins.Plugins.closeScreens() }
    }

    fun openSignIn(context: Context) {
        launch(context, "https://users.nexusmods.com/auth/sign_in?redirect_url=https%3A%2F%2Fwww.nexusmods.com%2F")
    }

    private fun launch(context: Context, url: String) {
        try {
            val intent = CustomTabsIntent.Builder().setShowTitle(true).build()
            intent.intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.launchUrl(context, url.toUri())
        } catch (e: Exception) {
            Log.e(TAG, "Could not open $url", e)
            Toast.makeText(context, "No browser available to open Nexus", Toast.LENGTH_LONG).show()
        }
    }

    /** Handles an nxm:// link: resolves the mod and file from the database, fetches the link and downloads. */
    suspend fun handle(context: Context, uri: Uri?) {
        val link = NxmLink.parse(uri) ?: return
        // A link sent by a page that was open when the run was cancelled
        if (ModListManager.cancelled) return
        // The browser has handed over; bring up the next queued file's page while this one downloads, if a download
        // slot is free (otherwise the page opens when one finishes).
        synchronized(lock) { activeDownloads++ }
        withContext(Dispatchers.Main) { finishCurrent(context, link.modId, link.fileId) }
        try {
            download(context, link)
        } finally {
            synchronized(lock) { activeDownloads = (activeDownloads - 1).coerceAtLeast(0) }
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { openNextIfIdle(context.applicationContext) }
        }
    }

    private suspend fun download(context: Context, link: NxmLink) {
        withContext(Dispatchers.IO) {
            val mod = ModDatabase.getDatabase(context).modDao().getModById(link.modId)
            val info = mod?.downloadInfo?.firstOrNull { it.nexusFileId == link.fileId }
                ?: mod?.downloadInfo?.firstOrNull { d -> d.versions.any { it.fileId == link.fileId } }
            if (mod == null || info == null) {
                Log.w(TAG, "No mod ${link.modId} / file ${link.fileId} in the database")
                notify(context, "Nexus sent a download for a mod this list does not contain")
                return@withContext
            }
            if (link.key == null || link.expires == null) {
                notify(context, "The Nexus download link had no key")
                ModListManager.finishMod()
                return@withContext
            }
            val api = "https://api.nexusmods.com/v1/games/${link.game}/mods/${link.modId}/files/${link.fileId}/download_link.json" +
                "?key=${Uri.encode(link.key)}&expires=${Uri.encode(link.expires)}"
            var failure: String? = null
            val uriString = try {
                val parsed = json.parseToJsonElement(ModListManager.fetchJsonResponse(api))
                (parsed as? JsonArray)?.firstOrNull()?.jsonObject?.get("URI")?.jsonPrimitive?.contentOrNull
            } catch (e: Exception) {
                Log.e(TAG, "download_link failed", e)
                failure = e.cause?.message ?: e.message
                // A key is only valid for the account that pressed the button, so say so when the API key is another account's.
                val apiUser = try {
                    json.parseToJsonElement(ModListManager.fetchJsonResponse("https://api.nexusmods.com/v1/users/validate.json"))
                        .jsonObject["user_id"]?.jsonPrimitive?.contentOrNull
                } catch (_: Exception) { null }
                if (apiUser != null && link.userId != null && apiUser != link.userId) {
                    failure = "the browser is signed in as Nexus user ${link.userId} but the app's API key belongs to $apiUser; use that account's API key or sign in with it"
                }
                null
            }
            if (uriString == null) {
                notify(context, "Nexus refused the download: ${failure ?: "no link returned"}")
                ModListManager.finishMod()
                return@withContext
            }
            downloadModFile(mod, info.copy(directDownload = uriString.replace(" ", "%20")))
        }
    }

    private suspend fun notify(context: Context, text: String) = withContext(Dispatchers.Main) {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }
}
