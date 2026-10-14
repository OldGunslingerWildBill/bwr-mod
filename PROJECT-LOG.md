# Project log

## Alpha.32 — Full core display, rod select matrix, SRV switches — 2026-10-14

Rod selection on the reactor (`select_rod`, step and continuous commands, CC `selectRod` ...), per-rod
indications (`rod_<n>_drift`, `_scram`, `_full_in`, `_full_out`, `_accum_trouble`, GE coordinates) and LPRM string
readouts, and two panel components, `full_core_display` (Columbia lamp matrix or Browns Ferry digital) and
`rod_select_matrix`, that read the whole core through one binding and one compact patch. They span up to 8 x 8 blocks
(a 185-rod core is 96 x 96 cells). The panel's GPU cache now also holds static legends as text vertex buffers. No rod
block, sequence control or worth minimiser was added (`checkNoProtectionLogic` stays green).

Frame cost on a real formed 185-rod core, `FcdClientCheck` (`-PbwrFcdClientCheck`), 1280 x 720, vsync off, mean frame
time over 8 s from a fixed camera 9 to 13 blocks away, empty scene 0.46 to 0.58 ms (the machine's own noise between
runs). Procedural bodies unless noted. "Per frame text" is the panel cache as it was (solid geometry in a vertex buffer,
every legend laid out each frame), "no cache" is `-Dbwr.panelImmediate=true`:

| Scene | no cache | per frame text | now | now, baked models |
| --- | --- | --- | --- | --- |
| Columbia | 2.42 | 1.95 | 0.54 | 0.57 |
| Browns Ferry | 2.13 | 1.45 | 0.49 | 0.55 |
| Select matrix | 0.54 | 0.76 | 0.50 | 0.50 |
| Columbia + matrix | 2.10 | 1.93 | 0.51 | 0.51 |
| Browns Ferry + matrix | 2.19 | 1.57 | 0.52 | 0.52 |
| Columbia + matrix, beyond 24 blocks | 1.29 | 0.51 | 0.44 | 0.50 |
| Browns Ferry + matrix, beyond 24 blocks | 0.89 | 1.57 | 0.50 | 0.47 |

The cost over the empty scene is 0.05 to 0.09 ms, against the 2 ms budget for a 185-rod display plus a matrix. The
check itself fails if a block's text is not cached, a legend is laid out again, or buffers are rebuilt while the
picture is static.

## Alpha.32 — Phases J and K — 2026-10-13

RHR now cools the suppression pool and pressure vessel, sprays containment,
and routes break and relief steam through the modeled containment. The vessel
stores metal heat, and excessive cooldown adds boundary damage. Containment
pressure remains one shared drywell/wetwell node. Client visuals add GPU
Cherenkov and water shaders plus an instanced cooling vapor option. Every
effect can fall back independently; `bwr-visuals.toml` selects `AUTO`,
`SHADER`, or `CLASSIC`. The classic renderers remain available.

## Alpha.31 — Phase F — 2026-10-11

Completed the buildable-containment milestone: surveyed rooms, strength and
openings, penetrations and isolation, vault door and airlock, physical nitrogen
supply and wetwell vent, plus the `bwr_containment` computer interface. Review
fixes preserve the dual-open airlock leak through override release and reload;
redstone now cycles leaves under the interlock. Persistent tank GameTests wait
for their entity chunks before checking drops. The controller GUI is planned
for a later visual overhaul. Full verification and artifact details are in the
[alpha.31 verification record](RELEASE-VERIFICATION-ALPHA31.md).
