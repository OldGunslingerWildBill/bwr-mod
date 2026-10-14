# Full Core Display, rod select matrix and SRV switches: spec

Branch `fcd` (worktree `C:\Users\14238\Downloads\bwr-mod-handoff\bwr-fcd`). The project owner asked
for all four pieces below. Work is split into a **models** track (Blender, branch `fcd-models`) and a
**code** track (Java, branch `fcd-code`). They meet at the contract in section 6 and are merged by
Claude. If work stops partway, the status table at the end says where to resume.

Reference photos (owner-supplied, read-only, do NOT commit them):
`C:\Users\14238\AppData\Local\Temp\claude\C--Users-14238-Downloads-bwr-mod-handoff\418cd604-018d-4e92-a0cc-37a0e339a9c4\scratchpad\cr-refs\fcd\`
- `columbia_rod_select_matrix.png`: Columbia Generating Station (BWR-5) benchboard. A round array of
  square illuminated rod-select pushbuttons, one per rod, on the seafoam benchboard, plus black
  strips on both sides with large yellow illuminated pushbuttons under clear guards (rod motion
  controls, RMCS status, rod select power).
- `columbia_fcd.png`, `columbia_fcd_closeup.png`: Columbia vertical-board Full Core Display. A
  black octagonal backplate with a dense grid of square lamp tiles.
- `columbia_srv_switches_1.png`, `_2.png`: SRV controls. 30.5 mm oiltight 3-position selector switches
  (OFF / AUTO / OPEN legend plate above), either a black knob with a pointer or a chrome wing lever,
  and green (smooth dome) and red (ribbed dome) indicating lights with chrome bezels.
- `browns_ferry_fcd.jpg`: Browns Ferry Unit 3 (BWR-4) panel 3-9-5, the digital Full Core Display on a
  beige board.

## 1. Columbia Full Core Display (lamp matrix)

Reading the close-up: **every rod is a 2 x 2 block of square lamp tiles.** Each tile is split into an
upper and a lower legend half that light independently:

| Tile | Upper half | Lower half |
| --- | --- | --- |
| A | rod coordinate, e.g. `18-31` (lights white when the rod is SELECTED) | `DRIFT` (amber) |
| B | `FULL IN` (white) | `FULL OUT` (red) |
| C | `ACCUM` (amber: accumulator trouble) | `SCRAM` (blue) |
| D | blank, black, unlit | |

The blocks are **mirrored by parity**: a rod at an even column has A B over C D. Its neighbour to the
right is mirrored left-right (B A / D C), and the rows mirror top-bottom in pairs. As a result the blank
D tiles of four neighbouring rods meet in a 2 x 2 black cluster. A small **yellow dot** sits at the
centre of each cluster, marking an LPRM string location (photo). Reproduce that exactly: the
component's layout code chooses the mirroring so that blanks cluster around LPRM locations of the
linked core. Where the core has no LPRM at a cluster, there is no dot.

Behind the tiles: a black backplate following the core outline (octagonal stepped outline as in the
photo; draw it as the union of the rod blocks plus a margin of one tile).

## 2. Browns Ferry Full Core Display (digital)

**Each rod module** has:
- a **2-digit LED position readout**, 00 to 48. The colours in the photo: green `00` (full in),
  red `48` (full out), amber for the positions between.
- **beneath it, a small lamp cluster** with ACCUM, SCRAM, DRIFT and FULL IN/FULL OUT windows, plus a
  SELECT lamp. Zoom into the photo to get the arrangement; it is about 2 rows of small windows.

**LPRM modules** sit between rod modules at LPRM string locations: a narrow vertical strip of **4 lamps
(levels A, B, C, D from top to bottom)** with the coordinate label below (e.g. `08-49`). Some strips
also carry a label above, such as `SRM-A` or `IRM-C`. Assign SRM and IRM labels only if the core models
those detectors. If it doesn't, leave them off and say so in the docs.

**Grid lines:** black lines run from the edge labels to the modules: row numbers on the left (03, 07 ...
59) and column numbers along the bottom (02, 06 ... 58), as in the photo. The panel background is the
player's panel colour. The component itself draws no backplate in this style, only the lines and
labels.

## 3. Rod select matrix (Columbia benchboard)

- **One square illuminated pushbutton per rod,** in core layout, on the seafoam benchboard. Pressing
  one selects that rod, and the selected rod's button lights white. Its legend is the rod coordinate.
- **Rod motion controls** are separate, ordinary panel components: large yellow illuminated pushbuttons
  under a clear guard cover (new model, section 6). Each uses a plant sink on the reactor:
  - INSERT: momentary, steps the selected rod in one notch.
  - WITHDRAW: momentary, steps it out one notch.
  - CONT INSERT and CONT WITHDRAW: held, step continuously at the normal rod speed while pressed.

  These are ordinary new commands (section 5). Players lay out their own RMCS strip from them.
- **No rod blocks, sequence control or worth minimiser.** RWM, RSCS and RBM interlocks are protection
  logic; the mod never adds them (`checkNoProtectionLogic`). Players can build them with CC or Phase O
  relays from the signals.

## 4. SRV switches and lights (Columbia)

- `rotary_selector` gets an **oiltight style**: a 30.5 mm 3-position selector with a chrome bezel ring.
  It has a rectangular black legend plate above with three white legends (default `OFF`, `AUTO`,
  `OPEN`, from the existing position legends). Two variants: a **black knob** with a raised pointer
  bar, and a **chrome wing lever**. The detents are left / centre / right, about 45 degrees apart.
- `lamp` gets two lens variants: a **smooth dome** (the green ones) and a **ribbed dome** (the red
  ones, with vertical flutes), both with a chrome bezel. The existing light models stay.

## 5. Code track (Java)

1. **Reactor rod selection** (server state on the reactor controller, saved, old saves load with none):
   - signal `selected_rod` (1-based, 0 = none);
   - commands `select_rod` (n), `selected_rod_insert`, `selected_rod_withdraw` (one notch each), and
     `selected_rod_insert_continuous`, `selected_rod_withdraw_continuous`. The continuous commands are
     FOLLOW/MOMENTARY-friendly: a value of 1 starts stepping, 0 stops.

   They move rods only through the existing rod-command path (`DeviceCommands.reactorRodPosition` or
   the rod network), honouring CRD power and water exactly as today. CC peripheral methods match
   (`selectRod`, `getSelectedRod`, ...). Document them in SIGNALS.md.
2. **Per-rod indications.** The existing `rod_<n>_position` and `rod_<n>_accumulator` signals stay.
   Add:
   - `rod_<n>_drift`: the core's existing drift notion. Find it; drift means the rod is moving without
     a command.
   - `rod_<n>_scram`: the scram is active and this rod is not yet fully in, or use the core's per-rod
     scram state if one exists.
   - `rod_<n>_full_in`, `rod_<n>_full_out`.
   - `rod_<n>_accum_trouble`: charge below a threshold. The default is the core's own low-charge notion
     if one exists, otherwise 0.9 of full, configurable.
   - `rod_<n>_lattice_x`, `rod_<n>_lattice_y`: core coordinates.
   - `rod_<n>_label`: the coordinate in the GE XX-YY convention, available from CC as a string.

   Per LPRM string and level: indicated percent, bypassed, failed, and downscale/upscale flags. The
   thresholds are component config with documented typical defaults; cite a GE BWR source if you find
   one, otherwise call them gameplay defaults.
3. **Component types** (`dev.bwr.mod.panel`):
   - `full_core_display`, styles `columbia` and `browns_ferry`;
   - `rod_select_matrix`, style `columbia`.

   Each binds to ONE reactor controller with the existing linker, like a plant input. The layout comes
   automatically from the bound core's rod and LPRM lattice. The footprint is computed as rods across x
   module size; config `tile_cells` (2 to 6, default 3) lets players choose compact or large. If the
   footprint exceeds the span, refuse with a translated message that names the needed size. These types
   need more than `MAX_SPAN_BLOCKS = 4`: allow a per-type span of up to 8 blocks (128 cells) without
   changing other types. A full-size BWR (185 rods, 15 across) must fit at tile_cells 3 or less. Compact
   mod cores must look right too (test the smallest supported core).

   Unbound, the component shows its frame and dark tiles. With the binding gone or the reactor
   unformed, all lamps go dark.

   Clicking a rod-select button sends `select_rod` through the normal component output path (an output
   sink per press), with the existing panel permission checks.
4. **Data path.** Indications must NOT be wired one input per rod by the player. The component reads
   the bound reactor through one compact server-side snapshot per sample: positions, flag bits,
   selected rod and LPRM bits. It is sent as ONE content patch (the existing `syncsByPatch`
   mechanism) only when something changed. Keep it under `ComponentData.MAX_PATCH`, and bit-pack the
   flags.
5. **Rendering performance.** Up to ~740 tiles plus legends and up to ~370 two-digit readouts in one
   component. The static geometry and **static legends** (rod coordinates, FULL IN, DRIFT ...) must be
   cached. Today `PanelMeshCache` caches solid geometry but draws text every frame. Extend it, or build a
   cached text buffer for these types. Only lamp emission and digits are dynamic. A lamp that is off
   must not cost a text draw.

   **Target:** a 185-rod FCD in either style, plus a 185-rod select matrix, costs under 2 ms per frame
   together. Measure it with the `PanelPerformanceCheck` approach and report before and after. Use LOD:
   beyond the existing panel LOD distance, draw lamps as coloured quads with no text.
6. **Baked models** are used through the existing loader (`PanelModels`, `StyledComponentModel`), with
   procedural fallbacks so the code track never waits on the models track. Model ids and part names are
   in section 6.
7. **Tests** (GameTests; give every new class its own fixture origin, starting at 75_900 and stepping by
   2_000; force-load, release and reset as in existing tests):
   - layout from a real formed core matches its rod count and coordinates;
   - mirroring puts blanks around LPRM points;
   - select and step commands move only the selected rod, one notch, and respect CRD power;
   - continuous stepping starts and stops;
   - snapshot patch round trip, plus the no-change, no-patch case;
   - unbound, unformed and old-save cases;
   - span refusal.

   Add pure-Java tests for the layout math. Keep `checkNoProtectionLogic` green. Add lang entries,
   recipes and loot, with the asset audit at 0.
8. **Docs:** CONTROL-PANELS.md (how to place and bind an FCD), SIGNALS.md (new signals and commands),
   and a short section in PANEL-MODELS-SPEC.md for the new models.

9. **Labels** (project owner request, after the code above):
   - `full_core_display` and `rod_select_matrix` get an optional `Caption` text field (for example `FULL CORE
     DISPLAY`, `ROD SELECT`) drawn above the picture, and a `CoordLegends` toggle for the rod-coordinate legends
     (default on). The display also gets `EdgeNumbers` for the GE row and column numbers: `auto` (on for
     `browns_ferry`, off for `columbia`), `on` or `off`. Rod coordinates and LPRM labels stay automatic from the core.
   - The nameplate matches the Columbia SRV plates (`columbia_srv_switches_1.png`, `_2.png`): up to 3 lines (`Text`,
     `Text2`, `Text3`, 32 characters each; an old single-line save loads into line 1), two small bottom corner fields
     (`CornerLeft` "16", `CornerRight` "1101 PSIG"), an optional coloured tag with its own text (`Tag`, `TagColor`: a red
     `PAM`) and an optional coloured dot (`Dot`, `DotColor`), both bottom left, and an optional coloured stripe down the
     left edge (`Stripe`, `StripeColor`, red on the ADS valves). All are config fields with lang keys; the text ones are
     CC-settable through `PanelTextTarget` (`setText` is line 1 or the caption; `setTextField(label, field, text)` names
     `line1` to `line3`, `cornerLeft`, `cornerRight`, `tag` or `caption`). Patch round trip and old-save load are tested.

## 6. Models track (Blender): ids, parts, budgets

Use `art/models/panel/panel_export.py` (read its docstring) and the existing family pattern
(`art/models/panel/<family>/build.py` + README.md). Work in real inches and export into
`mod/src/main/resources/assets/bwr/models/panel/`. These models repeat hundreds of times, so the face
budgets below are hard limits.

| Model id | What | Parts (role) | Max faces |
| --- | --- | --- | --- |
| `fcd_tile_columbia` | one square lamp tile, ~1.0" face, thin dark bezel, split window | `bezel` (paint), `upper` (lamp), `lower` (lamp); label anchors `upper`, `lower` | 40 |
| `fcd_tile_blank` | the black blank tile | `bezel` (paint), `face` (paint) | 20 |
| `fcd_bf_rod_module` | Browns Ferry rod module: digit window + lamp cluster | `body` (paint), `digits` (glass; label anchor `digits`), lamp parts `accum`, `scram`, `drift`, `full_in`, `full_out`, `select` (lamp; label anchors same names) | 120 |
| `fcd_bf_lprm_strip` | 4-lamp vertical LPRM strip | `body` (paint), `lvl_a`..`lvl_d` (lamp); label anchors `coord`, `top` | 80 |
| `rod_select_button` | square illuminated pushbutton, slightly raised cap | `bezel` (paint), `cap` (lamp, travel 0.08" inward); anchor `legend` | 40 |
| `pb_guarded_yellow` | large square yellow illuminated pushbutton under a clear guard cube | `bezel` (paint), `cap` (lamp, travel), `guard` (glass); anchor `legend` | 150 |
| `oiltight_selector_knob` | 30.5 mm selector, chrome ring, black knob with pointer bar, black legend plate | `plate` (paint), `ring` (paint), `handle` (pivot at shaft, axis Z); anchors `pos1`..`pos3`, `caption` | 400 |
| `oiltight_selector_lever` | same, chrome wing lever | as above | 400 |
| `light_dome_smooth` | indicating light, chrome bezel, smooth dome lens | `bezel` (paint), `lens` (lamp) | 200 |
| `light_dome_ribbed` | same with a vertically fluted lens | `bezel` (paint), `lens` (lamp) | 300 |

Footprints should match the default cell aspect of the component that uses them. Lamp tints come from
the game. Model the lens in neutral white, `role lamp`, and the game colours it per half or legend.
Previews: front, three-quarter and side for every model in `tmp/panel-models/`, plus a 6 x 6 tiled
preview of the Columbia tiles and of the Browns Ferry modules, to judge the repeated look against the
photos. LOOK at the previews and iterate. Never touch the user's open Blender scene (`anim_prev`); use
headless `blender --background --factory-startup`.

## 7. Status (update when a track stops)

| Track | State | Resume from |
| --- | --- | --- |
| models (`fcd-models`) | not started | section 6 |
| code (`fcd-code`) | done: items 1 to 9 | Nothing left. Commits: rod selection 51c48ec, indications 3d9334a, components addb852, patch 252bdcd, cache and client models 4e4038a, style table f744b4d, docs e50724d, labels (item 9, last commit). To finish at integration: merge `fcd-models`, run `:mod:runTurbineModelCheck -PbwrFcdClientCheck` once (baked models) and compare screenshots in `mod/run/turbineModelCheck/fcd-check/` with the photos |
| integration (Claude) | not started | merge both, client screenshots against the photos |
