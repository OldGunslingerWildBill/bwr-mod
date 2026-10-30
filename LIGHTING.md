# Industrial lamps

Six lamps for turbine halls, reactor buildings and yards: three lamp types, each as a high-bay pendant or a wall pack.
They are decorative and redstone-controlled; they never act on the plant and need no FE. Code is in
`dev.bwr.mod.lighting`, the tests are `IndustrialLampTests` (batch `industriallamps`, namespace `bwrlamps`).

| Block | Lamp | Colour (lit lens) | Warm-up (game) | Restrike wait |
|---|---|---|---|---|
| `bwr:lps_high_bay`, `bwr:lps_wall_pack` | Low-pressure sodium (589 nm) | `#FF8C00`, deep orange | 40 s (real 7 to 10 min) | none, restarts at once |
| `bwr:hps_high_bay`, `bwr:hps_wall_pack` | High-pressure sodium | `#FFC85A`, golden | 20 s (real 3 to 5 min) | 15 s |
| `bwr:mercury_high_bay`, `bwr:mercury_wall_pack` | Mercury vapour | `#AADCFF`, blue-white | 27 s (real 4 to 7 min) | 15 s |

Low-pressure sodium is monochrome: its light renders almost everything in orange, which is the point of it. The lens
colour above is the colour of the light the lamp is meant to give.

## Behaviour

- **Redstone.** Powered is on. A lamp is struck when a neighbour carries signal and it is ready.
- **Light.** Lit, a lamp gives light level 4, then 8, 12 and 15 at the end of three warm-up steps. Unlit, it gives none.
- **Warm-up.** One step is a third of the warm-up time: 133 ticks for high-pressure sodium, 180 for mercury, 266 for
  low-pressure sodium. Each step is a scheduled block tick. There is no per-block ticker.
- **Restrike.** A hot high-pressure or mercury lamp that is switched off cools for three 100-tick units (about 15 s)
  before it can strike again. A lamp that is still powered when the cooling ends strikes at once. A lamp switched back on
  during cooling stays dark until the cooling is done.
- **Low-pressure sodium** has no restrike wait. This is deliberate: its discharge is at low pressure and it does not
  have the hot restrike delay of the high-intensity types.
- **Mounts.** A high bay hangs under a solid block (its support is the block above). A wall pack mounts on a solid block
  behind it; it is placed by clicking that wall. Either one drops itself if its support goes.
- **Warm-up shows in the lens.** The lens has three lit looks: warm-up stage 1 is dim and pushed towards red-pink
  (light 4, `<type>_lens_w0`), stage 2 is dimmer and paler (light 8, `<type>_lens_w1`), and full is the lamp colour at
  light 15 (`<type>_lens_on`). Unlit, the lens is dark glass (`<type>_lens_off`).

## Blocks and state

State: `lit`, `warmup` 0 to 3 (the light stage), `cooling` 0 to 3 (restrike units still to run), and `facing` on the wall
pack. The blockstates are multipart: the housing always, and one lens part chosen by `lit` and `warmup` (`lit=false`:
off; `lit=true` with `warmup` 0, 1, and 2 or 3: w0, w1, on). Wall packs add the facing to every part and turn it with `y`.
A new save format adds nothing to old saves: the lamps are new blocks.

## Lit lens (fullbright)

The lens is its own model part, separate from the housing. The lit lens parts set `emissive_ambient: true`. In NeoForge's
OBJ loader that flag takes each quad's light level from its material's ambient colour `Ka` (`light = mean(Ka) * 15`), not
from the world, so the lens is fullbright and the housing still takes world light. The `Ka` values are 4.2/15 (w0),
8.2/15 (w1) and 1.0 (on). The lit lens parts have no shading, so their colour is flat. The unlit lens part has
`emissive_ambient: false` and world light.

The item model is one whole-lamp OBJ (the housing plus the lit lens), so the inventory icon shows the lamp lit.

## Shaders (Iris and labPBR)

The lit lens is an emissive texture. Each `_on` lens ships a labPBR specular map beside it, named with the suffix
`_s` (`bwr:block/lamp/<type>_lens_on_s.png`), so a shader pack that reads labPBR (Complementary: the Integrated PBR /
labPBR setting) reads the emission from the map's alpha channel and makes the lens glow.

- **Map layout (labPBR).** R is perceptual smoothness (230 for the lens), G is F0 and 0 here (not metal), B is
  porosity (0), and **A is emission**. The lens uses 220 across its face and 180 on the frame. labPBR's convention is
  that 255 means no emission and 0 to 254 is the level; this is taken from secondary sources (the shaderlabs wiki
  describes the alpha channel; the 255 rule is from a tool's documentation) and should be checked against the official
  labPBR spec before a release.
- **How Iris finds it.** Iris looks for `<texture>_n.png` and `<texture>_s.png` next to each block texture, in the same
  folder, from resource packs and mod assets alike. The mod ships no `_n` map, so the normal stays flat.
  Iris's behaviour with a mod's own assets is expected from its documentation and was **not verified in game** (see
  "Not tested").
- **Lens colour.** The lit lens's colour is the lamp's colour. A shader that tints light from the emitting block's
  texture therefore gets the right colour. Shader packs that tint by block ID need their own `block.properties`
  entry; the mod does not ship one.
- **Unlit lens** (`<type>_lens_off`): dark glass, no `_s` map, so it does not glow.
- **Warm-up lenses** (`w0`, `w1`) carry `_s` maps too, with emission 120 and 180, so they glow less.
- **Render type.** Solid. The lenses are opaque discs and faces, so no cutout or translucent layer is needed.

## Files

- Code: `mod/src/main/java/dev/bwr/mod/lighting/` (`LampType`, `IndustrialLampBlock`, `HighBayLampBlock`,
  `WallPackLampBlock`); registration in `BwrBlocks` (the `lamp()` helper and the six blocks) and `BwrItems`.
- Models: Blender, headless, `art/models/industrial_lamps/build_lamps.py`
  (`blender --background --factory-startup --python art/models/industrial_lamps/build_lamps.py`). It writes two housing
  OBJ files, 24 lens OBJ files (three types, two fixtures, four stages), six item OBJ files and `lamps_materials.mtl` into
  `mod/src/main/resources/assets/bwr/models/block/lamp/`. The geometry is in block units; the script checks each file's
  bounds. Every model is under 100 faces.
- JSON (models, multipart blockstates, item models, loot tables, recipes): `tools-export-lamps.py`.
- Textures: `tools-make-lamp-textures.py` writes the four lens looks and the labPBR maps into
  `mod/src/main/resources/assets/bwr/textures/block/lamp/`. The housing uses `minecraft:block/white_concrete`, tinted
  grey by the material, as the furniture does.
- Pickaxe tag: all six blocks. Lang: `block.bwr.*` keys. Guide: "Industrial lamps", in the control-room chapter.

## Recipes

Each lamp type has its own lens glass, with iron, redstone and glass in common.

| Block | Pattern |
|---|---|
| LPS high bay | `III` / `OGO` / ` R ` (O orange stained glass) |
| LPS wall pack | `IRI` / `OGO` |
| HPS high bay | `III` / `GLG` / ` R ` (L glowstone) |
| HPS wall pack | `IRI` / `GLG` |
| Mercury high bay | `III` / `GBG` / ` R ` (B light blue stained glass) |
| Mercury wall pack | `IRI` / `GBG` |

I iron ingot, R redstone dust, G glass.

## Showroom

`LampClientCheck` (`-PbwrLampClientCheck`, optionally `-PbwrRenderMods=newest -PbwrShaderPack=...`) builds a 20 x 8 x 20
hall with a dark floor and walls: six high bays in two rows, three wall packs on the west wall. Each lamp is switched by a
lever beside it (on the ceiling for a high bay, on the wall above a wall pack), so no bright redstone block shows. It takes
six pictures and logs the light at each lamp.

## Tests

`IndustrialLampTests`, batch `industriallamps`, run with `-PbwrGameTestNamespaces=bwrlamps`:

- registration, the item, the pickaxe tag, the loot table and the recipe, and a dark cold starting state;
- every lamp state survives the save format (`NbtUtils`);
- a high-pressure lamp on redstone ramps 4, 8, 12, 15 at the scheduled steps;
- a hot high-pressure lamp switched off and on again stays dark until its cooling is done, then strikes;
- a low-pressure lamp restrikes at once;
- a mid-warm-up lamp has its step scheduled and its state round-trips;
- a high bay needs a ceiling and a wall pack needs a wall; the wall pack's lens turns with its facing.
- the lit lens model is fullbright (`emissive_ambient`) and the housing is not: checked on the showroom pictures, not by
  a GameTest.

## Not tested, and limits

- **Shaders.** Iris 1.8.14-beta.1 with Sodium 0.8.13 and Complementary Reimagined r5.9.3 were run on the showroom. The
  pictures are lit and differ from vanilla. Whether the labPBR `_s` emission, rather than block light, is what makes the
  lens glow was **not isolated**. The labPBR emission convention above is from secondary sources.
- **Save and reload** of a mid-warm-up lamp is covered by the state round-trip and the scheduled-tick check. A real
  save-and-reload of a world was not run for these blocks.
- **Warm-up colour** is not shown (see Behaviour).
- **Time scale.** Warm-up and cooling are game-time compressions of the real times, chosen to sit in the 20 to 40 second
  range the design asked for.

# Beam lamps (xenon floodlight, xenon high-bay, HPS flat panel)

Three redstone lamps that throw light where their beam lands, by placing invisible vanilla `minecraft:light` blocks. Code
is in `dev.bwr.mod.floodlight`, the tests are `FloodlightTests`, `FloodlightReachTests` and `FloodlightAimTests` (namespace
`bwrfloodlight`, run with `-PbwrGameTestNamespaces=bwrfloodlight`), the pictures `FloodlightClientCheck`
(`-PbwrFloodlightClientCheck`).

| Block | Mount | Range | Main beam (full angle) | Spill zone (full angle) | Light |
|---|---|---|---|---|---|
| `bwr:xenon_floodlight` | stands on any block; aimable on a screen (heading 0 to 359 degrees, tilt -75 to +30, one degree at a time) | 32, or 8 to 128 as set | 30 degrees | none | cold white, at once |
| `bwr:xenon_high_bay` | ceiling | 48 | 110 degrees | out to 160 degrees | cold white, at once |
| `bwr:hps_flat_panel` | ceiling or wall | 40 | 120 degrees | out to 160 degrees | golden `#FFCE80`, warms 4/8/12/15 over about 20 s |

- **Projection.** A fan of rays over the cone walks the grid to the first opaque block; the last free cell in front of it
  (within 2.5 blocks) gets a light. A lamp with no spill zone (the floodlight) casts up to 2048 rays (146 at its usual reach
  of 32, 585 at 64, the 2048 at 128: the count grows with the square of the reach), thins the landing cells to one per
  2-block lattice box and widens the box up to 6 until the plan fits. A lamp places at most **256** lights.
- **Reach and spill (high-bay, HPS panel).** These two hang tens of blocks up over a big floor, so they reach 48 and 40
  blocks (the floor of a hall 30 up is lit out to 37 blocks from under the high-bay) and cast 1024 rays over 160 degrees,
  thicker towards the edge of the cone. The cells the rays outside the main beam land on are the **spill zone**: their
  lights are four fifths of the beam's level (15 becomes 12; the HPS warm-up 4, 8, 12, 15 gives spill 3, 6, 10, 12), so the
  beam stays the bright centre and the pool has no hard dark edge. Past the cone, light only falls off at one level a
  block, so a hard edge of light 15 would leave a dark ring. The lattice box grows with the distance from the lens, from 2
  up to 6, as coarse as the cap needs and no coarser (dense under the lamp, sparse far off). Block light over the floor
  of a flat hall, lowest and mean over 4 blocks of horizontal distance, high-bay 12 up (before: 24 blocks of range, no
  spill):

  | Distance | 0 | 8 | 12 | 16 | 20 | 24 | 28 | 32 | 36 | 40 | 44 | 48 |
  |---|---|---|---|---|---|---|---|---|---|---|---|---|
  | before | 13/14.2 | 13/14.1 | 13/14.1 | 9/12.1 | 4/7.6 | 0/3.0 | 0/0.2 | 0/0.0 | 0/0.0 | 0/0.0 | 0/0.0 | 0/0.0 |
  | now | 11/13.3 | 11/13.3 | 9/12.8 | 7/10.3 | 6/9.1 | 6/9.1 | 6/9.0 | 6/9.0 | 5/8.9 | 4/8.7 | 2/7.9 | 0/4.2 |

  Beyond about 25 blocks from the lamp the floor reads 5 to 9 (the 256 lights are about 6 blocks apart there, and a spill
  light spreads 12 levels); under the beam it reads 11 to 15. The cap limits it more than the planner does: an offline
  model of the same floor with 512 lights read half a level to a level better on the far floor (not run in the game), so the
  cap stays at 256.
- **Cost.** A re-plan casts 1024 rays (it was 1029 for the high-bay and 1206 for the HPS panel), but twice as far: on a flat floor 1.1 ms against 0.4 to 0.7
  (the planner alone, 12 up), and about 2 ms a lamp against 1.3 with the world behind it (a hall of 40 high-bays,
  everything in place: one pass over all 40 took 79 ms against 53 ms). Cells next to the lens are read once for all the
  rays; farther out, where the rays have parted, each is read as it is met; the chunk of the last cell read is remembered
  for the length of one plan. `FloodlightReachTests` logs these (`FLOODLIGHT PLANGEOMETRY`, `FLOODLIGHT PLANCOST`).
- **What may be replaced.** Plain air, cave air, or a still water source (the light is waterlogged). Nothing else, and
  never a light block the lamp did not place itself.
- **Cleanup.** The block entity lists every cell it changed and what it held. Off, re-aimed, broken (any path, explosions
  included) or replaced: each listed cell is put back only while it still holds a light. A cell in an unloaded chunk goes
  to a level save queue (`PendingLights`) and is put back once, when its chunk is loaded again. Nothing loads a chunk;
  writes skip neighbour shape updates; nothing runs on a chunk unload, a save or a shutdown.
- **Levels are not saved.** The save is still `Lights` and `LightKinds` (cell and what it held). Which lights are spill
  is a property of the plan, so a lamp from an older save, whose lights were all level 15, is brought to the spill levels
  at its next re-plan, in place, with no light added or taken away.
- **Timing.** No ticker: redstone re-plans at once; a lit lamp re-plans on its own scheduled tick every 5 s (a floodlight set
  to a reach over 32 blocks adds 0 to 39 ticks, by its position, so a yard of them does not re-plan in one tick); a change of
  the floodlight's aim from its screen or a computer re-plans once, 4 ticks after the first change of a burst (below).
- **Lenses.** Separate lens parts, full bright in vanilla (NeoForge face light data; the floodlight's head lens through its
  renderer), with labPBR `_s` maps (alpha 254 on the lit lenses) for shader packs.
- **Computers.** A `bwr_beam_lamp` peripheral on all three lamps. Redstone is the only switch. `getStatus()` gives `lit`,
  `lightBlocks`, `yawDeg`, `pitchDeg` and `reach` (blocks) (and `yawDegrees` and `pitchDegrees`, the same two numbers under
  their older names; the high-bay and the panel report yaw 0, pitch 0 and their fixed range as the reach). The floodlight
  alone has **`setAim(yawDeg, pitchDeg)`** (yaw 0 to 359 from the way it faces, pitch -75 to +30, up is plus) and
  **`setReach(blocks)`** (8 to 128). The values must be whole numbers in range or the call fails and nothing changes; on a
  high-bay or a panel both calls fail ("Only the xenon floodlight can be aimed"). The head turns at once and the light follows
  within 4 ticks (one re-plan for a run of calls).

## The floodlight's aim and reach

- **The aiming screen.** Right-click the floodlight with an empty hand (`FloodlightAimMenu`, `FloodlightAimScreen`, the mod's
  usual menu and command packet). Three cards, each with a minus and a plus button: **Heading** (how far the lamp is turned
  from the way it faces, 0 to 359 degrees, plus turns it to the right), **Tilt** (-75 to +30 degrees, plus is up) and **Reach**
  (8 to 128 blocks, 32 is the usual). A click is one degree (one block of reach); with Shift held, 10 degrees (8 blocks).
  The numbers change as you click, the head on the client turns to the exact angle at once (the block entity's update
  tag carries `YawDeg` and `PitchDeg`), and the light follows. A card of the screen also shows whether the lamp is lit and
  how many of its 256 light blocks are placed.
- **Sneak-right-click** keeps its old job as a quick tilt: 15 degrees a click, from +30 round to -75, the aim on the action bar.
  A plain right-click used to turn the lamp 45 degrees; it now opens the screen (the quick 45-degree turn is gone; the
  screen's heading does that in any step).
- **What the server checks** on every command: the player has this menu open and the lamp is in reach, the player is not a
  spectator and may build there (`BeamLampBlockEntity.mayAim`, as before), at most 30 commands come in 20 ticks (the rest are
  dropped), and the values are in range (yaw 0 to 359, pitch -75 to +30, reach 8 to 128). A command that fails any check does
  nothing.
- **One re-plan for a burst** (`AimReplans`). A command moves the head and the saved aim at once. The first change of a burst
  starts a timer of 4 ticks; when it ends the lamp re-plans once, for the aim it has by then; a burst that goes on is cut
  into re-plans 4 ticks apart, so a held button costs at most one plan in four ticks. The queue holds a lamp only between a
  change and its re-plan (it is not a ticker per block, and the server tick does nothing while it is empty).
- **Saves.** The aim is `YawDeg` (0 to 359), `PitchDeg` (-75 to +30) and `Reach` (8 to 128), each written only when it is not
  the default (0, 0, 32). While the yaw is a multiple of 45 degrees and the pitch of 15 (from -75), the old keys `Yaw` and
  `Pitch` (steps 0 to 7) are written too, so a world opened in an older build keeps the aim. A save that has only `Yaw` and
  `Pitch` (every save made before) loads to the same aim: yaw is the step times 45, pitch is -75 plus the step times 15
  (`Pitch` absent is step 5, level), and the reach is 32, so an existing floodlight lights exactly what it did.
- **Long reach.** The planner is the one the floodlight always had, given the lamp's reach. A far wall 123 blocks away at
  reach 128: 183 lights (129 of them on the wall), so the plan is
  inside the cap with room to spare. Worked out from the lights (15 less a level a block), the pool within 20 blocks of its
  middle reads 10 at its worst and 12.7 on average, and the light engine, read back, agrees: 15 at the middle, 10 at the
  worst of the sampled cells.
  The floor, at long reach: A floodlight 22 up, tilted 15 degrees down, reach 128: 246 lights, every floor cell
  inside the cone within 100 blocks reads 10 or more (mean 13.1). The yard of the pictures (12 up, level, a wall at 123 blocks,
  so floor and wall share the lights): 229 lights, 9 or more (mean 12.5), and the light engine reads the same. The usual reach
  (10 up, tilted 30 down, reach 32): 53 lights, 13 or more. So the cap of 256 lights and the lattice (2 widening towards 6) hold at 128, and the planner
  was left as it was. The far pool is lit more sparsely than a pool at 32 blocks (a light about every 3 to 4 blocks against 2),
  and the reach does not make the cone any wider: the beam stays 30 degrees across, so the pool on a wall 120 blocks away is
  about 64 blocks across.
- **What a plan costs.** One re-plan of a lit floodlight that finds nothing to change (what the 5-second refresh costs), median of
  40: **0.2 ms at reach 32, 1.6 ms at 64, 9.5 ms at 128** (7.2 to 13.6; with a wall at 123 blocks every one of the 2048
  rays walks the whole way, about 190 cells each; at 32 and 64 the rays in the test find nothing to land on and the cost is
  the walk alone). A **yard of 20 lit floodlights at reach 128**, all re-planned one after the other, takes **200 ms**
  (167 to 230), 10 ms a lamp: that is what all twenty at once would cost, which the stagger avoids (twenty lamps fall on 18
  different ticks, at most two in one: about 20 ms in the worst tick). `FloodlightAimTests` logs these
  (`FLOODLIGHT AIM PLANCOST`, `FLOODLIGHT AIM FARWALL`, `FLOODLIGHT AIM FLOORPOOL`, `FLOODLIGHT AIM YARDFLOOR`).
- **No chunk is loaded.** A ray stops at the first cell whose chunk is not loaded, as before (so a long beam stops at the edge
  of the loaded area and lights nothing beyond it); a chunk that is full but not yet visible to the chunk source (it became
  full this tick) now counts as not loaded too, so a plan never makes the game finish a chunk load on its stack. Lights that
  landed far away are cleaned up exactly like the others, through `PendingLights` when their chunk is unloaded.
- **Pictures** (`-PbwrFloodlightClientCheck`, `floodlight-check/vanilla/`): `floodlight-far-wall-reach128` and `-reach32` (and
  `-wide`) show one floodlight on a 12-block tower, level, looking along a flat yard at a wall 123 blocks away at night, at
  reach 128 (the wall is lit) and at reach 32 (it is dark). The check ends with the screen over the real connection: the server
  opens the floodlight's menu for the player, three clicks and a change of reach are made through the screen's own methods
  (so as packets), and the server's lamp and the client's copy must both have the exact aim (`floodlight-aim-screen-open.png`).
  The screen's layouts are in the screen check (`GuiScreenCheck`: `floodlight-aim`, `-far`, `-limits`, `-waiting`).
- **Tests** (`FloodlightAimTests`): a one-degree change of tilt or heading moves the lit pool the right way by about half a
  block, and five degrees five times as far; the quick steps still move on the old grid; a save with step aim loads to the
  same aim and lights exactly the same cells, a save with degrees round-trips (client copy and beam direction too), defaults write
  nothing; the screen's commands (good ones, every kind of bad one, a player too far away, a spectator, a player who may not
  build, a high-bay, a hundred commands in a tick, the window opening again); a burst of fifteen clicks costs one plan and a
  click a tick for twelve ticks at most four; re-aiming and every change of reach put every light back exactly; reach 128
  lights a wall 123 blocks away and 16, 32 and 64 do not (the light engine read back), the far pool and the floor pool are
  covered; the cost; a long beam stops at an unloaded chunk and loads nothing; far lights are cleaned up exactly, through
  the queue when their chunk is out; the computer face.
