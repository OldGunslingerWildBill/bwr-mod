# Player Lua examples

These six CC:Tweaked programs are the player's own automation, not part of the
mod. Nothing installs, starts, or runs them automatically. They call the same
peripherals available to a player in the current mod; no extra control API is
added by these examples.

## Installation and equipment names

Install CC:Tweaked separately. Copy the desired files to an `examples/` directory
on your in-game computer, preserving their `.lua` names. Use CC's editor or your
usual method of transferring files to that computer. In particular, keep this
`startup.lua` inside `examples/`: it is a plant manoeuvre run with arguments, not
an argument-free program to install as the computer's boot script.

Connect that computer to the intended machines using adjacency or enabled wired
modems. See [transport and computer connections](../../PLANT-TRANSPORT.md) for
which machine faces expose peripherals. Inspect the names on that computer:

```lua
for _, name in ipairs(peripheral.getNames()) do
  print(name, peripheral.getType(name))
end
```

Use those exact names in the commands below. A name such as `bwr_reactor_0` is an
example, not a guaranteed address. A direct connection may instead be `left`,
`right`, `top`, or another computer side. Each example requires named targets;
it does not choose the first reactor or pump it happens to discover.

Arguments are described in each script's header. Invalid arguments and missing
or incompatible peripherals produce errors. Plant commands already sent remain
in effect if a script exits, is terminated, or loses its peripheral connection.
Terminating a monitor stops its monitoring; it does not undo earlier commands.

## Startup manoeuvre

```text
examples/startup.lua <reactor_name> <flow_fraction> <rod_id> <target_notch> <timeout_s> [poll_s]
```

[startup.lua](startup.lua) sets the requested recirculation fraction, commands
one rod, and waits for its measured position and the rods-not-at-demand count.
Rod IDs are **1-based**, and notch labels are **0 through 48 in steps of 2**.
The flow argument is **0 through 1 of rated**, not kg/s. Default polling is
0.25 seconds. The timeout is required.

Prepare the closed, formed reactor, fuel, drive power/water, recirculation
hardware and its FE supply, steam path, and feedwater first. The script refuses
an active scram or unsettled earlier rod demands. It never resets scram or
selects the next rod. Repeat it for your chosen pattern while observing the
instruments. Measured core flow is printed separately; successful rod motion
does not establish that the pumps delivered the requested flow.

## Backup scram monitor

```text
examples/backup_scram.lua <reactor_name> <max_power_fraction> <max_pressure_psig> <min_level_in> <poll_s> <max_polls>
```

[backup_scram.lua](backup_scram.lua) reads `getStatus()` and sends one `scram()`
when **any** player-supplied limit is met: total power at or above the maximum,
pressure at or above the maximum, or level at or below the minimum. A power
fraction of 1 means 100% of rated total power, including decay heat. Level is
the indicated instrument scale in inches, not depth above the floor or fuel.

All limits, polling interval, and poll count are mandatory. **Monitoring stops**
after the poll count, on an error or termination, or after a scram is active.
The script confirms the scram latch, not physical insertion of every rod;
accumulator charge and drive condition still matter. It never resets the latch
or starts cooling equipment. Missing or invalid telemetry stops the program
with an error; the example contains no fallback action.

## ECCS start

```text
examples/eccs_start.lua <pump_name> <pool|tank|boron_tank> [flow_fraction=1] [speed_fraction=1]
```

[eccs_start.lua](eccs_start.lua) supports LPCS, HPCS, RHR, RCIC, HPCI, and SLC
peripherals. Both fractions are 0 through 1 of rated. It sets suction, selects
`injection` for an RHR train, sets flow and shaft-speed demands, and starts the
named pump. SLC requires `boron_tank`; the other pumps accept `pool` or `tank`.
Build the corresponding physical suction/discharge and supply the drive first.
Selecting suction here does not create a water source or pipe connection.

The result confirms the running command and prints current speed and delivered
flow. Flow may still be zero during spin-up, without a supplied drive or suction,
or when vessel pressure exceeds the pump's head. This program does not command
ADS or choose a later suction transfer. Demands remain set after exit. Use that
pump's `stop()` or `releaseControl()` deliberately when appropriate; releasing
control hands authority back to redstone.

## Containment isolation

```text
examples/containment_isolation.lua [--timeout seconds] <valve_name> [valve_name...]
```

[containment_isolation.lua](containment_isolation.lua) preflights every named
MSIV or containment-penetration peripheral before sending commands. It calls
`setOpen(false)` on each, handles a penetration's refusal separately from the
MSIV's normal no-value return, and waits for actual zero position and a closed
demand. The default timeout is 15 seconds; accepted values are 0 through 300.
A zero timeout makes one immediate readback without waiting for travel.

Refusal and timeout are errors, with other accepted close demands left in place.
The script never reopens valves. Name every valve you intend it to close; doors,
airlocks, wetwell vents, purge equipment, and unlisted valves are separate.
Closing the listed valves does not establish that the whole enclosure is sealed
or command a reactor scram.

## Panel display driver

```text
examples/panel_display.lua <panel_name> <reactor_name> <graphic_label> [once|frame_count] [interval_seconds=1]
```

[panel_display.lua](panel_display.lua) draws total power percent, pressure in
psig, indicated level in inches, scram state, and a power bar from `getStatus()`.
An unformed reactor gets an explicit `UNFORMED` display. Omit the frame count to
run until terminated, use `once` for one update, or supply a positive integer.
The minimum interval is 0.05 seconds. No sleep follows the final counted frame.

Place a graphic display at least **2×2 blocks / 64×64 pixels** on the panel and
give it a label in the wrench screen. Verify the current label using the panel's
`list()` after merging groups. Graphic drawing does not require a numeric CC
input binding. This program overwrites that display's pixels and palette
indices 0, 5, 14, and 15. It treats drawing methods' `false` return as unchanged,
not failure. See the [panel API](../../CONTROL-PANELS.md#4-cctweaked-reference) for the
underlying drawing and component methods.

The last picture remains if the program stops or loses a peripheral; the console
reports errors. The driver never commands the plant, and the picture is not an
independent alarm or freshness indication after its computer stops.

## Safety parameter display

```text
examples/spds.lua <panel_name> <reactor_name> <plant_computer_label> [once|frame_count] [interval_seconds=1]
```

[spds.lua](spds.lua) draws a plant computer ([PLANT-COMPUTER.md](../../PLANT-COMPUTER.md)) on a control panel: page 1 the six
critical safety functions (reactivity control, core cooling, heat sink, RPV integrity, containment, inventory) as green / amber /
red tiles, the reason for the first one that is not green, and a power trend; page 2 trends of power, level, pressure and
containment pressure; page 3 the numbers with their limits. Touching the SAFETY, TRENDS or VALUES soft key shows that page
(`plant_computer_touch`). The display must be set to the CC program in the wrench and have a grid of at least 24 x 12 characters
(a 4 x 3 block display is 31 x 15). Name the panel and the reactor as for the other examples; the third argument is the label of
the plant computer on the panel (`plantList()` names them). It updates in one coroutine and answers touches in another, so a touch is
not lost while a frame is being drawn (every call to the display takes a server tick).

The limits that make a function amber or red are a table at the top of the file and are this example's choices, not the mod's:
the mod judges nothing. The program reads the reactor and writes a display; it issues no plant commands, and the picture is not
an independent alarm (it stays as it was if the program stops).

## Scope of these examples

Startup demonstrates one explicit recirculation/rod manoeuvre in the player's
chosen startup sequence. Backup scram applies player-supplied limits while the
program is running. ECCS start commands one named train. Containment isolation
closes named valves and waits for their actual travel. The panel driver and the SPDS only
read the reactor and write a display.

Choose settings and equipment for your own in-game build. These examples do not
establish a complete operating procedure or prove a whole plant's readiness.
The mod continues to supply measurements and actuators; all decisions in these
files belong to the player who installs and runs them.

## Developer execution check

From the repository root, using the project's Java 21 installation:

```powershell
.\gradlew.bat :mod:runLuaExamplesCheck --offline --console=plain
```

This opt-in dedicated-server check loads these exact files onto a real
registered CC:Tweaked ServerComputer and executes them with the real Lua
runtime and peripheral
dispatch. It attaches actual BWR capability objects to computer sides, exercises
successful operations and failures, and verifies block-entity readbacks. It
does not substitute a hand-written Lua peripheral API or validate cable-network
construction. The harness owns this runtime and its lifetime; no physical
computer block ticker competes for the direct side bindings. Its 29 scenarios
include a separate real computer block with repeatedly invalidated native sides,
verifying that native scans cannot erase the ECCS attachment. Script names and
source hashes appear in the check's output.

The development harness is stripped from the production JAR. It does not install
these examples into a player's world. Results and limits are recorded in the
[L5 review report](../../PHASE-L5-REVIEW.md).

## Reading the area radiation monitor

An area radiation monitor is a peripheral of type `bwr_radiation_monitor` on any of its faces (see
[RADIATION.md](../../RADIATION.md) for the units and where the numbers come from). Dose rates are in **mSv/h**:

```lua
local monitor = peripheral.wrap("bwr_radiation_monitor_0")   -- your own name, see the listing above
local s = monitor.getStatus()
print(s.doseRate, s.unit, s.highRadiation, s.alert, s.alarmSetpoint)
print(s.airborne, s.deposited, s.release, s.core)             -- where the dose rate comes from
monitor.setAlarmSetpoint(5)                                   -- mSv/h; its own lamp and outputs only
```

`getDoseRate()`, `isHighRadiation()`, `isAlert()`, `getAlarmSetpoint()` and `getAlertSetpoint()` read one value each. The
monitor changes nothing in the plant; any trip or isolation on high radiation is a program you write.
