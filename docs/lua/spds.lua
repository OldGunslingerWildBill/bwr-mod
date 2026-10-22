-- Player's own automation, not part of the mod.
-- Usage: spds <panel-name> <reactor-name> <plant-computer-label> [once|frame_count] [interval_seconds=1]
-- Example: spds bwr_panel_0 bwr_reactor_0 PC-1
-- A safety parameter display (SPDS) for a plant computer ("bwr:plant_computer") on a control panel. Wire both named
-- peripherals to the computer, place the plant computer on the panel, set its Display to "CC program" in the wrench, and
-- give the label it has (the panel's plantList() names them). The display needs a grid of at least 24 x 12 characters.
-- Page 1 SAFETY: the six critical safety functions as green / amber / red tiles, the reason for the first one that is not
--   green, and a power trend. Page 2 TRENDS: power, level, pressure and containment pressure. Page 3 VALUES: the numbers.
-- Touching the soft keys SAFETY, TRENDS or VALUES (plant_computer_touch) shows that page.
-- Without once/count this runs until Ctrl-T. A count is a positive integer; interval is at least 0.05 seconds.
--
-- It reads the reactor and writes a display; it issues no plant commands and does not need any. The limits below are THIS
-- EXAMPLE'S choices of when a function is amber or red: the mod judges nothing, so change them to your plant's procedures.
-- On an error or termination the last picture stays, and nothing marks it stale: this is a display, not an alarm system.

local args = { ... }
if #args < 3 or #args > 5 then
    error("Usage: spds <panel-name> <reactor-name> <plant-computer-label> [once|count] [interval=1]", 0)
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

-- When a safety function is amber or red. Edit these.
local LIMITS = {
    shutdownNeutron = 0.05,   -- scrammed, and neutron power (fraction of rated) still above this: reactivity not controlled
    neutronWarn = 1.05,       -- not scrammed: neutron power above this is amber ...
    neutronAlarm = 1.20,      -- ... and above this red
    levelMarginOk = 120,      -- inches of indicated level above the top of active fuel for green inventory ...
    levelMarginWarn = 20,     -- ... amber down to this margin, red below it
    cladWarn = 800,           -- peak cladding temperature, degC
    cladAlarm = 1204,
    pressureWarn = 1100,      -- dome pressure, psig
    pressureAlarm = 1250,
    cooldownWarn = 55.6,      -- vessel cooldown rate, degC per hour (100 degF an hour)
    stressAlarm = 1.0,        -- fraction of the cooldown thermal stress life used
    containmentWarn = 2,      -- containment pressure, psig
    containmentAlarm = 15,
    h2Warn = 4,               -- containment hydrogen, percent
    h2Alarm = 6,
    sinkPowerFloor = 0.01,    -- below this fraction of rated power there is next to no heat to remove
}

local function connect(name, kind, methods)
    local device = peripheral.wrap(name)
    if not device then error("No peripheral named " .. name, 0) end
    if peripheral.getType(name) ~= kind then error(name .. " must be " .. kind, 0) end
    for _, method in ipairs(methods) do
        if type(device[method]) ~= "function" then error(name .. " lacks method " .. method, 0) end
    end
    return device
end
local panel = connect(panelName, "bwr_panel", { "plantList", "plantInfo", "plantClear", "plantSetPage", "plantTitle", "plantSet",
    "plantValue", "plantPush", "plantFlush" })
if not peripheral.hasType(panelName, "bwr_plant_computer") then error(panelName .. " is not a bwr_plant_computer", 0) end
local reactor = connect(reactorName, "bwr_reactor", { "getStatus", "getNeutronPower", "getSteamOutletFlow", "getTopOfActiveFuel" })
local function call(device, name, method, ...)
    local ok, first = pcall(device[method], ...)
    if not ok then error(name .. "." .. method .. ": " .. tostring(first), 0) end
    return first
end
local function display(method, ...) return call(panel, panelName, method, label, ...) end

local listed = call(panel, panelName, "plantList")
if type(listed) ~= "table" or type(listed[label]) ~= "table" then error("No plant computer labelled " .. label .. "; nothing drawn", 0) end
local info = display("plantInfo")
if info.mode ~= "program" then
    error(label .. " shows a built-in page (" .. tostring(info.mode) .. "); set its Display to the CC program in the wrench", 0)
end
local cols, rows = info.cols, info.rows
if cols < 24 or rows < 12 then
    error(label .. " is " .. cols .. " x " .. rows .. " characters; this program needs at least 24 x 12", 0)
end
local taf = call(reactor, reactorName, "getTopOfActiveFuel")
if not finite(taf) then error("Invalid top of active fuel", 0) end

-- ---- the pages

local FUNCTIONS = { "REACTIVITY", "CORE COOLING", "HEAT SINK", "RPV INTEGRITY", "CONTAINMENT", "INVENTORY" }
local tileW = math.floor(cols / 3)
local tileH = rows >= 15 and 3 or 2
local function put(page, id, spec) display("plantSet", page, id, spec) end
local function keys(page)
    put(page, "k1", { type = "key", key = 1, text = "SAFETY" })
    put(page, "k2", { type = "key", key = 2, text = "TRENDS" })
    put(page, "k3", { type = "key", key = 3, text = "VALUES" })
end

local function build()
    display("plantClear")
    -- page 1: the safety functions
    display("plantTitle", 1, "SAFETY FUNCTIONS")
    for i, name in ipairs(FUNCTIONS) do
        local column, line = (i - 1) % 3, math.floor((i - 1) / 3)
        put(1, "csf" .. i, { type = "tile", col = 1 + column * tileW, row = 2 + line * tileH,
            w = column == 2 and cols - 2 * tileW or tileW, h = tileH, text = name, state = "off" })
    end
    local messageRow = 2 + 2 * tileH
    put(1, "msg", { type = "text", col = 1, row = messageRow, text = "WAITING FOR DATA", color = "grey" })
    local trendRow = messageRow + 1
    if rows - trendRow >= 3 then
        put(1, "pwr1", { type = "trend", col = 1, row = trendRow, w = cols, h = rows - trendRow, text = "POWER", unit = "%",
            min = 0, max = 120, decimals = 1, color = "cyan" })
    end
    keys(1)
    -- page 2: trends, two by two when the grid is tall enough, else one under the other
    display("plantTitle", 2, "TRENDS")
    local halfW, halfH = math.floor(cols / 2), math.floor((rows - 2) / 2)
    local trends = {
        { "pwr", "POWER", "%", 0, 120, 1 }, { "lvl", "LEVEL", "in", -200, 100, 1 },
        { "prs", "PRESSURE", "psig", 0, 1400, 0 }, { "cnt", "CONT PRESS", "psig", 0, 60, 1 },
    }
    for i, t in ipairs(trends) do
        local column, line = (i - 1) % 2, math.floor((i - 1) / 2)
        put(2, t[1], { type = "trend", col = 1 + column * halfW, row = 2 + line * halfH,
            w = column == 1 and cols - halfW or halfW, h = halfH, text = t[2], unit = t[3], min = t[4], max = t[5],
            decimals = t[6], color = "cyan" })
    end
    keys(2)
    -- page 3: the numbers, coloured by the same limits
    display("plantTitle", 3, "VALUES")
    put(3, "power", { type = "field", col = 1, row = 2, w = cols, text = "POWER", unit = "%", warnHigh = 105, alarmHigh = 120 })
    put(3, "neutron", { type = "field", col = 1, row = 3, w = cols, text = "NEUTRON POWER", unit = "%",
        warnHigh = LIMITS.neutronWarn * 100, alarmHigh = LIMITS.neutronAlarm * 100 })
    put(3, "level", { type = "bar", col = 1, row = 4, w = cols, text = "LEVEL", unit = "in", min = -200, max = 100,
        warnLow = taf + LIMITS.levelMarginOk, alarmLow = taf + LIMITS.levelMarginWarn })
    put(3, "pressure", { type = "bar", col = 1, row = 5, w = cols, text = "PRESSURE", unit = "psig", min = 0, max = 1400,
        decimals = 0, warnHigh = LIMITS.pressureWarn, alarmHigh = LIMITS.pressureAlarm })
    put(3, "clad", { type = "field", col = 1, row = 6, w = cols, text = "CLAD TEMP", unit = "C", decimals = 0,
        warnHigh = LIMITS.cladWarn, alarmHigh = LIMITS.cladAlarm })
    put(3, "cont", { type = "field", col = 1, row = 7, w = cols, text = "CONT PRESS", unit = "psig",
        warnHigh = LIMITS.containmentWarn, alarmHigh = LIMITS.containmentAlarm })
    put(3, "h2", { type = "field", col = 1, row = 8, w = cols, text = "CONT H2", unit = "%",
        warnHigh = LIMITS.h2Warn, alarmHigh = LIMITS.h2Alarm })
    put(3, "cool", { type = "field", col = 1, row = 9, w = cols, text = "COOLDOWN", unit = "C/h", warnHigh = LIMITS.cooldownWarn })
    keys(3)
    display("plantSetPage", 1)
end

-- ---- the safety functions

local RANK = { off = 0, ok = 1, warn = 2, alarm = 3 }
-- Each returns the state of the function and, when it is not ok, a short reason.
local function worse(state, reason, otherState, otherReason)
    if RANK[otherState] > RANK[state] then return otherState, otherReason end
    return state, reason
end

local function safetyFunctions(s, neutron, steamOut)
    local result = {}
    -- 1 reactivity control: shut down after a scram, and not running away otherwise
    if s.scram then
        result[1] = neutron > LIMITS.shutdownNeutron and { "alarm", "NOT SHUT DOWN" } or { "ok" }
    elseif neutron > LIMITS.neutronAlarm then result[1] = { "alarm", "NEUTRON POWER HIGH" }
    elseif neutron > LIMITS.neutronWarn then result[1] = { "warn", "POWER ABOVE RATED" }
    else result[1] = { "ok" } end
    -- 2 core cooling: fuel not uncovered and not hot
    local state, reason = "ok", nil
    if s.level < taf then state, reason = "alarm", "LEVEL BELOW FUEL" end
    state, reason = worse(state, reason, s.peakCladTemp >= LIMITS.cladAlarm and "alarm" or s.peakCladTemp >= LIMITS.cladWarn and "warn" or "ok",
        "CLAD TEMP HIGH")
    result[2] = { state, reason }
    -- 3 heat sink: the heat made has somewhere to go (steam to the turbine side, or shutdown cooling)
    if s.power < LIMITS.sinkPowerFloor or steamOut > 1 or s.shutdownCoolingMW > 1 then result[3] = { "ok" }
    elseif s.pressure >= LIMITS.pressureWarn then result[3] = { "alarm", "NO SINK, PRESSURE UP" }
    else result[3] = { "warn", "NO STEAM OR RHR FLOW" } end
    -- 4 RPV integrity: pressure, cooldown rate and thermal stress inside their limits
    state, reason = "ok", nil
    state, reason = worse(state, reason, s.pressure >= LIMITS.pressureAlarm and "alarm" or s.pressure >= LIMITS.pressureWarn and "warn" or "ok",
        "PRESSURE HIGH")
    state, reason = worse(state, reason, s.cooldownRateCPerHour > LIMITS.cooldownWarn and "warn" or "ok", "COOLDOWN RATE HIGH")
    state, reason = worse(state, reason, (s.cooldownThermalStress >= LIMITS.stressAlarm or s.interfacingSystemsLoca) and "alarm" or "ok",
        "STRESS OR ISLOCA")
    result[4] = { state, reason }
    -- 5 containment: pressure and hydrogen
    state, reason = "ok", nil
    state, reason = worse(state, reason, s.containmentPressure >= LIMITS.containmentAlarm and "alarm" or s.containmentPressure >= LIMITS.containmentWarn and "warn" or "ok",
        "PRESSURE HIGH")
    state, reason = worse(state, reason, s.containmentHydrogenPercent >= LIMITS.h2Alarm and "alarm" or s.containmentHydrogenPercent >= LIMITS.h2Warn and "warn" or "ok",
        "HYDROGEN HIGH")
    result[5] = { state, reason }
    -- 6 inventory: indicated level well above the top of active fuel
    local margin = s.level - taf
    if margin < LIMITS.levelMarginWarn then result[6] = { "alarm", "LEVEL VERY LOW" }
    elseif margin < LIMITS.levelMarginOk then result[6] = { "warn", "LEVEL LOW" }
    else result[6] = { "ok" } end
    return result
end

local function setValue(page, id, value) display("plantValue", page, id, value) end

local function frame()
    local s = call(reactor, reactorName, "getStatus")
    if type(s) ~= "table" or type(s.formed) ~= "boolean" then error("Invalid reactor status", 0) end
    if not s.formed then
        for i = 1, #FUNCTIONS do setValue(1, "csf" .. i, "off") end
        display("plantValue", 1, "msg", "REACTOR NOT FORMED")
        for _, id in ipairs({ "power", "neutron", "level", "pressure", "clad", "cont", "h2", "cool" }) do setValue(3, id, nil) end
        display("plantFlush")
        return
    end
    for _, key in ipairs({ "power", "pressure", "level", "peakCladTemp", "containmentPressure", "containmentHydrogenPercent",
        "shutdownCoolingMW", "cooldownRateCPerHour", "cooldownThermalStress" }) do
        if not finite(s[key]) then error("Invalid formed-reactor reading " .. key, 0) end
    end
    local neutron = call(reactor, reactorName, "getNeutronPower")
    local steamOut = call(reactor, reactorName, "getSteamOutletFlow")
    if not finite(neutron) or not finite(steamOut) then error("Invalid reactor readings", 0) end
    local functions = safetyFunctions(s, neutron, steamOut)
    local message = "ALL SAFETY FUNCTIONS OK"
    local worst = "ok"
    for i, f in ipairs(functions) do
        setValue(1, "csf" .. i, f[1])
        if RANK[f[1]] > RANK[worst] then
            worst = f[1]
            message = FUNCTIONS[i] .. ": " .. tostring(f[2])
        end
    end
    display("plantValue", 1, "msg", string.sub(message, 1, cols))
    -- the trends: one point a frame
    local powerPercent = s.power * 100
    display("plantPush", 1, "pwr1", powerPercent)
    display("plantPush", 2, "pwr", powerPercent)
    display("plantPush", 2, "lvl", s.level)
    display("plantPush", 2, "prs", s.pressure)
    display("plantPush", 2, "cnt", s.containmentPressure)
    -- the numbers
    setValue(3, "power", powerPercent)
    setValue(3, "neutron", neutron * 100)
    setValue(3, "level", s.level)
    setValue(3, "pressure", s.pressure)
    setValue(3, "clad", s.peakCladTemp)
    setValue(3, "cont", s.containmentPressure)
    setValue(3, "h2", s.containmentHydrogenPercent)
    setValue(3, "cool", s.cooldownRateCPerHour)
    display("plantFlush")
end

-- ---- run

build()
local shown = 0
local timer = nil
if count == 1 then
    frame()
    shown = 1
else
    frame()
    shown = 1
    timer = os.startTimer(interval)
end
while not count or shown < count do
    local event, a, b, c, d = os.pullEvent()
    if event == "timer" and a == timer then
        frame()
        shown = shown + 1
        timer = os.startTimer(interval)
    elseif event == "plant_computer_touch" and a == panelName and b == label and d >= 1 and d <= 3 then
        -- c is the page that was shown, d the soft key: the keys of every page are the three pages
        display("plantSetPage", d)
    end
end
print("Updated " .. label .. " for " .. shown .. " frame(s).")
