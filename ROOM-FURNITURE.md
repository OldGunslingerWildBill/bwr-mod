# Control-room furniture

Eleven decorative blocks that furnish a control room. They have no block entities, nothing mounts on them and they do
nothing in the plant: the panel components, the mimic tape and the wiring tools all stay on the three panel bodies. They
break with any tool (a pickaxe is fastest), are crafted from iron, redstone, glass panes and a few dyes, and sit in the
creative tab after the panel components. The research behind the choice and the shapes is in
`CR-FURNITURE-BWR-RESEARCH.md` and `CR-FURNITURE-PWR-RESEARCH.md`.

| Block | Size | What it is |
|---|---|---|
| Relay Cabinet | 1 x 2 x 1 (two blocks) | Double front doors with a seam and bar handles, top and bottom louvres, a small indicator window, a recessed dark plinth, and a service door with louvres and a handle on the back. |
| Relay Cabinet (Plain Back) | 1 x 2 x 1 (two blocks) | The same front; the back is a flat panel at the edge of the block, so two rows can stand back to back. |
| Operator Desk | 1 x 1 x 1 | Straight desk, 0.75 m high, side panels, modesty panel, a pencil drawer. A run of these stands side by side. |
| Operator Desk (Round End) | 1 x 1 x 1 | A straight desk with a semicircular end for the end of a run, and a half-drum pedestal under it. |
| Operator Desk (L Corner) | 1 x 1 x 1 | An L-shaped top with two arms and an open crook, for the corner of a room or the turn of a run. |
| Supervisor Desk | 2 x 1 x 1 (two blocks) | A wide desk with a drawer pedestal on one side and an open knee space on the other. |
| Mimic Band (Cream) / (Black) | 1 x 1 x 1 | A flat board 3/16 deep for the wall, printed with a plain flow diagram, in the cream of EDF and Westinghouse boards and the black of the EDF and VVER synoptic friezes. Dark grey top and bottom rails; the lines meet at the sides, so a run of bands joins up. |
| Wall Display | 2 x 2 x 1 (four blocks) | A large flat display in a dark grey frame, with a dark screen showing a plant overview picture. |
| Wall Display (Wide) | 3 x 2 x 1 (six blocks) | The same, three blocks wide, for the AP1000 and ABWR wide display panel look. |
| Control Stand | 1 x 1 x 1 | A narrow pedestal column with a small tilted top carrying a lamp and button panel, for a status point or a pushbutton station on the floor of the room. |

The display picture is a still image (a vessel, steam line, turbine, generator, condenser, alarm tiles, bars and a trend).
It shows nothing live.

## Using them

- **Facing.** The front is the side that faces you when you place a piece, like the panels. A desk's front is where the
  operator sits; a cabinet's front is the door side; a band or display faces the room from the wall it is on.
- **Multi-block pieces** are placed whole by one item and broken whole: break any block and every block goes and exactly
  one item drops. If any block of the footprint is in the way, nothing is placed and the item is kept. The cabinet's upper
  half is above the block you click on. The supervisor desk and the displays grow toward the operator's left and up from the
  block where you place them: the drawer pedestal of a supervisor desk, the bottom right block of a display (right as
  seen from the front).
- **Monitors.** Each desk block (both halves of the supervisor desk, separately) can carry a flat monitor or a CRT with a
  keyboard. Right-click a desk with an empty hand to cycle none, flat monitor, CRT. A held item is never intercepted, so
  you can still place blocks against a desk.
- **Round-end desk.** Sneak and right-click with an empty hand to mirror it, which moves the rounded end to the other side
  of the run.
- **Mimic tape.** The tape draws on the panel bodies, which carry the block entity it needs. It does not work on the mimic
  bands; adding that needs a block entity and a renderer, which these blocks deliberately avoid.

## Finishes

Every piece takes one of five paint finishes (seafoam, the original; cream; tan; grey; light blue): sneak-right-click it with
the panel wrench to step to the next, the whole piece at once. Desks, cabinets and the control stand paint their body, bezel
and desktop; their dark trim, plinths, handles, louvres and screens stay. The thin housing of the mimic bands and wall
displays takes a darker shade of the finish. The item and its drop keep the finish. See CONTROL-PANELS.md, "Finishes".

## Laying out a room

These are guidance, not rules the blocks enforce.

- **Aisles.** Real rooms keep at least 96 in (2.44 m) between facing rows of boards, and a clear walk in front of back
  panels. Keep **at least two blocks of clear aisle between facing rows**, for example between the benchboard run and the
  desks, between a cabinet row and the next row it faces, and in front of a cabinet row against a wall. One block is a squeeze; two lets two
  people pass. The plain-back cabinets are the exception: stand two rows back to back and keep the two-block aisle on the
  fronts. The standard cabinet's service door is at the back, so leave a block behind it where you want to walk round it.
- **Desks.** A desk is 0.75 m high with an open knee space (about 0.65 m deep) and kick space under the top, more than the
  100 mm that ergonomics standards ask for. Face desks toward the boards they serve, about two blocks back from the
  benchboard front; the operator sits on the desk's front side and looks at the board over the monitor. Put the supervisor
  desk behind the operator desks, facing the same way, with the wall display or the board run in sight of both.
- **Seeing over.** Monitors on a desk stay below 1.15 m, so a standing operator can see over them to the board.
- **Back panels.** Relay cabinets belong behind or beside the operating area, in rows against a wall or back to back in the
  middle of a second aisle, as in the back panel areas of Duane Arnold, Grand Gulf and Susquehanna.
- **Mimic bands** above a run of vertical boards (black over cream boards is the EDF look) or along a wall under a display.
  Wall displays go about 1.5 to 2 m up; a 3 x 2 display over a cream band reads as an ABWR or AP1000 front wall.

## Crafting

Cabinet: iron ingots around an iron door and redstone. Plain-back cabinet: iron ingots around redstone. Operator desk: five
iron ingots, two desks. Round end and corner desk: one operator desk and an iron nugget or an iron ingot. Supervisor desk: two
operator desks and two iron ingots. Mimic band: a vertical control panel and bone meal (cream) or black dye. Wall display: iron
ingots and black stained glass panes (one pane for the 2 x 2, three for the 3 x 2). Control stand: a vertical control panel over an
iron ingot.

## How it is built

- Models are Blender-built OBJ files for `neoforge:obj`, authored for facing=north with the operator at -Z and 1 block = 1
  unit, like the panel bodies. `art/models/room_furniture/furniture_geo.py` holds the geometry in plain Python (boxes written
  face by face so hidden faces are left out, clipped per block for the multi-block pieces so there are no seams),
  `build_furniture.py` turns it into Blender meshes, saves `room_furniture.blend`, exports the OBJ files with Blender's
  exporter and writes the shared `materials.mtl`. Run it headless:
  `blender --background --factory-startup --python art/models/room_furniture/build_furniture.py -- --previews`
  (previews go to `tmp/furniture_preview/`).
- Colours match the panel bodies (seafoam face `A7BCAE`, bezel `7D9284`) with dark grey trim; the painted materials
  (`paint`, `bezel`, `top`, `frame`) are `minecraft:block/white_concrete` with a `neoforge_TintIndex` (4 to 7) that the paint
  finish colours (`PanelFinish`), the other plain colours are tinted by Kd, and only the louvres, indicator window, monitor screens, keyboard, the two
  mimic finishes and the two display pictures are real textures (`tools-make-furniture-textures.py`, standard library only).
- `tools-export-furniture.py` writes the model, blockstate, item model and loot table JSON from the OBJ list.
- Code is in `dev.bwr.mod.furniture`: `AssemblyBlock` (facing, footprint, one-item placement, whole-piece breaking) with
  `RelayCabinetBlock`, `DeskBase` and its four desks, `MimicBandBlock` and `WallDisplayBlock`.
- Tests: `RoomFurnitureTests` (GameTest) and the showroom `FurnitureClientCheck`
  (`:mod:runTurbineModelCheck -PbwrFurnitureClientCheck`, pictures in `run/turbineModelCheck/furniture-check/`).

## Face counts

Faces per block model (quads and triangles as exported); a placed cabinet is two models, a display one model per block.

| Block | Model files | Faces |
|---|---|---|
| Relay Cabinet | `cabinet_lower`, `cabinet_upper` | 56 + 67 = 123 |
| Relay Cabinet (Plain Back) | `cabinet_plain_lower`, `cabinet_plain_upper` | 43 + 54 = 97 |
| Operator Desk | `desk` | 34 |
| Operator Desk (Round End) | `desk_round`, `desk_round_mirrored` | 61 each |
| Operator Desk (L Corner) | `desk_corner` | 40 |
| desk monitors (add to any desk block) | `desk_flat` (also `_round_`, `_corner_` positions) / `desk_crt` | 25 flat, 23 CRT |
| Supervisor Desk | `supervisor_drawers`, `supervisor_knee` | 56 + 27 = 83 |
| Mimic Band (Cream / Black) | `mimic_band_cream`, `mimic_band_black` | 13 each |
| Wall Display (2 x 2) | `wall_display_2x2_<col><row>` | 10 per block, 40 in all |
| Wall Display (Wide, 3 x 2) | `wall_display_3x2_<col><row>` | 10, 10, 4, 4, 10, 10 = 48 in all |
| Control Stand | `control_stand` | 15 |

The `*_item` models (the whole cabinet, supervisor desk or display, centred) are used only for the inventory icon. A
typical furnished desk with a monitor is 59 faces, a cabinet 123: a room of twenty cabinets and a dozen desks is about
3,200 faces.
