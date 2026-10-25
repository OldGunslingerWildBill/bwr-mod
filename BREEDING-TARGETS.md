# Reactor-bred isotopes

Six more irradiation targets turn elements that lie with uranium and thorium ore into the isotopes the plant can use: **polonium-210**,
**uranium-233**, **neptunium-237**, **plutonium-238**, **actinium-227** and **thulium-170**. They use the target rods the mod already had
(cobalt, lithium, silicon: [FUELS-AND-RODS.md](FUELS-AND-RODS.md)), the same casing and the same way of working: put the rod in a
refuelling position, run the core, take the finished rod out, use it in your hand. What a rod makes is worked out from neutron
cross sections, in `dev.bwr.core.fuel.BreedingTargets`, not picked.

Nothing here acts on the plant. A target takes flux and makes nothing until a player harvests it; nothing is produced for the time a
chunk is unloaded; decay heat does not advance a target (the existing rule).

## The products at a glance

| Target rod | Made from | Rated time | Product, one per finished rod | Grams | Heat (W) | Half-life | Use |
|---|---|---|---|---|---|---|---|
| Bismuth | casing + 2 `bismuth_nugget` (10 kg Bi-209) | 96 h | `polonium_210_ingot` | 0.082 | 11.5 | 138.4 d | RTG fuel; about no gamma |
| Thorium | casing + 1 thorium ingot (45 kg Th-232) | 168 h | `uranium_233_ingot` | 480 | none (fuel) | 159,200 y | thorium fuel driver |
| Depleted uranium | casing + 1 depleted uranium (45 kg, 0.25 % U-235) | 240 h | `neptunium_237_ingot` | 0.20 | none | 2.14 My | target of the next rod |
| Neptunium | casing + 8 `neptunium_237_ingot` (1.6 g) | 120 h | `plutonium_238_ingot` | 0.28 | 0.16 | 87.7 y | RTG fuel |
| Radium | casing + 8 `radium` (80 mg Ra-226) | 168 h | `actinium_227_ingot` | 0.00103 | 0.015 | 21.77 y | RTG fuel; daughters are strong gamma |
| Thulium | casing + 8 `rare_earth_concentrate` (500 g Tm-169) | 48 h | `thulium_170_ingot` | 19.0 | 218 | 128.6 d | RTG fuel; bremsstrahlung |

The four heat sources come as an **ingot** and a **block of nine** (`<isotope>_ingot`, `<isotope>_block`, nine to one both ways), each with an entry in
the RTG fuel data map `bwr:rtg_fuel` ([RTG.md](RTG.md)), the block's mass nine times the ingot's. An RTG takes either in a well. A block is a block you can
place; it is nothing else (no machine, no ticker). `uranium_233_ingot` and `neptunium_237_ingot` are not RTG fuel and have no entry. `bismuth_nugget`
(5 kg of bismuth) is what uranium ore gives (below).

## The calibration: what 72 hours means

The cobalt target has always been 72 running hours "matched to long fuel cycles". Here that is given a meaning, and everything else is scaled from it:

* **72 game hours stand for one 18-month BWR cycle** (13,140 h at rated flux), so **one game hour is 182.5 hours of neutron exposure** (`FLUENCE_HOURS_PER_GAME_HOUR`).
* **Thermal flux 3e13 n/cm2/s** in a cassette position (a typical BWR in-core thermal flux; a design choice, not a measurement).
* At those two numbers a cassette of 10 kg of cobalt-59 (sigma 37.2 b) is **4.7 % converted: 474 g of cobalt-60, about 0.5 MCi**, the size of an industrial source batch. The old
  numbers were not changed; they now have a reading.

A target's time is a gameplay choice (a ladder from 2 to 10 days), not the straight ratio of cross sections. The straight ratio to cobalt would be 1,100 times 72 h for bismuth, which no
player would run. Instead **the cross section shows up as the yield**: the same flux and time make a thousandth of the polonium that they make of cobalt-60, and the table's grams are what the
chain below gives at the rod's rated time.

## The chain physics

Each target is a chain of nuclides with a thermal capture (rate sigma x flux) or a decay feeding the next member. Every member is removed by its own decay and by burning (fission or capture it
does not pass on), so a long run **saturates**. The inventory obeys `dN/dt = A N`, solved with a matrix exponential (`BreedingTargets.inventoryGrams`); the core tests check it against the hand
formula, that the bismuth chain loses only the atoms polonium's own decay takes (2e-5 of them), and that nothing exceeds its starting atoms.

| Route | Chain and its numbers (thermal) | Result at the rated time |
|---|---|---|
| Bi-209 to Po-210 | Bi-209 (n,g) 16.2 mb to Bi-210 (5.01 d) to Po-210 (138.4 d) | 82 mg (8.4e-6 of the bismuth): saturated, twice the time adds 3 % |
| Th-232 to U-233 | Th-232 7.37 b to Th-233 (22 min) to Pa-233 (26.98 d; loses 40 b) to U-233 (burns 576 b) | 478 g of 45 kg (1.06 %): the burning of U-233 is why it saturates at about 1.3 % |
| U-235 to Np-237 | U-235 capture 98.3 b (fission 585 b) to U-236 (5.1 b) to U-237 (6.75 d) to Np-237 (burns 176 b) | 0.20 g from 45 kg (0.25 % U-235) |
| Np-237 to Pu-238 | Np-237 175.9 b to Np-238 (2.1 d; fission 2,100 b) to Pu-238 (burns 560 b) | 0.282 g of 1.6 g (17.6 %); past 120 h the Pu-238 burns away |
| Ra-226 to Ac-227 | Ra-226 12.8 b to Ra-227 (42.2 min) to Ac-227 (21.77 y; burns 890 b) | 1.03 mg of 80 mg (1.3 %) |
| Tm-169 to Tm-170 | Tm-169 100.2 b to Tm-170 (128.6 d; burns about 100 b) | 19.0 g of 500 g (3.8 %), 86 % of saturation |

What the model leaves out, each worth tens of percent and none an order of magnitude: resonance captures (only thermal cross sections, so the yields are a little low), self-shielding of a
thick target (a little high), the neutron spectrum (a fast-flux route to U-237 would add a little neptunium), and the plutonium-239 that a uranium target also breeds (it stays in the rod; the
mod has no way to reprocess a target). The cross sections of U-236 (5.1 b), Np-238 fission (2,100 b), Pu-238 (560 b), Co-60 (2 b) and Tm-170 (100 b) are standard evaluated values quoted from memory and
were **not** opened in the research: check them before relying on the last digit.

The grams in the data map are the table's, rounded to two or three figures, so one finished rod makes exactly one ingot. A GameTest (`theFuelListMassesAreTheCoresPhysics`) and a core test
(`test07_oneCassetteMakesOneIngot`) keep each within 3 % of the physics.

## Each route, and what was dropped

### Polonium-210 (kept)

Bi-209 captures a neutron to Bi-210, which beta-decays with a 5.01-day half-life to Po-210, an alpha emitter with a 138.4-day half-life that decays to stable Pb-206. About one decay in 100,000 emits a
gamma ray, so it is a very low-gamma source; it was the heat source of the Lunokhod rovers. The honest cross section is **16.2 mb** to the 5-day ground state (the 33.8 mb figure in tables is the
total, and the rest goes to the long-lived isomer Bi-210m, which makes thallium-206, not polonium), so the yield is small: real Russian production is grams a month from tonnes of bismuth at very
high flux. Bismuth occurs with pitchblende in the Erzgebirge Bi-Co-Ni-Ag-U ("five-element") veins, which is why uranium ore drops it.

* Specific power 140 W/g (Po-210 alone). A 0.082 g ingot is 11.5 W of heat; six in an RTG make 69 W thermal, 3.4 W electric, 0.26 FE/t.
* **Dose** `gamma_w_per_g` 0.0002 W/g (the 803 keV line at 1e-5 per decay, 1.66e14 Bq/g): a carried ingot reads 0.014 mSv/h at a metre, a block 0.13. The point of polonium here is that it is nearly harmless to carry; its danger (ingested or inhaled) is not modelled.
* **Decay.** At 138 days a polonium RTG loses half its heat in a world-time season: the RTG's clock does this (the existing rule: world time, not play time).

### Uranium-233 (kept)

Th-232 captures a neutron to Th-233 (22 min), which beta-decays to Pa-233 (27 days), which beta-decays to fissile U-233. In the mod's thorium fuel this happens inside the assembly (unchanged). The target
makes it outside: one thorium ingot (45 kg) in a rod, 168 h, gives 478 g (the ingot is 480 g). **It is fissile fuel, not RTG fuel**, so there is no RTG entry and no block.

**In the thorium fuel route.** A U-233 ingot can stand in for the enriched-uranium driver (`FuelFabricatorBlockEntity`, `FuelItemRoute.u233Cost`, `ThoriumMassBalance.u233Ingots`). The driver is the
fuel type's fraction of the assembly's 180 kg of heavy metal, in whole 480 g ingots, rounded up (the machine never gains):

| Grade | Driver | U-233 | Thorium | Steel | Back |
|---|---|---|---|---|---|
| `thorium_lean` | 2 % = 3.6 kg | 8 | 4 | 4 | nothing |
| `thorium` (standard) | 3 % = 5.4 kg | 12 | 4 | 4 | nothing |
| `thorium_rich` | 5 % = 9 kg | 19 | 4 | 4 | nothing |

It is an alternative inside the existing thorium targets: no selector step was added (that would change the selector's 13 steps), and the existing costs (natural uranium 18, 26, 43; depleted uranium back) are
untouched. The thorium input slot also takes U-233 (never more than two assemblies' worth, so it cannot jam), kept in its own store (`U233Ingots`, saved only when it holds some). The usual
route is tried first; the U-233 one runs when it cannot but the U-233 store has what it needs. Nothing enriches, so there is no depleted uranium. The screen's status line shows both costs.

### Neptunium-237 and plutonium-238 (kept; the honest route is slow)

Pu-238 (0.5674 W/g, 87.7 y) is the best RTG fuel there is and is made from Np-237. Np-237 has to come from somewhere real. The mod's reprocessing plant counts fission products, plutonium and uranium and has
no neptunium; changing its outputs would change its tests, so it was **not** touched. The honest alternative is the one that makes Np-237 in real reactors: U-235 captures a neutron to U-236, which
captures another to U-237 (6.75 d), which beta-decays to Np-237. A target of **depleted uranium** (0.25 % U-235, the fabricator's tails) is that chain at low yield: 0.20 g per 45 kg ingot in a
whole cycle. A neptunium target (eight ingots, 1.6 g) then gives 0.28 g of Pu-238 (about 17.6 %; HFIR converts 10 to 15 % of a neptunium target per cycle). The route works and the numbers are honest, and
**it is weak**: a Pu-238 ingot is 0.16 W, and a rod's worth needs eight depleted uranium rods first. Real plutonium-238 starts from kilograms of reprocessed neptunium, which this mod's reprocessing does not make.
The Np-237 ingot has no RTG entry (0.0002 W/g).

### Actinium-227 (kept; tiny and hot)

Ra-226 captures a neutron to Ra-227 (42.2 min), which beta-decays to Ac-227 (21.77 y). Ac-227 was studied as an RTG heat source; its daughters (Th-227 18.7 d, Ra-223, Rn-219, ...) bring it to about
**14.6 W/g** (this number is quoted from SCK CEN's page; the decay-energy sum of the chain, five alphas near 33 MeV and betas, gives 14.8 W/g from 72.3 Ci/g), and they carry strong gamma, so the entry has
`gamma_w_per_g` 0.13 W/g (about 0.3 MeV of photons per decay: derived by summing the main lines of Th-227, Ra-223, Rn-219, Pb-211 and Bi-211, which is an estimate to a factor of two). The yield per radium is
about 1.3 % (the reaction yield "about 2 % of the radium weight" in the literature), and radium is 10 mg an item and 1 to 2.5 % of an ore, so **a rod of 8 radium makes 1.03 mg of Ac-227: 15 mW**. A carried
ingot reads 0.12 mSv/h at a metre: 8 times a polonium ingot for one-eightieth of the mass. It is a real route and a poor one.

### Thulium-170 (kept)

Tm-169 is the only stable thulium isotope and captures 100 b. Tm-170 (128.6 d, 99.87 % beta to Yb-170) has been proposed as a low-cost heat source. Its specific power is **11.5 W/g** (derived: 2.21e14 Bq/g x
about 0.34 MeV mean beta energy; no source opened gives it directly). The 84 keV gamma and bremsstrahlung are what it shows outside: `gamma_w_per_g` 0.08, scaled from the mod's Sr-90 bremsstrahlung ratio
(0.6 %) and Tm-170's lower beta energy; it is uncertain by a factor of a few, so a carried 19 g ingot reads about 1.3 Sv/h at a metre, comparable with a strontium-90 capsule. Thulium stays inside
`rare_earth_concentrate`: **8 concentrates stand for 500 g of thulium**, which in life is tonnes of monazite; that is the gameplay price (the recipe stands for the whole separation).
A thulium ingot is the strongest heat source of the set (218 W) and decays in a season.

### Dropped

* **Polonium trace in the ore.** Po-210 is in uranium ore in secular equilibrium (Curie found it in pitchblende), but at about 0.1 mg per tonne of uranium, which is micrograms in an ore block: an item of it would
  be a thousand times too much, and a 138-day half-life means it would be gone from any stored item. It is left as a bred product only.
* **A reprocessing-plant route to neptunium.** Not done, as above.
* **Bismuth ingot and block.** Only a nugget: it is a target material, not a fuel.
* **Radium-228 / thorium-228 (the thorium series), americium to curium and others.** Real but not bred "from an element in the ore"; the existing separator already makes americium.

## Recipes

A casing and the ore element, shapeless, no extra charge item (silicon works the same: a casing and quartz). `irradiation_casing` is three iron ingots and glass, as before.

| Rod | Recipe |
|---|---|
| Bismuth | casing + 2 bismuth nuggets |
| Thorium | casing + 1 thorium ingot (`#c:ingots/thorium`) |
| Depleted uranium | casing + 1 depleted uranium (`#c:ingots/depleted_uranium`) |
| Neptunium | casing + 8 neptunium-237 ingots |
| Radium | casing + 8 radium |
| Thulium | casing + 8 rare earth concentrate |

The ingots and blocks of the four heat sources craft nine-and-one both ways (the usual metal block recipe). The rod is consumed when harvested; the casing comes back (the existing rule), and the element
in it is spent: the cassette has no residue the mod could reprocess.

## Ore: bismuth

Uranium ore drops a **bismuth nugget** by chance, as lead, radium and copper do: 5 / 6.5 / 8 / 10 % for Fortune 0 / I / II / III, one or two nuggets, **skipped by Silk Touch**, the main drop untouched
(`data/bwr/loot_table/blocks/uranium_ore.json`, `deepslate_uranium_ore.json`; `UraniumOreTests.onlyListedByproducts` lists it). Real: bismuth is a companion of pitchblende in the Erzgebirge Bi-Co-Ni-Ag-U veins. The nugget has no
recipe; it is made only by mining.

## Radioactivity of what you carry

Each ingot and block with a fuel entry is a carried point source by grams x `gamma_w_per_g` x wall (RTG.md, "Carried capsules"). At a metre: polonium-210 ingot 0.014 mSv/h, plutonium-238 ingot
0.0025, actinium-227 ingot 0.12, thulium-170 ingot about 1,300 mSv/h; a block is nine times. Uranium-233 and neptunium-237 ingots and bismuth nuggets are not on the list and are as inert as the other
uranium items (the mod's uranium has no radiation).

## The guide chapter

The operator's guide has a chapter, **Breeding and decay** (`assets/bwr/guide/en_us/breeding-and-decay.json`), with a page per route and for the uranium and thorium decay series, marking each step as mine, core, plant or wait.

## What is tested

* Core, `BreedingTargetsTest`: the cobalt calibration; each kind a target that completes at its rated time and no later; yields never exceed the starting atoms; the bismuth chain loses only what polonium decays; polonium against
  the hand formula; saturation and monotonicity; one cassette makes one ingot (3 %); the heat of each ingot; the U-233 driver worked by hand (8, 12, 19).
* GameTests, namespace `bwrbreeding` (`BreedingTargetTests`, batch `breeding`; `-PbwrGameTestNamespaces=bwrbreeding`): each of the six targets irradiated in a real controller's core and harvested to its product;
  a save and reload; carried dose of every product; the fuel list's masses against the physics; the new fuels powering an RTG; the recipes; the U-233 fabricator route and its store; bismuth from uranium ore.

## References

Opened and read for this write-up; a figure marked "quoted from memory" above is not on this list.

* Po-210 (half-life, 140 W/g, gamma about 1 in 100,000, Lunokhod, Bi-210 5 d), <https://en.wikipedia.org/wiki/Polonium-210>
* Bi-209(n,g)Bi-210g thermal cross section 16.2 mb and resonance integral 89.8 mb, Al-Khasawneh et al., Phys. Rev. C 103, 065805 (2021), <https://arxiv.org/abs/2107.06477>; the Po-210 inventory spread between libraries,
  <https://oecd-nea.org/dbdata/hprl/hprlview.pl?ID=535>
* Thermal absorption cross sections of Co (37.18 b), Th (7.37 b), Bi (0.0338 b), Tm (100 b), Ra-226 (12.8 b), Np (175.9 b), U-235 (680.9 b), U-238 (2.68 b), Li-6, Si-30: NIST neutron scattering lengths and cross sections, <https://www.ncnr.nist.gov/resources/n-lengths/list.html>
* U-233 (fission 531.2 b, capture 45.3 b) and Pa-233 (capture 40.0 b): <https://energyfromthorium.com/?p=124> (a blog quoting the Nuclear Information Service, so weaker than the NIST table)
* Ac-227 (21.772 y, 98.62 % beta, equilibrium with daughters after about 185 d, made by irradiating Ra-226 via Ra-227 42.2 min, studied as an RTG element), <https://en.wikipedia.org/wiki/Actinium-227>, <https://en.wikipedia.org/wiki/Isotopes_of_radium>
* Ac-227 72.3 Ci/g, 21.772 y: <https://www.isotopes.gov/sites/default/files/2021-02/Ac-227.pdf>; 14.6 W/g and the gram-scale production from irradiated radium: <https://sckcen.be/en/news/how-space-innovation-led-modern-cancer-treatments>, <https://researchportal.sckcen.be/en/publications/gram-scale-production-of-227ac-from-irradiated-radium/>;
  Ac-227 capture 890 b (JEFF-3.1.1) and 800 b (JENDL-5): <https://www.oecd-nea.org/dbforms/data/eva/evatapes/jeff_31/JEFF311/html/JEFF311N8931_MT451.html>, <https://wwwndc.jaea.go.jp/jendl/j5/Comment/n_089-Ac-227.txt>
* Tm-170 (128.6 d, 99.87 % beta, 84.25 keV gamma, proposed heat source), <https://en.wikipedia.org/wiki/Thulium-170>; Tm-170 heat source patent, <https://patents.google.com/patent/US5082617>; the Savannah River note (betas 0.967 and 0.882 MeV, bremsstrahlung the main dose), <https://sti.srs.gov/fulltext/DP-MS-68-28.pdf>
* Np-237 (2.144 My; U-235 to U-236 to U-237 6.75 d to Np-237; about 0.05 % of spent fuel), <https://en.wikipedia.org/wiki/Neptunium>; Pu-238 (87.7 y, 0.57 W/g, Np-238 2.099 d), <https://en.wikipedia.org/wiki/Plutonium-238>;
  HFIR production of Pu-238 from Np-237 targets, <https://www.osti.gov/biblio/1029578>, <https://www.frontiersin.org/articles/10.3389/fnuen.2022.1044657/full>
* The decay series, with half-lives (U-238 4.463 Gy, Ra-226 1600 y, Rn-222 3.82 d, Pb-210 22.2 y, Bi-210 5.012 d, Po-210 138.376 d; Th-232 14 Gy, Ra-228 5.75 y, Th-228 1.9125 y, Ra-224 3.632 d, Pb-212 10.63 h): <https://en.wikipedia.org/wiki/Decay_chain>
* Bismuth (a byproduct of lead, copper and tin; Bi-209 primordial, 2e19 y), <https://en.wikipedia.org/wiki/Bismuth>; the Erzgebirge Bi-Co-Ni-Ag-U veins are the textbook "five-element" association (not opened: the Wikipedia page for bismuth does not name them).
* Thorium fuel experience (Shippingport, Indian Point 1): <https://en.wikipedia.org/wiki/Thorium-based_nuclear_power>
* The mod's own: THORIUM.md (monazite, radium, lead), URANIUM-ORE.md, RTG.md, FUELS-AND-RODS.md.

Honest limits: the in-core flux (3e13), the 18-month calibration and every payload mass are design choices. The decay-energy figures for thulium-170 and the gamma figures of actinium-227 and thulium-170 are derived,
not quoted. The five-element-vein association rests on the author's knowledge, not on a page that was opened.
