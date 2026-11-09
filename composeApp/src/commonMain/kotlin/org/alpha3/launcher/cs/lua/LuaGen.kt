package org.alpha3.launcher.cs.lua

/**
 * Turns a [LuaMod] into the files of an OpenMW mod: one .lua per script, the .omwscripts list and the settings' l10n.
 *
 * The code is meant to be read: requires at the top, the script's variables, one local function per handler, and the
 * returned table of engine and event handlers. The OpenMW rule that the world is only changed by global scripts and the
 * screen only by player scripts is handled here: a call that the script's kind may not make is sent as an event to a
 * script that may (a world script for teleport/moveInto/remove..., the player's script for messages and UI), so the
 * mod's author does not have to split the work themselves.
 */
class LuaGen(private val api: LuaApi, private val mod: LuaMod) {
    class Output(
        /** Files by path inside the mod's folder. */
        val files: Map<String, String>,
        /** For each script file, the block (or handler) uid each line came from, by line (1-based). */
        val lines: Map<String, Map<Int, String>>,
        val notes: List<String>,
    )

    private val modId = identifier(mod.id)
    private val notes = ArrayList<String>()
    /** Calls sent to another script: the kind they run in, the event's name and the code that makes the call there. */
    private class Route(val kind: ScriptKind, val event: String, val body: String, val modules: Set<String>)
    private val routes = ArrayList<Route>()
    private val byAccess: Map<String, ApiModule> = api.modules.filter { it.kind == "package" || it.kind == "aux" }.associateBy { it.access }

    /** Settings any script other than the player's reads: those go in a group of global storage. */
    private val globalSettings: Boolean by lazy {
        mod.scripts.any { s -> s.kind != ScriptKind.PLAYER && usesSettings(s) }
    }

    private fun usesSettings(s: LuaScript): Boolean =
        allBlocks(s).any { b -> exprsOf(b).any { e -> e.anyExpr { it is Expr.Setting } } } ||
            s.handlers.any { it.trigger is Trigger.SettingChanged }

    val settingsGroup: String get() = if (globalSettings) "SettingsGlobal$modId" else "SettingsPlayer$modId"
    val settingsPage: String get() = modId

    fun scriptPath(s: LuaScript) = "scripts/$modId/${identifier(s.name, "script")}.lua"

    fun generate(): Output {
        val files = LinkedHashMap<String, String>()
        val lines = LinkedHashMap<String, Map<Int, String>>()
        val scripts = mod.scripts.toMutableList()

        // a pass over every script first, to know which calls are routed elsewhere (their events are named after
        // the block, so the second pass makes the same ones)
        scripts.forEach { ScriptGen(it).build() }
        val needGlobal = routes.any { it.kind == ScriptKind.GLOBAL } || (mod.settings.isNotEmpty() && globalSettings)
        val needPlayer = routes.any { it.kind == ScriptKind.PLAYER } || mod.settings.isNotEmpty()
        val extra = ArrayList<LuaScript>()
        if (needGlobal && scripts.none { it.kind == ScriptKind.GLOBAL && it.code == null })
            extra += LuaScript(uid = "_global", name = "world", kind = ScriptKind.GLOBAL)
        if (needPlayer && scripts.none { it.kind == ScriptKind.PLAYER && it.code == null })
            extra += LuaScript(uid = "_player", name = "player", kind = ScriptKind.PLAYER)
        scripts += extra
        for (s in scripts) {
            val gen = ScriptGen(s).build(withRoutes = s.uid == firstOf(s.kind)?.uid)
            val path = scriptPath(s)
            if (s.code != null) {
                files[path] = s.code
                lines[path] = emptyMap()
            } else {
                files[path] = gen.first
                lines[path] = gen.second
            }
        }
        files["$modId.omwscripts"] = buildString {
            appendLine("# ${mod.title}${if (mod.author.isNotBlank()) " by ${mod.author}" else ""}. Made with the Alpha3 Lua mod creator.")
            for (s in scripts) appendLine("${s.kind.tag}: ${scriptPath(s)}")
        }
        if (mod.settings.isNotEmpty()) files["l10n/$modId/en.yaml"] = l10n()
        return Output(files, lines, notes.distinct())
    }

    private fun l10n(): String = buildString {
        fun line(key: String, text: String) = appendLine("$key: \"${text.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        line("pageName", mod.settingsTitle ?: mod.title)
        line("pageDescription", mod.description.ifBlank { mod.title })
        line("groupName", "Settings")
        for (s in mod.settings) {
            line("${s.key}_name", s.name)
            if (s.description.isNotBlank()) line("${s.key}_description", s.description)
            s.items.forEach { line("${s.key}_item_$it", it) }
        }
    }

    // ---- one script ----

    private inner class ScriptGen(val script: LuaScript) {
        val kind = script.kind
        val out = Code()
        val modules = LinkedHashSet<String>()
        var usesInterfaces = false
        var usesSelf = false
        var usesSettings = false
        var usesAsync = false
        val handlerFns = LinkedHashMap<String, MutableList<String>>()  // engine handler -> local functions it calls
        val eventFns = LinkedHashMap<String, MutableList<String>>()
        val timers = ArrayList<Pair<String, Double>>()
        val settingWatchers = ArrayList<Pair<String, String?>>()
        val consoleWords = ArrayList<Pair<String, String>>()
        val keyFilters = ArrayList<Pair<String, Trigger.Key>>()
        var currentUid = script.uid

        fun build(withRoutes: Boolean = false): Pair<String, Map<Int, String>> {
            val body = Code()
            // the script's own functions first, so handlers can call them
            for (f in script.functions) {
                body.line("local function ${identifier(f.name, "fn")}(${f.params.joinToString(", ") { identifier(it, "arg") }})", f.uid)
                body.indent { blocks(f.body, body) }
                body.line("end", f.uid)
                body.blank()
            }
            for ((i, h) in script.handlers.withIndex()) handler(h, i, body)
            if (withRoutes) for (r in routes.filter { it.kind == kind }.distinctBy { it.event }) {
                modules += r.modules
                if (r.modules.contains("I")) usesInterfaces = true
                val fn = "routed_${identifier(r.event.substringAfter(modId + "_"))}"
                body.line("-- sent here by another script of the mod: this kind of script may make the call", null)
                body.line("local function $fn(d)", null)
                body.indent { r.body.lines().forEach { body.line(it, null) } }
                body.line("end", null)
                body.blank()
                eventFns.getOrPut(r.event) { ArrayList() } += fn
            }
            val settingsHere = mod.settings.isNotEmpty() && (
                (kind == ScriptKind.PLAYER && (script.uid == firstOf(ScriptKind.PLAYER)?.uid)) ||
                    (globalSettings && kind == ScriptKind.GLOBAL && script.uid == firstOf(ScriptKind.GLOBAL)?.uid))

            // the head: what the script uses, its variables
            out.line("-- ${mod.title}: ${script.name} (${kind.label}). Made with the Alpha3 Lua mod creator.", null)
            if (settingsHere || usesSettings) { modules += "storage" }
            if (settingsHere) usesInterfaces = true
            if (settingWatchers.isNotEmpty()) { usesAsync = true; modules += "storage" }
            if (usesAsync) modules += "async"
            if ("self" in modules) usesSelf = true
            for (m in modules.filter { it != "I" && it != "self" }.sortedBy { orderOf(it) }) {
                val module = byAccess[m] ?: continue
                out.line("local ${module.access} = require('${module.require}')", null)
            }
            if (usesSelf && kind.isLocal) out.line("local self = require('openmw.self')", null)
            if (usesInterfaces) out.line("local I = require('openmw.interfaces')", null)
            out.blank()
            if (usesSettings || settingsHere || settingWatchers.isNotEmpty()) {
                val section = if (globalSettings) "storage.globalSection('$settingsGroup')" else "storage.playerSection('$settingsGroup')"
                out.line("local settings = $section", null)
            }
            if (settingsHere) registerSettings()
            for (v in script.variables) {
                out.line("local ${identifier(v.name, "value")} = ${expr(v.initial)}${if (v.saved) "  -- kept in saves" else ""}", null)
            }
            for ((name, _) in timers) out.line("local ${name}_time = 0", null)
            if (script.variables.isNotEmpty() || timers.isNotEmpty()) out.blank()
            out.append(body)

            // engine handlers made of the triggers that share one (keys, timers, console words)
            if (keyFilters.isNotEmpty()) {
                out.line("local function keyPressed(key)", null)
                out.indent {
                    for ((fn, k) in keyFilters) {
                        val cond = buildList {
                            add("key.symbol == ${luaString(k.symbol)}")
                            if (k.shift) add("key.withShift")
                            if (k.ctrl) add("key.withCtrl")
                            if (k.alt) add("key.withAlt")
                        }.joinToString(" and ")
                        out.line("if $cond then $fn(key) end", null)
                    }
                }
                out.line("end", null)
                out.blank()
                handlerFns.getOrPut("onKeyPress") { ArrayList() } += "keyPressed"
            }
            if (timers.isNotEmpty()) {
                out.line("local function timersRun(dt)", null)
                out.indent {
                    for ((name, seconds) in timers) {
                        out.line("${name}_time = ${name}_time + dt", null)
                        out.line("if ${name}_time >= ${num(seconds)} then", null)
                        out.indent { out.line("${name}_time = 0", null); out.line("$name()", null) }
                        out.line("end", null)
                    }
                }
                out.line("end", null)
                out.blank()
                handlerFns.getOrPut("onUpdate") { ArrayList() }.add(0, "timersRun")
            }
            if (consoleWords.isNotEmpty()) {
                out.line("local function consoleCommand(mode, command, selectedObject)", null)
                out.indent {
                    out.line("local word, args = command:match('^%s*lua%s+(%S+)%s*(.-)%s*$')", null)
                    for ((fn, word) in consoleWords) out.line("if word == ${luaString(word)} then $fn(args, selectedObject) end", null)
                }
                out.line("end", null)
                out.blank()
                handlerFns.getOrPut("onConsoleCommand") { ArrayList() } += "consoleCommand"
            }
            if (settingWatchers.isNotEmpty()) {
                out.line("settings:subscribe(async:callback(function(_, key)", null)
                out.indent {
                    for ((fn, key) in settingWatchers)
                        out.line(if (key == null) "$fn(key)" else "if key == ${luaString(key)} then $fn(key) end", null)
                }
                out.line("end))", null)
                out.blank()
            }

            // saved variables: written into the save and read back, around any onSave/onLoad of the mod's own
            val saved = script.variables.filter { it.saved }
            val ownSave = handlerFns.remove("onSave")
            val ownLoad = handlerFns.remove("onLoad")
            out.line("return {", null)
            out.indent {
                if (script.interfaceName != null && script.functions.any { it.exported }) {
                    out.line("interfaceName = ${luaString(identifier(script.interfaceName))},", null)
                    out.line("interface = {", null)
                    out.indent {
                        out.line("version = 1,", null)
                        for (f in script.functions.filter { it.exported }) out.line("${identifier(f.name)} = ${identifier(f.name)},", null)
                    }
                    out.line("},", null)
                }
                val engine = LinkedHashMap<String, String>()
                for ((h, fns) in handlerFns) {
                    val params = handlerParams(h).joinToString(", ")
                    engine[h] = if (fns.size == 1) fns[0] else "function($params) ${fns.joinToString(" ") { "$it($params)" }} end"
                }
                if (saved.isNotEmpty() || ownSave != null) {
                    engine["onSave"] = buildString {
                        append("function() ")
                        ownSave?.forEach { append("$it() ") }
                        append("return { ")
                        append(saved.joinToString(", ") { "${identifier(it.name)} = ${identifier(it.name)}" })
                        append(" } end")
                    }
                }
                if (saved.isNotEmpty() || ownLoad != null) {
                    engine["onLoad"] = buildString {
                        append("function(data, initData) ")
                        if (saved.isNotEmpty()) {
                            append("data = data or {} ")
                            saved.forEach { append("if data.${identifier(it.name)} ~= nil then ${identifier(it.name)} = data.${identifier(it.name)} end ") }
                        }
                        ownLoad?.forEach { append("$it(data, initData) ") }
                        append("end")
                    }
                }
                if (engine.isNotEmpty()) {
                    out.line("engineHandlers = {", null)
                    out.indent { for ((k, v) in engine) out.line("$k = $v,", null) }
                    out.line("},", null)
                }
                if (eventFns.isNotEmpty()) {
                    out.line("eventHandlers = {", null)
                    out.indent {
                        for ((k, fns) in eventFns) {
                            val key = if (Regex("^[A-Za-z_][A-Za-z0-9_]*$").matches(k)) k else "[${luaString(k)}]"
                            out.line("$key = ${if (fns.size == 1) fns[0] else "function(data) ${fns.joinToString(" ") { "$it(data)" }} end"},", null)
                        }
                    }
                    out.line("},", null)
                }
            }
            out.line("}", null)
            return out.text() to out.map
        }

        fun registerSettings() {
            usesInterfaces = true
            if (kind == ScriptKind.PLAYER) {
                out.line("I.Settings.registerPage {", null)
                out.indent {
                    out.line("key = ${luaString(settingsPage)},", null)
                    out.line("l10n = ${luaString(modId)},", null)
                    out.line("name = 'pageName',", null)
                    out.line("description = 'pageDescription',", null)
                }
                out.line("}", null)
            }
            if ((kind == ScriptKind.GLOBAL) == globalSettings) {
                out.line("I.Settings.registerGroup {", null)
                out.indent {
                    out.line("key = ${luaString(settingsGroup)},", null)
                    out.line("page = ${luaString(settingsPage)},", null)
                    out.line("l10n = ${luaString(modId)},", null)
                    out.line("name = 'groupName',", null)
                    out.line("permanentStorage = true,", null)
                    out.line("settings = {", null)
                    out.indent {
                        for (s in mod.settings) {
                            val arg = when (s.renderer) {
                                "number" -> listOfNotNull(
                                    s.min?.let { "min = ${num(it)}" }, s.max?.let { "max = ${num(it)}" },
                                    if (s.integer) "integer = true" else null,
                                ).takeIf { it.isNotEmpty() }?.joinToString(", ", "{ ", " }")
                                "select" -> "{ l10n = ${luaString(modId)}, items = { ${s.items.joinToString(", ") { luaString(it) }} } }"
                                else -> null
                            }
                            out.line("{", null)
                            out.indent {
                                out.line("key = ${luaString(s.key)},", null)
                                out.line("renderer = ${luaString(s.renderer)},", null)
                                out.line("name = ${luaString("${s.key}_name")},", null)
                                if (s.description.isNotBlank()) out.line("description = ${luaString("${s.key}_description")},", null)
                                out.line("default = ${expr(s.default)},", null)
                                if (arg != null) out.line("argument = $arg,", null)
                            }
                            out.line("},", null)
                        }
                    }
                    out.line("},", null)
                }
                out.line("}", null)
            }
            out.blank()
        }

        fun handlerParams(engine: String): List<String> = when (engine) {
            "onKeyPress", "onKeyRelease" -> listOf("key")
            "onConsoleCommand" -> listOf("mode", "command", "selectedObject")
            else -> api.handler(engine)?.params?.map { identifier(it, "arg") } ?: emptyList()
        }

        fun handler(h: Handler, index: Int, body: Code) {
            currentUid = h.uid
            val t = h.trigger
            val fn = when (t) {
                is Trigger.Engine -> t.name.removePrefix("on").replaceFirstChar { it.lowercase() } + index
                is Trigger.Event -> "on" + identifier(t.name).replaceFirstChar { it.uppercase() } + index
                is Trigger.Key -> "key${identifier(t.symbol, "k").replaceFirstChar { it.uppercase() }}$index"
                is Trigger.Every -> "every$index"
                is Trigger.Console -> "command${identifier(t.word).replaceFirstChar { it.uppercase() }}$index"
                is Trigger.SettingChanged -> "settingChanged$index"
            }
            val params = when (t) {
                is Trigger.Engine -> handlerParams(t.name)
                is Trigger.Event -> listOf("data")
                is Trigger.Key -> listOf("key")
                is Trigger.Every -> emptyList()
                is Trigger.Console -> listOf("args", "selectedObject")
                is Trigger.SettingChanged -> listOf("key")
            }
            body.line("-- ${triggerTitle(t)}", h.uid)
            body.line("local function $fn(${params.joinToString(", ")})", h.uid)
            body.indent { blocks(h.body, body) }
            body.line("end", h.uid)
            body.blank()
            when (t) {
                is Trigger.Engine -> handlerFns.getOrPut(t.name) { ArrayList() } += fn
                is Trigger.Event -> eventFns.getOrPut(t.name) { ArrayList() } += fn
                is Trigger.Key -> keyFilters += fn to t
                is Trigger.Every -> timers += fn to t.seconds
                is Trigger.Console -> consoleWords += fn to t.word
                is Trigger.SettingChanged -> settingWatchers += fn to t.key
            }
        }

        fun blocks(list: List<Block>, c: Code) {
            if (list.isEmpty()) return
            for (b in list) block(b, c)
        }

        fun block(b: Block, c: Code) {
            val id = b.uid
            currentUid = id
            when (b) {
                is Block.Call -> {
                    val call = callCode(b.ref, b.target, b.args, routeable = true, saveAs = b.saveAs)
                    c.line(if (b.saveAs != null && !call.startsWith("core.sendGlobalEvent") && !call.startsWith("for _, p")) "local ${identifier(b.saveAs)} = $call" else call, id)
                }
                is Block.If -> {
                    c.line("if ${expr(b.condition)} then", id)
                    c.indent { blocks(b.then, c) }
                    if (b.otherwise.isNotEmpty()) {
                        c.line("else", id)
                        c.indent { blocks(b.otherwise, c) }
                    }
                    c.line("end", id)
                }
                is Block.ForEach -> {
                    c.line("for ${identifier(b.index, "i")}, ${identifier(b.item, "item")} in ipairs(${expr(b.over)}) do", id)
                    c.indent { blocks(b.body, c) }
                    c.line("end", id)
                }
                is Block.Repeat -> {
                    c.line("for ${identifier(b.counter, "n")} = 1, ${expr(b.times)} do", id)
                    c.indent { blocks(b.body, c) }
                    c.line("end", id)
                }
                is Block.Set -> c.line("${if (b.local) "local " else ""}${identifier(b.name, "value")} = ${expr(b.value)}", id)
                is Block.Send -> {
                    val data = if (b.data.isEmpty()) "{}" else table(b.data)
                    val to = b.to
                    when {
                        to == null -> { modules += "core"; c.line("core.sendGlobalEvent(${luaString(b.event)}, $data)", id) }
                        to == Expr.Player && kind == ScriptKind.GLOBAL -> {
                            modules += "world"
                            c.line("for _, p in ipairs(world.players) do p:sendEvent(${luaString(b.event)}, $data) end", id)
                        }
                        else -> c.line("${exprAtom(to, callee = true)}:sendEvent(${luaString(b.event)}, $data)", id)
                    }
                }
                is Block.After -> {
                    usesAsync = true
                    c.line("async:newUnsavableSimulationTimer(${expr(b.seconds)}, function()", id)
                    c.indent { blocks(b.body, c) }
                    c.line("end)", id)
                }
                is Block.Return -> c.line(if (b.value == null) "do return end" else "do return ${expr(b.value)} end", id)
                is Block.Stop -> c.line("do return end", id)
                is Block.Lua -> b.code.lines().forEach { c.line(it, id) }
                is Block.Note -> b.text.lines().forEach { c.line("-- $it", id) }
            }
        }

        /** The code of a call; one this script's kind may not make becomes an event to a script that may. */
        fun callCode(ref: String, target: Expr?, args: List<Expr>, routeable: Boolean, saveAs: String? = null): String {
            if (ref.startsWith("self:")) return "${identifier(ref.removePrefix("self:"))}(${args.joinToString(", ") { expr(it) }})"
            val c = api.resolve(ref) ?: return "--[[ unknown function $ref ]] nil"
            if (routeable && kind != ScriptKind.MENU && !api.allowed(c, kind)) {
                val dest = when {
                    api.allowed(c, ScriptKind.GLOBAL) && kind != ScriptKind.GLOBAL -> ScriptKind.GLOBAL
                    api.allowed(c, ScriptKind.PLAYER) && kind != ScriptKind.PLAYER -> ScriptKind.PLAYER
                    else -> null
                }
                if (dest != null) return route(c, target, args, dest, saveAs)
            }
            use(c)
            val argText = args.joinToString(", ") { expr(it) }
            return if (c.fn.method) "${target?.let { exprAtom(it, callee = true) } ?: "nil"}:${c.fn.name}($argText)"
            else "${c.title}($argText)"
        }

        fun route(c: ApiCallable, target: Expr?, args: List<Expr>, dest: ScriptKind, saveAs: String?): String {
            val event = "${modId}_${c.fn.name}_$currentUid"
            if (saveAs != null) notes += "${c.title}: its result can't be kept here, the call is made by the ${dest.label.lowercase()} script"
            notes += "${c.title} is made by the ${dest.label.lowercase()} script (sent as event $event)"
            val data = buildList {
                if (target != null) add(Entry("target", target))
                args.forEachIndexed { i, a -> add(Entry("a${i + 1}", a)) }
            }
            // the receiving script's code: the same call over the event's data
            val modulesThere = LinkedHashSet<String>()
            c.module?.let { modulesThere += if (it.kind == "interface") "I" else it.access }
            val argsThere = args.indices.joinToString(", ") { "d.a${it + 1}" }
            val callThere = if (c.fn.method) "d.target:${c.fn.name}($argsThere)" else "${c.title}($argsThere)"
            if (routes.none { it.event == event }) routes += Route(dest, event, callThere, modulesThere)
            return if (dest == ScriptKind.GLOBAL) {
                modules += "core"
                "core.sendGlobalEvent(${luaString(event)}, ${table(data)})"
            } else if (kind == ScriptKind.GLOBAL) {
                modules += "world"
                "for _, p in ipairs(world.players) do p:sendEvent(${luaString(event)}, ${table(data)}) end"
            } else {
                modules += "nearby"
                "for _, p in ipairs(nearby.players) do p:sendEvent(${luaString(event)}, ${table(data)}) end"
            }
        }

        fun use(c: ApiCallable) {
            val m = c.module ?: return
            when (m.kind) {
                "interface" -> usesInterfaces = true
                "package", "aux" -> if (m.access == "self") usesSelf = true else modules += m.access
                else -> {}
            }
        }

        fun table(entries: List<Entry>): String = if (entries.isEmpty()) "{}" else
            entries.joinToString(", ", "{ ", " }") { e ->
                val k = if (Regex("^[A-Za-z_][A-Za-z0-9_]*$").matches(e.key)) e.key else "[${luaString(e.key)}]"
                "$k = ${expr(e.value)}"
            }

        /** An expression as an operand: bracketed when it could bind wrongly; [callee] also for 'x':m() and {..}.f. */
        fun exprAtom(e: Expr, callee: Boolean = false): String {
            val s = expr(e)
            val wrap = e is Expr.Op || e is Expr.Not || (e is Expr.Lua && !Regex("^[A-Za-z_][A-Za-z0-9_.]*$").matches(e.code.trim())) ||
                (callee && (e is Expr.Text || e is Expr.Num || e is Expr.Table))
            return if (wrap) "($s)" else s
        }

        fun expr(e: Expr): String = when (e) {
            Expr.Nil -> "nil"
            is Expr.Text -> luaString(e.value)
            is Expr.Num -> num(e.value)
            is Expr.Bool -> e.value.toString()
            is Expr.Var -> identifier(e.name, "value")
            is Expr.Call -> callCode(e.ref, e.target, e.args, routeable = false)
            is Expr.Field -> "${exprAtom(e.of, callee = true)}.${e.name}"
            is Expr.Const -> {
                val head = e.path.substringBefore('.')
                when {
                    head == "I" -> usesInterfaces = true
                    head == "self" -> usesSelf = true
                    byAccess.containsKey(head) -> modules += head
                }
                e.path
            }
            is Expr.Op -> "${exprAtom(e.left)} ${e.op} ${exprAtom(e.right)}"
            is Expr.Not -> "not ${exprAtom(e.value)}"
            is Expr.Table -> table(e.entries)
            is Expr.Record -> luaString(e.id)
            is Expr.Setting -> { usesSettings = true; "settings:get(${luaString(e.key)})" }
            Expr.Player -> when (kind) {
                ScriptKind.PLAYER -> { usesSelf = true; "self.object" }
                ScriptKind.GLOBAL -> { modules += "world"; "world.players[1]" }
                ScriptKind.MENU -> "nil"
                else -> { modules += "nearby"; "nearby.players[1]" }
            }
            Expr.Self -> if (kind.isLocal) { usesSelf = true; "self.object" } else "nil --[[ only scripts on objects have a self ]]"
            is Expr.Lua -> e.code
        }
    }

    private fun firstOf(kind: ScriptKind): LuaScript? =
        mod.scripts.firstOrNull { it.kind == kind && it.code == null }
            ?: if (kind == ScriptKind.GLOBAL) LuaScript(uid = "_global", name = "world", kind = kind)
            else LuaScript(uid = "_player", name = "player", kind = kind)

    private fun orderOf(access: String) = listOf("core", "world", "types", "self", "nearby", "ui", "util", "async", "storage", "input", "camera", "ambient").indexOf(access).let { if (it < 0) 100 else it }

    /** A plain reading of what starts a handler, for the comment over its code and the editor. */
    fun triggerTitle(t: Trigger): String = Companion.triggerTitle(t)

    companion object {
        fun triggerTitle(t: Trigger): String = when (t) {
            is Trigger.Engine -> HANDLER_TITLES[t.name] ?: t.name
            is Trigger.Event -> "When the event \"${t.name}\" arrives"
            is Trigger.Key -> "When ${listOfNotNull(if (t.ctrl) "Ctrl" else null, if (t.alt) "Alt" else null, if (t.shift) "Shift" else null).plus(t.symbol.uppercase()).joinToString("+")} is pressed"
            is Trigger.Every -> "Every ${num(t.seconds)} seconds"
            is Trigger.Console -> "When \"lua ${t.word}\" is typed in the console"
            is Trigger.SettingChanged -> if (t.key == null) "When a setting changes" else "When the setting \"${t.key}\" changes"
        }

        /** Friendly names of the engine handlers. */
        val HANDLER_TITLES = mapOf(
            "onInit" to "When the script starts (once)",
            "onUpdate" to "Every frame",
            "onFrame" to "Every frame, even when paused",
            "onSave" to "When the game is saved",
            "onLoad" to "When the script is loaded from a save",
            "onNewGame" to "When a new game starts",
            "onPlayerAdded" to "When the player enters the world",
            "onObjectActive" to "When an object comes near",
            "onActorActive" to "When an NPC or creature comes near",
            "onItemActive" to "When an item comes near",
            "onActivate" to "When anything is activated",
            "onNewExterior" to "When a new exterior cell is made",
            "onDropped" to "When an item is dropped",
            "onPlaced" to "When an item is placed",
            "onActive" to "When this object comes near",
            "onInactive" to "When this object goes away",
            "onTeleported" to "When this object is teleported",
            "onActivated" to "When this object is activated",
            "onConsume" to "When this actor eats or drinks something",
            "onKeyPress" to "When any key is pressed",
            "onKeyRelease" to "When any key is released",
            "onControllerButtonPress" to "When a controller button is pressed",
            "onControllerButtonRelease" to "When a controller button is released",
            "onTouchPress" to "When the screen is touched",
            "onTouchRelease" to "When a touch ends",
            "onTouchMove" to "When a touch moves",
            "onMouseButtonPress" to "When a mouse button is pressed",
            "onMouseButtonRelease" to "When a mouse button is released",
            "onMouseWheel" to "When the mouse wheel turns",
            "onConsoleCommand" to "When anything is typed in the console",
            "onViewportResized" to "When the screen changes size",
            "onQuestUpdate" to "When a quest is updated",
            "onStateChanged" to "When a game starts or ends (menu)",
            "onContentFilesLoaded" to "When the content files are loaded",
            "onInterfaceOverride" to "When this overrides another interface",
            "onInputAction" to "When a game control is used (old)",
        )
    }
}

/** Every expression a block holds directly (not those of nested blocks). */
fun exprsOf(b: Block): List<Expr> = when (b) {
    is Block.Call -> b.args + listOfNotNull(b.target)
    is Block.If -> listOf(b.condition)
    is Block.ForEach -> listOf(b.over)
    is Block.Repeat -> listOf(b.times)
    is Block.Set -> listOf(b.value)
    is Block.Send -> b.data.map { it.value } + listOfNotNull(b.to)
    is Block.After -> listOf(b.seconds)
    is Block.Return -> listOfNotNull(b.value)
    else -> emptyList()
}

fun Expr.anyExpr(test: (Expr) -> Boolean): Boolean = test(this) || when (this) {
    is Expr.Call -> args.any { it.anyExpr(test) } || target?.anyExpr(test) == true
    is Expr.Field -> of.anyExpr(test)
    is Expr.Op -> left.anyExpr(test) || right.anyExpr(test)
    is Expr.Not -> value.anyExpr(test)
    is Expr.Table -> entries.any { it.value.anyExpr(test) }
    else -> false
}

fun allBlocks(s: LuaScript): Sequence<Block> = s.handlers.asSequence().flatMap { it.body.walk() } + s.functions.asSequence().flatMap { it.body.walk() }

fun luaString(s: String): String = buildString {
    append('\'')
    for (ch in s) when (ch) {
        '\\' -> append("\\\\")
        '\'' -> append("\\'")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> if (ch < ' ') append("\\" + ch.code) else append(ch)
    }
    append('\'')
}

fun num(d: Double): String = if (d == kotlin.math.floor(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toString()

/** Lines of code with indentation, and which uid each line came from. */
class Code {
    private val sb = StringBuilder()
    private var depth = 0
    private var lineNo = 1
    val map = LinkedHashMap<Int, String>()

    fun line(text: String, uid: String?) {
        repeat(depth) { sb.append("    ") }
        sb.append(text).append('\n')
        if (uid != null) map[lineNo] = uid
        lineNo++
    }

    fun blank() { sb.append('\n'); lineNo++ }

    inline fun indent(block: () -> Unit) { push(); try { block() } finally { pop() } }
    fun push() { depth++ }
    fun pop() { depth-- }

    fun append(other: Code) {
        val offset = lineNo - 1
        for ((k, v) in other.map) map[k + offset] = v
        val text = other.text()
        sb.append(text)
        lineNo += text.count { it == '\n' }
    }

    fun text(): String = sb.toString()
}
