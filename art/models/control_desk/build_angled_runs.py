"""The modular 45 degree runs (CONTROL-PANELS-SPEC section 14) as block OBJ models, with Blender.

Run headless (never against an open scene):
    blender --background --factory-startup --python art/models/control_desk/build_angled_runs.py [--no-preview]
The plan (which cubes belong to bend A, a diagonal unit, bend B, the origins, the faces) is run_plan.py, the Java
AngledRunGeometry has the same numbers. This script sweeps the profile of the family along the mitred band, slices it into
the cubes of each piece and writes, to mod/src/main/resources/assets/bwr/models/block/control_panel/ (the shared
materials.mtl there is build_trim.py's):
    run_<fam>_<kind>_<role>_<part>      one cube of the piece (part = index into run_plan parts), facing north, unmirrored
    run_<fam>_<kind>_<role>_<part>_m    the same cube mirrored inside its own cube (the mirror-hand piece)
    run_<fam>_<kind>_<role>_item        the whole piece, centred on its cube, for the item model
fam desk|tall, kind inside|outside, role bend_a|unit|bend_b. Previews go to art/models/control_desk/previews/.

Frame as build_desk_corner.py: x to the operator's right, f from the back toward the operator, y up; the model of a cube is
authored facing north, world(p) = (1 - lx, y, 1 - f); the mirrored world_m(p) = (lx, y, 1 - f).
"""
import json
import math
import os
import sys

import bpy

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import run_plan as rp  # noqa: E402

ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
ASSETS = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "bwr")
MODELS = os.path.join(ASSETS, "models", "block", "control_panel")
TEXTURES = os.path.join(ASSETS, "textures", "block", "control_panel")
PREVIEW = os.path.join(HERE, "previews")

SQ2 = rp.SQ2
T22 = rp.T22
TB = rp.TB
EPS = 1e-9
ROLE_NAME = {"a": "bend_a", "unit": "unit", "b": "bend_b"}

# --- the desk profile (build_desk_corner.py / build_desk.py), (depth t, height y) ---
TOP = 0.75
SHELF = 1.10
FACE_BACK = 0.125
W_DESK = 0.75
RISER_HEAD = 0.075
KNEE_FOOT = 0.2
LIP = 0.0625
UNDER = TOP - LIP
KNEE_TOP = KNEE_FOOT + (0.5 - KNEE_FOOT) * UNDER / TOP
DESK_PROFILE = [(W_DESK, TOP, None), (FACE_BACK, TOP, None), (RISER_HEAD, SHELF, None), (0.0, SHELF, None),
                (0.0, 0.0, None), (KNEE_FOOT, 0.0, None), (KNEE_TOP, UNDER, None), (W_DESK, UNDER, None)]
# --- the tall panel: the bench of build_trim.py (BENCH_PROFILE), two vertical boards and the header strip as one board ---
K = 0.125
D = 0.25
BENCH = [(K, 0.0, "bezel"), (1.0, 0.0, "face"), (0.0, 1.0, "back"), (0.0, K, "kick"), (K, K, "kick")]
UPPER = [(0.0, 1.0, "bezel"), (D, 1.0, "face"), (D, 3.0, "face"), (D - TB, 4.0, "bezel"), (0.0, 4.0, "back")]
PROFILES = {"desk": [DESK_PROFILE], "tall": [BENCH, UPPER]}

MATERIALS = ["face", "paint", "bezel", "back", "kick", "cheek", "hood", "apron", "cabinet", "annunciator",
             "annunciator_lit", "vent"]


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
        poly = [(-30.0, -30.0), (30.0, -30.0), (30.0, 30.0), (-30.0, 30.0)]
        for plane in self.planes + [(self.tlin[0], self.tlin[1], ta - self.tlin[2]),
                                    (-self.tlin[0], -self.tlin[1], self.tlin[2] - tb)]:
            poly = clip(poly, plane)
            if len(poly) < 3:
                return []
        return poly

    def segment(self, t):
        g = self.gradient()
        n = math.hypot(self.tlin[0], self.tlin[1])
        base = (g[0] * (t - self.tlin[2]) / n, g[1] * (t - self.tlin[2]) / n)
        d = (-g[1], g[0])
        lo, hi = -80.0, 80.0
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


def run_ends(kind, fam, n, mode):
    """The planes where the sliced pieces end: bend A's x = 1, bend B's end of the straight run; 'preview' goes two blocks
    further on both sides (a straight run of two blocks)."""
    pc = rp.pieces(kind, fam)
    sf = rp.CHAIN_F[kind]
    fb_n = pc["b"]["origin"][1] + n * sf
    b_end = fb_n + 1 if kind == "inside" else fb_n
    a_end = 1.0
    if mode == "preview":
        a_end += 2.0
        b_end += 2.0 * sf
    return a_end, float(b_end)


def sections(kind, fam, n, mode):
    r = 1.0 / SQ2
    w = rp.FAMILIES[fam]["W"]
    phi = rp.PHI[(fam, kind)]
    a_end, b_end = run_ends(kind, fam, n, mode)
    out = []
    if kind == "inside":
        xwall = -n - 1.0
        fq = phi - xwall
        o, i = (phi, 0.0), (phi + T22 * w, w)
        q, i2 = (xwall, fq), (xwall + w, fq + T22 * w)
        out.append(Section([(-1, 0, -a_end), through(o, i, (phi + 3, 0.3))], (0.0, 1.0, 0.0),
                           end=(lambda t: (a_end, t), (1.0, 0.0))))
        out.append(Section([through(o, i, (phi - 2, 0.3)), through(q, i2, (xwall + 2, fq))], (r, r, -phi * r)))
        out.append(Section([(0, -1, -b_end), through(q, i2, (xwall + 0.3, fq + 5))], (1.0, 0.0, -xwall),
                           end=(lambda t: (xwall + t, b_end), (0.0, 1.0))))
    else:
        xwall = -float(n)
        xi = phi + T22 * w
        f_i = xwall - xi
        pi, po = (xi, 0.0), (phi, w)
        pib, pob = (xwall, f_i), (xwall - w, f_i + T22 * w)
        out.append(Section([(-1, 0, -a_end), through(pi, po, (xi + 3, 0.3))], (0.0, 1.0, 0.0),
                           end=(lambda t: (a_end, t), (1.0, 0.0))))
        out.append(Section([through(pi, po, (phi - 3, -2)), through(pib, pob, (xwall + 2, f_i + 2.3))],
                           (-r, r, xi * r)))
        out.append(Section([(0, 1, b_end), through(pib, pob, (xwall - 0.3, f_i - 5))], (-1.0, 0.0, xwall),
                           end=(lambda t: (xwall - t, b_end), (0.0, -1.0))))
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
    if abs(a[0] - W_DESK) < EPS and abs(b[0] - W_DESK) < EPS:
        return "bezel"
    if b[1] > a[1] and b[0] > a[0]:
        return "kick"
    return "paint"


def with_uv(fam, mat, pts):
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
    if fam == "desk":
        return [(a, p[1] / SHELF) for a, p in zip(along, pts)]
    lo, hi = min(ys), max(ys)
    return [(a, (p[1] - lo) / max(EPS, hi - lo)) for a, p in zip(along, pts)]


def ear_clip(pts):
    """Triangulation of a simple counter-clockwise 2D polygon: index triples."""
    idx = list(range(len(pts)))
    out = []

    def cross(a, b, c):
        return (pts[b][0] - pts[a][0]) * (pts[c][1] - pts[b][1]) - (pts[b][1] - pts[a][1]) * (pts[c][0] - pts[b][0])

    def inside(p, a, b, c):
        return (cross(a, b, p) > 1e-12 and cross(b, c, p) > 1e-12 and cross(c, a, p) > 1e-12)

    guard = 0
    while len(idx) > 3 and guard < 500:
        guard += 1
        for k in range(len(idx)):
            a, b, c = idx[k - 1], idx[k], idx[(k + 1) % len(idx)]
            cr = cross(a, b, c)
            if abs(cr) < 1e-12:
                idx.pop(k)
                break
            if cr < 0:
                continue
            if any(inside(p, a, b, c) for p in idx if p not in (a, b, c)):
                continue
            out.append((a, b, c))
            idx.pop(k)
            break
        else:
            raise AssertionError("ear clipping failed")
    if len(idx) == 3:
        out.append(tuple(idx))
    return out


def solid(kind, fam, n, mode="pieces"):
    polys = []
    for sec in sections(kind, fam, n, mode):
        g = sec.gradient()
        for prof in PROFILES[fam]:
            count = len(prof)
            for i in range(count):
                a, b = prof[i], prof[(i + 1) % count]
                dt, dy = b[0] - a[0], b[1] - a[1]
                length = math.hypot(dt, dy)
                n_t, n_y = dy / length, -dt / length          # outward in (t, y)
                out = (n_t * g[0], n_y, n_t * g[1])
                mat = a[2] or material_of(a, b)
                if abs(dt) < EPS:
                    seg = sec.segment(a[0])
                    if seg:
                        p0, p1 = seg
                        pts = [(p0[0], a[1], p0[1]), (p1[0], a[1], p1[1]), (p1[0], b[1], p1[1]), (p0[0], b[1], p0[1])]
                        polys.append((mat, pts, with_uv(fam, mat, pts), out))
                    continue
                plan = sec.strip(a[0], b[0]) if dt > 0 else sec.strip(b[0], a[0])
                if len(plan) < 3:
                    continue
                pts = []
                for p in plan:
                    t = sec.t(p)
                    y = a[1] if abs(dy) < EPS else a[1] + dy * (t - a[0]) / dt
                    pts.append((p[0], y, p[1]))
                polys.append((mat, pts, with_uv(fam, mat, pts), out))
            if sec.end is not None:
                fn, plan_out = sec.end
                flat = [(t, y) for t, y, _ in prof]
                for tri in ear_clip(flat):
                    pts = [(fn(flat[j][0])[0], flat[j][1], fn(flat[j][0])[1]) for j in tri]
                    polys.append(("bezel", pts, with_uv(fam, "bezel", pts), (plan_out[0], 0.0, plan_out[1])))
    return polys


# ---------------------------------------------------------------------------------------------------------------
# slicing into cubes

def newell(pts):
    nx = ny = nz = 0.0
    for i, a in enumerate(pts):
        b = pts[(i + 1) % len(pts)]
        nx += (a[1] - b[1]) * (a[2] + b[2])
        ny += (a[2] - b[2]) * (a[0] + b[0])
        nz += (a[0] - b[0]) * (a[1] + b[1])
    return (nx, ny, nz)


def clip_poly(pts, uvs, axis, value, keep_below):
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


def dedupe(p, u):
    keep = []
    for i in range(len(p)):
        j = (i + 1) % len(p)
        if max(abs(p[i][k] - p[j][k]) for k in range(3)) > 1e-7:
            keep.append(i)
    return [p[i] for i in keep], [u[i] for i in keep]


def part_polys(polys, bx, bf, by, yclip):
    """The polygons clipped to the cube (bx, bf, by), in cube-local (lx, y, f). The desk is not clipped in y (its shelf
    stands 0.1 above the block)."""
    axes = [(0, bx), (2, bf)] + ([(1, by)] if yclip else [])
    result = []
    for mat, pts, uv, out in polys:
        p, u = list(pts), list(uv)
        for axis, lo in axes:
            for value, below in ((lo, False), (lo + 1.0, True)):
                if len(p) >= 3:
                    p, u = clip_poly(p, u, axis, value, below)
        p, u = dedupe(p, u)
        if len(p) < 3:
            continue
        for axis, lo in axes:
            for value in (lo, lo + 1.0):
                if all(abs(q[axis] - value) < 1e-7 for q in p):
                    if (value - (lo + 0.5)) * out[axis] <= 0:
                        p = []
        if len(p) < 3:
            continue
        area = math.sqrt(sum(c * c for c in newell(p))) / 2.0
        if area < 1e-7:
            continue
        yo = by if yclip else 0
        result.append((mat, [(q[0] - bx, q[1] - yo, q[2] - bf) for q in p], u, out))
    return result


def world(p):
    return (1.0 - p[0], p[1], 1.0 - p[2])


def world_m(p):
    return (p[0], p[1], 1.0 - p[2])


def orient(polys, mapping=world, mirror=False):
    out = []
    for mat, pts, uv, direction in polys:
        n = newell([mapping(p) for p in pts])
        want = (direction[0], direction[1], -direction[2]) if mirror else (-direction[0], direction[1], -direction[2])
        if n[0] * want[0] + n[1] * want[1] + n[2] * want[2] < 0:
            pts, uv = pts[::-1], uv[::-1]
        out.append((mat, pts, uv))
    return out


def canon(polys):
    return sorted((mat, tuple(sorted(tuple(round(c, 5) for c in p) for p in pts))) for mat, pts, _, _ in polys)


def piece_cubes(kind, fam, n):
    """{(role, k): [(cube, part index)]} for a run of n units; k = 1.. for the units, 0 for the others."""
    pc = rp.pieces(kind, fam)
    sf = rp.CHAIN_F[kind]
    out = {}
    ao = pc["a"]["origin"]
    out[("a", 0)] = [((ao[0] + p[0], ao[1] + p[1], ao[2] + p[2]), j) for j, p in enumerate(pc["a"]["parts"])]
    uo = pc["unit"]["origin"]
    for k in range(1, n + 1):
        o = (uo[0] - (k - 1), uo[1] + (k - 1) * sf, uo[2])
        out[("unit", k)] = [((o[0] + p[0], o[1] + p[1], o[2] + p[2]), j) for j, p in enumerate(pc["unit"]["parts"])]
    bo = pc["b"]["origin"]
    o = (bo[0] - n, bo[1] + n * sf, 0)
    out[("b", 0)] = [((o[0] + p[0], o[1] + p[1], o[2] + p[2]), j) for j, p in enumerate(pc["b"]["parts"])]
    return out


def slice_pieces(kind, fam, n=3):
    """{role: [local polys per part]} from a run of n units; asserts units 1..n identical."""
    polys = solid(kind, fam, n)
    yclip = fam == "tall"
    cubes = piece_cubes(kind, fam, n)
    result = {}
    unit_sets = []
    for (role, k), parts in cubes.items():
        sliced = []
        for cube, j in parts:
            local = part_polys(polys, cube[0], cube[1], cube[2], yclip)
            assert local, "%s %s %s part %d (cube %s) has no geometry" % (kind, fam, role, j, cube)
            sliced.append(local)
        if role == "unit":
            unit_sets.append([canon(s) for s in sliced])
            if k == 1:
                result[role] = sliced
        else:
            result[role] = sliced
    for k in range(1, len(unit_sets)):
        assert unit_sets[k] == unit_sets[0], "%s %s: unit %d differs from unit 1" % (kind, fam, k + 1)
    return result


# ---------------------------------------------------------------------------------------------------------------
# checks

def check_faces(kind, fam, n=3):
    """The grid corners of every face of unit 1 lie on the profile surface: the depth there is the one the face gives
    and the diagonal term (not a mitre) decides it."""
    (ux, uf), (nx, nf) = rp.plan_dirs(kind)
    worst = 9.0
    for spec in rp.face_specs(fam):
        xc, fc, _ = rp.face_centre(kind, fam, spec)
        tmid = (spec["t0"] + spec["t1"]) / 2.0
        rows = spec["rows"]
        for i in (0, 8, 16):
            for j in (0, rows // 2, rows):
                t = spec["t0"] + (spec["t1"] - spec["t0"]) * j / rows
                x = xc + ux * (i - 8) / 16.0 + nx * (t - tmid)
                f = fc + uf * (i - 8) / 16.0 + nf * (t - tmid)
                got = rp.depth(kind, fam, x, f, n)
                assert abs(got - t) < 1e-6, "%s %s face %s corner %d,%d: depth %g, face %g" % (kind, fam, spec["kind"], i, j, got, t)
                ts = rp.terms(kind, fam, x, f, n)
                m = (min(ts[0], ts[2]) - ts[1]) if kind == "inside" else (ts[1] - max(ts[0], ts[2]))
                worst = min(worst, m)
    assert worst > 0.05, "%s %s: a face reaches into a mitre (margin %g)" % (kind, fam, worst)
    return worst


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


def tint_materials():
    out = {}
    for name, col in (("tint_a", (0.85, 0.35, 0.3, 1)), ("tint_unit", (0.35, 0.75, 0.4, 1)),
                      ("tint_b", (0.3, 0.45, 0.9, 1)), ("tint_s", (0.7, 0.7, 0.7, 1)), ("tint_unit2", (0.9, 0.8, 0.3, 1))):
        mat = bpy.data.materials.new(name)
        mat.diffuse_color = col
        out[name] = mat
    return out


def split(polys):
    out = []
    for mat, pts, uv in polys:
        if len(pts) <= 4:
            out.append((mat, pts, uv))
            continue
        for i in range(1, len(pts) - 1):
            out.append((mat, [pts[0], pts[i], pts[i + 1]], [uv[0], uv[i], uv[i + 1]]))
    return out


def to_mesh(name, polys, materials, offset=(0.0, 0.0, 0.0), mapping=world, tint=None):
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
    if tint is None:
        for m in MATERIALS:
            mesh.materials.append(materials[m])
        for poly, mat in zip(mesh.polygons, mats):
            poly.material_index = MATERIALS.index(mat)
            poly.use_smooth = False
    else:
        mesh.materials.append(tint)
        for poly in mesh.polygons:
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
    # only triangles and quads, something in it
    count = 0
    for ln in lines:
        if ln.startswith("f "):
            count += 1
            assert len(ln.split()) - 1 in (3, 4), "%s: a face of %d corners" % (name, len(ln.split()) - 1)
    assert count > 0, name + " is empty"
    open(path, "w", encoding="utf-8", newline="\n").write(
        "# Generated by art/models/control_desk/build_angled_runs.py (Blender)\n" + "\n".join(lines) + "\n")
    stray = os.path.join(MODELS, name + ".mtl")
    if os.path.isfile(stray):
        os.remove(stray)
    return count


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
    cam.location = c + d * 40.0
    cam.rotation_euler = (c - cam.location).to_track_quat("-Z", "Y").to_euler()
    scene.render.filepath = path
    bpy.ops.render.render(write_still=True)


def preview_view(kind, fam, oriented_polys, cam, name):
    xs, ys, zs = [], [], []
    for _, pts, _ in oriented_polys:
        for p in pts:
            w = world(p)
            xs.append(w[0]); ys.append(w[1]); zs.append(-w[2])
    centre = ((min(xs) + max(xs)) / 2.0, (min(zs) + max(zs)) / 2.0, 0.35 if fam == "desk" else 2.0)
    span = max(max(xs) - min(xs), max(zs) - min(zs))
    sc = span * (1.55 if fam == "desk" else 1.9) + 0.8
    frm = (0.5, 1.0, 0.85) if kind == "inside" else (-0.5, 1.0, 0.85)
    if fam == "tall":
        frm = (0.5, 1.0, 0.8) if kind == "inside" else (-0.5, 1.0, 0.8)
    shoot(cam, centre, frm, sc, os.path.join(PREVIEW, name + ".png"))
    shoot(cam, centre, (0.0, 0.02, 1.0), sc, os.path.join(PREVIEW, name + "-top.png"))


def main():
    os.makedirs(PREVIEW, exist_ok=True)
    clear_scene()
    materials = make_materials()
    tints = tint_materials()
    cam = setup_render()
    report = {}
    for fam in rp.FAMILIES:
        for kind in rp.KINDS:
            margin = check_faces(kind, fam)
            pieces = slice_pieces(kind, fam)
            plan = rp.pieces(kind, fam)
            for role, parts in pieces.items():
                for j, local in enumerate(parts):
                    base = "run_%s_%s_%s_%d" % (fam, kind, ROLE_NAME[role], j)
                    for suffix, mapping, mirror in (("", world, False), ("_m", world_m, True)):
                        obj = to_mesh("part", orient(local, mapping, mirror), materials, mapping=mapping)
                        report[base + suffix] = export(obj, base + suffix)
                        bpy.data.objects.remove(obj, do_unlink=True)
                # the whole piece for the item model, unmirrored
                whole = []
                for j, local in enumerate(parts):
                    ix, if_, iy = plan[role]["parts"][j]
                    oy = iy if fam == "tall" else 0
                    for mat, pts, uv, out in local:
                        whole.append((mat, [(p[0] + ix, p[1] + oy, p[2] + if_) for p in pts], uv, out))
                oriented = orient(whole)
                xs, ys, zs = [], [], []
                for _, pts, _ in oriented:
                    for p in pts:
                        w = world(p)
                        xs.append(w[0]); ys.append(w[1]); zs.append(w[2])
                offset = (0.5 - (min(xs) + max(xs)) / 2.0, 0.5 - (min(ys) + max(ys)) / 2.0, 0.5 - (min(zs) + max(zs)) / 2.0)
                item = to_mesh("item", oriented, materials, offset)
                report["run_%s_%s_%s_item" % (fam, kind, ROLE_NAME[role])] = export(item, "run_%s_%s_%s_item" % (fam, kind, ROLE_NAME[role]))
                bpy.data.objects.remove(item, do_unlink=True)
            print("RUN %s-%s: parts a=%d unit=%d b=%d, face margin %.3f" % (
                fam, kind, len(pieces["a"]), len(pieces["unit"]), len(pieces["b"]), margin))
    if "--no-preview" in sys.argv:
        return report
    for fam in rp.FAMILIES:
        for kind in rp.KINDS:
            polys = solid(kind, fam, 3, "preview")
            oriented = orient(polys)
            obj = to_mesh("pv", oriented, materials)
            tag = "%s-run-%s-3" % (fam, kind)
            preview_view(kind, fam, oriented, cam, tag + "-built-piece-by-piece")
            bpy.data.objects.remove(obj, do_unlink=True)
            # the pieces in three tints: cut by cube, owner by colour
            yclip = fam == "tall"
            cubes = piece_cubes(kind, fam, 3)
            owner = {}
            for (role, k), parts in cubes.items():
                for cube, _ in parts:
                    owner[cube] = ("tint_a" if role == "a" else "tint_b" if role == "b" else
                                   "tint_unit" if k % 2 == 1 else "tint_unit2")
            groups = {}
            layers = [by for by, _ in rp.FAMILIES[fam]["layers"]]
            for bx in range(-5, 4):
                for bf in range(-14, 15):
                    for by in layers:
                        local = part_polys(polys, bx, bf, by, yclip)
                        if not local:
                            continue
                        yo = by if yclip else 0
                        tintname = owner.get((bx, bf, by), "tint_s")
                        for mat, pts, uv, out in local:
                            groups.setdefault(tintname, []).append(
                                (mat, [(p[0] + bx, p[1] + yo, p[2] + bf) for p in pts], uv, out))
            objs = []
            allor = []
            for tintname, plist in groups.items():
                oriented_t = orient(plist)
                allor.extend(oriented_t)
                objs.append(to_mesh("pt", oriented_t, materials, tint=tints[tintname]))
            bpy.context.scene.display.shading.color_type = "MATERIAL"
            preview_view(kind, fam, allor, cam, tag + "-pieces")
            bpy.context.scene.display.shading.color_type = "TEXTURE"
            for o in objs:
                bpy.data.objects.remove(o, do_unlink=True)
    return report


result = main()
print("FACES", len(result))
