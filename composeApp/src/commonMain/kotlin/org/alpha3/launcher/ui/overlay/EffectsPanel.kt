package org.alpha3.launcher.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.alpha3.launcher.engine.Engine
import kotlin.math.roundToInt

// The overlay's ReShade panel (support/src/vulkan/reshade.cpp): which techniques run and in what order, their
// settings, and the #defines the shaders test. Every change goes to the game at once (values the next frame, the rest
// after that file compiles again) and into Alpha3/reshade/ReShadePreset.ini. The game stays visible on the left.

object EffectsPanelState {
    var visible by mutableStateOf(false)
}

private data class EffectsUniform(
    val name: String,
    val label: String,
    val type: String, // float, int, uint, bool
    val components: Int,
    val ui: String,
    val tooltip: String,
    val category: String,
    val default: String,
    val min: Float?,
    val max: Float?,
    val step: Float?,
    val items: List<String>,
)

private data class EffectsTechnique(val name: String, val label: String, val usable: Boolean, val hidden: Boolean)

private data class EffectsFile(
    val name: String,
    val starter: Boolean,
    val status: String,
    val techniques: List<String>, // as the text has them (before it is compiled)
    val compiled: List<EffectsTechnique>?, // once compiled
    val uniforms: List<EffectsUniform>,
    val defines: List<Pair<String, String>>, // the ones the file tests, with the value it was compiled with
    val values: Map<String, String>,
)

private data class EffectsReport(
    val on: Boolean,
    val folder: String,
    val enabled: List<String>,
    val files: List<EffectsFile>,
    val defines: List<Pair<String, String>>,
)

private fun parseEffects(text: String): EffectsReport {
    val root = Json.parseToJsonElement(text).jsonObject
    fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.content ?: ""
    fun pairs(array: JsonArray?) = array?.map { it.jsonArray[0].jsonPrimitive.content to it.jsonArray[1].jsonPrimitive.content }
        ?: emptyList()
    val files = root["files"]?.jsonArray?.map { element ->
        val file = element.jsonObject
        val effect = file["effect"]?.jsonObject
        EffectsFile(
            name = file.str("name"),
            starter = file["starter"]?.jsonPrimitive?.booleanOrNull ?: false,
            status = file.str("status"),
            techniques = file["techniques"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
            compiled = effect?.get("techniques")?.jsonArray?.map {
                val t = it.jsonObject
                EffectsTechnique(
                    t.str("name"), t.str("label"),
                    t["usable"]?.jsonPrimitive?.booleanOrNull ?: true,
                    t["hidden"]?.jsonPrimitive?.booleanOrNull ?: false,
                )
            },
            uniforms = effect?.get("uniforms")?.jsonArray?.map {
                val u = it.jsonObject
                EffectsUniform(
                    name = u.str("name"),
                    label = u.str("label"),
                    type = u.str("type"),
                    components = u["components"]?.jsonPrimitive?.intOrNull ?: 1,
                    ui = u.str("ui"),
                    tooltip = u.str("tooltip"),
                    category = u.str("category"),
                    default = u.str("default"),
                    min = u["min"]?.jsonPrimitive?.floatOrNull,
                    max = u["max"]?.jsonPrimitive?.floatOrNull,
                    step = u["step"]?.jsonPrimitive?.floatOrNull,
                    items = u["items"]?.jsonArray?.map { item -> item.jsonPrimitive.content } ?: emptyList(),
                )
            } ?: emptyList(),
            defines = pairs(effect?.get("defines")?.jsonArray),
            values = file["values"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap(),
        )
    } ?: emptyList()
    return EffectsReport(
        on = root["on"]?.jsonPrimitive?.booleanOrNull ?: true,
        folder = root.str("folder"),
        enabled = root["enabled"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
        files = files,
        defines = pairs(root["defines"]?.jsonArray),
    )
}

private fun command(vararg words: String) = Engine.effectsCommand(words.joinToString("\t"))

private val accent = Color(0xFF8FC7FF)
private val dim = Color(0xFFB0B8C0)
private val error = Color(0xFFFF8A80)

@Composable
fun EffectsPanel() {
    var report by remember { mutableStateOf<EffectsReport?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var openFile by remember { mutableStateOf<String?>(null) }
    // values being dragged: shown at once, before the report comes back with them
    val pending = remember { mutableStateMapOf<String, String>() }

    LaunchedEffect(refresh) {
        while (true) {
            val text = withContext(Dispatchers.Default) { Engine.effectsReport() }
            report = text?.let { runCatching { parseEffects(it) }.getOrNull() }
            delay(if (report?.files?.any { it.status == "compiling" } == true) 300 else 1000)
        }
    }
    fun changed() {
        refresh++
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier
                .fillMaxHeight()
                .width(400.dp)
                .background(Color(0xE6101418))
                // the panel keeps its touches from the game's touch areas and the SDL view under it: a pointer input
                // here makes it the one hit (Compose stops at the topmost sibling hit). It must not consume them: the
                // buttons' tap detection checks in the Final pass whether the up was consumed, and gives up if so
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent()
                    }
                }
                .padding(8.dp)
        ) {
            val current = report
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Effects", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                if (current != null) {
                    Switch(checked = current.on, onCheckedChange = {
                        command("on", if (it) "1" else "0")
                        changed()
                    })
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    command("rescan")
                    changed()
                }) { Text("Rescan", color = accent) }
                IconButton(onClick = { EffectsPanelState.visible = false }) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            }
            if (current == null) {
                Text("The effects need the Vulkan renderer and a running game.", color = dim, fontSize = 13.sp)
                return@Column
            }
            val open = openFile?.let { name -> current.files.firstOrNull { it.name == name } }
            if (open != null) {
                FileSettings(open, current, pending, onBack = { openFile = null }, onChanged = ::changed)
            } else {
                TechniqueLists(current, onOpen = { openFile = it }, onChanged = ::changed)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
}

@Composable
private fun TechniqueLists(report: EffectsReport, onOpen: (String) -> Unit, onChanged: () -> Unit) {
    val byFile = report.files.associateBy { it.name }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                "Shaders from ${report.folder}/Shaders. Vulkan renderer only. Tap a name for its settings.",
                color = dim, fontSize = 11.sp
            )
            SectionTitle("Running, in order")
            if (report.enabled.isEmpty()) Text("Nothing yet: tick a technique below.", color = dim, fontSize = 12.sp)
        }
        items(report.enabled.withIndex().toList(), key = { "on:" + it.value }) { (index, id) ->
            val name = id.substringBefore('@')
            val fileName = id.substringAfter('@')
            val file = byFile[fileName]
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f).clickable { onOpen(fileName) }.padding(vertical = 4.dp)) {
                    Text(name, color = Color.White, fontSize = 14.sp)
                    StatusLine(file, fileName)
                }
                IconButton(enabled = index > 0, onClick = {
                    command("move", id, (index - 1).toString())
                    onChanged()
                }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.ArrowUpward, "Earlier", tint = if (index > 0) Color.White else Color.Gray)
                }
                IconButton(enabled = index < report.enabled.size - 1, onClick = {
                    command("move", id, (index + 1).toString())
                    onChanged()
                }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.ArrowDownward, "Later",
                        tint = if (index < report.enabled.size - 1) Color.White else Color.Gray)
                }
                IconButton(onClick = {
                    command("technique", id, "0")
                    onChanged()
                }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Close, "Turn off", tint = Color.White)
                }
            }
        }
        item { SectionTitle("Available") }
        if (report.files.isEmpty()) {
            item { Text("No .fx files found.", color = dim, fontSize = 12.sp) }
        }
        items(report.files, key = { "file:" + it.name }) { file ->
            val techniques = file.compiled?.filter { !it.hidden }?.map { it.name } ?: file.techniques
            Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(
                    file.name + if (file.starter) "  (bundled)" else "",
                    color = dim, fontSize = 11.sp,
                    modifier = Modifier.clickable { onOpen(file.name) }
                )
                for (technique in techniques) {
                    val id = "$technique@${file.name}"
                    val on = id in report.enabled
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().height(36.dp).clickable {
                            command("technique", id, if (on) "0" else "1")
                            onChanged()
                        }
                    ) {
                        Checkbox(
                            checked = on,
                            onCheckedChange = {
                                command("technique", id, if (it) "1" else "0")
                                onChanged()
                            },
                            colors = CheckboxDefaults.colors(uncheckedColor = dim, checkedColor = accent)
                        )
                        Text(technique, color = Color.White, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(file: EffectsFile?, fileName: String) {
    val status = file?.status ?: "file not found"
    when {
        file == null -> Text("$fileName: not found", color = error, fontSize = 11.sp)
        status == "ok" || status.isEmpty() -> Text(fileName, color = dim, fontSize = 11.sp)
        status == "compiling" -> Text("$fileName: compiling…", color = accent, fontSize = 11.sp)
        else -> Text(status.lineSequence().take(3).joinToString("\n"), color = error, fontSize = 11.sp, maxLines = 3)
    }
}

@Composable
private fun FileSettings(
    file: EffectsFile,
    report: EffectsReport,
    pending: MutableMap<String, String>,
    onBack: () -> Unit,
    onChanged: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ Back", color = accent) }
                Text(file.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    command("reset", file.name)
                    pending.keys.filter { it.startsWith(file.name + "/") }.forEach { pending.remove(it) }
                    onChanged()
                }) { Text("Reset all", color = accent) }
            }
            if (file.status != "ok" && file.status.isNotEmpty()) {
                Text(file.status, color = if (file.status == "compiling") accent else error, fontSize = 11.sp)
            }
            if (file.compiled == null && file.status != "compiling") {
                Text("Turn one of its techniques on to see its settings.", color = dim, fontSize = 12.sp)
            }
        }
        var lastCategory = ""
        val groups = file.uniforms.groupBy { it.category }
        for ((category, uniforms) in groups) {
            if (category.isNotEmpty() && category != lastCategory) {
                item(key = "cat:$category") { SectionTitle(category) }
                lastCategory = category
            }
            items(uniforms, key = { "u:" + it.name }) { uniform ->
                val key = file.name + "/" + uniform.name
                val value = pending[key] ?: file.values[uniform.name] ?: uniform.default
                UniformControl(uniform, value, onValue = {
                    pending[key] = it
                    command("value", file.name, uniform.name, it)
                }, onReset = {
                    pending.remove(key)
                    command("reset", file.name, uniform.name)
                    onChanged()
                })
            }
        }
        if (file.defines.isNotEmpty()) {
            item { SectionTitle("Preprocessor definitions (compiles the file again)") }
            items(file.defines, key = { "d:" + it.first }) { (name, compiledWith) ->
                val value = report.defines.firstOrNull { it.first == name }?.second ?: compiledWith
                DefineControl(name, value, onValue = {
                    command("define", name, it)
                    onChanged()
                })
            }
        }
    }
}

private fun formatNumber(value: Float, integer: Boolean): String =
    if (integer) value.roundToInt().toString()
    else ((value * 1000f).roundToInt() / 1000f).toString()

@Composable
private fun UniformControl(uniform: EffectsUniform, value: String, onValue: (String) -> Unit, onReset: () -> Unit) {
    val parts = value.split(',').map { it.trim() }
    val integer = uniform.type != "float"
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(uniform.label, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
            if (value != uniform.default) {
                Text("reset", color = accent, fontSize = 11.sp, modifier = Modifier.clickable(onClick = onReset).padding(4.dp))
            }
        }
        if (uniform.tooltip.isNotEmpty()) Text(uniform.tooltip, color = dim, fontSize = 10.sp, maxLines = 2)
        when {
            uniform.type == "bool" -> {
                val on = parts.firstOrNull().let { it == "1" || it == "true" }
                Switch(checked = on, onCheckedChange = { onValue(if (it) "1" else "0") })
            }
            uniform.items.isNotEmpty() && uniform.components == 1 -> {
                val selected = parts.firstOrNull()?.toFloatOrNull()?.roundToInt() ?: 0
                Column {
                    uniform.items.forEachIndexed { index, item ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                                .background(
                                    if (index == selected) accent.copy(alpha = 0.25f) else Color.Transparent,
                                    RoundedCornerShape(4.dp)
                                )
                                .clickable { onValue(index.toString()) }
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            Text(item, color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }
            else -> {
                val isColor = uniform.ui == "color"
                val low = uniform.min ?: if (isColor) 0f else if (integer) 0f else 0f
                val high = uniform.max ?: if (isColor) 1f else if (integer) 10f else 1f
                for (i in 0 until uniform.components) {
                    val current = parts.getOrNull(i)?.toFloatOrNull() ?: low
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (uniform.components > 1) {
                            Text(
                                if (isColor) "RGBA"[i].toString() else "XYZW"[i].toString(),
                                color = dim, fontSize = 11.sp, modifier = Modifier.width(14.dp)
                            )
                        }
                        Slider(
                            value = current.coerceIn(minOf(low, high), maxOf(low, high)),
                            onValueChange = { moved ->
                                var v = moved
                                val step = uniform.step ?: if (integer) 1f else 0f
                                if (step > 0f) v = low + ((v - low) / step).roundToInt() * step
                                val updated = (0 until uniform.components).map { c ->
                                    if (c == i) formatNumber(v, integer) else parts.getOrNull(c) ?: "0"
                                }
                                onValue(updated.joinToString(","))
                            },
                            valueRange = minOf(low, high)..maxOf(low, high).let { if (it == minOf(low, high)) it + 1f else it },
                            modifier = Modifier.weight(1f).height(32.dp),
                        )
                        Text(formatNumber(current, integer), color = Color.White, fontSize = 11.sp, modifier = Modifier.width(48.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DefineControl(name: String, value: String, onValue: (String) -> Unit) {
    val number = value.trim().toIntOrNull()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(name, color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
        if (number != null) {
            IconButton(onClick = { onValue((number - 1).toString()) }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Remove, "Less", tint = Color.White)
            }
            Text(number.toString(), color = Color.White, fontSize = 13.sp, modifier = Modifier.width(32.dp))
            IconButton(onClick = { onValue((number + 1).toString()) }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Add, "More", tint = Color.White)
            }
        } else {
            Text(value, color = dim, fontSize = 12.sp)
        }
    }
}
