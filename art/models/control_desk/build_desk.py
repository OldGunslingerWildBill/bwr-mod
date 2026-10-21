"""The control desks (the owner's Fusion 'Control Desk 1m.obj' and 'Control Desk.obj'), as block OBJ models, with Blender.

Run headless (never against an open scene):
    blender --background --factory-startup --python art/models/control_desk/build_desk.py
    blender ... --python art/models/control_desk/build_desk.py -- --verify-source <folder holding the two owner OBJs>
The second form first checks the profile below against both of the owner's files: every vertex of each must lie on the
profile's outline, or inside the profile on one of the two end caps, and the bounds must be 100 and 200 units wide.

THE OWNER'S MODELS are one constant cross-section extruded 1 m (Control Desk 1m) and 2 m (Control Desk) wide, 420 and
762 triangles, no UVs, one material (Steel - Satin). Units are centimetres (the "1m" desk is 100 wide): one block is
one metre, as in every other model of the mod. As usual for the owner's Fusion exports OBJ Z is real up and OBJ -Y is
real forward (toward the operator); rendering the file headless shows the sloped underside, where the operator's knees
go, on the -Y side. PROFILE is the cross-section as (forward m, up m), with forward 0 the back of the desk:

    the flat back at forward 0, 0.575 up; the narrow shelf along the back from 0 to 0.075 at 0.575; the riser down to
    (0.125, 0.40); the flat top, the operating surface, from 0.125 to 0.5 forward at 0.40; the sloped front falling from
    the front edge (0.5, 0.40) back under the desk to (0.2, 0), the knee space; the floor from 0.2 back to 0.

(The owner's file has extra collinear points on the long edges and the riser; they are dropped, the shape is the same.)

THE BLOCK. One desk is one block wide, standing against the back of its block, so the top (the mounting face) is a
16 x 6 cell grid, 0.375 block deep, level, 0.40 up. The 2 m desk is the same block twice side by side; the model of each
half is the one 1 m model, and an item model shows both. The mounting face is the only 'face' polygon, exactly the
corners PanelGeometry puts the grid on (checked here and by PanelTrimTests). The rest of the desk takes the paint
(riser, tint index 0), the bezel (shelf top, ends: tint index 1), the back (service door side) and the dark kick (the
sloped front and the floor; no tint), so it takes the paint finishes like the other bodies.

Authoring frame (as build_trim.py): facing=north, the operator at -Z; local lx to the operator's right, y up, f from the
back of the block (0) toward the operator (1); world() maps that to block space (1 - lx, y, 1 - f).

Writes mod/src/main/resources/assets/bwr/models/block/control_panel/desk.obj, desk_item.obj and desk_wide_item.obj (the shared
materials.mtl there is build_trim.py's and is not rewritten) and preview renders under tmp/desk-preview/.
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
PREVIEW = os.path.join(ROOT, "tmp", "desk-preview")

# The owner's profile as (OBJ y, OBJ z) in centimetres (the extrusion runs along OBJ x), and as (forward m, up m).
# Listed front-top, riser foot, riser head, back-top, back-foot, front-foot: a closed loop.
SOURCE_PROFILE = [(-30.0, 40.0), (7.5, 40.0), (12.5, 57.5), (20.0, 57.5), (20.0, 0.0), (0.0, 0.0)]
BACK_Y = 20.0
PROFILE = [((BACK_Y - y) / 100.0, z / 100.0) for y, z in SOURCE_PROFILE]
TOP = 0.40
FACE_BACK = 0.125      # forward of the back edge of the top (the foot of the riser)
FACE_FRONT = 0.5       # forward of the front edge of the top

# The two ends of the extrusion: the cross-section is concave at the foot of the riser, so each end is two convex quads.
END_QUADS = [[(0.0, 0.0), (0.2, 0.0), (FACE_FRONT, TOP), (FACE_BACK, TOP)],
             [(0.0, 0.0), (FACE_BACK, TOP), (0.075, 0.575), (0.0, 0.575)]]

MATERIALS = ["face", "paint", "bezel", "back", "kick", "cheek", "hood", "apron", "cabinet", "annunciator",
             "annunciator_lit", "vent"]

# The mounting face as PanelGeometry puts the grid on it: (lx, y, f) of grid cells (0,0), (16,0), (16,6), (0,6).
FRAME_CORNERS = [(0, TOP, FACE_BACK), (1, TOP, FACE_BACK), (1, TOP, FACE_FRONT), (0, TOP, FACE_FRONT)]

EPS = 1e-9


# ---------------------------------------------------------------------------------------------------------------
# The owner's OBJ files, checked against the profile

def verify_source(folder):
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

    for name, width in (("Control Desk 1m.obj", 100.0), ("Control Desk.obj", 200.0)):
        count = cap = faces = 0
        lo, hi = [1e18] * 3, [-1e18] * 3
        points = []
        with open(os.path.join(folder, name), encoding="utf-8") as fh:
            for line in fh:
                if line.startswith("f "):
                    faces += 1
                if line.startswith("v "):
                    points.append(tuple(float(v) for v in line.split()[1:4]))
        for x, y, z in points:
            count += 1
            for i, v in enumerate((x, y, z)):
                lo[i], hi[i] = min(lo[i], v), max(hi[i], v)
        for x, y, z in points:
            p = (y, z)
            d = min(dist(p, SOURCE_PROFILE[i], SOURCE_PROFILE[(i + 1) % len(SOURCE_PROFILE)])
                    for i in range(len(SOURCE_PROFILE)))
            if d > 1e-3:
                if not ((abs(x - lo[0]) < 1e-6 or abs(x - hi[0]) < 1e-6) and inside(p)):
                    raise AssertionError("%s: vertex %r is %.3f off the profile and not on an end cap" % (name, (x, y, z), d))
                cap += 1
        if abs(lo[0] + width) > 1e-6 or abs(hi[0]) > 1e-6:
            raise AssertionError("%s: x runs %g..%g, not -%g..0" % (name, lo[0], hi[0], width))
        print("SOURCE %s: %d vertices, %d triangles, x %g..%g, y %g..%g, z %g..%g; all on the profile outline "
              "(%d inside an end cap)" % (name, count, faces, lo[0], hi[0], lo[1], hi[1], lo[2], hi[2], cap))


# ---------------------------------------------------------------------------------------------------------------
# The piece

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


def polygons(shift=0.0, left_end=True, right_end=True):
    """One block of desk with its left edge at lx = shift: (material, [local (lx, y, f)], [uv], outward direction)."""
    lx1 = 1.0
    polys = []
    n = len(PROFILE)
    for i in range(n):
        a, b = PROFILE[i], PROFILE[(i + 1) % n]
        df, dy = b[0] - a[0], b[1] - a[1]
        length = math.hypot(df, dy)
        # the loop runs clockwise in (f, y), so the outward normal of an edge is (dy, -df) there: (lx, y, f) = (0, -df, dy)
        out = (0.0, -df / length, dy / length)
        if abs(a[1] - TOP) < EPS and abs(b[1] - TOP) < EPS:
            mat = "face"
        elif abs(a[0]) < EPS and abs(b[0]) < EPS:
            mat = "back"
        elif abs(a[1]) < EPS and abs(b[1]) < EPS:
            mat = "kick"           # the floor
        elif abs(a[1] - b[1]) < EPS:
            mat = "bezel"          # the top of the back shelf
        elif b[1] > a[1] and b[0] > a[0]:
            mat = "kick"           # the sloped front, in the shadow of the overhang
        else:
            mat = "paint"          # the riser
        pts = [(shift, a[1], a[0]), (shift + lx1, a[1], a[0]), (shift + lx1, b[1], b[0]), (shift, b[1], b[0])]
        if mat == "face":
            # the panel face texture over the top: across, and from the back edge (texture top) to the front edge
            va = 1.0 - (a[0] - FACE_BACK) / (FACE_FRONT - FACE_BACK)
            vb = 1.0 - (b[0] - FACE_BACK) / (FACE_FRONT - FACE_BACK)
            uv = [(0.0, va), (lx1, va), (lx1, vb), (0.0, vb)]
        elif abs(dy) < EPS:
            uv = [(0.0, a[0]), (lx1, a[0]), (lx1, b[0]), (0.0, b[0])]
        else:
            uv = [(0.0, a[1]), (lx1, a[1]), (lx1, b[1]), (0.0, b[1])]
        polys.append((mat, pts, uv, out))
    for lx, out, wanted in ((shift, (-1.0, 0.0, 0.0), left_end), (shift + lx1, (1.0, 0.0, 0.0), right_end)):
        for quad in END_QUADS if wanted else []:
            polys.append(("bezel", [(lx, y, f) for f, y in quad], [(f, y) for f, y in quad], out))
    return polys


def orient(polys):
    """Wind every polygon counter-clockwise seen from outside (along its outward direction)."""
    out = []
    for mat, pts, uv, direction in polys:
        n = newell([world(p) for p in pts])
        want = (-direction[0], direction[1], -direction[2])      # the local direction in world space
        if n[0] * want[0] + n[1] * want[1] + n[2] * want[2] < 0:
            pts, uv = pts[::-1], uv[::-1]
        out.append((mat, pts, uv))
    return out


def check_face(polys):
    faces = [pts for mat, pts, _ in polys if mat == "face"]
    if len(faces) != 1 or len(faces[0]) != 4:
        raise AssertionError("%d 'face' polygons" % len(faces))
    got = {tuple(round(c, 6) for c in p) for p in faces[0]}
    if got != {tuple(round(float(c), 6) for c in p) for p in FRAME_CORNERS}:
        raise AssertionError("the face %s is not on the mounting grid %s" % (sorted(got), FRAME_CORNERS))
    for mat, pts, _ in polys:
        for lx, y, f in pts:
            # nothing in front of the plane: the normal is straight up, so only the back shelf and riser rise above it,
            # and they are behind the face (f <= FACE_BACK)
            if y > TOP + EPS and f > FACE_BACK + EPS:
                raise AssertionError("%s reaches (%s, %s, %s) above the face" % (mat, lx, y, f))


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
        "# Generated by art/models/control_desk/build_desk.py (Blender)\n" + "\n".join(lines) + "\n")
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


def preview(materials, polys):
    """The 2 m desk (two blocks) from the front three-quarter, the side and from above."""
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
    to_mesh("left", polys, materials)
    to_mesh("right", polys, materials, (1.0, 0.0, 0.0))
    cam = bpy.data.objects.new("cam", bpy.data.cameras.new("cam"))
    scene.collection.objects.link(cam)
    scene.camera = cam
    cam.data.type = "ORTHO"
    cam.data.ortho_scale = 3.2
    centre = Vector((1.0, -0.25, 0.3))                  # Blender +Y is the operator's side (block -Z)
    for view, direction in (("front34", (-0.7, 1.0, 0.6)), ("side", (-1.0, 0.0, 0.0)), ("top", (0.0, 0.05, 1.0))):
        d = Vector(direction).normalized()
        cam.location = centre + d * 20.0
        cam.rotation_euler = (centre - cam.location).to_track_quat("-Z", "Y").to_euler()
        scene.render.filepath = os.path.join(PREVIEW, "desk-%s.png" % view)
        bpy.ops.render.render(write_still=True)


def main():
    args = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    if "--verify-source" in args:
        verify_source(args[args.index("--verify-source") + 1])
    clear_scene()
    materials = make_materials()
    one = orient(polygons())
    check_face(one)
    report = {"desk": export(to_mesh("desk", one, materials), "desk")}
    # the item models are centred on the middle of their cube (the gui and hand transforms turn them about it): the
    # desk is 0.575 high and stands at the back of its block, so lift it and bring it forward to the centre
    centre = (0.0, 0.5 - 0.575 / 2.0, -(0.75 - 0.5))
    report["desk_item"] = export(to_mesh("desk_item", one, materials, centre), "desk_item")
    # the 2 m desk: both blocks, centred on the item's block (x -0.5 .. 1.5)
    two = [(mat, [(lx - 0.5, y, f) for lx, y, f in pts], uv)
           for mat, pts, uv in orient(polygons(0.0, True, False) + polygons(1.0, False, True))]
    report["desk_wide_item"] = export(to_mesh("desk_wide_item", two, materials, centre), "desk_wide_item")
    for obj in list(bpy.data.objects):
        bpy.data.objects.remove(obj, do_unlink=True)
    if "--no-preview" not in args:
        preview(materials, one)
    return report


result = main()
print("FACES", result)
