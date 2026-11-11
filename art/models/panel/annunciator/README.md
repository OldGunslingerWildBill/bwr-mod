# Annunciator family: lamp box window and control station

Two models for the annunciator of the control boards, built by `build.py` and exported
through `../panel_export.py`:

    blender --background --factory-startup --python art/models/panel/annunciator/build.py
    python3 art/models/panel/annunciator/build.py                 (bpy from PyPI)
    ... build.py -- --only ann_window --no-preview

The script writes `mod/src/main/resources/assets/bwr/models/panel/<id>.json` and renders
previews to `tmp/panel-models/annunciator/` (git-ignored), or to the folder named by
`BWR_PANEL_PREVIEW_DIR`. EEVEE needs a GPU; on a machine without one set
`BWR_PANEL_PREVIEW_ENGINE=CYCLES`.

- `ann_window_front|three_quarter|side.png`, and `ann_window_off_*` / `ann_window_lit_*`:
  one window, its lens as the game shows an amber window unlit (30 %) and lit.
- `ann_box_4x3_front|three_quarter.png`: twelve windows joined into one box, three lit,
  assembled from the window's parts the way the game does it. Preview only, not exported.
- `ann_control_front|three_quarter|side.png`, and `ann_control_pressed_*` with all four
  caps at full travel.

## What this is, and what it is not

**It is not a model of an identified product.** The owner asked for Columbia Generating
Station's annunciators. What could be established about them, from search results only
(no document could be opened: this environment could not fetch any web page or PDF):

| Claim | Evidence |
| --- | --- |
| Windows are named panel, box, row-column: `H13-P603.A7 2-5`, "P603 A7 1-8", "P603.A7 3.6 and 5.8". Boxes A7 and A8 are on the reactor control benchboard P603, and a box has at least 5 rows and 8 columns | [S] search-result excerpts of Columbia written examinations and scenario outlines hosted by the NRC (ML091030038; a 2004 scenario outline, ML043150315; a 2009 scenario, ML100910275). Not opened |
| Legends of that kind: "ROD OUT BLOCK" (A7 2-7), "APRM UPSCALE" (A8 2-6), "ROD OVERTRAVEL" (A7 1-8) | [S] the same excerpts |
| Alarm response procedures are filed per window ("ARI H13-P601-0016-G5") | [S] excerpt of a scenario outline (ML22165A326 or ML17164A060) |

**Not established:** the annunciator's make and model, the window and lamp box dimensions,
the lens colours used at Columbia and what they mean there, the legend layout on a
window, whether and how the boxes are labelled with row and column designations on the
board, what the operator's acknowledge / silence / reset / test controls look like or
where they are, and the canopy arrangement above the boards. The places that would say
(the FSAR chapter 7 annunciator description, the 1985 detailed control room design
review summary and its NRC safety evaluation ML17279A599, the 1983 human factors audit
ML20077N595, the exam files above, photographs) are all on hosts this environment
could not reach. No maker's data sheet was read either.

So the geometry is a generic lamp box, with one sourced dimension:

- **Window tile 2.8125 x 2.625 in**: `CONTROL-PANELS.md` (size table), which cites
  `CR-PANELS-BWR-RESEARCH.md`; that file is not in the repository, so its primary source
  was not re-read.
- **Everything else is assumed** (`[ASM]` in `build.py`) or chosen to line up with what
  the game already draws (`[GAME]`): the 3/8 in trim frame, the depths, the lens bevel.
- **The control station** is four 30.5 mm oiltight flush pushbuttons, the operators of
  `../pushbuttons` (GE Control Catalog Sec. 9, CR104P outline p.9-49; the octagonal ring
  is drawn 1.36 in across flats instead of 1.42 to stay inside the button row), on a
  plain plate with four screws. That Columbia's station is built this way is a guess.
  The cap colours (white, blue, amber, green) are the procedural module's, not sourced.

No maker's name or logo is on either model.

## Models

| id | Component / style | Footprint (in) | Faces | Parts | Labels |
| --- | --- | --- | --- | --- | --- |
| `ann_window` | `annunciator`, style `lampbox` | 3.5625 x 3.375 | 53 | `lens` (role `lamp`, tint `white`, 5 faces); `frame_top`, `frame_bottom`, `frame_left`, `frame_right` (4 each); `corner_tl`, `corner_tr`, `corner_bl`, `corner_br` (8 each) | `legend` (centre of the lens; not used, see below) |
| `ann_control` | `annunciator_control`, style `lampbox` | 10.0 x 2.5 | 685 | `body` (plate and screws), `bezels`, and the caps `cap_ack`, `cap_reset`, `cap_silence`, `cap_test`, each sliding 0.15 along -Z | `ack`, `reset`, `silence`, `test` under the caps |

### The window

A lens (the tile, 2.8125 x 2.625) with a broken edge, in a trim frame 0.375 wide whose
front stands 0.65 above the panel; the lens front is at 0.50. The frame is split into
four straight runs and four mitred corners so that the game can leave out what a
joined box does not have.

**The game does not fit this model with one scale** as it does every other. Windows
must tile a box with no space between them at every size (12x8, 16x12, 8x6, 8x8, 1x1,
2x2 cells) and on a sloped board, so `AnnunciatorLampBox` cuts the model in nine at the
lens's outline and stretches each piece onto the rectangles the procedural window
uses (`AnnunciatorBox.insets`):

- the lens fills the tile;
- a frame run is drawn only on a side not joined to a neighbour, and then reaches
  across the half gap to the neighbour's run;
- a corner is drawn only where both its sides are open;
- elevations are scaled so the lens front is the procedural tile's (`TILE_Z`, 1.0), which
  puts the frame front at the procedural bezel lip (1.3).

A window inside a box is its lens: 5 quads, as many as the procedural tile. A whole 5 x 8
box is 536 quads from the model against 530 procedural (both counted with the dark base
under each window); a window standing alone is 58 against 30.

The lens is recoloured by the game with the window's tile colour (dim when off). The lit
tile, the grey no-signal flag and the legend (up to four centred lines) are drawn by the
same code as for the procedural window, over the lens, so flash, lamp test and legends
behave identically. The `legend` anchor only marks the place.

Known limit: where a window is joined to a neighbour of a different size that stands
further out than it, the end of its frame run is open (the procedural lip is a closed
box). Inside corners of an L-shaped box use the procedural corner pieces, in the same
colours as the model's frame.

### The control station

Laid out as the procedural module, which is what keeps clicks right: the buttons are in
the four quarters of the width (a click picks the quarter), centred in the top 60 %, and
the legends ACK, RESET, SILENCE, TEST are printed by the game in the band from 63 % to
86 % of the height. The footprint is 4 : 1 like the module's sizes (24x6, 16x4, 32x8), so
the fit is exact; on the retired 2x1 size the station is centred, still the full width.
A cap slides 0.15 in and glows while its output is 1.

## In the game

Both component types have a **Style** setting in the wrench screen: `Classic (simple)`,
the procedural look and the default, or `Lamp box (model)`. The style is saved (key
`Style`) only when it is not classic, so existing saves and new components are unchanged
until a player picks it. A missing or unusable model file draws the classic look.
