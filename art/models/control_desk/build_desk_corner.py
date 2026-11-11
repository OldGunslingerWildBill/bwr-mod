"""The 45 degree desk corners (CONTROL-PANELS-SPEC section 14), as block OBJ models, with Blender.

Run headless (never against an open scene):
    blender --background --factory-startup --python art/models/control_desk/build_desk_corner.py
Writes, to mod/src/main/resources/assets/bwr/models/block/control_panel/ (the shared materials.mtl there is
build_trim.py's), for each of the two corners (inside, outside):
    desk_corner_<kind>_<part>.obj    the four blocks (origin, left, deep, deep_left) of the piece, each a slice of the console
    desk_corner_<kind>_item.obj      the whole piece, centred on its cube, for the item model
and preview renders to art/models/control_desk/previews/ (closed iso view of each corner, and a run + corner + run
arrangement of each, straight desks on both sides, from the operator's side and from above).

THE PIECE. The straight desk (build_desk.py, the owner's Fusion 'Control Desk' profile as remodelled in alpha.60) is one
constant cross-section, PROFILE below (back at depth 0), one block wide. A corner bends that same console through two 45
degree bends so that a run and a second run at 90 degrees to it read as one console with a chamfered corner section:
the depth W of the console stays the same all the way round (mitred joints). The plan is in DeskCornerGeometry.java, which
has the same numbers and is the authority; this script repeats them and checks the mounting frame against them.

Footprint (x to the operator's right, f from the back toward the operator, in blocks), 2 x 2, the straight run arriving at
x = 2 exactly where a straight desk stands (back f = 0, W deep):
    INSIDE  (the console wraps around the operator, the U): the second run leaves at f = 2 with its back at x = 0 (facing
            right); the operator-side mitre vertices are (1.5, W) and (W, 1.5), the back diagonal x + f = INSIDE_BACK.
    OUTSIDE (the console wraps away from the operator): the second run leaves at f = -1 (backwards) in x = 0.25..1, facing
            left; operator-side (convex) mitre vertices (1.5, W) and (0.25, -0.5), back diagonal x - f = OUTSIDE_BACK.
The four blocks are the footprint's cells; the model of each block is the console clipped to it. Authoring frame as in
build_desk.py: facing north, operator at -Z; world() maps (lx, y, f) of a block to block space (1 - lx, y, 1 - f).

THE FACE. The level top of the angled section carries components: 16 columns (1.0 m) along the diagonal by 10 rows
(0.625 m, from the foot of the riser to the front edge), the same 16 x 10 cells as a straight desk. Its frame is
PanelGeometry's DESK_CORNER_INSIDE / DESK_CORNER_OUTSIDE (the columns run along the diagonal). It is not a separate polygon
(the whole top is one material); this script checks that the frame's rectangle lies on the top of the console.
"""
import math
import os
import sys

import bpy

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
ASSETS = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "bwr")
MODELS = os.path.join(ASSETS, "models", "block", "control_panel")
TEXTURES = os.path.join(ASSETS, "textures", "block", "control_panel")
PREVIEW = os.path.join(HERE, "previews")

SQ2 = math.sqrt(2.0)
EPS = 1e-9

# --- the straight desk profile (build_desk.py), (depth t from the back, height y) ---
TOP = 0.75
SHELF = 1.10
FACE_BACK = 0.125
W = 0.75                      # FACE_FRONT: the depth of the console
RISER_HEAD = 0.075
KNEE_FOOT = 0.2
LIP = 0.0625
UNDER = TOP - LIP
KNEE_TOP = KNEE_FOOT + (0.5 - KNEE_FOOT) * UNDER / TOP
PROFILE = [(W, TOP), (FACE_BACK, TOP), (RISER_HEAD, SHELF), (0.0, SHELF), (0.0, 0.0), (KNEE_FOOT, 0.0),
           (KNEE_TOP, UNDER), (W, UNDER)]
END_QUADS = [[(0.0, 0.0), (KNEE_FOOT, 0.0), (KNEE_TOP, UNDER), (FACE_BACK, TOP)],
             [(KNEE_TOP, UNDER), (W, UNDER), (W, TOP), (FACE_BACK, TOP)],
             [(0.0, 0.0), (FACE_BACK, TOP), (RISER_HEAD, SHELF), (0.0, SHELF)]]
MATERIALS = ["face", "paint", "bezel", "back", "kick", "cheek", "hood", "apron", "cabinet", "annunciator",
             "annunciator_lit", "vent"]

# --- the corner plan (DeskCornerGeometry.java) ---
INSIDE_BEND = 1.5
OUTSIDE_BEND = 1.5
INSIDE_BACK = INSIDE_BEND + W - W * SQ2
OUTSIDE_BACK = OUTSIDE_BEND - W + W * SQ2
OUTSIDE_B_FRONT = 0.25
OUTSIDE_B_BACK = 1.0
CORE = {"inside": "left", "outside": "deep"}
PARTS = {"origin": (0, 0), "left": (1, 0), "deep": (0, 1), "deep_left": (1, 1)}   # (left, deep)


def block_x(part):
    return 1 - PARTS[part][0]


def block_f(kind, part):
    return PARTS[part][1] if kind == "inside" else -PARTS[part][1]


def depth(kind, x, f):
    if kind == "inside":
        return min(f, x, (x + f - INSIDE_BACK) / SQ2)
    return max(f, OUTSIDE_B_BACK - x, (OUTSIDE_BACK - (x - f)) / SQ2)


def face_frame(kind):
    """(origin (x, f) in footprint coordinates of cell (0,0), unit column step, unit row step) of the mounting face."""
    mid = FACE_BACK + (W - FACE_BACK) / 2.0
    r = math.sqrt(0.5)
    if kind == "inside":
        xf = (INSIDE_BACK + mid * SQ2) / 2.0
        centre, u, v = (xf, xf), (r, -r), (r, r)
    else:
        diff = OUTSIDE_BACK - mid * SQ2
        centre, u, v = ((1.0 + diff) / 2.0, (1.0 - diff) / 2.0), (r, r), (-r, r)
    origin = (centre[0] - u[0] * 0.5 - v[0] * 0.3125, centre[1] - u[1] * 0.5 - v[1] * 0.3125)
    return origin, u, v


def check_face(kind):
    """The 16 x 10 grid of the frame lies on the top of the console (between the foot of the riser and the front edge)."""
    origin, u, v = face_frame(kind)
    worst = 1e9
    for i in range(0, 17):
        for j in range(0, 11):
            x = origin[0] + (u[0] * i + v[0] * j) / 16.0
            f = origin[1] + (u[1] * i + v[1] * j) / 16.0
            t = depth(kind, x, f)
            worst = min(worst, t - FACE_BACK + 1e-12, W - t + 1e-12)
    if worst < -1e-9:
        raise AssertionError("%s: the face grid leaves the top of the console by %g" % (kind, -worst))
    return worst


# ---------------------------------------------------------------------------------------------------------------
# plan geometry: convex polygon clipping against half-planes a*x + b*f >= c

def clip(poly, plane):
    a, b, c = plane
    out = []
    n = len(poly)
    for i in range(n):
        p, q = poly[i], poly[(i + 1) % n]
        dp = a * p[0] + b * p[1] - c
        dq = a * q[0] + b * q[1] - c
        if dp >= -EPS:
            out.append(p)
        if (dp >= -EPS) != (dq >= -EPS) and abs(dp - dq) > EPS:
            s = dp / (dp - dq)
            out.append((p[0] + s * (q[0] - p[0]), p[1] + s * (q[1] - p[1])))
    return out


def through(p, q, ref):
    dx, dy = q[0] - p[0], q[1] - p[1]
    a, b = -dy, dx
    c = a * p[0] + b * p[1]
    if a * ref[0] + b * ref[1] < c:
        a, b, c = -a, -b, -c
    return (a, b, c)


class Section:
    """A straight stretch of the console: half-planes bounding it, the linear depth t = ta*x + tb*f + tc, and the
    plan of its far end (for an end cap): fn(t) -> (x, f), and the outward plan direction there."""

    def __init__(self, planes, tlin, end=None):
        self.planes = planes
        self.tlin = tlin
        self.end = end

    def t(self, p):
        return self.tlin[0] * p[0] + self.tlin[1] * p[1] + self.tlin[2]

    def gradient(self):
        n = math.hypot(self.tlin[0], self.tlin[1])
        return (self.tlin[0] / n, self.tlin[1] / n)

    def strip(self, ta, tb):
        poly = [(-8.0, -8.0), (8.0, -8.0), (8.0, 8.0), (-8.0, 8.0)]
        for plane in self.planes + [(self.tlin[0], self.tlin[1], ta - self.tlin[2]),
                                    (-self.tlin[0], -self.tlin[1], self.tlin[2] - tb)]:
            poly = clip(poly, plane)
            if len(poly) < 3:
                return []
        return poly

    def segment(self, t):
        """The part of the line of depth t that lies in the section (two end points) or []."""
        g = self.gradient()
        n = math.hypot(self.tlin[0], self.tlin[1])
        base = (g[0] * (t - self.tlin[2]) / n, g[1] * (t - self.tlin[2]) / n)
        d = (-g[1], g[0])
        lo, hi = -50.0, 50.0
        for a, b, c in self.planes:
            den = a * d[0] + b * d[1]
            num = c - (a * base[0] + b * base[1])
            if abs(den) < 1e-12:
                if num > 1e-9:
                    return []
                continue
            s = num / den
            if den > 0:
                lo = max(lo, s)
            else:
                hi = min(hi, s)
        if hi - lo < 1e-9:
            return []
        return [(base[0] + d[0] * lo, base[1] + d[1] * lo), (base[0] + d[0] * hi, base[1] + d[1] * hi)]


def sections(kind, runs):
    r = 1.0 / SQ2
    out = []
    if kind == "inside":
        po, pi = (INSIDE_BACK, 0.0), (INSIDE_BEND, W)
        qo, qi = (0.0, INSIDE_BACK), (W, INSIDE_BEND)
        centre = (0.9, 0.9)
        mit_a = lambda ref: through(po, pi, ref)
        mit_b = lambda ref: through(qo, qi, ref)
        out.append(Section([(-1, 0, -2.0), mit_a((2.0, 0.3))], (0.0, 1.0, 0.0), end=(lambda t: (2.0, t), (1.0, 0.0))))
        out.append(Section([mit_a(centre), mit_b(centre)], (r, r, -INSIDE_BACK * r)))
        out.append(Section([(0, -1, -2.0), mit_b((0.3, 2.0))], (1.0, 0.0, 0.0), end=(lambda t: (t, 2.0), (0.0, 1.0))))
        if runs:
            out.append(Section([(1, 0, 2.0), (-1, 0, -4.0)], (0.0, 1.0, 0.0), end=(lambda t: (4.0, t), (1.0, 0.0))))
            out.append(Section([(0, 1, 2.0), (0, -1, -4.0)], (1.0, 0.0, 0.0), end=(lambda t: (t, 4.0), (0.0, 1.0))))
    else:
        f_o = W - (OUTSIDE_BEND - OUTSIDE_B_FRONT)
        po, pi = (OUTSIDE_BACK, 0.0), (OUTSIDE_BEND, W)
        qo, qi = (OUTSIDE_B_BACK, 1.0 - OUTSIDE_BACK), (OUTSIDE_B_FRONT, f_o)
        centre = (1.1, -0.1)
        mit_a = lambda ref: through(po, pi, ref)
        mit_b = lambda ref: through(qo, qi, ref)
        out.append(Section([(-1, 0, -2.0), mit_a((2.0, 0.3))], (0.0, 1.0, 0.0), end=(lambda t: (2.0, t), (1.0, 0.0))))
        out.append(Section([mit_a(centre), mit_b(centre)], (-r, r, OUTSIDE_BACK * r)))
        out.append(Section([(0, 1, -1.0), mit_b((0.6, -2.0))], (-1.0, 0.0, 1.0), end=(lambda t: (1.0 - t, -1.0), (0.0, -1.0))))
        if runs:
            out.append(Section([(1, 0, 2.0), (-1, 0, -4.0)], (0.0, 1.0, 0.0), end=(lambda t: (4.0, t), (1.0, 0.0))))
            out.append(Section([(0, -1, 1.0), (0, 1, -3.0)], (-1.0, 0.0, 1.0), end=(lambda t: (1.0 - t, -3.0), (0.0, -1.0))))
    return out


# ---------------------------------------------------------------------------------------------------------------
# the solid as polygons: (material, [(x, y, f)], [uv], outward direction (x, y, f))

def material_of(a, b):
    if abs(a[1] - TOP) < EPS and abs(b[1] - TOP) < EPS:
        return "face"
    if abs(a[0]) < EPS and abs(b[0]) < EPS:
        return "back"
    if abs(a[1]) < EPS and abs(b[1]) < EPS:
        return "kick"
    if abs(a[1] - b[1]) < EPS and a[1] < TOP:
        return "kick"
    if abs(a[1] - b[1]) < EPS:
        return "bezel"
    if abs(a[0] - W) < EPS and abs(b[0] - W) < EPS:
        return "bezel"
    if b[1] > a[1] and b[0] > a[0]:
        return "kick"
    return "paint"


def with_uv(mat, pts):
    """uv within 0..1 (a uv past 1 would read the next sprite of the atlas): the bounding box of the polygon."""
    xs = [p[0] for p in pts]
    fs = [p[2] for p in pts]
    ys = [p[1] for p in pts]
    if max(xs) - min(xs) >= max(fs) - min(fs):
        along = [(p[0] - min(xs)) / max(EPS, max(xs) - min(xs)) for p in pts]
    else:
        along = [(p[2] - min(fs)) / max(EPS, max(fs) - min(fs)) for p in pts]
    if mat in ("face", "bezel") and (max(ys) - min(ys)) < EPS:
        across = [(p[0] - min(xs)) / max(EPS, max(xs) - min(xs)) for p in pts]
        along = [(p[2] - min(fs)) / max(EPS, max(fs) - min(fs)) for p in pts]
        return list(zip(across, along))
    return [(a, p[1] / SHELF) for a, p in zip(along, pts)]


def solid(kind, runs=False):
    polys = []
    n = len(PROFILE)
    for sec in sections(kind, runs):
        g = sec.gradient()
        for i in range(n):
            a, b = PROFILE[i], PROFILE[(i + 1) % n]
            dt, dy = b[0] - a[0], b[1] - a[1]
            length = math.hypot(dt, dy)
            n_t, n_y = dy / length, -dt / length          # outward in (t, y)
            out = (n_t * g[0], n_y, n_t * g[1])
            mat = material_of(a, b)
            if abs(dt) < EPS:
                seg = sec.segment(a[0])
                if seg:
                    p0, p1 = seg
                    pts = [(p0[0], a[1], p0[1]), (p1[0], a[1], p1[1]), (p1[0], b[1], p1[1]), (p0[0], b[1], p0[1])]
                    polys.append((mat, pts, with_uv(mat, pts), out))
                continue
            plan = sec.strip(a[0], b[0]) if dt > 0 else sec.strip(b[0], a[0])
            if len(plan) < 3:
                continue
            pts = []
            for p in plan:
                t = sec.t(p)
                y = a[1] if abs(dy) < EPS else a[1] + dy * (t - a[0]) / dt
                pts.append((p[0], y, p[1]))
            polys.append((mat, pts, with_uv(mat, pts), out))
        if sec.end is not None:
            fn, plan_out = sec.end
            for quad in END_QUADS:
                pts = [(fn(t)[0], y, fn(t)[1]) for t, y in quad]
                polys.append(("bezel", pts, with_uv("bezel", pts), (plan_out[0], 0.0, plan_out[1])))
    return polys


# ---------------------------------------------------------------------------------------------------------------
# slicing into the four blocks

def newell(pts):
    nx = ny = nz = 0.0
    for i, a in enumerate(pts):
        b = pts[(i + 1) % len(pts)]
        nx += (a[1] - b[1]) * (a[2] + b[2])
        ny += (a[2] - b[2]) * (a[0] + b[0])
        nz += (a[0] - b[0]) * (a[1] + b[1])
    return (nx, ny, nz)


def clip_poly(pts, uvs, axis, value, keep_below):
    """Sutherland-Hodgman on a 3D polygon with uv, against x (axis 0) or f (axis 2) <= / >= value."""
    out, out_uv = [], []
    n = len(pts)
    for i in range(n):
        p, q = pts[i], pts[(i + 1) % n]
        up, uq = uvs[i], uvs[(i + 1) % n]
        dp = (value - p[axis]) if keep_below else (p[axis] - value)
        dq = (value - q[axis]) if keep_below else (q[axis] - value)
        if dp >= -EPS:
            out.append(p)
            out_uv.append(up)
        if (dp >= -EPS) != (dq >= -EPS) and abs(dp - dq) > EPS:
            s = dp / (dp - dq)
            out.append(tuple(p[k] + s * (q[k] - p[k]) for k in range(3)))
            out_uv.append((up[0] + s * (uq[0] - up[0]), up[1] + s * (uq[1] - up[1])))
    return out, out_uv


def part_polys(polys, bx, bf):
    """The polygons clipped to the block whose near corner is (bx, bf), in block-local (lx, y, f)."""
    result = []
    for mat, pts, uv, out in polys:
        p, u = list(pts), list(uv)
        for axis, lo in ((0, bx), (2, bf)):
            for value, below in ((lo, False), (lo + 1.0, True)):
                if len(p) >= 3:
                    p, u = clip_poly(p, u, axis, value, below)
        if len(p) < 3:
            continue
        # a polygon lying in a block boundary belongs to the block whose solid it bounds: its outward direction points
        # away from the block's middle
        flat = False
        for axis, lo in ((0, bx), (2, bf)):
            for value in (lo, lo + 1.0):
                if all(abs(q[axis] - value) < 1e-7 for q in p):
                    flat = True
                    centre = lo + 0.5
                    if (value - centre) * out[axis] <= 0:
                        p = []
        if len(p) < 3:
            continue
        area = math.sqrt(sum(c * c for c in newell(p))) / 2.0
        if area < 1e-7:
            continue
        result.append((mat, [(q[0] - bx, q[1], q[2] - bf) for q in p], u, out))
    return result


def world(p):
    lx, y, f = p
    return (1.0 - lx, y, 1.0 - f)


def orient(polys):
    out = []
    for mat, pts, uv, direction in polys:
        n = newell([world(p) for p in pts])
        want = (-direction[0], direction[1], -direction[2])
        if n[0] * want[0] + n[1] * want[1] + n[2] * want[2] < 0:
            pts, uv = pts[::-1], uv[::-1]
        out.append((mat, pts, uv))
    return out


# ---------------------------------------------------------------------------------------------------------------
# Blender

def make_materials():
    out = {}
    for name in MATERIALS:
        mat = bpy.data.materials.new(name)
        mat.use_nodes = True
        tree = mat.node_tree
        for node in list(tree.nodes):
            if node.type != "OUTPUT_MATERIAL":
                tree.nodes.remove(node)
        path = os.path.join(TEXTURES, name + ".png")
        if os.path.isfile(path):
            image = bpy.data.images.load(path, check_existing=True)
            node = tree.nodes.new("ShaderNodeTexImage")
            node.image = image
            node.interpolation = "Closest"
            bsdf = tree.nodes.new("ShaderNodeBsdfDiffuse")
            tree.links.new(node.outputs["Color"], bsdf.inputs["Color"])
            tree.links.new(bsdf.outputs["BSDF"], tree.nodes["Material Output"].inputs["Surface"])
        out[name] = mat
    return out


def split(polys):
    """The game's OBJ loader draws quads and triangles: a convex polygon of five or more corners becomes a fan of triangles."""
    out = []
    for mat, pts, uv in polys:
        if len(pts) <= 4:
            out.append((mat, pts, uv))
            continue
        for i in range(1, len(pts) - 1):
            out.append((mat, [pts[0], pts[i], pts[i + 1]], [uv[0], uv[i], uv[i + 1]]))
    return out


def to_mesh(name, polys, materials, offset=(0.0, 0.0, 0.0), mapping=world):
    verts, index, faces, uvs, mats = [], {}, [], [], []
    for mat, pts, uv in split(polys):
        loop = []
        for p in pts:
            wx, wy, wz = mapping(p)
            wx, wy, wz = wx + offset[0], wy + offset[1], wz + offset[2]
            key = (round(wx, 6), round(wy, 6), round(wz, 6))
            if key not in index:
                index[key] = len(verts)
                verts.append((wx, -wz, wy))
            loop.append(index[key])
        if len(set(loop)) < 3:
            continue
        faces.append(loop)
        uvs.extend(uv)
        mats.append(mat)
    mesh = bpy.data.meshes.new(name)
    mesh.from_pydata(verts, [], faces)
    for m in MATERIALS:
        mesh.materials.append(materials[m])
    for poly, mat in zip(mesh.polygons, mats):
        poly.material_index = MATERIALS.index(mat)
        poly.use_smooth = False
    layer = mesh.uv_layers.new(name="UVMap")
    k = 0
    for poly in mesh.polygons:
        for loop_index in poly.loop_indices:
            layer.data[loop_index].uv = uvs[k]
            k += 1
    mesh.update()
    obj = bpy.data.objects.new(name, mesh)
    bpy.context.scene.collection.objects.link(obj)
    return obj


def export(obj, name):
    path = os.path.join(MODELS, name + ".obj")
    bpy.ops.object.select_all(action="DESELECT")
    obj.select_set(True)
    bpy.context.view_layer.objects.active = obj
    bpy.ops.wm.obj_export(filepath=path, export_selected_objects=True, export_uv=True, export_normals=True,
                          export_materials=True, export_triangulated_mesh=False, export_smooth_groups=False,
                          forward_axis="NEGATIVE_Z", up_axis="Y", global_scale=1.0, path_mode="STRIP")
    lines = []
    for line in open(path, encoding="utf-8").read().splitlines():
        if line.startswith("mtllib"):
            line = "mtllib materials.mtl"
        elif line.startswith(("o ", "g ", "# Blender", "# www.blender")):
            continue
        lines.append(line)
    open(path, "w", encoding="utf-8", newline="\n").write(
        "# Generated by art/models/control_desk/build_desk_corner.py (Blender)\n" + "\n".join(lines) + "\n")
    stray = os.path.join(MODELS, name + ".mtl")
    if os.path.isfile(stray):
        os.remove(stray)
    return sum(1 for ln in lines if ln.startswith("f "))


def clear_scene():
    for obj in list(bpy.data.objects):
        bpy.data.objects.remove(obj, do_unlink=True)
    for coll in (bpy.data.meshes, bpy.data.materials, bpy.data.images):
        for item in list(coll):
            coll.remove(item)


def setup_render():
    scene = bpy.context.scene
    scene.render.engine = "BLENDER_WORKBENCH"
    scene.display.shading.light = "STUDIO"
    scene.display.shading.color_type = "TEXTURE"
    scene.render.image_settings.file_format = "PNG"
    scene.view_settings.view_transform = "Standard"
    scene.render.resolution_x, scene.render.resolution_y = 1000, 750
    world_data = bpy.data.worlds.new("w")
    scene.world = world_data
    world_data.color = (0.55, 0.62, 0.7)
    cam = bpy.data.objects.new("cam", bpy.data.cameras.new("cam"))
    scene.collection.objects.link(cam)
    scene.camera = cam
    cam.data.type = "ORTHO"
    return cam


def shoot(cam, centre, direction, scale, path):
    from mathutils import Vector
    scene = bpy.context.scene
    cam.data.ortho_scale = scale
    d = Vector(direction).normalized()
    c = Vector(centre)
    cam.location = c + d * 30.0
    cam.rotation_euler = (c - cam.location).to_track_quat("-Z", "Y").to_euler()
    scene.render.filepath = path
    bpy.ops.render.render(write_still=True)


def main():
    os.makedirs(PREVIEW, exist_ok=True)
    clear_scene()
    materials = make_materials()
    report = {}
    cam = setup_render()
    for kind in ("inside", "outside"):
        margin = check_face(kind)
        polys = solid(kind)
        for part in PARTS:
            sliced = orient(part_polys(polys, block_x(part), block_f(kind, part)))
            piece = to_mesh("part", sliced, materials)
            report["%s_%s" % (kind, part)] = export(piece, "desk_corner_%s_%s" % (kind, part))
            bpy.data.objects.remove(piece, do_unlink=True)
        # the item: the whole piece in the frame of the origin block, centred on its cube
        shift_f = block_f(kind, "origin")
        whole = orient([(m, [(p[0] - 1.0, p[1], p[2] - shift_f) for p in pts], uv, out) for m, pts, uv, out in polys])
        xs, ys, zs = [], [], []
        for _, pts, _ in whole:
            for p in pts:
                w = world(p)
                xs.append(w[0]); ys.append(w[1]); zs.append(w[2])
        offset = (0.5 - (min(xs) + max(xs)) / 2.0, 0.5 - (min(ys) + max(ys)) / 2.0, 0.5 - (min(zs) + max(zs)) / 2.0)
        item = to_mesh("item", whole, materials, offset)
        report["%s_item" % kind] = export(item, "desk_corner_%s_item" % kind)
        bpy.data.objects.remove(item, do_unlink=True)
        print("FACE %s: grid margin inside the top %.4f block" % (kind, margin))
    if "--no-preview" in sys.argv:
        return report
    for kind in ("inside", "outside"):
        # the corner alone, then a run + corner + run (two straight desk blocks each side); in game orientation (facing
        # north, operator toward +Y of Blender), seen from the operator's side above
        for label, runs in (("iso", False), ("arrangement", True)):
            polys = solid(kind, runs)
            obj = to_mesh("pv", orient(polys and [(m, pts, uv, out) for m, pts, uv, out in polys]), materials)
            xs, ys, zs = [], [], []
            for _, pts, _ in orient(polys):
                for p in pts:
                    w = world(p)
                    xs.append(w[0]); ys.append(w[1]); zs.append(-w[2])
            centre = ((min(xs) + max(xs)) / 2.0, (min(zs) + max(zs)) / 2.0, 0.35)
            span = max(max(xs) - min(xs), max(zs) - min(zs))
            sc = span * 1.75 + 0.6
            frm = (0.9, 1.0, 0.85) if kind == "inside" else (-0.9, 1.0, 0.85)
            shoot(cam, centre, frm, sc, os.path.join(PREVIEW, "desk-corner-%s-%s.png" % (kind, label)))
            shoot(cam, centre, (0.0, 0.02, 1.0), sc, os.path.join(PREVIEW, "desk-corner-%s-%s-top.png" % (kind, label)))
            bpy.data.objects.remove(obj, do_unlink=True)
    return report


result = main()
print("FACES", result)
