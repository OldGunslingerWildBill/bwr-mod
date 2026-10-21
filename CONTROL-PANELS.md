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
| Panel wrench | Right-click a component: label, colours and other settings. Sneak-right-click removes it. Sneak-right-click on a bare spot of a panel (or on trim or furniture) changes its **finish**, see "Finishes" |
| **Panel linker** | Wires components. See below |
| **Networking Wrench** | Selects a device, shows its lines and Networking screen, names machines and wires components. See below |
| **Mimic tape** | Draws flow lines with arrows on the board face: see section 2d |

### Finishes

Panel bodies, trim blocks and the room furniture come in five paint finishes, as seen in real plants:

| Finish | Paint | Bezel | Where it comes from |
| --- | --- | --- | --- |
| seafoam (default) | `A7BCAE` | `7D9284` | the original GE green; every block placed before finishes existed |
| cream | `DDD6BB` | `A8A38E` | US and French rooms, VVER-440 (CR-FURNITURE-PWR-RESEARCH.md) |
| tan | `C2AE8A` | `9A8A6C` | Shearon Harris beige with brown trim |
| grey | `B4B9BC` | `8A9092` | Sizewell B, Qinshan pale grey |
| light blue | `9CC4D6` | `7499AA` | the owner's simulator (its panel is `5F8A9B` in the screenshot, under that game's lighting) |

**Changing it:** hold the panel wrench and **sneak-right-click** a bare spot of a panel (no component under the crosshair),
or any end cap, header, plinth, corner or piece of furniture. Each click steps to the next finish (the chat bar names it).
Clicking a mounted component with the same gesture still removes the component, so the finish gesture only works where
that would do nothing. A multi-block piece (a duplex column, a 2 m control desk, a 2 high cabinet, the supervisor desk, a wall display)
changes as one; a single panel changes alone, so a run can be mixed. Grouping and mounting do not look at the finish:
components span blocks of different finishes, and they are untouched by a repaint.

**Saves and items:** the finish is the blockstate property `finish`; a block with none (an old save) is seafoam. A dropped
block keeps its finish as the item's vanilla `block_state` component (none for seafoam), and placing the item gives every
block of the piece that finish. Pick block gives the finished item.

**How it is drawn:** the painted materials of the OBJ models (`face`, `paint`, `bezel`, `back`, `cheek`, `cabinet`, and the
furniture's `paint`, `bezel`, `top` and the mimic and display `frame`) have a `neoforge_TintIndex`; their textures are grey
(`art/models/control_panel_trim/make_textures.py`, level 230), and `PanelFinishColors` multiplies in the finish colour for
blocks and for item icons. Dark trim, kick plinths, hoods, screens, louvres and handles have no tint and keep their colour.
The annunciator strip, canopy and vent are pictures of a window box and a grille, and keep their seafoam border in every
finish. Colours are in `PanelFinish.java`; a new finish is one more enum value and one more loot table child per block.

### Component sizes

A panel block face is a 16 x 16 grid of cells (one cell is 1/16 block, 6.25 cm). **The instruments and controls
start at twice their real size** (see "Real sizes" below); the older, bigger sizes are one click away. Right-click air
with the item (or sneak-click a panel) to cycle through the sizes **from the smallest to the biggest**, or
**Alt+click** with the item in hand to open the size picker: a list of every size of that type with its size in
cells, its real size in centimetres, an outline of the footprint and a marker on the current one; click a row to
choose it (the list scrolls with the wheel and a scrollbar when it is long). A component may span up to 4 x 4
blocks; the two core displays (below) are the exception and may span up to 8 x 8, because a 185-rod core is
96 cells across.

The table below lists the sizes in the order of the lists in the game files (the real sizes, which were added last,
are at the end of each row and marked **real**); a new item starts at the first real size, an item you already
hold with a size chosen keeps it, and a component already on a panel keeps the size it was placed at.

| Component | Sizes (cells) |
| --- | --- |
| Pushbutton | 6x6, 8x8, **real** 1x1 |
| Toggle switch | 6x8, 8x10, **real** 1x1 |
| Key switch | 6x8, 8x10, **real** 1x2 |
| Rotary selector | 8x10, 12x14 (the extra rows hold the position name), **real** 1x2, 2x3 |
| Setpoint knob | 8x8, 12x12, **real** 1x1, 2x2 (the 2x2 is the default) |
| Control switch | 8x12 (the default), 12x16, **real** 1x2, 2x3 |
| Thumbwheel | 3 cells wide per digit, 6 tall: 9x6 (3 digits), 3x6 to 24x6 (1 to 8 digits); **real** 1 cell wide per digit, 2 tall: 3x2 (3 digits), 1x2 to 8x2; **default** 6x4 (3 digits, 2 wide and 4 tall each) |
| Lamp | 4x4, 6x6, **real** 1x1, **default** 2x2 |
| Annunciator window | 12x8, 16x12, **real** 1x1, 2x2 (the 2x2 is the default) |
| Annunciator control module | 24x6, **real** 2x1, 4x2 (the 4x2 is the default) |
| Bar graph | 3x24, 3x12, 3x16, 3x32 and the same lying down (24x3 ...); one segment per 2 cells; **real** 1x3, 3x1, 1x6, 6x1 (two segments per cell), **default** 2x6 |
| Round dial | 12x12, 16x16, 20x20, 24x24, 32x32, **real** 2x2, 3x3, **default** 4x4 |
| Edgewise meter | vertical 6x16, 6x24, 8x24, 8x32, **real** 1x3, **default** 2x6; horizontal 16x6, 24x6, 24x8, 32x8, **real** 3x1, **default** 6x2 |
| Bar gauge | 10x16 (one block tall), 8x16, 12x24, 16x32, **real** 1x3, **default** 2x6 |
| RPS trip assembly | 16x8, 24x12, 32x16, **real** 2x1, **default** 4x2 |
| Push-to-turn switch | 8x8, 12x12, 16x16, **real** 1x1, **default** 2x2 |
| Controller station (manual/auto) | 8x16, 12x24; the real tail, **twice real** first: 6x12 (new items start here), **real** 3x6, 4x8, **real** 2x4 |
| Synchroscope | 12x16, 15x20, 20x26; **twice real** 6x8 (default), **real** 3x4, 4x6, **real** 2x3 |
| Two-pen strip recorder | 16x16, 24x24, 32x32; **twice real** 6x6 (default), **real** 3x3, 4x4, **real** 2x2 |
| Joystick bypass switch | 8x8, 12x12, 16x16; **twice real** 2x2 (default), **real** 1x1, 4x4 |
| LED rod pattern display | 51x51; **twice real** 34x34 (2 cells a rod, default), **real** 17x17 (1 cell a rod, the 185 rod core), **real** 26x26 (the biggest core) |
| Strip-chart recorder | 16x16, 24x16, 32x24 |
| Paperless recorder | DX1000 16x16 (one block, the default), 12, 20, 24; DX2000 32x32 (2 x 2, twice a DX1000), 40, 48; all square |
| 7- and 16-segment display | 5 cells wide per digit, 8 tall: 20x8 (4 digits), 1 to 12 digits; **real** 1 cell wide per digit, 2 tall: 4x2 (4 digits), 1x2 to 8x2; **default** 8x4 (4 digits, 2 wide and 4 tall each) |
| Character LCD | 3 cells wide per character, 4 tall per row: 48x8 (16 x 2), 1 to 4 rows of 8 to 20 characters |
| Graphic display | 32x32 (2 x 2 blocks), 16x16, 48x48, 64x64 and rectangles; 2 pixels per cell |
| Mimic line | 3 cells thick: 16x3, 8x3, 4x3, 3x3, 32x3, 64x3 and vertical; corners and tees 6x6 to 16x16 |
| Mimic symbol | 6x6, 8x8, 10x10, 12x12 |
| Mimic path (tape, not a component) | none: free points on the cell grid, 2 to 64 points, across blocks |
| Nameplate | 4 cells tall: 16x4, 8x4, 24x4, 32x4, 48x4, 64x4, 24x6, 32x8; valve plates 32x10, 24x8, 32x12 |
| Full core display | 96x96, 128x128, 64x64, 80x80, 112x112, 48x48, 128x96, 128x80, 112x80 (up to **8 x 8 blocks**) |
| Rod select matrix | 64x64, 48x48, 80x80, 96x96, 112x112, 128x128, 32x32 (up to 8 x 8 blocks) |

The GE target pistol and W-2 heavy-duty pistol read best at 12x16 (their style table row says so, and the
showcases use it); a new control-switch item is 8x12, the first size of its list, and 12x16 is one click away. The
control switch is the one type that does not start at twice its real size: the owner placed the 8x12 and the 12x16 side
by side and found the 8x12 exactly the right size (the 12x16 is for other panels). Explicit item
sizes and already placed components keep their dimensions; changing a placed switch's style does not resize its
occupied panel area.

#### Real sizes

A real control board about 4 m wide holds dozens of instruments, but with the sizes above only a few fit: each part
was five to ten times its real size. The instruments and controls below now have a real size, rounded to whole cells
(1 cell = 6.25 cm = 2.46 in, at least 1 cell). A new item places **twice that in each dimension** (the owner's
decision after the first release: at the exact real size the parts were too small to work); the exact real size is
the next pick down. The paperless recorders (their
DX1000 and DX2000 sizes are fixed), the core displays, the rod select matrix, the LCD and graphic displays, the strip
chart, the nameplates and the mimic pieces are unchanged.

| Part | Real size | Source | Exact real cells, then the default |
| --- | --- | --- | --- |
| Round dial | GE AB-40 meter cover 4.33 in (11 cm); 6 in round meter | model README (art/models/panel/meters); 6 in is an estimate | 2x2, 3x3; **4x4** |
| Edgewise meter | GE Type 180, 6.04 x 2.29 in (15.3 x 5.8 cm) | model README (meters) | vertical 1x3, horizontal 3x1; **2x6**, **6x2** |
| Control switch | breaker control escutcheon about 2.5 x 3.5 in (6 x 9 cm); the GE target pistol model itself is 4.4 x 6.6 in (11 x 17 cm) | estimate; model README (ge_sb) | 1x2, 2x3; the default is **8x12** (see below) |
| Key switch | keyed escutcheon 3 x 4 in (7.6 x 10 cm) | model README (ge_sb) | 1x2; **2x4** |
| Rotary selector | GE selector plate 3.5 x 4.4 in (8.9 x 11 cm); oiltight knob plate 2 x 2.5 in | model README (ge_sb, oiltight) | 1x2, 2x3; **2x4** |
| Setpoint knob | 2.25 in knob (5.7 cm); 4 in knob | model README (pushbuttons); 4 in is an estimate | 1x1, **2x2** |
| Toggle switch | heavy duty toggle on its plate, about 6 cm | estimate | 1x1; **2x2** |
| Pushbutton | 30.5 mm oiltight operator, plate 2 in (5.1 cm) | model README (pushbuttons) | 1x1; **2x2** |
| Lamp | 30.5 mm indicating light, 1.1 to 1.7 in (2.8 to 4.3 cm) | model README (pushbuttons, oiltight) | 1x1; **2x2** |
| RPS trip assembly | the owner's CAD, 4.8 x 2.44 in (12.2 x 6.2 cm) | NEW-HARDWARE-SPEC.md | 2x1; **4x2** |
| Push-to-turn switch | the owner's CAD, 2.3 x 2.2 in (5.8 x 5.6 cm) | NEW-HARDWARE-SPEC.md | 1x1; **2x2** |
| Annunciator window | tile 2.8125 x 2.625 in (7.1 x 6.7 cm); a double size window | CR-PANELS-BWR-RESEARCH.md; the double size is not real | 1x1, **2x2** |
| 7- and 16-segment display | a 4 digit panel meter window; each digit is made 1 x 2 cells (6 x 12.5 cm), larger than a real LED digit (about 1 x 1.4 cm) because a cell is the smallest step | estimate | 4x2 (4 digits), 1x2, 2x2, 3x2, 6x2, 8x2; **8x4** (4 digits) |
| Thumbwheel | a real digit is under 1 cm wide; each digit is made 1 x 2 cells | estimate | 3x2 (3 digits), 1x2, 2x2, 4x2, 5x2, 6x2, 8x2; **6x4** (3 digits) |
| Bar graph | an LED bar column or row, one cell thick | estimate | 1x3, 3x1, 1x6, 6x1; **2x6** |
| Bar gauge | the owner's CAD, a case 5 x 20 cm (0.8 x 3.4 cells) | NEW-HARDWARE-SPEC.md | 1x3; **2x6** |

The sizes are appended at the end of each list, so the index an item stack stores (the `component_variant` it was
cycled to) and the size saved with every placed component mean what they always did; a bare stack (no stored size)
is the one thing that changed, and it now places twice the first real size (the doubled sizes are appended after the
exact real ones, on the list like any other: a dial's list is 12x12 ... 32x32, 2x2, 3x3, 4x4). The cycling and the picker list the sizes by
area, smallest first. A component dropped from a panel always keeps its size in the item (also the first entry of a
list, which used to be left unset).

**Bar gauge.** Its real size is 1x3 and a new one places 2x6. Its three size-assuming assertions in `BarGaugeTests`
(the scale at least 1.5 cells clear of the bottom and 0.5 of the top, the channel right of 1.5 cells of scale, and a
channel under a fifth of the width) are now shares of the case, so they test the same layout at every size: 1.5 and
0.5 cells of its 16 tall (0.09375 and 0.03125 of the case's height), the channel at least 0.4 of the case's width
from its case's left edge, and a channel of at most 0.25 of the case's width (the CAD's is 0.216).

Small parts keep their look: the procedural placeholders (annunciator, toggle, bar graph, thumbwheel, segment
display, and every classic look) scale their elevation with a component under 3 cells across, so a 1-cell lamp is not
a post, an annunciator window's bezel is a tenth of a cell instead of three quarters, and the corner flag shrinks with
the window. A bar graph under 3 cells thick has two segments per cell, a segment display or thumbwheel under 8 (6)
cells tall has one digit per cell. Everything 3 cells across or more draws exactly as before. A block can own up to
256 components by default now (`maxComponentsPerBlock`, was 64: a block face of 1x1 windows holds 256); a
`bwr-panels.toml` written by an earlier version keeps its own value, delete the line to take the new default.

**Annunciator boxes.** Annunciator windows that touch along a whole side draw as ONE box, like the reference boards: a
thin dark gap between the windows and one shared dark recessed frame, with a bezel lip, round the group. Nothing is
configured and nothing is sent: each client works the frame out from the windows of the panel group it already has
(`AnnunciatorBoxes`, a pure edge detection in `AnnunciatorBox`), so windows in different blocks of one group join, a
window beside a gap or another part keeps its own frame on that side, and an L of windows gets an inside corner. A window
by itself looks like a bezelled tile as before. Lit colour and flash are unchanged: the tile is full-bright when lit (a
dim version of its colour when off), the lettering is a dark tone of the colour on a white or amber tile and light on a
red one, and dim when off. The legend is up to **four lines** (Engraved line 1 to 4 in the wrench screen; lines 3 and 4
are saved only when used), each centred, one scale for all (`AnnunciatorLayout`). Not done: the row and column labels
of the decorative header strip.

**Annunciator control module** (`annunciator_control`, 2x1 real, 24x6 and 4x2 also): ACK, RESET, SILENCE and TEST
pushbuttons in one part. Each output (`ack`, `reset`, `silence`, `test`) is 1 while held, like a pushbutton, and only
one button is held at a time; the quarter of the face a click lands on picks the button. ACK and RESET do what a
pushbutton with that role does (`AnnunciatorGroups`, once per press, for the module's **Annunciator group**), nothing
more. SILENCE has no horn to silence, so it is an output only. TEST lights every window of the module's group while it
is held (a lamp test the client draws from the synced output); it changes no alarm state and sends nothing to the plant.

**Distance fade of text.** Small printed text (annunciator legends, gauge numbers, captions) turns to speckle from across
a room. The panel renderer works out, once per component per frame, how tall a glyph is on the screen (`TextFade`: the
distance and the projection of the level pass); below 4.5 pixels of cap height the glyphs fade out, and by 2 pixels they
are gone and a soft strip in the text's colour, at most half opaque, stands in for the line (one quad, from the length of
the string; nothing is laid out per glyph). The blend is continuous, so there is no boundary to flicker at. The client
option `panelTextFade` (default on, in `bwr-visuals.toml`) turns it off.

The bar gauge keeps the proportions of the owner's CAD part (a case 5 by 20 cm with its tag plate above), so it is
centred in whichever size it has, with panel showing either side. Its white face is curved along its length, standing
3 cm proud at the middle; the scale, the colour bands, the numbers and the units are printed on the curve, and the
pointer rides it in the black channel. Each of its four bands has a colour code with three named choices beside it:
**Nominal** (white), **Caution** (yellow) and **Danger** (red); any other code still works, and a band saves its
colour as it always did. On the presets a nominal range is simply the white face between the bands.

Engraved text, captions and labels scale with their component. Components placed before this size change keep
the size they were placed at; a display's digit, character and segment counts follow its width, so an old
display shows fewer digits or characters than before.

#### Gauge text and tick size

Every gauge prints its numbers, units, labels and tick marks in proportion to itself (`GaugeScale`): a 1x3 bar
gauge is the 10x16 one scaled down, with numbers the same share of its face (they used to keep a least height of 0.15
cell, twice their share on a 1x3 gauge). A least size that stops something vanishing shrinks with the gauge below the
size its art was drawn for; at that size and above nothing changed. The wrench screen of the dial, the two edgewise
meters and the bar gauge has two sliders, **Text size** and **Tick size**, 50 % to 200 % in steps of 5, default
100 %. They multiply the printed text and the ticks' thickness and length (the baked GE meters' printed graduations
too). Text only grows as far as it stays on the face and clear of its neighbours (a bar gauge's numbers also step
aside for longer ticks), so near 200 % some text stops growing. Each setting is saved with the component only when
it is not 100 %, and the server clamps it to the range. `GaugeScaleGuardTests` draws every gauge-like type at all its
sizes and fails if text or ticks grow relatively bigger on a small one, or text leaves the face or overlaps.

**Round dial labels.** The classic dial prints its units and its minimum and maximum inside the face at every size and
Text size: the units under the hub, the two end labels side by side under them in the gap of the scale (the bottom
90 degrees, the only part of the face the bands and ticks leave free). The text shrinks to the largest size at which
every corner of every piece stays inside the face (the guard test measures the face from the dial's own drawing), so
past about 110 % of the default a dial's text stops growing. The red OFF flag sits above the hub, clear of them.

**Captions.** The caption printed under a component is 0.26 cell per font pixel with a 0.5 cell gap and up to one
block of width at its design size and above, as it always was. A part smaller than the type's smallest ordinary size
(its design size: a dial's 12 cells, a bar gauge's 8) gets the caption scaled by `GaugeScale.captionFactor`, its smaller
side over the design size, size, gap and room together, so a 4x4 dial's caption is a third of a big dial's, not as tall
as the part.

**Bar gauge text.** The bar gauge's numbers and units letters were measured on a screenshot against the owner's reference
(references/gauges/owner-bar-gauge-text-proportions.png): as a share of the case's width, the game's numbers were 11 %
shorter and the units letters 17 % shorter, the font being lower than the picture's. The design caps are raised to 0.58
cm (numbers) and 0.50 cm (units) of the 5 cm case, a guard test holds them there at every size, and the Text size slider
range is unchanged.

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

### The Networking Wrench

A second wiring tool beside the Panel Linker (the linker is unchanged and still works as above). It stores the
selected device in the same slot, so either tool makes exactly the same binding. **Line linking means the
Networking Wrench**: a direct plant binding (a plant signal into an input, a plant command out of an output).

* **Right-click a plant device** to select it: the action bar shows its name (see *Machine names*) and its signal
  count. Right-click it again, or sneak-right-click the air, to deselect. A block that reports no signals is refused.
* **Sneak-right-click a device** to open its **Networking screen**: the machine name and caption template, every
  signal with its live value, unit, quantity and how many components read it, every command and how many send it,
  and the components linked to it, each with an *Unlink* button. *Name linked components* copies the machine name
  to those components. Values refresh once a second while the screen is open; nothing is sent per tick.
* **Right-click a panel component** with a device selected to open the binding screen as the linker does, with the
  best fitting compatible signal already chosen (a lamp prefers an on/off signal, a gauge the first signal its
  Measures setting accepts): one click on Apply confirms. Nothing binds until you press Apply.
* **Lines, while the wrench is held** (drawn through walls, only from data your client already has):
  a signal-providing device under the crosshair gets a **white outline** (linkable) and the selected device
  stays **yellow** until you clear it. **Hovering a bound component** draws a line from its face to each device it reads
  (cyan) or commands (orange), together with the linker's outline of those devices. **Hovering a device** draws a
  line to every component of the loaded panels that is line-linked to it (at most 64 lines). Only line links get
  lines; a CC port binding gets none.
* **Shared lines.** When you hold the wrench and point at a device or component, players within
  `networkLineShareDistance` blocks (server config `bwr-panels.toml`, default 32, 0 shares nothing, never more than
  `linkRange`) see the same lines from their own loaded panels, a little fainter and tinted with your name colour, so one
  player can show the others how a board is wired. Each player can lower or switch off what they see with
  `networkLineViewDistance` (client config `bwr-visuals.toml`, default 32, 0 hides other players' lines); the smaller of
  the two values applies, and your own lines always draw up to `linkRange`. Only the target travels (one small packet
  when it changes, at most four a second); the lines are drawn from the receiver's own panel data.

Nothing here acts by itself: selecting, binding, naming and unlinking are each a click, and the same checks as the
linker apply (same dimension, `linkRange`, loaded, permitted).

### Machine names, captions and the name push

Every plant device can carry a **name**, its equipment tag (RHR-1, RHR-2, ...): up to 24 characters, typed on its
Networking screen or set from Lua with `setName(text)` (read with `getName()`). The name belongs to the machine as a
whole (every cell of a multiblock shares it), is saved with it only when it is not empty, and old saves have none. A
machine with no name is shown as its block name and position. Every device reports it as the `name` TEXT signal.

Components can print a **caption** under themselves (the *Caption* row of the wrench screen; saved only when not empty).
Two buttons fill captions from a machine name, both a player's click and never automatic:

* **Name linked components** on the machine's Networking screen sets the caption of every component, on the loaded
  panels within `linkRange` blocks, whose first **line link** names this machine.
* **Use machine name** in the Networking section of one component's wrench screen does the same for that component.

The caption comes from the machine's **template**, default `{name}`; `{signal}` is the label of the bound signal and
`{unit}` its unit, so `{name} {signal}` gives RHR-1 Valve position. A component bound to several machines takes its
caption from its first line link. Panels you may not edit are left alone and counted; the action bar reports how many
components were named; no sink fires and no signal changes. A machine with no name gives nothing to push.

**CC port bindings are never named.** On a CC wired network one name would reach every machine on that network, so
CC setups are named by hand (or by your own program).

### Gauge compatibility: Measures

Every gauge-like component (dial, edgewise meters, bar graph, segment displays) has a **Measures** setting: ANY (the
default: it takes any signal, exactly as before) or a quantity: LEVEL, PRESSURE, TEMPERATURE, RATE, FLOW, POWER, ENERGY,
POSITION, PERCENT, SPEED, CHEMISTRY or COUNT. Each signal has a quantity worked out from its unit (SIGNALS.md): a signal
in in, m or mB is a LEVEL, psig a PRESSURE, MW, kW, W, FE/t or FE/s a POWER, and so on; a fraction or percent is a LEVEL only
when the signal key says level. A gauge that is not ANY lists only matching signals on the binding screen and the server
**refuses** any other: "This gauge measures LEVEL; mw now is POWER". The same check applies to a line link and to a CC
port. A save made before the setting existed may hold a mismatched binding: it still loads and works, the wrench screen
shows a yellow "Measures LEVEL, but wired to POWER" note, and nothing unbinds on its own.

### Gauge power units

A gauge wired to a POWER signal in W, kW, MW, FE/t or FE/s shows a **Power units** setting (Raw, W, kW, MW), and one wired
to stored energy (FE) offers Raw, kJ, MJ and never W. Picking a unit sets the **scale of the binding** (the same
`raw x scale + offset` as the Scale field, so it costs nothing at run time) and the Units text of the gauge. One FE is
`feToJoules` joules (server config `bwr-panels.toml`, default 2.5): a signal in FE per tick is 20 x 2.5 = 50 W per unit, so
100 FE/t reads 5.0 kW and 0.005 MW; one in FE per second is 2.5 W per unit, so 2000 FE/s reads 5.0 kW. An FE rate whose unit
does not say per tick or per second gets a Per tick / Per second selector. Min and Max are not changed by a unit: the screen
shows the range as it stands and what one unit of the source reads, and **Fit range** sets Min and Max from the signal
range converted the same way when you press Done. A custom Scale you typed is never overridden by a unit picked earlier.

### CC network bindings by port (CC:Tweaked only)

This is **not line linking**: it sets nothing up automatically, names nothing and draws no lines. A machine on a CC wired
network is reached by a **CC port**, a number from **0000 to 9999** that you set by hand on the machine's Networking screen
or with `setPort(n)` (read with `getPort()`; `setPort(10000)` and `setPort(-1)` are refused). The port is saved as one
number only when set; a machine with no port cannot be reached through CC.

On the panel side the binding screen has a **CC network** mode: type a port, press *Look up*, pick a signal of the machine
that answers there and press Apply. The panel joins a CC wired network when a wired modem or network cable touches it; the
machines are the ones on that network (a modem next to them, right-clicked to connect). A port nobody answers on, or that
two machines share, reads **no signal** and says so ("Port 0042 is used by 2 machines"); nothing is guessed. Gauge
compatibility and power units apply in full; `linkRange` and the panel's dimension apply; an unloaded machine reads no
signal and sampling never loads a chunk.

Every machine peripheral also has `getName()`, `setName(text)`, `getPort()` and `setPort(n)`.

### Trim blocks: finishing a control room

Most of these are decoration with a collision shape that follows the model, no block entity and no ticking, so a
whole room of them costs only its model. The exceptions are the annunciator header strip, its canopy and the two
45 degree chamfer corners, which take components (below). Each has a facing like a panel (the operator's side: it
faces you when you place it). Crafted from panels and iron; see the recipes.

| Block | Use |
| --- | --- |
| End cap (flat, benchboard, console, rounded) | A 2 px cheek with the exact cross-section of its panel, closing the open end of a run. It goes in the block beside the last panel and takes its side from the panel it is placed against (sneak flips it) |
| Annunciator header strip, and with a canopy | A full block on top of a vertical board, flush with its face at the foot and leaning back 5 degrees, with a grid of window tiles. **Takes components** on the leaning face. The canopy has a projecting top hood over the top row of cells |
| Panel base cabinet | One block high, under a vertical board (or as a counter), with a dark recessed toe-kick |
| Panel foot apron | A low non-slip plate on the floor in front of benchboards and consoles |
| Panel toe-kick strip | A low dark skirting strip along a wall |
| Corner (vertical or benchboard; inside, outside, 45 degree) | Closes the corners of a U- or horseshoe-shaped room. The corner sits at the far left of its block as you look at it, so the four directions you can look give the four corners. A vertical *inside* corner is an L, the *45 degree* one fills the L with a diagonal, and the *outside* one is the post where two runs turn the outside of a corner. The benchboard corners are the valley, the hip and a diagonal facet. Only the two *45 degree* (chamfer) corners carry a mounting face |

#### Mounting on the angled trim

The header strip, the canopy and the two chamfer corners are panel blocks (the panel block entity, groups, claims, the
ghost, the wrench and the drops are all the panel's own). Their faces are not square to the block, so each is a panel
style of its own, and the 16 x 16 grid is laid on the face the model draws. A cell is 1/16 block wide along the
face, as everywhere, so a part is its real size on a chamfer too.

| Block | Style | Face | Joins |
| --- | --- | --- | --- |
| Header strip, canopy | `HEADER_TILT` | the leaning annunciator panel, 5 degrees back from the board face; a row is 1/cos 5 degrees long | sideways, strip and canopy together, to the operator's right and left. **One row**: the next block up is 0.0875 further back, which is not on the block grid (as the console), so a strip never grows upward. The canopy's hood takes the top row of cells |
| Vertical chamfer | `CHAMFER_VERTICAL` | the diagonal facet filling the inside corner of two vertical boards, 0.71 block wide, normal toward the operator's right-front; **11 of the 16 columns** are on it | **up and down** into a taller facet; never sideways (the facet of the next block along is not on its plane). A part wider than 11 cells does not fit |
| Bench chamfer | `CHAMFER_BENCH` | the triangle (1.41 block along its top, a block tall) that fills the inside corner of two benchboards, normal pointing up and toward the operator's right-front; only the cells wholly on the triangle are usable | nothing: a group of one |

Looking at a diagonal face works as on any other: the cell under the crosshair, the green or red ghost and the wrench
outline all come from the face's own plane. A placed block that had no block entity (a world saved before) gets one
when its chunk loads.

The panel bodies are modelled in Blender (`art/models/control_panel_trim/`): the mounting face is exactly where it
was, and the detail is behind it (module seams on the face, service-door backs, bevelled back edges, a dark
recessed toe-kick at the foot of the benchboard and console backs, a console front lip).

### Control desks (1 m and 2 m)

Two small desks from the owner's Fusion models (`Control Desk 1m.obj`, `Control Desk.obj`, modelled in centimetres: one block is
one metre): a level **operating top**, 0.40 m high, 1 m wide and 0.375 m deep, behind it a riser up to a narrow shelf along the
back (0.575 m high), and a front that falls away under the top to the floor (the operator's knee space). The desk stands against the
back of its block, so the front half of the block is free. The shape is the owner's; it is drawn plain on purpose, so you mount what
you like on it. The mounting face is the top, a level plane with the normal straight up: **16 columns by 6 rows** of the usual 1/16
block cells, row 0 at the back, rows running toward the operator (a part keeps its real size, as everywhere: a dial 4 x 4 fits,
a 12 x 12 gauge does not). The cells that are not on the top (rows 6 to 15, the air in front of the desk) take nothing.

| Block | Item | Size | Style |
| --- | --- | --- | --- |
| Control Panel (Desk, 1 m) | `bwr:control_panel_desk` | 1 x 1 x 1, one block | `DESK` |
| Control Panel (Desk, 2 m) | `bwr:control_panel_desk_wide` | 2 x 1 x 1, two blocks | `DESK`, both blocks |

* **Joining.** Desks of the `DESK` style and one facing join sideways, to the operator's right and left, into one face: a 1 m desk,
  a 2 m desk and another 1 m desk are one run 64 cells wide, and a part can span the seams. A desk is a single row (nothing joins in
  front of or behind it) and joins nothing but desks.
* **The 2 m desk** is placed by one item, from the block you click: that block is the desk's right half (as the operator sees it) and
  the second block goes to the operator's left, like the supervisor desk. Both blocks are panel blocks; if the second is in the way,
  nothing is placed. Breaking either half removes the desk with one drop (the right half's loot), and every mounted part drops as on
  any panel. A desk that loses a half some other way falls apart and gives its item back.
* **Finishes.** The whole desk takes the finish (the top and the riser are the paint, the shelf and the ends the bezel, the back the
  service door texture); only the dark sloped front and the floor keep their colour. The wrench cycles the finish of both halves of a
  2 m desk together.
* **Recipes.** 1 m desk: a console over a vertical board. 2 m desk: two 1 m desks side by side.
* **Saves.** New blocks only: no existing block, state or saved value changed.

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
drops a half drawn path). **Erase** removes the path under the cursor (all its pieces). **Edit** works like editing a draw.io
connector: **click a waypoint** (a clicked point; they show as white dots on the outlined path) to pick it up and **click again** where
it should go (it may move into a neighbouring block of the wall); **sneak-click a waypoint** to remove it, **sneak-click the line** to
add one where the cursor is; a plain click on the line elsewhere opens the style screen. A moved path keeps its style and binding; a
move that would stack two waypoints, or leave the board, is refused and changes nothing. The panel
**wrench** opens the same screen on any path that has no component over the cursor, and the panel **linker** opens the binding screen.
In erase and edit the path under the cursor is outlined in red or cyan.

### Style (per path)

| Setting | Values |
| --- | --- |
| Colour | the control room palette (red, green, white, amber, blue and the default blue) or `#RRGGBB` |
| Width | 1, 2 (default), 3 or 4 cells |
| Line | solid or dashed |
| Route | **straight** between the clicked points, or **elbow**: two right-angle turns between each pair of points (draw.io's orthogonal connector) |
| Corners | **sharp**, **rounded** (each corner a round fillet of the corner radius) or **curved** (one smooth curve through all the points) |
| Corner radius | 1 to 8 cells, for rounded corners (default 3) |
| Start and end | plain, **arrow** or **dot**; the default is an arrow at the end |
| Arrow size | automatic (follows the width) or 1 to 8 cells long |
| Arrow colour | the line's colour (leave blank) or its own `#RRGGBB` |
| Flow arrows | off, or an arrowhead every 8, 16 or 32 cells along the path; they point along the path, round curves too |
| Lit above | for a bound path: the level of the signal above which it is lit (default 0) |

**Junctions** are automatic: a dot is drawn where a path's point lies on another path, on the inside of one of its segments (a tee).
Two paths that only cross, or meet end to end, get no dot; an end that carries an arrow keeps the arrow.

### Binding a signal (optional, indication only)

Sneak-right-click a plant device with the linker, then right-click a path (with no component over the cursor): the screen lists the
device's signals. Bound, the path is drawn in its colour while the signal is **above its threshold** and dim grey otherwise, as a
bound `mimic_line` does. Nothing moves: paths are static lines (the project owner's call). **Only plant signals can be bound** (one input per path, the same checks as for a
component: same dimension, within `linkRange`, loaded, permitted). A path has no output, so nothing here commands the plant.

### Saves, sync, rendering

The paths are saved in the panel block entity under `MimicPaths` (with `NextMimicId`), written only when the block has paths, so a
panel without any saves exactly as before and an older save loads unchanged. A damaged entry is dropped. They are sent with the full
panel state when something changes; a bound path's value travels as an ordinary value delta (addressed as `-localId`), batched like
every other, with no packet of its own. Rendering is baked: the lines, ends, junction dots and the arrows of an unbound path are flat
quads (mitred corners, triangular heads) recorded once into the block's GPU buffer with the components' static geometry, clipped to
the block so a path across blocks has no overdraw; only a bound path's lit overlay is drawn per frame, and it is pre-built.
Rounded and curved shapes are tessellated into short pieces when the mesh is built (at most 1024 points a path). It works on vertical, benchboard and console boards (a line is as thick and an arrow as true on a slope as on a wall) and with
and without shader packs.

## 2e. Premade boards: the board blueprint

A **board blueprint** (`bwr:board_blueprint`) puts a whole realistic BWR control board in the world in one click: the panel
blocks, every instrument, switch, lamp, nameplate and annunciator window on them with its real labels, ranges and bands, and
the mimic tape and demarcation lines. **Nothing is wired**: no component has a source or a sink, and nothing acts on the
plant. You wire it with the Networking Wrench like any board you built by hand.

### Using it

* One item for every board; the board it places is part of the stack. The creative tab holds one stack per board. In your
  hand, **right-click the air** to step to the next board (**sneak**: the one before it); the name and tooltip say which.
  (A selector screen would be one more screen to keep in step with the data packs; stepping is enough for a handful of
  boards. Crafting: paper, blue dye, an iron nugget and a vertical panel, shapeless.)
* While it is held and the crosshair is on a block, an **outline of the whole board** is drawn there, through walls: green
  when the board fits, and when something is in the way the blocked blocks are red and the rest yellow. The action bar says
  which. The outline is the shape of each panel block, so a duplex column shows its bench wedge and tilted header.
* **Right-click the block** to place it. The block in front of the clicked face (or the clicked block if it can be replaced)
  is the board's **front left corner as the operator sees it**; the panels face you, and the board stands to your right and
  away from you.
* It is **refused, and nothing changes**, when a block is in the way (the first is named), part of the board is outside the
  world or in an unloaded chunk, you may not build there (the same rule as placing a component: spawn protection, claims), the
  board would make a panel group wider or taller than `maxGroupColumns` / `maxGroupRows`, or a block would hold more components
  than `maxComponentsPerBlock` allows.
* **Creative: free.** **Survival: the board is paid for from your inventory** when it is placed: the panel blocks (one duplex
  item per column), one item for every component (any size of the item pays for it; a DX2000 recorder needs the DX2000 item) and
  the mimic tape its paths need (one per 32 cells, like drawing it). If anything is missing, nothing is placed and the missing
  items are listed (`Missing for RPS scram and mode switch: 96 x Annunciator, ...`). The blueprint is not used up.

### The five boards

Each carries one **annunciator control module** (ACK, RESET, SILENCE, TEST) for its annunciator windows. All are laid out at about twice the exact real size (a dial 4 x 4 cells, a switch 2 x 4, a lamp 2 x 2: the exact real sizes were
too small to read), from `CR-PANELS-BWR-RESEARCH.md` (the number is the board in that report). Labels follow its conventions:
white laminate with black engraving above controls and below indicators (nameplates above, captions below), black plates for
system titles, valve switches CLOSE left and OPEN right with a green (closed) and a red (open) lamp, annunciator tiles red for
reactor protection information, amber for inoperable, white for off normal. Black demarcation lines round the systems are
mimic tape.

| Board (file) | Frames | Contents |
| --- | --- | --- |
| **Rod control** (`rod_control`, board 1) | back panel wall 10 x 5 and a benchboard 10 x 1 | full core display (Columbia, 64 x 64) and rod select matrix (48 x 48), the four rod display, ACT CTL DISAGREE and ROD DRIFT lamps; RWM / RSCS annunciators, six error and interlock lamps, rod group display, RWM NORM / BYP key; on the bench INSERT, WITHDRAW, CONT INS, CONT W/D, drift TEST and RESET, SEQ A/B, INSERT/WITHDRAW, ALL/FREE and F.I./BYPASS selectors. **Reserved:** the LED rod pattern map (amber/red LED per rod: no such part yet) |
| **RPV level, pressure and flow** (`rpv_level_pressure_flow`, board 2) | duplex, 5 columns | nine level indicators (narrow A-C and wide A-B bar gauges, upset, shutdown, two fuel zone edgewise meters) with the L1/L3/L4/L8 bands, three pressure dials (850-1050, 0-1200, 0-1500 psig) with the 1025/1043/1120 marks, core, steam and feed flow, the seven trace recorder (APRM power, pressure, steam, turbine steam, feed flow, narrow level, core flow), a vessel temperature recorder, alarm windows, the trip level plaque. **Reserved (TODO):** the feedwater master level controller and the recirculation master flow controller / M/A stations |
| **RPS scram and mode switch** (`reactor_scram_rps`, board 3) | duplex, 5 columns | two guarded manual scram assemblies (A1/B1, A2/B2), the four position mode switch (SHUTDOWN, REFUEL, STARTUP, RUN) and its key, scram reset (RESET A / B), two guarded ARI buttons, the SDV bypass key, eight white scram group lamps, the mode and setpoint plaques, 96 scram annunciator windows for trip systems A and B. **Reserved:** the IRM / APRM bypass joysticks |
| **ECCS bench** (`eccs_p601`, boards 4 and 6) | duplex, 13 columns | divisions I and II, each with RHR and core spray: pump pistol-grip switches and valve CLOSE/OPEN selectors with green and red lamps (ten per division), the LPCI (blue) and core spray (green) mimic with pump, valve and tank symbols, pump pressure dials and flow meters, ARM / DEPRESS guarded buttons, 42 alarm windows; the ADS section with 7 ADS and 4 relief valve open/auto/close selectors (red stripe on the ADS valves), AUTO INHIBIT and INITIATE push-turn switches (turn to arm, then push), ADS reset buttons and the backup transfer key |
| **Neutron monitoring** (`neutron_monitoring`, board 10) | duplex, 6 columns | eight IRMs (0-125 divisions, bands at 5, 108 and 120) with range selectors, six APRM bar gauges (0-125 %, red from 118 %), sixteen LPRM dials, four SRM count rates, two two-pen recorders, four bypass switches, alarm windows. The IRM range switch is a 6 position selector (the real one has 10) |

### The template format (`data/<namespace>/panel_board/<name>.json`)

A data pack adds a board by shipping a file; `/reload` loads it and the blueprint finds it. The server sends the boards to the
clients when they join and on `/reload`. A file with any problem is logged (every problem with the entry it belongs to) and
left out; the other boards still load. `tools-generate-boards.py` writes the five shipped files (`python tools-generate-boards.py`;
`--check` fails when the files differ from it) and is a good example of a layout script, but the JSON is the source.

```json
{
  "format": 1,
  "title": "RPS scram and mode switch", "description": "...", "source": "CR-PANELS-BWR-RESEARCH.md board 3", "order": 3,
  "todo": ["comments for whoever extends the board"],
  "frames": [ {"id": "main", "kind": "duplex", "columns": 5, "at": [0, 0, 0]} ],
  "components": [
    {"on": "main.bench", "type": "bwr:lamp", "at": [3, 4], "size": [2, 2], "label": "RHR-A-RUN", "caption": "RUN",
     "config": {"Color": "E02020", "Style": "ge", "Variant": "round"}},
    {"on": "main.bench", "type": "bwr:lamp", "at": [3, 8], "size": [2, 2], "label": "SCR-{name}",
     "repeat": {"count": 4, "step": [6, 0]}, "names": ["A", "B", "C", "D"]}
  ],
  "reserved": [ {"on": "main.board", "at": [0, 0], "size": [24, 12], "for": "bwr:controller_station", "note": "TODO ..."} ],
  "mimic": [ {"on": "main.board", "points": [[2, 2], [30, 2]], "style": {"Color": "2E7DE0", "FlowEvery": 16, "EndEnd": 1}} ]
}
```

* **frames** are runs of panel blocks. `kind` is `duplex` (one item per column: surfaces `board` 2 blocks tall, `header`,
  `bench`), `vertical`, `benchboard`, `console` or `back_panel` (surface `face`). `at` is `[right, up, forward]` in blocks from the
  board's origin (right of the operator, up, toward the operator); for a duplex it is the bench of the left column, for the
  rest the top left block (rows go down, on a benchboard also toward the operator). A surface is named `<frame>.<part>`, for
  example `main.bench`; its cells are 16 per block, x to the operator's right and y down. Frames may not share a block, a group
  may be at most 16 x 8 blocks.
* **components** say which surface, which `type`, where (`at`, cells), how big (`size`, **required**: a board never takes a
  type's default size, so it cannot change when a default does; a size has to be one of the type's sizes, or one of the doubled
  real sizes the parser knows, such as a dial 4 x 4 or a lamp 2 x 2), and then `label` (the component's wiring tag, at most 16
  characters, unique in the board), `caption` (the printed text under it) and `config`: any field of the part's wrench screen
  by its key (`Min`, `Band1Low`, `Text`, `Names`, `Style`, `Variant`, ...), checked against the real field: a number in range,
  a text not too long, a choice from its list, a colour as a number or an `"RRGGBB"` string. `repeat` (`count`, or `cols` and
  `rows`, and a `step` in cells) makes copies; in `label`, `caption` and the text values of `config`, `{n}` is the copy number
  from 1, `{i}` from 0, `{col}` and `{row}` its place in the grid and `{name}` the copy's entry of `names`.
* **reserved** keeps a space empty for a part that does not exist yet (`for` is its future type id, `note` says what goes
  there). It is checked like a component (inside the surface, over nothing) and left empty when the board is placed. This is
  the "commented TODO" of the format, and it is where the **manual/auto controller station** (feedwater, recirculation),
  the **joystick bypass switch** and the **LED rod pattern display** go: replace the entry with a component when the part exists.
* **mimic** are tape paths (2 to 64 points, in cells of the surface; `style` is any field of the mimic path screen).
* Wiring is not part of a template.

The checks (`BoardTemplateParser`): every type and size exists, every component lies inside its surface and spans no more
blocks than its type allows, nothing overlaps (components and reserved spaces together), labels are unique, no panel block holds
more components than `maxComponentsPerBlock` (256 by default), every frame fits the group limits.

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

### Machine peripherals: names and CC ports

Every plant machine peripheral (not the panel) has four more calls, besides its own. They are the program's own
doing: nothing names or numbers machines in bulk.

| Call | Returns | Description |
| --- | --- | --- |
| `getName()` | string | The machine's name (its equipment tag), `""` when it has none |
| `setName(text)` | string | Sets the name (cleaned, at most 24 characters) and returns it; `""` clears it |
| `getPort()` | number or nil | The CC port, 0 to 9999, or nil when none is set |
| `setPort(n)` | number | Sets the CC port; an error unless `n` is a whole number from 0 to 9999 |

A panel input can read a machine by port (the binding screen's *CC network* mode). Two machines on one port on the
same wired network make that port read no signal; the wrench Networking screen shows the conflict.

## 5. Saves, limits, behaviour worth knowing

* Bindings save **absolute positions** (plant devices, component owners) and the CC value; a world copied
  elsewhere keeps pointing at the old positions.
* A binding to an unknown (future or malformed) kind is preserved untouched and reads no signal.
* Redstone input and output share the back face. A panel reading the same conductor block it powers ignores
  its own output, so it cannot latch itself.
* Mimic paths: at most 64 points per path and 128 paths per panel block (fixed, not configurable); see section 2d.
* `bwr-panels.toml`: `sampleTicks` (2), `linkRange` (64), `maxGroupColumns` (16), `maxGroupRows` (8),
  `maxComponentsPerBlock` (256; 64 before real sizes), `maxLabelLength` (16).
* Network protocol version 22 (22 added the size picker's `panel_set_size`, client to server: the hand, and an index into the held component item's sizes, refused for any other item or index; 21 the Networking Wrench; 20 the mimic tape's `mimic_use`, `mimic_mode`, `mimic_configure` and `mimic_open_config`; 19 added the recorder's free message packet `panel_recorder_message`). Packets: `panel_linker_open` and `panel_bind` (client to server, validated:
  reach, claim permission, the linker's own device, dimension, range, key exists, momentary value within the
  command's range), `panel_linker_screen`
  (server to client), and `panel_content` (server to client: bounded display content patches, at most one per
  component per tick).


## 6. Control hardware from the research gaps (CR-PANELS-BWR / PWR research, "Gaps")

Five parts the two research reports found missing. All are drawn in code (no Blender model), start at **twice their real
size** (the exact real size is the next size up in the list: right-click air with the item or Alt+click for the picker) and
**decide nothing**: outputs change only when a player operates a control, and displays only show what they are bound to.

### 6.1 Controller station (`bwr:controller_station`)

A manual/auto station (GE / Bailey / Foxboro hand-auto): one scale with the **process variable pointer** (red, right of the
line) and the **setpoint pointer** (green, left) over the span Min to Max, the **demand bar** beside it (amber in MAN, green in
AUTO), **AUTO** and **MAN** lamp pushbuttons, **RAISE** and **LOWER** pushbuttons (hold to keep stepping, about five steps a
second; the mouse wheel steps too).

| | |
| --- | --- |
| Inputs | `pv` (process variable, in the scale units), `setpoint` (optional: bound = remote setpoint, unbound = the station's own), `auto_demand` (0 to 100 %, what the bar shows in AUTO) |
| Outputs | `demand` (0 to 100), `mode` (0 MAN, 1 AUTO), `raise` and `lower` (1 only while held), `setpoint` (the local setpoint, in the scale units) |
| Config | Min, Max, Units, Step (% of demand per press), Setpoint step (% of span per press) |

* **MAN:** RAISE / LOWER / wheel move `demand` by Step. A program may also SET it.
* **AUTO:** with no bound setpoint they move the local setpoint (Setpoint step); with a bound setpoint they move nothing (the
  `raise` / `lower` pulses still show). The bar shows `auto_demand`; the `demand` output stays where MAN left it. A program may not
  set the demand in AUTO.
* **MAN after AUTO** is bumpless: `demand` takes the value the bar showed (when `auto_demand` has a signal).
* There is **no control law in the station**. Wire `demand` and `mode` where you want them, and bind `auto_demand` to whatever you
  wrote (a CC program through a CC binding, a plant controller signal) that should run it in AUTO.
* Saved: mode (only AUTO), demand (only when not 0), local setpoint (as a fraction of the span, only when not mid-span).
  Premade boards should place it by the id `bwr:controller_station`.

### 6.2 Synchroscope (`bwr:synchroscope`)

A round meter: 12 o'clock is in phase, clockwise is FAST (the generator leads). Inputs `phase` (degrees, -180 to 180: the grid
tie `phase_degrees`) and `slip` (Hz, positive when fast: its `slip_hz`). With only `slip` the pointer turns by itself at the slip
rate (a turn a second per hertz); with `phase` it follows it the short way round; with both it dead reckons between samples. The
two lamps under the dial are the synchronising lamps across the open breaker: the left is the dark lamp (dark when in phase), the
right the bright lamp (full when in phase). No outputs. Config: reverse sense, the two lamp colours.

### 6.3 Two-pen strip recorder (`bwr:two_pen_recorder`)

The strip chart with exactly two pens, red and green, inputs `pen1` and `pen2`, each pen on its own scale (Min / Max and Min2 /
Max2), paper span 10 s to an hour. The chart scrolls **upward** from the pens at the bottom edge; each pen holds its last value
between changes. History is recorded on change (a pen that does not move costs nothing) and saved like the strip chart history.

### 6.4 Joystick bypass switch (`bwr:joystick_switch`)

A Square-D style joystick with five positions. Click the plate on the side you want; click the middle to centre. Outputs `up`,
`down`, `left`, `right` (never two at once) and `position` (0 centre, 1 up, 2 down, 3 left, 4 right). Config: **Spring return**
(the lever returns to the centre when the use key is let go, with the usual fail-safe; the four way spring joystick) and the four
legends. Without the spring it stays where it was put, also across a restart.

### 6.5 LED rod pattern display (`bwr:led_rod_display`)

Two small LEDs, amber over red, for every rod in core layout. Like the full core display it has one input, `core`, bound with the
linker to the reactor controller (`formed` is the natural signal), reads the same snapshot and is dark when nothing formed is
bound. Pattern (config): **position**, amber a rod that is withdrawn but not fully out, red a rod fully out; **fault**, amber a
rod with accumulator trouble, red a rod drifting. The selected rod of the reactor is framed in white. Tile 1 to 3 cells a rod. No
outputs. It keeps the 4 block limit of ordinary components (the 8 blocks of the full core display are its own).
