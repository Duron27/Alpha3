-- Multiplayer client. Every player keeps their own world. Everyone online appears as a stand-in
-- NPC avatar when they share your cell (activate one to invite them). Members of your party (max 3)
-- additionally share vitals and chat, and actors that are alive in a member's world but dead/missing
-- in ours appear as marked proxies (proxies.lua). The UI lives in player.lua.
local net = require('scripts/mp/netlib')
local world = require('openmw.world')
local util = require('openmw.util')
local types = require('openmw.types')
local async = require('openmw.async')
local core = require('openmw.core')
local storage = require('openmw.storage')
local markup = require('openmw.markup')
local I = require('openmw.interfaces')
local proxies = require('scripts/mp/proxies')
local shots = require('scripts/mp/shots')
local loot = require('scripts/mp/loot')
local items = require('scripts/mp/items')
local relay = require('scripts/mp/relay')
local clientmods = require('scripts/mp/clientmods')
local bot = require('scripts/mp/bot')
local interp = require('scripts/mp/interp')

local SETTINGS = 'SettingsGlobalMultiplayer'
local SEND_INTERVAL = 1 / 30 -- while moving; standing still, IDLE_INTERVAL (a change of stance, sneaking... goes at once)
local IDLE_INTERVAL = 0.5
local EQUIP_INTERVAL = 1
local RETRY_INTERVAL = 3
local REMOTE_SCRIPT = 'scripts/mp/remote.lua'
local VITALS_INTERVAL = 0.5
local UI_INTERVAL = 0.5
local INVITE_TTL = 60
local MAX_PARTY = 3
local DEBUG = false -- log avatar/proxy visibility, drift and the player list (helps diagnose flicker and lag)

-- the settings page itself is registered by player.lua (pages can't be registered from global scripts)
I.Settings.registerGroup {
    key = SETTINGS, page = 'MultiplayerPage', l10n = 'Multiplayer', name = 'Connection',
    permanentStorage = true,
    settings = {
        { key = 'Enabled', renderer = 'checkbox', name = 'Enabled', default = true },
        { key = 'Mode', renderer = 'select', name = 'Mode', default = 'join',
          argument = { l10n = 'Multiplayer', items = { 'join', 'host' } } },
        { key = 'Host', renderer = 'textLine', name = 'Server host', default = '127.0.0.1' },
        { key = 'Password', renderer = 'textLine', name = 'Password (host: required to join, joiner: sent to the host)', default = '' },
        { key = 'MaxPlayers', renderer = 'number', name = 'Most players when hosting', default = 64,
          argument = { integer = true, min = 2, max = 64 } },
        { key = 'Motd', renderer = 'textLine', name = 'Message of the day when hosting', default = '' },
        { key = 'ShowMapMarkers', renderer = 'checkbox', name = 'Show other players on the map and minimap (orange: your group, green: everyone else)', default = true },
        { key = 'ShowPlayerArrows', renderer = 'checkbox', name = 'Show nameplates (name, level, health) over other players in your cell', default = true },
        { key = 'SharedKills', renderer = 'checkbox', name = 'Kills are shared with your group', default = true },
        { key = 'OtherWorldStandins', renderer = 'checkbox', name = "Show characters that are alive in other players' worlds (after a few minutes)", default = true },
        { key = 'SharedLoot', renderer = 'checkbox', name = 'Ordinary loot taken from containers is shared with your group', default = true },
        { key = 'SharedLootGear', renderer = 'checkbox', name = 'Also share plain weapons and armor (never enchanted or scripted ones)', default = false },
        { key = 'SharedFights', renderer = 'checkbox', name = 'Damage to actors is shared with your group', default = true },
        { key = 'Port', renderer = 'number', name = 'Server port', default = 25565,
          argument = { integer = true, min = 1, max = 65535 } },
    },
}
local settings = storage.globalSection(SETTINGS)

local sendTimer, equipTimer, retry = 0, 0, 0
local hostErrorShown = false
local players = {} -- latest list from the relay: { id, name, ping, host }
local worldTimer = 0
local lastReal
local lastFailure = {} -- invite failure text -> clock, so a repeated failure is not spammed
local lastLevel
local lastServerMessage = 0 -- real time of the last thing the host told us
local SERVER_SILENCE = 15
local lastWhisperFrom -- who whispered to us last (for /r)
local resumeToken -- given by the host when we joined: lets us take our place back after a dropped connection
local refusedBy -- why the host turned us away: we stop retrying until the settings change
local botWanted = false -- test bot (menu): only while hosting
local hostInfo -- when hosting: { port, lan, public, error, looking }
local savedTimeScale -- our game time scale while we wait for the host's clock to catch up
local WORLD_INTERVAL = 5
local lastEquipJson
local myId
local remotes = {} -- id -> { profile, state, equip, obj, equipSent }  (group members' avatars)
local avatarIds = {} -- local object id -> remote id
local party = { members = {}, leader = nil } -- members: list of ids including ours
local vitals = {} -- id -> { h, hm, m, mm, f, fm }
local invitesIn = {} -- inviter id -> expiry (clock)
local invitesOut = {} -- invitee id -> expiry (clock)
local clock = 0
local vitalsTimer, uiTimer = 0, 0

local function log(...) print('[mp]', ...) end
local guardedErrors = {} -- what -> last error text, so a repeating failure is logged once
local function guarded(what, fn, ...)
    local ok, err = pcall(fn, ...)
    if not ok and guardedErrors[what] ~= tostring(err) then
        guardedErrors[what] = tostring(err)
        print('[mp] ' .. what .. ' failed: ' .. tostring(err))
    end
end
local function player() return world.players[1] end
local function cellKey(cell) return cell.isExterior and '' or cell.name end
local function inParty() return #party.members > 1 end
local function dlog(...) if DEBUG then print('[mp-dbg]', ...) end end
local function nameOf(id)
    local r = remotes[id]
    return r and r.profile and r.profile.name or ('Player ' .. tostring(id))
end
local function toUi(event, data)
    local p = world.players[1]
    if p then p:sendEvent(event, data) end
end
local function chatLine(scope, from, text, mine)
    toUi('MpChat', { scope = scope, from = from, text = text, mine = mine })
end
local function notice(text, sound) toUi('MpChat', { scope = 'system', text = text, sound = sound }) end
local function isMember(id) for _, m in ipairs(party.members) do if m == id then return true end end return false end

proxies.init({
    send = function(t) net.send(net.encodeJson(t)) end,
    log = log,
    dlog = dlog,
    blockActivation = function(obj) I.Activation.addHandlerForObject(obj, function() return false end) end,
    player = player,
    cellKey = cellKey,
    myId = function() return myId end,
    active = function() return net.isConnected() and inParty() end,
    isAvatar = function(obj) return avatarIds[obj.id] ~= nil end,
    ownerName = nameOf,
    sharedKills = function() return settings:get('SharedKills') ~= false end,
    sharedFights = function() return settings:get('SharedFights') ~= false end,
    strangerStandins = function() return settings:get('OtherWorldStandins') ~= false end,
    connected = function() return net.isConnected() end,
    isMember = isMember,
})

shots.init({
    player = player,
    cellKey = cellKey,
    blockActivation = function(obj) I.Activation.addHandlerForObject(obj, function() return false end) end,
})

------------------------------------------------------------------ connection / group

-- Two modes, never both: 'join' connects to someone else's server; 'host' runs the server in this game
-- (relay.lua) and plays through a loopback connection to it. Either way there is exactly one connection.
local function hosting() return settings:get('Mode') == 'host' end

-- Only a game started from the Multiplayer screen is a session: the player script sends 'configure' then. A plain
-- single-player game never connects, hosts or retries.
local sessionActive = false

local function connect()
    if not sessionActive or not settings:get('Enabled') then return end
    local port = settings:get('Port')
    if hosting() then
        if not relay.isRunning() then
            local err = relay.start(port, { password = settings:get('Password'), maxPlayers = settings:get('MaxPlayers'),
                motd = settings:get('Motd') })
            if err then
                log('could not host on port ' .. tostring(port) .. ': ' .. tostring(err))
                if not hostErrorShown then
                    hostErrorShown = true
                    notice('Could not host on port ' .. tostring(port) .. ': ' .. tostring(err))
                end
                return
            end
            hostErrorShown = false
            log('hosting on port ' .. tostring(port))
            hostInfo = { port = port, looking = true }
            -- hosting is pointless without the address people outside the network must use, so look it up now
            -- (one request to api.ipify.org)
            net.lookupPublicAddress()
            notice('Hosting on port ' .. tostring(port) .. '. Looking up your internet address (api.ipify.org)...')
        end
        net.connect('127.0.0.1', port)
    else
        if relay.isRunning() then relay.stop() end
        hostInfo = nil
        log('connecting to ' .. settings:get('Host') .. ':' .. tostring(port))
        net.connect(settings:get('Host'), port)
    end
end

local clearRemotes
local onEv
local onShot
local onAid
local spellHelps
local onAvatarActivated

-- Settings read while playing: changing one of these must not drop the session.
local LIVE_SETTINGS = { ShowMapMarkers = true, ShowPlayerArrows = true, SharedKills = true, SharedFights = true,
    SharedLoot = true, SharedLootGear = true, OtherWorldStandins = true }

settings:subscribe(async:callback(function(_, key)
    if key and LIVE_SETTINGS[key] then return end
    clearRemotes()
    net.disconnect()
    relay.stop() -- reconnecting starts it again if still hosting
    hostInfo = nil
    hostErrorShown = false
    refusedBy = nil
    resumeToken = nil -- other settings (server, name...) mean a different session
    retry = RETRY_INTERVAL
end))

------------------------------------------------------------------ client mods kept on the server (clientmods.lua)

clientmods.init({
    send = function(t) net.send(net.encodeJson(t)) end,
    toUi = toUi,
    notice = notice,
    now = core.getRealTime,
    -- the mods changed only files the game already has: leave cleanly and reload every script; the reloaded scripts
    -- join again by themselves, now with the server's mods
    reload = function()
        net.disconnect()
        clearRemotes()
        clientmods.reloadScripts()
    end,
    declined = function()
        -- without the server's mods the game would not match the others': leave
        refusedBy = 'mods'
        notice('You need the server\'s mods to play here. Join again to be asked once more.')
        net.disconnect()
        clearRemotes()
    end,
})

------------------------------------------------------------------ time and weather (the host is the clock)

local function releaseClock()
    if savedTimeScale then
        pcall(world.setGameTimeScale, savedTimeScale)
        savedTimeScale = nil
    end
end

local function sendWorld()
    local p = player()
    if not p then return end
    local msg = { t = 'world', time = core.getGameTime() % 86400 }
    local ok, w = pcall(function() return core.weather.getCurrent(p.cell) end)
    if ok and w then msg.weather, msg.region = w.recordId, p.cell.region end
    net.send(net.encodeJson(msg))
end

local function applyWorld(msg)
    -- time of day: jump forward if we are behind; stand still if we are ahead until the host catches up
    if type(msg.time) == 'number' then
        local diff = (msg.time - core.getGameTime() % 86400) % 86400 -- seconds we are behind, 0..86400
        if diff > 43200 then diff = diff - 86400 end -- negative: we are ahead
        if math.abs(diff) < 30 then
            releaseClock()
        elseif diff > 0 then
            releaseClock()
            world.advanceTime(diff / 3600)
        elseif not savedTimeScale then
            savedTimeScale = world.getGameTimeScale()
            pcall(world.setGameTimeScale, 0)
        end
    end
    -- weather in the host's region follows the host; a dedicated server names no region: the weather applies wherever we are
    if msg.weather then
        local region = msg.region
        local p = player()
        if (not region or region == '') and p then region = p.cell.region end
        if region and region ~= '' then
            local ok, rec = pcall(function() return core.weather.records[msg.weather] end)
            local current = pcall(function() return core.weather.getCurrent(p and p.cell).recordId end)
            local same = false
            if p then
                local okC, cur = pcall(function() return core.weather.getCurrent(p.cell) end)
                same = okC and cur and cur.recordId == msg.weather and (not msg.region or msg.region == '')
            end
            if ok and rec and not same then pcall(core.weather.changeWeather, region, rec) end
        end
    end
end

------------------------------------------------------------------ outbound player state

local function contentList()
    local ok, list = pcall(function()
        local out = {}
        for _, f in ipairs(core.contentFiles.list) do out[#out + 1] = f:lower() end
        return out
    end)
    return ok and list or {}
end

local function sendProfile()
    local p = player()
    if not p then return end
    local rec = types.NPC.record(p)
    net.send(net.encodeJson({
        t = 'profile', name = rec.name, race = rec.race, class = rec.class,
        head = rec.head, hair = rec.hair, isMale = rec.isMale,
        level = types.Actor.stats.level(p).current,
        content = contentList(),
    }))
end

-- Items made at runtime (enchanting, spellmaking, ...) have record ids that only exist on this machine.
-- Describe them so the other side can rebuild something that looks the same.
local EQUIP_KINDS = { 'Armor', 'Clothing', 'Weapon' }

local function describeGenerated(obj)
    for _, kind in ipairs(EQUIP_KINDS) do
        if obj.type == types[kind] then
            local ok, rec = pcall(function() return types[kind].record(obj) end)
            if ok and rec and rec.model then return { k = kind, n = rec.name, m = rec.model, i = rec.icon } end
        end
    end
    return nil
end

local function sendEquipment(force)
    local p = player()
    if not p then return end
    local slots, gen = {}, {}
    for slot, obj in pairs(types.Actor.getEquipment(p)) do
        local key = tostring(slot)
        slots[key] = obj.recordId
        if obj.recordId:lower():find('^generated:') then
            local d = describeGenerated(obj)
            if d then gen[key] = d else slots[key] = nil end
        end
    end
    local json = net.encodeJson({ t = 'equip', slots = slots, gen = gen })
    if force or json ~= lastEquipJson then
        lastEquipJson = json
        net.send(json)
    end
end

local myInput = { sneak = false, seq = 0, jseq = 0, reading = false, pitch = 0 } -- from player.lua (MpInput)
local lastSent -- { pos, time, cell } of the previous state message, for the velocity estimate

loot.init({
    send = function(t) net.send(net.encodeJson(t)) end,
    dlog = dlog,
    notice = notice,
    player = player,
    objectById = proxies.objectById,
    contentId = proxies.contentId,
    isAvatar = function(obj) return avatarIds[obj.id] ~= nil end,
    enabled = function() return settings:get('SharedLoot') ~= false end,
    gear = function() return settings:get('SharedLootGear') == true end,
    active = function() return net.isConnected() and inParty() end,
    containerOpen = function() return myInput.mode == 'Container' end,
})

local myLook = { fx = {} } -- from observe.lua (MpLook): the spell we have ready and the magic on us that shows

local function q(x) return math.floor(x + 0.5) end -- positions to whole units (1.4 cm): plenty, and short on the wire
local lastFlags = ''
local sentIdle = false

-- Where we are and what we look like doing it, 30 times a second while moving (less when standing still). Each message
-- carries our clock, so the others play us back evenly however the messages arrive (interp.lua).
local function sendState(force)
    local p = player()
    if not p then return end
    local pos = p.position
    local cell = cellKey(p.cell)
    local now = core.getRealTime()
    local vel = { 0, 0, 0 }
    if lastSent and lastSent.cell == cell and now > lastSent.time then
        local dt = now - lastSent.time
        local v = (pos - lastSent.pos) / dt
        if v:length() < 1500 then vel = { q(v.x), q(v.y), q(v.z) } end -- bigger means a teleport
    end
    local moving = vel[1] ~= 0 or vel[2] ~= 0 or vel[3] ~= 0
        or (lastSent and math.abs(p.rotation:getYaw() - lastSent.yaw) > 0.01)
        or (lastSent and math.abs((myInput.pitch or 0) - lastSent.pitch) > 0.02)
    local stance = types.Actor.getStance(p)
    local dead = types.Actor.isDead(p)
    local hp = 1
    pcall(function()
        local h = types.Actor.stats.dynamic.health(p)
        hp = math.max(0, math.min(1, h.current / math.max(1, h.base + h.modifier)))
    end)
    hp = math.floor(hp * 100 + 0.5) / 100
    local flags = table.concat({ cell, tostring(dead), stance, tostring(myInput.sneak), tostring(myInput.fly),
        tostring(myInput.reading), tostring(myInput.jseq), hp }, '|')
    local changed = flags ~= lastFlags
    -- standing still: a message now and then is enough (the first one after stopping goes at once, so we stop exactly)
    if not force and not changed and not moving and sentIdle and lastSent and now - lastSent.time < IDLE_INTERVAL then return end
    sentIdle = not moving
    lastFlags = flags
    lastSent = { pos = pos, time = now, cell = cell, yaw = p.rotation:getYaw(), pitch = myInput.pitch or 0 }
    net.send(net.encodeJson({
        t = 'state', ts = math.floor(now * 1000 + 0.5) / 1000,
        cell = cell,
        dead = dead or nil,
        pos = { q(pos.x), q(pos.y), q(pos.z) },
        vel = vel,
        yaw = math.floor(p.rotation:getYaw() * 1000 + 0.5) / 1000,
        stance = stance,
        rs = q(types.Actor.getRunSpeed(p)),
        hp = hp,
        sneak = myInput.sneak or nil, fly = myInput.fly or nil, jseq = myInput.jseq,
        reading = myInput.reading or nil, pitch = math.floor((myInput.pitch or 0) * 100 + 0.5) / 100,
    }))
end

------------------------------------------------------------------ avatars of group members

-- Where the player is shown right now: their messages played back evenly a little behind (interp.lua). r.pos, r.vel
-- and r.yaw are refreshed once a frame by sampleRemote.
local function sampleRemote(r, now)
    if not r.buf then return end
    local pos, vel, yaw = r.buf:sample(now)
    if not pos then return end
    r.pos = util.vector3(pos[1], pos[2], pos[3])
    r.vel = util.vector3(vel[1], vel[2], vel[3])
    r.yaw = yaw
end

local function stateVectors(r)
    local s = r.state
    local pos = r.pos or util.vector3(s.pos[1], s.pos[2], s.pos[3])
    return pos, util.transform.rotateZ(r.yaw or s.yaw or 0)
end

local function predictedPosition(r)
    if r.pos then return r.pos end
    local s = r.state
    return util.vector3(s.pos[1], s.pos[2], s.pos[3])
end

local function placeRemote(r)
    r.placing = true -- teleport is applied next frame; it also enables the object
    local pos, rot = stateVectors(r)
    r.obj:teleport(r.state.cell, pos, { rotation = rot })
end

local genCache = {} -- "<kind>|<model>|<name>" -> local record id (or false)

-- Rebuild a runtime-generated item as a local record: find a content-file record with the same model to
-- use as a template (so armor/clothing bodyparts are right), then rename it.
local function resolveGenerated(d)
    local key = d.k .. '|' .. d.m .. '|' .. tostring(d.n)
    if genCache[key] ~= nil then return genCache[key] or nil end
    local T = types[d.k]
    local template
    if T then
        for _, rec in ipairs(T.records) do
            if rec.model == d.m and not rec.id:lower():find('^generated:') then template = rec break end
        end
    end
    local id = false
    if template then
        local ok, record = pcall(function()
            return world.createRecord(T.createRecordDraft({ template = template, name = d.n }))
        end)
        if ok then id = record.id end
    end
    genCache[key] = id
    if not id then log('cannot rebuild item ' .. tostring(d.n) .. ' (' .. d.k .. ', model ' .. tostring(d.m) .. ')') end
    return id or nil
end

local function stockEquipment(r)
    local inv = types.Actor.inventory(r.obj)
    local slots = {}
    for slotStr, recordId in pairs(r.equip or {}) do
        local gen = r.gen and r.gen[slotStr]
        if gen then recordId = resolveGenerated(gen) end
        if recordId ~= nil then -- (written without goto)
            if inv:countOf(recordId) == 0 then
                local ok, item = pcall(world.createObject, recordId, 1)
                if ok then item:moveInto(inv) else log('cannot create item ' .. recordId) end
            end
            slots[tonumber(slotStr)] = recordId
        end
    end
    r.obj:sendEvent('MpEquip', { slots = slots })
    r.equipSent = true
end

local function spawnRemote(r)
    local draft = types.NPC.createRecordDraft({
        template = types.NPC.record(player()),
        name = r.profile.name, race = r.profile.race, class = r.profile.class,
        head = r.profile.head, hair = r.profile.hair, isMale = r.profile.isMale,
        isEssential = true,
    })
    local record = world.createRecord(draft)
    r.obj = world.createObject(record.id, 1)
    avatarIds[r.obj.id] = r.id
    -- Block the standard NPC dialogue and show our player menu instead. (The global onActivate *engine*
    -- handler only observes; blocking has to go through the Activation interface.)
    I.Activation.addHandlerForObject(r.obj, function()
        onAvatarActivated(r.id)
        return false
    end)
    r.obj:addScript(REMOTE_SCRIPT, {})
    placeRemote(r)
    r.equipSent = false
    r.lookDirty = true
    r.hidden = false
    log('spawned avatar for ' .. tostring(r.profile.name))
end

local function removeRemote(id)
    local r = remotes[id]
    if not r then return end
    if r.obj then
        avatarIds[r.obj.id] = nil
        if r.obj:isValid() then pcall(function() r.obj:remove() end) end
    end
    remotes[id] = nil
    proxies.removeOwner(id)
    log('remote player ' .. tostring(id) .. ' left')
end

clearRemotes = function()
    local ids = {}
    for id in pairs(remotes) do ids[#ids + 1] = id end
    for _, id in ipairs(ids) do removeRemote(id) end
    proxies.reset()
    shots.reset()
    loot.reset()
    releaseClock()
    players = {}
    party = { members = {}, leader = nil }
    vitals, invitesIn, invitesOut = {}, {}, {}
end

-- Avatars are visible only in our own cell.
local function setHidden(r, hidden, reason)
    if r.hidden ~= hidden then
        r.hidden = hidden
        dlog('avatar', tostring(r.id), hidden and 'hidden' or 'shown', reason or '')
    end
end

local DRIFT_NEAR, DRIFT_FAR, DRIFT_TIME = 45, 220, 0.35
local NUDGE, NUDGE_RATE = 35, 6 -- units off that get pulled in, and how fast (per second)
local FLOAT_HOLD, FLOAT_EASE = 6, 10 -- units of height difference that are put right / how fast the height eases (per second)

-- Desync protection: the avatar walks toward the predicted position, but collisions, steps or a bad
-- connection can leave it behind. Snap it back when it is far off, or somewhat off for too long.
local function checkDrift(r, dt)
    local want = predictedPosition(r)
    local off = (r.obj.position - want):length()
    if DEBUG then
        local m = r.metric or { n = 0, sum = 0, max = 0, t = 0 }
        r.metric = m
        m.n, m.sum, m.max, m.t = m.n + 1, m.sum + off, math.max(m.max, off), m.t + dt
        if m.t >= 1 then
            dlog('avatar', tostring(r.id), 'drift avg ' .. math.floor(m.sum / m.n) .. ' max ' .. math.floor(m.max))
            r.metric = nil
        end
    end
    -- A player who is off the ground for a while (levitating, flying) is shown with an avatar that levitates too, and its
    -- height eases toward theirs, up and down: a walking avatar would just fall and be put back over and over.
    local flying = r.state.fly and true or false
    if flying ~= (r.floating or false) then
        r.floating = flying
        dlog('avatar', tostring(r.id), flying and 'airborne: levitating' or 'back on the ground')
        r.obj:sendEvent('MpFloat', { on = flying })
    end
    if flying then
        local gap = want.z - r.obj.position.z
        if math.abs(gap) > FLOAT_HOLD then
            local z = r.obj.position.z + gap * math.min(1, dt * FLOAT_EASE)
            r.obj:teleport(r.state.cell, util.vector3(r.obj.position.x, r.obj.position.y, z), { rotation = r.obj.rotation })
        end
        -- (sideways it walks as usual: only a big miss is snapped back, below)
        if math.abs(gap) < DRIFT_NEAR then off = (util.vector2(r.obj.position.x - want.x, r.obj.position.y - want.y)):length() end
    end
    -- A gap the legs cannot close quickly (the engine eases an actor's starts, stops and turns; the player reversed or
    -- dodged): pull the avatar a part of the way each frame, so it never lags far and never has to jump back.
    local pos = r.obj.position
    local flat = util.vector2(want.x - pos.x, want.y - pos.y):length() -- (up and down is the jump's own business)
    if not flying and flat > NUDGE and off <= DRIFT_FAR and dt > 0 then
        local pull = math.min(1, dt * NUDGE_RATE) * (1 - NUDGE / flat)
        local to = util.vector3(pos.x + (want.x - pos.x) * pull, pos.y + (want.y - pos.y) * pull, pos.z)
        r.obj:teleport(r.state.cell, to, { rotation = r.obj.rotation })
        return
    end
    if off > DRIFT_FAR then
        r.offTime = DRIFT_TIME
    elseif off > DRIFT_NEAR then
        r.offTime = (r.offTime or 0) + dt
    else
        r.offTime = 0
    end
    if (r.offTime or 0) >= DRIFT_TIME then
        r.offTime = 0
        dlog('avatar', tostring(r.id), 'drifted ' .. math.floor(off) .. ' units, snapping')
        r.obj:teleport(r.state.cell, want, { rotation = util.transform.rotateZ(r.yaw or r.state.yaw or 0) })
    end
end

local function updateRemote(r, dt)
    local p = player()
    if not (p and r.profile and r.state) then return end
    local here = cellKey(p.cell)
    if r.state.cell ~= here then
        r.anims = nil -- what they did elsewhere is not replayed when they come in
        if r.obj and r.obj:isValid() and r.obj.enabled then r.obj.enabled = false end
        setHidden(r, true, 'cell ' .. tostring(r.state.cell) .. ' vs ' .. tostring(here))
        return
    end
    if not r.obj or not r.obj:isValid() then
        dlog('avatar', tostring(r.id), 'object invalid, respawning')
        spawnRemote(r)
        return
    end
    if r.obj.enabled then r.placing = false end
    if not r.obj.enabled then
        if not r.placing then placeRemote(r) end
        setHidden(r, true, 'disabled, re-placing')
        return
    end
    setHidden(r, false)
    checkDrift(r, dt)
    if not r.equipSent and r.equip then stockEquipment(r) end
    local dead = r.state.dead and true or false
    if dead ~= (r.wasDead or false) then
        r.wasDead = dead
        notice(nameOf(r.id) .. (dead and ' died.' or ' is back on their feet.'))
    end
    local v = r.vel
    r.obj:sendEvent('MpTarget', {
        dead = dead,
        pos = predictedPosition(r), yaw = r.yaw or r.state.yaw, stance = r.state.stance, at = core.getRealTime(),
        vel = v, speed = v and math.sqrt(v.x * v.x + v.y * v.y) or 0,
        sneak = r.state.sneak, rs = r.state.rs, pitch = r.state.pitch, reading = r.state.reading,
    })
    -- one jump per take-off (not for as long as the player is in the air)
    local jseq = r.state.jseq
    if jseq and r.lastJSeq and jseq ~= r.lastJSeq then r.obj:sendEvent('MpJump', {}) end
    r.lastJSeq = jseq or r.lastJSeq
    -- their animations, each when the position shown has reached the moment it was played
    if r.anims and #r.anims > 0 then
        local now = core.getRealTime()
        while r.anims[1] and r.anims[1].due <= now do
            local a = table.remove(r.anims, 1)
            r.obj:sendEvent('MpAnim', a.msg)
        end
    end
    if r.lookDirty then
        r.lookDirty = false
        r.obj:sendEvent('MpLook', r.look or {})
    end
end

------------------------------------------------------------------ party / ui plumbing

-- Harmful effects on us, for the party panel: { { n = name, c = 'p' poison | 'd' disease | 'x' other } }, at most
-- MAX_AILMENTS, most important first (so a healer sees what to cure).
local MAX_AILMENTS = 4
local function readAilments(p)
    local found, seen = {}, {}
    pcall(function()
        for _, spell in types.Actor.activeSpells(p) do
            local rec = core.magic.spells.records[spell.id]
            local disease = rec and (rec.type == core.magic.SPELL_TYPE.Disease or rec.type == core.magic.SPELL_TYPE.Blight)
            -- (a race's or birthsign's weaknesses are part of who they are, nothing to cure)
            local ability = rec and rec.type == core.magic.SPELL_TYPE.Ability
            for _, fx in ipairs(ability and {} or spell.effects) do
                local mgef = core.magic.effects.records[fx.id]
                if mgef and mgef.harmful and not seen[fx.name] then
                    seen[fx.name] = true
                    local c = fx.id == core.magic.EFFECT_TYPE.Poison and 'p' or disease and 'd' or 'x'
                    found[#found + 1] = { n = fx.name, c = c }
                end
            end
        end
    end)
    local rank = { p = 1, d = 2, x = 3 }
    table.sort(found, function(a, b) return rank[a.c] < rank[b.c] end)
    while #found > MAX_AILMENTS do found[#found] = nil end
    return found
end

-- Every effect on us for the party frames, as icons: { { e = effect id, k = kind, t = seconds left (-1: lasts) } }.
-- Kinds: 'p' poison, 'd' disease, 'b' damage over time (burning, freezing, shock, bleeding health), 'x' other harm,
-- '+' good. Harm first (a healer looks there), then the good ones; the soonest to end first in each.
local MAX_AURAS = 12
local BLEEDS = { firedamage = true, frostdamage = true, shockdamage = true, damagehealth = true, absorbhealth = true,
    sundamage = true }
local AURA_RANK = { p = 1, d = 2, b = 3, x = 4, ['+'] = 5 }
local function readAuras(p)
    local found, at = {}, {}
    pcall(function()
        for _, spell in types.Actor.activeSpells(p) do
            local rec = core.magic.spells.records[spell.id]
            -- abilities (a race's or birthsign's) are not worth a slot: they are always there
            local ability = rec and rec.type == core.magic.SPELL_TYPE.Ability
            local disease = rec and (rec.type == core.magic.SPELL_TYPE.Disease or rec.type == core.magic.SPELL_TYPE.Blight)
            if not ability then
                for _, fx in ipairs(spell.effects) do
                    local mgef = core.magic.effects.records[fx.id]
                    if mgef then
                        local k = fx.id == 'poison' and 'p' or disease and 'd' or BLEEDS[fx.id] and mgef.harmful and 'b'
                            or mgef.harmful and 'x' or '+'
                        local left = (fx.duration and fx.duration > 0 and fx.durationLeft) and math.floor(fx.durationLeft + 0.5) or -1
                        local i = at[fx.id]
                        if not i then
                            found[#found + 1] = { e = fx.id, k = k, t = left }
                            at[fx.id] = #found
                        elseif left > found[i].t and found[i].t >= 0 then found[i].t = left end -- (the longest of the same)
                    end
                end
            end
        end
    end)
    table.sort(found, function(a, b)
        if AURA_RANK[a.k] ~= AURA_RANK[b.k] then return AURA_RANK[a.k] < AURA_RANK[b.k] end
        local ta, tb = a.t < 0 and math.huge or a.t, b.t < 0 and math.huge or b.t
        if ta ~= tb then return ta < tb end
        return a.e < b.e
    end)
    while #found > MAX_AURAS do found[#found] = nil end
    return found
end

local function readVitals()
    local p = player()
    if not p then return nil end
    local ok, v = pcall(function()
        local d = types.Actor.stats.dynamic
        local h, m, f = d.health(p), d.magicka(p), d.fatigue(p)
        local function r(x) return math.floor(x + 0.5) end
        return {
            t = 'vitals',
            h = r(h.current), hm = r(h.base + h.modifier),
            m = r(m.current), mm = r(m.base + m.modifier),
            f = r(f.current), fm = r(f.base + f.modifier),
            bad = readAilments(p),
            fx = readAuras(p),
        }
    end)
    return ok and v or nil
end

local function pushUi()
    local members = {}
    for _, id in ipairs(party.members) do
        if id ~= myId then
            local v = vitals[id] or {}
            local r = remotes[id]
            local p = player()
            local dist
            if r and r.state and p and r.state.cell == cellKey(p.cell) and r.pos then dist = math.floor((r.pos - p.position):length() / 70 + 0.5) end -- (in metres, roughly)
            members[#members + 1] = {
                id = id, name = nameOf(id), leader = party.leader == id, dist = dist, lvl = r and r.profile and r.profile.level,
                dead = r and r.state and r.state.dead and true or nil,
                h = v.h, hm = v.hm, m = v.m, mm = v.mm, f = v.f, fm = v.fm, bad = v.bad, fx = v.fx,
            }
        end
    end
    local invites = {}
    for id, exp in pairs(invitesIn) do
        invites[#invites + 1] = { id = id, name = nameOf(id), left = math.max(0, math.floor(exp - clock)) }
    end
    toUi('MpUiState', {
        connected = net.isConnected(), me = myId, leader = party.leader == myId,
        size = #party.members, members = members, invites = invites, refused = refusedBy, hosting = hosting() and relay.isRunning(),
    })
end

local function onParty(msg)
    local old = {}
    for _, id in ipairs(party.members) do old[id] = true end
    local members = msg.members or {}
    local new = {}
    for _, id in ipairs(members) do new[id] = true end
    for id in pairs(old) do
        if not new[id] and id ~= myId then
            proxies.removeOwner(id)
            vitals[id] = nil
            notice(nameOf(id) .. ' left the party.')
        end
    end
    for id in pairs(new) do
        if not old[id] and id ~= myId then
            invitesIn[id], invitesOut[id] = nil, nil
            notice(nameOf(id) .. ' joined the party.')
        end
    end
    if #members == 0 and #party.members > 1 then notice('You are no longer in a party.') end
    if #members > 1 and #party.members <= 1 then notice('You joined a party.') end
    party = { members = members, leader = msg.leader }
    log('party: ' .. #members .. ' member(s)')
    for _, m in ipairs(members) do invitesIn[m], invitesOut[m] = nil, nil end -- no invites between party members
    pushUi()
end

local JOIN_REFUSALS = {
    version = 'This host runs a different version of the multiplayer mod. Update the mod on both sides.',
    password = 'Wrong password. Set it in the Multiplayer menu (or Options > Scripts > Multiplayer).',
    full = 'The game is full.',
    banned = 'The host banned you from this game.',
}

local REASONS = {
    unknown = 'That player is not online.',
    busy = 'That player is already in a party.',
    full = 'A party can have at most ' .. MAX_PARTY .. ' players.',
    expired = 'That invite has expired.',
    slow = 'Slow down: wait a moment before inviting again.',
    already = 'That player is already in your party.',
}

local function onCommand(d)
    local cmd = d.cmd
    dlog('command', cmd, tostring(d.id), tostring(d.scope), tostring(d.text))
    if cmd == 'chat' then
        if not net.isConnected() then notice('Not connected to the server.') return end
        if d.scope == 'party' and not inParty() then notice('You are not in a party.') return end
        net.send(net.encodeJson({ t = 'chat', scope = d.scope, text = d.text }))
        chatLine(d.scope, 'You', d.text, true)
    elseif cmd == 'toggle' then
        -- /markers and /arrows: show or hide other players on the maps / their nameplates
        local key = d.what == 'markers' and 'ShowMapMarkers' or 'ShowPlayerArrows'
        local want
        if d.state == 'on' then want = true elseif d.state == 'off' then want = false else want = settings:get(key) == false end
        settings:set(key, want)
        notice((d.what == 'markers' and 'Other players on the map and minimap: ' or 'Nameplates over other players: ')
            .. (want and 'on' or 'off') .. '.')
    elseif cmd == 'ip' then
        if not hostInfo then
            notice('You are not hosting. To join someone, enter their address in the Multiplayer menu.')
        elseif hostInfo.looking then
            notice('Still looking up your address...')
        else
            hostInfo.looking = true
            net.lookupPublicAddress() -- contacts api.ipify.org
            notice('Asking api.ipify.org for your internet address...')
        end
    elseif cmd == 'admin' then
        net.send(net.encodeJson({ t = 'admin', cmd = d.sub, name = d.name, text = d.text, password = d.password }))
    elseif cmd == 'emote' then
        if not net.isConnected() then notice('Not connected to the server.') return end
        net.send(net.encodeJson({ t = 'chat', scope = 'emote', text = d.text }))
        chatLine('emote', 'You', d.text, true)
    elseif cmd == 'goto' then
        -- walk up to a party member: teleport next to them (their position is where their avatar would stand)
        local target
        for id, r in pairs(remotes) do
            if r.profile and nameOf(id):lower() == tostring(d.name):lower() then target = id end
        end
        if not target then notice('No player named ' .. tostring(d.name) .. ' is online.') return end
        local s = remotes[target].state
        if not s then notice(nameOf(target) .. ' has not told us where they are yet.') return end
        player():teleport(s.cell, util.vector3(s.pos[1] + 90, s.pos[2], s.pos[3]))
        notice('You joined ' .. nameOf(target) .. '.')
    elseif cmd == 'reply' then
        if not lastWhisperFrom or not remotes[lastWhisperFrom] then notice('Nobody has whispered to you yet.') return end
        net.send(net.encodeJson({ t = 'chat', scope = 'whisper', to = lastWhisperFrom, text = d.text }))
        chatLine('whisper', 'To ' .. nameOf(lastWhisperFrom), d.text, true)
    elseif cmd == 'party_kick' then
        local target
        for id, r in pairs(remotes) do
            if r.profile and nameOf(id):lower() == tostring(d.name):lower() then target = id end
        end
        if not target then notice('No player named ' .. tostring(d.name) .. ' is online.') return end
        net.send(net.encodeJson({ t = 'party_kick', id = target }))
    elseif cmd == 'whisper' then
        local target
        for _, pl in ipairs(players) do
            if pl.id ~= myId and tostring(pl.name):lower() == d.to:lower() then target = pl.id end
        end
        if not target then notice('No player named ' .. d.to .. ' is online.') return end
        net.send(net.encodeJson({ t = 'chat', scope = 'whisper', to = target, text = d.text }))
        chatLine('whisper', 'To ' .. d.to, d.text, true)
    elseif cmd == 'invite' then
        if isMember(d.id) then notice(nameOf(d.id) .. ' is already in your party.') return end
        local out = invitesOut[d.id]
        if out and out > clock then notice('You already invited ' .. nameOf(d.id) .. '.') return end
        net.send(net.encodeJson({ t = 'invite', to = d.id }))
    elseif cmd == 'accept' then
        -- guards: only answer an invite that is really pending, and never while already in a party
        local exp = invitesIn[d.id]
        if not exp or exp <= clock then dlog('ignored accept without a pending invite from', tostring(d.id)) return end
        if inParty() then invitesIn[d.id] = nil return end
        net.send(net.encodeJson({ t = 'accept', from = d.id }))
        invitesIn[d.id] = nil
    elseif cmd == 'decline' then
        if not invitesIn[d.id] then return end
        net.send(net.encodeJson({ t = 'decline', from = d.id }))
        invitesIn[d.id] = nil
        notice('Declined the invite from ' .. nameOf(d.id) .. '.')
    elseif cmd == 'leave' then
        net.send(net.encodeJson({ t = 'leave_party' }))
    elseif cmd == 'sync' then
        pushUi()
    elseif cmd == 'configure' then
        sessionActive = true
        if d.bot ~= nil then
            botWanted = d.bot and true or false
            if not botWanted then bot.stop() end
        end
        if d.mode and d.mode ~= settings:get('Mode') then settings:set('Mode', d.mode) end
        if d.password ~= nil and d.password ~= settings:get('Password') then settings:set('Password', d.password) end
        if d.host and d.host ~= settings:get('Host') then settings:set('Host', d.host) end
        if d.port and d.port ~= settings:get('Port') then settings:set('Port', d.port) end
    elseif cmd == 'trade_send' then
        if net.isConnected() then net.send(net.encodeJson(d.msg)) end
    end
    pushUi()
end

local function resolveInviter(name)
    -- /accept [name]: by name if given, otherwise the only/most recent invite
    local best, bestExp
    for id, exp in pairs(invitesIn) do
        if (not name or nameOf(id):lower() == name:lower()) and (not bestExp or exp > bestExp) then
            best, bestExp = id, exp
        end
    end
    return best
end

onAvatarActivated = function(rid)
    dlog('avatar activated', tostring(rid))
    local expIn = invitesIn[rid]
    local expOut = invitesOut[rid]
    toUi('MpAvatar', {
        id = rid, name = nameOf(rid),
        inMyParty = isMember(rid),
        invitedMe = expIn ~= nil and expIn > clock,
        iInvited = expOut ~= nil and expOut > clock,
        myPartySize = #party.members,
        connected = net.isConnected(),
    })
end

------------------------------------------------------------------ messages

local function onMessage(msg)
    lastServerMessage = core.getRealTime()
    local t = msg.t
    if t == 'hello' then
        myId = msg.id
        log('my id is ' .. tostring(myId))
        -- who we are, before anything else: the host checks version, password, player limit and bans
        net.send(net.encodeJson({ t = 'join', proto = relay.PROTO, name = types.NPC.record(player()).name,
            password = settings:get('Password') or '', token = resumeToken }))
        sendProfile()
        sendEquipment(true)
        net.send(net.encodeJson({ t = 'look', fx = myLook.fx, spell = myLook.spell }))
        return
    end
    if t == 'join_result' then
        if msg.ok then
            resumeToken = msg.token or resumeToken
            if msg.id then myId = msg.id end -- a resumed session keeps the id everybody knows us by
            if msg.resumed then notice('Reconnected.', 'Menu Click') end
            if msg.motd and not msg.resumed then notice('[Host] ' .. msg.motd) end
        else
            refusedBy = msg.reason or 'refused'
            resumeToken = nil
            notice(JOIN_REFUSALS[refusedBy] or ('The host refused you: ' .. tostring(refusedBy)))
            net.disconnect()
            clearRemotes()
        end
        return
    end
    if t == 'kicked' then
        refusedBy = msg.reason or 'kicked'
        resumeToken = nil
        notice(msg.reason == 'banned' and 'The host banned you from this game.' or 'The host kicked you out of this game.')
        net.disconnect()
        clearRemotes()
        return
    end
    if t == 'mods' then clientmods.onServerMods(msg.list) return end
    if t == 'modchunk' then clientmods.onChunk(msg) return end
    if t == 'notice' then notice(tostring(msg.text or '')) return end
    if t == 'ping' then net.send(net.encodeJson({ t = 'pong', n = msg.n })) return end
    if t == 'players' then
        players = type(msg.list) == 'table' and msg.list or {}
        -- where each of them is and whether they are with us, for the Players window
        local p = player()
        local here = p and cellKey(p.cell)
        for _, pl in ipairs(players) do
            local r = remotes[pl.id]
            if r and r.state then
                pl.where = r.state.cell == '' and 'Outside' or r.state.cell
                pl.near = r.state.cell == here or nil
            end
            pl.party = isMember(pl.id) or nil
        end
        toUi('MpPlayers', { list = players, me = myId, partySize = #party.members })
        local parts = {}
        for _, pl in ipairs(players) do parts[#parts + 1] = tostring(pl.name) .. '#' .. tostring(pl.id) .. '=' .. tostring(pl.ping) .. 'ms' end
        dlog('players: ' .. table.concat(parts, ', '))
        return
    end
    if t == 'world' then
        if not hosting() then applyWorld(msg) end
        return
    end
    if t == 'party' then onParty(msg) return end
    if t == 'invite' then
        -- guards: nobody in our party needs inviting, and while we are in a party invites cannot be accepted anyway
        if isMember(msg.from) or inParty() then dlog('ignored invite from', tostring(msg.from)) return end
        invitesIn[msg.from] = clock + INVITE_TTL
        notice(nameOf(msg.from) .. ' invited you to a party. Activate them, or type /accept.', 'Menu Click')
        pushUi()
        return
    end
    if t == 'invite_result' then
        if msg.ok then
            invitesOut[msg.to] = clock + INVITE_TTL
            notice('Invite sent to ' .. nameOf(msg.to) .. '.')
        else
            -- the same failure is shown at most once every few seconds
            local key = tostring(msg.reason) .. tostring(msg.to)
            if lastFailure[key] and clock - lastFailure[key] < 5 then return end
            lastFailure[key] = clock
            notice(REASONS[msg.reason] or ('Invite failed: ' .. tostring(msg.reason)))
        end
        return
    end
    if t == 'declined' then
        invitesOut[msg.id] = nil
        notice(nameOf(msg.id) .. ' declined your invite.')
        return
    end
    if t == 'chat' then
        if msg.scope == 'whisper' then lastWhisperFrom = msg.id end
        chatLine(msg.scope == 'party' and 'party' or msg.scope == 'whisper' and 'whisper' or msg.scope == 'emote' and 'emote' or 'global',
            nameOf(msg.id), msg.text, false)
        return
    end
    if t == 'away' then
        -- lost their connection: their avatar goes, their party place and everything else stays for a while
        notice(nameOf(msg.id) .. ' lost connection. Their place is kept for ' .. tostring(msg.grace or 90) .. ' seconds.')
        toUi('MpTrade', { t = 'trade_cancel', id = msg.id, name = nameOf(msg.id), left = true })
        removeRemote(msg.id)
        return
    end
    if t == 'back' then
        notice(nameOf(msg.id) .. ' is back.')
        return
    end
    if t == 'leave' then
        notice(nameOf(msg.id) .. ' left.')
        toUi('MpTrade', { t = 'trade_cancel', id = msg.id, name = nameOf(msg.id), left = true })
        invitesIn[msg.id], invitesOut[msg.id] = nil, nil
        vitals[msg.id] = nil
        removeRemote(msg.id)
        return
    end
    if type(t) == 'string' and t:sub(1, 6) == 'trade_' then
        msg.name = nameOf(msg.id)
        toUi('MpTrade', msg)
        return
    end
    if t == 'vitals' then vitals[msg.id] = msg return end
    if t == 'shot' then onShot(msg) return end
    if t == 'loot' then loot.onMessage(msg) return end
    if t == 'aid' then onAid(msg) return end
    if proxies.onMessage(msg) then return end
    local id = msg.id
    if not id then return end
    local r = remotes[id]
    if not r then
        r = { id = id }
        remotes[id] = r
        log('player ' .. tostring(id) .. ' appeared')
    end
    if t == 'profile' then
        if not r.profile then notice(tostring(msg.name) .. ' joined.') end
        if r.profile and msg.level and r.profile.level and msg.level > r.profile.level then
            notice(tostring(msg.name) .. ' reached level ' .. tostring(msg.level) .. '!', 'skillraise')
        end
        r.profile = msg
        if not r.contentChecked and type(msg.content) == 'table' then
            r.contentChecked = true
            local mine = {}
            for _, f in ipairs(contentList()) do mine[f] = true end
            local missing = {}
            for _, f in ipairs(msg.content) do if not mine[f] then missing[#missing + 1] = f end end
            if #missing > 0 then
                notice(nameOf(id) .. ' uses content you do not have (' .. table.concat(missing, ', ')
                    .. '); some of their gear or actors may not appear.')
            end
        end
    elseif t == 'equip' then r.equip, r.gen, r.equipSent = msg.slots, msg.gen, false
    elseif t == 'state' and type(msg.pos) == 'table' then
        local now = core.getRealTime()
        if r.state and r.state.cell ~= msg.cell and r.buf then r.buf:reset() end -- a door: nothing to blend from
        r.buf = r.buf or interp.new()
        r.buf:push(tonumber(msg.ts), now, msg.pos, type(msg.vel) == 'table' and msg.vel or nil, tonumber(msg.yaw) or 0)
        r.state = msg
        r.stateAt = now
    elseif t == 'anim' and type(msg.g) == 'string' then
        -- played when the position we show reaches the time they played it, so a swing lands where they stood
        local now = core.getRealTime()
        local due = now
        if r.buf and r.buf.offset and tonumber(msg.ts) then
            due = math.max(now, math.min(now + 0.4, msg.ts + r.buf.offset + r.buf:delay()))
        end
        r.anims = r.anims or {}
        if #r.anims < 16 then r.anims[#r.anims + 1] = { due = due, msg = { g = msg.g, o = msg.o } } end
    elseif t == 'look' then
        r.look = { fx = type(msg.fx) == 'table' and msg.fx or {}, spell = type(msg.spell) == 'table' and msg.spell or nil }
        r.lookDirty = true
    elseif t == 'ev' then
        onEv(r, msg)
    end
end

-- True if every effect of the spell is beneficial (nothing that could be used to hurt a group member).
spellHelps = function(id)
    local ok, helps = pcall(function()
        local rec = core.magic.spells.records[id]
        if not rec or #rec.effects == 0 then return false end
        for _, fx in ipairs(rec.effects) do
            if fx.effect.harmful then return false end
        end
        return true
    end)
    return ok and helps
end

local lastAid = {} -- sender id -> real time of the last spell we accepted from them
local AID_INTERVAL = 0.4

-- A group member cast a helpful spell at our avatar in their world: it takes effect on us.
onAid = function(msg)
    local p = player()
    if not p or not isMember(msg.id) or type(msg.spell) ~= 'string' or not spellHelps(msg.spell) then return end
    local now = core.getRealTime()
    if lastAid[msg.id] and now - lastAid[msg.id] < AID_INTERVAL then return end
    lastAid[msg.id] = now
    local rec = core.magic.spells.records[msg.spell]
    local indices = {}
    for i = 0, #rec.effects - 1 do indices[#indices + 1] = i end
    local ok, err = pcall(function() types.Actor.activeSpells(p):add({ id = msg.spell, effects = indices }) end)
    if ok then
        notice(nameOf(msg.id) .. ' cast ' .. tostring(rec.name) .. ' on you.', 'restoration hit')
    else
        dlog('aid', 'could not apply', msg.spell, tostring(err))
    end
end

-- Another player shot or cast something: show it if they are in our cell (cosmetic only).
-- k = 'bolt' / 'arrow' fly from msg.from to msg.to (shots.lua); 'impact' is where one of their bolts really hit in their
-- world (the explosion moves there); every spell also shows its cast effects on their avatar, and a spell on
-- themselves (k = 'cast', range self) lands on them.
onShot = function(msg)
    local r = remotes[msg.id]
    local p = player()
    if not r or not p or not r.state or r.state.cell ~= cellKey(p.cell) then return end
    if msg.k == 'impact' then shots.onImpact(msg) return end
    if (msg.k == 'bolt' and msg.rec) or msg.k == 'arrow' then shots.onShot(msg) end
    if msg.k ~= 'arrow' and r.obj and r.obj:isValid() and r.obj.enabled then
        r.obj:sendEvent('MpCastVfx', { fx = msg.fx, range = msg.range })
    end
end

-- Something happened to another player (observe.lua): damage, healing, a skill rising. Shown over their head when we
-- can see them; a level-up or skill of a party member also goes to chat (relay sends the party line).
onEv = function(r, msg)
    local p = player()
    if not p or not r.state or r.state.cell ~= cellKey(p.cell) then return end
    if msg.k ~= 'dmg' and msg.k ~= 'heal' and msg.k ~= 'skill' then return end
    toUi('MpFloatText', { id = r.id, k = msg.k, n = tonumber(msg.n), s = type(msg.s) == 'string' and msg.s:sub(1, 40) or nil })
end

-- Our own bolts: where they really hit, so the others' explosion is in the right place (the aim was only a guess).
local boltsInFlight = {} -- shot ids, oldest first
local function onOwnBoltHit(projectile, hit)
    local p = player()
    local info = projectile and projectile.userData
    if not p or not info or info.caster ~= p or #boltsInFlight == 0 then return end
    local sid = table.remove(boltsInFlight, 1)
    local pos = hit and hit.hitPos
    if not pos or not net.isConnected() then return end
    net.send(net.encodeJson({ t = 'shot', k = 'impact', sid = sid,
        to = { math.floor(pos.x + 0.5), math.floor(pos.y + 0.5), math.floor(pos.z + 0.5) } }))
end
pcall(function() I.Projectiles.addOnProjectileHitHandler(I.Projectiles.TYPES.Magic, onOwnBoltHit) end)

------------------------------------------------------------------ network callbacks (see netlib.lua)

net.callbacks = {
    onConnected = function() log('connected'); retry = 0; lastServerMessage = core.getRealTime(); pushUi() end,
    onDisconnected = function(reason)
        log('disconnected: ' .. tostring(reason))
        clientmods.reset()
        clearRemotes()
        notice('Disconnected from the server.')
        pushUi()
    end,
    onMessage = function(line)
        local ok, msg = pcall(markup.decodeYaml, line)
        if ok and type(msg) == 'table' then onMessage(msg) end
    end,
    onClientConnected = relay.onClientConnected,
    onClientDisconnected = relay.onClientDisconnected,
    onClientMessage = relay.onClientMessage,
}

------------------------------------------------------------------ handlers

return {
    engineHandlers = {
        onPlayerAdded = function() connect() end,
        onSave = function()
            releaseClock() -- never save with time stopped
            -- stand-ins are saved with the world; remember them so they can be cleaned up on load
            local objs = {}
            for _, r in pairs(remotes) do if r.obj then objs[#objs + 1] = r.obj end end
            for _, o in ipairs(proxies.savedObjects()) do objs[#objs + 1] = o end
            for _, o in ipairs(shots.savedObjects()) do objs[#objs + 1] = o end
            return { objs = objs, kills = proxies.getPendingKills(), loot = loot.getPending() }
        end,
        onLoad = function(saved)
            remotes, avatarIds, lastEquipJson = {}, {}, nil
            party, vitals, invitesIn, invitesOut = { members = {}, leader = nil }, {}, {}, {}
            proxies.reset()
            shots.reset()
            proxies.setPendingKills(saved and saved.kills)
            loot.reset()
            loot.setPending(saved and saved.loot)
            for _, obj in ipairs(saved and saved.objs or {}) do
                if obj:isValid() then pcall(function() obj:remove() end) end
            end
        end,
        onUpdate = function(gameDt)
            -- network timers run on real time: while this player has a menu open the game is paused (dt = 0),
            -- but the others must still hear from us
            local realNow = core.getRealTime()
            local dt = math.min(0.5, realNow - (lastReal or realNow))
            lastReal = realNow
            clock = clock + dt
            relay.update(dt)
            clientmods.update()
            net.update(dt)
            if botWanted and hosting() and relay.isRunning() and net.isConnected() then
                if not bot.isRunning() then
                    bot.start({ player = player, cellKey = cellKey })
                    notice('A test player has joined: look for the green arrow near you.')
                end
                bot.update(dt)
            end
            if hostInfo and hostInfo.looking then
                local address, err = net.getPublicAddress()
                if address or err then
                    hostInfo.looking = false
                    hostInfo.lan = net.lanAddress
                    log('address lookup: ' .. tostring(address) .. ' ' .. tostring(err) .. ' lan ' .. tostring(net.lanAddress))
                    if address then
                        hostInfo.public, hostInfo.error = address, nil
                        notice('Others join you at ' .. address .. ':' .. tostring(hostInfo.port)
                            .. ' (forward TCP port ' .. tostring(hostInfo.port) .. ' on your router to this computer, '
                            .. tostring(hostInfo.lan or '?') .. '). Same network: ' .. tostring(hostInfo.lan or '?') .. ':'
                            .. tostring(hostInfo.port) .. '.')
                    else
                        hostInfo.error = err
                        notice('Could not look up your internet address (' .. tostring(err)
                            .. '). Type /ip to try again. Same network: ' .. tostring(hostInfo.lan or '?') .. ':' .. tostring(hostInfo.port) .. '.')
                    end
                end
            end
            if hosting() and net.isConnected() then
                worldTimer = worldTimer + dt
                if worldTimer >= WORLD_INTERVAL then worldTimer = 0; sendWorld() end
            end
            for _, r in pairs(remotes) do sampleRemote(r, realNow) end
            -- nameplates over the other players: where they are shown, sent every frame so the overlay does not lag or jump
            do
                local p = player()
                local list = {}
                if p then
                    local here = cellKey(p.cell)
                    if settings:get('ShowPlayerArrows') ~= false then
                        for id, r in pairs(remotes) do
                            if r.state and r.profile and r.state.cell == here then
                                -- hidden by magic (invisible, or mostly chameleon): no nameplate, unless they are with us
                                local hide = false
                                for _, f in ipairs(r.look and r.look.fx or {}) do
                                    if f.e == 'invisibility' or (f.e == 'chameleon' and (tonumber(f.m) or 0) >= 75) then hide = true end
                                end
                                list[#list + 1] = { id = id, pos = predictedPosition(r), name = nameOf(id), group = isMember(id),
                                    hp = tonumber(r.state.hp), lvl = r.profile.level, cls = r.profile.class, race = r.profile.race,
                                    dead = r.state.dead and true or false, hide = hide and not isMember(id),
                                    obj = r.obj and r.obj:isValid() and r.obj or nil }
                            end
                        end
                    end
                end
                toUi('MpAvatars', { list = list })
            end
            -- each part on its own: one failing (an object removed under it) must not stop the rest of the frame, least of
            -- all sending our own position
            guarded('proxies', proxies.onUpdate, gameDt)
            guarded('loot', loot.onUpdate, dt) -- (real time: looting happens in a window that pauses the game)
            guarded('shots', shots.onUpdate, gameDt)
            for _, r in pairs(remotes) do guarded('avatar', updateRemote, r, gameDt) end
            if net.isConnected() and core.getRealTime() - lastServerMessage > SERVER_SILENCE then
                -- the host stopped answering (crash, cable, router): do not stay "connected" to nothing
                log('host went silent, reconnecting')
                notice('Lost contact with the host. Reconnecting...')
                clearRemotes()
                net.disconnect()
                retry = RETRY_INTERVAL
            end
            if net.isConnected() then
                sendTimer = sendTimer + dt
                equipTimer = equipTimer + dt
                vitalsTimer = vitalsTimer + dt
                if sendTimer >= SEND_INTERVAL then sendTimer = 0; sendState(false) end
                if equipTimer >= EQUIP_INTERVAL then
                    equipTimer = 0
                    sendEquipment(false)
                    local p = player()
                    if p then
                        local level = types.Actor.stats.level(p).current
                        if lastLevel and level ~= lastLevel then sendProfile() end -- others see level-ups
                        lastLevel = level
                    end
                end
                if vitalsTimer >= VITALS_INTERVAL then
                    vitalsTimer = 0
                    if inParty() then
                        local v = readVitals()
                        if v then net.send(net.encodeJson(v)) end
                    end
                end
            elseif sessionActive and player() and settings:get('Enabled') and not refusedBy then
                retry = retry + dt
                if retry >= RETRY_INTERVAL then
                    retry = 0
                    connect()
                end
            end
            uiTimer = uiTimer + dt
            if uiTimer >= UI_INTERVAL then
                uiTimer = 0
                -- every online player on the map, wherever they are
                local markers = {}
                if settings:get('ShowMapMarkers') ~= false then
                    local me = player()
                    for id, r in pairs(remotes) do
                        if r.state and r.profile then
                            -- how far above or below us they are (the maps are flat), for the tooltip
                            local info
                            if me and r.state.pos[3] then
                                local dz = r.state.pos[3] - me.position.z
                                if math.abs(dz) < 60 then info = 'level with you'
                                else info = string.format('%d %s you', math.floor(math.abs(dz) / 10 + 0.5) * 10, dz > 0 and 'above' or 'below') end
                            end
                            markers[#markers + 1] = { cell = r.state.cell, x = r.state.pos[1], y = r.state.pos[2],
                                name = nameOf(id), group = isMember(id), info = info }
                        end
                    end
                end
                toUi('MpMarkers', { list = markers })
                toUi('MpHostInfo', hostInfo or {})

                for id, exp in pairs(invitesIn) do if exp <= clock then invitesIn[id] = nil end end
                for id, exp in pairs(invitesOut) do if exp <= clock then invitesOut[id] = nil end end
                pushUi()
            end
        end,
        onNetEvent = net.onEvent,
    },
    eventHandlers = {
        MpInput = function(d)
            -- sneaking, a jump, opening a menu: the others see it at once, not with the next position
            local now = d.sneak ~= myInput.sneak or d.jseq ~= myInput.jseq or d.reading ~= myInput.reading or d.fly ~= myInput.fly
            myInput = d
            if now and net.isConnected() then sendState(true) end
        end,
        -- An animation our character played (observe.lua): the others' copy of us plays it too
        MpAnim = function(d)
            if not net.isConnected() then return end
            net.send(net.encodeJson({ t = 'anim', g = d.g, o = d.o, ts = math.floor((d.ts or core.getRealTime()) * 1000 + 0.5) / 1000 }))
        end,
        -- The spell we have ready and the magic on us that shows (observe.lua)
        MpLook = function(d)
            myLook = { fx = d.fx or {}, spell = d.spell }
            if net.isConnected() then net.send(net.encodeJson({ t = 'look', fx = myLook.fx, spell = myLook.spell })) end
        end,
        -- Damage we took, healing, a skill that went up (observe.lua): numbers over our head in the others' game
        MpEv = function(d)
            if net.isConnected() then net.send(net.encodeJson({ t = 'ev', k = d.k, n = d.n, s = d.s })) end
            if d.k == 'skill' and inParty() then -- (our own game already says it to us)
                net.send(net.encodeJson({ t = 'chat', scope = 'party', text = string.format('[%s rose to %s]', tostring(d.s), tostring(d.n or '?')) }))
            end
        end,
        -- Our player shot an arrow or cast a spell (player.lua works out the details): tell the others
        MpShot = function(d)
            local p = player()
            if not p or not net.isConnected() then return end
            local target = d.target
            d.target = nil -- an object cannot go on the wire
            -- cast at a group member's avatar: if the spell only helps, it takes effect on the real player
            if d.aid and target and avatarIds[target.id] then
                local to = avatarIds[target.id]
                local touchOk = not d.touch or (target.position - p.position):length() < 300
                if touchOk and isMember(to) and spellHelps(d.aid) then
                    net.send(net.encodeJson({ t = 'aid', to = to, spell = d.aid }))
                end
            end
            d.aid, d.touch = nil, nil
            d.t = 'shot'
            if d.k == 'bolt' and d.sid then
                boltsInFlight[#boltsInFlight + 1] = d.sid
                while #boltsInFlight > 8 do table.remove(boltsInFlight, 1) end
            end
            net.send(net.encodeJson(d))
        end,
        MpSnap = function(d)
            if proxies.onSnap(d.actor) then dlog('proxy snapped') return end
            for _, r in pairs(remotes) do
                if r.obj == d.actor and r.state then
                    dlog('avatar', tostring(r.id), 'snapped (too far from target)')
                    placeRemote(r)
                end
            end
        end,
        MpProxyDamage = proxies.onProxyDamage,
        MpProxyDied = proxies.onProxyDied,
        MpAvatarHit = proxies.onAvatarHit,
        -- Player-to-player trade: d.give / d.get are { [recordId] = count }. Items leave this world's
        -- inventory and are recreated from their record id on the other side (conditions/enchant charges reset,
        -- generated records can't be traded).
        MpTradeExec = function(d)
            local p = player()
            local function fail(err) toUi('MpTradeResult', { ok = false, err = err }) end
            if not p then return fail('no player') end
            local inv = types.Actor.inventory(p)
            -- items we made while playing are found by the key of their description
            local ownGen = {} -- key -> list of objects
            for _, obj in ipairs(inv:getAll()) do
                if obj.recordId:lower():find('^generated:') then
                    local desc = items.describe(obj)
                    if desc then
                        local key = items.key(desc)
                        ownGen[key] = ownGen[key] or {}
                        table.insert(ownGen[key], obj)
                    end
                end
            end
            local function have(rid)
                if not items.isKey(rid) then return inv:countOf(rid) end
                local n = 0
                for _, obj in ipairs(ownGen[rid] or {}) do n = n + obj.count end
                return n
            end
            for rid, count in pairs(d.give or {}) do
                if have(rid) < count then return fail('You no longer have ' .. count .. ' x ' .. tostring(rid)) end
            end
            local created = {}
            for rid, count in pairs(d.get or {}) do
                local recordId = rid
                if items.isKey(rid) then
                    local desc = d.gens and d.gens[rid]
                    if not desc or items.key(desc) ~= rid then
                        for _, o in ipairs(created) do pcall(function() o:remove() end) end
                        return fail('Unknown item ' .. tostring(rid))
                    end
                    local why
                    recordId, why = items.rebuild(desc)
                    if not recordId then
                        for _, o in ipairs(created) do pcall(function() o:remove() end) end
                        log('cannot rebuild traded item ' .. items.name(desc) .. ': ' .. tostring(why))
                        return fail('Cannot create ' .. items.name(desc))
                    end
                end
                local ok, obj = pcall(world.createObject, recordId, count)
                if not ok or count < 1 then
                    for _, o in ipairs(created) do pcall(function() o:remove() end) end
                    return fail('Unknown item ' .. tostring(rid))
                end
                created[#created + 1] = obj
            end
            for rid, count in pairs(d.give or {}) do
                local left = count
                local objs = items.isKey(rid) and (ownGen[rid] or {}) or inv:findAll(rid)
                for _, obj in ipairs(objs) do
                    if left <= 0 then break end
                    local n = math.min(left, obj.count)
                    obj:remove(n)
                    left = left - n
                end
            end
            for _, obj in ipairs(created) do obj:moveInto(inv) end
            toUi('MpTradeResult', { ok = true })
        end,
        MpCmd = function(d)
            if d.cmd == 'modsAnswer' then clientmods.answer(d.accept and true or false) return end
            if d.cmd == 'modsRestart' then clientmods.restart() return end
            if d.cmd == 'accept' and not d.id then d.id = resolveInviter(d.name) end
            if (d.cmd == 'accept' or d.cmd == 'decline') and not d.id then
                notice('No pending invite' .. (d.name and (' from ' .. d.name) or '') .. '.')
                return
            end
            onCommand(d)
        end,
    },
}
