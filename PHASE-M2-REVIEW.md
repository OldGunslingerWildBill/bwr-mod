# Phase M2 — core grid arithmetic

Implemented independently while the additional M1 fixture-timing question was
pending. These are pure Java primitives; world integration remains M3–M7.

- Existing 13.4 W/(FE/t) conversion and derived 0.67 J/FE units.
- Completed-tick energy ledgers, 20/1200-tick windows including idle zeros,
  durable saturating long totals, reload warm-up, no telemetry-driven clock.
- Immutable validated fixed/daily/remote demand settings and server maximum.
- Shared long per-tick budgets, fractional carry, simulation purity and expiring
  whole-FE allowance. No persisted same-tick reservation.
- Phase/slip, reference sync-window indication and bad-close damage arithmetic.
  No breaker policy, automatic control or mod dependency in these classes.

## Verification

Six registered tests in `GridArithmeticTest`; `AcceptanceTests` now registers
300 methods in 45 classes including M1. Focused command from the repo:

```powershell
& "$env:JAVA_HOME\bin\java.exe" -cp 'core/build/classes/java/main;core/build/classes/java/test' dev.bwr.core.AcceptanceTests GridArithmetic
```

**6/6 pass** (`..\phase-m2-focused.log`). A separately compiled negative-control
copy that resets the demand budget on every call fails both the fractional-carry
and multi-call tests, including accepting another 2,147,483,647 FE after the
allowance was spent (`..\phase-m2-negative.log`). No production mutation.

The full registered core suite is running under:

```powershell
.\gradlew.bat :core:acceptance :mod:checkNoProtectionLogic --console=plain
```

Its final result must be read from `..\phase-m-core-full.log`; focused tests do
not establish that result. M1's separate existing valve test remains unresolved.

No FE capability, grid device, meter, demand clock service, NBT/item integration,
breaker state machine or long load-following scenario is claimed by this item.
