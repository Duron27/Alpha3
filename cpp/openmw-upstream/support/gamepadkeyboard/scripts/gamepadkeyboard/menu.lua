-- Gamepad Keyboard: an on-screen keyboard driven by a gamepad, for the game's text boxes (the console, a saved game's
-- name, the character's and a spell's names, the inventory's search) and Lua mods' ones.
--
-- A menu script, so it works in the main menu, in game, over the console and over dialogs. It opens by itself when a
-- text box gets the focus while the gamepad is in use (or with the left stick's click) and closes when the text box
-- loses it. The typing and the gamepad's capture are the engine's side (openmw.input._alpha3*, Alpha3's
-- support/src/gamepadkeyboard.cpp); without them (another build of OpenMW) the mod does nothing.
--
--   D-pad / left stick  move          A      type the key
--   B                   delete        X      space
--   Y                   shift / caps  LB/RB  move the text cursor
--   Start               enter         Back   close (L3 opens it again)
--
-- Optional: Options > Scripts > Gamepad Keyboard turns it off, or keeps it from opening by itself.

local core = require('openmw.core')
local input = require('openmw.input')
local storage = require('openmw.storage')
local ui = require('openmw.ui')
local util = require('openmw.util')
local I = require('openmw.interfaces')

local l10n = core.l10n('GamepadKeyboard')

if input._alpha3TypeText == nil then
    print('[Gamepad Keyboard] this build of OpenMW cannot type into text boxes from Lua: the keyboard is off')
    return {}
end

-- Settings ------------------------------------------------------------------------------------------------------------

local settingsKey = 'SettingsGamepadKeyboard'

I.Settings.registerPage({
    key = 'GamepadKeyboard',
    l10n = 'GamepadKeyboard',
    name = 'PageName',
    description = 'PageDescription',
})

I.Settings.registerGroup({
    key = settingsKey,
    page = 'GamepadKeyboard',
    l10n = 'GamepadKeyboard',
    name = 'GroupName',
    permanentStorage = true,
    settings = {
        { key = 'enabled', renderer = 'checkbox', name = 'Enabled', description = 'EnabledDescription', default = true },
        { key = 'autoOpen', renderer = 'checkbox', name = 'AutoOpen', description = 'AutoOpenDescription', default = true },
        {
            key = 'consoleCombo',
            renderer = 'checkbox',
            name = 'ConsoleCombo',
            description = 'ConsoleComboDescription',
            default = true,
        },
    },
})

local settings = storage.playerSection(settingsKey)

local function enabled()
    return settings:get('enabled') ~= false
end

-- Layouts -------------------------------------------------------------------------------------------------------------

-- Keys of the character rows are one unit wide; the last row's are wider. Ten units a row.
local letterRows = {
    { '1', '2', '3', '4', '5', '6', '7', '8', '9', '0' },
    { 'q', 'w', 'e', 'r', 't', 'y', 'u', 'i', 'o', 'p' },
    { 'a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l', "'" },
    { 'z', 'x', 'c', 'v', 'b', 'n', 'm', ',', '.', '-' },
}
local symbolRows = {
    { '!', '@', '#', '$', '%', '^', '&', '*', '(', ')' },
    { '~', '`', '_', '=', '+', '[', ']', '{', '}', '\\' },
    { '<', '>', '/', '?', ':', ';', '"', '|', '-', '.' },
    { ',', "'", '0', '1', '2', '3', '4', '5', '6', '7' },
}
local bottomRow = {
    { action = 'shift', width = 1.5 },
    { action = 'symbols', width = 1.5 },
    { action = 'space', width = 3 },
    { action = 'left', width = 1 },
    { action = 'right', width = 1 },
    { action = 'backspace', width = 1 },
    { action = 'enter', width = 1 },
}

local SHIFT_OFF, SHIFT_ONCE, SHIFT_CAPS = 0, 1, 2

local state = {
    open = false,
    element = nil,
    row = 2,
    column = 1,
    shift = SHIFT_OFF,
    symbols = false,
    wasFocused = false,
    gamepadLast = false,
    -- held direction: which, when it repeats next
    held = nil,
    nextRepeat = 0,
    -- the focused box's top and bottom on the screen (0 top, 1 bottom), and whether the keyboard sits at the top
    boxTop = 0,
    boxBottom = 1,
    atTop = false,
}

-- The rows of keys as they are now: each key { label, text or action, x (units from the left), width }
local function currentRows()
    local rows = {}
    local chars = state.symbols and symbolRows or letterRows
    for r, row in ipairs(chars) do
        local keys = {}
        local x = (10 - #row) / 2
        for _, char in ipairs(row) do
            local text = char
            if not state.symbols and state.shift ~= SHIFT_OFF then
                text = char:upper()
            end
            keys[#keys + 1] = { label = text, text = text, x = x, width = 1 }
            x = x + 1
        end
        rows[r] = keys
    end
    local keys = {}
    local x = 0
    for _, key in ipairs(bottomRow) do
        local label
        if key.action == 'shift' then
            label = state.shift == SHIFT_CAPS and l10n('Caps') or l10n('Shift')
        elseif key.action == 'symbols' then
            label = state.symbols and l10n('Letters') or l10n('Symbols')
        elseif key.action == 'space' then
            label = l10n('Space')
        elseif key.action == 'left' then
            label = '<'
        elseif key.action == 'right' then
            label = '>'
        elseif key.action == 'backspace' then
            label = l10n('Backspace')
        else
            label = l10n('Enter')
        end
        keys[#keys + 1] = { label = label, action = key.action, x = x, width = key.width }
        x = x + key.width
    end
    rows[#rows + 1] = keys
    return rows
end

-- Drawing -------------------------------------------------------------------------------------------------------------

local white = ui.texture({ path = 'white' })
local gold = util.color.rgb(0.792, 0.651, 0.463)
local keyColor = util.color.rgb(0.12, 0.11, 0.10)
local backColor = util.color.rgb(0.03, 0.03, 0.03)
local black = util.color.rgb(0, 0, 0)

local function layerSize()
    local index = ui.layers.indexOf('Notification')
    if index then
        return ui.layers[index].size
    end
    return ui.screenSize()
end

local function keyLayout(key, selected, unit, keyHeight, y, gap)
    local active = key.action == 'shift' and state.shift ~= SHIFT_OFF or key.action == 'symbols' and state.symbols
    local fill = selected and gold or keyColor
    return {
        type = ui.TYPE.Widget,
        props = {
            position = util.vector2(key.x * unit + gap, y),
            size = util.vector2(key.width * unit - gap * 2, keyHeight),
        },
        content = ui.content({
            {
                type = ui.TYPE.Image,
                props = { resource = white, relativeSize = util.vector2(1, 1), color = fill, alpha = 0.92 },
            },
            {
                type = ui.TYPE.Image,
                props = {
                    resource = white,
                    relativeSize = util.vector2(1, 0),
                    size = util.vector2(0, 2),
                    relativePosition = util.vector2(0, 1),
                    anchor = util.vector2(0, 1),
                    color = gold,
                    alpha = (active and not selected) and 0.9 or 0,
                },
            },
            {
                type = ui.TYPE.Text,
                props = {
                    text = key.label,
                    textSize = math.floor(keyHeight * 0.5),
                    textColor = selected and black or gold,
                    relativePosition = util.vector2(0.5, 0.5),
                    anchor = util.vector2(0.5, 0.5),
                },
            },
        }),
    }
end

local function layout()
    local screen = layerSize()
    local width = math.min(screen.x * 0.94, screen.y * 1.6)
    local unit = width / 10
    local keyHeight = math.floor(math.min(unit * 0.78, screen.y * 0.075))
    local gap = math.max(2, math.floor(unit * 0.04))
    local hintSize = math.max(12, math.floor(keyHeight * 0.32))
    local top = hintSize + gap * 3
    local rows = currentRows()
    local content = {
        {
            type = ui.TYPE.Image,
            props = { resource = white, relativeSize = util.vector2(1, 1), color = backColor, alpha = 0.88 },
        },
        {
            type = ui.TYPE.Text,
            props = {
                text = l10n('Hints'),
                textSize = hintSize,
                textColor = gold,
                position = util.vector2(width / 2, gap),
                anchor = util.vector2(0.5, 0),
            },
        },
    }
    for r, keys in ipairs(rows) do
        local y = top + (r - 1) * (keyHeight + gap * 2)
        for c, key in ipairs(keys) do
            content[#content + 1] = keyLayout(key, r == state.row and c == state.column, unit, keyHeight, y, gap)
        end
    end
    local height = top + #rows * (keyHeight + gap * 2) + gap
    -- at the bottom, unless that covers the text box and the top does not (the console's line is at its bottom)
    local share = (height + gap * 4) / screen.y
    state.atTop = state.boxBottom > 1 - share and state.boxTop >= share
    local edge = state.atTop and 0 or 1
    return {
        layer = 'Notification',
        type = ui.TYPE.Widget,
        props = {
            relativePosition = util.vector2(0.5, edge),
            anchor = util.vector2(0.5, edge),
            position = util.vector2(0, state.atTop and gap * 2 or -gap * 2),
            size = util.vector2(width, height),
        },
        content = ui.content(content),
    }
end

local function redraw()
    if not state.open then
        return
    end
    if state.element then
        state.element.layout = layout()
        state.element:update()
    else
        state.element = ui.create(layout())
    end
end

local function open()
    if state.open or not enabled() then
        return
    end
    state.open = true
    state.held = nil
    input._alpha3CaptureGamepad()
    redraw()
end

local function close()
    if not state.open then
        return
    end
    state.open = false
    state.held = nil
    if state.element then
        state.element:destroy()
        state.element = nil
    end
end

-- Moving and typing ---------------------------------------------------------------------------------------------------

local function clampColumn(rows)
    local keys = rows[state.row]
    state.column = math.max(1, math.min(state.column, #keys))
end

local function move(dx, dy)
    local rows = currentRows()
    if dy ~= 0 then
        local from = rows[state.row][state.column]
        local center = from.x + from.width / 2
        state.row = (state.row - 1 + dy) % #rows + 1
        -- the key of the new row under the old one's middle
        local best, bestDistance = 1, math.huge
        for c, key in ipairs(rows[state.row]) do
            local distance = math.abs(key.x + key.width / 2 - center)
            if distance < bestDistance then
                best, bestDistance = c, distance
            end
        end
        state.column = best
    end
    if dx ~= 0 then
        local count = #rows[state.row]
        state.column = (state.column - 1 + dx) % count + 1
    end
    clampColumn(rows)
    redraw()
end

local function typeText(text)
    input._alpha3TypeText(text)
    if state.shift == SHIFT_ONCE then
        state.shift = SHIFT_OFF
        redraw()
    end
end

local function doAction(action)
    if action == 'shift' then
        state.shift = (state.shift + 1) % 3
        redraw()
    elseif action == 'symbols' then
        state.symbols = not state.symbols
        clampColumn(currentRows())
        redraw()
    elseif action == 'space' then
        input._alpha3TypeText(' ')
    elseif action == 'left' then
        input._alpha3PressKey('Left')
    elseif action == 'right' then
        input._alpha3PressKey('Right')
    elseif action == 'backspace' then
        input._alpha3PressKey('Backspace')
    elseif action == 'enter' then
        -- the text box decides: the console runs the command and keeps the focus (the keyboard stays), a dialog
        -- accepts and closes (the keyboard goes with its text box)
        input._alpha3PressKey('Return')
    end
end

local function pressSelected()
    local rows = currentRows()
    local key = rows[state.row][state.column]
    if key.text then
        typeText(key.text)
    else
        doAction(key.action)
    end
end

-- Directions held: the d-pad's buttons and the left stick, repeated while held
local directions = {
    up = { 0, -1 },
    down = { 0, 1 },
    left = { -1, 0 },
    right = { 1, 0 },
}
local buttonDirection = {
    [input.CONTROLLER_BUTTON.DPadUp] = 'up',
    [input.CONTROLLER_BUTTON.DPadDown] = 'down',
    [input.CONTROLLER_BUTTON.DPadLeft] = 'left',
    [input.CONTROLLER_BUTTON.DPadRight] = 'right',
}
local REPEAT_DELAY, REPEAT_EVERY, STICK = 0.35, 0.09, 0.55

local function heldDirection()
    for button, direction in pairs(buttonDirection) do
        if input.isControllerButtonPressed(button) then
            return direction
        end
    end
    local x = input.getAxisValue(input.CONTROLLER_AXIS.LeftX)
    local y = input.getAxisValue(input.CONTROLLER_AXIS.LeftY)
    if math.abs(x) > math.abs(y) then
        if x > STICK then
            return 'right'
        elseif x < -STICK then
            return 'left'
        end
    else
        if y > STICK then
            return 'down'
        elseif y < -STICK then
            return 'up'
        end
    end
    return nil
end

local function stepDirection(direction)
    local d = directions[direction]
    move(d[1], d[2])
end

local function updateHeld()
    local direction = heldDirection()
    local now = core.getRealTime()
    if direction == nil then
        state.held = nil
    elseif direction ~= state.held then
        state.held = direction
        state.nextRepeat = now + REPEAT_DELAY
        stepDirection(direction)
    elseif now >= state.nextRepeat then
        state.nextRepeat = now + REPEAT_EVERY
        stepDirection(direction)
    end
end

-- Engine handlers -----------------------------------------------------------------------------------------------------

local function onFrame()
    local focused, boxTop, boxBottom = false, 0, 1
    if enabled() then
        focused, boxTop, boxBottom = input._alpha3TextFocused()
    end
    local moved = math.abs(boxTop - state.boxTop) > 0.01 or math.abs(boxBottom - state.boxBottom) > 0.01
    state.boxTop, state.boxBottom = boxTop, boxBottom
    if state.open then
        if not focused then
            close()
        else
            if moved then
                redraw() -- the box moved (another box took the focus, a window was dragged): keep clear of it
            end
            -- the gamepad stays the keyboard's while it is open (the engine gives it back a moment after this stops)
            input._alpha3CaptureGamepad()
            updateHeld()
        end
    elseif focused and not state.wasFocused and state.gamepadLast and settings:get('autoOpen') ~= false then
        open()
    end
    state.wasFocused = focused
end

local function onControllerButtonPress(button)
    state.gamepadLast = true
    local B = input.CONTROLLER_BUTTON
    -- Back + Start: the console (Back does nothing by default; the capture keeps this Start from the game's menu)
    if button == B.Start and input.isControllerButtonPressed(B.Back) and enabled()
        and settings:get('consoleCombo') ~= false then
        input._alpha3CaptureGamepad()
        input._alpha3ToggleConsole()
        return
    end
    if not state.open then
        if button == input.CONTROLLER_BUTTON.LeftStick and enabled() and input._alpha3TextFocused() then
            open()
        end
        return
    end
    if buttonDirection[button] then
        return -- moved in onFrame, with the repeat
    elseif button == B.A then
        pressSelected()
    elseif button == B.B then
        input._alpha3PressKey('Backspace')
    elseif button == B.X then
        input._alpha3TypeText(' ')
    elseif button == B.Y then
        doAction('shift')
    elseif button == B.LeftShoulder then
        input._alpha3PressKey('Left')
    elseif button == B.RightShoulder then
        input._alpha3PressKey('Right')
    elseif button == B.Start then
        doAction('enter')
    elseif button == B.Back or button == B.LeftStick then
        close()
    end
end

local function notGamepad()
    state.gamepadLast = false
end

settings:subscribe(require('openmw.async'):callback(function()
    if not enabled() then
        close()
    end
end))

return {
    interfaceName = 'GamepadKeyboard',
    interface = {
        version = 1,
        isOpen = function()
            return state.open
        end,
        open = open,
        close = close,
    },
    engineHandlers = {
        onFrame = onFrame,
        onControllerButtonPress = onControllerButtonPress,
        onKeyPress = notGamepad,
        onMouseButtonPress = notGamepad,
    },
}
