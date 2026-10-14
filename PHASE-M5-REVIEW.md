# Phase M5 — directional zero-storage FE meter

Added six-way direction, a raised arrow placeholder model, label/units, persistent
identity/totals, normal item condition retention, and synchronous push/pull bridging.
Only the opposite input/output faces expose capabilities. Wrong, unloaded, replaced
or rotated endpoints refuse transfer. Direction changes reset rates, not totals.
Visited identities and a 64-bridge depth guard cover lookup and transfer, including
simulation, with cleanup in finally. No polling transfer job or meter inventory.

`FeMeterTests` uses fresh origins (93000,245,93000) and (93256,245,93000).
It checks real generator push, external pull, simulation, wrong faces, downstream
rejection, fractional source FE, item/reload, 65/64-bridge boundaries and reentry.
An initial new fixture reused the first meter's retained total when creating its
chain; it now removes those fixture owners before building the new chain. The
first cable probe addressed an unconnected cable side; the revised fixture uses
an actual generator connection and ordinary server ticks.

Installed cable evidence: **Mekanism 10.7.19**, generator -> basic universal cable
-> FE meter -> fuel fabricator, 10000 FE supplied, 10000 metered/received, source
0 and cable 0 remaining. This proves that arrangement, not every foreign cable or
priority layout. The meter still reports zero inventory and zero capacity.

- `:mod:runTurbineGameTest :mod:checkNoProtectionLogic`: **278/278**, green
  protection scan, BUILD SUCCESSFUL in 2m17s (`../phase-m5-gametests-fixed.log`).
- Asset and translation audits: 0 problems.

Complete shared commands/adapters are M6. No manual visual inspection of the
placeholder arrow yet. Nothing pushed.
