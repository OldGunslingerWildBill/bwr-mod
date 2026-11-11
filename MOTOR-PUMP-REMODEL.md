# Motor-driven pump remodel

The LPCS, HPCS, RHR/LPCI, motor feedwater and SLC pump models were reworked against NRC, DOE and EPRI reports on the
real equipment (listed under REFERENCES). This replaces the earlier remodel, which leaned on generic pump and motor
sources. Block IDs, footprints, controller cells, port cells, port faces and flange centres are unchanged, so existing
pipe runs still meet the flanges. Nothing about how the pumps work changed. The SLC boron tank was not touched: no
reference that could be opened describes it, so there was nothing to correct it against.

| Machine | Footprint (W x H x D) | Real form, per the references | How it fits the footprint |
| --- | --- | --- | --- |
| LPCS | 5 x 5 x 3 | Vertical can-type pump, 4160 V open vertical motor of about 1500 hp | Discharge head on a sole plate with in-line suction and discharge nozzles. Guarded motor stand with shaft, coupling, mechanical seal and cyclone separator. Mid-sized motor. Minimum-flow line with orifice and motor-operated valve. Water-leg pump piped from the suction to the discharge. |
| RHR / LPCI | 7 x 3 x 3 | Vertical can-type pump with mechanical seal and cyclone separator, about 800 hp | Suction drops from the west flange to the can nozzle at floor level. Discharge leaves the head through a check valve and rises to the top flange. Smallest motor. Minimum-flow line, seal-water cooler and instrument rack. |
| HPCS | 9 x 4 x 3 | Vertical motor-driven centrifugal pump, 3000 hp | Same arrangement as RHR with the largest motor. Minimum-flow line with restricting orifice and motor-operated valve. Water-leg (jockey) pump: a small horizontal single-stage pump taking suction from the main suction line and discharging downstream of the check valve. |
| Motor feedwater | 7 x 3 x 3 | Single-stage double-suction reactor feed pump, speed-increasing gear, large horizontal motor | Short forged casing between two bolted end covers, bearing housings on brackets each side, centreline feet. Discharge nozzle on top, straight to its flange. Suction nozzle underneath; the line runs under the outboard bearing and rises to its flange. Gear case with offset shafts, box-frame motor with a top air housing, lube oil console, minimum-flow line with a control valve. |
| SLC pump | 3 x 2 x 3 | Horizontal triplex plunger pump driven through a triple-reduction gear reducer | Liquid end with three plungers, stuffing boxes, discharge valve covers and manifold above, round suction manifold below. Power end with a sloped frame top and vent. Reducer with four shaft bearing caps stepping up in size. Heat tracing on the suction manifold. Strainer, pulsation dampener, relief valve and gauge as before. |
| SLC boron tank | 3 x 4 x 3 | Not checked against a reference | Unchanged from the previous remodel. |

For the vertical pumps the can is implied to hang below the floor; only the motor, discharge head and the top of the can show.

## What changed, and why

- **ECCS motors.** The finned, fan-cooled frames are gone. The three motors are now open air-cooled vertical machines:
  screened air intakes in the lower bracket, a smooth stator frame with stiffening ribs, a louvred air housing under
  the top cover, and the upper thrust-bearing oil reservoir with a sight glass, fill cap and cooling-water tubes. The
  terminal box is larger, as for a 4160 V machine. (Open, air-cooled motor: a Columbia event report describes the
  HPCS motor's upper air deflector directing cooling air into the housing. Motor cooling water: NUREG/CR-5268 Table A-2.)
- **Motor sizes.** HPCS is now the largest and RHR the smallest, following 3000 hp, about 1500 hp and 800 hp. The LPCS
  motor used to be the biggest of the three only because its footprint is the tallest. The footprints still limit this:
  the volumes are in a ratio of about 3.3 : 2.5 : 1 against 3.75 : 1.9 : 1 for the horsepower.
- **Seal flush.** Each vertical pump has a cyclone separator on the motor stand, with tubing from the discharge head,
  to the seal gland and back to the suction side (NUREG/CR-5268: "vertical can-type pumps with mechanical seals and
  cyclone separators"). The seal-water cooler on RHR and HPCS stays (same report, Table A-2).
- **Minimum-flow lines.** RHR and LPCS gain one; all three now show a restricting orifice ahead of the motor-operated
  valve (NUREG/CR-5462 Figure 8, NUREG/CR-5268 section 2.1.1).
- **Water-leg pumps.** HPCS and LPCS each have a small horizontal single-stage pump on its own base (NUREG/CR-5462
  section 2.3.2 and Figure 8). RHR has none in the model; no source that could be opened puts one on the RHR pump.
- **Motor feedwater pump.** Rebuilt. The long multistage barrel casing and the variable-speed fluid coupling had no
  support in the references. EPRI FP-754 describes reactor feed pumps as usually single-stage double-suction and
  shows two sections (Figures 5 and 6) with the nozzles on the vertical centreline, a short casing and two end covers.
  The drive is now a constant-speed motor through a speed-increasing gear, which FP-754 lists as one of the standard
  arrangements. Search results agree: a geared constant-speed motor at Nine Mile Point 2, and flow control by regulating
  valves rather than pump speed at LaSalle.
- **SLC pump.** The single gear box became a triple-reduction reducer (SAND93-7045). The power end has the sloped
  frame top and vent of Figure 3-12. The suction manifold carries electric heat tracing (same report: the suction
  piping and plunger casing are heat traced). The liquid end already matched Figure 3-11 and was kept.

## Details common to all

- **Bolting:** bolted raised-face flanges with gaskets and hex nuts on the nozzles, valves and spools.
- **Instruments:** taps with a root valve, red handwheel and dial gauge.
- **Foundations:** sole plates or baseplates on grout pads, with anchor bolts.
- **Markings:** yellow coupling guards with a caution label, and equipment tags. Identification colours are unchanged: LPCS green, RHR teal, HPCS red, feed pump blue, SLC green.
- **Plates:** all text is generic. There are no maker names or logos.

## Compatibility

- **Old one-block saves:** these use the compact models (`compact.obj` and `compact.json` in each pump folder). Those files are untouched.
  - Each source mesh lists the old compact model's materials first (`art/models/modern/legacy_compact_materials.json`), so `materials.mtl` keeps `mat_0..` as `compact.obj` expects. Materials after those were renumbered by this rebuild; nothing but the regenerated cell and inventory meshes uses them.
  - The source carries `frozen_compact: true`, so `tools-export-modern.py` and `tools-export-alpha.py` do not regenerate `compact.*` for these machines.
- **Held item:** uses the scaled copy of the remodelled mesh, `inventory.obj`. The five pump icons were re-rendered.
- **Legacy cell sets:** the non-modern cell sets (`pumps/<id>/cell_N`) that previously assembled saves use are not touched.
- **Collision boxes:** each cell's box follows the new mesh, as before.
- **Turbine feed pump and pipes:** not part of this work. The turbine feed pump has its own script and write-up ([TURBINE-FEED-PUMP-REMODEL.md](TURBINE-FEED-PUMP-REMODEL.md)); the round pipes are built in `art/models/pipes` ([PIPES.md](PIPES.md)). Neither comes from `modern_pumps_and_pipes.blend` any more.

## Authoring

```powershell
python art/models/modern/make_motor_pump_texture.py          # 256 x 256 detail sheet (plates, louvres, screens, gauge)
blender.exe --background --factory-startup --python art/models/modern/build_motor_pumps.py [-- --preview DIR]
python tools-export-modern.py; python tools-export-alpha.py     # then --check for each
```

Without a Blender binary, the `bpy` module from PyPI runs the same scripts:

```sh
python3 art/models/modern/build_motor_pumps.py -- [--preview DIR] [--only id,id] [--no-save]
BWR_ICON_ONLY=lpcs_pump,hpcs_pump,rhr_pump,motor_feed_pump,slc_pump python3 art/models/inventory/render_icons.py
```

In that mode there is no GPU, so previews and icons render with Cycles on the CPU instead of EEVEE (`--cycles` forces the same under Blender). The five icons in this change were rendered that way; they are slightly grainier than the EEVEE icons beside them.

`build_motor_pumps.py` writes the source meshes (`art/models/modern/<id>.json`, `art/models/alpha/slc_*.json`) and saves the scenes to `motor_pumps.blend`. Options:

- `--preview DIR` renders three views of each machine.
- `--before SRC --preview DIR` renders an older source JSON from the same three views, for comparison.
- `--only id,id` builds a subset; `--no-save` leaves `motor_pumps.blend` alone.

Source triangles (the baked cell meshes carry the same count):

| Machine | Before any remodel | Previous remodel | Now |
| --- | ---: | ---: | ---: |
| LPCS | 7,552 | 10,602 | 11,618 |
| HPCS | 8,306 | 15,504 | 15,580 |
| RHR | 8,318 | 14,744 | 15,086 |
| Motor feed | 8,318 | 9,894 | 10,470 |
| SLC pump | 3,492 | 8,374 | 8,176 |
| SLC tank | 4,940 | 5,672 | 5,672 |

These are block-model (chunk mesh) machines. They are not in the cached GPU renderer covered by [MACHINE-RENDERING.md](MACHINE-RENDERING.md). The client model check bakes every cell in all four rotations.

## REFERENCES

Nothing was downloaded into the repository. Tags show how each claim was checked:

- **[V]** the document was opened and the claim read there (text, table or figure).
- **[S]** the claim appeared in a search result for the document, which could not be opened. Open these in a browser before relying on them.

**What could not be reached.** The NRC website refuses automated downloads, so none of the documents hosted only there
were opened: the Columbia FSAR (chapters 5.4, 6.3, 8.3, 9.3.5, 10.4.7), the NRC BWR/4 and BWR/6 technology manuals, the
other BWR/5-6 FSARs, the NRC plant sourcebook for Columbia and the Information Notices. Everything tagged [V] below
came from the DOE document server (osti.gov) or the INL system-study site, which host NRC contractor reports. Every
Columbia-specific number is therefore [S] at best.

### ECCS pumps (RHR/LPCI, LPCS, HPCS)

- [V] NUREG/CR-5462, *Aging Study of Boiling Water Reactor High Pressure Injection Systems* (INEL for the NRC): https://www.osti.gov/servlets/purl/34240
  - Section 2.3: the HPCS pump is a 3000 hp electric-driven centrifugal pump, 1550 gpm at 1147 psi and 6110 gpm at 200 psi reactor pressure.
  - Section 2.3.2: the system is kept full by a jockey pump, a single-stage horizontal centrifugal pump of at most 50 gpm.
  - Figure 8 (typical HPCS flow diagram): pressure taps on suction and discharge, a discharge check valve, a minimum-flow line with restricting orifice and motor-operated valve, test lines, and the jockey pump piped from the main suction to the discharge.
- [V] NUREG/CR-5268, *Aging Study of Boiling Water Reactor Residual Heat Removal System* (BNL for the NRC): https://www.osti.gov/servlets/purl/5960372
  - Section 2.1.1: "The RHR pumps are motor driven, vertical can-type pumps with mechanical seals and cyclone separators", driven by 4160 V three-phase motors; a minimum-flow line returns to the suppression chamber and its valve opens on low flow. LPCI flows run from about 7000 to 10,000 gpm.
  - Plant grouping table in section 2: Columbia (listed as WNP2) is in the group with three loops, three pumps and two heat exchangers.
  - Table A-2: plant cooling water serves pump seal coolers, pump coolers and, at some plants, the pump motors.
- [V] INL/NRC High Pressure Core Spray system study, system description: https://nrcoe.inl.gov/publicdocs/SystemStudies/hpcs-system-description.pdf
  - One motor-driven centrifugal pump outside containment, 4160 V supply, suction from the condensate storage tank or the suppression pool; Figure 1 shows the minimum-flow and test lines. Columbia is one of the eight plants covered.
- [S] Columbia FSAR section 8.3.1.1.7.2.2 (Amendments 61 and 66): HPCS pump motor nameplate 3000 hp at 4000 V. https://www.nrc.gov/docs/ML2134/ML21349B385.pdf
- [S] LaSalle (BWR/5) design analysis: LPCS pump motor 1517 hp, RHR pump motors 800 hp, HPCS 3050 hp. https://www.nrc.gov/docs/ML1511/ML15113B041.pdf
  - This is LaSalle, not Columbia. It is the only source found for the LPCS and RHR motor sizes.
- [S] Columbia event notification, March 2005: degradation of the upper air deflector of the HPCS pump motor, the part that directs cooling air into the motor housing. https://www.nrc.gov/documents-reports/document-collections/events-reports-associated-with/event-notification-reports/2005/20050316en
- [S] Columbia water-leg pumps LPCS-P-2 and HPCS-P-2: https://www.nrc.gov/docs/ML1105/ML110550189.pdf and https://www.nrc.gov/docs/ML0106/ML010680246.pdf
- [S] Grand Gulf (BWR/6) pump data in an FSAR question response (carried over from the previous remodel): RHR 3-stage, LPCS 4-stage, HPCS 13-stage, 1780 rpm. https://www.nrc.gov/docs/ML2000/ML20009G882.pdf
- Carried over from the previous remodel, where it was tagged [V]; not re-opened this time: Columbia Technical Specification test flows (LPCS ≥ 6200 gpm, LPCI ≥ 7200 gpm, HPCS ≥ 6350 gpm; SLC ≥ 41.2 gpm at ≥ 1220 psig). https://www.nrc.gov/docs/ML1718/ML17186A435.pdf

### Motor-driven feedwater pump

- [V] EPRI FP-754, *Survey of Feed Pump Outages* (1978): https://www.osti.gov/servlets/purl/7039347
  - Section 3.1: nuclear feed pumps, called reactor feed pumps in a BWR, are usually single-stage double-suction; a small share are multistage. A pump may be driven by a constant-speed motor directly, through a gear box or through a variable-speed hydraulic coupling, or by a turbine.
  - Figure 5 (single-stage double-suction, forged case, diffuser type) and Figure 6 (forged outer casing, double-volute inner casing, double-suction impeller): the casing proportions, the two end covers with their studs, the nozzles on the vertical centreline and the bearing housings either side.
  - Minimum flow for feed pumps is usually not less than 25 % of design flow.
  - This is an industry report, not an NRC document. No NRC description of a BWR motor-driven feed pump could be opened.
- [S] LaSalle UFSAR chapter 1: feedwater flow is controlled by the speed of the turbine-driven pumps or by the regulating valves on the motor-driven reactor feed pump, i.e. that pump runs at constant speed. https://www.nrc.gov/docs/ML1611/ML16111B212.pdf
- [S] Conference paper on a Nine Mile Point 2 reactor feed pump: 16,500 hp, 1785 rpm induction motor, speed-increasing gear of ratio 1 : 1.87, two-stage barrel pump. Vendor-authored, not official. https://kh.aquaenergyexpo.com/wp-content/uploads/2024/01/Thermal-Load-Effect-on-Vibration-on-a-Reactor-Feed-Water-Pump-FWP.pdf

### Standby Liquid Control

- [V] SAND93-7045, *Aging Management Guideline for Commercial Nuclear Power Plants - Pumps* (Sandia for the DOE, 1994): https://www.osti.gov/servlets/purl/10143968
  - Section 3.4.5.2: SLC pumps are "typically stainless steel reciprocating triplex plunger type which are driven by electric motors through a triple reduction gear reducer"; the suction piping and plunger casing are electrically heat traced.
  - Pump data table in section 3: horizontal, 34 to 62 gpm, 650 to 1220 psi.
  - Figure 3-11 (liquid end: suction manifold and valve below, cylinder, stuffing box and plunger, discharge valve and manifold above) and Figure 3-12 (power end: crankshaft, crosshead, sloped frame, vent).
  - The same report calls BWR core spray and RHR pumps "single stage, vertical". That reads as the older plants; it does not match the Grand Gulf stage counts above. The stages are below the floor either way.

### Gaps

- **Columbia FSAR:** not opened at all. Section 6.3 and Table 6.3 pump data, the 5.4.7 RHR description, 9.3.5 and its figures, and nozzle sizes and elevations are all unverified.
- **LPCS and RHR motor horsepower:** LaSalle figures from a search result, not Columbia's.
- **Motor-driven feed pump:** whether Columbia has one was not verified; the model follows the general BWR form in FP-754. The gear ratio, motor size and the lube oil console are judgment.
- **Vertical pump nozzle arrangement:** unchanged from the previous remodel, and still not checked against a drawing. No opened reference shows where the suction and discharge nozzles sit on a BWR/5 ECCS pump.
- **Motor details:** the open air-cooled enclosure rests on one search result about the HPCS motor. The bearing oil reservoir, sight glass and lifting eyes are ordinary features of large vertical motors, added from judgment.
- **SLC pump:** the pulsation dampener, relief valve and strainer are kept from the previous remodel without a reference. The motor size is unsourced.
- **SLC tank:** no reference opened.
- **Proportions:** these are game representations fitted to fixed footprints, not vendor drawings.
