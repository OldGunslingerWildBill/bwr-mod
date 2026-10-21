# Changelog

## Unreleased: the premade boards are blank shapes named by their form

- The board blueprint now ships **blank shapes** instead of five function boards, on the owner's direction that a board is just a board and is not tied to a reactor function: **Vertical board**, **Back panel**, **Benchboard**, **Console**, **Back panel with benchboard** and **Duplex board**, each four blocks wide (`vertical_board`, `back_panel`, `benchboard`, `console`, `back_panel_with_benchboard`, `duplex_board`), and the two control desks below. A shape is panel blocks only: no components, labels, captions, mimic tape, reserved spaces or notes, and no plant system in any name. The five removed boards (rod control, RPV level / pressure / flow, RPS scram, the ECCS bench, neutron monitoring) were the frames of these shapes with a reactor function laid on them; the reserved spaces they held for the controller station, joystick switch and LED rod display are gone with them (nothing was added for those parts).
- **Two more shapes, the control desks:** **Control desk (1 m)** (`control_desk_1m`, four 1 m desks side by side) and **Control desk (2 m)** (`control_desk_2m`, two 2 m desks side by side), both four blocks wide like the rest, so there are **eight** shapes. The template format gained two frame kinds for them, `desk` (1 m desks, one block each) and `desk_wide` (2 m desks: `columns` counts desks, each two blocks, the right half paying the one item and the left half placed to the operator's left, facing like the board); both are one row, and their surface is the desk top, 16 cells per block by 6 deep. Survival costs the desk items (one per 1 m desk, one per 2 m desk). The five older kinds are unchanged.
- The blueprint item, the ghost, placement, the survival cost, validation and the data pack loader are unchanged (a data pack can still ship a full board). An old blueprint stack saved with a removed board id places the first shape (the vertical board), as for any unknown id. Survival now costs only the panel blocks (for example 8 back panels and 4 benchboards for the back panel with benchboard).
- `tools-generate-boards.py` is now just the list of shapes (it also deletes the retired files). `PremadeBoardTests` asserts the new set (ids, blank, valid panel blocks, no reserved space left, the fallback for a retired id) and places each shape; the board client check photographs the eight shapes (front, three-quarter, bench) and the blueprint ghost.

## Unreleased: GPU mesh cache budget

- The machine mesh cache (`MachineMeshCache`) now holds 256 MB instead of 96 MB. A reactor with the largest (21 x 21) core keeps about 90 MB in the four meshes it draws every frame (vessel, drives, core, jets); together with anything else in view that went over 96 MB, so the cache dropped one of them every frame and uploaded it again, every frame, for as long as the reactor was in view (the RIP model check caught it as "RIP core uploads every frame"). Smaller reactors were never affected. No saved data changes.

## Unreleased: server tick cost and chunk-boundary audit

- **Measured** what our systems cost per server tick in a whole plant (TESTING.md, "Server tick cost and chunk boundaries"): `TickProbe`, a development-only profiler (off unless `-Dbwr.tickProbe=true`), and `TickBudgetTests`, which builds a reactor, recirculation, steam lines, pool and RHR, feed and spray pumps, condenser, cooling, a containment room and two panel boards and asserts the work done over 700 ticks (not the time, so it does not flake).
- **Fixed, with identical physics:** every cell of a pump assembly ran the whole-footprint check on its own scheduled tick (N cells read N blocks each, every two seconds); only the controller cell does now (`TurbineAssemblyBlock` already did), and the check reads the footprint's chunks directly. The 49-block and 25-block search cubes of the pumps, the ADS controller, the suppression pool and the turbine outlet are read by section (`BlockScan`), in the same order and with the same result, instead of block by block (a 2 to 6 ms hitch every 30 seconds per machine, every 2 seconds for one with no reactor). Panel inputs no longer copy the device's whole signal list on every read (`Schema`). `PipeTopology.routes` returns the answer it computed last time while the graph and every valve in it are as they were.
- **Fixed, chunk boundaries:** a turbine outlet whose steam line was cut (a tube broken, a chunk unloaded) kept drawing its commanded flow straight out of the reactor it had learned through the line; it forgets a reactor farther than its own search radius when the line is gone. The outlet read its reactor, on every tick and when it unloaded, without asking whether the reactor's chunk was loaded, which loaded that chunk. A pump, the ADS controller or the suppression pool whose rescan could not see part of its search cube (a chunk not loaded) cleared the binding it had; it now keeps a binding it cannot disprove, instead of losing it for up to thirty seconds. Machines that read another machine many chunks away (reactor to its pumps and nozzles, outlet to its valves, pumps to their reactor, pool and tank, the pipe route walk, panel inputs) used to renew a one-tick chunk ticket with every read and so kept a chunk at the edge of the loaded area from ever unloading; they read the chunk object if it exists and nothing else (`LoadedWorld.blockEntity`).
- New: `SyncLoadAudit` (every chunk our code loads synchronously during a GameTest run is printed), `-PbwrOnlyTick`, `-PbwrTickProbe`, `-PbwrJfr`. Nothing saved changes.

## Unreleased: control desks (1 m and 2 m)

- Added the owner's two small Fusion **control desks** as mountable panel bodies (CONTROL-PANELS.md, "Control desks"): **Control Panel (Desk, 1 m)** (`bwr:control_panel_desk`, one block) and **Control Panel (Desk, 2 m)** (`bwr:control_panel_desk_wide`, two blocks placed by one item, the second to the operator's left). The shape is the owner's (units are centimetres, one block is one metre): a level top 0.375 m deep, a riser and a narrow shelf behind it 0.175 m above the top, and a front that falls away under the top for the knee space; the desk stands against the back of its block. The owner's top is 0.40 m high, which is low next to the operator desks, so on the owner's instruction ("stretch to desk height") the part below the top is stretched vertically to put the top at **0.75 m**, the height of the operator desks; the top, the riser and the shelf keep the owner's sizes (shelf 0.925 m), and the sloped front still runs from the front edge of the top to the floor, a little steeper. The model keeps the owner's outline (the extra collinear points of the Fusion mesh are dropped: 10 faces instead of 420 triangles); `art/models/control_desk/build_desk.py` rebuilds it headless in Blender and checks it against both source files.
- New panel style `PanelType.DESK`: the mounting face is the top, a level plane (normal straight up) 16 columns by 6 rows (`PanelGeometry.DESK_ROWS`; the cells in front of the top take nothing, as on the chamfers), row 0 at the back, rows toward the operator. Desks join sideways into one face of any width (a 1 m, a 2 m and a 1 m desk are one run) and are a single row.
- Both desks take the five paint finishes like the other bodies (grey tinted textures with tint indices; the dark sloped front and the floor keep their colour), drop their item with the finish, and the wrench changes both halves of a 2 m desk together. Breaking either half of a 2 m desk removes it with one drop, as the duplex column does.
- New blocks only: nothing saved changes, old saves are unaffected. The mounting face test of `PanelTrimTests` reads the shipped `desk.obj` back like the other bodies (its grid is 16 x 6 cells, and the riser and shelf rise above the top but only behind it); exhaustive `PanelType` switches in the tests gained the `DESK` arm. The panel client check has desk stages (1 m and 2 m, seafoam and tan, a mixed run in light blue, a side view).

## Unreleased: premade control boards and the board blueprint

- Added the **board blueprint** (`bwr:board_blueprint`, CONTROL-PANELS.md section 2e): one item that places a whole premade control board in one click, with an outline ghost (green fits, red blocked) while it is held. Right-click the air to pick the next board; right-click a block to place it, the clicked spot being the front left corner of the board and the panels facing the player. Creative is free; survival takes the panel blocks, one item per component and the mimic tape from the inventory and lists what is missing; a blocked, out of world, unloaded, protected or over-limit site is refused and nothing changes. Boards are placed **unwired**.
- Boards are **data**: `data/<namespace>/panel_board/<name>.json` (panel frames, components with type, cell, explicit size and any wrench setting, reserved spaces for parts that do not exist yet, mimic tape), loaded with the data packs, synced to clients, validated as a whole (every problem is logged, a bad file is skipped).
- (Superseded by "the premade boards are blank shapes" above.) Five boards shipped, laid out at about twice the exact real size from `CR-PANELS-BWR-RESEARCH.md`: **rod control**, **RPV level, pressure and flow**, **RPS scram and mode switch**, the **ECCS bench (P601)** with the ADS / SRV section, and **neutron monitoring**. Space is reserved (and marked as a TODO in the template) for the manual/auto controller station (feedwater, recirculation), the joystick bypass switch and the LED rod pattern display.
- `PanelOps.placeDirectQuiet` (a component with a given label and no per-component sync) for bulk placement. New data component `bwr:board_template`. Old saves are unaffected.

## Unreleased: control hardware (controller station, synchroscope, two-pen recorder, joystick switch, LED rod display)

- Five new panel components the research reports found missing (CONTROL-PANELS.md, section 6): a **manual/auto controller station** (`bwr:controller_station`: PV and setpoint pointers on one scale, demand bar, AUTO/MAN, RAISE/LOWER; outputs demand, mode, raise, lower, setpoint; no control law, nothing moves by itself), a **synchroscope** (phase and slip inputs, two synchronising lamps), a **two-pen strip recorder** (the strip chart with two coloured pens, each on its own scale), a **joystick bypass switch** (centre, up, down, left, right, optional spring return) and an **LED rod pattern display** (amber and red LED per rod from the full core display snapshot).
- New items start at **twice their real size**; the exact real size stays selectable. Old saves are unaffected: the types are new, `StripChartData` is only no longer final, and `ComponentType.onPlaced` is a no-op for every type but the station.

## Unreleased: gauge polish and twice the real size

- **Real size defaults are twice as big** in each dimension for every type that has real sizes: dial 4x4, edgewise 2x6 / 6x2, bar gauge 2x6, bar graph 2x6, key switch and rotary selector 2x4, lamp, pushbutton, toggle, push-to-turn switch, setpoint knob and annunciator window 2x2, RPS trip 4x2, segment display 8x4 and thumbwheel 6x4 (digits 2 cells wide, 4 tall). The **control switch** is the exception: a new one is 8x12 (the owner found it exactly the right size next to the 12x16). A bare, creative or crafted stack places that; the exact real sizes stay on the lists (cycle or Alt+click) and nothing placed or stored moves (the new sizes are appended).
- The **bar gauge has a real size** (1x3, and 2x6 as the default); its layout tests are relative to the case. Its numbers and units are 12 and 19 % bigger, measured against the owner's reference picture.
- The **classic dial's labels** (minimum, maximum, units) stay inside the face at every size and Text size; the OFF flag moved above the hub.
- **Captions** under a real size part scale with it (`GaugeScale.captionFactor`); at the design size and above they are unchanged.

## Unreleased: paint finishes for panels, trim and furniture

- Every **panel body** (vertical, benchboard, console, back panel, duplex), every **trim block** (end caps, header strip, canopy and vent, plinths, corners) and every piece of **room furniture** (cabinets, desks, mimic bands, wall displays, control stand) has a paint **finish**: **seafoam** (the look they always had, and the default), **cream**, **tan**, **grey** and **light blue** (the owner's simulator). It is a blockstate property `finish`, so a saved block that has none, which is every block placed before, loads as seafoam and looks exactly as it did (CONTROL-PANELS.md, "Finishes").
- **Sneak-right-click with the panel wrench** on a bare spot of a panel (nothing mounted under the crosshair) or on any trim or furniture block steps the finish: seafoam, cream, tan, grey, light blue, seafoam. A multi-block piece (a duplex column, a cabinet, the supervisor desk, a wall display) changes as one. Sneak-click on a mounted component still removes it, and the click on a mimic path, the config screen and the old gestures are unchanged. Neighbouring panels may have different finishes: grouping and mounting never look at the finish, and mounted components are untouched.
- A broken block drops its **item with the finish** (the vanilla `block_state` component; a seafoam drop has none, so it still stacks with a crafted one), the item places the same finish on every block of the piece, pick block gives the finished item, and item icons are tinted.
- The models carry **grey paint textures with a tint index**, coloured per finish by a block and an item colour handler, so one texture serves all five. Only painted surfaces take the finish (faces, bezels, paint, the back, the end cap cheek and the plinth doors, the desk tops); dark trim, kick plinths, hoods, screens, louvres and handles keep their colour. The annunciator strip and the vent grille are pictures (a box of windows, a grille) and keep their seafoam border in every finish. The thin housing of mimic bands and wall displays takes a darker shade of the finish (it is the same dark grey in seafoam).

## Unreleased: components on the angled trim (header strips, canopies, chamfer corners)

- The **annunciator header strip, the canopy and the two 45 degree chamfer corners** (vertical and benchboard) now take components like any panel (CONTROL-PANELS.md, "Mounting on the angled trim"). They are panel blocks with the panel block entity under the same ids, models, recipes, drops and states, so a world saved before loads: a block that had no block entity gets one when its chunk loads (and, failing that, the first time anything asks for it), and breaking one drops what is mounted on it.
- Three new panel styles: `HEADER_TILT` (the face as the model draws it, leaning back 5 degrees; strips and canopies join sideways into one row, never upward), `CHAMFER_VERTICAL` (the diagonal facet, 11 of 16 columns; stacks into rows, never joins sideways) and `CHAMFER_BENCH` (the triangular facet; a group of one). A grid cell is still 1/16 block along the face, so parts keep their real size; the cells that are not on a facet (and a canopy's hood row) take nothing.
- Existing exhaustive `PanelType` switches in the tests gained the new arms; the registration test of `PanelTrimTests` now expects the four pieces to be panel blocks with a panel block entity (the other trim pieces are unchanged and still have none).

## Unreleased: annunciator boxes, the control module, distance fade of text

- Annunciator windows that touch now draw as **one grouped box** (a thin dark gap between them, one shared dark recessed frame with a bezel lip round the group, found on each client from the windows of the panel group; CONTROL-PANELS.md, "Annunciator boxes"). Lit colour and flash are as before; the lettering follows the window's colour scheme. The legend is up to **four lines** (Engraved line 3 and 4, saved only when used, so old saves load unchanged).
- Added the **Annunciator Control Module** (ACK, RESET, SILENCE and TEST pushbuttons in one part, real size 2x1 cells): momentary outputs `ack`, `reset`, `silence` and `test`; ACK and RESET do what a pushbutton with that role does for the module's group, TEST is a lamp test (lights the group's windows while held, changes no alarm state). It acts only when pressed.
- **Distance fade of text**: printed text whose glyphs are only a few screen pixels tall fades out and a soft strip in the text's colour stands in for it, instead of turning to speckle. Computed per component per frame, no per-glyph work; client option `panelTextFade` (default on, `bwr-visuals.toml`) turns it off.

## Unreleased: gauge text and ticks in proportion, Text size and Tick size

- Gauge **text and ticks scale with the gauge** (CONTROL-PANELS.md, "Gauge text and tick size"): the numbers of a small bar gauge are no longer held at a least height of 0.15 cell, the classic dial's least tick and pointer widths and the classic edgewise meter's margins, ticks, pointer and flag shrink with a meter smaller than its art, and a 1-cell bar graph's housing is the big bars' share. The big sizes look as before.
- The dial, edgewise and bar gauges have **Text size** and **Tick size** sliders (50 % to 200 %, default 100 %) in the wrench screen. Saved only when not 100 %, so saves are unchanged; the server clamps them.

## Unreleased: real size control panel components

- The **instruments and controls start at their real size** (CONTROL-PANELS.md, "Real sizes"): dial gauge 2x2 cells (12.5 cm), edgewise meters 1x3 / 3x1, control switch 1x2, key switch 1x2, rotary selector 1x2, setpoint knob 1x1, toggle switch 1x1, thumbwheel 1x2 per digit, lamp 1x1, pushbutton 1x1, RPS trip assembly 2x1, push-to-turn switch 1x1, annunciator window 1x1, segment displays 1x2 per digit and bar graphs 1 cell thick. Before, every one of them was five to ten times its real size, so a 4 m board held a handful of parts.
- The real sizes are **appended** to each footprint list, so stored item sizes and placed components are unchanged; a bare item (no stored size) now places the real size. The big sizes are reached by cycling (right-click air or sneak-click a panel), which now goes from the smallest to the biggest, or with the new **size picker**: Alt+click with a component item in hand opens a scrollable list of every size with its cells and real size in centimetres, an outline and a marker on the current one (new packet `panel_set_size`, validated like the cycling). The network protocol version is now 22.
- Small placeholders scale their elevation, bezels, corner flags and segments with the component, so 1-cell parts are not posts; segment displays and thumbwheels get one digit per cell at the real size and bar graphs two segments per cell.
- A block may own up to 256 components by default (was 64).
- The **bar gauge** has no real size yet: its tests assume a case at least 11 cells tall (see CONTROL-PANELS.md).

## Unreleased: control room trim blocks and remodelled panel bodies

- The three panel bodies (vertical, benchboard, console) are remodelled in Blender with the **mounting face exactly where it was** (a game test reads the shipped OBJ files back and checks the face polygon's four corners against `PanelGeometry`, and that nothing else is in front of that plane). Detail: seafoam face and bezel textures with module seams, service-door backs, bevelled back edges on the vertical board, a dark recessed 2 px toe-kick on the back of the benchboard and console, a front lip on the console. Ids, states and group joining are unchanged, so placed boards just look better.
- Added 15 decorative trim blocks, none with a block entity: **end caps** (flat, benchboard, console, rounded; they take the side of the run they are placed against), **annunciator header strips** (a half-height block tilted forward 12 degrees with a grid of window tiles; a canopy variant with a projecting hood), **plinths** (base cabinet with a toe-kick, foot apron, toe-kick strip) and **corner fillers** (vertical and benchboard; inside, outside and 45 degree).

## Unreleased: Networking Wrench, machine names, gauge Measures and FE power units

- Added the **Networking Wrench**, a second wiring tool beside the unchanged Panel Linker (either stores the device in the same slot, so a bind is the same bind). Right-click selects a device, sneak-right-click opens its **Networking screen** (name, caption template, every signal with its live value, unit, quantity and readers, every command, the linked components with Unlink, and *Name linked components*). While it is held, a device under the crosshair is outlined white, the selected one yellow, and hovering a bound component or a device draws see-through lines (read cyan, command orange, at most 64). Nearby players see the same lines, fainter and tinted with your name colour, within `networkLineShareDistance` (server, default 32) and `networkLineViewDistance` (client, default 32).
- Added **machine names** (equipment tags, up to 24 characters; saved only when set), the `name` TEXT signal on every device, component **captions** (printed under a component), and the two buttons that fill captions from a name: *Name linked components* and *Use machine name*. They touch line links only (never CC port bindings), respect the panel edit permission and never fire a sink.
- Added gauge **Measures** (default ANY): a gauge set to LEVEL lists and accepts only level signals; `bind` refuses a mismatch with the reason. Old mismatched bindings keep working and show a warning. Each signal now has a quantity worked out from its unit (SIGNALS.md). `generated_fe_tick`, `delivered_fe_tick` and `buffer_delta_fe_tick` now read `FE/t` (they said `FE`).
- Added gauge **Power units** (Raw, W, kW, MW; kJ and MJ for stored energy) through the existing binding scale, `feToJoules` (default 2.5), and *Fit range*.
- Added **CC port bindings** (CC:Tweaked only): a machine gets a port 0000 to 9999 set by hand (`setPort(n)` or its Networking screen), and a panel input can read a signal of the machine on a port of its CC wired network. Two machines on one port read no signal. Every machine peripheral also has `getName`, `setName`, `getPort` and `setPort`.
- The network protocol version is now 21.
- The **bar gauge** is curved like the owner's CAD part: a real case 5 by 20 cm (kept to those proportions and centred in its size) whose white face bulges 3 cm along an arc between raised side rails, grey top and bottom caps (a dark dot on the bottom one) and the tag plate above. The ticks, colour bands, numbers and units are printed on the curved face and lean with it; the pointer rides the arc in its black channel, still reading exactly against the ticks. Band colours offer **Nominal** (white), **Caution** (yellow) and **Danger** (red) beside the colour code. Saves are unchanged.
- Added **control-room furniture** (ROOM-FURNITURE.md), eleven decorative blocks with no block entities and nothing mounted on them: the **Relay Cabinet** and **Relay Cabinet (Plain Back)** (two blocks tall, placed and broken like a door; double doors, seam, handles, louvres, an indicator window, a service door on the back of the standard one, a flat back on the other so rows stand back to back), the **Operator Desk**, **Round End** and **L Corner** desks (0.75 m with a free knee and kick space; a bare hand cycles an optional flat monitor or CRT on top, a sneaking bare hand mirrors the round end), the **Supervisor Desk** (two blocks wide), the **Mimic Band** in cream and black (a flat 3/16 deep board with a flow diagram), the **Wall Display** (2 x 2) and **Wall Display (Wide)** (3 x 2) with a dark screen and a plant overview picture, and a small **Control Stand** (a pedestal with a tilted lamp and button top). Multi-block pieces go down whole or not at all, come up whole and drop one item. The mimic tape does not draw on the bands. Models are Blender OBJ in the seafoam panel palette with dark grey trim; saves are unchanged.

## Unreleased: machine screens redesigned

- All fourteen **machine screens** (reactor panel, refuelling floor, pump panel, Terry turbine, legacy recirculation pump, condenser, condensate tank, cooling, power module, valves, fuel fabricator, RHR heat exchanger, suppression tank, service panels) are rebuilt on one design system (`gui/client/style`, audit in GUI-AUDIT.md). Every function, button, packet, validation and inventory slot position is as before; the displayed numbers are the same numbers.
- **One look**: a dark control-room palette with every text and background pair at 4.5:1 or better, a 4 px spacing scale, one type hierarchy, label-value rows with units in their own column, cards, bar meters, status icons, tabs (in the title bar), primary / secondary / danger buttons with hover, pressed, focus and disabled states, and one text field and slider style. The panels are drawn, not blitted, so the five GUI background textures are gone.
- **Colour is never alone**: a caution, alarm, info, normal or off value carries its own shape (triangle, diamond, square, circle, ring) and a word in the tooltip, and the one colour that used to mean "water" no longer means anything but status. The burnup map runs light teal to dark red-orange instead of green to red, and the maps have a colour scale.
- **Fits every window**: panels are at most 352 x 224 and never wider than the window; every screen fits 320 x 240, the smallest GUI size the game gives a screen (before, six screens did not, and the Terry turbine and suppression tank ran off a 1080p window at GUI scale 4). A body that still does not fit scrolls. Text is cut with an ellipsis and a tooltip with the whole text, never left to run over.
- **Keyboard and screen reader**: Tab and Shift-Tab visit every control in layout order, Enter and Space activate, Escape closes (also from a text field, where letters no longer reach the inventory key), Left and Right switch tabs, the sliders take the arrow keys (1 %, Shift 10 %), the core map takes the arrow keys, Enter or Space, plus, minus and Home. Every control has a tooltip, shown on keyboard focus too, and says why it is disabled (LOAD, UNLOAD, MARK, SWAP, every control a computer holds). A screen reader hears the readouts when a screen opens.
- Fixes found by the audit: the refuelling statistics and the fuel fabricator rows no longer overprint, the SCRAM indicator is no longer drawn over the FLUX tab, the legacy recirculation pump's bar no longer runs through its text, the fabricator's title is visible again, and the active tab no longer looks disabled. The reactor panel's hover-only fuel and damage details are rows on the INFO tab.

## 0.1.0-alpha.32 (unreleased) — Full core display, rod select matrix and SRV switches

- Added reactor rod selection: signal `selected_rod`, commands `select_rod`, `selected_rod_insert` / `_withdraw` (one notch) and `selected_rod_insert_continuous` / `_withdraw_continuous` (held), and CC `selectRod`, `getSelectedRod`, `stepSelectedRodIn` / `Out`, `setSelectedRodInsertHeld` / `WithdrawHeld`. They move only the selected rod through the existing drive network, honouring CRD power and water. The selection is saved; old saves load with none.
- Added per-rod indications `rod_<n>_drift`, `_scram`, `_full_in`, `_full_out`, `_accum_trouble`, `_lattice_x`, `_lattice_y`, `_label` (GE `XX-YY` coordinates) and per LPRM string and level `lprm_<s>_<a-d>_percent`, `_bypassed`, `_failed`, `_downscale`, `_upscale`. The core has no rod drift or low-charge notion, so those two are documented defaults; see SIGNALS.md.
- Added the **full core display** (`columbia` lamp matrix and `browns_ferry` digital style) and the **rod select matrix** panel components. One binding to the reactor controller, no port per rod; one compact patch only when something changed. They span up to 8 x 8 blocks; a drawing that does not fit is refused with the size needed.
- The panel GPU cache holds static legends in text vertex buffers; a 185-rod display plus a matrix costs about 0.05 to 0.09 ms a frame (was 1.4 to 1.5).
- Style table: oiltight 30.5 mm selector (black knob, chrome wing lever), smooth and ribbed dome lights and the guarded yellow pushbutton, when their models ship.
- No rod blocks, sequence control or worth minimiser: protection logic stays the player's.
- Added the owner's CAD hardware as panel items. **Bar gauge** (`bar_gauge`): a vertical edgewise indicator laid out like the owner's simulator (numbers on the left, ticks and up to four colour bands against a narrow black channel, a grey pointer in the channel pointing at the scale, the units down the face right of it one letter per line, the label on a tag plate above); a Preset field fills in the narrow range (0 to 60 in) or wide range (-150 to +60 in) RPV level scale. **RPS trip assembly** (`rps_trip`): two guarded red buttons, outputs `a` and `b`, momentary; the click position picks the button. **Push-to-turn switch** (`push_turn_switch`): hold right-click to push the knob in, scroll while it is in to turn it between AUTO and ARM; outputs `position` and `pushed`, optional spring return.
- The baked GE edgewise and dial meters now draw their colour bands. Gauge units may be 12 characters.
- ADS: new bindable inputs `ads_allow`, `ads_activate_a` and `ads_activate_b` on the ADS controller (both activate inputs held together open the bank once; `ads_allow` cleared keeps it shut) and a per-valve `ads_allow` on relief valves (CLOSE on a valve switch: the ADS may not hold it open). Defaults change nothing; old saves load unchanged. See SIGNALS.md.
- The imported CAD models had their faces turned inside out (lit from behind); fixed and re-exported, including the Modern button, SRV switches and WL-2.
- The SRV switches and the RPS trip assembly are rebuilt from the owner's CAD dimensions: they had come out upside down and the decimated cylinders had collapsed. The SRV switch now has its legend tab above the knob (Close | Open, Close | Auto | Open) and a straight bar handle on a white disc; the RPS assembly has its tall cup guards back, rippled buttons, and the legends on the raised label pads.

## 0.1.0-alpha.31 — 2026-10-11 — Buildable containment

- Added surveyed containment rooms built from full solid blocks, with volume, leak position, layer-based weakest-spot pressure rating and reactor/pool linking.
- Added steam, water, power, data and general penetrations. Powered isolation valves close on lost actuator power; connected open process lines can bypass the containment atmosphere.
- Added 3×3 and 4×4 Blender-modeled vault doors and a two-leaf personnel airlock. Redstone pulses cycle the airlock interlock; only CC override forces both leaves open, creating a medium leak even after releasing override.
- Added a powered nitrogen supply with consumable canisters and a powered wetwell vent valve for concrete basins and enclosed suppression tanks. Vent discharge needs exterior air or a straight stack.
- Replaced the containment controller's status-only CC face with room, opening, isolation, bypass and atmosphere methods. The in-world status readout shows openings, weakest spot, hydrogen, oxygen and pressure.
- Fixed persistent-world GameTest fixture readiness for tank casing drops; added airlock reload, redstone and equipment regressions. The controller GUI is deferred to a later visual overhaul.
- See [CONTAINMENT-UPDATE.md](CONTAINMENT-UPDATE.md) for a worked build and limitations. The corium and severe-accident model remains lumped, not a fluid-dynamics simulation.

## 0.1.0-alpha.30 — 2026-10-09 — Natural circulation, core melt and containment hydrogen

- Added natural circulation: with pumps tripped or absent, core flow follows the cube root of thermal power and needs water above the shroud head. A two-pump trip from rated, with pressure and level held, settles near 23% flow and 26% power.
- Added cladding failure: rods burst between 750 and 1,200 °C, release the gap inventory and leave the coolant radioactive. This damage is permanent.
- Added core melt: above 2,200 °C excess heat melts fuel as latent heat, and molten fuel relocates to the lower plenum with its decay heat.
- Added lower plenum debris behavior: water quenches debris and saves the head, while dry debris creeps the lower head to failure (about 35 min at pressure and 80 min depressurised for 30% of the core).
- Lower head failure drains the vessel from the bottom and moves the debris onto the drywell floor.
- Added core-concrete interaction: corium ablates concrete unless water reaching the floor removes its heat. It produces gas and hydrogen, and penetrating the 3 m basemat fails containment.
- Added a containment atmosphere: hydrogen burns at 8% with oxygen present and detonates when oxygen reaches an already-rich mixture. Burns consume oxygen that a sealed containment does not regain.
- Added containment controls: nitrogen inerting and de-inerting purges, a pool-scrubbed wetwell vent, overpressure failure, and air ingress through a failed containment.
- Added fission product releases to containment and the environment. Contamination decays with a one-day half-life, and nearby players receive escalating status effects.
- Hydrogen burns cause explosions that damage entities but not blocks; other milestones produce chat notices and sounds. Events never replay after a reload.
- Added the CC:Tweaked methods getNaturalCirculationFlow, getCoreDamageStage, getCoreDamage, getContainment, setContainmentInerting and setContainmentVent, plus new getStatus fields, and expanded the controller INFO readout.
- Damage and containment state persist under a new SevereAccident save key. Old saves load undamaged with an air-filled containment. Menu network protocol stays at 12.
- Added 30 physics acceptance tests covering the new models. A recirculation-line break or failed lower head still removes all core flow, because natural circulation cannot run through a short-circuited loop.

## 0.1.0-alpha.29 — 2026-09-27 — Refuelling map controls

- Added cursor-centered scroll-wheel zoom, right-drag panning, and a FIT button to the refuelling map.
- Clipped zoomed cells to the map panel and kept hit testing aligned while zooming and panning.
- Added click-to-toggle multiple selection, visible selected outlines/count, and CLEAR.
- LOAD and UNLOAD now process selected positions through one bounded server request and one map refresh.
- Preserved held-fuel priority, click order, fuel exposure, specialty inserts, inventory exhaustion, and overflow item drops.
- Fixed Creative-mode unloading silently discarding rods when the player's inventory was full; overflow now drops beside the player.
- Kept MARK/SWAP as an explicit single-pair operation and retained the head-off refuelling requirement.
- Added real client input/network and inventory-conservation checks. Menu network protocol is 12; update client and server together.

## 0.1.0-alpha.28 — 2026-09-27 — Circular reactor internals

- Capped modeled active fuel at nine blocks, with smaller footprints approaching 3.5 blocks; vessel height no longer stretches the fuel channels.
- Accepted complete jet-pump assemblies at all fully enclosed mounting heights in the inner perimeter band, including raised construction locations.
- Placed formed jet pumps between the core shroud and vessel wall using the existing uniformly scaled Blender fittings.
- Rendered bound hydraulic control drives beneath their circular core/blade positions and concealed the rectangular construction grid, including spare drives.
- Preserved saved CRD blocks, fuel, control mapping and bottom-face water/power connections. Holding a CRD item exposes original construction selection shapes for maintenance.
- Kept water, Cherenkov visuals and spray headers aligned with the shortened core; accepted existing spray rings at their old build heights.
- Invalidated the jet survey on construction changes instead of expiring it every second; retained cached GPU meshes.
- Added a single-size 17×17×28 integrated client/server regression fixture and a Blender assembly driven by its exported runtime coordinates.

## 0.1.0-alpha.27 — 2026-09-27 — Spray-ring placement and rotation

- Corrected the construction model's quarter-turn offset so new spray branches face the player.
- Stopped straight neighboring segments from overwriting the chosen nozzle direction; automatic 90-degree corners remain available.
- Added Mekanism Configurator Rotate-mode support: right-click for a clockwise quarter turn of straight or corner segments.
- Saved manual orientation so neighbor updates and world reloads do not undo tool rotations.
- Fixed held-item interaction so extending a ring does not accidentally toggle LPCS/HPCS; empty-hand right-click still switches loops.
- Added in-game placement/control tooltips and integration checks using the actual Mekanism Configurator.

## 0.1.0-alpha.26 — 2026-09-27 — Port-height LOCA and white steam

- Changed reactor accident plumes from red to white, retaining the existing particle budget.
- Removing a previously connected vessel pipe now creates a persistent leak; never-connected construction ports remain buildable.
- Added pressure- and elevation-dependent water discharge, with larger lower recirculation inlet breaks and smaller upper outlet breaks.
- Direct liquid drainage stops at each broken nozzle's height; exposed nozzles vent steam and boiling/flashing can continue lowering inventory.
- Added a fixed-volume, two-phase rupture solve coupling mass, outlet enthalpy, expanding steam space and depressurization.
- Feedwater and steam-line breaks vent exposed vessel nozzles; multiple openings and their locations survive reload.
- Pipe failures become possible above 1,250 psig after accumulated wear; vessel-head failures retain the separate 1,375-psig gate.
- Added physics and Minecraft regression coverage for port elevation, flow scaling, conservation, pipe removal and save compatibility.


## 0.1.0-alpha.25 — 2026-09-27 — Jet-pump proportions

- Rebuilt the installed jet-pump appearance in Blender as separate diffuser, straight-pipe, inlet and support parts.
- Preserved round pipe sections and uniformly scaled bends, suction bells, diffusers, braces and fasteners.
- Limited straight-section length adjustment and total pump aspect ratio, preventing tall vessels from stretching the pumps into thin columns.
- Improved fitting to the curved shroud/vessel gap while retaining spacing between adjacent assemblies.
- Preserved existing construction placement, saved pump blocks, pairing and hydraulic capacity; retained cached GPU geometry.
- Expanded the client checks to validate actual part bounds, materials, aspect ratios and a close view inside the downcomer.

## 0.1.0-alpha.24 — 2026-09-27 — Installed jet pumps

- Formed reactors now display their installed jet pumps down alongside the outside of the core shroud, inside the vessel wall.
- Retained the tall construction models and existing lower mounting rows for building; unforming restores those models and their selection/collision shapes.
- Removed invisible construction-column obstacles while formed without moving or deleting saved pump blocks.
- Fit pump models to vessel size and installed count, including smaller and rectangular vessels; kept paired-flow rules and the ten-jet-per-recirculation-pump rating unchanged.
- Reused the existing Blender-authored pump mesh, checked the shroud fit in Blender, and cached installed geometry on the GPU.
- Added formation, disassembly, reload, legacy footprint, missing-pump, flow and client geometry checks.

## 0.1.0-alpha.23 — 2026-09-27 — Open reactor access

- Removed physical collision and crosshair obstruction from the raised/failed vessel head; closing the head restores collision. Preserved structure blocks and saved state.
- Added water- and power-dependent blue Cherenkov-style light around submerged fuel, including decay-power glow after shutdown.
- Added original Blender 90-degree sparger elbows that automatically turn at neighboring corners. New segments inherit the adjacent LPCS/HPCS setting.
- Kept vessel/core meshes cached; no new machine ticker, particle cloud, or world-light updates.
- Added open/closed/reloaded head collision, real entity passage, corner orientation and glow-condition checks.

## 0.1.0-alpha.22 — 2026-09-27 — Steam paths and accident visuals

- Fixed suppression wall-inlet routing after pipe changes. A separate quencher and submerged inlet block are no longer required. Retired quencher crafting/creative entry while keeping old-save compatibility.
- Added a water drain outlet with manual, redstone and CC control. It drains outside or into a connected water line, stops against obstructions, and can empty below the separate RHR suction floor.
- Made RPV steam nozzles permanently open; downstream MSIVs retain powered redstone/computer isolation. Old closed nozzle saves migrate automatically.
- Waterlogged high-pressure water and steam pipes now survive underwater placement.
- Shared pump suction across multiple water sources and added the connected-source count to the GUI; delivery remains limited by pump rating.
- Connected existing pressure damage above 1,375 psig to actual service-pipe breaks and persistent failure effects. Vessel-side feedwater rupture now loses coolant.
- Added an original Blender damaged-head model, bounded red-tinted accident steam and a loud one-shot vanilla explosion sound pending custom audio.
- Preserved the existing temperature/steam-dependent oxidation and hydrogen model and documented its connection to loss of cooling.
- Added drainage, RHR, intake, underwater pipe and failure/reload regressions; expanded the client visual check. GUI protocol is 11: update client and server together.

## 0.1.0-alpha.21 — 2026-09-27 — Reactor Visual Update

- Replaced formed-vessel controller cubes with a shallow Blender instrument panel.
- Integrated round Blender steam, feedwater and recirculation nozzles while keeping existing pipe and computer connections.
- Added translucent water when the head is off, following physical water level and smoothing between server updates.
- Kept static vessel/core meshes cached as water moves. Unformed vessels restore their construction blocks.
- Named the 2.70% intermediate and 3.50% high BWR enrichment grades; retired 1.20%/1.40% from new selections while preserving saved fuel histories.

## 0.1.0-alpha.20 — 2026-09-27

- Filled usable gaps around the RIP core perimeter with actual loadable fuel slots.
- 17 × 17 RIP cores increase from 444 to **476 assemblies**, with **109 control
  blades** instead of 101. 23 × 23 cores increase to **1,084 / 253**.
- Added controls to partial peripheral fuel groups where the blade and guide fit.
  Complete four-assembly groups retain a valid physical drive.
- Shared the packing dimensions between simulation, Minecraft and Blender; kept
  the ABWR shroud size and pump mounts in place. Preserved every old fuel slot.
- Existing 17 × 17 RIP plants need eight additional CRDs; 23 × 23 plants need
  twelve. Surviving rod state is remapped by position, so refresh cached CC rod IDs.
- Added packing checks across all 289 footprints and an alpha.19 save-upgrade
  regression covering fuel, controls, missing new drives and save/reload.

## 0.1.0-alpha.19 — 2026-09-26

- Corrected the visible RIP annulus using GE ABWR DCD Table 5.3-2: 7,112 mm
  vessel ID, 5,600.7 mm shroud OD and 57.2 mm shroud wall.
- The 17 × 17 shroud grows from 9.68 to 12.00 blocks across; the radial gap
  decreases from 2.78 to 1.62 blocks. The same proportions scale to every size.
- Rebuilt the RIP shroud and rim in Blender and expanded the displayed fuel,
  supports, guides and control blades together. The core remains continuous.
- Separated visual dimensions from Minecraft construction-clearance masks.
  Saved fuel capacities, drive identities, pump mounts and circulation are unchanged.
- Added a top-down Minecraft review and checks of actual baked shroud dimensions,
  fuel fit and reuse of uploaded GPU geometry.

## 0.1.0-alpha.18 — 2026-09-26

- Enlarged the continuous RIP core and shroud by moving the tightest pump sites
  slightly outward while maintaining the existing clearances.
- A 17 × 17 RIP reactor now holds 444 assemblies and 101 control drives, up from
  408 and 97. Its twelve pump sites remain; only four diagonal sites move.
- A 23 × 23 RIP core now holds 1,036 assemblies, up from 964. Ordinary core
  capacities, pump counts, equipment dimensions and flow ratings are unchanged.
- Updated Blender layout reviews, mounting coordinates and upgrade guidance.

## 0.1.0-alpha.17 — 2026-09-26

- Replaced the holes above internal pumps with one continuous, smaller core.
- Reduced the core shroud and fuel/drive layout together, reserving a peripheral
  pump ring with clearance from the shroud and vessel wall.
- Additional RIPs now add flow without cutting more holes or reducing core size
  again. Ordinary reactors retain their existing capacities.
- Added diameter/area ratios to the CC mounting query and updated placement
  guides with the new ring coordinates and upgrade instructions.
- Reviewed matching Blender scenes and Minecraft cores at minimum, reference,
  maximum and rectangular sizes. Preserved fuel-conversion checks, surviving
  control-drive state and reusable GPU geometry.

## 0.1.0-alpha.16 — 2026-09-26

- Made reactor internal pumps form through a scalable bottom-head mounting grid,
  with 4, 12 and 16 sites at 7-, 17- and 23-block-wide vessels.
- Added held-item mount outlines and the CC:Tweaked `getInternalPumpMounts()` query.
- Installed RIPs reserve fuel/drive positions, require FE and contribute real
  core flow. Each powered full-speed RIP supplies three jet-equivalent units.
- Protected loaded fuel during conversion; preserved surviving rod positions,
  accumulator charge and pending commands through mounting changes and reloads.
- Added a Blender hydraulic control rod drive model referenced to Columbia FSAR
  Figures 4.6-2/4.6-3, preserving shared water/FE and computer connections.
- Updated pump status, reactor INFO, placement guides and model references.

## 0.1.0-alpha.15 — 2026-09-26

- Added Blender-authored scalable core internals based on Columbia FSAR diagrams:
  fuel channels, lifting handles, support cells, top guides, shroud and cruciform blades.
- Visible fuel and specialty inserts follow the actual saved loading; empty slots
  remain empty. Compact and legacy capacities/control IDs are preserved.
- Added continuous inner/outer vessel-head surfaces, sealed poles and overlapping
  shell joints to eliminate open surfaces and seam cracks.
- Core meshes reuse GPU buffers; closed vessels skip the interior from outside.
  Moving blades reuse one mesh. Refuelling does not rebuild the vessel shell.
- Added a Columbia FSAR reference index and core characteristics notes.

## 0.1.0-alpha.14 — 2026-09-26

- Renamed ADS Relief Valve to **Pressure Relief Valve** and ADS Division Controller
  to **Division**. Existing item IDs, recipes, placed machines and CC peripheral
  names remain compatible.
- New division hardware defaults to 1. Local selectors cycle through **1–4**;
  saved division 0 and existing computer commands remain supported.
- Replaced core-sparger cubes with Blender-authored header/nozzle segments.
  Formed reactors display circular or elliptical rings scaled to the vessel,
  with fixed-diameter pipes, circuit collars, nozzle tips and mounting brackets.
- Missing segments leave visible gaps and preserve proportional spray capacity.
  Rings reuse cached reactor geometry and revert to individual models on unforming.
- Added a Blender-rendered sparger inventory icon to keep creative-menu rendering light.
- Updated hardware and construction guides; GUI protocol remains 10.

## 0.1.0-alpha.13 — 2026-09-25

- Cached stationary reactor, cooling-machine, condenser and condensate-tank
  geometry in reusable GPU buffers. Normal frames update placement and lighting
  instead of rebuilding and uploading every model face on the CPU.
- Kept mechanical-tower fans separate from the body; all fan instances reuse
  one mesh while their rotation follows the existing animation.
- Cached suppression-pool component meshes while preserving moving water levels.
- Used full-machine bounds for large-object visibility and distance checks,
  including views where the controller's chunk section is outside the camera.
  NeoForge still rejects machines whose complete bounds are off-screen.
- Added cache cleanup on resource reload, world unload and shutdown, plus bounded
  reuse of assembly variants. Preserved the ordinary vertex path for special
  destruction/outline consumers and retained Blender material colors.
- Added a disposable client benchmark and checks for stable uploads, off-screen
  rejection, tower-top visibility, day/night lighting and resource reloads.
- Client rendering update; existing saves, simulation and GUI protocol 10 remain compatible.

## 0.1.0-alpha.12 — 2026-09-25

- Added smoothly varying random wind and gusts to both cooling towers' vapor.
- Wind changes with height and reaches different sections of the plume at
  different times, producing bends and winding shapes as existing vapor drifts.
- Calm periods favor upright plumes; stronger gusts carry vapor sideways and
  reduce its rise. Nearby towers share coherent wind, with gentle local mixing.
- Retained the continuous white emission, gradual fade, reduced/minimal particle
  settings and shared 1,200-particle cap. Motion is client-only; GUI protocol stays 10.

## 0.1.0-alpha.11 — 2026-09-25

- Rebuilt RCIC and HPCI in Blender as separate Terry-inspired turbine/pump skids,
  with bolted casings, governor linkage, oil systems, gauges, coupling guards
  and visible CC:Tweaked panels. HPCI adds an insulated casing and service platform.
- Added four round, grid-aligned flanges: steam admission, steam exhaust, water
  suction and water discharge. New footprints are 5 × 4 × 5 and 7 × 5 × 5 blocks
  respectively (width × height × depth). Suction accepts ordinary water through
  Mekanism-compatible fluid connections; discharge uses BWR high-pressure water pipes.
- Added dedicated RCIC/HPCI GUIs with manual speed/start/stop, control ownership,
  water-source selection, live steam/water measurements and port coordinates/status.
- Preserved existing block IDs, old installed models/ports, saved controls and
  finite steam/water accounting. Breaking and replacing an old assembly uses the new layout.
- Updated the fast creative-inventory icons using Blender renders. Assembly
  integrity checks now run once per controller instead of once per child cell.
- Extended rotation, obstruction, teardown/drop, old-save, fluid/CC capability,
  control ownership, snapshot, model and complete pumping-loop checks.
- GUI protocol is now 10. Install alpha.11 on both clients and servers.

## 0.1.0-alpha.10 — 2026-09-25

- Reworked both cooling-tower plumes into continuous overlapping vapor layers instead of intermittent puffs.
- Made vapor whiter and denser with larger Blender-rendered sprites, a fuller base and slower dispersal aloft.
- Fixed overlapping vapor layers cutting each other into visible circular slices.
- Retained wind drift, rising motion, eventual fade, particle settings and the shared 1,200-particle limit. GUI protocol remains 9.

## 0.1.0-alpha.9 — 2026-09-25

- Reversed the mechanical cooling-tower fan animation following in-game feedback.
- Increased full-speed fan animation from 30 to 40 RPM (33% faster), retaining powered-speed scaling and smooth interpolation.
- Includes the alpha.8 suppression-tank and tower-vapor changes. GUI protocol remains 9.

## 0.1.0-alpha.8 — 2026-09-25

- Reversed the mechanical cooling-tower fans and made their rotation continuous as speed changes.
- Added tall, wind-drifting vapor plumes to operating natural and mechanical towers. Puffs rise, spread and fade; the effect is client-only with a shared particle cap.
- Added enclosed concrete suppression tanks and a Blender-modeled steam inlet flange. Tanks form automatically, start empty and retain inventory through repair and controller replacement.
- Steam from BWR pipes or optional Mekanism tubes heats the stored water and adds condensate. Shared inlet limits and valve routing prevent duplicate allocation.
- Added steam-flow and inlet-buffer measurements to the tank GUI and CC:Tweaked status. Water suction/return, spray fill and the separate RHR exchanger circuit remain available.
- Fixed relief discharge recognition after pumped tank filling and pipe disconnection. Verified ocean intake → makeup pump → exchanger → discharge cooling with separate water inventories.
- GUI protocol is now 9. Update clients and servers together.

## 0.1.0-alpha.7 — 2026-09-24

- Increased completed tritium target yield to **12,500 buckets (12,500,000 mB)**:
  1,250 samples per rod, processed in the existing 10,000 mB oxidizer batches.
- Retained the seven-day rated-flux irradiation requirement. Existing completed
  but unharvested rods receive the new yield; already harvested samples retain
  their original per-item output.
- Split large harvests into normal inventory stacks and preserved dropped
  overflow when inventory space runs out. Added an overflow conservation check
  and pinned the total yield against real Chemical Oxidizer output.
- Updated tooltips and the fuel guide, including the 64-target / 700-fuel
  example for a 764-position core and a 2.5 buckets/second D-T consumer.
- GUI protocol remains 8. Use matching alpha.7 client/server builds.

## 0.1.0-alpha.6 — 2026-09-24

- Increased rated-local-flux exposure to 48 operating hours for antimony
  activation, 24 hours for silicon, 72 hours for cobalt and 168 hours for
  tritium. These are real running hours at 20 TPS, not Minecraft days.
- Preserved flux-dependent progress, saved exposure seconds, and the absence
  of offline progress. Existing unharvested rods are measured against the new
  thresholds; already harvested products remain usable.
- Completed tritium rods now yield 64 sealed samples and one reusable casing.
- Added an optional Mekanism Chemical Oxidizer recipe: one sample becomes
  10,000 mB tritium, totaling 640,000 mB per rod. Each operation fits the
  machine's output tank and consumes its input normally. Both Mekanism and
  Mekanism Generators are required for this recipe and remain optional for
  BWR itself; ordinary sample crafting is unchanged.
- Added operating-exposure and batch-yield tooltips, updated the fuel guide,
  and extended tests for multi-day progress, migration, one-shot harvesting,
  powered chemical processing and optional-mod recipe gating.
- GUI protocol remains 8. Use matching alpha.6 client/server builds.

## 0.1.0-alpha.5 — 2026-09-24

This entry covers all accumulated alpha.1–alpha.5 work since the previous
GitHub `main` push, commit
[`b126650`](https://github.com/OldGunslingerWildBill/bwr-mod/commit/b1266509bdde477436a47169b5ea255c245cfc35)
on September 23, 2026. It includes code, Blender sources, exported models,
textures, recipes, regression tests and documentation.

### Transport, heat and computer connections

- Exposed machine/controller and modeled assembly faces accept CC:Tweaked
  modems. Passive vessel and concrete walls remain structural blocks.
- Added measurement peripherals for condensers, RHR heat exchangers, fuel
  fabricators, control rod drives, RPV water ports and jet pumps.
- Computer calls resolve the current owner on the server thread. Removed or
  unloaded owners return Lua errors; connections recover after replacement or
  reload instead of retaining stale controllers.
- Water inventories carry temperature and energy through pumps, tanks,
  cooling towers, heat exchangers, condensers and suppression-pool fill/spray.
  Mixing, fractional withdrawals and save/reload preserve mass and heat.
- Condenser cooling-water temperature and exhaust backlog affect vacuum and
  LP turbine backpressure, reducing turbine work when cooling deteriorates.
- Pipe blocks have no block entities or tickers. Cached routing collapses
  ordinary runs into junctions and is invalidated by structural, capability
  and chunk changes, rather than rebuilt every tick. Transfers still check
  current valve openings and available inventory. Searches are bounded and
  never force-load chunks.
- Shared fluid stores are reserved once across multiple port proxies,
  preventing duplicate simulated capacity and water loss.
- MSIV isolation follows the consumer branch; a closed unused branch no
  longer throttles an open steam header. MSIV-only headers are supported.
- Recirculation commands use the inverse of the shared pump/jet delivery
  limits, correcting the mismatch between requested and delivered flow.
- Fuel-grid and panel readouts are batched to reduce GUI rendering cost.

See [plant transport and computers](PLANT-TRANSPORT.md) and the
[re-audit and regression record](PHASE-ONE-REAUDIT.md).

### SLC, ADS and water discharge

- Added Blender-built SLC boron solution tank, powered injection pump,
  borate charges, ADS division cabinet, ADS relief valve and discharge port.
- SLC uses finite water and boron inventories and actual FE-powered delivery.
  Connect its discharge to an RPV water inlet or a valid feedwater cross-tie.
  Computer readouts report actual delivered boron.
- ADS division assignments persist and support local, redstone and computer
  control. Relief valves use real steam connections and a shared nozzle
  allowance, avoiding duplicate steam withdrawal by multiple reporters.
- The passive water discharge/outfall port accepts water at the rear when
  the outlet is clear and loaded. All inputs share its rate limit; readouts
  include flow, cumulative mass, temperature and heat discharge.
- Added matching recipes, loot tables, models, connection data, service GUIs
  and computer methods. Protection automation remains player-programmed.

See [alpha hardware and connections](ALPHA-HARDWARE.md).

### Creative inventory performance

- Replaced expensive machine meshes in inventory slots with 33 lightweight
  Blender-rendered icons while retaining full placed and held models.
- Reproduced and corrected the creative-menu FPS drop. In the local test
  fixture, the measured menu improved from about 19 FPS to about 561 FPS;
  this is a test result, not a guarantee for every machine or modpack.
- Added client checks for the icons and repeatable performance captures.

### Condensate tanks and control rod drives

- Breaking a formed condensate tank restores its construction casings
  instead of deleting the entire structure. Survival/tool/creative drops
  and surplus construction materials are handled without duplicate drops.
- An on-disk restoration ledger handles unloaded portions without loading
  their chunks. Drain stored water before dismantling a tank.
- Face-adjacent control rod drives share finite water and FE supplies.
  A packed 17 × 17 grid can be supplied through separate water and power
  connections on two bottom faces, provided those sources cover total demand.
- Individual drive motion, wear and accumulators remain independent. Supply
  topology is cached and changes only when connections or chunks change.

See [condensate tanks](CONDENSATE-TANK.md) and [compact cores](COMPACT-CORE.md).

### Condenser fit, placement and hotwell makeup

- Clicking an LP turbine's underside with a condenser snaps it beneath that
  turbine. A green/red placement preview checks the complete footprint,
  obstructions, entities and build permissions before consuming the item.
- Reworked the Blender model to fit the LP seat, widen the body and seal its
  transition. The final layout occupies 9 blocks across the turbine shaft,
  7 along it and 6 in height; adjacent LP sections retain clearance.
- Corrected new condenser orientation by 90 degrees relative to the shaft,
  putting the main pipe faces toward the hall sides rather than the next LP.
- Rebuilt the small oval side fittings as circular open flanges and made
  both working hotwell makeup-water inlets. The final layout has eight ports.
- Makeup mixes with finite hotwell inventory and heat independently of the
  circulating cooling-water circuit. The two inlets share one capacity limit.
- Updated hotwell GUI labels and added computer readouts `hotwellKg`,
  `hotwellC` and `makeupSpaceKg`.
- Preserved older v1/v2/v3 condenser dimensions, orientations, port numbering,
  inventories and render assets for existing worlds. Drain and replace an
  old condenser to receive the new geometry and connections.

### Powered MSIVs

- MSIVs start closed and require FE to open and remain open: a 20,000 FE
  buffer, 100 FE/t during opening and 20 FE/t while holding open.
- Loss of power releases a four-second spring closure.
- Non-steam faces accept electric cables; modeled part faces accept CC modems.
  Added power telemetry to `bwr_msiv` and verified Mekanism cable connections
  in every rotation. Legacy MSIV cubes also require FE.

See [condenser and MSIV placement, ports and migration](CONDENSER-AND-MSIV.md).

### Fuel catalogue and specialty rods

- Expanded from five to 19 fuel definitions while preserving the original
  five IDs, numerical tuning and saved exposure.
- Uranium grades now cover natural 0.711%, 1.2%, 1.4%, 2.7%, 3.5%, 4.95%,
  8%, 19.75% and 20%, plus a 3.5% gadolinia variant.
- Added lean/standard/rich MOX, plutonium and thorium/U-233-driver families.
  Unusual grades are labeled experimental. Natural uranium is subcritical
  in this BWR model; MOX values are gameplay-effective fissile fractions,
  not commercial total-plutonium assays.
- Fuel fabrication selects grades from actual input enrichment without
  creating additional fissile inventory.
- Added eight non-fuel rods: boron-carbide and hafnium absorbers; Cf-252,
  Am-Be and Sb-Be neutron sources; cobalt, tritium and silicon targets.
- Absorbers affect local and bulk absorption; sources add startup neutrons.
  Targets accumulate exposure from local fission flux. They do not count as
  fuel, produce fission heat or inflate fuel burnup and power capacity.
- Refuelling, shuffling, tooltips, purple core-map entries, saves and pending
  controller-removal recovery retain specialty rods and their exposure.
- Added a simple craft/load/irradiate/harvest loop. Completed targets yield
  a sample and reusable casing once; full inventories drop the results.
  Samples have Minecraft crafting uses. These are simplified game recipes.
- Added the separate **Realistic BWR: Fuel & Rods** creative tab with 33
  entries, plus 34 Blender-rendered PNGs including the refreshed original
  fuel assembly icon. All icons use lightweight item models.

See [fuel grades, recipes and reference notes](FUELS-AND-RODS.md).

### Compatibility and verification

- Version: **0.1.0-alpha.5**, Minecraft **1.21.1**, NeoForge **21.1.248**,
  Java **21**. GUI protocol is now **8**; update clients and server together.
- Added regression coverage for transport, computer lifecycle, SLC/ADS,
  inventory performance, tank dismantling, shared CRD supplies, condenser
  placement/makeup, powered MSIVs and specialty fuels.
- Latest Minecraft run passed **50/50** required GameTests. Dedicated-server
  checks passed with and without Mekanism/CC:Tweaked, including eight
  assembly-permission scenarios in each configuration.
- The full physics run passed **210/211** checks; its sole failure was an
  outdated five-fuel catalogue assertion. After updating that assertion, the
  entire `FuelTypeSpec` group passed **4/4**. Final fuel calibration passed
  **5/5** focused tests. The long suite was not repeated after the test-only
  catalogue correction; these are combined passing results, not a claim of
  one clean 211-test run.
- Build/design checks passed, along with the 2,392-file JSON audit, packaged
  class/license audit, machine-icon checks and all 19 fuel/8 rod client checks.
- Updated the README, connection/migration guides, verification records and
  Blender exporters. Detailed evidence and JAR checksum: [BUILD-STATUS.md](BUILD-STATUS.md).

Earlier suppression-pool fill/spray/passive cooling, the original detailed
water pumps, compact reactor cores and the initial cooling systems were
already included in the preceding push and are not new additions here.
