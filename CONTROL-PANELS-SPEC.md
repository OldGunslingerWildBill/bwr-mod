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
player-set **label** (unique within the group; auto-assigned like `PB-1`),
type-specific config, and live state.

- **Placement:** right-click a mounting face holding a component item. The
  hit point snaps to the grid as the footprint's top-left. A client-side ghost
  outline shows fit (green) or collision (red) before clicking.
- **Straddling:** a footprint may extend into other blocks of the same group,
  up to 4 × 4 blocks. The anchor block's BE owns the component; every covered
  cell is reserved so nothing else can overlap, and clicks on any covered cell
  are forwarded to the owner. Breaking any block a component covers drops the
  component item.
- **Removal:** sneak-right-click with the panel wrench (§7) drops the item.
- Every component has a recipe and drops itself. `tools-audit-assets.py`
  must stay at 0 problems.

### 2.1 Catalog (v1)

Inputs (produce an output value):
| Type | Footprint | Behavior |
| --- | --- | --- |
| Pushbutton | 2×2 | Momentary 1 while held, color option |
| Toggle switch | 2×3 | Maintained 0/1 |
| Rotary selector | 3×3 | 2–6 labeled positions, outputs position index |
| Control switch (pistol grip) | 3×4 | GE style: positions e.g. CLOSE / NORMAL / OPEN with spring return to NORMAL; red lamp = open/running, green = closed/stopped (US convention; lamps also labeled) |
| Key switch | 2×3 | 0/1, only operable while holding the matching key item (optional) |
| Setpoint knob | 3×3 | Continuous 0–100 % (scroll or drag) |
| Thumbwheel | 1×2 per digit | Numeric entry, N digits |

Indicators and annunciators:
| Type | Footprint | Behavior |
| --- | --- | --- |
| Lamp | 1×1 or 2×2 | On/off/flash, color |
| Annunciator window | 4×3 | Engraved text; ISA-18.1 sequence A: alarm → fast flash; acknowledge → steady; condition clears → off. Group-level ACK/RESET via a pushbutton bound to the group's `ack` |
| Bar graph | 1×N or N×1 | Segmented level with warn/alarm bands |

Gauges:
| Type | Footprint | Behavior |
| --- | --- | --- |
| Round dial | 4×4 to 8×8 | Needle, min/max, units, colored bands, damping |
| Edgewise vertical | 2×6 | Like the GUI mockup: scale, pointer, optional demand marker |
| Edgewise horizontal | 6×2 | Same, horizontal |
| Strip-chart recorder | 6×6 | 1–4 pens, configurable time span, scrolling paper |

Displays:
| Type | Footprint | Behavior |
| --- | --- | --- |
| 7-segment display | 2×3 per digit | N digits, decimal point, color. Digits plus the letters 7-seg can show (A b C d E F G H I J L n o P q r S t U y - _); anything else renders blank |
| 16-segment display | 2×3 per char | Full A–Z, 0–9, punctuation |
| Character LCD | 1 row = 2 cells tall, 1 char = 1 cell wide | Clock-style LCD, 1–4 rows × N chars, 5×7 dot matrix, backlight color |
| Graphic display | up to 4×4 blocks | Pixel framebuffer, 32 px per block edge, palette of 16; drawn by CC (§8) |

Labels and mimic:
| Type | Footprint | Behavior |
| --- | --- | --- |
| Nameplate | 1×W | Engraved custom text, plate/text colors |
| Mimic line | 1×N, corners, tees | Colored bus line; optional binding dims it to gray when 0 |
| Mimic symbol | 2×2 | Valve, pump, tank, turbine, breaker; colored by bound state |

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
switch commanding an unpowered MSIV does nothing, the same as CC would.

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
- Event `bwr_panel_input(label, value)` whenever a player operates an input.

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
