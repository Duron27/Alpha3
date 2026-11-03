-- Alpha3 Lua benchmark: the same pure-Lua workloads as the phone's standalone LuaJIT benchmark, plus OpenMW API
-- calls, timed inside the game. One workload per frame after a game is loaded; results go to openmw.log as
-- "[luabench] ...". Remove alpha3-luabench.omwscripts from openmw.cfg to stop it.
--
-- Reading it: the standalone LuaJIT on this phone (Snapdragon 855, gold core) took, JIT on / JIT off:
--   nbody 57 / 429 ms, spectral-norm 16 / 308, vec3-metatable 7 / 556, closures-oop 23 / 169
-- Numbers near the first column mean the JIT works inside OpenMW; near the second, scripts are interpreted.
local core = require('openmw.core')
local world = require('openmw.world')
local util = require('openmw.util')
local clock = core.getRealTime
local REPS = 3

local W = {}
local function add(name, f) W[#W + 1] = { name, f } end

add("nbody", function()
  local sqrt = math.sqrt
  local PI = math.pi; local SM = 4 * PI * PI; local DP = 365.24
  local bodies = {
    {0,0,0,0,0,0,SM},
    {4.84143144246472090e+00,-1.16032004402742839e+00,-1.03622044471123109e-01,1.66007664274403694e-03*DP,7.69901118419740425e-03*DP,-6.90460016972063023e-05*DP,9.54791938424326609e-04*SM},
    {8.34336671824457987e+00,4.12479856412430479e+00,-4.03523417114321381e-01,-2.76742510726862411e-03*DP,4.99852801234917238e-03*DP,2.30417297573763929e-05*DP,2.85885980666130812e-04*SM},
    {1.28943695621391310e+01,-1.51111514016986312e+01,-2.23307578892655734e-01,2.96460137564761618e-03*DP,2.37847173959480950e-03*DP,-2.96589568540237556e-05*DP,4.36624404335156298e-05*SM},
    {1.53796971148509165e+01,-2.59193146099879641e+01,1.79258772950371181e-01,2.68067772490389322e-03*DP,1.62824170038242295e-03*DP,-9.51592254519715870e-05*DP,5.15138902046611451e-05*SM},
  }
  local n = #bodies
  for _ = 1, 200000 do
    for i = 1, n do
      local bi = bodies[i]
      local x1, y1, z1, m1 = bi[1], bi[2], bi[3], bi[7]
      for j = i + 1, n do
        local bj = bodies[j]
        local dx, dy, dz = x1 - bj[1], y1 - bj[2], z1 - bj[3]
        local d2 = dx*dx + dy*dy + dz*dz
        local mag = 0.01 / (d2 * sqrt(d2))
        local m2 = bj[7]
        bi[4] = bi[4] - dx*m2*mag; bi[5] = bi[5] - dy*m2*mag; bi[6] = bi[6] - dz*m2*mag
        bj[4] = bj[4] + dx*m1*mag; bj[5] = bj[5] + dy*m1*mag; bj[6] = bj[6] + dz*m1*mag
      end
    end
    for i = 1, n do
      local b = bodies[i]
      b[1] = b[1] + 0.01*b[4]; b[2] = b[2] + 0.01*b[5]; b[3] = b[3] + 0.01*b[6]
    end
  end
end)

add("spectral-norm", function()
  local function A(i, j) local ij = i + j - 1; return 1.0 / (ij * (ij - 1) * 0.5 + i) end
  local function Av(x, y, N) for i = 1, N do local a = 0; for j = 1, N do a = a + x[j] * A(i, j) end; y[i] = a end end
  local function Atv(x, y, N) for i = 1, N do local a = 0; for j = 1, N do a = a + x[j] * A(j, i) end; y[i] = a end end
  local function AtAv(x, y, t, N) Av(x, t, N); Atv(t, y, N) end
  local N = 300
  local u, v, t = {}, {}, {}
  for i = 1, N do u[i] = 1 end
  for _ = 1, 10 do AtAv(u, v, t, N); AtAv(v, u, t, N) end
end)

add("fannkuch(9)", function()
  local n = 9
  local p, q, s, sign, maxflips, sum = {}, {}, {}, 1, 0, 0
  for i = 1, n do p[i] = i; q[i] = i; s[i] = i end
  repeat
    local q1 = p[1]
    if q1 ~= 1 then
      for i = 2, n do q[i] = p[i] end
      local flips = 1
      while true do
        local qq = q[q1]
        if qq == 1 then sum = sum + sign * flips; if flips > maxflips then maxflips = flips end; break end
        q[q1] = q1
        if q1 >= 4 then local i, j = 2, q1 - 1; repeat q[i], q[j] = q[j], q[i]; i = i + 1; j = j - 1 until i >= j end
        q1 = qq; flips = flips + 1
      end
    end
    if sign == 1 then p[2], p[1] = p[1], p[2]; sign = -1
    else
      p[2], p[3] = p[3], p[2]; sign = 1
      for i = 3, n do
        local sx = s[i]
        if sx ~= 1 then s[i] = sx - 1; break end
        if i == n then return end
        s[i] = i
        local t = p[1]; for j = 1, i do p[j] = p[j + 1] end; p[i + 1] = t
      end
    end
  until false
end)

add("binary-trees(14)", function()
  local function bottomUp(d) if d == 0 then return {} end; d = d - 1; return { bottomUp(d), bottomUp(d) } end
  local function check(t) if t[1] then return 1 + check(t[1]) + check(t[2]) else return 1 end end
  local N = 14
  local long = bottomUp(N)
  for d = 4, N, 4 do
    local iters = 2 ^ (N - d + 4)
    local c = 0
    for _ = 1, iters do c = c + check(bottomUp(d)) end
  end
  check(long)
end)

-- A vector class with metamethods: how script-side math tends to look (every operator allocates a table)
add("vec3-metatable", function()
  local V = {}; V.__index = V
  local function new(x, y, z) return setmetatable({ x = x, y = y, z = z }, V) end
  V.__add = function(a, b) return new(a.x + b.x, a.y + b.y, a.z + b.z) end
  V.__sub = function(a, b) return new(a.x - b.x, a.y - b.y, a.z - b.z) end
  V.__mul = function(a, s) return new(a.x * s, a.y * s, a.z * s) end
  function V:length() return math.sqrt(self.x * self.x + self.y * self.y + self.z * self.z) end
  function V:normalized() local l = self:length(); return new(self.x / l, self.y / l, self.z / l) end
  local pos, vel, acc = new(0, 0, 0), new(1, 0.5, 0.25), new(0.001, 0.002, 0.003)
  local sum = 0
  for i = 1, 600000 do
    vel = vel + acc * 0.016
    pos = pos + vel * 0.016
    if i % 64 == 0 then vel = (vel - pos):normalized() * 2 end
    sum = sum + pos:length()
  end
end)

-- Per-frame event bookkeeping: lists of small records filled, filtered, rebuilt (table.insert/remove, ipairs/pairs)
add("table-churn", function()
  local live = {}
  for frame = 1, 4000 do
    for i = 1, 40 do live[#live + 1] = { id = frame * 100 + i, hp = i, tags = { "a", "b" }, t = frame } end
    local keep = {}
    for _, e in ipairs(live) do if (e.id + frame) % 3 ~= 0 then keep[#keep + 1] = e end end
    live = keep
    if #live > 400 then for _ = 1, 100 do table.remove(live, 1) end end
    local total = 0
    for _, e in pairs(live) do total = total + e.hp end
  end
end)

add("string-ops", function()
  local fmt, find, gsub, sub, concat = string.format, string.find, string.gsub, string.sub, table.concat
  local parts = {}
  for i = 1, 60000 do
    local s = fmt("npc_%05d:%s:%0.2f", i, "Seyda Neen", i * 0.37)
    local a, b = find(s, ":", 1, true)
    local name = sub(s, 1, a - 1)
    s = gsub(s, "%d+", "#")
    parts[#parts + 1] = name .. s
    if #parts > 2000 then parts = {} end
  end
  local t = {}
  for i = 1, 40000 do t[i] = "k" .. i end
  local joined = concat(t, ",")
  local n = 0
  for w in joined:gmatch("[^,]+") do n = n + #w end
end)

add("sort-comparator", function()
  local seed = 12345
  local function rnd() seed = (seed * 1103515245 + 12345) % 2147483648; return seed end
  local a = {}
  for i = 1, 60000 do a[i] = { key = rnd() % 10000, name = i } end
  table.sort(a, function(x, y) if x.key ~= y.key then return x.key < y.key end return x.name < y.name end)
end)

add("closures-oop", function()
  local Class = {}; Class.__index = Class
  function Class.new(v) return setmetatable({ v = v, hooks = {} }, Class) end
  function Class:on(f) self.hooks[#self.hooks + 1] = f end
  function Class:fire(x) for i = 1, #self.hooks do self.hooks[i](self, x) end end
  local objs = {}
  for i = 1, 200 do
    local o = Class.new(i)
    local n = 0
    o:on(function(self, x) n = n + x + self.v end)
    o:on(function(self, x) self.v = (self.v + x) % 1000 end)
    objs[i] = o
  end
  for frame = 1, 5000 do for i = 1, #objs do objs[i]:fire(frame) end end
end)


-- Through OpenMW's C++ bindings: every vector is a userdata made by sol2, every call crosses into C++
add("api:vector3-math", function()
  local v, a, s = util.vector3(1, 2, 3), util.vector3(0.001, 0.002, 0.003), 0
  for _ = 1, 200000 do v = v + a * 0.016; s = s + v:length() end
end)

add("api:actor-scan x200", function()
  local s = 0
  local p0 = world.players[1] and world.players[1].position or util.vector3(0, 0, 0)
  for _ = 1, 200 do
    local actors = world.activeActors
    for i = 1, #actors do
      local a = actors[i]
      s = s + (a.position - p0):length()
      local _ = a.recordId
    end
  end
end)

local results, index, started = {}, 0, false

local function runOne(w)
  local ok, err = pcall(w[2])  -- warm-up, also compiles hot loops when the JIT works
  if not ok then return string.format("%-20s failed: %s", w[1], tostring(err)) end
  local first, best = nil, math.huge
  for _ = 1, REPS do
    local t = clock(); w[2](); t = (clock() - t) * 1000
    first = first or t
    if t < best then best = t end
  end
  return string.format("%-20s best %8.1f ms", w[1], best), best
end

return {
  engineHandlers = {
    onUpdate = function()
      if index > #W then return end
      if not started then
        started = true
        print(string.format("[luabench] start: %s, jit global %s, %d actors active",
          tostring(_VERSION), type(jit), #world.activeActors))
        index = 1
        return
      end
      local line, best = runOne(W[index])
      results[W[index][1]] = best
      print("[luabench] " .. line)
      index = index + 1
      if index > #W then
        local v = results["vec3-metatable"]
        local verdict = not v and "unknown" or v < 50 and "JIT is working" or v > 300 and "JIT is NOT working (interpreted)"
          or "partly compiled"
        print("[luabench] done: " .. verdict)
      end
    end,
  },
}
