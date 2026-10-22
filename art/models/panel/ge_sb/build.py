"""GE SB-1 / SB-9 / SB-10 control switches (family A of PANEL-MODELS-SPEC.md).

Run headless from anywhere:

    blender --background --factory-startup --python art/models/panel/ge_sb/build.py

It builds every model of the family from the parameter table below, checks that
every handle position stays inside the footprint, exports the JSON through
panel_export, and renders the previews into tmp/panel-models/ge_sb/ (the spec
set from panel_export.preview, each handle extreme, plus a closer "closeup"
inspection render). Append `-- --no-preview` to skip the renders, or
`-- <model id> ...` to build only those models.

Models (see README.md for the full description):
    ge_sb_target_pistol     control_switch  "ge"  target escutcheon + pistol grip
    ge_sb_standard_oval     rotary_selector "ge"  standard escutcheon + oval handle
    ge_sb_standard_knurled  rotary_selector "ge"  standard escutcheon + knurled knob
    ge_sb_standard_lever    rotary_selector "ge"  standard escutcheon + lever handle
    ge_sb_keyed             key_switch      "ge"  keyed escutcheon + removable handle

Coordinates follow panel_export: inches, panel face at Z = 0, +Z toward the
operator, +Y up, footprint from (0, 0) to (W, -H). Handles are modelled at rest
(pointer at 12 o'clock; pistol grip and lever hanging straight down) with their
object origin on the shaft so a Z rotation turns them about the pivot.
"""
import math
import os
import sys

import bpy
import bmesh
from mathutils import Matrix, Vector

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
import panel_export as px  # noqa: E402

TMP = os.path.join(px.REPO, "tmp", "panel-models", "ge_sb")

# ---------------------------------------------------------------------------
# Sources
#   [AA124] GE 10AA124 breaker control switch catalog: handle photos (knurled,
#           lever, pistol grip, oval), escutcheon photos (STANDARD, TARGET,
#           KEYED), "Standard escutcheon" outline with panel drilling, pistol
#           grip side view, SB-1 3-hole drilling, position numbering 1..8.
#   [GEH]   GE GEH-908H "Control and Instrument Switches Types SB-1, SB-7 and
#           SB-10", fig. 4 panel drilling (1-1/8" hole, 1/4" holes 1" either
#           side at 1-3/32" above, one 1-3/32" below).
#   [GEF]   GE Vernova GEF-2357K SB-1 renewal parts: Table 1 "Black Handles",
#           fig. 1 (SB-1 with target escutcheon and pistol grip, handle chart).
#   [SB1]   GE Grid Solutions "Model SB-1" data sheet: escutcheon types; target
#           shows green after the handle is turned 45 deg left, red after 45 deg
#           right; "8 types of fixed handles (black only)".
# ---------------------------------------------------------------------------

# Standard escutcheon [AA124 "Standard escutcheon"]
STD_W = 2.625            # 2-5/8 wide
STD_H = 2.5625           # 2-9/16 high
STD_T = 0.1875           # 3/16 thick
STD_SHAFT_DOWN = 1.25    # shaft 1-1/4 below the top edge (so 1-5/16 above the bottom)
STD_R = 0.30             # corner radius, scaled from the outline drawing
STD_TRIM = 0.08          # bright bevelled edge band (spec: "black with bright trim edge")
STD_SCREWS = ((-0.9375, 0.6875), (0.9375, 0.6875), (-0.9375, -1.0), (0.9375, -1.0))  # 15/16 each side; 11/16 up, 1 down
SCREW_R, SCREW_H = 0.13, 0.06

# Keyed escutcheon [AA124 "KEYED", "1-3/8 hole - removable handle"]
KEY_HOLE_R = 1.375 / 2
# keyway notches seen in the KEYED photo: (clock angle deg clockwise from 12, half width, outer radius)
KEY_NOTCHES = ((0, 0.04, 0.80), (45, 0.07, 0.80), (90, 0.09, 0.82), (180, 0.08, 0.81))

# Target escutcheon [AA124 "TARGET" photo scaled so the two upper screws sit on the
# 2" centres of the SB-1 drilling [GEH fig. 4]; the SB-1 3-hole plate outline in
# [AA124] scales to the same ~2.75" square]
TGT_W = TGT_H = 2.75
TGT_R = 0.32             # outer corner radius
TGT_RIM_W = 0.14         # raised bright border
TGT_FIELD_Z = 0.20       # black field
TGT_RIM_Z = 0.25         # border top
TGT_SCREWS_TOP = ((-1.0, 0.75), (1.0, 0.75))      # large bright heads in the field
TGT_SCREWS_BOT = ((-1.03, -1.09), (1.03, -1.09))  # small heads on the bottom corner pads
TGT_PAD = (-1.04, -1.08, 0.50, 0.46)              # left pad centre x, y, w, h (mirrored right)
# Sector target window: radial sides from the shaft centre [AA124 TARGET photo]
WIN_R0, WIN_R1, WIN_HALF, WIN_FRAME = 0.38, 0.96, 22.0, 0.06
BOSS_R = 0.36            # bright shaft bushing around the handle

# Pistol grip [AA124 side view "Pistol grip handle", fig. 169 front view; spec ~2.6"
# from shaft to tip; [GEF] fig. 1 photo for the barrel/grip proportions]
BARREL_R = 0.31
# grip sections (y, half width, back z, front z): rounded elbow on the shaft,
# tapered grip, finger swell toward the panel, raked outward at the bottom
GRIP_DZ = 0.20
GRIP = (
    (0.355, 0.07, 0.98, 1.36),
    (0.330, 0.16, 0.96, 1.52),
    (0.270, 0.25, 0.95, 1.64),
    (0.150, 0.32, 0.95, 1.72),
    (0.000, 0.34, 0.95, 1.75),
    (-0.180, 0.32, 0.96, 1.75),
    (-0.420, 0.265, 1.00, 1.74),
    (-0.800, 0.235, 1.07, 1.75),
    (-1.400, 0.235, 1.14, 1.79),
    (-1.950, 0.260, 1.13, 1.85),
    (-2.300, 0.280, 1.20, 1.89),
    (-2.480, 0.275, 1.29, 1.90),
    (-2.580, 0.215, 1.39, 1.87),
    (-2.635, 0.110, 1.50, 1.80),
)

# Footprints (inches) chosen for the component aspect ratios of the spec table
FP_CONTROL = (4.4, 6.6)       # control_switch 8 x 12 cells (2:3)
FP_SELECTOR = (3.5, 4.375)    # rotary_selector 8 x 10 cells (4:5)
FP_KEY = (3.0, 4.0)           # key_switch 6 x 8 cells (3:4)

PALETTE = {  # spec palette (sRGB)
    "black": ("141414", 0.45),
    "trim": ("B8BCC0", 0.30),
    "handle": ("1A1A1A", 0.30),
    "handle_hi": ("4A4A4A", 0.30),   # glossy-phenolic edge highlight on handle bevels
    "white": ("F2F0E8", 0.50),
    "flag": ("20B040", 0.60),
    "floor": ("6E7276", 0.60),
}
FLAG_RED, FLAG_GREEN = "E02020", "20B040"
MATS = {}


# ---------------------------------------------------------------------------
# 2D helpers
# ---------------------------------------------------------------------------
def _area(loop):
    return sum(x0 * y1 - x1 * y0 for (x0, y0), (x1, y1) in zip(loop, loop[1:] + loop[:1])) / 2


def ccw(loop):
    return list(loop) if _area(loop) > 0 else list(loop)[::-1]


def rrect(cx, cy, w, h, r, seg=5):
    """Rounded rectangle, counter-clockwise."""
    pts = []
    for x, y, a0 in ((cx + w / 2 - r, cy + h / 2 - r, 0), (cx - w / 2 + r, cy + h / 2 - r, 90),
                     (cx - w / 2 + r, cy - h / 2 + r, 180), (cx + w / 2 - r, cy - h / 2 + r, 270)):
        for i in range(seg + 1):
            a = math.radians(a0 + 90 * i / seg)
            pts.append((x + r * math.cos(a), y + r * math.sin(a)))
    return pts


def circle(cx, cy, r, n, a0=None):
    a0 = math.pi / n if a0 is None else a0
    return [(cx + r * math.cos(a0 + 2 * math.pi * i / n), cy + r * math.sin(a0 + 2 * math.pi * i / n)) for i in range(n)]


def inset(loop, d):
    """Miter offset of a CCW loop toward its inside by d."""
    out, n = [], len(loop)
    for i in range(n):
        p0, p1, p2 = loop[i - 1], loop[i], loop[(i + 1) % n]
        n1, n2 = _inward(p0, p1), _inward(p1, p2)
        k = 1 + n1[0] * n2[0] + n1[1] * n2[1]
        out.append((p1[0] + d * (n1[0] + n2[0]) / k, p1[1] + d * (n1[1] + n2[1]) / k))
    return out


def _inward(a, b):
    dx, dy = b[0] - a[0], b[1] - a[1]
    length = math.hypot(dx, dy)
    return (-dy / length, dx / length)


def shift(loop, dx, dy):
    return [(x + dx, y + dy) for x, y in loop]


def clock(r, deg):
    """Point at radius r and a clock angle (degrees clockwise from 12 o'clock)."""
    a = math.radians(deg)
    return (r * math.sin(a), r * math.cos(a))


def sector(r0, r1, half_deg, d=0.0, n_out=8, n_in=4):
    """Annular sector centred on 12 o'clock with radial sides, shrunk by d all round."""
    def arc(r, n, reverse):
        h = math.radians(half_deg) - math.asin(d / r)
        pts = [(r * math.sin(-h + 2 * h * i / n), r * math.cos(-h + 2 * h * i / n)) for i in range(n + 1)]
        return pts[::-1] if reverse else pts
    return ccw(arc(r1 - d, n_out, False) + arc(r0 + d, n_in, True))


def oval(length, w_end, w_mid, end_h, n_end=8):
    """Elongated handle head along Y: elliptical ends, slightly waisted sides."""
    cy = length - end_h
    pts = [(w_end * math.cos(math.pi * i / n_end), cy + end_h * math.sin(math.pi * i / n_end)) for i in range(n_end + 1)]
    pts.append((-w_mid, 0.0))
    pts += [(w_end * math.cos(math.pi + math.pi * i / n_end), -cy + end_h * math.sin(math.pi + math.pi * i / n_end))
            for i in range(n_end + 1)]
    pts.append((w_mid, 0.0))
    return ccw(pts)


def notched_hole(r, notches, n=32):
    """Circle of radius r with rectangular keyway notches (parallel sides), CCW."""
    spans = []
    for deg, hw, r2 in notches:
        theta = math.radians(90 - deg)  # math angle of the notch centre
        spans.append((theta, math.asin(hw / r), math.asin(hw / r2), r2))
    events = []
    for i in range(n):
        a = 2 * math.pi * i / n
        if all(abs((a - t + math.pi) % (2 * math.pi) - math.pi) > d1 + 1e-3 for t, d1, _, _ in spans):
            events.append((a, [(r * math.cos(a), r * math.sin(a))]))
    for t, d1, d2, r2 in spans:
        pts = [(r * math.cos(t - d1), r * math.sin(t - d1)), (r2 * math.cos(t - d2), r2 * math.sin(t - d2)),
               (r2 * math.cos(t + d2), r2 * math.sin(t + d2)), (r * math.cos(t + d1), r * math.sin(t + d1))]
        events.append(((t - d1) % (2 * math.pi), pts))
    events.sort(key=lambda e: e[0])
    return [p for _, pts in events for p in pts]


# ---------------------------------------------------------------------------
# Mesh assembly (flat faces; the game draws one colour and normal per face)
# ---------------------------------------------------------------------------
def _newell(pts):
    nx = ny = nz = 0.0
    for i, (x0, y0, z0) in enumerate(pts):
        x1, y1, z1 = pts[(i + 1) % len(pts)]
        nx += (y0 - y1) * (z0 + z1)
        ny += (z0 - z1) * (x0 + x1)
        nz += (x0 - x1) * (y0 + y1)
    return (nx, ny, nz)


class Mesh:
    def __init__(self):
        self.faces = []

    def add(self, pts, mat, want=None, away=None):
        n = _newell(pts)
        if want is not None and n[0] * want[0] + n[1] * want[1] + n[2] * want[2] < 0:
            pts = pts[::-1]
        if away is not None:
            c = [sum(p[k] for p in pts) / len(pts) for k in range(3)]
            if sum(n[k] * (c[k] - away[k]) for k in range(3)) < 0:
                pts = pts[::-1]
        self.faces.append((list(pts), mat))


def _z(z, i):
    return z[i] if isinstance(z, (list, tuple)) else z


def walls(m, loop, z0, z1, mat, inward=False):
    n = len(loop)
    for i in range(n):
        j = (i + 1) % n
        (x0, y0), (x1, y1) = loop[i], loop[j]
        q = [(x0, y0, _z(z0, i)), (x1, y1, _z(z0, j)), (x1, y1, _z(z1, j)), (x0, y0, _z(z1, i))]
        m.add(q[::-1] if inward else q, mat)


def band(m, outer, zo, inner, zi, mat):
    """Quad strip between two matched loops (chamfers, rings); faces up/out."""
    n = len(outer)
    for i in range(n):
        j = (i + 1) % n
        m.add([(*outer[i], _z(zo, i)), (*outer[j], _z(zo, j)), (*inner[j], _z(zi, j)), (*inner[i], _z(zi, i))],
              mat, want=(0, 0, 1))


def cap(m, loop, z, mat):
    m.add([(x, y, _z(z, i)) for i, (x, y) in enumerate(loop)], mat, want=(0, 0, 1))


def zip_strip(m, outer, inner, z, mat, c):
    """Triangle strip between two loops with different vertex counts around centre c."""
    def ang(p):
        return math.atan2(p[1] - c[1], p[0] - c[0]) % (2 * math.pi)

    def prep(loop):
        loop = ccw(loop)
        k = min(range(len(loop)), key=lambda i: ang(loop[i]))
        loop = loop[k:] + loop[:k]
        angles, off = [], 0.0
        for i, p in enumerate(loop):
            a = ang(p) + off
            if i and a < angles[-1] - 1e-9:
                off += 2 * math.pi
                a += 2 * math.pi
            angles.append(a)
        return loop + [loop[0]], angles + [angles[0] + 2 * math.pi]
    a, ua = prep(outer)
    b, ub = prep(inner)
    i = j = 0
    while i < len(a) - 1 or j < len(b) - 1:
        if j >= len(b) - 1 or (i < len(a) - 1 and ua[i + 1] <= ub[j + 1]):
            tri = [(*a[i], z), (*a[i + 1], z), (*b[j], z)]
            i += 1
        else:
            tri = [(*a[i], z), (*b[j + 1], z), (*b[j], z)]
            j += 1
        m.add(tri, mat, want=(0, 0, 1))


def prism(m, loop, z0, z1, side, top=None, ch=0.0, ch_mat=None):
    """Extrudes a 2D loop from z0 up to z1 (a number or f(x, y) for a sloped top),
    with an optional 45-degree front chamfer of size ch. No back face."""
    loop = ccw(loop)
    f = z1 if callable(z1) else (lambda x, y: z1)
    walls(m, loop, z0, [f(x, y) - ch for x, y in loop], side)
    top_loop = loop
    if ch > 0:
        top_loop = inset(loop, ch)
        band(m, loop, [f(x, y) - ch for x, y in loop], top_loop, [f(x, y) for x, y in top_loop], ch_mat or side)
    if top is not None:
        cap(m, top_loop, [f(x, y) for x, y in top_loop], top)
    return top_loop


def cylinder(m, cx, cy, r, z0, z1, mat, seg=12, top=None):
    c = circle(cx, cy, r, seg)
    walls(m, c, z0, z1, mat)
    if top is not None:
        cap(m, c, z1, top)


def screw(m, x, y, z, r=SCREW_R, h=SCREW_H, mat="trim", seg=8):
    """Domed round screw head."""
    c0 = circle(x, y, r, seg)
    c1 = circle(x, y, 0.6 * r, seg)
    walls(m, c0, z, z + 0.45 * h, mat)
    band(m, c0, z + 0.45 * h, c1, z + 0.85 * h, mat)
    for i in range(seg):
        m.add([(*c1[i], z + 0.85 * h), (*c1[(i + 1) % seg], z + 0.85 * h), (x, y, z + h)], mat, want=(0, 0, 1))


def loft(m, sections, mat, caps=(True, True)):
    """Skins matched 3D section loops; faces point away from the section centres."""
    cents = [[sum(p[k] for p in s) / len(s) for k in range(3)] for s in sections]
    n = len(sections[0])
    for s in range(len(sections) - 1):
        a, b = sections[s], sections[s + 1]
        axis = [(cents[s][k] + cents[s + 1][k]) / 2 for k in range(3)]
        for i in range(n):
            j = (i + 1) % n
            m.add([a[i], a[j], b[j], b[i]], mat(i) if callable(mat) else mat, away=axis)
    end = mat(-1) if callable(mat) else mat
    if caps[0]:
        m.add(list(sections[0]), end, away=cents[1])
    if caps[1]:
        m.add(list(sections[-1]), end, away=cents[-2])


# ---------------------------------------------------------------------------
# Blender objects
# ---------------------------------------------------------------------------
def make_materials():
    MATS.clear()
    for name, (hex_, rough) in PALETTE.items():
        MATS[name] = px.material(f"ge_sb_{name}", hex_, rough)


def make_object(name, mesh, part="body", role="paint", pivot=None):
    me = bpy.data.meshes.new(name)
    bm = bmesh.new()
    verts, names = {}, []
    off = Vector(pivot) if pivot else Vector((0, 0, 0))
    for pts, mat in mesh.faces:
        vs = []
        for p in pts:
            key = tuple(round(c, 6) for c in p)
            v = verts.get(key)
            if v is None:
                v = verts[key] = bm.verts.new(Vector(key) - off)
            if not vs or vs[-1] is not v:
                vs.append(v)
        if len(vs) > 1 and vs[0] is vs[-1]:
            vs.pop()
        if len(set(vs)) < 3:
            continue
        try:
            f = bm.faces.new(vs)
        except ValueError:
            continue
        if mat not in names:
            names.append(mat)
        f.material_index = names.index(mat)
    bm.to_mesh(me)
    bm.free()
    for nm in names:
        me.materials.append(MATS[nm])
    obj = bpy.data.objects.new(name, me)
    obj.location = off
    bpy.context.scene.collection.objects.link(obj)
    px.tag(obj, part=part, role=role, pivot=tuple(pivot) if pivot else None)
    return obj


def count_faces(objs):
    """Faces as exported (n-gons above 4 sides become triangles)."""
    total = 0
    for o in objs:
        for p in o.data.polygons:
            total += 1 if len(p.vertices) <= 4 else len(p.vertices) - 2
    return total


def check_sweep(handle, angles_deg, fw, fh):
    """Every handle position (Blender Z rotation about the pivot) must stay inside the footprint."""
    piv = handle.location
    worst = [1e9, -1e9, 1e9, -1e9]
    for a in angles_deg:
        rot = Matrix.Rotation(math.radians(a), 4, "Z")
        for v in handle.data.vertices:
            w = rot @ v.co + piv
            worst = [min(worst[0], w.x), max(worst[1], w.x), min(worst[2], w.y), max(worst[3], w.y)]
            if w.x < -1e-4 or w.x > fw + 1e-4 or w.y > 1e-4 or w.y < -fh - 1e-4:
                raise ValueError(f"{handle.name} at {a} deg leaves the footprint: {tuple(round(c, 3) for c in w)}")
    print(f"  sweep {handle.name}: x {worst[0]:.3f}..{worst[1]:.3f} of {fw}, y {worst[2]:.3f}..{worst[3]:.3f} of -{fh}")


# ---------------------------------------------------------------------------
# Escutcheons
# ---------------------------------------------------------------------------
def standard_plate(m, sx, sy, keyed=False):
    """Standard (or keyed) escutcheon: black plate, bright bevelled edge, four screws."""
    cy = sy + STD_SHAFT_DOWN - STD_H / 2
    outline = rrect(sx, cy, STD_W, STD_H, STD_R)
    top_loop = prism(m, outline, 0.0, STD_T, "trim", top=None if keyed else "black", ch=STD_TRIM, ch_mat="trim")
    if keyed:
        hole = shift(notched_hole(KEY_HOLE_R, KEY_NOTCHES), sx, sy)
        zip_strip(m, top_loop, hole, STD_T, "black", (sx, sy))
        walls(m, hole, 0.02, STD_T, "black", inward=True)
        cap(m, circle(sx, sy, 0.86, 16), 0.02, "floor")
    for dx, dy in STD_SCREWS:
        screw(m, sx + dx, sy + dy, STD_T)


def std_labels(sx, sy, z):
    """pos1..pos8 at 45-degree steps in GE's numbering (1 at 9 o'clock, clockwise) + caption."""
    for k in range(1, 9):
        deg = (k - 3) * 45
        r = 1.0 if k % 2 else 0.92
        x, y = clock(r, deg)
        px.label(f"pos{k}", sx + x, sy + y, z, 0.11)
    px.label("caption", sx, sy + STD_SHAFT_DOWN + 0.22, 0.002, 0.15)


def target_plate(m, flag, sx, sy):
    """SB-1 target escutcheon: black field in a raised bright border, bright pads in the
    lower corners, sector target window above the shaft, bright shaft bushing."""
    outline = rrect(sx, sy, TGT_W, TGT_H, TGT_R)
    ch = 0.035
    walls(m, outline, 0.0, TGT_RIM_Z - ch, "trim")
    top_outer = inset(outline, ch)
    band(m, outline, TGT_RIM_Z - ch, top_outer, TGT_RIM_Z, "trim")
    field = rrect(sx, sy, TGT_W - 2 * TGT_RIM_W, TGT_H - 2 * TGT_RIM_W, TGT_R - TGT_RIM_W)
    band(m, top_outer, TGT_RIM_Z, field, TGT_RIM_Z, "trim")
    walls(m, field, TGT_FIELD_Z, TGT_RIM_Z, "trim", inward=True)
    cap(m, field, TGT_FIELD_Z, "black")
    # bright corner pads below the field (they carry the lower screws)
    px_, py_, pw, ph = TGT_PAD
    for sgn in (-1, 1):
        pad = rrect(sx + sgn * abs(px_), sy + py_, pw, ph, 0.09, seg=3)
        prism(m, pad, TGT_FIELD_Z, TGT_RIM_Z + 0.002, "trim", top="trim", ch=0.02)
    for dx, dy in TGT_SCREWS_TOP:
        screw(m, sx + dx, sy + dy, TGT_FIELD_Z, r=0.19, h=0.09, seg=10)
    for dx, dy in TGT_SCREWS_BOT:
        screw(m, sx + dx, sy + dy, TGT_RIM_Z + 0.002, r=0.11, h=0.05)
    # target window: raised bright frame round a sector, flag inside it
    outer = shift(sector(WIN_R0, WIN_R1, WIN_HALF), sx, sy)
    inner = shift(sector(WIN_R0, WIN_R1, WIN_HALF, d=WIN_FRAME), sx, sy)
    frame_z = TGT_RIM_Z - 0.01
    walls(m, outer, TGT_FIELD_Z, frame_z, "trim")
    band(m, outer, frame_z, inner, frame_z, "trim")
    walls(m, inner, TGT_FIELD_Z + 0.004, frame_z, "trim", inward=True)
    cap(flag, inner, TGT_FIELD_Z + 0.004, "flag")
    # shaft bushing
    prism(m, circle(sx, sy, BOSS_R, 16), TGT_FIELD_Z, TGT_FIELD_Z + 0.10, "trim", top="trim", ch=0.03)


# ---------------------------------------------------------------------------
# Handles (built in world coordinates around the shaft at (sx, sy))
# ---------------------------------------------------------------------------
def pistol_grip(m, sx, sy, z0):
    cylinder(m, sx, sy, BARREL_R, z0, 1.02 + GRIP_DZ, "handle", seg=14)
    sections = []
    for y, hw, zb, zf in GRIP:
        zb, zf = zb + GRIP_DZ, zf + GRIP_DZ
        zc, hd = (zb + zf) / 2, (zf - zb) / 2
        loop = []
        for i in range(12):
            t = 2 * math.pi * (i + 0.5) / 12
            c, s = math.cos(t), math.sin(t)
            loop.append((sx + hw * math.copysign(abs(c) ** 0.75, c), sy + y, zc + hd * math.copysign(abs(s) ** 0.75, s)))
        sections.append(loop)
    # quad i spans section angles 30*(i+1) +/- 15 deg (90 = toward the operator):
    # the two rounded front edges carry the glossy highlight
    loft(m, sections, lambda i: "handle_hi" if i in (1, 3) else "handle")


def inlay_arrow(m, sx, sy, tip, base, half_w, z):
    """White pointer mark on a handle face (z may be f(x, y))."""
    f = z if callable(z) else (lambda x, y: z)
    pts = [(sx, sy + tip), (sx - half_w, sy + base), (sx + half_w, sy + base)]
    m.add([(x, y, f(x, y) + 0.004) for x, y in pts], "white", want=(0, 0, 1))


def oval_handle(m, sx, sy):
    cylinder(m, sx, sy, 0.27, STD_T - 0.01, 0.50, "handle")
    head = shift(oval(0.75, 0.37, 0.32, 0.30), sx, sy)
    prism(m, head, 0.50, 0.88, "handle", top="handle", ch=0.07, ch_mat="handle_hi")
    inlay_arrow(m, sx, sy, 0.64, 0.38, 0.12, 0.88)


def knurled_handle(m, sx, sy):
    cylinder(m, sx, sy, 0.36, STD_T - 0.01, 0.48, "handle", seg=14)
    lobes = [(sx + (0.60 - 0.055 * math.cos(10 * t)) * math.cos(t), sy + (0.60 - 0.055 * math.cos(10 * t)) * math.sin(t))
             for t in (2 * math.pi * i / 40 + math.pi / 2 for i in range(40))]
    prism(m, lobes, 0.48, 0.98, "handle", top="handle", ch=0.06, ch_mat="handle_hi")
    inlay_arrow(m, sx, sy, 0.50, 0.26, 0.11, 0.98)


def lever_handle(m, sx, sy):
    cylinder(m, sx, sy, 0.28, STD_T - 0.01, 0.30, "handle")
    hub = [(0.34 * math.cos(math.radians(a)), 0.34 * math.sin(math.radians(a))) for a in range(-40, 221, 26)]
    outline = ([(0.20, -1.56), (0.27, -1.53), (0.295, -1.45), (0.255, -0.45)] + hub +
               [(-0.255, -0.45), (-0.295, -1.45), (-0.27, -1.53), (-0.20, -1.56)])
    outline = shift(outline, sx, sy)

    def top(x, y):  # thick at the hub, thinner toward the tip [AA124 "LEVER"]
        return 0.84 + 0.18 * (y - sy)
    prism(m, outline, 0.30, top, "handle", top="handle", ch=0.06, ch_mat="handle_hi")
    inlay_arrow(m, sx, sy, 0.27, 0.08, 0.09, top)


def removable_handle(m, sx, sy):
    """Removable (key) handle in the keyed escutcheon: bright bayonet collar in the
    1-3/8" opening, black shank, black oval wing with a pointer mark."""
    collar = circle(sx, sy, 0.62, 16)
    shank = circle(sx, sy, 0.25, 16)
    walls(m, collar, 0.02, STD_T - 0.02, "trim")
    band(m, collar, STD_T - 0.02, shank, STD_T - 0.02, "trim")
    walls(m, shank, STD_T - 0.02, 0.68, "handle")
    head = shift(oval(0.62, 0.30, 0.26, 0.24), sx, sy)
    prism(m, head, 0.68, 0.98, "handle", top="handle", ch=0.06, ch_mat="handle_hi")
    inlay_arrow(m, sx, sy, 0.52, 0.30, 0.11, 0.98)


# ---------------------------------------------------------------------------
# Models
# ---------------------------------------------------------------------------
SRC_COMMON = ("GE 10AA124 SB-1 breaker control switch catalog (handle and escutcheon photos, "
              "standard escutcheon outline and drilling); GE GEH-908H fig. 4; GE GEF-2357K fig. 1")


def closeup(path, fw, fh, res=640):
    """Extra inspection render (not part of the spec set): perspective from the
    operator's front-right, panel 'up' kept up in the image, model filling the frame."""
    scene = bpy.context.scene
    cx, cy = fw / 2, -fh / 2
    size = max(fw, fh)
    bpy.ops.mesh.primitive_plane_add(size=size * 10, location=(cx, cy, -0.06))
    bd = bpy.context.active_object
    bd.data.materials.append(px.material("backdrop", "A7BCAE", 0.8))
    sun = bpy.data.objects.new("closeup_sun", bpy.data.lights.new("closeup_sun", "SUN"))
    sun.data.energy = 3.5
    sun.rotation_euler = (math.radians(-40), math.radians(-25), math.radians(20))  # upper left, as preview()
    scene.collection.objects.link(sun)
    cam = bpy.data.objects.new("closeup_cam", bpy.data.cameras.new("closeup_cam"))
    scene.collection.objects.link(cam)
    scene.camera = cam
    cam.data.lens = 50
    target = Vector((cx, cy, 0.6))
    eye = target + Vector((0.9, 0.3, 1.0)).normalized() * size * 1.9
    fwd = (target - eye).normalized()
    right = fwd.cross(Vector((0, 1, 0))).normalized()
    up = right.cross(fwd)
    cam.matrix_world = Matrix((
        (right.x, up.x, -fwd.x, eye.x), (right.y, up.y, -fwd.y, eye.y),
        (right.z, up.z, -fwd.z, eye.z), (0, 0, 0, 1)))
    scene.render.resolution_x = scene.render.resolution_y = res
    scene.render.filepath = path
    bpy.ops.render.render(write_still=True)
    light, camera = sun.data, cam.data
    for obj in (bd, sun, cam):
        bpy.data.objects.remove(obj)
    bpy.data.lights.remove(light)
    bpy.data.cameras.remove(camera)


def finish(name, fw, fh, parts, handle, angles, source, extremes, preview_on):
    check_sweep(handle, angles, fw, fh)
    faces = count_faces(parts)
    print(f"  {name}: {faces} faces")
    px.export(name, fw, fh, source, objects=parts)
    if not preview_on:
        return
    os.makedirs(TMP, exist_ok=True)
    px.preview(os.path.join(TMP, name), fw, fh)
    closeup(os.path.join(TMP, f"{name}_closeup.png"), fw, fh)
    for tag, angle, hook in extremes:
        handle.rotation_euler.z = math.radians(angle)
        if hook:
            hook(True)
        px.preview(os.path.join(TMP, f"{name}_{tag}"), fw, fh, views=("front", "three_quarter"))
        closeup(os.path.join(TMP, f"{name}_{tag}_closeup.png"), fw, fh)
        if hook:
            hook(False)
    handle.rotation_euler.z = 0.0


def build_target_pistol(preview_on):
    name = "ge_sb_target_pistol"
    fw, fh = FP_CONTROL
    sx, sy = fw / 2, -2.85
    px.clear_scene()
    make_materials()
    body, flag, handle = Mesh(), Mesh(), Mesh()
    target_plate(body, flag, sx, sy)
    pistol_grip(handle, sx, sy, TGT_FIELD_Z + 0.08)
    o_body = make_object(f"{name}_body", body)
    o_flag = make_object(f"{name}_target", flag, part="target", role="flag")
    o_handle = make_object(f"{name}_handle", handle, part="handle", pivot=(sx, sy, 0.0))
    z = TGT_FIELD_Z + 0.002
    px.label("caption", sx, sy + TGT_H / 2 + 0.22, 0.002, 0.16)
    px.label("left", sx - 0.64, sy + 0.42, z, 0.13, rot_deg=55)     # TRIP, reads up to the right
    px.label("right", sx + 0.64, sy + 0.42, z, 0.13, rot_deg=-55)   # CLOSE, reads down to the right
    px.label("lower_left", sx - 0.80, sy - 0.10, z, 0.085)          # PULL TO LOCK
    px.label("bottom", sx, sy - 0.80, z, 0.085)                     # GENERAL POWER (a placeholder anchor; the game draws no maker legend)

    flag_mat = MATS["flag"]

    def flag_colour(hex_):
        def hook(on):
            px.material(flag_mat.name, hex_ if on else PALETTE["flag"][0], PALETTE["flag"][1])
        return hook
    # Blender +Z rotation is counter-clockwise on the face: CLOSE = clockwise = -45
    finish(name, fw, fh, [o_body, o_flag, o_handle], o_handle, (-45, 0, 45),
           SRC_COMMON + "; SB-1 data sheet (target colours)",
           (("close", -45, flag_colour(FLAG_RED)), ("trip", 45, flag_colour(FLAG_GREEN))), preview_on)


def build_standard(variant, handle_fn, preview_on):
    name = f"ge_sb_standard_{variant}"
    fw, fh = FP_SELECTOR
    sx, sy = fw / 2, -fh / 2
    px.clear_scene()
    make_materials()
    body, handle = Mesh(), Mesh()
    standard_plate(body, sx, sy)
    handle_fn(handle, sx, sy)
    o_body = make_object(f"{name}_body", body)
    o_handle = make_object(f"{name}_handle", handle, part="handle", pivot=(sx, sy, 0.0))
    std_labels(sx, sy, STD_T + 0.002)
    detents = [(3 - k) * 45 for k in range(1, 9)]  # Blender angle for pos k (pos3 = rest)
    extremes = [("pos1", 90, None), ("pos5", -90, None)]
    if variant == "lever":
        extremes.append(("pos7", 180, None))
    finish(name, fw, fh, [o_body, o_handle], o_handle, detents, SRC_COMMON, extremes, preview_on)


def build_keyed(preview_on):
    name = "ge_sb_keyed"
    fw, fh = FP_KEY
    sx, sy = fw / 2, -2.1
    px.clear_scene()
    make_materials()
    body, handle = Mesh(), Mesh()
    standard_plate(body, sx, sy, keyed=True)
    removable_handle(handle, sx, sy)
    o_body = make_object(f"{name}_body", body)
    o_handle = make_object(f"{name}_handle", handle, part="handle", pivot=(sx, sy, 0.0))
    std_labels(sx, sy, STD_T + 0.002)
    detents = [(3 - k) * 45 for k in range(1, 9)]
    finish(name, fw, fh, [o_body, o_handle], o_handle, detents, SRC_COMMON + " (KEYED escutcheon)",
           (("on", -45, None),), preview_on)


def main():
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    preview_on = "--no-preview" not in argv
    only = [a for a in argv if not a.startswith("--")]
    jobs = {
        "ge_sb_target_pistol": lambda: build_target_pistol(preview_on),
        "ge_sb_standard_oval": lambda: build_standard("oval", oval_handle, preview_on),
        "ge_sb_standard_knurled": lambda: build_standard("knurled", knurled_handle, preview_on),
        "ge_sb_standard_lever": lambda: build_standard("lever", lever_handle, preview_on),
        "ge_sb_keyed": lambda: build_keyed(preview_on),
    }
    for name, job in jobs.items():
        if not only or name in only:
            print(f"building {name}")
            job()


main()
