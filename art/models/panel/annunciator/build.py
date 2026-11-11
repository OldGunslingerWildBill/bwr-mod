"""Family: the annunciator tile of Columbia Generating Station's P800 annunciator boxes
and a pushbutton control station, built from parameters and exported through
panel_export.

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

READ THE README FIRST. The shapes and proportions are read off three owner-supplied
photographs (not in the repository). No real dimension and no maker is known: the
inches below are a nominal scale, only the proportions mean anything.
"""
import math
import os
import sys

import bpy
from mathutils import Matrix, Vector

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
import panel_export as px  # noqa: E402

PREVIEW_DIR = os.environ.get("BWR_PANEL_PREVIEW_DIR") or os.path.join(px.REPO, "tmp", "panel-models", "annunciator")

# --------------------------------------------------------------------------
# PROPORTIONS. Sources:
#   [P3]   Owner-supplied photograph 3: box "ANN PANEL P800-C5" on Columbia's
#          light-blue electrical board, well lit, nearly square on. Measured in pixels
#          on the photograph; every value is a share of the tile pitch.
#   [P1]   Owner-supplied photograph 1: box "ANN PANEL P800-C1", the same hardware in
#          dim light, seen from below (used to check P3, not measured).
#   [P2]   Owner-supplied photograph 2: a grey local panel of an unnamed plant with a
#          box of unsplit windows and four pushbuttons under it. The pattern for the
#          control station; NOT Columbia's control room.
#   [EYE]  By eye from the photograph named, not measured.
#   [NOM]  Nominal: a scale chosen so the model has a size in inches. No real dimension
#          of this hardware is known.
#   [GAME] Chosen so the model lines up with what the game draws: AnnunciatorBox.java
#          (the lit face at 1.0, the legend at 1.17, in 1/16 block) and the button
#          layout of AnnunciatorControlModel.java.
# --------------------------------------------------------------------------
# ---- one tile (the model ann_window). Read by the game from the model file itself.
TILE_W = 4.0             # [NOM] tile pitch, wide. By eye against the edgewise meters under the box in P3
                         #       (if those are 6.5 in wide) a tile is roughly 4 in; not a measurement.
TILE_H = 3.0             # [P3] pitch 143 x 107 px: 1.33 : 1, a 4 : 3 tile
GAP = 0.03 * TILE_W      # [P3] black gap between two tiles, about 4 px of 143
RIM = 0.07 * TILE_W      # [P3] raised rim of the tray, about 10 px of 143; the corner radius is the same [EYE]
PLATE_Z = 0.35           # [EYE P3, P1] front of the black plate above the board (its edge is plainly thick)
FACE_Z = 0.50            # [GAME] the recessed face that carries the legend (the game puts it at 1.0)
RIM_Z = 0.65             # [EYE P3] front of the rim, proud of the face
CORNER_SEGMENTS = 2      # the rounded corner as two flats: enough at a few blocks

# ---- the box, as shares of the tile pitch width. THE GAME DRAWS THESE IN CODE:
# AnnunciatorBox.java holds the same numbers under the same names. Keep the two in step.
MARGIN_LEFT = 0.30       # [P3] plate margin left of the tiles (holds the row numerals)
MARGIN_RIGHT = 0.31      # [P3] right of the tiles (holds the latch)
MARGIN_TOP = 0.19        # [P3] above the tiles: a quarter of a tile height (holds the column numerals)
MARGIN_BOTTOM = 0.375    # [P3] below the tiles: half a tile height (holds the nameplate)
DIVIDER = 0.02           # [P3] the dark line that splits a tile into two alarms, about 2 px of 143, drawn a little heavier
NUMERAL_CAP = 0.085      # [P3] cap height of the white margin numerals, about 12 px
NAMEPLATE_W = 0.95       # [P3] white nameplate, 135 px wide
NAMEPLATE_H = 0.33       # [P3] 47 px high
NAMEPLATE_GAP = 0.012    # [P3] it all but touches the bottom row of tiles
NAMEPLATE_CAP = 0.095    # [P3] cap height of its two lines, about 14 px
NAMEPLATE_T = 0.006      # [EYE] thickness of the nameplate
LATCH_R = 0.10           # [P3] round bright knob in the right margin, 28 px across
LATCH_H = 0.09           # [EYE P3] how far it stands off the plate

# ---- the control station (the model ann_control), after P2
ST_W = 10.0              # [GAME] 4 : 1, the proportion of the module's sizes (24x6, 16x4, 32x8)
ST_H = 2.5               # [GAME]
ST_PITCH = ST_W / 4      # [GAME] one button per quarter of the width (a click picks the quarter)
ST_BUTTON_Y = 0.825      # [GAME] button centres: the middle of the top 60 %, 3 % down
ST_LEGEND_Y = 1.8625     # [GAME] centre of the legend row (63 % to 86 % of the height)
ST_LEGEND_H = 0.26       # legend cap height
RING_R = 0.25 * ST_PITCH     # [P2] bright bezel ring: half the button pitch across
CAP_R = 0.165 * ST_PITCH     # [P2] black button: a third of the pitch across
RING_Z = 0.22            # [EYE P2] front of the ring
CAP_Z = 0.17             # [EYE P2] the button sits just inside the ring
CAP_TRAVEL = 0.15        # [GAME] stroke used by the game while held (as ../pushbuttons)
TAG_W = 0.80 * ST_PITCH  # [P2] black legend plate under each button
TAG_H = 0.20 * ST_PITCH  # [P2]
TAG_T = 0.03             # [EYE] its thickness
TAG_EDGE = 0.035         # [EYE P2] the light edge of the engraved laminate

SRC_WINDOW = ("One annunciator tile after owner-supplied photographs of Columbia Generating Station's annunciator "
              "boxes P800-C5 and P800-C1 (not in the repository): proportions measured on the photographs, "
              "scale nominal, no maker's drawing (see annunciator/README.md)")
SRC_STATION = ("Annunciator pushbutton station after an owner-supplied photograph of a local panel of an unnamed "
               "plant (not in the repository): four round black pushbuttons in bright bezel rings, a legend plate "
               "under each; laid out as AnnunciatorControlModel.java, scale nominal (see annunciator/README.md)")

# Palette (sRGB)
RIM_RGB = "E9E1C5"       # [P3] ivory moulded tile
RIM_WALL_RGB = "D6CEB2"  # the tile's side, a tone down so the gap between tiles reads
RIM_INNER_RGB = "D2CAAE" # the step from the rim down to the face
FACE_RGB = "DDD5B8"      # [P3] the face, a little darker than the rim; the game recolours it
PLATE_RGB = "17191A"     # [P3] black plate (the game's AnnunciatorModel.BOX_DARK)
DIVIDER_RGB = "4A463C"   # [P3] the dark dividing line
NAMEPLATE_RGB = "F4F2EA" # [P3] white nameplate
LATCH_RGB = "C4C9CE"     # [P3] bright metal
INK_RGB = "1E1E1C"       # [P3] black legends on an unlit tile
NUMERAL_RGB = "E8E8E8"   # [P3] white margin numerals
CHROME = "C8CCD0"
BUTTON_RGB = "1A1A1A"
TAG_RGB = "141414"
TAG_EDGE_RGB = "D8D6CC"
BUTTONS = ("ack", "reset", "silence", "test")

WHOLE, UPPER, LOWER = 0, 1, 2


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

    def rect(self, x0, y0, x1, y1, z, m=0):
        self.face([(x0, y0, z), (x1, y0, z), (x1, y1, z), (x0, y1, z)], (0, 0, 1), m)

    def box(self, x0, y0, x1, y1, z0, z1, m=0, side=None):
        """A block from z0 to z1: its front and four sides (side: their material slot, default m)."""
        s = m if side is None else side
        self.rect(x0, y0, x1, y1, z1, m)
        self.face([(x0, y0, z0), (x1, y0, z0), (x1, y0, z1), (x0, y0, z1)], (0, -1, 0), s)
        self.face([(x0, y1, z0), (x1, y1, z0), (x1, y1, z1), (x0, y1, z1)], (0, 1, 0), s)
        self.face([(x0, y0, z0), (x0, y1, z0), (x0, y1, z1), (x0, y0, z1)], (-1, 0, 0), s)
        self.face([(x1, y0, z0), (x1, y1, z0), (x1, y1, z1), (x1, y0, z1)], (1, 0, 0), s)

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
        nr, nz = z1 - z0, r0 - r1          # outward normal of the segment in the (r, z) half plane
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
# The tile
# --------------------------------------------------------------------------
def tile_materials(face_rgb=FACE_RGB, suffix=""):
    """Slots: 0 rim front, 1 the tile's outer wall, 2 the step down to the face, 3 the face, 4 the divider."""
    return [px.material("tile_rim", RIM_RGB, 0.55), px.material("tile_wall", RIM_WALL_RGB, 0.6),
            px.material("tile_step", RIM_INNER_RGB, 0.6), px.material("tile_face" + suffix, face_rgb, 0.5),
            px.material("tile_divider", DIVIDER_RGB, 0.7)]


def tile_pieces(x0, y0, w, h, role, mats, k=1.0):
    """The pieces of one tile on the cells (x0, y0, w, h), as {part name: Geo}.

    role: WHOLE (rim all round), UPPER (the top alarm of a split tile: no rim along its bottom, where the
    tile carries on into the LOWER one) or LOWER. k scales the gap and the rim (1 for a tile TILE_W wide).
    This is the construction the game repeats from the model's parts (AnnunciatorTileBox.java): corners keep
    their shape, the straight runs and the face stretch.
    """
    g, r, d = GAP / 2 * k, RIM * k, DIVIDER * TILE_W * k
    top, bottom = role != LOWER, role != UPPER
    ox0, ox1 = x0 + g, x0 + w - g
    oy0 = y0 + g if top else y0
    oy1 = y0 + h - g if bottom else y0 + h
    fx0, fx1 = ox0 + r, ox1 - r
    fy0 = oy0 + r if top else y0 + d / 2
    fy1 = oy1 - r if bottom else y0 + h - d / 2
    zp, zf, zr = PLATE_Z, FACE_Z, RIM_Z
    out = {}

    def corner(name, cx, cy, sx, sy):
        """The rounded corner whose inner corner (the arc's centre) is (cx, cy); (sx, sy) points outward."""
        geo = Geo(*mats)
        pts = []
        for i in range(CORNER_SEGMENTS + 1):
            a = math.pi / 2 * i / CORNER_SEGMENTS
            pts.append((cx + sx * r * math.cos(a), cy + sy * r * math.sin(a)))
        for a, b in zip(pts, pts[1:]):
            mx, my = (a[0] + b[0]) / 2 - cx, (a[1] + b[1]) / 2 - cy
            geo.face([(a[0], a[1], zp), (b[0], b[1], zp), (b[0], b[1], zr), (a[0], a[1], zr)], (mx, my, 0), 1)
        geo.face([(p[0], p[1], zr) for p in pts] + [(cx, cy, zr)], (0, 0, 1), 0)
        out[name] = geo

    def run(name, ax, ay, bx, by, nx, ny):
        """A straight run of rim: its outer edge from (ax, ay) to (bx, by), (nx, ny) pointing outward."""
        geo = Geo(*mats)
        ix0, iy0, ix1, iy1 = ax - nx * r, ay - ny * r, bx - nx * r, by - ny * r
        geo.face([(ax, ay, zp), (bx, by, zp), (bx, by, zr), (ax, ay, zr)], (nx, ny, 0), 1)
        geo.face([(ax, ay, zr), (bx, by, zr), (ix1, iy1, zr), (ix0, iy0, zr)], (0, 0, 1), 0)
        geo.face([(ix0, iy0, zr), (ix1, iy1, zr), (ix1, iy1, zf), (ix0, iy0, zf)], (-nx, -ny, 0), 2)
        out[name] = geo

    ry0 = oy0 + r if top else oy0
    ry1 = oy1 - r if bottom else oy1
    if top:
        corner("corner_tl", fx0, oy0 + r, -1, -1)
        corner("corner_tr", fx1, oy0 + r, 1, -1)
        run("rim_top", fx0, oy0, fx1, oy0, 0, -1)
    if bottom:
        corner("corner_bl", fx0, oy1 - r, -1, 1)
        corner("corner_br", fx1, oy1 - r, 1, 1)
        run("rim_bottom", fx0, oy1, fx1, oy1, 0, 1)
    run("rim_left", ox0, ry0, ox0, ry1, -1, 0)
    run("rim_right", ox1, ry0, ox1, ry1, 1, 0)
    geo = Geo(*mats)
    geo.rect(fx0, fy0, fx1, fy1, zf, 3)
    out["face"] = geo
    if role != WHOLE:
        geo = Geo(*mats)
        if role == UPPER:
            geo.rect(fx0, y0 + h - d / 2, fx1, y0 + h, zf + 0.01, 4)
        else:
            geo.rect(fx0, y0, fx1, y0 + d / 2, zf + 0.01, 4)
        out["divider"] = geo
    return out


def build_window():
    """One whole tile on its piece of plate, split by the divider as Columbia's are. The game takes the
    rim, the corners and the face from here and draws the divider and everything round the tiles in code."""
    px.clear_scene()
    mats = tile_materials()
    pieces = tile_pieces(0.0, 0.0, TILE_W, TILE_H, WHOLE, mats)
    for name, geo in pieces.items():
        if name == "face":
            geo.build(name, part=name, role="lamp", tint="white")
        else:
            geo.build(name, part=name)
    g, r, d = GAP / 2, RIM, DIVIDER * TILE_W
    geo = Geo(*mats)
    geo.rect(g + r, TILE_H / 2 - d / 2, TILE_W - g - r, TILE_H / 2 + d / 2, FACE_Z + 0.01, 4)
    geo.build("divider", part="divider")
    plate = Geo(px.material("plate", PLATE_RGB, 0.8))
    plate.box(0.0, 0.0, TILE_W, TILE_H, 0.0, PLATE_Z)
    plate.build("plate", part="plate")
    # Where the legends go: the game lays out up to four centred lines over the face of each window itself.
    quarter = (TILE_H - GAP - 2 * RIM) / 4
    px.label("upper", TILE_W / 2, -(TILE_H / 2 - quarter), FACE_Z + 0.002, 0.26)
    px.label("lower", TILE_W / 2, -(TILE_H / 2 + quarter), FACE_Z + 0.002, 0.26)


# --------------------------------------------------------------------------
# PREVIEW ONLY: a whole box as the game assembles it
# --------------------------------------------------------------------------
PREVIEW_LEGENDS = [("DG 2", "AUTO START"), ("DG 2", "FAIL TO START"), ("BUS 8", "LOSS OF VOLT"), ("BUS 8", "DEGRADED VOLT"),
                   ("BKR 8/81", "TRIP"), ("BKR 8/81", "GND"), ("DG 2", "VIB HIGH"), ("DG 2", "GEN GND"),
                   ("BACKUP XFMR", "BKR B8 TRIP"), ("DG 2 DAY TANK", "OIL LVL HIGH"), ("24 VDC BATT", "BLOWN FUSE"),
                   ("INVERTER", "TROUBLE"), ("RPS BUS B", "TROUBLE"), ("BUS 85", "UNDERVOLTAGE"), ("125 VDC DIV 2", "OUT OF SVC"),
                   ("XFMR TR-8/83", "WDG TEMP HI"), ("", ""), ("BUS 83", "MCC OL TRIP"), ("DG 2", "OVERSPEED"),
                   ("ANNUNCIATORS", "125 VDC LOSS")]


def text(body, x, y, z, cap, rgb, name):
    """A line of preview text centred on (x, y_down). The game draws its own with the Minecraft font."""
    curve = bpy.data.curves.new(name, "FONT")
    curve.body = body
    curve.align_x = "CENTER"
    curve.align_y = "CENTER"
    curve.size = cap / 0.69           # the cap height of Blender's built-in font is 0.69 of its size
    obj = bpy.data.objects.new(name, curve)
    obj.location = (x, -y, z)
    curve.materials.append(px.material("ink_" + rgb, rgb, 0.8))
    bpy.context.scene.collection.objects.link(obj)
    return obj


def build_box(cols, tile_rows, lit=None):
    """A box of cols x tile_rows split tiles: 2 x tile_rows alarm windows down, laid out exactly as
    AnnunciatorModel does from AnnunciatorBox.layout. lit: {(half row, column): rgb}. Returns its size."""
    px.clear_scene()
    u = TILE_W
    ml, mr, mt, mb = MARGIN_LEFT * u, MARGIN_RIGHT * u, MARGIN_TOP * u, MARGIN_BOTTOM * u
    half = TILE_H / 2
    bw, bh = cols * u, tile_rows * TILE_H
    plate = Geo(px.material("plate", PLATE_RGB, 0.8))
    plate.box(0.0, 0.0, ml + bw + mr, mt + bh + mb, 0.0, PLATE_Z)
    plate.build("plate")
    mats = tile_materials()
    lit_mats = {}
    merged = Geo(*mats)
    for row in range(2 * tile_rows):
        for col in range(cols):
            x0, y0 = ml + col * u, mt + row * half
            role = UPPER if row % 2 == 0 else LOWER
            rgb = (lit or {}).get((row, col))
            for name, geo in tile_pieces(x0, y0, u, half, role, mats).items():
                if name == "face" and rgb:
                    if rgb not in lit_mats:
                        m = px.material("face_lit_" + rgb, rgb, 0.4)
                        bsdf = m.node_tree.nodes.get("Principled BSDF")
                        bsdf.inputs["Emission Color"].default_value = list(bsdf.inputs["Base Color"].default_value)
                        bsdf.inputs["Emission Strength"].default_value = 1.0
                        bsdf.inputs["Base Color"].default_value = (0.0, 0.0, 0.0, 1.0)
                        lit_mats[rgb] = Geo(m)
                    # the game draws the lit face as a low block over the face: 1.0 to 1.12 of 1/16 block
                    f = geo.v
                    xs, ys = [p[0] for p in f], [-p[1] for p in f]
                    lit_mats[rgb].box(min(xs), min(ys), max(xs), max(ys), FACE_Z, FACE_Z * 1.12)
                    continue
                base = len(merged.v)
                merged.v.extend(geo.v)
                merged.f.extend(tuple(i + base for i in f) for f in geo.f)
                merged.fm.extend(geo.fm)
            legend = PREVIEW_LEGENDS[(row * cols + col) % len(PREVIEW_LEGENDS)]
            cy = y0 + half / 2 + (GAP / 4 + RIM / 2 if role == UPPER else -(GAP / 4 + RIM / 2))
            ink = INK_RGB if not rgb or rgb != "E02A1E" else "FFE8E0"
            for i, line in enumerate(legend):
                if line:
                    text(line, x0 + u / 2, cy + (i - 0.5) * 0.36, FACE_Z * (1.17 if rgb else 1.0) + 0.012, 0.19, ink,
                         f"legend_{row}_{col}_{i}")
    merged.build("tiles")
    for rgb, geo in lit_mats.items():
        geo.build("lit_" + rgb)
    for row in range(2 * tile_rows):
        text(str(row + 1), ml / 2, mt + (row + 0.5) * half, PLATE_Z + 0.01, NUMERAL_CAP * u, NUMERAL_RGB, f"row_{row}")
    for col in range(cols):
        text("-%d" % (col + 1), ml + (col + 0.5) * u, mt / 2, PLATE_Z + 0.01, NUMERAL_CAP * u, NUMERAL_RGB, f"col_{col}")
    # The nameplate, centred under the box, and the latch, half way up its right margin.
    cx = ml + bw / 2
    ny0 = mt + bh + NAMEPLATE_GAP * u
    name = Geo(px.material("nameplate", NAMEPLATE_RGB, 0.5))
    name.box(cx - NAMEPLATE_W * u / 2, ny0, cx + NAMEPLATE_W * u / 2, ny0 + NAMEPLATE_H * u, PLATE_Z,
             PLATE_Z + NAMEPLATE_T * u)
    name.build("nameplate")
    for i, line in enumerate(("ANN PANEL", "P800-C5")):
        text(line, cx, ny0 + NAMEPLATE_H * u * (0.29 + 0.42 * i), PLATE_Z + NAMEPLATE_T * u + 0.01, NAMEPLATE_CAP * u,
             INK_RGB, f"name_{i}")
    latch = Geo(px.material("latch", LATCH_RGB, 0.3, 0.6))
    lr, lz = LATCH_R * u, PLATE_Z + LATCH_H * u
    lathe(latch, ml + bw + mr / 2, mt + bh / 2, [(lr, PLATE_Z), (lr, lz - lr * 0.3), (lr * 0.7, lz), (0, lz)], 8,
          phase=math.radians(22.5))
    latch.build("latch")
    return ml + bw + mr, mt + bh + mb


# --------------------------------------------------------------------------
# The control station
# --------------------------------------------------------------------------
def build_station():
    px.clear_scene()
    chrome = px.material("chrome", CHROME, 0.3, 0.6)
    dark = px.material("ring_bore", "2A2C2E", 0.6)
    tag_m = px.material("tag", TAG_RGB, 0.6)
    edge_m = px.material("tag_edge", TAG_EDGE_RGB, 0.6)
    button_m = px.material("button", BUTTON_RGB, 0.5)
    for i, name in enumerate(BUTTONS):
        cx, cy = ST_PITCH * (i + 0.5), ST_BUTTON_Y
        ring = Geo(chrome, dark)
        lathe(ring, cx, cy, [(RING_R, 0.0), (RING_R, RING_Z * 0.45), (RING_R - 0.07, RING_Z), (CAP_R + 0.07, RING_Z),
                             (CAP_R + 0.015, RING_Z - 0.06), (CAP_R + 0.015, 0.0)], 16, [0, 0, 0, 0, 1])
        ring.build(f"ring_{name}", part="rings")
        cap = Geo(button_m)
        lathe(cap, cx, cy, [(CAP_R, 0.0), (CAP_R, CAP_Z - 0.03), (CAP_R - 0.035, CAP_Z), (0, CAP_Z)], 16)
        cap.build(f"cap_{name}", part=f"cap_{name}", travel=CAP_TRAVEL, axis=(0, 0, -1))
        tag = Geo(tag_m, edge_m)
        x0, x1 = cx - TAG_W / 2, cx + TAG_W / 2
        y0, y1 = ST_LEGEND_Y - TAG_H / 2, ST_LEGEND_Y + TAG_H / 2
        e, t = TAG_EDGE, TAG_T
        tag.rect(x0 + e, y0 + e, x1 - e, y1 - e, t, 0)
        tag.face([(x0, y0, 0), (x1, y0, 0), (x1 - e, y0 + e, t), (x0 + e, y0 + e, t)], (0, -1, 1), 1)
        tag.face([(x0, y1, 0), (x1, y1, 0), (x1 - e, y1 - e, t), (x0 + e, y1 - e, t)], (0, 1, 1), 1)
        tag.face([(x0, y0, 0), (x0, y1, 0), (x0 + e, y1 - e, t), (x0 + e, y0 + e, t)], (-1, 0, 1), 1)
        tag.face([(x1, y0, 0), (x1, y1, 0), (x1 - e, y1 - e, t), (x1 - e, y0 + e, t)], (1, 0, 1), 1)
        tag.build(f"tag_{name}", part="tags")
        px.label(name, cx, -ST_LEGEND_Y, TAG_T + 0.002, ST_LEGEND_H)


# --------------------------------------------------------------------------
# Previews
# --------------------------------------------------------------------------
def set_face(state):
    """Preview only: the tile's face unlit as modelled, or lit amber as the game lights an amber window."""
    mat = bpy.data.materials["tile_face"]
    bsdf = mat.node_tree.nodes.get("Principled BSDF")
    if "authored_base" not in mat:
        mat["authored_base"] = list(bsdf.inputs["Base Color"].default_value)
    bsdf.inputs["Emission Strength"].default_value = 0.0
    bsdf.inputs["Base Color"].default_value = mat["authored_base"]
    if state == "lit":
        amber = px.material("tmp_amber", "FFB000").node_tree.nodes.get("Principled BSDF").inputs["Base Color"].default_value
        bsdf.inputs["Emission Color"].default_value = list(amber)
        bsdf.inputs["Emission Strength"].default_value = 1.0
        bsdf.inputs["Base Color"].default_value = (0.0, 0.0, 0.0, 1.0)


def render(path, w, h, view, res_x=1600):
    """One preview of the current scene framed on its w x h footprint. panel_export.preview frames a square
    round the larger side from far off, which leaves a wide box a small strip; this looks at it as it hangs
    on a board: "front" square on, "below" from the operator's eye, under it and a little to the left."""
    scene = bpy.context.scene
    scene.render.engine = "BLENDER_EEVEE_NEXT" if "BLENDER_EEVEE_NEXT" in [e.identifier for e in
        bpy.types.RenderSettings.bl_rna.properties["engine"].enum_items] else "BLENDER_EEVEE"
    if os.environ.get("BWR_PANEL_PREVIEW_ENGINE"):
        scene.render.engine = os.environ["BWR_PANEL_PREVIEW_ENGINE"]
        if scene.render.engine == "CYCLES":
            scene.cycles.device = "CPU"
            scene.cycles.samples = 24
    if scene.world is None:
        scene.world = bpy.data.worlds.new("w")
    bg = scene.world.node_tree.nodes.get("Background") if scene.world.node_tree else None
    if bg is not None:
        bg.inputs["Color"].default_value = (0.18, 0.2, 0.19, 1.0)
    scene.view_settings.view_transform = "Standard"
    cx, cy = w / 2, -h / 2
    pad = 0.1 * h
    bpy.ops.mesh.primitive_plane_add(size=6 * max(w, h), location=(cx, cy, -0.06))
    backdrop = bpy.context.active_object
    backdrop.data.materials.append(px.material("backdrop", "A7BCAE", 0.8))
    sun = bpy.data.objects.new("sun", bpy.data.lights.new("sun", "SUN"))
    sun.data.energy = 3.5
    sun.rotation_euler = (math.radians(-40), math.radians(-25), math.radians(20))
    scene.collection.objects.link(sun)
    cam = bpy.data.objects.new("cam", bpy.data.cameras.new("cam"))
    scene.collection.objects.link(cam)
    scene.camera = cam
    scene.render.resolution_x = res_x
    if view == "front":
        scene.render.resolution_y = int(res_x * (h + 2 * pad) / (w + 2 * pad))
        cam.data.type = "ORTHO"
        cam.data.ortho_scale = w + 2 * pad
        cam.location = (cx, cy, 40)
        cam.rotation_euler = (0, 0, 0)
    else:
        scene.render.resolution_y = int(res_x * 0.62)
        cam.data.type = "PERSP"
        cam.data.lens = 45
        cam.location = (cx - 0.22 * w, cy - 0.5 * w, 1.5 * w)
        # Look at the middle with the board's up (+Y) as the picture's up, as someone standing in front of it would.
        forward = (Vector((cx, cy, 0.0)) - cam.location).normalized()
        right = forward.cross(Vector((0.0, 1.0, 0.0))).normalized()
        up = right.cross(forward)
        cam.rotation_euler = Matrix((right, up, -forward)).transposed().to_euler()
    scene.render.filepath = f"{path}_{view}.png"
    bpy.ops.render.render(write_still=True)
    bpy.data.objects.remove(backdrop)
    light, camera = sun.data, cam.data
    bpy.data.objects.remove(sun)
    bpy.data.objects.remove(cam)
    bpy.data.lights.remove(light)
    bpy.data.cameras.remove(camera)


def previews():
    os.makedirs(PREVIEW_DIR, exist_ok=True)
    build_window()
    base = os.path.join(PREVIEW_DIR, "ann_window")
    px.preview(base, TILE_W, TILE_H)
    set_face("lit")
    px.preview(base + "_lit", TILE_W, TILE_H, views=("front", "three_quarter"))
    w, h = build_box(7, 5)
    for view in ("front", "below"):
        render(os.path.join(PREVIEW_DIR, "ann_box_7x5"), w, h, view)
    w, h = build_box(7, 5, lit={(0, 1): "FFB000", (1, 1): "FFB000", (4, 3): "FFF6D8", (7, 5): "E02A1E", (8, 0): "FFB000"})
    for view in ("front", "below"):
        render(os.path.join(PREVIEW_DIR, "ann_box_7x5_lit"), w, h, view)
    build_station()
    # The legends the game prints on the plates, for the picture only.
    for i, (name, word) in enumerate(zip(BUTTONS, ("ACK", "RESET", "SILENCE", "TEST"))):
        text(word, ST_PITCH * (i + 0.5), ST_LEGEND_Y, TAG_T + 0.01, ST_LEGEND_H, NUMERAL_RGB, f"legend_{name}")
    base = os.path.join(PREVIEW_DIR, "ann_control")
    for view in ("front", "below"):
        render(base, ST_W, ST_H, view)
    px.preview(base, ST_W, ST_H, views=("side",), res=1024)
    for name in BUTTONS:
        bpy.data.objects[f"cap_{name}"].location.z -= CAP_TRAVEL
    render(base + "_pressed", ST_W, ST_H, "below")
    px.preview(base + "_pressed", ST_W, ST_H, views=("side",), res=1024)


MODELS = {"ann_window": (build_window, TILE_W, TILE_H, SRC_WINDOW),
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
