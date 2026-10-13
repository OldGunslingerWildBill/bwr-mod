# Pre-L2 fixes and L2 review

Branch: `containment-structure`. Work remains local; nothing was pulled or pushed.
Stop point: **L2**, before L3 advancements.

## 1. Repaired-seal hydrogen accumulation — `f2ca75b`

With the user's explicit authorization, `ContainmentStructurePhysicsTest.test03`
now checks the maximum hydrogen percentage during the 60-second sealed period.
It retains the threshold `sealed max > leak-period peak + 1.0` and all
open-boundary assertions. The commit message records why final hydrogen no
longer measures this intent after a localized drywell burn.

| Measurement | Result |
| --- | ---: |
| Leak-period peak H2 | 0.377741% |
| Sealed-period peak H2 | 4.682459% |
| Final H2 | 0.428661% |
| Burns | 1 |

The **full post-L1 core suite passed 289/289**, in 981.1 seconds of test time.
Command: `.\gradlew.bat :core:acceptance --console=plain`.
Log: `../pre-l2-core-full.log`.

## 2. Steam-inerting scenario — `ac178c6`

Selected the explicit-strengthened-boundary option. The fixture already used a
1,000-psig threshold; the test now explains and prints it alongside the normal
140-psig threshold, and asserts the fixture is intact before spray. It isolates
steam inerting and spray-triggered combustion. It is **not** a survivable Mark I
accident or a structural-strength validation. Steam inputs and acceptance
thresholds were unchanged.

| Timeline point | Steam | H2 | O2 | Drywell / wetwell |
| --- | ---: | ---: | ---: | --- |
| Before spray, after steam loading and 20 s of H2 injection | 85.2% | 2.1% | 2.7% | 151.3 / 109.7 psig; no burns |
| 133.1 s after spray starts | 64.7% | — | — | One burn; peak 151.4 / 117.4 psig |

These rerun results match the prior timeline, as expected for a clarified test
boundary with unchanged physics. The full core run above includes this scenario.

## 3. RVU stage-10 root cause — `8a02913`

The first failing commit is `f6b3617`, which introduced both automatic sparger
corners and the stage-10 fixture. Reproductions on that commit and `8c5510b`
failed at `(149,191,152)`; parent `79994bd` passed its earlier six-stage harness,
which did not yet contain stage 10.

The cause is fixture construction. It called `setBlock(defaultState)` for every
segment. Normal item placement calls `getStateForPlacement`, which computes
same-loop perpendicular connections. Neighbor updates corrected earlier pieces,
but the final corner never received a later shape update and stayed
`CORNER=false`. The default heading also oriented side straight pieces across
the ring instead of along it.

The fixture now uses `DirectionalPlaceContext` and `getStateForPlacement`, with
placement directed from inside each side of the ring. No geometry/exporter
change or expectation relaxation was needed. **Every original corner and
baked-material assertion is unchanged.**

- Full current RVU harness: all 12 stages passed after using normal placement.
- Final quick harness: all six selected stages passed, including corrected
  inward headings, sparger corners and jet proportions.
- Inspected final capture:
  `mod/run/turbineModelCheck/rvu-check/rvu-sparger-corners.png`.
- Logs: `../pre-l2-rvu-fixed.log`, `../pre-l2-rvu-final.log`; earlier historical
  reproductions are in `../rvu-baseline.log`, `../rvu-intro.log` and
  `../rvu-parent-real.log`.

## L2. Translatable status and chat

Player reports now carry keys and arguments to the receiving client. Nested
validation reasons, equipment/accident states, coordinates and measurement
precision survive serialization. Existing diagnostic and Lua String consumers
retain English output. The unformed-reactor menu now transports components;
its wire format is identified by GUI protocol 18.

Panel chat/status coverage includes the existing key-switch, footprint and
linker messages plus translated wiring-refusal reasons. The common network
handler reports those reasons. No file under `dev.bwr.mod.panel.client` was
edited. A percent sign in the panel binding preset's language entry was escaped
for Minecraft's translation formatter.

See `TRANSLATIONS.md` for contributor instructions and scope. Only the English
catalog ships here; other languages can be supplied by resource packs.

### Verification

- Final full GameTests: **217/217 passed**, including two new L2 tests and the
  actual reactor-menu snapshot round trip.
- `:mod:build`: passed; JAR packaging audit passed.
- `checkNoProtectionLogic`: green, 555 mod source files scanned.
- Translation audit: **400 static calls, 732 English keys, 0 problems**.
- Asset audit: **0 problems**.
- Final command: `.\gradlew.bat :mod:runTurbineGameTest :mod:build
  :mod:checkNoProtectionLogic --console=plain`. Log: `../l2-final-verification.log`.

The localization tests exercise the real chat call, translation selection after
serialization, reordered nested arguments, stable English diagnostics, the
reactor-menu snapshot and all panel wiring statuses. Their fixture uses a unique
origin `(67900,230,67900)`, clears its block and releases its forced chunk.

No existing test was weakened. The only existing test changes are the two
explicitly requested pre-L2 updates above. Pre-existing untracked `.claude/`
and `PROJECT-CHANGELOG-START-TO-ALPHA29.txt` were left untouched.
