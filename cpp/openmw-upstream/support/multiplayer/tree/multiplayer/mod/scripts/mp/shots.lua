-- Arrows, thrown weapons and spell bolts of other players, as harmless visuals. The shooter's game works out where
-- the shot ends (it casts the ray in its own world); here an object just flies from the start to that point and the
-- spell's hit effect plays there. Nothing here damages anything: attacks of other players are animation only.
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

local ctx -- { player, cellKey, blockActivation, log }
local shots = {} -- { obj, from, dir, length, travelled, speed, rot, hitModel, age }

function M.init(context) ctx = context end

function M.reset()
    for _, s in ipairs(shots) do
        if s.obj and s.obj:isValid() then pcall(function() s.obj:remove() end) end
    end
    shots = {}
end

function M.savedObjects()
    local list = {}
    for _, s in ipairs(shots) do
        if s.obj and s.obj:isValid() then list[#list + 1] = s.obj end
    end
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

local function modelOf(kind, id)
    if not id then return nil end
    local ok, rec = pcall(function() return types[kind].records[id] end)
    return ok and rec and rec.model or nil
end

local function playHit(s)
    if not s.hitModel then return end
    pcall(world.vfx.spawn, s.hitModel, s.to, { useAmbientLight = false })
end

-- msg: { k = 'bolt' | 'arrow', rec = record id of the flying object, hit = static id of the hit effect,
--        from, to = { x, y, z }, spd = units/s, hitpos = true if the shot ended on something }
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
    obj:teleport(ctx.cellKey(p.cell), from, { rotation = rot })
    shots[#shots + 1] = {
        obj = obj, from = from, to = to, dir = dir, length = length, travelled = 0, speed = speed, rot = rot, age = 0,
        hitModel = msg.hitpos ~= false and modelOf('Static', msg.hit) or nil,
    }
end

function M.onUpdate(dt)
    local p = ctx.player()
    for i = #shots, 1, -1 do
        local s = shots[i]
        s.age = s.age + dt
        s.travelled = s.travelled + s.speed * dt
        local gone = not p or not s.obj:isValid() or s.age > MAX_LIFETIME
        if not gone and s.travelled >= s.length then
            playHit(s)
            gone = true
        end
        if gone then
            if s.obj:isValid() then pcall(function() s.obj:remove() end) end
            table.remove(shots, i)
        else
            s.obj:teleport(ctx.cellKey(p.cell), s.from + s.dir * s.travelled, { rotation = s.rot })
        end
    end
end

return M
