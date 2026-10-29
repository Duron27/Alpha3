-- Runs the generated-item logic without the game:  luajit multiplayer/test/items_test.lua
local json = dofile('multiplayer/test/jsonlite.lua')

local created = {}
local weaponType = {}
local enchantments = { ['generated:e1'] = { type = 1, cost = 12, charge = 300, isAutocalc = false,
    effects = { { id = 'firedamage', range = 1, area = 0, duration = 5, magnitudeMin = 10, magnitudeMax = 20 } } } }
local weaponRecords = { ['generated:w1'] = { id = 'generated:w1', name = 'Burning Sword', model = 'meshes/w/sword.nif',
    icon = 'icons/sword.dds', weight = 12.5, value = 800, type = 1, health = 500, speed = 1.1, reach = 1,
    enchantCapacity = 30, chopMinDamage = 2, chopMaxDamage = 20, slashMinDamage = 2, slashMaxDamage = 20,
    thrustMinDamage = 1, thrustMaxDamage = 15, enchant = 'generated:e1' } }
local contentSword = { id = 'iron longsword', model = 'meshes/w/sword.nif' }

package.loaded['openmw.core'] = { magic = {
    effects = { records = { firedamage = true } },
    enchantments = { records = enchantments, createRecordDraft = function(t) t.draftOf = 'ench' return t end },
} }
package.loaded['openmw.types'] = {
    Weapon = { type = weaponType, record = function(obj) return weaponRecords[obj.recordId] end,
        records = { contentSword }, createRecordDraft = function(t) t.draftOf = 'weapon' return t end },
    Armor = {}, Clothing = {}, Book = {}, Potion = {},
}
package.loaded['openmw.world'] = { createRecord = function(draft)
    local n = #created + 1
    draft.id = 'generated:new' .. n
    created[n] = draft
    return draft
end }
package.loaded['openmw.vfs'] = { fileExists = function(path) return path:find('missing') == nil end }
package.path = 'multiplayer/mod/?.lua;' .. package.path
local items = require('scripts/mp/items')

local failures = 0
local function check(cond, what) if not cond then failures = failures + 1 print('FAIL: ' .. what) end end

local desc = items.describe({ type = package.loaded['openmw.types'].Weapon, recordId = 'generated:w1' })
check(desc and desc.k == 'Weapon' and desc.f.name == 'Burning Sword' and desc.e and #desc.e.fx == 1, 'a made-up weapon is described with its enchantment')
local key = items.key(desc)
check(items.isKey(key), 'its key is recognisable')
local wire = json.decode(json.encode(desc))
check(items.key(wire) == key, 'the key survives the trip over the network')
check(items.key(desc) == key, 'the key is stable')

local other = json.decode(json.encode(desc))
other.f.value = 801
check(items.key(other) ~= key, 'a different item has a different key')

local id = items.rebuild(wire)
check(id == 'generated:new2' and #created == 2, 'rebuild creates the enchantment and the item')
check(created[2].enchant == 'generated:new1' and created[2].template == contentSword, 'the item uses the new enchantment and a template')
check(created[2].mwscript == '', 'a rebuilt item has no script')
check(items.rebuild(wire) == id and #created == 2, 'rebuilding twice makes one record')

local bad = json.decode(json.encode(desc)); bad.e.fx[1].id = 'nosucheffect'
local rid, why = items.rebuild(bad)
check(rid == nil and why, 'an unknown effect is refused')
local noModel = json.decode(json.encode(desc)); noModel.f.model = 'meshes/missing.nif'
check(items.rebuild(noModel) == nil, 'a missing model is refused')
local huge = json.decode(json.encode(desc)); huge.f.value = 1e12; huge.f.name = 'Huge'
local hid = items.rebuild(huge)
check(hid and created[#created].value == 1000000, 'absurd numbers are clamped')
check(items.rebuild({ k = 'Evil', f = {} }) == nil, 'an unknown kind is refused')

if failures == 0 then print('items tests: all passed') else print(failures .. ' failed') os.exit(1) end
