# Pump models and pipe connections

The seven models in `BWR_Pump_Assets_Scale_Review.zip` are implemented. Existing
LPCS, HPCS, RHR/LPCI, motor feedwater, turbine feedwater and jet-pump IDs now use
the supplied textured geometry. `bwr:rip_pump` adds the reactor internal pump.
The external recirculation pump keeps its existing appearance and controls.

## Placement and saved worlds

One item places one complete machine, with one controller and one simulation.
Leave its whole rectangular footprint clear, including space between visible
components. Placement checks block collisions, entities, loaded chunks, world
height and borders. Breaking a part removes the assembly; suitable-tool survival
harvesting returns one item. Machines cannot be pushed by pistons.

| Block ID | Width × height × depth, blocks |
|---|---|
| `lpcs_pump` | 4 × 5 × 2 |
| `rhr_pump` (also LPCI) | 6 × 3 × 3 |
| `hpcs_pump` | 8 × 4 × 3 |
| `motor_feed_pump` | 6 × 2 × 2 |
| `turbine_feed_pump` | 7 × 3 × 3 |
| `jet_pump` (a pair) | 2 × 6 × 1 |
| `rip_pump` | 2 × 5 × 2 |

Existing saved one-block pumps load with a compact version of the new model and
retain their original controller data. **Pick up and place them again to build
the full-size machine and enable its physical ports.** This avoids expanding a
saved block through neighbouring equipment. The old compact motor/ECCS machines
retain their earlier proximity-based water behavior. Compact jet blocks must be
replaced by full paired jets to contribute to the new recirculation circuit.

## Ports

Right-click a flange to read its role. BWR pressurised tubes connect only to
defined flange faces. Short visible adapters bridge the model's original nozzle
to the Minecraft grid. Feedwater suction and discharge adapters have a gap between
their risers so the two circuits can be built separately.

Coordinates below are `(x,y,z)` offsets from the placed controller for a machine
with `facing=north`: +x is east, +y is up, +z is south. Rotate both the offsets and
faces with the machine. Put the first tube **outside the listed face**, not in the
listed machine cell.

| Machine | Port | Cell | Outward face |
|---|---|---|---|
| LPCS | Water suction | (0,0,1) | West |
| LPCS | Water discharge | (3,0,1) | East |
| RHR/LPCI | Water suction | (0,1,1) | West |
| RHR/LPCI | Water discharge | (1,2,1) | Up |
| HPCS | Water suction | (0,1,1) | West |
| HPCS | Water discharge | (1,3,1) | Up |
| Motor feedwater | Water suction | (1,1,1) | Up |
| Motor feedwater | Water discharge | (3,1,1) | Up |
| Turbine feedwater | Water suction | (1,2,1) | Up |
| Turbine feedwater | Water discharge | (3,2,1) | Up |
| Turbine feedwater | Steam inlet | (5,2,1) | Up |
| Turbine feedwater | Steam exhaust | (5,1,2) | South |
| Paired jet | Drive water inlet | (1,0,0) | North |
| Paired jet | Mixed-water outlets | (0,0,0), (1,0,0) | Down into the lower plenum |

### Water circuits and power

- **LPCS/HPCS/RHR suction:** pipe to a condensate storage tank or formed
  suppression-pool controller. Select that source with the existing pump controls.
- **Water discharge:** pipe to the reactor controller. The existing spray rings
  still determine core-spray delivery; future sprayer models can replace their
  appearance later.
- **RHR pool cooling:** select pool suction and pool-cooling mode, and connect the
  discharge back to that same pool controller. It cannot inject and cool at once.
- **Feedwater suction:** pipe to a storage tank, or connect a NeoForge water pipe
  directly to the suction flange. This fills the existing 2,000 mB buffer, suitable
  for water returned by a Mekanism turbine. Discharge never exposes this buffer.
- FE cables and CC:Tweaked peripherals on a machine's part cells address its
  shared controller. Powered machines still require FE. Turbine drives refuse FE.
  Use the controller cell for redstone start commands.
- A crossed steam/water circuit, incompatible port connection or ambiguous
  endpoint cannot deliver. MSIVs in the actual circuit throttle it. Surveys stop
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

## Jet count and recirculation

Place each full paired jet at the bottom interior elevation of the formed vessel,
inside its outer two-cell band. Its entire 2 × 6 × 1 footprint must fit inside the
vessel. The two lower outlets empty into the lower plenum; they need no external
discharge pipe. Connect its north-facing drive inlet to an external recirculation
pump through BWR pressurised tubes and any manually operated MSIVs.

The game balance is:

- Each normal paired jet contributes up to **10%** of rated forced core flow.
- Each external recirculation pump supplies up to **50% × actual shaft speed**.
- A shared circuit delivers the smaller of connected jet capacity and summed
  drive-pump output. Branching a pipe does not duplicate a pump's capacity.
- Ten paired jets (twenty individual jets) and two full-speed external pumps can
  reach 100%. Two pairs on one full-speed pump reach 20%; one pair reaches 10%.
- Incomplete, disconnected, unloaded or out-of-vessel assemblies do not count.
  Broken circuits are checked live; newly placed jets are discovered within one
  second and pump associations refresh within two seconds.
- Lua's direct recirculation demand is capped at the installed connected capacity.
  Start/speed commands remain player-owned. Existing natural-circulation physics
  and shaft coastdown remain in the core.

These are explicit gameplay ratings, not manufacturer hydraulic calculations.
The old `size=large` saved-state multiplier is retained; both size states use the
same supplied paired geometry. Normal placement uses `size=small`.

The controller's status and CC:Tweaked methods expose
`getRecirculationCapacity()` (0–1), `getConnectedJetPairs()` and
`getInstalledInternalPumps()`.

### Reactor internal pump

The RIP is a bottom-head penetration. Prepare a 2 × 2 opening along the vessel's
bottom-head rim and clearance below it, then place its controller at floor height. The assembly
extends **three blocks below** that controller and one above it. Its mounting
cells complete the pressure boundary; the flange may overlap the shell's outer
row while the wet end overlaps the interior. This leaves the required control-rod
drives clear. For a north-facing model on the west rim, put the controller in the
west shell column so the model's east column enters the interior. It needs no external drive-water pipe and its
mechanical mounting flange is not a pipe port.

Each complete mounted RIP contributes up to **10% × actual shaft speed**, powered
and controlled through the existing recirculation-pump GUI/peripheral. It also
updates the reactor's existing internal-pump boundary configuration. The new
crafting recipe uses an external recirculation pump, three vessel blocks and a
copper block.

## Source and implementation

Source archive SHA-256:
`2324ff88b74351fcf5c34756b12a1ef7c8b07261bcadf1129d7c8321f574b3a8`.

`tools-import-pump-pack.py <archive.zip>` reproduces the runtime assets: supplied
clipped OBJ meshes, the supplied atlas, grid adapters, compact inventory models,
blockstates, root-only loot tables and collision/port manifests. The Blender
project and full source archive are not needed by Minecraft. Rendering uses the
built-in NeoForge OBJ loader. No new runtime mod dependency is added.

The pack's equipment sizes and arrangements are visual reference estimates;
existing motor/feedwater/ECCS nameplates are preserved. The pack contains no new
HPCI or RCIC mesh; the previous STEP assemblies remain available. External
recirculation and the future spray nozzles have no replacement models in this pack.

## Verification

```powershell
./gradlew.bat :mod:runTurbineGameTest
./gradlew.bat :mod:runTurbineModelCheck
./gradlew.bat build
python tools-audit-assets.py
python tools-check-jar.py
```

GameTest exercises all seven machines in four rotations, blocked placement,
one-controller ownership, port arms, sided fluid/FE access, one-item root loot,
part cleanup, old-state migration, live motor/ECCS/feedwater circuits, shared
steam accounting and jet/RIP flow. The client check loads every occupied cell
and all inventory/compact models with their actual textures. These tests do not
replace a long-running player-world or chunk-unload soak test.
