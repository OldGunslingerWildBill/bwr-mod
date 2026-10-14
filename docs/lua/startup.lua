-- This is the player's own automation, not part of the mod's control logic.
-- Invoke deliberately for ONE startup manoeuvre in your chosen rod sequence:
-- startup <reactor_name> <flow_fraction> <rod_id> <target_notch> <timeout_s> [poll_s]
-- Names come from peripheral.getNames(); the selected peripheral must be bwr_reactor.
-- Rod IDs are 1-based; labels are 00..48 in steps of 2. Flow is a fraction, 0..1.
-- Prepare fuel, CRD power/water, recirculation hardware/FE, steam and feedwater yourself.
-- This does not seek criticality or target power, reset a scram, or start the whole plant.
-- Completion confirms rod motion, not achievement of the requested hydraulic flow.
-- Timeout, Ctrl+T or an error does NOT cancel demands already sent to the hardware.

local args = { ... }
local function fail(message)
  error("startup: " .. message, 0)
end

local function finite(value)
  return type(value) == "number" and value == value
    and value ~= math.huge and value ~= -math.huge
end

local function numberArg(index, name)
  local value = tonumber(args[index])
  if not finite(value) then fail(name .. " must be a finite number") end
  return value
end

if #args < 5 or #args > 6 or not args[1] or args[1] == "" then
  fail("usage: startup <reactor_name> <flow_fraction> <rod_id> <target_notch> <timeout_s> [poll_s]")
end
local name = args[1]
local flow = numberArg(2, "flow_fraction")
local rod = numberArg(3, "rod_id")
local target = numberArg(4, "target_notch")
local timeout = numberArg(5, "timeout_s")
local poll = args[6] and numberArg(6, "poll_s") or 0.25
if flow < 0 or flow > 1 then fail("flow_fraction must be between 0 and 1") end
if rod < 1 or rod % 1 ~= 0 then fail("rod_id must be a positive integer") end
if target < 0 or target > 48 or target % 2 ~= 0 then
  fail("target_notch must be an even label from 0 to 48")
end
if timeout <= 0 or poll <= 0 then fail("timeout_s and poll_s must be positive") end

if not peripheral.isPresent(name) then fail("peripheral unavailable: " .. name) end
if not peripheral.hasType(name, "bwr_reactor") then fail(name .. " is not a bwr_reactor") end
local reactor = peripheral.wrap(name)
if not reactor then fail("peripheral unavailable: " .. name) end
for _, method in ipairs({ "getStatus", "getRodPosition", "getRodDemand", "getRodsNotAtDemand",
    "setRodPosition", "setRecirculationFlow" }) do
  if type(reactor[method]) ~= "function" then fail("required method unavailable: " .. method) end
end

local function call(method, ...)
  local ok, result = pcall(reactor[method], ...)
  if not ok then fail(method .. " failed: " .. tostring(result) .. "; check the plant locally") end
  return result
end

local function status()
  local value = call("getStatus")
  if type(value) ~= "table" or value.formed ~= true then fail("reactor is not formed") end
  for _, key in ipairs({ "power", "pressure", "level", "flow", "rodCount" }) do
    if not finite(value[key]) then fail("invalid getStatus field: " .. key) end
  end
  if value.rodCount < 1 or value.rodCount % 1 ~= 0 then fail("invalid rod count") end
  if type(value.scram) ~= "boolean" then fail("invalid getStatus field: scram") end
  if value.scram then fail("scram is active; this example never resets it") end
  if value.vessel ~= "shutdown" and value.vessel ~= "operating" then
    fail("vessel head is not closed")
  end
  return value
end

local function rodReading(method)
  local value = call(method, rod)
  if not finite(value) or value < 0 or value > 48 or value % 2 ~= 0 then
    fail("invalid " .. method .. " readout")
  end
  return value
end

local function rodsMoving()
  local count = call("getRodsNotAtDemand")
  if not finite(count) or count < 0 or count % 1 ~= 0 then fail("invalid rods-not-at-demand count") end
  return count
end

-- Finish all argument/method/plant/readout checks before sending either command.
local before = status()
if rod > before.rodCount then fail("rod_id exceeds this core's " .. before.rodCount .. " rods") end
local position = rodReading("getRodPosition")
local demand = rodReading("getRodDemand")
if rodsMoving() ~= 0 or position ~= demand then
  fail("existing rod demands have not settled; finish the earlier manoeuvre first")
end
print(string.format("%s: rod %d from %02d to %02d; recirculation request %.3f",
  name, rod, position, target, flow))
status() -- Refresh formation/head/scram after the preceding main-thread reads.
call("setRecirculationFlow", flow)
status() -- A failed second command must not be mistaken for a completed manoeuvre.
call("setRodPosition", rod, target)

local deadline = os.clock() + timeout
while true do
  local now = status()
  if rodReading("getRodDemand") ~= target then
    fail("selected rod demand changed; another control may be commanding it")
  end
  local actual = rodReading("getRodPosition")
  if actual == target and rodsMoving() == 0 then
    print(string.format("Rod %d settled at %02d. Requested flow %.3f; measured core flow %.3f.",
      rod, target, flow, now.flow))
    print("This manoeuvre ends here; inspect instruments before choosing another rod command.")
    return
  end
  local remaining = deadline - os.clock()
  if remaining <= 0 then
    fail("rod movement timed out; demands remain set, inspect CRD power/water and actual positions")
  end
  sleep(math.min(poll, remaining))
end
