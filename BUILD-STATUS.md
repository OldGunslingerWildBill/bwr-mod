# Build Status

Last updated: 2026-08-15. Every claim below was produced by running the command named next to
it in this session, against the tree as it stands now. Nothing here is inferred from reading
code, and nothing has been carried forward unverified from the previous version of this file.

## Summary

**`gradle clean build` succeeds from a wiped build directory.** Both modules compile with zero
errors and zero javac warnings, the physics acceptance suite passes **117/117** as part of
`check`, and both jars are produced.

**The test count nearly doubled: 59 → 117.** Seven agents worked in parallel and four of them
added tests. Nothing was lost to a merge: all **16** test classes present on disk are listed in
`AcceptanceTests.TEST_CLASSES`, and the registry files came through consistent — 21 blocks,
22 items, 11 block entity types, 5 menus, with the 11 classes implementing `newBlockEntity`
exactly the 11 with a registered type.

**The mod loads in a real Minecraft 1.21.1 / NeoForge 21.1.248 runtime, verified four ways** —
with both soft dependencies, with each one removed, and with neither. All four boot clean.
CC:Tweaked is now on the dev runtime (1.120.2), so `computerCraftPresent` is finally `true` in
a run rather than permanently `false`.

**Assets are complete for all 21 blocks**, including every new ECCS and GUI block, verified by
`tools-audit-assets.py` (0 problems).

Three things were repaired in this pass, described under *Changed in this verification pass*:
a **save/reload defect that zeroed the hydrogen inventory and cancelled an in-progress quench
spike**, a **hole in the `:core` design-rule guard** that left 14 of 37 physics classes
unscanned, and a **missing hydrogen readout** on the Lua peripheral.

The honest headline gap is unchanged: **the mod has still never run in an actual world.**
`runData` proves registration and mod loading. It does not tick anything, does not place a
block, and does not load a single model or texture.

## Building

There is no JDK or Gradle on PATH. PrismLauncher ships a full JDK 21.0.7 including `javac`:

```bash
export JAVA_HOME="/c/Users/14238/AppData/Roaming/PrismLauncher/java/java-runtime-delta"
export PATH="$JAVA_HOME/bin:$PATH"
gradle clean build
gradle :mod:runData                                # boots with both soft dependencies
gradle :mod:runData -PbwrNoMekanism                # proves the Mekanism soft dependency
gradle :mod:runData -PbwrNoCC                      # proves the CC:Tweaked soft dependency
gradle :mod:runData -PbwrNoCC -PbwrNoMekanism      # neither present
python tools-audit-assets.py                       # asset integrity, non-zero on any gap
python tools-check-jar.py                          # nothing forbidden bundled, non-zero on any
```

Do not pipe gradle through `tail`/`head` and then trust `$?` — that is the pipe's exit code.
Redirect to a file, check the code, then grep the file.

Pinned: NeoForge 21.1.248, ModDevGradle 2.0.143, CC:Tweaked API 1.111.0 compile / 1.120.2
runtime, Mekanism 1.21.1-10.7.19.85 via modmaven.dev (which needs
`metadataSources { artifact() }` — it publishes jars with no POM).

The physics module also builds with nothing but a JDK — no Gradle, no network, no JUnit:

```bash
javac -d out $(find core/src/main/java -name '*.java')
javac -cp out -d testout $(find core/src/test/java -name '*.java')
java -cp "out;testout" dev.bwr.core.AcceptanceTests
```

## Verified

| Check | Command | Result |
|---|---|---|
| Clean build from scratch | `gradle clean build` | **BUILD SUCCESSFUL**, 0 errors, 0 warnings |
| Acceptance suite (wired into `check`) | same | **117 tests, 117 passed, 0 failed**, 104 s |
| Mod loads, both soft deps present | `gradle :mod:runData` | **exit 0** — mod list shows CC: Tweaked 1.120.2 and Mekanism 10.7.19; zero exceptions |
| Mod loads, Mekanism absent | `... -PbwrNoMekanism` | **exit 0** — zero occurrences of "mekanism" in the log |
| Mod loads, CC:Tweaked absent | `... -PbwrNoCC` | **exit 0** — zero occurrences of "computercraft" in the log |
| Mod loads, neither present | `... -PbwrNoCC -PbwrNoMekanism` | **exit 0** — zero occurrences of either, zero `NoClassDefFoundError` |
| Asset integrity | `python tools-audit-assets.py` | **0 problems**; 125 JSON files parsed, 75 texture references all resolve |
| Per-block asset coverage | same | **21/21** blockstate, block model, item model, loot table, recipe |
| Blockstate variant coverage | same | every BlockState combination the Java defines has a model, including all **64** pipe states on `pressurised_tube` |
| `core/` Minecraft imports | `grep -rn "^import net\.minecraft\|^import net\.neoforged" core/src` | **0 matches** |
| `core/` foreign references of any kind | `grep -rnE "net\.minecraft\|net\.neoforged\|mekanism\|dan200\|cc\.tweaked" core/src` | **0 matches**; the only imports in the whole module are `java.*` and `dev.bwr.*` |
| Design rule enforced in `:core` | `ReactorCoreTickTest.test07` | scans **976 methods across 37 physics classes** — every class on the classpath, not a hardcoded list |
| Design rule enforced in `:mod` | `gradle :mod:checkNoProtectionLogic` | **exit 0**, scans **90 files**, no protection logic present |
| Design rule, negative-tested (`:mod`) | inject `shouldScram()`, build, remove, build | **exit 1 then exit 0** — the guard names the offending file and method |
| Design rule, negative-tested (`:core`) | inject `isHighPressure()` into `NodalFluxSolver`, run, remove, run | **exit 1 then exit 0** — and `NodalFluxSolver` is a class the guard could not see before this session |
| Persistence comparator coverage, negative-tested | delete one comparison line, run | **fails, naming the component** |
| CC:Tweaked / Mekanism classes bundled | `python tools-check-jar.py` | **0 and 0**. 157 class files scanned including nested jarJar |
| Jar contents | `python zipfile` | mod jar **294 files** (336 entries incl. dirs); top-level roots exactly `META-INF`, `assets`, `data`, `dev` |
| Artifacts | | `core-0.1.0-SNAPSHOT.jar` (149 KB), `mod-0.1.0-SNAPSHOT.jar` (463 KB) |

### Soft dependencies — how they are actually guarded

Both are `compileOnly` (each additionally `runtimeOnly` for the dev runtime only) and neither is
bundled. Verified at the **bytecode** level, not by reading imports: scanning every `.class` in
the jar for constant-pool references gives exactly

- `mekanism.api.*` — 4 classes: `fuel/MekanismFuelFeed`, `mekanism/BwrMekanismSupport`,
  `mekanism/MekanismSteam`, `mekanism/TurbineSteamOutletChemicalHandler`
- `dan200.computercraft.*` — 7 classes, all in `peripheral/`

Every one is reached only through a `ModList.get().isLoaded(...)` branch in `BwrMod`'s
constructor, and because the check and the reference live in different classes the JVM never
resolves a foreign type on an install that lacks the mod. The four `runData` permutations are
the empirical proof for both.

## Verified physics

Measured values, copied from this session's `:core:acceptance` output.

| Behaviour | Measured |
|---|---|
| Approach to critical | 179 steps, SRM 72.9 → 3806.5 cps; 1/M falls 1.000 → 0.019 as rho goes −0.060 → −0.0011 |
| Startup to rated | 1.0012e-10% → 102.49% of rated over 6039 s, ending 1025.0 psig, APRM 98.8% |
| Flow-only load change | flow 1.00 → 0.70 drives power 100.02% → 72.54%, rods stationary |
| SCRAM from rated | 4 s after: fission 2.14% of pre-scram, total 7.31% = 2.14% fission + 5.17% decay. 600 s after: total 2.17% (77 MW), **not zero** |
| Decay heat ignores the scram | 6.625% of rated at scram (237 MW) → 4.84% at 10 s → 1.36% at one hour (48.5 MW) → 0.52% at one day |
| Delayed tail | time constant 80.6 s over 450–750 s, exactly 1/λ₁ of the longest precursor group |
| Pressurisation | 1025 → 3301 psig in 20 s, void 0.3714 → 0.2879, fission 0.9381 → 4.1609 |
| Saturation curve | 544.597 °F at 1000 psia vs table 544.58 (+0.017); 1025 psig → 287.438 °C; worst error 0.357 °F over 800–1400 psia |
| Delayed neutrons | rho 0.0040 is 0.62$ on U-235 but 1.91$ on Pu-239 — Pu goes prompt where U rides out |
| SRM dead time | paralyzable peak 9.9967e10 cps; rolls over rather than pegging |
| Instrument overlap | SRM 7.317e-14 → 6.628e-03, IRM 1.265e-07 → 1.000e-01, APRM 3.000e-02 → 1.250 |
| Rod position | 25 discrete notches, per REFERENCE-DATA §6 |
| Flux shape | uniform core k=1.10995, non-leakage 0.9910, radial peaking 2.091, edge bundle 12.0% of centre |
| Rod shadowing | one rod in: its bundles −61.6%, the bundle at the same radius on the far side +16.7%; half inserted, bottom/top ratio 1.000 → 0.372 |
| Stuck rod | 176 rods in, one out: that bundle carries 10.49× core average, radial peaking 1.604 → 10.495 |
| Loading pattern | same inventory, fresh centre k=1.10655 peaking 3.079 against fresh edge k=1.07360 peaking 1.852 |
| Effective beta | 69 MOX bundles in the centre give β 0.006014; 220 of the same at the edge give 0.006207 — three times the plutonium, less than half the effect |
| Axial shape | flooded core peaks mid-height at 1.455; boiling peaks at node 6 of 25 at 1.765 |
| Shift is an accelerator only | shifted 77 iterations vs unshifted 858 for the same k to 9 digits, 10.4× faster, weights agree to 6.09e-06 |
| Nodal solve cost | 18700 cells; cold solve 24.4 ms, 24 warm solves after a rod move mean 9.84 ms, worst 13.03 ms |
| Quasi-static separation | at a 20-tick interval the shape moves only on ticks 20/40/60/80 of 80; shortest frozen run is exactly 20 |
| Scram capability is hardware | accumulator charge after a scram from notch 24/12/0 = 0.00/0.50/1.00; with CRD water lost, a spent accumulator is still 0.000 after ten minutes |
| Persistence | round trip exact across all 26 record components; mid-transient restore worst 0.3416%, converging to 0.0003% after 120 s |
| Suppression pool | 2 h pool suction, no RHR: 32 → 89.99 °C, capacity 938237 → 129502 MJ; with RHR 83.10 °C |
| Overpressure onset | stress exactly 0.0 at and below the 1250 psig design limit, positive immediately above; a 0.05 s excursion to 1300 psig leaves 6.667e-06 that never un-fatigues |
| Overpressure rate law | x² in overpressure: 1275/1300/1325/1350/1375 psig for 60 s give 0.00200/0.00800/0.01800/0.03200/0.05000; the rate at the code limit is 4.0× the rate halfway to it |
| First break | 20 s isolation reaches 3301 psig and wounds without breaking; unrelieved, the recirculation line fails at 42.9 s and 4235 psig after 15.85 s above the code limit |
| Break consequences differ | steam line: 1025 → 401 psig, break flow 777 kg/s. Recirculation: flow 1.00 → 0.002, fuel fully uncovered. Feedwater: pressure *rises* to 1140 psig first |
| Level indication lies | during a steam line break the indication bottoms at 15.5 in at 37 s and then reads 19.5 in while the real collapsed level keeps falling to −56.2 in |
| Uncovery collapses the heat sink | covered U_cc 299.2 MW/°C → uncovered 0.00500 MW/°C, 59845× worse |
| Decay heat alone melts a scrammed core | rods fully inserted from tick one, fission zero throughout: clad 1200 °C at 1329 s, fuel melt at 4259 s, 675 kg of hydrogen |
| Zr-water onset is emergent | rate roughly triples per 100 °C (3.71× at the top of 1000–1500 °C, 2.16× at the bottom); at the declared 1200 °C onset it makes 26.1 MW against 48.3 MW of decay heat |
| The reaction self-accelerates | at pinned decay heat it overtakes it at 1549 °C; fastest clad rise 14.92 °C/s is 31.4× the slowest, with the heat source unchanged; 19080 accelerating ticks, 0 decelerating |
| Quench spike branches at 1527 °C | reflood at 1450 °C costs +1.1% hydrogen and strips no oxide; at 1600 °C it costs +6.1% and strips the film 141.9 → 35.1 µm. A 5× step at the hinge |
| Late reflood is the TMI-2 mechanism | reflood at 1900 °C: +110.47 kg H₂ (46% more), oxide 230.1 → 19.9 µm (91% stripped), peak H₂ rate 37× |
| Reflood always beats inaction | 4 h totals: no reflood 2651.8 kg H₂ and 100% oxidised; reflood at 1400 °C 73.9 kg (3%). Short-window crossover at 2200 °C is real and does not change the four-hour answer |
| Hydrogen is stoichiometric | 60000 kg of Zr fully consumed gives 2651.8 kg against a ceiling of 2651.8; worst relative disagreement 7.55e-15 |
| Damage is monotonic under abuse | 400000 adversarial ticks including NaN and negative steps: every monotonic quantity held |
| Fuel types | LEU β 0.006502, MOX 0.003500 (0.54×), Pu-239 0.002099 (0.32×), thorium 0.002660 (0.41×) |
| Mixed core | 60/30/10 LEU/MOX/Pu by flux gives β_eff 0.005161 where the unweighted mean would be 0.004034 |
| Fuel position matters | 24 MOX in a 15×15 LEU core cost 6.685e-04 of β in the centre against 1.090e-04 at the edge — 6.13× for the same fuel |
| End of cycle | fresh LEU excess 0.0795 Δk/k, spent at 29088 MWd/t; thorium peaks at 10000 MWd/t before ending at 33500 |

## The design rule

> The mod provides hardware and physics. The player provides control logic.

Enforced by three tests that fail the build, all three negative-tested this session:

1. `ReactorCoreTickTest.test07_noProtectionLogicAnywhereInThePhysics` — 976 methods, 37 classes.
2. `SuppressionPoolTest.test07_poolContainsNoProtectionLogic` — 22 methods.
3. `BoundaryStressTest.test11_noProtectionLogicInTheDamageModel` — 59 methods, 4 classes, and
   it additionally demonstrates the rule rather than only asserting it: 30 s of unrelieved
   isolation reaches 4029 psig and **opens nothing**.
4. `:mod:checkNoProtectionLogic`, wired into `check` — 90 files.

## What exists

```
core/src/main   37 files   kinetics, thermal, instrument, fuel, poison, pool, nodal,
                           boundary, eccs, harness
core/src/test   19 files   acceptance suite + runner
mod/src/main    90 files   reactor, pool, steam, flow, rods, fuel cycle, ECCS, damage,
                           GUIs, Mekanism boundary, peripherals, registries
mod/resources  180 files   assets and data
```

21 blocks, 22 items, 11 block entity types, 5 menus, 5 datapack fuel types.

## SPEC section 14 build order

| # | Step | Status | Evidence |
|---|---|---|---|
| 1 | `ReactorCore` standalone | **done** | 23 tests across `PointKineticsTest`, `ReactorCoreScramTest`, `ReactorCoreTickTest`; rated core holds 1.0000 → 1.0002 over 300 s |
| 2 | Nodal flux solve feeding β_eff and weights | **done** | `NodalFluxSolverTest` 14 tests; 18700 cells, cold 24.4 ms / warm 9.84 ms mean; `test10` asserts the quasi-static freeze |
| 3 | Multiblock wrapper | **done, never ticked in a world** | `ReactorControllerBlockEntity` with `SYNC_INTERVAL_TICKS = 5` (4 Hz); structure validation and sparger degradation exist; no world has ever loaded it |
| 4 | Fuel registry + assemblies | **done** | 5 datapack `fuel_type` JSONs, codec round-trips, 8 classes of malformed entry rejected with legible messages; `fuel_assembly` item carries burnup and enrichment in a data component |
| 5 | Control rods + CRDs, S-curve worth, accumulators | **done** | `control_rod_drive` block; 25 notches asserted; accumulator charge 0.00/0.50/1.00 by insertion depth, and a spent one stays 0.000 for ten minutes with CRD water lost |
| 6 | Pumps + jet pumps, energy limiting, inertia, GUI slider, CC toggle | **done** | `recirculation_pump` and `jet_pump` blocks, asymmetric coastdown (τ 3 s up / 11 s down), `isPowerLimited()`, `RecirculationPumpScreen` slider, `ReactorPeripheral.setRecirculationFlow` |
| 7 | Pressure + saturation + SRVs | **done** | `SaturationTest` worst error 0.357 °F over 800–1400 psia; `safety_relief_valve` and `msiv` blocks actuated by redstone or Lua |
| 8 | `IPeripheral` exposure | **partial** | 7 peripherals compile and CC:Tweaked is now on the dev runtime, but `runData` does not tick, so no `@LuaFunction` has ever been called. The surface is unexercised |
| 9 | Overpressure stress/failure model | **done** | `BoundaryStress` wired into `ReactorCore`, persisted by `BoundaryDamageNbt`, exposed by `BoundaryDamageReadout`; `BoundaryStressTest` 11 tests. This was "not built" last session |
| 10 | Refuelling GUI + head state + refuel rendering | **partial** | `RefuellingMenu`/`RefuellingScreen` with working `loadAssembly`/`unloadAssembly`, and `VesselState` refuses head removal under pressure. **No refuel rendering**: there is no `BlockEntityRenderer` anywhere in the mod, so the head never visibly comes off |
| 11 | Severe accident model | **done** | 22 tests across `SevereAccidentEscalationTest`, `QuenchSpikeRefloodTest`, `DamageBookkeepingTest`. Was "modelled but untested" last session |
| 12 | Suppression pool, ECCS machines, spargers, SLC, ADS | **done** | `rcic_turbine_pump`, `hpci_turbine_pump`, `hpcs_pump`, `lpcs_pump`, `rhr_pump`, `slc_pump`, `ads_controller`, `core_spray_sparger`, `condensate_storage_tank` all registered; `ReactorEccsBus` calls `setInjectionFlowKgPerS` and `setBoronInjectionPpmPerMinute` |

Ten of twelve done, two partial. Both partials are blocked on the same thing: nothing has run
in a client.

## Changed in this verification pass

1. **Fixed a save/reload defect that cancelled accidents.** `ReactorState` carried
   `peakCladTempC` and `oxidationFraction` but neither the hydrogen mass nor the protective
   oxide film, and `ReactorCore.fromState` passed the *live* (zero, on a fresh core) hydrogen
   value into `restoreDamageState`. Reloading a world therefore zeroed the hydrogen counter and
   reset the oxide film to its grown value — which cancels an in-progress quench spike, because
   cracking that film is the whole mechanism. `DamageBookkeepingTest.test03` had measured the
   cost (41% of the spike lost) and filed it as a FINDING without fixing it. `ReactorState` now
   carries `hydrogenKg` and `protectiveOxideArealKgPerM2`, `fromState` restores the film *after*
   the oxidation inversion, `ReactorStateNbt` persists both, and the round-trip test asserts
   both survive exactly.
2. **Closed a hole in the `:core` design-rule guard.** `ReactorCoreTickTest.test07` scanned a
   hardcoded list of 23 classes. `core/` has 37. Everything in `boundary/`, `eccs/`, `nodal/`
   and `harness/` — all written by this batch of agents — was invisible to the rule that is
   supposed to be non-negotiable. The test now discovers every compiled class under
   `dev.bwr.core` from its own code source, keeps the old list as a floor so discovery finding
   nothing is itself a failure, and scans 976 methods instead of 705. Negative-tested by
   injecting `isHighPressure()` into `NodalFluxSolver`, one of the classes it previously could
   not see.
3. **Added a guard against the persistence comparator going stale.**
   `ReactorStateRoundTripTest.assertStatesIdentical` names all 26 record components by hand,
   which is exactly how the hydrogen field went unnoticed. `test07` now perturbs each record
   component reflectively in turn and requires the comparator to catch every one. Deleting a
   single comparison line makes it fail, naming the component.
4. **Exposed hydrogen to Lua.** `ReactorPanelMenu` sent `hydrogenKg` to the GUI but
   `ReactorPeripheral` had no readout for it, so the mod's primary control surface could see
   oxidation fraction but not the kilograms the containment problem is actually measured in.
   Added `getHydrogenKg()` and a `hydrogenKg` key in the bulk table. It is a measurement — no
   threshold, no judgement.

No physics constants, blocks, models or recipes were modified.

## Known issues

1. **Never run in a world.** Still the single largest unknown. `runData` boots the mod and
   constructs every registry, but `Initializing Data Gatherer for mods []` in the log means no
   data provider runs — it validates nothing about assets. Ticking, structure validation, NBT
   round-tripping in a live chunk, block placement, GUI interaction and peripheral attachment
   are correct only by compilation and by the pure-Java tests.
2. **No model or texture has ever been loaded by a client.** The audit proves every reference
   *resolves to a file*; it cannot prove a model renders, that UV mapping is right, or that a
   16×16 PNG is not visually garbage. Only `gradle :mod:runClient` shows that.
3. **No `@LuaFunction` has ever executed.** CC:Tweaked is on the dev runtime now, which is an
   improvement over the previous session, but `runData` does not tick, so the peripheral surface
   — the product — remains unexercised. The five GUI screens have likewise never been opened.
4. **Two-node thermal model cannot represent partial uncovery.** `SevereAccidentEscalationTest.
   test01` measures and documents this: clad temperature after 600 s barely moves between 100%
   and 5% covered, then jumps at 0%. Real cores uncover hot channels first. Every accident in
   this model is a whole-core accident.
5. **The late severe-accident stages are absent, by name.** SPEC §8.1 stages 4 (cladding failure
   and gap activity release), 6 (RPV lower head failure) and 7 (molten core-concrete
   interaction) have no class. Past melt the two-node model keeps integrating and reports clad
   3932 °C, which is bookkeeping, not physics. SPEC §8.2's "very late (post-relocation)" reflood
   branch is likewise not implemented — `QuenchSpikeRefloodTest.test09` asserts the gap rather
   than the behaviour.
6. **No `BlockEntityRenderer` exists.** Consequence: no head-off rendering (SPEC §14 step 10),
   no visible fuel in the core, no moving parts anywhere.
7. **Containment does not exist** (SPEC §16). The pool reports uncondensed steam for it to
   consume and `FuelThermal` can make 2652 kg of hydrogen; nothing receives either. Inerting,
   deflagration and venting are all absent, so the accident chain ends in a number rather than
   a consequence.
8. **`ReactorStructure.measureInterior`** expands along single axes from a seed block, so it
   assumes a rectangular interior. An irregular cavity may measure wrong rather than be rejected.
9. **The suppression pool scans a 49³ block region on revalidation.** Fine on neighbour-change,
   worth watching if that is ever called more often.
10. **One deprecation note in the build**: `gui/client/LatticeGridWidget.java` overrides a
    deprecated API (`onClick(double, double)`). It is a `Note:`, not a warning, and the build is
    still warning-free, but it should be moved to the current signature.
11. **Copyright holder in the LICENSE files is a placeholder**: "the Realistic BWR contributors".
12. **`fuel_assembly` has one texture and one flat item model** — no visual distinction between
    fuel types or burnup states, though the data component tracks both.
13. **All textures are flat-colour placeholders.** By design for now; real art is coming.

## Next steps

1. **`gradle :mod:runClient`** and place every block. Still the highest-value action available:
   it invalidates Known issues 1, 2 and 3 in one sitting, and it is the only way to find out
   whether five GUI screens that have never been drawn actually work. Expect problems no amount
   of JSON cross-referencing can predict.
2. **Attach a computer to a controller and run Lua.** CC is on the runtime; nothing has called
   into it. Write the scram program the design rule says the player must write, and find out
   whether the peripheral surface is actually sufficient to write it.
3. **Model partial uncovery** with an axial or channel-resolved clad temperature. Known issue 4
   is the largest remaining physics fidelity gap, and it makes every accident coarser than the
   rest of the model deserves.
4. **Containment**, so hydrogen and uncondensed steam have somewhere to go. The accident chain
   currently ends by printing a mass.
5. **A `BlockEntityRenderer`**, starting with the vessel head, which is the one SPEC §14 step 10
   explicitly names and the only reason that step is not done.
