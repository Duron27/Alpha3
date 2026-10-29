-- Main menu "Multiplayer" screen (the button itself is built into the engine's main menu).
-- Lets you set the server, then start a new character or load a save; the connection is made
-- automatically once the game is running. Settings go to the permanent player storage section
-- 'MpConnect', which player.lua forwards to the global script.
local ui = require('openmw.ui')
local util = require('openmw.util')
local menu = require('openmw.menu')
local input = require('openmw.input')
local storage = require('openmw.storage')
local async = require('openmw.async')
local I = require('openmw.interfaces')

local V2, rgb = util.vector2, util.color.rgb
local WHITE = ui.texture { path = 'white' }
local section = storage.playerSection('MpConnect')
-- 'Active' means: the game being loaded was started from this screen. Never carry it over from an earlier run.
section:set('Active', false)

local DEFAULT_HOST = '192.53.121.201' -- the public server; used until the player joins another one
local W, H = 640, 590
local TEXT_SIZE = 18
local C = {
    text = rgb(0.92, 0.92, 0.92), head = rgb(1, 0.85, 0.35), dim = rgb(0.65, 0.65, 0.65),
    button = rgb(1, 1, 1), err = rgb(1, 0.5, 0.4), ok = rgb(0.5, 0.95, 0.55), panel = rgb(0.1, 0.08, 0.06),
    field = rgb(0.03, 0.03, 0.03),
}

-- Settings page: live list of connected players with their latency. Filled by player.lua.
-- Settings page: how to reach this game when hosting (set by player.lua from the host's addresses).
I.Settings.registerRenderer('mpAddress', function(text)
    local rows = {}
    for line in tostring(text or ''):gmatch('[^\n]+') do
        rows[#rows + 1] = { template = I.MWUI.templates.textNormal, props = { text = line } }
    end
    if #rows == 0 then rows[1] = { template = I.MWUI.templates.textNormal, props = { text = 'Not hosting.', textColor = C.dim } } end
    return { type = ui.TYPE.Flex, content = ui.content(rows) }
end)

I.Settings.registerRenderer('mpPlayers', function(list)
    local rows = {}
    if type(list) ~= 'table' or #list == 0 then
        rows[1] = { template = I.MWUI.templates.textNormal, props = { text = 'Not connected.', textColor = C.dim } }
    else
        for _, pl in ipairs(list) do
            rows[#rows + 1] = {
                template = I.MWUI.templates.textNormal,
                props = { text = pl.name .. (pl.level and (' (level ' .. pl.level .. ')') or '') .. (pl.host and ' (host)' or '') .. (pl.muted and ' (muted)' or '') .. '    ' .. (pl.ping and (pl.ping .. ' ms') or '-') },
            }
        end
    end
    return { type = ui.TYPE.Flex, content = ui.content(rows) }
end)

local el
local view = 'main' -- 'main' | 'load'
local hostBuf, portBuf, passBuf = nil, nil, nil
local mode = 'join' -- 'join' someone else's server | 'host' one from this game
local message, messageColor = nil, C.dim

local function text(props, events)
    props.textSize = props.textSize or TEXT_SIZE
    return { template = I.MWUI.templates.textNormal, props = props, events = events }
end

local function close()
    if el then el:destroy() el = nil end
    view = 'main'
end

local render

local function apply(bot)
    local host = (hostBuf or ''):gsub('^%s+', ''):gsub('%s+$', '')
    local port = tonumber(portBuf)
    if mode == 'join' and host == '' then message, messageColor = 'Enter the server address.', C.err render() return false end
    if not port or port < 1 or port > 65535 or port ~= math.floor(port) then
        message, messageColor = 'The port must be a number from 1 to 65535.', C.err render() return false
    end
    if mode == 'join' then -- remember the servers we connect to
        local list = { { host = host, port = port } }
        for _, e in ipairs(section:get('Recent') or {}) do
            if #list < 6 and not (e.host == host and e.port == port) then list[#list + 1] = { host = e.host, port = e.port } end
        end
        section:set('Recent', list)
    end
    section:set('Mode', mode)
    section:set('Password', passBuf or '')
    section:set('Bot', bot and true or false)
    if mode == 'join' then section:set('Host', host) end
    section:set('Port', port)
    return true
end

local function button(x, y, label, fn, color)
    return text({ position = V2(x, y), text = '[ ' .. label .. ' ]', textColor = color or C.button, textSize = 20 },
        { mouseClick = async:callback(fn) })
end

local function field(x, y, w, initial, onChange)
    return {
        type = ui.TYPE.Widget, props = { position = V2(x, y), size = V2(w, 28) },
        content = ui.content {
            { type = ui.TYPE.Image, props = { resource = WHITE, relativeSize = V2(1, 1), color = C.field, alpha = 0.9 } },
            {
                type = ui.TYPE.TextEdit,
                props = { position = V2(6, 3), size = V2(w - 12, 22), textSize = TEXT_SIZE, textColor = rgb(1, 1, 1),
                    multiline = false, text = initial },
                events = { textChanged = async:callback(onChange) },
            },
        },
    }
end

local function saveList()
    local list = {}
    local ok, all = pcall(menu.getAllSaves)
    if not ok then return list end
    for dir, slots in pairs(all) do
        for slot, info in pairs(slots) do
            list[#list + 1] = { dir = dir, slot = slot, info = info }
        end
    end
    table.sort(list, function(a, b) return (a.info.creationTime or 0) > (b.info.creationTime or 0) end)
    return list
end

render = function()
    local content = {
        { type = ui.TYPE.Image, props = { resource = WHITE, relativeSize = V2(1, 1), color = C.panel, alpha = 0.96 } },
        text({ position = V2(24, 16), text = 'Multiplayer', textColor = C.head, textSize = 28 }),
    }
    local running = menu.getState() == menu.STATE.Running
    if view == 'main' then
        content[#content + 1] = text({ position = V2(24, 70), text = 'Mode', textColor = C.text })
        local function modeButton(x, label, value)
            local selected = mode == value
            content[#content + 1] = button(x, 66, label, function()
                if running then
                    message, messageColor = 'Return to the main menu to switch between hosting and joining.', C.err
                    render()
                elseif mode ~= value then
                    mode, message = value, nil
                    render()
                end
            end, selected and C.ok or C.dim)
        end
        modeButton(140, 'Join a server', 'join')
        modeButton(340, 'Host a game', 'host')
        if mode == 'join' then
            content[#content + 1] = text({ position = V2(24, 118), text = 'Server', textColor = C.text })
            content[#content + 1] = field(140, 114, 300, hostBuf, function(t) hostBuf = t end)
            content[#content + 1] = button(450, 112, 'Recent', function() view = 'recent' render() end, C.dim)
        end
        content[#content + 1] = text({ position = V2(24, 160), text = 'Port', textColor = C.text })
        content[#content + 1] = field(140, 156, 120, portBuf, function(t) portBuf = t end)
        content[#content + 1] = text({ position = V2(24, 202), text = mode == 'host' and 'Password' or 'Password', textColor = C.text })
        content[#content + 1] = field(140, 198, 300, passBuf, function(t) passBuf = t end)
        content[#content + 1] = text({ position = V2(450, 202), text = mode == 'host' and '(optional)' or '(if the host set one)', textColor = C.dim, textSize = 15 })
        local info = {
            "Your character's name, look and gear are shared with other players automatically.",
            'Everyone keeps their own world; you only share a world state inside a party.',
        }
        if mode == 'host' then
            info[#info + 1] = 'Hosting starts when your game does and shows the internet address friends join with'
            info[#info + 1] = '(forward the TCP port on your router; this contacts api.ipify.org once).'
            info[#info + 1] = 'While hosting you cannot join another server.'
        else
            info[#info + 1] = 'You can be connected to one host at a time.'
        end
        for i, line in ipairs(info) do
            content[#content + 1] = text({ position = V2(24, 246 + (i - 1) * 22), text = line, textColor = C.dim, textSize = 15 })
        end
        if message then content[#content + 1] = text({ position = V2(24, 362), text = message, textColor = messageColor }) end
        if running then
            content[#content + 1] = button(24, 395, 'Apply and reconnect', function()
                if apply() then message, messageColor = 'Saved. Reconnecting in the game.', C.ok render() end
            end)
            content[#content + 1] = button(24, 435, 'Add a test player', function()
                if mode ~= 'host' then
                    message, messageColor = 'The test player needs hosting: return to the main menu and choose Host a game.', C.err
                    render()
                elseif apply(true) then
                    message, messageColor = 'A test player will appear near you.', C.ok render()
                end
            end)
        else
            content[#content + 1] = button(24, 395, 'New character', function()
                if apply() then section:set('Active', true) close() menu.newGame() end
            end)
            content[#content + 1] = button(24, 435, 'Load character', function()
                if apply() then view = 'load' render() end
            end)
            content[#content + 1] = button(24, 475, 'Test: load a save with a test player', function()
                mode = 'host'
                if apply(true) then view = 'load' render() end
            end, C.ok)
        end
        content[#content + 1] = button(24, H - 50, 'Close', close)
    elseif view == 'recent' then
        content[#content + 1] = text({ position = V2(24, 64), text = 'Servers you joined before', textColor = C.text })
        local list = section:get('Recent') or {}
        if #list == 0 then content[#content + 1] = text({ position = V2(24, 110), text = 'None yet.', textColor = C.dim }) end
        for i, e in ipairs(list) do
            content[#content + 1] = text({ position = V2(32, 94 + (i - 1) * 28), text = e.host .. ':' .. tostring(e.port), textColor = C.text },
                { mouseClick = async:callback(function() hostBuf, portBuf = e.host, tostring(e.port) view = 'main' render() end) })
        end
        content[#content + 1] = button(24, H - 50, 'Back', function() view = 'main' render() end)
    else
        content[#content + 1] = text({ position = V2(24, 64), text = 'Choose a save to join with', textColor = C.text })
        local list = saveList()
        if #list == 0 then
            content[#content + 1] = text({ position = V2(24, 110), text = 'No saves found.', textColor = C.dim })
        end
        for i = 1, math.min(#list, 11) do
            local s = list[i]
            local label = tostring(s.info.playerName or s.dir) .. '  (level ' .. tostring(s.info.playerLevel or '?') .. ')  '
                .. tostring(s.info.description or s.slot)
            content[#content + 1] = text({ position = V2(32, 94 + (i - 1) * 28), text = label, textColor = C.text },
                { mouseClick = async:callback(function() section:set('Active', true) close() menu.loadGame(s.dir, s.slot) end) })
        end
        content[#content + 1] = button(24, H - 50, 'Back', function() view = 'main' render() end)
    end

    local layout = {
        layer = 'Modal', type = ui.TYPE.Widget,
        -- centred in the GUI's own units (ui.screenSize is in pixels, which differs when the GUI is scaled)
        props = { relativePosition = V2(0.5, 0.5), anchor = V2(0.5, 0.5), size = V2(W, H) },
        content = ui.content(content),
    }
    if el then
        el.layout = layout
        el:update()
    else
        el = ui.create(layout)
    end
end

local function open()
    mode = section:get('Mode') or mode
    hostBuf = hostBuf or section:get('Host') or DEFAULT_HOST
    portBuf = portBuf or tostring(section:get('Port') or 25565)
    passBuf = passBuf or section:get('Password') or ''
    view, message = 'main', nil
    render()
end

-- Our button on the main menu (an engine hook, generic: the engine knows nothing about multiplayer). Its textures are the
-- mod's own. A click arrives in onMainMenuButton below.
local ok, err = pcall(ui.addMainMenuButton, {
    id = 'multiplayer', after = 'loadgame',
    normal = 'textures/menu_multiplayer.tga',
    highlighted = 'textures/menu_multiplayer_over.tga',
    pushed = 'textures/menu_multiplayer_pressed.tga',
})
if not ok then print('[mp-menu] could not add the main menu button: ' .. tostring(err)) end

return {
    interfaceName = 'MultiplayerMenu',
    interface = { version = 1, open = open },
    engineHandlers = {
        onMainMenuButton = function(id)
            if id == 'multiplayer' then
                open()
            else
                -- New or Load from the main menu itself: a plain single-player game, no multiplayer session
                section:set('Active', false)
                close()
            end
        end,
        onStateChanged = function()
            close()
            -- back at the main menu: whatever game starts next is single-player unless this screen starts it
            if menu.getState() == menu.STATE.NoGame then section:set('Active', false) end
        end,
        -- Escape closes the screen like the engine's own windows
        onKeyPress = function(key)
            if el and key.code == input.KEY.Escape then close() end
        end,
    },
}
