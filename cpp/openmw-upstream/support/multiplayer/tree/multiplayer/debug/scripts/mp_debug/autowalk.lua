-- Test helper (player script): walks the character through forward/strafe/back/turn/jump so another
-- instance can check how well the avatar follows.
local self = require('openmw.self')
local I = require('openmw.interfaces')
local types = require('openmw.types')
local camera = require('openmw.camera')
local t = 0
local uiOpen = false
local armed = false
local nextAttack = 45
local kinds = { 'chop', 'slash', 'thrust' }
local n = 0
local plan = { -- seconds, forward, side, run, yawRate, jump
    { 4, 1, 0, true, 0, false }, { 3, 0, 1, true, 0, false }, { 3, -1, 0, false, 0, false },
    { 3, 1, 0, true, 1.2, false }, { 2, 1, 0, true, 0, true }, { 3, 1, -1, true, -0.8, false },
    { 2, 0, 0, false, 0, false },
}
local total = 0
for _, s in ipairs(plan) do total = total + s[1] end
return {
    engineHandlers = {
        onUpdate = function(dt)
            t = t + dt
            if not armed and t > 6 then -- wield the weapon give.lua added
                local sword = types.Actor.inventory(self):find('daedric longsword')
                if sword then
                    armed = true
                    types.Actor.setEquipment(self, { [types.Actor.EQUIPMENT_SLOT.CarriedRight] = sword })
                    types.Actor.setStance(self, types.Actor.STANCE.Weapon)
                end
            end
            if t > nextAttack then
                nextAttack = t + 3
                n = n + 1
                self:sendEvent('MpDebugAttack', { kind = kinds[n % 3 + 1] })
            end
            if t < 40 then return end
            camera.setPitch(0.7 * math.sin(t * 0.8)) -- look up and down
            if t > 70 and t < 80 and not uiOpen then
                uiOpen = true
                I.UI.setMode('Interface', { windows = {} }) -- like opening the inventory: pauses the game
            elseif t >= 80 and uiOpen then
                uiOpen = false
                I.UI.setMode()
            end -- wait for the connection
            I.Controls.overrideMovementControls(true)
            local x = (t - 40) % total
            for _, s in ipairs(plan) do
                if x < s[1] then
                    self.controls.movement, self.controls.sideMovement = s[2], s[3]
                    self.controls.run, self.controls.yawChange, self.controls.jump = s[4], s[5] * dt, s[6]
                    return
                end
                x = x - s[1]
            end
        end,
    },
}
