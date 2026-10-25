# Industrial lamps

Six lamps for turbine halls, reactor buildings and yards: three lamp types, each as a high-bay pendant or a wall pack.
They are decorative and redstone-controlled; they never act on the plant and need no FE. Code is in
`dev.bwr.mod.lighting`, the tests are `IndustrialLampTests` (batch `industriallamps`, namespace `bwrlamps`).

| Block | Lamp | Colour (lit lens) | Warm-up (game) | Restrike wait |
|---|---|---|---|---|
| `bwr:lps_high_bay`, `bwr:lps_wall_pack` | Low-pressure sodium (589 nm) | `#FFA01E`, deep orange-yellow | 40 s (real 7 to 10 min) | none, restarts at once |
| `bwr:hps_high_bay`, `bwr:hps_wall_pack` | High-pressure sodium | `#FFCE80`, golden-white | 20 s (real 3 to 5 min) | 15 s |
| `bwr:mercury_high_bay`, `bwr:mercury_wall_pack` | Mercury vapour | `#BEECE2`, cold bluish-white, greenish | 27 s (real 4 to 7 min) | 15 s |

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
- **Warm-up is shown by light, not colour.** The lens looks the same while the lamp warms; only the light level ramps.
  Vanilla cannot change a lamp's light colour by stage, so the warm-up's dim red-pink glow is not shown.

## Blocks and state

State: `lit` (`BlockStateProperties.LIT`), `warmup` 0 to 3 (the light stage), `cooling` 0 to 3 (restrike units still
to run), and `facing` on the wall pack. The lit and unlit models differ only in the lens texture. A new save format
adds nothing to old saves: the lamps are new blocks.

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
- **Render type.** Solid. The lenses are opaque discs and faces, so no cutout or translucent layer is needed.

## Files

- Code: `mod/src/main/java/dev/bwr/mod/lighting/` (`LampType`, `IndustrialLampBlock`, `HighBayLampBlock`,
  `WallPackLampBlock`); registration in `BwrBlocks` (the `lamp()` helper and the six blocks) and `BwrItems`.
- Models: Blender, headless, `art/models/industrial_lamps/build_lamps.py`
  (`blender --background --factory-startup --python art/models/industrial_lamps/build_lamps.py`). It writes twelve OBJ
  files (three types, two fixtures, lit and unlit) and `lamps_materials.mtl` into
  `mod/src/main/resources/assets/bwr/models/block/lamp/`. The geometry is in block units; the script checks each file's
  bounds. Every model is under 100 faces.
- JSON (models, blockstates, item models, loot tables, recipes): `tools-export-lamps.py`.
- Textures: `tools-make-lamp-textures.py` writes the lenses and the labPBR maps into
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

## Tests

`IndustrialLampTests`, batch `industriallamps`, run with `-PbwrGameTestNamespaces=bwrlamps`:

- registration, the item, the pickaxe tag, the loot table and the recipe, and a dark cold starting state;
- every lamp state survives the save format (`NbtUtils`);
- a high-pressure lamp on redstone ramps 4, 8, 12, 15 at the scheduled steps;
- a hot high-pressure lamp switched off and on again stays dark until its cooling is done, then strikes;
- a low-pressure lamp restrikes at once;
- a mid-warm-up lamp has its step scheduled and its state round-trips;
- a high bay needs a ceiling and a wall pack needs a wall; the wall pack's lens turns with its facing.

## Not tested, and limits

- **Shaders were not run here.** The Iris and Complementary path (`-PbwrRenderMods=newest` with a shader pack) was not
  exercised for these lamps. The labPBR emission convention above is from secondary sources.
- **Save and reload** of a mid-warm-up lamp is covered by the state round-trip and the scheduled-tick check. A real
  save-and-reload of a world was not run for these blocks.
- **Warm-up colour** is not shown (see Behaviour).
- **Time scale.** Warm-up and cooling are game-time compressions of the real times, chosen to sit in the 20 to 40 second
  range the design asked for.
