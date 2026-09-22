# Phase One Audit Fixes

Implemented September 21, 2026, against the working tree reviewed in
[PHASE-ONE-AUDIT.md](PHASE-ONE-AUDIT.md). Existing condenser, cooling and tank
development changes are retained.

## Repairs

| Finding / GitHub issue | Corrected behavior | Regression coverage |
| --- | --- | --- |
| [F01 / #1](https://github.com/OldGunslingerWildBill/bwr-mod/issues/1) | Controller removal returns loaded fuel once, including fuel awaiting restoration. Bundle enrichment, mass, burnup and remaining gadolinia are preserved. | Live removal, saved-but-unformed controller, exposure and repeated recovery. |
| [F02 / #2](https://github.com/OldGunslingerWildBill/bwr-mod/issues/2) | A basin has one world-saved inventory and one controller. Replacing the controller cannot refill it. | Duplicate controllers, withdrawal, replacement, basin-data save/restore. |
| [F03 / #3](https://github.com/OldGunslingerWildBill/bwr-mod/issues/3) | Vessel validation requires exactly one controller. | Both duplicate controllers rejected; formation restored after removing the extra controller. |
| [F04 / #4](https://github.com/OldGunslingerWildBill/bwr-mod/issues/4) | Remote energy/fluid interfaces resolve the current loaded owner for every operation. Valid ports remain reconnectable while their controller is unavailable. | Generator/LP inventory replacement, pump/condenser/cooling water reconnection, capability cache across chunk availability changes and engine unload/NBT restoration. |
| [F05 / #5](https://github.com/OldGunslingerWildBill/bwr-mod/issues/5) | Placement and player mining check the entire affected footprint against vanilla permissions. Tank shrinking also checks cells it removes. | Six dedicated-server scenarios with an actual non-operator and active spawn protection. |
| [F06 / #6](https://github.com/OldGunslingerWildBill/bwr-mod/issues/6) | A vessel cannot form at a smaller size while fuel occupies positions excluded by that size. | Rejected shrink preserves fuel; restoring the old vessel exposes it again. |
| [F07 / #7](https://github.com/OldGunslingerWildBill/bwr-mod/issues/7) | Reactor computer recirculation commands set connected motor demand. Actual core delivery always comes from measured hardware. | Unpowered commanded pump delivers zero; powering it produces bounded flow. |
| [F08 / #8](https://github.com/OldGunslingerWildBill/bwr-mod/issues/8) | Only quenchers and direct-water discharge points in the controller's surveyed basin count toward its steam input. | A quencher in a separate neighboring basin is excluded. |
| [F09 / #9](https://github.com/OldGunslingerWildBill/bwr-mod/issues/9) | Pump admission follows a real route and shares valve allowance/metering with other steam consumers. Unused branches do not throttle the selected route. | Dead branch; operating RCIC, HPCI and turbine feedwater pumps competing with another consumer; closed admission. |
| [F10 / #10](https://github.com/OldGunslingerWildBill/bwr-mod/issues/10) | Reactor and basin validation use loaded chunks only. Missing required terrain produces a diagnostic and defers formation. | Loaded-chunk inventory checked before/after both scans. |
| [F11 / #11](https://github.com/OldGunslingerWildBill/bwr-mod/issues/11) | Fully loaded assemblies with missing cells finish dismantling. Temporarily unavailable chunks suspend operation without dismantling an intact machine. | Saved incomplete pump, RCIC, power module, condenser, cooling pump and tank; child removal during a controller availability transition. |
| [F12 / #12](https://github.com/OldGunslingerWildBill/bwr-mod/issues/12) | Actual fabricator charging marks its chunk dirty; simulated charging does not. | Dirty flag, simulation and energy save/restore. |
| [F13 / #13](https://github.com/OldGunslingerWildBill/bwr-mod/issues/13) | Live cores rebind fuel after datapack replacement and refresh derived physics constants without resetting bundle history. | Real fuel-loader apply path; changed multiplication factor and delayed-neutron fraction; preserved exposure. |

## Existing worlds and controls

- **Fuel recovery:** mining a reactor controller drops its loaded bundles as
  items under the normal block-drop game rule. A rejected shrink explains how
  to recover: restore the previous vessel size and unload the outer bundles,
  or remove the controller to recover its fuel.
- **Suppression pools:** the first successful claim migrates the existing
  controller's simulated inventory into world saved data. That record survives
  controller replacement. Another controller cannot claim it while the current
  owner remains placed or its location is unloaded. Previously metered basins
  cannot be merged by moving controllers. Geometry changes adjust capacity;
  they do not refill a metered basin. Historical basin cells remain recorded to
  prevent draining and rebuilding the same basin for free water.
- **Computer recirculation:** `setRecirculationFlow` still exists, but now
  requests speed from connected physical motors. It no longer supplies virtual
  flow to a reactor without working pumps. Power and motor response determine
  delivery; natural circulation remains part of the core model.
- **Broken machinery:** recovery waits for all footprint chunks. Missing cells
  then cause normal teardown, including the controller/material refund.
  Intentionally damaged assemblies must be rebuilt. Stored process water and
  energy follow the existing teardown behavior.
- **Fuel reload:** new definitions take effect on the next formed-reactor tick.
  Existing enrichment, heavy-metal mass, exposure and remaining gadolinia stay
  with each bundle.

## Verification

Final build and verification results are recorded in [BUILD-STATUS.md](BUILD-STATUS.md).
The permanent tests are in `mod/src/main/java/dev/bwr/mod/devtest/` and are
excluded from the distributed JAR.

The chunk regression uses real chunk-availability transitions and a real
NeoForge capability cache. Because Minecraft retains neighboring chunks at a
partial status, the test explicitly invokes the engine unload callback and
restores serialized block-entity data to cover a discarded controller instance.
It is not a long-duration multiplayer or third-party chunk-loader test.

The repairs do not add the separate phase-two features identified in the audit:
LP exhaust coupling to the external condenser and plant-wide temperature/
enthalpy transport remain future work. Automatic plant protection remains
player-controlled.
