-- A fake second player that lives inside the host's relay, for trying the multiplayer UI with one game:
-- it stands near you, accepts party invites, answers chat, trades, and shows vitals once in a party.
local relay = require('scripts/mp/relay')
local util = require('openmw.util')
local core = require('openmw.core')

local NAME = 'Test Bot'
local BOT_ITEMS = { ['gold_001'] = 500, ['iron dagger'] = 2, ['ingred_bread_01'] = 5, ['p_restore_health_c'] = 3,
    ['misc_com_bottle_01'] = 4 }
local OFFSET = util.vector3(220, 160, 0)

local M = {}
local id, generation
local anchorCell, anchor
local sendTimer, vitalsTimer, t = 0, 0, 0
local party = {}
local ctx

local function send(msg) relay.virtualSend(id, msg) end

local function say(scope, text, to)
    send({ t = 'chat', scope = scope, text = text, to = to })
end

local function onMessage(msg)
    local mt = msg.t
    if mt == 'invite' then
        send({ t = 'accept', from = msg.from })
    elseif mt == 'party' then
        party = type(msg.members) == 'table' and msg.members or {}
    elseif mt == 'chat' then
        local reply = 'I heard "' .. tostring(msg.text) .. '".'
        if msg.scope == 'whisper' then say('whisper', reply, msg.id)
        elseif msg.scope == 'party' then say('party', reply)
        else say('global', reply) end
    elseif mt == 'trade_request' then
        send({ t = 'trade_accept', to = msg.id })
        send({ t = 'trade_inv', to = msg.id, items = BOT_ITEMS })
    elseif mt == 'trade_deal' then
        send({ t = 'trade_ok', to = msg.id, rev = msg.rev })
    end
end

function M.isRunning() return id ~= nil and generation == relay.generation() end

function M.start(context)
    if M.isRunning() then return end
    ctx = context
    id, generation = relay.addVirtual(onMessage), relay.generation()
    anchor, anchorCell, party = nil, nil, {}
    send({ t = 'profile', name = NAME, race = 'dark elf', class = 'acrobat', head = 'b_n_dark elf_m_head_01',
        hair = 'b_n_dark elf_m_hair_01', isMale = true, content = {} })
    send({ t = 'equip', slots = {}, gen = {} })
    say('global', 'Hello! I am a test player. Activate me to invite me to a party or trade, or just chat.')
end

function M.stop()
    if id and generation == relay.generation() then relay.removeVirtual(id) end
    id = nil
end

function M.update(dt)
    if not id then return end
    if generation ~= relay.generation() then id = nil return end -- the relay was reset
    local p = ctx.player()
    if not p then return end
    t = t + dt
    sendTimer = sendTimer + dt
    if sendTimer < 0.1 then return end
    sendTimer = 0
    local cell = ctx.cellKey(p.cell)
    -- stay put near where you were when we saw you, but come along if you wander off or change cell
    if cell ~= anchorCell or not anchor or (p.position - anchor):length() > 900 then
        anchorCell, anchor = cell, p.position + OFFSET
    end
    local to = p.position - anchor
    send({ t = 'state', cell = cell, pos = { anchor.x, anchor.y, anchor.z }, vel = { 0, 0, 0 },
        yaw = math.atan2(to.x, to.y), pitch = 0, stance = 0 })
    if #party > 1 then
        vitalsTimer = vitalsTimer + 0.1
        if vitalsTimer >= 0.5 then
            vitalsTimer = 0
            send({ t = 'vitals', h = 80, hm = 80, m = 30 + math.floor(20 * (1 + math.sin(t / 4))), mm = 70,
                f = 70 + math.floor(25 * (1 + math.sin(t / 6))), fm = 120,
                bad = math.sin(t / 8) > 0 and { { n = 'Poison', c = 'p' }, { n = 'Ataxia', c = 'd' } } or {} })
        end
    end
end

return M
