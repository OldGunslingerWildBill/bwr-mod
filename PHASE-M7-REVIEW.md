# Phase M7 — integration and coupled load-follow acceptance

Status: M7 implemented and verified; ready for review with the coverage limits below.
No push. Branch: `containment-structure`.

## Implemented sequence

| Item | Commit | Scope |
| --- | --- | --- |
| Review | `1c10d88` | R1–R5 and approved acceptance-contract updates |
| M1 | `ea5f93f` | Shaft inertia, losses, generation and overspeed damage |
| M2 | `7bdf98f` | Demand, energy ledger, synchronization arithmetic |
| M3 | `b319f60` | Actual delivered FE, generator identity and retained condition |
| M4 | `5b2829d` | Grid tie, demand modes and manual synchronization |
| M5 | `0676d5a` | Directional, zero-inventory FE meter |
| M6 | `ae1de65` | Aligned load reports, CC, signals and existing panel linker |
| M7 | This commit | Coupled long acceptance, integration/lifecycle/cable scenarios, creative entries and approved optional-CC fixtures |

The M1–M6 reports record intermediate results. This report supersedes their
pending gates and fixture-status notes with the final results below.

## New acceptance coverage

- `GridLoadScenarioTests` at (97000,245,97000): starts the shaft from rest,
  synchronizes by moving the existing control valve, demonstrates fixed-valve
  load rejection, explicit throttling and recovery, an open export breaker with
  a retained base load, overspeed damage, and physical stop-valve isolation.
  Steam supply and the condenser sink are independent of requested grid demand.
  No RPM or phase writes. The test-side operator is not a production governor.
- `GridLifecycleTests` at (99016,245,99016), tie +32 X and meter +64 X:
  legacy generator inventory, detached capabilities, frozen unloaded clocks,
  same-UUID reconnect, meter warm-up, new-identity item placement, retained totals
  and malformed-save sanitization. These use unload callbacks and detached block
  entities/NBT; this is not a new physical chunk-streaming test. The existing
  `partialChunkLifecycle` engine-callback coverage remains in the full suite.
- `GridCableBranchTests` at (101000,245,101000): real Mekanism basic universal
  cable, metered export branch and fuel-fabricator base branch. Before trip:
  5000 FE to each. After trip and another 10000 FE supply: export remains 5000,
  base reaches 15000. Both balances are exact and the open meter records no flow.
- `LoadFollowingLongTest`, registered separately in `:core:longAcceptance`:
  one continuously evolving rated core; 10-minute baseline, 1-hour ramp down,
  24-hour reduced hold, 2-hour ramp up, 24-hour recovery hold. Default 0.05 s
  step and existing kinetics substeps are unchanged. Rods are held for the first
  five minutes of each ramp. Thereafter the existing test operator adjusts rods,
  pressure and level. Its APRM target subtracts measured decay heat from the
  requested thermal target. No power/poison reassignment after initialization.

The long runner participates in registration verification; ordinary acceptance
still contains 300 tests and excludes this deliberately slow scenario.
Grid Tie and FE Meter were also added to the main creative tab.

## Steam/load timeline

Stage means are the final 10 seconds of each 120-second controlled stage.
Declared tolerances: delivery ±2.5 MW and final shaft speed ±15 rpm.

| Stage | Delivery MW | Steam kg/s | Final rpm | Buffer FE | Loss MW |
| --- | ---: | ---: | ---: | ---: | ---: |
| 50 MW operating point | 50.000000019 | 95.52 | 1500.2830 | 1,996,268,657 | 3.7514 |
| 35 MW, after explicit throttle | 35.000000020 | 69.16 | 1499.3903 | 1,997,388,060 | 3.7469 |
| Return to 50 MW | 50.000000019 | 95.08 | 1501.2520 | 1,996,268,657 | 3.7563 |

At the fixed-valve reduction, 10 seconds at 35 MW raised speed from 1500.2830
to 1530.5395 rpm while steam remained 96 kg/s. Opening export while retaining
an explicit 5 MW base pull delivered another 174,999,846 FE to that base load.
The rejection/stop-travel peak was 1704.0786 rpm; after three seconds of manual
stop-valve travel and coast-down, fresh steam and steam-generated power were
zero, speed was 1701.9073 rpm and accumulated damage was 0.01919893. The valve
has a physical closing stroke; the peak includes admitted torque during travel.

The separate M1 `ShaftDynamicsTests.rejectionAndManualSteamIsolation` removes
**all** terminal extraction: its loaded startup speed is 793.6694 rpm, then it
reaches 1702.8286 rpm 12.05 seconds after rejection. Manual isolation leaves
1701.1266 rpm after coast-down, with damage 0.00750164. This is a separate
fixture/output level, not a directly comparable rated-speed rejection time.
It retains the per-tick generated − delivered = buffer-change assertion.

## Coupled core timeline

Completed: **1/1 long acceptance passed**, 51h10m simulated at the unchanged
0.05 s step. Test runtime **4285.949 s** (71m26s); Gradle command runtime
**1h11m35s**. The ordinary suite remains separate.

Final 10-minute thermal means: **1.000477 / 0.709614 / 0.980371** of rated
against targets 1.00 / 0.70 / 1.00 (predeclared tolerance ±0.05).
Xenon peaked at **9.7709196e14 atoms/cm³ at 5.221 h**, reached its recovery
minimum of **6.8014449e14 at 30.208 h**, and ended at **8.0500453e14**.
No poison state was reset; both required turning trends passed. Finite-state,
rod-range, no-scram and no-fuel-failure assertions passed throughout.

Flow initiation with rods held:

```text
FLOW DOWN held rods: power 1.000490 -> 0.972913 at 900.0 s
FLOW UP held rods: power 0.709864 -> 0.727586 at 90900.0 s
```

Raw log: `../phase-m7-long-thermal-target.log`. The hourly timeline below
preserves the logged values; rods are mean notch, pressure is psig, steam kg/s,
and I/Xe are atoms/cm³. The last row is the final sample.

| Hours | Thermal fraction | Flow fraction | Rod mean | Iodine | Xenon | Pressure | Steam |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 0.0000 | 1.001707 | 1.000000 | 20.4520 | 3.2719402e+15 | 8.2540041e+14 | 1024.848 | 1940.945 |
| 1.0000 | 0.730183 | 0.666667 | 20.7062 | 3.2334986e+15 | 8.5365979e+14 | 1025.001 | 1417.266 |
| 2.0000 | 0.680206 | 0.600000 | 21.4181 | 3.1326449e+15 | 9.1494126e+14 | 1025.000 | 1320.244 |
| 3.0000 | 0.680485 | 0.600000 | 21.7006 | 3.0407232e+15 | 9.5160992e+14 | 1025.000 | 1320.788 |
| 4.0000 | 0.680020 | 0.600000 | 21.8870 | 2.9581325e+15 | 9.7035968e+14 | 1025.000 | 1319.881 |
| 5.0000 | 0.680027 | 0.600000 | 21.9492 | 2.8839112e+15 | 9.7687801e+14 | 1025.000 | 1319.894 |
| 6.0000 | 0.682267 | 0.600000 | 21.9548 | 2.8173840e+15 | 9.7505897e+14 | 1025.000 | 1324.243 |
| 7.0000 | 0.690173 | 0.600000 | 21.9548 | 2.7591844e+15 | 9.6664070e+14 | 1025.000 | 1339.589 |
| 8.0000 | 0.703515 | 0.600000 | 21.9548 | 2.7104046e+15 | 9.5260750e+14 | 1025.000 | 1365.485 |
| 9.0000 | 0.709638 | 0.600000 | 21.8418 | 2.6705508e+15 | 9.3514817e+14 | 1025.000 | 1377.369 |
| 10.0000 | 0.709508 | 0.600000 | 21.6836 | 2.6350733e+15 | 9.1892985e+14 | 1025.000 | 1377.114 |
| 11.0000 | 0.709900 | 0.600000 | 21.5763 | 2.6031419e+15 | 9.0424674e+14 | 1025.000 | 1377.879 |
| 12.0000 | 0.709431 | 0.600000 | 21.4859 | 2.5744094e+15 | 8.9096385e+14 | 1024.999 | 1376.955 |
| 13.0000 | 0.709939 | 0.600000 | 21.3390 | 2.5485976e+15 | 8.7892658e+14 | 1025.000 | 1377.955 |
| 14.0000 | 0.709517 | 0.600000 | 21.1864 | 2.5254048e+15 | 8.6804899e+14 | 1024.999 | 1377.123 |
| 15.0000 | 0.709641 | 0.600000 | 21.0904 | 2.5045202e+15 | 8.5825979e+14 | 1025.000 | 1377.380 |
| 16.0000 | 0.709601 | 0.600000 | 21.0169 | 2.4857190e+15 | 8.4944600e+14 | 1025.000 | 1377.304 |
| 17.0000 | 0.709761 | 0.600000 | 20.9605 | 2.4687864e+15 | 8.4151483e+14 | 1025.000 | 1377.617 |
| 18.0000 | 0.709470 | 0.600000 | 20.9096 | 2.4535658e+15 | 8.3435615e+14 | 1024.997 | 1376.981 |
| 19.0000 | 0.709486 | 0.600000 | 20.8644 | 2.4398710e+15 | 8.2790980e+14 | 1025.000 | 1377.082 |
| 20.0000 | 0.709909 | 0.600000 | 20.8249 | 2.4275589e+15 | 8.2209940e+14 | 1025.000 | 1378.069 |
| 21.0000 | 0.709703 | 0.600000 | 20.7910 | 2.4166511e+15 | 8.1695702e+14 | 1025.000 | 1377.495 |
| 22.0000 | 0.709863 | 0.600000 | 20.7627 | 2.4066596e+15 | 8.1224678e+14 | 1025.000 | 1377.806 |
| 23.0000 | 0.709288 | 0.600000 | 20.7345 | 2.3976606e+15 | 8.0801257e+14 | 1025.000 | 1376.690 |
| 24.0000 | 0.709137 | 0.600000 | 20.7119 | 2.3895770e+15 | 8.0419060e+14 | 1025.000 | 1376.396 |
| 25.0000 | 0.709350 | 0.600000 | 20.6949 | 2.3822871e+15 | 8.0076411e+14 | 1025.000 | 1376.810 |
| 26.0000 | 0.834983 | 0.766667 | 20.4350 | 2.3931611e+15 | 7.8532840e+14 | 1025.000 | 1620.647 |
| 27.0000 | 0.984574 | 0.966667 | 19.8701 | 2.4520595e+15 | 7.4478639e+14 | 1025.000 | 1910.996 |
| 28.0000 | 1.009708 | 1.000000 | 19.6723 | 2.5372244e+15 | 7.0515420e+14 | 1025.000 | 1959.788 |
| 29.0000 | 1.008081 | 1.000000 | 19.5989 | 2.6142833e+15 | 6.8623179e+14 | 1025.000 | 1956.625 |
| 30.0000 | 1.009069 | 1.000000 | 19.5819 | 2.6834497e+15 | 6.8029803e+14 | 1025.000 | 1958.548 |
| 31.0000 | 1.006426 | 1.000000 | 19.5819 | 2.7455259e+15 | 6.8213875e+14 | 1025.000 | 1953.418 |
| 32.0000 | 0.996029 | 1.000000 | 19.5819 | 2.7991036e+15 | 6.8959332e+14 | 1025.000 | 1933.237 |
| 33.0000 | 0.981912 | 1.000000 | 19.5876 | 2.8428328e+15 | 7.0119708e+14 | 1025.001 | 1905.864 |
| 34.0000 | 0.980989 | 1.000000 | 19.6271 | 2.8797712e+15 | 7.1348611e+14 | 1025.000 | 1904.043 |
| 35.0000 | 0.980343 | 1.000000 | 19.6667 | 2.9129811e+15 | 7.2451828e+14 | 1025.000 | 1902.786 |
| 36.0000 | 0.980256 | 1.000000 | 19.7062 | 2.9428210e+15 | 7.3443778e+14 | 1025.000 | 1902.615 |
| 37.0000 | 0.980716 | 1.000000 | 19.7458 | 2.9696436e+15 | 7.4334908e+14 | 1024.999 | 1903.506 |
| 38.0000 | 0.981289 | 1.000000 | 19.7853 | 2.9937497e+15 | 7.5135865e+14 | 1024.999 | 1904.618 |
| 39.0000 | 0.980887 | 1.000000 | 19.8192 | 3.0154474e+15 | 7.5853957e+14 | 1025.000 | 1903.839 |
| 40.0000 | 0.980475 | 1.000000 | 19.8531 | 3.0349215e+15 | 7.6501587e+14 | 1024.999 | 1902.850 |
| 41.0000 | 0.981016 | 1.000000 | 19.8870 | 3.0521555e+15 | 7.7074000e+14 | 1025.000 | 1904.098 |
| 42.0000 | 0.980768 | 1.000000 | 19.9153 | 3.0679404e+15 | 7.7596412e+14 | 1025.000 | 1903.618 |
| 43.0000 | 0.980004 | 1.000000 | 19.9379 | 3.0821313e+15 | 7.8066589e+14 | 1025.000 | 1902.135 |
| 44.0000 | 0.981044 | 1.000000 | 19.9661 | 3.0948936e+15 | 7.8489033e+14 | 1025.000 | 1904.152 |
| 45.0000 | 0.981084 | 1.000000 | 19.9887 | 3.1063689e+15 | 7.8868892e+14 | 1025.000 | 1904.230 |
| 46.0000 | 0.980138 | 1.000000 | 20.0056 | 3.1166876e+15 | 7.9210412e+14 | 1025.000 | 1902.394 |
| 47.0000 | 0.980610 | 1.000000 | 20.0282 | 3.1259553e+15 | 7.9518068e+14 | 1025.000 | 1903.311 |
| 48.0000 | 0.980209 | 1.000000 | 20.0452 | 3.1342799e+15 | 7.9795040e+14 | 1025.000 | 1902.532 |
| 49.0000 | 0.980150 | 1.000000 | 20.0621 | 3.1417634e+15 | 8.0043932e+14 | 1025.000 | 1902.418 |
| 50.0000 | 0.980322 | 1.000000 | 20.0791 | 3.1484986e+15 | 8.0267009e+14 | 1025.000 | 1902.751 |
| 51.0000 | 0.980594 | 1.000000 | 20.0960 | 3.1545401e+15 | 8.0468332e+14 | 1025.000 | 1903.280 |
| 51.1667 | 0.980139 | 1.000000 | 20.0960 | 3.1554784e+15 | 8.0500453e+14 | 1025.000 | 1902.396 |

## Existing-test edits and fixture findings

The owner explicitly authorized:

1. M1's complete shaft-energy accounting and coast-down reload contract; the
   old energy tolerance stayed 1e-6.
2. The duplicate-tick assertion in `SteamValveRuntimeCheck` moved before the
   deliberate expiry-clock advance. Assertion text and tolerance stayed unchanged.
3. The three repairs in `../PHASE-M-CC-ABSENT-PROPOSAL.patch`: two CC-only
   containment tests explicitly skip only without CC; stopped recirculation uses
   the same `DeviceCommands` actuator without CC. All original assertions remain.

The subsequent CC-free run reached the new cable/load scenarios successfully
but crashed loading `LuaException` from `CondenserRuntimeCheck`'s catch clause.
Source inspection also found unconditional peripheral calls in
`CoolingRuntimeCheck`. The owner approved the follow-up patch on 2026-10-14
("Okay your good to go"). Applied `../PHASE-M-CC-ABSENT-FOLLOWUP.patch`:
the condenser still requires the exact LuaException type, checked by name to
avoid eager resolution with CC absent; cooling skips only its peripheral
commands/capability checks without CC, with explicit skip logs. All physics
assertions and the standard CC-present checks remain. The CC-free rerun passed.

New-fixture development corrected a startup controller that settled above the
synchronization window, a stop-valve peak measured before its closing travel,
and the test operator's APRM/thermal-target distinction. No old physics test
was weakened to resolve these new-fixture mistakes.

## Verification

Final CC-present run after all approved fixture repairs
(`../phase-m-final-standard-followup.log`): **282/282 required GameTests passed**,
`:mod:build` passed, no CC-absent skip messages, command runtime 2m3s.
`checkNoProtectionLogic` was up-to-date from the preceding successful scan of
614 files in the CC-free run. The earlier final standard run was also 282/282
(`../phase-m-final-standard.log`, 2m18s).
Full ordinary core (`../phase-m-final-core.log`): **300/300 passed**, 1041.0 s
test time, 17m29s command runtime. Long acceptance: **1/1 passed**; complete measured timeline and runtime above.
Full CC-free run (`../phase-m-final-nocc-followup.log`): **282 required tests
reported successful**, 2m7s command runtime, protection policy green. This
includes explicit skips of the two CC-only containment tests and two cooling
CC sections approved above; optional CC branches elsewhere also naturally
remain unexercised in this mode. The standard run verifies the CC assertions.
The prior run (`../phase-m-final-mod.log`) passed **23 real Lua example
scenarios**, covering five actual scripts and cleanup.
Final asset audit: 2922 JSON files, all 70 registered blocks covered, 0 problems.
Translation audit: 403 static calls, 808 English keys, 0 problems.
Registration negative control (`../phase-m-registration-final.log`): PASS; an
unregistered canary in an isolated copy is rejected before verification and
filtered runs. The first sandboxed attempt could not create its temporary class
subdirectory; the approved unsandboxed run completed successfully.

## Limits and review notes

- Inertia allocation, losses, conversion speed curve and overspeed damage are
  documented game calibrations, not validated turbine protection settings.
- Real cable results cover Mekanism 10.7.19. The branch test proves base supply
  after export opens and exact sharing in this fixture. It does **not** verify a
  foreign cable's configurable priority. The separate load scenario serves its
  base pull first explicitly. The configured-priority case requested by spec
  §7.3.5 remains unverified; arbitrary network priority is not provided.
- No manual client visual review of the placeholder blocks or meter arrow was
  performed. No new GUI, panel rendering or Blender work belongs to this phase.
- No new production chunk ticket, automatic governor, trip or protection logic.
- No push and no work on TODO-5 B/C or Phase O. Unrelated untracked files remain.
