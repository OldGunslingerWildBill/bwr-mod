# Control panels — implementation spec (planned alpha.32)

Status: **design, not yet built.** Player-built control rooms: panel blocks that
players cover with whatever instruments and controls they want, wired to the
plant, to each other, to redstone, or to CC:Tweaked.

Final 3D models wait for reference diagrams from the project owner. Build
everything with **procedural placeholder geometry**, structured so a Blender
model can replace each component's body later without touching its logic (§10).

House rules (from the codebase): physics stays in `core/`; no per-block tickers
beyond what's needed (panels may tick only while they have linked inputs);
cache and invalidate; never force-load chunks; every saved state round-trips;
**nothing acts automatically**: a panel component only commands the plant
when a player (or a CC program the player wrote) operates it. NEVER edit an
existing test to make it pass.

## 1. Panel blocks

- `bwr:control_panel_vertical` — vertical board, mounting face on the front.
- `bwr:control_panel_benchboard` — sloped desk (45° face). Full-block footprint.
- `bwr:control_panel_console` — low desk, 15° face.
- Horizontal facing on placement. Placeholder models: simple painted boxes in
  the control-board palette (§9). Final Blender models come later.

Each panel block has **one mounting face** carrying a **16 × 16 grid**
(1 cell = 1/16 block). Adjacent panel blocks of the same type, same facing
and coplanar faces form a **panel group** (cached, invalidated on change,
same pattern as other mod multiblocks). Groups are capped (config, default
16 × 8 blocks).

## 2. Components

A component has a type, a grid footprint (w × h cells), an anchor cell, a
player-set **label** (unique within the group; auto-assigned like `PB-1`; see §8 for merges),
type-specific config, and live state.

- **Placement:** right-click a mounting face holding a component item. The
  hit point snaps to the grid as the footprint's top-left. A client-side ghost
  outline shows fit (green) or collision (red) before clicking.
- **Straddling:** a footprint may extend into other blocks of the same group,
  up to 4 × 4 blocks. The anchor block's BE owns the component; every covered
  cell is reserved so nothing else can overlap, and clicks on any covered cell
  are forwarded to the owner. Breaking any block a component covers drops the
  component item. A block removed while the other half's chunk is unloaded
  cannot tell it (chunks are never force-loaded), so each side re-verifies
  itself when its chunk becomes available: an owner removes (and drops) a
  component whose covered block is loaded but is no longer a same-type,
  same-facing panel holding the matching claim; a covered block silently drops
  a claim whose loaded owner no longer owns that component. A check that meets
  an unloaded position is deferred until that chunk loads (`PanelConsistency`).
- **Removal:** sneak-right-click with the panel wrench (§7) drops the item.
- Every component has a recipe and drops itself. `tools-audit-assets.py`
  must stay at 0 problems.

### 2.1 Catalog (v1)

Inputs (produce an output value):
| Type | Footprint | Behavior |
| --- | --- | --- |
| Pushbutton | 6×6 (8×8) | Momentary 1 while held, color option |
| Toggle switch | 6×8 (8×10) | Maintained 0/1 |
| Rotary selector | 8×10 (12×14; the bottom rows hold the position name) | 2–6 labeled positions, outputs position index |
| Control switch (pistol grip) | 8×12 (12×16) | GE style: positions e.g. CLOSE / NORMAL / OPEN with spring return to NORMAL; red lamp = open/running, green = closed/stopped (US convention; lamps also labeled) |
| Key switch | 6×8 (8×10) | 0/1, only operable while holding the matching key item (optional) |
| Setpoint knob | 8×8 (12×12) | Continuous 0–100 % (scroll or drag) |
| Thumbwheel | 3×6 per digit | Numeric entry, N digits |

Indicators and annunciators:
| Type | Footprint | Behavior |
| --- | --- | --- |
| Lamp | 4×4 or 6×6 | On/off/flash, color |
| Annunciator window | 12×8 (16×12) | Engraved text; ISA-18.1 sequence A: alarm → fast flash; acknowledge → steady; condition clears → off. Group-level ACK/RESET via a pushbutton bound to the group's `ack` |
| Bar graph | 3×N or N×3 (N 12 to 32; one segment per 2 cells) | Segmented level with warn/alarm bands |

Gauges:
| Type | Footprint | Behavior |
| --- | --- | --- |
| Round dial | 12×12 to 32×32 | Needle, min/max, units, colored bands, damping |
| Edgewise vertical | 6×16 (up to 8×32) | Like the GUI mockup: scale, pointer, optional demand marker |
| Edgewise horizontal | 16×6 (up to 32×8) | Same, horizontal |
| Strip-chart recorder | 16×16 (24×16, 32×24) | 1–4 pens, configurable time span, scrolling paper |

Displays:
| Type | Footprint | Behavior |
| --- | --- | --- |
| 7-segment display | 5×8 per digit (1 to 12 digits) | N digits, decimal point, color. Digits plus the letters 7-seg can show (A b C d E F G H I J L n o P q r S t U y - _); anything else renders blank |
| 16-segment display | 5×8 per char (1 to 12) | Full A–Z, 0–9, punctuation |
| Character LCD | 1 row = 4 cells tall, 1 char = 3 cells wide | Clock-style LCD, 1–4 rows × N chars, 5×7 dot matrix, backlight color |
| Graphic display | 32×32 default, up to 4×4 blocks | Pixel framebuffer, 32 px per block edge, palette of 16; drawn by CC (§8) |

Labels and mimic:
| Type | Footprint | Behavior |
| --- | --- | --- |
| Nameplate | 4×W (4 cells tall) | Engraved custom text, plate/text colors |
| Mimic line | 3×N (3 cells thick), corners, tees | Colored bus line; optional binding dims it to gray when 0 |
| Mimic symbol | 6×6 (to 12×12) | Valve, pump, tank, turbine, breaker; colored by bound state |

Sizes are width × height in cells (one cell is 1/16 block; sizes in parentheses are the other options the item
cycles through, the first is the default). They were made roughly three times larger than the first design so a
wall reads at a few blocks; components may still span up to 4 × 4 blocks. Engraved text, captions and labels
scale with their component (aiming for at least about 3 cells of text height where the text fits). A footprint
that is no longer in a type's list (a component placed before the change) keeps its stored size.

### 2.2 Display text and formats

Segment and LCD displays show either **static text** or a **format string**
with signal inputs: `PWR {0:5.1f}%`, `{0:04d}`, `{1}` (raw). The formatter is
a pure function in its own class with unit tests (precision, width, padding,
overflow shows `----`/`OFL`, NaN/no signal shows dashes).

## 3. Signals, wiring and commands

Every component has numbered **inputs** (output components) or **outputs**
(input components). Each input takes exactly one source; outputs fan out.

Sources for a component input:
1. **Plant signal:** a linked device and a signal key (§4), e.g. reactor
   dome pressure.
2. **Component:** another component's output in the same group (e.g. a lamp
   that shows a switch's position).
3. **Redstone:** the signal strength (0–15) arriving at the panel block that
   holds the anchor, from its back face.
4. **CC:** a value set by Lua (§8). Shows a distinct "awaiting CC" state until
   set.

Sinks for a component output:
1. **Plant command:** a linked device and a command key (§4), e.g. MSIV
   open/close, pump speed demand, valve demand.
2. **Redstone:** emitted from the anchor block's back face.
3. **CC event:** `bwr_panel_input` (§8).

Commands go through exactly the same code path as the device's CC setter, so
power requirements, interlocks and `computerControlled` behave identically. A
switch commanding an unpowered MSIV records the open demand, the same as CC's
`open()`; the valve opens when power returns.

A plant sink has a **mode**. FOLLOW (default; the only behavior before GUI
protocol 16, and what a save without a `Mode` key loads as) sends
`output x scale` on every change. MOMENTARY sends a fixed, configured command
value once when the output becomes non-zero and ignores the return to zero. It
exists because momentary inputs (the spring-return control switch, a
pushbutton) go 1 then 0: a FOLLOW sink bound to a BOOLEAN command would
reverse itself on release, while real GE switches are momentary and the
equipment seals in. The fixed value is validated server-side (finite, within
the command's min/max). The binding screen defaults to MOMENTARY (OPEN sends 1,
CLOSE sends 0, a pushbutton sends 1) when a control switch's `open` or `close`
output, or a pushbutton's output, is bound to a BOOLEAN or LEVEL command; the
player can switch it back. A momentary sink still fires only on an operated
output change, never on load, resample or rebind. Redstone sinks have no mode.

A **plant signal** source carries a linear transform `displayed = raw x scale + offset` (defaults 1 and 0,
from GUI protocol 17). It is applied in `PlantSource.sample`, so every consumer sees the transformed value; NaN
(no signal) stays NaN. It is saved as `Scale` and `Offset` only when non-default, so earlier saves load
unchanged and re-save byte-identical; a tag whose `Scale` is zero, non-finite or not a number, or whose `Offset`
is, is kept verbatim like any other malformed source. The `Bind` payload carries `scale` (shared with sinks) and
a trailing `offset`; for a plant input the server requires a finite non-zero scale and a finite offset, both of
magnitude at most 1e6 (`BAD_VALUE` otherwise). Component, redstone and CC sources have no transform in v1. The
binding screen shows Scale and Offset fields for plant inputs, with presets Raw (x1) and Fraction to %
(x100), so a 0 to 1 signal such as the MSIV `position` can drive a 7-segment display that reads 0 to 100.

No logic, comparators or timers in v1. Annunciator alarm conditions
(`input > setpoint`, `input < setpoint`, `input == 1`) are display behavior
only and never command anything.

## 4. Signal layer (`dev.bwr.mod.signal`)

A CC-independent API, so panels work without CC installed:

```java
public interface SignalProvider {
    List<SignalSpec> signals();
    OptionalDouble read(String key);          // booleans as 0/1
    default Optional<String> readText(String key) { return Optional.empty(); }
    List<CommandSpec> commands();
    boolean command(String key, double value); // false if refused
}
public record SignalSpec(String key, String label, String unit,
                         double min, double max, Kind kind) {}
public record CommandSpec(String key, String label, Kind kind, double min, double max) {}
public enum Kind { NUMBER, BOOLEAN, LEVEL, PULSE, TEXT }
public final class SignalProviders {
    public static SignalProvider at(Level level, BlockPos pos); // null if none or unloaded
}
```

- Providers are **adapters registered by block-entity class** in
  `dev.bwr.mod.signal.adapters`, not changes to the block entities, unless a
  value has no accessor (then add a plain getter).
- Values must match what the CC peripherals report. Use the same getters; a
  GameTest compares adapter reads with the peripheral's (when CC is present).
- Never load chunks; an unloaded or missing device reads empty and the
  component shows "no signal" (gauge pegs low and an off-scale flag, displays
  show dashes, lamps off).
- Coverage v1: reactor controller (power, period, dome pressure, level, core
  flow, rod data, temperatures, core damage stage, containment H2/O2/pressure),
  MSIV, SRV, turbine valves, turbine steam outlet, RPV steam outlet, feedwater
  pump, recirculation pump, ECCS pumps, suppression pool, RHR heat exchanger,
  condenser, condensate tank, cooling, power module, containment controller,
  penetrations, vault door, airlock.

**Sampling:** a panel group samples its linked signals at most every N ticks
(config, default 2), server-side, and sends changed values to watching
clients in one batched packet per group. The panel ticks only while it has
plant- or redstone-linked inputs.

## 5. Networking

- C2S: place, remove, configure, operate (press/release/turn/set). Validate
  distance, permission (`AssemblyAccess.permitted`), group membership, and
  ranges. Never trust client values beyond the component's own range.
- S2C: full group state on watch; batched value deltas after that.
- Bump the network protocol version.

## 6. Saves

Each panel BE saves its owned components (type, anchor, footprint, label,
config, bindings, latched state such as toggle positions and annunciator
acknowledge state) under a versioned key. Bindings save absolute positions.
Round-trip tests for every component type.

## 7. Tools

- **Panel wrench** `bwr:panel_wrench`: right-click a component to open its
  config screen (label, color, range, bands, format, inputs and outputs);
  sneak-right-click removes it.
- **Panel linker** `bwr:panel_linker`: sneak-right-click a device to store it,
  then right-click a component to bind it; the screen lists that device's
  signals or commands. Range limit (config, default 64 blocks, same dimension).
  Hovering a bound component with the linker outlines the linked device.
- Config screens are **functional only** (vanilla widgets are fine). A later
  GUI overhaul restyles them; keep layout code separate from logic.

## 8. CC:Tweaked (soft dependency)

Any panel block exposes the group as peripheral `bwr_panel`:

- `list()` → labels with type and footprint; `get(label)`; `set(label, value)`
  for CC-sourced inputs; `setText(label, text)` for displays and nameplates;
  `getInputs()`.
- Character LCD: `write(label, row, col, text)`, `clear(label)`.
- Graphic display: `getSize(label)`, `clear(label, color)`,
  `setPixel(label, x, y, color)`, `fill(label, x, y, w, h, color)`,
  `drawText(label, x, y, text, color)`, `blit(label, rows)` (hex strings),
  `setPalette(label, index, rgb)`. Rate-limited, and only changes are synced.
- Event `bwr_panel_input(name, label, value, port)` whenever a player operates an input. `name` is the
  attachment name the computer sees (`IComputerAccess.getAttachmentName()`, stored when the peripheral attaches),
  first as in `monitor_touch`, so scripts can tell two groups apart. At most one event per computer per group
  per output change: CC gives every attachment its own `IComputerAccess`, so the computer is identified by
  `getID()`, and when it is attached to the same group through several names the earliest still-attached one
  is used. Computers attached to other groups are not notified.
- Label uniqueness: labels are unique at placement and on wrench rename (a rename to a taken label is
  refused). When a group (re)forms, duplicates among its loaded members are renamed deterministically: in
  owner `BlockPos` order, then component id, the first keeps its label; later ones get the next free
  `PREFIX-n`, or `label-n` (n from 2) for custom labels, truncated to the label length limit. Members in
  unloaded chunks are not considered and nothing is ever renamed back, so a split and re-merge is stable.
  Lookups stay deterministic regardless: `list()`/`getInputs()` never drop a component; if a duplicate
  exists, later ones are keyed `label#2`, `label#3` in the same order, and `get(label)` accepts those keys.

This is how players "load their own software": a CC computer drives displays
and reads switches.

## 9. Look

Control-room style, matching the planned GUI overhaul: seafoam benchboard
paint (#A7BCAE face, #7D9284 bezel), black engraved nameplates, amber/red/
green segment colors, backlit annunciator tiles. Lamps and displays render
full-bright (emissive). The US color convention is red = open/running, green =
closed/stopped, and lamps also carry text so color isn't the only cue.
Glowing (emissive) geometry is drawn with the full-bright packed light and an up-facing normal, so the entity
shader neither darkens it with the world light at night nor shades it by the face's angle; unlit lamps, segments
and tiles, and pushbutton caps that are not held, stay at world light.

## 10. Rendering and models

- One block-entity renderer per panel block that owns components; extended
  render bounds for straddling components. No per-frame allocation; cache
  static geometry per component and only redraw moving parts (needles,
  lamps, segments, pens).
- LOD: beyond 24 blocks skip text and fine detail; beyond 64 skip components.
- Cached solid geometry: each panel block keeps the recorded static meshes of its components, and the posed
  geometry of baked models, in one vertex buffer on the GPU, rebuilt only when something in it changes (see
  PANEL-MODELS-SPEC.md, "Cost"); a frame draws it with one call and runs model code only for what moves
  continuously, the legends and the glass, switching render type once per block for each.
- Placeholder bodies are procedural (boxes and quads). Put each type's body
  behind a `ComponentModel` interface keyed by type, so a baked Blender model
  can be swapped in later per type.
- Run the existing client performance checks pattern (see
  `GuiPerformanceCheck`, `*ClientCheck`) for a panel wall full of components.

## 11. Tests

GameTests:
- placement, collision, straddling across blocks, and group formation and split;
- wrench removal and dropped items, and breaking a covered block;
- save and reload of every type;
- each signal adapter reads the same as its CC peripheral (when CC is present);
- commands obey power and interlocks (an unpowered MSIV records the demand like CC and reports it cannot move yet);
- redstone in and out;
- the annunciator sequence;
- the CC peripheral (skipped without CC);
- "no signal" on an unloaded device.

Pure unit tests: the format strings, the 7-segment and 16-segment character
maps, and annunciator state transitions.

## 12. Out of scope for v1

Logic gates and timers; final Blender models; sounds (switch clicks, alarm
horns: wire sound events with silent placeholders only); GUI styling.

## 13. Build order

1. **Foundation:** panel blocks, grid, groups, straddling, placement and
   removal, saves, networking, renderer framework, component type registry,
   wrench. Prove it with the nameplate and lamp.
2. **Signal layer** (§4): can be built in parallel with step 1.
3. **Inputs, indicators and annunciators** (§2.1 first two tables).
4. **Gauges and displays** (§2.1 gauges and displays, §2.2).
5. **Wiring and integration:** linker, binding screens, component-to-
   component wiring, redstone, CC peripheral, docs (`CONTROL-PANELS.md` with
   a worked example control room).

## 14. 45 degree desk corners (alpha.61)

The owner's request: "there's no corner blocks or desks that have a 45 deg corner". A run of control desks could only meet another
at a hard 90 degree corner; real BWR consoles, and the reference screenshots, join runs through 45 degree chamfered sections
that carry controls too.

**What was added.** Two items, `bwr:control_panel_desk_corner_inside` (the console wraps around the operator, the U) and
`bwr:control_panel_desk_corner_outside` (it wraps away). Each continues the desk profile of `control_panel_desk` and
`control_panel_desk_wide` exactly (the same cross-section, 0.75 deep, the top 0.75 up, the overhang, the riser and shelf, the knee
front), so run -> corner -> run reads as one console. The 1 m and the 2 m desk share one profile, so one pair of corners serves
both. **The benchboard already has its 45 degree pieces** (`control_panel_corner_bench_inside`, `_outside` and the mountable
`_chamfer`), and a new benchboard section was not built: the benchboard face falls 45 degrees over a full block of
depth, so a mitred band of it is a hip, which the existing bench chamfer already is.

**Footprint: 2 x 2 blocks** (one item; `DeskCornerBlock`, four blocks with a `part` property: `origin`, `left`, `deep`, `deep_left`).
A 1 x 1 chamfer cannot work against a 0.75 deep console: the mitred band's operator-side edge is 0.35 m long (inside) and its
mounting rectangle does not fit. 2 x 2 gives an angled section 1.06 m long on the operator side (inside) and 1.15 to 1.77 m
(outside), and a face of the same 16 columns x 10 rows (1.0 m x 0.625 m) as a straight desk. The straight run arrives at the edge
of the footprint exactly where a straight desk stands, so it joins with no seam. `DeskCornerGeometry` has the plan (mitred joints,
constant depth): inside, runs along `f = 0..0.75` and `x = 0..0.75`, operator-side vertices at 1.5; outside, the second run leaves
backwards (toward the back, so the footprint grows away from the operator) in `x = 0.25..1`. Placing is whole or not at all, from
the origin block (the one at the operator's right; the others go to the operator's left and one block deep, toward the
operator for an inside corner, away for an outside one); breaking any block removes the corner with one drop; a corner that
loses a block another way falls apart. Placing from the four facings reaches all four orientations (left and right U corners,
both bends).

**The 45 degree face.** `PanelGeometry.Frame` was already a general block-space frame (cell origin, column step, row step,
normal) and the chamfer trim blocks already use a diagonal one; the panel code asks `PanelGeometry.frame(be.panelType(),
be.facing())` for every cell, ray, ghost, line and component, so nothing assumed a cardinal face except the frames. The two new
`PanelType`s, `DESK_CORNER_INSIDE` and `DESK_CORNER_OUTSIDE`, are a level top (normal straight up, 0.75 up) whose columns and rows
run along the diagonals of the plan (a cell is still 1/16 block both ways). Their frame origin is given in the local frame of
the **core** block, the one the middle of the face lies in (`left` for an inside corner, `deep` for an outside one), so the face
reaches 0.5 m into the other blocks. Only the core has a block entity and a face (`panelType(state)` is `null` for the other three,
like the duplex board's body cells); a click on any block of the corner is forwarded to the core's block entity
(`DeskCornerBlock.faceEntity`, used by `PanelClientInput`, `PanelClientOperate`, `MimicClientInput` and the block's own vanilla
use paths), and the cell is found by the ray against the true plane exactly as on every face. Placement, operate and wiring checks
(`PanelOps`, `WiringOps`, `AssemblyAccess`) address the core's position and are unchanged: reach is measured to the core block,
which the face never leaves by more than 0.6 m (the reach slack is 1.0). Saved components are the core's, with the usual keys and
absolute claim positions; nothing about a cardinal face changed. **The corner's face is its own panel group of one block**
(`hasRows` and `hasColumns` are false): it does not merge its grid with the straight runs beside it, and components do not span
between a run and the corner (the 0.5 m stubs of console at each end of the corner are not mountable).

**Model and shape.** `art/models/control_desk/build_desk_corner.py` (Blender, headless, procedural like `build_desk.py`) builds the
mitred console from the same profile and slices it into the four block models (`desk_corner_<inside|outside>_<part>.obj`) and a
whole-piece item model; `tools-export-desk-corner.py` writes the model, blockstate, item and loot JSON. Polygons of five or more
corners are split into triangles: the game's OBJ loader drops them (the first build had holes). Previews are in
`art/models/control_desk/previews/` (closed iso and run + corner + run, from the operator's side and from above, plus the in-game
pictures of the panel client check). Collision and outline shapes are stepped per 1/16 cell from the same profile and built
lazily (`DeskCornerGeometry.shape`).

**Recipes.** Grid recipes (not Fabrication Desk ones: `FabricationDeskTests` pins the desk at exactly 42 recipes): three 1 m desks
and an iron ingot (inside), plus an iron nugget (outside, so the two recipes differ); with Mekanism two steel ingots (and two
steel nuggets), the usual doubling.

**Tests.** `DeskCornerTests` (namespace `bwrcorner`): the frames, the face on the console, the shapes under every cell of the face
and the knee space, the models under every cell, placing for every facing, refusal, breaking, mounting through a real block ray
cast and the real `Place` and `Operate` packet handlers, wiring a component to another on the face, groups, finishes, saves. The
panel client check (`-PbwrPanelClientCheck`, stages `corner-inside-*`, `corner-outside-*`) draws both corners with parts mounted
and has the client's own aiming (`PanelClientInput.hoveredClaim`) find the dial at the middle of the face.

**Not built (open design points, owner request after the corners).**
*Longer 45 degree runs.* A run advancing one block along each axis has a pitch of 1.414 m, not a multiple of the 1 m cell grid; a
panel group's columns are 16 cells of one block and cells must stay square (a component model is drawn square from the frame), so
the faces of repeated diagonal pieces cannot be one cell space: each piece would be its own group with 16 of its 22.6 cells (the
rest dead top), exactly like the corners. The piece-by-piece scheme that fits the grid is: a bend piece (a straight run into a 45
degree turn, mitred, left and right hand, inside and outside) and a diagonal straight piece (an L of three blocks, a main block and
two wedge blocks, pitch (1, 1)), with the corner a bend + n diagonals + bend; the core/forwarding mechanism here
(`DeskCornerBlock.faceEntity`) already serves it, and `build_desk_corner.py` already builds a console from any list of sections.
Not implemented: it needs a handedness property, a face index in the frame (one `PanelType` per face, or a per-state frame) and
a block-state per part of a (n+1) x (n+1) footprint.
*Tall panels (benchboard).* The benchboard group is a staircase: each row down is one block toward the operator. A 45 degree run of
it steps (1, 1) blocks per row, 1.41 m of travel for 1 m of drop, a 35 degree face, not the 45 degree one: that is exactly the
existing mountable `CHAMFER_BENCH` facet (normal 35.26 degrees above the horizon). A multi-row diagonal bench therefore tiles only as
stacked chamfer facets, which join nothing today (`hasColumns`/`hasRows` false); making them join into one group needs a diagonal
column/row step in `PanelGroup`. No new tall-panel corner was added.
