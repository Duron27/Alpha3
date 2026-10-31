-- Client mods kept on the server. A server (or a game hosting) hands out a folder of Lua mods; the relay tells every client
-- which mods and files it has, with their SHA-256. This module compares that with what is installed here, asks the
-- player, downloads what is missing or changed over the session connection, checks every file against its hash (the
-- engine refuses a file whose hash is wrong) and removes what the server no longer has. If every changed file is one the
-- game already loaded, the Lua scripts are reloaded on the spot (the session reconnects by itself); new files or mods
-- are only seen by a game that starts again, so then it offers to restart.
local markup = require('openmw.markup')
local ok, modstore = pcall(require, 'openmw.modstore')
if not ok then modstore = nil end

local M = {}

local ctx -- { send(table), toUi(event, data), notice(text), declined(), reload(), now() }
local state -- nil | 'asking' | 'downloading' | 'done'
local need, drop -- files to fetch { mod, p, s, h }; things to delete { mod, p (nil: the whole mod) }
local index, cur, lastChunk, installed

local MAX_FILES, MAX_BYTES = 2000, 256 * 1024 * 1024

function M.init(c) ctx = c end
function M.available() return modstore ~= nil end
function M.busy() return state ~= nil end

local function isHash(h) return type(h) == 'string' and #h == 64 and h:match('^%x+$') ~= nil end

-- What the server hands out, as { mod id = { path = { s, h } } }, or nil if the list is not what it should be
local function parseServer(list)
    if type(list) ~= 'table' then return nil end
    local mods, files = {}, 0
    for _, mod in ipairs(list) do
        if type(mod) ~= 'table' or type(mod.id) ~= 'string' or type(mod.files) ~= 'table' then return nil end
        local entry = {}
        for _, f in ipairs(mod.files) do
            files = files + 1
            if files > MAX_FILES or type(f) ~= 'table' or type(f.p) ~= 'string' or type(f.s) ~= 'number' or not isHash(f.h) then
                return nil
            end
            entry[f.p] = { s = f.s, h = f.h:lower() }
        end
        mods[mod.id] = entry
    end
    return mods
end

local function parseLocal()
    local mods = {}
    local okJson, list = pcall(function() return markup.decodeYaml(modstore.manifest()) end)
    if not okJson or type(list) ~= 'table' then return mods end
    for _, mod in ipairs(list) do
        local entry = {}
        for _, f in ipairs(mod.files or {}) do entry[f.p] = { s = f.s, h = f.h } end
        mods[mod.id] = entry
    end
    return mods
end

local function sortedKeys(t)
    local keys = {}
    for k in pairs(t) do keys[#keys + 1] = k end
    table.sort(keys)
    return keys
end

local function fail(reason)
    if ctx then ctx.notice('Could not install the server\'s mods: ' .. tostring(reason)) end
    state, need, drop, cur = nil, nil, nil, nil
    if ctx then ctx.toUi('MpModsDone', { ok = false, reason = tostring(reason) }) end
end

-- The server's list arrived (after joining).
function M.onServerMods(list)
    if not modstore or not ctx or state == 'asking' or state == 'downloading' then return end
    state = nil
    local theirs = parseServer(list)
    if not theirs then return end
    local mine = parseLocal()
    need, drop = {}, {}
    local bytes, added, updated, deleted = 0, 0, 0, 0
    for _, id in ipairs(sortedKeys(theirs)) do
        for _, p in ipairs(sortedKeys(theirs[id])) do
            local want = theirs[id][p]
            local have = mine[id] and mine[id][p]
            -- only what differs: a file with the same hash is left alone
            if not have or have.h ~= want.h then
                need[#need + 1] = { mod = id, p = p, s = want.s, h = want.h }
                bytes = bytes + want.s
                if have then updated = updated + 1 else added = added + 1 end
            end
        end
        for _, p in ipairs(sortedKeys(mine[id] or {})) do
            if not theirs[id][p] then drop[#drop + 1] = { mod = id, p = p } deleted = deleted + 1 end
        end
    end
    for _, id in ipairs(sortedKeys(mine)) do
        if not theirs[id] then
            drop[#drop + 1] = { mod = id }
            for _ in pairs(mine[id]) do deleted = deleted + 1 end
        end
    end
    if #need == 0 and #drop == 0 then need, drop = nil, nil return end
    if bytes > MAX_BYTES then need, drop = nil, nil ctx.notice('The server\'s mods are too large to install.') return end
    -- the mods that change, for the question
    local names, seen = {}, {}
    for _, f in ipairs(need) do if not seen[f.mod] then seen[f.mod] = true names[#names + 1] = f.mod end end
    for _, d in ipairs(drop) do if not seen[d.mod] then seen[d.mod] = true names[#names + 1] = d.mod end end
    table.sort(names)
    state = 'asking'
    ctx.toUi('MpModPrompt', { mods = names, added = added, updated = updated, deleted = deleted, bytes = bytes })
end

local function requestNext()
    index = index + 1
    cur = need[index]
    if not cur then
        -- everything is here and checked: delete what the server no longer has
        for _, d in ipairs(drop) do
            local err = modstore.remove(d.mod, d.p or '')
            if err ~= '' then return fail('could not remove ' .. d.mod .. (d.p and ('/' .. d.p) or '') .. ': ' .. err) end
        end
        local count = #need
        local live = modstore.wasLoaded ~= nil and modstore.reloadScripts ~= nil and #drop == 0
        for _, f in ipairs(need) do
            if live and not modstore.wasLoaded(f.mod, f.p) then live = false end
        end
        state, need, drop, cur = 'done', nil, nil, nil
        if live then
            ctx.notice('The server\'s mods are installed (' .. count .. ' files). Reloading scripts...')
            ctx.reload()
            return
        end
        ctx.toUi('MpModsDone', { ok = true, files = count })
        return
    end
    cur.parts, cur.got = {}, 0
    lastChunk = ctx.now()
    ctx.send({ t = 'modget', mod = cur.mod, p = cur.p, off = 0 })
end

-- The player's answer to the question.
function M.answer(accept)
    if state ~= 'asking' then return end
    if not accept then
        state, need, drop = nil, nil, nil
        ctx.declined()
        return
    end
    state, index = 'downloading', 0
    ctx.notice('Downloading the server\'s mods...')
    requestNext()
end

function M.onChunk(msg)
    if state ~= 'downloading' or not cur then return end
    if msg.mod ~= cur.mod or msg.p ~= cur.p then return end
    if msg.missing then return fail('the server no longer has ' .. cur.p) end
    if msg.off ~= cur.got or type(msg.d) ~= 'string' or type(msg.n) ~= 'number' or msg.n ~= cur.s then
        return fail('the server sent ' .. cur.p .. ' wrong')
    end
    local piece = modstore.base64Decode(msg.d)
    if not piece then return fail('the server sent ' .. cur.p .. ' wrong') end
    cur.parts[#cur.parts + 1] = piece
    cur.got = cur.got + #piece
    lastChunk = ctx.now()
    if cur.got > cur.s then return fail(cur.p .. ' is larger than announced') end
    if cur.got < cur.s and #piece > 0 then
        return ctx.send({ t = 'modget', mod = cur.mod, p = cur.p, off = cur.got })
    end
    if cur.got ~= cur.s then return fail(cur.p .. ' ended early') end
    -- the engine writes it only if the SHA-256 is the announced one
    local err = modstore.write(cur.mod, cur.p, table.concat(cur.parts), cur.h)
    if err ~= '' then return fail(cur.mod .. '/' .. cur.p .. ': ' .. err) end
    requestNext()
end

function M.update()
    if state == 'downloading' and ctx.now() - lastChunk > 20 then fail('the server stopped answering') end
end

-- Reloads the Lua scripts (see reload in the context).
function M.reloadScripts() if modstore and modstore.reloadScripts then modstore.reloadScripts() end end

-- The player asked to restart (after the install).
function M.restart()
    if state ~= 'done' then return end
    if not modstore.restart() then
        ctx.notice('The mods are installed. Start the game again to load them.')
    end
end

-- Connection gone: forget a half done download (what was written stays: files are only ever written whole and checked)
function M.reset()
    if state == 'asking' or state == 'downloading' then state, need, drop, cur = nil, nil, nil, nil end
end

return M
