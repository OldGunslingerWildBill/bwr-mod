"""Family: annunciator lamp box window and the operator's annunciator control station,
built from parameters and exported through panel_export.

Run headless from the repo root (either way):

    blender --background --factory-startup --python art/models/panel/annunciator/build.py
    python3 art/models/panel/annunciator/build.py            (bpy from PyPI)
    ... build.py -- --only ann_window --no-preview

Exports mod/src/main/resources/assets/bwr/models/panel/<id>.json for every model and
renders previews into tmp/panel-models/annunciator/ (git-ignored), or into the folder
named by BWR_PANEL_PREVIEW_DIR. On a machine without a GPU set
BWR_PANEL_PREVIEW_ENGINE=CYCLES (see panel_export.preview).

Conventions are those of panel_export: inches, panel face Z = 0, +Z toward the
operator, +Y up, footprint top-left at (0, 0) extending to (w, -h). The helpers below
take y DOWN the face (as the game does) and flip it when a vertex is made.

READ THE README FIRST: which annunciator make Columbia's boards carry could not be
established, so this is a generic lamp box, and only the window size has a source.
"""
import math
import os
import sys

import bpy
from mathutils import Vector

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
import panel_export as px  # noqa: E402

PREVIEW_DIR = os.environ.get("BWR_PANEL_PREVIEW_DIR") or os.path.join(px.REPO, "tmp", "panel-models", "annunciator")

# --------------------------------------------------------------------------
# DIMENSIONS (inches). Sources:
#   [DOC]  CONTROL-PANELS.md size table: window tile 2.8125 x 2.625 in, cited there to
#          CR-PANELS-BWR-RESEARCH.md (that file is not in the repository, so the
#          primary source behind it could not be re-read).
#   [GAME] Chosen so the model lines up with what the game already draws: the
#          elevations of AnnunciatorBox.java (tile 1.0, bezel lip 1.3, in 1/16 block)
#          and the button layout of AnnunciatorControlModel.java.
#   [CAT]  GE Control Catalog Sec. 9 (rev. 7/13) CR104P 30.5 mm oiltight operators, as
#          used by ../pushbuttons/build.py (page cited per value there).
#   [ASM]  Assumed: typical lamp box / control station proportions, no source found.
# --------------------------------------------------------------------------
TILE_W = 2.8125          # [DOC] one window tile, wide
TILE_H = 2.625           # [DOC] one window tile, high
FRAME = 0.375            # [ASM] trim frame of the lamp box round the outermost windows
LENS_Z = 0.50            # [ASM] front of the lens above the panel face (the cabinet stands proud)
LENS_BEVEL = 0.06        # [ASM] broken edge of the lens
LENS_EDGE_Z = 0.44       # [ASM] where the lens meets the frame, behind its front
FRAME_Z = 0.65           # [GAME] front of the trim frame: 1.3 x the lens, as the game's bezel lip
FRAME_CHAMFER = 0.06     # [ASM] broken outer edge of the frame
FRAME_LAND = 0.30        # [ASM] where the frame's flat front ends and its inner slope starts
WIN_W = TILE_W + 2 * FRAME
WIN_H = TILE_H + 2 * FRAME

# Control station: four pushbuttons in a row, ACK / RESET / SILENCE / TEST, on one plate.
ST_W = 10.0              # [GAME] 4 : 1, the proportion of the module's sizes (24x6, 16x4, 32x8)
ST_H = 2.5               # [GAME]
ST_PITCH = ST_W / 4      # [GAME] one button per quarter of the width (a click picks the quarter)
ST_BUTTON_Y = 0.825      # [GAME] button centres: the middle of the top 60 %, 3 % down
ST_LEGEND_Y = 1.8625     # [GAME] centre of the legend row (63 % to 86 % of the height)
ST_LEGEND_H = 0.28       # [ASM] legend cap height
ST_PLATE_T = 0.06        # [ASM] station plate thickness
ST_PLATE_M = 0.03        # [ASM] plate inset from the footprint
ST_EDGE = 0.05           # [ASM] plate edge chamfer
ST_SCREW_R = 0.085       # [ASM] plate screws
OCT_AF = 1.36            # [CAT, reduced] octagonal mounting ring across flats (1.42 on the drawing; kept
                         #        inside the 1.5 in button row)
OCT_H = 0.30             # [CAT] ring height
LOCK_R = 0.70            # [CAT] round locking plate under the ring
LOCK_T = 0.05            # [CAT]
COLLAR_R = 0.60          # [CAT] round collar in front of the ring
COLLAR_IN = 0.525        # [CAT] collar bore
CAP_R = 0.49             # [CAT] flush cap
CAP_FRONT = 0.53         # [CAT] flush operator front, from the panel
CAP_TRAVEL = 0.15        # [GAME] stroke used by the game while held (as ../pushbuttons)

SRC_WINDOW = ("Generic annunciator lamp box window, not a maker's drawing: tile 2.8125 x 2.625 in from "
              "CONTROL-PANELS.md (CR-PANELS-BWR-RESEARCH.md); trim frame and depths assumed, elevations "
              "matched to AnnunciatorBox.java (see annunciator/README.md)")
SRC_STATION = ("Generic annunciator control station, not a maker's drawing: four 30.5 mm oiltight flush "
               "pushbuttons (GE Control Catalog Sec. 9 CR104P outline p.9-49, as ../pushbuttons) on one plate, "
               "laid out as AnnunciatorControlModel.java; plate assumed (see annunciator/README.md)")

# Palette (sRGB). The frame colours are the ones the procedural window box already uses
# (AnnunciatorModel.BOX_LIP / BOX_DARK), so a model window joins a box of them without a seam.
FRAME_RGB = "3A413E"
FRAME_SLOPE_RGB = "232827"
LENS_RGB = "F0F0E0"      # white translucent lens (PANEL-MODELS-SPEC palette); the game recolours it
PLATE_RGB = "23272C"     # station plate: ModelDraw.HOUSING, so the game's light legends read on it
PLATE_EDGE_RGB = "3A4046"
CHROME = "C8CCD0"
HOUSING = "2A2C2E"
SCREW = "B8BCC0"
# Cap colours of the procedural module (AnnunciatorControlModel.CAPS): white, blue, amber, green.
BUTTONS = (("ack", "E6E2D0"), ("reset", "4FA0E0"), ("silence", "E8A820"), ("test", "30B050"))


# --------------------------------------------------------------------------
# Mesh helpers (y is DOWN the face here; a vertex is flipped to Blender's y up)
# --------------------------------------------------------------------------
class Geo:
    """Accumulates one object's vertices and faces with per-face material slots."""

    def __init__(self, *mats):
        self.mats = list(mats)
        self.v, self.f, self.fm = [], [], []

    def face(self, pts, out, m=0):
        """A face through pts [(x, y_down, z), ...], wound so its normal points along `out` (y down too)."""
        clean = []
        for p in pts:
            p = (float(p[0]), -float(p[1]), float(p[2]))
            if not clean or (Vector(p) - Vector(clean[-1])).length > 1e-7:
                clean.append(p)
        if len(clean) > 1 and (Vector(clean[0]) - Vector(clean[-1])).length <= 1e-7:
            clean.pop()
        if len(clean) < 3:
            return
        n = Vector((0.0, 0.0, 0.0))
        for i in range(len(clean)):
            a, b = Vector(clean[i]), Vector(clean[(i + 1) % len(clean)])
            n += a.cross(b)
        if n.dot(Vector((out[0], -out[1], out[2]))) < 0:
            clean.reverse()
        base = len(self.v)
        self.v.extend(clean)
        self.f.append(tuple(range(base, base + len(clean))))
        self.fm.append(m)

    def build(self, name, part="body", role="paint", **tagkw):
        me = bpy.data.meshes.new(name)
        me.from_pydata(self.v, [], self.f)
        for m in self.mats:
            me.materials.append(m)
        for poly, mi in zip(me.polygons, self.fm):
            poly.material_index = mi
        me.update()
        obj = bpy.data.objects.new(name, me)
        bpy.context.scene.collection.objects.link(obj)
        px.tag(obj, part=part, role=role, **tagkw)
        return obj


def lathe(g, cx, cy, profile, segs, mat=0, phase=0.0):
    """Surface of revolution about the Z axis through (cx, cy_down). profile: [(r, z), ...] from the
    outside wall upward and then inward over the front; a final r == 0 closes the centre.
    mat: one slot, or one per profile segment."""
    for i in range(len(profile) - 1):
        (r0, z0), (r1, z1) = profile[i], profile[i + 1]
        m = mat[i] if isinstance(mat, (list, tuple)) else mat
        # Outward normal of the segment in the (r, z) half plane.
        nr, nz = z1 - z0, r0 - r1
        for j in range(segs):
            a0 = phase + 2 * math.pi * j / segs
            a1 = phase + 2 * math.pi * (j + 1) / segs
            am = (a0 + a1) / 2
            pts = [(cx + r0 * math.cos(a0), cy + r0 * math.sin(a0), z0),
                   (cx + r0 * math.cos(a1), cy + r0 * math.sin(a1), z0),
                   (cx + r1 * math.cos(a1), cy + r1 * math.sin(a1), z1),
                   (cx + r1 * math.cos(a0), cy + r1 * math.sin(a0), z1)]
            g.face(pts, (nr * math.cos(am), nr * math.sin(am), nz), m)


# --------------------------------------------------------------------------
# The window
# --------------------------------------------------------------------------
# Cross-section of the trim frame from its outer edge inward: (distance in, z).
FRAME_PROFILE = [(0.0, 0.0), (0.0, FRAME_Z - FRAME_CHAMFER), (FRAME_CHAMFER, FRAME_Z), (FRAME_LAND, FRAME_Z),
                 (FRAME, LENS_EDGE_Z)]


def window_materials():
    return {"frame": px.material("frame", FRAME_RGB, 0.6), "slope": px.material("frame_slope", FRAME_SLOPE_RGB, 0.7),
            "lens": px.material("lens_white", LENS_RGB, 0.4)}


def frame_side(M, name, x0, y0, x1, y1, ux, uy):
    """One straight run of the frame: its outer edge from (x0, y0) to (x1, y1), (ux, uy) pointing inward."""
    g = Geo(M["frame"], M["slope"])
    for i in range(len(FRAME_PROFILE) - 1):
        (d0, z0), (d1, z1) = FRAME_PROFILE[i], FRAME_PROFILE[i + 1]
        nd, nz = -(z1 - z0), d1 - d0
        pts = [(x0 + d0 * ux, y0 + d0 * uy, z0), (x1 + d0 * ux, y1 + d0 * uy, z0),
               (x1 + d1 * ux, y1 + d1 * uy, z1), (x0 + d1 * ux, y0 + d1 * uy, z1)]
        g.face(pts, (nd * ux, nd * uy, nz), 1 if i == len(FRAME_PROFILE) - 2 else 0)
    return g.build(name, part=name)


def frame_corner(M, name, cx, cy, sx, sy):
    """A mitred corner of the frame: (cx, cy) its outer corner, (sx, sy) = +-1 pointing into the window."""
    g = Geo(M["frame"], M["slope"])
    for i in range(len(FRAME_PROFILE) - 1):
        (d0, z0), (d1, z1) = FRAME_PROFILE[i], FRAME_PROFILE[i + 1]
        nd, nz = -(z1 - z0), d1 - d0
        m = 1 if i == len(FRAME_PROFILE) - 2 else 0
        # The arm that runs along x (its outer edge is the horizontal side of the box) ...
        g.face([(cx + sx * d0, cy + sy * d0, z0), (cx + sx * FRAME, cy + sy * d0, z0),
                (cx + sx * FRAME, cy + sy * d1, z1), (cx + sx * d1, cy + sy * d1, z1)], (0, nd * sy, nz), m)
        # ... and the arm that runs along y.
        g.face([(cx + sx * d0, cy + sy * d0, z0), (cx + sx * d1, cy + sy * d1, z1),
                (cx + sx * d1, cy + sy * FRAME, z1), (cx + sx * d0, cy + sy * FRAME, z0)], (nd * sx, 0, nz), m)
    return g.build(name, part=name)


def lens(M, x0, y0, x1, y1, name="lens"):
    """The window lens: a flat front with a broken edge. Its outline is the tile, exactly."""
    g = Geo(M["lens"])
    b, zf, ze = LENS_BEVEL, LENS_Z, LENS_EDGE_Z
    g.face([(x0 + b, y0 + b, zf), (x1 - b, y0 + b, zf), (x1 - b, y1 - b, zf), (x0 + b, y1 - b, zf)], (0, 0, 1))
    g.face([(x0, y0, ze), (x1, y0, ze), (x1 - b, y0 + b, zf), (x0 + b, y0 + b, zf)], (0, -1, 1))
    g.face([(x0, y1, ze), (x1, y1, ze), (x1 - b, y1 - b, zf), (x0 + b, y1 - b, zf)], (0, 1, 1))
    g.face([(x0, y0, ze), (x0, y1, ze), (x0 + b, y1 - b, zf), (x0 + b, y0 + b, zf)], (-1, 0, 1))
    g.face([(x1, y0, ze), (x1, y1, ze), (x1 - b, y1 - b, zf), (x1 - b, y0 + b, zf)], (1, 0, 1))
    return g.build(name, part="lens", role="lamp", tint="white")


def build_window():
    """One window with the whole trim frame round it: what a window standing alone is. The game
    draws only the frame parts on the sides that are not joined to a neighbour (README)."""
    px.clear_scene()
    M = window_materials()
    f, w, h = FRAME, WIN_W, WIN_H
    frame_side(M, "frame_top", f, 0, w - f, 0, 0, 1)
    frame_side(M, "frame_bottom", f, h, w - f, h, 0, -1)
    frame_side(M, "frame_left", 0, f, 0, h - f, 1, 0)
    frame_side(M, "frame_right", w, f, w, h - f, -1, 0)
    frame_corner(M, "corner_tl", 0, 0, 1, 1)
    frame_corner(M, "corner_tr", w, 0, -1, 1)
    frame_corner(M, "corner_bl", 0, h, 1, -1)
    frame_corner(M, "corner_br", w, h, -1, -1)
    lens(M, f, f, w - f, h - f)
    # Where the legend goes; the game lays out up to four centred lines over the lens itself.
    px.label("legend", w / 2, -h / 2, LENS_Z + 0.002, 0.30)


def build_box(cols, rows, lit=()):
    """PREVIEW ONLY: a box of cols x rows windows as the game assembles it from the window model's
    parts: one frame round the outside, a thin dark gap between the lenses."""
    px.clear_scene()
    M = window_materials()
    gap = 0.06 * TILE_H          # the game's gap between two windows: 6 % of the smaller side
    f = FRAME
    w, h = cols * TILE_W + 2 * f, rows * TILE_H + 2 * f
    frame_side(M, "frame_top", f, 0, w - f, 0, 0, 1)
    frame_side(M, "frame_bottom", f, h, w - f, h, 0, -1)
    frame_side(M, "frame_left", 0, f, 0, h - f, 1, 0)
    frame_side(M, "frame_right", w, f, w, h - f, -1, 0)
    frame_corner(M, "corner_tl", 0, 0, 1, 1)
    frame_corner(M, "corner_tr", w, 0, -1, 1)
    frame_corner(M, "corner_bl", 0, h, 1, -1)
    frame_corner(M, "corner_br", w, h, -1, -1)
    back = Geo(px.material("box_dark", "17191A", 0.8))
    back.face([(f, f, 0.40), (w - f, f, 0.40), (w - f, h - f, 0.40), (f, h - f, 0.40)], (0, 0, 1))
    back.build("back")
    lit_mat = px.material("lens_lit", "FFB000", 0.4)
    bsdf = lit_mat.node_tree.nodes.get("Principled BSDF")
    bsdf.inputs["Emission Color"].default_value = bsdf.inputs["Base Color"].default_value
    bsdf.inputs["Emission Strength"].default_value = 1.2
    off_mat = px.material("lens_off", "4D3500", 0.5)   # the game's unlit tile: 30 % of the colour
    for r in range(rows):
        for c in range(cols):
            x0 = f + c * TILE_W + (gap / 2 if c > 0 else 0)
            x1 = f + (c + 1) * TILE_W - (gap / 2 if c < cols - 1 else 0)
            y0 = f + r * TILE_H + (gap / 2 if r > 0 else 0)
            y1 = f + (r + 1) * TILE_H - (gap / 2 if r < rows - 1 else 0)
            obj = lens({"lens": lit_mat if (r, c) in lit else off_mat}, x0, y0, x1, y1, name=f"lens_{r}_{c}")
            obj["bwr_part"] = f"lens_{r}_{c}"
    return w, h


# --------------------------------------------------------------------------
# The control station
# --------------------------------------------------------------------------
def build_station():
    px.clear_scene()
    plate_m = px.material("plate", PLATE_RGB, 0.6)
    edge_m = px.material("plate_edge", PLATE_EDGE_RGB, 0.6)
    chrome = px.material("chrome", CHROME, 0.3, 0.5)
    housing = px.material("housing", HOUSING, 0.6)
    screw = px.material("screw", SCREW, 0.5, 0.3)
    # Plate with a broken edge.
    g = Geo(plate_m, edge_m)
    m, t, e = ST_PLATE_M, ST_PLATE_T, ST_EDGE
    x0, y0, x1, y1 = m, m, ST_W - m, ST_H - m
    g.face([(x0 + e, y0 + e, t), (x1 - e, y0 + e, t), (x1 - e, y1 - e, t), (x0 + e, y1 - e, t)], (0, 0, 1), 0)
    g.face([(x0, y0, 0), (x1, y0, 0), (x1 - e, y0 + e, t), (x0 + e, y0 + e, t)], (0, -1, 1), 1)
    g.face([(x0, y1, 0), (x1, y1, 0), (x1 - e, y1 - e, t), (x0 + e, y1 - e, t)], (0, 1, 1), 1)
    g.face([(x0, y0, 0), (x0, y1, 0), (x0 + e, y1 - e, t), (x0 + e, y0 + e, t)], (-1, 0, 1), 1)
    g.face([(x1, y0, 0), (x1, y1, 0), (x1 - e, y1 - e, t), (x1 - e, y0 + e, t)], (1, 0, 1), 1)
    g.build("plate")
    # Four plate screws, clear of the buttons and the legends.
    g = Geo(screw)
    for sx in (0.22, ST_W - 0.22):
        for sy in (0.22, ST_H - 0.22):
            lathe(g, sx, sy, [(ST_SCREW_R, t), (ST_SCREW_R, t + 0.03), (ST_SCREW_R - 0.03, t + 0.05), (0, t + 0.05)], 6)
    g.build("screws")
    rc = OCT_AF / 2 / math.cos(math.radians(22.5))
    for i, (name, rgb) in enumerate(BUTTONS):
        cx, cy = ST_PITCH * (i + 0.5), ST_BUTTON_Y
        g = Geo(chrome, housing)
        z = t
        lathe(g, cx, cy, [(LOCK_R, z), (LOCK_R, z + LOCK_T), (OCT_AF / 2 - 0.05, z + LOCK_T)], 12)
        z += LOCK_T
        lathe(g, cx, cy, [(rc - 0.03, z), (rc, z + 0.03), (rc, z + OCT_H - 0.045), (rc - 0.055, z + OCT_H),
                          (COLLAR_R - 0.02, z + OCT_H)], 8, phase=math.radians(22.5))
        z += OCT_H
        lathe(g, cx, cy, [(COLLAR_R, z), (COLLAR_R, CAP_FRONT - 0.035), (COLLAR_R - 0.035, CAP_FRONT),
                          (COLLAR_IN, CAP_FRONT), (COLLAR_IN, 0.25), (0.40, 0.25)], 12, [0, 0, 0, 0, 1])
        g.build(f"bezel_{name}", part="bezels")
        g = Geo(px.material(f"cap_{name}", rgb, 0.6))
        lathe(g, cx, cy, [(CAP_R, 0.30), (CAP_R, CAP_FRONT - 0.042), (CAP_R - 0.03, CAP_FRONT - 0.012),
                          (0, CAP_FRONT)], 12)
        g.build(f"cap_{name}", part=f"cap_{name}", travel=CAP_TRAVEL, axis=(0, 0, -1))
        px.label(name, cx, -ST_LEGEND_Y, t + 0.002, ST_LEGEND_H)


# --------------------------------------------------------------------------
# Previews
# --------------------------------------------------------------------------
def set_lens(state):
    """Preview only: the window lens as the game shows an amber window, unlit (30 %) or lit."""
    mat = bpy.data.materials["lens_white"]
    bsdf = mat.node_tree.nodes.get("Principled BSDF")
    if "authored_base" not in mat:
        mat["authored_base"] = list(bsdf.inputs["Base Color"].default_value)
    amber = px.material("tmp_amber", "FFB000").node_tree.nodes.get("Principled BSDF").inputs["Base Color"].default_value
    bsdf.inputs["Emission Strength"].default_value = 0.0
    if state == "lit":
        bsdf.inputs["Emission Color"].default_value = list(amber)
        bsdf.inputs["Emission Strength"].default_value = 1.2
        bsdf.inputs["Base Color"].default_value = (0.0, 0.0, 0.0, 1.0)
    elif state == "off":
        bsdf.inputs["Base Color"].default_value = (amber[0] * 0.3, amber[1] * 0.3, amber[2] * 0.3, 1.0)
    else:
        bsdf.inputs["Base Color"].default_value = mat["authored_base"]


def previews():
    os.makedirs(PREVIEW_DIR, exist_ok=True)
    build_window()
    base = os.path.join(PREVIEW_DIR, "ann_window")
    px.preview(base, WIN_W, WIN_H)
    for state in ("off", "lit"):
        set_lens(state)
        px.preview(base + "_" + state, WIN_W, WIN_H, views=("front", "three_quarter"))
    w, h = build_box(4, 3, lit={(0, 1), (1, 2), (2, 0)})
    px.preview(os.path.join(PREVIEW_DIR, "ann_box_4x3"), w, h, views=("front", "three_quarter"))
    build_station()
    base = os.path.join(PREVIEW_DIR, "ann_control")
    px.preview(base, ST_W, ST_H)
    for name, _ in BUTTONS:
        cap = bpy.data.objects[f"cap_{name}"]
        cap.location.z -= CAP_TRAVEL
    px.preview(base + "_pressed", ST_W, ST_H, views=("three_quarter", "side"))


MODELS = {"ann_window": (build_window, WIN_W, WIN_H, SRC_WINDOW),
          "ann_control": (build_station, ST_W, ST_H, SRC_STATION)}


def main():
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    only = set(argv[argv.index("--only") + 1].split(",")) if "--only" in argv else None
    for name, (fn, w, h, source) in MODELS.items():
        if only and name not in only:
            continue
        fn()
        px.export(name, w, h, source)
    if "--no-preview" not in argv:
        previews()


main()
