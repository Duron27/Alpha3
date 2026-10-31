-- Test helper (player script, the joining instance): walks off, turns round to face where it came from (where the host
-- stands), readies a fireball, casts it, puts a shield on, swings a sword, strafes and jumps, over and over, so the
-- host can check that every bit of it shows on the avatar.
local self = require('openmw.self')
local core = require('openmw.core')
local I = require('openmw.interfaces')
local types = require('openmw.types')
local Actor = types.Actor

local t = 0
local step = 0
local function log(s) print('[mp-act] ' .. string.format('%.1f', t) .. ' ' .. s) end
local plan = {
    { 12, 'walk', function() end },
    { 15, 'turn', function() end },
    { 17.5, 'ready fireball', function()
        pcall(function() Actor.spells(self):add('fireball') end)
        Actor.setSelectedSpell(self, 'fireball')
        Actor.setStance(self, Actor.STANCE.Spell)
    end },
    { 21, 'cast fireball', function() end },
    { 25, 'shield on', function()
        pcall(function() Actor.spells(self):add('shield') end)
        Actor.setSelectedSpell(self, 'shield')
    end },
    { 26, 'cast shield', function() end },
    { 30, 'sword out', function()
        local sword = Actor.inventory(self):find('daedric longsword')
        if sword then Actor.setEquipment(self, { [Actor.EQUIPMENT_SLOT.CarriedRight] = sword }) end
        Actor.setStance(self, Actor.STANCE.Weapon)
    end },
    { 32, 'swing', function() end },
    { 35, 'strafe and jump', function() end },
    { 41, 'stop', function() end },
}
local using = 0
local function press(seconds) using = seconds end
-- no idle camera orbiting the player in tests (screenshots should show what the player faces)
pcall(function() require('openmw.types').Player.setControlSwitch(require('openmw.self'), require('openmw.types').Player.CONTROL_SWITCH.VanityMode, false) end)
return {
    engineHandlers = {
        onUpdate = function(dt)
            if dt <= 0 then return end
            t = t + dt
            local cycle = t % 45
            local s = plan[step + 1]
            if s and cycle >= s[1] then
                step = step + 1
                log(s[2])
                s[3]()
                if s[2] == 'cast fireball' or s[2] == 'cast shield' then press(0.2) end
                if s[2] == 'swing' then press(0.6) end
            end
            if cycle < 1 and step >= #plan then step = 0 end
            I.Controls.overrideMovementControls(true)
            I.Controls.overrideCombatControls(true)
            local c = self.controls
            c.movement, c.sideMovement, c.yawChange, c.jump, c.run = 0, 0, 0, false, false
            if cycle >= 10.5 and cycle < 12 then c.movement, c.run = 1, false end
            if cycle >= 12 and cycle < 15 then c.yawChange = math.pi / 3 * dt end
            if cycle >= 35 and cycle < 41 then
                c.sideMovement, c.run = (cycle % 2 < 1) and 1 or -1, true
                c.jump = cycle % 3 < 0.1
            end
            if using > 0 then using = using - dt; c.use = 1 else c.use = 0 end
        end,
    },
}
