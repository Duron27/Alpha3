package org.alpha3.launcher.cs.lua.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.alpha3.launcher.cs.lua.Block
import org.alpha3.launcher.cs.lua.Handler
import org.alpha3.launcher.cs.lua.LuaApi
import org.alpha3.launcher.cs.lua.LuaCheck
import org.alpha3.launcher.cs.lua.LuaGen
import org.alpha3.launcher.cs.lua.LuaMod
import org.alpha3.launcher.cs.lua.LuaModStore
import org.alpha3.launcher.cs.lua.LuaScript
import org.alpha3.launcher.cs.lua.ModTemplates
import org.alpha3.launcher.cs.lua.Place
import org.alpha3.launcher.cs.lua.Problem
import org.alpha3.launcher.cs.lua.ScriptFunction
import org.alpha3.launcher.cs.lua.ScriptKind
import org.alpha3.launcher.cs.lua.ScriptVar
import org.alpha3.launcher.cs.lua.Severity
import org.alpha3.launcher.cs.lua.Trigger
import org.alpha3.launcher.cs.lua.allBlocks
import org.alpha3.launcher.cs.lua.identifier
import org.alpha3.launcher.cs.lua.insert
import org.alpha3.launcher.cs.lua.move
import org.alpha3.launcher.cs.lua.newUid
import org.alpha3.launcher.cs.lua.update
import org.alpha3.launcher.cs.lua.walk
import org.alpha3.launcher.tools.openmw.ToolException
import org.alpha3.launcher.tools.openmw.ToolFs
import org.alpha3.launcher.tools.openmw.enableInOpenMWConfig
import org.alpha3.launcher.tools.openmw.readOpenMW
import org.alpha3.launcher.paths.OpenMWPaths
import org.alpha3.launcher.utils.Platform

private val SEV_COLOUR = mapOf(Severity.Error to Color(0xFFE57373), Severity.Warning to Color(0xFFE0B050), Severity.Info to Color(0xFF7FB2FF))

/** The mod being edited, with undo. */
private class Project(start: LuaMod) {
    var mod by mutableStateOf(start)
    private val undo = ArrayList<LuaMod>()
    private val redo = ArrayList<LuaMod>()
    var canUndo by mutableStateOf(false)
    var canRedo by mutableStateOf(false)
    var dirty by mutableStateOf(false)

    fun change(next: LuaMod) {
        if (next == mod) return
        undo += mod
        if (undo.size > 200) undo.removeAt(0)
        redo.clear()
        mod = next
        dirty = true
        sync()
    }

    fun undo() { if (undo.isNotEmpty()) { redo += mod; mod = undo.removeAt(undo.size - 1); dirty = true; sync() } }
    fun redo() { if (redo.isNotEmpty()) { undo += mod; mod = redo.removeAt(redo.size - 1); dirty = true; sync() } }
    private fun sync() { canUndo = undo.isNotEmpty(); canRedo = redo.isNotEmpty() }

    fun script(uid: String?) = mod.scripts.firstOrNull { it.uid == uid }
    fun changeScript(uid: String, f: (LuaScript) -> LuaScript) = change(mod.copy(scripts = mod.scripts.map { if (it.uid == uid) f(it) else it }))
}

/** Where a block list lives: a handler's body or a function's. */
private data class ListOwner(val script: String, val handler: String? = null, val function: String? = null)

/**
 * The Lua mod creator: make OpenMW Lua mods from blocks, without knowing the API by heart. A mod is a set of scripts
 * (world, player, on NPCs…); each script has handlers ("When a key is pressed", "Every 5 seconds", "When this NPC
 * is activated"…) holding steps picked from the chain menu. The Lua the game will run is shown beside the blocks
 * and checked as it changes; Save writes the mod folder and Enable adds it to the load order.
 */
@Composable
fun LuaModCreator(fs: ToolFs, root: String, onClose: () -> Unit) {
    val store = remember { LuaModStore(fs, root) }
    var api by remember { mutableStateOf<LuaApi?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var project by remember { mutableStateOf<Project?>(null) }
    LaunchedEffect(Unit) { runCatching { api = withContext(Dispatchers.Default) { LuaApi.load() } }.onFailure { loadError = it.toString() } }
    val a = api
    when {
        loadError != null -> Text("Could not read the Lua API: $loadError", modifier = Modifier.padding(16.dp))
        a == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        project == null -> ModList(store, a, onClose = onClose, onOpen = { project = Project(it) })
        else -> Editor(store, fs, a, project!!, onClose = { project = null })
    }
}

// ---- the list of mods and the template gallery ----

@Composable
private fun ModList(store: LuaModStore, api: LuaApi, onClose: () -> Unit, onOpen: (LuaMod) -> Unit) {
    var mods by remember { mutableStateOf(runCatching { store.list() }.getOrDefault(emptyList())) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<LuaMod?>(null) }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Lua mods", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Button(onClick = { creating = true }) { Icon(Icons.Default.Add, null); Text(" New Lua mod") }
        }
        Text("Make OpenMW Lua mods from blocks. Each mod is its own data folder, saved in ${store.root}. " +
            "The game's Lua API (revision ${api.catalog.apiRevision}) is built in: ${api.modules.size} parts, ${api.catalog.types.values.sumOf { it.fns.size }} functions.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (mods.isEmpty()) Text("No Lua mods yet. Start one from a template.", modifier = Modifier.padding(top = 24.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(mods) { m ->
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().clickable { onOpen(m) }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.title, fontWeight = FontWeight.SemiBold)
                            Text("${m.scripts.size} script${if (m.scripts.size == 1) "" else "s"}: " + m.scripts.joinToString(", ") { "${it.name} (${it.kind.label})" },
                                fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { deleting = m }) { Icon(Icons.Default.Delete, "Delete") }
                    }
                }
            }
        }
    }
    if (creating) NewModDialog(store, onDismiss = { creating = false }) { creating = false; onOpen(it) }
    deleting?.let { m ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${m.title}?") },
            text = { Text("Its folder ${store.folder(m.id)} is deleted. Remove it from the load order in the launcher too.") },
            confirmButton = { TextButton(onClick = { store.delete(m.id); mods = store.list(); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NewModDialog(store: LuaModStore, onDismiss: () -> Unit, onCreate: (LuaMod) -> Unit) {
    var name by remember { mutableStateOf("MyLuaMod") }
    var template by remember { mutableStateOf(ModTemplates.all[1]) }
    val id = identifier(name)
    val taken = store.exists(id)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Lua mod") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, isError = taken,
                    supportingText = { Text(if (taken) "A mod named $id exists already" else "Folder and script names: $id") })
                Text("Start from", fontWeight = FontWeight.SemiBold)
                LazyVerticalGrid(GridCells.Adaptive(200.dp), modifier = Modifier.height(380.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(ModTemplates.all) { t ->
                        val sel = t == template
                        Surface(shape = RoundedCornerShape(10.dp), color = if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.clickable { template = t }) {
                            Column(Modifier.padding(10.dp)) {
                                Text(t.title, fontWeight = FontWeight.SemiBold)
                                Text(t.description, fontSize = 12.sp)
                                Text("Shows: ${t.teaches}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = !taken, onClick = { onCreate(template.make(id).copy(title = name.trim().ifEmpty { id })) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---- the editor ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Editor(store: LuaModStore, fs: ToolFs, api: LuaApi, p: Project, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(p.mod.scripts.firstOrNull()?.uid) }
    var output by remember { mutableStateOf<LuaGen.Output?>(null) }
    var problems by remember { mutableStateOf<List<Problem>>(emptyList()) }
    var message by remember { mutableStateOf("") }
    var editingInfo by remember { mutableStateOf(false) }
    var editingSettings by remember { mutableStateOf(false) }
    var addingScript by remember { mutableStateOf(false) }
    var editingScript by remember { mutableStateOf<String?>(null) }
    var confirmSave by remember { mutableStateOf<Boolean?>(null) }
    var confirmClose by remember { mutableStateOf(false) }
    val focus = remember { mutableStateOf<String?>(null) }

    // generate and check whenever the mod changes (on a worker, a moment after the last change)
    LaunchedEffect(p.mod) {
        delay(150)
        val mod = p.mod
        val (out, found) = withContext(Dispatchers.Default) {
            val gen = LuaGen(api, mod)
            val o = gen.generate()
            val kinds = o.files.keys.associateWith { path ->
                mod.scripts.firstOrNull { gen.scriptPath(it) == path }?.kind
                    ?: o.files["${identifier(mod.id)}.omwscripts"]?.lines()?.firstOrNull { it.endsWith(": $path") }?.substringBefore(":")
                        ?.let { tag -> ScriptKind.entries.firstOrNull { it.tag == tag } }
            }.filterValues { it != null }.mapValues { it.value!! }
            o to (LuaCheck(api).checkMod(o, kinds) + o.notes.map { Problem(Severity.Info, null, 0, 0, it) })
        }
        output = out
        problems = found
    }

    fun save(enable: Boolean) {
        val out = output ?: return
        scope.launch {
            message = try {
                withContext(Dispatchers.Default) {
                    store.save(p.mod, out.files)
                    if (enable) {
                        val cfg = OpenMWPaths.USER_OPENMW_CFG
                        val omw = readOpenMW(cfg, Platform.features.toolPlatform!!, fs)
                        enableInOpenMWConfig(fs, cfg, omw, "lua", store.folder(p.mod.id), content = listOf("${identifier(p.mod.id)}.omwscripts"))
                    }
                }
                p.dirty = false
                if (enable) "Saved and added to the load order: start the game to try it" else "Saved to ${store.folder(p.mod.id)}"
            } catch (e: Exception) {
                "Could not save: ${(e as? ToolException)?.message ?: e}"
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // top bar
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (p.dirty) confirmClose = true else onClose() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Column(Modifier.weight(1f).clickable { editingInfo = true }) {
                Text(p.mod.title + if (p.dirty) " •" else "", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (message.isNotEmpty()) message else "Tap to edit the mod's name and description", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = p::undo, enabled = p.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
            IconButton(onClick = p::redo, enabled = p.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
            IconButton(onClick = { editingSettings = true }) { Icon(Icons.Default.Settings, "Settings page") }
            ProblemsChip(problems)
            IconButton(onClick = { if (problems.any { it.severity == Severity.Error }) confirmSave = false else save(false) }) { Icon(Icons.Default.Save, "Save") }
            Button(onClick = { if (problems.any { it.severity == Severity.Error }) confirmSave = true else save(true) }) { Text("Save & enable") }
        }
        HorizontalDivider()
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 900.dp
            val scripts: @Composable (Modifier) -> Unit = { m ->
                ScriptsPane(p, selected, m, onSelect = { selected = it }, onAdd = { addingScript = true }, onEdit = { editingScript = it })
            }
            val blocks: @Composable (Modifier) -> Unit = { m ->
                val s = p.script(selected)
                if (s == null) Box(m, contentAlignment = Alignment.Center) { Text("Add a script to start") }
                else ScriptPane(api, p, s, problems, focus, m, onEdit = { editingScript = s.uid })
            }
            val side: @Composable (Modifier, Int) -> Unit = { m, tab ->
                val s = p.script(selected)
                when (tab) {
                    0 -> CodePane(api, p, s, output, problems, m)
                    1 -> ProblemsPane(problems, m) { pr ->
                        p.mod.scripts.firstOrNull { output != null && LuaGen(api, p.mod).scriptPath(it) == pr.file }?.let { selected = it.uid }
                        focus.value = pr.uid
                    }
                    else -> HelpPane(api, m)
                }
            }
            if (wide) {
                var tab by remember { mutableStateOf(0) }
                Row(Modifier.fillMaxSize()) {
                    scripts(Modifier.width(230.dp).fillMaxHeight())
                    Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
                    blocks(Modifier.weight(1f).fillMaxHeight())
                    Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
                    Column(Modifier.width(420.dp).fillMaxHeight()) {
                        TabRow(tab) {
                            listOf("Lua", "Problems (${problems.count { it.severity != Severity.Info }})", "Help").forEachIndexed { i, t ->
                                Tab(tab == i, { tab = i }, text = { Text(t, fontSize = 13.sp) })
                            }
                        }
                        side(Modifier.fillMaxSize(), tab)
                    }
                }
            } else {
                var tab by remember { mutableStateOf(1) }
                Column(Modifier.fillMaxSize()) {
                    TabRow(tab) {
                        listOf("Scripts", "Blocks", "Lua", "Problems (${problems.count { it.severity != Severity.Info }})").forEachIndexed { i, t ->
                            Tab(tab == i, { tab = i }, text = { Text(t, fontSize = 12.sp, maxLines = 1) })
                        }
                    }
                    when (tab) {
                        0 -> ScriptsPane(p, selected, Modifier.fillMaxSize(), onSelect = { selected = it; tab = 1 }, onAdd = { addingScript = true }, onEdit = { editingScript = it })
                        1 -> blocks(Modifier.fillMaxSize())
                        2 -> side(Modifier.fillMaxSize(), 0)
                        else -> ProblemsPane(problems, Modifier.fillMaxSize()) { pr ->
                            p.mod.scripts.firstOrNull { LuaGen(api, p.mod).scriptPath(it) == pr.file }?.let { selected = it.uid }
                            focus.value = pr.uid
                            tab = 1
                        }
                    }
                }
            }
        }
    }

    if (editingInfo) ModInfoDialog(p.mod, onDismiss = { editingInfo = false }) { p.change(it); editingInfo = false }
    if (editingSettings) SettingsDialog(p.mod, onDismiss = { editingSettings = false }) { p.change(p.mod.copy(settings = it)); editingSettings = false }
    if (addingScript) NewScriptDialog(p.mod, onDismiss = { addingScript = false }) { s ->
        p.change(p.mod.copy(scripts = p.mod.scripts + s)); selected = s.uid; addingScript = false
    }
    editingScript?.let { uid ->
        p.script(uid)?.let { s ->
            ScriptDialog(api, p.mod, s, output?.files?.get(LuaGen(api, p.mod).scriptPath(s)), onDismiss = { editingScript = null }, onDelete = {
                p.change(p.mod.copy(scripts = p.mod.scripts.filter { it.uid != uid })); selected = p.mod.scripts.firstOrNull()?.uid; editingScript = null
            }) { p.changeScript(uid) { _ -> it }; editingScript = null }
        }
    }
    confirmSave?.let { enable ->
        AlertDialog(
            onDismissRequest = { confirmSave = null },
            title = { Text("The mod has errors") },
            text = { Text("${problems.count { it.severity == Severity.Error }} problem(s) will stop scripts from working in the game. Save anyway?") },
            confirmButton = { TextButton(onClick = { confirmSave = null; save(enable) }) { Text("Save anyway") } },
            dismissButton = { TextButton(onClick = { confirmSave = null }) { Text("Cancel") } },
        )
    }
    if (confirmClose) AlertDialog(
        onDismissRequest = { confirmClose = false },
        title = { Text("Unsaved changes") },
        text = { Text("Save ${p.mod.title} before closing?") },
        confirmButton = { TextButton(onClick = { confirmClose = false; save(false); onClose() }) { Text("Save") } },
        dismissButton = { Row { TextButton(onClick = { confirmClose = false; onClose() }) { Text("Discard") }; TextButton(onClick = { confirmClose = false }) { Text("Cancel") } } },
    )
}

@Composable
private fun ProblemsChip(problems: List<Problem>) {
    val e = problems.count { it.severity == Severity.Error }
    val w = problems.count { it.severity == Severity.Warning }
    val (text, colour) = when {
        e > 0 -> "$e error${if (e == 1) "" else "s"}" to SEV_COLOUR.getValue(Severity.Error)
        w > 0 -> "$w warning${if (w == 1) "" else "s"}" to SEV_COLOUR.getValue(Severity.Warning)
        else -> "✓ OK" to Color(0xFF8FD98F)
    }
    Surface(shape = RoundedCornerShape(50), color = colour.copy(alpha = 0.25f), modifier = Modifier.padding(horizontal = 4.dp)) {
        Text(text, color = colour, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

@Composable
private fun ScriptsPane(p: Project, selected: String?, modifier: Modifier, onSelect: (String) -> Unit, onAdd: () -> Unit, onEdit: (String) -> Unit) {
    Column(modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Scripts", fontWeight = FontWeight.SemiBold)
        p.mod.scripts.forEach { s ->
            val sel = s.uid == selected
            Surface(shape = RoundedCornerShape(8.dp), color = if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().clickable { onSelect(s.uid) }) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(s.kind.label + if (s.code != null) " · Lua" else "", fontSize = 11.sp)
                    }
                    IconButton(onClick = { onEdit(s.uid) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Edit, "Edit script", Modifier.size(18.dp)) }
                }
            }
        }
        OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Text(" Script") }
        Spacer(Modifier.height(8.dp))
        Text("World scripts change the world, player scripts own the screen and keys, scripts on NPCs see what is near them. " +
            "Calls a script can't make are passed to one that can for you.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One script: its handlers and functions as cards of blocks, or its Lua when written by hand. */
@Composable
private fun ScriptPane(api: LuaApi, p: Project, s: LuaScript, problems: List<Problem>, focus: androidx.compose.runtime.MutableState<String?>, modifier: Modifier, onEdit: () -> Unit) {
    var addingHandler by remember { mutableStateOf(false) }
    var chainFor by remember { mutableStateOf<Pair<ListOwner, Place>?>(null) }
    var editBlock by remember { mutableStateOf<Pair<ListOwner, Block>?>(null) }
    var editTrigger by remember { mutableStateOf<Handler?>(null) }
    val byUid = problems.filter { it.uid != null }.groupBy { it.uid!! }

    fun changeList(owner: ListOwner, f: (List<Block>) -> List<Block>) = p.changeScript(s.uid) { sc ->
        when {
            owner.handler != null -> sc.copy(handlers = sc.handlers.map { if (it.uid == owner.handler) it.copy(body = f(it.body)) else it })
            owner.function != null -> sc.copy(functions = sc.functions.map { if (it.uid == owner.function) it.copy(body = f(it.body)) else it })
            else -> sc
        }
    }

    if (s.code != null) {
        Column(modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${s.name}: written as Lua", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { p.changeScript(s.uid) { it.copy(code = null) } }) { Text("Back to blocks") }
            }
            val mine = problems.filter { it.file?.endsWith("/${identifier(s.name, "script")}.lua") == true }
            mine.take(3).forEach { pr -> Text("Line ${pr.line}: ${pr.message}", color = SEV_COLOUR.getValue(pr.severity), fontSize = 12.sp) }
            OutlinedTextField(s.code, { code -> p.changeScript(s.uid) { it.copy(code = code) } }, modifier = Modifier.fillMaxSize(),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
        }
        return
    }

    LazyColumn(modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.name, style = MaterialTheme.typography.titleMedium)
                    Text(s.kind.explain, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (s.variables.isNotEmpty()) Text("Variables: " + s.variables.joinToString(", ") { it.name + if (it.saved) " (saved)" else "" }, fontSize = 12.sp)
                }
                OutlinedButton(onClick = onEdit) { Text("Script…") }
            }
        }
        items(s.handlers, key = { it.uid }) { h ->
            val owner = ListOwner(s.uid, handler = h.uid)
            Card(title = LuaGen.triggerTitle(h.trigger), colour = Color(0xFF8A6A2F), problems = byUid[h.uid].orEmpty(),
                onTitle = { editTrigger = h },
                menu = listOf(
                    "Move up" to { p.changeScript(s.uid) { sc -> sc.copy(handlers = moveIn(sc.handlers, h, -1)) } },
                    "Move down" to { p.changeScript(s.uid) { sc -> sc.copy(handlers = moveIn(sc.handlers, h, 1)) } },
                    "Delete" to { p.changeScript(s.uid) { sc -> sc.copy(handlers = sc.handlers.filter { it.uid != h.uid }) } },
                )) {
                BlockList(api, h.body, null, "body", byUid, focus,
                    onAdd = { place -> chainFor = owner to place },
                    onEdit = { b -> editBlock = owner to b },
                    onChange = { f -> changeList(owner, f) })
            }
        }
        items(s.functions, key = { it.uid }) { f ->
            val owner = ListOwner(s.uid, function = f.uid)
            Card(title = "Function ${f.name}(${f.params.joinToString(", ")})${if (s.interfaceName != null && f.exported) "  · I.${s.interfaceName}.${f.name}" else ""}",
                colour = Color(0xFF2F5D8A), problems = byUid[f.uid].orEmpty(), onTitle = onEdit,
                menu = listOf("Delete" to { p.changeScript(s.uid) { sc -> sc.copy(functions = sc.functions.filter { it.uid != f.uid }) } })) {
                BlockList(api, f.body, null, "body", byUid, focus,
                    onAdd = { place -> chainFor = owner to place },
                    onEdit = { b -> editBlock = owner to b },
                    onChange = { fn -> changeList(owner, fn) })
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 24.dp)) {
                Button(onClick = { addingHandler = true }) { Icon(Icons.Default.Add, null); Text(" When…") }
                OutlinedButton(onClick = {
                    p.changeScript(s.uid) { it.copy(functions = it.functions + ScriptFunction(name = "myFunction${it.functions.size + 1}")) }
                }) { Icon(Icons.Default.Add, null); Text(" Function") }
            }
        }
    }

    val names = remember(s, p.mod) { scopeNames(api, s) }
    val ctx = ExprContext(api, p.mod, s.kind, names)
    if (addingHandler) ChainMenu(api, p.mod, s.kind, ChainMode.Trigger, emptyList(), onDismiss = { addingHandler = false }) { r ->
        addingHandler = false
        if (r is Trigger) {
            val h = Handler(trigger = r)
            p.changeScript(s.uid) { it.copy(handlers = it.handlers + h) }
            if (r !is Trigger.Engine && r !is Trigger.SettingChanged) editTrigger = h
        }
    }
    chainFor?.let { (owner, place) ->
        ChainMenu(api, p.mod, s.kind, ChainMode.Block, s.functions.map { it.name }, onDismiss = { chainFor = null }) { r ->
            chainFor = null
            if (r is Block) {
                changeList(owner) { it.insert(place, r) }
                if (r !is Block.Stop) editBlock = owner to r
            }
        }
    }
    editBlock?.let { (owner, b) ->
        BlockDialog(b, ctx, onDismiss = { editBlock = null }) { nb ->
            changeList(owner) { list -> list.update(b.uid) { nb } }
            editBlock = null
        }
    }
    editTrigger?.let { h ->
        TriggerDialog(h.trigger, api, onDismiss = { editTrigger = null }) { t ->
            p.changeScript(s.uid) { sc -> sc.copy(handlers = sc.handlers.map { if (it.uid == h.uid) it.copy(trigger = t) else it }) }
            editTrigger = null
        }
    }
}

private fun <T> moveIn(list: List<T>, item: T, by: Int): List<T> {
    val i = list.indexOf(item)
    val j = (i + by).coerceIn(0, list.size - 1)
    if (i < 0 || i == j) return list
    return list.toMutableList().also { it.removeAt(i); it.add(j, item) }
}

/** The names a value can refer to in a script: its variables, the handlers' arguments, results kept, loop items. */
private fun scopeNames(api: LuaApi, s: LuaScript): List<String> {
    val out = LinkedHashSet<String>()
    s.variables.forEach { out += it.name }
    s.handlers.forEach { h ->
        when (val t = h.trigger) {
            is Trigger.Engine -> api.handler(t.name)?.params?.let { out += it }
            is Trigger.Event -> out += "data"
            is Trigger.Key -> out += "key"
            is Trigger.Console -> { out += "args"; out += "selectedObject" }
            is Trigger.SettingChanged -> out += "key"
            is Trigger.Every -> {}
        }
    }
    s.functions.forEach { out += it.params }
    allBlocks(s).forEach { b ->
        when (b) {
            is Block.Call -> b.saveAs?.let { out += it }
            is Block.Set -> out += b.name
            is Block.ForEach -> { out += b.item }
            is Block.Repeat -> out += b.counter
            else -> {}
        }
    }
    return out.toList()
}

@Composable
private fun Card(title: String, colour: Color, problems: List<Problem>, onTitle: () -> Unit, menu: List<Pair<String, () -> Unit>>, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().background(colour.copy(alpha = 0.85f)).clickable(onClick = onTitle).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
                problems.firstOrNull()?.let { Text("●", color = SEV_COLOUR.getValue(it.severity)) }
                Box {
                    IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "More", tint = Color.White) }
                    DropdownMenu(open, { open = false }) {
                        menu.forEach { (label, act) -> DropdownMenuItem(text = { Text(label) }, onClick = { open = false; act() }) }
                    }
                }
            }
            problems.forEach { pr -> Text(pr.message, color = SEV_COLOUR.getValue(pr.severity), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) }
            Column(Modifier.padding(8.dp)) { content() }
        }
    }
}

private fun blockColour(b: Block): Color = when (b) {
    is Block.Call -> if (b.ref.startsWith("openmw.ui:")) Color(0xFF6B4A8A) else Color(0xFF2F5D8A)
    is Block.If, is Block.ForEach, is Block.Repeat, is Block.After, is Block.Stop, is Block.Return -> Color(0xFF8A6A2F)
    is Block.Set -> Color(0xFF2F7A5D)
    is Block.Send -> Color(0xFF8A3F3F)
    is Block.Lua, is Block.Note -> Color(0xFF555555)
}

/** A list of blocks with their nested lists, each block a row that opens its editor, and an add button at the end. */
@Composable
private fun BlockList(
    api: LuaApi, blocks: List<Block>, parent: String?, slot: String, problems: Map<String, List<Problem>>,
    focus: androidx.compose.runtime.MutableState<String?>,
    onAdd: (Place) -> Unit, onEdit: (Block) -> Unit, onChange: ((List<Block>) -> List<Block>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        blocks.forEach { b ->
            BlockRow(api, b, problems[b.uid].orEmpty(), focus.value == b.uid, onEdit = { focus.value = b.uid; onEdit(b) },
                onUp = { onChange { it.move(b.uid, -1) } }, onDown = { onChange { it.move(b.uid, 1) } },
                onDelete = { onChange { it.update(b.uid) { null } } },
                onCopy = { onChange { it.insert(Place(after = b.uid), copyBlock(b)) } },
                onAddAfter = { onAdd(Place(after = b.uid)) })
            val nested: List<Pair<String, List<Block>>> = when (b) {
                is Block.If -> listOf("then" to b.then) + if (b.otherwise.isNotEmpty()) listOf("else" to b.otherwise) else emptyList()
                is Block.ForEach -> listOf("body" to b.body)
                is Block.Repeat -> listOf("body" to b.body)
                is Block.After -> listOf("body" to b.body)
                else -> emptyList()
            }
            nested.forEach { (sl, list) ->
                if (sl == "else") Text("Otherwise", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 18.dp))
                Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                    Box(Modifier.padding(start = 10.dp).width(3.dp).fillMaxHeight().background(blockColour(b).copy(alpha = 0.6f)))
                    Column(Modifier.padding(start = 6.dp).weight(1f)) {
                        BlockList(api, list, b.uid, sl, problems, focus, onAdd, onEdit, onChange)
                    }
                }
            }
            if (b is Block.If && b.otherwise.isEmpty()) TextButton(onClick = { onAdd(Place(parent = b.uid, slot = "else")) }, modifier = Modifier.padding(start = 16.dp)) {
                Text("+ Otherwise…", fontSize = 12.sp)
            }
        }
        TextButton(onClick = { onAdd(Place(parent = parent, slot = slot)) }) {
            Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Text(if (blocks.isEmpty()) " Add a step" else " Add", fontSize = 13.sp)
        }
    }
}

private fun copyBlock(b: Block): Block = when (b) {
    is Block.Call -> b.copy(uid = newUid())
    is Block.If -> b.copy(uid = newUid(), then = b.then.map(::copyBlock), otherwise = b.otherwise.map(::copyBlock))
    is Block.ForEach -> b.copy(uid = newUid(), body = b.body.map(::copyBlock))
    is Block.Repeat -> b.copy(uid = newUid(), body = b.body.map(::copyBlock))
    is Block.Set -> b.copy(uid = newUid())
    is Block.Send -> b.copy(uid = newUid())
    is Block.After -> b.copy(uid = newUid(), body = b.body.map(::copyBlock))
    is Block.Return -> b.copy(uid = newUid())
    is Block.Stop -> b.copy(uid = newUid())
    is Block.Lua -> b.copy(uid = newUid())
    is Block.Note -> b.copy(uid = newUid())
}

@Composable
private fun BlockRow(api: LuaApi, b: Block, problems: List<Problem>, focused: Boolean, onEdit: () -> Unit, onUp: () -> Unit, onDown: () -> Unit,
                     onDelete: () -> Unit, onCopy: () -> Unit, onAddAfter: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val colour = blockColour(b)
    Surface(shape = RoundedCornerShape(8.dp), color = if (focused) colour.copy(alpha = 0.45f) else colour.copy(alpha = 0.28f), modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(5.dp).height(40.dp).background(colour))
                Text(describe(b, api), modifier = Modifier.weight(1f).clickable(onClick = onEdit).padding(horizontal = 10.dp, vertical = 8.dp),
                    fontFamily = if (b is Block.Lua) FontFamily.Monospace else null, fontStyle = if (b is Block.Note) androidx.compose.ui.text.font.FontStyle.Italic else null,
                    maxLines = 3, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                problems.firstOrNull()?.let { Text("●", color = SEV_COLOUR.getValue(it.severity)) }
                IconButton(onClick = onUp, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.KeyboardArrowUp, "Up", Modifier.size(18.dp)) }
                IconButton(onClick = onDown, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.KeyboardArrowDown, "Down", Modifier.size(18.dp)) }
                Box {
                    IconButton(onClick = { open = true }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.MoreVert, "More", Modifier.size(18.dp)) }
                    DropdownMenu(open, { open = false }) {
                        DropdownMenuItem(text = { Text("Edit") }, onClick = { open = false; onEdit() }, leadingIcon = { Icon(Icons.Default.Edit, null) })
                        DropdownMenuItem(text = { Text("Add a step after") }, onClick = { open = false; onAddAfter() }, leadingIcon = { Icon(Icons.Default.Add, null) })
                        DropdownMenuItem(text = { Text("Duplicate") }, onClick = { open = false; onCopy() }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { open = false; onDelete() }, leadingIcon = { Icon(Icons.Default.Delete, null) })
                    }
                }
            }
            problems.forEach { pr -> Text(pr.message, color = SEV_COLOUR.getValue(pr.severity), fontSize = 12.sp, modifier = Modifier.padding(start = 14.dp, end = 8.dp, bottom = 4.dp)) }
        }
    }
}

/** The script's Lua as the game will run it, with the problem lines marked. */
@Composable
private fun CodePane(api: LuaApi, p: Project, s: LuaScript?, output: LuaGen.Output?, problems: List<Problem>, modifier: Modifier) {
    val files = output?.files.orEmpty()
    val gen = remember(p.mod) { LuaGen(api, p.mod) }
    var file by remember(s?.uid) { mutableStateOf(s?.let { gen.scriptPath(it) }) }
    Column(modifier) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            files.keys.forEach { f -> FilterChip(file == f, { file = f }, label = { Text(f.substringAfterLast('/'), fontSize = 11.sp) }) }
        }
        val text = files[file] ?: ""
        val marks = problems.filter { it.file == file }.groupBy { it.line }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(6.dp)) {
            text.lines().forEachIndexed { i, line ->
                val m = marks[i + 1]?.minByOrNull { it.severity.ordinal }
                Row(Modifier.background(m?.let { SEV_COLOUR.getValue(it.severity).copy(alpha = 0.18f) } ?: Color.Transparent)) {
                    Text("${i + 1}".padStart(4) + "  ", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp, softWrap = false)
                }
            }
        }
    }
}

@Composable
private fun ProblemsPane(problems: List<Problem>, modifier: Modifier, onPick: (Problem) -> Unit) {
    if (problems.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) { Text("No problems found. The scripts use the game's API as documented.", modifier = Modifier.padding(16.dp)) }
        return
    }
    LazyColumn(modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(problems) { pr ->
            Surface(shape = RoundedCornerShape(8.dp), color = SEV_COLOUR.getValue(pr.severity).copy(alpha = 0.15f), modifier = Modifier.fillMaxWidth().clickable { onPick(pr) }) {
                Column(Modifier.padding(8.dp)) {
                    Text(pr.severity.name + (pr.file?.let { " · ${it.substringAfterLast('/')}:${pr.line}" } ?: ""), fontSize = 11.sp, color = SEV_COLOUR.getValue(pr.severity))
                    Text(pr.message, fontSize = 13.sp)
                }
            }
        }
    }
}

/** How mods work, in short, and the API's parts with what each can be used in. */
@Composable
private fun HelpPane(api: LuaApi, modifier: Modifier) {
    LazyColumn(modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text("How it fits together", fontWeight = FontWeight.SemiBold)
            Text("• A handler (“When…”) runs its steps when that happens in the game.\n" +
                "• World scripts change the world; player scripts show things and read keys; scripts on NPCs and items see their surroundings.\n" +
                "• Scripts talk by events: “Send an event” on one side, “When the event … arrives” on the other.\n" +
                "• Steps a script can't do are passed to one that can, automatically (shown in orange).\n" +
                "• Variables marked “saved” are kept in the save game.\n" +
                "• Save & enable writes the mod and adds it to the load order. In game, the console command `reloadlua` reloads changed scripts.", fontSize = 12.sp)
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text("The game's Lua API", fontWeight = FontWeight.SemiBold)
        }
        items(api.modules.filter { it.kind != "lua" }) { m ->
            Column {
                Text("${moduleLabel(m)}  (${if (m.kind == "interface") "I." + m.name else m.require})", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text("In ${m.contexts.joinToString(", ")} scripts. ${moduleHint(m)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ModInfoDialog(mod: LuaMod, onDismiss: () -> Unit, onDone: (LuaMod) -> Unit) {
    var m by remember { mutableStateOf(mod) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("About this mod") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(m.title, { m = m.copy(title = it) }, label = { Text("Title") }, singleLine = true)
                OutlinedTextField(m.author, { m = m.copy(author = it) }, label = { Text("Author") }, singleLine = true)
                OutlinedTextField(m.version, { m = m.copy(version = it) }, label = { Text("Version") }, singleLine = true)
                OutlinedTextField(m.description, { m = m.copy(description = it) }, label = { Text("Description") }, minLines = 2)
                Text("Folder and script names: ${m.id}", fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { onDone(m) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NewScriptDialog(mod: LuaMod, onDismiss: () -> Unit, onDone: (LuaScript) -> Unit) {
    var kind by remember { mutableStateOf(ScriptKind.PLAYER) }
    var name by remember { mutableStateOf("") }
    val fallback = when (kind) { ScriptKind.GLOBAL -> "world"; ScriptKind.PLAYER -> "player"; ScriptKind.MENU -> "menu"; else -> kind.name.lowercase() }
    val finalName = identifier(name.ifBlank { fallback }, "script").let { n -> if (mod.scripts.any { it.name == n }) n + (mod.scripts.size + 1) else n }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New script") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ScriptKind.entries.forEach { k ->
                    Surface(shape = RoundedCornerShape(8.dp), color = if (k == kind) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth().clickable { kind = k }) {
                        Column(Modifier.padding(8.dp)) {
                            Text(k.label, fontWeight = FontWeight.SemiBold)
                            Text(k.explain, fontSize = 12.sp)
                        }
                    }
                }
                OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") }, singleLine = true, supportingText = { Text("File: $finalName.lua") })
            }
        },
        confirmButton = { TextButton(onClick = { onDone(LuaScript(name = finalName, kind = kind)) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A script's name, kind, variables, functions' names, interface; and switching it to hand-written Lua. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScriptDialog(api: LuaApi, mod: LuaMod, script: LuaScript, generated: String?, onDismiss: () -> Unit, onDelete: () -> Unit, onDone: (LuaScript) -> Unit) {
    var s by remember { mutableStateOf(script) }
    var confirmDelete by remember { mutableStateOf(false) }
    val ctx = ExprContext(api, mod, s.kind, s.variables.map { it.name })
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Script ${s.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(s.name, { s = s.copy(name = identifier(it, "script")) }, label = { Text("Name") }, singleLine = true)
                Text("Runs as", fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScriptKind.entries.forEach { k -> FilterChip(s.kind == k, { s = s.copy(kind = k) }, label = { Text(k.label, fontSize = 12.sp) }) }
                }
                Text(s.kind.explain, fontSize = 12.sp)
                HorizontalDivider()
                Text("Variables", fontWeight = FontWeight.SemiBold)
                Text("Values the script keeps while it runs. “Saved” ones are written into the save game.", fontSize = 12.sp)
                s.variables.forEachIndexed { i, v ->
                    fun set(n: ScriptVar) { s = s.copy(variables = s.variables.toMutableList().also { it[i] = n }) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(v.name, { set(v.copy(name = identifier(it, "value"))) }, singleLine = true, label = { Text("Name") }, modifier = Modifier.weight(0.4f))
                        Column(Modifier.weight(0.45f).padding(start = 6.dp)) { ExprField("Starts as", v.initial, ctx) { set(v.copy(initial = it)) } }
                        Checkbox(v.saved, { set(v.copy(saved = it)) })
                        Text("Saved", fontSize = 11.sp)
                        IconButton(onClick = { s = s.copy(variables = s.variables.filterIndexed { j, _ -> j != i }) }) { Icon(Icons.Default.Delete, "Remove") }
                    }
                }
                TextButton(onClick = { s = s.copy(variables = s.variables + ScriptVar("value${s.variables.size + 1}", org.alpha3.launcher.cs.lua.Expr.Num(0.0))) }) {
                    Icon(Icons.Default.Add, null); Text(" Add a variable")
                }
                HorizontalDivider()
                Text("Functions", fontWeight = FontWeight.SemiBold)
                s.functions.forEachIndexed { i, f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(f.name, { n -> s = s.copy(functions = s.functions.toMutableList().also { it[i] = f.copy(name = identifier(n, "fn")) }) },
                            singleLine = true, label = { Text("Name") }, modifier = Modifier.weight(0.45f))
                        OutlinedTextField(f.params.joinToString(", "), { t ->
                            s = s.copy(functions = s.functions.toMutableList().also { it[i] = f.copy(params = t.split(",").map { p -> p.trim() }.filter { p -> p.isNotEmpty() }) })
                        }, singleLine = true, label = { Text("Arguments") }, modifier = Modifier.weight(0.55f).padding(start = 6.dp))
                    }
                }
                OutlinedTextField(s.interfaceName ?: "", { s = s.copy(interfaceName = it.ifBlank { null }) }, singleLine = true,
                    label = { Text("Interface name (optional)") }, supportingText = { Text("Other mods can then call the functions as I.<name>.<function>(...)") })
                HorizontalDivider()
                if (s.code == null) TextButton(onClick = { s = s.copy(code = generated ?: "return {}\n") }) { Text("Write this script as Lua (keeps the blocks, edits the code)") }
                else TextButton(onClick = { s = s.copy(code = null) }) { Text("Back to blocks (the hand-written Lua is dropped)") }
                TextButton(onClick = { confirmDelete = true }) { Text("Delete this script", color = Color(0xFFE57373)) }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(s) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete ${s.name}?") },
        text = { Text("Its handlers and blocks are removed from the mod.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}
