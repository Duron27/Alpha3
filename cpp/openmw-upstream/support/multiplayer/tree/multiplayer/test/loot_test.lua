-- Runs the shared-loot logic without the game:  luajit multiplayer/test/loot_test.lua
-- The engine modules are replaced by small fakes.
local records = {} -- recordId -> record (what types.X.record returns)
local function recordFn(id) return records[id] end
local function kind(name) return { name = name, record = function(item) return records[item.recordId] end } end

local types = {
    Miscellaneous = kind('Miscellaneous'), Ingredient = kind('Ingredient'), Potion = kind('Potion'),
    Lockpick = kind('Lockpick'), Probe = kind('Probe'), Repair = kind('Repair'), Weapon = kind('Weapon'),
    Armor = kind('Armor'), Clothing = kind('Clothing'), Book = kind('Book'),
    Container = kind('Container'), NPC = kind('NPC'), Creature = kind('Creature'),
}
types.Weapon.TYPE = { Arrow = 12, Bolt = 13, MarksmanThrown = 11, LongBladeOneHand = 1 }
local inventories = {} -- object id -> inventory
types.Container.inventory = function(obj) return inventories[obj.id] end
types.Actor = { inventory = function(obj) return inventories[obj.id] end, isDead = function(a) return a.dead end }
package.loaded['openmw.types'] = types

local nextId = 0
local function newItem(type_, recordId, count, rec)
    records[recordId] = rec or {}
    nextId = nextId + 1
    return { id = 'item' .. nextId, type = type_, recordId = recordId, count = count or 1 }
end

local function newInventory(items, resolved)
    local inv = { items = items, resolved = resolved ~= false }
    function inv:isResolved() return self.resolved end
    function inv:resolve() self.resolved = true end
    function inv:getAll() local out = {} for _, i in ipairs(self.items) do out[#out + 1] = i end return out end
    function inv:findAll(id) local out = {} for _, i in ipairs(self.items) do if i.recordId == id then out[#out + 1] = i end end return out end
    for _, item in ipairs(items) do
        function item:remove(n)
            self.count = self.count - (n or self.count)
            if self.count <= 0 then for i, x in ipairs(inv.items) do if x == self then table.remove(inv.items, i) break end end end
        end
    end
    return inv
end

local vec = {}
vec.__index = vec
vec.__sub = function(a, b) return setmetatable({ x = a.x - b.x, y = a.y - b.y, z = a.z - b.z }, vec) end
function vec:length() return math.sqrt(self.x * self.x + self.y * self.y + self.z * self.z) end
local function v(x, y, z) return setmetatable({ x = x, y = y, z = z }, vec) end

local function newContainer(id, items, pos)
    local obj = { id = id, type = types.Container, position = pos or v(0, 0, 0), contentFile = 'Morrowind.esm' }
    function obj:isValid() return true end
    inventories[id] = newInventory(items)
    return obj
end

package.path = 'multiplayer/mod/?.lua;' .. package.path
local loot = require('scripts/mp/loot')

local failures = 0
local function check(cond, what) if not cond then failures = failures + 1 print('FAIL: ' .. what) end end

-- ---------------------------------------------------------------- what is shareable
local gold = newItem(types.Miscellaneous, 'gold_001', 100)
check(loot.shareable(gold, false), 'gold is shareable')
check(not loot.shareable(newItem(types.Miscellaneous, 'key_ald_ruhn', 1, {}), false), 'a key is not')
check(not loot.shareable(newItem(types.Miscellaneous, 'misc_dwrv_artifact60', 1, {}), false), 'other miscellaneous items are not')
check(loot.shareable(newItem(types.Ingredient, 'ingred_bread_01', 3), false), 'an ingredient is')
check(loot.shareable(newItem(types.Potion, 'p_restore_health_c', 2), false), 'a potion is')
check(not loot.shareable(newItem(types.Potion, 'generated:77', 1), false), 'a potion made by a player is not')
check(not loot.shareable(newItem(types.Potion, 'p_quest', 1, { mwscript = 'questScript' }), false), 'anything with a script is not')
check(loot.shareable(newItem(types.Lockpick, 'pick_apprentice_01', 1), false), 'a lockpick is')
check(loot.shareable(newItem(types.Probe, 'probe_apprentice_01', 1), false), 'a probe is')
check(loot.shareable(newItem(types.Repair, 'hammer_repair', 1), false), 'a repair tool is')
check(loot.shareable(newItem(types.Weapon, 'iron arrow', 30, { type = 12 }), false), 'an arrow is')
check(loot.shareable(newItem(types.Weapon, 'steel bolt', 30, { type = 13 }), false), 'a bolt is')
check(loot.shareable(newItem(types.Weapon, 'iron dart', 10, { type = 11 }), false), 'a thrown weapon is')
check(not loot.shareable(newItem(types.Weapon, 'enchanted arrow', 5, { type = 12, enchant = 'ench_fire' }), false), 'an enchanted arrow is not')
local sword = newItem(types.Weapon, 'iron longsword', 1, { type = 1 })
check(not loot.shareable(sword, false), 'a sword is not, by default')
check(loot.shareable(sword, true), 'a plain sword is, when weapons and armor are shared too')
check(not loot.shareable(newItem(types.Weapon, 'fire sword', 1, { type = 1, enchant = 'ench_fire' }), true), 'an enchanted sword never is')
check(not loot.shareable(newItem(types.Weapon, 'sword_with_script', 1, { type = 1, mwscript = 'x' }), true), 'a scripted sword never is')
check(not loot.shareable(newItem(types.Armor, 'iron cuirass', 1, {}), false) and loot.shareable(newItem(types.Armor, 'iron cuirass2', 1, {}), true), 'armor only when asked')
check(not loot.shareable(newItem(types.Book, 'bk_quest_note', 1, {}), true), 'a book never is')
check(not loot.shareable({ recordId = 'x', type = {} }, true), 'something odd is not (and does not crash)')

-- ---------------------------------------------------------------- snapshots and what was taken
local chest = newContainer('chest1', { newItem(types.Miscellaneous, 'gold_001', 100), newItem(types.Potion, 'p_restore_health_c', 2),
    newItem(types.Miscellaneous, 'key_x', 1, {}), newItem(types.Weapon, 'iron longsword', 1, { type = 1 }) })
local snap = loot.snapshot(chest, false)
check(snap and snap.gold_001 == 100 and snap.p_restore_health_c == 2 and not snap.key_x and not snap.iron_longsword, 'a snapshot has only shareable items')
check(loot.snapshot(chest, true)['iron longsword'] == 1, 'with weapons shared, a sword is in it')
inventories.chest1.resolved = false
check(loot.snapshot(chest, false) == nil, 'a container that is not filled in yet has no snapshot')
inventories.chest1.resolved = true
local gone, any = loot.removed({ gold_001 = 100, p = 2 }, { gold_001 = 60, p = 2, extra = 5 })
check(any and gone.gold_001 == 40 and gone.p == nil and gone.extra == nil, 'only what went missing counts')
check(select(2, loot.removed({ a = 1 }, { a = 1 })) == false, 'nothing missing, nothing sent')

-- ---------------------------------------------------------------- the whole flow
local sent, notices = {}, {}
local open = false
local settings = { enabled = true, gear = false, active = true }
local byCid = {}
local player = { position = v(0, 0, 0) }
player.cell = { getAll = function(_, t)
    if t == types.Container then return { chest } end
    return {}
end }
loot.init({
    send = function(m) sent[#sent + 1] = m end, dlog = function() end, notice = function(t) notices[#notices + 1] = t end,
    player = function() return player end,
    objectById = function(cid) return byCid[cid] end,
    contentId = function(obj) return obj.contentFile and ('Morrowind.esm:' .. obj.id) or nil end,
    isAvatar = function() return false end,
    enabled = function() return settings.enabled end, gear = function() return settings.gear end,
    active = function() return settings.active end, containerOpen = function() return open end,
})

loot.onUpdate(0.1) -- window closed: nothing watched
open = true
loot.onUpdate(0.01) -- the window opens: what is in the chest is remembered
-- the player takes 40 gold and a potion
for _, i in ipairs(inventories.chest1.items) do
    if i.recordId == 'gold_001' then i:remove(40) end
    if i.recordId == 'p_restore_health_c' then i:remove(1) end
end
loot.onUpdate(0.3)
check(#sent == 1 and sent[1].t == 'loot' and sent[1].cid == 'Morrowind.esm:chest1', 'taking loot is reported for that container')
check(sent[1] and sent[1].items.gold_001 == 40 and sent[1].items.p_restore_health_c == 1, 'with what was taken')
loot.onUpdate(0.3)
check(#sent == 1, 'it is reported once')
-- the player drops the potion back in and takes it again
table.insert(inventories.chest1.items, 1, newItem(types.Potion, 'p_restore_health_c', 1))
loot.onUpdate(0.3)
check(#sent == 1, 'putting something in sends nothing')
open = false
loot.onUpdate(0.3); loot.onUpdate(2); loot.onUpdate(0.3)
check(#sent == 1, 'closing the window sends nothing more')

-- ---------------------------------------------------------------- what the others took
local other = newContainer('chest2', { newItem(types.Miscellaneous, 'gold_001', 50), newItem(types.Ingredient, 'ingred_bread_01', 4),
    newItem(types.Miscellaneous, 'key_x', 1, {}), newItem(types.Weapon, 'iron longsword', 1, { type = 1 }) })
byCid['Morrowind.esm:chest2'] = other
loot.onMessage({ t = 'loot', cid = 'Morrowind.esm:chest2', items = { gold_001 = 30, ingred_bread_01 = 9, key_x = 1, ['iron longsword'] = 1, nothing = 3 } })
check(#notices == 1, 'the player is told once what this does')
loot.onUpdate(1.1)
local left = {}
for _, i in ipairs(inventories.chest2.items) do left[i.recordId] = i.count end
check(left.gold_001 == 20, 'gold the group took is removed here')
check(left.ingred_bread_01 == nil, 'removal stops at what is there')
check(left.key_x == 1, 'a key is never removed, whatever the sender claims')
check(left['iron longsword'] == 1, 'a sword is not removed when weapons are not shared')
check(next(loot.getPending()) == nil, 'nothing is left waiting')

loot.onMessage({ t = 'loot', cid = 'Morrowind.esm:later', items = { gold_001 = 5 } })
check(loot.getPending()['Morrowind.esm:later'].gold_001 == 5, 'a removal for a container that is not loaded waits')
loot.setPending(loot.getPending())
check(loot.getPending()['Morrowind.esm:later'].gold_001 == 5, 'and survives saving and loading')
loot.onMessage({ t = 'loot', cid = 'x', items = { gold_001 = -5 } })
loot.onMessage({ t = 'loot', cid = 'y', items = { gold_001 = 0 } })
loot.onMessage({ t = 'loot', cid = string.rep('z', 200), items = { gold_001 = 1 } })
loot.onMessage({ t = 'loot', cid = 'w', items = 'nope' })
check(loot.getPending().x == nil and loot.getPending().y == nil and loot.getPending().w == nil and loot.getPending()[string.rep('z', 200)] == nil,
    'nonsense counts and ids are dropped')

settings.enabled = false
local before = 0
for _ in pairs(loot.getPending()) do before = before + 1 end
loot.onMessage({ t = 'loot', cid = 'off', items = { gold_001 = 1 } })
local after = 0
for _ in pairs(loot.getPending()) do after = after + 1 end
check(before == after, 'with sharing switched off nothing is accepted')

-- weapons and armor shared: the same message now takes the sword
settings.enabled, settings.gear = true, true
loot.onMessage({ t = 'loot', cid = 'Morrowind.esm:chest2', items = { ['iron longsword'] = 1 } })
loot.onUpdate(1.1)
local swords = 0
for _, i in ipairs(inventories.chest2.items) do if i.recordId == 'iron longsword' then swords = swords + i.count end end
check(swords == 0, 'a plain sword is removed when weapons and armor are shared')

-- while the receiver looks at a container nothing is changed under them
open = true
loot.onMessage({ t = 'loot', cid = 'Morrowind.esm:chest2', items = { gold_001 = 5 } })
loot.onUpdate(1.1)
local goldNow = 0
for _, i in ipairs(inventories.chest2.items) do if i.recordId == 'gold_001' then goldNow = goldNow + i.count end end
check(goldNow == 20, 'nothing changes while a container window is open')
open = false
loot.onUpdate(0.01)
loot.onUpdate(1.1)
goldNow = 0
for _, i in ipairs(inventories.chest2.items) do if i.recordId == 'gold_001' then goldNow = goldNow + i.count end end
check(goldNow == 15, 'it is applied once the window is closed')

if failures == 0 then print('loot tests: all passed') else print(failures .. ' failed') os.exit(1) end
