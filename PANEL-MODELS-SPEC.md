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

## Integration (as built)
The game side lives in `dev.bwr.mod.panel.model` (pure: parser, poses, legends, the style table's data) and
`dev.bwr.mod.panel.client` (`PanelModelLoader`, `StyledComponentModel`, `BakedComponentModel`).

**Loading.** `PanelModelLoader` is a client resource reload listener. It reads every
`assets/bwr/models/panel/*.json` into an immutable `BakedPanelModel`; a file that is not format 1, has
malformed numbers or colours, a face with other than 3 or 4 corners, a vertex outside the footprint (0.02 in of
slack, z from -0.06 to 8), a pivot or label outside it, a duplicate label id, or more than 4000 faces / 64
parts / 64 labels is logged as a warning and skipped. Nothing a file contains can crash the client.

**Fit.** The footprint is fitted into the component's cell rectangle with one uniform scale (cells per inch,
elevation included) and centred on the axis with room to spare. Pick the footprint to match the default
aspect ratio and leave room for every handle position inside it.

**Sloped panels.** A cell is one column wide but 1.41 (benchboard, 45 degrees) or 1.04 (console, 15 degrees)
columns long down the face, so the fit is made in real lengths: the footprint is fitted into the rectangle's real
size (`cellsW` columns by `cellsH x rowAspect` columns), and a model inch spans the same length across the face,
down it and out of it. A model therefore keeps its proportions on every panel type, its elevation axis is the
face normal, and its normals light it as the face it is. The cell grid, placement and collision are unchanged;
a round dial on a benchboard is round, in a rectangle that is taller than it looks. `PanelSlopeTests` checks
edge lengths, angles, the elevation axis and the normals of every shipped model on all three panel types.

**Style table.** `PanelStyles.TABLE` is the one table from (component type, style, variant) to model id. A row
lists candidate ids (the first that exists wins) and may carry a pointer colour, a key switch pose, and a
preferred footprint. A missing or rejected model means the classic procedural look for that component, never
an empty hole. New components start as `ge` when the default `ge` model of their type ships in the mod, else
`classic`; the choice is saved, so models arriving later never change an existing component.

| type | style | variant -> model id |
| --- | --- | --- |
| control_switch | ge | pistol -> `ge_sb_target_pistol` (prefers 12x16) |
| control_switch | westinghouse | pistol -> `w2_pistol`; pistol_hd -> `w2_pistol_hd` (prefers 12x16) |
| rotary_selector | ge | oval -> `ge_sb_standard_oval`; knurled -> `ge_sb_standard_knurled`; lever -> `ge_sb_standard_lever` |
| rotary_selector | westinghouse | oval -> `w2_oval`; round_notched -> `w2_round_notched`; mini_slim -> `w2_mini_slim` |
| key_switch | ge | key -> `ge_sb_keyed` (OFF pos3 at 0, ON pos4 at +45) |
| key_switch | westinghouse | key -> `w2_key`; tumbler -> `w2_tumbler` (OFF `left` at -45, ON `right` at +45) |
| pushbutton | ge | flush -> `pb_flush`; extended -> `pb_extended`; mushroom -> `pb_mushroom`; guarded -> `pb_guarded`; illuminated -> `pb_illuminated` |
| lamp | ge | round -> `light_round`; round_plate -> `light_round_plate`; et16 -> `light_et16`; square -> `light_square` |
| setpoint_knob | ge | fluted -> `knob_fluted` |
| dial_gauge | ge | standard -> `ge_ab40`; red_pointer -> `ge_ab40_red` (else `ge_ab40` with the pointer recoloured) |
| edgewise_gauge_vertical | ge | standard -> `ge_180_vertical`; red_pointer -> `ge_180_vertical_red` (same fallback) |
| edgewise_gauge_horizontal | ge | standard -> `ge_180_horizontal`; red_pointer -> `ge_180_horizontal_red` (same fallback) |

**Poses** (`Poses`, `Detents`). Angles are clockwise as the operator sees the face, applied as an ordinary
rotation about the part's pivot and axis (axis `[0,0,1]`) in the y-down frame.
* Control switch: the grip is modelled hanging straight down at NORMAL; the handle turns `-45 x position`
  (CLOSE, position -1, is +45 clockwise; TRIP, the mod's OPEN, +1, is -45). The `right` label carries the CLOSE
  caption and `left` the OPEN one.
* Rotary selector: the pointer is modelled at 12 o'clock; position k (from 0) points at the label `pos(k+1)`
  (its bearing from the pivot), so GE (pos1 at 9 o'clock) and Westinghouse (pos1 at 7:30, pos4 at 12) both work.
  Without a pos label for every position it falls back to 45 degree steps about the modelled pose.
* Key switch: the two angles come from the table row; a tumbler's `plug` part turns 0 locked, +90 unlocked.
* Setpoint knob: -135 at 0 percent to +135 at 100 percent, rest = 50 percent pointing up.
* Dial pointer: `-125 + 250 x fraction`; edgewise pointer slides `travel x fraction` along its axis, low end at
  the modelled position; both follow the gauge's configured lag; no signal pegs low.
* Pushbutton cap: slides its `travel` along its axis while held. An optional `demand` part of an edgewise
  meter slides with input 1 and is hidden without a signal.

**Colour.** Parts with a `tint` take the colour the player set on the component (cap, lens, knob), the tint's
palette colour while it is still the default; authored per-face shading is kept. Pointer parts take the red-pointer
variant's colour only when its dedicated model is missing. Lamp lenses are full-bright when lit (pushbutton:
held, key switch: on, lamp: input, control switch: either lamp input) and dimmed to 22 percent when not. The
target flag shows red when only the red lamp input is lit, green when only the green one is, else the last
operation, else neutral dark. Which operation leaves it red is the switch's "Target red after" setting:
CLOSE (default, as on a GE breaker control switch) or OPEN (valves and pumps, red = open / running).
Glass is drawn last and translucent.

**Legends.** Models mark where; the game supplies the words: `caption` the component label (`legend` too on a
pushbutton or lamp); control switch `right` / `left` / `center` the CLOSE / OPEN / NORMAL captions; selector
`pos1..pos8` the position names; key switch OFF and ON at the row's anchors; meters `units` and `scale0 ..
scaleN` (one numeral per anchor from min to max; the end numerals of a strip meter are nudged half a cap height
inward), or a single `scale` anchor, or `scale_min` / `scale_max`; knob `min` / `mid` / `max` (0, 50, 100).
Other ids (`maker`, `top`, `lower_left`, `bottom`) are not drawn. Text is scaled by the anchor's cap height,
rotated by its `rot`, aligned by `align`, coloured to read on the surface behind it (dark on a lit lens), and
drawn only within 24 blocks.

**Cost.** A baked model is hundreds of faces, so a block's solid geometry is not sent through the CPU every
frame. Each panel block keeps its recorded static meshes plus the posed geometry of its baked components (a
handle, cap, key, knob, lens or flag in its current pose) in one vertex buffer on the GPU (`PanelMeshCache`),
rebuilt only when the block's light, the loaded models, a component's revision or a component's pose changes,
and drawn with one call; a rebuild runs the same drawing code as the per frame path, so the pictures are
identical (the performance walls and the client check's fixed camera stages photograph pixel for pixel the same with
the cache on and off). What moves
continuously (the pointers of the two meter types, which follow a signal), the legends and the glass stay per
frame, and a block draws them with one render type switch each (solid, text, glass) rather than three per
component. Beyond 24 blocks legends and glass are skipped as before, beyond 64 blocks nothing is drawn.
Measured with `-PbwrPanelPerformanceCheck` on a fully populated 16 x 8 vertical wall (704 components in 128
blocks: control switches, selectors, keys, pushbuttons, lamps, knobs, round and edgewise meters), mean frame
time over 8 seconds, an empty scene being 0.45 ms: without the cache the classic wall costs 3.8 to 4.1 ms and
the baked wall 30 to 35 ms (from beyond 24 blocks 1.6 and 18 to 21 ms); with it, 3.5 to 4.0 ms and 3.5 to 3.9 ms
(1.4 to 1.7 and 1.3 to 1.5 ms from beyond 24 blocks). The baked wall holds 193,000 quads in video memory, about
28 MB, and takes about 45 ms in all to build the first time all of it is in view (0.35 ms a block). Buffers are
freed ten seconds after a block was last drawn. The client option `panelGpuCache` (`bwr-visuals.toml`) turns
the cache off, and a shader pack in use does so by itself (the buffers are drawn with the vanilla entity
program); `-Dbwr.panelImmediate=true` does the same for development.

**Known limits.** The classic procedural components still fill their cells as drawn, so on a benchboard or
console they are stretched along the rows (a classic round dial is an oval there); baked models are not.
A model with a frame much larger than its plate (the target pistols) looks small at the default size; the table
marks such rows as preferring the larger footprint. Edgewise meters ignore the demand marker unless the model
has a `demand` part.

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
