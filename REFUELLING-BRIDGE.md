# The refuelling bridge and the cattle chute

Fuel used to move by clicking the refuelling map: one click, one assembly, instantly, from the core into your pocket. A
real plant does not do that. A **refuelling bridge** rides rails across the refuel floor, over the open reactor cavity and
the spent fuel pool; a telescoping mast lowers a grapple, latches the handle of **one** assembly, lifts it, carries it, and
sets it down in a core position or a pool rack. The whole way the fuel stays under water, and the cavity and the pool are
joined by a water-filled slot with gates, the **cattle chute** (plant slang for the transfer canal). Nothing in it moves
unless a player, or their program, says so.

The old instant map is the server option `instantRefuelling` (`bwr-gameplay.toml`), **off by default**. With it off, the
refuelling map still opens and still shows the whole core (burnup, k-inf), but its buttons stay dark and say how fuel moves
now; with it on, it works exactly as it did. Existing worlds keep their fuel either way.

## Building one

All blocks are in the Realistic BWR creative tab (iron and gray concrete; the console also wants a comparator and glass panes).

1. **Rails.** Two parallel runs of **Refuelling Rail**, on the refuel floor, at the same height, up to 40 blocks apart, overlapping
   along their length by at least two blocks. A rail has an axis (the way you face when you place it); the bridge runs along it.
   The reactor's interior and the pool's interior must both lie between the rails (inside their overlap, along the rails, and
   between them, across). The reactor needs its head off (the refuelling vessel state); the pool needs to be formed.
2. **The cattle chute.** A straight row of **Transfer Canal** blocks from the reactor shell to the pool wall, in a row that both
   interiors share, filling every column between the two shells. It is a **trench**: a 4.5 m assembly stands upright in it, so the
   canal blocks are stacked (the lowest has the floor, the highest the water surface; the model joins them as you place them). The
   top block is at the height of the rails or up to four blocks below them, and the trench must be deep enough for the assembly
   to hang clear of its floor (in the test world, 9 blocks; the bridge halts with the figures if it is not). One or more **Canal Gate**
   blocks stand in the row (a gate is a canal block with a plate across it, raised when closed and sunk when open; stack gates to
   make a gate the full depth: the bridge counts the row closed if any gate block in it is). A bucket fills or drains the whole stack it is used on.
   The chute meets the cavity and the pool at their walls; the model takes the assembly through the wall at that depth (the real slot
   is gated, and the vessel's shell is not something a player can cut).
3. **The console.** A **Refuelling Bridge Console** beside any rail block. Right-click it for the control screen; sneak-click for
   what the bridge sees (rails, reactor, pool, chute, state).

Gate and canal are player actions: an empty hand opens or closes a gate; a bucket drains or fills a canal. Nothing else ever does.

## Using it

The console opens the refuelling map as the bridge's control. Pick a core position on the map (click it), pick a rack cell
(the arrows and the EMPTY and FUEL jumps step through the pool's racks, four cells to a rack), and queue the move:

| Button | Does |
|---|---|
| TO POOL | the assembly in the selected core position goes to the chosen free rack cell |
| TO CORE | the assembly in the chosen rack cell goes to the selected empty core position |
| SET DOWN | after a halt, the assembly still on the grapple goes to the chosen free rack cell |
| ABORT | stops where it is, forgets the queue (an assembly on the grapple stays on it) |
| RESUME | after a halt (a closed gate, the hoist limit, a destination that filled up) tries the step again |
| OVERRIDE | overrides the hoist limit (see below) |

The screen shows the phase, the time left in it, the queue, the water over the core and over the racks, the chute (open, closed,
drained, absent), what is on the grapple, and the reason for a halt. Up to 16 moves queue; they run one at a time.
A move is refused at once, with the reason, if it cannot be done: no reactor or pool under the bridge, the head is on, the
source is empty, the destination is taken, a position is out of reach, the chute is absent, or a gate is shut.

### A move, phase by phase

| Phase | What happens | Time |
|---|---|---|
| travel | the bridge runs along the rails and the trolley across them, mast retracted | the slower of 1.0 m/s along the rails and 0.6 m/s across |
| lower | the mast telescopes down until the grapple sits on the handle | 0.6 m/s |
| latch | the grapple closes. **Just before it the hoist limit is checked.** At its end the assembly leaves its seat (the reactor's own `unloadAssembly`, which stamps `bwr:discharge_time`; or the pool's `extractFrom`) and is on the grapple | 4 s |
| lift | the assembly is raised 1.0 m off its seat | 0.3 m/s (loaded) |
| carry | the loaded trolley goes to the other body of water, one leg per waypoint, through the chute between cavity and pool, all three axes together | 1.0 / 0.6 / 0.3 m/s |
| set down | the assembly is lowered onto its new seat | 0.3 m/s |
| release | the grapple opens; the assembly goes in (the reactor's `loadAssembly`, the pool's `insertAt`) | 4 s |
| retract | the mast goes back up | 0.6 m/s |

The speeds are real-ish: a bridge runs at fractions of a metre a second and a loaded hoist at about 10 cm/s; the game compresses them
about threefold and keeps the ratios. Measured in the GameTests with a 5 x 5 reactor and the smallest pool, 11 m from the core to the racks: **49 s** core to rack and **46 s** rack to core
(travel, lower, latch 4 s, lift, carry, set down, release 4 s, retract). A larger reactor with the pool further away takes proportionally longer; a queue of moves runs one after another.

The mast and grapple are drawn from the console's synced state (no entities, no per-tick packets: one update when a phase begins,
the client advances the pose with its own clock). The operator's seat is the console beside the rails; riding the gantry was not built.

## The reactor well and the cavity seal

A formed reactor's vessel is round and its block footprint is square, so a gap is left round the vessel wall and the concrete that
surrounds it. In a real plant that gap is the **drywell**; it stays dry. Above the vessel is the **reactor well** (the cavity), a
concrete shaft up to the refuel floor, and at flange height a **cavity seal** (a bellows and a seal plate) bridges the vessel flange to
the wall of the well. With the head off, the water is raised out of the vessel over the flange and up the well, and the seal holds it
above the drywell. The mod does the same, and the bridge reads the result.

### The seal (formed with the vessel)

The seal is part of the vessel's picture, a steel plate with a dark collar drawn from the round flange out to the square footprint and
the surrounding blocks (the corner gaps included). It is **not a block a player places**: you build the wall, the seal appears.

**The gap rule.** At one elevation (one row of blocks), looking outward from the vessel's block footprint all the way round the ring,
corners included:

* a full, solid block **touching** the vessel (gap 0) or **one block away** (gap 1) closes the ring at that column;
* the ring closes at that row only if **every** column round it finds such a block;
* more than one block of air anywhere round the ring: that row does not close. No seal forms at a row where it does not.

**Where it forms.** Scanning down from the flange (the top of the vessel's body once the head is off; the dome rows above it go with the head), the
**highest row that closes** takes the seal. It may be below the flange. It must be in the **upper third** of the vessel: at most `round(H / 3)` blocks below the
top shell row, where H is the formed vessel's total height in blocks (the shell included) and `round` is to the nearest block (H = 10: 3 below the top; 11: 4;
13: 4; 14: 5; the default 17 x 17 x 28: 9). A ring that closes only deeper than that is no seal. No row from the flange to that depth closes: no seal at all,
and the INFO tab and the computer say which side is how many blocks too far, and where.

*Why one third and not one quarter.* The owner's fallback was `round(H / 4)` if a third caused a real problem (a seal at or below the top of the active fuel or a port row,
or annulus water clashing with the nozzle models). Checked: the top of the active fuel is 5.5 above the bottom of the smallest vessel (7 x 7 x 10, H = 10) and its deepest
seal row is 6 above the bottom; on the default 17 x 17 x 28 the fuel top is 11 above the bottom and the deepest row is 18; on the largest (23 x 23 x 131) the fuel top is 11 and the
deepest row 86. So a seal is never at or below the fuel. Port rows are the player's to place, so the rule cannot rule a clash out: the seal forms at the **highest** closing row, which is
the flange for a shaft that closes all the way up (the steam outlets of the test vessels sit one to three rows below the flange), and only a wall the player builds open down to a deeper row
would put the plate below a nozzle. Nothing needed the quarter; a third was used.

Canal and gate blocks of the cattle chute count as wall (the chute meets the cavity there). Pipes and other partial blocks do not.

**When it is surveyed.** On formation and whenever the reactor revalidates, which it does when a block next to the controller changes and on
the five-second sweep it already did; never per tick and per block. A block in an unloaded chunk leaves the last answer standing (no chunk is
ever loaded for it). Nothing of the seal is saved: an old world surveys it on its first tick. The result goes to the clients inside the
vessel's picture, only when there is a seal.

### The well

From the seal row up, layer by layer: the vessel's footprint (the shell is gone with the head, and above the vessel a block over the footprint is
a ceiling that ends the well) and every open cell joined to it within three blocks. A layer that leaks ends the well; its top is the top of the last closed
layer, where the refuel floor is. The well is usable only if it reaches the flange.

* **Water over the flange.** The model's flange lip stands at collapsed level **+60 in** (the top of the narrow range band, where the drawn vessel's lip has
  always been). Below it the inventory and the level are exactly what they were. Above it the inventory first fills the **annulus** (outside the vessel
  body, from the seal plate up to the lip: the level in the vessel *holds at the lip* while it fills), then rises through the well layer by layer, each layer its own
  cross-section in m2 (one block is one metre). The collapsed level, the inventory (`liquidMassKg`) and `VesselWaterGeometry` stay one thing: the level above
  the lip is `60 in + rise / 0.0254`, and the drawn surface is the same rise above the drawn lip.
* **Head replaced while flooded.** Nothing moves. The water over the flange stays where it is, the level reads where it was, and it comes down only as the level
  falls (the plant's own drains, boil-off). Closing the head is not refused and does not drain anything; it just stops the spill described next.
* **Without a seal**, or with a seal under a wall that opens below the flange, or when the well is full: the head is off and the vessel can not hold the water, so it
  leaves at the rate it arrives, up to **4000 kg/s** (`PressureVessel.WELL_SPILL_KG_PER_S`, twice the rated feedwater flow): into the drywell (no usable well: a refuelling seal failure)
  or onto the refuel floor (over the top of a full well). The level holds at the lip (or the top). The INFO tab and `getCavity()` show the rate and where it goes.
  With the head on there is no spill: the vessel is closed.
* **Nothing floods by itself.** Only the player's feed, condensate and residual heat removal pumps (and a program's commands) put water in.

### What the screen and the computers say

INFO tab, "Cavity seal and well": the survey's verdict (present at y, row, H and the lowest allowed row, touching or with a gap; or absent, with the closest row and which side is
how many blocks from the vessel at which x, z), the water over the flange, the level over the flange, the well's capacity and the leak (or overflow). CC:Tweaked
`reactor.getCavity()` returns the same as a table (`sealPresent`, `sealReason`, `vesselHeight`, `lowestSealRowY`, `flangeRowY`, `sealRowY`, `gapSides`, `tooWideSide`,
`tooWideGapBlocks`, `wellUsable`, `waterOverFlangeM3`, `levelOverFlangeM`, `wellCapacityM3`, `spillKgPerS`, `spillTo`).

### Drawing

The plate and collar are built into the vessel's cached mesh (one rebuild when the seal changes). The water in the well and the annulus is one translucent surface at the level the
server sends (only while there is water over the lip), over the cells of the well at that height, in the pool's water tint: no water blocks. The core's Cherenkov glow reaches up to it.

## Water, depth and dose

The top of the carried assembly, its handle where the grapple holds it, is the point that matters. The bridge tracks the metres of water
over it, `cover = surface - handle`, at every tick of a move, in whichever body it is over:

* **cavity**: the top of the drawn core plus the metres of water the reactor model has above the top of active fuel,
  `(collapsed level - TAF) x 0.0254` (the same number `RadiationField` uses for an open head). A freshly formed cold vessel has only 1.2 m
  over the fuel, so **the player floods the cavity before refuelling** (the normal operating level is 4.4 m over the fuel); the hoist limit
  below says so when the water is too shallow. Water over the vessel flange in a sealed well is part of that level (see
  [the reactor well and the cavity seal](#the-reactor-well-and-the-cavity-seal)), so a flooded well adds its rise to the cover metre for metre;
* **pool**: its own level, `floor + levelM`, so a boiled-down pool shows up at once;
* **chute**: the surface of its top canal block (13/16 of the block); none if any block of its row is drained.

**Registered radiation sources** (`RadiationSources`, so the Survey Meter and the Area Radiation Monitor read them):

* the **carried assembly**: gamma power `0.5 x decay heat` (`AssemblyDecayHeat`, from its burnup and its discharge stamp, the model's
  `GAMMA_FRACTION_OF_DECAY_HEAT`), a point source at the handle, through `e^(-7.07 x cover)` (`DoseModel.waterTransmission`, the law the
  reactor model applies to an open head), nothing in air. Registered while it is carried, cleared when it is set down, the console is broken or the chunk unloads;
* the **pool's stored fuel**: each rack with fuel is a point source at the middle of its top, the same gamma share of the decay heat of what it holds, through the water over
  the rack top (none when the water has fallen below it). One provider for every pool in the level.

For a just-discharged 45 GWd/t assembly (315 kW, 157 kW gamma) seen from the deck 4.5 m above its rack (`theDoseRisesWhenTheAssemblyIsLiftedAndSpikesOutOfTheWater`): **4.1 uSv/h**
seated under 3 m of water, **8.0 mSv/h** lifted a metre (2 m over the handle), and **2.8e8 mSv/h** with the handle out of the water a few metres from the observer (the survey meter picture reads
906 Sv/h from 9 m away). Each metre of water is a factor of 1,200.
Looking down is the conservative case: the model has the point source at the handle and counts only the water straight above it.

### The hoist limit

The bridge **refuses to lift an assembly higher than leaves 1.5 m of water over its handle** (the check is made for the whole route, every waypoint,
just before the grapple latches, so a refusal leaves the assembly on its seat). It halts with the figures and waits. The player may **override** it
(OVERRIDE on the screen, `setOverride(true)` from a program): the bridge then lifts the real 4.5 m, the whole length of the assembly, which in
this game's shallow water brings the handle out of it, and the survey meter spikes. This is the machine's own limit, a player-facing interlock; it
protects nothing in the plant and nothing reads it. It is not in the physics module: the `core` physics scan (`ReactorCoreTickTest.test07`) objected to a method
named for it, and the decision now lives in the block entity (the core only measures the cover).

Normal carries lift 1.0 m (game scale: the real lift is 4.5 m under 7 m of water); in the chute the assembly is held 2.0 m under the lowest of the three
surfaces (cavity, pool, canal). With a 3 m full pool the cover is 2 m over a rack seat; with the cavity flooded to 3 m, 2 m over the core. The mast
can only raise the grapple as high as its rest position (1 m over the rails), so even an overridden lift stops there: the handle then hangs in air
if the surface is lower than that.

### The cattle chute, and how it couples the water

**Decision: it does not.** An open gate is a path, not a pipe. The cavity's water is the reactor vessel's inventory (level from the collapsed level
of the pressure vessel model, with its own pressure and temperature) and the pool's is a separate lumped body (mass and temperature, evaporation,
boil-off, a cooling train). Joining them for level would need a flow law between two plant models and would make an open gate drain the pool into the
vessel (or the reverse) by itself, an automatic action on the plant. Neither is cheap and the second is not allowed; and nothing a player sees needs it, because
both bodies already have levels the bridge reads. So:

* a **closed gate** isolates the pool from the cavity for the bridge: the move is refused (and a gate shut during a move stops the bridge in front of it,
  with the assembly on the grapple, until a player opens it and resumes);
* the chute is a third body of water with its own level (its blocks'): drain one column with a bucket and the chute is **drained**; the bridge halts at the hoist limit
  (nothing would shield the assembly) and an overridden carry makes the meter spike as it passes through;
* the chute must be deep enough that the assembly does not hang through its floor (a shape rule, not an override): in the GameTest world 9 stacked blocks.

## Performance, chunks, saves

* **One ticker while moving, none idle.** The console has no block entity ticker. A move schedules ordinary block ticks, one per game tick, only until the queue is empty or the
  bridge halts. An idle or halted bridge has nothing scheduled (`aBridgeThatIsNotMovingHasNothingScheduledAndTicksOnlyWhileItMoves`). The reactor and the pool are read through their registries.
* **Chunk safe.** It never loads or forces a chunk: if a chunk of the reactor or pool it works with is not loaded it waits where it is (time does not run for it), and the survey reads only loaded blocks.
* **Saves.** The state of a move (phase, ticks done, the leg, the route, the queue, the assembly on the grapple) is saved with the console, only when non-default; an old world has none of it and loads as before.
  Breaking the console drops what the grapple holds.
* **Fuel is never duplicated or lost.** The assembly is in exactly one of: a core position, a rack cell, the grapple. It leaves its seat when the grapple latches and enters its new one when the grapple releases.
  If the destination has filled up the bridge halts with the assembly on the grapple (SET DOWN or RESUME).

## Computers

CC:Tweaked peripheral `bwr_refuelling_bridge` on the console (every method runs on the server thread):

| Method | Returns |
|---|---|
| `getStatus()` | `ready`, `problem`, `phase`, `busy`, `halted`, `progress`, `secondsLeftInPhase`, `queued`, `override`, `holding`, `carried`, `carriedCoverM`, `grapple {x,y,z}`, `haltReason`, `chute`, `reactorFound`, `poolFound`, `cavityWaterM`, `coreSlots`, `coreReady`, `poolWaterOverRacksM`, `rackCells`, `move` |
| `getRacks()` | one table per rack: position, and four `cells` (`cell` number, `occupied`, `decayHeatKW`) |
| `isCoreOccupied(index)` | whether core position `index` (the refuelling map's numbering, 0 in the middle) holds fuel |
| `queueCoreToPool(coreIndex, cellIndex)`, `queuePoolToCore(cellIndex, coreIndex)`, `queueSetDown(cellIndex)` | `{ok, message}`; cell numbers are `rack * 4 + cell`, counting from 0 |
| `abort()`, `resume()` | the same as the buttons |
| `setOverride(bool)`, `getOverride()` | the hoist-limit override |
| `setGate(open)`, `getChute()` | opens or closes every gate of the chute (returns how many); `"absent"`, `"open"`, `"closed"` or `"drained"` |

```lua
local bridge = peripheral.find("bwr_refuelling_bridge")
local r = bridge.queueCoreToPool(12, 0)       -- the program's choice, nothing else queues a move
if not r.ok then print(r.message) end
while bridge.getStatus().busy do sleep(1) end
```

## Pictures (1920 x 1080, `art/refuelling-bridge/`)

`chute-open-floor.png` and `chute-closed-floor.png` (the chute from the refuel floor, gate sunk and raised), `chute-open-side.png` and `chute-closed-side.png` (from the side),
`bridge-span.png` (the gantry over the chute between the cavity and the pool), `mast-in-core.png` (the mast lowered into the core), `assembly-through-chute.png` (a hot assembly glowing
in the water of the chute, seen from inside the trench), `assembly-in-rack.png` (just set into a rack), `control-screen.png` (the map as the bridge's control, mid move),
`survey-meter-spike.png` (the Survey Meter HUD at 906 Sv/h with the handle out of the water), and the four screens of the GUI check (`gui-*.png`).

## Tests

Core (`:core:acceptance`): `BridgeMotionTest` (legs take as long as the slowest axis, the water over the handle and the dose law against `DoseModel`, routes and the least cover, the chute floor margin).
GameTests (`RefuellingBridgeTests`, namespace `bwrbridge`, `-PbwrOnlyBridge`): a full move from the core to the pool and back (phases in order, fuel conserved every tick, burnup and discharge stamp kept, minutes of
travel); a closed gate refuses a move, stops a move in progress and resumes once opened; the hoist limit halts with the assembly still on its seat and an override continues; the dose rises when lifted and spikes out of the water;
a drained chute halts and, overridden, spikes in the chute; a destination that fills up halts holding the assembly (set down elsewhere, abort); a move saved halfway and loaded again finishes the same job; an idle bridge has nothing scheduled
and a moving one ticks once a tick on the real scheduler; the instant option on and off (the map says how to use the bridge when off); the screen's commands and snapshot. Client check `-PbwrRefuellingBridgeCheck`
(`RefuellingBridgeClientCheck`) saves the pictures in `art/refuelling-bridge/`; the screens are in `-PbwrGuiScreenCheck`.

## Honest limits

* The operator does not ride the gantry; the console is beside the rails.
* The bodies of water are drawn as they always were (the vessel's compressed water, the pool's surface, the canal block's water body) and the bridge's numbers are the physical ones: the drawn cavity surface and the
  physical one can differ by a metre or two in a given build.
* No collision: the assembly does not hit the rack tops it passes over; the lift is the game-scale 1 m.
* One reactor and one pool per bridge (the nearest of each to the console between the rails). A pool-to-pool move works; a bridge with no reactor has no screen (the peripheral works).
* Specialty inserts (rods, sources) move between core positions; the pool racks take fuel assemblies only.
* Two consoles on the same rails are two independent bridges; the second does not know where the first is (nothing stops them sharing a column).
* The assembly crosses the vessel's shell and the pool wall at the depth of the chute (see the build notes); nothing is cut in either.
