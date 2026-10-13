"""Family D: GE CR104P-style 30.5 mm oiltight pushbuttons, indicating lights and a
setpoint knob, built from parameters and exported through panel_export.

Run headless from the repo root:

    blender --background --factory-startup --python art/models/panel/pushbuttons/build.py
    blender ... --python art/models/panel/pushbuttons/build.py -- --only pb_flush,knob_fluted --no-preview

Exports mod/src/main/resources/assets/bwr/models/panel/<id>.json for every model
and renders previews into tmp/panel-models/pushbuttons/ (front, three-quarter,
side, plus pressed / lit / knob-extreme renders).

Conventions are those of panel_export: inches, panel face Z = 0, +Z toward the
operator, +Y up, footprint top-left at (0, 0) extending to (w, -h).
"""
import math
import os
import sys

import bpy
from mathutils import Matrix, Vector

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
import panel_export as px  # noqa: E402

PREVIEW_DIR = os.path.join(px.REPO, "tmp", "panel-models", "pushbuttons")

# --------------------------------------------------------------------------
# DIMENSIONS (inches). Sources:
#   [CAT]  GE Industrial Solutions Control Catalog, Section 9 "Pilot and
#          Signaling Devices", rev. 7/13, CR104P heavy-duty 30.5 mm oiltight
#          pages 9-2 .. 9-49 (page cited per value).
#   [ET16] GE Vernova "ET-16/17 Indicating Lamps" brochure GEA-N50470
#          (dimension table and drawing, p.2).
#   [PH]   Estimated from the [CAT] product photographs / outline, scaled
#          against a catalogued dimension (no dimensioned drawing published).
#   [ASM]  Assumed: typical 30.5 mm oiltight proportions, no source found.
# --------------------------------------------------------------------------
HOLE_D = 1.20            # [CAT 9-49] mounting hole 1-13/64" (30.5 mm)
PLATE_W = 1.93           # [CAT 9-40] nameplate Standard (Size 1): 1.98 H x 1.93 W
PLATE_H = 1.98           # [CAT 9-40]
PLATE_T = 0.032          # [ASM] aluminium nameplate stock
BAND_H = 0.44            # [PH 9-40] satin legend band at the top of the Size 1 plate
HOLE_FROM_TOP = 1.16     # [PH 9-40] operator centre below the plate's top edge
# Operator stack in front of the panel, [PH 9-49 outline]: side and front views scaled
# from the drawing's 1.73" housing and the .53" front protrusion.
LOCK_D = 1.48            # [PH 9-49] round locking plate under the octagon; kept inside the octagon's
                         # corners so the octagon still reads from the front (drawing suggests up to ~1.6")
LOCK_T = 0.05            # [PH 9-49]
OCT_AF = 1.42            # [PH 9-49 front view] octagonal ring across flats
OCT_H = 0.30             # [PH 9-49 side view] octagonal ring, top ~0.38" from the panel
COLLAR_R = 0.60          # [PH 9-49 front view] round collar ~1.2" dia in front of the octagon
COLLAR_IN = 0.525        # [PH 9-49 front view] collar bore
CAP_R = 0.49             # [PH 9-49 front view] flush cap ~0.98" dia
FLUSH_FRONT = 0.53       # [CAT 9-49 outline] flush operator front .53" (13.5 mm) from panel
COLLAR_TOP = FLUSH_FRONT # [CAT 9-49] the flush cap is level with the collar front
EXT_FRONT = 0.78         # [PH 9-5 photo] extended cap ~1/4" proud of the flush position
ILLUM_FRONT = 0.92       # [PH 9-6 photo] illuminated extended lens, as far out as the pilot-light lens
ILLUM_R = 0.43           # [PH 9-6 photo] illuminated lens cap is slimmer than the plain cap
GUARDED_FRONT = 1.00     # [PH 9-6 "extended with guard" photo] lens face just inside the guard rim
CAP_TRAVEL = 0.15        # [ASM] stroke used by the game while held
MUSH_D = 1.375           # [CAT 9-7] 1-3/8" mushroom head
MUSH_FRONT = 1.08        # [PH 9-7 photo] head front; < 1.38" depth of guard CR104PXG14 [CAT 9-38]
GUARD_FRONT = 1.20       # [CAT 9-38] CR104PXG04 guard ring for guarded buttons: 1.20" (30.5 mm) projection
GUARD_R = 0.70           # [PH 9-38 photo] guard tube outside radius
GUARD_WALL = 0.07        # [PH]
LENS_FRONT = 0.92        # [CAT, lens CR104PXL01*] indicating-light lens 0.92" projection from panel
LENS_R = 0.47            # [PH 9-15 photo] ribbed lens body radius
ET16_CAP_D = 0.88        # [ET16] lens cap diameter "A" = 0.88
ET16_FRONT = 0.69        # [ET16] lens front 0.69 from panel
ET16_NUT_D = 1.00        # [ASM] knurled bezel nut behind the cap (0.66" hole [ET16])
SQ_BEZEL = 0.94          # [ASM] square indicator bezel (typical 3/4" square-lens indicator)
SQ_LENS = 0.72           # [ASM] square lens
SQ_FRONT = 0.24          # [ASM]
KNOB_PLATE = 2.15        # [ASM] square dial plate
KNOB_SKIRT_R = 0.62      # [ASM] skirt 1.24" dia with pointer line
KNOB_R = 0.42            # [ASM] fluted body 0.84" dia, 12 flutes
KNOB_TOP = 0.66          # [ASM]
KNOB_SWEEP = 135.0       # [spec] knob rotation +-135 deg (setpoint 0..100 %)

SRC_CAT = ("GE Control Catalog Sec. 9 (rev. 7/13) CR104P 30.5 mm oiltight: p.9-49 drilling/outline "
           "(1.20 in hole, flush operator 0.53 in proud), p.9-40 Size 1 nameplate 1.98x1.93 in, "
           "p.9-38 octagonal rings / guards; proportions from catalog photos")
SRC_ET16 = "GE Vernova ET-16/17 Indicating Lamps brochure GEA-N50470: lens cap 0.88 in dia, 0.69 in from panel"
SRC_ASM = "No manufacturer drawing; typical control-board proportions (see pushbuttons/README.md)"

# Palette (sRGB), PANEL-MODELS-SPEC.md
CHROME = "C8CCD0"
FIELD_BLACK = "141414"
RED_FIELD = "8E1A14"     # red nameplate field (CR104PXN*R), darker than the red cap so the two separate
SATIN = "B8BCC0"
PHENOLIC = "1A1A1A"
HOUSING = "2A2C2E"
MARK_WHITE = "F2F0E8"
CAPS = {"red": "C0261E", "green": "1E7B3C", "black": "1A1A1A", "white": "E8E6DE",
        "amber": "E09A00", "blue": "2858B8"}
LENSES = {"red": "E02020", "green": "20B040", "white": "F0F0E0", "amber": "FFB000", "blue": "3070E0"}


# --------------------------------------------------------------------------
# Mesh helpers
# --------------------------------------------------------------------------
class Geo:
    """Accumulates one object's vertices and faces with per-face material slots."""

    def __init__(self, *mats):
        self.mats = list(mats)
        self.v, self.f, self.fm = [], [], []

    def vert(self, p):
        self.v.append(tuple(float(c) for c in p))
        return len(self.v) - 1

    def face(self, idx, m=0):
        self.f.append(tuple(idx))
        self.fm.append(m)

    def build(self, name, part="body", role="paint", origin=None, **tagkw):
        me = bpy.data.meshes.new(name)
        me.from_pydata(self.v, [], self.f)
        for m in self.mats:
            me.materials.append(m)
        for poly, mi in zip(me.polygons, self.fm):
            poly.material_index = mi
        me.update()
        obj = bpy.data.objects.new(name, me)
        bpy.context.scene.collection.objects.link(obj)
        if origin is not None:
            o = Vector(origin)
            me.transform(Matrix.Translation(-o))
            obj.location = o
        px.tag(obj, part=part, role=role, **tagkw)
        return obj


def lathe(g, cx, cy, profile, segs, mat=0, phase=0.0, rmod=None, ngon_cap=False, cap_mat=None):
    """Surface of revolution about the Z axis through (cx, cy).

    profile: [(r, z), ...] traversed so the visible side is on the left (outer
    wall upward, front faces inward); a final r == 0 closes the centre with a
    triangle fan, or with ngon_cap=True the last ring is closed by one polygon.
    mat: material slot, or a list with one slot per profile segment.
    rmod(i, j, r) -> radius lets ring i vary around the circumference (flutes).
    """
    rings = []
    for i, (r, z) in enumerate(profile):
        if r == 0:
            c = g.vert((cx, cy, z))
            rings.append([c] * segs)
            continue
        ring = []
        for j in range(segs):
            th = phase + 2 * math.pi * j / segs
            rr = rmod(i, j, r) if rmod else r
            ring.append(g.vert((cx + rr * math.cos(th), cy + rr * math.sin(th), z)))
        rings.append(ring)
    for i in range(len(profile) - 1):
        a_ring, b_ring = rings[i], rings[i + 1]
        m = mat[i] if isinstance(mat, (list, tuple)) else mat
        for j in range(segs):
            j2 = (j + 1) % segs
            a, b, c, d = a_ring[j], a_ring[j2], b_ring[j2], b_ring[j]
            if c == d:
                g.face((a, b, c), m)
            elif a == b:
                g.face((a, c, d), m)
            else:
                g.face((a, b, c, d), m)
    if ngon_cap:
        m = cap_mat if cap_mat is not None else (mat[-1] if isinstance(mat, (list, tuple)) else mat)
        g.face(tuple(rings[-1]), m)
    return rings


def box(g, x0, y0, z0, x1, y1, z1, m=0, back=False, sides=True):
    """Axis-aligned box; the back (-Z) face is skipped unless back=True."""
    v = {}
    for xi, x in enumerate((x0, x1)):
        for yi, y in enumerate((y0, y1)):
            for zi, z in enumerate((z0, z1)):
                v[xi, yi, zi] = g.vert((x, y, z))
    g.face((v[0, 0, 1], v[1, 0, 1], v[1, 1, 1], v[0, 1, 1]), m)          # front +Z
    if sides:
        g.face((v[1, 0, 0], v[1, 1, 0], v[1, 1, 1], v[1, 0, 1]), m)      # +X
        g.face((v[0, 0, 0], v[0, 0, 1], v[0, 1, 1], v[0, 1, 0]), m)      # -X
        g.face((v[0, 1, 0], v[0, 1, 1], v[1, 1, 1], v[1, 1, 0]), m)      # +Y
        g.face((v[0, 0, 0], v[1, 0, 0], v[1, 0, 1], v[0, 0, 1]), m)      # -Y
    if back:
        g.face((v[0, 0, 0], v[0, 1, 0], v[1, 1, 0], v[1, 0, 0]), m)


def radial_bar(g, cx, cy, ang_deg, r0, r1, w, z0, z1, m=0):
    """A raised bar from radius r0 to r1 at angle ang (degrees clockwise from up)."""
    a = math.radians(ang_deg)
    u = (math.sin(a), math.cos(a))          # radial direction
    p = (math.cos(a), -math.sin(a))         # to the right of u
    corners = {}
    for xi, lx in enumerate((-w / 2, w / 2)):
        for yi, ly in enumerate((r0, r1)):
            for zi, z in enumerate((z0, z1)):
                corners[xi, yi, zi] = g.vert((cx + lx * p[0] + ly * u[0], cy + lx * p[1] + ly * u[1], z))
    v = corners
    g.face((v[0, 0, 1], v[1, 0, 1], v[1, 1, 1], v[0, 1, 1]), m)
    g.face((v[1, 0, 0], v[1, 1, 0], v[1, 1, 1], v[1, 0, 1]), m)
    g.face((v[0, 0, 0], v[0, 0, 1], v[0, 1, 1], v[0, 1, 0]), m)
    g.face((v[0, 1, 0], v[0, 1, 1], v[1, 1, 1], v[1, 1, 0]), m)
    g.face((v[0, 0, 0], v[1, 0, 0], v[1, 0, 1], v[0, 0, 1]), m)


def profile_strip(g, cx, cy, ang_deg, pts, w, lift, m=0):
    """A raised stripe of width w following a lathe profile at one angle (a knob's pointer line).

    pts: [(r, z), ...] along the surface from the centre outward/downward; the stripe
    stands `lift` off the surface along the profile normal and has two side walls.
    """
    a = math.radians(ang_deg)
    u = (math.sin(a), math.cos(a))
    p = (math.cos(a), -math.sin(a))
    norms = []
    for i in range(len(pts)):
        ns = []
        for i0, i1 in ((i - 1, i), (i, i + 1)):
            if 0 <= i0 and i1 < len(pts):
                dr, dz = pts[i1][0] - pts[i0][0], pts[i1][1] - pts[i0][1]
                ln = math.hypot(dr, dz)
                ns.append((-dz / ln, dr / ln))
        nr, nz = sum(n[0] for n in ns), sum(n[1] for n in ns)
        ln = math.hypot(nr, nz)
        norms.append((nr / ln, nz / ln))

    def v(r, z, s):
        return g.vert((cx + r * u[0] + s * p[0], cy + r * u[1] + s * p[1], z))

    rows = []
    for (r, z), (nr, nz) in zip(pts, norms):
        ro, zo = r + lift * nr, z + lift * nz
        rows.append({"in-": v(r, z, -w / 2), "in+": v(r, z, w / 2),
                     "out-": v(ro, zo, -w / 2), "out+": v(ro, zo, w / 2)})
    for a_, b_ in zip(rows, rows[1:]):
        g.face((a_["out-"], a_["out+"], b_["out+"], b_["out-"]), m)
        g.face((a_["in+"], b_["in+"], b_["out+"], a_["out+"]), m)
        g.face((a_["in-"], a_["out-"], b_["out-"], b_["in-"]), m)


def arc_band(g, cx, cy, r0, r1, a0_deg, a1_deg, segs, z, m=0):
    """Flat annular sector facing +Z (angles clockwise from up)."""
    pts = []
    for k in range(segs + 1):
        a = math.radians(a0_deg + (a1_deg - a0_deg) * k / segs)
        s, c = math.sin(a), math.cos(a)
        pts.append((g.vert((cx + r0 * s, cy + r0 * c, z)), g.vert((cx + r1 * s, cy + r1 * c, z))))
    for k in range(segs):
        (i0, o0), (i1, o1) = pts[k], pts[k + 1]
        # angle increases clockwise, so (inner k, outer k, outer k+1, inner k+1) is CCW from +Z
        g.face((i0, o0, o1, i1), m)


def guard_tube(g, cx, cy, r_out, r_in, z0, z1, segs, windows, m=0, lip=0.03):
    """Thick tube from z0 to z1 with rectangular side windows and a chamfered lip.

    windows: [(centre_deg, half_width_deg, zw0, zw1)], angles CCW from +X.
    """
    def ang(j):
        return 2 * math.pi * j / segs

    def in_window(j):
        mid = math.degrees(ang(j + 0.5)) % 360
        for c, hw, zw0, zw1 in windows:
            d = (mid - c + 180) % 360 - 180
            if abs(d) < hw:
                return zw0, zw1
        return None

    def p(r, j, z):
        return g.vert((cx + r * math.cos(ang(j)), cy + r * math.sin(ang(j)), z))

    zt = z1 - lip
    for j in range(segs):
        j2 = j + 1
        win = in_window(j)
        intervals = [(z0, zt, z1)] if win is None else [(z0, win[0], win[0]), (win[1], zt, z1)]
        for za, zb_out, zb_in in intervals:
            g.face((p(r_out, j, za), p(r_out, j2, za), p(r_out, j2, zb_out), p(r_out, j, zb_out)), m)
            g.face((p(r_in, j, zb_in), p(r_in, j2, zb_in), p(r_in, j2, za), p(r_in, j, za)), m)
        if win is not None:
            zw0, zw1 = win
            # sill (faces +Z) and head (faces -Z)
            g.face((p(r_out, j, zw0), p(r_out, j2, zw0), p(r_in, j2, zw0), p(r_in, j, zw0)), m)
            g.face((p(r_in, j, zw1), p(r_in, j2, zw1), p(r_out, j2, zw1), p(r_out, j, zw1)), m)
            # jambs where the window meets solid wall
            if in_window((j - 1) % segs) is None:      # wall on the -theta side, jamb faces +theta
                g.face((p(r_in, j, zw0), p(r_in, j, zw1), p(r_out, j, zw1), p(r_out, j, zw0)), m)
            if in_window((j + 1) % segs) is None:      # wall on the +theta side, jamb faces -theta
                g.face((p(r_in, j2, zw0), p(r_out, j2, zw0), p(r_out, j2, zw1), p(r_in, j2, zw1)), m)
        # chamfered lip and top ring
        g.face((p(r_out, j, zt), p(r_out, j2, zt), p(r_out - lip, j2, z1), p(r_out - lip, j, z1)), m)
        g.face((p(r_out - lip, j, z1), p(r_out - lip, j2, z1), p(r_in, j2, z1), p(r_in, j, z1)), m)


# --------------------------------------------------------------------------
# Shared CR104P pieces
# --------------------------------------------------------------------------
def materials():
    return {
        # Only the base colour is exported; metallic/roughness just make the previews read.
        "chrome": px.material("chrome", CHROME, 0.3, 0.5),
        "field": px.material("plate_field", FIELD_BLACK, 0.55),
        "red_field": px.material("plate_red", RED_FIELD, 0.55),
        "satin": px.material("satin", SATIN, 0.65, 0.2),
        "housing": px.material("housing", HOUSING, 0.6),
        "phenolic": px.material("phenolic", PHENOLIC, 0.5),
        "mark": px.material("mark_white", MARK_WHITE, 0.5),
    }


def cap_material(tint):
    return px.material(f"cap_{tint}", CAPS[tint], 0.6)


def lens_material(tint):
    return px.material(f"lens_{tint}", LENSES[tint], 0.4)


def nameplate(M, cx, top, red=False):
    """GE CR104P Size 1 nameplate: satin legend band over a black (or red) field."""
    g = Geo(M["satin"], M["red_field"] if red else M["field"])
    x0, x1 = cx - PLATE_W / 2, cx + PLATE_W / 2
    y1, y0 = top, top - PLATE_H
    yb = top - BAND_H
    t = PLATE_T
    box(g, x0, yb, 0.0, x1, y1, t, 0)   # band (front + sides)
    box(g, x0, y0, 0.0, x1, yb, t, 1)   # field
    g.build("nameplate")
    px.label("legend", cx, top - BAND_H / 2, t + 0.002, 0.15)
    return yb


def octagon_ring(g, cx, cy, z0, m, height=OCT_H, inner=COLLAR_R - 0.02):
    """Round locking plate, then the chamfered octagonal mounting ring on top of it."""
    lathe(g, cx, cy, [(LOCK_D / 2, z0), (LOCK_D / 2, z0 + LOCK_T), (OCT_AF / 2 - 0.05, z0 + LOCK_T)], 24, m)
    z0 += LOCK_T
    rc = OCT_AF / 2 / math.cos(math.radians(22.5))
    lathe(g, cx, cy, [(rc - 0.03, z0), (rc, z0 + 0.03), (rc, z0 + height - 0.045), (rc - 0.055, z0 + height),
                      (inner, z0 + height)], 8, m, phase=math.radians(22.5))
    return z0 + height


def collar(g, cx, cy, z0, m, floor_m, segs=20):
    """Round collar in front of the octagon; its bore ends in a dark floor (the operator body)."""
    lathe(g, cx, cy, [(COLLAR_R, z0), (COLLAR_R, COLLAR_TOP - 0.035), (COLLAR_R - 0.035, COLLAR_TOP),
                      (COLLAR_IN, COLLAR_TOP), (COLLAR_IN, 0.25), (0.40, 0.25)], segs,
          [m, m, m, m, floor_m])


def operator_bezel(M, cx, cy, z0):
    """Locking plate, octagonal mounting ring and round collar (CR104PXG01/02/08)."""
    g = Geo(M["chrome"], M["housing"])
    zt = octagon_ring(g, cx, cy, z0, 0)
    collar(g, cx, cy, zt, 0, 1)
    return g.build("bezel")


def round_cap(mat, cx, cy, front, name="cap", part="cap", role="paint", tint=None, travel=CAP_TRAVEL,
              segs=20, crown=0.012, edge=0.03):
    """Plain cap: cylinder with a chamfered edge and a slightly crowned face peaking at `front`."""
    g = Geo(mat)
    lathe(g, cx, cy, [(CAP_R, 0.30), (CAP_R, front - crown - edge), (CAP_R - edge, front - crown),
                      (0, front)], segs, 0)
    return g.build(name, part=part, role=role, tint=tint, travel=travel)


# --------------------------------------------------------------------------
# Models
# --------------------------------------------------------------------------
PB_FOOT = 2.0  # square footprint holding the 1.93 x 1.98 nameplate, centred


def pb_layout():
    cx = PB_FOOT / 2
    top = -(PB_FOOT - PLATE_H) / 2
    return cx, top, top - HOLE_FROM_TOP


def build_pb(front, tint, mushroom=False, guard=False, lamp=False, red_plate=False):
    px.clear_scene()
    M = materials()
    cx, top, cy = pb_layout()
    nameplate(M, cx, top, red=red_plate)
    z0 = PLATE_T
    if guard:
        g = Geo(M["chrome"], M["housing"])
        zt = octagon_ring(g, cx, cy, z0, 0)
        r_in = GUARD_R - GUARD_WALL
        guard_tube(g, cx, cy, GUARD_R, r_in, zt, GUARD_FRONT, 24,
                   [(0, 31, 0.50, 1.02), (180, 31, 0.50, 1.02)], 0)
        lathe(g, cx, cy, [(r_in, 0.40), (CAP_R + 0.02, 0.40)], 24, 1)   # operator floor seen in the guard
        g.build("guard")
    else:
        operator_bezel(M, cx, cy, z0)
    mat = lens_material(tint) if lamp else cap_material(tint)
    role = "lamp" if lamp else "paint"
    if mushroom:
        g = Geo(mat)
        rh = MUSH_D / 2
        f = MUSH_FRONT
        lathe(g, cx, cy, [(0.36, 0.30), (0.36, 0.60), (0.58, 0.655), (rh, 0.75), (rh - 0.01, f - 0.21),
                          (rh - 0.09, f - 0.10), (0.42, f - 0.035), (0, f)], 24, 0)
        g.build("cap", part="cap", role=role, tint=tint, travel=CAP_TRAVEL)
    elif lamp:
        # Illuminated lens cap: slightly tapered, chamfered, one fresnel step on the face.
        g = Geo(mat)
        r = ILLUM_R
        lathe(g, cx, cy, [(r, 0.30), (r - 0.012, front - 0.04), (r - 0.04, front), (0.27, front),
                          (0.25, front + 0.012), (0, front + 0.016)], 20, 0)
        g.build("cap", part="cap", role="lamp", tint=tint, travel=CAP_TRAVEL)
    else:
        round_cap(mat, cx, cy, front, role=role, tint=tint)
    return cx, cy


MODELS = {}


def model(name, w, h, source, moving):
    def deco(fn):
        MODELS[name] = (fn, w, h, source, moving)
        return fn
    return deco


@model("pb_flush", PB_FOOT, PB_FOOT, SRC_CAT, "press")
def pb_flush():
    build_pb(FLUSH_FRONT, "black")


@model("pb_extended", PB_FOOT, PB_FOOT, SRC_CAT, "press")
def pb_extended():
    build_pb(EXT_FRONT, "green")


@model("pb_mushroom", PB_FOOT, PB_FOOT, SRC_CAT + "; 1-3/8 in mushroom head p.9-7", "press")
def pb_mushroom():
    build_pb(MUSH_FRONT, "red", mushroom=True, red_plate=True)


@model("pb_guarded", PB_FOOT, PB_FOOT, SRC_CAT + "; guard ring CR104PXG04 1.20 in deep p.9-38", "press")
def pb_guarded():
    build_pb(GUARDED_FRONT, "red", guard=True)


@model("pb_illuminated", PB_FOOT, PB_FOOT, SRC_CAT + "; illuminated extended operator p.9-6", "press+lit")
def pb_illuminated():
    build_pb(ILLUM_FRONT, "amber", lamp=True)


def round_lens(cx, cy, tint):
    g = Geo(lens_material(tint))
    rib = 0.025
    f = LENS_FRONT
    prof = [(LENS_R, 0.30), (LENS_R, 0.70), (LENS_R - 0.025, 0.785), (0.38, f - 0.055), (0.22, f - 0.012), (0, f)]
    lathe(g, cx, cy, prof, 24, 0, rmod=lambda i, j, r: r - (rib if (i <= 1 and j % 2) else 0.0))
    g.build("lens", part="lens", role="lamp", tint=tint)


LR_FOOT = 1.70


@model("light_round", LR_FOOT, LR_FOOT, SRC_CAT + "; standard indicating light p.9-15, lens 0.92 in", "lit")
def light_round():
    px.clear_scene()
    M = materials()
    cx, cy = LR_FOOT / 2, -LR_FOOT / 2
    operator_bezel(M, cx, cy, 0.0)
    round_lens(cx, cy, "red")


@model("light_round_plate", PB_FOOT, PB_FOOT, SRC_CAT + "; standard indicating light p.9-15, lens 0.92 in", "lit")
def light_round_plate():
    px.clear_scene()
    M = materials()
    cx, top, cy = pb_layout()
    nameplate(M, cx, top)
    operator_bezel(M, cx, cy, PLATE_T)
    round_lens(cx, cy, "white")


ET_FOOT = 1.10


@model("light_et16", ET_FOOT, ET_FOOT, SRC_ET16, "lit")
def light_et16():
    px.clear_scene()
    M = materials()
    cx, cy = ET_FOOT / 2, -ET_FOOT / 2
    rn = ET16_NUT_D / 2
    g = Geo(M["phenolic"])
    lathe(g, cx, cy, [(rn, 0.0), (rn, 0.085), (rn - 0.04, 0.115), (0.40, 0.115)], 24, 0,
          rmod=lambda i, j, r: r - (0.022 if (i <= 1 and j % 2) else 0.0))
    g.build("bezel")
    rc = ET16_CAP_D / 2
    rd = rc - 0.02
    zc = ET16_FRONT - rd
    prof = [(rc, 0.10), (rc, 0.22), (rd, 0.235)]
    for k in range(5):
        phi = math.radians(90 * k / 4)
        prof.append((rd * math.cos(phi) if k < 4 else 0, zc + rd * math.sin(phi)))
    g = Geo(lens_material("green"))
    lathe(g, cx, cy, prof, 24, 0, rmod=lambda i, j, r: r - (0.018 if (i <= 1 and j % 2) else 0.0))
    g.build("lens", part="lens", role="lamp", tint="green")


SQ_FOOT = 1.0


@model("light_square", SQ_FOOT, SQ_FOOT, SRC_ASM + "; 3/4 in square-lens indicator", "lit")
def light_square():
    px.clear_scene()
    M = materials()
    cx, cy = SQ_FOOT / 2, -SQ_FOOT / 2
    s2 = math.sqrt(2)
    hb, hl = SQ_BEZEL / 2, SQ_LENS / 2
    g = Geo(M["phenolic"])
    lathe(g, cx, cy, [(hb * s2, 0.0), (hb * s2, 0.10), ((hb - 0.035) * s2, 0.135), ((hl + 0.025) * s2, 0.135),
                      ((hl + 0.025) * s2, 0.08), ((hl - 0.03) * s2, 0.08)], 4, 0, phase=math.radians(45))
    g.build("bezel")
    g = Geo(lens_material("white"))
    lathe(g, cx, cy, [(hl * s2, 0.08), (hl * s2, SQ_FRONT - 0.035), ((hl - 0.035) * s2, SQ_FRONT)], 4, 0,
          phase=math.radians(45), ngon_cap=True)
    g.build("lens", part="lens", role="lamp", tint="white")
    px.label("legend", cx, cy, SQ_FRONT + 0.002, 0.10)


KN_FOOT = 2.25


@model("knob_fluted", KN_FOOT, KN_FOOT, SRC_ASM + "; fluted instrument knob on a 270 deg dial plate", "rotate")
def knob_fluted():
    px.clear_scene()
    M = materials()
    cx, cy = KN_FOOT / 2, -KN_FOOT / 2
    s2 = math.sqrt(2)
    hp = KNOB_PLATE / 2
    zp = 0.06
    g = Geo(M["field"], M["satin"], M["mark"])
    lathe(g, cx, cy, [(hp * s2, 0.0), (hp * s2, zp - 0.03), ((hp - 0.03) * s2, zp)], 4, [0, 1],
          phase=math.radians(45), ngon_cap=True, cap_mat=0)
    for k in range(11):
        a = -KNOB_SWEEP + 2 * KNOB_SWEEP * k / 10
        major = k % 5 == 0
        radial_bar(g, cx, cy, a, 0.70 if major else 0.80, 0.955, 0.055 if major else 0.032, zp, zp + 0.012, 2)
    arc_band(g, cx, cy, 0.93, 0.955, -KNOB_SWEEP, KNOB_SWEEP, 30, zp + 0.006, 2)
    g.build("dial")
    zs = zp
    g = Geo(M["phenolic"], M["mark"])
    lathe(g, cx, cy, [(KNOB_SKIRT_R, zs), (KNOB_SKIRT_R, zs + 0.09), (KNOB_SKIRT_R - 0.035, zs + 0.13),
                      (0.30, zs + 0.13)], 32, 0)
    flute = lambda i, j, r: r - (0.05 if (i <= 1 and j % 3 == 2) else 0.0)   # 12 flutes: land, land, groove
    lathe(g, cx, cy, [(KNOB_R, zs + 0.13), (KNOB_R, KNOB_TOP - 0.05), (KNOB_R - 0.05, KNOB_TOP - 0.01),
                      (0, KNOB_TOP)], 36, 0, phase=math.radians(90 - 5), rmod=flute)
    # White pointer line over the top, down the front land, and across the skirt.
    profile_strip(g, cx, cy, 0.0, [(0.0, KNOB_TOP), (KNOB_R - 0.05, KNOB_TOP - 0.01), (KNOB_R, KNOB_TOP - 0.05),
                                   (KNOB_R, zs + 0.13)], 0.05, 0.012, 1)
    profile_strip(g, cx, cy, 0.0, [(KNOB_R, zs + 0.13), (KNOB_SKIRT_R - 0.035, zs + 0.13),
                                   (KNOB_SKIRT_R, zs + 0.09), (KNOB_SKIRT_R, zs + 0.01)], 0.05, 0.012, 1)
    g.build("knob", part="knob", origin=(cx, cy, 0.0), pivot=(cx, cy, 0.0), axis=(0, 0, 1))
    # Scale numerals just outside the tick ends (anchor = centre of the text box).
    for lid, a, r in (("min", -KNOB_SWEEP, 1.10), ("mid", 0.0, 1.04), ("max", KNOB_SWEEP, 1.10)):
        px.label(lid, cx + r * math.sin(math.radians(a)), cy + r * math.cos(math.radians(a)), zp + 0.001, 0.12)
    px.label("caption", cx, cy - 0.93, zp + 0.001, 0.11)


# --------------------------------------------------------------------------
# Previews
# --------------------------------------------------------------------------
def lamp_materials():
    mats = set()
    for o in bpy.context.scene.objects:
        if o.type == "MESH" and o.get("bwr_role") == "lamp":
            mats.update(s.material for s in o.material_slots if s.material)
    return mats


def set_lamp(state, strength=1.3, dim=0.3):
    """Preview only. "lit": full-bright (emission of the authored colour, no shading), as
    the game draws a lit lamp; "off": the lens dimmed as the game dims an unlit lamp;
    "authored": the plain lens colour as exported."""
    for mat in lamp_materials():
        bsdf = mat.node_tree.nodes.get("Principled BSDF")
        base = bsdf.inputs["Base Color"]
        if "authored_base" not in mat:
            mat["authored_base"] = list(base.default_value)
        col = list(mat["authored_base"])
        bsdf.inputs["Emission Strength"].default_value = 0.0
        if state == "lit":
            bsdf.inputs["Emission Color"].default_value = col
            bsdf.inputs["Emission Strength"].default_value = strength
            base.default_value = (0.0, 0.0, 0.0, 1.0)
        elif state == "off":
            base.default_value = (col[0] * dim, col[1] * dim, col[2] * dim, 1.0)
        else:
            base.default_value = col


def render_previews(name, w, h, moving):
    os.makedirs(PREVIEW_DIR, exist_ok=True)
    base = os.path.join(PREVIEW_DIR, name)
    outs = px.preview(base, w, h)
    if "press" in moving:
        cap = bpy.data.objects["cap"]
        cap.location.z -= cap["bwr_travel"]
        outs += px.preview(base + "_pressed", w, h, views=("three_quarter", "side"))
        cap.location.z += cap["bwr_travel"]
    if "lit" in moving:
        for state in ("off", "lit"):
            set_lamp(state)
            outs += px.preview(base + "_" + state, w, h, views=("front", "three_quarter"))
        set_lamp("authored")
    if moving == "rotate":
        knob = bpy.data.objects["knob"]
        for tag_, deg in (("min", KNOB_SWEEP), ("max", -KNOB_SWEEP)):   # Blender +Z = counter-clockwise
            knob.rotation_euler.z = math.radians(deg)
            outs += px.preview(base + "_" + tag_, w, h, views=("front", "three_quarter"))
        knob.rotation_euler.z = 0.0
    return outs


def main():
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    only = None
    if "--only" in argv:
        only = set(argv[argv.index("--only") + 1].split(","))
    previews = "--no-preview" not in argv
    for name, (fn, w, h, source, moving) in MODELS.items():
        if only and name not in only:
            continue
        fn()
        px.export(name, w, h, source)
        if previews:
            render_previews(name, w, h, moving)


main()
