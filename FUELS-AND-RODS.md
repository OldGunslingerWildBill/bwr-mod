# Fuel and specialty rods — alpha.21

Open **Realistic BWR: Fuel & Rods** in the creative inventory. The machine tab
now contains plant hardware; this tab contains 18 selectable fuel grades, eight specialty
rod cassettes, empty irradiation rods, target charges and samples, and the three items of the fuel cycle
(plutonium nuggets, plutonium ingots and fission-product waste). All icons
are small PNGs rendered from the Blender studio in `art/models/fuel_icons/`.
Items still represent one logical core position, not a placeable fuel block.

## Fuel catalogue

| Family | Available grades | Meaning |
|---|---|---|
| Uranium | 0.711%, intermediate 2.70%, high BWR 3.50%, 4.95%, 8.00%, 19.75%, 20.00% | U-235 weight fraction in the simplified fuel model |
| Depleted uranium | 0.25% | A blanket that absorbs when fresh and breeds plutonium over burnup |
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
gadolinia variant is available from the creative tab or datapack recipes. The
thorium grades are made by the fabricator's item route from the mod's own
thorium and natural uranium (see "Thorium targets" below and
[THORIUM.md](THORIUM.md)); there is still no thorium reprocessing chain, and the
reprocessing plant still refuses thorium assemblies. Existing fuel
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

**Crafting the machine.** With Mekanism it is the recipe from Mekanism parts
(`fuel_fabricator`); without Mekanism a vanilla recipe (`fuel_fabricator_no_mekanism`,
gated on `neoforge:not` / `neoforge:mod_loaded`) takes its place: four steel or iron
ingots, a comparator, two redstone blocks, a blast furnace and a reactor vessel. Exactly
one of the two is loaded. Steel for the item route itself still has to come from a mod
that tags `c:ingots/steel`; vanilla has none.

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

By item tag, so any mod's items work. The mod's own ore (`bwr:uranium_ingot`, `bwr:raw_uranium`, see
[URANIUM-ORE.md](URANIUM-ORE.md)) carries the two uranium tags, so the item route needs no other mod for uranium; Mekanism's uranium
counts exactly the same:

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

Depleted uranium is a fuel type of its own, a blanket (see below).

### MOX targets: plutonium and depleted uranium in

Three more steps on the selector make **mixed oxide** from what the reprocessing plant hands back: plutonium
nuggets, depleted uranium ingots as the matrix, and steel, in; a fresh MOX assembly out and nothing returned (the
matrix is part of the product). The selector steps through the uranium ladder and then `MOX 4.50 %`, `MOX 7.00 %`
and `MOX 9.50 %`.

The plutonium fraction is the MOX fuel type's own `nominal_enrichment` (its effective fissile fraction, a datapack
number), counted as the plutonium's share of the four ingots of heavy metal, the same model as the uranium route and
as the plant. Plutonium is counted in nuggets (a ninth of an ingot) and rounded **up**, the matrix is the rest of the
four ingots rounded up to whole ingots, so, as on the uranium route, the machine can lose to rounding and never gain
(`dev.bwr.core.fuel.MoxMassBalance`, `MoxMassBalanceTest`).

| Selector step | Fuel type made | Plutonium fraction | Plutonium nuggets | Depleted uranium | Steel | Time | Energy |
|---|---|---|---|---|---|---|---|
| MOX 4.50 % (`mox_lean`) | `mox_lean` | 4.5 % of 36 nuggets = 1.62 | 2 | 4 | 4 | 5.6 s | 22,400 FE |
| MOX 7.00 % (`mox_standard`) | `mox` | 7 % = 2.52 | 3 | 4 | 4 | 5.6 s | 22,400 FE |
| MOX 9.50 % (`mox_rich`) | `mox_rich` | 9.5 % = 3.42 | 4 | 4 | 4 | 5.6 s | 22,400 FE |

An ingot of plutonium counts as nine nuggets. Time is 100 ticks plus 3 per matrix ingot, 200 FE a tick, as the
uranium route. The id of the standard grade is `mox_standard` and not `mox` on purpose: the fuel's name is not a
target (the uranium ladder and the saves keep their meaning), and a save naming one loads as the default.

**The slots.** The four slots a pipe sees are the same four, in the same order, and the sides are unchanged. On a MOX
step the slot that takes uranium takes plutonium and depleted uranium instead (and refuses uranium); on a uranium
step it refuses them. The two go into separate stores inside the machine, which are held up to twice what an
assembly needs (6 plutonium nuggets and 8 depleted uranium for the standard grade), and the slot only accepts as much
of an item as its store has room for, so a hopper that delivers one kind can never leave the slot holding the kind
the machine is not waiting for. Switching back to a uranium step keeps what was held (it is dropped with the block).
The screen's first caption reads `Pu, DU` on a MOX step, the cost line reads `Costs 3 plutonium, 4 depleted U, 4
steel`, and the item assembly card shows both stores against their cost. The CC peripheral adds
`plutoniumHeldNuggets` and `matrixHeldIngots` (`target` is the selector's id, for example `mox_standard`).

**Saves.** Two new keys, `PlutoniumNuggets` and `MatrixIngots`, are written only when they hold something; the target
is saved only when it is not the default, as before. An old fabricator loads exactly as it did.

### Thorium targets: thorium, natural uranium and steel in

Three more steps on the selector, after the MOX grades, make thorium fuel (`Th 2.00 %`, `Th 3.00 %` and `Th 5.00 %`). The
selector now has 13 steps: the first ten are the uranium ladder and the MOX grades exactly as before (`FuelItemRoute.LADDER`, same
indexes, saves and commands), then `thorium_lean`, `thorium_standard` and `thorium_rich` (`FuelItemRoute.SELECTOR`).
Thorium-232 is fertile, not fissile, so the assembly needs a **fissile driver** until uranium-233 breeds in. The driver here is
uranium enriched to 19.75 % (HALEU, the `uranium_1975` grade) and the machine makes it itself, by the uranium route's own mass
balance. The driver fraction is the thorium fuel type's `nominal_enrichment` (2, 3 and 5 % of the heavy metal, unchanged), and
`dev.bwr.core.fuel.ThoriumMassBalance` is the only place the arithmetic lives (unit test `ThoriumMassBalanceTest`):

    driver product  D = 4 x f / 0.1975                      ingots of 19.75 % uranium
    natural uranium   = D x (0.1975 - 0.0025) / (0.00711 - 0.0025)    rounded up
    depleted uranium  = natural uranium - D                  rounded down
    thorium           = 4 - D                                rounded up to whole ingots

| Selector step | Fuel type made | Driver | Driver uranium (19.75 %) | Natural uranium | Thorium | Steel | Depleted uranium back | Time | Energy |
|---|---|---|---|---|---|---|---|---|---|
| Th 2.00 % | `thorium_lean` | 2 % | 0.405 ingots | 18 | 4 | 4 | 16 | 154 ticks (7.7 s) | 30,800 FE |
| Th 3.00 % | `thorium` | 3 % | 0.608 ingots | 26 | 4 | 4 | 25 | 178 ticks (8.9 s) | 35,600 FE |
| Th 5.00 % | `thorium_rich` | 5 % | 1.013 ingots | 43 | 3 | 4 | 41 | 229 ticks (11.5 s) | 45,800 FE |

Like the other routes it can lose to rounding and never gain: the uranium and the thorium are rounded up, the depleted uranium
down. Thorium is monoisotopic Th-232, so there is no thorium enrichment, only the driver. Time is 100 ticks plus 3 per natural
uranium ingot, 200 FE a tick. The id of the standard grade is `thorium_standard` and not `thorium` on purpose (the fuel's name
is not a target, as with `mox`).

**What counts as thorium:** `c:ingots/thorium` and `c:raw_materials/thorium` (raw thorium smelts to one ingot), one each.
**The slots** are the same four, in the same order, with the same sides. On a thorium step the slot that takes uranium takes
**uranium and thorium**, each into its own store (held up to twice what an assembly needs, so the slot is not left holding one
when the other is wanted; the slot takes only what the stores have room for and refuses everything else, plutonium and depleted
uranium included). On the uranium and MOX steps it refuses thorium. The screen's caption reads `U, Th`, the cost line `Costs 26
thorium, ...`, the item assembly card shows both stores. Breaking the block drops the thorium as ingots. **Saves:** one new key,
`ThoriumIngots`, written only when it holds something; an old fabricator loads exactly as it did. The CC peripheral adds
`thoriumHeldIngots`.

## Recipes: what is craftable

`python tools-audit-recipes.py` checks every recipe parses and every registered
item is the result of a recipe, except these, which are not craftable on
purpose:

| Item | Why |
|---|---|
| `cobalt_sample`, `tritium_sample`, `doped_silicon` | Products of irradiating a target in a reactor |
| `corium` | Made by a core melting through its vessel |
| `depleted_uranium` | The fuel fabricator's byproduct |
| `thorium_ore`, `deepslate_thorium_ore`, `monazite_sand` | Found in the ground, as the uranium ores are (THORIUM.md) |
| `rare_earth_concentrate`, `radium` | Byproducts of mining thorium ore (THORIUM.md); made by the mining, not at a bench |
| `fission_product_waste` | What burning fuel leaves, the reprocessing plant's byproduct: nothing uses it, and a recipe would make waste out of nothing |
| `rcic_turbine_pump`, `hpci_turbine_pump`, `suppression_pool_quencher` | Retired blocks kept so old worlds load (the Terry-style `rcic_twl` and `hpci_turbine` and the suppression pool steam inlet replaced them); `tools-audit-assets.py` fails any recipe that produces or uses them |

## Depleted uranium as a fuel (blanket)

`depleted_uranium` (`data/bwr/fuel_type/depleted_uranium.json`, also a compiled
preset) is the fuel type of the uranium the fabricator's item route returns. It
is a **blanket**: loaded among fuel it absorbs neutrons while it is fresh, and
U-238 capture breeds plutonium into it as it burns, so its k-infinity climbs
with burnup. It never becomes a fuel in its own right. It is in the Fuel & Rods
creative tab like the other grades.

**Making one.** Craft it: four depleted uranium ingots (`c:ingots/depleted_uranium`)
round four steel ingots (`c:ingots/steel`), in a ring (steel at the corners):

    S D S
    D . D
    S D S

The result is a fresh 180 kg depleted uranium assembly, the same data as the
creative tab's. Only the steel tag needs a mod behind it (Mekanism's steel is in
it); the machine's item route does not make it, because it has no target for
the tails.

| Field | Value | Why |
|---|---|---|
| `nominal_enrichment` | 0.0025 | The tails assay of the fabricator's mass balance (0.25 % U-235) |
| `k_inf_base` | 0.262 | Not tuned by hand: natural uranium's 0.5802 at 0.711 % carried down to 0.25 % along the mod's own enrichment-utilisation curve (`FuelAssembly.baseKInf`). Fresh it is far below 1, so it is a net absorber, and well below natural uranium, which cannot sustain a chain either |
| `conversion_gain_k_inf` | 0.036 | Thorium's 0.1 scaled by the thermal capture ratio of the fertile isotope, U-238 2.7 b against Th-232 7.4 b (0.37). The same mechanism and the same 20,000 MWd/t saturation as thorium (`FuelAssembly.burnupPenaltyKInf`) |
| `depletion_rate` | 5e-7 per MWd/t | LEU's 6.5e-6 scaled by fissile content (0.25 % against 3.5 %): there is almost no U-235 to burn. It has to be below the initial breeding slope (0.036 / 20,000 = 1.8e-6) for k-infinity to rise |
| `beta` | 0.006502 | Like natural uranium, as asked. (Real U-238 fast fission has a larger delayed fraction, so this is the conservative side for control margin) |
| `prompt_lifetime_seconds`, `doppler_coeff_per_c`, `heat_per_fission_mev` | 4e-5, -1.6e-5, 202.5 | Like natural uranium |
| `gad_content` | 0 | No burnable poison: there is no excess reactivity to hold down |

Resulting k-infinity: 0.262 fresh, 0.271 at 10 GWd/t, 0.275 at 20 GWd/t, peaking
near 0.275 around 25 GWd/t and then falling as the little U-235 is used. Twenty
in the middle of the default core lower k_eff(all rods out) from 1.0864 to
1.0858: a blanket costs reactivity in proportion to how much of the core it
replaces (`DepletedUraniumTest`, `DepletedUraniumTests`). These are model values in the
mod's calibrated style, not a depletion calculation; a datapack may retune them.

## Reprocessing plant: closing the cycle

The **Reprocessing Plant** (machine tab, styled like the fabricator) takes a **spent** fuel assembly apart: one with
burnup above zero, from a reactor, a burned depleted uranium blanket or a burned MOX assembly. A fresh assembly is
refused (at the slot, from a pipe, in the screen), and so is a thorium assembly (there is no plutonium chain in
thorium fuel, and returning its U-233 as an uranium item would mislabel it). It makes three things:

| Output | Item | Unit | What it is |
|---|---|---|---|
| Plutonium | `bwr:plutonium_nugget` (nine make a `bwr:plutonium_ingot`, crafted both ways) | a ninth of an ingot | The plutonium left in the assembly, bred or the MOX's own that survived. Tags `c:nuggets/plutonium`, `c:ingots/plutonium`. The fabricator's MOX route takes it. |
| Recovered uranium | `bwr:depleted_uranium` | an ingot | The uranium that is left, whatever its enrichment (see below). |
| Fission products | `bwr:fission_product_waste` | a ninth of an ingot | The heavy metal that was fissioned. Nothing uses it and nothing crafts it (it is in `tools-audit-recipes.py`'s exception list with that reason). |

**Plutonium is counted in nuggets, not ingots,** because a spent assembly holds a fraction of one nugget of it (about
a quarter at 45 GWd/t). The plant keeps every product as a fraction in the block (a fixed-point count of grains, a
millionth of a nugget, saved as longs) and releases a whole item only when it holds one; the rest carries into the next
assembly, and nothing rounds into or out of existence: the three amounts of one assembly always add up to its four
ingots of heavy metal to the grain, and so do the items out plus what the plant holds, across any number of assemblies
(`ReprocessingTests.fractionsOfPlutoniumAreConservedAcrossManyAssemblies`). Breaking the plant drops the whole items it
holds; only the fraction of an item is lost with the block.

### Recovered uranium is depleted uranium (and why)

Reprocessed uranium is close to tails assay, so it comes back as `bwr:depleted_uranium`. Real reprocessed uranium keeps
rather more U-235 than the 0.25 % tails of the fabricator's mass balance (spent 3.5 % fuel still holds about 0.8 to 1
%), so putting it all in the tails item **throws that surplus away**: it can only lose, never gain, which keeps the
cycle from minting fissile, keeps one uranium item in the mod, and sends the recovered uranium where depleted
uranium already goes (blanket assemblies and the MOX matrix). It is deliberately not tagged as a uranium ingot, so the
fabricator will not take it back as feed.

### The model, and where its numbers come from

One assembly is **4 ingot-units of heavy metal** (36 nuggets), as in the fabricator; the assembly's actual
`heavy_metal_kg` does not enter (every fabricated assembly is 180 kg). With `B` the burnup in MWd/t, `e` the
assembly's enrichment, and everything a fraction of the heavy metal (`dev.bwr.core.fuel.SpentFuel`):

1. **Fission products** `phi = G * B`. This is exact bookkeeping: the heavy metal that was fissioned becomes fission
   products. `G` comes from the fuel's own `heat_per_fission_mev` (energy per MWd over energy per fission, times the
   mass of 235 u): 1.039e-6 per MWd/t at 202.5 MeV, about 1.04 g of heavy metal a MWd (`HeatPerFissionTest` is the
   same energy basis).
2. **Fissile left** `f` = `FuelAssembly.fissileEquivalentWeightFraction()`: the enrichment at which a fresh assembly
   would have the k-infinity this one has now less the burnable poison, that is `baseKInf - burnupPenaltyKInf`, read
   back through the inverse of the mod's own thermal-utilisation curve. So **fissile consumption is the fuel's
   `depletion_rate`, and breeding is its `conversion_gain_k_inf`**, through the same enrichment response the core uses
   (a fresh assembly gives back exactly its enrichment; a blanket gives back more than it started with).
3. **Plutonium bred** `b = phi + f - e`: every fission destroys a fissile atom, so what was destroyed equals what
   was there plus what was bred less what is left. The k-infinity curve flattens at high enrichment, where it cannot
   see captures, so `b` is never below `0.15 * (1 - e) * phi` (`SpentFuel.MIN_CONVERSION`, the low end of what real
   light-water lattices convert) and a 20 % assembly still makes a little.
4. **Plutonium left** `p = (e_Pu + b) * f / (e + b)`, where `e_Pu` is `e` for a fuel that starts as plutonium (the
   names `mox*` and `plutonium*`) and 0 for a uranium fuel: the U-235 and the plutonium burn at the same relative
   rate, so the same fraction of each survives. (A datapack fuel with another name counts as a uranium fuel.)
5. **Uranium left** `1 - phi - p`.

The numbers it gives are in the range of real spent fuel (spent LWR fuel holds about 1 % plutonium, of which a bit
over 0.6 % is fissile; this model counts fissile plutonium) and are not an isotopic depletion calculation: one
constant (`MIN_CONVERSION`) and the choice of 235 u for the fissioned mass are not from the mod's data, everything
else is. Retuning a fuel in a datapack retunes what its spent assemblies hold.

| Spent assembly | Burnup GWd/t | Plutonium nuggets | Depleted uranium ingots | Waste items | Plutonium of the heavy metal |
|---|---|---|---|---|---|
| LEU 3.50 % | 20 | 0.202 | 3.894 | 0.748 | 0.56 % |
| LEU 3.50 % | 29 | 0.243 | 3.852 | 1.085 | 0.68 % |
| LEU 3.50 % | 45 | 0.265 | 3.783 | 1.683 | 0.74 % |
| LEU 3.50 % | 60 | 0.253 | 3.722 | 2.245 | 0.70 % |
| 4.95 % | 55 | 0.227 | 3.746 | 2.058 | 0.63 % |
| HEU 20 % | 40 | 0.092 | 3.824 | 1.496 | 0.25 % |
| Natural uranium | 7 | 0.108 | 3.959 | 0.262 | 0.30 % |
| Natural uranium | 10 | 0.121 | 3.945 | 0.374 | 0.34 % |
| Depleted uranium blanket | 5 | 0.063 | 3.972 | 0.187 | 0.17 % |
| Depleted uranium blanket | 10 | 0.076 | 3.950 | 0.374 | 0.21 % |
| Depleted uranium blanket | 20 | 0.085 | 3.907 | 0.748 | 0.24 % |
| Depleted uranium blanket | 30 | 0.088 | 3.865 | 1.122 | 0.25 % |
| MOX 7 % (recycled) | 45 | 0.998 | 3.705 | 1.655 | 2.77 % |
| Plutonium 6 % (recycled) | 45 | 1.016 | 3.708 | 1.612 | 2.82 % |

Per assembly of four ingots: the three columns add up to 4 ingots (36 nuggets: the plutonium and waste columns are in
nuggets, the uranium in ingots). `SpentFuelTest` pins ten of these rows grain for grain against a separate
implementation of the model, and `ReprocessingTests` pins six through the real block entity.

What that means in play: the plant does not hand plutonium out cheaply. One standard MOX assembly needs 3 nuggets,
which is the plutonium of about 11 spent LEU assemblies at 45 GWd/t, or of 35 depleted uranium blankets at 20 GWd/t
(the blanket makes plutonium, but little of it stays: the mod's breeding gain is small and its bred plutonium is burned
as it is made). Spent MOX gives back about a third of what went in plus what it bred, so a MOX loop slowly loses
plutonium, as it does in life. These are model values a datapack may retune.

### How the plant works

- **Four slots**, through the item capability, in the fabricator's way: spent assembly in (slot 0), plutonium nuggets,
  depleted uranium and waste out (1, 2, 3). A hopper above sees only the input, a hopper below only the outputs, the four
  sides all four; the input cannot be pulled back out by a pipe, the outputs cannot be filled, and the input slot takes
  only an irradiated, non-thorium assembly.
- **Too hot to start.** A spent assembly is accepted into the slot, but the plant does not start on one whose decay heat is
  above `reprocessingMaxDecayHeatKw` (`bwr-gameplay.toml`, default 50 kW per assembly, 0 turns it off). It looks again
  once a second and starts by itself when the assembly has cooled; an assembly it has started is never stopped. The heat
  is worked out from the assembly's burnup and the game tick it was unloaded from a core (see the next section), so
  it keeps falling while the assembly is a chest item, and an assembly with no recorded tick is long-cooled and goes
  straight in. A 45 GWd/t assembly passes 50 kW after about two hours. The screen's first line reads `Too hot: 83 kW (limit 50)`, the
  status text says so, and the peripheral adds `assemblyDecayHeatKW`, `tooHot`, `secondsUntilCool` and `decayHeatLimitKW`.
- **Power and time:** 200 ticks and 150 FE a tick, 30,000 FE an assembly, from a 100,000 FE buffer (it takes energy
  and gives none back). The assembly stays in the slot while it works and is consumed when the time is up; taking it
  out first costs nothing. With too little energy it works only as far as the energy goes.
- **Outputs that do not fit** (a slot holds 64) wait in the plant, and it does not start another assembly while a
  product has a full stack waiting; it goes on as the slots are emptied. An idle plant costs a boolean test a tick.
- **Saves.** `Energy` always; `WorkTicks`, `PlutoniumGrains`, `UraniumGrains`, `WasteGrains` and each slot
  (`Input`, `PlutoniumOut`, `UraniumOut`, `WasteOut`) only when they hold something.
- **Screen.** The spent assembly slot and the three product slots on the left, what the assembly in the slot will
  yield (worked out on the client from the item, through the same arithmetic), what the plant holds in fractions,
  progress and stored energy on the right. A fresh assembly that is somehow in the slot reads `This assembly is
  refused`.
- **CC peripheral** `bwr_reprocessing_plant`, `getStatus()`: `energyFE`, `working`, `progress`, `hasAssembly`, and with
  an assembly `assemblyFuel`, `assemblyBurnupMwdPerTonne` and `accepted`; `plutoniumHeldNuggets`,
  `depletedUraniumHeldIngots` and `wasteHeld` (fractions held), `plutoniumOut`, `depletedUraniumOut`, `wasteOut`
  (the slots' counts).
- **Recipe, ungated:** a reactor vessel between two obsidian on top, a blast furnace between two hoppers in the middle
  and iron ingots (`c:ingots/iron`) along the bottom (`OVO` / `HFH` / `III`); it needs only vanilla items and the mod's own
  reactor vessel, so it does not depend on Mekanism (the fabricator's recipe does). Nine plutonium nuggets craft an
  ingot and an ingot crafts nine.

## The fuel cycle, end to end

| Step | Machine | What happens to the assembly |
|---|---|---|
| 1. Make | fuel fabricator | uranium and steel (or plutonium and depleted uranium for MOX) in; a fresh assembly out |
| 2. Burn | reactor | burnup builds up on the item; unloading stamps the game tick it left the core (`bwr:discharge_time`) |
| 3. **Cool** | **spent fuel pool** ([SPENT-FUEL-POOL.md](SPENT-FUEL-POOL.md)) | racked under water; its decay heat falls on the reactor's own curve (315 kW at discharge for a 45 GWd/t assembly, 63 kW after an hour, 23 kW after a day); the player has to keep the pool cooled and topped up, or it boils and uncovers the fuel |
| 4. Take apart | reprocessing plant | refuses to start above 50 kW (configurable); plutonium, depleted uranium and waste out |
| 5. Remake | fuel fabricator, MOX route | plutonium and depleted uranium make MOX; back to 2 |

The pool is the reason the cycle has a pause in it: step 4 will not accept fresh discharges, and a fresh discharge left to itself in
air runs away. Nothing automates any step; the player carries the assemblies (or pipes them) and runs the cooling.

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
