-- Test helper (player script): turns this game into a multiplayer session as the Multiplayer screen would (the UI and
-- what the others see of us run only then). Use with autohost or autojoin.
local storage = require('openmw.storage')
storage.playerSection('MpConnect'):set('Active', true)
-- no idle camera orbiting the player in tests (screenshots should show what the player faces)
pcall(function() require('openmw.types').Player.setControlSwitch(require('openmw.self'), require('openmw.types').Player.CONTROL_SWITCH.VanityMode, false) end)
return {}
