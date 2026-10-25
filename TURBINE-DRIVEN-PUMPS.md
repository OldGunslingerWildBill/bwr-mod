# Turbine-driven pump models

The three steam-turbine-driven pumps have new exterior models:

- `bwr:turbine_feed_pump`: the reactor feed pump turbine (RFPT) and its barrel feed pump.
- `bwr:rcic_twl`: the RCIC Terry turbine and pump skid.
- `bwr:hpci_turbine`: the HPCI Terry turbine and pump skid.

Only the appearance changed. Each machine keeps all of these:

- the same block ID and footprint;
- the same controller cell;
- the same four process ports, at the same cells and faces;
- the same flange centres. Existing pipes still meet the flanges exactly.

The legacy layouts used by old saves are untouched. These include:

- the `modern=false` Terry cells (`models/block/turbines/...`);
- the non-modern `turbine_feed_pump` cells;
- the compact migration models;
- the retired `hpci_turbine_pump` and `rcic_turbine_pump` single blocks.

These are chunk-baked block models. They have no block entity renderer and no animation. Neither the old models nor the new ones had a spinning shaft.

## What the models show

### Feed pump turbine (9 x 3 x 3)

The skid is a fabricated baseplate with a drip rim and anchor bolts. On it, from west to east:

- **Barrel feed pump.** It has a horizontal outer barrel and a heavy bolted discharge cover with 16 studs and nuts. Both ends have bearing housings with split caps, and the drive end has a mechanical seal housing. The barrel sits on centreline support feet. Conical suction and discharge nozzles rise to the existing top flanges. Each nozzle has a pressure gauge, and there is a top vent valve, a balance leak-off line and a nameplate.
- **Coupling.** The coupling is yellow, with a guard that carries warning labels on both sides.
- **Condensing turbine.** The casing is horizontally split, with a bolted joint flange running its full length. The exhaust hood has stiffener ribs, an inspection cover and lifting lugs. The exhaust leaves through a side nozzle on the south face, with a reinforcing pad, gussets and a bolted joint, to the existing exhaust flange.
- **HP end and front standard.** The HP casing tapers down to the front standard. The front standard carries a red hand-trip knob, a speed pickup and an oil-pressure gauge.
- **Steam admission.** All of it sits above the casing. Steam comes down from the existing inlet flange through an insulated elbow with jacket bands. It then passes the main stop valve, which has a horizontal hydraulic actuator, a spring can, a limit-switch box and oil tubing. Next comes the control-valve steam chest, with steam leads down into the casing. The control valves use a bar lift: posts, a crossbar and valve stems, worked by a hydraulic servomotor with its lever.
- **Lube oil console (south side).** It has a reservoir, two motor-driven oil pumps, a duplex filter with a transfer handle, an oil cooler on saddles and a gauge. An oil supply header runs along the north side, with risers to all four bearings.
- **Local gauge board (north side).** It has three gauges with impulse tubing.

### RCIC Terry skid (5 x 4 x 5)

- **Terry turbine.** It is a single-wheel turbine with a horizontally split drum casing and bolted joint lips. Both side covers carry a bolt circle of 16, the look in the Terry reference photographs. The casing also has bearing bosses, a lifting lug, a support pedestal with bolted feet, and a nameplate.
- **Bearing housings.** They have split caps, oil-level sight glasses and temperature elements.
- **Governor end.** It has a mechanical overspeed trip with a red tappet lever and reset knob. The hydraulic governor actuator sits on the governor drive and carries oil tubing.
- **Steam admission.** Steam enters at the existing west inlet flange. It passes the trip and throttle valve, which has a bonnet, yoke, stem, red handwheel and red trip latch. It then passes the governor valve, which has an opening spring, a lever and a linkage rod to the actuator. An insulated elbow carries it down into a bolted steam chest with a gauge. A pipe stanchion supports the valve train.
- **Exhaust.** An insulated line runs north. On the way it has a swing check valve with a bolted hinge cover, a rupture-diaphragm branch with two holder flanges and a drain. A reducer brings it down to the existing exhaust flange.
- **Pump.** The yellow coupling and guard lead to a horizontal multistage ring-section pump. The pump has these parts:
  - stage casings and tie rods;
  - bolted casing flanges and centreline feet;
  - a nameplate.

  Its discharge is a tangential nozzle that runs south to the existing flange, with an orifice flange pair, a gauge and a minimum-flow bypass valve. Its suction is an end boss with the nozzle at the existing east flange, plus a gauge.
- **Lube oil cooler.** It is cooled from the pump discharge and has oil lines to both bearings and an oil filter.
- **Barometric condenser.** The vessel has dished heads, a gauge and a level glass. It comes with a vacuum pump, a condensate pump and gland leak-off piping.
- **Local control panel.** It sits at the north face of local cell (3,2,0), where the CC:Tweaked note in [TERRY-TURBINES.md](TERRY-TURBINES.md) says to attach a modem. It has a mimic diagram, lamp pairs and selector switches, with a three-gauge board below.

### HPCI Terry skid (7 x 5 x 5)

The HPCI skid is laid out like the RCIC skid, but it is larger and uses BWR/3-4 HPCI details:

- **Turbine casing.** It is insulated, with an aluminium jacket and bands. The side covers and joint are bolted steel.
- **Stop valve.** It is hydraulically operated, with no handwheel. It has a hydraulic cylinder, a spring housing, a limit switch and oil tubing.
- **Governor valve.** It has an opening spring and a linkage to the hydraulic governor actuator on the turbine end.
- **Exhaust line.** The exhaust line is larger. It has a check valve, a rupture-diaphragm branch and a drain pot.
- **Pump.** The main pump has a booster-style suction casing on the same shaft. It has the same tangential discharge and end suction as the RCIC pump, at the existing HPCI ports.
- **Gland seal condenser.** It is fed from both turbine glands and comes with a gland exhauster blower and motor.
- **Lube oil console (north-east).** It has a reservoir, a cooler, a duplex filter and an auxiliary oil pump with its motor. Risers run to all four bearings.
- **Valve-access platform.** It stands over the gland condenser, with grating, posts, handrails, toe plates and a ladder. The HPCI room photographs show the same thing.
- **Local control panel.** It is at the north face of local cell (4,2,0).

### Materials and textures

Most surfaces are flat colours, through `minecraft:block/white_concrete` with an `Kd` colour, as before:

- painted casings: feed-turbine grey, feed-pump blue-grey, Terry grey and ECCS green;
- dark baseplates;
- aluminium insulation jackets with darker bands;
- dark fasteners;
- a yellow coupling guard;
- red handwheels and trip levers;
- ochre lube-oil lines.

Nine small generated textures add detail: gauge dials, nameplates, panel faces and a guard warning label. They are `assets/bwr/textures/block/modern/tdp_*.png`, made by `art/models/turbine_pumps/make_textures.py`. All text is generic, such as "FEED PUMP TURBINE", "RCIC TURBINE" and "INJECTION PUMP". There are no vendor names or logos.

Columbia Generating Station (BWR/5) is the reference plant. It has RCIC and a motor-driven HPCS, but no HPCI. The HPCI details therefore come from BWR/3-4 references.

### Size

| Model | Source triangles, old | Source triangles, new | Cells |
| --- | ---: | ---: | ---: |
| Feed pump turbine | 8,580 | 16,342 | 81 |
| RCIC skid | 19,832 | 22,148 | 100 |
| HPCI skid | 22,480 | 24,932 | 175 |

These machines are ordinary chunk-baked block models. The machine mesh cache in [MACHINE-RENDERING.md](MACHINE-RENDERING.md) does not hold them. They cost chunk-mesh vertices only when a chunk section rebuilds. Hidden stud ends and the seated ends of fasteners are not modelled. Segment counts were reduced until the RCIC and HPCI skids were within about 10 % of the old models.

## Rebuilding

```powershell
python art/models/turbine_pumps/make_textures.py
blender --background --factory-startup --python art/models/turbine_pumps/build_models.py
python tools-export-modern.py        # feed pump turbine cells, manifest and the tdp_* textures
python tools-export-terry.py         # RCIC and HPCI cells and manifests
blender --background --factory-startup --python art/models/turbine_pumps/render_icons.py
python tools-export-modern.py --check
python tools-export-terry.py --check
```

`build_models.py` checks the ports, footprint and controller cell against the shipped `data/bwr/pump_models/*.json` before it writes anything. It also refuses any geometry outside the footprint. It writes these files:

- `art/models/modern/turbine_feed_pump.json`;
- `art/models/terry/{rcic_twl,hpci_turbine}.json`;
- the editable `art/models/turbine_pumps/turbine_pumps.blend`.

Do not regenerate `turbine_feed_pump` with `art/models/modern/build_models.py`, because that script would restore the old model.

`render_previews.py` makes Blender studio previews of any exported JSON. To make comparable before and after pictures, pass it an old JSON and a new one:

```
blender --background --factory-startup --python art/models/turbine_pumps/render_previews.py -- OUT LABEL a.json ...
```

In-game pictures with all four ports piped:

```powershell
C:\Users\14238\Downloads\bwr-mod-handoff\run-mc.ps1 -Dir <worktree> -Log <log> -GradleArgs ':mod:runTurbineModelCheck','-PbwrTurbinePumpShots=<label>','--offline','--console=plain'
```

This saves nine pictures to `mod/run/turbineModelCheck/turbine-pump-shots/<label>/`, three angles for each machine. The check fails if the first pipe on any port does not join its flange.

## REFERENCES

Images and drawings were viewed online. Nothing was downloaded or redistributed. The models are original game-scale exteriors informed by these sources. They are not vendor CAD.

Feed pump turbine and barrel feed pump:

- Feed pump turbine arrangement (stop valve, control valve chest and lift gear, casing, exhaust hood, baseplate): <https://www.toshiba-tjps.in/images/bftp-turbine.jpg>, from <https://www.toshiba-tjps.in/boiler-feed-pump-turbine.aspx>.
- Barrel feed pump with a bolted end cover, top nozzles and centreline pedestals: <https://www.powermag.com/wp-content/uploads/2020/01/ksb_chtd11-boiler-feed-pump.jpg>, from <https://www.powermag.com/press-releases/the-worlds-most-powerful-boiler-feed-pump-dispatched/>.
- Barrel pump on centreline supports, with its bearing end: <https://www.worldpumps.com/content/news/case-study-two-shaft-turbine-feed-pumps-for-nuclear-power-station-and-thermal-power-station>.
- Turbine, coupling and feed pump train photograph: <https://www.researchgate.net/figure/Internal-structure-of-the-steam-turbine-feed-pump_fig5_314859247>.
- NRC HRTD BWR systems manual, chapter 2.0: main steam supplies the reactor feed pump turbines. <https://www.nrc.gov/docs/ML1127/ML11271A021.pdf>.

Terry turbines, RCIC and HPCI:

- Terry turbine on a test skid, showing the split casing, bolted covers, governor valve and trip and throttle valve with handwheel: <https://turboreliability.engr.tamu.edu/research/terry-turbine/>. The image is <https://turboreliability.engr.tamu.edu/wp-content/uploads/sites/225/2019/02/Joshuas-test-rig.jpg>.
- RCIC Terry turbine training aid, showing oil piping, the governor valve actuator and the exhaust flange: <https://www.genassis.com/rcic2.jpg>, from <https://www.genassis.com/trngaids.htm>.
- RCIC turbine and pump skid photograph (local control box, gauges, casing bolting): <https://brainmindinst.blogspot.com/2011/07/fukushima-failure-by-design.html>.
- HPCI room photograph (insulated turbine and steam lines, green pumps, handrails) and system schematic: <https://simplyinfo.org/hpci-high-pressure-coolant-injection/>.
- RCIC schematic and the role of the trip and throttle valve: <https://blog.ucs.org/dlochbaum/rcic-look-see/>.
- Terry turbine research (LWRS expanded operating band): <https://lwrs.inl.gov/terry-turbine-expanded-operating-band/>.
- NRC Information Notice 86-14 S1, on Terry turbines with Woodward governors, the EG-R actuator, the governor valve and the overspeed trip: <https://www.nrc.gov/reading-rm/doc-collections/gen-comm/info-notices/1986/in86014s1.html>.
- NRC IE Circular 78-14, on the HPCI Terry turbine and its reversing-chamber bolting: <https://www.nrc.gov/documents-reports/generic-communications/circulars/1978/cr78014>.
- NRC RCIC training chapter 2.7, covering the trip and throttle valve, governor valve, EG-R, barometric condenser and oil system: <https://www.nrc.gov/docs/ML1125/ML11258A312.pdf>. The direct fetch was refused (HTTP 403), so it was read through search excerpts only.
- Columbia Generating Station (BWR/5): RCIC with steam-driven feed pumps, and HPCS rather than HPCI. <https://blog.ucs.org/dlochbaum/columbia-generating-station-nrcs-special-inspection-of-self-inflicted-safety-woes/>.

The earlier references in [TERRY-TURBINES.md](TERRY-TURBINES.md#references) still apply.
