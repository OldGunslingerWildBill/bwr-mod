# Steam stop and turbine control valves

Two placeable, Blender-authored blocks control the physical steam supply:

| Block | Manual controls | Full actuator stroke |
| --- | --- | --- |
| Steam Stop Valve | Open / Close | 0.5 seconds |
| Turbine Steam Control Valve | 0–100%, in 0.1% increments | 2 seconds |

Both occupy one block and connect to **High-Pressure Steam Pipe** through the
two opposing flanges. The top actuator and side faces are not pipe ports;
water pipes cannot connect. Rotate the placement by facing along the pipe run.
The valve can pass steam in either direction along its horizontal axis.
New valves start closed. Right-click for a panel, or attach a CC:Tweaked modem.
Both recipes use iron and a steam pipe; the stop valve uses redstone, and the
control valve uses a comparator. Both are in the Realistic BWR creative tab.

## One control valve for a turbine plant

```text
RPV steam outlet -> stop valve -> control valve -> common main-steam header
                                                  |              |
                                                 HP             HP
                                                  |              |
                                                  +-- crossover -+
                                                       | | | |
                                                      LP LP LP LP
```

All branches intended to share the control must be downstream of that valve.
A pipe physically bypassing a closed valve remains an open steam path.
HP exhaust feeds LP inlets; shaft alignment joins mechanical work, not steam.
LP exhaust now enters a matching condenser below each LP section. Take water
from the condenser’s condensate flange; generator FE connections are unchanged.

HP/LP panels now show operating data. They have no separate admission setting
or Start/Stop button. Sections expand available steam when their shaft has
generator capacity and their outlets have room. Use the stop valve to isolate
the supply and the control valve to regulate it. Trapped downstream steam can
finish expanding after closure, and shaft speed then coasts down.

The valve opening scales available steam supply, rather than requesting a
fixed electrical output. A 25% valve on one common header permits 25% of its
unthrottled source supply, shared by the attached HP sections. Actual power
also depends on steam pressure, shaft speed, exhaust room and generator load.
The shared per-tick allowances prevent two consumers from taking the same
valve capacity or steam twice. Nozzle discharge uses the downstream opening
once; turbine intake does not apply the same percentage again.

The first hydraulic model uses the narrowest opening on a route and the most
open available route through a loop. It does not sum parallel valve conductance
or model Cv, pipe pressure loss or steam-line inventory. Nozzle discharge can
still exceed what downstream machines accept when their buffers are full;
manage supply with the physical valves. No automatic trip logic is included.

## ComputerCraft

```lua
local stop = peripheral.find("bwr_steam_stop_valve")
local control = peripheral.find("bwr_turbine_control_valve")
stop.open()
control.setPosition(0.653) -- 65.3%; fraction from 0 to 1
print(textutils.serialize(control.getStatus()))
-- stop.close() isolates the common main-steam header
```

Both types provide `open()`, `close()`, `setPosition(fraction)` and `getStatus()`.
Stop valves accept only 0 or 1. Status contains `target`, actual `position`,
`turbineSteamKgPerS` (metered main-turbine/Mekanism export flow) and `stopValve`.
Actuator positions and targets persist through saves. Commands are manual;
the most recent panel or computer command wins.

**Migration:** local HP/LP `setAdmission` and `setRunning` methods are removed.
Old saved local admission/running settings are ignored; steam and energy
inventories are preserved. Existing directly piped turbines run from their
available supply. Add the upstream valves before operating the updated plant.
The older MSIV remains available and continues to restrict steam paths.

## Assets and verification

- Editable source: `art/models/steam_valves/steam_valves.blend`
- Blender authoring: `art/models/steam_valves/build_models.py`
- Export/check: `python tools-export-steam-valves.py [--check]`
- Server regression suite: `SteamValveRuntimeCheck` through
  `:mod:runTurbineGameTest`
- Actual client geometry and panel checks:
  `:mod:runTurbineModelCheck -PbwrPowerPanelCheck`

The mod display name is **Realistic BWR**. `modId=bwr` is preserved for saved
worlds and item IDs. `mod/src/main/resources/logo.png` contains the user's
uploaded artwork, converted to PNG without cropping, resizing or pixel edits.
