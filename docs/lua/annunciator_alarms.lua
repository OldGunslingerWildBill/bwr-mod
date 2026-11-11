-- Player's own automation, not part of the mod.
-- Usage: annunciator_alarms <panel-name> <reactor-name> <level-address> <bus-address> [once|count] [interval=1]
-- Example: annunciator_alarms bwr_panel_0 bwr_reactor_0 101 102
-- Drives two annunciator lights by their address. A light is an annunciator
-- window whose "Light address" field (wrench screen, 0 to 10000) is that
-- number; several windows may share an address and are then driven together.
--   <level-address>  RPV LEVEL LOW: the reactor's indicated level is below LOW_LEVEL_IN
--   <bus-address>    BUS UNDERVOLTAGE: no redstone signal on BUS_HEALTHY_SIDE
-- Each window must be unwired (the first setAlarm wires it to CC) or already
-- wired to CC, and keep its default trigger (alarm when the input is above 0.5),
-- because setAlarm writes 1 or 0. For a window with its own setpoint, send the
-- measured number with setValue(address, number) instead.
-- The window runs its own alarm sequence: a new alarm flashes and sounds the horn
-- until someone presses ACK (SILENCE stops the horn only). This program reports
-- the two conditions once per interval and never acknowledges anything.
-- A computer has no bus voltage to read: give BUS_HEALTHY_SIDE a redstone signal
-- that is on while the bus is energised (an undervoltage relay contact).
-- It reads the reactor and writes two lights; it issues no plant commands.
-- Without once/count this runs until Ctrl-T; the lights keep their last state.

local LOW_LEVEL_IN = 12.5       -- inches on the instrument-zero scale: your own low-level setpoint
local BUS_HEALTHY_SIDE = "back" -- redstone on this side of the computer = bus healthy

local args = { ... }
if #args < 4 or #args > 6 then
    error("Usage: annunciator_alarms <panel-name> <reactor-name> <level-address> <bus-address> [once|count] [interval=1]", 0)
end
local panelName, reactorName = args[1], args[2]
local function address(text, what)
    local value = tonumber(text)
    if not value or value % 1 ~= 0 or value < 0 or value > 10000 then
        error(what .. " address must be a whole number from 0 to 10000", 0)
    end
    return value
end
local levelAddress, busAddress = address(args[3], "Level"), address(args[4], "Bus")
local count = args[5] == "once" and 1 or (args[5] and tonumber(args[5]) or nil)
if args[5] and (not count or count < 1 or count % 1 ~= 0) then
    error("Count must be once or a positive whole number", 0)
end
local interval = 1
if args[6] ~= nil then interval = tonumber(args[6]) end
if not interval or interval ~= interval or interval < 0.05 or interval == math.huge then
    error("Interval must be finite and at least 0.05 seconds", 0)
end

local function connect(name, kind, methods)
    local device = peripheral.wrap(name)
    if not device then error("No peripheral named " .. name, 0) end
    if peripheral.getType(name) ~= kind then error(name .. " must be " .. kind, 0) end
    for _, method in ipairs(methods) do
        if type(device[method]) ~= "function" then error(name .. " lacks method " .. method, 0) end
    end
    return device
end
local panel = connect(panelName, "bwr_panel", { "setAlarm", "getAlarm" })
local reactor = connect(reactorName, "bwr_reactor", { "getWaterLevel" })
local function light(method, ...)
    local ok, result = pcall(panel[method], ...)
    if not ok then error(panelName .. "." .. method .. ": " .. tostring(result), 0) end
    return result
end

-- Both lights must exist before either is written: getAlarm raises the panel's
-- own error for an address no window has.
light("getAlarm", levelAddress)
light("getAlarm", busAddress)

local done = 0
while true do
    -- A level that cannot be read (the reactor is not formed) is shown as low:
    -- an alarm that cannot be judged must not stay dark.
    local ok, level = pcall(reactor.getWaterLevel)
    local low = not ok or type(level) ~= "number" or level < LOW_LEVEL_IN
    local undervoltage = not redstone.getInput(BUS_HEALTHY_SIDE)
    light("setAlarm", levelAddress, low)
    light("setAlarm", busAddress, undervoltage)
    -- "off", "alarm" (new, flashing) or "acknowledged" (steady): what each light shows now.
    print(("level %s, bus %s"):format(light("getAlarm", levelAddress), light("getAlarm", busAddress)))
    done = done + 1
    if count and done >= count then break end
    sleep(interval)
end
