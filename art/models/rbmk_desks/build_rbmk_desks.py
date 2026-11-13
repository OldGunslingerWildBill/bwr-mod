"""The three operator control desks of the Chernobyl unit 4 main control room, as plain unpopulated shells.

Run headless from the repo root (pip bpy module, no blender binary needed):
    python3 art/models/rbmk_desks/build_rbmk_desks.py
It wipes the scene, builds everything from the PARAMETERS block below and saves
    art/models/rbmk_desks/rbmk_desks.blend
Previews are made by the companion script:
    python3 art/models/rbmk_desks/render_previews.py

What it makes
  Desk_A  reactor desk,   sections 1A..5A   (Cyrillic 1А..5А)
  Desk_P  middle desk,    sections 1P..6P   (Cyrillic 1П..6П)
  Desk_T  turbine desk,   sections 1T..7T, 1E, 2E   (Cyrillic 1Т..7Т, 1Э, 2Э)
No instruments, switches, labels or cut-outs: the shells are meant to be modified later.

One cross-section (PROFILE) is shared by every section of every desk. A section is that profile extruded along
its width, with mitred ends where it meets its neighbours, so a desk is a faceted arc that is concave toward the
seated operator (its ends come forward, its rear is convex).

Profile coordinates (mm), used throughout:
    y  depth measured rearward from the desktop's front top edge (y = 0 is that edge, the rear top edge is y = DEPTH)
    z  height above the floor
The section widths in LAYOUT are measured along the desktop front edge (the chord at y = 0).

Scene frame: 1 Blender unit = 1 m, Z up, floor at Z = 0. Every desk faces -Y (the operator stands at -Y and looks
toward +Y). Desks stand side by side along +X, DESK_GAP apart, in DESK_ORDER.

Desk origin ("rear centre"): every mesh object of a desk has its object origin at that desk's origin, with identity
rotation and scale (mesh data holds the geometry). The origin is on the floor (Z = 0); its X is the midpoint of the
chord between the desk's two outer front corners (that chord is parallel to X); its Y is the rearmost point of the
desk's rear top edge (doors not counted). So the convex rear just touches the plane Y = origin.y.

Object names (per section, <D> = A/P/T, <S> = section id):
    <D>_<S>_body      the painted carcass: rear wall, top, apron, underside, knee-recess front (dark)
    <D>_<S>_riser     the near-vertical instrument face (plain panel slab)
    <D>_<S>_desktop   the sloping blue-gray desktop slab (shortened in a writing-shelf section)
    <D>_<S>_shelf     only in a writing-shelf section: the lowered flat writing shelf at the front
    <D>_<S>_doors     the two flat rear service doors
    <D>_<S>_plinth    the floor plinth (protrudes slightly at front, rear and the desk ends)
per desk:
    <D>_cheek_L, <D>_cheek_R   the flat end cheeks, following the outer profile
"""
import math
import os

import bpy
import bmesh  # noqa: E402  (the pip bpy module only exposes bmesh after bpy is imported)

try:
    HERE = os.path.dirname(os.path.abspath(__file__))
except NameError:
    HERE = os.path.dirname(os.path.abspath(bpy.data.filepath))
BLEND = os.path.join(HERE, "rbmk_desks.blend")

# =====================================================================================================================
# PARAMETERS  (all lengths in millimetres, angles in degrees) -- edit here, then rerun the script
# =====================================================================================================================
PROFILE = dict(
    # Scale comes from standard drawing 01 (1250 deep, 1200 high). Everything else was measured off the rectified
    # end view 05 of the recreation; where drawing and recreation disagree the recreation wins.
    HEIGHT=1200.0,           # floor to the top of the riser (front edge of the top)   (drawing 01: 1200)
    DEPTH=1250.0,            # desktop front top edge to the rear top edge             (drawing 01: 1250)
    FRONT_Z=780.0,           # height of the desktop front top edge   (05: 784; drawing dimension says 760)
    TOP_RISE=40.0,           # the top is not level: its rear edge is this much lower than its front edge (01, 05)
    TOP_DEPTH=300.0,         # top, rear top edge to the riser's front top edge        (05: 297, 01: ~313)
    REAR_LEAN=300.0,         # the rear wall leans back: rear top edge is this much behind the rear foot at the
                             # floor (05: ~14.8 deg; drawing 01 shows only ~6 deg = ~120)
    PEDESTAL_BASE=400.0,     # pedestal depth at the floor, rear foot to knee-recess front (05: 417 at plinth top)
    RISER_LEAN=85.0,         # riser front face: its base is this much forward of its top (05: ~14 deg)
    RISER_THICK=30.0,        # riser panel slab thickness (horizontal)
    DESK_SLOPE=5.5,          # desktop falls toward the operator by this angle         (05: 5.3, 01: 5-6)
    DESK_THICK=30.0,         # desktop slab thickness (vertical)                       (05: ~27)
    APRON_H=130.0,           # gray apron band under the desktop slab                  (05: ~130)
    APRON_SETBACK=20.0,      # the apron face stands this far behind the slab's front edge (05: ~23)
    UNDER_Z=535.0,           # height where the diagonal underside meets the knee-recess front (05: 536, 01: 521)
    PLINTH_H=105.0,          # floor plinth height                                     (05: 105)
    PLINTH_OUT=20.0,         # how far the plinth stands proud of the faces above it   (05: 15-25)
    CHEEK_THICK=20.0,        # end cheek plate thickness (counted inside the end section's width; not measurable)
    DOOR_THICK=20.0,         # rear service doors stand proud of the rear wall by this (09)
    DOOR_TOP_GAP=40.0,       # rear wall above the doors (measured vertically from the rear top edge) (est. 09)
    DOOR_BOTTOM_GAP=20.0,    # gap between the plinth top and the door bottoms (est. 09)
    DOOR_SIDE_GAP=30.0,      # gap from a section's edge to its outer door edge (est. 09)
    DOOR_MID_GAP=15.0,       # gap between the two doors of a section (09)
    SHELF_DROP=0.0,          # writing shelf top lies this far below FRONT_Z (02, 07: level with the neighbours)
    SHELF_DEPTH=410.0,       # level writing shelf, front edge to the step up to the sloping desktop (est. 03)
)

# Per desk: list of (section id, front-edge chord width mm, turn in degrees at the joint to the NEXT section,
# writing shelf flag). The last section's turn is ignored. Turns are positive = the desk bends toward the operator.
# Widths and turns were measured from the front views (02, 06, 07) with a fitted pinhole camera, scaled by the
# 1200 height. The ratios between sections and between desks are better known than the absolute widths (+-10 %).
LAYOUT = {
    "A": [("1A", 880.0, 6.0, False),
          ("2A", 880.0, 6.0, False),
          ("3A", 880.0, 6.0, True),
          ("4A", 880.0, 6.0, False),
          ("5A", 880.0, 0.0, False)],
    "P": [("1P", 890.0, 6.0, False),
          ("2P", 890.0, 6.0, False),
          ("3P", 890.0, 6.0, True),
          ("4P", 890.0, 6.0, False),
          ("5P", 890.0, 6.0, False),
          ("6P", 890.0, 0.0, False)],
    "T": [("1T", 660.0, 6.0, False),
          ("2T", 660.0, 6.0, False),
          ("3T", 805.0, 6.0, False),
          ("4T", 805.0, 6.0, True),
          ("5T", 640.0, 6.0, False),
          ("6T", 640.0, 6.0, False),
          ("7T", 765.0, 6.0, False),
          ("1E", 710.0, 6.0, False),
          ("2E", 710.0, 0.0, False)],
}
DESK_ORDER = ["A", "P", "T"]
DESK_GAP = 1500.0            # clear gap between neighbouring desks' bounding boxes along X

# Flat colours (sRGB hex), sampled from the reference images.
COLOURS = {
    "paint":  "#7f8a8e",     # console gray paint: rear, top, riser, apron, cheeks, doors
    "desk":   "#4d5f6d",     # blue-gray desktop / writing shelf
    "knee":   "#1d2124",     # dark knee-recess front of the pedestal
    "plinth": "#5c676c",     # floor plinth: the console paint, a shade darker (02, 05, 07)
}
# =====================================================================================================================

MM = 0.001


def srgb_to_linear(c):
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def make_materials():
    mats = {}
    for name, hexc in COLOURS.items():
        rgb = [srgb_to_linear(int(hexc[i:i + 2], 16) / 255.0) for i in (1, 3, 5)]
        m = bpy.data.materials.new("RBMK_desk_" + name)
        bsdf = m.node_tree.nodes.get("Principled BSDF")
        bsdf.inputs["Base Color"].default_value = (*rgb, 1.0)
        bsdf.inputs["Roughness"].default_value = 0.6 if name != "desk" else 0.45
        m.diffuse_color = (*rgb, 1.0)
        mats[name] = m
    return mats


# ---------------------------------------------------------------------------------------------------------------------
# The shared cross-section
# ---------------------------------------------------------------------------------------------------------------------
class Profile:
    """Derived points of the one cross-section all desks share. Polygons are lists of (y, z, material-of-edge-to-next)."""

    def __init__(self, p):
        self.p = p
        H, D = p["HEIGHT"], p["DEPTH"]
        self.H, self.D = H, D
        self.tan = math.tan(math.radians(p["DESK_SLOPE"]))
        self.yt = D - p["TOP_DEPTH"]                    # riser front, top
        self.yb = self.yt - p["RISER_LEAN"]             # riser front, base (on the desktop)
        self.zb = self.zt(self.yb)
        self.tr = p["RISER_THICK"]
        self.t = p["DESK_THICK"]
        self.zf = p["FRONT_Z"]
        self.z_apron = self.zf - self.t - p["APRON_H"]
        self.pl = p["PLINTH_H"]
        self.y_foot = D - p["REAR_LEAN"]                # rear wall at the floor
        self.y_ped = self.y_foot - p["PEDESTAL_BASE"]   # knee-recess front (vertical)
        self.z_under = p["UNDER_Z"]
        self.zr = H - p["TOP_RISE"]                     # rear top edge height
        self.sb = p["APRON_SETBACK"]
        self.zs = self.zf - p["SHELF_DROP"]
        self.sd = p["SHELF_DEPTH"]
        assert 0 < self.y_ped < self.yb and self.z_under < self.z_apron and self.pl < self.z_under
        assert self.zs - self.t > self.z_apron and self.zs <= self.zt(self.sd) and self.sd < self.yb

    def zt(self, y):
        """Desktop top surface height at depth y."""
        return self.p["FRONT_Z"] + y * self.tan

    def y_rear(self, z):
        """Rear wall depth at height z (the wall leans back toward the top)."""
        return self.y_foot + self.p["REAR_LEAN"] * z / self.zr

    def _lower(self):
        """Body outline from the riser's back up over the top, down the rear and round the knee, to the apron foot."""
        return [(self.yb + self.tr, self.zb, "paint"),       # riser back (contact)
                (self.yt + self.tr, self.H, "paint"),        # flat top
                (self.D, self.zr, "paint"),                  # rear wall
                (self.y_rear(self.pl), self.pl, "plinth"),   # bottom (on the plinth, hidden)
                (self.y_ped, self.pl, "knee"),               # knee-recess front
                (self.y_ped, self.z_under, "paint"),         # underside
                (self.sb, self.z_apron, "paint")]            # apron, up to the desktop / shelf

    def body(self, shelf):
        if shelf:
            head = [(self.sb, self.zs - self.t, "desk"), (self.sd, self.zs - self.t, "desk"),
                    (self.sd, self.zs, "desk"), (self.yb, self.zb - self.t, "desk")]
        else:
            head = [(self.sb, self.zt(self.sb) - self.t, "desk"), (self.yb, self.zb - self.t, "desk")]
        return head + [(self.yb, self.zb, "paint")] + self._lower()

    def desktop(self, shelf):
        if shelf:
            return [(self.sd, self.zs, "desk"), (self.sd, self.zt(self.sd), "desk"),
                    (self.yb, self.zb, "desk"), (self.yb, self.zb - self.t, "desk")]
        return [(0.0, self.zf - self.t, "desk"), (0.0, self.zf, "desk"),
                (self.yb, self.zb, "desk"), (self.yb, self.zb - self.t, "desk")]

    def shelf(self):
        return [(0.0, self.zs - self.t, "desk"), (0.0, self.zs, "desk"),
                (self.sd, self.zs, "desk"), (self.sd, self.zs - self.t, "desk")]

    def riser(self):
        return [(self.yb, self.zb, "paint"), (self.yt, self.H, "paint"),
                (self.yt + self.tr, self.H, "paint"), (self.yb + self.tr, self.zb, "paint")]

    def outer(self, shelf):
        """Outline of the whole section, for the end cheeks."""
        if shelf:
            head = [(0.0, self.zs - self.t, "paint"), (0.0, self.zs, "paint"), (self.sd, self.zs, "paint"),
                    (self.sd, self.zt(self.sd), "paint")]
            zu = self.zs - self.t
        else:
            head = [(0.0, self.zf - self.t, "paint"), (0.0, self.zf, "paint")]
            zu = self.zf - self.t
        return head + [(self.yb, self.zb, "paint"), (self.yt, self.H, "paint"), (self.D, self.zr, "paint"),
                       (self.y_rear(self.pl), self.pl, "paint"), (self.y_ped, self.pl, "paint"),
                       (self.y_ped, self.z_under, "paint"), (self.sb, self.z_apron, "paint"), (self.sb, zu, "paint")]

    def plinth(self):
        o = self.p["PLINTH_OUT"]
        y0, y1 = self.y_ped - o, self.y_rear(self.pl) + o
        return [(y0, 0.0, "plinth"), (y0, self.pl, "plinth"), (y1, self.pl, "plinth"), (y1, 0.0, "plinth")]


# ---------------------------------------------------------------------------------------------------------------------
# Plan layout
# ---------------------------------------------------------------------------------------------------------------------
def v_add(a, b):
    return (a[0] + b[0], a[1] + b[1])


def v_mul(a, s):
    return (a[0] * s, a[1] * s)


def v_dot(a, b):
    return a[0] * b[0] + a[1] * b[1]


class Plan:
    """Front-edge polyline of one desk in plan (mm), front toward -Y, chord between the end corners along +X."""

    def __init__(self, sections):
        pts, dirs = [(0.0, 0.0)], []
        th = 0.0
        for i, (_sid, w, turn, _sh) in enumerate(sections):
            d = (math.cos(th), math.sin(th))
            dirs.append(th)
            pts.append(v_add(pts[-1], v_mul(d, w)))
            th -= math.radians(turn)            # turning right = bending toward the operator at -Y
        # rotate so the end chord lies along +X
        c = math.atan2(pts[-1][1] - pts[0][1], pts[-1][0] - pts[0][0])
        cc, ss = math.cos(-c), math.sin(-c)
        self.pts = [(x * cc - y * ss, x * ss + y * cc) for x, y in pts]
        self.th = [t - c for t in dirs]
        self.n = len(sections)

    def u(self, i):
        return (math.cos(self.th[i]), math.sin(self.th[i]))

    def r(self, i):
        """Rearward horizontal direction of section i (away from the operator)."""
        return (-math.sin(self.th[i]), math.cos(self.th[i]))

    def joint(self, j):
        """(point, mitre direction scaled so that moving y mm rearward in both neighbours lands on it)."""
        if j == 0:
            return self.pts[0], self.r(0)
        if j == self.n:
            return self.pts[-1], self.r(self.n - 1)
        ra, rb = self.r(j - 1), self.r(j)
        m = v_add(ra, rb)
        k = v_dot(m, ra)                        # |m| cos(half angle) -> scale so its rearward component is 1
        return self.pts[j], v_mul(m, 1.0 / k)

    def bounds(self, i, out_l=0.0, out_r=0.0):
        """Left and right boundary functions y -> plan point for section i. At a desk end the boundary is square and
        can be shifted along the section by out_l / out_r (positive = outward)."""
        def make(j, out, sgn):
            p, m = self.joint(j)
            end = (j == 0) or (j == self.n)
            if end:
                p = v_add(p, v_mul(self.u(i), sgn * out))
            return lambda y: v_add(p, v_mul(m, y))
        return make(i, out_l, -1.0), make(i + 1, out_r, 1.0)


# ---------------------------------------------------------------------------------------------------------------------
# Mesh building
# ---------------------------------------------------------------------------------------------------------------------
def ear_clip(pts):
    """Triangulate a simple 2D polygon (list of (y, z)) by ear clipping. Returns index triples."""
    n = len(pts)
    area2 = sum(pts[i][0] * pts[(i + 1) % n][1] - pts[(i + 1) % n][0] * pts[i][1] for i in range(n))
    sgn = 1.0 if area2 > 0 else -1.0

    def cross(o, a, b):
        return ((pts[a][0] - pts[o][0]) * (pts[b][1] - pts[o][1]) - (pts[a][1] - pts[o][1]) * (pts[b][0] - pts[o][0])) * sgn

    rest, out = list(range(n)), []
    while len(rest) > 3:
        for k in range(len(rest)):
            a, b, c = rest[k - 1], rest[k], rest[(k + 1) % len(rest)]
            if cross(a, b, c) <= 1e-9:
                continue
            if any(cross(a, b, q) >= -1e-9 and cross(b, c, q) >= -1e-9 and cross(c, a, q) >= -1e-9
                   for q in rest if q not in (a, b, c)):
                continue
            out.append((a, b, c))
            rest.pop(k)
            break
        else:
            raise RuntimeError("ear clipping failed: polygon is not simple")
    out.append(tuple(rest))
    return out


def orient_outward(bm):
    """Make the closed shell's normals consistent and pointing out (positive signed volume)."""
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces[:])
    if bm.calc_volume(signed=True) < 0:
        bmesh.ops.reverse_faces(bm, faces=bm.faces[:])


def prism(name, poly, left, right, mats, cap_mat, offset, collection):
    """Extrude the (y, z, mat) polygon between the boundary functions left(y) / right(y) (plan points, mm)."""
    bm = bmesh.new()
    mat_names = list(mats.keys())

    def vert(fn, y, z):
        x, yy = fn(y)
        return bm.verts.new(((x - offset[0]) * MM, (yy - offset[1]) * MM, z * MM))

    L = [vert(left, y, z) for y, z, _ in poly]
    R = [vert(right, y, z) for y, z, _ in poly]
    n = len(poly)
    for k in range(n):
        f = bm.faces.new((L[k], L[(k + 1) % n], R[(k + 1) % n], R[k]))
        f.material_index = mat_names.index(poly[k][2])
    caps = []
    for a, b, c in ear_clip([(y, z) for y, z, _ in poly]):   # concave end caps as triangles, joined to quads
        caps.append(bm.faces.new((L[a], L[b], L[c])))
        caps.append(bm.faces.new((R[c], R[b], R[a])))
    for f in caps:
        f.material_index = mat_names.index(cap_mat)
    bmesh.ops.join_triangles(bm, faces=caps, angle_face_threshold=0.2, angle_shape_threshold=3.0)
    orient_outward(bm)
    me = bpy.data.meshes.new(name)
    bm.to_mesh(me)
    bm.free()
    for m in mats.values():
        me.materials.append(m)
    # drop unused material slots so each object lists only what it uses
    used = sorted({p.material_index for p in me.polygons})
    remap = {old: new for new, old in enumerate(used)}
    index = [remap[p.material_index] for p in me.polygons]
    keep = [me.materials[i] for i in used]
    me.materials.clear()                 # this resets every face to slot 0, so the indices are written afterwards
    for m in keep:
        me.materials.append(m)
    for p, k in zip(me.polygons, index):
        p.material_index = k
    ob = bpy.data.objects.new(name, me)
    collection.objects.link(ob)
    return ob


def doors(name, prof, plan, i, left, right, mats, offset, collection):
    """Two flat door slabs on section i's rear wall."""
    p = prof.p
    z0 = prof.pl + p["DOOR_BOTTOM_GAP"]
    z1 = prof.zr - p["DOOR_TOP_GAP"]
    P0, u, r = plan.pts[i], plan.u(i), plan.r(i)

    def ucoord(pt):
        return v_dot((pt[0] - P0[0], pt[1] - P0[1]), u)

    lo = max(ucoord(left(prof.y_rear(z))) for z in (z0, z1)) + p["DOOR_SIDE_GAP"]
    hi = min(ucoord(right(prof.y_rear(z))) for z in (z0, z1)) - p["DOOR_SIDE_GAP"]
    mid = 0.5 * (lo + hi)
    g = 0.5 * p["DOOR_MID_GAP"]
    # outward normal of the leaning rear wall in (y, z): rearward and slightly down
    dy, dz = p["REAR_LEAN"], prof.zr
    ln = math.hypot(dy, dz)
    ny, nz = dz / ln, -dy / ln
    t = p["DOOR_THICK"]

    bm = bmesh.new()

    def pt(uu, z, out):
        y = prof.y_rear(z) + out * ny
        zz = z + out * nz
        x, yy = v_add(v_add(P0, v_mul(u, uu)), v_mul(r, y))
        return bm.verts.new(((x - offset[0]) * MM, (yy - offset[1]) * MM, zz * MM))

    for a, b in ((lo, mid - g), (mid + g, hi)):
        v = [pt(a, z0, 0), pt(b, z0, 0), pt(b, z1, 0), pt(a, z1, 0),
             pt(a, z0, t), pt(b, z0, t), pt(b, z1, t), pt(a, z1, t)]
        for q in ((0, 1, 2, 3), (4, 7, 6, 5), (0, 4, 5, 1), (1, 5, 6, 2), (2, 6, 7, 3), (3, 7, 4, 0)):
            bm.faces.new([v[k] for k in q])
    orient_outward(bm)
    me = bpy.data.meshes.new(name)
    bm.to_mesh(me)
    bm.free()
    me.materials.append(mats["paint"])
    ob = bpy.data.objects.new(name, me)
    collection.objects.link(ob)
    return ob


def build_desk(key, prof, mats, scene_coll):
    sections = LAYOUT[key]
    plan = Plan(sections)
    coll = bpy.data.collections.new("Desk_" + key)
    scene_coll.children.link(coll)
    p = prof.p
    ct, po = p["CHEEK_THICK"], p["PLINTH_OUT"]

    # desk origin: chord midpoint in X, rearmost rear-top point in Y (see module docstring)
    rear_y = max(plan.joint(j)[0][1] + plan.joint(j)[1][1] * prof.D for j in range(plan.n + 1))
    origin = ((plan.pts[0][0] + plan.pts[-1][0]) * 0.5, rear_y)
    objs = []
    last = plan.n - 1
    for i, (sid, _w, _turn, shelf) in enumerate(sections):
        cl = -ct if i == 0 else 0.0                   # shorten the end sections by the cheek thickness
        cr = -ct if i == last else 0.0
        left, right = plan.bounds(i, cl, cr)
        base = "%s_%s_" % (key, sid)
        objs.append(prism(base + "body", prof.body(shelf), left, right, mats, "paint", origin, coll))
        objs.append(prism(base + "riser", prof.riser(), left, right, mats, "paint", origin, coll))
        objs.append(prism(base + "desktop", prof.desktop(shelf), left, right, mats, "desk", origin, coll))
        if shelf:
            objs.append(prism(base + "shelf", prof.shelf(), left, right, mats, "desk", origin, coll))
        objs.append(doors(base + "doors", prof, plan, i, left, right, mats, origin, coll))
        pl, pr = plan.bounds(i, po if i == 0 else 0.0, po if i == last else 0.0)
        objs.append(prism(base + "plinth", prof.plinth(), pl, pr, mats, "plinth", origin, coll))
    # end cheeks
    for side, i in (("L", 0), ("R", last)):
        shelf = sections[i][3]
        a, b = plan.bounds(i, 0.0, 0.0)
        a2, b2 = plan.bounds(i, -ct, -ct)
        lft, rgt = (a, a2) if side == "L" else (b2, b)
        objs.append(prism("%s_cheek_%s" % (key, side), prof.outer(shelf), lft, rgt, mats, "paint", origin, coll))
    return coll, objs


def clean_scene():
    for coll in list(bpy.data.collections):
        bpy.data.collections.remove(coll)
    for ob in list(bpy.data.objects):
        bpy.data.objects.remove(ob, do_unlink=True)
    for block in (bpy.data.meshes, bpy.data.materials, bpy.data.cameras, bpy.data.lights):
        for item in list(block):
            block.remove(item)


def main():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    clean_scene()
    scene = bpy.context.scene
    scene.unit_settings.system = "METRIC"
    scene.unit_settings.scale_length = 1.0
    prof = Profile(PROFILE)
    mats = make_materials()
    x_cursor = 0.0
    for key in DESK_ORDER:
        coll, objs = build_desk(key, prof, mats, scene.collection)
        xs = [(ob.matrix_world @ v.co).x for ob in objs for v in ob.data.vertices]
        shift = x_cursor - min(xs)
        for ob in objs:
            ob.location.x += shift
        x_cursor += (max(xs) - min(xs)) + DESK_GAP * MM
        print("Desk_%s: %d objects, width %.3f m" % (key, len(objs), max(xs) - min(xs)))
    # centre the whole row on X = 0
    bpy.context.view_layer.update()
    allx = [(ob.matrix_world @ v.co).x for ob in scene.objects for v in ob.data.vertices]
    c = 0.5 * (min(allx) + max(allx))
    for ob in scene.objects:
        ob.location.x -= c
    for key in DESK_ORDER:
        o = bpy.data.collections["Desk_" + key].objects[0].location
        print("Desk_%s origin (rear centre, floor): (%.3f, %.3f, 0) m" % (key, o.x, o.y))
    bpy.ops.wm.save_as_mainfile(filepath=BLEND)
    print("saved", BLEND)


if __name__ == "__main__":
    main()
