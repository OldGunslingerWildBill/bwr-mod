"""Columbia SRV switch and light models (family F): 30.5 mm oiltight selectors and dome lights.

Builds, exports and previews:

    oiltight_selector_knob    rotary_selector style "oiltight": black knob with pointer bar
    oiltight_selector_lever   rotary_selector style "oiltight": chrome wing lever
    light_dome_smooth         lamp lens variant: smooth dome (the green lights)
    light_dome_ribbed         lamp lens variant: vertically fluted dome (the red lights)

Run headless from the repo root:

    blender --background --factory-startup --python art/models/panel/oiltight/build.py
    blender ... --python art/models/panel/oiltight/build.py -- --only light_dome_smooth --no-preview

Exports mod/src/main/resources/assets/bwr/models/panel/<id>.json and renders previews into
tmp/panel-models/oiltight/ (git-ignored): <id>_front / _three_quarter / _side, the handle at
each detent (_pos1 / _pos2 / _pos3), lamp off and lit states, and sample legends.

Conventions are those of panel_export: inches, panel face Z = 0, +Z toward the operator,
+Y up, footprint top-left at (0, 0) extending to (w, -h). The selector handle is modelled
pointing straight up (12 o'clock, the AUTO position); the game turns it to the bearing of the
pos1..pos3 label anchors seen from the pivot.
"""
import math
import os
import sys

import bpy

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
import panel_export as px  # noqa: E402
import panel_geo as pg  # noqa: E402
from panel_geo import Geo, box, lathe, prism  # noqa: E402

PREVIEW_DIR = os.path.join(px.REPO, "tmp", "panel-models", "oiltight")

SRC = ("30.5 mm oiltight selector switches and indicating lights, owner-supplied photos of "
       "Columbia Generating Station SRV panel (no manufacturer drawing; sizes scaled from the photos "
       "against the 30.5 mm / 1.20 in mounting hole)")

# --------------------------------------------------------------------------
# DIMENSIONS (inches). [PH] = scaled off the owner-supplied photos against the 30.5 mm
# (1.20 in) mounting hole; [ASM] = assumed, no source; [SPEC] = FULL-CORE-DISPLAY-SPEC.md.
# --------------------------------------------------------------------------
SEL_W, SEL_H = 2.00, 2.50       # [SPEC] 4:5, the rotary_selector default cell aspect (8 x 10)
SHAFT = (1.00, -1.40)           # [PH] shaft centre: legend plate above, ring below fit the footprint
RING_R = 0.78                   # [PH] chrome mounting ring, 1.56 in across (about 1.3 x the hole)
RING_Z = 0.28                   # [ASM] ring top
BORE_R = 0.585                  # [PH] ring bore the knob sits in
KNOB_R = 0.56                   # [PH] knob and lever base radius (about 3/4 of the ring)
KNOB_TOP = 0.50                 # [PH] knob top, a little above the ring
BAR_TOP = 0.62                  # [PH] pointer bar top
LEVER_BASE_TOP = 0.46           # [PH] chrome lever base top
LEVER_TOP = 0.72                # [PH] wing top
CELL_W, CELL_H = 0.62, 0.36     # [PH] one legend cell of the black legend plate
CELL_DX = 0.60                  # [PH] outer cell centre offset from the shaft
CELL_DY_OUT, CELL_DY_MID = 0.78, 0.93   # [ASM] outer / centre cell centre above the shaft
PLATE_Z = 0.05                  # [ASM] legend plate thickness (it tucks under the ring)
DETENT = math.degrees(math.atan2(CELL_DX, CELL_DY_OUT))   # handle angle: bearing of pos1 / pos3

LIGHT_FOOT = 1.10               # [PH] small panel light, about 0.6 of the selector ring
LIGHT_BEZEL_R = 0.50            # [PH] chrome bezel
LIGHT_LENS_R = 0.39             # [PH] dome radius

# Palette (sRGB), PANEL-MODELS-SPEC.md
CHROME = "C8CCD0"
PHENOLIC = "1A1A1A"             # black knob
PLATE_BLACK = "141414"          # legend plate
BAR_BLACK = "2A2A2A"            # pointer bar top, a touch lighter so it reads on the black knob
SLOT = "101010"
LENS = "F0F0E0"                 # neutral white lens, tinted by the game


def mats():
    return {
        "chrome": px.material("chrome", CHROME, 0.3, 0.6),
        "knob": px.material("phenolic", PHENOLIC, 0.5),
        "bar": px.material("bar", BAR_BLACK, 0.45),
        "plate": px.material("plate_black", PLATE_BLACK, 0.55),
        "slot": px.material("slot", SLOT, 0.5),
        "lens": px.material("lens_white", LENS, 0.4),
    }


MODELS = {}


def model(name, w, h, max_faces, kind):
    def deco(fn):
        MODELS[name] = (fn, w, h, max_faces, kind)
        return fn
    return deco


def poly_loops(g, loops, mat=0, cap=True):
    """Convex polygon loops (same vertex count, counter-clockwise from +Z) joined bottom to top."""
    rings = [[g.vert((x, y, z)) for x, y in pts] for pts, z in loops]
    n = len(rings[0])
    for a, b in zip(rings, rings[1:]):
        for j in range(n):
            j2 = (j + 1) % n
            g.face((a[j], a[j2], b[j2], b[j]), mat)
    if cap:
        g.face(tuple(rings[-1]), mat)


def scaled(pts, sx, sy, cx=0.0, cy=0.0):
    return [(cx + (x - cx) * sx, cy + (y - cy) * sy) for x, y in pts]


def selector_base(M):
    """Legend plate (three black cells, the outer two dropped so each sits on its detent
    bearing) and the chrome mounting ring."""
    cx, cy = SHAFT
    g = Geo(M["plate"])
    for dx, dy in ((-CELL_DX, CELL_DY_OUT), (0.0, CELL_DY_MID), (CELL_DX, CELL_DY_OUT)):
        x, y = cx + dx, cy + dy
        box(g, x - CELL_W / 2, y - CELL_H / 2, 0.0, x + CELL_W / 2, y + CELL_H / 2, PLATE_Z, 0)
    g.build("plate", part="plate")
    g = Geo(M["chrome"])
    segs = 24
    knurl = lambda i, j, r: r - (0.018 if (i <= 1 and j % 2) else 0.0)
    lathe(g, cx, cy, [(RING_R, 0.0), (RING_R, RING_Z - 0.04), (RING_R - 0.04, RING_Z), (BORE_R + 0.035, RING_Z),
                      (BORE_R, RING_Z - 0.03), (BORE_R, 0.12)], segs, 0, rmod=knurl, ngon_cap=True)
    g.build("ring", part="ring")
    for k, (dx, dy) in enumerate(((-CELL_DX, CELL_DY_OUT), (0.0, CELL_DY_MID), (CELL_DX, CELL_DY_OUT))):
        px.label("pos%d" % (k + 1), cx + dx, cy + dy, PLATE_Z + 0.002, 0.15)
    px.label("caption", cx, cy - RING_R - 0.17, 0.003, 0.15)


@model("oiltight_selector_knob", SEL_W, SEL_H, 400, "selector")
def oiltight_selector_knob():
    px.clear_scene()
    M = mats()
    selector_base(M)
    cx, cy = SHAFT
    g = Geo(M["knob"], M["bar"])
    segs = 24
    lathe(g, cx, cy, [(KNOB_R, 0.16), (KNOB_R, KNOB_TOP - 0.05), (KNOB_R - 0.045, KNOB_TOP), (KNOB_R - 0.075, KNOB_TOP)],
          segs, 0, ngon_cap=True)
    # raised pointer bar: tail at the back, tapering to the tip (pointing up in the modelled pose)
    bar = [(-0.15, -0.38), (0.15, -0.38), (0.11, 0.18), (0.065, 0.49), (-0.065, 0.49), (-0.11, 0.18)]
    pts = [(cx + x, cy + y) for x, y in bar]
    poly_loops(g, [(pts, KNOB_TOP - 0.02), (pts, BAR_TOP - 0.03),
                   ([(cx + x * 0.8, cy + y * 0.93) for x, y in bar], BAR_TOP)], 1)
    g.build("handle", part="handle", origin=(cx, cy, 0.0), pivot=(cx, cy, 0.0), axis=(0, 0, 1))


@model("oiltight_selector_lever", SEL_W, SEL_H, 400, "selector")
def oiltight_selector_lever():
    px.clear_scene()
    M = mats()
    selector_base(M)
    cx, cy = SHAFT
    segs = 24
    g = Geo(M["chrome"], M["slot"])
    lathe(g, cx, cy, [(KNOB_R, 0.16), (KNOB_R, LEVER_BASE_TOP - 0.07), (KNOB_R - 0.06, LEVER_BASE_TOP),
                      (0.40, LEVER_BASE_TOP + 0.015), (0.0, LEVER_BASE_TOP + 0.03)], segs, 0)
    # wing: tall flat bar across the knob, pointer end (top) slightly narrower
    wing = [(-0.19, -0.48), (0.19, -0.48), (0.21, 0.0), (0.12, 0.50), (-0.12, 0.50), (-0.21, 0.0)]
    pts = [(cx + x, cy + y) for x, y in wing]
    top = [(cx + x * 0.72, cy + y * 0.93) for x, y in wing]
    poly_loops(g, [(pts, LEVER_BASE_TOP), (pts, LEVER_TOP - 0.05), (top, LEVER_TOP)], 0)
    # the dark slot along the wing's top (1 quad)
    s = [g.vert((cx - 0.028, cy - 0.36, LEVER_TOP + 0.003)), g.vert((cx + 0.028, cy - 0.36, LEVER_TOP + 0.003)),
         g.vert((cx + 0.028, cy + 0.36, LEVER_TOP + 0.003)), g.vert((cx - 0.028, cy + 0.36, LEVER_TOP + 0.003))]
    g.face(tuple(s), 1)
    g.build("handle", part="handle", origin=(cx, cy, 0.0), pivot=(cx, cy, 0.0), axis=(0, 0, 1))


def light_bezel(M, segs):
    cx = cy = LIGHT_FOOT / 2
    cy = -cy
    g = Geo(M["chrome"])
    r = LIGHT_BEZEL_R
    lathe(g, cx, cy, [(r, 0.0), (r, 0.11), (r - 0.04, 0.15), (LIGHT_LENS_R + 0.03, 0.15), (LIGHT_LENS_R + 0.005, 0.11)],
          segs, 0)
    g.build("bezel", part="bezel")
    return cx, cy


@model("light_dome_smooth", LIGHT_FOOT, LIGHT_FOOT, 200, "lamp")
def light_dome_smooth():
    px.clear_scene()
    M = mats()
    segs = 20
    cx, cy = light_bezel(M, segs)
    g = Geo(M["lens"])
    R = LIGHT_LENS_R
    lathe(g, cx, cy, [(R, 0.10), (R, 0.20), (R * 0.92, 0.31), (R * 0.74, 0.40), (R * 0.44, 0.46), (0, 0.485)],
          segs, 0)
    g.build("lens", part="lens", role="lamp", tint="white")


@model("light_dome_ribbed", LIGHT_FOOT, LIGHT_FOOT, 300, "lamp")
def light_dome_ribbed():
    px.clear_scene()
    M = mats()
    segs = 24
    cx, cy = light_bezel(M, segs)
    g = Geo(M["lens"])
    R = LIGHT_LENS_R
    # 12 flutes: every second segment is pulled in; the groove fades towards the apex
    flute = lambda i, j, r: r - (0.034 * (r / R) if (j % 2 and i >= 1) else 0.0)
    lathe(g, cx, cy, [(R, 0.10), (R, 0.20), (R * 0.95, 0.285), (R * 0.82, 0.365), (R * 0.60, 0.43),
                      (R * 0.30, 0.47), (0, 0.49)], segs, 0, rmod=flute)
    g.build("lens", part="lens", role="lamp", tint="white")


# --------------------------------------------------------------------------
# Previews
# --------------------------------------------------------------------------
SAMPLES = {"pos1": ("OFF", "F2F0E8", 0), "pos2": ("AUTO", "F2F0E8", 0), "pos3": ("OPEN", "F2F0E8", 0),
           "caption": ("SRV", "202020", 0)}


def render_all(name, w, h, kind):
    os.makedirs(PREVIEW_DIR, exist_ok=True)
    base = os.path.join(PREVIEW_DIR, name)
    res = 900
    outs = px.preview(base, w, h, res=res)
    if kind == "lamp":
        for state in ("off", "lit"):
            pg.set_lamps(state)
            outs += px.preview(base + "_" + state, w, h, views=("front", "three_quarter"), res=res)
        pg.set_lamps("authored")
    if kind == "selector":
        handle = bpy.data.objects["handle"]
        for tag_, deg in (("pos1", DETENT), ("pos2", 0.0), ("pos3", -DETENT)):   # Blender +Z is counter-clockwise
            handle.rotation_euler.z = math.radians(deg)
            outs += px.preview(base + "_" + tag_, w, h, views=("front", "three_quarter"), res=res)
        handle.rotation_euler.z = math.radians(-DETENT)
        made = pg.sample_legends(SAMPLES)
        outs += px.preview(base + "_legends_pos3", w, h, views=("front", "three_quarter"), res=res)
        handle.rotation_euler.z = 0.0
        outs += px.preview(base + "_legends_pos2", w, h, views=("front",), res=res)
        for o in made:
            bpy.data.objects.remove(o)
    return outs


def main():
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    only = set(argv[argv.index("--only") + 1].split(",")) if "--only" in argv else None
    previews = "--no-preview" not in argv
    for name, (fn, w, h, max_faces, kind) in MODELS.items():
        if only and name not in only:
            continue
        fn()
        px.export(name, w, h, SRC, max_faces=max_faces)
        if previews:
            render_all(name, w, h, kind)
    if previews and (not only or "row" in only):
        srv_row()


def srv_row():
    """Preview in context: a row like the Columbia SRV panel (green smooth light, red ribbed light,
    legend, selector), knob and lever versions."""
    px.clear_scene()
    M = mats()
    sel = {}
    light = {}
    # build each template into one scene, offset far away, then clone into the row
    oiltight_selector_knob()
    sel["knob"] = [o for o in bpy.context.scene.objects if o.type == "MESH"]
    labels_k = {e["bwr_label"]: e.location.copy() for e in bpy.context.scene.objects if e.type == "EMPTY"}
    before = set(bpy.context.scene.objects)
    px_clear = px.clear_scene
    px.clear_scene = lambda: None
    try:
        for e in [o for o in bpy.context.scene.objects if o.type == "EMPTY"]:
            bpy.data.objects.remove(e)
        oiltight_selector_lever()
        new = [o for o in bpy.context.scene.objects if o not in before]
        sel["lever"] = [o for o in new if o.type == "MESH"]
        for e in [o for o in bpy.context.scene.objects if o.type == "EMPTY"]:
            bpy.data.objects.remove(e)
        before = set(bpy.context.scene.objects)
        light_dome_smooth()
        light["smooth"] = [o for o in bpy.context.scene.objects if o not in before and o.type == "MESH"]
        before = set(bpy.context.scene.objects)
        light_dome_ribbed()
        light["ribbed"] = [o for o in bpy.context.scene.objects if o not in before and o.type == "MESH"]
        for e in [o for o in bpy.context.scene.objects if o.type == "EMPTY"]:
            bpy.data.objects.remove(e)
    finally:
        px.clear_scene = px_clear
    green = px.material("pv_green_lit", "20B040", 0.4)
    pg.set_lamp_mat(green, "lit", strength=1.4)
    red = px.material("pv_red_off", "E02020", 0.4)
    pg.set_lamp_mat(red, "off", dim=0.4)
    pitch = 3.0
    n = 4
    for k in range(n):
        x = k * pitch
        kind = "knob" if k % 2 == 0 else "lever"
        for o in sel[kind]:
            pg.clone(o, x + 0.5, -2.6, name=f"{o.name}_{k}")
        for o in light["smooth"]:
            pg.clone(o, x + 0.15, 0.0, material=green)
        for o in light["ribbed"]:
            pg.clone(o, x + 1.55, 0.0, material=red)
        # nameplate under the lights
        pg.plane(x - 0.1, -1.95, x + 2.6, -1.25, -0.01, px.material("nameplate", "EDEBE3", 0.7), "nameplate")
        pg.add_text("SAFETY RELIEF", x + 1.25, -1.45, 0.0, 0.17, "202020", 0.0)
        pg.add_text("MS-RV-%dA" % (k + 1), x + 1.25, -1.75, 0.0, 0.17, "202020", 0.0)
        for lid, txt in (("pos1", "OFF"), ("pos2", "AUTO"), ("pos3", "OPEN")):
            e = labels_k[lid]
            pg.add_text(txt, x + 0.5 + e.x, -2.6 + e.y, PLATE_Z + 0.004, 0.15, "F2F0E8", 0.0)
    # handle poses: OFF, AUTO, OPEN, AUTO
    poses = [DETENT, 0.0, -DETENT, 0.0]
    for k in range(n):
        for o in bpy.context.scene.objects:
            if o.type == "MESH" and o.name.startswith("handle") and o.name.endswith(f"_{k}"):
                o.rotation_euler.z = math.radians(poses[k])
    for o in sum(sel.values(), []) + sum(light.values(), []):
        o.hide_render = True
    pg.plane(-0.3, -4.9, n * pitch, 0.3, -0.02, px.material("srv_board", "7FB0B4", 0.8), "srvboard")
    px.preview(os.path.join(PREVIEW_DIR, "srv_row"), n * pitch, 5.2, views=("front", "three_quarter"), res=1600)


main()
