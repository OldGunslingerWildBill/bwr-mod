-- Player's own automation, not part of the mod.
-- Usage: eccs_start <pump-name> <pool|tank|boron_tank> [flow=1] [speed=1]
-- Example: eccs_start bwr_rhr_0 pool 0.35 0.6
-- Connect this exact pump by a wired modem or computer side. Build and supply
-- its physical suction/discharge, reactor connection and motor/steam drive.
-- RHR is deliberately put in INJECTION mode. SLC requires boron_tank; the
-- other supported pumps require pool or tank. This sends one start request;
-- it does not operate ADS, switch suction later, or guarantee delivered water.
-- Commands remain in effect after exit. Call stop()/releaseControl() yourself
-- when appropriate; errors do not roll back any command already sent.

local args = { ... }
if #args < 2 or #args > 4 then
    error("Usage: eccs_start <pump-name> <pool|tank|boron_tank> [flow=1] [speed=1]", 0)
end
local name, source = args[1], args[2]
local function fraction(text, fallback, label)
    local value = text == nil and fallback or tonumber(text)
    if not value or value ~= value or value < 0 or value > 1 then
        error(label .. " must be a finite number from 0 to 1", 0)
    end
    return value
end
local flow = fraction(args[3], 1, "Flow fraction")
local speed = fraction(args[4], 1, "Speed fraction")
local allowed = {
    bwr_lpcs = true, bwr_hpcs = true, bwr_rhr = true,
    bwr_rcic = true, bwr_hpci = true, bwr_slc = true,
}
local pump = peripheral.wrap(name)
if not pump then error("No peripheral named " .. name, 0) end
local kind = peripheral.getType(name)
if not allowed[kind] then error(name .. " is not a supported BWR ECCS pump", 0) end
if kind == "bwr_slc" then
    if source ~= "boron_tank" then error("SLC requires suction boron_tank", 0) end
elseif source ~= "pool" and source ~= "tank" then
    error("This pump requires suction pool or tank", 0)
end
local methods = { "setSuctionSource", "setFlow", "setSpeed", "start", "getStatus" }
if kind == "bwr_rhr" then methods[#methods + 1] = "setMode" end
for _, method in ipairs(methods) do
    if type(pump[method]) ~= "function" then
        error(name .. " lacks method " .. method .. "; no commands sent", 0)
    end
end
local function call(method, ...)
    local ok, result = pcall(pump[method], ...)
    if not ok then
        error(name .. "." .. method .. ": " .. tostring(result)
            .. ". Earlier commands may remain applied; inspect the pump.", 0)
    end
    return result -- These actuator methods return no value; nil is normal.
end
local initial = call("getStatus")
if type(initial) ~= "table" then error(name .. " returned invalid status; no commands sent", 0) end

call("setSuctionSource", source)
if kind == "bwr_rhr" then call("setMode", "injection") end
call("setFlow", flow)
call("setSpeed", speed)
call("start")
local status = call("getStatus")
if type(status) ~= "table" or status.running ~= true then
    error(name .. " did not report running after the start request; inspect its state", 0)
end
print(name .. ": start demand applied; suction=" .. source)
print(string.format("Requested flow %.2f, speed %.2f (fractions of rated)", flow, speed))
print("Current delivered flow: " .. tostring(status.flow) .. " kg/s")
print("Current shaft speed: " .. tostring(status.speed) .. "; mode=" .. tostring(status.mode))
print("This snapshot may show zero flow during spin-up or with insufficient drive, suction or head.")
