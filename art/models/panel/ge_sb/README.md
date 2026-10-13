# GE SB series control switches (SB-1 / SB-9 / SB-10)

Family A of `PANEL-MODELS-SPEC.md`. `build.py` builds, checks, exports and
previews all of them:

    blender --background --factory-startup --python art/models/panel/ge_sb/build.py
    (append "-- --no-preview" to skip the renders, or "-- <model id>" to build one)

The SB-1, SB-9 (heavy-duty mechanism) and SB-10 (SB-1 with pull contacts,
"PULL TO LOCK") all use the same escutcheons and handles, so one set of models
covers all three. Output: `mod/src/main/resources/assets/bwr/models/panel/ge_sb_*.json`;
previews in `tmp/panel-models/ge_sb/` (not committed).

## Models

| Model id | Component / style | Footprint (in) | Faces | Parts |
| --- | --- | --- | --- | --- |
| `ge_sb_target_pistol` | `control_switch` "ge" | 4.4 x 6.6 (2:3) | 608 | body, target (flag), handle |
| `ge_sb_standard_oval` | `rotary_selector` "ge" | 3.5 x 4.375 (4:5) | 237 | body, handle |
| `ge_sb_standard_knurled` | `rotary_selector` "ge" (variant) | 3.5 x 4.375 | 299 | body, handle |
| `ge_sb_standard_lever` | `rotary_selector` "ge" (variant) | 3.5 x 4.375 | 234 | body, handle |
| `ge_sb_keyed` | `key_switch` "ge" | 3.0 x 4.0 (3:4) | 377 | body, handle |

The three selector variants share one footprint, sized for the lever's full
sweep, so swapping handle variants never changes the size of the plate.
`build.py` rotates every handle through all of its positions and fails if any
vertex leaves the footprint.

**ge_sb_target_pistol** is the SB-1 breaker control switch. It has a 2.75"
square target escutcheon with a raised bright border, a black field, bright pads
in the two lower corners and four screws. Above the shaft is the sector-shaped
target window: a bright frame round the `target` part, which has role `flag`.
Below that is a bright shaft bushing. The black pistol-grip handle has a
0.62"-diameter barrel that comes out about 0.85" from the plate, then a grip
that hangs down to 2.64" below the shaft, with a rounded elbow, a slight
outward rake and a finger swell toward the panel. The handle sticks out about
2.1" from the panel. The shaft is 2.85" below the top of the footprint.

**ge_sb_standard_\*** is the 2-5/8" x 2-9/16" x 3/16" standard escutcheon:
black, with a bright bevelled edge and four screws at the drilling positions.
The shaft is 1-1/4" below the top edge. Three handles fit it:
- **oval**: a 1.5" x 0.74" head on a 0.54" shank, with a white pointer arrow.
- **knurled**: a 1.3" knob with ten scallops, a 0.72" neck and a white pointer
  arrow.
- **lever**: a blade 1.56" long that hangs down, thick at the hub and thinner
  toward the tip, with a white pointer on the hub that points up.

**ge_sb_keyed** is the standard plate with the 1-3/8" removable-handle opening
and four keyway notches (at 12, 1:30, 3 and 6 o'clock). The removable handle is
shown inserted: a bright bayonet collar that fills the opening (the lock
cylinder face), a black shank and a black oval wing with a white pointer.

## Moving parts and conventions

Every handle is a separate part called `handle`. Its pivot is on the shaft
centre at the panel face, and its axis is `[0, 0, 1]`.

Rotation sense: in the exported frame (x right, y down, z out), a positive
angle about +z, applied with the usual rotation formula to y-down
coordinates, turns the handle **clockwise as the operator sees it**. Label
`rot` uses the same sense.

| Model | Rest (as exported) | Positions (exported-frame angle) |
| --- | --- | --- |
| target_pistol | NORMAL: grip straight down | CLOSE **+45** (clockwise: the grip swings to the lower left and its top points at the CLOSE legend). TRIP **-45** (the grip swings to the lower right). With the mod's ControlSwitchData `position` (-1 CLOSE, 0 NORMAL, +1 OPEN), angle = -45 x position. |
| standard_* | pointer at 12 o'clock (GE position 3) | GE position k (1 = 9 o'clock, numbered clockwise in 45 deg steps, as on GE's escutcheons) is at **(k - 3) x 45**. Detents are every 45 deg. Every one of the 8 positions fits the footprint. A suggested layout for N positions, centred on 12 o'clock: 2 -> pos3, pos4; 3 -> pos2..pos4; 4 -> pos2..pos5; 5 -> pos1..pos5; 6 -> pos1..pos6. |
| keyed | OFF: pointer at 12 o'clock | ON **+45** (1:30), as on GE's KEYED escutcheon (OFF at 12, ON at 1:30). The key is shown inserted; it is not modelled as removable. |

Rest orientation of the pistol grip: **straight down at NORMAL**. This follows
the 10AA124 front-view photo of the breaker control switch, the handle chart,
the SB-1 photos with handles fitted, and GEF-2357K fig. 1. One catalog photo
shows the grip horizontal, but it is posed. The pointer sense (the top of the
handle points at the legend, so the grip sits opposite it) comes from GE's
escutcheon engravings and the SB-1 data sheet: "handle turned to the right
(45°) → red target at 12 o'clock".

**Target flag:** the `target` part (role `flag`) is a flat sector behind the
window. It is exported green (`20B040`, after TRIP). The renderer should show
red after CLOSE and green after TRIP, as the SB-1 data sheet describes ("shows
last operated position"). Black is a reasonable choice for an unknown state.

## Label anchors

These are text anchors only; the game draws the legends.

- **target_pistol:** `caption` above the escutcheon (h 0.16);
  `left` = TRIP (rot -55, h 0.13); `right` = CLOSE (rot +55, h 0.13);
  `lower_left` = "PULL TO LOCK" (h 0.085; on the real plate it is two lines,
  PULL / TO LOCK); `bottom` = maker legend "GENERAL ELECTRIC" (h 0.085). The
  grip hangs in front of the middle of the maker legend, as on the real
  switch.
- **standard_\* and keyed:** `pos1`..`pos8` at 45 deg steps in GE numbering
  (pos1 at 9 o'clock, pos3 at 12, pos5 at 3, pos7 at 6). The odd positions sit
  at radius 1.0", the diagonals at 0.92" so they clear the screw heads (h 0.11).
  `caption` is above the plate (h 0.15).

## Colours

Spec palette: escutcheon `141414`, bright trim, screws and target frame
`B8BCC0`, phenolic handles `1A1A1A`. Two additions make the models readable at
a distance:
- the handle bevels, and the two rounded front edges of the pistol grip, are
  `4A4A4A`. This stands in for the gloss highlight on black phenolic, so a
  black handle can still be picked out against the black plate in flat game
  lighting.
- the handle pointer marks are white (`F2F0E8`). The catalog OVAL and KNURLED
  photos show a light pointer mark; the lever's mark is an assumption.

The keyed opening's floor, seen through the notches, is grey metal `6E7276`.

## Red handle variant: not built

`ge_sb_target_pistol_red` was skipped because no GE source supports it:
- GEF-2357K Table 1 is titled "Black Handles" and lists every SB-1 handle.
- The SB-1 data sheet says "8 types of fixed handles (black only)".
- The SBM data sheet says "eight types of fixed, black handles".
- The SB-9 is "normally furnished with a large black pistol grip".

The red/green indication on these switches comes from the target flag and the
separate indicating lamps.

## Deviations from the spec and assumptions

- The **target escutcheon size** (2.75" square) is not dimensioned in the
  sources. It is scaled from the TARGET photo so that the two upper screws sit
  on the 2"-apart holes of the SB-1 drilling (GEH-908H fig. 4). The SB-1 3-hole
  plate outline in 10AA124 scales to the same size. Window, screw and pad
  positions come from the same photo.
- The **pistol grip** depth and barrel length come from the 10AA124 side view
  and the photos. Its length (2.6" from shaft to tip) follows the spec and the
  10AA124 front photo scaled to the escutcheon.
- **Not modelled:** the GE logo disc between GENERAL and ELECTRIC (it would sit
  under the drawn maker text), the catalog number engraved on the right edge,
  the pull-to-lock axial travel (SB-10), the lever's grip grooves, and
  everything behind the panel.
- The label ids `lower_left` and `bottom` are new; the exporter docstring
  lists `caption/left/right/top/pos*`.

## Sources

The references are cited here; the images are not in the repo.
- GE 10AA124 breaker control switch catalog. Local copy in the cr-refs scratch
  folder; crop ids from `aa124\`:
  - handle types, 94/95;
  - escutcheons STANDARD / TARGET / KEYED, 97/98/99;
  - SB-1 with target escutcheon, 115;
  - breaker control switch front view, 169;
  - SB-1/SB-10 photos with pistol grips, 88/106/116/123-125/168/170/845-848;
  - handle chart, 142;
  - SB-1 plate outline and escutcheon position numbering 1..8, 255/256;
  - standard escutcheon outline and drilling, 285;
  - pistol grip side view and SB-1 drilling, 297;
  - keyed escutcheon and removable handle, 93/266/267.
- GE GEH-908H, Types SB-1, SB-7, SB-10 instructions: fig. 1-3 (construction,
  fixed and removable handle mounting), fig. 4 (panel drilling); the
  operation text (SB-10 = SB-1 with pull contacts).
- GE Vernova GEF-2357K, SB-1 Control Switches renewal parts (gevernova.com):
  Table 1 "Black Handles", fig. 1 (SB-1 with target escutcheon and pistol grip;
  handle chart).
- GE Grid Solutions "Model SB-1 Control & Transfer Switch" data sheet
  (electro-meters.com/wp-content/uploads/pdf/ge/sb1.pdf): escutcheon types,
  target colours by handle direction, black-only fixed handles.
- GE "Model SB-9" and "Model SBM" data sheets (electro-meters.com, same path,
  sb9.pdf and sbm.pdf): SB-9 escutcheons the same as SB-1 with a large black
  pistol grip; SBM fixed handles black.
