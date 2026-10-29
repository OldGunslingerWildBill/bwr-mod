# Turbine auxiliaries: lube oil, turning gear, generator cooling

A turbine-generator needs more than steam. Bearings need oil while the shaft turns, a hot rotor must be rolled slowly after a
shutdown or it bows, and a 1,500 MW generator must have its winding cooled. These are the machines that do that. All the
numbers are game calibration, not a manufacturer's curves. The physics is `dev.bwr.core.turbine.TurbineAux` (pure functions,
`TurbineAuxTest`); the train step is `dev.bwr.mod.power.TurbineAuxTrain`.

Since alpha.59 the oil can also be **piped**: a brown Lube Oil Pipe, a Lube Oil Valve with a limit switch box, and oil ports on the
tank, the shaft-driven pump, the filter housing and every turbine and generator section (see *Lube oil piping* below). Everything
described before that section is still true for a train with no oil pipe on it.

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
| **Oil Filter Housing** (`oil_filter_housing`) | **Directly against a Main Oil Tank** (see Oil filtration below): its back on the tank, on any side of the tank; **or in-line in a Lube Oil Pipe run** on a tank's discharge | A **duplex** full-flow lube oil filter: **sides A and B**, one Oil Filter Cartridge each, and a **transfer valve** that picks the side in service so the other can be changed while the plant runs (below). It filters the tank's oil. It is not on the shaft train and needs no power |
| **Lube Oil Pipe** (`lube_oil_pipe`) | Between the oil ports of the equipment (see Lube oil piping below) | A brown, small-bore pipe: a network of its own that never joins a water or steam pipe. Crafted from iron nuggets and brown dye (6 pipes); **with Mekanism** steel nuggets (MEKANISM-RECIPES.md) |
| **Lube Oil Valve** (`lube_oil_valve`) | In-line in a Lube Oil Pipe run | A quarter-turn ball valve with a **limit switch box** on top that shows open or shut. Crafted from 4 iron, a lever, redstone and 2 Lube Oil Pipes; **with Mekanism** steel, a basic circuit in place of the redstone |
| **Lube Oil Conditioner** (`lube_oil_conditioner`) | Bolted against a Main Oil Tank, or piped to its suction outlet and fill connection (see Oil cleanup below) | A side-stream **centrifuge and vacuum dehydrator** on FE. Running, it cuts the oil's degradation to 30 %; with a clean filter cartridge too, a 20,000 L tank lasts about one refuelling cycle (8.5 days). Crafted from iron, glass, 2 Lube Oil Pipes, a hopper and redstone; **with Mekanism** steel and a basic circuit |
| **Oil Filter Cartridge** (item) | The housing's slot: by hand, a hopper or a pipe, or in its screen | The filter element. Crafted from four iron ingots, four paper and white wool; **with Mekanism** four steel ingots, four paper and an HDPE sheet |
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
  thins): a 1,000 L drum lasts about 18 minutes at rated speed, a full 20,000 L tank about six hours. **An Oil Filter Housing with a
  cartridge in it makes that ten times longer** (Oil filtration, below). Refill it with Turbine Oil Drums (with Mekanism installed, keeping a train supplied is a production chain: MEKANISM-RECIPES.md). Alarm under 30 % level.

## Oil filtration

Real turbine lube oil is hardly ever "used up". It is **degraded**: water gets in, particles wear off the bearings and the oil's own
oxidation products build up, and a charge that is not conditioned is drained and replaced in months. A **full-flow filter** in the
pump discharge with a **side-stream conditioner** keeps the same charge in service for years. The game's "oil loss" is that degradation
(plus the small leak), so the filter is modelled as what it does to the loss, and the player's cost is the cartridge.

| Quantity | Value | Why |
| --- | --- | --- |
| Loss through a clean cartridge | **0.1 of the unfiltered loss** (`OIL_FILTER_FACTOR`) | "A lot longer", the owner's words: ten times. At rated pressure and 50 C a 1,000 L drum lasts **3.1 hours** instead of 18.5 minutes, and a full 20,000 L tank **62 hours** instead of 6.2 |
| Cartridge life | **6 hours** at rated pressure and 50 C oil (`FILTER_LIFE_S`) | About two drums of filtered running (1,944 L), so one cartridge per two drums: a long session on one cartridge, a change at the same bench visit as a refill. The unfiltered loss over those six hours would have been 19,440 L |
| What loads it | the header pressure (`pressure / 25 psig`) times `2^((oil C - 50) / 15)` for oil above 50 C | the same two terms as the oil's own loss: dirt comes with the flow, and hot oil oxidises faster. Oil at 65 C loads it twice as fast; a tank nobody pumps loads nothing |
| Differential pressure | `3 + 17 x load^2` psid: 3 clean, 12 at 73 % full, **20 when full** | real elements are flat while clean and climb as they fill. A change is due at **12 psid** (a little over a quarter of the life left); the bypass valve opens at 20 |
| Full, or the slot empty | the **bypass opens**: the factor is 1 | the oil goes round the element, so loss and degradation are **today's rate again**. A housing with no cartridge is the same as no housing |

A cartridge is not cleaned. Taking it out gives back **the same item as a used cartridge** (a data component, `bwr:oil_filter_load`,
0 clean to 1 full; a clean one carries none and stacks with new ones), which remembers exactly how full it is and goes back where it
left off. A spent one is just a spent one (its bar and tooltip say so). There is no scrap return. Changing it is the only reset: take
the old one out of the slot and put a new one in.

**No extra harm.** The brief offered a small extra bearing wear for oil left unfiltered. It was left out on purpose: the bearing laws
are made of film, temperature and speed, none of them of oil condition, so a "dirty oil" term would be a new law and not a use of the
existing ones, and the bypass already costs what matters here (the oil). Unfiltered oil is today's behaviour.

**What it does not do.** A loaded cartridge does **not** lower the header pressure (a real discharge has a relief and a regulator that
hold it), and it does not change the oil film, the bearing temperature or the fires. The oil fire laws (`TurbineFire`) are unchanged.

### The housing: how it attaches and how it is changed

- **Mounting.** The housing is bolted **straight against a Main Oil Tank**; it needs no power, no water and no train. It faces away
  from the tank (placing it turns its back to an adjacent tank). Its tank is the one on its **inlet face** (the back), else any tank it
  stands against. A tank uses the first housing against it that has it as its tank, so a second housing on one tank does nothing, and a
  housing next to no tank filters nothing (its screen says so). Nothing is stored for this: the tank finds its housing by looking at
  its six neighbours in its oil step, never loading a chunk, so it survives any rebuilding.
- **Flow axis.** The housing has an **inlet face** (the back, `TurbineAuxBlock.oilInFace`) and an **outlet face** (the front,
  `oilOutFace`), with a round flange on each in the model. The rule that decides which tank a housing serves is in **one place**
  (`TurbineAuxBlockEntity.tank()`, with `oilFilter()` the tank's side of it). Bolted on a tank it is as described here; **in-line in a Lube
  Oil Pipe run** (see Lube oil piping) the tank is the one whose discharge the run from the inlet leads back to, and the tank's filter is
  the first housing entered by its inlet in the run leaving the tank's discharge. Direct contact comes first.
- **The slots.** Right-click the housing with a cartridge to put one in the side in service when that is empty (with it full, open the
  screen). Right-click with nothing on a side face to open its screen (two slots, A and B, a transfer button and the player's inventory;
  shift-click works); right-click on the **top** throws the transfer lever. **Hoppers and pipes may put a cartridge into an empty slot of
  either side, on any face, and can take nothing out**: nothing is pulled out for you. Breaking the housing drops both cartridges with
  their loads. Everything about the two sides is in *The duplex filter* below.
- **No ticker.** The housing has none. The Main Oil Tank's own oil step, which the train calls once a tick, loads the cartridge by the
  oil that flowed.

### The duplex filter: sides A and B and the transfer valve

Plants change a filter **with the plant running**: a duplex filter has two canisters and a **transfer (changeover) valve** that
puts one in service while the other is isolated and opened. The Oil Filter Housing is one.

- **Two slots, A and B**, one Oil Filter Cartridge each, and a **transfer valve** (a lever on the changeover valve, between the
  canisters) that picks the side in service. Side A is the one on the left seen from the front (the outlet side). The model shows it:
  a red lever leaning at the canister in service, a **green band and lamp on the canister in service and an amber one on the other**,
  a big A and B, and the **lid of the canister out of service standing open when its slot is empty** (open for a change).
- **Only the side in service filters and loads.** The side out of service is not in the flow: its cartridge does not load, and it
  can be opened and its cartridge changed while the turbine runs, with the oil never going round the filter.
- **A cartridge cannot be taken out of the side in service.** The realistic rule is the one chosen: the canister is under
  pressure, so the screen's slot for the side in service gives nothing up (and shift-click leaves it), and the game never opens the
  bypass for you by letting one out; **throw the transfer valve first**, then change the other side. An *empty* side in service
  can be filled (it was running on its bypass), and putting a cartridge in the other side is always allowed.
- **Throwing the valve.** By hand: **click the top of the housing** (where the lever is) and the action bar says which side is in
  service; any other face opens the screen, which has a **Transfer to side A/B** button. By **redstone**: power selects side B,
  losing it selects side A, and only a change of the power counts (the same convention as the Lube Oil Valve). By **signals** and
  **CC** (`selected_side`, `setSide("A"|"B")`, `transfer()`). Transferring to an empty side is allowed and runs the oil on the bypass.
- **Right-click with a cartridge** puts it into the side in service when that is empty; with it full the housing says so and the
  other side is filled from the screen (shift-click puts a cartridge into the side out of service first) or by a hopper.
- **Hoppers and pipes** see the two slots, A and B, and may put a cartridge into an **empty slot of either side** (a slot in
  service that is empty is on its bypass, one out of service is waiting) and **can take nothing out of either**.
- **Readings.** Everything the housing, the tank and the train read (`filter_life`, `filter_dp`, `filter_bypass`, the alarm, the
  screen's meter) is the side in service; the housing's signals and CC also give each side's cartridge, life, pressure drop and
  change-due, and the selected side.
- **Old saves.** A housing saved with one cartridge loads it into **side A with A in service**. `Cartridge` is side A's key as it
  always was; `CartridgeB` is written only when B holds one, `SideB` only while B is in service, `Powered` only while the redstone
  reading is powered. The block state (`side_b`, `off_open`, drawn by the model) is brought up to date by the tank's next step or when
  the screen opens. Breaking the housing drops both cartridges with their loads.

### Old saves and what is unchanged

A tank saved before the filter existed has no housing and **loses oil exactly as it always did** (`oilLossLitres(psig, oilC, dt)` is
unchanged, and a test checks the tank against it tick by tick). The housing, cartridge and menu are new ids; the tank, the train and
the power modules save no new key. A housing saves `Cartridge` (the item with its load) **only while it holds one**. Trains with no oil
tank are unaffected, and the `-PbwrNoCC` and `-PbwrNoMekanism` runs play the same game.

## Oil cleanup: the Lube Oil Conditioner

Filters only get so much (a clean cartridge makes the oil last ten times as long, 62 hours for a 20,000 L tank). A plant keeps its
oil in service far longer with a **side-stream oil cleanup plant** (a lube oil conditioner): a **centrifuge** (a disc stack that takes
out the particles and the free water) and a **vacuum dehydrator** (a heated column under vacuum that takes out the dissolved water and
gas), taking a small stream out of the tank and returning it cleaned. The **Lube Oil Conditioner** (`lube_oil_conditioner`) is that skid.

- **Connections.** Oil in on its **back** flange from the Main Oil Tank's **suction outlet** (below the tank), cleaned oil out on its
  **front** flange to the tank's **fill connection** (on top): two runs of Lube Oil Pipe, which may carry valves (a shut valve in
  either run cuts the stream). It may also be **bolted directly on the tank**, on any face, as the housing is, and then needs no pipe.
  The rule that decides which tank it serves is in one place (`TurbineAuxBlockEntity.conditionerTank`): the tank on its back, else the
  tank whose suction outlet its inlet run reaches *and* whose fill connection its outlet run reaches (it draws from the tank and returns
  to the same tank), else the first tank against any other face. A tank is fitted with one conditioner. It is not on the train.
- **Power.** It runs on **FE on any face but its two oil flanges** and draws **11 kW** (821 FE/t, `TurbineAux.CONDITIONER_W`) while it
  runs: a centrifuge, a vacuum pump and a heater for a 20,000 L system. You **start and stop it** from its screen, a program or a
  panel; nothing starts it for you. It has no ticker: the tank's oil step, which the train calls once a tick, steps it, so a skid with
  no train ticking does nothing.
- **What it does.** Running (switched on, with the FE for the tick, the tank holding oil and oil able to pass: bolted on, or both runs
  open) it leaves **30 %** of the oil's degradation (`CONDITIONER_FACTOR` = 0.3026). The filter's own factor (a tenth with a clean
  cartridge in service) is separate and **the two multiply**: the filter protects the bearings, the conditioner keeps the oil itself healthy.
  It does not change the header pressure, the film, the bearing temperature or the fires, and it does not slow the cartridge's loading
  (the cartridge keeps its modest factor and its six hours).
- **Losing power or flow** (stopped, no FE, an empty tank, a shut valve or a cut run) **puts the oil back on the filter-only rate**,
  bit for bit: the tank's loss is the old law's times 1.0.
- **No consumable.** A real coalescer element lasts about a year and is cheap, but a change every few hundred hours of play would be a
  chore the owner did not ask for, so the conditioner has **none**: its cost is the skid, its FE and your attention to the switch.

### How long the oil lasts: the rule, and what "a refuelling cycle" means here

**The rule: with the cleanup plant running and a clean cartridge in service, a full 20,000 L tank at rated turbine speed lasts about one
refuelling cycle, 8.5 real days (204 h) of running, so you top the oil up at the refuelling outage.** The owner's words: the reactor
usually gets refuelled every 7 to 10 days, so the oil would last that long; 8.5 days is the middle.

- **Time scale.** The mod has no constant for a fuel cycle's length. What it has is a convention for long laws: they run in **real
  running hours at 20 TPS** (game time, one game second a second), not Minecraft days: FUELS-AND-RODS.md says so for the breeding
  targets ("real running hours at 20 TPS, not Minecraft day/night cycles"), whose 72 to 240 hours are "matched to long fuel cycles";
  SPENT-FUEL-POOL.md does the same for decay heat ("the clock is the server's game time"), and RTG.md for isotope decay. The oil law
  already ran on it (62 h with a filter). So **8.5 days is 204 h of running at 20 TPS**, which sits inside that 72 to 240 h span.
- **The numbers** (`TurbineAux`, core `TurbineAuxTest` 15 and 16). `OIL_LIFE_CONDITIONED_S` = 204 h, and the conditioner's factor is
  *derived*, not chosen: `CONDITIONER_FACTOR = OIL_TANK_L / (OIL_LEAK_L_PER_S x OIL_FILTER_FACTOR x OIL_LIFE_CONDITIONED_S)` =
  20,000 / (0.9 x 0.1 x 734,400) = **0.3026**. At the rated header pressure (25 psig, the shaft pump alone at speed) and 50 C oil:

| Tank of 20,000 L | Lasts |
| --- | --- |
| no filter, no conditioner | 6.2 h |
| a clean cartridge in service | 62 h |
| the conditioner running, filter bypassed | 20.4 h |
| **a clean cartridge and the conditioner running** | **204 h (8.5 days)** |

  Hot oil degrades faster and a header held at more than rated pressure loses more, as the law has always said: with the AC pump also
  on the header is at 30 psig and the same tank lasts 170 h (7.1 days), still inside a cycle.
- **Why 30 %.** A conditioner takes out what makes oil need replacing, but not the leak or the oil burned in the bearings, and it
  cannot make the oil last for ever; 30 % of the degradation with a filter's tenth is the 3 % that makes eight and a half days where a
  filter alone makes two and a half.

### The conditioner's screen, signals and computers

- **Screen** (the aux skid screen, kind 4): state (running, no power, no oil flow, stopped), the FE it holds, the share of the oil's
  degradation that remains, the tank's oil, a **Start/Stop** button. The **Main Oil Tank's screen** carries an *Oil conditioner* row.
- **Signals** on the conditioner: `attached`, `on`, `running`, `fe`, `degradation_factor`, command `on` (start/stop). On the tank:
  `conditioner`, `conditioner_running`. On every power module: `oil_conditioner_running` ("no signal" without a conditioner on the tank).
- **ComputerCraft: `bwr_lube_oil_conditioner`** on all six sides: `getStatus()` (`attached`, `on`, `running`, `supplyFE`,
  `degradationFactor`) and `start()`, `stop()`, `setRunning(bool)`; nothing else is writable. The tank's `getStatus()` carries `conditioner`,
  `conditionerOn`, `conditionerRunning`; the power modules' `oilConditionerRunning`.
- **Recipes** (both installs): 5 iron ingots (steel with Mekanism), a glass (the sight glass), 2 Lube Oil Pipes, a hopper (the
  strainer) and a redstone (a basic circuit with Mekanism, the skid's control). The blockstate is `facing` only; FE terminal and conduit are modelled.
- **Save keys.** `CondOn` only while switched on, `AcFe` only while it holds FE (the key the AC bus has always used).

## Lube oil piping

Oil does not have to be bolted on. A **Lube Oil Pipe** run carries it between the oil equipment, so the tank can stand in a basement
lube oil room and be piped up through the floor to every bearing, the way a real plant is built. Everything below is *added to* the
old rule (a tank bolted against a section feeds the whole train); a plant with no oil pipe in it behaves exactly as it always did.

### The pieces

| Block | What it is |
| --- | --- |
| **Lube Oil Pipe** (`lube_oil_pipe`) | A small-bore (5 px), flanged, **brown** line. It is a network of its own: it joins other lube oil pipes, lube oil valves, filter housings and the oil ports below, and **nothing else**, so it can never join a water or steam pipe (the water and steam rules do not accept it either). Dye repaints its paint like the other pipes. Up to 256 blocks of pipe in one run |
| **Lube Oil Valve** (`lube_oil_valve`) | In-line, two flanges. See below |
| **Oil Filter Housing** | In-line in a run, inlet face (its back) to outlet face (its front), as well as bolted on a tank |

### Oil ports of the equipment

Every connection is a flange on one face of the block; a pipe laid against it takes a bolted flange, and the machine's own face needs no
model change (`PipeJointModels` draws the flange on the pipe side).

| Equipment | Port | Face | What it is |
| --- | --- | --- | --- |
| **Main Oil Tank** | discharge | front (the way it faces) | what the **AC pump and the emergency DC pump** on the skid deliver, to the bearing header |
| | return | back | the bearing drains come back here |
| | suction | down | the outlet to a shaft-driven pump's suction |
| | fill | up | for a filling line (and, later, a placeable drum); it takes a pipe today and nothing flows through it yet |
| | (cooling water flanges) | left and right | unchanged, and never an oil port |
| **Shaft-Driven Main Oil Pump** | suction | down | needs a pipe to a tank's suction outlet (or, as before, a tank on its own train) |
| | discharge | up | to the bearing header |
| **Each turbine and generator section** (HP, LP, generator) | bearing **supply** | the west flank, floor row, middle of the casing | the header ends here |
| | bearing **drain** | the east flank, floor row, middle of the casing | the return line starts here |
| **Oil Filter Housing** | inlet / outlet | back / front | in-line, the oil enters by the inlet |
| **Lube Oil Valve** | two flanges | on the flow axis | in-line |

"West" and "east" are the section's own, turned with it: the flanks are the two long sides of the casing. They are the flanks and not the
underside because an LP turbine has its condenser right under it. Pipe goes down from them through the floor to the basement.

### The flow model

```
                   fill (up)
                       |
 supply run <---- discharge  [ MAIN OIL TANK ]  return <---- drain run
 (header)        (front)     [ AC 70 %, DC 45% ]  (back)      (header)
    |   ^                    [   20,000 L      ]    ^            |
    |   | filter, valve      [  suction (down) ]    |            |
    v   |                         |                 |            v
 section SUPPLY  ---- bearings ---+-> section DRAIN ------------>+
 (west flank)                  shaft-driven pump     (east flank)
                               suction (down) <- tank suction
                               discharge (up) -> header
```

Oil goes **out by the supply and back by the drain**. For a section the model asks, once a tick:

1. **Which pumps does its supply run reach?** A pump's discharge (the tank's, or a shaft pump's) joined to the section's supply port by
   pipes and open valves. A **shut valve cuts the run**: the oil does not pass.
2. **Does its drain run lead back to the tank that pump draws from?** (the tank's return inlet; for a shaft pump the tank it draws
   from). If not, the oil has no way home and the section is **not fed**: its bearing housings flood, in the game's terms.
3. **Its oil is the sum of those pumps' delivery**, each counted once, and its header pressure and **film** follow from that sum exactly
   as before (`oilPsig(flow)`, `film(flow, rpm)`). **A section that nothing reaches has no oil**: film 0, pressure 0, the bearing laws act as for
   no oil pressure (heating by the existing law, damage above 115 C, fire above 300 C when a tank has oil to burn).
4. The bearing heat of each fed section goes to the tanks its oil comes from, split evenly; each tank takes the highest header pressure
   it supplies (that sets its oil loss, with the filter) and the lowest film.

A pump's delivery is its rated share of the whole train's need, as it has always been, and a pump delivers it to every section it reaches
in full (it is not shared out by section).

**The shaft-driven pump's suction.** Piped to a tank's suction outlet it draws from the tanks that run reaches, through open valves
(a shut valve, a cut line: it delivers nothing). Not piped, it draws from the Main Oil Tanks bolted on its own train, as it always did.
With neither it **delivers nothing**.

### Direct contact against piped: the precedence rule

| Pump | Its discharge is... | It feeds |
| --- | --- | --- |
| a tank or shaft pump **bolted on the train** | not piped (no pipe run from its discharge port reaches a section's supply port) | **every section of the train**, through the machine's own header: no pipe, nothing to cut, exactly the old rule (every old save) |
| any tank or shaft pump, bolted on or **not** | **piped** (a run from its discharge reaches the supply port of at least one section) | **only the sections its header reaches** through open valves, and loops (drain back to its tank) |

- "Piped" is decided by the pipework alone, with valves taken as open. So **closing a valve starves what is behind it and never puts the
  pump back on the integral header**, and a pump with a pipe on it never feeds the whole train as well.
- It is a **cliff by design**: a tank bolted on the train that you then pipe to the generator's supply feeds the generator and not the HP or
  LP (they get no oil until their own supply and drain are on the header). That is what a real header does.
- A section's oil is the union of all this: the pumps bolted on its train and not piped, and the pumps its pipes reach. A tank can serve
  several trains and a train can have several tanks; one tank shared between two trains takes the bearing heat of the train that steps it.
- A train that has **no pump bolted on it, but is piped to one**, is governed by the oil system too (the bearing laws apply and the
  readings are live), the same as a train with a tank against it.
- Direct contact also covers the **filter housing**: a housing bolted on a tank (any face) is that tank's filter first; failing that, the
  first housing in-line in the run leaving the tank's discharge, entered by its inlet face, is.
- A housing in the run installed **backwards** (entered by its outlet) is a plain conduit: it passes the oil and filters nothing.

### The Lube Oil Valve

A **quarter-turn full-bore ball valve** with a **limit switch box** on top.

- **Why a ball valve.** It is what a lube oil isolation is in a plant: quarter-turn, full bore (no loss open), tight shut-off, and a
  quarter-turn stem is what a pair of limit switches reports. A gate valve (multi-turn, rising stem) would need a stroke the block does
  not have.
- **The box.** A generic limit switch box: a lid with four screws and a conduit gland, and a domed indicator showing the stem. **Open:
  the dome is green and its white bar lies along the pipe. Shut: the dome is red and the bar lies across it.** It is the block state
  (`open`), so the picture changes the moment the valve does, for everyone looking.
- **Operated by hand** (right-click turns it, the action bar says OPEN or SHUT), by **redstone** (power opens it, losing the power shuts
  it; only a *change* of the power is a command, so a valve placed new is open) and by a **computer or panel** (below). Nothing else
  ever moves it: no alarm or trip closes it, and a fire does not.
- **Stroke.** A quarter-turn is under a second, so it is taken as instant: the open and closed limit switches are never both off.
- **A shut valve stops the oil on that run**: the survey is thrown away and redone when the valve moves, and a section or a shaft pump
  behind it is starved by the rules above.
- Signals `open` (open limit switch made), `closed` (closed limit switch made), `position` (0 or 1) and the command `open`
  (see [SIGNALS.md](SIGNALS.md#lube-oil-valve)); CC `bwr_lube_oil_valve` on all six sides (`open()`, `close()`, `setOpen(bool)`,
  `isOpen()`, `isClosed()`, `getPosition()`, `getStatus()`).

### How it is kept cheap and safe

- **No ticker.** The train's own step reads the oil system once a tick. The Lube Oil Pipe, the valve and the ports have none.
- **Surveys are cached.** Each oil port's run is surveyed when it is first asked for and kept (`OilLineNetwork`); it is thrown away
  only when a block at or beside the run is placed, removed or toggled, or a chunk of it becomes available or unavailable. A plant that
  does not change builds no survey. A port with no pipe on it is answered by reading one block and keeps nothing.
- **Loaded chunks only.** A run that crosses a chunk that is not loaded is **not connected past it** (the pump behind it is starved
  until the chunk is back, and the survey is redone when it arrives). Nothing is ever loaded for it: a survey reads only chunks that are
  completely available, and takes no chunk ticket.
- **No new save key** except the valve's `Powered` (only while powered). The tank, the pumps and the sections save what they always did;
  old saves load, and a piped plant saved and loaded again finds its runs again from the blocks.

### Back-compat, in one line

A Main Oil Tank placed against a section keeps feeding that train as today; a Shaft-Driven Pump on the generator's end with its tank on the
same train keeps working with no suction pipe; piping is the extra and the proper way.

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

- **Oil Filter Housing screen**: the two cartridge slots, A and B (the one in service marked, its slot locked while it holds a
  cartridge), the **Transfer to side A/B** button, the life of the cartridge in service (a meter), the pressure drop across it, the bypass
  state, the share of the unfiltered oil loss that is getting through, the time left at today's pressure and oil temperature, the side
  out of service and the tank's oil. The **Main Oil Tank's screen** carries an *Oil filter* row (life and pressure drop of the side in
  service, or "bypass open", or "none fitted") and an *Oil conditioner* row.
- **Power module screen** (any section): a *Turbine auxiliaries* card with the oil pressure, temperature, level, film and oil filter, bearing
  temperature, shaft vibration, rotor temperature and bow, turning gear state and its supply, winding temperature, stator flow and
  hydrogen pressure, and the **Turning gear** button on the generator.
- **Skid screens** show their own pumps, buses, inventories and cooling water, with the pump switches.
- **Signals** (panels, gauges, annunciators, through the Networking Wrench; see [SIGNALS.md](SIGNALS.md)): on every power module
  `oil_pressure`, `oil_temp`, `oil_level`, `oil_film`, `bearing_temp`, `vibration`, `rotor_temp`, `rotor_bow`, `turning_gear`,
  `turning_gear_rolling`, `winding_temp`, `stator_flow`, `h2_pressure`, `coolant_temp` and the alarms
  `oil_pressure_alarm`, `oil_level_alarm`, `oil_temp_alarm`, `bearing_alarm`, `vibration_alarm`, `winding_alarm`, `h2_alarm`
  `oil_fed` (oil reaches this section's bearings), `oil_supply_piped` and `oil_drain_piped` (on a piped train: its supply run joins a pump
  discharge, its drain run joins a tank return; "no signal" where nothing is piped),
  `oil_filter_life` (fraction left), `oil_filter_dp` (psi across the cartridge), `oil_filter_bypass` and `oil_filter_alarm` (a change is
  due: the pressure drop is at 12 psid, or the oil is going round the filter), which read "no signal" on a train whose tank has no
  housing (a system the train does not have reads "no signal"); the command `turning_gear`. On the skids their switches (`ac_pump`,
  `dc_pump`, `stator_pumps`) and readings. A new quantity, **VIBRATION** (mm/s), is appended for gauges; the rest use PRESSURE,
  TEMPERATURE, LEVEL, PERCENT and BOOLEAN.
- **ComputerCraft:** `bwr_generator`, `bwr_hp_turbine` and `bwr_lp_turbine` `getStatus()` carry the same readings as `oilPressurePsig`,
  `bearingTempC`, `rotorBow`, `windingTempC`, `turningGearPowered` ... and `setTurningGear(bool)` / `getTurningGear()` (the generator; engaging above 10 rpm
  raises an error). The skids are `bwr_main_oil_tank` (`setAcPump`, `setDcPump`), `bwr_shaft_oil_pump` and
  `bwr_generator_cooling_skid` (`setStatorPumps`), each with `getStatus()`, and **`bwr_lube_oil_valve`** (see the valve above). The power modules' `getStatus()` also carries `oilFed` (and, on a piped train, `oilSupplyPiped` and `oilDrainPiped`); the tank's `dischargePiped`; the shaft pump's `suctionPiped` and `dischargePiped`. The Main Oil Tank's carries `filterHousing` and, with a housing
  against it, `filterCartridge`, `filterLife`, `filterDpPsi` and `filterBypass`; the power modules' `getStatus()` carries `oilFilterLife`,
  `oilFilterDpPsi`, `oilFilterBypass` and `oilFilterAlarm` (with a housing on the train's tank). **`bwr_oil_filter`** is the housing's
  own read-only face (on all six sides, `getStatus()` only): `attached`, `filterCartridge`, `filterLife` (1 new to 0 spent),
  `filterDpPsi`, `filterBypass` (true with the slot empty or the cartridge full), `filterChangeDue` and, while oil is flowing,
  `filterHoursLeft`.

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
`StatorFlow` and the two cooling-water tanks on a skid. An Oil Filter Housing saves `Cartridge` (the cartridge item, with its load)
only while it holds one. A skid or link in an unloaded chunk is kept, never loaded to look at. The Lube Oil Valve saves `Powered` only while its last redstone
reading was powered; its position is the block state. Nothing about piping is saved anywhere else: the runs are found again from the blocks.

## Pictures

1920 x 1080, from `-PbwrTurbineAuxCheck` (`TurbineAuxClientCheck`) and the GUI check, in `art/models/turbine_aux/screens/`: `aux-overview`, `aux-oil-tank`,
`aux-shaft-pump`, `aux-cooling-skid` (the world), `gui-generator-aux`, `gui-hp-aux`, `gui-oil-tank`, `gui-cooling-skid`, `gui-shaft-pump`,
`gui-generator-turning-gear` (the screens, the last with the turning gear on and rolling the shaft).

The oil piping pictures are saved by the pipe shots check (`:mod:runTurbineModelCheck -PbwrPipeShots=<label>`, `PipeShots`), in
`run/turbineModelCheck/pipe-shots/<label>/` and copied to `art/oil-piping/`: `oil-room` and `oil-room-tank-and-filter` (the basement lube
oil room), `oil-filter-in-line`, `oil-risers-south-flank` and `oil-risers-close` (the risers through the hall floor to the train),
`oil-valves-open-and-shut`, `oil-valve-open-close-up` and `oil-valve-shut-close-up` (the limit switch box), `oil-water-steam-straights`.

## Models and sources

Four Blender models (headless): the oil tank (3,892 triangles), the generator cooling skid (5,628), the shaft-driven pump (2,056)
and the oil filter housing (3,648: two canisters, a changeover valve, a pressure-drop gauge, and a flange with a brown band on each of
its inlet and outlet faces), in `art/models/turbine_aux/` (`build_models.py`, `turbine_aux.blend`, the previews). The **Lube Oil Valve** (636 polygons a state: ball body,
actuator, limit switch box with a green or red dome and a white bar, flanges at both ends) is `art/models/lube_oil_valve/build_models.py`
(headless Blender) and `python tools-export-lube-oil-valve.py` (`--check` verifies); the **Lube Oil Pipe** models are the `oil` set of
`art/models/pipes/build_pipes.py` (PIPES.md). `python tools-export-turbine-aux.py`
bakes them (`--check` verifies); `python tools-make-turbine-aux-assets.py` makes the item textures (the cartridge's too).

## Tests

Core `TurbineAuxTest` (16 tests; 13 and 14 are the filter, 15 and 16 the conditioner: 204 h with both, the factors multiply, back to the filter-only rate without power). GameTests `bwraux` (18 tests) (`TurbineAuxTests`, `TurbineAuxCcChecks`): the shaft pump makes oil only with
speed; start-up needs the AC pump; no oil while rolling wrecks the bearings, and oil does not; the DC pump holds the oil on loss of
AC power; the cooler and the oil's leak and the drums; a hot shaft bows without the gear and the gear recovers it; a bowed
roll-up shakes and damages; the gear switch engages only at low speed (screen, signal, CC); an unpowered cooling skid lets the
winding rise with load; hydrogen; the readouts and signals; the old-train rule; saves; nothing ticks while idle. Since alpha.59
`TurbineBearingFireTests` (2 more): a bearing run dry lights its oil and a running pump feeds the fire; a train with no oil burns nothing.
`OilFilterTests` (6 more in `bwraux`, 1 in `bwrmektier`): a filtered tank loses a tenth of the oil an unfiltered one does over the same
run; the cartridge loads up, its pressure drop rises and a full one is bypassed (and hot oil loads it faster); changing it restores
filtration, an empty slot is the same as no filter, a used cartridge keeps its load, hoppers insert only; a tank with no housing loses
oil exactly as the old law says and saves no filter key; the housing saves its cartridge only when it has one and drops it when broken;
the signals and the computer faces (`bwr_oil_filter`, the tank's summary); and the cartridge and the housing have a recipe in both installs.
Since alpha.59 `OilPipingTests` (16 in namespace `bwroil`, plus one in `bwrmektier` for the recipes; `OilPipingCcChecks` is the computer face): every
oil port is where it should be and no face is both a water flange and an oil port; a piped tank in another room feeds a train; a
section whose drain is not piped back is not fed; a shut valve starves the bearings and they heat by the existing laws, opened again
the oil is back; the valve by hand, redstone, signals and computers; the filter in-line (and backwards); a housing bolted on a tank as
before; the shaft pump with no suction line delivers nothing, with one it delivers, a cut or shut line starves it, and with its tank on its
train it needs no pipe; a tank bolted on the train and not piped feeds every section with the old law's exact numbers; a bolted tank
that is then piped feeds only the sections its header reaches; a header reaching every section feeds every bearing; a train never
piped runs to the bit as before and saves no new key; oil pipes never join water or steam pipes (arms, rules and surveys); a survey is
kept while nothing changes and redone when a block on its run does; a run across an unloaded chunk is not connected and nothing is loaded.
`OilConditioningTests` (11 more in `bwroil`, plus a recipe test in `bwrmektier`; `OilConditioningCcChecks` is the computer faces): only the side in
service filters and loads; the side out of service is changed while the plant runs with the oil never going round, and the cartridge in service
cannot be taken out (menu slots and shift-click); the transfer valve by hand, redstone, signals and CC; an old housing's cartridge loads into
side A and keys are written only when set; hoppers fill an empty slot of either side and take nothing out; the block state shows the lever and
the open lid; a running conditioner bolted on leaves 30 % (the conditioned law to the bit each tick, with the filter too 3 %, back to the
filter-only rate with no FE or stopped, drawing its FE); piped to the tank's suction and fill it does the same and a shut valve in its suction run
cuts it off; a 20,000 L tank with both lasts 190 to 220 h at 25 psig; signals, the six computer faces and the saves; the ports and recipes.
The pipe shots (`-PbwrPipeShots`) check the oil fixtures on the client: a flange at each turbine port and none between pipes,
brown paint, that the valve's open and shut states reach the client and draw differently, that the duplex housing's four states (lever at A or B, lid shut or open) all draw differently and show what the server set, and that the conditioner's pipes join it to the tank.
