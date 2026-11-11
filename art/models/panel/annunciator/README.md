# Annunciator family: Columbia's tile box and a pushbutton station

Two models for the annunciator of the control boards, built by `build.py` and exported
through `../panel_export.py`:

    blender --background --factory-startup --python art/models/panel/annunciator/build.py
    python3 art/models/panel/annunciator/build.py                 (bpy from PyPI)
    ... build.py -- --only ann_window --no-preview

The script writes `mod/src/main/resources/assets/bwr/models/panel/<id>.json` and renders
previews to `tmp/panel-models/annunciator/` (git-ignored), or to the folder named by
`BWR_PANEL_PREVIEW_DIR`. EEVEE needs a GPU; on a machine without one set
`BWR_PANEL_PREVIEW_ENGINE=CYCLES`.

- `ann_window_front|three_quarter|side.png`, `ann_window_lit_*`: the tile model alone.
- `ann_box_7x5_front|below.png`, `ann_box_7x5_lit_*`: a whole box of 7 x 5 split tiles
  (70 alarms) with its plate margins, numerals, nameplate and latch, assembled the way
  the game does it, dark and with a few alarms lit; square on, and from below as an
  operator sees it. Preview only: the box is not a model file. The text in these
  pictures is Blender's; in the game it is the Minecraft font.
- `ann_control_front|below|side.png`, `ann_control_pressed_*` with all four buttons in.

## Sources

Three **owner-supplied photographs, not in the repository** (their copyright status is
unknown, so they are cited, not copied):

| | What it shows | Used for |
| --- | --- | --- |
| P3 | Box "ANN PANEL P800-C5" on Columbia Generating Station's light-blue electrical board, well lit, nearly square on | Everything measured: see below |
| P1 | Box "ANN PANEL P800-C1", the same hardware, dim, seen from below | A check on P3 (same grid, numerals, nameplate, latch); that the plate has a thick edge and the tiles stand off it |
| P2 | A grey local panel of a plant the owner did not name: an 8 x 6 box of the same kind of tray tile, white and unsplit, rows lettered A to F and columns numbered 1 to 8, a maker's badge, and four round pushbuttons below | The pushbutton station; that an unsplit tile has rim all round. **Not Columbia's control room.** The badge is not modelled |

**Measured on P3** (pixels on the photograph, as shares of the tile pitch; the camera is
slightly below and left of the box, so a few percent either way):

| What | Measured | In `build.py` |
| --- | --- | --- |
| Tile pitch | 143 x 107 px: 1.33 to 1 | `TILE_W` : `TILE_H` = 4 : 3 |
| Black gap between tiles | about 4 px | `GAP` 0.03 |
| Raised rim of the tray | about 10 px | `RIM` 0.07 |
| Plate margins: left, right, top, bottom | 40, 45, 30, 53 px | `MARGIN_*` 0.30, 0.31, 0.19, 0.375 |
| Dividing line of a tile | about 2 px | `DIVIDER` 0.02 (a little heavier, to read from a few blocks) |
| Margin numerals, cap height | about 12 px | `NUMERAL_CAP` 0.085 |
| Nameplate | 135 x 47 px, touching the bottom row, under the middle column | `NAMEPLATE_W` 0.95, `NAMEPLATE_H` 0.33 |
| Nameplate lettering, cap height | about 14 px | `NAMEPLATE_CAP` 0.095 |
| Latch | 28 px across, mid height of the right margin | `LATCH_R` 0.10 |

**Read off P3 and P1, not measured:** each tile is one moulded ivory tray, a raised rim
with rounded corners round a slightly darker recessed face; the corner radius is about
the rim's width; every tile is split by a thin dark horizontal line into an upper and a
lower alarm, each with its own one- or two-line black legend (a half can be blank, and
some halves are brighter or greyer than their partners: they are separate alarm points);
white numerals 1 to 10 down the left margin, one per alarm; "-1" to "-7" across the top,
one per column; a white two-line nameplate, "ANN PANEL" over the box's number; a round
bright-metal knob in the right margin; the plate is black and plainly thick.

**By eye:** how far the plate stands off the board, how far the rim stands off the face,
the latch's height, the nameplate's thickness, and the colours (ivory rim `E9E1C5`, face
`DDD5B8`).

**Not known:**

- **Any real dimension.** The models are in inches because the format is; the scale is
  nominal (`TILE_W` = 4 in, from comparing a tile with the edgewise meters under the box
  in P3, which is a guess at what those meters are). Only proportions were taken. The
  older figure in `CONTROL-PANELS.md`, "tile 2.8125 x 2.625 in", is nearly square and
  does not describe these tiles; it is not used here.
- **The maker and model** of the annunciator. No maker's name or logo is on either model.
- **What colour a window lights**, and whether Columbia uses coloured lenses at all. Only
  the unlit ivory is in the photographs. The game lights a window in the colour the
  player gave it, as it always has.
- **The control station in Columbia's control room**: where the acknowledge, silence,
  reset and test controls are and what they look like. The station model follows P2.
- **The canopy or header arrangement** above Columbia's main boards. Not modelled.
- Whether every Columbia box is 7 x 5, how boxes on the main control room boards
  (P601, P603 and so on) differ from these two on P800, and what the latch does.

Earlier search-result evidence (NRC-hosted Columbia examination material that could not
be opened) names windows as panel, box, row-column, for example `H13-P603.A7 2-5`, which
agrees with the numerals on these boxes: rows down the left, "-N" columns across the top.

## Models

| id | Component / style | Footprint (in, nominal) | Faces | Parts | Labels |
| --- | --- | --- | --- | --- | --- |
| `ann_window` | `annunciator`, style `columbia` | 4.0 x 3.0 | 31 | `face` (role `lamp`, tint `white`, 1 face); `rim_top`, `rim_bottom`, `rim_left`, `rim_right` (3 each: the tile's side, the rim's front, the step down to the face); `corner_tl`, `corner_tr`, `corner_bl`, `corner_br` (3 each: a two-flat rounded corner); `plate` (5) and `divider` (1), which the game does not draw | `upper`, `lower` (the middles of the two alarms; not used, see below) |
| `ann_control` | `annunciator_control`, style `station` | 10.0 x 2.5 | 532 | `rings` (four bright bezel rings), `tags` (four black legend plates with a light edge), and the buttons `cap_ack`, `cap_reset`, `cap_silence`, `cap_test`, each sliding 0.15 along -Z | `ack`, `reset`, `silence`, `test`, on the legend plates |

### The tile, and how the game uses it

The model is one whole tile on its piece of plate, split by the dividing line as
Columbia's are. Its footprint is one tile pitch, so the gap round the tile is half the
gap between two tiles.

**The game does not fit this model with one scale** as it does every other. One window
of the game is one alarm, which at Columbia is *half* a tile, and windows of several
sizes must tile a box. So `AnnunciatorTileBox` cuts the model at the outline of its
`face` and lays the pieces on the window's cells:

- the gap and the rim keep the share of the tile's width the model gives them, in both
  directions, so a corner stays round at every window size; the straight runs and the
  face stretch to the window;
- a window at least twice as wide as tall (8x3, 12x4, 16x6) is one alarm of a split
  tile: the upper one has the top corners and the top run, its side runs and its face
  reach its lower edge, and it draws half of the dividing line there; the lower one
  mirrors it. Down a column the half windows pair off, upper then lower. A half window
  with no partner, and a window of any other size, is a whole tile with rim all round;
- elevations are scaled so the model's face is at the procedural tile's (`TILE_Z`, 1.0
  of 1/16 block), which puts the rim at 1.3 and the plate at 0.7.

`tile_pieces()` in `build.py` is the same construction, and builds the preview box.

One alarm of a split tile is 18 quads (16 from the model, the plate under it and half
the dividing line); a whole tile is 26. The procedural window is 10 inside a box.

The face is recoloured by the game: ivory with 8 % of the window's colour while dark.
The lit face, the grey no-signal flag and the legend (up to four centred lines) are
drawn by the same rules as for the procedural window, over the face; the dark legend
is black. The label anchors only mark where the two legends of a tile go.

### Round the tiles (drawn in code)

The black plate, its margins, the numerals, the nameplate and the latch are not in a
model file: their sizes depend on the box. `AnnunciatorBox.java` holds the proportions
under the same names as `build.py` (`MARGIN_LEFT` ... `LATCH_H`); **change both
together**. Each window draws the plate under itself and a margin strip outside its own
cells on every side not joined to another window; `AnnunciatorBox.layout` says which
window of a box draws the nameplate (bottom row, under the box's middle) and the latch
(right column, at mid height), and gives every window its row and column for the
numerals. A 7 x 10 box (7 x 5 tiles) is 1,356 quads with its margins, nameplate and
latch; the same windows classic are 870.

### The pushbutton station

After P2: four round black pushbuttons, each in a bright bezel ring set straight in the
panel face (no backing plate), with a small black legend plate under it. P2 shows two
plates under each button (a legend and a tag number); the model has the one the game
prints on. Measured on P2 as shares of the button pitch: ring 0.5 across, button 0.33,
plate 0.8 x 0.2. Heights are by eye.

It is laid out as the procedural module, which is what keeps clicks right: the buttons
are in the four quarters of the width (a click picks the quarter), centred in the top
60 %, and the plates are in the band from 63 % to 86 % of the height. The footprint is
4 : 1 like the module's sizes (24x6, 16x4, 32x8), so the fit is exact; on the retired 2x1
size the station is centred, still the full width. The game prints ACK, RESET, SILENCE
and TEST in white at the label anchors. A button is black at rest; while its output is 1
it is in by 0.15 in and glows in the procedural module's colour for it (white, blue,
amber, green), so a held button still shows.

## In the game

- Annunciator window: **Style** `Classic (simple)` (the default) or `Columbia tile box
  (model)`; **Box name (nameplate)**, the second line of the box's nameplate, set on any
  window of the box.
- Annunciator control module: **Style** `Classic (simple)` (the default) or `Pushbutton
  station (model)`.

A style is saved (key `Style`) only when it is not classic, and the box name (key
`BoxName`) only when not empty, so existing saves and new components are unchanged until
a player sets them. A missing or unusable model file draws the classic look.
`CONTROL-PANELS.md` ("Columbia style") has the player's side: the half height sizes, the
room a box needs round it, and the limits.
