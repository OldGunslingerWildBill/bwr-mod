# Family D: pushbuttons, indicating lights, setpoint knob

GE CR104P-style heavy-duty 30.5 mm oiltight pilot devices, the kind fitted to
US nuclear control boards, plus a GE ET-16 switchboard indicating lamp and a
fluted setpoint knob. Built by `build.py` and exported through
`../panel_export.py`:

    blender --background --factory-startup --python art/models/panel/pushbuttons/build.py
    blender ... --python art/models/panel/pushbuttons/build.py -- --only pb_flush,knob_fluted --no-preview

The script writes `mod/src/main/resources/assets/bwr/models/panel/<id>.json`,
and renders previews to `tmp/panel-models/pushbuttons/` (git-ignored):

- `<id>_front|three_quarter|side.png` for every model.
- `<id>_pressed_*` for buttons, with the cap at full travel.
- `<id>_off_*` and `<id>_lit_*` for lamps. "Off" dims the lens as the game
  does; "lit" draws it full-bright.
- `knob_fluted_min_*` and `knob_fluted_max_*` for the knob at -135 deg and +135 deg.

Every dimension is in the table at the top of `build.py`, each with its source tag.

## Models

| id | Component / style | Footprint (in) | Faces | Moving part | Labels | Default tint |
| --- | --- | --- | --- | --- | --- | --- |
| `pb_flush` | pushbutton `ge` (default) | 2.00 x 2.00 | 250 | `cap` slides 0.15 along -Z | `legend` | black |
| `pb_extended` | pushbutton `ge` variant | 2.00 x 2.00 | 250 | `cap` slides 0.15 along -Z | `legend` | green |
| `pb_mushroom` | pushbutton `ge` variant (emergency / scram) | 2.00 x 2.00 | 358 | `cap` slides 0.15 along -Z | `legend` | red |
| `pb_guarded` | pushbutton `ge` variant | 2.00 x 2.00 | 306 | `cap` slides 0.15 along -Z | `legend` | red |
| `pb_illuminated` | pushbutton `ge` variant, lit | 2.00 x 2.00 | 290 | `cap` (role `lamp`) slides 0.15 along -Z | `legend` | amber |
| `light_round` | lamp `ge` (default) | 1.70 x 1.70 | 300 | none; `lens` is role `lamp` | none | red |
| `light_round_plate` | lamp `ge` variant, with nameplate | 2.00 x 2.00 | 310 | none; `lens` is role `lamp` | `legend` | white |
| `light_et16` | lamp `ge` variant (GE ET-16 switchboard lamp) | 1.10 x 1.10 | 240 | none; `lens` is role `lamp` | none | green |
| `light_square` | lamp `ge` variant (square lens) | 1.00 x 1.00 | 29 | none; `lens` is role `lamp` | `legend` (on the lens) | white |
| `knob_fluted` | setpoint_knob `ge` | 2.25 x 2.25 | 316 | `knob` turns about Z through the dial centre, +/-135 deg | `min`, `mid`, `max`, `caption` | none (black phenolic) |

All footprints are square, matching the 1:1 default aspect of `pushbutton` (6x6),
`lamp` (4x4) and `setpoint_knob` (8x8). Each device is centred left to right.
Pushbuttons and `light_round_plate` sit on a CR104P Size 1 nameplate
(1.93 W x 1.98 H), which is centred in the footprint. The operator sits 1.16
below the nameplate's top edge, under the legend band, as on the real plate.

**Caps and tints.** The cap colour comes from its material, but the cap is
tagged role `paint` with `bwr_tint` set. The game should recolour it from the
component's configured colour (`PushbuttonData.color`). Lamp lenses are role
`lamp` with `bwr_tint` set, so the game recolours them from `LampData.color`,
dims them when off and draws them full-bright when on. The illuminated
pushbutton cap is both the lamp and the moving part.

**Travel.** A cap slides 0.15 in along its axis `[0, 0, -1]`, into the panel,
while the button is held. At full travel the flush cap sits inside the chrome
collar, the extended and illuminated caps sit level with it, and the guarded cap
drops inside the guard.

**Knob.** At rest (0 deg) the pointer points straight up, which is 50 %. 0 % is
the pointer at 7:30 (counter-clockwise as seen by the operator) and 100 % is
4:30. This matches the existing `SetpointKnobModel` mapping of
-135 deg + 270 deg x value / 100.

The pointer is a raised white stripe over the top, down the front land and
across the skirt, so it reads from any angle. The dial plate carries 11 tick
marks as geometry, with majors at 0, 50 and 100, and a scale arc.

**Labels** are anchors only; the game draws the text:

- `legend` (cap height 0.15) is centred in the nameplate's satin band. On
  `light_square` it is on the lens face (0.10), for an engraved-lens legend.
- `min`, `mid` and `max` (0.12) sit just outside the 0 %, 50 % and 100 % tick
  ends, and `caption` (0.11) sits below the knob on the dial plate.
- The anchor is the centre of the text, with no rotation.

## What the dimensions are based on

There is no public dimensioned drawing of the CR104P bezel, cap, lens or
mushroom head. What exists and was used:

- **Catalogue values.** These come from the GE Control Catalog, Section 9,
  rev. 7/13:
  - The mounting hole is 1-13/64 in (30.5 mm), and the flush operator projects
    .53 in (13.5 mm) from the panel (p. 9-49 outline).
  - The Size 1 nameplate is 1.98 x 1.93 in (p. 9-40).
  - The mushroom head is 1-3/8 in (p. 9-7 / 9-38).
  - The guard ring for guarded buttons, CR104PXG04, projects 1.20 in (p. 9-38).
  - The standard indicating-light lens CR104PXL01 projects 0.92 in from the
    panel (distributor listings, below).
- **Scaled from the catalogue outline.** The p. 9-49 outline (front and side
  views) was scaled against its own 1.73 in housing dimension:
  - The octagonal ring is about 1.42 in across flats and about 0.27 to 0.30 in
    thick, with its top about 0.38 in from the panel.
  - The round collar is about 1.2 in in diameter and runs from about 0.38 in to
    the .53 in front. The flush cap is about 0.98 in, level with the collar
    front.
  - A round locking plate sits under the octagon. The drawing suggests up to
    about 1.6 in; it is modelled at 1.48 in so the octagon still reads from the
    front.
  - On the Size 1 nameplate (p. 9-40 photo), the satin legend band is about
    0.44 in high and the hole centre is about 1.16 in below the top edge.
- **Estimated from the catalogue photographs:**
  - The extended cap is about 1/4 in proud of the flush one.
  - The illuminated lens cap is slimmer than the plain cap and stands about
    0.92 in out.
  - The mushroom head is profiled with its front at 1.08 in, under the 1.38 in
    depth of the CR104PXG14 mushroom guard.
  - The guard tube is about 1.40 in in outside diameter, with two side windows.
  - The pilot-light lens is ribbed with a domed face.
- **ET-16.** The GE Vernova ET-16/17 brochure gives the lens front 0.69 in
  from the panel, a 0.66 in mounting hole and an "A" dimension of 0.88 in.
  "A" is taken as the lens-cap diameter, but the brochure's drawing puts the
  0.88 callout on the resistor body, so treat it as uncertain. The knurled
  bezel nut (1.00 in) is assumed.
- **Assumed, with no source found:**
  - `light_square`: a 0.94 in black bezel with a 0.72 in square lens. No GE
    drawing or BWR panel photo with a dimensioned square-lens indicator was
    found. This is a generic square-lens indicator of the kind seen on
    rod-select and annunciator-style boards. Treat it as a stand-in.
  - `knob_fluted`: a 12-flute 0.84 in knob, a 1.24 in skirt and a 2.15 in
    square dial plate. The CR104P line's setpoint device is the CR104PXP
    potentiometer operator (p. 9-32), but no knob dimensions are published.
  - Cap travel of 0.15 in is from the spec.
- **Palette.** Colours are from `PANEL-MODELS-SPEC.md`, with these additions:
  - nameplate legend band: satin B8BCC0;
  - operator body seen in the bore: 2A2C2E;
  - red nameplate field: 8E1A14, darker than the red cap so the two separate;
  - dial marks: F2F0E8;
  - cap colours: red C0261E, green 1E7B3C, black 1A1A1A, amber E09A00;
  - lens colours: as in the spec.

## Sources

- GE Industrial Solutions, *Control Catalog, Section 9: Pilot and Signaling
  Devices* (rev. 7/13), CR104P pages 9-1 to 9-49. Copy hosted by USESI:
  <https://assets.usesi.com/product-media/catalogs/USESI_73266_catalog.pdf>.
  The rev. 4/16 edition is at
  <https://assets.usesi.com/product-media/catalogs/USESI_17225_catalog.pdf>.
- CR104PXL01 lens, "0.92 in projection from panel", from distributor listings:
  - <https://www.partssupercenter.com/Product/10643-cr104pxl01m>
  - <https://rexelusa.com/p/718987/ge/lens-indicating-light-clear-092-depth/783166290002/cr104pxl01c>
- GE Vernova, *ET-16/17 Indicating Lamps* brochure GEA-N50470, dimension table
  and drawing on p. 2:
  <https://www.gevernova.com/grid-solutions/sites/default/files/2025-11/ET-16-17-Product-Brochure-EN-N50470-Hybrid-202507-R002.pdf>.
  The ET-16 LED fact sheet is at
  <https://www.gevernova.com/grid-solutions/sites/default/files/2025-11/Model-ET-16-LED-Fact-Sheet-EN-N50200-Hybrid-202505-R001.pdf>.

The catalogue images are not copied into the repo.
