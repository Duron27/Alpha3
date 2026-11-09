package org.alpha3.launcher.cs.ui

import org.alpha3.launcher.tools.openmw.isOfficialContent
import androidx.compose.material.icons.filled.Lock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.alpha3.launcher.cs.model.CsDocument
import org.alpha3.launcher.cs.model.EDITABLE_TYPES
import org.alpha3.launcher.cs.model.LoadOrderEntry
import org.alpha3.launcher.cs.model.RECORD_DEFS
import org.alpha3.launcher.cs.model.RecordRow
import org.alpha3.launcher.cs.model.RecordState
import org.alpha3.launcher.cs.model.Workspace
import org.alpha3.launcher.paths.OpenMWPaths
import org.alpha3.launcher.tools.openmw.ToolException
import org.alpha3.launcher.tools.openmw.baseName
import org.alpha3.launcher.tools.openmw.dirName
import org.alpha3.launcher.tools.openmw.enableInOpenMWConfig
import org.alpha3.launcher.tools.openmw.joinPath
import org.alpha3.launcher.tools.openmw.readOpenMW
import org.alpha3.launcher.utils.Platform

/** Where new plugins are made: a data folder of the Construction Set's own, added to openmw.cfg on request. */
internal fun csPluginDir(): String = joinPath(OpenMWPaths.USER_GENERATED.trimEnd('/').substringBeforeLast('/'), "alpha3_cs")

internal val stateColour = mapOf(
    RecordState.Base to Color(0xFFBBBBBB),
    RecordState.Modified to Color(0xFF7FB2FF),
    RecordState.New to Color(0xFF8FD98F),
    RecordState.Deleted to Color(0xFFE57373),
)

/** The open document and what the screens show of it. */
internal class CsSession(val workspace: Workspace, val doc: CsDocument) {
    /** Models and textures for the cell view, kept while the plugin is open. */
    val scene by lazy { SceneCache(workspace.assets) }
    var version by mutableStateOf(0)
    var type by mutableStateOf("NPC_")
    var selected by mutableStateOf<Pair<String, String>?>(null)
    var message by mutableStateOf("")
    /** Show only what the plugin adds, changes or deletes (not the masters' records it leaves alone). */
    var onlyPlugin by mutableStateOf(doc.plugin.records.isNotEmpty())
    /** The selected reference of each cell opened, kept while the plugin is open (and across full screen). */
    val cellSelection = HashMap<String, androidx.compose.runtime.MutableState<org.alpha3.launcher.cs.model.RefKey?>>()

    fun changed() { version = doc.version }

    fun row(): RecordRow? {
        val (tag, id) = selected ?: return null
        return doc.rows(tag).firstOrNull { it.id == id }
    }
}

/**
 * The Construction Set: open a plugin of the load order (or start a new one over chosen masters), browse its records
 * and their masters' by type, edit, create, clone and delete records, save. Records it does not understand are kept
 * byte for byte.
 */
@Composable
fun ConstructionSet() {
    val features = Platform.features
    val fs = features.toolFs
    val platform = features.toolPlatform
    if (fs == null || platform == null) {
        Text("The Construction Set is not available on this platform", modifier = Modifier.padding(16.dp))
        return
    }
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<CsSession?>(null) }
    var savedGames by remember { mutableStateOf(false) }
    var luaMods by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val workspace = remember {
        runCatching { Workspace(fs, platform, OpenMWPaths.USER_OPENMW_CFG) }.onFailure { error = it.message }.getOrNull()
    }

    fun run(label: String, block: suspend () -> CsDocument) {
        busy = label
        scope.launch {
            try {
                val doc = withContext(Dispatchers.Default) { block() }
                session = CsSession(workspace!!, doc).also { cs ->
                    cs.changed()
                    // an opened mod starts on what it changes
                    if (cs.onlyPlugin) doc.plugin.records.firstOrNull { it.tag in EDITABLE_TYPES }?.let { cs.type = it.tag }
                }
            } catch (e: Exception) {
                error = (e as? ToolException)?.message ?: e.toString()
            } finally {
                busy = null
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        val s = session
        when {
            savedGames -> org.alpha3.launcher.cs.save.SaveEditorScreen(fs, onClose = { savedGames = false })
            luaMods -> org.alpha3.launcher.cs.lua.ui.LuaModCreator(fs, joinPath(csPluginDir(), "lua"), onClose = { luaMods = false })
            workspace == null -> Text(error ?: "No openmw.cfg yet: set up the game files first", modifier = Modifier.padding(16.dp))
            s == null -> StartScreen(
                workspace,
                onSaves = { savedGames = true },
                onLua = { luaMods = true },
                onOpen = { path -> run("Opening ${baseName(path)}") { workspace.openFile(path) { busy = it } } },
                onNew = { name, author, desc, masters ->
                    run("Creating $name") { workspace.create(csPluginDir(), name, author, desc, masters) { busy = it } }
                },
            )
            else -> Editor(s, onClose = { s.doc.close(); session = null })
        }
        busy?.let { label ->
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Text(label, color = Color.White)
                }
            }
        }
        error?.let { e ->
            AlertDialog(
                onDismissRequest = { error = null },
                title = { Text("Construction Set") },
                text = { Text(e) },
                confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } },
            )
        }
    }
}

/** A cell opens in the cell view, every other record in the form. */
@Composable
private fun RecordOrCell(s: CsSession, row: RecordRow) {
    if (row.tag == "CELL" && row.state != RecordState.Deleted) CellEditor(s, row) else RecordEditor(s, row)
}

@Composable
private fun StartScreen(workspace: Workspace, onSaves: () -> Unit, onLua: () -> Unit, onOpen: (String) -> Unit, onNew: (String, String, String, List<String>) -> Unit) {
    var creating by remember { mutableStateOf(false) }
    // an official file picked: it is never edited, so a new mod over it is offered instead
    var over by remember { mutableStateOf<LoadOrderEntry?>(null) }
    val order = workspace.loadOrder
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Construction Set", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            androidx.compose.material3.OutlinedButton(onClick = onLua) { Text("Lua mods") }
            androidx.compose.material3.OutlinedButton(onClick = onSaves) { Text("Saved games") }
            Button(onClick = { creating = true }) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("New plugin")
            }
        }
        Text("Open a plugin of the load order to edit it. The official Morrowind files are read-only: picking one starts a new mod that changes it.",
            style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
            items(order) { e ->
                Surface(
                    color = Color.White.copy(alpha = 0.05f), shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().clickable(enabled = e.path != null) {
                        if (isOfficialContent(e.name)) over = e else e.path?.let(onOpen)
                    },
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Description, null, tint = if (e.isMaster) Color(0xFFCAA560) else Color.LightGray, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                if (isOfficialContent(e.name)) {
                                    Spacer(Modifier.width(8.dp))
                                    Icon(Icons.Default.Lock, "Read-only", tint = Color.Gray, modifier = Modifier.size(14.dp))
                                    Text(" official, read-only", fontSize = 11.sp, color = Color.Gray)
                                }
                            }
                            Text(e.path?.let(::dirName) ?: "not found in any data folder", fontSize = 11.sp, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
    if (creating) NewPluginDialog(order.filter { it.isMaster && it.path != null }.map { it.name }, onDismiss = { creating = false }) { n, a, d, m ->
        creating = false
        onNew(n, a, d, m)
    }
    over?.let { e ->
        // the file and what it needs (an official plugin over the masters before it), in load order
        val base = order.filter { it.path != null && (it.isMaster || it.name == e.name) }.map { it.name }
        val needs = base.filter { it == e.name || (it.lowercase() in setOf("morrowind.esm", "tribunal.esm", "bloodmoon.esm") && base.indexOf(it) <= base.indexOf(e.name)) }
        NewPluginDialog(
            base, onDismiss = { over = null },
            title = "New mod over ${e.name}",
            note = "${e.name} is an official Morrowind file and is never changed. Your changes go in a new mod that has it as a master.",
            initialName = e.name.substringBeforeLast('.') + " changes",
            preselect = needs,
        ) { n, a, d, m ->
            over = null
            onNew(n, a, d, m)
        }
    }
}

@Composable
private fun NewPluginDialog(
    masters: List<String>,
    onDismiss: () -> Unit,
    title: String = "New plugin",
    note: String? = null,
    initialName: String = "MyMod",
    preselect: List<String>? = null,
    onCreate: (String, String, String, List<String>) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var author by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var addon by remember { mutableStateOf(false) }
    val chosen = remember { mutableStateListOf<String>().also { l -> l += preselect ?: masters.filter { it.lowercase() in setOf("morrowind.esm", "tribunal.esm", "bloodmoon.esm") } } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                note?.let { Text(it, fontSize = 13.sp, color = Color.LightGray) }
                OutlinedTextField(name, { name = it.filter { c -> c !in "/\\:*?\"<>|" } }, label = { Text("File name") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(addon, { addon = it })
                    Text(".omwaddon (OpenMW only) instead of .esp")
                }
                OutlinedTextField(author, { author = it.take(31) }, label = { Text("Author") }, singleLine = true)
                OutlinedTextField(desc, { desc = it.take(255) }, label = { Text("Description") })
                Text("Masters", fontWeight = FontWeight.SemiBold)
                LazyColumn(Modifier.height(160.dp)) {
                    items(masters) { m ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(m in chosen, { if (it) chosen += m else chosen -= m })
                            Text(m, fontSize = 13.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                // masters in load order, as the game wants them
                onCreate(name.trim() + if (addon) ".omwaddon" else ".esp", author, desc, masters.filter { it in chosen })
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Editor(s: CsSession, onClose: () -> Unit) {
    var confirmClose by remember { mutableStateOf(false) }
    var header by remember { mutableStateOf(false) }
    var addToLoadOrder by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val doc = s.doc

    fun save() {
        scope.launch {
            try {
                withContext(Dispatchers.Default) { doc.save(s.workspace::masterSize) }
                s.changed()
                s.message = "Saved ${baseName(doc.plugin.path)}"
                val name = baseName(doc.plugin.path)
                if (s.workspace.loadOrder.none { it.name.equals(name, ignoreCase = true) }) addToLoadOrder = true
            } catch (e: Exception) {
                s.message = "Save failed: ${(e as? ToolException)?.message ?: e}"
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // the bar: file, undo, redo, header, save, close
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (doc.dirty) confirmClose = true else onClose() }) { Icon(Icons.Default.Close, "Close") }
            Column(Modifier.weight(1f)) {
                Text(baseName(doc.plugin.path) + if (s.version >= 0 && doc.dirty) " *" else "", fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(s.message.ifEmpty { "Masters: " + doc.masters.joinToString { it.name } }, fontSize = 11.sp, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            FilterChip(s.onlyPlugin, {
                s.onlyPlugin = !s.onlyPlugin
                // land on a type the plugin has something in
                if (s.onlyPlugin && doc.plugin.records.none { it.tag == s.type }) doc.plugin.records.firstOrNull { it.tag in EDITABLE_TYPES }?.let { s.type = it.tag; s.selected = null }
            }, label = { Text("Only this plugin") })
            IconButton(enabled = s.version >= 0 && doc.canUndo, onClick = { doc.undo(); s.changed() }) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
            IconButton(enabled = s.version >= 0 && doc.canRedo, onClick = { doc.redo(); s.changed() }) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
            TextButton(onClick = { header = true }) { Text("Header") }
            Button(onClick = ::save) {
                Icon(Icons.Default.Save, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Save")
            }
        }
        HorizontalDivider()
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth > 900.dp
            val row = remember(s.version, s.selected) { s.row() }
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    TypeList(s, Modifier.width(190.dp).fillMaxHeight())
                    RecordList(s, Modifier.width(320.dp).fillMaxHeight())
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        if (row != null) RecordOrCell(s, row) else Text("Pick a record, or + to make one", color = Color.Gray, modifier = Modifier.padding(16.dp))
                    }
                }
            } else {
                // narrow: one pane at a time, back goes up
                var showTypes by remember { mutableStateOf(false) }
                when {
                    row != null -> Column {
                        TextButton(onClick = { s.selected = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                            Text(RECORD_DEFS[s.type]?.label ?: s.type)
                        }
                        RecordOrCell(s, row)
                    }
                    showTypes -> TypeList(s, Modifier.fillMaxSize()) { showTypes = false }
                    else -> Column {
                        TextButton(onClick = { showTypes = true }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                            Text("Types")
                        }
                        RecordList(s, Modifier.fillMaxSize())
                    }
                }
            }
        }
    }

    if (confirmClose) AlertDialog(
        onDismissRequest = { confirmClose = false },
        title = { Text("Close without saving?") },
        text = { Text("The changes to ${baseName(doc.plugin.path)} since the last save are lost.") },
        confirmButton = { TextButton(onClick = { confirmClose = false; onClose() }) { Text("Close") } },
        dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("Cancel") } },
    )
    if (header) HeaderDialog(s) { header = false }
    if (addToLoadOrder) AlertDialog(
        onDismissRequest = { addToLoadOrder = false },
        title = { Text("Add to the load order?") },
        text = { Text("${baseName(doc.plugin.path)} is not in openmw.cfg yet. Add it (and its folder) so the game loads it?") },
        confirmButton = {
            TextButton(onClick = {
                addToLoadOrder = false
                try {
                    val fs = s.doc.fs
                    val cfg = OpenMWPaths.USER_OPENMW_CFG
                    val omw = readOpenMW(cfg, Platform.features.toolPlatform!!, fs)
                    enableInOpenMWConfig(fs, cfg, omw, "cs", dirName(doc.plugin.path), content = listOf(baseName(doc.plugin.path)))
                    s.message = "Added ${baseName(doc.plugin.path)} to the load order"
                } catch (e: Exception) {
                    s.message = "Could not add it: ${(e as? ToolException)?.message ?: e}"
                }
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = { addToLoadOrder = false }) { Text("Not now") } },
    )
}

@Composable
private fun HeaderDialog(s: CsSession, onDismiss: () -> Unit) {
    val (a, d) = remember { s.doc.headerText() }
    var author by remember { mutableStateOf(a) }
    var desc by remember { mutableStateOf(d) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Header") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(author, { author = it.take(31) }, label = { Text("Author") }, singleLine = true)
                OutlinedTextField(desc, { desc = it.take(255) }, label = { Text("Description") })
                Text("Masters", fontWeight = FontWeight.SemiBold)
                s.doc.plugin.masters.forEach { (m, size) -> Text("$m  ($size bytes)", fontSize = 12.sp, color = Color.LightGray) }
                Text("${s.doc.plugin.records.size} records", fontSize = 12.sp, color = Color.Gray)
            }
        },
        confirmButton = { TextButton(onClick = { s.doc.setHeaderText(author, desc); s.changed(); onDismiss() }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TypeList(s: CsSession, modifier: Modifier, onPicked: () -> Unit = {}) {
    val all = remember(s.version) { s.doc.counts() }
    val own = remember(s.version) { s.doc.plugin.records.groupingBy { it.tag }.eachCount() }
    val counts = if (s.onlyPlugin) own else all
    // with the filter on, the types the plugin touches come first
    val types = if (s.onlyPlugin) EDITABLE_TYPES.sortedBy { (own[it] ?: 0) == 0 } else EDITABLE_TYPES
    LazyColumn(modifier.background(Color.White.copy(alpha = 0.03f))) {
        items(types) { tag ->
            val selected = tag == s.type
            Row(
                Modifier.fillMaxWidth()
                    .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable { s.type = tag; s.selected = null; onPicked() }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                val none = (counts[tag] ?: 0) == 0
                Text(RECORD_DEFS[tag]?.label ?: tag, modifier = Modifier.weight(1f), fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (none && s.onlyPlugin) Color.Gray else Color.Unspecified)
                Text("${counts[tag] ?: 0}", color = Color.Gray, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun RecordList(s: CsSession, modifier: Modifier) {
    var query by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    val rows = remember(s.version, s.type) { s.doc.rows(s.type) }
    val shown = remember(rows, query, s.onlyPlugin) {
        rows.filter { (!s.onlyPlugin || it.state != RecordState.Base) && (query.isBlank() || query.lowercase() in it.id) }
    }
    // skills and magic effects are fixed by the game: they are edited, never made
    val canCreate = RECORD_DEFS[s.type]?.sub("NAME") != null
    Column(modifier) {
        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, { query = it }, placeholder = { Text("Search ${shown.size}") }, singleLine = true, modifier = Modifier.weight(1f))
            if (canCreate) IconButton(onClick = { creating = true }) { Icon(Icons.Default.Add, "New ${RECORD_DEFS[s.type]?.label}") }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(shown, key = { it.id }) { row ->
                val selected = s.selected?.second == row.id && s.selected?.first == row.tag
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent)
                        .clickable { s.selected = row.tag to row.id }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).background(stateColour.getValue(row.state), RoundedCornerShape(4.dp)))
                    Spacer(Modifier.width(8.dp))
                    Text(row.displayId, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (row.state == RecordState.Deleted) Color.Gray else Color.Unspecified)
                }
            }
        }
    }
    if (creating) IdDialog("New ${RECORD_DEFS[s.type]?.label}", "", onDismiss = { creating = false }) { id ->
        creating = false
        if (s.doc.rows(s.type).any { it.id == id.lowercase() }) s.message = "$id already exists"
        else {
            s.doc.create(s.type, id)
            s.changed()
            s.selected = s.type to id.lowercase()
        }
    }
}

@Composable
internal fun IdDialog(title: String, initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var id by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(id, { id = it.take(31) }, label = { Text("ID") }, singleLine = true) },
        confirmButton = { TextButton(enabled = id.isNotBlank(), onClick = { onOk(id.trim()) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun SmallButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.height(34.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)) {
        Text(label, fontSize = 13.sp)
    }
}
