# Testing and release verification

The repository maintainer owns the release checks below. Contributors should
run the checks affected by their changes; the GitHub workflow repeats the
headless checks on every push and pull request, and supports manual dispatch.

## Automated checks

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
