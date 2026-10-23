# Status and chat translations

Phase L2 sends player-facing reports as Minecraft translation components. The
English catalog is `mod/src/main/resources/assets/bwr/lang/en_us.json`. Resource
packs can supply the same keys in another language file. No additional complete
language pack is included in this phase.

## Writing a report

Use `BwrText.tr(key, englishFallback, arguments...)`. Keep measurements and
coordinates in arguments. Use `%s` placeholders, `%%` for a percent sign, and
`%2$s` / `%1$s` when a translation needs a different argument order. Format
measurement precision separately; Minecraft translations do not accept `%f` or
`%d`. Nested state descriptions should also be components. Player labels,
program text, fuel identifiers and external diagnostic details are data.

Update the English catalog whenever a fallback changes. Run:

```powershell
py tools-audit-translations.py
py tools-audit-assets.py
.\gradlew.bat :mod:runTurbineGameTest :mod:checkNoProtectionLogic --console=plain
```

The translation audit checks static keys, argument counts, percent escaping and
fallback/catalog agreement. It includes negative controls. The GameTests also
exercise the actual containment chat path, nested component serialization,
argument reordering in a test language, reactor menu snapshots and panel chat
and wiring-result keys.

## Compatibility and scope

- Player reports use `statusComponents()`. Existing `statusLines()` diagnostics
  and computer consumers use an explicit English rendering, independent of the
  receiving player's language.
- Structure validation preserves translated reasons and coordinates. The
  String overloads remain available for external diagnostics and test callers.
- The unformed-reactor menu transports components and resolves them on the
  client. GUI network protocol **18** identifies the changed snapshot format;
  server and client must use the same mod version.
- Coverage includes containment, reactor and suppression reports, nozzles,
  control drives, fuel fabrication and fuel status, accident notices, refuelling
  notices, hardware tooltips, and panel chat/wiring feedback. Panel rendering
  classes and the controller's visual layout are outside this change.
- The panel's existing key-switch, footprint and linker notices are covered by
  the translation tests. Wiring refusals now report a translated reason through
  the common/server packet handler. Component labels and display contents remain
  player-authored text.

The synthetic Spanish strings in `PhaseLTranslationsTests` test language
selection and reordered arguments; they are not a shipped Spanish translation.

## The in-game guide

The Operator's Guide is data, not code: its pages are JSON under `assets/bwr/guide/<language>/`, and a translation is a copy of
that folder (see [GUIDE.md](GUIDE.md)). Its buttons and tooltips are `gui.bwr.guide.*` lang keys, which this audit covers with the
rest. `python tools-audit-guide.py` checks every chapter, page, icon id, image and referenced document, and that a translated chapter
has the same pages as English.
