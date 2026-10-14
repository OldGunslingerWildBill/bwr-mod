# L4 sound scaffolding and advancement polish

Branch: `containment-structure`. Base: `d8cd914b9c3fedc55966a6c50f2ae189fe75a029`.
Stop point: **L4, before L5 Lua examples**. Nothing was pulled or pushed.

## Local commits

- Advancement polish: `7ceb185518679df4cfe82a878ddc992f255af66a`
  (`Fit the advancement tree in the vanilla viewport with distinct icons`).
- L4 sounds and this report: the following commit,
  `Register silent sound placeholders with reproducible generation and credits`.
  Its hash is included in the accompanying stop report in chat.

## Built

The advancement tree now has three rows across four columns. All nine nodes use
distinct existing item icons, including the root. The two identical-looking
controller icons and repeated fuel/corium icons are replaced with distinguishable
silhouettes. Parent links arrange presentation only: earning conditions, saved
advancement IDs, the reactor-controller inventory trigger, and hidden accident
behavior are unchanged. `ADVANCEMENTS.md` describes the new presentation.

The native client check now asserts that the complete server-computed tree,
including hidden nodes, fits the vanilla viewport at every capture stage. It
also checks client widget bounds and distinct icons. It never pans or scrolls.
The existing visibility, title, and description checks remain in place.

L4 adds **16 variable-range sound events**, each with a separate mono Ogg Vorbis
file and English subtitle key. They cover the event names listed for the later
sound pack: annunciator/acknowledgement, relay/contactor/control switch, breaker
close/trip, pump, turbine, steam/SRV, MSIV, scram buzzer, EDG start/run, and Geiger
clicks. `BwrMod` registers the new deferred sound registry on the mod bus.

Every file is deliberate silence generated from 12,000 zero PCM samples at
48 kHz (0.25 seconds), encoded using the installed Blender 5.2.2 LTS Audaspace
writer. The generator makes each Ogg stream serial deterministic and recalculates
its checksums. It reproduces all sixteen files byte for byte with the pinned
encoder build. No recording, downloaded sample, or third-party audio is included.

`SOUND-CREDITS.md` records provenance, the existing project license, all event/file
and subtitle mappings, exact authoring-tool versions, and regeneration commands.
It is also packaged at `META-INF/SOUND-CREDITS.md` in the mod JAR. The generator
and encoder are authoring tools, not runtime dependencies or bundled binaries.

This phase registers events and assets only. It adds no playback hooks, looping
engine, tickers, plant commands, automatic alarms, trips, or starts. The later
Phase Q owns audible synthesis and playback driven by plant state. Core physics,
the panel client package, and production screen/menu classes are untouched.

## Commands and checks

Commands ran from `C:\Users\14238\Downloads\bwr-mod-handoff\bwr-mod`, with
`JAVA_HOME=C:\Users\14238\AppData\Roaming\PrismLauncher\java\java-runtime-delta`.

```powershell
.\gradlew.bat :mod:runAdvancementClientCheck --offline --console=plain
.\gradlew.bat :mod:runTurbineGameTest :mod:runSoundClientCheck :mod:build :mod:checkNoProtectionLogic --offline --console=plain
py -3.13 tools-audit-advancements.py --self-test
py -3.13 tools-audit-sounds.py --self-test
py -3.13 tools-audit-assets.py
py -3.13 tools-audit-translations.py
py -3.13 tools-generate-sound-placeholders.py --blender 'C:\Program Files (x86)\Steam\steamapps\common\Blender\blender.exe' --check
py -3.13 tools-check-jar.py
py -3.13 C:\Users\14238\Downloads\BWR_Codex_Project_Brief\agent_work\l4\check_jar_payload.py
git diff --check
```

- Advancement client: **5/5 stages passed**, full tree **112×81** within the
  vanilla **234×113** viewport, without scrolling. Gradle passed in 2m 1s.
- Full server suite: **235/235 GameTests passed**, including the new registry
  integration test; GameTest time 1.304 minutes. The registry test places no
  blocks, holds no forced chunks, and requires no persistent fixture cleanup.
- After the temporary faults and exact source restoration, the persistent-world
  server suite passed **235/235 again**, also in 1.304 minutes. The final combined
  server/advancement/build command passed in **3m 37s**. Its advancement client
  passed **5/5 stages** again at **112×81**, and all five final captures were
  opened and visually inspected again.
- Actual sound client: **16/16 events resolved** through Minecraft's sound and
  resource managers, including their subtitle translations. The pinned
  `JOrbisAudioStream` decoded **192,000 float samples**, all exactly zero, mono
  at 48 kHz, exactly 12,000 samples per file. It created no world and requested
  no playback. Results: `mod/run/soundClientCheck/sound-check/results.json`.
- Mod build passed; protection check: **565 Java files, no violations**.
  The combined server/client/build Gradle command passed in **3m 17s**.
- Advancement audit: **9 definitions, 0 errors**; all **10** negative corruptions
  detected, including duplicate icons. The original resource set also fails
  the strengthened duplicate-icon check as expected.
- Sound audit: **16 definitions, 16 files, 0 errors**; all **14** negative cases
  detected, including missing resources, wrong format, bad checksums, malformed
  headers, wrong subtitle, and missing attribution.
- Asset audit: **2,898 JSON files, 0 problems**. Translation audit:
  **400 static calls, 766 English keys, 0 problems**.
- Generator `--check`: **16/16 byte-identical** files, 3,981 bytes each. No
  checked-in asset was overwritten by the verification command.
- JAR audit: **771 class files**, including nested project JARs; **0** bundled
  ComputerCraft/Mekanism implementation classes and **0** devtest classes.
  Both project JARs passed the license and attribution checks.
- JAR payload check: sound registry class present; all **16** events, Ogg files,
  and subtitles, all **9** advancement definitions, and packaged sound credits
  verified against source. Resource and credit bytes match exactly.
  Artifact: `mod/build/libs/mod-0.1.0-alpha.32.jar`.
- `git diff --check`: passed.

### Negative controls and restoration

```powershell
py -3.13 C:\Users\14238\Downloads\BWR_Codex_Project_Brief\agent_work\l4\negative-controls.py
py -3.13 C:\Users\14238\Downloads\BWR_Codex_Project_Brief\agent_work\l4\negative-controls.py --only tall-tree
.\gradlew.bat :mod:runTurbineGameTest :mod:runAdvancementClientCheck :mod:build :mod:checkNoProtectionLogic --offline --console=plain
```

Removing only the new sound-registry bus registration caused **exactly one
GameTest failure**, `everyPlaceholderSoundIsRegisteredWithVariableRange`, with
`missing sound registry entry bwr:annunciator_horn`. The other **234 tests
passed**. Gradle correctly returned nonzero.

The first layout probe restored old parent links while retaining the new icons.
Vanilla packed that graph into **112×108** on this run, so it correctly passed
the viewport guard. It was not a reliable overflow fixture. No test assertion
was changed. The corrected corruption makes seven sibling branches while keeping
the lower-head → melt-through link, unique icons, and earning conditions.
The unchanged client check rejected its **84×189** tree at stage 0 with
`full tree at stage 0 exceeds vanilla viewport height: [0, 0, 84, 189]`.
Gradle returned nonzero as required.

Both temporary-fault runs restore the exact original bytes in `finally`, with
concurrent-edit checks. The final restoration covered the entrypoint and nine
advancement JSONs. Source hashes and original bytes are preserved in the local
review staging directory; no test was edited for either negative control.

Evidence under
`C:\Users\14238\Downloads\BWR_Codex_Project_Brief\agent_work\l4\`:
`advancement-client.log`, `positive-first.log`, `positive-final.log`,
`negative-sound-registration.log`, `old-layout-probe.log`,
`negative-tall-tree.log`, `negative-restored-sha256.json`, and
`generator-check.log`.

## Inspected client captures

All five captures from both positive runs were opened and visually inspected at
1280×900, GUI scale 2. The retained copies are from the final restored run.
All nine nodes fit in the window with margin, the item icons have distinct
appearances, no icon is clipped, and the native tooltips are readable. Accident
entries stay hidden until their respective awards. The complete tree is visible
at once with no scrolling.

Copies retained under the ignored `tmp/l4/advancements/` directory:

- `01-hidden-accidents.png`: initial earned milestones, both accidents hidden.
- `02-power-title.png`: Power to the Grid title and wrapped description.
- `03-lower-head-revealed.png`: vessel icon and lower-head tooltip; melt-through hidden.
- `04-complete-tree.png`: all nine distinct icons and the whole tree visible.
- `05-melt-through-title.png`: corium icon and melt-through tooltip.

The source captures are in
`mod/run/advancementClientCheck/advancement-check/`.

## Scope, placeholders, and limits

The sixteen sound files are the intentional silent placeholders requested for
L4. No other placeholders were introduced. No gameplay or physics limits were
chosen. There are no spec deviations and no existing GameTest assertions were
weakened or identified as wrong. The advancement client check was strengthened
for the explicitly requested layout/icon polish.

The standalone core acceptance suite and separate optional-dependency/peripheral
runs were not rerun for this registry/resource change. Audible sound design,
positional playback, seamless looping, pitch/volume behavior, and lifecycle
cleanup belong to Phase Q and were not implemented or evaluated here.

L5 example Lua programs remain the next item, with their own stop report.
Pre-existing untracked `.claude/` and
`PROJECT-CHANGELOG-START-TO-ALPHA29.txt` were preserved.
