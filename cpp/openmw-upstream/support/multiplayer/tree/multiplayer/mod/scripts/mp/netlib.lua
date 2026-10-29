-- The multiplayer networking layer, written on top of the engine's generic sockets (openmw.net):
--   * one outgoing connection to a host ("client"),
--   * a listener with any number of players ("server"; player ids are small numbers starting at 1),
--   * a JSON encoder (decoding is markup.decodeYaml),
--   * a small HTTP lookup of the public internet address.
-- Engine events arrive through M.onEvent (wired to the onNetEvent handler); the owner sets M.callbacks:
--   onConnected(), onDisconnected(reason), onMessage(line)                      -- client
--   onClientConnected(id), onClientDisconnected(id), onClientMessage(id, line)  -- server
local net = require('openmw.net')

local M = {}
M.callbacks = {}

------------------------------------------------------------------ JSON

local MAX_DEPTH = 32
local ESCAPES = { ['"'] = '\\"', ['\\'] = '\\\\', ['\n'] = '\\n', ['\r'] = '\\r', ['\t'] = '\\t' }

local function quote(s)
    return '"' .. (s:gsub('[%c"\\]', function(c) return ESCAPES[c] or string.format('\\u%04x', c:byte()) end)) .. '"'
end

local function encode(v, depth, out)
    local t = type(v)
    if t == 'nil' then out[#out + 1] = 'null'
    elseif t == 'boolean' then out[#out + 1] = v and 'true' or 'false'
    elseif t == 'number' then
        if v ~= v or v == math.huge or v == -math.huge then error('encodeJson: NaN and infinity can not be encoded', 0) end
        if v == math.floor(v) and math.abs(v) < 1e15 then out[#out + 1] = string.format('%d', v)
        else out[#out + 1] = string.format('%.17g', v) end
    elseif t == 'string' then out[#out + 1] = quote(v)
    elseif t == 'table' then
        if depth > MAX_DEPTH then error('encodeJson: table is nested too deeply or contains a cycle', 0) end
        local count, maxIndex, isArray = 0, 0, true
        for k in pairs(v) do
            count = count + 1
            if type(k) == 'number' and k >= 1 and k == math.floor(k) then
                if k > maxIndex then maxIndex = k end
            else
                isArray = false
            end
        end
        if count == 0 then out[#out + 1] = '[]'
        elseif isArray and maxIndex == count then
            out[#out + 1] = '['
            for i = 1, count do
                if i > 1 then out[#out + 1] = ',' end
                encode(v[i], depth + 1, out)
            end
            out[#out + 1] = ']'
        else
            out[#out + 1] = '{'
            local first = true
            for k, x in pairs(v) do
                if type(k) ~= 'string' then error('encodeJson: object keys must be strings', 0) end
                if not first then out[#out + 1] = ',' end
                first = false
                out[#out + 1] = quote(k)
                out[#out + 1] = ':'
                encode(x, depth + 1, out)
            end
            out[#out + 1] = '}'
        end
    else
        error('encodeJson: unsupported value type: ' .. t, 0)
    end
end

-- Lua value -> JSON text without line breaks. nil, booleans, numbers, strings and tables; a table with keys 1..n
-- is an array, anything else is an object with string keys, and an empty table is an empty array.
function M.encodeJson(value)
    local out = {}
    encode(value, 0, out)
    return table.concat(out)
end

------------------------------------------------------------------ client: one connection to a host

local clientId, clientUp = nil, false

function M.disconnect()
    if clientId then net.close(clientId) end
    clientId, clientUp = nil, false
end

function M.connect(host, port)
    M.disconnect()
    clientId = net.connect(host, port)
    -- told to the server if the game is closed normally: it is not a lost connection, nothing to hold for us
    if clientId then net.setFarewell(clientId, M.encodeJson({ t = 'bye' })) end
end

function M.isConnected() return clientUp end

function M.send(line)
    if not clientUp then return false end
    return net.send(clientId, line)
end

------------------------------------------------------------------ server: a listener and its players

local listenerId
local socketOfPlayer, playerOfSocket = {}, {}
local nextPlayer = 1

-- Returns an error message, or nil on success.
function M.stopListening()
    if listenerId then net.close(listenerId) end
    for _, sock in pairs(socketOfPlayer) do net.close(sock) end
    listenerId, socketOfPlayer, playerOfSocket, nextPlayer = nil, {}, {}, 1
end

function M.listen(port)
    M.stopListening()
    local id, err = net.listen(port)
    if not id then return err or 'could not listen' end
    listenerId = id
    return nil
end

function M.isListening() return listenerId ~= nil end

function M.sendTo(player, line)
    local sock = socketOfPlayer[player]
    return sock ~= nil and net.send(sock, line)
end

function M.kick(player)
    local sock = socketOfPlayer[player]
    if not sock then return end
    net.close(sock)
    socketOfPlayer[player], playerOfSocket[sock] = nil, nil
end

-- Where a player connects from (nil if the engine cannot tell).
function M.remoteAddress(player)
    local sock = socketOfPlayer[player]
    if not sock or not net.remoteAddress then return nil end
    local ok, address = pcall(net.remoteAddress, sock)
    return ok and address or nil
end

------------------------------------------------------------------ public address lookup (HTTP GET to api.ipify.org)

local LOOKUP_TIMEOUT = 10
local lookup -- { id, status, headersDone, body, age }
local lookupResult -- { address } or { error }, read once by getPublicAddress
M.lanAddress = nil -- our address on the network that reached the internet (known after a lookup connected)

local function finishLookup(address, err)
    if lookup and lookup.id then net.close(lookup.id) end
    lookup = nil
    lookupResult = address and { address = address } or { error = err or 'lookup failed' }
end

function M.lookupPublicAddress()
    if lookup then return end
    lookupResult = nil
    local id = net.connect('api.ipify.org', 80)
    if not id then return finishLookup(nil, 'too many sockets') end
    lookup = { id = id, body = {}, age = 0 }
end

-- Nothing while the lookup runs; once it is done: address, or nil and an error message (returned only once).
function M.getPublicAddress()
    local r = lookupResult
    if not r then return nil end
    lookupResult = nil
    return r.address, r.error
end

local function lookupEvent(kind, data)
    if kind == 'connected' then
        M.lanAddress = net.localAddress(lookup.id)
        net.send(lookup.id, 'GET / HTTP/1.0\r\nHost: api.ipify.org\r\nUser-Agent: openmw-multiplayer\r\n\r\n', true)
    elseif kind == 'line' then
        if not lookup.status then lookup.status = data
        elseif lookup.headersDone then lookup.body[#lookup.body + 1] = data
        elseif data == '' then lookup.headersDone = true end
    elseif kind == 'closed' then
        lookup.id = nil -- already closed
        local address
        for _, line in ipairs(lookup.body) do
            line = line:gsub('%s', '')
            if line ~= '' and #line <= 45 and not line:find('[^%x%.:]') then address = line end
        end
        if address and lookup.status and lookup.status:find(' 200') then finishLookup(address)
        else finishLookup(nil, data ~= '' and ('could not reach api.ipify.org: ' .. tostring(data)) or 'unexpected reply') end
    end
end

-- Call every frame with the real elapsed time (timeouts).
function M.update(dt)
    if lookup then
        lookup.age = lookup.age + dt
        if lookup.age > LOOKUP_TIMEOUT then finishLookup(nil, 'timed out') end
    end
end

------------------------------------------------------------------ engine events

function M.onEvent(kind, id, data, listener)
    local cb = M.callbacks
    if lookup and id == lookup.id then return lookupEvent(kind, data) end
    if id == clientId then
        if kind == 'connected' then
            clientUp = true
            if cb.onConnected then cb.onConnected() end
        elseif kind == 'line' then
            if cb.onMessage then cb.onMessage(data) end
        elseif kind == 'closed' then
            clientId, clientUp = nil, false
            if cb.onDisconnected then cb.onDisconnected(data) end
        end
    elseif kind == 'accepted' then
        if listener == listenerId then
            local player = nextPlayer
            nextPlayer = nextPlayer + 1
            socketOfPlayer[player], playerOfSocket[id] = id, player
            if cb.onClientConnected then cb.onClientConnected(player) end
        else
            net.close(id) -- a listener we no longer track
        end
    else
        local player = playerOfSocket[id]
        if not player then return end
        if kind == 'line' then
            if cb.onClientMessage then cb.onClientMessage(player, data) end
        elseif kind == 'closed' then
            socketOfPlayer[player], playerOfSocket[id] = nil, nil
            if cb.onClientDisconnected then cb.onClientDisconnected(player) end
        end
    end
end

return M
