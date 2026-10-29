package org.alpha3.launcher.launch

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Gold = Color(0xFFCAA560)

/** Rows of the list: a group heading or an option. */
private sealed interface Row2 {
    data class Header(val title: String, val count: Int) : Row2
    data class Option(val name: String) : Row2
}

/**
 * The Launch options popup, opened from the launcher's menu. Two tabs: the environment variables and the command line options
 * the game starts with. Each is a list of switches grouped by what they do, with the value editor that fits (choices,
 * numbers, text, saved games), a search, and room for something that is not in the list. Every change is saved at once and
 * used the next time the game starts.
 */
@Composable
fun LaunchOptionsDialog(onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }

    val env = remember { mutableStateMapOf<String, OptionEntry>() }
    val args = remember { mutableStateMapOf<String, OptionEntry>() }
    var custom by remember { mutableStateOf("") }
    val saves = remember { mutableStateListOf<Pair<String, String>>() }
    var newName by remember { mutableStateOf("") }
    var newValue by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val loadedEnv = LaunchEnvironment.load()
        val loadedArgs = LaunchArguments.load()
        val loadedCustom = LaunchArguments.loadCustom()
        val found = withContext(Dispatchers.Default) { LaunchArguments.scanSaves() }
        env.putAll(loadedEnv)
        args.putAll(loadedArgs)
        custom = loadedCustom
        saves.addAll(found)
        loaded = true
    }

    fun saveEnv() { val snapshot = env.toMap(); scope.launch { LaunchEnvironment.save(snapshot) } }
    fun saveArgs() { val snapshot = args.toMap(); scope.launch { LaunchArguments.save(snapshot) } }

    val entries = if (tab == 0) env else args
    val catalog = if (tab == 0) LaunchEnvironment.catalog else LaunchArguments.catalog
    val specs = if (tab == 0) LaunchEnvironment.byName else LaunchArguments.byName
    val groups = if (tab == 0) LaunchEnvironment.groups else LaunchArguments.groups

    fun entryOf(name: String) = entries[name] ?: OptionEntry(false, specs[name]?.defaultValue ?: "")
    fun setEntry(name: String, entry: OptionEntry) {
        entries[name] = entry
        if (tab == 0) saveEnv() else saveArgs()
    }

    fun matches(name: String, description: String) = query.isBlank() ||
        name.contains(query.trim(), ignoreCase = true) || description.contains(query.trim(), ignoreCase = true)

    // Entries of the environment tab that are not in the catalog
    val customNames = if (tab == 0) env.keys.filter { it !in LaunchEnvironment.byName }.sorted() else emptyList()
    val rows: List<Row2> = buildList {
        for (group in groups) {
            val inGroup = catalog.filter { it.group == group && matches(it.name, it.description) }
            if (inGroup.isEmpty()) continue
            add(Row2.Header(group, inGroup.size))
            inGroup.forEach { add(Row2.Option(it.name)) }
        }
        val yours = customNames.filter { matches(it, "") }
        if (yours.isNotEmpty()) {
            add(Row2.Header(LaunchEnvironment.GROUP_CUSTOM, yours.size))
            yours.forEach { add(Row2.Option(it)) }
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(10.dp).border(2.dp, Gold, RoundedCornerShape(12.dp)),
            shape = RoundedCornerShape(12.dp),
            color = Color(0xF2101214),
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Launch options", color = Gold, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, softWrap = false, modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${entries.values.count { it.enabled }} on", color = Color.White, fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false, modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    Button(
                        onClick = {
                            entries.keys.toList().forEach { entries[it] = entries.getValue(it).copy(enabled = false) }
                            if (tab == 0) saveEnv() else saveArgs()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A2A2A), contentColor = Color.White),
                        shape = RoundedCornerShape(6.dp),
                    ) { Text("All off", fontSize = 11.sp, maxLines = 1) }
                    Spacer(Modifier.width(6.dp))
                    Button(
                        onClick = onClose,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E3A8A), contentColor = Color.White),
                        shape = RoundedCornerShape(6.dp),
                    ) { Text("Close", fontSize = 11.sp) }
                }

                Text(
                    if (tab == 0) {
                        "Environment variables the game is started with. Switched-on ones are used the next time you launch."
                    } else {
                        "Command line options for OpenMW. Pick what you need; anything else goes in the box at the bottom."
                    },
                    color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp, lineHeight = 14.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )

                TabRow(
                    selectedTabIndex = tab, containerColor = Color.Transparent, contentColor = Gold,
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    Tab(selected = tab == 0, onClick = { tab = 0; query = "" }, text = { Text("Environment", fontSize = 13.sp) })
                    Tab(selected = tab == 1, onClick = { tab = 1; query = "" }, text = { Text("Arguments", fontSize = 13.sp) })
                }

                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("Search by name or description", fontSize = 12.sp) },
                    textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                    colors = fieldColors(), modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                )

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (!loaded) {
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(rows) { row ->
                                when (row) {
                                    is Row2.Header -> Text(
                                        "${row.title}  (${row.count})", color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 2.dp),
                                    )

                                    is Row2.Option -> OptionRow(
                                        name = row.name, spec = specs[row.name], entry = entryOf(row.name), saves = saves,
                                        onToggle = { on -> setEntry(row.name, entryOf(row.name).copy(enabled = on)) },
                                        onValue = { value -> setEntry(row.name, entryOf(row.name).copy(value = value)) },
                                        onRemove = if (specs[row.name] == null) ({ env.remove(row.name); saveEnv() }) else null,
                                    )
                                }
                            }
                            item { Spacer(Modifier.height(8.dp)) }
                        }
                    }
                }

                if (loaded && tab == 0) {
                    Text("Add your own", color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = newName, onValueChange = { newName = it.trim() }, singleLine = true,
                            placeholder = { Text("NAME", fontSize = 12.sp) },
                            textStyle = TextStyle(color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                            colors = fieldColors(), modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = newValue, onValueChange = { newValue = it }, singleLine = true,
                            placeholder = { Text("value", fontSize = 12.sp) },
                            textStyle = TextStyle(color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                            colors = fieldColors(), modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = {
                                if (LaunchEnvironment.isValidName(newName)) {
                                    env[newName] = OptionEntry(true, newValue)
                                    saveEnv()
                                    newName = ""
                                    newValue = ""
                                }
                            },
                            enabled = LaunchEnvironment.isValidName(newName),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B5E20), contentColor = Color.White),
                            shape = RoundedCornerShape(6.dp),
                        ) { Text("Add", fontSize = 12.sp) }
                    }
                }
                if (loaded && tab == 1) {
                    Text("Other arguments", color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = custom,
                        onValueChange = { custom = it; val text = it; scope.launch { LaunchArguments.saveCustom(text) } },
                        placeholder = { Text("--option value --other \"a value with spaces\"", fontSize = 12.sp) },
                        textStyle = TextStyle(color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                        colors = fieldColors(), minLines = 2, maxLines = 4,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                    Text(
                        "Quotes keep a value together. The game's resources folder is passed by the launcher and cannot be changed here.",
                        color = Color.White.copy(alpha = 0.45f), fontSize = 10.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
    focusedBorderColor = Gold, unfocusedBorderColor = Color.White.copy(alpha = 0.25f), cursorColor = Gold,
    focusedPlaceholderColor = Color.White.copy(alpha = 0.4f), unfocusedPlaceholderColor = Color.White.copy(alpha = 0.4f),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionRow(
    name: String,
    spec: OptionSpec?,
    entry: OptionEntry,
    saves: List<Pair<String, String>>,
    onToggle: (Boolean) -> Unit,
    onValue: (String) -> Unit,
    onRemove: (() -> Unit)?,
) {
    Surface(
        color = Color(0xFF1B1E24),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, if (entry.enabled) Gold.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.08f)),
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Switch(
                    checked = entry.enabled, onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(checkedTrackColor = Gold, checkedThumbColor = Color.White),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    FlowRow(verticalArrangement = Arrangement.Center, itemVerticalAlignment = Alignment.CenterVertically) {
                        Text(
                            name, color = if (entry.enabled) Gold else Color.White, fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
                        )
                        if (spec != null) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                spec.source, color = Color.White.copy(alpha = 0.55f), fontSize = 10.sp, maxLines = 1, softWrap = false,
                                modifier = Modifier.background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    if (spec != null) {
                        Text(spec.description, color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                        Text("Without it: ${spec.engineDefault}", color = Color.White.copy(alpha = 0.4f), fontSize = 10.sp)
                    }
                }
                if (onRemove != null) {
                    Button(
                        onClick = onRemove,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A2A2A), contentColor = Color.White),
                        shape = RoundedCornerShape(6.dp),
                    ) { Text("Remove", fontSize = 11.sp) }
                }
            }

            // The value only matters once the option is on
            if (entry.enabled) {
                Spacer(Modifier.height(6.dp))
                when (spec?.kind) {
                    OptionKind.FLAG -> Unit

                    OptionKind.CHOICE -> FlowRow(
                        Modifier.padding(start = 52.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        spec.options.forEach { option -> Chip(option, option == entry.value) { onValue(option) } }
                    }

                    OptionKind.SAVE -> if (saves.isEmpty()) {
                        Text(
                            "No saved games found yet.", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp,
                            modifier = Modifier.padding(start = 52.dp),
                        )
                    } else {
                        FlowRow(
                            Modifier.padding(start = 52.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            saves.forEach { (label, path) -> Chip(label, path == entry.value) { onValue(path) } }
                        }
                    }

                    else -> OutlinedTextField(
                        value = entry.value,
                        onValueChange = { v -> onValue(if (spec?.kind == OptionKind.NUMBER) v.filter { it.isDigit() || it == '.' } else v) },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                        colors = fieldColors(),
                        modifier = Modifier.fillMaxWidth().padding(start = 52.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
        color = if (selected) Color.Black else Color.White,
        modifier = Modifier
            .background(if (selected) Gold else Color.White.copy(alpha = 0.08f), RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
