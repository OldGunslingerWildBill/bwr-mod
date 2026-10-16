# Paperless recorder (Yokogawa DX1000 / DX2000): progress

Branch `recorder-code`, worktree `bwr-recorder-code`. Spec: `../DX1000-RECORDER-SPEC.md` (sections 1-5 DX1000, 5b DX2000, 7 model contract).
Baseline before this work: `:mod:runTurbineGameTest` 292/292.

## Done

1. `RecorderAlarmLogic` (pure) and `RecorderAlarmLogicTests` (7 GameTests, no world). 299/299.

2. `paperless_recorder` type + `PaperlessRecorderData` (+ RecorderStyle/Screen/Key/Actions/Ui), item/recipe/lang, ports by channel count, settings as sparse flattened keys, dot ring with min/max, patch sync, alarm log, ACK through FUNC soft key (RecorderUi) and ack pushbutton (AnnunciatorGroups). 312/312. `PanelBlockEntity.hasLiveInputs` + `ComponentType.keepsSampling` keep the clock for constant/CC-only recorders.

## Next

3. Client screen renderer (320x240 / 640x480 dynamic texture).
4. Face keys, menu, DX2000 cover.
5. Setup screen, bind screen paging.
5b. DX2000 four-panel and circular screens.
Final: runLuaExamplesCheck, runTurbineGameTest x3, -PbwrNoCC, audits.
