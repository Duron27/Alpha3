package org.openmw.mods

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.openmw.R
import org.alpha3.launcher.mods.downloader.ModListManager
import org.alpha3.launcher.mods.downloader.NexusInfo
import org.alpha3.launcher.mods.downloader.StatusInfo
import org.alpha3.launcher.mods.downloader.finishedFileCount
import org.alpha3.launcher.mods.downloader.notificationProgress
import org.alpha3.launcher.mods.downloader.sizeText
import org.alpha3.launcher.mods.downloader.speedText
import org.alpha3.launcher.paths.OpenMWPathsAndroid

/**
 * Keeps the mod downloader alive while it works: a foreground service (so a phone call, turning the screen off or
 * switching to the browser does not get the process killed), a partial wake lock (the CPU keeps unpacking archives)
 * and a Wi-Fi lock (the connection is not dropped to save power). The work itself still runs in the app's coroutines;
 * the service only holds the process up and shows progress in a notification. It follows [StatusInfo.processing].
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            )
        } catch (e: Exception) {
            Log.e(TAG, "Could not go to the foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CANCEL) {
            ModListManager.cancelRun()
            stopSelf()
            return START_NOT_STICKY
        }
        acquireLocks()
        scope.launch {
            while (isActive && StatusInfo.processing) {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
                delay(2_000)
            }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        super.onDestroy()
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "alpha3:modDownloader")
                .apply { setReferenceCounted(false); acquire(WAKE_LOCK_MAX_MS) }
        }
        if (wifiLock == null) {
            @Suppress("DEPRECATION")
            wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "alpha3:modDownloader")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Mod downloads", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Progress of mod list downloads and installs" }
            )
        }
    }

    private fun buildNotification(): Notification {
        val total = ModListManager.runTotal.coerceAtLeast(1)
        val finished = finishedFileCount().coerceIn(0, total)
        val speed = NexusInfo.downloadSpeedMap.values.sum()
        val progress = notificationProgress()
        val text = buildString {
            append("$finished of $total files")
            if (progress.downloading.isNotEmpty()) append(" · ${progress.downloading.size} downloading")
            if (speed > 0) append(" · ").append(speedText(speed))
            if (ModListManager.failedFiles > 0) append(" · ${ModListManager.failedFiles} failed")
        }
        // Expanded: every file being downloaded on its own line (Android shows the first few), under the totals
        val style = NotificationCompat.InboxStyle().setSummaryText(buildString {
            append("$finished of $total files done")
            if (progress.receivedBytes > 0) append(" · ${sizeText(progress.receivedBytes)} received")
            if (progress.unpacking > 0) append(" · ${progress.unpacking} unpacking")
        })
        progress.downloading.take(MAX_LINES).forEach { style.addLine(it) }
        if (progress.downloading.size > MAX_LINES) style.addLine("and ${progress.downloading.size - MAX_LINES} more")
        if (progress.downloading.isEmpty()) style.addLine(text)
        val open = PendingIntent.getActivity(
            this, 0, (packageManager.getLaunchIntentForPackage(packageName) ?: Intent()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Installing ${ModListManager.modList}")
            .setContentText(text)
            .setStyle(style)
            .setProgress(total, finished, ModListManager.runTotal == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(
                0, "Cancel",
                PendingIntent.getService(
                    this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()
    }

    companion object {
        private const val TAG = "DownloadService"
        private const val CHANNEL_ID = "mod_downloads"
        private const val NOTIFICATION_ID = 4107
        private const val MAX_LINES = 6
        private const val ACTION_CANCEL = "org.openmw.modDownloader.CANCEL"
        private const val WAKE_LOCK_MAX_MS = 12L * 60 * 60 * 1000

        /**
         * Starts the service when the downloader starts working. It stops itself when [StatusInfo.processing] goes
         * false; stopping it from here could land before it has called startForeground, which Android treats as a crash.
         * Starting needs the app to be in the foreground.
         */
        fun sync(running: Boolean) {
            if (!running) return
            val context = OpenMWPathsAndroid.appContext
            try {
                ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "Could not start the download service", e)
            }
        }
    }
}
