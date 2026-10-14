# Phase M review update — stopped at an acceptance-contract conflict

Date: 2026-10-14. Branch: `containment-structure`; starting commit `bc49e32`.

## Completed

Applied R1–R5 to `GRID-TIE-SPEC.md` and updated the stale B6 deferral in
`REFERENCE-DATA.md` §13. One documentation commit, titled
`Apply Phase M review and document shaft acceptance conflicts`.

- Seven implementation commits, with shaft dynamics before breaker/sync.
- Explicit rotor-energy balance, player throttle, mechanical losses and overspeed
  damage; new numerical parameters are explicitly game calibration.
- One-way tie binding; several ties per generator.
- Existing panel linker for generator binding and meter selection.
- Transient per-tick budgets; no same-tick unload persistence.
- Separate registered long acceptance when the 51-hour scenario would add more
  than approximately three minutes to the normal suite.

No production code, existing test, GUI or asset was changed. Implementation has
not started. Nothing was pushed or pulled; the two pre-existing untracked paths
were untouched.

## Why implementation stopped

The handoff expressly permits an early stop for a real scope conflict and requires
existing tests not to be weakened. R1's shaft physics conflicts with two retained
acceptance contracts. These are source-level findings, not executed failures:

1. **Fixed valves, less grid demand, less steam** (`GRID-TIE-SPEC.md` §7.3.4).
   `PowerTrain.java:40` currently gates expansion on buffer room, and line 51
   relaxes RPM to a fixed target. R1 removes both behaviors. Reduced electrical
   work then leaves more rotational energy. The admission capacity in
   `PowerModuleBlockEntity.java:84` rises with RPM rather than closing a valve.
   Proposed acceptance: lower demand at fixed valve position and assert the
   load-rejection acceleration; then explicitly throttle the existing control
   valve to demonstrate reduced steam draw and controlled speed. Recover through
   explicit operator commands. Never make the source track demand or add an
   automatic production governor.
2. **Exact steam-only FE accounting and zero output after reload**
   (`PowerModuleRuntimeCheck.java:134–138`, `modularPlant`). The fixture seeds
   1500 rpm, then expects all steam work times 0.985 as FE after 300 ticks. It
   omits changes in kinetic energy and mechanical losses. Its subsequent zero-MW
   assertion also forbids conversion of retained rotor energy after reload.
   Proposed authorized edit: conserve steam work plus starting rotor energy
   against generated electrical work divided by efficiency, mechanical losses
   and final rotor energy; retain the current numerical tolerance. Preserve all
   mass, header allocation and duplicate-tick checks. Verify reload preserves
   both steam and shaft energy without introducing additional energy.

The core `PowerTurbineTest.test02` need not change: its explicit work-budget
primitive remains valid. The mod caller must supply the appropriate expansion
budget instead of conflating it with electrical buffer room.

The original §7.3.4 requirement and all existing tests remain unchanged pending
resolution. The concrete proposals are recorded in spec §9.1. Authorization is
needed for these two acceptance-contract updates before M1 proceeds.

## Verification

Commands run from `C:\Users\14238\Downloads\bwr-mod-handoff\bwr-mod`:

```powershell
py -3.13 ..\phase-m-spec-check.py
git diff --check
$env:JAVA_HOME='C:\Users\14238\AppData\Roaming\PrismLauncher\java\java-runtime-delta'
.\gradlew.bat :mod:checkNoProtectionLogic --console=plain
```

- Documentation check: nine current-spec checks pass; all nine reject the
  `bc49e32` baseline as negative controls. Inertia derivation and module fractions
  pass arithmetic checks. This verifies documentation contracts, not physics.
- `git diff --check`: pass.
- Protection check: `BUILD SUCCESSFUL in 19s`, task `UP-TO-DATE`. Log:
  `..\phase-m-spec-protection.log`.

Not run: core acceptance, GameTests, Lua checks, client checks, builds or asset /
translation audits. There were no source, asset or translation changes. No Phase
M physics, synchronization, metering, save migration or load-following behavior
has been implemented or verified. No newly failing suite is claimed.

## Deviation

Stopped before M1 under the handoff's scope-conflict rule. Phase M is incomplete;
this is not the requested final seven-commit implementation report.
