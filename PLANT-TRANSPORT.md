# Computer faces, fluid temperatures and pipe routing

The current alpha.5 build uses GUI protocol 8; install the same build on server and clients.
Existing machines retain their dimensions, recipes and control method names.

## Computer connections

Attach a CC:Tweaked modem to an exposed face of a machine or modeled assembly
part. A computer connection does not consume a steam, water or power port.
For the reactor use its controller or an instrumented penetration; plain vessel
and concrete wall blocks are structural material, not separate computers.

All existing pump, valve, tank, reactor, turbine and generator Lua methods keep
their names. New measurement peripherals include:

| Hardware | Peripheral type | Measurements |
| --- | --- | --- |
| Condenser | `bwr_condenser` | `getStatus()`: inventories, cooling/steam flow, heat rejected, water temperatures, `backpressurePsia`, `vacuumInHg` |
| Four-port RHR exchanger | `bwr_rhr_heat_exchanger` | `getStatus()`: primary/secondary flow, water inventory, temperatures, transferred heat |
| Fuel fabricator | `bwr_fuel_fabricator` | `getStatus()`: batch mass, enrichment, energy and finished assemblies |
| Physical control-rod drive | `bwr_control_rod_drive` | `getStatus()`: attachment, rod index/notch, accumulator, water, power and health |
| RPV water/recirculation penetration | `bwr_rpv_water_port` | `getStatus()`: reactor connection, vessel pressure and water temperature |
| Jet-pump assembly | `bwr_jet_pump` | `getStatus()`: passive hardware, size and assembly completeness |

Cooling equipment retains its existing peripheral types and `setSpeed(0..1)`
where an electric drive exists. `getStatus()` now includes `inletC` and `outletC`.
The condensate tank's existing `getTemperature()` now reads its mixed inventory.

```lua
local condenser = peripheral.find("bwr_condenser")
local data = condenser.getStatus()
print(data.coldWaterC, data.hotWaterC, data.condensateC)
print(data.backpressurePsia, data.vacuumInHg)
```

The modem binds to its physical connection face. Each Lua call resolves the
current controller on the server thread. A missing/unloaded controller returns
a Lua error; a retained connection resumes after the same assembly reloads.
No method operates on a discarded controller object. Player scripts should use
`pcall` when a plant can be partly unloaded. Load the complete machine for it to run.

The mod supplies measurements and actuators. Automatic trips, sequencing and
plant-wide control remain the player's programs.

## Segmented temperatures

BWR tanks and machine buffers store water mass and energy. A transfer carries
the source's specific enthalpy (energy per kilogram); a receiving buffer mixes
incoming energy with its existing water. BWR water pipes transport this packet
without storing water or simulating a temperature at every block. Units remain
**1 mB = 1 kg**. The fluid is still ordinary `minecraft:water` with a BWR data
component, not a separate hot-water fluid.

- Circulating, makeup, feedwater and emergency pumps pass the supplied water's
  temperature through. A pump does not imply a feedwater heater.
- Condensate tanks preserve mixed energy through filling, fractional withdrawal,
  automatic formation and saves. Older untagged tank/pump inventories retain
  their former 32 C assumption when loaded.
- New untagged external water, buckets and screened intake water enter at
  **13 C**. Existing cooling-buffer saves use their previous design temperatures.
- Cooling towers remove up to an **11 C range per pass**, stopping at a **13 C
  ambient approach**. Water colder than that is not heated. The existing 2%
  evaporation/blowdown allowance remains; removed water carries energy too.
- Condenser cold/hot water and condensate are separate finite inventories.
  The condenser accounts for incoming steam energy and cooling-water heat gain.
- The RHR exchanger uses the supplied secondary-water temperature. Heat removed
  from the suppression pool enters the separate secondary stream. Suppression
  pool fill/spray uses the delivered water temperature.

Use BWR water pipes for a temperature-preserving BWR plant loop. Third-party
fluid handlers may preserve, strip or compare data components differently, and
are not guaranteed to mix different-temperature packets. Mekanism water
connections remain available; this update does not replace Mekanism's fluid
network or add a thermal solver to it. Water returned without BWR metadata uses
the external-water assumption above.

No weather, wet-bulb model, pipe heat loss, transport delay, ice, water chemistry
or flashing of hot water is simulated. Suppression-pool bulk thermodynamics
retain their existing lumped heat-capacity approximation.

## Condenser backpressure

The condenser reports absolute pressure in **psia** and vacuum relative to
14.696 psia in **inHg**. Its simplified pressure depends on cooling-water
temperature and buffered exhaust steam. Warmer cooling water raises pressure;
loss of cooling lets steam accumulate until the finite buffer fills.

LP turbine expansion uses that pressure as its exhaust boundary. Higher
backpressure reduces work per kilogram; pressure at or above the LP inlet
prevents expansion. The existing full-buffer and missing-condenser limits also
remain. No automatic trip or bypass opening is added.

The effective 4,000 m3 exhaust volume, 27 C approach, 40 C nominal condensate
floor and 11 C cooling range are **game calibration**, not manufacturer curves.
There is no noncondensable-air inventory, air ejector, dedicated vacuum pump,
moisture separator/reheater or detailed wet-steam stage calculation yet.

## Pipe performance

Neither BWR pipe block has a block entity or ticker. An event-invalidated graph
stores positions, faces and connectivity; contiguous ordinary pipe runs collapse
into a single junction. Transfers resolve current machine inventories and valve
positions on that compact graph.

- Placing/removing pipes, rotating ports, capability invalidation and chunk
  load/unload cause the affected cached routes to be rebuilt on next use.
- Time passing and valve stroke do not rebuild geometry. Valve positions and
  shared steam allowances are read during a transfer.
- Machines still update their physical inventories and flow on server ticks.
  Continuous fluid simulation cannot stop advancing time; the expensive pipe
  walk is what has been removed from those updates.
- Networks remain bounded at 256 surveyed line positions and never force-load
  chunks. Oversized networks refuse transfers through the incomplete survey.
- The per-level cache holds up to 512 route entries. Evicted routes rebuild
  when used again; this bounds memory in very large worlds.

## GUI performance

The fuel map previously flushed a draw call for each cell. Core-grid rectangles
and each screen's readouts are now batched; slots and tooltips keep their drawing
order. The client snapshot buffer is also released after decoding.

The repeatable local test used Minecraft 1.21.1/NeoForge, CC:Tweaked/Mekanism,
1280x720, GUI scale 2, render distance 4, uncapped FPS and no shaders:

| Screen | Previous screen CPU time | Updated screen CPU time | FPS before → after |
| --- | --- | --- | --- |
| Pump | 0.37 ms | 0.26 ms | 1,193 → 1,380 |
| 764-assembly reactor | 4.80 ms | 0.80 ms | 109 → 205 |
| Maximum reactor | 8.13 ms | 0.79 ms | 67 → 148 |

These are local reactor-panel measurements, not FPS promises for a modpack. The opt-in
`:mod:runTurbineModelCheck -PbwrGuiPerformanceCheck` harness creates disposable
worlds, logs paired world/menu measurements and saves screenshots under
`mod/run/turbineModelCheck/gui-performance/`. It is excluded from the release JAR.

### Creative inventory correction

The user subsequently identified the **Minecraft creative menu** as the screen
with the severe drop. Rendering the full cooling-tower and condenser meshes in
every inventory slot reproduced it: the BWR tab ran at **18.84 FPS**, spending
**51.17 ms** of CPU time rendering the screen in the controlled local test.

The 33 complex items now use two-quad, transparent inventory icons rendered
from their actual models in Blender. Placed and held meshes remain unchanged;
dyed pipes retain their existing color behavior. In the same test the BWR tab
ran at **560.83 FPS**, with **1.14 ms** screen CPU time. The vanilla tab remained
about 620–650 FPS. These figures describe this local scene, not every modpack.

Run `:mod:runTurbineModelCheck -PbwrCreativePerformanceCheck` to reproduce the
measurement. Results and screenshots go under
`mod/run/turbineModelCheck/creative-performance/`. `InventoryModelCheck` checks
all GUI icons for valid textures and two-quad geometry on the real client.
