-- Test helper: sets this instance to 'host' mode with the test bot without touching the menu.
local core = require('openmw.core')
local done = false
return {
    engineHandlers = {
        onUpdate = function()
            if done then return end
            done = true
            core.sendGlobalEvent('MpCmd', { cmd = 'configure', mode = 'host', bot = true, host = '127.0.0.1', port = 25565 })
        end,
    },
}
