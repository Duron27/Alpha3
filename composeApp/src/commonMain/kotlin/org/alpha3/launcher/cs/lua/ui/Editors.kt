package org.alpha3.launcher.cs.lua.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.alpha3.launcher.cs.lua.ApiParam
import org.alpha3.launcher.cs.lua.Block
import org.alpha3.launcher.cs.lua.Entry
import org.alpha3.launcher.cs.lua.Expr
import org.alpha3.launcher.cs.lua.LuaApi
import org.alpha3.launcher.cs.lua.LuaMod
import org.alpha3.launcher.cs.lua.ModSetting
import org.alpha3.launcher.cs.lua.ScriptKind
import org.alpha3.launcher.cs.lua.Trigger
import org.alpha3.launcher.cs.lua.num
import org.alpha3.launcher.cs.lua.plainDoc
import org.alpha3.launcher.cs.lua.prettyType

/** What an expression editor needs to offer: the names in scope, the mod's settings, the API. */
class ExprContext(val api: LuaApi, val mod: LuaMod, val kind: ScriptKind, val names: List<String>)

/** A value shown as a chip; tapping it opens [ExprDialog]. */
@Composable
fun ExprField(label: String, value: Expr, ctx: ExprContext, hint: String = "", onChange: (Expr) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().clickable { open = true }) {
            Text(exprText(value), modifier = Modifier.padding(10.dp), fontFamily = if (value is Expr.Lua) FontFamily.Monospace else null)
        }
        if (hint.isNotBlank()) Text(hint, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
    }
    if (open) ExprDialog(label, value, ctx, onDismiss = { open = false }) { onChange(it); open = false }
}

private enum class ExprKind(val label: String) {
    Text("Text"), Number("Number"), YesNo("Yes / no"), Name("A variable"), Player("The player"), Self("This object"),
    Setting("A setting"), Record("A record id"), Call("A function's result"), Field("Part of a value"), Const("A constant"),
    Compare("Compare / maths"), Not("Not"), Nothing("Nothing"), Lua("Lua"),
}

private fun kindOf(e: Expr): ExprKind = when (e) {
    is Expr.Text -> ExprKind.Text
    is Expr.Num -> ExprKind.Number
    is Expr.Bool -> ExprKind.YesNo
    is Expr.Var -> ExprKind.Name
    Expr.Player -> ExprKind.Player
    Expr.Self -> ExprKind.Self
    is Expr.Setting -> ExprKind.Setting
    is Expr.Record -> ExprKind.Record
    is Expr.Call -> ExprKind.Call
    is Expr.Field -> ExprKind.Field
    is Expr.Const -> ExprKind.Const
    is Expr.Op -> ExprKind.Compare
    is Expr.Not -> ExprKind.Not
    Expr.Nil -> ExprKind.Nothing
    is Expr.Lua, is Expr.Table -> ExprKind.Lua
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExprDialog(title: String, initial: Expr, ctx: ExprContext, onDismiss: () -> Unit, onDone: (Expr) -> Unit) {
    var e by remember { mutableStateOf(initial) }
    var chain by remember { mutableStateOf(false) }
    val kinds = ExprKind.entries.filter { k ->
        when (k) {
            ExprKind.Setting -> ctx.mod.settings.isNotEmpty()
            ExprKind.Self -> ctx.kind.isLocal
            ExprKind.Name -> true
            else -> true
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    kinds.forEach { k ->
                        FilterChip(selected = kindOf(e) == k, onClick = {
                            e = when (k) {
                                ExprKind.Text -> Expr.Text((e as? Expr.Text)?.value ?: "")
                                ExprKind.Number -> Expr.Num((e as? Expr.Num)?.value ?: 0.0)
                                ExprKind.YesNo -> Expr.Bool(true)
                                ExprKind.Name -> Expr.Var(ctx.names.firstOrNull() ?: "value")
                                ExprKind.Player -> Expr.Player
                                ExprKind.Self -> Expr.Self
                                ExprKind.Setting -> Expr.Setting(ctx.mod.settings.first().key)
                                ExprKind.Record -> Expr.Record("", "")
                                ExprKind.Call -> { chain = true; e }
                                ExprKind.Field -> Expr.Field(if (e is Expr.Field) (e as Expr.Field).of else e.takeIf { it !is Expr.Nil } ?: Expr.Player, (e as? Expr.Field)?.name ?: "position")
                                ExprKind.Const -> Expr.Const((e as? Expr.Const)?.path ?: "")
                                ExprKind.Compare -> Expr.Op("==", e.takeIf { it !is Expr.Op } ?: Expr.Var("value"), Expr.Num(0.0))
                                ExprKind.Not -> Expr.Not(e)
                                ExprKind.Nothing -> Expr.Nil
                                ExprKind.Lua -> Expr.Lua(if (e is Expr.Lua) (e as Expr.Lua).code else "")
                            }
                        }, label = { Text(k.label, fontSize = 12.sp) })
                    }
                }
                HorizontalDivider()
                ExprBody(e, ctx, onChange = { e = it }, onChain = { chain = true })
            }
        },
        confirmButton = { TextButton(onClick = { onDone(e) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (chain) ChainMenu(ctx.api, ctx.mod, ctx.kind, ChainMode.Value, emptyList(), onDismiss = { chain = false }) { r ->
        if (r is Expr) e = r
        chain = false
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExprBody(e: Expr, ctx: ExprContext, onChange: (Expr) -> Unit, onChain: () -> Unit) {
    when (e) {
        is Expr.Text -> OutlinedTextField(e.value, { onChange(Expr.Text(it)) }, label = { Text("Text") }, modifier = Modifier.fillMaxWidth())
        is Expr.Num -> {
            var text by remember(e) { mutableStateOf(num(e.value)) }
            OutlinedTextField(text, { text = it; it.toDoubleOrNull()?.let { d -> onChange(Expr.Num(d)) } }, label = { Text("Number") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, isError = text.toDoubleOrNull() == null)
        }
        is Expr.Bool -> Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(e.value, { onChange(Expr.Bool(true)) }, label = { Text("Yes (true)") })
            Text("  ")
            FilterChip(!e.value, { onChange(Expr.Bool(false)) }, label = { Text("No (false)") })
        }
        is Expr.Var -> {
            OutlinedTextField(e.name, { onChange(Expr.Var(it)) }, label = { Text("Name") }, singleLine = true)
            if (ctx.names.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ctx.names.forEach { n -> FilterChip(e.name == n, { onChange(Expr.Var(n)) }, label = { Text(n) }) }
            }
        }
        is Expr.Setting -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ctx.mod.settings.forEach { s -> FilterChip(e.key == s.key, { onChange(Expr.Setting(s.key)) }, label = { Text(s.name) }) }
        }
        is Expr.Record -> {
            OutlinedTextField(e.id, { onChange(e.copy(id = it)) }, label = { Text("Record id (as in the Construction Set)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Ids of the game's content are lower case: gold_001, iron dagger, fargoth.", fontSize = 11.sp)
        }
        is Expr.Call -> {
            val c = ctx.api.resolve(e.ref)
            Text(c?.title ?: e.ref, fontWeight = FontWeight.SemiBold)
            c?.let { Text(plainDoc(it.fn.doc), fontSize = 12.sp, maxLines = 6) }
            if (c?.fn?.method == true) ExprField("On", e.target ?: Expr.Player, ctx) { onChange(e.copy(target = it)) }
            ArgsEditor(c?.fn?.params.orEmpty(), e.args, ctx) { onChange(e.copy(args = it)) }
            TextButton(onClick = onChain) { Text("Pick another function") }
        }
        is Expr.Field -> {
            ExprField("Of", e.of, ctx) { onChange(e.copy(of = it)) }
            OutlinedTextField(e.name, { onChange(e.copy(name = it)) }, label = { Text("Part (field name)") }, singleLine = true)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                fieldSuggestions(ctx.api, e.of).forEach { n -> FilterChip(e.name == n, { onChange(e.copy(name = n)) }, label = { Text(n, fontSize = 12.sp) }) }
            }
        }
        is Expr.Const -> {
            OutlinedTextField(e.path, { onChange(Expr.Const(it)) }, label = { Text("Constant") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = onChain) { Text("Choose from the game's constants…") }
        }
        is Expr.Op -> {
            ExprField("Left", e.left, ctx) { onChange(e.copy(left = it)) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OPERATORS.forEach { op -> FilterChip(e.op == op, { onChange(e.copy(op = op)) }, label = { Text(opText(op)) }) }
            }
            ExprField("Right", e.right, ctx) { onChange(e.copy(right = it)) }
        }
        is Expr.Not -> ExprField("Not", e.value, ctx) { onChange(Expr.Not(it)) }
        is Expr.Lua -> OutlinedTextField(e.code, { onChange(Expr.Lua(it)) }, label = { Text("Lua expression") },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth())
        is Expr.Table -> Text("A table: edit it as Lua")
        Expr.Player -> Text("The player: in player scripts the player itself, elsewhere the first player.")
        Expr.Self -> Text("The object this script runs on.")
        Expr.Nil -> Text("No value (nil).")
    }
}

/** Field names a value probably has: those of GameObject for objects, else of the documented type of a call's result. */
private fun fieldSuggestions(api: LuaApi, of: Expr): List<String> {
    val type = when (of) {
        Expr.Player, Expr.Self, is Expr.Var -> "openmw.core#GameObject"
        is Expr.Call -> api.resolve(of.ref)?.fn?.returns?.firstOrNull()?.type
        else -> null
    } ?: return emptyList()
    return (api.fields(type).map { it.name } + if (type.endsWith("#GameObject")) listOf("type") else emptyList()).distinct().take(30)
}

@Composable
fun ArgsEditor(params: List<ApiParam>, args: List<Expr>, ctx: ExprContext, onChange: (List<Expr>) -> Unit) {
    val count = maxOf(args.size, params.count { !it.optional })
    for (i in 0 until maxOf(count, params.size)) {
        val p = params.getOrNull(i)
        val present = i < args.size
        if (!present && p?.optional == true) {
            TextButton(onClick = { onChange(args + List(i - args.size + 1) { j -> if (j == i - args.size) defaultFor(p.type, p.name, ctx.kind) else Expr.Nil }) }) {
                Icon(Icons.Default.Add, null); Text(" ${p.name} (optional, ${prettyType(p.type)})")
            }
            continue
        }
        if (!present) continue
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                ExprField("${p?.name ?: "argument ${i + 1}"}${p?.let { " — ${prettyType(it.type)}" } ?: ""}", args[i], ctx, hint = p?.doc?.let(::plainDoc).orEmpty()) { v ->
                    onChange(args.toMutableList().also { it[i] = v })
                }
            }
            if (i == args.size - 1 && (p == null || p.optional)) IconButton(onClick = { onChange(args.dropLast(1)) }) { Icon(Icons.Default.Delete, "Remove") }
        }
    }
}

/** The dialog that edits one block. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BlockDialog(block: Block, ctx: ExprContext, onDismiss: () -> Unit, onDone: (Block) -> Unit) {
    var b by remember { mutableStateOf(block) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(blockTitle(b)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (val x = b) {
                    is Block.Call -> {
                        val c = ctx.api.resolve(x.ref)
                        if (c != null) {
                            Text(c.title, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                            Text(plainDoc(c.fn.doc), fontSize = 12.sp)
                            if (!ctx.api.allowed(c, ctx.kind)) Text(
                                "This script can't make this call itself; it is sent to a ${if (ctx.api.allowed(c, ScriptKind.GLOBAL)) "world" else "player"} script of the mod, which makes it.",
                                color = Color(0xFFE0A050), fontSize = 12.sp,
                            )
                            c.fn.usage.firstOrNull()?.let { Text("Example: $it", fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                        } else if (x.ref.startsWith("self:")) Text("Calls ${x.ref.removePrefix("self:")} of this script")
                        else Text("This function is not in the game's API: ${x.ref}", color = Color(0xFFE57373))
                        if (c?.fn?.method == true) ExprField("On (the object)", x.target ?: Expr.Player, ctx) { b = x.copy(target = it) }
                        ArgsEditor(c?.fn?.params.orEmpty(), x.args, ctx) { b = x.copy(args = it) }
                        if (c?.fn?.returns?.isNotEmpty() == true || x.saveAs != null) {
                            OutlinedTextField(x.saveAs ?: "", { b = x.copy(saveAs = it.ifBlank { null }) }, singleLine = true,
                                label = { Text("Keep the result as (optional): ${c?.fn?.returns?.joinToString { prettyType(it.type) } ?: ""}") })
                        }
                    }
                    is Block.If -> ExprField("If", x.condition, ctx, "True when this holds; then the steps inside run") { b = x.copy(condition = it) }
                    is Block.ForEach -> {
                        ExprField("Each of", x.over, ctx, "A list: nearby.actors, an inventory's getAll(), world.players…") { b = x.copy(over = it) }
                        OutlinedTextField(x.item, { b = x.copy(item = it) }, label = { Text("Call each one") }, singleLine = true)
                    }
                    is Block.Repeat -> ExprField("Times", x.times, ctx) { b = x.copy(times = it) }
                    is Block.Set -> {
                        OutlinedTextField(x.name, { b = x.copy(name = it) }, label = { Text("Variable") }, singleLine = true)
                        if (ctx.names.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ctx.names.forEach { n -> FilterChip(x.name == n, { b = x.copy(name = n) }, label = { Text(n, fontSize = 12.sp) }) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(x.local, { b = x.copy(local = it) }); Text("Only here (a new local variable of this handler)")
                        }
                        ExprField("Value", x.value, ctx) { b = x.copy(value = it) }
                    }
                    is Block.Send -> {
                        OutlinedTextField(x.event, { b = x.copy(event = it) }, label = { Text("Event name") }, singleLine = true)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(x.to == null, { b = x.copy(to = null) }, label = { Text("World scripts") })
                            FilterChip(x.to == Expr.Player, { b = x.copy(to = Expr.Player) }, label = { Text("The player") })
                            FilterChip(x.to != null && x.to != Expr.Player, { b = x.copy(to = Expr.Var(ctx.names.firstOrNull() ?: "actor")) }, label = { Text("An object…") })
                        }
                        if (x.to != null && x.to != Expr.Player) ExprField("To", x.to, ctx) { b = x.copy(to = it) }
                        Text("Built-in events the engine understands: ShowMessage {message}, ModifyStat {stat, amount}, PlaySound3d {sound}, StartAIPackage…", fontSize = 11.sp)
                        x.data.forEachIndexed { i, en ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(en.key, { k -> b = x.copy(data = x.data.toMutableList().also { it[i] = en.copy(key = k) }) }, singleLine = true,
                                    label = { Text("Name") }, modifier = Modifier.weight(0.4f))
                                Column(Modifier.weight(0.6f).padding(start = 6.dp)) {
                                    ExprField("Value", en.value, ctx) { v -> b = x.copy(data = x.data.toMutableList().also { it[i] = en.copy(value = v) }) }
                                }
                                IconButton(onClick = { b = x.copy(data = x.data.filterIndexed { j, _ -> j != i }) }) { Icon(Icons.Default.Delete, "Remove") }
                            }
                        }
                        TextButton(onClick = { b = x.copy(data = x.data + Entry("value${x.data.size + 1}", Expr.Nil)) }) { Icon(Icons.Default.Add, null); Text(" Add data") }
                    }
                    is Block.After -> ExprField("Seconds", x.seconds, ctx) { b = x.copy(seconds = it) }
                    is Block.Return -> ExprField("Value", x.value ?: Expr.Nil, ctx) { b = x.copy(value = it) }
                    is Block.Stop -> Text("Leaves the handler: the steps after this one are not run.")
                    is Block.Lua -> OutlinedTextField(x.code, { b = x.copy(code = it) }, label = { Text("Lua") }, minLines = 4,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth())
                    is Block.Note -> OutlinedTextField(x.text, { b = x.copy(text = it) }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(b) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

internal fun blockTitle(b: Block) = when (b) {
    is Block.Call -> "Call"
    is Block.If -> "If"
    is Block.ForEach -> "For each"
    is Block.Repeat -> "Repeat"
    is Block.Set -> "Set a variable"
    is Block.Send -> "Send an event"
    is Block.After -> "Wait, then…"
    is Block.Return -> "Return"
    is Block.Stop -> "Stop"
    is Block.Lua -> "Lua code"
    is Block.Note -> "Note"
}

/** The dialog that edits what starts a handler (the key, the seconds, the event's name…). */
@Composable
fun TriggerDialog(trigger: Trigger, api: LuaApi, onDismiss: () -> Unit, onDone: (Trigger) -> Unit) {
    var t by remember { mutableStateOf(trigger) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(org.alpha3.launcher.cs.lua.LuaGen.triggerTitle(t)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (val x = t) {
                    is Trigger.Engine -> {
                        val h = api.handler(x.name)
                        Text(x.name, fontFamily = FontFamily.Monospace)
                        h?.let {
                            Text(plainDoc(it.doc), fontSize = 12.sp)
                            if (it.params.isNotEmpty()) Text("Gives you: ${it.params.joinToString(", ")}", fontSize = 12.sp)
                        }
                    }
                    is Trigger.Key -> {
                        OutlinedTextField(x.symbol, { t = x.copy(symbol = it.lowercase().take(12)) }, label = { Text("Key (a letter or a name like f1, space, return)") }, singleLine = true)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(x.shift, { t = x.copy(shift = it) }); Text("Shift  ")
                            Checkbox(x.ctrl, { t = x.copy(ctrl = it) }); Text("Ctrl  ")
                            Checkbox(x.alt, { t = x.copy(alt = it) }); Text("Alt")
                        }
                    }
                    is Trigger.Every -> {
                        var text by remember { mutableStateOf(num(x.seconds)) }
                        OutlinedTextField(text, { text = it; it.toDoubleOrNull()?.takeIf { d -> d > 0 }?.let { d -> t = Trigger.Every(d) } }, label = { Text("Seconds of game time") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                    }
                    is Trigger.Event -> {
                        OutlinedTextField(x.name, { t = Trigger.Event(it.trim()) }, label = { Text("Event name") }, singleLine = true)
                        api.catalog.events.firstOrNull { it.name == x.name }?.let { Text(plainDoc(it.doc), fontSize = 12.sp) }
                        Text("Its data arrives as `data` (data.actor, data.amount…).", fontSize = 12.sp)
                    }
                    is Trigger.Console -> {
                        OutlinedTextField(x.word, { t = Trigger.Console(it.trim().replace(" ", "")) }, label = { Text("Command word") }, singleLine = true)
                        Text("Typed in the console as: lua ${x.word} …  The rest of the line arrives as `args`.", fontSize = 12.sp)
                    }
                    is Trigger.SettingChanged -> Text("Runs when one of the mod's settings is changed; `key` is the setting's key.")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(t) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The mod's settings page: each setting's key, name, kind and default. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsDialog(mod: LuaMod, onDismiss: () -> Unit, onDone: (List<ModSetting>) -> Unit) {
    var list by remember { mutableStateOf(mod.settings) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings page") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("These appear in Options > Scripts > ${mod.title}. Read them in blocks as “A setting”.", fontSize = 12.sp)
                list.forEachIndexed { i, s ->
                    fun set(n: ModSetting) { list = list.toMutableList().also { it[i] = n } }
                    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(s.name, { set(s.copy(name = it)) }, label = { Text("Name shown") }, singleLine = true, modifier = Modifier.weight(1f))
                                IconButton(onClick = { list = list.filterIndexed { j, _ -> j != i } }) { Icon(Icons.Default.Delete, "Remove") }
                            }
                            OutlinedTextField(s.key, { set(s.copy(key = org.alpha3.launcher.cs.lua.identifier(it, "setting"))) }, label = { Text("Key (used in code)") }, singleLine = true)
                            OutlinedTextField(s.description, { set(s.copy(description = it)) }, label = { Text("Description") })
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("checkbox" to "Switch", "number" to "Number", "textLine" to "Text", "select" to "Choice").forEach { (r, label) ->
                                    FilterChip(s.renderer == r, {
                                        set(s.copy(renderer = r, default = when (r) {
                                            "checkbox" -> Expr.Bool(true); "number" -> Expr.Num(0.0); "select" -> Expr.Text(s.items.firstOrNull() ?: "One"); else -> Expr.Text("")
                                        }, items = if (r == "select" && s.items.isEmpty()) listOf("One", "Two") else s.items))
                                    }, label = { Text(label) })
                                }
                            }
                            when (s.renderer) {
                                "checkbox" -> Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox((s.default as? Expr.Bool)?.value ?: true, { set(s.copy(default = Expr.Bool(it))) }); Text("On by default")
                                }
                                "number" -> {
                                    var d by remember(i) { mutableStateOf(num((s.default as? Expr.Num)?.value ?: 0.0)) }
                                    var mn by remember(i) { mutableStateOf(s.min?.let(::num) ?: "") }
                                    var mx by remember(i) { mutableStateOf(s.max?.let(::num) ?: "") }
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        OutlinedTextField(d, { d = it; it.toDoubleOrNull()?.let { v -> set(s.copy(default = Expr.Num(v))) } }, label = { Text("Default") }, singleLine = true, modifier = Modifier.weight(1f))
                                        OutlinedTextField(mn, { mn = it; set(s.copy(min = it.toDoubleOrNull())) }, label = { Text("Min") }, singleLine = true, modifier = Modifier.weight(1f))
                                        OutlinedTextField(mx, { mx = it; set(s.copy(max = it.toDoubleOrNull())) }, label = { Text("Max") }, singleLine = true, modifier = Modifier.weight(1f))
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(s.integer, { set(s.copy(integer = it)) }); Text("Whole numbers") }
                                }
                                "select" -> {
                                    OutlinedTextField(s.items.joinToString(", "), { v -> set(s.copy(items = v.split(",").map { it.trim() }.filter { it.isNotEmpty() })) }, label = { Text("Choices, separated by commas") })
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        s.items.forEach { item -> FilterChip((s.default as? Expr.Text)?.value == item, { set(s.copy(default = Expr.Text(item))) }, label = { Text(item) }) }
                                    }
                                }
                                else -> OutlinedTextField((s.default as? Expr.Text)?.value ?: "", { set(s.copy(default = Expr.Text(it))) }, label = { Text("Default text") })
                            }
                        }
                    }
                }
                TextButton(onClick = {
                    val n = list.size + 1
                    list = list + ModSetting("setting$n", "Setting $n", renderer = "checkbox", default = Expr.Bool(true))
                }) { Icon(Icons.Default.Add, null); Text(" Add a setting") }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(list) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
