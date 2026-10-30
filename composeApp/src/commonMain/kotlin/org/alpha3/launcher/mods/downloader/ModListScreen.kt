@file:Suppress("DEPRECATION")

package org.alpha3.launcher.mods.downloader

import org.alpha3.launcher.utils.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Color.Companion.White
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.alpha3.launcher.paths.OpenMWPaths
import org.alpha3.launcher.mods.downloader.ModListManager.modList
import org.alpha3.launcher.mods.downloader.NexusInfo.downloadProgressMap
import org.alpha3.launcher.mods.downloader.StatusInfo.activeMods
import org.alpha3.launcher.mods.downloader.ZipUtils.nativeGetArchiveFileList
import org.alpha3.launcher.utils.currentTimeMillis
import org.alpha3.launcher.ui.controls.UIStateManager.customColor
import org.alpha3.launcher.utils.GameFilesPreferences
import org.alpha3.launcher.io.File
import org.alpha3.launcher.ui.controls.UIStateManager.gold

@Composable
fun ModListScreen() {
    val scope = rememberCoroutineScope()
    val modDao = remember { ModDatabase.getDatabase().modDao() }

    // Track which mods have been processed to prevent duplicate work
    val processedMods = remember { mutableStateSetOf<String>() }

    // Collect mods as State
    val mods by modDao.getAllModsFlow().collectAsState(initial = emptyList())

    // Filter mods and update activeMods count
    val filteredMods = remember(mods, modList) {
        val filtered = mods.filter { mod ->
            mod.onLists.contains(modList)
        }
        activeMods = filtered.size // Update the count whenever filtered list changes
        filtered
    }

    // Single refresh on first load
    LaunchedEffect(Unit) {
        scope.launch {
            mods.forEach { mod ->
                if (!processedMods.contains(mod.slug)) {
                    val currentUnixTimestamp = (currentTimeMillis() / 1000)

                    val updatedDownloadInfoList = mod.downloadInfo.map { info ->
                        val expiresTimestamp = info.directDownload?.let { extractExpiresValue(it) }
                        val isExpired = info.directDownload.isNullOrEmpty() ||
                                (expiresTimestamp != null && expiresTimestamp < currentUnixTimestamp)

                        if (isExpired && info.nexusFileId != null) {
                            try {
                                val updatedInfo = withContext(Dispatchers.IO) {
                                    getDirectDownload(mod, info)
                                }
                                Log.d("Refresh", "Updated download info for ${mod.slug}")
                                updatedInfo
                            } catch (e: Exception) {
                                Log.e("Refresh", "Failed to refresh ${mod.slug}", e)
                                info // fallback to original
                            }
                        } else {
                            info
                        }
                    }

                    val updatedMod = mod.copy(downloadInfo = updatedDownloadInfoList)
                    modDao.updateMod(updatedMod)
                    processedMods.add(mod.slug)
                    Log.d("Refresh", "Successfully refreshed all downloads for ${mod.slug}")
                }
            }
        }
    }

    Column {
        LazyColumn {
            items(
                count = filteredMods.size,
                key = { index -> filteredMods[index].slug }
            ) { index ->
                ModItem(
                    mod = filteredMods[index],
                    onDelete = { scope.launch { modDao.deleteMod(filteredMods[index]) } },
                    modDao = modDao
                )
            }
        }
    }
}

@Composable
fun CustomPagerIndicator(
    pagerState: PagerState,
    pageCount: Int,
    modifier: Modifier = Modifier,
    activeColor: Color = Color.White,
    inactiveColor: Color = Color.Gray,
    indicatorSize: Int = 8,
    spacing: Int = 4
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(pageCount) { index ->
            Box(
                modifier = Modifier
                    .size(indicatorSize.dp)
                    .clip(CircleShape)
                    .background(
                        if (pagerState.currentPage == index) activeColor else inactiveColor
                    )
            )
        }
    }
}

fun stripHtml(html: String): String {
    return html.replace(Regex("<[^>]+>"), "")
}

@Composable
fun ModItem(mod: ModDesc, onDelete: () -> Unit, modDao: ModDao) {
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var detailsExpanded by remember { mutableStateOf(false) }
    var currentMod by remember { mutableStateOf(mod) }
    val pagerState = rememberPagerState(pageCount = { currentMod.galleryUrls.size })
    val downloadingMap = remember { mutableStateMapOf<String, Boolean>() }
    val isDownloading = downloadingMap[mod.slug] ?: false
    val currentUnixTimestamp = (currentTimeMillis() / 1000)
    val progressText = downloadProgressMap[mod.slug]
        ?: downloadProgressMap.entries.firstOrNull { it.key.startsWith("${mod.slug}|") }?.value
    val coroutineScope = rememberCoroutineScope()
    var isLoadingGallery by remember { mutableStateOf(false) }
    var galleryError by remember { mutableStateOf<String?>(null) }
    var selectedImageUrl by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val plainText = stripHtml(mod.usageNotes ?: "")
    val isPremium by GameFilesPreferences.getNullablePreference(GameFilesPreferences.IS_NEXUS_PREMIUM).collectAsState(initial = null)
    val currentListDownloads = mod.downloadInfo
        .filter { it.sourceLists.contains(modList) && !it.fileName.isNullOrBlank() }
        .distinctBy { "${it.fileName}|${it.nexusFileId}|${it.directDownload}" }

    LaunchedEffect(expanded) {
        if (expanded) {
            try {
                if (currentMod.url.contains("nexusmods.com")) {
                    try {
                        isLoadingGallery = true
                        val updatedModGal = getUpdatedModGallery(currentMod)
                        modDao.updateMod(updatedModGal)
                        currentMod = updatedModGal
                    } catch (e: Exception) {
                        galleryError = "Failed to load gallery: ${e.message}"
                    } finally {
                        isLoadingGallery = false
                    }

                    val allNexusFileIdsPresent = mod.downloadInfo.all { !it.nexusFileId.isNullOrEmpty() }

                    val updatedInfoList = if (!allNexusFileIdsPresent) {
                        Log.i("GetFileID_Debug", "Started for ${mod.modId}")
                        try {
                            getFileIds(mod)
                        } catch (e: Exception) {
                            errorMessage = "Failed to fetch nexusId: ${e.message}"
                            mod.downloadInfo // Fallback to original list
                        }
                    } else {
                        Log.d(
                            "getFileIds",
                            "Nexus file IDs already present: \n${
                                mod.downloadInfo.joinToString(separator = "\n") {
                                    "fileName=${it.fileName ?: "unknown"}, nexusFileId=${it.nexusFileId}"
                                }
                            }"
                        )
                        mod.downloadInfo // No need to process, use original list
                    }

                    // Combine updates for galleryUrls and downloadInfo
                    val updatedMod = mod.copy(
                        downloadInfo = updatedInfoList
                    )
                    modDao.updateMod(updatedMod)
                }

                if (currentMod.url.contains("web.archive.org")) {
                    val lastDigits = extractLastDigits(currentMod.url)

                    val archivedUrl = "https://web.archive.org/cdx/search/cdx?url=mw.modhistory.com/file.php?id=$lastDigits&output=json&sort=descending&limit=1"
                    val updatedUrl = getLatestWaybackUrl(archivedUrl)
                    val cleanedDlUrl = mod.dlUrl?.replace("/files/", "")
                    if (updatedUrl != null) {
                        println("Download link: $updatedUrl")
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
                    }
                }
                if (currentMod.url.contains("https://mega")) {
                    Log.d(
                        "ProcessModList",
                        "Processing mega link: ${mod.url}"
                    )
                    scope.launch(Dispatchers.IO) {
                        val downloader = MegaDownloader()
                        // MEGA names the file itself, so record that name; the extractor opens CACHE/<fileName>
                        val file = downloader.download(mod.url, File("${OpenMWPaths.USER_FILE_STORAGE}/OpenMW/CACHE"))
                        if (file != null) {
                            val updatedDownloadInfo = mod.downloadInfo.map { info -> info.copy(fileName = file.name) }
                            modDao.insertOrUpdateMod(mod.copy(downloadInfo = updatedDownloadInfo))
                        }
                    }
                }
                if (currentMod.url.contains("https://baturin.org")) {
                    Log.d(
                        "ProcessModList",
                        "Processing local file for ${mod.slug}: ${mod.url}"
                    )
                    try {
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
                    }
                }

                if (mod.url.contains("gitlab")) {
                    fetchGitProjectId(mod, modDao)
                }
                if (mod.url.startsWith("/")) {
                    val directDownloadUrl =
                        "https://modding-openmw.com${mod.url}"
                    val fileName = mod.url.substringAfterLast('/')
                        .takeIf { it.isNotBlank() }
                        ?: "${mod.slug}.zip"

                    val updatedDownloadInfo = mod.downloadInfo.map { info ->
                        info.copy(
                            fileName = fileName,
                            directDownload = directDownloadUrl)
                    }

                    val updatedMod = mod.copy(
                        dlUrl = directDownloadUrl,
                        downloadInfo = updatedDownloadInfo
                    )
                    modDao.insertOrUpdateMod(updatedMod)
                }

            } catch (e: Exception) {
                "modItem LaunchedEffect Failed: ${e.message}"
            } finally {
                isLoadingGallery = false
            }
        }
    }

    LaunchedEffect(mod.downloadInfo) {
        if (mod.downloadInfo.size > 1) {
            println("Duplicate check for ${mod.slug}:")
            mod.downloadInfo.groupBy { it.fileName }
                .filter { it.value.size > 1 }
                .forEach { (name, items) ->
                    println("'$name' appears ${items.size} times")
                }
        }
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(2.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (expanded) Color.DarkGray else customColor,
        )
    ) {
        Column(modifier = Modifier.padding(4.dp)) {
            // Basic Info Section (Always visible)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = mod.name.ifEmpty { "Unnamed Mod" },
                        style = MaterialTheme.typography.titleSmall
                    )
                }

                val isNexusMod = mod.url.contains("nexusmods.com")
                val hasValidDownload = mod.downloadInfo.any { info ->
                    val directDownload = info.directDownload
                    val expiresTimestamp = directDownload?.let { extractExpiresValue(it) } ?: Long.MAX_VALUE
                    val isExpired = directDownload.isNullOrEmpty() || expiresTimestamp < currentUnixTimestamp
                    !directDownload.isNullOrEmpty() && !isExpired
                }
                if ((!isNexusMod && hasValidDownload) || (isNexusMod && hasValidDownload)) {
                    val fileExists = remember(mod.downloadInfo) {
                        mod.downloadInfo.any { info ->
                            val destination = File("${OpenMWPaths.USER_FILE_STORAGE}/OpenMW/CACHE", "${info.fileName}")
                            destination.exists()
                        }
                    }
                    val folderExists = remember(mod.downloadInfo) {
                        mod.downloadInfo.any { info ->
                            val extractFolder = File("${OpenMWPaths.USER_FILE_STORAGE}/OpenMW/${modList}/${mod.category}", "${info.extractTo}")
                            extractFolder.exists() && extractFolder.listFiles()?.isNotEmpty() == true
                        }
                    }
                    if (fileExists && !folderExists) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            tint = gold,
                            contentDescription = "File downloaded",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    if (folderExists) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "File Extracted",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Badge(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                    ) {
                        Text("Ready")
                    }
                }
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand"
                    )
                }
            }

            // Expanded Details Section
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Text(
                        text = "Mod Id: ${mod.modId.ifEmpty { "Mod ID Not Found" }}",
                        style = MaterialTheme.typography.titleSmall
                    )
                    HtmlText(
                        html = mod.author.ifEmpty { "Unknown author" },
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    )
                    InfoRow(label = "URL", value = mod.url, isLink = true)
                    //Log.d("URL_Debug", mod.url)
                    Text(
                        text = mod.description.ifEmpty { "No description available" },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )

                    InfoRow(label = "Category", value = mod.category)
                    InfoRow(label = "Directory", value = mod.dir)
                    InfoRow(label = "Direct URL", value = mod.dlUrl, isLink = true)
                    InfoRow(label = "Usage Notes", value = plainText)
                    InfoRow(label = "Plugins", value = mod.plugins.joinToString(", "))

                    // Download Info Section
                    Text(
                        text = "Download Info",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    if (mod.downloadInfo.isEmpty()) {
                        Text(
                            text = "None",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Column {
                            Row {
                                Text(
                                    text = "Show more details",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                IconButton(
                                    onClick = { detailsExpanded = !detailsExpanded },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        if (detailsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = if (detailsExpanded) "Collapse" else "Expand"
                                    )
                                }
                            }
                            if (detailsExpanded) {
                                InfoRow(label = "Slug", value = mod.slug)
                                InfoRow(label = "Compatibility", value = mod.compat.toString())
                                InfoRow(label = "Date Added", value = mod.dateAdded)
                                InfoRow(label = "Last Updated", value = mod.dateUpdated)
                                InfoRow(label = "ProjectId", value = mod.gitlabProjectId)
                                InfoRow(label = "PackageId", value = mod.gitlabPackageId)
                                InfoRow(
                                    label = "Data Paths",
                                    value = if (mod.dataPaths.isNotEmpty()) mod.dataPaths.joinToString(
                                        ", "
                                    ) else "Empty"
                                )
                                InfoRow(
                                    label = "On Lists",
                                    value = mod.onLists
                                        .filterNot { it.contains("-wip", ignoreCase = true) }
                                        .joinToString(", ") { it.ifEmpty { "None" } }
                                        .ifEmpty { "None" }
                                )
                            }

                            // Render current list downloads with details
                            if (currentListDownloads.isEmpty()) {
                                Text(
                                    text = "No download info available for $modList",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                currentListDownloads.forEach { info ->
                                    Text(
                                        text = "• ${info.fileName ?: "Unknown"} ✓",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )

                                    info.fileSize?.let {
                                        Text(
                                            text = "Filesize: $it kb",
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(start = 16.dp)
                                        )
                                    }
                                    info.extractTo?.let {
                                        Text(
                                            text = "Extract To: $it",
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(start = 16.dp)
                                        )
                                    }
                                    info.nexusFileId?.let {
                                        Text(
                                            text = "Nexus File ID: $it",
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(start = 16.dp)
                                        )
                                    }


                                }
                            }


                        }
                    }



                    // Modified to use forEach with downloadInfo for multiple directDownloads
                    if (isPremium == true && mod.url.contains("nexusmods.com")) {
                        currentListDownloads.forEach { info ->
                            val expiresTimestamp = info.directDownload?.let { extractExpiresValue(it) }
                            val isExpired = info.directDownload.isNullOrEmpty() ||
                                    (expiresTimestamp != null && expiresTimestamp < currentUnixTimestamp)
                            if (!info.directDownload.isNullOrEmpty()) {
                                InfoRow(
                                    label = "Time until expires",
                                    value = info.directDownload.let { downloadUrl ->
                                        val expiresTimestamp = extractExpiresValue(downloadUrl)
                                        if (expiresTimestamp == null || expiresTimestamp < currentUnixTimestamp) "Expired"
                                        else formatRemainingTime(
                                            expiresTimestamp,
                                            currentUnixTimestamp
                                        )
                                    },
                                    isLink = false
                                )
                                InfoRow(
                                    label = "Direct Download",
                                    value = if (isExpired) "Expired" else info.directDownload,
                                    isLink = !isExpired && info.directDownload.isNotEmpty(),
                                    fileName = info.fileName
                                )
                            }
                            if (isPremium == true && isExpired) {
                                Button(
                                    onClick = {
                                        isLoading = true
                                        errorMessage = null
                                        coroutineScope.launch {
                                            try {
                                                if (mod.url.contains("nexusmods.com")) {
                                                    val currentUnixTimestamp = (currentTimeMillis() / 1000)

                                                    val updatedDownloadInfoList =
                                                        mod.downloadInfo.map { info ->

                                                            val expiresTimestamp =
                                                                info.directDownload?.let {
                                                                    extractExpiresValue(it)
                                                                } ?: 0
                                                            val isExpired =
                                                                info.directDownload.isNullOrEmpty() || expiresTimestamp < currentUnixTimestamp

                                                            if (isExpired && info.nexusFileId != null) {
                                                                try {
                                                                    val updatedInfo =
                                                                        withContext(Dispatchers.IO) {
                                                                            getDirectDownload(
mod,
                                                                                info
                                                                            )
                                                                        }
                                                                    Log.d(
                                                                        "Refresh",
                                                                        "Updated download info for ${mod.slug}"
                                                                    )
                                                                    updatedInfo
                                                                } catch (e: Exception) {
                                                                    errorMessage =
                                                                        "Failed to fetch Direct Download Link: ${e.message}"
                                                                    info // fallback to original
                                                                }
                                                            } else {
                                                                info // not expired
                                                            }
                                                        }
                                                    val updatedMod =
                                                        mod.copy(downloadInfo = updatedDownloadInfoList)
                                                    val modDao =
                                                        ModDatabase.getDatabase().modDao()
                                                    modDao.insertOrUpdateMod(updatedMod)
                                                    Log.d(
                                                        "Refresh",
                                                        "Saved updated mod ${mod.slug} with refreshed downloadInfo list"
                                                    )
                                                }

                                                if (mod.url.contains("gitlab")) {
                                                    fetchGitProjectId(mod, modDao)
                                                }
                                                // Direct Handler
                                                if (mod.url.startsWith("/")) {
                                                    val directDownloadUrl =
                                                        "https://modding-openmw.com${mod.url}"
                                                    val fileName = mod.url.substringAfterLast('/')
                                                        .takeIf { it.isNotBlank() } ?: "${mod.slug}.zip"

                                                    val updatedDownloadInfo =
                                                        mod.downloadInfo.map { info ->
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
                                                }
                                            } catch (e: Exception) {
                                                errorMessage = "Failed to refresh: ${e.message}"
                                                Log.e("Refresh", "Error refreshing link", e)
                                            } finally {
                                                isLoading = false
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = customColor, // Background color
                                        contentColor = Color.White   // Text color
                                    ),
                                    enabled = !isLoading,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    if (isLoading) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Text(
                                            text = "Refresh Download Links",
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                    }
                                }
                                errorMessage?.let { message ->
                                    Text(
                                        text = message,
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }
                        }
                        if (currentListDownloads.none { !it.directDownload.isNullOrEmpty() }) {
                            InfoRow(
                                label = "Time until expires",
                                value = "Unavailable",
                                isLink = false
                            )
                            InfoRow(
                                label = "Direct Download",
                                value = "Unavailable",
                                isLink = false,
                                fileName = null
                            )
                        }
                    }
                    if (isLoadingGallery) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    } else if (galleryError != null) {
                        Text(
                            text = galleryError ?: "Unknown error",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    } else if (currentMod.galleryUrls.isNotEmpty()) {
                        Column {
                            HorizontalPager(
                                state = pagerState,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                            ) { page ->
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    AsyncImage(
                                        model = currentMod.galleryUrls[page],
                                        contentDescription = "Mod Image",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(180.dp)
                                            .clickable {
                                                selectedImageUrl = currentMod.galleryUrls[page]
                                            }
                                    )
                                    val imageName =
                                        currentMod.galleryUrls[page].substringAfterLast("/")
                                    InfoRow(label = "Image Name", value = imageName)
                                }
                            }
                            CustomPagerIndicator(
                                pagerState = pagerState,
                                pageCount = currentMod.galleryUrls.size,
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .padding(16.dp)
                            )
                        }
                    } else {
                        Text(
                            text = "No gallery images available",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                    // Tags Section
                    if (mod.tags.isNotEmpty()) {
                        FlowRow(
                            modifier = Modifier.padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            mod.tags.forEach { tag ->
                                SuggestionChip(
                                    onClick = {},
                                    label = { Text(tag) }
                                )
                            }
                        }
                    }

                    // Action Buttons
                    Column(
                        modifier = Modifier.padding(top = 16.dp)
                    ) {
                        currentListDownloads.forEach { info ->
                            val archiveFile = File("${OpenMWPaths.USER_FILE_STORAGE}/OpenMW/CACHE/${info.fileName}")
                            val filePath = File(
                                "${OpenMWPaths.USER_FILE_STORAGE}/OpenMW/CACHE",
                                info.fileName ?: "${mod.slug}.zip"
                            )
                            val fileExists = filePath.exists()
                            if (!info.directDownload.isNullOrBlank() || isPremium == false) {
                                HorizontalDivider(color = White, thickness = 1.dp)
                                Text(
                                    text = "${info.fileName}",
                                    color = MaterialTheme.colorScheme.secondary,
                                    style = MaterialTheme.typography.labelMedium
                                )
                                if (fileExists && info.extractedSizeBytes != null) {
                                    val sizeKb = info.extractedSizeBytes / 1024
                                    Text(
                                        text = "Extracted archive size: ${formatSize(sizeKb)}",
                                        color = MaterialTheme.colorScheme.secondary,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }

                                if (isDownloading) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        if (progressText != null) {
                                            Text("Downloading... $progressText")
                                        }
                                    }
                                } else {
                                    if (fileExists) {
                                        // Show both "Downloaded" text and Extract button
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = "Downloaded",
                                                color = MaterialTheme.colorScheme.secondary,
                                                style = MaterialTheme.typography.labelMedium
                                            )

                                            Spacer(modifier = Modifier.width(8.dp))

                                            Button(
                                                onClick = {
                                                    scope.launch {
                                                        val fileList = nativeGetArchiveFileList(archiveFile)
                                                        val extractTo = File("${OpenMWPaths.USER_FILE_STORAGE}/OpenMW/${modList}/${info.extractTo}")

                                                        if (info.extractedSizeBytes == null && fileExists) {
                                                            calculateAndUpdateSizeOnDisk(mod, info)
                                                        }

                                                        /*
                                                        val totalSize = ZipUtils.getArchiveTotalSize(archiveFile)
                                                        Log.i("ArchiveInfo", "Total extracted size: ${ZipUtils.formatFileSize(totalSize)}")
                                                         */

                                                        if (shouldSkipExtraction(fileList, extractTo)) {
                                                            Log.i("ExtractionDecision", "Skipping extraction for ${info.fileName}")
                                                        } else {
                                                            extractModFile(mod, info)
                                                        }
                                                    }
                                                },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = customColor, // Background color
                                                    contentColor = Color.White   // Text color
                                                )
                                            ) {
                                                Text("Extract")
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Button(
                                                onClick = {
                                                    scope.launch {
                                                        val fileName = info.fileName ?: "${mod.slug}.zip"
                                                        val destination = File(
                                                            "${OpenMWPaths.USER_FILE_STORAGE}/OpenMW/CACHE",
                                                            fileName
                                                        )

                                                        if (destination.exists()) {
                                                            val deleted = destination.delete()
                                                            if (deleted) {
                                                                // Update UI state to reflect deletion
                                                                // You might want to trigger a recomposition or update a state variable
                                                                Log.i("FileDelete", "Successfully deleted: ${destination.absolutePath}")
                                                            } else {
                                                                Log.e("FileDelete", "Failed to delete: ${destination.absolutePath}")
                                                            }
                                                        }
                                                    }
                                                },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = Color.Red, // Red color for delete action
                                                    contentColor = Color.White
                                                )
                                            ) {
                                                Text("Delete")
                                            }
                                        }
                                    } else {
                                        val fileKey = progressKey(mod, info)
                                        val fileProgress = downloadProgressMap[fileKey]
                                        val waitingInBrowser = NxmDownloads.isPending(mod.modId, info.nexusFileId)
                                        val partFile = File("${OpenMWPaths.USER_FILE_STORAGE}/OpenMW/CACHE/${info.fileName}.part")
                                        if (fileProgress != null && !fileProgress.contains("Failed")) {
                                            // Live status, so a long download never looks like nothing is happening.
                                            val percent = NexusInfo.downloadProgressPercentMap[fileKey] ?: 0
                                            val speed = NexusInfo.downloadSpeedMap[fileKey]
                                            Text(
                                                text = fileProgress + if (speed != null) "  $percent%  ($speed KB/s)" else "",
                                                style = MaterialTheme.typography.labelMedium
                                            )
                                            LinearProgressIndicator(
                                                progress = { percent / 100f },
                                                modifier = Modifier.fillMaxWidth().height(4.dp)
                                            )
                                            TextButton(onClick = { ModListManager.togglePaused(fileKey) }) {
                                                Text(if (ModListManager.isPaused(fileKey)) "Resume" else "Pause")
                                            }
                                        } else if (waitingInBrowser) {
                                            Text(
                                                text = "Waiting for you in the browser: press Slow download on the Nexus page.",
                                                style = MaterialTheme.typography.labelMedium
                                            )
                                        } else {
                                            if (fileProgress != null) {
                                                Text(fileProgress, color = Color.Red, style = MaterialTheme.typography.labelMedium)
                                            }
                                            if (partFile.exists()) {
                                                Text(
                                                    text = "Partial download (${partFile.length() / (1024 * 1024)} MB): Download continues it.",
                                                    style = MaterialTheme.typography.labelMedium
                                                )
                                            }
                                                Button(
                                                    onClick = {
                                                        scope.launch {
                                                            if (mod.url.contains("nexusmods.com")) {
                                                                if (isPremium == true) {
                                                                    downloadModFile(mod, info)
                                                                } else {
                                                                    NxmDownloads.open(mod, info)
                                                                }
                                                            } else {
                                                                downloadModFile(mod, info)
                                                            }
                                                        }
                                                    },
                                                    colors = ButtonDefaults.buttonColors(
                                                        containerColor = customColor, // Background color
                                                        contentColor = Color.White   // Text color
                                                    )
                                                ) {
                                                    Text("Download")
                                                }
                                        }
                                    }
                                }
                            }
                        }
                        HorizontalDivider(color = White, thickness = 1.dp)
                        Button(
                            onClick = onDelete,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = customColor, // Background color
                                contentColor = Color.White   // Text color
                            )
                        ) {
                            Text("Remove mod from list")
                        }
                    }
                }
            }
        }
    }

    // Fullscreen Image Dialog
    selectedImageUrl?.let { imageUrl ->
        Dialog(
            onDismissRequest = { selectedImageUrl = null },
            properties = DialogProperties(usePlatformDefaultWidth = false) // Fullscreen dialog
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.9f)) // Semi-transparent black background
                    .clickable(
                        onClick = { selectedImageUrl = null }, // Dismiss on background click
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = "Fullscreen Mod Image",
                    contentScale = ContentScale.Fit, // Fit to screen without cropping
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp) // Optional padding for better appearance
                )
                // Optional Close Button
                IconButton(
                    onClick = { selectedImageUrl = null },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .size(48.dp)
                        .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun InfoRow(label: String, value: String?, isLink: Boolean = false, fileName: String? = null) {
    if (value.isNullOrEmpty()) return
    val uriHandler = LocalUriHandler.current

    Row(
        modifier = Modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        if (isLink) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.primary),
                modifier = Modifier.clickable {
                    // The platform downloads direct links itself (Android's DownloadManager); otherwise the browser opens it
                    val host = host
                    if (host != null) host.openLink(value, label, fileName) else uriHandler.openUri(value)
                }
            )
        } else {
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/** Text that may carry HTML (a mod's author is often a link): shown without the tags. */
@Composable
fun HtmlText(html: String, style: TextStyle) {
    Text(text = stripHtml(html).replace("&amp;", "&"), style = style)
}
