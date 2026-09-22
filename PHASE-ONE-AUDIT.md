# Realistic BWR — Phase One Code Audit

**Latest verification:** [PHASE-ONE-REAUDIT.md](PHASE-ONE-REAUDIT.md) records five
additional correctness issues found after the repairs below. The standard build
passes; those additional cases still need fixes.

**Repair follow-up:** all 13 findings have implementations and regression coverage
in [PHASE-ONE-FIXES.md](PHASE-ONE-FIXES.md). This document retains the original
audit snapshot and evidence; see [BUILD-STATUS.md](BUILD-STATUS.md) for final validation.

**Audit date:** 21 September 2026
**Snapshot:** current working tree, including the existing uncommitted condenser, cooling-water, and tank changes. Git base: `b77cf001ce07a61342e4c11022e926aed80bd693`.
**Purpose:** identify and report defects. No production fixes, commits, or Git pushes were made.

## Summary

**13 actionable findings: 5 high priority (P1), 8 medium priority (P2).** Eight findings have an executed reproduction of their principal symptom; five are confirmed by source and lifecycle tracing.

The normal build and existing tests pass. The main weaknesses are inventory ownership, partial chunk loading, and older control paths that do not consistently obey newer physical hardware. I would address the P1 findings before a public release, then the flow-control and persistence findings.

This is a broad repository audit, not a guarantee that every possible defect has been found. The distinction between executed evidence and source evidence is recorded below.

| ID | Priority | Finding | Evidence |
| --- | --- | --- | --- |
| F01 | P1 | Breaking a reactor controller loses its loaded fuel | Executed loot check + removal-path review |
| F02 | P1 | Suppression-basin inventory can be duplicated or reset | Executed |
| F03 | P1 | Two controllers can operate independent cores in one vessel | Executed |
| F04 | P1 | Cached remote ports can retain obsolete inventories after controller unload | Source / engine lifecycle |
| F05 | P1 | Large assemblies bypass spawn protection at remote cells | Source / engine permissions |
| F06 | P2 | Shrinking a vessel hides fuel that remains active in the core | Executed |
| F07 | P2 | Direct computer control bypasses stopped recirculation hardware | Executed |
| F08 | P2 | A suppression controller can claim another basin's steam discharge | Executed |
| F09 | P2 | Steam-driven pumps use inconsistent valve routing and accounting | Executed branch case + source tracing |
| F10 | P2 | Structure scans synchronously load otherwise unloaded chunks | Source / engine chunk access |
| F11 | P2 | Removing a child with its controller unloaded leaves a broken assembly | Source / lifecycle |
| F12 | P2 | Charging an idle fuel fabricator does not mark its energy for saving | Executed dirty-state check |
| F13 | P2 | Fuel datapack reloads leave already-loaded core fuel using old constants | Source |

**Priority definitions:** P1 = address before release because of inventory loss/duplication, conflicting ownership, or server permission bypass. P2 = functional correctness or reliability issue to fix next. These are game/software priorities.

## High-priority findings

### F01 — Breaking a reactor controller loses its loaded fuel

**Locations:** [controller block](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/reactor/ReactorControllerBlock.java:26), [controller loot](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/resources/data/bwr/loot_table/blocks/reactor_controller.json:13), [fuel serialization](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/reactor/ReactorControllerBlockEntity.java:958).

Fuel exists in the controller's core inventory and is written to the block entity's save data. The controller block has no removal handler that returns that inventory, and its loot table creates only an ordinary controller item. It neither drops the bundles nor copies their saved data into the dropped controller.

**Reproduction:** load a bundle, inspect the controller's ordinary block loot, then follow the normal removal path. The audit obtained `[1 bwr:reactor_controller]` while its saved `CoreFuel` list contained one bundle; the item contained no block-entity data. Replacing the controller therefore cannot recover the fuel. This affects exposure-bearing used fuel as well as fresh bundles.

**Fix direction:** define one authoritative inventory-transfer path on controller destruction, including the saved-but-not-yet-restored fuel list. Preserve bundle exposure and prevent duplicate drops when removal follows loot generation.

### F02 — One suppression basin can supply multiple independent inventories

**Locations:** [basin validation and initialization](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/suppression/SuppressionPoolBlockEntity.java:586), [mass initialization](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/suppression/SuppressionPoolBlockEntity.java:614).

Each controller owns a separate simulation inventory. Validation measures the same physical water blocks but neither assigns exclusive ownership of the connected basin nor shares its inventory with other controllers. Pump withdrawal changes simulation mass without changing those source-water blocks.

**Executed reproduction:** a basin containing 64 source-water blocks, representing 64,000 kg, was accepted by two controllers. Each supplied 60,800 kg, leaving its own reserve:

```text
physical=64000 kg; first=60800; second=60800; total=121600
```

After draining one controller to 3,200 kg, replacing only that controller initialized it to 64,000 kg again. No water was added to the basin.

**Impact:** emergency-water availability is no longer finite. Adding controllers duplicates available water; replacing a controller resets it.

**Fix direction:** give a basin one persistent inventory and an unambiguous owner, and define how that inventory survives owner replacement. Merely rejecting a second controller would not fix the replacement reset.

### F03 — Multiple controllers independently simulate the same vessel

**Locations:** [accepted shell blocks](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/reactor/ReactorStructure.java:560), [formation and core creation](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/reactor/ReactorControllerBlockEntity.java:470), [drive ownership](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/rods/ControlRodDriveBlockEntity.java:84).

Validation accepts another reactor controller as vessel shell without requiring exactly one controller. Both controllers create their own core, fuel inventory, and rod network. Both associate themselves with the same drives and ports; those associations are overwritten by whichever controller writes last.

**Executed reproduction:** controllers on opposite walls both reported formed, measured the same interior, and held different `ReactorCore` objects.

**Impact:** one physical reactor has two persistent simulations and conflicting controls. This is not a claim that inserting one fuel item automatically copies it into the other controller.

**Fix direction:** enforce one controller per vessel, reject or clearly diagnose duplicates, and make drive/port ownership exclusive rather than last-writer-wins.

### F04 — Remote capability caches can retain a discarded controller inventory

**Locations:** [power capability providers](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/power/PowerModuleCapabilities.java:12), [raw energy extraction](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/power/PowerModuleBlockEntity.java:110), [raw water extraction](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/power/PowerModuleBlockEntity.java:123), [assembly invalidation](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/eccs/PumpAssemblyBlock.java:178).

A capability is the interface another block uses to transfer fluid or energy. A remote port returns the controller's raw handler. That handler does not check whether its owner was removed, replaced, or unloaded.

**Trigger:** keep a port and a capability-caching consumer loaded while the controller's different chunk is saved and unloaded. The cached handler still references the old controller. Extraction mutates that discarded object; reloading the controller restores its saved inventory, allowing the extracted amount to be obtained again.

Placement and block removal invalidate ports, but there is no equivalent assembly-wide invalidation for this partial unload/reload. The bundled NeoForge source confirms that capability caches retain handlers, including null results, until invalidated; invalidation is scoped to the supplied position or chunk.

The guarded [condenser](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/condenser/CondenserCapabilities.java:11) and [cooling](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/cooling/CoolingCapabilities.java:9) handlers avoid stale extraction, but their cached obsolete/null connection can instead remain unusable after the missing chunk returns.

**Evidence limit:** source-confirmed; a real asymmetric chunk-unload scenario with a caching consumer was not executed.

**Fix direction:** use handlers that resolve and verify the current owner on every operation, and invalidate remote ports when assembly availability changes. Cover both duplication and reconnection.

### F05 — Remote multiblock cells bypass vanilla spawn protection

**Locations:** [pump placement](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/eccs/PumpAssemblyBlock.java:159), [pump teardown](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/eccs/PumpAssemblyBlock.java:201), [condenser placement](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/condenser/CondenserBlock.java:51), [cooling placement](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/cooling/CoolingBlock.java:54), [tank teardown](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/eccs/CondensateTankAssembly.java:48).

Placement checks space, collisions, chunk availability, and world bounds, but does not check the player's permission for every occupied cell. It directly writes the additional cells. Teardown similarly removes remote cells without per-cell permission checks. Tank resizing has a permission check; its teardown still has the gap.

**Trigger:** on a dedicated server with active spawn protection, a non-operator places an assembly outside the protected area whose footprint crosses into it. Conversely, mining an unprotected child of an operator-built assembly can remove its protected controller and other parts.

The vanilla interaction packet protects the clicked position; it does not authorize all remote positions changed by these custom methods.

**Evidence limit:** confirmed from placement/removal code and bundled server permission code, not an executed multiplayer test. No claim is made about the behavior of particular third-party claim plugins.

**Fix direction:** preflight all affected positions with the acting player's permissions before committing a placement or player-triggered teardown. Keep the operation atomic and its item refund consistent.

## Medium-priority findings

### F06 — Shrinking a vessel leaves hidden, active fuel

**Locations:** [core rebuilding](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/reactor/ReactorControllerBlockEntity.java:499), [visible core positions](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/reactor/ReactorControllerBlockEntity.java:717), [fuel restoration](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/reactor/ReactorControllerBlockEntity.java:824).

A resize preserves every occupied position in the fixed 31 × 31 fuel lattice. The refuelling screen exposes only the positions permitted by the new vessel's assembly count. Restored fuel outside that smaller set remains in the physics loading.

**Executed reproduction:** put fuel in the 49th position of a 7 × 7 interior, then shrink it to 5 × 5 while retaining the controller. The resulting 25-position vessel still contained the outer bundle at lattice index 604, but that position was absent from `corePositions()`. The supposedly hidden bundle still produced an aggregate `kInf` of approximately 1.12.

**Impact:** fuel becomes inaccessible through the normal refuelling screen while continuing to affect the reactor.

**Fix direction:** explicitly handle overflow fuel on resize: prevent a shrink until those positions are emptied, or move the bundles into an accessible recovery inventory. Do not silently discard them.

### F07 — A computer can demand flow from stopped, unpowered recirculation hardware

**Locations:** [CC command](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/peripheral/ReactorPeripheral.java:795), [last-writer flow handling](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/reactor/ReactorControllerBlockEntity.java:266), [installed versus delivered capacity](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/flow/RecirculationNetwork.java:139), [solver flow response](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/core/src/main/java/dev/bwr/core/ReactorCore.java:984).

The CC command writes the core's recirculation demand directly. Hardware limits it to installed maximum capacity, but the measured hardware flow replaces that command only when the measured value changes. A stopped pump remains at zero, so a later manual demand survives.

**Executed reproduction:** a connected DVSS with zero FE and zero actual speed accepted a CC demand of `0.0454545`, its installed no-jet capacity. Three subsequent hardware-gather passes left that demand in place while motor speed stayed zero. The core's flow update follows this demand.

**Impact:** the legacy direct actuator bypasses the electrical and speed requirements of the modeled pumps. This is separate from the intended natural-circulation baseline.

**Fix direction:** make CC control pump demand through the physical pumps, or bound actual core delivery by measured powered hardware output. If legacy virtual-flow control must remain, expose it as an explicit separate mode.

### F08 — A controller can assign another suppression basin's quencher to itself

**Locations:** [quencher search](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/suppression/SuppressionPoolBlockEntity.java:566), [validation order](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/suppression/SuppressionPoolBlockEntity.java:584), [discharge attribution](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/suppression/SuppressionPoolBlockEntity.java:648).

The controller collects submerged quenchers throughout its search box and gathers their relief valves before surveying its own basin. Discharge attribution does not filter those quenchers through the existing basin-ownership check.

**Executed reproduction:** two separate basins were twelve blocks apart. A quencher in the second basin returned `ownsQuencher=false` for the first controller, yet that controller counted the quencher's connected relief valve as its own discharge.

**Impact:** steam and heat can be assigned to the wrong suppression pool. The observed defect is incorrect ownership; detailed heat partition under competing live controllers was not separately measured.

**Fix direction:** determine the connected basin first, then admit only its quenchers and direct-water discharge points to the steam accounting.

### F09 — Steam-driven pumps do not consistently follow the physical valve route

**Locations:** [whole-network opening minimum](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/eccs/AssemblyPlumbing.java:45), [feed-pump admission and direct claims](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/feedwater/FeedwaterPumpBlockEntity.java:263), [RCIC/HPCI steam claims](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/eccs/EccsPumpBlockEntity.java:334). Compare the newer [route-based claim implementation](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/steam/SteamValveRouting.java:16).

Two related failures remain in the older pump steam path:

1. **An unused branch throttles the valid path.** The survey takes the minimum opening of every visited valve, including a valve on a dead-end side branch. The executed test kept the same source nozzle and unchanged open route, added a 10%-open valve on a branch with no consumer, and changed the reported opening from 1.0 to 0.1. Feed-pump steam availability uses that result.
2. **Pump claims bypass the shared valve ledger.** RCIC/HPCI claim directly from the nozzle and omit the admission opening from their supply limit. Feed pumps apply a per-pump limit but likewise bypass valve `allowance()/record()`. On a shared header whose other route is more open, those claims can exceed the restricted branch's intended allowance and are absent from its flow readout.

**Evidence:** the branch case was executed; the shared-ledger case is source-confirmed. A fully shut branch is skipped correctly, so this is not a claim that every closed valve leaks.

**Fix direction:** use the same selected-path throttling and per-source valve accounting for steam-driven pumps, HP turbines, bypass condensers, and the Mekanism boundary. Add mixed-consumer and dead-branch regressions.

### F10 — Validation can synchronously load and generate distant chunks

**Locations:** [reactor interior reads](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/reactor/ReactorStructure.java:373), [pool search](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/suppression/SuppressionPoolBlockEntity.java:566), [pool fluid reads](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/suppression/SuppressionPoolBlockEntity.java:966).

These scans call world block/fluid accessors without first checking loadedness. In the bundled engine, those accessors request a full chunk and can wait for loading or generation.

A suppression controller scans a 49 × 49 × 49 box even before finding a valid basin. Unformed controllers retry every 100 ticks; formed pools retry every 600 ticks. Reactor validation also makes unguarded reads beyond its loaded vicinity.

**Impact:** a controller near the edge of loaded terrain can stall the server thread loading chunks that the player did not keep loaded. Repeated unloading and rescanning can repeat the cost.

**Evidence limit:** source/engine behavior confirmed; no sustained TPS benchmark or generation-duration measurement was made.

**Fix direction:** use non-loading reads or explicit loadedness checks and defer validation of incomplete regions. Do not use structure detection as an implicit chunk loader.

### F11 — Partial unload plus child removal leaves permanently incomplete machinery

**Locations:** [condenser removal](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/condenser/CondenserBlock.java:100), [condenser cleanup](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/condenser/CondenserBlock.java:87), [pump removal](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/eccs/PumpAssemblyBlock.java:205), [tank removal](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/eccs/CondensateStorageTankBlock.java:58). Cooling machines and RCIC/HPCI assemblies have equivalent paths.

**Trigger:** a child remains loaded while its controller chunk is absent, and a server-side operation removes that child. A separately chunk-loaded block breaker is one practical example.

Condenser/cooling/tank owner lookup returns null, so teardown is skipped. Pump/turbine teardown skips unloaded cells. On reload, cleanup verifies that the controller exists, rather than completing the interrupted teardown. A controller can therefore survive with missing children indefinitely.

**Impact:** the structure remains nonfunctional and partially present across saves. It requires manual removal/rebuilding; the tank's resize/repair path can also recover it.

**Evidence limit:** source-confirmed, not an executed asymmetric chunk test.

**Fix direction:** persist pending removal or a structure generation/tombstone, and reconcile it when remaining parts load. Keep suspended-but-intact structures distinct from broken ones.

### F12 — Idle fuel-fabricator energy changes may not be saved

**Locations:** [energy implementation](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/fuel/FuelFabricatorBlockEntity.java:90), [early-return tick paths](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/fuel/FuelFabricatorBlockEntity.java:117), [raw energy capability](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/fuel/FuelCapabilities.java:30).

The exposed energy storage inherits `receiveEnergy()` without a change listener. Running fabrication marks the block dirty, but charging while there is no chemical feed or while output is blocked does not.

**Executed reproduction:** after clearing the chunk's dirty flag to represent a completed save, the external energy capability accepted 10,000 FE and the flag remained false.

**Impact:** if nothing else dirties that chunk before it is skipped by saving/unloading, the newly received energy is lost on reload. The probe verified the missing persistence notification, not a complete disk-save/unload cycle.

**Fix direction:** call `setChanged()` when a non-simulated external energy transfer actually changes the buffer. Retain no-op behavior for simulations.

### F13 — Fuel hot reload leaves live cores on the old fuel definition

**Locations:** [registry replacement](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/fuel/FuelTypeLoader.java:140), [fuel-type reference held by a bundle](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/core/src/main/java/dev/bwr/core/fuel/FuelAssembly.java:83), [item-to-core conversion](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/fuel/FuelAssemblies.java:59), [client registry synchronization](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/fuel/FuelTypeSync.java:76).

A datapack reload replaces the registry and synchronizes the new definitions to clients. Already-loaded core assemblies retain a final reference to the old `FuelType`. Neither the reload handler nor registry replacement updates live core fuel. Newly loaded bundles resolve the new definition; saved core fuel resolves it after reconstruction.

**Trigger:** change a used fuel type's constants and run `/reload` while a reactor already contains it. Its installed bundles continue with the old constants, while newly loaded bundles and item tooltips use the new ones. Restarting/reconstructing the core changes its interpretation again.

**Impact:** the same named fuel can use different nuclear data depending on when it was inserted or restored.

**Evidence limit:** source-confirmed; no live datapack modification was performed during this audit.

**Fix direction:** rebind loaded assemblies on reload while preserving enrichment/exposure and invalidating derived caches, or explicitly implement restart-only retuning with consistent UI behavior.

## Validation performed

| Check | Result |
| --- | --- |
| Full Gradle build and core acceptance suite | **182 tests, 182 passed, 0 failed** |
| CC/Mekanism peripheral runtime harness | Passed, 0 problems |
| Existing server GameTest harness | Passed, including pump/turbine placement, physical ports, operation, persistence, and teardown |
| Pump runtime scenarios | 73 passed |
| Modular power runtime scenarios | 15 passed |
| Steam valve / MSIV scenarios | 12 / 5 passed |
| Condenser / cooling / tank scenarios | 10 / 25 / 8 passed |
| Startup with both CC:Tweaked and Mekanism absent | Passed, 0 problems |
| Asset audit | 2,170 JSON files; 0 reported problems |
| Eight model-export consistency checks | All passed |
| Release JAR audit | 294 classes scanned; required project notices present; no bundled CC/Mekanism classes or dev-test classes |
| Audit-only reproduction probes | Five probe methods plus the existing GameTest passed; the probe assertions deliberately verify that the reported bugs exist |

The full build took approximately 15 minutes, including the acceptance suite. The existing green checks do not test several ownership and partial-chunk situations above.

The exporter checks covered DVSS, modern pumps, power turbines, steam valves, MSIV, condenser, cooling machines, and the condensate tank. They establish consistency of exported assets; they are not a new visual playtest of every model.

Audited JAR SHA-256:

```text
6D02131BA8D8E703C6A4F57122229618A0D9A3BE5376AF9EAB9245DE0C2D0549
```

### Evidence files

- [Full build and acceptance log](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/tmp/phase-one-audit-tests.log)
- [Final reproduction log](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/tmp/phase-one-audit-probes-final.log)
- [Audit-only reproduction source](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/tmp/PhaseOneAuditProbe.java)
- [Optional integrations absent log](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/tmp/phase-one-audit-no-integrations.log)
- [Asset audit](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/tmp/phase-one-audit-assets.log)
- [JAR audit](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/tmp/phase-one-audit-jar.log)
- [Detailed multiblock/engine review notes](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/tmp/audit-multiblocks.md)

The temporary probe was compiled into the development build directory only and removed afterward. It was not added to production sources or the release JAR. The probes used disposable development test worlds, not the player's reactor save. Some setup/validation methods were invoked reflectively to isolate the behavior under test.

## Coverage and remaining limits

The audit covered the core physics module and mod integration: reactor formation/state/fuel/rods, ECCS/feedwater/recirculation, steam and water routes, modular turbine/generator code, suppression pools, condenser/cooling/tank assemblies, GUI commands, CC/Mekanism boundaries, save/load and capabilities, resources, exporter consistency, and packaging. The tree contains approximately 213 production Java files and 47,166 lines including comments, plus 31 core test files and 21 development harness files.

This does **not** establish:

- Scientific validation against an operating plant or manufacturer performance curves.
- Exhaustive branch coverage or absence of additional bugs.
- A long-duration multiplayer performance/stress result.
- Compatibility with every external pipe, claim, or chunk-loading mod.
- A new rendered inspection of all model variants.
- Every optional-dependency combination independently; both-present and both-absent were exercised.
- End-to-end reproductions of the five source-only findings.

### Existing scope limitations, kept separate from defects

- **LP exhaust is not yet coupled to the placeable condenser.** LP modules still perform their temporary internal condensation. The external condenser accepts bypass steam; fitting it underneath an LP module does not establish exhaust transfer or condenser backpressure. This is documented in [the cooling guide](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/COOLING-WATER.md:85).
- **Water temperature is not transported throughout arbitrary pipe networks.** Cooling uses fixed design temperatures and simplified tower losses. A physically closed temperature/enthalpy loop remains future work.
- **Legacy virtual plant actuators remain exposed.** Some direct feedwater/ECCS controls intentionally work without constructed hardware. Decide explicitly whether those compatibility paths remain part of phase two. F07 is the narrower demonstrated conflict with installed recirculation hardware.
- **Automatic protection is intentionally player-controlled.** The absence of automatic SCRAM/ECCS logic was not counted as a defect.

## Suggested repair order

1. Protect fuel and water inventories: F01–F04, including recovery behavior for existing saves.
2. Close the remote-cell permission gap: F05.
3. Make the physical plant authoritative: F06–F09.
4. Fix partial loading and persistence: F10–F13.
5. Turn the audit reproductions into permanent regressions that assert the corrected behavior. Add real remote capability-cache/chunk lifecycle tests and a non-operator spawn-protection test.

No fixes have been applied by this audit.
