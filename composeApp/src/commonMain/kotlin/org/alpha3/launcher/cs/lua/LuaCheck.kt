package org.alpha3.launcher.cs.lua

/**
 * The creator's checker. It reads each script's Lua (generated from blocks or written by hand) and reports what would
 * fail or misbehave in the game, in plain words:
 *  - syntax errors, as the game's Lua would report them;
 *  - API use against the engine's documentation: modules a script kind may not require (openmw.world in a player
 *    script), functions that do not exist or that the script kind may not call, too many or too few arguments;
 *  - engine handlers that do not exist or do not run in the script's kind;
 *  - events sent that nothing handles, and handled events nothing sends;
 *  - names read that were never set (a misspelt variable or a missing require).
 */
enum class Severity { Error, Warning, Info }

data class Problem(
    val severity: Severity,
    /** The script file inside the mod's folder, or null for the mod as a whole. */
    val file: String?,
    val line: Int,
    val col: Int,
    val message: String,
    /** The block or handler the line came from, when the script is made of blocks. */
    val uid: String? = null,
)

class LuaCheck(private val api: LuaApi) {
    /** What a name stands for while checking. */
    private sealed class Binding {
        data class Module(val module: ApiModule) : Binding()
        data class Type(val type: String, val label: String) : Binding()
        data object Interfaces : Binding()
        data object Value : Binding()
    }

    private val luaGlobals = setOf(
        "assert", "error", "getmetatable", "ipairs", "next", "pairs", "pcall", "print", "rawequal", "rawget", "rawlen",
        "rawset", "select", "setmetatable", "tonumber", "tostring", "type", "unpack", "xpcall", "require", "_G", "_VERSION",
        "math", "string", "table", "coroutine", "os", "bit", "utf8", "loadstring", "load",
    )

    class ScriptInfo(val file: String, val kind: ScriptKind, val sent: MutableList<Pair<String, Pos>> = ArrayList(), val handled: MutableList<Pair<String, Pos>> = ArrayList())

    /** Checks one script; [lines] maps its lines to blocks (see [LuaGen.Output.lines]). */
    fun checkScript(file: String, kind: ScriptKind, code: String, lines: Map<Int, String> = emptyMap(), info: ScriptInfo? = null): List<Problem> {
        val problems = ArrayList<Problem>()
        fun report(sev: Severity, p: Pos, msg: String) {
            problems += Problem(sev, file, p.line, p.col, msg, lines[p.line] ?: nearestUid(lines, p.line))
        }
        val chunk = try {
            LuaParser.parse(code)
        } catch (e: LuaSyntaxError) {
            report(Severity.Error, Pos(e.line, e.col), "Lua syntax: ${e.message}")
            return problems
        }
        Walker(kind, ::report, info).chunk(chunk)
        return problems
    }

    private fun nearestUid(lines: Map<Int, String>, line: Int): String? {
        var l = line
        while (l > 0) { lines[l]?.let { return it }; l-- }
        return null
    }

    /** Checks every script of a generated mod, and the events between them. */
    fun checkMod(output: LuaGen.Output, kinds: Map<String, ScriptKind>): List<Problem> {
        val problems = ArrayList<Problem>()
        val infos = ArrayList<ScriptInfo>()
        for ((path, code) in output.files) {
            if (!path.endsWith(".lua")) continue
            val kind = kinds[path] ?: continue
            val info = ScriptInfo(path, kind)
            infos += info
            problems += checkScript(path, kind, code, output.lines[path].orEmpty(), info)
        }
        val handled = infos.flatMap { i -> i.handled.map { it.first } }.toSet()
        val sent = infos.flatMap { i -> i.sent.map { it.first } }.toSet()
        for (i in infos) {
            for ((name, p) in i.sent) if (name !in handled && name !in api.builtinEvents)
                problems += Problem(Severity.Warning, i.file, p.line, p.col,
                    "The event \"$name\" is sent, but no script of this mod handles it (fine if another mod does)",
                    output.lines[i.file]?.get(p.line))
            for ((name, p) in i.handled) if (name !in sent && name !in api.builtinEvents)
                problems += Problem(Severity.Info, i.file, p.line, p.col,
                    "The event \"$name\" is handled, but this mod never sends it (fine if another mod or the engine does)",
                    output.lines[i.file]?.get(p.line))
        }
        return problems.sortedWith(compareBy({ it.severity.ordinal }, { it.file }, { it.line }))
    }

    private inner class Walker(val kind: ScriptKind, val report: (Severity, Pos, String) -> Unit, val info: ScriptInfo?) {
        val scopes = ArrayList<HashMap<String, Binding>>().apply { add(HashMap()) }
        val globalsSet = HashSet<String>()

        fun declare(name: String, b: Binding = Binding.Value) { scopes.last()[name] = b }
        fun lookup(name: String): Binding? { for (i in scopes.indices.reversed()) scopes[i][name]?.let { return it }; return null }
        inline fun scoped(block: () -> Unit) { scopes += HashMap(); try { block() } finally { scopes.removeAt(scopes.size - 1) } }

        fun chunk(stats: List<LStat>) {
            // a function declared after it is used (local function f called by an earlier function) is still found:
            // the checker's pass over names is per scope, so first collect every top-level local
            stats(stats)
            val ret = stats.lastOrNull() as? LStat.Return
            if (ret == null) {
                report(Severity.Warning, Pos(1, 1), "The script does not end with 'return { ... }': the game will attach it, but it does nothing")
                return
            }
            val t = ret.values.firstOrNull()
            val table = when (t) {
                is LExpr.Table -> t
                is LExpr.Name -> null
                else -> null
            } ?: return
            for (f in table.fields) {
                when (f.name) {
                    "engineHandlers" -> (f.value as? LExpr.Table)?.fields?.forEach { h -> h.name?.let { handlerName(it, h.value.pos) } }
                    "eventHandlers" -> (f.value as? LExpr.Table)?.fields?.forEach { h ->
                        val n = h.name ?: (h.key as? LExpr.Str)?.value
                        if (n != null) info?.handled?.add(n to h.value.pos)
                    }
                    "interfaceName", "interface" -> {}
                    null -> {}
                    else -> report(Severity.Warning, f.value.pos, "'${f.name}' in the returned table is not something the game reads (engineHandlers, eventHandlers, interfaceName, interface)")
                }
            }
        }

        fun handlerName(name: String, p: Pos) {
            val h = api.handler(name)
            if (h == null) {
                val close = api.catalog.handlers.minByOrNull { distance(it.name.lowercase(), name.lowercase()) }?.name
                report(Severity.Error, p, "There is no engine handler '$name'${close?.let { "; did you mean '$it'?" } ?: ""}")
            } else if (!api.allowed(h, kind)) {
                report(Severity.Error, p, "'$name' is not called in ${kind.label.lowercase()} scripts (only in ${h.contexts.joinToString(", ")} scripts)")
            }
        }

        fun stats(list: List<LStat>) { for (s in list) stat(s) }

        fun stat(s: LStat) {
            when (s) {
                is LStat.Local -> {
                    s.values.forEach { expr(it) }
                    s.names.forEachIndexed { i, n -> declare(n, bindingOf(s.values.getOrNull(i))) }
                }
                is LStat.Assign -> {
                    s.values.forEach { expr(it) }
                    for (t in s.targets) {
                        if (t is LExpr.Name) {
                            if (lookup(t.name) == null) {
                                globalsSet += t.name
                                report(Severity.Warning, t.pos, "'${t.name}' is set without 'local': it becomes a global of this script; write 'local ${t.name} = ...' unless that is meant")
                                declare(t.name)
                            }
                        } else expr(t)
                    }
                }
                is LStat.CallStat -> expr(s.call)
                is LStat.Do -> scoped { stats(s.body) }
                is LStat.While -> { expr(s.cond); scoped { stats(s.body) } }
                is LStat.Repeat -> scoped { stats(s.body); expr(s.cond) }
                is LStat.If -> {
                    s.conds.forEachIndexed { i, c -> expr(c); scoped { stats(s.blocks[i]) } }
                    s.otherwise?.let { scoped { stats(it) } }
                }
                is LStat.NumFor -> {
                    expr(s.from); expr(s.to); s.step?.let { expr(it) }
                    scoped { declare(s.name); stats(s.body) }
                }
                is LStat.GenFor -> {
                    s.values.forEach { expr(it) }
                    scoped { s.names.forEach { declare(it) }; stats(s.body) }
                }
                is LStat.Function -> {
                    val t = s.target
                    if (t is LExpr.Name && lookup(t.name) == null) { globalsSet += t.name; declare(t.name) }
                    else if (t is LExpr.Index) expr(t.obj)
                    func(s.fn)
                }
                is LStat.LocalFunction -> { declare(s.name); func(s.fn) }
                is LStat.Return -> s.values.forEach { expr(it) }
                is LStat.Break, is LStat.Goto, is LStat.Label -> {}
            }
        }

        fun func(f: LExpr.Func) = scoped {
            f.params.forEach { declare(it) }
            if (f.vararg) declare("arg")
            stats(f.body)
        }

        /** What a local is set to: a required module, the interfaces, or a plain value. */
        fun bindingOf(e: LExpr?): Binding {
            if (e is LExpr.Call && e.fn is LExpr.Name && e.fn.name == "require") {
                val id = (e.args.firstOrNull() as? LExpr.Str)?.value ?: return Binding.Value
                if (id == "openmw.interfaces") return Binding.Interfaces
                return api.moduleByRequire(id)?.let { Binding.Module(it) } ?: Binding.Value
            }
            return resolveValue(e) ?: Binding.Value
        }

        /** A documented table an expression names (types.Actor, I.UI, core.sound), for checking what is taken from it. */
        fun resolveValue(e: LExpr?): Binding? = when (e) {
            is LExpr.Name -> lookup(e.name) ?: stdModule(e.name)
            is LExpr.Index -> {
                val key = (e.key as? LExpr.Str)?.value
                val base = resolveValue(e.obj)
                if (key == null || base == null || !e.dot) null else when (base) {
                    is Binding.Interfaces -> api.interfaceModule(key)?.let { Binding.Module(it) }
                    is Binding.Module -> api.fields(base.module.type).firstOrNull { it.name == key }?.let { f ->
                        if (api.isTable(f.type)) Binding.Type(f.type, "${base.module.access}.$key") else null
                    }
                    is Binding.Type -> api.fields(base.type).firstOrNull { it.name == key }?.let { f ->
                        if (api.isTable(f.type)) Binding.Type(f.type, "${base.label}.$key") else null
                    }
                    Binding.Value -> null
                }
            }
            is LExpr.Paren -> resolveValue(e.value)
            else -> null
        }

        fun stdModule(name: String): Binding? = if (name in setOf("math", "string", "table", "coroutine")) api.module("lua.$name")?.let { Binding.Module(it) } else null

        fun typeOf(b: Binding): Pair<String, String>? = when (b) {
            is Binding.Module -> b.module.type to b.module.access
            is Binding.Type -> b.type to b.label
            else -> null
        }

        fun expr(e: LExpr) {
            when (e) {
                is LExpr.Name -> if (lookup(e.name) == null && e.name !in luaGlobals && e.name !in globalsSet)
                    report(Severity.Warning, e.pos, "'${e.name}' is not set anywhere before this (misspelt, or a missing 'local ${e.name} = require(...)'?)")
                is LExpr.Index -> {
                    expr(e.obj)
                    if (!e.dot) expr(e.key)
                    member(e)
                }
                is LExpr.Call -> {
                    expr(e.fn)
                    e.args.forEach { expr(it) }
                    call(e)
                }
                is LExpr.Method -> {
                    expr(e.obj)
                    e.args.forEach { expr(it) }
                    if (e.name == "sendEvent") (e.args.firstOrNull() as? LExpr.Str)?.let { info?.sent?.add(it.value to it.pos) }
                    val m = api.methodsByName[e.name]
                    if (m != null && m.isNotEmpty() && m.all { !api.allowedMethod(it, kind) }) {
                        val first = m.first()
                        report(Severity.Error, e.pos, "':${e.name}()' can only be used in ${api.contexts(first).joinToString(" or ")} scripts")
                    }
                }
                is LExpr.Func -> func(e)
                is LExpr.Table -> e.fields.forEach { f -> f.key?.let { expr(it) }; expr(f.value) }
                is LExpr.Bin -> { expr(e.left); expr(e.right) }
                is LExpr.Un -> expr(e.value)
                is LExpr.Paren -> expr(e.value)
                is LExpr.Str, is LExpr.Num, is LExpr.Lit -> {}
            }
        }

        /** module.name: does the module (or documented table) have it? */
        fun member(e: LExpr.Index) {
            if (!e.dot) return
            val key = (e.key as? LExpr.Str)?.value ?: return
            val base = resolveValue(e.obj) ?: return
            if (base is Binding.Interfaces) {
                if (api.interfaceModule(key) == null && key !in api.catalog.modules.map { it.name }) {
                    report(Severity.Info, e.pos, "I.$key is not one of the game's interfaces; fine if a mod provides it")
                } else api.interfaceModule(key)?.let { m ->
                    if (!api.allowed(m, kind)) report(Severity.Error, e.pos, "I.$key is not available in ${kind.label.lowercase()} scripts (only ${m.contexts.joinToString(", ")})")
                }
                return
            }
            // the standard library's tables are LuaJIT's, which has more than the docs list (table.unpack...)
            if (base is Binding.Module && base.module.kind == "lua") return
            val (type, label) = typeOf(base) ?: return
            val known = api.fields(type).any { it.name == key } || api.fns(type).any { it.name == key }
            if (!known) {
                val names = api.fields(type).map { it.name } + api.fns(type).map { it.name }
                val close = names.minByOrNull { distance(it.lowercase(), key.lowercase()) }?.takeIf { distance(it.lowercase(), key.lowercase()) <= 3 }
                report(Severity.Error, e.pos, "$label has no '$key'${close?.let { "; did you mean '$it'?" } ?: ""}")
            }
        }

        fun call(e: LExpr.Call) {
            val fn = e.fn
            // require: is the module there, and may this kind of script have it?
            if (fn is LExpr.Name && fn.name == "require") {
                val id = (e.args.firstOrNull() as? LExpr.Str)?.value ?: return
                if (id == "openmw.interfaces") return
                val m = api.moduleByRequire(id)
                if (m == null) {
                    if (id.startsWith("openmw.") || id.startsWith("openmw_aux."))
                        report(Severity.Error, e.pos, "There is no module '$id' in this version of the game")
                } else if (!api.allowed(m, kind)) {
                    report(Severity.Error, e.pos, "'$id' can't be used in ${kind.label.lowercase()} scripts, only in ${m.contexts.joinToString(", ")} scripts" + hint(m))
                }
                return
            }
            if (fn !is LExpr.Index || !fn.dot) return
            val name = (fn.key as? LExpr.Str)?.value ?: return
            val owner = resolveValue(fn.obj) ?: return
            val (type, label) = typeOf(owner) ?: return
            val f = api.fns(type).firstOrNull { it.name == name } ?: return
            val c = ApiCallable((owner as? Binding.Module)?.module ?: moduleOfType(type), type, listOf(name), f)
            if (!api.allowed(c, kind)) report(Severity.Error, e.pos, "$label.$name can only be called in ${api.contexts(c).joinToString(" or ")} scripts")
            if (label == "core" && name == "sendGlobalEvent") (e.args.firstOrNull() as? LExpr.Str)?.let { info?.sent?.add(it.value to it.pos) }
            arity(f, e.args, e.pos, "$label.$name")
        }

        fun moduleOfType(type: String): ApiModule? = api.modules.firstOrNull { type.startsWith(it.id + "#") }

        fun arity(f: ApiFn, args: List<LExpr>, p: Pos, label: String) {
            val last = args.lastOrNull()
            val open = last is LExpr.Call || last is LExpr.Method || (last is LExpr.Lit && last.text == "...")
            val required = f.params.count { !it.optional && it.name != "..." }
            val varargs = f.params.any { it.name == "..." || it.type.contains("...") }
            if (!varargs && args.size > f.params.size && f.params.isNotEmpty())
                report(Severity.Warning, p, "$label takes ${f.params.size} argument${if (f.params.size == 1) "" else "s"} (${f.params.joinToString(", ") { it.name }}), ${args.size} given")
            else if (!varargs && f.params.isEmpty() && args.isNotEmpty() && !open)
                report(Severity.Warning, p, "$label takes no arguments, ${args.size} given")
            if (!open && args.size < required)
                report(Severity.Warning, p, "$label needs ${f.params.take(required).joinToString(", ") { it.name }}; ${args.size} given")
        }

        fun hint(m: ApiModule): String = when {
            kind != ScriptKind.GLOBAL && "global" in m.contexts -> ". Send an event to a global script and do it there"
            kind == ScriptKind.GLOBAL && "player" in m.contexts -> ". Send an event to the player and do it in a player script"
            else -> ""
        }
    }

    companion object {
        fun distance(a: String, b: String): Int {
            val d = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var prev = d[0]
                d[0] = i
                for (j in 1..b.length) {
                    val tmp = d[j]
                    d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                    prev = tmp
                }
            }
            return d[b.length]
        }
    }
}

/** A method's script kinds, as for functions (object:teleport() is for global scripts only). */
fun LuaApi.allowedMethod(c: ApiCallable, kind: ScriptKind): Boolean = contexts(c).let { ctx ->
    // a method's own type has no module contexts: only what its description says narrows it
    val d = c.fn.doc.lowercase()
    if ("only" in d || "not available" in d) ctx.any { it in kind.contexts } else true
}
