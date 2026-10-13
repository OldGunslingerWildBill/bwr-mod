# Control panels: building and wiring a control room

Player guide and CC reference for the control panel system (design in `CONTROL-PANELS-SPEC.md`, plant
signals in `SIGNALS.md`). Panels are boards you cover with instruments and controls and wire to the plant,
to each other, to redstone, or to ComputerCraft. **Nothing on a panel acts by itself:** a control commands the
plant only when a player (or a CC program the player wrote) operates it.

Components shipped with the wiring layer: nameplate and indicator lamp. The input components (pushbutton,
switches, knobs), the annunciator, the gauges and the displays are added by their own steps of the build
order and plug into the same wiring; the worked example below names them as the spec does.

## 1. The tools

| Item | Use |
| --- | --- |
| Panel blocks (vertical, benchboard, console) | Place them side by side; blocks of one style and facing whose faces line up form a **group** |
| Component items | Right-click a panel face to place (sneak-click cycles the size). The ghost is green when it fits |
| Panel wrench | Right-click a component: label, colours and other settings. Sneak-right-click removes it |
| **Panel linker** | Wires components. See below |

### Component sizes

A panel block face is a 16 x 16 grid of cells (one cell is 1/16 block). Components are sized so a wall reads
from a few blocks away; right-click air with the item to cycle through the sizes (width x height in cells,
the default first). A component may span up to 4 x 4 blocks.

| Component | Sizes (cells) |
| --- | --- |
| Pushbutton | 6x6, 8x8 |
| Toggle switch, key switch | 6x8, 8x10 |
| Rotary selector | 8x10, 12x14 (the extra rows hold the position name) |
| Setpoint knob | 8x8, 12x12 |
| Control switch | 8x12, 12x16 |
| Thumbwheel | 3 cells wide per digit, 6 tall: 9x6 (3 digits), 3x6 to 24x6 (1 to 8 digits) |
| Lamp | 4x4, 6x6 |
| Annunciator window | 12x8, 16x12 |
| Bar graph | 3x24, 3x12, 3x16, 3x32 and the same lying down (24x3 ...); one segment per 2 cells |
| Round dial | 12x12, 16x16, 20x20, 24x24, 32x32 |
| Edgewise meter | vertical 6x16, 6x24, 8x24, 8x32; horizontal 16x6, 24x6, 24x8, 32x8 |
| Strip-chart recorder | 16x16, 24x16, 32x24 |
| 7- and 16-segment display | 5 cells wide per digit, 8 tall: 20x8 (4 digits), 1 to 12 digits |
| Character LCD | 3 cells wide per character, 4 tall per row: 48x8 (16 x 2), 1 to 4 rows of 8 to 20 characters |
| Graphic display | 32x32 (2 x 2 blocks), 16x16, 48x48, 64x64 and rectangles; 2 pixels per cell |
| Mimic line | 3 cells thick: 16x3, 8x3, 4x3, 3x3, 32x3, 64x3 and vertical; corners and tees 6x6 to 16x16 |
| Mimic symbol | 6x6, 8x8, 10x10, 12x12 |
| Nameplate | 4 cells tall: 16x4, 8x4, 24x4, 32x4, 48x4, 64x4, 24x6, 32x8 |

Engraved text, captions and labels scale with their component. Components placed before this size change keep
the size they were placed at; a display's digit, character and segment counts follow its width, so an old
display shows fewer digits or characters than before.

### The linker

* **Sneak-right-click a plant device** (any block of it: a reactor controller, an MSIV, a pump...) to store
  it. The action bar says what was linked; a block that reports no signals is refused. Sneak-right-click the
  air to forget the device. The tooltip shows the stored device.
* **Right-click a component** (not sneaking) to open the binding screen. Choose a port (an *input* is a
  value the component displays, an *output* is a value a control produces), choose what to wire it to, and
  press Apply. Clear removes the wiring of the chosen port.
* **Hold the linker and look at a wired component:** every plant device it reads from is outlined in cyan
  and every device it commands in orange, through walls, so you can follow a run across the plant.
* A device can be bound only if it is **in the same dimension** and within `linkRange` blocks of the panel
  block (config `bwr-panels.toml`, default 64), loaded, and permitted for you (claims).

The wrench screen shows linker-made inputs read-only ("Plant position @ x, y, z", "Redstone", ...) and
saving it never unwires them; unwire with the linker.

## 2. What a port can be wired to

Every component has numbered **inputs** (it displays them) or **outputs** (a control produces them). **An
input has exactly one source; an output can fan out to up to 8 sinks.**

### Input sources

| Source | Reads | Notes |
| --- | --- | --- |
| Plant signal | One signal of the linked device, e.g. the reactor's `pressure` | Sampled on the server at most every `sampleTicks` ticks (default 2); only changes are sent to clients, batched per chunk and tick. An unloaded or removed device, or a signal with no value, reads **no signal**. Never loads a chunk |
| Component | An output of another component **in the same group** | Pushed the moment the control changes, no polling. Follows the component by id, so renaming labels does not break it. Removing the control gives no signal |
| Redstone | Signal strength 0 to 15 at the **back face** of the panel block that holds the anchor | Read like a repeater reads its input |
| CC | A value a program sets with `set` | Shows **awaiting CC** (no signal) until the first set; the value is saved |
| Constant / none | Wrench screen | A fixed number, or no signal |

A panel is sampled only while it has a plant or redstone input; a panel with none costs nothing per tick.

### Output sinks

| Sink | Does | Notes |
| --- | --- | --- |
| Plant command | Sends `output x scale` to a command of the linked device, e.g. the MSIV's `open` | The same code as the device's CC setter, so power, interlocks and `computerControlled` behave identically. A PULSE command (scram) fires only for a non-zero value, so a pushbutton acts on press, not release. Mode *momentary* sends a fixed value on a rise instead (see below) |
| Redstone | Emits `round(output x scale)` clamped to 0 to 15 from the **back face** | The strongest of the block's redstone sinks wins. Neighbours update like a repeater's (the block behind it is strongly powered) |
| CC event | `bwr_panel_input(name, label, value, port)` | Always on: every operated output raises it once for every computer attached to the group. Not stored per output |

A command fires only when a control's output **changes because someone operated it**. Loading a world,
re-sampling, rebinding or resyncing never fires a sink. Setting the same value again fires nothing.

**MSIV note.** Commanding an unpowered MSIV open records the demand, exactly as CC's `open()` does; the valve
opens when power returns. The wiring reports that it cannot move yet; the demand stands.

### Momentary sinks

A plant command sink has a mode. **Follow** (the default for existing bindings) sends `output x scale` on
every change. **Momentary** sends one fixed value when the output becomes non-zero and nothing when it
returns to zero. Use it for controls that spring back or are pressed and released: a spring-return control
switch goes OPEN = 1 then back to 0, so a follow sink on the MSIV's `open` would open the valve and shut it
again on release. Real GE control switches are momentary and the equipment seals in; a momentary sink does
the same. A pushbutton can start or stop a pump the same way (one button momentary 1, another momentary 0).

The binding screen shows a mode button and a "send value" field for plant outputs, and defaults to
momentary when you bind a control switch's `open` (sends 1) or `close` (sends 0) output, or a pushbutton's
output (sends 1), to a BOOLEAN or LEVEL command. The value must lie within the command's range. You can
switch back to follow. Momentary sinks, like all sinks, fire only on an operated change, never on load,
resample or rebind. Redstone sinks have no mode.

### Scale and offset on plant inputs

A **plant signal** input has a linear transform, `shown = raw x scale + offset` (defaults 1 and 0), set with
the Scale and Offset fields of the binding screen when the mode is *Plant*. Two presets sit under the fields:
*Raw (x1)* and *Fraction to % (x100)*. The scale must be finite and non-zero (at most 1,000,000 in size) and
the offset finite (same bound); the server refuses anything else. No signal stays no signal: a removed or
unloaded device still reads dashes, never `offset`. Use it where a display or gauge should show a signal in
other units or a 0 to 1 fraction as a percentage:

* MSIV `position` is 0 to 1. Scale 100 makes a 7-segment with format `{0:3.0f}` read `100` when open and `0`
  when closed (without the scale it reads `1.00` / `0.00`).
* Reactor `pressure` in psig on a gauge ranged in MPa: scale 0.00689476.
* A kg/s signal shown in t/h: scale 3.6. Celsius to kelvin: offset 273.15.

The transform is applied where the plant signal is read, so everything downstream (the display format, a
gauge's range, an annunciator's setpoint) sees the transformed number. It is saved on the binding only when it
is not the identity, so older worlds load and save unchanged. Component, redstone and CC inputs have no
transform. The wrench screen shows a scaled binding as "Plant key @ x, y, z (xS + O)".

### Scale (sinks)

A sink has a scale factor, default 1 (redstone defaults to 15 in the screen). Use it to match ranges: a 0 to
100 percent knob driving a 0 to 1 demand needs scale 0.01; a 0/1 switch driving redstone needs scale 15.

## 3. Worked example: a small reactor control room

A vertical board five blocks wide and three high (one group, 80 x 48 cells; see the component sizes above). Link the reactor controller once,
then wire left to right. Signal and command keys are in `SIGNALS.md`. Component appearance and settings
(ranges, bands, formats, alarm setpoints) are each component's own wrench screen; this example is about the
wiring, which is identical for every type.

1. **Nameplates.** A row of nameplates along the top: `REACTOR`, `POWER`, `MSIV`. Static engraved text
   (wrench screen), or changed from CC with `setText`.
2. **Reactor pressure dial** (round dial). Sneak-right-click the reactor controller with the linker.
   Right-click the dial: port *value*, mode *Plant*, signal `pressure` (Dome pressure, psig), Apply. Set the
   dial's range to 0 to 1400 in the wrench screen.
3. **Power display** (7-segment). Same device, signal `aprm_percent`. With no signal (reactor not formed,
   chunk unloaded) a display shows dashes.
4. **MSIV control switch with lamps.** Sneak-right-click the MSIV with the linker.
   * Control switch (CLOSE / NORMAL / OPEN, spring return): right-click it, an *output* port, mode *Plant*,
     command `open` (Open / close), **twice**, both *momentary*: output `open` with value 1, and output
     `close` with value 0 (the screen suggests exactly this). The device treats 0.5 and above as open.
     Turning the switch to OPEN sends 1 once; the spring return to NORMAL sends nothing, so the valve stays
     open until the switch is turned to CLOSE. Operating it commands the valve exactly as CC's
     `open()`/`close()` would. On an unpowered valve the open demand is recorded and the valve opens when
     power returns.
   * Open-position display (7-segment, format `{0:3.0f}`): input *value*, mode *Plant*, signal `position`,
     **Scale 100** (press *Fraction to %*). It reads `100` with the valve fully open, `0` closed, and the
     travel in between.
   * Red lamp (open): input *state*, mode *Plant*, signal `position`. It lights for any non-zero position, so
     it glows while the valve is open or opening.
   * Green lamp (closed): input *state*, mode *Plant*, signal `position` on a second lamp plus a nameplate
     `CLOSED`; or, to show the demand rather than the valve, mode *Component* reading the switch's output.
   * Label the lamps with nameplates: colour is never the only cue.
5. **High-pressure annunciator** (annunciator window). Input *condition*: mode *Plant*, signal `pressure`,
   and set the alarm test (input above a setpoint such as 1050) in its wrench screen. It flashes, a
   pushbutton wired to its acknowledge input (mode *Component*, the pushbutton's output) makes it steady, and
   it goes out when the condition clears. The annunciator only displays; it never commands.
6. **Scram pushbutton** under a `SCRAM` nameplate: *output*, mode *Plant*, command `scram` on the reactor
   controller. `scram` is a PULSE command, so it fires on press only.
7. **Horn via redstone.** A toggle switch `HORN`: *output*, mode *Redstone*, scale 15. Switch it on and the
   block behind the board is powered; put a note block or a lamp there.

What the example shows: values flow plant to display with no player action; commands flow from controls to
the plant only when someone operates a control; lamps and nameplates carry text.

## 4. CC:Tweaked reference

Soft dependency: panels, the linker and everything above work without CC. With CC, **any block of a panel
group** is the peripheral `bwr_panel` for the whole group. Components are addressed by **label** (unique in
the group, shown in the wrench screen; auto-assigned like `LP-1`).

**Label uniqueness.** Labels are unique when you place a component and when you rename one with the wrench (a
rename to a label already used in the group is refused and the old label stays). Joining two groups can bring
two equal labels together (both walls had `PB-1`). When a group forms or merges, the duplicates among its
loaded blocks are renamed once: in order of owner block position (Minecraft `BlockPos` order: lowest Y, then
Z, then X), then component id, the first holder keeps the label and each later one becomes the next free
`PREFIX-n` (for auto labels like `PB-1`) or `label-2`, `label-3`... (for custom labels). A rename is saved and
synced like a wrench edit. Renames only move away from a clash and never back, so splitting and re-joining
walls does not rename again; a block in an unloaded chunk is not touched until it loads and its group is
recomputed. Scripts should read labels with `list()` instead of hard-coding them after joining walls. If two
components ever do share a label, `list()` and `getInputs()` still show both (the first by position as
`label`, the others as `label#2`, `label#3`) and `get(label)` is deterministic.

```lua
local panel = peripheral.find("bwr_panel")
```

Arguments are numbers, strings and booleans (and a table of strings for `blit`). Every call runs on the
server thread; a bad label or argument raises an error.

| Call | Returns | Description |
| --- | --- | --- |
| `list()` | `{label = {type, width, height}}` | Everything on the group |
| `get(label)` | `{type, label, width, height, inputs = {port = value}, outputs = {port = value}}` | Live values; an input with no signal is absent |
| `getInputs()` | `{label = {port = value}}` | The current **output** of every control (switches, knobs, buttons): the "inputs" a program reads |
| `set(label, value)` | boolean | Sets the first input of a component that is wired to **CC**. Errors if that input has another source or the value is not finite |
| `setPort(label, port, value)` | boolean | Same, for the input named `port` (keys as in `get(label).inputs`) |
| `setText(label, text)` | boolean (changed) | Text of a nameplate or text display |
| `getSize(label)` | `width, height` | Pixels (graphic display) or characters and rows (character LCD) |
| `clear(label, color)` | boolean | Clears to a palette colour; an LCD clears its text and ignores the colour |
| `setPixel(label, x, y, color)` | boolean | Graphic display |
| `fill(label, x, y, w, h, color)` | boolean | Graphic display |
| `drawText(label, x, y, text, color)` | boolean | Graphic display |
| `blit(label, rows)` | boolean | `rows` is a table of hex strings, one palette index per digit, from the top-left |
| `setPalette(label, index, rgb)` | boolean | `rgb` as `0xRRGGBB` |
| `write(label, row, column, text)` | boolean | Character LCD, 0-based |

The drawing calls work on components whose data implements `PanelDrawTarget`; on anything else they raise
"is not a drawable display". Drawing is coalesced: however many calls arrive in a tick, each watching client
gets one update.

**Event** `bwr_panel_input`: `event, name, label, value, port = os.pullEvent("bwr_panel_input")` whenever a
player operates a control, for every computer attached to the group (also fires for outputs wired to
nothing, and is not raised by `set`). `name` is the peripheral name the computer sees for the panel, like the
first argument of `monitor_touch`: `"top"` for a panel the computer touches, or `"bwr_panel_3"` through a
wired modem. Labels are unique only within a group, so with two walls on one computer use `name` to tell
them apart. A computer gets **one** event per change per group, even when it is attached to several blocks
of the group (say a side plus a modem on another block); the `name` is then the one that was attached first
(if that detaches, the next one). A computer attached to two different groups sees each group's events under
that group's name.

```lua
local panel = peripheral.find("bwr_panel")
panel.setText("NP-2", "TURBINE ONLINE")
while true do
  local _, name, label, value, port = os.pullEvent("bwr_panel_input")
  if name == peripheral.getName(panel) and label == "SW-MSIV" then
    peripheral.find("bwr_msiv").setOpen(value >= 0.5)
  end
  panel.set("DISP-POWER", peripheral.find("bwr_reactor").getAprmPercent())
end
```

Several panels on one computer (two walls through modems):

```lua
local panels = {}
for _, n in ipairs(peripheral.getNames()) do
  if peripheral.getType(n) == "bwr_panel" then panels[n] = peripheral.wrap(n) end
end
while true do
  local _, name, label, value, port = os.pullEvent("bwr_panel_input")
  local panel = panels[name]
  if panel then
    print(name, label, port, value)
    panel.setText("NP-1", label .. "=" .. value)
  end
end
```

(Wire `DISP-POWER`'s input to **CC** in the binding screen first. The reactor and MSIV peripheral calls are
the existing `bwr_reactor` / `bwr_msiv` API.)

## 5. Saves, limits, behaviour worth knowing

* Bindings save **absolute positions** (plant devices, component owners) and the CC value; a world copied
  elsewhere keeps pointing at the old positions.
* A binding to an unknown (future or malformed) kind is preserved untouched and reads no signal.
* Redstone input and output share the back face. A panel reading the same conductor block it powers ignores
  its own output, so it cannot latch itself.
* `bwr-panels.toml`: `sampleTicks` (2), `linkRange` (64), `maxGroupColumns` (16), `maxGroupRows` (8),
  `maxComponentsPerBlock` (64), `maxLabelLength` (16).
* Network protocol version 16. Packets: `panel_linker_open` and `panel_bind` (client to server, validated:
  reach, claim permission, the linker's own device, dimension, range, key exists, momentary value within the
  command's range), `panel_linker_screen`
  (server to client), and `panel_content` (server to client: bounded display content patches, at most one per
  component per tick).
