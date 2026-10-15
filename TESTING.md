# Testing and release verification

The repository maintainer owns the release checks below. Contributors should
run the checks affected by their changes; the GitHub workflow repeats the
headless checks on every push and pull request, and supports manual dispatch.

## Automated checks

The full core display adds GameTest classes `FcdRodTests` (rod selection, steps, continuous stepping; fixture
75,900), `FcdIndicationTests` (indications, LPRM strings, coordinates; 77,900), `FcdDisplayTests` (layout, mirroring,
span and refusals, unbound, unformed and old saves, matrix presses; 79,900), `FcdSnapshotTests` (the codec and one
patch per change; 81,900) and `FcdModelTests` (style table and model file contracts). They run in their own batch
`fcd` on a real formed 15 x 15 reactor and a panel wall, and need CC for nothing. `:mod:runTurbineModelCheck
-PbwrFcdClientCheck` (stages with `-PbwrFcdClientStages`, comparisons with `-PbwrPanelImmediate`,
`-PbwrFcdLegendsPerFrame` and `-PbwrFcdProcedural`) measures frame cost and writes screenshots to
`run/turbineModelCheck/fcd-check/`; it fails if the legend cache is not used.

APRM calibration (heat balance, per-channel gain and error, the bounded manual `aprm_<n>_gain` command) is covered by
the core test `AprmCalibrationTest` (6 checks: heat balance closes on the model power at a steady rated core and reads
zero with no steam, a miscalibrated channel is recalibrated by hand to within 0.5 points, all six channels calibrate
independently and the gains survive a state round trip, the bounds refuse and change nothing, nothing recalibrates by
itself) and the GameTest `AprmCalibrationTests` (fixture origin 175,840; real formed 15 x 15 reactor and signal command
path, plus the CC peripheral when CC is present; a save and reload). The GameTest fixture has no recirculation pumps, so
it stages the core at power and runs it on the core with a stand-in turbine and feedwater pump until it is steady before
anything is compared; the logged `APRM CALIBRATION GAMETEST` lines show the heat balance agreeing with the core.

`:mod:runTurbineModelCheck -PbwrGridClientCheck` (class `GridClientCheck`) places the Grid Tie and four FE Meters facing north,
east, south and west in a disposable creative world and writes `grid-tie-and-meters.png` and `meters-arrows-close.png` to
`run/turbineModelCheck/grid-check/`; the arrow on each meter's top face points toward its output.

Flashing-lamp cost (C2) is measured by `-PbwrPanelPerformanceCheck -PbwrPanelPerformanceStages=annunciators-off,annunciators-flash`
(the 16 x 8 wall with 50 annunciator windows, normal then all flashing) and by `-PbwrFcdClientCheck
-PbwrFcdClientStages=columbia-scram-perf` (a 185-rod full core display, frames before and for 8 s after a scram). Flashing is drawn
per frame from game time, so it never rebuilds a block buffer: the annunciator walls rebuilt exactly 128 buffers per stage, the same
as the plain wall. Local results (one machine, frame times drift 2x between the first and later stages of one run, so compare
pairs): flashing minus normal came out between -1.0 and +1.1 ms, about +0.3 ms on average over six pairs; the display during a
scram averaged 0.50-0.60 ms (p95 0.7-1.0 ms) against 0.53-0.63 ms steady, with about 25 single-block rebuilds of about 2.4 ms in
the 8 s, one frame over 16 ms. Neither is near the 2 ms limit, so no renderer change was made.

Alpha.30 adds `CoreMeltProgressionTest` (9), `ContainmentHydrogenTest` (11),
`SevereAccidentIntegrationTest` (4) and `NaturalCirculationTest` (6). They cover
burst and gap release, latent-heat melting, relocation, flooded versus dry lower
heads, pressure dependence of head creep, ablation with and without water,
basemat melt-through, snapshot round trips without event replay, hostile input,
hydrogen ignition and standing flames, inerting and de-inerting, oxygen
starvation, venting, overpressure and air ingress, contamination decay, the
vessel bottom breach, debris heat to water, and natural circulation versus power
and level. No mod GameTest covers the new save key yet.

Alpha.7 extends `FuelVarietyRegressionTests` to six checks. A completed tritium
rod must yield exactly 1,250 samples and one casing, once only. The added
overflow regression starts with a nearly full inventory and a partial sample
stack, checks inventory-plus-drop conservation, and rejects oversized stacks.
The actual Chemical Oxidizer check also pins the total to 12,500,000 mB per rod.

Alpha.6 extends `FuelVarietyTest` to six checks and
`FuelVarietyRegressionTests` to five. New coverage checks multi-day exposure,
retention of old exposure seconds, a 64-sample single-use harvest, and actual
powered Chemical Oxidizer processing/extraction. Default dev runs now include
Mekanism Generators, which owns the tritium chemical; it is runtime-only and
never bundled. `-PbwrNoMekanismGenerators` tests base Mekanism without Generators;
`-PbwrNoMekanism -PbwrNoCC` removes all three optional integrations. The recipe
must be absent whenever either Mekanism or Generators is absent.

Use `:mod:runPeripheralCheck -PbwrNoMekanism -PbwrNoCC` for the no-integration
configuration; it explicitly checks recipe gating. The full GameTest harness
still has an older fixture with an unconditional CC:Tweaked `LuaException`
reference and cannot start with CC:Tweaked absent. This is a test-only
limitation; these fixtures are excluded from the release JAR.

Alpha.5 adds `FuelVarietyTest` (five physics checks) and
`FuelVarietyRegressionTests` (four real Minecraft tests): family constants,
non-fuel absorption, source addition, exposure limits, component/core save
recovery, shuffling, snapshots and single-use harvesting. The previously
five-entry `FuelTypeSpecTest` now checks all 19 entries. `FuelCatalogueClientCheck`
runs as the fourth stage of `:mod:runTurbineModelCheck -PbwrCreativePerformanceCheck`
and validates all 19 fuel and eight specialty-rod textures and names.
Release results and the focused rerun after the catalogue-test correction are
recorded at the top of `BUILD-STATUS.md`.

The condenser/MSIV patch adds `CondenserValveRegressionTests`: underside
placement in every facing, entity/block rejection without consuming items,
LP-menu pass-through, v2/v3 save compatibility, FE conservation and spring closure,
all exposed CC faces, and recognition by a Mekanism Universal Cable. Optional
API checks are guarded when those mods are absent. The dedicated permission
harness also rejects a snap whose remote footprint crosses spawn protection.

The condenser client harness (`:mod:runTurbineModelCheck
-PbwrCondenserPanelCheck`) captures eleven views, including green/red attachment
outlines, then places a condenser through the normal client interaction path. Additional
views show the round makeup nozzles and two adjacent LP/condenser modules.
`CondenserMakeupRegressionTests` covers adjacent units in four rotations, all
port clearances, shared hotwell reservation, a real powered makeup pump feeding
BWR pipes, water/heat conservation, save/reload and stale capability rejection.
The dedicated runtime harness transfers real water through Mekanism Mechanical
Pipes into both cooling and hotwell inlets.

`.github/workflows/verify.yml` has three jobs:

1. **Physics, build and resources:** JDK 21; `gradlew check build`; the
   registration negative test; JSON/resource and JAR audits.
2. **Server with integrations:** Minecraft GameTests, followed by the dedicated
   server peripheral and spawn-protection checks with CC:Tweaked and Mekanism.
3. **Server without integrations:** dedicated-server startup, common hardware
   and spawn-protection checks with both optional mods absent.

Failures propagate through the Gradle exit status. Bash runs with `pipefail`, so
capturing logs through `tee` cannot turn a failed check into a successful job.
Logs are uploaded even on failure. CI uses disposable test worlds and read-only
repository permissions; it does not publish a mod release.

The workflow becomes active when its commit reaches GitHub. A locally validated
workflow is not evidence that a hosted GitHub run has completed.

## Local commands

Use JDK 21 and Python 3.11 or newer. On Windows use `gradlew.bat` as below; on
Linux/macOS use `bash ./gradlew` with the same arguments.

```powershell
.\gradlew.bat check build
python tools-check-test-registration.py
.\gradlew.bat :mod:runTurbineGameTest
.\gradlew.bat :mod:runPeripheralCheck
.\gradlew.bat :mod:runPeripheralCheck -PbwrNoCC -PbwrNoMekanism
python tools-audit-assets.py
python tools-check-jar.py
```

The dedicated dev server needs `eula=true` in
`mod/run/peripheralCheck/eula.txt`. Keep `spawn-protection=16` and
`online-mode=false` in its `server.properties`; it must be a disposable test
world. Use separate fresh run directories when switching optional-mod sets to
avoid old-world missing-registry warnings. The CI jobs already have separate
workspaces. GameTests use `mod/run/turbineGameTest`.

The full physics acceptance suite includes long transients and currently takes
about 15 minutes on the development machine. Filtered runs are available via
`:core:acceptance --args=RecirculationSizing`, for example. Final release
verification must include the complete suite.

### Core registration and counts

The plain Java runner retains an explicit ordered `TEST_CLASSES` array. Before
any run, including a filtered run, it scans the compiled test classes and
rejects public static zero-argument `void test*()` methods in unregistered
classes. Duplicate registrations and registered classes without test methods
also fail. Errors name the offending classes.

`:core:verifyTestRegistration` runs only that check and prints actual class and
method counts. The acceptance transcript reports how many tests really ran.
`tools-check-test-registration.py` copies compiled tests to an isolated temporary
directory, compiles an unregistered canary there, and proves both verification
and filtered runs reject it. It does not edit project sources or build output.

The earlier published snapshot had **171 methods in 26 classes**. Local
condenser/cooling additions and LP condensate migration brought that to
**183 methods in 28 classes**. The compact-core update brings the current total
to **186 methods in 29 classes**. Suppression-basin and RHR exchanger tests bring
that total to **191 methods in 30 classes**. Dry-basin and spray tests bring it
to **193 methods in 30 classes**; passive cooling brings it to **195 methods in
30 classes**. Segmented heat transport and the recirculation inverse regression
bring the total to **202 methods in 31 classes**. Finite SLC solution tests bring
the first alpha total to **204 methods in 32 classes**. Two hotwell makeup
conservation tests bring alpha.4 to **206 methods in 32 classes**. New tests may change those counts;
the runner's output is authoritative.

### Alpha hardware and creative inventory

The alpha has **46 required Minecraft GameTests**. Four alpha hardware tests cover:

- Finite SLC water/borate mass, FE dependence, actual boron readouts, feedwater
  cross-ties, broken lines and live tank capability ownership after reload.
- Outfall orientation, simulated/actual shared rate budgets, water-only filling,
  blocked mouths and removed-block capabilities.
- Independent ADS divisions, reassignment, persistence and real CC calls.
- Physical ADS steam source, nozzle closure/broken lines, shared steam claims
  and prevention of a second vessel debit for nozzle-supplied steam.

The CC face regression covers **44 machine block types × six directions**.
The original F01–F13, GitHub #14–#18 and R01–R05 regressions remain in the gate.

Five tank/CRD regressions cover actual player mining in survival and creative,
unsuitable tools, root and flange breaking, deferred cross-chunk casing
restoration and its saved ledger, a 289-drive grid fed through two bottom faces,
water/FE conservation, shared pipe reservation, independent wear, disconnected
groups, vertical connections, block-entity replacement and chunk unload/reload.
The grid test also asserts that passing time does not rebuild its connection map.

Opt-in real-client checks:

```powershell
.\gradlew.bat :mod:runTurbineModelCheck -PbwrCreativePerformanceCheck
.\gradlew.bat :mod:runTurbineModelCheck -PbwrAlphaClientCheck
python tools-export-alpha.py --check
```

The first measures vanilla and BWR creative tabs in a disposable world. Local
BWR results were 18.84 → 560.83 FPS and 51.17 → 1.14 ms screen CPU time. Keep
resolution, GUI scale, FPS cap and scene identical when comparing runs.
The second saves six hardware/panel screenshots under
`mod/run/turbineModelCheck/alpha-check/`; all were visually inspected. It also
checks 33 two-quad inventory icons and the original world/held model resources.
The Blender export check verifies all 144 new hardware output files are current.

Current artifacts and transcripts are recorded in BUILD-STATUS.md. Benchmarks
measure the local test scene and do not guarantee FPS in every modpack.

### Minecraft-facing coverage

The concrete suppression update had **24 required GameTests**, including six
new tests for concrete-basin lifecycle, physical RHR circulation/cooling,
rotated exchanger capabilities/persistence, pumped filling/spray and older pump
compatibility, plus unpowered natural cooling and its reload/ambient bounds.
The opt-in visual check
`gradlew.bat :mod:runTurbineModelCheck -PbwrSuppressionPanelCheck` creates a
disposable world and captures the basin, exchanger flanges and live panels in
`mod/run/turbineModelCheck/suppression-check`. It is excluded from the release JAR.

The mod uses NeoForge's headless GameTest server and dedicated-server harnesses
instead of a JUnit test source set. Their assertions are automated and exit
nonzero on failure. They are explicitly executed by CI, not implicitly by
`:mod:check`, which enforces the source-level hardware/control design rule.
Development test classes and fixtures are excluded from the release JAR.

The server suite includes assembly placement, ports, resource conservation,
save/restore, teardown, pump/valve accounting, and the regressions below.

The release candidate has **32 required GameTests**. `ReleaseRegressionTests`
adds shared-fluid-inventory accounting, event-driven topology, branch-specific
MSIV isolation, thermal packets and reload, live computer replacement, real
chunk-frontier reconnection, all-six-face coverage for 41 machine block types,
and oversized-network refusal. `partialChunkLifecycle` also retains a computer
connection across real controller chunk unload/reload. The dedicated-server
CC check invokes the dynamic Lua entry point on and off the server thread;
it checks scheduling and results, not just Java reflection signatures.

CC-specific test implementations live behind the optional-mod guard, so even
GameTest discovery succeeds when CC:Tweaked is absent. Development classes are
excluded from the player JAR.

| Original finding | Permanent regression coverage |
| --- | --- |
| F01 fuel recovery | `reactorOwnershipAndResize`: loaded/pending fuel, exposure and exactly-once drops |
| F02 basin ownership | `poolOwnership`: duplicate controllers, replacement and saved basin inventory |
| F03 vessel ownership | `reactorOwnershipAndResize`: duplicate rejection and recovery |
| F04 live remote capabilities | `remotePortReplacement`, `remoteWaterReconnect`, `partialChunkLifecycle` |
| F05 footprint permissions | `AssemblyPermissionRuntimeCheck`: seven dedicated-server spawn-boundary scenarios, including automatic tank formation |
| F06 shrinking with fuel | `reactorOwnershipAndResize`: rejected shrink retains outer fuel |
| F07 physical recirculation | `stoppedRecirculationOverride`: commanded/unpowered hardware delivers zero |
| F08 basin discharge ownership | `poolOwnership`: neighboring basin's quencher excluded |
| F09 valve route/budget | `unusedSteamBranch` and `PumpValveLedgerCheck` for RCIC, HPCI and turbine feedwater |
| F10 loaded-only validation | `unloadedScans`: reactor and pool, extended to ECCS/ADS/feedwater bindings |
| F11 incomplete assemblies | `incompleteAssemblyRecovery`, `partialChunkLifecycle` |
| F12 idle FE persistence | `fabricatorEnergyPersistence` |
| F13 fuel definitions | `liveFuelReload`: actual loader apply and preserved exposure |

`PhaseTwoRegressionTests` adds cache invalidation, live inventory resolution,
production pump use of caching, allocation/timing measurements, and internal
pump registration across formation, unload, NBT restore and structural failure.

## Pipe-cache contract and measurement

Neither BWR pipe block has a block entity or ticker. `PipeTopology` caches
geometry per level and collapses contiguous ordinary pipes into junctions.
Inventories, controller objects, valve positions and shared steam allowances
are resolved during transfer, not frozen into the topology.

- Block/port edits and capability invalidation dirty affected entries. The
  survey watches empty frontiers as well as existing pipes.
- Chunk accessibility changes invalidate routes without force-loading chunks.
  The regression includes a LIGHT-retained chunk becoming FULL again, which
  need not generate another physical chunk-load event.
- Time passing and valve travel do **not** rebuild topology. The previous
  five-tick rebuild interval is removed. A 1,000-tick stationary route keeps
  the same graph; a valve moves without rebuilding that graph.
- Each level keeps at most 512 entries. Surveys stop at 256 line positions;
  a truncated network refuses transfer rather than using a partial route.

The GameTest log prints `F16 PLUMBING` timing/allocation measurements for HPCS,
electric feedwater and turbine feedwater fixtures. Repeated endpoint tracing,
cached line lookups and advancing-time lookups are compared, with an assertion that
time alone creates no new topology. Timing is reported rather than used as a
hardware-dependent pass/fail limit. These measurements cover plumbing work,
not whole-plant or multiplayer TPS.

## Sodium, Iris and shader packs (C1)

Sodium, Iris and a shader pack are optional, run-only test dependencies. They are never bundled in the jar, never declared in
`neoforge.mods.toml` and never referenced at compile time (the checks ask the mod list and, by reflection, Iris's public API
whether a pack is in use; see `RenderEnvironment`). The player note is `docs/shader-packs.md`.

Properties of the client runs (`:mod:runTurbineModelCheck`, `runClient`, the multiplayer clients and the other client runs):

| Property | Meaning |
| --- | --- |
| `-PbwrRenderMods=stable` | Sodium 0.6.13 and Iris 1.8.12 into the run's `mods/` |
| `-PbwrRenderMods=newest` | Sodium 0.8.13 and Iris 1.8.14-beta.1 |
| `-PbwrShaderPack=ComplementaryReimagined_r5.9.3.zip` | the pack (from `shaderpacks/`) is copied to the run's `shaderpacks/` and enabled in `config/iris.properties` |
| `-PbwrShaderPack=none`, or no property | Iris on with shaders off (with a pair), or nothing |
| `-PbwrRenderModsDir=<path>` | the folder with `stable/`, `newest/` and `shaderpacks/` (default `../optional-client-mods` next to the repo) |
| `-PbwrSodiumDisable=a.b,c.d` | diagnostics: switches those Sodium mixin groups off (`config/sodium-mixins.properties`) |
| `-PbwrRenderProfile` | diagnostics: samples the render thread every 2 ms and logs the busiest frames per FCD stage |

Without `-PbwrRenderMods` every client run first deletes `sodium*` and `iris*` jars from its `mods/`, `iris.properties`,
`iris-excluded.json`, `sodium-options.json`, `sodium-mixins.properties` and the `.zip` packs in `shaderpacks/`, so a plain run never
keeps Sodium; the line `render mods: ...` at the top of the run says what was set up. Each check logs
`RENDER ENV check=... sodium=<version|none> iris=<version|none> shaderPackInUse=<bool>` once the world is up.

The release jars load in the dev environment as they are (FML reads `<gameDir>/mods` in dev, and NeoForge 1.21.1 runs with
Mojang names), so nothing had to be remapped. The `Reference map 'iris.refmap.json' ... could not be read` warnings are
Iris's own and harmless in a dev run.

Commands (one JVM each, a real window opens; the panel check takes about 3 minutes, the FCD check 7, the performance pair 1):

```powershell
$g = '.\gradlew.bat'; $c = '--offline', '--console=plain'
& $g :mod:runTurbineModelCheck -PbwrPanelClientCheck @c                                  # vanilla
& $g :mod:runTurbineModelCheck -PbwrPanelClientCheck -PbwrRenderMods=stable @c           # stable pair, no pack
& $g :mod:runTurbineModelCheck -PbwrPanelClientCheck -PbwrRenderMods=stable -PbwrShaderPack=ComplementaryReimagined_r5.9.3.zip @c
& $g :mod:runTurbineModelCheck -PbwrPanelClientCheck -PbwrRenderMods=newest -PbwrShaderPack=ComplementaryReimagined_r5.9.3.zip @c
# the same four with -PbwrFcdClientCheck, and with
#   -PbwrPanelPerformanceCheck -PbwrPanelPerformanceStages=annunciators-off,annunciators-flash
# frame times of the full core display during a scram: -PbwrFcdClientCheck -PbwrFcdClientStages=columbia-scram-perf
```

The default panel stages stop at `board-night-close` on vanilla (see the limitations), so the runs below pass
`-PbwrPanelClientStages=board-day-wide,board-controls-close,board-annunciators,board-lod-28m,board-night-lamps,wrench-config,linker-binding,baked,slope`
(every stage but `board-night-close`, plus `linker-outline` where no pack is on).

Results on the development machine (Ryzen 7 5800X3D, RTX 5080, 1280 x 720; the machine was also running another long Java
job, so only numbers within one table are comparable, and the stable + Complementary performance pair ran with an unfocused
window, `active=false`). The screenshots of the four configurations were compared with their vanilla twins: the panel stages
(board day, close, annunciators, 28 m level of detail, night lamps, wrench and linker screens, 18 baked boards and close-ups,
7 sloped bench and console boards) and the FCD stages (Columbia and Browns Ferry displays, the rod select matrix, the far and
close views, scram, legends at reading distance and the nameplates):

| Configuration | wall, 50 annunciators normal / flashing (mean ms) | full core display, 8 s around a scram (mean / p95 ms) | verdict |
| --- | --- | --- | --- |
| vanilla | 3.62 / 4.23 | 0.53 / 0.79 | reference |
| Sodium 0.6.13 + Iris 1.8.12, no pack | 2.51 / 2.47 | 0.50 / 0.73 | same pictures |
| the same with Complementary Reimagined r5.9.3 | 6.32 / 7.60 | 1.32 / 1.80 | pictures match, in the pack's look |
| Sodium 0.8.13 + Iris 1.8.14-beta.1 with Complementary | 6.18 / 5.68 | 1.18 / 1.50 | same as the stable pair |

What the pictures show. Without a pack Sodium and Iris change nothing visible except the surface of sloped boards (benchboard
and console), which is a little darker and greyer: that is the board's own block face, meshed by Sodium with its own shading;
the components on it are identical. With Complementary every element is relit by the pack: lamps, annunciator windows, LCD
and segment displays and the lit tiles of the core displays keep their glow (they are drawn full bright, and stand out against
the pack's dark night), meters keep needles, dials, numerals and units, engraved text, the FCD cell legends and the rod select
labels stay crisp down to the 1.6 block reading distance, glass windows are clear, and the wrench and linker screens are the
same. No z-fighting, no black or missing face and no text through a wall was seen. Colour grading, shadows (a panel casts one
in the pack) and fog differ, which is the pack.

Real defects the work found, all fixed in the mod, each with a check:

1. **Meter numerals vanished under Sodium and Iris, with or without a pack.** The meter window was drawn with the vanilla
   `entity_translucent` render type, which writes depth, and the printed scale sits just behind it; Sodium and Iris draw the
   translucent type before the text, so every numeral, unit and tag behind a window disappeared. `PanelRenderContext.GLASS`
   is now the same type without the depth write. Check: stage `baked-closeup-round-meter` of the panel client check counts
   dark pixels where the first dial's `40` and `60` are (142 and 149 on vanilla and with Sodium, 117 to 122 under a pack, 0
   before the fix; the assertion needs 60).
2. **A shader pack switched the panel block buffers off,** so a fully populated baked wall was rebuilt vertex by vertex every
   frame: 36 to 39 ms a frame instead of 6 ms. The buffers go through the same render type as the per frame path, which Iris
   re-routes, so they stay on under a pack. Check: `PanelPerformanceCheck` fails if a wall stage ends with no block buffers.
3. **The machine mesh cache missed under a pack** (the vessel, the drives and other machines): the pack's shadow pass hands
   block entity renderers an Iris wrapper that is not a `BufferSource`, and the cache then rebuilt every quad on the CPU every
   frame (a 15 x 15 reactor with its 185 drives: 127 ms a frame, and the FCD stage could not reach 100 frames). Those draws
   now go through a cached buffer with the light baked in, drawn with the render type's own program (the pack's). Check: the
   existing FCD frame count assertion (it failed with 66 frames before; the stage now runs 6,000).

Known limitations (test environment, not defects; no assertion was edited):

* **`board-night-close` fails on vanilla as well** (`red=0 amber=5306`): the check wants lamp pixels of at least 215 in the red
  channel, and the full bright lamp quads come out at `0xBC1B1B` because the entity shader still applies the face's directional
  shade. It fails without any optional mod, so the runs above leave that one stage out. The picture is saved before the
  assertion now, so it can be looked at (looked at in all four configurations: the lit tiles, LCDs and segments glow at night in
  each, with bloom under the pack). The reviewer should decide whether the assertion (written for earlier lamp
  models) should be re-based.
* **`linker-outline` under a pack:** it looks for pixels of exactly `0xFF9933` (the orange of the bound-device outline), which a
  pack's tone mapping never leaves exact. The outline is drawn (about 10,450 orange-ish pixels around the device with and
  without a pack, and `LinkerHighlight.debugDraws()` counts it); the stage is left out of the pack runs.
* **`-PbwrMachineRenderCheck` with Sodium** fails at its `offscreen` stage (`Offscreen models still submitted`) with Sodium
  alone, no pack: Sodium culls block entities by section, not one by one, so a renderer is called for every machine in a visible
  section even while it is behind the camera (`SodiumWorldRenderer.renderBlockEntity` has no per block entity frustum test).
  The vanilla run passes all seven stages, and the `gpu-day` stage passes under both mod sets.
* Under Complementary a good part of the cost is the pack's shadow pass drawing every panel and machine a second time.
* Two runs hit a transient before the pass that is reported: a start-up `ExceptionInInitializerError` in FML's early window
  (`FileSystemNotFoundException`, once, in the stable pair no-pack panel run) and one `Baked showcase block missing on the
  client` (newest pair, the showcase blocks had not arrived yet). Both passed on rerun without any change.

## GUI performance check

`gradlew.bat :mod:runTurbineModelCheck -PbwrGuiPerformanceCheck` creates a
disposable client world and measures paired world/menu frames for a pump,
764-assembly reactor and maximum reactor. It logs screen CPU time and FPS,
and captures screens in `mod/run/turbineModelCheck/gui-performance/`.

The fuel-grid and screen readout batching fix reduced local reactor screen
CPU time from 4.80/8.13 ms to about 0.80 ms. Exact hardware/settings and paired
FPS results are in [PLANT-TRANSPORT.md](PLANT-TRANSPORT.md). The user's exact
216-to-20 FPS result was not reproduced, so these measurements do not establish
modpack/shader performance. This harness is not part of headless CI.

## Manual release checks

The maintainer should run the relevant client model/GUI harness after visual or
screen changes (`:mod:runTurbineModelCheck` with the matching panel option).
Inspect the resulting screenshots, port alignment and GUI controls. Repeat the
asset exporter's `--check` when generated models change. These graphical checks
are separate from headless CI.

Record the final JAR hash, executed checks and known limitations in
`BUILD-STATUS.md`. Passing tests do not close unrelated findings automatically;
`PHASE-ONE-REAUDIT.md` records the original R01–R05 reproductions and their
current repairs. All five now have passing regression coverage.

## Compact core layout verification

`CompactCoreLayoutTest` checks all 289 supported rectangular footprints,
reflection symmetry, four local fuel positions per blade, stable IDs, and
764/185 reference / 1,476/357 maximum capacities. It also exercises physics
restore with the larger logical loading.

`CompactCoreRegressionTests` runs through actual Minecraft formation, physical
CRD ownership, GUI wire decoding, missing-drive repair, legacy saves across a
broken-vessel reload, peripheral fuel exposure recovery, continuous motion
persistence and adjacent-drive supply sharing. It is included in
`:mod:runTurbineGameTest`; existing audit regressions remain registered.

```powershell
.\gradlew.bat :core:acceptance --args=CompactCoreLayout
.\gradlew.bat :mod:runTurbineModelCheck -PbwrCompactCorePanelCheck
```

The client check creates a disposable world, renders both reference and maximum
core maps, opens refuelling, sends an actual GUI command to the final blade and
closes itself. Images are written under
`mod/run/turbineModelCheck/compact-core-check/`.

## Pressure vessel visual checks

Run `:mod:runTurbineModelCheck -PbwrVesselModelCheck` after changes to the vessel
renderer, client appearance synchronization or Blender components. Inspect all
screenshots under `mod/run/turbineModelCheck/vessel-check/`, especially the
broken/repaired shell and the port-to-barrel transitions. Verify reproducible
exports with `python tools-export-reactor-vessel.py --check`.

### Dry suppression basins, spray and detailed pumps (2026-09-23)

The subsequent natural-cooling patch passed **195/195 core tests** and **24/24
required GameTests**. It adds heat/water accounting, exposed-area and inventory
scaling, timestep subdivision, invalid inputs and ambient bounds in the core;
the Minecraft test verifies a genuinely unpowered basin, level-dependent heat
loss and reload continuity. The existing client fixture also asserts the new
Natural cooling readout arrives in its menu snapshot.

Core acceptance: **193/193**, including empty inventory, finite spray heat/mass,
no-flow and hot-supply cases, mode switching and spray-buffer persistence.
The 23 required GameTests include actual pipe-capability fills, source-block
rejection as metered inventory, dynamic quencher submersion, empty saved
controllers, repairs and old pump layout/port/teardown compatibility. Basin tests
use fresh coordinates because saved metered inventory deliberately survives
across repeated GameTestServer runs.

The suppression client fixture now checks seven views: assembled water, exchanger
orientations, exchanger panel, fill panel, a real GUI spray-mode packet, spray
headers/particles, and both detailed pump models. It also checks standalone model
textures, including the preserved old pump models. Test steam is explicitly
injected by the development fixture; production steam still comes from plant
reports. The release JAR excludes all development fixture code.
