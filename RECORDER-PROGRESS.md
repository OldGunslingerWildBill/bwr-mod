# Paperless recorder (Yokogawa DX1000 / DX2000): progress

Branch `recorder-code`, worktree `bwr-recorder-code`. Spec: `../DX1000-RECORDER-SPEC.md` (sections 1-5 DX1000, 5b DX2000, 7 model contract).
Baseline before this work: `:mod:runTurbineGameTest` 292/292.

## Done

1. `RecorderAlarmLogic` (pure) and `RecorderAlarmLogicTests` (7 GameTests, no world). 299/299.

## Next

2. Component type + data (`paperless_recorder`), ports, settings, dot ring, patches, alarm log, save/load.
3. Client screen renderer (320x240 / 640x480 dynamic texture).
4. Face keys, menu, DX2000 cover.
5. Setup screen, bind screen paging.
5b. DX2000 four-panel and circular screens.
Final: runLuaExamplesCheck, runTurbineGameTest x3, -PbwrNoCC, audits.
