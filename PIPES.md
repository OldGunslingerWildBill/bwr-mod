# Pipes: the look of the water, steam and oil lines

The **High-Pressure Water Pipe** and the **High-Pressure Steam Pipe** (`bwr:pressurised_tube`) were remodelled in alpha.59, and the
**Lube Oil Pipe** (`bwr:lube_oil_pipe`, below) joined them as a third pipe of its own.
Only the look changed: the block ids, the blockstate properties (six connections, `paint`, `waterlogged`), the connection
rules, the collision shape, the recipes and the saves are as they were. Old worlds get the new look as they load.
Before and after pictures are in [`art/pipe-remodel/`](art/pipe-remodel/); the owner's review changes (the water pipe's 16 sides
and steel banding, the duplex oil filter's pipework) are in [`art/pipe-review/`](art/pipe-review/).

## What they look like

- **Water pipe: painted carbon steel.** It is a bare 8 px pipe (the old bore), painted all over in **plant light blue**
  (`0x5A9FCC`). The colour stays in the mod's light-blue family but is a little greyer than the old bright band blue, so it
  reads as paint on steel rather than plastic. Blue is a common code for process water, and next to the steam line's
  aluminium and amber it can't be taken for anything else. It is **16-sided**, so it reads round rather than octagonal, and it
  carries **bare steel banding**: a chunky **coupling collar** (3 px wide, standing 1 px proud) over every joint at the block
  boundary, and a narrow **steel band** round the middle of every straight block. Bends are smooth **short-radius elbows** (bend
  radius = one pipe diameter) with a collar at each end, tees are welded tees, and a run that ends is **capped**. (Until the
  alpha.59 review it was 12-sided with a thin raised weld bead at each joint; the owner asked for more sides and steel banding.)
- **Steam pipe: lagged main steam line.** It is 12 px across: thick insulation under **aluminium cladding**. Each block is
  one sheet, with the next sheet's **lap seam** across the middle and a longitudinal lap underneath. An **amber service
  band** covers the joint at every block boundary. The bends are **gored** like a real elbow jacket (four gores), and a dead
  end is closed with a cladding end disc.
- **Against a machine: a bolted flange.** Where a pipe meets a pump, a valve, a turbine or any other machine rather than
  another pipe, the client adds a **bolted flange** with eight nuts. It forms a flange pair with the machine's own nozzle
  flange. A pipe meeting a pipe stays a plain, seamless run.
- **Dye** still works on both, with the same rules (one dye a segment in survival, persistent, display only). It now repaints a
  water pipe's **paint** (the blue between the bands) and a steam pipe's **amber bands**. The water pipe's collars and bands,
  every flange and the steam cladding stay bare metal.

## How it is built

- **Geometry.** `art/models/pipes/build_pipes.py` runs in headless Blender:
  `blender --background --factory-startup --python art/models/pipes/build_pipes.py`.
  - It builds every part from one **parameter set per pipe**: radius, sides, body material (paint or cladding), joint style
    (`ring` with a steel-collar or band material, or `flange`), an optional mid-block band, cap, port flange and elbow segments.
  - It writes `art/models/pipes/<set>/mesh.json` and `art/models/pipes/pipes.blend` (one scene per set).
- **Export.** `python tools-export-pipes.py` (`--check` is read-only) writes the OBJ models, the model JSONs, `materials.mtl`,
  the blockstates and the item models.
  - It writes 64 `connection_<mask>` models per pipe, one per pattern of connected faces, as the blockstates have always used.
  - It also writes `port_<direction>` (the machine flange) and `item` (a spool with a flange at each end).
- **Textures.** `python tools-make-pipe-textures.py` (`--check`) writes `textures/block/pipes/`.
  - `painted_steel` and `service_band` are near white, because the block colour (tint index 0) gives them their colour.
  - The others are `flange_steel` (flanges, the water pipe's collars and bands, caps) and `cladding`.
  - Each texture has a labPBR `_s` map. Paint is a dielectric; the steel and the cladding are iron or aluminium
    (`232`). There are no `_n` maps, as with the lamps ([LIGHTING.md](LIGHTING.md)).
- **The machine flange.** A blockstate says which faces connect, not to what. `piping/client/PipeJointModels` therefore
  wraps each pipe model.
  - When a chunk section is meshed, it reads the six neighbours once (`getModelData`).
  - It adds the port flange model on each connected face whose neighbour is not the same pipe.
  - This is client rendering only. The server never reads it.
- **Steam outline.** The steam pipe's **outline** (the selection box and what the crosshair picks) follows the 12 px cladding,
  so clicking the lagging selects the pipe and not the block behind it. Its **collision** and occlusion keep the 8 px shape
  they always had: walking, fluids, support and pathing are unchanged.
- **Tests.** The client check `:mod:runTurbineModelCheck -PbwrPipeShots=<label>` (`PipeShots`) saves 26 pictures to
  `run/turbineModelCheck/pipe-shots/<label>/`. It fails if:
  - a fixture pipe does not join what it was laid against;
  - a pump-port pipe lacks its flange;
  - a pipe-to-pipe joint draws one;
  - the steam pipe's outline and collision shapes are not 12 px and 8 px;
  - the lube oil fixtures fail the same tests (a flange where an oil pipe meets a turbine port, none between pipes, the brown paint
    and plain flanges, the pipe model wrapped for port flanges) or the Lube Oil Valve's open and shut states do not reach the client or
    draw alike (the valve is opened on the server mid-check and the client must show it).

  The model check (`PumpModelCheck`) still asserts that every state of all three pipes has tinted paint and plain metal, and that the
  Lube Oil Valve has a model in every state.

## Polygons

Polygons are counts of quads (a few triangles in caps). The old models were all triangles, and the NeoForge OBJ loader turns
each triangle into its own quad.

| Part | Before (water and steam shared it) | Water | Steam | Oil |
| --- | ---: | ---: | ---: | ---: |
| Lone segment | 352 | 94 (was 70) | 46 | 46 |
| Dead end | 688 | 55 (was 41) | 29 | 27 |
| Straight | 800 | 128 (was 60) | 36 | 40 |
| Elbow | 1,312 | 160 (was 120) | 72 | 64 |
| Tee | 1,616 | 128 (was 96) | 60 | 64 |
| Six-way | 3,008 | 272 (was 204) | 132 | 136 |
| All 64 patterns together | 104,416 | 9,728 (was 7,184) | 4,568 | 4,592 |
| Machine flange (per connected port) | none | 79 (was 69) | 69 | 39 |
| Item | 800 | 286 (was 198) | 174 | 118 |

Straight runs, which are most of any plant, are open prisms with no faces on the block boundary. Neighbours join with no
seam and nothing hidden is drawn. The only boundary faces are the flanges' outer faces at machines, and those cull against
full blocks (`automatic_culling`).

## The turbine sections' oil ports

Every HP turbine, LP turbine and generator section shows its two bearing oil ports (TURBINE-AUX.md, Piping): a short stub of Lube
Oil Pipe bore out of the casing and a blanked flange the size of the pipe's machine flange (4.5/16 across, four studs at 45 degrees),
painted **cobalt blue** (`#0047AB`) on the supply port (oil in, the section's west flank) and **brown** (`#6F4A2E`) on the drain port
(oil out, the east flank). On the HP, whose flank there is its foundation plinth, the stub ends in a small terminal box on the plinth.

- **Where:** `piping/client/TurbineOilPortModels` adds the port model to every cell state for which `PowerModuleBlock.oilPortAt`
  names a port on a face: the same method the oil network uses, so drawing and plumbing cannot drift, and every blockstate of the
  cell (each facing; the LP and HP with the hood off or burst open, whose floor row stays) gets it. Unassembled (compact) sections
  have no ports and draw none; the item models show no casing and are unchanged.
- **With a pipe:** the pipe draws its own steel machine flange against the painted one (PipeJointModels treats the section as a
  machine), so a connected port reads as one flange pair, steel against blue or brown. Neither is skipped: they sit back to back on
  the cell boundary and their blind faces hide each other.
- **Meshes:** the `turbine_ports` set in `art/models/pipes/build_pipes.py` (`TURBINE_PORTS`: the depth from the cell face to the
  casing, measured from the exported cells; re-measure if a casing is remodelled), 24 models (three sections, supply and drain,
  four faces), written by `tools-export-pipes.py`. 50 polygons a port on the LP and generator, 55 on the HP (with its box): 100 to
  110 a section, against the casings' 13,764 (HP), 12,060 (LP) and 4,482 (generator) polygons.

## The oil pipe

The **Lube Oil Pipe** (`bwr:lube_oil_pipe`) carries turbine lube oil (TURBINE-AUX.md, *Lube oil piping*):

- a 5 px small-bore pipe, painted **brown** (tint index 0, `0x6F4A2E` in `PipeColors`), flanged at every joint, with a four-stud flange at
  machines (the `oil` set of `build_pipes.py`; the models are in the mod's assets like the other two, with a blockstate and item model);
- **a network of its own.** Its one connection rule is `OilLineNetwork.acceptsLineOn`: other lube oil pipes, lube oil valves, filter
  housings (inlet and outlet faces) and the oil ports of the oil equipment (Main Oil Tank, Shaft-Driven Main Oil Pump, every turbine and
  generator section). The water and steam rules accept none of these blocks, so an oil pipe laid against a water or steam pipe grows no
  arm and the other pipe grows none towards it; it also never joins the cooling water flanges of the oil tank, which are not oil ports;
- **dyeable** like the others (the shared `PaintedPipeBlock`): only the paint changes colour, and the default service colour is brown;
- the client adds the **bolted flange** where it meets a machine (`PipeJointModels`, set `oil`), not between two pipes;
- the **Lube Oil Valve** (`bwr:lube_oil_valve`, `art/models/lube_oil_valve/`) is a ball valve with a limit switch box on top whose
  dome is green (open, bar along the pipe) or red (shut, bar across it); its two models, `open` and `closed`, are chosen by the `open`
  block state, so the indicator changes in the world the moment the valve does. `python tools-export-lube-oil-valve.py --check` verifies
  its assets.

Pictures: `art/oil-piping/` (the pipe shots check, `-PbwrPipeShots`).
