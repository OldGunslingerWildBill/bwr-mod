# Build Status

## 2026-09-21 — inline turbine steam valves and Realistic BWR logo

Added Blender-authored `steam_stop_valve` and `turbine_control_valve` blocks,
axial steam connections, recipes, loot, creative entries, manual panels and
optional CC peripherals. The control valve has 0.1% resolution and a two-second
full stroke; the binary stop valve strokes in 0.5 seconds. Both start closed.
HP/LP sections now operate from their physical supply; local admission/Start
controls and their CC methods are removed. Existing steam/energy inventories
remain valid. See [STEAM-VALVES.md](STEAM-VALVES.md) for wiring and migration.

The mod name/creative tab are **Realistic BWR**; `bwr` registry IDs are retained.
The supplied artwork is embedded as `logo.png`, with the original image pixels
preserved. README and turbine instructions reflect the new controls.

Verification:

- 12 new real-server scenarios pass: common control at 100%, 50%, 25%, 25.1%
  and 0%; two HP/four LP mass accounting; stop isolation with trapped steam;
  a physical bypass; closed Mek export branch; all flange rotations; actuator
  persistence/invalid NBT; GUI range/distance checks; CC actuation.
- Existing 15 main-module, 73 pump, 96 RCIC/HPCI assembly and two plumbing
  scenarios pass. Five focused core turbine tests also pass.
- Actual client bake checks: 2,203 water/steam/valve states and eight item
  models, 6,816 pump/main-module states and 11 item models, plus 312 RCIC/HPCI
  states. Eight client screenshots captured; new valves and both valve panels
  visually inspected, with 65.3% command and stop closure round-trips verified.
- `:mod:build`, protection-logic check (157 files), asset audit (zero problems),
  valve export reproducibility (14 files), and JAR audit (257 classes) pass.
  No CC/Mekanism or developer-test classes are bundled. Full long-running
  reactor acceptance suite was not rerun; the pure-Java reactor core is unchanged
  by the valve patch.

Artifact: `mod/build/libs/mod-0.1.0-SNAPSHOT.jar`, **14,261,494 bytes**.

SHA-256: `97444843E48B2931C91420A99ADBBD008ADE9498E6C247E08525BDA5C24A5C16`.

Logs: `tmp/valve-live-check.log`, `tmp/valve-release-build.log`,
`tmp/valve-assets.log`. Reviewed screenshots and editable Blender source:
`art/models/steam_valves/`. The subsequent requested GitHub update packages
this patch with the pending modular turbines, modern pumps/pipes and
volume-based recirculation changes documented below.

## 2026-09-20 — modular main turbine and generator release

Three Blender-authored placeable modules: TX-10-style HP (5 x 5 x 9), LP
(7 x 5 x 7), and TX-NLCH-style generator (5 x 5 x 9). Touching aligned shafts
form a train; external steam headers connect HP exhausts to LP inlets. LP
condensate returns through a dedicated Mekanism-compatible water outlet.
The condenser/MSR is deferred; the temporary condensation model is documented
in [MODULAR-TURBINES.md](MODULAR-TURBINES.md). Generator capacity is 1,500 MW per
module, using the mod's existing FE conversion. Panels and CC are included.

Validation of the final implementation:

- Five new core physics tests pass: HP/LP mass and energy balance, finite load
  and inventory limits, parallel draws, invalid inputs and fractional restore.
- Fifteen main-turbine runtime scenarios pass, including 2 HP / 4 LP on one
  shaft, proportional shared-header supply, no duplicated nozzle flow or FE,
  all four rotations, shaft gaps/height/reversal, persistence, and actual
  Mekanism mechanical-pipe condensate transfer.
- All 73 existing pump scenarios, 96 RCIC/HPCI assembly scenarios and two
  earlier turbine plumbing scenarios pass.
- Actual client model checks pass: 6,816 pump/main-turbine cell states plus
  312 RCIC/HPCI states, inventory models and textures. Five screenshots were
  visually checked: the train, generator exterior and three working panels.
  Client admission changes round-trip to the server. The test generator
  produces FE under load from a real hot-reactor nozzle.
- Both model exporter reproducibility checks pass (1,414 new module files and
  949 existing modern pump/pipe files). Asset audit reports zero problems.
- `:mod:build` and protection-logic checks pass. JAR audit checks 249 classes,
  no bundled Mekanism/CC classes, and no dev-test classes. `git diff --check`
  passes. The entire long-running core acceptance suite was not rerun.

Artifact: `mod/build/libs/mod-0.1.0-SNAPSHOT.jar`, **13,368,882 bytes**.

SHA-256: `D194A57D3187AB22148F344B06169CE24C73B656BF75C674116E0D6975E0F813`.

Final server/client/build log: `tmp/power-balanced-release.log`. In-game
previews are included in `art/models/power_turbines/`. Changes remain local.

## 2026-09-20 — ten jet assemblies per recirculation pump

Raised `RecirculationSizing.JETS_PER_EXTERNAL_PUMP` from eight to **ten normal
placed assemblies**, as requested. Required pump counts, actual flow limits,
CC:Tweaked sizing and the INFO tooltip use this shared value. Volume-derived
jet requirements remain unchanged. Two full-speed RCPs support 20 normal
assemblies, three support 30, and four support 40. For a 32-assembly target,
the resulting flow limits are 62.5%, 93.75% and 100%. A 44-assembly target now
requires five full-speed RCPs. The no-jet path retains 10% of drive capacity.

Validation:

- **Six core sizing tests passed**, including plateau, speed/stopped-drive,
  volume, no-jet, flow preservation and restore checks.
- **73 pump runtime scenarios passed**, plus all 96 RCIC/HPCI assembly scenarios
  and both turbine plumbing scenarios. Shared-header tests reached the revised
  limits and verified that surplus jets and duplicate pumps add no capacity.
- `:mod:build`, JAR audit and `git diff --check` passed. The JAR contains 231
  classes including the nested core, no bundled optional mods and no dev tests.
- The full core suite and client rendering were not rerun for this limit change;
  prior validation is recorded below.

Playable artifact: `mod/build/libs/mod-0.1.0-SNAPSHOT.jar`, **12,435,273 bytes**.
SHA-256: `CB36985FF80BADDA1327797F1B0298FDA3B8FC03025BB51B3BAE362388470F47`.

## 2026-09-20 — volume-based recirculation and finite drive capacity

Required normal jet assemblies now equal `2 * ceil(6 * cbrt(max(1, V / 200)))`,
where V is interior width × height × depth. A minimum vessel needs 12 assemblies;
a 23 × 23 outside footprint, 10 blocks high, needs 32. Each RCP supports eight
normal assembly equivalents, so that larger example requires four full-speed
drives. These are gameplay calibration values. The weak no-jet path, matched
opposing assemblies, speed ramp and coastdown remain part of the flow calculation.

The core flow reference scales with the target while each jet/RCP/RIP has a
fixed kg/s capacity. Updating the reference preserves instantaneous physical
flow and demand; it does not create water, heat, fuel or a new operating core.
The controller stores `RatedCoreFlowKgPerS` beside the core snapshot, preserves
it through pending/unformed saves and rebases old fixed-reference saves on load.
Height-only resizing keeps the existing core and fuel. Thermal MW and vessel
inventory sizing are separate work; this patch scales circulation requirements.

INFO shows matched/required jets, installed/required RCPs, interior volume and
available flow. Tooltips show required/actual kg/s and unmatched/internal hardware.
CC:Tweaked exposes the same sizing through `getRecirculationSizing()`.

Validation:

- **24 targeted core tests passed, 0 failed** (97.7 s): six new sizing/drive/
  reference-preservation tests plus the existing continuous-rod, cold-start and
  state-round-trip tests. The complete long-running acceptance suite was not
  rerun for this patch; its previous result is recorded below.
- **73 pump runtime scenarios passed, 0 failed**, including real minimum and
  maximum-footprint vessels, roof extension without fuel loss, reference NBT
  migration, and a tall 32-assembly target with shared pump headers. Two drives
  stayed at 50% with 16, 32 and 40 jets; three reached 75%, four 100%; stopping
  the fourth returned delivery to 75%. Duplicate registrations did not add flow.
  All 96 RCIC/HPCI assembly scenarios and both turbine-plumbing scenarios pass.
- Client model checks and all panel captures pass. The updated INFO screenshot
  was visually inspected: 1,089 interior blocks, 22 assemblies and three RCPs.
- `:mod:build`, `git diff --check` and JAR audit pass. The JAR contains 231 classes
  including the nested core, no bundled optional mods and no dev-test package.

Guide: [RECIRCULATION.md](RECIRCULATION.md).
Playable artifact: `mod/build/libs/mod-0.1.0-SNAPSHOT.jar`, **12,435,230 bytes**.
SHA-256: `91A8366ACCCABB1FB8587D08D3B170961446D645D69A55F66E92A91089B4126A`.

## 2026-09-19 — centered pump flanges and dyeable round piping

Blender-authored modern layouts replace the offset green adapters on new HPCS,
LPCS, RHR/LPCI, electric feedwater and turbine feedwater placements. RHR grows
15%; both feedwater bodies grow 25%. All five footprints have odd widths and
depths, with centered base controllers and flanges at exact block-face centers.
The DVSS model, reactor physics and pump curves are unchanged. See
[MODERN-PUMPS-AND-PIPES.md](MODERN-PUMPS-AND-PIPES.md) for sizes and migration.

Steam and water pipes use 64 Blender-built round connection patterns. Each
segment's identification bands accept all 16 vanilla dyes. Paint persists in
blockstates, consumes one dye per changed segment in survival, and has no effect
on service compatibility or flow. Solid material UVs sample the texture interior
to avoid atlas-edge artifacts; pipe blocks use non-occluding rendering.

Validation:

- **71 pump runtime scenarios passed**, including four-way modern placement,
  20 legacy-layout cases, real Mekanism suction delivery, separate steam/water
  circuits, turbine admission/exhaust, and both pipe types with every dye.
  The dye checks cover consumption, creative mode, repeated color, NBT,
  neighbor updates and rotation. All 96 RCIC/HPCI assembly scenarios and both
  turbine-plumbing scenarios also pass.
- Client baking: **4,036 pump cell states**, eight inventory/compact models,
  **2,195 water/steam states**, six associated item models and 312 RCIC/HPCI
  states. Real client captures of all five connected modern pumps and the 16
  pipe colors were visually inspected, as were the Blender previews.
- Export reproduction: **949 modern assets** match. Asset audit: **1,380 JSON
  files, zero problems**. JAR audit passes with no bundled optional-mod classes
  or development-test package. Final `:mod:build` passes.
- Core physics was not modified; the 160-test acceptance result below is from
  the preceding rod/jet update and was not rerun for this model/pipe patch.

Editable source: `art/models/modern/modern_pumps_and_pipes.blend`.
Game captures: `mod/run/turbineModelCheck/panel-check/modern-*.png` and
`pipe-colors.png`. Blender previews are alongside the editable project.
Playable artifact: `mod/build/libs/mod-0.1.0-SNAPSHOT.jar`, **12,430,929 bytes**.
SHA-256: `BA959D23E96163A10335A55612F776BD82B5F75525E2E58A5785671CC8D2E800`.

## 2026-09-19 — continuous rod travel and one-column jet assemblies

Rod commands retain discrete notch labels, while absorber position and integral
worth advance each simulation tick. A normal label 24→28 move takes about 4.17 s
at the existing drive speed. Nodal absorption uses fractional positions too.
Drive outages hold the physical position; reversal and scram start there. The
fractional position survives NBT saves, with a notch-based fallback for old saves.
See [ROD-MOTION.md](ROD-MOTION.md).

New jet placements occupy **1 × 6 × 1** blocks. Only mesh width changes: the
original 1.111-block-wide geometry is scaled horizontally by 0.85 and centered in
one column; height, depth, materials and UVs are retained. Opposite-wall pairs
can share the same row instead of requiring a diagonal reflection through the
vessel center. Same-wall, same-facing, shifted-row and wrong-height pairs are
rejected. Saved two-column assemblies and their old pairing rule remain valid;
pick up and replace them to use the narrow layout.

Validation:

- Full core acceptance run: **160 tests passed, zero failed**, 880.9 s.
- After the final hold-until-demand-is-restored refinement, all **five rod-motion
  tests and seven state round-trip tests** passed again. The 34 snapshot fields
  are covered. The subsequent packaging cleanup changed formatting/comments and
  removed an unused local; no additional behavior changed.
- Label 24→28 changes worth on every 50 ms tick. A near-critical withdrawal test
  raises power on 80 consecutive ticks, with a largest step of 0.6241% and a
  finite positive SRM period. Original notch-worth endpoints remain exact.
- **50 pump runtime scenarios passed** with Mekanism and CC:Tweaked, including
  both opposite-wall axes, invalid pairs, six-cell occupancy, legacy-save
  pairing and real mod NBT round trips. All 96 RCIC/HPCI assembly scenarios and
  both turbine-plumbing scenarios also pass.
- Client baking: **2,476 pump cell states**, eight inventory/compact models,
  83 water states and 312 RCIC/HPCI states. Five GUI screens, the connected DVSS,
  and an off-center opposing jet pair rendered in the test world. The jet pair
  reports **2 matched, 0 unmatched**; its screenshot was visually inspected.
- Narrow-model reproduction: **47 current/legacy assets** verified. Asset audit:
  **916 JSON files, zero problems**. JAR audit: **224 classes**, no bundled
  optional-mod classes or dev-test package. Final `:mod:build` passes.

Preview: `mod/run/turbineModelCheck/panel-check/jet-pair-in-vessel.png`.
Playable artifact: `mod/build/libs/mod-0.1.0-SNAPSHOT.jar`, **8,625,083 bytes**.
SHA-256: `24B1492027351CE54523146E978566A0493560C7D2840639667EF80097BC9279`.

## 2026-09-18 — enlarged DVSS and continuous suction elbow

The current DVSS reserves **5 × 10 × 5 blocks** (width × height × depth), with
uniform 5/3 enlargement and 250 owned cells. One continuous mesh now joins the
rear suction flange to the axial inlet underneath the casing. Suction is centered
0.5 blocks above the floor; discharge is centered 2.5 blocks above it.

The shared pump CELL range supports 256 cells. State-dependent layouts preserve
both saved compact RCPs and the previous 3 × 6 × 3 assembled DVSS. Missing
`enlarged` loads false; new item placements set it true. Existing pumps keep
their original controller, controls, occupancy and flange positions. Pick up
and replace a pump to enlarge it, then reconnect the headers.

Validation for this revision:

- **48 pump runtime scenarios passed**, including four rotations of the new
  model and four legacy-save cases (54 cells, one owner, pipe connections,
  preserved speed, root loot and neighboring-block protection).
- The connected loop still reaches the reactor flow solver. Existing jet
  matching, pump speed, crossed-header and broken-loop tests pass.
- Client baking: **2,452 pump cell states**, eight inventory/compact models,
  83 water states and 312 RCIC/HPCI cell states pass. Both DVSS sizes are checked.
- Five real GUI screens and the enlarged DVSS with connected pipes were rendered
  in a disposable Minecraft world; the world screenshot was visually checked.
- **620 generated DVSS assets** reproduce from the current and legacy source
  meshes, with conserved area and validated OBJ bounds/normals. Current mesh:
  7,880 triangles, 414.788 square block units of surface area.
- Asset audit: **901 JSON files, zero problems**. JAR audit: **224 classes**,
  no bundled optional-mod classes or dev-test package. `:mod:build` passes.

Blender source: `art/models/dvss/dvss_recirculation_pump.blend`.
Preview/detail: `art/models/dvss/dvss_preview.png`, `dvss_suction_detail.png`.
Playable artifact: `mod/build/libs/mod-0.1.0-SNAPSHOT.jar`, **7,784,127 bytes**.
SHA-256: `BB250F95CE89FB0D080F72870F688D806740708E55A8CC6CEA154FBD44BBA7D5`.

The entries below describe earlier revisions.

## 2026-09-18 — DVSS recirculation pump and reactor INFO

Added the Blender-built DVSS exterior on the existing recirculation-pump id:
3 × 6 × 3 blocks, one controller, rear lower suction and front elevated discharge.
The 54 clipped OBJ cells render through NeoForge. Existing placed RCPs keep
compact occupancy, orientation, same-height ports and saved controls.

The closed vessel loop now resolves model flange cells. It supplies weak
no-jet flow, matches jet assemblies across the vessel centre at the lowest two
interior elevations, and shares capacity without diluting a running pump when
a stopped parallel pump is connected. The supplied jet meshes are unchanged.
The reactor INFO tab adds configuration, matched/unmatched jets, installed flow
capacity, live output and explicitly labeled planning estimates. No core thermal
solver, automatic control, reactor scaling or new cooling source was added.

Validation on Minecraft 1.21.1 / NeoForge 21.1.248:

- **44 pump scenarios passed** with Mekanism and CC:Tweaked; **40 passed without
  either optional mod**, plus all 96 RCIC/HPCI assembly scenarios and both live
  turbine-plumbing cases. Includes four DVSS orientations, shared capabilities,
  compact migration, core-solver flow with no jets, mirrored versus same-side jets,
  elevated/too-high jets, crossed/broken loops, half speed, stopped parallel pump,
  conserved water/temperature and populated configuration snapshot round trips.
- Client baking: **1,452 occupied pump cell states**, eight inventory/compact
  models, **83 water block states**, five water items and **312 RCIC/HPCI states**.
  The DVSS alone has 42,292 baked quads across four rotations.
- Integrated-client QA opens five real server-backed screens, including reactor
  INFO, and places the DVSS model with water pipes in an isolated world. Images
  live in `mod/run/turbineModelCheck/panel-check/`.
- `tools-export-dvss.py --check`: **116 assets** reproduced from **6,912 source
  triangles**, preserved surface area and validated per-cell bounds/normals.
- `tools-import-turbines.py --check`: **172 existing assets** remain reproducible.
- Asset audit: **649 JSON files, 32 registered blocks, 31 recipes, zero problems**.
- Final JAR audit: **223 classes**, no bundled CC/Mekanism classes or dev-test
  package. `:mod:build` and the no-protection-logic check pass.
- The previous core acceptance results below still apply: this update changes
  mod-side hardware derivation and readouts, not core physics.

Player instructions and model provenance: [RECIRCULATION.md](RECIRCULATION.md).
Blender source: `art/models/dvss/dvss_recirculation_pump.blend`.
Playable artifact: `mod/build/libs/mod-0.1.0-SNAPSHOT.jar`.
JAR size: **7,152,672 bytes**. SHA-256:
`19552FD4595134558026D22CDFEF1741CE64224B3617323463E1314917D5CE03`.

## 2026-09-17 — pump panels, condensate tank, and vessel recirculation

Implemented the shared 0–100% speed/Start/Stop panel for powered pumps, with
live flow, pressure, temperature and drive readouts. Panel/computer ownership
persists; ECCS suction and RHR operating mode are selectable in the panel.
Electric status no longer prints steam admission/exhaust. The finite CST now has
a tank model, level panel, and an external fluid handler that honors fractional
water already delivered to pumps. The RPV injection face now fills the shell.

Restored all twelve jet-pump cell meshes to the supplied archive geometry, with
no added green adapters or game ports. New top-outlet/bottom-inlet vessel ports
close an external water-pipe loop through the RCP's separate inlet/outlet faces.
Installed jet count and connected drive capacity limit recirculation; internal
manifold piping is implicit. See [WATER-PLUMBING.md](WATER-PLUMBING.md).

Verification on Minecraft 1.21.1 / NeoForge 21.1.248:

- **39 pump scenarios passed** with Mekanism and CC:Tweaked, plus the existing
  RCIC/HPCI placement and physical steam/water plumbing checks. Four scenarios
  exercise the actual Mekanism Mechanical Pipe network delivering water into
  LPCS, HPCS, RHR and motor-feed suction. They caught and verified a fix for stale
  capabilities on removed multipart cells, including pipes placed before pumps.
- **35 pump scenarios passed without either optional mod**, plus both turbine
  plumbing fixtures. GameTest exits successfully in both configurations.
- Client baking: **87 water/tank/RCP block states and six inventory models**,
  **1,236 pump cell states**, seven inventory/compact models, and **312 RCIC/HPCI
  cell states** with both inventory models.
- Real integrated-client GUI check: electric, steam, recirculation and tank
  screens opened, received server snapshots and rendered successfully. Images
  under `mod/run/turbineModelCheck/panel-check/` were visually inspected.
- Core: **all 153 existing tests passed** in the full 867-second run. The two
  new speed/persistence tests pass on the final fixture. The first run's speed
  assertion used an overly tight tolerance for finite-time spin-up (1e-8 at
  200 seconds); it was corrected to 1e-6 and the two new tests rerun successfully.
  Production core code did not change after the full run.
- Asset audit: **593 valid JSON, 32 blocks, 31 recipes, zero problems**.
  All twelve restored jet cell OBJ files match the supplied archive after
  normalizing line endings. `git diff --check` passes.
- Final `:mod:build` succeeds; the no-protection-logic check scans 134 files.
  Packaging audit finds 222 classes including the nested physics jar, with
  **zero bundled Mekanism, CC:Tweaked or devtest classes**.

The GUI uses the existing suction/vessel temperature model. Stored condensate
remains 32°C; no new water-temperature solver is introduced. The runtime fixture
does not construct a complete Mekanism Generators turbine or perform a long
player-world soak. Existing RCP loops require the rewiring documented in the guide.

Artifact: `mod/build/libs/mod-0.1.0-SNAPSHOT.jar` — **6,829,027 bytes**.
SHA-256: `7E533E52C0E97BB79B981B766537B055524AE4D66DD2994472B017B9E6F16727`.

## 2026-09-17 — separate water piping and RPV injection

Added High-Pressure Water Pipe, outward-facing RPV Water Injection Port,
NeoForge condensate return, and strict separation of steam/water flange routing.
Kept RCP available as requested. Retired the old RCIC/HPCI cubes from recipes and
the creative tab while preserving their IDs. Modeled HPCI now has two blue
associated-pump water adapters and requires real suction/discharge piping.
See [WATER-PLUMBING.md](WATER-PLUMBING.md) for the required existing-world rewiring.

Verified on Minecraft 1.21.1 / NeoForge 21.1.248:

```text
:mod:runTurbineGameTest :mod:build
Turbine assembly runtime checks: 0 failure(s)
Turbine plumbing PASS: rcic
Turbine plumbing PASS: hpci
PUMP RUNTIME CHECK: 35 scenarios passed, 0 failure(s)
All 1 required tests passed
checkNoProtectionLogic: scanned 127 files; no protection logic present
BUILD SUCCESSFUL in 41s

:mod:runTurbineModelCheck :mod:build
WATER MODEL CHECK PASS: 70 block states and two inventory models
PUMP MODEL CHECK PASS: 1236 occupied cell states, seven inventory and seven compact models
TURBINE MODEL CHECK PASS: 312 cell states and both inventory models
BUILD SUCCESSFUL in 38s

tools-audit-assets.py
583 valid JSON; 30 blocks; 29 recipes (two legacy blocks intentionally uncraftable)
PROBLEMS: 0

tools-import-turbines.py --check
172 deterministic text assets verified

tools-check-jar.py
216 classes including nested physics jar
0 bundled CC:Tweaked classes, 0 bundled Mekanism classes, 0 devtest entries
OK: nothing forbidden is bundled.
```

The condensate test pushes actual NeoForge water through a real pipe network into
a modeled feed pump and checks finite water delivery. It also checks wrong fluids,
capacity/simulation semantics, broken cached routes, wrong pipe types, backwards
ports, freestanding ports, unformed vessels, and fractional ECCS-buffer persistence.
The fixture does not build a complete Mekanism Generators turbine. The unchanged
153-test physics suite was already run for the preceding update below; this update
changes the Minecraft integration and uses its runtime, model, asset, and build gates.

Artifact: `mod/build/libs/mod-0.1.0-SNAPSHOT.jar` — 6,805,610 bytes.
SHA-256: `EDC72D430CDBD4896CA47543F2B441A62C0A11C9FD8BF1E02CCAA9E9D193C7BF`.

## 2026-09-17 — pump model pack and connected recirculation

Implemented the seven supplied models, shared controller/capability access, sided
water ports, live pump circuits and jet-count flow limits. The RIP has a recipe,
bottom-head rim mounting and the existing speed-control screen. Existing IDs and
saved compact machines remain readable. See [PUMP-MODELS.md](PUMP-MODELS.md).

Verified with Minecraft 1.21.1 / NeoForge 21.1.248:

```text
build (full physics suite)
153 tests, 153 passed, 0 failed, 865.8 s
BUILD SUCCESSFUL in 14m 32s

:mod:runTurbineGameTest :mod:build
Turbine assembly runtime checks: 0 failure(s)
Turbine plumbing PASS: rcic
Turbine plumbing PASS: hpci
PUMP RUNTIME CHECK: 34 scenarios passed, 0 failure(s)
All 1 required tests passed
checkNoProtectionLogic: scanned 122 files in :mod, no protection logic present
BUILD SUCCESSFUL

:mod:runTurbineModelCheck
PUMP MODEL CHECK PASS: 1236 occupied cell states, seven inventory and seven compact models
TURBINE MODEL CHECK PASS: 312 cell states and both inventory models
BUILD SUCCESSFUL

tools-audit-assets.py
573 valid JSON files; 28/28 blocks have models, blockstates, items, loot and recipes
PROBLEMS: 0

tools-check-jar.py
209 class files including the nested physics jar
0 bundled CC:Tweaked classes, 0 bundled Mekanism classes, 0 devtest entries
OK: nothing forbidden is bundled.
```

The new scenarios cover all seven machines in four rotations, occupied-footprint
rejection, collision bounds, flange/tube arms, shared FE, suction-only fluid
access, root loot and removal, saved-state migration, an isolated closed valve
branch alongside an open suction header, live motor feedwater and
three ECCS pump circuits, RFPT water/steam accounting, lost suction/discharge/
exhaust, paired-jet count and disconnected drive lines, and a formed vessel with
an internal pump and usable control menu. External pumps and RIPs are also checked
for automatic association through their pipe or mount. Earlier RCIC/HPCI cases still run.

The supplied geometry uses approximate equipment envelopes. Tests do not include
a long-running player-world/chunk-unload soak or an interactive performance
benchmark with many machines. No condenser or future spray-nozzle model is added.

## 2026-09-14 — RCIC TWL and HPCI placeable assemblies

Added `bwr:rcic_twl` and `bwr:hpci_turbine`, imported from the supplied estimated
STEP exteriors, with full-footprint placement, recipes, one-item harvesting,
individual process ports and BWR pressurised tube routing. See
[TURBINE-ASSEMBLIES.md](TURBINE-ASSEMBLIES.md) for the port guide and approximations.

Verified in this pass:

```text
./gradlew.bat build
153 tests, 153 passed, 0 failed, 901.0 s
BUILD SUCCESSFUL in 15m 10s

./gradlew.bat :mod:runTurbineGameTest :mod:build
Turbine assembly runtime checks: 0 failure(s)
Turbine plumbing PASS: rcic
Turbine plumbing PASS: hpci
All 1 required tests passed
checkNoProtectionLogic: scanned 116 files in :mod, no protection logic present

./gradlew.bat :mod:runTurbineModelCheck
TURBINE MODEL CHECK PASS: 312 cell states and both inventory models
```

The single GameTest runs 96 placement/harvesting/port/persistence cases across the
two machines and four rotations, plus connected-plant and shared-flow regressions.
In controlled 80-second pump runs, RCIC delivered 2475.58 kg and HPCI 18940.49 kg
from their tank; tank inventory agreed within its sub-kilogram remainder.
Steam claims shared the nozzle ledger, with no second steam debit via the ECCS bus.

The deterministic STEP importer verified 172 text assets; the asset audit reports
238 valid JSON files, all 27 blocks covered and zero problems. The jar audit finds
no bundled CC:Tweaked/Mekanism classes or development harness classes.
`tools-render-turbines.py` produced a visually inspected preview from the exported
cell meshes. Compiler output includes deprecated Minecraft API notes.

The four new pure-Java tests cover empty/restricted steam supplies, independent
steam and water pressures, and coastdown after supply loss. The shared ECCS bus
also now clears its last owned channel on withdrawal/expiry; the game fixture tests
that regression and leaves unowned manually controlled channels alone.

The HPCI input models a turbine exterior only, so its pump-water association
remains abstract as documented. These checks do not include a long-running player
world or an actual chunk-unload soak test. Source dimensions are estimates.

Older counts and limitations below refer to their dated passes.

## 2026-08-25 — feedwater pumps, and a warning about the rest of this file

**Everything below this section dates from 2026-08-16 and its counts are stale.** They were
already stale before the feedwater work: this file says `124 tests` and `scanned 98 files in :mod`,
but the close-out and playtest passes of 17–18 August took those to 142 and 106 without refreshing
it. They have not been corrected wholesale here, because doing so would mean quoting figures for
other people's work that this pass did not re-derive. Trust `HANDOFF.md` §5 and the commands below
over any number further down.

Run in this pass, `./gradlew clean build` from clean, each exit status captured with `$?` rather
than through a pipe:

```
checkNoProtectionLogic: scanned 110 files in :mod, no protection logic present
149 tests, 149 passed, 0 failed, 864.2 s
BUILD SUCCESSFUL in 14m 32s
PROBLEMS: 0
OK: nothing forbidden is bundled.
```

`GRADLE_EXIT=0  ASSETS_EXIT=0  JAR_EXIT=0`. Zero compiler warnings across both modules; `:core`
also compiles clean under `-Xlint:all -Werror`.

What changed: the two reactor feed pumps of SPEC §15 now exist as hardware
(`bwr:motor_feed_pump`, `bwr:turbine_feed_pump`), 7 new tests in
`core/src/test/.../feedwater/FeedwaterPumpTest.java` take the suite from 142 to 149, and both pumps
were added to the `runPeripheralCheck` harness so their Lua surface is actually invoked. Full
description in `HANDOFF.md` §5 and `SPEC.md` §15.1.

Three defects were found and fixed *in the course of writing this*, and two of them were in code
that predates it:

- `EccsPump.fromArray` restored `flowDemandFraction` and `speedFraction` raw from NBT, so a
  non-finite value on disk reloaded as itself and went straight into the vessel's mass balance.
  This affected the six existing ECCS machines, not only the new pumps. Both fields clamp now.
  Same shape as the two NaN-on-restore defects the August audit confirmed.
- The feedwater suction draw floored to whole millibuckets, which delivers **nothing at all** below
  20 kg/s — feedwater would have worked at full demand and silently failed during a startup. The
  sub-kilogram remainder is carried between ticks, as `CondensateStorageTankBlockEntity` already
  did.
- A turbine feed pump claimed the relief channel only while its drive steam was non-zero, so a pump
  coasting to a stop stopped claiming and left the core holding its last relief flow forever. It
  claims on drive type now, which is why the emergency machines never had this bug.

**Not run in this pass:** none of the five `run*` tasks. `runPeripheralCheck` in particular now has
two new probe targets that have never executed. See `HANDOFF.md` §9.

---

Last updated: 2026-08-16, immediately after the audit-fix pass. **The previous version of this
file described the tree as it stood on 2026-08-15 and is wrong in most of its details** — an audit
found 102 defects across the tree (37.5k lines of production Java, 45.5k with the tests), all but
one have been fixed, and the fixes moved numbers that were quoted here as measurements.

Provenance is tagged, because not everything here can be produced by a command:

- **Run in this pass.** The command is named next to the claim. The acceptance suite was re-run
  directly out of `core/build/classes` from this build, the two Python audits were re-run, and the
  jar was re-opened.
- **Read from the source.** Constants, structure, wiring. Marked as such.
- **Measured mod-side during the fix work, with no test that can reproduce it.** `:mod` has no test
  source set, so nothing on the Minecraft side of the boundary is re-runnable. Marked **[mod-side,
  not reproducible]** every time it appears. Treat those as the weakest claims in the document.

## Summary

**The plant is now operable.** That is the headline, and it is a change of kind rather than of
degree. Before this pass: repairing a broken vessel deleted the fuel, the running transient and the
boundary damage record; every chunk reload drove all rods fully in; the recirculation pump had no
energy capability, so core flow was permanently zero; neither rods nor recirculation flow could be
commanded from Lua at all; and the mod never installed a startup neutron source, so the source range
monitors sat at 0.015 cps against a 3.0 cps bottom of scale — a healthy shut-down core indicating
00.00 on every channel. Each of those is fixed. Taking a core critical from a Lua console is now a
thing a player could attempt.

**`./gradlew clean build` succeeds.** BUILD SUCCESSFUL, both modules compile with zero errors and
zero warnings, `124 tests, 124 passed, 0 failed` as part of `check`, both jars produced. The test
count went 117 → 124 across 17 test classes, and `CriticalDefectRegressionTest` arrived: one test
per critical defect, each named for the wrong behaviour it forbids rather than for the component it
exercises, because in two years the interesting thing about it will not be that a scram latch round
trips — it will be that a save used to *cancel* a scram.

**Rod worth is position-dependent now, and rod indices agree.** The nodal solve computed per-rod
flux weights and threw them away; a centre rod and an edge rod were both worth 6.34e-05 dk/k.
Wired through, a centre rod is worth **-2.003e-03** against an edge rod's **-2.809e-04**, a ratio of
**7.13**. Separately, the solver ranked rods by radius while the multiblock rastered them, so drive
*i* and rod *i* were different rods: measured, **0 of 16** drives on a 9x9 vessel shadowed their own
four bundles, worst rank **76 of 81**. With the real map built from structure validation, **9 of 16**
have their own four positions as the top four by flux rise and the worst rank anywhere is **9 of 81**.

**The nodal solve is deterministic and it costs what it costs.** The warm start was deleted, because
per-rod flux weights now reach total reactivity and a history-dependent flux shape made a restored
core disagree with a running one. `solve()` is a pure function of its inputs, with an exact memo when
nothing moved. **The "cold 24.4 ms / warm 9.84 ms" figures the previous version of this file quoted
no longer describe anything**: there is no warm solve. See *Verified physics* for the replacement
numbers and *Known issues* for the open performance decision.

**`ReactorState` went from 26 record components to 33.** Added: `scramActive`, `peakFuelTempC`,
`intermediateRangeMonitorRanges`, `coreInletEnthalpyKJPerKg`, `rodFluxWeights`,
`fuelExcessReactivityDkOverK`, `dopplerCoefficientPerCAtAnchor`. A save used to cancel an
in-progress scram, un-melt the fuel, re-range the IRMs to 1 and lose most of the axial void profile.
The 26-argument compatibility constructor was deleted on purpose, so that adding a component breaks
every call site at compile time rather than silently defaulting one.

The honest headline gap is unchanged and is the most important sentence in this file:

> **Nothing has ever run in a Minecraft world or client.** No GUI has been rendered. No
> `@LuaFunction` has been called. `:mod` has no test source set at all, so every mod-side change in
> this pass is verified by compilation and by reading, and nothing else. The 124 tests are
> exclusively `core/`.

## Building

There is no JDK or Gradle on PATH. The repository now carries a Gradle 8.12 wrapper; PrismLauncher
ships a full JDK 21.0.7 including `javac`:

```bash
export JAVA_HOME="/c/Users/14238/AppData/Roaming/PrismLauncher/java/java-runtime-delta"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew clean build
./gradlew :mod:runData                                # boots with both soft dependencies
./gradlew :mod:runData -PbwrNoMekanism                # proves the Mekanism soft dependency
./gradlew :mod:runData -PbwrNoCC                      # proves the CC:Tweaked soft dependency
./gradlew :mod:runData -PbwrNoCC -PbwrNoMekanism      # neither present
./gradlew :mod:runPeripheralCheck                     # builds a plant in a real world — NEVER RUN
python tools-audit-assets.py                          # asset integrity, non-zero on any gap
python tools-check-jar.py                             # nothing forbidden bundled, non-zero on any
```

Do not pipe gradle through `tail`/`head` and then trust `$?` — that is the pipe's exit code.
Redirect to a file, check the code, then grep the file.

Pinned: NeoForge 21.1.248, ModDevGradle 2.0.143, Gradle 8.12, CC:Tweaked **1.120.2 on the 1.21.1
branch** (`cc-tweaked-1.21.1-core-api` and `-forge-api` `compileOnly`, the full `-forge` jar
`runtimeOnly` for dev runs only), Mekanism 1.21.1-10.7.19.85 via modmaven.dev (which needs
`metadataSources { artifact() }` — it publishes jars with no POM). The old 1.111.0 API is gone: its
NeoForge range is `[21.0.21-beta,21.1)` and FML refuses it against 21.1.248.

The physics module still builds with nothing but a JDK — no Gradle, no network, no JUnit:

```bash
javac -d out $(find core/src/main/java -name '*.java')
javac -cp out -d testout $(find core/src/test/java -name '*.java')
java -cp "out;testout" dev.bwr.core.AcceptanceTests
```

Under Git Bash, wrap every semicolon-joined classpath entry in `cygpath -w`. A bare POSIX path in a
`;`-joined `-cp` is silently discarded, and what you then compile against is whatever stale classes
sit on the rest of the path. This cost real time twice during the fix work and once again while
verifying this document.

## Verified

Everything in this table was produced by running the command named in it, in this pass, against the
tree as it stands, except the two rows explicitly marked otherwise.

| Check | Command | Result |
|---|---|---|
| Clean build from scratch | `./gradlew clean build` | **BUILD SUCCESSFUL**, 0 errors, 0 warnings. *Run by the pass that produced the artifacts now on disk; not re-run while writing this file.* |
| Acceptance suite (wired into `check`) | `java -cp "core/build/classes/java/main;core/build/classes/java/test" dev.bwr.core.AcceptanceTests` | **124 tests, 124 passed, 0 failed, 784.7 s** — run straight out of this build's own class files |
| Test-class registry is complete | `find core/src/test -name '*.java'` vs `AcceptanceTests.TEST_CLASSES` | 20 files, 17 test classes, all 17 registered; static method count sums to **124** |
| Design rule enforced in `:mod` | `mod/build/checkNoProtectionLogic.txt` | `scanned 98 files, 0 violations` |
| Design rule enforced in `:core` | `ReactorCoreTickTest.test07` | scans **1066 methods and 747 fields across 46 classes in 11 packages** — 23 named explicitly, the rest by classpath discovery |
| Asset integrity | `python tools-audit-assets.py` | **PROBLEMS: 0**, exit 0; 125 JSON files parsed, 75 texture refs from models and 5 from Java all resolve, 0 textures referenced by nobody |
| Per-block asset coverage | same | **21/21** blockstate, block model, item model, loot table, recipe |
| Blockstate variant coverage | same | every BlockState combination the Java defines has a model, including all **64** pipe states on `pressurised_tube` (6 properties, 7 multipart parts) |
| Foreign item ids in recipes | same | **4 used, 4 cross-checked against `Mekanism-1.21.1-10.7.19.85.jar`** — this check is new, and it is what caught `mekanism:pellet_fissile_fuel` |
| Nothing forbidden bundled | `python tools-check-jar.py` | **OK**, exit 0. 172 class files scanned incl. nested jarJar: **0** bundled `dan200/computercraft`, **0** bundled `mekanism/`, **0** entries under `dev/bwr/mod/devtest/` |
| Jar contents | `python zipfile` | mod jar **309 files** (351 entries incl. dirs); top-level roots exactly `META-INF`, `assets`, `data`, `dev`, `pack.mcmeta` |
| Artifacts | `ls` | `core-0.1.0-SNAPSHOT.jar` (156 KB), `mod-0.1.0-SNAPSHOT.jar` (510 KB) |
| `core/src/main` has no Minecraft | `grep -rnE "net\.minecraft\|net\.neoforged\|mekanism\|dan200\|cc\.tweaked" core/src/main` | **0 matches**; the only imports in the whole of `core/src/main` are `java.*` and `dev.bwr.*` |
| ...and cannot load it either | `CriticalDefectRegressionTest.test08` | asserts `Class.forName` fails for `net.minecraft.world.level.Level`, `net.minecraft.nbt.CompoundTag`, `net.neoforged.neoforge.energy.IEnergyStorage`. The only `net.minecraft` strings under `core/src` are the five inside this test |
| Rod index agreement | throwaway harness over `RodLatticeMapping` + `NodalFluxSolver`, not a repo test | 9×9 interior, 16 drives, withdraw one from an all-in core and rank all 81 positions by flux rise: with the structure-derived map **9 of 16** drives have their own four bundles as the top four, worst rank **9 of 81**; with the solver's own centre-outward default, **0 of 16** and worst rank **76 of 81**. In-repo, `NodalFluxSolverTest.test13` asserts the map is a partition; nothing in the suite asserts it is the *right* partition |
| Mod loads, four soft-dependency permutations | `./gradlew :mod:runData [-PbwrNoCC] [-PbwrNoMekanism]` | **exit 0 on all four — but last run on 2026-08-15, against a tree that has since changed by ~100 fixes. This evidence is stale. Re-run it.** |

### Soft dependencies — how they are actually guarded

Both are `compileOnly` (each additionally `runtimeOnly` for the dev runtime only) and neither is
bundled. Verified at the **bytecode** level, not by reading imports: scanning every `.class` in the
jar for constant-pool references gives exactly

- `mekanism.*` — 4 classes: `fuel/MekanismFuelFeed`, `mekanism/BwrMekanismSupport`,
  `mekanism/MekanismSteam`, `mekanism/TurbineSteamOutletChemicalHandler`
- `dan200.computercraft.*` — **10** classes, which is every class in `peripheral/` and nothing else.
  This was 7 last pass. A comment in `mod/build.gradle` cites a "4 classes reference `dan200.*`"
  figure in this file; there has never been one, and the number that is checked on every build is
  `tools-check-jar.py`'s, not a comment's.

Every one is reached only through a `ModList.get().isLoaded(...)` branch in `BwrMod`'s constructor,
and because the check and the reference live in different classes the JVM never resolves a foreign
type on an install that lacks the mod. `dev.bwr.mod.devtest` is the only place outside `peripheral/`
that names a CC:Tweaked type, and the jar task strips the whole package — confirmed, 0 entries.

The four `runData` permutations were the empirical proof for both. **That proof is now stale** and
has not been re-run since the fixes.

## Verified physics

Measured values, copied out of this pass's acceptance run — `124 tests, 124 passed, 0 failed,
784.7 s`. Where a number moved since the previous version of this file, the row says so; several
did, so do not assume an unchanged-looking row was copied forward.

That 784.7 s is against the 104 s the previous pass reported for 117 tests inside Gradle. Part of
that is seven new tests, part is that another build was running on the same machine, and part is
that the transient tests now pay a full cold nodal solve every simulated second instead of a warm
one. The three are not separated here, so treat the total as an upper bound and
`NodalFluxSolverTest.test11` — which came out within a millisecond of the previous pass's cold
figure — as the number that actually means something.

| Behaviour | Measured |
|---|---|
| Approach to critical | 169 steps, SRM 72.9 → 3763.1 cps; 1/M falls 1.000 → 0.019 as rho goes −0.060003 → −0.001135 |
| Startup to rated | 1.0012e-10% → 102.75% of rated over 6015 s (1205 rows), ending 1025.0 psig, level 30.0 in, APRM 99.0%, and the SRM at 1.224e-51 cps having peaked at 9.996e10 |
| Flow-only load change | flow 1.00 → 0.70 drives power 100.00% → 73.07% with the rods held at notch 20.45, void 0.3714 → 0.3794 |
| Rated core holds its point | 300 s at rated: total 1.0000 → 1.0005, worst deviation 0.0017, at 1025.0 psig / 287.44 °C / void 0.3715 / fuel 478 °C |
| SCRAM from rated | 4 s after: fission 2.28% of pre-scram, total 0.0731 = 0.0214 fission + 0.0517 decay. 600 s after: total 0.0217 (77 MW), **not zero** |
| Decay heat ignores the scram | in a full plant transient: 0.0621 of rated at the scram (222 MW, against a saturated 0.0662) → 0.0503 (180 MW) at 5 s → 0.0127 (45 MW) at one hour |
| Delayed tail | time constant 80.6 s over 450–750 s, exactly 1/λ₁ of the longest precursor group (73.4 s over 150–450 s) |
| Pressurisation | 20 s of isolation: 1025 → 3084 psig, void 0.3715 → 0.3020, fission 0.9383 → 3.2918 |
| Doppler | at rated, fuel 478 °C gives rho_doppler −0.00973 dk/k; after the scram, fuel 293 °C gives −0.00632 |
| Saturation curve, operating band | 544.597 °F at 1000 psia vs table 544.58 (+0.017); 1025 psig → 287.438 °C; worst error 0.357 °F over 800–1400 psia. Bit-identical to the old fit at and above 800 psia |
| Enthalpy fits | worst h_g error 0.253% against the tabulated sum over 800–1400 psia |
| Delayed neutrons | rho 0.0040 is 0.62$ on U-235 but 1.91$ on Pu-239 — after 1 s, U-235 reaches n = 4.15, Pu-239 pins at 1e12 |
| SRM dead time | paralyzable peak 9.9967e10 cps at n = 6.628e-03; rolls over rather than pegging, and at 100× that flux indicates 1.01e-30 cps |
| A rolled-over channel lies | on a true +20 s period the meter reads +20.2 s below rollover and **−11.1 s** above it, with power still climbing |
| Subcritical equilibrium | n = ΛS/|rho| to −0.000% at five reactivities from −0.005 to −0.200; `initialiseSubcritical` holds 1.000937e-12 exactly for 1000 s |
| Hot shutdown count rate | rho −0.10667 dk/k (−16.41$) gives **41.05 cps, status ONSCALE** — the whole point of the calibrated source. 600 ppm boron takes it to 29.86 cps |
| 1/M is hyperbolic in shutdown margin | rho −0.160 → −0.010 gives 1/M 1.0000 → 0.0625 on exactly the 1/2, 1/4, 1/8 halving |
| Instrument overlap | SRM 7.317e-14 → 6.628e-03, IRM 1.265e-07 → 1.000e-01, APRM 3.000e-02 → 1.250 |
| Rod position | 25 discrete notches, per REFERENCE-DATA §6 |
| **Rod worth depends on where the rod is** | 177 rods: central **−0.002003** dk/k, peripheral **−0.0002809**, ratio **7.13**; per-rod flux weights span 0.2762 to 1.9694, where uniform weighting would be 1.000 everywhere. The audit measured both rods at 6.34e-05 before the weights were wired |
| Flux shape | uniform core k=1.10995, non-leakage 0.9910, radial peaking 2.091, edge bundle 12.0% of centre, 58 iterations |
| Rod shadowing | one rod of 748 in: its bundle −61.6%, the bundle at the same radius on the far side +16.8%; half inserted, bottom/top ratio 1.000 → 0.372 |
| Local absorption | an absorber at one position depresses flux to 0.461 / 0.660 / 0.745 / 0.791 / 0.820 at 0–4 bundles away, and costs the whole core k 1.10995 → 1.10899 |
| Stuck rod | 177 rods in, one out: that bundle carries 10.49× core average, radial peaking 1.604 → 10.494 |
| Loading pattern | same inventory, fresh centre k=1.10655 peaking 3.079 against fresh edge k=1.07360 peaking 1.852 |
| Effective beta | 69 MOX bundles in the centre give β 0.006014; 220 of the same at the edge give 0.006207 — three times the plutonium, less than half the effect |
| Axial shape | flooded core peaks mid-height at 1.455, symmetric to 2.15e-05; boiling peaks at node 6 of 25 at 1.765 |
| **The solve is a function of its inputs alone** | the same core solved cold from the analytic guess and after being driven through a scram and a full withdrawal: both 54 iterations, both k=1.08134818, **worst relative weight difference 0.00e+00 by route**. Re-solving an unchanged core takes **0 iterations** — that is the memo |
| Shift is an accelerator only | shifted 77 iterations / 32.0 ms vs unshifted 858 / 325.5 ms for the same k to 9 digits, 10.2× faster, weights agree to 6.09e-06 |
| **Nodal solve cost** | 18700 cells (748 lattice positions × 25 axial nodes). **Cold solve 25.0 ms** (58 iterations); 24 successive solves each with one rod moved one notch: **mean 18.80 ms, worst 24.25 ms**. There is no warm solve any more — the previous "24.4 ms cold / 9.84 ms warm" pair no longer exists, because the warm start is gone |
| Quasi-static separation | at a 20-tick interval the shape moves only on ticks 20/40/60/80 of 80; shortest frozen run is exactly 20 |
| Mesh and rod map | 748 assemblies, 18700 cells, 177 rods shadowing 703 assemblies (94%); node 15.24 × 15.24 cm; power weights sum to 0.999999999999997 |
| Scram capability is hardware | accumulator charge after a scram from notch 24/12/0 = 0.0000/0.5000/1.0000; a spent one recharges to 0.259 in ten minutes, and **0.000** in ten minutes with the CRD water supply lost |
| Persistence round trip | exact across all **33** record components — `test07` perturbs each one reflectively and the comparator catches every one |
| Persistence carries the flux shape | restored core's per-rod flux weights: **0 of 177 differ**; the axial void profile the solve ran on is restored from the record rather than reconstructed |
| Mid-transient restore | worst power difference **0.0000%**, and 0.0000% after 120 s. The previous pass measured 0.3416% converging to 0.0003%; a restored core now simply resumes |
| A save must not cancel a scram | saved mid-scram at notch 13 with accumulator 0 at 0.6667: 30 s later the latched core is at notch 0 with 0.1250 charge, the deliberately un-latched counterfactual is still at notch 13 with 0.6796 |
| A save must not un-melt the fuel | ten reload cycles with a second of running between each hold peak fuel at 2400.0 °C |
| A reload must not range the IRMs for you | saved rack `[2,4,6,8,10,3,5,7]` comes back `[2,4,6,8,10,3,5,7]`, not the fresh-core `[1,1,1,1,1,1,1,1]` |
| The vessel conserves mass | 75 s at rated with every valve shut: 226796.557 → 226798.977 kg (+2.421 kg, 1.07e-05 relative) ending at the 5000 psig ceiling; at the pressure floor a 2000 kg/s relief demand removes **0.000 kg/s** and the inventory does not move |
| A full vessel at low pressure is not an uncovered core | 800 → 0 psig: collapsed level −15.0 → −119.1 in against a TAF of −167.0 in, covered fraction 1.0000 throughout |
| Suppression pool | 2 h pool suction, no RHR: 32.00 → 88.10 °C, remaining heat capacity 977227 → 162415 MJ; with RHR in pool cooling 81.39 °C |
| Suppression degrades continuously | at containment pressure the pool saturates at 99.29 °C; condensation effectiveness 1.000 at 29 °C of subcooling, 0.774 at 9.29, 0.500 at 6.00, 0.083 at 1.00, 0.000 at zero |
| Overpressure onset | stress exactly 0.0 at and below the 1250 psig design limit on all four components, positive immediately above; a 0.05 s excursion to 1300 psig leaves 6.667e-06 that is unchanged ten minutes later — metal does not un-fatigue |
| Overpressure rate law | x² in overpressure: 1275/1300/1325/1350/1375 psig for 60 s give 0.00200/0.00800/0.01800/0.03200/0.05000; the rate at the code limit is 4.0× the rate halfway to it |
| Failure is weighted by exposure | 400 trials at 1500 psig: recirculation line 67.0%, main steam 28.5%, feedwater 4.5%, vessel head 0.0%; mean time to first failure 368.6 s. Reactor internal pumps last 17.8% longer than external loops |
| First break | 20 s of isolation reaches 3084 psig and wounds without breaking (recirc stress 0.743); unrelieved, the recirculation line fails at **43.5 s and 4255 psig** after 16.35 s above the code limit |
| Break consequences differ | steam line: 1025 → 411 psig, break flow 796 kg/s, fission 0.9383 → 0.2422. Recirculation: core flow 1.00 → 0.002, fuel fully uncovered. Feedwater: pressure *rises* to 1141 psig first, and 60 s in unattended every one of the four components is broken |
| Level indication lies | during a steam line break the indication bottoms at 15.6 in at 35 s and then reads 22.4 in while the real collapsed level keeps falling, 8.1 → −40.3 in over 90 s |
| Uncovery collapses the heat sink | covered U_cc 299.2 MW/°C → uncovered 0.00500 MW/°C, **59845× worse** |
| Decay heat alone melts a scrammed core | rods fully inserted from tick one, fission zero throughout: clad 1200 °C at 1329 s, fuel melt at 4259 s, 675 kg of hydrogen |
| Decay heat has no off switch | with fission pinned at zero from the instant the rods hit bottom: 6.625% of rated (237 MW) → 4.84% (173 MW) at 10 s → 1.355% (48.5 MW) at one hour → 0.522% (18.7 MW) at one day. `clearInventory()` is the only thing that zeroes it, and that is a claim about what is in the fuel, not a control action |
| Zr-water onset is emergent | rate roughly triples per 100 °C (3.71× at the top of 1000–1500 °C, 2.16× at the bottom, because it is an exponential in 1/T); at the declared 1200 °C onset it makes 26.1 MW against 48.3 MW of decay heat an hour after shutdown |
| The reaction self-accelerates | at pinned decay heat it overtakes it at 1549 °C; fastest clad rise 14.9172 °C/s is 31.4× the slowest, with the heat source unchanged; 19080 accelerating ticks, 0 decelerating |
| Quench spike branches at 1527 °C | reflood at 1450 °C costs +1.1% hydrogen and strips no oxide; at 1600 °C it costs +6.1% and strips the film 141.9 → 35.1 µm. A 5× step at the hinge, and below it a 1400 °C core hit with 2000 kg/s of spray cools at 1085 °C/s without cracking anything |
| Late reflood is the TMI-2 mechanism | reflood at 1900 °C: +110.47 kg H₂ (46% more), oxide 230.1 → 19.9 µm (91% stripped), peak H₂ rate 37× |
| Reflood always beats inaction | 4 h totals: no reflood 2651.8 kg H₂ and 100% oxidised at 8092 °C; reflood at 1400 °C 73.9 kg (3%). Short-window crossover at about 2200 °C is real and does not change the four-hour answer |
| Hydrogen is stoichiometric | 60000 kg of Zr fully consumed gives 2651.8 kg against a ceiling of 2651.8; worst relative disagreement 7.55e-15 |
| Damage is monotonic under abuse | 400000 adversarial ticks (402 with a NaN step, 394 with a negative step): every monotonic quantity held. The protective oxide is deliberately *not* monotonic — cracking it is the quench spike |
| Fuel types | LEU β 0.006502, MOX 0.003500 (0.54×), Pu-239 0.002099 (0.32×), thorium 0.002660 (0.41×) |
| Mixed core | 60/30/10 LEU/MOX/Pu by flux gives β_eff 0.005161 where the unweighted mean would be 0.004034 |
| Fuel position matters | 24 MOX in a 15×15 LEU core cost 6.685e-04 of β in the centre against 1.090e-04 at the edge — 6.13× for the same fuel |
| End of cycle | fresh LEU excess 0.0795 Δk/k, spent at 29088 MWd/t; thorium peaks at 10000 MWd/t before ending at 33500 |

## The design rule

> The mod provides hardware and physics. The player provides control logic.

Enforced by five checks that fail the build:

1. `ReactorCoreTickTest.test07_noProtectionLogicAnywhereInThePhysics` — **1066 methods and 747
   fields across 46 classes in 11 packages**, 23 named explicitly and the rest found by classpath
   discovery so that a new package cannot hide from it. It now scans field names as well as method
   names, and it carries three named exemptions with a written reason each:
   `PhysicalConstants.RECIRC_LOW_SPEED_FRACTION` (a detent on two-speed hardware, not a judgement)
   and the harness operator's own `PRESSURE_SETPOINT_PSIG` and `LEVEL_SETPOINT_IN` (the stand-in for
   the player's Lua, which is the side of the line a setpoint belongs on).
2. `SuppressionPoolTest.test07_poolContainsNoProtectionLogic` — 25 methods. The pool's heat capacity
   temperature limit is deliberately absent: subcooling and effectiveness are published, and the
   limit is the operator's to choose.
3. `BoundaryStressTest.test11_noProtectionLogicInTheDamageModel` — 59 methods across 4 classes, and
   it demonstrates the rule rather than only asserting it: **30 s of unrelieved isolation reached
   5000 psig and opened nothing**.
4. `NodalFluxSolverTest.test12_noProtectionLogicInTheSpatialSolve` — 107 methods across 4 nodal
   classes. A flux map is exactly where a convenience method deciding a peaking factor is
   unacceptable would be tempting.
5. `:mod:checkNoProtectionLogic`, wired into `check` — **98 files**, 0 violations.

## What exists

```
core/src/main   37 files   kinetics, thermal, instrument, fuel, poison, pool, nodal,
                           boundary, eccs, harness
core/src/test   20 files   acceptance suite + runner (17 test classes, 124 tests)
mod/src/main    98 files   reactor, pool, steam, flow, rods, fuel cycle, ECCS, damage,
                           GUIs, Mekanism boundary, peripherals, registries, devtest
mod/resources  180 files   assets and data
```

21 blocks, 22 items, 11 block entity types, 5 menus, 5 screens, 5 datapack fuel types. The 11
classes implementing `newBlockEntity` are exactly the 11 with a registered type.

`ReactorPeripheral` alone exposes **73** `@LuaFunction`s, including `setRodPosition`,
`setRecirculationFlow`, `setIrmRange`, `scram`, `resetScram`, `setInjectionFlow`,
`setBoronInjection` and the twelve period meters. Across the mod there are **179** `@LuaFunction`s
and **171 of them are `mainThread = true`**. The eight that are not are: `getTopOfActiveFuel`,
`getTemperature`, `getStrokeSeconds`, `getRatedPowerDraw`, `getCapacity`,
`getMillibucketsPerKilogram`, `getRatedFlow` — each returning a single `static final` — and
`isMekanismPresent`, which returns a flag fixed at mod construction. Each says so in its javadoc.

## SPEC section 14 build order

| # | Step | Status | Evidence |
|---|---|---|---|
| 1 | `ReactorCore` standalone | **done** | `PointKineticsTest`, `ReactorCoreScramTest`, `ReactorCoreTickTest`, `SubcriticalSourceTest`; rated core holds 1.0000 → 1.0005 over 300 s |
| 2 | Nodal flux solve feeding β_eff and weights | **done, and the weights now arrive** | `NodalFluxSolverTest` 14 tests. Per-rod flux weights reach `RodWorth`, which is why rod worth is position-dependent at all; `test10` asserts the quasi-static freeze |
| 3 | Multiblock wrapper | **done, never ticked in a world** | `ReactorControllerBlockEntity`, `SYNC_INTERVAL_TICKS = 5`. Repairing a vessel no longer discards the core, rod demand survives a rebuild, and the structure now supplies the rod↔lattice map. No world has ever loaded it |
| 4 | Fuel registry + assemblies | **done** | 5 datapack `fuel_type` JSONs, codec round-trips, malformed entries rejected with legible messages. The `fuel_assembly` recipe named a Mekanism item that does not exist and has been corrected; the asset audit now cross-checks foreign ids against the Mekanism jar so it cannot recur |
| 5 | Control rods + CRDs, S-curve worth, accumulators | **done** | 25 notches asserted; accumulator charge 0.0000/0.5000/1.0000 by insertion depth, spent one stays 0.000 for ten minutes with CRD water lost; rod position is commandable from Lua through `setRodPosition` |
| 6 | Pumps + jet pumps, energy limiting, inertia, GUI slider, CC toggle | **done** | `RecirculationPumpBlockEntity` now publishes an `IEnergyStorage` capability, without which it drew no power and core flow was permanently zero. Asymmetric coastdown, `isPowerLimited()`, `RecirculationPumpScreen` slider, `ReactorPeripheral.setRecirculationFlow` |
| 7 | Pressure + saturation + SRVs | **done** | `SaturationTest` worst error 0.357 °F over 800–1400 psia, and the correlations now extend down to 14.7 psia (see *What was wrong*); `safety_relief_valve` and `msiv` actuated by redstone or Lua |
| 8 | `IPeripheral` exposure | **complete surface, never executed** | 10 peripheral classes, 179 `@LuaFunction`s, every one that touches block-entity, level or core state marshalled to the main thread. `runPeripheralCheck` exists to call all of them in a real world and **has never been run**. No `@LuaFunction` has ever executed |
| 9 | Overpressure stress/failure model | **done** | `BoundaryStress` wired into `ReactorCore`, persisted by `BoundaryDamageNbt`, exposed by `BoundaryDamageReadout`; `BoundaryStressTest` 11 tests |
| 10 | Refuelling GUI + head state + refuel rendering | **partial** | `RefuellingMenu`/`RefuellingScreen` with working `loadAssembly`/`unloadAssembly`, `VesselState` refuses head removal under pressure, and `canHoldPressure()` finally has a consumer — an open head is a 350 kg/s-per-psi steam discharge path, so the vessel cannot be pressurised through a hole the size of itself. **Still no refuel rendering**: there is no `BlockEntityRenderer` anywhere in the mod |
| 11 | Severe accident model | **done** | `SevereAccidentEscalationTest`, `QuenchSpikeRefloodTest`, `DamageBookkeepingTest` |
| 12 | Suppression pool, ECCS machines, spargers, SLC, ADS | **done** | All ECCS blocks registered; `ReactorEccsBus` calls `setInjectionFlowKgPerS` and `setBoronInjectionPpmPerMinute`. Pool capacity now follows the basin the player actually built rather than a universal 3,400,000 kg |

Eleven of twelve done, one partial. Step 10 is blocked on the one thing nothing in this project has
yet done: run a client. Step 8 is functionally complete and completely unexercised, which is a
different and more uncomfortable status than "partial".

## What was wrong, and what was done about it

The audit found 102 defects. 101 are fixed; the one left open is recorded under *Known issues*.
This section is deliberately a record rather than a changelog — a reader should be able to see what
was broken, not just that something was.

### The ten criticals

1. **Repairing a vessel deleted the plant.** Breaking and replacing one vessel block discarded the
   `ReactorCore`, taking the fuel, the running transient, the decay heat and the boundary damage
   record with it. `revalidate` now keeps the core unless the rod count actually changed, and
   snapshots state, fuel, boundary damage and rod demand before any rebuild.
2. **Every chunk reload drove all rods fully in.** The rebuilt drive network came up with no
   commanded pattern. Rod demand is now carried across the rebuild.
3. **The recirculation pump had no energy capability**, so it never received power, so core flow was
   permanently zero and the plant could not be operated at all. It now publishes
   `Capabilities.EnergyStorage.BLOCK`. **[mod-side, not reproducible — `core/` cannot see this, and
   `CriticalDefectRegressionTest.test08` says so out loud.]**
4. **Rods and recirculation flow could not be commanded from Lua.** The mod's entire stated premise
   is that the player writes the control logic; the two things a BWR operator actually manipulates
   were absent from the peripheral. `setRodPosition` and `setRecirculationFlow` now exist.
5. **No neutron source was installed.** `CoreConfig` ships 1.0e-12/s, three orders of magnitude
   below what this core needs; a healthy shut-down plant read **0.015 cps against a 3.0 cps bottom
   of scale**, so every SRM indicated 00.00 and 1/M could not be plotted. The block entity now
   installs `TransientHarness.CALIBRATED_SOURCE_PER_SECOND` = 2.67e-9/s, sized from the subcritical
   equilibrium, and a hot shut-down core reads **41.05 cps ONSCALE**.
6. **Per-rod flux weights were computed and discarded**, so every rod was worth the same. Now wired:
   centre −2.003e-03 against edge −2.809e-04, ratio 7.13.
7. **Rod indices disagreed between the solver and the multiblock.** `setRodLatticeMap` had zero
   callers. `RodLatticeMapping` now builds a real map from structure validation; 0 of 16 → 9 of 16.
8. **A save cancelled an in-progress scram**, un-melted the fuel, re-ranged the IRMs to 1 and
   reconstructed the axial void profile instead of restoring it — the audit measured 72% of the
   profile lost mid-transient and, on a mixed-fuel core, excess reactivity wrong by 5.0e-06 dk/k.
   *(Those two figures are the audit's, pre-fix; they are not reproducible now that the fields
   exist.)* Seven components added to `ReactorState`; `CriticalDefectRegressionTest` 01–04 are the
   regression cover, and the round-trip test now reports the profile restored from the record
   rather than reconstructed.
9. **Every `@LuaFunction` ran on the computer thread.** None was `mainThread = true`, and one
   re-entered the nodal solver from a CC computer thread mid-reallocation. **171 of 179** are now
   marshalled; the remaining eight return an immutable constant and are named above.
10. **The `fuel_assembly` recipe named `mekanism:pellet_fissile_fuel`**, an id that has never existed
    in Mekanism 10.7. `RecipeManager` drops a recipe with an unknown ingredient at datapack load, so
    the only crafting route to a fuel assembly silently did not exist. Corrected to
    `mekanism:yellow_cake_uranium`, and `tools-audit-assets.py` now opens the pinned Mekanism jar
    out of the Gradle cache and cross-checks every foreign id.

### The rest, grouped

- **The nodal solve was history-dependent.** The warm start made `solve()` a function of what the
  solver had done before, so a restored core and a running one disagreed by up to the convergence
  tolerance — and once flux weights reach reactivity, that difference reaches total reactivity.
  Removed. Iterating far below tolerance was measured and does not work: at 1e-15 the iteration
  never converges, and after 4000 iterations from two histories 590 of 961 assembly weights still
  disagree in their last bits. `solve()` is now pure, with an exact bitwise memo for an unchanged
  core. The cost is recorded honestly under *Known issues*.
- **Steam-table correlations were extrapolated below their fitted band.** Saturated vapour density
  read −46% at 14.7 psia, so a vessel in blowdown was told its dome held half the steam it did;
  liquid enthalpy read −10.35%. The vapour density fit is now four segments, each fitted through the
  steam-table density at both ends, and liquid enthalpy is a T_sat quadratic: **+0.00% and −0.71% at
  14.7 psia**. Everything at and above 800 psia is bit-identical, so every calibration quoted
  against the operating band still holds to the last bit.
- **Suppression pool capacity was a universal hardcoded 3,400,000 kg.** It now follows the basin the
  player built, by a bounded flood fill from the controller: one contiguous body, refused outright if
  it runs past the 24-block survey box, capped at 32,768 blocks, 1000 kg per block, minimum 64.
  `SuppressionPool` measures its suction floor and level fraction against that inventory, so a small
  pool behaves like a small pool instead of sitting permanently below a floor sized for a BWR/6.
- **`VesselState.canHoldPressure()` had no consumer**, so removing the head was a refuelling gate and
  nothing else — a player could open the head at 20 psig and pressurise normally through an opening
  the size of the vessel. The controller now sets a steam discharge path of 350 kg/s per psi of
  gauge pressure, sized as choked flow through a ~32 m² opening against ~1940 kg/s of rated steam
  generation. Measured 20.0 → 29.5 psig over ten minutes with the head on, pinned at 0.0 psig with
  it off. **[mod-side, not reproducible.]**
- **Eleven of the twelve period meters were unreachable from Lua.** A BWR control room has one per
  range instrument — 4 SRM, 8 IRM — and exactly one was exposed, on an SRM, which rolls over long
  before the plant reaches a power anyone operates at. A player was asked to fly a startup on period
  with no period. All twelve are now readable as period, inverse period, startup rate and meter
  status, plus a bulk `getPeriods()`. They are raw measurements: nothing says what a period means or
  what is short.
- **`runPeripheralCheck` could pass by crashing.** It now fails by default, via a shutdown hook
  installed at class load and removed only on success, so a crash during plant construction still
  exits non-zero. It also gained an off-thread probe pass that verifies the main-thread marshalling
  actually marshals. **It has still never been run.**
- **The `:core` design-rule guard grew field scanning and explicit exemptions**, going from 976
  methods across 37 classes to 1066 methods and 747 fields across 46.
- **Carried forward from the previous pass, and still holding:** `ReactorState` gained `hydrogenKg`
  and `protectiveOxideArealKgPerM2` because a reload used to zero the hydrogen counter and reset the
  oxide film to its grown value, which cancels an in-progress quench spike — cracking that film is
  the whole mechanism. `DamageBookkeepingTest.test03` still measures what it cost (98.29 kg of
  hydrogen still to come from the spike live and on a correct reload, 58.39 kg without the film —
  41% of the spike lost) and keeps it as the reason the two fields exist.

### What the regression suite can and cannot reach

`CriticalDefectRegressionTest` covers seven of the ten criticals. Three — the multiblock rebuild on
chunk load, the recirculation pump's energy capability, and the `CMD_ROD_STEP` overflow in the
peripheral command path — are mod-side and have **no coverage at all**. `test08` asserts that
`core/` cannot even load `net.minecraft`, then says so in a printed note, so that nobody can come to
believe by accident that the physics tests cover them. They need a mod-side test source set, which
does not exist.

## Known issues

The first four are the ones that decide what this project is.

1. **Never run in a world.** Still the single largest unknown, and it did not move this pass.
   `runData` boots the mod and constructs every registry; it does not tick, does not place a block
   and does not load a model. Ticking, structure validation, NBT round-tripping in a live chunk,
   block placement, GUI interaction and peripheral attachment are correct only by compilation and by
   reading. **`:mod` has no test source set**, so this is not a gap in coverage — it is the absence
   of any mod-side coverage at all.
2. **No `@LuaFunction` has ever executed**, and no GUI screen has ever been drawn. The peripheral
   surface is the product. `runPeripheralCheck` was built to exercise it in a real world and has
   never been run.
3. **No model or texture has ever been loaded by a client.** The audit proves every reference
   resolves to a file; it cannot prove a model renders or that a 16×16 PNG is not visually garbage.
   All block textures are flat-colour placeholders pending real artwork, by design for now.
4. **No `BlockEntityRenderer` exists.** No head-off rendering (SPEC §14 step 10), no visible fuel in
   the core, no moving parts anywhere.

Open defects and modelling gaps. Item 5 is the one audit defect left unfixed; items 6, 7 and 8 are
things the fix work turned up or created and are **not** among the 102, so do not reconcile these
numbers against that count:

5. **`CoreLoading.heatPerFissionScaleFactor()` is defined and never called.** Deliberately out of
   scope for this pass. SPEC 2.1 says `heat_per_fission_mev` controls thermal output and
   `FuelTypeSpec` refuses an entry that omits it, but every thermal power figure in the model is
   `powerFraction * ratedThermalMW` — the fission rate is normalised to rated, so MeV per fission
   cancels and a datapack fuel quoting double the energy per fission produces a bit-identical core.
   The missing step is one multiplication at the single place a power fraction becomes megawatts.
   The multiplier is written, documented and ready; nothing calls it.
6. **There are no APRM period meters.** `ReactorCore` builds `PeriodMeter`s for the 4 SRM and 8 IRM
   channels only, so above IRM range there is no period reading at all — which is the power range,
   where a player running on APRM has nothing to differentiate.
7. **The nodal solve costs 19–25 ms once per second per reactor, against a 50 ms tick budget.** This
   is the price of determinism and it is a real, open decision rather than a defect. The lever that
   does not reintroduce history dependence is *solving less often than 1 Hz*
   (`CoreConfig.nodalSolveIntervalTicks`), since the quasi-static argument the model already rests on
   is what makes a frozen shape legitimate between solves. A core nobody is touching costs nothing,
   via the memo — but a running plant has void creeping every second, so a running plant pays every
   second. Nothing has measured what several plants in one world does, because nothing has run in a
   world.
8. **The suppression pool basin survey counts contiguous water, not dug-out water.** Open water is
   refused — a body that runs past the survey box has no far wall the controller can see, which also
   closes the one-block-channel-to-the-ocean cheese. But a natural pond that fits entirely inside the
   24-block box and touches the controller is indistinguishable from a basin somebody dug, and will
   be counted as the player's pool. The survey itself is a 49³ hardware sweep plus a flood fill
   bounded at 32,768 blocks; it is throttled to every 600 ticks once formed and 100 while unformed,
   which is fine on paper and has never been profiled in a game.
9. **Two-node thermal model cannot represent partial uncovery.**
   `SevereAccidentEscalationTest.test01` measures and documents this: clad temperature after 600 s
   barely moves between 100% and 5% covered, then jumps at 0%. Real cores uncover hot channels first.
   Every accident in this model is a whole-core accident.
10. **The late severe-accident stages are absent, by name.** SPEC §8.1 stages 4 (cladding failure and
    gap activity release), 6 (RPV lower head failure) and 7 (molten core-concrete interaction) have
    no class. Past melt the two-node model keeps integrating and reports clad temperatures that are
    bookkeeping, not physics. SPEC §8.2's "very late (post-relocation)" reflood branch is likewise
    not implemented — `QuenchSpikeRefloodTest.test09` asserts the gap rather than the behaviour.
11. **Containment does not exist** (SPEC §16). The pool reports uncondensed steam for it to consume
    and `FuelThermal` can make 2652 kg of hydrogen; nothing receives either. Inerting, deflagration
    and venting are all absent, so the accident chain ends in a number rather than a consequence.
12. **`ReactorStructure.measureInterior`** expands along single axes from a seed block, so it assumes
    a rectangular interior. An irregular cavity may measure wrong rather than be rejected.
13. **One deprecation note in the build**: `gui/client/LatticeGridWidget.java` overrides a deprecated
    API (`onClick(double, double)`). It is a `Note:`, not a warning, and the build is still
    warning-free, but it should be moved to the current signature.
14. **Copyright holder in the LICENSE files is a placeholder**: "the Realistic BWR contributors".
15. **`fuel_assembly` has one texture and one flat item model** — no visual distinction between fuel
    types or burnup states, though the data component tracks both.

## Next steps

1. **`./gradlew :mod:runClient`** and place every block. Still the highest-value action available:
   it invalidates Known issues 1, 2 and 3 in one sitting, and it is the only way to find out whether
   five GUI screens that have never been drawn actually work. Expect problems no amount of JSON
   cross-referencing can predict.
2. **Attach a computer to a controller and take the core critical from Lua.** This is the action
   that changed status this pass: before, it was impossible — no rod command, no flow command, no
   readable count rate. Now it is merely untried. Withdraw rods on a 1/M plot, watch the SRMs come
   up off 41 cps, take it critical, and find out whether the peripheral surface is actually
   sufficient to write the scram program the design rule says the player must write.
   `./gradlew :mod:runPeripheralCheck` is the cheap first half of this and has never been run.
3. **Re-run the four `runData` permutations.** The soft-dependency proof in this document predates
   ~100 fixes and is the one piece of evidence here that is knowingly stale.
4. **Decide the nodal solve interval** (Known issue 7). ~25 ms per second per reactor is affordable
   for one plant and is not obviously affordable for several.
5. **Model partial uncovery** with an axial or channel-resolved clad temperature. Known issue 9 is
   the largest remaining physics fidelity gap, and it makes every accident coarser than the rest of
   the model deserves.
6. **Containment**, so hydrogen and uncondensed steam have somewhere to go.
7. **A `BlockEntityRenderer`**, starting with the vessel head, which is the one SPEC §14 step 10
   explicitly names and the only reason that step is not done.
