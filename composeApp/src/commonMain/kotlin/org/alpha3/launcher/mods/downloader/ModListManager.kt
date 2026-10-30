package org.alpha3.launcher.mods.downloader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.edit
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelChildren
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
import okio.IOException
import org.alpha3.launcher.io.File
import org.alpha3.launcher.mods.downloader.ModListManager.allowExtract
import org.alpha3.launcher.mods.downloader.ModListManager.brokenMods
import org.alpha3.launcher.mods.downloader.ModListManager.modList
import org.alpha3.launcher.mods.downloader.ModListManager.onlySync
import org.alpha3.launcher.mods.downloader.ModListManager.remainingMods
import org.alpha3.launcher.mods.downloader.ModListManager.selectedThreadCount
import org.alpha3.launcher.mods.downloader.ModListManager.validate
import org.alpha3.launcher.mods.downloader.StatusInfo.processing
import org.alpha3.launcher.paths.OpenMWPaths
import org.alpha3.launcher.ui.view.addCustomLog
import org.alpha3.launcher.utils.GameFilesPreferences
import org.alpha3.launcher.utils.Log
import org.alpha3.launcher.utils.availableCpuCores
import org.alpha3.launcher.utils.currentTimeMillis
import org.alpha3.launcher.utils.dataStore
import kotlin.concurrent.Volatile
import kotlin.math.absoluteValue
import kotlin.time.Duration.Companion.milliseconds

/** How long the downloaded mod lists are reused before the next refresh. */
private const val LIST_REFRESH_INTERVAL_MS = 6L * 60 * 60 * 1000

/** The downloads, archives and generated configs live here. */
internal val modStorage get() = "${OpenMWPaths.USER_FILE_STORAGE}/OpenMW"
internal val modCache get() = "$modStorage/CACHE"

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

fun processModList(isPremium: Boolean): Flow<List<ModDesc>> = flow {
    // isValidated is required, but also isvalidated = isPremium in this case.
    val modDao = ModDatabase.getDatabase().modDao()
    var brokenModCount = 0
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

    File("$modStorage/Download_$modList.log").delete()
    File("$modStorage/Extraction_$modList.log").delete()

    // SQL narrows it to this list, so the rest of the table isn't decoded; the exact check stays as a safety net
    val mods = modDao.getModsByModList(modList).filter { mod -> mod.onLists.contains(modList) }

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
        if (!File(modCache, "${info.fileName}").isFile) continue
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
    val brokenLock = SpinLock()

    coroutineScope {
        // Only refreshes the premium flag in the background; it must not hold up the sync.
        if (ModListManager.apiKey.isNotBlank()) {
            launch { withTimeoutOrNull(15_000.milliseconds) { validate() } }
        }
        // Biggest archives first: they take longest, so starting them late would leave one or two slow downloads at the end.
        fun largestKb(mod: ModDesc) = mod.downloadInfo.filter { it.sourceLists.contains(modList) }
            .maxOfOrNull { it.fileSize?.toLongOrNull() ?: 0L } ?: 0L
        mods.sortedByDescending(::largestKb).mapIndexed { index, mod ->
            async(Dispatchers.IO) {
                Log.i("ProcessModList", "Processing mod ${index + 1}/${mods.size} (${mod.slug}) with ${mod.downloadInfo.size} download links and Premium status of $isPremium")
                addCustomLog("Processing mod ${index + 1}/${mods.size} (${mod.slug}) [Premium: $isPremium]", textSize = 10, textColor = Color.Cyan)
                syncPermits.withPermit { syncMod(mod, isPremium, modDao) { brokenLock.withLock { brokenModCount++ } } }
                brokenMods = "Total Broken mods: ${brokenLock.withLock { brokenModCount }}"

                // The sync step above saved the links and file IDs it found to the database, not into this mod,
                // so downloading from `mod` would find no link for a mod that was synced in this very run.
                val freshMod = modDao.getModById(mod.modId) ?: mod

                if (onlySync) {
                    ModListManager.finishMod()
                } else for (info in freshMod.downloadInfo.filter { it.sourceLists.contains(modList) }) {
                    // A file that is already in the cache only needs unpacking, so it must not wait for a download slot
                    val cachedAlready = File(modCache, "${info.fileName}").exists() || ExtractMarker.isDone(freshMod, info)
                    if (cachedAlready) fetchFile(freshMod, info, isPremium)
                    else downloadPermits.withPermit { fetchFile(freshMod, info, isPremium) }
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

/** Finds the download links and file IDs of [mod] for its site and saves them to the database. */
private suspend fun syncMod(mod: ModDesc, isPremium: Boolean, modDao: ModDao, broken: () -> Unit) {
    when {
        mod.url.contains("nexusmods.com") -> {
            val currentUnixTimestamp = currentTimeMillis() / 1000

            // Step 1: Get updated DownloadInfo list with nexusFileId values
            val updatedInfoList = if (mod.downloadInfo.any { it.nexusFileId == null }) {
                try {
                    Log.i("ProcessModList", "Fetching file IDs for ${mod.slug}")
                    addCustomLog("Nexus: Fetching file IDs for ${mod.slug}", textSize = 10, textColor = Color.Cyan)
                    getFileIds(mod)
                } catch (e: CancellationException) {
                    throw e
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

            // Step 2: Refresh the direct download links that have expired (premium members only)
            val finalInfoList = updatedInfoList.map { info ->
                val expiresTimestamp = info.directDownload?.let { extractExpiresValue(it) } ?: Long.MAX_VALUE
                val isExpired = info.directDownload.isNullOrEmpty() || expiresTimestamp < currentUnixTimestamp
                if (isPremium && isExpired && info.nexusFileId != null) {
                    try {
                        getDirectDownload(mod, info)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.i("ProcessModList", "Failed to fetch direct download for ${mod.slug}: ${e.message}")
                        addCustomLog("Failed to fetch direct download for ${mod.slug}: ${e.message}", textSize = 10, textColor = Color.Red)
                        info
                    }
                } else info
            }
            modDao.updateMod(mod.copy(downloadInfo = finalInfoList))
        }

        mod.url.startsWith("/") -> {
            Log.d("ProcessModList", "Processing local file for ${mod.slug}: ${mod.url}")
            addCustomLog("Processing local file: ${mod.slug}", textSize = 10, textColor = Color.Cyan)
            val directDownloadUrl = "https://modding-openmw.com${mod.url}"
            val fileName = mod.url.substringAfterLast('/').takeIf { it.isNotBlank() } ?: "${mod.slug}.zip"

            // The list may already give a real link (e.g. a GitLab archive) with the mod's page as its url;
            // only entries without one are files hosted on the list's own site.
            val updatedDownloadInfo = mod.downloadInfo.map { info ->
                if (!info.directDownload.isNullOrEmpty()) info
                else info.copy(directDownload = directDownloadUrl, fileName = fileName)
            }
            modDao.insertOrUpdateMod(
                mod.copy(
                    downloadInfo = updatedDownloadInfo,
                    dlUrl = if (mod.downloadInfo.any { !it.directDownload.isNullOrEmpty() }) mod.dlUrl else directDownloadUrl
                )
            )
        }

        mod.url.contains("web.archive.org") -> {
            val lastDigits = extractLastDigits(mod.url)
            val archivedUrl = "https://web.archive.org/cdx/search/cdx?url=mw.modhistory.com/file.php?id=$lastDigits&output=json&sort=descending&limit=1"
            val updatedUrl = getLatestWaybackUrl(archivedUrl)
            val cleanedDlUrl = mod.dlUrl?.replace("/files/", "")
            if (updatedUrl != null) {
                addCustomLog("Archive.org Link: $updatedUrl", textSize = 10, textColor = Color.Cyan)
                val updatedDownloadInfo = mod.downloadInfo.map { it.copy(directDownload = updatedUrl, fileName = cleanedDlUrl) }
                modDao.insertOrUpdateMod(mod.copy(downloadInfo = updatedDownloadInfo))
            } else {
                addCustomLog("Archive.org error: Mod not found for ${mod.slug}", textSize = 10, textColor = Color.Red)
            }
        }

        mod.url.contains("gitlab") -> {
            mod.downloadInfo.forEach { info ->
                if (info.directDownload.isNullOrEmpty()) {
                    fetchGitProjectId(mod, modDao)
                    addCustomLog("Fetching GitLab ID for ${mod.slug}", textSize = 10, textColor = Color.Cyan)
                } else {
                    Log.d("fetchGitLabProjectId", "Download Link and Filename already exists for ${mod.slug}, skipping")
                    addCustomLog("GitLab: Skipping ${mod.slug} (Exists)", textSize = 10, textColor = Color.Green)
                }
            }
        }

        mod.url.startsWith("https://baturin.org") -> {
            addCustomLog("Processing local file for ${mod.slug}: ${mod.url}", textSize = 10, textColor = Color.White)
            val (directDownloadUrl, fileName) = baturinLink(mod)
            val updatedDownloadInfo = mod.downloadInfo.map { it.copy(directDownload = directDownloadUrl, fileName = fileName) }
            modDao.insertOrUpdateMod(mod.copy(downloadInfo = updatedDownloadInfo, dlUrl = directDownloadUrl))
        }

        else -> broken()
    }
}

/** baturin.org hosts its mods as morrowind-mods/<fragment of the mod's url, with '_' for '-'>.zip */
internal fun baturinLink(mod: ModDesc): Pair<String, String> {
    val fragment = mod.url.substringAfterLast("#")
    val fileName = if (fragment.isNotBlank() && fragment != mod.url) "${fragment.replace("-", "_")}.zip"
    else "${mod.slug.replace("-", "_")}.zip"
    return "https://baturin.org/misc/morrowind-mods/$fileName" to fileName
}

/** Downloads one file of the run (or only unpacks it when it is cached already) and counts it off. */
private suspend fun fetchFile(mod: ModDesc, info: DownloadInfo, isPremium: Boolean) {
    appendToDownloadLog("Downloading: ${info.fileName}")
    try {
        if (File(modCache, "${info.fileName}").exists() || ExtractMarker.isDone(mod, info)) {
            Log.d("DownloadSkip", "File already exists for ${mod.slug}, skipping download")
            addCustomLog("Cache Hit: ${info.fileName}", textSize = 10, textColor = Color.Green)
            NexusInfo.downloadProgressMap[progressKey(mod, info)] = "${mod.name} Downloaded"
            NexusInfo.downloadProgressPercentMap[progressKey(mod, info)] = 100
            NexusInfo.downloadSpeedMap.remove(progressKey(mod, info))

            // extractModFile counts the file off remainingMods when it finishes
            if (allowExtract) extractModFile(mod, info) else ModListManager.finishMod()
            return
        }
        when {
            mod.url.contains("nexusmods.com") -> {
                if (!isPremium) {
                    withContext(Dispatchers.Main) { NxmDownloads.open(mod, info) }
                } else if (!info.directDownload.isNullOrEmpty()) {
                    downloadModFile(mod, info)
                } else {
                    failWithoutLink(mod, info)
                }
            }

            mod.url.contains("mega.nz") -> {
                val file = MegaDownloader().download(mod.url, File(modCache))
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
                if (!info.directDownload.isNullOrEmpty()) downloadModFile(mod, info) else failWithoutLink(mod, info)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e("ProcessModList", "Failed to download file for ${mod.slug}: ${e.message}", e)
        addCustomLog("Download Error (${mod.slug}): ${e.message}", textSize = 10, textColor = Color.Red)
        NexusInfo.downloadProgressMap[progressKey(mod, info)] = "Failed to download ${mod.name}"
        NexusInfo.downloadProgressPercentMap[progressKey(mod, info)] = 0
        ModListManager.fileFailed()
        ModListManager.finishMod()
    } finally {
        NexusInfo.downloadSpeedMap.remove(progressKey(mod, info))
    }
}

object ModListManager {
    // Mod Manager
    var modList by mutableStateOf("i-heart-vanilla")
    var apiKey by mutableStateOf("")
    var allowExtract by mutableStateOf(true)
    var onlySync by mutableStateOf(false)
    var brokenMods by mutableStateOf("")

    val cpuThreadCount = availableCpuCores()
    var selectedThreadCount by mutableIntStateOf(cpuThreadCount)

    // How many mods are synced at once. Defaults to the core count; the download options can lower it.
    var syncThreadCount by mutableIntStateOf(cpuThreadCount)

    var remainingMods by mutableIntStateOf(0)

    /** Archives unpack here, outside the download slots, a few at a time (unpacking is CPU and disk heavy). */
    val extractScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val extractPermits = Semaphore(3)

    /** Downloads started from nxm:// links, outside [runJob]; cancelling the run stops them too. */
    internal val transferScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Set from the downloader screen; running downloads hold their place until it is cleared. */
    var paused by mutableStateOf(false)

    /** Set by [cancelRun]: downloads that see it stop instead of retrying. Cleared when the next run starts. */
    @Volatile var cancelled = false

    /** The coroutine that runs the current mod list, so [cancelRun] can stop it. */
    @Volatile var runJob: Job? = null

    /**
     * Stops the run: running downloads (their connections are dropped, partial files stay for a later resume), archives
     * waiting to unpack, the Nexus pages waiting for a free member's confirmation, and keeping the app awake. Nothing
     * is installed or configured for a cancelled run.
     */
    fun cancelRun() {
        cancelled = true
        runJob?.cancel()
        runJob = null
        transferScope.coroutineContext.cancelChildren()
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
    fun fileFailed() { remainingLock.withLock { failedFiles++ } }

    /** Display names by mod slug, for the progress card. Filled before the run's downloads start. */
    val modNames = mutableStateMapOf<String, String>()

    /** Size in KB of each file of the run as the mod list states it, by progress key; 0 or missing when unknown. */
    val fileSizeKb = mutableStateMapOf<String, Long>()

    /** Files paused one by one from their card, by progress key (see [progressKey]). */
    val pausedFiles = mutableStateMapOf<String, Boolean>()

    fun isPaused(key: String) = paused || pausedFiles[key] == true
    fun togglePaused(key: String) { if (pausedFiles[key] == true) pausedFiles.remove(key) else pausedFiles[key] = true }

    // Many coroutines finish at once (downloads, extractions); a plain `remainingMods--` loses counts.
    private val remainingLock = SpinLock()
    private var onAllFinished: (() -> Unit)? = null

    fun cancelPendingCompletion() { remainingLock.withLock { onAllFinished = null } }

    fun finishMod() {
        val done = remainingLock.withLock {
            if (remainingMods > 0) remainingMods--
            if (remainingMods == 0) onAllFinished.also { onAllFinished = null } else null
        }
        done?.invoke()
    }

    /** Runs [action] once no file is left to download or extract, immediately if that is already so. */
    fun whenAllFinished(action: () -> Unit) {
        val runNow = remainingLock.withLock {
            if (remainingMods <= 0) true else { onAllFinished = action; false }
        }
        if (runNow) action()
    }

    // Starts with the defaults and is replaced when the real lists arrive; loading never blocks the caller.
    val availableLists = mutableStateListOf("i-heart-vanilla", "total-overhaul")

    suspend fun fetchAvailableLists(): MutableList<String> = withContext(Dispatchers.IO) {
        try {
            val jsonString = ModHttp.getText("https://modding-openmw.com/lists/json")
            Json.decodeFromString<List<String>>(jsonString)
                .filter { !it.endsWith("-wip") }
                .filter { !it.contains("one-day-morrowind-modernization") }
                .toMutableList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            addCustomLog("Failed to fetch/parse mod lists: ${e.message}", textSize = 10, textColor = Color.Cyan)
            mutableListOf("i-heart-vanilla", "total-overhaul")
        }
    }

    val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    private val loaderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listsLoad by lazy {
        loaderScope.async {
            val fetched = fetchAvailableLists()
            withContext(Dispatchers.Main) {
                availableLists.clear()
                availableLists.addAll(fetched)
            }
        }
    }

    suspend fun validate() {
        try {
            val jsonString = fetchJsonResponse("https://api.nexusmods.com/v1/users/validate.json")
            val premiumStatus = Json.parseToJsonElement(jsonString).jsonObject["is_premium"]?.jsonPrimitive?.booleanOrNull ?: false
            dataStore.edit { preferences -> preferences[GameFilesPreferences.IS_NEXUS_PREMIUM] = premiumStatus }
            Log.i("Validate", "Raw JSON: $jsonString")
            addCustomLog("Validate: Raw JSON: $jsonString", textSize = 10, textColor = Color.Yellow)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("VALIDATE_ERROR", "Validation failed: ${e.message}")
            addCustomLog("Validate: Validation failed: ${e.message}", textSize = 10, textColor = Color.Yellow)
        }
    }

    private val blockedModNames = listOf(
        "Buy and Install Morrowind",
        "Install OpenMW",
        "Set your Site Settings",
        "Folder Deploy Script",
        "How to Install Mods",
        "S3LightFixes",
        "MOMW Tools Pack"
    )

    /** The mods of one list as this app keeps them: blocked entries dropped, a mod ID given, the archives tagged with the list. */
    private fun prepareList(listName: String, mods: List<ModDesc>, fallbackId: (ModDesc) -> String): List<ModDesc> =
        mods.filter { it.name !in blockedModNames }.map { modDesc ->
            val modId = if (modDesc.url.contains("nexusmods.com/morrowind/mods/")) {
                modDesc.url.substringAfter("mods/").substringBefore("?").trim().toIntOrNull().toString()
            } else fallbackId(modDesc)
            modDesc.copy(
                modId = modId,
                downloadInfo = modDesc.downloadInfo.map { it.forList(listName) },
                onLists = listOf(listName)
            )
        }

    suspend fun downloadAndParseModList(): List<ModDesc> = withContext(Dispatchers.Default) {
        processing = true
        val allFilteredMods = mutableListOf<ModDesc>()
        val modDao = ModDatabase.getDatabase().modDao()

        // The lists change rarely, and this runs every time the screen opens, so reuse the saved copy
        // when they were fetched recently instead of downloading dozens of JSON files again.
        listsLoad.await()
        val lastSync = GameFilesPreferences.getPreference(GameFilesPreferences.MOD_LISTS_SYNCED_AT, 0L).first()
        val listsAreFresh = currentTimeMillis() - lastSync < LIST_REFRESH_INTERVAL_MS && modDao.getModCount() > 0
        if (listsAreFresh) {
            addCustomLog("Mod lists synced recently, using the saved copy", textSize = 10, textColor = Color.Cyan)
        }

        // Fetch and parse the online lists a few at a time, then write them to the DB in order.
        // "poti" comes from a local import, it doesn't exist on the server.
        val fetchPermits = Semaphore(4)
        var fetchFailed = false
        val fetchedLists = if (listsAreFresh) emptyList() else coroutineScope {
            availableLists.toList().filter { it != "poti" }.map { listName ->
                async(Dispatchers.IO) {
                    fetchPermits.withPermit {
                        try {
                            val body = ModHttp.getText("https://modding-openmw.com/lists/$listName/json")
                            listName to json.decodeFromString<List<ModDesc>>(body)
                        } catch (e: CancellationException) {
                            throw e
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

        // Every list is written in ONE transaction at the end (see ModDao.mergeLists).
        val pendingLists = mutableListOf<Pair<String, List<ModDesc>>>()

        for ((listName, modListData) in fetchedLists) {
            val prepared = prepareList(listName, modListData) {
                it.slug.hashCode().absoluteValue.toString().padStart(6, '0').takeLast(6)
            }
            pendingLists += listName to prepared
            allFilteredMods.addAll(prepared)
        }

        // Lists the person saved as JSON files next to the downloads
        val directory = File(modStorage)
        addCustomLog("Looking for custom lists in: ${directory.absolutePath}", textSize = 10, textColor = Color.Cyan)
        if (directory.isDirectory) {
            val jsonFiles = directory.listFiles { it.name.endsWith(".json") }.orEmpty()
            addCustomLog("Found ${jsonFiles.size} JSON files", textSize = 10, textColor = Color.Cyan)
            for (file in jsonFiles) {
                try {
                    val modListData = json.decodeFromString<List<ModDesc>>(file.readText())
                    val listName = file.nameWithoutExtension
                    val prepared = prepareList(listName, modListData) { (100000..999999).random().toString() }
                    pendingLists += listName to prepared
                    allFilteredMods.addAll(prepared)
                    addCustomLog("Processed local file: ${file.name}", textSize = 10, textColor = Color.Green)
                } catch (e: Exception) {
                    Log.e("ModListManager", "Error processing local file ${file.name}: ${e.message}")
                    addCustomLog("Error processing local file ${file.name}: ${e.message}", textSize = 10, textColor = Color.Red)
                }
            }
        } else {
            Log.w("ModListManager", "Directory does not exist or is not a directory: ${directory.absolutePath}")
        }

        modDao.mergeLists(pendingLists) { listName, count, error ->
            if (error == null) addCustomLog("Merged $listName ($count mods)", textSize = 10, textColor = Color.Cyan)
            else addCustomLog("Partial failure merging $listName: ${error.message}", textSize = 10, textColor = Color.Yellow)
        }
        if (!listsAreFresh && !fetchFailed) {
            GameFilesPreferences.setPreference(GameFilesPreferences.MOD_LISTS_SYNCED_AT, currentTimeMillis())
        }
        processing = false
        return@withContext allFilteredMods
    }

    /** Set while the Nexus hourly request limit holds the sync back, for the progress card. */
    var rateLimitWait by mutableStateOf<String?>(null)

    /** Epoch milliseconds; set when Nexus actually answers 429. The hourly count shown by the headers alone does not stop requests. */
    private var limitedUntil: Long? = null

    private suspend fun awaitNexusRateLimit() {
        while (true) {
            val until = limitedUntil ?: break
            val ms = until - currentTimeMillis()
            if (ms <= 0) { limitedUntil = null; break }
            rateLimitWait = "Nexus asked us to slow down. Continuing in ${(ms / 60_000) + 1} min."
            delay(minOf(ms + 3_000, 20_000).milliseconds)
        }
        rateLimitWait = null
    }

    suspend fun fetchJsonResponse(url: String, retried: Boolean = false): String {
        if (url.contains("api.nexusmods.com")) awaitNexusRateLimit()
        try {
            val response = withTimeout(90_000.milliseconds) {
                ModHttp.client.get(url) {
                    header("Accept", "application/json")
                    header("apikey", apiKey)
                    header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                }
            }
            NexusInfo.apply {
                hourlyremaining = response.headers["x-rl-hourly-remaining"]?.toIntOrNull() ?: hourlyremaining
                dailyremaining = response.headers["x-rl-daily-remaining"]?.toIntOrNull() ?: dailyremaining
                hourlyResetTime = response.headers["x-rl-hourly-reset"]?.let { parseResetTime(it) }
                dailyResetTime = response.headers["x-rl-daily-reset"]?.let { parseResetTime(it) }
                hourlyResetInMinutes = hourlyResetTime?.let { calculateMinutesUntilReset(it) } ?: 0
                dailyResetInMinutes = dailyResetTime?.let { calculateMinutesUntilReset(it) } ?: 0
            }
            // Status and rate-limit info are enough; logging every header floods the log view.
            Log.d("HTTP_DEBUG", "Status: ${response.status} for $url")
            addCustomLog(
                "Status: ${response.status.value} (hourly left: ${response.headers["x-rl-hourly-remaining"] ?: "?"}, daily left: ${response.headers["x-rl-daily-remaining"] ?: "?"})",
                textSize = 10, textColor = Color.Green
            )

            if (response.status.value == 429 && !retried && url.contains("api.nexusmods.com")) {
                // Nexus refused: wait for the reset time it reported (a short pause if it gave none), then try once more
                limitedUntil = NexusInfo.hourlyResetTime ?: (currentTimeMillis() + 60_000)
                return fetchJsonResponse(url, retried = true)
            }
            if (!response.status.isSuccess()) {
                val detail = response.bodyAsText().take(300)
                throw IOException("Failed to fetch: ${response.status.value} ${response.status.description} $detail")
            }
            addCustomLog("Received: ${response.contentLength() ?: -1} bytes", textSize = 10, textColor = Color.Cyan)
            return response.bodyAsText()
        } catch (e: TimeoutCancellationException) {
            throw IOException("Request timed out after 90 seconds", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Failed to fetch response: ${e.message}", e)
        }
    }
}
