# Pump models and pipe connections

The seven models in `BWR_Pump_Assets_Scale_Review.zip` are implemented. Existing
LPCS, HPCS, RHR/LPCI, motor feedwater, turbine feedwater and jet-pump IDs now use
the supplied textured geometry. `bwr:rip_pump` adds the reactor internal pump.
The external recirculation pump now uses a Blender-built DVSS exterior; see [RECIRCULATION.md](RECIRCULATION.md).
All powered pumps now open a simple numerical speed panel.

## Placement and saved worlds

One item places one complete machine, with one controller and one simulation.
Leave its whole rectangular footprint clear, including space between visible
components. Placement checks block collisions, entities, loaded chunks, world
height and borders. Breaking a part removes the assembly; suitable-tool survival
harvesting returns one item. Machines cannot be pushed by pistons.

| Block ID | Width × height × depth, blocks |
|---|---|
| `lpcs_pump` | 5 × 5 × 3 |
| `rhr_pump` (also LPCI) | 7 × 3 × 3 |
| `hpcs_pump` | 9 × 4 × 3 |
| `motor_feed_pump` | 7 × 3 × 3 |
| `turbine_feed_pump` | 9 × 3 × 3 |
| `jet_pump` (a pair) | 1 × 6 × 1 |
| `rip_pump` | 2 × 5 × 2 |
| `recirculation_pump` (DVSS) | 5 × 10 × 5 |

Existing saved one-block pumps load with a compact version of the new model and
retain their original controller data. **Pick up and place them again to build
the full-size machine and enable its physical ports.** This avoids expanding a
saved block through neighbouring equipment. The old compact motor/ECCS machines
retain their earlier proximity-based water behavior. Compact jet blocks must be
replaced by full paired jets to contribute to the new recirculation circuit.

The five modernized electric/feedwater machines place their controller at the
**center of the base**, so leave space on both sides of the clicked block.
Previously assembled versions keep their old footprint, ports and controller
data. Pick up and replace a machine to get its new size and round flanges, then
reconnect its pipes. See [Modern pumps and pipes](MODERN-PUMPS-AND-PIPES.md).

## Ports

Right-click any machine cell to open its control panel. Water pipes connect to water flanges, and
steam pipes connect to steam flanges. The five modernized pump models have
straight round necks and flanges centered on block faces, without offset green
adapters. Feedwater suction and discharge have a gap between their risers so the
two circuits can be built separately. The internal jet has no external adapters.

Coordinates below are `(x,y,z)` offsets from the placed controller for a machine
with `facing=north`: +x is east, +y is up, +z is south. Rotate both the offsets and
faces with the machine. Put the first tube **outside the listed face**, not in the
listed machine cell.

| Machine | Port | Cell | Outward face |
|---|---|---|---|
| LPCS | Water suction | (-2,0,0) | West |
| LPCS | Water discharge | (2,0,0) | East |
| RHR/LPCI | Water suction | (-3,1,0) | West |
| RHR/LPCI | Water discharge | (-2,2,0) | Up |
| HPCS | Water suction | (-4,1,0) | West |
| HPCS | Water discharge | (-2,3,0) | Up |
| Motor feedwater | Water suction | (-2,2,0) | Up |
| Motor feedwater | Water discharge | (0,2,0) | Up |
| Turbine feedwater | Water suction | (-3,2,0) | Up |
| Turbine feedwater | Water discharge | (-1,2,0) | Up |
| Turbine feedwater | Steam inlet | (2,2,0) | Up |
| Turbine feedwater | Steam exhaust | (3,1,1) | South |
| DVSS RCP | Water suction | (0,0,2) | South |
| DVSS RCP | Water discharge | (0,2,-2) | North |
| Paired jet / RIP | No external water ports | Internal manifold / bottom-head mounting | — |

### Water circuits and power

- **LPCS/HPCS/RHR suction:** pipe to a condensate storage tank or formed
  suppression-pool controller. Alternatively, supply ordinary NeoForge water
  directly at the suction flange with a Mekanism Mechanical Pipe. Select
  **Tank / piped water** or **Suppression pool** in the pump panel.
- **Water discharge:** use High-Pressure Water Pipe to an outward-facing RPV Water Injection Port in the vessel wall. The existing spray rings
  still determine core-spray delivery; future sprayer models can replace their
  appearance later.
- **RHR pool cooling:** select pool suction and pool-cooling mode, and connect the
  discharge back to that same pool controller. It cannot inject and cool at once.
- **Feedwater suction:** use High-Pressure Water Pipe to a storage tank, or return
  Mekanism water directly through a Mechanical Pipe into the suction flange.
  High-Pressure Water Pipe also accepts pushed water. This fills the existing 2,000 mB buffer, suitable
  for water returned by a Mekanism turbine. Discharge never exposes this buffer.
- FE cables and CC:Tweaked peripherals on a machine's part cells address its
  shared controller. Powered machines still require FE. Turbine drives refuse FE.
  Use the controller cell for redstone start commands.
- A crossed steam/water circuit, incompatible port connection or ambiguous
  endpoint cannot deliver. MSIVs throttle steam circuits only. Surveys stop
  at 256 loaded blocks and never load chunks.

### Turbine feedwater steam

Pipe the steam inlet to an open, formed RPV steam nozzle. Pipe the exhaust to
`bwr:turbine_steam_outlet`, then connect that outlet to the Mekanism steam consumer.
Command a nonzero flow on the outlet as well as starting the feedwater pump.
Its command and available buffer space limit accepted exhaust.

The feedwater turbine claims steam from the shared nozzle ledger. Its exhaust is
placed in the existing steam export buffer, including fractional mB bookkeeping;
the reactor is not debited a second time. A full export buffer or broken exhaust
stops further steam admission without changing the player's run command.

Once connected to pump exhaust, an outlet remembers that role across saves.
Disconnecting the pipe does not make it draw steam directly from a nearby reactor.
Pick up and replace that outlet to return it to main-steam service. Its right-click
status identifies exhaust service. No condenser block is added.

Use High-Pressure Water Pipe for pressure-side discharge and external recirculation.
Ordinary water pipes are allowed at pump suction. Use High-Pressure Steam Pipe
(`bwr:pressurised_tube`) for steam inlet and exhaust only. See [WATER-PLUMBING.md](WATER-PLUMBING.md) for upgrade instructions.

## Jet count and recirculation

See [RECIRCULATION.md](RECIRCULATION.md) for loop construction, opposite-side jet
matching and the flow table. New jets use one-column placement, retaining the
source height and textures. Mount full assemblies 1 or 2 blocks above the bottom
shell, at the interior perimeter, and align them in the same row across opposing
walls with opposite facings. The old diagonal arrangement also remains supported.
Saved two-column jets retain their footprint until picked up and replaced.

A complete external loop provides weak circulation without jets. Required flow
scales with interior volume, including height, starting at 12 normal matched
assemblies for the smallest vessel. Each RCP supports ten normal assemblies'
worth of assisted core flow; extra jets cannot bypass that limit. See the volume
table in [RECIRCULATION.md](RECIRCULATION.md). Speed and connected drive capacity limit flow.
The original `getConnectedJetPairs()` Lua name now reports **matched assemblies**,
not the number of opposing sets. `getRecirculationCapacity()` remains a 0–1
hardware ceiling. The reactor INFO tab also shows unmatched assemblies and pumps.
`getRecirculationSizing()` exposes the volume-derived targets and capacities.

### Reactor internal pump

The RIP is a bottom-head penetration. Prepare a 2 × 2 opening along the vessel's
bottom-head rim and clearance below it, then place its controller at floor height. The assembly
extends **three blocks below** that controller and one above it. Its mounting
cells complete the pressure boundary; the flange may overlap the shell's outer
row while the wet end overlaps the interior. This leaves the required control-rod
drives clear. For a north-facing model on the west rim, put the controller in the
west shell column so the model's east column enters the interior. It needs no external drive-water pipe and its
mechanical mounting flange is not a pipe port.

Each complete mounted RIP contributes up to **1.2 normal jet equivalents × actual shaft speed**
(10% of the smallest reactor's rating), powered
and controlled through the existing recirculation-pump GUI/peripheral. It also
updates the reactor's existing internal-pump boundary configuration. The new
crafting recipe uses an external recirculation pump, three vessel blocks and a
copper block.

## Source and implementation

Source archive SHA-256:
`2324ff88b74351fcf5c34756b12a1ef7c8b07261bcadf1129d7c8321f574b3a8`.

`tools-import-pump-pack.py <archive.zip>` reproduces the legacy source-pack assets,
then reapplies the narrow jet and modern pump/pipe exports. The editable modern
Blender project and its exported source meshes are in `art/models/modern/`.
`python tools-export-modern.py --check` verifies the modern runtime meshes,
materials, textures, blockstates, inventory models, loot and port manifests.
The exporter preserves UVs and clips each pump into its owned cells. Blender and
the source archive are not needed by Minecraft; rendering uses NeoForge's OBJ
loader without an additional runtime dependency.

The pack's equipment sizes and arrangements are visual reference estimates;
existing motor/feedwater/ECCS nameplates are preserved. The pack contains no new
HPCI or RCIC mesh; the previous STEP assemblies remain available. The DVSS model is a separate addition built in Blender; the future spray
nozzles are still not modeled.

## Verification

```powershell
./gradlew.bat :mod:runTurbineGameTest
./gradlew.bat :mod:runTurbineModelCheck
./gradlew.bat build
python tools-audit-assets.py
python tools-check-jar.py
```

GameTest exercises all eight pump assemblies in four rotations, blocked placement,
one-controller ownership, port arms, sided fluid/FE access, one-item root loot,
part cleanup, old-state migration, live motor/ECCS/feedwater circuits, shared
steam accounting and jet/RIP flow. The client check loads every occupied cell
and all inventory/compact models with their actual textures. These tests do not
replace a long-running player-world or chunk-unload soak test.
