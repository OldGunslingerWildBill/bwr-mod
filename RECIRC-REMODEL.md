# Recirculation equipment remodel: references and design

This remodel replaces the look of the external recirculation pump (`bwr:recirculation_pump`, 5 x 10 x 5), the paired jet
pump (`bwr:jet_pump`, 1 x 6 x 1) and the reactor internal pump (`bwr:rip_pump`, 2 x 5 x 2). The earlier versions were the
"DVSS" exterior and the supplied pack meshes. Only the look changes. The block IDs, footprints, controller cells, port cells,
outward faces and flange centres are those in [PUMP-MODELS.md](PUMP-MODELS.md). The exporter asserts them against the
manifests, so pipes connect exactly where they did. Nothing about flow, power or behaviour changes.

## 1. What the real equipment looks like

### External recirculation pump (BWR/4-6, Columbia BWR/5)

- **Pump.** A variable-speed, single-stage, vertically mounted centrifugal pump in each loop. The suction and discharge
  lines are welded to the casing. It sits low, below the vessel, for NPSH. (NRC HRTD 2.4.3.3; FSAR 5.4.1.3.)
- **Size.** Columbia: 24 in suction and discharge, about 47,200 gpm. The motor is about 8,900 hp at about 1,780 rpm.
  (FSAR Table 5.4-2.)
- **Seal.** A two-stage mechanical seal cartridge. Each seal takes half the pressure and either can hold full pressure. Both
  can be replaced without removing the motor. A breakdown bushing sits below them. The cavity pressure and temperature of
  each stage are monitored, and the seals are cooled by closed cooling water. (FSAR 5.4.1.3; HRTD 2.4.3.3 and Fig 2.4-6.)
- **Coupling.** Changing the seal without lifting the motor needs a removable spacer between the pump and motor shafts.
  That means an open motor stand with access windows and a coupling guard.
- **Motor.** A vertical, solid-shaft, totally enclosed, air-water-cooled induction motor (TEWAC). The air coolers are boxes
  on the frame, fed with cooling water. The windings and bearing oil are cooled by that water. The motor has a vibration
  sensor and bearing-oil level alarms. There is no flywheel. (FSAR 5.4.1.3; HRTD 2.4.3.3.)
- **Supports.** Snubbers at the top of the motor and at the bottom of the casing, and hangers rather than anchors.
  (FSAR 5.4.1.3.)
- **Loop.** Two motor-operated gate valves (suction and discharge) and a flow element: elbow taps in the pump suction
  line. (FSAR 5.4.1.3, Table 5.4-2; HRTD Fig 2.4-1, Fig 5.4-1 pictorial view.)

### Jet pump (BWR/5 five-nozzle type)

Each pair of jet pumps has the following parts, from FSAR Fig 3.9-4, HRTD Fig 2.4-4 and patent US6633623:

- **Riser.** It rises from a thermal-sleeve inlet elbow at the vessel wall.
- **Transition piece.** It sits on the riser. The flow splits there and turns down through two elbows into the five-hole
  drive nozzles.
- **Inlet mixers.** There are two, each with a suction bell around the nozzle and a long straight throat. They are removable
  and clamped at the transition piece.
- **Slip joints.** Each mixer meets a long tapered diffuser at a slip joint with a clamp.
- **Holding parts.** A hold-down beam (the "hold down clamp") with its bolt and two lifting eyes, restrainer brackets on the
  mixers, and a riser brace to the vessel wall.

### Reactor internal pump (ABWR)

This follows the sources already cited in [INTERNAL-PUMPS.md](INTERNAL-PUMPS.md). Inside the vessel there is a wet end: the
impeller over a diffuser on a stretch tube through the bottom head. Below the head a wet-motor casing hangs from the
bottom-head nozzle. It has a bolted motor cover at the bottom, cooling-water nozzles to the RIP heat exchanger, a purge line
and the power cable entry.

## 2. What the models show

**External recirculation pump.** From the bottom up:

- **Suction.** A long-radius suction elbow from the rear flange, with two elbow flow taps and root valves.
- **Casing.** A cast stainless bowl on four lugs, with columns and base plates. Two lateral snubbers sit at the bottom of the
  casing.
- **Discharge.** The front discharge nozzle.
- **Cover closure.** A big bolted cover closure: 20 studs with nuts above and below, and a gasket line.
- **Seal.** The seal cartridge housing on the cover, with its stage pressure lines. Stainless tubing runs to two local racks,
  each with two gauges.
- **Motor stand.** A painted stand with front and back access windows behind yellow expanded-metal coupling guards. The guard
  has real holes, so the cells render as cutout. Through it you see the shaft and the three-piece spacer coupling.
- **Motor.** A ribbed frame (the cooling fins) with an upper bearing housing and oil pot. It carries:
  - two air-water coolers with louvered faces, water boxes and cooling-water nozzles;
  - a terminal box with a conduit;
  - lifting lugs, a vibration probe, an oil sight glass and a rotation arrow.
- **Nameplates.** Generic only: "RECIRC PUMP RRC-P-1A" and "INDUCTION MOTOR 8900 HP".
- **Port bands.** Blue and teal bands mark the suction and discharge as before.

The loop gate valves are not part of the pump model. A valve drawn on the pump would look operable, and the plant already
has a separate water valve block.

**Jet pump.** Two diffusers with weld bands, bottom collars and slip-joint clamps, and two inlet mixers with suction bells and
nozzle bodies. The riser rises from a thermal-sleeve elbow, which points south: model +z, the vessel wall side. With an
east-facing jet at the west wall it points west, into the wall. Then come the transition piece and the two elbows down into
the nozzles, the hold-down beam with bolt, nut and lifting eyes, the restrainer brackets and wedges, the riser brace to the
wall, and the diffuser sensing lines. Everything is bare stainless. When the reactor forms, the installed jets are still drawn
by the reactor renderer from `art/models/reactor_vessel/installed_jet` (unchanged).

**RIP.** The model has these parts:

- the wet end: a diffuser barrel with external ribs, an inlet bell, and an impeller hub with vanes;
- the stretch-tube nozzle stub;
- a 16-bolt mounting flange at the bottom-shell level (layer 3, as before);
- the motor casing with its upper flange and reinforcing rings;
- a bolted motor cover with nuts and a cable connector;
- two cooling-water nozzles, a purge line and a nameplate.

Colours: painted machinery green-grey (motor and stand), cast and brushed stainless (pump, jets, RIP), machined steel (flanges
and coupling), zinc nuts, safety-yellow guard, green cooling water, black cable.

## 3. Pipeline and budgets

| Step | File |
| --- | --- |
| Texture atlas (procedural, Pillow, seeded) | `art/models/recirc/make_atlas.py` writes `textures/block/pumps/recirc_atlas.png` (256 x 256, 32 tiles) |
| Geometry (headless Blender, editable objects with UVs) | `art/models/recirc/build_recirc.py` writes `recirc_remodel.blend` and `<id>.json` |
| Cell bake (UV-preserving clip, budgets, port faces) | `python tools-export-recirc.py [--check]` |
| Review renders of the baked cells | `art/models/recirc/preview_cells.py` (`before/`, `after/`) |
| In-game pictures | `:mod:runRecircRemodelCheck` through run-mc.ps1 (`RecircRemodelClientCheck`) |

Each new mesh faces into one atlas tile. The exporter keeps `compact.obj`, `assembled.obj` and `materials.mtl` in these folders
and the `recirculation_pump_legacy` and `jet_pump_legacy` folders as they were; old saves use them. `tools-export-dvss.py`
and `tools-narrow-jet.py` still generate and `--check` those files. The new cells use their own `recirc.mtl`. The item
models now use a scaled `inventory.obj` of the new mesh. Saved one-cell `assembled=false` blocks keep their compact model.

Baked faces against the replaced models. The cell bake asserts that no model exceeds its old size.

| Machine | Before | After |
| --- | ---: | ---: |
| Recirculation pump | 13,612 | 8,199 |
| Jet pump pair | 14,242 | 1,648 |
| RIP | 4,120 | 1,933 |

Collision: the bounding boxes of the closed bodies (casing, seal housing, motor, coolers and the RIP casing and wet end), plus
the clipped faces in each cell.

## 4. REFERENCES

Viewed online or in the local FSAR copy. Nothing is bundled or redistributed, and no maker's artwork, name or logo is used.

- **Columbia Generating Station FSAR, Amendment 67** (NRC ADAMS accession ML23346A215). The local copy is
  `../references/columbia/Columbia FSAR.pdf`, outside the repository. Page renders are in `../references/recirc/` and
  `../references/rpv/`. Pages used:
  - §5.4.1.3, PDF pages 2161-2164: the pump, two-stage seal cartridge, breakdown bushing, TEWAC motor, no flywheel,
    snubbers and gate valves.
  - Table 5.4-2, page 2218: sizes, flow, motor rating, the flow element (elbow taps) and the jet-pump nozzle count.
  - Fig 5.4-1, page 2225: the recirculation system pictorial view.
  - Fig 5.4-5, page 2229: the jet pump operating principle.
  - Fig 3.9-4, page 1516: the BWR/5 jet pump with hold-down clamp, drive nozzle, riser, throat, restrainer bracket, slip
    joint clamp, diffuser and riser inlet.
- **NRC HRTD, GE BWR/4 Technology Manual, Chapter 2.4 Recirculation System.** It covers the pump, seal, motor cooling and
  discharge piping, with Fig 2.4-1 (system), Fig 2.4-4 (jet pump) and Fig 2.4-6 (seal assembly):
  https://www.nrc.gov/docs/ML1125/ML11258A306.pdf
- **US patent US6633623B2**, on the jet pump nozzle assembly and inlet-mixer. It covers the riser, transition piece, nozzle,
  removable inlet-mixer, slip joint and diffuser: https://patents.google.com/patent/US6633623
- **GE BWR/6 General Description** (jet pumps in the downcomer; already cited in JET-PUMP-INSTALLATION.md):
  https://corephysics.com/docs/BWR6GeneralDescription.pdf
- **ABWR RIP arrangement**, as cited in INTERNAL-PUMPS.md:
  - https://www.nrc.gov/reading-rm/doc-collections/acrs/tr/subcommittee/2007/ab120507.pdf
  - https://www.hitachi-hgne-uk-abwr.co.uk/downloads/2016-05_abwr-brochure.pdf

Proportions are a game-scale approximation (about 0.9 m per block), not manufacturer drawings. The plate data (flow, horse
power, voltage) are rounded Columbia-class figures on generic plates.

## 5. The vessel's recirculation nozzles

The recirculation outlet and inlet port blocks (`bwr:recirculation_outlet`, `bwr:recirculation_inlet`) used to be a shell
cube with a flat square decal. On the formed v2 vessel they got the same thin spool, generic forging, safe end and water
nozzle as every water port. Now both have their own nozzles.

Only the look changes. The ids, the cells, the faces that must point outward, the loop rules and the flange centres are as
before. On the formed vessel the flange face is where the old water nozzle's face was, so a pipe joins exactly as it did.

### What the real nozzles look like (Columbia, BWR/5)

- **Outlet.** The outlet nozzles (2 x 24 in, 172.5 in above vessel zero) are big, short forgings low on the shell. They blend
  into the plate with a large radius. (FSAR Fig 5.3-3, Fig 5.3-5.)
- **Inlet.** The inlet (riser) nozzles (10 x 12 in, 181 in) are slimmer and have **thermal sleeves**. The jet-pump riser is
  welded to that sleeve inside the vessel. (§5.3.3.1.4.5, §3.9.5.)
- **Nozzle detail.** The detail drawing in the FSAR is the feedwater nozzle (Fig 5.3-6). It has the same family of parts:
  - the low-alloy forging, with a fillet radius off the shell and a conical taper;
  - an Inconel overlay (buttering) where the safe end is welded on;
  - the thermal sleeve and its extension welded at the safe end;
  - the safe end tapering down to the pipe weld.
- **Safe ends.** The safe ends are stainless. They were welded on after the vessel's heat treatment, to avoid sensitizing
  them. Columbia replaced its recirculation inlet safe ends in type 316L. (§5.3.3.1.4.5, §5.2 text on IGSCC; Table 5.2-7
  materials.)
- **Weld overlays.** Cracked stainless weldments in BWR recirculation piping are commonly reinforced with a weld overlay.
  GL 88-01 accepts that repair. The outlet model shows one over its safe-end weld.

### What the models show (`art/models/reactor_vessel_v2/build_recirc_nozzles.py`)

- **Outlet** (`VesselModel.recircNozzle`):
  - a broad boss flaring to 1.8 times the forging radius, set radially into the shell, in the shell's colour;
  - the Inconel butter band with weld ripples;
  - the stainless safe end, straight and then tapered;
  - the safe-end weld under a structural weld overlay;
  - the stainless pipe, bent in a smooth curve from the nozzle's radial line onto the port face;
  - the flange at the face: a weld-neck hub, a raised face, a gasket, 12 nuts and a blue band (it feeds the pump's blue
    suction).
- **Inlet.** The fillet-and-taper forging (Fig 5.3-6), butter, safe end, weld, the bent pipe and a teal band (fed from the
  pump's teal discharge). Inside the vessel its thermal sleeve stands in from the inner wall.
- **How the sizes work.** The forging radii are 0.56 and 0.42 blocks, the same on every vessel, because the game pipe is one
  block whatever the vessel's size. Where the shell is close to the face (the centre column of a wall) the pieces shorten in
  proportion, and they never cross the face.
- **Wrong facing.** A wrongly faced or top/bottom recirculation port keeps the generic kit, or shows as a plain block, as
  before.
- **Unformed blocks** (`port_blocks/`, exported to `models/block/recirculation_port/` by `tools-export-reactor-vessel.py`):
  - the vessel shell cube with a counterbore in its face;
  - the nozzle set flush into it: forging, butter, safe end and a bolted flange (12 studs on the outlet, 8 on the inlet);
  - the port's colour band.
  Nothing sticks out of the block, so a pipe joins as before.

**The vessel tells the client the kind.** `VesselAppearance.PortKind` gains `RECIRC_OUTLET` and `RECIRC_INLET` at the end,
so `WATER` (2) and `STEAM` (3) keep their wire codes. The envelope is sent on the controller's update tag and not saved.
`PortKind.isWater()` keeps both treated as water interfaces, and the outward-facing check applies to them as to water
ports. GameTest `RecircNozzleTests` checks the typed kinds, the wrongly faced port and the old codes.

**Triangles per nozzle.**

| Nozzle | Triangles |
| --- | ---: |
| Old generic water kit | 2,124 |
| Outlet, with an 8-piece bend | 3,060 |
| Inlet, with an 8-piece bend and its sleeve | 3,124 |

A vessel with 2 outlets and 10 inlets adds about 12,000 triangles to its exterior mesh (about 39,000), once, in the cached
mesh.

**Pictures.**
- Blender: `preview_recirc_nozzles.py`, before and after, in `art/models/reactor_vessel_v2/recirc_review/`.
- In-game: `-PbwrTankNozzleShots=<label>` (TankNozzleShots), with copies in `art/cst-recirc-remodel/`:
  - four inlets and a low outlet on the north wall of a formed 17 x 17 x 28 vessel, plus an outlet in the upper half where
    the loop rule wants one, all piped;
  - a loose wall with the unformed blocks.

### REFERENCES (nozzles)

- **Columbia Generating Station FSAR, Amendment 67** (ML23346A215), local copy `../references/columbia/Columbia FSAR.pdf`.
  Pages (1-based PDF):
  - Fig 5.3-5, page 2158: the vessel cutaway with the recirculating water outlet and inlet nozzles;
  - Fig 5.3-6, page 2159: the feedwater nozzle detail (nozzle SA-508, Inconel safe end, thermal sleeve and extension, Inconel
    overlay, fillet radii, taper);
  - Fig 5.3-3, page 2156: the nozzle elevations;
  - §5.3.3.1.4.5, pages 2131-2132: which nozzles have thermal sleeves, and the stainless safe ends welded after heat treatment;
  - §5.2 text, page 2077: the recirculation inlet safe ends replaced in 316L;
  - Table 5.2-7, page 2101: the nozzle and safe-end materials;
  - §3.9.5, page 1406: the riser welded to the inlet nozzle's thermal sleeve;
  - Table 3.9-2a, page 1427: the usage factor at the thermal sleeve to safe end.
- **NRC Generic Letter 88-01**, IGSCC in BWR austenitic stainless steel piping (viewed): cracked weldments reinforced with a
  weld overlay are acceptable. Scope: all BWR austenitic stainless piping of 4 in and larger.
  https://www.nrc.gov/reading-rm/doc-collections/gen-comm/gen-letters/1988/gl88001.html
- [S] Seen in search results only. The PDFs are scanned images, so their text could not be read:
  - Susquehanna relief request for a full structural weld overlay on a recirculation outlet nozzle (stainless safe end on a
    low-alloy nozzle, Alloy 182 buttering): https://www.nrc.gov/docs/ML0409/ML040960442.pdf
  - an NRC presentation with recirculation nozzle overlays: https://www.nrc.gov/docs/ML1621/ML16210A491.pdf

