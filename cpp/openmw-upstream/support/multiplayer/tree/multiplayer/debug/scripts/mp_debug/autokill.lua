-- Test helper (host): invites the other player into a party, then kills the nearest living NPC/creature.
local core = require('openmw.core')
local world = require('openmw.world')
local types = require('openmw.types')
local t, invited, killed = 0, false, false
return {
    engineHandlers = {
        onUpdate = function(dt)
            t = t + dt
            if t > 75 and not invited then
                invited = true
                core.sendGlobalEvent('MpCmd', { cmd = 'invite', id = 2 })
            end
            if t > 105 and not killed then
                killed = true
                local p = world.players[1]
                local best, bestD
                for _, a in ipairs(world.activeActors) do
                    if a ~= p and (a.type == types.NPC or a.type == types.Creature) and not types.Actor.isDead(a) and not tostring(a.recordId):find('Generated') then
                        local d = (a.position - p.position):length()
                        if d < 3000 and (not bestD or d < bestD) then best, bestD = a, d end
                    end
                end
                if best then
                    print('[mp-debug] killing ' .. best.recordId .. ' ' .. tostring(best.id) .. ' at distance ' .. math.floor(bestD))
                    best:addScript('scripts/mp/damage.lua')
                    best:sendEvent('MpKillNow', {})
                else
                    print('[mp-debug] nobody to kill nearby')
                end
            end
        end,
    },
}
