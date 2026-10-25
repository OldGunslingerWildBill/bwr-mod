# Uranium ore (the mod's own uranium)

The mod's thorium ore (veins, blobs, a placer sand, the fuel route) is built the same way and is written up in [THORIUM.md](THORIUM.md). It is a second,
independent set of veins (its own `salt`), with its own server option `thoriumOreGeneration`; this page's numbers and options are untouched by it.
Uranium ore still drops only raw uranium (and itself with Silk Touch): the byproducts of real uranium ore (lead, radium) are proposed in THORIUM.md
and not yet applied here (the existing test `oresDropRawUraniumWithFortuneAndThemselvesWithSilkTouch` asserts a single drop). Thorium and uranium veins share host rock, so where they cross
the feature that runs first takes the block: in a worldgen check run with the thorium features present the arid vein body is 44,227 blocks and not 44,215, a few blocks in new chunks only.

The fuel fabricator's item route used to need another mod for uranium (`c:ingots/uranium`). The mod now has its own ore, so a world with
only this mod can mine, smelt and enrich uranium. Mekanism's uranium keeps working beside it: both are the same tags.

## Blocks and items

| | Id | Notes |
|---|---|---|
| Uranium Ore | `bwr:uranium_ore` | in stone and the other ordinary rocks; hardness 3, as iron ore |
| Deepslate Uranium Ore | `bwr:deepslate_uranium_ore` | in deepslate; hardness 4.5, as deepslate iron ore |
| Raw Uranium | `bwr:raw_uranium` | what the ores drop |
| Block of Raw Uranium | `bwr:raw_uranium_block` | nine raw uranium; also found in the veins |
| Uranium Ingot | `bwr:uranium_ingot` | smelt or blast raw uranium or either ore |
| Block of Uranium | `bwr:uranium_block` | nine ingots |

- **Mining level: iron pickaxe** for all four blocks (`minecraft:mineable/pickaxe`, `minecraft:needs_iron_tool`, and they drop nothing to a
  stone or wooden pickaxe).
- **Drops.** The ores drop one raw uranium; Fortune adds the vanilla ore bonus (Fortune III averages 2.2, one to four); Silk Touch drops the
  ore block. The two storage blocks drop themselves.
- **Recipes.** Raw uranium, uranium ore and deepslate uranium ore smelt (200 ticks) or blast (100) to one ingot; nine raw uranium make a raw
  block, nine ingots a block, and each block makes the nine back. The two ores have no recipe (the world makes them; silk touch gives
  them back), which `tools-audit-assets.py` and `tools-audit-recipes.py` list with the reason.
- **Tags.** `c:ores/uranium` (block and item), `c:ores_in_ground/stone`, `c:ores_in_ground/deepslate`, `c:raw_materials/uranium`,
  `c:ingots/uranium`, `c:storage_blocks/uranium`, `c:storage_blocks/raw_uranium`.
- **The fabricator.** `FuelItemRoute` reads `c:ingots/uranium` (one ingot) and `c:raw_materials/uranium` (one ingot as well), so our ingot and
  raw uranium go straight in, and so do Mekanism's. Steel (`c:ingots/steel`) is still another mod's. Depleted uranium is not a uranium ingot.
- **Textures** are made by `tools-make-uranium-assets.py` (Pillow). Real uraninite is black, dense and pitch-like with a greasy lustre and a
  dull yellow-orange crust where it has weathered, so the ore is black blobs in plain stone with a few ochre specks, never glowing green.

## World generation

Two things are generated, both only in chunks that are being generated for the first time:

1. **Small scattered blobs**, vanilla's own ore feature (size 4 to 7; `bwr:uranium_ore` is `minecraft:ore` behind the server option) per biome group.
2. **Super veins**: long, winding ribbons of altered host rock, a few hundred blocks long, with ore pockets and an occasional raw uranium
   block in them; large and rare, like the big iron veins of vanilla but made without touching the noise router.

### Why there is no noise router change

Vanilla's large ore veins come from the `NoiseRouter` of the overworld noise settings. Overriding it (a datapack file `worldgen/noise_settings/
overworld.json`) changes the terrain, replaces every other mod's change to the same file, and can make a world generate differently
from the same seed, which is the corruption and compatibility risk to avoid. This mod changes **no noise settings and no density
function**. The veins are an ordinary worldgen `Feature` (`bwr:uranium_vein`) added to biomes by NeoForge biome modifiers.

### How a vein several hundred blocks long is placed safely

A feature may write only inside the chunk it is placed in (the generation region allows one chunk around, and chunks beyond that may
not exist yet or may be finished). A vein hundreds of blocks long crosses dozens of chunks, so no single chunk can place it. Instead:

- The world is divided into square **cells** (384 blocks). A cell has at most one vein.
- Everything about the vein of a cell (does it exist, where it starts, its heading, its bends, its widths, its side branches) comes from a
  random generator seeded by `(world seed, cell x, cell z)` and nothing else. Every decision about one **block** (host rock or ore,
  filler or ore, raw uranium block or not) is a hash of the seed and the block position.
- When a chunk is generated, the feature asks which veins of the cells around reach into this chunk's 16 x 16 columns, rebuilds them (from a
  small cache) and places **only the blocks inside its own chunk**. It never calls `setBlock` outside the chunk, never loads a chunk,
  and never reads another chunk's blocks.
- So the slices of neighbouring chunks meet exactly whatever order the chunks are generated in, generating a chunk twice gives the same
  blocks, a chunk does not depend on any other, and nothing is ever written to an unloaded or finished chunk. The GameTest
  `aVeinIsLongConnectedAndSeamlessAcrossChunks` rebuilds a vein chunk by chunk and compares it with the whole vein block for block.
- A vein only replaces **host rock** (`#bwr:uranium_host_rock`: stone, granite, diorite, andesite, tuff, deepslate, sandstone, red sandstone,
  terracotta), never air, water, other ores, structures or anything a player or another feature put there, and not within 6 blocks of the
  surface (`min_depth`), so veins are found by digging and in caves, not lying on the ground. The ore is deepslate uranium ore where the host is
  deepslate and uranium ore elsewhere.

### The shape

A vein is a chain of 8 block segments with a heading that wanders and a pitch that stays within 17 degrees of level, so it is a sheet or lens
rather than a pipe: half width 4.5 to 8, half thickness 2.5 to 4.5, tapering to a point at both ends and swelling and pinching along its length,
with up to two shorter side ribbons. The main ribbon is 200 to 360 blocks long. The edge is lumped with low frequency noise. Inside the body the
host rock becomes filler (granite, tuff, calcite, andesite by group); pockets of it become ore, richer towards the centre line; a few ore spots are
raw uranium blocks.

### Biome groups (real geology, tunable)

Uranium concentrates in a few real settings, and the groups follow them:

| Group | Biomes | Real setting | Veins (share of cells) | Grade (ore in the body) | Raw blocks | Blobs a chunk | Filler |
|---|---|---|---|---|---|---|---|
| `arid` | desert, badlands, eroded and wooded badlands | **sandstone-hosted roll-front deposits** (Colorado Plateau, Wyoming basins, Kazakhstan): uranium carried by groundwater and dropped where it meets reducing rock, the richest and the most common | 85 % | 34 % | 8 % | 2 (size 7) | calcite / tuff |
| `highland` | windswept hills and gravelly hills, windswept forest, meadow, grove, snowy slopes, frozen, jagged and stony peaks, cherry grove | **granite and pegmatite hosted** (the Alps, Erzgebirge, Rossing): uranium from the late melt of granite | 50 % | 28 % | 6 % | 1 (size 6) | granite / tuff |
| `semi_arid` | savanna, savanna plateau, windswept savanna | **dry-basin** deposits at the edge of the arid group, thinner | 30 % | 24 % | 5 % | one in 2 chunks (size 6) | andesite / tuff |
| `lowland` | plains, sunflower plains, snowy plains, ice spikes, forests, taigas, jungles, swamps, mushroom fields | few deposits; mostly small blobs | 12 % | 18 % | 3 % | one in 4 chunks (size 5) | tuff |
| `aquatic` | rivers, beaches, stony shore, every ocean | almost none (uranium lies under thick young sediment or water) | 2 % | 12 % | 2 % | one in 16 chunks (size 4) | tuff |

(Cave biomes have no group: the surface biome of the column decides, because a feature is placed at the surface height of the chunk's centre.
The Nether and the End have no uranium.) Every ordinary overworld biome is in exactly one group, and a GameTest checks it. A modded biome that is in
no group gets no uranium until a pack adds it to a tag (below).

### Where the numbers are, and changing them

All of it is datapack JSON. A pack can override any file at the same path, or add its own.

| File | What |
|---|---|
| `data/bwr/tags/worldgen/biome/has_uranium/<group>.json` | which biomes are in the group (add a modded biome here with `"replace": false`) |
| `data/bwr/worldgen/configured_feature/uranium_vein_<group>.json` | `vein_chance`, `ore_density`, `raw_block_chance`, `min_depth`, the `geometry` object (`cell_size`, `min_y`, `max_y`, `length_min/max`, `half_width_min/max`, `half_thickness_min/max`, `branches_max`, `salt`), `host` tag, the five block states |
| `data/bwr/worldgen/configured_feature/uranium_blob_<group>.json` | the blob's `size` and `discard_chance_on_air_exposure` |
| `data/bwr/worldgen/placed_feature/uranium_vein_<group>.json`, `uranium_blob_<group>.json` | placement; the blobs' `count` or `rarity_filter`, and `height_range` |
| `data/bwr/neoforge/biome_modifier/uranium_<group>.json` | which tag gets which placed features |
| `data/bwr/tags/block/uranium_host_rock.json` | what a vein may replace |

Keep the `geometry` object the same in all five vein files: the vein's shape comes from its cell and the geometry, and only its **chance** and
**grade** change from one group to the next, so a vein keeps its outline where it passes from one group's chunks into another's. (It simply
stops where the chance of the next group is lower than the vein's roll: a desert vein ends at the edge of a plains chunk, which is the
point of the table.) `vein_chance` is a monotone coupling: a vein that exists at 12 % exists at 85 %, so lowering a chance only removes veins.

### Safety: existing worlds, removing the mod, other mods

- **Only new chunks.** Generation is the ordinary decoration step of chunk generation. Nothing in this mod listens for a chunk loading, there
  is no retrogen, and the feature writes only into the chunk it is placed in. A chunk that exists in a world is never changed.
  The worldgen check proves it on a live server: a block of chunks is generated, the chunks round it are generated afterwards, and every
  block of the first block is compared before and after.
- **An existing world.** Old chunks keep what they have: no uranium. Chunks generated after the update get it. So an old world has a visible
  edge at the old world border of generation; exploring new ground finds ore. (A pack that wants ore in old ground can use a chunk
  regeneration tool of its own choosing; this mod does not.) Chunks that were only partly generated when the world was saved finish their
  generation after the update and get ore, which is the ordinary way vanilla treats a new feature.
- **Switching it off.** The server option `uraniumOreGeneration` in `serverconfig/bwr-gameplay.toml` (default `true`). The biome modifiers
  read it when the server starts (after the world's server config is loaded) and add nothing when it is false, so the biomes keep exactly the
  features they have without this mod; the vein feature also reads it for every chunk. A NeoForge condition on the datapack files could not do this
  (conditions are evaluated while the data loads, before any world config exists, so they could only read the default), which is why the
  modifier type is the mod's own: `bwr:add_uranium_features` (the same JSON as `neoforge:add_features`). Changing the option while a
  server runs stops new veins at once; blobs follow after a restart.
- **If the mod is removed.** The standard behaviour: the uranium blocks in the world are unknown blocks and load as air (the chunk's palette
  entry is dropped on load); the items disappear from inventories; veins leave a body of tuff, granite and so on in the rock, which is ordinary stone
  with a different texture. Nothing in the vein generation changes a vanilla block's id or data in a way another mod depends on. The save compat
  check (`:mod:runSaveCompatCheck`) shows the reverse: an old world loads with the new blocks registered.
- **Mekanism** also generates uranium ore (biome modifier `mekanism:uranium`, placed features `mekanism:ore_uranium_small` and
  `mekanism:ore_uranium_buried` in `#mekanism:spawn_ores`). Ours is on by default beside it. A pack can turn either off: ours with
  `uraniumOreGeneration = false`, or by overriding the file `data/bwr/neoforge/biome_modifier/uranium_<group>.json` with `{"type": "neoforge:none"}`;
  Mekanism's with its own world config (the ore's `shouldGenerate` option in Mekanism's world config) or by overriding
  `data/mekanism/neoforge/biome_modifier/uranium.json` the same way. Both ores carry `c:ores/uranium` and drop an item in
  `c:raw_materials/uranium`, so everything that uses the tags works with either.

## Measured

`:mod:runWorldgenCheck` (a dedicated server, world seed 20261022, a datapack with one flat dimension per biome group: bedrock, 63 deepslate, 80
stone, 3 dirt, the group's biome, normal biome decoration with the biome modifiers applied, the same world seed in each) generates the same 64 x 64
chunks (a square 1,024 blocks on a side, nearly three vein cells each way) in each dimension and counts the uranium blocks. Because only the biome
differs, the difference is the biome table's alone. Veins and blobs are told apart by the vein feature's own counters.

| Group | Uranium blocks a chunk | of them in veins | of them in blobs | Chunks with any | Vein slices | Vein body placed |
|---|---|---|---|---|---|---|
| `arid` | **12.55** | 3.25 | 9.30 | 4,094 of 4,096 | 998 | 44,215 |
| `highland` | 5.39 | 1.60 | 3.80 | 3,931 | 635 | 26,475 |
| `semi_arid` | 2.62 | 0.76 | 1.86 | 2,081 | 377 | 14,954 |
| `lowland` | 0.93 | 0.22 | 0.71 | 994 | 224 | 6,401 |
| `aquatic` | 0.10 | 0.00 | 0.10 | 182 | 0 | 0 |

So an arid chunk has 13.5 times the uranium of a lowland one (14.8 times in veins alone) and 2.3 times a highland one's; a highland chunk has 5.8 times a lowland one's; an ocean chunk a tenth of a lowland one's. The ordering is asserted (`arid > highland > semi_arid > lowland >= aquatic`, arid at least 5 times lowland, arid vein ore at least
1.5 times highland's and 8 times lowland's). The same vein cells and shapes are in all five dimensions; only the chance and the grade differ, so
the table is the biome table's effect and not luck of position.

**The veins.** In the arid square the generator wrote 44,215 vein blocks (filler, ore and raw), measured exactly as 26-neighbour connected
bodies of what was placed: 21 bodies, the largest **12,023 blocks, 159 x 23 x 182 (a horizontal span of 242 blocks)**, the next 11,289 blocks
(292 x 37 x 139, span 323), then 5,924 and 5,863. Counting only the ore, joined within 3 blocks (the ore sits in pockets with filler between), the
largest body is 2,994 blocks across 158. The pure geometry of a sample of twelve veins (GameTest `aVeinIsLongConnectedAndSeamlessAcrossChunks`,
no world): 4,100 to 17,000 blocks each, up to 292 blocks across, 100 % of the body connected, ore 26 to 31 % of the body at the arid grade, and 8 % of
the ore spots raw uranium blocks.

**Real terrain.** On the ordinary overworld of the same seed, a vein whose centre line is in the arid group was located from the seed and the biome
source alone (cell 4, 6, middle at 1580, 2396), and the 153 chunks round it generated (9 x 17 chunks, the real caves, ravines and aquifers
cutting across): 142 vein slices, 9,316 blocks placed, 2,389 ore and 190 raw uranium blocks in the vein, in 9 connected bodies, the largest **5,150
blocks, 98 x 16 x 115 (span 151)**: the caves of real terrain cut the ribbon into pieces and the biome-group edges end it, as the table says.

**Cost.** The vein feature took 203 ms for all 4,096 chunks of the arid square (about 50 microseconds a chunk, 0.12 to 0.2 ms for each chunk
a vein passes through; the rest of generating a chunk of that dimension takes about 33 ms). The cache of veins is at most 2,048 small objects.

**Old chunks.** With the option off, an 8 x 8 block of chunks over a vein was generated: 0 uranium blocks and 0 vein blocks (the option works on
live generation, blobs included). With the option on again, the 80 chunks round it were generated: 724 uranium blocks, 1,223 vein blocks, and **0 of
the 64 first chunks changed** (every block state of each was hashed before and after).
A mutation test that made the vein feature write 8 blocks past its own chunk into these neighbours changed nothing either: the generator itself discards writes into a chunk that is already finished (the neighbour is handed to a feature read-only), so a new chunk cannot alter an old one even by mistake; the feature never tries.

The numbers above are from `mod/run/worldgenCheck/logs/latest.log` of one run; the world and the seed are fixed, so a run on the same code gives the same
lines (only the timings move).

## Checks

- GameTests, class `UraniumOreTests` (namespace `bwruranium`; `-PbwrOnlyUranium` runs only these): blocks, iron-pickaxe level, tags, recipes
  (smelting, blasting, nine to one both ways), loot with and without Silk Touch and with Fortune III, the fabricator taking our ingot and raw
  uranium (and Mekanism's when it is loaded), the vein shape (long, connected, chunk slices adding up to the whole vein), the grade, the biome
  table read back from the loaded datapack (every ordinary biome in exactly one group, the ordering of the numbers), and the server option.
- `:mod:runWorldgenCheck`: a dedicated server on a fresh world generates real chunks and measures them (see Measured).
- `:mod:runServerBootCheck` and `:mod:runSaveCompatCheck`: a dedicated server boots with the new registries and datapack, and an old world
  loads.
- `:mod:runTurbineModelCheck -PbwrUraniumClientCheck`: 1920 x 1080 pictures of a vein, the blocks and the items
  (`run/turbineModelCheck/uranium-check/`).
