# RTG and isotope extraction

Two machines and four items close one more loop of the fuel cycle: the **fission product waste** the reprocessing plant
has always made now has a use.

| Piece | What it is |
|---|---|
| **Isotope Separator** (`bwr:isotope_separator`) | Fission-product waste in; sealed capsules of strontium-90, caesium-137 and americium-241, and stripped waste, out. |
| **RTG** (`bwr:rtg`) | A radioisotope thermoelectric generator. Six wells take isotope ingots, blocks or capsules; it makes a small steady supply of FE for decades, and heat. |
| **Large RTG** (`bwr:large_rtg`) | A 2 x 2 x 3 multiblock that holds up to 1,000 kg of ingots and blocks, mixed: a very large remote power source and heater, behind 40 cm of lead. |
| `<isotope>_ingot`, `<isotope>_block` | The form the RTGs take (nine ingots a block), for strontium-90, caesium-137, americium-241 and cobalt-60: see "Ingots and blocks". |
| `bwr:strontium_90_capsule`, `bwr:caesium_137_capsule`, `bwr:americium_241_capsule` | 250 g of the isotope each, sealed: what the separator makes. Radioactive when carried. They craft one to one into ingots. |
| `bwr:stripped_waste` | What the separator leaves. It cannot be separated again. |

Nothing here acts on the plant. Both machines do what the player feeds them; nothing is automatic, and nothing is produced
for the time a chunk is unloaded.

## The numbers at a glance

One capsule is 250 g (one Beta-M's worth of strontium-90). Electricity is 5 % of heat; FE per tick is watts over 13.4,
the mod's one conversion (`EnergyUnits.WATTS_PER_FE_PER_TICK`; the turbine auxiliaries' 75 kW is 5,598 FE/t).

| Capsule | Specific power | Half-life | Heat | Electric | FE/t | Six in an RTG |
|---|---|---|---|---|---|---|
| Sr-90 (with Y-90) | 0.92 W/g | 28.9 y | 230 W | 11.5 W | 0.858 | 1,380 W heat, 69 W, **5.15 FE/t** |
| Cs-137 (with Ba-137m) | 0.42 W/g | 30.04 y | 105 W | 5.25 W | 0.392 | 630 W heat, 31.5 W, 2.35 FE/t |
| Am-241 | 0.1147 W/g | 432.6 y | 28.7 W | 1.43 W | 0.107 | 172 W heat, 8.6 W, 0.64 FE/t |

A real radioisotope unit is small by the plant's standards (1 W of electricity is 0.0746 FE/t), and the RTG says so. It
is for the places a plant cannot reach: a remote instrument, a panel that must stay alive through a station blackout. A
reprocessing plant uses 150 FE/t while it works, so six strontium capsules run it a thirtieth of the time. If you want
more, raise `rtgOutputMultiplier` (below); the default adds nothing.

## What is in a waste item

A waste item is a ninth of an ingot of the heavy metal that was fissioned. An assembly is 4 ingots and 180 kg
(`FuelAssembly.REFERENCE_HEAVY_METAL_MASS_KG`), so a waste item is **5 kg** and the plant's grain, a millionth of an item,
is 5 mg (`IsotopeExtraction.GRAMS_PER_GRAIN`). The separator splits it (`dev.bwr.core.isotope.IsotopeExtraction`, grain for
grain, the four parts always add up to the input):

| Part | Share of the item | Grams of 5 kg | Where the number comes from |
|---|---|---|---|
| Strontium-90 | 21,945 ppm | 109.7 g | Cumulative thermal fission yield of U-235 5.73 % x 90 u / 235 u (a fissioned atom's mass, as the plant counts it) |
| Caesium-137 | 36,267 ppm | 181.3 g | 6.221 % x 137 / 235 |
| Americium-241 | 15,000 ppm | 75.0 g | Grown in from plutonium-241 (half-life 14.3 y, 13.9 to 15.2 % of the plutonium of spent LWR fuel) and sent to the high-level waste by PUREX; about 0.7 kg per tonne of spent fuel a decade after discharge, which is 1.5 % of an assembly's waste at 45 GWd/t |
| Stripped waste | the rest, 4,633.8 g | | Everything else |

An assembly burned to 45 GWd/t makes 1.683 waste items, so it holds 185 g of Sr-90, 305 g of Cs-137 and 126 g of Am-241:
about 0.74, 1.22 and 0.50 of a capsule. Six strontium capsules (one RTG) take the waste of eight such assemblies and 137,000
FE of separator work, which the RTG repays in about 22 minutes of running.

Two honest caveats, both in the model on purpose:

* **Age.** The strontium and caesium shares are as at discharge. The mod does not age waste, so real assemblies cooled for ten
  years hold about 80 % of them (28.9 and 30.0 year half-lives).
* **Bookkeeping of the americium.** The plant's waste item is the fissioned mass, and americium is not a fission product; it
  is booked inside the waste item (the plant's three products are all the plutonium and uranium it counts). The waste mass is
  conserved: the americium is part of the 5 kg, not extra.

## The Isotope Separator

Why a block of its own, and not a mode of the reprocessing plant: the plant's four slots, screen, peripheral and save are
pinned by its own tests and by old worlds, and the separator needs a different input and different outputs. A second block
leaves the plant exactly as it was (`oldReprocessingSavesLoad`).

* **Slots** (through the item capability, the plant's sides): 0 waste in; 1 strontium, 2 caesium, 3 americium capsules out;
  4 stripped waste out. The top face shows only the input, the bottom only the outputs. The input takes only
  `bwr:fission_product_waste`; stripped waste is refused, so the same mass cannot be mined twice.
* **Work:** one waste item at a time, 100 ticks at 100 FE/t, **10,000 FE an item**, from a 40,000 FE buffer. The item is
  consumed when the time is up; taking it out first costs nothing.
* **Stores:** the four parts are kept as grains. A capsule (50,000 grains, 250 g) or a stripped item (1,000,000 grains) is
  released only when its store holds one; the rest carries to the next item. Items out plus grains held always equal the waste
  that went in, to the grain (`extractionFromRealSpentFuelYieldsCapsulesAndConservesMass`).
* **Backlog:** a product with a full slot's worth waiting stops the next item starting. Breaking the block drops the whole
  items it holds; the fractions are lost with it.
* **Saves:** `Energy` always; `WorkTicks`, `StrontiumGrains`, `CaesiumGrains`, `AmericiumGrains`, `StrippedGrains` and `Items`
  only when non-zero or non-empty.
* **Computers:** `bwr_isotope_separator`, `getStatus()`: `energyFE`, `working`, `progress`, `wasteIn`, `strontiumHeldG`,
  `caesiumHeldG`, `americiumHeldG`, `strippedHeldItems`, `strontiumOut`, `caesiumOut`, `americiumOut`, `strippedOut`.
* **Recipe:** iron, hoppers, copper and a brewing stand (`IHI` / `CBC` / `III`); vanilla only.

### Plutonium-238 is left out, and why

Pu-238 (0.568 W/g, 87.7 y) is the best RTG fuel there is, and it does not come out of spent fuel in useful amounts: the 1 to 2
% of Pu-238 in LWR plutonium is not separable economically. It is made by irradiating **neptunium-237** targets (10 to 15 %
convert per cycle, a few months each, ORNL's HFIR makes about 400 g a year). An honest path would need Np-237 recovered in
reprocessing (the plant's split has no neptunium: it counts fission products, plutonium and uranium), an Np-237 item, a
target rod through the existing target-rod mechanism, and a conversion of about 12 %. That is a separate change to the plant's
model, and the plant's tests are the owner's. The fuel list is data, so Pu-238 can be added later by one line and an item.

## The RTG

* **Fuel:** six wells, one item each. Anything with an entry in the fuel list (below) is accepted: an isotope ingot, a storage block (nine ingots), a separator capsule or a cobalt sample, by hand, hopper or pipe
  through the screen; the block exposes no item capability.
* **Power:** `thermal W` = the sum over the wells of `grams x specific power x 2^(-t / half-life)`; `electric W` = thermal x
  **5 %**; `FE/t` = electric / 13.4 x `rtgOutputMultiplier` (default 1). 5 % sits between the Beta-M's 4.3 %
  (10 W from 230 W) and the MMRTG's 5.5 % (about 110 W from 2,000 W); the GPHS-RTG reached 6.8 %. One figure for every isotope.
* **Decay:** a well's clock starts when its capsule goes in, in world time (a capsule in a chest does not age; a saved RTG
  keeps its clocks). **At the real half-lives the decay is negligible in game time**: 100 hours of running costs a strontium
  capsule 0.027 % of its heat, and a year of running 2.4 %. It is computed because it is true (`decayFollowsTheHalfLifeInTheBlock`).
  Real RTGs also lose thermocouple efficiency (the MMRTG falls about 1.6 to 4.8 % a year against 0.8 % for decay of Pu-238 alone);
  this model does not.
* **FE out:** the block holds up to 1,000 FE in a buffer that fills lazily from the game clock and gives it to the six
  neighbours that are loaded and can receive, every 5 ticks, and to anything that pulls (the energy capability is
  extract-only, on every face). Nothing is produced while the chunk is unloaded.
* **Heat:** the body always runs at its capsules' thermal power; the buffer being full only stops the electricity (a sealed
  RTG has no off switch). Its heat warms players through Cold Sweat if that is installed (see "RTG heat" below); there is no other heat effect on the world.
* **Why a ticker:** a generator has to push, so the RTG has a server ticker, as the turbine generator (`PowerModuleBlockEntity`,
  `pushOutputs`) has. It is as cheap as one gets: an RTG with no capsule and no stored energy returns after one boolean test;
  a fuelled one does one modulo a tick and, every 5 ticks, a settle and up to six capability lookups on loaded neighbours.
* **Dose:** the capsules' photon power x the capsule wall x **1e-4** (the housing: the Soviet Sr-90 units took 1 to 2 Sv/h at
  1 m of bare core down to 0.1 mSv/h) is a point source at the block (`RadiationSources.emit`), set when the contents change
  and when the chunk loads, cleared when the block is removed or unloaded. Six strontium capsules read 0.75 mSv/h at 1 m; six
  caesium capsules 33 mSv/h (a caesium RTG needs a wall); americium about 4 uSv/h.
* **Saves:** `Fuel` and `LoadedAt` only when a well is loaded, `Buffer` only when it holds something. An empty RTG saves nothing.
* **Model:** a finned cylinder (base plate on four feet, hazard band, ten radiator fins, flange and dome, an FE port on two
  faces), built headless in Blender (`art/models/rtg/build_models.py`, `tools-export-rtg.py`).
* **Recipe:** iron, copper, redstone and a hopper (`ICI` / `RHR` / `III`).

### Screen, signals, computers

* **Screen:** the six wells, thermal W, electric W, generating FE/t, the shortest half-life loaded, the dose at 1 m and the
  energy waiting.
* **Networking Wrench / panels:** signals `thermal_power` (W), `electric_power` (FE/t), `dose_rate` (mSv/h at 1 m), `capsules`,
  `buffer` (FE) and `fuelled`. Read-only: it has no commands.
* **Computers:** `bwr_rtg`, `getStatus()`: `capsules`, `thermalW`, `electricW`, `fePerTick`, `halfLifeYears`,
  `doseAt1mMsvPerH`, `bufferFE`, `efficiency`, `outputMultiplier`.
* **Config** (`bwr-gameplay.toml`): `rtgOutputMultiplier`, default **1.0**, range 0 to 1000. It scales the electricity only,
  not the heat, the dose or the decay. The default adds no hidden boost; six strontium capsules make 5.15 FE/t.

## Ingots and blocks: the pattern for an RTG isotope

Every RTG isotope comes in two forms the RTGs take: an **ingot** and a **storage block** of nine ingots. The small RTG takes either in a well
(one item per well, six wells; a block is nine ingots of heat in one well). The Large RTG takes both, by the thousand.

| Isotope | Ingot (`bwr:<id>`) | Grams | Block (`bwr:<id>`) | Grams | Made by |
|---|---|---|---|---|---|
| Sr-90 | `strontium_90_ingot` | 250 | `strontium_90_block` | 2,250 | Isotope Separator (capsule) |
| Cs-137 | `caesium_137_ingot` | 250 | `caesium_137_block` | 2,250 | Isotope Separator (capsule) |
| Am-241 | `americium_241_ingot` | 250 | `americium_241_block` | 2,250 | Isotope Separator (capsule) |
| Co-60 | `cobalt_60_ingot` | 100 | `cobalt_60_block` | 900 | a cobalt target in the reactor (`cobalt_sample`) |

**Why new ingot items and not renamed capsules.** The separator makes `bwr:strontium_90_capsule`, `bwr:caesium_137_capsule` and
`bwr:americium_241_capsule`, and a cobalt target makes `bwr:cobalt_sample`. Those ids are in saves, in inventories, in the separator's
and the rods' code and in tests that pin them (the separator's grain accounting is 250 g a capsule to the grain; the recipe tests list them as
"not craftable"). Renaming and re-texturing them would have worked for the picture but left no way back from a block (a recipe that
makes a capsule is a crafted capsule, which those pinned lists forbid). So the capsules stay exactly as they were, are still
accepted by every RTG, and **craft one to one into the ingot** (`<isotope>_ingot_from_capsule`, `cobalt_60_ingot_from_sample`),
the same mass. The ingot then packs and unpacks 9:1 with its block with no restriction. The capsules and the cobalt sample are out of the
creative tab; the ingots and blocks are in it.

**The pattern, for the next isotope** (polonium-210, plutonium-238, actinium-227, thulium-170 ...), all data except the Java list:

1. **Names.** `bwr:<isotope>_<mass number>_ingot` and `bwr:<isotope>_<mass number>_block`, for example `plutonium_238_ingot`, `plutonium_238_block`.
2. **Data map** `data/bwr/data_maps/item/rtg_fuel.json`: one line per item, the block's `grams_per_item` exactly nine times the ingot's and every other
   field the same:
   ```json
   "bwr:plutonium_238_ingot": { "isotope": "Pu-238", "specific_power_w_per_g": 0.568, "half_life_years": 87.7, "grams_per_item": 100,
       "gamma_w_per_g": 0.0001, "capsule_transmission": 0.5, "lead_hvl_cm": 0.05 },
   "bwr:plutonium_238_block": { "isotope": "Pu-238", "specific_power_w_per_g": 0.568, "half_life_years": 87.7, "grams_per_item": 900,
       "gamma_w_per_g": 0.0001, "capsule_transmission": 0.5, "lead_hvl_cm": 0.05 }
   ```
   `lead_hvl_cm` (new, optional, default 1.0) is the half-value layer of lead for the isotope's photons, what the Large RTG's housing uses
   (see below). The tooltips, the carried dose, both RTGs' screens, the signals and the Cold Sweat heat all read this one line; nothing else needs a number.
3. **Items and blocks** (Java, `BwrItems` and `BwrBlocks`): the ingot is `new IsotopeCapsuleItem(new Item.Properties())`; the block is
   `new Block(isotopeBlock(MapColor...))` with item `new IsotopeBlockItem(block, new Item.Properties())`. Add both to the fuel tab's list in
   `BwrItems`.
4. **Assets.** One entry in `ISOTOPE_COLOURS` in `tools-make-isotope-assets.py` makes the ingot icon, the block texture, models, blockstate and loot table.
   Add `block.bwr.<id>`, `item.bwr.<id>` for both to the language file, and the block to `mineable/pickaxe` and `needs_iron_tool`.
5. **Recipes** (`data/bwr/recipe`, three files per isotope), the shapes the tests and the audit expect:
   * `<id>_block.json`: `crafting_shaped`, pattern `###` / `###` / `###`, key `#` = `bwr:<id>_ingot`, result 1 `bwr:<id>_block`.
   * `<id>_ingot_from_block.json`: `crafting_shapeless`, one `bwr:<id>_block`, result 9 `bwr:<id>_ingot`.
   * for an ingot made by a machine from some other item, a one-to-one `<id>_ingot_from_<that item>.json`, the same mass.
   A new ingot has a crafting recipe (from its block), so it is **not** added to the "not craftable" lists in `FuelFabricatorItemRouteTests` and
   `tools-audit-recipes.py`; the block-and-ingot loop is the audit's accepted "storage pair". Mass is conserved at every step, and a recipe for the
   *first* ingot out of nothing is the thing not to write.
6. Carried, an ingot or block is a gamma source through the same data-map line (`CarriedIsotopes`); a **placed** block is inert storage, like the same ingots in a chest
   (the mod's carried-source rule has never made a chest of capsules a source, and a block entity per storage block to change that was not worth its cost).

## Cobalt-60: the reactor-bred bulk fuel

Strontium-90, caesium-137 and americium-241 come from reprocessing waste and there is no way to make them in bulk: strontium-90 is a fission product and only
comes out of fuel. What a reactor really makes in bulk is **cobalt-60**: neutron capture on cobalt-59 in target or adjuster rods. CANDU adjuster rods
make most of the world's supply, and GE Hitachi announced cobalt-60 production in a BWR at Clinton in 2011 (FUELS-AND-RODS.md). The mod already had the cobalt
target rod, which after 72 operating hours makes one sealed `bwr:cobalt_sample`; that is now the RTG fuel, with an ingot and a block of its own.

| Number | Value | Where it comes from |
|---|---|---|
| Half-life | 5.2714 y | Co-60 decay data, <https://en.wikipedia.org/wiki/Cobalt-60> |
| Specific activity | 4.19e13 Bq/g, 1,130 Ci/g | ln 2 / half-life x Avogadro / 59.93 u; Wikipedia says close to 42 TBq/g, about 1,100 Ci/g |
| Decay energy per decay | 2.823 MeV | beta (mean 0.317 MeV) + gammas 1.173 + 1.332 MeV |
| Thermal power | **18.9 W/g** | 4.19e13 Bq/g x 2.823 MeV x 1.602e-13 J/MeV, which agrees with the 17 to 19 W/g the owner quoted (Wikipedia's "about 26 W/g" does not follow from its own activity and energies, and is not used). A thick housing absorbs the gammas, so nearly all of it is heat |
| Photon power | 16.8 W/g | 2.506 MeV a decay, in the dose model's unit: 14.8 Sv/h per gram at 1 m (the exposure constant 1.32 R m2/(Ci h) says 14 to 15) |
| Wall | 0.90 | 3 mm of 316L at 1.25 MeV |
| Lead half-value layer | 1.2 cm | broad beam at 1.25 MeV |

**Grams per sample: 100 g, a stated assumption.** The target-rod documentation gives no mass. A back-of-envelope for the real rod (figures recalled, not looked up: a 4 m, 1 cm rod holds about 2.8 kg of cobalt-59, the capture cross-section is about 37 b, a BWR's thermal flux
about 3e13 /cm2/s) says 72 hours at rated flux makes about 1 g of Co-60, because real cobalt is irradiated for years: 100 g is a deliberate game-scale figure, one sample
the heat of eight strontium capsules, so a cobalt campaign is an RTG fuel and not a curiosity. It is one line in the data map (`bwr:cobalt_sample`, `bwr:cobalt_60_ingot`,
`bwr:cobalt_60_block`); a datapack retunes it. One sample is 1,890 W of heat and **carried reads about 1,330 Sv/h at 1 m**: far worse than a caesium capsule (54 Sv/h),
and the honest cost of the choice. The sample's other uses are unchanged (the comparator recipe `cobalt_sample_detector` still takes it, tested), but anyone who carries one now
has a dose.

## RTG heat, and the heat-only switch

An RTG is a heater. The body always runs at its material's thermal power (a sealed RTG has no off switch), and 5 % of it can leave as FE.

* **Heat-only switch** (small RTG and Large RTG): stops the FE and nothing else. The heat, the decay and the dose are unchanged; the buffer stops filling. It is a button on the
  screen, a `heat_only` signal (read-only on the small RTG, whose signal list has never had a command; a `set_heat_only` toggle command on the Large RTG, for a panel or the wrench) and
  `setHeatOnly(boolean)` on the computer face. The save key `HeatOnly` is written only when it is on, so an old RTG saves and loads exactly as before. Nothing switches it for you.
* **Cold Sweat** (body temperature, an optional dependency): with it installed, each RTG cell is a Cold Sweat `BlockTemp` that adds **0.15 Cold Sweat units per kW** of the
  RTG's thermal power at its own block (a unit is 25 C; so 1 kW is 3.75 C), faded by Cold Sweat with distance to nothing at its block range (7 blocks by default), capped at
  1.5 units (37.5 C) in total however many RTGs there are. A small strontium RTG (1.38 kW) adds 0.21 units, 5 C, beside it; a furnace adds 0.33. The Large RTG's twelve cells share its watts.
  **An empty RTG adds nothing** (its `BlockTemp` is not valid). The heat is the thermal watts, so heat-only mode changes nothing here.
  * *API used:* Cold Sweat's Java API, `com.momosoftworks.coldsweat.api.temperature.block_temp.BlockTemp` registered on `BlockTempRegisterEvent` (NeoForge event bus), against Cold Sweat 2.4.4;
    2.4.3 or later is needed (the dynamic `getMaxEffect`/`getRange` getters). The datapack/config route (`block_temperatures`) gives a block one fixed temperature and cannot read a
    block entity, and an RTG's heat is what is loaded, so it was not an option.
  * *Optional:* `cold_sweat` is `type = "optional"` in `neoforge.mods.toml`; `dev.bwr.mod.compat.coldsweat` is the only package that names a Cold Sweat type and `BwrMod` loads it
    only when `ModList.isLoaded("cold_sweat")` (the same pattern as CC:Tweaked and Mekanism). Cold Sweat is `compileOnly` (never bundled; it is GPL-3) from Modrinth's maven by
    version id (`coldSweatVersionId` in `gradle.properties`), and on the dev runtime only with `-PbwrColdSweat`, so no ordinary run, gate or jar has it. The registration is in a
    try/catch, so a Cold Sweat whose API differs logs a warning and leaves the RTGs as plain blocks.
  * *Tested:* see "What is tested" (the real `BlockTempModifier` with `-PbwrColdSweat`; the mod running without it in every ordinary run).
  * *Not modelled:* the client side. Cold Sweat computes the world temperature on the server, where the RTG's block entity has its contents; the client's copy of an RTG block
    entity is empty (neither RTG sends its contents to clients), so a client-side prediction of the heat would read zero until the server's value arrives.
* **Mekanism heat: skipped.** Mekanism's heat capability (`mekanism:heat_handler`) is not in the API jar this mod compiles against (it is in the full mod), and its model
  is temperature and conductance between ticking machines: a faithful "this many watts" source would need a thermal resistance invented here and a
  heat-capacity-and-dissipation model checked against Mekanism's own consumers, which could not be done honestly in this change. The thermal watts are on the screens, the signals and the
  computer faces for anything that wants to use them.

## The Large RTG

A 2 x 2 footprint, **3 high**, one item (`bwr:large_rtg`), for a very large remote power source. Recipe: five lead ingots, an RTG, two iron blocks and a hopper (`LLL` / `LRL` / `SHS`).

* **Height, and why 3.** The housing is 40 cm of lead-equivalent wall in a 2 m square, which leaves a core cavity about 1.2 m across; a two-block-high unit had room for that wall or
  for the radiator stack that sheds up to 920 kW of strontium heat (and 18.9 MW of cobalt), not both. Three high is a shielded two-block body with a one-block finned radiator stack and lifting cap.
* **Placement.** Like the pumps (PUMP-MODELS.md): leave the whole footprint clear; the clicked block is the corner (the controller, cell 0) and the unit extends east and south of it turned
  to the player's facing, front (the hatch, placards, nameplate) toward the player. Placement checks every cell for permission, loaded chunk, build height, world border, replaceable block and
  entities, and refuses the whole unit if any fails. Breaking any cell removes the whole assembly and returns the one item and **every item that was loaded**; pistons cannot move it. A part whose
  controller is removed by other means removes itself, and the controller checks the footprint every 100 ticks.
* **One ticker.** Only the controller cell has a block entity and the assembly's one server ticker (the small RTG's generator pattern: one boolean test when empty; one modulo a tick and, every 5 ticks,
  a lazy settle and up to 12 capability lookups on what touches the bottom layer when running). No per-cell tickers, no scheduled ticks.
* **Capacity: 1,000 kg**, tracked as a **mass**, not slots. The block keeps a count per item kind (ingots, blocks and capsules of each isotope; 4,000 ingots is a number); the mass is the sum
  of counts times the fuel list's grams, and an item that would take it past 1,000,000 g is refused whole (a stack goes in as far as it fits). Mixed isotopes are fine.
* **Loading.** Right-click with fuel in the hand; the screen's **Load my fuel** button (every fuel item in the inventory, up to the cap); a hopper or pipe into any face of any cell (item capability, insert only, one virtual slot);
  nothing can be taken out except by breaking the block. A full unit refuses and the hopper keeps its items.
* **Power.** The same physics as the small RTG, over the store: thermal W = the sum over kinds of count x grams x specific power x 2^(-age / half-life); electric = thermal x 5 %; FE/t = electric / 13.4 x `rtgOutputMultiplier`
  (which scales the electricity only, as before). Decay: every item of a kind shares a half-life, so a kind has one *effective age*, merged exactly when a batch is added (`LargeRtgStore.mergedAgeSeconds`), tested to 1e-12 against summing the batches.
  FE buffer 1,000,000 FE; it pushes to whatever touches its bottom layer and gives to anything that pulls (extract-only on every face of every cell).

  | 1,000 kg of | Thermal | Electric | FE/t |
  |---|---|---|---|
  | Sr-90 | 920 kW | 46 kW | **3,432.8** |
  | Cs-137 | 420 kW | 21 kW | 1,567.2 |
  | Am-241 | 114.7 kW | 5.74 kW | 428.0 |
  | Co-60 (10,000 ingots) | 18.9 MW | 945 kW | 70,522 |

* **Radiation: heavy shielding, and the number is always shown.** The housing is **40 cm of lead-equivalent** (a lead, tungsten and steel sandwich in the 2 m body). What gets through is
  `2^(-40 cm / half-value layer)` for the isotope, the half-value layer of lead in the fuel line (`lead_hvl_cm`): Sr-90 0.6 cm, Cs-137 0.65, Am-241 0.02, Co-60 1.2. The result is the unit's point source
  (`RadiationSources`, set when the contents change and when the chunk loads, cleared when it is removed or unloaded) and the screen's **Dose at 1 m**. For scale the screen also shows what the **bare core** would read.

  | 1,000 kg of | Bare core at 1 m | Through the housing |
  |---|---|---|
  | Sr-90 | 5.0 kSv/h | 4e-14 mSv/h |
  | Cs-137 | 217 kSv/h | 6e-11 mSv/h |
  | Am-241 | 28 Sv/h | 0 |
  | Co-60 | 13.3 MSv/h | **1.23 mSv/h** |

  40 cm of lead stops caesium, strontium and americium completely: the model has only the direct beam, and a real cask is limited by streaming through its hatch, intake and terminals and by
  scatter, which this does not model, so the reading for those is the true value of the model and not a promise about a real unit. **Cobalt-60 is the one that is still there**: a full cobalt unit reads 1.2 mSv/h at a metre (about a hundred times the public limit rate), which is exactly what
  "a cobalt RTG needs heavy walls" means; thicker walls would be a different block. The small RTG's flat 1e-4 housing is unchanged: a small RTG of cobalt reads tens of mSv/h.
* **Heat-only** and **Cold Sweat**: as above.
* **Model.** Built headless in Blender (`art/models/rtg/build_large.py`, split into cells by `tools-export-large-rtg.py`): a plinth, an eight-sided shield jacket with bolted bands and a hazard stripe, a stack of fourteen
  radiator fins on four posts, a lifting cap, copper FE terminals on the east and west faces, a hinged loading hatch with an intake funnel, hazard placards on the front and back, a plain nameplate and a service ladder.
* **Screen, signals, computers.** The screen shows stored mass by isotope against the cap, thermal W, electric W, FE/t, half-life, the dose at 1 m and the bare core's, the buffer, the heat-only switch and Load my fuel.
  Signals: `thermal_power` (W), `electric_power` (FE/t), `dose_rate` (mSv/h at 1 m), `mass` (kg), `buffer`, `fuelled`, `heat_only`, and the command `set_heat_only`.
  Computer face `bwr_large_rtg` (on every cell): `getStatus()` (`massKg`, `capacityKg`, `items`, `thermalW`, `electricW`, `fePerTick`, `halfLifeYears`, `doseAt1mMsvPerH`, `bareDoseAt1mMsvPerH`, `bufferFE`, `efficiency`,
  `outputMultiplier`, `heatOnly`, `kgByIsotope`) and `setHeatOnly(boolean)`. The small RTG's `getStatus()` gains `heatOnly` and its face gains `setHeatOnly`.
* **Saves.** `Kinds` (a list of `Id`, `Count`, `Epoch`) only when something is loaded, `HeatOnly` only when on, `Buffer` only when it holds something; an empty unit saves nothing. A save cannot hold more than the cap
  and an item that has left the data keeps its count (no power; it drops when the block breaks).

### The supply is the problem, honestly

Nothing here makes isotope from nothing. **Strontium-90, caesium-137 and americium-241 come from reprocessing waste**: the separator gives about 0.11 kg of Sr-90 per waste item (109.7 g of the 5 kg item), 0.18 kg of Cs-137
and 0.075 kg of Am-241. 1,000 kg of strontium-90 is 9,100 waste items, the waste of about 5,400 assemblies burned to 45 GWd/t, and 91 million FE of separator work; that is a very long-term goal, and the separator's yields are unchanged. **Cobalt-60 comes from irradiating cobalt targets in the reactor** (72 operating hours a rod, one 100 g sample each): 10,000 samples (1,000 kg, a full cobalt unit) is ten thousand rod-harvests, still a campaign, but the only one of the four materials a reactor makes in bulk (the others are fission products and only come from fuel).
Real cobalt sources are far smaller: Wikipedia gives India's capacity as over 6 MCi (about 5 t of Co-60 at 1,100 Ci/g) in 2021, and a disused teletherapy source stolen in 2013 was about 3,000 Ci (2.7 g). The cap is a limit, not a typical load.

## Carried capsules

An isotope capsule in a player's inventory or off-hand is a point source at the player (the way spent resin is), read by the
survey meter and the area monitor, and by the carrier's own dose. The photon power per gram is what the isotope's decays send
out as photons, in the dose model's own unit (`DoseModel`'s 1 MeV tissue coefficient: 1 W of gamma is 881 mSv/h at 1 m).

| Capsule (250 g) | Photons, W/g | Wall passes | Carried, at 1 m | What the number is |
|---|---|---|---|---|
| Cs-137 | 0.29 (0.85 yield x 662 keV x 3.2e12 Bq/g) | 0.85 (3 mm of 316L) | **about 54 Sv/h** | Bare caesium is about 0.29 Sv/h per gram; a Hanford capsule (35 kCi) reads 8,600 to 18,000 rem/h at 3 ft |
| Sr-90 | 0.0057 (bremsstrahlung of Y-90's 2.28 MeV betas) | 1 | about 1.26 Sv/h | The RHS-90 core (about 255 g) reads 1 to 2 Sv/h at 1 m |
| Am-241 | 0.00063 (59.5 keV x 0.36 plus the Np L X-rays) | 0.05 (3 mm of steel stops it) | about 7 mSv/h | The reference constants for Am-241 differ by 4 to 25 times; this is the photon-energy estimate |

The dose is real, and the mod's effects are mild next to it (the meter is honest; a plant that is walked into is not
instantly fatal in game), but 54 Sv/h kills in minutes in life. Carry caesium for as short as you can.

## The fuel list is data: adding radium

The RTG's fuel is the item data map **`bwr:rtg_fuel`** (`data/bwr/data_maps/item/rtg_fuel.json`, synced to clients). An item
is RTG fuel exactly when it has an entry, so a datapack or another mod adds a fuel with one line and no Java; the
well filter, the screen, the tooltip, the carried dose and the signals all read the same line. The fields:

| Field | Meaning |
|---|---|
| `specific_power_w_per_g` | Thermal W per gram, with the daughters that carry the heat (required) |
| `grams_per_item` | Grams of the isotope in one item (required) |
| `half_life_years` | Optional; 0 or missing never decays |
| `gamma_w_per_g` | Optional photon power, W/g, default 0 (no carried dose, no RTG dose) |
| `capsule_transmission` | Optional, what the item's wall lets through, 0 to 1, default 1 |
| `isotope` | Optional label for the tooltip |

### Radium (added with the thorium merge)

Ra-226 with its daughters in equilibrium (radon retained): about 0.16 W/g (27 MeV a chain, 1600 y; historic calorimetry gave
0.12 to 0.16 W/g), half-life 1,600 years, 0.99 Ci/g, 0.825 R m2/(Ci h) with a 0.5 mm platinum filter, which is about 8.2 mSv/h per
gram at 1 m and 0.0093 W/g of photons in the dose model's unit. A radium item is a poor RTG fuel (a gram makes 0.16 W of
heat against strontium's 0.92) but it lasts and it is mined. `rtg_fuel.json` has this entry. One `bwr:radium` is a sealed source of
about 10 mg of Ra-226 (THORIUM.md), so `grams_per_item` is 0.01 and a radium item makes 1.6 mW of heat: an honest, tiny
RTG fuel. `capsule_transmission` is 0 because the radium item already carries its own gamma source (`RadiumItem`, through
`RadiationSources`); a non-zero value here would count its dose twice.

```json
"bwr:radium": { "isotope": "Ra-226 with daughters", "specific_power_w_per_g": 0.16, "half_life_years": 1600, "grams_per_item": 0.01, "gamma_w_per_g": 0.0093, "capsule_transmission": 0.0 }
```

### Radium-beryllium neutron source (added with the thorium merge)

Historically real: Ra-226 alphas on beryllium, about 30 neutrons per million alphas, or about 1.5e7 n/s per gram of radium
(one source gives 4 to 15e6; this is the weakest-sourced figure here), against about 7.6e6 n/s per gram of Am-241 in an Am-Be
source. The same order, so the existing Am-Be startup rod stands in for it. Radium is usable for one without Java:
`data/bwr/recipe/americium_beryllium_source_from_radium.json` (a radium-fuelled Ra-Be rod is the Am-Be rod's data; the
gamma of radium, which a real Ra-Be source carries, is not modelled on the rod):

```json
{
  "type": "minecraft:crafting_shapeless",
  "category": "misc",
  "ingredients": [ { "item": "bwr:irradiation_casing" }, { "item": "bwr:radium" } ],
  "result": { "id": "bwr:specialty_rod", "count": 1,
    "components": { "bwr:core_insert": { "kind": "americium_beryllium_source", "exposure_seconds": 0 } } }
}
```

A distinct Ra-Be rod with its own strength would need a `CoreInsert.Kind`, which is Java, and is not added.

## The Am-Be startup rod

The Am-Be source rod used to be crafted from an irradiation casing and an echo shard, a stand-in with no isotope behind it
(`data/bwr/recipe/americium_beryllium_source.json`, unchanged). There is now a second route, **an irradiation casing and an
Am-241 capsule** (`americium_beryllium_source_from_capsule.json`), which makes the same rod. The old recipe is left in so no
world or advancement loses its route; the owner may retire it.

## What is tested

The Large RTG, RTG heat and the ingot and block pattern (namespace `bwrlargertg`, `LargeRtgTests`; `:core:acceptance` `LargeRtgStoreTest`): 1,000 kg of strontium reads 920 kW / 46 kW / 3,432.8 FE/t and a mixed 1,000 kg adds; the cap is exact (the 4,001st ingot and a block are refused);
decay and the merged clock; heat-only gives no FE and the full heat; the dose readout is the DoseModel through the housing and the block is exactly that point source; placement and breaking in all four facings (one across a chunk boundary), a blocked footprint refused,
one drop plus the contents; a hopper loads up to the cap and then keeps its items; FE reaches a real neighbour; saves, a hand-edited save, and an old small-RTG save; signals and the computer faces; the recipes and the data lines; the heat helpers and, with `-PbwrColdSweat`, the real Cold Sweat
`BlockTempModifier`; and every ordinary run is the mod with Cold Sweat absent. `-PbwrLargeRtgClientCheck` takes the pictures.


`:core:acceptance`: `RadioisotopePowerTest` (power from mass and specific power, half-life decay, efficiency and FE, photon
dose) and `IsotopeExtractionTest` (mass conserved to the grain over 20,000 random inputs and 500 items, the documented
masses). GameTests, namespace `bwrrtg` (`RtgTests`): extraction from real spent fuel through the real plant with mass
accounted; the separator's fractions and saves; old reprocessing saves load; an RTG powers a real neighbour (delivered plus
waiting is rate times ticks to within 1e-6) and gives zero with no fuel; decay in the block; the RTG's save; the carried
dose and the block's point source; the fuel list as data and a parsing radium line; the recipes; the signals; the computer
faces. `-PbwrRtgClientCheck` takes the pictures.

## References

* Cobalt-60 and the Large RTG: Co-60 half-life, specific activity (about 1,100 Ci/g), gamma energies and CANDU and BWR production, <https://en.wikipedia.org/wiki/Cobalt-60>; GE Hitachi and Exelon on Co-60 at Clinton,
  <https://www.ge.com/news/press-releases/ge-hitachi-nuclear-energy-and-exelon-announce-potential-production-critical-medical>; lead half-value layers (Cs-137 about 0.65 cm, Co-60 about 1.2 cm),
  <https://open-exam-prep.com/study-guides/cih/ionizing-radiation-protection/radiation-shielding-physics>.
* Cold Sweat's BlockTemp API (`BlockTemp`, `BlockTempRegistry`, `BlockTempRegisterEvent`, `BlockTempModifier`), read from its source, <https://github.com/Momo-Softworks/Cold-Sweat> (branch `1.21-FG`, version 2.4.4, GPL-3).

* RTG efficiency, power and fuel: MMRTG, <https://www.energy.gov/ne/articles/powering-curiosity-multi-mission-radioisotope-thermoelectric-generators>,
  <https://www.jpl.nasa.gov/news/press_kits/mars_2020/launch/mission/spacecraft/power/>, <https://en.wikipedia.org/wiki/Multi-mission_radioisotope_thermoelectric_generator>;
  GPHS-RTG, <https://en.wikipedia.org/wiki/GPHS-RTG>, <https://science.nasa.gov/planetary-science/programs/radioisotope-power-systems/power>;
  Beta-M, <https://en.wikipedia.org/wiki/Beta-M>; Soviet Sr-90 units, <https://bellona.org/news/nuclear-issues/radioactive-waste-and-spent-nuclear-fuel/2005-04-radioisotope-thermoelectric-generators-2>;
  the RTG overview and Sentinel units, <https://en.wikipedia.org/wiki/Radioisotope_thermoelectric_generator>.
* Specific power and half-life: Pu-238, <https://en.wikipedia.org/wiki/Plutonium-238>, <https://mathscinotes.com/2012/01/nuclear-battery-math/>;
  Sr-90, <https://en.wikipedia.org/wiki/Strontium-90>; Cs-137, <https://en.wikipedia.org/wiki/Caesium-137>, and the Hanford capsules (35 kCi, about 190 W),
  <https://www.nationalacademies.org/read/10684/chapter/7>; Am-241, <https://en.wikipedia.org/wiki/Americium-241>; Po-210, <https://en.wikipedia.org/wiki/Polonium-210>;
  Ra-226, <https://en.wikipedia.org/wiki/Radium>, Curie's calorimetry, <https://www.nobelprize.org/uploads/2018/06/pierre-curie-lecture.pdf>; Cm-244, <https://en.wikipedia.org/wiki/Curium>.
* What spent fuel holds: cumulative fission yields, <https://en.wikipedia.org/wiki/Fission_product_yield>; plutonium vectors of spent PWR fuel,
  <https://world-nuclear.org/information-library/nuclear-fuel-cycle/fuel-recycling/plutonium>; americium in spent fuel, <https://en.wikipedia.org/wiki/Americium>;
  Pu-238 from Np-237 targets, <https://www.frontiersin.org/articles/10.3389/fnuen.2022.1044657/full>, <https://www.osti.gov/biblio/1029578>.
* Gamma and shielding: exposure-rate constants and lead half-value layers, <https://open-exam-prep.com/study-guides/cih/ionizing-radiation-protection/radiation-shielding-physics>;
  mass attenuation of lead, <https://physics.nist.gov/PhysRefData/XrayMassCoef/ElemTab/z82.html>; Am-241 fact sheet, <https://researchhow2.uc.edu/docs/default-source/default-document-library/isotope-fact-sheets.pdf>;
  Ra-Be and neutron sources, <https://en.wikipedia.org/wiki/Neutron_source>.
* Why caesium is not an RTG fuel in practice (heavy shielding, soluble chloride; the Hanford capsules were never used for generators),
  <https://www.nationalacademies.org/read/10684/chapter/7>, <https://en.wikipedia.org/wiki/Caesium-137>.

The mod's own figures that are derived, not quoted: the Sr-90 and Cs-137 shares (yield x mass number / 235), the Am-241 share
(1.5 % of the waste item), the Sr-90 photon power (from the RHS-90 dose rate), the 5 % efficiency (a figure between the units
above) and the 1e-4 body transmission. The research's flagged weak points: no source states a "typical RTG efficiency"
range, the Am-241 gamma constant disagrees by 4 to 25 times between sources, the Np-237 content at 45 GWd/t has only a 56 GWd/t
measurement, and the radium-beryllium yield is weakly sourced.
