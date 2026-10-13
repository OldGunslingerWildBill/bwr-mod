# L3 advancement review

Branch: `containment-structure`. Stop point: **L3**, before L4 sounds.
Work remains local; nothing was pulled or pushed.

## Built

Added eight server-observed milestones: first criticality, delivered generator
power, a completed intact scram, refueling, sealed containment, an instrumented
control room, lower-head failure, and basemat melt-through. Nine advancement
definitions include the tab root, with 18 English translation keys. The two
accident entries are hidden and do not announce in chat. No entry grants items,
recipes, experience, or functions.

`ADVANCEMENTS.md` records every predicate and the saved-data contract. Equipment
stores cosmetic ownership and pending awards in its existing block-entity NBT.
Real player placement or the first permitted legacy interaction establishes
attribution. Automated fake players cannot take it. Offline delivery retries
only loaded equipment; no chunk is force-loaded. Existing accident state is
baselined before the first resumed step to avoid retroactive accident awards.

The hooks reuse existing reactor, FE extraction, refueling, containment-survey,
and panel-sampling paths. They add no block ticker, plant commands, protection
logic, or permissions. Core physics and GUI classes are unchanged.

## Verification

Commands ran from `C:\Users\14238\Downloads\bwr-mod-handoff\bwr-mod` with
`JAVA_HOME=C:\Users\14238\AppData\Roaming\PrismLauncher\java\java-runtime-delta`.

```powershell
.\gradlew.bat :mod:runTurbineGameTest :mod:build :mod:checkNoProtectionLogic --offline --console=plain
py -3.13 tools-audit-advancements.py --self-test
py -3.13 tools-audit-assets.py
py -3.13 tools-audit-translations.py
py -3.13 tools-check-jar.py
git diff --check
```

- Corrected full server run: **227/227 passed**, including 10 new L3 tests.
- Restored persistent-world run: **227/227 passed** again, in 1.317 minutes
  of GameTest time; the complete Gradle command succeeded in 2m 4s.
- Mod build: passed. Protection check: **557 files, 0 violations**.
- Advancement audit: **9 definitions, 0 errors**; all 9 deliberate corruptions
  were rejected.
- Asset audit: **2,897 JSON files, 0 problems**.
- Translation audit: **400 static calls, 750 English keys, 0 problems**.
- Final JAR audit: **769 class files checked, nothing forbidden bundled**;
  all 9 advancement definitions and the runtime helper are present, and the
  new test class is excluded. `git diff --check` passed.

Built artifact: `mod/build/libs/mod-0.1.0-alpha.32.jar`.

The new tests use unique fixtures starting at `(69900,190,69900)`, with 96-block
spacing. Setup and cleanup release forced chunks and restore synthetic player
and advancement caches on failures. The fixtures clear their area before use
and on exit. Existing tests were not edited.

### Failure investigation and negative control

The initial run passed all 217 existing tests but failed seven new tests. The
new fixture used NeoForge's `FakePlayerAdvancements`, whose award methods are
intentional no-ops. Synthetic human fixtures now use normal vanilla advancement
storage and clean up its listeners/cache. The criticality fixture also now
synchronizes its seeded rod positions with the real rod network before the
normal server tick. Award assertions were retained.

Negative-control command:

```powershell
py -3.13 C:\Users\14238\Downloads\BWR_Codex_Project_Brief\agent_work\l3\negative-fe-hook.py
```

This temporarily removes only the new FE-delivery observation call, runs the
unchanged GameTests, and restores the exact source bytes in `finally`.
Result: **226 passed, exactly 1 failed**, the new
`actualEnergyDeliveryAttributesOwnerAndSurvivesOfflineReload` test, with
`pending real export was lost or awarded to the wrong player`. The GameTest
server and Gradle returned nonzero as required. Restored source SHA-256:
`7f616fa726700a9051f5e5b92e9b97f2ca74d4f626ad4d8bf916f72076099ca1`.

Logs are in
`C:\Users\14238\Downloads\BWR_Codex_Project_Brief\agent_work\l3\`:
`gametest-first.log`, `gametest-fixed.log`, `negative-fe-hook.log`, and
`gametest-final.log`.

## Scope and limits

The brief names milestones without specifying achievement thresholds or
attribution. The documented scram hold, power fractions, control-room counts,
and ownership policy are gameplay definitions. They are not physical operating
limits. "Power to the Grid" uses actual existing FE delivery; Phase M's grid
tie, breaker synchronization, diesel, and demand model remain unimplemented.

No placeholders were added and no existing test is considered wrong. The
289-test standalone core acceptance suite, client visual checks, and separate
optional-dependency/peripheral runs were not rerun for this mod-only change.
Server resource decoding and real advancement progress were verified; the
advancement tab's client appearance has not been visually inspected.

L4, L5, and Phase M remain pending their subsequent work/review steps.
Pre-existing untracked `.claude/` and
`PROJECT-CHANGELOG-START-TO-ALPHA29.txt` were left untouched.
