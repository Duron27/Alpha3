package org.alpha3.launcher.tools.moddev

import androidx.compose.foundation.BorderStroke
import kotlinx.coroutines.IO
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import org.alpha3.launcher.io.File
import org.alpha3.launcher.tools.ModTool
import org.alpha3.launcher.tools.ModToolsRunner
import org.alpha3.launcher.tools.openmw.ToolFs
import org.alpha3.launcher.tools.openmw.ToolPlatform
import org.alpha3.launcher.tools.render.AssetViewerDialog
import org.alpha3.launcher.tools.audio.AudioPlayer
import org.alpha3.launcher.tools.audio.playableAudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.alpha3.launcher.tools.pluginquery.QueryMode

private val Gold = Color(202, 165, 96)
private val Muted = Color.White.copy(alpha = 0.65f)
private val Green = Color(0xFF4CAF50)
private val Red = Color(0xFFE57373)
private val Blue = Color(0xFF81D4FA)

/**
 * Developer window for one entry of openmw.cfg (a data folder, or a content/groundcover plugin): what the
 * entry contains, what it conflicts with, and every tool the launcher has for it. Shown from a mod card's
 * popup when the devOptions preference is on.
 *
 * @param pluginFile the plugin on disk for content/groundcover entries, if it was found.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModDevToolsDialog(
    category: String,
    value: String,
    pluginFile: File?,
    cfgFile: File,
    fs: ToolFs,
    platform: ToolPlatform,
    onDismiss: () -> Unit,
) {
    val ops = remember(fs, platform) { ModDevOps(fs, platform) }
    val runner = remember(fs, platform) { ModToolsRunner(fs, platform) }
    val isData = category == "data"
    // fallback-archive= entries are BSAs: [pluginFile] is then the archive (found the same way as a plugin)
    val isArchive = category == "fallback-archive"
    val isPlugin = !isData && !isArchive
    val dir = if (isData) ops.cleanDirName(value) else null
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    val dataDirs = remember(cfgFile) { ops.dataDirs(cfgFile) }
    val masterDirs = remember(dataDirs) { dataDirs.distinct() }
    val assetDirs = remember(dir, masterDirs) { (masterDirs + listOfNotNull(dir)).distinct() }

    var output by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<File?>(null) }
    val audio = remember { AudioPlayer() }
    var playing by remember { mutableStateOf<String?>(null) }
    var audioPosition by remember { mutableStateOf("") }
    var audioError by remember { mutableStateOf<String?>(null) }
    // Files taken out of an archive for viewing or playing only live while they are open; the folder is emptied when
    // the window opens (left over if the app was stopped) and when it closes
    var playingTemp by remember { mutableStateOf<File?>(null) }
    DisposableEffect(Unit) {
        ops.clearArchiveView()
        onDispose { audio.release(); ops.clearArchiveView() }
    }
    LaunchedEffect(playing) {
        if (playing == null) { playingTemp?.delete(); playingTemp = null }
        while (playing != null) {
            fun clock(ms: Int) = "${ms / 60000}:${(ms / 1000 % 60).toString().padStart(2, '0')}"
            audioPosition = "${clock(audio.positionMs)} / ${clock(audio.durationMs)}"
            kotlinx.coroutines.delay(250)
        }
    }
    var files by remember { mutableStateOf<ModFiles?>(null) }
    var archiveFiles by remember { mutableStateOf<Pair<Map<FileKind, List<String>>, Map<String, Long>>?>(null) }
    var archiveError by remember { mutableStateOf<String?>(null) }
    var fileConflicts by remember { mutableStateOf<FileConflictReport?>(null) }
    var recordConflicts by remember { mutableStateOf<RecordConflictReport?>(null) }
    var typeText by remember { mutableStateOf("") }
    var idText by remember { mutableStateOf("") }
    var cellrefText by remember { mutableStateOf("") }
    var fileFilter by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var cleanOverwrite by remember { mutableStateOf(false) }

    fun run(label: String, block: () -> String) {
        if (busy) return
        busy = true
        output = "$label...\n"
        scope.launch(Dispatchers.IO) {
            output = try { block() } catch (e: Throwable) { "Error: ${e.message ?: e}" }
            busy = false
        }
    }

    LaunchedEffect(dir) {
        if (dir != null && dir.isDirectory) files = kotlinx.coroutines.withContext(Dispatchers.IO) { ops.listFiles(dir) }
    }
    LaunchedEffect(pluginFile) {
        if (pluginFile != null && isPlugin) output = kotlinx.coroutines.withContext(Dispatchers.IO) { ops.showHeader(pluginFile, masterDirs) }
        if (pluginFile != null && isArchive) {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                try { archiveFiles = ops.listArchive(pluginFile) } catch (e: Exception) { archiveError = e.message ?: e.toString() }
            }
        }
    }

    viewing?.let { shown ->
        AssetViewerDialog(shown, fs, assetDirs.map { d -> d.path }) {
            viewing = null
            if (ops.isArchiveView(shown)) shown.delete()
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(
            modifier = Modifier.fillMaxSize().padding(8.dp).border(2.dp, Gold, RoundedCornerShape(12.dp)),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF12151A))
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Developer tools", color = Gold, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("${category.uppercase()}  ${if (isData) dir?.path else value}", color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                    Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E3A8A), contentColor = Color.White), shape = RoundedCornerShape(6.dp)) {
                        Text("Close", fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))

                val scroll = rememberLazyListState()
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(state = scroll, modifier = Modifier.weight(1f).fillMaxHeight()) {
                        // ---------------- tools
                        item { Title("Tools") }
                        item {
                            if (isArchive && pluginFile == null) {
                                Note("The archive was not found in the data folders of this modlist.", Red)
                            }
                            if (isArchive && pluginFile != null) {
                                Group("This archive (BSA)") {
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Act("List files with sizes", !busy && archiveFiles != null) {
                                            run("Listing") {
                                                val sizes = archiveFiles!!.second
                                                "${pluginFile.path}\n${sizes.size} files, ${sizes.values.sum() / 1024} KB\n\n" +
                                                    sizes.entries.sortedBy { it.key.lowercase() }.joinToString("\n") { "${it.key.padEnd(60)} ${it.value}" }
                                            }
                                        }
                                    }
                                }
                            }
                            if (isPlugin && pluginFile == null) {
                                Note("The plugin file was not found in the data folders of this modlist, so the plugin tools are unavailable.", Red)
                            }
                            if (isPlugin && pluginFile != null) {
                                Group("This plugin (tes3cmd)") {
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Act("Header", !busy) { run("Reading header") { ops.showHeader(pluginFile, masterDirs) } }
                                        Act("Sync header", !busy) { run("Synchronizing header") { ops.syncHeader(pluginFile, masterDirs) } }
                                        Act(if (cleanOverwrite) "Clean (replace original)" else "Clean (write Clean_ copy)", !busy) { run("Cleaning") { ops.clean(pluginFile, masterDirs, cleanOverwrite) } }
                                        Act(if (cleanOverwrite) "Clean mode: replace" else "Clean mode: copy", !busy) { cleanOverwrite = !cleanOverwrite }
                                        Act(if (pluginFile.extension.equals("esp", true)) "Convert to ESM" else "Convert to ESP", !busy && pluginFile.extension.lowercase() in setOf("esp", "esm")) { run("Converting") { ops.convert(pluginFile, masterDirs) } }
                                        Act("Recover damaged", !busy) { run("Recovering") { ops.recover(pluginFile, masterDirs) } }
                                    }
                                }
                                Group("Look at records (plugin query / dump)") {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                        Field("Record type (Static, Cell, NPC...)", typeText, Modifier.weight(1f)) { typeText = it }
                                        Field("Id regex", idText, Modifier.weight(1f)) { idText = it }
                                        Field("Cell object regex", cellrefText, Modifier.weight(1f)) { cellrefText = it }
                                    }
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Act("List records", !busy) { run("Listing") { ops.list(pluginFile, masterDirs, typeText, idText) } }
                                        Act("Ids only", !busy) { run("Querying") { ops.query(pluginFile, typeText, idText, cellrefText, QueryMode.Ids) } }
                                        Act("Count", !busy) { run("Counting") { ops.query(pluginFile, typeText, idText, cellrefText, QueryMode.Count) } }
                                        Act("Dump records", !busy) { run("Dumping") { ops.query(pluginFile, typeText, idText, cellrefText, QueryMode.Dump) } }
                                        Act("Query whole load order", !busy) { run("Querying the load order") { ops.queryLoadOrder(cfgFile, typeText, idText, cellrefText, QueryMode.Ids) } }
                                        Act("Delete matching records...", !busy && (typeText.isNotBlank() || idText.isNotBlank()), danger = true) { confirmDelete = true }
                                    }
                                    if (confirmDelete) {
                                        Note("Delete every ${typeText.ifBlank { "record" }} matching \"$idText\" from ${pluginFile.name}? The plugin is rewritten; a backup is kept as ${pluginFile.nameWithoutExtension}~1.${pluginFile.extension}.", Red)
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Act("Yes, delete", !busy, danger = true) { confirmDelete = false; run("Deleting") { ops.delete(pluginFile, masterDirs, typeText, idText) } }
                                            Act("Cancel", true) { confirmDelete = false }
                                        }
                                    }
                                }
                                Group("Compare with another plugin of this modlist") {
                                    var other by remember { mutableStateOf("") }
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                        Field("Other plugin file name", other, Modifier.weight(1f)) { other = it }
                                        val otherFile = remember(other, masterDirs) { masterDirs.asReversed().firstNotNullOfOrNull { d -> d.listFiles()?.firstOrNull { f -> f.name.equals(other.trim(), true) } } }
                                        Act("Diff", !busy && otherFile != null) { run("Comparing") { ops.diff(pluginFile, otherFile!!, masterDirs) } }
                                        Act("Common ids", !busy && otherFile != null) { run("Comparing") { ops.common(pluginFile, otherFile!!, masterDirs) } }
                                    }
                                }
                            }
                            Group("Whole load order") {
                                Text("Preview shows what the tool would do; Run writes the plugins into the generated folder and adds them to openmw.cfg.", color = Muted, fontSize = 11.sp)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Act("Preview record merge", !busy) { run("Merging records") { ops.previewMerge(cfgFile) } }
                                    Act("Preview groundcover", !busy) { run("Finding groundcover") { ops.previewGroundcover(cfgFile) } }
                                    Act("Preview light fixes", !busy) { run("Fixing lights") { ops.previewLightFixes(cfgFile) } }
                                    for (tool in ModTool.entries) {
                                        Act("Run: ${tool.title}", !runner.running) { runner.start(listOf(tool)); output = "Started ${tool.title}." }
                                    }
                                }
                                if (runner.running || runner.log.isNotEmpty()) {
                                    Text(runner.status, color = Gold, fontSize = 12.sp)
                                    Text(runner.log.takeLast(30).joinToString("\n"), color = Color.White.copy(alpha = 0.85f), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                            if (isData && dir != null) {
                                Group("This folder") {
                                    val plugins = files?.byKind?.get(FileKind.PLUGIN).orEmpty().filter { it.substringAfterLast('.').lowercase() in setOf("esp", "esm") }
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Act("Show plugin headers", !busy && plugins.isNotEmpty()) {
                                            run("Reading headers") { plugins.joinToString("\n\n") { p -> "== $p\n" + ops.showHeader(File(dir, p), masterDirs) } }
                                        }
                                        Act("Clean plugins here", !busy && plugins.isNotEmpty()) {
                                            run("Cleaning") { plugins.joinToString("\n") { p -> ops.clean(File(dir, p), masterDirs, cleanOverwrite) } }
                                        }
                                        Act(if (cleanOverwrite) "Clean mode: replace" else "Clean mode: copy", true) { cleanOverwrite = !cleanOverwrite }
                                    }
                                }
                            }
                        }

                        // ---------------- conflicts
                        item { Title("Conflicts") }
                        item {
                            if (isData && dir != null) {
                                Act("Find file conflicts with other data folders", !busy) {
                                    run("Comparing files with ${dataDirs.size} data folders") {
                                        fileConflicts = ops.fileConflicts(cfgFile, dir)
                                        "Done."
                                    }
                                }
                                fileConflicts?.let { r ->
                                    Note("${r.total} files in this folder. ${r.wins.size} replace files from earlier folders (this one wins); ${r.loses.size} are replaced by later folders (this one loses).", Color.White)
                                    Expandable("Files this folder replaces", r.wins.size) { r.wins.take(400).forEach { c -> ConflictRow(c.path, "also in " + c.others.joinToString(", ")) } }
                                    Expandable("Files replaced by later folders", r.loses.size) { r.loses.take(400).forEach { c -> ConflictRow(c.path, "replaced by " + c.others.joinToString(", ")) } }
                                }
                            } else if (pluginFile != null && isPlugin) {
                                Act("Find record conflicts in the load order", !busy) {
                                    run("Comparing records with the whole load order") {
                                        recordConflicts = ops.recordConflicts(cfgFile, value, pluginFile)
                                        "Done."
                                    }
                                }
                                recordConflicts?.let { r ->
                                    Note("${r.recordCount} records. ${r.overrides.size} override records from earlier plugins; ${r.overridden.size} more are overridden by later plugins.", Color.White)
                                    if (r.byType.isNotEmpty()) Text(r.byType.entries.sortedByDescending { it.value }.joinToString("   ") { "${it.key} ${it.value}" }, color = Blue, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                    Expandable("Records this plugin overrides", r.overrides.size) {
                                        r.overrides.take(400).forEach { c -> ConflictRow("${c.type} ${c.id}", "after " + c.before.joinToString(", ") + if (c.after.isNotEmpty()) "; later still: " + c.after.joinToString(", ") else "") }
                                    }
                                    Expandable("Records overridden by later plugins", r.overridden.size) {
                                        r.overridden.take(400).forEach { c -> ConflictRow("${c.type} ${c.id}", "overridden by " + c.after.joinToString(", ")) }
                                    }
                                    if ((r.byType["LEVI"] ?: 0) + (r.byType["LEVC"] ?: 0) > 0) Note("Leveled list conflicts can be resolved by merging them: use Run: Merge records above.", Gold)
                                }
                            }
                        }

                        // ---------------- files
                        if (isArchive) {
                            item { Title("Files in this archive") }
                            item {
                                val a = archiveFiles
                                when {
                                    archiveError != null -> Note(archiveError!!, Red)
                                    pluginFile == null -> {}
                                    a == null -> Note("Reading the archive...", Muted)
                                    else -> {
                                        Field("Filter file names", fileFilter, Modifier.fillMaxWidth()) { fileFilter = it }
                                        Note("${a.second.size} files", Muted)
                                        for ((kind, names) in a.first) {
                                            val shown = names.filter { fileFilter.isBlank() || it.contains(fileFilter, ignoreCase = true) }
                                            Expandable(kind.label, shown.size, startOpen = false) {
                                                shown.take(300).forEach { rel ->
                                                    val ext = rel.substringAfterLast('.', "").lowercase()
                                                    val viewable = kind == FileKind.MESH && ext == "nif" || kind == FileKind.TEXTURE
                                                    val audible = kind == FileKind.AUDIO && ext in playableAudio
                                                    // the file is written to the cache first: the viewers and the player read files
                                                    fun extracted(then: (File) -> Unit) {
                                                        scope.launch {
                                                            val result = kotlinx.coroutines.withContext(Dispatchers.IO) { runCatching { ops.extractFromArchive(pluginFile!!, rel) } }
                                                            result.onSuccess(then).onFailure { output = "Could not take $rel out of the archive: ${it.message ?: it}" }
                                                        }
                                                    }
                                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                                        Text(rel, color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                                                        if (viewable) Act(if (kind == FileKind.MESH) "Render 3D" else "View", true) { extracted { viewing = it } }
                                                        if (audible) {
                                                            if (playing == rel) {
                                                                Text(audioPosition, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(end = 6.dp))
                                                                Act("Stop", true) { audio.stop(); playing = null }
                                                            } else Act("Play", true) {
                                                                extracted { f ->
                                                                    playingTemp?.let { old -> audio.stop(); old.delete() }
                                                                    playingTemp = f
                                                                    audioError = audio.play(f.path) { playing = null }
                                                                    playing = if (audioError == null) rel else null
                                                                    if (audioError != null) output = audioError!!
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                                if (shown.size > 300) Text("... and ${shown.size - 300} more (use the filter)", color = Muted, fontSize = 11.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (isData) {
                            item { Title("Files in this folder") }
                            item {
                                val f = files
                                if (f == null) Note("Reading the folder...", Muted)
                                else {
                                    Field("Filter file names", fileFilter, Modifier.fillMaxWidth()) { fileFilter = it }
                                    Note("${f.total} files", Muted)
                                    for ((kind, names) in f.byKind) {
                                        val shown = names.filter { fileFilter.isBlank() || it.contains(fileFilter, ignoreCase = true) }
                                        Expandable("${kind.label}", shown.size, startOpen = kind == FileKind.MESH && shown.size < 40) {
                                            shown.take(300).forEach { rel ->
                                                val viewable = kind == FileKind.MESH && rel.endsWith(".nif", true) || kind == FileKind.TEXTURE
                                                val audible = kind == FileKind.AUDIO && rel.substringAfterLast('.', "").lowercase() in playableAudio
                                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                                    Text(rel, color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                                                    if (viewable) Act(if (kind == FileKind.MESH) "Render 3D" else "View", true) { viewing = File(f.root, rel) }
                                                    if (audible) {
                                                        if (playing == rel) {
                                                            Text(audioPosition, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(end = 6.dp))
                                                            Act("Stop", true) { audio.stop(); playing = null }
                                                        } else Act("Play", true) {
                                                            audioError = audio.play(File(f.root, rel).path) { playing = null }
                                                            playing = if (audioError == null) rel else null
                                                        }
                                                    }
                                                }
                                                if (audioError != null && audible && playing == null && audioError!!.contains(rel.substringAfterLast('/'))) {
                                                    Note(audioError!!, Color(0xFFF87171))
                                                }
                                            }
                                            if (shown.size > 300) Text("... and ${shown.size - 300} more (use the filter)", color = Muted, fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }

                        // ---------------- output
                        item { Title("Output") }
                        item {
                            Surface(color = Color(0xFF0C0E12), shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, if (copied) Green else Color.White.copy(alpha = 0.08f)), modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    SelectionContainer {
                                        Text(if (busy) "Working...\n$output" else output.ifBlank { "Pick a tool above." }, color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Act(if (copied) "Copied!" else "Copy output", output.isNotBlank()) { clipboard.setText(AnnotatedString(output)); copied = true }
                                }
                            }
                            LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(1500); copied = false } }
                            Spacer(Modifier.height(16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(text, color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp, start = 2.dp))
}

@Composable
private fun Note(text: String, color: Color) {
    Text(text, color = color, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Color(0xFF1B1E24), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun Act(label: String, enabled: Boolean, danger: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (danger) Color(0xFF7F1D1D) else Color(0xFF2A2A2A), contentColor = if (danger) Color.White else Gold),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)
    ) { Text(label, fontSize = 12.sp) }
}

@Composable
private fun Field(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true, label = { Text(label, fontSize = 11.sp) },
        textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Gold, unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
            focusedLabelColor = Gold, unfocusedLabelColor = Muted, cursorColor = Gold,
            focusedTextColor = Color.White, unfocusedTextColor = Color.White
        ),
        modifier = modifier
    )
}

@Composable
private fun Expandable(title: String, count: Int, startOpen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var open by remember { mutableStateOf(startOpen) }
    Surface(color = Color(0xFF1B1E24), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column {
            Row(modifier = Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (open) "v" else ">", color = Gold, fontSize = 13.sp, modifier = Modifier.width(18.dp))
                Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("$count", color = Muted, fontSize = 12.sp)
            }
            if (open && count > 0) Column(modifier = Modifier.padding(start = 30.dp, end = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
        }
    }
}

@Composable
private fun ConflictRow(what: String, detail: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(what, color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Text(detail, color = Muted, fontSize = 10.sp)
    }
}
