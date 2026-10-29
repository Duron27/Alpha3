@file:OptIn(ExperimentalCoroutinesApi::class)
@file:Suppress("DEPRECATION")

package org.openmw.modDownloader

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.edit
import androidx.room.withTransaction
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.alpha3.launcher.paths.OpenMWPaths
import org.openmw.modDownloader.ModListManager.allowExtract
import org.openmw.modDownloader.ModListManager.brokenMods
import org.openmw.modDownloader.ModListManager.modList
import org.openmw.modDownloader.ModListManager.onlySync
import org.openmw.modDownloader.ModListManager.remainingMods
import org.openmw.modDownloader.ModListManager.selectedThreadCount
import org.openmw.modDownloader.ModListManager.validate
import org.openmw.modDownloader.StatusInfo.processing
import org.alpha3.launcher.ui.view.addCustomLog
import org.alpha3.launcher.utils.GameFilesPreferences
import org.alpha3.launcher.utils.dataStore
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.absoluteValue
import kotlin.time.Duration.Companion.milliseconds

/** How long the downloaded mod lists are reused before the next refresh. */
private const val LIST_REFRESH_INTERVAL_MS = 6L * 60 * 60 * 1000

/** A file that has no download link must show up as failed, not silently count as done. */
private fun failWithoutLink(mod: ModDesc, info: DownloadInfo) {
    val key = progressKey(mod, info)
    NexusInfo.downloadProgressMap[key] = "Failed to download ${mod.name}: no download link"
    NexusInfo.downloadSpeedMap.remove(key)
    appendToDownloadLog("Failed: ${info.fileName}: no download link")
    ModListManager.fileFailed()
    addCustomLog("No download link for ${mod.slug} (${info.fileName})", textSize = 10, textColor = Color.Red)
    ModListManager.finishMod()
}

@OptIn(DelicateCoroutinesApi::class)
fun processModList(context: Context, isPremium: Boolean): Flow<List<ModDesc>> = flow {
    // isValidated is required, but also isvalidated = isPremium in this case.
    val modDao = ModDatabase.getDatabase(context).modDao()
    val brokenModCount = AtomicInteger(0)
    remainingMods = 0
    ModListManager.paused = false
    ModListManager.cancelled = false
    ModListManager.pausedFiles.clear()
    ModListManager.cancelPendingCompletion()

    // Clear previous progress data (all of it, or stale "Extracted" states leak into the new run)
    NexusInfo.downloadProgressMap.clear()
    NexusInfo.downloadProgressPercentMap.clear()
    NexusInfo.downloadSpeedMap.clear()
    NexusInfo.downloadBytesMap.clear()
    NexusInfo.downloadTotalMap.clear()
    NexusInfo.extractionProgressMap.clear()
    NexusInfo.extractionProgressPercentMap.clear()

    File("${OpenMWPaths.USER_FILE_STORAGE}/OpenMW/Download_$modList.log").delete()
    File("${OpenMWPaths.USER_FILE_STORAGE}/OpenMW/Extraction_$modList.log").delete()

    // SQL narrows it to this list, so the rest of the table isn't decoded; the exact check stays as a safety net
    val mods = modDao.getModsByModList(modList).filter { mod ->
        mod.onLists.contains(modList)
    }

    Log.d("ProcessModList", "Found ${mods.size} mods for list: $modList")
    addCustomLog("Found ${mods.size} mods for list: $modList", textSize = 10, textColor = Color.Cyan)

    // One unit of work per mod when only syncing, otherwise one per file to download and extract.
    remainingMods = if (onlySync) mods.size
    else mods.sumOf { mod -> mod.downloadInfo.count { it.sourceLists.contains(modList) } }
    StatusInfo.activeMods = mods.size
    ModListManager.runTotal = remainingMods
    ModListManager.failedFiles = 0
    ModListManager.modNames.clear()
    mods.forEach { ModListManager.modNames[it.slug] = it.name }
    ModListManager.fileSizeKb.clear()
    for (mod in mods) for (info in mod.downloadInfo.filter { it.sourceLists.contains(modList) }) {
        ModListManager.fileSizeKb[progressKey(mod, info)] = info.fileSize?.toLongOrNull() ?: 0L
    }

    // What an earlier run already finished is shown as finished right away, so the counts are true from the start
    for (mod in mods) for (info in mod.downloadInfo.filter { it.sourceLists.contains(modList) }) {
        if (info.fileName.isNullOrEmpty()) continue
        val cached = File("${OpenMWPaths.USER_FILE_STORAGE}/OpenMW/CACHE", "${info.fileName}")
        if (!cached.isFile) continue
        val key = progressKey(mod, info)
        NexusInfo.downloadProgressMap[key] = "${mod.name} Downloaded"
        NexusInfo.downloadProgressPercentMap[key] = 100
        if (ExtractMarker.isDone(mod, info)) {
            NexusInfo.extractionProgressMap[key] = "${mod.name} Extracted"
            NexusInfo.extractionProgressPercentMap[key] = 100f
        }
    }

    // Syncing hits web APIs (Nexus rate-limits hard), downloading hits the disk and network.
    // Separate limits keep a slow sync from starving downloads, and neither fires hundreds of requests at once.
    val syncPermits = Semaphore(ModListManager.syncThreadCount.coerceIn(1, ModListManager.cpuThreadCount))
    val downloadPermits = Semaphore(selectedThreadCount.coerceAtLeast(1))

    coroutineScope {
        // Only refreshes the premium flag in the background; it must not hold up the sync.
        if (ModListManager.apiKey.isNotBlank()) {
            launch { withTimeoutOrNull(15_000.milliseconds) { validate(context) } }
        }
        // Biggest archives first: they take longest, so starting them late would leave one or two slow downloads at the end.
        fun largestKb(mod: ModDesc) = mod.downloadInfo.filter { it.sourceLists.contains(modList) }
            .maxOfOrNull { it.fileSize?.toLongOrNull() ?: 0L } ?: 0L
        mods.sortedByDescending(::largestKb).mapIndexed { index, mod ->
            async {
                Log.i(
                    "ProcessModList",
                    "Processing mod ${index + 1}/${mods.size} (${mod.slug}) with ${mod.downloadInfo.size} download links and Premium statue of $isPremium"
                )
                addCustomLog("Processing mod ${index + 1}/${mods.size} (${mod.slug}) [Premium: $isPremium]", textSize = 10, textColor = Color.Cyan)
                syncPermits.withPermit {
                when {
                    mod.url.contains("nexusmods.com") -> {
                        val currentUnixTimestamp = Instant.now().epochSecond

                        // Step 1: Get updated DownloadInfo list with nexusFileId values
                        val hasMissingFileIds = mod.downloadInfo.any { it.nexusFileId == null }

                        val updatedInfoList = if (hasMissingFileIds) {
                            try {
                                Log.i("ProcessModList", "Fetching file IDs for ${mod.slug}")
                                addCustomLog("Nexus: Fetching file IDs for ${mod.slug}", textSize = 10, textColor = Color.Cyan)
                                getFileIds(mod)
                            } catch (e: Exception) {
                                Log.i("ProcessModList", "Failed to fetch file IDs for ${mod.slug}: ${e.message}")
                                addCustomLog("Failed to fetch file IDs for ${mod.slug}: ${e.message}", textSize = 10, textColor = Color.Red)
                                mod.downloadInfo
                            }
                        } else {
                            Log.i("ProcessModList", "File IDs for ${mod.slug} already set!")
                            addCustomLog("File IDs for ${mod.slug} already set!", textSize = 10, textColor = Color.Green)
                            mod.downloadInfo
                        }

                        // Step 2: Process each DownloadInfo for direct download links or non-premium actions
                        val finalInfoList = updatedInfoList.map { info ->
                            var updatedInfo = info

                            // Refresh direct download link if expired
                            val expiresTimestamp = updatedInfo.directDownload?.let { extractExpiresValue(it) } ?: Long.MAX_VALUE
                            val isExpired = updatedInfo.directDownload.isNullOrEmpty() || expiresTimestamp < currentUnixTimestamp

                            if (isPremium) {
                                if (isExpired && updatedInfo.nexusFileId != null) {
                                    try {
                                        updatedInfo = getDirectDownload(context, mod, updatedInfo)
                                    } catch (e: Exception) {
                                        Log.i("ProcessModList", "Failed to fetch direct download for ${mod.slug}: ${e.message}")
                                        addCustomLog("Failed to fetch direct download for ${mod.slug}: ${e.message}", textSize = 10, textColor = Color.Red)
                                    }
                                }
                            }
                            updatedInfo
                        }

                        // Update the mod with the new DownloadInfo list
                        val updatedMod = mod.copy(downloadInfo = finalInfoList)
                        modDao.updateMod(updatedMod)
                    }

                    mod.url.startsWith("/") -> {
                        Log.d(
                            "ProcessModList",
                            "Processing local file for ${mod.slug}: ${mod.url}"
                        )
                        addCustomLog("Processing local file: ${mod.slug}", textSize = 10, textColor = Color.Cyan)
                        try {
                            val directDownloadUrl = "https://modding-openmw.com${mod.url}"
                            val fileName =
                                mod.url.substringAfterLast('/').takeIf { it.isNotBlank() }
                                    ?: "${mod.slug}.zip"

                            // The list may already give a real link (e.g. a GitLab archive) with the mod's page as its url;
                            // only entries without one are files hosted on the list's own site.
                            val updatedDownloadInfo = mod.downloadInfo.map { info ->
                                if (!info.directDownload.isNullOrEmpty()) info
                                else info.copy(directDownload = directDownloadUrl, fileName = fileName)
                            }

                            val updatedMod = mod.copy(
                                downloadInfo = updatedDownloadInfo,
                                dlUrl = if (mod.downloadInfo.any { !it.directDownload.isNullOrEmpty() }) mod.dlUrl else directDownloadUrl
                            )

                            modDao.insertOrUpdateMod(updatedMod)
                        } catch (e: Exception) {
                            Log.i(
                                "ProcessModList",
                                "Failed to fetch a direct download link: ${e.message}"
                            )
                            addCustomLog("Error: Failed to fetch local download link: ${e.message}", textSize = 10, textColor = Color.Red)
                        }
                    }
                    mod.url.contains("web.archive.org") -> {
                        val lastDigits = extractLastDigits(mod.url)

                        val archivedUrl = "https://web.archive.org/cdx/search/cdx?url=mw.modhistory.com/file.php?id=$lastDigits&output=json&sort=descending&limit=1"
                        val updatedUrl = getLatestWaybackUrl(archivedUrl)
                        val cleanedDlUrl = mod.dlUrl?.replace("/files/", "")
                        if (updatedUrl != null) {
                            println("Download link: $updatedUrl")
                            addCustomLog("Archive.org Link: $updatedUrl", textSize = 10, textColor = Color.Cyan)
                            val updatedDownloadInfo = mod.downloadInfo.map { info ->
                                info.copy(
                                    directDownload = updatedUrl,
                                    fileName = cleanedDlUrl
                                )
                            }

                            val updatedMod = mod.copy(
                                downloadInfo = updatedDownloadInfo
                            )

                            modDao.insertOrUpdateMod(updatedMod)
                        } else {
                            println("Mod not found or error occurred")
                            addCustomLog("Archive.org error: Mod not found for ${mod.slug}", textSize = 10, textColor = Color.Red)
                        }
                    }

                    mod.url.contains("gitlab") -> {
                        mod.downloadInfo.forEach { info ->
                            if (info.directDownload.isNullOrEmpty()) {
                                fetchGitProjectId(mod, modDao)
                                addCustomLog("Fetching GitLab ID for ${mod.slug}", textSize = 10, textColor = Color.Cyan)
                            } else {
                                Log.d(
                                    "fetchGitLabProjectId",
                                    "Download Link and Filename already exists for ${mod.slug}, skipping"
                                )
                                addCustomLog("GitLab: Skipping ${mod.slug} (Exists)", textSize = 10, textColor = Color.Green)
                            }
                        }
                    }
                    mod.url.startsWith("https://baturin.org") -> {
                        Log.d(
                            "ProcessModList",
                            "Processing local file for ${mod.slug}: ${mod.url}"
                        )
                        addCustomLog("Processing local file for ${mod.slug}: ${mod.url}", textSize = 10, textColor = Color.White)
                        try {
                            // Extract the filename from the URL fragment (part after #)
                            val fragment = mod.url.substringAfterLast("#")
                            val fileName = if (fragment.isNotBlank() && fragment != mod.url) {
                                "${fragment.replace("-", "_")}.zip"
                            } else {
                                // Fallback to slug if no fragment found
                                "${mod.slug.replace("-", "_")}.zip"
                            }

                            val directDownloadUrl = "https://baturin.org/misc/morrowind-mods/$fileName"

                            val updatedDownloadInfo = mod.downloadInfo.map { info ->
                                info.copy(
                                    directDownload = directDownloadUrl,
                                    fileName = fileName
                                )
                            }

                            val updatedMod = mod.copy(
                                downloadInfo = updatedDownloadInfo,
                                dlUrl = directDownloadUrl
                            )

                            modDao.insertOrUpdateMod(updatedMod)
                        } catch (e: Exception) {
                            Log.i(
                                "ProcessModList",
                                "Failed to fetch a direct download link: ${e.message}"
                            )
                            addCustomLog("Error: Failed to fetch local download link: ${e.message}", textSize = 10, textColor = Color.Red)
                        }
                    }
                    else  -> {
                        brokenModCount.incrementAndGet()
                    }
                }
                }
                brokenMods = "Total Broken mods: ${brokenModCount.get()}"

                // The sync step above saved the links and file IDs it found to the database, not into this mod,
                // so downloading from `mod` would find no link for a mod that was synced in this very run.
                val freshMod = modDao.getModById(mod.modId) ?: mod

                if (onlySync) {
                    ModListManager.finishMod()
                } else for (info in freshMod.downloadInfo.filter { it.sourceLists.contains(modList) }) {
                    val mod = freshMod
                    // A file that is already in the cache only needs unpacking, so it must not wait for a download slot
                    val cachedAlready = File("${OpenMWPaths.USER_FILE_STORAGE}/OpenMW/CACHE", "${info.fileName}").exists() ||
                        ExtractMarker.isDone(freshMod, info)
                    val slot: suspend (suspend () -> Unit) -> Unit =
                        if (cachedAlready) { block -> block() } else { block -> downloadPermits.withPermit { block() } }
                    slot {
                        appendToDownloadLog("Downloading: ${info.fileName}")
                        if (!onlySync) {
                            try {
                                val destination = File(
                                    "${OpenMWPaths.USER_FILE_STORAGE}/OpenMW/CACHE",
                                    "${info.fileName}"
                                )
                                if (destination.exists() || ExtractMarker.isDone(mod, info)) {
                                    Log.d(
                                        "DownloadSkip",
                                        "File already exists for ${mod.slug}, skipping download"
                                    )
                                    addCustomLog("Cache Hit: ${info.fileName}", textSize = 10, textColor = Color.Green)

                                    NexusInfo.downloadProgressMap[progressKey(mod, info)] =
                                        "${mod.name} Downloaded"
                                    NexusInfo.downloadProgressPercentMap[progressKey(mod, info)] = 100
                                    NexusInfo.downloadSpeedMap.remove(progressKey(mod, info))

                                    // extractModFile counts the file off remainingMods when it finishes
                                    if (allowExtract) extractModFile(mod, info) else ModListManager.finishMod()
                                } else {
                                    when {
                                        mod.url.contains("nexusmods.com") -> {
                                            if (!isPremium) {
                                                withContext(Dispatchers.Main) { NxmDownloads.open(context, mod, info) }
                                            } else {
                                                if (!info.directDownload.isNullOrEmpty()) {
                                                    downloadModFile(mod, info)
                                                } else {
                                                    failWithoutLink(mod, info)
                                                }
                                            }
                                        }

                                        mod.url.contains("mega.nz") -> {
                                            val cacheDir = File("${OpenMWPaths.USER_FILE_STORAGE}/OpenMW/CACHE")
                                            val file = withContext(Dispatchers.IO) {
                                                MegaDownloader().download(mod.url, cacheDir)
                                            }
                                            if (file != null) {
                                                // MEGA names the file itself, and the extractor opens CACHE/<fileName>,
                                                // so point the DB entry at the file that was really saved.
                                                val savedInfo = info.copy(fileName = file.name)
                                                renameDownloadInfoFile(mod.modId, info.fileName, file.name)

                                                NexusInfo.downloadProgressMap[progressKey(mod, info)] = "${mod.name} Downloaded"
                                                NexusInfo.downloadProgressPercentMap[progressKey(mod, info)] = 100
                                                NexusInfo.downloadSpeedMap.remove(progressKey(mod, info))
                                                calculateAndUpdateSizeOnDisk(mod, savedInfo)

                                                // extractModFile counts the mod off remainingMods when it completes
                                                if (allowExtract) extractModFile(mod, savedInfo) else ModListManager.finishMod()
                                            } else {
                                                ModListManager.finishMod()
                                            }
                                        }

                                        else -> {
                                            if (!info.directDownload.isNullOrEmpty()) {
                                                downloadModFile(mod, info)
                                            } else {
                                                failWithoutLink(mod, info)
                                            }
                                        }
                                    }
                                }
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.e(
                                    "ProcessModList",
                                    "Failed to download file for ${mod.slug}: ${e.message}",
                                    e
                                )
                                addCustomLog("Download Error (${mod.slug}): ${e.message}", textSize = 10, textColor = Color.Red)
                                NexusInfo.downloadProgressMap[progressKey(mod, info)] =
                                    "Failed to download ${mod.name}"
                                NexusInfo.downloadProgressPercentMap[progressKey(mod, info)] = 0
                                ModListManager.fileFailed()
                                ModListManager.finishMod()
                            } finally {
                                NexusInfo.downloadSpeedMap.remove(progressKey(mod, info))
                            }
                        }
                    }
                }
            }
        }.awaitAll()
    }
    Log.d("ProcessModList", "Total updated mods: ${mods.size}")
    addCustomLog("Processing complete. Total updated: ${mods.size}", textSize = 10, textColor = Color.Green)
    // Free Nexus members download after this flow ends (they confirm each file in the browser), and extraction
    // runs on after the downloads, so the run is only complete once every file has been counted off.
    ModListManager.whenAllFinished {
        StatusInfo.installResult = if (!onlySync) installModListProfile(modList, StatusInfo.pendingConfigPath) else null
        StatusInfo.pendingConfigPath = null
        processing = false
        StatusInfo.showProcessCompleteDialog = true
    }
    emit(mods)
}

object ModListManager {
    // Mod Manager
    var modList by mutableStateOf("i-heart-vanilla")
    var apiKey by mutableStateOf("")
    var allowExtract by mutableStateOf(true)
    var onlySync by mutableStateOf(false)
    var brokenMods by mutableStateOf("")

    val cpuThreadCount = Runtime.getRuntime().availableProcessors()
    var selectedThreadCount by mutableIntStateOf(cpuThreadCount)

    // How many mods are synced at once. Defaults to the core count; the download options can lower it.
    var syncThreadCount by mutableIntStateOf(cpuThreadCount)
    private var _applicationContext: Context? = null
    val applicationContext: Context
        get() = _applicationContext ?: throw IllegalStateException("ApplicationContext not initialized")
    val applicationContextOrNull: Context? get() = _applicationContext

    // Initialize the context when your app starts
    fun init(context: Context) {
        _applicationContext = context.applicationContext
    }

    var remainingMods by mutableIntStateOf(0)

    /** Archives unpack here, outside the download slots, a few at a time (unpacking is CPU and disk heavy). */
    val extractScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val extractPermits = Semaphore(3)

    /** Set from the downloader screen; running downloads hold their place until it is cleared. */
    var paused by mutableStateOf(false)

    /** Set by [cancelRun]: downloads that see it stop instead of retrying. Cleared when the next run starts. */
    @Volatile var cancelled = false

    /** The coroutine that runs the current mod list, so [cancelRun] can stop it. */
    @Volatile var runJob: Job? = null

    /**
     * Stops the run: running downloads (their connections are dropped, partial files stay for a later resume), archives
     * waiting to unpack, the Nexus pages waiting for a free member's confirmation, and the foreground service. Nothing
     * is installed or configured for a cancelled run.
     */
    fun cancelRun() {
        cancelled = true
        runJob?.cancel()
        runJob = null
        client.dispatcher.cancelAll()
        extractScope.coroutineContext.cancelChildren()
        NxmDownloads.cancelAll()
        cancelPendingCompletion()
        remainingMods = 0
        for (key in NexusInfo.downloadProgressMap.keys.toList()) {
            val text = NexusInfo.downloadProgressMap[key] ?: continue
            if (text.startsWith("Downloading") || text.startsWith("Paused") || text.startsWith("Retrying")) {
                NexusInfo.downloadProgressMap[key] = text.replaceFirst(Regex("^\\w+"), "Cancelled")
            }
        }
        NexusInfo.downloadSpeedMap.clear()
        StatusInfo.pendingConfigPath = null
        appendToDownloadLog("Cancelled")
        StatusInfo.processing = false
    }

    /** Files in the current run, and how many of them failed (no link, download or unpack error). */
    var runTotal by mutableIntStateOf(0)
    var failedFiles by mutableIntStateOf(0)
    fun fileFailed() { synchronized(remainingLock) { failedFiles++ } }

    /** Display names by mod slug, for the progress card. */
    val modNames = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Size in KB of each file of the run as the mod list states it, by progress key; 0 or missing when unknown. */
    val fileSizeKb = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Files paused one by one from their card, by progress key (see [progressKey]). */
    val pausedFiles = mutableStateMapOf<String, Boolean>()

    fun isPaused(key: String) = paused || pausedFiles[key] == true
    fun togglePaused(key: String) { if (pausedFiles[key] == true) pausedFiles.remove(key) else pausedFiles[key] = true }

    // Many coroutines finish at once (downloads, extractions); a plain `remainingMods--` loses counts.
    private val remainingLock = Any()
    private var onAllFinished: (() -> Unit)? = null

    fun cancelPendingCompletion() { synchronized(remainingLock) { onAllFinished = null } }

    fun finishMod() {
        val done = synchronized(remainingLock) {
            if (remainingMods > 0) remainingMods--
            if (remainingMods == 0) onAllFinished.also { onAllFinished = null } else null
        }
        done?.invoke()
    }

    /** Runs [action] once no file is left to download or extract, immediately if that is already so. */
    fun whenAllFinished(action: () -> Unit) {
        val runNow = synchronized(remainingLock) {
            if (remainingMods <= 0) true else { onAllFinished = action; false }
        }
        if (runNow) action()
    }

    // Starts with the defaults and is replaced when the real lists arrive; loading never blocks the caller.
    val availableLists = mutableStateListOf("i-heart-vanilla", "total-overhaul")

    private fun httpGetText(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", "Alpha3/2.71").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            return response.body.string()
        }
    }

    suspend fun fetchAvailableLists(): MutableList<String> = withContext(Dispatchers.IO) {
        try {
            val jsonString = httpGetText("https://modding-openmw.com/lists/json")
            Json.decodeFromString<List<String>>(jsonString)
                .filter { !it.endsWith("-wip") }
                .filter { !it.contains("one-day-morrowind-modernization") }
                .toMutableList()
        } catch (e: Exception) {
            println("Failed to fetch/parse mod lists: ${e.message}")
            addCustomLog("Failed to fetch/parse mod lists: ${e.message}", textSize = 10, textColor = Color.Cyan)
            mutableListOf(
                "i-heart-vanilla",
                "total-overhaul"
            )
        }
    }

    val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    // Centralized client with high-concurrency limits and optimized connection pooling
    /*
    val client = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .dispatcher(Dispatcher().apply {
            maxRequests = 128
            maxRequestsPerHost = 16
        })
        .connectionPool(ConnectionPool(20, 5, TimeUnit.MINUTES))
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES)
        .retryOnConnectionFailure(true)
        .addInterceptor(RetryInterceptor(3))
        .build()        
     */

    // Replace all individual client creations with this:
    val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(RetryInterceptor(3))
        .build()

    /** Retries network failures, 5xx and 429 with a growing wait. Other errors (404, 403...) go straight back to the caller. */
    class RetryInterceptor(private val maxRetries: Int) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            var attempt = 0
            while (true) {
                attempt++
                val response = try {
                    chain.proceed(request)
                } catch (e: IOException) {
                    if (attempt >= maxRetries) throw e
                    Thread.sleep(1000L * attempt)
                    continue
                }
                val retriable = response.code == 429 || response.code >= 500
                if (!retriable || attempt >= maxRetries) return response

                // Honor Retry-After when the server sends one, but never wait long
                val waitSeconds = response.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 10) ?: attempt.toLong()
                response.close()
                Thread.sleep(waitSeconds * 1000)
            }
        }
    }

    private val loaderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listsLoad = loaderScope.async {
        val fetched = fetchAvailableLists()
        availableLists.clear()
        availableLists.addAll(fetched)
    }

    suspend fun validate(context: Context) {
        try {
            val jsonString =
                fetchJsonResponse("https://api.nexusmods.com/v1/users/validate.json")

            val json = Json.parseToJsonElement(jsonString).jsonObject
            val premiumStatus = json["is_premium"]?.jsonPrimitive?.booleanOrNull ?: false

            // Save to DataStore
            dataStore.edit { preferences ->
                preferences[GameFilesPreferences.IS_NEXUS_PREMIUM] = premiumStatus
            }
            Log.i("Validate", "Raw JSON: $jsonString")
            addCustomLog("Validate: Raw JSON: $jsonString", textSize = 10, textColor = Color.Yellow)
        } catch (e: Exception) {
            Log.e("VALIDATE_ERROR", "Validation failed: ${e.message}")
            addCustomLog("Validate: Validation failed: ${e.message}", textSize = 10, textColor = Color.Yellow)
        }
    }

    suspend fun downloadAndParseModList(context: Context): List<ModDesc> = withContext(Dispatchers.Default) {
        processing = true
        val allFilteredMods = mutableListOf<ModDesc>()
        val blockedModNames = listOf(
            "Buy and Install Morrowind",
            "Install OpenMW",
            "Set your Site Settings",
            "Folder Deploy Script",
            "How to Install Mods",
            "S3LightFixes",
            "MOMW Tools Pack"
        )

        val modDao = ModDatabase.getDatabase(applicationContext).modDao()

        // The lists change rarely, and this runs every time the screen opens, so reuse the saved copy
        // when they were fetched recently instead of downloading dozens of JSON files again.
        listsLoad.await()
        val lastSync = GameFilesPreferences.getPreference(GameFilesPreferences.MOD_LISTS_SYNCED_AT, 0L).first()
        val listsAreFresh = System.currentTimeMillis() - lastSync < LIST_REFRESH_INTERVAL_MS && modDao.getModCount() > 0
        if (listsAreFresh) {
            addCustomLog("Mod lists synced recently, using the saved copy", textSize = 10, textColor = Color.Cyan)
        }

        // Fetch and parse the online lists a few at a time, then write them to the DB in order.
        // "poti" comes from a local import, it doesn't exist on the server.
        val fetchPermits = Semaphore(4)
        var fetchFailed = false
        val fetchedLists = if (listsAreFresh) emptyList() else coroutineScope {
            availableLists.filter { it != "poti" }.map { listName ->
                async(Dispatchers.IO) {
                    fetchPermits.withPermit {
                        try {
                            val body = httpGetText("https://modding-openmw.com/lists/$listName/json")
                            listName to json.decodeFromString<List<ModDesc>>(body)
                        } catch (e: Exception) {
                            fetchFailed = true
                            Log.e("ModListManager", "Critical error loading mod list $listName: ${e.message}")
                            addCustomLog("Critical error loading $listName: ${e.message}", textSize = 10, textColor = Color.Red)
                            null
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }

        // Every list is written in ONE transaction at the end. Room only tells the observers (the three screens
        // that collect getAllModsFlow) once the transaction commits, instead of once per list, so they don't
        // each reload and re-decode the whole table dozens of times during a sync.
        val pendingLists = mutableListOf<Pair<String, List<ModDesc>>>()

        // Process online mod lists
        for ((currentListName, modListData) in fetchedLists) {
            try {

                val filteredModList = modListData
                    .filter { it.name !in blockedModNames }
                    .map { modDesc ->
                        val finalModId = when {
                            modDesc.url.contains("nexusmods.com/morrowind/mods/") -> {
                                modDesc.url
                                    .substringAfter("mods/")
                                    .substringBefore("?")
                                    .trim()
                                    .toIntOrNull()
                            }
                            else -> modDesc.slug.hashCode()
                                .absoluteValue
                                .toString()
                                .padStart(6, '0')
                                .takeLast(6)
                        }

                        val downloadInfoWithSources = modDesc.downloadInfo.map { info ->
                            info.forList(currentListName)
                        }

                        modDesc.copy(
                            modId = finalModId.toString(),
                            downloadInfo = downloadInfoWithSources,
                            onLists = listOf(currentListName)
                        )
                    }

                pendingLists += currentListName to filteredModList
                allFilteredMods.addAll(filteredModList)
            } catch (e: Exception) {
                Log.e("ModListManager", "Critical error loading mod list $modList: ${e.message}")
                addCustomLog("Critical error loading $modList: ${e.message}", textSize = 10, textColor = Color.Red)
            }
        }

        // Process local JSON files - FIXED PATH
        val directory = File("${OpenMWPaths.USER_FILE_STORAGE}/OpenMW")

        // Debug logging
        Log.d("ModListManager", "Looking for custom lists in: ${directory.absolutePath}")
        addCustomLog("Looking for custom lists in: ${directory.absolutePath}", textSize = 10, textColor = Color.Cyan)
        Log.d("ModListManager", "Directory exists: ${directory.exists()}")
        addCustomLog("Directory exists: ${directory.exists()}", textSize = 10, textColor = Color.Cyan)
        Log.d("ModListManager", "Is directory: ${directory.isDirectory}")
        addCustomLog("Is directory: ${directory.isDirectory}", textSize = 10, textColor = Color.Cyan)

        if (directory.exists() && directory.isDirectory) {
            val jsonFiles = directory.listFiles { _, name -> name.endsWith(".json") }
            Log.d("ModListManager", "Found ${jsonFiles?.size ?: 0} JSON files")
            addCustomLog("Found ${jsonFiles?.size ?: 0} JSON files", textSize = 10, textColor = Color.Cyan)

            jsonFiles?.forEach { file ->
                Log.d("ModListManager", "Processing file: ${file.name}")
                addCustomLog("Processing file: ${file.name}", textSize = 10, textColor = Color.Cyan)
                try {
                    val jsonText = file.readText()
                    Log.d("ModListManager", "File content length: ${jsonText.length}")

                    val modListData = json.decodeFromString<List<ModDesc>>(jsonText)
                    Log.d("ModListManager", "Parsed ${modListData.size} mods from ${file.name}")

                    val listNameFromFileName = file.nameWithoutExtension
                    val filteredModList = modListData
                        .filter { it.name !in blockedModNames }
                        .map { modDesc ->
                            val finalModId = when {
                                modDesc.url.contains("nexusmods.com/morrowind/mods/") -> {
                                    modDesc.url
                                        .substringAfter("mods/")
                                        .substringBefore("?")
                                        .trim()
                                        .toIntOrNull()
                                }
                                else -> (100000..999999).random()
                            }
                            
                            val downloadInfoWithSources = modDesc.downloadInfo.map { info ->
                                info.forList(listNameFromFileName)
                            }

                            modDesc.copy(
                                modId = finalModId.toString(),
                                downloadInfo = downloadInfoWithSources,
                                onLists = listOf(listNameFromFileName)
                            )
                        }

                    Log.d("ModListManager", "Filtered to ${filteredModList.size} mods")
                    pendingLists += listNameFromFileName to filteredModList
                    allFilteredMods.addAll(filteredModList)
                    Log.d("ModListManager", "Successfully processed ${file.name}")
                    addCustomLog("Processed local file: ${file.name}", textSize = 10, textColor = Color.Green)

                } catch (e: Exception) {
                    Log.e("ModListManager", "Error processing local file ${file.name}: ${e.message}")
                    addCustomLog("Error processing local file ${file.name}: ${e.message}", textSize = 10, textColor = Color.Red)
                    e.printStackTrace()
                }
            }
        } else {
            Log.w("ModListManager", "Directory does not exist or is not a directory: ${directory.absolutePath}")
        }

        ModDatabase.getDatabase(applicationContext).withTransaction {
            for ((listName, listMods) in pendingLists) {
                try {
                    modDao.insertOrAppendMods(listMods, listName, context)
                    addCustomLog("Merged $listName (${listMods.size} mods)", textSize = 10, textColor = Color.Cyan)
                } catch (e: Exception) {
                    Log.w("ModListManager", "Partial failure merging $listName: ${e.message}")
                    addCustomLog("Partial failure merging $listName: ${e.message}", textSize = 10, textColor = Color.Yellow)
                }
            }
        }
        if (!listsAreFresh && !fetchFailed) {
            GameFilesPreferences.setPreference(GameFilesPreferences.MOD_LISTS_SYNCED_AT, System.currentTimeMillis())
        }
        processing = false
        return@withContext allFilteredMods
    }

    /** Set while the Nexus hourly request limit holds the sync back, for the progress card. */
    var rateLimitWait by mutableStateOf<String?>(null)

    /** Set when Nexus actually answers 429; the hourly count shown by the headers alone does not stop requests. */
    private var limitedUntil: java.time.ZonedDateTime? = null

    private suspend fun awaitNexusRateLimit() {
        while (true) {
            val until = limitedUntil ?: break
            val ms = java.time.Duration.between(java.time.ZonedDateTime.now(until.zone), until).toMillis()
            if (ms <= 0) { limitedUntil = null; break }
            rateLimitWait = "Nexus asked us to slow down. Continuing in ${(ms / 60_000) + 1} min."
            delay(minOf(ms + 3_000, 20_000).milliseconds)
        }
        rateLimitWait = null
    }

    suspend fun fetchJsonResponse(url: String, retried: Boolean = false): String {
        if (url.contains("api.nexusmods.com")) awaitNexusRateLimit()
        try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("apikey", apiKey)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .build()

            val response = withTimeout(90_000.milliseconds) { // 90 seconds timeout
                client.newCall(request).execute().also { response ->
                    NexusInfo.apply {
                        hourlyremaining = response.header("x-rl-hourly-remaining")?.toIntOrNull() ?: hourlyremaining
                        dailyremaining = response.header("x-rl-daily-remaining")?.toIntOrNull() ?: dailyremaining
                        val hourlyResetHeader = response.header("x-rl-hourly-reset")
                        val dailyResetHeader = response.header("x-rl-daily-reset")

                        hourlyResetTime = hourlyResetHeader?.let { parseResetTime(it) }
                        dailyResetTime = dailyResetHeader?.let { parseResetTime(it) }

                        // Update time until reset
                        hourlyResetInMinutes = hourlyResetTime?.let { calculateMinutesUntilReset(it) } ?: 0
                        dailyResetInMinutes = dailyResetTime?.let { calculateMinutesUntilReset(it) } ?: 0
                    }

                    // Status and rate-limit info are enough; logging every header floods the log view.
                    Log.d("HTTP_DEBUG", "Status: ${response.code} ${response.message} for $url")
                    addCustomLog(
                        "Status: ${response.code} (hourly left: ${response.header("x-rl-hourly-remaining") ?: "?"}, daily left: ${response.header("x-rl-daily-remaining") ?: "?"})",
                        textSize = 10, textColor = Color.Green
                    )
                }
            }

            if (response.code == 429 && !retried && url.contains("api.nexusmods.com")) {
                // Nexus refused: wait for the reset time it reported (a short pause if it gave none), then try once more
                response.close()
                limitedUntil = NexusInfo.hourlyResetTime ?: java.time.ZonedDateTime.now().plusMinutes(1)
                return fetchJsonResponse(url, retried = true)
            }
            if (!response.isSuccessful) {
                val detail = response.body.use { it.string() }.take(300)
                throw IOException("Failed to fetch: ${response.code} ${response.message} $detail")
            }

            return response.body.use { body ->
                val contentLength = body.contentLength()
                Log.d("HTTP_DEBUG", "Received $contentLength bytes")
                addCustomLog("Received: $contentLength bytes", textSize = 10, textColor = Color.Cyan)
                body.string()
            }
        } catch (e: TimeoutCancellationException) {
            throw IOException("Request timed out after 90 seconds", e)
        } catch (e: Exception) {
            throw IOException("Failed to fetch response: ${e.message}", e)
        }
    }
}

class DownloadViewModel : ViewModel() {
    val completedDownloads = mutableStateSetOf<String>()

    fun markAsCompleted(modId: String) {
        completedDownloads.add(modId)
        // Clean up after a short delay to show completion
        viewModelScope.launch {
            delay(2000.milliseconds) // Wait 2 seconds before cleanup
            NexusInfo.downloadProgressMap.remove(modId)
            NexusInfo.downloadProgressPercentMap.remove(modId)
            NexusInfo.downloadSpeedMap.remove(modId)
            completedDownloads.remove(modId)
        }
    }
}
