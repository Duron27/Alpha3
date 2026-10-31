-- The relay: lets the host's game act as the multiplayer server, or runs on its own in openmw-mp-server (apps/mpserver).
-- Everyone connected shares a global channel (presence and global chat). Parties (max 3) are formed
-- with invites and additionally share party chat, vitals, and actor rosters/damage. Every player keeps
-- their own world; the relay only routes messages. The host plays through an ordinary loopback client.
local net = require('scripts/mp/netlib')
local markup = require('openmw.markup')
local core = require('openmw.core')
local okModstore, modstore = pcall(require, 'openmw.modstore') -- the client mods this host or server hands out (absent in older engines)
if not okModstore then modstore = nil end

local MAX_PARTY = 3
local MAX_PLAYERS = 128 -- the most a server takes, whatever it is told (a hosting game counts as one of them)
local INVITE_TTL = 60
local CHAT_MAX = 240
local GLOBAL_TYPES = { profile = true, equip = true, look = true }
local NEAR_TYPES = { anim = true, ev = true } -- only for those who can see the sender (interest below)
local PUBLIC_TYPES = { pubactors = true } -- shown to the others in the sender's cell who are not in their party
local SHOT_TYPES = { shot = true } -- shown to those near, never cached (a late joiner must not see old shots)
local PARTY_TYPES = { actors = true, vitals = true, dmg = true, aid = true, loot = true }
local CACHED = { profile = true, equip = true, state = true, actors = true, vitals = true, look = true }
local GLOBAL_ORDER = { 'profile', 'equip', 'look', 'state' }

local clients = {} -- id -> { party = id|nil, last = { [type] = encoded json }, tokens, tokenTime }
local parties = {} -- party id -> { members = {ids}, leader = id }
local invites = {} -- 'from:to' -> { from, to, exp }
local nextParty = 1
local clock = 0
local running = false
local hostId -- the first client after start() is the host's own loopback connection
local generation = 0 -- bumped whenever the relay is reset, so the bot knows it is gone
local pingTimer, pingSeq = 0, 0
local PING_INTERVAL = 2
local PROTO = 3 -- bumped whenever the messages change incompatibly; clients with another version are refused
local JOIN_TIMEOUT = 10
local SILENCE_LIMIT = 25 -- a player that sends nothing (not even ping replies) for this long is gone
local MESSAGE_LIMIT = 400 -- messages per second per player; more is dropped
local KICK_DELAY = 0.5 -- lets the "you were refused" message reach the client before the socket closes
local options = { password = '', maxPlayers = 8, motd = '', adminPassword = '' }
local dedicated = false -- a standalone server: nobody is the host, one player is the clock, admins log in with a password
local bans = {} -- lower-case character name -> true (a server keeps them in a file: options.onBansChanged, M.setBans)
local function bansChanged()
    if not options.onBansChanged then return end
    local list = {}
    for name in pairs(bans) do list[#list + 1] = name end
    table.sort(list)
    pcall(options.onBansChanged, list)
end
local pendingKicks = {} -- id -> clock to close at
local dropReport = 0 -- when the dropped messages were last reported
local DROP_REPORT_INTERVAL = 60
local conn = {} -- socket id -> player id (a player that reconnects keeps the id everybody else knows)
local trades = {} -- 'low:high' -> { from, to, state = 'requested' | 'active', exp }
local GRACE = 90 -- seconds a player that lost their connection keeps their name, party and place
local TRADE_REQUEST_TTL = 60
-- messages per second and player, by type; more is dropped (the general limit above still applies)
local RATE = { anim = 24, look = 6, ev = 10, pubactors = 2, state = 45, vitals = 5, actors = 8, equip = 3, profile = 3, shot = 12, aid = 4, dmg = 30, world = 4, loot = 10,
    trade = 30, modget = 100 }

local M = {}
M.PROTO = PROTO

local function enc(t) return net.encodeJson(t) end

-- What the operator sees in the server console (or the game's log): who did what, with a time.
local function stamp()
    local ok, s = pcall(function() return os.date('%H:%M:%S') end)
    return ok and s or string.format('+%ds', math.floor(clock))
end
local function log(text) print('[' .. stamp() .. '] ' .. text) end
local function who(id)
    local c = clients[id]
    if not c then return '#' .. tostring(id) end
    return (c.name or ('connection ' .. tostring(id))) .. ' (#' .. tostring(id) .. ')'
end
local function sendRaw(id, json)
    local c = clients[id]
    if not c then return end
    if c.virtual then c.virtual(markup.decodeYaml(json))
    elseif c.sock then net.sendTo(c.sock, json) end -- (no socket: the player lost their connection)
end
local function sendTo(id, msg) sendRaw(id, enc(msg)) end
local function remove(list, v)
    for i, x in ipairs(list) do if x == v then table.remove(list, i) return end end
end

local function partyMembers(id)
    local c = clients[id]
    local p = c and c.party and parties[c.party]
    return p and { table.unpack(p.members) } or {}
end

local function toParty(id, json)
    for _, m in ipairs(partyMembers(id)) do
        if m ~= id then sendRaw(m, json) end
    end
end

local function announceParty(pid)
    local p = parties[pid]
    if not p then return end
    local json = enc({ t = 'party', members = p.members, leader = p.leader })
    for _, m in ipairs(p.members) do sendRaw(m, json) end
end

local noParty = enc({ t = 'party', members = {}, leader = false })

local function removeFromParty(id)
    local c = clients[id]
    local pid = c and c.party
    if not pid then return end
    local p = parties[pid]
    log('party: ' .. who(id) .. ' left their party')
    remove(p.members, id)
    c.party = nil
    sendRaw(id, noParty)
    if #p.members <= 1 then -- a party of one is no party
        for _, m in ipairs(p.members) do
            clients[m].party = nil
            sendRaw(m, noParty)
        end
        parties[pid] = nil
    else
        if p.leader == id then p.leader = p.members[1] end
        announceParty(pid)
    end
end

local function purgeInvites()
    for k, inv in pairs(invites) do
        if inv.exp <= clock then invites[k] = nil end
    end
end

local function pendingTo(id)
    local out = {}
    for _, inv in pairs(invites) do
        if inv.from == id then out[inv.to] = true end
    end
    return out
end

-- one invite action per client per second (a stuck script or a held key must not flood anybody)
local function inviteAllowed(c)
    if c.lastInviteAt and clock - c.lastInviteAt < 1 then return false end
    c.lastInviteAt = clock
    return true
end

local function handleInvite(id, msg)
    purgeInvites()
    local to = msg.to
    local function fail(reason) sendTo(id, { t = 'invite_result', ok = false, to = to, reason = reason }) end
    if not inviteAllowed(clients[id]) then return fail('slow') end
    if type(to) ~= 'number' or not clients[to] or to == id then return fail('unknown') end
    if clients[to].party and clients[to].party == clients[id].party then return fail('already') end
    local existing = invites[id .. ':' .. to]
    if existing and existing.exp > clock then return end -- already pending: say nothing
    if clients[to].party then return fail('busy') end
    local members = partyMembers(id)
    local waiting = pendingTo(id)
    for _, m in ipairs(members) do for t in pairs(pendingTo(m)) do waiting[t] = true end end
    waiting[to] = nil
    local count = 0
    for _ in pairs(waiting) do count = count + 1 end
    if math.max(#members, 1) + count >= MAX_PARTY then return fail('full') end
    invites[id .. ':' .. to] = { from = id, to = to, exp = clock + INVITE_TTL }
    sendTo(to, { t = 'invite', from = id })
    sendTo(id, { t = 'invite_result', ok = true, to = to })
end

local function handleAccept(id, msg)
    purgeInvites()
    local frm = msg.from
    local key = tostring(frm) .. ':' .. id
    if not invites[key] or not clients[frm] then
        -- an accept for nothing: tell them once in a while, never repeatedly
        local c = clients[id]
        if not c.lastExpiredAt or clock - c.lastExpiredAt > 5 then
            c.lastExpiredAt = clock
            sendTo(id, { t = 'invite_result', ok = false, to = frm, reason = 'expired' })
        end
        return
    end
    invites[key] = nil
    if clients[id].party then
        return sendTo(id, { t = 'invite_result', ok = false, to = frm, reason = 'busy' })
    end
    local members = partyMembers(frm)
    if #members >= MAX_PARTY then
        return sendTo(id, { t = 'invite_result', ok = false, to = frm, reason = 'full' })
    end
    if #members == 0 then
        local pid = nextParty
        nextParty = nextParty + 1
        parties[pid] = { members = { frm }, leader = frm }
        clients[frm].party = pid
    end
    local pid = clients[frm].party
    table.insert(parties[pid].members, id)
    clients[id].party = pid
    for k, inv in pairs(invites) do
        if inv.to == id or inv.from == id then invites[k] = nil end -- nothing pending once grouped
    end
    announceParty(pid)
    log(string.format('party: %s joined %s (%d in the party)', who(id), who(frm), #parties[pid].members))
    -- bring everyone up to date on each other's actors/vitals
    for _, m in ipairs(parties[pid].members) do
        for _, t in ipairs({ 'actors', 'vitals' }) do
            local json = clients[m].last[t]
            if json then
                for _, o in ipairs(parties[pid].members) do
                    if o ~= m then sendRaw(o, json) end
                end
            end
        end
    end
end

local function handleDecline(id, msg)
    local key = tostring(msg.from) .. ':' .. id
    if invites[key] then
        invites[key] = nil
        sendTo(msg.from, { t = 'declined', id = id })
    end
end

local function allowChat(c)
    local tokens = math.min(5.0, c.tokens + (clock - c.tokenTime) * 1.5)
    c.tokenTime = clock
    if tokens < 1 then c.tokens = tokens return false end
    c.tokens = tokens - 1
    return true
end

local function broadcastOthers(id, json)
    for o, c in pairs(clients) do
        if o ~= id and c.authed then sendRaw(o, json) end
    end
end

-- Interest: who can see whom. Same interior, or both outside within INTEREST_RANGE (a little past what is drawn).
-- Those get every position, animation, shot and number; everybody else gets a position once in FAR_INTERVAL (enough for
-- the map) and nothing else. This keeps a full server from sending everyone everything 30 times a second.
local INTEREST_RANGE = 3 * 8192
local FAR_INTERVAL = 1
local function canSee(a, b)
    if a.cell == nil or b.cell == nil or a.cell ~= b.cell then return false end
    if a.cell ~= '' then return true end
    if not a.pos or not b.pos then return true end
    local dx, dy = a.pos[1] - b.pos[1], a.pos[2] - b.pos[2]
    return dx * dx + dy * dy <= INTEREST_RANGE * INTEREST_RANGE
end

local function toNear(id, json)
    local c = clients[id]
    for o, oc in pairs(clients) do
        if o ~= id and oc.authed and canSee(c, oc) then sendRaw(o, json) end
    end
end

local function sendState(id, json)
    local c = clients[id]
    c.farSent = c.farSent or {}
    for o, oc in pairs(clients) do
        if o ~= id and oc.authed then
            local partner = oc.party and oc.party == c.party -- (party members always follow each other: map, goto)
            if partner or canSee(c, oc) then
                sendRaw(o, json)
                c.farSent[o] = clock
            elseif clock - (c.farSent[o] or -100) >= FAR_INTERVAL then
                sendRaw(o, json)
                c.farSent[o] = clock
            end
        end
    end
end

-- Dedicated server world: time of day and weather belong to the server, so everybody sees the same sky. The date is
-- never synced (everybody is on their own day), only the hour.
local WEATHER_POOL = { 'clear', 'clear', 'clear', 'cloudy', 'cloudy', 'overcast', 'foggy', 'rain', 'thunder' }
local WORLD_INTERVAL = 2 -- seconds between world messages
local skyTime = 9 * 3600 -- seconds into the day
local skyScale = 30 -- game seconds per real second (the game's default)
local skyWeather = 'clear'
local fixedWeather -- a weather id the admin set, or nil to let the weather change by itself
local weatherEvery = 15 * 60
local nextWeatherAt = 0
local worldJson, worldTimer = nil, 0

local function broadcastWorld()
    worldJson = enc({ t = 'world', time = math.floor(skyTime), weather = skyWeather })
    for o, c in pairs(clients) do if c.authed then sendRaw(o, worldJson) end end
end

-- Who sets time and weather: the host's game, or on a dedicated server the server itself (nobody's game).
local function clockId()
    if dedicated then return nil end
    return hostId
end

------------------------------------------------------------------ validation

local function isNum(x) return type(x) == 'number' and x == x and x > -1e9 and x < 1e9 end
local function isStr(x, max) return type(x) == 'string' and #x <= max end
local function isVec3(v) return type(v) == 'table' and isNum(v[1]) and isNum(v[2]) and isNum(v[3]) end
local function optNum(x) return x == nil or isNum(x) end

-- key -> count maps of a trade (item ids and counts), bounded in size
local function isCountMap(m, maxEntries)
    if m == nil then return true end
    if type(m) ~= 'table' then return false end
    local n = 0
    for k, v in pairs(m) do
        n = n + 1
        if n > maxEntries or not isStr(k, 200) or not isNum(v) or v < 0 then return false end
    end
    return true
end

-- Shape checks for what clients send. A message that does not fit is dropped: a broken or hostile client must not be
-- able to push nonsense (huge lists, NaN positions, absurd damage) into everyone else's game.
local VALID = {
    state = function(m) return isVec3(m.pos) and (m.cell == nil or isStr(m.cell, 200)) and (m.vel == nil or isVec3(m.vel))
        and optNum(m.yaw) and optNum(m.pitch) end,
    vitals = function(m)
        for _, k in ipairs({ 'h', 'hm', 'm', 'mm', 'f', 'fm' }) do if not optNum(m[k]) then return false end end
        return (m.bad == nil or (type(m.bad) == 'table' and #m.bad <= 8)) and (m.fx == nil or (type(m.fx) == 'table' and #m.fx <= 16))
    end,
    pubactors = function(m) return type(m.list) == 'table' and #m.list <= 40 and isStr(m.cell or '', 200) end,
    actors = function(m) return type(m.list) == 'table' and #m.list <= 80
        and (m.dead == nil or (type(m.dead) == 'table' and #m.dead <= 200)) end,
    dmg = function(m) return isNum(m.to) and isStr(m.nid or '', 100) and (m.frac == nil or (isNum(m.frac) and m.frac >= 0 and m.frac <= 10)) end,
    aid = function(m) return isNum(m.to) and isStr(m.spell, 120) end,
    anim = function(m) return isStr(m.g, 32) and type(m.o) == 'table' and optNum(m.ts) end,
    look = function(m) return (m.fx == nil or (type(m.fx) == 'table' and #m.fx <= 12)) and (m.spell == nil or type(m.spell) == 'table') end,
    ev = function(m) return isStr(m.k, 8) and optNum(m.n) and (m.s == nil or isStr(m.s, 40)) end,
    shot = function(m) return isStr(m.k, 8) and (m.rec == nil or isStr(m.rec, 120)) and (m.from == nil or isVec3(m.from))
        and (m.to == nil or isVec3(m.to)) and optNum(m.spd) end,
    equip = function(m) return type(m.slots) == 'table' end,
    loot = function(m)
        if not isStr(m.cid, 100) or not isCountMap(m.items, 30) then return false end
        for _, count in pairs(m.items) do if count > 1000000 then return false end end
        return true
    end,
    trade_inv = function(m) return isCountMap(m.items, 2000) and (m.gens == nil or (type(m.gens) == 'table')) end,
    trade_deal = function(m) return isNum(m.rev) and isCountMap(m.a, 200) and isCountMap(m.b, 200) end,
    trade_ok = function(m) return isNum(m.rev) end,
    modget = function(m) return isStr(m.mod, 40) and isStr(m.p, 160) and (m.off == nil or isNum(m.off)) end,
}

local function withinRate(c, t)
    local key = t:sub(1, 6) == 'trade_' and 'trade' or t
    local limit = RATE[key]
    if not limit then return true end
    c.rate = c.rate or {}
    local r = c.rate[key]
    local second = math.floor(clock)
    if not r or r.second ~= second then r = { second = second, n = 0 } c.rate[key] = r end
    r.n = r.n + 1
    return r.n <= limit
end

------------------------------------------------------------------ trades (the relay only passes on what fits a real deal)

local function tradeKey(a, b) return math.min(a, b) .. ':' .. math.max(a, b) end

local function tradeBusy(id)
    for _, tr in pairs(trades) do
        if tr.from == id or tr.to == id then return true end
    end
    return false
end

-- Ends every trade a player is part of and tells the partners.
local function endTrades(id, left)
    for key, tr in pairs(trades) do
        if tr.from == id or tr.to == id then
            trades[key] = nil
            local other = tr.from == id and tr.to or tr.from
            if clients[other] then sendTo(other, { t = 'trade_cancel', id = id, left = left or nil }) end
        end
    end
end

-- Returns true if the message was a trade message (handled here).
local function handleTrade(id, c, t, msg)
    local other = msg.to
    if type(other) ~= 'number' or other == id or not clients[other] or not clients[other].authed
        or not (clients[other].sock or clients[other].virtual) then
        return true
    end
    local key = tradeKey(id, other)
    local tr = trades[key]
    if t == 'trade_request' then
        if tr or tradeBusy(id) or tradeBusy(other) then
            sendTo(id, { t = 'trade_decline', id = other }) -- one trade at a time
            return true
        end
        trades[key] = { from = id, to = other, state = 'requested', exp = clock + TRADE_REQUEST_TTL }
        log('trade: ' .. who(id) .. ' asked ' .. who(other))
    elseif t == 'trade_accept' then
        if not tr or tr.state ~= 'requested' or tr.to ~= id then return true end
        tr.state, tr.exp = 'active', nil
        log('trade: ' .. who(id) .. ' and ' .. who(other) .. ' are trading')
    elseif t == 'trade_decline' then
        if not tr or tr.state ~= 'requested' or tr.to ~= id then return true end
        trades[key] = nil
    elseif t == 'trade_cancel' then
        if not tr then return true end
        trades[key] = nil
        log('trade: ' .. who(id) .. ' cancelled the trade with ' .. who(other))
    elseif t == 'trade_inv' or t == 'trade_deal' or t == 'trade_ok' or t == 'trade_done' then
        if not tr or tr.state ~= 'active' then return true end -- nothing is being traded
        if t == 'trade_done' then
            trades[key] = nil
            log('trade: ' .. who(id) .. ' and ' .. who(other) .. ' completed a trade')
        end
    else
        return true -- unknown trade message
    end
    msg.to = nil
    sendTo(other, msg)
    return true
end

------------------------------------------------------------------ engine events

function M.onClientConnected(id)
    if not running then return end
    conn[id] = id
    local ip = net.remoteAddress and net.remoteAddress(id) or nil
    clients[id] = { last = {}, tokens = 5.0, tokenTime = clock, pings = {}, authed = false, joinBy = clock + JOIN_TIMEOUT,
        lastSeen = clock, sock = id, ip = ip }
    if not hostId and not dedicated then hostId = id end
    log(string.format('connection %d from %s%s', id, ip or 'unknown address', id == hostId and ' (the host)' or ''))
    sendTo(id, { t = 'hello', id = id, host = id == hostId, proto = PROTO, needPassword = options.password ~= '' })
end

-- A client that passed the join check gets the current world: everyone's look and position, time and weather.
-- The client mods this host or server hands out, with their hashes (see clientmods in the engine): the client compares them
-- with what it has installed and asks the player before downloading anything.
local function sendMods(id)
    local c = clients[id]
    if not modstore or not c or c.virtual or id == hostId then return end
    local ok, json = pcall(modstore.manifest)
    if ok and type(json) == 'string' and #json < 400000 then
        sendRaw(id, '{"t":"mods","list":' .. json .. '}')
    end
end

local function admit(id)
    local c = clients[id]
    c.authed = true
    sendMods(id)
    if dedicated then
        if worldJson then sendRaw(id, worldJson) end
    else
        local source = clockId()
        if source ~= id and source and clients[source] and clients[source].last.world then
            sendRaw(id, clients[source].last.world)
        end
    end
    for other, oc in pairs(clients) do
        if other ~= id and oc.authed then
            for _, t in ipairs(GLOBAL_ORDER) do
                if oc.last[t] then sendRaw(id, oc.last[t]) end
            end
        end
    end
end

local function playerCount()
    local n = 0
    for _, c in pairs(clients) do if c.authed then n = n + 1 end end
    return n
end

local function nameTaken(name, except)
    for id, c in pairs(clients) do
        if id ~= except and c.name and c.name:lower() == name:lower() then return true end
    end
    return false
end

-- Two characters with the same name get "(2)", "(3)"... so they can be told apart everywhere.
local function uniqueName(name, id)
    name = tostring(name or 'Player'):sub(1, 32)
    if name == '' then name = 'Player' end
    local candidate, n = name, 1
    while nameTaken(candidate, id) do
        n = n + 1
        candidate = name .. ' (' .. n .. ')'
    end
    return candidate
end

local function refuse(id, reason)
    sendTo(id, { t = 'join_result', ok = false, reason = reason })
    local c = clients[id]
    log(string.format('refused %s from %s: %s', c and c.tryName or '?', c and c.ip or 'unknown address', reason))
    if c then c.refused = true end
    pendingKicks[id] = clock + KICK_DELAY
end

local tokenSeq = 0
local function newToken()
    tokenSeq = tokenSeq + 1
    return string.format('%06x%06x%06x%x', math.random(0, 0xffffff), math.random(0, 0xffffff),
        math.floor(core.getRealTime() * 1000) % 0xffffff, tokenSeq)
end

local function findByToken(token)
    if type(token) ~= 'string' or token == '' then return nil end
    for id, c in pairs(clients) do
        if c.token == token and c.authed and not c.virtual and not c.refused and id ~= hostId then return id end
    end
    return nil
end

-- A player whose connection was lost (or is half dead) comes back with their token: they get their old id, name and
-- party back, and everyone is told they are back.
local function resume(sock, old, msg)
    local c0 = clients[old]
    if c0.sock and c0.sock ~= sock then
        conn[c0.sock] = nil
        net.kick(c0.sock) -- the old socket is dead to us from now on
    end
    c0.sock, c0.awayUntil, c0.dropped, c0.lastSeen, c0.pings = sock, nil, nil, clock, {}
    c0.ip = clients[sock] and clients[sock].ip or c0.ip
    conn[sock] = old
    clients[sock] = nil
    log(who(old) .. ' is back, now from ' .. tostring(c0.ip or 'unknown address'))
    sendTo(old, { t = 'join_result', ok = true, name = c0.name, id = old, token = c0.token, resumed = true,
        motd = options.motd ~= '' and options.motd or nil })
    admit(old)
    if c0.party and parties[c0.party] then
        announceParty(c0.party)
        for _, m in ipairs(parties[c0.party].members) do
            for _, t in ipairs({ 'actors', 'vitals' }) do
                if m ~= old and clients[m].last[t] then sendRaw(old, clients[m].last[t]) end
            end
        end
    end
    local json = enc({ t = 'back', id = old, name = c0.name })
    for o, oc in pairs(clients) do
        if o ~= old and oc.authed then
            sendRaw(o, json)
            for _, t in ipairs(GLOBAL_ORDER) do
                if c0.last[t] then sendRaw(o, c0.last[t]) end
            end
        end
    end
end

local function handleJoin(id, msg)
    local c = clients[id]
    if c.authed or c.refused then return end
    c.tryName = tostring(msg.name or '?'):sub(1, 32)
    if msg.proto ~= PROTO then return refuse(id, 'version') end
    if options.password ~= '' and msg.password ~= options.password then return refuse(id, 'password') end
    if bans[tostring(msg.name or ''):lower()] then return refuse(id, 'banned') end
    local old = findByToken(msg.token)
    -- No token (the game was restarted), but the same name from the same address as a player whose place is being held:
    -- that is them coming back, not a second player. Without this the old one stands there until the hold runs out.
    if not old and c.ip then
        local want = tostring(msg.name or ''):lower()
        for oid, oc in pairs(clients) do
            if oc.awayUntil and oc.authed and not oc.virtual and oid ~= hostId and oc.ip == c.ip
                and tostring(oc.name or ''):lower() == want then
                old = oid
                break
            end
        end
    end
    if old then return resume(id, old, msg) end
    local name = uniqueName(msg.name, id)
    if playerCount() >= options.maxPlayers then return refuse(id, 'full') end
    c.name = name
    c.token = newToken()
    sendTo(id, { t = 'join_result', ok = true, name = name, token = c.token,
        motd = options.motd ~= '' and options.motd or nil })
    admit(id)
    log(string.format('%s joined from %s (%d/%d players)', who(id), c.ip or 'unknown address', playerCount(), options.maxPlayers))
end

-- The player is gone for good: out of their party, invites and trades, and everyone is told.
local function removeClient(id)
    if not clients[id] then return end
    local label = who(id)
    pendingKicks[id] = nil
    endTrades(id, true)
    removeFromParty(id)
    for k, inv in pairs(invites) do
        if inv.from == id or inv.to == id then invites[k] = nil end
    end
    local wasAdmitted = clients[id].authed
    clients[id] = nil
    if id == hostId then hostId = nil end
    log(label .. ' left')
    if not wasAdmitted then return end -- nobody knew about them
    local json = enc({ t = 'leave', id = id })
    for o, c in pairs(clients) do if c.authed then sendRaw(o, json) end end
end

function M.onClientDisconnected(sock)
    local id = conn[sock]
    conn[sock] = nil
    if not id then return end -- unknown, or the socket of a player who already came back on another one
    local c = clients[id]
    if not c or c.sock ~= sock then return end
    pendingKicks[id] = nil
    if c.authed and not c.virtual and not c.refused and not c.leaving and id ~= hostId and c.token then
        -- connection lost: keep their name, party and place for a while so they can come back
        c.sock, c.awayUntil, c.pings = nil, clock + GRACE, {}
        log(who(id) .. ' lost connection; their place is kept for ' .. GRACE .. ' s')
        endTrades(id, true)
        local json = enc({ t = 'away', id = id, name = c.name, grace = GRACE })
        for o, oc in pairs(clients) do if o ~= id and oc.authed then sendRaw(o, json) end end
        return
    end
    removeClient(id)
end

local function findByName(name)
    name = tostring(name or ''):lower()
    for id, c in pairs(clients) do
        if c.authed and c.name and c.name:lower() == name then return id, c end
    end
    return nil
end

-- Commands for the host, or (on a dedicated server) for admins and the server console: kick, ban, unban, mute,
-- unmute, announce. `reply(text)` answers whoever asked; `actor` is their player id (nil for the console).
local function adminCommand(msg, reply, actor)
    local cmd = msg.cmd
    if cmd == 'announce' then
        local text = tostring(msg.text or ''):sub(1, CHAT_MAX)
        if text == '' then return end
        local json = enc({ t = 'notice', text = (dedicated and '[Server] ' or '[Host] ') .. text })
        for other, c in pairs(clients) do if c.authed then sendRaw(other, json) end end
        return
    end
    if cmd == 'weather' or cmd == 'time' then
        if not dedicated then return reply('Only a dedicated server controls the sky; the host\'s game does that here.') end
        local arg = tostring(msg.text or msg.name or ''):lower()
        if cmd == 'weather' then
            if arg == '' then return reply('Weather is ' .. skyWeather .. (fixedWeather and ' (fixed)' or ' (changes by itself)') .. '.') end
            if arg == 'cycle' then
                fixedWeather = nil
                nextWeatherAt = clock
                return reply('The weather changes by itself again.')
            end
            if #arg > 40 or arg:find('[^%w_ ]') then return reply('Not a weather id.') end
            fixedWeather, skyWeather = arg, arg
        else
            local hours = tonumber(arg)
            if not hours or hours < 0 or hours >= 24 then return reply('Usage: time <hour 0-24>, for example 14.5') end
            skyTime = hours * 3600
        end
        broadcastWorld()
        return reply(cmd == 'time' and ('It is now ' .. string.format('%d:%02d', math.floor(skyTime / 3600), math.floor(skyTime / 60) % 60) .. '.')
            or ('The weather is now ' .. skyWeather .. '.'))
    end
    if cmd == 'unban' then
        local key = tostring(msg.name or ''):lower()
        if bans[key] then bans[key] = nil reply(msg.name .. ' can join again.') bansChanged() else reply('Nobody by that name is banned.') end
        return
    end
    local target, c = findByName(msg.name)
    if not target then return reply('No player named ' .. tostring(msg.name) .. ' is online.') end
    if target == hostId or target == actor then return reply('You cannot do that to yourself.') end
    if cmd == 'kick' or cmd == 'ban' then
        if cmd == 'ban' then bans[c.name:lower()] = true bansChanged() end
        if not c.sock then -- (lost their connection: there is nobody to tell)
            removeClient(target)
            return reply(c.name .. (cmd == 'ban' and ' was banned.' or ' was kicked.'))
        end
        sendTo(target, { t = 'kicked', reason = cmd == 'ban' and 'banned' or 'kicked' })
        pendingKicks[target] = clock + KICK_DELAY
        c.refused = true
        reply(c.name .. (cmd == 'ban' and ' was banned.' or ' was kicked.'))
    elseif cmd == 'mute' or cmd == 'unmute' then
        c.muted = cmd == 'mute'
        reply(c.name .. (c.muted and ' is muted.' or ' can chat again.'))
        sendTo(target, { t = 'notice', text = c.muted and 'The ' .. (dedicated and 'server' or 'host') .. ' muted you.' or 'You were unmuted.' })
    end
end

-- Where a player is in the game, from their last position message: the cell, or the exterior grid square.
local function locationOf(c)
    local s = c.last and c.last.state and markup.decodeYaml(c.last.state)
    if type(s) ~= 'table' or type(s.pos) ~= 'table' then return 'unknown' end
    local x, y, z = tonumber(s.pos[1]) or 0, tonumber(s.pos[2]) or 0, tonumber(s.pos[3]) or 0
    local where = (s.cell and s.cell ~= '') and s.cell or string.format('exterior (%d, %d)', math.floor(x / 8192), math.floor(y / 8192))
    return string.format('%s  at %d, %d, %d', where, x, y, z)
end

-- Lines describing everybody online, for admins and the server console: id, name, flags, latency, address, location.
-- (Addresses never go in the player list that all players receive.)
local function whoLines()
    local ids = {}
    for id, c in pairs(clients) do if c.authed then ids[#ids + 1] = id end end
    table.sort(ids)
    local lines = {}
    for _, id in ipairs(ids) do
        local c = clients[id]
        local flags = (c.admin and ' [admin]' or '') .. (id == hostId and ' [host]' or '') .. (c.muted and ' [muted]' or '')
        local latency = c.virtual and 'bot' or c.sock and (c.ping and (math.floor(c.ping + 0.5) .. ' ms') or '? ms') or 'away'
        lines[#lines + 1] = string.format('%d  %s%s  %s  %s  %s', id, c.name or '?', flags, latency, c.ip or 'unknown address',
            locationOf(c))
    end
    if #lines == 0 then lines[1] = 'Nobody is online.' end
    return lines
end

local MAX_LOGIN_FAILURES = 5

local function handleAdmin(id, msg)
    local c = clients[id]
    local function reply(text) sendTo(id, { t = 'notice', text = text }) end
    if msg.cmd == 'login' then
        if options.adminPassword == '' then return reply('This server has no admin password.') end
        if (c.loginFailures or 0) >= MAX_LOGIN_FAILURES then return reply('Too many wrong passwords.') end
        if msg.password == options.adminPassword then
            c.admin = true
            log('admin: ' .. who(id) .. ' logged in')
            reply('You are now an admin.')
        else
            c.loginFailures = (c.loginFailures or 0) + 1
            log(string.format('admin: wrong password from %s (%d/%d)', who(id), c.loginFailures, MAX_LOGIN_FAILURES))
            reply('Wrong password.')
        end
        return
    end
    if id ~= hostId and not c.admin then
        return reply(dedicated and 'Only admins can do that. Use /login <password>.' or 'Only the host can do that.')
    end
    log(string.format('admin: %s ran %s %s', who(id), tostring(msg.cmd), tostring(msg.name or msg.text or '')))
    if msg.cmd == 'who' then
        for _, line in ipairs(whoLines()) do reply(line) end
        return
    end
    adminCommand(msg, reply, id)
end

-- What the server console and the in-game admin commands do (shown by `help`, and by openmw-mp-server --help).
M.HELP = table.concat({
    'Server console commands:',
    '  players | who              list everybody online: id, name, flags, latency, address and location',
    '  kick <name>                disconnect a player',
    '  ban <name>                 disconnect a player and refuse that name until the server restarts',
    '  unban <name>               let a banned name join again',
    '  mute <name> | unmute <name>   stop or allow a player\'s chat',
    '  announce <text> | say <text>  send a message to everybody',
    '  weather                    show the current weather',
    '  weather <id>               fix the weather: clear, cloudy, overcast, foggy, rain, thunder, snow, ash, blight, blizzard',
    '  weather cycle              let the weather change by itself again',
    '  time <hour>                set the time of day, 0 to 24 (for example 14.5); the date is never synced',
    '  help | ?                   this list',
    '  quit | exit | stop         stop the server (Ctrl+C does the same)',
    '',
    'In the game, admins (after /login <admin password>) can use: /who /kick /ban /unban /mute /unmute',
    '/announce /weather /time. Everyone can use /players /me /w /r /g /p /goto /accept /decline /leave /pkick.',
}, '\n')

local process

function M.onClientMessage(sock, line)
    local id = conn[sock]
    if not id or not clients[id] or clients[id].sock ~= sock then return end
    local ok, msg = pcall(markup.decodeYaml, line)
    if not ok or type(msg) ~= 'table' then return end
    process(id, msg)
end

local MOD_CHUNK = 24000 -- bytes of a file per message (a little over 32000 characters once it is base64)
local modCache = {} -- 'id/path' -> { data, t }: a file being downloaded is read once, not for every chunk
local function modData(modId, path)
    local key = modId .. '/' .. path
    local entry = modCache[key]
    if entry and clock - entry.t < 30 then entry.t = clock return entry.data end
    local data = modstore.read(modId, path)
    if not data then return nil end
    local n, oldest = 0, nil
    for k, e in pairs(modCache) do
        n = n + 1
        if clock - e.t >= 30 then modCache[k] = nil elseif not oldest or e.t < modCache[oldest].t then oldest = k end
    end
    if n >= 6 and oldest and modCache[oldest] then modCache[oldest] = nil end
    modCache[key] = { data = data, t = clock }
    return data
end

local function handleModGet(id, msg)
    if not modstore then return end
    local data = modData(msg.mod, msg.p)
    if not data then return sendTo(id, { t = 'modchunk', mod = msg.mod, p = msg.p, missing = true }) end
    local off = math.max(0, math.floor(msg.off or 0))
    sendTo(id, { t = 'modchunk', mod = msg.mod, p = msg.p, off = off, n = #data,
        d = modstore.base64Encode(data:sub(off + 1, off + MOD_CHUNK)) })
end

process = function(id, msg)
    local c = clients[id]
    if not c or c.refused then return end
    c.lastSeen = clock
    if c.windowStart ~= math.floor(clock) then c.windowStart, c.windowCount = math.floor(clock), 0 end
    c.windowCount = c.windowCount + 1
    if c.windowCount > MESSAGE_LIMIT then c.floodDropped = (c.floodDropped or 0) + 1 return end -- flooding
    local t = msg.t
    if t == 'join' then return handleJoin(id, msg) end
    if not c.authed then return end -- nothing counts before the join check
    if t == 'admin' then return handleAdmin(id, msg) end
    if t == 'invite' then return handleInvite(id, msg) end
    if t == 'accept' then return handleAccept(id, msg) end
    if t == 'decline' then return handleDecline(id, msg) end
    if t == 'bye' then c.leaving = true return end -- the game was closed on purpose: the socket closing now is a goodbye
    if t == 'leave_party' then return removeFromParty(id) end
    if t == 'party_kick' then -- the leader sends a member away
        local pid = c.party
        local target = msg.id
        if not pid or parties[pid].leader ~= id then return sendTo(id, { t = 'notice', text = 'Only the party leader can do that.' }) end
        if type(target) ~= 'number' or target == id or not clients[target] or clients[target].party ~= pid then return end
        removeFromParty(target)
        sendTo(target, { t = 'notice', text = 'You were removed from the party.' })
        return
    end
    if t == 'pong' then
        local sent = c.pings[msg.n]
        if sent then
            c.pings[msg.n] = nil
            local ms = (core.getRealTime() - sent) * 1000
            c.ping = c.ping and (c.ping * 0.6 + ms * 0.4) or ms
        end
        return
    end
    msg.id = id
    if type(t) ~= 'string' or #t > 16 then return end
    if not withinRate(c, t) then c.rateDropped = (c.rateDropped or 0) + 1 return end
    local valid = VALID[t]
    if valid and not valid(msg) then c.invalidDropped = (c.invalidDropped or 0) + 1 return end
    if t == 'modget' then return handleModGet(id, msg) end
    if t == 'world' then -- time and weather: only the clock (the host, or the elected player of a server) sets them
        if id == clockId() then
            c.last.world = enc(msg)
            broadcastOthers(id, c.last.world)
        end
        return
    end
    if t == 'profile' then
        msg.name = c.name or uniqueName(msg.name, id) -- the name the relay gave us
        c.name = msg.name
        c.level = tonumber(msg.level)
    end
    if t:sub(1, 6) == 'trade_' then -- trades are between two players, party or not
        if #t > 16 then return end
        return handleTrade(id, c, t, msg)
    end
    if t == 'chat' then
        local text = tostring(msg.text or ''):sub(1, CHAT_MAX):match('^%s*(.-)%s*$')
        if text == '' or not allowChat(c) then return end
        if c.muted then return sendTo(id, { t = 'notice', text = 'You are muted by the host.' }) end
        if msg.scope == 'whisper' then
            local target = msg.to
            if type(target) ~= 'number' or not clients[target] or target == id then
                return sendTo(id, { t = 'invite_result', ok = false, to = target, reason = 'unknown' })
            end
            log('[whisper] ' .. who(id) .. ' -> ' .. who(target))
            return sendTo(target, { t = 'chat', id = id, text = text, scope = 'whisper' })
        end
        local scope = msg.scope == 'party' and 'party' or msg.scope == 'emote' and 'emote' or 'global'
        if scope ~= 'party' and dedicated then log('[' .. scope .. '] ' .. (c.name or '?') .. ': ' .. text) end
        local json = enc({ t = 'chat', id = id, text = text, scope = scope })
        if msg.scope == 'party' then toParty(id, json) else broadcastOthers(id, json) end
        return
    end
    if t == 'state' then c.cell, c.pos = msg.cell or '', msg.pos end -- (where players are, for what is only for those who share a cell)
    if PUBLIC_TYPES[t] then
        local json = enc(msg)
        for o, oc in pairs(clients) do
            if o ~= id and oc.authed and oc.cell == c.cell and not (oc.party and oc.party == c.party) then sendRaw(o, json) end
        end
        return
    end
    if SHOT_TYPES[t] or NEAR_TYPES[t] then
        toNear(id, enc(msg))
        return
    end
    if CACHED[t] then c.last[t] = enc(msg) end
    if t == 'state' then
        sendState(id, c.last[t])
    elseif GLOBAL_TYPES[t] then
        broadcastOthers(id, c.last[t])
    elseif PARTY_TYPES[t] then
        local target = msg.to
        if target ~= nil then
            if type(target) == 'number' and target ~= id then
                for _, m in ipairs(partyMembers(id)) do
                    if m == target then sendTo(target, msg) break end
                end
            end
        else
            toParty(id, enc(msg))
        end
    end
end

------------------------------------------------------------------ control

-- Returns an error message, or nil on success.
function M.start(port, opts)
    M.stop()
    options = { password = tostring(opts and opts.password or ''), maxPlayers = math.max(1, math.min(MAX_PLAYERS, tonumber(opts and opts.maxPlayers) or 64)),
        motd = tostring(opts and opts.motd or ''), adminPassword = tostring(opts and opts.adminPassword or ''),
        onBansChanged = opts and opts.onBansChanged }
    dedicated = opts and opts.dedicated and true or false
    if dedicated then
        skyTime = (tonumber(opts.startHour) or 9) % 24 * 3600
        skyScale = tonumber(opts.timeScale) or 30
        weatherEvery = (tonumber(opts.weatherMinutes) or 15) * 60
        local w = tostring(opts.weather or 'cycle'):lower()
        fixedWeather = w ~= 'cycle' and w ~= '' and w or nil
        skyWeather = fixedWeather or 'clear'
        nextWeatherAt, worldJson, worldTimer = clock + weatherEvery, nil, 0
        broadcastWorld() -- (nobody is connected yet: this just makes the first sky ready for whoever joins)
    end
    local err = net.listen(port)
    if err then return err end
    running = true
    hostId, pingTimer = nil, 0
    return nil
end

function M.stop()
    generation = generation + 1
    pendingKicks = {}
    if running or net.isListening() then net.stopListening() end
    running = false
    clients, parties, invites, conn, trades = {}, {}, {}, {}, {}
end

function M.isRunning() return running end

-- Names banned before (a server reads them from its file at start): a list of names
function M.setBans(list)
    for _, name in ipairs(list or {}) do
        if type(name) == 'string' and name ~= '' then bans[name:lower()] = true end
    end
end
function M.isDedicated() return dedicated end

-- Everything a server window needs, as plain data: the players (with latency, address and location), banned names, the
-- sky and the limits.
function M.snapshot()
    local ids = {}
    for id, c in pairs(clients) do if c.authed then ids[#ids + 1] = id end end
    table.sort(ids)
    local players = {}
    for _, id in ipairs(ids) do
        local c = clients[id]
        players[#players + 1] = {
            id = id, name = c.name or '?', admin = c.admin and true or false, muted = c.muted and true or false,
            away = not c.sock and not c.virtual, host = id == hostId, bot = c.virtual and true or false,
            ping = c.sock and c.ping and math.floor(c.ping + 0.5) or -1, ip = c.ip or '', location = locationOf(c),
        }
    end
    local bannedNames = {}
    for name in pairs(bans) do bannedNames[#bannedNames + 1] = name end
    table.sort(bannedNames)
    return {
        players = players, bans = bannedNames, maxPlayers = options.maxPlayers, passworded = options.password ~= '',
        dedicated = dedicated, hour = skyTime / 3600, weather = skyWeather, weatherFixed = fixedWeather ~= nil,
    }
end

-- The server console (dedicated servers): one line in, the answer out.
function M.console(line)
    local cmd, arg = tostring(line or ''):match('^%s*(%S+)%s*(.-)%s*$')
    if not cmd then return '' end
    cmd = cmd:lower()
    local out = {}
    local function reply(text) out[#out + 1] = text end
    if cmd == 'help' or cmd == '?' then
        reply(M.HELP)
    elseif cmd == 'players' or cmd == 'who' then
        for _, line in ipairs(whoLines()) do reply(line) end
    elseif cmd == 'weather' or cmd == 'time' then
        adminCommand({ cmd = cmd, text = arg }, reply, nil)
    elseif cmd == 'announce' or cmd == 'say' then
        adminCommand({ cmd = 'announce', text = arg }, reply, nil)
    elseif cmd == 'kick' or cmd == 'ban' or cmd == 'unban' or cmd == 'mute' or cmd == 'unmute' then
        if arg == '' then reply('Usage: ' .. cmd .. ' <name>') else
            log('console: ' .. cmd .. ' ' .. arg)
            adminCommand({ cmd = cmd, name = arg }, reply, nil)
        end
    else
        reply('Unknown command. Type help.')
    end
    return table.concat(out, '\n')
end
function M.clientCount()
    local n = 0
    for _ in pairs(clients) do n = n + 1 end
    return n
end
-- Pings everyone every PING_INTERVAL seconds and tells everyone the resulting player list.
-- Virtual clients live inside this process (the test bot): `handler(msg)` receives what a real client would be sent,
-- and virtualSend(id, msg) is what a real client would send.
local nextVirtual = 1000
function M.addVirtual(handler)
    local id = nextVirtual
    nextVirtual = nextVirtual + 1
    clients[id] = { last = {}, tokens = 5.0, tokenTime = clock, pings = {}, virtual = handler, ping = 1, authed = true }
    for other, oc in pairs(clients) do -- replay global presence, like for a real client
        if other ~= id and oc.authed and not oc.virtual then
            for _, t in ipairs(GLOBAL_ORDER) do
                if oc.last[t] then sendRaw(id, oc.last[t]) end
            end
        end
    end
    sendTo(id, { t = 'hello', id = id })
    return id
end
function M.virtualSend(id, msg) process(id, msg) end
function M.removeVirtual(id) removeClient(id) end
function M.generation() return generation end

function M.update(dt)
    clock = clock + dt
    if not running then return end
    for id, c in pairs(clients) do
        if c.authed and not c.virtual and not c.refused and c.sock and not c.dropped
            and clock - (c.lastSeen or clock) > SILENCE_LIMIT then
            c.dropped = true -- (not refused: they may come back)
            log(who(id) .. ' stopped answering; closing the connection')
            pendingKicks[id] = clock
        end
        if c.awayUntil and clock > c.awayUntil then
            log(who(id) .. ' did not come back; their place is given up')
            removeClient(id)
        end
    end
    for key, tr in pairs(trades) do
        if tr.exp and clock > tr.exp then trades[key] = nil end -- nobody answered the request
    end
    -- messages thrown away (too many, or not shaped like what they claim to be), reported once a minute per player
    if clock - dropReport >= DROP_REPORT_INTERVAL then
        dropReport = clock
        for id, c in pairs(clients) do
            if c.floodDropped or c.rateDropped or c.invalidDropped then
                log(string.format('%s: dropped %d flooding, %d rate-limited and %d malformed messages in the last minute', who(id),
                    c.floodDropped or 0, c.rateDropped or 0, c.invalidDropped or 0))
                c.floodDropped, c.rateDropped, c.invalidDropped = nil, nil, nil
            end
        end
    end
    for id, c in pairs(clients) do
        if not c.authed and not c.refused and clock > c.joinBy then
            c.refused = true
            pendingKicks[id] = clock -- never said who they are
        end
    end
    for id, at in pairs(pendingKicks) do
        if clock >= at then
            pendingKicks[id] = nil
            local c = clients[id]
            if c and c.virtual then M.removeVirtual(id)
            elseif c and c.sock then local sock = c.sock net.kick(sock) M.onClientDisconnected(sock)
            elseif c then removeClient(id) end
        end
    end
    if dedicated then
        skyTime = (skyTime + dt * skyScale) % 86400
        if not fixedWeather and clock >= nextWeatherAt then
            nextWeatherAt = clock + weatherEvery * (0.5 + math.random())
            skyWeather = WEATHER_POOL[math.random(#WEATHER_POOL)]
            log('weather is now ' .. skyWeather)
        end
        worldTimer = worldTimer + dt
        if worldTimer >= WORLD_INTERVAL then
            worldTimer = 0
            broadcastWorld()
        end
    end
    pingTimer = pingTimer + dt
    if pingTimer < PING_INTERVAL then return end
    pingTimer = 0
    local list = {}
    local ids = {}
    for id, c in pairs(clients) do if c.authed then ids[#ids + 1] = id end end
    table.sort(ids)
    for _, id in ipairs(ids) do
        local c = clients[id]
        list[#list + 1] = { id = id, name = c.name or ('Player ' .. id), ping = c.ping and math.floor(c.ping + 0.5) or false,
            host = id == hostId, level = c.level, muted = c.muted or nil, away = (not c.sock and not c.virtual) or nil }
        if c.sock or c.virtual then
        pingSeq = pingSeq + 1
        local now = core.getRealTime()
        for n, sent in pairs(c.pings) do
            if now - sent > 10 then c.pings[n] = nil end -- never came back
        end
        c.pings[pingSeq] = now
        sendTo(id, { t = 'ping', n = pingSeq })
        end
    end
    local json = enc({ t = 'players', list = list })
    for _, id in ipairs(ids) do sendRaw(id, json) end
end

return M
