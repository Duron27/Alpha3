package org.alpha3.launcher.cs.lua

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The OpenMW Lua API as the engine documents it: files/lua_api.json, made from the engine's LuaDoc and reference pages
 * by cpp/openmw-upstream/tools/lua_api_catalog.py. The creator's menus list what it holds and the checker tests code
 * against it, so both follow the API the game actually runs.
 */
@Serializable
data class ApiCatalog(
    val apiRevision: Int = 0,
    val modules: List<ApiModule> = emptyList(),
    val types: Map<String, ApiType> = emptyMap(),
    val handlers: List<ApiHandler> = emptyList(),
    val events: List<ApiEvent> = emptyList(),
)

@Serializable
data class ApiModule(
    val id: String,
    val name: String,
    /** package (openmw.*), interface (I.*), aux (openmw_aux.*) or lua (the standard library) */
    val kind: String,
    /** What require() takes ("" for the standard library, which is global). */
    val require: String = "",
    /** The name the code uses: core, I.UI, math. */
    val access: String = "",
    val contexts: List<String> = emptyList(),
    val doc: String = "",
    val summary: String = "",
    val type: String = "",
    val usage: List<String> = emptyList(),
    val multiplayer: Boolean = false,
)

@Serializable
data class ApiType(
    val doc: String = "",
    val extends: String? = null,
    val fields: List<ApiField> = emptyList(),
    val fns: List<ApiFn> = emptyList(),
    val list: String? = null,
    val map: String? = null,
)

@Serializable
data class ApiField(
    @SerialName("n") val name: String,
    @SerialName("t") val type: String = "any",
    @SerialName("d") val doc: String = "",
    @SerialName("u") val usage: List<String> = emptyList(),
)

@Serializable
data class ApiParam(
    @SerialName("n") val name: String,
    @SerialName("t") val type: String = "any",
    @SerialName("d") val doc: String = "",
    @SerialName("o") val optional: Boolean = false,
)

@Serializable
data class ApiReturn(@SerialName("t") val type: String = "any", @SerialName("d") val doc: String = "")

@Serializable
data class ApiFn(
    @SerialName("n") val name: String,
    @SerialName("d") val doc: String = "",
    @SerialName("p") val params: List<ApiParam> = emptyList(),
    @SerialName("r") val returns: List<ApiReturn> = emptyList(),
    /** Called as a method, object:name(...). */
    @SerialName("m") val method: Boolean = false,
    @SerialName("u") val usage: List<String> = emptyList(),
)

@Serializable
data class ApiHandler(
    @SerialName("n") val name: String,
    @SerialName("p") val params: List<String> = emptyList(),
    val contexts: List<String> = emptyList(),
    @SerialName("d") val doc: String = "",
    @SerialName("r") val returns: String? = null,
)

@Serializable
data class ApiEvent(
    @SerialName("n") val name: String,
    val section: String = "",
    @SerialName("d") val doc: String = "",
    val example: String = "",
)

/** The kinds of script a mod has, as its .omwscripts file names them. */
enum class ScriptKind(val tag: String, val label: String, val explain: String) {
    GLOBAL("GLOBAL", "World (global)", "One copy for the whole game. Changes the world: moves, creates and removes objects, gives items."),
    PLAYER("PLAYER", "Player", "Runs on the player. Owns the screen and input: messages, windows, keys, the camera."),
    NPC("NPC", "Every NPC", "A copy on each NPC while it is near the player. Sees what is nearby; its AI and animations."),
    CREATURE("CREATURE", "Every creature", "A copy on each creature while it is near the player."),
    ACTOR("NPC, CREATURE", "Every NPC and creature", "A copy on each actor while it is near the player."),
    OBJECT("ACTIVATOR, DOOR, CONTAINER", "Activators, doors and containers", "A copy on each of these objects while it is near the player."),
    ITEM("WEAPON, ARMOR, CLOTHING, POTION, INGREDIENT, BOOK, MISCELLANEOUS, LIGHT", "Items in the world", "A copy on each item lying in the world near the player."),
    CUSTOM("CUSTOM", "Attached on demand", "Not attached by itself: a global script adds it to chosen objects (object:addScript)."),
    MENU("MENU", "Main menu", "Runs from the main menu on, even with no game loaded. Settings, menus, new game options."),
    ;

    /** The script kinds of the API docs (@context) this kind may use: a player script is a local script too. */
    val contexts: Set<String>
        get() = when (this) {
            GLOBAL -> setOf("global")
            PLAYER -> setOf("player", "local")
            MENU -> setOf("menu")
            else -> setOf("local")
        }

    val isLocal get() = this != GLOBAL && this != MENU
}

/** A function the creator can call: where it lives in the API and how the code names it. */
data class ApiCallable(
    val module: ApiModule?,
    /** The type the function is documented on (openmw.core#GameObject). */
    val owner: String,
    /** The path from the module's root to the function, without the module itself: ["Actor", "getEquipment"]. */
    val path: List<String>,
    val fn: ApiFn,
) {
    /** The reference saved in a mod: module id, then the path (openmw.types:Actor.getEquipment), or the owner type for a method. */
    val ref: String get() = if (fn.method) "$owner:${fn.name}" else "${module?.id}:${path.joinToString(".")}"
    val title: String get() = if (fn.method) "${owner.substringAfter('#')}:${fn.name}" else
        listOfNotNull(module?.access?.takeIf { it.isNotEmpty() && module.id != "lua.global" }).plus(path).joinToString(".")
}

class LuaApi(val catalog: ApiCatalog) {
    val modules: List<ApiModule> = catalog.modules
    private val byId = modules.associateBy { it.id }
    private val handlersByName = catalog.handlers.associateBy { it.name }
    val builtinEvents: Set<String> = catalog.events.map { it.name }.toSet()

    fun module(id: String): ApiModule? = byId[id]
    fun moduleByRequire(require: String): ApiModule? = modules.firstOrNull { it.require == require && it.kind == "package" || it.require == require && it.kind == "aux" }
    fun interfaceModule(name: String): ApiModule? = byId["interfaces.$name"]
    fun type(key: String): ApiType? = catalog.types[key]
    fun handler(name: String): ApiHandler? = handlersByName[name]

    fun allowed(module: ApiModule, kind: ScriptKind): Boolean = module.contexts.any { it in kind.contexts }
    fun allowed(handler: ApiHandler, kind: ScriptKind): Boolean = handler.contexts.any { it in kind.contexts }

    /**
     * The script kinds a function may be called in: its module's, narrowed by what its description says ("Can be
     * called only from a global script", "Not available in load scripts"...). A player script counts as local.
     */
    fun contexts(c: ApiCallable): Set<String> {
        val base = c.module?.contexts?.toSet() ?: setOf("global", "local", "player", "menu", "load")
        val d = c.fn.doc.lowercase()
        val only = Regex("""only (?:be )?(?:called |used |available )?(?:in|from|by|on self in) (?:an? )?([a-z ,]+?) scripts?""").find(d)
        var out = base
        if (only != null) {
            val words = only.groupValues[1]
            val named = setOf("global", "local", "player", "menu", "load").filter { Regex("""\b$it\b""").containsMatchIn(words) }.toMutableSet()
            if ("self" in d && "local" !in named && Regex("""on self""").containsMatchIn(d)) named += "local"
            if (named.isNotEmpty()) out = named
        } else if ("available in player and menu" in d || "available in player, menu and global" in d || "available both in player, menu and global" in d) {
            out = setOf("player", "menu") + (if ("global" in d) setOf("global") else emptySet())
        }
        Regex("""not available in ([a-z ,]+?) scripts""").find(d)?.let { m ->
            out = out - setOf("global", "local", "player", "menu", "load").filter { m.groupValues[1].contains(it) }.toSet()
        }
        if ("local" in out) out = out + "player"
        return out
    }

    fun allowed(c: ApiCallable, kind: ScriptKind): Boolean = contexts(c).any { it in kind.contexts }

    /** A type's fields and functions with those of the types it extends (NPC has Actor's). */
    fun fns(typeKey: String): List<ApiFn> = chain(typeKey).flatMap { type(it)?.fns.orEmpty() }.distinctBy { it.name }
    fun fields(typeKey: String): List<ApiField> = chain(typeKey).flatMap { type(it)?.fields.orEmpty() }.distinctBy { it.name }

    private fun chain(typeKey: String): List<String> {
        val out = ArrayList<String>()
        var t: String? = typeKey
        while (t != null && t !in out && out.size < 8) {
            out += t
            t = type(t)?.extends
        }
        return out
    }

    /** Whether [typeKey] names a documented table of functions or constants (types.Actor), not a value type (#string). */
    fun isTable(typeKey: String): Boolean = '#' in typeKey && type(typeKey)?.let { it.fns.isNotEmpty() || it.fields.isNotEmpty() || it.extends != null } == true

    /** Everything callable from a module: its own functions and those of its documented sub tables (types.NPC.stats...). */
    fun callables(module: ApiModule): List<ApiCallable> {
        val out = ArrayList<ApiCallable>()
        fun walk(typeKey: String, path: List<String>, seen: Set<String>) {
            for (f in fns(typeKey)) if (!f.method) out += ApiCallable(module, typeKey, path + f.name, f)
            if (path.size >= 3) return
            for (field in fields(typeKey)) {
                if (field.type in seen || !isTable(field.type)) continue
                walk(field.type, path + field.name, seen + field.type)
            }
        }
        walk(module.type, emptyList(), setOf(module.type))
        return out
    }

    /** The methods of an object type, object:name(...), with those it inherits. */
    fun methods(typeKey: String): List<ApiCallable> = chain(typeKey).flatMap { t ->
        type(t)?.fns.orEmpty().filter { it.method }.map { ApiCallable(null, t, listOf(it.name), it) }
    }.distinctBy { it.fn.name }

    /** Every documented method, by name: what object:name(...) can be when the object's type is unknown. */
    val methodsByName: Map<String, List<ApiCallable>> by lazy {
        catalog.types.keys.flatMap { t -> type(t)!!.fns.filter { it.method }.map { ApiCallable(null, t, listOf(it.name), it) } }.groupBy { it.fn.name }
    }

    /** A saved reference back to its function (see [ApiCallable.ref]), or null when the API no longer has it. */
    fun resolve(ref: String): ApiCallable? {
        val (head, tail) = ref.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        if ('#' in head) return methods(head).firstOrNull { it.fn.name == tail }
        val module = byId[head] ?: return null
        val path = tail.split('.').filter { it.isNotEmpty() }
        if (path.isEmpty()) return null
        var typeKey = module.type
        for (seg in path.dropLast(1)) typeKey = fields(typeKey).firstOrNull { it.name == seg }?.type ?: return null
        val fn = fns(typeKey).firstOrNull { it.name == path.last() } ?: return null
        return ApiCallable(module, typeKey, path, fn)
    }

    /** The constants a module offers for picking (types.Actor.EQUIPMENT_SLOT.Helmet, input.ACTION...): tables of fields only. */
    fun constantGroups(module: ApiModule): List<Pair<String, List<ApiField>>> {
        val out = ArrayList<Pair<String, List<ApiField>>>()
        fun walk(typeKey: String, path: List<String>, depth: Int) {
            for (field in fields(typeKey)) {
                val t = type(field.type) ?: continue
                val p = path + field.name
                if (t.fns.isEmpty() && t.fields.isNotEmpty() && field.name.all { it.isUpperCase() || it == '_' || it.isDigit() }) {
                    out += (listOf(module.access).plus(p).joinToString(".")) to t.fields
                } else if (depth < 2 && t.fields.isNotEmpty()) walk(field.type, p, depth + 1)
            }
        }
        walk(module.type, emptyList(), 0)
        return out
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): LuaApi = LuaApi(json.decodeFromString(ApiCatalog.serializer(), text))

        private var loaded: LuaApi? = null

        /** The catalog shipped with the launcher, read once. */
        suspend fun load(): LuaApi {
            loaded?.let { return it }
            val bytes = org.alpha3.launcher.resources.Res.readBytes("files/lua_api.json")
            return parse(bytes.decodeToString()).also { loaded = it }
        }
    }
}

/** A type key as a reader sees it: openmw.core#GameObject -> GameObject, list<...> -> list of .... */
fun prettyType(t: String): String = when {
    t.startsWith("list<") -> "list of " + prettyType(t.removePrefix("list<").removeSuffix(">"))
    t.startsWith("map<") -> "map"
    '#' in t -> t.substringAfter('#')
    else -> t
}

/** LuaDoc markup made readable: @{#Actor} and @{openmw.core#GameObject} become their names. */
fun plainDoc(doc: String): String = doc
    .replace(Regex("""@\{[^}#]*#?([^}]*)\}""")) { it.groupValues[1].substringAfterLast('#').ifEmpty { it.value } }
    .replace(Regex("""\[([^\]]+)\]\([^)]+\)"""), "$1")
    .trim()
