-- Player-to-player trade (player script side): protocol state machine and the trade window.
-- Both players see both inventories (like a merchant's goods) and edit one shared deal:
-- "you give" (from your inventory) and "they give" (from theirs). Any edit resets both accepts;
-- when both have accepted the same revision each client applies it through global.lua (MpTradeExec).
local ui = require('openmw.ui')
local util = require('openmw.util')
local core = require('openmw.core')
local self = require('openmw.self')
local types = require('openmw.types')
local async = require('openmw.async')
local I = require('openmw.interfaces')
local items = require('scripts/mp/items')

local V2, rgb = util.vector2, util.color.rgb
local WHITE = ui.texture { path = 'white' }

local W, H = 980, 580
local ROW_H, TEXT_SIZE = 20, 14
local INV_ROWS, DEAL_ROWS = 18, 8
local REQUEST_TTL = 60

local C = {
    text = rgb(0.92, 0.92, 0.92), dim = rgb(0.6, 0.6, 0.6), head = rgb(1, 0.85, 0.35),
    give = rgb(0.95, 0.6, 0.45), get = rgb(0.5, 0.9, 0.55), ok = rgb(0.45, 0.95, 0.5),
    button = rgb(1, 1, 1), panel = rgb(0.12, 0.1, 0.08),
}

local ITEM_TYPES = { types.Weapon, types.Armor, types.Clothing, types.Book, types.Potion, types.Ingredient,
    types.Miscellaneous, types.Apparatus, types.Lockpick, types.Probe, types.Repair, types.Light }

local M = {}
local ctx -- { send(msg), notice(text), setMode(), unsetMode() }
local session -- see start()
local pendingIn = {} -- partner id -> { name, expires }
local outgoing -- { id, name, expires }
local window
local nameCache = {}
local ignoreModeUntil = 0

function M.init(context) ctx = context end

local function now() return core.getRealTime() end

local function itemName(rid)
    if nameCache[rid] then return nameCache[rid] end
    local name = rid
    if items.isKey(rid) then
        -- items made while playing: named by their description
        local desc = (session and ((session.myGens and session.myGens[rid]) or (session.theirGens and session.theirGens[rid])))
        return desc and items.name(desc) or rid
    end
    for _, T in ipairs(ITEM_TYPES) do
        local ok, rec = pcall(function() return T.records[rid] end)
        if ok and rec and rec.name and rec.name ~= '' then name = rec.name break end
    end
    nameCache[rid] = name
    return name
end

local MAX_GENERATED = 60 -- distinct made-up items offered in one trade

-- What we can offer: record id -> count, plus (for items made while playing) key -> count and key -> description.
local function snapshot()
    local list, gens, nGens = {}, {}, 0
    for _, obj in ipairs(types.Actor.inventory(self):getAll()) do
        local rid = obj.recordId
        if rid:lower():find('^generated:') then
            local desc = items.describe(obj)
            if desc then
                local key = items.key(desc)
                if gens[key] or nGens < MAX_GENERATED then
                    if not gens[key] then nGens = nGens + 1 end
                    gens[key] = desc
                    list[key] = (list[key] or 0) + obj.count
                end
            end
        else
            list[rid] = (list[rid] or 0) + obj.count
        end
    end
    return list, gens
end

local function sorted(map)
    local list = {}
    for rid, count in pairs(map) do
        if count > 0 then list[#list + 1] = { rid = rid, count = count, name = itemName(rid) } end
    end
    table.sort(list, function(a, b) return a.name:lower() < b.name:lower() end)
    return list
end

local function send(t) t.to = session and session.partner or t.to; ctx.send(t) end

------------------------------------------------------------------ window

local function destroyWindow()
    if window then window:destroy() window = nil end
end

local render

local function bump()
    session.rev = session.rev + 1
    session.meOk, session.themOk = false, false
    send({ t = 'trade_deal', rev = session.rev, a = session.a, b = session.b })
    render()
end

local function edit(map, limitMap, rid, delta)
    local cur = map[rid] or 0
    local new = math.max(0, math.min(limitMap[rid] or 0, cur + delta))
    if new == cur then return end
    map[rid] = new > 0 and new or nil
    bump()
end

local function close(sendCancel, text)
    if session and sendCancel then send({ t = 'trade_cancel' }) end
    session = nil
    destroyWindow()
    ignoreModeUntil = now() + 0.3
    ctx.unsetMode()
    if text then ctx.notice(text) end
end

local function execute()
    if session.executed then return end
    session.executed = true
    core.sendGlobalEvent('MpTradeExec', { give = session.a, get = session.b, gens = session.theirGens })
end

local function text(props, events)
    props.textSize = props.textSize or TEXT_SIZE
    return { template = I.MWUI.templates.textNormal, props = props, events = events }
end

local function listRows(content, x, y, w, list, offset, rows, color, onPress, onWheel)
    for i = 1, rows do
        local item = list[offset + i]
        if not item then break end
        content[#content + 1] = text({
            position = V2(x, y + (i - 1) * ROW_H), text = item.name .. '  x' .. item.count, textColor = color,
        }, {
            mousePress = async:callback(function(e) onPress(item, e.button) end),
            mouseWheel = async:callback(function(e) onWheel(-e.delta.y) end),
        })
    end
end

render = function()
    if not session or session.phase ~= 'active' then return end
    local content = {
        { type = ui.TYPE.Image, props = { resource = WHITE, relativeSize = V2(1, 1), color = C.panel, alpha = 0.92 } },
        text({ position = V2(14, 10), text = 'Trade with ' .. session.name, textColor = C.head, textSize = 18 }),
        text({ position = V2(14, 36), text = 'Click an item to add one, right-click to add all. Click items in the deal to remove them.', textColor = C.dim, textSize = 12 }),
    }
    local function panel(x, title, color)
        content[#content + 1] = text({ position = V2(x, 62), text = title, textColor = color or C.head, textSize = 15 })
    end
    local myList, theirList = sorted(session.myInv), sorted(session.theirInv)
    local giveList, getList = sorted(session.a), sorted(session.b)
    local colMine, colDeal, colTheirs = 14, 330, 670

    panel(colMine, 'Your items')
    listRows(content, colMine, 86, 300, myList, session.scrollMine, INV_ROWS, C.text,
        function(item, b) edit(session.a, session.myInv, item.rid, b == 3 and item.count or 1) end,
        function(d) session.scrollMine = math.max(0, math.min(math.max(0, #myList - INV_ROWS), session.scrollMine + d)); render() end)

    panel(colDeal, 'You give', C.give)
    listRows(content, colDeal, 86, 320, giveList, 0, DEAL_ROWS, C.give,
        function(item, b) edit(session.a, session.myInv, item.rid, b == 3 and -item.count or -1) end, function() end)
    panel(colDeal, session.name .. ' gives', C.get)
    content[#content].props.position = V2(colDeal, 86 + DEAL_ROWS * ROW_H + 24)
    listRows(content, colDeal, 86 + DEAL_ROWS * ROW_H + 48, 320, getList, 0, DEAL_ROWS, C.get,
        function(item, b) edit(session.b, session.theirInv, item.rid, b == 3 and -item.count or -1) end, function() end)

    panel(colTheirs, session.name .. "'s items")
    listRows(content, colTheirs, 86, 300, theirList, session.scrollTheirs, INV_ROWS, C.text,
        function(item, b) edit(session.b, session.theirInv, item.rid, b == 3 and item.count or 1) end,
        function(d) session.scrollTheirs = math.max(0, math.min(math.max(0, #theirList - INV_ROWS), session.scrollTheirs + d)); render() end)

    local status = (session.meOk and 'You accepted. ' or '') .. (session.themOk and (session.name .. ' accepted.') or '')
    if status == '' then status = 'Waiting for both players to accept the deal.' end
    content[#content + 1] = text({ position = V2(14, H - 70), text = status, textColor = C.ok })
    content[#content + 1] = text({
        position = V2(14, H - 40), textSize = 17, textColor = C.button,
        text = session.meOk and '[ Accepted - waiting ]' or '[ Accept deal ]',
    }, { mouseClick = async:callback(function()
        if session.meOk then return end
        session.meOk = true
        send({ t = 'trade_ok', rev = session.rev })
        if session.themOk then execute() end
        render()
    end) })
    content[#content + 1] = text({
        position = V2(W - 130, H - 40), textSize = 17, textColor = C.button, text = '[ Cancel ]',
    }, { mouseClick = async:callback(function() close(true, 'Trade cancelled.') end) })

    local layout = {
        layer = 'Windows', type = ui.TYPE.Widget,
        props = { relativePosition = V2(0.5, 0.5), anchor = V2(0.5, 0.5), size = V2(W, H) },
        content = ui.content(content),
    }
    if window then
        window.layout = layout
        window:update()
    else
        window = ui.create(layout)
    end
end

------------------------------------------------------------------ session control

local function start(partner, name)
    local inv, gens = snapshot()
    session = {
        phase = 'active', partner = partner, name = name, rev = 0, a = {}, b = {},
        myInv = inv, myGens = gens, theirInv = {}, theirGens = {}, meOk = false, themOk = false,
        scrollMine = 0, scrollTheirs = 0,
    }
    outgoing = nil
    pendingIn[partner] = nil
    ctx.setMode()
    send({ t = 'trade_inv', items = session.myInv, gens = next(gens) and gens or nil })
    render()
end

function M.active() return session ~= nil end
function M.hasIncoming(id) local p = pendingIn[id] return p ~= nil and p.expires > now() end
function M.pendingOutgoing(id) return outgoing ~= nil and outgoing.id == id and outgoing.expires > now() end

function M.request(id, name)
    if session then ctx.notice('You are already trading.') return end
    outgoing = { id = id, name = name, expires = now() + REQUEST_TTL }
    ctx.send({ t = 'trade_request', to = id })
    ctx.notice('Trade request sent to ' .. name .. '.')
end

function M.accept(id)
    local p = pendingIn[id]
    if not p or session then return end
    ctx.send({ t = 'trade_accept', to = id })
    start(id, p.name)
end

function M.decline(id)
    local p = pendingIn[id]
    pendingIn[id] = nil
    ctx.send({ t = 'trade_decline', to = id })
    if p then ctx.notice('Declined the trade with ' .. p.name .. '.') end
end

function M.onMode(newMode)
    -- the Interface mode that hosts the window was closed (Escape) or replaced
    if session and newMode ~= 'Interface' and now() > ignoreModeUntil then
        close(true, 'Trade cancelled.')
    end
end

function M.onMessage(msg)
    local t, from = msg.t, msg.id
    if t == 'trade_request' then
        if session then ctx.send({ t = 'trade_decline', to = from }) return end
        pendingIn[from] = { name = msg.name, expires = now() + REQUEST_TTL }
        ctx.notice(msg.name .. ' wants to trade. Activate them to accept.')
    elseif t == 'trade_accept' then
        if outgoing and outgoing.id == from and not session then start(from, msg.name) end
    elseif t == 'trade_decline' then
        if outgoing and outgoing.id == from then outgoing = nil ctx.notice(msg.name .. ' declined the trade.') end
    elseif t == 'trade_cancel' then
        pendingIn[from] = nil
        if outgoing and outgoing.id == from then outgoing = nil end
        if session and session.partner == from then
            close(false, msg.left and (msg.name .. ' left the game. Trade cancelled.') or (msg.name .. ' cancelled the trade.'))
        end
    elseif session and session.partner == from then
        if t == 'trade_inv' then
            session.theirInv = msg.items or {}
            session.theirGens = type(msg.gens) == 'table' and msg.gens or {}
            -- only keys that match their description can be offered (the other side checks again)
            for key, desc in pairs(session.theirGens) do
                if items.key(desc) ~= key then session.theirGens[key] = nil; session.theirInv[key] = nil end
            end
            -- drop deal entries the partner can no longer cover
            render()
        elseif t == 'trade_deal' and msg.rev >= session.rev then
            session.rev = msg.rev
            session.b, session.a = msg.a or {}, msg.b or {} -- their "give" is what we get, and vice versa
            session.meOk, session.themOk = false, false
            render()
        elseif t == 'trade_ok' and msg.rev == session.rev then
            session.themOk = true
            if session.meOk then execute() end
            render()
        end
    end
end

-- test hooks (same code paths as clicking)
function M.debugEdit(side, rid, delta)
    if not session or session.phase ~= 'active' then return end
    if side == 'give' then edit(session.a, session.myInv, rid, delta) else edit(session.b, session.theirInv, rid, delta) end
end

function M.debugAccept()
    if not session or session.meOk then return end
    session.meOk = true
    send({ t = 'trade_ok', rev = session.rev })
    if session.themOk then execute() end
    render()
end

function M.onResult(r)
    if not session then return end
    if r.ok then
        local partner = session.partner
        send({ t = 'trade_done' })
        close(false, 'Trade complete.')
    else
        close(true, 'Trade failed: ' .. tostring(r.err))
    end
end

return M
