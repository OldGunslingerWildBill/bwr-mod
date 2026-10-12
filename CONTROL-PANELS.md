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
| CC event | `bwr_panel_input(label, value, port)` | Always on: every operated output raises it for every computer attached to the group. Not stored per output |

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

### Scale

A sink has a scale factor, default 1 (redstone defaults to 15 in the screen). Use it to match ranges: a 0 to
100 percent knob driving a 0 to 1 demand needs scale 0.01; a 0/1 switch driving redstone needs scale 15.

## 3. Worked example: a small reactor control room

A vertical board three blocks wide and two high (one group, 48 x 32 cells). Link the reactor controller once,
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

**Event** `bwr_panel_input`: `event, label, value, port = os.pullEvent("bwr_panel_input")` whenever a player
operates a control, for every computer attached to the group (also fires for outputs wired to nothing, and
is not raised by `set`).

```lua
local panel = peripheral.find("bwr_panel")
panel.setText("NP-2", "TURBINE ONLINE")
while true do
  local _, label, value, port = os.pullEvent("bwr_panel_input")
  if label == "SW-MSIV" then
    peripheral.find("bwr_msiv").setOpen(value >= 0.5)
  end
  panel.set("DISP-POWER", peripheral.find("bwr_reactor").getAprmPercent())
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
