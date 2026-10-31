-- Runs the relay logic outside the game: luajit multiplayer/test/relay_test.lua
-- The engine modules it needs are replaced by small fakes.
local sent = {}      -- socket id -> list of messages the relay sent to that player
local kicked = {}
local now = 1000

local json = dofile('multiplayer/test/jsonlite.lua') -- small JSON codec for the test

package.loaded['openmw.core'] = { getRealTime = function() return now end }
package.loaded['openmw.markup'] = { decodeYaml = json.decode }
package.loaded['scripts/mp/netlib'] = {
    encodeJson = json.encode,
    listen = function() return nil end,
    isListening = function() return true end,
    stopListening = function() end,
    sendTo = function(id, line) sent[id] = sent[id] or {}; table.insert(sent[id], json.decode(line)); return true end,
    kick = function(id) kicked[id] = true end,
}
package.path = 'multiplayer/mod/?.lua;' .. package.path
local relay = require('scripts/mp/relay')

local failures = 0
local function check(cond, what) if not cond then failures = failures + 1 print('FAIL: ' .. what) end end
local function last(id, t)
    for i = #(sent[id] or {}), 1, -1 do if sent[id][i].t == t then return sent[id][i] end end
end
local function count(id, t) local n = 0 for _, m in ipairs(sent[id] or {}) do if m.t == t then n = n + 1 end end return n end
local function say(id, msg) relay.onClientMessage(id, json.encode(msg)) end
local function join(id, name, password, proto)
    relay.onClientConnected(id)
    say(id, { t = 'join', proto = proto or 3, name = name, password = password })
end
local function tick(seconds) now = now + seconds; relay.update(seconds) end

-- ---------------------------------------------------------------- join checks
check(relay.start(25565, { password = 'secret', maxPlayers = 3, motd = 'hello all' }) == nil, 'start')
relay.onClientConnected(1)
check(last(1, 'hello') and last(1, 'hello').proto == 3 and last(1, 'hello').needPassword, 'hello announces protocol and password')
say(1, { t = 'chat', scope = 'global', text = 'sneaky' })
check(not last(1, 'chat'), 'nothing is accepted before join')
say(1, { t = 'join', proto = 3, name = 'Host', password = 'secret' })
check(last(1, 'join_result') and last(1, 'join_result').ok and last(1, 'join_result').motd == 'hello all', 'host joins, gets motd')

join(2, 'Alice', 'wrong')
check(last(2, 'join_result') and last(2, 'join_result').reason == 'password', 'wrong password refused')
tick(1); check(kicked[2], 'refused client is disconnected')
join(3, 'Bob', 'secret', 2)
check(last(3, 'join_result').reason == 'version', 'old protocol refused')
tick(1)
join(4, 'Alice', 'secret')
check(last(4, 'join_result').ok, 'right password joins')
join(5, 'Alice', 'secret')
check(last(5, 'join_result').ok and last(5, 'join_result').name == 'Alice (2)', 'duplicate names get a suffix')
join(6, 'Carol', 'secret')
check(last(6, 'join_result').reason == 'full', 'player limit')
tick(1)

-- nobody is told about refused clients; admitted ones see each other
say(4, { t = 'profile', name = 'Alice', race = 'x' })
check(last(5, 'profile') and last(5, 'profile').name == 'Alice', 'profiles are shared')
say(5, { t = 'profile', name = 'Alice', race = 'x' })
check(last(4, 'profile').name == 'Alice (2)', 'profile carries the unique name')

-- ---------------------------------------------------------------- invites
say(4, { t = 'invite', to = 5 })
check(last(5, 'invite') and last(5, 'invite').from == 4, 'invite arrives')
say(4, { t = 'invite', to = 5 })
check(count(5, 'invite') == 1, 'a pending invite is not repeated')
say(5, { t = 'accept', from = 4 })
check(last(4, 'party') and #last(4, 'party').members == 2, 'party formed')
say(5, { t = 'party_kick', id = 4 })
check(last(5, 'notice') and last(5, 'notice').text:find('leader'), 'only the leader can remove members')
say(4, { t = 'party_kick', id = 5 })
check(last(5, 'party') and #last(5, 'party').members == 0, 'the leader can remove a member')
tick(2); say(4, { t = 'invite', to = 5 }); say(5, { t = 'accept', from = 4 })
check(last(4, 'party') and #last(4, 'party').members == 2, 'they can party up again')
tick(2)
say(4, { t = 'invite', to = 5 })
check(last(4, 'invite_result') and last(4, 'invite_result').reason == 'already', 'cannot invite a party member')
say(5, { t = 'accept', from = 1 })
local expiredBefore = count(5, 'invite_result')
say(5, { t = 'accept', from = 1 }); say(5, { t = 'accept', from = 1 })
check(count(5, 'invite_result') <= expiredBefore + 1, "'expired' is not spammed")

-- ---------------------------------------------------------------- admin
say(4, { t = 'admin', cmd = 'kick', name = 'Alice (2)' })
check(last(4, 'notice') and last(4, 'notice').text:find('Only the host'), 'only the host can use admin commands')
say(1, { t = 'admin', cmd = 'mute', name = 'Alice' })
say(4, { t = 'chat', scope = 'global', text = 'muted talk' })
check(not last(1, 'chat') or last(1, 'chat').text ~= 'muted talk', 'muted players cannot chat')
say(1, { t = 'admin', cmd = 'unmute', name = 'Alice' })
tick(2)
say(4, { t = 'chat', scope = 'emote', text = 'waves' })
check(last(1, 'chat') and last(1, 'chat').scope == 'emote', 'emotes are shared')
say(1, { t = 'admin', cmd = 'ban', name = 'Alice (2)' })
check(last(5, 'kicked') and last(5, 'kicked').reason == 'banned', 'ban kicks')
tick(1); check(kicked[5], 'banned socket is closed')
say(1, { t = 'admin', cmd = 'announce', text = 'server restart soon' })
check(last(4, 'notice').text == '[Host] server restart soon', 'announce reaches everybody')
join(7, 'Alice (2)', 'secret')
check(last(7, 'join_result').reason == 'banned', 'banned name cannot rejoin')
say(1, { t = 'admin', cmd = 'unban', name = 'Alice (2)' })
tick(1)

-- ---------------------------------------------------------------- join timeout
relay.onClientConnected(8)
tick(11)
check(kicked[8], 'a client that never joins is dropped')

-- ---------------------------------------------------------------- players list and pings
tick(3)
local list = last(1, 'players')
check(list and #list.list >= 2, 'player list is broadcast')

-- ---------------------------------------------------------------- interest: who sees whom
-- 1 and 4 stand together outside, 9 is in an interior: 9 gets 4's position only now and then, and none of 4's shots
join(9, 'Dee', 'secret')
say(1, { t = 'state', ts = 1, cell = '', pos = { 0, 0, 0 } })
say(9, { t = 'state', ts = 1, cell = 'Balmora, Guild of Mages', pos = { 0, 0, 0 } })
local statesTo1, statesTo9 = count(1, 'state'), count(9, 'state')
for i = 1, 10 do say(4, { t = 'state', ts = 1 + i / 30, cell = '', pos = { 100 + i, 0, 0 } }) end
check(count(1, 'state') == statesTo1 + 10, 'a player nearby gets every position')
check(count(9, 'state') == statesTo9 + 1, 'a player elsewhere gets a position only once a second')
tick(1.1)
say(4, { t = 'state', ts = 3, cell = '', pos = { 120, 0, 0 } })
check(count(9, 'state') == statesTo9 + 2, '... and again a second later')
say(4, { t = 'anim', g = 'weapononehand', o = { s = 'chop start', e = 'chop max attack' }, ts = 3 })
check(last(1, 'anim') and last(1, 'anim').id == 4 and not last(9, 'anim'), 'animations go to those who can see')
say(4, { t = 'look', fx = { { e = 'fireshield', m = 10 } } })
check(last(9, 'look') and last(1, 'look'), 'how a player looks goes to everyone (kept for when they come near)')
say(4, { t = 'ev', k = 'dmg', n = 12 })
check(last(1, 'ev') and last(1, 'ev').n == 12 and not last(9, 'ev'), 'damage numbers go to those who can see')
-- far away outside: as good as elsewhere
say(1, { t = 'state', ts = 2, cell = '', pos = { 90000, 0, 0 } })
local anims = count(1, 'anim')
say(4, { t = 'anim', g = 'spellcast', o = { s = 'target start', e = 'target stop' }, ts = 4 })
check(count(1, 'anim') == anims, 'nothing but positions for a player far away outside')
say(1, { t = 'state', ts = 3, cell = '', pos = { 0, 0, 0 } })

-- ---------------------------------------------------------------- shots
local shotsBefore = count(1, 'shot')
say(4, { t = 'shot', k = 'arrow', rec = 'iron arrow', from = { 0, 0, 0 }, to = { 100, 0, 0 }, spd = 2000 })
check(count(1, 'shot') == shotsBefore + 1 and last(1, 'shot').id == 4, 'a shot is shown to the others, stamped with its shooter')
check(count(4, 'shot') == 0, 'a shot is not echoed to its shooter')
say(4, { t = 'shot', k = 12345 })
check(count(1, 'shot') == shotsBefore + 1, 'a malformed shot is dropped')
join(8, 'Dave', 'secret')
check(not last(8, 'shot'), 'shots are not replayed to late joiners')

-- helpful spells only travel inside a party
local aidBefore = count(1, 'aid')
say(4, { t = 'aid', to = 1, spell = 'restore health' })
check(count(1, 'aid') == aidBefore, 'a spell aimed at someone outside the party goes nowhere')

-- ---------------------------------------------------------------- silence and flooding
for i = 1, 600 do say(4, { t = 'state', cell = '', pos = { i, 0, 0 } }) end
check(last(1, 'state') and last(1, 'state').pos[1] <= 400, 'a flood of messages is cut off')
tick(30)
check(kicked[4], 'a player that goes silent is dropped')

-- ---------------------------------------------------------------- reconnect, rate limits, validation, trades
sent, kicked = {}, {}
check(relay.start(25566, { password = '', maxPlayers = 8, motd = '' }) == nil, 'restart')
join(11, 'Host'); join(12, 'Alice'); join(13, 'Bob'); join(14, 'Carol')
local token = last(12, 'join_result').token
check(type(token) == 'string' and #token > 10, 'a joining player gets a token')
say(12, { t = 'invite', to = 13 }); tick(1.1); say(13, { t = 'accept', from = 12 })
check(last(12, 'party') and #last(12, 'party').members == 2, 'party formed')

relay.onClientDisconnected(12) -- Alice loses her connection
check(count(13, 'away') == 1 and count(13, 'leave') == 0, 'others hear that she is away, not that she left')
tick(2.1)
local away = false
for _, e in ipairs(last(13, 'players').list) do if e.id == 12 and e.away then away = true end end
check(away, 'the player list marks her as away')

say(12, { t = 'chat', scope = 'global', text = 'ghost' })
check(not last(13, 'chat'), 'a closed socket cannot talk any more')

join(15, 'Alice', '', 3) -- no token: a different player, the name is reserved
check(last(15, 'join_result').name == 'Alice (2)' and not last(15, 'join_result').resumed, 'without the token the name is taken')
relay.onClientDisconnected(15)

relay.onClientConnected(16)
say(16, { t = 'join', proto = 3, name = 'Alice', token = token })
local jr = last(16, 'join_result')
check(jr and jr.ok and jr.resumed and jr.id == 12 and jr.name == 'Alice', 'the token gives her old id and name back')
check(count(13, 'back') == 1, 'others hear that she is back')
check(last(16, 'party') and #last(16, 'party').members == 2, 'her party is intact')
say(16, { t = 'chat', scope = 'party', text = 'hello again' })
check(last(13, 'chat') and last(13, 'chat').id == 12, 'she can talk with the new connection')

relay.onClientConnected(17) -- a second connection with the same token takes over, the old one is cut
say(17, { t = 'join', proto = 3, name = 'Alice', token = token })
check(kicked[16], 'taking the place over closes the old socket')
say(16, { t = 'chat', scope = 'global', text = 'old socket' })
check(count(13, 'chat') == 1, 'the old socket is ignored')

relay.onClientDisconnected(13) -- Bob drops and does not come back
for _ = 1, 10 do tick(10); for _, sock in ipairs({ 11, 14, 17 }) do say(sock, { t = 'pong', n = 0 }) end end
local function leaves(sock, who) local n = 0 for _, m in ipairs(sent[sock] or {}) do if m.t == 'leave' and m.id == who then n = n + 1 end end return n end
check(leaves(17, 13) == 1, 'after the grace period the place is given up')
check(last(17, 'party') and #last(17, 'party').members == 0, 'and the party is gone')

local n0 = count(11, 'shot')
for i = 1, 100 do say(17, { t = 'shot', k = 'arrow', from = { 0, 0, 0 }, to = { 1, 0, 0 } }) end
check(count(11, 'shot') - n0 <= 8, 'shots are rate limited')
tick(1)
local st = count(11, 'state')
say(17, { t = 'state', pos = { 'x', 0, 0 } })
say(17, { t = 'state', pos = { 1e12, 0, 0 } })
check(count(11, 'state') == st, 'a position that is not a position is dropped')
local big = {}
for i = 1, 100 do big[i] = { n = i } end
tick(1)
local ac = count(11, 'actors')
say(17, { t = 'actors', list = big })
check(count(11, 'actors') == ac, 'an oversized actor list is dropped')
say(17, { t = 'dmg', to = 11, nid = 'x', frac = 1e9 })
check(count(11, 'dmg') == 0, 'absurd damage is dropped')

-- trades: only between two players who agreed
join(18, 'Dora')
say(17, { t = 'trade_deal', to = 18, rev = 1, a = { sword = 1 }, b = {} })
check(count(18, 'trade_deal') == 0, 'a deal without a request goes nowhere')
say(17, { t = 'trade_request', to = 18 })
check(count(18, 'trade_request') == 1 and last(18, 'trade_request').id == 12, 'a request is delivered')
say(17, { t = 'trade_deal', to = 18, rev = 1, a = {}, b = {} })
check(count(18, 'trade_deal') == 0, 'no deal before the request is accepted')
say(18, { t = 'trade_accept', to = 12 })
check(count(17, 'trade_accept') == 1, 'the answer is delivered')
say(17, { t = 'trade_deal', to = 18, rev = 1, a = { sword = 1 }, b = {} })
check(count(18, 'trade_deal') == 1, 'a deal passes once accepted')
join(19, 'Eve')
say(19, { t = 'trade_deal', to = 18, rev = 2, a = { sword = 100 }, b = {} })
check(count(18, 'trade_deal') == 1, 'an outsider cannot inject into a trade')
say(17, { t = 'trade_deal', to = 18, rev = 3, a = { sword = -5 }, b = {} })
check(count(18, 'trade_deal') == 1, 'a negative count is dropped')
relay.onClientDisconnected(18) -- Dora loses her connection mid-trade
check(count(17, 'trade_cancel') == 1, 'a dropped partner cancels the trade')

-- ---------------------------------------------------------------- dedicated server
sent, kicked = {}, {}
check(relay.start(25567, { dedicated = true, password = '', adminPassword = 'sesame', weather = 'cycle', startHour = 6,
    timeScale = 60, weatherMinutes = 1 }) == nil, 'start dedicated')
check(relay.isDedicated(), 'it is dedicated')
join(31, 'Ann'); join(32, 'Ben')
check(not last(31, 'hello').host and not last(32, 'hello').host, 'nobody is the host')
local w = last(31, 'world')
check(w and w.time == 6 * 3600 and w.weather, 'a joining player gets the server time and weather')
say(31, { t = 'world', time = 12345, weather = 'thunder' })
tick(2.1)
check(last(32, 'world').time ~= 12345 and last(32, 'world').weather ~= 'thunder', 'a player cannot set the sky')
tick(10)
check(math.abs(last(32, 'world').time - (6 * 3600 + 12.1 * 60)) < 200, 'time of day advances by the server clock')

say(31, { t = 'admin', cmd = 'weather', text = 'rain' })
check(last(31, 'notice').text:find('Only admins'), 'players are not admins')
say(31, { t = 'admin', cmd = 'login', password = 'wrong' })
check(last(31, 'notice').text == 'Wrong password.', 'wrong admin password')
for _ = 1, 5 do say(31, { t = 'admin', cmd = 'login', password = 'wrong' }) end
say(31, { t = 'admin', cmd = 'login', password = 'sesame' })
check(last(31, 'notice').text == 'Too many wrong passwords.', 'guessing the admin password is limited')
say(32, { t = 'admin', cmd = 'login', password = 'sesame' })
check(last(32, 'notice').text == 'You are now an admin.', 'the right password makes an admin')
say(32, { t = 'admin', cmd = 'weather', text = 'rain' })
check(last(31, 'world').weather == 'rain', 'an admin sets the weather for everybody')
say(32, { t = 'admin', cmd = 'time', text = '23.5' })
check(last(31, 'world').time == 23.5 * 3600, 'an admin sets the time')
say(32, { t = 'admin', cmd = 'time', text = '99' })
check(last(32, 'notice').text:find('Usage'), 'a bad hour is refused')
tick(1.1)
say(32, { t = 'admin', cmd = 'weather', text = 'rain; drop' })
check(last(32, 'notice').text == 'Not a weather id.', 'a weather id must be a plain word')

say(31, { t = 'state', cell = 'Vivec, Temple', pos = { 1, 2, 3 } })
do
    local snap = relay.snapshot()
    local ann
    for _, p in ipairs(snap.players) do if p.name == 'Ann' then ann = p end end
    check(ann and ann.location:find('Vivec, Temple') and ann.ping == -1 and ann.ip == '' and not ann.admin, 'snapshot: a player with location')
    local ben
    for _, p in ipairs(snap.players) do if p.name == 'Ben' then ben = p end end
    check(ben and ben.admin, 'snapshot: admin flag')
    check(snap.dedicated and snap.maxPlayers == 64 and snap.weather == 'rain' and snap.weatherFixed and math.abs(snap.hour - 23.5) < 0.5 and snap.passworded == false,
        'snapshot: sky and limits')
end
local who = relay.console('players')
check(who:find('Ann') and who:find('Vivec, Temple') and who:find('at 1, 2, 3') and who:find('%[admin%]'), 'the console lists players with their location')
check(relay.console('weather'):find('rain'), 'console: weather')
check(relay.console('kick Ann'):find('was kicked'), 'console: kick')
check(relay.console('bogus'):find('Unknown command'), 'console: unknown command')
check(relay.console('weather cycle'):find('by itself'), 'console: weather cycles again')

-- ---------------------------------------------------------------- shared loot and other worlds' actors
sent, kicked = {}, {}
check(relay.start(25568, { password = '', maxPlayers = 8 }) == nil, 'restart for loot')
join(51, 'Host'); join(52, 'Ann'); join(53, 'Ben'); join(54, 'Cy'); join(55, 'Dee')
say(52, { t = 'invite', to = 53 }); tick(1.1); say(53, { t = 'accept', from = 52 })
say(52, { t = 'loot', cid = 'Morrowind.esm:77', items = { gold_001 = 40 } })
check(last(53, 'loot') and last(53, 'loot').id == 52 and last(53, 'loot').items.gold_001 == 40, 'loot goes to the party')
check(not last(54, 'loot') and not last(51, 'loot') and not last(52, 'loot'), 'but to nobody else, not even the sender')
local lootBefore = count(53, 'loot')
local many = {}
for i = 1, 31 do many['item' .. i] = 1 end
say(52, { t = 'loot', cid = 'c', items = many })
say(52, { t = 'loot', cid = 'c', items = { gold_001 = -1 } })
say(52, { t = 'loot', cid = 'c', items = { gold_001 = 1e9 } })
say(52, { t = 'loot', cid = string.rep('x', 101), items = { gold_001 = 1 } })
say(52, { t = 'loot', cid = 'c', items = 'gold' })
check(count(53, 'loot') == lootBefore, 'too many kinds, negative or huge counts, a long id and a non-table are dropped')
for _ = 1, 50 do say(52, { t = 'loot', cid = 'c', items = { gold_001 = 1 } }) end
check(count(53, 'loot') - lootBefore <= 10, 'loot is rate limited')

tick(1.1)
for _, id in ipairs({ 52, 53, 54 }) do say(id, { t = 'state', cell = 'Balmora', pos = { 1, 2, 3 } }) end
say(55, { t = 'state', cell = 'Vivec', pos = { 1, 2, 3 } })
local entry = { n = 'Morrowind.esm:5', r = 'ordinator', h = 1, p = { 1, 2, 3 }, y = 0 }
say(52, { t = 'pubactors', cell = 'Balmora', list = { entry } })
check(last(54, 'pubactors') and last(54, 'pubactors').id == 52 and #last(54, 'pubactors').list == 1, 'someone outside the party in the same cell hears it')
check(not last(53, 'pubactors'), 'a party member does not (they get the party roster)')
check(not last(55, 'pubactors'), 'somebody in another cell does not')
check(not last(52, 'pubactors') and not last(51, 'pubactors'), 'and not the sender, nor the host in another place')
tick(1.1)
local pubBefore = count(54, 'pubactors')
local big = {}
for i = 1, 41 do big[i] = entry end
say(52, { t = 'pubactors', cell = 'Balmora', list = big })
check(count(54, 'pubactors') == pubBefore, 'an oversized list is dropped')
for _ = 1, 20 do say(52, { t = 'pubactors', cell = 'Balmora', list = { entry } }) end
check(count(54, 'pubactors') - pubBefore <= 2, 'the public roster is rate limited')
join(56, 'Late')
check(not last(56, 'pubactors') and not last(56, 'loot'), 'neither is replayed to a late joiner')

-- ---------------------------------------------------------------- limits: 128 players, parties of 3
sent, kicked = {}, {}
check(relay.start(25569, { dedicated = true, maxPlayers = 500 }) == nil, 'start with a huge limit')
check(relay.snapshot().maxPlayers == 128, 'a server never takes more than 128 players')
check(relay.start(25570, { dedicated = true }) == nil and relay.snapshot().maxPlayers == 64, 'the default limit is 64')
check(relay.start(25571, { dedicated = true, maxPlayers = 0 }) == nil and relay.snapshot().maxPlayers == 1, 'a limit of 0 becomes 1')
sent, kicked = {}, {}
check(relay.start(25572, { maxPlayers = 8 }) == nil, 'start for parties')
join(61, 'Host'); join(62, 'Ann'); join(63, 'Ben'); join(64, 'Cy'); join(65, 'Dee')
say(62, { t = 'invite', to = 63 }); tick(1.1); say(63, { t = 'accept', from = 62 }); tick(1.1)
say(62, { t = 'invite', to = 64 }); tick(1.1); say(64, { t = 'accept', from = 62 }); tick(1.1)
check(last(62, 'party') and #last(62, 'party').members == 3, 'a party of three forms')
say(62, { t = 'invite', to = 65 })
check(last(62, 'invite_result') and last(62, 'invite_result').reason == 'full', 'a fourth cannot be invited: parties hold three')
tick(1.1)
say(63, { t = 'invite', to = 65 })
check(last(63, 'invite_result') and last(63, 'invite_result').reason == 'full', 'nor by another member')

if failures == 0 then print('relay tests: all passed') else print(failures .. ' failed') os.exit(1) end
