-- Playback of other players' positions without the game: luajit multiplayer/test/interp_test.lua
package.path = 'multiplayer/mod/?.lua;' .. package.path
local interp = require('scripts/mp/interp')

local failures = 0
local function check(cond, what) if not cond then failures = failures + 1 print('FAIL: ' .. what) end end
local function near(a, b, tol) return math.abs(a - b) <= (tol or 1) end

-- a player walking east at 200 units/s, messages every 1/30 s, their clock 500 s ahead of ours, 40 ms on the way with
-- some jitter: what we show must move evenly (no jumps backwards, speed close to theirs)
local buf = interp.new()
local speed, rate = 200, 1 / 30
local lastX, worst, minStep, maxStep = nil, 0, math.huge, 0
local seed = 1
local function rnd() seed = (seed * 1103515245 + 12345) % 2147483648; return seed / 2147483648 end
local arrivals = {}
for i = 0, 300 do
    local ts = 500 + i * rate
    arrivals[#arrivals + 1] = { at = ts - 500 + 0.04 + rnd() * 0.03, ts = ts, x = speed * i * rate }
end
table.sort(arrivals, function(a, b) return a.at < b.at end)
local frame, k = 1 / 60, 1
for f = 0, 600 do
    local now = f * frame
    while arrivals[k] and arrivals[k].at <= now do
        local a = arrivals[k]
        buf:push(a.ts, a.at, { a.x, 0, 0 }, { speed, 0, 0 }, 0)
        k = k + 1
    end
    local pos = buf:sample(now)
    if pos and f > 60 and f < 560 then
        if lastX then
            local step = pos[1] - lastX
            minStep, maxStep = math.min(minStep, step), math.max(maxStep, step)
        end
        lastX = pos[1]
        -- the true position at their time (now - offset - delay) is what we should show
        local theirNow = now - buf.offset - buf:delay()
        worst = math.max(worst, math.abs(pos[1] - speed * (theirNow - 500)))
    end
end
check(minStep > 0, 'never moves backwards (smallest step ' .. minStep .. ')')
check(maxStep < 2 * speed * frame, 'no jumps forward (largest step ' .. maxStep .. ')')
check(worst < 3, 'follows the path within 3 units (worst ' .. worst .. ')')
check(buf:delay() < 0.12, 'shown less than 120 ms behind (' .. buf:delay() .. ')')

-- a stop: the last message says standing still; it must not run on past it
local stop = interp.new()
stop:push(10, 1, { 0, 0, 0 }, { 300, 0, 0 }, 0)
stop:push(10.05, 1.05, { 15, 0, 0 }, { 300, 0, 0 }, 0)
stop:push(10.1, 1.1, { 20, 0, 0 }, { 0, 0, 0 }, 0)
local p = stop:sample(3)
check(near(p[1], 20), 'a stopped player stays where they stopped (' .. p[1] .. ')')

-- a teleport: no gliding across the map
local tp = interp.new()
tp:push(1, 1, { 0, 0, 0 }, { 0, 0, 0 }, 0)
tp:push(1.05, 1.05, { 5000, 0, 0 }, { 0, 0, 0 }, 0)
p = tp:sample(1.08)
check(near(p[1], 5000), 'a teleport is a jump (' .. p[1] .. ')')

-- old or repeated messages are ignored
tp:push(1.0, 1.1, { 1, 1, 1 }, nil, 0)
check(#tp.snaps == 1, 'old messages are dropped')

-- facing turns the short way round
local turn = interp.new()
turn:push(1, 1, { 0, 0, 0 }, nil, 3.0)
turn:push(1.1, 1.1, { 0, 0, 0 }, nil, -3.0)
local _, _, yaw = turn:sample(1.15 + turn:delay() - 0.1)
check(math.abs(interp.wrap(yaw - math.pi)) < 0.2, 'turns across the back, not all the way round (' .. yaw .. ')')

if failures == 0 then print('interp_test: all passed') else print('interp_test: ' .. failures .. ' failed') os.exit(1) end
