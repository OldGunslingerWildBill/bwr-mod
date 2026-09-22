# GitHub issues 14–18: fixes and verification

Implemented against the local tree after the original thirteen phase-one
repairs. Existing uncommitted model, condenser, cooling and storage-tank work is
retained. This pass targets the five additional issues filed by Sam-Elsberry;
it is separate from the R01–R05 findings in `PHASE-ONE-REAUDIT.md`.

| Issue | Change | Regression/verification |
| --- | --- | --- |
| [#14](https://github.com/OldGunslingerWildBill/bwr-mod/issues/14) | Added push/PR/manual GitHub workflow for physics/build, assets, JARs, GameTests, dedicated permissions and optional-mod absence. Documented coverage and release ownership. | Workflow syntax validated with actionlint; corresponding local commands are recorded in `BUILD-STATUS.md`. Hosted execution starts after pushing the workflow. |
| [#15](https://github.com/OldGunslingerWildBill/bwr-mod/issues/15) | The ordered acceptance runner verifies registration against the compiled test tree before full or filtered execution. | Real unregistered canary fails with its class name in an isolated copy of the test output. |
| [#16](https://github.com/OldGunslingerWildBill/bwr-mod/issues/16) | ECCS and feedwater pumps reuse stable port routes; current valve positions and known pipe/chunk changes invalidate them immediately. New branches appear within five ticks. | Three production pump fixtures, broken/repaired pipe, added tee, replaced tank owner, live TCV/MSIV movement; allocation and timing measurements including periodic rebuilds. |
| [#17](https://github.com/OldGunslingerWildBill/bwr-mod/issues/17) | ECCS, ADS and feedwater binding scans skip unloaded chunks. Reactor/source lookups guard chunk availability; a pending ADS release during rebind waits for its unloaded valve. | Extended the existing missing-chunk regression to all three binding paths. |
| [#18](https://github.com/OldGunslingerWildBill/bwr-mod/issues/18) | Formation and removal maintain a separate formed-reactor registry. RIP binding no longer enumerates the jet-survey cache. Lifecycle/geometry changes discard old surveys. | RIP binds with an empty jet cache before any flow measurement; unload, serialized replacement and broken-vessel cleanup are checked. |

## Scope of the original thirteen checks

The original permanent regression suite is retained and rerun, including its
operating-pump shared-valve tests and the six dedicated-server permission cases.
`TESTING.md` maps F01–F13 to the checks exercising them. This verifies those
reproductions remain fixed; it is not a guarantee against every future layout
or lifecycle combination.

The five R01–R05 issues from the subsequent verification audit remain separately
tracked: shared-port simulation, condensed basin inventory, remote computer
rebinding, MSIV-only branch routing, and recirculation command conversion. They
were not included in the request to fix GitHub #14–#18.

## Compatibility and performance

No machine dimensions, recipes, models, save formats, pump ratings or commands
change. Route caching retains the existing live source/inventory resolution and
shared steam budget. No automatic reactor protection is added.

See `TESTING.md` for the five-tick discovery window and benchmark methodology.
Measured timing is specific to the development machine; lower allocation in
these fixtures is not a promise of a particular whole-plant TPS gain.

Final server measurements for a 32-pipe suction route (200 advancing ticks,
39 periodic rebuilds; allocation measured on the server thread):

| Pump | Uncached time / lookup | Cached time / tick, including rebuilds | Uncached / cached bytes |
| --- | --- | --- | --- |
| HPCS | 115.7 microseconds | 22.7 microseconds | 15,102 / 3,889 |
| Electric feedwater | 43.0 microseconds | 10.8 microseconds | 15,120 / 3,889 |
| Turbine feedwater | 73.6 microseconds | 19.1 microseconds | 15,120 / 3,889 |

That is approximately 74% less allocation for these fixtures. Warm-cache
lookups allocated zero bytes in this run. The test asserts reduced allocation
and bounded rebuild counts, not a machine-dependent timing threshold.

Final check results and artifact hash are recorded in `BUILD-STATUS.md`.
