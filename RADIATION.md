# Measuring radiation: the survey meter and the area radiation monitor

The plant already models radiation hazards ([SEVERE-ACCIDENT-UPDATE.md](SEVERE-ACCIDENT-UPDATE.md), "Releases,
contamination and world effects"): fission products released from the fuel become airborne in containment, settle
onto plant surfaces, or leave to the site, and players near the reactor get hunger, weakness, poison and wither scaled
by the hazard and their distance. Until now a player could not measure any of it. Two instruments do:

* the **Survey Meter**, a handheld counter and personal dosimeter;
* the **Area Radiation Monitor**, a small box that mounts on a wall or a panel, shows a lamp, and gives panels and
  computers a dose rate and an alarm.

Both read one function, `RadiationField` (`dev.bwr.mod.radiation`), which reads the plant's **existing** models. There is
no second radiation model. Nothing here acts on the plant: the meter reads, the monitor lights its own lamp and drives its
own outputs, and what a program does with the outputs is the player's.

## Units

**Dose rate is in millisieverts per hour (mSv/h); an accumulated dose is in mSv.** Gamma only, so 1 Gy = 1 Sv. Instruments
display the unit that reads best (uSv/h below 1 mSv/h, mSv/h, Sv/h from 1000 mSv/h), but every number that crosses an
interface (signals, CC, the API, the saved dose) is mSv/h or mSv.

Reference points: real natural background is about 0.0001 mSv/h, but the game has none, so the meter reads exactly zero
where the plant has put nothing (its Geiger clicks idle at 0.4 a second for company; that is audio only and never part of
a reading); a "high radiation area" is 1 mSv/h; 1 Sv/h is an hour from radiation sickness; 10 Sv/h is lethal in minutes.

## How the model's hazard becomes mSv/h

The containment model's hazard is in **fraction of the core's fission product inventory**: 1.0 is the whole core, 0.05 the
gap release of a whole core. The conversion (`dev.bwr.core.radiation.DoseModel`) takes everything it can from the model's own
source terms and has exactly one calibrated number.

1. **Gamma power of an inventory.** The contamination half-life is one simulated day, so the inventory is a day old. The
   decay heat model (`DecayHeat`) gives the decay heat of a saturated core one day after shutdown: 0.52 % of rated. Half of
   decay heat is gamma:
   `W = 0.5 x 0.0052 x 3579 MW = 9.4 MW`.
2. **Spread through containment.** The containment's free volume is `V = 8000 m3` (`FREE_VOLUME_AT_RATED_M3`), an equivalent
   sphere of radius `R = 12.4 m`, which is also the model's in-plant half distance (12 blocks). A person at the centre of a
   uniform volume source gets
   `D = (W / V) x (mu_en/rho)tissue x (1 - e^(-mu_air R)) / mu_air = 1.5e5 Sv/h`, with 0.0031 m2/kg for soft tissue at 1 MeV
   and 0.0076 per m for air. That is the bare figure.
3. **Structure.** Pedestal, piping, pool, steel and concrete stand between a person and most of the activity:
   `STRUCTURE_SHIELDING_FACTOR = 1/100`, the one calibrated number, checked against published accident readings
   (Fukushima Daiichi unit 2 pedestal, about 530 Sv/h, is a few tenths of an inventory here; TMI-2 a few Sv/h at a percent).

The result is **1.5e6 mSv/h per unit of hazard** (`DoseModel.MSV_PER_H_PER_INVENTORY`), so the existing effect thresholds land
where radiology puts them:

| Hazard (inventory fraction) | Existing effect | Dose rate |
| --- | --- | --- |
| 1e-4 | hunger | about 150 mSv/h |
| 1e-3 | weakness and poison | about 1.5 Sv/h |
| 1e-2 | wither | about 15 Sv/h |
| 1e-1 | wither III | about 150 Sv/h |
| 0.05 (gap release) at 12 blocks | | about 38 Sv/h |

The game's effects are deliberately mild next to the real doses; the meter is honest about the number.

## What a reading is made of

`RadiationField.read(level, position)` returns each term in mSv/h, and the total is their sum:

| Term | Source | Falloff |
| --- | --- | --- |
| `airborne` | activity airborne in containment (`getAirborneFraction`) | in-plant hazard: `x / (1 + d^2 / 12^2)` |
| `deposited` | activity settled on plant surfaces, decaying with the contamination half-life (`getPlantContaminationIndex` less the airborne part) | the same |
| `release` | the decaying environmental release (`getSiteContaminationIndex`) | site hazard: `20 x x / (1 + d^2 / 64^2)` |
| `core` | gamma from the fuel itself, running or shut down | inverse square from the core, behind its shielding |
| `other` | everything registered through `RadiationSources` | its own |

The first three use the formulas the game applies to a player, `ReactorSevereAccidentEffects.plantHazardAt` and
`siteHazardAt` (the effects code calls the same two functions), with `d` the distance to the reactor controller block, and
stop at the model's own 256 blocks (so does the core term, and a source of your own: beyond 256 m a source reads zero, and a
core making under a watt of gamma emits nothing). A reading and what the game does to a player standing there cannot disagree.

**The core.** Gamma power is `0.07 x fission power + 0.5 x decay heat` (about 7 % of fission energy leaves as prompt and
capture gamma). It is a point source at the middle of the active fuel, `W x 0.0031 x e^(-0.0076 r) / (4 pi r^2)`, times the
transmission of what is in the way:

| Between you and the fuel | Transmission |
| --- | --- |
| Vessel wall, 15 cm of steel | 8.5e-4 |
| Water around the core, 1 m, scaled by how much of the fuel is covered | 8.5e-4 covered, 1 uncovered |
| Biological shield and building | 1e-4 |
| (Looking down an **open head**, over the vessel opening) only the water above the fuel; from a point in the water, the water on the line to the fuel above its top | `e^(-7.07 x depth)` |
| (After the **lower head fails**) the vessel wall and its water are gone | |

So a shielded vessel at power reads a couple of tenths of a mSv/h at 10 m and a hundredth of that a day after shutdown; an
uncovered core inside a closed vessel reads about 1200 times higher; looking down an open vessel with the fuel under 1.2 m of
water reads hundreds of Sv/h, and under 3 m of water 0.1 mSv/h. Beside an open vessel the walls still shield. Positions are
the middle of the interior and the top of active fuel. The core term, like the others, stops at 256 blocks.

## Water between you and the source, and swimming

Water shields at `e^(-7.07 x depth)`, and the depth is **the water on the line between the dose point and the source**. Two cases, one function
(`DoseModel.waterPathM`), used by the spent fuel pool's racks (`PoolRadiation`) and by the open reactor (`RadiationField.coreDoseRate`):

* **A point at or above the surface** (a person on the deck, the rim, the refuel floor, a meter on a bridge): as it has always been, the water straight over the
  source, `surface - top of the rack` (or `surface - top of the active fuel` over an open vessel), however the point is placed beside it. Nothing about these
  readings changed (`readingsAboveTheWaterAreExactlyWhatTheyAlwaysWere`, and the existing bridge, crane and cavity dose tests).
* **A point in the water** (inside the pool's interior and under its surface, or inside an open vessel's footprint or its flooded well and under the water): the length
  of the straight line from the source to the point that lies in the water. It gets no credit for the water above it. Straight over the source that is the depth between
  the point and the rack top; beside or level with it, the whole distance. Over an open vessel only the part of the line above the top of the fuel counts, since the fuel
  is not shielding.

The difference is large and it is the point. A diver 1 m over a rack of four just-discharged assemblies is under 1 m of water, not under the 3 m over the rack tops that the
old rule gave a person who is in fact above the surface: **about 480 Sv/h** against 0.3 mSv/h. A swimmer at the surface over 3 m of water reads about 2 mSv/h
(`aDiverNearFreshFuelIsHurtAndASwimmerAtTheSurfaceIsNot`, `IMMERSION POOL` in its log). In the well the same: a metre over the fuel of a just-scrammed core under 4.5 m of water reads
hundreds of Sv/h, at the surface a trifle (`aDiverInAFloodedWellOverAnOpenVesselIsHurtAndOneAtTheSurfaceIsNot`).

The pool's water is a body in the pool's model, drawn as a surface; "in the water" is geometric (the middle of the body, where the meter measures, below that surface and inside
the interior), so it holds with or without vanilla water blocks in the pool, which the pool allows. The well's is the physical level over the fuel, or the drawn surface where that
is higher (the two can differ by a metre or two, REFUELLING-BRIDGE.md), so a player in the water they can see is in it. A point in the shaft over an open vessel (the cells of the surveyed
well, outside the vessel's own footprint) reads as looking down the open head, as the footprint does, instead of through the vessel wall.

### Swimming hurts

`dev.bwr.mod.radiation.ImmersionDose` makes it hurt. A player in the water of a **formed pool** or of an **open reactor with water over the fuel and its well** is sampled **every 10
ticks**, staggered by player (a player not in such water costs one integer test a tick). The sample reads exactly what the survey meter reads (`RadiationTracker.read`, the same
`RadiationField`), so the meter and the harm cannot disagree, and then:

* **adds the dose** of the interval to the personal dose, **whether or not a meter is carried**. A person in the water is exposed whether or not they can measure it, so the
  dose is recorded and a meter picked up afterwards shows it. With a meter carried the meter's own sampler records it and it is not added twice;
* **applies the effects** of the contamination model for the hazard that dose rate stands for (`DoseModel.hazardFromDoseRate`, the inverse of the table above): hunger from
  about 150 mSv/h, weakness and poison from 1.5 Sv/h, wither from 15 Sv/h, wither III from 150 Sv/h. A diver beside fresh fuel is dead in seconds to a minute; a swimmer at
  the surface of a deep pool has no effect and a dose of microsieverts. The contamination multiplier of `bwr-gameplay.toml` is not applied (it scales the plant's airborne activity,
  and the reading has to equal the harm);
* **tells the player once** when the rate first reaches the hunger threshold: "You feel a burning warmth on your skin. The water here is not safe: get out. Dose rate ..., your
  dose so far ...", and again after they have been out of it or below it.

Creative and spectator players are exempt (nothing is recorded, no effects, no message). The effects are keyed to the dose **rate**, as the contamination effects are; the dose
accumulates and is on the meter, but nothing is done to a person for the total (acute radiation sickness is not modelled). A player in a **dry** pool beside the fuel, or on the rim, is
not sampled: the uncovered-fuel accident's own contamination (`SPENT-FUEL-POOL.md`) and the meter cover them.

## Shielding: blocks between you and a source

Blocks a player places between a **point source** and the dose point shield it (`dev.bwr.mod.radiation.BlockShielding`, arithmetic in
`dev.bwr.core.radiation.Shielding`). The straight line from the source to the point is marched through the block grid (a 3D DDA, Amanatides and
Woo), each block gets the true length of the line inside it, and what gets through is

`T = product over the blocks crossed of 2^(-path_cm / HVL) = e^(-sum mu x path)`,

times the source's own transmission and the inverse square and air of `DoseModel.pointSourceMsvPerH`. A slanted line through a wall goes
through more of it than a straight one; a line that clips a corner of a block gets the few centimetres it crosses, not a whole block. Both
instruments, the CC `other` term and the swimming dose read the shielded value with no change of their own.

### Half-value layers (one energy, about 1 MeV)

| Material (family tag `bwr:radiation_shielding/...`) | HVL used | Narrow beam, NIST XCOM 1 MeV | What counts |
| --- | --- | --- | --- |
| Lead (`lead`) | **1.0 cm** | 0.86 cm (mu/rho 0.0710 cm2/g x 11.35 g/cm3) | `bwr:lead_block`, `#c:storage_blocks/lead` (other mods' lead blocks) |
| Steel (`steel`) | **2.5 cm** | 1.47 cm (iron, 0.0600 x 7.87) | iron, gold, copper, netherite and steel blocks, iron bars, doors, trapdoors, anvils, chains, cauldrons, hoppers |
| Concrete (`concrete`) | **6.0 cm** | 4.6 cm (ordinary concrete, 0.0650 x 2.30) | vanilla concrete, stone, deepslate, cobblestone, ores, obsidian, bricks, terracotta, sandstone, end stone, netherrack; Immersive Engineering concrete (optional tag entries) |
| Leaded concrete (`leaded_concrete`) | 3.5 cm | | Immersive Engineering leaded concrete (optional tag entries) |
| Shield plug concrete, tiers 1 / 2 / 3 | 5.0 / 3.5 / 2.5 cm | | the mod's three shield plug concrete blocks, progressively stronger (more steel, then netherite); a section lifted off by the reactor building crane is not in the world while it hangs, so it shields nothing until it is set down (BUILDING-CRANE.md) |
| Water | **9.8 cm** | 9.8 cm | water blocks and the water of waterlogged blocks: the dose model's own 7.07 per m |
| Earth (`earth`) | 9 cm | | dirt and its kin, sand, gravel, clay, concrete powder, soul sand |
| Wood (`wood`) | 23 cm | | logs, planks, wooden slabs and stairs |
| Glass (`glass`) | 5.5 cm at **1 % fill** | | glass blocks and panes count as a 1 cm glazed panel: about 12 % off per block |
| Air, and every block with no entry | none | | torches, plants, machines, and the mod's formed structures (below) |

**Why these numbers.** Lead, steel and concrete use the rounded **broad-beam** half-value layers of shielding practice, which include the build-up of
scattered photons in a thick wall: typical health physics tables (for example Cember and Johnson, *Introduction to Health Physics*) give about 1.2,
2.1 and 6.2 cm for cobalt-60 and 0.65, 1.6 and 4.8 cm for caesium-137. A wall a player builds is a thick broad-beam shield, and the broad-beam value
is the conservative one (more gets through than the narrow-beam figure says); the narrow-beam NIST values are in the table for comparison. Lead's
1 cm is also the default `lead_hvl_cm` of the RTG fuel list ([RTG.md](RTG.md)), so a lead block and the Large RTG's housing agree. **Water** keeps the
dose model's own coefficient, so a water block and the pool's water are one number. Earth, wood and glass scale concrete's mass half-value layer
(13.8 g/cm2; at 1 MeV the mass attenuation of light materials is nearly the same) by their density (1.5, 0.6 and 2.5 g/cm3).

At block scale this is decisive: one block of lead is 100 half-value layers (2^-100, nothing measurable gets through), one block of concrete about 17
(a hundred-thousandth), one block of steel 40, one of earth 11, one of wood about 4, one of glass an eighth off.

**One energy.** Every source is shielded as a 1 MeV emitter, the energy the dose model uses for everything. Per-source energy was left out: only the
RTG fuels carry one (`lead_hvl_cm`), the plant's sources (fuel, crud, resin) are broad fission and activation spectra, and one table is what a player
can learn. For a low-energy emitter (americium-241, 60 keV) the shielding is therefore conservative; for cobalt-60 behind a thick wall it is close.

**Partial blocks.** A block's share of its cell is its collision shape (a slab is half a block of its material); a data map entry can give a fixed
`fill` instead (glass). A waterlogged block adds water in the part of the cell it does not fill. The light blocks the floodlights place
(`minecraft:light`) are air; a waterlogged one is the water source it was placed in, so switching a lamp on or off never changes a reading.

### Materials are data

The block data map `bwr:radiation_shielding` (`data/<namespace>/data_maps/block/radiation_shielding.json`) gives a block or a block tag
`{"hvl_cm": <cm>, "fill": <0..1, optional>}`. The mod's file maps the family tags above and the shield plug tiers; a pack adds a block to a family
tag, or gives it its own entry. Immersive Engineering is never a dependency: its blocks are optional (`"required": false`) entries of the family tags.
A datapack reload recomputes every block and drops every cached ray. **Block of Lead** (`bwr:lead_block`): nine lead ingots, and back; a stone
pickaxe or better; `c:storage_blocks/lead`.

### Nothing is counted twice

* **Formed structures.** The blocks in `bwr:radiation_shielding_exempt` never count, whatever the data map says: the reactor and its machines
  (`#bwr:reactor_blocks`), the spent fuel pool's lining, racks, ports and controller, the new fuel elevator, the RTG and the Large RTG. Their shielding
  is in their sources' calibrated numbers.
* **A source's own housing.** The cell the source is in is not counted, nor the source's own box: the spent fuel pool's racks skip the pool's whole
  walls-and-interior box (lead dropped into the pool does not shield its own racks; the pool's water already does), the Large RTG its footprint (its
  40 cm lead-equivalent housing is in its number).
* **Water.** A source whose transmission already holds the water on the line (the pool's racks, an assembly on the bridge, the crane, the elevator, the
  equipment pads) does not count water blocks again, so a pool full of vanilla water reads exactly what it did, and swimming (`ImmersionDose`,
  `DoseModel.waterPathM`) is unchanged. Any other source behind a water block gets the dose model's water for it.
* **The reactor core term is not marched.** Its calibrated 1e-4 "biological shield and building" is the shielding around the reactor, the containment
  the player built among it. The contamination terms (airborne, deposited, release) are spread through containment and the site, not point sources,
  and are not marched either.

Every reading the existing tests pin (the pool above and in the water, the bridge, the crane, the cavity, the RTGs, the radiation tools) is unchanged.

### Cost and limits

Nothing ticks and nothing scans. A ray is marched only when an instrument samples (a held meter every 5 ticks, a monitor at most every 5 ticks, a
swimmer every 10), only for a source within the dose model's 256 m (the ray length cap; beyond it the source reads zero anyway), and stops once the
transmission is zero. Only **loaded** chunks are read (`getChunkNow`): a chunk that is not loaded is never loaded or asked for, and the part of the
line inside it counts as **no shielding** (a meter can over-read across the edge of the loaded world; it never under-reads). A ray is cached per
source position, dose-point block and options for 40 ticks; a block change that notifies its neighbours (a player placing or breaking, a fluid, an
explosion, `/setblock`) in a chunk the ray crossed, or that chunk loading or unloading, drops it at once, and a silent change (a piston's move) is
seen when it expires. A ray through an unloaded chunk is not cached. The meter shows the shielded reading; it does not show a lead-equivalent
thickness (`BlockShielding.trace(...).leadEquivalentCm()` has it for code). Cost measured in `ShieldingTests.aSampleInADensePoolStaysCheap`
(`SHIELDING PERF` in the log), on the
owner's PC with other games running: a 25 x 25 pool with 46 loaded racks (184 assemblies) under a concrete floor, 40 sample points, **310 to 490 us a sample cold
(46 marches, 7 cells read each) and 35 to 107 us warm** (all cache hits) over two runs; 400 free point sources behind lead walls, one meter, 3.3 to 6.4 ms
cold and 0.09 to 0.32 ms warm.
A held meter samples 4 times a second.

## The Survey Meter

* **Hold it** (either hand): the server samples the dose rate at you four times a second (every 5 ticks, staggered by player)
  and sends it to you alone. The HUD shows the dose rate and your dose, coloured green, yellow, orange, red by level, and the
  meter **clicks like a Geiger counter**: the vanilla comparator click, pitched up, played at random with a mean rate of
  `0.4 + 2.5 x log2(1 + uSv/h)` a second, 40 at most (3 a second at 1 uSv/h, 25 at 1 mSv/h; compressed because a speaker
  cannot play eight decades). No reading, no readout, no click.
* **Carry it** (anywhere in your inventory): your **personal dose** accumulates in mSv, stored on the player as a data
  attachment (saved only above zero, kept through death). Creative and spectator players read but record nothing.
* **Right-click** shows the reading and your dose on the action bar; **sneak-right-click resets your dose**. A reading adds
  nothing to the dose.
* Recipe: a copper ingot, four iron ingots, a glass pane and a comparator.

No scan runs per tick: a player with no meter costs one integer test per tick; a sample is a loop over the formed reactors of
the level and the registered sources.

## The Area Radiation Monitor

Recipe: a copper ingot, five iron ingots, two glass panes and a comparator. Mounts on **any face** of any block (the face you click) with its front out, wall, floor, ceiling or the back of a panel.

* **Lamp**: dark; **amber** at the **alert** setpoint; **red** at the **alarm** setpoint. The alert setpoint is a tenth of the
  alarm setpoint; the default alarm setpoint is 1 mSv/h. A lamp or output that is on stays on until the reading falls below
  90 % of its setpoint. The block glows (light 4 amber, 8 red) and is a **redstone** source: 7 at alert, 15 at alarm.
* **Empty-hand right-click**: the reading and the setpoints on the action bar. **Empty-hand sneak-right-click**: the next
  alarm setpoint from 0.01, 0.05, 0.1, 0.5, 1, 5, 10, 50, 100, 1000 mSv/h, wrapping (needs permission to edit there). A
  program or a panel knob can set any value from 0.001 to 1,000,000 mSv/h. With a wrench or the linker in hand the click
  goes to the tool.
* **Saves** `HighSetpoint` only when it is not 1 mSv/h; there are no monitors in old worlds.
* **Performance**: no block ticker. Loaded monitors register with one service, which walks them once a second to update the
  lamp (an integer test per server tick with none loaded). Anything that asks a monitor for its reading (a panel's input
  sampler, a computer) is given the last measurement if it is under 5 ticks old, so a monitor costs one `RadiationField`
  evaluation per 5 ticks however many panels and computers read it.

### Wiring

The monitor is a device of the signal layer ([SIGNALS.md](SIGNALS.md#area-radiation-monitor)), so the **Networking Wrench**
selects it, its Networking screen lists its signals, and a panel component binds to them exactly as to any machine.

| Signal | Unit / kind | Notes |
| --- | --- | --- |
| `dose_rate` | mSv/h, quantity DOSE_RATE | gauge input |
| `high_radiation` | BOOLEAN | at or above the alarm setpoint; an annunciator or lamp input |
| `radiation_alert` | BOOLEAN | at or above the alert setpoint |
| `alarm_setpoint`, `alert_setpoint` | mSv/h, DOSE_RATE | |

Command: `alarm_setpoint` (0.001 to 1,000,000 mSv/h), a panel knob's or a program's explicit choice.

**Gauge compatibility.** There is a new quantity, **DOSE_RATE** (units mSv/h, uSv/h, Sv/h), appended after TEXT so no existing
ordinal moves. A gauge whose **Measures** setting is Dose rate takes only these signals and refuses a level, a pressure or a
power with the usual "This gauge measures DOSE_RATE; ..." (a line link and a CC port alike); a gauge that measures anything
else refuses the dose rate; ANY takes everything as before. A linear gauge is a poor fit for eight decades: set its Max, or
use Scale and Offset on the binding, to the range you care about (0 to 10 mSv/h is a good area scale).

### Computers

Peripheral **`bwr_radiation_monitor`** (every face):

| Method | Returns |
| --- | --- |
| `getDoseRate()` | mSv/h |
| `isHighRadiation()`, `isAlert()` | booleans |
| `getAlarmSetpoint()`, `getAlertSetpoint()` | mSv/h |
| `setAlarmSetpoint(mSvPerHour)` | `true`, or `false` outside 0.001 to 1,000,000 |
| `getStatus()` | `doseRate`, `unit` ("mSv/h"), `highRadiation`, `alert`, `lamp` (0, 1, 2), `alarmSetpoint`, `alertSetpoint`, and the dose rate by source: `airborne`, `deposited`, `release`, `core`, `other` |

plus the machine methods every device has (`getName`, `setName`, `getPort`, `setPort`). Reading the dose rate of a monitor
from a computer on a CC wired network by **port** works like any other signal. Example:

```lua
local monitor = peripheral.wrap("bwr_radiation_monitor_0")
while true do
  local s = monitor.getStatus()
  print(string.format("%.3f mSv/h  core %.3f  airborne %.3f", s.doseRate, s.core, s.airborne))
  if s.highRadiation then redstone.setOutput("back", true) end   -- your program's choice, not the mod's
  sleep(1)
end
```

## Adding a source of your own (spent fuel pool, irradiated assemblies, a hot sample)

`dev.bwr.mod.radiation.RadiationSources` is the extension point. Everything is server thread, in mSv/h; the arithmetic of a
point source behind shielding is `DoseModel.pointSourceMsvPerH(gammaWatts, distanceMetres, transmission)`. A point source set with `emit` is also shielded by the blocks on the line (see Shielding); a provider that wants the same calls
`BlockShielding.pointSource(level, source, at, gammaWatts, transmission, waterModelled)`.

```java
// 1. A provider for a whole kind of source, registered once. Called with the level and the point asked about.
RadiationSources.register(ResourceLocation.fromNamespaceAndPath("bwr", "spent_fuel_pool"),
        (level, at) -> pools.in(level).stream().mapToDouble(p -> p.doseRateAt(at)).sum());

// 2. A point source that comes and goes with a block entity: set it when it changes, clear it when it is removed or unloads.
RadiationSources.emit(level, blockEntity, Vec3.atCenterOf(pos), gammaWatts, transmission);
RadiationSources.clear(level, blockEntity);
```

The new fuel elevator's assemblies (a provider evaluated from the trip's clock, `NewFuelElevatorBlockEntity.registerRadiation`), the spent fuel pool's racks (a provider, `PoolRadiation`) and an assembly on the refuelling bridge's grapple (a point source) are registered this way, behind the water over them: see [REFUELLING-BRIDGE.md](REFUELLING-BRIDGE.md#water-depth-and-dose).

 Carried isotope capsules (`CarriedIsotopes`) and the RTG block (`RtgBlockEntity`) are sources added this way: see [RTG.md](RTG.md).

`DoseModel.INVENTORY_GAMMA_WATTS` (9.4 MW) is the scale of a whole core inventory a day after shutdown. A provider that
throws is skipped and logged once. Sources beyond 256 m read zero. Both instruments and the CC `other` term pick sources up
with no further work.

## Tests

* `:core:acceptance` runs `DoseModelTest`: the inventory scale, the effect thresholds, shielding, open head, inverse square,
  the click rate, formatting, the water path along the line (above and in the water, the open head's fuel top, continuity), and the hazard a dose rate stands for.
* `:core:acceptance` runs `ShieldingTest`: one, two and three half-value layers give a half, a quarter and an eighth; the march gives each block its
  true length (straight, diagonal, a clipped corner, along a face, through an edge); water counted once and as the dose model's water.
* GameTests `ShieldingTests` (namespace `bwrshield`, batch `shielding`): the materials table as data, a lead wall between an RTG (and a carried
  caesium capsule) and the meter, a concrete bunker and the shield plug tiers, glass, air and non-solid blocks, formed structures and the pool's water
  not counted twice (the pool's readings unchanged with water blocks and lead in it, the immersion dose unchanged), an unloaded chunk on the line, the
  cache on placing, breaking and expiry, and the cost in a dense pool. Client check `-PbwrShieldingClientCheck`: a lead cask around an RTG, the meter
  inside and outside.
* GameTests (`RadiationToolsTests`, `RadiationCcTests`): the meter reads 0 at background; non-zero and rising near a
  contaminated plant; falls with distance exactly as the effects model does, term by term, and reads nothing past 256
  blocks; a closed vessel reads low outside and an open head high looking down; the dose accumulates, persists in the
  player's data and resets; the API; the monitor drives a wired gauge, an annunciator, its lamp and its redstone; incompatible
  gauge kinds are refused; the setpoint and its saves; the lamp's hysteresis and the 5 tick reuse; CC fields; the recipes.
* Client check `-PbwrRadiationClientCheck`: 1920 x 1080 screenshots of the meter held near a contamination source with the
  HUD, and of a monitor on a panel beside a gauge and an annunciator, in alarm.
