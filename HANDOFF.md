# Agent Handoff

## 2026-09-21: physical steam admission valves and branding

Latest user requested stop valves, one fine upstream steam-control valve for
the HP-to-LP plant, and the Realistic BWR name/logo. Implemented and built;
see [STEAM-VALVES.md](STEAM-VALVES.md) and the latest BUILD-STATUS entry.

`TurbineValveBlock` supplies two opposed horizontal steam flanges and opens
`TurbineValveMenu`. `TurbineValveBlockEntity` saves target/actual position and
tracks shared source allowances once per tick. Manual GUI and optional
`TurbineValvePeripheral` control it. No automatic plant commands or new
redstone override were added. Stop/control blocks start closed.

`SteamValveRouting` handles downstream nozzle opening and actual Mek export
paths. It honors closed branches/bypasses, does not traverse machines, and
never loads chunks. `PowerSteamNetwork` carries the selected path's valves;
main-turbine draws debit their shared allowances and the existing nozzle/HP
exhaust ledger. Valve opening is applied once to vessel supply. Series valves
use the tightest opening; parallel conductances are not added in this first
model. Legacy lines without new valves preserve their nozzle boundary.

HP/LP panels and peripherals now report status only. Local `setRunning` and
`setAdmission` are removed, and old saved Running/Valve fields are ignored.
Steam/energy inventories persist. Existing direct-fed turbines automatically
use supplied steam; users should add inline valves before operating them.

Blender source: `art/models/steam_valves/steam_valves.blend`, with authoring
script and mesh exports beside it. `tools-export-steam-valves.py --check`
reproduces all 14 game resources. User artwork was converted to PNG preserving
every decoded pixel and referenced by `logoFile` in mod metadata. Registry ID
`bwr` is unchanged. Final JAR, validation counts and hash are in BUILD-STATUS.
The subsequent GitHub update request includes this patch and all preceding
pending turbine, pump/pipe and volume-based recirculation changes.

## 2026-09-20: modular TX-10-style turbines and TX-NLCH-style generator

Added `hp_turbine` (5 x 5 x 9), `lp_turbine` (7 x 5 x 7), and
`nuclear_generator` (5 x 5 x 9), authored through the live Blender connection.
Editable isolated scenes are in `art/models/power_turbines/power_turbines.blend`;
the previous pump scenes were preserved. `tools-export-power-turbines.py` uses
the shared UV/area-preserving cell exporter. Regeneration checks for both new
and earlier modern pump/pipe assets pass.

`PowerModuleBlock` reuses full-object placement/removal from `PumpAssemblyBlock`
with three new kinds. `PowerTrain` follows touching coaxial shaft ends, allows
reversed sections and multiple generators, and allocates work once per tick.
Steam piping is separate: HP draws from actual RPV nozzle claim ledgers; LP
draws finite HP exhaust inventory. Shared headers allocate steam by admission
demand. No chunk is loaded by a topology search. Maximum 32 modules per train.

`PowerTurbine` / `SteamInventory` implement bounded mass and enthalpy inventories
and a simplified pressure-dependent expansion. LP temporarily condenses to
40 C return water; residual heat is explicitly rejected. Actual condenser/MSR
hardware is deferred as requested. LP fluid capability is output-only at its
water flange; real Mekanism pipe transfer is tested. Generator FE output is
only the copper side terminal, 1,500 MW cap, 98.5% efficiency, existing 13.4 W
per FE/t calibration. No automatic protection was introduced.

Panels expose manual admission, Start/Stop, steam flow, pressure/temperature
estimates, shaft speed, section/train MW and FE. CC has `bwr_hp_turbine`,
`bwr_lp_turbine`, `bwr_generator`, with `setAdmission`, `setRunning`, `getStatus`.
Steam inventory, fractional water, FE and controls persist. Rotor kinetic
energy and external water-temperature transport remain future work.

Verification: five core tests; 15 real-server scenarios including 2 HP / 4 LP,
balanced parallel supply, nozzle sharing, shaft gap/height/reversal, all four
rotations, correct capability faces, Mekanism water, save/load and conservation;
the previous 73 pump, 96 assembly and two plumbing scenarios also pass.
Opt-in client check: `runTurbineModelCheck -PbwrPowerPanelCheck`; generated
screenshots live in `mod/run/turbineModelCheck/power-check`. The client check
uses a fixed camera in a disposable world and extracts FE through the real
capability as a test electrical load. Dev fixtures are stripped from the JAR.

See [MODULAR-TURBINES.md](MODULAR-TURBINES.md) for building instructions and
modeling limits, and [BUILD-STATUS.md](BUILD-STATUS.md) for the artifact hash.
This work is local; the user has not requested a commit/push for this feature.
All earlier uncommitted pump, pipe and recirculation work is preserved.

## 2026-09-20: ten normal jet assemblies per RCP

User requested increasing the external recirculation pump limit from eight to
ten. `RecirculationSizing.JETS_PER_EXTERNAL_PUMP` is now **10**; this supersedes
the eight-assembly calibration in the historical entry below. The INFO tooltip
now reads the shared constant. Pump requirements and the CC sizing map already
derive from it. Volume-derived jet targets, matching and internal pumps remain
unchanged. The no-jet allowance is still 10% of each drive's assisted capacity.

Updated sizing and runtime expectations verify 20/30/40 normal assembly capacity
for two/three/four full-speed RCPs, subject to installed jets and vessel demand.
A 32-assembly vessel reaches 62.5%/93.75%/100%; a 44-assembly vessel requires
five RCPs. Updated the current README and recirculation/model guides.

Validation: six core sizing tests, 73 pump runtime scenarios, 96 RCIC/HPCI
assembly scenarios, both turbine plumbing scenarios, build and JAR audit pass.
Artifact hash is in [BUILD-STATUS.md](BUILD-STATUS.md). Changes remain local;
this turn did not request a commit or push.

## 2026-09-20: vessel volume, jet requirements and drive limits

`core.flow.RecirculationSizing` owns the gameplay calibration: base interior
volume 200, 12 normal placed jet assemblies, cube-root scaling rounded up to
opposing pairs, eight normal equivalents per RCP, and 1.2 per RIP. The whole
geometric interior counts, including height; falling water level cannot lower
the target. The no-jet route retains 10% drive efficiency. Saved large jets still
count 1.5 equivalents per matched assembly, limited by the smaller partner.

`RecirculationNetwork` computes capacity and actual speed-limited delivery in
assembly equivalents, divides by the volume-derived target and caps at 100%.
It deduplicates registered positions, preserves running-pump capacity when a
parallel pump stops, and reports consistent physical kg/s on individual pumps.
This removes the previous fixed 10% per jet / 50% per RCP percentages.

`ReactorCore.setRatedCoreFlowKgPerS` rebases actual/demand fractions while keeping
physical kg/s unchanged. The controller applies it on every successful geometry
validation, including height-only changes, without replacing the operating core.
`RatedCoreFlowKgPerS` is saved alongside `Core`; pending saves preserve it too.
Restore installs the saved reference before `fromState`, then rebases against
current geometry. Missing/invalid references use the legacy fixed reference.
The reactor state record is unchanged; the reference is plant configuration.
Thermal MW, water storage capacity and fuel ratings are not rescaled here.

`ReactorConfigurationInfo` syncs volume, required normal jets, required external
drives and required kg/s. INFO displays the construction targets and tooltips;
`getRecirculationSizing()` exposes them through CC. Targets assume normal jets
and external pumps at full speed; RIPs can contribute independently.

Validation: 24 targeted core tests and 73 pump runtime scenarios pass, as do
turbine checks, the client render/sync checks, build and JAR audit. Runtime tests
include real vessel height changes, pending/legacy NBT, maximum footprint,
32/40 jets on two/three/four drives and stopped-drive behavior. The updated INFO
screen was visually inspected. See [BUILD-STATUS.md](BUILD-STATUS.md).
This patch and the preceding modern pump/pipe patch are local, not committed or
pushed. The user will provide drawings for a future steam turbine; turbine work
has not begun in this patch.

## 2026-09-19: modern pump ports and pipe colors

`ModernPumpAssemblyBlock` adds a `modern` state property to LPCS, HPCS, RHR,
motor-feed and turbine-feed pumps. Missing/false loads their original manifests
and occupied cells. New item placement sets true and uses `<id>_modern`, with
the controller at the center of the base. Ownership includes the layout flag;
loot supports each layout's root. Pick up/replacement upgrades a saved pump.
Port offsets and reserved dimensions are in [PUMP-MODELS.md](PUMP-MODELS.md).
The existing DVSS, jets, pump controls and physics were not changed.

All new geometry was authored through Blender. The original pump gallery was
copied into isolated scenes, preserving its textured parts; direct round necks
and bolted flanges replace the original nozzle/adapter geometry. RHR body scale
is 1.15, both feedwater bodies 1.25. The editable file, UV-preserving mesh
snapshots, build script and previews are under `art/models/modern/`.
`tools-export-modern.py` clips geometry into cells, verifies surface area and
writes 949 assets; `--check` is read-only. The original pack importer reapplies
this export after the narrow jet, preserving the modern assets during reimports.

`PaintedPipeBlock` shares the dye interaction and `paint` blockstate for both
pipe types. `PipePaint` contains NONE plus 16 vanilla colors. Client-only
`PipeColors` tints material index 0; steel uses -1. The 64 Blender connection
patterns are shared by steam and water, with blue/amber default bands.
Connectivity is still controlled by the existing service-specific code.
New solid meshes have UV (.5,.5): sampling an atlas corner caused black flecks
in the first Minecraft preview. The final game captures verify that fix.

Validation: 71 pump scenarios, all 96 turbine assembly scenarios, both turbine
plumbing scenarios, client model checks, visual captures, asset reproduction,
asset/JAR audits and the mod build pass. Three existing flow fixtures were
updated to route from the actual modern port coordinates. No core changes or
new core acceptance run. See the newest [BUILD-STATUS.md](BUILD-STATUS.md) entry.
This patch is local; the preceding rod/jet update was pushed as `82b9514`.

## 2026-09-19: rod travel and narrow, same-row jet pairs

`ReactorCore.rodPositionNotches` now holds continuous physical position, while
the integer notch remains the last latch crossed. Normal motion keeps its old
speed; continuous scram consumes charge in proportion to distance. Rod worth
interpolates its original notch values and nodal absorption uses fractional
positions. Per-drive availability holds a failed drive without changing demand.
`ReactorState`/NBT append `rodPositionsNotches`; empty arrays restore old notches.
The core holds restored positions until a command is supplied, and the mod
continues to restore operator demand through `ControlRodDriveNetwork`.
See [ROD-MOTION.md](ROD-MOTION.md).

`JetPumpBlock.NARROW` defaults false for old saves and is true on new placements.
The new layout reserves six cells (1 × 6 × 1), while `jet_pump_legacy` preserves
the original twelve cells. `tools-narrow-jet.py` merges the old column meshes and
scales X by 0.85, preserving height, depth, materials and UVs. The pack importer
calls it after regenerating the original models; `--check` verifies 47 assets.

`RecirculationNetwork` tries reflection across each vessel axis while retaining
the transverse row, opposite walls and facing, and equal height. It then tries
the prior 180-degree footprint reflection for existing layouts. Same-wall pairs
are explicitly rejected. Flow balance and the weak no-jet loop are unchanged.
See [RECIRCULATION.md](RECIRCULATION.md) for placement. Existing wide jets need
pickup/replacement to shrink; loading a save does not alter their occupied cells.

Validation: 160 core acceptance tests passed; the final restore refinement also
passed all 12 targeted motion/save tests. All 50 pump runtime scenarios, model
baking, live panel/world previews, asset audit, JAR audit and build pass. The
in-world opposing narrow pair reports 2 matched and 0 unmatched. The newest
[BUILD-STATUS.md](BUILD-STATUS.md) entry records the artifact and test details.
The README now documents the implemented systems, installation, migration,
recirculation examples, and code-derived vessel size limits for publication.

## 2026-09-18 follow-up: larger DVSS and lower pipe repair

New DVSS placements now occupy **5 × 10 × 5** (width × height × depth), with a
single swept suction-elbow mesh underneath the pump. Coordinates are uniformly
scaled 5/3; rear suction is at (0,0,2) relative to the controller, facing south,
and front discharge at (0,2,-2), facing north before rotation.

`PumpAssemblyBlock.Layout` contains manifest dimensions, ports and shapes. Its
state-aware methods let `RecirculationPumpBlock` select the previous 54-cell
layout when `enlarged=false`; new `placementState()` sets true and controller 12
for the 250-cell layout. Legacy controller 4, child offsets, saved controls and
flange positions survive. No automatic expansion touches neighboring blocks.
The CELL property now ranges 0..255; all seven other pump state mappings cover
the extra unused cells. Ownership also checks `enlarged` for RCP parts.

`tools-export-dvss.py --check` checks both current and legacy source meshes and
all 620 generated assets. Current editable source and two render previews live
in `art/models/dvss/`. The original gallery file has not been overwritten.

Validation: 48 pump scenarios (including four legacy assembled-save cases),
2,452 client pump states, all water/turbine models, five GUI screens and the
enlarged pump's in-world placement. Build, asset audit and JAR audit pass.
See the newest entry in [BUILD-STATUS.md](BUILD-STATUS.md) for the artifact hash.
The original core flow behavior and supplied jet geometry remain unchanged.

## 2026-09-18 update: DVSS model, opposing jets, reactor INFO

See [RECIRCULATION.md](RECIRCULATION.md) for current placement and balance.
`RecirculationPumpBlock` now extends `PumpAssemblyBlock` with kind RCP. Its old id
and compact-save port positions survive; new placements are 54-cell DVSS models.
`RecirculationCircuit` resolves real rotated flange cells and verifies both
headers reach the same formed vessel. Per-part FE/CC access forwards to one root.

`RecirculationNetwork` matches jet footprints under a 180-degree turn through
the vessel centre, at either of the two lowest interior elevations. No-jet
loops contribute 5% per RCP; each normal matched assembly contributes 10% capacity.
Independent drives sum before the shared cap, so stopped pumps do not dilute
working ones. RIP behavior and the core thermal solver are unchanged.

`ReactorConfigurationInfo` is a read-only serialized planning snapshot for the
new INFO tab. Reference power is unchanged; estimates do not actuate the reactor.
The Blender source, portable mesh export and preview live under `art/models/dvss`;
`tools-export-dvss.py --check` checks generated resources. The connected original
Blender gallery file was not overwritten.

The older notes below describe the preceding update.

## 2026-09-17 update: pump panels and vessel recirculation

See [WATER-PLUMBING.md](WATER-PLUMBING.md) for current build instructions.
Powered pumps use `PumpControlMenu`/`PumpControlScreen`; speed is a persisted shaft
demand separate from the flow throttle. Three-value saved arrays default to 100%
target speed. Manual panel ownership persists; computer ownership disables panel
commands until explicitly handed back. All powered pump blocks open this screen.
ECCS/feedwater Lua now provides `setSpeed(0..1)` and `getTargetSpeed()`.

The finite CST has a tank model, level screen and fractional-debt-aware external
fluid handler. Suction accepts direct Mekanism Mechanical Pipes; assembly placement
invalidates capability caches on completion. Electric status only reports motor
drive. The RPV injection port has a full cube shell to eliminate the visible gap.

External RCPs have front discharge / rear suction. `RecirculationCircuit` requires
separate water headers connecting top outlet and bottom inlet of one formed vessel.
`RecirculationNetwork` pools installed jet capacity and complete external loops;
jets use the supplied mesh with no added adapters or ports. Each pair caps flow at
10%, each RCP at 50% times speed. RIP mounting is unchanged. Existing external
recirculation lines need manual rewiring. No automatic control or world conversion.

Client QA: `:mod:runTurbineModelCheck -PbwrPumpPanelCheck` creates a new disposable
flat world, opens server-backed screens, saves images under
`mod/run/turbineModelCheck/panel-check/`, and exits. Devtest is excluded from the jar.

## 2026-09-17 update: dedicated water pipes and vessel injection

Current guide: [WATER-PLUMBING.md](WATER-PLUMBING.md). At this stage the user chose
to retain the RCP until its model was supplied; the September 18 DVSS update above
fulfills that replacement while retaining the id and compact saves. Only the
old RCIC/HPCI cubes are hidden from crafting/creative; their registry IDs and
saved behavior remain. The modeled turbine recipes now use ordinary materials.

`high_pressure_water_pipe` is a blue-banded water-only block. Its block capability
forwards pushed NeoForge condensate to actual CST/pump buffers, with bounded
loaded-chunk traversal and no per-pipe inventory. ECCS suction buffers carry
fractional draw debt across saves. Select condensate-tank suction to use them.
`pressurised_tube` retains its ID but is now named High-Pressure Steam Pipe and
cannot join water flanges, RCPs, tanks, water pipes, or injection ports.

`rpv_water_injection_port` is a six-way orientable pressure-boundary block. Formed
reactor validation assigns its owner; discharge routing checks the owner's shell
list and an outward-facing flange. Full modeled machines discharge to these ports,
not reactor controllers. Existing worlds need manual water-line replacement and
rerouting; there is no destructive automatic conversion. The HPCI STEP is still
turbine-only, so its two blue skid fittings are explicitly labeled as gameplay
adapters for the associated pump, at (3,0,0) east and (3,0,2) east in north pose.

Runtime plumbing checks, client model baking, asset audit, deterministic STEP
import comparison and jar packaging checks pass. See [BUILD-STATUS.md](BUILD-STATUS.md)
for the exact results and final artifact hash. This update follows the pump-model
integration committed as `e7631ee` on `main`.

## 2026-09-17 update: supplied pump model pack

Seven supplied textured models are integrated. HPCS, LPCS, RHR/LPCI, motor
feedwater, turbine feedwater and paired jets replace their matching placeholders;
`rip_pump` adds a reactor internal pump. See [PUMP-MODELS.md](PUMP-MODELS.md) for
the complete placement/port guide, compatibility behavior and source archive hash.

`PumpAssemblyBlock` reserves the full footprint with one controller. Saved old
blockstates load a compact mesh without expanding into adjacent builds; pick up
and replace to use full-size physical connections. `ProcessAssembly` shares
flange routing with the previous RCIC/HPCI machines. Motor/ECCS water flow follows
real pipe circuits. The feedwater turbine's shared nozzle claim is exported as
exhaust through the existing Mekanism boundary, without a second vessel debit.

`RecirculationNetwork` caps connected circuits by both paired-jet capacity and
external drive-pump output. A pair contributes 10%; an external drive contributes
50% times actual speed. RIPs contribute 10% times actual speed and mount at the
bottom-head rim, clear of the CRDs. Block/cell removal and disconnected pipes
withdraw capacity. Flow commands remain manual and the existing coastdown remains.

The GameTest now includes 34 new pump scenarios as well as the original turbine
cases. The actual client model check covers 1,236 pump cell orientations and seven
inventory/compact models, plus the earlier RCIC/HPCI checks. Results are recorded
in the newest [BUILD-STATUS.md](BUILD-STATUS.md) section.

## 2026-09-14 update: placeable RCIC and HPCI CAD assemblies

The new blocks are `bwr:rcic_twl` (3 × 3 × 2) and `bwr:hpci_turbine`
(4 × 5 × 3). Each item places its full footprint; only the origin cell owns the
pump and peripheral. Existing ECCS and feedwater block IDs are preserved.
Read [TURBINE-ASSEMBLIES.md](TURBINE-ASSEMBLIES.md) for placement, port routing,
source provenance, the turbine-only HPCI water association, and verification commands.

Geometry is generated from the supplied estimated STEP exteriors by
`tools-import-turbines.py`, including source colors, individual parts, per-cell OBJ
models, collision manifests, and blockstates. The RCIC water discharge uses the
outer upward grid cell so its tube does not join the adjacent steam riser.
The supplied HPCI exhaust blanking cover is omitted in the connected configuration.

`AssemblyPlumbing` follows separate, bounded tube circuits. Assemblies claim steam
already discharged through a live RPV nozzle and report zero additional steam draw
to the ECCS bus. RCIC takes water through its physical suction/discharge ports.
Exhaust must reach a quencher in the basin actually owned by a formed pool controller.
All operation still follows the player's redstone/Lua commands.

The shared ECCS bus now clears the final rate when its last contributor disconnects
or expires; previously it could leave injection/feedwater/relief latched in the core.
New GameTest coverage includes this regression, physical connections, tank mass,
shared nozzle claims, placement, obstruction, harvesting, rotation and NBT restoration.
`runTurbineModelCheck` checks 312 cell states and both inventory models in the client.

For current results, use the newest section of `BUILD-STATUS.md`; numerical counts
in the older handoff sections below are historical.

**You are picking up a Minecraft 1.21.1 / NeoForge mod that simulates a boiling water reactor.**
This file is the entry point for an agent starting cold. Read it before touching anything; it
exists to stop you rediscovering things that already cost hours.

The repository is public at <https://github.com/OldGunslingerWildBill/bwr-mod>, so this file and
the ones beside it are the project's front door. Keep them true; a reader must be able to finish
them knowing exactly what is proven and what is not.

`README.md` is the *design* handoff written before any code existed. This file is the *codebase*
handoff. Both are current — they answer different questions.

---

## 1. Prove the project works before you change it

Everything below is verified. If any of it fails on your machine, fix that before writing code,
because you cannot tell your breakage from pre-existing breakage otherwise.

```bash
cd <repo root>
./gradlew clean build          # BUILD SUCCESSFUL; 124 tests, 124 passed, 0 failed
python tools-audit-assets.py   # PROBLEMS: 0
python tools-check-jar.py      # OK: nothing forbidden is bundled
```

Two lines inside that build are worth reading rather than scrolling past, because they are the
project checking itself. Verbatim, on this machine:

```
124 tests, 124 passed, 0 failed, 784.7 s
checkNoProtectionLogic: scanned 98 files in :mod, no protection logic present
```

The counts are the load-bearing part; the elapsed time is not.

Those three commands are offline and need nothing but a JDK and the Gradle cache. The run tasks
need the NeoForge, CC:Tweaked and Mekanism artifacts already resolved, and they are the ones
section 5 says have **not** been re-run since the last round of fixes:

```bash
./gradlew :mod:runData              # mod loads in a real MC runtime
./gradlew :mod:runPeripheralCheck   # builds a plant on a dedicated server, calls every @LuaFunction
```

There is a Gradle wrapper, so you need nothing installed but a JDK 21.

**Finding a JDK is the first trap.** On the machine this was built on there is no `java` or
`javac` on `PATH` at all. A full JDK 21.0.7 *including `javac`* ships inside PrismLauncher:

```bash
export JAVA_HOME="/c/Users/14238/AppData/Roaming/PrismLauncher/java/java-runtime-delta"
export PATH="$JAVA_HOME/bin:$PATH"
```

If that path is gone, any JDK 21 works. NeoForge 1.21.1 requires exactly Java 21.

The physics module needs **no Gradle at all** — useful when you want a fast loop:

```bash
javac -d out $(find core/src/main/java -name '*.java')
javac -cp out -d testout $(find core/src/test/java -name '*.java')
java -cp "out;testout" dev.bwr.core.AcceptanceTests     # ';' on Windows, ':' on POSIX
java -cp "out;testout" dev.bwr.core.AcceptanceTests NodalFluxSolverTest   # one class
```

The runner takes test-class names as arguments, which matters: the full suite is 13 minutes, while
`SaturationTest` is 0.1 s and `ReactorCoreTickTest` — the slowest of the short ones — is 30.6 s.
If you put an **absolute** path in that `-cp` under Git Bash, read the `cygpath` trap in section 6
first; it will not do what you think.

---

## 2. The rule that will fail your build

> **The mod provides hardware and physics. The player provides control logic.**

There is deliberately **no scram logic, no trip setpoints, no automatic ECCS actuation and no
protection system anywhere in this codebase.** Players write all of it themselves in CC:Tweaked
Lua, from scratch. The mod exposes **measurements and actuators, never judgements**.

This is not a style preference. It is enforced by two build checks, both of which have been
negative-tested to confirm they actually fail:

| Check | Scope | Run it |
|---|---|---|
| `ReactorCoreTickTest.test07` | 1066 methods and 747 fields across 46 classes in 11 packages, discovered by reflection at runtime | `./gradlew :core:acceptance` |
| `checkNoProtectionLogic` | 98 `mod/` source files, comments stripped first | `./gradlew :mod:checkNoProtectionLogic` |

Banned name fragments in `:mod`: `shouldScram`, `autoScram`, `checkTrip(s)`, `isSafe`, `isUnsafe`,
`isHighPressure`, `isLowLevel`, `autoStart`, `mustScram`, `needsScram`, `scramIfNeeded`,
`tripOnHighPressure`, `protectionSystem`. The `:core` guard bans those **and** a word list —
`high`, `low`, `trip`, `permissive`, `setpoint`, `interlock`, `alarm`, `unsafe`, `acceptable`,
`violation` — across methods *and fields*, because a setpoint is far likelier to arrive as a
constant than as a method. It carries exactly three exemptions, each named and justified in the
test and each asserted to still exist so a stale exemption cannot sit there quietly widening.

Javadoc that *prohibits* such a name is fine and should stay — comments are stripped before
scanning, deliberately. So is "protective", as in the protective oxide layer on the cladding:
that is a film of zirconia, not a protection system, and the distinction is the whole point.

**`scram()` exists and is correct.** It is a bare, unconditional actuator with no checks. The
rule is not "no scram", it is "nothing in the mod decides *when* to scram."

Corollaries that are easy to violate by accident:

- **SRVs are player-actuated** via CC or redstone. They do not self-open on pressure. This means
  the plant has **no automatic overpressure relief at all**, which is why the stress model in
  `core/boundary/` is the only automatic consequence in the entire mod.
- **IRM ranging is manual.** Never auto-range an instrument — mis-ranging is a real startup
  hazard and auto-ranging deletes the skill.
- **Pump shutoff head is physics; an injection permissive is not.** Model the head so flow goes
  to zero. Do not implement the real plant's 500 psig interlock — that is a chosen setpoint and
  belongs in the player's Lua.
- **A period meter indicates; it does not conclude.** Twelve of them are now on the Lua surface
  (section 5). Nothing in the mod says what a period *means*. A short-period alarm, a rod block
  or a period trip are all chosen numbers and all the player's.

---

## 3. Architecture

Two Gradle modules, and the split is enforced by the compiler rather than by discipline.

```
core/   pure Java physics. ZERO Minecraft on the classpath. MIT licensed.
        40 main + 24 test files.
        boundary  eccs  feedwater  fuel  harness  instrument  kinetics  nodal
        poison  pool  thermal

mod/    NeoForge integration. MPL-2.0 licensed. 110 files, and NO test source set —
        mod/src/ contains 'main' and nothing else.
        damage  devtest  eccs  feedwater  flow  fuel  gui (+ gui/client, gui/net)
        mekanism  peripheral  reactor  registry  rods  steam  suppression
```

**There is one pump model, and it is meant to stay one.** `core/eccs/` holds
`PumpCurve`, `SteamTurbineDrive` and `EccsPump` — a general centrifugal pump with
a head-flow curve and a choice of motor or steam-turbine drive. Nothing in
`EccsPump` is about emergency cooling; it was simply written for it first and
named accordingly. It takes a `PumpDesign` interface, and both `EccsDesign` (the
six emergency machines) and `feedwater/FeedwaterDesign` (the two reactor feed
pumps) implement it. If you are adding another pump, add a nameplate. Do not fork
the model: two copies drift, and the second copy is always the one that misses
the fix.

`mod/` has grown: the GUI split into `gui/client` (screens and widgets) and `gui/net` (the menu
sync and command payloads), and `devtest/` holds the two-class peripheral runtime harness that is
stripped from the published jar.

`core/` cannot import Minecraft because Minecraft is not on its classpath. Verify with:

```bash
grep -rn "^import net\.minecraft\|^import net\.neoforged" core/src   # must return nothing
```

`ReactorCore` exposes `toState()` / `fromState()` returning a plain `ReactorState` record. That
single snapshot serves NBT persistence, client sync and the CC peripheral readout. The core never
touches NBT — `mod/reactor/ReactorStateNbt.java` does the conversion.

`ReactorState` is **33 record components with exactly one constructor, the canonical one.** A
shorter compatibility overload used to live beside it so the tree kept compiling while the
persistence layer caught up; it was a landmine, because any caller that bound to it — the NBT
reader above all — silently defaulted the newest components and un-scrammed a saved reactor. It
has been deleted on purpose. **Adding a component is supposed to break every call site**, and a
call site that still compiles after you add one is a call site that has quietly stopped
persisting something.

Tick order inside `ReactorControllerBlockEntity` matters and is load-bearing:

```java
rodNetwork.preStep(dt);   // rods move, accumulators discharge
core.step();              // physics, pressure held CONSTANT within the sub-stepped kinetics
rodNetwork.postStep();    // hardware condition written back
```

Pressure is updated once per tick and frozen inside the kinetics sub-steps. That timescale
separation is what keeps the pressure → void → reactivity positive feedback loop stable. **Do not
"fix" it by updating pressure mid-tick.** The same reasoning governs the nodal flux shape, which
is solved at 1 Hz and frozen in between — see section 5 on why that solve must stay a pure
function of its inputs.

---

## 4. The integrations, and the licensing constraint on them

Both are **soft**: the mod must load and run correctly with either absent. That is verified by
running rather than asserted — though the last such run predates the current tree, so see
section 5 before you treat it as fresh.

```bash
./gradlew :mod:runData                    # both present
./gradlew :mod:runData -PbwrNoCC          # no CC:Tweaked
./gradlew :mod:runData -PbwrNoMekanism    # no Mekanism
```

Every foreign type is reached only through a `ModList.get().isLoaded(...)` branch in `BwrMod`'s
constructor, and because the check and the reference live in *different classes*, a JVM without
the mod never resolves the type. `PeripheralRuntimeCheck` / `PeripheralRuntimeCheckCC` is split
the same way for the same reason, so running the peripheral check with `-PbwrNoCC` also proves
the guard.

**CC:Tweaked must never be bundled.** Parts of its API — `IPeripheral` among them — are still
`LicenseRef-CCPL`, which permits redistribution only "unmodified and in full". It is `compileOnly`
for publishing, `runtimeOnly` for the dev runtime only, and never in `jarJar`. Verify with
`python tools-check-jar.py`; the answer must be zero CC classes. It currently reports 172 class
files scanned in the jar, **0** bundled `dan200/computercraft` classes (10 of our own merely
reference them), **0** bundled `mekanism/` classes, and **0** entries under `dev/bwr/mod/devtest/`.

Mekanism is MIT, so bundling would be *legal*, but it is still not bundled — players install it.
Note `metadataSources { artifact() }` on the modmaven repository: modmaven publishes bare jars
with **no POM**, and without that line Gradle 404s looking for module metadata. That same pinned
Mekanism jar is now opened by `tools-audit-assets.py`, which cross-checks every foreign item id
our recipes name against what is actually in it — 4 used, 4 verified. Section 6 explains what that
check exists to catch.

Pinned versions live in `gradle.properties`: NeoForge 21.1.248, ModDevGradle 2.0.143,
CC:Tweaked 1.120.2, Mekanism 1.21.1-10.7.19.85.

---

## 5. Where the project actually stands

`BUILD-STATUS.md` is the detailed status with full command output. This is the summary.

### Verified on the current tree

- `./gradlew clean build` — **BUILD SUCCESSFUL**
- `124 tests, 124 passed, 0 failed, 784.7 s` — all of them `core/`
- `checkNoProtectionLogic: scanned 98 files in :mod, no protection logic present`
- `python tools-audit-assets.py` — `PROBLEMS: 0`, exit 0. 21 blocks; blockstate, block model, item
  model, loot table and recipe all 21/21
- `python tools-check-jar.py` — `OK: nothing forbidden is bundled`, exit 0
- The mod jar builds at `mod/build/libs/mod-0.1.0-SNAPSHOT.jar`

### The plant is now operable, and it was not before

An audit of ~37k lines found 102 defects. All but one are fixed. The ones that mattered most were
not the subtle ones — they were the ones that made the mod unplayable:

- repairing a broken vessel deleted all the fuel, the running transient and the boundary damage
  record
- every chunk reload drove all rods fully in
- the recirculation pump had no energy capability, so core flow was permanently zero
- rods and recirculation flow could not be commanded from Lua **at all**
- the mod never installed a calibrated startup source, so the SRMs read 0.015 cps against a scale
  bottom of `SourceRangeMonitor.SCALE_BOTTOM_CPS = 3.0` — a startup with no visible beginning

Then the physics that was written but not wired:

- **Rod worth is position-dependent.** The nodal solve computed per-rod flux weights and discarded
  them. They now reach `RodWorth.rodFluxWeights`, and a centre rod is worth **7.13x** an edge rod
  — `-2.00e-03` against `-2.81e-04` dk/k, where both used to read `6.34e-05`.
- **Rod indices agree.** The solver ranked rods by radius while the multiblock rastered them, and
  `setRodLatticeMap` had zero callers. Structure validation now builds a real map. Measured
  before: **0 of 16** drives shadowed their own bundles. After: the drive's own four positions are
  the top four by flux rise for **9 of 16**, worst rank 9 of 81.
- **The nodal solve is deterministic.** The warm start is gone. With per-rod weights reaching
  reactivity, a history-dependent flux shape made a restored core disagree with a running one, and
  the round-trip test holds persistence to bit-for-bit equality. `solve()` is now a pure function
  of its inputs, with an exact memo for a core whose problem has not moved.
  **This costs real time and the figures in `BUILD-STATUS.md` are the old ones.** Measured now:
  memo hit (unchanged core) **1.23 ms**; one rod moved one notch **21.7 ms**; void creeping, i.e.
  a running plant, **25.1 ms** — once per second per reactor against a 50 ms tick budget. The
  "24 ms cold / 9.8 ms warm" pair is wrong twice over: cold is ~25 ms and there is no warm path
  any more. This is an **open performance decision**. The lever that does not reintroduce history
  dependence is solving less often than 1 Hz. Reinstating the warm start is not a lever; the class
  comment on `seedFluxFromGeometry()` records the two obvious repairs and why both were measured
  and rejected.
- **Seven components were added to `ReactorState`, taking it to 33**: `scramActive`,
  `peakFuelTempC`, `intermediateRangeMonitorRanges`, `coreInletEnthalpyKJPerKg`, `rodFluxWeights`,
  `fuelExcessReactivityDkOverK`, `dopplerCoefficientPerCAtAnchor`. A save previously lost 72% of
  the axial void profile mid-transient, cancelled an in-progress scram, and on a mixed-fuel core
  got excess reactivity wrong by 5.0e-06 dk/k. See section 3 on the deleted compatibility
  constructor.
- **CC:Tweaked threading is fixed.** 194 of 205 `@LuaFunction` methods declare
  `mainThread = true` — every one that touches block-entity, level or core state. The other eleven
  return compile-time constants. **None** of them declared it before, and one re-entered the nodal
  solver from the computer thread mid-reallocation.
- **Steam-table correlations reach below their fitted band.** Saturated vapour density went from
  −46% to **+0.00%** at 14.7 psia; liquid enthalpy from −10.35% to **−0.71%**. Everything at and
  above 800 psia is bit-identical — see section 7.
- **Suppression pool capacity follows the structure the player built**, via a bounded flood fill
  that refuses open water, replacing a universal hardcoded 3,400,000 kg.
- **The fuel assembly recipe works at last.** It named `mekanism:pellet_fissile_fuel`, an id that
  has never existed in Mekanism 10.7, so `RecipeManager` dropped the entire recipe at datapack
  load and there was no crafting route to a fuel assembly at all. It is `mekanism:yellow_cake_uranium`
  now. The pre-fix `runPeripheralCheck` log still carries the evidence:
  `[ERROR] [RecipeManager]: Parsing error loading recipe bwr:fuel_assembly`.
- **Twelve period meters are on the Lua surface**, not one. Four SRM and eight IRM channels, as
  raw measurements. The single reachable one was on an SRM, which rolls over and stops indicating
  long before the plant reaches a power anyone operates at — so a player was being asked to fly a
  startup on period with no period.
- **`VesselState.canHoldPressure()` has a consumer.** An open head is a steam discharge path.
  Measured: head on, 20.0 → 29.5 psig over ten minutes; head off, pinned at 0.0 psig.

### Feedwater is hardware now, and it was not

`ReactorCore` has taken a feedwater flow and a feedwater temperature since it was
written, and until now nothing in the world drove either: a Lua program set a
kg/s number on the reactor peripheral and the vessel believed it. The normal
level control path — the one whose loss starts most level transients — had no
pump anywhere. That is the same shape as the defects section 5 lists above, in
reverse: physics with no hardware attached to it.

Two blocks now exist, `bwr:motor_feed_pump` and `bwr:turbine_feed_pump`, half
capacity each so two make a plant. They report into `ReactorEccsBus` on a new
feedwater channel, which — like every other channel there — is only written if
some machine has claimed it, so `ReactorPeripheral.setFeedwaterFlow` still works
exactly as before for a player who has not built any of this.

Three things about it are worth knowing before you change anything:

- **There is no condenser block, deliberately.** The player's Mekanism turbine is
  the condenser. Feed pumps expose a fluid tank on every face for the condensate
  return, and fall back to a condensate storage tank within 24 blocks. The
  turbine-driven pump's drive steam leaves the vessel and condenses in the same
  place the main steam does — it is **not** dumped into the suppression pool the
  way the RCIC and HPCI exhausts are, because those run for minutes into a heat
  sink sized for it and a feed pump turbine runs continuously at power.
- **Feedwater heating is modelled rather than built.** `FeedwaterHeating`
  interpolates final feedwater temperature from the condensate temperature at no
  flow to 215.6 °C at rated flow. This is load-bearing: feeding 32 °C condensate
  at rated flow costs about a quarter of rated thermal power in heating duty, so
  without it a plant simply cannot reach rated power. It also gives loss of
  feedwater heating for free — a transient where power goes *up*.
- **The motor-driven pump is 13 MW, a little under 1 MFE/t.** That is derived
  from the duty point rather than chosen, and it makes feedwater comfortably the
  largest electrical load in the mod. It is supposed to be. That cost is the
  whole reason the turbine-driven pump is worth building.

Nothing in any of it controls level. There is no level element, no steam flow
element, no three-element scheme, no feed pump trip and no runback. SPEC §15 used
to ask for a three-element controller; §15 now records why that line was wrong
and left the loop to the player.

### What has actually run, and what has not

Read this part twice. It is where an estimate of this project usually goes wrong, in both
directions.

**No Minecraft client has ever launched.** No model, texture, GUI or screen has ever been
rendered. Every log under `mod/run/logs/` is a `forgedatadev` run; there is no `runClient` log.

**A dedicated server has run, and the peripheral surface has been called.**
`./gradlew :mod:runPeripheralCheck` built a reactor, a suppression pool, a turbine outlet, ECCS
pumps and an ADS controller in a real world, force-loaded the chunks, ticked the block entities,
formed the multiblocks (`formed=true status=[Reactor formed: 25 assemblies, 4 control rods ...]`),
and invoked **every `@LuaFunction` on every peripheral** through the real CC:Tweaked capability
lookup — once with CC:Tweaked loaded and once without it, taking the guarded path. The transcripts
are in `mod/run/peripheralCheck/logs/`; read them before you write anything peripheral-facing.
The earlier claim in this file that "no `@LuaFunction` has ever executed" was wrong.

**But that run predates the fix pass, and the harness it ran under could not fail.** It counted
problems, logged them, called `server.halt(false)` exactly as it does on a pass, and exited 0
either way — so its green result proved only that the JVM reached the end. That is fixed: the
failing exit status is now armed by a shutdown hook installed at class load and cleared only by a
run that reaches `finish()` with nothing wrong, so a crash during plant construction still exits
non-zero. The harness also gained an off-thread probe pass that checks the `mainThread`
marshalling actually marshals. **Neither of those has been exercised.** The newest run directory
is a day older than the fixes.

So: **re-running `:mod:runPeripheralCheck` is the cheapest real verification available**, and
`:mod:runData` after it. Do that before you believe anything in this section that is not a test
count.

**`:mod` has no test source set at all.** `mod/src/` contains `main` and nothing else; the 124
tests are exclusively `core/`. Every mod-side change in the fix pass is verified by **compile and
by reading only**. That is the weakest link in this project and you should treat it as one.

**Taking a core critical from a Lua console is still the highest-value next action.** What has
changed is that it is now *possible* — before the fix pass the rods and the recirculation flow
could not be commanded from Lua, the flow was pinned at zero, and the SRMs were below scale, so
there was no startup to fly. It exercises the peripheral, structure validation, CRD binding,
ticking and NBT together and will expose more real defects than any further building.

### Still short, and honestly so

- **No `BlockEntityRenderer` exists**, so head-off core rendering is absent. That is the only
  reason SPEC §14 step 10 is not done.
- **Textures are flat-colour placeholders.** The project owner is doing real art separately.
- **Partial core uncovery is not representable** — the thermal model uses two lumped nodes.
- **SPEC §8.1 stages 4, 6 and 7 have no implementation.**
- **Containment does not exist**, so the hydrogen the model generates has nowhere to go.
- **`CoreLoading.heatPerFissionScaleFactor()` is defined and never called.** A datapack's
  `heat_per_fission_mev` therefore still does not reach thermal output. It is one wiring job in
  `core/`, deliberately left out of scope for the fix pass.
- **There are no APRM period meters.** `ReactorCore` builds `PeriodMeter`s for the 4 SRM and 8 IRM
  channels only, so above IRM range there is no period reading anywhere.
- **The suppression pool basin survey counts contiguous water.** It refuses a body that escapes
  the survey box and caps the total, which is what stops an ocean or a one-block channel to one —
  but a natural pond that fits inside the box and under the cap still counts as a basin somebody
  dug.

`BUILD-STATUS.md` carries the SPEC §14 step table and the per-step evidence. The two steps it
scores short are **8, `IPeripheral` exposure** and **10, refuelling**. Neither is short on physics:
step 10 wants a `BlockEntityRenderer` so the head visibly comes off, and step 8 wants the
peripheral surface run again on the tree as it stands now.

---

## 6. Traps this project already fell into

Each of these cost real time. They will bite you the same way.

**Git Bash does not path-translate inside a semicolon-joined `-cp`.** Write
`javac -cp "/tmp/out;$(cat modcp.txt)"` and the first entry is silently discarded: the whole
string stops looking like a lone POSIX path, so nothing is converted, and `javac` compiles against
whatever stale prebuilt classes are on the rest of the path. The symptom is *phantom errors about
code that no longer exists*, which sends you hunting a compiler bug. Use
`javac -cp "$(cygpath -w /tmp/out);$(cat modcp.txt)"`. This cost real time **twice** during the
fix work. Relative entries (`-cp "out;testout"`) are safe because there is nothing to translate.

**Never trust a piped exit code.** `gradle ... | tail -20` gives you `tail`'s exit code. This
produced a confident report of "BUILD SUCCESSFUL" on a build that had failed with 40 errors.
Always redirect to a file, check `$?`, *then* grep the file.

**A harness that halts the server exits 0 whatever it found.** `PeripheralRuntimeCheck` counted
failures, logged "FAIL with N problem(s)", then called `server.halt(false)` — and
`DedicatedServer.onServerExit()` sets no status, so the process ended 0. `runPeripheralCheck` is a
`JavaExec`, so Gradle passed the task, and the project's status documents cited that green run as
evidence. The fix is to make the status **failing from class load** and clear it only on a clean
finish, which is also the only shape that survives a crash during setup.

**A test that measures a defect is not a test that catches it.** A round-trip test quantified that
41% of an in-progress quench spike was lost across a save, and filed it as a "finding" rather than
failing. The bug survived. If you measure something wrong, fail on it.

**Hand-written field lists rot silently.** `assertStatesIdentical` enumerated 26 record components
by hand, so when `hydrogenKg` was added nothing compared it. There is now a reflective test that
perturbs every component and requires the comparator to notice, and the record's compatibility
constructor is gone so a new component breaks every call site at compile time. Prefer reflection
over hand lists for anything that must stay exhaustive.

**Hardcoded scan lists rot the same way.** The `:core` design-rule guard scanned a hardcoded 23
classes while `core/` had grown to 37 — four whole packages were invisible to the project's most
important rule. It now discovers classes at runtime, and the floor assertion is a number
(currently 40) that discovery must clear, not the length of the hardcoded list, because
`discovered.size() >= physics.length` is implied by the assertions above it and can never fire.

**A cached shape makes a function of history out of a function of inputs.** The nodal solver warm
started from the previous second's flux. Once per-rod flux weights reached reactivity, a running
core and a restored one settled on answers a few ulps apart, and the persistence round trip is
held to bit-for-bit equality. Driving the iteration to a tighter tolerance does not fix it — at
1e-15 it never converges, and after 4000 iterations from two histories 590 of 961 weights still
disagree in their last bits. If a result must round-trip exactly, it cannot depend on what ran
before it.

**An unknown item id deletes the whole recipe, not the ingredient.** `mekanism:pellet_fissile_fuel`
does not exist in Mekanism 10.7, so `RecipeManager` logged one line at datapack load and dropped
`bwr:fuel_assembly` entirely — no crafting route to fuel, no crash, no test failure.
`tools-audit-assets.py` now opens the pinned Mekanism jar out of the Gradle cache and cross-checks
every foreign id our recipes name. Do the same for any new foreign dependency.

API details that are easy to get wrong on this stack:

- `CompoundTag` has int, long and byte arrays but **no double array**. Use `ListTag` of `DoubleTag`
  (`ReactorStateNbt.putDoubles` / `getDoubles`).
- `registerSimpleBlockItem` returns `DeferredItem<BlockItem>`, not `DeferredItem<Item>`.
- ModDevGradle run *types* are exactly `[client, data, gameTestServer, server, junit]`. There is no
  `clientData()`. A named run config can still be anything you like — `peripheralCheck` is a
  `server()` with a system property and its own game directory.
- Minecraft 1.21 renamed the data folders to **singular**: `data/<ns>/recipe/`, `loot_table/`,
  `advancement/`. Getting it wrong silently loads nothing.
- 1.21 recipe **results** use `"id"`; ingredients still use `"item"` (or `"tag"`).
- A block registered `requiresCorrectToolForDrops()` with **no** `mineable/pickaxe` tag can never
  be harvested by anything. Both are required.
- Mekanism 10.7 (1.21) **unified** gas/infusion/pigment/slurry into one `Chemical` type. There is
  no `GasStack` any more.
- `PipeBlock` declares an abstract `codec()` you must override.
- Lua has one number type and produces NaN and infinity trivially. The physics sanitises silently,
  so an unguarded `0/0` in a control program used to turn into a quiet zero demand with nothing to
  debug against. `ReactorPeripheral.finite()` rejects a non-finite *argument* by name — a judgement
  about the argument, never about the plant.

---

## 7. Domain facts worth not re-deriving

- **Rod position is 25 discrete notches labelled 00–48 in steps of two**, as the Full Core Display
  reads them. Not a 0–100% float. (`ROD_NOTCH_POSITIONS = 25`, `ROD_NOTCH_MAX_LABEL = 48`,
  `ROD_NOTCH_STEP = 2`.) Rods are 1-based on the Lua surface and 0-based in the arrays.
- **Point kinetics needs a source term.** Without `+ S`, `n = 0` is an equilibrium, a shutdown core
  decays to exactly zero and source range monitors read nothing. With it, the subcritical
  equilibrium `n = Λ·S/|ρ|` gives both the count-rate floor and the hyperbolic approach to
  criticality that makes 1/M plots work. The *mod* must also install a source in the built plant,
  which for a long time it did not — section 5.
- **The integrator is implicit, not explicit.** The Jacobian is an arrowhead matrix, so backward
  Euler collapses to one scalar equation — no linear algebra library. Guard the denominator: it
  can cross zero when `ρ > β`, exactly during a super-prompt excursion.
- **Detectors are paralyzable pulse counters**: `indicated = true · exp(−true · deadTime)`. That
  rises, peaks, then **rolls to zero** — which is why a saturated SRM reads `00.00` rather than
  pegging high, and why that is the cue to switch to IRMs. No special-case branch.
- **Void coefficient steepens with void.** A 1% void step removes ~1.1% of the water at 10% void
  but ~3.45% at 70%. A flat coefficient understates feedback where BWR behaviour actually lives.
- **The Pu/MOX danger is emergent from β**, never a hardcoded penalty. Keepin totals: U-235
  0.006502, Pu-239 0.002099, U-233 0.00266.
- `T_sat(°F) = 115.1 · P(psia)^0.225` — under 0.4 °F error across 800–1400 psia, +0.02 °F at 1000.
- **The other steam properties are no longer that power law below 800 psia.** Liquid density and
  liquid enthalpy blend to a low-pressure form over a 400–800 psia smoothstep (so a blowdown
  crossing it puts no step in the level), and latent heat uses a Watson relation below 800 and
  again above 1400. The power law was out by up to 64% on density and +2740% on vapour density at
  the 0.1 psia floor, and the vessel does not stay in the 800–1400 band — ADS, the SRVs and every
  blowdown take it out. Everything at and above 800 psia is unchanged, so calibrations quoted
  against the rated band still hold.
- Rated dome pressure is **1025 psig**, not 1150. Design 1250, ASME limit 1375.

`REFERENCE-DATA.md` has all of these with citations to the NRC BWR/6 Systems Manual
(ADAMS **ML20090J537**). That document is deliberately **not** committed — facts are not
copyrightable, but the PDF is not ours to redistribute.

---

## 8. Document map

| File | What it is |
|---|---|
| `HANDOFF.md` | This file. Codebase and environment orientation. |
| `README.md` | The original design handoff. Why the mod exists, principles, scope boundaries. |
| `SPEC.md` | **Authoritative specification.** Read in full before substantial work. |
| `REFERENCE-DATA.md` | Real BWR/6 constants with NRC citations, and the corrections applied to SPEC. |
| `BUILD-STATUS.md` | Verified current state, SPEC §14 progress table, known defects. |
| `spec-v1-draft.md` | Superseded earlier draft. **Do not implement from it.** |
| `tools-audit-assets.py` | Asset integrity check, plus the foreign-item-id cross-check. Exits non-zero on any gap. |
| `tools-check-jar.py` | Confirms no soft-dependency classes are bundled. |

## 9. Before you claim anything is done

Run these, and quote the real output rather than describing it:

```bash
./gradlew clean build                       # 124 tests, 124 passed, 0 failed
./gradlew :mod:runData                      # loads in a real runtime
./gradlew :mod:runData -PbwrNoCC            # still loads without CC:Tweaked
./gradlew :mod:runData -PbwrNoMekanism      # still loads without Mekanism
./gradlew :mod:runPeripheralCheck           # plant built and probed on a server; must exit 0
./gradlew :mod:runPeripheralCheck -PbwrNoCC # proves the CC-less guard, must also exit 0
python tools-audit-assets.py                # PROBLEMS: 0
python tools-check-jar.py                   # OK: nothing forbidden is bundled
```

**None of the five `run*` lines has been executed since the last round of fixes** — the newest run
directory under `mod/run/` is older than the newest source file. The three offline commands have.
`runPeripheralCheck` is the only one in this list that puts the mod in a world and ticks it.

If you add a guard, **negative-test it** — inject a violation, confirm the build fails, remove it,
confirm it passes. A guard that cannot fail is decoration, and this project has already shipped
two of those: a design-rule scan that could not see four packages, and a runtime harness that
exited 0 on failure.
