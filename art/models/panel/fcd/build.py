"""Full Core Display, rod select matrix and rod motion pushbutton models (family E).

Builds, exports and previews:

    fcd_tile_columbia   Columbia FCD lamp tile: split upper / lower legend window
    fcd_tile_blank      Columbia FCD blank (black) tile
    fcd_bf_rod_module   Browns Ferry digital rod module: digit window + lamp cluster
    fcd_bf_lprm_strip   Browns Ferry LPRM strip: 4 level lamps, coordinate plate
    rod_select_button   Columbia rod select matrix: square illuminated pushbutton
    pb_guarded_yellow   Columbia RMCS yellow illuminated pushbutton under a clear guard

Run headless from the repo root:

    blender --background --factory-startup --python art/models/panel/fcd/build.py
    blender ... --python art/models/panel/fcd/build.py -- --only fcd_tile_columbia --no-preview

Exports mod/src/main/resources/assets/bwr/models/panel/<id>.json and renders previews
into tmp/panel-models/fcd/ (git-ignored): <id>_front / _three_quarter / _side, lit and
off lamp states, a pressed cap, the tiled 6x6 / 8x8 Columbia grids and the Browns
Ferry module grid with an LPRM strip.

Conventions are those of panel_export: inches, panel face Z = 0, +Z toward the
operator, +Y up, footprint top-left at (0, 0) extending to (w, -h).
"""
import math
import os
import sys

import bpy

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
import panel_export as px  # noqa: E402
import panel_geo as pg  # noqa: E402
from panel_geo import Geo, box, prism, rect_pts, rlathe  # noqa: E402

PREVIEW_DIR = os.path.join(px.REPO, "tmp", "panel-models", "fcd")

SRC_PHOTOS = ("owner-supplied photos of Columbia Generating Station and Browns Ferry 3 panel 3-9-5 "
              "(no manufacturer drawing; sizes are scaled from the photos)")
SRC_COL_FCD = "Columbia FCD lamp tiles, " + SRC_PHOTOS
SRC_BF_FCD = "Browns Ferry 3 panel 3-9-5 digital FCD modules, " + SRC_PHOTOS
SRC_COL_BTN = "Columbia rod select matrix and RMCS strips, " + SRC_PHOTOS

# --------------------------------------------------------------------------
# DIMENSIONS (inches). No published drawings: every value is [PH] = scaled off the
# owner-supplied photos against an assumed size, or [SPEC] = FULL-CORE-DISPLAY-SPEC.md 6.
# --------------------------------------------------------------------------
# Columbia lamp tile. [PH columbia_fcd_closeup] tile = silver rim, black bezel, white split
# window; the dark backplate shows between tiles. Window enlarged over the photo (about 60 %
# of pitch there) to the spec's ~1.0 in face so legends stay readable at a few blocks.
TILE_FOOT = 1.00            # [SPEC] pitch: 1.0 in square
TILE_RIM = 0.47             # [PH] silver rim half width (outer tile 0.94 in, 0.06 in backplate gap)
TILE_BEZEL = 0.43           # [PH] black bezel half width inside the silver rim
TILE_WIN = 0.36             # [PH/SPEC] window half width (0.72 in wide)
TILE_Z_RIM = 0.125          # [ASM] rim top
TILE_Z_BEZEL = 0.095        # [ASM] bezel top, below the rim
TILE_Z_FLOOR = 0.06         # [ASM] window floor
TILE_Z_LENS = 0.09          # [ASM] lens front, just below the bezel top
TILE_SPLIT = 0.010          # [PH] half gap between the upper and lower legend half

# Browns Ferry rod module. [PH browns_ferry_fcd]: ~1.2 : 1 beige plate, black digit window on
# top, two wide windows (each split in two) below. Real scale unknown; 2.4 in wide is chosen
# so the 2-digit window is about 1.4 in, matching a ~0.55 in digit height.
BF_W, BF_H = 2.40, 1.95     # [PH] plate and footprint
BF_DIG_X0, BF_DIG_X1 = 0.50, 1.90      # [PH] digit window
BF_DIG_Y0, BF_DIG_Y1 = -0.92, -0.12    # [PH]
BF_PIP_W = 0.26             # [ASM] FULL IN / FULL OUT lamp beside the digit window
BF_CL_Y0, BF_CL_Y1 = -1.85, -1.02      # [PH] cluster frame
BF_COL_X = ((0.12, 1.18), (1.22, 2.28))  # [PH] two cluster columns

# Browns Ferry LPRM strip. [PH browns_ferry_fcd]: plate with 4 rows x 2 windows, the left
# (LO) windows lit white, a pale label plate beneath with the coordinate.
LP_W, LP_H = 1.70, 3.30     # [PH/ASM] footprint
LP_TOP = 0.35               # [ASM] room above the plate for an SRM/IRM label
LP_PLATE_H = 2.50           # [PH]
LP_ROW_H, LP_ROW_GAP = 0.50, 0.09
LP_LABEL_H = 0.36           # [PH] coordinate plate

# Rod select button. [PH columbia_rod_select_matrix]: small square silver-bezel buttons with a
# raised pale cap.
RS_FOOT = 1.00              # [ASM] square, matching the 1:1 component cell aspect
RS_TRAVEL = 0.08            # [SPEC] cap travel

# Guarded yellow pushbutton. [PH columbia_rod_select_matrix right strip]: big square yellow
# illuminated cap, thin dark bezel, clear guard cube with a pale rim.
GY_FOOT = 2.00              # [ASM] matches the other pushbutton models (2.0 in square)
GY_TRAVEL = 0.15            # [ASM] as pb_*
GY_GUARD_TOP = 0.95         # [PH] guard top, ~0.95 in off the strip (a "cube" 1.7 in wide)

# Palette (sRGB), PANEL-MODELS-SPEC.md
SILVER = "B8BCC0"           # bright metal trim
CHROME = "C8CCD0"
BEZEL = "202224"            # dark bezel
BLACK = "0C0C0C"
LENS = "F0F0E0"             # neutral white lamp lens, tinted by the game
AMBER = "FFB000"            # lamp amber of the spec palette
BF_PLATE = "CBC5B3"         # Browns Ferry module plate (the board is lighter beige)
BF_FRAME = "8E918F"         # window frame metal
BF_FLOOR = "08090A"
BF_GLASS = "101416"
BF_DARKWIN = "4A4D50"
LP_LABEL = "EDE3B0"         # pale yellow coordinate plate
GUARD_RIM = "B9BDC0"
GUARD_GLASS = "DCE7EB"
STRIP_BLACK = "1A1A1A"


def mats():
    return {
        "silver": px.material("silver", SILVER, 0.45, 0.4),
        "chrome": px.material("chrome", CHROME, 0.3, 0.5),
        "bezel": px.material("bezel", BEZEL, 0.55),
        "black": px.material("black", BLACK, 0.4),
        "lens": px.material("lens_white", LENS, 0.4),
        "amber": px.material("lens_amber", AMBER, 0.4),
        "plate": px.material("bf_plate", BF_PLATE, 0.8),
        "frame": px.material("bf_frame", BF_FRAME, 0.5, 0.3),
        "floor": px.material("bf_floor", BF_FLOOR, 0.5),
        "glass": px.material("bf_glass", BF_GLASS, 0.15),
        "darkwin": px.material("bf_darkwin", BF_DARKWIN, 0.5),
        "label": px.material("lp_label", LP_LABEL, 0.7),
        "rim": px.material("guard_rim", GUARD_RIM, 0.5, 0.3),
        "gglass": px.material("guard_glass", GUARD_GLASS, 0.1),
        "strip": px.material("strip_black", STRIP_BLACK, 0.55),
    }


# --------------------------------------------------------------------------
# Models
# --------------------------------------------------------------------------
MODELS = {}


def model(name, w, h, source, max_faces, kind):
    def deco(fn):
        MODELS[name] = (fn, w, h, source, max_faces, kind)
        return fn
    return deco


def tile_rim_profile(floor_window):
    """Silver rim, then the black bezel stepping down; the window floor closes it if asked."""
    r, b, w = TILE_RIM, TILE_BEZEL, TILE_WIN
    prof = [(r, r, 0.0), (r, r, 0.11), (r - 0.03, r - 0.03, TILE_Z_RIM), (b, b, TILE_Z_RIM),
            (b, b, TILE_Z_BEZEL)]
    if floor_window:
        prof += [(w + 0.015, w + 0.015, TILE_Z_BEZEL), (w, w, TILE_Z_FLOOR)]
    return prof


@model("fcd_tile_columbia", TILE_FOOT, TILE_FOOT, SRC_COL_FCD, 40, "tile")
def fcd_tile_columbia():
    px.clear_scene()
    M = mats()
    cx, cy = TILE_FOOT / 2, -TILE_FOOT / 2
    g = Geo(M["silver"], M["bezel"], M["black"])
    rlathe(g, cx, cy, tile_rim_profile(True), [0, 0, 0, 1, 1, 1], ngon_cap=True, cap_mat=2)
    g.build("bezel", part="bezel")
    for part, y0, y1 in (("upper", cy + TILE_SPLIT, cy + TILE_WIN), ("lower", cy - TILE_WIN, cy - TILE_SPLIT)):
        g = Geo(M["lens"])
        box(g, cx - TILE_WIN, y0, TILE_Z_FLOOR, cx + TILE_WIN, y1, TILE_Z_LENS, 0)
        g.build(part, part=part, role="lamp", tint="white")
    h_txt = 0.17
    px.label("upper", cx, cy + (TILE_WIN + TILE_SPLIT) / 2, TILE_Z_LENS + 0.002, h_txt)
    px.label("lower", cx, cy - (TILE_WIN + TILE_SPLIT) / 2, TILE_Z_LENS + 0.002, h_txt)


@model("fcd_tile_blank", TILE_FOOT, TILE_FOOT, SRC_COL_FCD, 20, "blank")
def fcd_tile_blank():
    px.clear_scene()
    M = mats()
    cx, cy = TILE_FOOT / 2, -TILE_FOOT / 2
    prof = tile_rim_profile(False)
    prof[-1] = (TILE_BEZEL, TILE_BEZEL, 0.09)
    g = Geo(M["silver"], M["bezel"])
    rlathe(g, cx, cy, prof, [0, 0, 0, 1])
    g.build("bezel", part="bezel")
    g = Geo(M["black"])
    rlathe(g, cx, cy, [(TILE_BEZEL, TILE_BEZEL, 0.09)], 0, ngon_cap=True)
    g.build("face", part="face")


def slab(g, x0, y0, x1, y1, z0, z1, m, sides=True):
    box(g, x0, y0, z0, x1, y1, z1, m, sides=sides)


@model("fcd_bf_rod_module", BF_W, BF_H, SRC_BF_FCD, 120, "bf")
def fcd_bf_rod_module():
    px.clear_scene()
    M = mats()
    cx, cy = BF_W / 2, -BF_H / 2
    hw, hh = BF_W / 2, BF_H / 2
    # plate, window frames, digit floor: part "body"
    g = Geo(M["plate"], M["frame"], M["floor"])
    rlathe(g, cx, cy, [(hw, hh, 0.0), (hw, hh, 0.06), (hw - 0.03, hh - 0.03, 0.08)], 0, ngon_cap=True)
    slab(g, BF_DIG_X0 - 0.03, BF_DIG_Y0 - 0.03, BF_DIG_X1 + 0.03, BF_DIG_Y1 + 0.03, 0.075, 0.09, 2)
    for x0, x1 in BF_COL_X:
        slab(g, x0, BF_CL_Y0, x1, BF_CL_Y1, 0.075, 0.095, 1)
    pip_y0, pip_y1 = (BF_DIG_Y0 + BF_DIG_Y1) / 2 - 0.25, (BF_DIG_Y0 + BF_DIG_Y1) / 2 + 0.25
    pip_x = {"full_in": (0.12, 0.12 + BF_PIP_W + 0.04),
             "full_out": (BF_W - 0.12 - BF_PIP_W - 0.04, BF_W - 0.12)}
    for x0, x1 in pip_x.values():
        slab(g, x0, pip_y0, x1, pip_y1, 0.075, 0.095, 1)
    g.build("body")
    # digit window glass (translucent, drawn last); digits are drawn by the game behind it
    g = Geo(M["glass"])
    slab(g, BF_DIG_X0, BF_DIG_Y0, BF_DIG_X1, BF_DIG_Y1, 0.09, 0.115, 0)
    g.build("digits", part="digits", role="glass")
    # lamp lenses
    lamps = {}
    ymid = (BF_CL_Y0 + BF_CL_Y1) / 2
    ya = (BF_CL_Y1 - 0.03, ymid + 0.015)       # upper half window (top, bottom)
    yb = (ymid - 0.015, BF_CL_Y0 + 0.03)       # lower half window
    for (x0, x1), (up, lo) in zip(BF_COL_X, (("accum", "scram"), ("select", "drift"))):
        lamps[up] = (x0 + 0.03, ya[1], x1 - 0.03, ya[0])
        lamps[lo] = (x0 + 0.03, yb[1], x1 - 0.03, yb[0])
    for name, (x0, x1) in pip_x.items():
        lamps[name] = (x0 + 0.02, pip_y0 + 0.02, x1 - 0.02, pip_y1 - 0.02)
    for name, (x0, y0, x1, y1) in lamps.items():
        g = Geo(M["lens"])
        slab(g, x0, y0, x1, y1, 0.095, 0.11, 0)
        g.build(name, part=name, role="lamp", tint="white")
    zt = 0.114
    px.label("digits", (BF_DIG_X0 + BF_DIG_X1) / 2, (BF_DIG_Y0 + BF_DIG_Y1) / 2, zt + 0.003, 0.56)
    for name, (x0, y0, x1, y1) in lamps.items():
        h = 0.11 if name in pip_x else 0.17
        px.label(name, (x0 + x1) / 2, (y0 + y1) / 2, 0.112, h)


@model("fcd_bf_lprm_strip", LP_W, LP_H, SRC_BF_FCD, 80, "lprm")
def fcd_bf_lprm_strip(clear=True):
    if clear:
        px.clear_scene()
    M = mats()
    cx = LP_W / 2
    ptop = -LP_TOP
    pbot = ptop - LP_PLATE_H
    cy = (ptop + pbot) / 2
    g = Geo(M["plate"], M["frame"], M["darkwin"], M["label"])
    hw, hh = LP_W / 2, LP_PLATE_H / 2
    rlathe(g, cx, cy, [(hw, hh, 0.0), (hw, hh, 0.045), (hw - 0.03, hh - 0.03, 0.06)], 0, ngon_cap=True)
    cols = ((0.10, 0.82), (0.88, 1.60))
    rows = []
    y = ptop - 0.12
    for _ in range(4):
        rows.append((y - LP_ROW_H, y))
        y -= LP_ROW_H + LP_ROW_GAP
    for y0, y1 in rows:
        slab(g, cols[0][0], y0, cols[0][1], y1, 0.055, 0.075, 1)
        # the HI window beside each level lamp: dark, unlit (not a lamp part)
        pg_quad(g, cols[1][0], y0, cols[1][1], y1, 0.063, 2)
    # coordinate plate under the strip
    ly1 = pbot - 0.07
    slab(g, 0.15, ly1 - LP_LABEL_H, LP_W - 0.15, ly1, 0.0, 0.04, 3)
    g.build("body")
    for k, (y0, y1) in enumerate(rows):
        g = Geo(M["lens"])
        slab(g, cols[0][0] + 0.03, y0 + 0.03, cols[0][1] - 0.03, y1 - 0.03, 0.075, 0.09, 0)
        g.build("lvl_" + "abcd"[k], part="lvl_" + "abcd"[k], role="lamp", tint="white")
    px.label("coord", cx, ly1 - LP_LABEL_H / 2, 0.043, 0.20)
    px.label("top", cx, -LP_TOP / 2, 0.002, 0.20)


def pg_quad(g, x0, y0, x1, y1, z, m):
    a, b = g.vert((x0, y0, z)), g.vert((x1, y0, z))
    c, d = g.vert((x1, y1, z)), g.vert((x0, y1, z))
    g.face((a, b, c, d), m)


@model("rod_select_button", RS_FOOT, RS_FOOT, SRC_COL_BTN, 40, "press")
def rod_select_button():
    px.clear_scene()
    M = mats()
    cx, cy = RS_FOOT / 2, -RS_FOOT / 2
    g = Geo(M["silver"], M["bezel"], M["black"])
    rlathe(g, cx, cy, [(0.47, 0.47, 0.0), (0.47, 0.47, 0.12), (0.44, 0.44, 0.135), (0.39, 0.39, 0.135),
                       (0.39, 0.39, 0.05)], [0, 0, 0, 1], ngon_cap=True, cap_mat=2)
    g.build("bezel", part="bezel")
    g = Geo(M["lens"])
    rlathe(g, cx, cy, [(0.365, 0.365, 0.06), (0.365, 0.365, 0.22), (0.335, 0.335, 0.245)], 0, ngon_cap=True)
    g.build("cap", part="cap", role="lamp", tint="white", travel=RS_TRAVEL)
    px.label("legend", cx, cy, 0.247, 0.17)


@model("pb_guarded_yellow", GY_FOOT, GY_FOOT, SRC_COL_BTN, 150, "press")
def pb_guarded_yellow():
    px.clear_scene()
    M = mats()
    cx, cy = GY_FOOT / 2, -GY_FOOT / 2
    g = Geo(M["strip"], M["rim"], M["black"])
    rlathe(g, cx, cy, [(0.92, 0.92, 0.0), (0.92, 0.92, 0.12), (0.88, 0.88, 0.14), (0.76, 0.76, 0.14),
                       (0.72, 0.72, 0.08)], 0, ngon_cap=True, cap_mat=2)
    # guard: base ring, top ring and four corner posts (the clear cube's frame)
    gz0, gz1 = 0.14, GY_GUARD_TOP
    for z0, z1 in ((gz0, gz0 + 0.12), (gz1 - 0.10, gz1)):
        rlathe(g, cx, cy, [(0.86, 0.86, z0), (0.86, 0.86, z1), (0.79, 0.79, z1), (0.79, 0.79, z0)], 1)
    for sx in (-1, 1):
        for sy in (-1, 1):
            prism(g, rect_pts(cx + sx * 0.82, cy + sy * 0.82, 0.045, 0.045), gz0 + 0.12, gz1 - 0.10, 1,
                  top=False)
    g.build("bezel", part="bezel")
    g = Geo(M["gglass"])
    box(g, cx - 0.83, cy - 0.83, gz0, cx + 0.83, cy + 0.83, gz1 - 0.02, 0)
    g.build("guard", part="guard", role="glass")
    g = Geo(M["amber"])
    rlathe(g, cx, cy, [(0.70, 0.70, 0.06), (0.70, 0.70, 0.50), (0.65, 0.65, 0.57)], 0, ngon_cap=True)
    g.build("cap", part="cap", role="lamp", tint="amber", travel=GY_TRAVEL)
    px.label("legend", cx, cy, 0.573, 0.24)


# --------------------------------------------------------------------------
# Previews (never exported)
# --------------------------------------------------------------------------
TINT = {"white": "F0F0E0", "amber": "FFB000", "red": "E02020", "blue": "3070E0", "green": "20B040"}


def view_pair(base, w, h, res=640, views=("front", "three_quarter", "side")):
    return px.preview(base, w, h, views=views, res=res)


def render_default(name, w, h, kind):
    os.makedirs(PREVIEW_DIR, exist_ok=True)
    base = os.path.join(PREVIEW_DIR, name)
    res = 900
    pg.ghost_glass()
    outs = view_pair(base, w, h, res)
    if kind in ("tile", "bf", "lprm", "press"):
        for state in ("off", "lit"):
            pg.set_lamps(state)
            outs += view_pair(base + "_" + state, w, h, res, views=("front", "three_quarter"))
        pg.set_lamps("authored")
    if kind == "press":
        cap = bpy.data.objects["cap"]
        cap.location.z -= cap["bwr_travel"]
        pg.set_lamps("lit")
        outs += view_pair(base + "_pressed", w, h, res, views=("three_quarter", "side"))
        pg.set_lamps("authored")
        cap.location.z += cap["bwr_travel"]
    return outs


SAMPLES = {
    "fcd_tile_columbia": {"upper": ("18-31", "202020", 0), "lower": ("DRIFT", "202020", 0)},
    "fcd_bf_rod_module": {"digits": ("48", "FF2A10", 4.0), "accum": ("ACCUM", "202020", 0),
                          "scram": ("SCRAM", "202020", 0), "select": ("20-31", "202020", 0),
                          "drift": ("DRIFT", "202020", 0), "full_in": ("IN", "202020", 0),
                          "full_out": ("OUT", "202020", 0)},
    "fcd_bf_lprm_strip": {"coord": ("24-49", "101010", 0), "top": ("SRM-A", "101010", 0)},
    "rod_select_button": {"legend": ("18-31", "202020", 0)},
    "pb_guarded_yellow": {"legend": ("INSERT", "202020", 0)},
}


def render_legends(name, w, h):
    if name not in SAMPLES:
        return []
    made = pg.sample_legends(SAMPLES[name])
    pg.ghost_glass()
    outs = view_pair(os.path.join(PREVIEW_DIR, name + "_legends"), w, h, 900, views=("front", "three_quarter"))
    for o in made:
        bpy.data.objects.remove(o)
    return outs


# --- tiled grids ------------------------------------------------------------
def lens_mats():
    """Per-tint lens materials: off (dimmed as the game dims an unlit lamp) and lit (emissive)."""
    out = {}
    for tint, hx in TINT.items():
        for state in ("off", "lit"):
            m = px.material(f"pv_{tint}_{state}", hx, 0.4)
            pg.set_lamp_mat(m, state, dim=0.42, strength=1.5)
            out[tint, state] = m
    return out


def mesh_parts():
    return {o["bwr_part"]: o for o in bpy.context.scene.objects if o.type == "MESH"}


def tiled_columbia(n_rods, tag):
    """n_rods x n_rods rods, each a 2 x 2 block (A B / C D) mirrored by column / row parity,
    so the blank D tiles of four neighbouring rods meet in a 2 x 2 black cluster."""
    fcd_tile_columbia()
    col = mesh_parts()
    templates = list(col.values())
    M = mats()
    cx, cy = TILE_FOOT / 2, -TILE_FOOT / 2
    prof = tile_rim_profile(False)
    prof[-1] = (TILE_BEZEL, TILE_BEZEL, 0.09)
    g = Geo(M["silver"], M["bezel"])
    rlathe(g, cx, cy, prof, [0, 0, 0, 1])
    bl_bezel = g.build("blank_bezel")
    g = Geo(M["black"])
    rlathe(g, cx, cy, [(TILE_BEZEL, TILE_BEZEL, 0.09)], 0, ngon_cap=True)
    bl_face = g.build("blank_face")
    templates += [bl_bezel, bl_face]
    sm = lens_mats()
    n = 2 * n_rods
    pg.plane(-1.0, -n - 1.0, n + 1.0, 1.0, -0.02, px.material("backplate", "101012", 0.7), "backplate")

    def rod_state(i, j):
        k = (i * 7 + j * 3) % 5
        return {"full_out": k in (0, 1, 2, 4), "full_in": k == 3, "selected": (i, j) == (1, 1),
                "drift": (i, j) == (2, 0), "accum": (i, j) == (0, 2), "scram": False}

    texts = []
    for j in range(n_rods):
        for i in range(n_rods):
            st = rod_state(i, j)
            coord = "%02d-%02d" % (18 + 4 * i, 31 - 4 * j)
            for ty in (0, 1):
                for tx in (0, 1):
                    kind = {(0, 0): "A", (1, 0): "B", (0, 1): "C", (1, 1): "D"}[tx, ty]
                    lx = 1 - tx if i % 2 else tx
                    ly = 1 - ty if j % 2 else ty
                    dx, dy = 2 * i + lx, -(2 * j + ly)
                    if kind == "D":
                        pg.clone(bl_bezel, dx, dy)
                        pg.clone(bl_face, dx, dy)
                        continue
                    pg.clone(col["bezel"], dx, dy)
                    if kind == "A":
                        tints, keys, txt = ("white", "amber"), ("selected", "drift"), (coord, "DRIFT")
                    elif kind == "B":
                        tints, keys, txt = ("white", "red"), ("full_in", "full_out"), ("FULL\nIN", "FULL OUT")
                    else:
                        tints, keys, txt = ("amber", "blue"), ("accum", "scram"), ("ACCUM", "SCRAM")
                    for part, tint, key, t in zip(("upper", "lower"), tints, keys, txt):
                        pg.clone(col[part], dx, dy, material=sm[tint, "lit" if st[key] else "off"],
                                 name=f"{part}_{i}_{j}_{kind}")
                        yy = cy + (0.19 if part == "upper" else -0.19)
                        texts.append((t, cx + dx, yy + dy, TILE_Z_LENS + 0.004, 0.13 if "\n" in t else 0.15))
    for t, x, y, z, h in texts:
        pg.add_text(t, x, y, z, h, "1A1A1A", 0.0, width=0.62)
    dot_mat = px.material("lprm_dot", "E8D020", 0.5)
    for dj in range(0, n_rods - 1, 2):
        for di in range(0, n_rods - 1, 2):
            bpy.ops.mesh.primitive_cylinder_add(vertices=16, radius=0.09, depth=0.02,
                                                location=(2 * (di + 1), -2 * (dj + 1), TILE_Z_RIM + 0.012))
            bpy.context.active_object.data.materials.append(dot_mat)
    for t in templates:
        t.hide_render = True
    return px.preview(os.path.join(PREVIEW_DIR, f"fcd_tile_columbia_tiled_{tag}"), n, n,
                      views=("front", "three_quarter"), res=1400)


def render_bf_grid():
    """Browns Ferry preview: two module columns, an LPRM strip column, two more module columns,
    three module rows, with the grid lines and edge labels of the photo."""
    fcd_bf_rod_module()
    mod = mesh_parts()
    mod_labels = {e["bwr_label"]: e.location.copy() for e in bpy.context.scene.objects
                  if e.type == "EMPTY" and "bwr_label" in e}
    before = set(bpy.context.scene.objects)
    fcd_bf_lprm_strip(clear=False)
    new = [o for o in bpy.context.scene.objects if o not in before]
    lp = {o["bwr_part"]: o for o in new if o.type == "MESH"}
    lp_labels = {e["bwr_label"]: e.location.copy() for e in new if e.type == "EMPTY"}
    sm = lens_mats()
    pitch_x, pitch_y, strip_gap = 2.62, 2.17, 2.15
    cols_x = [0.0, pitch_x, 2 * pitch_x + strip_gap, 3 * pitch_x + strip_gap]
    n_rows = 3
    total_w = cols_x[-1] + BF_W
    total_h = n_rows * pitch_y
    pg.plane(-1.2, -total_h - 3.0, total_w + 1.2, 0.6, -0.03, px.material("bf_board", "D8D2C0", 0.8), "board")
    line_m = px.material("bf_line", "111111", 0.7)
    for r in range(n_rows):
        yy = -(r * pitch_y) - BF_H * 0.5
        pg.plane(-1.0, yy - 0.04, 0.0, yy + 0.04, -0.02, line_m, "line")
        pg.add_text("%02d" % (59 - 4 * r), -1.35, yy, 0.0, 0.2, "111111", 0.0, name="rowlbl")
    for k, x in enumerate(cols_x):
        pg.plane(x + BF_W / 2 - 0.04, -total_h - 0.8, x + BF_W / 2 + 0.04, -total_h, -0.02, line_m, "line")
        pg.add_text(("02", "06", "10", "14")[k], x + BF_W / 2, -total_h - 1.0, 0.0, 0.2, "111111", 0.0,
                    name="collbl")
    digits = [("12", "FFB000"), ("48", "FF2A10"), ("48", "FF2A10"), ("00", "30FF30"), ("08", "FFB000"),
              ("48", "FF2A10"), ("00", "30FF30"), ("48", "FF2A10"), ("12", "FFB000"), ("48", "FF2A10"),
              ("00", "30FF30"), ("48", "FF2A10")]
    for r in range(n_rows):
        for c, x in enumerate(cols_x):
            y = -(r * pitch_y)
            for part, o in mod.items():
                mat = None
                if o.get("bwr_role") == "lamp":
                    lit = (part == "select" and (r, c) == (1, 1)) or (part == "drift" and (r, c) == (2, 0)) \
                        or (part == "accum" and (r, c) == (0, 3))
                    mat = sm["white", "lit" if lit else "off"]
                pg.clone(o, x, y, material=mat, name=f"{part}_{r}_{c}")
            txt, colr = digits[(r * 4 + c) % len(digits)]
            lbl = mod_labels["digits"]
            pg.add_text(txt, lbl.x + x, lbl.y + y, lbl.z + 0.002, 0.56, colr, 4.5, name="digits")
            for part, t in (("accum", "ACCUM"), ("scram", "SCRAM"),
                            ("select", "%02d-%02d" % (20 + 4 * c, 31 - 4 * r)), ("drift", "DRIFT")):
                l = mod_labels[part]
                pg.add_text(t, l.x + x, l.y + y, l.z + 0.003, 0.15, "1A1A1A", 0.0, name="l_" + part, width=0.9)
    sx = 2 * pitch_x + (strip_gap - LP_W) / 2 - 0.05
    levels = {0: (1, 1, 0, 1), 1: (1, 0, 0, 1)}
    for k, (coord, top) in enumerate((("24-49", "SRM-A"), ("24-41", ""))):
        y = -(k * (LP_H + 0.1))
        for part, o in lp.items():
            mat = None
            if o.get("bwr_role") == "lamp":
                mat = sm["white", "lit" if levels[k]["abcd".index(part[-1])] else "off"]
            pg.clone(o, sx, y, material=mat, name=f"{part}_{k}")
        c = lp_labels["coord"]
        pg.add_text(coord, c.x + sx, c.y + y, c.z + 0.003, 0.20, "101010", 0.0, name="coord")
        if top:
            t = lp_labels["top"]
            pg.add_text(top, t.x + sx, t.y + y, 0.003, 0.2, "101010", 0.0, name="top")
    for o in list(mod.values()) + list(lp.values()):
        o.hide_render = True
    pg.ghost_glass()
    w, h = total_w, total_h + 3.4
    return px.preview(os.path.join(PREVIEW_DIR, "fcd_bf_modules_grid"), w, h, views=("front", "three_quarter"),
                      res=2000)


def main():
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    only = set(argv[argv.index("--only") + 1].split(",")) if "--only" in argv else None
    previews = "--no-preview" not in argv
    os.makedirs(PREVIEW_DIR, exist_ok=True)
    for name, (fn, w, h, source, max_faces, kind) in MODELS.items():
        if only and name not in only:
            continue
        fn()
        px.export(name, w, h, source, max_faces=max_faces)
        if previews:
            render_default(name, w, h, kind)
            render_legends(name, w, h)
    if previews and (not only or "grids" in only):
        tiled_columbia(3, "6x6")
        tiled_columbia(4, "8x8")
        render_bf_grid()


main()
