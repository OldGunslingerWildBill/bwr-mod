"""The duplex control board (the owner's Fusion 'Control-Panel.obj'), cut into per-block OBJ pieces with Blender.

Run headless (never against an open scene):
    blender --background --factory-startup --python art/models/control_panel_duplex/build_duplex.py
    blender ... --python art/models/control_panel_duplex/build_duplex.py -- --verify-source <path to Control-Panel.obj>
The second form first checks the profile below against the owner's 100 MB OBJ (which is not shipped): every vertex of
it must lie on the profile's outline, or inside the profile on one of the two end caps.

THE OWNER'S MODEL is one constant cross-section extruded 4 m wide. As usual for the owner's Fusion exports it lies on
its back: OBJ Z is real up and OBJ -Y is real forward (toward the operator). PROFILE is that cross-section as
(forward m, up m), with forward 0 the plane of the vertical board's face:

    floor from forward +1.0 back to -0.8; the flat back at -0.8, 4 m tall; the top from -0.8 to +0.5 at 4 m;
    the tilted annunciator face from (+0.5, 4) down to (0, 3), overhanging the operator and leaning 26.6 degrees
    from vertical; the vertical board from 3 down to 1 at forward 0; the bench top from (0, 1) down to (+1, 0.75),
    sloping 14 degrees toward the operator; the bench front at +1 down to the floor.

THE BLOCK GRID. One item places a COLUMN, 1 block wide, 2 deep and 4 tall; columns line up sideways (the owner's 4 m
board is four of them). The board's face plane is the boundary between the back and the front block of the column, so
the back blocks hold the 0.8 m slab (the rear 0.2 m of the back blocks stays empty) and the front blocks the 1 m bench.
Each block of the column is the profile clipped to that block's square, extruded one block wide:

    back  row 0  'duplex_base'    the body behind the bench           (nothing mounts here)
    back  rows 1, 2 'duplex_board' the vertical board, face at the block's front   (PanelType.DUPLEX_BOARD)
    back  row 3  'duplex_top'     the top of the slab                 (nothing mounts here)
    front row 0  'duplex_bench'   the bench                           (PanelType.DUPLEX_BENCH)
    front row 3  'duplex_header'  the tilted annunciator face          (PanelType.DUPLEX_HEADER)
    front rows 1, 2 are air: the operator's space above the bench.

A side of a piece that another piece of the same column covers exactly is left out (the column is always whole). The
outline segments of the profile that are mounting faces get the material 'face'; the clip gives each piece exactly one
such quad, and its corners are those PanelGeometry puts the 16 x 16 grid on (checked here and by PanelTrimTests).

Authoring frame (as build_trim.py): facing=north, the operator at -Z; local lx to the operator's right, y up, f from the
back of the block (0) toward the operator (1); world() maps that to block space (1 - lx, y, 1 - f).

Writes mod/src/main/resources/assets/bwr/models/block/control_panel/duplex_*.obj (the shared materials.mtl there is
build_trim.py's and is not rewritten) and preview renders under tmp/duplex-preview/.
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
PREVIEW = os.path.join(ROOT, "tmp", "duplex-preview")

# The owner's profile as (OBJ z, OBJ y) in mm, and as (forward m, up m).
SOURCE_PROFILE = [(0, -1000), (0, 800), (4000, 800), (4000, -500), (3000, 0), (1000, 0), (750, -1000)]
PROFILE = [(-y / 1000.0, z / 1000.0) for z, y in SOURCE_PROFILE]

# The outline segments that are mounting faces, and what the other outline segments are made of.
FACE_SEGMENTS = [((0.5, 4.0), (0.0, 3.0)), ((0.0, 3.0), (0.0, 1.0)), ((0.0, 1.0), (1.0, 0.75))]

MATERIALS = ["face", "paint", "bezel", "back", "kick", "cheek", "hood", "apron", "cabinet", "annunciator",
             "annunciator_lit", "vent"]

# (name, forward of the block's back edge, row) for every piece; front rows 1 and 2 clip to nothing.
CELLS = [("duplex_base", -1.0, 0), ("duplex_board", -1.0, 1), ("duplex_top", -1.0, 3),
         ("duplex_bench", 0.0, 0), ("duplex_header", 0.0, 3)]
# The mounting face of each mountable piece, as PanelGeometry places it: (lx, y, f) of grid cells (0,0), (16,0),
# (16,16), (0,16).
FRAME_CORNERS = {
    "duplex_board": [(0, 1, 1), (1, 1, 1), (1, 0, 1), (0, 0, 1)],
    "duplex_bench": [(0, 1, 0), (1, 1, 0), (1, 0.75, 1), (0, 0.75, 1)],
    "duplex_header": [(0, 1, 0.5), (1, 1, 0.5), (1, 0, 0), (0, 0, 0)],
}

EPS = 1e-9


# ---------------------------------------------------------------------------------------------------------------
# 2D geometry of the profile

def area2(poly):
    return sum(poly[i][0] * poly[(i + 1) % len(poly)][1] - poly[(i + 1) % len(poly)][0] * poly[i][1]
               for i in range(len(poly)))


def clip(poly, f0, f1, u0, u1):
    """Sutherland-Hodgman clip of a polygon to the rectangle [f0,f1] x [u0,u1]."""
    def cut(points, inside, cross):
        out = []
        for i in range(len(points)):
            a, b = points[i - 1], points[i]
            if inside(b):
                if not inside(a):
                    out.append(cross(a, b))
                out.append(b)
            elif inside(a):
                out.append(cross(a, b))
        return out

    def at_f(value):
        return lambda a, b: (value, a[1] + (b[1] - a[1]) * (value - a[0]) / (b[0] - a[0]))

    def at_u(value):
        return lambda a, b: (a[0] + (b[0] - a[0]) * (value - a[1]) / (b[1] - a[1]), value)

    pts = list(poly)
    for inside, cross in ((lambda p: p[0] >= f0 - EPS, at_f(f0)), (lambda p: p[0] <= f1 + EPS, at_f(f1)),
                          (lambda p: p[1] >= u0 - EPS, at_u(u0)), (lambda p: p[1] <= u1 + EPS, at_u(u1))):
        pts = cut(pts, inside, cross)
        if not pts:
            return []
    clean = []
    for p in pts:                          # drop repeated and collinear points
        p = (round(p[0], 9) + 0.0, round(p[1], 9) + 0.0)
        if not clean or abs(clean[-1][0] - p[0]) > EPS or abs(clean[-1][1] - p[1]) > EPS:
            clean.append(p)
    if len(clean) > 1 and abs(clean[0][0] - clean[-1][0]) < EPS and abs(clean[0][1] - clean[-1][1]) < EPS:
        clean.pop()
    changed = True
    while changed and len(clean) >= 3:
        changed = False
        for i in range(len(clean)):
            a, b, c = clean[i - 1], clean[i], clean[(i + 1) % len(clean)]
            if abs((b[0] - a[0]) * (c[1] - b[1]) - (b[1] - a[1]) * (c[0] - b[0])) < EPS:
                clean.pop(i)
                changed = True
                break
    if len(clean) < 3 or abs(area2(clean)) < EPS:
        return []
    if area2(clean) < 0:
        clean.reverse()
    return clean


def on_segment(p, a, b):
    cross = (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0])
    if abs(cross) > 1e-7:
        return False
    t = ((p[0] - a[0]) * (b[0] - a[0]) + (p[1] - a[1]) * (b[1] - a[1])) / ((b[0] - a[0]) ** 2 + (b[1] - a[1]) ** 2)
    return -1e-7 <= t <= 1 + 1e-7


def edge_material(a, b):
    """The material of an outline edge a-b (forward, up): face, back, bezel or paint."""
    for s, e in FACE_SEGMENTS:
        if on_segment(a, s, e) and on_segment(b, s, e):
            return "face"
    if abs(a[0] + 0.8) < EPS and abs(b[0] + 0.8) < EPS:
        return "back"                      # the flat back of the slab
    if abs(a[0] - 1.0) < EPS and abs(b[0] - 1.0) < EPS:
        return "paint"                     # the bench front
    return "bezel"                         # the top and the floor


# ---------------------------------------------------------------------------------------------------------------
# Pieces

def newell(pts):
    nx = ny = nz = 0.0
    for i, a in enumerate(pts):
        b = pts[(i + 1) % len(pts)]
        nx += (a[1] - b[1]) * (a[2] + b[2])
        ny += (a[2] - b[2]) * (a[0] + b[0])
        nz += (a[0] - b[0]) * (a[1] + b[1])
    return (nx, ny, nz)


def world(p):
    lx, y, f = p
    return (1.0 - lx, y, 1.0 - f)


class Piece:
    def __init__(self, name):
        self.name = name
        self.polys = []                    # (material, [local (lx, y, f)], [uv])

    def add(self, mat, pts, out, uv):
        """A polygon whose front faces along the local direction `out`."""
        n = newell([world(p) for p in pts])
        want = (-out[0], out[1], -out[2])
        if n[0] * want[0] + n[1] * want[1] + n[2] * want[2] < 0:
            pts, uv = pts[::-1], uv[::-1]
        self.polys.append((mat, pts, uv))


def cell_polygon(f_back, row):
    """The profile clipped to one block, in the block's own (f, y)."""
    poly = clip(PROFILE, f_back, f_back + 1.0, float(row), row + 1.0)
    return [(f - f_back, u - row) for f, u in poly]


def covered(f_back, row, a, b):
    """Whether the block edge a-b (block-local f, y) is shared exactly with a neighbouring block of the column."""
    if abs(a[0] - b[0]) < EPS and abs(a[0] - round(a[0])) < EPS:       # on f = 0 or f = 1
        other = (f_back - 1.0, row) if a[0] < 0.5 else (f_back + 1.0, row)
        shift = (1.0, 0.0) if a[0] < 0.5 else (-1.0, 0.0)
    elif abs(a[1] - b[1]) < EPS and abs(a[1] - round(a[1])) < EPS:     # on y = 0 or y = 1
        other = (f_back, row - 1) if a[1] < 0.5 else (f_back, row + 1)
        shift = (0.0, 1.0) if a[1] < 0.5 else (0.0, -1.0)
    else:
        return False
    if not (-1.0 - EPS <= other[0] <= 0.0 + EPS) or not (0 <= other[1] <= 3):
        return False
    poly = cell_polygon(other[0], other[1])
    a2 = (a[0] + shift[0], a[1] + shift[1])
    b2 = (b[0] + shift[0], b[1] + shift[1])
    for i in range(len(poly)):
        p, q = poly[i], poly[(i + 1) % len(poly)]
        if (math.dist(p, b2) < 1e-7 and math.dist(q, a2) < 1e-7) or (math.dist(p, a2) < 1e-7 and math.dist(q, b2) < 1e-7):
            return True
    return False


def build_piece(name, f_back, row):
    poly = cell_polygon(f_back, row)
    if not poly:
        raise AssertionError(name + " clips to nothing")
    piece = Piece(name)
    n = len(poly)
    for i in range(n):
        a, b = poly[i], poly[(i + 1) % n]
        if covered(f_back, row, a, b):
            continue
        mat = edge_material((a[0] + f_back, a[1] + row), (b[0] + f_back, b[1] + row))
        df, dy = b[0] - a[0], b[1] - a[1]
        length = math.hypot(df, dy)
        out = (0.0, -df / length, dy / length)               # counter-clockwise: the solid is on the left
        pts = [(0.0, a[1], a[0]), (1.0, a[1], a[0]), (1.0, b[1], b[0]), (0.0, b[1], b[0])]
        if mat == "face":
            # the panel face texture over the block: across, and down the face from its top edge
            top = max(a[1], b[1])
            span = abs(dy) or 1.0
            uv = [(0.0, 1.0 - (top - a[1]) / span), (1.0, 1.0 - (top - a[1]) / span),
                  (1.0, 1.0 - (top - b[1]) / span), (0.0, 1.0 - (top - b[1]) / span)]
        elif abs(dy) < EPS:
            uv = [(0.0, a[0]), (1.0, a[0]), (1.0, b[0]), (0.0, b[0])]
        else:
            uv = [(0.0, a[1]), (1.0, a[1]), (1.0, b[1]), (0.0, b[1])]
        piece.add(mat, pts, out, uv)
    # the two ends of the extrusion (the column's sides)
    for lx, out in ((0.0, (-1.0, 0.0, 0.0)), (1.0, (1.0, 0.0, 0.0))):
        pts = [(lx, y, f) for f, y in poly]
        piece.add("bezel", pts, out, [(f, y) for f, y in poly])
    return piece


def check_face(piece):
    faces = [pts for mat, pts, _ in piece.polys if mat == "face"]
    want = FRAME_CORNERS.get(piece.name)
    if want is None:
        if faces:
            raise AssertionError(piece.name + " is not mountable but has a 'face' polygon")
        return
    if len(faces) != 1 or len(faces[0]) != 4:
        raise AssertionError("%s: %d 'face' polygons" % (piece.name, len(faces)))
    got = {tuple(round(c, 6) for c in p) for p in faces[0]}
    if got != {tuple(round(float(c), 6) for c in p) for p in want}:
        raise AssertionError("%s: the face %s is not on the mounting grid %s" % (piece.name, sorted(got), want))


# ---------------------------------------------------------------------------------------------------------------
# The owner's OBJ, checked against the profile

def verify_source(path):
    def dist(p, a, b):
        dx, dy = b[0] - a[0], b[1] - a[1]
        t = max(0.0, min(1.0, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / (dx * dx + dy * dy)))
        return math.hypot(p[0] - a[0] - t * dx, p[1] - a[1] - t * dy)

    def inside(p):
        result = False
        for i in range(len(SOURCE_PROFILE)):
            (x1, y1), (x2, y2) = SOURCE_PROFILE[i], SOURCE_PROFILE[(i + 1) % len(SOURCE_PROFILE)]
            if (y1 > p[1]) != (y2 > p[1]) and p[0] < x1 + (p[1] - y1) * (x2 - x1) / (y2 - y1):
                result = not result
        return result

    count = cap = 0
    lo, hi = [1e18] * 3, [-1e18] * 3
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            if not line.startswith("v "):
                continue
            x, y, z = (float(v) for v in line.split()[1:4])
            count += 1
            for i, v in enumerate((x, y, z)):
                lo[i], hi[i] = min(lo[i], v), max(hi[i], v)
            p = (z, y)
            d = min(dist(p, SOURCE_PROFILE[i], SOURCE_PROFILE[(i + 1) % len(SOURCE_PROFILE)])
                    for i in range(len(SOURCE_PROFILE)))
            if d > 1.0:
                if not ((abs(x) < 0.01 or abs(x - 4000.0) < 0.01) and inside(p)):
                    raise AssertionError("vertex %r is %.1f mm off the profile and not on an end cap" % ((x, y, z), d))
                cap += 1
    print("SOURCE %d vertices, x %.0f..%.0f, y %.0f..%.0f, z %.0f..%.0f; all on the profile outline (%d inside an end cap)"
          % (count, lo[0], hi[0], lo[1], hi[1], lo[2], hi[2], cap))


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


def to_mesh(name, polys, materials, offset=(0.0, 0.0, 0.0)):
    verts, index, faces, uvs, mats = [], {}, [], [], []
    for mat, pts, uv in polys:
        loop = []
        for p in pts:
            wx, wy, wz = world(p)
            wx, wy, wz = wx + offset[0], wy + offset[1], wz + offset[2]
            key = (round(wx, 6), round(wy, 6), round(wz, 6))
            if key not in index:
                index[key] = len(verts)
                verts.append((wx, -wz, wy))               # block (x, y, z) -> Blender (x, -z, y)
            loop.append(index[key])
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
        "# Generated by art/models/control_panel_duplex/build_duplex.py (Blender)\n" + "\n".join(lines) + "\n")
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


def preview(objs):
    """The four-column board (the owner's 4 m) from the front three-quarter and from the side, like the references."""
    from mathutils import Vector
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
    os.makedirs(PREVIEW, exist_ok=True)
    for column in range(1, 4):
        for obj in list(objs):
            copy = obj.copy()
            copy.location = (column, 0.0, 0.0)
            scene.collection.objects.link(copy)
    cam = bpy.data.objects.new("cam", bpy.data.cameras.new("cam"))
    scene.collection.objects.link(cam)
    scene.camera = cam
    cam.data.type = "ORTHO"
    cam.data.ortho_scale = 6.5
    centre = Vector((2.0, -1.0, 2.0))                 # Blender +Y is the operator's side (block -Z)
    for view, direction in (("front34", (-0.7, 1.0, 0.3)), ("side", (-1.0, 0.0, 0.0))):
        d = Vector(direction).normalized()
        cam.location = centre + d * 20.0
        cam.rotation_euler = (centre - cam.location).to_track_quat("-Z", "Y").to_euler()
        scene.render.filepath = os.path.join(PREVIEW, "duplex-%s.png" % view)
        bpy.ops.render.render(write_still=True)


def main():
    args = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    if "--verify-source" in args:
        verify_source(args[args.index("--verify-source") + 1])
    clear_scene()
    materials = make_materials()
    report = {}
    placed = []                  # one column, assembled, for the preview
    item = []                    # the whole column, centred on the item's block
    for name, f_back, row in CELLS:
        piece = build_piece(name, f_back, row)
        check_face(piece)
        report[name] = export(to_mesh(name, piece.polys, materials), name)
        back = 1.0 if f_back < 0 else 0.0          # the back blocks are one block behind the bench (+Z)
        for r in ((1, 2) if name == "duplex_board" else (row,)):
            placed.append(to_mesh("%s_%d" % (name, r), piece.polys, materials, (0.0, float(r), back)))
            item.extend((mat, [(lx, y + r - 1.5, f + f_back + 0.5) for lx, y, f in pts], uv) for mat, pts, uv in piece.polys)
    report["duplex_item"] = export(to_mesh("duplex_item", item, materials), "duplex_item")
    for obj in bpy.data.objects:
        obj.hide_render = obj not in placed
    if "--no-preview" not in args:
        preview(placed)
    return report


result = main()
print("FACES", result, "per column", sum(v for k, v in result.items() if k != "duplex_item") + result["duplex_board"])
