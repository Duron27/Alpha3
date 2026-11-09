package org.alpha3.launcher.cs.save

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.alpha3.launcher.paths.OpenMWPaths
import org.alpha3.launcher.tools.openmw.ToolException
import org.alpha3.launcher.tools.openmw.ToolFs
import org.alpha3.launcher.tools.openmw.baseName
import org.alpha3.launcher.tools.openmw.dirName
import org.alpha3.launcher.tools.openmw.joinPath
import org.jetbrains.compose.resources.decodeToImageBitmap

private val Gold = Color(0xFFCAA560)
private val Panel = Color.White.copy(alpha = 0.05f)

/** A save found in the saves folder: its character folder and file. */
private class SaveFileEntry(val character: String, val path: String, val modified: Long)

private fun findSaves(fs: ToolFs): List<SaveFileEntry> {
    val root = OpenMWPaths.USER_SAVES
    if (!fs.isDirectory(root)) return emptyList()
    return fs.list(root).orEmpty().flatMap { character ->
        val dir = joinPath(root, character)
        if (!fs.isDirectory(dir)) return@flatMap emptyList()
        fs.list(dir).orEmpty().filter { it.endsWith(".omwsave", true) }.map {
            val p = joinPath(dir, it)
            SaveFileEntry(character, p, fs.lastModified(p))
        }
    }.sortedByDescending { it.modified }
}

/**
 * The saved game editor: pick a save, change the character (name, level, attributes, skills, health, magicka,
 * fatigue, gold, bounty, factions), its inventory, the game's globals and quest stages, and save it all as a new
 * file next to the original, which stays as it was.
 */
@Composable
fun SaveEditorScreen(fs: ToolFs, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var saves by remember { mutableStateOf<List<SaveFileEntry>?>(null) }
    var open by remember { mutableStateOf<SaveGame?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) { saves = withContext(Dispatchers.Default) { findSaves(fs) } }

    Box(Modifier.fillMaxSize()) {
        val s = open
        if (s == null) {
            SaveList(saves, onBack = onClose) { entry ->
                busy = "Opening ${baseName(entry.path)}"
                scope.launch {
                    try {
                        open = withContext(Dispatchers.Default) { SaveGame.open(fs, entry.path) }
                    } catch (e: Exception) {
                        error = (e as? ToolException)?.message ?: e.toString()
                    } finally {
                        busy = null
                    }
                }
            }
        } else {
            SaveEditor(s, onBack = { open = null; reload++ }, onError = { error = it }, onBusy = { busy = it })
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
                title = { Text("Saved games") },
                text = { Text(e) },
                confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } },
            )
        }
    }
}

@Composable
private fun SaveList(saves: List<SaveFileEntry>?, onBack: () -> Unit, onPick: (SaveFileEntry) -> Unit) {
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Saved games", style = MaterialTheme.typography.titleLarge)
        }
        Text("Pick a save to edit. Your changes are saved as a new file next to it; the original is never changed.",
            style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
        when {
            saves == null -> CircularProgressIndicator()
            saves.isEmpty() -> Text("No saves yet in ${OpenMWPaths.USER_SAVES}", color = Color.LightGray)
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                var last: String? = null
                for (e in saves.sortedWith(compareBy<SaveFileEntry> { it.character.lowercase() }.thenByDescending { it.modified })) {
                    if (e.character != last) {
                        last = e.character
                        item(key = "h:${e.character}") {
                            Text(e.character, color = Gold, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                        }
                    }
                    item(key = e.path) {
                        Surface(color = Panel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().clickable { onPick(e) }) {
                            Text(baseName(e.path).removeSuffix(".omwsave"), modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp))
                        }
                    }
                }
            }
        }
    }
}

private enum class SaveTab(val title: String) { CHARACTER("Character"), INVENTORY("Inventory"), GLOBALS("Globals"), QUESTS("Quests") }

@Composable
private fun SaveEditor(s: SaveGame, onBack: () -> Unit, onError: (String) -> Unit, onBusy: (String?) -> Unit) {
    val scope = rememberCoroutineScope()
    // every edit bumps the save's version: reading it here redraws the screens
    var tick by remember { mutableIntStateOf(0) }
    fun act(block: () -> Unit) { block(); tick = s.version }
    @Suppress("UNUSED_VARIABLE") val t = tick
    var tab by remember { mutableStateOf(SaveTab.CHARACTER) }
    var saving by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var savedTo by remember { mutableStateOf<String?>(null) }
    val shot = remember(s) { s.screenshot?.let { runCatching { it.decodeToImageBitmap() }.getOrNull() } }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            shot?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.size(72.dp, 44.dp).background(Color.Black, RoundedCornerShape(4.dp))) }
            Column(Modifier.weight(1f)) {
                Text("${s.playerName} · level ${s.level} ${s.className}", fontWeight = FontWeight.Bold, maxLines = 1)
                Text("${s.cellName} · day ${s.gameDays} · ${baseName(s.path)}", style = MaterialTheme.typography.bodySmall, color = Color.LightGray, maxLines = 1)
            }
            Button(onClick = { saving = true }) {
                Icon(Icons.Default.Save, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Save as")
            }
        }
        progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) }
        savedTo?.let { Text("Saved as ${baseName(it)}: load it from the game's Load menu", color = Color(0xFF8FD98F), style = MaterialTheme.typography.bodySmall) }
        PrimaryTabRow(selectedTabIndex = tab.ordinal, containerColor = Color.Transparent) {
            SaveTab.entries.forEach { e -> Tab(selected = e == tab, onClick = { tab = e }, text = { Text(e.title) }) }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
            when (tab) {
                SaveTab.CHARACTER -> CharacterTab(s, ::act)
                SaveTab.INVENTORY -> InventoryTab(s, ::act)
                SaveTab.GLOBALS -> GlobalsTab(s, ::act)
                SaveTab.QUESTS -> QuestsTab(s, ::act)
            }
        }
    }

    if (saving) SaveAsDialog(s, onDismiss = { saving = false }) { description, fileName ->
        saving = false
        val target = joinPath(dirName(s.path), fileName)
        onBusy("Saving $fileName")
        scope.launch {
            try {
                if (description.isNotBlank()) act { s.description = description }
                withContext(Dispatchers.Default) { s.writeTo(target) { p -> progress = p } }
                savedTo = target
            } catch (e: Exception) {
                onError((e as? ToolException)?.message ?: e.toString())
            } finally {
                progress = null
                onBusy(null)
            }
        }
    }
}

@Composable
private fun SaveAsDialog(s: SaveGame, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    val original = baseName(s.path).removeSuffix(".omwsave")
    var description by remember { mutableStateOf("${s.description} (edited)") }
    var fileName by remember { mutableStateOf("$original edited") }
    val clean = fileName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
    val target = "$clean.omwsave"
    val same = target.equals(baseName(s.path), true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save as a new file") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(description, { description = it }, label = { Text("Name in the game's Load menu") }, singleLine = true)
                OutlinedTextField(fileName, { fileName = it }, label = { Text("File name") }, singleLine = true, isError = same || clean.isEmpty())
                Text(if (same) "Pick a different name: the original stays as it is" else "Saved next to the original, as $target",
                    style = MaterialTheme.typography.bodySmall, color = if (same) Color(0xFFE57373) else Color.LightGray)
            }
        },
        confirmButton = { TextButton(enabled = !same && clean.isNotEmpty(), onClick = { onSave(description.trim(), target) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// --- fields ---------------------------------------------------------------------------------------------------------

/** A number field: applied as soon as what is typed is a number. */
@Composable
private fun NumberField(label: String, value: Number, modifier: Modifier = Modifier, decimals: Boolean = false, onValue: (Double) -> Unit) {
    fun show(v: Number) = if (decimals) v.toString() else v.toDouble().let { if (it == kotlin.math.floor(it)) it.toLong().toString() else it.toString() }
    var text by remember(value) { mutableStateOf(show(value)) }
    OutlinedTextField(
        value = text,
        onValueChange = { t -> text = t; t.toDoubleOrNull()?.let(onValue) },
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimals) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun Section(title: String) {
    Text(title, color = Gold, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

/** Lays out fields two or three to a row. */
@Composable
private fun <T> FieldGrid(entries: List<T>, perRow: Int = 3, cell: @Composable (T, Modifier) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in entries.chunked(perRow)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (e in row) cell(e, Modifier.weight(1f))
            repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

// --- tabs -----------------------------------------------------------------------------------------------------------

@Composable
private fun CharacterTab(s: SaveGame, act: (() -> Unit) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Section("Character")
            var name by remember { mutableStateOf(s.playerName) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it; act { s.playerName = it } }, label = { Text("Name") }, singleLine = true, modifier = Modifier.weight(2f))
                NumberField("Level", s.level, Modifier.weight(1f)) { act { s.level = it.toInt().coerceIn(1, 65535) } }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Gold", s.gold, Modifier.weight(1f)) { act { s.setGold(it.toInt().coerceAtLeast(0)) } }
                NumberField("Bounty", s.bounty, Modifier.weight(1f)) { act { s.bounty = it.toInt().coerceAtLeast(0) } }
                NumberField("Reputation", s.reputation, Modifier.weight(1f)) { act { s.reputation = it.toInt() } }
            }
        }
        if (!s.canEditPlayer) {
            item { Text("This save has no stats for the player yet.", color = Color.LightGray) }
            return@LazyColumn
        }
        // each stat shows when the save has it: a new game's save holds less than an old one's
        val dyn = DYNAMICS.indices.filter { s.dynamic(it) != null }
        if (dyn.isNotEmpty()) item {
            Section("Health, magicka, fatigue (base / current)")
            FieldGrid(dyn, perRow = 3) { i, m ->
                val d = s.dynamic(i)
                Column(m, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    NumberField("${DYNAMICS[i]} base", d?.base ?: 0f) { v -> act { s.setDynamic(i, v.toFloat(), null) } }
                    NumberField("${DYNAMICS[i]} now", d?.current ?: 0f) { v -> act { s.setDynamic(i, null, v.toFloat()) } }
                }
            }
        }
        val attrs = ATTRIBUTES.indices.filter { s.attribute(it) != null }
        if (attrs.isNotEmpty()) item {
            Section("Attributes")
            FieldGrid(attrs, perRow = 4) { i, m ->
                NumberField(ATTRIBUTES[i], s.attribute(i)?.base ?: 0f, m) { v -> act { s.setAttribute(i, v.toFloat()) } }
            }
        }
        val skills = SKILLS.indices.filter { s.skill(it) != null }
        if (skills.isNotEmpty()) item {
            Section("Skills")
            FieldGrid(skills, perRow = 3) { i, m ->
                NumberField(SKILLS[i], s.skill(i)?.base ?: 0f, m) { v -> act { s.setSkill(i, v.toFloat()) } }
            }
        }
        val factions = s.factions
        if (factions.isNotEmpty()) {
            item { Section("Factions (rank / reputation)") }
            items(factions, key = { it.id }) { f ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 3.dp)) {
                    Text(f.id + if (f.expelled) " (expelled)" else "", modifier = Modifier.weight(2f), maxLines = 2)
                    NumberField("Rank", f.rank, Modifier.weight(1f)) { v -> act { s.setFaction(f.id, v.toInt().coerceIn(-1, 9), null) } }
                    NumberField("Reputation", f.reputation, Modifier.weight(1f)) { v -> act { s.setFaction(f.id, null, v.toInt()) } }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun InventoryTab(s: SaveGame, act: (() -> Unit) -> Unit) {
    if (!s.canEditInventory) {
        Text("This save's player record has no place for an inventory, so it can't be edited.", color = Color(0xFFE57373))
        return
    }
    var newId by remember { mutableStateOf("") }
    var newCount by remember { mutableStateOf("1") }
    var filter by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(newId, { newId = it }, label = { Text("Add item by id (e.g. gold_001)") }, singleLine = true, modifier = Modifier.weight(2f))
            OutlinedTextField(newCount, { newCount = it }, label = { Text("Count") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(0.8f))
            Button(enabled = newId.isNotBlank() && (newCount.toIntOrNull() ?: 0) > 0, onClick = {
                act { s.addItem(newId.trim(), newCount.toIntOrNull() ?: 1) }
                newId = ""; newCount = "1"
            }) { Icon(Icons.Default.Add, "Add") }
        }
        Text("The id must be an item of your load order's content, or the game drops it when loading.",
            style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
        OutlinedTextField(filter, { filter = it }, label = { Text("Search ${s.items.size} items") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
        val shown = s.items.filter { filter.isBlank() || it.id.contains(filter.trim(), true) }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(shown, key = { "${it.index}:${it.id}" }) { item ->
                Surface(color = Panel, shape = RoundedCornerShape(8.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(2f)) {
                            Text(item.id, maxLines = 1)
                            val notes = listOfNotNull(
                                "equipped".takeIf { item.equipped },
                                item.charge?.let { "enchantment charge ${it.toInt()}" },
                            )
                            if (notes.isNotEmpty()) Text(notes.joinToString(" · "), fontSize = 11.sp, color = Gold)
                        }
                        NumberField("Count", item.count, Modifier.weight(0.8f)) { v -> act { s.setItemCount(item.index, v.toInt().coerceAtLeast(1)) } }
                        if (item.condition != null) NumberField("Condition", item.condition, Modifier.weight(0.9f)) { v -> act { s.setItemCondition(item.index, v.toInt().coerceAtLeast(0)) } }
                        else Spacer(Modifier.weight(0.9f))
                        IconButton(onClick = { act { s.removeItem(item.index) } }) { Icon(Icons.Default.Delete, "Remove", tint = Color(0xFFE57373)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun GlobalsTab(s: SaveGame, act: (() -> Unit) -> Unit) {
    var filter by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(filter, { filter = it }, label = { Text("Search ${s.globals.size} globals") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Scripts and quests keep their state in globals. Change them only when you know what a value means.",
            style = MaterialTheme.typography.bodySmall, color = Color.LightGray, modifier = Modifier.padding(vertical = 4.dp))
        val shown = s.globals.filter { filter.isBlank() || it.id.contains(filter.trim(), true) }
        LazyColumn(Modifier.weight(1f)) {
            items(shown, key = { it.id }) { g ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 2.dp)) {
                    Column(Modifier.weight(2f)) {
                        Text(g.id, maxLines = 1)
                        Text(when (g.type) { 's' -> "short"; 'l' -> "long"; else -> "float" }, fontSize = 11.sp, color = Color.Gray)
                    }
                    NumberField("Value", if (g.type == 'f') g.value else g.value.toInt(), Modifier.weight(1f), decimals = g.type == 'f') { v ->
                        act { s.setGlobal(g, v.toFloat()) }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuestsTab(s: SaveGame, act: (() -> Unit) -> Unit) {
    var filter by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        var newQuest by remember { mutableStateOf("") }
        var newStage by remember { mutableStateOf("10") }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(newQuest, { newQuest = it }, label = { Text("Start a quest by id (e.g. A1_1_FindSpymaster)") }, singleLine = true, modifier = Modifier.weight(2f))
            OutlinedTextField(newStage, { newStage = it }, label = { Text("Stage") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(0.7f))
            Button(enabled = newQuest.isNotBlank() && newStage.toIntOrNull() != null, onClick = {
                act { s.startQuest(newQuest, newStage.toInt()) }
                newQuest = ""
            }) { Icon(Icons.Default.Add, "Start") }
        }
        OutlinedTextField(filter, { filter = it }, label = { Text("Search ${s.quests.size} quests") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("The stage is the quest's journal index. ${s.journalEntries} journal entries are kept as they are.",
            style = MaterialTheme.typography.bodySmall, color = Color.LightGray, modifier = Modifier.padding(vertical = 4.dp))
        val shown = s.quests.filter { filter.isBlank() || it.id.contains(filter.trim(), true) }
        LazyColumn(Modifier.weight(1f)) {
            items(shown, key = { it.id }) { q ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 2.dp)) {
                    Text(q.id, modifier = Modifier.weight(2f), maxLines = 1)
                    NumberField("Stage", q.stage, Modifier.weight(0.8f)) { v -> act { s.setQuest(q, v.toInt().coerceAtLeast(0), null) } }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(q.finished, { v -> act { s.setQuest(q, null, v) } })
                        Text("Done", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

