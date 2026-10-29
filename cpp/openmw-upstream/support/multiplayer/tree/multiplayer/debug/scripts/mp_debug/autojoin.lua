-- Test helper: sets this instance to 'join' mode without touching the menu, and fakes player input
-- (alternating attacks, jumping, sneaking) so the host can check the avatar animations.
local core = require('openmw.core')
local done, t, seq = false, 0, 0
local acceptTimer = 0
local done2 = false
local kinds = { 'chop', 'slash', 'thrust' }
return {
    engineHandlers = {
        onUpdate = function(dt)
            acceptTimer = acceptTimer + dt
            if acceptTimer > 4 and not done2 then -- accept any party invite from the host (id 1)
                acceptTimer = 0
                core.sendGlobalEvent('MpCmd', { cmd = 'accept', id = 1 })
            end
            if not done then
                done = true
                core.sendGlobalEvent('MpCmd', { cmd = 'configure', mode = 'join', host = '127.0.0.1', port = 25565, password = 'hunter2' })
            end
        end,
    },
}
