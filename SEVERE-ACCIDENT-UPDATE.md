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
| Lower head failure, basemat melt-through | Notice and explosion sound |
| Hydrogen deflagration | Explosion (radius 4) that damages entities, not blocks |
| Hydrogen detonation | Explosion (radius 10) that damages entities, not blocks, plus a loud report |
| Containment overpressure failure | Notice and explosion sound |

Explosions do not destroy blocks, so the vessel stays formed and its damage stays
readable. The endpoint is a contaminated, wrecked plant, not a crater (SPEC 8.4).
Events fire once and are never replayed after a reload.

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
GUI or network protocol change: the menu protocol stays at **12**.

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

Not modelled: in-vessel steam explosions, debris recriticality, hydrogen in the reactor building separately from containment,
igniters and recombiners, and a cleanup mechanic beyond decay.
