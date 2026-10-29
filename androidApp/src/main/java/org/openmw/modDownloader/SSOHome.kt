package org.openmw.modDownloader

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Color.Companion.White
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.alpha3.launcher.paths.OpenMWPaths
import org.alpha3.launcher.paths.Profiles
import org.openmw.R
import org.openmw.modDownloader.ModListManager.allowExtract
import org.openmw.modDownloader.ModListManager.apiKey
import org.openmw.modDownloader.ModListManager.availableLists
import org.openmw.modDownloader.ModListManager.brokenMods
import org.openmw.modDownloader.ModListManager.modList
import org.openmw.modDownloader.ModListManager.onlySync
import org.openmw.modDownloader.ModListManager.remainingMods
import org.openmw.modDownloader.ModListManager.selectedThreadCount
import org.openmw.modDownloader.ModListManager.validate
import org.openmw.modDownloader.NexusInfo.extractionProgressMap
import org.openmw.modDownloader.NexusInfo.extractionProgressPercentMap
import org.openmw.modDownloader.NexusInfo.isNexusExpanded
import org.openmw.modDownloader.SevenZip.isInitialized
import org.alpha3.launcher.ui.controls.UIStateManager.customColor
import org.alpha3.launcher.ui.controls.UIStateManager.gold
import org.alpha3.launcher.ui.view.ProgressDialog
import org.alpha3.launcher.ui.view.addCustomLog
import org.alpha3.launcher.ui.view.getAvailableStorageSpace
import org.alpha3.launcher.ui.view.getMessages
import org.alpha3.launcher.utils.GameFilesPreferences.IS_NEXUS_PREMIUM
import org.alpha3.launcher.utils.GameFilesPreferences.NEXUS_API_KEY
import org.alpha3.launcher.utils.GameFilesPreferences
import org.alpha3.launcher.utils.dataStore
import org.openmw.utils.stringRes
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.math.roundToInt

object StatusInfo {
    private var running by mutableStateOf(false)

    /** True while a mod list is being downloaded and installed; keeps the app alive in the background through [DownloadService]. */
    var processing: Boolean
        get() = running
        set(value) {
            if (running == value) return
            running = value
            DownloadService.sync(value)
        }
    var showProcessCompleteDialog by mutableStateOf(false)
    var activeMods by mutableIntStateOf(0)
    var pendingConfigPath: String? by mutableStateOf(null)
    /** The profile set up for the mod list that just finished; null for a sync-only run. */
    var installResult: ProfileInstall? by mutableStateOf(null)
}

@Composable
fun OnboardingScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .wrapContentHeight()
            .fillMaxWidth()
            .background(customColor)
            .padding(8.dp)
    ) {
        Column {
            Text(
                "Please enter your nexus API key,\nif nothing happens when you try to press the ok button the key is not valid ",
                style = MaterialTheme.typography.labelLarge,
                color = gold
            )
            OutlinedTextField(
                value = apiKey.replace("null", ""),
                onValueChange = {
                    apiKey = it
                },
                label = { Text(stringResource(R.string.nexus_api_key)) },
                modifier = Modifier
                    .height(100.dp)
                    .fillMaxWidth()
            )
            Spacer(modifier = Modifier.padding(8.dp))
            Row {
                TextButton(
                    onClick = {
                        NxmDownloads.openSignIn(context)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = gold,
                        contentColor = Color.Black
                    )
                ) {
                    Text("Sign in to nexus (If free user)")
                }
                Spacer(modifier = Modifier.weight(1f))

                TextButton(
                    onClick = {
                        scope.launch(Dispatchers.Default) {
                            try {
                                validate(context)
                            } catch (e: Exception) {
                                Log.e("VALIDATE_ERROR", "Validation failed: ${e.message}")
                                addCustomLog("VALIDATE_ERROR: Validation failed: ${e.message}", textSize = 10, textColor = Color.Cyan)
                            } finally {
                                dataStore.edit { preferences ->
                                    preferences[NEXUS_API_KEY] = apiKey
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = gold,
                        contentColor = Color.Black
                    )
                ) {
                    Text("OK")
                }
            }
        }
    }
}

@Composable
fun SSOHome() {
    val context = LocalContext.current
    val modDao = ModDatabase.getDatabase(context).modDao()
    var settings by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var showInitiatingDialog by rememberSaveable { mutableStateOf(false) }
    val fetchNexusAPI by GameFilesPreferences.getNullablePreference(NEXUS_API_KEY).collectAsState(initial = "")
    var showModsLog by remember { mutableStateOf(false) }
    var confirmCancel by remember { mutableStateOf(false) }
    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("Cancel the download?") },
            text = { Text("Running downloads stop and the waiting ones are dropped. Finished files are kept, and partly downloaded files continue where they stopped next time. Nothing is installed.") },
            confirmButton = { TextButton(onClick = { confirmCancel = false; ModListManager.cancelRun() }) { Text("Cancel download") } },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("Keep going") } }
        )
    }
    val logMessages = remember { mutableStateListOf<String>() }
    val isPremium by GameFilesPreferences.getNullablePreference(IS_NEXUS_PREMIUM).collectAsState(initial = null)
    var showLogs by remember { mutableStateOf(false) }
    val isValidated by remember {
        derivedStateOf { isPremium != null }
    }

    AnimatedVisibility(
        visible = showModsLog,
        enter = fadeIn() + expandIn(),
        exit = fadeOut() + shrinkOut()
    ) {
        Dialog(
            onDismissRequest = { showModsLog = false }
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text("Remaining mods: $remainingMods")

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        reverseLayout = true // Newest at the top
                    ) {
                        items(logMessages.reversed()) { message ->
                            Text(
                                text = message,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                color = when {
                                    message.contains("ERROR", ignoreCase = true) -> Color.Red
                                    message.contains("WARN", ignoreCase = true) -> Color.Yellow
                                    message.contains("INFO", ignoreCase = true) -> Color.Green
                                    else -> White
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 1.dp)
                            )
                        }
                    }

                    Button(
                        onClick = { showModsLog = false },
                        modifier = Modifier.align(Alignment.End),
                        colors = ButtonDefaults.buttonColors(containerColor = gold)
                    ) {
                        Text("Close")
                    }
                }
            }
        }
    }

    LaunchedEffect(showModsLog) {
        while (showModsLog) {
            logMessages.clear()
            logMessages.addAll(getMessages())
            delay(1000.milliseconds) // Refresh every second
        }
    }

    LaunchedEffect(Unit) {
        launch(Dispatchers.Default) {
            // The lists land in the database, which the screens observe; the returned list isn't needed
            ModListManager.downloadAndParseModList(context)

            val potiFile = File("${OpenMWPaths.USER_FILE_STORAGE}/OpenMW/POTI.modlist")
            if (potiFile.exists()) {
                val count = importPotiModlistStructured(context, potiFile.path)
                Log.d("MAIN", "Imported $count POTI mods")
                addCustomLog("SSOHome: Imported $count POTI mods", textSize = 10, textColor = Color.Cyan)

                // Append to the list
                if (!availableLists.contains("poti")) {
                    availableLists.add("poti")
                }
            } else {
                Log.w("MAIN", "POTI.modlist file not found at ${potiFile.path}")
                addCustomLog("SSOHome: POTI.modlist file not found at ${potiFile.path}", textSize = 10, textColor = Color.Cyan)
            }
        }

        println("Entry nexusAPI: $fetchNexusAPI")
        addCustomLog("Entry nexusAPI: $fetchNexusAPI", textSize = 10, textColor = Color.Cyan)
        println("Entry isNowPremium: $isPremium")
        addCustomLog("Entry isNowPremium: $isPremium", textSize = 10, textColor = Color.Cyan)
        println("Entry isValidated: $isValidated")
        addCustomLog("Entry isValidated: $isValidated", textSize = 10, textColor = Color.Cyan)
    }

    LaunchedEffect(fetchNexusAPI, isPremium) {
        apiKey = "$fetchNexusAPI"
        println("nexusAPI changed: $fetchNexusAPI")
        println("apiKey changed: $apiKey")
        println("isNowPremium changed: $isPremium")
        println("isValidated Changed: $isValidated")
    }

    isInitialized()

    if (showInitiatingDialog) {
        ProgressDialog(
            text = stringRes(R.string.loading)
        )
    }

    if (showLogs) {
        LogViewerDialog(onDismiss = { showLogs = false })
    }

    if (StatusInfo.showProcessCompleteDialog) {
        val result = StatusInfo.installResult
        val close = { StatusInfo.installResult = null; StatusInfo.showProcessCompleteDialog = false }
        AlertDialog(
            onDismissRequest = { close() },
            title = {
                Text(
                    when {
                        result == null -> "Processing Complete"
                        ModListManager.failedFiles > 0 -> "${result.modList}: ${ModListManager.failedFiles} files missing"
                        else -> "${result.modList} installed"
                    },
                    color = gold
                )
            },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("${StatusInfo.activeMods} mods processed.", color = White)
                    if (ModListManager.failedFiles > 0) {
                        Text(
                            "${ModListManager.failedFiles} files could not be downloaded or unpacked, so the list is not complete. The mod cards show which ones; press Download on them (or the whole list again) to retry.",
                            color = Color.Red, fontSize = 12.sp
                        )
                    }
                    if (brokenMods.isNotEmpty() && !brokenMods.trim().endsWith(": 0")) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(brokenMods, color = Color.Red, fontSize = 12.sp)
                    }
                    if (result != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = White.copy(alpha = 0.3f))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Profile: ${result.profile}", color = gold, fontWeight = FontWeight.Bold)
                        Text(result.folder, color = White.copy(alpha = 0.7f), fontSize = 10.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            if (result.settingsCopied) "settings.cfg: copied from your default profile."
                            else "settings.cfg: the profile already had its own, so it was kept.",
                            color = White, fontSize = 12.sp
                        )
                        if (result.cfgInstalled) {
                            Text(
                                "openmw.cfg: replaced with ${result.modList}.cfg. It already lists Morrowind, the expansions and every mod, so nothing is added to it.",
                                color = White, fontSize = 12.sp
                            )
                        }
                        if (result.disabledEntries > 0) {
                            Text(
                                "${result.disabledEntries} openmw.cfg entries point at files that were not found and were switched off. See the log.",
                                color = Color.Yellow, fontSize = 12.sp
                            )
                        }
                        if (result.problem != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(result.problem, color = Color.Red, fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Saves, screenshots and the navmesh of this list are kept in the profile folder.", color = White.copy(alpha = 0.8f), fontSize = 11.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            if (Profiles.active == result.profile) "This profile is active."
                            else "Active profile: ${if (Profiles.isDefault) "Default" else Profiles.active}. Switch now to play this list.",
                            color = White
                        )
                    }
                }
            },
            confirmButton = {
                Row {
                    if (result != null && Profiles.active != result.profile) {
                        TextButton(onClick = {
                            Profiles.switchTo(result.profile)
                            close()
                        }) {
                            Text("Switch to ${result.profile}", color = Color.Green)
                        }
                    }
                    TextButton(onClick = {
                        StatusInfo.showProcessCompleteDialog = false
                        showModsLog = true
                    }) {
                        Text("Review Logs", color = gold)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { close() }) {
                    Text(if (result != null && Profiles.active != result.profile) "Stay on current" else "Close", color = White)
                }
            },
            containerColor = customColor,
            textContentColor = White
        )
    }

    if (settings && isValidated) {
        AlertDialog(
            onDismissRequest = { settings = false },
            confirmButton = {
                TextButton(onClick = { settings = false }) {
                    Text("OK", color = gold)
                }
            },
            dismissButton = {
                TextButton(onClick = { settings = false }) {
                    Text("Cancel", color = gold)
                }
            },
            title = {
                Text("Download Options", color = gold)
            },
            text = {
                Column {
                    Text(
                        brokenMods,
                        style = MaterialTheme.typography.labelSmall
                    )
                    HorizontalDivider(thickness = 2.dp)
                    if (!onlySync) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (allowExtract) "Allow Archive Extraction" else "Download Only", color = gold
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Switch(
                                checked = allowExtract,
                                onCheckedChange = { newValue ->
                                    allowExtract = newValue
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = gold,
                                    uncheckedThumbColor = gold.copy(alpha = 0.5f),
                                    checkedTrackColor = Color.Black.copy(alpha = 0.9f),
                                    uncheckedTrackColor = Color.Black.copy(alpha = 0.5f),
                                    checkedBorderColor = White,
                                    uncheckedBorderColor = White
                                )
                            )
                        }
                    }
                    HorizontalDivider(thickness = 1.dp)
                    val deleteArchives by GameFilesPreferences.getPreference(GameFilesPreferences.DELETE_ARCHIVES_KEY, false).collectAsState(initial = false)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Delete downloads once installed", color = gold)
                        Spacer(modifier = Modifier.weight(1f))
                        Switch(
                            checked = deleteArchives,
                            onCheckedChange = { newValue ->
                                scope.launch { GameFilesPreferences.setPreference(GameFilesPreferences.DELETE_ARCHIVES_KEY, newValue) }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = gold,
                                uncheckedThumbColor = gold.copy(alpha = 0.5f),
                                checkedTrackColor = Color.Black.copy(alpha = 0.9f),
                                uncheckedTrackColor = Color.Black.copy(alpha = 0.5f),
                                checkedBorderColor = White,
                                uncheckedBorderColor = White
                            )
                        )
                    }
                    HorizontalDivider(thickness = 1.dp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (onlySync) "Only do the sync process" else "Sync, Download, and Extract", color = gold
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Switch(
                            checked = onlySync,
                            onCheckedChange = { newValue ->
                                onlySync = newValue
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = gold,
                                uncheckedThumbColor = gold.copy(alpha = 0.5f),
                                checkedTrackColor = Color.Black.copy(alpha = 0.9f),
                                uncheckedTrackColor = Color.Black.copy(alpha = 0.5f),
                                checkedBorderColor = White,
                                uncheckedBorderColor = White
                            )
                        )
                    }
                    if (ModListManager.cpuThreadCount > 1) {
                        HorizontalDivider(thickness = 1.dp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "Sync threads: ${ModListManager.syncThreadCount}", color = gold)
                            Slider(
                                value = ModListManager.syncThreadCount.toFloat(),
                                onValueChange = { ModListManager.syncThreadCount = it.roundToInt() },
                                valueRange = 1f..ModListManager.cpuThreadCount.toFloat(),
                                steps = (ModListManager.cpuThreadCount - 2).coerceAtLeast(0),
                                colors = SliderDefaults.colors(
                                    thumbColor = gold,
                                    activeTrackColor = gold,
                                    inactiveTrackColor = gold.copy(alpha = 0.3f)
                                ),
                                modifier = Modifier.weight(1f).padding(start = 12.dp)
                            )
                        }
                    }
                    HorizontalDivider(thickness = 2.dp)
                    HorizontalDivider(color = Color.Transparent, thickness = 20.dp)
                    HorizontalDivider(thickness = 2.dp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    modDao.clearAllMods()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(2.dp, White)
                                .background(Color.Red.copy(alpha = 0.2f)),
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = White
                            )
                        ) {
                            Text(
                                "Wipe database",
                                style = TextStyle(color = gold)
                            )
                        }
                    }
                    HorizontalDivider(thickness = 2.dp)
                    HorizontalDivider(color = Color.Transparent, thickness = 20.dp)
                    if (fetchNexusAPI != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.approval_delegation_24dp_e8eaed_fill0_wght400_grad0_opsz24),
                                contentDescription = "Checkmark",
                                colorFilter = ColorFilter.tint(Color.Green),
                                modifier = Modifier
                                    .size(36.dp)
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Button(
                                onClick = {
                                    // Remove the API key and set it to an empty string
                                    scope.launch {
                                        dataStore.edit { preferences ->
                                            preferences.remove(IS_NEXUS_PREMIUM)
                                            preferences.remove(NEXUS_API_KEY)
                                        }
                                        apiKey = ""
                                        settings = false
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = customColor, // Background color
                                    contentColor = White   // Text color
                                )
                            ) {
                                Text(stringResource(R.string.remove_api_key), color = gold)
                            }
                        }
                    } else {
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = { Text(stringResource(R.string.nexus_api_key)) },
                            modifier = Modifier
                                .height(100.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                // Save the value to DataStore
                                scope.launch {
                                    dataStore.edit { preferences ->
                                        preferences[NEXUS_API_KEY] = apiKey
                                    }
                                    validate(context)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = customColor, // Background color
                                contentColor = White   // Text color
                            )
                        ) {
                            Text(stringResource(R.string.save), color = gold)
                        }
                    }
                }
            }
        )
    }

    if (isValidated) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(10.dp)
                .keepScreenOn()
        ) {
            Row(
                modifier = Modifier
                    .height(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = "Settings",
                    modifier = Modifier
                        .size(48.dp)
                        .clickable {
                            settings = !settings
                        }
                )
                Spacer(modifier = Modifier.width(8.dp))
                AvailableModLists(modDao = modDao)
                Spacer(modifier = Modifier.weight(1f))
                if (StatusInfo.processing) {
                    IconButton(onClick = { confirmCancel = true }, modifier = Modifier.size(46.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel the download",
                            tint = gold,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(64.dp)
                ) {
                    if (!StatusInfo.processing) {
                        IconButton(
                            onClick = {
                                settings = false
                                isNexusExpanded = true
                                StatusInfo.processing = true
                                ModListManager.runJob = scope.launch(Dispatchers.Default) {
                                    processModList(context, isPremium!!).collect { processedMods ->
                                        // Optional: Log or handle processed mods
                                        Log.d(
                                            "RateLimitDisplay",
                                            "Processed ${processedMods.size} mods"
                                        )
                                    }
                                }
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        val jsonString =
                                            fetchJsonFromUrl("https://modding-openmw.com/api/cfg-generator/$modList")
                                        val config = parseOpenMWConfig(jsonString)
                                        val formattedConfig = formatOpenMWConfig(context, config)
                                        val savedFile = saveConfigToFile(
                                            "$modList.cfg",
                                            formattedConfig
                                        )
                                        StatusInfo.pendingConfigPath = savedFile.absolutePath
                                        Log.d("ConfigSave", "Saved to ${savedFile.absolutePath}")
                                    } catch (e: Exception) {
                                        Log.e("ConfigFetch", "Error fetching config: ${e.message}")
                                    }
                                }
                            },
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(customColor)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "Download",
                                tint = gold,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    } else {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(46.dp)
                                .clickable { showModsLog = true }
                        ) {
                            CircularProgressIndicator(
                                color = gold,
                                modifier = Modifier.size(46.dp)
                            )
                            Text(
                                text = "$remainingMods",
                                color = gold, // or Color.White for better contrast
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
            RateLimitDisplay()
            ModListScreen()
        }
    } else {
        OnboardingScreen()
    }
}

@Composable
fun AvailableModLists(modDao: ModDao) {
    var expanded by remember { mutableStateOf(false) }

    // Get all available lists (hardcoded + JSON)
    val allAvailableLists = getAllAvailableLists(modDao)

    // Filter out lists with "-wip" in their name
    val filteredLists = allAvailableLists.filter { !it.contains("-wip") }

    // Collect all mods as state
    val mods by modDao.getAllModsFlow().collectAsState(initial = emptyList())

    // Compute counts per list
    val modCounts = remember(mods) {
        val counts = mutableMapOf<String, Int>()
        mods.forEach { mod ->
            mod.onLists.forEach { listName ->
                counts[listName] = (counts[listName] ?: 0) + 1
            }
        }
        counts
    }

    Box(
        modifier = Modifier
            .wrapContentSize()
    ) {
        Row(
            modifier = Modifier
                .wrapContentSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { expanded = true },
                modifier = Modifier
                    .wrapContentSize(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = customColor,
                    contentColor = White,
                    disabledContainerColor = Color.Gray,
                    disabledContentColor = Color.DarkGray
                ),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, gold)
            ) {
                Text(modList)
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .background(customColor)
                .border(1.dp, White)
        ) {
            filteredLists.forEach { option ->
                val count = modCounts[option] ?: 0
                val sizeForThisListKb = mods
                    .filter { it.onLists.contains(option) }
                    .flatMap { it.downloadInfo }
                    .mapNotNull { it.fileSize?.toLongOrNull() }
                    .sum()

                val extractionSizeBytes = mods
                    .filter { it.onLists.contains(option) }
                    .flatMap { it.downloadInfo }
                    .mapNotNull { it.extractedSizeBytes }
                    .sum()

                DropdownMenuItem(
                    text = {
                        Column {
                            // Highlight custom lists from JSON files
                            val isCustomList = option !in availableLists

                            Text(
                                text = if (isCustomList) "📁 $option (Mods: $count)" else "$option (Mods: $count)",
                                color = if (isCustomList) Color(0xFF4CAF50) else White
                            )
                            Text(
                                "Download Size: ${formatSize(sizeForThisListKb)}",
                                style = MaterialTheme.typography.labelSmall
                            )
                            Text(
                                "Extracted Size: ${formatSize(extractionSizeBytes / 1024)}",
                                style = MaterialTheme.typography.labelSmall
                            )
                            HorizontalDivider(color = White, thickness = 1.dp)
                        }
                    },
                    onClick = {
                        modList = option
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun getAllAvailableLists(modDao: ModDao): List<String> {

    // Collect all mods to extract lists from JSON files
    val mods by modDao.getAllModsFlow().collectAsState(initial = emptyList())

    // Extract unique list names from all mods
    val jsonLists = remember(mods) {
        mods.flatMap { it.onLists }.distinct()
    }

    // Combine and remove duplicates
    return remember(mods) {
        (availableLists + jsonLists).distinct().sorted()
    }
}

@Composable
fun RateLimitDisplay() {
    val context = LocalContext.current
    val availableSpace = getAvailableStorageSpace()
    val modDao = remember { ModDatabase.getDatabase(context).modDao() }
    var totalMods by remember { mutableIntStateOf(0) }
    val isPremium by GameFilesPreferences.getNullablePreference(IS_NEXUS_PREMIUM).collectAsState(initial = null)

    LaunchedEffect(Unit) {
        totalMods = modDao.getModCount()
    }

    Column(
        modifier = Modifier
            .padding(8.dp)
            .background(
                color = when {
                    NexusInfo.isNearLimit -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                shape = MaterialTheme.shapes.small
            )
            .animateContentSize()
            .clickable { isNexusExpanded = !isNexusExpanded } // Toggle on click
            .padding(8.dp)
            .verticalScroll(rememberScrollState())
    ) {
        if (isNexusExpanded) {
            // Expanded state: Show full content
            // Title with premium badge
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Nexus API Limits",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f)
                )
                if (isPremium== true) {
                    Badge(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                    ) {
                        Text("Premium")
                    }
                } else {
                    Badge(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                    ) {
                        Text("Free User")
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            RateLimitBar(
                name = "Hourly",
                remaining = NexusInfo.hourlyremaining,
                limit = NexusInfo.hourlylimit,
                resetText = NexusInfo.hourlyResetFormatted,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            RateLimitBar(
                name = "Daily",
                remaining = NexusInfo.dailyremaining,
                limit = NexusInfo.dailylimit,
                resetText = NexusInfo.dailyResetFormatted,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.height(8.dp))


            Text(
                text = "Free Space: $availableSpace    Cached Mods: $totalMods    $selectedThreadCount Threads Detected",
                color = White,
                fontSize = 12.sp
            )
            HorizontalDivider(color = White, thickness = 1.dp)

            DownloadActivity()

            // Warning message when near limit
            if (NexusInfo.isNearLimit) {
                Text(
                    text = "Only ${NexusInfo.dailyremaining} Nexus requests left today.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        } else {
            // Collapsed state: Show only hourly and daily limits in a Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Hourly: ${NexusInfo.hourlyremaining}/${NexusInfo.hourlylimit}",
                    style = MaterialTheme.typography.labelMedium
                )
                Text(
                    text = "Daily: ${NexusInfo.dailyremaining}/${NexusInfo.dailylimit}",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

@Composable
private fun RateLimitBar(
    name: String,
    remaining: Int,
    limit: Int,
    resetText: String,
    color: Color
) {
    val percentage = remaining.toFloat() / limit

    Column {
        // Label and count
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Resets in: $resetText",
                    style = MaterialTheme.typography.labelSmall,
                    color = color.copy(alpha = 0.7f)
                )
            }
            Text(
                text = "$remaining / $limit",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (percentage < 0.2f) FontWeight.Bold else FontWeight.Normal
            )
        }

        // Progress bar
        LinearProgressIndicator(
            progress = { percentage },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .padding(top = 4.dp),
            color = color,
            trackColor = color.copy(alpha = 0.2f)
        )

        // Percentage text
        Text(
            text = "${(percentage * 100).toInt()}% remaining",
            style = MaterialTheme.typography.labelSmall,
            color = color.copy(alpha = 0.8f),
            modifier = Modifier.align(Alignment.End)
        )
    }
}
