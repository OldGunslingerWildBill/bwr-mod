# Build Status

## 2026-09-25 — 0.1.0-alpha.13: cached machine rendering

Built `mod/build/libs/mod-0.1.0-alpha.13.jar` (**24,492,332 bytes**).
SHA-256: `D63E04A6295A71233F9CB7D6EB7E600110C78F44BF670BE7CFFDBB100E69A150`.
Minecraft 1.21.1 / NeoForge 21.1.248 / Java 21. GUI protocol **10**.

- Stationary reactor shells, cooling machines, condensers and condensate tanks
  reuse GPU buffers; tower fans rotate independently using shared geometry.
  Suppression-pool water and header components also reuse cached meshes.
- Large machines use their full bounds for visibility and distance checks.
  The tower-top view passes even when its controller is outside the frustum;
  looking away produces zero machine renderer calls.
- The seven-stage client benchmark passed. In the same-build comparison,
  uncached submission took **31.88 ms** of renderer CPU work per frame;
  cached samples took **0.161 / 0.148 ms**. Seven shared meshes used
  **25,211,232 vertex bytes**, with zero uploads during steady rendering.
  A real resource reload rebuilt them once, and explicit cleanup freed all entries.
  This controlled scene disables particles and does not measure GPU execution time
  or predict FPS in a player's modpack. See [MACHINE-RENDERING.md](MACHINE-RENDERING.md).
- Cooling, vessel, condenser, condensate-tank and suppression-pool client checks
  passed. Reviewed captures cover model colors, day/night lighting, moving fans,
  vapor, reactor open-head/breach/repair, tank sizes and condenser fit.
- **56/56 required Minecraft GameTests passed**, including the existing machine,
  plumbing, fluid-accounting, teardown and control regressions. Dedicated-server
  startup also passed with the client-only cache classes present in the mod.
- `:mod:build`, design guard, asset audit (**2,681 JSON files, zero problems**),
  whitespace and JAR packaging checks passed. No development fixtures or optional
  mod implementation classes are bundled. The full core physics suite was not
  repeated for these client rendering changes.
- Existing simulation and saves are unchanged. Shader packs and replacement
  renderers have not been compatibility-tested.
- Source update for GitHub `main`. This source push does not publish a GitHub
  Release or upload the built JAR.

Logs: `build-gpu-alpha13-benchmark-final.log`, `build-gpu-alpha13-cooling.log`,
`build-gpu-alpha13-vessel.log`, `build-gpu-alpha13-condenser.log`,
`build-gpu-alpha13-tank.log`, `build-gpu-alpha13-suppression.log`,
`build-gpu-alpha13-final.log`, `build-gpu-alpha13-assets.log`,
`build-gpu-alpha13-jar.log`. Captures: `mod/run/turbineModelCheck/`.

## 2026-09-25 — 0.1.0-alpha.12: changing wind and winding tower vapor

Built `mod/build/libs/mod-0.1.0-alpha.12.jar` (**24,485,131 bytes**).
SHA-256: `5EB9A0F24A516360213D9DD39AC7BE27BC548E42E14470B3E0444D39CE93B395`.
Minecraft 1.21.1 / NeoForge 21.1.248 / Java 21. GUI protocol **10**.

- Both cooling towers now follow smoothly interpolated random gusts. Wind
  changes with height and reaches higher vapor layers later; particle momentum
  produces bends and winding shapes. Calm periods restore taller upright plumes.
- Continuous white emission, fading, particle settings and the shared 1,200
  particle cap remain. This patch changes client visuals only.
- Minecraft cooling client check passed. All ten captures were generated;
  four wide views taken eight seconds apart were visually reviewed and show
  sideways drift, an S-shaped bend and a return to upright plumes. The running
  towers used **1,015–1,025 vapor particles** across those four captures.
- `:mod:build`, design guard, asset audit (**2,680 JSON files, zero problems**),
  whitespace and JAR packaging checks passed. No development fixtures or
  optional-mod implementation classes are bundled.
- The server GameTests and core physics suite were not repeated for this
  client-only follow-up; the alpha.11 results below cover its included turbine work.
- Source update for GitHub `main`, including the alpha.11 turbine rebuild.
  This source push does not publish a GitHub Release or upload the built JAR.

Logs: `build-vapor-drift-alpha12.log`, `build-vapor-alpha12-assets.log`,
`build-vapor-alpha12-jar.log`. Visual captures:
`mod/run/turbineModelCheck/cooling-check/`.

## 2026-09-25 — 0.1.0-alpha.11: Terry RCIC/HPCI rebuild

Built `mod/build/libs/mod-0.1.0-alpha.11.jar` (**24,482,399 bytes**).
SHA-256: `A0ED30D28512603E351462B2D582CD0337EFA54A0FE487BAE6FCB67684132C81`.
Minecraft 1.21.1 / NeoForge 21.1.248 / Java 21. GUI protocol **10**.

- Original Blender RCIC (5 × 4 × 5) and HPCI (7 × 5 × 5) turbine/pump skids,
  19,832 and 22,480 source triangles, four round process flanges, CC panels and
  dedicated operating screens. Both `.blend` sources and exporters are included.
- Existing `modern=false` CAD assemblies retain their old ports, footprint and
  saved controls. New placements use the Terry layouts. Only the controller owns
  simulation; scheduled full-footprint validation no longer repeats for every child.
- **56/56 required Minecraft GameTests passed.** The assembly suite now includes
  **200 scenarios** spanning both generations and all four rotations: obstruction,
  geometry, one-item drops, old-property-free NBT restoration, Mekanism-compatible
  water input, wrong-fluid/face rejection, CC access, GUI command ownership,
  snapshot round trips and stale-capability invalidation after teardown.
- **Four complete turbine plumbing scenarios passed** (old/new RCIC and HPCI).
  Each generation delivered identical water and used identical steam in the same
  test plant: about 2,475.58 kg water / 224.56 kg steam for RCIC and 18,940.49 kg
  water / 1,027.89 kg steam for HPCI. Shared ledger, tank debit and exhaust-break
  checks passed. These are fixture totals, not new published equipment ratings.
- Real NeoForge model baking verified **1,412 turbine cell states** and both
  inventory models, alongside the existing pump-model checks. In-game captures
  of both connected skids and both synchronized screens were visually reviewed.
- Blender export verification passed for **562 generated assets**. Asset audit:
  **2,680 JSON files, zero problems**. Build/design guard, whitespace and JAR
  packaging checks passed; no dev fixtures or optional-mod classes are bundled.
- Reactor kinetics and pump equations were not changed; the full core physics
  suite was not repeated for the three new pressure readout getters.
- Included in the alpha.12 source update to GitHub `main`.

Logs: `build-terry-gametest.log`, `build-terry-final.log`, `build-terry-assets.log`,
`build-terry-jar.log`, `build-terry-models.log`, `build-terry-icons.log`.
Visual captures: `mod/run/turbineModelCheck/terry-check/`.
Connection/migration guide: [TERRY-TURBINES.md](TERRY-TURBINES.md).

## 2026-09-25 — 0.1.0-alpha.10: continuous white tower vapor

Built `mod/build/libs/mod-0.1.0-alpha.10.jar` (**22,726,911 bytes**).
SHA-256: `38C0A2AD9D3DEDF3694D79F416C3464F6AFA3FBBCDAF6A564382596F04B9BBA9`.

- Both towers emit overlapping layers every client tick. Larger white sprites
  keep a denser core before dispersing. Mechanical emission is spread across
  fan outlets; natural emission is concentrated above the stack mouth.
- Vapor uses a shared translucent batch without depth writes, preventing
  overlapping clouds from cutting one another into circular slices. Solid
  scenery still depth-tests. Blender sprite sources and textures were updated.
- Shared 1,200-particle cap, reduced/minimal settings, wind, rise, fading and
  actual heat-rejection gating remain. No machine physics or GUI protocol change.
- Minecraft cooling client check and screenshot review passed: 1,031 particles
  across both running towers, continuous plumes, correct fan speed and GUI.
- Build/design guard, 2,401-JSON asset audit, JAR packaging audit and whitespace
  checks passed. Full reactor tests were not rerun for this visual patch.
- Source update for GitHub `main`. GUI protocol remains 9. This source push
  does not publish a GitHub Release or upload the built JAR.

Logs: `tmp/vapor-alpha10-final-client-build.log`, `tmp/vapor-alpha10-blender.log`,
`tmp/vapor-alpha10-assets.log`, `tmp/vapor-alpha10-jar.log`.

## 2026-09-25 — 0.1.0-alpha.9: fan direction and animation speed

Built `mod/build/libs/mod-0.1.0-alpha.9.jar` (**22,727,745 bytes**).
SHA-256: `BF3E08B066CFA31835F66F7EC67432CF2F2CD040F63E5F465EBD15413B5C7180`.

- Reversed the alpha.8 mechanical-tower fan animation and raised full-speed
  rotation from 30 to 40 visual RPM. Actual powered-speed scaling and smooth
  wrap/interpolation are retained. Cooling physics and GUI protocol 9 unchanged.
- Minecraft cooling client check passed, including measured +6 degrees per
  tick at 50% speed, sustained vapor and all seven existing visual captures.
- `:mod:build`, design guard, JAR audit and whitespace checks passed.
- No full physics/GameTest rerun for this animation-only follow-up; the
  alpha.8 213/213 physics and 56/56 GameTest results below remain applicable
  evidence for the unchanged simulation code.
- Source update for GitHub `main`, including all alpha.8 changes. This source
  push does not publish a GitHub Release or upload the built JAR.

Logs: `tmp/fan-alpha9-client-build.log`, `tmp/fan-alpha9-jar.log`.

## 2026-09-25 — 0.1.0-alpha.8: suppression tanks and tower vapor

Built `mod/build/libs/mod-0.1.0-alpha.8.jar` (**22,727,751 bytes**).
SHA-256: `61A9B6CFD96BDB11DF35A5C4595E418E54E8040BE908C88242C1E008C3D11BC3`.
Included in the alpha.9 source update; not published as a GitHub Release.
Minecraft 1.21.1 / NeoForge 21.1.248 / Java 21. GUI protocol **9**;
update client and server together.

- Enclosed suppression tanks form from concrete floor, walls, roof, controller
  and directional ports. New tanks start empty. Dedicated steam inlets accept
  BWR steam pipes and optional Mekanism tubes; actual steam heats the water and
  adds condensate. Shared budgets prevent double allocation. Water, heat and
  steam inventory survive breach, repair and controller replacement.
- Live relief routing responds to pumped filling and disconnected lines.
  A real ocean intake → powered makeup pump → exchanger → discharge test
  confirms isolated primary/secondary water accounting and finite backpressure.
- Mechanical fan rotation is corrected. Both operating towers emit Blender-
  rendered vapor sprites with buoyant rise, changing drift, spreading and fading.
  Effects are client-only, respect particle settings and share a 1,200-puff cap.
  No pipe block tickers were introduced; transport uses cached topology.
- **213/213 full core acceptance tests passed** (879 seconds).
- **56/56 Minecraft GameTests passed**, including the new enclosed-tank,
  BWR/Mekanism steam, ADS fill/disconnect and ocean cooling regressions.
  Existing runtime checks also passed.
- Dedicated server without optional integrations: **PASS, zero problems**,
  including eight permission scenarios. CC faces cover 45 machine block types
  in the integration runtime.
- Actual Minecraft client checks passed for enclosed tank/GUI and operating
  towers. Final plume screenshot recorded 769 particles across both towers.
  Screenshots and reproducible Blender sources are under `art/models/`.
- `:mod:build`, design guard, exported-asset consistency and JAR audit passed.
  Asset audit: **2,401 JSON files, zero problems**. Particle sprite references
  are now audited, with a deliberate missing-sprite failure check. No bundled
  optional-mod implementations or development fixtures. Whitespace checks passed.

Logs: `tmp/tank-core-full.log`, `tmp/tank-gametest-release.log`,
`tmp/tank-no-optional.log`, `tmp/tank-suppression-client.log`,
`tmp/tank-cooling-client-final.log`, `tmp/tank-assets-final.log`,
`tmp/tank-particle-audit-negative.log`, `tmp/tank-jar-final.log`.

## 2026-09-24 — 0.1.0-alpha.7: 12,500 buckets per tritium rod

Built `mod/build/libs/mod-0.1.0-alpha.7.jar` (**22,596,020 bytes**).
SHA-256: `2F7C5983D571349991FBDDF5635BB54B11A7D5C6CA48C1E8C0074E97BA57D168`.
Validation for the user-requested alpha.6–alpha.7 source update to GitHub
`main`. This source push does not publish a GitHub Release or upload the JAR.

- One completed tritium rod gives 1,250 samples and one casing. Each sample
  still produces 10,000 mB in the powered Chemical Oxidizer; total **12,500,000
  mB / 12,500 buckets**. Seven-day rated-local-flux exposure is unchanged.
- Large harvests split into legal stacks, including inventory overflow drops.
  Existing unharvested rods get the new yield; already harvested samples retain
  their per-item value. GUI protocol remains 8; update client and server together.
- **52/52 Minecraft GameTests passed** with Mekanism, Generators and CC:Tweaked.
  New checks conserve 1,250 samples plus a casing across inventory and drops,
  reject oversized stacks and repeat harvesting, and pin the rod yield against
  actual Chemical Oxidizer output. Runtime gate also passed its existing
  machine, transport, tank and peripheral scenarios.
- `:mod:build` and design guard passed. Asset audit: **2,393 JSON files, zero
  problems**. JAR audit passed with no bundled optional-mod implementations or
  development fixtures. Whitespace checks passed.
- No reactor physics, irradiation timing, recipe gating or optional-mod loading
  changed in alpha.7; the alpha.6 compatibility and focused physics results
  below remain the latest evidence for those paths.

Logs: `tmp/fuel-alpha7-{gametest-build,assets,jar}.log`.

## 2026-09-24 — 0.1.0-alpha.6: bulk tritium and multi-day irradiation

Built `mod/build/libs/mod-0.1.0-alpha.6.jar` (**22,595,615 bytes**).
SHA-256: `522ED83B3D669895E9188BF3B0EAF77F878DED9F2BE826DF0C3054CF851C6746`.
Local patch; not committed, pushed or published as a GitHub Release.

- At rated local fission flux: silicon 24 operating hours, antimony-beryllium
  48 hours, cobalt 72 hours and tritium 168 hours. Reduced flux increases the
  required time; shutdown and offline time do not advance exposure. Saved
  exposure seconds are retained, with progress recalculated for the new targets.
- One completed tritium rod yields 64 samples and one reusable casing. Each
  sample produces 10,000 mB of `mekanismgenerators:tritium` in a powered Chemical
  Oxidizer: 640,000 mB per rod. The recipe requires both Mekanism and Generators.
- GUI protocol remains 8; update client and server together. No pipe ticking
  or additional transport rebuilds were introduced.

Verification:

- Focused fuel acceptance tests: **6/6 passed**. The full 212-test physics
  catalogue was not rerun for this patch; earlier full-run evidence is below.
- **51/51 Minecraft GameTests passed** with Mekanism, Generators and CC:Tweaked,
  including actual powered Chemical Oxidizer processing of two samples,
  unpowered rejection, exact output, extraction and no duplicate production.
- **51/51 Minecraft GameTests passed** with base Mekanism and CC:Tweaked but
  without Generators; the tritium recipe was correctly absent.
- Dedicated server without all three optional integrations: **PASS, zero
  problems**, including explicit recipe-absence verification and eight
  assembly-permission scenarios. The full GameTest harness cannot run without
  CC:Tweaked because an older fixture references `LuaException` unconditionally;
  that attempted run failed before tests and was replaced by the dedicated
  compatibility harness. Development fixtures are excluded from the JAR.
- `:mod:build`, design guard and whitespace checks passed. Asset audit:
  **2,393 JSON files, zero problems**. JAR audit passed: no bundled optional-mod
  implementations or development harnesses.

Logs: `tmp/fuel-alpha6-{build,gametest-final,no-generators,no-optional-server,assets,jar}.log`.

## 2026-09-24 — 0.1.0-alpha.5: fuel catalogue and specialty rods

Built `mod/build/libs/mod-0.1.0-alpha.5.jar` (**22,594,012 bytes**).
SHA-256: `D58BB9576F1AC770D1D55071B569D755172E729B785A9C8990B0CA676BD0C332`.
Deliver this JAR directly; the user now requests JAR-only downloads.

- 19 fuel definitions; original five presets retain their tuning. Natural
  uranium, 1.2/1.4/2.7/4.95/8/19.75% uranium, extra gadolinia, and lean/rich
  MOX, plutonium and thorium variants are added.
- Eight non-fuel cassettes: two fixed absorbers, three source types, three
  irradiation targets. Separate exposure component, tagged core persistence,
  local absorption/source integration, refuelling maps and single-use harvest.
- Separate 33-entry fuel creative tab. 34 Blender PNG renders, including the
  original assembly icon replacement; no dynamic inventory renderers added.
- GUI protocol **8**. Existing world fuel saves remain compatible; both sides
  must update. `FUELS-AND-RODS.md` documents recipes, experimental choices,
  simplified physics and primary references.

Verification:

- Full physics run: **210/211 passed** in 876.5 seconds. The only failure was
  the previous test's explicit five-preset expectation. Its expected catalogue
  now covers 19 entries and still pins each family's beta; the complete
  `FuelTypeSpec` group then passed **4/4**. All 211 checks have passing evidence
  across that run and the focused rerun; the long suite was not repeated after
  this test-only correction. Final source calibration also passed **5/5**
  `FuelVariety` tests.
- Minecraft **50/50** required GameTests passed, including the original 46
  regressions and four new fuel tests. Checks include loaded and pending NBT
  recovery, shuffling, wire snapshots, actual irradiation and no double harvest.
- Real client: all **19 fuel / 8 rod** names and texture overrides verified.
  Fuel tab screenshot: `art/models/fuel_icons/fuel_tab_ingame.png`. Screen CPU
  rendering averaged about **1.26 ms** in this local fixture; not a general FPS
  guarantee. Existing 33 machine inventory icon checks also passed.
- Dedicated server **with and without** Mekanism/CC:Tweaked: zero problems;
  eight assembly-permission scenarios passed in both configurations.
- Asset audit: **2,392 JSON files**, zero problems. JAR audit: **374 project
  classes**, no optional-mod implementation classes or development harnesses.
- `:mod:build` and the design guard passed. The subsequent user request
  authorizes committing and pushing all accumulated alpha changes to GitHub
  `main`; the complete scope since `b126650` is recorded in `CHANGELOG.md`.

Logs: `tmp/fuel-alpha5-{core,fuel-spec,build,final-build,gametest,client,peripherals,no-optional,assets,jar,blender}.log`.

## 2026-09-24 — 0.1.0-alpha.4: transverse condenser and hotwell makeup

Local patch and packaged test build; no commit, push or public release made.
Includes all earlier uncommitted alpha fixes.

- Rebuilt the two small oval side fittings as circular, open Blender flanges.
  They now accept water into the existing finite hotwell, retaining inlet
  enthalpy and leaving the cold/hot cooling inventories separate.
- New layout v4 is **7 x 6 x 9**, controller `[3,0,4]`, with **eight ports**.
  Snap facing is 90 degrees clockwise from the LP; the main pipes face the
  sides of the turbine hall. The world footprint remains nine across the
  shaft and seven along it. Inset makeup nozzles remain accessible between
  adjacent LP/condenser units.
- Archived v3 alongside v1/v2. Saved geometry, facing, inventories and old LP
  attachment behavior remain intact. Drain and re-place to upgrade.
- Updated hotwell GUI labels, compatible CC status aliases, documentation and
  the lightweight Blender-rendered inventory icon. GUI protocol remains 7;
  no ticking or per-tick rebuilds were added to pipes.

Verification on the final implementation:

- **206/206 core acceptance tests**, including makeup mass, heat, capacity,
  simulation, circuit isolation and reload (full suite: 883.2 seconds).
- **46/46 Minecraft GameTests**. New tests exercise adjacent LPs in all four
  rotations, every socket's clearance, a real powered makeup pump through BWR
  pipes, two-port shared reservation, hotwell persistence and stale handles.
  Existing audit, tank/CRD, MSIV and placement-permission regressions pass.
  Updated runtime harness transfers water through actual Mekanism Mechanical
  Pipes into both cooling and makeup sockets; v2/v3 save fixtures retain their
  original LP coupling. The first run exposed a hardcoded old-facing test
  coordinate; it was corrected before the final complete rerun.
- Real client: **eleven screenshots**, four renderer rotations, all 33
  lightweight icons, materials, circular fittings, green/red outlines, normal
  unsneaked snapping and adjacent LP clearance. Inspected the final views.
- Dedicated server **with** CC/Mekanism and **without** both: zero problems;
  eight assembly-permission scenarios pass in each configuration.
- `:mod:build` and design guard pass (251 mod source files). Export check:
  **30 resources, 179 occupied cells, eight ports, 18,492 mesh triangles**;
  also verifies circular makeup radii and unobstructed connector cells.
- Asset audit: **2,324 JSON files, zero problems**. JAR audit: **368 classes**,
  no development fixtures or optional-mod classes bundled; attribution passes.

JAR: `mod/build/libs/mod-0.1.0-alpha.4.jar` (**22,103,889 bytes**).
SHA-256: `15409B0B93CE618C2DD7E72C93E4D849971F59CA672061D1BBDDC1B2377A4ABB`.

Download ZIP: `../Realistic-BWR-0.1.0-alpha.4.zip` (**17,862,564 bytes**).
SHA-256: `AF9CE3A520CED724CC027C00B1FE91C99638047AF8AC181FBCDB0C1351E4017D`.
Includes JAR, installation/upgrade instructions, changelog, checksum and license
documents. ZIP CRC and extracted JAR hash verified against the tested artifact.

Evidence: `tmp/condenser-alpha4-{core,gametest,client,peripherals,no-optional,
export,assets,jar}.log`. Final images are under
`mod/run/turbineModelCheck/condenser-check/` and copied alongside Blender sources
in `art/models/condenser_ports/`. No additional manual player testing is claimed.

## 2026-09-24 — 0.1.0-alpha.3: condenser snapping and powered MSIV

Local patch; no commit, push or public release made for this request.

- New condensers occupy **9 x 6 x 7** blocks, widened in Blender with a centered
  LP exhaust seat and six full-size pipe flanges. Clicking an LP underside
  snaps the condenser beneath its actual controller and inherits its facing.
  A cached green/red box preview shows local placement validity. Placement
  checks the complete footprint before consuming the item or writing parts.
- Both old condenser layouts retain their meshes, cell indices, connections
  and saved inventories. Seven-block compact units still connect to their LPs.
  Drain and replace to upgrade to the wider model.
- MSIVs now start closed and require FE: **100 FE/t opening, 20 FE/t holding**,
  a **20,000 FE** buffer and receive-only ports on non-steam faces. Power
  exhaustion releases their four-second spring closure. Every exposed part
  accepts a CC modem; power telemetry is available through `bwr_msiv`.
  Legacy cube valves also require FE while retaining their old footprint.
- The condenser's inventory icon was rendered in Blender and keeps the
  two-quad creative-menu path. Pipe transport and GUI protocol 7 are unchanged.

Verification:

- **44/44 required Minecraft GameTests passed** on the final code, including
  all prior audit and tank/CRD regressions. New cases exercise four-direction
  snapping, block/entity rejection and item conservation, offhand menu
  pass-through, narrow-v2 save compatibility, FE conservation/persistence,
  four-second spring closure, actual CC calls on all 18 MSIV faces, and
  Mekanism Universal Cable recognition in every valve orientation.
- Dedicated servers with integrations and with both integrations absent:
  **zero problems** each; **eight** assembly-permission scenarios passed,
  including rejection of a snapped footprint crossing spawn protection.
- Real client: **nine** condenser screenshots, including green/red previews
  and a successful normal unsneaked placement beneath an existing LP turbine.
  Inspected previews and placed-model images; colors, fit and ports are intact.
  Model checks include **17,844** condenser quads in four rotations and all
  **33** lightweight creative icons.
- `:mod:build` and the design guard passed (**250 mod source files**).
  Exporter reproduces **26 resources**, **230 occupied cells**, **six ports**.
  Asset audit: **2,322 JSON files, zero problems**. JAR audit: **368 classes**,
  no bundled optional-mod classes or development fixtures; attribution passes.
- Core source is unchanged by this patch. Alpha.1's full **204/204** physics
  suite remains applicable; it was not rerun for this mod-layer patch.

Artifact: `mod/build/libs/mod-0.1.0-alpha.3.jar` (**21,832,018 bytes**).
SHA-256: `D299E562549C65C1573F977A4948A4EA8EEA1A9210410C3E1E056EAB911A1ADD`.

Evidence: `tmp/condenser-msiv-release.log`, `tmp/condenser-msiv-client.log`,
`tmp/condenser-msiv-peripherals.log`, `tmp/condenser-msiv-no-optional.log`,
`tmp/condenser-msiv-export.log`, `tmp/condenser-msiv-assets.log`,
`tmp/condenser-msiv-jar.log`. Screenshots:
`mod/run/turbineModelCheck/condenser-check/`; reviewed preview/placement images
are also in `art/models/condenser_ports/`.

## 2026-09-24 — 0.1.0-alpha.2: tank recovery and shared CRD supplies

Local patch; no commit, push or release made for this request.

- Breaking a formed condensate tank restores ordinary casing blocks. The mined
  part follows normal tool/creative drops, excess materials are refunded, and
  collision cells cannot create extra blocks. A saved restoration ledger handles
  unloaded sections without force-loading them. Water still needs draining first.
- Face-adjacent CRDs share their actual saved water/FE buffers. One water inlet
  and one energy inlet on separate bottom faces supply a full 17 × 17 grid.
  Sources must cover total consumption; wear, accumulators and rod movement
  remain independent. Six-face topology is cached until a structural, capability
  or chunk-availability change. Supply balancing does not rebuild the graph.
- Existing tanks and drive layouts need no replacement to receive these fixes.
  Reactor physics, model resources and GUI protocol 7 are unchanged.

Verification:

- **41/41 required Minecraft GameTests passed**, including all existing audit
  regressions and five new tank/CRD tests. Actual player mining covers correct
  and unsuitable tools, creative, root and port removal. The cross-chunk case
  restores **241 casings + one drop = 242 paid blocks**, including save/load of
  the pending restoration. The 289-drive test verifies supplies from two bottom
  faces, conservation, independent wear and no graph rebuild as time passes.
- Dedicated server with CC:Tweaked and Mekanism: **zero problems**, all seven
  assembly permission scenarios passed.
- Dedicated server with both optional integrations absent: **zero problems**,
  all seven assembly permission scenarios passed.
- Real client: four captured tank stages. Inspected assembled and dismantled
  screenshots confirm the cylinder becomes visible, recoverable casing blocks.
- `:mod:build` passed; design check scanned 245 mod source files. Assets:
  **2,320 JSON files, zero problems**. JAR audit: **362 classes**, zero bundled
  optional-mod classes and zero development-fixture entries.
- No core source changed since alpha.1's **204/204** complete physics suite;
  that prior result remains applicable and was not rerun for this mod-layer patch.

Artifact: `mod/build/libs/mod-0.1.0-alpha.2.jar` (**21,557,910 bytes**).
SHA-256: `AD89118AF40195E495092852DD389B48194BE5DA056A9A57FD575C1E660C7A4B`.

Evidence: `tmp/tank-crd-release.log`, `tmp/tank-crd-client.log`,
`tmp/tank-crd-no-optional.log`,
`tmp/tank-crd-assets.log`, `tmp/tank-crd-jar.log`.
Screenshots: `mod/run/turbineModelCheck/tank-check/`, including
`tank-ports.png` and `tank-dismantled.png`.

## 2026-09-23 — 0.1.0-alpha.1: physical SLC/ADS and creative-menu fix

**Local alpha candidate; not committed, pushed or published.** This includes
the transport/computer candidate below and all its regression fixes.

- Reproduced the reported creative-menu slowdown: detailed machine meshes
  rendered per slot gave 18.84 FPS / 51.17 ms screen CPU time. Blender-rendered
  inventory icons gave 560.83 FPS / 1.14 ms in the same local scene. Placed and
  held meshes remain intact. These are local measurements, not modpack promises.
- Blender-built SLC tank, powered positive-displacement pump, ADS division
  cabinet, ADS relief valve, borate charge and water outfall. SLC consumes
  finite water/boron through a physical source and normal RPV water-injection
  route, including feedwater cross-ties. Computer boron readouts reflect the
  delivered solution, not the former fixed-rate estimate.
- ADS division selection persists and only commands matching valves. The new
  valve resolves the actual steam inlet and shares its nozzle's allowance.
  Multiple ADS/pool reporters cannot claim or debit that steam twice. The
  final regression also checks nozzle closure and broken steam lines.
- Passive outfall tracks discharged water/heat and refuses blocked outlets.
  All six computer faces cover 44 machine block types. Pipes retain their
  event-invalidated geometry cache and have no block entities or tickers.

Final verification:

- **204/204 core tests**, 32 classes, 867.0 seconds; registration negative canary
  passed. No core source changed after the full suite.
- **36/36 required Minecraft GameTests** on final production sources, retaining
  F01–F13, GitHub #14–#18 and R01–R05 coverage. New SLC, ADS and outfall tests
  exercise actual capabilities, physical pipes and controller behavior.
- Dedicated server with CC:Tweaked/Mekanism: **zero problems**, seven permission
  scenarios. Both optional mods absent: **zero problems**, seven scenarios.
- Real client: six inspected hardware/panel screenshots; **33 valid two-quad
  inventory icons**; **7,032 occupied pump cell states**, 13 pump/compact models;
  existing turbine, condenser, cooling and pipe model checks passed.
- Assets: **2,320 JSON files**, 50 blocks, 52 items, **zero problems**. Alpha
  Blender export check: **144 files, zero stale outputs**.
- Final `:mod:build` passes; design check scanned 242 mod source files with no
  automatic protection logic. `git diff --check` is clean.
- JAR audit: **358 classes**, current license/attribution in both project JARs,
  zero bundled CC/Mekanism classes and zero development-fixture entries.

Artifact: `mod/build/libs/mod-0.1.0-alpha.1.jar` (**21,543,788 bytes**).
SHA-256: `36F62D281074905410F12D27B264EDFDAF10A14438AFB717A6B47EA58678B73A`.

Use Minecraft 1.21.1, NeoForge 21.1.248 and Java 21. Clients and server need
the same JAR (**GUI protocol 7**). Replace legacy SLC cubes to build the new
physical ports. See [ALPHA-HARDWARE.md](ALPHA-HARDWARE.md) for ports, tank
charging, ADS division control, the outfall and remaining modeling assumptions.

Ignored evidence logs: `tmp/alpha-first-gate.log` (full core suite),
`tmp/alpha-release-final.log` (final GameTests, integrated server and mod build),
`tmp/alpha-no-optional.log`, `tmp/alpha-client.log`,
`tmp/alpha-creative-before.log`, `tmp/alpha-creative-after.log`,
`tmp/alpha-assets-audit.log`, `tmp/alpha-jar-audit.log`.
Client screenshots: `mod/run/turbineModelCheck/alpha-check/`.

Earlier entries below are historical, including the previously unidentified
creative-menu slowdown and older artifact versions/counts.

## 2026-09-23 — First-release transport and computer candidate

- All R01–R05 findings now have repairs and permanent regressions: shared
  inventory simulation, preserved pool mass, live computer owner resolution,
  branch-specific MSIV isolation and inverse recirculation flow commands.
- CC:Tweaked connections cover all six faces of 41 machine block types,
  including modeled assembly parts. Every operation resolves the current owner
  on the server thread. New condenser, exchanger and hardware readouts are
  available; player-written controls remain independent.
- Water buffers carry mass and energy through BWR pipes, pumps, tanks, cooling
  towers and the RHR exchanger. Condenser backpressure responds to coolant and
  stored exhaust, and governs LP expansion. No automatic trip/control logic.
- Pipe blocks have no ticker or block entity. Event-invalidated topology
  collapses plain pipe runs into junctions; transfers resolve live valves and
  inventories. No timed rebuild or per-tick pipe walk. Chunk accessibility
  transitions, including LIGHT-to-FULL reactivation, reconnect cached routes.
- Fuel-grid draw calls and GUI readouts are batched. A controlled local run
  improved the 764-assembly panel from 109 to 205 FPS and the maximum panel
  from 67 to 148 FPS. Screen CPU time fell from 4.80/8.13 ms to about 0.80 ms.
  The exact reported 216-to-20 modpack drop was not reproduced.

Verification:

- **202/202 core tests**, 31 registered classes, 862.9 seconds. Registration
  negative canary passed. Core sources did not change after that full run.
- **32/32 required Minecraft GameTests**, retaining original F01–F13 and
  GitHub #14–#18 coverage. New tests include 1,000 time advances without a
  topology rebuild, live valve motion, rotated ports, chunk reconnect, thermal
  mixing/reload, logical inventory identity and retained modem calls.
- Dedicated-server integration/permission checks pass with CC:Tweaked and
  Mekanism installed and with both absent: zero problems; seven permission
  scenarios. Dynamic peripheral calls are exercised on/off the server thread.
- GUI benchmark plus condenser, cooling, suppression/exchanger and tank
  client harnesses passed. Screenshots of updated readouts were inspected;
  labels, values, buttons and tooltips render without overlap. Existing model
  checks pass on the real client.
- Asset audit: **2,210 JSON files, zero problems**. Design-rule check and final
  mod build passed. JAR audit checks current notices and nested core, and
  excludes optional dependencies and every development fixture.

JAR: `mod/build/libs/mod-0.1.0-SNAPSHOT.jar` (**20,558,190 bytes**).
SHA-256: `C990770119DECED3EF3C0769149C19AA8DC01BE06D8AE4E268FDF69302C7603D`.

Use the same build on server and clients (GUI protocol **6**). Thermal state
has legacy-save defaults. Third-party pipes can compare or discard temperature
components; use BWR pipes for a temperature-preserving plant loop. The condenser
vacuum model is a calibrated simplification without noncondensable gases.
See [PLANT-TRANSPORT.md](PLANT-TRANSPORT.md) for the operating assumptions.

This is a locally verified candidate based on `b126650`; this update has not
been committed, pushed or published as a GitHub release. Older entries below
are historical, including statements that R01/R03/R04/R05 were still open.

## 2026-09-23 — Natural suppression-pool cooling

- Formed, ticking pools cool gradually toward a fixed 25 C ambient without
  pumps, power or water injection. Surface area, wetted shell area and water
  inventory determine the rate. An exponential step prevents overshoot; no
  evaporation or water loss is introduced. Concrete wetted area follows level;
  legacy boundary areas are cached during structure validation.
- New Natural cooling GUI readout in kW and CC status fields passiveCoolingMW
  and ambientTemperatureC. GUI protocol is 5. Existing saved temperature needs
  no migration; passive loss does not increase the RHR heat-removal total.
- Full 105,000 kg example: 80 C to 77.340 C after one simulated hour, with no
  heat input. The coefficients are game calibration, documented in the guide.

Verification completed:

- **195/195 physics tests passed**, 867.3 seconds; 30 registered classes.
- **24/24 required Minecraft GameTests passed**, including cooling without any
  attached supply, inventory preservation, water-level geometry, reload and
  ambient bounds. Existing spray, RHR and legacy pump tests remain passing.
- Seven real-client checks passed. The new natural-cooling snapshot was
  asserted and the panel screenshot inspected; no label overlap.
- CC:Tweaked runtime: zero problems. Asset audit: 2,210 JSON files, zero
  problems. Mod design check, final build and packaged-JAR audit passed.
- Final JAR explicitly checked for the new core cooling method and GUI readout.
  No optional dependencies or development fixtures are bundled.

JAR: `mod/build/libs/mod-0.1.0-SNAPSHOT.jar` (**20,531,308 bytes**).
SHA-256: `CA75D1B2E355F8308EC7C46B4CCFF13EC60C483E600078C94AA05D6368BE25AB`.

This publication includes the preceding pool/pump changes, Blender sources,
generated models, regression tests and updated guides. GitHub main was at
685d14c when checked; no upstream merge was needed. R01/R03/R04/R05 remain
unrelated open findings. Older entries below retain their historical results.

## 2026-09-23 — Dry suppression multiblocks, spray and detailed water pumps

- Complete concrete shells auto-form empty. Pipe-delivered water sets the
  rendered level and quencher submersion; source blocks do not grant inventory.
  Saved metered inventory survives repair, replacement and reload.
- Regular fill or over-pool spray selected through the GUI or CC:Tweaked.
  A 6,000 kg header supplies at most 600 kg/s; capture is limited by incoming
  steam and supplied water's heat margin. Supplied water and captured steam
  remain in the basin with their heat. Blender rails, nozzles and risers are
  visible above the water. Generic supply remains 32 C; there is no stored
  containment atmosphere model. GUI protocol is 4.
- Blender pump replacements: VCT-inspired vertical circulating unit, 5x8x5;
  horizontal end-suction centrifugal makeup unit, 3x3x7. Detailed motors,
  coupling/guard, flanges, bolts, gauges, piping and skid. Layout version 3;
  prior placements preserve their geometry and connections with versioned assets.
  Flow and electrical ratings are unchanged. Manufacturer drawings are cited in
  COOLING-WATER.md; all new meshes are original.

Verification completed:

- Full core acceptance: **193/193 passed**, 873.6 seconds, 30 classes.
- Minecraft server: **23/23 required GameTests passed**, including pumped fill,
  finite spray, dynamic submersion, unformed-controller reload and old pump
  layout/capability/teardown compatibility.
- Real client: **seven scenarios passed**. Screenshots reviewed for basin water,
  spray rails/particles, both pumps, exchanger connections and GUI commands.
  Current and preserved pump models have no missing textures.
- CC:Tweaked dedicated-server runtime check: **zero problems**.
- Asset audit: **2,210 JSON files, zero problems**. Cooling (58) and suppression
  (24) generated files match their source exports.
- Final mod build passed; design rule scanned 215 Java files. Packaged-JAR audit
  checked 327 classes, current licenses and nested core. New spray assets,
  preserved pump assets and renamed inlet are packaged; no test fixtures or
  optional dependency classes are bundled.

JAR: `mod/build/libs/mod-0.1.0-SNAPSHOT.jar` (**20,530,163 bytes**).
SHA-256: `60AE39685760A8BF32A1067C9B185EF9E4CBA6F5B536D551D22160BB5CDAF654`.

These changes are included with the natural-cooling publication above.
R01/R03/R04/R05 remain unrelated open findings. Earlier entries record previous
build and publication states.

## 2026-09-22 — Concrete suppression basin and four-port RHR exchanger

- Blender-authored concrete wall/rim panels, recessed basin suction/return
  flanges and a four-port exchanger. Editable source and generated OBJ/MTL
  resources are under `art/models/suppression` and the mod's assets.
- Open tubs form automatically with a complete concrete shell, one controller,
  outward water ports and at least 64 water sources. Outside width/depth 5–25,
  height 4–16; at least 64 internal spaces below the rim are required.
- Modeled LPCI/RHR pumps support direct suction, reactor injection, direct
  pool return, and an exchanger loop returning to the same basin. Only the
  exchanger removes heat from a new concrete pool; its secondary water circuit
  is finite and separate. The existing dug-pool behavior remains compatible.
- R02 fixed: revalidation and capacity changes preserve condensed water and
  thermal inventory. Repair and controller replacement cannot refill the pool.
- GUI protocol 3; exchanger live flow/heat/inventory readouts and basin panels
  opened from wall ports. The cooling-water inlet still assumes 13 C;
  ordinary water pipes do not transport temperature. See SUPPRESSION-BASIN.md.

Verification completed:

- Full core acceptance: **191/191 passed**, 877.9 seconds, 30 registered classes.
  The subsequent outlet-temperature readout adjustment also passed the focused
  five-test exchanger suite and live client check.
- Final Minecraft server run: **21/21 required GameTests passed**, including
  automatic basin formation, break/repair, exclusive ownership, cached fluid
  handlers after replacement, actual RHR plumbing, separate inventories,
  interrupted loops, rotated secondary ports and fractional fill accounting.
- Dedicated server with CC:Tweaked/Mekanism installed and with both absent:
  **0 problems**. Seven existing permission scenarios passed.
- Real client: all four visual/panel scenarios passed; screenshots inspected.
  The disposable fixture resends completed chunk lighting after bulk placement
  and asserts daylight synchronization. Model materials and all facing states
  load; the pool and exchanger panels report live physical cooling.
- Asset audit: **2,203 JSON files, zero problems**. All 15 generated suppression
  assets match their Blender exports. Mod build, design rule (214 Java files)
  and packaged-JAR license/dependency/test-harness audit passed.

JAR: `mod/build/libs/mod-0.1.0-SNAPSHOT.jar` (**19,912,676 bytes**).
SHA-256: `5884131680D77B9A14BFB5314E6959FF4F09DA4327ED344E6C00850213FF6826`.

This publication includes the basin/exchanger implementation, Blender sources,
generated assets, tests and construction guide. The prior combined publication
is commit `619290a`. R01/R03/R04/R05 remain open; these checks do not claim those
unrelated findings are resolved.

## 2026-09-22 — Combined GitHub publication verification

- GitHub main was b77cf00, matching the local base; no open pull request or
  newer upstream branch needed merging.
- The publication snapshot includes all accumulated models and gameplay work,
  including the rounded reactor vessel and compact fuel/CRD layouts.
- Reran the mod build, test registration (29 classes / 186 methods), and all
  18 required Minecraft GameTests successfully. Existing hardware checks passed.
- Resource audit: 2,183 JSON files, zero problems. Packaged-JAR audit passed.
- The most recent complete physics run remains 186/186 passing; the published
  GitHub workflow runs the full suite again from the committed snapshot.
- Historical entries below describe the state at their original verification
  time. The five R01–R05 re-audit findings remain unresolved.

## 2026-09-22 — Rounded pressure vessel appearance

- Original Blender components: barrel, dished lower head/skirt, proportional
  domed head, bolted flange, welds and adapters to existing player-placed ports.
- One controller renderer supports square and rectangular envelopes. The
  rectangular build, collision and selection boundary remains; holding a
  vessel block shows its outline. See PRESSURE-VESSEL.md.
- Formation/break/repair and controller removal update shell visibility.
  Live NeoForge data packets now update the same client mirror as chunk tags.
  Ordinary vessel and pipe blocks gain no tickers or block entities.
- 18/18 required GameTests passed, including compact-core and original audit
  regressions; the existing pump, valve, condenser, cooling and tank checks passed.
- Actual client: eight scenarios passed across four sizes, head opening,
  broken/repaired shell, controller removal and material resolution. Screenshots
  inspected; reference preview is `art/models/reactor_vessel/in-game.png`.
- Export verification: 24 generated files matched. Resource audit: 2,183 JSONs,
  zero problems. Build/design gate and packaged-JAR audit passed.
- Core physics is unchanged in this pass; the earlier 186/186 full core result
  below remains the latest physics run.

JAR: `mod/build/libs/mod-0.1.0-SNAPSHOT.jar` (19,786,248 bytes).
SHA-256: `62D001C76ABB25D848820FE75DD418C997C3D6A89C91498FC6C98C07A379879A`.

GitHub #1–#13 and #15–#18 now have `fixed` and `pending-push` labels.
#14 has `pending-push` only, pending hosted CI publication/execution. Issue
states remain open. The code and model changes have not been pushed in this pass.
The separate R01–R05 re-audit findings remain outstanding.

## 2026-09-22 — Compact logical fuel / physical CRD layout

- New 17 × 17 vessels: 764 individually tracked fuel assemblies, 185 physical CRD blocks.
- Symmetric masks for every supported footprint; minimum 88/21, maximum 1,476/357.
- Versioned saves preserve legacy layouts, fuel exposure and rod demand. Compact
  controllers retain their formed footprint to preserve physical blade IDs.
- Shared horizontal CRD supply manifolds conserve water/FE; individual hardware
  condition and continuous rod motion remain active.
- Explicit fuel/drive maps in GUI protocol 2 and CC `getCoreLayout()`.
- Refuelling and reactor summaries fit the larger layouts. Internal RIPs mount
  in the unused corner spaces instead of occupying required drive columns.
- See [COMPACT-CORE.md](COMPACT-CORE.md) for construction, migration and sizing.

Verification:

- Compact core tests: all 289 supported width/depth combinations, reference/max
  inventory and physics restore, **3 passed**.
- Minecraft: **18/18 required GameTests passed**, including original audit
  regressions, actual CRD bindings, menu packet round trips, legacy broken saves,
  peripheral fuel recovery and continuous rod travel across reload.
- CC/Mekanism installed and absent: peripheral/startup checks **0 problems**,
  seven permission scenarios passed.
- Actual client: reference and maximum fuel/rod maps, refuelling screen, and
  commands to the last physical drive passed; screenshots inspected.
- Build, no-protection-logic gate, resource audit and JAR packaging audit passed.
- Full `:core:check`: **186/186 passed**, 881.9 seconds, 29 registered classes.

JAR: `mod/build/libs/mod-0.1.0-SNAPSHOT.jar` (19,609,303 bytes).
SHA-256: `3993EA7E474BE704B423C277322B344EA50B469142599A8EEB937A11AEBD380D`.

The separate R01–R05 re-audit findings remain tracked in PHASE-ONE-REAUDIT.md.

## 2026-09-22 — Intake, cooling-tower, LP exhaust and automatic tank revision

- Screened intake parts retain water through waterlogging. Two exterior source-water
  sides qualify; a solid lakebed is allowed. Isolated waterlogged parts cannot supply
  themselves. The passive screen no longer displays motor/fan or cooling-tower rows.
- Both towers were rebuilt in live Blender using real tower photo references.
  Natural draft: concrete panel/pour detail, supports, walkway and distribution piping.
  Circular induced draft: 19 animated fans, stacks, louvers, deck, rails and ladder.
  Original editable Blender files and deterministic mesh exports are retained.
  Existing towers preserve their saved assembly occupancy; ports and ratings are unchanged.
- LP turbines have no water flange/capability or internal condensation. Residual
  exhaust enters the matching compact condenser six blocks below, with the same
  facing. Missing/full condensers block LP flow. Old LP water migrates without
  losing fractional amounts when condenser capacity is available.
- Complete player-built tank boxes automatically become cylinders: square odd
  widths 3–15, height 3–24. A hollow shell is recommended. Formation preserves
  combined available water and the exact number of paid blocks; the panel is
  now read-only. Existing single-block and assembled tanks remain compatible.

Validation of the resulting production sources:

- Full `:core:check`: **183 passed, 0 failed**, 867.1 seconds, 28 registered classes.
- `:mod:runTurbineGameTest`: **all 15 required tests passed**. Existing audit
  regressions retained; runtime checks passed 73 pump, 16 power-module, 12 valve,
  5 MSIV, 10 condenser, 27 cooling and 9 tank scenarios.
- New regressions cover submerged placement/teardown water retention, solid
  lakebed suction, isolated screen rejection, old tower layouts, completed/missing
  tank roofs, obstruction rejection, exact block refunds, fractional tank water,
  missing/full/dry/cooled condensers and cached condenser-port reconnection.
- Dedicated peripheral checks: **zero problems** with integrations installed and
  with both `-PbwrNoCC -PbwrNoMekanism`; **seven spawn-protection scenarios** pass
  in each, including automatic tank formation.
- Actual Minecraft client checks pass for the towers, all 19 rotors, ports,
  underwater screen placement, passive intake GUI, automatically formed
  minimum/medium/maximum tanks and the read-only tank GUI. Screenshots reviewed
  and copied into the model directories. No player world was used.
- Both changed asset exporters pass `--check`. Asset audit: **2,175 JSON files,
  zero problems**. Final JAR audit: **306 class files**, notices retained, no
  bundled optional integrations or development harnesses.
- Final standard `build -x :core:acceptance` and `git diff --check`: **passed**.
  The full core suite above was run separately; production core sources were stable.

Artifact: `mod/build/libs/mod-0.1.0-SNAPSHOT.jar`, **19,602,804 bytes**.
SHA-256: `3589F8041CA8B726DD9400A0BAF4C994D9000A72DE5F46A7C84370CCB7908E03`.

Logs: `tmp/cooling-revision-core-full.log`, `tmp/cooling-revision-runtime.log`,
`tmp/cooling-revision-no-integrations.log`, `tmp/cooling-revision-wet-client.log`,
`tmp/cooling-revision-tank-client.log`, `tmp/cooling-revision-final-build.log` and
`tmp/cooling-revision-jar-audit.log`. This revision does not resolve the separate
R01–R05 re-audit findings recorded below. Water temperatures remain the existing
design assumptions, and condenser vacuum is not dynamically simulated.

## 2026-09-21 — GitHub issues #14–#18 fixed; original regressions retained

See [PHASE-TWO-FIXES.md](PHASE-TWO-FIXES.md) for the five fixes and measured
performance, and [TESTING.md](TESTING.md) for the F01–F13 regression map. Added
push/PR verification, a test-registration guard, pump-route caching, loaded-only
binding scans and a formed-reactor registry independent of jet-flow surveys.

Final verification of the resulting sources:

- Full `:core:check`: **182 passed, 0 failed**, 877.9 seconds; registration
  verified **28 classes**. Core sources were unchanged during the final mod checks.
- `:mod:runTurbineGameTest`: **all 15 required tests passed**, including all
  original audit regressions, operating-pump shared-valve checks and three new
  cache/registry tests. The unloaded-scan regression now covers ECCS, ADS and
  feedwater binding as well as reactors and pools.
- `:mod:runPeripheralCheck`: **0 problems**, with CC:Tweaked/Mekanism installed;
  **six dedicated-server spawn-protection scenarios passed**.
- Fresh dedicated server with `-PbwrNoCC -PbwrNoMekanism`: **0 problems**;
  the same **six permission scenarios passed** with both integrations absent.
- Final standard `build -x :core:acceptance`: **passed**; the complete physics
  suite above was run separately. Design-rule check: **198 mod Java files**.
- Registration negative test: **passed**; an actual unregistered compiled test
  fails both verification and filtered execution, naming the missing class.
- Asset audit: **2,170 JSON files, zero problems**. JAR audit: **307 class files**,
  notices retained, no bundled optional-mod or development-test classes.
- Workflow syntax: **actionlint passed**. `git diff --check`: **passed**.
  Hosted CI has not run; the workflow takes effect after it is pushed.

The original thirteen reproductions remain fixed under these checks. The
separate **R01–R05 findings below remain open**; this pass addresses the five
issues filed by Sam-Elsberry, not those additional re-audit findings.

Artifact: `mod/build/libs/mod-0.1.0-SNAPSHOT.jar`, **18,116,203 bytes**.
SHA-256: `33675EDED5BD0C8DBAD9DE035E21761DD70C394467D6665A14CD7B1F84D05368`.

Logs: `tmp/phase-two-full-verification.log` (complete passing core suite; its
later server stage exposed leftover geometry in the new test fixture),
`tmp/phase-two-final-runtime.log` (corrected fixture and all final server tests,
permissions and build passed), `tmp/phase-two-optional.log`,
`tmp/phase-two-release.log`, `tmp/phase-two-registration.log`,
`tmp/phase-two-assets.log`, `tmp/phase-two-jar.log`. Fixture setup now removes its
old branch and asserts exactly one initial endpoint; successful runs clean up
their pipes and tanks. This correction did not weaken the inventory assertion.

## 2026-09-21 — verification audit: five remaining defects

See [PHASE-ONE-REAUDIT.md](PHASE-ONE-REAUDIT.md) for reproduced failures and source
locations. The normal build passes, but shared fluid capacity, suppression-pool
revalidation, remote computer reloads, MSIV branch routing and recirculation
command conversion still have correctness issues. No gameplay code was changed
in this verification pass.

- Fresh `:core:check`: **182 passed, 0 failed**, 880.1 seconds.
- Fresh `:mod:runTurbineGameTest`: **12 passed** with CC:Tweaked and Mekanism.
- Full `build`: **passed**, 15 minutes 21 seconds.
- Four temporary server probes reproduced four additional defects; a separate
  compiled-core calculation reproduced the recirculation command mismatch.
  These failing probes are separate from the passing standard suite.
- Assets: **2,170 JSON files, zero problems**. JAR: **303 classes**, no bundled
  optional-mod or development-test classes. Whitespace validation passed.
- Normal build output restored after the probes. Release artifact SHA-256 is
  unchanged: `61EBC479DBC3052D4C6DEBE6DFBA5649EEBC18228B5DD6CF32603C8D100AE5D2`.

Logs and test scope are listed in the verification report. The earlier repair
entry below is historical and does not supersede these new findings.

## 2026-09-21 — all 13 phase-one audit findings repaired

See [PHASE-ONE-FIXES.md](PHASE-ONE-FIXES.md) for the issue-by-issue changes,
regressions, existing-world behavior and coverage limits. Fuel and basin
inventory ownership, remote capabilities, spawn permissions, pump valve
accounting, chunk recovery, idle FE persistence and live fuel reload are fixed.

Verification of the resulting working tree:

- `:core:check`: **182 tests passed, 0 failed**, 884.4 seconds.
- `:mod:runTurbineGameTest`: **all 12 GameTests passed**, including the existing
  246 machine scenarios and 11 new audit regression methods. Three operating
  steam-driven pump fixtures also check competing consumers on a shared valve.
- `:mod:runPeripheralCheck`: **0 problems**, with CC:Tweaked/Mekanism installed;
  **six dedicated-server spawn-protection scenarios passed**.
- The same peripheral/startup check with `-PbwrNoCC -PbwrNoMekanism`: **passed**,
  including the six permission scenarios.
- `build -x :core:acceptance`: **passed**. The complete core acceptance run
  above was executed separately against the same core sources to avoid repeating
  the 15-minute suite. Hardware-only design-rule check: **196 mod Java files**.
- Asset audit: **2,170 JSON files, zero problems**.
- JAR audit: **303 class files**, both project JARs retain their notices;
  no bundled optional-mod classes or development harness classes.
- `git diff --check`: **passed**. Models/resources are unchanged by this repair.

Artifact: `mod/build/libs/mod-0.1.0-SNAPSHOT.jar`, **18,109,108 bytes**.
SHA-256: `61EBC479DBC3052D4C6DEBE6DFBA5649EEBC18228B5DD6CF32603C8D100AE5D2`.

Logs: `tmp/phase-one-fix-core.log`, `tmp/phase-one-fix-release.log`,
`tmp/phase-one-fix-integration-3.log` (successful peripheral/permission stage),
`tmp/phase-one-fix-optional.log`, `tmp/phase-one-fix-assets.log`,
`tmp/phase-one-fix-jar.log`. The earlier integration log includes a subsequently
corrected test-fixture failure in its later GameTest stage; the final GameTest
result is in `phase-one-fix-release.log`.

## 2026-09-21 — scalable cylindrical condensate storage tank

Replaced the assembled cube appearance with five original Blender components:
round enamel shell, shallow cone roof with railings and vent, plinth, ladder,
and four water flanges. Players select odd diameter 3–15 and height 3–24 in the
tank GUI. Capacity follows cylinder dimensions. Survival construction consumes
tank blocks, shrinking refunds them, and resizing preserves stored water and
fractional withdrawals. Legacy single-block tanks remain compatible. See
CONDENSATE-TANK.md for construction, connections and dismantling behavior.

Verification:

- `:mod:runTurbineGameTest`: **8 tank scenarios passed**, including legacy
  water, min/medium/max sizes, shared flange inventory, fractional persistence,
  obstruction rejection, survival expansion/refunds and teardown drops. Existing
  turbine, pump, valve, condenser and cooling scenarios also passed.
- `:mod:runTurbineModelCheck -PbwrTankPanelCheck`: **passed**. Checked **5,518
  component quads**, min/medium/max scaling, material colors and bounds. Actual
  Minecraft world screenshots show three sizes, connected flanges and a live
  client-to-server resize. Inspected the model and final synchronized GUI.
  Existing model checks, including **2,274 water/steam states**, also passed.
- `tools-export-condensate-tank.py --check`: **15 resources reproduced**.
- Asset audit: **2,170 JSON files**, all **44 blocks** covered, **zero problems**.
- `:core:jar :core:sourcesJar :mod:build`: passed. JAR audit: **294 classes**,
  current notices in both project JARs, no bundled optional-mod classes or
  development harness. Whitespace check passed.

Artifact: `mod/build/libs/mod-0.1.0-SNAPSHOT.jar`, **18,088,745 bytes**.

SHA-256: `6D02131BA8D8E703C6A4F57122229618A0D9A3BE5376AF9EAB9245DE0C2D0549`.

Logs: `tmp/tank-runtime.log`, `tmp/tank-package-final.log`,
`tmp/tank-assets-final.log`. Blender sources and inspected Minecraft previews
are in `art/models/condensate_tank/`. This patch has not been committed or pushed.

## 2026-09-21 — cooling towers, circulation and makeup water

Added five Blender-authored placeable assemblies: natural-draft tower (25x36x25),
Columbia-style circular induced-draft tower (17x8x17), circulating-water pump
(5x8x5), makeup pump (3x5x3), and screened intake (3x2x3). Models expose physical
water flanges and FE boxes; six fan rotors animate on the circular tower. Simple
local speed/readout panels and CC:Tweaked controls are included. See COOLING-WATER.md.

Finite water inventories and shared outlet budgets prevent duplication. Towers
use the existing 24->13 C design point and lose 2% water to a modeled combined
evaporation/blowdown allowance. Lake extraction requires source water and feeds
a finite buffer. Chemistry and water temperature transport are not implemented;
the LP turbine's existing internal condensation remains unchanged.

Verification:

- `:core:acceptance --args=CoolingWater`: **6 passed**, including finite storage,
  invalid input, blocked outlets, speed limits and closed-loop mass accounting.
- `:mod:runTurbineGameTest`: **25 cooling scenarios passed**. All five models in
  all four orientations, obstruction rejection, ownership, save/load, fluid/FE
  faces, steam-pipe rejection, survival teardown, stale handlers, fan/pump power,
  speed, real CC capability, lake source rules, condenser/tower circulation and
  intake/makeup/tower transfers. Existing turbine, pump, valve and condenser
  regression scenarios also passed.
- `:mod:runTurbineModelCheck -PbwrCoolingPanelCheck`: **passed**. Verified 60,152
  body quads, five inventory meshes, six fan instances, four renderer rotations,
  material colors, bounds and controller-only rendering. Inspected screenshots
  from a fresh actual Minecraft world, with connected pipe flanges and the GUI.
  Fixed base-shadow lighting and GUI clipping found during that review.
- `tools-export-cooling.py --check`: all **58 generated resources** reproduced.
- Asset audit: **2,164 JSON files**, all **44 blocks** covered, **zero problems**.
  Audit now recognizes explicitly registered BlockItem subclasses correctly.
- `:mod:build :core:sourcesJar`: passed. JAR audit: **290 classes**, correct
  notices, no bundled Mekanism/CC classes or development harness. Whitespace
  check passed.

Artifact: `mod/build/libs/mod-0.1.0-SNAPSHOT.jar`, **17,996,971 bytes**.

SHA-256: `BF01E6F61597F9155625246DD6754B3B3EEBE71553FCA45CFDB13FC3D4805F38`.

Logs: `tmp/cooling-runtime.log` (core tests), `tmp/cooling-final.log` (server/client),
`tmp/cooling-package.log` and `tmp/cooling-assets.log`. Previews are saved under
`art/models/cooling/`. This patch has not been committed or pushed.

## 2026-09-21 — condenser shell seam repair

Closed the horizontal opening beside the catwalk on both front and rear faces
in Blender. The tapered transition now meets the actual lower casing in height
and depth, with a small overlap at the joint. Re-exported the game and inventory
meshes and updated the Blender previews. Footprint, ports, controller and all
172 occupied cell indexes/roles match the previous compact layout, so existing
compact units update without replacement. Triangle count remains 17,696.

Verification:

- Inspected front and rear Blender views. A shell-only ray check hit the near
  casing at all 630 samples across the former opening.
- `:mod:build :mod:runTurbineModelCheck -PbwrCondenserPanelCheck`: passed.
  Inspected actual in-game close-ups of both repaired seams, plus the LP fit
  and existing day/rear/night views. Renderer material and rotation checks passed.
- Exporter reproduced all 22 resources. Asset audit: zero problems. JAR audit:
  272 classes, valid notices, no bundled optional mods or development harness.
- This is a geometry-only patch; reactor and condenser physics were unchanged.

Artifact: `mod/build/libs/mod-0.1.0-SNAPSHOT.jar`, **16,376,101 bytes**.

SHA-256: `391FD4028DF3170143A230D3C9EEC47A2863494F3B73BBB0CB2D6DE905C75B22`.

Log: `tmp/condenser-seam-client.log`. Close-up previews:
`art/models/condenser_ports/condenser_front_seam.png` and
`art/models/condenser_ports/condenser_rear_seam.png`.

## 2026-09-21 — compact condenser fitted beneath one LP turbine

New placements use a **7x6x7** closed, single-bay Blender model with six ports:
one bypass inlet, two hot-water outlets, two cold-water inlets and one condensate
outlet. Its top collar was shaped against the actual LP skid/casing. Place the
LP root six blocks above the condenser root with the same facing. The compact
model has 17,696 triangles and 172 occupied cells.

Fixed white in-world rendering by preserving OBJ material RGB in the vertex
writer and using the entity atlas render buffer. Old 25x14x25 machines keep their
saved geometry, collision, inventories and ports; break/replacement upgrades them.
The physics assumptions and temporary internal LP condensation are unchanged.

Verification completed:

- `:mod:runTurbineGameTest`: ten condenser scenarios passed, including actual
  LP placement above the condenser in all four directions, all six connection
  faces, Mekanism transfer, CC bypass control and legacy save/reload/replacement.
  Existing assembly, pump, turbine, valve and MSIV regressions also passed.
- `:mod:runTurbineModelCheck -PbwrCondenserPanelCheck`: passed. Renderer capture
  checks material RGB preservation, entity buffer, mesh bounds and all four
  rotations. Existing 2,255 water/steam, 6,816 pump/power-module and 312 RCIC/HPCI
  cell states also passed. A fresh Minecraft world placed a real LP turbine on
  the condenser with steam/water pipes. Front, rear and night screenshots were
  inspected: correct colors, no white silhouette, seated LP and joined flanges.
- Blender front/rear and fitted-LP renders inspected. Saved source geometry is
  in `art/models/condenser_ports/condenser_ports.blend`; the previous large asset
  is archived beside it for save compatibility.
- Exporter check: 22 resources reproduced. Asset audit: 2,123 valid JSON files,
  all 39 blocks covered, zero problems. Whitespace check passed.
- `:mod:build`: passed. JAR audit: 272 classes, matching project notices, no
  bundled optional-mod classes or development harness. Packaged layouts verified
  as 7x6x7 (new) and 25x14x25 (legacy). No reactor-physics changes were made;
  the long reactor acceptance suite was not rerun for this visual/layout patch.

Artifact: `mod/build/libs/mod-0.1.0-SNAPSHOT.jar`, **16,375,914 bytes**.

SHA-256: `66E7D8CB8A6F58140D0B86347EB10FF07C0A2ACF97650C80EA00510F910F8A7F`.

Logs: `tmp/condenser-fit-runtime.log`, `tmp/condenser-fit-client.log`,
`tmp/condenser-fit-build.log`. Screenshots are in
`mod/run/turbineModelCheck/condenser-check/`; the front view is also saved as
`art/models/condenser_ports/lp_condenser_ingame.png`.

## 2026-09-21 — connectable condenser and bypass steam valve

Added the closed Arabelle-style condenser as a 25x14x25 placeable machine.
Its 18 physical ports comprise three upper-front bypass steam inlets, six
front hot-water outlets, six rear cold-water inlets and three separate rear
condensate outlets. The original cutaway remains an inspection source only.
The new one-block Blender bypass valve has local percentage control and the
`bwr_bypass_steam_valve` CC peripheral, with a two-second full stroke.

Condenser cooling and condensate inventories are separate and finite. A basic
mass/enthalpy balance requires cooling supply and room for both products.
Fixed 13/24 C cooling and 40 C condensate temperatures are labeled assumptions;
temperature propagation, towers, vacuum and external LP exhaust are future work.
LP sections retain temporary internal condensation. No automatic controls added.

Verification completed:

- `:core:acceptance --args=SurfaceCondenser`: all five new physics tests passed
  (mass/heat balance, dry/full limits, simulation, invalid input and fractional
  persistence). The unrelated long-running reactor acceptance suite was not
  rerun for this patch.
- `:mod:runTurbineGameTest`: nine new condenser scenarios passed, including
  18 ports in four orientations, obstruction, collision, correct pipe types,
  NeoForge/Mekanism transfer, separate products, NBT, far-port menu reachability,
  actual CC peripheral discovery/commands, shared steam admission, finite
  buffer backpressure, teardown, stale handlers and one-item survival drops.
  Existing 96 RCIC/HPCI assembly, two plumbing, 73 pump, 15 main-turbine,
  12 steam-valve and five MSIV scenarios also passed.
- `:mod:build :core:sourcesJar :mod:runTurbineModelCheck`: passed, including
  protection-logic checks and the final Blender exports. The actual condenser
  renderer emitted its 74,924 quads once in each of four orientations within
  the full structure bounds; child parts emitted no duplicate mesh. Existing
  checks covered 2,255 water/steam states, ten corresponding item models,
  6,816 pump/power-module cells and 312 RCIC/HPCI cells.
- Asset audit: 2,121 JSON files, 39 blocks, all 40 condenser states covered,
  no problems. Condenser and MSIV exporters reproduced 18 and 13 resources.
- Front/rear condenser and bypass-valve Blender renders inspected. Both new
  standalone Blender files reopened. Documentation links and whitespace passed.
- JAR audit: 272 classes, exact current notices in both project JARs, no
  bundled optional-mod classes or development harness.

Artifact: `mod/build/libs/mod-0.1.0-SNAPSHOT.jar`, **16,763,348 bytes**.

SHA-256: `0ADA270443B456211AD8237D690C3F6E8BAF853B03CB02252E1A39D7F504E4F5`.

Logs: `tmp/condenser-runtime.log`, `tmp/condenser-build.log`,
`tmp/condenser-assets.log`. Connections and CC examples:
[CONDENSER-AND-MSIV.md](CONDENSER-AND-MSIV.md).

## 2026-09-21 — Arabelle condenser artwork and modeled MSIV

Built the detailed three-bay condenser in Blender, using the supplied cutaway
and Arabelle's 1,700 MWe reference dimensions. Standalone exterior/cutaway
scenes contain 95,912 triangles and 1,357 named meshes; both were rendered and
inspected. This is a model asset, not implemented condenser gameplay.

Newly placed MSIVs use a 1x3x1 Blender model with 10,804 triangles, opposed base
steam ports and one actuator simulation. Existing cubes retain their geometry,
connections and saved data until replaced. Redstone can command any part;
CC remains on the base. Four-second full travel ends exactly on tick 80.

Verification completed:

- `:mod:build` passed, including `checkNoProtectionLogic`.
- `:mod:runTurbineGameTest` passed: five new MSIV scenarios, 12 steam-valve,
  15 main-turbine, 73 pump, two turbine-plumbing scenarios and the existing
  RCIC/HPCI assembly suite. MSIV tests exercise all four facings, obstruction,
  per-tick steam routing/closure, upper redstone, CC, moving-state persistence,
  reversal, valid single-item drops and legacy saves.
- `:mod:runTurbineModelCheck` passed: 2,251 water/steam states (including all
  48 MSIV states) and nine inventory models, plus 6,816 pump/power-module
  cell states and 312 RCIC/HPCI cell states. The check now reads both unculled
  OBJ faces and culled legacy-cube faces.
- Asset audit: 2,108 JSON files, 37 registered blocks, no problems.
- `tools-export-msiv.py --check`: 13 resources reproduced with bounds and
  surface-area checks. Both standalone Blender files reopened successfully.
- JAR audit: 258 classes, two project JARs with exact current notices, no
  bundled optional-mod classes or development test harness.

Artifact: `mod/build/libs/mod-0.1.0-SNAPSHOT.jar`, **14,845,315 bytes**.

SHA-256: `8AF4516B57E4A967A9725D7605A1F4A6D99F82A536C4CC518556887CFD24AE7A`.

Source assets, renders, controls and migration: [CONDENSER-AND-MSIV.md](CONDENSER-AND-MSIV.md).

## 2026-09-21 — README, attribution and source-available licensing

Updated the README for the current steam-valve controls, modular turbine
layout, migration steps, model sources, validation and distribution terms.
Added the Realistic BWR Source-Available License 1.0 and owner attribution.
It permits private edits and unchanged redistribution with credit, while
modified redistribution and reuse in another distributed project require
prior written approval. Earlier MIT/MPL grants through `19a7424` remain valid;
their original texts are preserved in `licenses/legacy/`.

The mod, nested core and core source JARs include the license, attribution,
third-party notices and historical license texts. Mod metadata names
OldGunslingerWildBill and the new license identifier.

Verification:

- `:mod:build :core:sourcesJar` passed, including the protection-logic check.
- The JAR audit passed for 257 classes and both project JARs, checking exact
  notice contents, metadata and the existing dependency/dev-test exclusions.
- Historical license texts match Git history; README/document links and
  source-JAR notices passed. An in-memory JAR missing attribution was correctly
  rejected by the auditor. The whitespace check passed for edited material;
  the archived MPL text retains its original trailing space on line 38.
- Gameplay code is unchanged; runtime/client scenarios were not rerun for
  this documentation and packaging update. Prior results appear below.

Artifact: `mod/build/libs/mod-0.1.0-SNAPSHOT.jar`, **14,286,663 bytes**.

SHA-256: `5192781CD78A0EFF00E8BACC6EA9BACA4391AE38076E468F8DF5E3B0F3D2760E`.

Build log: `tmp/license-readme-build.log`.

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
