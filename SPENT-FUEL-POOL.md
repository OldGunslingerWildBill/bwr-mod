# Spent fuel pool

The fuel cycle now runs fabricator, reactor, **pool**, reprocessing plant, MOX. A spent assembly leaves the core
hot, with the decay heat of its fission products still in it, and cannot be reprocessed until that has fallen. The
pool is where it waits: a lined, open-topped box of water with racks standing in it, a cooling train the player builds
and runs, and a heat balance that boils the water and uncovers the fuel if the player lets it. Nothing in it starts by
itself.

## Building one

1. A floor and four walls of **Spent Fuel Pool Lining**, up to the rim. Outside 5 to 25 blocks wide and deep and **8 to 16
   high**. It is open at the top (a pool, not a tank). The minimum height is the racks and the shielding water over them:
   a rack is **4 m** tall and the pool must be deep enough for **3 m of water over the rack tops** (an interior at least
   7 m deep, 8 high outside). The real figure is about 7 m of water over the fuel; 3 is that scaled to the game's block height.
   This is a shape rule: a pool forms empty, and how much water is in it is the player's business (the panel shows the metres over the fuel).
2. One **Spent Fuel Pool Controller** in the shell. It is the pool's only ticker.
3. **Spent Fuel Racks** on the floor inside, up to 48. A rack is **one single tier, 4 m tall** (assemblies are about 4.5 m and stand
   upright): a column of **exactly four** Spent Fuel Rack blocks standing on the pool floor, holding four assemblies. A rack never
   stands on another rack, and a column that is three or five blocks tall, or does not start on the floor, stops the pool forming
   with the reason ("A rack is one tier of exactly 4 blocks standing on the pool floor, never on another rack"). The rack top is
   therefore always 4 m above the floor. One craft makes four rack blocks, one rack.
4. Ports in a side wall, above the floor, facing outward (the three are made from a lining block, a hopper and a dye):

| Port | Flange | Does |
|---|---|---|
| Makeup Port | amber | water in, to the pool |
| Cooling Inlet | blue | cold cooling water in, to the cooling train |
| Cooling Outlet | cyan | warmed cooling water out of the cooling train, pushed into whatever accepts it |

5. Nothing else in the interior (air, racks and vanilla water sources only). The pool surveys itself when a block of it
   is placed or broken, and every five seconds while it is not formed; sneak-click the controller for the reason it is
   not. Only the controller ticks: the lining, racks and ports are plain blocks, found through a hash map the
   controller fills when it surveys.

Capacity is 1,000 kg a cell: the smallest pool, 9 x 9 x 8 outside, holds 7 x 7 x 7 = 343,000 kg and a level of 7 m, which is 3 m over the
rack tops. "Uncovered" means the water is below the rack top (4 m).

## Moving fuel

- **The refuelling bridge** moves assemblies between the core and the racks, through the cattle chute and under water, one at a time, and is the normal way (see [REFUELLING-BRIDGE.md](REFUELLING-BRIDGE.md)); the pool's stored fuel is also a registered radiation source behind the water over the racks.
- **The new fuel elevator** puts fresh fuel into the pool through the water: a hoist on the rim with a guide frame down the inside of the wall
  (see [the elevator](#the-new-fuel-elevator)).
- **Carrying it.** Right-click a rack with an assembly (the cell under the cursor on the top face if it is free, else
  the first free cell; click the top of the column). Sneak with an empty hand takes one out (the cell under the cursor if it holds one, else the one
  that has cooled longest). A plain right-click reports the rack's cells. Right-click the controller with an assembly
  to put it in the first free cell.
- **Pipes and hoppers.** The controller is an item handler: one slot per cell, rack by rack, one assembly a slot.
  A hopper under the reactor refuelling or a CC program driving a pipe can fill and empty it. The pool is unreachable
  (zero slots) while it is not formed, but it keeps what it holds.
- **The clock does not stop.** When an assembly is unloaded from a core (`ReactorControllerBlockEntity.unloadAssembly`,
  also the fuel handed back when a reactor is broken) it is stamped with the level's game tick in a data component,
  `bwr:discharge_time`. It is a separate component from the bundle's exposure record on purpose: that record is compared
  whole by the refuelling code. The stamp survives item NBT, chests, hoppers and the pool's own save, and the decay heat
  is computed from it in the pool *and* as an item (tooltip, reprocessing plant), because game time keeps running in a
  chest. **An assembly with no stamp (an old save, or one that never left a core) counts as long-cooled**: five years,
  which the decay curve turns into a few kilowatts at most. Time spent in the vessel after shutdown before the
  assembly is lifted out is not credited, which errs on the hot side.

## The new fuel elevator

A real plant receives new fuel on the refuel floor, examines it, and lowers it into the spent fuel pool with a **new fuel elevator**: the U.S. EPR's
safety report describes new assemblies being loaded into the new fuel elevator basket, which "lowers the fuel assembly into the spent fuel storage pool for
underwater storage" (NRC, U.S. EPR FSAR Chapter 9, fuel handling: ML14209A587 and ML13220A776), and the APR1400 fuel handling paper (KNS, 17S-060) describes the same
elevator taking fuel from the new fuel racks into the pool. The same documents say administrative controls keep a new assembly from moving over the spent fuel racks
while it is out of the water, and NRC asked about the drop of one onto the pool (Information Notice 80-01 records a new assembly dropped at Pilgrim in 1979). The mod's
elevator does what the elevator does and nothing else: the assembly goes down a guide against the wall and only crosses the rack tops under water.

**Block**: `bwr:new_fuel_elevator` (iron ingots, a hopper, a pool lining block and redstone). It is drawn by its renderer: a yellow stand with a deck, a handrail,
a gantry with a hoist motor and cable drum, a control cabinet with a screen and a beacon, a guide frame (two rails with lips, cross ties and diagonals) down the
inside face of the wall to the floor, a blue carriage on a rope with jaws round the handle of the assembly (Blender, headless:
`art/models/new_fuel_elevator/build_models.py`, exported by `tools-export-new-fuel-elevator.py`; 1,376 triangles in four parts, the guide tiled once a metre).

**Where it goes, and how it finds its pool.** Stand it **on top of the rim of a pool wall**: the block under it is a lining block on the top row of a straight run
of wall, not a corner. The pool is looked for when it is asked (loading, a command, the screen, and once a second after the block is placed or loaded), never
scanned for: among the pool controllers of the level (the registry's) the one whose top row the block stands on, and which is formed, is its pool. The guide hangs in the
interior column against the inside face of that wall, from the rim to the floor (keep that column clear of racks: the carriage passes through them). An elevator
that is not on a formed pool says so ("Not on a pool ..." or "The pool is not formed ..." with the pool's own reason) and does nothing.

**Loading**: right-click it with an assembly, or feed its hatch from a hopper, a pipe or a computer's `pushItems` (an item handler on every face: slots 0 to 3
are the hatch, insert only, slot 4 is the landing, take only). Up to four wait; one goes down at a time.

**Lowering.** The carriage rides down the guide with the assembly to one metre over the rack seat, the assembly leaves the carriage and runs over the rack tops to the
first free cell (racks in the pool's order, cells in order), is lowered onto its seat, and the empty carriage returns. Speeds (game scale, as the bridge's):
0.5 m/s on the guide loaded, 1.0 empty, 0.6 m/s across the racks, 0.3 m/s onto the seat. In the 9 x 9 x 8 pool a trip to the first rack takes about 22 s and the cell is
filled at about 18 s. **The assembly enters its cell at the end of the seating leg**, and the cell is the one the trip was started for unless it was taken
meanwhile (then the next free one, then, if there is none, the landing slot, then the ground: fuel is never lost).

**Refusals, with the reason** (action bar for a right-click, the screen's "Last refusal", `getStatus().lastRefusal`, and a hopper simply waits):

| Refusal | When |
|---|---|
| Not on a pool / The pool is not formed | not on a straight run of the rim of a formed pool |
| Every rack cell is taken (or promised to an assembly already waiting) | the free cells are fewer than the assemblies waiting plus this one |
| The water is too low | less than **1.5 m of water over the handle** of an assembly at the height it crosses the racks (1 m over the seat), the refuelling bridge's hoist limit: a pool 6.5 m deep over 4 m racks |
| Only fuel assemblies go in the hatch / The hatch is full | |

**Raising**: a command, the player's own (the screen's RAISE COOLEST, or `raise()` and `raiseCell(n)` from a computer). The empty carriage goes down; **when it arrives**
the assembly that has cooled longest is taken out of its cell, lifted a metre, slid to the guide and ridden up to the hatch, where it waits (the landing slot) for a hopper, a pipe,
the screen's TAKE FROM HATCH, or a sneaking bare hand. It is refused while the carriage is busy, while one already waits, or when there is nothing to raise.

**Dose.** An assembly out of the water is a radiation source exactly as the bridge's grapple is (`RadiationSources`, a point source at the handle with the gamma half of its
decay heat, `e^(-7.07 x water over the handle)`, nothing in air): the one on the carriage is evaluated from its pose when an instrument asks (the clock is the
same one that draws it), the ones waiting in the hatch and at the landing sit with their handles 1.3 m over the foot of the block, over the water of a full pool.
A just-discharged 45 GWd/t assembly (315 kW) reads about 36 Sv/h 2 m from the hatch and a few mSv/h at the same distance under 2 m of water
(`aHotAssemblyOutOfTheWaterIsARadiationSourceAndUnderItIsNot`). **A hot assembly raised to the hatch is lethal to stand next to**: the screen shows the decay
heat of the one RAISE would bring (amber above 50 kW) and the hatch's decay heat in red. Fresh fuel makes no gamma and is never a source.

**No ticker.** The block has none. A trip schedules two ordinary block ticks (the moment the assembly changes hands with the rack, and the carriage coming home);
the client draws the carriage and the assembly from the trip's start tick and legs, the same numbers `FuelElevatorMotion` (core, pure Java) gives the server,
so a moving elevator costs one packet when it starts and one when it changes hands, and an idle or blocked one nothing. A trip saved with the world resumes where the
clock says it is. Saves: `Hatch`, `Landing` and `Trip` only while there is something in them, `Inward` and `FloorY` once it has found its pool; an old world has none.

**Screen**: the pool it is on, the free cells, the water level and the water over the handle against the 1.5 m needed, the carriage's state, leg and time left, the
assemblies waiting, the one at the hatch, the coolest in the pool, and the last refusal; buttons RAISE COOLEST, TRY AGAIN (a blocked hatch) and TAKE FROM HATCH.
**Computer**: peripheral `bwr_new_fuel_elevator`, `isLinked()`, `getStatus()` (`linked`, `problem`, `state` = idle, waiting, lowering, raising or unlinked, `leg`, `progress`,
`secondsLeft`, `queued`, `landing`, `landingDecayHeatKW`, `inFlightDecayHeatKW`, `freeCells`, `cells`, `waterOverTransferM`, `minCoverM`, `poolLevelM`,
`coolestDecayHeatKW`, `lastRefusal`), `raise()`, `raiseCell(cell)` (rack * 4 + cell from 0, as the bridge numbers them) and `retry()`, each returning `{ok, reason, message}`.

```lua
local lift = peripheral.find("bwr_new_fuel_elevator")
local r = lift.raise()                       -- the program's choice: bring the coolest assembly up
if not r.ok then print(r.reason, r.message) end
```

Honest limits: the carriage cannot be blocked by what is in its way (no collision: it crosses rack tops as the bridge does and passes through racks standing in the guide's
column); one assembly travels at a time; the elevator is not a crane (it cannot lift out of a pool anything but assemblies); a raised assembly is the player's to deal with.

## Swimming in the pool

See [RADIATION.md](RADIATION.md#water-between-you-and-the-source-and-swimming): the shielding is the water **along the line** between a point in the water and the
fuel, and a person in the water of a formed pool takes the dose the meter reads and the mod's effects for it.

## Decay heat

`dev.bwr.core.thermal.AssemblyDecayHeat` evaluates the reactor's own eighteen-group decay heat fit
(`DecayHeat`, tabulated there against the ANS-5.1 infinite-operation curve for U-235 to 1.3 %) in closed form for one
assembly. A group filled by operating for `T` seconds and left to decay for `t` seconds holds
`a_i (1 - exp(-lambda_i T)) exp(-lambda_i t)` of its saturated amount, and the assembly's power is its operating power
times the sum. It is the same solution `DecayHeat.afterOperatingFor` + `projectedFractionOfRated` give (a unit test
checks them to 1e-9).

- **`T` comes from the burnup**: `T = B / p`, with `p` the mod's own core-average specific power, rated thermal power over the heavy
  metal of the 748 reference assemblies, 26.6 MW/t, so the assembly is taken to have run at that power for the whole of its burnup.
  Its operating power is `p` times its heavy metal, 4.8 MW for the 180 kg reference assembly. An assembly from a hot
  position left with more, one from the periphery with less. A short burnup fills only the fast groups, so a bundle
  with a few MWd/t has very little long-lived heat, but not none: half an MWd/t (half an hour at power) is
  already 233 kW at discharge.
- **Checked** against the tabulated reference curve (worst 1.3 %), the reactor's group solution, and the Way-Wigner
  approximation `P/P0 = 0.0622 (t^-0.2 - (t+T)^-0.2)`. Way-Wigner is only a cross-check: it runs 30 to 45 % low in the
  first ten minutes and is within 30 % from an hour to six weeks, which the test records and asserts for that range.

For an assembly at 45 GWd/t (`AssemblyDecayHeatTest`):

| After discharge | Decay power |
|---|---|
| 0 | 315 kW |
| 1 hour | 62.6 kW |
| 2 hours | 50 kW |
| 1 day | 22.8 kW |
| 1 year | 0.88 kW |

## The pool's heat balance

`dev.bwr.core.pool.SpentFuelPool`, pure Java, stepped once a second (`SpentFuelPoolBlockEntity`, every 20 ticks).

- **One body of water**, mass and temperature, in a vertical-sided pool: level `= mass / (1000 * footprint)` metres above
  the floor. Heat in is the decay power of the assemblies the water covers.
- **Evaporation** (below 100 C): `A (p_w - p_a)(0.089 + 0.0782 V) / Y` kg/s, the ASHRAE indoor-pool correlation
  (HVAC Applications, "Natatoriums"), `A` the pool area, pressures in kPa, `V` = 0.1 m/s still hall air, `Y` = 2400 kJ/kg.
  `p_w` is the Antoine saturation pressure (NIST constants for water, 1 to 100 C, within 1 % of the steam tables at 25, 40,
  60 and 100 C in the unit test); `p_a` that of 25 C air at 50 % humidity. The mass leaves the pool with its latent
  heat. A 49 m2 pool loses 29 kg/h at 35 C and 325 kg/h at 80 C. It needs makeup.
- **Passive loss**: 8 W/m2 K from the surface and 1.5 W/m2 K from the wetted floor and walls (about 1.5 m of lined concrete),
  to a 25 C ambient. Game-calibrated round numbers, not vendor data.
- **Boiling.** At 100 C the surplus energy becomes steam at 2257 kJ/kg, the temperature is held and the mass leaves, so the
  level falls at `(decay power - losses) / 2257 kJ/kg`. 1 MW into a 49 m2 pool at the boil is 0.42 kg/s, 3.1 cm an hour; the
  GameTest `withoutCoolingTheWaterBoilsAndTheLevelFallsAtTheEnergyBalanceRate` checks the whole hour against the same balance
  worked out second by second in the test (3017 kg against 3017 kg for 56 six-hour-old assemblies in the 7 m pool).
- **Time to boil and time to uncover** are projections at the present decay power, cooling and losses (the decay power falls,
  so they are worst cases); "never" when the pool is not heating or not losing water. Time to uncover is the time to boil
  plus the time to boil off the water above the highest rack that holds fuel.
- Energy is conserved to 1 % over a two hour heat-up (unit test). A pool in unloaded chunks stands still, as every
  other pool in the mod does; the assemblies' decay clock does not.

Scale: 1 MW into a full 7 x 7 x 5 pool takes 30.1 hours to go from 30 C to the boil (23.9 hours with no losses; the unit test); the same
load in a full 7 x 7 x 7 pool has a no-loss ideal of 27.9 hours. Decay heat falls quickly, so a boil needs a big, fresh load or a small
pool, and the panel's time to boil says which you have. Time to uncover is long: 56 six-hour-old assemblies (about 2 MW) in the 7 m pool
take 48.9 hours from the boil to bring the water down to the rack tops (GameTest).

## Cooling train

The pool controller has a built-in **circulation pump and plate exchanger** (`SpentFuelPoolCooler`), so the player builds the
secondary side and the electricity, not the primary pipes:

- Blue port: cold water in (any water, BWR high-pressure water pipe or a Mekanism pipe; untagged water is 13 C). The
  exchanger holds 12,000 kg of cold and 12,000 kg of warm water, as the RHR exchanger does, and draws up to 400 kg/s.
- Cyan port: the warmed water, pushed into whatever accepts water on that port's face, up to 400 kg/s. If nothing takes
  it the exchanger fills and the heat transfer stops.
- Power: the controller accepts FE (80,000 buffer). The pump draws **400 FE a tick while it runs**.
- **Switch**: the panel's button or the peripheral's `setCooling(true)`. Nothing switches it for the player, and
  without power or cold water it moves nothing and says so.
- Duty: pool water goes through at 150 kg/s, up to 6 MW (the low end of 5 to 20 MW quoted for a full-core offload; one train is
  enough for a part core, not a full one), 80 % effectiveness against the cold side, the cooling water rising at most 11 C. It cannot cool the pool
  below the cold water's temperature, and loses suction below 5 % of the pool's capacity.

GameTest: a 56-assembly pool held at about 2 MW for an hour stays at 19.8 C with the loop (16,062 MJ removed, the pump running
every second) against 30.9 C without it, and the level holds in both.

## Uncovered fuel

An assembly whose rack top (4 m above the floor) is above the water is uncovered. It stops heating the water and heats itself as a 180 kJ/K
lump, losing heat to the air by conduction (20 W/K) and radiation (0.4 m2 of effective area, emissivity 0.7: a rack is a poor
view of the room). Unit-tested outcomes in dry air:

| Decay power | Result |
|---|---|
| 300 kW (just discharged) | cladding bursts at 329 s, zirconium ignites at 554 s |
| 100 kW | bursts at 1047 s, ignites at 1936 s |
| 20 kW (a day old) | bursts after 3.1 h, never ignites, 590 C |
| 10 kW | settles at 378 C, intact |
| 5 kW | settles at 222 C |

Cladding bursts at 565 C (a gap release) and the zirconium ignites at 900 C and then adds 100 kW per assembly until its 50 kg is
used up (NUREG-1738, "Technical Study of Spent Fuel Pool Accident Risk at Decommissioning Nuclear Power Plants", is the source of
the two temperatures; the powers and masses are game scale and none of this is validated). Water covering the assembly again quenches it.

**Release.** A burst adds 5e-4, an igniting assembly 1e-5 a second, each times `min(1, burnup / 10 GWd/t)`, to a hazard index
that decays with the mod's contamination half-life (`contaminationHalfLifeSeconds`) and doses players near the pool through the same
effects as the reactor's contamination (`ReactorSevereAccidentEffects.dose`, scaled by `contaminationDoseMultiplier`). It is saved
only when non-zero. It is a simple tie-in, not a source term.

## Reprocessing gate

The reprocessing plant **will not start on an assembly whose decay heat is above `reprocessingMaxDecayHeatKw`** (server config
`bwr-gameplay.toml`, default **50 kW**; 0 turns the check off). A spent assembly is still accepted into the slot (a pipe may fill it),
but the plant waits, looks again once a second, and starts by itself the moment the assembly has cooled; it never stops one it
has started (the heat only falls). The screen's first line reads "Too hot: 83 kW (limit 50)", the status text says so, and
`bwr_reprocessing_plant` reports `assemblyDecayHeatKW`, `tooHot`, `secondsUntilCool` and `decayHeatLimitKW`. A 45 GWd/t assembly passes 50 kW
after 124 minutes. Unstamped assemblies are long-cooled and go straight in, so every existing save and test behaves as before.

The item tooltip shows an assembly's decay heat and cooling time once it has a stamp.

## Panel and computer

The panel (right-click the controller or any port) shows: water level, water above the fuel, fill, temperature, evaporation and boil-off, assemblies and
cells, total heat and the part heating the water and the part in air, time to boil and to uncover, the cooling train (pump state, heat
removed, water held, power), and one line per rack (assemblies, decay heat, hottest fuel; amber when uncovered or failed, red when burning).
The only control is the pump switch.

CC:Tweaked peripheral `bwr_spent_fuel_pool` (every method on the server thread):

| Method | Returns |
|---|---|
| `isFormed()` | boolean |
| `getStatus()` | `waterKg`, `capacityKg`, `levelM`, `levelFraction`, `temperatureC`, `waterAboveFuelM`, `highestFuelTopM`, `assemblies`, `cells`, `racks`, `decayHeatKW`, `coveredDecayHeatKW`, `uncoveredDecayHeatKW`, `evaporationKgPerS`, `boilOffKgPerS`, `passiveLossKW`, `coolingKW`, `secondsToBoil`, `secondsToUncover` (-1 for never), `boiling`, `fuelUncovered`, `coolingEnabled`, `coolingRunning`, `energyFE`, the cooler's cold and warm water, `damagedAssemblies`, `burningAssemblies`, `releaseHazard`; an unformed pool gives `formed = false` and `problem` |
| `getRacks()` | one table per rack: position, `topM`, `assemblies`, `decayHeatKW`, `hottestFuelC`, `uncovered`, `damaged`, `burning` |
| `getAssemblies()` | one table per stored assembly: rack, cell, fuel, burnup, `decayHeatKW`, `coolingSeconds`, `dischargeRecorded`, `secondsUntilReprocessable`, `fuelC`, `covered`, `damaged`, `burning` |
| `setCooling(bool)`, `getCooling()` | the pump switch |

```lua
local pool = peripheral.find("bwr_spent_fuel_pool")
local s = pool.getStatus()
print(s.levelM, s.temperatureC, s.decayHeatKW, s.secondsToBoil)
if s.temperatureC > 45 then pool.setCooling(true) end   -- the program's choice, nothing does it for you
```

## Look

A translucent water surface at the pool's level, drawn from the picture the controller sends (rim bounds, the level in eighths of a metre, one
glow strength per cell, sent only when the level or a glow step changes). The Cherenkov-style glow is the reactor's own `CherenkovAppearance` fed with the
assembly's decay power as a fraction of its operating power: strong for fresh fuel (0.9 at discharge), a third of that after an hour, nearly nothing after
years, and none for uncovered fuel. Each occupied rack cell shows an assembly head in the block's model (the controller sets `cell_a` to `cell_d`).

## Saves

Controller: `WaterKg` and `WaterC` (when there is water), `Racks` (each assembly as its full item with stamp, plus `FuelC`, `Damaged`, `Ignited`, `Zr` only when
not default), `Cooling`, `Energy`, `ColdKg`/`ColdH`, `HotKg`/`HotH` and `Hazard`, each only when non-default. Formed is surveyed again on load. An
empty controller saves none of them. A pool that is not formed keeps its fuel and water and stands still until repaired. Breaking a rack drops its
assemblies; breaking the controller drops all of them (the water is lost).

## Sources and honest limits

- Decay heat: the mod's `DecayHeat` fit to ANS-5.1; Way-Wigner as a cross-check; real pools cool by days to years, which this mod does in real time as well:
  the clock is the server's game time.
- Evaporation: ASHRAE HVAC Applications, "Natatoriums"; saturation pressure: Antoine equation, NIST constants.
- Zirconium behaviour: NUREG-1738 for the temperatures; everything else in the accident section is game scale.
- Not modelled: pool water chemistry, radiolytic hydrogen, criticality in racks (all assemblies are subcritical in their racks), shielding, leaks, a
  neighbouring-assembly fire spread, a catch-up for the time a pool spent in unloaded chunks.
- The 5 years for an unstamped assembly and the 50 kW reprocessing limit are choices (the limit is configurable).

## Tests

Core (`:core:acceptance`): `FuelElevatorMotionTest` (the legs and their timings, the pose at each moment, when the assembly changes hands, the water rule, the dose in air and under water), `AssemblyDecayHeatTest` (reference points, the reactor's group solution, Way-Wigner, shape, scale, time to cool),
`SpentFuelPoolTest` (steam tables, evaporation, boil-off rate, heat-up, energy conservation, cooling loop, uncovered fuel, makeup, estimates, quench).
GameTests (`SpentFuelPoolTests`, namespace `bwrpool`, `-PbwrOnlyPool` runs just these): formation and refusals, boil-off rate and level fall, cooling
holds and no cooling does not, uncovered fuel heats bursts and burns and is quenched, covered fuel stays at the water temperature, the stamp at unload and
through saves and the pool, the plant refusing hot fuel and starting once it has cooled, items and water connections and breakage, save and reload (and old
saves), and the computer face; `NewFuelElevatorTests` (the elevator: lowering into the first free cell, the real scheduler with its two ticks, a right-click, a hopper, the refusals, raising, the dose along the trip, a save and reload, the old rack and controller paths, the computer face) and `ImmersionDoseTests` (diver and surface swimmer, effects and dose with no meter, creative exempt, the flooded well, readings above the water unchanged). Client check: `:mod:runTurbineModelCheck -PbwrSpentFuelPoolCheck` (pictures in `spent-fuel-pool-check/`), and the screens in
`-PbwrGuiScreenCheck`.

## Pictures (1920 x 1080, `art/spent-fuel-pool/`)

`pool-built.png` (the pool with its three ports), `pool-racks-glow.png` (the 4 m racks under 2 m of water over their tops; the fresh fuel's Cherenkov glow
in the first racks, long-cooled fuel dark), `pool-ports.png`, `pool-panel.png` and `gui-pool.png` (the panel of a running pool), `pool-uncovered.png` and
`gui-pool-uncovered.png` (the water below the rack tops: alarms), `gui-reprocessor-hot.png` (the plant waiting on a 302 kW assembly).

The new fuel elevator: `elevator-on-pool.png` (on the rim of the east wall, guide frame hanging down inside, the pool full), `elevator-lowering.png` (the carriage with an assembly
part way down the guide), `elevator-sliding-over-racks.png` (the carriage waiting at the transfer height while the assembly runs over the rack tops), `gui-new-fuel-elevator.png`
(the screen of a raising carriage), `gui-new-fuel-elevator-water-low.png` (a refusal with its reason: 1.0 m over the handle, 1.5 m needed) and `gui-new-fuel-elevator-hot-at-hatch.png`
(a 52 kW assembly waiting at the hatch, no free cells). Made by `:mod:runTurbineModelCheck -PbwrSpentFuelPoolCheck` and `-PbwrGuiScreenCheck`.
