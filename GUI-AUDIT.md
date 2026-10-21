# GUI audit: the machine screens as they were (alpha.44, commit 815ffdb)

Scope: every screen in `mod/src/main/java/dev/bwr/mod/gui/client/` (14 menu types and 14 screen classes, one of them the
legacy recirculation pump screen), their menus in `gui/` and the networking in `gui/net`. The panel config screens
(`panel/client/*Screen`) are not audited here; they only receive the shared style primitives.

## How it was measured

`GuiScreenCheck` (`mod/src/main/java/dev/bwr/mod/devtest/GuiScreenCheck.java`, run with
`:mod:runTurbineModelCheck -PbwrGuiScreenCheck`) opens each screen in 42 menu states with fabricated menu data (the layout
of a screen is a function of its menu's public fields: no machine or server is needed), at GUI scale 2 and GUI scale 4 in a
1920 x 1080 window, saves a shot of each and checks that every widget, panel and slot lies inside the window. It also lays
every screen out once at 320 x 240 and 427 x 240, the smallest GUI sizes the game ever gives a screen (the game picks the
largest scale that leaves at least 320 x 240, so 320 x 240 is the floor to design for). Shots of the old screens:
`scratch-gui-shots/before/scale2` and `scale4`; the violation list: `scratch-gui-shots/before/violations.txt`.

Result for the old screens: **39 violations**.

* At GUI scale 4 (480 x 266 scaled): Terry turbine (panel 384 x 300, its last button at y 257 to 277) and suppression
  tank (310 x 274) run off the top and bottom of the window.
* At 320 x 240: Terry (384 wide: buttons at x -20), condenser (330 wide), cooling (340), condensate tank (340), service
  (330) and suppression tank (274 high) do not fit. Only the reactor panel, the refuelling floor, the pump panel, the
  fuel fabricator, the legacy recirculation pump, the power module, the valve and the RHR heat exchanger fit.

## Faults common to all screens

1. **No design system.** `BwrScreen` has seven colour constants and a `bar()` and `readout()` helper; ten of the fourteen
   screens paint their own background with copies of the same hex literals (`0xFF171D26`, `0xFF293A4E`,
   `0xFF58708A`; the suppression screen has a slightly different set), and four more use PNG backgrounds. There is no spacing scale:
   rows sit at hand-typed y values with pitches of 9, 10, 12, 14, 16, 17, 18 and 20 px, so rhythm differs from screen to
   screen.
2. **Contrast.** Labels use `TEXT_DIM` 0xFF7C848F, which is 4.48:1 on the dark panel but 3.94:1 on the readout wells
   (0xFF202832) and 3.47:1 on the texture panels: below the 4.5:1 minimum. The "Hover here" hint, the fuel footers and all
   explanatory captions are drawn in it.
3. **Colour means different things.** Blue (`ACCENT`) is used both for "this is water" (cold inventory, spray flow, steam
   received) and for "selected"; amber is used for a status caution and also for the Terry steam-circuit heading; green
   marks both a running pump and a plain good value. A reader cannot tell a category from an alarm.
4. **Colour is the only signal.** Caution and alarm values (pressure over 1250 psig, level, clad temperature, power
   over 105 %, charged accumulators) change colour only: no icon, no word, no tooltip saying what the figure was compared
   with.
5. **Truncation without a signal.** Long strings are cut with `plainSubstrByWidth` or `substring` mid-word and no
   ellipsis ("Suction piped, discharge connected to the rea", "no cross-tie fa"), or are not cut at all and run past the
   panel edge (unformed reactor status lines, the legacy recirculation pump's footer, the fabricator's feed line).
   Nothing offers the full text.
6. **Numbers are formatted inconsistently.** "1612.4 kg/s" next to "28,000 kg/s", "2204.6 MW" next to "1,500 MW",
   units are glued to the value so decimals and units do not line up in a column.
7. **Controls without explanations.** Only the refuelling buttons have tooltips. Edit boxes, the Apply/Start/Stop/Control
   buttons, the sliders, the tabs and the lattice map have none, and nothing says why a button is greyed out (a pump under
   computer control greys four controls and the screen never says so on the controls themselves).
8. **Tab and key handling is ad hoc.** Four screens swallow every key while an edit box is focused except Escape so that
   typing "e" does not close the inventory screen, and each re-implements it; Tab does not leave the edit box. Sliders
   move one pixel's worth per arrow press (0.36 % on a 280 px slider) and need Enter first. The lattice map cannot be used
   without a mouse at all.
9. **Edit-box state is invisible.** An out-of-range entry only greys Apply; the field itself looks the same.
10. **Fixed sizes.** Screens are 240 to 384 px wide and up to 300 px high whatever the window: nothing adapts, nothing
    scrolls.
11. **No inventory chrome of its own.** The fabricator and refuelling floor rely on baked PNG backgrounds with the slot
    wells painted in, so the slots only line up with the background by luck; the fabricator's title is drawn in vanilla's
    dark grey (`4210752`) on its dark panel and is invisible.
12. **All text is hard-coded English** (`Component.literal` and string literals), so none of it is translatable. Not
    changed by this work (see the final report), but listed.
13. **Dead screen.** `RecirculationPumpScreen` is registered but no code opens `RecirculationPumpMenu` (the pump panel
    `PumpControlScreen` replaced it). It is restyled anyway because it is a registered screen with a registered packet
    protocol.

## Per screen

**`ReactorPanelScreen`** (256 x 230, texture `reactor_panel.png`). Shows the core map (flux or rod overlay) with three
tabs, 15 plant readouts in a column, two summary lines, two rows of four actuator buttons, an INFO page with planning
estimates and tooltips.
* The readout labels (`PRESS`, `PKCLAD`) run into their values (`PRESS1,008 psig`); labels are in the sub-4.5:1 grey.
* The "SCRAM" indicator is drawn at (10, 18), on top of the FLUX tab.
* Readouts are 9 px apart with no grouping (power, pressure, level, neutron instrumentation, fuel and clad temperatures
  are one undifferentiated list); the INFO page spills its footnote past its card.
* The tab buttons mark the *selected* tab as disabled-looking, so the active tab looks dead and the others look live.
* The unformed state lists status lines that run past the panel edge (up to 78 characters at 6 px is 468 px in a 256 px
  panel).
* The two summary lines and the hover hint are three lines of 10 px text packed into a 28 px strip; the hint ("Hover here:
  fuel, damage and cooling details") is the only way to learn the footer has a tooltip, and it is unreadable grey.
* The core map has no legend: heat colours are explained nowhere. The lattice cannot be focused or operated by keyboard.
* SCRAM and the other actuators are the same grey buttons: nothing marks SCRAM as the dangerous one.

**`RefuellingScreen`** (256 x 230, texture `refuelling.png`, slots at x 48, y 148 and 206). Shows burnup / k-inf map,
core statistics, selection summary and LOAD / UNLOAD / MARK / SWAP.
* The map area is 146 x 84, so a 31-wide core is drawn with 2 px cells (see shot): unreadable without zooming.
* The selection line is clipped to the map width ("Wheel zoom / Drag RMB to pa").
* Disabled actions give no reason (LOAD needs an empty position and a bundle in the inventory).
* The tab-selected state is inverted (as above); "CLEAR" and "FIT" are tabs-sized buttons mixed into the tab row.
* The map's green-to-red burnup scale cannot be read by a red-green colour-blind player and has no legend.
* Inventory slots sit on baked PNG wells.
* The head state is hard-wired to "head off" text (the menu only opens with the head off, so it is correct, but it is a
  literal).

**`PumpControlScreen`** (260 x 234). One screen for RHR, LPCS, HPCS, SLC, feed and recirculation pumps.
* Header line mixes drive and state in one green string; "Waiting for pump..." and "Waiting for pump" appear twice.
* The connection line is cut mid-word. The unit sits in the value string, the label column is dim.
* Under computer control Start, Apply, Suction, Mode and the edit box are greyed with no word saying a computer holds
  the controls (only a footnote at the bottom on some variants).
* Large unused area at the bottom for SLC and feed variants; the `Suction:` footnote duplicates the button label.
* The edit box is vanilla black with white text; no invalid state.

**`TerryTurbineScreen`** (384 x 300). Steam and water circuit cards, port table with coordinates and a hover tooltip,
speed, run, control, water source.
* 384 x 300 does not fit at GUI scale 4 or at the 320 x 240 floor; four of its controls are off screen.
* The port names use four different colours as identifiers (amber, white, blue, teal) and the state column
  ("Linked" / "Dry / shut" / "No route") is colour-only.
* The port tooltip is only reachable by mouse, over a hidden hit region.
* Duplicate speed readout (bar and the value above the circuits).

**`RecirculationPumpScreen`** (176 x 166, texture `recirculation_pump.png`; unreachable). Commanded / actual /
achievable readouts, a slider, a control toggle.
* The speed bar is drawn through the mismatch line; the mismatch line and the footer run past the panel.
* Upper-case labels at 10 px pitch; the slider carries the label and value inside a dark trough with a 1 px handle.

**`CondenserScreen`** (330 x 240). Cold, hot, hotwell, steam, rates, heat rejected and four caption lines.
* 330 wide: not available at the floor. No status lamp for "ready", a text line instead. Cold water is blue for no
  reason. The captions are a sixth of the screen in grey.

**`CondensateTankScreen`** (340 x 234). Water, temperature, geometry, build instructions.
* 340 wide. "0 / 0 kg" and "0 C" for an unbuilt tank (meaningless numbers instead of a state). The bar is the one place a
  fill is shown; a 10 px bar with 4:1 contrast.

**`CoolingScreen`** (340 x 234). Four designs (tower, circulating pump, makeup pump, intake) in one layout.
* 340 wide. Large empty band when the design has fewer rows (intake). The speed controls hide as three separate widgets.
* Uses the legacy edit-box key handling; the Apply button is the only submit affordance (Enter also works).

**`PowerModuleScreen`** (300 x 238). HP, LP and generator variants.
* The status string is cut ("no cross-tie fa") and the last line touches the bottom border (y 228 + 9 in 238).
* Seven grey captions of instructions share the screen with the readouts; the only status colouring is "Electrical
  output" in green regardless of value. "1,500 MW" is a hard-coded rating.

**`TurbineValveScreen`** (300 x 224). Requested/actual opening, bar, flow, set opening, -0.1 / +0.1 / Close / Open.
* "1522.40 kg/s" has no thousands separator; the three rows of control sit at three different gutters.

**`FuelFabricatorScreen`** (176 x 166, texture `fuel_fabricator.png`, output slot at 116, 35). Batch, fissile fraction,
"would make", feed line, progress bars.
* The labels run into the values ("BAT61.3 / 180 kg HM" overprinting), "WOULD MAKE" overprints the product name, which in
  turn runs under the output slot, and the feed line is cut at 44 characters and still overflows the panel.
* The title is invisible (dark on dark). The bars are 6 and 8 px with percentages and FE written in dim grey beside them.

**`RhrHeatExchangerScreen`** (320 x 218). Flows, heat, inventories, temperatures.
* Two caption lines carrying the colour key ("red IN / amber OUT") are the only explanation of the port colours; the
  cooling-water inlet row is mixed into the captions.

**`SuppressionPoolScreen`** (310 x 274, texture `suppression_pool.png`). Eleven readouts, a bar, fill-mode buttons or an
RHR duty slider.
* 274 high: runs off the window at GUI scale 4 and at the floor. The 14 px row pitch with eleven rows leaves no room.
* "Inlet mode" line sits between rows 11 and 12 out of order (y 200 before y 173 and 187).
* Slider and buttons share a position and are toggled by visibility.
* The unformed state is a mostly empty panel with a live-looking RHR slider drawn at the bottom.

**`ServiceScreen`** (330 x 240). SLC tank, ADS controller, relief valve and water discharge on one screen: up to eight
raw text lines from the server and up to three buttons.
* 330 wide. The lines are the server's English strings at 20 px pitch (no label/value structure, so nothing aligns); the
  state words ("OPEN command", "Closed command") are inside prose. The Open / Close / Division buttons have no tooltip.

**Widgets.**
* `FractionSlider`: vanilla slider sprites; the value is only in the label, keyboard step is one pixel of travel, Enter
  must be pressed first (see 8).
* `LatticeGridWidget`: 2 px minimum cell; selected / marked / hover outlines are white, amber and pale blue (colour
  only); no keyboard operation, no legend, narration is the constant "Core map" whatever the cell.

## Functional gaps that need no new server behaviour

* Reasons for disabled controls (LOAD, UNLOAD, MARK, SWAP; every control under computer control).
* Keyboard operation of the lattice map and of the sliders.
* Status as text and shape as well as colour (above).
* The full text of anything cut.
* A visible "who holds control" state on every pump panel.

---

# What replaced it

## The design system (`gui/client/style`)

**Tokens** (`BwrStyle`, pure Java, no Minecraft type, so a GameTest can read the numbers).
* Surfaces: panel `#12161C`, card `#1A2028`, raised control `#252D38` (hover `#2E3844`, pressed `#161B22`), well `#0C0F13`, title bar
  `#1B2D42`; borders `#3A4552` and `#66768A`; focus ring white.
* Text: primary `#EDF1F5`, secondary `#AAB5C2`, disabled `#939EAB`, accent `#80C4FF`; status colours NORMAL `#62D690`, CAUTION
  `#F2B93B`, ALARM `#FF7B7B`, INFO `#6FBAFF`, OFF (secondary). Buttons: primary `#1F63A8`, danger `#A33030`, white text on both.
* Contrast, measured by `BwrStyle.textPairs()` (60 pairs) and enforced at 4.5:1 by `GuiStyleTokenTests`: primary text 12.2 to
  16.9, secondary 6.7 to 9.2, disabled 5.1 to 7.1, accent 7.5 to 10.3, NORMAL 7.6 to 10.6, CAUTION 7.8 to 10.8, ALARM 5.5 to 7.7,
  INFO 6.7 to 9.3 on every surface; white on the primary button 6.2, on the danger button 6.9. Graphic parts (focus ring, borders,
  status icons, meter fill) are held to 3:1.
* Spacing: a 4 px scale, `SP1`..`SP5` = 4, 8, 12, 16, 24; control height 20, title bar 20, tab 16, meter 8, row 12 (dense 10).
* Type: vanilla font at whole-number scales only (fractional sizes blur the bitmap font at GUI scale 1 to 3): TITLE (bold), HEADER
  (upper case, accent), BODY, CAPTION (secondary) and DISPLAY (2x).
* Status: each status has a colour, a silhouette (circle, triangle, diamond, square, ring) and a word, so colour is never the only
  signal.

**Primitives.** Drawing: panel, card, well, rule, slot well, focus ring, status icon, typed text with truncation (`Ui`). Layout
elements (`Layout`, each a `UiElement`): column, split (weights or fixed widths, optional stacking on a narrow screen), card
(title, optional status, compact), section header, label-value row (unit column, status, dense), note (wrapped, line limit), line,
status line, bar meter (labelled, quarter ticks), colour legend, row of controls, tabs, one-widget box, scroll area, spacer, title
bar items and chip. Widgets: `BwrButton` (primary, secondary, danger; hover, pressed, focus, disabled), `BwrEditBox` (invalid
state), `BwrTab`, `FractionSlider`, `LatticeGridWidget`. The base screen adds the window, the scroll container, the inventory tray
and slot wells, tooltips and narration.

**Layout rules.** Outer padding 8, card padding 8 (4 compact), gaps 4 or 8; values right-aligned and units in their own column;
no screen does pixel arithmetic, the element tree does; a label or value that does not fit is cut with an ellipsis and the row's
tooltip carries the whole text; a panel is as tall as its content needs (never taller than the window) and at most 400 wide, never
wider than the window; side-by-side cards stack on a narrow window; a body that still does not fit scrolls.

**Accessibility.** Tab and Shift-Tab visit every control in layout order; Enter and Space activate; Escape closes, also from a
focused text field (where typed letters no longer reach the inventory key); Left and Right switch tabs; the slider takes the arrow
keys (1 %, Shift 10 %, Page Up and Down 10 %, Home and End); the core map takes the arrow keys, Enter or Space, plus and minus
and Home, outlines its cursor cell and narrates it; every control has a tooltip, shown on keyboard focus as well, and a disabled
control's tooltip says why; the screen announces its readouts to a screen reader when it opens.

## Outcome

* `GuiScreenCheck` on the old screens: 39 violations. On the new ones: 0, over 49 screen states, GUI scale 2 and 4, 427 x 240 and
  320 x 240, plus the keyboard, tooltip, narration and text-overflow assertions it did not have.
* Fixed outright: every fault in the per-screen list above that was a layout fault (the overprinted fabricator and refuelling rows,
  the SCRAM indicator over the FLUX tab, the legacy pump's bar through its text, the invisible fabricator title, the inverted tab
  states, the cut-off status strings, all six screens that did not fit 320 x 240, the two that left a 1080p window at GUI scale 4).
* Not changed: all text is still English literals (translation of the machine screens is a separate job), and the legacy
  `RecirculationPumpScreen` is still not opened by anything.
