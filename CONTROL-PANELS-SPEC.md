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

## 14. 45 degree desk corners and angled tall panels (alpha.61)

The owner's request: "there's no corner blocks or desks that have a 45 deg corner". A run of control desks could only meet another at
a hard 90 degree corner; real BWR consoles, and the reference screenshots, join runs through 45 degree chamfered sections that carry
controls too, and the owner wants to extend such a run **block by block, not pick a length up front**, for the desks and for the tall
panels (the benchboard with the boards above it).

### 14.1 What exists

| Item (`bwr:`) | What | Blocks |
| --- | --- | --- |
| `control_panel_desk_corner_inside`, `_outside` | the **single corner**: both bends and one angled unit in one item (the U, and the wrap away) | 4 each (2 x 2), unchanged from the first cut |
| `control_panel_desk_run_<inside\|outside>_bend_a`, `_unit`, `_bend_b` | the desk **run pieces** | 2, 3 and 2 |
| `control_panel_tall_run_<inside\|outside>_bend_a`, `_unit`, `_bend_b` | the tall panel run pieces | 5 / 9 / 8 inside, 8 / 9 / 5 outside |

**The fixed lengths `_2`, `_3`, `_4` of the first cut are gone** (unreleased, so no save has them): a run of bend A, n units and bend B
covers every length they did and any other, one unit at a time. The length-1 2 x 2 corner stays as the natural single corner: one item,
one face, four blocks, where a run needs three items and ten.

### 14.2 The scheme: bend A + n units + bend B

A run is `bend A`, any number n >= 0 of `unit`s and `bend B`; the straight run before bend A and the one after bend B are the ordinary
straight blocks (desks, or a benchboard with the boards above it). The console is a band of constant depth W bent twice by 45 degrees
with mitred joints (`AngledRunGeometry`; `art/models/control_desk/run_plan.py` is the same maths in Python and writes
`data/bwr/angled_runs/pieces.json`, which the game reads for the cubes of every piece). In the frame of the unmirrored run (x to the
operator's right, f from the back toward the operator, y up; the straight run A arrives from +x at bf = 0):

    inside : t = min(f, (x + f - PHI) / sqrt 2, x - xwall)                    the console is where 0 < t < depth of the profile
    outside: t = max(f, (PHI + tan(22.5) W - (x - f)) / sqrt 2, xwall - x)

The diagonal advances one block in x and one in f per unit, so **a unit is the part of the console between two planes x = const**: the
same shape each time, shifted by one chain step `(-1, +1)` (inside) or `(-1, -1)` (outside). Bend A is the column right of the first
plane (it holds the first mitre and the end of the straight run A), bend B the column left of the last plane (the second mitre and the
beginning of the straight run B, which runs along f). The blocks of every piece come from sampling the plan on a 16 x 16 grid per
block; the pieces of a run of any n partition the console exactly (`thePiecesPartitionARunOfAnyLength...`, n = 0 to 3). A is never
mirror-symmetric to B (the cut planes are x = const, so bend B holds a stub of straight run B along f where bend A holds one along x), which
is why they are two items. **The origins of consecutive pieces are exactly one chain step apart** (bend A (0, 0); unit k at k steps; bend
B at n + 1 steps), so a piece placed against the open end of another lands in line.

**Hand.** The mirror image of the whole plan (x to the operator's left) is the other hand: block state `mirror`; models are the exact
mirror (`..._m.obj`). One item serves both hands and the four facings (the block state has `facing`, `mirror`, `part`, `finish`):
the item reads the hand from what it is placed against, and when it is placed free standing it faces the placer and sneaking
mirrors it. The face frames of a mirrored piece have their columns reversed along the diagonal, so a face reads to the operator's right
exactly as on an unmirrored piece (a test compares the handedness of every frame with the straight panel's: no mirrored pictures).

**Placing snaps** (`AngledRunItem.updatePlacementContext`, `AngledRunBlock.resolve`): a unit (or bend) placed against *any block* of a
piece of the same family and kind goes to that piece's open end (exactly one chain step along) with the facing and hand of that piece,
whichever block was clicked (a unit has two ends and goes to the one nearer the click). A bend placed against a straight desk (or
benchboard) takes its facing from the straight run: bend A's facing is the desk's, bend B's follows from the second leg's, the hand from
which side of the end block is free (or nearer the click). The first of those that fits wins; the whole piece is placed or nothing.
Breaking any block removes that piece alone with one drop (the origin's loot) and releases its parts; the neighbouring pieces stay.
A piece that loses a block another way falls apart. The finish (wrench) paints the piece.

**Faces.** Only a unit has faces, one 16-column face per layer of the family (below), each **its own panel group of one block**
(`PanelType.DESK_RUN`, `RUN_BENCH`, `RUN_VERTICAL`, `RUN_HEADER`: `hasRows` and `hasColumns` false): a unit is 1.414 m long and a face
1.0 m wide, 0.41 m of plain surface between neighbours, and cells must stay square. The block that holds the middle of a face (its
**core**) is a panel block with the usual block entity, claims and saves; the other blocks are body only (no entity, no cells), like the
duplex board's body cells. A click on any block finds the face the view ray lands on (`AngledRunBlock.faceEntity`, reached through the
same `DeskCornerBlock.faceEntity` every call site already used: the faces reach into the blocks of the pieces beside the unit, so the
unit and the two neighbours are looked at, the nearest hit on a face wins, a hit on a face itself before one within half a cell of an
edge). The faces of the four layers of a tall unit have their middles in four different blocks (checked: no two share a block). A
frame (`AngledRunGeometry.faceFrame`, picked by `PanelGeometry.frame(be)`) is given in the local frame of the core.

### 14.3 The desk run

Family `DESK`: the profile of `control_panel_desk` (0.75 deep, the top 0.75 up, the overhang, the riser and the shelf, the knee front),
one block layer, the level top of each unit a 16 x 10 face turned 45 degrees (1.0 m x 0.625 m, as a straight desk). PHI 6/16 inside,
5/16 outside. Pieces of 2 (bend A), 3 (unit) and 2 (bend B) blocks. The 2 x 2 single corner keeps its own model files.

### 14.4 The tall panel run: a true 45 degree angled section

**What "tall panel" is.** The benchboard run as it stands in a control room is the bench (the 45 degree `control_panel_benchboard` wedge
at the floor), two vertical boards above it and the header strip, which leans back 5 degrees (`FurnitureClientCheck`, the owner's
reference pictures `5.png`, `6.webp`). Family `TALL` is exactly that profile, four layers high: layer 0 the bench wedge (the exact
`BENCH_PROFILE` of the trim builder, toe-kick and all), layers 1 and 2 vertical boards (0.25 deep, face at 0.25), layer 3 the header
(0.25 deep at its foot, leaning back 5 degrees). Fixed at that height: a taller or shorter run is built with the straight blocks, the
angled section is the four rows of the owner's panel. (**Interpretation to confirm:** the first cut's analysis spoke of a *staircase* of
benchboard blocks; the owner's own panels are bench + boards, and the references show that, so the section is built to match. A stack of 45
degree wedge rows stepping forward would be another family of the same machinery.)

**Why it is a true 45 degree section and not a 35 degree one.** The first cut concluded that a diagonal staircase of blocks cannot give
the face angle of a straight run, because each block row would have to step a whole block. A multiblock whose rows are placed freely in
block space has no such constraint: the profile is **swept along the diagonal** exactly as the desk's, so every layer's face angle is that of
the straight panel (bench 45 degrees, boards 90, header 85: `everyUnitFaceLiesOnItsProfileAtTheRightAngle...` compares the normal, the cell
shape and the handedness of every face with the straight `BENCHBOARD`, `VERTICAL` and `HEADER_TILT` frames, for both hands and all four
facings) and the sections meet the straight runs at the mitre with **flat sides and no lip**: bend A's end and bend B's end are the plain
profile caps of the straight run, plane to plane (the `control_panel_corner_bench_*` blocks stay as they are, old saves use them). Each face
of a unit is its own group, owned by the block that contains the face's middle; the rows are not on the block grid (the faces of the three
upper layers reach 0.07 m into the neighbouring pieces, the bench's up to 0.2 m), which is why a face is addressed through its core block.

Hard 90 degree inside corner of two tall runs (`6.webp`): the section fills it, with a triangular void between the two walls and the
diagonal back (the room stage of the client check shows it). PHI 8/16 inside, 1/16 outside. Pieces: bend A 5 blocks inside (8 outside),
unit 9, bend B 8 inside (5 outside), at most 10 blocks per piece (the `part` property is 0..9).

### 14.5 Models, shapes, recipes, tests

* **Models** (`art/models/control_desk/build_angled_runs.py`, Blender, headless, procedural; `tools-export-angled-runs.py` writes the JSON): the
  profile of each family swept along mitred sections, sliced into the cubes of each piece (x, f **and y**), a model per part and per hand
  (128 OBJ files, `run_<fam>_<kind>_<role>_<part>[_m]`), caps only at the two real ends; polygons of five or more corners are split
  (the game's OBJ loader drops them). The script asserts that units 1, 2 and 3 slice identically and that every face lattice lies on its
  surface. Blender previews: `art/models/control_desk/previews/desk-run-*` and `tall-run-*` (built piece by piece, three units; `-pieces`
  tints bend A, units and bend B).
* **Shapes**: stepped per 1/16 cell from the same profile and built lazily (`AngledRunGeometry.shape`); the header's box is the whole block
  (up to 0.09 m proud of its lean at the top), the bench's wedge is cut at the back of each cell.
* **Recipes**: grid recipes (not Fabrication Desk ones: `FabricationDeskTests` pins the desk at 42 recipes), shapeless, each with a Mekanism
  twin (an iron ingot is two steel ingots, a nugget one steel nugget): desk bend A inside = desk + nugget (outside: two nuggets), bend B = desk
  + ingot (outside: + a nugget), unit = two desks + nugget (outside: two nuggets); tall pieces the same with a benchboard and one (bends) or three
  (unit) vertical boards in place of the desks (`tools-write-angled-run-recipes.py`).
* **Tests**: `DeskCornerTests` (the single corner) and `AngledRunTests` (namespace `bwrcorner`): the plan and the partition, the frames, the
  shapes, the models of every part in both hands, placing every piece for every facing and hand, refusal, building a run by clicking (bend A
  against a straight run, units against the open end, a fourth unit, bend B), the facing and hand taken from straight runs, free-standing
  placement, breaking (one piece, one drop, the finish, the parts), mounting through a real block ray cast on every face of every unit (found
  through the block under the crosshair) and the real `Place` and `Operate` handlers, wiring, groups, finishes, saves, registration. The
  panel client check has the stages `angled-run-*` (`-PbwrPanelClientStages=angled-run`): the desk and the tall run, inside and outside,
  piece by piece and close to a face, and a room corner filled by the angled tall panel; the client's own aiming finds the dial at the
  middle of the face.
