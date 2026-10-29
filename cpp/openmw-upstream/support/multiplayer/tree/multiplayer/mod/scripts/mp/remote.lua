-- Local script for stand-in actors: remote-player avatars and proxies of group members' NPCs.
-- Disables AI and steers the actor toward the last known remote position so it plays real
-- walk/run animations; the global script teleports it if it gets too far off.
-- Proxies (initData.proxy) additionally mirror health from the owner's world and report
-- damage done to them locally so the owner's real actor can take it.
local self = require('openmw.self')
local core = require('openmw.core')
local types = require('openmw.types')
local nearby = require('openmw.nearby')
local I = require('openmw.interfaces')
local anim = require('openmw.animation')

local Actor = types.Actor

-- A player who floats in place (levitating, flying) is shown by giving the avatar the effect of a levitation potion, so
-- it hangs there instead of falling and being put back. Whichever of these potions the loaded content has.
local LEVITATE_POTIONS = { 'p_levitation_s', 'p_levitation_q', 'p_levitation_e', 'p_levitation_c', 'p_levitation_b' }
local floatOn, floatCheck = false, 0
local landSettle = 0
local LAND_SETTLE, LAND_SETTLE_DIST, LAND_MOVING = 0.4, 70, 60 -- seconds to stand still after landing / units off that still need a step / speed (units per second) that counts as still moving

local function levitatePotion()
    for _, id in ipairs(LEVITATE_POTIONS) do
        local ok, rec = pcall(function() return types.Potion.records[id] end)
        if ok and rec then return id end
    end
    return nil
end

local function applyFloat(on)
    local id = levitatePotion()
    if not id then return end
    local spells = Actor.activeSpells(self)
    pcall(function()
        local active = spells:isSpellActive(id)
        if on and not active then
            spells:add({ id = id, effects = { 0 }, quiet = true })
        elseif not on and active then
            for _, spell in pairs(spells) do
                if spell.id == id and spell.temporary then spells:remove(spell.activeSpellId) end
            end
        end
    end)
end

local WALK_START, WALK_STOP = 18, 6 -- distance hysteresis (game units); the target is already extrapolated
local RUN_SPEED = 230 -- the player's speed (units/s) above which they are shown running
local SNAP_DIST = 350
local TURN_RATE = 14 -- rad/s
local DAMAGE_REPORT_INTERVAL = 0.2

local playAttack
local setReading
local setDown
local downed = false
local ATTACK_TIME = 1.0 -- a swing is cut off after this long, whatever the animation does
local attackLeft, attackGroup, attackHeld
local followPending -- { group, from, to, speed }: the follow-through that plays when the swing is over
local SWING_SPEED = 1.3
local speedTimer
local jumpLeft
local curPitch = 0
local attackBlend = 0
local reading = false
local dbgT, dbgPos
local isProxy, key
local target -- { pos, yaw, stance, sneak, hp }
local walking = false
local desiredEquip
local equipTimer = 0
local snapTimer = 0
local aiOff = false
local aggro = false -- proxies: once the guest hits one it fights back with its own AI
local lastHp
local pendingDamage = 0
local damageTimer = 0

local function wrap(a)
    while a > math.pi do a = a - 2 * math.pi end
    while a < -math.pi do a = a + 2 * math.pi end
    return a
end

local function equipMatches()
    local cur = Actor.getEquipment(self)
    for slot, id in pairs(desiredEquip) do
        local o = cur[slot]
        if not o or o.recordId ~= id then return false end
    end
    return true
end

local function healthStat() return Actor.stats.dynamic.health(self) end
local function maxHealth(h) return math.max(1, h.base + h.modifier) end

-- A proxy that was hit fights back in this world, so the guest takes damage from it too.
local function startAggro()
    if aggro then return end
    aggro = true
    self:enableAI(true)
    pcall(function() I.AI.startPackage({ type = 'Combat', target = nearby.players[1] }) end)
end

-- Avatars of other players are stand-ins: they can't be hurt, and hitting one is not a crime.
local function keepAvatarUnhurt()
    local h = healthStat()
    local max = maxHealth(h)
    if h.current < max - 0.01 then
        h.current = max
        core.sendGlobalEvent('MpAvatarHit', {})
    end
end

local deathReported = false

local function trackHealth(dt)
    local h = healthStat()
    if Actor.isDead(self) and not deathReported then
        deathReported = true
        core.sendGlobalEvent('MpProxyDied', { key = key }) -- dead for everyone in the group
    end
    if lastHp == nil then lastHp = h.current end
    if h.current < lastHp - 0.001 then
        pendingDamage = pendingDamage + (lastHp - h.current) / maxHealth(h)
        if not Actor.isDead(self) then startAggro() end
    end
    lastHp = h.current
    damageTimer = damageTimer - dt
    if pendingDamage > 0 and damageTimer <= 0 then
        damageTimer = DAMAGE_REPORT_INTERVAL
        core.sendGlobalEvent('MpProxyDamage', { key = key, frac = pendingDamage })
        pendingDamage = 0
    end
    -- the owner's world is authoritative for health: mirror it downward only
    if target and target.hp and not Actor.isDead(self) then
        local want = target.hp * maxHealth(h)
        if want < h.current - 0.5 then
            h.current = want
            lastHp = want
        end
    end
end

local PITCH_RATE = 3 -- rad/s

-- A player with a menu open (inventory, Esc menu...) has their game paused: show them reading a book.
local READ_GROUP = 'idle2'
local bookModel
local function findBook()
    if bookModel == nil then
        bookModel = false
        pcall(function()
            for _, rec in ipairs(types.Book.records) do
                if rec.model and rec.model ~= '' and not rec.isScroll then bookModel = rec.model break end
            end
        end)
    end
    return bookModel or nil
end

-- A dead player's avatar plays the death animation and stays down until they are back on their feet.
setDown = function(on)
    if on == downed then return end
    downed = on
    if on then
        pcall(function()
            if attackGroup then anim.cancel(self, attackGroup) attackGroup = nil end
            setReading(false)
            anim.playBlended(self, 'death1', { priority = anim.PRIORITY.Scripted, loops = 0, autoDisable = false })
        end)
    else
        pcall(anim.cancel, self, 'death1')
    end
end

setReading = function(on)
    if on == reading then return end
    reading = on
    print('[mp-anim] reading ' .. tostring(on) .. ' book ' .. tostring(findBook()) .. ' group ' .. tostring(anim.hasGroup(self, READ_GROUP)))
    if on then
        local model = findBook()
        if model then pcall(anim.addVfx, self, model, { boneName = 'Bip01 L Hand', vfxId = 'mp_book', loop = true,
            useAmbientLight = false }) end
        pcall(function()
            if anim.hasGroup(self, READ_GROUP) then
                anim.playBlended(self, READ_GROUP, { loops = 100000, priority = anim.PRIORITY.Scripted,
                    blendMask = anim.BLEND_MASK.UpperBody, forceLoop = true })
            end
        end)
    else
        pcall(anim.removeVfx, self, 'mp_book')
        pcall(anim.cancel, self, READ_GROUP)
    end
end

local Weapon = types.Weapon
local ONE_HAND = { [Weapon.TYPE.ShortBladeOneHand] = true, [Weapon.TYPE.LongBladeOneHand] = true,
    [Weapon.TYPE.BluntOneHand] = true, [Weapon.TYPE.AxeOneHand] = true }
local TWO_HAND = { [Weapon.TYPE.LongBladeTwoHand] = true, [Weapon.TYPE.BluntTwoClose] = true,
    [Weapon.TYPE.AxeTwoHand] = true }
local TWO_WIDE = { [Weapon.TYPE.BluntTwoWide] = true, [Weapon.TYPE.SpearTwoWide] = true }

-- animation group and keys for the weapon in hand
local function attackAnimation(kind)
    if kind == 'cast' then return 'spellcast', 'self start', 'self stop' end
    local w = Actor.getEquipment(self)[Actor.EQUIPMENT_SLOT.CarriedRight]
    local ok, rec = pcall(function() return w and Weapon.record(w) end)
    local t = ok and rec and rec.type
    if t == Weapon.TYPE.MarksmanBow then return 'bowandarrow', 'shoot start', 'shoot follow stop' end
    if t == Weapon.TYPE.MarksmanCrossbow then return 'crossbow', 'shoot start', 'shoot follow stop' end
    if t == Weapon.TYPE.MarksmanThrown then return 'throwweapon', 'shoot start', 'shoot follow stop' end
    local group = 'handtohand'
    if ONE_HAND[t] then group = 'weapononehand' elseif TWO_HAND[t] then group = 'weapontwohand'
    elseif TWO_WIDE[t] then group = 'weapontwowide' end
    return group, kind .. ' start', kind .. ' small follow stop'
end

local function hasKey(group, key)
    for _, name in ipairs({ group .. ': ' .. key, key }) do
        local ok, t = pcall(anim.getTextKeyTime, self, name)
        if ok and type(t) == 'number' and t >= 0 then return true end
    end
    return false
end

-- first key that exists in this group (an unknown stop key makes the animation run to its very end,
-- which looks like several punches in a row)
local function firstKey(group, keys)
    for _, k in ipairs(keys) do if hasKey(group, k) then return k end end
    return nil
end

local function keyTime(group, k)
    for _, name in ipairs({ group .. ': ' .. k, k }) do
        local okT, t = pcall(anim.getTextKeyTime, self, name)
        if okT and type(t) == 'number' and t >= 0 then return t end
    end
end

local RANGED = { bowandarrow = true, crossbow = true, throwweapon = true }

-- Plays [from, to] of a group on the upper body. hold = keep the last frame until replaced (a charged attack).
local function playSegment(group, from, to, speed, hold)
    local ok = pcall(function()
        if not anim.hasGroup(self, group) then error('no group') end
        if attackGroup then pcall(anim.cancel, self, attackGroup) end
        -- Blended on the upper body only, so walking, strafing and jumping carry on underneath. (A queued animation
        -- takes over the whole actor and left it stuck.)
        anim.playBlended(self, group, {
            startKey = from, stopKey = to, priority = anim.PRIORITY.Weapon, blendMask = anim.BLEND_MASK.UpperBody,
            autoDisable = not hold, speed = speed,
        })
    end)
    if not ok then print('[mp-anim] could not play ' .. tostring(group) .. ' ' .. tostring(from)) return false end
    local t0, t1 = keyTime(group, from), keyTime(group, to)
    local length = (t0 and t1 and t1 > t0) and (t1 - t0) / speed or 0.6
    attackGroup = group
    attackLeft = hold and 5 or math.min(2.5, length + 0.15) -- a held wind-up gives up after 5 s
    attackHeld = hold
    print(string.format('[mp-anim] %s %s -> %s (%.2fs)%s', group, from, to, length, hold and ' held' or ''))
    return true
end

-- A player pressed attack (wind-up, held until they release) or released it (the swing, harder when charged longer).
-- Spells have no charge: they are cast on press.
playAttack = function(d)
    if Actor.isDead(self) then return end
    followPending = nil
    local kind = d.kind
    local group, from = attackAnimation(kind)
    if group == 'spellcast' then
        if d.phase == 'press' then playSegment(group, 'self start', 'self stop', 1, false) end
        return
    end
    if RANGED[group] then
        if d.phase == 'press' then
            playSegment(group, 'shoot start', firstKey(group, { 'shoot max attack', 'shoot min attack' }) or 'shoot start', 1, true)
        else
            local rel = firstKey(group, { 'shoot release' }) or 'shoot start'
            playSegment(group, rel, firstKey(group, { 'shoot follow stop' }) or 'shoot follow stop', 1.2, false)
        end
        return
    end
    if d.phase == 'press' then
        -- wind up: raise the weapon and hold it there
        local top = firstKey(group, { kind .. ' max attack', kind .. ' min attack' })
        if top then playSegment(group, kind .. ' start', top, 1, true) end
    else
        local str = d.str or 0
        local size = str < 0.33 and 'small' or str < 0.66 and 'medium' or 'large'
        -- The swing runs from the charged pose to the point of impact. The follow-through that comes after it is played
        -- separately and only for this strength: the animation holds all three (large, medium, small) one after the
        -- other, so playing on from the hit to a later stop key would swing again and again.
        local from = firstKey(group, { kind .. ' max attack', kind .. ' min attack', kind .. ' start' }) or (kind .. ' start')
        local hit = firstKey(group, { kind .. ' min hit', kind .. ' hit' }) or (kind .. ' hit')
        local fStart = firstKey(group, { kind .. ' ' .. size .. ' follow start' })
        local fStop = firstKey(group, { kind .. ' ' .. size .. ' follow stop' })
        local speed = 1.0 + 0.5 * str -- a hard swing is a bit faster and carries further
        if playSegment(group, from, hit, speed, false) then
            followPending = fStart and fStop and { group = group, from = fStart, to = fStop, speed = speed } or nil
        end
    end
end

return {
    engineHandlers = {
        onInit = function(d)
            if d then isProxy, key = d.proxy, d.key end
        end,
        onActive = function() if not aggro then self:enableAI(false); aiOff = true end end,
        onUpdate = function(dt)
            if floatOn then
                floatCheck = floatCheck - dt
                if floatCheck <= 0 then
                    floatCheck = 2 -- (the potion wears off after a while: put it back)
                    applyFloat(true)
                end
            end
            if not aiOff and not aggro then self:enableAI(false); aiOff = true end
            local c = self.controls
            if attackGroup then
                attackLeft = attackLeft - dt
                local playing = anim.isPlaying(self, attackGroup)
                if attackLeft <= 0 or (not playing and not attackHeld) then
                    if playing then pcall(anim.cancel, self, attackGroup) end
                    attackGroup, attackLeft, attackHeld = nil, nil, nil
                    local f = followPending
                    followPending = nil
                    if f then playSegment(f.group, f.from, f.to, f.speed, false) end
                end
            end

            if isProxy then trackHealth(dt) else keepAvatarUnhurt() end

            if desiredEquip then
                equipTimer = equipTimer - dt
                if equipTimer <= 0 then
                    equipTimer = 1
                    if equipMatches() then desiredEquip = nil else Actor.setEquipment(self, desiredEquip) end
                end
            end

            if not target or aggro or Actor.isDead(self) then return end
            setDown(target.dead and true or false)
            if downed then return end -- lying on the ground: nothing else to show
            setReading(target.reading and true or false)

            -- look where the player looks (the engine turns head and spine with the actor's pitch)
            local wantPitch = math.max(-1.2, math.min(1.2, target.pitch or 0))
            local step = math.max(-PITCH_RATE * dt, math.min(PITCH_RATE * dt, wantPitch - curPitch))
            curPitch = curPitch + step
            -- while attacking the whole upper body follows the aim, not just the head
            attackBlend = attackBlend + math.max(-6 * dt, math.min(6 * dt, (attackGroup and 1 or 0) - attackBlend))
            local look = -curPitch -- the engine's positive is "down", the camera's is the other way round
            pcall(anim.setHeadPitch, self, look * (1 - 0.6 * attackBlend), math.max(-0.9, math.min(0.9, look * 0.8 * attackBlend)))
            snapTimer = snapTimer - dt

            local delta = target.pos - self.position
            local dist = math.sqrt(delta.x * delta.x + delta.y * delta.y)
            if dist > SNAP_DIST and snapTimer <= 0 then
                snapTimer = 1
                core.sendGlobalEvent('MpSnap', { actor = self.object })
            end

            -- just after a jump, let the landing finish: stepping off to catch up cuts it short and looks like a second,
            -- smaller landing. (Only when it is close and the player has stopped; running on from a jump has to keep up.)
            if not Actor.isOnGround(self) then landSettle = LAND_SETTLE else landSettle = math.max(0, landSettle - dt) end
            if walking then
                if dist < WALK_STOP then walking = false end
            elseif dist > WALK_START and (landSettle <= 0 or dist > LAND_SETTLE_DIST or (target.speed or 0) > LAND_MOVING) then
                walking = true
            end

            -- always face where the player faces; travel relative to that (so strafing and backing up look right)
            local yaw = self.rotation:getYaw()
            local err = wrap(target.yaw - yaw)
            local maxTurn = TURN_RATE * dt
            c.yawChange = math.max(-maxTurn, math.min(maxTurn, err))
            if walking and dist > 0 then
                local dx, dy = delta.x / dist, delta.y / dist
                local sy, cy = math.sin(yaw), math.cos(yaw)
                c.movement = dx * sy + dy * cy -- forward component
                c.sideMovement = dx * cy - dy * sy -- right component
            else
                c.movement, c.sideMovement = 0, 0
            end
            -- run when the player runs, or when we are far behind and need to catch up
            c.run = walking and ((target.speed or 0) > RUN_SPEED or dist > 120)
            c.sneak = target.sneak or false
            if jumpLeft then
                jumpLeft = jumpLeft - dt
                if jumpLeft <= 0 then jumpLeft = nil end
            end
            c.jump = jumpLeft ~= nil and Actor.isOnGround(self)
            -- make the avatar as fast as the player it mirrors, so it does not fall behind (or overshoot)
            speedTimer = (speedTimer or 0) + dt
            if speedTimer >= 0.4 and target.rs and target.rs > 0 then
                speedTimer = 0
                local mine = Actor.getRunSpeed(self)
                local ratio = mine > 0 and target.rs / mine or 1
                if math.abs(ratio - 1) > 0.02 then
                    local attr = Actor.stats.attributes.speed(self)
                    local total = attr.base + attr.modifier
                    attr.modifier = math.max(-attr.base + 1, math.min(1000, attr.modifier + (ratio - 1) * total * 0.9))
                end
            end
            dbgT = (dbgT or 0) + dt
            if dbgT >= 0.5 and not isProxy then
                local moved = (self.position - (dbgPos or self.position)):length() / dbgT
                print(string.format('[mp-move] dist %.0f targetSpeed %.0f actualSpeed %.0f fwd %.2f side %.2f run %s walking %s',
                    dist, target.speed or 0, moved, c.movement, c.sideMovement, tostring(c.run), tostring(walking)))
                dbgT, dbgPos = 0, self.position
            end
        end,
    },
    eventHandlers = {
        MpTarget = function(d)
            target = { pos = d.pos, yaw = d.yaw, sneak = d.sneak, hp = d.hp, speed = d.speed, rs = d.rs, pitch = d.pitch, reading = d.reading, dead = d.dead }
            if d.stance and Actor.getStance(self) ~= d.stance then Actor.setStance(self, d.stance) end
        end,
        -- Another player attacked or cast: show the animation only. (The avatar has no AI and never deals damage.)
        MpAttack = function(d) playAttack(d) end,
        MpJump = function() jumpLeft = 0.15 end,
        MpFloat = function(d)
            floatOn = d.on and true or false
            floatCheck = 0
            applyFloat(floatOn)
        end,
        -- The player cast a spell: its cast effect on their hands, or its effect on themselves
        MpCastVfx = function(d)
            local function static(id)
                if not id or id == '' then return nil end
                local ok, rec = pcall(function() return types.Static.records[id] end)
                return ok and rec and rec.model or nil
            end
            local cast, hit = static(d.cast), static(d.hit)
            if cast then
                pcall(anim.addVfx, self, cast, { boneName = 'Bip01 L Hand', vfxId = 'mp_cast', loop = false,
                    useAmbientLight = false })
            end
            if hit then pcall(anim.addVfx, self, hit, { vfxId = 'mp_selfhit', loop = false, useAmbientLight = false }) end
        end,
        MpEquip = function(d)
            desiredEquip = d.slots
            equipTimer = 0
        end,
        MpKill = function()
            deathReported = true -- the owner already knows
            local h = healthStat()
            lastHp = 0
            h.current = 0
        end,
    },
}
