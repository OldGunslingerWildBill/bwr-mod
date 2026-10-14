# Family F: oiltight selectors and dome lights (Columbia SRV panel)

Models for `rotary_selector` style `oiltight` and the two new `lamp` lens variants of
`FULL-CORE-DISPLAY-SPEC.md` section 4. Built by `build.py` and exported through
`../panel_export.py` (helpers in `../panel_geo.py`):

    blender --background --factory-startup --python art/models/panel/oiltight/build.py
    blender ... --python art/models/panel/oiltight/build.py -- --only light_dome_smooth --no-preview

The script writes `mod/src/main/resources/assets/bwr/models/panel/<id>.json` and previews into
`tmp/panel-models/oiltight/` (git-ignored):

- `<id>_front|three_quarter|side.png` for every model.
- `oiltight_selector_*_pos1|pos2|pos3_*`: the handle at each detent; `_legends_pos*` with sample text.
- `light_dome_*_off_*` / `_lit_*`: the lens dimmed as the game dims an unlit lamp, and full-bright.
- `srv_row_*`: four selectors (knob, lever, knob, lever at OFF, AUTO, OPEN, AUTO) with a green
  smooth light, a red ribbed light and a nameplate each, to compare with the Columbia SRV photos.

## Models

| id | Serves | Footprint (in) | Faces / budget | Parts (role) | Moving part | Label anchors |
| --- | --- | --- | --- | --- | --- | --- |
| `oiltight_selector_knob` | `rotary_selector` `oiltight`, black knob | 2.00 x 2.50 (4:5) | 267 / 400 | `plate` (paint), `ring` (paint), `handle` (paint) | `handle` turns about Z through (1.0, 1.4) | `pos1`, `pos2`, `pos3` (0.15), `caption` (0.15) |
| `oiltight_selector_lever` | `rotary_selector` `oiltight`, chrome wing lever | 2.00 x 2.50 | 270 / 400 | same | same | same |
| `light_dome_smooth` | `lamp` lens variant, smooth dome (the green lights) | 1.10 x 1.10 | 180 / 200 | `bezel` (paint), `lens` (lamp) | none | none |
| `light_dome_ribbed` | `lamp` lens variant, 12-flute dome (the red lights) | 1.10 x 1.10 | 240 / 300 | `bezel` (paint), `lens` (lamp) | none | none |

**Selector.** A black legend plate of three cells (white legends drawn by the game at `pos1` to
`pos3`) sits tucked behind a chrome mounting ring 1.56 in across. In the photos the three cells
are a straight row; here the outer two are dropped 0.15 in so each sits on a bearing of about
37.6 degrees from the shaft. The handle is modelled pointing straight up (AUTO, `pos2`); the game
turns it to the bearing of the `pos` label it is on, so the detents are left, centre and right,
about 37.6 degrees apart rather than the spec's 45 (a plate above the ring cannot put the outer
legends at 45 degrees). `handle` has pivot (1.0, 1.4) in the exported y-down frame and axis Z.
`caption` is on the panel below the ring.

- `oiltight_selector_knob`: black phenolic knob with a raised tapered pointer bar (its top a touch
  lighter, so it reads on the black knob).
- `oiltight_selector_lever`: chrome base with a tall chrome wing across it, narrower at the pointer
  end, with a dark slot along its top.

**Lights.** Chrome bezel (1.0 in) with a dome 0.78 in across. The smooth lens is a plain dome;
the ribbed one has 12 vertical flutes that fade towards the apex. Lenses are neutral white, role
`lamp`, `bwr_tint` `white`: the game colours them (green and red on the SRV panel).

## What the dimensions are based on

No manufacturer drawing was used. Sizes were scaled off the photos against the 30.5 mm (1.20 in)
mounting hole: the ring is about 1.3 x the hole, the knob about 3/4 of the ring, the lights about
0.6 of the ring. Proportions of the legend plate, the knob and the lever are estimates from the
photos, not measurements.

## Sources

Owner-supplied photos of Columbia Generating Station (`columbia_srv_switches_1.png`,
`columbia_srv_switches_2.png`; not in the repo).
