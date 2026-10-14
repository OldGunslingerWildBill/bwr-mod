# L5 player Lua examples

**2026-10-14 resolution:** The two panel regression failures recorded below were diagnosed and repaired; the final current suite passed 269/269 twice on the same persistent world. See [Panel regression repair review](PANEL-REGRESSION-FIX-REVIEW.md) for the root causes, negative controls, cleanup verification, and complete validation. The original L5 run results below remain unchanged as historical evidence.

Branch: `containment-structure`. Base: `e07a8cea677235164e60f5fe5b912ad89774cf1a`.
Stop point: **L5, before Phase M step 0**. Nothing was pulled or pushed.

## Built

Five standalone examples in `docs/lua/`, each explicitly headed as the player's
own automation, not part of the mod:

- `startup.lua`: one player-selected recirculation/rod manoeuvre, with argument,
  formation, vessel-head, scram, and existing rod-demand checks. It waits for
  physical rod position and the rods-not-at-demand count with a bounded timeout.
- `backup_scram.lua`: a bounded monitor using player-supplied total-power,
  pressure, and indicated-level limits. It sends at most one scram, confirms the
  latch, and never resets it. Poll expiry explicitly ends monitoring.
- `eccs_start.lua`: sets suction, injection mode for RHR, flow/speed fractions,
  and the run command for one named ECCS train. It reports current delivered
  flow separately from accepted commands.
- `containment_isolation.lua`: preflights named MSIVs and penetrations, sends
  close commands, handles refusal, and waits for measured valve closure. It
  reports partial failure and never reopens an accepted close demand.
- `panel_display.lua`: renders reactor telemetry and a power bar on an existing
  named graphic display, with one-frame, finite-count, and continuous modes.
  Drawing methods returning `false` mean unchanged and are not treated as errors.

`docs/lua/README.md` explains installation, named peripheral connections, every
CLI and its units, command persistence, display setup, and operational limits.
The root README links to it. No shared Lua library or extra runtime dependency
is needed. No example is embedded into the mod, installed on a player's computer,
or automatically started. Production Java, core physics, saves, and UI are
unchanged.

## Validation

The new `:mod:runLuaExamplesCheck` is an opt-in dedicated-server integration
check. It loads the exact five files from `docs/lua/` into real CC:Tweaked
computers, executes them with CC's Lua runtime, and attaches actual registered
BWR peripheral capability objects to the computers' sides. It does not replace
the peripheral API with Lua mocks. It logs every script's SHA-256 and records
Lua results plus block-entity readbacks in
`mod/run/luaExamplesCheck/lua-examples-check/results.json`.

The 22 scenarios cover:

| Program | Scenarios | Important readbacks |
| --- | ---: | --- |
| Startup | 5 | Rod demand and physical position both reach notch 2; without drive supply, demand remains 2 and physical position 0 at timeout; invalid notch, active scram, and missing peripheral are rejected. |
| Backup scram | 5 | Low-level trigger sets the real scram latch; no-trigger case leaves it clear; already active, unformed, and missing-peripheral cases are covered. |
| ECCS start | 3 | RHR run command, injection mode, pool suction, 0.35 flow demand, and 0.6 speed demand; invalid flow and missing pump are rejected. |
| Containment isolation | 4 | MSIV and steam penetration physically reach position 0; preflight failure preserves open valves; a non-valved service refuses closure while the valid MSIV closes; timeout leaves the accepted close demand applied. |
| Panel driver | 5 | Two formed and two unformed frames; exact `P 0.0%`, `SCRAM NO`, and `UNFORMED` glyph pixels; unchanged drawing returns `false` normally; missing label/reactor and invalid interval leave the display unchanged. |

Each scenario uses its own fixture at `(83_000 + index * 64, 190, 83_000)`.
Fixtures reset the existing area before use and clear it afterward. Cleanup
releases all fixture chunks on success and failure, then allows 100 normal ticks
for unload/save work to settle before normal server shutdown. A completion token
prevents reading a partial or stale Lua result. Failed checks produce a nonzero
process exit, including when Minecraft would otherwise exit successfully.

Commands were run from the repository root with Java 21:

```powershell
$env:JAVA_HOME = 'C:/Users/14238/AppData/Roaming/PrismLauncher/java/java-runtime-delta'
.\gradlew.bat :mod:compileJava --offline --console=plain
.\gradlew.bat :mod:runLuaExamplesCheck --offline --console=plain
```

Compilation passed. The first complete run of the final harness passed **22/22**
scenarios in **1m 49s**, with all five source hashes recorded. Cleanup released
**132 chunks**, with **0 pending tasks** and **no light work**, and the server
saved all dimensions and exited normally.

A negative control temporarily replaced the real startup script's quoted
`setRodPosition` method with `l5_missing_setRodPosition`, then ran the same
Gradle Lua check. It failed as intended with
`required method unavailable: l5_missing_setRodPosition`, and Gradle exited 1
after 41 seconds. The helper restored the original script in `finally`; the
restored SHA-256 is
`5f5a21b56459852766c437f8a38fb275fef8eff15fb0cc6de8dcdeac474b92e8`.
No assertions or production APIs were changed for this control.

The restored files were then checked again in the same persistent test world:

```powershell
.\gradlew.bat :mod:runLuaExamplesCheck :mod:runTurbineGameTest :mod:build :mod:checkNoProtectionLogic --offline --console=plain
```

The Lua task passed **22/22 again**, with the same five source hashes, 132
released chunks, zero pending tasks, no light work, and normal server exit.
The subsequent existing GameTest suite passed **234/235**: unchanged
`PanelDesyncTests.orphanClaimIsDroppedOnVerify` expected one dropped nameplate
but found zero. This made the combined command fail after **3m 59s**, before
its build/protection tasks ran. No production panel code or existing test was
edited; the new Lua harness is inactive during that separate GameTest run.

The build/protection tasks were therefore run first, followed by an unchanged
GameTest rerun:

```powershell
.\gradlew.bat :mod:build :mod:checkNoProtectionLogic :mod:runTurbineGameTest --offline --console=plain
py -3.13 tools-check-jar.py
git diff --check
```

The build and protection check passed: **566 mod source files**, no protection
logic. The JAR audit passed: **771 class files**, **2 project JARs** with license
and attribution, **0 bundled CC classes**, **0 bundled Mekanism classes**, and
**0 devtest entries**. The unchanged GameTest rerun again passed **234/235**:
the first failing test passed, but
`PanelDesyncTests.unloadedOwnerRepairsWhenItsChunkLoads` failed with
`nothing left deferred`. The combined rerun command exited 1 after **2m 7s**.
Whitespace validation passed.

**Regression qualification:** neither full GameTest run was clean. Both
failures are in the existing, unchanged `PanelDesyncTests`; they differ between
runs. Their cause has not been established, and this report does not assert that
an existing test is wrong. No assertion, test timing, panel implementation, or
fixture in that class was changed to obtain a pass. L5's own 22-scenario real-CC
check passed twice; the broader panel test failures remain reported for review.

Local evidence is preserved in
`C:/Users/14238/Downloads/BWR_Codex_Project_Brief/agent_work/l5/logs/`:
`lua-drain.log`, `positive-drain-results.json`,
`negative-missing-method.log`, `negative-results.json`,
`negative-restoration.json`, `final.log`, `positive-final-results.json`,
`regression-rerun.log`, and `jar-check.log`. The negative-control helper is
`../negative_missing_method.py` relative to that evidence directory; it was run
with `py -3.13` and returned success only after observing the expected Gradle
failure and restoring the source.

Development iterations exposed two harness problems: attaching peripherals
before CC finished booting let its initial neighbor scan remove them, and an
immediate server stop after chunk release stalled the unload/save loop. The
final harness attaches after a boot-ready marker and drains cleanup before
shutdown. The stalled development run passed the scenarios but was terminated
after thread-dump inspection and is **not counted as a successful run**. Fixture
review also corrected panel orientation and made valve tests wait for measured
fully-open position before exercising closure. No existing tests were changed.

## Scope and limits

Startup is one deliberate manoeuvre in the player's sequence, not a procedure
that finds criticality or takes a whole plant to target power. A scram latch
does not prove every rod has inserted. Pump commands do not prove hydraulic
delivery. Named valve closure does not prove the entire containment enclosure
is sealed. A stopped computer leaves the panel's last picture visible.

Example polling intervals and timeouts are program settings, not new gameplay
calibration or plant protection setpoints. Backup thresholds are all explicit
player inputs. Commands already sent remain applied on termination or error.

The runtime fixtures verify actual peripheral dispatch and selected machine
effects, with these boundaries:

- Startup executes the real recirculation setter but has no connected
  recirculation motors, so motor demand and hydraulic response are not asserted.
  Physical rod movement is asserted.
- ECCS runtime coverage uses RHR. The other supported ECCS types are checked
  against their actual source APIs but are not each started in this check. The
  unplumbed fixture's delivered flow is zero; the check proves commands, not
  cooling performance.
- The backup scram runtime trigger uses low level. High-power and high-pressure
  branches were source-reviewed, not driven through separate plant transients.
- Direct attachment of real capability objects tests API execution, not wired
  modem/cable discovery. Panel checks inspect stored pixels, not client captures.
- No full-plant startup/criticality sequence, hydraulic acceptance scenario,
  separate core acceptance suite, client visual run, or optional-dependency
  matrix was added or claimed. Advancement and audio client checks were not
  repeated for this documentation/devtest change.

No placeholders or spec deviations. No existing test assertions were weakened.
There is no all-green regression claim because of the two failures above.
Phase M's spec and references have not been started. The pre-existing untracked
`.claude/` and `PROJECT-CHANGELOG-START-TO-ALPHA29.txt` are preserved.
