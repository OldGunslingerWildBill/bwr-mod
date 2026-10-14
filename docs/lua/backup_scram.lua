-- This is the player's own automation, not part of the mod's control logic.
-- backup_scram <reactor_name> <max_power_fraction> <max_pressure_psig> <min_level_in> <poll_s> <max_polls>
-- Choose all thresholds for YOUR in-game plant; this example supplies none.
-- Power is total power/rated (1 = 100%); pressure is psig; level is the indicated
-- instrument scale in inches, not depth above the vessel floor or top of fuel.
-- The player's rule is OR: power >= maximum, pressure >= maximum, or level <= minimum.
-- It sends at most one scram, then exits. It never resets scram or starts ECCS.
-- max_polls is mandatory: after it expires this program STOPS MONITORING.
-- Missing/unformed hardware or invalid/failed telemetry stops with an error.
-- No reconnect, implicit threshold, or fallback action is hidden in this example.
-- A latched scram is a command acknowledgement, not proof every rod inserted;
-- accumulator charge and drive condition still determine physical insertion.

local args = { ... }
local function fail(message)
  error("backup_scram: " .. message, 0)
end

local function finite(value)
  return type(value) == "number" and value == value
    and value ~= math.huge and value ~= -math.huge
end

local function numberArg(index, label)
  local value = tonumber(args[index])
  if not finite(value) then fail(label .. " must be a finite number") end
  return value
end

if #args ~= 6 or not args[1] or args[1] == "" then
  fail("usage: backup_scram <reactor_name> <max_power_fraction> <max_pressure_psig> <min_level_in> <poll_s> <max_polls>")
end
local name = args[1]
local maxPower = numberArg(2, "max_power_fraction")
local maxPressure = numberArg(3, "max_pressure_psig")
local minLevel = numberArg(4, "min_level_in")
local poll = numberArg(5, "poll_s")
local maxPolls = numberArg(6, "max_polls")
if maxPower <= 0 or maxPressure <= 0 then fail("maximum power and pressure must be positive") end
if poll <= 0 then fail("poll_s must be positive") end
if maxPolls < 1 or maxPolls % 1 ~= 0 then fail("max_polls must be a positive integer") end

if not peripheral.isPresent(name) then fail("peripheral unavailable: " .. name) end
if not peripheral.hasType(name, "bwr_reactor") then fail(name .. " is not a bwr_reactor") end
local reactor = peripheral.wrap(name)
if not reactor then fail("peripheral unavailable: " .. name) end
for _, method in ipairs({ "getStatus", "scram" }) do
  if type(reactor[method]) ~= "function" then fail("required method unavailable: " .. method) end
end

local function call(method)
  local ok, result = pcall(reactor[method])
  if not ok then fail(method .. " failed: " .. tostring(result) .. "; monitoring has stopped") end
  return result
end

local function status()
  local value = call("getStatus")
  if type(value) ~= "table" or value.formed ~= true then fail("reactor is not formed; monitoring has stopped") end
  for _, key in ipairs({ "power", "pressure", "level" }) do
    if not finite(value[key]) then fail("invalid getStatus field: " .. key .. "; monitoring has stopped") end
  end
  if type(value.scram) ~= "boolean" then fail("invalid getStatus field: scram; monitoring has stopped") end
  return value
end

print(string.format("Player backup rule on %s: power >= %.4g, pressure >= %.4g psig, or level <= %.4g in",
  name, maxPower, maxPressure, minLevel))
print(string.format("Up to %d polls, %.3g s apart; monitoring ends on exit or Ctrl+T.", maxPolls, poll))
for sample = 1, maxPolls do
  local value = status()
  if value.scram then
    print("Scram is already active; no new command sent. Monitoring ends.")
    return
  end
  local reasons = {}
  if value.power >= maxPower then reasons[#reasons + 1] = "high total power" end
  if value.pressure >= maxPressure then reasons[#reasons + 1] = "high pressure" end
  if value.level <= minLevel then reasons[#reasons + 1] = "low indicated level" end
  if #reasons > 0 then
    print("Player rule triggered: " .. table.concat(reasons, ", "))
    call("scram")
    local after = status()
    if not after.scram then fail("scram command returned but latch was not confirmed; inspect the plant") end
    print("Scram latch confirmed; verify actual rod insertion and cooling. Monitoring ends.")
    return
  end
  if sample < maxPolls then sleep(poll) end
end
print("Poll limit reached without a trigger. Monitoring has stopped; no command was sent.")
