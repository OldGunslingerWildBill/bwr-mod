# Testing and release verification

The repository maintainer owns the release checks below. Contributors should
run the checks affected by their changes; the GitHub workflow repeats the
headless checks on every push and pull request, and supports manual dispatch.

## Server tick cost and chunk boundaries (GameTest namespace `bwrtick`)

Two development tools and their tests live in the `bwrtick` namespace, so `-PbwrOnlyTick` runs just them (about a minute,
instead of the whole suite) while they are being worked on; the normal `:mod:runTurbineGameTest` runs everything.

**Tick cost.** `dev.bwr.mod.TickProbe` times our block entity tickers, our server tick handlers and the scheduled ticks of
the assembly blocks with `System.nanoTime` and reads the allocation counter of the server thread. It is off (and costs a
static boolean test) unless the JVM has `-Dbwr.tickProbe=true`; with Gradle, `-PbwrTickProbe`. `TickBudgetTests` builds a whole
plant in force-loaded chunks (`PlantKit`: a formed, fuelled 15 x 15 reactor with all 185 drives, two recirculation
pumps, two main steam lines with MSIV and turbine outlet, a suppression pool with an RHR loop, feed, HPCS and LPCS pumps with
tanks, a condenser, a cooling tower and circulating pump, a sealed 21-block containment room, two boards of 24 panel gauges
bound to plant devices with the linker, a fake player watching the chunk so that update packets are built), lets it settle
and measures 700 ticks. With the probe on it prints `TICK PROBE` lines (mean and worst microseconds and kilobytes per
server tick, per system, plus `SPIKE` lines for any system that used 8 ms in one tick), `SIGNAL COST`, `CABLE COST` and
`SCAN COST` tables, and with CC present `CC COST` (what one pass of every getter of each peripheral costs the server thread).
What the test **asserts** is work, not time, so it is stable on a loaded machine: over the window no pipe graph may have been
rebuilt more than 40 times, no rod bank re-surveyed more than 5 times, and the sectioned scans may not have read more than
200,000 blocks. `-PbwrJfr=<file.jfr>` records a Java Flight Recorder profile of the run (`jfr print --events jdk.ExecutionSample`).

**Chunk boundaries.** `ChunkLinkTests` lays steam lines and a water line along X across chunk borders and takes chunks away
by dropping their tickets and waiting until the chunk object is gone, then brings them back through the ticket system with the
plant running (`ChunkLab`, `Script`). A forced chunk keeps everything within two chunks loaded, so a chunk only unloads when
every forced chunk is three or more away: a line that must lose a middle chunk is seven chunks long. The cases: the outlet
chunk of a four chunk line unloads and reloads; the middle chunk, holding an isolation valve, of a seven chunk line unloads;
the reactor chunk unloads under an outlet that keeps its ticket; a tube is broken and put back on each side of a chunk border
(and the two tubes at the border must grow arms toward each other); every chunk of the plant is saved and read back; a line
that crosses the world border at the largest block coordinates; a water pipe 97 blocks long to a tank, with water conservation
checked to the millibucket; an old single-block pump whose first scan cannot see its tank's chunk. Every test also fails if
something of ours loaded a chunk by reading a block in it (`ChunkEvent.Load` with one of our frames on the stack), logged a
warning or error (`LogWatch`), or rebuilt the network on every tick. `SyncLoadAudit` does the same watching over the whole
GameTest run and prints `SYNC LOAD AUDIT` lines for any chunk our code loaded synchronously. The one known path is the containment room scan (`ContainmentControllerBlockEntity$Scan.advance`): `Level.isLoaded` follows tickets, so a boundary chunk that has a ticket but is still being read makes the following `getBlockState` wait for it. The acceptance tests that force chunks and scan in the same tick depend on exactly that read (a chunk loaded this tick is not yet visible to `getChunkNow`), so it was left as it is; the wait is for a load already in flight, never a new one.

## Automated checks

The full core display adds GameTest classes `FcdRodTests` (rod selection, steps, continuous stepping; fixture
75,900), `FcdIndicationTests` (indications, LPRM strings, coordinates; 77,900), `FcdDisplayTests` (layout, mirroring,
span and refusals, unbound, unformed and old saves, matrix presses; 79,900), `FcdSnapshotTests` (the codec and one
patch per change; 81,900) and `FcdModelTests` (style table and model file contracts). They run in their own batch
`fcd` on a real formed 15 x 15 reactor and a panel wall, and need CC for nothing. `:mod:runTurbineModelCheck
-PbwrFcdClientCheck` (stages with `-PbwrFcdClientStages`, comparisons with `-PbwrPanelImmediate`,
`-PbwrFcdLegendsPerFrame` and `-PbwrFcdProcedural`) measures frame cost and writes screenshots to
`run/turbineModelCheck/fcd-check/`; it fails if the legend cache is not used.

The multiplayer check (`:mod:runMultiplayerCheck`, a dedicated server and two real clients) and the GameTest `PanelHeldControlTests` (a held rod control
is released when its panel chunk unloads; fixture 85,900) are described under "Dedicated server and two real clients (C3)" below. The
required GameTest count is 292 (291 before C3).

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

The control-room furniture (ROOM-FURNITURE.md) adds the GameTest class `RoomFurnitureTests` (11 tests, fixtures around 110,000 /
110,000, each run once the fixture chunks answer entity lookups so drop counts are real): registration with item, recipe, loot
table, lang name and creative tab entry; every piece placed through the real item for all four facings with the footprint
checked; every block of every multi-block piece broken through the real player game mode (survival: the whole piece goes and
exactly one item drops; creative: nothing drops) and removed by command or neighbour update; nothing placed when any block of
the footprint is blocked or the top is above the build limit; the desk monitor cycle and the round desk flip; shapes for every
facing (desk height 0.75 m, free kick space); and every state surviving a save. `:mod:runTurbineModelCheck -PbwrFurnitureClientCheck`
(class `FurnitureClientCheck`) builds a small control room in a disposable world, checks that every furniture state bakes to quads
with no missing texture, and writes eight pictures (seven of the furnished room and one of the inventory icons) to `run/turbineModelCheck/furniture-check/`.

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
workspaces. GameTests use `mod/run/turbineGameTest`; its blocks persist between runs, but `:mod:runTurbineGameTest`
deletes the stored entities (`world/entities`) first. Fixtures that clear a loaded reactor drop its fuel assemblies as
items, the chunk saves them, and thirty runs later tens of thousands of them made the `fcd` batch take seven minutes
instead of eleven seconds (every item's collision check scans its whole chunk section).

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

### Client check screenshots: 1920 x 1080, supersampled

Every picture the client checks of `:mod:runTurbineModelCheck` save (panel incl. its trim, duplex, realscale and owner stages,
furniture, networking, mimic, and the other checks of that run config) is a **1920 x 1080 PNG, anti-aliased by 2 x 2
supersampling**, written by the one helper `dev.bwr.mod.devtest.CheckShots` (`save`, or `saveKeeping` when the check also reads
pixels). The run config opens a 1920 x 1080 *windowed* client (`--width 1920 --height 1080`); a decorated window cannot be 1080
high on a 1080p monitor (Windows caps the client area, 1061 high here), so the saved picture does not depend on the window:
`CheckShots` renders the same frame once more into a 3840 x 2160 main render target (the window is never made larger; the `Window`
temporarily reports the doubled framebuffer size and GUI scale, so the GUI, scissor boxes and line widths stay consistent),
reads it back and averages every 2 x 2 block (area filter) down to 1920 x 1080. The extra frame costs about 60 ms and the
1080p PNG write another 125 ms or so (188 ms a shot in all, against 125 ms for a plain 1920 x 1061 capture).

* `-PbwrNoShotAA` saves the plain framebuffer at window size instead (debugging; 1920 x 1061 on a 1080p monitor).
  `-PbwrShotPlainCopy` also writes `<name>-plain.png` next to each picture for comparisons. With Iris loaded the plain
  framebuffer is saved (Iris replaces the pipeline).
* The checks' **pixel assertions read the plain live framebuffer capture** (taken first, at window size), never the saved
  supersampled file, so they measure what the game draws. Where a number was tuned at 1280 x 720 it is scaled, never lowered:
  the round-meter numeral boxes of `PanelClientCheck` are mapped by `s = height / 720` about the picture centre with the
  dark-pixel gate `60 * s * s`; `CoolingClientCheck.brightVaporPixels` scales its region and sampling step by `width / 1280`
  (the constant 1000 is unchanged because the sampling density is). Both are the old values at 1280 x 720.
* Other run configs (`refuellingCheck`, `reactorFitCheck`, `advancementClientCheck`, the multiplayer clients) keep their own
  window sizes and their own plain screenshot calls.

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

## Paperless recorder (DX1000 / DX2000)

GameTests: `RecorderAlarmLogicTests` (the alarm state machine, no world), `PaperlessRecorderTests` (dots and their minimum and
maximum, stop and start, the alarm log, ACK from the FUNC soft key and from a bound acknowledge pushbutton, patches that keep a client
copy equal, saves and old saves, validation, the makes' limits, ports, keys, free messages; fixture origin 88,900, 64 blocks apart)
and `PaperlessRecorderScreenTests` (every screen of both makes painted headless in white and black and written to
`run/turbineGameTest/recorder-screens/`, the blink, the paint cost, the key layout). The required GameTest count is 318.

`:mod:runTurbineModelCheck -PbwrRecorderClientCheck` (class `RecorderClientCheck`, stage groups `-PbwrRecorderClientStages=shots,keys,performance`)
places a DX1000 and a DX2000 with the valves' signals bound and a recorded history, photographs every screen in white and black,
both bezels (the DX2000's cover closed and open), the menu, the FUNC strip, an alarm before and after ACK and each screen from
reading distance, into `run/turbineModelCheck/recorder-check/`; clicks every face key through the real click handler and checks
what the client and the server did; opens the setup screen from MENU and the wrench, edits and sends it, and pages the bind
screen's 48 ports; and measures four DX1000s at 12 channels and a DX2000 at 48, 5 s per division, against the same wall with no
recorders. Result on the development machine: the recorders' render path 0.13 ms and screen painting 0.011 ms per frame (a repaint
is 0.39 ms, 8 a second for each recorder: a new dot every 3.3 ticks and the one second refresh), frame time 0.72 ms against 0.45 ms
without them (delta 0.27 ms); the gate is under 1 ms.

## Dedicated server and two real clients (C3)

`:mod:runMultiplayerCheck` proves the control panels, the full core display binding and the rod select matrix on a real
dedicated server with two real game clients at the same time. One Gradle invocation does all of it (about four and a half minutes on the development
machine; two windows of 960 x 540 open, and the machine needs a few GB of free memory for three JVMs):

```powershell
.\gradlew.bat :mod:runMultiplayerCheck --offline --console=plain
# the same with both clients under Sodium, Iris and a shader pack:
.\gradlew.bat :mod:runMultiplayerCheck -PbwrRenderMods=stable -PbwrShaderPack=ComplementaryReimagined_r5.9.3.zip --offline --console=plain
```

The driver task (in `mod/build.gradle`) writes a fresh `run/mpServer` (`eula=true`; `server.properties` with `online-mode=false`,
`server-ip=127.0.0.1`, `server-port=25599`, `spawn-protection=0`, a flat world with a single bedrock layer, survival, peaceful),
starts the `mpServer` run (type server, `--nogui`), waits for `Done (`, starts `mpClientA` and `mpClientB` (`--username
BwrTesterA` / `BwrTesterB`, their own game directories `run/mpClientA` and `run/mpClientB`, `--quickPlayMultiplayer
127.0.0.1:25599`) and waits for the verdict. Everything is on 127.0.0.1: nothing listens on another interface, nothing goes to the
internet (the client talks to the address as an IP because `localhost` resolves to IPv6 first under the dev JVM's settings and
the server does not listen there). The build fails on `MP CHECK FAIL <reason>`, on a process that dies, and after 25 minutes.
Logs are `run/mp-check/server-1.log`, `server-2.log`, `clientA.log`, `clientB.log`; screenshots are in
`run/mpClientA/mp-check/` and `run/mpClientB/mp-check/`.

The server conducts (`MpServerCheck`). When the first player joins it builds the fixture: a formed 15 x 15 reactor with its drives
supplied and every rod at notch 12, an MSIV, and a panel wall of 10 x 6 blocks with a full core display, a rod select matrix, a
control switch (to the MSIV), three meters (MSIV position, selected rod, one to bind), four pushbuttons wired to
`selected_rod_withdraw`, `_insert` and the two continuous commands, lamps and a spare pushbutton. Each player is given a
wrench, a linker (already holding the reactor), two lamps and two pushbuttons, and stands in front of the controls in survival. It
writes one step file at a time into `run/mp-check/` (what each player should do), waits until the rods are at rest and for a sync
interval, reads back what each client saw, compares it with its own state, and writes `MP CHECK PASS` or `MP CHECK FAIL`.
The clients (`MpClientCheck`) act only through what a real player sends: the mod's own payloads (`PanelPayloads.Operate`, `Place`,
`Remove`, `Configure`, `LinkerOpen`, `Bind`), inventory and hotbar clicks, the movement keys, and the Disconnect and Join a player
does; they never touch a server object. After every step they write what their own client level holds (the FCD and matrix
snapshots, the switch, the meters, the binding, every label, their inventory) and take the step's screenshot. The server never
trusts them: it compares every number with the reactor and the panel it holds.

| Scenario | What is asserted |
| --- | --- |
| S0 | both clients show the same fixture (labels, FCD, matrix, switch, meters) as the server |
| S1 | A selects a rod on the matrix and steps it twice while B turns the switch, with the two packets in the same tick, A first and B first, and again the other way round (B selects and steps, A turns the switch): nothing lost or doubled, and both FCDs, both matrices and both switches equal the reactor and each other |
| S2 | both bind the same meter to different signals with the linker at once, same tick, A first and B first: the later packet wins deterministically, the clients and the server agree, no sink appeared |
| S3 | both remove the same component at once (exactly one item comes out), each removes a different one, both place on the same spot (one lamp stands, the loser keeps the item), each places and renames through `Configure`: the ledger of every bwr item (both inventories, item entities, every component on the panel) is the same before and after every step and the clients agree on every label |
| S4 | A selects rod 100, holds the continuous withdraw button, repeating the press as the client does, and drops off the network while holding: the hold is gone within a second and a half, the rod stops, nothing continues, the selection stays (it is reactor state), B sees the stopped rod, A rejoins and its FCD equals the reactor in every cell |
| S5 | B leaves and rejoins while A keeps stepping: no step lost, B resyncs |
| S6, S7 | the server stops (the driver then starts it again on the same world) with both players on, both clients rejoin on their own: panel, bindings, labels, switch, selection, matrix, rod demands and the item ledger are exactly what they were, and both can operate again |

Result on the development machine: `MP CHECK PASS` in 246 seconds on vanilla and in 251 seconds with both clients under Sodium 0.8.13, Iris
1.8.14-beta.1 and Complementary Reimagined, after the last change (earlier passes before the Disconnect fix below took 268 and 269 seconds; the runs
before those stopped on defects of the check itself, which were fixed). What the screenshots show (all looked at, side by side for
the two clients): both players see the same wall, in the same state, at every step. The selected rod's button on the matrix and the
highlighted rod on the FCD move together; the meters and the switch agree; dropped items lie in the same places on both screens;
the other player's body is visible beside the camera; after A leaves, B's screen shows the message and the unchanged wall; after
the restart the wall, the dropped items and the selection are as before.

### A real bug it led to

A held rod motion control is let go by `PanelTimers` a second and a half after the last press (the fail-safe that covers a client
that stops repeating its press), and the dedicated server run confirmed that a player who drops off the network stops the rod. The
timer, however, drops the entry of a panel whose chunk is no longer loaded, while the "held" flag lives in the reactor controller,
which may be in a chunk that stays loaded. A player who held the control and left with nobody else near the panel therefore left
the rod withdrawing for good: the release never reached the reactor. `PanelHeldControlTests` (GameTest, fixture 85,900) shows it:
it fails without the fix (`the rod's continuous withdraw is still held after the panel that held it unloaded`, five ticks after the
last press, with the chunk already gone). A panel now lets go of its held controls when its chunk unloads or the block is removed
(`PanelBlockEntity.releaseHeldControls`), exactly as the timer would have, and the release reaches the plant. The multiplayer check
keeps the panel chunks loaded (the other player stands next to them), so it covers the timer case and the GameTest the unload case.

### Limits

* The clients send the payloads a click, the wrench and the linker screens send; they do not aim a camera and click (the server
  still re-checks reach, permission and the item in hand for every one). The binding screen is opened by the server's real
  `LinkerScreen` payload and closed by the client.
* The player's Disconnect button closes the connection and the server sees it at once (`lost connection: Disconnected`). A client that
  vanishes without closing it (a crash, a cable) is noticed only after the server's keep-alive timeout, 15 to 30 seconds. The first
  versions of this check disconnected without closing the connection by mistake and so waited for that timeout, which is slower
  than the step's limit under Sodium and Iris. The held control's fail-safe does not depend on either.
* The server and the clients exchange step files and acknowledgements through the run directory (`run/mp-check/`), on the same
  machine; nothing of that goes through the game.
* The check does not run with CC:Tweaked absent for any reason of its own (it names no CC type); a `-PbwrNoCC` run of the client and
  server works the same way.
* Concurrency is as fine as one server tick: two packets that arrive in the same tick are handled one after the other on the
  server thread, in arrival order; the deterministic winner of the bind race is the later one.
* One early attempt ended with client B's JVM gone without a trace before it joined (no crash report, no log line; the machine was
  loaded by another long job); the next runs passed. It was seen once.

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

## Machine screen check and style tokens

`gradlew.bat :mod:runTurbineModelCheck -PbwrGuiScreenCheck` (class `GuiScreenCheck`) opens every machine screen (14 menu types,
49 states: formed and unformed, computer-held, alarm values, a long title, every cooling design, the four service panels, both
refuelling overlays, the Terry PORTS tab, the INFO tab scrolled) with fabricated client-side menu data (no machine or server round trip:
a screen's layout is a function of its menu's public fields) at GUI scale 2 and GUI scale 4 in a 1920 x 1080 window, and saves a
supersampled 1920 x 1080 picture of each to `gui-check/scale2/` and `gui-check/scale4/` of the run directory
(`mod/run/turbineModelCheck`); the pointer is a fake one (the screen is drawn with a pointer the check places, off the screen unless a
state is about hover), and eight screens are also photographed laid out for 320 x 240 and 427 x 240 (`gui-check/floor/`). It then lays every screen out again at 320 x 240 and 427 x 240, the smallest GUI sizes the game gives a
screen (the game picks the largest scale that leaves at least 320 x 240). It asserts, per screen and size:

* no visible widget, panel or slot lies outside the window, no widget lies outside its panel, no two widgets overlap, no widget
  covers an inventory slot, and the body does not need scrolling at either real scale or at 427 x 240 (it may at 320 x 240);
* every string the style draws lies inside the panel (across always, down unless it is scrolled content, which is clipped by
  design): text is cut with an ellipsis and a tooltip, never left to run over;
* once per screen: every enabled control can be reached with Tab, has a tooltip and a narration title, and a focused tab is selected
  by Space; Escape closes a pump panel with its speed field focused or not.

`-PbwrGuiCheckLenient` only logs violations (that is how `GUI-AUDIT.md` measured the old screens: 39). The check needs no CC:Tweaked
or Mekanism and runs the same with `-PbwrNoCC`.

`GuiStyleTokenTests` (a GameTest class, pure: `BwrStyle` imports nothing from Minecraft) checks the design tokens as numbers: every
text-on-background pair the screens draw (`BwrStyle.textPairs()`, 60 of them) reaches 4.5:1, every graphic part 3:1, the spacing scale
is a 4 px scale, each status colour has its own shape and word, the type scale uses whole-number sizes, and the contrast function
matches the WCAG definition. `theOldPaletteWouldFailTheSameRule` runs the colours of the old screens through the same rule and expects
them to fail, so the rule is known to be able to.

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

## Premade control boards (the board blueprint)

The GameTest class `PremadeBoardTests` (fixtures around 163,700 / 163,700, 64 blocks apart) covers CONTROL-PANELS.md section 2e:
every shipped `panel_board` file parses and validates and is the board the data pack loader registered; each of the five boards
keeps its key parts; the parser refuses each kind of bad template with a reason (unknown type, off the surface, overlap,
a size the type does not have, a missing size, an unknown config field, a value out of range, a label too long or used twice,
frames sharing a block, a span of too many blocks, a crowded block, a mimic path of one spot, a reserved space over a
component); `repeat` and `names` expand; every board placed through `BoardPlacer` in a fresh world has exactly its panel blocks
and components (type, place, size, label, caption and every setting), leaves its reserved spaces empty, has its mimic paths
and comes out **unwired**; a board faces all four ways; a blocked, out of world or forbidden (permission hook) site is refused
with nothing placed; survival refuses with the missing items listed, consumes exactly the bill (panels, parts, tape) and leaves
spare items alone; the blueprint item cycles and places through `useOn`; the sync packet round trips.

The client check `-PbwrBoardClientCheck` (`BoardClientCheck`, run through `:mod:runTurbineModelCheck`) places the five boards with
the real placer, checks every component reached the client, and writes 1920 x 1080 pictures of each (front, three-quarter and
four close-ups) and of the blueprint ghost (fits, and with a block in the way) to `run/turbineModelCheck/board-client-check/`.
`-PbwrBoardClientStages=rod_control-front` filters by picture name.

`python tools-generate-boards.py --check` fails when the shipped JSON differs from the generator.
