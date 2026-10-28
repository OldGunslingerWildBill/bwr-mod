# Turbine auxiliaries: lube oil, turning gear, generator cooling

A turbine-generator needs more than steam. Bearings need oil while the shaft turns, a hot rotor must be rolled slowly after a
shutdown or it bows, and a 1,500 MW generator must have its winding cooled. These are the machines that do that. All the
numbers are game calibration, not a manufacturer's curves. The physics is `dev.bwr.core.turbine.TurbineAux` (pure functions,
`TurbineAuxTest`); the train step is `dev.bwr.mod.power.TurbineAuxTrain`.

**House rules, as everywhere in the mod.** Nothing acts on its own. A pump runs when you switch it on, the turning gear engages when
you engage it, and nothing trips the turbine or sheds load when an alarm reads: the alarms are readings for your panel, your
annunciators and your computer. Skids have no ticker; the shaft train steps them once a tick, so a skid with no train does nothing.

## What you build

| Block | Where it goes | What it is |
| --- | --- | --- |
| **Turbine Main Oil Tank** (`main_oil_tank`) | Against any section of the train, on any face of the section | 20,000 L of turbine oil, the **AC auxiliary oil pump** (FE on the side and bottom faces), the **emergency DC oil pump** (FE on the top face: wire a battery), the **oil cooler** (cooling water in on the left flange, warmed water out on the right, seen from the front) |
| **Turbine Shaft-Driven Main Oil Pump** (`shaft_oil_pump`) | On the **free shaft end of the generator**, coupling (its back) toward the generator. It turns to face the right way when you place it there | Driven by the shaft: no oil at standstill, full delivery at 90 % of rated speed. It draws its oil from the Main Oil Tank of the same train |
| **Generator Cooling Skid** (`generator_cooling_skid`) | Against the generator | **Stator water pumps** (FE on any face but the two flanges), a **hydrogen** charge for the casing, and the **coolers** (cooling water as on the oil tank) |
| **Turning gear** | Built into the generator: a switch, no block | See below |
| **Turbine Oil Drum** (item) | Right-click a Main Oil Tank | 1,000 L of oil. A drum that does not fit is not poured. Crafted from coal and an iron ingot; **with Mekanism** from four Turbine Oil Cans (made in a Pressurized Reaction Chamber) and a steel ingot, see [MEKANISM-RECIPES.md](MEKANISM-RECIPES.md) |
| **Hydrogen Cylinder** (item) | Right-click a Generator Cooling Skid | 5 kg of hydrogen (the casing holds 20 kg, 30 psig). A cylinder that does not fit is not emptied |

A skid joins the train the moment it stands against it (or the section is set down against it); the module keeps the link in its save.
Several tanks or skids on one train add up: more oil, more pumps, more cooling. Cooling water comes through ordinary High-Pressure
Water Pipe; the flanges take it exactly as the condenser's do.

## Lube oil and the bearings

**Oil pressure is the sum of what the running pumps deliver.** Each pump delivers a share of the flow the bearings need at rated speed:

| Pump | Delivers | Runs when |
| --- | --- | --- |
| AC auxiliary pump | 70 % | switched on and the AC bus has 5,598 FE/t (75 kW) |
| Emergency DC pump | 45 % | switched on and the DC bus has 2,239 FE/t (30 kW) |
| Shaft-driven pump | `(speed / 90 % of rated)^2`, at most 100 % | the shaft turns, and the tank has oil |

Header pressure is 25 psig at exactly the flow needed (capped at 120 %). A pump draws from the tank, so it delivers nothing from an
empty one (the suction falls off under 5 % level). The bearings need a flow of `0.4 + 0.6 x speed/rated`, and the **oil film** is the
delivered flow over the need, 0 to 1.

- **Start-up needs the AC pump.** At 30 % speed the shaft pump alone gives a film of 0.19. The AC pump carries the shaft until the
  shaft pump takes over near speed. The AC pump alone cannot carry a shaft at full speed (a film of 0.7), and neither can the DC pump.
- **The DC pump is the emergency pump.** On a coasting shaft (the existing shaft model coasts for hours) it holds the film; at speed
  it does not. It does not start by itself: switch it on from the screen, a panel or a program when the AC bus fails, or leave it on.
- **Bearing temperature** relaxes toward the oil temperature plus a friction rise (28 K at rated speed, growing as `speed^1.2`), time
  constant 90 s. A missing film multiplies the rise by up to 61 and replaces the oil by room air. **No oil pressure while the shaft is
  rolling above about 15 % speed: bearing metal passes 115 C within about 15 s and the shaft takes bearing damage, 1.2 % a second per
  60 K above 115 C.** That damage is the turbine's existing shaft damage (the counter overspeed and wet-steam wear add to); there is no
  second model. Alarm at 100 C. **Past 300 C (about a minute at rated speed) the oil left in the bearing's housing catches fire** (200 L
  out of the tanks), and running pumps then feed the fire: see Turbine fires in [TURBINE-MAINTENANCE.md](TURBINE-MAINTENANCE.md). A train
  whose tanks hold no oil has nothing to burn.
- **Hot oil.** Oil temperature is bearing heat (3 MW at rated speed) minus the cooler and 15 kW/K to the hall, over the oil's thermal
  mass. The cooler is a 150 kW/K exchanger on 20 kg/s of cooling water: with 13 C water the oil settles near 50 C, with no water it
  keeps climbing (about 0.17 K/s at 10,000 L). Hot oil lifts the bearing temperature one for one, so a failed cooler takes minutes
  to hurt where no oil takes seconds. Alarm at 65 C.
- **Oil is consumed.** A pumped tank loses `0.9 L/s x (pressure / 25 psig)`, doubling with every 15 K above 50 C (it oxidises and
  thins). Refill it with Turbine Oil Drums (with Mekanism installed, keeping a train supplied is a production chain: MEKANISM-RECIPES.md). Alarm under 30 % level.

## Turning gear and the rotor bow

The turning gear is a motor-and-gear on the generator: **a switch** on the generator's screen (button **Turning gear**), the signal
and command `turning_gear` (a panel switch through the Networking Wrench, or `setTurningGear` over CC). It draws 1,119 FE/t (15 kW)
while it rolls the shaft. The motor is on the **AC bus of the Main Oil Tank** (the same FE input as the AC oil pump, side and bottom
faces), so the generator keeps its one FE face, the copper terminal. With nothing on the bus the gear does not turn.

- It **rolls the shaft at 3 rpm** and never lets a shaft at or under its overrun speed stop. **It cannot be engaged above 10 rpm** (the
  switch refuses and says why). Above 10 rpm, with steam on, the clutch overruns: it neither turns the shaft nor draws power, and the
  switch stays where you left it. Nothing engages it for you.
- The rotor metal of each turbine section soaks up steam heat (300 s) and cools slowly (4 h). A rotor above 120 C **at standstill**
  (under 1 rpm) bows: full bow in about 15 minutes at 285 C. A rotor on the gear straightens over 30 minutes. A bow does not heal at rest.
- **A bowed rotor shakes.** Vibration is `1.5 mm/s x speed/rated` for a healthy rotor and adds up to 14 mm/s x bow through the critical
  speed band around 35 % of rated. Above 7.1 mm/s the section takes damage (0.6 % a second per 7 mm/s over), again in the shaft damage
  counter. A bowed rotor rolled up through the band is hurt; one that is straight is not.
- The existing shaft coasts for hours. The gear can be engaged when the shaft is under 10 rpm, and a hot rotor bows once it is under
  1 rpm; engage the gear in between and leave it on until the rotor is cold.

## Generator cooling

The winding is cooled by stator water and a hydrogen casing, both given up to cooling water. It needs power: the stator pumps draw
6,717 FE/t (90 kW) while they run.

| Quantity | Value |
| --- | --- |
| Loss into the cooling | `22 MW x load^2 + 1.5 MW x (speed/rated)^2` (load is output over the 1,500 MW rating) |
| Cooling fraction | `0.45 x stator flow + 0.55 x (0.25 + 0.75 x hydrogen pressure / 30 psig)` (hydrogen at full pressure; air in the casing is a quarter of it) |
| Winding to coolant | `340 kW/K x cooling fraction`, plus 40 kW/K to the hall; winding heat capacity 33 MJ/K |
| Coolant | 80 MJ/K, cooled by a 800 kW/K exchanger on 500 kg/s of cooling water |
| Rated load, fully cooled | winding about 102 C |
| Alarm / limit | 120 C / 130 C; above the limit insulation damage accrues, 1 % a second per 40 K over |

Stator pumps with no FE, no cooling water, no hydrogen or no skid at all: the winding heats with load. **The generator is held under its
limit by you reducing the load.** With the stator water stopped the winding settles near 139 C at rated load and about 68 C at 60 % load.
Damage goes into the generator's shaft damage, so a destroyed winding fails the generator like a destroyed shaft. Hydrogen leaks slowly
(0.1 kg a minute at full pressure); a low charge cools worse. Hydrogen purity and an air-in-hydrogen explosion are not modelled.

## Readouts, signals and computers

- **Power module screen** (any section): a *Turbine auxiliaries* card with the oil pressure, temperature, level and film, bearing
  temperature, shaft vibration, rotor temperature and bow, turning gear state and its supply, winding temperature, stator flow and
  hydrogen pressure, and the **Turning gear** button on the generator.
- **Skid screens** show their own pumps, buses, inventories and cooling water, with the pump switches.
- **Signals** (panels, gauges, annunciators, through the Networking Wrench; see [SIGNALS.md](SIGNALS.md)): on every power module
  `oil_pressure`, `oil_temp`, `oil_level`, `oil_film`, `bearing_temp`, `vibration`, `rotor_temp`, `rotor_bow`, `turning_gear`,
  `turning_gear_rolling`, `winding_temp`, `stator_flow`, `h2_pressure`, `coolant_temp` and the alarms
  `oil_pressure_alarm`, `oil_level_alarm`, `oil_temp_alarm`, `bearing_alarm`, `vibration_alarm`, `winding_alarm`, `h2_alarm`
  (a system the train does not have reads "no signal"); the command `turning_gear`. On the skids their switches (`ac_pump`,
  `dc_pump`, `stator_pumps`) and readings. A new quantity, **VIBRATION** (mm/s), is appended for gauges; the rest use PRESSURE,
  TEMPERATURE, LEVEL, PERCENT and BOOLEAN.
- **ComputerCraft:** `bwr_generator`, `bwr_hp_turbine` and `bwr_lp_turbine` `getStatus()` carry the same readings as `oilPressurePsig`,
  `bearingTempC`, `rotorBow`, `windingTempC`, `turningGearPowered` ... and `setTurningGear(bool)` / `getTurningGear()` (the generator; engaging above 10 rpm
  raises an error). The skids are `bwr_main_oil_tank` (`setAcPump`, `setDcPump`), `bwr_shaft_oil_pump` and
  `bwr_generator_cooling_skid` (`setStatorPumps`), each with `getStatus()`.

## Trains that were there before

The rule is per system, and the simplest fair one: **a train runs as it always did until it has chosen the system.**

- Oil and bearings govern a train when it has a Main Oil Tank or Shaft-Driven Pump attached. Rotor bow and vibration govern a train
  when a generator's turning gear is on. Winding temperature governs a train when it has a Generator Cooling Skid.
- **A turbine section a player places new** from a fresh item (the server option `turbineAuxRequired`, default **true**) marks its
  train as needing all three, from the first tick. Sections that exist in a world, and sections picked up and set down again
  (they carry the status of the section they were), are not marked. `turbineAuxRequired = false` makes new sections like old ones.
- An old train with no auxiliaries has no alarms, no bearing or winding temperature, no vibration, and its save gains no new key.
  Rotor temperature is tracked either way (and saved only above ambient), but without a governed system nothing bows.
- One new auxiliary opts in that system only: an oil tank on an old train brings in oil and bearings, and not winding cooling.
- Mixed trains: any section marked new marks the whole train.

## Saves

Every new key is written only when it is not its default: `AuxLinks`, `AuxRequired`, `TurningGear`, `Bow`, `BearingC`,
`RotorC`, `WindingC` on a power module; `Host`, `AcOn`, `DcOn`, `StatorOn`, `AcFe`, `DcFe`, `OilL`, `OilC`, `H2Kg`, `CoolantC`,
`StatorFlow` and the two cooling-water tanks on a skid. A skid or link in an unloaded chunk is kept, never loaded to look at.

## Pictures

1920 x 1080, from `-PbwrTurbineAuxCheck` (`TurbineAuxClientCheck`) and the GUI check, in `art/models/turbine_aux/screens/`: `aux-overview`, `aux-oil-tank`,
`aux-shaft-pump`, `aux-cooling-skid` (the world), `gui-generator-aux`, `gui-hp-aux`, `gui-oil-tank`, `gui-cooling-skid`, `gui-shaft-pump`,
`gui-generator-turning-gear` (the screens, the last with the turning gear on and rolling the shaft).

## Models and sources

Three Blender models (headless): the oil tank (3,892 triangles), the generator cooling skid (5,628) and the shaft-driven pump
(2,056), in `art/models/turbine_aux/` (`build_models.py`, `turbine_aux.blend`, the previews). `python tools-export-turbine-aux.py`
bakes them (`--check` verifies); `python tools-make-turbine-aux-assets.py` makes the two item textures.

## Tests

Core `TurbineAuxTest` (12 tests). GameTests `bwraux` (18 tests) (`TurbineAuxTests`, `TurbineAuxCcChecks`): the shaft pump makes oil only with
speed; start-up needs the AC pump; no oil while rolling wrecks the bearings, and oil does not; the DC pump holds the oil on loss of
AC power; the cooler and the oil's leak and the drums; a hot shaft bows without the gear and the gear recovers it; a bowed
roll-up shakes and damages; the gear switch engages only at low speed (screen, signal, CC); an unpowered cooling skid lets the
winding rise with load; hydrogen; the readouts and signals; the old-train rule; saves; nothing ticks while idle. Since alpha.59
`TurbineBearingFireTests` (2 more): a bearing run dry lights its oil and a running pump feeds the fire; a train with no oil burns nothing.
