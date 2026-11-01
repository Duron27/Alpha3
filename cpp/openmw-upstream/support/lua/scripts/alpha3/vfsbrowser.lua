-- The VFS browser: the game's virtual file system (every data directory and archive merged, as the engine sees it)
-- shown like a file browser. Opened from Options, Scripts, Alpha3 (in the main menu and in a game).
--  - folders first, then files, a page at a time (touch friendly: no scrolling list in the Lua UI)
--  - for a file: its size, where it comes from (the data directory or BSA whose copy the engine uses, from
--    alpha3/vfs-sources.txt, which the support library makes the first time it is read), and a preview: textures as an
--    image, text files' first lines, anything else its first bytes in hex
--  - a search box: every file under the folder whose path contains the text
local ui = require('openmw.ui')
local util = require('openmw.util')
local vfs = require('openmw.vfs')
local input = require('openmw.input')
local async = require('openmw.async')
local I = require('openmw.interfaces')

local V2, rgb = util.vector2, util.color.rgb
local WHITE = ui.texture { path = 'white' }
local W, H = 1060, 500 -- GUI units; a phone with the GUI scaled 2x is about 1140 by 540
local LIST_W = 560
local ROW_H = 26
local ROWS = 14
local TEXT_SIZE = 17
local MAX_SEARCH = 2000
local C = {
    text = rgb(0.92, 0.92, 0.92), head = rgb(1, 0.85, 0.35), dim = rgb(0.6, 0.6, 0.6), folder = rgb(0.55, 0.8, 1),
    button = rgb(1, 1, 1), sel = rgb(1, 0.85, 0.35), panel = rgb(0.06, 0.05, 0.04), field = rgb(0.02, 0.02, 0.02),
    err = rgb(1, 0.5, 0.4),
}

local TEXT_TYPES = {
    txt = true, lua = true, yaml = true, yml = true, omwscripts = true, cfg = true, json = true, xml = true,
    omwfx = true, glsl = true, vert = true, frag = true, ini = true, md = true, layout = true, skin = true, csv = true,
    toml = true, html = true, fnt = true, omwgame = false,
}
local IMAGE_TYPES = { dds = true, tga = true, png = true, jpg = true, jpeg = true, bmp = true, ktx = true }

local el
local folder = '' -- '' or 'textures/', always ending in '/'
local entries = {} -- { name, path, isFolder, count }
local page = 1
local selected -- path of the file shown
local search = ''
local searchBuf = ''
local message

-- where each file comes from, read once
local sourceNames, sourceOf

local function loadSources()
    if sourceOf then return end
    sourceNames, sourceOf = {}, {}
    local ok, err = pcall(function()
        for line in vfs.lines('alpha3/vfs-sources.txt') do
            local index, rest = line:match('^#(%d+)\t(.*)$')
            if index then
                sourceNames[tonumber(index)] = rest
            else
                local n, path = line:match('^(%d+)\t(.*)$')
                if n then sourceOf[path] = tonumber(n) end
            end
        end
    end)
    if not ok then message = 'No file sources: ' .. tostring(err) end
end

local function sourceText(path)
    local n = sourceOf and sourceOf[path]
    local name = n and sourceNames[n]
    if not name then return 'unknown' end
    return name
end

local function extension(path)
    return (path:match('%.([%w_]+)$') or ''):lower()
end

local function humanSize(bytes)
    if not bytes then return '?' end
    if bytes < 1024 then return bytes .. ' B' end
    if bytes < 1024 * 1024 then return string.format('%.1f KB', bytes / 1024) end
    return string.format('%.2f MB', bytes / 1024 / 1024)
end

local function listFolder()
    local folders, files = {}, {}
    local n = #folder
    for path in vfs.pathsWithPrefix(folder) do
        local rest = path:sub(n + 1)
        local slash = rest:find('/', 1, true)
        if slash then
            local name = rest:sub(1, slash - 1)
            folders[name] = (folders[name] or 0) + 1
        else
            files[#files + 1] = rest
        end
    end
    entries = {}
    local names = {}
    for name in pairs(folders) do names[#names + 1] = name end
    table.sort(names)
    for _, name in ipairs(names) do
        entries[#entries + 1] = { name = name .. '/', path = folder .. name .. '/', isFolder = true, count = folders[name] }
    end
    table.sort(files)
    for _, name in ipairs(files) do
        entries[#entries + 1] = { name = name, path = folder .. name }
    end
end

local function searchFolder()
    entries = {}
    local needle = search:lower()
    local more = false
    for path in vfs.pathsWithPrefix(folder) do
        if path:find(needle, #folder + 1, true) then
            if #entries >= MAX_SEARCH then more = true break end
            entries[#entries + 1] = { name = path:sub(#folder + 1), path = path }
        end
    end
    table.sort(entries, function(a, b) return a.path < b.path end)
    message = #entries .. ' found' .. (more and (' (the first ' .. MAX_SEARCH .. ')') or '') .. ' for "' .. search .. '"'
end

local function refresh()
    page = 1
    message = nil
    if search ~= '' then searchFolder() else listFolder() end
end

local function text(props, events)
    props.textSize = props.textSize or TEXT_SIZE
    return { template = I.MWUI.templates.textNormal, props = props, events = events }
end

local function clipped(s, chars)
    if #s > chars then return s:sub(1, chars - 1) .. '~' end
    return s
end

local render

local function button(x, y, label, fn, color)
    return text({ position = V2(x, y), text = '[ ' .. label .. ' ]', textColor = color or C.button, textSize = 19 },
        { mouseClick = async:callback(fn) })
end

local function close()
    if el then el:destroy() el = nil end
end

-- the preview of the selected file, in the right column
local function preview(content, x, y, w)
    if not selected then
        content[#content + 1] = text({ position = V2(x, y), text = 'Choose a file to see it here.', textColor = C.dim })
        return
    end
    local path = selected
    content[#content + 1] = text({ position = V2(x, y), text = clipped(path:match('[^/]+$') or path, 44), textColor = C.head })
    local size, head
    local ok = pcall(function()
        local f = vfs.open(path)
        size = f:seek('end')
        f:seek('set', 0)
        head = f:read(64)
        f:close()
    end)
    content[#content + 1] = text({ position = V2(x, y + 26), text = 'Size: ' .. (ok and humanSize(size) or 'cannot be read'), textColor = C.text })
    local source = sourceText(path)
    content[#content + 1] = text({ position = V2(x, y + 50), text = 'From: ' .. clipped(source, 60), textColor = C.text, textSize = 15 })
    if #source > 60 then
        content[#content + 1] = text({ position = V2(x, y + 68), text = '  ~' .. source:sub(-58), textColor = C.dim, textSize = 15 })
    end
    local top = y + 96
    local ext = extension(path)
    if IMAGE_TYPES[ext] then
        local side = math.min(w, H - top - 20)
        content[#content + 1] = {
            type = ui.TYPE.Image,
            props = { position = V2(x, top), size = V2(side, side), resource = ui.texture { path = path } },
        }
    elseif TEXT_TYPES[ext] then
        local count = 0
        pcall(function()
            for line in vfs.lines(path) do
                count = count + 1
                if count > 15 then break end
                content[#content + 1] = text({ position = V2(x, top + (count - 1) * 20),
                    text = clipped(line:gsub('\t', '  '):gsub('\r', ''), 62), textColor = C.text, textSize = 14 })
            end
        end)
    elseif head then
        local hex, shown = {}, {}
        for i = 1, #head do
            local b = head:byte(i)
            hex[#hex + 1] = string.format('%02x', b)
            shown[#shown + 1] = (b >= 32 and b < 127) and string.char(b) or '.'
        end
        for row = 0, 3 do
            local a, b = row * 16 + 1, math.min(row * 16 + 16, #hex)
            if a > #hex then break end
            content[#content + 1] = text({ position = V2(x, top + row * 22),
                text = table.concat(hex, ' ', a, b), textColor = C.text, textSize = 14 })
            content[#content + 1] = text({ position = V2(x + 330, top + row * 22),
                text = table.concat(shown, '', a, b), textColor = C.dim, textSize = 14 })
        end
        if ext == 'nif' or ext == 'kf' or ext == 'osgt' or ext == 'dae' then
            content[#content + 1] = text({ position = V2(x, top + 100), text = 'A mesh: no preview.', textColor = C.dim })
        end
    end
end

render = function()
    local content = {
        { type = ui.TYPE.Image, props = { resource = WHITE, relativeSize = V2(1, 1), color = C.panel, alpha = 0.94 } },
        text({ position = V2(16, 10), text = 'VFS', textColor = C.head, textSize = 22 }),
        text({ position = V2(70, 13), text = clipped('/' .. folder, 46), textColor = C.text }),
    }
    -- the search box
    content[#content + 1] = {
        type = ui.TYPE.Widget, props = { position = V2(LIST_W - 200, 8), size = V2(190, 28) },
        content = ui.content {
            { type = ui.TYPE.Image, props = { resource = WHITE, relativeSize = V2(1, 1), color = C.field, alpha = 0.9 } },
            {
                type = ui.TYPE.TextEdit,
                props = { position = V2(6, 3), size = V2(178, 22), textSize = TEXT_SIZE, textColor = rgb(1, 1, 1),
                    multiline = false, text = searchBuf },
                events = { textChanged = async:callback(function(t) searchBuf = t end) },
            },
        },
    }
    content[#content + 1] = button(LIST_W + 2, 8, 'Find', function()
        search = searchBuf:gsub('^%s+', ''):gsub('%s+$', '')
        refresh() render()
    end)

    -- the list
    local pages = math.max(1, math.ceil(#entries / ROWS))
    page = math.min(page, pages)
    local first = (page - 1) * ROWS + 1
    for i = first, math.min(#entries, first + ROWS - 1) do
        local e = entries[i]
        local y = 46 + (i - first) * ROW_H
        local label = e.isFolder and (clipped(e.name, 40) .. '   (' .. e.count .. ')') or clipped(e.name, 52)
        local color = e.isFolder and C.folder or (e.path == selected and C.sel or C.text)
        content[#content + 1] = text({ position = V2(20, y), size = V2(LIST_W - 30, ROW_H), text = label, textColor = color },
            { mouseClick = async:callback(function()
                if e.isFolder then
                    folder, search, searchBuf = e.path, '', ''
                    refresh()
                else
                    selected = e.path
                end
                render()
            end) })
    end
    if #entries == 0 then
        content[#content + 1] = text({ position = V2(20, 46), text = 'Nothing here.', textColor = C.dim })
    end

    -- the bottom row
    local by = H - 40
    content[#content + 1] = button(16, by, 'Up', function()
        if search ~= '' then
            search, searchBuf = '', ''
        else
            folder = folder:gsub('[^/]+/$', '')
        end
        refresh() render()
    end, folder == '' and search == '' and C.dim or C.button)
    content[#content + 1] = button(86, by, 'Top', function() folder, search, searchBuf = '', '', '' refresh() render() end)
    content[#content + 1] = button(166, by, '<', function() page = math.max(1, page - 1) render() end, page > 1 and C.button or C.dim)
    content[#content + 1] = text({ position = V2(214, by + 2), text = page .. ' / ' .. pages, textColor = C.text })
    content[#content + 1] = button(300, by, '>', function() page = math.min(pages, page + 1) render() end, page < pages and C.button or C.dim)
    content[#content + 1] = button(360, by, '<<', function() page = math.max(1, page - 10) render() end, page > 1 and C.button or C.dim)
    content[#content + 1] = button(420, by, '>>', function() page = math.min(pages, page + 10) render() end, page < pages and C.button or C.dim)
    content[#content + 1] = button(W - 100, by, 'Close', close)
    if message then
        content[#content + 1] = text({ position = V2(LIST_W + 20, by - 26), text = clipped(message, 56), textColor = C.dim, textSize = 15 })
    end

    -- the file
    content[#content + 1] = { type = ui.TYPE.Image,
        props = { resource = WHITE, position = V2(LIST_W + 8, 46), size = V2(2, H - 100), color = C.dim, alpha = 0.5 } }
    preview(content, LIST_W + 22, 46, W - LIST_W - 40)

    local layout = {
        layer = 'Modal', type = ui.TYPE.Widget,
        props = { relativePosition = V2(0.5, 0.5), anchor = V2(0.5, 0.5), size = V2(W, H) },
        content = ui.content(content),
    }
    if el then
        el.layout = layout
        el:update()
    else
        el = ui.create(layout)
    end
end

local function open()
    loadSources()
    if #entries == 0 then refresh() end
    render()
end

-- Options, Scripts, Alpha3: a button that opens it
I.Settings.registerRenderer('alpha3OpenButton', function()
    return text({ text = '[ Open ]', textColor = C.button, textSize = 19 }, { mouseClick = async:callback(open) })
end)

I.Settings.registerPage {
    key = 'Alpha3',
    l10n = 'Alpha3',
    name = 'Alpha3',
    description = 'Alpha3Description',
}

I.Settings.registerGroup {
    key = 'SettingsAlpha3Tools',
    page = 'Alpha3',
    l10n = 'Alpha3',
    name = 'Tools',
    permanentStorage = true,
    settings = {
        {
            key = 'VfsBrowser',
            renderer = 'alpha3OpenButton',
            name = 'VfsBrowser',
            description = 'VfsBrowserDescription',
            default = 0,
        },
    },
}

return {
    interfaceName = 'Alpha3VfsBrowser',
    interface = { version = 1, open = open, close = close },
    engineHandlers = {
        onKeyPress = function(key)
            if el and key.code == input.KEY.Escape then close() end
        end,
    },
}
