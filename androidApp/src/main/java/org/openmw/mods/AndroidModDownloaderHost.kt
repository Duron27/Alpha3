package org.openmw.mods

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import org.alpha3.launcher.mods.downloader.ModDownloaderHost
import org.alpha3.launcher.paths.OpenMWPaths
import org.alpha3.launcher.paths.OpenMWPathsAndroid
import org.openmw.plugins.Plugins
import java.io.File

/**
 * The mod downloader on Android: a free member's Nexus page opens in a plugin's screen when one offers to, otherwise in
 * a Custom Tab (the nxm:// link comes back through MainActivity), and [DownloadService] keeps the process alive while a
 * list installs.
 */
object AndroidModDownloaderHost : ModDownloaderHost {
    private const val TAG = "ModDownloaderHost"
    private val context: Context get() = OpenMWPathsAndroid.appContext

    override fun openNexusPage(url: String) {
        val handled = try { Plugins.nexusPageOpener?.open(url) == true } catch (e: Throwable) {
            Log.e(TAG, "Plugin could not open $url", e)
            false
        }
        if (!handled) launch(url)
    }

    override fun openSignIn(url: String) = launch(url)

    override fun closeNexusPages() = Plugins.closeScreens()

    override fun setRunning(running: Boolean) = DownloadService.sync(running)

    override fun openLink(url: String, label: String, fileName: String?) {
        try {
            val name = if (label == "Direct Download" && !fileName.isNullOrEmpty()) fileName else "mod_${System.currentTimeMillis()}.zip"
            val cacheDir = File(OpenMWPaths.CACHE_DIR).apply { mkdirs() }
            val request = DownloadManager.Request(url.toUri())
                .setTitle("$label Download")
                .setDescription("Downloading $label")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationUri(Uri.fromFile(File(cacheDir, name)))
                .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
            (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to start download: ${e.message}", Toast.LENGTH_SHORT).show()
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun launch(url: String) {
        try {
            val intent = CustomTabsIntent.Builder().setShowTitle(true).build()
            intent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.launchUrl(context, url.toUri())
        } catch (e: Exception) {
            Log.e(TAG, "Could not open $url", e)
            Toast.makeText(context, "No browser available to open Nexus", Toast.LENGTH_LONG).show()
        }
    }
}
