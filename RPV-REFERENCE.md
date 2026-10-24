# Reactor vessel remodel: reference data and phase-1 concept

Status: **phase 1 only (research and concept)**. Nothing here is in the game yet. The concept is
`art/models/reactor_vessel_v2/build_concept.py`, a headless Blender script. The owner reviews it before phase 2.

## 1. Sources

### Local: Columbia Generating Station FSAR (BWR/5, 251-764)

The source is the Columbia Generating Station FSAR, Amendment 67, NRC ADAMS ML23346A215. The local copy is
`../references/columbia/Columbia FSAR.pdf`. It is outside the repository, and the PDF is not committed (see
`.gitignore`). Page numbers are 1-based PDF pages. The page renders are in `../references/rpv/fsar-page-NNNN.png`,
outside the repository, alongside the existing Columbia renders.

| Item | PDF page | What it gives |
| --- | ---: | --- |
| Table 1.3-1 | 126-131 | ID 20 ft 11 in (251 in); inside height 72 ft 11 in (875 in); minimum base metal 6.75 in; cladding 1/8 in; 4 steam lines of 26 in; 2 recirculation loops; 20 jet pumps |
| §3.9.5 | 1403-1408 | Internals list; shroud regions; shroud head and separators; jet pumps (one riser per pair); dryer skirt below water level; dryer supported on wall brackets and held down from the head; feedwater spargers (one per nozzle); core spray lines and spargers; head spray nozzle on a bolted flange; 3 LPCI nozzles discharging inside the shroud |
| Fig 3.9-2 | 1514 | Flow paths: jet pump in the annulus, shroud support plate at the bottom-head junction, recirculation nozzles at that level |
| §4.1.2 | 1922, 1926-1927 | Removable internals; shroud flange mates with the shroud head; long shroud-head bolts reach above the separators; guide rods for the dryer |
| Fig 4.1-1 | 1939 | Steam separator: standpipe, turning vanes, barrel, returning-water annuli, water level on the barrel |
| Fig 4.1-2 | 1940 | Steam dryer panel: perforated plates, vane banks, the steam nozzle beside them, the separator grid below |
| Fig 4.3-1 | 1971 | Quarter-core plan: vessel, shroud, jet-pump risers and mixers, surveillance capsule |
| Fig 5.2-5 | 2113 (crop `fsar-page-2113-elevation-chart.png`) | Elevation chart: top of head flange 898 in max; steam nozzle N3 648 in; instrument nozzles N14 599, N13 517, N12 366 in; **bottom of dryer skirt = instrument zero = 527.5 in**; jet pump instrument N9 152 in |
| §5.3.1.7, §5.3.3 | 2127-2135 | Studs, nuts and washers (SA-540) tensioned hydraulically; two concentric metal O-rings with a leak-off tap; skirt flange bolted to the pedestal; CRD housings welded into bottom-head penetrations; in-core housings welded to the bottom head; nozzle thermal sleeves; stainless safe ends; access openings in the skirt (p 2080) |
| Table 5.3-9 | 2151 | ID with cladding 251 in; wall 6.188 in (lower intermediate shell) and 9.5 in (lower shell) |
| Fig 5.3-2 | 2155 | Hemispherical heads; girth seams at 99 13/16 in, 230 in and 405 in; 3 plates (3 vertical seams) per beltline course |
| Fig 5.3-3 | 2156 | Elevations: recirculation outlet 172.5 in, inlet 181.0 in, BAF 216.31 in, TAF 366.31 in, feedwater 493.25 in, normal water 563.55 in, steam 648.0 in, L8 582 in |
| Fig 5.3-5 | 2158 | Labelled vessel cutaway: dryer lifting lugs, shroud-head lifting lugs, hold-down bolts, guide bars, top-head cooling spray nozzle |
| Fig 5.3-7 | 2160 | Feedwater sparger: forged tee at the nozzle, curved 6 in header, end brackets |
| Table 5.4-2 | 2218-2219 | Recirculation suction and discharge 24 in, manifold 16 in, inlet lines 12 in (5 per loop); jet pump throat 6.4 in, diffuser 19.0 in |
| §5.4 / §3.6 text | 972, 1408 | Feedwater enters through six 12 in risers (3 per side); 3 LPCI lines; 2 core spray nozzles; head vent is a 2 in line |

### Public web (read only, nothing bundled)

| URL | What it shows |
| --- | --- |
| https://www.nrc.gov/docs/ML0933/ML093380369.pdf (NRC SE of BWRVIP-181, 2010) | Dryer vanes come in banks: "four to six banks" depending on vessel size. The banks sit on an upper support ring carried by 4-6 wall brackets. A cylindrical skirt forms a water seal around the separators, and normal water is at about mid-height of the skirt. In the pool the dryer rests on a lower ring at the bottom of the skirt. Troughs feed drain channels. Hoods are square, slanted or curved. |
| https://data.epo.org/publication-server/rest/v1.2/patents/EP1806753NWA2/document.html (GE dryer hold-down patent) | The dryer has a support ring on 4-6 brackets and four hold-down brackets on the head, over the eyes of the lifting rods, with a gap of about 0.5-1 in. |
| https://www.osti.gov/servlets/purl/1213335 (ORNL, BWR upper internals, Peach Bottom BWR/4 251-764 MELCOR model) | Upper shroud OD 220 in; shroud head 2 in thick; 211 standpipes (model value); standpipe OD 6.63 in; separator OD 12.75 in; standpipe lower section 74.8 in, separator 72.8 in, top section 19.7 in; free space above the separators 29.5 in; dryer height 2.2 m. The masses are dryers 40.8 t, separators 56.6 t and dome 6.8 t. |
| https://www.nrc.gov/docs/ML2431/ML24319A084.pdf (Fermi 2 UFSAR ch. 4; BWR/4 with the same 251-764 vessel) | "an array of standpipes (225)", one separator on each; four steam outlet nozzles beside the dryer |
| https://www.nrc.gov/docs/ML2203/ML22032A142.pdf (Duke letter enclosing EPRI 3002014589) | Operating BWRs have 64-92 RPV studs, 5.625-6.0 in shank, about 5.5 ft long. Studs are left in place during refuelling, except in the "cattle chute" region, and get stud covers. |
| https://corephysics.com/docs/BWR6GeneralDescription.pdf (GE BWR/6 General Description; scanned, viewed only) | Fig 2-1 reactor assembly: vent and head spray at the crown, steam dryer lifting lug, steam outlet, and core spray inlet above the LPCI inlet. Fig 2-2: three-stage separator. Fig 2-3: dryer with skirt, vanes and collecting trough. Text: the shroud-head bolts extend above the separators, the dryer sits on wall pads, and the single full-diameter head has two metal O-rings. |
| https://www.nrc.gov/docs/ML1209/ML120970422.pdf (NRC Reactor Concepts Manual, BWR chapter) | General BWR vessel and two-stage moisture separation (separators, then dryer) |
| https://blog.ucs.org/dlochbaum/fission-stories-128-monticellos-radioactive-steam-dryer | Photos of a dryer lift and the dryer in the equipment pool. The Monticello replacement dryer is 17 ft across, 16 ft tall and 60,000 lb, which gives the proportion of the skirt plus the banks. |

Not found in public sources: Columbia stud count, separator count, shroud diameter, closure-flange and dryer
elevations, and the core-spray and LPCI nozzle elevations. These are marked **est.** below. The estimates come
from the figures and from the Peach Bottom and Fermi values, which share the same 251 in vessel.

## 2. Real dimensions (BWR/5, 251 in vessel) and the game mapping

Elevations are inches above vessel zero (the inside bottom of the lower head). Game values are blocks above the
envelope floor for the **17x17x28** reference: footprint 17, `e.height()` 27, plus the CRD layer.

| Part | Real | Source | Game, 17x17x28 | How it is mapped |
| --- | --- | --- | --- | --- |
| Inside diameter | 251 in | T 5.3-9 | 15.23 (2 x 0.448 W) | unchanged; horizontal scale s_h = 0.061 block/in |
| Inside height | 875 in | T 1.3-1 | 0.35 to 26.8 | fills the envelope |
| Wall | 6.2-9.5 in (5.4 % of radius) | T 5.3-9 | 0.20 (0.012 W) | kept thin so the existing jet-pump and sparger fits stay valid |
| Bottom head | hemispherical, girth seam at 99.8 in | Fig 5.3-2 | ellipsoid, outer rise 2.10 (0.13 D) | as deep as possible while the annulus floor at y = 1.72 stays 0.43 W wide; hemispherical at 7x7 |
| Top head | hemispherical (about 125 in rise) | Fig 5.3-2 | ellipsoid, rise 3.9 (0.25 D); 7.0 at 23x23x40 | real rise times s_up |
| Closure flange | about 755 in (est.) | Fig 5.3-2 | 23.08; forgings 0.8 high, 0.025 W proud | s_up |
| Studs | 64-92; 5.6-6 in; about 66 in long | EPRI | 74 studs, r 0.18 | real pitch (11.2 in est.), so the same count at every size, clamped to 24-92 |
| Steam nozzles | 4 x 26 in at 648 in | T 1.3-1, Fig 5.3-3 | y 19.76 | s_up |
| Feedwater | 6 x 12 in at 493.25 in | §5.2, Fig 5.3-3 | y 15.04 | s_up |
| Core spray | 2 x 10 in, about 470 in (est.) | §3.9.5, BWR6 Fig 2-1 | y 14.32 | s_up |
| LPCI | 3 x 12 in, about 420 in (est.) | §3.9.5.1.14 | y 12.77 | s_up |
| Recirculation outlet / inlet | 2 x 24 in at 172.5; 10 x 12 in at 181 | Fig 5.3-3, T 5.4-2 | just above the knuckle (2.6 to 3.0) | the lower plenum is compressed (BAF fixed at y = 2), so the nozzles are lifted onto the cylinder |
| Head nozzles | vent (2 in line), spray (flanged), spare | §3.6.2.5.4.10, §3.9.5.1.10 | 3 flanged nozzles at the crown | — |
| Support skirt | bolted to the pedestal; access openings | §5.3.3.1.4.1, p 2080 | skirt from 0.80-0.86 R, base ring with anchor nuts, 2 openings | short, because the floor is at y = 0 |
| CRD and in-core housings | 185 CRD; about 55 in-core (43 LPRM + 4 SRM + 8 IRM) | T 1.3-1 | stub tubes from the dome to y = 0 on the drive grid | the existing CRD layer continues below |
| Core shroud | about 198 in OD (Fig 4.3-1 scaled) | Fig 4.3-1 | 0.375 W | unchanged (ReactorCoreGeometry) |
| Upper shroud | 220 in OD | ORNL | 0.418 W | ratio 220/198 |
| BAF / TAF | 213 / 363 in (mod constants; Columbia 216 / 366) | Fig 5.3-3 | 2 / 11 | game rule, unchanged |
| Top guide | about 380 in (est.) | — | 11.53 | s_up |
| Shroud flange / head dome | about 440 / 465 in (est.) | Fig 5.3-5 | 13.39 / 14.16 | s_up |
| Standpipes | OD 6.63 in, 74.8 in long | ORNL | r 0.21; 14.2 to 16.4 | they stretch if the envelope is taller than real proportions need |
| Separators | 225 (Fermi 2), OD 12.75 in; 72.8 in + 19.7 in tall; hexagonal grid | Fermi, ORNL | 223 at pitch 0.82; 16.4 to 19.3 | real pitch with a minimum of 0.5 block, so 85 at 7x7 |
| Water level (normal) | 563.55 in, on the separator barrels | Fig 5.3-3, Fig 4.1-1 | 17.14 | s_up |
| Dryer skirt bottom | 527.5 in (instrument zero) | Fig 5.2-5 | 16.02 | s_up |
| Dryer support ring | about 662 in (est.) | — | 20.19 | s_up |
| Dryer vane banks | 6 banks for a 251 in vessel; about 87 in tall; hoods, troughs, end plates | BWRVIP-181 SE, ORNL | 6 banks, 20.2 to 22.8 | s_up |
| Dryer lifting rods and eyes | 4, engaged by head hold-down brackets | EPO patent | 4 rods with eyes into the head dome | — |
| Feedwater spargers | 6 curved 6 in headers with a tee and end brackets | Fig 5.3-7 | 52 degree arcs at 0.433 W | — |
| Core spray | 2 lines split into 4 half-rings, LPCS and HPCS at different heights, inside the upper shroud | §3.9.5.1.9 | rings at 0.40 W, inside the upper shroud | ring radius unchanged (CoreSpargerGeometry) |
| Jet pumps | 20, two per riser; throat 6.4 in, diffuser 19 in; inlet near 2/3 core height | T 5.4-2, NRC Issue 12 | 10 risers x 2 at r 0.4115 W, 1.72 to 2 + 0.72 x core height | same placement rule as VesselJetGeometry |

The vertical scale above the core, s_up = (top − TAF) / (875 − 363) in, is 0.031 block/in at 17x17x28. That is
half the horizontal scale (0.061), because the game vessel is twice as squat as a real one (H/D 1.7 against 3.5).
Every part above the core is compressed by the same factor, so the relative order and spacing stay real. A
23x23x40 envelope gets 0.056, close to true proportions.

## 3. What is wrong with the current model (alpha.52)

Screenshots, 1920x1080 from `-PbwrRvuCheck` and `-PbwrVesselModelCheck` (both passed), are in
`references/rpv/current/`.

1. **Squat, featureless can.** The barrel is plain. It has 12 hairline vertical "welds" (0.0006 radius,
   invisible) and circumferential bands every 4 blocks, not at real course seams.
2. **Bottom head is a pancake.** Its rise is fixed at 1.02 blocks whatever the size (0.065 D at 17 wide). A real
   head is hemispherical.
3. **Support skirt is hidden.** It is a 1-block sleeve tucked inside the bottom head and cannot be seen. There is
   no base ring, no anchor bolts and no access openings.
4. **Top head is flat and bare.** Its rise is min(0.22 H, 0.18 W): 0.2 D. It has only two tiny ears. It is
   missing the four lifting lugs and the vent, head-spray and spare nozzles at the crown.
5. **Closure is too thin.** The flange is 0.42 block for both forgings together. Studs are spaced by perimeter
   (24-80) with small nuts. Studs disappear when the head is off, but real studs stay in with covers.
6. **No nozzle schedule.** Only the player's port blocks get a thin spool, collar and pipe. Nothing shows the 4
   big steam nozzles, 6 feedwater, 10+2 recirculation, core spray, LPCI, instrument or drain nozzles. There are
   no forgings or safe ends, and every port has the same thin pipe whatever its service.
7. **Missing internals.** There is no shroud head, no standpipes, no steam separators, no steam dryer, no
   feedwater spargers, no core-spray lines, no top-guide grid or core plate, no shroud support plate, no guide
   rods and no dryer brackets. With the head off you see the fuel, one shroud ring and the spray rings floating
   in open water.
8. **Shroud is wrong.** It is one cylinder at 0.375 W with two rims. There is no stepped upper shroud and no
   flange. The spray rings at 0.40 W sit outside it, where real ones are inside the upper shroud.
9. **Water fills the steam space.** With the head off, the water draws up to the flange area. The +60 in narrow
   range is mapped to the rim, so normal level (+30) draws at y 22.2 for 17x17x28. That is where the dryer banks
   would be.
10. **Inner wall is a hairline.** The inner surface is 0.012 W (0.2 block) inside the outer, with no visible wall
    thickness at the open flange. The flange lip reads as a thin ring.

## 4. The concept (phase 1)

Script: `art/models/reactor_vessel_v2/build_concept.py`. It is parametric in W, D and H. Rectangular
footprints give elliptical sections. Run it with:

```text
"C:\Program Files (x86)\Steam\steamapps\common\Blender\blender.exe" --background --factory-startup ^
  --python art\models\reactor_vessel_v2\build_concept.py -- --size 17 17 27 --views all ^
  --stats concept\17x17x27-triangles.json
```

Renders are 1920x1080, Workbench, flat colours, in `art/models/reactor_vessel_v2/concept/`:

- `17x17x27-exterior.png`: three-quarter view of the exterior.
- `17x17x27-cutaway.png`: quarter cut.
- `17x17x27-section.png`: elevation section showing every internal.
- `17x17x27-headoff.png`: head off, looking down on the dryer. The studs stay in.
- `17x17x27-dryerout.png`: dryer removed, showing the separators and shroud-head bolts.
- `17x17x27-sepout.png`: shroud head and separators removed, showing the top guide, core, spray rings and
  feedwater spargers.
- `7x7x10-*`, `23x23x40-*` (the smallest and largest) and `15x11x22-*` (rectangular): exterior, section and
  head-off views.

Side-by-side comparisons are `references/rpv/compare-exterior-17x17x28.png`, `compare-headoff-17x17x28.png`,
`compare-internals-17x17x28.png` and `compare-sizes.png`.

### Triangles, 17x17x28 (from `concept/17x17x27-triangles.json`)

| Group | Triangles | When drawn |
| --- | ---: | --- |
| Exterior: shell, seams, skirt, nozzles, CRD and in-core stubs | 24,122 | always |
| Closure studs | 2,072 | always (they stay in with the head off) |
| Head: dome, flange, nuts and washers, crown nozzles, lugs | 12,576 | head on; also the head missile |
| Internals: shroud, plates, top guide, jet pumps, spray, feedwater, rods | 17,028 | interior visible only |
| Shroud head, standpipes and 223 separators | 28,400 | interior visible only |
| Steam dryer | 9,686 | interior visible only |
| Simplified core (stand-in for the existing core mesh, not new) | 18,144 | — |

So: closed vessel about 39k, open vessel (head off, internals shown) about 81k new triangles (7x7x10: 74k in total, 23x23x40: 135k in total).
For comparison, `head.json` alone is about 2 MB of JSON today.

Phase-2 LOD plan:
- Draw the separators as 8-sided tubes, and as 6-sided beyond about 48 blocks.
- Fold the separator stage ring and lip into the barrel past 32 blocks.
- Bake the dryer slats into a texture strip instead of geometry.
- Draw nothing inside the shroud head unless the interior is visible (already the rule).

All of it would be cached in MachineMeshCache meshes like today, with no per-frame tessellation.

## 5. Phase-2 integration plan (proposal)

**Replace the exterior parts with Blender-exported pieces.** The exterior becomes shell, bottom head, skirt, seams,
flange, stud, nut, top head, crown nozzles, lugs and nozzle kits.
- Keep the current `reactor_vessel/*` model names where the renderer and other code use them: `head`, `flange`,
  `stud`, `spool`, `collar`, `failed_head`, `steam_nozzle`, `water_nozzle` and `controller_panel`. This keeps
  `ReactorHeadRenderer`, the head-missile entity and the damaged head working.
  The head missile scales `head` by (W, H/1.65, D). The new head must keep the 0.92-wide, 1.65-high unit
  convention, or the entity renderer changes in the same commit. The latter is simpler: export the head at unit
  rise and pass the rise.
- Rebuild `failed_head` from the new head: the same tear script, on the new mesh.
- Shared axial maths moves into one class, `VesselProfile`, used by ReactorVesselRenderer, VesselDriveGeometry
  (CRD neck heights, currently `1.2 − 1.02·√…`), VesselWaterGeometry.radiusFraction (lower dish, currently 0.94),
  CavitySealGeometry and the port-spool projection. Today those five places each hard-code the old dish.
- **Ports:** the player's port blocks keep their positions and pipe faces. Each gets the nozzle kit for its
  service (steam = 26 in class forging, feedwater/recirculation = 12 in, and so on), projected onto the new
  profile, as the spools are now. Fixed decorative nozzles (instrument, drain, crown vent and spray) are drawn
  always. **Owner choice:** also draw a full real schedule of blind nozzles even where no port exists, or only
  real ports. I recommend only real ports plus the non-connectable small ones, so nothing implies a connection
  that does not exist.
- **Instrument panel** (controller) keeps its own model and position.

**Internals.** These are new cached meshes, drawn only when the interior is visible (the existing
`interiorVisible` gate).

Parts that must keep working:
- **Core shroud.** Must still swap to the RIP/ABWR variant (`abwr_shroud`). An internal-pump vessel gets no jet
  pumps and no risers. The ABWR has a different separator count but the same dryer concept.
- **Jet pumps and spargers.** They keep their existing layout code. Only the spray rings' visual elevation
  changes, from rows above TAF to inside the upper shroud. The construction rows that validate LPCS and HPCS do
  not change.
- **Cherenkov glow.** It uses TAF and the water surface; no change, provided the water mapping below is decided
  first.
- **Cavity seal and well water.** They use `rim`. If the drawn flange moves, `rim` must follow it. CavitySeal's
  flange row is gameplay, so this needs care and the cavity GameTests.

**Open decision, needs the owner: the water mapping.** Physics puts the flange at instrument +60 in
(`PressureVessel.FLANGE_LEVEL_IN`). In a real vessel +60 is at the bottom of the dryer, and the flange is about
+225. Two options:

- **(a) Physics change.** Raise FLANGE_LEVEL_IN to about +225 so that drawing and physics agree. Flooding the
  well then needs about 120 m3 more water. This touches the cavity and refuelling-bridge tests: no existing
  assertion may be edited, so it needs your approval first.
- **(b) Visual only.** Draw TAF to +30 in on the real stack, so the operating level sits on the separators, and
  stretch +30 to +60 up to the flange. The visible water then climbs quickly through the dryer zone only during
  overfill. I recommend (b) for phase 2, with (a) as a later physics item.

**Gameplay proposal (not built): removable dryer and separators.**
- Real plants lift the dryer, then the shroud head and separators, into the dryer/separator storage pool before
  moving fuel.
- In the game, a "Dryer" and a "Shroud head and separator assembly" state on the vessel, toggled from the
  refuelling bridge or crane, only with the head off and the well flooded.
- While either is installed, fuel moves are refused. Removing the separators with the water below the flange is
  refused.
- Removed assemblies are drawn on a player-placed pad block, the storage pool.
- With nothing removed, the vessel behaves as now. Saves without the new keys default to "installed".
- Physics effect, optional: none, or a small moisture-carryover penalty if the plant is run with the dryer out.

**Gates for phase 2:**
- The rendering client checks (`-PbwrVesselModelCheck`, `-PbwrRvuCheck`, reactor fit, cavity, accident blast for
  the head missile).
- GameTests with and without CC.
- Save compatibility, if the removable-assembly state is added.

## 6. Phase 2: the model in the game (alpha.53)

### Owner decisions

These came from the owner on 2026-10-23:
- Build the look as rendered.
- Draw only real ports plus the small nozzles nothing connects to.
- Water line option (b): a visual stretch only. `FLANGE_LEVEL_IN` and the physics are unchanged.
- Make the dryer and the separators removable.

### Where the game differs from the concept, and why

- **Head rise.** The concept's head rose 0.25 D at 17 x 17 x 28. In the game it keeps the old dome height,
  `min(0.22 H, 0.18 W)` (0.196 D). The thrown head's size, the head access cells (`VesselHeadAccess`) and the flange lip `rim`
  hang on it, and the lip is the cavity seal's row and the well's start. Moving it would move existing players' seal rows. The
  drawn flange face is therefore the old head base, and the internals above the core are scaled to fit between the fuel top and
  that flange (`VesselProfile`).
- **Wall.** The wall stays 0.012 of the width (the real wall is 5.4 % of the radius), so the jet-pump and spray-ring fits stay
  valid.
- **Bottom head.** It is as deep as the jet-pump seat allows: an ellipsoid of about 0.13 D at 17 wide, hemispherical at 7 wide.
- **Studs.** Their positions follow the real pitch (74). They are drawn with the head off; the nuts and washers go with the head, until the stud tensioner takes them off (BUILDING-CRANE.md): then the head the crane lifts has none.

### What changed in the code

**`VesselProfile`** (new) is the one shape. The five places that hard-coded the old dish now read it:
- the port projection;
- `VesselDriveGeometry`'s CRD necks;
- `VesselWaterGeometry`, through a new `radiusFraction(e, y)` (the old one-argument method is kept for its callers);
- `VesselAccidentEffects`' steam from a failed head;
- `VesselInternalsGeometry.spargerHeight`. The rings are now drawn inside the upper shroud; the construction rows are unchanged.

**Parts.** The unit parts come from `art/models/reactor_vessel_v2/build_parts.py` and are placed by:
- `VesselModel`: the outside, the shared head and the ports;
- `VesselInternalsModel`: the fixed internals, the separators and the dryer.

The old `barrel`, `bottom`, `flange`, `head`, `weld`, `stud`, `collar` and `failed_head` models are gone. The failed head is
rebuilt on the new dome: torn rim, peeled segment, sheared studs.

**Thrown head.** `ReactorHeadRenderer` draws the thrown head with `VesselModel.buildHead`: the same flange, dome, crown nozzles,
lugs and nuts, at the entity's saved width, depth and dome height.

**Variants.** The jet-pump vessel's old `shroud` is replaced by the v2 shroud. The internal-pump (ABWR) vessel keeps
`abwr_shroud`, and the v2 upper shroud, shroud head, separators and dryer carry on above it at its radius.

**Water (option b).** The surface is true to the drawn scale up to +30 in. +30 to +60 in is stretched to the lip; overfill stays
at the lip, and the well is unchanged. `levelAtHeight` is the exact inverse.

### Removable dryer and separators: the rules chosen

- **Lifting.** The refuelling bridge did it in alpha.53 (no new hoist: the smaller change). Since the building crane (BUILDING-CRANE.md) the reactor building crane lifts the head first, then the dryer and the separators; the bridge moves fuel only and refuses these lifts.
- **Storage.** An Equipment Storage Pad block on the floor of a player-built pit of vanilla water is the dryer/separator storage
  pool. A pool multiblock would have been more work for no extra realism here.
- **Consequences.**
  - Fuel moves are refused while either assembly is in.
  - The head cannot go back on while either is out, so the vessel cannot hold pressure.
  - The INFO tab and `getInternals()` say "Not operable".
  - Control rods are not interlocked. Real plants do refuelling criticality checks with the internals out.
- **Doses.** Dryer 0.2 W and separator assembly 10 W of equivalent gamma (game-scale estimates).

### In-game pictures (1920 x 1080, `art/rpv-remodel/`)

From `-PbwrRpvRemodelCheck`:
1. the 17 x 17 x 28 vessel outside;
2. head off, looking down on the dryer;
3. the dryer hanging from the bridge;
4. the dryer on its pad in the flooded pit;
5. the separators on the core;
6. the separators lifted;
7. the core with both out;
8. both on their pads;
9. 7 x 7 x 10;
10. 23 x 23 x 40;
11. 15 x 11 x 22;
12. 23 x 23 x 40 head off;
13. 7 x 7 x 10 head off.

From the other checks:
14. the thrown head in flight (`-PbwrAccidentBlastCheck`);
15. the thrown head landed (`-PbwrAccidentBlastCheck`);
16. the vessel after the head missile (`-PbwrAccidentBlastCheck`);
17. the damaged head (`-PbwrRvuCheck`);
18. head off with water (`-PbwrRvuCheck`);
19. the vessel check's reference vessel (`-PbwrVesselModelCheck`).

Old against new, in game: `references/rpv/compare-ingame-exterior.png`.

In game the flat tints read darker than in the Workbench concept: the entity lighting darkens them, and the part colours were
already raised to the old vessel's brightness.
