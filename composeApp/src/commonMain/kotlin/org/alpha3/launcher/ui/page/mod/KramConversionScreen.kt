package org.alpha3.launcher.ui.page.mod

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.alpha3.launcher.io.File
import org.alpha3.launcher.paths.OpenMWPaths
import org.alpha3.launcher.resources.Res
import org.alpha3.launcher.resources.total_used_memory
import org.alpha3.launcher.tools.KramBatch
import org.alpha3.launcher.tools.KramTool
import org.alpha3.launcher.ui.view.getMemoryInfo
import org.alpha3.launcher.utils.FileBrowserMode
import org.alpha3.launcher.utils.FileBrowserPopup
import org.alpha3.launcher.utils.PlatformContext
import org.alpha3.launcher.utils.stringRes
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

private enum class KramState { IDLE, RUNNING, COMPLETED }

/** The BSA archive to unpack and where to. */
private data class BsaSource(
    val archive: String = "",
    val outputDir: String = "",
    /** Unpack every file, not only the textures. */
    val everything: Boolean = false,
)

private val compactButtonPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)

/** The folder the converter reads by default. */
private val modsDir get() = "${OpenMWPaths.USER_FILE_STORAGE}/OpenMW/Mods"

/** The top of the shared storage (/storage/emulated/0 on Android), where the file browsers start. */
private val storageRoot get() = File(OpenMWPaths.USER_FILE_STORAGE).parentFile ?: File(OpenMWPaths.USER_FILE_STORAGE)

/**
 * Texture converter: DDS -> ASTC KTX for every texture under the mods folder, or for the textures of a BSA archive,
 * via [KramTool]. Laid out for a small screen: the controls scroll, the log keeps a fixed share of the height.
 */
@Composable
fun KramConversionScreen(context: PlatformContext, runner: KramTool.Runner?) {
    if (runner == null) {
        Text("Texture conversion is not available on this platform.", modifier = Modifier.padding(16.dp))
        return
    }

    var state by remember { mutableStateOf(KramState.IDLE) }
    var logs by remember { mutableStateOf<List<KramBatch.LogMessage>>(emptyList()) }
    var progress by remember { mutableStateOf(KramBatch.Progress()) }
    var elapsedSeconds by remember { mutableStateOf(0.0) }
    var config by remember { mutableStateOf(KramBatch.Config()) }
    var memory by remember { mutableStateOf("") }
    var settingsOpen by remember { mutableStateOf(false) }
    var bsa by remember { mutableStateOf(BsaSource()) }
    var bsaDialog by remember { mutableStateOf(false) }
    var sourceDir by remember { mutableStateOf(modsDir) }
    var pickingFolder by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val running = state == KramState.RUNNING

    // one place that runs a job and keeps the progress, log and timer state in step
    fun start(job: suspend () -> Unit) {
        scope.launch {
            state = KramState.RUNNING
            logs = emptyList()
            progress = KramBatch.Progress()
            elapsedSeconds = 0.0
            job()
            state = KramState.COMPLETED
        }
    }
    val onLog: (KramBatch.LogMessage) -> Unit = { logs = (logs + it).takeLast(200) }

    LaunchedEffect(Unit) {
        while (true) {
            val info = getMemoryInfo(context)
            memory = "${stringRes(Res.string.total_used_memory)}: ${info.usedMemory} / ${info.totalMemory}"
            delay(1000.milliseconds)
        }
    }

    // Elapsed time ticks while a batch runs, so the time-left estimate keeps counting down
    LaunchedEffect(state) {
        if (state == KramState.RUNNING) {
            val begin = TimeSource.Monotonic.markNow()
            while (true) {
                elapsedSeconds = begin.elapsedNow().inWholeMilliseconds / 1000.0
                delay(500.milliseconds)
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
        // the log gets about a third of the height, the controls above it scroll if they do not fit
        val logHeight = (maxHeight * 0.35f).coerceIn(96.dp, 280.dp)

        Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                SettingsSummary(config, memory, settingsOpen) { settingsOpen = !settingsOpen }
                AnimatedVisibility(settingsOpen) {
                    SettingsPanel(config, enabled = !running) { config = it }
                }

                FolderRow(sourceDir, enabled = !running, onPick = { pickingFolder = true }, onReset = { sourceDir = modsDir })

                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = {
                            start {
                                KramBatch.run(
                                    runner = runner,
                                    baseDir = sourceDir,
                                    config = config,
                                    onProgress = { progress = it },
                                    onLog = onLog,
                                )
                            }
                        },
                        enabled = !running,
                        contentPadding = compactButtonPadding,
                        modifier = Modifier.weight(1f),
                    ) { Text("Convert folder", maxLines = 1) }
                    FilledTonalButton(
                        onClick = { bsaDialog = true },
                        enabled = !running,
                        contentPadding = compactButtonPadding,
                        modifier = Modifier.weight(1f),
                    ) { Text("From BSA…", maxLines = 1) }
                    TextButton(
                        onClick = {
                            state = KramState.IDLE
                            logs = emptyList()
                            progress = KramBatch.Progress()
                            elapsedSeconds = 0.0
                        },
                        enabled = !running,
                        contentPadding = compactButtonPadding,
                    ) { Text("Clear") }
                }

                if (state != KramState.IDLE) {
                    ProgressStrip(state, progress, elapsedSeconds)
                }
            }

            Spacer(Modifier.height(6.dp))
            LogPanel(logs, Modifier.fillMaxWidth().height(logHeight))
        }
    }

    if (pickingFolder) {
        FileBrowserPopup(
            initialDirectory = File(sourceDir).takeIf { it.exists() } ?: storageRoot,
            mode = FileBrowserMode.FOLDER,
            onDismiss = { pickingFolder = false },
            onFolderSelected = { folder ->
                pickingFolder = false
                sourceDir = folder.path
            },
        )
    }

    if (bsaDialog) {
        BsaDialog(
            source = bsa,
            onChange = { bsa = it },
            onDismiss = { bsaDialog = false },
            onStart = {
                bsaDialog = false
                start {
                    KramBatch.runFromBsa(
                        runner = runner,
                        bsaPath = bsa.archive,
                        outDir = bsa.outputDir,
                        config = config,
                        everything = bsa.everything,
                        onProgress = { progress = it },
                        onLog = onLog,
                    )
                }
            },
        )
    }
}

/** The folder that gets converted, with every folder inside it. Tap Folder to pick another. */
@Composable
private fun FolderRow(path: String, enabled: Boolean, onPick: () -> Unit, onReset: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            path,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.StartEllipsis,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        if (path != modsDir) {
            TextButton(onClick = onReset, enabled = enabled, contentPadding = compactButtonPadding) { Text("Mods") }
        }
        TextButton(onClick = onPick, enabled = enabled, contentPadding = compactButtonPadding) { Text("Folder…") }
    }
}

// region Settings

/** One line: the current settings, tap to open them. The memory use sits on the right. */
@Composable
private fun SettingsSummary(config: KramBatch.Config, memory: String, open: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = onToggle, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)) {
            Text(
                "${config.blockSize.arg} · q${config.astcQuality} · min ${config.sizeThreshold}px" +
                    if (config.inPlace) " · replace" else "",
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = if (open) "Hide settings" else "Show settings")
        }
        Text(
            memory,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).padding(start = 8.dp),
        )
    }
}

@Composable
private fun SettingsPanel(config: KramBatch.Config, enabled: Boolean, onChange: (KramBatch.Config) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = config.sizeThreshold.toString(),
                onValueChange = { v -> v.toIntOrNull()?.let { onChange(config.copy(sizeThreshold = it)) } },
                label = { Text("Min size px") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
            BlockSizeMenu(config.blockSize, enabled, Modifier.weight(1f)) { onChange(config.copy(blockSize = it)) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Quality ${config.astcQuality}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(84.dp))
            Slider(
                value = config.astcQuality.toFloat(),
                onValueChange = { onChange(config.copy(astcQuality = it.toInt())) },
                valueRange = 0f..100f,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SwitchRow("Delete DDS", config.inPlace, enabled, Modifier.weight(1f)) { onChange(config.copy(inPlace = it)) }
            SwitchRow("Keep backup", config.backupOriginal, enabled, Modifier.weight(1f)) { onChange(config.copy(backupOriginal = it)) }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 6.dp), maxLines = 2)
    }
}

/** The block size as a small button that opens a menu, instead of a full-width field. */
@Composable
private fun BlockSizeMenu(current: KramTool.AstcFormat, enabled: Boolean, modifier: Modifier = Modifier, onChange: (KramTool.AstcFormat) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(current.arg, maxLines = 1)
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Block size")
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            KramBatch.BLOCK_SIZES.forEach { option ->
                DropdownMenuItem(text = { Text(option.arg) }, onClick = { expanded = false; onChange(option) })
            }
        }
    }
}

// endregion

// region Progress and log

@Composable
private fun ProgressStrip(state: KramState, progress: KramBatch.Progress, elapsedSeconds: Double) {
    val running = state == KramState.RUNNING
    val percent = if (state == KramState.COMPLETED) 100 else (progress.fraction * 100).toInt()
    val eta = if (running && progress.phase == "Converting") progress.etaSeconds(elapsedSeconds) else null
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "${progress.phase} ${progress.completedFiles}/${progress.totalFiles}",
                style = MaterialTheme.typography.labelLarge,
            )
            Text("$percent%", style = MaterialTheme.typography.labelLarge)
        }
        if (running && progress.totalFiles == 0) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp)) // still searching for textures
        } else {
            LinearProgressIndicator(
                progress = { if (state == KramState.COMPLETED) 1f else progress.fraction },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Elapsed ${formatDuration(elapsedSeconds)}", style = MaterialTheme.typography.labelSmall, color = muted)
            Text(
                when {
                    state == KramState.COMPLETED -> "Done"
                    eta != null -> "~${formatDuration(eta)} left"
                    running && progress.phase == "Converting" -> "Estimating…"
                    else -> ""
                },
                style = MaterialTheme.typography.labelSmall,
                color = muted,
            )
        }
        if (running && progress.inFlight.isNotEmpty()) {
            Text(
                progress.inFlight.joinToString(", "),
                style = MaterialTheme.typography.labelSmall,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LogPanel(logs: List<KramBatch.LogMessage>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.scrollToItem(logs.lastIndex)
    }
    Surface(modifier, tonalElevation = 4.dp, shape = MaterialTheme.shapes.small) {
        LazyColumn(state = listState, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
            if (logs.isEmpty()) {
                item {
                    Text("Log", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                items(logs) { entry ->
                    Text(
                        entry.message,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        color = when (entry.type) {
                            KramBatch.LogType.INFO -> MaterialTheme.colorScheme.onSurface
                            KramBatch.LogType.SUCCESS -> MaterialTheme.colorScheme.primary
                            KramBatch.LogType.WARNING -> MaterialTheme.colorScheme.onSurfaceVariant
                            KramBatch.LogType.ERROR -> MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
        }
    }
}

/** 75 -> "1:15", 3700 -> "1:01:40" */
private fun formatDuration(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val sec = total % 60
    fun two(n: Long) = if (n < 10) "0$n" else n.toString()
    return if (h > 0) "$h:${two(m)}:${two(sec)}" else "$m:${two(sec)}"
}

// endregion

// region BSA

private enum class Browsing { ARCHIVE, FOLDER }

/** "Extract a BSA, then convert what came out": a Morrowind archive is unpacked to a folder and kram converts it. */
@Composable
private fun BsaDialog(source: BsaSource, onChange: (BsaSource) -> Unit, onDismiss: () -> Unit, onStart: () -> Unit) {
    var browsing by remember { mutableStateOf<Browsing?>(null) }

    // while a browser is open the dialog steps aside, so the browser is not stacked on top of it
    if (browsing == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("From a BSA archive") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "Unpacks the textures of a Morrowind .bsa into a folder and converts them with the settings you chose. " +
                            "Only uncompressed Morrowind archives are supported.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    PathRow("BSA archive", source.archive, onChange = { onChange(source.copy(archive = it)) }) { browsing = Browsing.ARCHIVE }
                    PathRow("Output folder", source.outputDir, onChange = { onChange(source.copy(outputDir = it)) }) { browsing = Browsing.FOLDER }
                    SwitchRow("Unpack every file, not only textures", source.everything, true) { onChange(source.copy(everything = it)) }
                }
            },
            confirmButton = {
                Button(onClick = onStart, enabled = source.archive.isNotBlank() && source.outputDir.isNotBlank()) {
                    Text("Extract and convert")
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
    }

    when (browsing) {
        Browsing.ARCHIVE -> FileBrowserPopup(
            initialDirectory = File(source.archive).parentFile?.takeIf { it.exists() } ?: storageRoot,
            mode = FileBrowserMode.FILE,
            onDismiss = { browsing = null },
            onFileSelected = { file ->
                browsing = null
                // suggest a folder named after the archive in the mods folder unless one was chosen already
                onChange(source.copy(archive = file.path, outputDir = source.outputDir.ifBlank { "$modsDir/${file.nameWithoutExtension}" }))
            },
        )
        Browsing.FOLDER -> FileBrowserPopup(
            initialDirectory = File(source.outputDir).takeIf { it.exists() } ?: storageRoot,
            mode = FileBrowserMode.FOLDER,
            onDismiss = { browsing = null },
            onFolderSelected = { folder ->
                browsing = null
                onChange(source.copy(outputDir = folder.path))
            },
        )
        null -> {}
    }
}

@Composable
private fun PathRow(label: String, value: String, onChange: (String) -> Unit, onBrowse: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = onBrowse, contentPadding = compactButtonPadding) { Text("Browse") }
    }
}

// endregion
