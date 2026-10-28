# Pipes: the look of the water, steam and oil lines

The **High-Pressure Water Pipe** and the **High-Pressure Steam Pipe** (`bwr:pressurised_tube`) were remodelled in alpha.59.
Only the look changed: the block ids, the blockstate properties (six connections, `paint`, `waterlogged`), the connection
rules, the collision shape, the recipes and the saves are as they were. Old worlds get the new look as they load.
Before and after pictures are in [`art/pipe-remodel/`](art/pipe-remodel/).

## What they look like

- **Water pipe: painted carbon steel.** It is a bare 8 px pipe (the old bore), painted all over in **plant light blue**
  (`0x5A9FCC`). The colour stays in the mod's light-blue family but is a little greyer than the old bright band blue, so it
  reads as paint on steel rather than plastic. Blue is a common code for process water, and next to the steam line's
  aluminium and amber it can't be taken for anything else. The joints are **butt welds**: a narrow raised bead of unpainted
  steel at every block boundary. Bends are smooth **short-radius elbows** (bend radius = one pipe diameter), tees are welded
  tees, and a run that ends is **capped**.
- **Steam pipe: lagged main steam line.** It is 12 px across: thick insulation under **aluminium cladding**. Each block is
  one sheet, with the next sheet's **lap seam** across the middle and a longitudinal lap underneath. An **amber service
  band** covers the joint at every block boundary. The bends are **gored** like a real elbow jacket (four gores), and a dead
  end is closed with a cladding end disc.
- **Against a machine: a bolted flange.** Where a pipe meets a pump, a valve, a turbine or any other machine rather than
  another pipe, the client adds a **bolted flange** with eight nuts. It forms a flange pair with the machine's own nozzle
  flange. A pipe meeting a pipe stays a plain, seamless run.
- **Dye** still works on both, with the same rules (one dye a segment in survival, persistent, display only). It now repaints a
  water pipe's **paint** and a steam pipe's **bands**. Welds, flanges and cladding stay metal.

## How it is built

- **Geometry.** `art/models/pipes/build_pipes.py` runs in headless Blender:
  `blender --background --factory-startup --python art/models/pipes/build_pipes.py`.
  - It builds every part from one **parameter set per pipe**: radius, sides, body material (paint or cladding), joint style
    (`ring` with a weld or band material, or `flange`), cap, port flange and elbow segments.
  - It writes `art/models/pipes/<set>/mesh.json` and `art/models/pipes/pipes.blend` (one scene per set).
- **Export.** `python tools-export-pipes.py` (`--check` is read-only) writes the OBJ models, the model JSONs, `materials.mtl`,
  the blockstates and the item models.
  - It writes 64 `connection_<mask>` models per pipe, one per pattern of connected faces, as the blockstates have always used.
  - It also writes `port_<direction>` (the machine flange) and `item` (a spool with a flange at each end).
- **Textures.** `python tools-make-pipe-textures.py` (`--check`) writes `textures/block/pipes/`.
  - `painted_steel` and `service_band` are near white, because the block colour (tint index 0) gives them their colour.
  - The others are `weld`, `flange_steel` and `cladding`.
  - Each texture has a labPBR `_s` map. Paint is a dielectric; the weld, flange and cladding are iron or aluminium
    (`232`). There are no `_n` maps, as with the lamps ([LIGHTING.md](LIGHTING.md)).
- **The machine flange.** A blockstate says which faces connect, not to what. `piping/client/PipeJointModels` therefore
  wraps each pipe model.
  - When a chunk section is meshed, it reads the six neighbours once (`getModelData`).
  - It adds the port flange model on each connected face whose neighbour is not the same pipe.
  - This is client rendering only. The server never reads it.
- **Steam outline.** The steam pipe's **outline** (the selection box and what the crosshair picks) follows the 12 px cladding,
  so clicking the lagging selects the pipe and not the block behind it. Its **collision** and occlusion keep the 8 px shape
  they always had: walking, fluids, support and pathing are unchanged.
- **Tests.** The client check `:mod:runTurbineModelCheck -PbwrPipeShots=<label>` (`PipeShots`) saves 17 pictures to
  `run/turbineModelCheck/pipe-shots/<label>/`. It fails if:
  - a fixture pipe does not join what it was laid against;
  - a pump-port pipe lacks its flange;
  - a pipe-to-pipe joint draws one;
  - the steam pipe's outline and collision shapes are not 12 px and 8 px.

  The model check (`PumpModelCheck`) still asserts that every state of both pipes has tinted paint and plain metal.

## Polygons

Polygons are counts of quads (a few triangles in caps). The old models were all triangles, and the NeoForge OBJ loader turns
each triangle into its own quad.

| Part | Before (water and steam shared it) | Water | Steam | Oil (not in game yet) |
| --- | ---: | ---: | ---: | ---: |
| Lone segment | 352 | 70 | 46 | 46 |
| Dead end | 688 | 41 | 29 | 27 |
| Straight | 800 | 60 | 36 | 40 |
| Elbow | 1,312 | 120 | 72 | 64 |
| Tee | 1,616 | 96 | 60 | 64 |
| Six-way | 3,008 | 204 | 132 | 136 |
| All 64 patterns together | 104,416 | 7,184 | 4,568 | 4,592 |
| Machine flange (per connected port) | none | 69 | 69 | 39 |
| Item | 800 | 198 | 174 | 118 |

Straight runs, which are most of any plant, are open prisms with no faces on the block boundary. Neighbours join with no
seam and nothing hidden is drawn. The only boundary faces are the flanges' outer faces at machines, and those cull against
full blocks (`automatic_culling`).

## The oil pipe (not a block yet)

The `oil` parameter set is ready for the lubricating-oil pipe a later change adds:

- a 5 px small-bore pipe, painted **brown** (tint index 0), flanged at every joint, with a four-stud flange at machines;
- the same 64 + 6 + 1 models, already exported to `art/models/pipes/oil/resources/`, laid out like `mod/src/main/resources`
  (with a blockstate and item model for a block called `oil_pipe`);
- a preview at `art/models/pipes/oil_preview.png`.

To ship it:

1. Register the block (a `PaintedPipeBlock`), then set `'ship': True` for `oil` in `build_pipes.py` and add the block id to
   `BLOCKS` in `tools-export-pipes.py`.
2. Re-run the build and the export.
3. Add the block to `PipeColors` (default brown, for example `0x6F4A2E`, the preview colour) and to `PipeJointModels`.
