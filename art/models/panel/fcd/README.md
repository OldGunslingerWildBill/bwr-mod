# Family E: Full Core Display, rod select matrix, rod motion pushbutton

Models for the `full_core_display` (styles `columbia`, `browns_ferry`) and `rod_select_matrix`
(style `columbia`) components and for the ordinary rod motion pushbuttons of
`FULL-CORE-DISPLAY-SPEC.md`. Built by `build.py` and exported through `../panel_export.py`
(helpers in `../panel_geo.py`):

    blender --background --factory-startup --python art/models/panel/fcd/build.py
    blender ... --python art/models/panel/fcd/build.py -- --only fcd_tile_columbia --no-preview

The script writes `mod/src/main/resources/assets/bwr/models/panel/<id>.json` and previews into
`tmp/panel-models/fcd/` (git-ignored):

- `<id>_front|three_quarter|side.png` for every model, `<id>_off_*` / `<id>_lit_*` lamp states
  (off is dimmed to 30 percent as the game does, lit is full-bright), `<id>_legends_*` with sample
  text at the label anchors (preview only, never exported).
- `rod_select_button_pressed_*`, `pb_guarded_yellow_pressed_*`: cap at full travel.
- `fcd_tile_columbia_tiled_6x6_*` and `_8x8_*`: 3 x 3 and 4 x 4 rods as 2 x 2 blocks (A B / C D),
  mirrored by column and row parity, so the blank D tiles of four neighbouring rods meet in a
  2 x 2 cluster, with a yellow LPRM dot at each cluster (preview-only), on a black backplate.
- `fcd_bf_modules_grid_*`: 4 x 3 Browns Ferry rod modules with two LPRM strips and the grid lines
  and edge labels of the photo.

Every dimension is in the table at the top of `build.py` with its source tag. There are no
published drawings of any of this hardware: sizes are scaled off the photos.

## Models

| id | Serves | Footprint (in) | Faces / budget | Parts (role) | Moving part | Label anchors |
| --- | --- | --- | --- | --- | --- | --- |
| `fcd_tile_columbia` | FCD `columbia`: lamp tile | 1.00 x 1.00 | 35 / 40 | `bezel` (paint), `upper` (lamp), `lower` (lamp) | none | `upper`, `lower` (0.17) |
| `fcd_tile_blank` | FCD `columbia`: blank D tile | 1.00 x 1.00 | 17 / 20 | `bezel` (paint), `face` (paint) | none | none |
| `fcd_bf_rod_module` | FCD `browns_ferry`: rod module | 2.40 x 1.95 | 69 / 120 | `body` (paint), `digits` (glass), lamps `accum`, `scram`, `select`, `drift`, `full_in`, `full_out` | none | `digits` (0.56), `accum`, `scram`, `select`, `drift` (0.17), `full_in`, `full_out` (0.11) |
| `fcd_bf_lprm_strip` | FCD `browns_ferry`: LPRM string | 1.70 x 3.30 | 58 / 80 | `body` (paint), `lvl_a` to `lvl_d` (lamp) | none | `coord`, `top` (0.20) |
| `rod_select_button` | `rod_select_matrix` `columbia`: one per rod | 1.00 x 1.00 | 26 / 40 | `bezel` (paint), `cap` (lamp) | `cap` slides 0.08 along -Z | `legend` (0.17) |
| `pb_guarded_yellow` | rod motion pushbutton (INSERT / WITHDRAW / CONT ...) | 2.00 x 2.00 | 71 / 150 | `bezel` (paint), `cap` (lamp, tint `amber`), `guard` (glass) | `cap` slides 0.15 along -Z | `legend` (0.24) |

All footprints are measured from the top-left corner with y down in the exported JSON. The tile
and button models are left-right and top-bottom symmetric, so the game's mirroring of the A B / C D
blocks changes only which legend and tint each half gets, never the geometry.

**Lamp lenses** are modelled in neutral white (`F0F0E0`), role `lamp`, `bwr_tint` `white`; the game
colours each half or legend. The one exception is the cap of `pb_guarded_yellow`, which is
amber (`FFB000`, tint `amber`) like the existing `pb_illuminated`, because it is a yellow button.

## Columbia tile (`fcd_tile_columbia`, `fcd_tile_blank`)

Source: close-up of the Columbia FCD (owner-supplied photos of Columbia Generating Station and
Browns Ferry 3 panel 3-9-5). Each tile is a thin silver rim, a black bezel and a recessed white
window split into an upper and a lower half that light independently. The tile is 0.94 in over
a 1.00 in pitch, so a 0.06 in gap lets the backplate show between tiles as in the photo. The
window is 0.72 in wide, larger than in the photo (about 60 percent of pitch there), so legends
stay readable at a few blocks; the spec asks for a ~1.0 in face. The blank tile is the same rim
and bezel with a flat black recessed face (a separate `face` part).

## Browns Ferry rod module (`fcd_bf_rod_module`)

Source: Browns Ferry 3 panel 3-9-5 (digital rod modules). Zoomed crops show a pale raised plate
with a black digit window on top and, beneath it, **two wide windows, each split in an upper and
a lower half**. The layout used:

| Column | Upper half | Lower half |
| --- | --- | --- |
| left | `accum` | `scram` |
| right | `select` (shows the rod coordinate in the photo) | `drift` |

The photo shows no separate FULL IN / FULL OUT windows: the 2-digit readout is the indication (`00`
green, `48` red). The spec still wants `full_in` and `full_out` lamps, so they are two small
lamp windows flanking the digit window, where the photo shows two small screw dots. The digit
window is a dark `glass` part with its label anchor `digits`; the game draws the digits behind
the glass. Real size is not known; 2.40 in wide is chosen so the digit window is 1.4 in.

## Browns Ferry LPRM strip (`fcd_bf_lprm_strip`)

Source: the LPRM strips of panel 3-9-5. The real strip is **2 columns x 4 rows**: a lamp per
level on the left (labelled `D LO` ... `A LO`, lit white) and a dark HI window on the right. The model
has the 4 level lamps `lvl_a` to `lvl_d` (A on top, as the spec says; the photo has D on top and A
on the bottom), with the right-hand windows as dark, unlit paint. A pale yellow coordinate plate sits
under the strip (anchor `coord`), and 0.35 in is left empty above it for the `top` anchor (SRM-A,
IRM-C ...); the game draws that text on the panel.

## Rod select button (`rod_select_button`)

Source: the Columbia rod select matrix photo. A small square button with a silver bezel and a
slightly raised cap; the selected rod's button lights white (cap role `lamp`, neutral white).
The cap travels 0.08 in into the panel.

## Guarded yellow pushbutton (`pb_guarded_yellow`)

Source: the RMCS strips of the same photo. A large square yellow cap in a thin black bezel under
a clear guard cube with a pale rim: the guard is a `glass` part (drawn translucent), its rim and
posts are part of `bezel`. The cap travels 0.15 in. It sits straight on the black strip, with no
nameplate, in a 2.0 in square footprint like the other `pb_*` models.

## Sources

Owner-supplied photos of Columbia Generating Station and Browns Ferry 3 panel 3-9-5
(`columbia_fcd_closeup.png`, `columbia_fcd.png`, `columbia_rod_select_matrix.png`,
`browns_ferry_fcd.jpg`). They are not in the repo. No manufacturer drawings were used.
