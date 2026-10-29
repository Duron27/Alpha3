-- Multiplayer UI: chat box (Global / Party tabs), party vitals panel, invite banner, and the menu
-- shown when you activate another player's avatar. All state comes from global.lua through events.
local ui = require('openmw.ui')
local util = require('openmw.util')
local core = require('openmw.core')
local input = require('openmw.input')
local async = require('openmw.async')
local I = require('openmw.interfaces')
local trade = require('scripts/mp/trade')
local storage = require('openmw.storage')

I.Settings.registerPage {
    key = 'MultiplayerPage', l10n = 'Multiplayer', name = 'Multiplayer',
    description = 'Connect to a relay server. Press Enter or Y to chat; activate another player to invite them to a party.',
}

I.Settings.registerGroup {
    key = 'SettingsMpPlayers', page = 'MultiplayerPage', l10n = 'Multiplayer', name = 'Connected players',
    permanentStorage = false,
    settings = {
        { key = 'Address', renderer = 'mpAddress', name = 'Your address', default = '' },
        { key = 'List', renderer = 'mpPlayers', name = 'Players', default = {} },
    },
}
local playersSection = storage.playerSection('SettingsMpPlayers')

local V2, rgb = util.vector2, util.color.rgb
local WHITE = ui.texture { path = 'white' }

local CHAT_W = 480
local LINES = 8
local LINE_H = 18
local TEXT_SIZE = 15
local WRAP = 68
local FADE_AFTER = 14 -- seconds a new message stays visible while chat is closed
local MAX_HISTORY = 80
local PARTY_W, BAR_W, BAR_H, BLOCK_H = 200, 100, 8, 78

local COLORS = {
    global = rgb(0.92, 0.92, 0.92), party = rgb(0.45, 0.95, 0.5), system = rgb(1, 0.85, 0.35), whisper = rgb(0.9, 0.6, 1), emote = rgb(0.95, 0.75, 0.55),
    health = rgb(0.78, 0.12, 0.12), magicka = rgb(0.2, 0.3, 0.9), fatigue = rgb(0.15, 0.7, 0.25),
    bar_bg = rgb(0.08, 0.08, 0.08), tab_on = rgb(1, 1, 1), tab_off = rgb(0.55, 0.55, 0.55),
}

local camera = require('openmw.camera')
local nearby = require('openmw.nearby')
local lastMarkerSig
local markers = {} -- avatar object id -> { el, name }
local avatarList = {}
local ambient = require('openmw.ambient')
local function playSound(id) pcall(ambient.playSound, id) end -- a missing sound must never break the UI
local playerList, myPlayerId = {}, nil
local self = require('openmw.self')
local types = require('openmw.types')
local groundTime, airTime = 0, 0
local wasUsing, wasAir, attackSeq, jumpSeq, lastAttack, lastInputSig = false, false, 0, 0, nil, ''
local releaseSeq, releaseKind, releaseStrength, pressedAt, holding = 0, nil, 0, 0, false
local inputData = { sneak = false, reading = false, pitch = 0, mode = nil }
local inputSentAt = -10

-- Sends our animation-relevant input to the global script when it changed (and once a second regardless).
local function pushInput(force)
    inputData.atk, inputData.seq, inputData.jseq = lastAttack, attackSeq, jumpSeq
    local sig = table.concat({ tostring(inputData.sneak), tostring(inputData.reading), tostring(inputData.mode), tostring(inputData.fly), attackSeq, releaseSeq, jumpSeq,
        math.floor(inputData.pitch * 20 + 0.5) }, '|')
    local t = core.getRealTime()
    if force or sig ~= lastInputSig or t - inputSentAt > 1 then
        lastInputSig, inputSentAt = sig, t
        core.sendGlobalEvent('MpInput', { sneak = inputData.sneak, reading = inputData.reading, pitch = inputData.pitch, mode = inputData.mode, fly = inputData.fly,
            atk = lastAttack, seq = attackSeq, jseq = jumpSeq, rkind = releaseKind, rseq = releaseSeq, str = releaseStrength })
    end
end
local tab = 'global'
local logs = { global = {}, party = {} }
local unread = { global = 0, party = 0 }
local lastMsgTime = -1e9
local chatOpen, menuOpen = false, false
local inputBuf = ''
local state = { connected = false, members = {}, invites = {}, size = 1 }
local dirty = { chat = true, party = true, invites = true }
local logVisible = false
local synced = false

local logEl, inputEl, partyEl, inviteEl, menuEl

local function now() return core.getRealTime() end
-- The GUI's own units (ui.screenSize is in pixels, which differs when the GUI is scaled)
local function screen()
    local ok, size = pcall(function() return ui.layers[ui.layers.indexOf('HUD')].size end)
    return ok and size or ui.screenSize()
end

-- Multiplayer is only on in a game started from the Multiplayer screen (menu.lua sets 'Active'); a game started
-- with the main menu's own New/Load stays plain single-player: no UI, no connection.
local mpActive
local function active()
    if mpActive == nil then
        local conn = storage.playerSection('MpConnect')
        mpActive = conn:get('Active') == true
    end
    return mpActive
end

local function destroy(el) if el then el:destroy() end return nil end

local function text(props)
    props.textSize = props.textSize or TEXT_SIZE
    local events = props.events
    props.events = nil
    return { template = I.MWUI.templates.textNormal, props = props, events = events }
end

local function background(alpha)
    return {
        type = ui.TYPE.Image,
        props = { resource = WHITE, relativeSize = V2(1, 1), color = rgb(0, 0, 0), alpha = alpha },
    }
end

------------------------------------------------------------------ chat log

local function wrap(str, limit)
    local out = {}
    for para in (str .. '\n'):gmatch('(.-)\n') do
        while #para > limit do
            local cut = para:sub(1, limit):match('^.*()%s') or limit
            out[#out + 1] = para:sub(1, cut)
            para = para:sub(cut + 1)
        end
        out[#out + 1] = para
    end
    return out
end

local function addLine(scope, str)
    local lines = wrap(str, WRAP)
    local targets = (scope == 'system' or scope == 'whisper' or scope == 'emote') and { 'global', 'party' } or { scope }
    for _, t in ipairs(targets) do
        for i, l in ipairs(lines) do
            local log = logs[t]
            log[#log + 1] = { text = (i > 1 and '   ' or '') .. l, scope = scope }
        end
        while #logs[t] > MAX_HISTORY do table.remove(logs[t], 1) end
        if t ~= tab or not chatOpen then unread[t] = unread[t] + 1 end
    end
    lastMsgTime = now()
    dirty.chat = true
end

local function setTab(t)
    tab = t
    unread[t] = 0
    dirty.chat = true
end

local function renderChat()
    local active = chatOpen or (now() - lastMsgTime < FADE_AFTER)
    logVisible = active
    local scr = screen()
    local height = 28 + LINES * LINE_H + 6
    local content = {}
    -- tabs
    local function tabLabel(id, label, x)
        local n = unread[id] > 0 and (' (' .. unread[id] .. ')') or ''
        local c = tab == id and COLORS.tab_on or COLORS.tab_off
        content[#content + 1] = text {
            position = V2(x, 5), text = '[' .. label .. n .. ']', textColor = c,
            events = {
                mouseClick = async:callback(function() setTab(id) end),
            },
        }
    end
    tabLabel('global', 'Global', 8)
    tabLabel('party', 'Party', 100)
    -- lines (newest at bottom)
    local log = logs[tab]
    local first = math.max(1, #log - LINES + 1)
    for i = first, #log do
        local l = log[i]
        content[#content + 1] = text {
            position = V2(8, 28 + (i - first) * LINE_H), text = l.text,
            textColor = COLORS[l.scope] or COLORS.global,
        }
    end
    local layout = {
        layer = 'HUD', type = ui.TYPE.Widget,
        props = { position = V2(10, scr.y * 0.5 - height / 2), size = V2(CHAT_W, height), alpha = active and 1 or 0.45 },
        content = ui.content({ background(chatOpen and 0.6 or 0.35), table.unpack(content) }),
    }
    if logEl then
        logEl.layout = layout
        logEl:update()
    else
        logEl = ui.create(layout)
    end
end

------------------------------------------------------------------ input

trade.init({
    send = function(msg) core.sendGlobalEvent('MpCmd', { cmd = 'trade_send', msg = msg }) end,
    notice = function(str) addLine('system', '* ' .. str) end,
    setMode = function() I.UI.setPauseOnMode('Interface', true); I.UI.setMode('Interface', { windows = {} }) end,
    unsetMode = function() I.UI.setMode() end,
})

local closeChat
local updateMarkers
local updateStatus

------------------------------------------------------------------ connection status (top left)

local statusEl, statusText
local hostPort

updateStatus = function()
    local text
    if state.connected then
        local mine
        for _, pl in ipairs(playerList) do if pl.id == myPlayerId then mine = pl.ping end end
        local others = math.max(0, #playerList - 1)
        if state.hosting then
            text = 'Multiplayer: hosting' .. (hostPort and (' on port ' .. hostPort) or '') .. ' - ' .. others .. ' other player' .. (others == 1 and '' or 's')
        else
            text = 'Multiplayer: connected' .. (mine and (' - ' .. mine .. ' ms') or '') .. ' - ' .. others .. ' other player' .. (others == 1 and '' or 's')
        end
    elseif state.refused then
        text = 'Multiplayer: refused by the host (' .. tostring(state.refused) .. ')'
    else
        text = 'Multiplayer: not connected'
    end
    if text == statusText and statusEl then return end
    statusText = text
    local layout = {
        layer = 'HUD', type = ui.TYPE.Widget, props = { position = V2(8, 6), size = V2(420, 20) },
        content = ui.content { { template = I.MWUI.templates.textNormal,
            props = { text = text, textSize = 14, textColor = state.connected and rgb(0.6, 0.9, 0.6) or rgb(0.9, 0.6, 0.5) } } },
    }
    if statusEl then statusEl.layout = layout statusEl:update() else statusEl = ui.create(layout) end
end

------------------------------------------------------------------ markers over other players

local ARROW = ui.texture { path = 'textures/mp_arrow.tga' } -- green: everybody else
local ARROW_GROUP = ui.texture { path = 'textures/mp_arrow_group.tga' } -- orange: your group
local ARROW_SIZE, HEAD_HEIGHT = 28, 135

-- An arrow (and name) above every other player in this cell: orange for your group, green for everybody else. It is a screen overlay, so walls and
-- scenery never hide it.
function updateMarkers()
    local scr = ui.screenSize()
    local gui = screen()
    local k = gui.x / scr.x
    local seen = {}
    local camPos = camera.getPosition()
    local forward = camera.viewportToWorldVector(V2(scr.x / 2, scr.y / 2))
    for _, a in ipairs(avatarList) do
        do
            local pos = a.pos
            local v = camera.worldToViewportVector(pos + util.vector3(0, 0, HEAD_HEIGHT))
            local key = a.name
            if (pos - camPos):dot(forward) > 0 and v.x > -50 and v.x < scr.x + 50 and v.y > -50 and v.y < scr.y + 50 then
                seen[key] = true
                local m = markers[key]
                local layout = {
                    layer = 'HUD', type = ui.TYPE.Widget,
                    props = { position = V2(v.x * k - 60, v.y * k - ARROW_SIZE - 24), size = V2(120, ARROW_SIZE + 22) },
                    content = ui.content {
                        { template = I.MWUI.templates.textNormal,
                          props = { text = a.name, textColor = a.group and rgb(1, 0.75, 0.4) or rgb(0.5, 1, 0.5), textSize = 16,
                                    position = V2(60, 0), anchor = V2(0.5, 0) } },
                        { type = ui.TYPE.Image,
                          props = { resource = a.group and ARROW_GROUP or ARROW, size = V2(ARROW_SIZE, ARROW_SIZE), position = V2(60, 20), anchor = V2(0.5, 0) } },
                    },
                }
                if m then
                    m.layout = layout
                    m:update()
                else
                    markers[key] = ui.create(layout)
                end
            end
        end
    end
    for key, el in pairs(markers) do
        if not seen[key] then el:destroy() markers[key] = nil end
    end
end

local function sendChat(str)
    str = str:gsub('^%s+', ''):gsub('%s+$', '')
    if str == '' then return end
    if str:sub(1, 1) == '/' then
        local cmd, arg = str:match('^/(%S+)%s*(.*)$')
        cmd = (cmd or ''):lower()
        if arg == '' then arg = nil end
        if cmd == 'accept' or cmd == 'decline' then
            core.sendGlobalEvent('MpCmd', { cmd = cmd, name = arg })
        elseif cmd == 'leave' then
            core.sendGlobalEvent('MpCmd', { cmd = 'leave' })
        elseif cmd == 'me' then
            if arg then core.sendGlobalEvent('MpCmd', { cmd = 'emote', text = arg }) else addLine('system', 'Usage: /me <text>') end
        elseif cmd == 'r' or cmd == 'reply' then
            if arg then core.sendGlobalEvent('MpCmd', { cmd = 'reply', text = arg }) else addLine('system', 'Usage: /r <text>') end
        elseif cmd == 'pkick' then
            if arg then core.sendGlobalEvent('MpCmd', { cmd = 'party_kick', name = arg }) else addLine('system', 'Usage: /pkick <party member> (leader only)') end
        elseif cmd == 'markers' or cmd == 'arrows' then
            core.sendGlobalEvent('MpCmd', { cmd = 'toggle', what = cmd, state = arg and arg:lower() or nil })
        elseif cmd == 'goto' then
            if arg then core.sendGlobalEvent('MpCmd', { cmd = 'goto', name = arg }) else addLine('system', 'Usage: /goto <player>') end
        elseif cmd == 'kick' or cmd == 'ban' or cmd == 'unban' or cmd == 'mute' or cmd == 'unmute' then
            if arg then core.sendGlobalEvent('MpCmd', { cmd = 'admin', sub = cmd, name = arg })
            else addLine('system', 'Usage: /' .. cmd .. ' <player> (host only)') end
        elseif cmd == 'who' then
            core.sendGlobalEvent('MpCmd', { cmd = 'admin', sub = 'who' })
        elseif cmd == 'login' then
            if arg then core.sendGlobalEvent('MpCmd', { cmd = 'admin', sub = 'login', password = arg }) else addLine('system', 'Usage: /login <admin password> (dedicated servers)') end
        elseif cmd == 'weather' or cmd == 'time' then
            core.sendGlobalEvent('MpCmd', { cmd = 'admin', sub = cmd, text = arg or '' })
        elseif cmd == 'announce' then
            if arg then core.sendGlobalEvent('MpCmd', { cmd = 'admin', sub = 'announce', text = arg })
            else addLine('system', 'Usage: /announce <text> (host only)') end
        elseif cmd == 'w' or cmd == 'whisper' then
            local to, text = (arg or ''):match('^(%S+)%s+(.+)$')
            if to then core.sendGlobalEvent('MpCmd', { cmd = 'whisper', to = to, text = text })
            else addLine('system', 'Usage: /w <player> <text>') end
        elseif cmd == 'ip' then
            core.sendGlobalEvent('MpCmd', { cmd = 'ip' })
        elseif cmd == 'players' or cmd == 'who' then
            if #playerList == 0 then addLine('system', 'Not connected.') end
            for _, pl in ipairs(playerList) do
                addLine('system', pl.name .. (pl.level and (' (level ' .. pl.level .. ')') or '') .. (pl.host and ' (host)' or '') .. (pl.id == myPlayerId and ' (you)' or '')
                    .. '  ' .. (pl.ping and (pl.ping .. ' ms') or '-'))
            end
        elseif cmd == 'g' or cmd == 'p' then
            if arg then core.sendGlobalEvent('MpCmd', { cmd = 'chat', scope = cmd == 'g' and 'global' or 'party', text = arg }) end
        else
            addLine('system', 'Commands: /players, /me, /goto, /markers [on|off] (other players on the map), /arrows [on|off], /ip, /w, /r, /pkick (party leader), host or server admin: /kick /ban /unban /mute /unmute /announce, server: /login /who /weather /time, /accept [name], /decline [name], /leave, /g <text>, /p <text>')
        end
        return
    end
    core.sendGlobalEvent('MpCmd', { cmd = 'chat', scope = tab, text = str })
end

local function openChat()
    if chatOpen then return end
    chatOpen = true
    inputBuf = ''
    unread[tab] = 0
    local scr = screen()
    local height = 28 + LINES * LINE_H + 6
    -- A UI mode is needed for keyboard focus and the cursor, but this one must not pause the game.
    I.UI.setPauseOnMode('Interface', false)
    I.UI.setMode('Interface', { windows = {} })
    inputEl = ui.create {
        layer = 'HUD', type = ui.TYPE.Widget,
        props = { position = V2(10, scr.y * 0.5 + height / 2 + 4), size = V2(CHAT_W, 26) },
        content = ui.content {
            background(0.7),
            {
                type = ui.TYPE.TextEdit, name = 'edit',
                props = {
                    position = V2(6, 3), size = V2(CHAT_W - 12, 20), textSize = TEXT_SIZE,
                    textColor = rgb(1, 1, 1), multiline = false, autoFocus = true, text = '',
                },
                events = {
                    textChanged = async:callback(function(t) inputBuf = t end),
                    keyPress = async:callback(function(key)
                        if key.code == input.KEY.Enter or key.code == input.KEY.NP_Enter then
                            local t = inputBuf
                            closeChat()
                            sendChat(t)
                        elseif key.code == input.KEY.Escape then
                            closeChat()
                        elseif key.code == input.KEY.Tab then
                            setTab(tab == 'global' and 'party' or 'global')
                        end
                    end),
                },
            },
        },
    }
    dirty.chat = true
end

closeChat = function()
    if not chatOpen then return end
    chatOpen = false
    inputEl = destroy(inputEl) -- also drops keyboard focus
    I.UI.setMode()
    I.UI.setPauseOnMode('Interface', true) -- menus and trades pause as usual
    dirty.chat = true
end

------------------------------------------------------------------ party panel & invites

local function bar(x, y, value, max, color)
    local ratio = (value and max and max > 0) and math.max(0, math.min(1, value / max)) or 0
    return {
        {
            type = ui.TYPE.Image,
            props = { resource = WHITE, position = V2(x, y), size = V2(BAR_W, BAR_H), color = COLORS.bar_bg },
        },
        {
            type = ui.TYPE.Image,
            props = { resource = WHITE, position = V2(x, y), size = V2(BAR_W * ratio, BAR_H), color = color },
        },
        text {
            position = V2(x + BAR_W + 6, y - 5), textSize = 12,
            text = value and (value .. '/' .. max) or '?', textColor = COLORS.global,
        },
    }
end

local function renderParty()
    local members = state.members
    if #members == 0 then
        partyEl = destroy(partyEl)
        return
    end
    local scr = screen()
    local content = {}
    for i, m in ipairs(members) do
        local y = 6 + (i - 1) * BLOCK_H
        content[#content + 1] = text {
            position = V2(8, y), text = (m.leader and '* ' or '') .. m.name, textColor = COLORS.party,
        }
        -- ailments (poison, disease, curses...) so the healer knows what to cure
        local AILMENT_COLORS = { p = rgb(0.45, 0.9, 0.3), d = rgb(0.75, 0.55, 0.3), x = rgb(0.95, 0.4, 0.4) }
        local rows = { { m.h, m.hm, COLORS.health }, { m.m, m.mm, COLORS.magicka }, { m.f, m.fm, COLORS.fatigue } }
        for r, row in ipairs(rows) do
            for _, w in ipairs(bar(10, y + 24 + (r - 1) * 12, row[1], row[2], row[3])) do
                content[#content + 1] = w
            end
        end
        local x = 10
        for _, bad in ipairs(m.bad or {}) do
            content[#content + 1] = text {
                position = V2(x, y + 62), textSize = 11, text = bad.n, textColor = AILMENT_COLORS[bad.c] or AILMENT_COLORS.x,
            }
            x = x + 7 * #bad.n + 14
            if x > PARTY_W then break end
        end
    end
    local layout = {
        layer = 'HUD', type = ui.TYPE.Widget,
        props = { position = V2(10, scr.y * 0.1), size = V2(PARTY_W, 8 + #members * BLOCK_H) },
        content = ui.content({ background(0.4), table.unpack(content) }),
    }
    if partyEl then
        partyEl.layout = layout
        partyEl:update()
    else
        partyEl = ui.create(layout)
    end
end

local function renderInvites()
    local invites = state.invites
    if #invites == 0 then
        inviteEl = destroy(inviteEl)
        return
    end
    local scr = screen()
    local content = {}
    for i, inv in ipairs(invites) do
        content[#content + 1] = text {
            position = V2(8, 6 + (i - 1) * 22),
            text = inv.name .. ' invited you to a party (' .. inv.left .. 's). Activate them or type /accept.',
            textColor = COLORS.system,
        }
    end
    local layout = {
        layer = 'HUD', type = ui.TYPE.Widget,
        props = { position = V2(scr.x / 2 - 260, 70), size = V2(520, 12 + #invites * 22) },
        content = ui.content({ background(0.6), table.unpack(content) }),
    }
    if inviteEl then
        inviteEl.layout = layout
        inviteEl:update()
    else
        inviteEl = ui.create(layout)
    end
end

------------------------------------------------------------------ avatar menu

local function closeMenu(keepMode)
    if not menuOpen then return end
    menuOpen = false
    menuEl = destroy(menuEl)
    if not keepMode then I.UI.setMode() end
end

local function openMenu(d)
    closeMenu()
    local options = {}
    local function opt(label, fn, opensMode) options[#options + 1] = { label = label, fn = fn, opensMode = opensMode } end
    local note
    if not d.connected then
        note = 'Not connected to the server.'
    elseif d.inMyParty then
        note = d.name .. ' is in your party.'
        opt('Leave party', function() core.sendGlobalEvent('MpCmd', { cmd = 'leave' }) end)
    elseif d.invitedMe then
        note = d.name .. ' invited you to a party.'
        opt('Accept', function() core.sendGlobalEvent('MpCmd', { cmd = 'accept', id = d.id }) end)
        opt('Decline', function() core.sendGlobalEvent('MpCmd', { cmd = 'decline', id = d.id }) end)
    elseif d.iInvited then
        note = 'Invite to ' .. d.name .. ' is pending.'
    elseif d.myPartySize >= 3 then
        note = 'Your party is full (3 players).'
    else
        note = d.name
        opt('Invite to party', function() core.sendGlobalEvent('MpCmd', { cmd = 'invite', id = d.id }) end)
    end
    if d.connected and not trade.active() then
        if trade.hasIncoming(d.id) then
            opt('Accept trade', function() trade.accept(d.id) end, true)
            opt('Decline trade', function() trade.decline(d.id) end)
        elseif trade.pendingOutgoing(d.id) then
            note = note .. ' (trade request pending)'
        else
            opt('Trade', function() trade.request(d.id, d.name) end)
        end
    end
    opt('Close', function() end)

    local content = { text { position = V2(12, 10), text = note, textColor = COLORS.system, textSize = 16 } }
    for i, o in ipairs(options) do
        content[#content + 1] = text {
            position = V2(24, 14 + i * 26), text = '> ' .. o.label, textColor = COLORS.tab_on, textSize = 16,
            events = {
                mouseClick = async:callback(function()
                    closeMenu(o.opensMode)
                    o.fn()
                end),
            },
        }
    end
    local w, h = 300, 22 + (#options + 1) * 26 + 10
    local scr = screen()
    menuOpen = true
    I.UI.setPauseOnMode('Interface', true)
    I.UI.setMode('Interface', { windows = {} })
    menuEl = ui.create {
        layer = 'Windows', type = ui.TYPE.Widget,
        props = { position = V2(scr.x / 2 - w / 2, scr.y / 2 - h / 2), size = V2(w, h) },
        content = ui.content({ background(0.85), table.unpack(content) }),
    }
end

------------------------------------------------------------------ the server's client mods (see clientmods.lua)

local modEl

local function closeModWindow()
    modEl = destroy(modEl)
    I.UI.setMode()
end

-- A small window in the middle of the screen: some lines of text and a few choices. The game is paused while it is open.
local function openModWindow(lines, options)
    if modEl then modEl = destroy(modEl) end
    local content = {}
    for i, line in ipairs(lines) do
        content[#content + 1] = text { position = V2(14, 10 + (i - 1) * 22), text = line, textColor = COLORS.system, textSize = 16 }
    end
    local top = 10 + #lines * 22 + 12
    for i, o in ipairs(options) do
        content[#content + 1] = text {
            position = V2(28, top + (i - 1) * 28), text = '> ' .. o.label, textColor = COLORS.tab_on, textSize = 17,
            events = { mouseClick = async:callback(function() closeModWindow() o.fn() end) },
        }
    end
    local w, h = 560, top + #options * 28 + 14
    local scr = screen()
    I.UI.setPauseOnMode('Interface', true)
    I.UI.setMode('Interface', { windows = {} })
    modEl = ui.create {
        layer = 'Windows', type = ui.TYPE.Widget,
        props = { position = V2(scr.x / 2 - w / 2, scr.y / 2 - h / 2), size = V2(w, h) },
        content = ui.content({ background(0.9), table.unpack(content) }),
    }
end

local function sizeText(bytes)
    if bytes >= 1048576 then return string.format('%.1f MB', bytes / 1048576) end
    return string.format('%d KB', math.max(1, math.floor(bytes / 1024 + 0.5)))
end

local function listText(names, limit)
    local shown = {}
    for i = 1, math.min(#names, limit) do shown[i] = names[i] end
    return table.concat(shown, ', ') .. (#names > limit and (' and ' .. (#names - limit) .. ' more') or '')
end

local function countText(n, what) return n .. ' ' .. what .. (n == 1 and ' file' or ' files') end

local function askMods(d)
    local lines = { 'This server\'s client mods are not the ones you have: ' .. listText(d.mods, 4) }
    local changes = {}
    if d.added > 0 then changes[#changes + 1] = countText(d.added, 'new') end
    if d.updated > 0 then changes[#changes + 1] = countText(d.updated, 'updated') end
    if d.deleted > 0 then changes[#changes + 1] = countText(d.deleted, 'removed') end
    lines[#lines + 1] = table.concat(changes, ', ') .. (d.bytes > 0 and ('  (download: ' .. sizeText(d.bytes) .. ')') or '')
    lines[#lines + 1] = 'Every file is checked against the server\'s hash. The game restarts afterwards, and you join again.'
    openModWindow(lines, {
        { label = 'Install and restart', fn = function() core.sendGlobalEvent('MpCmd', { cmd = 'modsAnswer', accept = true }) end },
        { label = 'Decline (leave the server)', fn = function() core.sendGlobalEvent('MpCmd', { cmd = 'modsAnswer', accept = false }) end },
    })
end

local function modsDone(d)
    if not d.ok then
        openModWindow({ 'The mods could not be installed.', tostring(d.reason or '') }, { { label = 'OK', fn = function() end } })
        return
    end
    openModWindow({ 'The server\'s mods are installed (' .. (d.files or 0) .. ' files).', 'The game has to restart to load them.' }, {
        { label = 'Restart now', fn = function() core.sendGlobalEvent('MpCmd', { cmd = 'modsRestart' }) end },
        { label = 'Later', fn = function() end },
    })
end

------------------------------------------------------------------ shots (see shots.lua)

local SHOT_MAX_RANGE = 6000
local AIM_RANGE = 8000
local BOLT_SPEED, ARROW_SPEED, THROWN_SPEED = 1500, 2400, 1500

local function vtab(v) return { math.floor(v.x + 0.5), math.floor(v.y + 0.5), math.floor(v.z + 0.5) } end

-- Where a shot fired from our body at what the crosshair points at ends: { from, to, hit }.
local function aimShot()
    local ok, res = pcall(function()
        local camPos = camera.getPosition()
        local look = camera.viewportToWorldVector(util.vector2(0.5, 0.5)):normalize()
        local aim = nearby.castRay(camPos, camPos + look * AIM_RANGE, { ignore = self })
        local aimPoint = aim.hit and aim.hitPos or (camPos + look * AIM_RANGE)
        local from = self.position + util.vector3(0, 0, 110) + util.vector3(look.x, look.y, 0):normalize() * 30
        local dir = (aimPoint - from):normalize()
        local stop = nearby.castRay(from, from + dir * SHOT_MAX_RANGE, { ignore = self })
        local to = stop.hit and stop.hitPos or (from + dir * SHOT_MAX_RANGE)
        return { from = vtab(from), to = vtab(to), hitpos = stop.hit and true or false, target = aim.hitObject }
    end)
    return ok and res or nil
end

-- A spell was cast: its cast effect shows on our avatar, a bolt flies, a spell on ourselves shows its hit effect.
local function reportCast()
    pcall(function()
        local spell = types.Actor.getSelectedSpell(self)
        local fx = spell and spell.effects and spell.effects[1]
        local mgef = fx and fx.effect
        if not mgef then return end
        local magicka = types.Actor.stats.dynamic.magicka(self).current
        if not spell.isAutocalc and spell.cost and spell.cost > magicka then return end -- not cast: no mana
        local msg = { k = 'cast', cast = mgef.castStatic, hit = mgef.hitStatic }
        local target
        if fx.range ~= core.magic.RANGE.Self then
            local aim = aimShot()
            if aim then
                target = aim.target
                if fx.range == core.magic.RANGE.Target and mgef.bolt and mgef.bolt ~= '' then
                    msg = { k = 'bolt', cast = mgef.castStatic, rec = mgef.bolt, hit = mgef.hitStatic, from = aim.from,
                        to = aim.to, hitpos = aim.hitpos, spd = (mgef.speed and mgef.speed > 0) and (mgef.speed > 20 and mgef.speed or mgef.speed * BOLT_SPEED) or BOLT_SPEED }
                end
            end
        end
        -- a spell cast at another player's avatar: global.lua passes it on if it only helps (heal, cure...)
        msg.aid = spell.id
        msg.touch = fx.range == core.magic.RANGE.Touch or nil
        msg.target = target
        core.sendGlobalEvent('MpShot', msg)
    end)
end

local function rangedWeapon()
    local W = types.Weapon
    return { [W.TYPE.MarksmanBow] = ARROW_SPEED, [W.TYPE.MarksmanCrossbow] = ARROW_SPEED, [W.TYPE.MarksmanThrown] = THROWN_SPEED }
end

-- A bow, crossbow or thrown weapon was released: the arrow (or the weapon itself) flies.
local function reportRangedShot(strength)
    pcall(function()
        if strength < 0.1 then return end
        local eq = types.Actor.getEquipment(self)
        local weapon = eq[types.Actor.EQUIPMENT_SLOT.CarriedRight]
        if not weapon then return end
        local speed = rangedWeapon()[types.Weapon.record(weapon).type]
        if not speed then return end
        local ammo = types.Weapon.record(weapon).type ~= types.Weapon.TYPE.MarksmanThrown
            and eq[types.Actor.EQUIPMENT_SLOT.Ammunition] or weapon
        if not ammo then return end
        local aim = aimShot()
        if not aim then return end
        core.sendGlobalEvent('MpShot', { k = 'arrow', rec = ammo.recordId, from = aim.from, to = aim.to,
            hitpos = aim.hitpos, spd = speed * (0.6 + 0.4 * strength) })
    end)
end

------------------------------------------------------------------ handlers

return {
    engineHandlers = {
        -- What the other players need to animate our avatar: sneaking, jumping, attacking and casting, whether we
        -- have a menu open, and where we are looking. Attacks and jumps carry a counter so each is shown exactly once.
        onUpdate = function(dt)
            if not active() then return end
            local using = I.UI.getMode() == nil and input.isActionPressed(input.ACTION.Use)
            local function attackKind()
                local fwd = input.isActionPressed(input.ACTION.MoveForward) or input.isActionPressed(input.ACTION.MoveBackward)
                local side = input.isActionPressed(input.ACTION.MoveLeft) or input.isActionPressed(input.ACTION.MoveRight)
                return fwd and 'thrust' or side and 'slash' or 'chop'
            end
            if using and not wasUsing then
                local stance = types.Actor.getStance(self)
                if stance == types.Actor.STANCE.Weapon then
                    -- pressing starts the wind-up; releasing swings, harder the longer it was held
                    attackSeq = attackSeq + 1
                    lastAttack = attackKind()
                    pressedAt, holding = core.getRealTime(), true
                elseif stance == types.Actor.STANCE.Spell then
                    attackSeq = attackSeq + 1
                    lastAttack = 'cast'
                    reportCast()
                end
            elseif holding and not using and wasUsing then
                holding = false
                local speed = 1
                pcall(function()
                    local w = types.Actor.getEquipment(self)[types.Actor.EQUIPMENT_SLOT.CarriedRight]
                    if w then speed = types.Weapon.record(w).speed end
                end)
                releaseSeq = releaseSeq + 1
                releaseKind = attackKind()
                releaseStrength = math.max(0, math.min(1, (core.getRealTime() - pressedAt) * speed / 0.8))
                reportRangedShot(releaseStrength)
            end
            wasUsing = using
            local air = not types.Actor.isOnGround(self) and not types.Actor.isSwimming(self)
            -- a jump is a take-off after standing on the ground for a moment; flickering on and off the ground while
            -- floating (levitation, flying cheats) is not a string of jumps
            if air then
                if not wasAir and groundTime >= 0.1 then jumpSeq = jumpSeq + 1 end
                groundTime = 0
                airTime = airTime + dt
            else
                groundTime = groundTime + dt
                airTime = 0
            end
            inputData.fly = airTime >= 1.8 -- off the ground for good (levitation, flying), not even a long jump or a short fall
            wasAir = air
            inputData.sneak = self.controls.sneak and true or false
            pushInput(false)
        end,
        onFrame = function()
            if not active() then return end
            updateMarkers()
            updateStatus()
            -- onFrame also runs while the game is paused, which is exactly when a menu is open
            inputData.reading = I.UI.getMode() ~= nil and not chatOpen
            inputData.mode = I.UI.getMode() -- (a container window open = the player is looting)
            inputData.pitch = camera.getPitch() + camera.getExtraPitch()
            pushInput(false)
            if not synced then
                synced = true
                core.sendGlobalEvent('MpCmd', { cmd = 'sync' })
                -- server chosen on the main menu screen (menu.lua) -> global script
                local conn = storage.playerSection('MpConnect')
                local function sendConfig()
                    if conn:get('Mode') then
                        core.sendGlobalEvent('MpCmd', {
                            cmd = 'configure', mode = conn:get('Mode'), host = conn:get('Host'), port = conn:get('Port'),
                            password = conn:get('Password') or '',
                            bot = conn:get('Bot') and true or false })
                    end
                end
                sendConfig()
                -- the main menu screen can change the settings while the game runs (e.g. "add test bot")
                conn:subscribe(async:callback(sendConfig))
            end
            local shouldShow = chatOpen or (now() - lastMsgTime < FADE_AFTER)
            if shouldShow ~= logVisible then dirty.chat = true end
            if dirty.chat then dirty.chat = false; renderChat() end
            if dirty.party then dirty.party = false; renderParty() end
            if dirty.invites then dirty.invites = false; renderInvites() end
        end,
        onKeyPress = function(key)
            if not active() then return end
            if I.UI.getMode() ~= nil or chatOpen then return end
            if key.code == input.KEY.Enter or key.code == input.KEY.NP_Enter or key.code == input.KEY.Y then
                openChat()
            end
        end,
    },
    eventHandlers = {
        MpChat = function(d)
            local label = d.scope == 'party' and 'P' or d.scope == 'whisper' and 'W' or 'G'
            if d.scope == 'system' then
                addLine('system', '* ' .. d.text)
                if d.sound then playSound(d.sound) end
            elseif d.scope == 'emote' then
                addLine('emote', '* ' .. tostring(d.from) .. ' ' .. d.text)
            else
                if d.scope == 'whisper' and not d.mine then playSound('Menu Click') end
                addLine(d.scope == 'whisper' and 'whisper' or d.scope, '[' .. label .. '] ' .. tostring(d.from) .. ': ' .. d.text)
            end
        end,
        MpUiState = function(d)
            local function sig(s)
                local parts = { tostring(s.connected), tostring(#s.members), tostring(#s.invites) }
                for _, m in ipairs(s.members) do
                    parts[#parts + 1] = table.concat({ m.id, m.name, tostring(m.leader), tostring(m.h), tostring(m.hm),
                        tostring(m.m), tostring(m.mm), tostring(m.f), tostring(m.fm) }, ',')
                    for _, b in ipairs(m.bad or {}) do parts[#parts] = parts[#parts] .. ',' .. b.c .. b.n end
                end
                for _, i in ipairs(s.invites) do parts[#parts + 1] = i.id .. ':' .. i.left end
                return table.concat(parts, '|')
            end
            local changed = sig(d) ~= sig(state)
            state = d
            if changed then dirty.party = true; dirty.invites = true end
        end,
        MpDebugAttack = function(d) -- test macro: press, then release with strength d.str after d.hold seconds
            attackSeq = attackSeq + 1; lastAttack = d.kind
            async:newUnsavableSimulationTimer(d.hold or 0.1, function()
                releaseSeq = releaseSeq + 1; releaseKind = d.kind; releaseStrength = d.str or 0
            end)
        end,
        MpHostInfo = function(d)
            hostPort = d.port
            local text = ''
            if d.port then
                if d.public then text = 'Internet address: ' .. d.public .. ':' .. d.port
                    .. '  (forward TCP port ' .. d.port .. ' on your router)'
                elseif d.looking then text = 'Internet address: looking it up...'
                else text = 'Internet address: unknown (type /ip in chat to try again)' end
                text = text .. '\nSame network: ' .. tostring(d.lan or '?') .. ':' .. d.port
            end
            pcall(function() playersSection:set('Address', text) end)
        end,
        MpAvatars = function(d) avatarList = d.list or {} end,
        MpModPrompt = function(d) askMods(d) end,
        MpModsDone = function(d) modsDone(d) end,
        MpMarkers = function(d)
            local list = d.list or {}
            local ok, err = pcall(ui.setMapMarkers, list)
            if not ok then print('[mp-ui] setMapMarkers failed: ' .. tostring(err))
            else
                local sig = {}
                for _, m in ipairs(list) do
                    sig[#sig + 1] = string.format('%s@[%s]', tostring(m.name), tostring(m.cell))
                end
                sig = #list .. ' ' .. table.concat(sig, '; ')
                if sig ~= lastMarkerSig then
                    lastMarkerSig = sig
                    print('[mp-ui] map markers: ' .. sig)
                end
            end
        end,
        MpPlayers = function(d)
            playerList, myPlayerId = d.list, d.me
            -- shown on the Multiplayer settings page (renderer in menu.lua)
            local ok, err = pcall(function() playersSection:set('List', d.list) end)
            if not ok then print('[mp-ui] could not publish player list: ' .. tostring(err)) end
        end,
        MpAvatar = function(d) openMenu(d) end,
        MpTrade = function(msg) trade.onMessage(msg) end,
        MpTradeResult = function(r) trade.onResult(r) end,
        MpUiOpenChat = function() openChat() end, -- for tests/macros
        MpUiAvatar = function(d) openMenu(d) end, -- for tests/macros
        MpUiTrade = function(d) trade.request(d.id, d.name) end, -- for tests/macros
        MpUiTradeDebug = function(d) -- for tests/macros
            if d.action == 'accept' then trade.debugAccept() else trade.debugEdit(d.action, d.rid, d.n) end
        end,
        -- Another window opened, or Escape closed our menu's Interface mode: drop our widgets.
        -- (I.UI.getMode() lags a frame behind setMode, so it can't be polled for this.)
        UiModeChanged = function(d)
            if not active() then return end
            print('[mp-ui] mode ' .. tostring(d.oldMode) .. ' -> ' .. tostring(d.newMode) .. ' chatOpen=' .. tostring(chatOpen) .. ' menuOpen=' .. tostring(menuOpen))
            -- Escape ended our Interface mode, or another window replaced it
            if d.newMode ~= 'Interface' then
                if chatOpen then
                    chatOpen = false
                    inputEl = destroy(inputEl)
                    I.UI.setPauseOnMode('Interface', true)
                end
                trade.onMode(d.newMode)
                if menuOpen then menuOpen = false; menuEl = destroy(menuEl) end
            end
            -- the engine may have hidden/rebuilt the HUD while a menu was open: rebuild ours
            logEl, partyEl, inviteEl, statusEl = destroy(logEl), destroy(partyEl), destroy(inviteEl), destroy(statusEl)
            dirty.chat, dirty.party, dirty.invites = true, true, true
        end,
    },
}
