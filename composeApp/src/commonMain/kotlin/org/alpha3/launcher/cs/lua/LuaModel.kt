package org.alpha3.launcher.cs.lua

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

/**
 * A Lua mod as the creator edits it. It is saved beside the scripts it generates (alpha3-luamod.json in the mod's
 * folder) so it opens again as blocks; the folder itself is an ordinary OpenMW mod: scripts/<id>/(name).lua, <id>.omwscripts
 * and l10n/<id>/en.yaml for its settings page.
 */
@Serializable
data class LuaMod(
    /** The folder, script folder and .omwscripts name: letters, digits and underscores. */
    val id: String,
    val title: String = id,
    val author: String = "",
    val description: String = "",
    val version: String = "1.0",
    val scripts: List<LuaScript> = emptyList(),
    val settings: List<ModSetting> = emptyList(),
    /** The settings page's title; null keeps the mod's title. */
    val settingsTitle: String? = null,
)

@Serializable
data class LuaScript(
    val uid: String = newUid(),
    /** The file name without .lua. */
    val name: String,
    val kind: ScriptKind,
    val handlers: List<Handler> = emptyList(),
    val variables: List<ScriptVar> = emptyList(),
    /** Functions other scripts can call, I.<interfaceName>.<name>(...). */
    val interfaceName: String? = null,
    val functions: List<ScriptFunction> = emptyList(),
    /** Set when the script was switched to hand-written Lua: the blocks are kept but the code is what is saved. */
    val code: String? = null,
)

/** A variable of the script, kept for as long as the script runs; [saved] ones are also written into the save game. */
@Serializable
data class ScriptVar(val name: String, val initial: Expr = Expr.Nil, val saved: Boolean = false)

/** A function of the script: callable from its own blocks, and from other scripts when the script has an interface. */
@Serializable
data class ScriptFunction(
    val uid: String = newUid(),
    val name: String,
    val params: List<String> = emptyList(),
    val body: List<Block> = emptyList(),
    val exported: Boolean = true,
)

/** What starts a list of blocks. */
@Serializable
data class Handler(
    val uid: String = newUid(),
    val trigger: Trigger,
    val body: List<Block> = emptyList(),
)

@Serializable
sealed class Trigger {
    /** One of the engine's handlers (onUpdate, onActivated...): its arguments are named as the docs name them. */
    @Serializable @SerialName("engine")
    data class Engine(val name: String) : Trigger()

    /** An event sent by this mod, another mod or the engine (Died, UiModeChanged...); its data is `data`. */
    @Serializable @SerialName("event")
    data class Event(val name: String) : Trigger()

    /** A key pressed (onKeyPress filtered on the key's symbol, with optional Shift/Ctrl/Alt). */
    @Serializable @SerialName("key")
    data class Key(val symbol: String, val shift: Boolean = false, val ctrl: Boolean = false, val alt: Boolean = false) : Trigger()

    /** Repeats every [seconds] of game time while the game runs (an onUpdate timer). */
    @Serializable @SerialName("every")
    data class Every(val seconds: Double) : Trigger()

    /** A console command: `lua <word> ...` in the in-game console (onConsoleCommand); the rest of the line is `args`. */
    @Serializable @SerialName("console")
    data class Console(val word: String) : Trigger()

    /** A setting of the mod changed (subscribed on the settings section). */
    @Serializable @SerialName("setting")
    data class SettingChanged(val key: String? = null) : Trigger()
}

@Serializable
sealed class Block {
    abstract val uid: String

    /** A call of an API function ([ref], see [ApiCallable.ref]), or of one of the script's own functions ("self:name"). */
    @Serializable @SerialName("call")
    data class Call(
        override val uid: String = newUid(),
        val ref: String,
        val args: List<Expr> = emptyList(),
        /** The object a method is called on. */
        val target: Expr? = null,
        /** Keep the result in a local variable of this name. */
        val saveAs: String? = null,
    ) : Block()

    @Serializable @SerialName("if")
    data class If(
        override val uid: String = newUid(),
        val condition: Expr,
        val then: List<Block> = emptyList(),
        val otherwise: List<Block> = emptyList(),
    ) : Block()

    /** For each value of a list (an inventory, nearby actors...): [item] is the value, [index] its position. */
    @Serializable @SerialName("each")
    data class ForEach(
        override val uid: String = newUid(),
        val over: Expr,
        val item: String = "item",
        val index: String = "i",
        val body: List<Block> = emptyList(),
    ) : Block()

    @Serializable @SerialName("repeat")
    data class Repeat(override val uid: String = newUid(), val times: Expr, val counter: String = "n", val body: List<Block> = emptyList()) : Block()

    /** Set a variable: one of the script's (see [ScriptVar]) or a new local one. */
    @Serializable @SerialName("set")
    data class Set(override val uid: String = newUid(), val name: String, val value: Expr, val local: Boolean = false) : Block()

    /** Send an event: to the global scripts ([to] null) or to an object's scripts. */
    @Serializable @SerialName("send")
    data class Send(
        override val uid: String = newUid(),
        val event: String,
        val to: Expr? = null,
        val data: List<Entry> = emptyList(),
    ) : Block()

    /** Run [body] after [seconds] of game time (async timer; not kept in saves). */
    @Serializable @SerialName("after")
    data class After(override val uid: String = newUid(), val seconds: Expr, val body: List<Block> = emptyList()) : Block()

    @Serializable @SerialName("return")
    data class Return(override val uid: String = newUid(), val value: Expr? = null) : Block()

    @Serializable @SerialName("stop")
    data class Stop(override val uid: String = newUid()) : Block()

    /** Any Lua, as written. */
    @Serializable @SerialName("lua")
    data class Lua(override val uid: String = newUid(), val code: String) : Block()

    @Serializable @SerialName("note")
    data class Note(override val uid: String = newUid(), val text: String) : Block()
}

@Serializable
data class Entry(val key: String, val value: Expr)

@Serializable
sealed class Expr {
    // (not exported to Objective-C: its factory would be a method named nil, which Objective-C's nil macro breaks)
    @OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)
    @kotlin.native.HiddenFromObjC
    @Serializable @SerialName("nil") data object Nil : Expr()
    @Serializable @SerialName("text") data class Text(val value: String) : Expr()
    @Serializable @SerialName("num") data class Num(val value: Double) : Expr()
    @Serializable @SerialName("bool") data class Bool(val value: Boolean) : Expr()
    /** A variable, a handler's argument or a loop's item, by name. */
    @Serializable @SerialName("var") data class Var(val name: String) : Expr()
    /** A function's result. */
    @Serializable @SerialName("call") data class Call(val ref: String, val args: List<Expr> = emptyList(), val target: Expr? = null) : Expr()
    /** value.name: a field of an object (actor.position, item.recordId) or a constant (types.Actor.EQUIPMENT_SLOT.Helmet). */
    @Serializable @SerialName("field") data class Field(val of: Expr, val name: String) : Expr()
    /** A documented value of a module, by its full name (types.Actor.EQUIPMENT_SLOT.Helmet, self, world.players). */
    @Serializable @SerialName("const") data class Const(val path: String) : Expr()
    @Serializable @SerialName("op") data class Op(val op: String, val left: Expr, val right: Expr) : Expr()
    @Serializable @SerialName("not") data class Not(val value: Expr) : Expr()
    @Serializable @SerialName("table") data class Table(val entries: List<Entry> = emptyList()) : Expr()
    /** A record of the game (an item, NPC, cell, sound...): its id, picked from the loaded content. */
    @Serializable @SerialName("record") data class Record(val kind: String, val id: String) : Expr()
    /** One of the mod's settings, read when the code runs. */
    @Serializable @SerialName("setting") data class Setting(val key: String) : Expr()
    /** The player: self in a player script, the first player elsewhere. */
    @Serializable @SerialName("player") data object Player : Expr()
    /** The object the script runs on (self.object in a local script). */
    @Serializable @SerialName("self") data object Self : Expr()
    @Serializable @SerialName("lua") data class Lua(val code: String) : Expr()
}

/** A setting on the mod's page in Options > Scripts. */
@Serializable
data class ModSetting(
    val key: String,
    val name: String,
    val description: String = "",
    /** checkbox, number, textLine, select */
    val renderer: String = "checkbox",
    val default: Expr = Expr.Bool(true),
    val min: Double? = null,
    val max: Double? = null,
    val integer: Boolean = false,
    val items: List<String> = emptyList(),
)

fun newUid(): String = buildString { repeat(10) { append("abcdefghijklmnopqrstuvwxyz0123456789"[Random.nextInt(36)]) } }

val modJson = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = false; classDiscriminator = "is" }

fun LuaMod.toJson(): String = modJson.encodeToString(LuaMod.serializer(), this)
fun luaModFromJson(text: String): LuaMod = modJson.decodeFromString(LuaMod.serializer(), text)

/** A name made usable as a Lua identifier or file name: letters, digits, underscores, not starting with a digit. */
fun identifier(text: String, fallback: String = "MyMod"): String {
    val s = text.trim().replace(Regex("[^A-Za-z0-9_]+"), "_").trim('_')
    return when {
        s.isEmpty() -> fallback
        s[0].isDigit() -> "_$s"
        else -> s
    }
}

/** The blocks of a list and of every list nested in them, depth first. */
fun List<Block>.walk(): Sequence<Block> = sequence {
    for (b in this@walk) {
        yield(b)
        when (b) {
            is Block.If -> { yieldAll(b.then.walk()); yieldAll(b.otherwise.walk()) }
            is Block.ForEach -> yieldAll(b.body.walk())
            is Block.Repeat -> yieldAll(b.body.walk())
            is Block.After -> yieldAll(b.body.walk())
            else -> {}
        }
    }
}

/** [this] with the block [uid] replaced by what [change] makes of it (null removes it), wherever it is nested. */
fun List<Block>.update(uid: String, change: (Block) -> Block?): List<Block> = mapNotNull { b ->
    if (b.uid == uid) change(b) else when (b) {
        is Block.If -> b.copy(then = b.then.update(uid, change), otherwise = b.otherwise.update(uid, change))
        is Block.ForEach -> b.copy(body = b.body.update(uid, change))
        is Block.Repeat -> b.copy(body = b.body.update(uid, change))
        is Block.After -> b.copy(body = b.body.update(uid, change))
        else -> b
    }
}

/** Where a new block goes: into a block's nested list ([slot] "then", "else" or "body") or after a block, or at the end. */
data class Place(val parent: String? = null, val slot: String = "body", val after: String? = null)

fun List<Block>.insert(place: Place, block: Block): List<Block> {
    if (place.parent == null) {
        if (place.after == null) return this + block
        val i = indexOfFirst { it.uid == place.after }
        if (i >= 0) return take(i + 1) + block + drop(i + 1)
        return map { b -> nestedInsert(b, place, block) }
    }
    return map { b -> nestedInsert(b, place, block) }
}

private fun nestedInsert(b: Block, place: Place, block: Block): Block {
    if (b.uid == place.parent && place.after == null) return when (b) {
        is Block.If -> if (place.slot == "else") b.copy(otherwise = b.otherwise + block) else b.copy(then = b.then + block)
        is Block.ForEach -> b.copy(body = b.body + block)
        is Block.Repeat -> b.copy(body = b.body + block)
        is Block.After -> b.copy(body = b.body + block)
        else -> b
    }
    val sub = place.copy(parent = if (b.uid == place.parent) null else place.parent)
    return when (b) {
        is Block.If -> b.copy(then = b.then.insert(sub, block), otherwise = b.otherwise.insert(sub, block))
        is Block.ForEach -> b.copy(body = b.body.insert(sub, block))
        is Block.Repeat -> b.copy(body = b.body.insert(sub, block))
        is Block.After -> b.copy(body = b.body.insert(sub, block))
        else -> b
    }
}

/** Moves a block one place up or down within the list that holds it. */
fun List<Block>.move(uid: String, by: Int): List<Block> {
    val i = indexOfFirst { it.uid == uid }
    if (i >= 0) {
        val j = (i + by).coerceIn(0, size - 1)
        if (i == j) return this
        val list = toMutableList()
        val b = list.removeAt(i)
        list.add(j, b)
        return list
    }
    return map { b ->
        when (b) {
            is Block.If -> b.copy(then = b.then.move(uid, by), otherwise = b.otherwise.move(uid, by))
            is Block.ForEach -> b.copy(body = b.body.move(uid, by))
            is Block.Repeat -> b.copy(body = b.body.move(uid, by))
            is Block.After -> b.copy(body = b.body.move(uid, by))
            else -> b
        }
    }
}
