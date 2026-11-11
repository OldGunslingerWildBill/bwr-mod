# Turbine-driven feed pump remodel

The turbine-driven reactor feed pump, `bwr:turbine_feed_pump` (9 x 3 x 3), was rebuilt in Blender to look like the
Columbia-type machine: a single-stage, double-suction feed pump driven by a dual-admission condensing steam turbine.
The earlier model ([TURBINE-DRIVEN-PUMPS.md](TURBINE-DRIVEN-PUMPS.md)) showed a multistage barrel pump and a turbine
with one steam train. Block ID, footprint, controller cell, port cells, port faces and flange centres are unchanged, so
existing pipe runs still meet the flanges. Nothing about how the pump works changed.

Coordinates below are for a north-facing machine: west is the pump end, east the turbine end.

## What it shows

| Part, west to east | What is modelled | Reference (see REFERENCES) |
| --- | --- | --- |
| Lube oil console | Reservoir, two AC motor-driven oil pumps and a smaller DC emergency pump, duplex filter with transfer handle, oil cooler, level glass and gauge. An ochre supply header runs along the north side with a riser to each of the four bearings. | General practice; no plant source found. |
| Feed pump | Horizontal, single-stage, double-suction casing: a fat central volute between two suction chambers, radially split, with bolted end covers. Shaft seal housings, a thrust bearing housing outboard and a journal bearing inboard, both with split caps. Four centreline pedestals. | Pump type: R4, R5, R6, R9. Casing anatomy: R9. |
| Pump nozzles | Twin top nozzles side by side across the shaft. The suction nozzle (south) spreads into a branch that feeds both sides of the impeller. Formed spools carry them to the two existing top flanges, suction west and discharge east. Each spool has a pressure gauge. | Double suction: R9. Flange positions are the game's. |
| Pump auxiliaries | Casing vent, minimum-flow branch with its valve off the discharge spool, seal-water injection tubing to both seals, nameplate. | General practice. |
| Coupling | Spacer coupling under a yellow guard with caution labels. A three-gauge local board stands beside it. | General practice. |
| Front standard | Bearing pedestal with split cover, red hand-trip knob, speed pickup and oil-pressure gauge. It carries the control-valve servomotor. | General practice. |
| Turbine casing | Multistage condensing turbine, horizontally split with a studded joint. The small inlet end flares to a large exhaust hood with stiffener ribs, lifting lugs, a nameplate and an atmospheric relief diaphragm on top. | Multistage, condensing: R2, R5, R7, R8. |
| Low-pressure steam train | Under the steam inlet flange: a large stop valve with a horizontal hydraulic actuator and spring can, then the control-valve chest on top of the casing. Four steam leads drop into the casing. A bar lift (posts, crossbar, stems) is worked by a lever from the servomotor. | Two sets of stop and control valves, dual admission: R1, R2, R4, R5. Multivalve: R7. |
| High-pressure steam train | A small lagged branch off the inlet spool runs down the south side through its own stop valve and a single control valve with its own servomotor, into a separate nozzle boss on the casing. | R1, R2, R4, R5. |
| Exhaust | A rectangular outlet on the south side of the hood, a rectangular-to-round transition and a stainless expansion joint, to the existing exhaust flange. | Exhaust to the main condenser: R1, R6. Duct details are general practice. |
| Exhaust-end bearing and turning gear | Bearing pedestal with split cap, then a turning gear case on the free end with a small vertical motor and a red engagement lever. | General practice (R10 describes the main turbine's motor-driven turning gear, not this machine's). |
| Foundation | One concrete pad with separate bedplates for the pump and the turbine, with anchor bolts. | General practice. |

Colours stay close to the earlier model: feed-pump blue-grey, turbine grey, dark bedplates, aluminium lagging, ochre oil
lines, a yellow guard and red trip and valve handles. The gauge, nameplate and guard-label textures are the existing
generic `tdp_*` ones. There are no maker names or logos.

### Where the game shape differs from the plant

- **One steam port, two steam trains.** The real turbine has two separate supplies: low-pressure steam for normal running and high-pressure main steam for startup and low load. The block has a single steam inlet, so both trains hang off that one flange.
- **Port positions.** The four flange centres are fixed by the block. The pump nozzles therefore need spools to reach flanges that sit two blocks apart along the shaft. The exhaust leaves sideways because the exhaust port is on the south face.
- **Which end drives the pump.** The inlet port is west of the exhaust port, so the turbine's inlet end faces the pump and the exhaust hood and turning gear are at the free end. No source was found for which end Columbia's turbines drive from.

## Compatibility

- **Old one-block saves:** these use the compact model (`compact.obj` and `compact.json` in `pumps/turbine_feed_pump_modern/`). Those two files are untouched and still show the earlier model.
  - The new source mesh lists the compact model's 23 materials first, in their old order (`art/models/modern/turbine_feed_pump_compact_materials.json`), so `materials.mtl` keeps `mat_0`..`mat_22` exactly as `compact.obj` expects. Five new materials follow them.
  - The source carries `frozen_compact: true`, so `tools-export-modern.py` no longer regenerates `compact.*` for this machine. The exporter itself was not edited; it already supported this for the motor pumps.
- **Held item:** uses a new scaled copy of the remodelled mesh, `inventory.obj`. The inventory icon was re-rendered.
- **Legacy cell set:** the non-modern cells (`pumps/turbine_feed_pump/cell_N`) that previously assembled saves use are not touched.
- **Collision boxes:** each cell's box follows the new mesh, as before. Eleven of the 81 cells are empty, the same number as before, but not all the same cells.
- **Other pumps and the pipes:** the exporter's output for the four motor-driven pumps is byte-identical. The round pipes are not produced by this exporter (see [PIPES.md](PIPES.md)) and no pipe file changed.

## Authoring

```powershell
blender --background --factory-startup --python art/models/modern/build_turbine_feed_pump.py [-- --preview DIR]
python tools-export-modern.py
blender --background --factory-startup --python art/models/modern/build_turbine_feed_pump.py -- --icon
python tools-export-modern.py --check
python tools-audit-assets.py
```

Each `blender ... --python script.py` line also runs as `python3 script.py` where the `bpy` module is installed from PyPI.

`build_turbine_feed_pump.py` writes `art/models/modern/turbine_feed_pump.json` and the editable
`art/models/modern/turbine_feed_pump.blend`. It checks the ports, footprint and controller cell against the shipped
manifest first and refuses geometry outside the footprint. Options after `--`:

- `--preview DIR` renders five views of the new model (Cycles on the CPU, so no GPU or display is needed).
- `--before SRC.json --preview DIR` renders an older source JSON from the same five views, for comparison.
- `--icon` re-renders the inventory icon from the exported `inventory.obj`. It calls `art/models/inventory/render_icons.py` and forces Cycles, because EEVEE cannot start without a GPU.
- `--parts` lists the triangle count of the largest parts. `--no-save` leaves the `.blend` alone.

The script takes its primitives, paints and JSON writer from `art/models/turbine_pumps/build_models.py`. That script
still builds the Terry skids. Its `build_tfp()` is the earlier feed pump model; it no longer writes
`turbine_feed_pump.json`.

| Count | Old | New |
| --- | ---: | ---: |
| Source triangles | 16,342 | 15,890 |
| Faces in the 81 baked cell OBJs | 19,928 | 19,721 |
| Materials | 23 | 28 |

This is a block-model (chunk mesh) machine. It is not in the cached GPU renderer covered by
[MACHINE-RENDERING.md](MACHINE-RENDERING.md). The client model check and the in-game port pictures
(`-PbwrTurbinePumpShots`, see TURBINE-DRIVEN-PUMPS.md) could not be run where this was authored; CI runs the build.

## REFERENCES

Nothing was downloaded into the repository. Tags show how each source was checked:

- **[V]** the page was opened and the claim read there.
- **[S]** the claim was matched in search results only; the page was not opened.

**Every source below is [S].** Where this was authored, page fetches were blocked for every host, the NRC site
included, so no document could be opened and no figure or table was seen. Open these in a browser before relying on
them. No drawing or photograph of the machine was viewed for this model; the shapes are built from the text facts
below plus general turbine and pump practice.

### Columbia Generating Station

- R1 [S] Columbia FSAR, Amendments 53 and 59: https://www.nrc.gov/docs/ML1735/ML17355A661.pdf, https://www.nrc.gov/docs/ML1735/ML17355A668.pdf
  - Two turbine-driven reactor feedwater pumps. Their discharge passes the sixth stage of feedwater heating on the way to the vessel.
  - Feedwater flow is controlled by varying the speed of the turbines. At startup and low load the pumps run at constant speed and a startup valve throttles.
  - Main steam drives the feed pump turbines during startup and low load.
  - The turbines have a low-pressure stop valve, a high-pressure stop valve and control valves.
  - The main condenser receives the exhaust of the feed pump turbines. Sealing steam goes to the turbines' high-pressure ends.
- R6 [S] Columbia operator licensing examinations (the search did not show which of the two carries the question): https://www.nrc.gov/docs/ML0910/ML091030038.pdf, https://www.nrc.gov/docs/ML0106/ML010680246.pdf
  - The feed pumps trip on low condenser vacuum, which fits an exhaust to the main condenser.
- R11 [S] Columbia special inspection, discussed by the Union of Concerned Scientists: https://blog.ucs.org/dlochbaum/columbia-generating-station-nrcs-special-inspection-of-self-inflicted-safety-woes/
  - The feed pumps' steam supply is downstream of the main steam isolation valves.

### NRC training manuals

- R2 [S] General Electric Systems Technology Manual, chapter 2.6, Condensate and Feedwater: https://www.nrc.gov/docs/ML1125/ML11258A310.pdf
  - The feed pump drive is a six-stage, single-direction, dual-admission condensing turbine. The chapter has a figure of it, which was not seen.
- R3 [S] BWR/4 course handout, Condensate and Feedwater (most likely this file; the search named it only as a course handout): https://www.nrc.gov/docs/ML1125/ML11258A309.pdf
  - Two steam-driven feed pumps, each about 14,000 gpm at about 1,130 psig, 67 % of system capacity.
- R10 [S] Westinghouse Technology manual, sections 7.3 and 7.4 (General Electric turbine): https://www.nrc.gov/docs/ML1122/ML11223A249.pdf, https://www.nrc.gov/docs/ml1122/ml11223a247.pdf
  - A motor-driven turning gear drives a pinion that meshes with a bull gear on the shaft. This describes the main turbine.

### Other BWR safety analysis reports

- R4 [S] Peach Bottom UFSAR: https://www.nrc.gov/docs/ML1713/ML17130A254.pdf
  - Three horizontal turbine-driven feed pumps. A table lists "horizontal, single-stage" and a rated speed of 5,400 rpm, and the turbines as horizontal, dual-admission, multistage. The search excerpt did not make clear which table row is the pump, so check it.
  - One valve set takes high-pressure reactor steam and the other low-pressure steam from the crossaround piping. Crossaround steam is used in normal running, reactor steam at startup, low load and in transients.
- R5 [S] Fermi 2 UFSAR, chapter 10: https://www.nrc.gov/docs/ML1729/ML17298B245.pdf
  - The reactor feed pump is turbine-driven, horizontal, single-stage and centrifugal. Its turbine takes steam from two sources through two sets of main stop and control valves and exhausts to the condenser.
- R7 [S] LaSalle UFSAR, chapter 10: https://www.nrc.gov/docs/ML0813/ML081330080.pdf
  - Two turbine-driven feed pumps and one motor-driven. The drives are multivalve, multistage condensing turbines.
- R8 [S] Limerick UFSAR, chapter 10: https://www.nrc.gov/docs/ML2227/ML22278A267.pdf
  - Turbine-driven feed pumps with variable-speed, six-stage turbines.

### Vendor literature (anatomy only)

- R9 [S] Single-stage reactor feed pump brochure: https://www.abset.com/vamp/wp-content/uploads/2014/07/ps-80-4-ea4.pdf
  - A single-stage, high-speed feed pump with a double-suction impeller in a centreline-mounted, radially split casing, used for BWR reactor feed service.

### Gaps

- **Columbia's pump type:** no Columbia document was found that states the pump is single-stage and double-suction. The single stage comes from two other BWR reports (R4, R5) and the double suction from vendor literature (R9).
- **Columbia's ratings:** no flow, head, speed or horsepower was found for Columbia's pumps or turbines. The numbers above are from a BWR/4 handout (R3) and Peach Bottom (R4). They did not set any dimension.
- **Low-pressure steam source at Columbia:** R1 gives a low-pressure stop valve, but the search results did not show where its steam comes from. Other BWRs use crossaround or moisture-separator steam (R4).
- **No official source:** the turning gear on this turbine, the lube oil console and its pumps, the relief diaphragm, the exhaust expansion joint, the minimum-flow line, seal-water injection, the bar-lift valve gear, the couplings, the pedestals and the bedplates. These follow general practice for feed pump turbines and between-bearings pumps.
- **Not modelled:** gland sealing-steam lines, the exhaust isolation valve, drains, and the second low-pressure supply pipe (see "One steam port" above).
- **Proportions:** these are game representations, not vendor drawings.
