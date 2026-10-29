-- Items made while playing (enchanting, alchemy, scrolls...) have record ids that exist only on one machine. This
-- module describes such an item as plain data, gives it a key both sides can compute (so a trade can name it), and
-- rebuilds it from the description on the other side (global scripts only). Everything that arrives from another
-- player is validated and clamped: unknown effects or missing models are refused, numbers are kept in sane ranges.
local core = require('openmw.core')
local types = require('openmw.types')

local world, vfs
pcall(function() world = require('openmw.world') end) -- not available in player scripts
pcall(function() vfs = require('openmw.vfs') end)

local M = {}

M.PREFIX = 'gen:'

local KINDS = { 'Weapon', 'Armor', 'Clothing', 'Book', 'Potion' }
-- fields copied into the description; every kind also gets the common ones
local COMMON = { 'name', 'model', 'icon', 'weight', 'value' }
local FIELDS = {
    Weapon = { 'type', 'health', 'speed', 'reach', 'enchantCapacity', 'chopMinDamage', 'chopMaxDamage', 'slashMinDamage',
        'slashMaxDamage', 'thrustMinDamage', 'thrustMaxDamage' },
    Armor = { 'type', 'health', 'baseArmor', 'enchantCapacity' },
    Clothing = { 'type', 'enchantCapacity' },
    Book = { 'skill', 'isScroll', 'enchantCapacity', 'text' },
    Potion = { 'isAutocalc' },
}
local MAX_TEXT = 8000
local MAX_NAME = 80
local MAX_NUMBER = 1000000
local MAX_EFFECTS = 8

local function effectsOf(list)
    local out = {}
    for _, e in ipairs(list or {}) do
        if #out >= MAX_EFFECTS then break end
        out[#out + 1] = { id = e.id, range = e.range, area = e.area, dur = e.duration, min = e.magnitudeMin,
            max = e.magnitudeMax, skill = e.affectedSkill, attr = e.affectedAttribute }
    end
    return out
end

-- Description of a generated item (a table of plain values), or nil if this kind of item cannot be described.
function M.describe(obj)
    for _, kind in ipairs(KINDS) do
        if obj.type == types[kind] then
            local ok, desc = pcall(function()
                local rec = types[kind].record(obj)
                local d = { k = kind, f = {} }
                for _, group in ipairs({ COMMON, FIELDS[kind] }) do
                    for _, field in ipairs(group) do
                        if rec[field] ~= nil then d.f[field] = rec[field] end
                    end
                end
                if kind == 'Potion' then d.fx = effectsOf(rec.effects) end
                if rec.enchant and rec.enchant ~= '' then
                    local ench = core.magic.enchantments.records[rec.enchant]
                    if ench then
                        d.e = { type = ench.type, cost = ench.cost, charge = ench.charge, auto = ench.isAutocalc,
                            fx = effectsOf(ench.effects) }
                    end
                end
                return d
            end)
            if ok then return desc end
            return nil
        end
    end
    return nil
end

local function canon(v)
    local t = type(v)
    if t == 'number' then return string.format('%.6g', v) end
    if t ~= 'table' then return tostring(v) end
    if v[1] ~= nil then
        local parts = {}
        for i, x in ipairs(v) do parts[i] = canon(x) end
        return '[' .. table.concat(parts, ',') .. ']'
    end
    local keys = {}
    for k in pairs(v) do keys[#keys + 1] = k end
    table.sort(keys)
    local parts = {}
    for _, k in ipairs(keys) do parts[#parts + 1] = k .. '=' .. canon(v[k]) end
    return '{' .. table.concat(parts, ',') .. '}'
end

-- The same description gives the same key on every machine.
function M.key(desc)
    local s = canon(desc)
    local a, b = 5381, 0
    for i = 1, #s do
        local c = s:byte(i)
        a = (a * 33 + c) % 4294967296
        b = (b * 131 + c + i) % 4294967291
    end
    return string.format('%s%08x%08x', M.PREFIX, a, b)
end

function M.isKey(id) return type(id) == 'string' and id:sub(1, #M.PREFIX) == M.PREFIX end

function M.name(desc) return desc and desc.f and desc.f.name or '?' end

------------------------------------------------------------------ validation and rebuilding (global scripts)

local function num(x, lo, hi)
    x = tonumber(x)
    if not x or x ~= x then return nil end
    return math.max(lo or 0, math.min(hi or MAX_NUMBER, x))
end

local function fileExists(path)
    if type(path) ~= 'string' or path == '' then return false end
    if not vfs then return true end
    local ok, exists = pcall(vfs.fileExists, path)
    return ok and exists
end

-- Effects must be known ones, with values in range.
local function cleanEffects(list)
    if type(list) ~= 'table' or #list == 0 or #list > MAX_EFFECTS then return nil end
    local out = {}
    for _, e in ipairs(list) do
        if type(e) ~= 'table' or not core.magic.effects.records[e.id] then return nil end
        out[#out + 1] = {
            id = e.id, range = num(e.range, 0, 2) or 0, area = num(e.area, 0, 1000) or 0,
            duration = num(e.dur, 0, 100000) or 0, magnitudeMin = num(e.min, 0, 10000) or 0,
            magnitudeMax = num(e.max, 0, 10000) or 0,
            affectedSkill = type(e.skill) == 'string' and e.skill or nil,
            affectedAttribute = type(e.attr) == 'string' and e.attr or nil,
        }
    end
    return out
end

local built = {} -- key -> record id (or false)

-- Creates (once) the local record for a description and returns its id, or nil with the reason.
function M.rebuild(desc)
    if not world then return nil, 'not a global script' end
    if type(desc) ~= 'table' or type(desc.f) ~= 'table' or not FIELDS[desc.k] then return nil, 'unknown kind' end
    local key = M.key(desc)
    if built[key] ~= nil then
        if built[key] then return built[key] end
        return nil, 'previously failed'
    end
    built[key] = false
    local kind = desc.k
    local T = types[kind]
    local f = desc.f
    if type(f.name) ~= 'string' or f.name == '' or not fileExists(f.model) then return nil, 'bad name or model' end
    local fields = { name = f.name:sub(1, MAX_NAME), model = f.model, mwscript = '' }
    if fileExists(f.icon) then fields.icon = f.icon end
    for _, group in ipairs({ { 'weight', 'value' }, FIELDS[kind] }) do
        for _, field in ipairs(group) do
            local v = f[field]
            if field == 'text' then
                if type(v) == 'string' then fields.text = v:sub(1, MAX_TEXT) end
            elseif field == 'skill' then
                if type(v) == 'string' then fields.skill = v end
            elseif field == 'isScroll' or field == 'isAutocalc' then
                if type(v) == 'boolean' then fields[field] = v end
            elseif v ~= nil then
                local n = num(v)
                if n == nil then return nil, 'bad ' .. field end
                fields[field] = n
            end
        end
    end
    if kind == 'Potion' then
        fields.effects = cleanEffects(desc.fx)
        if not fields.effects then return nil, 'bad potion effects' end
    end
    if desc.e then
        local fx = cleanEffects(desc.e.fx)
        if not fx then return nil, 'bad enchantment' end
        local ok, record = pcall(function()
            return world.createRecord(core.magic.enchantments.createRecordDraft({
                type = num(desc.e.type, 0, 5) or 0, cost = num(desc.e.cost, 0, 100000) or 0,
                charge = num(desc.e.charge, 0, 100000) or 0, isAutocalc = desc.e.auto and true or false, effects = fx,
            }))
        end)
        if not ok then return nil, 'cannot create enchantment: ' .. tostring(record) end
        fields.enchant = record.id
    end
    -- gear needs a template with the same model, so its body parts show correctly
    if kind == 'Weapon' or kind == 'Armor' or kind == 'Clothing' then
        for _, rec in ipairs(T.records) do
            if rec.model == f.model and not rec.id:lower():find('^generated:') then fields.template = rec break end
        end
    end
    local ok, record = pcall(function() return world.createRecord(T.createRecordDraft(fields)) end)
    if not ok and fields.mwscript == '' then
        fields.mwscript = nil
        ok, record = pcall(function() return world.createRecord(T.createRecordDraft(fields)) end)
    end
    if not ok then return nil, 'cannot create record: ' .. tostring(record) end
    built[key] = record.id
    return record.id
end

function M.reset() built = {} end

return M
