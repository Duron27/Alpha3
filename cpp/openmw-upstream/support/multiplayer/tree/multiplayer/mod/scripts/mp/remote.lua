-- Local script for stand-in actors: remote-player avatars and proxies of group members' NPCs.
-- Disables AI and steers the actor toward the last known remote position so it plays real
-- walk/run animations; the global script teleports it if it gets too far off.
-- Proxies (initData.proxy) additionally mirror health from the owner's world and report
-- damage done to them locally so the owner's real actor can take it.
local self = require('openmw.self')
local core = require('openmw.core')
local DEBUG = false -- log every mirrored animation, look change and how well the avatar keeps up
local types = require('openmw.types')
local nearby = require('openmw.nearby')
local I = require('openmw.interfaces')
local anim = require('openmw.animation')
local util = require('openmw.util')

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

local WALK_START, WALK_STOP = 14, 5 -- distance hysteresis (game units)
local START_SPEED, STOP_SPEED = 30, 12 -- and the same for how fast they move (units per second)
local CATCH_UP = 3 -- per second: how much of the gap to them is closed by walking a bit faster or slower
-- They stand still and we cannot get closer (something in the way, or the last few steps too small to take): stand
-- where we are rather than walk on the spot. A gap this small is left to the global script's nudge.
local STILL_SPEED = 25 -- their speed below which they count as standing
local SETTLE_DIST = 70 -- a gap we may stand at
local STALL_TIME = 0.3 -- seconds of not closing in before we give up walking
local CLOSING = 20 -- units per second that count as closing in
local lookTimer = 0
local RUN_SPEED = 230 -- the player's speed (units/s) above which they are shown running
local SNAP_DIST = 350
local TURN_RATE = 14 -- rad/s
-- Standing, any turn at all plays the turning animation instead of the idle (and its little moves): small differences
-- in facing are left until they grow past TURN_START, then turned away completely (to TURN_STOP).
local TURN_START, TURN_STOP = 0.12, 0.015
local turningInPlace = false
local DAMAGE_REPORT_INTERVAL = 0.2

local playMirrored
local castHands
local setReading
local setDown
local downed = false
local speedTimer
local jumpLeft
local curPitch = 0
local attackBlend = 0
local reading = false
local dbgT, dbgPos
local isProxy, key
local target -- { pos, yaw, stance, sneak, hp }
local walking = false
-- An engine with animation.setMovementOverride moves the avatar at exactly the velocity we ask for (its legs still
-- follow the controls); without it the walk animation moves it, at about the speed the controls ask for.
local exactMove = anim.setMovementOverride ~= nil
local overriding = false
local function setMove(v)
    if not exactMove then return end
    if v == nil and not overriding then return end
    overriding = v ~= nil
    pcall(anim.setMovementOverride, self, v)
end
local settled = false -- standing a little off their spot on purpose (see SETTLE_DIST)
local stallTime, lastDist = 0, nil
local desiredEquip
local wornEquip -- what they wear, by slot (desiredEquip is that, minus the weapon while their hands hold a spell)
local equipTimer = 0
local drawWaitSince -- when we started waiting for their weapon to be in hand before drawing it
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

local RIGHT = Actor.EQUIPMENT_SLOT.CarriedRight

local function equipMatches()
    local cur = Actor.getEquipment(self)
    for slot, id in pairs(desiredEquip) do
        local o = cur[slot]
        if not o or o.recordId ~= id then return false end
    end
    return desiredEquip[RIGHT] ~= nil or cur[RIGHT] == nil
end

-- Their weapon is put away while they hold a spell; the engine hides it only if its own draw/sheathe ran through
-- uninterrupted, which their mirrored animations can cut short. Taking it out of the hand shows the same thing for sure.
local function equipForStance(stance)
    if not wornEquip then return true end
    local want = {}
    for slot, id in pairs(wornEquip) do want[slot] = id end
    if stance == Actor.STANCE.Spell then want[RIGHT] = nil end
    desiredEquip = want
    if equipMatches() then return true end
    Actor.setEquipment(self, want)
    equipTimer = 1
    return false
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
    if DEBUG then print('[mp-anim] reading ' .. tostring(on) .. ' book ' .. tostring(findBook()) .. ' group ' .. tostring(anim.hasGroup(self, READ_GROUP))) end
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

------------------------------------------------------------------ the player's own animations (observe.lua)

-- Played as their engine played it: same keys, speed, start point, priorities and bone groups. An attack's wind-up,
-- its strength, a cast's kind (self, touch, target), a bow's draw, a hit they took: all exactly theirs.
-- Mirrored groups playing their last part (to a '... stop' key): once it ends we let go of it. The engine holds an
-- animation on its last frame, above the idle, so without this the avatar would stand frozen in the end of its swing or
-- cast instead of breathing and shifting its grip like they do. (A pause mid-action, a drawn bow or a raised sword
-- waiting for the swing, ends at another key and is held as it should be.)
local finishing = {}
local mirrored -- { group, left }: what we play now (for the aim and to stop a held pose that never got its follow-up)
local WEAPON_GROUPS = { handtohand = true, weapononehand = true, weapontwohand = true, weapontwowide = true,
    bowandarrow = true, crossbow = true, throwweapon = true, spellcast = true }

playMirrored = function(d)
    if Actor.isDead(self) or downed or type(d.g) ~= 'string' or type(d.o) ~= 'table' then return end
    local o = d.o
    local ok, err = pcall(function()
        if not anim.hasGroup(self, d.g) then error('no group ' .. d.g) end
        local priority = o.pr
        if type(priority) == 'table' then
            priority = { [0] = priority[1], [1] = priority[2], [2] = priority[3], [3] = priority[4] }
        end
        local options = {
            startKey = o.s, stopKey = o.e, speed = tonumber(o.sp) or 1, startPoint = math.max(0, math.min(1, tonumber(o.pt) or 0)),
            loops = math.max(0, math.min(1000, tonumber(o.l) or 0)), autoDisable = o.ad and true or false,
            forceLoop = o.fl and true or false, priority = priority or anim.PRIORITY.Weapon,
        }
        if o.bm then options.blendMask = o.bm end
        -- a group that already ran to its stop key stays held there and would not start again: the engine clears it
        -- before each new part of an attack, and so do we
        anim.cancel(self, d.g)
        anim.playBlended(self, d.g, options)
    end)
    if not ok then print('[mp-anim] could not play ' .. tostring(d.g) .. ': ' .. tostring(err)) return end
    if DEBUG then print(string.format('[mp-anim] %s %s -> %s x%.2f from %.2f', d.g, tostring(o.s), tostring(o.e), tonumber(o.sp) or 1, tonumber(o.pt) or 0)) end
    finishing[d.g] = type(o.e) == 'string' and o.e:sub(-4) == 'stop' or nil
    if WEAPON_GROUPS[d.g] then
        mirrored = { group = d.g, left = 4 }
        -- the hands light up as the cast starts, in the colour of the spell (as the engine does for a caster)
        if d.g == 'spellcast' and type(o.s) == 'string' and o.s:sub(-5) == 'start' then castHands() end
    end
end

------------------------------------------------------------------ magic that shows on them

local function staticModel(id)
    if not id or id == '' then return nil end
    local ok, rec = pcall(function() return types.Static.records[id] end)
    return ok and rec and rec.model or nil
end
local function effectRecord(id)
    local ok, rec = pcall(function() return core.magic.effects.records[id] end)
    return ok and rec or nil
end

local look = {} -- the last MpLook: { fx = { { e, m } }, spell = { first, last, range, list } }
local handsOn -- particle texture of the glowing hands while a spell is ready ('' for the default), nil when not shown
local shownFx = {} -- effect id -> 'vfx' | 'spell' (what we put on the avatar for it)

local function handsModel() return staticModel('VFX_Hands') end

local function setReadyHands(particle)
    if particle == handsOn then return end
    pcall(anim.removeVfx, self, 'mp_ready_l')
    pcall(anim.removeVfx, self, 'mp_ready_r')
    handsOn = particle
    local model = particle and handsModel()
    if not model then handsOn = nil return end
    for _, side in ipairs({ { 'L', 'mp_ready_l' }, { 'R', 'mp_ready_r' } }) do
        pcall(anim.addVfx, self, model, { boneName = 'Bip01 ' .. side[1] .. ' Hand', vfxId = side[2], loop = true,
            particleTextureOverride = particle, useAmbientLight = false })
    end
end

castHands = function()
    local spell = look.spell
    local last = spell and effectRecord(spell.last)
    local model = handsModel()
    if not model or not last then return end
    for _, bone in ipairs({ 'Bip01 L Hand', 'Bip01 R Hand' }) do
        pcall(anim.addVfx, self, model, { boneName = bone, loop = false, particleTextureOverride = last.particle,
            useAmbientLight = false })
    end
end

-- A spell record that has this effect, to put the effect on the avatar for real (light, chameleon, invisibility: the
-- engine then draws them the way it draws anyone under that spell)
local spellWith = {}
local function spellWithEffect(effectId)
    if spellWith[effectId] == nil then
        spellWith[effectId] = false
        pcall(function()
            for _, rec in ipairs(core.magic.spells.records) do
                for i, fx in ipairs(rec.effects) do
                    if fx.id == effectId then spellWith[effectId] = { id = rec.id, index = fx.index or (i - 1) } return end
                end
            end
        end)
    end
    return spellWith[effectId] or nil
end
local STATE_EFFECTS = { light = true, chameleon = true, invisibility = true }

-- The engine lets an actor ready magic only with a spell selected: our copy of them gets one with the right look (their
-- readied spell's first effect), or any at all.
local function selectSomeSpell()
    if Actor.getSelectedSpell(self) then return end
    local sp = (look.spell and spellWithEffect(look.spell.first)) or spellWithEffect('firedamage')
    if not sp then return end
    pcall(function()
        Actor.spells(self):add(sp.id)
        Actor.setSelectedSpell(self, sp.id)
    end)
end

local function removeSpellEffect(effectId)
    local sp = spellWithEffect(effectId)
    if not sp then return end
    pcall(function()
        for _, active in pairs(Actor.activeSpells(self)) do
            if active.id == sp.id and active.temporary then Actor.activeSpells(self):remove(active.activeSpellId) end
        end
    end)
end

local function applyLook()
    local want = {}
    for _, f in ipairs(look.fx or {}) do if type(f) == 'table' and type(f.e) == 'string' then want[f.e] = tonumber(f.m) or 0 end end
    -- gone
    for id, how in pairs(shownFx) do
        if want[id] == nil then
            if how == 'spell' then removeSpellEffect(id) else pcall(anim.removeVfx, self, 'mp_fx_' .. id) end
            shownFx[id] = nil
        end
    end
    -- new
    for id, magnitude in pairs(want) do
        if not shownFx[id] then
            local mgef = effectRecord(id)
            local sp = STATE_EFFECTS[id] and spellWithEffect(id)
            if sp then
                pcall(function()
                    Actor.activeSpells(self):add({ id = sp.id, effects = { { index = sp.index, magnitudeMin = magnitude,
                        magnitudeMax = magnitude, duration = 3600 } }, ignoreResistances = true, ignoreReflect = true,
                        ignoreSpellAbsorption = true })
                end)
                shownFx[id] = 'spell'
            elseif mgef then
                local model = staticModel(mgef.hitStatic)
                if model then
                    pcall(anim.addVfx, self, model, { vfxId = 'mp_fx_' .. id, loop = mgef.continuousVfx and true or false,
                        particleTextureOverride = mgef.particle, useAmbientLight = false })
                end
                shownFx[id] = 'vfx'
            end
        end
    end
    local stanceSpell = Actor.getStance(self) == Actor.STANCE.Spell
    local last = look.spell and effectRecord(look.spell.last)
    setReadyHands(stanceSpell and last and (last.particle or '') or nil)
end

-- They cast a spell (the release of their cast): its cast effects on them, its sound, and if it was on themselves its
-- hit effects too.
local function castVfx(d)
    local played = {}
    for i, f in ipairs(type(d.fx) == 'table' and d.fx or {}) do
        local mgef = type(f) == 'table' and effectRecord(f.e)
        if mgef then
            local cast = staticModel(mgef.castStatic) or staticModel('VFX_DefaultCast')
            if cast and not played[cast] then
                played[cast] = true
                pcall(anim.addVfx, self, cast, { loop = false, particleTextureOverride = mgef.particle, useAmbientLight = false })
            end
            if i == 1 and mgef.castSound and mgef.castSound ~= '' then pcall(core.sound.playSound3d, mgef.castSound, self) end
            if f.r == core.magic.RANGE.Self then
                local hit = staticModel(mgef.hitStatic)
                if hit and not played[hit] then
                    played[hit] = true
                    pcall(anim.addVfx, self, hit, { loop = false, particleTextureOverride = mgef.particle, useAmbientLight = false })
                end
                if i == 1 and mgef.hitSound and mgef.hitSound ~= '' then pcall(core.sound.playSound3d, mgef.hitSound, self) end
            end
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
            for g in pairs(finishing) do
                if not anim.isPlaying(self, g) then
                    finishing[g] = nil
                    pcall(anim.cancel, self, g)
                end
            end
            if mirrored then
                mirrored.left = mirrored.left - dt
                if mirrored.left <= 0 or not anim.isPlaying(self, mirrored.group) then mirrored = nil end
            end
            lookTimer = lookTimer - dt
            if lookTimer <= 0 then
                lookTimer = 1 -- (a stance change shows or hides the ready hands; an effect may have worn off)
                applyLook()
            end

            if isProxy then trackHealth(dt) else keepAvatarUnhurt() end

            if desiredEquip then
                equipTimer = equipTimer - dt
                if equipTimer <= 0 then
                    equipTimer = 1
                    if equipMatches() then desiredEquip = nil else Actor.setEquipment(self, desiredEquip) end
                end
            end

            if not target or aggro or Actor.isDead(self) then setMove(nil) return end
            setDown(target.dead and true or false)
            if downed then return end -- lying on the ground: nothing else to show
            setReading(target.reading and true or false)

            -- look where the player looks (the engine turns head and spine with the actor's pitch)
            local wantPitch = math.max(-1.2, math.min(1.2, target.pitch or 0))
            local step = math.max(-PITCH_RATE * dt, math.min(PITCH_RATE * dt, wantPitch - curPitch))
            curPitch = curPitch + step
            -- while attacking the whole upper body follows the aim, not just the head
            attackBlend = attackBlend + math.max(-6 * dt, math.min(6 * dt, (mirrored and 1 or 0) - attackBlend))
            local look = -curPitch -- the engine's positive is "down", the camera's is the other way round
            pcall(anim.setHeadPitch, self, look * (1 - 0.6 * attackBlend), math.max(-0.9, math.min(0.9, look * 0.8 * attackBlend)))
            snapTimer = snapTimer - dt

            -- Where they are now: the target as sent, moved on by their velocity for the moment since (a frame or two)
            local now = core.getRealTime()
            local vel = target.vel
            local want = target.pos
            if vel and target.at then want = want + vel * math.min(0.1, math.max(0, now - target.at)) end
            local delta = want - self.position
            local dist = math.sqrt(delta.x * delta.x + delta.y * delta.y)
            if dist > SNAP_DIST and snapTimer <= 0 then
                snapTimer = 1
                core.sendGlobalEvent('MpSnap', { actor = self.object })
            end

            -- just after a jump, let the landing finish: stepping off to catch up cuts it short and looks like a second,
            -- smaller landing. (Only when it is close and the player has stopped; running on from a jump has to keep up.)
            if not Actor.isOnGround(self) then landSettle = LAND_SETTLE else landSettle = math.max(0, landSettle - dt) end

            -- The velocity to walk with: theirs, plus a pull toward where they are that closes a gap in about a third of
            -- a second. Fed straight to the legs (speed as a fraction of walking or running), so the avatar moves as
            -- they move instead of chasing a point.
            local vx, vy = (vel and vel.x or 0) + delta.x * CATCH_UP, (vel and vel.y or 0) + delta.y * CATCH_UP
            local speed = math.sqrt(vx * vx + vy * vy)
            local still = not vel or (vel.x * vel.x + vel.y * vel.y) < STILL_SPEED * STILL_SPEED
            if walking then
                if speed < STOP_SPEED and dist < WALK_STOP then walking = false
                elseif still then
                    if lastDist and dist > lastDist - CLOSING * dt then stallTime = stallTime + dt else stallTime = 0 end
                    if stallTime > STALL_TIME and dist < SETTLE_DIST then walking, settled = false, true end
                end
            elseif settled then
                if not still or dist > SETTLE_DIST then settled = false end -- (walking again from the next frame)
            elseif (speed > START_SPEED or dist > WALK_START)
                and (landSettle <= 0 or dist > LAND_SETTLE_DIST or (target.speed or 0) > LAND_MOVING) then
                walking, stallTime = true, 0
            end
            lastDist = dist

            -- always face where the player faces; travel relative to that (so strafing and backing up look right)
            local yaw = self.rotation:getYaw()
            local err = wrap(target.yaw - yaw)
            local maxTurn = TURN_RATE * dt
            if walking then turningInPlace = false
            elseif turningInPlace then turningInPlace = math.abs(err) > TURN_STOP
            else turningInPlace = math.abs(err) > TURN_START end
            if walking or turningInPlace then c.yawChange = math.max(-maxTurn, math.min(maxTurn, err)) else c.yawChange = 0 end
            if walking and speed > 0 then
                local walkSpeed = math.max(1, Actor.getWalkSpeed(self))
                local runSpeed = math.max(walkSpeed, Actor.getRunSpeed(self))
                local run = speed > walkSpeed * 1.1 or (target.speed or 0) > RUN_SPEED
                local factor = math.max(0.25, math.min(1, speed / (run and runSpeed or walkSpeed)))
                local dx, dy = vx / speed, vy / speed
                local sy, cy = math.sin(yaw), math.cos(yaw)
                c.movement = (dx * sy + dy * cy) * factor -- forward component
                c.sideMovement = (dx * cy - dy * sy) * factor -- right component
                c.run = run
                -- (catching up on a big gap no faster than half again their running speed: bigger ones are snapped)
                local cap = math.max(runSpeed, target.speed or 0) * 1.5
                local k = speed > cap and cap / speed or 1
                setMove(util.vector3(vx * k, vy * k, 0))
            else
                c.movement, c.sideMovement, c.run = 0, 0, false
                setMove(util.vector3(0, 0, 0))
            end
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
            if DEBUG and dbgT >= 0.5 and not isProxy then
                local moved = (self.position - (dbgPos or self.position)):length() / dbgT
                print(string.format('[mp-move] dist %.0f targetSpeed %.0f actualSpeed %.0f fwd %.2f side %.2f run %s walking %s',
                    dist, target.speed or 0, moved, c.movement, c.sideMovement, tostring(c.run), tostring(walking)))
                dbgT, dbgPos = 0, self.position
            end
        end,
    },
    eventHandlers = {
        MpTarget = function(d)
            target = { pos = d.pos, vel = d.vel, at = d.at, yaw = d.yaw, sneak = d.sneak, hp = d.hp, speed = d.speed, rs = d.rs,
                pitch = d.pitch, reading = d.reading, dead = d.dead }
            selectSomeSpell() -- (ahead of time: the engine may take a moment to take it)
            if d.stance and Actor.getStance(self) ~= d.stance then
                -- the weapon back in hand before drawing it: the engine takes new equipment a frame later, and drawing an
                -- empty hand first shows fists and then never the weapon
                local now = core.getRealTime()
                drawWaitSince = drawWaitSince or now
                if equipForStance(d.stance) or d.stance == Actor.STANCE.Spell or now - drawWaitSince > 0.5 then
                    Actor.setStance(self, d.stance)
                    drawWaitSince = nil
                end
                lookTimer = 0.3 -- (the hands light up once the spell is drawn)
            end
        end,
        -- One of their animations (attack, cast, draw, hit...), exactly as their engine played it
        MpAnim = function(d) playMirrored(d) end,
        -- The spell they have ready and the magic on them that shows
        MpLook = function(d)
            look = d or {}
            if DEBUG then print('[mp-look] ' .. #(look.fx or {}) .. ' effects, spell ' .. tostring(look.spell and look.spell.first)) end
            applyLook()
        end,
        MpJump = function() jumpLeft = 0.15 end,
        MpFloat = function(d)
            floatOn = d.on and true or false
            floatCheck = 0
            applyFloat(floatOn)
        end,
        -- They cast a spell: its cast effects on them, and on themselves its hit effects
        MpCastVfx = function(d) castVfx(d) end,
        MpEquip = function(d)
            wornEquip = d.slots
            equipForStance(Actor.getStance(self))
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
