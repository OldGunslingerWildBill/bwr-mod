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
| Lube oil console | Reservoir, two AC motor-driven oil pumps and a smaller DC emergency pump, duplex filter with transfer handle, oil cooler, level glass and gauge. An ochre supply header runs along the north side with a riser to each of the four bearings. | A lube oil system feeding the thrust and radial bearings: V4. The console's make-up is general practice. |
| Feed pump | Horizontal, single-stage, double-suction casing: a fat central volute between two suction chambers, with bolted end covers. Shaft seal housings, a thrust bearing housing outboard and a journal bearing inboard (drive end), both with split caps. Four centreline pedestals on two rails. | Pump type: V2, V3, V4. Casing, end covers, seals, outboard thrust bearing, inboard coupling end: the cross sections in V3 and V4. |
| Pump nozzles | On the casing centre plane, opposite each other: the discharge nozzle on top, the inlet nozzle underneath, where a wide branch feeds both sides of the impeller. A short offset spool takes the discharge up to its flange. The suction line drops from its flange at the west end, turns under the thrust bearing and runs between the pedestal rails to the inlet nozzle. Each line has a pressure gauge. | Opposed top and bottom nozzles: all four cross sections in V3 and V4. Flange positions are the game's. |
| Pump auxiliaries | Casing vent, minimum-flow branch with its valve off the discharge spool, seal-water injection tubing to both seals, nameplate. | Minimum-flow recirculation, seal water injection, vents and drains: V4. |
| Coupling | Spacer coupling under a yellow guard with caution labels. A three-gauge local board stands beside it. | General practice. |
| Front standard | Bearing pedestal with split cover, red hand-trip knob, speed pickup and oil-pressure gauge. It carries the control-valve servomotor. | General practice. |
| Turbine casing | Multistage condensing turbine, horizontally split with a studded joint. The small inlet end flares to a large exhaust hood with stiffener ribs, lifting lugs, a nameplate and an atmospheric relief diaphragm on top. | Multistage, condensing: V2, R2, R5, R7, R8. Casing shape is general practice. |
| Low-pressure steam train | Under the steam inlet flange: a large stop valve with a horizontal hydraulic actuator and spring can, then the control-valve chest on top of the casing. Four steam leads drop into the casing. A bar lift (posts, crossbar, stems) is worked by a lever from the servomotor. | Dual admission with a high-pressure and a low-pressure valve: V2. Two sets of stop and control valves: R1, R2, R4, R5. Multivalve: R7. Valve shapes are general practice. |
| High-pressure steam train | A small lagged branch off the inlet spool runs down the south side through its own stop valve and a single control valve with its own servomotor, into a separate nozzle boss on the casing. | V2, R1, R2, R4, R5. |
| Exhaust | A rectangular outlet on the south side of the hood, a rectangular-to-round transition and a stainless expansion joint, to the existing exhaust flange. | Exhaust to the main condenser: V1, V2, R1, R6. Duct details are general practice. |
| Exhaust-end bearing and turning gear | Bearing pedestal with split cap, then a turning gear case on the free end with a small vertical motor and a red engagement lever. | General practice (R10 describes the main turbine's motor-driven turning gear, not this machine's). |
| Foundation | One concrete pad, two pedestal rails under the pump and a bedplate under the turbine, with anchor bolts. | Floor-mounted at grade on a low base: V1. Details are general practice. |

Colours stay close to the earlier model: feed-pump blue-grey, turbine grey, dark bedplates, aluminium lagging, ochre oil
lines, a yellow guard and red trip and valve handles. The gauge, nameplate and guard-label textures are the existing
generic `tdp_*` ones. There are no maker names or logos.

### Where the game shape differs from the plant

- **One steam port, two steam trains.** The real turbine has two separate supplies: low-pressure steam for normal running and high-pressure main steam for startup and low load. The block has a single steam inlet, so both trains hang off that one flange.
- **Port positions.** The four flange centres are fixed by the block. Both water flanges face up, so the suction line comes down from above and sweeps under the pump; in a plant it would normally arrive from the piping below. The exhaust leaves sideways because the exhaust port is on the south face.
- **Size.** WNP-2's feed pump sets are listed as 9.5 x 4.0 x 3.7 m each (V1). The block's footprint is 9 x 3 x 3, so the model is close in length and a little narrow and low.
- **Which end drives the pump.** The inlet port is west of the exhaust port, so the turbine's inlet end faces the pump and the exhaust hood and turning gear are at the free end. The WNP-2 plan and section in V1 show each set only as a small outline, too coarse to settle which end drives.

## Compatibility

- **Old one-block saves:** these use the compact model (`compact.obj` and `compact.json` in `pumps/turbine_feed_pump_modern/`). Those two files are untouched and still show the earlier model.
  - The new source mesh lists the compact model's 23 materials first, in their old order (`art/models/modern/turbine_feed_pump_compact_materials.json`), so `materials.mtl` keeps `mat_0`..`mat_22` exactly as `compact.obj` expects. Five new materials follow them.
  - The source carries `frozen_compact: true`, so `tools-export-modern.py` no longer regenerates `compact.*` for this machine. The exporter itself was not edited; it already supported this for the motor pumps.
- **Held item:** uses a new scaled copy of the remodelled mesh, `inventory.obj`. The inventory icon was re-rendered.
- **Legacy cell set:** the non-modern cells (`pumps/turbine_feed_pump/cell_N`) that previously assembled saves use are not touched.
- **Collision boxes:** each cell's box follows the new mesh, as before. Fifteen of the 81 cells are empty (eleven before), and not all the same cells.
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
| Source triangles | 16,342 | 15,666 |
| Faces in the 81 baked cell OBJs | 19,928 | 19,230 |
| Materials | 23 | 28 |

This is a block-model (chunk mesh) machine. It is not in the cached GPU renderer covered by
[MACHINE-RENDERING.md](MACHINE-RENDERING.md). The client model check and the in-game port pictures
(`-PbwrTurbinePumpShots`, see TURBINE-DRIVEN-PUMPS.md) could not be run where this was authored; CI runs the build.

## REFERENCES

Nothing was downloaded into the repository. Tags show how each source was checked:

- **[V]** the document was opened and the claim read, or the figure looked at, on the page given.
- **[S]** the claim was matched in search results only; the page was not opened.

The V sources were downloaded from OSTI, read, and not kept. The R sources were not opened: nearly all are on the NRC site,
which refuses automated requests, and the other two (R9, R11) were not retried. Open those in a browser before relying on them.

### Opened sources

- V1 [V] NUREG/CR-0672 Vol. 2, *Technology, Safety and Costs of Decommissioning a Reference Boiling Water Reactor Power Station, Appendices* (Pacific Northwest Laboratory, 1980): https://www.osti.gov/servlets/purl/5278962
  - PDF p. 67 (C-1): the reference plant is described from the WNP-2 FSAR and from drawings supplied by the owner. WNP-2 is Columbia's earlier name.
  - PDF p. 71 (C-5): the auxiliaries include turbine-driven feedwater pumps.
  - PDF p. 132 (C-66), Table C.3-4: "Reactor Feedwater Pump (2)", 54,886 kg each, 9.5 x 4.0 x 3.7 m (L x W x H).
  - PDF p. 95 (C-29), Figure C.2-7, turbine building plan at grade: the two sets stand side by side, each in its own walled room next to the condenser. Each is drawn as one long in-line outline.
  - PDF p. 101 (C-35), Figure C.2-10, sections A-A and B-B: the sets sit on the grade floor on a low base. The outlines are small and schematic; no valve, nozzle or casing detail can be read from them.
  - PDF pp. 136 and 138 (C-70, C-72): penetrations in the main condenser shell take the exhaust from the feedwater pump turbines.
- V2 [V] *Energy Economic Data Base (EEDB) Program, Technical Reference Book* (United Engineers & Constructors for Oak Ridge National Laboratory, September 1983): https://www.osti.gov/servlets/purl/6021579
  - PDF p. 176 (4-46), the BWR model plant: two 50 % turbine-driven feed pumps "of the centrifugal single stage type", 17,500 gpm, 2,350 ft total head, 5,300 rpm, 10,300 brake horsepower. Each is driven by "a dual admission, multi-stage, condensing steam turbine" rated 10,300 hp at 5,300 rpm. The rest is said to be nearly identical to the PWR description.
  - PDF p. 101 (3-71), that PWR description: each turbine exhausts to the main condenser. The dual admission inlet is a high-pressure and a low-pressure valve. Main steam feeds the high-pressure valve; steam from the crossover piping (high-pressure turbine exhaust to the moisture separators) feeds the low-pressure valve. Normal running is on low-pressure steam; below 40 % load main steam supplements it.
  - PDF p. 171 (4-41): in the BWR plant, part of the steam flow is taken for the steam turbine drives of the reactor feedwater pumps, and the two turbine-driven pumps feed the high-pressure heater.
- V3 [V] EPRI FP-754, *Survey of Feed Pump Outages* (April 1978): https://www.osti.gov/servlets/purl/7039347
  - PDF p. 18 (section 3.1): a nuclear feed pump "is usually a single-stage double-suction design similar to a conventional feed water booster pump".
  - PDF pp. 70 and 71, Figures 5 and 6: cross sections of single-stage, double-suction reactor feed pumps with forged cases. Both show one large nozzle above the shaft and one below it, on the casing centre plane, with a bearing housing at each end.
- V4 [V] EPRI NP-1571, *Evaluation of Basic Causes of Repetitive Failures of Nuclear and Fossil Feedwater Pumps* (October 1980): https://www.osti.gov/servlets/purl/6724584
  - PDF p. 19 (II-2), Figure II-1, labelled cross section: discharge nozzle on top, inlet nozzle underneath, single-stage double-suction impeller, inboard and outboard end covers, radial bearings, a double-acting thrust bearing at the outboard end and the shaft extension at the inboard end.
  - PDF p. 65 (III-8), Figure III-2: another maker's single-stage, double-suction nuclear feed pump, again with opposed nozzles.
  - PDF p. 58 (III-1): all US nuclear feed pumps are horizontal centrifugal pumps, and the trend is to steam turbine-driven, single-stage, double-suction pumps.
  - PDF p. 61 (III-4), Table III-2, typical BWR feed pump: 2,800 ft head, 11,200 gpm, 5,500 rpm, 8,450 hp (ranges 3,600 to 7,000 rpm and 4,000 to 10,000 hp).
  - PDF p. 21 (II-4): the auxiliaries are minimum-flow recirculation, seal water injection and leak-off, a lube oil system for the thrust and radial bearings, vibration monitoring, warm-up, and vents and drains.

The first build of this model had twin top nozzles side by side. The cross sections in V3 and V4 show opposed nozzles
instead, so the pump was rebuilt with the discharge on top and the inlet underneath.

### Not opened: every source from here on is [S]

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

- **Columbia's pump type:** no Columbia document was opened that states the pump is single-stage and double-suction. V1 confirms turbine-driven feed pumps and their size at WNP-2. The pump type comes from the generic BWR descriptions in V2, V3 and V4.
- **Columbia's ratings:** no flow, head, speed or horsepower was found for Columbia's own pumps or turbines. The numbers above are for model or typical BWR plants (V2, V4) and from R3 and R4. They did not set any dimension.
- **Low-pressure steam source at Columbia:** R1 gives a low-pressure stop valve, but the search results did not show where its steam comes from. The model plant in V2 and other BWRs (R4) use crossover or moisture-separator steam.
- **No source for the look of the turbine:** no drawing or photograph of a feed pump turbine was opened. The casing shape, the stop and control valve bodies, the bar-lift valve gear, the turning gear, the relief diaphragm, the exhaust expansion joint, the make-up of the lube oil console, the coupling, the pedestals and the bedplate follow general practice. The GE manual figure (R2) would settle most of this.
- **Not modelled:** gland sealing-steam lines, the exhaust isolation valve, drains, and the second low-pressure supply pipe (see "One steam port" above).
- **Proportions:** these are game representations, not vendor drawings.
