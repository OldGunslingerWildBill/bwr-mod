# Modular main turbines and generator

Three new placeable objects are modeled in Blender, inspired by Toshiba's
TX-10 nuclear steam turbine and TX-NLCH generator. These are game-scale exterior
interpretations, not CAD replicas or manufacturer performance simulations.

| Item | Width x height x length | Connections | Game rating |
| --- | --- | --- | --- |
| TX-10 Style HP Turbine | 5 x 5 x 9 | Top steam inlet; left HP steam exhaust; two end shafts | 2,200 kg/s |
| TX-10 Style LP Turbine | 7 x 5 x 7 | Top crossover steam inlet; left water outlet; two end shafts | 750 kg/s |
| TX-NLCH Style Nuclear Generator | 5 x 5 x 9 | Two end shafts; copper FE terminal on right | 1,500 MW electrical |

Left/right are relative to a module's front. All shaft centers are 2.5 blocks
above the placement floor. Each item reserves its full footprint, creates one
simulation entity, and dismantles as one object when any part is broken. The
placement anchor is the center cell of the floor. Leave the footprint clear.

## Build a plant

1. Place modules end to end with touching shaft ends, aligned horizontally and
   at the same height. One HP, three LP and one generator is a useful starting
   layout; two HP and four LP also work. Steam headers may feed LP modules on a
   different shaft train, provided each working train has a generator.
2. Use **High-Pressure Steam Pipe** from RPV steam outlet nozzles to HP top
   inlets. Valve positions and finite nozzle capacity still determine supply.
3. Pipe HP left-side exhausts to LP top inlets. A common header can join several
   HP exhausts and several LP inlets. Shafts transmit work, not steam.
4. Connect LP left-side water outlets to **Mekanism mechanical pipes**, then to
   condensate storage or feedwater pump suction. The LP outlet pushes water and
   also permits extraction. No steam is accepted at this water port.
5. Connect energy cables/storage to the generator's copper terminal. Its other
   faces have no FE capability; the terminal is output-only.
6. Put a **Steam Stop Valve** and **Turbine Steam Control Valve** upstream of the
   common HP supply header. Open the stop valve and adjust the control valve
   from 0–100% in 0.1% increments. Turbine sections use their supplied steam
   automatically. Right-click HP, LP or generator sections for readouts.
   See [the steam valve guide](STEAM-VALVES.md), including save/API migration.

All sections in a straight shaft train share speed and available generator
load. Reversing a module is allowed when the couplings remain coaxial. A gap,
height mismatch or sideways offset separates trains. Up to 32 modules can join
one train; unloaded/incomplete assemblies cannot supply work. Searches never
load chunks. Arrange chunk loading for the complete plant when operating it.

## Power and water accounting

- HP and LP sections have finite steam inventories. Multiple consumers share
  each RPV nozzle's existing per-tick allocation, and LP sections consume actual
  HP exhaust inventory. No section can generate repeatedly from the same steam.
  Available header supply is apportioned by the connected sections' steam
  demand, so one early-ticking LP does not consume every other LP's share.
- The simplified expansion model uses pressure-dependent drops capped at
  280 kJ/kg in HP and 570 kJ/kg in LP. It conserves mass and energy but is not a
  full wet-steam stage calculation. Steam temperatures shown in panels are
  saturation estimates from each segment's pressure.
- Generator efficiency is 98.5%, with a 1,500 MW electrical limit per generator.
  Conversion uses the existing pump calibration: **1 FE/t = 13.4 W**.
  A 1,000 MW output therefore generates about **74.6 million FE/t**.
  Cable and storage throughput can constrain usable output.
- A generator stores up to 2 billion FE. With no generator capacity available,
  turbine sections stop accepting further work. A full HP exhaust buffer or LP
  water buffer also limits flow. These are physical inventory limits, not
  automatic reactor controls.
- **Temporary condensation:** each LP section returns one kilogram of water per
  kilogram of steam consumed, at a displayed 40 C. Residual heat is explicitly
  rejected by this temporary built-in sink. Water holds 20,000 kg per LP section;
  an undrained outlet eventually stops it. The mod convention is **1 mB = 1 kg**.
- The condenser, moisture separator/reheater and water-temperature transport
  through ordinary mechanical pipes remain future work. This release does not
  require those unimplemented machines.
- Steam, energy and fractional condensate inventories survive saves. Speed is
  a simple startup/coastdown indication; rotor stored kinetic energy is not yet
  simulated. No automatic turbine or reactor protection is added.

The existing nozzle model can discharge unclaimed steam when the line is open.
The new inline valves restrict vessel discharge through that line without
commanding the nozzle's own actuator. Manage them manually or by computer.

## ComputerCraft

Attach a modem to any accessible part. Peripheral types:
`bwr_hp_turbine`, `bwr_lp_turbine`, `bwr_generator`.

```lua
local hp = peripheral.find("bwr_hp_turbine")
local valve = peripheral.find("bwr_turbine_control_valve")
valve.setPosition(0.75) -- fraction, 0 to 1; open the upstream stop valve too
print(textutils.serialize(hp.getStatus()))
```

`getStatus()` reports running state, rpm, steam flow, section/train MW, generator MW,
inlet/outlet psia and C, stored water/FE, train section counts and connection
status. Steam controls live on the physical valves; the last valve panel/computer
command wins. Local turbine `setAdmission` and `setRunning` are removed.

## Models and references

- Editable Blender scenes: `art/models/power_turbines/power_turbines.blend`
- Blender authoring script: `art/models/power_turbines/build_models.py`
- Resource exporter: `python tools-export-power-turbines.py`
- Reproducibility check: `python tools-export-power-turbines.py --check`
- [Toshiba Steam Turbines and Generators catalogue](https://www.global.toshiba/content/dam/toshiba/jp/products-solutions/thermal/products-technical-services/generation-technologies/STGcatalogue2024_web.pdf):
  PDF page 6 shows TX-10 nuclear turbine casings and the multi-cylinder train;
  PDF page 13 shows the TX-NLCH generator exterior and cutaways. The catalogue's
  generator rating is up to 1,700 MVA; this mod's 1,500 MW limit is a gameplay
  choice informed by that class of machine.

Verification entry points: `PowerTurbineTest` (pure Java mass/energy accounting),
`PowerModuleRuntimeCheck` (actual server assemblies and pipes), and
`runTurbineModelCheck -PbwrPowerPanelCheck` (actual client models and panels).
