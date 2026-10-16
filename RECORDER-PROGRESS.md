# Paperless recorder (Yokogawa DX1000 / DX2000): progress

Branch `recorder-code`, worktree `bwr-recorder-code`. Spec: `../DX1000-RECORDER-SPEC.md`. Baseline before this work: `:mod:runTurbineGameTest` 292/292.
`recorder-models` (bezel models) is merged.

## Done

1. `RecorderAlarmLogic` + `RecorderAlarmLogicTests` (299).
2. `paperless_recorder` type and `PaperlessRecorderData` (settings, ring, patches, alarms, log, saves), RecorderUi, item, recipe, lang (312).
3. Screen painter (`RecorderPainter`, `RecorderCanvas`), `RecorderLayout`, `RecorderModel`, headless screen tests, `RecorderClientCheck` shots and performance.
4. Face keys (client hit boxes, menu, FUNC strip, DX2000 cover), free message packet (protocol 19).
5. Setup screen (`RecorderSetupScreen`), bind screen port paging, key and setup client checks (317).
5b. DX2000: four-panel and circular screens, cover, style limits (in the steps above).

Footprints (owner's size change): DX1000 12/16/20/24 default 16, DX2000 24/32/40/48 default 32 (24 is a DX1000).

## Next / not done

- CC bulk setup (spec 3.6), optional: not built.
- Final gates: runLuaExamplesCheck, runTurbineGameTest x3, -PbwrNoCC.
