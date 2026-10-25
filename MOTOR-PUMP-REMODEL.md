# Motor-driven pump remodel

The LPCS, HPCS, RHR/LPCI, motor feedwater and SLC pump models, and the SLC boron tank, were rebuilt in Blender to look
like the real equipment. Block IDs, footprints, controller cells, port cells, port faces and flange centres are
unchanged, so existing pipe runs still meet the flanges. Nothing about how the pumps work changed.

| Machine | Footprint (W x H x D) | Real form | How it fits the footprint |
| --- | --- | --- | --- |
| LPCS | 5 x 5 x 3 | Vertical multistage can pump with a vertical induction motor | Discharge head on a sole plate. In-line suction and discharge nozzles on the head, bolted to the port spools. Guarded motor stand with the shaft, spacer coupling and seal. A 3-block weather-protected motor on top. |
| RHR / LPCI | 7 x 3 x 3 | Vertical can pump, as above | Low suction nozzle on the exposed top of the can. The suction line drops from the west flange to floor level. The discharge leaves the head through a swing check valve and rises to the top flange. A squat motor fits in 3 blocks. A seal-water cooler and a local instrument rack fill the east end. |
| HPCS | 9 x 4 x 3 | Vertical can pump (13 stages), the largest motor | Same arrangement as RHR with a taller motor. Adds a minimum-flow line with a motor-operated globe valve. |
| Motor feedwater | 7 x 3 x 3 | Horizontal barrel multistage pump, variable-speed fluid coupling, horizontal motor | Barrel casing with a bolted discharge cover and centreline feet. Top suction and discharge nozzles bolted to spools that reach the top flanges. Coupling guards, a fluid coupling with scoop-tube actuator and oil cooler, and a ribbed TEFC motor with fan cowl. |
| SLC pump | 3 x 2 x 3 | Horizontal triplex positive-displacement plunger pump | Fluid end with three plungers, packing glands and valve covers. Power frame, gear reducer and TEFC motor on a skid. Suction Y-strainer and manifold. Discharge manifold with pulsation dampener, relief valve and gauge. |
| SLC boron tank | 3 x 4 x 3 | Heated sodium pentaborate storage tank | Insulated stainless jacket on legs. Mixer drive, manway, air sparger line, immersion heater with terminal box, level gauge, ladder and placard. |

In every case the pump can of a vertical pump is implied to hang below the floor, as in the real plants; only the motor, discharge head and the top of the can show.

## Details common to all

- **Motors:** painted frames with cooling fins. The vertical ones add a top hat with louvred air openings and a drip cap. All have lifting eyes, a main terminal box with rigid conduit to a floor seal, an auxiliary heater/RTD box with flexible conduit, and a generic nameplate.
- **Bolting:** bolted raised-face flanges with gaskets and hex nuts on the nozzles, valves and spools.
- **Instruments:** taps with a root valve, red handwheel and dial gauge.
- **Foundations:** sole plates or baseplates on grout pads, with anchor bolts.
- **Markings:** yellow coupling guards with a caution label, and equipment tags. Identification colours stay close to the old ones: LPCS green, RHR teal, HPCS red, feed pump blue, SLC green.
- **Plates:** all text is generic. There are no maker names or logos.

## Compatibility

- **Old one-block saves:** these use the compact models (`compact.obj` and `compact.json` in each pump folder). Those files are untouched.
  - Each new source mesh lists the old compact model's materials first (`art/models/modern/legacy_compact_materials.json`), so `materials.mtl` keeps `mat_0..` as `compact.obj` expects.
  - The source carries `frozen_compact: true`, so `tools-export-modern.py` and `tools-export-alpha.py` no longer regenerate `compact.*` for these machines.
- **Held item:** uses a new scaled copy of the remodelled mesh, `inventory.obj`. Inventory icons were re-rendered with `art/models/inventory/render_icons.py`.
- **Legacy cell sets:** the non-modern cell sets (`pumps/<id>/cell_N`) that previously assembled saves use are not touched.
- **Collision boxes:** each cell's box follows the new mesh, as before.
- **Turbine feed pump and pipe fittings:** unchanged. The turbine feed pump and the 64 round pipe fittings are still exported from `modern_pumps_and_pipes.blend`, which this work does not open.

## Authoring

```powershell
python art/models/modern/make_motor_pump_texture.py          # 256 x 256 detail sheet (plates, louvres, guard mesh, gauge)
blender.exe --background --factory-startup --python art/models/modern/build_motor_pumps.py [-- --preview DIR]
python tools-export-modern.py; python tools-export-alpha.py     # then --check for each
```

`build_motor_pumps.py` writes the source meshes (`art/models/modern/<id>.json`, `art/models/alpha/slc_*.json`) and saves the scenes to `motor_pumps.blend`. Options:

- `--preview DIR` renders three views of each machine.
- `--before SRC --preview DIR` renders an older source JSON from the same three views, for comparison.

Source triangles:

| Machine | Old | New |
| --- | ---: | ---: |
| LPCS | 7,552 | 10,602 |
| HPCS | 8,306 | 15,504 |
| RHR | 8,318 | 14,744 |
| Motor feed | 8,318 | 9,894 |
| SLC pump | 3,492 | 8,374 |
| SLC tank | 4,940 | 5,672 |

These are block-model (chunk mesh) machines. They are not in the cached GPU renderer covered by [MACHINE-RENDERING.md](MACHINE-RENDERING.md). The client model check bakes every cell in all four rotations.

## REFERENCES

Viewed online; nothing was downloaded into the repository. Tags show how each source was checked:

- **[V]** the page was opened and the claim read there.
- **[S]** the claim was matched in search results, but the NRC site refused automated page fetches (HTTP 403) or the file was too large to open, so it was not opened. Open these in a browser before relying on them.

### ECCS pumps (RHR/LPCI, LPCS, HPCS)

- [V] Columbia Generating Station Technical Specification pages (TSTF-545 submittal, 2017): https://www.nrc.gov/docs/ML1718/ML17186A435.pdf
  - Test flows: LPCS ≥ 6200 gpm, LPCI ≥ 7200 gpm, HPCS ≥ 6350 gpm.
  - SLC pumps ≥ 41.2 gpm at ≥ 1220 psig.
- [S] Grand Gulf (BWR/6) pump data in an FSAR question response: https://www.nrc.gov/docs/ML2000/ML20009G882.pdf
  - Pumps: RHR 3-stage, 7620 gpm at 301 ft. LPCS 4-stage. HPCS 13-stage. All 1780 rpm.
  - Casings about 37 in. Long cans below the mounting flange.
- [V] INL/NRC High Pressure Core Spray system study: https://nrcoe.inl.gov/publicdocs/SystemStudies/hpcs-system-description.pdf
  - Single motor-driven centrifugal pump outside containment, suction from the CST or suppression pool. Minimum-flow and test lines.
- [V] NRC Reactor Concepts Manual, "Boiling Water Reactor Systems": https://www.nrc.gov/docs/ML1209/ML120970422.pdf
  - Low-pressure ECCS pumps take suction from the suppression pool.
  - SLC: a heated tank, two positive-displacement pumps and explosive valves.

### Vertical can pump anatomy

- [V] Discharge head patent ("O" head) with drawings: https://patents.google.com/patent/US8226352
  - Motor mounting plate on support members over the coupling and seal, elbow discharge nozzle, base plate.
- [V] Vertical turbine pumps, photograph (silhouette only, low resolution): https://commons.wikimedia.org/wiki/File:Vertical_turbine_pumps.jpg

### Motor-driven feedwater pump

- [V] Boiler feed pumps, including double-casing ("barrel type") designs: https://www.mhi.com/products/energy/pumps_for_thermal_power_plant_list.html
- [V] Boiler feed pump photograph: https://commons.wikimedia.org/wiki/File:Boler_Feed_Pump_Sulzer_Pumps.jpg
- [S] Fluid coupling for boiler feed pumps (a scoop tube varies speed): https://ebara.com/en/jihou/no/list/detail/252-5.html
- [S] BWR with motor-driven standby feed pumps beside the turbine-driven ones: https://patents.google.com/patent/US5610957

### Standby Liquid Control

- [S] NRC Information Notice 91-27, reciprocating positive-displacement SLC pumps and their drive train: https://ww2.nrc.gov/docs/ML0311/ML031190640.pdf
- [S] NRC Information Notice 91-12, SLC pump suction head; nearly all BWRs use similar positive-displacement pumps: https://www.nrc.gov/docs/ML0311/ML031190605.pdf

### Motors

- [V] Induction motor (TEFC finned frames, terminal box): https://en.wikipedia.org/wiki/Induction_motor

### Gaps

- **Motor horsepower:** no plant value was found for the ECCS pump motors. Motor sizes are proportioned from the pump data and typical 4160 V, 1780 rpm vertical motors.
- **SLC drive:** the triplex plunger with gear reducer is the usual form for these pumps, but no plant photograph was found to confirm the reducer.
- **Proportions:** these are game representations, not vendor drawings.
