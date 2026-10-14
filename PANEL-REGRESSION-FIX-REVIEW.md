# Panel regression repair review - 2026-10-14

Branch: `containment-structure`; reviewed base `a663d86d4679932a4ed4b15834f318b968c29f3d`.
This repair addresses the two outstanding panel failures recorded in PHASE-L5-REVIEW.md and the causally related panel fixture readiness/cleanup issues. Phase M and unrelated features were not started. Nothing was pushed; the focused repair commit is recorded in the handoff.

## Diagnosis and repair

Both failures were fixture defects, with concrete evidence rather than an assumption based on a successful retry:

- `unloadedOwnerRepairsWhenItsChunkLoads` compared a level-wide deferral count with an earlier baseline while other tests ran concurrently. The original diagnostic log `original-evidence/2026-10-14-32.log`, line 91, records `now=1 baseline=0`, with the remaining waiter at `(53152,110,52900)`, belonging to slot 4 (`deferredVerificationRunsWhenCoveredChunkLoads`), while the failing test owns slot 3/chunks 3318 and 3317. Logs `-33` and `-36` repeat this evidence. Deferral assertions now count only block entities in their own fixture; the expected +1 and return-to-baseline assertions remain.
- A forced, block-readable chunk is not necessarily queryable through the entity lookup yet. The original diagnostic log `-32`, line 342, records an accepted item spawn with `drops=0`; after one tick it remains alive but absent from both the area query and UUID lookup; after two ticks both lookups find it. Fixed waits did not establish the required readiness condition. A test-only `EntityLookupProbe` now spawns an inert bedrock item and waits until the actual area query can find that identity in each relevant chunk, then discards it before preparing the fixture. The owner reload test resets readiness and observes it again after loading. Real item-drop counts remain exact and are not used as readiness conditions.
- `PanelFoundationTests` had the same fixed-delay assumption, independently observed in the prior repair branch as a wrench-removal item-count failure. It uses the shared readiness probe too.

The core scoped-count/probe work was reviewed and reused from local commits `eef3d1902aa5c6b6c6d5924a0de0dc0fc82e0dce` and `09682b754fa8bb522c1a39eac17793e7a286bc62`, authored by Claude with Claude Sonnet 5.5 co-author attribution. Only their four relevant file diffs were applied; their branch and other worktree were not modified or merged. Original raw diagnostics were copied read-only from that worktree, with provenance in `original-evidence/index.json`.

Review found an additional cleanup defect: readiness could reach the GameTest timeout before the helper's 200-poll cleanup branch. Both stages now register idempotent pass/fail/rerun listeners and release forced chunks in `finally`. Sequence poll exhaustion calls `testInfo.fail` so it is terminal; merely throwing `helper.fail`/`assertTrue` is retryable inside `thenWaitUntil` and could previously retry after clearing the stage.

Production panel repair, item spawning, chunk availability events, physics, model content, and Lua examples are unchanged. `PanelConsistency` only gains read-only scoped count and diagnostic methods. No test behavior assertion was removed or relaxed, no existing timeout increased, no test skipped, and no world deleted.

## Independent validation

All commands used the actual repository root and Java 21:

```powershell
$env:JAVA_HOME = 'C:/Users/14238/AppData/Roaming/PrismLauncher/java/java-runtime-delta'
.\gradlew.bat :mod:runTurbineGameTest --offline --console=plain
.\gradlew.bat :mod:runLuaExamplesCheck --offline --console=plain
.\gradlew.bat :mod:build :mod:checkNoProtectionLogic --offline --console=plain
py -3.13 tools-check-jar.py
git diff --check
```

The initial review run of the reused patch passed **269/269** current required tests (`initial-reused-fixes.log`). The suite has grown since L5's 235-test runs.

A reversible negative control then checked two independent failure paths in one full run (`negative_controls.py`, `negative-drop-and-timeout.log`):

1. Suppress only the orphan fixture owner's component drop at `(53024,110,52900)`. The unchanged assertion fails with exactly `only the owner break dropped an item, got 0`. Readiness has not hidden a real missing drop.
2. Keep the foundation slot 0 probe from reporting readiness after spawning its items. The unchanged 200-tick runner timeout fires. The failure listener logs all three probe entities removed and `remainingForced=0` across its fixture's entire forced-chunk rectangle.

Exactly these two tests failed, Gradle exited 1, and the helper treated the expected failures/cleanup as success. All temporarily modified source bytes were restored in `finally`; `negative-restoration.json` records hashes and restoration checks. There is no negative-control switch left in the mod.

After restoration, the final source passed the full suite **269/269 twice** on the same persistent world: `final-suite-1.log` (2m 4s) and `final-suite-2.log` (2m 1s). These are clean final runs, separate from the intentionally failing negative control. No intervening source edit or world reset occurred.

The real CC Lua check passed **22/22 scenarios across all five actual scripts** in 1m 48s (`final-lua.log`, `final-lua-results.json`), recording source hashes and machine/pixel readbacks. Cleanup released 132 chunks, drained 100 normal ticks with 0 pending tasks and no light work, and the server exited normally.

Build and protection checks passed in 42s (`final-build.log`): **592 mod source files**, no protection logic. The JAR audit passed (`final-jar.log`): **798 class files**, **2 project JARs** with license and attribution, **0 bundled CC classes**, **0 bundled Mekanism classes**, **0 devtest entries**. `git diff --check` passed. An independent read-only review found no remaining actionable issue after the cleanup correction.

**Result:** the two outstanding L5 panel regression failures are resolved. No remaining issue was found within this repair scope. The general limits of automated GameTests and the existing Lua scenarios remain; no new client visual or whole-plant acceptance claim is made.

## Changed files and evidence

- `PanelDesyncTests.java`: actual entity readiness, per-fixture deferral assertions, timeout-safe cleanup.
- `PanelFoundationTests.java`: shared actual entity readiness and timeout-safe cleanup.
- `EntityLookupProbe.java`: test-only probe, removed by identity after observation and on cleanup.
- `PanelConsistency.java`: read-only scoped counts and failure diagnostics.
- This report and a dated resolution pointer in `PHASE-L5-REVIEW.md`.

All logs and helpers are preserved under:
`C:/Users/14238/Downloads/BWR_Codex_Project_Brief/agent_work/astra-panel-fixes/`.

The existing `.claude/` and `PROJECT-CHANGELOG-START-TO-ALPHA29.txt` are preserved. The earlier L5 failure records remain historical evidence; this report does not rewrite those runs as passes. Existing L5 runtime coverage boundaries remain unchanged.
