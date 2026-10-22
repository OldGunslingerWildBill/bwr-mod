# Natural circulation, core melt and containment hydrogen — alpha.30

This update completes the accident chain in [SPEC.md](SPEC.md) sections 4.5 and 8.
Before it, the model stopped at the zirconium-water reaction: the core could
uncover, heat up, oxidise and make hydrogen, but nothing happened next. Now a
loss of cooling carries on to cladding failure, melt, relocation, a failed vessel,
corium attacking concrete, and hydrogen burning in containment. Each stage has a
recovery point until it does not.

No automatic protection has been added. Nothing scrams, starts a pump, inerts or
vents on its own. Every new state is a measurement for your CC:Tweaked programs.

## Natural circulation

With the recirculation pumps tripped or not installed, water still moves through
the core. Steam voids make the core lighter than the downcomer, and that density
difference drives flow.

- Natural circulation flow is `0.366 × (thermal power)^(1/3)` of rated flow. This
  is a floor: pumps running faster than it set the flow as before.
- It needs water above the shroud head. Flow is full with collapsed level at or
  above −60 in and fades to nothing 20 in above the top of active fuel.
- Fuel that has slumped out of the core blocks the flow path in proportion.
- A broken recirculation line or a failed lower head short-circuits the loop:
  water coming down the downcomer leaves through the hole. Natural circulation
  stops, so a recirculation break still loses core flow and inventory together.
- A two-pump trip from rated power, with pressure and level held, settles near
  **23% flow and 26% power** after about four minutes. Five minutes after a scram
  with the pumps off, decay heat alone drives about **11% flow**.
- A reactor built with no recirculation pumps now runs on natural circulation at
  reduced power instead of having zero core flow.

These numbers are calibrated to the shape of a jet-pump BWR power/flow map. They
are not a validated two-phase loop calculation for any plant.

The reactor INFO readout shows the flow when natural circulation is setting it.
CC: `getNaturalCirculationFlow()`; `getStatus().naturalCirculation`.

## Core damage stages

`getCoreDamageStage()` returns the SPEC 8.1 stage. `getCoreDamage()` returns the details.

| Stage | Name | What happens | Recoverable? |
| --- | --- | --- | --- |
| 0 | Intact | — | — |
| 1 | Core uncovery | Collapsed level below top of active fuel | Yes |
| 2 | Cladding heatup | Uncovered fuel more than 100 °C above saturation | Yes |
| 3 | Zirconium-water reaction | Clad above 1,200 °C; oxidation and hydrogen (existing model) | Yes; late reflood causes a quench spike |
| 4 | Cladding failure | Rods burst between 750 and 1,200 °C; gap activity released | No: damage is permanent |
| 5 | Fuel melt and relocation | Core liquefies at 2,200 °C and slumps into the lower plenum | No |
| 6 | Lower head failure | Dry hot debris creeps the vessel bottom until it fails | No |
| 7 | Core-concrete interaction | Corium ablates the drywell floor | No |

Stages 1–3 are current conditions. Stages 4–7 are a permanent record, and
`worstStage` remembers the worst point reached.

### Cladding failure

The failed fraction follows the hottest the clad has been, between 750 °C (first
bursts) and 1,200 °C (all rods). It releases the fuel-clad gap inventory, 5% of
the core's volatile fission products, so the coolant becomes radioactive.

### Melt and relocation

Real cores lose their shape well below the 2,800 °C UO₂ melting point because
molten zirconium dissolves the fuel. The lumped core therefore liquefies at
**2,200 °C**. Heat above that point melts fuel (277 kJ/kg) instead of raising its
temperature. Molten material falls into the lower plenum with a three-minute time
constant.

Relocated fuel takes its decay heat with it. The fuel left in the core heats only
with its own share, and the debris bed carries the rest.

### Debris in the lower plenum

- **Flooded lower plenum:** water quenches the debris and boils. Debris that falls
  in hot puts a burst of heat into the vessel, about 580 MW at first for 30% of the
  core, then cools on a two-minute time constant. The head survives. This is the
  TMI-2 outcome, and it is why injecting into a melting core is still correct.
- **Dry lower plenum:** the debris sits in the 2,000s °C and creeps the lower
  head. A dry 30% bed fails the head in about **35 minutes at rated pressure** and
  about **80 minutes depressurised**. Depressurising still buys time after melt.

Creep damage never heals. Water that arrives after the head has partly crept
stops further damage but does not undo it.

### Lower head failure

The vessel bottom breaches. The vessel drains through it at a rate set by gauge
pressure plus hydrostatic head, about 1,350 kg/s just after failure. All lower
plenum debris leaves the vessel, and anything still molten falls straight through
afterwards.

### Core-concrete interaction

Corium on the drywell floor is cooled by water reaching the floor. Any break's
liquid discharge lands there, including water you inject into a vessel with a
failed head. A flooded floor can remove up to 1 MW/m² (30 MW on a full-size
pedestal). Heat that water does not remove ablates concrete and gives off
non-condensable gas and some hydrogen.

- 30% of a core with no water ablates about 0.14 m in the first hour. With
  50 kg/s of water it does not ablate at all.
- Uncooled corium reaches through the **3 m basemat** in roughly six hours. That
  fails containment.

## Containment atmosphere and hydrogen

Hydrogen from the zirconium reaction and from concrete goes into a lumped
containment of 8,000 m³ free volume at rated size, scaled with reactor power. It
starts as air at atmospheric pressure. **It is not inerted unless you inert it.**

| Condition | Result |
| --- | --- |
| H₂ ≥ 4.1% and O₂ ≥ 5% | Flammable (event and readout) |
| H₂ reaches 8% with O₂ ≥ 5% | **Deflagration**: burns the hydrogen and spikes pressure |
| Mixture already above 13% H₂ when oxygen reaches 5% | **Detonation**: always fails containment |
| Pressure above 140 psig | Overpressure failure |

- After a burn, hydrogen still arriving burns as a standing flame for two
  minutes. A large release causes one burn, not an explosion every tick.
- Burns use up oxygen, and **a sealed containment never gets it back by itself**.
  An oxygen-starved containment can then hold any amount of hydrogen.
- **Nitrogen inerting** (`setContainmentInerting(true)`) purges oxygen toward 2%
  with a 30-minute time constant. It must be done in advance. Inerted, hydrogen
  piles up harmlessly.
- **De-inerting** (`setContainmentInerting(false)`) purges air back in. Doing that
  with hydrogen inside is how a detonation happens.
- A **failed** containment blows down to atmospheric and then breathes outside
  air. Hydrogen stored in an oxygen-starved or inerted containment meets oxygen
  again, which is the Fukushima failure mode.
- **Venting** (`setContainmentVent(true)`) through the suppression pool lowers
  pressure and removes hydrogen. Fission products leaving with the gas are scrubbed
  by a factor of 100. A failed containment leaks unscrubbed.

Containment gas temperature is held constant and steam is not tracked, so steam
inerting is not modelled.

## Releases, contamination and world effects

Fission products released from the fuel (gap, then melt, then core-concrete
interaction) become airborne in containment. They settle onto plant surfaces with
a two-hour time constant, or leave to the environment through a vent or a failure.
Deposited and released activity decays with a **one-day half-life of simulated
time**, so a wrecked plant stays dangerous for a while rather than forever.

Players near the reactor receive effects scaled by the hazard and their distance:
hunger, then weakness and poison, then wither. Environmental releases reach
further at lower intensity. Creative and spectator players are exempt.

| Event | In the world |
| --- | --- |
| Cladding failure, melt onset | Chat notice to players within 96 blocks |
| Core relocation | Notice and a heavy impact sound |
| Lower head failure | Notice, sound, and a blast that breaks the pedestal under the vessel (see "Accident blasts") |
| Basemat melt-through | Notice and sound; the corium pool melts the floor column along the ablation depth |
| Hydrogen deflagration | Explosion (radius 4) that damages entities, and room pressure that breaks weak fixtures |
| Hydrogen detonation | Explosion (radius 10) that damages entities, a loud report, a breach at the containment's weakest spot and heavy damage inside |
| Containment overpressure failure | Notice, sound, a breach at the weakest spot and a jet that sweeps weak fixtures near it |

Explosions damage the world, always. There is no switch for it: accident explosions break blocks whose
blast resistance is exceeded, drop items under the vanilla explosion rules, and are the end of a plant's
containment, not a decoration. What they do **not** break is the formed vessel, the reactor and containment
controllers, bedrock and anything else unbreakable, so the damage to the plant stays readable and the reactor
stays formed. Section "Accident blasts" has the mechanism, the numbers and the exceptions. Events fire once
and are never replayed after a reload; a blast still working through its blocks when the world saves resumes
and finishes, once.

## Accident blasts

Before the accident blast update the explosions here were entity-only (`ExplosionInteraction.NONE`) and the endpoint was
described as "a contaminated, wrecked plant, not a crater". That is gone. The reasoning and the numbers follow;
all of it is `dev.bwr.core.accident.BlastPhysics` (pure arithmetic, covered by `BlastPhysicsTest`) turned into
blocks by `dev.bwr.mod.accident.AccidentBlast`.

### Energy to pressure to broken blocks

1. **Energy.** Burned hydrogen releases 120 MJ/kg. A deflagration delivers 10% of that as a pressure wave (slow
   flame; most of the heat stays in the gas), a detonation 50% (confined, so high). A bursting vessel delivers 40%
   of its stored steam energy, a bursting containment 5% of its stored gas energy (isentropic expansion to
   atmosphere, `P V / (g - 1) (1 - (P0/P)^((g-1)/g))`).
2. **TNT equivalent.** Blast energy over 4.184 MJ/kg. Hopkinson-Cranz scaling then fixes the pressure at range R
   from a charge W as a function of `Z = R / W^(1/3)` alone.
3. **Pressure.** The Kinney-Graham fit to that curve gives the free-air overpressure. A deflagration in a room
   cannot exceed the room's adiabatic isochoric complete combustion pressure, which the containment model already
   computes for every burn, so the curve is clipped to it (10 psig for an 8% burn). A detonation's load on the
   walls is twice the absolute AICC pressure (Chapman-Jouguet over AICC is 1.9 to 2 for hydrogen-air).
4. **Rating.** A block breaks where the pressure at its centre exceeds `K * sqrt(blast resistance)` psi (default
   `K = 5.8`, per-block cap 250): the same number the containment survey uses to rate wall layers, with the same
   data pack overrides, floored at 1 psi so zero-resistance fixtures go at about 1 psi. So concrete is exactly as
   strong against a blast as it is in a wall, and a datapack that makes a block stronger as containment wall makes
   it stronger here too.

Ratings at the default calibration: glass 3.2 psi, oak planks and doors 10.0, vanilla concrete 7.8, stone and iron
blocks 14.2, machine blocks 44.9, vessel steel and obsidian 200.9.

This is a game model on real scaling laws. It has no shock reflection, no venting and no shielding by walls
between the source and a block; a block simply sees the pressure its distance gives.

### Hydrogen deflagration

Pressure-driven damage **inside the containment room**. The sweep is the room's interior box (clipped to the
distance where the blast has fallen to 1 psi), centred on the top of the vessel where the release and the relief
valves are. A block breaks if the clipped pressure at its centre exceeds its rating; the room's own wall layers
lie outside the interior box and are not swept (the walls fail by their column rating, below), so a room built of
concrete keeps its walls through a burn that takes the glass, doors, lamps, signs, torches and loose panels out of
it. With no containment controller the sweep is a sphere around the flame and nothing is spared by position.
Damage scales with the burned hydrogen mass because the mass sets the TNT equivalent and so the reach.

| Hydrogen burned | Combustion | TNT (deflagration / detonation) | Glass breaks out to | Planks, doors | Stone |
| --- | --- | --- | --- | --- | --- |
| 10 kg | 1.2 GJ | 29 / 143 kg | 18 / 31 blocks | 10 / 17 | never / 14.5 |
| 52 kg (8% in 8,000 m3) | 6.2 GJ | 149 / 746 kg | 31 / 54 | 17 / 29 | never / 25 |
| 100 kg | 12 GJ | 287 / 1,434 kg | 39 / 67 | 21 / 37 | never / 31 |
| 200 kg | 24 GJ | 574 / 2,868 kg | 49 / 84 | 27 / 46 | never / 39 |

(Distances are where the unclipped curve falls to that rating; a deflagration "never" breaks stone because it is
clipped to the 10 psig AICC peak of an 8% burn, below stone's 14.2 psi. Reach is capped at 40 blocks from the
source.) Vanilla concrete rates only 7.8 psi, below an 8% burn's 10 psig, so a loose concrete block near the
flame does break; reinforced means a stronger block, or several layers in a wall.

### Hydrogen detonation and containment overpressure failure

The containment is opened at its **weakest spot**, the column the room survey already rates lowest
(CONTAINMENT-UPDATE.md). The weakest column always goes whole. Around it the wall load falls off as
`P / (1 + (d / s)^2)` with `s = 0.5 * W^(1/3)` blocks, and every other exterior wall column whose rating is
below the load at its distance goes too, whole (all its layers, up to the scan depth). The hole's radius is
`s * sqrt(P / R - 1)` for centre load `P` and rating `R`, at most 8 blocks. A sudden failure never loads a wall
less than 1.25 times its rating. Vault doors, airlocks and sleeves jam or rupture at the weakest spot as before;
one that is merely nearby is left standing, as it was built for the pressure.

| Event | Wall load P | TNT | Scale s | Hole radius against a 25 psi wall |
| --- | --- | --- | --- | --- |
| Overpressure failure at 25 psig, 8,000 m3 | 31 psi | 16 kg | 1.3 | 0.6 (the one column) |
| Overpressure failure at 140 psig | 175 psi | 125 kg | 2.5 | 1.25 (a cross of five columns) |
| Detonation of 85 kg H2, 10 psig AICC | 35 psi | 1,219 kg | 5.3 | 3.3 |
| Same, 20 psig AICC | 55 psi | 1,219 kg | 5.3 | 5.8 |

A detonation also sweeps the whole room with the detonation load, falling with distance and clipped to it: glass,
fixtures, wood, loose stone (a 35 to 55 psig load takes anything rated below that, such as machine blocks at
44.9 psi near the source). An overpressure failure sweeps a jet around the hole with the excess pressure across
the failed wall, which is small for a failure that just reaches the rating. Without a containment controller
there is no wall to open and a failure does only its sound.

The room then reports itself unsealed with the leak at the weakest spot, the reactor loses its containment link,
and the atmosphere blows down as it did before.

### Lower head failure and basemat melt-through

A failed lower head drops debris into the cavity. A molten fuel-coolant interaction turns 0.1% of the debris'
1.2 MJ/kg of sensible heat into blast: 12 kg of TNT for 30% of the core, enough to break stone out to 6 blocks
and concrete to 8. The sweep is the **pedestal**: the footprint of the vessel, from the row below the control rod
drives down until the pressure at the block no longer exceeds its rating, at most 8 deep. The corium pool waits
for it, then anchors on the first full-cube block of what is left (CoriumPool), so the ex-vessel pool sits where
the blast left a floor.

Basemat melt-through needs no blast: it is slow. The corium pool already replaces a column of floor blocks below
the molten pool with corium (slag once frozen) along the ablation depth, one block per metre, and one block deeper
in the centre at melt-through. That is unchanged, and it still honours `coriumAltersWorld` (default on) because an
existing test asserts it; the explosion damage has no such switch.

### Vessel head failure

See STEAM-AND-ACCIDENT-UPDATE.md.

### What survives, exactly

- **Never broken, whatever the pressure:** air and pure fluid; any block with `destroySpeed < 0` (bedrock, end
  portal frames, barriers, command blocks); the reactor controller and the containment controller.
- **Never broken by a blast of this reactor:** its formed structure: the whole vessel shell, interior, ports and
  nozzles, spargers, jet pumps and the control rod drive row below the bottom head (the box from one block out of
  the interior, and from two below its bottom, to one above its top). Other formed reactors' structures are spared
  as well, while loaded. Because the vessel stays formed, its damaged-head model, corium, contamination and every
  CC reading stay readable.
- **Spared by pipe whip:** the other pipe blocks of the broken line (see STEAM-AND-ACCIDENT-UPDATE.md).
- **Everything else obeys its rating:** suppression pool walls (31.8 psi) go in a large detonation, machine
  blocks (44.9 psi) near one, pipes and valves (44.9) near a detonation or a head failure, glass and lamps in
  anything.

### Other mods, claims and drops

Every slice of a blast posts `ExplosionEvent.Start` (cancel it and that slice is discarded), a
`BlockEvent.BreakEvent` for each block with NeoForge's standard fake player (cancel it and that block is spared),
and `ExplosionEvent.Detonate` with the surviving list (a protection mod may prune it), then removes the blocks
through `Explosion.finalizeExplosion`, so drops, block entities' contents, block `onExplosionHit` overrides and
the vanilla drop decay (`1 / radius` when the block-explosion drop-decay rule is on, as for TNT) all apply. A claim
mod that blocks the fake player or explosions therefore protects what it claims.

### Chunks, budget and saves

A blast never loads a chunk. A position in an unloaded chunk is **deferred**, not skipped: the job saves the chunk
column and does it, against the block states then, when the chunk loads. A job whose chunks never load stays saved
and silent. Work is bounded per reactor per tick (16,384 positions examined and 1,024 blocks removed), so a large
sweep spreads over ticks, and a reactor with no blast pending spends nothing. The job is saved with the reactor
under `AccidentBlasts` only while it has work left; the core event that starts one is never saved, so a reload
resumes a half-done blast and never begins another.

## Controls and readouts

New CC:Tweaked reactor methods:

- `getNaturalCirculationFlow()` returns the fraction of rated flow.
- `getCoreDamageStage()` returns 0–7.
- `getCoreDamage()` returns a table: `stage`, `stageName`, `worstStage`,
  `permanent`, `failedCladding`, `melted`, `moltenInCore`, `lowerPlenumDebris`,
  `debrisTemp`, `lowerHeadDamage`, `lowerHeadFailed`, `exVessel`,
  `coriumCoolingMW`, `ablationDepth`, `ablationRateMmPerHour`, `basematFailed`,
  `released`.
- `getContainment()` returns a table: `hydrogenPercent`, `oxygenPercent`,
  `hydrogenKg`, `pressure`, `peakPressure`, `flammable`, `inerted`, `inerting`,
  `purgeActive`, `ventOpen`, `failed`, `failureMode`, `burns`, `airborne`,
  `deposited`, `environmentalRelease`, `plantContamination`, `siteContamination`.
- `setContainmentInerting(boolean)` starts a nitrogen purge, or an air purge to
  de-inert.
- `setContainmentVent(boolean)` opens or closes the wetwell vent.
- `getStatus()` adds `naturalCirculation`, `coreDamageStage`,
  `containmentHydrogenPercent` and `containmentPressure`.

The controller INFO readout adds natural circulation, the damage stage, melt and
debris state, lower head creep, ablation, the containment atmosphere, burns and
releases.

## Saves and compatibility

Core damage and containment state are saved next to the existing boundary damage
under a new `SevereAccident` key, and restored when the vessel re-forms. Older
saves load as an undamaged core with an air-filled, uninerted containment. No
GUI or network protocol change: the menu protocol stays at **12**. Blasts still
working through their blocks are saved under `AccidentBlasts`, written only while
non-empty; saves without it load as before.

## Representative station blackout

Physics core only: scram from rated, no pumps, no feedwater, no injection, relief
valves cycling between 1,050 and 1,105 psig, containment not inerted.

| Time | What happened |
| --- | --- |
| 22 min | Top of active fuel uncovers |
| 99 min | Vessel boiled dry; first cladding bursts (stage 4) |
| 135 min | All cladding failed; 5% of volatile inventory released |
| 199 min | Core reaches 2,200 °C and starts to melt (stage 5) |
| 200–255 min | Melt relocates into a dry lower plenum; head creep climbs to failure |
| 255 min | **Lower head fails** (stage 6). Blowdown sweeps steam across 1,400–2,200 °C zirconium, making about 650 kg of hydrogen within 15 minutes |
| 255 min | One deflagration; the standing flame uses the oxygen down to about 2.6% |
| 4–12 h | 82% of the core is on the floor (stage 7). Concrete ablates at about 0.25 m/h; containment pressure rises with core-concrete gas; hydrogen sits near 45–50% with too little oxygen to burn |
| 11.75 h | Ablation 1.98 m of 3 m; containment 53 psig (design 56); oxidation 41%, 1,085 kg hydrogen made; 42% of volatile inventory released from the fuel, 0.017% of it to the environment |

The run ended there. At that rate the basemat would be penetrated a few hours
later. That fails containment, and outside air reaching the stored hydrogen is
the setup for a detonation.

Inject water at any point before 255 minutes and the lower head survives.
Inject after it and the water falls through onto the corium and stops the
ablation. Inert beforehand and the 255-minute burn never happens.

These are results of this game model, not predictions for any real plant.

## Scope and sources

This is a lumped game model. It follows the real mechanisms and orders of
magnitude, but it is not MELCOR or MAAP. Burst range, gap release fraction,
liquefaction and the lower-head and ex-vessel phenomena follow the NRC's
accident source term ([NUREG-1465](https://www.nrc.gov/reading-rm/doc-collections/nuregs/staff/sr1465/))
and the general severe accident literature. Hydrogen flammability limits and Mark I
inerting follow standard practice. Every threshold, time constant and rate is game
calibration and is documented as a named constant in
`dev.bwr.core.accident.SevereAccident` and `ContainmentAtmosphere`.

Phase J added steam mass, gas temperature, condensation, sprays, and linked-pool
heat removal. J-fix4 split the containment into **drywell and wetwell gas
nodes**, joined by submerged downcomers and passive vacuum breakers. Surface
condensation is limited by area, heat-transfer coefficient and temperature
difference rather than an instantaneous cold-pool cap. The DBA test now checks
the two pressure peaks against separate Mark I reference ranges. The model
still lumps each volume internally; it does not resolve local jets, eddies,
stratification or structural pressure waves.

Not modelled: in-vessel steam explosions (the head failure blast is the vessel bursting, not a
fuel-coolant explosion inside it), debris recriticality, hydrogen in the reactor building separately from containment,
igniters and recombiners, and a cleanup mechanic beyond decay.
