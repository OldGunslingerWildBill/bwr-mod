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

## 10. Rendering and models

- One block-entity renderer per panel block that owns components; extended
  render bounds for straddling components. No per-frame allocation; cache
  static geometry per component and only redraw moving parts (needles,
  lamps, segments, pens).
- LOD: beyond 24 blocks skip text and fine detail; beyond 64 skip components.
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
