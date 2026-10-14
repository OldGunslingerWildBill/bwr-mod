# Phase M1 — shaft dynamics and authorized acceptance updates

Owner authorization: “update them,” 2026-10-14, in response to the two specific
acceptance-contract proposals in `PHASE-M-SPEC-UPDATE-REVIEW.md`.

## Built

- Updated §7.3.4 of the grid spec: load rejection at fixed valves, followed by
  explicit player throttling for the lower-steam operating point and recovery.
- Replaced `PowerModuleRuntimeCheck.modularPlant`'s steam-only FE balance with
  steam work + initial rotor energy = electrical work / efficiency + mechanical
  and coupling losses + final rotor energy. Its existing relative tolerance is
  unchanged (1e-6). Reload now verifies conservation of rotor/electrical energy
  and zero new steam work rather than requiring zero coast-down generation.
- Added core shaft energy balance, startup, passive losses, inertia and coupling
  heat, and overspeed damage. Mod expansion no longer depends on FE buffer room.
- Preserved the existing steam valves as the controls. No governor or trip.
- Saved overspeed and damage; exposed speed percent, overspeed, module kinetic
  energy, train loss power, damage and failed state through signals and CC.

The new generator speed characteristic and removal of the old RPM-squared steam
admission multiplier are explicit game calibration in the spec. Existing rated
steam capacities remain HP 2200 / LP 750 kg/s. A disconnected turbine still
retains steam for compatibility; its shaft can coast. Damage-preserving item
drops remain the planned M3 lifecycle work; M1 is not a finished grid-tie feature.

## Verification so far

From the repo, with Java 21 at
`C:\Users\14238\AppData\Roaming\PrismLauncher\java\java-runtime-delta`:

```powershell
.\gradlew.bat :core:testClasses :core:verifyTestRegistration :mod:compileJava :mod:checkNoProtectionLogic --console=plain
& "$env:JAVA_HOME\bin\java.exe" -cp 'core/build/classes/java/main;core/build/classes/java/test' dev.bwr.core.AcceptanceTests ShaftDynamics PowerTurbine
.\gradlew.bat :mod:runTurbineGameTest --console=plain
```

- Compilation and protection check passed; registration at that point was
  294 methods in 44 classes. Log `..\phase-m1-compile.log`.
- Focused core: 11/11 pass, including all six unchanged PowerTurbine tests.
- Negative control: a temporary compiled copy of ShaftDynamics with loss power
  disabled fails 2/5 shaft tests. Production sources were not mutated. Log
  `..\phase-m1-negative.log`; temporary files in `tmp/shaft-negative-control`.
- Initial full GameTests: **272/273 pass**, one required failure described below.
  After the separately authorized fixture timing correction: **273/273 pass**
  (`..\phase-m1-gametests-fixed.log`), including all 12 steam-valve scenarios.
  The final protection check passed (593 files scanned).
  `PowerModuleRuntimeCheck`: 16 scenarios pass. Log `..\phase-m1-gametests.log`.
- New fixture origin `(75000,245,75000)` starts at rest, uses a fixed external
  steam supply through a real stop valve and a fixed heat sink, and never writes
  shaft RPM or phase. With electrical extraction it reaches 793.669 rpm. Load
  rejection raises speed to **1702.829 rpm after 12.05 s**. Manual stop closure
  removes fresh steam torque; after 40 further ticks speed is **1701.127 rpm**,
  damage **0.00750164**. Overspeed/damage round-trip without a rated-speed clamp.
- A full core acceptance run including M2's six arithmetic tests was started as
  `:core:acceptance :mod:checkNoProtectionLogic`; its result is recorded in the
  subsequent stop report, not inferred from these focused passes.

An initial compile of the new GameTest used the wrong AABB constructor; corrected
before the full run above. No assertion was removed to address it.

## Additional authorized fixture correction

`SteamValveRuntimeCheck.run`, zero-opening iteration, intentionally advances the
world clock to expire a flow-meter sample, then executes its duplicate-tick
check. That check now tests a genuinely new tick. Retained rotor energy produces
FE during that tick, yielding:

```text
second tick regenerated steam: 2.227385502689598E8 != 1.1194029850746268E8
```

Owner-authorized fix: move the unchanged `stored -> PowerTrain.tick -> near(stored)`
assertion before the intentional meter-expiry clock advance. Retain the zero
flow-meter checks after the advance. This tests duplicate execution on the same
tick and leaves the original tolerance and comparisons unchanged.

The owner separately authorized: "Fix fixture timing; keep assertion unchanged."
That timing change is included in M1. Its comparison and tolerance are unchanged.
Legitimate coast-down output is preserved.

## Limits

M1 does not include a grid tie, meter, breaker, generator delivery ledger, retained
item condition, real synchronization or the coupled load-follow scenario. M3–M7
remain necessary. No client/GUI, CC-absent startup, Lua or asset/translation audit
was run for this item. No push or pull. Known unrelated untracked files untouched.
