# RTG and isotope extraction

Two machines and four items close one more loop of the fuel cycle: the **fission product waste** the reprocessing plant
has always made now has a use.

| Piece | What it is |
|---|---|
| **Isotope Separator** (`bwr:isotope_separator`) | Fission-product waste in; sealed capsules of strontium-90, caesium-137 and americium-241, and stripped waste, out. |
| **RTG** (`bwr:rtg`) | A radioisotope thermoelectric generator. Six wells take capsules; it makes a small steady supply of FE for decades. |
| `bwr:strontium_90_capsule`, `bwr:caesium_137_capsule`, `bwr:americium_241_capsule` | 250 g of the isotope each, sealed. Radioactive when carried. |
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

* **Fuel:** six wells, one item each. Anything with an entry in the fuel list (below) is accepted, by hand, hopper or pipe
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
  RTG has no off switch). There is no heat effect on the world.
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

### The line to add when the thorium branch's `bwr:radium` is merged

Ra-226 with its daughters in equilibrium (radon retained): about 0.16 W/g (27 MeV a chain, 1600 y; historic calorimetry gave
0.12 to 0.16 W/g), half-life 1,600 years, 0.99 Ci/g, 0.825 R m2/(Ci h) with a 0.5 mm platinum filter, which is about 8.2 mSv/h per
gram at 1 m and 0.0093 W/g of photons in the dose model's unit. A radium item is a poor RTG fuel (a gram makes 0.16 W of
heat against strontium's 0.92) but it lasts and it is mined. Add inside `"values"` of `rtg_fuel.json`, with `grams_per_item`
set to the mass of one `bwr:radium` (10 g shown):

```json
"bwr:radium": { "isotope": "Ra-226 with daughters", "specific_power_w_per_g": 0.16, "half_life_years": 1600, "grams_per_item": 10, "gamma_w_per_g": 0.0093, "capsule_transmission": 0.5 }
```

Do not add it before `bwr:radium` exists: a data-map line for an item that is not registered is dropped with a warning at
best. Nothing in the Java names radium; `theFuelListIsDataAndAnotherLineParses` checks that this exact value parses with
the real codec.

### Radium-beryllium neutron source (not added)

Historically real: Ra-226 alphas on beryllium, about 30 neutrons per million alphas, or about 1.5e7 n/s per gram of radium
(one source gives 4 to 15e6; this is the weakest-sourced figure here), against about 7.6e6 n/s per gram of Am-241 in an Am-Be
source. The same order, so the existing Am-Be startup rod stands in for it. To make radium usable for one without Java,
add `data/bwr/recipe/americium_beryllium_source_from_radium.json` (a radium-fuelled Ra-Be rod is the Am-Be rod's data; the
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

`:core:acceptance`: `RadioisotopePowerTest` (power from mass and specific power, half-life decay, efficiency and FE, photon
dose) and `IsotopeExtractionTest` (mass conserved to the grain over 20,000 random inputs and 500 items, the documented
masses). GameTests, namespace `bwrrtg` (`RtgTests`): extraction from real spent fuel through the real plant with mass
accounted; the separator's fractions and saves; old reprocessing saves load; an RTG powers a real neighbour (delivered plus
waiting is rate times ticks to within 1e-6) and gives zero with no fuel; decay in the block; the RTG's save; the carried
dose and the block's point source; the fuel list as data and a parsing radium line; the recipes; the signals; the computer
faces. `-PbwrRtgClientCheck` takes the pictures.

## References

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
