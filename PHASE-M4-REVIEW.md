# Phase M4 — external demand, manual breaker and synchronizing

Added the grid tie, fixed/daily/remote demand, server maximum, one-way saved
generator references, per-face shared long FE allowance, and shared loaded-device
service. No new block ticker, production chunk ticket, governor or automatic trip.
Closing outside the reference angle/slip window succeeds and damages the source.
Basic CC registration is included; complete adapters/linker/report follow in M6.
The block model is the explicitly permitted placeholder.

`GridTieTests` uses distinct origins (91000,245,91000) and (91128,245,91000).
The real steam/control-valve sequence starts at rest and never writes RPM or phase:

- In-window close: 35.9 s, 1500.8717 rpm, -9.5289 degrees, +0.02906 Hz slip.
- 302238805 FE delivered before manual trip; generator and tie totals agree.
- Separate deliberately bad close at 56.2999 degrees causes persistent damage.
- Stationary close is also accepted with damage, not blocked.
- Multi-face 48000-MW allowance exceeds signed-int FE/t without overflow; repeated
  simulation is pure, a configuration edit does not replenish a spent allowance,
  settings apply next tick, and reload retains totals but clears rate windows.

Verification:

- `:mod:runTurbineGameTest :mod:checkNoProtectionLogic`: 276/276 passed,
  BUILD SUCCESSFUL in 2m08s (`../phase-m4-gametests.log`).
- Prior persistent-world repeat: 275/275 passed in 2m06s
  (`../phase-m34-gametests-repeat.log`), including unchanged remote-port regression.
- `py -3.13 tools-audit-assets.py`: 0 problems.
- `py -3.13 tools-audit-translations.py`: 0 problems.

No manual client review or installed-cable validation in this item. Nothing pushed.
