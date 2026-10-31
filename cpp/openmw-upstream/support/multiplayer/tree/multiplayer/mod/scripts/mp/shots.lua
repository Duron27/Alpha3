-- Arrows, thrown weapons and spell bolts of other players, as harmless visuals. The shooter's game works out where
-- the shot ends (it casts the ray in its own world, and tells us again where a bolt really hit: onImpact); here an
-- object flies from the start to that point, a bolt glows in its spell's colour and hums its sound, and where it lands
-- the spell's hit effect, its area burst and its sound play. Nothing here damages anything.
local world = require('openmw.world')
local core = require('openmw.core')
local types = require('openmw.types')
local util = require('openmw.util')

local M = {}

local MAX_SHOTS = 40
local MAX_RANGE = 8000
local MAX_LIFETIME = 6
local MIN_SPEED, MAX_SPEED = 300, 6000
local SHOW_DISTANCE = 6000 -- shots further than this from us are not worth showing
local GLOW_RADIUS = 220
local AFTERGLOW = 1.2 -- seconds the light (and the sound it carries) stays where a bolt burst

local ctx -- { player, cellKey, blockActivation, log }
local shots = {} -- { obj, glow, from, to, dir, length, travelled, speed, rot, fx, age, owner, sid }
local embers = {} -- { obj, left }: lights left where a bolt burst, carrying its sound
local glowRecords = {} -- colour key -> Light record id (false: cannot be made)

function M.init(context) ctx = context end

local function removeObj(o)
    if o and o:isValid() then pcall(function() o:remove() end) end
end

function M.reset()
    for _, s in ipairs(shots) do removeObj(s.obj) removeObj(s.glow) end
    for _, e in ipairs(embers) do removeObj(e.obj) end
    shots, embers = {}, {}
end

function M.savedObjects()
    local list = {}
    for _, s in ipairs(shots) do
        if s.obj and s.obj:isValid() then list[#list + 1] = s.obj end
        if s.glow and s.glow:isValid() then list[#list + 1] = s.glow end
    end
    for _, e in ipairs(embers) do if e.obj:isValid() then list[#list + 1] = e.obj end end
    return list
end

local function vec(t)
    if type(t) ~= 'table' then return nil end
    local x, y, z = tonumber(t[1]), tonumber(t[2]), tonumber(t[3])
    if not (x and y and z) then return nil end
    return util.vector3(x, y, z)
end

-- Facing along dir: yaw about Z, then pitch (the engine's positive pitch is downwards).
local function rotationFor(dir)
    local yaw = math.atan2(dir.x, dir.y)
    local pitch = -math.asin(math.max(-1, math.min(1, dir.z)))
    return util.transform.rotateZ(yaw) * util.transform.rotateX(pitch)
end

local function staticModel(id)
    if not id or id == '' then return nil end
    local ok, rec = pcall(function() return types.Static.records[id] end)
    return ok and rec and rec.model or nil
end

local function effectRecord(id)
    local ok, rec = pcall(function() return core.magic.effects.records[id] end)
    return ok and rec or nil
end

-- A light with no model in the spell's colour: the glow a bolt carries, and something to play its sounds from.
local function glowFor(mgef)
    if not mgef or not mgef.color then return nil end
    local c = mgef.color
    local key = string.format('%d_%d_%d', math.floor(c.r * 255), math.floor(c.g * 255), math.floor(c.b * 255))
    if glowRecords[key] == nil then
        glowRecords[key] = false
        pcall(function()
            local rec = world.createRecord(types.Light.createRecordDraft({
                name = '', model = '', icon = '', radius = GLOW_RADIUS, color = c, duration = -1, weight = 0, value = 0,
                isDynamic = true, isOffByDefault = false, isFire = false, isFlicker = false, isPulse = false, isCarriable = false,
            }))
            glowRecords[key] = rec.id
        end)
    end
    local id = glowRecords[key]
    if not id then return nil end
    local ok, obj = pcall(world.createObject, id, 1)
    return ok and obj or nil
end

local function play3d(sound, obj, opts)
    if sound and sound ~= '' and obj and obj:isValid() then pcall(core.sound.playSound3d, sound, obj, opts) end
end

-- Where a bolt bursts: each effect's hit effect, its area burst (as big as the spell's area) and their sounds.
local function burst(s)
    local at = s.from + s.dir * math.min(s.travelled, s.length)
    if s.fx then
        local played = {}
        for _, f in ipairs(s.fx) do
            local mgef = effectRecord(f.e)
            if mgef then
                local hit = staticModel(mgef.hitStatic) or staticModel('VFX_DefaultHit')
                if hit and not played[hit] then
                    played[hit] = true
                    pcall(world.vfx.spawn, hit, at, { particleTextureOverride = mgef.particle, useAmbientLight = false })
                end
                local area = tonumber(f.a) or 0
                if area > 0 then
                    local model = staticModel(mgef.areaStatic) or staticModel('VFX_DefaultArea')
                    if model then
                        pcall(world.vfx.spawn, model, at, { particleTextureOverride = mgef.particle, scale = area * 2,
                            useAmbientLight = false })
                    end
                end
            end
        end
        local first = effectRecord(s.fx[1] and s.fx[1].e)
        if s.glow and s.glow:isValid() then
            pcall(core.sound.stopSound3d, first and first.boltSound or '', s.glow)
            s.glow:teleport(ctx.cellKey(ctx.player().cell), at)
            local area = tonumber(s.fx[1].a) or 0
            play3d(first and (area > 0 and first.areaSound or first.hitSound), s.glow, { volume = 1 })
            embers[#embers + 1] = { obj = s.glow, left = AFTERGLOW }
            s.glow = nil
        end
    elseif s.hitModel then
        pcall(world.vfx.spawn, s.hitModel, at, { useAmbientLight = false })
    end
end

-- msg: { id = shooter, k = 'bolt' | 'arrow', rec = record id of the flying object, fx = { { e, r, a } } the spell's
--        effects (bolts), from, to = { x, y, z }, spd = units/s, hitpos = true if the shot ended on something, sid }
function M.onShot(msg)
    local p = ctx.player()
    if not p or #shots >= MAX_SHOTS then return end
    local from, to = vec(msg.from), vec(msg.to)
    if not from or not to or type(msg.rec) ~= 'string' then return end
    if (from - p.position):length() > SHOW_DISTANCE then return end
    local delta = to - from
    local length = delta:length()
    if length < 1 or length > MAX_RANGE then return end
    local speed = math.max(MIN_SPEED, math.min(MAX_SPEED, tonumber(msg.spd) or 1500))
    local ok, obj = pcall(world.createObject, msg.rec, 1)
    if not ok or not obj then return end
    pcall(ctx.blockActivation, obj)
    local dir = delta / length
    local rot = rotationFor(dir)
    local cell = ctx.cellKey(p.cell)
    obj:teleport(cell, from, { rotation = rot })
    local fx = type(msg.fx) == 'table' and #msg.fx > 0 and msg.fx or nil
    local glow
    if msg.k == 'bolt' and fx then
        local first = effectRecord(fx[1].e)
        glow = glowFor(first)
        if glow then
            glow:teleport(cell, from)
            play3d(first.boltSound, glow, { loop = true, volume = 1 })
        end
    end
    shots[#shots + 1] = {
        obj = obj, glow = glow, from = from, to = to, dir = dir, length = length, travelled = 0, speed = speed, rot = rot,
        age = 0, fx = fx, owner = msg.id, sid = msg.sid, fresh = true,
        hitModel = msg.k == 'arrow' and msg.hitpos ~= false and staticModel(msg.hit) or nil,
    }
end

-- Where one of their bolts really hit (their world told them): it now flies there from where it is.
function M.onImpact(msg)
    local to = vec(msg.to)
    if not to then return end
    for _, s in ipairs(shots) do
        if s.owner == msg.id and s.sid == msg.sid then
            local here = s.from + s.dir * math.min(s.travelled, s.length)
            local delta = to - here
            local left = delta:length()
            if left < 1 then s.travelled = s.length return end
            s.from, s.dir, s.travelled, s.length = here, delta / left, 0, left
            s.rot = rotationFor(s.dir)
            return
        end
    end
end

function M.onUpdate(dt)
    local p = ctx.player()
    for i = #embers, 1, -1 do
        local e = embers[i]
        e.left = e.left - dt
        if e.left <= 0 or not e.obj:isValid() then removeObj(e.obj) table.remove(embers, i) end
    end
    for i = #shots, 1, -1 do
        local s = shots[i]
        s.age = s.age + dt
        s.travelled = s.travelled + s.speed * dt
        local gone = not p or not s.obj:isValid() or s.age > MAX_LIFETIME
        if not gone and s.travelled >= s.length then
            burst(s)
            gone = true
        end
        if gone then
            removeObj(s.obj)
            removeObj(s.glow)
            table.remove(shots, i)
        elseif s.fresh then
            s.fresh = nil -- (placed this frame already: an object can only be moved once a frame)
        else
            local at = s.from + s.dir * s.travelled
            local cell = ctx.cellKey(p.cell)
            s.obj:teleport(cell, at, { rotation = s.rot })
            if s.glow and s.glow:isValid() then s.glow:teleport(cell, at) end
        end
    end
end

return M
