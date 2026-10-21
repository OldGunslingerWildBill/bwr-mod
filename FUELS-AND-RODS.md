# Fuel and specialty rods — alpha.21

Open **Realistic BWR: Fuel & Rods** in the creative inventory. The machine tab
now contains plant hardware; this tab contains 17 selectable fuel grades, eight specialty
rod cassettes, empty irradiation rods, target charges and samples. All icons
are small PNGs rendered from the Blender studio in `art/models/fuel_icons/`.
Items still represent one logical core position, not a placeable fuel block.

## Fuel catalogue

| Family | Available grades | Meaning |
|---|---|---|
| Uranium | 0.711%, intermediate 2.70%, high BWR 3.50%, 4.95%, 8.00%, 19.75%, 20.00% | U-235 weight fraction in the simplified fuel model |
| Gadolinia uranium | 3.50% | Additional burnable absorption compared with the standard 3.50% assembly |
| Mixed oxide | Lean 4.5%, standard 7%, rich 9.5% | Effective fissile fractions for gameplay, **not total plutonium percentages** |
| Plutonium | Lean 4%, standard 6%, rich 8.5% | Existing Pu-dominated kinetics family, extended with experimental grades |
| Thorium / U-233 driver | Lean 2%, standard 3%, rich 5% | Fissile driver fraction; thorium itself is fertile |

The original `leu`, `heu`, `mox`, `plutonium` and `thorium` definitions retain
their numerical tuning and item components. New grades use the existing
enrichment-utilisation curve and family constants. This is an extension of
the mod's calibrated model, not a library of validated commercial fuel designs
or an isotope-by-isotope depletion calculation. Natural uranium is a weak,
subcritical experimental loading in this BWR model.

The fuel fabricator's chemical route selects uranium, MOX and plutonium grades
from the actual incoming enrichment, without granting additional fissile
inventory (its item route, below, makes the uranium grade the player picks). The
gadolinia variant and thorium grades are available from the creative tab or
datapack recipes; there is no new thorium processing chain. Existing fuel
crafting remains compatible. Every fuel definition remains datapack editable
under `data/bwr/fuel_type/`.

### RVU fuel naming and saved rods

The intermediate (2.70%) and high BWR (3.50%) categories use the existing
`uranium_27` and `leu` definitions and Blender icons. Both are low-enriched
uranium; the word high distinguishes this BWR catalogue grade from intermediate,
not the separate 20% experimental HEU option. The percentages are the
project owner’s selected mod values, not values established by Figure 2.2-8.

The 1.20% and 1.40% grades are no longer offered in the creative tab or selected
by the fabricator. Their definitions, icons and item IDs remain for saved rods
and datapack compatibility. Fabrication still records the actual input fissile
fraction, including values between catalogue grades; it never creates extra
fissile inventory or upgrades old fuel.

## Fuel fabricator: the item route

The fabricator has two ways in, and they take turns.

- **Chemical route (unchanged).** Mekanism chemicals through the machine's
  tanks; the enrichment of the assembly is the ratio of fissile fuel to matrix
  the player feeds. Needs Mekanism.
- **Item route (new).** Uranium and steel *items* in, an assembly and depleted
  uranium out. Needs only items: Mekanism supplies them in a normal game, but
  any mod that tags its uranium and steel works, and the machine loads and runs
  with Mekanism absent.

The machine has four slots, and hoppers and pipes use them through the item
capability: **uranium in**, **steel in**, **assembly out** and **depleted
uranium out**. The assembly is slot 0 (where the single output slot always was),
depleted uranium slot 1, uranium slot 2, steel slot 3. A hopper above feeds the
inputs (the top face shows only the inputs); a hopper below collects the
outputs (the bottom face shows only the outputs); the four sides show all four.
Outputs can only be taken, inputs only given, and a pipe cannot pull an input
back out (the GUI can). The Mekanism chemical tanks stay on Mekanism's own
capability.

### What counts as uranium and steel

By item tag, so any mod's items work:

| Input | Tag | Counts as |
|---|---|---|
| Uranium ingot | `c:ingots/uranium` | 1 ingot |
| Raw uranium | `c:raw_materials/uranium` | 1 ingot (it smelts to one) |
| Yellow cake | `bwr:fabricator_feed/yellow_cake` (optional, holds `mekanism:yellow_cake_uranium`) | half an ingot (a Mekanism enrichment chamber makes two from one ingot) |
| Steel | `c:ingots/steel` | 1 steel |

Depleted uranium is tagged `c:ingots/depleted_uranium` and is **not** a
uranium ingot, so the machine will not take it back as feed.

### Choosing the enrichment, and what it costs

The screen has a selector over the uranium ladder (0.71 %, 2.70 %, 3.50 %,
4.95 %, 8.00 %, 19.75 %, 20.00 %). The retired 1.2 % and 1.4 % grades are not
offered, as in the creative tab. A fabricator starts on 3.50 % (`leu`); the
choice is saved with the block only when it is not that default.

The uranium an assembly takes is the **mass balance of an enrichment plant**,
not a price list. An assembly is 4 ingots' worth of enriched product, and the
natural uranium needed to make that much at assay `xp`, with natural feed
`xf` = 0.711 % and 0.25 % tails `xt`, is

    feed / product = (xp - xt) / (xf - xt)        depleted uranium = feed - product

The uranium that did not become product comes back as **depleted uranium
ingots**. Feed is rounded up and depleted uranium rounded down, so the machine
can lose to rounding but never gain. Steel is the cladding and structure, a
fixed 4 ingots whatever the enrichment. Fabrication takes 100 ticks plus 3 per
uranium ingot at 200 FE a tick.

| Assembly | Feed per product | Uranium ingots | Steel | Depleted uranium back | Time | Energy |
|---|---|---|---|---|---|---|
| 0.711 % natural (`natural_uranium`) | 1.00 | 4 | 4 | 0 | 5.6 s | 22,400 FE |
| 2.70 % (`uranium_27`) | 5.31 | 22 | 4 | 17 | 8.3 s | 33,200 FE |
| 3.50 % (`leu`) | 7.05 | 29 | 4 | 24 | 9.4 s | 37,400 FE |
| 4.95 % (`uranium_495`) | 10.19 | 41 | 4 | 36 | 11.2 s | 44,600 FE |
| 8.00 % (`uranium_8`) | 16.81 | 68 | 4 | 63 | 15.2 s | 60,800 FE |
| 19.75 % (`uranium_1975`) | 42.30 | 170 | 4 | 165 | 30.5 s | 122,000 FE |
| 20.00 % (`heu`) | 42.84 | 172 | 4 | 167 | 30.8 s | 123,200 FE |
| *1.20 % (retired)* | *2.06* | *9* | *4* | *4* | | |
| *1.40 % (retired)* | *2.49* | *10* | *4* | *5* | | |

The formula is `dev.bwr.core.fuel.EnrichmentMassBalance` and nowhere else
(unit test `EnrichmentMassBalanceTest`, hand-worked numbers); the cost of a
fuel type follows its `nominal_enrichment`, so a datapack that retunes a grade
retunes its cost. An assembly the item route makes is the same data the
chemical route stamps on a fresh bundle: that fuel type, its nominal
enrichment, 180 kg of heavy metal, no burnup.

### How the machine takes its inputs

A 20 % assembly takes 172 ingots and a stack holds 64, so the machine takes
uranium out of its slot into an internal store, only as far as the selected
assembly needs. Steel and the finished work are committed together when the
time is up: nothing is consumed by an assembly that does not happen, and with
too little of either input, or no power, the machine does nothing. The
depleted uranium of one assembly can also be more than a stack (167 for 20 %),
so what the slot cannot hold waits and moves into it as it is emptied; the
machine stops if it would hold more than 512. Breaking the block drops
everything it holds (the uranium it had taken in comes back as ingots).

The item route waits while a chemical batch is part built and the chemical
route waits while an item assembly is made; they share the output slot and the
energy buffer. A fabricator with nothing to do costs a boolean test a tick.

A fabricator placed before this change loads with empty item slots, on the
default target, with its chemical tanks, batch, energy and output exactly as
they were (`FuelFabricatorItemRouteTests`).

Depleted uranium has **no use in a reactor yet**. See the note at the end of
the CHANGELOG entry for why.

## Recipes: what is craftable

`python tools-audit-recipes.py` checks every recipe parses and every registered
item is the result of a recipe, except these, which are not craftable on
purpose:

| Item | Why |
|---|---|
| `cobalt_sample`, `tritium_sample`, `doped_silicon` | Products of irradiating a target in a reactor |
| `corium` | Made by a core melting through its vessel |
| `depleted_uranium` | The fuel fabricator's byproduct |
| `rcic_turbine_pump`, `hpci_turbine_pump`, `suppression_pool_quencher` | Retired blocks kept so old worlds load (the Terry-style `rcic_twl` and `hpci_turbine` and the suppression pool steam inlet replaced them); `tools-audit-assets.py` fails any recipe that produces or uses them |

## Specialty rods

Remove the vessel head and open refuelling. Hold the desired item, select an
empty position and press **LOAD**. **UNLOAD** returns that exact cassette and
its irradiation history. **MARK / SWAP** moves it alongside fuel bundles.
Specialty positions appear purple in both core maps and show their progress.
The loaded fuel count excludes them; they contribute no fission heat, heavy
metal inventory or fuel burnup.

| Rod | Effect |
|---|---|
| Boron carbide absorber | Fixed neutron absorption |
| Hafnium absorber | A second fixed absorber option |
| Californium-252 source | Adds a small startup neutron source |
| Americium-beryllium source | Adds a weaker startup neutron source |
| Antimony-beryllium source | Builds up source strength while irradiated |
| Cobalt target | Produces one sealed cobalt-60 sample |
| Tritium target | Produces 1,250 sealed tritium samples per completed rod; experimental BWR option |
| Silicon target | Produces one neutron-doped silicon item |

Absorbers add local absorption to the spatial solve and a geometry-weighted
fixed absorption term to the whole-core balance. Source rods add to the
existing background source; they do not replace it. These first-version
sources retain their strength while stored; radioactive decay is not simulated.
Fixed absorbers do not replace the movable control blades.

## Simple irradiation loop

1. Craft an empty irradiation rod from three iron ingots and one glass block.
2. Combine it with a cobalt target charge, lithium target charge or quartz to
   make a cobalt, tritium or silicon target. Cobalt charges use iron + lapis;
   lithium charges use clay + redstone. These are fictional Minecraft recipes.
3. Load the target into the reactor. Progress follows the local spatial flux
   and fission power. Decay heat does not process targets; unloaded items and
   unloaded reactors gain no offline progress.
4. Remove a completed target and **use it in your hand**. Cobalt and silicon
   yield one product; tritium yields 1,250 sealed samples. Each harvest returns
   one empty rod and consumes the completed cassette. A full inventory drops
   the result instead of deleting it. Large harvests split into normal stacks
   of at most 64, including any dropped overflow.

At local flux equal to the rated core average:

| Insert | Required operating exposure |
|---|---|
| Antimony-beryllium secondary source | 48 hours to full source strength |
| Silicon target | 24 hours |
| Cobalt target | 72 hours |
| Tritium target | 168 hours / 7 days |

These are **real running hours at 20 TPS**, not Minecraft day/night cycles or
offline elapsed time. They are gameplay settings matched to long fuel cycles,
not real irradiation schedules. At half the reference local fission flux, a
batch takes twice as long. Position and reactor power change the rate; decay
heat alone does not advance it. Exposure survives shuffling, save/reload and
controller removal, and completed targets stop accumulating progress.

Existing alpha.5 rods retain their accumulated exposure seconds; their shown
percentage is recalculated against the longer threshold. An unharvested rod
that met the old short threshold therefore needs more exposure. Already
harvested samples remain usable.

Sample uses: silicon + iron makes a repeater; cobalt sample + redstone + quartz
makes a comparator; tritium sample + glass makes a sea lantern.

### Bulk Mekanism tritium

With Mekanism **and Mekanism Generators** installed, process a sealed tritium
sample in a powered **Chemical Oxidizer** to produce **10,000 mB of
`mekanismgenerators:tritium`**. Pipe the
chemical into Mekanism storage or its fusion-fuel system. One completed rod
provides 1,250 samples, or **12,500,000 mB / 12,500 buckets**. This fills
20 inventory slots (19 full stacks and one stack of 34), plus the empty casing.
This is a game-balance yield, not a physical conversion from rod mass.

For a fusion system consuming 2.5 buckets of D-T fuel per second with a 1:1
deuterium/tritium input ratio, the tritium requirement is 756,000 buckets per
seven days. A 764-position core with 64 targets leaves 700 fuel positions;
one completed batch yields 800,000 buckets of tritium, about 5.8% above that
requirement. This assumes all targets receive rated local flux for seven
operating days and deuterium is supplied separately. Lower flux and refuelling
downtime require more targets or stored supply. Harvesting is a batch process,
not continuous export.

The alpha.7 increase applies when a completed rod is harvested, including rods
already loaded or stored. Previously harvested sample items still yield
10,000 mB each; they are not retroactively multiplied. Exposure is unchanged.

Each sample fits the pinned Chemical Oxidizer's 10,000 mB output tank. Empty
or pipe out that tank between operations. Harvesting is one-shot, and the
oxidizer consumes one sample per operation. Cobalt and silicon do not have
Mekanism chemical outputs. The conversion recipe is disabled if either optional
mod is absent; the rods, samples and ordinary crafting uses remain available.
Generators owns the [tritium chemical registration](https://github.com/mekanism/Mekanism/blob/1.21.x/src/generators/java/mekanism/generators/common/registries/GeneratorsChemicals.java).

Other rod recipes combine an empty casing with: borate charge (boron),
netherite scrap (hafnium), nether star (californium), echo shard (Am-Be), or
glowstone dust (Sb-Be). These recipes represent gameplay costs only.

## Reference basis

- The NRC describes natural uranium at about 0.7% U-235 and traditional power
  reactor fuel at roughly 3–5%. Consequently 3.5% is still **LEU**, even when
  it is a relatively rich BWR loading. [NRC uranium enrichment](https://www.nrc.gov/facilities-safety/fuel-cycle-facilities/uranium-enrichment).
- HALEU is above 5% and below 20%; 20% is the HEU boundary. The high-end
  options are labeled experimental. [NRC HALEU](https://www.nrc.gov/materials/new-fuels/haleu).
- The IAEA's comparison distinguishes uranium enrichment, total Pu content
  of MOX, and burnable absorbers. Our simplified effective fissile value must
  not be interpreted as a commercial MOX assay. [IAEA fuel information sheet](https://www.iaea.org/sites/default/files/24/11/evt2304628_information_sheet.pdf).
- Thorium-based fuel has historical BWR test experience, including Elk River
  and Lingen. Our three variants retain the existing U-233-driver model.
  [IAEA thorium fuel experience](https://conferences.iaea.org/event/146/contributions/5192/).
- Cf-252 primary sources and Sb-Be secondary sources are real reactor source
  categories. [QSA Global source catalogue](https://www.qsa-global.com/nuclear-neutron-sources).
- GE reported cobalt-60 production at Clinton in 2011, providing a BWR basis
  for the cobalt target idea. [GE Hitachi announcement](https://www.ge.com/news/press-releases/ge-hitachi-nuclear-energy-and-exelon-announce-potential-production-critical-medical).
- The documented TPBAR design is for PWRs, not ordinary BWR fuel. Our tritium
  target is explicitly a gameplay adaptation. [PNNL TPBAR description](https://www.pnnl.gov/publications/description-tritium-producing-burnable-absorber-rod-commercial-light-water-reactor).

Install alpha.7 on clients and server together so exposure thresholds and
tooltips agree. GUI protocol remains 8; saved original fuel items and core
inventories remain compatible.
