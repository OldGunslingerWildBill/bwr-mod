# Phase M6 — panel/CC controls and aligned load report

Added explicit meter lists (32 unique identities), disjoint-feeder MW error,
completed-tick alignment, validity/warm-up, common telemetry and all specified
signal/CC fields. Empty selection means zero observed base load; unavailable
endpoints omit derived values. Dynamic meter keys expose labels, validity, MW/MWh.
Numeric and pulse commands share `DeviceCommands`; CC dispatch copies at most
32 coordinate rows before scheduling on the server thread. Nonfinite, fractional
enum/coordinate and invalid curve commands reject without partial mutation.

The existing linker binds a selected generator to an open tie or toggles a
selected meter, with range/dimension/loading/permission checks. Panel component
binding retains its existing gestures. No new GUI or `/bwr` command was added.
`SIGNALS.md` and `docs/grid-wiring.md` describe units, commands and wiring limits.

New regression: `GridControlTests.linkerCommandsAndAlignedReport` at
(95000,245,95000), including actual linker uses, generator -> meter -> tie,
an explicitly selected base feeder, excluded export meter, duplicate/invalid
selection, panel/CC parity, real dynamic-peripheral dispatch, invalid values,
query purity, retained requests while open, reload warm-up and replacement.

Verification:

- `:mod:runTurbineGameTest`: 281/281 required tests passed with the in-progress
  M7 scenarios (`../phase-m-final-mod.log`). Earlier M6-only run: 279/279.
- Asset/translation audits: 0 problems.
- CC-absent startup and the new linker/adapter scenario passed. The full
  CC-absent suite has three pre-existing tests that instantiate CC classes
  unconditionally: `Alpha31ContainmentPeripheralTests.roomPeripheralExposesOpeningsAndPoweredIsolation`,
  `Alpha31ContainmentEquipmentTests.missingEquipmentCannotPurgeOrVent`, and
  `PhaseOneRegressionTests.stoppedRecirculationOverride`. Their code is unchanged
  from `bc49e32`; all fail with missing `IPeripheral`. They have not been edited.
  The first CC-free run also caught a new M7 startup-fixture error, subsequently
  corrected and passed in the standard run. Final CC-free counts follow in M7.

Full core, Lua examples and final packaging are running for M7. No claim of a
green complete CC-absent suite, arbitrary cable priority or manual GUI review.
Nothing pushed.
