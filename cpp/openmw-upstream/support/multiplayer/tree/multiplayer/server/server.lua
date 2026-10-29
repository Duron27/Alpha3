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

local err = relay.start(OPTIONS.port, {
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
