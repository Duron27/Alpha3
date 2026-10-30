package org.alpha3.launcher.ui.page.mod

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.alpha3.launcher.tools.ModTool
import org.alpha3.launcher.tools.ModToolsRunner
import org.alpha3.launcher.tools.merge.DEFAULT_SKIPPED_TYPES
import org.alpha3.launcher.tools.merge.MERGEABLE_TYPES
import org.alpha3.launcher.utils.GameFilesPreferences
import org.alpha3.launcher.utils.Platform

/** Runs the Kotlin OpenMW tools (tes3cmd clean, record merge, groundcover, light fixes) on the user's load order. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModToolsScreen() {
    val features = Platform.features
    val fs = features.toolFs
    val platform = features.toolPlatform
    if (fs == null || platform == null) {
        Text("Mod tools are not available on this platform", modifier = Modifier.padding(16.dp))
        return
    }
    val runner = remember { ModToolsRunner(fs, platform) }
    val selected = remember { mutableStateMapOf(ModTool.MERGE to true, ModTool.GROUNDCOVER to true, ModTool.LIGHT_FIXES to true) }
    val logState = rememberLazyListState()
    val experimental by GameFilesPreferences.getPreference(GameFilesPreferences.NEW_FEATURE_ENABLED_KEY, false).collectAsState(initial = false)
    var confirmPrune by remember { mutableStateOf(false) }
    var lightSettings by remember { mutableStateOf(false) }
    var mergeSettings by remember { mutableStateOf(false) }
    val kotlinNavMesh by GameFilesPreferences.getPreference(GameFilesPreferences.KOTLIN_NAVMESH_KEY, false).collectAsState(initial = false)
    val prefScope = rememberCoroutineScope()

    val report = runner.pruneReport
    if (confirmPrune && report != null) {
        AlertDialog(
            onDismissRequest = { confirmPrune = false },
            title = { Text("Delete ${report.files.size} files?") },
            text = { Text("This frees ${report.bytes / (1024 * 1024)} MB. The files cannot be restored except by downloading the mods again, and mods that are updated or re-extracted may bring some back.") },
            confirmButton = { TextButton(onClick = { confirmPrune = false; runner.deleteOverridden() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmPrune = false }) { Text("Cancel") } },
        )
    }

    if (lightSettings) LightSettingsDialog(fs, platform) { lightSettings = false }
    if (mergeSettings) AlertDialog(
        onDismissRequest = { mergeSettings = false },
        title = { Text("Merge: record types") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("A type that is switched off is skipped by the merge (the same as --skip on DeltaPlugin). Cells and dialogue start off.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { MERGEABLE_TYPES.forEach { runner.mergeTypes[it.first] = true } }) { Text("All on") }
                    OutlinedButton(onClick = { MERGEABLE_TYPES.forEach { runner.mergeTypes[it.first] = false } }) { Text("All off") }
                    OutlinedButton(onClick = { MERGEABLE_TYPES.forEach { runner.mergeTypes[it.first] = it.first !in DEFAULT_SKIPPED_TYPES } }) { Text("Reset") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                    for ((tag, label) in MERGEABLE_TYPES) {
                        FilterChip(
                            selected = runner.mergeTypes[tag] != false,
                            onClick = { runner.mergeTypes[tag] = runner.mergeTypes[tag] == false },
                            label = { Text(label) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { mergeSettings = false }) { Text("Close") } },
    )

    LaunchedEffect(runner.log.size) {
        // The list may not have the new line yet when this runs; asking for an item it lacks crashes the layout
        val last = minOf(runner.log.size, logState.layoutInfo.totalItemsCount) - 1
        if (last >= 0) try { logState.animateScrollToItem(last) } catch (_: IndexOutOfBoundsException) {}
    }

    Column(modifier = Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val logEmpty = runner.log.isEmpty() && !runner.running
        LazyColumn(modifier = Modifier.weight(if (logEmpty) 1f else 0.6f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(ModTool.entries.toList()) { tool ->
                ToolRow(
                    title = tool.title,
                    description = tool.description,
                    leading = { Checkbox(checked = selected[tool] == true, onCheckedChange = { selected[tool] = it }, enabled = !runner.running) },
                    onSettings = if (tool == ModTool.LIGHT_FIXES || tool == ModTool.MERGE) {
                        { if (tool == ModTool.MERGE) mergeSettings = true else lightSettings = true }
                    } else null,
                    actions = { SmallAction("Run", !runner.running) { runner.start(listOf(tool)) } },
                    enabled = !runner.running,
                )
            }
            if (Alpha3Cache.available) item {
                Column {
                    ToolRow(
                        title = "Build Alpha3 cache",
                        description = "Converts the mod list's textures and makes every distant mesh and tree card now, so " +
                            "the game loads a new area as fast as one it has seen. Run it again after changing mods; what " +
                            "is already cached is skipped. It uses every core whatever [General] thermal mode says.",
                        actions = {
                            if (Alpha3Cache.running) SmallAction("Stop", true, Alpha3Cache::cancel)
                            else SmallAction("Build", !runner.running, Alpha3Cache::start)
                        },
                    )
                    // the progress shows whether the row is open or not
                    if (Alpha3Cache.status.isNotEmpty()) {
                        Text(
                            Alpha3Cache.status,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            item {
                ToolRow(
                    title = "Replace cleaned plugins",
                    description = "Clean plugins writes over the original plugins instead of next to them; the originals are backed up as <name>~1.esp.",
                    leading = { Switch(checked = runner.cleanOverwrite, onCheckedChange = { runner.cleanOverwrite = it }, enabled = !runner.running) },
                )
            }
            item {
                ToolRow(
                    title = "Kotlin navmesh generator (experimental)",
                    description = "Builds navmesh.db with the Kotlin port instead of the native openmw-navmeshtool when you generate the navmesh from the main menu. Off by default.",
                    leading = {
                        Switch(
                            checked = kotlinNavMesh,
                            onCheckedChange = { v -> prefScope.launch { GameFilesPreferences.setPreference(GameFilesPreferences.KOTLIN_NAVMESH_KEY, v) } },
                            enabled = !runner.running,
                        )
                    },
                    actions = { SmallAction("Generate", !runner.running, runner::generateNavMesh) },
                )
            }
            if (experimental) item {
                val report = runner.pruneReport
                ToolRow(
                    title = "Remove overridden files (experimental)",
                    description = "Finds mod files that a later mod in the load order replaces with a file of the same path. OpenMW never loads " +
                        "these, but deleting them is permanent, and changing the load order afterwards can bring them back into play. " +
                        "Plugins, archives and the Morrowind install are never touched.",
                    actions = {
                        SmallAction("Scan", !runner.running, runner::scanOverridden)
                        if (report != null && report.files.isNotEmpty()) {
                            SmallAction("Delete ${report.files.size} (${report.bytes / (1024 * 1024)} MB)", !runner.running) { confirmPrune = true }
                        }
                    },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { runner.start(ModTool.entries.filter { selected[it] == true }) },
                enabled = !runner.running && selected.values.any { it },
            ) { Text("Run selected") }
            if (runner.running) OutlinedButton(onClick = runner::cancel) { Text("Cancel") }
            else if (!logEmpty) OutlinedButton(onClick = runner::clearLog) { Text("Clear log") }
            Text(runner.status, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        }
        // The log only takes room once a tool has written to it
        if (!logEmpty) Box(modifier = Modifier.weight(0.4f).fillMaxWidth().background(Color.Black, RoundedCornerShape(8.dp)).padding(8.dp)) {
            LazyColumn(state = logState) {
                items(runner.log.toList()) { line ->
                    Text(line, color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

/**
 * One tool on a line: its switch or checkbox, its name, a gear for its settings and its buttons. The description is
 * folded away under the name (tap the name to read it), so the buttons never squeeze it into a narrow column.
 */
@Composable
private fun ToolRow(
    title: String,
    description: String,
    leading: (@Composable () -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    Surface(color = Color.White.copy(alpha = 0.05f), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                leading?.invoke()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f).clickable { open = !open }.padding(vertical = 8.dp, horizontal = 4.dp),
                ) {
                    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f, fill = false))
                    Icon(
                        if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (open) "Hide description" else "Show description",
                        tint = Color.Gray,
                        modifier = Modifier.size(18.dp),
                    )
                }
                if (onSettings != null) {
                    IconButton(onClick = onSettings, enabled = enabled, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", modifier = Modifier.size(20.dp))
                    }
                }
                actions?.invoke(this)
            }
            if (open) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.LightGray,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SmallAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        modifier = Modifier.height(34.dp),
    ) { Text(label, fontSize = 13.sp) }
}
