# Thorium ore, its byproducts and the thorium fuel route

The mod has had thorium fuel since alpha.21 (`thorium_lean`, `thorium` and `thorium_rich`, a Th-232 fuel with a U-233 driver), but it was creative-only
or datapack-only. The mod now has its own thorium: an ore found the way its uranium is ([URANIUM-ORE.md](URANIUM-ORE.md)), and a survival route
from the ore to the fuel in the fuel fabricator ([FUELS-AND-RODS.md](FUELS-AND-RODS.md)). Fuel physics are not changed: the three grades,
their numbers and their 2, 3 and 5 % driver fractions are the existing fuel types.

## What thorium really is (short, with sources)

- **Where it comes from.** Monazite, a rare-earth phosphate, is the dominant commercial source, mostly from heavy-mineral beach and river sands: World Nuclear
  Association (WNA) puts world monazite resources at about 16 Mt, of which about 12 Mt are in Indian heavy-mineral sands, and lists India, Brazil, Australia,
  the USA, Egypt and Turkey as the largest thorium resources. Thorite (ThSiO4) is the other common thorium mineral and thorianite (ThO2) a rarer one, both
  hard-rock minerals of igneous rocks and pegmatites. In a placer the monazite is a small share of the heavy minerals (Florida beach sands: USGS).
- **How much thorium.** Monazite carries about 5 to 12 % thorium (WNA: typically about 7 %); Indian monazite about 9 to 10 % ThO2. Thorite is ideally 81 % ThO2
  and thorianite ideally 100 % (Webmineral), but real samples carry less and uranium. Thorium is about three times as abundant in the crust as uranium (WNA).
- **Radioactivity.** Th-232 has a half-life of 14 billion years and is an alpha emitter with weak gamma (EPA). The ore is mildly radioactive; monazite sand is a
  naturally occurring radioactive material (NORM). Inhaled dust is the hazard, not the lump in the pocket.
- **Fuel.** Th-232 is **fertile, not fissile**. It captures a neutron to become Th-233, which decays to Pa-233 (half-life 27 days) and then to fissile U-233. A fresh
  thorium core therefore needs a fissile **driver** (U-235 or plutonium) until U-233 has bred in. Natural thorium is essentially pure Th-232, so unlike uranium it needs
  no enrichment, only the driver. Thorium-fuelled light-water reactors have been run (Shippingport's seed and blanket breeder core, 1977 to 1982; Indian Point 1 on
  thorium with highly enriched uranium).
- **Processing.** Monazite is cracked with caustic soda (WNA gives about 140 C) and ThO2 is precipitated; thorium is mostly a by-product of rare-earth recovery. The
  game skips this: raw thorium smelts to an ingot.

## Blocks and items

| | Id | Notes |
|---|---|---|
| Thorium Ore | `bwr:thorium_ore` | in stone and the other ordinary rocks; iron pickaxe, hardness as iron ore |
| Deepslate Thorium Ore | `bwr:deepslate_thorium_ore` | in deepslate; iron pickaxe, hardness as deepslate iron ore |
| Monazite Sand | `bwr:monazite_sand` | a placer in beach and river sand; falls like sand; **any shovel**, no tool tier |
| Raw Thorium | `bwr:raw_thorium` | what the ores and the sand drop |
| Block of Raw Thorium | `bwr:raw_thorium_block` | nine raw thorium; also found in the veins |
| Thorium Ingot | `bwr:thorium_ingot` | smelt (200 ticks) or blast (100) raw thorium or either ore |
| Thorium Nugget | `bwr:thorium_nugget` | nine to an ingot, both ways |
| Block of Thorium | `bwr:thorium_block` | nine ingots, both ways |
| Rare Earth Concentrate | `bwr:rare_earth_concentrate` | byproduct (below); with an iron ingot it makes a flint and steel (ferrocerium is a cerium alloy) |
| Lead Nugget, Lead Ingot | `bwr:lead_nugget`, `bwr:lead_ingot` | byproduct of thorium and uranium ore (below); nine to one both ways; no use in this mod, tagged for others |
| Radium | `bwr:radium` | rare byproduct of thorium and uranium ore (below); a radioactive source material |

Drops and tags follow the uranium blocks: the ores and the sand drop one raw thorium, Fortune adds the vanilla ore bonus (Fortune III averages 2.2), Silk Touch drops the
block and **none of the byproducts**; the storage blocks drop themselves. Tags: `c:ores/thorium` (both ores and the sand), `c:ores_in_ground/stone` and `/deepslate`,
`c:raw_materials/thorium`, `c:ingots/thorium`, `c:nuggets/thorium`, `c:storage_blocks/thorium`, `c:storage_blocks/raw_thorium`, and for lead `c:ingots/lead` and
`c:nuggets/lead`. The two ores and the sand have no recipe (the world makes them), and `rare_earth_concentrate` and `radium` are made only by mining; they are in the
exception lists of `tools-audit-recipes.py` (and the three blocks in `tools-audit-assets.py`) with the reason.

### Byproducts of mining thorium ore

Real ore comes with other elements, and the ore drops them. The main drop is unchanged; each byproduct is its own chance, **skipped on Silk Touch**, raised by Fortune
(`minecraft:table_bonus`, chances for Fortune 0, I, II, III), and given only when the ore is broken with the right tool:

| Byproduct | Ore | Sand | Chances (Fortune 0 / I / II / III) | Why |
|---|---|---|---|---|
| Rare Earth Concentrate | yes | yes | ore 30 / 40 / 50 / 60 %; sand 25 / 33 / 41 / 50 % | monazite is a cerium, lanthanum and neodymium phosphate, roughly 55 to 65 % rare-earth oxides |
| Bone meal (1 to 2) | yes | | 15 / 20 / 25 / 30 % | monazite is a phosphate |
| Iron nugget (1 to 2) | | yes | 30 / 38 / 46 / 55 % | the sand is heavy-mineral sand: ilmenite (iron titanium oxide) lies with the monazite |
| Lead Nugget (1 to 2) | yes | | 10 / 13 / 16 / 20 % | lead-208 is the stable end of the thorium decay series |
| Raw Uranium | yes | | 2 / 3 / 4 / 5 % | monazite holds small amounts of uranium |
| Radium | yes | | 1 / 1.5 / 2 / 2.5 % | the thorium series passes through radium-228 |

- **Lead** is the mod's own `bwr:lead_nugget` and `bwr:lead_ingot`, tagged `c:nuggets/lead` and `c:ingots/lead`, so any mod whose recipes read those tags (Mekanism,
  Immersive Engineering) accepts it, with or without that mod installed; nothing here names another mod. This mod has no use for lead.
- **Radium** is a sealed capsule of about ten milligrams of radium-226 (3.7e8 Bq, about ten millicuries) and is **clearly radioactive**: carried in the inventory or the off
  hand it is a gamma point source at the player (the same provider pattern as `SpentResinItem`, through `RadiationSources`), so the survey meter reads it and a player
  carrying a meter accumulates dose. One capsule reads about 0.065 mSv/h at a metre (the GameTest prints it: 0.0065 mSv/h for a tenth of this activity), sixteen in a stack sixteen times that, and a carrier's own body is at half a metre. Its tooltip says so. It is a
  **source material only**: this mod gives it no use and no recipe; it is on the not-crafted list. Radium is hazardous (it behaves like calcium in the body and its
  decay product radon is a lung carcinogen: EPA, Wikipedia), which is why it is rare and why nothing here makes it casually.
- **Not on thorium ore:** vanadium and copper are real uranium-ore companions; they are on uranium ore, see "Byproducts of mining uranium ore" below.

### Byproducts of mining uranium ore

The same kind of extra drops are on `bwr:uranium_ore` and `bwr:deepslate_uranium_ore` (loot tables in `data/bwr/loot_table/blocks`; the main drop, one raw uranium with the
vanilla Fortune bonus, and Silk Touch giving the block alone, are unchanged). Each is its own chance for Fortune 0 / I / II / III, skipped on Silk Touch:

| Byproduct | Chances | Why |
|---|---|---|
| Lead Nugget (1 to 2) | 10 / 13 / 16 / 20 % | lead-206 is the stable end of the uranium-238 series (uranium-lead dating) |
| Radium | 1 / 1.5 / 2 / 2.5 % | radium-226 is in the series; about a third of a gram per tonne of uranium at equilibrium, which is why it is rare |
| Raw Vanadium, **whatever a mod tags `c:raw_materials/vanadium`** | 4 / 5 / 6 / 8 % | carnotite, the uranium-vanadium mineral of the Colorado Plateau, was mined for both; this mod has no vanadium, so nothing drops unless another mod provides it (a loot tag entry, no mod name in the file) |
| Raw Copper (vanilla) | 4 / 5 / 6 / 8 % | copper is recovered with uranium at deposits such as Olympic Dam |

Lead and radium are the same items as thorium ore's (`bwr:lead_nugget`, `bwr:radium`), so both ores feed the same lead and the same radium.

## World generation

Exactly the uranium design ([URANIUM-ORE.md](URANIUM-ORE.md) explains each point; read it for the chunk-safety argument). Three things are generated, only in chunks that
are being generated for the first time:

1. **Small scattered blobs**: `bwr:thorium_ore`, vanilla's `minecraft:ore` behind the server option, per biome group.
2. **Super veins**: the same long winding ribbons of altered rock as uranium's (`bwr:thorium_vein`, the same pure geometry `UraniumVeins`), a **second, independent set**
   (their own `salt`, so a thorium vein is never at the uranium vein's place), with ore pockets and an occasional raw thorium block. The filler is granite in the highland and
   placer groups and diorite elsewhere.
3. **Monazite sand placers**: blobs of up to 12 sand blocks replaced by monazite sand, three tries per chunk, in the sand of beaches and rivers (`bwr:monazite_sand_placer`,
   `#minecraft:sand` to `bwr:monazite_sand`). Only sand is replaced, in place, so nothing is left unsupported.

Safety, as for uranium: **the noise router is not touched** (no change to terrain or any density function); the vein feature writes **only inside the chunk it is placed in**
(it rebuilds the veins of the cells round it from the seed and places its own 16 x 16 columns), never calls a block write outside it, **never loads or force-loads a chunk**
and never reads another chunk's blocks; only host rock is replaced (`#bwr:thorium_host_rock`, the same blocks as uranium's: stone, granite, diorite, andesite, tuff,
deepslate, sandstone, terracotta), never air, water, other ores or anything built, and not within 6 blocks of the surface; there is no retrogen and nothing listens for a
chunk loading, so **existing chunks are never modified** and old worlds only get thorium in new ground; removing the mod leaves unknown blocks that load as air and a body of
granite, diorite and tuff.

**The option.** `thoriumOreGeneration` in `serverconfig/bwr-gameplay.toml` (default `true`), independent of `uraniumOreGeneration`. The biome modifier type
`bwr:add_thorium_features` (the same JSON as `neoforge:add_features`) reads it when the server starts and adds nothing when it is false; the vein feature and the blob
feature read it for every chunk, so changing it while a server runs stops new thorium at once.

### Biome groups (real geology, balanced)

Thorium is more abundant than uranium in the crust, but it is a placer and a granite mineral, so the groups differ from uranium's. Super veins are **rarer** than
uranium's everywhere; blobs a little more common. All in datapack JSON (`data/bwr/tags/worldgen/biome/has_thorium/<group>.json`,
`worldgen/configured_feature/thorium_vein_<group>.json`, `thorium_blob_<group>.json`, `placed_feature/...`, `neoforge/biome_modifier/thorium_<group>.json`).

| Group | Biomes | Real setting | Vein chance (uranium's, same kind) | Grade | Raw blocks | Blobs a chunk | Placers |
|---|---|---|---|---|---|---|---|
| `highland` | windswept hills, gravelly hills, windswept forest, meadow, grove, snowy slopes, frozen, jagged and stony peaks, cherry grove, stony shore | **granite, pegmatite and syenite-hosted** thorite and monazite (also the source rock of placers) | 30 % (50 %) | 26 % | 6 % | 1 (size 6) | none |
| `placer` | rivers, frozen rivers, beaches, snowy beaches | **heavy-mineral beach and river placers** (India, Brazil, Australia), with the crystalline rock they shed from | 12 % (2 % aquatic) | 18 % | 4 % | one in 2 chunks (size 5) | **3 a chunk**, size 12 |
| `lowland` | all other land biomes (plains, forests, taigas, jungles, swamps, savannas, desert, badlands, mushroom fields) | ordinary crust: thin and scattered | 6 % (12 %) | 15 % | 3 % | one in 3 chunks (size 5) | none |
| `ocean` | every ocean | almost none | 1 % (2 %) | 10 % | 2 % | one in 16 chunks (size 4) | none |

Every ordinary overworld biome is in exactly one group (a GameTest checks it); cave biomes have none (the surface biome decides, as for uranium). Keep the `geometry` object
the same in all four vein files (the vein keeps its outline across a biome edge); only the chance and the grade differ.

## The fuel route

See [FUELS-AND-RODS.md](FUELS-AND-RODS.md#thorium-targets-thorium-natural-uranium-and-steel-in) for the full table. In short, the fuel fabricator's selector has three steps after the
MOX grades: `Th 2.00 %`, `Th 3.00 %` and `Th 5.00 %`. Thorium (ingots or raw), **natural uranium** and 4 steel go in; the machine enriches the fissile driver to 19.75 % itself, by
the same mass balance as the uranium route, and gives back the depleted uranium.

| Grade | Driver | Thorium | Natural uranium | Depleted uranium back |
|---|---|---|---|---|
| `thorium_lean` | 2 % | 4 | 18 | 16 |
| `thorium` (selector step `thorium_standard`) | 3 % | 4 | 26 | 25 |
| `thorium_rich` | 5 % | 3 | 43 | 41 |

**A bred U-233 driver.** A thorium target in a reactor core makes U-233 ingots ([BREEDING-TARGETS.md](BREEDING-TARGETS.md)); the fabricator's thorium grades take them in place of natural uranium: 8, 12 or 19 U-233 ingots with 4 thorium and 4 steel for the 2, 3 and 5 % grades, no enrichment and no depleted uranium back. The costs above are unchanged; the U-233 route runs only when the player has put the U-233 in.

The driver is uranium and not plutonium on purpose: it is the mod's existing enrichment arithmetic, it needs no reactor run first, and a plutonium driver in whole nuggets
could not tell the standard grade from the rich one (36 nuggets to an assembly: 1.08 and 1.8 round to 2 and 2). The selector keeps `FuelItemRoute.LADDER` (the uranium ladder and
the MOX grades, ten steps, same indexes) and adds the thorium steps in `FuelItemRoute.SELECTOR`; the reprocessing plant still refuses thorium assemblies.

## Radiation

The mod's items that are radioactive are the ones that carry activity (spent nanofilter elements, spent fuel); **uranium items have no radiation in the mod**, and thorium
items follow them: raw thorium, ingots and the ores are as inert as the uranium ones (the real thing is weak). The one new radioactive item is **radium**, above.

## Measured

`:mod:runWorldgenCheck` (a dedicated server, world seed 20261022, the datapack `bwr_check` with one flat dimension per thorium group: bedrock, 63 deepslate, 80 stone and 3 dirt, or 3 sand in the placer one, the group's biome, normal biome decoration with the biome modifiers applied, the same seed in each) generates the same 64 x 64 chunks (a square 1,024 blocks on a side, nearly three vein cells each way) in each dimension and counts the thorium blocks. Because only the biome differs, the difference is the biome table's alone.

| Group | Thorium blocks a chunk (ore and raw) | Chunks with any | Monazite sand blocks | Vein body placed |
|---|---|---|---|---|
| `highland` | **5.29** (21,294 ore, 357 raw) | 3,948 of 4,096 | 0 | 26,156 (570 slices; 5,458 ore) |
| `placer` | 1.56 | 4,082 | **70,489** (17.2 a chunk of flat sand) | 0 |
| `lowland` | 1.04 | 1,386 | 0 | 0 |
| `ocean` | 0.14 | 235 | 0 | 0 |

The ordering is asserted (`highland > placer > lowland >= ocean`, highland at least 3 times lowland), as is that the monazite sand is only in the placer group (at least 500 blocks there, none elsewhere) and that the thorium vein chance in the highland group (0.30) is below uranium's (0.50). The highland square had
26,156 blocks of vein body; the other three groups had **no vein at all** in this square (nine cells at 12, 6 and 1 per cent): thorium veins are rare, and the numbers for those groups are blobs and sand. The shipped thorium veins are as rare as the table says by construction (a cell has a vein when its roll is below the chance, and the same roll serves every group, so a higher chance only adds veins).
The measured highland figure (5.29) lands near uranium's highland (5.39 in URANIUM-ORE.md) because this seed's highland veins happened to be generous; the veins are the rarer ones (30 % of cells against 50 %), the blobs the commoner (thorium lowland 1.04 against uranium's 0.93).

**Old chunks.** With `thoriumOreGeneration` off, an 8 x 8 block of chunks over a thorium vein (cell 6) was generated: **0 thorium blocks and 0 vein body blocks** (the option works on live generation, blobs included). With it on again, the 80 chunks round it were generated: 518 thorium blocks (2,141 of vein body), and **0 of the 64 first chunks changed** (every block state of each was hashed before and after).

**Uranium is not disturbed, but it moves a little.** Thorium and uranium veins share host rock, so where a thorium vein and a uranium vein cross, whichever feature runs first takes the block: the uranium numbers in this run differ from URANIUM-ORE.md's by a few blocks (arid vein body 44,227 against 44,215, arid ore 50,693 and 1,009 raw). Both are new-chunk effects; nothing existing changes.

The numbers above are from `mod/run/worldgenCheck/logs/latest.log` of one run; the world and the seed are fixed, so a run on the same code gives the same lines (only the timings move).

## Checks

- Core `ThoriumMassBalanceTest` (hand-worked grades, rounding only loses, impossible fractions refused).
- GameTests, class `ThoriumOreTests` (namespace `bwrthorium`; `-PbwrOnlyThorium` runs only these): blocks and tools, tags, recipes (smelting, blasting, nine to one, nuggets,
  the flint and steel), drops with and without Silk Touch and Fortune III, the byproducts' chances (6,000 mines each) and that Silk Touch skips them, radium as a carried
  gamma source, the fabricator's costs against the mass balance, a real fabricator making all three grades (exact consumption, depleted uranium out, stamped fuel type),
  its slot (right kinds only, never jams), its save and an old save, the biome table and the option (including that uranium off does not stop thorium).
- `:mod:runWorldgenCheck`: density by group, old chunks untouched, the option off giving none (see Measured).
- `:mod:runServerBootCheck` and `:mod:runSaveCompatCheck`; the GUI check's `fabricator-thorium` scenario; `:mod:runTurbineModelCheck -PbwrThoriumClientCheck`
  (`run/turbineModelCheck/thorium-check/`).

## References

Opened and read for this write-up (a figure marked in the text is the page's own):

- World Nuclear Association, Thorium: https://world-nuclear.org/information-library/current-and-future-generation/thorium
- World Nuclear Association, Naturally occurring radioactive materials: https://world-nuclear.org/information-library/safety-and-security/radiation-and-health/naturally-occurring-radioactive-materials-norm
- World Nuclear Association, Geology of uranium deposits: https://world-nuclear.org/information-library/nuclear-fuel-cycle/uranium-resources/geology-of-uranium-deposits
- World Nuclear Association, Uranium mining overview: https://world-nuclear.org/information-library/nuclear-fuel-cycle/mining-of-uranium/uranium-mining-overview
- USGS, Thorium statistics and information: https://www.usgs.gov/centers/national-minerals-information-center/thorium-statistics-and-information
- USGS, Rare earths statistics and information: https://www.usgs.gov/centers/national-minerals-information-center/rare-earths-statistics-and-information
- USGS, Vanadium statistics and information: https://www.usgs.gov/centers/national-minerals-information-center/vanadium-statistics-and-information
- USGS Circular 824 (heavy minerals of the Florida coast, monazite a small share): https://pubs.usgs.gov/publication/cir824
- US EPA, Radionuclide basics: thorium: https://www.epa.gov/radiation/radionuclide-basics-thorium
- US EPA, Radionuclide basics: radium: https://www.epa.gov/radiation/radionuclide-basics-radium
- Webmineral, Thorite: https://webmineral.com/data/Thorite.shtml and Thorianite: https://mail.webmineral.com/data/Thorianite.shtml
- Wikipedia: Thorium https://en.wikipedia.org/wiki/Thorium; Occurrence of thorium https://en.wikipedia.org/wiki/Occurrence_of_thorium; Thorium-based nuclear power
  https://en.wikipedia.org/wiki/Thorium-based_nuclear_power; Monazite https://en.wikipedia.org/wiki/Monazite; Radium https://en.wikipedia.org/wiki/Radium; Uraninite
  https://en.wikipedia.org/wiki/Uraninite; Carnotite https://en.wikipedia.org/wiki/Carnotite; Uranium-lead dating https://en.wikipedia.org/wiki/Uranium%E2%80%93lead_dating;
  Rare-earth element https://en.wikipedia.org/wiki/Rare-earth_element; Mineral sands https://en.wikipedia.org/wiki/Mineral_sands
- Thorium Energy Alliance, Historical thorium-fuelled reactors: https://thoriumenergyalliance.com/5-1-3-historical-thorium-fueled-reactors/

Honest limits: several authoritative pages (Britannica, mindat, the USGS Mineral Commodity Summaries PDFs, IAEA) could not be opened by the research, so several figures rest on WNA, the
EPA, USGS HTML pages and Wikipedia. Two claims are **not** relied on because no opened page supports them: the Elk River BWR's thorium use, and monazite "up to 20 %" thorium. The
radium abundance (about 0.34 g per tonne of uranium at secular equilibrium) comes from a search snippet and from the author's own arithmetic, and is used only to explain why radium
is rare.
