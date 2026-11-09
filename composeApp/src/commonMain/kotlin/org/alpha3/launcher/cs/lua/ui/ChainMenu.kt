package org.alpha3.launcher.cs.lua.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.alpha3.launcher.cs.lua.ApiCallable
import org.alpha3.launcher.cs.lua.ApiModule
import org.alpha3.launcher.cs.lua.Block
import org.alpha3.launcher.cs.lua.Expr
import org.alpha3.launcher.cs.lua.LuaApi
import org.alpha3.launcher.cs.lua.LuaGen
import org.alpha3.launcher.cs.lua.LuaMod
import org.alpha3.launcher.cs.lua.ScriptKind
import org.alpha3.launcher.cs.lua.Trigger
import org.alpha3.launcher.cs.lua.plainDoc
import org.alpha3.launcher.cs.lua.prettyType
import org.alpha3.launcher.ui.view.HideDialogSystemBars
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** What the chain menu is choosing. */
enum class ChainMode { Block, Trigger, Value }

/** One choice of a step: it either opens the next step or finishes with a result. */
private class ChainOption(
    val label: String,
    val hint: String = "",
    val colour: Color = Color(0xFF3A4F6B),
    val next: (() -> ChainStep)? = null,
    val pick: (() -> Any)? = null,
)

private class ChainStep(val title: String, val options: List<ChainOption>)

private val C_DO = Color(0xFF2F5D8A)
private val C_SHOW = Color(0xFF6B4A8A)
private val C_FLOW = Color(0xFF8A6A2F)
private val C_DATA = Color(0xFF2F7A5D)
private val C_EVENT = Color(0xFF8A3F3F)
private val C_RAW = Color(0xFF555555)

/**
 * The chain menu: a ring of choices around what is being built, each choice narrowing the next ring (what kind of
 * step -> which part of the game -> which function), so a whole call is made by tapping. Long lists (a module's
 * functions) open as a grid with a filter, and a search box finds any function by name or description at once.
 * The result is a [Block], a [Trigger] or an [Expr], with default arguments the block editor then fills in.
 */
@Composable
fun ChainMenu(
    api: LuaApi,
    mod: LuaMod,
    kind: ScriptKind,
    mode: ChainMode,
    functions: List<String>,
    onDismiss: () -> Unit,
    onResult: (Any) -> Unit,
) {
    val root = remember(mode, kind) { rootStep(api, mod, kind, mode, functions) }
    val stack = remember(root) { mutableStateListOf(root) }
    var query by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        HideDialogSystemBars()
        Surface(Modifier.fillMaxSize(), color = Color(0xF0141820)) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (stack.size > 1) stack.removeAt(stack.size - 1) else onDismiss() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                    }
                    Text(stack.joinToString("  ›  ") { it.title }, color = Color.White, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
                }
                if (mode != ChainMode.Trigger) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        placeholder = { Text("Search every function: heal, teleport, message, inventory…") },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    )
                }
                val step = if (query.length >= 2) searchStep(api, kind, mode, query) else stack.last()
                fun choose(o: ChainOption) {
                    o.pick?.let { onResult(it()); return }
                    o.next?.let { query = ""; stack += it() }
                }
                if (step.options.size <= 10 && query.length < 2) Ring(step, ::choose)
                else Grid(step, ::choose)
            }
        }
    }
}

@Composable
private fun Ring(step: ChainStep, choose: (ChainOption) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight)
        val radius = side * 0.36f
        val item = (side * 0.27f).coerceAtMost(150.dp)
        Box(Modifier.size(side), contentAlignment = Alignment.Center) {
            Surface(shape = CircleShape, color = Color(0xFF222A36), modifier = Modifier.size(side * 0.3f).border(2.dp, Color(0xFF55657A), CircleShape)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(10.dp)) {
                    Text(step.title, color = Color.White, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
            val n = step.options.size
            step.options.forEachIndexed { i, o ->
                val a = -PI / 2 + 2 * PI * i / n
                Surface(
                    shape = RoundedCornerShape(50), color = o.colour,
                    modifier = Modifier
                        .offset(x = radius * cos(a).toFloat(), y = radius * sin(a).toFloat())
                        .width(item)
                        .clickable { choose(o) },
                ) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(o.label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, textAlign = TextAlign.Center, maxLines = 2)
                        if (o.hint.isNotEmpty()) Text(o.hint, color = Color(0xCCFFFFFF), fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun Grid(step: ChainStep, choose: (ChainOption) -> Unit) {
    var filter by remember(step) { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        if (step.options.size > 16) OutlinedTextField(filter, { filter = it }, singleLine = true, placeholder = { Text("Filter ${step.options.size} choices") },
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp))
        val shown = step.options.filter { filter.isBlank() || it.label.contains(filter, true) || it.hint.contains(filter, true) }
        if (shown.isEmpty()) Text("Nothing matches", color = Color.LightGray, modifier = Modifier.padding(16.dp))
        LazyVerticalGrid(columns = GridCells.Adaptive(220.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shown) { o ->
                Surface(shape = RoundedCornerShape(10.dp), color = o.colour, modifier = Modifier.clickable { choose(o) }) {
                    Column(Modifier.padding(10.dp)) {
                        Text(o.label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (o.hint.isNotEmpty()) Text(o.hint, color = Color(0xCCFFFFFF), fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

// ---- the steps ----

private fun rootStep(api: LuaApi, mod: LuaMod, kind: ScriptKind, mode: ChainMode, functions: List<String>): ChainStep = when (mode) {
    ChainMode.Trigger -> triggerRoot(api, mod, kind)
    ChainMode.Value -> ChainStep("A value", listOf(
        ChainOption("From the game", "A function's result: a stat, an inventory, the time…", C_DO, next = { modulesStep(api, kind, ChainMode.Value) }),
        ChainOption("From an object", "obj:method() results", C_DO, next = { methodTypesStep(api, kind, ChainMode.Value) }),
        ChainOption("A constant", "Equipment slots, stances, UI layers…", C_DATA, next = { constantsStep(api, kind) }),
    ))
    ChainMode.Block -> ChainStep("Add a step", buildList {
        add(ChainOption("Do something", "Any game function: items, stats, world, sound…", C_DO, next = { modulesStep(api, kind, ChainMode.Block) }))
        add(ChainOption("Object action", "obj:teleport, :moveInto, :remove, :sendEvent…", C_DO, next = { methodTypesStep(api, kind, ChainMode.Block) }))
        add(ChainOption("Show a message", "A message box on screen", C_SHOW, pick = { Block.Call(ref = "openmw.ui:showMessage", args = listOf(Expr.Text("Hello"))) }))
        add(ChainOption("Check", "If … then … else", C_FLOW, next = { flowStep() }))
        add(ChainOption("Remember", "Set a variable", C_DATA, pick = { Block.Set(name = "value", value = Expr.Num(0.0)) }))
        add(ChainOption("Send an event", "Tell another script to do something", C_EVENT, pick = { Block.Send(event = "${mod.id}_event") }))
        add(ChainOption("Wait, then…", "Run steps after some seconds", C_FLOW, pick = { Block.After(seconds = Expr.Num(1.0)) }))
        if (functions.isNotEmpty()) add(ChainOption("My functions", "Call a function of this script", C_DO, next = {
            ChainStep("My functions", functions.map { f -> ChainOption(f, "", C_DO, pick = { Block.Call(ref = "self:$f") }) })
        }))
        add(ChainOption("More", "Lua code, notes, stop", C_RAW, next = {
            ChainStep("More", listOf(
                ChainOption("Lua code", "Write any Lua line", C_RAW, pick = { Block.Lua(code = "print('hello')") }),
                ChainOption("Note", "A comment for yourself", C_RAW, pick = { Block.Note(text = "What this part does") }),
                ChainOption("Stop", "Leave this handler here", C_FLOW, pick = { Block.Stop() }),
                ChainOption("Return a value", "For functions", C_FLOW, pick = { Block.Return(value = Expr.Nil) }),
                ChainOption("Print to console", "A line in the F1 console", C_SHOW, pick = {
                    Block.Call(ref = "openmw.ui:printToConsole", args = listOf(Expr.Text("Hello"), Expr.Const("ui.CONSOLE_COLOR.Info")))
                }),
            ))
        }))
    })
}

private fun flowStep() = ChainStep("Check", listOf(
    ChainOption("If", "Do steps when something is true", C_FLOW, pick = { Block.If(condition = Expr.Op("==", Expr.Var("value"), Expr.Num(1.0))) }),
    ChainOption("If / else", "One list or the other", C_FLOW, pick = { Block.If(condition = Expr.Op("==", Expr.Var("value"), Expr.Num(1.0)), otherwise = listOf(Block.Note(text = "otherwise"))) }),
    ChainOption("For each", "Every item of a list", C_FLOW, pick = { Block.ForEach(over = Expr.Const("nearby.actors"), item = "actor") }),
    ChainOption("Repeat", "A number of times", C_FLOW, pick = { Block.Repeat(times = Expr.Num(3.0)) }),
))

private fun triggerRoot(api: LuaApi, mod: LuaMod, kind: ScriptKind): ChainStep {
    val allowed = api.catalog.handlers.filter { api.allowed(it, kind) && it.name != "onInterfaceOverride" && it.name != "onInputAction" }
    fun group(title: String, hint: String, names: Set<String>) = allowed.filter { it.name in names }.takeIf { it.isNotEmpty() }?.let { list ->
        ChainOption(title, hint, C_FLOW, next = {
            ChainStep(title, list.map { h ->
                ChainOption(LuaGen.HANDLER_TITLES[h.name] ?: h.name, plainDoc(h.doc).take(90), C_FLOW, pick = { Trigger.Engine(h.name) })
            })
        })
    }
    val start = setOf("onInit", "onActive", "onInactive", "onSave", "onLoad", "onNewGame", "onPlayerAdded", "onStateChanged", "onContentFilesLoaded")
    val time = setOf("onUpdate", "onFrame")
    val input = setOf("onKeyPress", "onKeyRelease", "onControllerButtonPress", "onControllerButtonRelease", "onTouchPress", "onTouchRelease", "onTouchMove",
        "onMouseButtonPress", "onMouseButtonRelease", "onMouseWheel", "onConsoleCommand", "onViewportResized", "onMainMenuButton")
    val known = start + time + input
    return ChainStep("When…", buildList {
        group("Start & save", "Script starts, game loads, saves", start)?.let(::add)
        if (kind == ScriptKind.PLAYER || kind == ScriptKind.MENU) add(ChainOption("A key", "A key is pressed", C_FLOW, pick = { Trigger.Key("k") }))
        add(ChainOption("Every few seconds", "A repeating timer", C_FLOW, pick = { Trigger.Every(1.0) }))
        group("Every frame", "onUpdate / onFrame", time)?.let(::add)
        add(ChainOption("An event", "Sent by a script or the engine", C_EVENT, next = { eventsStep(api, mod) }))
        if (kind == ScriptKind.PLAYER || kind == ScriptKind.MENU) add(ChainOption("Console command", "Typed as lua <word>", C_FLOW, pick = { Trigger.Console("hello") }))
        group("Input", "Mouse, touch, controller", input)?.let(::add)
        allowed.filter { it.name !in known }.takeIf { it.isNotEmpty() }?.let { list ->
            add(ChainOption("In the world", "Activations, actors arriving, items…", C_FLOW, next = {
                ChainStep("In the world", list.map { h -> ChainOption(LuaGen.HANDLER_TITLES[h.name] ?: h.name, plainDoc(h.doc).take(90), C_FLOW, pick = { Trigger.Engine(h.name) }) })
            }))
        }
        if (mod.settings.isNotEmpty()) add(ChainOption("A setting changes", "One of this mod's settings", C_DATA, pick = { Trigger.SettingChanged(null) }))
    })
}

private fun eventsStep(api: LuaApi, mod: LuaMod): ChainStep {
    val own = mod.scripts.flatMap { s -> s.handlers.flatMap { h -> h.body } }.let { _ ->
        mod.scripts.flatMap { s -> org.alpha3.launcher.cs.lua.allBlocks(s).filterIsInstance<Block.Send>().map { it.event }.toList() }
    }.distinct()
    return ChainStep("An event", buildList {
        add(ChainOption("A new event", "Name it next", C_EVENT, pick = { Trigger.Event("${mod.id}_event") }))
        own.forEach { add(ChainOption(it, "Sent by this mod", C_EVENT, pick = { Trigger.Event(it) })) }
        api.catalog.events.forEach { e -> add(ChainOption(e.name, "${e.section}: ${e.doc}".take(100), C_RAW, pick = { Trigger.Event(e.name) })) }
    })
}

/** The modules this kind of script may use, as the first ring of "Do something". */
private fun modulesStep(api: LuaApi, kind: ScriptKind, mode: ChainMode): ChainStep {
    val mods = api.modules.filter { m -> m.kind != "interface" && m.id != "openmw.interfaces" && api.allowed(m, kind) && callablesFor(api, m, kind, mode).isNotEmpty() }
    val interfaces = api.modules.filter { it.kind == "interface" && api.allowed(it, kind) && callablesFor(api, it, kind, mode).isNotEmpty() }
    val important = listOf("openmw.types", "openmw.core", "openmw.world", "openmw.nearby", "openmw.ui", "openmw.self", "openmw.ambient", "openmw.input", "openmw.util")
    val top = mods.filter { it.id in important }.sortedBy { important.indexOf(it.id) }
    val rest = mods.filter { it.id !in important }
    return ChainStep(if (mode == ChainMode.Value) "From the game" else "Do something", buildList {
        top.forEach { m -> add(ChainOption(moduleLabel(m), moduleHint(m), C_DO, next = { moduleStep(api, m, kind, mode) })) }
        if (rest.isNotEmpty()) add(ChainOption("More parts", rest.joinToString(", ") { moduleLabel(it) }, C_RAW, next = {
            ChainStep("More parts", rest.map { m -> ChainOption(moduleLabel(m), moduleHint(m), C_DO, next = { moduleStep(api, m, kind, mode) }) })
        }))
        if (interfaces.isNotEmpty()) add(ChainOption("Built-in systems", interfaces.joinToString(", ") { it.name }, C_SHOW, next = {
            ChainStep("Built-in systems", interfaces.map { m -> ChainOption(m.name, moduleHint(m), C_SHOW, next = { moduleStep(api, m, kind, mode) }) })
        }))
    })
}

private fun callablesFor(api: LuaApi, m: ApiModule, kind: ScriptKind, mode: ChainMode): List<ApiCallable> =
    api.callables(m).filter { c ->
        val okKind = api.allowed(c, kind) || (kind != ScriptKind.MENU && (api.allowed(c, ScriptKind.GLOBAL) || api.allowed(c, ScriptKind.PLAYER)) && mode == ChainMode.Block)
        okKind && (mode != ChainMode.Value || c.fn.returns.isNotEmpty())
    }

/** A module's functions, in sub groups when it has documented sub tables (types.NPC, types.Actor.stats…). */
private fun moduleStep(api: LuaApi, m: ApiModule, kind: ScriptKind, mode: ChainMode): ChainStep {
    val all = callablesFor(api, m, kind, mode)
    val groups = all.groupBy { it.path.dropLast(1).joinToString(".") }
    if (groups.size <= 1 || all.size <= 12) return functionsStep(moduleLabel(m), api, all, kind, mode)
    return ChainStep(moduleLabel(m), groups.entries.sortedBy { (k, v) -> if (k.isEmpty()) "" else k }.map { (k, list) ->
        ChainOption(if (k.isEmpty()) "${m.access} itself" else k, "${list.size} functions: " + list.take(4).joinToString(", ") { it.fn.name }, C_DO,
            next = { functionsStep(if (k.isEmpty()) m.access else k, api, list, kind, mode) })
    })
}

private fun functionsStep(title: String, api: LuaApi, list: List<ApiCallable>, kind: ScriptKind, mode: ChainMode) =
    ChainStep(title, list.map { c ->
        val routed = !api.allowed(c, kind)
        ChainOption(
            c.title,
            (if (routed) "Done for you by a ${if (api.allowed(c, ScriptKind.GLOBAL)) "world" else "player"} script. " else "") +
                plainDoc(c.fn.doc).lineSequence().firstOrNull().orEmpty(),
            if (routed) C_EVENT else C_DO,
            pick = { result(c, kind, mode) },
        )
    })

private fun methodTypesStep(api: LuaApi, kind: ScriptKind, mode: ChainMode): ChainStep {
    val preferred = listOf("openmw.core#GameObject", "openmw.core#Inventory", "openmw.core#Cell", "openmw.storage#StorageSection",
        "openmw.ui#Element", "openmw.util#Vector3", "openmw.core#ActiveSpells", "openmw.core#ActiveEffects")
    val types = api.catalog.types.keys.filter { t -> api.methods(t).any { mode != ChainMode.Value || it.fn.returns.isNotEmpty() } }
    val sorted = types.sortedWith(compareBy({ preferred.indexOf(it).let { i -> if (i < 0) 100 else i } }, { it.substringAfter('#') }))
    return ChainStep("Object action", sorted.map { t ->
        val ms = api.methods(t).filter { mode != ChainMode.Value || it.fn.returns.isNotEmpty() }
        ChainOption(prettyType(t), ms.take(5).joinToString(", ") { ":" + it.fn.name }, if (t in preferred) C_DO else C_RAW, next = {
            functionsStep(prettyType(t), api, ms, kind, mode)
        })
    })
}

private fun constantsStep(api: LuaApi, kind: ScriptKind): ChainStep {
    val groups = api.modules.filter { (it.kind == "package") && api.allowed(it, kind) }.flatMap { api.constantGroups(it) }
    return ChainStep("A constant", groups.map { (path, fields) ->
        ChainOption(path, fields.take(6).joinToString(", ") { it.name }, C_DATA, next = {
            ChainStep(path.substringAfterLast('.'), fields.map { f -> ChainOption(f.name, plainDoc(f.doc).take(80), C_DATA, pick = { Expr.Const("$path.${f.name}") }) })
        })
    })
}

private fun searchStep(api: LuaApi, kind: ScriptKind, mode: ChainMode, query: String): ChainStep {
    val q = query.trim().lowercase()
    val callables = api.modules.filter { api.allowed(it, kind) || it.kind == "package" }.flatMap { callablesFor(api, it, kind, mode) } +
        api.catalog.types.keys.flatMap { api.methods(it) }.filter { mode != ChainMode.Value || it.fn.returns.isNotEmpty() }
    val scored = callables.distinctBy { it.ref }.mapNotNull { c ->
        val name = c.title.lowercase()
        val doc = c.fn.doc.lowercase()
        val score = when {
            c.fn.name.lowercase() == q -> 0
            c.fn.name.lowercase().startsWith(q) -> 1
            name.contains(q) -> 2
            doc.contains(q) -> 3
            else -> return@mapNotNull null
        }
        score to c
    }.sortedWith(compareBy({ it.first }, { it.second.title.length })).take(60).map { it.second }
    return functionsStep("Search “$query”", api, scored, kind, mode)
}

/** The block or value a picked function makes, with an argument for each required parameter. */
private fun result(c: ApiCallable, kind: ScriptKind, mode: ChainMode): Any {
    val args = c.fn.params.filter { !it.optional }.map { p -> defaultFor(p.type, p.name, kind) }
    val target = if (c.fn.method) defaultFor(c.owner, "object", kind) else null
    return if (mode == ChainMode.Value) Expr.Call(c.ref, args, target)
    else Block.Call(ref = c.ref, args = args, target = target)
}

internal fun defaultFor(type: String, name: String, kind: ScriptKind): Expr = when {
    type == "string" -> Expr.Text("")
    type == "number" -> Expr.Num(0.0)
    type == "boolean" -> Expr.Bool(true)
    type.endsWith("#GameObject") || name in setOf("actor", "object", "player", "obj", "target") ->
        if (kind.isLocal && kind != ScriptKind.PLAYER) Expr.Self else Expr.Player
    else -> Expr.Nil
}
