-- Attached on demand to a real NPC/creature in *this* world when a group member hurts that actor's
-- proxy in their own world. Applies the damage as a fraction of max health and makes the actor turn on
-- the local player, so a fight started by a guest becomes a shared fight.
local self = require('openmw.self')
local types = require('openmw.types')
local nearby = require('openmw.nearby')
local I = require('openmw.interfaces')

return {
    eventHandlers = {
        -- A group member killed this actor in their world; it dies here too (essential actors cannot die).
        MpKillNow = function()
            local essential = false
            pcall(function() essential = types.NPC.objectIsInstance(self) and types.NPC.record(self).isEssential end)
            if essential or types.Actor.isDead(self) then return end
            pcall(function() I.AI.removePackages() end)
            types.Actor.stats.dynamic.health(self).current = 0
        end,
        MpDamage = function(d)
            local health = types.Actor.stats.dynamic.health(self)
            local max = health.base + health.modifier
            -- A guest must never be able to kill an actor the owner may still need for a quest: their copy of the
            -- actor dies in their own world, ours is only wounded and stands down.
            local floor = math.min(max, 1)
            health.current = math.max(floor, health.current - d.frac * max)
            if health.current <= floor then
                pcall(function() I.AI.removePackages('Combat') end)
            else
                pcall(function()
                    local pkg = I.AI.getActivePackage()
                    if not (pkg and pkg.type == 'Combat') then
                        I.AI.startPackage({ type = 'Combat', target = nearby.players[1] })
                    end
                end)
            end
        end,
    },
}
