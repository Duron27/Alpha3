package org.alpha3.launcher.cs.lua

/**
 * Mods to start from. Each is small, works as it is, and shows one way of doing things the large mods (the multiplayer
 * mod's HUD, its events between the world and the players, settings, console commands, saved state) are made of.
 */
class ModTemplate(val id: String, val title: String, val description: String, val teaches: String, val make: (String) -> LuaMod)

object ModTemplates {
    private fun call(ref: String, vararg args: Expr, target: Expr? = null, saveAs: String? = null) =
        Block.Call(ref = ref, args = args.toList(), target = target, saveAs = saveAs)

    private fun message(text: Expr) = call("openmw.ui:showMessage", text)
    private fun text(s: String) = Expr.Text(s)
    private fun v(name: String) = Expr.Var(name)
    private fun join(vararg parts: Expr): Expr = parts.reduce { a, b -> Expr.Op("..", a, b) }

    val all: List<ModTemplate> = listOf(
        ModTemplate(
            "empty", "Empty mod", "A mod with one player script and nothing in it yet.",
            "Start from nothing",
        ) { id -> LuaMod(id = id, title = id, scripts = listOf(LuaScript(name = "player", kind = ScriptKind.PLAYER))) },

        ModTemplate(
            "hello", "Hello on start", "Shows a message when a game starts or is loaded.",
            "A player script, a handler, a message",
        ) { id ->
            LuaMod(
                id = id, title = id,
                scripts = listOf(LuaScript(name = "player", kind = ScriptKind.PLAYER, handlers = listOf(
                    Handler(trigger = Trigger.Engine("onActive"), body = listOf(message(text("Hello from $id!")))),
                ))),
            )
        },

        ModTemplate(
            "healkey", "Heal on a key", "Press H to restore 50 health, with a message. The amount is a setting.",
            "Keys, built-in events (ModifyStat), a settings page",
        ) { id ->
            LuaMod(
                id = id, title = id,
                settings = listOf(ModSetting("amount", "Health restored", "How much health the H key gives back", "number", Expr.Num(50.0), min = 1.0, max = 1000.0, integer = true)),
                scripts = listOf(LuaScript(name = "player", kind = ScriptKind.PLAYER, handlers = listOf(
                    Handler(trigger = Trigger.Key("h"), body = listOf(
                        Block.Send(event = "ModifyStat", to = Expr.Player, data = listOf(Entry("stat", text("health")), Entry("amount", Expr.Setting("amount")))),
                        message(join(text("Healed "), Expr.Call("lua.global:tostring", listOf(Expr.Setting("amount"))))),
                    )),
                ))),
            )
        },

        ModTemplate(
            "gift", "Gift on activation", "Activating a chosen object (a statue, a shrine...) gives the player an item, once.",
            "A world script, conditions, creating objects, a saved variable",
        ) { id ->
            LuaMod(
                id = id, title = id,
                scripts = listOf(LuaScript(
                    name = "world", kind = ScriptKind.GLOBAL,
                    variables = listOf(ScriptVar("given", Expr.Bool(false), saved = true)),
                    handlers = listOf(Handler(trigger = Trigger.Engine("onActivate"), body = listOf(
                        Block.If(
                            condition = Expr.Op("and", Expr.Op("==", Expr.Field(v("object"), "recordId"), Expr.Record("ACTI", "furn_shrine_tribunal_cure_01")), Expr.Not(v("given"))),
                            then = listOf(
                                call("openmw.world:createObject", Expr.Record("MISC", "gold_001"), Expr.Num(100.0), saveAs = "gift"),
                                call("openmw.core#GameObject:moveInto", Expr.Call("openmw.types:Actor.inventory", listOf(v("actor"))), target = v("gift")),
                                Block.Set(name = "given", value = Expr.Bool(true)),
                                Block.Send(event = "ShowMessage", to = v("actor"), data = listOf(Entry("message", text("The shrine gives you 100 gold.")))),
                            ),
                        ),
                    ))),
                )),
            )
        },

        ModTemplate(
            "reward", "Bounty for kills", "Every NPC or creature that dies gives the player gold. NPC scripts tell the world script, which pays.",
            "Scripts on every actor, sending events between scripts",
        ) { id ->
            LuaMod(
                id = id, title = id,
                settings = listOf(ModSetting("gold", "Gold per kill", "", "number", Expr.Num(10.0), min = 0.0, max = 10000.0, integer = true)),
                scripts = listOf(
                    LuaScript(name = "actor", kind = ScriptKind.ACTOR, handlers = listOf(
                        Handler(trigger = Trigger.Event("Died"), body = listOf(
                            Block.Send(event = "${id}_killed", data = listOf(Entry("actor", Expr.Self))),
                        )),
                    )),
                    LuaScript(name = "world", kind = ScriptKind.GLOBAL, handlers = listOf(
                        Handler(trigger = Trigger.Event("${id}_killed"), body = listOf(
                            Block.If(condition = Expr.Op(">", Expr.Setting("gold"), Expr.Num(0.0)), then = listOf(
                                call("openmw.world:createObject", Expr.Record("MISC", "gold_001"), Expr.Setting("gold"), saveAs = "gold"),
                                call("openmw.core#GameObject:moveInto", Expr.Call("openmw.types:Actor.inventory", listOf(Expr.Player)), target = v("gold")),
                                Block.Send(event = "ShowMessage", to = Expr.Player, data = listOf(Entry("message", join(text("Bounty: "), Expr.Call("lua.global:tostring", listOf(Expr.Setting("gold"))), text(" gold"))))),
                            )),
                        )),
                    )),
                ),
            )
        },

        ModTemplate(
            "hud", "HUD text", "A line of text in the corner of the screen showing the player's health, updated every half second.",
            "UI elements (like the multiplayer HUD), timers, stats",
        ) { id ->
            LuaMod(
                id = id, title = id,
                scripts = listOf(LuaScript(
                    name = "hud", kind = ScriptKind.PLAYER,
                    variables = listOf(ScriptVar("hud", Expr.Nil)),
                    handlers = listOf(
                        Handler(trigger = Trigger.Engine("onActive"), body = listOf(
                            Block.Lua(code = """
                                hud = ui.create {
                                    layer = 'HUD',
                                    type = ui.TYPE.Text,
                                    props = {
                                        relativePosition = util.vector2(0.02, 0.05),
                                        textSize = 18,
                                        textColor = util.color.rgb(1, 0.85, 0.5),
                                        textShadow = true,
                                        text = '',
                                    },
                                }
                            """.trimIndent()),
                        )),
                        Handler(trigger = Trigger.Every(0.5), body = listOf(
                            Block.If(condition = v("hud"), then = listOf(
                                Block.Set(name = "health", local = true, value = Expr.Call("openmw.types:Actor.stats.dynamic.health", listOf(Expr.Player))),
                                Block.Lua(code = "hud.layout.props.text = string.format('Health %d / %d', health.current, health.base)\nhud:update()"),
                            )),
                        )),
                        Handler(trigger = Trigger.Engine("onInactive"), body = listOf(
                            Block.If(condition = v("hud"), then = listOf(Block.Lua(code = "hud:destroy()\nhud = nil"))),
                        )),
                    ),
                )),
            )
        },

        ModTemplate(
            "console", "Console commands", "Type `lua where` in the console to print where you are, `lua heal` to heal fully.",
            "Console commands, printing to the console, reading the player's cell",
        ) { id ->
            LuaMod(
                id = id, title = id,
                scripts = listOf(LuaScript(name = "player", kind = ScriptKind.PLAYER, handlers = listOf(
                    Handler(trigger = Trigger.Console("where"), body = listOf(
                        call("openmw.ui:printToConsole", join(text("You are in "), Expr.Lua("(self.cell.name ~= '' and self.cell.name or 'the wilderness')"),
                            text(" at "), Expr.Lua("tostring(self.position)")), Expr.Const("ui.CONSOLE_COLOR.Success")),
                    )),
                    Handler(trigger = Trigger.Console("heal"), body = listOf(
                        Block.Set(name = "health", local = true, value = Expr.Call("openmw.types:Actor.stats.dynamic.health", listOf(Expr.Player))),
                        Block.Lua(code = "health.current = health.base"),
                        call("openmw.ui:printToConsole", text("Healed"), Expr.Const("ui.CONSOLE_COLOR.Success")),
                    )),
                ))),
            )
        },

        ModTemplate(
            "reminder", "Save reminder", "Reminds you to save every few minutes of play. The interval and the switch are settings.",
            "Timers, settings (checkbox and number)",
        ) { id ->
            LuaMod(
                id = id, title = id,
                settings = listOf(
                    ModSetting("enabled", "Remind me", "", "checkbox", Expr.Bool(true)),
                    ModSetting("minutes", "Every (minutes)", "", "number", Expr.Num(10.0), min = 1.0, max = 120.0, integer = true),
                ),
                scripts = listOf(LuaScript(
                    name = "player", kind = ScriptKind.PLAYER,
                    variables = listOf(ScriptVar("played", Expr.Num(0.0))),
                    handlers = listOf(Handler(trigger = Trigger.Every(60.0), body = listOf(
                        Block.Set(name = "played", value = Expr.Op("+", v("played"), Expr.Num(1.0))),
                        Block.If(condition = Expr.Op("and", Expr.Setting("enabled"), Expr.Op(">=", v("played"), Expr.Setting("minutes"))), then = listOf(
                            Block.Set(name = "played", value = Expr.Num(0.0)),
                            message(text("Remember to save your game.")),
                        )),
                    ))),
                )),
            )
        },

        ModTemplate(
            "nearby", "Who is around", "Press N to count the NPCs and creatures near you, and name the closest.",
            "Lists of nearby objects, loops, distances",
        ) { id ->
            LuaMod(
                id = id, title = id,
                scripts = listOf(LuaScript(name = "player", kind = ScriptKind.PLAYER, handlers = listOf(
                    Handler(trigger = Trigger.Key("n"), body = listOf(
                        Block.Set(name = "count", local = true, value = Expr.Num(0.0)),
                        Block.Set(name = "closest", local = true, value = Expr.Nil),
                        Block.Set(name = "best", local = true, value = Expr.Num(1e9)),
                        Block.ForEach(over = Expr.Const("nearby.actors"), item = "actor", body = listOf(
                            Block.If(condition = Expr.Op("~=", v("actor"), Expr.Player), then = listOf(
                                Block.Set(name = "count", value = Expr.Op("+", v("count"), Expr.Num(1.0))),
                                Block.Set(name = "d", local = true, value = Expr.Lua("(actor.position - self.position):length()")),
                                Block.If(condition = Expr.Op("<", v("d"), v("best")), then = listOf(
                                    Block.Set(name = "best", value = v("d")),
                                    Block.Set(name = "closest", value = v("actor")),
                                )),
                            )),
                        )),
                        Block.If(condition = v("closest"), then = listOf(
                            message(join(Expr.Call("lua.global:tostring", listOf(v("count"))), text(" around you; the closest is "), Expr.Field(Expr.Lua("closest.type.record(closest)"), "name"))),
                        ), otherwise = listOf(message(text("Nobody is around.")))),
                    )),
                ))),
            )
        },

        ModTemplate(
            "greeter", "NPC remarks", "NPCs say a line (as a message) when you activate them, with their name.",
            "Scripts on every NPC, the object a script runs on, records",
        ) { id ->
            LuaMod(
                id = id, title = id,
                scripts = listOf(LuaScript(name = "npc", kind = ScriptKind.NPC, handlers = listOf(
                    Handler(trigger = Trigger.Engine("onActivated"), body = listOf(
                        Block.If(condition = Expr.Op("==", v("actor"), Expr.Player), then = listOf(
                            Block.Send(event = "ShowMessage", to = v("actor"), data = listOf(Entry("message",
                                join(Expr.Field(Expr.Call("openmw.types:NPC.record", listOf(Expr.Self)), "name"), text(": Well met, outlander."))))),
                        )),
                    )),
                ))),
            )
        },

        ModTemplate(
            "counter", "Kill counter", "Counts the player's kills, keeps the count in the save game, and tells it with `lua kills`.",
            "Variables kept in saves, events from many scripts to one",
        ) { id ->
            LuaMod(
                id = id, title = id,
                scripts = listOf(
                    LuaScript(name = "actor", kind = ScriptKind.ACTOR, handlers = listOf(
                        Handler(trigger = Trigger.Event("Died"), body = listOf(
                            Block.Send(event = "${id}_kill", to = Expr.Player),
                        )),
                    )),
                    LuaScript(
                        name = "player", kind = ScriptKind.PLAYER,
                        variables = listOf(ScriptVar("kills", Expr.Num(0.0), saved = true)),
                        handlers = listOf(
                            Handler(trigger = Trigger.Event("${id}_kill"), body = listOf(
                                Block.Set(name = "kills", value = Expr.Op("+", v("kills"), Expr.Num(1.0))),
                            )),
                            Handler(trigger = Trigger.Console("kills"), body = listOf(
                                call("openmw.ui:printToConsole", join(text("Kills: "), Expr.Call("lua.global:tostring", listOf(v("kills")))), Expr.Const("ui.CONSOLE_COLOR.Info")),
                            )),
                        ),
                    ),
                ),
            )
        },

        ModTemplate(
            "teleport", "Recall point", "Shift+M marks where you stand, M takes you back. The player script asks the world script to move you.",
            "Events from the player to the world, for what only world scripts can do",
        ) { id ->
            LuaMod(
                id = id, title = id,
                scripts = listOf(
                    LuaScript(
                        name = "player", kind = ScriptKind.PLAYER,
                        variables = listOf(ScriptVar("markCell", Expr.Nil, saved = true), ScriptVar("markPos", Expr.Nil, saved = true)),
                        handlers = listOf(
                            Handler(trigger = Trigger.Key("m", shift = true), body = listOf(
                                Block.Set(name = "markCell", value = Expr.Lua("self.cell.id")),
                                Block.Set(name = "markPos", value = Expr.Lua("self.position")),
                                message(text("Recall point marked.")),
                            )),
                            Handler(trigger = Trigger.Key("m"), body = listOf(
                                Block.If(condition = Expr.Lua("markPos and not key.withShift"), then = listOf(
                                    Block.Send(event = "${id}_recall", data = listOf(Entry("player", Expr.Player), Entry("cell", v("markCell")), Entry("position", v("markPos")))),
                                )),
                            )),
                        ),
                    ),
                    LuaScript(name = "world", kind = ScriptKind.GLOBAL, handlers = listOf(
                        Handler(trigger = Trigger.Event("${id}_recall"), body = listOf(
                            call("openmw.core#GameObject:teleport", Expr.Call("openmw.world:getCellById", listOf(Expr.Field(v("data"), "cell"))),
                                Expr.Field(v("data"), "position"), target = Expr.Field(v("data"), "player")),
                        )),
                    )),
                ),
            )
        },

        ModTemplate(
            "menu", "Main menu note", "A menu script that prints a line to the console when a game starts or ends.",
            "Menu scripts, the game's state",
        ) { id ->
            LuaMod(
                id = id, title = id,
                scripts = listOf(LuaScript(name = "menu", kind = ScriptKind.MENU, handlers = listOf(
                    Handler(trigger = Trigger.Engine("onStateChanged"), body = listOf(
                        call("openmw.ui:printToConsole", join(text("Game state: "), Expr.Call("lua.global:tostring", listOf(Expr.Call("openmw.menu:getState")))), Expr.Const("ui.CONSOLE_COLOR.Info")),
                    )),
                ))),
            )
        },
    )
}
