-- Player's own automation, not part of the mod.
-- Usage: containment_isolation [--timeout seconds] <valve-name> [valve-name...]
-- Example: containment_isolation --timeout 15 bwr_msiv_0 bwr_containment_penetration_0
-- Wire these exact MSIV/penetration peripherals to this computer. All targets
-- are checked before commands; no peripheral is discovered or selected for you.
-- Only these named valves are closed. Doors, vents, purge equipment and other
-- penetrations are untouched; this does not prove the whole room is sealed.
-- A true penetration result means accepted, not finished. MSIV setOpen returns
-- no value. Readback below waits for actual travel, with a bounded timeout.
-- Demands remain applied after success/error; this script never reopens valves.

local args = { ... }
local timeout, first = 15, 1
if args[1] == "--timeout" then
    timeout, first = tonumber(args[2]), 3
end
if not timeout or timeout ~= timeout or timeout < 0 or timeout > 300 or #args < first then
    error("Usage: containment_isolation [--timeout seconds (0..300)] <valve-name> [valve-name...]", 0)
end
local targets, names = {}, {}
local function read(target)
    local ok, status = pcall(target.device.getStatus)
    if not ok then error(target.name .. ".getStatus: " .. tostring(status), 0) end
    if type(status) ~= "table" or type(status.demandOpen) ~= "boolean" then
        error(target.name .. " returned an invalid demand status", 0)
    end
    local position = target.kind == "bwr_msiv" and status.position or status.isolationPosition
    if type(position) ~= "number" or position ~= position or position < 0 or position > 1 then
        error(target.name .. " returned an invalid valve position", 0)
    end
    if target.kind == "bwr_containment_penetration" and type(status.ruptured) ~= "boolean" then
        error(target.name .. " returned an invalid rupture status", 0)
    end
    return status, position
end
for i = first, #args do
    local name = args[i]
    if names[name] then error("Duplicate target " .. name .. "; no commands sent", 0) end
    names[name] = true
    local device = peripheral.wrap(name)
    if not device then error("No peripheral named " .. name .. "; no commands sent", 0) end
    local kind = peripheral.getType(name)
    if kind ~= "bwr_msiv" and kind ~= "bwr_containment_penetration" then
        error(name .. " must be an MSIV or containment penetration; no commands sent", 0)
    end
    for _, method in ipairs({ "setOpen", "getStatus" }) do
        if type(device[method]) ~= "function" then
            error(name .. " lacks method " .. method .. "; no commands sent", 0)
        end
    end
    local target = { name = name, kind = kind, device = device }
    read(target) -- Check availability/status before changing any named valve.
    targets[#targets + 1] = target
end

local failures = {}
for _, target in ipairs(targets) do
    local ok, accepted = pcall(target.device.setOpen, false)
    if not ok then
        failures[#failures + 1] = target.name .. ": " .. tostring(accepted)
    elseif target.kind == "bwr_containment_penetration" and accepted ~= true then
        failures[#failures + 1] = target.name .. ": close refused (check valve service and rupture)"
    else
        print(target.name .. ": close demand sent")
    end
end
if #failures > 0 then
    error("Isolation incomplete: " .. table.concat(failures, "; ")
        .. ". Other accepted close demands remain applied.", 0)
end

local deadline = os.clock() + timeout
while true do
    local pending = {}
    for _, target in ipairs(targets) do
        local status, position = read(target)
        if status.demandOpen or position > 0 or status.ruptured == true then
            pending[#pending + 1] = target.name .. " (position=" .. tostring(position)
                .. ", demandOpen=" .. tostring(status.demandOpen)
                .. (status.ruptured == true and ", ruptured" or "") .. ")"
        end
    end
    if #pending == 0 then
        print("Confirmed closed: " .. #targets .. " named valves. Whole-room sealing is a separate check.")
        break
    end
    local remaining = deadline - os.clock()
    if remaining <= 0 then
        error("Isolation timed out: " .. table.concat(pending, "; ") .. ". Close demands remain applied.", 0)
    end
    sleep(math.min(0.25, remaining))
end
