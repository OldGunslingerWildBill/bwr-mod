# Family F: Yokogawa DX1000 and DX2000 paperless recorder bezels

Models for the `paperless_recorder` component (`DX1000-RECORDER-SPEC.md`, section 7 is the model / code
contract and is followed exactly). Built by `build.py` and exported through `../panel_export.py`:

    blender --background --factory-startup --python art/models/panel/recorders/build.py
    blender ... --python art/models/panel/recorders/build.py -- --only yokogawa_dx1000 --no-preview

The script builds, runs its own checks (parts present, screen is one 4:3 quad recessed behind the bezel face,
every part inside the footprint, key hit boxes (part bounds) disjoint, keys clear of the closed cover, the cover
open at 100 degrees still inside the footprint to 0.08 in), exports
`mod/src/main/resources/assets/bwr/models/panel/<id>.json`, and renders previews into
`tmp/panel-models/recorders/` (git-ignored): `<id>_front|three_quarter|side.png`, `<id>_legends_front.png`
(preview-only sample text at the label anchors, with a sample screen picture on `screen`), `<id>_far_front.png`
(160 px), and for the DX2000 `yokogawa_dx2000_open_*` with the cover opened 100 degrees.

All numbers below are exported model space: inches, x right, y DOWN from the footprint's top-left, z out of the
panel (z = 0 is the panel the recorder is mounted in). `build.py` authors in millimetres (`P()` converts).

| Model id | Style | Footprint | Cells | Faces / budget |
| --- | --- | --- | --- | --- |
| `yokogawa_dx1000` | `yokogawa_dx1000` | 5.66929 x 5.66929 in (144 mm square) | 32 x 32 (2 x 2 blocks) | 399 / 1500 |
| `yokogawa_dx2000` | `yokogawa_dx2000` | 11.33858 x 11.33858 in (288 mm square) | 48 x 48 (3 x 3 blocks) | 674 / 1500 |

Bezel colour is `4A5157`, "charcoal gray light" (Munsell 10B3.6/0.3, [UM] 13.6); the LCD frame is a shade darker,
keys are a dark cap in a lighter outline ring, the screen is `101418`.

## Parts

Roles are `paint` unless stated. Bounds are min .. max per axis in inches. Hit boxes for keys are the (x, y)
bounds of each part.

### `yokogawa_dx1000`

| Part | Role | Faces | x | y | z | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| `bezel` | paint | 23 | 0 .. 5.669 | 0 .. 5.669 | 0 .. 0.984 | case, front face with LCD and strip openings, sloped LCD frame |
| `screen` | paint | 1 | 0.555 .. 5.114 | 0.669 .. 4.091 | 0.669 | one flat quad, 4.559 x 3.422 in (4:3), 0.315 in behind the face |
| `strip_plate` | paint | 1 | 0.417 .. 4.053 | 4.409 .. 5.561 | 0.937 | floor of the key strip (recessed 0.047 in) |
| `key_soft1` .. `key_soft5` | paint | 18 each | x1 0.628 .. 1.194, x2 1.316 .. 1.883, x3 2.004 .. 2.571, x4 2.692 .. 3.259, x5 3.365 .. 3.932 | 4.604 .. 4.844 | 0.937 .. 1.024 | one row, left to right |
| `key_start` | paint | 18 | 0.657 .. 1.106 | 5.113 .. 5.322 | 0.937 .. 1.024 | |
| `key_stop` | paint | 22 | 1.283 .. 1.527 | 5.095 .. 5.339 | 0.937 .. 1.024 | round |
| `key_user` | paint | 18 | 1.694 .. 2.072 | 5.113 .. 5.322 | 0.937 .. 1.024 | |
| `key_func` | paint | 18 | 2.143 .. 2.521 | 5.113 .. 5.322 | 0.937 .. 1.024 | |
| `key_esc` | paint | 18 | 2.697 .. 3.075 | 5.113 .. 5.322 | 0.937 .. 1.024 | |
| `key_menu` | paint | 18 | 3.161 .. 3.539 | 5.113 .. 5.322 | 0.937 .. 1.024 | |
| `key_fav` | paint | 18 | 3.694 .. 4.112 | 5.056 .. 5.379 | 0.937 .. 1.024 | |
| `ring_base` | paint | 46 | 4.114 .. 5.248 | 4.321 .. 5.455 | 0.984 .. 1.016 | plate behind the DISP/ENTER ring, centre (4.681, 4.888), r 0.567 |
| `key_disp` | paint | 30 | 4.398 .. 4.965 | 4.605 .. 5.172 | 1.016 .. 1.063 | round DISP/ENTER |
| `key_up` | paint | 15 | 4.394 .. 4.968 | 4.347 .. 4.585 | 1.016 .. 1.060 | annular sector, with a marker triangle |
| `key_down` | paint | 15 | 4.394 .. 4.968 | 5.191 .. 5.430 | 1.016 .. 1.060 | |
| `key_left` | paint | 15 | 4.140 .. 4.378 | 4.601 .. 5.175 | 1.016 .. 1.060 | |
| `key_right` | paint | 15 | 4.984 .. 5.223 | 4.601 .. 5.175 | 1.016 .. 1.060 | |
| `power_led` | lamp, tint `red` | 18 | 0.477 .. 0.567 | 4.516 .. 4.602 | 0.937 .. 0.984 | modelled red `E02020` |

Labels (id, anchor x y, cap height in, align): `maker` (0.354, 0.236, 0.134, left), `model` (5.315, 0.236, 0.127,
right), `start` (0.881, 5.217), `stop` (1.405, 5.217), `user` (1.883, 5.217), `func` (2.332, 5.217), `esc` (2.886,
5.217), `menu` (3.350, 5.217), `fav` (3.903, 5.217) all 0.059 centred, `disp` (4.681, 4.832, 0.056), `enter` (4.681,
4.968, 0.041).

### `yokogawa_dx2000`

| Part | Role | Faces | x | y | z | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| `bezel` | paint | 29 | 0 .. 11.339 | 0 .. 11.339 | 0 .. 1.108 | includes the power indicator box and remote sensor window at the left |
| `screen` | paint | 1 | 1.468 .. 9.870 | 1.516 .. 7.815 | 0.484 | one flat quad, 8.402 x 6.299 in (4:3), 0.6 in behind the face |
| `strip_plate` | paint | 1 | 1.280 .. 8.622 | 8.307 .. 11.043 | 0.661 | floor of the key-strip pocket (0.413 in deep) |
| `key_soft1` .. `key_soft7` | paint | 18 each | x1 1.661 .. 2.331, x2 2.681 .. 3.350, x3 3.701 .. 4.370, x4 4.720 .. 5.390, x5 5.740 .. 6.409, x6 6.760 .. 7.429, x7 7.780 .. 8.449 | 8.913 .. 9.252 | 0.661 .. 0.819 | |
| `key_start` | paint | 18 | 1.754 .. 2.435 | 9.661 .. 9.984 | 0.661 .. 0.819 | |
| `key_stop` | paint | 22 | 1.839 .. 2.272 | 10.185 .. 10.618 | 0.661 .. 0.819 | round |
| `key_esc` | paint | 18 | 2.799 .. 3.461 | 9.661 .. 9.984 | 0.661 .. 0.819 | |
| `key_menu` | paint | 18 | 3.610 .. 4.272 | 9.661 .. 9.984 | 0.661 .. 0.819 | |
| `key_user` | paint | 18 | 2.799 .. 3.461 | 10.465 .. 10.787 | 0.661 .. 0.819 | |
| `key_func` | paint | 18 | 3.610 .. 4.272 | 10.465 .. 10.787 | 0.661 .. 0.819 | |
| `keypad` | paint | 216 | 5.165 .. 7.468 | 9.496 .. 10.953 | 0.661 .. 0.819 | 12 keys (3 x 4), decorative, one part |
| `key_fav` | paint | 18 | 7.874 .. 8.543 | 10.325 .. 10.817 | 0.661 .. 0.819 | |
| `ring_base` | paint | 46 | 8.661 .. 10.827 | 8.563 .. 10.728 | 1.075 .. 1.134 | ring plate, centre (9.744, 9.646), r 1.083 |
| `key_disp` | paint | 30 | 9.181 .. 10.307 | 9.083 .. 10.209 | 1.134 .. 1.220 | |
| `key_up` | paint | 15 | 9.196 .. 10.292 | 8.612 .. 9.067 | 1.134 .. 1.209 | |
| `key_down` | paint | 15 | 9.196 .. 10.292 | 10.224 .. 10.680 | 1.134 .. 1.209 | |
| `key_left` | paint | 15 | 8.710 .. 9.165 | 9.098 .. 10.194 | 1.134 .. 1.209 | |
| `key_right` | paint | 15 | 10.323 .. 10.778 | 9.098 .. 10.194 | 1.134 .. 1.209 | |
| `power_led` | lamp, tint `red` | 18 | 0.772 .. 0.921 | 9.102 .. 9.244 | 1.106 .. 1.142 | on the indicator box, left of the strip, never covered |
| `key_cover` | paint | 11 | 1.319 .. 8.583 | 8.386 .. 10.957 | 0.917 .. 1.065 | hinged key-panel cover, closed pose; includes the paper label strip |
| `cover_tab` | paint | 6 | 5.000 .. 6.339 | 8.031 .. 8.661 | 0.917 .. 1.166 | click target, centred, straddles the LCD frame and the cover |

Labels: `maker` (0.551, 0.539, h 0.205, left), `model` (10.787, 0.413, 0.194, right), `series` (10.787, 0.768, 0.102,
right; text DAQSTATION), `start` (2.094, 9.823), `stop` (2.055, 10.402), `esc` (3.130, 9.823), `menu` (3.941,
9.823), `user` (3.130, 10.626), `func` (3.941, 10.626), `fav` (8.209, 10.571) all h 0.106, `disp` (9.744, 9.533,
0.101), `enter` (9.744, 9.803, 0.074), `pad1` .. `pad12` (columns x 5.469 / 6.315 / 7.165, rows y 9.646 / 10.016 /
10.394 / 10.803, h 0.130, characters `1 2 3 / 4 5 6 / 7 8 9 / - 0 .` in reading order), `cover_label` (4.941, 9.528,
0.197) on the cover's label strip.

### The key-panel cover (DX2000)

- **Part `key_cover`**, role `paint`, closed pose as listed. It spans x 1.319 .. 8.583, y 8.386 .. 10.957 (213 mm to
  278.3 mm down the face) and is 0.138 in thick; its front is 0.02 in behind the bezel face, so it sits flush in the
  pocket and hides every soft key, START/STOP/USER/FUNC/ESC/MENU, the keypad and Favorite. It does NOT hide the power
  LED, the remote sensor, the DISP/ENTER ring or the arrows (they are beside it), as on the real unit.
- **Pivot** `[5.66929, 10.95669, 1.05512]`: the hinge line along the cover's front-bottom edge (x is the face centre,
  y 278.3 mm, z the cover's front plane).
- **Axis** `[-1, 0, 0]`. With the game's ordinary rotation about the pivot and axis in the y-down frame, a POSITIVE
  angle opens it: 0 = closed, +100 degrees = open, the top edge swinging toward the operator and down (a negative
  angle would swing it into the panel). Rotating by +100 degrees puts its farthest vertex at y 11.40, z 3.61, that is
  0.065 in beyond the footprint's lower edge (the real cover also hangs below the bezel). 95 degrees fits inside it.
- **`cover_tab`** has no pivot: it is a fixed latch grip on the bezel that stays where it is whatever the cover
  does, so it is always a click target. When the cover is open the tab simply stays above the strip.
- Bounds overlap in the face plane: the cover over every key behind it, and the tab over the cover's top edge. While
  closed, the cover (and tab above it) must win the click over the keys; when open the keys win.

## Reference drawings and photographs (not committed; cited)

All reads from Yokogawa's own documents; local copies in the session scratch folder.

- **DX2000 Operation Guide IM 04L42B01-02E**
  - p.10 (PDF page 15) "Names of Parts", front view with the key panel opened and with the front cover opened:
    bezel 358 x 360 drawing units for 288 mm; LCD frame opening 5.6 mm from the sides, 27.4 mm from the top;
    4:3 screen 74 percent of the width, 38 mm from the top; key strip below; tab at the centre of the cover's upper
    edge straddling the frame; power indicator and remote control sensor box at the left; label strip on the cover.
  - p.14 (PDF page 19) "Panel Keys": every key's position and size (7 soft keys; START over STOP; ESC|MENU over
    USER|FUNC; the 3 x 4 keypad; Favorite; the DISP/ENTER disc inside four arrow keys). Measured from the
    300 dpi render against the soft key pitch.
  - p.51 (PDF page 56) "External Dimensions": 288 mm front (295.2 mm incl. the mounting flange), 27.3 mm bezel
    projection, 281 mm panel cut-out.
  - p.13 (PDF page 18): 3/4 views, the opened operation cover swinging forward and down, depth of the LCD frame.
- **DX2000 User's Manual IM 04L42B01-01E**: 13.2 (10.4 inch 640 x 480 TFT), 13.6 (bezel colour "charcoal gray light"),
  13.7 (points to the Operation Guide for dimensions).
- **DX1000/DX1000N User's Manual IM 04L41B01-01E**: 4.1 "Operations Using Other Keys" (page 4-2, PDF page 134), the
  key drawing with five soft keys, START, STOP, USER, FUNC, ESC, MENU, Favorite and the DISP/ENTER ring: relative
  positions and sizes, scaled so strip plus ring are 123 mm of the 144 mm front. 12.2 (5.7 inch 320 x 240 LCD).
  12.7 "External Dimensions" (PDF page 325) only refers to the DX1000/DX1000N Operation Guide (IM 04L41B01-02E),
  which was not available.
- **Yokogawa FX1000 Paperless Recorder bulletin 04L21B01-01EN** (cover page photograph, fetched from a reseller's copy
  of the bulletin): the closed key-panel cover, the tab at its top centre, the ring beside the cover, the wordmark
  at the top left. The FX1000 is the sibling recorder with the same front family.
- Yokogawa's DX1000/DX2000 product page (yokogawa.com) was searched; it has no dimensions or front drawing.

## Judgement calls and what is assumed

- **DX1000 has two key rows, not one.** The 4.1 drawing shows the five soft keys on top and the other keys in a
  second row inside the same plate, so the model follows the drawing.
- **DX1000 front proportions are scaled, not read.** No DX1000 dimension drawing is on disk. The strip and ring come
  from the key drawing; the LCD opening, top band (for the wordmark) and bezel projection (25 mm) are assumed from
  the DX2000 proportions.
- **Assumed depths** (neither drawing shows them): LCD recess 15 mm (DX2000) and 8 mm (DX1000); key height 4 mm
  (DX2000) and 2.2 mm; pocket depth 10.5 mm and 1.2 mm; cover thickness 3.5 mm; ring plate 1.5 mm proud.
- **Arrow keys** are four annular sectors of 64 degrees with wide diagonal gaps (the real ones have thin gaps) so that
  their bounding boxes, which the code turns into hit boxes, do not overlap each other or `key_disp`. Each carries
  a modelled marker triangle (a lighter face), so the arrows are geometry, not text labels.
- **No label anchors for the soft keys** (their legends are drawn on the screen) or for the arrows.
- **Favorite**: the real key shows a pencil-and-note icon; the anchor `fav` is where the game can draw an icon or
  the text FAV.
- **Wordmark and model text** are label anchors only, with no logo artwork: `maker` (YOKOGAWA), `model` (DX1000 /
  DX2000), `series` (DAQSTATION, DX2000 only).
