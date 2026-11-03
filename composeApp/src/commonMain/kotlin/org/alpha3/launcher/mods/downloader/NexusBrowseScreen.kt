package org.alpha3.launcher.mods.downloader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Color.Companion.White
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.dialogs.compose.rememberFileSaverLauncher
import io.github.vinceglb.filekit.readString
import io.github.vinceglb.filekit.writeString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.alpha3.launcher.io.File
import org.alpha3.launcher.paths.Profiles
import org.alpha3.launcher.ui.controls.UIStateManager.customColor
import org.alpha3.launcher.ui.controls.UIStateManager.gold

private val dim = White.copy(alpha = 0.6f)

@Composable
private fun FullScreen(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).padding(10.dp)) { content() }
    }
}

@Composable
private fun Header(title: String, onDismiss: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = gold, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        actions()
        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close", tint = gold) }
    }
}

@Composable
private fun GoldButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = customColor, contentColor = gold),
        border = BorderStroke(1.dp, gold),
        shape = RoundedCornerShape(10.dp),
    ) { Text(text) }
}

private fun compact(n: Int): String = when {
    n >= 1_000_000 -> "${(n / 100_000) / 10.0}M"
    n >= 1_000 -> "${(n / 100) / 10.0}k"
    else -> n.toString()
}

// ---------------------------------------------------------------- browse and search

/** Searching and browsing Nexus, and putting a mod on a list. */
@Composable
fun NexusBrowseDialog(onDismiss: () -> Unit, onOpenLists: () -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(NexusBrowse.Sort.ENDORSEMENTS) }
    val results = remember { mutableStateListOf<NexusBrowse.Mod>() }
    var total by remember { mutableStateOf(0) }
    var page by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<NexusBrowse.Mod?>(null) }
    var searchToken by remember { mutableStateOf(0) }

    fun load(reset: Boolean) {
        if (loading && !reset) return
        val token = if (reset) ++searchToken else searchToken
        val next = if (reset) 0 else page + 1
        loading = true
        error = null
        scope.launch {
            try {
                val result = NexusBrowse.search(query, sort, next)
                if (token != searchToken) return@launch
                if (reset) results.clear()
                results.addAll(result.mods.filter { m -> results.none { it.modId == m.modId } })
                total = result.total
                page = next
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (token == searchToken) error = e.message
            } finally {
                if (token == searchToken) loading = false
            }
        }
    }

    LaunchedEffect(sort) { load(reset = true) }

    FullScreen(onDismiss) {
        Column(Modifier.fillMaxSize()) {
            Header("Browse Nexus", onDismiss) {
                TextButton(onClick = onOpenLists) { Text("My lists", color = gold) }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Search Morrowind mods by name") },
                trailingIcon = { IconButton(onClick = { load(reset = true) }) { Icon(Icons.Default.Search, "Search", tint = gold) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) sort = NexusBrowse.Sort.RELEVANCE; load(reset = true) }),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
                for (s in NexusBrowse.Sort.entries) {
                    if (s == NexusBrowse.Sort.RELEVANCE && query.isBlank()) continue
                    val on = s == sort
                    Text(
                        s.label,
                        color = if (on) Color.Black else gold,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(end = 6.dp)
                            .background(if (on) gold else customColor, RoundedCornerShape(14.dp))
                            .border(1.dp, gold, RoundedCornerShape(14.dp))
                            .clickable { if (sort == s) load(reset = true) else sort = s }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            error?.let { Text(it, color = Color(0xFFE57373), fontSize = 13.sp) }
            if (total > 0) Text("$total mods", color = dim, fontSize = 12.sp)
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                itemsIndexed(results, key = { _, m -> m.modId }) { index, mod ->
                    ModRow(mod) { selected = mod }
                    if (index == results.lastIndex && results.size < total) {
                        LaunchedEffect(results.size) { load(reset = false) }
                    }
                }
                if (loading) item {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = gold)
                    }
                }
                if (!loading && results.isEmpty() && error == null) item {
                    Text("Nothing found", color = dim, modifier = Modifier.padding(16.dp))
                }
            }
        }
    }

    selected?.let { ModDetailDialog(it) { selected = null } }
}

@Composable
private fun ModRow(mod: NexusBrowse.Mod, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        AsyncImage(
            model = mod.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.width(112.dp).height(64.dp).background(customColor, RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(mod.name, color = White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${mod.author.ifBlank { mod.uploader }} · ${mod.category} · ♥ ${compact(mod.endorsements)} · ↓ ${compact(mod.downloads)}",
                color = gold, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(mod.summary, color = dim, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    HorizontalDivider(color = White.copy(alpha = 0.1f))
}

/** A mod's page: its picture, summary and files, and the lists it is on, with a button to put it on one. */
@Composable
private fun ModDetailDialog(mod: NexusBrowse.Mod, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var files by remember { mutableStateOf<List<NexusBrowse.ModFile>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var chosen by remember { mutableStateOf<NexusBrowse.ModFile?>(null) }
    var onLists by remember { mutableStateOf<Set<String>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pickList by remember { mutableStateOf(false) }

    LaunchedEffect(mod.modId) {
        try {
            val f = NexusBrowse.files(mod.modId)
            files = f
            chosen = f.firstOrNull { it.category == "MAIN" } ?: f.firstOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message
        }
        onLists = CustomLists.listsOf(mod.modId)
    }

    FullScreen(onDismiss) {
        Column(Modifier.fillMaxSize()) {
            Header(mod.name, onDismiss)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                AsyncImage(
                    model = mod.pictureUrl ?: mod.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                )
                Text(
                    "Version ${mod.version} · by ${mod.author.ifBlank { mod.uploader }} · ${mod.category}",
                    color = gold, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
                )
                Text("♥ ${compact(mod.endorsements)} endorsements · ↓ ${compact(mod.downloads)} downloads", color = dim, fontSize = 12.sp)
                Text(mod.summary, color = White, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp))
                TextButton(onClick = { uri.openUri(mod.pageUrl) }) { Text("Open the Nexus page (description, requirements)", color = gold) }

                Text("File to install", color = gold, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                when {
                    error != null -> Text("Could not load the files: $error", color = Color(0xFFE57373))
                    files == null -> CircularProgressIndicator(color = gold, modifier = Modifier.padding(8.dp))
                    files!!.isEmpty() -> Text("This mod has no files to download", color = dim)
                    else -> for (f in files!!) {
                        Row(
                            Modifier.fillMaxWidth().clickable { chosen = f },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = chosen == f, onClick = { chosen = f },
                                colors = RadioButtonDefaults.colors(selectedColor = gold, unselectedColor = dim),
                            )
                            Column(Modifier.weight(1f)) {
                                Text("${f.name} ${f.version}".trim(), color = White, fontSize = 14.sp)
                                Text(
                                    "${f.category.lowercase().replaceFirstChar { it.uppercase() }} · ${formatSize(f.sizeKb)}",
                                    color = dim, fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }

                if (onLists.isNotEmpty()) {
                    Text("On your lists", color = gold, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                    for (list in onLists.sorted()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(list, color = White, modifier = Modifier.weight(1f))
                            if (CustomLists.has(list, mod.modId)) {
                                TextButton(enabled = !busy, onClick = {
                                    busy = true
                                    scope.launch {
                                        CustomLists.remove(list, mod.modId)
                                        onLists = CustomLists.listsOf(mod.modId)
                                        message = "Taken off $list"
                                        busy = false
                                    }
                                }) { Text("Remove", color = gold) }
                            } else {
                                Text("from the list itself", color = dim, fontSize = 11.sp)
                            }
                        }
                    }
                }
                message?.let { Text(it, color = dim, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                GoldButton("Add to a list", enabled = chosen != null && !busy) { pickList = true }
            }
        }
    }

    if (pickList) {
        val file = chosen
        ListPicker(
            onDismiss = { pickList = false },
            onPick = { list ->
                pickList = false
                if (file == null) return@ListPicker
                busy = true
                scope.launch {
                    message = try {
                        CustomLists.add(list, mod, file)
                        onLists = CustomLists.listsOf(mod.modId)
                        "Added to $list. It downloads with the list's next run" +
                            if (CustomLists.isCreated(list)) "." else ", and is added to the list's openmw.cfg."
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        "Could not add it: ${e.message}"
                    }
                    busy = false
                }
            },
        )
    }
}

/** Picks a list to add to: the ones made in the app first, then the site's, or a new one. */
@Composable
private fun ListPicker(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var creating by remember { mutableStateOf(false) }
    val all = remember(CustomLists.revision) {
        CustomLists.created.toList() + ModListManager.availableLists.filter { it !in CustomLists.created }.sorted()
    }
    if (creating) {
        NewListDialog(onDismiss = { creating = false }, onCreated = { creating = false; onPick(it) })
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to which list?") },
        text = {
            LazyColumn(Modifier.height(360.dp)) {
                item {
                    Text("+ New list", color = gold, fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth().clickable { creating = true }.padding(vertical = 10.dp))
                    HorizontalDivider()
                }
                itemsIndexed(all) { _, name ->
                    val own = name in CustomLists.created
                    Text(
                        if (own) "$name  (yours)" else name,
                        color = if (own) gold else White,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(name) }.padding(vertical = 10.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NewListDialog(onDismiss: () -> Unit, onCreated: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    val problem = CustomLists.nameProblem(name.trim())
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New mod list") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") })
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description (optional)") })
                (error ?: problem?.takeIf { name.isNotEmpty() })?.let { Text(it, color = Color(0xFFE57373), fontSize = 12.sp) }
                Text(
                    "It starts with the game's own files. Add mods from the search, then arrange the load order in My lists.",
                    color = dim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = problem == null, onClick = {
                try {
                    CustomLists.create(name.trim(), description.trim())
                    onCreated(name.trim())
                } catch (e: Exception) {
                    error = e.message
                }
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------- my lists

/** The lists made in the app (make, import, share, delete) and the load order of any installed list. */
@Composable
fun MyListsDialog(onDismiss: () -> Unit, onBrowse: () -> Unit) {
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    var organizing by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { CustomLists.refresh() } }

    val saver = rememberFileSaverLauncher { file ->
        val name = exporting ?: return@rememberFileSaverLauncher
        exporting = null
        if (file == null) return@rememberFileSaverLauncher
        scope.launch {
            message = try {
                file.writeString(CustomLists.export(name))
                "Saved $name. Share the file; anyone with Alpha3 can import it in My lists."
            } catch (e: Exception) {
                "Could not save it: ${e.message}"
            }
        }
    }
    val picker = rememberFilePickerLauncher(type = FileKitType.File(listOf("json"))) { file ->
        if (file == null) return@rememberFilePickerLauncher
        scope.launch {
            message = try {
                val name = CustomLists.import(file.readString())
                "Imported $name. Pick it in the downloader and press Download to install it."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Could not import it: ${e.message}"
            }
        }
    }

    FullScreen(onDismiss) {
        Column(Modifier.fillMaxSize()) {
            Header("My lists", onDismiss) {
                TextButton(onClick = onBrowse) { Text("Browse Nexus", color = gold) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GoldButton("New list") { creating = true }
                GoldButton("Import") { picker.launch() }
            }
            message?.let { Text(it, color = dim, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp)) }
            LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp)) {
                item {
                    Text("Made in the app", color = gold, fontWeight = FontWeight.Bold)
                    if (CustomLists.created.isEmpty()) {
                        Text("None yet. Make one, import one, or add a mod from Browse Nexus to a new list.", color = dim, fontSize = 13.sp)
                    }
                }
                itemsIndexed(CustomLists.created.toList(), key = { _, n -> "own:$n" }) { _, name ->
                    val list = remember(name, CustomLists.revision) { CustomLists.load(name) }
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(name, color = White, fontWeight = FontWeight.Bold)
                        val desc = list?.description.orEmpty()
                        Text(
                            "${list?.mods?.size ?: 0} mods" + if (desc.isNotBlank()) " · $desc" else "",
                            color = dim, fontSize = 12.sp,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { organizing = name }) { Text("Load order", color = gold) }
                            TextButton(onClick = {
                                exporting = name
                                saver.launch(suggestedName = name.replace(' ', '-'), extension = "json")
                            }) { Text("Export", color = gold) }
                            TextButton(onClick = { deleting = name }) { Text("Delete", color = gold) }
                        }
                    }
                    HorizontalDivider(color = White.copy(alpha = 0.1f))
                }
                item {
                    Text("Installed lists", color = gold, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
                    Text("Arrange the openmw.cfg that a list installed to its profile.", color = dim, fontSize = 12.sp)
                }
                val installed = (listOf(Profiles.DEFAULT) + Profiles.list()).distinct()
                    .filter { File(Profiles.folderOf(it), "openmw.cfg").isFile }
                    .filter { it == Profiles.DEFAULT || it !in CustomLists.created.map(Profiles::sanitize) }
                itemsIndexed(installed, key = { _, n -> "profile:$n" }) { _, profile ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (profile == Profiles.DEFAULT) "Default profile" else profile, color = White, modifier = Modifier.weight(1f))
                        TextButton(onClick = { organizing = "profile:$profile" }) { Text("Load order", color = gold) }
                    }
                }
            }
        }
    }

    if (creating) NewListDialog(onDismiss = { creating = false }, onCreated = { creating = false; message = "Made $it. Add mods from Browse Nexus." })
    deleting?.let { name ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete $name?") },
            text = { Text("The list and its load order go. Downloaded files and an installed profile stay.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch { CustomLists.delete(name); message = "Deleted $name" }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
    organizing?.let { target -> LoadOrderDialog(target) { organizing = null } }
}

// ---------------------------------------------------------------- the organizer

/**
 * Arranges a load order: [target] is a list made in the app (its own order, put into its profile as well when it
 * was installed) or "profile:<name>" (that profile's openmw.cfg).
 */
@Composable
private fun LoadOrderDialog(target: String, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val profile = target.removePrefix("profile:").takeIf { target.startsWith("profile:") }
    val title = when {
        profile == null -> target
        profile == Profiles.DEFAULT -> "Default profile"
        else -> profile
    }
    var game by remember { mutableStateOf<String?>(null) }
    var source by remember { mutableStateOf<LoadOrder?>(null) }
    val order = remember { mutableStateMapOf<LoadOrder.Kind, List<LoadOrder.Entry>>() }
    var kind by remember { mutableStateOf(LoadOrder.Kind.CONTENT) }
    var dirty by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    // The plugin order before the last sort, until something else changes it
    var beforeSort by remember { mutableStateOf<List<LoadOrder.Entry>?>(null) }
    var hasBackup by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    var reloads by remember { mutableStateOf(0) }
    var sorting by remember { mutableStateOf(false) }
    // What LOOT said about each plugin at the last sort, by lower-case name
    var notes by remember { mutableStateOf<Map<String, List<LootSort.Message>>>(emptyMap()) }

    LaunchedEffect(target, reloads) {
        game = CustomLists.gameDataFiles()
        val lines = withContext(Dispatchers.IO) {
            hasBackup = LoadOrderBackup.exists(target)
            if (profile != null) File(Profiles.folderOf(profile), "openmw.cfg").takeIf { it.isFile }?.readLines().orEmpty()
            else CustomLists.load(target)?.cfg.orEmpty()
        }
        val parsed = LoadOrder(lines)
        source = parsed
        order.clear()
        order.putAll(parsed.entries)
        dirty = false
        beforeSort = null
        notes = emptyMap()
    }

    fun move(from: Int, to: Int) {
        val items = order[kind].orEmpty().toMutableList()
        if (to !in items.indices) return
        items.add(to, items.removeAt(from))
        order[kind] = items
        dirty = true
        beforeSort = null
    }

    fun save() {
        val parsed = source ?: return
        val lines = parsed.write(order)
        scope.launch {
            message = try {
                withContext(Dispatchers.IO) {
                    // What was saved before is kept, so Restore previous can bring it back
                    LoadOrderBackup.keep(target, LoadOrderBackup.current(target))
                    hasBackup = true
                    if (profile != null) {
                        File(Profiles.folderOf(profile), "openmw.cfg").writeText(lines.joinToString("\n", postfix = "\n"))
                        "Saved to the profile's openmw.cfg"
                    } else {
                        CustomLists.saveCfg(target, lines)
                        if (CustomLists.applyToProfile(target, game) != null) "Saved, and put into the $target profile"
                        else "Saved. It is used when the list is installed."
                    }
                }
            } catch (e: Exception) {
                "Could not save: ${e.message}"
            }
            dirty = false
            beforeSort = null
            source = LoadOrder(lines)
        }
    }

    // A plugin's file, looked for in the switched-on data folders, last folder first (as OpenMW does)
    fun find(name: String): File? = order[LoadOrder.Kind.DATA].orEmpty().filter { it.enabled }.asReversed().firstNotNullOfOrNull { d ->
        File(CustomLists.expand(d.value, game).removeSurrounding("\""), name).takeIf { it.isFile }
    }

    FullScreen(onDismiss) {
        Column(Modifier.fillMaxSize()) {
            Header("Load order: $title", onDismiss)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
                for (k in LoadOrder.Kind.entries) {
                    val count = order[k]?.size ?: 0
                    if (count == 0 && k == LoadOrder.Kind.GROUNDCOVER) continue
                    val on = k == kind
                    val changed = movedCount(source?.entries?.get(k).orEmpty(), order[k].orEmpty())
                    Text(
                        "${k.title} ($count" + (if (changed > 0) " · $changed moved" else "") + ")",
                        color = if (on) Color.Black else gold,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(end = 6.dp)
                            .background(if (on) gold else customColor, RoundedCornerShape(14.dp))
                            .border(1.dp, gold, RoundedCornerShape(14.dp))
                            .clickable { kind = k }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            Text(
                when (kind) {
                    LoadOrder.Kind.DATA -> "Later folders win: a file in a lower folder replaces the same file above it."
                    LoadOrder.Kind.CONTENT -> "Plugins load top to bottom; each must come after its masters."
                    else -> "Loaded top to bottom."
                },
                color = dim, fontSize = 12.sp,
            )
            val items = order[kind].orEmpty()
            val savedIndex = remember(source, kind) {
                source?.entries?.get(kind).orEmpty().withIndex().associate { (i, e) -> e.value.lowercase() to i }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                itemsIndexed(items, key = { i, e -> "$i:${e.value}" }) { index, entry ->
                    val was = savedIndex[entry.value.lowercase()]
                    val moved = was != null && was != index
                    Column(
                        Modifier.fillMaxWidth()
                            .background(if (moved) gold.copy(alpha = 0.12f) else Color.Transparent, RoundedCornerShape(6.dp))
                    ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}", color = dim, fontSize = 11.sp, modifier = Modifier.width(28.dp))
                        Checkbox(
                            checked = entry.enabled,
                            onCheckedChange = { on ->
                                order[kind] = items.toMutableList().also { it[index] = entry.copy(enabled = on) }
                                dirty = true
                                beforeSort = null
                            },
                            colors = CheckboxDefaults.colors(checkedColor = gold, uncheckedColor = dim),
                        )
                        Text(
                            entry.label,
                            color = if (entry.enabled) White else dim,
                            fontSize = 13.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { move(index, index - 1) }, enabled = index > 0, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.ArrowUpward, "Up", tint = if (index > 0) gold else dim)
                        }
                        if (moved) {
                            Text(
                                (if (was!! > index) "↑" else "↓") + " was ${was + 1}",
                                color = gold, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }
                        IconButton(onClick = { move(index, index + 1) }, enabled = index < items.lastIndex, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.ArrowDownward, "Down", tint = if (index < items.lastIndex) gold else dim)
                        }
                    }
                    if (kind == LoadOrder.Kind.CONTENT) {
                        for (note in notes[entry.value.lowercase()].orEmpty()) {
                            Text(
                                (when (note.level) { "error" -> "✖ "; "warning" -> "⚠ "; else -> "ℹ " }) + note.text,
                                color = when (note.level) { "error" -> Color(0xFFE57373); "warning" -> Color(0xFFFFB74D); else -> dim },
                                fontSize = 11.sp,
                                modifier = Modifier.padding(start = 76.dp, end = 8.dp, bottom = 4.dp),
                            )
                        }
                    }
                    }
                }
            }
            message?.let { Text(it, color = dim, fontSize = 13.sp) }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Sorting only ever happens here, when asked, and can be undone before or after saving
                val undo = beforeSort
                if (kind == LoadOrder.Kind.CONTENT && undo == null && LootSort.available) {
                    OutlinedButton(enabled = !sorting, onClick = {
                        sorting = true
                        message = "LOOT is sorting…"
                        scope.launch {
                            try {
                                val folders = order[LoadOrder.Kind.DATA].orEmpty().filter { it.enabled }
                                    .map { CustomLists.expand(it.value, game).removeSurrounding("\"") }
                                val result = LootSort.sort(items, folders, ::find)
                                notes = result.messages.groupBy { it.plugin.lowercase() }
                                val skipped = if (result.skipped.isEmpty()) "" else
                                    " ${result.skipped.size} switched off or not installed stayed where they were."
                                val said = if (result.messages.isEmpty()) "" else " LOOT has ${result.messages.size} notes, shown under the plugins."
                                if (result.order == items) {
                                    message = "LOOT keeps this order.$said$skipped"
                                } else {
                                    beforeSort = items
                                    order[LoadOrder.Kind.CONTENT] = result.order
                                    dirty = true
                                    message = "Sorted by LOOT. Moved plugins are marked; check them, then save or undo.$said$skipped"
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                message = "LOOT could not sort: ${e.message}"
                            } finally {
                                sorting = false
                            }
                        }
                    }, border = BorderStroke(1.dp, gold)) {
                        if (sorting) CircularProgressIndicator(color = gold, modifier = Modifier.size(16.dp))
                        else Text("Sort with LOOT", color = gold)
                    }
                }
                if (kind == LoadOrder.Kind.CONTENT && undo == null && !LootSort.available) {
                    OutlinedButton(onClick = {
                        val sorted = LoadOrder.sortPlugins(items, ::find)
                        if (sorted == items) {
                            message = "Already in an order that loads every plugin after its masters."
                        } else {
                            beforeSort = items
                            order[LoadOrder.Kind.CONTENT] = sorted
                            dirty = true
                            message = "Sorted by the masters each plugin needs. Check it, then save, or undo."
                        }
                    }, border = BorderStroke(1.dp, gold)) { Text("Sort by masters", color = gold) }
                }
                if (undo != null) {
                    OutlinedButton(onClick = {
                        order[LoadOrder.Kind.CONTENT] = undo
                        beforeSort = null
                        notes = emptyMap()
                        message = "Sort undone"
                    }, border = BorderStroke(1.dp, gold)) { Text("Undo sort", color = gold) }
                }
                if (dirty) {
                    OutlinedButton(onClick = { reloads++; message = "Back to the saved order" }, border = BorderStroke(1.dp, gold)) {
                        Text("Revert", color = gold)
                    }
                }
                if (hasBackup && !dirty) {
                    OutlinedButton(onClick = { confirmRestore = true }, border = BorderStroke(1.dp, gold)) {
                        Text("Restore previous", color = gold)
                    }
                }
                GoldButton("Save", enabled = dirty && source != null) { save() }
            }
        }
    }

    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("Restore the previous order?") },
            text = { Text("The load order goes back to what it was before the last save. The order now becomes the one Restore previous brings back.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = false
                    scope.launch {
                        message = try {
                            withContext(Dispatchers.IO) { LoadOrderBackup.swap(target, game) }
                            "Restored the previous order"
                        } catch (e: Exception) {
                            "Could not restore it: ${e.message}"
                        }
                        reloads++
                    }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("Cancel") } },
        )
    }
}

/**
 * The order before the last save of the organizer, one per list or profile, in OpenMW/loadorder-backups. Restoring
 * swaps it with the current one, so a restore can be undone the same way.
 */
private object LoadOrderBackup {
    private fun file(target: String) =
        File("$modStorage/loadorder-backups", "${target.replace(Regex("[/\\\\:*?\"<>|]"), "_").ifBlank { "default" }}.cfg")

    fun exists(target: String) = file(target).isFile

    /** The saved lines of [target] now: a profile's openmw.cfg, or a list made in the app's load order. */
    fun current(target: String): List<String> {
        val profile = target.removePrefix("profile:").takeIf { target.startsWith("profile:") }
        return if (profile != null) File(Profiles.folderOf(profile), "openmw.cfg").takeIf { it.isFile }?.readLines().orEmpty()
        else CustomLists.load(target)?.cfg.orEmpty()
    }

    fun keep(target: String, lines: List<String>) {
        if (lines.isEmpty()) return
        File("$modStorage/loadorder-backups").mkdirs()
        file(target).writeText(lines.joinToString("\n", postfix = "\n"))
    }

    fun swap(target: String, game: String?) {
        val backup = file(target).takeIf { it.isFile }?.readLines()?.dropLastWhile { it.isBlank() } ?: return
        keep(target, current(target))
        val profile = target.removePrefix("profile:").takeIf { target.startsWith("profile:") }
        if (profile != null) {
            File(Profiles.folderOf(profile), "openmw.cfg").writeText(backup.joinToString("\n", postfix = "\n"))
        } else {
            CustomLists.saveCfg(target, backup)
            CustomLists.applyToProfile(target, game)
        }
    }
}

/** How many entries of [now] are at another place than in [saved]. */
private fun movedCount(saved: List<LoadOrder.Entry>, now: List<LoadOrder.Entry>): Int {
    val at = saved.withIndex().associate { (i, e) -> e.value.lowercase() to i }
    return now.withIndex().count { (i, e) -> at[e.value.lowercase()]?.let { it != i } == true }
}
