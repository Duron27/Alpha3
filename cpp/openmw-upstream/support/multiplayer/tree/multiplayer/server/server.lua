-- The server script run by openmw-mp-server (apps/mpserver). The program supplies sockets, a clock and a JSON decoder
-- as the usual engine modules; everything else is the mod's own relay, so a server behaves like a hosted game that
-- nobody plays on. OPTIONS is set by the program from its command line.
local net = require('scripts/mp/netlib')
local relay = require('scripts/mp/relay')

net.callbacks = {
    onClientConnected = relay.onClientConnected,
    onClientDisconnected = relay.onClientDisconnected,
    onClientMessage = relay.onClientMessage,
}

-- Bans outlive a restart: one name a line in bans.txt where the server runs (or OPTIONS.bansFile)
local BANS_FILE = OPTIONS.bansFile or 'bans.txt'
local function loadBans()
    local f = io and io.open(BANS_FILE, 'r')
    if not f then return {} end
    local list = {}
    for line in f:lines() do
        line = line:match('^%s*(.-)%s*$')
        if line ~= '' and not line:find('^#') then list[#list + 1] = line end
    end
    f:close()
    return list
end
local function saveBans(list)
    local f = io and io.open(BANS_FILE, 'w')
    if not f then print('[server] cannot write ' .. BANS_FILE) return end
    f:write('# names banned from this server, one a line (lower case); delete a line to unban\n')
    for _, name in ipairs(list) do f:write(name, '\n') end
    f:close()
end

local err = relay.start(OPTIONS.port, {
    onBansChanged = saveBans,
    dedicated = true,
    password = OPTIONS.password,
    adminPassword = OPTIONS.adminPassword,
    maxPlayers = OPTIONS.maxPlayers,
    motd = OPTIONS.motd,
    weather = OPTIONS.weather,
    weatherMinutes = OPTIONS.weatherMinutes,
    timeScale = OPTIONS.timeScale,
    startHour = OPTIONS.startHour,
})
if err then error('cannot listen on port ' .. tostring(OPTIONS.port) .. ': ' .. tostring(err), 0) end
local banned = loadBans()
relay.setBans(banned)
if #banned > 0 then print(string.format('[server] %d banned name(s) from %s', #banned, BANS_FILE)) end

print(string.format('[server] listening on port %d%s', OPTIONS.port, OPTIONS.password ~= '' and ' (password protected)' or ' (no password)'))
print(string.format('[server] up to %d players; weather %s; time starts at %s and runs %gx',
    OPTIONS.maxPlayers, OPTIONS.weather == 'cycle' and 'changes by itself' or ('fixed at ' .. OPTIONS.weather),
    string.format('%d:%02d', math.floor(OPTIONS.startHour), math.floor(OPTIONS.startHour % 1 * 60)), OPTIONS.timeScale))
if OPTIONS.adminPassword == '' then print('[server] no admin password: players cannot use /login (use this console)') end
print('[server] type help for the list of commands, quit to stop')

local M = {}

function M.onEvent(kind, id, data, listener) net.onEvent(kind, id, data, listener) end

function M.update(dt) relay.update(dt) end

function M.console(line) return relay.console(line) end

function M.snapshot() return relay.snapshot() end

return M
