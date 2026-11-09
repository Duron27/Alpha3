package org.alpha3.launcher.cs

import org.alpha3.launcher.cs.lua.Block
import org.alpha3.launcher.cs.lua.Expr
import org.alpha3.launcher.cs.lua.Handler
import org.alpha3.launcher.cs.lua.LuaApi
import org.alpha3.launcher.cs.lua.LuaCheck
import org.alpha3.launcher.cs.lua.LuaGen
import org.alpha3.launcher.cs.lua.LuaMod
import org.alpha3.launcher.cs.lua.LuaParser
import org.alpha3.launcher.cs.lua.LuaScript
import org.alpha3.launcher.cs.lua.LuaSyntaxError
import org.alpha3.launcher.cs.lua.ModTemplates
import org.alpha3.launcher.cs.lua.ScriptKind
import org.alpha3.launcher.cs.lua.Severity
import org.alpha3.launcher.cs.lua.Trigger
import org.alpha3.launcher.cs.lua.luaModFromJson
import org.alpha3.launcher.cs.lua.toJson
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The Lua mod creator's parser, generator and checker: the parser must read every script of the multiplayer mod (and
 * the engine's own), generated mods must pass `luac -p` (when luac is installed) and the checker must find the
 * mistakes it is meant to.
 */
class LuaCreatorTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    private val api by lazy { LuaApi.parse(File(root, "composeApp/src/commonMain/composeResources/files/lua_api.json").readText()) }

    private fun luaFiles(dir: String) = File(root, dir).walkTopDown().filter { it.isFile && it.extension == "lua" }.toList()

    @Test
    fun parsesLargeMods() {
        val files = luaFiles("cpp/openmw-upstream/support/multiplayer/tree/multiplayer") +
            luaFiles("androidApp/src/main/assets/libopenmw-upstream/resources/vfs/scripts")
        assertTrue(files.size > 30, "expected the multiplayer mod's and the engine's scripts, found ${files.size}")
        for (f in files) {
            try {
                LuaParser.parse(f.readText())
            } catch (e: LuaSyntaxError) {
                fail("${f.path}:${e.line}:${e.col}: ${e.message}")
            }
        }
    }

    @Test
    fun syntaxErrorsLikeLuac() {
        val e = runCatching { LuaParser.parse("local x = 1\nif x then\n  print(x)\n") }.exceptionOrNull() as LuaSyntaxError
        assertEquals(4, e.line)
        val e2 = runCatching { LuaParser.parse("local t = { a = 1, b = }") }.exceptionOrNull() as LuaSyntaxError
        assertEquals(1, e2.line)
    }

    @Test
    fun checkerFindsMistakes() {
        val check = LuaCheck(api)
        val code = """
            local world = require('openmw.world')
            local core = require('openmw.core')
            local ui = require('openmw.ui')
            local function f()
                core.sendGlobalEvnt('X')
                ui.showMessage('hi', 1, 2, 3)
                prnt('typo')
            end
            return { engineHandlers = { onUpdte = f, onKeyPress = f } }
        """.trimIndent()
        val problems = check.checkScript("a.lua", ScriptKind.GLOBAL, code)
        val text = problems.joinToString("\n") { "${it.severity} ${it.line}: ${it.message}" }
        println(text)
        assertTrue(problems.any { it.message.contains("sendGlobalEvnt") && it.message.contains("sendGlobalEvent") }, text)
        assertTrue(problems.any { it.message.contains("openmw.ui") }, text)
        assertTrue(problems.any { it.message.contains("onUpdte") }, text)
        assertTrue(problems.any { it.message.contains("onKeyPress") && it.severity == Severity.Error }, text)
        assertTrue(problems.any { it.message.contains("prnt") }, text)
        assertTrue(problems.none { it.message.contains("openmw.world") }, text)
    }

    @Test
    fun multiplayerModChecksClean() {
        // the large mods are correct: the checker must not report errors in them (warnings about globals are fine)
        val check = LuaCheck(api)
        val dir = File(root, "cpp/openmw-upstream/support/multiplayer/tree/multiplayer/mod")
        val kinds = File(dir, "mp.omwscripts").readLines().mapNotNull { l ->
            val (tag, path) = l.split(":").map { it.trim() }.takeIf { it.size == 2 } ?: return@mapNotNull null
            path to when (tag) { "GLOBAL" -> ScriptKind.GLOBAL; "PLAYER" -> ScriptKind.PLAYER; "MENU" -> ScriptKind.MENU; else -> ScriptKind.CUSTOM }
        }
        val errors = kinds.flatMap { (path, kind) -> check.checkScript(path, kind, File(dir, path).readText()) }.filter { it.severity == Severity.Error }
        println(errors.joinToString("\n") { "${it.file}:${it.line}: ${it.message}" })
        assertTrue(errors.size <= 3, "unexpected errors in the multiplayer mod:\n" + errors.joinToString("\n") { "${it.file}:${it.line}: ${it.message}" })
    }

    @Test
    fun templatesGenerateValidLua() {
        val out = File(System.getProperty("java.io.tmpdir"), "alpha3-luamod-test").apply { deleteRecursively(); mkdirs() }
        val luac = listOf("/usr/bin/luajit", "/usr/bin/luac").firstOrNull { File(it).canExecute() }
        val check = LuaCheck(api)
        for (t in ModTemplates.all) {
            val mod = luaModFromJson(t.make("Test_${t.id}").toJson())  // also round-trips the saved form
            val gen = LuaGen(api, mod).generate()
            val kinds = gen.files.keys.associateWith { p -> mod.scripts.firstOrNull { LuaGen(api, mod).scriptPath(it) == p }?.kind ?: guess(gen.files["${mod.id}.omwscripts"]!!, p) }
                .filterValues { it != null }.mapValues { it.value!! }
            val problems = check.checkMod(gen, kinds)
            val errors = problems.filter { it.severity == Severity.Error }
            for ((path, text) in gen.files) {
                val f = File(out, "${t.id}/$path").apply { parentFile.mkdirs(); writeText(text) }
                if (luac != null && path.endsWith(".lua")) {
                    val args = if (luac.endsWith("luajit")) listOf(luac, "-bl", f.path) else listOf(luac, "-p", f.path)
                    val p = ProcessBuilder(args).redirectErrorStream(true).start()
                    val msg = p.inputStream.bufferedReader().readText()
                    if (p.waitFor() != 0) fail("${t.id} $path does not compile:\n$msg\n$text")
                }
            }
            assertTrue(errors.isEmpty(), "${t.id}: " + errors.joinToString("\n") { "${it.file}:${it.line}: ${it.message}" } + "\n" + gen.files.entries.joinToString("\n") { "== ${it.key}\n${it.value}" })
            println("${t.id}: ${gen.files.keys} ${problems.size} notes ${gen.notes}")
        }
    }

    private fun guess(omwscripts: String, path: String): ScriptKind? = omwscripts.lines().firstOrNull { it.endsWith(": $path") }?.substringBefore(":")?.let { tag ->
        ScriptKind.entries.firstOrNull { it.tag == tag }
    }

    @Test
    fun routesWorldCallsFromPlayer() {
        val mod = LuaMod(
            id = "RouteTest",
            scripts = listOf(
                LuaScript(
                    name = "player", kind = ScriptKind.PLAYER,
                    handlers = listOf(
                        Handler(trigger = Trigger.Key("k"), body = listOf(
                            Block.Call(ref = "openmw.core#GameObject:teleport", target = Expr.Player, args = listOf(Expr.Text(""), Expr.Lua("self.position"))),
                            Block.Call(ref = "openmw.ui:showMessage", args = listOf(Expr.Text("Teleported"))),
                        )),
                    ),
                ),
            ),
        )
        val gen = LuaGen(api, mod).generate()
        val player = gen.files["scripts/RouteTest/player.lua"]!!
        val world = gen.files["scripts/RouteTest/world.lua"]!!
        println(player); println(world)
        assertTrue("core.sendGlobalEvent('RouteTest_teleport_" in player)
        assertTrue("d.target:teleport(d.a1, d.a2)" in world)
        assertTrue("ui.showMessage('Teleported')" in player)
        assertTrue("GLOBAL: scripts/RouteTest/world.lua" in gen.files["RouteTest.omwscripts"]!!)
    }
}
