-- Runs the stand-in logic (proxies.lua) without the game:  luajit multiplayer/test/standins_test.lua
-- Covers the stand-ins for characters that are alive in other players' worlds (players outside the group).
local vec = {}
vec.__index = vec
vec.__sub = function(a, b) return setmetatable({ x = a.x - b.x, y = a.y - b.y, z = a.z - b.z }, vec) end
function vec:length() return math.sqrt(self.x * self.x + self.y * self.y + self.z * self.z) end
local function v(x, y, z) return setmetatable({ x = x or 0, y = y or 0, z = z or 0 }, vec) end

local actorsByIndex, activeActors = {}, {}
local created, removed, records = {}, {}, {}
local crimeSet = {}

local types = { NPC = {}, Creature = {}, Actor = {}, Player = {} }
types.NPC.records = setmetatable({}, { __index = function(_, id) return { name = 'Person ' .. id } end })
types.Creature.records = {}
types.NPC.createRecordDraft = function(fields) return fields end
types.Creature.createRecordDraft = function(fields) return fields end
types.Actor.isDead = function(a) return a.dead end
types.Actor.stats = { dynamic = { health = function() return { current = 100, base = 100, modifier = 0 } end } }
types.Player.getCrimeLevel = function() return 0 end
types.Player.setCrimeLevel = function(_, level) crimeSet[#crimeSet + 1] = level end

local util = { vector3 = v, transform = { rotateZ = function(a) return a end } }
local core = { getFormId = function(file, index) return index end }
local world = {
    activeActors = activeActors,
    getObjectByFormId = function(i) return actorsByIndex[i] end,
    createRecord = function(draft) local n = #records + 1 draft.id = 'standin_record_' .. n records[n] = draft return draft end,
    createObject = function(recordId)
        local obj = { id = 'new' .. (#created + 1), recordId = recordId, enabled = true, events = {}, scripts = {} }
        function obj:isValid() return not self.removed end
        function obj:addScript(name, data) self.scripts[name] = data or true end
        function obj:teleport() end
        function obj:sendEvent(name, data) self.events[#self.events + 1] = { name = name, data = data } end
        function obj:remove() self.removed = true removed[#removed + 1] = self end
        created[#created + 1] = obj
        return obj
    end,
}
package.loaded['openmw.core'], package.loaded['openmw.world'], package.loaded['openmw.types'], package.loaded['openmw.util'] =
    core, world, types, util

package.path = 'multiplayer/mod/?.lua;' .. package.path
local proxies = require('scripts/mp/proxies')

local function actor(index, dead, pos)
    local a = { id = tostring(0x1000000 + index), contentFile = 'Morrowind.esm', type = types.NPC, recordId = 'ordinator', dead = dead,
        enabled = true, position = pos or v(index * 10, 0, 0), rotation = { getYaw = function() return 0 end } }
    function a:isValid() return true end
    actorsByIndex[index] = a
    activeActors[#activeActors + 1] = a
    return a
end

local sent = {}
local settings = { standins = true, connected = true }
local members = {}
local player = { position = v(0, 0, 0), cell = { isExterior = true } }
proxies.init({
    send = function(m) sent[#sent + 1] = m end, log = function() end, dlog = function() end,
    blockActivation = function() end, player = function() return player end, cellKey = function() return '' end,
    myId = function() return 1 end, active = function() return false end, isAvatar = function() return false end,
    ownerName = function(id) return id == 7 and 'Bob' or 'Cy' end, sharedKills = function() return true end,
    sharedFights = function() return true end, strangerStandins = function() return settings.standins end,
    connected = function() return settings.connected end, isMember = function(id) return members[id] == true end,
})

local failures = 0
local function check(cond, what) if not cond then failures = failures + 1 print('FAIL: ' .. what) end end
local function roster(owner, list, cell) proxies.onMessage({ t = 'pubactors', id = owner, cell = cell or '', list = list }) end
local function entry(index, extra)
    local e = { n = 'Morrowind.esm:' .. index, r = 'ordinator', h = 0.5, p = { index * 10, 0, 0 }, y = 0 }
    for k, val in pairs(extra or {}) do e[k] = val end
    return e
end
local function run(seconds, step)
    step = step or 1
    for _ = 1, math.floor(seconds / step) do proxies.onUpdate(step) end
end
local function publicRosters() local n = 0 for _, m in ipairs(sent) do if m.t == 'pubactors' then n = n + 1 end end return n end

-- ---------------------------------------------------------------- the delay
local killed = actor(1, true)
run(4)
roster(7, { entry(1) })
check(#created == 0, 'an actor that has only just been gone gets no stand-in')
run(200)
roster(7, { entry(1) })
check(#created == 0, 'nor after three minutes')
run(110)
roster(7, { entry(1) })
check(#created == 1, 'after five minutes another world\'s copy appears')
local px = created[1]
check(px.scripts['scripts/mp/remote.lua'] and px.scripts['scripts/mp/remote.lua'].proxy, 'it is a stand-in (proxy script)')
check(records[1] and records[1].name:find("Bob's world", 1, true), 'labelled with whose world it comes from')
roster(7, { entry(1) })
check(#created == 1, 'one stand-in per actor')
local moved = px.events[#px.events]
check(moved and moved.name == 'MpTarget' and moved.data.hp == nil, 'it follows its owner\'s copy but its health is ours alone')

-- ---------------------------------------------------------------- group members and bad input
members[8] = true
local second = actor(2, true)
for _ = 1, 62 do -- (their world keeps listing the first one, so its stand-in stays)
    roster(7, { entry(1) })
    run(5)
end
roster(8, { entry(2) })
check(#created == 1, 'a group member\'s public roster is ignored (they use the party roster)')
members[8] = nil
roster(7, { { n = 'Morrowind.esm:2' } })
roster(7, { entry(2, { p = { 'x', 0, 0 } }) })
roster(7, { entry(2, { n = string.rep('a', 200) }) })
roster(7, { entry(2, { y = 'north' }) })
roster(7, { 'junk', 5, true })
roster(7, 'nope')
check(#created == 1, 'malformed entries are ignored')
roster(7, { entry(2) }, 'Vivec, Temple')
check(#created == 1, 'a roster from another cell is ignored')
settings.standins = false
roster(7, { entry(2) })
check(#created == 1, 'switched off: nothing appears')
settings.standins = true

-- ---------------------------------------------------------------- what happens to a stand-in stays here
sent = {}
proxies.onProxyDamage({ key = 'Morrowind.esm:1', frac = 0.4 })
check(#sent == 0, 'hitting it tells the other world nothing')
proxies.onProxyDied({ key = 'Morrowind.esm:1' })
check(#sent == 0, 'killing it tells the other world nothing')
roster(7, { entry(1) })
check(#created == 1, 'a stand-in we killed does not come back')
run(65, 5)
check(px.removed, 'its corpse is cleaned up after a minute')

-- ---------------------------------------------------------------- it goes when its owner stops listing it
roster(7, { entry(2) })
check(#created == 2, 'another one appears for the second actor')
local third = created[2]
run(12)
check(third.removed, 'a stand-in nobody lists any more goes away')

-- ---------------------------------------------------------------- the cap
for i = 10, 20 do actor(i, true) end
run(310)
local list = {}
for i = 10, 20 do list[#list + 1] = entry(i) end
roster(7, list)
local alive = 0
for _, o in ipairs(created) do if not o.removed then alive = alive + 1 end end
check(alive == 8, 'at most eight stand-ins from other worlds at once (' .. alive .. ')')

-- ---------------------------------------------------------------- what we tell the others
sent = {}
local living = actor(30, false, v(500, 0, 0))
local near = actor(31, false, v(50, 0, 0))
run(1.2)
local pub
for _, m in ipairs(sent) do if m.t == 'pubactors' then pub = m end end
check(pub and pub.cell == '', 'the public roster is sent about once a second')
check(pub and #pub.list == 2 and pub.list[1].n == 'Morrowind.esm:31' and pub.list[2].n == 'Morrowind.esm:30', 'with the living characters around us, nearest first (not the dead)')
local before = publicRosters()
settings.connected = false
run(3)
check(publicRosters() == before, 'nothing is sent when we are not connected')
settings.connected, settings.standins = true, false
run(3)
check(publicRosters() == before, 'nor when the setting is off')

if failures == 0 then print('stand-in tests: all passed') else print(failures .. ' failed') os.exit(1) end
