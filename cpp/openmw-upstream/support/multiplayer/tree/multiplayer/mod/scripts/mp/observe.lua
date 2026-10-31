-- What the other players need to see of us, beyond where we stand (player script; player.lua drives it):
--  * every animation our character plays that is not walking or idling (attacks with their wind-up and strength, spell
--    casting with its kind, bow draws, hits taken, knock-downs), exactly as the engine plays it, so their copy of us
--    plays the same thing (I.AnimationController hands us each one with its keys, speed and priorities);
--  * the spell we have ready (its hands glow and its colour), and the magic on us that shows: shields, light,
--    chameleon, invisibility, levitation...;
--  * damage we take and healing we get, skills that go up: shown as numbers and words over our head in their game.
local core = require('openmw.core')
local self = require('openmw.self')
local types = require('openmw.types')
local I = require('openmw.interfaces')

local Actor = types.Actor
local M = {}

local enabled = function() return false end

------------------------------------------------------------------ animations

-- The animation groups worth showing to others; walking, running, idling, turning and jumping their copy does by itself.
local MIRROR = {
    handtohand = true, weapononehand = true, weapontwohand = true, weapontwowide = true,
    bowandarrow = true, crossbow = true, throwweapon = true, spellcast = true,
    hit1 = true, hit2 = true, hit3 = true, hit4 = true, hit5 = true,
    swimhit1 = true, swimhit2 = true, swimhit3 = true,
    knockdown = true, knockout = true, swimknockdown = true, swimknockout = true,
}
local MAX_PER_SECOND = 24
local window, windowCount = 0, 0

local function round(x, step) return math.floor(x / step + 0.5) * step end

-- The options the engine played it with, in a form that fits on the wire (the priority may be one number or one per
-- bone group, keyed 0..3).
local function packOptions(o)
    local packed = {
        s = o.startKey, e = o.stopKey, sp = round(tonumber(o.speed) or 1, 0.01), pt = round(tonumber(o.startPoint) or 0, 0.001),
        l = math.min(1000, tonumber(o.loops) or 0), ad = o.autoDisable and true or false, fl = o.forceLoop and true or nil,
        bm = tonumber(o.blendMask),
    }
    local p = o.priority
    if type(p) == 'number' then packed.pr = p
    elseif type(p) == 'table' then packed.pr = { p[0] or 0, p[1] or 0, p[2] or 0, p[3] or 0 } end
    return packed
end

local function onPlay(group, options)
    if not enabled() or not MIRROR[group] or type(options) ~= 'table' then return end
    local startKey = tostring(options.startKey or '')
    if startKey:find('equip') then return end -- drawing and sheathing: their copy does it when our stance changes
    local now = core.getRealTime()
    if math.floor(now) ~= window then window, windowCount = math.floor(now), 0 end
    windowCount = windowCount + 1
    if windowCount > MAX_PER_SECOND then return end
    core.sendGlobalEvent('MpAnim', { g = group, o = packOptions(options), ts = now })
end

------------------------------------------------------------------ magic

-- Effects whose look the others should see on us. 'vfx' ones loop their hit effect (shields...), 'state' ones change
-- how we are drawn (the engine does it once the effect is on our copy).
local SHOWN = {
    shield = 'vfx', fireshield = 'vfx', frostshield = 'vfx', lightningshield = 'vfx', sanctuary = 'vfx',
    reflect = 'vfx', spellabsorption = 'vfx', light = 'state', chameleon = 'state', invisibility = 'state',
    waterwalking = 'vfx', swiftswim = 'vfx', jump = 'vfx', slowfall = 'vfx', feather = 'vfx', fortifyattack = 'vfx',
    burden = 'vfx', paralyze = 'vfx', silence = 'vfx', blind = 'vfx', sound = 'vfx', calm = 'vfx', frenzy = 'vfx',
    demoralize = 'vfx', rally = 'vfx', soultrap = 'vfx', telekinesis = 'vfx', detectanimal = 'vfx',
    firedamage = 'vfx', frostdamage = 'vfx', shockdamage = 'vfx', poison = 'vfx', damagehealth = 'vfx',
    weaknesstofire = 'vfx', weaknesstofrost = 'vfx', weaknesstoshock = 'vfx', turnundead = 'vfx',
    resistfire = 'vfx', resistfrost = 'vfx', resistshock = 'vfx', resistmagicka = 'vfx', resistpoison = 'vfx',
    fortifyhealth = 'vfx', restorehealth = 'vfx', restorefatigue = 'vfx', restoremagicka = 'vfx',
}
local MAX_SHOWN = 8

-- The magic on us that shows, as { { e = effect id, m = magnitude } }, sorted so an unchanged list compares equal.
local function visibleEffects()
    local list, seen = {}, {}
    pcall(function()
        for _, spell in pairs(Actor.activeSpells(self)) do
            for _, fx in ipairs(spell.effects) do
                local id = fx.id
                if SHOWN[id] and not seen[id] then
                    local mgef = core.magic.effects.records[id]
                    -- only effects that last (a shield, a heal that ticks for 5 s, a ring of light): a one-off has nothing
                    -- to show, and abilities (a race's resistances) are not something anybody sees
                    if mgef and ((fx.duration or 0) > 0 or spell.fromEquipment) then
                        seen[id] = true
                        list[#list + 1] = { e = id, m = math.floor((fx.magnitudeThisFrame or fx.maxMagnitude or 0) + 0.5) }
                    end
                end
            end
        end
    end)
    table.sort(list, function(a, b) return a.e < b.e end)
    while #list > MAX_SHOWN do list[#list] = nil end
    return list
end

-- The spell (or enchanted item) we have ready: its first effect sets how the cast looks, its last the colour of the
-- hands (as the engine does).
local function readiedSpell()
    local ok, info = pcall(function()
        local effects
        local spell = Actor.getSelectedSpell(self)
        if spell then effects = spell.effects
        else
            local item = Actor.getSelectedEnchantedItem(self)
            local rec = item and item.type.record(item)
            local ench = rec and rec.enchant and core.magic.enchantments.records[rec.enchant]
            effects = ench and ench.effects
        end
        if not effects or #effects == 0 then return nil end
        local first, last = effects[1], effects[#effects]
        local list = {}
        for i, fx in ipairs(effects) do if i <= 4 then list[#list + 1] = fx.id end end
        return { first = first.id, last = last.id, range = first.range, list = list }
    end)
    return ok and info or nil
end

------------------------------------------------------------------ what happens to us

local lastHealth
local pendingHurt, pendingHeal, pendingAt = 0, 0, 0
local EVENT_GATHER = 0.2 -- damage over a few frames (a fire spell ticking) is shown as one number

local function trackHealth(dt)
    local h = Actor.stats.dynamic.health(self).current
    if lastHealth then
        local d = h - lastHealth
        if d < -0.05 then pendingHurt = pendingHurt - d elseif d > 0.05 then pendingHeal = pendingHeal + d end
    end
    lastHealth = h
    pendingAt = pendingAt + dt
    if pendingAt >= EVENT_GATHER then
        pendingAt = 0
        if pendingHurt >= 1 then core.sendGlobalEvent('MpEv', { k = 'dmg', n = math.floor(pendingHurt + 0.5) }) end
        if pendingHeal >= 1 then core.sendGlobalEvent('MpEv', { k = 'heal', n = math.floor(pendingHeal + 0.5) }) end
        pendingHurt, pendingHeal = 0, 0
    end
end

local function skillName(id)
    local ok, rec = pcall(function() return core.stats.Skill.records[id] end)
    return ok and rec and rec.name or tostring(id)
end

pcall(function()
    I.SkillProgression.addSkillLevelUpHandler(function(skillid, source)
        if not enabled() then return end
        local stat = Actor.stats.skills[skillid] and Actor.stats.skills[skillid](self)
        local value = stat and (stat.base + 1) or nil -- (the handlers run before the increase)
        core.sendGlobalEvent('MpEv', { k = 'skill', s = skillName(skillid), n = value })
    end)
end)

pcall(function() I.AnimationController.addPlayBlendedAnimationHandler(onPlay) end)

------------------------------------------------------------------ the module

local lookSig, lookAt = '', 0

-- Every frame: what changed is told to the global script (MpLook: readied spell and visible magic; MpEv: numbers).
function M.update(dt, isEnabled)
    enabled = isEnabled
    if not isEnabled() then return end
    trackHealth(dt)
    lookAt = lookAt + dt
    if lookAt < 0.25 then return end
    lookAt = 0
    local look = { fx = visibleEffects() }
    if Actor.getStance(self) == Actor.STANCE.Spell then look.spell = readiedSpell() end
    local parts = {}
    for _, f in ipairs(look.fx) do parts[#parts + 1] = f.e .. math.floor(f.m / 5) end
    if look.spell then parts[#parts + 1] = 'S' .. look.spell.first .. look.spell.last .. tostring(look.spell.range) end
    local sig = table.concat(parts, ',')
    if sig ~= lookSig then
        lookSig = sig
        core.sendGlobalEvent('MpLook', look)
    end
end

return M
