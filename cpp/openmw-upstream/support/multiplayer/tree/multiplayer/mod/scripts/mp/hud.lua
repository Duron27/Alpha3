-- The multiplayer HUD over the world (player script; player.lua drives it):
--  * nameplates over the other players in your cell: name, level and a health bar; orange for your party, green for
--    everybody else, grey when they are down. They shrink with distance and fade out far away; walls do not hide them,
--    but magic does (invisible, or mostly chameleon), except for your party.
--  * numbers over their heads: damage they take (red), healing (green), skills that rise (gold). They float up and fade.
--  * a target frame at the top: the player under your crosshair, with level, race and class, health and latency.
--  * the connection line at the top left: hosting or connected, players online, your latency in colour.
local ui = require('openmw.ui')
local util = require('openmw.util')
local core = require('openmw.core')
local camera = require('openmw.camera')
local nearby = require('openmw.nearby')
local types = require('openmw.types')
local self = require('openmw.self')
local I = require('openmw.interfaces')

local V2, rgb = util.vector2, util.color.rgb
local WHITE = ui.texture { path = 'white' }
local ARROW = ui.texture { path = 'textures/mp_arrow.tga' } -- green: everybody else
local ARROW_GROUP = ui.texture { path = 'textures/mp_arrow_group.tga' } -- orange: your group

local M = {}

local HEAD_HEIGHT = 135
local PLATE_W, PLATE_BAR_H = 130, 6
local FADE_START, FADE_END = 2500, 4500 -- units: nameplates fade out between these distances
local FLOAT_TIME, FLOAT_RISE = 1.6, 60 -- seconds a number stays, how far (pixels) it rises
local MAX_FLOATS = 24

local C = {
    group = rgb(1, 0.72, 0.35), other = rgb(0.55, 1, 0.55), dead = rgb(0.6, 0.6, 0.6),
    hp = rgb(0.8, 0.13, 0.13), hpBack = rgb(0.12, 0.02, 0.02), frame = rgb(0, 0, 0),
    dmg = rgb(1, 0.3, 0.25), heal = rgb(0.4, 1, 0.45), skill = rgb(1, 0.85, 0.3), dim = rgb(0.75, 0.75, 0.75),
    good = rgb(0.45, 0.95, 0.45), fair = rgb(1, 0.85, 0.3), poor = rgb(1, 0.4, 0.3),
}

local plates = {} -- player id -> ui element
local floats = {} -- { id, text, color, age, dx }
local floatEls = {}
local targetEl, statusEl, statusSig
local targetId, targetCheck = nil, 0

local function screen()
    local ok, size = pcall(function() return ui.layers[ui.layers.indexOf('HUD')].size end)
    return ok and size or ui.screenSize()
end

local function text(props)
    props.textSize = props.textSize or 15
    props.textShadow = true
    props.textShadowColor = rgb(0, 0, 0)
    return { template = I.MWUI.templates.textNormal, props = props }
end

local function rect(pos, size, color, alpha)
    return { type = ui.TYPE.Image, props = { resource = WHITE, position = pos, size = size, color = color, alpha = alpha or 1 } }
end

-- A health bar: dark back, red fill, thin black frame
local function healthBar(x, y, w, h, frac, alpha)
    frac = math.max(0, math.min(1, tonumber(frac) or 1))
    return {
        rect(V2(x - 1, y - 1), V2(w + 2, h + 2), C.frame, 0.8 * alpha),
        rect(V2(x, y), V2(w, h), C.hpBack, 0.9 * alpha),
        rect(V2(x, y), V2(w * frac, h), C.hp, alpha),
    }
end

local function nameOfRecord(list, id)
    if not id then return nil end
    local ok, rec = pcall(function() return list.records[id] end)
    return ok and rec and rec.name or nil
end

------------------------------------------------------------------ projection

-- Where a point in the world is on the HUD (GUI units), how far it is, or nil when behind us or off screen.
local function project(pos, scr, k, camPos, forward)
    if (pos - camPos):dot(forward) <= 0 then return nil end
    local v = camera.worldToViewportVector(pos)
    if v.x < -80 or v.x > scr.x + 80 or v.y < -80 or v.y > scr.y + 80 then return nil end
    return V2(v.x * k, v.y * k), (pos - camPos):length()
end

------------------------------------------------------------------ nameplates

local function plateLayout(a, at, distance)
    local alpha = 1
    if distance > FADE_START then alpha = math.max(0, 1 - (distance - FADE_START) / (FADE_END - FADE_START)) end
    local scale = math.max(0.65, math.min(1, 900 / math.max(1, distance) + 0.45))
    local w = math.floor(PLATE_W * scale)
    local color = a.dead and C.dead or a.group and C.group or C.other
    local label = a.name .. (a.lvl and ('  ' .. a.lvl) or '')
    local size = math.floor(16 * scale + 0.5)
    local content = {
        text { text = label, textColor = color, textSize = size, position = V2(w / 2 + 4, 0), anchor = V2(0.5, 0) },
    }
    for _, e in ipairs(healthBar(4, size + 4, w, math.max(3, math.floor(PLATE_BAR_H * scale)), a.dead and 0 or a.hp, 1)) do
        content[#content + 1] = e
    end
    content[#content + 1] = { type = ui.TYPE.Image, props = { resource = a.group and ARROW_GROUP or ARROW,
        size = V2(16 * scale, 16 * scale), position = V2(w / 2 + 4, size + 12), anchor = V2(0.5, 0) } }
    return {
        layer = 'HUD', type = ui.TYPE.Widget,
        props = { position = at - V2(w / 2 + 4, size + 30), size = V2(w + 8, size + 30), alpha = alpha },
        content = ui.content(content),
    }
end

------------------------------------------------------------------ floating numbers

-- d: { id, k = 'dmg' | 'heal' | 'skill', n, s }
function M.addFloat(d)
    local str, color
    if d.k == 'dmg' then str, color = '-' .. tostring(d.n or '?'), C.dmg
    elseif d.k == 'heal' then str, color = '+' .. tostring(d.n or '?'), C.heal
    elseif d.k == 'skill' then str, color = tostring(d.s or 'Skill') .. (d.n and (' ' .. d.n) or '') .. '!', C.skill
    else return end
    -- side by side when several land at once
    local n = 0
    for _, f in ipairs(floats) do if f.id == d.id and f.age < 0.3 then n = n + 1 end end
    floats[#floats + 1] = { id = d.id, text = str, color = color, age = 0, dx = (n % 3 - 1) * 28, big = d.k == 'skill' }
    while #floats > MAX_FLOATS do table.remove(floats, 1) end
end

------------------------------------------------------------------ target frame

local function updateTarget(dt, avatars, players)
    targetCheck = targetCheck - dt
    if targetCheck <= 0 then
        targetCheck = 0.1
        targetId = nil
        pcall(function()
            local camPos = camera.getPosition()
            local dir = camera.viewportToWorldVector(V2(0.5, 0.5)):normalize()
            local hit = nearby.castRay(camPos, camPos + dir * 3000, { ignore = self })
            if hit.hitObject then
                for _, a in ipairs(avatars) do
                    if a.obj and a.obj == hit.hitObject and not a.hide then targetId = a.id end
                end
            end
        end)
    end
    local a
    for _, x in ipairs(avatars) do if x.id == targetId then a = x end end
    if not a then
        if targetEl then targetEl:destroy() targetEl = nil end
        return
    end
    local ping
    for _, pl in ipairs(players) do if pl.id == a.id then ping = pl.ping end end
    local scr = screen()
    local W, H = 300, 62
    local who = {}
    if a.lvl then who[#who + 1] = 'Level ' .. a.lvl end
    who[#who + 1] = nameOfRecord(types.NPC.races, a.race)
    who[#who + 1] = nameOfRecord(types.NPC.classes, a.cls)
    local sub = table.concat(who, ' ')
    local content = {
        rect(V2(0, 0), V2(W, H), C.frame, 0.55),
        text { text = a.name, textColor = a.group and C.group or C.other, textSize = 18, position = V2(10, 5) },
        text { text = sub, textColor = C.dim, textSize = 13, position = V2(10, 26) },
    }
    if ping then
        local pc = ping < 80 and C.good or ping < 180 and C.fair or C.poor
        content[#content + 1] = text { text = ping .. ' ms', textColor = pc, textSize = 13, position = V2(W - 10, 7), anchor = V2(1, 0) }
    end
    if a.group then content[#content + 1] = text { text = 'Party', textColor = C.group, textSize = 13, position = V2(W - 10, 26), anchor = V2(1, 0) } end
    for _, e in ipairs(healthBar(10, 46, W - 20, 9, a.dead and 0 or a.hp, 1)) do content[#content + 1] = e end
    content[#content + 1] = text { text = a.dead and 'Down' or (math.floor((a.hp or 1) * 100 + 0.5) .. '%'), textSize = 11,
        textColor = rgb(1, 1, 1), position = V2(W / 2, 44), anchor = V2(0.5, 0) }
    local layout = {
        layer = 'HUD', type = ui.TYPE.Widget,
        props = { position = V2(scr.x / 2 - W / 2, 12), size = V2(W, H) },
        content = ui.content(content),
    }
    if targetEl then targetEl.layout = layout targetEl:update() else targetEl = ui.create(layout) end
end

------------------------------------------------------------------ connection line

-- s: { connected, hosting, refused, port, ping, others }
function M.updateStatus(s)
    local label, color
    if s.connected then
        label = (s.hosting and ('Hosting' .. (s.port and (' on port ' .. s.port) or '')) or 'Online')
            .. '  ·  ' .. s.others .. ' other player' .. (s.others == 1 and '' or 's')
        if s.ping and not s.hosting then
            label = label .. '  ·  ' .. s.ping .. ' ms'
            color = s.ping < 80 and C.good or s.ping < 180 and C.fair or C.poor
        else
            color = C.good
        end
    elseif s.refused then
        label, color = 'Refused by the host (' .. tostring(s.refused) .. ')', C.poor
    else
        label, color = 'Not connected', C.poor
    end
    local sig = label
    if sig == statusSig and statusEl then return end
    statusSig = sig
    local layout = {
        layer = 'HUD', type = ui.TYPE.Widget, props = { position = V2(8, 6), size = V2(460, 22) },
        content = ui.content {
            rect(V2(0, 5), V2(10, 10), color, 1),
            text { text = label, textColor = rgb(0.92, 0.92, 0.92), textSize = 14, position = V2(16, 2) },
        },
    }
    if statusEl then statusEl.layout = layout statusEl:update() else statusEl = ui.create(layout) end
end

------------------------------------------------------------------ every frame

function M.update(dt, avatars, players)
    local scr = ui.screenSize()
    local gui = screen()
    local k = gui.x / scr.x
    local camPos = camera.getPosition()
    local forward = camera.viewportToWorldVector(V2(0.5, 0.5))
    local seen, heads = {}, {}
    for _, a in ipairs(avatars) do
        -- over the avatar's body where it is this frame (the position sent along is where they are by the network, which
        -- the body trails a little, and a frame old)
        local head = a.pos + util.vector3(0, 0, HEAD_HEIGHT)
        if a.obj then
            local ok, p = pcall(function()
                if not a.obj:isValid() then return nil end
                local box = a.obj.getBoundingBox and a.obj:getBoundingBox()
                if box and box.halfSize.z > 20 then -- (the top of the body: lower when they sneak)
                    return util.vector3(a.obj.position.x, a.obj.position.y, box.center.z + box.halfSize.z + 12)
                end
                return a.obj.position + util.vector3(0, 0, HEAD_HEIGHT)
            end)
            if ok and p then head = p end
        end
        local at, distance = project(head, scr, k, camPos, forward)
        if at then heads[a.id] = at end
        if at and not a.hide and distance < FADE_END then
            seen[a.id] = true
            local layout = plateLayout(a, at, distance)
            if plates[a.id] then plates[a.id].layout = layout plates[a.id]:update() else plates[a.id] = ui.create(layout) end
        end
    end
    for id, el in pairs(plates) do
        if not seen[id] then el:destroy() plates[id] = nil end
    end
    -- the numbers: rise and fade over their owner's head
    for i = #floats, 1, -1 do
        local f = floats[i]
        f.age = f.age + dt
        if f.age >= FLOAT_TIME then table.remove(floats, i) end
    end
    for i = 1, MAX_FLOATS do
        local f, el = floats[i], floatEls[i]
        local at = f and heads[f.id]
        if at then
            local t = f.age / FLOAT_TIME
            local layout = {
                layer = 'HUD', type = ui.TYPE.Widget,
                props = { position = at + V2(f.dx - 60, -60 - FLOAT_RISE * t), size = V2(120, 30), alpha = t < 0.6 and 1 or (1 - t) / 0.4 },
                content = ui.content { text { text = f.text, textColor = f.color, textSize = f.big and 20 or 22,
                    position = V2(60, 0), anchor = V2(0.5, 0) } },
            }
            if el then el.layout = layout el:update() else floatEls[i] = ui.create(layout) end
        elseif el then
            el:destroy()
            floatEls[i] = nil
        end
    end
    updateTarget(dt, avatars, players)
end

-- The HUD was rebuilt by the engine (a menu): drop our elements, they are made again next frame.
function M.reset()
    for id, el in pairs(plates) do el:destroy() plates[id] = nil end
    for i, el in pairs(floatEls) do el:destroy() floatEls[i] = nil end
    if targetEl then targetEl:destroy() targetEl = nil end
    if statusEl then statusEl:destroy() statusEl = nil end
    statusSig = nil
end

return M
