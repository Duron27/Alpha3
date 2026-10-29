-- Test helper (player script): opens a menu for a while, like opening the inventory (pauses the game).
local I = require('openmw.interfaces')
local t, opened = 0, false
return {
    engineHandlers = {
        onFrame = function()
            t = t + require('openmw.core').getRealFrameDuration()
            if t > 40 and t < 70 and not opened then
                opened = true
                I.UI.setMode('Interface', { windows = {} })
            elseif t >= 70 and opened then
                opened = false
                I.UI.setMode()
                t = 1000
            end
        end,
    },
}
