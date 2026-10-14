# Phase M3 — generator delivery accounting

Actual external extraction and terminal push now share a delivery ledger. Push
reserves inventory before calling a receiver so a reentrant receiver cannot spend
the offered FE twice. Rejected reservations are refunded. Completed-tick generated,
delivered and buffer-delta readings are separate; existing electric MW stays production.
Lifetime delivery and damage survive normal item drops; placement gets a new UUID.
Cached capabilities reconnect to the same saved machine, not a replacement.

New regression: `GeneratorAccountingTests.deliveryReservationAndRetainedCondition`
at (77000,245,77000), covering simulation, fractional FE, push/pull, a reentrant
receiver, exact accounting, save/load and actual item drops. The new M1 shaft test
also verifies generated FE minus delivered FE equals buffer delta each step.

Verification with the in-progress M4 devices registered:

- `:mod:runTurbineGameTest :mod:checkNoProtectionLogic`: 275/275 required tests,
  protection scan 604 files, BUILD SUCCESSFUL (3m31s). Log in parent directory:
  `phase-m34-gametests-fresh.log`.
- Full `:core:acceptance :mod:checkNoProtectionLogic`: 300/300 core tests,
  BUILD SUCCESSFUL (18m24s), `phase-m-core-full.log`.

An earlier run exposed cached capability reconnection to a provisional controller
created during the removal interval. Production now distinguishes established
identities from those provisional owners. The existing remote-port regression was
not edited. Its failed-run world was preserved before the clean verification run.
Persistent-world repeat results are recorded in the subsequent review.

No GUI or cable compatibility claim in this item. Nothing pushed.
