package org.alpha3.launcher.cs.lua.ui

import org.alpha3.launcher.cs.lua.ApiModule
import org.alpha3.launcher.cs.lua.Block
import org.alpha3.launcher.cs.lua.Expr
import org.alpha3.launcher.cs.lua.LuaApi
import org.alpha3.launcher.cs.lua.num

/** Plain names for the API's modules, so the menus read as what they are for. */
internal fun moduleLabel(m: ApiModule): String = when (m.id) {
    "openmw.core" -> "Game"
    "openmw.world" -> "World"
    "openmw.types" -> "Actors & items"
    "openmw.nearby" -> "Around this object"
    "openmw.self" -> "This object"
    "openmw.ui" -> "Screen"
    "openmw.ambient" -> "Sounds & music"
    "openmw.camera" -> "Camera"
    "openmw.input" -> "Keys & controls"
    "openmw.storage" -> "Saved data"
    "openmw.util" -> "Vectors & colours"
    "openmw.async" -> "Timers"
    "openmw.animation" -> "Animations"
    "openmw.vfs" -> "Game files"
    "openmw.markup" -> "YAML"
    "openmw.debug" -> "Debug"
    "openmw.postprocessing" -> "Shaders"
    "openmw.menu" -> "Main menu"
    "openmw.content" -> "Content records"
    "openmw.net" -> "Network"
    "openmw.modstore" -> "Mod store"
    "openmw.interfaces" -> "Interfaces"
    "lua.math" -> "Maths"
    "lua.string" -> "Text"
    "lua.table" -> "Lists"
    "lua.global" -> "Lua basics"
    "lua.coroutine" -> "Coroutines"
    else -> when (m.kind) {
        "interface" -> m.name
        "aux" -> "Helpers: " + m.name
        else -> m.name
    }
}

internal fun moduleHint(m: ApiModule): String = when (m.id) {
    "openmw.core" -> "Time, events, sounds, magic, game records"
    "openmw.world" -> "Create, find and change objects and cells"
    "openmw.types" -> "Stats, inventories, equipment, records of NPCs and items"
    "openmw.nearby" -> "Actors, items and doors near, rays, paths"
    "openmw.self" -> "The object this script is on"
    "openmw.ui" -> "Messages, the console, windows and HUD elements"
    "openmw.ambient" -> "Sounds and music for the player"
    "openmw.input" -> "Keys, actions, the mouse and controllers"
    "openmw.storage" -> "Data that lasts: settings, saves"
    "openmw.util" -> "Vectors, colours, transforms, maths"
    "openmw.async" -> "Timers and callbacks"
    "openmw.net" -> "Sockets (multiplayer build)"
    "openmw.modstore" -> "Client mods (multiplayer build)"
    else -> m.summary.ifBlank { m.doc.lineSequence().firstOrNull().orEmpty() }.take(80)
}

/** A sentence for a block, as the editor lists it. */
internal fun describe(b: Block, api: LuaApi?): String = when (b) {
    is Block.Call -> {
        val c = api?.resolve(b.ref)
        val name = c?.let { friendlyCall(it.title) } ?: b.ref.substringAfter(':')
        val args = b.args.joinToString(", ") { exprText(it) }
        val on = b.target?.let { exprText(it) + ": " } ?: ""
        val keep = b.saveAs?.let { " → $it" } ?: ""
        when (b.ref) {
            "openmw.ui:showMessage" -> "Show message ${b.args.firstOrNull()?.let { exprText(it) } ?: ""}"
            "openmw.ui:printToConsole" -> "Print to console ${b.args.firstOrNull()?.let { exprText(it) } ?: ""}"
            else -> "$on$name($args)$keep"
        }
    }
    is Block.If -> "If ${exprText(b.condition)}"
    is Block.ForEach -> "For each ${b.item} in ${exprText(b.over)}"
    is Block.Repeat -> "Repeat ${exprText(b.times)} times"
    is Block.Set -> "${if (b.local) "Let" else "Set"} ${b.name} = ${exprText(b.value)}"
    is Block.Send -> "Send \"${b.event}\" to ${b.to?.let { exprText(it) } ?: "the world scripts"}" +
        if (b.data.isEmpty()) "" else " with " + b.data.joinToString(", ") { "${it.key} = ${exprText(it.value)}" }
    is Block.After -> "After ${exprText(b.seconds)} seconds"
    is Block.Return -> "Return" + (b.value?.let { " " + exprText(it) } ?: "")
    is Block.Stop -> "Stop here"
    is Block.Lua -> b.code.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.let { if (b.code.lines().size > 1) "$it …" else it } ?: "Lua"
    is Block.Note -> b.text
}

private fun friendlyCall(title: String) = title

/** An expression as it reads in a block. */
internal fun exprText(e: Expr): String = when (e) {
    Expr.Nil -> "nothing"
    is Expr.Text -> "“${e.value}”"
    is Expr.Num -> num(e.value)
    is Expr.Bool -> if (e.value) "yes" else "no"
    is Expr.Var -> e.name
    is Expr.Call -> (e.target?.let { exprText(it) + ":" } ?: "") + e.ref.substringAfter(':').substringAfterLast('.') + "(" + e.args.joinToString(", ") { exprText(it) } + ")"
    is Expr.Field -> "${exprText(e.of)}.${e.name}"
    is Expr.Const -> e.path
    is Expr.Op -> "${exprText(e.left)} ${opText(e.op)} ${exprText(e.right)}"
    is Expr.Not -> "not ${exprText(e.value)}"
    is Expr.Table -> "{" + e.entries.joinToString(", ") { "${it.key} = ${exprText(it.value)}" } + "}"
    is Expr.Record -> "‘${e.id}’"
    is Expr.Setting -> "setting ${e.key}"
    Expr.Player -> "the player"
    Expr.Self -> "this object"
    is Expr.Lua -> e.code
}

internal fun opText(op: String) = when (op) {
    "==" -> "="
    "~=" -> "≠"
    "<=" -> "≤"
    ">=" -> "≥"
    ".." -> "joined with"
    else -> op
}

internal val OPERATORS = listOf("==", "~=", "<", ">", "<=", ">=", "and", "or", "+", "-", "*", "/", "..")
