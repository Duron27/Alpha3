-- Minimal JSON codec for the tests (the game decodes with markup.decodeYaml and encodes with netlib.encodeJson).
local M = {}

function M.encode(v)
    local t = type(v)
    if t == 'nil' then return 'null' end
    if t == 'boolean' or t == 'number' then return tostring(v) end
    if t == 'string' then return '"' .. v:gsub('[%c"\\]', function(c) return string.format('\\u%04x', c:byte()) end) .. '"' end
    local n, isArray = 0, true
    for k in pairs(v) do n = n + 1 if type(k) ~= 'number' then isArray = false end end
    local out = {}
    if isArray then
        for i = 1, n do out[i] = M.encode(v[i]) end
        return '[' .. table.concat(out, ',') .. ']'
    end
    for k, x in pairs(v) do out[#out + 1] = M.encode(tostring(k)) .. ':' .. M.encode(x) end
    return '{' .. table.concat(out, ',') .. '}'
end

function M.decode(s)
    local pos = 1
    local function ws() pos = s:find('%S', pos) or #s + 1 end
    local value
    local function str()
        local out, i = {}, pos + 1
        while true do
            local c = s:sub(i, i)
            if c == '"' then pos = i + 1 return table.concat(out) end
            if c == '\\' then
                local e = s:sub(i + 1, i + 1)
                if e == 'u' then out[#out + 1] = string.char(tonumber(s:sub(i + 2, i + 5), 16) % 256) i = i + 6
                else out[#out + 1] = ({ n = '\n', t = '\t', r = '\r' })[e] or e i = i + 2 end
            else out[#out + 1] = c i = i + 1 end
        end
    end
    function value()
        ws()
        local c = s:sub(pos, pos)
        if c == '{' then
            local obj = {}
            pos = pos + 1 ws()
            if s:sub(pos, pos) == '}' then pos = pos + 1 return obj end
            while true do
                ws() local k = str() ws() pos = pos + 1 -- colon
                obj[k] = value() ws()
                local d = s:sub(pos, pos) pos = pos + 1
                if d == '}' then return obj end
            end
        elseif c == '[' then
            local arr = {}
            pos = pos + 1 ws()
            if s:sub(pos, pos) == ']' then pos = pos + 1 return arr end
            while true do
                arr[#arr + 1] = value() ws()
                local d = s:sub(pos, pos) pos = pos + 1
                if d == ']' then return arr end
            end
        elseif c == '"' then return str()
        elseif s:sub(pos, pos + 3) == 'true' then pos = pos + 4 return true
        elseif s:sub(pos, pos + 4) == 'false' then pos = pos + 5 return false
        elseif s:sub(pos, pos + 3) == 'null' then pos = pos + 4 return nil
        else
            local num = s:match('^-?[%d%.eE+-]+', pos)
            pos = pos + #num
            return tonumber(num)
        end
    end
    return value()
end

return M
