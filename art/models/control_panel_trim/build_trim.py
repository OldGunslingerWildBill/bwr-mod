"""Control panel bodies and trim blocks, modelled in Blender and exported as OBJ + MTL.

Run headless (never against an open scene):
    blender --background --factory-startup --python art/models/control_panel_trim/build_trim.py
or through the Blender MCP background tool on art/models/control_panel_trim/control_panel_trim.blend:
    exec(open(<this file>).read())

What it makes, into mod/src/main/resources/assets/bwr/models/block/:
  control_panel/{vertical,benchboard,console}.obj   the three panel bodies, remodelled
  control_panel_trim/*.obj                          end caps, header strips, plinths, corner fillers
plus one materials.mtl in each folder, and preview renders under tmp/trim-preview/.

Authoring frame (the same as PanelGeometry and the old tools-export-panels.py): a model is authored for
facing=north, the operator at -Z. Model code works in a local frame
    lx  to the operator's right, 0..1
    y   up, 0..1
    f   from the back (0) toward the operator (1)
and world() maps it to block space (1 - lx, y, 1 - f). One block is one unit.

THE MOUNTING FACE IS NOT TO BE MOVED. The 'face' material quads of the three bodies keep the four corners they
always had (the plane PanelGeometry puts the 16 x 16 grid on); every added detail lies under or behind that plane,
and nothing but the face material is allowed on it. PanelTrimTests reads the shipped OBJ files and checks it.
"""
import math
import os
import sys

import bpy

try:
    HERE = os.path.dirname(os.path.abspath(__file__))
except NameError:                       # exec'd by the MCP tool: the open .blend sits next to this script
    HERE = os.path.dirname(os.path.abspath(bpy.data.filepath))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
ASSETS = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "bwr")
MODELS = os.path.join(ASSETS, "models", "block")
TEXTURES = os.path.join(ASSETS, "textures", "block", "control_panel")
PREVIEW = os.path.join(ROOT, "tmp", "trim-preview")

PX = 1.0 / 16.0
K = 0.125                                # the toe-kick: 100 mm in the research, 2 px here
KF = PX                                  # the toe-kick under a console's front lip (the desk is only 1.7 px high there)
YK = PX
T12 = math.tan(math.radians(12.0))
T15 = math.tan(math.radians(15.0))
CONSOLE_TOP = 0.375                      # PanelGeometry.CONSOLE_TOP
CONSOLE_FRONT = CONSOLE_TOP - T15        # the face at the front edge
VERTICAL_DEPTH = 0.25                    # PanelGeometry.VERTICAL_DEPTH
CAP = 0.125                              # thickness of an end cap, 2 px
HEADER_H = 0.5
HEADER_FRONT = VERTICAL_DEPTH + HEADER_H * T12

MATERIALS = ["face", "paint", "bezel", "back", "kick", "cheek", "hood", "apron", "cabinet", "annunciator"]
# Materials whose texture is stretched over the polygon's own bounding box rather than laid on the block grid.
FIT = {"back", "cheek", "cabinet"}


def world(p):
    lx, y, f = p
    return (1.0 - lx, y, 1.0 - f)


def sub(a, b):
    return (a[0] - b[0], a[1] - b[1], a[2] - b[2])


def cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def dot(a, b):
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def newell(pts):
    nx = ny = nz = 0.0
    for i, a in enumerate(pts):
        b = pts[(i + 1) % len(pts)]
        nx += (a[1] - b[1]) * (a[2] + b[2])
        ny += (a[2] - b[2]) * (a[0] + b[0])
        nz += (a[0] - b[0]) * (a[1] + b[1])
    return (nx, ny, nz)


def triangulate(pts):
    """Ear clipping of a planar simple polygon (3D points). Returns index triples; every triple keeps the order."""
    n = newell(pts)
    axis = max(range(3), key=lambda i: abs(n[i]))
    keep = [i for i in range(3) if i != axis]
    flat = [(p[keep[0]], p[keep[1]]) for p in pts]
    area2 = sum(flat[i][0] * flat[(i + 1) % len(pts)][1] - flat[(i + 1) % len(pts)][0] * flat[i][1] for i in range(len(pts)))
    sign = 1.0 if area2 > 0 else -1.0

    def turn(a, b, c):
        return ((flat[b][0] - flat[a][0]) * (flat[c][1] - flat[b][1]) - (flat[b][1] - flat[a][1]) * (flat[c][0] - flat[b][0])) * sign

    def inside(p, a, b, c):
        d1 = (flat[b][0] - flat[a][0]) * (flat[p][1] - flat[a][1]) - (flat[b][1] - flat[a][1]) * (flat[p][0] - flat[a][0])
        d2 = (flat[c][0] - flat[b][0]) * (flat[p][1] - flat[b][1]) - (flat[c][1] - flat[b][1]) * (flat[p][0] - flat[b][0])
        d3 = (flat[a][0] - flat[c][0]) * (flat[p][1] - flat[c][1]) - (flat[a][1] - flat[c][1]) * (flat[p][0] - flat[c][0])
        return (d1 * sign > 1e-9 and d2 * sign > 1e-9 and d3 * sign > 1e-9)

    rest = list(range(len(pts)))
    out = []
    guard = 0
    while len(rest) > 3 and guard < 1000:
        guard += 1
        for k in range(len(rest)):
            a, b, c = rest[k - 1], rest[k], rest[(k + 1) % len(rest)]
            t = turn(a, b, c)
            if abs(t) <= 1e-9:                      # a collinear vertex: drop it, it spans no area
                rest.pop(k)
                break
            if t < 0:
                continue
            if any(inside(p, a, b, c) for p in rest if p not in (a, b, c)):
                continue
            out.append((a, b, c))
            rest.pop(k)
            break
        else:
            raise RuntimeError("cannot triangulate " + repr(pts))
    out.append((rest[0], rest[1], rest[2]))
    return out


class Model:
    """Polygons in local coordinates; build() turns them into a Blender mesh object."""

    def __init__(self, name):
        self.name = name
        self.polys = []         # (material, [local points], [uv])

    def add(self, mat, pts, hint, uv=None):
        pts = [tuple(float(c) for c in p) for p in pts]
        if uv is None:
            uv = self.default_uv(mat, pts)
        if len(pts) > 4:
            for tri in triangulate(pts):
                self.add(mat, [pts[i] for i in tri], hint, [uv[i] for i in tri])
            return
        wp = [world(p) for p in pts]
        nrm = newell(wp)
        want = (-hint[0], hint[1], -hint[2])
        if dot(nrm, want) < 0:
            pts = pts[::-1]
            uv = uv[::-1]
        self.polys.append((mat, pts, [tuple(map(float, t)) for t in uv]))

    @staticmethod
    def default_uv(mat, pts):
        n = newell(pts)
        axis = max(range(3), key=lambda i: abs(n[i]))
        if axis == 1:
            coords = [(p[0], p[2]) for p in pts]          # top/bottom: lx across, f up the picture
        elif axis == 0:
            coords = [(p[2], p[1]) for p in pts]          # a side: f across, y up
        else:
            coords = [(p[0], p[1]) for p in pts]          # front/back: lx across, y up
        if mat in FIT:
            us = [c[0] for c in coords]
            vs = [c[1] for c in coords]
            du = (max(us) - min(us)) or 1.0
            dv = (max(vs) - min(vs)) or 1.0
            return [((c[0] - min(us)) / du, (c[1] - min(vs)) / dv) for c in coords]
        return coords

    # -- shapes ---------------------------------------------------------------------------------------------

    def prism(self, profile, x0, x1, cap_in="bezel", cap_out="bezel", remap=None):
        """Extrude a profile across lx = x0..x1. profile is [(f, y, material of the edge to the next vertex)],
        counter-clockwise in the (f right, y up) picture, so that interior is on the left of each edge."""
        remap = remap or {}
        n = len(profile)
        for i in range(n):
            f0, y0, mat = profile[i]
            f1, y1, _ = profile[(i + 1) % n]
            mat = remap.get(mat, mat)
            df, dy = f1 - f0, y1 - y0
            length = math.hypot(df, dy)
            hint = (0.0, -df / length, dy / length)
            pts = [(x0, y0, f0), (x1, y0, f0), (x1, y1, f1), (x0, y1, f1)]
            uv = None
            if mat == "face":
                lo, hi = min(y0, y1), max(y0, y1)
                span = (hi - lo) or 1.0
                uv = [(x0, (y0 - lo) / span), (x1, (y0 - lo) / span), (x1, (y1 - lo) / span), (x0, (y1 - lo) / span)]
            self.add(mat, pts, hint, uv)
        self.add(remap.get(cap_in, cap_in), [(x0, y, f) for f, y, _ in profile], (-1.0, 0.0, 0.0))
        self.add(remap.get(cap_out, cap_out), [(x1, y, f) for f, y, _ in profile], (1.0, 0.0, 0.0))

    def plan_prism(self, plan, y0, y1, bottom="bezel", top="bezel"):
        """Extrude a plan polygon [(lx, f, material of the edge to the next vertex)], counter-clockwise in the
        (lx right, f up) picture, between heights y0 and y1."""
        n = len(plan)
        for i in range(n):
            a, b = plan[i], plan[(i + 1) % n]
            dl, df = b[0] - a[0], b[1] - a[1]
            length = math.hypot(dl, df)
            hint = (df / length, 0.0, -dl / length)
            pts = [(a[0], y0, a[1]), (b[0], y0, b[1]), (b[0], y1, b[1]), (a[0], y1, a[1])]
            uv = None
            if a[2] == "face":
                along = (lambda p: p[0]) if abs(dl) >= abs(df) else (lambda p: p[1])
                uv = [(along(a), y0), (along(b), y0), (along(b), y1), (along(a), y1)]
            self.add(a[2], pts, hint, uv)
        self.add(top, [(p[0], y1, p[1]) for p in plan], (0.0, 1.0, 0.0))
        self.add(bottom, [(p[0], y0, p[1]) for p in plan], (0.0, -1.0, 0.0))

    # -- Blender ---------------------------------------------------------------------------------------------

    def build(self, materials):
        verts, index, faces, uvs, mats = [], {}, [], [], []
        for mat, pts, uv in self.polys:
            loop = []
            for p in pts:
                wx, wy, wz = world(p)
                key = (round(wx, 6), round(wy, 6), round(wz, 6))
                if key not in index:
                    index[key] = len(verts)
                    verts.append((wx, -wz, wy))               # block (x, y, z) -> Blender (x, -z, y)
                loop.append(index[key])
            faces.append(loop)
            uvs.extend(uv)
            mats.append(mat)
        mesh = bpy.data.meshes.new(self.name)
        mesh.from_pydata(verts, [], faces)
        for name in MATERIALS:
            mesh.materials.append(materials[name])
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
        obj = bpy.data.objects.new(self.name, mesh)
        bpy.context.scene.collection.objects.link(obj)
        return obj


# ---------------------------------------------------------------------------------------------------------------
# Profiles, (f, y, material of the edge to the next vertex), counter-clockwise in the (f right, y up) picture.
# Each has the same mounting-face edge the old placeholder had, with the same two end points.

VERTICAL_PROFILE = [(0.0, 0.0, "bezel"), (VERTICAL_DEPTH, 0.0, "face"), (VERTICAL_DEPTH, 1.0, "bezel"),
                    (0.0, 1.0, "back")]

BENCH_PROFILE = [(K, 0.0, "bezel"),            # underside
                 (1.0, 0.0, "face"),           # the 45 degree mounting face, front-bottom to back-top
                 (0.0, 1.0, "back"),           # the back wall
                 (0.0, K, "kick"),             # under the back wall's foot: the dark recessed toe-kick
                 (K, K, "kick")]

CONSOLE_PROFILE = [(K, 0.0, "bezel"),
                   (1.0 - KF, 0.0, "kick"),            # front toe-kick, under the lip
                   (1.0 - KF, YK, "kick"),
                   (1.0, YK, "bezel"),                 # the front lip
                   (1.0, CONSOLE_FRONT, "face"),       # the 15 degree mounting face, front to back
                   (0.0, CONSOLE_TOP, "back"),
                   (0.0, K, "kick"),
                   (K, K, "kick")]

HEADER_STRIP_PROFILE = [(0.0, 0.0, "bezel"), (VERTICAL_DEPTH, 0.0, "annunciator"), (HEADER_FRONT, HEADER_H, "bezel"),
                        (0.0, HEADER_H, "back")]

HOOD_Y = 0.4688                                 # where the windows stop under the hood
HOOD_F = 0.55                                   # how far the hood projects
HOOD_TOP = 0.5625
HEADER_CANOPY_PROFILE = [(0.0, 0.0, "bezel"), (VERTICAL_DEPTH, 0.0, "annunciator"),
                         (VERTICAL_DEPTH + HOOD_Y * T12, HOOD_Y, "hood"), (HOOD_F, HOOD_Y, "hood"),
                         (HOOD_F, HOOD_TOP, "hood"), (0.0, HOOD_TOP, "back")]

APRON_PROFILE = [(0.0, 0.0, "kick"), (1.0, 0.0, "kick"), (1.0, PX, "kick"), (1.0 - PX, K, "apron"), (0.0, K, "kick")]

BASE_PROFILE = [(0.0, 0.0, "kick"), (1.0 - K, 0.0, "kick"), (1.0 - K, K, "kick"), (1.0, K, "cabinet"),
                (1.0, 1.0, "bezel"), (0.0, 1.0, "back")]
# (the profile above lists the edge to the next vertex: bottom, toe-kick wall, underside of the body, front, top, back)


def check_face_unchanged(model, corners, name):
    """Asserts the 'face' polygons of a body span exactly the four corners the mounting plane has always had."""
    got = set()
    for mat, pts, _ in model.polys:
        if mat == "face":
            for p in pts:
                got.add(tuple(round(c, 6) for c in p))
    want = {tuple(round(c, 6) for c in p) for p in corners}
    if got != want:
        raise AssertionError("%s: the mounting face moved: %s != %s" % (name, sorted(got), sorted(want)))


def vertical_body():
    """The vertical board. The face is the whole front; the back edges are bevelled and the back is a service door."""
    m = Model("vertical")
    c = PX
    d = VERTICAL_DEPTH
    m.add("face", [(0, 0, d), (1, 0, d), (1, 1, d), (0, 1, d)], (0, 0, 1), [(0, 0), (1, 0), (1, 1), (0, 1)])
    m.add("back", [(c, 0, 0), (1 - c, 0, 0), (1 - c, 1, 0), (c, 1, 0)], (0, 0, -1),
          [(c, 0), (1 - c, 0), (1 - c, 1), (c, 1)])
    m.add("bezel", [(0, 0, c), (c, 0, 0), (c, 1, 0), (0, 1, c)], (-1, 0, -1))      # bevels of the two back edges
    m.add("bezel", [(1 - c, 0, 0), (1, 0, c), (1, 1, c), (1 - c, 1, 0)], (1, 0, -1))
    m.add("bezel", [(0, 0, c), (0, 0, d), (0, 1, d), (0, 1, c)], (-1, 0, 0))
    m.add("bezel", [(1, 0, d), (1, 0, c), (1, 1, c), (1, 1, d)], (1, 0, 0))
    hexagon = [(0, d), (1, d), (1, c), (1 - c, 0), (c, 0), (0, c)]
    m.add("bezel", [(x, 1, f) for x, f in hexagon], (0, 1, 0))
    m.add("bezel", [(x, 0, f) for x, f in hexagon], (0, -1, 0))
    check_face_unchanged(m, [(0, 0, d), (1, 0, d), (1, 1, d), (0, 1, d)], "vertical")
    return m


def benchboard_body():
    m = Model("benchboard")
    m.prism(BENCH_PROFILE, 0.0, 1.0)
    check_face_unchanged(m, [(0, 0, 1), (1, 0, 1), (1, 1, 0), (0, 1, 0)], "benchboard")
    return m


def console_body():
    m = Model("console")
    m.prism(CONSOLE_PROFILE, 0.0, 1.0)
    check_face_unchanged(m, [(0, CONSOLE_FRONT, 1), (1, CONSOLE_FRONT, 1), (1, CONSOLE_TOP, 0), (0, CONSOLE_TOP, 0)],
                         "console")
    return m


# ---------------------------------------------------------------------------------------------------------------
# End caps: a 2 px cheek with the exact cross-section of its panel, closing one open side of a run. 'right' sits
# at the left edge of the block (its cell is to the right of the run); 'left' at the right edge.

CAP_REMAP = {"face": "paint", "back": "bezel"}


def end_cap(shape, side):
    m = Model("end_cap_%s_%s" % (shape, side))
    x0, x1 = (0.0, CAP) if side == "right" else (1.0 - CAP, 1.0)
    if side == "right":
        cap_in, cap_out = "bezel", "cheek"          # lx=x0 touches the run, lx=x1 is the open end
    else:
        cap_in, cap_out = "cheek", "bezel"
    if shape == "round":
        round_cap(m, side)
        return m
    profile = {"flat": VERTICAL_PROFILE, "bench": BENCH_PROFILE, "console": CONSOLE_PROFILE}[shape]
    m.prism(profile, x0, x1, cap_in, cap_out, CAP_REMAP)
    return m


def round_cap(m, side):
    """A bullnose: a half-disc in plan, 4 px across (the thickness of the board) and 2 px out, 1 high."""
    r = VERTICAL_DEPTH / 2.0
    steps = 8
    sign = 1.0 if side == "right" else -1.0
    base = 0.0 if side == "right" else 1.0
    ring = []
    for i in range(steps + 1):
        a = -math.pi / 2.0 + math.pi * i / steps
        ring.append((base + sign * CAP * math.cos(a), r + r * math.sin(a)))     # an elliptical nose, CAP deep
    for a, b in zip(ring, ring[1:]):
        out = ((a[0] + b[0]) / 2.0 - base, 0.0, (a[1] + b[1]) / 2.0 - r)
        m.add("paint", [(a[0], 0, a[1]), (b[0], 0, b[1]), (b[0], 1, b[1]), (a[0], 1, a[1])], out)
    m.add("paint", [(x, 1, f) for x, f in ring], (0, 1, 0))
    m.add("bezel", [(x, 0, f) for x, f in ring], (0, -1, 0))
    # the straight side against the run
    m.add("bezel", [(base, 0, 0), (base, 0, VERTICAL_DEPTH), (base, 1, VERTICAL_DEPTH), (base, 1, 0)], (-sign, 0, 0))


# ---------------------------------------------------------------------------------------------------------------
# Header strips, sitting on top of a vertical board, tilted forward 12 degrees.

def header(shape):
    m = Model("header_" + shape)
    profile = HEADER_STRIP_PROFILE if shape == "strip" else HEADER_CANOPY_PROFILE
    n = len(profile)
    for i in range(n):
        f0, y0, mat = profile[i]
        f1, y1, _ = profile[(i + 1) % n]
        df, dy = f1 - f0, y1 - y0
        length = math.hypot(df, dy)
        hint = (0.0, -df / length, dy / length)
        pts = [(0, y0, f0), (1, y0, f0), (1, y1, f1), (0, y1, f1)]
        uv = None
        if mat == "annunciator":
            uv = [(0, 0), (1, 0), (1, 1), (0, 1)] if y1 > y0 else [(0, 1), (1, 1), (1, 0), (0, 0)]
        m.add(mat, pts, hint, uv)
    m.add("bezel", [(0, y, f) for f, y, _ in profile], (-1, 0, 0))
    m.add("bezel", [(1, y, f) for f, y, _ in profile], (1, 0, 0))
    return m


# ---------------------------------------------------------------------------------------------------------------
# Plinths.

def plinth(shape):
    m = Model("plinth_" + shape)
    if shape == "kick":
        d = K
        m.prism([(0.0, 0.0, "kick"), (d, 0.0, "kick"), (d, K, "kick"), (0.0, K, "kick")], 0.0, 1.0, "kick", "kick")
    elif shape == "apron":
        m.prism(APRON_PROFILE, 0.0, 1.0, "kick", "kick")
    else:
        m.prism(BASE_PROFILE, 0.0, 1.0, "bezel", "bezel")
    return m


# ---------------------------------------------------------------------------------------------------------------
# Corner fillers. The second wall is at lx = 0 (the operator's left); the first wall is the back (f = 0).

D = VERTICAL_DEPTH


def corner_vertical_inside():
    m = Model("corner_vertical_inside")
    # edges: back wall, A's open end, A's face, B's face, B's open end, the left wall
    plan = [(0, 0, "back"), (1, 0, "bezel"), (1, D, "face"), (D, D, "face"), (D, 1, "bezel"), (0, 1, "back")]
    m.plan_prism(plan, 0.0, 1.0)
    return m


def corner_vertical_chamfer():
    m = Model("corner_vertical_chamfer")
    c = 0.5
    plan = [(0, 0, "back"), (1, 0, "bezel"), (1, D, "face"), (D + c, D, "face"), (D, D + c, "face"),
            (D, 1, "bezel"), (0, 1, "back")]
    m.plan_prism(plan, 0.0, 1.0)
    return m


def corner_vertical_outside():
    """The post where two runs turn the outside of a corner: 4 x 4 px, the two faces in line with the runs.
    It sits at the back-left of the cell, like the other corners: one run goes on to the left, the other behind."""
    m = Model("corner_vertical_outside")
    b = PX
    plan = [(0, 0, "bezel"), (D, 0, "face"), (D, D - b, "bezel"), (D - b, D, "face"), (0, D, "bezel")]
    m.plan_prism(plan, 0.0, 1.0)
    return m


def corner_bench_inside():
    m = Model("corner_bench_inside")
    m.add("face", [(0, 1, 0), (1, 1, 0), (1, 0, 1)], (0, 1, 1), [(0, 1), (1, 1), (1, 0)])
    m.add("face", [(0, 1, 0), (1, 0, 1), (0, 1, 1)], (1, 1, 0), [(0, 1), (1, 0), (1, 1)])
    m.add("back", [(0, 0, 0), (1, 0, 0), (1, 1, 0), (0, 1, 0)], (0, 0, -1))
    m.add("back", [(0, 0, 0), (0, 0, 1), (0, 1, 1), (0, 1, 0)], (-1, 0, 0))
    m.add("bezel", [(0, 0, 0), (1, 0, 0), (1, 0, 1), (0, 0, 1)], (0, -1, 0))
    return m


def corner_bench_outside():
    m = Model("corner_bench_outside")
    # surface y = min(1 - f, 1 - lx): the peak (0, 1, 0) at the back-left corner, falling to the far corners
    m.add("face", [(0, 1, 0), (0, 0, 1), (1, 0, 1)], (0, 1, 1), [(0, 1), (0, 0), (1, 0)])
    m.add("face", [(0, 1, 0), (1, 0, 1), (1, 0, 0)], (1, 1, 0), [(0, 1), (1, 0), (1, 1)])
    m.add("bezel", [(0, 0, 0), (0, 1, 0), (0, 0, 1)], (-1, 0, 0))
    m.add("bezel", [(0, 0, 0), (0, 1, 0), (1, 0, 0)], (0, 0, -1))
    m.add("bezel", [(0, 0, 0), (1, 0, 0), (1, 0, 1), (0, 0, 1)], (0, -1, 0))
    return m


def corner_bench_chamfer():
    m = Model("corner_bench_chamfer")
    # y = min(1, 2 - lx - f): a steep diagonal facet below a flat deck at the corner
    m.add("face", [(1, 1, 0), (0, 1, 1), (1, 0, 1)], (1, 1, 1), [(1, 1), (0.5, 1), (1, 0)])
    m.add("bezel", [(0, 1, 0), (1, 1, 0), (0, 1, 1)], (0, 1, 0))
    m.add("back", [(0, 0, 0), (1, 0, 0), (1, 1, 0), (0, 1, 0)], (0, 0, -1))
    m.add("back", [(0, 0, 0), (0, 0, 1), (0, 1, 1), (0, 1, 0)], (-1, 0, 0))
    m.add("bezel", [(1, 0, 0), (1, 1, 0), (1, 0, 1)], (1, 0, 0))
    m.add("bezel", [(0, 0, 1), (0, 1, 1), (1, 0, 1)], (0, 0, 1))
    m.add("bezel", [(0, 0, 0), (1, 0, 0), (1, 0, 1), (0, 0, 1)], (0, -1, 0))
    return m


# ---------------------------------------------------------------------------------------------------------------

BODIES = [("control_panel", "vertical", vertical_body), ("control_panel", "benchboard", benchboard_body),
          ("control_panel", "console", console_body)]
TRIM = []
for shape in ("flat", "bench", "console", "round"):
    for side in ("left", "right"):
        TRIM.append(("control_panel_trim", "end_cap_%s_%s" % (shape, side), (lambda s=shape, d=side: end_cap(s, d))))
TRIM += [("control_panel_trim", "header_strip", lambda: header("strip")),
         ("control_panel_trim", "header_canopy", lambda: header("canopy")),
         ("control_panel_trim", "plinth_kick", lambda: plinth("kick")),
         ("control_panel_trim", "plinth_apron", lambda: plinth("apron")),
         ("control_panel_trim", "plinth_base", lambda: plinth("base")),
         ("control_panel_trim", "corner_vertical_inside", corner_vertical_inside),
         ("control_panel_trim", "corner_vertical_outside", corner_vertical_outside),
         ("control_panel_trim", "corner_vertical_chamfer", corner_vertical_chamfer),
         ("control_panel_trim", "corner_bench_inside", corner_bench_inside),
         ("control_panel_trim", "corner_bench_outside", corner_bench_outside),
         ("control_panel_trim", "corner_bench_chamfer", corner_bench_chamfer)]

MTL = "".join("newmtl %s\nKd 1.000000 1.000000 1.000000\nmap_Kd bwr:block/control_panel/%s\n\n" % (n, n)
              for n in MATERIALS)


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


def export(obj, folder, name):
    directory = os.path.join(MODELS, folder)
    os.makedirs(directory, exist_ok=True)
    path = os.path.join(directory, name + ".obj")
    bpy.ops.object.select_all(action="DESELECT")
    obj.select_set(True)
    bpy.context.view_layer.objects.active = obj
    bpy.ops.wm.obj_export(filepath=path, export_selected_objects=True, export_uv=True, export_normals=True,
                          export_materials=True, export_triangulated_mesh=False, export_smooth_groups=False,
                          forward_axis="NEGATIVE_Z", up_axis="Y", global_scale=1.0, path_mode="STRIP")
    text = open(path, encoding="utf-8").read()
    lines = []
    for line in text.splitlines():
        if line.startswith("mtllib"):
            line = "mtllib materials.mtl"
        elif line.startswith("o ") or line.startswith("g "):
            continue
        elif line.startswith("# Blender") or line.startswith("# www.blender"):
            continue
        lines.append(line)
    open(path, "w", encoding="utf-8", newline="\n").write(
        "# Generated by art/models/control_panel_trim/build_trim.py (Blender)\n" + "\n".join(lines) + "\n")
    stray = os.path.join(directory, name + ".mtl")
    if os.path.isfile(stray):
        os.remove(stray)
    return sum(1 for ln in lines if ln.startswith("f "))


def clear_scene():
    for obj in list(bpy.data.objects):
        bpy.data.objects.remove(obj, do_unlink=True)
    for coll in (bpy.data.meshes, bpy.data.materials, bpy.data.images):
        for item in list(coll):
            coll.remove(item)


def preview(objs, rows, tag):
    """Renders each row of objects from the operator side and from behind."""
    scene = bpy.context.scene
    scene.render.engine = "BLENDER_WORKBENCH"
    shading = scene.display.shading
    shading.light = "STUDIO"
    shading.color_type = "TEXTURE"
    shading.show_cavity = False
    scene.display.render_aa = "OFF"
    scene.render.film_transparent = False
    world = bpy.data.worlds.new("w") if not scene.world else scene.world
    scene.world = world
    world.color = (0.55, 0.62, 0.7)
    scene.render.image_settings.file_format = "PNG"
    scene.view_settings.view_transform = "Standard"
    os.makedirs(PREVIEW, exist_ok=True)
    cam_data = bpy.data.cameras.new("cam")
    cam_data.type = "ORTHO"
    cam = bpy.data.objects.new("cam", cam_data)
    scene.collection.objects.link(cam)
    scene.camera = cam
    from mathutils import Vector
    for r, row in enumerate(rows):
        members = [objs[n] for n in row]
        for o in objs.values():
            o.hide_render = o not in members
        spacing = 1.5
        for i, o in enumerate(members):
            o.location = (i * spacing, 0.0, 0.0)
        cx = (len(members) - 1) * spacing / 2.0 + 0.5
        width = len(members) * spacing + 0.4
        scene.render.resolution_x = 1600
        scene.render.resolution_y = int(1600 * max(0.4, 2.5 / width))
        cam_data.ortho_scale = width * 1.02
        # yaw: from the operator side (Blender +Y) turned 28 degrees; and from behind
        for view, yaw in (("front", 28.0), ("back", 180.0 - 28.0)):
            a = math.radians(yaw)
            el = math.radians(26.0)
            target = Vector((cx, -0.5, 0.55))
            cam.location = target + Vector((math.sin(a) * math.cos(el), math.cos(a) * math.cos(el), math.sin(el))) * 12.0
            cam.rotation_euler = (target - cam.location).to_track_quat("-Z", "Y").to_euler()
            scene.render.filepath = os.path.join(PREVIEW, "%s-%d-%s.png" % (tag, r, view))
            bpy.ops.render.render(write_still=True)
        for o in members:
            o.location = (0.0, 0.0, 0.0)
    for o in objs.values():
        o.hide_render = False


def main():
    clear_scene()
    materials = make_materials()
    report = {}
    objs = {}
    for folder, name, builder in BODIES + TRIM:
        model = builder()
        obj = model.build(materials)
        obj.name = name
        objs[name] = obj
        report[folder + "/" + name] = export(obj, folder, name)
    for folder in ("control_panel", "control_panel_trim"):
        with open(os.path.join(MODELS, folder, "materials.mtl"), "w", encoding="utf-8", newline="\n") as fh:
            fh.write(MTL)
    rows = [["vertical", "benchboard", "console"],
            ["end_cap_flat_right", "end_cap_bench_right", "end_cap_console_right", "end_cap_round_right",
             "end_cap_flat_left", "end_cap_round_left"],
            ["header_strip", "header_canopy", "plinth_kick", "plinth_apron", "plinth_base"],
            ["corner_vertical_inside", "corner_vertical_outside", "corner_vertical_chamfer",
             "corner_bench_inside", "corner_bench_outside", "corner_bench_chamfer"]]
    if "--no-preview" not in sys.argv:
        preview(objs, rows, "trim")
    if bpy.data.filepath:
        bpy.ops.wm.save_mainfile()
    return report


result = main()
print("FACES", result)
