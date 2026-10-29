-- DEBUG ONLY: maxes the player's attributes, skills and vitals and equips a bow with arrows.
-- Changes live in the running game; they reach a save only if you save while this is active.
local self = require('openmw.self')
local types = require('openmw.types')

local Actor, NPC = types.Actor, types.NPC
local WEAPON, AMMO = 'daedric long bow', 'daedric arrow'
local ATTRIBUTES = { 'strength', 'intelligence', 'willpower', 'agility', 'speed', 'endurance', 'personality', 'luck' }
local SKILLS = { 'block', 'armorer', 'mediumarmor', 'heavyarmor', 'bluntweapon', 'longblade', 'axe', 'spear',
    'athletics', 'enchant', 'destruction', 'alteration', 'illusion', 'conjuration', 'mysticism', 'restoration',
    'alchemy', 'unarmored', 'security', 'sneak', 'acrobatics', 'lightarmor', 'shortblade', 'marksman',
    'mercantile', 'speechcraft', 'handtohand' }

local applied, equipTimer, elapsed = false, 0, 0

local function apply()
    for _, a in ipairs(ATTRIBUTES) do Actor.stats.attributes[a](self).base = 200 end
    for _, s in ipairs(SKILLS) do NPC.stats.skills[s](self).base = 100 end
    for _, d in ipairs({ 'health', 'magicka', 'fatigue' }) do
        local stat = Actor.stats.dynamic[d](self)
        stat.base = 1000
        stat.current = 1000
    end
    print('[mp-debug] stats maxed')
end

return { engineHandlers = {
    onUpdate = function(dt)
        elapsed = elapsed + dt
        if not applied and elapsed > 0.5 then applied = true; apply() end
        equipTimer = equipTimer - dt
        if elapsed > 3 and equipTimer <= 0 and elapsed < 40 then
            equipTimer = 2
            local slots = Actor.EQUIPMENT_SLOT
            local right = Actor.getEquipment(self, slots.CarriedRight)
            local ammo = Actor.getEquipment(self, slots.Ammunition)
            local inv = Actor.inventory(self)
            if (not right or right.recordId ~= WEAPON or not ammo or ammo.recordId ~= AMMO)
                and inv:countOf(WEAPON) > 0 and inv:countOf(AMMO) > 0 then
                Actor.setEquipment(self, { [slots.CarriedRight] = WEAPON, [slots.Ammunition] = AMMO })
            elseif right and ammo then
                elapsed = 40 -- equipped, stop checking
            end
        end
    end,
} }
