# ASTRA TODO 5 — Part A test robustness

Base: `1b51ba3584bc7b6a4f01f5e92bad82d6d6922d86` on `containment-structure`.

## Changes

- Audited all 61 GameTest source classes and their delegated runtime helpers. Item-count fixtures now use actual entity-lookup readiness across their full bounds. The helper runs each assertion once, owns only newly forced test chunks, and cleans up on success, failure, timeout and rerun. Expected counts and material conservation checks are unchanged.
- Scoped deferred tank save/restore and completion checks to the fixture's assembly UUID. A second real unloaded tank proves that restoring/completing one plan preserves the other. A small support floor keeps each real root drop within the unchanged counting bounds during readiness waits. Scoped cache assertions compare fixture graph/bank identity; the intentionally synchronous Alpha26 no-new-graph assertion remains, with its rationale documented.
- Removed competing owners of the Lua harness's direct peripheral bindings. The harness now owns a real registered CC `ServerComputer`, while all scripts still execute in CC's real Lua runtime against actual registered BWR peripherals and hardware readbacks. The new ECCS scenario repeatedly invalidates a separate real block computer's native sides. No script delay/retry or pump behavior change was added.
- FCD measurements and screenshots wait for fixture construction, real formed 185-rod snapshots, rendered sections, the intended first-person camera and 20 consecutive ready frames. The original warm-up/measurement windows and frame/cache assertions remain. A development probe deliberately opens the real Refuelling menu at a side-on camera for 12 seconds to reject the old sampling behavior. Active fixture chunks have bounded ownership and cleanup.
- Updated Lua harness documentation to distinguish direct capability/runtime coverage from physical cable-network coverage. The five player Lua programs are unchanged.

## Validation

All Gradle commands below used Java 21 at `C:/Users/14238/AppData/Roaming/PrismLauncher/java/java-runtime-delta`, from this repository, with `--offline --console=plain`.

| Check | Command | Result |
|---|---|---|
| Core registration preparation | `./gradlew.bat :core:testClasses --offline --console=plain` | PASS |
| Registration, including its unregistered-test negative control | `py -3.13 tools-check-test-registration.py` | PASS: 43 classes, 289 methods |
| Full core acceptance | `./gradlew.bat :core:acceptance --offline --console=plain` | PASS: 289/289; 21m25s Gradle duration |
| Lua repeated default checks | `./gradlew.bat :mod:runLuaExamplesCheck --offline --console=plain` | PASS: 10 consecutive runs, 23/23 each |
| Full GameTest suites | `./gradlew.bat :mod:runTurbineGameTest --offline --console=plain` | PASS: 10 consecutive runs, 272/272 each; every suite immediately followed Lua, last three with 16 busy workers |
| Close-up-first normal captures | `./gradlew.bat :mod:runTurbineModelCheck -PbwrFcdClientCheck -PbwrFcdClientStages=columbia-close,browns-close,matrix-close --offline --console=plain` | PASS: all three captures inspected |
| Close-up-first stale-menu regression | Same command with `-PbwrFcdReadinessProbe` | PASS: real Refuelling screen held for 12s with zero samples, then all three correct captures inspected |
| Build and protection audit | `./gradlew.bat :mod:build :mod:checkNoProtectionLogic --offline --console=plain` | PASS |
| Release JAR audit | `py -3.13 tools-check-jar.py` | PASS |
| Asset audit | `py -3.13 tools-audit-assets.py` | PASS |
| Whitespace | `git diff --check` | PASS |

## Failure controls and investigation

Temporary source mutations are restored byte-for-byte in `finally`; evidence records the original/restored SHA-256. They are not part of the final source.

- Restoring the old level-wide tank completion assertion, suppressing real tritium overflow drops, and forcing an external timeout while the tank probe remains unready caused exactly the three expected GameTest failures in a normally completed 272-test suite. The timeout control verified removal of probes and newly owned forced chunks.
- Restoring whole-map tank replacement produced exactly one expected tank failure and completed all 272 tests normally. The first attempt hit the expected assertion but an inherited broad exception catch wrapped it and crashed the server; that attempt is not accepted as regression proof. The catch now handles reflection failures only, allowing GameTest assertions to report normally.
- Original Lua diagnostics reproduced a missing real task dispatch after 36 successful ECCS scenarios. CC's guarded main-thread task cancels when its peripheral wrapper is detached. Native block scans were replacing the harness's manually attached remote peripheral with null. A deterministic native-scan control reproduced that detach. Draining the initial scan was insufficient: a later scan still failed after 43 successful scenarios. The final fix gives direct bindings one runtime owner. Reintroducing native ownership failed at the exact attachment-identity assertion; the final design then passed 100/100 real ECCS scripts with competing native scans.
- Restoring the old FCD sampler and request-time clock while retaining the real stale-menu probe failed with `FCD sampled frames before view readiness`. Both corrected three-stage positive runs passed. All six final images show the intended front-facing panels without a GUI or side-on reactor; the separate before image confirms the real Refuelling GUI was present during the probe. The first positive run captured Columbia and Browns Ferry correctly but timed out on matrix snapshot readiness; this was not counted as success. Holding the active fixture chunks through capture resolved that failure with the same 185-rod gate. These client captures partly overlapped the separate focused ECCS run, so their logged frame times are not claimed as isolated performance measurements.
- The first repeated-run attempt passed five Lua checks and four full suites, then suite five caught a deferred tank fixture error: 241 casings plus zero counted drops instead of 242. Read-only inspection of the saved entity region found the missing tank item at `[-13272898.875, 184.19404940102717, 4758424.697907276]`, age 54 and fall distance 36.66. The count box starts at Y195; the root was at Y220 with air below it. Waiting for lookup readiness let the valid item fall through the mined floor hole. This is a test-fixture assumption error, not evidence of production material loss. The failed attempt is preserved under `attempt-1/` and is not counted toward the final consecutive proof. A small ordinary support floor now keeps the real drop inside the existing bounds. With the same injected 80-poll readiness hold, the fixed fixture passed all 272 tests and retained 241+1=242; removing only floor creation produced exactly the original tank failure, 241+0!=242, in a normally completed 272-test suite. Exact source bytes were restored afterward. No artificial hold remains in the shipped test. The final ten-run sequence was restarted from zero after this correction.

## Limits and deviations

- Test harness and documentation changes only; no core physics, production protection, GUI screen classes, guidebook or Lua example behavior changes.
- Some originally global cache baselines were synchronous and could not interleave on the server thread; their move to fixture identity is explicit isolation, not a claim that an old race was reproduced.
- A cold chunk's lookup lag is scheduling-sensitive. The failure controls prove timeout cleanup, assertion strength and cross-fixture tank isolation; they do not claim a deterministic old-lag reproduction at every converted call site.
- Lua coverage uses the actual CC runtime and registered peripheral implementation, but does not validate physical computer adjacency/cable topology or plant calibration.
- FCD proof is limited to the stated close-up stages and actual inspected captures. No shader-pack, multiplayer or later-phase performance claim is made.
- Phase M step 0, Phase M implementation and Parts B/C remain outside this stop. No push was performed.

## Evidence

Local logs, exact mutation/restoration records, repeated-run source hashes, worker PIDs and captures: `C:/Users/14238/Downloads/BWR_Codex_Project_Brief/agent_work/part-a/`.

The complete per-class inventory is in [PART-A1-GAMETEST-AUDIT.md](PART-A1-GAMETEST-AUDIT.md). During the loaded suites, `sample_cpu.ps1` recorded CPU snapshots at least twice per suite. The FCD negative helper pins the old sampler to the base commit above so it remains reproducible after this commit.

This report is included in the Part A implementation commit; its hash is provided in the handoff response. No earlier accepted commits were amended.


## Repeated-run record

The runner did not delete or reset test worlds between these runs. Recorded source hashes were unchanged across the entire sequence.

| Pair | Lua scenarios | Lua seconds | GameTests | Suite seconds | Busy workers |
|---|---:|---:|---:|---:|---:|
| 1 | 23/23 | 122.624 | 272/272 | 144.367 | 0 |
| 2 | 23/23 | 106.745 | 272/272 | 115.795 | 0 |
| 3 | 23/23 | 109.07 | 272/272 | 135.24 | 0 |
| 4 | 23/23 | 108.259 | 272/272 | 119.214 | 0 |
| 5 | 23/23 | 107.14 | 272/272 | 115.382 | 0 |
| 6 | 23/23 | 107.218 | 272/272 | 119.059 | 0 |
| 7 | 23/23 | 106.668 | 272/272 | 116.214 | 0 |
| 8 | 23/23 | 106.944 | 272/272 | 123.855 | 16 |
| 9 | 23/23 | 106.761 | 272/272 | 124.0 | 16 |
| 10 | 23/23 | 107.168 | 272/272 | 117.363 | 16 |

All busy workers were still alive at the end of their suite and were then terminated and reaped by the owning runner. Two or more process snapshots per loaded suite verified increasing CPU time for every worker. No unrelated user processes were stopped.

Focused proof command: set `JAVA_TOOL_OPTIONS=-Dbwr.luaEccsRepeats=100 -Dbwr.luaEccsPendingScan=true` in the child process environment and run `./gradlew.bat :mod:runLuaExamplesCheck --offline --console=plain`. The normal repeated runs did not use these properties.

Reproduction helpers in the evidence directory: `py -3.13 a1_negative_batch.py`; `py -3.13 a1_negative_controls.py plans-global-restore`; `py -3.13 a1_tank_floor_control.py positive`; `py -3.13 a1_tank_floor_control.py negative`; `py -3.13 run_a2_proof.py`; `py -3.13 run_fcd_negative.py`; `py -3.13 run_fcd_positive.py`; `py -3.13 run_proof.py`. Negative helpers temporarily mutate only the specifically named source and restore its exact bytes. Run them serially, with no other build or source editor active.
