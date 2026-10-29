-- DEBUG ONLY: gives the player a melee weapon, a bow and arrows (once per session, only what is missing).
local world = require('openmw.world')
local types = require('openmw.types')

local ITEMS = { { 'daedric longsword', 1 }, { 'daedric long bow', 1 }, { 'daedric arrow', 200 } }
local timer = 0
local done = false

return { engineHandlers = { onUpdate = function(dt)
    local p = world.players[1]
    if done or not p then return end
    timer = timer + dt
    if timer < 1 then return end
    done = true
    local inv = types.Actor.inventory(p)
    for _, item in ipairs(ITEMS) do
        if inv:countOf(item[1]) == 0 then
            world.createObject(item[1], item[2]):moveInto(inv)
            print('[mp-debug] gave ' .. item[2] .. ' x ' .. item[1])
        end
    end
end } }
