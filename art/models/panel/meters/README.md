# Family C: GE switchboard meters

GE's switchboard instrument line, now sold by Yokogawa under the same model
numbers: the AB-40 round-scale meter and the Type 180 edgewise meter. All six
models are built by `build.py` and exported through `../panel_export.py`:

    blender --background --factory-startup --python art/models/panel/meters/build.py

Set `BWR_ONLY=ge_ab40,ge_180_vertical` to build only some of them. Previews are
written to `tmp/panel-models/meters/`: `<id>_front|three_quarter|side.png`, the
needle at each end and the middle (`<id>_min|mid|max_front.png`, plus
`<id>_max_three_quarter.png`), a 112 px "few blocks away" view
(`<id>_far_*.png`), and `<id>_labels_front.png` with sample legends drawn at
every label anchor.

All coordinates below are in the exported component space: inches, x right,
y DOWN from the footprint's top-left corner, z out of the panel.

| Model id | Component type, style | Footprint (in) | Faces |
| --- | --- | --- | --- |
| `ge_ab40` | dial_gauge "ge" | 4.33 x 4.33 (1:1) | 457 |
| `ge_ab40_red` | dial_gauge "ge", red pointer | 4.33 x 4.33 | 457 |
| `ge_180_vertical` | edgewise_gauge_vertical "ge" | 2.294 x 6.1173 (3:8) | 255 |
| `ge_180_vertical_red` | edgewise_gauge_vertical "ge", red pointer | 2.294 x 6.1173 | 255 |
| `ge_180_horizontal` | edgewise_gauge_horizontal "ge" | 6.1173 x 2.294 (8:3) | 255 |
| `ge_180_horizontal_red` | edgewise_gauge_horizontal "ge", red pointer | 6.1173 x 2.294 | 255 |

Each model has three parts: `body` (paint), `window` (glass) and `needle`
(role needle). Colours follow the spec palette: case black 1C1C1C, dial and
scale plates F2F0E8, black graduations 1A1A1A, pointer black 141414, white
F2F0E8 or Day-glo red E8402A, window E6EEF0.

## AB-40 round-scale meter (`ge_ab40`, `ge_ab40_red`)

The 4-1/4" AB/DB-40 metal-case switchboard meter. A black square cover
(4.33" square, rounded corners, rounded front edge, four corner screws) stands
0.86" proud of the panel. The round window shows a dark bezel cone round the
white dial. The 250 deg scale has 50 divisions with graduations on the outer
arc: minor ticks every division, longer ones every 5, and bold major ticks
every 10 (the five numbered intervals), the end ticks a little bolder. A
raised white centre platform covers the pointer root. The slotted white zero
adjuster sits in the centre of the window, where the catalog's cover drawing
and photographs show it.

- **Needle:** a black wedge from under the platform tapering to a knife-edge
  tip at r = 1.76". Part `needle`, pivot `[2.165, 2.165, 0.42]` (dial centre),
  axis `[0, 0, 1]`. It is authored pointing straight up, which is mid-scale.
  The angle is measured clockwise as the operator sees it: zero at -125 deg,
  full scale at +125 deg, so angle = -125 + 250 * fraction. In the exported
  y-down space this is the ordinary rotation matrix about +z,
  x' = x cos a - y sin a, y' = x sin a + y cos a (the same sense as
  `Shapes.spoke`).
- **Label anchors** (dial surface, z = 0.44): `scale0`..`scale5` (h 0.20,
  centred) on the inner scale at r = 1.24" at -125, -75, -25, +25, +75 and
  +125 deg. These are the major ticks, so labels go inside the arc, as on the
  real meter. `units` (h 0.15) is 0.80" below centre and `caption` (h 0.13)
  1.18" below centre, in the 110 deg gap at the bottom of the scale.
- **Colour-band track** (for the integration): the outer graduation band
  between r = 1.62" and 1.80" (the minor-tick zone), drawn just under the
  ticks at z = 0.426.

## Type 180 edgewise meter, vertical (`ge_180_vertical`, `ge_180_vertical_red`)

The 6" Type 180 single-edgewise meter with option 21, the black trim case.
The cover is 6.040" x 2.294" and bulges forward as a barrel: 1.065" in front
of the panel at mid-length, falling to 0.84" at the ends of the window. Side
trim rails and rounded end caps make up the case. Behind the domed window, as
in catalog note 4, the scale and legend are separate plates with a space for
the pointer between them:

- white scale plate on the left, with numerals at the left and graduations
  pointing right (50 divisions, majors every 10, mid ticks every 5);
- dark pointer channel;
- white legend strip on the right.

The slotted zero adjuster is in the bottom end cap.

- **Needle:** an arrowhead riding in the channel, its tip touching the
  graduations. White is the catalog's standard pointer; the `_red` model has
  the Day-glo red option 19. It is a sliding part: `travel` 4.448" (the
  catalog's standard scale length) along axis `[0, -1, 0]`, which is UP in
  exported space. It is authored at ZERO: the tip is at `(1.247, 5.28265)`,
  and full scale is at y = 0.83465. Position = rest + fraction * 4.448 along
  the axis.
- **Label anchors** (scale plate, z = 0.62): `scale0` (bottom) to `scale5`
  (top), h 0.22, right-aligned at x = 0.917, just left of the major ticks.
  `units` (h 0.20, rot 90, so it reads top to bottom like the "KILOWATTS"
  legend on the catalog photo) is at the centre of the legend strip.
  `caption` (h 0.13, centred) is on the top end cap at z = 0.86. That cap is
  black, so draw the caption light.
- **Colour-band track:** the scale plate's minor-tick zone, x 1.117 to 1.247,
  along the scale.

## Type 180 edgewise meter, horizontal (`ge_180_horizontal`, `ge_180_horizontal_red`)

The same cover turned 90 deg clockwise, matching the catalog's horizontal
photographs. The scale plate is on top, with numerals above graduations that
point down. Below it are the pointer channel, then the legend strip. The zero
adjuster is in the left end cap.

- **Needle:** travel 4.448" along `[1, 0, 0]`, authored at zero, with the tip
  at `(0.83465, 1.247)`. Full scale is at x = 5.28265.
- **Label anchors** (z = 0.62): `scale1`..`scale4` (h 0.22) are centred above
  their major ticks at y = 0.807. The end numerals are pulled in so that a
  three-digit full-scale value stays on the plate: `scale0` is left-aligned
  from just left of the zero tick, and `scale5` is right-aligned to just
  right of the full-scale tick. On the legend strip (y = 1.792, h 0.18),
  `caption` is left-aligned at the left end and `units` right-aligned at the
  right end.

## Deviations and judgement calls

- **Horizontal face size.** The task and the spec give 6.542" x 1.776" for the
  horizontal case, but in DWG B3147K22 that view shows terminal studs 1 to 4
  and the mounting brackets. It is the rear of the case. The front cover of
  the horizontal meter is the same 6.040" x 2.294" domed cover as the
  vertical one (the catalog photographs show this). That size also fits the
  8:3 footprint (2.633 vs 2.667), while 6.542 x 1.776 (3.68) would not.
- **Flat scale.** On the real edgewise meter the scale plate is a cylindrical
  arc: the movement turns 60 deg and the pointer tip sweeps an arc under the
  domed window. Here the plate is flat, at z = 0.60, because the needle is a
  linear slider. Only the window and case keep the dome.
- **Pointer span.** The spec suggested about 5.2". The catalog gives 4.448" as
  the standard Type 180 scale length (p.25), and that value is used.
- **AB-40 zero adjuster** is at the centre of the window rather than below
  centre (see above).
- **`ge_ab40_red`.** The AB/DB-40 catalog lists no Day-glo pointer, only red
  manual-set pointers (option 21, p.17). The red AB-40 is a mod variant that
  matches the Type 180 red pointer.
- **Dimensions from photographs.** These were not dimensioned in the drawings
  and were measured from the catalog photographs: AB-40 corner radius,
  window and dial radii, tick radii, platform, screw positions; Type 180 rail
  width, window and end-cap lengths, and the across-the-face split of scale
  plate, channel and legend strip. `build.py` marks each of them "photo".

## Sources

- Yokogawa Corporation of America, *Switchboard Instruments* catalog (the
  former GE switchboard instrument line; 37 pages). The page numbers here are
  the catalog's printed page numbers.
  - p.2: AB/DB general specifications. Full-scale deflection 250 deg;
    AB/DB-40 full scale length 6.9"; two-piece platform scale with graduations
    on the outer scale and numerals and legends on the inner scale.
  - p.3: catalog number digit 6, black or gray covers and anti-glare windows.
  - p.17: AB/DB-40 options.
  - p.23: "Dimensions and Panel Drilling for AB/DB 16 & AB/DB 40 (Metal
    Case)". Cover F = 4.33" square, C = 0.86" in front of the panel, case
    3.97" dia, 3.375" stud square.
  - p.25: Type 180 specifications. Scale length 4.448", numeral height 1/4",
    standard pointer white, black trim case optional. Product photographs of
    vertical and horizontal meters.
  - p.27: Type 180 options (19 Day-glo red pointer, 21 black trim case;
    note 4, scale and legend as separate parts with a space for the pointer)
    and DWG B3147K22. Cover 6.040" x 2.294" max, 1.065" max in front of the
    panel, rear case 6.542" x 1.776".
  - Front cover photographs of AB-40 meters (0-600 AC amperes, KW, AC volts,
    AC amperes) and of a horizontal Type 180, used for proportions.
- GE GEH-15398, *Types AB and DB ammeters and voltmeters* (manualslib): the
  user's reference for the AB series. It was not fetched. The Yokogawa catalog
  covers the same instruments.

No reference images are stored in this repository.
