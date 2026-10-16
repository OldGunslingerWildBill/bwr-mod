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
| **Mimic tape** | Draws flow lines with arrows on the board face: see section 2d |

### Component sizes

A panel block face is a 16 x 16 grid of cells (one cell is 1/16 block). Components are sized so a wall reads
from a few blocks away; right-click air with the item to cycle through the sizes (width x height in cells,
the default first). A component may span up to 4 x 4 blocks; the two core displays (below) are the exception and
may span up to 8 x 8, because a 185-rod core is 96 cells across.

| Component | Sizes (cells) |
| --- | --- |
| Pushbutton | 6x6, 8x8 |
| Toggle switch, key switch | 6x8, 8x10 |
| Rotary selector | 8x10, 12x14 (the extra rows hold the position name) |
| Setpoint knob | 8x8, 12x12 |
| Control switch | 8x12, 12x16 (new GE target pistol defaults to 12x16) |
| Thumbwheel | 3 cells wide per digit, 6 tall: 9x6 (3 digits), 3x6 to 24x6 (1 to 8 digits) |
| Lamp | 4x4, 6x6 |
| Annunciator window | 12x8, 16x12 |
| Bar graph | 3x24, 3x12, 3x16, 3x32 and the same lying down (24x3 ...); one segment per 2 cells |
| Round dial | 12x12, 16x16, 20x20, 24x24, 32x32 |
| Edgewise meter | vertical 6x16, 6x24, 8x24, 8x32; horizontal 16x6, 24x6, 24x8, 32x8 |
| Strip-chart recorder | 16x16, 24x16, 32x24 |
| Paperless recorder | DX1000 16x16 (one block, the default), 12, 20, 24; DX2000 32x32 (2 x 2, twice a DX1000), 40, 48; all square |
| 7- and 16-segment display | 5 cells wide per digit, 8 tall: 20x8 (4 digits), 1 to 12 digits |
| Character LCD | 3 cells wide per character, 4 tall per row: 48x8 (16 x 2), 1 to 4 rows of 8 to 20 characters |
| Graphic display | 32x32 (2 x 2 blocks), 16x16, 48x48, 64x64 and rectangles; 2 pixels per cell |
| Mimic line | 3 cells thick: 16x3, 8x3, 4x3, 3x3, 32x3, 64x3 and vertical; corners and tees 6x6 to 16x16 |
| Mimic symbol | 6x6, 8x8, 10x10, 12x12 |
| Mimic path (tape, not a component) | none: free points on the cell grid, 2 to 64 points, across blocks |
| Nameplate | 4 cells tall: 16x4, 8x4, 24x4, 32x4, 48x4, 64x4, 24x6, 32x8; valve plates 32x10, 24x8, 32x12 |
| Full core display | 96x96, 128x128, 64x64, 80x80, 112x112, 48x48, 128x96, 128x80, 112x80 (up to **8 x 8 blocks**) |
| Rod select matrix | 64x64, 48x48, 80x80, 96x96, 112x112, 128x128, 32x32 (up to 8 x 8 blocks) |

The GE target pistol and W-2 heavy-duty pistol prefer 12x16 so their handles fit. New control-switch
items use the GE target pistol and that larger size. Explicit item sizes and already placed components
keep their dimensions; changing a placed switch's style does not resize its occupied panel area.

Engraved text, captions and labels scale with their component. Components placed before this size change keep
the size they were placed at; a display's digit, character and segment counts follow its width, so an old
display shows fewer digits or characters than before.

### Looks: style and variant

Control switches, rotary selectors, key switches, pushbuttons, lamps, setpoint knobs and the three meter types
have a **Style** in the wrench screen: `classic` (the simple built-in look), `ge` and, for the three switch
types, `westinghouse`. Types with several models also have a second choice: the **Handle** (pistol grip, heavy
duty pistol; oval, knurled, lever, round notched, mini slimline; removable key, tumbler lock), the **Button**
(flush, extended, mushroom, guarded, illuminated), the **Lens** (round, round with plate, ET-16 dome, square) or
the **Pointer** (black, red). New components start as `ge` when the model ships and `classic` otherwise; saves
from before this feature load as `classic`. If a model file is missing the component simply draws `classic`.
Cap, lens and knob colours still work: a colour you set recolours the model, and the default colour leaves the
model's own. The control switch's target flag shows the plant state when its lamp inputs are wired, otherwise
the last operation. The switch's "Target red after" setting picks which operation leaves it red: CLOSE
(default, as on a GE breaker control switch) or OPEN (valves and pumps). The big breaker-control switches read best at their larger 12x16 size.

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

## 2b. Core displays: the full core display and the rod select matrix

Two components show the whole core, one rod and one LPRM string at a time, from **one binding to the reactor
controller**. You never wire a port per rod: the component reads the bound reactor itself (all the rods, their
flags, the selected rod and the LPRM strings) in one snapshot on every panel sample and sends it to clients as one
content patch only when something changed. Neither acts on the plant by itself.

| Component | What it is |
| --- | --- |
| **Full core display** (`full_core_display`) | A read-only picture of the core, in one of two styles chosen in the wrench screen (`Display style`): `columbia` (a lamp matrix) or `browns_ferry` (digital modules) |
| **Rod select matrix** (`rod_select_matrix`) | One square illuminated pushbutton per rod in core layout. Pressing one selects that rod; the selected rod's button lights white |

### Placing and binding one

1. Build a vertical or benchboard group big enough (at the default tile size a 185-rod core is **96 x 96 cells, 6 x 6
   blocks** as a Columbia display and 51 x 51 as a matrix). Cycle the item to the size you want (right-click air) and
   place it on the group like any component. It may cover up to 8 x 8 blocks (128 x 128 cells).
2. Sneak-right-click the **reactor controller** with the linker, then right-click the display with the linker, choose
   the input `Reactor`, mode *Plant*, and any signal (`formed` is the natural one: the binding only says *which*
   reactor), Apply.
3. The layout comes from the core: its rod lattice, its numbering in the GE `XX-YY` convention and its LPRM strings.
   A smaller or compact core draws a smaller picture centred in the component.

**Size and `tile_cells`.** The wrench screen has `Tile size (cells)` from 2 to 6. A new component starts at the
largest size at which a full 185-rod core fits the 8-block span (128 cells) with its margins and caption: 4 for the
Columbia display, 3 for Browns Ferry, 6 for the rod select matrix. A smaller footprint gets the largest of those
that it holds (never below 3, the old default), changing the look moves a size that is only the old look's default to
the new one's, and a size the player chose, and any saved component (one saved without the key loads as 3), keeps
what it has. Columbia is 2 tiles a rod
plus one tile of margin: `(2 x rods across + 2) x tile_cells` (96 at 15 rods across and 3 cells; 128 at 4 cells; the
largest core, 21 across, needs 2 cells to fit 8 blocks). Browns Ferry is a module of 2 x 1.6 tiles per rod plus a
strip of 1.4 tiles in each LPRM gap and the margins for the numbers (about 127 x 77 cells for 185 rods at 3). A
matrix is `(rods across + 2) x tile_cells`. **If the drawing would not fit the component, the linker refuses the
binding and the wrench refuses the setting**, with a message that names the size needed and the component's size
("This core needs 96 x 96 cells (6 x 6 blocks) at this tile size; the component is 64 x 64 ..."). A component that
is already bound when its reactor is rebuilt larger shows `NEEDS 96 x 96` instead of a picture. Binding the input to
a device that is not a reactor controller is refused too.

**Labels.** The wrench screen of both components has `Caption above` (for example `FULL CORE DISPLAY` or
`ROD SELECT`, drawn in the margin above the picture; CC `setText` sets it) and `Rod coordinate legends` (on by
default: the `18-31` on tiles, modules and buttons). The display also has `Edge row and column numbers`: automatic
(on for Browns Ferry, off for Columbia), on, or off; on in Columbia style prints the GE row numbers and column numbers
in the margin of the backplate. The rod coordinates and the LPRM string labels themselves always come from the core.

**Unbound, unformed, unloaded.** An unbound display draws a dark 185-rod reference layout (the frame and dark tiles) so
you can see what you placed. With the binding removed, the reactor unformed or its chunk unloaded every lamp goes dark
and the tiles stay; nothing ever lights from a stale reading.

### Columbia style

Every rod is a 2 x 2 block of square tiles, each tile split into an upper and a lower legend half that light on their
own:

| Tile | Upper half | Lower half |
| --- | --- | --- |
| A | rod coordinate, e.g. `18-31` (lights **white** when the rod is selected) | `DRIFT` (amber) |
| B | `FULL IN` (white) | `FULL OUT` (red) |
| C | `ACCUM` (amber: accumulator trouble) | `SCRAM` (blue) |
| D | blank, black, never lit | |

The blocks are mirrored so the blank D tiles of four neighbouring rods meet in a 2 x 2 black cluster, and a small yellow
dot marks the middle of each cluster, which is where an LPRM string stands. The mirroring follows the strings of the
bound core (a core with none uses plain parity: an even column reads A B over C D, its neighbour B A over D C), and
there is a dot only where there is a string. The backplate is the union of the rod blocks grown by one tile.

### Browns Ferry style

Each rod module has a **two digit position readout** (00 to 48; green `00` fully in, red `48` fully out, amber between)
and under it a small lamp cluster: ACCUM and SCRAM in the left column, SELECT (which carries the rod coordinate) and
DRIFT in the right, with FULL IN and FULL OUT as small lamps flanking the digits. Between module columns stand the
**LPRM strips**: four lamps, level D at the top and A at the bottom as on the real display, with the string coordinate
below (`08-49`). A level lamp is lit white while the chamber is in range, amber for upscale, blue when bypassed, red
when failed, and dark for downscale or an unformed core. The GE coordinates run along the left (rows 03 ... 59) and the
bottom (columns 02 ... 58) with black lines to the modules. The style draws no backplate: the board's own colour shows.
The mod does not model SRM or IRM detectors by position, so the `SRM-A` and `IRM-C` labels of the real board are not drawn.

Thresholds (wrench screen): `Accumulator trouble below` (default 0.9 of a full charge) and the LPRM `downscale below`
(5 percent) and `upscale above` (110 percent). They are gameplay defaults: the core has no notion of a low accumulator,
and no source for exact GE alarm values was found.

### Selecting and moving rods from a benchboard

The matrix has one output, `rod`, whose value is the number of the rod last pressed. Bind it with the linker to the
reactor's `select_rod` command in **follow** mode (the default for this output): every press sends the rod number,
including a press of the rod that is already selected. The rod motion controls are ordinary panel components: wire
pushbuttons (the `guarded_yellow` button variant is the large yellow one under a clear guard) to the reactor's
`selected_rod_insert` and `selected_rod_withdraw` (one notch a press; PULSE commands, so they act on press) and
`selected_rod_insert_continuous` / `selected_rod_withdraw_continuous` (held: the sink must be *follow* so the release
sends 0; the linker defaults these two to follow). They move the selected rod only, through the same drive network
and with the same CRD power and water rules as `rod_<n>_position`. Details and CC calls are in `SIGNALS.md`.
A held button lets go by itself a second and a half after the player's last press (the client repeats the press while the key
is down), when its panel's chunk unloads and when the block is removed, so a player who drops off the network, or whose panel
unloads while the reactor stays loaded, never leaves a rod running. The selection itself is reactor state: it survives a rejoin.

**There are no rod blocks, no sequence control and no worth minimiser.** RWM, RSCS and RBM are protection logic and
the mod never provides them; build them from the signals with CC or relays if you want them.

## 2c. Paperless recorder (Yokogawa DX1000 and DX2000)

`paperless_recorder` is a multi channel recorder with the look and the keys of a Yokogawa DX1000 or DX2000. Put any plant
signal on any channel with the linker (a plant signal, a component, redstone, CC or a constant) and give each channel its own
tag, unit, span, colour and up to four alarms. **It records and indicates only: alarms drive nothing, it has no outputs, and
the one thing a player does to an alarm is acknowledge it.**

| | DX1000 | DX2000 |
| --- | --- | --- |
| Footprint (cells, square) | 12, **16** (one block, default), 20, 24 | 32 (two blocks, default), 40, 48: always twice a DX1000 |
| Channels | 2, 4, 6 or 12 | 4, 8, 10, 20, 30, 40 or 48 |
| Groups | 10 of up to 6 channels | 36 of up to 10 |
| Screen | 320 x 240 | 640 x 480 |
| Extra screens | | four-panel (MIX, all trend, all digital, all bar) and the circular trend |

A new recorder is a DX1000 at one block and a DX2000 at 2 x 2 (right-click air with the item cycles the size; the make follows the size, 32 cells and up is a DX2000); the make and the
channel count are set on the setup screen. One input port per channel (`ch01` ... `ch48`); the binding screen pages its port
selector twelve at a time when a recorder has more.

**Screens** (DISP/ENTER opens the display selection menu): trend (horizontal, vertical or wide; 30 dots to a division, every dot the
minimum and maximum of its time, trend intervals 5 s to 10 h per division, trip lines, messages), digital, bar, overview (a tile
per channel, coloured by its most urgent alarm), alarm summary (the last 200 alarm events) and, on the DX2000, the four-panel
and circular displays. The status bar shows the group, the in-game date and time (or the real clock, a setting), the history held
and the alarm icon (flashing red while an alarm is unacknowledged, flashing green when they have cleared but not been acknowledged).
The background is white or black.

**Keys** are the model's own parts and are clicked on the face: DISP/ENTER and the arrows (up and down move in the menu; outside it
left and right change the group and up and down step trend, digital and bar), FUNC (soft keys: alarm ACK, message 1 to 10, free
message, START, STOP; the last soft key pages), START and STOP (memory sampling: while stopped no dot is closed and nothing is
logged, the alarms still indicate), ESC, MENU (the setup screen), USER and the favourite key (a click and nothing else). The DX2000's
key-panel cover hides the soft keys and every key but DISP/ENTER and the arrows while it is closed; its tab opens and closes it
(saved with the component). The menu and the FUNC strip are client side: each player sees their own.

**Alarms** are H, L, T and t (delayed, seconds), R and r (rate of rise and fall over 1 to 32 scans of one second), each with a
hysteresis, a level (1 to 4) and a colour, and may be hidden (not indicated, not logged). With hold indication (the default) an
alarm flashes until it is acknowledged; a cleared one flashes green until it is. ACK is FUNC then the alarm ACK soft key, or a panel
pushbutton with the `ack` role and the same group number as the recorder's `AckGroup` (default 1). A signal with no value holds an
alarm's state and shows `-----`; beyond 105 percent of the span a value reads `+Over` or `-Over`.

**Setup screen** (the MENU key or the wrench): tabs Channels (a paged list; click one to edit it), Alarms, Groups, Display and
Messages. It sends the ordinary configure packet, which the server validates per setting and accepts only from a player holding the
panel wrench; without one the screen says so. The settings are flattened keys (`Ch03Tag`, `Ch03Al2Type`, `G01C4`) and only
non-default ones are saved, so old saves load unchanged.

Sampling rides the panel sampler (no ticker of its own): a recorder with anything bound is sampled every `sampleTicks`. The
history (360 dots a channel), the alarm states and the log are saved; the server records, clients are sent small patches and never
bucket on their own. Cost: the screen is one texture per recorder, repainted only when a dot closes, once a second for the values and
on a blink or a key, never per frame; see TESTING.md.

## 2d. Mimic tape: flow lines and arrows on the board

Real control boards carry mimic buses, flow paths of coloured tape on the face that run behind and between the switches and
lamps, with arrows for the direction of flow and dots at junctions. The **mimic tape** (`bwr:mimic_tape`, a stack of tape from a
cheap shapeless recipe: paper, blue dye, an iron nugget) draws them. It is separate from the `mimic_line` pieces and the
`mimic_symbol` valves, pumps and tanks, which stay as they are and mix with it freely.

A path is **not a component**. It reserves no cells, so a component can be placed over it (it draws on the face just above the
paint and below every component), and it can run across several panel blocks of the wall. It is **indication only**: it has no
outputs and drives nothing, and a bound path only changes its own colour.

### Drawing

Hold the tape and look at a panel face. The ghost marker shows the grid point the next click takes (points snap to the 16 per block
cell grid); the points and lines drawn so far show live.

* **Right-click** a face to add a point; each new point makes a segment from the previous one. Segments are **orthogonal** (along
  whichever axis the cursor is farther from the last point). **Sneak** for free angles: a segment within 8 degrees of an axis or of a
  45 degree diagonal snaps to it, anything else is kept as clicked.
* **Right-click the air**, a block that is not a panel, or **sneak-click the last point** to finish the path. A path of one point is
  dropped. Using the tape on **another plane** (another facing, another board type or slope, another wall) also finishes it; that click
  starts nothing.
* A path may run across **coplanar** panel blocks: the blocks of the group (same type and facing, neighbours in the wall; a benchboard
  wall continues down the slope). It never crosses to a non-coplanar board and every point, and every segment between points, must lie
  over a loaded block of the group (a hole in the wall stops it). It never loads a chunk. A path that crosses blocks is stored as a copy
  in each block it touches, in that block's own cell space, under one shared path id: erasing, restyling or binding one piece updates
  them all (in the loaded blocks).
* **At most 64 points to a path and 128 paths touching a panel block**; over a limit the server refuses with a message and changes
  nothing.
* **Cost:** one tape per 32 cells of path length (measured on the board, at least one), taken when the path is finished; creative mode
  costs nothing. Short of tape, the path stays in the stack so it can be finished once there is enough. The half drawn path is stored on
  the tape stack itself.

### Modes

**Sneak and scroll** with the tape switches **draw**, **erase** and **edit** (the tooltip and the action bar name the mode; switching
drops a half drawn path). **Erase** removes the path under the cursor (all its pieces); **edit** opens its style screen. The panel
**wrench** opens the same screen on any path that has no component over the cursor, and the panel **linker** opens the binding screen.
In erase and edit the path under the cursor is outlined in red or cyan.

### Style (per path)

| Setting | Values |
| --- | --- |
| Colour | the control room palette (red, green, white, amber, blue and the default blue) or `#RRGGBB` |
| Width | 1, 2 (default), 3 or 4 cells |
| Line | solid or dashed |
| Start and end | plain, **arrow** or **dot**; the default is an arrow at the end |
| Flow arrows | off, or an arrowhead every 8, 16 or 32 cells along the path |
| Moving arrows | for a bound path that is lit: the flow arrows move along it (default on) |
| Lit above | for a bound path: the level of the signal above which it is lit (default 0) |

**Junctions** are automatic: a dot is drawn where a path's point lies on another path, on the inside of one of its segments (a tee).
Two paths that only cross, or meet end to end, get no dot; an end that carries an arrow keeps the arrow.

### Binding a signal (optional, indication only)

Sneak-right-click a plant device with the linker, then right-click a path (with no component over the cursor): the screen lists the
device's signals. Bound, the path is drawn in its colour while the signal is **above its threshold** and dim grey otherwise, as a
bound `mimic_line` does; with flow arrows on, the arrows move along a lit path (a function of game time on each client: nothing is
sent) and sit still and grey on a dim one. **Only plant signals can be bound** (one input per path, the same checks as for a
component: same dimension, within `linkRange`, loaded, permitted). A path has no output, so nothing here commands the plant.

### Saves, sync, rendering

The paths are saved in the panel block entity under `MimicPaths` (with `NextMimicId`), written only when the block has paths, so a
panel without any saves exactly as before and an older save loads unchanged. A damaged entry is dropped. They are sent with the full
panel state when something changes; a bound path's value travels as an ordinary value delta (addressed as `-localId`), batched like
every other, with no packet of its own. Rendering is baked: the lines, ends, junction dots and the arrows of an unbound path are flat
quads (mitred corners, triangular heads) recorded once into the block's GPU buffer with the components' static geometry, clipped to
the block so a path across blocks has no overdraw; only what a signal changes (the lit overlay and the moving arrows) is drawn per
frame. It works on vertical, benchboard and console boards (a line is as thick and an arrow as true on a slope as on a wall) and with
and without shader packs.

## 3. Worked example: a small reactor control room

A vertical board five blocks wide and three high (one group, 80 x 48 cells; see the component sizes above). Link the reactor controller once,
then wire left to right. Signal and command keys are in `SIGNALS.md`. Component appearance and settings
(ranges, bands, formats, alarm setpoints) are each component's own wrench screen; this example is about the
wiring, which is identical for every type.

0. **Valve nameplates.** A nameplate holds up to three lines of text, a small field at each bottom corner (`16`,
   `1101 PSIG`), an optional coloured tag with its own text (a red `PAM`), an optional coloured dot (both at the bottom
   left) and an optional coloured stripe down the left edge (red on the ADS valves), all in the wrench screen: the
   Columbia SRV plates. An old one-line plate loads into line 1.
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
| `setText(label, text)` | boolean (changed) | Text of a nameplate (line 1), a text display, or the caption of a core display or rod select matrix |
| `setTextField(label, field, text)` | boolean (changed) | One named text field: a nameplate's `line1`..`line3`, `cornerLeft`, `cornerRight`, `tag`; a core display's or matrix's `caption`. An unknown field is an error that lists the component's fields |
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
* Mimic paths: at most 64 points per path and 128 paths per panel block (fixed, not configurable); see section 2d.
* `bwr-panels.toml`: `sampleTicks` (2), `linkRange` (64), `maxGroupColumns` (16), `maxGroupRows` (8),
  `maxComponentsPerBlock` (64), `maxLabelLength` (16).
* Network protocol version 20 (the mimic tape's `mimic_use`, `mimic_mode`, `mimic_configure` and `mimic_open_config`; 19 added the recorder's free message packet `panel_recorder_message`). Packets: `panel_linker_open` and `panel_bind` (client to server, validated:
  reach, claim permission, the linker's own device, dimension, range, key exists, momentary value within the
  command's range), `panel_linker_screen`
  (server to client), and `panel_content` (server to client: bounded display content patches, at most one per
  component per tick).
