# Control-panel component models — modeling spec

Replaces the procedural placeholder bodies of panel components with models of
real control-room hardware, built in Blender from manufacturer drawings and
exported through `art/models/panel/panel_export.py` (read its docstring: units,
axes, part tags, roles, label anchors, export format, and the 800-face budget).

Scope: geometry and colour only. Text legends (TRIP/CLOSE, positions, scales,
units) are drawn by the game; models only mark where with label anchors.

## Deliverables per model

1. A generator script `art/models/panel/<family>/build.py`, rerunnable headless:
   `blender --background --factory-startup --python art/models/panel/<family>/build.py`
   It builds every model of the family from parameters (dimensions in a table
   at the top, each with its citation) and exports each one.
2. One JSON per model in `mod/src/main/resources/assets/bwr/models/panel/`,
   named `<family>_<variant>.json` (e.g. `ge_sb_target_pistol.json`).
3. Previews (front, three-quarter, side) for every model in `tmp/panel-models/`,
   via `panel_export.preview`, PLUS one render with every moving part at each
   extreme position (handle at each detent, needle at min/max, button pressed).
   LOOK at them and iterate until they read clearly as the real device at
   Minecraft distances (a few blocks). Do not ship anything you haven't seen.
4. Moving parts are separate parts with a pivot (rotation) or travel (slide):
   switch handles, target flags, meter needles/pointers, pushbutton caps.
   Lamps, lenses and target windows use roles `lamp` / `flag` / `glass`.
5. A short `art/models/panel/<family>/README.md`: what each model is, which
   mod component type and style it serves, its moving parts and their ranges,
   label anchors, and the sources.

Do not commit the manufacturer reference images into the repo (copyright);
cite them. They are available locally for reading (see Sources).

## Scale

Model at real size in inches. The game fits each model's footprint rectangle
into the component's cell rectangle (1 cell = 1/16 block) with a uniform scale,
so proportions are what matter. Pick each model's footprint to match the
component's DEFAULT footprint aspect ratio (cells w × h), with the device
centred; leave room for handles at every position.

| Component type (mod id) | Default cells | Aspect |
| --- | --- | --- |
| control_switch | 8 × 12 | 2 : 3 |
| rotary_selector | 8 × 10 | 4 : 5 |
| key_switch | 6 × 8 | 3 : 4 |
| pushbutton | 6 × 6 | 1 : 1 |
| lamp | 4 × 4 | 1 : 1 |
| setpoint_knob | 8 × 8 | 1 : 1 |
| dial_gauge | 12 × 12 | 1 : 1 |
| edgewise_gauge_vertical | 6 × 16 | 3 : 8 |
| edgewise_gauge_horizontal | 16 × 6 | 8 : 3 |

## Palette (sRGB)

Benchboard face A7BCAE; bezel 7D9284; switch escutcheon black 141414 with
bright metal trim B8BCC0; phenolic handles black 1A1A1A (GE breaker control
handles may also be red C0261E or black); meter cases black 1C1C1C; meter dials
white F2F0E8 with black scale; pointer black or Day-glo red E8402A; lamp lenses
red E02020 / green 20B040 / white F0F0E0 / amber FFB000 / blue 3070E0 (role
`lamp`, tint name set); chrome bezels C8CCD0.

## Families and models

### A. GE SB series control switches (SB-1, SB-9, SB-10)
Sources: GE GEH-90BH "Control and Instrument Switches Types SB-1, SB-7, SB-10"
(fig. 1–4: switch, mounting, panel drilling); GE 10AA124 breaker control switch
catalog (handle types, escutcheons, outline dimensions).
- **Standard escutcheon:** 2-5/8" wide × 2-9/16" high × 3/16" thick, rounded
  corners, four mounting screws (1/4" holes at ±15/16" horizontally), centre
  shaft hole 1/2" (1-3/8" for removable handle). Black with bright trim edge.
  Position legends engraved around the shaft (label anchors pos1..pos8 for up to
  8 positions at 45°, plus "caption" at top).
- **Target escutcheon (SB-1/SB-9 breaker control):** black plate, raised bright
  border; a sector-shaped TARGET WINDOW above the shaft (role `flag`, part
  "target", shows red/green by last operation); legends TRIP (left, rotated
  ~-55°) and CLOSE (right, ~+55°), "PULL TO LOCK" lower left, maker legend at
  bottom — label anchors only.
- **Keyed escutcheon:** as standard, with a key-lock cylinder face.
- **Handles (part "handle", pivot = shaft centre, axis Z, rotation ±45° for
  breaker control; selector detents at 45° steps):** pistol grip (GE breaker
  control default, ~2.6" from shaft to tip, tapered grip with finger swell),
  oval, knurled (round with flutes), lever, and removable key.
- Map: control_switch style "ge" = target escutcheon + pistol grip;
  rotary_selector "ge" = standard escutcheon + oval (variant knurled, lever);
  key_switch "ge" = keyed escutcheon + removable key handle.

### B. Westinghouse W-2 control switches
Source: Westinghouse Descriptive Bulletin 34-251 (April 1987) "Type W-2
Switch": p.3 handles, p.5 construction (nameplate/escutcheon, dial plate),
p.17 key lock, p.19 outline dimensions and handle shapes.
- **Nameplate/escutcheon:** 2.25" wide × 3.00" high, rounded rectangle, with the
  dial plate behind; two mounting screws (upper/lower); centre shaft. Body depth
  irrelevant (behind the panel). Legends: label anchors at top ("caption") and
  around the shaft.
- **Handles (moulded phenolic, recessed arrow):** oval, round notched (6 lobes),
  pistol grip (1.31" shaft to tip), heavy-duty pistol grip (2.50"), mini-slimline
  "DS" (1.0"), removable key (1.88"). Fig. 4–9 on p.19 give the shapes.
- **Tumbler key lock option:** lock cylinder 1.4375" dia, 2.50" above the shaft
  centre (p.17).
- Map: control_switch style "westinghouse" = W-2 + pistol grip (variant
  heavy-duty pistol grip); rotary_selector "westinghouse" = W-2 + oval
  (variants round notched, mini-slimline); key_switch "westinghouse" = W-2 +
  removable key, or tumbler lock.

### C. Switchboard meters (GE, now Yokogawa: same model numbers)
Source: Yokogawa Switchboard Instruments catalog (AB/DB40, Type 180 pages),
the former GE switchboard instrument line.
- **AB-40 / DB-40 round-scale meter (dial_gauge style "ge"):** square front
  cover 4.33" × 4.33" with rounded corners and 4 corner screws, round case
  3.97" dia behind; flat glass/polycarbonate window; white dial with a
  250° scale (6.9" scale length), scale on the outer arc, legends inside; black
  knife-edge pointer from the centre (part "needle", pivot at dial centre, axis
  Z, rotation range ±125°). Zero adjuster screw below centre.
- **Type 180 edgewise (edgewise_gauge_vertical style "ge"):** face 2.294" wide
  × 6.040" high, convex domed window (bulging ~0.25"), black trim case, white
  scale strip on one side of the centre line, pointer that travels VERTICALLY
  (part "needle" with travel along Y; scale span ~5.2"), optional Day-glo red
  pointer. Horizontal variant (edgewise_gauge_horizontal) is the same rotated
  90° (6.542" × 1.776" face for the horizontal case per the drawing).

### D. Pushbuttons, indicating lights, knob (30.5 mm oiltight, GE CR104P style)
Source: GE CR104P heavy-duty 30.5 mm oiltight pushbuttons and indicating
lights (mushroom head 1-3/8", octagonal mounting ring). Exact bezel/legend
plate dimensions not available — use typical 30.5 mm oiltight proportions and
say so in the README: chrome bezel ring ~1.56" dia, flush cap ~1.1" dia,
rectangular legend plate ~1.75" × 2.25" behind, mushroom cap 1.375".
- pushbutton style "ge": flush button (cap part "cap", travel 0.15" inward),
  extended button, mushroom (emergency, red), and guarded (ring guard).
- lamp style "ge": indicating light, chrome bezel + domed/fresnel lens
  (role `lamp`, tint by colour), optional legend plate.
- setpoint_knob style "ge": fluted control knob with pointer skirt and dial
  plate (part "knob", rotation ±135°).

## Integration (separate coding task, not the modelers')
A Java loader reads these JSON files into the panel renderer (`QuadSink` gains
arbitrary normals), maps roles and moving parts to component state (handle angle
from switch position, target flag colour from last operation, needle from
input, lamp emission from lamp inputs, cap travel while pressed), draws legends
at label anchors with the component's configured text, and adds a "style"
config field (ge / westinghouse / classic placeholder) to the component types.

## Sources (local copies for reading only)
Scratch folder: `C:\Users\14238\AppData\Local\Temp\claude\C--Users-14238-Downloads-bwr-mod-handoff\418cd604-018d-4e92-a0cc-37a0e339a9c4\scratchpad\cr-refs\`
- GE GEH-90BH: `ge_geh90bh.pdf`; rendered pages `geh_p2.png`, `geh_p4.png`.
- GE 10AA124: `ge_10aa124.pdf`; figure crops in `aa124\` (CCITT, may be
  colour-inverted), composites `aa124_handles.png`, `aa124_dims.png`.
- Westinghouse DB 34-251: `w2_db34251.pdf`; rendered `w2_p3.png`, `w2_p5.png`,
  `w2_p17.png`, `w2_p19.png`. More pages: run
  `..\pdfenv\Scripts\python.exe -I ..\render_pages.py w2_db34251.pdf w2 1.6 <pages>`
  then convert the .pgm with system Python PIL.
- Yokogawa switchboard catalog: `yoko_switchboard.pdf`; `yoko_p3.png`,
  `yoko_p24.png` (AB/DB40 dimensions), `yoko_p28.png` (Type 180 dimensions).
