# L3 review fixes

Branch: `containment-structure`. Base: `7cf56f69858bb28469044e13ceb9bb8847f46a70`
(the accepted L3 work plus the panel GPU cache and slope merge).
Stop point: **L3-fix1/2 and the requested client check, before L4**.
All work is local; nothing was pulled or pushed.

Local commits:

- L3-fix1: `5021034d603bc08fd21baba4589c8f8cf908ce7c`.
- L3-fix2: `7799498eef9c458e0ef0c90488157f57a23e9b9a`.
- The client-check harness and this report form the third commit.

## Built

L3-fix1 removes control-room advancement evaluation from periodic live sampling
and redstone-neighbour sampling. Component placement/removal, notified
configuration/wiring changes, owner claims/interactions, and group formation/join
request the check instead. Nested reader refreshes are batched by group. One
qualifying component traversal credits every owned member, including different
owners in the same group. A package-private counter measures actual group
component traversals. `ADVANCEMENTS.md` explains the event-driven semantics,
including a source becoming available after the last edit.

Two GameTests cover six input-only live gauges across eight actual sampler
intervals and a shared group crediting two owners with exactly one evaluation.
The existing connected-hardware/live-signal test keeps all its positive and
negative assertions; its trigger changes from periodic sampling to a notified
resample as explicitly requested by the review.

L3-fix2 adds a fully fueled cold-core regression. It verifies the formed core has
not advanced, closes the vessel head, checks every rod demand and position at
notch zero, and runs 41 ordinary reactor block-entity ticks. It checks the first
post-formation tick and every subsequent tick for no First Criticality award.
There is no direct observer call, hot initializer, reactivity override, or scram.
The existing nonnegative-reactivity predicate passes; no physics or predicate
change was needed.

The new fixtures have unique origins (slots 9–11 of the established L3 layout),
clear their blocks for persistent-world reruns, and release forced test chunks
and synthetic-player state. The asynchronous gauge fixture also cleans up on
framework timeout/failure and rerun callbacks. No production code force-loads
chunks, adds a ticker, commands equipment, or changes protection behavior.
The panel client package, production GUI classes, and core physics are untouched.

## Verification

Commands ran from `C:\Users\14238\Downloads\bwr-mod-handoff\bwr-mod` with
`JAVA_HOME=C:\Users\14238\AppData\Roaming\PrismLauncher\java\java-runtime-delta`.

```powershell
.\gradlew.bat :mod:runTurbineGameTest :mod:build :mod:checkNoProtectionLogic --offline --console=plain
.\gradlew.bat :mod:runAdvancementClientCheck --offline --console=plain
py -3.13 tools-audit-advancements.py --self-test
py -3.13 tools-audit-assets.py
py -3.13 tools-audit-translations.py
py -3.13 tools-check-jar.py
git diff --check
```

- Initial complete server run: **234/234 passed**, up from the merged baseline's
  231. GameTest time: 1.321 minutes; full Gradle command: 2m 8s.
- After both negative controls and exact source restoration, the persistent-world
  rerun passed **234/234** again. GameTest time: 1.308 minutes; full Gradle command:
  1m 59s. The build and protection tasks were up to date against the restored code.
- Six live gauges, eight measured sampler intervals: **29 → 29 group evaluations**.
- Both panel owners earned the milestone; one notified wiring edit increased
  the group traversal count by exactly one.
- Cold core: **41 ordinary BE ticks**, every rod at notch zero, reactivity
  `-0.1025297696959452`, neutron fraction `1.0056079191776197E-12`, no award.
- Mod build passed. Protection check: **562 files, no violations**.
- Advancement audit: **9 definitions, 0 errors**; all 9 deliberate corruptions
  rejected. Asset audit: **2,897 JSON files, 0 problems**. Translation audit:
  **400 static calls, 750 English keys, 0 problems**.
- JAR audit: **770 class files**, no forbidden dependency classes, and no
  `dev.bwr.mod.devtest` entries. License/attribution checks passed for both JARs.
- `git diff --check` passed; only the intended review files were staged.

Built artifact: `mod/build/libs/mod-0.1.0-alpha.32.jar`.

### Negative controls

```powershell
py -3.13 C:\Users\14238\Downloads\BWR_Codex_Project_Brief\agent_work\l3_review\negative-controls.py
```

This temporarily reintroduces `panelChanged(this)` at the end of `sampleLive`
and removes only `reactivity >= 0.0` from the criticality predicate. No test is
modified. Result: **232 passed, exactly 2 failed**, the two intended regressions:

- Gauge-wall sampling failed on its first measured interval with
  `live sampling reevaluated a group which cannot qualify; remaining cycles=8`.
- Cold fueled core failed on its first post-formation tick with
  `first tick after forming awarded criticality`, reactivity
  `-0.10252976967927473`, and neutron fraction `9.859485700401595E-13`.

Gradle returned exit code 1 as required. The script restored exact source bytes
in `finally`; restored SHA-256 values:

- `PanelBlockEntity.java`: `41f82b7a1f98af56dd401030e78e1aab49d65f2335fcb93370e8f0e1f63d557f`
- `PlantMilestones.java`: `dbc89fba8947ba43eb3c508f4b9dc6d4e39c068947d56e7965e3d3d4b270b269`

Logs and the restoration manifest are under
`C:\Users\14238\Downloads\BWR_Codex_Project_Brief\agent_work\l3_review\`:
`gametest-first.log`, `negative-controls.log`, `gametest-final.log`,
`client.log`, and `negative-restored-sha256.json`.

## Client appearance

Added an opt-in `runAdvancementClientCheck` Gradle run and a devtest-only fixture.
It creates a disposable flat world, grants selected milestones through the
integrated server, selects the BWR tab on the native advancements screen, checks
hidden-node visibility and resolved text/icons, and captures five stages. The
fixture uses native rendering with deterministic hover positions and scrolling;
it is excluded from the player JAR.

The client run passed in 1m 34s. All five screenshots were opened and visually
inspected at 1280×900, GUI scale 2:

| Capture | Observed result |
|---|---|
| `01-hidden-accidents.png` | BWR tab, connected tree and rendered item icons; both accident entries absent. |
| `02-power-title.png` | “Power to the Grid” title and description render correctly, with readable wrapping. |
| `03-lower-head-revealed.png` | Lower-head icon and “The Vessel Gives Way” tooltip appear after granting it; melt-through remains hidden. |
| `04-complete-tree-top.png` | Completed tree includes both accident icons and connected branches. |
| `05-melt-through-title.png` | “Nothing Left to Hold It” tooltip and description render correctly after granting melt-through. |

The tree is taller than the vanilla viewport; its edge nodes clip at the window
boundary and are accessible by native scrolling. Captures at different offsets
show the icons and tooltips correctly. No texture placeholders or unresolved
translation keys were observed. Captures are local, ignored runtime artifacts:
`mod/run/advancementClientCheck/advancement-check/`.

## Scope and next stop

No existing assertion was weakened and no existing test is reported as wrong.
No placeholders were added. The standalone 289-test core acceptance suite and
separate optional-dependency/peripheral runs were not rerun for this mod-only
change. Client appearance, server behavior, resource audits, and release JAR
packaging were checked as described above.

This report supersedes the original `PHASE-L3-REVIEW.md` statements about the
panel-sampling hook and the missing client visual inspection. That earlier
report remains a historical record of the accepted L3 commit.

L4 sounds is the next work item, with its own stop report, followed by L5 Lua
examples. The updated `ASTRA-TODO-4.txt` applies afterward: Phase M is the small
FE-based grid tie/load-following scope, with a spec review first and a 5–7 commit
budget. No L4, L5, or Phase M implementation is included here.

Pre-existing untracked `.claude/` and
`PROJECT-CHANGELOG-START-TO-ALPHA29.txt` were preserved.
