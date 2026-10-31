-- Where another player is, shown smoothly: their position messages carry the time they were taken (their own clock), and
-- we play them back a little behind, between two of them, instead of guessing ahead from the latest one. A late or lost
-- message then changes nothing that is already on screen, and a stop, a turn or a jump is shown where it happened.
--
-- Pure Lua (no engine), so it runs in the tests too: positions are { x, y, z } tables here.
--   local buf = interp.new()
--   buf:push(ts, localNow, pos, vel, yaw)   -- every state message
--   local pos, vel, yaw = buf:sample(localNow)
local M = {}

local MAX_SNAPSHOTS = 40
local MAX_EXTRAPOLATION = 0.25 -- seconds past the newest message we keep moving them before assuming they stopped
local TELEPORT = 700 -- units between two messages that are a jump in place (teleport, door), not movement
local MIN_DELAY, MAX_DELAY = 0.04, 0.3
local OFFSET_RISE = 0.05 -- how fast (fraction per message) the clock offset follows a connection that got slower

local Buffer = {}
Buffer.__index = Buffer

function M.new()
    return setmetatable({ snaps = {}, offset = nil, jitter = 0.01, interval = 0.05, lastTs = nil }, Buffer)
end

local function lerp(a, b, t) return a + (b - a) * t end

local function wrap(a)
    while a > math.pi do a = a - 2 * math.pi end
    while a < -math.pi do a = a + 2 * math.pi end
    return a
end

local function dist(a, b)
    local dx, dy, dz = a[1] - b[1], a[2] - b[2], a[3] - b[3]
    return math.sqrt(dx * dx + dy * dy + dz * dz)
end

-- Forget everything (a cell change or a teleport: nothing to blend from).
function Buffer:reset()
    self.snaps = {}
end

-- ts: when the sender took it (their clock); now: our clock as it arrived.
function Buffer:push(ts, now, pos, vel, yaw)
    if type(ts) ~= 'number' then ts = now end -- an older client without times: as if it was sent just now
    -- The offset between their clock and ours, plus the quickest the trip has been: the lowest (now - ts) seen. It rises
    -- slowly if the connection gets slower for good, and drops at once when a message is quicker.
    local sample = now - ts
    if not self.offset or sample < self.offset then self.offset = sample
    else self.offset = self.offset + (sample - self.offset) * OFFSET_RISE end
    -- how unevenly they arrive, and how often
    self.jitter = self.jitter + (math.abs(sample - self.offset) - self.jitter) * 0.1
    if self.lastTs and ts > self.lastTs then
        self.interval = self.interval + (math.min(0.5, ts - self.lastTs) - self.interval) * 0.1
    end
    local snaps = self.snaps
    local last = snaps[#snaps]
    if last and ts <= last.ts then return end -- old or repeated: we already have newer
    if last and dist(last.pos, pos) > TELEPORT then snaps = {}; self.snaps = snaps end
    self.lastTs = ts
    snaps[#snaps + 1] = { ts = ts, pos = pos, vel = vel or { 0, 0, 0 }, yaw = yaw or 0 }
    while #snaps > MAX_SNAPSHOTS do table.remove(snaps, 1) end
end

-- How far behind their newest message we show them: a little over one message apart plus how uneven the arrivals are,
-- so there is nearly always a newer message to move toward.
function Buffer:delay()
    return math.max(MIN_DELAY, math.min(MAX_DELAY, self.interval * 1.2 + self.jitter * 2))
end

-- Their position, velocity and facing for our clock `now`; nil before the first message.
function Buffer:sample(now)
    local snaps = self.snaps
    local n = #snaps
    if n == 0 then return nil end
    local t = now - self.offset - self:delay()
    local first, last = snaps[1], snaps[n]
    if t <= first.ts then return first.pos, first.vel, first.yaw end
    if t >= last.ts then
        local age = math.min(MAX_EXTRAPOLATION, t - last.ts)
        local p, v = last.pos, last.vel
        -- past the newest message only a short way: a player who stopped sending has probably stopped
        local fade = age < MAX_EXTRAPOLATION and 1 or 0
        return { p[1] + v[1] * age, p[2] + v[2] * age, p[3] + v[3] * age }, { v[1] * fade, v[2] * fade, v[3] * fade }, last.yaw
    end
    -- the two messages around t (searched from the newest end: t is nearly always there)
    local i = n - 1
    while i > 1 and snaps[i].ts > t do i = i - 1 end
    local a, b = snaps[i], snaps[i + 1]
    local span = b.ts - a.ts
    local s = span > 0 and (t - a.ts) / span or 1
    -- Hermite curve through both positions with their velocities: a curve stays a curve and speeds blend smoothly
    local s2, s3 = s * s, s * s * s
    local h00, h10, h01, h11 = 2 * s3 - 3 * s2 + 1, s3 - 2 * s2 + s, -2 * s3 + 3 * s2, s3 - s2
    local pos, vel = {}, {}
    for k = 1, 3 do
        local va, vb = a.vel[k] * span, b.vel[k] * span
        -- velocities that disagree with the actual travel (a bump, a stop) would make the curve overshoot: use the travel
        local travel = b.pos[k] - a.pos[k]
        if va * travel < 0 then va = travel end
        if vb * travel < 0 then vb = travel end
        pos[k] = h00 * a.pos[k] + h10 * va + h01 * b.pos[k] + h11 * vb
        vel[k] = span > 0 and travel / span or 0
    end
    return pos, vel, a.yaw + wrap(b.yaw - a.yaw) * s
end

-- The newest message (cell, flags...), for what is not interpolated.
function Buffer:latest()
    return self.snaps[#self.snaps]
end

M.wrap = wrap
return M
