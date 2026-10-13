# Family B: Westinghouse Type W-2 switches

These are Westinghouse Type W-2 instrument and control switches as they appear on
a benchboard. Each one has a die-cast aluminium dial plate with bright side
rails, a white engraved ABS nameplate (2.25" x 3.00") and a black moulded
phenolic handle. Each handle has the moulded arrow, modelled as a light inlay.
`build.py` builds all seven models from the dimension table at its top. Every
dimension in that table cites the bulletin page it came from. Rebuild and
re-render them with:

    blender --background --factory-startup --python art/models/panel/w2/build.py
    blender ... --python art/models/panel/w2/build.py -- --only w2_oval,w2_key --no-render

The JSON files are written to `mod/src/main/resources/assets/bwr/models/panel/`.
The previews are written to `tmp/panel-models/w2/`:

- `<id>_front`, `_three_quarter` and `_side`
- `<id>_closeup`, seen from above and to the right, as a player at a benchboard sees it
- one render per handle detent (`<id>_handle_cw45`, ...); the extremes also have a three-quarter view
- `<id>_labels_front`, which shows sample legends at the label anchors

## Conventions

- Angles are in degrees, **clockwise as seen by the operator**. 0 means the
  arrow points to 12 o'clock.
- In the exported frame (x right, y down, z out), this is a positive rotation
  about the exported axis `[0, 0, 1]` using the standard rotation matrix.
- Every moving part pivots at its listed pivot, which is the switch shaft for
  the handles.
- `build.py` checks every pose of every moving part against the footprint
  before export.
- All label anchors are 0.01" in front of the nameplate face (z = 0.29), and
  all have rot = 0. W-2 engraving is horizontal.

## Models

| id | component / style | footprint (in) | faces | moving parts |
| --- | --- | --- | --- | --- |
| `w2_pistol` | control_switch "westinghouse" | 2.40 x 3.60 (2:3) | 231 | `handle` -45 / 0 / +45; `target` (flag) |
| `w2_pistol_hd` | control_switch variant | 3.90 x 5.85 (2:3) | 231 | `handle` -45 / 0 / +45; `target` (flag) |
| `w2_oval` | rotary_selector "westinghouse" | 2.40 x 3.00 (4:5) | 253 | `handle` -135 ... +180 in 45 steps |
| `w2_round_notched` | rotary_selector variant | 2.40 x 3.00 (4:5) | 347 | `handle` -135 ... +180 in 45 steps |
| `w2_mini_slim` | rotary_selector variant | 2.40 x 3.00 (4:5) | 218 | `handle` -135 ... +180 in 45 steps |
| `w2_key` | key_switch "westinghouse" | 3.00 x 4.00 (3:4) | 472 | `handle` -45 (OFF) / +45 (ON) |
| `w2_tumbler` | key_switch variant | 3.60 x 4.80 (3:4) | 358 | `handle` -45 / +45; `plug` 0 (locked) / +90 (unlocked) |

In every model the shaft is at the centre of the footprint, and so is the
nameplate. The only exception is `w2_tumbler`, where the nameplate and lock
cylinder are centred together as a unit.

- **w2_pistol**: a breaker control switch with the pistol-grip handle (p.19
  fig. 5; 1.31" from the shaft to the tip; face 2.06" out from the panel).
  - The grip hangs straight down at 0 and leans 0.12" toward the operator at the
    tip.
  - The nameplate carries the trapezoidal red/green target window above the
    shaft (nameplate 692C707H14, p.13, and the "circuit breaker control" photo on
    p.1).
  - Part `target`, role `flag`: a single face that the game recolours. Its base
    colour is red C0261E.
- **w2_pistol_hd**: the same switch with the heavy-duty ("large") pistol grip
  (p.19 fig. 7, p.2).
  - 2.50" from the shaft to the tip, face 3.50" out from the panel, round hub,
    and a slanted tip that leans 0.45" outward.
  - The grip reaches 1" below the nameplate. At ±45 it swings well past the
    nameplate, which is why the footprint is wider.
- **w2_oval**: the oval handle (p.19 fig. 4, p.2 "Oval Fixed").
  - A tapered bar 2-3/16" x 3/4", 0.55" thick, on a round shank, with its face
    2.06" out from the panel.
  - The shaft sits 1.17" from the narrow end. The long arrow points to the
    narrow end.
- **w2_round_notched**: the round notched handle (p.19 fig. 6, p.2).
  - A six-lobe knob 2" across, 0.45" thick, with a raised centre boss 1.1"
    across and the arrow in the top lobe.
  - It hides the position legends when seen straight on, as the real knob does.
    They show from any angle off-axis.
- **w2_mini_slim**: the mini-slimline "DS" handle (p.19 fig. 8, p.2).
  - A low pointer whose face is 0.84" out from the panel, with a pointed
    head and a tail 1.0" from the shaft.
  - It has a raised rim around a shallow pocket, with the arrow in the point.
- **w2_key**: the removable key handle (p.19 fig. 9, which is the p.2
  "finger tip" shape).
  - 1.88" from the shaft to the tail, face 0.81" out from the panel, with a
    pointed hub, a waisted shank, a raised rim and a hub boss.
  - A bright bushing ring around the hub stands for the larger shaft opening on
    removable-key nameplates (p.13).
  - The key is always drawn in place. The game's key switch has no state for a
    removed key.
- **w2_tumbler**: the tumbler pin key lock above the handle (p.17). This is the
  standard top mounting.
  - A chrome cylinder 1.4375" across, with its centre 2.50" above the shaft and
    its face 0.30" proud of the panel.
  - An eccentric brass plug 0.58" across, 0.22" below the cylinder centre, with
    a keyway (part `plug`, pivot at the plug centre).
  - The handle is the standard pistol grip, as in the p.17 side view.
  - The lock bracket is behind the panel, so it is not modelled.

## Label anchors

- **Every model:**
  - `caption`: 1.24" above the shaft, h 0.10. These are engraving lines 15/16
    of table 3 on p.14.
  - `maker`: 1.31" below the shaft, h 0.065. This is where "Westinghouse" sits
    on every p.13 nameplate.
- **Control and key switches** (layout from example 3 on p.14):
  - `top` (the normal / centre legend): 0.98" above the shaft, h 0.08. On the
    control switches it sits above the target window.
  - `left` and `right`: ±0.46", 0.40" above the shaft, h 0.08. `left` is
    right-aligned and `right` is left-aligned.
  - `legend` (PULL TO LOCK, lower left; control switches only): (-0.38,
    -0.30), h 0.06, right-aligned.
- **Rotary selectors:**
  - `pos1`..`pos8` sit at radius 0.55" from the shaft, h 0.065, at
    -135 + 45·(k-1) degrees clockwise from 12 o'clock.
  - So `pos4` is at 12 o'clock and `pos8` at 6 o'clock, which matches the
    placeholder's `-135..+135` dial spread.
  - Anchors on the right are left-aligned, anchors on the left are
    right-aligned, and the top and bottom anchors are centred.

## Deviations and notes

- **Detent angle:** the real W-2 throws 30° between positions, with up to 12
  positions (p.2). The handles and anchors here use the spec's 45° steps.
- **Nameplate colour:** the nameplate is white, engraved black, per p.2
  ("white Cycolac ABS"), not the black escutcheon in the generic palette. Current
  Electroswitch Type W production ships a black nameplate as standard. To get
  that, change `WHITE` in `build.py`.
- **Mounting screws:** the screws are not modelled. Fig. 1 on p.19 draws them as
  hidden (dashed, on a 2.31" circle at 15°, per the p.17 drilling plan). The
  p.13 nameplates and p.1 photos show no screw heads, because the nameplate
  covers them.
- **Target window:** the spec gives no target for the W-2. It is added from the
  bulletin (p.13, p.14 example 3, p.1 photo) so that the Westinghouse control
  switch can show red/green like the GE one.
- **Standard pistol grip length:** this uses the printed 1.31" (p.19 fig. 5).
  However, fig. 5 and the p.2 front view are both drawn about 1.8" long, and p.2
  prints "2-1/4" and "1-3/16" on the side view. If the game wants a longer grip,
  change `PISTOL_L`; the 2:3 footprint has room for up to about 1.4".
- **Measured dimensions:** several dimensions were scaled off the drawings and
  are marked "measured" in the table. These are the plate corner radius, the
  oval's shaft offset, the lock plug, the lock projection and the target window.
- **Engraving:** the radial position lines and all text are left to the game,
  which draws them at the anchors.

## Sources

- Westinghouse Descriptive Bulletin 34-251, April 1987, "Instrument and Control
  Switches Type W-2". These pages were read locally, and no images are in the
  repo:
  - p.1: photos of the instrument switch, the control switch with target, and
    the switch lock
  - p.2: handle drawings with dimensions, and the switch dial and nameplate
    materials
  - p.3: the handle text and the recessed arrow
  - p.5: the exploded view, with the nameplate and escutcheon in front of the
    dial plate
  - p.13 and p.14: blank nameplates and the engraving-location table
  - p.17: the tumbler pin key lock
  - p.19: outline dimensions and handle figures 4 to 10
- Electroswitch Type W product page (electroswitch.com), which confirms the
  standard black nameplate and red/green target on current production. It is
  text only, and no images were used.
