-- Player's own automation, not part of the mod.
-- Usage: panel_display <panel-name> <reactor-name> <graphic-label> [once|count] [interval=1]
-- Example: panel_display bwr_panel_0 bwr_reactor_0 BWR-STATUS once
-- Without once/count this runs until Ctrl-T. A count is a positive integer;
-- interval is at least 0.05 seconds. No sleep follows the final requested frame.
-- Wire both named peripherals to the computer. Place a graphic display at least
-- 2x2 blocks (64x64 pixels), label it in the wrench screen, and verify list()
-- after panel groups merge. Graphic displays need no numeric CC input binding.
-- This overwrites that display's pixels and palette indices 0,5,14,15 only.
-- It reads the reactor and writes the display; it issues no plant commands.
-- On an error/termination the last picture remains; console errors indicate that
-- the display is no longer refreshing. It is not an independent alarm system.

local args = { ... }
if #args < 3 or #args > 5 then
    error("Usage: panel_display <panel-name> <reactor-name> <graphic-label> [once|count] [interval=1]", 0)
end
local panelName, reactorName, label = args[1], args[2], args[3]
local count = args[4] == "once" and 1 or (args[4] and tonumber(args[4]) or nil)
local function finite(value)
    return type(value) == "number" and value == value and value > -math.huge and value < math.huge
end
if args[4] and (not finite(count) or count < 1 or count % 1 ~= 0) then
    error("Frame count must be once or a positive finite integer", 0)
end
local interval = 1
if args[5] ~= nil then interval = tonumber(args[5]) end
if not finite(interval) or interval < 0.05 then error("Interval must be finite and at least 0.05 seconds", 0) end
local function connect(name, kind, methods)
    local device = peripheral.wrap(name)
    if not device then error("No peripheral named " .. name, 0) end
    if peripheral.getType(name) ~= kind then error(name .. " must be " .. kind, 0) end
    for _, method in ipairs(methods) do
        if type(device[method]) ~= "function" then error(name .. " lacks method " .. method, 0) end
    end
    return device
end
local panel = connect(panelName, "bwr_panel", { "list", "getSize", "clear", "drawText", "fill", "setPalette" })
local reactor = connect(reactorName, "bwr_reactor", { "getStatus" })
local function call(device, name, method, ...)
    local ok, first, second = pcall(device[method], ...)
    if not ok then error(name .. "." .. method .. ": " .. tostring(first), 0) end
    return first, second -- Drawing booleans mean changed; false is not an error.
end
local listed = call(panel, panelName, "list")
local entry = type(listed) == "table" and listed[label]
if type(entry) ~= "table" then error("No panel component labelled " .. label .. "; nothing drawn", 0) end
if entry.type ~= "bwr:graphic_display" then error(label .. " must be a graphic display; nothing drawn", 0) end
local width, height = call(panel, panelName, "getSize", label)
if not finite(width) or not finite(height) or width < 64 or height < 64 then
    error(label .. " needs at least 64x64 pixels; nothing drawn", 0)
end
local function draw(method, ...)
    return call(panel, panelName, method, label, ...)
end
local function text(y, value, color)
    draw("drawText", 2, y, value:sub(1, math.floor((width - 4) / 6)), color or 0)
end

local frame, previous = 0, nil
while not count or frame < count do
    local status = call(reactor, reactorName, "getStatus")
    if type(status) ~= "table" or type(status.formed) ~= "boolean" then error("Invalid reactor status", 0) end
    local lines, bar, color = { "BWR LIVE" }, 0, 5
    if status.formed then
        if not finite(status.power) or not finite(status.pressure) or not finite(status.level)
            or type(status.scram) ~= "boolean" then error("Invalid formed-reactor readings", 0) end
        lines[2] = string.format("P %.1f%%", status.power * 100)
        lines[3] = string.format("PSI %.0f", status.pressure)
        lines[4] = string.format("IN %.1f", status.level)
        lines[5] = status.scram and "SCRAM YES" or "SCRAM NO"
        bar = math.floor(math.max(0, math.min(1, status.power)) * (width - 4))
        color = status.scram and 14 or 5
    else
        lines[2], color = "UNFORMED", 14
    end
    local signature = table.concat(lines, "\n") .. ":" .. bar .. ":" .. color
    if frame == 0 then
        draw("setPalette", 0, 0xF0F0F0)
        draw("setPalette", 5, 0x7FCC19)
        draw("setPalette", 14, 0xCC4C4C)
        draw("setPalette", 15, 0x111111)
    end
    if signature ~= previous then draw("clear", 15) end
    for i, line in ipairs(lines) do text(2 + (i - 1) * 10, line, i == 5 and color or 0) end
    if bar > 0 then draw("fill", 2, height - 8, bar, 4, color) end
    previous = signature
    frame = frame + 1
    if not count or frame < count then sleep(interval) end
end
print("Updated " .. label .. " for " .. frame .. " frame(s).")
