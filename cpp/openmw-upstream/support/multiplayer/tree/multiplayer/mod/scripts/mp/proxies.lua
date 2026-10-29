-- Per-player worlds: nothing a group member does changes *this* world. Instead, each client
-- publishes a roster of the living NPCs/creatures around it. When a member has an actor alive
-- that is dead or missing in our world, we spawn a marked proxy copy ("Name [Alice's world]").
-- Hits on the proxy are reported to the owner, whose real actor takes the damage (so they can
-- finish their quest); the proxy has its own record, no script and no AI, so it changes nothing here.
local core = require('openmw.core')
local world = require('openmw.world')
local types = require('openmw.types')
local util = require('openmw.util')

local M = {}

local ROSTER_INTERVAL = 0.25
local ROSTER_RANGE = 4000
local ROSTER_MAX = 60
local DEAD_REPEATS = 12 -- how many rosters a death notice is repeated
local REMOTE_SCRIPT = 'scripts/mp/remote.lua'
local DAMAGE_SCRIPT = 'scripts/mp/damage.lua'
local SUPPRESS_PROXY_CRIME = true -- hitting a proxy must not give *us* a bounty
local CRIME_WINDOW = 1.5

local ctx -- { send, log, player, cellKey, myId, ownerName, isAvatar }
local rosterTimer = 0
local cleanTimer = 0
local clock = 0
local proxies = {} -- actor id (nid) -> { owner, obj, key, last, ownerCell, gone }: at most ONE proxy per actor
local proxyById = {} -- local object id -> proxy
local ownerCells = {} -- owner id -> cell key
local records = {} -- owner..'|'..recordId -> custom record
local recentDead = {} -- nid -> repeats left (our actors that just died)
local previousRoster = {} -- nid -> true
local damageQueue = {} -- { obj, frac, wait, kill }
local pendingKills = {} -- nid -> true: actors a group member killed that we have not been able to kill yet
local killTimer = 0
-- Stand-ins for characters that are alive in other players' worlds (players who are NOT in our group). Their kills are
-- not ours, so an actor we killed stays dead here; but after a while another world's copy of it appears, labelled with
-- whose world it comes from. These never touch the owner's world: hitting or killing one changes only ours.
local STRANGER_DELAY = 300 -- seconds an actor must have been gone from our world before another world's copy shows up
local STRANGER_MAX = 8 -- stand-ins from other worlds at once
local STRANGER_STALE = 10 -- seconds without its owner listing it before the stand-in goes
local DEAD_STANDIN_LINGER = 60 -- seconds a killed stand-in stays as a corpse
local PUBLIC_INTERVAL = 1.0
local PUBLIC_RANGE = 3000
local PUBLIC_MAX = 30
local MAX_RECORDS = 200 -- custom records stand-ins may create in total (they are saved with the game)
local goneSince = {} -- nid -> clock when our own copy was first seen dead or disabled
local finished = {} -- nid..'|'..owner -> true: a stand-in we killed does not come back this session
local publicTimer, goneTimer = 0, 0
local recordCount = 0
local fightSync = {} -- nid -> seconds until a group member's damage may be applied to our copy again
local FIGHT_MIN_DROP = 0.02 -- health ratio difference worth syncing
local FIGHT_COOLDOWN = 1.0 -- the damage is delivered within ~0.2 s; roster messages must not apply it twice
local crimeSamples = {} -- { t, level }
local crimeBase
local crimeGuard = 0

function M.init(context) ctx = context end

function M.reset()
    for _, px in pairs(proxies) do
        if px.obj and px.obj:isValid() then pcall(function() px.obj:remove() end) end
    end
    proxies, proxyById, ownerCells, previousRoster, recentDead, damageQueue = {}, {}, {}, {}, {}, {}
    pendingKills, fightSync, goneSince, finished = {}, {}, {}, {}
end

function M.isProxy(obj) return proxyById[obj.id] ~= nil end

-- Objects to delete when a save is loaded: stand-ins are saved with the world, and must not come back as ghosts.
function M.savedObjects()
    local list = {}
    for _, px in pairs(proxies) do
        if px.obj and px.obj:isValid() then list[#list + 1] = px.obj end
    end
    return list
end

function M.removeOwner(owner)
    ownerCells[owner] = nil
    for key, px in pairs(proxies) do
        if px.owner == owner then
            local id = px.obj.id
            if px.obj:isValid() then pcall(function() px.obj:remove() end) end
            proxyById[id] = nil
            proxies[key] = nil
        end
    end
end

-- Stable cross-client id: "<content file>:<index>" (GameObject.id embeds the load order).
local function contentId(obj)
    local n = tonumber(obj.id)
    if not obj.contentFile or not n then return nil end
    return obj.contentFile .. ':' .. (n % 0x1000000)
end

local function objectById(id)
    local file, index = tostring(id):match('^(.+):(%d+)$')
    if not file then return nil end
    local ok, obj = pcall(function() return world.getObjectByFormId(core.getFormId(file, tonumber(index))) end)
    if ok and obj and obj:isValid() then return obj end
    return nil
end

local function round(x, step) return math.floor(x / step + 0.5) * step end

------------------------------------------------------------------ outbound

local function healthRatio(a)
    local ok, r = pcall(function()
        local h = types.Actor.stats.dynamic.health(a)
        return h.current / math.max(1, h.base + h.modifier)
    end)
    return ok and round(r, 0.01) or 1
end

local function sendRoster()
    local p = ctx.player()
    if not p then return end
    local list, current = {}, {}
    for _, a in ipairs(world.activeActors) do
        if #list >= ROSTER_MAX then break end
        if a ~= p and (a.type == types.NPC or a.type == types.Creature) and not ctx.isAvatar(a) and not M.isProxy(a) then
            local nid = contentId(a)
            if nid and a.enabled and (a.position - p.position):length() < ROSTER_RANGE then
                if types.Actor.isDead(a) then
                    if previousRoster[nid] then recentDead[nid] = DEAD_REPEATS end
                else
                    current[nid] = true
                    local pos = a.position
                    list[#list + 1] = {
                        n = nid, r = a.recordId, h = healthRatio(a),
                        p = { round(pos.x, 1), round(pos.y, 1), round(pos.z, 1) },
                        y = round(a.rotation:getYaw(), 0.01),
                    }
                end
            end
        end
    end
    -- actors that vanished from the roster because they died (not because they walked away)
    for nid in pairs(previousRoster) do
        if not current[nid] then
            local obj = objectById(nid)
            if obj and types.Actor.isDead(obj) then recentDead[nid] = DEAD_REPEATS end
        end
    end
    previousRoster = current
    local dead = {}
    for nid, left in pairs(recentDead) do
        dead[#dead + 1] = nid
        if left <= 1 then recentDead[nid] = nil else recentDead[nid] = left - 1 end
    end
    ctx.send({ t = 'actors', cell = ctx.cellKey(p.cell), list = list, dead = dead })
end

-- Everybody who shares our cell (outside our group) hears which characters are alive around us, once a second.
local function sendPublicRoster()
    local p = ctx.player()
    if not p then return end
    local near = {}
    for _, a in ipairs(world.activeActors) do
        if a ~= p and (a.type == types.NPC or a.type == types.Creature) and not ctx.isAvatar(a) and not M.isProxy(a) then
            local nid = contentId(a)
            local dist = (a.position - p.position):length()
            if nid and a.enabled and dist < PUBLIC_RANGE and not types.Actor.isDead(a) then
                near[#near + 1] = { dist = dist, a = a, nid = nid }
            end
        end
    end
    table.sort(near, function(x, y) return x.dist < y.dist end)
    local list = {}
    for i = 1, math.min(#near, PUBLIC_MAX) do
        local a = near[i].a
        local pos = a.position
        list[i] = { n = near[i].nid, r = a.recordId, h = healthRatio(a),
            p = { round(pos.x, 1), round(pos.y, 1), round(pos.z, 1) }, y = round(a.rotation:getYaw(), 0.01) }
    end
    ctx.send({ t = 'pubactors', cell = ctx.cellKey(p.cell), list = list })
end

-- When did each of our dead (or disabled) actors go? A copy from another world only appears after STRANGER_DELAY.
local function trackGone()
    local seen = {}
    for _, a in ipairs(world.activeActors) do
        if (a.type == types.NPC or a.type == types.Creature) and not ctx.isAvatar(a) and not M.isProxy(a) then
            local nid = contentId(a)
            if nid and (types.Actor.isDead(a) or not a.enabled) then
                seen[nid] = true
                goneSince[nid] = goneSince[nid] or clock
            end
        end
    end
    for nid in pairs(goneSince) do
        if not seen[nid] then goneSince[nid] = nil end
    end
end

------------------------------------------------------------------ proxies

local function needsProxy(nid)
    local obj = objectById(nid)
    return not obj or types.Actor.isDead(obj) or not obj.enabled
end

local DUPLICATE_RADIUS = 350

-- Never put a stand-in next to a living actor with the same record: that is the same NPC twice, whatever the
-- bookkeeping says (e.g. our copy could not be looked up by id).
local function livingTwinNearby(e)
    local pos = util.vector3(e.p[1], e.p[2], e.p[3])
    for _, a in ipairs(world.activeActors) do
        if a.recordId == e.r and not M.isProxy(a) and not ctx.isAvatar(a) and a.enabled
            and not types.Actor.isDead(a) and (a.position - pos):length() < DUPLICATE_RADIUS then
            return true
        end
    end
    return false
end

local function removeProxy(px)
    if px.obj and px.obj:isValid() then
        proxyById[px.obj.id] = nil
        pcall(function() px.obj:remove() end)
    end
    proxies[px.last.n] = nil
end

local function recordFor(owner, recordId)
    local cacheKey = owner .. '|' .. recordId
    if records[cacheKey] then return records[cacheKey] end
    local T = types.NPC
    local template = T.records[recordId]
    if not template then
        T = types.Creature
        template = T.records[recordId]
    end
    if not template then return nil end
    if recordCount >= MAX_RECORDS then return nil end
    local fields = {
        template = template,
        name = (template.name or recordId) .. ' [' .. ctx.ownerName(owner) .. "'s world]",
        isEssential = false,
        isRespawning = false,
    }
    -- no script: the proxy must not run the original actor's quest logic in our world
    fields.mwscript = ''
    local ok, draft = pcall(T.createRecordDraft, fields)
    if not ok then
        fields.mwscript = nil
        ok, draft = pcall(T.createRecordDraft, fields)
    end
    if not ok then ctx.log('cannot create proxy record for ' .. recordId .. ': ' .. tostring(draft)) return nil end
    records[cacheKey] = world.createRecord(draft)
    recordCount = recordCount + 1
    return records[cacheKey]
end

local function place(px)
    px.placing = true -- teleport is applied next frame; it also enables the object
    local e = px.last
    px.obj:teleport(ctx.cellKey(ctx.player().cell), util.vector3(e.p[1], e.p[2], e.p[3]),
        { rotation = util.transform.rotateZ(e.y or 0) })
end

local function spawn(owner, e, key)
    local rec = recordFor(owner, e.r)
    if not rec then return nil end
    local obj = world.createObject(rec.id, 1)
    obj:addScript(REMOTE_SCRIPT, { proxy = true, key = key })
    ctx.blockActivation(obj) -- no dialogue/pickpocket on a stand-in
    local px = { owner = owner, obj = obj, key = key, last = e }
    proxies[key] = px
    proxyById[obj.id] = px
    place(px)
    ctx.log('proxy for ' .. e.r .. ' (' .. e.n .. ') from ' .. ctx.ownerName(owner))
    return px
end

local function kill(px)
    if px.gone then return end
    px.gone = true
    if px.obj and px.obj:isValid() then px.obj:sendEvent('MpKill') end
end

local function onRoster(msg)
    local p = ctx.player()
    local owner = msg.id
    if not p or not owner or type(msg.list) ~= 'table' then return end
    ownerCells[owner] = msg.cell
    local sameCell = msg.cell == ctx.cellKey(p.cell)
    for _, e in ipairs(msg.list) do
        local key = e.n -- one proxy per actor, whoever owns it
        local px = proxies[key]
        if px and px.owner ~= owner then
            px = nil -- another member's copy of this actor is already standing in for it
        elseif px and not needsProxy(e.n) then
            removeProxy(px) -- our own copy of the actor is back (respawned/enabled): no stand-in needed
            px = nil
        elseif not px and sameCell and not proxies[key] and needsProxy(e.n) and not livingTwinNearby(e) then
            px = spawn(owner, e, key)
        end
        if not px and sameCell and ctx.sharedFights() then M.syncFight(e) end
        if px then
            px.last = e
            if sameCell and px.obj:isValid() and px.obj.enabled and not px.gone then
                px.obj:sendEvent('MpTarget', {
                    pos = util.vector3(e.p[1], e.p[2], e.p[3]), yaw = e.y, hp = e.h,
                })
            end
        end
    end
    for _, nid in ipairs(msg.dead or {}) do
        local px = proxies[nid]
        if px and px.owner == owner then kill(px) end
        if ctx.sharedKills() then pendingKills[nid] = true end -- killed in their world: dead in ours too
    end
end

-- Is this entry from another player something we can use? (Strangers' messages are checked, not trusted.)
local function usableEntry(e)
    if type(e) ~= 'table' or type(e.n) ~= 'string' or #e.n > 100 or type(e.r) ~= 'string' or #e.r > 100 then return false end
    if type(e.p) ~= 'table' or type(e.p[1]) ~= 'number' or type(e.p[2]) ~= 'number' or type(e.p[3]) ~= 'number' then return false end
    return e.y == nil or type(e.y) == 'number'
end

local function strangerCount()
    local n = 0
    for _, px in pairs(proxies) do if px.stranger then n = n + 1 end end
    return n
end

-- A player who is not in our group lists the characters alive around them.
local function onPublicRoster(msg)
    if not ctx.strangerStandins() or not msg.id or ctx.isMember(msg.id) or type(msg.list) ~= 'table' then return end
    local p = ctx.player()
    if not p or msg.cell ~= ctx.cellKey(p.cell) then return end
    local count = 0
    for _, e in ipairs(msg.list) do
        count = count + 1
        if count > PUBLIC_MAX + 10 then break end
        if usableEntry(e) then
            local key = e.n
            local px = proxies[key]
            if px then
                if px.stranger and px.owner == msg.id then
                    px.last, px.seenAt = e, clock
                    if not px.gone and px.obj:isValid() and px.obj.enabled then
                        -- where their copy stands; its health is ours alone
                        px.obj:sendEvent('MpTarget', { pos = util.vector3(e.p[1], e.p[2], e.p[3]), yaw = e.y })
                    end
                end
            elseif goneSince[key] and clock - goneSince[key] >= STRANGER_DELAY and not finished[key .. '|' .. msg.id]
                and strangerCount() < STRANGER_MAX and needsProxy(key) and not livingTwinNearby(e) then
                local s = spawn(msg.id, e, key)
                if s then s.stranger, s.seenAt = true, clock end
            end
        end
    end
end

-- Shared fights: both worlds have this actor alive, so each client lowers its own copy to the lowest health any
-- group member reports (never raises it: that is idempotent, so two clients cannot echo damage back and forth).
-- The damage goes through damage.lua, which never kills (the kill itself is shared separately) and makes the
-- actor turn on us, so a fight one member starts is a fight for everyone.
function M.syncFight(e)
    if fightSync[e.n] and fightSync[e.n] > 0 then return end
    local obj = objectById(e.n)
    if not obj or not obj.enabled or types.Actor.isDead(obj) or M.isProxy(obj) then return end
    local drop = healthRatio(obj) - (e.h or 1)
    if drop < FIGHT_MIN_DROP then return end
    fightSync[e.n] = FIGHT_COOLDOWN
    ctx.dlog('fight', e.n, 'group member reports lower health, applying', drop)
    damageQueue[#damageQueue + 1] = { obj = obj, frac = drop, wait = 0 }
end

-- Another member damaged our real actor's proxy in their world.
local function onDamage(msg)
    if msg.kill then -- they killed the stand-in for our actor
        if ctx.sharedKills() then pendingKills[msg.nid] = true end
        return
    end
    local obj = objectById(msg.nid)
    if obj and not types.Actor.isDead(obj) then
        damageQueue[#damageQueue + 1] = { obj = obj, frac = msg.frac, wait = 0 }
    end
end

function M.onMessage(msg)
    if msg.t == 'actors' then onRoster(msg) return true end
    if msg.t == 'pubactors' then onPublicRoster(msg) return true end
    if msg.t == 'dmg' then
        if msg.to == ctx.myId() then onDamage(msg) end
        return true
    end
    return false
end

-- Event from a proxy's local script: it died here, so its owner's real actor must die too.
function M.onProxyDied(d)
    local px = proxies[d.key]
    if not px then return end
    if px.stranger then -- killed here: it stays dead here, and the other world never hears of it
        finished[px.key .. '|' .. px.owner] = true
        px.gone, px.removeAt = true, clock + DEAD_STANDIN_LINGER
        return
    end
    if not ctx.sharedKills() then return end
    ctx.send({ t = 'dmg', to = px.owner, nid = px.last.n, kill = true })
end

-- Kills waiting for their actor to be loaded (saved with the game).
function M.getPendingKills()
    local list = {}
    for nid in pairs(pendingKills) do list[#list + 1] = nid end
    return list
end
function M.setPendingKills(list)
    pendingKills = {}
    for _, nid in ipairs(list or {}) do pendingKills[nid] = true end
end

-- Event from a proxy's local script: we hurt it.
function M.onProxyDamage(d)
    local px = proxies[d.key]
    if not px then return end
    if not px.stranger then ctx.send({ t = 'dmg', to = px.owner, nid = px.last.n, frac = d.frac }) end -- (strangers: ours only)
    crimeGuard = CRIME_WINDOW + 0.5
end

------------------------------------------------------------------ per-frame

local function updateCrime(dt, p)
    if not SUPPRESS_PROXY_CRIME then return end
    local ok, level = pcall(types.Player.getCrimeLevel, p)
    if not ok then return end
    if crimeGuard > 0 then
        crimeGuard = crimeGuard - dt
        if not crimeBase then
            crimeBase = level
            for _, s in ipairs(crimeSamples) do crimeBase = math.min(crimeBase, s.level) end
        end
        if level > crimeBase then pcall(types.Player.setCrimeLevel, p, crimeBase) end
    else
        crimeBase = nil
        crimeSamples[#crimeSamples + 1] = { t = clock, level = level }
        while #crimeSamples > 0 and clock - crimeSamples[1].t > CRIME_WINDOW do table.remove(crimeSamples, 1) end
    end
end

function M.onUpdate(dt)
    clock = clock + dt
    local p = ctx.player()
    if not p then return end
    updateCrime(dt, p)
    for nid, left in pairs(fightSync) do
        if left <= dt then fightSync[nid] = nil else fightSync[nid] = left - dt end
    end

    -- deliver queued damage to our real actors
    for i = #damageQueue, 1, -1 do
        local d = damageQueue[i]
        d.wait = d.wait - dt
        if not d.obj:isValid() or types.Actor.isDead(d.obj) then
            table.remove(damageQueue, i)
        elseif d.wait <= 0 then
            if not d.obj:hasScript(DAMAGE_SCRIPT) then
                d.obj:addScript(DAMAGE_SCRIPT)
                d.wait = 0.2
            else
                d.obj:sendEvent(d.kill and 'MpKillNow' or 'MpDamage', { frac = d.frac })
                table.remove(damageQueue, i)
            end
        end
    end

    -- carry out kills our group made: the actor dies here as well (once it is loaded)
    killTimer = killTimer + dt
    if killTimer >= 0.5 then
        killTimer = 0
        for nid in pairs(pendingKills) do
            local obj = objectById(nid)
            if obj and (types.Actor.isDead(obj) or M.isProxy(obj)) then
                pendingKills[nid] = nil
            elseif obj then
                pendingKills[nid] = nil
                ctx.log('group kill: ' .. tostring(obj.recordId) .. ' (' .. nid .. ') dies in this world too')
                damageQueue[#damageQueue + 1] = { obj = obj, frac = 1, wait = 0, kill = true }
            end
        end
    end

    -- a stand-in must not outlive its reason: if our own copy of the actor is alive and enabled again, drop it
    cleanTimer = cleanTimer + dt
    if cleanTimer >= 1 then
        cleanTimer = 0
        for _, px in pairs(proxies) do
            if not px.gone and not needsProxy(px.last.n) then removeProxy(px) end
        end
    end

    -- other worlds' stand-ins: note which of our actors are gone, drop stand-ins nobody lists any more
    goneTimer = goneTimer + dt
    if goneTimer >= 2 then
        goneTimer = 0
        trackGone()
        for _, px in pairs(proxies) do
            if px.stranger and ((px.gone and px.removeAt and clock >= px.removeAt)
                or (not px.gone and clock - (px.seenAt or clock) > STRANGER_STALE)) then
                removeProxy(px)
            end
        end
    end

    -- proxies are only present while their owner shares our cell
    local here = ctx.cellKey(p.cell)
    for _, px in pairs(proxies) do
        if px.obj:isValid() then
            local together = ownerCells[px.owner] == here
            if px.obj.enabled then px.placing = false end
            if together and not px.obj.enabled and not px.placing then
                ctx.dlog('proxy', px.key, 'enabled again, re-placing')
                place(px)
            elseif not together and px.obj.enabled then
                ctx.dlog('proxy', px.key, 'hidden: owner cell', tostring(ownerCells[px.owner]), 'vs', here)
                px.obj.enabled = false
            end
        end
    end

    if ctx.connected() and ctx.strangerStandins() then
        publicTimer = publicTimer + dt
        if publicTimer >= PUBLIC_INTERVAL then
            publicTimer = 0
            sendPublicRoster()
        end
    end
    if ctx.active() then
        rosterTimer = rosterTimer + dt
        if rosterTimer >= ROSTER_INTERVAL then
            rosterTimer = 0
            sendRoster()
        end
    end
end

-- A group member's avatar was hit in this world: it can't be hurt, and it must not cost us a bounty.
function M.onAvatarHit()
    crimeGuard = CRIME_WINDOW + 0.5
end

-- A proxy fell too far from where its owner's actor is.
function M.onSnap(actor)
    local px = proxyById[actor.id]
    if px and not px.gone then place(px) return true end
    return false
end

-- (loot.lua finds containers and corpses by the same cross-client id)
M.contentId = contentId
M.objectById = objectById

return M
