# Main steam valve models: MSIV and safety relief valve

Two main steam valves were rebuilt in headless Blender to look like Columbia Generating Station's (BWR/5) hardware:

- `bwr:msiv`, the main steam isolation valve;
- `bwr:ads_relief_valve`, now named **Safety Relief Valve**. It is the mod's only relief valve.

The old plain cube, `bwr:safety_relief_valve`, is now named **Safety Relief Valve (old)**. It is no longer crafted or
shown in the creative tab. Placed cubes and stored items still load and work exactly as before. Its models were not changed.

Only the look changed. Each valve keeps all of these:

- the block ID;
- the footprint;
- the controller cell;
- the steam ports, on the same cells and faces;
- the pipe connection points, so existing pipes still meet the flanges.

Steam flow, power, division, redstone and CC behaviour are unchanged. The models do follow the valve now: each valve has
a shut model and an open model.

| Valve | Footprint | Steam ports | Shut | Open |
| --- | --- | --- | --- | --- |
| MSIV | 1 x 3 x 1, controller at the base | Base cell, north and south faces, flange centres (.5, .5, 0) and (.5, .5, 1) | `pumps/msiv/cell_0..2` | `pumps/msiv_open/cell_0..2` |
| Safety Relief Valve | 1 x 1 x 1 | Side inlet on the face counter-clockwise of `facing` (west for a north-facing valve) and the bottom face, at the face centres | `alpha/ads_relief_valve/body` | `alpha/ads_relief_valve/body_open` |

The MSIV shows open at any position above 0, so a stroking valve shows open. The relief valve shows open while it is commanded open.

## What the models show

### MSIV (FSAR 5.4.5.2 and Fig 5.4-9)

The Columbia valve is a 26 in Y-pattern globe valve, air to open, and closed by springs and air. In the model, from the bottom up:

- **Body.** A forged body runs between weld-neck hubs to the existing bolted steam flanges. It has a cast flow arrow (north to south), a drain boss and a generic nameplate.
- **Stem angle.** The inclined bonnet neck carries the stem axis. In the real valve the seat and stem sit at 45 degrees. A footprint one block deep allows only 7.4 degrees, leaning toward the inlet (north) as the real stem does.
- **Bonnet.** A bolted bonnet joint with 12 through-bolts, then the bonnet, the stuffing box, the gland follower and a plugged leak-off connection.
- **Actuator support.** An actuator support plate on legs carries four spring guide shafts. A helical closing spring on each shaft pushes down on the spring seat member, which is fixed to the stem through a split coupling.
- **Dashpot.** The hydraulic dashpot hangs under the cylinder plate. Its oil bypass has two speed control valves with red knobs.
- **Air cylinder.** Square heads, a round barrel, four tie rods and lifting eyes.
- **Accumulator.** Mounted on top of the cylinder (as in Fig 5.4-9), with saddles, a gauge, its outlet line and a check valve.
- **Control unit.** On the west side of the cylinder: a manifold with three solenoid pilot valves (open, close and the slow test exercise), a junction box, conduit and air lines to both ends of the cylinder.
- **Position indication.** On the east side, two position (limit) switches struck by a cam on the spring seat member, with their conduit. On the west side, a pointer on the spring seat member against a fixed OPEN/SHUT scale.

**Open:** the stem, spring seat member, cam and pointer rise by 0.24 block, and the springs are visibly shorter. **Shut:**
the pointer is in the green SHUT band.

### Safety relief valve (FSAR 5.2.2.4 and Fig 5.2-8)

Columbia's relief valves are **direct-acting spring-loaded dual-function safety/relief valves with an auxiliary actuating
device**: 6 in inlet and 10 in discharge (FSAR Fig 5.2-8). They are not two-stage pilot-operated valves. The owner chose to
follow Columbia. The model shows:

- **Body.** A cast body fed from the inlet flange, which is centred on the inlet face. The old model's flange sat at y 0.35, below the pipe.
- **Discharge.** The outlet nozzle leaves the body angled 45 degrees down. It passes a bolted 10 in flange joint, and the tailpipe then bends down to the bottom flange, toward the suppression pool. The tailpipe carries a thermocouple (tailpipe temperature indication).
- **Bonnet and top works.** A tall closed spring bonnet bolted to the body, the setpoint adjusting screw and the set pressure verification head with lifting eyes. The LVDT for valve position indication sits on top.
- **Actuator.** The lever pivots on a bracket off the bonnet top and lifts the spindle through a fork. A piston-type pneumatic actuator lifts the lever.
- **Air supply.** The solenoid/air control valve assemblies A, B and C are stacked on the actuator bracket. The valve's own accumulator has a gauge and an inlet check valve.
- **Nameplate.** Generic text only.

**Open:** the actuator rod is extended and the lever raised, a 0.035-block spindle lift at the fork.

### Materials, textures and size

- **Materials.** Flat colours through `minecraft:block/white_concrete` with a `Kd` colour, as in the other remodels:
  - forged and cast steel greys;
  - actuator grey;
  - black springs and solenoid coils;
  - machined stems and shafts;
  - brass fittings;
  - a grey-green accumulator;
  - a yellow pointer.
- **Textures.** Four small generated textures (`msv_*`, by `art/models/main_steam_valves/make_textures.py`): the two nameplates, the stem position scale and an air supply label. All text is generic. There are no maker names or logos.

| Model | Source triangles, old | Source triangles, new | Baked faces, old | Baked faces, new |
| --- | ---: | ---: | ---: | ---: |
| MSIV (each of shut and open) | 10,804 | 10,024 | 11,168 | 10,442 shut, 10,470 open |
| Safety Relief Valve (each of shut and open) | 2,484 | 4,924 | 2,484 | 4,924 |

Both valves are chunk-baked block models. The machine mesh cache in [MACHINE-RENDERING.md](MACHINE-RENDERING.md) does
not hold them, so they cost chunk-mesh vertices only when a section rebuilds. The MSIV is smaller than before. The relief
valve is about twice its old size, roughly half of one MSIV cell, for a one-block valve that a plant uses in banks
(Columbia has 18).

## Compatibility

- **Legacy MSIVs.** The one-block cube valves of old saves (`assembled=false`) keep `msiv_open` and `msiv_closed`. Those models were not touched.
- **MSIV upper cells.** The MSIV block entity now also sets `open` on the two upper cells, because they carry the stem. Nothing reads that flag there; it only selects the model. An old save's open valve picks it up on its next tick.
- **Old relief valve cube.** The old `safety_relief_valve` cube keeps its block, item, models, block entity and six steam faces.
- **Recipes that used the cube.** These now use what the cube's recipe was made of:
  - Safety Relief Valve: redstone, two pressurised tubes, a piston and a reactor vessel;
  - Division cabinet: two pistons instead of two old cubes.
- **IDs kept.** The registry IDs, the `bwr_ads` and `bwr_safety_relief_valve` CC types and the NBT keys are kept, so programs and worlds keep working.

## Rebuilding

```powershell
python art/models/main_steam_valves/make_textures.py
blender --background --factory-startup --python art/models/main_steam_valves/build_valves.py
python tools-export-msiv.py; python tools-export-alpha.py; python tools-export-modern.py   # modern copies the msv_* textures
blender --background --factory-startup --python art/models/main_steam_valves/render_icons.py
python tools-export-msiv.py --check; python tools-export-alpha.py --check; python tools-export-modern.py --check
```

- **What `build_valves.py` checks and writes.** It checks the MSIV ports, footprint and controller against the shipped manifest. It refuses geometry outside a footprint. It writes the shut and open source meshes and `main_steam_valves.blend`.
- **Old scripts.** Do not run `build_msiv()` in `art/models/condenser_msiv/build_models.py`, or the `ads_relief_valve` part of `art/models/alpha/build_models.py`: both would restore the old models.
- **Previews.** `render_previews.py` renders comparable Blender previews of any source JSON. The before and after sheets are in `art/models/main_steam_valves/previews/`.
- **In-game pictures.** These are taken with the pipes attached:

  ```powershell
  C:\Users\14238\Downloads\bwr-mod-handoff\run-mc.ps1 -Dir <worktree> -Log <log> -GradleArgs ':mod:runTurbineModelCheck','-PbwrSteamValveShots=<label>','--offline','--console=plain'
  ```

  This saves eight 1920 x 1080 pictures to `mod/run/turbineModelCheck/steam-valve-shots/<label>/`: four views of a shut and an open MSIV, and four of a shut and an open relief valve. The check fails if any port's first pipe does not join its flange, or if a pair is not in the state it is shown in.

## REFERENCES

Images and documents were viewed online or in the owner's local copy of the FSAR. Nothing was downloaded into the
repository. The models are original game-scale exteriors informed by these sources. They are not vendor drawings.

Tags show how each source was checked:

- **[F]** Columbia FSAR, Amendment 67 (NRC ADAMS ML23346A215). The local copy is `../references/columbia/Columbia FSAR.pdf`, outside the repository. Its text and figure labels were read.
- **[V]** The page or image was opened and looked at.
- **[S]** Search excerpt only.

Main steam isolation valve:

- [F] FSAR §5.4.5.2, PDF page 2170, covering the following:
  - two MSIVs welded in each of the four steam lines, inboard and outboard;
  - a 26 in Y-pattern globe valve with a 45 degree seat;
  - the poppet with its pilot, and the stuffing box;
  - an air cylinder and a hydraulic dashpot with speed adjustment (3 to 10 s);
  - an actuator support and spring guide shafts with helical springs;
  - a control unit with three types of control valve (open, close, slow exercise);
  - an accumulator and check valve, with plant air outboard and nitrogen inboard.
- [F] FSAR Fig 5.4-9 "Main Steam Line Isolation Valve", PDF page 2234. The labels read from it: accumulator (top), air cylinder (closing piston inside), MSIV speed control valves, hydraulic dash pot, actuator support and spring guide shaft, closing spring, spring seat member, stem, stem packing, leak-off connection (plugged), bonnet bolts, bonnet, main disc, pilot, balancing orifice, main valve seat, body.
- [V] US patent 4,770,029, valve testing method and device. Its Fig. 2 shows a BWR MSIV with a pneumatic cylinder, dashpot, spring loading, spring seat and frame. Page: https://patents.google.com/patent/US4770029, drawing: https://patentimages.storage.googleapis.com/f1/d5/f5/df7b99df67ff80/US4770029-drawings-page-2.png (only a small thumbnail could be opened).
- [V] Trillium Flow, on BWR MSIVs: air-to-open with air and/or spring to close, an oil dashpot with adjustable metering valves, and a "smaller upper structure with more springs" than the PWR valve. https://www.trilliumflow.com/tf-news/world-leader-in-design-manufacture-and-service-support-for-bwr-and-pwr-msivs/
- [S] NRC Information Notice 86-81, broken inner and outer closure springs on MSIVs. Source of the nested-spring detail; one spring per shaft is modelled. https://www.nrc.gov/documents-reports/generic-communications/information-notices/1986/in86081s1
- [S] NRC document on spring-loaded, pneumatically operated MSIVs that close on loss of gas or of power to the solenoid pilot valves (the inboard valve needs gas and spring under accident pressure). https://www.nrc.gov/docs/ML1705/ML17055C913.pdf

Safety relief valve:

- [F] FSAR §5.2.2.4 and §5.2.2.5, PDF pages 2063-2067:
  - the spring mode and the power-actuated mode (a pneumatic piston and cylinder with mechanical linkage);
  - a bellows and balancing piston;
  - each SRV's own accumulator and inlet check valve, and a solenoid valve per valve;
  - valve position indication and tailpipe temperature indication;
  - the discharge line to below the suppression pool water level;
  - mounting on the steam line on a special contour nozzle with an oversized flange (§5.2.2.5).
- [F] FSAR Fig 5.2-8, "Schematic of Safety Valve with Auxiliary Actuating Device", PDF page 2116. The labels read from it: valve position indication (VPI) LVDT, lever, piston-type pneumatic actuator assembly, set pressure verification device pneumatic head, setpoint adjust screw, bonnet, spring, spindle, solenoid/air control valve assemblies A, B and C, balancing piston, bellows, discharge, nozzle, body, steam flow inlet.
- [V] The same schematic (NRC source), reproduced by the Union of Concerned Scientists. It shows the tall closed bonnet, the lever over its top, the side actuator and the stacked A/B/C valves. Image: https://blog.ucsusa.org/wp-content/uploads/2022/10/DBD-06-Figure-2-bwr-srv-3.jpg, from https://blog.ucs.org/dlochbaum/when-safety-relief-valves-fail-to-provide-safety-or-relief-at-nuclear-plants/
- [S] NRC Information Notice 83-22, on two-stage Target Rock 7567F SRVs at other plants. This is the design the brief named, which Columbia does not use. https://www.nrc.gov/documents-reports/generic-communications/information-notices/1983/in83022
- [S] NRC IE Circular 79-18, on the Target Rock pilot, second stage and pneumatic actuator. https://www.nrc.gov/documents-reports/generic-communications/circulars/1979/cr79018

### Gaps

- **Photographs.** No usable photograph of a Columbia MSIV or relief valve was found online. The shapes follow the two FSAR figures, the patent drawing and the schematic. Colours are typical plant greys, not Columbia's.
- **MSIV stem angle.** It is compressed from 45 to 7.4 degrees by the one-block depth.
- **Relief valve proportions.** The real valve is about two blocks tall; here it is one block.
