# Testing and release verification

The repository maintainer owns the release checks below. Contributors should
run the checks affected by their changes; the GitHub workflow repeats the
headless checks on every push and pull request, and supports manual dispatch.

## Automated checks

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
to **186 methods in 29 classes**. New tests may change those counts; the runner's
output is authoritative.

### Minecraft-facing coverage

The mod uses NeoForge's headless GameTest server and dedicated-server harnesses
instead of a JUnit test source set. Their assertions are automated and exit
nonzero on failure. They are explicitly executed by CI, not implicitly by
`:mod:check`, which enforces the source-level hardware/control design rule.
Development test classes and fixtures are excluded from the release JAR.

The server suite includes assembly placement, ports, resource conservation,
save/restore, teardown, pump/valve accounting, and the regressions below.

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

ECCS and feedwater controllers keep their own route caches. The cached objects
contain positions, block states and measured valve openings, not inventories,
controller block entities or steam allowances. Source/destination objects and
the shared steam ledger are resolved live.

- A changed or removed known pipe, changed valve opening/orientation, or an
  unloaded dependency invalidates the route at its next use.
- New branches and previously unavailable search frontiers are resurveyed at
  least every **five ticks** (0.25 seconds at 20 TPS).
- Time moving backwards invalidates cached results too.
- Each controller retains at most one result per port role; destruction or
  unload discards that controller's cache along with the controller instance.

The GameTest log prints `F16 PLUMBING` measurements for HPCS, electric feedwater
and turbine feedwater fixtures with 32 connected water pipes. It compares the
original uncached tracer, warm-cache lookups, and advancing-tick lookups including
periodic rebuilds. Allocation uses the JDK thread allocation counter. Timing is
reported, not used as a brittle pass/fail threshold. This measures the plumbing
part of ticking; it is not a whole-plant or multiplayer TPS benchmark.

## Manual release checks

The maintainer should run the relevant client model/GUI harness after visual or
screen changes (`:mod:runTurbineModelCheck` with the matching panel option).
Inspect the resulting screenshots, port alignment and GUI controls. Repeat the
asset exporter's `--check` when generated models change. These graphical checks
are separate from headless CI.

Record the final JAR hash, executed checks and known limitations in
`BUILD-STATUS.md`. Passing tests do not close unrelated findings automatically;
`PHASE-ONE-REAUDIT.md` tracks the five separately reproduced R01–R05 issues.

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
