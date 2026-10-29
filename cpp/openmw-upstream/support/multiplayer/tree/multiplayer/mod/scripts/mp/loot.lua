-- Shared loot inside a group. Every player keeps their own world, so a chest one member empties is still full for the
-- others. With this on, what a member takes from a container or a corpse is taken from the same container in the other
-- members' worlds too, so the group does not loot the same thing twice.
--
-- It only ever REMOVES, and only ordinary loot: gold, ingredients, potions, lockpicks, probes, repair tools and ammunition
-- (and, with a second setting, plain weapons and armor). Anything with a script, an enchantment or a generated record, keys,
-- books, soul gems and every other kind of item is left alone, so nothing a quest depends on can disappear from someone
-- else's world. The receiver decides what is shareable from its own objects, never from what the sender claims.
local types = require('openmw.types')

local M = {}

local WATCH_RANGE = 450 -- containers and corpses this near the player are watched while a container window is open
local TICK = 0.25 -- seconds between looks at what changed
local LINGER = 1.5 -- keep watching this long after the window closes (the last click)
local APPLY_INTERVAL = 1.0
local MAX_ENTRIES = 30 -- item kinds in one message
local MAX_COUNT = 1000000
local MAX_ATTEMPTS = 20 -- a removal that keeps failing is dropped

local ctx -- { send, dlog, notice, player, objectById, contentId, isAvatar, enabled, gear, active, containerOpen }
local watching = {} -- object id -> { obj, cid, items }
local pending = {} -- container content id -> { items = { [recordId] = count }, tries = n }
local tickTimer, applyTimer, linger = 0, 0, 0
local wasOpen = false
local told = false

function M.init(context) ctx = context end

function M.reset()
    watching, wasOpen, linger = {}, false, 0
end

------------------------------------------------------------------ what may be shared

local function emptyScript(rec) return rec.mwscript == nil or rec.mwscript == '' end
local function plain(rec) return emptyScript(rec) and (rec.enchant == nil or rec.enchant == '') end

-- Gold, ingredients, potions, lockpicks, probes, repair tools and ammunition; with `gear` also unenchanted weapons and
-- armor and clothing. Never anything with a script, an enchantment or a generated record.
function M.shareable(item, gear)
    local ok, result = pcall(function()
        local id = item.recordId
        if id:lower():find('^generated:') then return false end
        local t = item.type
        local rec = t.record(item)
        if not emptyScript(rec) then return false end
        if t == types.Miscellaneous then return id:lower():find('^gold_%d+$') ~= nil end
        if t == types.Ingredient or t == types.Potion or t == types.Lockpick or t == types.Probe or t == types.Repair then
            return true
        end
        if t == types.Weapon then
            if not plain(rec) then return false end
            local w = types.Weapon.TYPE
            if rec.type == w.Arrow or rec.type == w.Bolt or rec.type == w.MarksmanThrown then return true end
            return gear and true or false
        end
        if t == types.Armor or t == types.Clothing then return gear and plain(rec) and true or false end
        return false
    end)
    return ok and result or false
end

local function inventoryOf(obj)
    if obj.type == types.Container then return types.Container.inventory(obj) end
    return types.Actor.inventory(obj)
end

-- { [recordId] = count } of the shareable items in a container or corpse, or nil if it has not been filled in yet.
function M.snapshot(obj, gear)
    local ok, items = pcall(function()
        local inv = inventoryOf(obj)
        if not inv:isResolved() then return nil end
        local out = {}
        for _, item in ipairs(inv:getAll()) do
            if M.shareable(item, gear) then out[item.recordId] = (out[item.recordId] or 0) + item.count end
        end
        return out
    end)
    return ok and items or nil
end

-- What went missing between two snapshots: { [recordId] = count removed }, and whether anything did.
function M.removed(before, after)
    local out, any = {}, false
    for id, n in pairs(before) do
        local now = after[id] or 0
        if now < n then out[id] = n - now any = true end
    end
    return out, any
end

------------------------------------------------------------------ sending what we took

local function candidates(p)
    local list = {}
    local function add(obj)
        if (obj.position - p.position):length() <= WATCH_RANGE and ctx.contentId(obj) then list[#list + 1] = obj end
    end
    pcall(function()
        for _, c in ipairs(p.cell:getAll(types.Container)) do add(c) end
        for _, kind in ipairs({ types.NPC, types.Creature }) do
            for _, a in ipairs(p.cell:getAll(kind)) do
                if a ~= p and types.Actor.isDead(a) and not ctx.isAvatar(a) then add(a) end
            end
        end
    end)
    return list
end

local function startWatching(p)
    watching = {}
    local gear = ctx.gear()
    for _, obj in ipairs(candidates(p)) do
        local items = M.snapshot(obj, gear)
        if items then watching[obj.id] = { obj = obj, cid = ctx.contentId(obj), items = items } end
    end
end

local function send(cid, removed)
    local batch, n = {}, 0
    for id, count in pairs(removed) do
        batch[id] = count
        n = n + 1
        if n >= MAX_ENTRIES then
            ctx.send({ t = 'loot', cid = cid, items = batch })
            batch, n = {}, 0
        end
    end
    if n > 0 then ctx.send({ t = 'loot', cid = cid, items = batch }) end
end

local function look()
    local gear = ctx.gear()
    for id, w in pairs(watching) do
        if not w.obj:isValid() then
            watching[id] = nil
        else
            local now = M.snapshot(w.obj, gear)
            if now then
                local gone, any = M.removed(w.items, now)
                w.items = now
                if any then
                    ctx.dlog('loot', w.cid, 'taken here, telling the group')
                    send(w.cid, gone)
                end
            end
        end
    end
end

------------------------------------------------------------------ applying what the others took

-- Returns true if the message was ours. Removals wait (and are saved with the game) until their container is loaded.
function M.onMessage(msg)
    if not ctx.enabled() or type(msg.cid) ~= 'string' or #msg.cid > 100 or type(msg.items) ~= 'table' then return end
    local entry = pending[msg.cid] or { items = {}, tries = 0 }
    local n, added = 0, 0
    for id, count in pairs(msg.items) do
        n = n + 1
        if n > MAX_ENTRIES then break end
        if type(id) == 'string' and #id <= 120 and type(count) == 'number' and count >= 1 and count <= MAX_COUNT then
            entry.items[id] = (entry.items[id] or 0) + math.floor(count)
            added = added + 1
        end
    end
    if added == 0 then return end -- nothing usable: nothing to remember
    pending[msg.cid] = entry
    if not told then
        told = true
        ctx.notice('Ordinary loot your group takes is taken from your copy of the container too.')
    end
end

local function applyTo(obj, items)
    local gear = ctx.gear()
    local inv = inventoryOf(obj)
    if not inv:isResolved() then inv:resolve() end
    for id, count in pairs(items) do
        local left = count
        for _, item in ipairs(inv:findAll(id)) do
            if left <= 0 then break end
            if M.shareable(item, gear) then -- what counts as ordinary loot is decided here, from our own object
                local take = math.min(left, item.count)
                item:remove(take)
                left = left - take
            end
        end
    end
end

local function apply()
    for cid, entry in pairs(pending) do
        local obj = ctx.objectById(cid)
        if obj then
            local ok, err = pcall(applyTo, obj, entry.items)
            if ok then
                pending[cid] = nil
                local w = watching[obj.id]
                if w then w.items = M.snapshot(obj, ctx.gear()) or w.items end -- (our own removal is not news)
                ctx.dlog('loot', cid, 'removed what the group took')
            else
                entry.tries = entry.tries + 1
                if entry.tries >= MAX_ATTEMPTS then
                    pending[cid] = nil
                    ctx.dlog('loot', cid, 'gave up:', tostring(err))
                end
            end
        end
    end
end

------------------------------------------------------------------ per frame, saving

function M.onUpdate(dt)
    if not ctx.enabled() then return end
    local p = ctx.player()
    if not p then return end
    local open = ctx.containerOpen()
    if ctx.active() then
        if open and not wasOpen then
            startWatching(p) -- a container window just opened: remember what is in everything around us
        end
        if open then linger = LINGER else linger = math.max(0, linger - dt) end
        tickTimer = tickTimer + dt
        if (open or linger > 0) and tickTimer >= TICK then
            tickTimer = 0
            look()
        end
        if not open and linger <= 0 then watching = {} end
    end
    wasOpen = open
    applyTimer = applyTimer + dt
    if applyTimer >= APPLY_INTERVAL then
        applyTimer = 0
        if not open and next(pending) then apply() end -- (never change a container the player is looking at)
    end
end

-- For the save file: removals still waiting for their container.
function M.getPending()
    local out = {}
    for cid, entry in pairs(pending) do out[cid] = entry.items end
    return out
end

function M.setPending(saved)
    pending = {}
    for cid, items in pairs(saved or {}) do
        if type(cid) == 'string' and type(items) == 'table' then pending[cid] = { items = items, tries = 0 } end
    end
end

return M
